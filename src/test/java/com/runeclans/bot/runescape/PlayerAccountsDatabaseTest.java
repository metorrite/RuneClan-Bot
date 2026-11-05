package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import com.runeclans.bot.testsupport.TestDatabaseUrl;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the account SQL against a real Postgres. Skipped unless {@code RUNECLAN_BOT_TEST_DATABASE_URL} (or the older JDBC-form {@code RUNECLAN_BOT_TEST_DB_URL}; old names {@code JONNYBOT_*}) points at an <em>empty
 * scratch</em> database it may fill and wipe (e.g. {@code jdbc:postgresql://localhost:5432/jonnybot_scratch?user=..&password=..}):
 * never point it at a database that holds real data.
 */
@EnabledIf("com.runeclans.bot.testsupport.TestDatabaseUrl#configured")
class PlayerAccountsDatabaseTest {
    private static final long HOME = 1L;
    private static final long OTHER = 2L;
    private static final long ALICE = 100L;
    private static final long BOB = 200L;

    private static ConnectionSupplier connections;
    private static PlayerLinkRepository repository;

    @BeforeAll
    static void connect() {
        String url = TestDatabaseUrl.jdbc();
        connections = Mockito.mock(ConnectionSupplier.class);
        try {
            Mockito.when(connections.getConnection()).thenAnswer(call -> DriverManager.getConnection(url));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        var initializer = new RuneScapeDatabaseInitializer(connections);
        repository = new PlayerLinkRepository(connections, initializer);
    }

    @AfterAll
    static void wipe() throws SQLException {
        clean();
    }

    @BeforeEach
    void emptyTables() throws SQLException {
        clean();
    }

    private static void clean() throws SQLException {
        try (Connection c = DriverManager.getConnection(TestDatabaseUrl.jdbc()); Statement s = c.createStatement()) {
            s.execute("TRUNCATE younglings.player_link, younglings.player_account, younglings.player_link_exclusion, younglings.player_activity RESTART IDENTITY");
            s.execute("TRUNCATE younglings.player_stats_snapshot RESTART IDENTITY CASCADE");
        }
    }

    private static List<String> rsns(List<PlayerLink> links) {
        return links.stream().map(PlayerLink::rsn).toList();
    }

    @Test
    void aSelfPollInOneServerStartsTheCooldownInEveryServerTheNameIsLinkedIn() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.createLink(OTHER, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        assertNull(repository.getLinkForRsn(OTHER, "Jonny Young").lastSelfPollAt());

        repository.recordSelfPoll(repository.getLinkForRsn(HOME, "Jonny Young").linkId());

        assertNotNull(repository.getLinkForRsn(OTHER, "Jonny Young").lastSelfPollAt());
    }

    @Test
    void linkingInOneServerRegistersTheAccountWithJonnyBotAndThatServer() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);

        var account = repository.getAccountForRsn("jonny young");
        assertNotNull(account);
        assertEquals(ALICE, account.discordUserId());
        assertEquals(HOME, account.verifiedGuildId());
        assertEquals(List.of("Jonny Young"), rsns(repository.getLinksForUser(HOME, ALICE)));
        assertEquals(List.of(), repository.getLinksForUser(OTHER, ALICE));
    }

    @Test
    void usingJonnyBotInAnotherServerBringsTheAccountAlong() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.createLink(HOME, ALICE, "Jonny Iron", PlayerLinkService.METHOD_ADMIN_MANUAL);

        assertEquals(List.of("Jonny Iron", "Jonny Young"), repository.adoptAccounts(OTHER, ALICE).stream().sorted().toList());
        assertEquals(List.of(), repository.adoptAccounts(OTHER, ALICE), "adopting twice adds nothing");
        assertEquals(2, repository.getLinksForUser(OTHER, ALICE).size());
        assertEquals(List.of(), repository.adoptAccounts(OTHER, BOB), "someone with no accounts has nothing to bring");
    }

    @Test
    void aDifferentServerCannotTakeANameAnotherServerVerified() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.adoptAccounts(OTHER, ALICE);

        var taken = assertThrows(PlayerLinkRepository.RsnTakenException.class,
                () -> repository.createLink(OTHER, BOB, "jonny young", PlayerLinkService.METHOD_ADMIN_MANUAL));

        assertEquals(ALICE, taken.ownerDiscordUserId());
        assertEquals(ALICE, repository.getAccountForRsn("Jonny Young").discordUserId());
        assertEquals(ALICE, repository.getLinkForRsn(OTHER, "Jonny Young").discordUserId(), "the failed attempt left nothing half done");
    }

    @Test
    void anAdminFixingAMistakeInTheOnlyServerThatHasTheNameStillWorks() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);

        repository.createLink(HOME, BOB, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);

        assertEquals(BOB, repository.getAccountForRsn("Jonny Young").discordUserId());
        assertEquals(BOB, repository.getLinkForRsn(HOME, "Jonny Young").discordUserId());
    }

    @Test
    void theOwnerUnlinkingRemovesTheAccountFromEveryServer() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.adoptAccounts(OTHER, ALICE);
        long linkId = repository.getLinkForRsn(HOME, "Jonny Young").linkId();

        repository.deleteAccount(HOME, linkId);

        assertNull(repository.getAccountForRsn("Jonny Young"));
        assertNull(repository.getLinkForRsn(HOME, "Jonny Young"));
        assertNull(repository.getLinkForRsn(OTHER, "Jonny Young"));
    }

    @Test
    void anAdminRemovingALinkKeepsItOutOfTheirServerUntilLinkedAgainOnPurpose() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.adoptAccounts(OTHER, ALICE);
        long otherLink = repository.getLinkForRsn(OTHER, "Jonny Young").linkId();

        repository.deleteLink(OTHER, otherLink);

        assertNotNull(repository.getAccountForRsn("Jonny Young"), "the account stays with its owner");
        assertNotNull(repository.getLinkForRsn(HOME, "Jonny Young"), "and in their other servers");
        assertNull(repository.getLinkForRsn(OTHER, "Jonny Young"));
        assertEquals(List.of(), repository.adoptAccounts(OTHER, ALICE), "/rs does not put it straight back");

        repository.createLink(OTHER, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        assertNotNull(repository.getLinkForRsn(OTHER, "Jonny Young"));
        repository.deleteLink(OTHER, repository.getLinkForRsn(OTHER, "Jonny Young").linkId());
        repository.createLink(OTHER, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        assertTrue(repository.getLinkForRsn(OTHER, "Jonny Young") != null, "linking on purpose clears the removal");
    }

    @Test
    void aRenameMovesTheAccountAndEveryServersLink() {
        repository.createLink(HOME, ALICE, "Old Name", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.adoptAccounts(OTHER, ALICE);

        repository.updateRsn(HOME, repository.getLinkForRsn(HOME, "Old Name").linkId(), "New Name");

        assertNull(repository.getAccountForRsn("Old Name"));
        assertEquals(ALICE, repository.getAccountForRsn("New Name").discordUserId());
        assertNotNull(repository.getLinkForRsn(HOME, "New Name"));
        assertNotNull(repository.getLinkForRsn(OTHER, "New Name"));
    }

    @Test
    void theSecondTierIsEveryRegisteredAccountOnce() {
        repository.createLink(HOME, ALICE, "Jonny Young", PlayerLinkService.METHOD_ADMIN_MANUAL);
        repository.adoptAccounts(OTHER, ALICE);
        repository.createLink(OTHER, BOB, "Zezima", PlayerLinkService.METHOD_ADMIN_MANUAL);

        assertEquals(List.of("Jonny Young", "Zezima"), repository.getAllAccountRsns());
    }
}
