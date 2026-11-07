package com.runeclans.bot.events;

import com.runeclans.bot.events.BingoBoardView.TileView;
import com.runeclans.bot.internal.WikiCatalog;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BingoTilesTest {
    private final BingoTiles tiles = new BingoTiles(new WikiCatalog());

    @Test
    void aBossAndItemLabelIsWorkedOutWithItsPicture() {
        var ref = tiles.resolve("Nex: Torva boots");
        assertEquals("Nex", ref.boss());
        assertEquals("Torva boots", ref.item());
        assertEquals("torva_boots", ref.iconKey());
        assertTrue(BingoTiles.icon(ref.iconKey()).isPresent());
    }

    @Test
    void theBossIsRespelledTheWayTheCatalogueDoesAndAnItemFromAnotherListStillFindsItsPicture() {
        assertEquals("Kree'arra", tiles.resolve("Kree'arra: Armadyl helmet").boss());
        // "Godwars Dungeon" is not a boss in the catalogue, but the shard is an item the catalogue knows
        var shard = tiles.resolve("Godwars Dungeon: Godsword shard 1");
        assertEquals("Godwars Dungeon", shard.boss());
        assertEquals("godsword_shard_1", shard.iconKey());
        // items RuneMetrics reports that the loot tables do not list still have the bot's own picture
        assertEquals("abyssal_whip", tiles.resolve("Abyssal demon: Abyssal whip").iconKey());
    }

    @Test
    void everyTileOfTheDefaultDropsPoolHasAPicture() {
        for (var spec : BingoBoard.dropsPool()) assertNotNull(tiles.iconKeyFor(spec.label()), spec.label());
    }

    @Test
    void aTileThatIsNotADropHasNoPictureAndNoBoss() {
        var ref = tiles.resolve("Reach 99 Slayer");
        assertNull(ref.boss());
        assertNull(ref.iconKey());
        assertNull(tiles.iconKeyFor(""));
        assertNull(tiles.iconKeyFor(null));
        assertNull(tiles.iconKeyFor("Nex: a thing that does not exist"));
    }

    @Test
    void theChoicesListEveryBossWithItsLootAndLeaveOutGuaranteedDrops() {
        DataArray bosses = tiles.choices().getArray("bosses");
        assertTrue(bosses.length() > 30);
        DataObject nex = null;
        for (int i = 0; i < bosses.length(); i++) if (bosses.getObject(i).getString("name").equals("Nex")) nex = bosses.getObject(i);
        assertNotNull(nex);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < nex.getArray("items").length(); i++) items.add(nex.getArray("items").getObject(i).getString("name"));
        assertTrue(items.contains("Torva platelegs") && items.contains("Virtus wand"), items.toString());
        assertFalse(items.contains("Big bones"), "bones always drop, so they make no bingo tile");
    }

    private static List<TileView> nine(TileView first) {
        List<TileView> list = new ArrayList<>(Collections.nCopies(9, new TileView(1, "Nex: Torva boots", 50, false, 0, 0)));
        list.set(0, first);
        return list;
    }
}
