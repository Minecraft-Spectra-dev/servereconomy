package cn.choosec.economy.service;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.MoneyUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Item recycling / sell-to-server. The server buys a limited quantity of each
 * configured rare item; the per-unit price falls as total server supply rises,
 * and never drops below the configured floor. The server price is intended to be
 * below the player-to-player market price (dynamic pricing).
 */
public final class SellService {

    private SellService() {
    }

    /** True if the server will buy this item at all. */
    public static synchronized boolean isSellable(String itemId) {
        return definition(itemId) != null;
    }

    public static synchronized EconomyConfig.SellableItem definition(String itemId) {
        for (EconomyConfig.SellableItem s : ConfigManager.get().sellableItems) {
            if (s.id.equals(itemId)) {
                return s;
            }
        }
        return null;
    }

    /** Current per-unit price for an item given cumulative supply. */
    public static synchronized BigDecimal unitPrice(String itemId) {
        EconomyConfig.SellableItem def = definition(itemId);
        if (def == null) {
            return MoneyUtil.norm(BigDecimal.ZERO);
        }
        int sold = soldCount(itemId);
        BigDecimal price = def.basePrice;
        if (ConfigManager.get().sell.dynamicPricing) {
            BigDecimal drop = def.basePrice
                    .multiply(BigDecimal.valueOf(def.decayPercentPerUnit).movePointLeft(2))
                    .multiply(BigDecimal.valueOf(sold));
            price = def.basePrice.subtract(drop);
        }
        if (price.compareTo(def.priceFloor) < 0) {
            price = def.priceFloor;
        }
        return price.setScale(MoneyUtil.SCALE, MoneyUtil.ROUNDING);
    }

    /** How many more units the server will buy before the cap is reached. */
    public static synchronized int remainingSupply(String itemId) {
        EconomyConfig.SellableItem def = definition(itemId);
        if (def == null) {
            return 0;
        }
        int max = ConfigManager.get().sell.globalMaxSupply;
        int sold = soldCount(itemId);
        return Math.max(0, max - sold);
    }

    /** Cumulative number of units the server has bought of this item. */
    public static synchronized int soldCount(String itemId) {
        try (Connection c = DatabaseManager.open()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT sold_count FROM supply WHERE item = ?")) {
                ps.setString(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
            return 0;
        }
    }

    /** Record a completed sale (increment supply) and log it. Returns total paid. */
    public static synchronized BigDecimal recordSale(String itemId, int count) {
        BigDecimal unit = unitPrice(itemId);
        BigDecimal total = MoneyUtil.norm(unit.multiply(BigDecimal.valueOf(count)));
        try (Connection c = DatabaseManager.open()) {
            String sql = DatabaseManager.isMySQL()
                    ? "INSERT INTO supply (item, sold_count, total_value) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE sold_count = sold_count + VALUES(sold_count), total_value = total_value + VALUES(total_value)"
                    : "INSERT INTO supply (item, sold_count, total_value) VALUES (?, ?, ?) ON CONFLICT(item) DO UPDATE SET sold_count = sold_count + excluded.sold_count, total_value = total_value + excluded.total_value";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, itemId);
                ps.setInt(2, count);
                ps.setBigDecimal(3, total);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            DatabaseManager.log(e);
        }
        return total;
    }
}
