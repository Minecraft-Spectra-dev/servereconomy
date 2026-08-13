package cn.choosec.economy.economy;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core currency service — the "industry standard" style account ledger.
 *
 * <p>The API mirrors the conventional Vault-style economy interface
 * (has/get/set/add/remove/transfer) so it is familiar to server admins and
 * easy to bridge to other economy plugins. Balances are stored as fixed-precision
 * DECIMAL(20,2) values.
 */
public final class EconomyService {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(EconomyService.class);

    private EconomyService() {
    }

    /** In-memory balance cache, updated/invalidated on every mutation. */
    private static final Map<UUID, BigDecimal> BALANCE_CACHE = new ConcurrentHashMap<>();

    /** Listener notified with the new balance after every successful mutation. */
    public interface BalanceListener {
        void onBalanceChanged(UUID uuid, BigDecimal newBalance);
    }

    private static final List<BalanceListener> BALANCE_LISTENERS = new CopyOnWriteArrayList<>();

    /**
     * Register an event-driven hook fired synchronously after any successful
     * balance mutation (set/add/remove/transfer). This lets services disable
     * paid abilities immediately when a balance drops to zero, instead of
     * waiting for the next periodic billing check.
     */
    public static void addBalanceListener(BalanceListener listener) {
        if (listener != null) {
            BALANCE_LISTENERS.add(listener);
        }
    }

    private static void notifyBalanceChanged(UUID uuid, BigDecimal newBalance) {
        for (BalanceListener listener : BALANCE_LISTENERS) {
            try {
                listener.onBalanceChanged(uuid, newBalance);
            } catch (RuntimeException e) {
                LOGGER.warn("Balance listener failed", e);
            }
        }
    }

    /** Short-lived cache of distinct account names (for command suggestions). */
    private static volatile List<String> nameCache = null;
    private static volatile long nameCacheAt = 0L;
    private static final long NAME_CACHE_TTL_MS = 30_000L;

    /** Get a player's balance (read-only; does not create the account row). The {@code name} parameter is kept for API compatibility and ignored. */
    public static synchronized BigDecimal getBalance(UUID uuid, String name) {
        BigDecimal cached = BALANCE_CACHE.get(uuid);
        if (cached != null) {
            return cached;
        }
        try (Connection c = DatabaseManager.open()) {
            BigDecimal balance = queryBalance(c, uuid);
            BALANCE_CACHE.put(uuid, balance);
            return balance;
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return BigDecimal.ZERO.setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
    }

    /** Drop a cached balance so the next read hits the database (used after a failed transaction). */
    public static void invalidateCache(UUID uuid) {
        BALANCE_CACHE.remove(uuid);
    }

    /** Returns true if the account has at least {@code amount}. */
    public static synchronized boolean has(UUID uuid, BigDecimal amount) {
        return getBalance(uuid, null).compareTo(MoneyUtil.norm(amount)) >= 0;
    }

    /** Set the balance directly (admin operation; logs a transaction). */
    public static synchronized boolean set(UUID uuid, String name, BigDecimal balance, String note) {
        try (Connection c = DatabaseManager.open()) {
            ensureRowInternal(c, uuid, name);
            BigDecimal normalized = MoneyUtil.norm(balance);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET balance = ? WHERE uuid = ?")) {
                ps.setBigDecimal(1, normalized);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            logTransaction(c, uuid, "SET", normalized, note);
            BALANCE_CACHE.put(uuid, normalized);
            notifyBalanceChanged(uuid, normalized);
            return true;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /** Add money. Returns the new balance, or null on error. */
    public static synchronized BigDecimal add(UUID uuid, String name, BigDecimal amount, String note) {
        BigDecimal add = MoneyUtil.norm(amount);
        if (add.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        try (Connection c = DatabaseManager.open()) {
            ensureRowInternal(c, uuid, name);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET balance = balance + ? WHERE uuid = ?")) {
                ps.setBigDecimal(1, add);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            logTransaction(c, uuid, "DEPOSIT", add, note);
            BigDecimal newBal = queryBalance(c, uuid);
            BALANCE_CACHE.put(uuid, newBal);
            if (add.compareTo(BigDecimal.ZERO) > 0) {
                notifyBalanceChanged(uuid, newBal);
            }
            return newBal;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    /**
     * Remove money. Returns the new balance, or null if the account had
     * insufficient funds (nothing is changed in that case).
     */
    public static synchronized BigDecimal remove(UUID uuid, String name, BigDecimal amount, String note) {
        BigDecimal remove = MoneyUtil.norm(amount);
        if (remove.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        try (Connection c = DatabaseManager.open()) {
            ensureRowInternal(c, uuid, name);
            BigDecimal current = queryBalance(c, uuid);
            if (current.compareTo(remove) < 0) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET balance = balance - ? WHERE uuid = ?")) {
                ps.setBigDecimal(1, remove);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            logTransaction(c, uuid, "WITHDRAW", remove, note);
            BigDecimal newBal = queryBalance(c, uuid);
            BALANCE_CACHE.put(uuid, newBal);
            if (remove.compareTo(BigDecimal.ZERO) > 0) {
                notifyBalanceChanged(uuid, newBal);
            }
            return newBal;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    /**
     * Transfer money between two accounts, optionally charging the sender a
     * fee percentage that is credited to the server "bank" account.
     * Returns true on success.
     */
    public static synchronized boolean transfer(UUID from, String fromName,
                                                UUID to, String toName,
                                                BigDecimal amount, BigDecimal feePercent) {
        BigDecimal amt = MoneyUtil.norm(amount);
        if (amt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);
            try {
                ensureRowInternal(c, from, fromName);
                ensureRowInternal(c, to, toName);
                BigDecimal current = queryBalance(c, from);
                if (current.compareTo(amt) < 0) {
                    c.rollback();
                    return false;
                }
                BigDecimal fee = feePercent == null ? BigDecimal.ZERO : MoneyUtil.percent(amt, feePercent);
                BigDecimal receiver = amt.subtract(fee);

                ensureRowInternal(c, BANK_UUID, "bank");
                updateBalanceDelta(c, from, amt.negate());
                updateBalanceDelta(c, to, receiver);
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    updateBalanceDelta(c, BANK_UUID, fee);
                }

                logTransaction(c, from, "TRANSFER_OUT", amt, "to " + toName);
                logTransaction(c, to, "TRANSFER_IN", receiver, "from " + fromName + (fee.compareTo(BigDecimal.ZERO) > 0 ? " (fee " + fee.toPlainString() + ")" : ""));
                if (fee.compareTo(BigDecimal.ZERO) > 0) {
                    logTransaction(c, BANK_UUID, "FEE", fee, "trade fee");
                }
                BigDecimal fromNew = queryBalance(c, from);
                BigDecimal toNew = queryBalance(c, to);
                c.commit();
                BALANCE_CACHE.remove(from);
                BALANCE_CACHE.remove(to);
                BALANCE_CACHE.remove(BANK_UUID);
                notifyBalanceChanged(from, fromNew);
                notifyBalanceChanged(to, toNew);
                return true;
            } catch (Exception e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                if (e instanceof SQLException se) {
                    throw se;
                }
                throw (RuntimeException) e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /** Recent transactions for a player, as {@code {type, amount, note}} rows. */
    public static synchronized List<String[]> getLog(UUID uuid, int limit) {
        List<String[]> out = new ArrayList<>();
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT type, amount, note, time FROM transactions WHERE uuid = ? ORDER BY time DESC LIMIT ?")) {
            ps.setString(1, uuid.toString());
            ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString("type"),
                            MoneyUtil.norm(rs.getBigDecimal("amount")).stripTrailingZeros().toPlainString(),
                            rs.getString("note") == null ? "" : rs.getString("note")});
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return out;
    }

    /** Top balances (name, balance string) for the leaderboard (server bank excluded). */
    public static synchronized List<String[]> topBalances(int limit) {
        List<String[]> out = new ArrayList<>();
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, balance FROM balances WHERE uuid <> ? ORDER BY balance DESC LIMIT ?")) {
            ps.setString(1, BANK_UUID.toString());
            ps.setInt(2, Math.max(1, Math.min(limit, 100)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{rs.getString("name"),
                            MoneyUtil.norm(rs.getBigDecimal("balance")).stripTrailingZeros().toPlainString()});
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return out;
    }

    /** Resolve an account's UUID by its stored display name, or {@code null}. */
    public static synchronized UUID uuidByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM balances WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try {
                        return UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException ignored) {
                        // malformed stored uuid
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return null;
    }

    /** Distinct display names of all accounts (for offline-capable command suggestions). */
    public static synchronized List<String> accountNames() {
        long now = System.currentTimeMillis();
        if (nameCache != null && now - nameCacheAt < NAME_CACHE_TTL_MS) {
            return nameCache;
        }
        List<String> out = new ArrayList<>();
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT name FROM balances")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String n = rs.getString(1);
                    if (n != null && !n.isEmpty() && !isUuidString(n)) {
                        out.add(n);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        nameCache = out;
        nameCacheAt = now;
        return out;
    }

    /**
     * Delete transaction rows older than {@code retentionDays}. Returns the number of rows
     * removed, or -1 if retention is disabled.
     */
    public static synchronized int purgeOldTransactions(int retentionDays) {
        if (retentionDays <= 0) {
            return -1;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM transactions WHERE time < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    private static boolean isUuidString(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A reserved UUID used to accumulate server fees / recycling budgets. */
    public static final UUID BANK_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /* ---------------- internal helpers ---------------- */

    private static void ensureRowInternal(Connection c, UUID uuid, String name) throws SQLException {
        String sql = DatabaseManager.isMySQL()
                ? "INSERT IGNORE INTO balances (uuid, name, balance) VALUES (?, ?, 0.00)"
                : "INSERT OR IGNORE INTO balances (uuid, name, balance) VALUES (?, ?, 0.00)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name == null ? uuid.toString() : name);
            ps.executeUpdate();
        }
        // Keep the stored display name current so /eco <name> and uuidByName keep
        // working after a player renames. Null names (billing by UUID) never
        // overwrite an existing real name.
        if (name != null && !name.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE balances SET name = ? WHERE uuid = ? AND name <> ?")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.executeUpdate();
            }
        }
    }

    private static BigDecimal queryBalance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT balance FROM balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return MoneyUtil.norm(rs.getBigDecimal("balance"));
                }
            }
        }
        return BigDecimal.ZERO.setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
    }

    private static void updateBalanceDelta(Connection c, UUID uuid, BigDecimal delta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE balances SET balance = balance + ? WHERE uuid = ?")) {
            ps.setBigDecimal(1, delta);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    private static void logTransaction(Connection c, UUID uuid, String type, BigDecimal amount, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transactions (uuid, type, amount, note, time) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, type);
            ps.setBigDecimal(3, MoneyUtil.norm(amount));
            ps.setString(4, note);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
