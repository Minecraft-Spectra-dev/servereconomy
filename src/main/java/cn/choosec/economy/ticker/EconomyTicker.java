package cn.choosec.economy.ticker;

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
     * cutting per-flying-player write amplification ~5x.
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
            BigDecimal accrued = pendingFlight.merge(uuid, perSecond, BigDecimal::add);
            pendingFlightName.put(uuid, p.getName().getString());
            // Cut flight off the moment the accrued cost reaches the balance,
            // rather than waiting up to FLIGHT_BILL_FLUSH_SECONDS for the flush.
            BigDecimal balance = EconomyService.getBalance(uuid, p.getName().getString());
            if (accrued.compareTo(balance) >= 0) {
                p.sendSystemMessage(MessageUtil.parse("&c余额不足，已禁用飞行"));
                disableFlight(p);
            }
        }
        if (tickCount % (FLIGHT_BILL_FLUSH_SECONDS * 20L) == 0) {
            flushFlight(server);
        }
    }

    /** Write accumulated flight costs for all pending players to the database. */
    private static void flushFlight(MinecraftServer server) {
        if (pendingFlight.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, BigDecimal> e : pendingFlight.entrySet()) {
            UUID uuid = e.getKey();
            BigDecimal total = e.getValue();
            String name = pendingFlightName.get(uuid);
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (EconomyService.remove(uuid, name, total, "flight") == null) {
                if (p != null && (p.getAbilities().mayfly || p.getAbilities().flying)) {
                    p.sendSystemMessage(MessageUtil.parse("&c余额不足，已禁用飞行"));
                    disableFlight(p);
                }
            }
        }
        pendingFlight.clear();
        pendingFlightName.clear();
    }

    /** Flush a single player's accrued flight cost (e.g. on disconnect). */
    public static void flushFlight(UUID uuid) {
        BigDecimal total = pendingFlight.remove(uuid);
        if (total != null) {
            String name = pendingFlightName.remove(uuid);
            EconomyService.remove(uuid, name, total, "flight");
        }
    }

    /** Flush all accrued flight costs (e.g. at server stop). */
    public static void flushAllFlight(MinecraftServer server) {
        flushFlight(server);
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

    /** Hourly billing for the excess fake players (from Carpet detection). */
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
            boolean success = EconomyService.remove(owner, null, cost, "fake-player billing") != null;
            ServerPlayer p = server.getPlayerList().getPlayer(owner);
            if (p != null) {
                p.sendSystemMessage(MessageUtil.parse(
                        success ? "&c假人扣费 &e" + MoneyUtil.format(cost)
                                : "&c余额不足，假人费用 &e" + MoneyUtil.format(cost) + " &c未支付"));
            }
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
