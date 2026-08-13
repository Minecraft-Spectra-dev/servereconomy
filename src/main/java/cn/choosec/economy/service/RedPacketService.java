package cn.choosec.economy.service;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import net.minecraft.server.level.ServerPlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Red packets (红包): no fee. Two kinds: lucky (拼手气, random split) and normal
 * (普通, even split). The sender pays the total up front; anyone (including the
 * sender) can grab from a chat clickable; a player can grab each packet once.
 */
public final class RedPacketService {

    public enum Result {
        SUCCESS, NO_FUNDS, NOT_FOUND, ALREADY_TAKEN, EXHAUSTED, ERROR
    }

    /** Outcome of grabbing: result + amount received on success. */
    public record GrabResult(Result result, BigDecimal amount) {
    }

    private RedPacketService() {
    }

    /** Create a red packet. Returns id, or -1 on failure / -2 insufficient balance. */
    public static synchronized int create(ServerPlayer sender, boolean lucky, BigDecimal total, int count) {
        total = MoneyUtil.norm(total);
        if (total.compareTo(BigDecimal.ZERO) <= 0 || count <= 0) {
            return -1;
        }
        // Reject packets that cannot pay every recipient at least one money unit.
        if (total.compareTo(MoneyUtil.minUnit().multiply(BigDecimal.valueOf(count))) < 0) {
            return -1;
        }
        if (EconomyService.remove(sender.getUUID(), sender.getName().getString(), total, "red packet") == null) {
            return -2;
        }
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO redpackets (sender, lucky, total, remaining_amount, count, remaining_count, time)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, sender.getUUID().toString());
                ps.setInt(2, lucky ? 1 : 0);
                ps.setBigDecimal(3, total);
                ps.setBigDecimal(4, total);
                ps.setInt(5, count);
                ps.setInt(6, count);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        return keys.getInt(1);
                    }
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            EconomyService.add(sender.getUUID(), sender.getName().getString(), total, "red packet refund");
        }
        return -1;
    }

    /** Grab a packet. Returns the amount received (0 if not applicable). */
    public static synchronized GrabResult grab(ServerPlayer p, int id) {
        UUID uuid = p.getUUID();
        try (Connection c = DatabaseManager.open()) {
            BigDecimal remaining; int remCount; boolean lucky;
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT remaining_amount, remaining_count, lucky FROM redpackets WHERE id = ?")) {
                sel.setInt(1, id);
                try (ResultSet rs = sel.executeQuery()) {
                    if (!rs.next()) return new GrabResult(Result.NOT_FOUND, BigDecimal.ZERO);
                    remaining = rs.getBigDecimal("remaining_amount");
                    remCount = rs.getInt("remaining_count");
                    lucky = rs.getInt("lucky") == 1;
                }
            }
            if (remCount <= 0 || remaining.compareTo(BigDecimal.ZERO) <= 0) {
                return new GrabResult(Result.EXHAUSTED, BigDecimal.ZERO);
            }
            try (PreparedStatement taken = c.prepareStatement(
                    "SELECT 1 FROM redpacket_taken WHERE packet_id = ? AND player = ?")) {
                taken.setInt(1, id);
                taken.setString(2, uuid.toString());
                try (ResultSet rs = taken.executeQuery()) {
                    if (rs.next()) return new GrabResult(Result.ALREADY_TAKEN, BigDecimal.ZERO);
                }
            }
            BigDecimal amount = grabAmount(remaining, remCount, lucky);
            // update packet
            try (PreparedStatement up = c.prepareStatement(
                    "UPDATE redpackets SET remaining_amount = remaining_amount - ?, remaining_count = remaining_count - 1 WHERE id = ?")) {
                up.setBigDecimal(1, amount);
                up.setInt(2, id);
                up.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO redpacket_taken (packet_id, player, amount) VALUES (?, ?, ?)")) {
                ins.setInt(1, id);
                ins.setString(2, uuid.toString());
                ins.setBigDecimal(3, amount);
                ins.executeUpdate();
            }
            EconomyService.add(uuid, p.getName().getString(), amount, "red packet grab #" + id);
            return new GrabResult(Result.SUCCESS, amount);
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return new GrabResult(Result.ERROR, BigDecimal.ZERO);
        }
    }

    /**
     * Compute the amount one grab receives. Pure (no database access) so the split
     * math is unit-testable. The last grab takes the whole remainder; every grab is
     * clamped to at least one money unit and never exceeds the remainder.
     */
    static BigDecimal grabAmount(BigDecimal remaining, int remCount, boolean lucky) {
        if (remCount <= 1) {
            return remaining;
        }
        BigDecimal amount;
        if (lucky) {
            BigDecimal factor = BigDecimal.valueOf(0.2 + Math.random() * 1.0);
            amount = remaining.multiply(factor)
                    .divide(BigDecimal.valueOf(remCount), MoneyUtil.SCALE + 2, RoundingMode.HALF_UP)
                    .setScale(MoneyUtil.SCALE, RoundingMode.HALF_UP);
            if (amount.compareTo(MoneyUtil.minUnit()) < 0) amount = MoneyUtil.minUnit();
            if (amount.compareTo(remaining) > 0) amount = remaining;
        } else {
            amount = remaining.divide(BigDecimal.valueOf(remCount), MoneyUtil.SCALE, RoundingMode.HALF_UP);
        }
        return amount;
    }

    /** Broadcast a clickable grab message for a packet. */
    public static void broadcast(int id, ServerPlayer sender, boolean lucky, BigDecimal total, int count) {
        net.minecraft.server.MinecraftServer server = sender.level().getServer();
        if (server == null) return;
        String type = lucky ? "拼手气" : "普通";
        net.minecraft.network.chat.MutableComponent msg = cn.choosec.economy.util.MessageUtil.parse(
                "&a[红包] &e" + sender.getName().getString() + " &f发了 &e" + type + "红包 &f共 &e"
                        + MoneyUtil.format(total) + " " + ConfigManager.get().currencyAbbreviation + " &f(" + count + " 份) ")
                .copy().append(net.minecraft.network.chat.Component.literal("[抢红包]")
                        .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.ChatFormatting.GOLD).withBold(true)
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/redpacket grab " + id))));
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }
}
