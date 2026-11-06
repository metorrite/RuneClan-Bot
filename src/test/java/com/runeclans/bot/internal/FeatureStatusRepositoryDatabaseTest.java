package com.runeclans.bot.internal;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feature status's one query over a real database (skipped without one): every table and column it names exists, and the no-row defaults. */
class FeatureStatusRepositoryDatabaseTest {
    private static final long G = 999_000_000_000_000_461L;
    /** The features' own classes that create the tables the query reads (each takes only the connection). */
    private static final List<Class<?>> TABLE_OWNERS = List.of(
            com.runeclans.bot.welcome.WelcomeDatabaseInitializer.class, com.runeclans.bot.goodbye.GoodbyeRepository.class,
            com.runeclans.bot.accountgate.AccountGateRepository.class, com.runeclans.bot.autorole.AutoroleRepository.class,
            com.runeclans.bot.member.MemberProfileRepository.class, com.runeclans.bot.rolepanel.RolePanelRepository.class,
            com.runeclans.bot.rolekeep.RoleKeepRepository.class, com.runeclans.bot.rolerules.RoleRuleRepository.class,
            com.runeclans.bot.roletools.RoleToolkitRepository.class, com.runeclans.bot.warning.WarningRepository.class,
            com.runeclans.bot.moderation.ModCaseRepository.class, com.runeclans.bot.appeal.AppealRepository.class,
            com.runeclans.bot.lockdown.LockdownRepository.class, com.runeclans.bot.autopurge.AutoPurgeRepository.class,
            com.runeclans.bot.autodelete.AutoDeleteRepository.class, com.runeclans.bot.honeypot.HoneypotRepository.class,
            com.runeclans.bot.servertag.ServerTagRepository.class, com.runeclans.bot.modlog.ModLogRepository.class,
            com.runeclans.bot.messagelog.MessageLogRepository.class, AdminToolsStore.class,
            com.runeclans.bot.staffdigest.StaffDigestRepository.class, com.runeclans.bot.ban.BanAnnouncementDatabaseInitializer.class,
            com.runeclans.bot.serverreminder.ServerReminderRepository.class, com.runeclans.bot.autoreply.AutoReplyRepository.class,
            com.runeclans.bot.customtag.CustomTagRepository.class, com.runeclans.bot.embedder.EmbedderDatabaseInitializer.class,
            com.runeclans.bot.feed.FeedRepository.class, com.runeclans.bot.twitch.TwitchRepository.class, com.runeclans.bot.kick.KickRepository.class,
            com.runeclans.bot.highlight.HighlightRepository.class, com.runeclans.bot.giveaway.GiveawayRepository.class,
            com.runeclans.bot.suggestion.SuggestionRepository.class, com.runeclans.bot.starboard.StarboardRepository.class,
            com.runeclans.bot.ticket.TicketDatabaseInitializer.class, com.runeclans.bot.forms.WebFormRepository.class,
            com.runeclans.bot.points.PointsRepository.class, com.runeclans.bot.chatlevel.ChatLevelRepository.class,
            com.runeclans.bot.tempvoice.TempVoiceRepository.class, com.runeclans.bot.voicetext.VoiceTextRepository.class,
            com.runeclans.bot.events.EventRepository.class, com.runeclans.bot.eventannounce.EventAnnounceRepository.class,
            com.runeclans.bot.wheel.WheelSettingsRepository.class, com.runeclans.bot.events.BingoTileRepository.class,
            com.runeclans.bot.clanprofile.ClanProfileRepository.class, com.runeclans.bot.runescape.RuneScapeDatabaseInitializer.class,
            com.runeclans.bot.anniversary.AnniversaryRepository.class, com.runeclans.bot.alliance.AllianceRepository.class,
            com.runeclans.bot.plugin.RuneSyncRepository.class, com.runeclans.bot.devtools.DevSnapshotRepository.class,
            com.runeclans.bot.lookup.LookupReminderRepository.class, com.runeclans.bot.tracking.TrackingDatabaseInitializer.class,
            com.runeclans.bot.coffer.CofferDatabaseInitializer.class);
    private static final List<String> TOUCHED = List.of("goodbye_config", "self_role", "clan_profile", "lookup_reminder", "role_toolkit_allow",
            "tracking_group_config", "tracking_destination");
    private static ConnectionSupplier supplier;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        for (Class<?> owner : TABLE_OWNERS) owner.getConstructor(ConnectionSupplier.class).newInstance(supplier);
        new CommunitySettings(supplier, com.runeclans.bot.testsupport.SiteMappings.off(supplier)); // its tables too (it also takes the channel mappings)
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : TOUCHED) sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    @Test
    void aServerThatNeverSavedAnythingHasEveryStoredFeatureOff() {
        FeatureStatusRepository.Facts facts = new FeatureStatusRepository(supplier).read(G);
        assertNotNull(facts, "the query runs: every table and column it names exists");
        assertEquals(FeatureStatusRepository.FLAGS.keySet(), facts.flags().keySet());
        for (Map.Entry<String, Boolean> flag : facts.flags().entrySet()) {
            // no profile row reads as UNLISTED (on); the snapshot list is the whole bot's, so other tests' rows may be there
            if (flag.getKey().equals("/clan/clan-profile")) assertTrue(flag.getValue());
            else if (!flag.getKey().equals("/tools/snapshots")) assertFalse(flag.getValue(), flag.getKey());
        }
        assertEquals(Set.of(), facts.toolkitGroups());
        assertEquals(Set.of(), facts.enabledLookupKinds());
        assertEquals(Set.of(), facts.trackingOff());
        assertEquals(Map.of(), facts.trackingChannels());
    }

    @Test
    void savedSettingsTurnTheirDotsOn() throws Exception {
        sql("INSERT INTO younglings.goodbye_config (guild_id, enabled) VALUES (" + G + ", TRUE)");
        sql("INSERT INTO younglings.self_role (guild_id, role_id) VALUES (" + G + ", 5)");
        sql("INSERT INTO younglings.clan_profile (guild_id, visibility) VALUES (" + G + ", 'PRIVATE')");
        sql("INSERT INTO younglings.lookup_reminder (guild_id, kind, enabled) VALUES (" + G + ", 'vos', TRUE), (" + G + ", 'reset-daily', FALSE)");
        sql("INSERT INTO younglings.role_toolkit_allow (guild_id, group_key, role_id) VALUES (" + G + ", 'admin', 7)");
        sql("INSERT INTO younglings.tracking_group_config (guild_id, group_key, enabled) VALUES (" + G + ", 'LEVEL_UPS', FALSE)");
        sql("INSERT INTO younglings.tracking_destination (guild_id, group_key, channel_id) VALUES (" + G + ", 'DROPS', 11), (" + G + ", 'DROPS', 12)");

        FeatureStatusRepository.Facts facts = new FeatureStatusRepository(supplier).read(G);
        assertTrue(facts.flag("/joins/goodbye"));
        assertTrue(facts.flag("/roles/self-roles"));
        assertFalse(facts.flag("/clan/clan-profile"), "a private profile is off");
        assertFalse(facts.flag("/joins/welcome"));
        assertEquals(Set.of("vos"), facts.enabledLookupKinds());
        assertEquals(Set.of("admin"), facts.toolkitGroups());
        assertEquals(Set.of("LEVEL_UPS"), facts.trackingOff());
        assertEquals(Map.of("DROPS", List.of(11L, 12L)), facts.trackingChannels());
    }
}
