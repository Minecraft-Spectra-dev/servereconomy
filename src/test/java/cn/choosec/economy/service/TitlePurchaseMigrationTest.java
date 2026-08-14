package cn.choosec.economy.service;

import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.MoneyUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies an older player_meta table (without title_purchased) is migrated on startup. */
class TitlePurchaseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void oldPlayerMetaGetsTitlePurchasedColumn() throws Exception {
        Class.forName("org.sqlite.JDBC");
        // Simulate a database created by an older mod version: player_meta without title_purchased.
        Path dbDir = tempDir.resolve("servereconomy");
        Files.createDirectories(dbDir);
        String url = "jdbc:sqlite:" + dbDir.resolve("economy.db").toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE player_meta ("
                    + "uuid TEXT PRIMARY KEY,"
                    + "purchased_slots INTEGER NOT NULL DEFAULT 0)");
        }

        MoneyUtil.SCALE = 2;
        EconomyConfig.DatabaseConfig cfg = new EconomyConfig.DatabaseConfig();
        cfg.type = "sqlite";
        try {
            DatabaseManager.init(tempDir, cfg);

            UUID uuid = UUID.randomUUID();
            assertFalse(PlayerService.titlePurchased(uuid));
            PlayerService.markTitlePurchased(uuid);
            assertTrue(PlayerService.titlePurchased(uuid));
        } finally {
            DatabaseManager.close();
        }
    }
}
