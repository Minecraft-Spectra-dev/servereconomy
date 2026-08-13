package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Per-player metadata stored in the economy database (account slots, home expansion, fake players, flight metering). */
public final class PlayerService {

    private PlayerService() {
    }

    private static void ensureRow(Connection c, UUID uuid) throws SQLException {
        String sql = DatabaseManager.isMySQL()
                ? "INSERT IGNORE INTO player_meta (uuid) VALUES (?)"
                : "INSERT OR IGNORE INTO player_meta (uuid) VALUES (?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /* ---------------- extra account slots ---------------- */

    public static synchronized int purchasedAccountSlots(UUID uuid) {
        try (Connection c = DatabaseManager.open()) {
            return intCol(c, uuid, "purchased_slots");
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    public static synchronized void addPurchasedAccountSlots(UUID uuid, int n) {
        try (Connection c = DatabaseManager.open()) {
            ensureRow(c, uuid);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE player_meta SET purchased_slots = purchased_slots + ? WHERE uuid = ?""")) {
                ps.setInt(1, n);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    public static synchronized void setPurchasedAccountSlots(UUID uuid, int n) {
        try (Connection c = DatabaseManager.open()) {
            ensureRow(c, uuid);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE player_meta SET purchased_slots = ? WHERE uuid = ?""")) {
                ps.setInt(1, n);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /* ---------------- purchased home/landmark slots ---------------- */

    public static synchronized int purchasedHomeSlots(UUID uuid) {
        try (Connection c = DatabaseManager.open()) {
            return purchasedHomeSlots(c, uuid);
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    static int purchasedHomeSlots(Connection c, UUID uuid) throws SQLException {
        ensureRow(c, uuid);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT purchased_home_slots FROM player_meta WHERE uuid = ?""")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public static synchronized void addPurchasedHomeSlots(UUID uuid, int n) {
        try (Connection c = DatabaseManager.open()) {
            ensureRow(c, uuid);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE player_meta SET purchased_home_slots = purchased_home_slots + ? WHERE uuid = ?""")) {
                ps.setInt(1, n);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /* ---------------- fake players ---------------- */

    public static synchronized int fakePlayersActive(UUID uuid) {
        try (Connection c = DatabaseManager.open()) {
            return intCol(c, uuid, "fake_players_active");
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    public static synchronized void addFakePlayersActive(UUID uuid, int n) {
        int cur = fakePlayersActive(uuid);
        setFakePlayersActive(uuid, cur + n);
    }

    public static synchronized void setFakePlayersActive(UUID uuid, int n) {
        try (Connection c = DatabaseManager.open()) {
            ensureRow(c, uuid);
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE player_meta SET fake_players_active = ? WHERE uuid = ?""")) {
                ps.setInt(1, Math.max(0, n));
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /* ---------------- internals ---------------- */

    private static int intCol(Connection c, UUID uuid, String col) throws SQLException {
        ensureRow(c, uuid);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT %s FROM player_meta WHERE uuid = ?""".formatted(col))) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
