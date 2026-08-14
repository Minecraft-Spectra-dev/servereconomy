package cn.choosec.economy.config;

import cn.choosec.economy.ServerEconomy;
import cn.choosec.economy.service.PreservedService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Loads and saves {@link EconomyConfig} to a JSON file in the server config directory.
 *
 * <p>Configuration is stored as JSON (as requested). If the file is missing it is
 * created with defaults; if it exists but a field is absent, the default from
 * {@link EconomyConfig} is used thanks to Gson's field-initializer fallback.
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static volatile EconomyConfig config = new EconomyConfig();
    private static Path configPath;
    /** Whether MoneyUtil.SCALE has been initialised (set once, never changed by /eco reload). */
    private static boolean scaleInitialized = false;

    private ConfigManager() {
    }

    /** Initialize the config manager for a given server config directory. */
    public static void init(Path configDir) {
        configPath = configDir.resolve("servereconomy.json");
        load();
    }

    /** Load (or create) the config file. Returns true on success. */
    public static boolean load() {
        try {
            if (configPath == null) {
                return false;
            }
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                EconomyConfig parsed = GSON.fromJson(json, EconomyConfig.class);
                if (parsed != null) {
                    config = parsed;
                }
            } else {
                save();
            }
            normalize(config);
            PreservedService.headerText = config.tabHeader == null ? "" : config.tabHeader;
            PreservedService.footerText = config.tabFooter == null ? "" : config.tabFooter;
            // clamp to the DB column scale (DECIMAL(20,2)) and set only once so /eco reload cannot
            // change precision mid-run and desync stored amounts.
            if (!scaleInitialized && config.currencyDecimals >= 0 && config.currencyDecimals <= 2) {
                cn.choosec.economy.economy.MoneyUtil.SCALE = config.currencyDecimals;
                scaleInitialized = true;
            }
            ServerEconomy.LOGGER.info("[ServerEconomy] Configuration loaded from {}", configPath);
            return true;
        } catch (IOException e) {
            ServerEconomy.LOGGER.error("[ServerEconomy] Failed to load configuration", e);
            return false;
        }
    }

    /**
     * Repair a partially edited JSON so the rest of the mod never sees null
     * sections or out-of-range values (Gson sets missing objects to null when the
     * file explicitly contains {@code null}).
     */
    private static void normalize(EconomyConfig cfg) {
        if (cfg.rates == null) cfg.rates = new EconomyConfig.Rates();
        EconomyConfig.Rates r = cfg.rates;
        if (r.fakePlayerHourly == null) r.fakePlayerHourly = new EconomyConfig.Rates().fakePlayerHourly;
        if (r.accountSlotPrice == null) r.accountSlotPrice = new EconomyConfig.Rates().accountSlotPrice;
        if (r.landmarkSlotPrice == null) r.landmarkSlotPrice = new EconomyConfig.Rates().landmarkSlotPrice;
        if (r.flightPerSecond == null) r.flightPerSecond = new EconomyConfig.Rates().flightPerSecond;
        if (r.titleFirstPurchase == null || r.titleFirstPurchase.compareTo(BigDecimal.ZERO) < 0) {
            r.titleFirstPurchase = BigDecimal.ZERO;
        }
        if (r.titleChange == null || r.titleChange.compareTo(BigDecimal.ZERO) < 0) {
            r.titleChange = BigDecimal.ZERO;
        }
        if (r.tradeFeePercent == null || r.tradeFeePercent.compareTo(BigDecimal.ZERO) < 0) {
            r.tradeFeePercent = BigDecimal.ZERO;
        } else if (r.tradeFeePercent.compareTo(new BigDecimal("100")) > 0) {
            r.tradeFeePercent = new BigDecimal("100");
        }
        if (r.publicLandmarkCost == null) r.publicLandmarkCost = new EconomyConfig.Rates().publicLandmarkCost;
        if (r.taskReachRadius < 0) r.taskReachRadius = new EconomyConfig.Rates().taskReachRadius;

        if (cfg.fakePlayers == null) cfg.fakePlayers = new EconomyConfig.FakePlayerConfig();
        if (cfg.fakePlayers.freePerPlayer < 0) cfg.fakePlayers.freePerPlayer = 0;
        if (cfg.accountSlots == null) cfg.accountSlots = new EconomyConfig.AccountSlotConfig();
        if (cfg.accountSlots.freeSlots < 0) cfg.accountSlots.freeSlots = 0;
        if (cfg.landmarks == null) cfg.landmarks = new EconomyConfig.LandmarkConfig();
        if (cfg.landmarks.defaultPersonalLimit < 0) cfg.landmarks.defaultPersonalLimit = 0;
        if (cfg.trade == null) cfg.trade = new EconomyConfig.TradeConfig();
        if (cfg.trade.maxListingsPerPlayer < 0) cfg.trade.maxListingsPerPlayer = 0;
        if (cfg.tasks == null) cfg.tasks = new EconomyConfig.TasksConfig();
        if (cfg.tasks.dailyCount < 0) cfg.tasks.dailyCount = 0;
        if (cfg.sell == null) cfg.sell = new EconomyConfig.SellConfig();
        if (cfg.sell.globalMaxSupply < 0) cfg.sell.globalMaxSupply = 0;
        if (cfg.sellableItems == null) {
            cfg.sellableItems = new ArrayList<>();
        } else {
            cfg.sellableItems.removeIf(s -> s == null || s.id == null);
        }
        if (cfg.database == null) cfg.database = new EconomyConfig.DatabaseConfig();
        if (cfg.currencyDecimals < 0) cfg.currencyDecimals = 0;
        if (cfg.currencyDecimals > 2) cfg.currencyDecimals = 2;
        for (EconomyConfig.SellableItem s : cfg.sellableItems) {
            if (s.basePrice == null) s.basePrice = new EconomyConfig.SellableItem().basePrice;
            if (s.priceFloor == null) s.priceFloor = new EconomyConfig.SellableItem().priceFloor;
            if (s.priceFloor.compareTo(BigDecimal.ZERO) < 0) s.priceFloor = BigDecimal.ZERO;
        }
    }

    /** Persist the current configuration back to the JSON file. */
    public static void save() {
        if (configPath == null) {
            return;
        }
        try {
            config.tabHeader = PreservedService.headerText;
            config.tabFooter = PreservedService.footerText;
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ServerEconomy.LOGGER.error("[ServerEconomy] Failed to save configuration", e);
        }
    }

    public static EconomyConfig get() {
        return config;
    }

    /** Reload from disk; returns true on success. */
    public static boolean reload() {
        try {
            return load();
        } catch (RuntimeException e) {
            ServerEconomy.LOGGER.error("[ServerEconomy] Failed to reload configuration", e);
            return false;
        }
    }
}
