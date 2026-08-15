package cn.choosec.economy.database;

import cn.choosec.economy.ServerEconomy;
import cn.choosec.economy.config.EconomyConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
 *
 * <p><strong>Main-thread safety.</strong> Every JDBC call — including calls on
 * the {@link Statement}, {@link PreparedStatement} and {@link ResultSet}
 * objects returned by the connection proxy — is executed on a single dedicated
 * database worker thread, never on the Minecraft server thread. Calls made
 * from the server thread wait at most {@link #MAIN_THREAD_SQL_TIMEOUT_MS} for
 * the result; if the database is stuck (for example a stalled MySQL server),
 * the wait times out, a circuit breaker opens for
 * {@link #DB_CIRCUIT_BREAKER_MS} and subsequent server-thread calls fail fast
 * while the queued cleanup work is still allowed to finish on the worker. This
 * bounds the worst-case server-thread stall and prevents watchdog crashes.
 * Startup and explicit blocking operations use {@link #callBlocking(DbTask)}.
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

    /** Marks the dedicated database worker thread so it can execute JDBC inline instead of queueing behind itself. */
    private static final ThreadLocal<Boolean> DB_WORKER = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Single worker that owns the shared JDBC connection; every SQL statement runs here. */
    private static final String DB_WORKER_NAME = "ServerEconomy-DB";
    private static final ExecutorService DB_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            DB_WORKER.set(Boolean.TRUE);
            r.run();
        }, DB_WORKER_NAME);
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Per-caller flag that disables the bounded server-thread wait. Used while the
     * database schema is being created so a slow first connection cannot fail startup.
     */
    private static final ThreadLocal<Boolean> ALLOW_BLOCKING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Upper bound for one server-thread JDBC round-trip before the watchdog guard trips. */
    private static final long MAIN_THREAD_SQL_TIMEOUT_MS = 500L;

    /**
     * Upper bound for the server thread's accumulated JDBC waits inside one
     * command/operation. Slow-but-under-the-per-call-timeout round-trips (for
     * example 400 ms × 100 result rows) therefore also trip the circuit breaker.
     */
    private static final long MAIN_THREAD_TOTAL_WAIT_BUDGET_MS = 2_000L;

    /** Idle gap after which the server-thread wait budget resets (operation boundary). */
    private static final long MAIN_WAIT_BUDGET_RESET_IDLE_MS = 1_000L;

    private static final ThreadLocal<Long> MAIN_WAIT_NANOS = ThreadLocal.withInitial(() -> 0L);
    private static final ThreadLocal<Long> MAIN_LAST_WAIT_AT_MS = ThreadLocal.withInitial(() -> 0L);

    /** How long the fast-fail circuit stays open after a server-thread database timeout. */
    private static final long DB_CIRCUIT_BREAKER_MS = 5_000L;

    /** SQLState used for "database busy" failures so they can be logged without spam. */
    private static final String BUSY_SQL_STATE = "SEBUS";

    private static final AtomicLong CIRCUIT_OPEN_UNTIL = new AtomicLong();
    private static final AtomicBoolean CIRCUIT_WARNING_LOGGED = new AtomicBoolean();

    /** A unit of work executed on the database worker thread. */
    @FunctionalInterface
    public interface DbTask<T> {
        T run() throws Exception;
    }

    private DatabaseManager() {
    }

    /**
     * Initialize the database and schema. Call once at server start.
     *
     * @param configDir the server config directory (used to locate the SQLite file)
     * @param cfg       the {@code database} section of the economy config
     */
    public static void init(Path configDir, EconomyConfig.DatabaseConfig cfg) throws SQLException, IOException {
        // A fresh init (first start, database switch or restart) starts with a
        // clean slate: forget any circuit-breaker state left by a previous stall.
        CIRCUIT_OPEN_UNTIL.set(0L);
        CIRCUIT_WARNING_LOGGED.set(false);
        // Schema creation may legitimately take longer than the per-call watchdog
        // budget (many CREATE TABLE round-trips on a remote MySQL server), so the
        // initialization thread is allowed to wait for the worker without timeout.
        ALLOW_BLOCKING.set(Boolean.TRUE);
        try {
            String type = cfg.type == null ? "sqlite" : cfg.type.trim().toLowerCase();
            if (type.equals("mysql")) {
                initMySql(cfg);
            } else if (type.equals("sqlite") || type.isEmpty()) {
                initSqlite(configDir);
            } else {
                throw new SQLException("Unknown database type '" + cfg.type + "'; expected 'sqlite' or 'mysql'.");
            }
        } finally {
            ALLOW_BLOCKING.remove();
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
                : cfg.extraParams.trim().replaceFirst("^\\?", "");
        // A stalled MySQL server must never pin the database worker forever. Add
        // network-level timeouts unless the admin already configured their own.
        if (!hasUrlParam(extra, "connectTimeout")) {
            extra = appendUrlParam(extra, "connectTimeout=5000");
        }
        if (!hasUrlParam(extra, "socketTimeout")) {
            extra = appendUrlParam(extra, "socketTimeout=30000");
        }
        dbUrl = base + (extra.isEmpty() ? "" : "?" + extra);
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
     * Return the shared connection wrapped in a non-closing async proxy.
     *
     * <p>Callers keep using {@code try (Connection c = DatabaseManager.open())} unchanged,
     * but {@code close()} on the proxy is a no-op so the single shared connection is reused
     * for every operation instead of opening a fresh connection each time. Every delegated
     * call — and every call on the statements/results returned by it — is serialised on the
     * database worker thread, so no JDBC I/O runs on the caller's thread.
     */
    public static Connection open() throws SQLException {
        synchronized (LOCK) {
            if (shared == null) {
                throw new SQLException("DatabaseManager not initialized; call init() first.");
            }
        }
        return asyncProxy(Connection.class, DatabaseManager::currentConnection, true);
    }

    private static Connection currentConnection() {
        synchronized (LOCK) {
            return shared;
        }
    }

    /** True when the calling thread is the dedicated database worker. */
    public static boolean isDbWorkerThread() {
        return Boolean.TRUE.equals(DB_WORKER.get());
    }

    /**
     * Run {@code task} on the database worker and wait for its result.
     *
     * <p>From the Minecraft server thread the wait is bounded by
     * {@link #MAIN_THREAD_SQL_TIMEOUT_MS}; after a timeout the circuit breaker
     * opens and later server-thread calls fail fast immediately after queueing,
     * so work order (and transactional cleanup) is preserved while the caller
     * never waits on a stuck database. From any other thread (or during
     * {@link #init}) this waits until the task completes, preserving the
     * synchronous API used by tests and integrations.
     */
    public static <T> T call(DbTask<T> task) throws SQLException {
        if (isDbWorkerThread()) {
            return runDbTaskForCall(task);
        }
        if (isServerThreadWaiting() && (isCircuitOpen() || mainThreadWaitBudgetExceeded())) {
            // High-level synchronous calls are not queued while the breaker is
            // open: rejecting without side effects is safer than applying an
            // operation the caller has already been told failed. Payout-style
            // EconomyService.add() re-queues itself asynchronously, and JDBC
            // proxy cleanup calls below still queue so rollbacks are preserved.
            throw busySqlException("database is busy; operation skipped to keep the server thread responsive");
        }
        Future<T> future = submitDbTask(task);
        if (isServerThreadWaiting()) {
            long started = System.nanoTime();
            try {
                T result = future.get(MAIN_THREAD_SQL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                recordMainThreadWait(started);
                return result;
            } catch (TimeoutException e) {
                recordMainThreadWait(started);
                openCircuit();
                throw timeoutSqlException("database call exceeded " + MAIN_THREAD_SQL_TIMEOUT_MS + " ms on the server thread");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the database worker", e);
            } catch (ExecutionException e) {
                throw unwrapExecution(e.getCause());
            }
        }
        return awaitUnbounded(future);
    }

    /**
     * Run {@code task} on the database worker and wait without the server-thread
     * timeout/circuit guard. Intended for server-stop flushes and other points
     * where no tick watchdog is running.
     */
    public static <T> T callBlocking(DbTask<T> task) throws SQLException {
        if (isDbWorkerThread()) {
            return runDbTaskForCall(task);
        }
        return awaitUnbounded(submitDbTask(task));
    }

    /**
     * Submit {@code task} to the database worker without waiting. Useful for
     * high-frequency paths (flight billing, task progress) that must never block
     * the server thread.
     */
    public static <T> CompletableFuture<T> submitAsync(DbTask<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.run();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, DB_EXECUTOR);
    }

    /** Fire-and-forget variant of {@link #submitAsync(DbTask)}. */
    public static CompletableFuture<Void> runAsync(Runnable task) {
        return CompletableFuture.runAsync(() -> {
            if (isDbWorkerThread()) {
                task.run();
            } else {
                DB_WORKER.set(Boolean.TRUE);
                try {
                    task.run();
                } finally {
                    DB_WORKER.remove();
                }
            }
        }, DB_EXECUTOR);
    }

    private static <T> Future<T> submitDbTask(DbTask<T> task) throws SQLException {
        try {
            return DB_EXECUTOR.submit(() -> runDbTask(task));
        } catch (RejectedExecutionException e) {
            throw new SQLException("Database worker is shutting down", e);
        }
    }

    private static <T> T runDbTask(DbTask<T> task) throws Exception {
        return task.run();
    }

    private static <T> T runDbTaskForCall(DbTask<T> task) throws SQLException {
        try {
            return task.run();
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("Database task failed: " + e, e);
        }
    }

    private static <T> T awaitUnbounded(Future<T> future) throws SQLException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the database worker", e);
        } catch (ExecutionException e) {
            throw unwrapExecution(e.getCause());
        }
    }

    private static SQLException unwrapExecution(Throwable cause) throws SQLException {
        if (cause instanceof SQLException se) {
            throw se;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new SQLException("Database task failed: " + cause, cause);
    }

    private static boolean isServerThreadWaiting() {
        return ServerEconomy.isServerThread() && !ALLOW_BLOCKING.get();
    }

    /** True for the fast-fail SQLExceptions produced by the server-thread watchdog guard. */
    public static boolean isBusyException(SQLException e) {
        return e != null && BUSY_SQL_STATE.equals(e.getSQLState());
    }

    private static SQLException busySqlException(String message) {
        return new SQLException(message, BUSY_SQL_STATE, 0);
    }

    private static SQLException timeoutSqlException(String message) {
        return new SQLException(message, BUSY_SQL_STATE, 0, new SQLTimeoutException(message));
    }

    /** True when the current server-thread operation has already used its cumulative wait budget. */
    private static boolean mainThreadWaitBudgetExceeded() {
        long now = System.currentTimeMillis();
        Long last = MAIN_LAST_WAIT_AT_MS.get();
        if (last == 0L || now - last > MAIN_WAIT_BUDGET_RESET_IDLE_MS) {
            MAIN_WAIT_NANOS.set(0L);
            MAIN_LAST_WAIT_AT_MS.set(now);
            return false;
        }
        return MAIN_WAIT_NANOS.get() >= MAIN_THREAD_TOTAL_WAIT_BUDGET_MS * 1_000_000L;
    }

    private static void recordMainThreadWait(long startedNanos) {
        MAIN_WAIT_NANOS.set(MAIN_WAIT_NANOS.get() + Math.max(0L, System.nanoTime() - startedNanos));
        MAIN_LAST_WAIT_AT_MS.set(System.currentTimeMillis());
        if (MAIN_WAIT_NANOS.get() >= MAIN_THREAD_TOTAL_WAIT_BUDGET_MS * 1_000_000L) {
            openCircuit();
        }
    }

    private static boolean isCircuitOpen() {
        long openUntil = CIRCUIT_OPEN_UNTIL.get();
        if (openUntil == 0L) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now >= openUntil) {
            CIRCUIT_OPEN_UNTIL.compareAndSet(openUntil, 0L);
            CIRCUIT_WARNING_LOGGED.set(false);
            return false;
        }
        return true;
    }

    private static void openCircuit() {
        long now = System.currentTimeMillis();
        if (CIRCUIT_OPEN_UNTIL.get() < now
                && CIRCUIT_OPEN_UNTIL.compareAndSet(0L, now + DB_CIRCUIT_BREAKER_MS)) {
            CIRCUIT_WARNING_LOGGED.set(false);
        }
        if (CIRCUIT_WARNING_LOGGED.compareAndSet(false, true)) {
            ServerEconomy.LOGGER.warn(
                    "[ServerEconomy] Database is not responding on the server thread; "
                            + "failing fast for {} ms while the database worker catches up",
                    DB_CIRCUIT_BREAKER_MS);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T asyncProxy(Class<T> type, Supplier<Object> targetSupplier, boolean connectionLevel) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                new AsyncJdbcInvocationHandler(type, targetSupplier, connectionLevel));
    }

    /** Wrap JDBC objects returned by the worker so their methods are offloaded too. */
    private static Object wrapJdbcResult(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof CallableStatement callable) {
            return asyncProxy(CallableStatement.class, () -> callable, false);
        }
        if (value instanceof PreparedStatement prepared) {
            return asyncProxy(PreparedStatement.class, () -> prepared, false);
        }
        if (value instanceof Statement statement) {
            return asyncProxy(Statement.class, () -> statement, false);
        }
        if (value instanceof ResultSet results) {
            return asyncProxy(ResultSet.class, () -> results, false);
        }
        if (value instanceof Connection connection) {
            return openOrWrapConnection(connection);
        }
        if (value instanceof DatabaseMetaData metadata) {
            return asyncProxy(DatabaseMetaData.class, () -> metadata, false);
        }
        return value;
    }

    private static Connection openOrWrapConnection(Connection connection) {
        try {
            return open();
        } catch (SQLException e) {
            // The physical connection is valid at this point; fall back to a
            // non-connection-level proxy around it.
            return asyncProxy(Connection.class, () -> connection, false);
        }
    }

    /**
     * Dispatches one JDBC method invocation.
     *
     * <p>The call is always submitted to the worker first. On the server thread it
     * is then awaited with the watchdog budget; if that budget is exhausted the
     * task is left running (so transactional cleanup queued behind it still
     * executes) and the caller receives a busy/timeout SQLException immediately.
     */
    private static Object dispatchJdbcCall(Callable<Object> task) throws Throwable {
        if (isDbWorkerThread()) {
            return task.call();
        }
        boolean serverThread = isServerThreadWaiting();
        if (serverThread && mainThreadWaitBudgetExceeded()) {
            // The cumulative wait for this server-thread operation already used
            // its budget; open the breaker so the call below fails fast while
            // still being queued for transactional cleanup.
            openCircuit();
        }
        Future<Object> future;
        try {
            future = DB_EXECUTOR.submit(task);
        } catch (RejectedExecutionException e) {
            throw new SQLException("Database worker is shutting down", e);
        }
        if (serverThread) {
            if (isCircuitOpen()) {
                throw busySqlException("database is busy; call was queued and will finish in the background");
            }
            long started = System.nanoTime();
            try {
                Object result = future.get(MAIN_THREAD_SQL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                recordMainThreadWait(started);
                return result;
            } catch (TimeoutException e) {
                recordMainThreadWait(started);
                openCircuit();
                throw timeoutSqlException("database call exceeded " + MAIN_THREAD_SQL_TIMEOUT_MS + " ms on the server thread");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for the database worker", e);
            } catch (ExecutionException e) {
                throw unwrapExecution(e.getCause());
            }
        }
        return awaitUnbounded(future);
    }

    private static final class AsyncJdbcInvocationHandler implements InvocationHandler {
        private final Class<?> type;
        private final Supplier<Object> targetSupplier;
        private final boolean connectionLevel;

        private AsyncJdbcInvocationHandler(Class<?> type, Supplier<Object> targetSupplier, boolean connectionLevel) {
            this.type = type;
            this.targetSupplier = targetSupplier;
            this.connectionLevel = connectionLevel;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return handleObjectMethod(proxy, name, args);
            }
            if (connectionLevel && "close".equals(name)) {
                return null; // keep the shared connection alive
            }
            if (connectionLevel && "isClosed".equals(name)) {
                return Boolean.FALSE;
            }
            if ("unwrap".equals(name) && args != null && args.length == 1 && args[0] instanceof Class<?> requested) {
                if (requested.isAssignableFrom(proxy.getClass())) {
                    return proxy;
                }
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1 && args[0] instanceof Class<?> requested) {
                if (requested.isAssignableFrom(proxy.getClass())) {
                    return Boolean.TRUE;
                }
            }
            return dispatchJdbcCall(() -> {
                try {
                    return invokeDirect(proxy, method, args);
                } catch (Exception e) {
                    throw e;
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
        }

        private Object handleObjectMethod(Object proxy, String name, Object[] args) {
            return switch (name) {
                case "toString" -> "ServerEconomy async JDBC proxy for " + type.getSimpleName();
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            };
        }

        private Object invokeDirect(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
            synchronized (LOCK) {
                Object target = targetSupplier.get();
                if (target == null) {
                    throw new SQLException("DatabaseManager not initialized; call init() first.");
                }
                try {
                    return wrapJdbcResult(method.invoke(target, args));
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    // A dropped MySQL/SQLite connection is recreated once so an idle
                    // timeout does not permanently break every money operation. Only
                    // connection-level proxies can safely retry against the new link.
                    if (connectionLevel && cause instanceof SQLException se && isConnectionBroken(se)) {
                        try {
                            reconnect();
                            return wrapJdbcResult(method.invoke(targetSupplier.get(), args));
                        } catch (java.lang.reflect.InvocationTargetException retry) {
                            throw retry.getCause();
                        }
                    }
                    throw cause;
                }
            }
        }
    }

    private static String appendUrlParam(String query, String param) {
        return query.isEmpty() ? param : query + "&" + param;
    }

    private static boolean hasUrlParam(String query, String name) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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
                home_limit_override INTEGER,
                title_purchased INTEGER NOT NULL DEFAULT 0
            );
            """);
        ensurePlayerMetaTitleColumn(st.getConnection());
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
            CREATE TABLE IF NOT EXISTS notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner TEXT NOT NULL,
                message TEXT NOT NULL,
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
            CREATE INDEX IF NOT EXISTS idx_notifications_owner ON notifications (owner);
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
                home_limit_override INT,
                title_purchased INT NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """);
        ensurePlayerMetaTitleColumn(st.getConnection());
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
            CREATE TABLE IF NOT EXISTS notifications (
                id BIGINT NOT NULL AUTO_INCREMENT,
                owner VARCHAR(36) NOT NULL,
                message TEXT NOT NULL,
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
        createIndex(st, "idx_notifications_owner", "notifications (owner)");
        createIndex(st, "idx_build_requests_player", "build_requests (player)");
        createIndex(st, "idx_balances_name", "balances (name)");
    }

    /**
     * Add the {@code title_purchased} column to an existing {@code player_meta}
     * table created by an older version of the mod (CREATE TABLE IF NOT EXISTS
     * never alters a table that already exists). Safe to call on every startup.
     */
    private static void ensurePlayerMetaTitleColumn(Connection c) throws SQLException {
        boolean found = false;
        if (dialect == Dialect.MYSQL) {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'player_meta'
                      AND COLUMN_NAME = 'title_purchased'""")) {
                try (ResultSet rs = ps.executeQuery()) {
                    found = rs.next() && rs.getInt(1) > 0;
                }
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(player_meta)");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("title_purchased".equalsIgnoreCase(rs.getString("name"))) {
                        found = true;
                        break;
                    }
                }
            }
        }
        if (!found) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("ALTER TABLE player_meta ADD COLUMN title_purchased INTEGER NOT NULL DEFAULT 0");
            }
        }
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

    /** Log a SQL error to the mod logger. Busy/timeout guard failures are logged once at circuit-open time. */
    public static void log(SQLException e) {
        if (BUSY_SQL_STATE.equals(e.getSQLState())) {
            return;
        }
        ServerEconomy.LOGGER.error("[ServerEconomy] SQL error: {}", e.getMessage(), e);
    }
}
