package cn.choosec.economy.service;

import cn.choosec.economy.config.ConfigManager;
import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.MoneyUtil;
import cn.choosec.economy.model.HomeLocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for personal-limit enforcement and public-name uniqueness. */
class LandmarkServiceTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ROT_OWNER = UUID.randomUUID();

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setUp() throws Exception {
        MoneyUtil.SCALE = 2;
        EconomyConfig.DatabaseConfig cfg = new EconomyConfig.DatabaseConfig();
        cfg.type = "sqlite";
        DatabaseManager.init(tempDir, cfg);
    }

    @AfterAll
    static void tearDown() {
        DatabaseManager.close();
    }

    @Test
    void personalLimitIsEnforced() {
        ConfigManager.get().landmarks.defaultPersonalLimit = 5;
        for (int i = 0; i < 5; i++) {
            assertTrue(LandmarkService.addHome(OWNER, "home" + i,
                    new HomeLocation("minecraft:overworld", i, 64, 0, 90, 0)));
        }
        assertFalse(LandmarkService.addHome(OWNER, "overflow",
                new HomeLocation("minecraft:overworld", 9, 64, 9, 0, 0)));
        assertEquals(5, LandmarkService.listHomes(OWNER).size());
    }

    @Test
    void homeStoresRotation() {
        assertTrue(LandmarkService.addHome(ROT_OWNER, "rot",
                new HomeLocation("minecraft:overworld", 1, 2, 3, 45.5f, 12.25f)));
        HomeLocation loc = LandmarkService.getHome(ROT_OWNER, "rot");
        assertNotNull(loc);
        assertEquals(45.5f, loc.yaw());
        assertEquals(12.25f, loc.pitch());
    }

    @Test
    void publicLandmarkNamesAreUnique() {
        assertTrue(LandmarkService.addPublic("spawn", "minecraft:overworld", 0, 64, 0, 0, 0, BigDecimal.ZERO));
        assertFalse(LandmarkService.addPublic("spawn", "minecraft:overworld", 1, 64, 1, 0, 0, BigDecimal.ZERO));
        assertNotNull(LandmarkService.getPublic("spawn"));
        assertTrue(LandmarkService.removePublic("spawn"));
        assertNull(LandmarkService.getPublic("spawn"));
    }
}
