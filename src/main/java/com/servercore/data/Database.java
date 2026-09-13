package com.servercore.data;

import com.servercore.core.Service;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Connection management and transaction boundaries.
 *
 * <p>Backed by SQLite in WAL mode, which permits many concurrent readers
 * alongside a single writer. That shape drives two rules the rest of the plugin
 * depends on:
 *
 * <ul>
 *   <li>Anything that writes opens with {@code BEGIN IMMEDIATE} via
 *       {@link #inTransaction}. Taking the write lock up front avoids the
 *       classic SQLite deadlock where two deferred transactions each hold a read
 *       lock and both fail trying to upgrade.</li>
 *   <li>No call on this class may run on the main thread. Every method asserts
 *       that, because a blocking database call during a tick stalls the whole
 *       server.</li>
 * </ul>
 *
 * <p>Business logic never sees a {@link Connection} directly; repositories do.
 * That is what keeps a future swap to another database from rippling outward.
 */
public final class Database implements Service {

    /** Unit of work executed inside a transaction. */
    @FunctionalInterface
    public interface TransactionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    /** Unit of work that needs a connection but manages no transaction of its own. */
    @FunctionalInterface
    public interface ConnectionWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final File dataFolder;
    private final Logger logger;
    private final ThreadGuard threadGuard;
    private final String fileName;
    private final int poolSize;
    private final long busyTimeoutMillis;

    private HikariDataSource dataSource;

    public Database(File dataFolder,
                    Logger logger,
                    ThreadGuard threadGuard,
                    String fileName,
                    int poolSize,
                    long busyTimeoutMillis) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.threadGuard = threadGuard;
        this.fileName = fileName;
        this.poolSize = poolSize;
        this.busyTimeoutMillis = busyTimeoutMillis;
    }

    /** Convenience for the running plugin. */
    public static Database forPlugin(JavaPlugin plugin, String fileName, int poolSize, long busyTimeoutMillis) {
        return new Database(plugin.getDataFolder(), plugin.getLogger(), ThreadGuard.bukkit(),
                fileName, poolSize, busyTimeoutMillis);
    }

    @Override
    public void onEnable() throws Exception {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dataFolder);
        }
        File dbFile = new File(dataFolder, fileName);

        SQLiteConfig sqlite = new SQLiteConfig();
        // WAL is what allows reads to proceed during a write. Without it every
        // reader blocks behind the writer and the GUIs stutter under load.
        sqlite.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // NORMAL is durable against process crashes under WAL; FULL costs an
        // fsync per commit for protection only against OS-level power loss.
        sqlite.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqlite.enforceForeignKeys(true);
        sqlite.setBusyTimeout((int) busyTimeoutMillis);
        // Every transaction we open takes the write lock immediately rather than
        // starting deferred and trying to upgrade later. Upgrading is what
        // produces SQLITE_BUSY deadlocks when two writers overlap. Setting it
        // here means the driver issues BEGIN IMMEDIATE itself on
        // setAutoCommit(false) -- issuing our own BEGIN on top of that is a
        // nested-transaction error.
        sqlite.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqlite);
        sqliteDataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        HikariConfig hikari = new HikariConfig();
        // Handing Hikari a configured DataSource rather than a driver class name
        // sidesteps JDBC driver discovery, which is unreliable under Paper's
        // library-loader classloader.
        hikari.setDataSource(sqliteDataSource);
        hikari.setPoolName("ServerCore-SQLite");
        hikari.setMaximumPoolSize(Math.max(2, poolSize));
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(Math.max(2_000L, busyTimeoutMillis + 2_000L));
        hikari.setLeakDetectionThreshold(30_000L);
        hikari.setAutoCommit(true);

        this.dataSource = new HikariDataSource(hikari);

        // Fail fast: prove we can actually open the file before anything else
        // starts and begins assuming persistence works.
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.isValid(5)) {
                throw new SQLException("Connection reported invalid immediately after opening");
            }
        }
        logger.info("Database ready at " + dbFile.getName()
                + " (WAL, pool=" + hikari.getMaximumPoolSize() + ")");
    }

    @Override
    public void onDisable() {
        if (dataSource == null) {
            return;
        }
        try {
            // Fold the WAL back into the main database file so operators can copy
            // a single consistent file for backups.
            try (Connection connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "WAL checkpoint on shutdown failed", e);
        } finally {
            dataSource.close();
            dataSource = null;
        }
    }

    /**
     * Runs {@code work} inside an immediate (write) transaction, committing on
     * success and rolling back on any failure.
     *
     * <p>Every money-moving operation in the plugin goes through here. The
     * rollback is what makes "no item or currency disappears if a transaction
     * fails halfway" true rather than aspirational.
     */
    public <T> T inTransaction(TransactionWork<T> work) {
        requireOffMainThread();
        try (Connection connection = connection()) {
            // Opens an IMMEDIATE transaction, per the transaction mode configured
            // on the data source in onEnable().
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Transaction failed", e);
        }
    }

    /** Runs {@code work} against a connection in autocommit mode, for single reads. */
    public <T> T withConnection(ConnectionWork<T> work) {
        requireOffMainThread();
        try (Connection connection = connection()) {
            return work.execute(connection);
        } catch (SQLException e) {
            throw new DataAccessException("Query failed", e);
        }
    }

    /**
     * Borrows a raw connection. Callers are responsible for closing it, and for
     * the transaction rules described on this class.
     */
    public Connection connection() throws SQLException {
        HikariDataSource source = this.dataSource;
        if (source == null) {
            throw new SQLException("Database is not open");
        }
        return source.getConnection();
    }

    public boolean isOpen() {
        return dataSource != null && !dataSource.isClosed();
    }

    private void requireOffMainThread() {
        if (threadGuard.isMainThread()) {
            throw new IllegalStateException(
                    "Database access attempted on the main server thread. Wrap the call in "
                            + "Scheduling.supplyAsync(...) and deliver the result with thenSync(...).");
        }
    }
}
