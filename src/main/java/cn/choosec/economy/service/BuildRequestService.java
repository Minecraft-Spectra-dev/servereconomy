package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Engineering-acceptance (contribution) requests.
 *
 * <p>Players submit a coordinate of a machine, building or facility they built
 * for the server community. Admins review the pending list and, upon
 * acceptance, grant the contribution / acceptance reward to the requester.
 */
public final class BuildRequestService {

    /** A single acceptance-request row. */
    public record BuildRequest(long id, UUID player, String playerName, String world,
                               double x, double y, double z, String note, String status,
                               BigDecimal reward, long time) {
    }

    /** Status values stored in the {@code status} column. */
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /** Result of an approval attempt. */
    public enum ApproveResult {
        SUCCESS, NOT_FOUND, NOT_PENDING, ERROR
    }

    private BuildRequestService() {
    }

    /**
     * Submit a new acceptance request. Returns the new row id, or -1 on error.
     */
    public static synchronized long submit(UUID uuid, String playerName, String world,
                                           double x, double y, double z, String note) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO build_requests
                        (player, player_name, x, y, z, world, note, status, reward, time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0.00, ?)""",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setDouble(3, x);
                ps.setDouble(4, y);
                ps.setDouble(5, z);
                ps.setString(6, world);
                ps.setString(7, note);
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getLong(1);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return -1;
    }

    /** List all requests, optionally filtered by status. Null/blank status = all. */
    public static synchronized List<BuildRequest> list(String status) {
        List<BuildRequest> result = new ArrayList<>();
        String sql = "SELECT id, player, player_name, x, y, z, world, note, status, reward, time "
                + "FROM build_requests";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = ?";
        }
        sql += " ORDER BY time ASC, id ASC";
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (status != null && !status.isBlank()) {
                    ps.setString(1, status);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(map(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return result;
    }

    /** Get a single request by id, or null. */
    public static synchronized BuildRequest get(long id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT id, player, player_name, x, y, z, world, note, status, reward, time
                    FROM build_requests WHERE id = ?""")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return map(rs);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return null;
    }

    /**
     * Approve a pending request: grant the acceptance reward to the requester
     * and mark it as approved.
     */
    public static synchronized ApproveResult approve(long id, BigDecimal amount) {
        BuildRequest req = get(id);
        if (req == null) {
            return ApproveResult.NOT_FOUND;
        }
        if (!PENDING.equals(req.status())) {
            return ApproveResult.NOT_PENDING;
        }
        BigDecimal reward = MoneyUtil.norm(amount);
        // Status flip and payout share one transaction (the shared connection is
        // single-threaded), so a failure can never leave the request re-approvable
        // after money was already granted, or grant money without marking APPROVED.
        try (Connection c = DatabaseManager.open()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement ps = c.prepareStatement("""
                        UPDATE build_requests SET status = 'APPROVED', reward = ? WHERE id = ? AND status = 'PENDING'""")) {
                    ps.setBigDecimal(1, reward);
                    ps.setLong(2, id);
                    updated = ps.executeUpdate();
                }
                if (updated != 1) {
                    c.rollback();
                    return ApproveResult.NOT_PENDING;
                }
                BigDecimal bal = EconomyService.add(req.player(), req.playerName(), reward, "build acceptance");
                if (bal == null) {
                    c.rollback();
                    EconomyService.invalidateCache(req.player());
                    return ApproveResult.ERROR;
                }
                c.commit();
                return ApproveResult.SUCCESS;
            } catch (SQLException e) {
                c.rollback();
                EconomyService.invalidateCache(req.player());
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return ApproveResult.ERROR;
        }
    }

    /** Reject a pending request. Returns false if missing or not pending. */
    public static synchronized boolean reject(long id) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE build_requests SET status = 'REJECTED'
                    WHERE id = ? AND status = 'PENDING'""")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    private static BuildRequest map(ResultSet rs) throws SQLException {
        return new BuildRequest(
                rs.getLong("id"),
                UUID.fromString(rs.getString("player")),
                rs.getString("player_name"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getString("note"),
                rs.getString("status"),
                MoneyUtil.norm(rs.getBigDecimal("reward")),
                rs.getLong("time"));
    }
}
