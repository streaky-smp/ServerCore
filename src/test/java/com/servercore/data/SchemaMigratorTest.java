package com.servercore.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Upgrade safety: never lose data, never run against a schema we do not understand. */
class SchemaMigratorTest {

    private static final Logger LOGGER = Logger.getLogger("SchemaMigratorTest");

    private Database database;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "schema.db", 4, 5_000L);
        database.onEnable();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    @Test
    @DisplayName("the real schema applies cleanly from empty")
    void appliesProductionSchema() {
        SchemaMigrator migrator = new SchemaMigrator(database, LOGGER, Schema.migrations());
        int applied = migrator.migrate();

        assertEquals(Schema.migrations().size(), applied);
        assertTrue(tableExists("sc_player"));
        assertTrue(tableExists("sc_audit_log"));
        assertTrue(tableExists("sc_notification"));
    }

    @Test
    @DisplayName("migrating twice is a no-op")
    void migrationIsIdempotent() {
        SchemaMigrator first = new SchemaMigrator(database, LOGGER, Schema.migrations());
        first.migrate();

        SchemaMigrator second = new SchemaMigrator(database, LOGGER, Schema.migrations());
        assertEquals(0, second.migrate(), "a restart must not re-run migrations");
    }

    @Test
    @DisplayName("only pending migrations are applied")
    void appliesOnlyWhatIsMissing() {
        List<Migration> v1 = List.of(new Migration(1, "first",
                List.of("CREATE TABLE t1 (id INTEGER PRIMARY KEY)")));
        new SchemaMigrator(database, LOGGER, v1).migrate();

        List<Migration> v1AndV2 = List.of(
                v1.getFirst(),
                new Migration(2, "second", List.of("CREATE TABLE t2 (id INTEGER PRIMARY KEY)")));

        assertEquals(1, new SchemaMigrator(database, LOGGER, v1AndV2).migrate());
        assertTrue(tableExists("t1"));
        assertTrue(tableExists("t2"));
    }

    @Test
    @DisplayName("a database newer than the code is refused, not silently used")
    void refusesToDowngrade() {
        List<Migration> newer = List.of(
                new Migration(1, "first", List.of("CREATE TABLE t1 (id INTEGER PRIMARY KEY)")),
                new Migration(2, "second", List.of("CREATE TABLE t2 (id INTEGER PRIMARY KEY)")));
        new SchemaMigrator(database, LOGGER, newer).migrate();

        // Simulates an operator rolling the plugin back a version while keeping
        // the database a newer build already upgraded.
        List<Migration> older = List.of(newer.getFirst());
        DataAccessException failure = assertThrows(DataAccessException.class,
                () -> new SchemaMigrator(database, LOGGER, older).migrate());

        assertTrue(failure.getMessage().contains("newer version"),
                "the error must tell the operator what actually happened");
    }

    @Test
    @DisplayName("a failed migration does not record its version")
    void failedMigrationLeavesVersionUnchanged() {
        List<Migration> broken = List.of(
                new Migration(1, "good", List.of("CREATE TABLE ok (id INTEGER PRIMARY KEY)")),
                new Migration(2, "broken", List.of("THIS IS NOT VALID SQL")));

        assertThrows(RuntimeException.class,
                () -> new SchemaMigrator(database, LOGGER, broken).migrate());

        // Version 1 committed in its own transaction and stands; version 2 rolled
        // back entirely, so a fixed build can retry it.
        List<SchemaMigrator.AppliedMigration> applied =
                new SchemaMigrator(database, LOGGER, List.of(broken.getFirst())).appliedMigrations();
        assertEquals(1, applied.size());
        assertEquals(1, applied.getFirst().version());
    }

    @Test
    @DisplayName("duplicate migration versions are a programming error")
    void rejectsDuplicateVersions() {
        List<Migration> duplicated = List.of(
                new Migration(1, "a", List.of("CREATE TABLE a (id INTEGER PRIMARY KEY)")),
                new Migration(1, "b", List.of("CREATE TABLE b (id INTEGER PRIMARY KEY)")));

        assertThrows(IllegalStateException.class,
                () -> new SchemaMigrator(database, LOGGER, duplicated));
    }

    private boolean tableExists(String name) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }
}
