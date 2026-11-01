package com.runeclans.bot.plugin;

import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuneSyncServiceTest {
    private static final long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private final PluginService plugins = mock(PluginService.class);
    private final RuneSyncRepository repository = mock(RuneSyncRepository.class);
    private final BotConfig config = mock(BotConfig.class);
    private final RuneSyncPanelService panel = mock(RuneSyncPanelService.class);
    private final RuneSyncService service = new RuneSyncService(plugins, repository, config, panel);

    @Test
    void configListsOnlyTheMembersConsentAndKeepsThePanelOff() {
        when(plugins.consent(USER)).thenReturn(EnumSet.of(PluginCategory.XP, PluginCategory.DROPS));

        DataObject answer = service.config(USER);

        assertEquals(List.of("DROPS", "XP"), answer.getArray("consent").toList().stream().map(Object::toString).sorted().toList());
        assertFalse(answer.getBoolean("panelAvailable"));
        assertEquals("0.0.0", answer.getString("minClientVersion"));
        assertEquals(60, answer.getInt("sendEverySeconds"));
    }

    @Test
    void thePanelIsAvailableWhenOneOfTheMembersClansAllowsIt() {
        when(panel.available(USER)).thenReturn(true);
        assertTrue(service.config(USER).getBoolean("panelAvailable"));
        when(panel.available(USER)).thenReturn(null);
        assertTrue(service.config(USER).isNull("panelAvailable"), "T-812: Discord too slow to say: unknown, never \"not allowed\"");
        when(panel.available(USER)).thenThrow(new RuntimeException("db down"));
        assertTrue(service.config(USER).isNull("panelAvailable"), "a failure is unknown too");
    }

    @Test
    void minClientVersionComesFromConfigOnlyWhenItIsAVersion() {
        when(config.getRuneSyncSetting("RUNESYNC_MIN_CLIENT_VERSION")).thenReturn(" 0.1.0 ");
        assertEquals("0.1.0", service.minClientVersion());
        when(config.getRuneSyncSetting("RUNESYNC_MIN_CLIENT_VERSION")).thenReturn("latest");
        assertEquals("0.0.0", service.minClientVersion());
    }

    @Test
    void consentFromTheGameWritesTheSameRecordAsTheProfile() {
        when(plugins.consent(USER)).thenReturn(EnumSet.of(PluginCategory.KILLS));

        service.setConsent(USER, DataObject.empty().put("categories", DataArray.fromCollection(List.of("kills", " xp "))));

        verify(plugins).setConsent(USER, EnumSet.of(PluginCategory.KILLS, PluginCategory.XP));
    }

    @Test
    void anUnknownCategoryIsRefusedAndNothingIsWritten() {
        var e = assertThrows(RuneSyncService.RuneSyncException.class,
                () -> service.setConsent(USER, DataObject.empty().put("categories", DataArray.fromCollection(List.of("XP", "BANK")))));
        assertEquals(400, e.status());
        verify(plugins, never()).setConsent(anyLong(), any());
    }

    @Test
    void redactionDropsSecretFieldsAtAnyDepth() {
        DataObject in = DataObject.fromJson("""
                {"clientVersion":"0.1.0","assertion":"a.b.c","rsLinkToken":"x","settings":{"paused":false,"trackingId":"123"},
                 "last20Batches":[{"accepted":3,"rejected":["not_consented"],"authorization":"RSLink z"}],"chatText":"hi","discordId":"1",
                 "chat_message":"hello","permissions":{"chatAccess":true,"trackingAccess":false}}""");

        String out = RuneSyncService.redact(in).toString();

        for (String gone : List.of("assertion", "rsLinkToken", "trackingId", "authorization", "chatText", "chat_message", "discordId", "a.b.c", "RSLink z")) {
            assertFalse(out.contains(gone), gone + " should have been removed: " + out);
        }
        assertTrue(out.contains("clientVersion") && out.contains("not_consented") && out.contains("paused"));
        assertTrue(out.contains("chatAccess") && out.contains("trackingAccess"), "permission flags stay: " + out);
    }

    @Test
    void snapshotsAreStoredRedactedForFourteenDaysUnderAShortId() {
        when(repository.insertDiagnostic(anyString(), eq(USER), anyString(), eq(NOW), eq(NOW.plus(RuneSyncService.SNAPSHOT_KEPT)))).thenReturn(true);

        DataObject answer = service.storeDiagnostics(USER, DataObject.empty().put("clientVersion", "0.1.0").put("token", "secret"), NOW);

        assertTrue(answer.getString("id").matches("RS-[A-Z0-9]{6}"));
        verify(repository).insertDiagnostic(eq(answer.getString("id")), eq(USER), eq("{\"clientVersion\":\"0.1.0\"}"), eq(NOW), any());
    }

    @Test
    void tooManySnapshotsInADayAreRefused() {
        when(repository.diagnosticsSince(eq(USER), any())).thenReturn(RuneSyncService.SNAPSHOTS_PER_DAY);
        var e = assertThrows(RuneSyncService.RuneSyncException.class, () -> service.storeDiagnostics(USER, DataObject.empty(), NOW));
        assertEquals(429, e.status());
    }

    @Test
    void anOversizedSnapshotIsRefused() {
        var e = assertThrows(RuneSyncService.RuneSyncException.class,
                () -> service.storeDiagnostics(USER, DataObject.empty().put("notes", "x".repeat(RuneSyncService.MAX_SNAPSHOT_BYTES + 1)), NOW));
        assertEquals(413, e.status());
    }

    @Test
    void idsAreTidiedForLookup() {
        assertEquals("RS-7K2M9Q", RuneSyncService.normaliseId(" rs-7k2m9q "));
        assertEquals("RS-7K2M9Q", RuneSyncService.normaliseId("7K2M9Q"));
        assertNull(RuneSyncService.normaliseId("RS-AAAAA"));
        assertNull(RuneSyncService.normaliseId("RS-7K2M9Q; DROP"));
    }

    @Test
    void theOwnerViewAnswersNullForAnUnknownId() {
        when(repository.diagnostic(eq("RS-7K2M9Q"), any())).thenReturn(Optional.empty());
        assertNull(service.diagnostic("RS-7K2M9Q", NOW));
        assertThrows(RuneSyncService.RuneSyncException.class, () -> service.diagnostic("nope", NOW));
    }

    @Test
    void theMemberViewHasTheirClientRecentEventsAndIds() {
        when(repository.clientStatus(USER)).thenReturn(Optional.of(new RuneSyncRepository.ClientStatus("RuneSync 0.1.0", NOW, true, NOW, 4, 1, 0)));
        when(repository.recentEvents(USER, RuneSyncService.PROFILE_TYPES, 50)).thenReturn(List.of(
                new RuneSyncRepository.RecentEvent("XP", NOW, null, null, "{\"skill\":\"Fletching\",\"xp\":1000}")));
        when(repository.diagnosticsOf(USER, NOW)).thenReturn(List.of(new RuneSyncRepository.Diagnostic("RS-7K2M9Q", USER, NOW, NOW.plusSeconds(60), null)));

        DataObject view = service.memberView(USER, NOW);

        assertTrue(view.getObject("client").getBoolean("paused"));
        assertEquals(4, view.getObject("client").getObject("lastBatch").getInt("accepted"));
        assertEquals("Fletching", view.getObject("recent").getArray("XP").getObject(0).getObject("data").getString("skill"));
        assertEquals(0, view.getObject("recent").getArray("DROP").length());
        assertEquals("RS-7K2M9Q", view.getArray("diagnostics").getObject(0).getString("id"));
    }

    @Test
    void clientNamesAreTrimmedToSafeText() {
        assertEquals("RuneSync 0.1.0 (beta)", RuneSyncService.cleanClient(" RuneSync 0.1.0 (beta)<> "));
        assertNull(RuneSyncService.cleanClient("<>"));
    }
}
