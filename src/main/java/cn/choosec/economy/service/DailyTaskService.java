package cn.choosec.economy.service;

import cn.choosec.economy.ServerEconomy;
import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.util.MessageUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daily task system: each player independently gets a set of tasks each real day,
 * drawn from the admin-defined task pool. No more global tasks. A scoreboard
 * shows the player's daily tasks.
 */
public final class DailyTaskService {

    public record Daily(int taskId, String type, String target, int amount, BigDecimal reward,
                        int progress, boolean completed, boolean rewardClaimed) {
    }

    /** Result of an admin progress adjustment on a daily task. */
    public record ProgressResult(boolean found, boolean completed, boolean paidNow, int progress, int amount) {
    }

    private DailyTaskService() {
    }

    /** Cache of daily-task lists keyed by "uuid|date"; invalidated on any assignment/progress change. */
    private static final Map<String, List<Daily>> DAILY_CACHE = new ConcurrentHashMap<>();

    private static void invalidateDaily(UUID uuid, String date) {
        DAILY_CACHE.remove(uuid + "|" + date);
    }

    /**
     * In-memory progress accumulator. Gameplay events only merge a count into
     * this map on the server thread; a scheduled database worker task applies
     * the accumulated counts in batches, so block breaks / kills / item uses
     * never perform JDBC I/O on the server thread.
     */
    private record PendingProgress(UUID uuid, String date, String type, String target,
                                   String playerName, long count) {
    }

    private static final Object PROGRESS_LOCK = new Object();
    private static final Map<String, PendingProgress> PENDING_PROGRESS = new HashMap<>();
    private static final AtomicBoolean PROGRESS_FLUSH_SCHEDULED = new AtomicBoolean(false);
    /** The currently scheduled/running batch flush, so server stop can wait for it. */
    private static final AtomicReference<CompletableFuture<Void>> ACTIVE_PROGRESS_FLUSH = new AtomicReference<>();

    /** Drop cached assignments for every player on a date (daily rollover cleanup). */
    public static void clearDate(String date) {
        if (date == null || date.isEmpty()) {
            return;
        }
        String suffix = "|" + date;
        DAILY_CACHE.keySet().removeIf(k -> k.endsWith(suffix));
    }

    /** Drop the entire daily cache (used when a pool task is deleted, which removes its rows). */
    public static void invalidateAll() {
        DAILY_CACHE.clear();
    }

    public static String today() {
        return java.time.LocalDate.now().toString();
    }

    /** Make sure the player has today's assigned tasks (draw from pool if absent). */
    public static synchronized void ensureDaily(ServerPlayer p, String date) {
        ensureDaily(p.getUUID(), date);
    }

    /** UUID-based overload so admin commands can manage a specific player by UUID. */
    public static synchronized void ensureDaily(UUID uuid, String date) {
        ensureDailyDirect(uuid, date, ConfigManager.get().tasks.dailyCount);
    }

    /** Monitor-free variant for the database worker; it must not enter a service monitor while flushing. */
    private static void ensureDailyDirect(UUID uuid, String date, int count) {
        // Fast path: if today's assignments are already cached and non-empty, the
        // database has them too, so high-frequency events (mine/kill/use) skip a
        // COUNT(*) round-trip. Empty cached lists keep re-checking so a task added
        // to the pool later in the day can still be drawn.
        List<Daily> cached = DAILY_CACHE.get(uuid + "|" + date);
        if (cached != null && !cached.isEmpty()) {
            return;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM daily_tasks WHERE player = ? AND date = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, date);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return;
                    }
                }
            }
            int n = Math.max(1, count);
            List<Integer> pool = new ArrayList<>();
            String orderBy = DatabaseManager.isMySQL() ? "RAND()" : "RANDOM()";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM tasks WHERE enabled = 1 ORDER BY " + orderBy + " LIMIT ?")) {
                ps.setInt(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pool.add(rs.getInt(1));
                    }
                }
            }
            String insSql = DatabaseManager.isMySQL()
                    ? "INSERT IGNORE INTO daily_tasks (player, date, task_id, progress, completed, reward_claimed) VALUES (?, ?, ?, 0, 0, 0)"
                    : "INSERT OR IGNORE INTO daily_tasks (player, date, task_id, progress, completed, reward_claimed) VALUES (?, ?, ?, 0, 0, 0)";
            try (PreparedStatement ins = c.prepareStatement(insSql)) {
                for (int tid : pool) {
                    ins.setString(1, uuid.toString());
                    ins.setString(2, date);
                    ins.setInt(3, tid);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        invalidateDaily(uuid, date);
    }

    /** The player's assigned tasks for a given date. */
    public static synchronized List<Daily> getDaily(UUID uuid, String date) {
        String key = uuid + "|" + date;
        List<Daily> cached = DAILY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            List<Daily> out = loadDailyDirect(uuid, date);
            DAILY_CACHE.put(key, List.copyOf(out));
            return out;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return List.of();
        }
    }

    /** Monitor-free DB load used by the async progress flush (must not contend with {@code getDaily}). */
    private static List<Daily> loadDailyDirect(UUID uuid, String date) throws SQLException {
        List<Daily> out = new ArrayList<>();
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT t.id, t.type, t.target, t.amount, t.reward, d.progress, d.completed, d.reward_claimed
                     FROM daily_tasks d JOIN tasks t ON t.id = d.task_id
                     WHERE d.player = ? AND d.date = ? ORDER BY t.id""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Daily(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                            rs.getBigDecimal(5), rs.getInt(6), rs.getInt(7) == 1, rs.getInt(8) == 1));
                }
            }
        }
        return out;
    }

    /** Re-roll: delete the player's tasks for the date, then draw a fresh set at the configured count. */
    public static synchronized int refresh(UUID uuid, String date) {
        return refresh(uuid, date, ConfigManager.get().tasks.dailyCount);
    }

    /** Re-roll with a specific task count; {@code count <= 0} clears all of the player's tasks for the date. */
    public static synchronized int refresh(UUID uuid, String date, int count) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM daily_tasks WHERE player = ? AND date = ?")) {
                del.setString(1, uuid.toString());
                del.setString(2, date);
                del.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
        invalidateDaily(uuid, date);
        if (count > 0) {
            ensureDailyDirect(uuid, date, count);
        }
        return getDaily(uuid, date).size();
    }

    /** Add a specific pool task to the player's daily tasks. False if the task doesn't exist or is already assigned. */
    public static synchronized boolean addTask(UUID uuid, String date, int taskId) {
        TaskService.Task t = TaskService.getTask(taskId);
        if (t == null || !t.enabled()) {
            return false;
        }
        try (Connection c = DatabaseManager.open()) {
            String insSql = DatabaseManager.isMySQL()
                    ? "INSERT IGNORE INTO daily_tasks (player, date, task_id, progress, completed, reward_claimed) VALUES (?, ?, ?, 0, 0, 0)"
                    : "INSERT OR IGNORE INTO daily_tasks (player, date, task_id, progress, completed, reward_claimed) VALUES (?, ?, ?, 0, 0, 0)";
            try (PreparedStatement ins = c.prepareStatement(insSql)) {
                ins.setString(1, uuid.toString());
                ins.setString(2, date);
                ins.setInt(3, taskId);
                boolean added = ins.executeUpdate() > 0;
                if (added) {
                    invalidateDaily(uuid, date);
                }
                return added;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /** Remove a task from the player's daily tasks. */
    public static synchronized boolean removeTask(UUID uuid, String date, int taskId) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM daily_tasks WHERE player = ? AND date = ? AND task_id = ?")) {
                del.setString(1, uuid.toString());
                del.setString(2, date);
                del.setInt(3, taskId);
                boolean removed = del.executeUpdate() > 0;
                if (removed) {
                    invalidateDaily(uuid, date);
                }
                return removed;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /**
     * Adjust a daily task's progress by a signed delta (admin command).
     * Reaching the required amount completes the task and pays the reward once;
     * dropping below the requirement un-completes it (already paid rewards are not clawed back).
     */
    public static synchronized ProgressResult adjustProgress(UUID uuid, String date, int taskId, int delta) {
        TaskService.Task t = TaskService.getTask(taskId);
        if (t == null) {
            return new ProgressResult(false, false, false, 0, 0);
        }
        try (Connection c = DatabaseManager.open()) {
            int cur;
            boolean rewardClaimed = false;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT progress, completed, reward_claimed FROM daily_tasks WHERE player = ? AND date = ? AND task_id = ?")) {
                sel.setString(1, uuid.toString());
                sel.setString(2, date);
                sel.setInt(3, taskId);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) {
                        return new ProgressResult(false, false, false, 0, t.amount());
                    }
                    cur = rs.getInt(1);
                    rewardClaimed = rs.getInt(3) == 1;
                }
            }
            int target = Math.max(0, Math.min(t.amount(), cur + delta));
            boolean nowComplete = target >= t.amount();
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE daily_tasks SET progress = ?, completed = ?, reward_claimed = ? WHERE player = ? AND date = ? AND task_id = ?")) {
                up.setInt(1, target);
                up.setInt(2, nowComplete ? 1 : 0);
                // keep reward_claimed sticky once paid, so un-completing and re-completing does not pay twice
                up.setInt(3, nowComplete ? 1 : (rewardClaimed ? 1 : 0));
                up.setString(4, uuid.toString());
                up.setString(5, date);
                up.setInt(6, taskId);
                up.executeUpdate();
            }
            invalidateDaily(uuid, date);
            boolean paidNow = false;
            if (nowComplete && !rewardClaimed) {
                EconomyService.add(uuid, null, t.reward(), "daily-task:" + taskId);
                paidNow = true;
            }
            return new ProgressResult(true, nowComplete, paidNow, target, t.amount());
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return new ProgressResult(false, false, false, 0, t.amount());
        }
    }

    /**
     * Credit progress for an action matching a task type/target.
     *
     * <p>This is a pure in-memory merge on the server thread: the event is
     * coalesced with other events of the same player/date/type/target and a
     * database worker applies the accumulated count later. Scoreboards refresh
     * from {@link #DAILY_CACHE} on the next tick, after the batch flush reloads
     * the affected entries.
     */
    public static void addProgress(ServerPlayer p, String type, String target) {
        if (p == null || type == null || type.isEmpty()) {
            return;
        }
        UUID uuid = p.getUUID();
        String date = today();
        String key = progressKey(uuid, date, type, target);
        synchronized (PROGRESS_LOCK) {
            PENDING_PROGRESS.merge(key, new PendingProgress(uuid, date, type, target, p.getName().getString(), 1L),
                    (old, added) -> new PendingProgress(uuid, date, type, target, old.playerName(), old.count() + added.count()));
        }
        scheduleProgressFlush();
    }

    /** Ask the database worker to drain queued progress, if any is pending. */
    public static void flushProgressAsync() {
        boolean pending;
        synchronized (PROGRESS_LOCK) {
            pending = !PENDING_PROGRESS.isEmpty();
        }
        if (pending) {
            scheduleProgressFlush();
        }
    }

    /** Synchronously drain queued progress and wait for any in-flight batch (server stop). */
    public static void flushProgressSync() {
        Map<String, PendingProgress> batch;
        synchronized (PROGRESS_LOCK) {
            batch = new HashMap<>(PENDING_PROGRESS);
            PENDING_PROGRESS.clear();
        }
        if (!batch.isEmpty()) {
            try {
                DatabaseManager.callBlocking(() -> {
                    applyProgressBatch(batch);
                    return null;
                });
            } catch (SQLException e) {
                DatabaseManager.log(e);
            }
        }
        CompletableFuture<Void> active = ACTIVE_PROGRESS_FLUSH.get();
        if (active != null) {
            try {
                active.join();
            } catch (RuntimeException e) {
                ServerEconomy.LOGGER.warn("[ServerEconomy] Failed to flush queued daily progress at server stop", e);
            }
        }
    }

    private static String progressKey(UUID uuid, String date, String type, String target) {
        return uuid + "|" + date + "|" + type + "|" + target;
    }

    private static void scheduleProgressFlush() {
        if (!PROGRESS_FLUSH_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture<Void> flush = DatabaseManager.runAsync(() -> {
            PROGRESS_FLUSH_SCHEDULED.set(false);
            drainAndApplyPendingProgress();
        });
        ACTIVE_PROGRESS_FLUSH.set(flush);
        flush.whenComplete((ignored, error) -> {
            ACTIVE_PROGRESS_FLUSH.compareAndSet(flush, null);
            if (error != null) {
                ServerEconomy.LOGGER.warn("[ServerEconomy] Daily progress flush failed", error);
            }
        });
    }

    private static void drainAndApplyPendingProgress() {
        Map<String, PendingProgress> batch;
        synchronized (PROGRESS_LOCK) {
            if (PENDING_PROGRESS.isEmpty()) {
                return;
            }
            batch = new HashMap<>(PENDING_PROGRESS);
            PENDING_PROGRESS.clear();
        }
        applyProgressBatch(batch);
    }

    /** One matching daily-task row loaded for a progress batch. */
    private record ProgressRow(int taskId, int progress, int rewardClaimed, int amount, BigDecimal reward) {
    }

    /** Apply accumulated progress counts to the database, then refresh affected caches. */
    private static void applyProgressBatch(Map<String, PendingProgress> batch) {
        Set<String> affected = new java.util.HashSet<>();
        for (PendingProgress event : batch.values()) {
            UUID uuid = event.uuid();
            String date = event.date();
            try {
                ensureDailyDirect(uuid, date, ConfigManager.get().tasks.dailyCount);
                try (Connection c = DatabaseManager.open()) {
                    List<ProgressRow> rows = new ArrayList<>();
                    try (PreparedStatement sel = c.prepareStatement("""
                            SELECT d.task_id, d.progress, d.reward_claimed, t.amount, t.reward
                            FROM daily_tasks d JOIN tasks t ON t.id = d.task_id
                            WHERE d.player = ? AND d.date = ? AND t.type = ? AND t.target = ? AND d.completed = 0""")) {
                        sel.setString(1, uuid.toString());
                        sel.setString(2, date);
                        sel.setString(3, event.type());
                        sel.setString(4, event.target());
                        try (ResultSet rs = sel.executeQuery()) {
                            while (rs.next()) {
                                rows.add(new ProgressRow(rs.getInt(1), rs.getInt(2), rs.getInt(3),
                                        rs.getInt(4), rs.getBigDecimal(5)));
                            }
                        }
                    }
                    for (ProgressRow row : rows) {
                        int newProgress = (int) Math.min(row.amount(), row.progress() + event.count());
                        boolean nowComplete = newProgress >= row.amount();
                        try (PreparedStatement up = c.prepareStatement("""
                                UPDATE daily_tasks SET progress = ?, completed = ?
                                WHERE player = ? AND date = ? AND task_id = ?""")) {
                            up.setInt(1, newProgress);
                            up.setInt(2, nowComplete ? 1 : 0);
                            up.setString(3, uuid.toString());
                            up.setString(4, date);
                            up.setInt(5, row.taskId());
                            up.executeUpdate();
                        }
                        if (nowComplete && row.rewardClaimed() == 0) {
                            BigDecimal reward = row.reward();
                            String currency = ConfigManager.get().currencyAbbreviation;
                            if (EconomyService.add(uuid, event.playerName(), reward, "daily-task:" + row.taskId()) != null) {
                                try (PreparedStatement claim = c.prepareStatement("""
                                        UPDATE daily_tasks SET reward_claimed = 1
                                        WHERE player = ? AND date = ? AND task_id = ?""")) {
                                    claim.setString(1, uuid.toString());
                                    claim.setString(2, date);
                                    claim.setInt(3, row.taskId());
                                    claim.executeUpdate();
                                }
                                ServerEconomy.runOnServer(() -> {
                                    MinecraftServer server = ServerEconomy.getServer();
                                    ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(uuid);
                                    if (online != null) {
                                        online.sendSystemMessage(MessageUtil.parse("&a完成每日任务，获得 &e"
                                                + MoneyUtil.format(reward) + " " + currency));
                                    }
                                });
                            }
                        }
                    }
                }
                affected.add(uuid + "|" + date);
            } catch (SQLException | RuntimeException e) {
                DatabaseManager.log(e instanceof SQLException se ? se
                        : new SQLException("Failed to flush daily progress for " + uuid + "/" + date, e));
            }
        }
        for (String key : affected) {
            int sep = key.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(key.substring(0, sep));
                String date = key.substring(sep + 1);
                DAILY_CACHE.put(key, List.copyOf(loadDailyDirect(uuid, date)));
            } catch (IllegalArgumentException | SQLException e) {
                // leave the stale entry out; the next getDaily reloads it
            }
        }
    }

    /** A scoreboard row: its display component and the sorting score. */
    private record ScoreRow(Component display, int score) {
    }

    /** Last sent score rows per player, for diffing. */
    private static final Map<UUID, Map<String, ScoreRow>> lastRows = new ConcurrentHashMap<>();

    /** Whether the per-player objective has been sent (ADD) on the current connection. */
    private static final Set<UUID> objectiveAdded = ConcurrentHashMap.newKeySet();

    /** Called when a player disconnects, to clear per-connection state. */
    public static void onPlayerQuit(UUID uuid) {
        objectiveAdded.remove(uuid);
        lastRows.remove(uuid);
    }

    /** Render the player's own daily tasks on their sidebar via direct packets (per-player, real-time). */
    public static void updateScoreboard(ServerPlayer p, String date) {
        try {
            UUID uuid = p.getUUID();
            // Load the configured scoreboard language's translations server-side
            // (no-op after the first call) for server-side scoreboard resolution.
            cn.choosec.economy.util.TaskNames.load(p.level().getServer());
            net.minecraft.world.scores.Scoreboard sb = p.level().getServer().getScoreboard();
            String name = "eco_daily_" + uuid.toString().substring(0, 8);
            boolean zh = "zh_cn".equalsIgnoreCase(ConfigManager.get().scoreboardLanguage);
            net.minecraft.world.scores.Objective obj = new net.minecraft.world.scores.Objective(
                    sb, name, ObjectiveCriteria.DUMMY,
                    Component.literal(zh ? "个人信息" : "Profile"),
                    ObjectiveCriteria.RenderType.INTEGER, false, null);
            // ADD (0) only once per connection, then UPDATE (2) — avoids client "already exists" error
            int mode = objectiveAdded.contains(uuid) ? 2 : 0;
            p.connection.send(new ClientboundSetObjectivePacket(obj, mode));
            objectiveAdded.add(uuid);

            String currency = ConfigManager.get().currencyAbbreviation;
            LinkedHashMap<String, ScoreRow> newRows = new LinkedHashMap<>();
            // 个人信息区块：货币（绿） + 数量/单位（黄）
            newRows.put("balance", new ScoreRow(MessageUtil.parse(
                    "&a" + (zh ? "货币" : "Balance")
                            + " &e" + MoneyUtil.format(EconomyService.getBalance(uuid, p.getName().getString()))
                            + " " + currency), 99));
            // 每日任务区块
            newRows.put("header_daily", new ScoreRow(MessageUtil.parse(
                    "&6" + (zh ? "每日任务" : "Daily Tasks")), 98));
            List<Daily> dailies = getDaily(uuid, date);
            if (dailies.isEmpty()) {
                newRows.put("task_empty", new ScoreRow(MessageUtil.parse(
                        "&7" + (zh ? "今日无任务" : "No tasks today")), 97));
            } else {
                int i = 1;
                for (Daily d : dailies) {
                    // Hybrid: the mod task-type key (servereconomy.task.type.*) is resolved
                    // to text server-side (a vanilla client has no mod lang file and would
                    // otherwise show the raw key), while the vanilla item/block/entity name
                    // stays a translatable key that the client renders from its own lang.
                    Style taskStyle = d.completed()
                            ? Style.EMPTY.withColor(ChatFormatting.GRAY).withStrikethrough(true)
                            : Style.EMPTY.withColor(ChatFormatting.GREEN);
                    Style rewardStyle = d.completed()
                            ? Style.EMPTY.withColor(ChatFormatting.GRAY).withStrikethrough(true)
                            : Style.EMPTY.withColor(ChatFormatting.YELLOW);
                    MutableComponent line = Component.empty()
                            .append(Component.literal(i + ". ").withStyle(taskStyle))
                            .append(cn.choosec.economy.util.TaskNames.taskComponent(d.type(), d.target(), zh, taskStyle));
                    if (!d.completed()) {
                        line.append(Component.literal(" " + d.progress() + "/" + d.amount()).withStyle(taskStyle));
                    }
                    line.append(Component.literal(" " + MoneyUtil.format(d.reward()) + " " + currency)
                            .withStyle(rewardStyle));
                    newRows.put("task_" + d.taskId(), new ScoreRow(line, 97 - (i - 1)));
                    i++;
                }
            }

            Map<String, ScoreRow> old = lastRows.getOrDefault(uuid, new HashMap<>());
            for (String k : old.keySet()) {
                if (!newRows.containsKey(k)) {
                    p.connection.send(new ClientboundResetScorePacket(k, name));
                }
            }
            for (Map.Entry<String, ScoreRow> e : newRows.entrySet()) {
                ScoreRow nr = e.getValue();
                ScoreRow or = old.get(e.getKey());
                if (or == null || or.score() != nr.score() || !or.display().equals(nr.display())) {
                    p.connection.send(new ClientboundSetScorePacket(e.getKey(), name, nr.score(),
                            Optional.of(nr.display()), Optional.empty()));
                }
            }
            lastRows.put(uuid, new LinkedHashMap<>(newRows));
            p.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, obj));
        } catch (Exception e) {
            // scoreboard is best-effort
        }
    }
}
