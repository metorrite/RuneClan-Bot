package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.commands.coffer.CofferHubPanel;
import com.runeclans.bot.commands.configure.ConfigureInteractionListener;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.Pagination;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.ClanMemberRepository;
import com.runeclans.bot.runescape.ClanOverviewRenderer;
import com.runeclans.bot.runescape.ClanOverviewService;
import com.runeclans.bot.runescape.ClanSyncService;
import com.runeclans.bot.runescape.MonthlyRecapRenderer;
import com.runeclans.bot.runescape.MonthlyRecapService;
import com.runeclans.bot.runescape.MonthlyRecapStats;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.runescape.ProfileResult;
import com.runeclans.bot.runescape.RsnRenameService;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
import com.runeclans.bot.runescape.RuneScapeStatsService;
import com.runeclans.bot.runescape.RuneScapeXpTable;
import com.runeclans.bot.runescape.SkillValue;
import com.runeclans.bot.runescape.UnverifiedGuestService;
import com.runeclans.bot.runescape.VerificationAttempt;
import com.runeclans.bot.runescape.VerificationReviewCards;
import com.runeclans.bot.runescape.VerificationRoleSyncService;
import com.runeclans.bot.tracking.WeeklyDigestService;
import com.runeclans.bot.runescape.XpChartRenderer;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Buttons/modal for {@link RsAdminCommand}'s panel: bulk actions (poll everyone, sync the clan
 * roster), admin-only per-player tools (Player Lookup, Manually Verify, Update RSN), and the full
 * pending-verification queue (moved here from the member-facing {@code /rs} — see
 * {@link RsInteractionListener}, which only ever shows a caller their own pending status now).
 * <p>
 * Deliberately does <em>not</em> show the calling admin's own linked account(s) the way it once
 * did — that's what {@code /rs} is for, same as any other member, so there's exactly one place that
 * renders a self-service profile instead of two copies of the same view.
 * <p>
 * "Update" here is unrestricted (no self-poll cooldown) — that limit only applies to a member's own
 * {@code rs_poll} click under {@code /rs}; see {@code PlayerLinkService#canSelfPoll}.
 */
@BService
public class RsAdminInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsAdminInteractionListener.class);

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;
    private final MonthlyRecapService monthlyRecapService;
    private final ClanSyncService clanSyncService;
    private final ClanOverviewService clanOverviewService;
    private final VerificationRoleSyncService roleSyncService;
    private final RsnRenameService renameService;
    private final RsChartInteractionListener chartListener;
    private final RsInteractionListener rsInteractionListener;
    private final ConfigureInteractionListener configureListener;
    private final WeeklyDigestService weeklyDigestService;
    private final UnverifiedGuestService unverifiedGuestService;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public RsAdminInteractionListener(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                       AdminRoleFilter adminRoleFilter,
                                       MonthlyRecapService monthlyRecapService, ClanSyncService clanSyncService,
                                       ClanOverviewService clanOverviewService, VerificationRoleSyncService roleSyncService,
                                       RsnRenameService renameService, RsChartInteractionListener chartListener,
                                       RsInteractionListener rsInteractionListener,
                                       ConfigureInteractionListener configureListener,
                                       WeeklyDigestService weeklyDigestService,
                                       UnverifiedGuestService unverifiedGuestService) {
        this.unverifiedGuestService = unverifiedGuestService;
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
        this.monthlyRecapService = monthlyRecapService;
        this.clanSyncService = clanSyncService;
        this.clanOverviewService = clanOverviewService;
        this.roleSyncService = roleSyncService;
        this.renameService = renameService;
        this.chartListener = chartListener;
        this.rsInteractionListener = rsInteractionListener;
        this.configureListener = configureListener;
        this.weeklyDigestService = weeklyDigestService;
    }

    /**
     * The only {@code /rsadmin} actions the Support tier may use: reviewing and verifying RSN requests, one
     * at a time. Everything else (bulk Approve All, lookups, clan tools, configuration, pruning) needs the
     * Admin tier. The Admin tier may use everything.
     */
    static final java.util.Set<String> SUPPORT_ACTIONS = java.util.Set.of(
            "rsnadmin_manualverify", "rsnadmin_manualverify_modal", "rsnadmin_review_pending", "rsnadmin_pending_page",
            "rsnadmin_review_approve", "rsnadmin_review_reject", "rsnadmin_post_pending");

    private boolean mayUse(Guild guild, Member member, String componentOrModalId) {
        if (adminRoleFilter.isAuthorized(guild, member)) return true;
        return SUPPORT_ACTIONS.contains(componentOrModalId.split(":")[0]) && adminRoleFilter.isSupportTier(guild, member);
    }

    private String denialFor(Guild guild, Member member) {
        return adminRoleFilter.isSupportTier(guild, member)
                ? "That tool is for admins — your Support role covers reviewing and verifying RSN requests."
                : "You need the Admin or Support role to use this.";
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_")) return;

        try {
            if (!mayUse(guild, member, id)) {
                Containers.replyEphemeral(event, Containers.WARNING, denialFor(guild, member));
                return;
            }
            handleButton(event, guild, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_")) return;

        try {
            if (!mayUse(guild, member, id)) {
                Containers.replyEphemeral(event, Containers.WARNING, denialFor(guild, member));
                return;
            }
            if (id.equals("rsnadmin_manualverify_modal:_")) {
                handleManualVerifyModal(event, guild, member);
                return;
            }
            if (id.equals("rsnadmin_lookup_modal:_")) {
                handleLookupModal(event, guild);
                return;
            }
            if (id.equals("rsnadmin_citadel_range_modal:_")) {
                handleCitadelRangeModal(event, guild);
                return;
            }
            if (id.startsWith("rsnadmin_renamemodal:")) {
                handleRenameModal(event, guild, id.split(":", 2)[1]);
                return;
            }
            if (id.startsWith("rsnadmin_joindatemodal:")) {
                handleJoinDateModal(event, guild, id.split(":", 2)[1]);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** The Citadel viewer's "pick a specific week" menu — admin tier only, like the rest of the viewer. */
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_citadel_week")) return;

        try {
            if (!mayUse(guild, member, id)) {
                Containers.replyEphemeral(event, Containers.WARNING, denialFor(guild, member));
                return;
            }
            LocalDate week = LocalDate.parse(event.getValues().getFirst());
            showCitadelViewer(event, guild, week, week);
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /**
     * Always fetches live rather than reading storage first — this is the "look up anyone, in the
     * clan or not, tracked before or not" tool, so a stale/missing local row isn't a reason to stop.
     * Also saves the fetch as a real snapshot on success, the same as any other poll, so looking
     * someone up starts tracking them going forward.
     */
    private void handleLookupModal(ModalInteractionEvent event, Guild guild) {
        String rsn = event.getValue("lookup_rsn").getAsString().trim();
        event.deferReply(true).queue();

        Lookup lookup = lookup(rsn, event.getUser().getIdLong());
        event.getHook().editOriginalComponents(List.of(buildLookupCard(guild, rsn, lookup.result()))).useComponentsV2(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        noteAfter(event.getHook(), lookup);
    }

    /**
     * Repoints an existing link at a new RSN in place — the manual fallback for a name change,
     * for a link the RuneClans hub's name changes ({@link com.runeclans.bot.runescape.HubNameChanges}) don't cover. Historical
     * snapshots/activity stay under the old name; only the link moves.
     */
    private void handleRenameModal(ModalInteractionEvent event, Guild guild, String oldRsn) {
        String newRsn = event.getValue("new_rsn").getAsString().trim();
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), oldRsn);
        if (link == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.isn_t_linked_to_anyone_nothing", oldRsn, newRsn));
            return;
        }

        try {
            linkService.renameLink(guild.getIdLong(), link.linkId(), newRsn);
            renameService.recordManualRename(guild.getIdLong(), oldRsn, newRsn);
            Containers.replyEphemeral(event, Containers.SUCCESS,
                    Text.of(event, "runescape.updated_the_link_historical_data_stays", oldRsn, newRsn));
        } catch (RuntimeException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.couldn_t_update_may_already_be", newRsn));
        }
    }

    /**
     * Manual dev backfill for {@code clan_member.clan_joined_at} — see the column comment in
     * {@link com.runeclans.bot.runescape.RuneScapeDatabaseInitializer} for why this isn't populated
     * automatically yet. Independent of verification: a member can be in the clan roster (and have a
     * join date worth recording) whether or not their Discord account is linked at all.
     */
    private void handleJoinDateModal(ModalInteractionEvent event, Guild guild, String rsn) {
        String raw = event.getValue("clan_joined_at").getAsString().trim();
        LocalDate date;
        try {
            date = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.isn_t_a_valid_date_use", raw));
            return;
        }

        boolean updated = clanSyncService.setClanJoinedAt(guild.getIdLong(), rsn, date);
        Containers.replyEphemeral(event, updated ? Containers.SUCCESS : Containers.WARNING,
                updated ? Text.of(event, "runescape.set_clan_join_date_to", rsn, date) : Text.of(event, "runescape.isn_t_in_the_tracked_clan", rsn));
    }

    /** Links an RSN straight to a Discord user and applies the same role sync a real verification approval would — no makeover-mage dance needed. */
    private void handleManualVerifyModal(ModalInteractionEvent event, Guild guild, Member admin) {
        String rawUserId = event.getValue("manual_verify_discord_id").getAsString().trim().replaceAll("[<@!>]", "");
        String rsn = event.getValue("manual_verify_rsn").getAsString().trim();

        long discordUserId;
        try {
            discordUserId = Long.parseLong(rawUserId);
        } catch (NumberFormatException e) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.doesn_t_look_like_a_discord", rawUserId));
            return;
        }

        try {
            linkService.manualLink(guild.getIdLong(), discordUserId, rsn, admin.getIdLong());
        } catch (PlayerLinkRepository.RsnTakenException taken) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.is_already_registered_with_jonnybot_to", rsn, taken.ownerDiscordUserId()));
            return;
        }
        rsInteractionListener.closeMatchingRequestAfterManualLink(guild, discordUserId, rsn, admin.getIdLong());
        roleSyncService.syncRoles(guild, discordUserId, rsn);
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "runescape.linked_to", rsn, discordUserId));
    }

    private void handleButton(ButtonInteractionEvent event, Guild guild, String id) {
        String action = id.split(":")[0];

        switch (action) {
            case "rsnadmin_poll_all" -> doPollAllPrompt(event, guild);
            case "rsnadmin_guildchart" -> doGuildChartPrompt(event, guild);
            case "rsnadmin_syncclan" -> doSyncClanPrompt(event);
            case "rsnadmin_lookup" -> doPlayerLookupPrompt(event);
            case "rsnadmin_manualverify" -> doManualVerifyPrompt(event);
            case "rsnadmin_clanlist" -> doClanList(event, guild);
            case "rsnadmin_clanoverview" -> doClanOverviewPrompt(event, guild);
            case "rsnadmin_review_pending" -> showPendingVerifications(event, guild, 0, false);
            case "rsnadmin_guests" -> showUnverifiedGuests(event, guild, 0, false);
            case "rsnadmin_guests_page" -> showUnverifiedGuests(event, guild, Integer.parseInt(id.split(":")[1]), true);
            case "rsnadmin_citadel" -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                showCitadelViewer(event, guild, WeeklyDigestService.citadelWeekStart(today), today);
            }
            case "rsnadmin_citadel_view" -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                LocalDate thisWeek = WeeklyDigestService.citadelWeekStart(today);
                if (id.endsWith(":last")) showCitadelViewer(event, guild, thisWeek.minusWeeks(1), thisWeek.minusDays(1));
                else showCitadelViewer(event, guild, thisWeek, today);
            }
            case "rsnadmin_citadel_range" -> doCitadelRangePrompt(event);
            case "rsnadmin_coffer" -> event.editComponents(List.of(CofferHubPanel.build("rsnadmin_back:_"))).useComponentsV2(true).queue();
            case "rsnadmin_back" -> event.editComponents(List.of(buildPanel(guild, event.getMember()))).useComponentsV2(true).queue();

            case "rsnadmin_poll_all_confirm" -> doPollAll(event, guild);
            case "rsnadmin_guildchart_confirm" -> doGuildChart(event, guild);
            case "rsnadmin_syncclan_confirm" -> doSyncClan(event, guild);
            case "rsnadmin_clanoverview_confirm" -> doClanOverview(event, guild);
            case "rsnadmin_bulk_cancel" -> Containers.edit(event, Containers.INFO, Text.of(event, "runescape.cancelled_nothing_was_run"));

            case "rsnadmin_poll" -> doPollOne(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_chart" -> doXpChart(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_recap" -> doMonthlyRecap(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_nearly" -> doNearlyThere(event, guild, id.split(":", 2)[1]);
            case "rsnadmin_unlink" -> doUnlinkPrompt(event, id.split(":", 2)[1]);
            case "rsnadmin_renamerequest" -> doRenamePrompt(event, id.split(":", 2)[1]);
            case "rsnadmin_joindate" -> doSetJoinDatePrompt(event, id.split(":", 2)[1]);

            case "rsnadmin_pending_page" -> showPendingVerifications(event, guild, Integer.parseInt(id.split(":")[1]), true);
            case "rsnadmin_review_approve" -> doReviewRowAction(event, guild, id.split(":", 2)[1], true);
            case "rsnadmin_review_reject" -> doReviewRowAction(event, guild, id.split(":", 2)[1], false);
            case "rsnadmin_post_pending" -> doPostPendingToReviewChannel(event, guild);
            case "rsnadmin_open_configure" -> doOpenConfigure(event, guild);
            case "rsnadmin_review_approve_all" -> doApproveAllPrompt(event, guild);
            case "rsnadmin_review_approve_all_confirm" -> doApproveAllConfirmed(event, guild);

            case "rsnadmin_clanlist_page" -> {
                int page = Integer.parseInt(id.split(":")[1]);
                event.editComponents(List.of(buildClanListContainer(guild, page))).useComponentsV2(true)
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
            }

            case "rsnadmin_unlink_confirm" -> {
                String rsn = id.split(":", 2)[1];
                PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);
                boolean unlinked = link != null && linkService.removeFromServer(guild.getIdLong(), link.linkId());
                Containers.edit(event, unlinked ? Containers.SUCCESS : Containers.WARNING,
                        unlinked ? Text.of(event, "runescape.removed_from_this_server", rsn) : Text.of(event, "runescape.couldn_t_unlink_that_link_may"));
            }

            case "rsnadmin_unlink_cancel" -> Containers.edit(event, Containers.INFO, Text.of(event, "runescape.cancelled_nothing_was_unlinked"));
        }
    }

    // --- Bulk actions ---

    /**
     * Every bulk/heavy action (anything that loops over more than one player) confirms first —
     * colored red since these are the actions actually worth being careful about on a limited
     * database plan, distinct from a single-target action's own confirm (e.g. Unlink, which is red
     * for irreversibility, not database load). {@code confirmId} is whatever this specific action's
     * own "go" button should be; Cancel is always the same shared button.
     */
    private void doBulkConfirmPrompt(ComponentInteraction event, String title, String message, String confirmId) {
        Container confirm = Containers.card(Containers.DANGER,
                TextDisplay.of("### " + title),
                TextDisplay.of(message + "\n\n-# This can take a while and will access the database multiple times — make sure you mean to run this now."),
                ActionRow.of(
                        Button.danger(confirmId, "Yes, Continue"),
                        Button.secondary("rsnadmin_bulk_cancel:_", "Cancel")));
        event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doPollAllPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getAllLinks(guild.getIdLong()).size();
        doBulkConfirmPrompt(event, "Update All Linked Players?",
                "This updates **every linked player's** RuneScape profile individually (" + count + " total) and writes " +
                "a database update for each one that's changed — up to " + count + " external requests and database writes.",
                "rsnadmin_poll_all_confirm:_");
    }

    private void doSyncClanPrompt(ComponentInteraction event) {
        doBulkConfirmPrompt(event, "Sync Clan Roster?",
                "This fetches the entire clan roster, then updates **every listed member** individually (with a short delay " +
                "between each) and writes updates to the database for each one. For a clan this size, this can take a " +
                "couple of minutes and will touch the database dozens of times.",
                "rsnadmin_syncclan_confirm:_");
    }

    private void doGuildChartPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getAllLinks(guild.getIdLong()).size();
        doBulkConfirmPrompt(event, "Build the Clan XP Graph?",
                "This reads recent update history from the database for **every linked player** (" + count + " total) to build the chart.",
                "rsnadmin_guildchart_confirm:_");
    }

    private void doClanOverviewPrompt(ComponentInteraction event, Guild guild) {
        int count = clanSyncService.getRoster(guild.getIdLong(), true).size();
        doBulkConfirmPrompt(event, "Build the Clan Overview?",
                "This reads snapshots, skills, and activity history from the database for **every active clan member** " +
                "(" + count + " total) to build the overview — potentially hundreds of database reads.",
                "rsnadmin_clanoverview_confirm:_");
    }

    private void doPollAll(ComponentInteraction event, Guild guild) {
        // The bot polls nobody: the hub keeps every tracked player current on its own cycle, so "update all" just redraws the panel.
        event.deferEdit().queue();
        event.getHook().editOriginalComponents(List.of(buildPanel(guild, event.getMember()))).useComponentsV2(true).queue();
    }

    private void doGuildChart(ComponentInteraction event, Guild guild) {
        // Deferred — one history query per linked player, plus chart rendering, adds up.
        event.deferReply(true).queue();

        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
        Map<String, List<PlayerLinkRepository.StatsSnapshotRow>> historyByRsn = new LinkedHashMap<>();
        OffsetDateTime since = OffsetDateTime.now().minusDays(GUILD_CHART_HISTORY_DAYS);
        for (PlayerLink link : links) {
            historyByRsn.put(link.rsn(), statsService.getSnapshotsSince(link.rsn(), since));
        }

        FileUpload chart = XpChartRenderer.renderMultiPlayer(historyByRsn);
        if (chart == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Not enough update history yet — need at least 2 updates for at least one linked player " +
                    "in the last " + GUILD_CHART_HISTORY_DAYS + " days."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Clan XP Graph"),
                MediaGallery.of(MediaGalleryItem.fromFile(chart)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private void doSyncClan(ComponentInteraction event, Guild guild) {
        String clanName = clanSyncService.getClanName(guild.getIdLong());
        if (clanName == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.no_clan_name_configured_for_this"));
            return;
        }

        // Deferred — fetches the whole clan roster, then polls every member with a delay between
        // each (paced by the poll coordinator), so this can genuinely take a few minutes for a
        // clan this size. That's expected, not a hang.
        event.deferReply(true).queue();
        var result = clanSyncService.syncAndPoll(guild);

        if (result.rosterSize() == 0) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't fetch the clan roster for **" + clanName + "** — check the clan name and try again."))).useComponentsV2(true).queue();
            return;
        }

        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Synced **" + clanName + "**: " + result.rosterSize() + " member(s) in the roster " +
                "(" + result.newMembers() + " new, " + result.departedMembers() + " no longer listed), " +
                result.polled() + "/" + result.rosterSize() + " updated successfully."))).useComponentsV2(true).queue();
    }

    private void doManualVerifyPrompt(ComponentInteraction event) {
        TextInput discordIdInput = TextInput.create("manual_verify_discord_id", TextInputStyle.SHORT)
                .setPlaceholder("Right-click the member > Copy User ID")
                .setRequired(true)
                .build();
        TextInput rsnInput = TextInput.create("manual_verify_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Exact in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_manualverify_modal:_", "Manually Verify a Player")
                .addComponents(Label.of("Discord User ID", discordIdInput), Label.of("RuneScape Name", rsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doClanList(ComponentInteraction event, Guild guild) {
        event.replyComponents(List.of(buildClanListContainer(guild, 0))).useComponentsV2(true).setEphemeral(true)
                .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
    }

    private void doPlayerLookupPrompt(ComponentInteraction event) {
        TextInput rsnInput = TextInput.create("lookup_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Exact in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_lookup_modal:_", "Look Up a Player")
                .addComponents(Label.of("RuneScape Name", rsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doClanOverview(ComponentInteraction event, Guild guild) {
        // Deferred — this walks every active clan member's snapshots, skills, and activities (all
        // local DB reads, but a few hundred of them for a clan this size), plus rendering a
        // leaderboard image. Comfortably more than 3 seconds worth of work.
        event.deferReply(true).queue();

        var stats = clanOverviewService.getOverview(guild.getIdLong());
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Nothing tracked yet — run **Sync Clan** first."))).useComponentsV2(true).queue();
            return;
        }

        String clanName = clanSyncService.getClanName(guild.getIdLong());
        FileUpload image = ClanOverviewRenderer.render(clanName != null ? clanName : "Clan", stats, fetchGuildIcon(guild));
        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't render the clan overview image right now."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### Clan Overview"),
                MediaGallery.of(MediaGalleryItem.fromFile(image)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    // --- Pending verifications (moved here from /rs — this is the full, server-wide queue) ---

    private static final int PENDING_REVIEW_PAGE_SIZE = 5;

    /**
     * The full queue, with Approve/Reject right on each row — no need to go find that player's
     * original post in the review channel just to act on it. Re-rendered in place (same message)
     * after any row action or page nav, so working through a backlog doesn't lose your spot; a fresh
     * "Review Pending" click posts a new one instead (see {@code isPageNav}).
     */
    private void showPendingVerifications(ComponentInteraction event, Guild guild, int pageIndex, boolean isPageNav) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(guild.getIdLong());
        if (pending.isEmpty()) {
            if (isPageNav) {
                Containers.edit(event, Containers.INFO, Text.of(event, "runescape.no_pending_verification_requests_remaining"));
            } else {
                Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "runescape.no_pending_verification_requests"));
            }
            return;
        }

        var page = Pagination.paginate(pending, pageIndex, PENDING_REVIEW_PAGE_SIZE);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# Pending Verifications (" + pending.size() + ")"));
        boolean adminTier = adminRoleFilter.isAuthorized(guild, event.getMember());
        children.add(adminTier
                ? ActionRow.of(
                        Button.success("rsnadmin_review_approve_all:_", "Approve All"),
                        Button.secondary("rsnadmin_post_pending:_", "Post to Review Channel"))
                : ActionRow.of(Button.secondary("rsnadmin_post_pending:_", "Post to Review Channel")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        for (VerificationAttempt attempt : page.items()) {
            children.add(TextDisplay.of("`#" + attempt.attemptId() + "` — **" + attempt.rsn() + "** — <@" + attempt.discordUserId() + ">"));
            children.add(ActionRow.of(
                    Button.success("rsnadmin_review_approve:" + attempt.attemptId(), "Approve"),
                    Button.danger("rsnadmin_review_reject:" + attempt.attemptId(), "Reject")));
        }

        if (!page.isSinglePage()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(Pagination.navRow(page, "rsnadmin_pending_page:"));
        }

        Container container = Containers.card(Containers.INFO, children);

        if (isPageNav) {
            event.editComponents(List.of(container)).useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).setEphemeral(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        }
    }

    // --- Guests who never linked an RSN ---

    private static final int GUEST_LIST_PAGE_SIZE = 10;

    /**
     * Everyone who holds Guest but never linked an RSN and has no request waiting (see
     * {@link UnverifiedGuestService}) — who was most likely given the role by hand. Visibility only: nothing here
     * removes a role, since handing out Guest to someone who doesn't play RS is allowed. Fetching the role's
     * holders from Discord can take a moment, so the click is acknowledged first.
     */
    private void showUnverifiedGuests(ComponentInteraction event, Guild guild, int pageIndex, boolean isPageNav) {
        if (isPageNav) event.deferEdit().queue();
        else event.deferReply(true).queue();

        unverifiedGuestService.find(guild, result -> {
            if (result.guestRole() == null) {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                        "No Guest role is configured. Set the Onboarding (or verified / unverified) role under **Configure → Verification**."))).useComponentsV2(true).queue();
                return;
            }
            if (result.guests().isEmpty()) {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                        "Everyone holding " + result.guestRole().getAsMention() + " has a linked RSN or a request waiting."))).useComponentsV2(true)
                        .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
                return;
            }

            var page = Pagination.paginate(result.guests(), pageIndex, GUEST_LIST_PAGE_SIZE);
            List<ContainerChildComponent> children = new ArrayList<>();
            children.add(TextDisplay.of("# Guests Without a Link (" + result.guests().size() + ")"));
            children.add(TextDisplay.of("Hold " + result.guestRole().getAsMention() + " but have no linked RSN and no request waiting.\n"
                    + "-# Visibility only, nothing is removed. Bots, admins and clan Members are left out. Use **Manually Verify** to link one."));
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            StringBuilder lines = new StringBuilder();
            for (var guest : page.items()) {
                lines.append("<@").append(guest.userId()).append(">");
                if (guest.joinedServerAt() != null) lines.append(" — joined the server <t:").append(guest.joinedServerAt().toEpochSecond()).append(":R>");
                lines.append('\n');
            }
            children.add(TextDisplay.of(lines.toString().stripTrailing()));
            if (!page.isSinglePage()) {
                children.add(Separator.createDivider(Separator.Spacing.SMALL));
                children.add(Pagination.navRow(page, "rsnadmin_guests_page:"));
            }

            event.getHook().editOriginalComponents(List.of(Containers.card(Containers.INFO, children))).useComponentsV2(true)
                    .setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        }, error -> {
            log.warn("Couldn't list guests without a link for guild {}", guild.getIdLong(), error);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't load the Guest role's members from Discord right now. Try again in a moment."))).useComponentsV2(true).queue();
        });
    }

    /** One row's Approve/Reject from the Review Pending list — shares the exact side effects (link, role sync, DM) that a standalone review-message click gets, then refreshes the list in place. */
    private void doReviewRowAction(ComponentInteraction event, Guild guild, String attemptIdRaw, boolean approve) {
        long attemptId = Long.parseLong(attemptIdRaw);
        VerificationAttempt result = approve
                ? rsInteractionListener.completeApproval(guild, attemptId, event.getUser().getIdLong())
                : rsInteractionListener.completeRejection(guild, attemptId, event.getUser().getIdLong());

        if (result == null) {
            var resolution = linkService.getResolution(attemptId);
            var attempt = linkService.getAttempt(attemptId);
            String how = resolution == null || attempt == null ? ""
                    : " " + VerificationReviewCards.outcome(resolution.status(), resolution.resolvedByUserId(), attempt.discordUserId());
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.that_request_was_already_resolved", how));
            return;
        }
        showPendingVerifications(event, guild, 0, true);
    }

    /** Opens {@code /configure}'s panel as its own message — same panel, same Administrator-permission gate as the command itself. */
    private void doOpenConfigure(ButtonInteractionEvent event, Guild guild) {
        if (!event.getMember().hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.you_need_the_administrator_permission_to"));
            return;
        }
        event.replyComponents(List.of(configureListener.buildMainPanel(guild))).useComponentsV2(true).setEphemeral(true).queue();
    }

    /**
     * Re-posts every still-pending request to the configured Review Channel, each with its own
     * Approve/Reject buttons — for requests submitted before a channel was set, or while it couldn't be
     * reached. Says so plainly if the channel is unset or the bot can't see it, rather than posting nothing.
     */
    private void doPostPendingToReviewChannel(ComponentInteraction event, Guild guild) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(guild.getIdLong());
        if (pending.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "runescape.no_pending_verification_requests_to_post"));
            return;
        }

        event.deferReply(true).queue();
        boolean started = rsInteractionListener.repostPending(guild, pending, (posted, failed) -> {
            String text = failed == 0
                    ? "Posted " + posted + " pending request(s) to the review channel."
                    : "Posted " + posted + " of " + pending.size() + " pending request(s) — " + failed
                      + " failed. Check that the bot can send messages in the review channel.";
            event.getHook().editOriginalComponents(List.of(Containers.toast(failed == 0 ? Containers.SUCCESS : Containers.WARNING, text)))
                    .useComponentsV2(true).queue();
        });
        if (!started) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "There's no usable review channel. Set one under `/configure` → Verification — and if it's a thread, make sure it isn't archived and the bot can see it."))).useComponentsV2(true).queue();
        }
    }

    private void doApproveAllPrompt(ComponentInteraction event, Guild guild) {
        int count = linkService.getPendingAttempts(guild.getIdLong()).size();
        if (count == 0) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "runescape.no_pending_verification_requests"));
            return;
        }

        doBulkConfirmPrompt(event, "Approve All " + count + " Pending Request(s)?",
                "This links every pending RSN to its requester and DMs each of them. " +
                "It can't be undone in bulk — you'd need to unlink each one individually afterward.",
                "rsnadmin_review_approve_all_confirm:_");
    }

    private void doApproveAllConfirmed(ComponentInteraction event, Guild guild) {
        List<VerificationAttempt> pending = linkService.getPendingAttempts(guild.getIdLong());
        if (pending.isEmpty()) {
            Containers.edit(event, Containers.INFO, Text.of(event, "runescape.no_pending_verification_requests_remaining"));
            return;
        }

        // Deferred — approving N people means N role syncs and N DM sends, comfortably longer than
        // Discord's 3-second ack window once the queue has more than a couple of entries.
        event.deferEdit().queue();
        int approved = 0;
        for (VerificationAttempt attempt : pending) {
            try {
                if (rsInteractionListener.completeApproval(guild, attempt.attemptId(), event.getUser().getIdLong()) != null) approved++;
            } catch (PlayerLinkRepository.RsnTakenException taken) {
                log.warn("Skipped approving '{}': registered to another Discord account in another server", attempt.rsn());
            }
        }
        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                "Approved " + approved + " of " + pending.size() + " pending request(s)."))).useComponentsV2(true).queue();
    }

    // --- Per-player actions ---

    /**
     * A fresh look at one player for the lookup card: the hub's Refresh on behalf of the admin
     * who pressed plus the hub's newest snapshot (the refresh itself lands within a minute; press Update again to see it).
     */
    /** What a lookup found, and what the hub said about refreshing it, for a toast under the card; null when nothing needs saying. */
    private record Lookup(ProfileResult result, String note) {}

    private Lookup lookup(String rsn, long pressedBy) {
        com.runeclans.bot.runescape.stats.HubRefresh.Result viaHub = statsService.refreshViaHub(rsn, pressedBy);
        return new Lookup(statsService.latestAsResult(rsn), com.runeclans.bot.runescape.stats.HubRefresh.describe(viaHub, rsn));
    }

    private static void noteAfter(net.dv8tion.jda.api.interactions.InteractionHook hook, Lookup lookup) {
        if (lookup.note() == null) return;
        hook.sendMessageComponents(List.of(Containers.toast(Containers.INFO, lookup.note()))).useComponentsV2(true).setEphemeral(true).queue();
    }

    /** Reached from a Player Lookup card's "Update" button — re-renders that same card, not the admin panel (which no longer shows any one player inline anyway). */
    private void doPollOne(ComponentInteraction event, Guild guild, String rsn) {
        event.deferEdit().queue();
        Lookup lookup = lookup(rsn, event.getUser().getIdLong());
        event.getHook().editOriginalComponents(List.of(buildLookupCard(guild, rsn, lookup.result())))
                .useComponentsV2(true).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class)).queue();
        noteAfter(event.getHook(), lookup);
    }

    private void doXpChart(ComponentInteraction event, Guild guild, String rsn) {
        if (statsService.getLatestSnapshot(rsn) == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.no_synced_data_for_yet_use", rsn));
            return;
        }
        event.replyComponents(List.of(chartListener.buildChartContainer(guild, rsn, Set.of(), RsChartInteractionListener.ChartStyle.LINE)))
                .useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doMonthlyRecap(ComponentInteraction event, Guild guild, String rsn) {
        // Deferred — building the donut chart plus a network fetch for the guild icon is
        // comfortably more than the 3-second ack window allows for.
        event.deferReply(true).queue();

        MonthlyRecapStats stats = monthlyRecapService.getStats(rsn);
        if (stats == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "No snapshots for **" + rsn + "** yet this month — update first."))).useComponentsV2(true).queue();
            return;
        }

        FileUpload image = MonthlyRecapRenderer.render(stats, fetchGuildIcon(guild));
        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.WARNING,
                    "Couldn't render the recap image right now."))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### " + rsn + " — Monthly Recap"),
                MediaGallery.of(MediaGalleryItem.fromFile(image)));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private void doNearlyThere(ComponentInteraction event, Guild guild, String rsn) {
        PlayerLinkRepository.StatsSnapshotRow latest = statsService.getLatestSnapshot(rsn);
        if (latest == null) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    Text.of(event, "runescape.no_synced_data_for_yet_use", rsn));
            return;
        }
        List<SkillValue> skills = statsService.getSkillsForSnapshot(latest.snapshotId());
        event.replyComponents(List.of(buildNearlyThereContainer(rsn, skills, latest))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private void doRenamePrompt(ComponentInteraction event, String rsn) {
        TextInput newRsnInput = TextInput.create("new_rsn", TextInputStyle.SHORT)
                .setPlaceholder("Their new in-game display name")
                .setRequired(true)
                .setRequiredRange(1, 12)
                .build();

        Modal modal = Modal.create("rsnadmin_renamemodal:" + rsn, "Update RSN for " + rsn)
                .addComponents(Label.of("New RuneScape Name", newRsnInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doSetJoinDatePrompt(ComponentInteraction event, String rsn) {
        TextInput dateInput = TextInput.create("clan_joined_at", TextInputStyle.SHORT)
                .setPlaceholder("YYYY-MM-DD")
                .setRequired(true)
                .setRequiredRange(10, 10)
                .build();

        Modal modal = Modal.create("rsnadmin_joindatemodal:" + rsn, "Set Clan Join Date for " + rsn)
                .addComponents(Label.of("Join Date", dateInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void doUnlinkPrompt(ComponentInteraction event, String rsn) {
        Container confirm = Containers.card(Containers.WARNING,
                TextDisplay.of("### Unlink " + rsn + "?"),
                TextDisplay.of("This removes **" + rsn + "** from this server. The account stays with its owner in any other server the bot is in, and they can link it here again. Historical stats are kept."),
                ActionRow.of(
                        Button.danger("rsnadmin_unlink_confirm:" + rsn, "Yes, Unlink"),
                        Button.secondary("rsnadmin_unlink_cancel:_", "Cancel")
                ));
        event.replyComponents(List.of(confirm)).useComponentsV2(true).setEphemeral(true).queue();
    }

    private static final int NEARLY_THERE_COUNT = 10;
    // Wide enough for "Dungeoneering" (13 chars), same reasoning as RsInteractionListener's Skills view.
    private static final int NEARLY_THERE_NAME_WIDTH = 13;

    /**
     * The 10 skills closest to their next level, by XP still needed — {@link RuneScapeXpTable} is
     * the game's own level curve, not an approximation. Kept as admin-only tooling; no longer wired
     * to a member-facing button (see {@code RsInteractionListener}'s profile panel), per request —
     * the logic stays intact here in case it comes back.
     */
    private Container buildNearlyThereContainer(String rsn, List<SkillValue> skills, PlayerLinkRepository.StatsSnapshotRow latest) {
        record Gap(SkillValue skill, long xpNeeded) {}

        List<Gap> gaps = skills.stream()
                .filter(skill -> skill.level() < 120)
                .map(skill -> new Gap(skill, RuneScapeXpTable.xpToNextLevel(skill.skillId(), skill.level(), skill.xp())))
                .sorted(Comparator.comparingLong(Gap::xpNeeded))
                .limit(NEARLY_THERE_COUNT)
                .toList();

        if (gaps.isEmpty()) {
            return Containers.card(Containers.SUCCESS,
                    TextDisplay.of("### " + rsn + " — Nearly There"),
                    TextDisplay.of("Every skill is already level 120!"));
        }

        StringBuilder body = new StringBuilder();
        for (Gap gap : gaps) {
            String row = String.format("%-" + NEARLY_THERE_NAME_WIDTH + "s  Lv %-3d -> %-3d  %11s xp",
                    RuneScapeSkillCatalog.nameFor(gap.skill().skillId()), gap.skill().level(), gap.skill().level() + 1,
                    String.format("%,d", gap.xpNeeded()));
            body.append("`").append(row).append("`\n");
        }

        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("### " + rsn + " — Nearly There"),
                TextDisplay.of(body.toString().stripTrailing()),
                TextDisplay.of("-# As of <t:" + latest.snapshotAt().toEpochSecond() + ":R> — closest " + gaps.size() + " skill(s) to leveling up"));
    }

    private static final int CLAN_LIST_PAGE_SIZE = 15;

    /**
     * Every currently-active clan_member row (i.e. still seen in the last Sync Clan), cross-
     * referenced against player_link so it's obvious at a glance who still needs verifying.
     * Mentions use {@code setAllowedMentions} to suppress the ping.
     */
    private Container buildClanListContainer(Guild guild, int pageIndex) {
        List<ClanMemberRepository.ClanMemberRow> members = clanSyncService.getRoster(guild.getIdLong(), true);
        if (members.isEmpty()) {
            return Containers.card(Containers.PRIMARY,
                    TextDisplay.of("### Clan Member List"),
                    TextDisplay.of("*Nothing tracked yet — run **Sync Clan** first.*"));
        }

        var page = Pagination.paginate(members, pageIndex, CLAN_LIST_PAGE_SIZE);

        StringBuilder sb = new StringBuilder();
        for (ClanMemberRepository.ClanMemberRow row : page.items()) {
            PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), row.rsn());
            sb.append("**").append(row.rsn()).append("** — ").append(row.clanRank());
            sb.append(link != null ? " — ✅ <@" + link.discordUserId() + ">" : " — *unverified*");
            sb.append("\n");
        }

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clan Member List (" + members.size() + " active)"));
        children.add(TextDisplay.of(sb.toString().stripTrailing()));
        if (!page.isSinglePage()) children.add(Pagination.navRow(page, "rsnadmin_clanlist_page:"));

        return Containers.card(Containers.PRIMARY, children);
    }

    /**
     * The "look up anyone" info card: clan-roster membership and verification status always shown,
     * then the live fetch outcome — full stats if it succeeded, or an explicit reason if it didn't
     * (see {@link ProfileResult}). Mentions use {@code setAllowedMentions} for the same reason as
     * {@link #buildClanListContainer}.
     */
    private Container buildLookupCard(Guild guild, String rsn, ProfileResult result) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + rsn + " — Player Lookup"));

        ClanMemberRepository.ClanMemberRow clanRow = clanSyncService.getRoster(guild.getIdLong(), true).stream()
                .filter(member -> member.rsn().equalsIgnoreCase(rsn))
                .findFirst().orElse(null);
        boolean inClan = clanRow != null;
        PlayerLink link = linkService.getLinkForRsn(guild.getIdLong(), rsn);

        StringBuilder meta = new StringBuilder()
                .append(inClan ? "✅ In the clan roster" : "— Not currently in the clan roster")
                .append(link != null ? "\n✅ Verified to <@" + link.discordUserId() + ">" : "\n— Not verified to any Discord account");
        if (inClan) {
            meta.append("\n📅 Clan joined: ").append(clanRow.clanJoinedAt() != null ? clanRow.clanJoinedAt().toString() : "*not recorded yet*");
        }
        children.add(TextDisplay.of(meta.toString()));

        switch (result) {
            case ProfileResult.Found(var profile) -> {
                children.add(TextDisplay.of("**Total Level:** " + profile.totalLevel() + "\n" +
                        "**Combat Level:** " + profile.combatLevel() + "\n" +
                        "**Quests Complete:** " + profile.questsComplete() + "\n" +
                        "**Total XP:** `" + String.format("%,d", profile.totalXp()) + " xp`"));
                children.add(link != null
                        ? ActionRow.of(
                                Button.primary("rsnadmin_poll:" + rsn, "Update"),
                                Button.secondary("rs_skills:" + rsn, "Full Skills"),
                                Button.secondary("rsnadmin_chart:" + rsn, "XP Chart"),
                                Button.secondary("rsnadmin_renamerequest:" + rsn, "Update RSN"),
                                Button.danger("rsnadmin_unlink:" + rsn, "Unlink"))
                        : ActionRow.of(
                                Button.primary("rsnadmin_poll:" + rsn, "Update"),
                                Button.secondary("rs_skills:" + rsn, "Full Skills"),
                                Button.secondary("rsnadmin_chart:" + rsn, "XP Chart"),
                                Button.secondary("rsnadmin_manualverify:_", "Verify This Player")));
                // XP Chart and Monthly Recap are still dev-in-progress (see RsInteractionListener) —
                // Player Lookup is where they stay testable while they're not on the live /rs profile.
                children.add(ActionRow.of(Button.secondary("rsnadmin_recap:" + rsn, "Monthly Recap")));
            }
            case ProfileResult.Private ignored -> children.add(TextDisplay.of(
                    "🔒 This player's **Adventurer's Log is set to private** — RuneScape won't return stats until they make it public in-game (Settings → Privacy)."));
            case ProfileResult.NotFound ignored -> children.add(TextDisplay.of(
                    "❓ No player found on the Jagex hiscores for **" + rsn + "** — check the spelling, or they may not be ranked yet."));
            case ProfileResult.RateLimited ignored -> children.add(TextDisplay.of(
                    "⏳ The Jagex hiscores are rate-limiting us right now — this player's been queued for a slower automatic retry. Try again in a few minutes."));
            case ProfileResult.Unavailable ignored -> children.add(TextDisplay.of(
                    "⚠️ Couldn't fetch stats right now — the RuneScape API may be temporarily unavailable. Try again shortly."));
        }

        // Always shown, independent of inClan/the live-poll outcome above — the modal handler
        // itself already rejects cleanly if rsn turns out not to be a tracked clan member (see
        // handleJoinDateModal), which is friendlier than hiding the button on a possibly-stale
        // roster read and leaving an admin wondering where it went.
        children.add(ActionRow.of(Button.secondary("rsnadmin_joindate:" + rsn, "Set Join Date")));

        return Containers.card(Containers.PRIMARY, children);
    }

    /** The server's own icon, used as the recap image's "clan logo" — {@code null} if the guild has no icon set, or the fetch fails. */
    private byte[] fetchGuildIcon(Guild guild) {
        String iconUrl = guild.getIconUrl();
        if (iconUrl == null) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(iconUrl + "?size=256"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() / 100 == 2 ? response.body() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch guild icon for monthly recap", e);
            return null;
        }
    }

    private static final int GUILD_CHART_HISTORY_DAYS = 30;

    private static final DateTimeFormatter CITADEL_WEEK_OPTION = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    /** Re-renders the message the click came from as the Citadel viewer for {@code from..to} (snapped to whole weeks). Deferred first — a long range is a query per week. */
    private void showCitadelViewer(IMessageEditCallback event, Guild guild, LocalDate from, LocalDate to) {
        event.deferEdit().queue();
        event.getHook().editOriginalComponents(List.of(buildCitadelViewer(guild, from, to))).useComponentsV2(true).queue();
    }

    /** Who capped and visited, with quick picks (this week, last week, any of the last 12 weeks) and a free date range. */
    Container buildCitadelViewer(Guild guild, LocalDate from, LocalDate to) {
        LocalDate thisWeek = WeeklyDigestService.citadelWeekStart(LocalDate.now(ZoneOffset.UTC));

        List<ContainerChildComponent> children = new ArrayList<>(weeklyDigestService.citadelSection(guild.getIdLong(), from, to));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(
                Button.primary("rsnadmin_citadel_view:current", "This Week"),
                Button.secondary("rsnadmin_citadel_view:last", "Last Week"),
                Button.secondary("rsnadmin_citadel_range:_", "Date Range…")));

        StringSelectMenu.Builder weeks = StringSelectMenu.create("rsnadmin_citadel_week:_").setPlaceholder("Pick a specific week");
        for (int i = 0; i < 12; i++) {
            LocalDate week = thisWeek.minusWeeks(i);
            weeks.addOption(week.format(CITADEL_WEEK_OPTION) + " – " + week.plusDays(6).format(CITADEL_WEEK_OPTION) + (i == 0 ? " (current)" : ""), week.toString());
        }
        children.add(ActionRow.of(weeks.build()));
        children.add(TextDisplay.of("-# Dates snap to whole Citadel weeks (Wednesday to Tuesday, resetting at 00:00 UTC). Up to " + WeeklyDigestService.MAX_VIEW_WEEKS + " weeks at a time."));
        children.add(ActionRow.of(Button.primary("rsnadmin_back:_", "Back")));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    private void doCitadelRangePrompt(ButtonInteractionEvent event) {
        TextInput from = TextInput.create("citadel_from", TextInputStyle.SHORT)
                .setPlaceholder("2026-09-23").setRequired(true).setRequiredRange(10, 10).build();
        TextInput to = TextInput.create("citadel_to", TextInputStyle.SHORT)
                .setPlaceholder("2026-10-14 — leave blank for today").setRequired(false).setMaxLength(10).build();

        Modal modal = Modal.create("rsnadmin_citadel_range_modal:_", "Citadel Date Range")
                .addComponents(Label.of("From (YYYY-MM-DD)", from), Label.of("To (YYYY-MM-DD, optional)", to))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleCitadelRangeModal(ModalInteractionEvent event, Guild guild) {
        String rawFrom = event.getValue("citadel_from").getAsString().trim();
        String rawTo = event.getValue("citadel_to").getAsString().trim();
        LocalDate from, to;
        try {
            from = LocalDate.parse(rawFrom);
            to = rawTo.isEmpty() ? LocalDate.now(ZoneOffset.UTC) : LocalDate.parse(rawTo);
        } catch (DateTimeParseException e) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "runescape.couldn_t_read_those_dates_use"));
            return;
        }
        showCitadelViewer(event, guild, from, to);
    }

    /** What the Support tier sees: just the request-review tools it's allowed to use. */
    private Container buildSupportPanel() {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RSN Requests"));
        children.add(TextDisplay.of("-# Support — review and verify RuneScape name requests"));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(
                Button.secondary("rsnadmin_review_pending:_", "Review Pending"),
                Button.secondary("rsnadmin_manualverify:_", "Manually Verify"),
                Button.secondary("rsnadmin_post_pending:_", "Post Pending to Review Channel")));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    /**
     * {@link RsAdminCommand}'s initial reply and this listener's own re-renders (Update All) — bulk
     * actions only now, no self section (see {@code /rs}). Grouped into labeled sections rather than
     * one undifferentiated button wall, since this panel is meant to grow well beyond RS3 tracking
     * over time (Coffer, Events, whatever comes next each get their own section here rather than
     * their own top-level admin command).
     */
    Container buildPanel(Guild guild, Member viewer) {
        if (!adminRoleFilter.isAuthorized(guild, viewer)) return buildSupportPanel();

        String clanName = clanSyncService.getClanName(guild.getIdLong());
        boolean hasClan = clanName != null;

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RS3 Admin Panel" + (hasClan ? " - " + clanName : "")));
        children.add(TextDisplay.of(hasClan
                ? "-# Administrator only — clan data updates automatically in the background; use these to trigger one right now"
                : "-# No clan is set up for this server, so the clan tools are hidden. Set one under **Bot Management → Configure → Clan Setup**."));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (hasClan) {
            children.add(TextDisplay.of("### Clan Management"));
            children.add(ActionRow.of(
                    Button.secondary("rsnadmin_syncclan:_", "Sync Clan"),
                    Button.secondary("rsnadmin_guildchart:_", "Clan XP Graph"),
                    Button.secondary("rsnadmin_clanlist:_", "Clan Member List"),
                    Button.secondary("rsnadmin_clanoverview:_", "Clan Overview"),
                    Button.secondary("rsnadmin_citadel:_", "Citadel Viewer")
            ));
            children.add(ActionRow.of(Button.secondary("rsnadmin_coffer:_", "Clan Coffer"), Button.secondary("namechg_list:_", "Name Changes")));
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
        }

        children.add(TextDisplay.of("### Player Management"));
        children.add(ActionRow.of(
                Button.primary("rsnadmin_poll_all:_", "Update All"),
                Button.secondary("rsnadmin_lookup:_", "Player Lookup"),
                Button.secondary("rsnadmin_manualverify:_", "Manually Verify"),
                Button.secondary("rsnadmin_review_pending:_", "Review Pending"),
                Button.secondary("rsnadmin_post_pending:_", "Post Pending to Review Channel")
        ));
        children.add(ActionRow.of(Button.secondary("rsnadmin_guests:_", "Guests Without a Link")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (hasClan) {
            children.add(TextDisplay.of("### Points & Promotions"));
            children.add(ActionRow.of(Button.secondary("clanpoints_open:_", "Configure Points & Ranks")));
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
        }

        children.add(TextDisplay.of("### Bot Management"));
        children.add(ActionRow.of(Button.secondary("rsnadmin_open_configure:_", "Configure")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        children.add(TextDisplay.of("### Server Moderation"));
        children.add(ActionRow.of(Button.danger("rsadmin_prune_open:_", "Prune Messages")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("-# Manage your own linked account(s) via `/rs`."));
        children.add(Containers.autoCloseNote());

        return Containers.card(Containers.PRIMARY, children);
    }
}
