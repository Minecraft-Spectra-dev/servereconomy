package cn.choosec.economy.database;

import cn.choosec.economy.ServerEconomy;
import cn.choosec.economy.config.EconomyConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.Proxy;

/**
 * Owns the database location, dialect and schema.
 *
 * <p>All mod data (balances, transactions, landmarks, task progress, market
 * listings, per-player metadata) is persisted either to a single SQLite file
 * under the server config directory, or to an external MySQL server, as
 * configured in {@code servereconomy.json}.
 *
 * <p>A single shared connection is created at startup and reused for every
 * operation. {@link #open()} returns a non-closing proxy around that connection,
 * so callers can keep using try-with-resources without the cost of opening a
 * fresh connection (and a fresh TCP handshake on MySQL) per query.
 */
public final class DatabaseManager {

    /** Supported database backends. */
    public enum Dialect {
        SQLITE,
        MYSQL
    }

    private static Dialect dialect = Dialect.SQLITE;
    private static String dbUrl;
    private static String user;
    private static String password;
    private static Connection shared;
    private static final Object LOCK = new Object();

    private DatabaseManager() {
    }

    /**
     * Initialize the database and schema. Call once at server start.
     *
     * @param configDir the server config directory (used to locate the SQLite file)
     * @param cfg       the {@code database} section of the economy config
     */
    public static void init(Path configDir, EconomyConfig.DatabaseConfig cfg) throws SQLException, IOException {
        String type = cfg.type == null ? "sqlite" : cfg.type.trim().toLowerCase();
        if (type.equals("mysql")) {
            initMySql(cfg);
        } else if (type.equals("sqlite") || type.isEmpty()) {
            initSqlite(configDir);
        } else {
            throw new SQLException("Unknown database type '" + cfg.type + "'; expected 'sqlite' or 'mysql'.");
        }
    }

    private static void initSqlite(Path configDir) throws SQLException, IOException {
        dialect = Dialect.SQLITE;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc driver not found; check the mod dependencies.", e);
        }
        Path dbPath = configDir.resolve("servereconomy").resolve("economy.db");
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }
        dbUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        shared = DriverManager.getConnection(dbUrl);
        try (Connection connection = open()) {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                createSchema(st);
            }
        }
        ServerEconomy.LOGGER.info("[ServerEconomy] Database initialized (sqlite) at {}", dbPath.toAbsolutePath());
    }

    private static void initMySql(EconomyConfig.DatabaseConfig cfg) throws SQLException {
        dialect = Dialect.MYSQL;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL JDBC driver not found; check the mod dependencies.", e);
        }
        if (cfg.host == null || cfg.host.isBlank()) {
            throw new SQLException("MySQL host is not configured (servereconomy.json -> database.host).");
        }
        if (cfg.database == null || cfg.database.isBlank()) {
            throw new SQLException("MySQL database name is not configured (servereconomy.json -> database.database).");
        }
        String base = "jdbc:mysql://" + cfg.host.trim() + ":" + cfg.port + "/" + cfg.database.trim();
        String extra = (cfg.extraParams == null || cfg.extraParams.isBlank())
                ? ""
                : "?" + cfg.extraParams.trim().replaceFirst("^\\?", "");
        dbUrl = base + extra;
        user = cfg.username;
        password = cfg.password;
        shared = DriverManager.getConnection(dbUrl, user, password);
        try (Connection connection = open()) {
            try (Statement st = connection.createStatement()) {
                createSchema(st);
            }
        }
        ServerEconomy.LOGGER.info("[ServerEconomy] Database initialized (mysql) at {}:{}/{}",
                cfg.host, cfg.port, cfg.database);
    }

    /**
     * Return the shared connection wrapped in a non-closing proxy.
     *
     * <p>Callers keep using {@code try (Connection c = DatabaseManager.open())} unchanged,
     * but {@code close()} on the proxy is a no-op so the single shared connection is reused
     * for every operation instead of opening a fresh connection each time. Every delegated
     * call is serialised on {@link #LOCK} so the shared connection is never used concurrently.
     */
    public static Connection open() throws SQLException {
        synchronized (LOCK) {
            if (shared == null) {
                throw new SQLException("DatabaseManager not initialized; call init() first.");
            }
        }
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                DatabaseManager::invokeShared);
    }

    private static Object invokeShared(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("close".equals(name)) {
            return null; // keep the shared connection alive
        }
        if ("isClosed".equals(name)) {
            return Boolean.FALSE;
        }
        synchronized (LOCK) {
            try {
                return method.invoke(shared, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                // A dropped MySQL/SQLite connection is recreated once so an idle
                // timeout does not permanently break every money operation.
                if (cause instanceof SQLException se && isConnectionBroken(se)) {
                    try {
                        reconnect();
                        return method.invoke(shared, args);
                    } catch (java.lang.reflect.InvocationTargetException retry) {
                        throw retry.getCause();
                    }
                }
                throw cause;
            }
        }
    }

    /** True for connection-level failures (SQLState class 08*) rather than query errors. */
    private static boolean isConnectionBroken(SQLException e) {
        String state = e.getSQLState();
        return e instanceof java.sql.SQLNonTransientConnectionException
                || (state != null && state.startsWith("08"));
    }

    /** Replace the shared connection after a dropped link (best-effort, no schema re-creation). */
    private static void reconnect() throws SQLException {
        if (dbUrl == null) {
            throw new SQLException("DatabaseManager not initialized; call init() first.");
        }
        if (shared != null) {
            try {
                shared.close();
            } catch (SQLException ignored) {
            }
        }
        shared = dialect == Dialect.MYSQL
                ? DriverManager.getConnection(dbUrl, user, password)
                : DriverManager.getConnection(dbUrl);
    }

    /** The currently active dialect. */
    public static Dialect dialect() {
        return dialect;
    }

    /** True when the active backend is MySQL. */
    public static boolean isMySQL() {
        return dialect == Dialect.MYSQL;
    }

    private static void createSchema(Statement st) throws SQLException {
        if (dialect == Dialect.MYSQL) {
            createSchemaMySql(st);
        } else {
            createSchemaSqlite(st);
        }
    }

    private static void createSchemaSqlite(Statement st) throws SQLException {
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS balances (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                balance DECIMAL(20,2) NOT NULL DEFAULT 0.00
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                type TEXT NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                note TEXT,
                time BIGINT NOT NULL
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS supply (
                item TEXT PRIMARY KEY,
                sold_count INTEGER NOT NULL DEFAULT 0,
                total_value DECIMAL(20,2) NOT NULL DEFAULT 0.00
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS landmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                owner TEXT,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                world TEXT NOT NULL,
                yaw REAL NOT NULL DEFAULT 0.00,
                pitch REAL NOT NULL DEFAULT 0.00,
                cost DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                is_public INTEGER NOT NULL DEFAULT 0
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS player_meta (
                uuid TEXT PRIMARY KEY,
                purchased_slots INTEGER NOT NULL DEFAULT 0,
                purchased_home_slots INTEGER NOT NULL DEFAULT 0,
                fake_players_active INTEGER NOT NULL DEFAULT 0,
                home_limit_override INTEGER
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS titles (
                uuid TEXT PRIMARY KEY,
                title TEXT
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT,
                type TEXT NOT NULL,
                target TEXT,
                amount INTEGER NOT NULL DEFAULT 1,
                reward DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                enabled INTEGER NOT NULL DEFAULT 1
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS daily_tasks (
                player TEXT NOT NULL,
                date TEXT NOT NULL,
                task_id INTEGER NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                reward_claimed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (player, date, task_id)
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS market_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller TEXT NOT NULL,
                item TEXT NOT NULL,
                count INTEGER NOT NULL,
                price DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                item_data TEXT,
                time BIGINT NOT NULL,
                type TEXT NOT NULL DEFAULT 'SELL'
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS redpackets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender TEXT NOT NULL,
                lucky INTEGER NOT NULL DEFAULT 0,
                total DECIMAL(20,2) NOT NULL,
                remaining_amount DECIMAL(20,2) NOT NULL,
                count INTEGER NOT NULL,
                remaining_count INTEGER NOT NULL,
                time BIGINT NOT NULL
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS redpacket_taken (
                packet_id INTEGER NOT NULL,
                player TEXT NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                PRIMARY KEY (packet_id, player)
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS mailbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner TEXT NOT NULL,
                item_id TEXT NOT NULL,
                item_data TEXT,
                count INTEGER NOT NULL,
                time BIGINT NOT NULL
            );
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS build_requests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player TEXT NOT NULL,
                player_name TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                world TEXT NOT NULL,
                note TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                reward DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                time BIGINT NOT NULL
            );
            """);

        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_transactions_uuid_time ON transactions (uuid, time);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_transactions_time ON transactions (time);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_market_seller ON market_listings (seller);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_market_item ON market_listings (item);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_mailbox_owner ON mailbox (owner);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_build_requests_player ON build_requests (player);
            """);
        st.executeUpdate("""
            CREATE INDEX IF NOT EXISTS idx_balances_name ON balances (name);
            """);
    }

    private static void createSchemaMySql(Statement st) throws SQLException {
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS balances (
                uuid VARCHAR(36) PRIMARY KEY,
                name VARCHAR(64) NOT NULL,
                balance DECIMAL(20,2) NOT NULL DEFAULT 0.00
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGINT NOT NULL AUTO_INCREMENT,
                uuid VARCHAR(36) NOT NULL,
                type VARCHAR(32) NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                note TEXT,
                time BIGINT NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS supply (
                item VARCHAR(255) PRIMARY KEY,
                sold_count INT NOT NULL DEFAULT 0,
                total_value DECIMAL(20,2) NOT NULL DEFAULT 0.00
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS landmarks (
                id BIGINT NOT NULL AUTO_INCREMENT,
                name VARCHAR(255) NOT NULL,
                owner VARCHAR(36),
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                world VARCHAR(255) NOT NULL,
                yaw DOUBLE NOT NULL DEFAULT 0.00,
                pitch DOUBLE NOT NULL DEFAULT 0.00,
                cost DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                is_public INT NOT NULL DEFAULT 0,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS player_meta (
                uuid VARCHAR(36) PRIMARY KEY,
                purchased_slots INT NOT NULL DEFAULT 0,
                purchased_home_slots INT NOT NULL DEFAULT 0,
                fake_players_active INT NOT NULL DEFAULT 0,
                home_limit_override INT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS titles (
                uuid VARCHAR(36) PRIMARY KEY,
                title TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS tasks (
                id BIGINT NOT NULL AUTO_INCREMENT,
                name VARCHAR(255) NOT NULL,
                description TEXT,
                type VARCHAR(64) NOT NULL,
                target TEXT,
                amount INT NOT NULL DEFAULT 1,
                reward DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                enabled INT NOT NULL DEFAULT 1,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS daily_tasks (
                player VARCHAR(36) NOT NULL,
                date VARCHAR(16) NOT NULL,
                task_id BIGINT NOT NULL,
                progress INT NOT NULL DEFAULT 0,
                completed INT NOT NULL DEFAULT 0,
                reward_claimed INT NOT NULL DEFAULT 0,
                PRIMARY KEY (player, date, task_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS market_listings (
                id BIGINT NOT NULL AUTO_INCREMENT,
                seller VARCHAR(36) NOT NULL,
                item VARCHAR(255) NOT NULL,
                count INT NOT NULL,
                price DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                item_data TEXT,
                time BIGINT NOT NULL,
                type VARCHAR(16) NOT NULL DEFAULT 'SELL',
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS redpackets (
                id BIGINT NOT NULL AUTO_INCREMENT,
                sender VARCHAR(36) NOT NULL,
                lucky INT NOT NULL DEFAULT 0,
                total DECIMAL(20,2) NOT NULL,
                remaining_amount DECIMAL(20,2) NOT NULL,
                count INT NOT NULL,
                remaining_count INT NOT NULL,
                time BIGINT NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS redpacket_taken (
                packet_id BIGINT NOT NULL,
                player VARCHAR(36) NOT NULL,
                amount DECIMAL(20,2) NOT NULL,
                PRIMARY KEY (packet_id, player)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS mailbox (
                id BIGINT NOT NULL AUTO_INCREMENT,
                owner VARCHAR(36) NOT NULL,
                item_id VARCHAR(255) NOT NULL,
                item_data TEXT,
                count INT NOT NULL,
                time BIGINT NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        st.executeUpdate("""
            CREATE TABLE IF NOT EXISTS build_requests (
                id BIGINT NOT NULL AUTO_INCREMENT,
                player VARCHAR(36) NOT NULL,
                player_name VARCHAR(64) NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                world VARCHAR(255) NOT NULL,
                note TEXT,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                reward DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                time BIGINT NOT NULL,
                PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        // Best-effort secondary indexes (MySQL lacks CREATE INDEX IF NOT EXISTS;
        // ignore "duplicate key name" error 1061 on restart).
        createIndex(st, "idx_transactions_uuid_time", "transactions (uuid, time)");
        createIndex(st, "idx_transactions_time", "transactions (time)");
        createIndex(st, "idx_market_seller", "market_listings (seller)");
        createIndex(st, "idx_market_item", "market_listings (item)");
        createIndex(st, "idx_mailbox_owner", "mailbox (owner)");
        createIndex(st, "idx_build_requests_player", "build_requests (player)");
        createIndex(st, "idx_balances_name", "balances (name)");
    }

    private static void createIndex(Statement st, String name, String spec) throws SQLException {
        try {
            st.executeUpdate("CREATE INDEX " + name + " ON " + spec);
        } catch (SQLException e) {
            if (e.getErrorCode() != 1061 && e.getErrorCode() != 1832) {
                throw e;
            }
            // index already exists; ignore
        }
    }

    public static boolean isOpen() {
        return dbUrl != null;
    }

    /** Close the shared connection at server stop. */
    public static void close() {
        synchronized (LOCK) {
            if (shared != null) {
                try {
                    shared.close();
                } catch (SQLException e) {
                    log(e);
                }
                shared = null;
            }
            dbUrl = null;
        }
    }

    /** Log a SQL error to the mod logger. */
    public static void log(SQLException e) {
        ServerEconomy.LOGGER.error("[ServerEconomy] SQL error: {}", e.getMessage(), e);
    }
}
