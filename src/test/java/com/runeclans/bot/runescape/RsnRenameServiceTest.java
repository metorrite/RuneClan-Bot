package com.runeclans.bot.runescape;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.permission.PermissionGroup;
import com.runeclans.bot.permission.PermissionGroupService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a vanished name is paired with newly-seen names: XP narrows it, then the deeper checks settle ambiguity. */
class RsnRenameServiceTest {
    private static final long GUILD = 1L;
    private static final long CHANNEL = 2L;

    private RuneScapeStatsService stats;
    private RsnRenameRepository repo;
    private Guild guild;
    private GuildMessageChannel channel;
    private RsnRenameService service;

    @BeforeEach
    void setUp() {
        stats = mock(RuneScapeStatsService.class);
        repo = mock(RsnRenameRepository.class);
        guild = mock(Guild.class);
        channel = mock(GuildMessageChannel.class, Answers.RETURNS_DEEP_STUBS);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getChannelById(GuildMessageChannel.class, CHANNEL)).thenReturn(channel);

        GuildSettingsService settings = mock(GuildSettingsService.class);
        GuildSettings effective = mock(GuildSettings.class);
        when(effective.renameAlertChannelId()).thenReturn(CHANNEL);
        when(settings.getEffective(GUILD)).thenReturn(effective);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        when(groups.roleIds(GUILD, PermissionGroup.ADMIN)).thenReturn(List.of());

        when(stats.getLatestSnapshot("Telnos")).thenReturn(new PlayerLinkRepository.StatsSnapshotRow(10L, OffsetDateTime.now(), 2000, 1_000L, 130, 0, 0, 0));
        when(stats.getSkillsForSnapshot(10L)).thenReturn(List.of(new SkillValue(0, 99, 500, 1)));
        when(stats.getRecentActivities("Telnos", 5)).thenReturn(List.of(new PlayerActivity("01-Oct-2026", "Killed a thing", "")));
        when(repo.create(anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(7L);
        when(repo.getById(7L)).thenReturn(new RsnRenameRepository.RenameCandidate(7L, GUILD, "Telnos", "x", "HIGH", "b", OffsetDateTime.now(), "PENDING"));

        service = new RsnRenameService(stats, mock(PlayerLinkService.class), repo, settings, groups);
    }

    private ProfileResult profile(String name, long skillXp, boolean sharedActivity) {
        List<PlayerActivity> acts = sharedActivity ? List.of(new PlayerActivity("01-Oct-2026", "Killed a thing", "")) : List.of();
        return new ProfileResult.Found(new RuneScapeProfile(name, 2000, 2_000L, 130, 0, 0, 0, List.of(new SkillValue(0, 99, skillXp, 1)), acts));
    }

    private Set<String> run(Map<String, ProfileResult> polled) {
        var before = List.of(new ClanMemberRepository.ClanMemberRow(0L, GUILD, "Telnos", "Member", null, null, true, 1_000L, 0L, null));
        var roster = List.of(new RuneScapeApiClient.ClanMember("Rak sha", "Member", 2_000L, 0L),
                new RuneScapeApiClient.ClanMember("From Zamorak", "Member", 3_000L, 0L));
        return service.detectAndNotify(guild, before, roster, Set.of("telnos"), Set.of("rak sha", "from zamorak"), polled);
    }

    @Test
    void twoXpPlausibleNewcomersButOnlyOneWithMatchingActivityRaisesNormalAlert() {
        run(Map.of("rak sha", profile("Rak sha", 600, true), "from zamorak", profile("From Zamorak", 600, false)));

        verify(repo).create(eq(GUILD), eq("Telnos"), eq("Rak sha"), eq("HIGH"), anyString());
    }

    @Test
    void twoNewcomersBothPassingIsStillAmbiguous() {
        run(Map.of("rak sha", profile("Rak sha", 600, true), "from zamorak", profile("From Zamorak", 600, true)));

        verify(repo, never()).create(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(channel, times(1)).sendMessageComponents(any(java.util.Collection.class));
    }

    @Test
    void whileTheHubPollsTheNewcomerIsReadFromTheHubsSnapshots() {
        when(stats.latestAsResult("Rak sha")).thenReturn(profile("Rak sha", 600, true));
        when(stats.latestAsResult("From Zamorak")).thenReturn(new ProfileResult.Unavailable());
        // the clan sync polled nobody (T-851): every newcomer arrives as Unavailable, the hub's newest snapshot stands in
        Set<String> held = run(Map.of("rak sha", new ProfileResult.Unavailable(), "from zamorak", new ProfileResult.Unavailable()));
        assertEquals(Set.of("telnos"), held, "one candidate was raised from the hub's data, so the departed name is held");
        verify(repo).create(eq(GUILD), eq("Telnos"), eq("Rak sha"), anyString(), anyString());
    }

    @Test
    void noNewcomerPassingRaisesNothing() {
        run(Map.of("rak sha", profile("Rak sha", 100, true), "from zamorak", profile("From Zamorak", 100, true)));

        verify(repo, never()).create(anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(channel, never()).sendMessageComponents(any(java.util.Collection.class));
    }

    @Test
    void theOnlySurvivorOfTheSkillCheckIsRaisedEvenWithoutActivity() {
        run(Map.of("rak sha", profile("Rak sha", 600, false), "from zamorak", profile("From Zamorak", 100, false)));

        verify(repo).create(eq(GUILD), eq("Telnos"), eq("Rak sha"), eq("MEDIUM"), anyString());
    }
}
