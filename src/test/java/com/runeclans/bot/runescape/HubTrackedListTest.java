package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: the hourly list of names the bot wants the hub to poll (plan D-3, D-4). */
class HubTrackedListTest {
    private static final long GUILD = 1_000_000_000_000_000_002L;

    @Test
    void theListGoesInBatchesWithTheServerIdOnClanMembers() {
        List<String> bodies = new ArrayList<>();
        List<HubTrackedList.Tracked> names = new ArrayList<>();
        for (int i = 0; i < HubTrackedList.BATCH + 2; i++) names.add(new HubTrackedList.Tracked("Member" + i, GUILD));
        names.add(new HubTrackedList.Tracked("Solo", null));
        HubTrackedList.Sender sender = new HubTrackedList.Sender(body -> {
            bodies.add(body);
            return new Answer(200, DataObject.empty().put("received", DataObject.fromJson(body).getArray("characters").length()).put("tracked", 5));
        }, () -> names);
        assertEquals(HubTrackedList.BATCH + 3, sender.send());
        assertEquals(2, bodies.size());
        DataObject first = DataObject.fromJson(bodies.get(0));
        assertEquals(HubTrackedList.BATCH, first.getArray("characters").length());
        assertEquals(Long.toString(GUILD), first.getArray("characters").getObject(0).getString("guildId"));
        DataObject last = DataObject.fromJson(bodies.get(1)).getArray("characters").getObject(2);
        assertEquals("Solo", last.getString("rsn"));
        assertFalse(last.hasKey("guildId"), "a /rsn account has no server");
    }

    @Test
    void aRefusalStopsTheRunAndIsRememberedUntilTheHubTakesItAgain() {
        int[] status = {403};
        List<String> bodies = new ArrayList<>();
        HubTrackedList.Sender sender = new HubTrackedList.Sender(body -> {
            bodies.add(body);
            return status[0] == 200 ? new Answer(200, DataObject.empty().put("received", 1)) : new Answer(status[0], DataObject.empty().put("error", "missing_scope"));
        }, () -> List.of(new HubTrackedList.Tracked("A", null)));
        assertEquals(0, sender.send());
        assertEquals(0, sender.send());
        status[0] = 200;
        assertEquals(1, sender.send());
        assertEquals(3, bodies.size());
        HubTrackedList.Sender down = new HubTrackedList.Sender(body -> null, () -> List.of(new HubTrackedList.Tracked("A", null)));
        assertEquals(0, down.send(), "unreachable: try again next hour");
    }
}
