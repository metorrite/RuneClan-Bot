package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.HubNameChanges;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Name changes in Discord (T-1160): the admin panel's <b>Name Changes</b> button lists the RuneClans hub's name changes for the server's clan,
 * the ones waiting for an admin (Link / Reject) and the recent links that can still be undone (Undo). Every press calls the hub's routes, the
 * same ones the website's Site Admin uses, as {@code bot:<discord user id>}; the hub then tells the bot (and the website) through its feed.
 * Only Support-tier staff of the server may press them.
 * <p>
 * The old "Possible RSN Rename" cards the bot posted before the hub took over still carry Confirm / Reject buttons; a press on one now points
 * to Name Changes, since the bot no longer decides renames itself.
 */
@BService
public class RsnRenameInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnRenameInteractionListener.class);
    static final String LIST = "namechg_list";
    static final String LINK = "namechg_link";
    static final String REJECT = "namechg_reject";
    static final String UNDO = "namechg_undo";
    /** Changes shown at once (each is a text and a row of buttons; a message holds at most 40 components). */
    static final int SHOWN = 8;

    private final HubNameChanges nameChanges;
    private final AdminRoleFilter adminRoleFilter;
    private final ScheduledExecutorService lane = BotScheduler.lane("name-change-buttons");

    public RsnRenameInteractionListener(HubNameChanges nameChanges, AdminRoleFilter adminRoleFilter) {
        this.nameChanges = nameChanges;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("rsnrename_confirm:") || id.startsWith("rsnrename_reject:")) {
            Containers.replyEphemeral(event, Containers.INFO, "Name changes are decided on RuneClans now. Open **Name Changes** in the admin "
                    + "panel (`/rsadmin`) or the clan website's Site Admin to link or reject it.");
            return;
        }
        String action = id.split(":", 2)[0];
        if (!List.of(LIST, LINK, REJECT, UNDO).contains(action)) return;

        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null || !adminRoleFilter.isSupportTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, "Only staff can do this.");
            return;
        }
        long guildId = guild.getIdLong();
        long actor = event.getUser().getIdLong();
        try {
            if (action.equals(LIST)) {
                event.deferReply(true).queue(hook -> lane.execute(() -> show(hook, guildId, null)));
                return;
            }
            long changeId = Long.parseLong(id.split(":", 2)[1]);
            event.deferEdit().queue(hook -> lane.execute(() -> {
                HubNameChanges.Result<HubNameChanges.Change> done = switch (action) {
                    case LINK -> nameChanges.decide(guildId, changeId, "link", actor);
                    case REJECT -> nameChanges.decide(guildId, changeId, "reject", actor);
                    default -> nameChanges.undo(guildId, changeId, actor);
                };
                show(hook, guildId, done.ok() ? outcome(action, done.value()) : "⚠️ " + done.problem());
            }));
        } catch (RuntimeException e) {
            log.error("Unhandled exception in name change button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void show(InteractionHook hook, long guildId, String note) {
        Container card;
        try {
            card = card(nameChanges.changes(guildId, "open"), nameChanges.changes(guildId, "linked"), note);
        } catch (RuntimeException e) {
            log.warn("Reading the hub's name changes failed: {}", e.toString());
            card = Containers.card(Containers.WARNING, TextDisplay.of("RuneClans didn't answer. Try again in a minute."));
        }
        hook.editOriginalComponents(List.of(card)).useComponentsV2(true).setAllowedMentions(EnumSet.noneOf(Message.MentionType.class))
                .queue(ok -> { }, error -> log.debug("Couldn't show the name changes: {}", error.toString()));
    }

    static String outcome(String action, HubNameChanges.Change c) {
        return switch (action) {
            case LINK -> "✅ Linked: **" + c.from() + "** is now **" + c.to() + "**. Everything follows the new name.";
            case REJECT -> "Rejected: **" + c.from() + "** and **" + c.to() + "** stay two players.";
            default -> "↩️ Undone: **" + c.to() + "** is **" + c.from() + "** again.";
        };
    }

    /** The list: waiting changes with Link / Reject, then links that can still be undone with Undo. */
    static Container card(HubNameChanges.Result<List<HubNameChanges.Change>> open, HubNameChanges.Result<List<HubNameChanges.Change>> linked, String note) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Name Changes"));
        if (note != null) children.add(TextDisplay.of(note));
        if (!open.ok()) {
            children.add(TextDisplay.of(open.problem()));
            return Containers.card(Containers.WARNING, children);
        }
        int shown = 0;
        children.add(TextDisplay.of("**Waiting for an admin**"));
        if (open.value().isEmpty()) children.add(TextDisplay.of("-# Nothing waiting. RuneClans links the sure ones by itself."));
        for (HubNameChanges.Change c : open.value()) {
            if (shown++ >= SHOWN) break;
            children.add(TextDisplay.of("**" + c.from() + "** → **" + c.to() + "** (" + c.confidence() + ")\n-# " + c.basis()));
            children.add(ActionRow.of(Button.success(LINK + ":" + c.id(), "Link"), Button.danger(REJECT + ":" + c.id(), "Reject")));
        }
        List<HubNameChanges.Change> undoable = linked.ok() ? linked.value().stream().filter(HubNameChanges.Change::canUndo).toList() : List.of();
        if (!undoable.isEmpty() && shown < SHOWN) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(TextDisplay.of("**Linked (can be undone for 30 days)**"));
            for (HubNameChanges.Change c : undoable) {
                if (shown++ >= SHOWN) break;
                children.add(TextDisplay.of("**" + c.from() + "** → **" + c.to() + "**"));
                children.add(ActionRow.of(Button.secondary(UNDO + ":" + c.id(), "Undo")));
            }
        }
        children.add(TextDisplay.of("-# The same list is on the clan website under Site Admin."));
        return Containers.card(Containers.INFO, children);
    }
}
