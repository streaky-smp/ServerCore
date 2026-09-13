package com.servercore.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRepositoryTest {

    private static final Logger LOGGER = Logger.getLogger("PlayerRepositoryTest");

    private Database database;
    private PlayerRepository players;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        database = new Database(new File(temp.toFile(), "data"), LOGGER,
                ThreadGuard.offMainThread(), "players.db", 4, 5_000L);
        database.onEnable();
        new SchemaMigrator(database, LOGGER, Schema.migrations()).migrate();
        players = new PlayerRepository(database);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.onDisable();
        }
    }

    @Test
    @DisplayName("first join creates a row")
    void firstJoinCreatesRow() {
        UUID id = UUID.randomUUID();
        players.touch(id, "Steve", PlayerRecord.Platform.JAVA);

        Optional<PlayerRecord> found = players.findByUuid(id);
        assertTrue(found.isPresent());
        assertEquals("Steve", found.get().name());
        assertFalse(found.get().isBedrock());
        assertEquals(1, players.count());
    }

    @Test
    @DisplayName("a rename updates the existing row rather than duplicating the player")
    void renameUpdatesInPlace() {
        UUID id = UUID.randomUUID();
        players.touch(id, "OldName", PlayerRecord.Platform.JAVA);
        players.touch(id, "NewName", PlayerRecord.Platform.JAVA);

        assertEquals(1, players.count(), "a rename must not create a second identity");
        assertEquals("NewName", players.findByUuid(id).orElseThrow().name());
        assertTrue(players.findByName("OldName").isEmpty());
        assertEquals(id, players.findByName("NewName").orElseThrow().uuid());
    }

    @Test
    @DisplayName("name lookup ignores case")
    void nameLookupIsCaseInsensitive() {
        UUID id = UUID.randomUUID();
        players.touch(id, "SteveTheBuilder", PlayerRecord.Platform.JAVA);

        assertEquals(id, players.findByName("stevethebuilder").orElseThrow().uuid());
        assertEquals(id, players.findByName("STEVETHEBUILDER").orElseThrow().uuid());
    }

    @Test
    @DisplayName("platform is recorded for Bedrock players")
    void recordsBedrockPlatform() {
        UUID id = UUID.randomUUID();
        players.touch(id, "BedrockSteve", PlayerRecord.Platform.BEDROCK);
        assertTrue(players.findByUuid(id).orElseThrow().isBedrock());
    }

    @Test
    @DisplayName("prefix search returns matches")
    void prefixSearchFindsPlayers() {
        players.touch(UUID.randomUUID(), "Alpha", PlayerRecord.Platform.JAVA);
        players.touch(UUID.randomUUID(), "Alphabet", PlayerRecord.Platform.JAVA);
        players.touch(UUID.randomUUID(), "Beta", PlayerRecord.Platform.JAVA);

        List<PlayerRecord> matches = players.searchByNamePrefix("alph", 10);
        assertEquals(2, matches.size());
    }

    /**
     * A search for {@code %} must match nothing, not everyone. Unescaped, it
     * becomes a LIKE wildcard and turns tab-completion into a full table scan
     * that leaks every player name on the server.
     */
    @Test
    @DisplayName("LIKE wildcards in search input are escaped")
    void searchEscapesLikeWildcards() {
        players.touch(UUID.randomUUID(), "Alpha", PlayerRecord.Platform.JAVA);
        players.touch(UUID.randomUUID(), "Beta", PlayerRecord.Platform.JAVA);

        assertTrue(players.searchByNamePrefix("%", 10).isEmpty(),
                "'%' must be treated as a literal character");
        assertTrue(players.searchByNamePrefix("_", 10).isEmpty(),
                "'_' must be treated as a literal character");
    }

    @Test
    @DisplayName("search honours its limit")
    void searchRespectsLimit() {
        for (int i = 0; i < 10; i++) {
            players.touch(UUID.randomUUID(), "Player" + i, PlayerRecord.Platform.JAVA);
        }
        assertEquals(3, players.searchByNamePrefix("player", 3).size());
    }

    @Test
    @DisplayName("an unknown player is absent, not an error")
    void unknownPlayerReturnsEmpty() {
        assertTrue(players.findByUuid(UUID.randomUUID()).isEmpty());
        assertTrue(players.findByName("NoSuchPlayer").isEmpty());
    }

    @Test
    @DisplayName("data survives closing and reopening the database")
    void dataPersistsAcrossRestart(@TempDir Path temp) throws Exception {
        UUID id = UUID.randomUUID();
        players.touch(id, "Persistent", PlayerRecord.Platform.JAVA);
        database.onDisable();

        // Reopen the same file, as a server restart would.
        database.onEnable();
        assertEquals("Persistent", new PlayerRepository(database).findByUuid(id).orElseThrow().name());
    }
}
