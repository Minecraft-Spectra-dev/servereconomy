package cn.choosec.economy.economy;

import cn.choosec.economy.config.EconomyConfig;
import cn.choosec.economy.database.DatabaseManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared ledger scenarios against a real MySQL server.
 *
 * <p>The suite is skipped (not failed) when the server is unreachable so the CI
 * build stays green without a database. Connection settings come from system
 * properties ({@code servereconomy.test.mysql.host/port/user/password/database}),
 * forwarded from Gradle {@code -P} flags, with localhost/root defaults. A dedicated
 * {@code servereconomy_test} database is dropped and recreated on every run.
 */
class EconomyServiceMySQLTest extends AbstractEconomyServiceTest {

    private static EconomyConfig.DatabaseConfig cfg;

    @BeforeAll
    static void setUp() throws Exception {
        cfg = mysqlConfig();
        resetDatabase(cfg);
        startBackend(cfg, null);
    }

    @Test
    void schemaIsIdempotentAcrossRestarts() throws Exception {
        assertNotNull(EconomyService.set(ALICE, "Alice", new BigDecimal("1.00"), "before restart"));
        DatabaseManager.close();
        DatabaseManager.init(null, cfg);

        assertEquals(new BigDecimal("1.00"), EconomyService.getBalance(ALICE, "Alice"));
        assertTrue(EconomyService.add(ALICE, "Alice", new BigDecimal("1.00"), "after restart") != null);
    }

    private static EconomyConfig.DatabaseConfig mysqlConfig() {
        EconomyConfig.DatabaseConfig c = new EconomyConfig.DatabaseConfig();
        c.type = "mysql";
        c.host = prop("host", "127.0.0.1");
        c.port = Integer.parseInt(prop("port", "3306"));
        c.database = prop("database", "servereconomy_test");
        c.username = prop("user", "root");
        c.password = prop("password", "");
        c.extraParams = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        return c;
    }

    private static String prop(String key, String fallback) {
        String value = System.getProperty("servereconomy.test.mysql." + key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void resetDatabase(EconomyConfig.DatabaseConfig c) {
        Assumptions.assumeTrue(c.database != null && c.database.matches("[A-Za-z0-9_]+"),
                "Invalid MySQL test database name: " + c.database);
        String admin = "jdbc:mysql://" + c.host + ":" + c.port
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        try (Connection conn = DriverManager.getConnection(admin, c.username, c.password);
             Statement st = conn.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + c.database);
            st.execute("CREATE DATABASE " + c.database
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        } catch (SQLException e) {
            Assumptions.assumeTrue(false, "MySQL not reachable at " + c.host + ":" + c.port
                    + " (" + e.getMessage() + "); set servereconomy.test.mysql.* to enable MySQL tests");
        }
    }
}
