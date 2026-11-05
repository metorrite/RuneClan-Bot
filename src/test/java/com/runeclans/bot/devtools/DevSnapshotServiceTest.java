package com.runeclans.bot.devtools;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.ticket.TicketDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Takes real snapshots against the local database (skipped without one) using a made-up server id. */
class DevSnapshotServiceTest {
    private static final long GUILD = 990_000_000_000_000_501L;
    private static final long OTHER_GUILD = 990_000_000_000_000_502L;
    private static final String SECRET = "seeded-" + "marker-" + "Q7vB2nXk9Lr4";

    private ConnectionSupplier db;
    private DevSnapshotService service;
    private DevSnapshotRepository repository;
    private SnapshotLogBuffer logs;
    private Guild guild;
    private long ticketId;
    private long foreignTicketId;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        new TicketDatabaseInitializer(db);
        repository = new DevSnapshotRepository(db);
        logs = new SnapshotLogBuffer();

        BotConfig config = mock(BotConfig.class);
        when(config.secretValues()).thenReturn(Set.of(SECRET));
        when(config.getLiveEnvironment()).thenReturn(false);
        when(config.getDatabaseUrl()).thenReturn("postgres" + "ql://u:p@127.0.0.1:5432/x");
        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Test Clan", null, null, null, null, null, null, null, true, "Test Clan", null, null));
        service = new DevSnapshotService(db, config, settings, logs, repository);

        JDA jda = mock(JDA.class);
        when(jda.getGatewayIntents()).thenReturn(EnumSet.of(GatewayIntent.GUILD_MEMBERS));
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getGuilds()).thenReturn(List.of());
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(jda);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn(Long.toString(GUILD));
        when(guild.getName()).thenReturn("Test Server");

        ticketId = insertTicket(GUILD, "closed with " + SECRET + " pasted");
        foreignTicketId = insertTicket(OTHER_GUILD, "someone else's ticket");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db == null) return;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.ticket WHERE guild_id IN (?, ?)")) {
            s.setLong(1, GUILD);
            s.setLong(2, OTHER_GUILD);
            s.executeUpdate();
        }
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.dev_snapshot WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        }
    }

    private long insertTicket(long guildId, String closeReason) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.ticket (guild_id, number, requester_id, close_reason) VALUES (?, 1, 4000000000000000001, ?) RETURNING id")) {
            s.setLong(1, guildId);
            s.setString(2, closeReason);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) out.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void aSnapshotHasTheDocumentedFilesAndIsStoredUnderItsId() throws Exception {
        var result = service.create(guild, 1L, "Owner", "", "abc1234");

        assertTrue(DevSnapshotService.ID_PATTERN.matcher(result.id()).matches(), result.id());
        Map<String, String> files = unzip(result.zip());
        assertTrue(files.keySet().containsAll(List.of("manifest.json", "environment.json", "guild.json", "logs.txt", "README.txt")), files.keySet().toString());
        assertTrue(files.get("manifest.json").contains(result.id()));
        assertTrue(files.get("manifest.json").contains("\"siteCommit\" : \"abc1234\""), files.get("manifest.json"));
        assertTrue(files.get("manifest.json").contains("\"environment\" : \"local\""));
        assertTrue(files.get("environment.json").contains("GUILD_MEMBERS"));
        assertTrue(files.get("guild.json").contains("Test Clan"));
        assertNotNull(repository.find(result.id()).orElse(null));
        assertEquals(result.zip().length, repository.find(result.id()).orElseThrow().sizeBytes());
    }

    @Test
    void aTicketFocusCopiesItsRowsAndMasksASecretPastedIntoOne() throws Exception {
        var result = service.create(guild, 1L, "Owner", "ticket:" + ticketId, "");

        Map<String, String> files = unzip(result.zip());
        String ticket = files.get("db/ticket.json");
        assertNotNull(ticket, files.keySet().toString());
        assertTrue(ticket.contains("closed with"), ticket);
        assertTrue(ticket.contains("\"4000000000000000001\""), "19-digit ids come out as text: " + ticket);
        files.forEach((name, text) -> assertFalse(text.contains(SECRET), name + " leaked the seeded secret"));
    }

    @Test
    void aTicketFromAnotherServerIsRefused() {
        var e = assertThrows(DevSnapshotService.SnapshotException.class, () -> service.create(guild, 1L, "Owner", "ticket:" + foreignTicketId, ""));
        assertEquals(404, e.status());
    }

    @Test
    void badFocusAndBadCommitAreRefused() {
        assertEquals(400, assertThrows(DevSnapshotService.SnapshotException.class, () -> service.create(guild, 1L, "Owner", "table:users", "")).status());
        assertEquals(400, assertThrows(DevSnapshotService.SnapshotException.class, () -> service.create(guild, 1L, "Owner", "", "x; rm -rf /")).status());
    }

    @Test
    void recentWarningsAreIncludedAndMasked() throws Exception {
        logs.add(new SnapshotLogBuffer.Entry(java.time.Instant.now(), "ERROR", "t.Test", "boom while using " + SECRET));
        var result = service.create(guild, 1L, "Owner", "", "");
        String log = unzip(result.zip()).get("logs.txt");
        assertTrue(log.contains("boom while using"), log);
        assertFalse(log.contains(SECRET));
    }

    @Test
    void expiredSnapshotsAreDeleted() throws Exception {
        var result = service.create(guild, 1L, "Owner", "", "");
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(
                "UPDATE younglings.dev_snapshot SET created_at = now() - interval '15 days' WHERE id = ?")) {
            s.setString(1, result.id());
            s.executeUpdate();
        }
        assertTrue(repository.deleteExpired() >= 1);
        assertTrue(repository.find(result.id()).isEmpty());
    }
}
