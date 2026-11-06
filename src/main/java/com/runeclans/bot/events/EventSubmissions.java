package com.runeclans.bot.events;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRepository.Participant;
import com.runeclans.bot.events.EventSpaceRepository.ChannelRow;
import com.runeclans.bot.hubevents.HubEventClient.Actor;
import com.runeclans.bot.hubevents.HubEvents;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The Discord side of {@code /submit}: sends a player's screenshot to the event's review channel with the Approve and Deny buttons, posts a receipt in the
 * player's own (or team's) channel when the event has one, and edits that receipt when staff decide. The rules about who may send from where are the
 * pure {@link EventSubmit}; the claim itself is the hub's ({@link HubEvents}), and the receipt's whereabouts are the bot's ({@code hub_event_claim_receipt}).
 */
@BService
public class EventSubmissions {
    private static final Logger log = LoggerFactory.getLogger(EventSubmissions.class);
    private static final String STATUS_PREFIX = "-# ";
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final HubEvents service;
    private final EventSpaceRepository space;
    private final Executor worker = Executors.newFixedThreadPool(2, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-submissions");
        thread.setDaemon(true);
        return thread;
    });
    private volatile JDA jda;

    public EventSubmissions(HubEvents service, EventSpaceRepository space, EventChanges changes) {
        this.service = service;
        this.space = space;
        changes.subscribe(change -> {
            if (change.kind() == EventChanges.Kind.CLAIM_DECIDED) worker.execute(() -> updateReceipt(change));
        });
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** The event channel with this id (one JonnyBot made or an organiser chose), or null when it isn't one. */
    public EventSubmit.Channel channelOf(long guildId, long channelId) {
        Optional<ChannelRow> row = space.channelById(guildId, channelId);
        return row.map(r -> new EventSubmit.Channel(r.eventId(), r.key())).orElse(null);
    }

    /** The channel staff review proof in: the one the event names, which the roles and channels setup also fills in. Null when there is none. */
    public TextChannel reviewChannel(Guild guild, Event event) {
        Long id = EventSpaces.reviewChannelOf(event, space);
        return id == null ? null : guild.getTextChannelById(id);
    }

    /** Whether the image is one that can be sent on (an image of at most 8 MB). Null when fine, otherwise what to tell the player. */
    public static String imageProblem(Message.Attachment shot) {
        String type = shot.getContentType();
        if (type == null || !type.startsWith("image/") || shot.getSize() > MAX_IMAGE_BYTES) return "Attach a screenshot (an image, up to 8 MB).";
        return null;
    }

    /**
     * Sends the screenshot of claim {@code claimId} to the review channel (with the buttons) and, if the event wants receipts, to the player's own channel.
     * Completes with what to tell the player; if the review channel can't be reached the claim is denied again and this completes exceptionally.
     */
    public CompletableFuture<String> deliver(Guild guild, Event event, long claimId, Member who, Participant player, String title, String note,
                                             Message.Attachment shot, boolean verification, TextChannel review) {
        String extra = note == null || note.isBlank() ? "" : "\n" + note.strip().replaceAll("[\\r\\n]+", " ");
        String what = verification ? "verification" : "submission";
        String head = "**" + event.name() + " · " + what + " #" + claimId + "** from " + who.getAsMention() + (verification ? "" : "\n" + title) + extra;
        String queueText = head + "\n-# Approve or deny it with the buttons, or on the dashboard (Event manager, Progress and proof).";
        return shot.getProxy().download().thenApplyAsync(stream -> readAll(stream), worker).thenCompose(bytes ->
                review.sendMessage(queueText).setAllowedMentions(List.of()).addFiles(FileUpload.fromData(bytes, shot.getFileName()))
                        .setComponents(EventClaimListener.buttons(review.getGuild().getIdLong(), event.id(), claimId)).submit().thenApply(message -> {
                            attachProof(guild.getIdLong(), event.id(), claimId, message);
                            sendReceipt(guild, event, claimId, player, head, bytes, shot.getFileName(), review.getIdLong());
                            return verification ? "Sent to staff as verification #" + claimId + ". You'll get a DM when they decide."
                                    : "Sent to staff as submission #" + claimId + " for **" + title + "**. You'll get a DM when they decide.";
                        })).whenComplete((text, error) -> {
            if (error != null) {
                log.warn("Could not deliver claim {} of event {}", claimId, event.id(), error);
                try {
                    service.decideClaim(guild.getIdLong(), event.id(), claimId, false, "Couldn't be sent to the review channel", service.self(guild.getIdLong()));
                } catch (RuntimeException problem) {
                    log.warn("Claim {} of event {} couldn't be denied after its delivery failed: {}", claimId, event.id(), problem.getMessage());
                }
            }
        });
    }

    /** The screenshot now lives in the review channel: the hub keeps its link on the claim, so the website shows the same picture staff see (T-941). */
    private void attachProof(long guildId, long eventId, long claimId, Message message) {
        if (message.getAttachments().isEmpty()) return;
        try {
            service.setClaimProof(guildId, eventId, claimId, message.getAttachments().get(0).getUrl());
        } catch (RuntimeException e) {
            log.info("Couldn't attach the proof of claim {} of event {} on the hub: {}", claimId, eventId, e.getMessage());
        }
    }

    private static byte[] readAll(InputStream stream) {
        try (stream) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Posts the receipt in the player's own (team's) channel. A receipt that can't be posted is only logged: the proof is already with staff. */
    private void sendReceipt(Guild guild, Event event, long claimId, Participant player, String head, byte[] bytes, String fileName, long reviewChannelId) {
        try {
            EventSettings settings = service.settingsOf(event);
            if (!settings.submissions().receipts()) return;
            List<ChannelRow> rows = space.channels(event.id());
            String key = EventSubmit.receiptKey(event.scoringMode() == EventRules.ScoringMode.TEAM, player.teamId(), player.userId(), rows.stream().map(ChannelRow::key).toList());
            if (key == null) return;
            ChannelRow row = rows.stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
            TextChannel channel = row == null ? null : guild.getTextChannelById(row.channelId());
            if (channel == null || channel.getIdLong() == reviewChannelId) return;
            channel.sendMessage(head + "\n" + STATUS_PREFIX + "Waiting for staff").setAllowedMentions(List.of()).addFiles(FileUpload.fromData(bytes, fileName)).queue(
                    message -> service.map().setReceipt(event.id(), claimId, channel.getIdLong(), message.getIdLong()),
                    error -> log.debug("Could not post the receipt of claim {}: {}", claimId, error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Could not post the receipt of claim {}", claimId, e);
        }
    }

    /** Changes the receipt's last line from "Waiting for staff" to what staff decided. */
    private void updateReceipt(EventChanges.Change change) {
        JDA client = jda;
        if (client == null) return;
        try {
            Claim claim = change.claimId() == 0 ? null : service.claim(change.eventId(), change.claimId()).orElse(null);
            if (claim == null || claim.receiptChannelId() == null || claim.receiptMessageId() == null) return;
            TextChannel channel = Shards.textChannel(client, claim.receiptChannelId());
            if (channel == null) return;
            String status = STATUS_PREFIX + statusText(claim.kind(), change.approved(), change.note());
            channel.retrieveMessageById(claim.receiptMessageId()).queue(message -> {
                String content = message.getContentRaw();
                int cut = content.lastIndexOf('\n' + STATUS_PREFIX);
                String body = cut >= 0 ? content.substring(0, cut) : content;
                message.editMessage(body + "\n" + status).setAllowedMentions(List.of()).queue(ok -> { }, error -> log.debug("Could not update a receipt: {}", error.getMessage()));
            }, error -> log.debug("Could not find a receipt to update: {}", error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Could not update the receipt of claim {}", change.claimId(), e);
        }
    }

    /** The receipt's last line for a decision. */
    static String statusText(EventRepository.ClaimKind kind, boolean approved, String note) {
        if (approved) return kind == EventRepository.ClaimKind.VERIFICATION ? "✅ Verified" : "✅ Approved";
        String reason = note == null || note.isBlank() ? "" : ": " + note.strip().replaceAll("[\\r\\n]+", " ");
        return "❌ Not accepted" + reason;
    }
}
