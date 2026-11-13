package com.runeclans.bot.events;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventRepository.Team;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventScoring.Standing;
import com.runeclans.bot.hubevents.HubEventMap;
import com.runeclans.bot.hubevents.HubEvents;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a bingo's live board in Discord: one message in the event's board channel with the board as a picture and a short leaderboard, edited
 * whenever progress changes (after a short pause, so a burst of approvals makes one update). The picture is the hub's ({@code board.png}, T-934):
 * the bot posts what the website shows, and draws nothing itself. Also tells a player when their proof is approved or denied. Nothing here changes
 * an event; it only reads it and posts.
 */
@BService
public class EventBoardPanels {
    private static final Logger log = LoggerFactory.getLogger(EventBoardPanels.class);
    private static final long DELAY_SECONDS = 4;

    private final HubEvents events;
    private final HubEventMap map;
    private final EventSpaceRepository space;
    private final ScheduledExecutorService executor = BotScheduler.lane("event-board-panels");
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    /** Boards held back by their event's update rules (T-650): since when, per event. Lost on a restart; the next approval holds it again. */
    private final Map<Long, Held> held = new ConcurrentHashMap<>();
    /** When each board was last redrawn after a change, for "at most once every N minutes". */
    private final Map<Long, Instant> lastDrawn = new ConcurrentHashMap<>();

    private record Held(long guildId, Instant since) {}
    private volatile JDA jda;

    public EventBoardPanels(HubEvents events, EventSpaceRepository space, EventChanges changes) {
        this.events = events;
        this.map = events.map();
        this.space = space;
        changes.subscribe(this::onChange);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> flushHeld(Instant.now()), 60, 60, TimeUnit.SECONDS);
        log.info("Event boards are ready: they will be posted to Discord when a bingo starts.");
    }

    private void onChange(EventChanges.Change change) {
        if (change.kind() == EventChanges.Kind.CLAIM_DECIDED) {
            executor.execute(() -> tellPlayer(change));
        }
        if (change.kind() == EventChanges.Kind.STAGE && "deleted".equals(change.note())) {
            // the event is gone from the hub (the map forgets it right after this): its board comes down now, while the message is still known
            map.event(change.eventId()).ifPresent(row -> removeMessage(row.panelChannelId(), row.panelMessageId()));
            return;
        }
        // only progress (approvals, tracked progress) may be held back by the event's update rules (T-650); a start, a finish or a settings change
        // always redraws the board straight away
        boolean holdable = change.kind() == EventChanges.Kind.PROGRESS || change.kind() == EventChanges.Kind.CLAIM_DECIDED;
        schedule(change.guildId(), change.eventId(), holdable);
    }

    private void schedule(long guildId, long eventId, boolean holdable) {
        if (jda == null) return;
        ScheduledFuture<?> old = pending.put(eventId, executor.schedule(() -> {
            pending.remove(eventId);
            afterPause(guildId, eventId, holdable, Instant.now());
        }, DELAY_SECONDS, TimeUnit.SECONDS));
        if (old != null) old.cancel(false);
    }

    /**
     * What happens once the short pause after a change is over: the board is redrawn now, or (progress on an event whose update rules batch the board or
     * wait for the daily reset, T-650) it is held back until {@link #flushHeld} finds it due. An event that never saved update rules is always redrawn
     * now, as before.
     */
    void afterPause(long guildId, long eventId, boolean holdable, Instant now) {
        if (AutoPostRouter.diverting()) return; // board posts are tracked and edited later: a bot that is not live makes none
        if (holdable) {
            BoardUpdates.Settings rules = rulesOf(guildId, eventId);
            if (rules.mode() != BoardUpdates.Mode.LIVE) {
                Held before = held.putIfAbsent(eventId, new Held(guildId, now));
                if (before == null) log.info("Board of event {}: held back until {} ({} updates)", eventId, BoardUpdates.dueAt(rules, now, lastDrawn.get(eventId)), rules.mode());
                return;
            }
        }
        redraw(guildId, eventId, now);
    }

    private void redraw(long guildId, long eventId, Instant now) {
        held.remove(eventId);
        lastDrawn.put(eventId, now);
        try {
            // always logged, so a board that didn't appear can be explained from the logs (the reason is also what the dashboard's button shows)
            log.info("Board of event {}: {}", eventId, refresh(guildId, eventId));
        } catch (RuntimeException e) {
            log.warn("Could not refresh the board of event {}", eventId, e);
        }
    }

    /** Redraws every held-back board whose time has come (T-650). Runs every minute; on the lane only (or in a test). */
    void flushHeld(Instant now) {
        if (AutoPostRouter.diverting()) return;
        for (Map.Entry<Long, Held> entry : List.copyOf(held.entrySet())) {
            long eventId = entry.getKey();
            Held h = entry.getValue();
            try {
                BoardUpdates.Settings rules = rulesOf(h.guildId(), eventId);
                if (BoardUpdates.due(rules, h.since(), lastDrawn.get(eventId), now)) redraw(h.guildId(), eventId, now);
            } catch (RuntimeException e) {
                log.warn("Could not check the held-back board of event {}", eventId, e);
            }
        }
    }

    /** When a board held back by its update rules will next be redrawn; null when nothing is waiting (or the board updates after every approval). */
    public Instant nextUpdate(long eventId, BoardUpdates.Settings rules) {
        Held h = held.get(eventId);
        if (h == null || rules.mode() == BoardUpdates.Mode.LIVE) return null;
        return BoardUpdates.dueAt(rules, h.since(), lastDrawn.get(eventId));
    }

    private BoardUpdates.Settings rulesOf(long guildId, long eventId) {
        try {
            return BoardUpdates.of(DataObject.fromJson(events.event(guildId, eventId).config()));
        } catch (RuntimeException e) {
            return BoardUpdates.DEFAULTS;
        }
    }

    private static boolean shown(Status status) {
        return status == Status.RUNNING || status == Status.SCORING || status == Status.DONE;
    }

    /**
     * Posts or updates the event's boards now: the one everybody shares and/or each team's or player's own, as the event's board display says
     * ({@link EventBoards.Display}). Returns a sentence on what happened, for the dashboard's "refresh" button.
     */
    public String refresh(long guildId, long eventId) {
        JDA client = jda;
        if (client == null) return "The bot isn't connected to Discord yet.";
        Guild guild = Shards.guild(client, guildId);
        Event event;
        try {
            event = guild == null ? null : events.event(guildId, eventId);
        } catch (EventProblem e) {
            event = null;
        }
        if (guild == null || event == null) return "That event or server wasn't found.";
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) return "Only bingo events have a board.";
        if (!shown(event.status())) return "The board is posted once the event starts.";
        EventBoards.Display display = EventBoards.displayOf(DataObject.fromJson(event.config()));
        List<String> parts = new ArrayList<>();
        if (display.shared()) parts.add(refreshShared(guild, event));
        if (display.own()) parts.add(refreshOwn(guild, event));
        return String.join(" ", parts);
    }

    /**
     * Each team's (or player's) own board, in their own channel when the event made one, else in the live board channel. A board is fetched and sent only
     * when its tiles changed since it was last posted, so a busy event doesn't redraw every card for every approval.
     */
    private String refreshOwn(Guild guild, Event event) {
        long eventId = event.id();
        try {
            List<String> keys = BingoBoardView.boardKeys(DataObject.fromJson(event.config()));
            List<ObjectiveSpec> objectives = events.objectives(eventId);
            List<Standing> standings = events.standings(guild.getIdLong(), eventId);
            boolean byTeam = event.scoringMode() == EventRules.ScoringMode.TEAM;
            List<Team> teams = events.teams(eventId);
            List<Participant> players = events.participants(eventId).stream().filter(p -> !p.waiting()).toList();
            Map<String, Long> ownChannel = new HashMap<>();
            for (EventSpaceRepository.ChannelRow row : space.channels(eventId)) ownChannel.put(row.key(), row.channelId());

            // whose board each subject's picture is asked for: the hub draws a member's own board (their team's, in a team event)
            Map<String, Long> viewerOf = new HashMap<>();
            List<EventBoards.Subject> subjects = new ArrayList<>();
            if (byTeam) {
                for (Team team : teams) {
                    Participant member = players.stream().filter(p -> p.teamId() != null && p.teamId() == team.id() && p.userId() != 0).findFirst().orElse(null);
                    if (member == null) continue;
                    subjects.add(new EventBoards.Subject("t:" + team.id(), team.name(), ownChannel.get(EventSpace.teamKey(team.id()))));
                    viewerOf.put("t:" + team.id(), member.userId());
                }
            } else {
                for (Participant player : players) {
                    if (player.userId() == 0) continue;
                    Member member = guild.getMemberById(player.userId());
                    subjects.add(new EventBoards.Subject("u:" + player.userId(), member == null ? "Player" : member.getEffectiveName(), ownChannel.get(EventSpace.playerKey(player.userId()))));
                    viewerOf.put("u:" + player.userId(), player.userId());
                }
            }
            if (subjects.isEmpty()) return "No own boards yet: nobody is " + (byTeam ? "on a team." : "in the event.");

            Map<String, Standing> standingOf = new HashMap<>();
            for (Standing s : standings) standingOf.put(s.subject(), s);
            Map<String, String> hashes = new HashMap<>();
            for (EventBoards.Subject s : subjects) hashes.put(s.key(), hashOf(keys, standingOf.get(s.key())));
            Map<String, EventBoards.Posted> posted = new HashMap<>();
            map.boardPanels(eventId).forEach((subject, panel) -> posted.put(subject, new EventBoards.Posted(panel.channelId(), panel.messageId(), panel.hash())));
            List<EventBoards.Step> steps = EventBoards.plan(subjects, EventSpaces.boardChannelOf(event, space), posted, hashes);
            int sent = 0, edited = 0, same = 0, nowhere = 0;
            for (EventBoards.Step step : steps) {
                switch (step.what()) {
                    case UNCHANGED -> same++;
                    case NO_PLACE -> nowhere++;
                    case POST, EDIT -> {
                        if (sendOwn(guild, event, keys, objectives, step, standingOf.get(step.subject().key()), viewerOf.get(step.subject().key()))) {
                            if (step.what() == EventBoards.What.POST) sent++;
                            else edited++;
                        } else {
                            nowhere++;
                        }
                    }
                }
            }
            return "Own boards: " + sent + " posted, " + edited + " updated, " + same + " unchanged" + (nowhere > 0 ? ", " + nowhere + " with nowhere to post (set up the event's channels, or choose a live board channel)." : ".");
        } catch (RuntimeException e) {
            log.warn("Could not refresh the own boards of event {}", eventId, e);
            return "The own boards couldn't be built: " + e.getMessage();
        }
    }

    /** A fingerprint of which tiles a subject has done, so an unchanged board isn't fetched and sent again. */
    static String hashOf(List<String> keys, Standing standing) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) text.append(key).append('=').append(standing == null ? 0 : standing.completions().getOrDefault(key, 0)).append(';');
        return Integer.toHexString(text.toString().hashCode());
    }

    /** Fetches one subject's board from the hub and posts it (or edits the message already there). False when the channel can't be used. */
    private boolean sendOwn(Guild guild, Event event, List<String> keys, List<ObjectiveSpec> objectives, EventBoards.Step step, Standing standing, long viewer) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, step.channelId());
        if (channel == null || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES)) return false;
        String subject = step.subject().key();
        long done = keys.stream().filter(k -> !BingoBoardOptions.FREE_KEY.equals(k) && standing != null && standing.completions().getOrDefault(k, 0) > 0).count();
        long of = keys.stream().filter(k -> !BingoBoardOptions.FREE_KEY.equals(k)).count();
        String text = "### " + event.name() + " · " + step.subject().name() + "\n" + done + " of " + of + " tiles done · " + stageText(event.status());
        byte[] png = picture(guild.getIdLong(), event.id(), viewer, true);
        if (png == null) text = withTiles(text, keys, objectives, k -> standing != null && standing.completions().getOrDefault(k, 0) > 0, k -> null);
        long eventId = event.id();
        long channelId = channel.getIdLong();
        String hash = step.hash();
        String message = text;
        byte[] picture = png;
        if (step.what() == EventBoards.What.EDIT) {
            HubEventMap.BoardPanel existing = map.boardPanels(eventId).get(subject);
            if (existing != null && existing.channelId() == channelId) {
                var edit = channel.editMessageById(existing.messageId(), message);
                edit = picture == null ? edit.setAttachments(java.util.Collections.emptyList()) : edit.setAttachments(FileUpload.fromData(picture, "bingo-board.png"));
                edit.queue(ok -> map.setBoardPanel(eventId, subject, channelId, existing.messageId(), hash), error -> {
                    log.info("The board message of {} in event {} couldn't be edited ({}); posting a new one", subject, eventId, error.getMessage());
                    postOwn(channel, eventId, subject, message, picture, hash);
                });
                return true;
            }
        }
        postOwn(channel, eventId, subject, message, picture, hash);
        return true;
    }

    private void postOwn(GuildMessageChannel channel, long eventId, String subject, String text, byte[] picture, String hash) {
        var send = channel.sendMessage(text).setAllowedMentions(List.of());
        if (picture != null) send = send.addFiles(FileUpload.fromData(picture, "bingo-board.png"));
        send.queue(message -> map.setBoardPanel(eventId, subject, channel.getIdLong(), message.getIdLong(), hash),
                error -> log.warn("Could not post the board of {} in event {}", subject, eventId, error));
    }

    /** The board picture from the hub, or null when it can't give one right now (it is down, or its fonts failed): the board is then posted as text. */
    private byte[] picture(long guildId, long eventId, long viewer, boolean mine) {
        try {
            byte[] png = events.boardPng(guildId, eventId, viewer, mine);
            return png == null || png.length == 0 ? null : png;
        } catch (RuntimeException e) {
            log.warn("The hub couldn't give the board picture of event {}: {}", eventId, e.getMessage());
            return null;
        }
    }

    /** The one live board everybody shares: how many teams or players have each tile. */
    private String refreshShared(Guild guild, Event event) {
        long guildId = guild.getIdLong();
        long eventId = event.id();
        Long boardChannelId = EventSpaces.boardChannelOf(event, space);
        if (boardChannelId == null) return "Shared board: there is no live board channel yet. Choose or make one on the Channels tab.";
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, boardChannelId);
        if (channel == null) return "The board channel isn't a channel the bot can post in (it may have been deleted, or it is a forum or category).";
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES)) {
            return "The bot needs to see, send messages and attach files in #" + channel.getName() + ".";
        }

        String text;
        List<String> keys;
        List<ObjectiveSpec> objectives;
        List<Standing> standings;
        int subjects;
        try {
            keys = BingoBoardView.boardKeys(DataObject.fromJson(event.config()));
            objectives = events.objectives(eventId);
            standings = events.standings(guildId, eventId);
            subjects = subjectCount(event, standings);
            text = panelText(guild, event, standings) + xpTilesNote(event, objectives);
        } catch (RuntimeException e) {
            log.warn("Could not build the board of event {}", eventId, e);
            return "The board couldn't be built: " + e.getMessage();
        }
        byte[] png = picture(guildId, eventId, 0, false);
        if (png == null) {
            // the hub draws the picture; while it can't, the board is posted as text so it still works
            List<Standing> all = standings;
            int of = subjects;
            text = withTiles(text, keys, objectives, k -> false, k -> (int) all.stream().filter(s -> s.completions().getOrDefault(k, 0) > 0).count() + "/" + of);
        }

        FileUpload file = png == null ? null : FileUpload.fromData(png, "bingo-board.png");
        String message = text;
        byte[] picture = png;
        String how = png == null ? " (as text: the picture couldn't be drawn right now)" : "";
        HubEventMap.Row row = map.event(eventId).orElse(null);
        if (row != null && row.panelChannelId() != null && row.panelMessageId() != null && row.panelChannelId() == channel.getIdLong()) {
            var edit = channel.editMessageById(row.panelMessageId(), message);
            edit = file == null ? edit.setAttachments(java.util.Collections.emptyList()) : edit.setAttachments(file);
            edit.queue(
                    ok -> { },
                    error -> {
                        log.info("The board message of event {} couldn't be edited ({}); posting a new one", eventId, error.getMessage());
                        post(channel, eventId, message, picture == null ? null : FileUpload.fromData(picture, "bingo-board.png"));
                    });
            return "Updated the board in #" + channel.getName() + "." + how;
        }
        post(channel, eventId, message, file);
        return "Posted the board in #" + channel.getName() + "." + how;
    }

    /** Under a bingo whose XP tiles are counted from RuneSync (T-543, {@code EVENT_XP_TILES_ENABLED}), the same note as the website's event pages (T-707). */
    static String xpTilesNote(Event event, List<ObjectiveSpec> objectives) {
        if (!PluginObjectiveMatcher.xpTilesOffered() || PluginObjectiveMatcher.shownXpTiles(ClanVsClan.game(event), event.status(), objectives).isEmpty()) return "";
        return "\n-# XP tiles count RuneSync XP only: players without RuneSync (Jagex hiscores only) make no progress on them.";
    }

    /**
     * The panel text with the tiles listed under it, for when the picture can't be had: each tile's number and label, ticked when {@code done} says so,
     * with {@code count}'s "n/of" after it when it gives one. Kept inside Discord's message length.
     */
    static String withTiles(String text, List<String> keys, List<ObjectiveSpec> objectives, java.util.function.Predicate<String> done, java.util.function.Function<String, String> count) {
        Map<String, ObjectiveSpec> byKey = new LinkedHashMap<>();
        objectives.forEach(o -> byKey.put(o.key(), o));
        StringBuilder out = new StringBuilder(text).append("\n");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            boolean free = BingoBoardOptions.FREE_KEY.equals(key);
            ObjectiveSpec o = byKey.get(key);
            String label = free ? "FREE" : o == null ? key : o.label();
            String tally = count.apply(key);
            String line = "\n" + (free || done.test(key) ? "✅" : "⬜") + " **" + (i + 1) + ".** " + label + (tally == null ? "" : " · " + tally);
            if (out.length() + line.length() > 1900) {
                out.append("\n…");
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    private void post(GuildMessageChannel channel, long eventId, String text, FileUpload file) {
        var send = channel.sendMessage(text).setAllowedMentions(List.of());
        if (file != null) send = send.addFiles(file);
        send.queue(
                message -> map.setPanel(eventId, channel.getIdLong(), message.getIdLong()),
                error -> log.warn("Could not post the board of event {}", eventId, error));
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    private int subjectCount(Event event, List<Standing> standings) {
        List<Participant> participants = events.participants(event.id()).stream().filter(p -> !p.waiting()).toList();
        if (event.scoringMode() == EventRules.ScoringMode.TEAM) {
            return (int) Math.max(standings.size(), participants.stream().filter(p -> p.teamId() != null).map(Participant::teamId).distinct().count());
        }
        return Math.max(standings.size(), participants.size());
    }

    private static String stageText(Status status) {
        return switch (status) {
            case RUNNING -> "Running";
            case SCORING -> "Scoring";
            case DONE -> "Finished";
            default -> status.name().toLowerCase();
        };
    }

    private String panelText(Guild guild, Event event, List<Standing> standings) {
        StringBuilder text = new StringBuilder("### " + event.name() + " · " + stageText(event.status()));
        EventStandings.Visibility visibility = EventStandings.of(DataObject.fromJson(event.config()));
        if (!EventStandings.tableShown(visibility, event.status())) {
            text.append("\n").append(EventStandings.hiddenText(visibility));
        } else if (standings.isEmpty()) {
            text.append("\nNo points yet.");
        } else {
            List<Team> teams = events.teams(event.id());
            int rank = 1;
            for (Standing s : standings) {
                if (rank > 5) break;
                String name = nameOf(guild, s.subject(), teams);
                text.append("\n").append(rank++).append(". **").append(name).append("** · ").append(s.points()).append(" pts · ").append(s.objectivesCompleted()).append(" tiles");
            }
        }
        return text.toString();
    }

    /** A subject of the standings ("u:id" for a player, "t:id" for a team) as a name: the member's server name, or the team's name. */
    static String nameOf(Guild guild, String subject, List<Team> teams) {
        long id;
        try {
            id = Long.parseLong(subject.substring(2));
        } catch (RuntimeException e) {
            return subject.startsWith("t:") ? "A team" : "Player";
        }
        if (subject.startsWith("t:")) return teams.stream().filter(t -> t.id() == id).map(Team::name).findFirst().orElse("A team");
        Member member = guild.getMemberById(id);
        return member == null ? "Player" : member.getEffectiveName();
    }

    /** DMs a player whose proof was decided. A closed DM is fine: nothing else depends on it. */
    private void tellPlayer(EventChanges.Change change) {
        JDA client = jda;
        if (client == null || change.userId() == 0) return;
        try {
            EventRepository.Claim claim = events.claim(change.eventId(), change.claimId()).orElse(null);
            Event event = events.eventById(change.eventId()).orElse(null);
            if (claim == null || event == null) return;
            String tile = claim.kind() == EventRepository.ClaimKind.VERIFICATION ? "verification"
                    : "**" + events.objectives(change.eventId()).stream().filter(o -> o.key().equals(claim.objectiveKey())).map(ObjectiveSpec::label).findFirst().orElse(claim.objectiveKey()) + "**";
            String message = change.approved()
                    ? "Your proof for " + tile + " in **" + event.name() + "** was approved."
                    : "Your proof for " + tile + " in **" + event.name() + "** wasn't accepted" + (change.note().isBlank() ? "." : ": " + change.note())
                    + (claim.kind() == EventRepository.ClaimKind.VERIFICATION ? "\nSend it again with `/submit event-verify`." : "");
            client.retrieveUserById(change.userId()).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(message))
                    .queue(ok -> { }, error -> log.debug("Could not DM {} about claim {}: {}", change.userId(), change.claimId(), error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Could not tell a player about claim {}", change.claimId(), e);
        }
    }

    /** Takes a deleted event's live board out of Discord, if it had one and the message is still there. */
    public void removeMessage(EventRepository.Panel panel) {
        if (panel != null) removeMessage(panel.channelId(), panel.messageId());
    }

    private void removeMessage(Long channelId, Long messageId) {
        JDA client = jda;
        if (client == null || channelId == null || messageId == null) return;
        try {
            GuildMessageChannel channel = Shards.channel(client, GuildMessageChannel.class, channelId);
            if (channel != null) channel.deleteMessageById(messageId).queue(ok -> { }, error -> log.debug("Could not remove an event's board message: {}", error.getMessage()));
        } catch (RuntimeException e) {
            log.debug("Could not remove an event's board message", e);
        }
    }
}
