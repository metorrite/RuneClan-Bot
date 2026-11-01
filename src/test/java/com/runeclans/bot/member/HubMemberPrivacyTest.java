package com.runeclans.bot.member;

import com.runeclans.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.runeclans.bot.runeclans.PanelSwitches;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-750: the bot's member privacy saves, sent on to the hub with the switch on and never with it off; each toggle in its own field; a save
 * held while the hub is down never lands after a newer one; a refused one is dropped.
 */
class HubMemberPrivacyTest {
    private static final long GUILD = Long.parseLong("1".repeat(18)), USER = Long.parseLong("2".repeat(18));
    private static final Answer OK = new Answer(200, DataObject.empty()), DOWN = new Answer(503, null), BAD = new Answer(400, DataObject.empty());

    private record Sent(String path, DataObject body) {}

    @Test
    void theFactoryGateSendsAPutOnlyWithTheSwitchOn() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.enabled()).thenReturn(true);
        when(hub.call("PUT", HubMemberPrivacy.memberPath(GUILD, USER), HubMemberPrivacy.toggles(true, false, false))).thenReturn(OK);
        HubMemberPrivacy.off().memberSaved(GUILD, USER, true, false, false);
        verify(hub, never()).call(anyString(), anyString(), anyString());

        HubMemberPrivacy on = HubMemberPrivacy.forHub(hub, Runnable::run, () -> true);
        assertTrue(on.on());
        on.memberSaved(GUILD, USER, true, false, false);
        verify(hub).call("PUT", "/v1/guilds/" + GUILD + "/member-privacy/members/" + USER, HubMemberPrivacy.toggles(true, false, false));
        assertEquals(0, on.held());

        // the bot's own factory: off unless the switch is on (Railway's setting first, else the panel) AND the hub client is on
        com.runeclans.bot.config.BotConfig config = mock(com.runeclans.bot.config.BotConfig.class);
        PanelSwitches none = PanelSwitches.none();
        PanelSwitches panelOn = PanelSwitches.of(Map.of(HubMemberPrivacy.SWITCH_ID, "on"));
        when(config.getRuneClansSetting(HubMemberPrivacy.SETTING_ENV)).thenReturn("off");
        assertFalse(HubMemberPrivacy.hubMemberPrivacy(config, hub, none).on());
        assertFalse(HubMemberPrivacy.hubMemberPrivacy(config, hub, panelOn).on(), "Railway's off wins over the panel");
        when(config.getRuneClansSetting(HubMemberPrivacy.SETTING_ENV)).thenReturn("on");
        when(hub.enabled()).thenReturn(false);
        assertFalse(HubMemberPrivacy.hubMemberPrivacy(config, hub, none).on(), "the hub client off");
        when(hub.enabled()).thenReturn(true);
        assertTrue(HubMemberPrivacy.hubMemberPrivacy(config, hub, none).on());
        when(config.getRuneClansSetting(HubMemberPrivacy.SETTING_ENV)).thenReturn(null);
        assertFalse(HubMemberPrivacy.hubMemberPrivacy(config, hub, none).on(), "unset on Railway and no panel answer: off");
        assertTrue(HubMemberPrivacy.hubMemberPrivacy(config, hub, panelOn).on(), "unset on Railway: the panel decides (T-817)");

        assertFalse(HubMemberPrivacy.on(null));
        assertFalse(HubMemberPrivacy.on("off"));
        assertTrue(HubMemberPrivacy.on(" TRUE "));
        assertTrue(HubMemberPrivacy.on("on"));
    }

    @Test
    void thePanelCanTurnItOnAndOffWhileTheBotRuns() {
        RuneClansHubClient hub = mock(RuneClansHubClient.class);
        when(hub.call(anyString(), anyString(), anyString())).thenReturn(OK);
        java.util.concurrent.atomic.AtomicBoolean panel = new java.util.concurrent.atomic.AtomicBoolean(false);
        HubMemberPrivacy privacy = HubMemberPrivacy.forHub(hub, Runnable::run, panel::get);
        privacy.memberSaved(GUILD, USER, true, false, false);
        verify(hub, never()).call(anyString(), anyString(), anyString());
        assertEquals(0, privacy.held(), "nothing is kept while the switch is off");

        panel.set(true);
        privacy.memberSaved(GUILD, USER, true, false, false);
        verify(hub).call("PUT", HubMemberPrivacy.memberPath(GUILD, USER), HubMemberPrivacy.toggles(true, false, false));
    }

    @Test
    void eachToggleGoesInItsOwnField() {
        List<Sent> sent = new ArrayList<>();
        HubMemberPrivacy on = HubMemberPrivacy.sendingTo((path, body) -> {
            sent.add(new Sent(path, DataObject.fromJson(body)));
            return OK;
        }, Runnable::run);
        on.memberSaved(GUILD, USER, true, false, false);
        on.memberSaved(GUILD, USER, false, true, false);
        on.memberSaved(GUILD, USER, false, false, true);
        on.defaultsSaved(GUILD, new PrivacyDefaults(true, false, false));
        on.defaultsSaved(GUILD, new PrivacyDefaults(false, true, false));
        on.defaultsSaved(GUILD, new PrivacyDefaults(false, false, true));
        assertEquals(6, sent.size());
        String[] keys = {"hideFromLeaderboards", "hideAdventureLog", "hideDiscordLink"};
        for (int i = 0; i < 6; i++) {
            assertEquals(i < 3 ? "/v1/guilds/" + GUILD + "/member-privacy/members/" + USER : "/v1/guilds/" + GUILD + "/member-privacy/defaults", sent.get(i).path());
            for (int k = 0; k < 3; k++) assertEquals(k == i % 3, sent.get(i).body().getBoolean(keys[k]), "save " + i + ", " + keys[k]);
            assertFalse(sent.get(i).body().hasKey("chosen"), "the hub marks a member who names a toggle as chosen");
        }
    }

    @Test
    void aHeldSaveNeverLandsAfterANewerOne() {
        List<Sent> sent = new ArrayList<>();
        AtomicInteger down = new AtomicInteger(1);
        HubMemberPrivacy on = HubMemberPrivacy.sendingTo((path, body) -> {
            if (down.get() > 0) return DOWN;
            sent.add(new Sent(path, DataObject.fromJson(body)));
            return OK;
        }, Runnable::run);
        on.memberSaved(GUILD, USER, false, false, false); // "show", held: the hub is down
        assertEquals(1, on.held());
        on.memberSaved(GUILD, USER, true, false, false); // "hide" replaces it
        assertEquals(1, on.held(), "one row per hub path");
        down.set(0);
        on.flush();
        assertEquals(1, sent.size(), "only the newest body is sent");
        assertTrue(sent.get(0).body().getBoolean("hideFromLeaderboards"));
        assertEquals(0, on.held());

        HubMemberPrivacy unreachable = HubMemberPrivacy.sendingTo((path, body) -> null, Runnable::run);
        unreachable.defaultsSaved(GUILD, PrivacyDefaults.NONE);
        assertEquals(1, unreachable.held(), "no answer: tried again next round");
        HubMemberPrivacy throwing = HubMemberPrivacy.sendingTo((path, body) -> {
            throw new IllegalStateException("hub down");
        }, Runnable::run);
        throwing.defaultsSaved(GUILD, PrivacyDefaults.NONE); // kept, not thrown: the bot's own save already happened
        assertEquals(1, throwing.held());
        HubMemberPrivacy refusing = HubMemberPrivacy.sendingTo((path, body) -> BAD, Runnable::run);
        refusing.defaultsSaved(GUILD, PrivacyDefaults.NONE);
        assertEquals(0, refusing.held(), "a refusal is dropped, not retried for ever");
    }

    @Test
    void aSaveWhileTheSwitchIsOffDropsTheOlderHeldRowSoItIsNeverSentLater() {
        List<Sent> sent = new ArrayList<>();
        AtomicInteger down = new AtomicInteger(1);
        java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        HubMemberPrivacy privacy = new HubMemberPrivacy((path, body) -> {
            if (down.get() > 0) return DOWN;
            sent.add(new Sent(path, DataObject.fromJson(body)));
            return OK;
        }, Runnable::run, on::get);
        privacy.memberSaved(GUILD, USER, true, false, false); // "hide", held: the hub is down
        assertEquals(1, privacy.held());
        on.set(false);
        privacy.memberSaved(GUILD, USER, false, false, false); // "show" while off: not sent, and the older "hide" is no longer the member's choice
        assertEquals(0, privacy.held(), "the stale row is dropped");
        on.set(true);
        down.set(0);
        privacy.flush();
        assertTrue(sent.isEmpty(), "turning the switch on again never sends the older save");
    }

    @Test
    void sendsWaitForTheLaneNotTheCaller() {
        List<Runnable> lane = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HubMemberPrivacy on = HubMemberPrivacy.sendingTo((path, body) -> {
            calls.incrementAndGet();
            return OK;
        }, lane::add);
        on.memberSaved(GUILD, USER, true, true, true);
        assertEquals(0, calls.get(), "the website's request never waits on the hub");
        lane.forEach(Runnable::run);
        assertEquals(1, calls.get());
    }

    @Test
    void aSaveDuringASendStaysHeldAndGoesNext() {
        List<Sent> sent = new ArrayList<>();
        List<Runnable> lane = new ArrayList<>();
        HubMemberPrivacy[] on = new HubMemberPrivacy[1];
        on[0] = HubMemberPrivacy.sendingTo((path, body) -> {
            if (sent.isEmpty()) on[0].memberSaved(GUILD, USER, true, false, false); // "hide" saved while "show" is on its way
            sent.add(new Sent(path, DataObject.fromJson(body)));
            return OK;
        }, lane::add);
        on[0].memberSaved(GUILD, USER, false, false, false);
        on[0].flush();
        assertEquals(1, on[0].held(), "the newer save is not dropped with the one the hub took");
        on[0].flush();
        assertEquals(2, sent.size());
        assertTrue(sent.get(1).body().getBoolean("hideFromLeaderboards"), "the newer save goes next");
        assertEquals(0, on[0].held());
    }

    @Test
    void eachRowSendsItsNewestBodyEvenWhenSavedMidRound() {
        List<Sent> sent = new ArrayList<>();
        AtomicInteger down = new AtomicInteger(1);
        HubMemberPrivacy[] on = new HubMemberPrivacy[1];
        on[0] = HubMemberPrivacy.sendingTo((path, body) -> {
            if (down.get() > 0) return DOWN;
            if (sent.isEmpty()) on[0].defaultsSaved(GUILD, new PrivacyDefaults(false, false, true)); // the second row changes mid-round
            sent.add(new Sent(path, DataObject.fromJson(body)));
            return OK;
        }, new ArrayList<Runnable>()::add);
        on[0].memberSaved(GUILD, USER, true, false, false);
        on[0].defaultsSaved(GUILD, new PrivacyDefaults(true, false, false));
        assertEquals(2, on[0].held());
        down.set(0);
        on[0].flush();
        assertEquals(2, sent.size(), "the stale defaults are never sent");
        assertEquals(HubMemberPrivacy.defaultsPath(GUILD), sent.get(1).path());
        assertFalse(sent.get(1).body().getBoolean("hideFromLeaderboards"));
        assertTrue(sent.get(1).body().getBoolean("hideDiscordLink"));
        assertEquals(0, on[0].held());
    }

    @Test
    void aBurstOfSavesQueuesOneRound() {
        List<Runnable> lane = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HubMemberPrivacy on = HubMemberPrivacy.sendingTo((path, body) -> {
            calls.incrementAndGet();
            return OK;
        }, lane::add);
        for (int i = 0; i < 5; i++) on.memberSaved(GUILD, USER + i, true, false, false);
        assertEquals(1, lane.size(), "one round for the burst");
        lane.remove(0).run();
        assertEquals(5, calls.get(), "the round sends the whole burst");
        on.memberSaved(GUILD, USER, false, false, false);
        assertEquals(1, lane.size(), "a save after the round started queues the next one");

        HubMemberPrivacy rejected = HubMemberPrivacy.sendingTo((path, body) -> OK, r -> {
            throw new java.util.concurrent.RejectedExecutionException("stopping");
        });
        rejected.memberSaved(GUILD, USER, true, false, false);
        assertEquals(1, rejected.held(), "kept for the timed round");
        List<Runnable> later = new ArrayList<>();
        // a lane that refused once doesn't leave the flag stuck: the next save still queues
        HubMemberPrivacy flaky = HubMemberPrivacy.sendingTo((path, body) -> OK, new java.util.concurrent.Executor() {
            boolean refused;
            @Override public void execute(Runnable r) {
                if (!refused) { refused = true; throw new java.util.concurrent.RejectedExecutionException("busy"); }
                later.add(r);
            }
        });
        flaky.memberSaved(GUILD, USER, true, false, false);
        flaky.memberSaved(GUILD, USER, false, false, false);
        assertEquals(1, later.size());
    }

    @Test
    void pastTheCapANewRowIsDroppedButAHeldOneStillUpdates() {
        HubMemberPrivacy on = HubMemberPrivacy.sendingTo((path, body) -> DOWN, new ArrayList<Runnable>()::add);
        for (int i = 0; i < HubMemberPrivacy.MAX_HELD; i++) on.memberSaved(GUILD, i, true, false, false);
        assertEquals(HubMemberPrivacy.MAX_HELD, on.held());
        on.memberSaved(GUILD, HubMemberPrivacy.MAX_HELD, true, false, false);
        assertEquals(HubMemberPrivacy.MAX_HELD, on.held(), "a new row past the cap is dropped");
        on.memberSaved(GUILD, 7, false, false, true);
        assertEquals(HubMemberPrivacy.MAX_HELD, on.held(), "a held row updates at the cap");
    }
}
