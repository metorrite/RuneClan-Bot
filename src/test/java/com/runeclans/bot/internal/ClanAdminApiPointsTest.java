package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.runescape.ClanPointsRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's clan points page (T-978): a hub blip is never shown, or saved back, as zeros. */
class ClanAdminApiPointsTest {
    // made-up ids, built at run time
    private static final long GUILD = Long.parseLong("6".repeat(17) + "1");
    private static final long ACTOR = Long.parseLong("6".repeat(17) + "2");

    private final ClanPointsRepository points = mock(ClanPointsRepository.class);
    private final ClanAdminApi api = new ClanAdminApi(null, points, null, null, null, null, null, null, null);
    private final Guild guild = mock(Guild.class);
    private final Member actor = mock(Member.class);

    ClanAdminApiPointsTest() {
        when(guild.getIdLong()).thenReturn(GUILD);
        when(actor.getIdLong()).thenReturn(ACTOR);
    }

    @Test
    void anUnknownAnswerIsA503NotZeros() {
        when(points.known(GUILD)).thenReturn(null);
        assertEquals(503, assertThrows(ApiError.class, () -> api.clanPoints(guild)).status);

        when(points.known(GUILD)).thenReturn(DataObject.empty().put("dailyMembershipPoints", 5).put("citadelVisitPoints", 3).put("citadelCapPoints", 20));
        DataObject shown = api.clanPoints(guild);
        assertEquals(3, shown.getInt("citadelVisitPoints"));
        assertEquals(ClanPointsRepository.STANDARD_RANK_NAMES.size(), shown.getArray("ranks").length());
    }

    @Test
    void aSaveSendsOnlyTheAmountsItNames() {
        when(points.save(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(DataObject.empty());
        when(points.known(GUILD)).thenReturn(DataObject.empty().put("dailyMembershipPoints", 7));
        api.saveClanPoints(guild, actor, DataObject.empty().put("dailyMembershipPoints", 7).putNull("citadelCapPoints")
                .put("ranks", DataArray.empty().add(DataObject.empty().put("id", "1").put("threshold", 40))));
        verify(points).save(eq(GUILD), eq(ACTOR), eq(7L), isNull(), isNull(), eq(Map.of(1L, 40L)));
    }

    /** B-36: the panel after a save is drawn from the save's own answer, with no second hub read. */
    @Test
    void aSaveIsDrawnFromItsOwnAnswerWithNoSecondRead() {
        when(points.save(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(DataObject.empty().put("dailyMembershipPoints", 9).put("citadelVisitPoints", 4).put("citadelCapPoints", 30));
        DataObject shown = api.saveClanPoints(guild, actor, DataObject.empty().put("dailyMembershipPoints", 9));
        assertEquals(9, shown.getInt("dailyMembershipPoints"));
        assertEquals(30, shown.getInt("citadelCapPoints"));
        assertEquals(ClanPointsRepository.STANDARD_RANK_NAMES.size(), shown.getArray("ranks").length());
        org.mockito.Mockito.verify(points, org.mockito.Mockito.never()).known(anyLong());

        when(points.save(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(null);
        assertEquals(503, assertThrows(ApiError.class, () -> api.saveClanPoints(guild, actor, DataObject.empty().put("dailyMembershipPoints", 9))).status);
    }
}
