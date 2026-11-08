package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanPointsRepository;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The Discord points panel (T-978): unknown values are never offered for editing, and the edit buttons carry what the panel showed. */
class ClanPointsPanelTest {
    private final ClanPointsInteractionListener listener = new ClanPointsInteractionListener(mock(AdminRoleFilter.class), mock(ClanPointsRepository.class));

    private static List<String> ids(Container panel) {
        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId).toList();
    }

    @Test
    void withNoHubAnswerThereIsNothingToEdit() {
        List<String> ids = ids(listener.buildMainPanel(1L, null));
        assertEquals(List.of("clanpoints_main:_"), ids, "only Try Again");
    }

    @Test
    void theEditButtonsCarryTheShownValues() {
        List<String> ids = ids(listener.buildMainPanel(1L, DataObject.empty().put("dailyMembershipPoints", 5).put("citadelVisitPoints", 3).put("citadelCapPoints", 20)));
        assertTrue(ids.contains("clanpoints_edit_values:5,3,20"), ids.toString());
        assertTrue(ids.contains("clanpoints_edit_ranks:0:0,0,0,0,0"), ids.toString());
        assertTrue(ids.stream().allMatch(id -> id.length() <= 100), "Discord's id limit");
    }

    @Test
    void anOldOrDamagedButtonOpensNoModal() {
        assertTrue(ClanPointsInteractionListener.complete(ClanPointsInteractionListener.numbers("5,3,20"), 3));
        assertFalse(ClanPointsInteractionListener.complete(ClanPointsInteractionListener.numbers("_"), 3), "a panel from before the move");
        assertFalse(ClanPointsInteractionListener.complete(ClanPointsInteractionListener.numbers("5,x,20"), 3));
        assertFalse(ClanPointsInteractionListener.complete(Arrays.asList(1L, 2L), 3));
    }
}
