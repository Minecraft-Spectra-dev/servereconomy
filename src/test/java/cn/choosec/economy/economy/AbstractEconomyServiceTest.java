package cn.choosec.economy.economy;

import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared ledger scenarios exercised against both the SQLite and MySQL backends.
 * Concrete subclasses only supply the backend connection settings.
 */
abstract class AbstractEconomyServiceTest {

    protected static final UUID ALICE = UUID.randomUUID();
    protected static final UUID BOB = UUID.randomUUID();

    protected static void startBackend(EconomyConfig.DatabaseConfig cfg, Path configDir) throws Exception {
        MoneyUtil.SCALE = 2;
        DatabaseManager.init(configDir, cfg);
    }

    @AfterAll
    static void tearDown() {
        DatabaseManager.close();
    }

    @BeforeEach
    void clearCache() {
        EconomyService.invalidateCache(ALICE);
        EconomyService.invalidateCache(BOB);
        EconomyService.invalidateCache(EconomyService.BANK_UUID);
    }

    @Test
    void setGetAddRemove() {
        assertTrue(EconomyService.set(ALICE, "Alice", new BigDecimal("100.00"), "init"));
        assertEquals(new BigDecimal("100.00"), EconomyService.getBalance(ALICE, "Alice"));
        assertEquals(new BigDecimal("150.00"), EconomyService.add(ALICE, "Alice", new BigDecimal("50.00"), "gift"));
        assertEquals(new BigDecimal("130.00"), EconomyService.remove(ALICE, "Alice", new BigDecimal("20.00"), "spend"));
        assertNull(EconomyService.remove(ALICE, "Alice", new BigDecimal("999.00"), "too much"));
        assertEquals(new BigDecimal("130.00"), EconomyService.getBalance(ALICE, "Alice"));
    }

    @Test
    void asyncVariantsRunOnTheDatabaseWorker() {
        assertTrue(EconomyService.set(ALICE, "Alice", new BigDecimal("10.00"), "init"));
        assertEquals(new BigDecimal("25.00"), EconomyService.addAsync(ALICE, "Alice", new BigDecimal("15.00"), "async gift").join());
        assertEquals(new BigDecimal("25.00"), EconomyService.getBalanceAsync(ALICE, "Alice").join());
        assertEquals(new BigDecimal("20.00"), EconomyService.removeAsync(ALICE, "Alice", new BigDecimal("5.00"), "async spend").join());
    }

    @Test
    void transferChargesFeeToBank() {
        EconomyService.set(ALICE, "Alice", new BigDecimal("100.00"), "init");
        EconomyService.set(BOB, "Bob", new BigDecimal("0.00"), "init");

        assertTrue(EconomyService.transfer(ALICE, "Alice", BOB, "Bob",
                new BigDecimal("10.00"), new BigDecimal("2")));
        assertEquals(new BigDecimal("90.00"), EconomyService.getBalance(ALICE, "Alice"));
        assertEquals(new BigDecimal("9.80"), EconomyService.getBalance(BOB, "Bob"));
        assertEquals(new BigDecimal("0.20"), EconomyService.getBalance(EconomyService.BANK_UUID, "bank"));
    }

    @Test
    void transferFailsAtomicallyOnInsufficientFunds() {
        EconomyService.set(ALICE, "Alice", new BigDecimal("5.00"), "init");
        EconomyService.set(BOB, "Bob", new BigDecimal("0.00"), "init");

        assertFalse(EconomyService.transfer(ALICE, "Alice", BOB, "Bob",
                new BigDecimal("10.00"), new BigDecimal("2")));
        assertEquals(new BigDecimal("5.00"), EconomyService.getBalance(ALICE, "Alice"));
        assertEquals(new BigDecimal("0.00"), EconomyService.getBalance(BOB, "Bob"));
    }

    @Test
    void nullFeeBehavesLikeFreeTransfer() {
        EconomyService.set(ALICE, "Alice", new BigDecimal("50.00"), "init");
        EconomyService.set(BOB, "Bob", new BigDecimal("0.00"), "init");

        assertTrue(EconomyService.transfer(ALICE, "Alice", BOB, "Bob",
                new BigDecimal("12.00"), null));
        assertEquals(new BigDecimal("12.00"), EconomyService.getBalance(BOB, "Bob"));
        assertEquals(new BigDecimal("38.00"), EconomyService.getBalance(ALICE, "Alice"));
    }

    @Test
    void accountNameUpdatesAfterRename() {
        EconomyService.set(ALICE, "OldName", new BigDecimal("1.00"), "init");
        EconomyService.add(ALICE, "NewName", new BigDecimal("1.00"), "rename login");

        assertNull(EconomyService.uuidByName("OldName"));
        assertEquals(ALICE, EconomyService.uuidByName("NewName"));
    }

    @Test
    void transactionLogAndPurge() throws Exception {
        EconomyService.set(BOB, "Bob", new BigDecimal("7.00"), "init");
        List<String[]> log = EconomyService.getLog(BOB, 10);
        assertFalse(log.isEmpty());
        assertEquals("SET", log.get(0)[0]);

        try (Connection c = DatabaseManager.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO transactions (uuid, type, amount, note, time) VALUES (?, 'SET', 1.00, 'old', ?)")) {
            ps.setString(1, BOB.toString());
            ps.setLong(2, System.currentTimeMillis() - 2 * 86_400_000L);
            ps.executeUpdate();
        }
        assertTrue(EconomyService.purgeOldTransactions(1) >= 1);
        assertEquals(-1, EconomyService.purgeOldTransactions(0));
    }
}
