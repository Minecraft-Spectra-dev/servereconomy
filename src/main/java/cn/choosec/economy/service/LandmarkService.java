package cn.choosec.economy.service;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.model.HomeLocation;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Landmarks — merges the original ServerRules "homes" (personal landmarks) with
 * the new economy public-landmark system.
 *
 * <p>Personal landmarks: default limit from config (5), expandable by buying
 * extra slots, teleport free. An admin override (legacy <tt>/home max</tt>)
 * takes precedence.
 *
 * <p>Public landmarks: set by admins, teleporting costs currency.
 */
public final class LandmarkService {

    /** A landmark row (public or personal). */
    public record Landmark(long id, String name, UUID owner, String world,
                           double x, double y, double z, float yaw, float pitch,
                           BigDecimal cost, boolean isPublic) {
    }

    private LandmarkService() {
    }

    /** Short-lived cache of the public landmark list (used for command suggestions / list). */
    private static volatile List<Landmark> publicCache = null;
    private static volatile long publicCacheAt = 0L;
    private static final long PUBLIC_CACHE_TTL_MS = 5_000L;

    private static void invalidatePublic() {
        publicCache = null;
    }

    /* ---------------- personal landmarks (homes) ---------------- */

    /** Add a personal landmark. Returns false if limit reached or name exists. */
    public static synchronized boolean addHome(UUID uuid, String name, HomeLocation loc) {
        try (Connection c = DatabaseManager.open()) {
            if (getHome(c, uuid, name) != null) {
                return false;
            }
            int limit = personalLimit(c, uuid);
            if (countHomes(c, uuid) >= limit) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO landmarks (name, owner, x, y, z, world, yaw, pitch, cost, is_public)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0.00, 0)""")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setDouble(3, loc.x());
                ps.setDouble(4, loc.y());
                ps.setDouble(5, loc.z());
                ps.setString(6, loc.world());
                ps.setFloat(7, loc.yaw());
                ps.setFloat(8, loc.pitch());
                ps.executeUpdate();
            }
            return true;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    public static synchronized HomeLocation getHome(UUID uuid, String name) {
        try (Connection c = DatabaseManager.open()) {
            return getHome(c, uuid, name);
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    public static synchronized Map<String, HomeLocation> listHomes(UUID uuid) {
        Map<String, HomeLocation> result = new LinkedHashMap<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT name, world, x, y, z, yaw, pitch FROM landmarks
                    WHERE owner = ? AND is_public = 0 ORDER BY id""")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("name"),
                                new HomeLocation(rs.getString("world"), rs.getDouble("x"),
                                        rs.getDouble("y"), rs.getDouble("z"),
                                        rs.getFloat("yaw"), rs.getFloat("pitch")));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return result;
    }

    public static synchronized boolean removeHome(UUID uuid, String name) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM landmarks WHERE owner = ? AND name = ? AND is_public = 0""")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    public static synchronized boolean renameHome(UUID uuid, String oldName, String newName) {
        try (Connection c = DatabaseManager.open()) {
            if (getHome(c, uuid, newName) != null) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE landmarks SET name = ? WHERE owner = ? AND name = ? AND is_public = 0""")) {
                ps.setString(1, newName);
                ps.setString(2, uuid.toString());
                ps.setString(3, oldName);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /** Effective personal landmark limit: admin override > default + purchased slots. */
    public static synchronized int personalLimit(UUID uuid) {
        try (Connection c = DatabaseManager.open()) {
            return personalLimit(c, uuid);
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return ConfigManager.get().landmarks.defaultPersonalLimit;
        }
    }

    public static synchronized void setPersonalLimitOverride(UUID uuid, int limit) {
        try (Connection c = DatabaseManager.open()) {
            String sql = DatabaseManager.isMySQL()
                    ? "INSERT INTO player_meta (uuid, home_limit_override) VALUES (?, ?) ON DUPLICATE KEY UPDATE home_limit_override = VALUES(home_limit_override)"
                    : "INSERT INTO player_meta (uuid, home_limit_override) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET home_limit_override = excluded.home_limit_override";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    public static synchronized void clearPersonalLimitOverride(UUID uuid) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE player_meta SET home_limit_override = NULL WHERE uuid = ?""")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /* ---------------- public landmarks ---------------- */

    public static synchronized boolean addPublic(String name, String world, double x, double y, double z,
                                                 float yaw, float pitch, BigDecimal cost) {
        if (getPublic(name) != null) {
            return false;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO landmarks (name, owner, x, y, z, world, yaw, pitch, cost, is_public)
                    VALUES (?, NULL, ?, ?, ?, ?, ?, ?, ?, 1)""")) {
                ps.setString(1, name);
                ps.setDouble(2, x);
                ps.setDouble(3, y);
                ps.setDouble(4, z);
                ps.setString(5, world);
                ps.setFloat(6, yaw);
                ps.setFloat(7, pitch);
                ps.setBigDecimal(8, cost == null ? BigDecimal.ZERO : cost);
                ps.executeUpdate();
            }
            invalidatePublic();
            return true;
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    public static synchronized Landmark getPublic(String name) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT * FROM landmarks WHERE name = ? AND is_public = 1""")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return row(rs);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return null;
    }

    public static synchronized List<Landmark> listPublic() {
        long now = System.currentTimeMillis();
        if (publicCache != null && now - publicCacheAt < PUBLIC_CACHE_TTL_MS) {
            return publicCache;
        }
        List<Landmark> result = new ArrayList<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT * FROM landmarks WHERE is_public = 1 ORDER BY name""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.add(row(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        publicCache = List.copyOf(result);
        publicCacheAt = now;
        return publicCache;
    }

    public static synchronized boolean removePublic(String name) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM landmarks WHERE name = ? AND is_public = 1""")) {
                ps.setString(1, name);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /* ---------------- internals ---------------- */

    private static HomeLocation getHome(Connection c, UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT world, x, y, z, yaw, pitch FROM landmarks
                WHERE owner = ? AND name = ? AND is_public = 0""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HomeLocation(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"),
                            rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch"));
                }
            }
        }
        return null;
    }

    private static int countHomes(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*) FROM landmarks WHERE owner = ? AND is_public = 0""")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static int personalLimit(Connection c, UUID uuid) throws SQLException {
        Integer override = null;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT home_limit_override FROM player_meta WHERE uuid = ?""")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    override = (Integer) rs.getObject("home_limit_override");
                }
            }
        }
        if (override != null) {
            return override;
        }
        int purchased = PlayerService.purchasedHomeSlots(c, uuid);
        return ConfigManager.get().landmarks.defaultPersonalLimit + purchased;
    }

    private static Landmark row(ResultSet rs) throws SQLException {
        String ownerStr = rs.getString("owner");
        UUID owner = ownerStr == null ? null : UUID.fromString(ownerStr);
        return new Landmark(rs.getLong("id"), rs.getString("name"), owner,
                rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"), rs.getBigDecimal("cost"),
                rs.getInt("is_public") == 1);
    }
}
