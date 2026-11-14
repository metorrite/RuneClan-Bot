package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.HubNameChanges;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-1160: the Name Changes buttons decide through the hub, for staff only; the old rename cards point there. */
class RsnRenameButtonsTest {
    private static final long GUILD = 10L;

    private HubNameChanges nameChanges;
    private AdminRoleFilter adminFilter;
    private RsnRenameInteractionListener listener;

    @BeforeEach
    void setUp() {
        nameChanges = mock(HubNameChanges.class);
        adminFilter = mock(AdminRoleFilter.class);
        listener = new RsnRenameInteractionListener(nameChanges, adminFilter);
    }

    private static ButtonInteractionEvent press(String id, Guild guild, Member member) {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(event.getComponentId()).thenReturn(id);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(event.getUser().getIdLong()).thenReturn(1L);
        return event;
    }

    private static Guild guild() {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        return guild;
    }

    @Test
    void aMemberWhoIsNotStaffCannotLinkRejectOrUndo() {
        Guild guild = guild();
        Member member = mock(Member.class);
        when(adminFilter.isSupportTier(guild, member)).thenReturn(false);

        for (String id : List.of("namechg_link:4", "namechg_reject:4", "namechg_undo:4", "namechg_list:_")) {
            ButtonInteractionEvent event = press(id, guild, member);
            listener.onButtonInteraction(event);
            verify(event).replyComponents(Mockito.anyList());
        }
        verify(nameChanges, never()).decide(anyLong(), anyLong(), anyString(), anyLong());
        verify(nameChanges, never()).undo(anyLong(), anyLong(), anyLong());
    }

    @Test
    void staffPressesAreAnsweredStraightAwayAndDoneInTheBackground() {
        Guild guild = guild();
        Member staff = mock(Member.class);
        when(adminFilter.isSupportTier(guild, staff)).thenReturn(true);

        ButtonInteractionEvent link = press("namechg_link:4", guild, staff);
        listener.onButtonInteraction(link);
        ButtonInteractionEvent list = press("namechg_list:_", guild, staff);
        listener.onButtonInteraction(list);

        verify(link).deferEdit();
        verify(list).deferReply(true);
    }

    @Test
    void anOldRenameCardPointsToNameChanges() {
        ButtonInteractionEvent event = press("rsnrename_confirm:5", null, null);

        listener.onButtonInteraction(event);

        verify(event).replyComponents(Mockito.anyList());
        verify(nameChanges, never()).decide(anyLong(), anyLong(), anyString(), anyLong());
    }

    private static List<String> buttons(Container card) {
        return ComponentTree.of(List.of(card)).findAll(Button.class).stream().map(Button::getCustomId).toList();
    }

    private static String text(Container card) {
        return String.join("\n", ComponentTree.of(List.of(card)).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());
    }

    @Test
    void theListShowsLinkAndRejectForWaitingOnesAndUndoForUndoableLinks() {
        var open = new HubNameChanges.Result<>(List.of(new HubNameChanges.Change(4, "Old", "New", "likely", "fits", "open", false)), null);
        var linked = new HubNameChanges.Result<>(List.of(
                new HubNameChanges.Change(3, "A", "B", "sure", "", "linked", true),
                new HubNameChanges.Change(2, "C", "D", "sure", "", "linked", false)), null);

        Container card = RsnRenameInteractionListener.card(open, linked, null);

        assertEquals(List.of("namechg_link:4", "namechg_reject:4", "namechg_undo:3"), buttons(card), "a final link has no Undo");
    }

    @Test
    void aHubProblemIsShownInPlainWords() {
        Container card = RsnRenameInteractionListener.card(new HubNameChanges.Result<>(null, "RuneClans didn't answer."), new HubNameChanges.Result<>(null, "x"), null);

        assertTrue(text(card).contains("RuneClans didn't answer."));
        assertEquals(List.of(), buttons(card));
    }

    @Test
    void theListNeverHoldsMoreThanEightChanges() {
        List<HubNameChanges.Change> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(new HubNameChanges.Change(i, "O" + i, "N" + i, "possible", "", "open", false));

        Container card = RsnRenameInteractionListener.card(new HubNameChanges.Result<>(many, null), new HubNameChanges.Result<>(List.of(), null), null);

        assertEquals(RsnRenameInteractionListener.SHOWN * 2, buttons(card).size());
        assertTrue(card.getComponents().size() + buttons(card).size() + 1 <= 40, "inside Discord's 40 components a message");
    }
}
