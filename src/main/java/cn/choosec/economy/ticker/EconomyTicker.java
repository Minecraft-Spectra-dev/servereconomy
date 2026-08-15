package cn.choosec.economy.ticker;

import cn.choosec.economy.ServerEconomy;
import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.service.PreservedService;
import cn.choosec.economy.util.MessageUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Periodic server-side billing:
 * <ul>
 *   <li>flight — deducted in real time (every second) at the configured per-second rate</li>
 *   <li>fake players (Carpet) — free allowance, hourly fee for each extra dummy, plus a
 *       prompt when a player exceeds the free allowance</li>
 * </ul>
 */
public final class EconomyTicker {

    /** Fake-player billing runs once per this many in-game seconds (fixed hourly). */
    private static final int FAKE_PLAYER_BILL_INTERVAL_SECONDS = 3600;
    private static long tickCount = 0;
    private static long lastBillSecond = 0;
    private static final Map<UUID, Integer> lastFakeNotify = new HashMap<>();
    private static String lastDay = "";

    /** Per-second flight costs accumulate in memory and flush to the DB every N seconds. */
    private static final int FLIGHT_BILL_FLUSH_SECONDS = 5;
    private static final Map<UUID, BigDecimal> pendingFlight = new ConcurrentHashMap<>();
    private static final Map<UUID, String> pendingFlightName = new ConcurrentHashMap<>();

    private EconomyTicker() {
    }

    public static void onTick(MinecraftServer server) {
        tickCount++;
        if (tickCount % 20 != 0) {
            return;
        }
        long nowSec = server.overworld().getGameTime() / 20;
        if (lastBillSecond == 0) {
            lastBillSecond = nowSec;
        }

        billFlightPerSecond(server);
        checkFakePlayerPrompt(server);
        checkReachTasks(server);
        checkDailyRollover(server);
        // Daily progress is accumulated in memory by gameplay events and applied
        // in batches on the database worker; nudge a flush here as a safety net.
        cn.choosec.economy.service.DailyTaskService.flushProgressAsync();
        // Refresh per-player daily-task scoreboards and the tab list every second.
        // Both read from in-memory caches (balance + daily tasks), so this no longer
        // touches the database and is cheap enough to run every tick-second.
        String today = cn.choosec.economy.service.DailyTaskService.today();
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            cn.choosec.economy.service.DailyTaskService.updateScoreboard(p, today);
        }
        boolean tabConfigured = PreservedService.headerText != null && !PreservedService.headerText.isEmpty()
                || PreservedService.footerText != null && !PreservedService.footerText.isEmpty();
        if (tabConfigured) {
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                PreservedService.updateTabForPlayer(p);
            }
        }

        int interval = FAKE_PLAYER_BILL_INTERVAL_SECONDS;
        if (nowSec - lastBillSecond >= interval) {
            lastBillSecond = nowSec;
            billFakePlayers(server);
        }
    }

    private static boolean isFlying(ServerPlayer p) {
        try {
            return p.getAbilities().flying && p.getAbilities().mayfly;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Real-time flight billing at the configured per-second rate.
     * Costs are accumulated in memory and flushed to the database in a single
     * transaction-log write every {@link #FLIGHT_BILL_FLUSH_SECONDS} seconds,
     * cutting per-flying-player write amplification ~5x. Balance checks and the
     * periodic flush are asynchronous, so a stalled MySQL server cannot block
     * this tick handler.
     */
    private static void billFlightPerSecond(MinecraftServer server) {
        BigDecimal perSecond = ConfigManager.get().rates.flightPerSecond;
        if (perSecond == null || perSecond.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!isFlying(p) || p.isSpectator() || p.getAbilities().instabuild) {
                continue;
            }
            UUID uuid = p.getUUID();
            String name = p.getName().getString();
            BigDecimal accrued = pendingFlight.merge(uuid, perSecond, BigDecimal::add);
            pendingFlightName.put(uuid, name);
            // Cut flight off the moment the accrued cost reaches the balance,
            // rather than waiting up to FLIGHT_BILL_FLUSH_SECONDS for the flush.
            BigDecimal balance = EconomyService.getCachedBalance(uuid);
            if (balance != null) {
                if (accrued.compareTo(balance) >= 0) {
                    p.sendSystemMessage(MessageUtil.parse("&c余额不足，已禁用飞行"));
                    disableFlight(p);
                }
                continue;
            }
            EconomyService.getBalanceAsync(uuid, name).whenComplete((loaded, error) -> {
                if (error != null || loaded == null) {
                    return;
                }
                BigDecimal stillPending = pendingFlight.getOrDefault(uuid, BigDecimal.ZERO);
                if (stillPending.compareTo(loaded) >= 0) {
                    ServerEconomy.runOnServer(() -> {
                        ServerPlayer flying = server.getPlayerList().getPlayer(uuid);
                        if (flying != null && isFlying(flying) && !flying.isSpectator()
                                && !flying.getAbilities().instabuild) {
                            flying.sendSystemMessage(MessageUtil.parse("&c余额不足，已禁用飞行"));
                            disableFlight(flying);
                        }
                    });
                }
            });
        }
        if (tickCount % (FLIGHT_BILL_FLUSH_SECONDS * 20L) == 0) {
            flushFlight(server);
        }
    }

    /** Write accumulated flight costs for all pending players to the database asynchronously. */
    private static void flushFlight(MinecraftServer server) {
        if (pendingFlight.isEmpty()) {
            return;
        }
        Map<UUID, BigDecimal> batch = new HashMap<>(pendingFlight);
        Map<UUID, String> names = new HashMap<>(pendingFlightName);
        pendingFlight.clear();
        pendingFlightName.clear();
        for (Map.Entry<UUID, BigDecimal> e : batch.entrySet()) {
            UUID uuid = e.getKey();
            BigDecimal total = e.getValue();
            String name = names.get(uuid);
            EconomyService.removeAsync(uuid, name, total, "flight").whenComplete((newBalance, error) -> {
                if (error != null || newBalance == null) {
                    ServerEconomy.runOnServer(() -> {
                        ServerPlayer p = server.getPlayerList().getPlayer(uuid);
                        if (p != null && (p.getAbilities().mayfly || p.getAbilities().flying)) {
                            p.sendSystemMessage(MessageUtil.parse("&c余额不足，已禁用飞行"));
                            disableFlight(p);
                        }
                    });
                }
            });
        }
    }

    /** Flush a single player's accrued flight cost (e.g. on disconnect). */
    public static void flushFlight(UUID uuid) {
        BigDecimal total = pendingFlight.remove(uuid);
        if (total != null) {
            String name = pendingFlightName.remove(uuid);
            EconomyService.removeAsync(uuid, name, total, "flight");
        }
    }

    /**
     * Flush all accrued flight costs synchronously (e.g. at server stop), when
     * the tick watchdog is no longer running and we must not lose pending charges.
     */
    public static void flushAllFlight(MinecraftServer server) {
        if (pendingFlight.isEmpty()) {
            return;
        }
        Map<UUID, BigDecimal> batch = new HashMap<>(pendingFlight);
        Map<UUID, String> names = new HashMap<>(pendingFlightName);
        pendingFlight.clear();
        pendingFlightName.clear();
        for (Map.Entry<UUID, BigDecimal> e : batch.entrySet()) {
            try {
                EconomyService.removeAsync(e.getKey(), names.get(e.getKey()), e.getValue(), "flight").join();
            } catch (RuntimeException ex) {
                // Server is stopping; log and continue so other pending charges still flush.
                cn.choosec.economy.database.DatabaseManager.log(
                        new java.sql.SQLException("Failed to flush flight charge for " + e.getKey(), ex));
            }
        }
    }

    /** Prompt players who exceed the free fake-player allowance (e.g. when summoning the 2nd+). */
    private static void checkFakePlayerPrompt(MinecraftServer server) {
        int free = Math.max(0, ConfigManager.get().fakePlayers.freePerPlayer);
        BigDecimal hourly = ConfigManager.get().rates.fakePlayerHourly;
        for (Map.Entry<UUID, Integer> e : cn.choosec.economy.service.CarpetIntegration.fakeCounts(server).entrySet()) {
            UUID owner = e.getKey();
            int active = e.getValue();
            if (active <= free) {
                // player is back within the free allowance: drop the notification watermark
                lastFakeNotify.remove(owner);
                continue;
            }
            int last = lastFakeNotify.getOrDefault(owner, free);
            if (active > last) {
                ServerPlayer p = server.getPlayerList().getPlayer(owner);
                if (p != null) {
                    p.sendSystemMessage(MessageUtil.parse(
                            "&6假人 &e" + active + "&7(免费" + free + ")，超出按 &e" + MoneyUtil.format(hourly)
                                    + "/时/个 &7计费"));
                }
                lastFakeNotify.put(owner, active);
            }
        }
    }

    /** Hourly billing for the excess fake players (from Carpet detection), applied asynchronously. */
    private static void billFakePlayers(MinecraftServer server) {
        int free = Math.max(0, ConfigManager.get().fakePlayers.freePerPlayer);
        BigDecimal hourly = ConfigManager.get().rates.fakePlayerHourly;
        for (Map.Entry<UUID, Integer> e : cn.choosec.economy.service.CarpetIntegration.fakeCounts(server).entrySet()) {
            UUID owner = e.getKey();
            int extra = Math.max(0, e.getValue() - free);
            if (extra == 0) {
                continue;
            }
            BigDecimal cost = hourly.multiply(BigDecimal.valueOf(extra)).setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP);
            EconomyService.removeAsync(owner, null, cost, "fake-player billing").whenComplete((newBalance, error) -> {
                boolean success = error == null && newBalance != null;
                ServerEconomy.runOnServer(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(owner);
                    if (p != null) {
                        p.sendSystemMessage(MessageUtil.parse(
                                success ? "&c假人扣费 &e" + MoneyUtil.format(cost)
                                        : "&c余额不足，假人费用 &e" + MoneyUtil.format(cost) + " &c未支付"));
                    }
                });
            });
        }
    }

    /** Edge-triggered tracking for REACH tasks: player inside radius -> +1 progress per visit. */
    private static final Map<String, Boolean> reachInside = new HashMap<>();

    private static void checkReachTasks(MinecraftServer server) {
        String date = cn.choosec.economy.service.DailyTaskService.today();
        int radius = cn.choosec.economy.config.ConfigManager.get().rates.taskReachRadius;
        boolean any = false;
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            String playerDim = p.level().dimension().identifier().toString();
            for (cn.choosec.economy.service.DailyTaskService.Daily d :
                    cn.choosec.economy.service.DailyTaskService.getDaily(p.getUUID(), date)) {
                if (!"reach".equalsIgnoreCase(d.type()) || d.completed()) continue;
                any = true;
                Reached parsed = parseReachTarget(d.target());
                if (parsed == null) continue;
                if (!parsed.world.equals(playerDim)) continue;
                double dx = p.getX() - parsed.x;
                double dy = p.getY() - parsed.y;
                double dz = p.getZ() - parsed.z;
                boolean inside = (dx * dx + dy * dy + dz * dz) <= (double) radius * radius;
                String key = p.getUUID() + ":" + d.taskId();
                Boolean was = reachInside.get(key);
                if (inside && !Boolean.TRUE.equals(was)) {
                    cn.choosec.economy.service.DailyTaskService.addProgress(p, "reach", d.target());
                }
                reachInside.put(key, inside);
            }
        }
        if (!any && !reachInside.isEmpty()) {
            reachInside.clear();
        }
    }

    /** Parsed REACH target: world + coordinates. */
    private record Reached(String world, double x, double y, double z) {
    }

    /** Parse a REACH target of the form "world:x,y,z" into a {@link Reached}, or null. */
    private static Reached parseReachTarget(String target) {
        if (target == null) return null;
        int lastColon = target.lastIndexOf(':');
        if (lastColon <= 0 || lastColon >= target.length() - 1) return null;
        String world = target.substring(0, lastColon);
        String coords = target.substring(lastColon + 1);
        String[] parts = coords.split(",");
        if (parts.length != 3) return null;
        try {
            return new Reached(world,
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Re-assign daily tasks for online players when the real date changes. */
    private static void checkDailyRollover(MinecraftServer server) {
        String today = cn.choosec.economy.service.DailyTaskService.today();
        if (today.equals(lastDay)) {
            return;
        }
        String previous = lastDay;
        lastDay = today;
        cn.choosec.economy.service.DailyTaskService.clearDate(previous);
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            cn.choosec.economy.service.DailyTaskService.ensureDaily(p, today);
            cn.choosec.economy.service.DailyTaskService.updateScoreboard(p, today);
        }
    }

    private static void disableFlight(ServerPlayer p) {
        try {
            p.getAbilities().mayfly = false;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
        } catch (Exception e) {
            // best effort
        }
    }
}
