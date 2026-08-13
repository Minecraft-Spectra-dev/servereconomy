package cn.choosec.economy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for config defaults, save-on-create and hostile-JSON normalization. */
class ConfigManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultsAreWrittenOnFirstInit() throws Exception {
        ConfigManager.init(tempDir);
        Path file = tempDir.resolve("servereconomy.json");
        assertTrue(Files.exists(file));
        assertEquals(2, ConfigManager.get().currencyDecimals);
    }

    @Test
    void hostileJsonIsNormalizedOnReload() throws Exception {
        ConfigManager.init(tempDir);
        Files.writeString(tempDir.resolve("servereconomy.json"), """
                {
                  "currencyDecimals": 99,
                  "rates": null,
                  "sellableItems": null,
                  "trade": null,
                  "database": null
                }
                """);

        assertTrue(ConfigManager.reload());
        EconomyConfig cfg = ConfigManager.get();
        assertEquals(2, cfg.currencyDecimals);
        assertNotNull(cfg.rates);
        assertNotNull(cfg.trade);
        assertNotNull(cfg.database);
        assertNotNull(cfg.sellableItems);
        assertTrue(cfg.sellableItems.isEmpty());
    }

    @Test
    void negativeFeeIsClampedToZero() throws Exception {
        ConfigManager.init(tempDir);
        Files.writeString(tempDir.resolve("servereconomy.json"),
                "{\"currencyDecimals\": 2, \"rates\": {\"tradeFeePercent\": -5.00}}");

        assertTrue(ConfigManager.reload());
        assertEquals(0, ConfigManager.get().rates.tradeFeePercent.compareTo(BigDecimal.ZERO));
    }
}
