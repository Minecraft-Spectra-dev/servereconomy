package cn.choosec.economy.service;

import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import cn.choosec.economy.economy.EconomyService;
import cn.choosec.economy.economy.MoneyUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests for the approve-once transactional flow. */
class BuildRequestServiceTest {

    private static final UUID PLAYER = UUID.randomUUID();

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

    @BeforeEach
    void clearCache() {
        EconomyService.invalidateCache(PLAYER);
    }

    @Test
    void approvePaysExactlyOnceAndLocksStatus() {
        long id = BuildRequestService.submit(PLAYER, "Tester", "minecraft:overworld", 1, 2, 3, "note");
        assertTrue(id > 0);

        assertEquals(BuildRequestService.ApproveResult.SUCCESS,
                BuildRequestService.approve(id, new BigDecimal("100.00")));
        assertEquals(new BigDecimal("100.00"), EconomyService.getBalance(PLAYER, "Tester"));
        assertEquals(BuildRequestService.APPROVED, BuildRequestService.get(id).status());

        assertEquals(BuildRequestService.ApproveResult.NOT_PENDING,
                BuildRequestService.approve(id, new BigDecimal("100.00")));
        assertEquals(new BigDecimal("100.00"), EconomyService.getBalance(PLAYER, "Tester"));
    }

    @Test
    void rejectLocksStatusAndPreventsPayout() {
        long id = BuildRequestService.submit(PLAYER, "Tester", "minecraft:overworld", 4, 5, 6, null);
        assertTrue(BuildRequestService.reject(id));
        assertEquals(BuildRequestService.REJECTED, BuildRequestService.get(id).status());

        BigDecimal before = EconomyService.getBalance(PLAYER, "Tester");
        assertEquals(BuildRequestService.ApproveResult.NOT_PENDING,
                BuildRequestService.approve(id, new BigDecimal("50.00")));
        assertEquals(before, EconomyService.getBalance(PLAYER, "Tester"));
    }

    @Test
    void approveMissingIdIsNotFound() {
        assertEquals(BuildRequestService.ApproveResult.NOT_FOUND,
                BuildRequestService.approve(999_999L, new BigDecimal("1.00")));
    }
}
