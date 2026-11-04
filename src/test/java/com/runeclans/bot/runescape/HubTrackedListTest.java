package com.runeclans.bot.runescape;

import com.runeclans.bot.runeclans.RuneClansHubClient.Answer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T-851: the hourly list of names the bot wants the hub to poll (plan D-3, D-4), against a fake hub that applies the live rules. */
class HubTrackedListTest {
    private static final long GUILD = 1_000_000_000_000_000_002L;
    /** The hub's request body cap. */
    private static final int HUB_BODY_CAP = 16 * 1024;

    /** Answers as the live hub does: 413 over the body cap, 400 for a name it won't have, else the count received. */
    private static Answer liveHub(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > HUB_BODY_CAP) return new Answer(413, DataObject.empty().put("error", "body_too_large"));
        DataArray characters = DataObject.fromJson(body).getArray("characters");
        for (int i = 0; i < characters.length(); i++) {
            String rsn = characters.getObject(i).getString("rsn").replace('_', ' ').strip().replaceAll("\\s+", " ");
            if (rsn.isEmpty() || rsn.length() > 12 || !rsn.matches("[A-Za-z0-9 -]+")) return new Answer(400, DataObject.empty().put("error", "bad_rsn"));
        }
        return new Answer(200, DataObject.empty().put("received", characters.length()).put("tracked", 5));
    }

    @Test
    void everyBatchStaysUnderTheHubsBodyCapAndAllNamesArrive() {
        List<String> bodies = new ArrayList<>();
        List<HubTrackedList.Tracked> names = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) names.add(new HubTrackedList.Tracked("Member" + i, GUILD));
        names.add(new HubTrackedList.Tracked("Solo", null));
        HubTrackedList.Sender sender = new HubTrackedList.Sender(body -> {
            bodies.add(body);
            return liveHub(body);
        }, () -> names);

        assertEquals(2_501, sender.send(), "every name was taken");
        assertTrue(bodies.size() >= 8, "2,500 names with server ids need several batches: " + bodies.size());
        int total = 0;
        for (String body : bodies) {
            assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= HubTrackedList.MAX_BODY_BYTES, "a body over the cap: " + body.length());
            total += DataObject.fromJson(body).getArray("characters").length();
        }
        assertEquals(2_501, total);
        DataObject first = DataObject.fromJson(bodies.get(0)).getArray("characters").getObject(0);
        assertEquals(Long.toString(GUILD), first.getString("guildId"));
        DataArray lastBatch = DataObject.fromJson(bodies.get(bodies.size() - 1)).getArray("characters");
        DataObject last = lastBatch.getObject(lastBatch.length() - 1);
        assertEquals("Solo", last.getString("rsn"));
        assertFalse(last.hasKey("guildId"), "a /rsn account has no server");
        assertNull(sender.problem());
    }

    @Test
    void namesAreSpeltTheHubsWayAndOnesItWouldRefuseAreLeftOut() {
        assertEquals("Lord of Bone", HubTrackedList.clean("Lord_of_Bone"));
        assertEquals("Lord of Bone", HubTrackedList.clean("  Lord   of Bone "));
        assertNull(HubTrackedList.clean("Lord_Of_Bones"), "13 characters once the underscores are spaces: the hub refuses it");
        assertEquals("Zezima", HubTrackedList.clean("Zezima"));
        assertEquals("a-b 1", HubTrackedList.clean("a-b_1"));
        assertNull(HubTrackedList.clean("Odd.Name"));
        assertNull(HubTrackedList.clean("ThirteenChars"));
        assertNull(HubTrackedList.clean(""));
        assertNull(HubTrackedList.clean("   "));
        assertNull(HubTrackedList.clean(null));
        assertNull(HubTrackedList.clean("Zezïma"));
    }

    @Test
    void oneRefusedBatchDoesNotStopTheOthers() {
        List<String> bodies = new ArrayList<>();
        List<HubTrackedList.Tracked> names = new ArrayList<>();
        names.add(new HubTrackedList.Tracked("Odd.Name", GUILD)); // never produced by gather(), but the hub's answer must not cost the rest
        for (int i = 0; i < 1_200; i++) names.add(new HubTrackedList.Tracked("Member" + i, GUILD));
        HubTrackedList.Sender sender = new HubTrackedList.Sender(body -> {
            bodies.add(body);
            return liveHub(body);
        }, () -> names);

        int accepted = sender.send();
        assertTrue(bodies.size() >= 4, bodies.size() + " batches");
        assertTrue(accepted >= 1_200 - HubTrackedList.BATCH, "only the batch with the bad name was refused: " + accepted);
        assertEquals("status 400 bad_rsn", sender.problem());

        names.remove(0);
        bodies.clear();
        assertEquals(1_200, sender.send());
        assertNull(sender.problem(), "a clean run clears the trouble");
    }

    @Test
    void aMissingScopeOrAnOutageStopsTheRunAndIsLoggedWhenItStartsAndWhenItChanges() {
        int[] status = {403};
        String[] code = {"missing_scope"};
        List<String> bodies = new ArrayList<>();
        List<HubTrackedList.Tracked> names = new ArrayList<>();
        for (int i = 0; i < 600; i++) names.add(new HubTrackedList.Tracked("Member" + i, GUILD));
        HubTrackedList.Sender sender = new HubTrackedList.Sender(body -> {
            bodies.add(body);
            return status[0] == 200 ? liveHub(body) : new Answer(status[0], DataObject.empty().put("error", code[0]));
        }, () -> names);

        assertEquals(0, sender.send());
        assertEquals(1, bodies.size(), "a refused key stops after the first batch: every batch would be refused");
        assertEquals("status 403 missing_scope", sender.problem());
        assertEquals(0, sender.send());
        assertEquals("status 403 missing_scope", sender.problem(), "the same trouble is not a new one");

        status[0] = 503;
        code[0] = "busy";
        bodies.clear();
        assertEquals(0, sender.send());
        assertEquals(1, bodies.size(), "an outage stops the run too");
        assertEquals("status 503 busy", sender.problem(), "a different trouble is logged in its own words, not as the key's scope");

        status[0] = 200;
        assertEquals(600, sender.send());
        assertNull(sender.problem());

        HubTrackedList.Sender down = new HubTrackedList.Sender(body -> null, () -> List.of(new HubTrackedList.Tracked("A", null)));
        assertEquals(0, down.send(), "unreachable: try again next hour");
        assertEquals("unreachable", down.problem());
    }

    @Test
    void batchesAreCutByBytesAndByCount() {
        List<HubTrackedList.Tracked> small = new ArrayList<>();
        for (int i = 0; i < HubTrackedList.BATCH + 1; i++) small.add(new HubTrackedList.Tracked("A" + (i % 10), null)); // tiny bodies: the count cuts them
        List<List<HubTrackedList.Tracked>> byCount = HubTrackedList.batches(small);
        assertEquals(2, byCount.size());
        assertEquals(HubTrackedList.BATCH, byCount.get(0).size());
        assertTrue(HubTrackedList.body(byCount.get(0)).getBytes(StandardCharsets.UTF_8).length <= HubTrackedList.MAX_BODY_BYTES);

        assertTrue(HubTrackedList.batches(List.of()).isEmpty());
    }
}
