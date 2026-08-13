package cn.choosec.economy.service;

import cn.choosec.economy.database.DatabaseManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Per-player mailbox. Items that cannot be handed out directly (the buyer is
 * offline, or their inventory is full) are stored here and claimed via the
 * /mails GUI.
 */
public final class MailboxService {

    public record Mail(int id, String owner, String itemId, String itemData, int count, long time) {
    }

    public enum ClaimResult { SUCCESS, NOT_FOUND, NO_SPACE }

    private MailboxService() {
    }

    public static synchronized void add(UUID owner, ItemStack stack, RegistryAccess reg) {
        if (stack.isEmpty()) {
            return;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO mailbox (owner, item_id, item_data, count, time) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, owner.toString());
                ps.setString(2, stack.typeHolder().getRegisteredName());
                ps.setString(3, TradeService.serialize(stack, reg));
                ps.setInt(4, stack.getCount());
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
    }

    public static synchronized List<Mail> list(UUID owner) {
        List<Mail> out = new ArrayList<>();
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM mailbox WHERE owner = ? ORDER BY id")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(row(rs));
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return out;
    }

    public static synchronized int count(UUID owner) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM mailbox WHERE owner = ?")) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    private static synchronized Mail get(int mailId) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM mailbox WHERE id = ?")) {
                ps.setInt(1, mailId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return null;
        }
    }

    private static synchronized boolean delete(int mailId) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM mailbox WHERE id = ?")) {
                ps.setInt(1, mailId);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return false;
        }
    }

    /** Rebuild the item stack for a mail for display. */
    public static synchronized ItemStack build(Mail m, RegistryAccess reg) {
        ItemStack stack = TradeService.buildFromData(m.itemData(), reg);
        if (stack.isEmpty()) {
            stack = TradeService.buildFromId(m.itemId(), m.count());
        }
        return stack;
    }

    /** Claim a mail into the player's inventory. Only the owner may claim. */
    public static synchronized ClaimResult claim(ServerPlayer p, int mailId) {
        Mail m = get(mailId);
        if (m == null || !m.owner().equals(p.getUUID().toString())) {
            return ClaimResult.NOT_FOUND;
        }
        RegistryAccess reg = p.level().getServer().registryAccess();
        ItemStack stack = TradeService.buildFromData(m.itemData(), reg);
        if (stack.isEmpty()) {
            stack = TradeService.buildFromId(m.itemId(), m.count());
        }
        if (stack.isEmpty()) {
            delete(mailId); // item no longer resolvable; drop the mail
            return ClaimResult.NOT_FOUND;
        }
        if (!p.getInventory().add(stack.copy())) {
            return ClaimResult.NO_SPACE;
        }
        delete(mailId);
        return ClaimResult.SUCCESS;
    }

    private static Mail row(ResultSet rs) throws SQLException {
        return new Mail(rs.getInt("id"), rs.getString("owner"), rs.getString("item_id"),
                rs.getString("item_data"), rs.getInt("count"), rs.getLong("time"));
    }
}
