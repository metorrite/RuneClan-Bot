package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanPointsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Configure Points & Ranks" tool on {@code /rsadmin}'s panel — lets an admin set the three point
 * values (daily membership, Citadel visit, Citadel cap) and each clan rank's point threshold, both of
 * which {@code ClanPointsService} reads every day to award points and decide who needs a promotion.
 * <p>
 * A separate listener from {@link RsAdminInteractionListener}, same reasoning as
 * {@link PruneInteractionListener}. Ranks are edited in pages of up to 5 (a modal's own component
 * cap) rather than one button/modal per rank — with the standard 11-rank ladder that's 3 modals to
 * fully configure instead of 11.
 * <p>
 * The settings live on the RuneClans hub (T-978). Discord wants an answer within 3 seconds and a hub read can take longer, so every
 * button that shows the panel acknowledges first and edits the reply when the hub answers; the edit buttons carry the values the panel
 * showed in their ids, so the modals open without a hub read; and a modal's save acknowledges first and draws the panel from the save's
 * own answer. A thresholds save sends only the thresholds, so the amounts are never overwritten from an old copy.
 */
@BService
public class ClanPointsInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsInteractionListener.class);
    private static final int RANKS_PER_PAGE = 5;

    private final AdminRoleFilter adminRoleFilter;
    private final ClanPointsRepository repository;

    public ClanPointsInteractionListener(AdminRoleFilter adminRoleFilter, ClanPointsRepository repository) {
        this.adminRoleFilter = adminRoleFilter;
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("clanpoints_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.you_need_the_admin_role_or"));
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "clanpoints_open" -> event.deferReply(true).queue(hook -> showPanel(event, guild.getIdLong()), failure -> {});
                case "clanpoints_main" -> event.deferEdit().queue(hook -> showPanel(event, guild.getIdLong()), failure -> {});
                case "clanpoints_edit_values" -> {
                    List<Long> carried = numbers(parts.length > 1 ? parts[1] : "");
                    if (!complete(carried, 3)) {
                        Containers.replyEphemeral(event, Containers.WARNING, STALE_PANEL);
                        return;
                    }
                    event.replyModal(buildValuesModal(carried)).queue();
                }
                case "clanpoints_edit_ranks" -> {
                    String[] page = (parts.length > 1 ? parts[1] : "0").split(":", 2);
                    int at = Integer.parseInt(page[0]);
                    List<Long> carried = numbers(page.length > 1 ? page[1] : "");
                    if (!complete(carried, pageOf(ladder(0), at).size())) {
                        Containers.replyEphemeral(event, Containers.WARNING, STALE_PANEL);
                        return;
                    }
                    event.replyModal(buildRanksModal(at, carried)).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in clan points button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("clanpoints_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.you_need_the_admin_role_or"));
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "clanpoints_values_modal" -> handleValuesModal(event, guild);
                case "clanpoints_ranks_modal" -> handleRanksModal(event, guild, Integer.parseInt(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in clan points modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** Draws the panel into an acknowledged interaction's reply, or the error toast when that fails. */
    private void showPanel(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, long guildId) {
        try {
            DataObject json = repository.known(guildId);
            event.getHook().editOriginalComponents(List.of(buildMainPanel(guildId, json))).useComponentsV2(true).queue(ok -> {}, failure -> {});
        } catch (Exception e) {
            log.error("Could not draw the clan points panel", e);
            Containers.replyError(event);
        }
    }

    /** Said when an edit button doesn't carry the values it would edit (a panel from before T-978, or a damaged id). */
    static final String STALE_PANEL = "This panel is out of date. Open Configure Points & Ranks again.";

    /** True when {@code carried} holds exactly {@code size} numbers, none missing. */
    static boolean complete(List<Long> carried, int size) {
        return carried.size() == size && carried.stream().allMatch(java.util.Objects::nonNull);
    }

    /** The numbers a panel button carries ("5,3,20"); missing or bad ones are null. */
    static List<Long> numbers(String carried) {
        List<Long> out = new ArrayList<>();
        if (carried == null || carried.isBlank()) return out;
        for (String part : carried.split(",")) out.add(parseNonNegative(part));
        return out;
    }

    private static String prefill(List<Long> carried, int at) {
        return at < carried.size() && carried.get(at) != null ? String.valueOf(carried.get(at)) : "";
    }

    private Modal buildValuesModal(List<Long> carried) {
        TextInput daily = TextInput.create("daily_points", TextInputStyle.SHORT)
                .setValue(prefill(carried, 0)).setRequired(true).build();
        TextInput visit = TextInput.create("visit_points", TextInputStyle.SHORT)
                .setValue(prefill(carried, 1)).setRequired(true).build();
        TextInput cap = TextInput.create("cap_points", TextInputStyle.SHORT)
                .setValue(prefill(carried, 2)).setRequired(true).build();

        return Modal.create("clanpoints_values_modal:_", "Edit Point Values")
                .addComponents(
                        Label.of("Points per day in the clan", daily),
                        Label.of("Points per Citadel visit", visit),
                        Label.of("Points per Citadel cap", cap))
                .build();
    }

    private void handleValuesModal(ModalInteractionEvent event, Guild guild) {
        Long daily = parseNonNegative(event.getValue("daily_points").getAsString());
        Long visit = parseNonNegative(event.getValue("visit_points").getAsString());
        Long cap = parseNonNegative(event.getValue("cap_points").getAsString());

        if (daily == null || visit == null || cap == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.all_three_values_must_be_whole"));
            return;
        }

        long guildId = guild.getIdLong(), actor = event.getUser().getIdLong();
        event.deferEdit().queue(hook -> saveAndShow(event, guildId, actor, daily, visit, cap, Map.of()), failure -> {});
    }

    /** Saves through the hub (after the acknowledgement) and draws the panel from the save's answer, or shows the error toast. */
    private void saveAndShow(ModalInteractionEvent event, long guildId, long actor, Long daily, Long visit, Long cap, Map<Long, Long> thresholds) {
        try {
            DataObject saved = repository.save(guildId, actor, daily, visit, cap, thresholds);
            if (saved == null) {
                Containers.replyError(event);
                return;
            }
            event.getHook().editOriginalComponents(List.of(buildMainPanel(guildId, saved))).useComponentsV2(true).queue(ok -> {}, failure -> {});
        } catch (Exception e) {
            log.error("Could not save clan points", e);
            Containers.replyError(event);
        }
    }

    /** The standard ladder (the hub's is always the standard one); ids are rank orders. */
    private static List<ClanPointsRepository.RankConfigRow> ladder(long guildId) {
        return ClanPointsRepository.ranksOf(guildId, null);
    }

    private Modal buildRanksModal(int page, List<Long> carried) {
        List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ladder(0), page);

        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < pageRanks.size(); i++) {
            var rank = pageRanks.get(i);
            TextInput input = TextInput.create("rank_" + rank.id(), TextInputStyle.SHORT)
                    .setValue(prefill(carried, i)).setRequired(true).build();
            labels.add(Label.of(rank.rankName() + " — point threshold", input));
        }

        return Modal.create("clanpoints_ranks_modal:" + page, "Edit Rank Thresholds").addComponents(labels).build();
    }

    private void handleRanksModal(ModalInteractionEvent event, Guild guild, int page) {
        long guildId = guild.getIdLong();
        List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ladder(guildId), page);

        Map<Long, Long> parsedThresholds = new LinkedHashMap<>();
        for (var rank : pageRanks) {
            Long threshold = parseNonNegative(event.getValue("rank_" + rank.id()).getAsString());
            if (threshold == null) {
                Containers.replyEphemeral(event, Containers.WARNING,
                        Text.of(event, "runescape.threshold_must_be_a_whole_number", rank.rankName()));
                return;
            }
            parsedThresholds.put(rank.id(), threshold);
        }

        // Applied only after every field on the page validates — an all-or-nothing save, since a
        // half-applied page would be confusing (some ranks updated, the rest silently not). Only the
        // thresholds are sent: the amounts stay as the hub has them.
        long actor = event.getUser().getIdLong();
        event.deferEdit().queue(hook -> saveAndShow(event, guildId, actor, null, null, null, parsedThresholds), failure -> {});
    }

    private static List<ClanPointsRepository.RankConfigRow> pageOf(List<ClanPointsRepository.RankConfigRow> ranks, int page) {
        int from = Math.min(page * RANKS_PER_PAGE, ranks.size());
        int to = Math.min(from + RANKS_PER_PAGE, ranks.size());
        return ranks.subList(from, to);
    }

    private static Long parseNonNegative(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The panel for a points answer ({@code json}: the hub's read or a save's answer; null when the hub didn't answer). */
    Container buildMainPanel(long guildId, DataObject json) {
        ClanPointsRepository.PointsSettings settings = ClanPointsRepository.settingsOf(json);
        List<ClanPointsRepository.RankConfigRow> ranks = ClanPointsRepository.ranksOf(guildId, json);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Points & Promotions"));
        children.add(TextDisplay.of("-# Points are awarded daily for clan membership and weekly for Citadel visits/caps. A member is flagged for promotion once their total points meet a higher rank's threshold than their current in-game rank — see the daily \"Clan Report\" in the Tracking panel."));

        children.add(TextDisplay.of("**Points per day in the clan:** " + settings.dailyMembershipPoints() +
                "\n**Points per Citadel visit:** " + settings.citadelVisitPoints() +
                "\n**Points per Citadel cap:** " + settings.citadelCapPoints()));
        if (json == null) {
            // unknown values are never offered for editing: a save would write these zeros back over the real ones
            children.add(TextDisplay.of("⚠️ RuneClans didn't answer, or this server has no clan site yet, so the saved values can't be shown or edited right now."));
            children.add(ActionRow.of(Button.secondary("clanpoints_main:_", "Try Again")));
            return Containers.card(Containers.PRIMARY, children);
        }
        children.add(ActionRow.of(Button.secondary("clanpoints_edit_values:" + settings.dailyMembershipPoints() + "," + settings.citadelVisitPoints() + ","
                + settings.citadelCapPoints(), "Edit Point Values")));

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("**Rank Thresholds** (lowest to highest):"));

        StringBuilder list = new StringBuilder();
        for (var rank : ranks) list.append("**").append(rank.rankName()).append("** — ").append(rank.pointThreshold()).append(" pts\n");
        children.add(TextDisplay.of(list.toString().trim()));

        List<Button> pageButtons = new ArrayList<>();
        int pageCount = (ranks.size() + RANKS_PER_PAGE - 1) / RANKS_PER_PAGE;
        for (int page = 0; page < pageCount; page++) {
            List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ranks, page);
            String label = pageRanks.size() == 1
                    ? "Edit " + pageRanks.getFirst().rankName()
                    : "Edit " + pageRanks.getFirst().rankName() + "–" + pageRanks.getLast().rankName();
            StringBuilder carried = new StringBuilder();
            for (var rank : pageRanks) carried.append(carried.isEmpty() ? "" : ",").append(rank.pointThreshold());
            pageButtons.add(Button.secondary("clanpoints_edit_ranks:" + page + ":" + carried, label));
        }
        if (!pageButtons.isEmpty()) children.add(ActionRow.of(pageButtons));

        return Containers.card(Containers.PRIMARY, children);
    }
}
