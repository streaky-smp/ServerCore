package com.servercore.data;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies pending {@link Migration}s and records what has been applied.
 *
 * <p>Existing data is never dropped or rewritten implicitly. Each migration runs
 * in its own transaction, so an interrupted upgrade leaves the schema at the
 * last fully applied version rather than somewhere in between.
 *
 * <p>A database newer than the code is treated as fatal. Continuing would mean
 * running queries written against an older schema, and the most likely outcome
 * is silent data corruption in the economy tables.
 */
public final class SchemaMigrator {

    private static final String VERSION_TABLE = "sc_schema_version";

    private final Database database;
    private final Logger logger;
    private final List<Migration> migrations;

    public SchemaMigrator(Database database, Logger logger, List<Migration> migrations) {
        this.database = database;
        this.logger = logger;
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::version));
        assertNoDuplicates(sorted);
        this.migrations = List.copyOf(sorted);
    }

    /** Highest version this build of the plugin knows how to produce. */
    public int targetVersion() {
        return migrations.isEmpty() ? 0 : migrations.getLast().version();
    }

    /**
     * Brings the schema up to {@link #targetVersion()}.
     *
     * @return the number of migrations applied
     */
    public int migrate() {
        ensureVersionTable();
        int current = currentVersion();
        int target = targetVersion();

        if (current > target) {
            throw new DataAccessException("Database schema is at version " + current
                    + " but this build of ServerCore only understands version " + target
                    + ". This database was written by a newer version of the plugin. "
                    + "Refusing to start rather than risk corrupting it -- restore the newer plugin build, "
                    + "or restore a backup taken at schema version " + target + " or lower.");
        }

        if (current == target) {
            logger.info("Database schema up to date (version " + current + ")");
            return 0;
        }

        int applied = 0;
        for (Migration migration : migrations) {
            if (migration.version() <= current) {
                continue;
            }
            applyMigration(migration);
            applied++;
        }
        logger.info("Database schema migrated from version " + current + " to " + target
                + " (" + applied + " migration" + (applied == 1 ? "" : "s") + " applied)");
        return applied;
    }

    private void applyMigration(Migration migration) {
        logger.log(Level.FINE, "Applying schema migration {0}: {1}",
                new Object[]{migration.version(), migration.description()});
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : migration.statements()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + VERSION_TABLE + " (version, applied_at, description) VALUES (?, ?, ?)")) {
                insert.setInt(1, migration.version());
                insert.setLong(2, System.currentTimeMillis());
                insert.setString(3, migration.description());
                insert.executeUpdate();
            }
            return null;
        });
    }

    private void ensureVersionTable() {
        database.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS %s (
                            version     INTEGER NOT NULL PRIMARY KEY,
                            applied_at  INTEGER NOT NULL,
                            description TEXT    NOT NULL
                        )
                        """.formatted(VERSION_TABLE));
            }
            return null;
        });
    }

    private int currentVersion() {
        return database.withConnection(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT COALESCE(MAX(version), 0) FROM " + VERSION_TABLE)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private static void assertNoDuplicates(List<Migration> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).version() == sorted.get(i - 1).version()) {
                throw new IllegalStateException(
                        "Duplicate migration version " + sorted.get(i).version()
                                + " -- each migration must have a unique version");
            }
        }
    }

    /** Exposed for diagnostics and the admin GUI. */
    public List<AppliedMigration> appliedMigrations() {
        return database.withConnection(connection -> {
            List<AppliedMigration> out = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT version, applied_at, description FROM " + VERSION_TABLE + " ORDER BY version")) {
                while (rs.next()) {
                    out.add(new AppliedMigration(rs.getInt(1), rs.getLong(2), rs.getString(3)));
                }
            }
            return List.copyOf(out);
        });
    }

    public record AppliedMigration(int version, long appliedAt, String description) {
    }
}
