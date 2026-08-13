package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.util.MessageUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player notifications with online/offline delivery. Notifications for a player
 * who is currently online are sent immediately; notifications for an offline
 * player are persisted and delivered in order the next time that player joins.
 */
public final class NotificationService {

    private NotificationService() {
    }

    /**
     * Send a legacy-colour message to {@code recipient} now if they are online,
     * otherwise queue it for their next join.
     *
     * @param server    the server used to resolve online players
     * @param recipient the player to notify
     * @param message   the message to deliver (supports {@code &} colour codes)
     */
    public static void notify(MinecraftServer server, UUID recipient, String message) {
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(recipient);
        if (player != null) {
            player.sendSystemMessage(MessageUtil.parse(message));
        } else {
            queue(recipient, message);
        }
    }

    /** Persist a notification so it is delivered when the recipient next joins. */
    public static void queue(UUID recipient, String message) {
        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO notifications (owner, message, time) VALUES (?, ?, ?)")) {
            ps.setString(1, recipient.toString());
            ps.setString(2, message);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    /** Deliver all queued notifications to a player who just joined, oldest first. */
    public static void deliver(ServerPlayer player) {
        List<String> messages = new ArrayList<>();
        String owner = player.getUUID().toString();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT message FROM notifications WHERE owner = ? ORDER BY id")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(rs.getString("message"));
                    }
                }
            }
            if (!messages.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM notifications WHERE owner = ?")) {
                    del.setString(1, owner);
                    del.executeUpdate();
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return;
        }
        for (String message : messages) {
            player.sendSystemMessage(MessageUtil.parse(message));
        }
    }
}
