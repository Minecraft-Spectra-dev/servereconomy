package cn.choosec.economy.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration object, serialized to/from a JSON file by {@link ConfigManager}.
 *
 * <p>All monetary amounts are stored as {@link BigDecimal} (scale 2) so the mod never
 * relies on floating-point arithmetic for money.
 */
public class EconomyConfig {

    /** Display name / abbreviation of the currency. */
    public String currencyName = "dollar";
    public String currencyAbbreviation = "$";
    /** Decimal precision used for all money arithmetic/display. */
    public int currencyDecimals = 2;

    /** Tab-list header/footer (set by the server administrator). */
    public String tabHeader = "";
    public String tabFooter = "";

    /** Base configuration shared across the system (rates, prices). */
    public Rates rates = new Rates();

    /** Fake-player (dummy) billing configuration. */
    public FakePlayerConfig fakePlayers = new FakePlayerConfig();

    /** Extra account-slot configuration. */
    public AccountSlotConfig accountSlots = new AccountSlotConfig();

    /** Landmark configuration (public cost + personal limit). */
    public LandmarkConfig landmarks = new LandmarkConfig();

    /** Player-to-player trade configuration. */
    public TradeConfig trade = new TradeConfig();

    /** Daily-task system configuration. */
    public TasksConfig tasks = new TasksConfig();

    /** Item recycling / sell-to-server configuration. */
    public SellConfig sell = new SellConfig();

    /** List of items the server will recycle (buy from players). Empty by default; admins add via /eco recycle add. */
    public List<SellableItem> sellableItems = new ArrayList<>();

    /* ------------------------------------------------------------------ */

    /** Database backend configuration. "sqlite" (default) or "mysql". */
    public DatabaseConfig database = new DatabaseConfig();

    /** Language used for the server-side scoreboard ("zh_cn" or "en_us"). */
    public String scoreboardLanguage = "zh_cn";

    /** Daily task system (per-player, real-time daily, drawn from a pool). */
    public static class TasksConfig {
        /** Number of daily tasks each player gets per day. */
        public int dailyCount = 5;
    }

    /** Global rates / price knobs the server admin can tune live. */
    public static class Rates {
        /** Per-hour price of each fake player beyond the free allowance. */
        public BigDecimal fakePlayerHourly = new BigDecimal("5.00");
        /** Price of each extra account slot. */
        public BigDecimal accountSlotPrice = new BigDecimal("2000.00");
        /** Price of each expanded personal landmark slot. */
        public BigDecimal landmarkSlotPrice = new BigDecimal("200.00");
        /** Price of flight permission per second (real-time deduction). */
        public BigDecimal flightPerSecond = new BigDecimal("0.01");
        /** Price of the first title a player buys with /buytitle. */
        public BigDecimal titleFirstPurchase = new BigDecimal("500.00");
        /** Price of each later title change/re-buy with /buytitle. */
        public BigDecimal titleChange = new BigDecimal("200.00");
        /** Trade fee, as a percentage (e.g. 2 means 2%). */
        public BigDecimal tradeFeePercent = new BigDecimal("2.00");
        /** Default cost to teleport to a public landmark (0 = free). */
        public BigDecimal publicLandmarkCost = new BigDecimal("1.00");
        /** Radius (blocks) for REACH-type tasks. */
        public int taskReachRadius = 8;
    }

    /** Fake-player (dummy) accounting. */
    public static class FakePlayerConfig {
        /** Free fake players every player may keep active. */
        public int freePerPlayer = 2;
    }

    /** Extra account slots. */
    public static class AccountSlotConfig {
        /** Free extra account slots every player gets. */
        public int freeSlots = 1;
    }


    /** Landmark limits & costs. */
    public static class LandmarkConfig {
        /** Default number of personal landmarks each player may set. */
        public int defaultPersonalLimit = 5;
    }

    /** Player-to-player trade. */
    public static class TradeConfig {
        /** Maximum number of active listings a player may keep. */
        public int maxListingsPerPlayer = 20;
    }

    /** Sell-to-server recycling. */
    public static class SellConfig {
        /** Max units of each item the server will accept total (per item id). */
        public int globalMaxSupply = 10000;
        /** Enable dynamic price adjustment by supply. */
        public boolean dynamicPricing = true;
    }

    /** A recyclable item definition. */
    public static class SellableItem {
        /** Registry id of the item, e.g. "minecraft:diamond". */
        public String id = "";
        /** Base price per unit when supply is 0. */
        public BigDecimal basePrice = new BigDecimal("0.00");
        /** Price never drops below this. */
        public BigDecimal priceFloor = new BigDecimal("0.00");
        /** Percentage price drop per unit of cumulative supply (e.g. 0.05 = 0.05% per unit). */
        public double decayPercentPerUnit = 0.05;
    }

    /** Database backend configuration (sqlite or mysql). */
    public static class DatabaseConfig {
        /** Backend: "sqlite" (default, local file) or "mysql" (external server). */
        public String type = "sqlite";

        /** MySQL host (ignored for sqlite). */
        public String host = "127.0.0.1";
        /** MySQL port (ignored for sqlite). */
        public int port = 3306;
        /** MySQL database/schema name; must already exist (ignored for sqlite). */
        public String database = "servereconomy";
        /** MySQL username (ignored for sqlite). */
        public String username = "root";
        /** MySQL password (ignored for sqlite). */
        public String password = "";
        /** Extra JDBC connection parameters appended to the MySQL URL (ignored for sqlite). */
        public String extraParams = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        /** Transaction history retention in days; older rows are purged at startup (0 = keep forever). */
        public int transactionRetentionDays = 90;
    }
}
