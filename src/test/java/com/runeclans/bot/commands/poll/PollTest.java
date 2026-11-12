package com.runeclans.bot.commands.poll;



import com.runeclans.bot.discord.Containers;

import net.dv8tion.jda.api.components.buttons.Button;

import net.dv8tion.jda.api.components.container.Container;

import net.dv8tion.jda.api.components.mediagallery.MediaGallery;

import net.dv8tion.jda.api.components.section.Section;

import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import net.dv8tion.jda.api.components.tree.ComponentTree;

import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import org.junit.jupiter.api.Test;



import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;

import java.io.ByteArrayInputStream;

import java.util.ArrayList;

import java.util.HashMap;

import java.util.List;

import java.util.Map;



import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;



class PollTest {

    private static final long GUILD = 1L, CHANNEL = 2L, OWNER = 10L, OTHER = 11L;



    // ---------- bars ----------



    @Test

    void aBarIsAValidTransparentPngOfTheFixedSize() throws Exception {

        for (double share : new double[]{0.0, 0.004, 0.5, 1.0, -3.0, 7.0}) {

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(PollBarRenderer.render(share, PollBarRenderer.Style.ACTIVE, "3 votes", "50%")));

            assertEquals(PollBarRenderer.WIDTH, image.getWidth());

            assertEquals(PollBarRenderer.HEIGHT, image.getHeight());

            assertTrue(image.getColorModel().hasAlpha(), "transparent so it sits on the container colour");

        }

    }



    @Test

    void aBiggerShareFillsMoreOfTheBar() throws Exception {

        assertTrue(filledPixels(0.25) < filledPixels(0.75));

        assertTrue(filledPixels(0.0) < filledPixels(0.01), "even 1% shows a visible nub");

    }



    private static int filledPixels(double share) throws Exception {

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(PollBarRenderer.render(share, PollBarRenderer.Style.ACTIVE, null, null)));

        int row = image.getHeight() / 2, filled = 0;

        for (int x = 0; x < PollBarRenderer.barWidth(false); x++) {

            int argb = image.getRGB(x, row);

            int blue = argb & 0xFF, red = (argb >> 16) & 0xFF;

            if (blue - red > 40) filled++; // the fill is blurple; the empty track is near-white

        }

        return filled;

    }



    // ---------- the poll message ----------



    private static PollSession session(boolean anonymous, boolean multiple) {

        return new PollSession(5, GUILD, CHANNEL, 99L, "Which boss next?", anonymous, multiple, "ACTIVE", OWNER);

    }



    private static List<PollOption> options(int count) {

        List<PollOption> options = new ArrayList<>();

        for (int i = 1; i <= count; i++) options.add(new PollOption(100 + i, 5, i, "Option number " + i + " with a reasonably long label to stress the text"));

        return options;

    }



    private static void assertFitsInAMessage(Container container) {

        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();

    }



    @Test

    void everyOptionIsAButtonRowThenItsBar() {

        Container poll = PollView.build(session(false, false), options(3), Map.of(101L, 2, 102L, 1), Map.of(), false, true);

        assertFitsInAMessage(poll);



        var tree = ComponentTree.of(List.of(poll));

        assertEquals(3, tree.findAll(MediaGallery.class).size(), "a bar per option");

        assertEquals(0, tree.findAll(Section.class).size(), "no side-by-side rows: the buttons sit left-aligned above their bars");

        assertEquals(List.of("poll_vote:5:1", "poll_vote:5:2", "poll_vote:5:3", "poll_mine:5"),

                tree.findAll(Button.class).stream().map(Button::getCustomId).toList());

        assertTrue(tree.findAll(Button.class).stream().filter(x -> x.getCustomId().startsWith("poll_vote")).allMatch(x -> x.getLabel().startsWith("Option number")), "the option's own label is the button");



        // Every option has the identical shape — button row immediately followed by its bar — so the buttons never drift.

        List<String> shape = poll.getComponents().stream().map(c -> c.getClass().getInterfaces()[0].getSimpleName()).toList();

        assertEquals(List.of("TextDisplay", "ActionRow", "MediaGallery", "ActionRow", "MediaGallery", "ActionRow", "MediaGallery", "Separator", "TextDisplay", "ActionRow"), shape);

    }



    @Test

    void theWorstCaseSixOptionsWithFullVoterListsStillFitsInOneMessage() {

        Map<Long, Integer> counts = new HashMap<>();

        Map<Long, List<Long>> voters = new HashMap<>();

        for (PollOption option : options(6)) {

            counts.put(option.optionId(), 40);

            List<Long> ids = new ArrayList<>();

            for (long u = 0; u < 40; u++) ids.add(900_000_000_000_000_000L + u);

            voters.put(option.optionId(), ids);

        }

        assertFitsInAMessage(PollView.build(session(false, true), options(6), counts, voters, false));

        assertFitsInAMessage(PollView.build(session(false, true), options(6), counts, voters, true));

    }



    @Test

    void aClosedPollDisablesEveryButtonAndPicksTheWinner() {

        Container closed = PollView.build(session(false, false), options(3), Map.of(101L, 1, 102L, 5, 103L, 2), Map.of(), true, true);

        var tree = ComponentTree.of(List.of(closed));



        assertTrue(tree.findAll(Button.class).stream().allMatch(Button::isDisabled));

        assertTrue(text(closed).contains("Closed"));

        List<String> trophies = tree.findAll(Button.class).stream().map(Button::getLabel).filter(l -> l.startsWith("🏆")).toList();

        assertEquals(1, trophies.size(), "exactly one winner is marked");

        assertTrue(trophies.getFirst().contains("Option number 2"));

    }



    @Test

    void aTieMarksEveryTiedOptionButAnEmptyPollMarksNone() {

        assertEquals(2, trophyCount(PollView.build(session(false, false), options(2), Map.of(101L, 3, 102L, 3), Map.of(), true, true)));

        assertEquals(0, trophyCount(PollView.build(session(false, false), options(2), Map.of(), Map.of(), true, true)));

    }



    private static long trophyCount(Container container) {

        return ComponentTree.of(List.of(container)).findAll(Button.class).stream().filter(b -> b.getLabel().startsWith("🏆")).count();

    }



    @Test

    void anAnonymousPollShowsNoVotersAndTheFooterSaysSo() {

        String text = text(PollView.build(session(true, false), options(2), Map.of(101L, 1), Map.of(), false));

        assertFalse(text.contains("↳"));

        assertTrue(text.contains("anonymous"));

    }



    @Test

    void anOpenPollShowsWhoVotedUpToFiveAndCountsTheRest() {

        Map<Long, List<Long>> voters = Map.of(101L, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L));

        String text = text(PollView.build(session(false, false), options(2), Map.of(101L, 7), voters, false));

        assertTrue(text.contains("<@1>, <@2>, <@3>, <@4>, <@5>"));

        assertTrue(text.contains("+2 more"));

        assertFalse(text.contains("<@6>"));

    }



    private static String text(Container container) {

        return String.join("\n", ComponentTree.of(List.of(container)).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());

    }



    // ---------- the /poll panel ----------



    private static PollSession ownedBy(long pollId, long owner) {

        return new PollSession(pollId, GUILD, CHANNEL, 1000 + pollId, "Poll " + pollId, false, false, "ACTIVE", owner);

    }



    @Test

    void aMemberSeesOnlyTheirOwnPollsAndAnAdminSeesEveryone() {

        PollService service = mock(PollService.class);

        when(service.activePolls(GUILD)).thenReturn(List.of(ownedBy(3, OTHER), ownedBy(2, OWNER), ownedBy(1, OTHER)));

        when(service.activePollsOwnedBy(GUILD, OWNER)).thenReturn(List.of(ownedBy(2, OWNER)));

        PollPanel panel = new PollPanel(service);



        List<String> memberView = endButtons(panel.build(GUILD, OWNER, false, 0, null));

        assertEquals(List.of("poll_end:2"), memberView);



        List<String> adminView = endButtons(panel.build(GUILD, 777L, true, 0, null));

        assertEquals(List.of("poll_end:3", "poll_end:2", "poll_end:1"), adminView);

    }



    @Test

    void thePanelAlwaysOffersCreateAndSaysWhenThereIsNothingToEnd() {

        PollService service = mock(PollService.class);

        PollPanel panel = new PollPanel(service);



        Container empty = panel.build(GUILD, OWNER, false, 0, null);

        assertFitsInAMessage(empty);

        assertTrue(ComponentTree.of(List.of(empty)).findAll(Button.class).stream().anyMatch(b -> "poll_new:_".equals(b.getCustomId())));

        assertTrue(text(empty).contains("no active polls"));

    }



    @Test

    void manyPollsArePagedAndEachPageFitsInAMessage() {

        PollService service = mock(PollService.class);

        List<PollSession> many = new ArrayList<>();

        for (long i = 12; i >= 1; i--) many.add(ownedBy(i, OWNER));

        when(service.activePolls(GUILD)).thenReturn(many);

        PollPanel panel = new PollPanel(service);



        Container first = panel.build(GUILD, 1L, true, 0, "✅ Poll posted in <#2>.");

        assertFitsInAMessage(first);

        assertEquals(PollPanel.PAGE_SIZE, endButtons(first).size());

        assertTrue(text(first).contains("Poll posted"));



        List<String> pageTwo = endButtons(panel.build(GUILD, 1L, true, 1, null));

        assertEquals(PollPanel.PAGE_SIZE, pageTwo.size());

        assertEquals(2, endButtons(panel.build(GUILD, 1L, true, 2, null)).size());

    }



    @Test

    void theEndConfirmationOffersEndEndAndDmAndCancel() {

        PollService service = mock(PollService.class);

        Container confirm = new PollPanel(service).buildEndConfirm(ownedBy(7, OWNER));

        assertFitsInAMessage(confirm);

        assertEquals(List.of("poll_end_go:7:0", "poll_end_go:7:1", "poll_end_cancel:_"),

                ComponentTree.of(List.of(confirm)).findAll(Button.class).stream().map(Button::getCustomId).toList());

    }



    @Test
    void anAnonymousPollsEndConfirmationOffersNoDmOfWhoVoted() {
        PollService service = mock(PollService.class);
        PollSession anonymous = new PollSession(8, GUILD, CHANNEL, 1008L, "Secret", true, false, "ACTIVE", OWNER);
        Container confirm = new PollPanel(service).buildEndConfirm(anonymous);
        assertEquals(List.of("poll_end_go:8:0", "poll_end_cancel:_"),
                ComponentTree.of(List.of(confirm)).findAll(Button.class).stream().map(Button::getCustomId).toList());
    }

    @Test
    void theResultsSummaryNamesVotersOnlyOnAnOpenBallot() {
        com.runeclans.bot.poll.PollStore store = mock(com.runeclans.bot.poll.PollStore.class);
        when(store.getAllActivePolls()).thenReturn(List.of());
        PollOption yes = new PollOption(100L, 8L, 1, "Yes"), no = new PollOption(101L, 8L, 2, "No");
        when(store.getOptions(8L)).thenReturn(List.of(yes, no));
        when(store.getOptions(9L)).thenReturn(List.of(yes, no));
        when(store.getVoteCounts(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Map.of(100L, 2, 101L, 1));
        when(store.getVotersByOption(org.mockito.ArgumentMatchers.anyLong())).thenReturn(Map.of(100L, List.of(OWNER, OTHER), 101L, List.of(12L)));
        when(store.getPollById(8L)).thenReturn(new PollSession(8, GUILD, CHANNEL, 1008L, "Secret", true, false, "CLOSED", OWNER));
        when(store.getPollById(9L)).thenReturn(new PollSession(9, GUILD, CHANNEL, 1009L, "Open", false, false, "CLOSED", OWNER));
        PollService service = new PollService(store);

        String anonymous = service.buildResultsSummary(8L);
        assertFalse(anonymous.contains("<@"), anonymous);
        assertTrue(anonymous.contains("2 votes") && anonymous.contains("1 vote"), "the numbers still show");
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).getVotersByOption(8L);

        String open = service.buildResultsSummary(9L);
        assertTrue(open.contains("<@" + OWNER + ">") && open.contains("<@12>"), open);
    }

    private static List<String> endButtons(Container panel) {

        return ComponentTree.of(List.of(panel)).findAll(Button.class).stream().map(Button::getCustomId)

                .filter(id -> id.startsWith("poll_end:")).toList();

    }



    // ---------- who may end a poll ----------



    @Test

    void onlyTheStarterOrAnAdminMayEndAPoll() {

        PollSession poll = ownedBy(1, OWNER);

        assertTrue(PollService.canManage(poll, OWNER, false));

        assertTrue(PollService.canManage(poll, OTHER, true));

        assertFalse(PollService.canManage(poll, OTHER, false));

    }



    // ---------- the create form ----------



    @Test

    void optionsAreOnePerNonBlankLine() {

        assertEquals(List.of("A", "B", "C"), PollInteractionListener.parseOptions("A\n\n  B  \r\nC\n"));

        assertEquals(List.of(), PollInteractionListener.parseOptions("  \n \n"));

    }



    @Test

    void aGoodPollPassesAndEachProblemIsExplained() {

        assertNull(PollInteractionListener.validate("Which?", List.of("A", "B")));

        assertNull(PollInteractionListener.validate("Which?", List.of("A", "B", "C", "D", "E", "F")));



        assertNotNull(PollInteractionListener.validate("  ", List.of("A", "B")));

        assertTrue(PollInteractionListener.validate("Which?", List.of("only one")).contains("at least 2"));

        assertTrue(PollInteractionListener.validate("Which?", List.of("1", "2", "3", "4", "5", "6", "7")).contains("at most 6"));

        assertTrue(PollInteractionListener.validate("Which?", List.of("Same", "same")).contains("twice"));

        assertTrue(PollInteractionListener.validate("x".repeat(151), List.of("A", "B")).contains("too long"));

        assertTrue(PollInteractionListener.validate("Which?", List.of("A", "x".repeat(101))).contains("longer than"));

    }



    @Test

    void theCreateFormHasTheQuestionOptionsAndTheTwoCheckboxes() {

        var modal = PollInteractionListener.buildCreateModal();

        assertEquals("poll_new_modal:_", modal.getId());

        assertEquals(4, modal.getComponents().size());

    }



    @Test

    void withoutFontsTheCountAndPercentageMoveToATextLineUnderTheBar() {

        Container poll = PollView.build(session(false, false), options(2), Map.of(101L, 1, 102L, 3), Map.of(), false, false);

        assertFitsInAMessage(poll);

        String text = text(poll);

        assertTrue(text.contains("1 vote  ·  **25%**"), text);

        assertTrue(text.contains("3 votes  ·  **75%**"), text);

    }



    @Test

    void withFontsThoseNumbersAreInTheImageSoNoSeparateLineIsAdded() {

        String text = text(PollView.build(session(false, false), options(2), Map.of(101L, 1, 102L, 3), Map.of(), false, true));

        assertFalse(text.contains("25%"), "the percentage lives inside the bar image");

    }



    @Test

    void theNoFontFallbackStillFitsTheWorstCase() {

        Map<Long, Integer> counts = new HashMap<>();

        Map<Long, List<Long>> voters = new HashMap<>();

        for (PollOption option : options(6)) {

            counts.put(option.optionId(), 40);

            List<Long> ids = new ArrayList<>();

            for (long u = 0; u < 40; u++) ids.add(900_000_000_000_000_000L + u);

            voters.put(option.optionId(), ids);

        }

        assertFitsInAMessage(PollView.build(session(false, true), options(6), counts, voters, false, false));

    }



    @Test

    void drawingTheNumbersChangesTheImage() {

        org.junit.jupiter.api.Assumptions.assumeTrue(PollBarRenderer.textAvailable(), "no fonts on this machine");

        byte[] with = PollBarRenderer.render(0.5, PollBarRenderer.Style.ACTIVE, "3 votes", "50%");

        byte[] without = PollBarRenderer.render(0.5, PollBarRenderer.Style.ACTIVE, null, null);

        assertFalse(java.util.Arrays.equals(with, without));

    }

    @Test
    void anOpenPollHasAMyVotesButtonAndAClosedOneDoesNot() {
        Container open = PollView.build(session(false, true), options(2), Map.of(), Map.of(), false, true);
        Container closed = PollView.build(session(false, true), options(2), Map.of(), Map.of(), true, true);
        assertTrue(ComponentTree.of(List.of(open)).findAll(Button.class).stream().anyMatch(b -> "poll_mine:5".equals(b.getCustomId())));
        assertTrue(ComponentTree.of(List.of(closed)).findAll(Button.class).stream().noneMatch(b -> b.getCustomId().startsWith("poll_mine")));
    }

    @Test
    void theBarFillsTheSpaceTheNumbersDoNotNeed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(PollBarRenderer.textAvailable(), "no fonts on this machine");
        int withNumbers = PollBarRenderer.barWidth(true);
        assertTrue(withNumbers > 650, "wider than the old fixed 610: " + withNumbers);
        assertTrue(withNumbers < PollBarRenderer.WIDTH - 150, "still leaves room for '999 votes' and '100%': " + withNumbers);
        assertEquals(PollBarRenderer.WIDTH, PollBarRenderer.barWidth(false), "with no numbers drawn the bar runs the full width");
        assertEquals(withNumbers, PollBarRenderer.barWidth(true), "every option's bar ends in the same place");
    }

    @Test
    void myVoteLinesListWhatThePersonPickedInOptionOrder() {
        com.runeclans.bot.poll.PollRepository repository = mock(com.runeclans.bot.poll.PollRepository.class);
        when(repository.getOptions(5)).thenReturn(options(4));
        when(repository.getUserVotes(5, OWNER)).thenReturn(java.util.Set.of(104L, 102L));
        PollService service = new PollService(repository);

        List<String> lines = service.myVoteLines(5, OWNER);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("2️⃣ Option number 2"));
        assertTrue(lines.get(1).startsWith("4️⃣ Option number 4"));
        assertEquals(List.of(), service.myVoteLines(5, OTHER), "someone who hasn't voted has no lines");
    }

    @Test
    void theVoteReplyListsYourPicksOrSaysThereAreNone() {
        assertEquals("**Your votes:** none yet", PollInteractionListener.describeMine(List.of()));
        assertEquals("**Your votes:**\n1️⃣ A\n3️⃣ C", PollInteractionListener.describeMine(List.of("1️⃣ A", "3️⃣ C")));
    }
}
