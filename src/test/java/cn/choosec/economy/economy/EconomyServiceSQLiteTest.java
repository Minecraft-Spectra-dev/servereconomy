package cn.choosec.economy.economy;

import cn.choosec.economy.config.EconomyConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** Runs the shared ledger scenarios against a throwaway SQLite database. */
class EconomyServiceSQLiteTest extends AbstractEconomyServiceTest {

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        EconomyConfig.DatabaseConfig cfg = new EconomyConfig.DatabaseConfig();
        cfg.type = "sqlite";
        startBackend(cfg, tempDir);
    }
}
