package com.runeclans.bot.giveaway;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Posting giveaways, drawing winners when they end (on time, or when told to), and rerolling. */
@BService
public class GiveawayService {
    private static final Logger log = LoggerFactory.getLogger(GiveawayService.class);

    /** Why something couldn't be done, in words for the person who asked. */
    public static final class GiveawayException extends RuntimeException {
        public GiveawayException(String message) {
            super(message);
        }
    }

    private final GiveawayRepository repository;
    private final Random random = new SecureRandom();
    private final ScheduledExecutorService executor = BotScheduler.lane("giveaways");

    public GiveawayService(GiveawayRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> sweep(jda), 30, 15, TimeUnit.SECONDS);
    }

    /** Creates the giveaway and posts its message. Throws {@link GiveawayException} if the channel can't be used (nothing is kept then). */
    public Giveaway start(Guild guild, long channelId, String prize, long hostId, int winners, Instant endsAt, Long requiredRoleId) {
        return start(guild, channelId, prize, hostId, winners, endsAt, requiredRoleId, null, false, false);
    }

    /**
     * As above, with an optional role for the winners, a DM to each winner, and entry by reacting as well as by the button. Waits for Discord, so the
     * website runs it as a Discord job ({@code GiveawayJobs}, T-456); a rate limit comes back as {@link DiscordJobs.RetryLater} with nothing kept.
     */
    public Giveaway start(Guild guild, long channelId, String prize, long hostId, int winners, Instant endsAt, Long requiredRoleId, Long winnerRoleId, boolean dmWinners,
                          boolean reactionEntry) {
        GuildMessageChannel channel = postable(guild, channelId);

        long id = repository.create(guild.getIdLong(), channelId, prize, hostId, winners, endsAt, requiredRoleId, winnerRoleId, dmWinners, reactionEntry);
        Giveaway g = repository.get(guild.getIdLong(), id).orElseThrow();
        try {
            var edit = GiveawayMessage.edit(g, 0);
            Message sent = DiscordJobs.send(channel.sendMessage(new MessageCreateBuilder().setEmbeds(edit.getEmbeds()).setComponents(edit.getComponents()).build()));
            repository.setMessage(id, sent.getIdLong());
            if (reactionEntry) sent.addReaction(Emoji.fromUnicode(Giveaway.ENTRY_EMOJI)).queue(ok -> {}, error -> log.warn("Couldn't add the entry reaction to giveaway {}", id, error));
            return repository.get(guild.getIdLong(), id).orElseThrow();
        } catch (RuntimeException e) {
            repository.delete(guild.getIdLong(), id);
            if (e instanceof DiscordJobs.RetryLater) throw e; // nothing was posted: the next try starts afresh
            log.warn("Couldn't post giveaway {} in channel {}", id, channelId, e);
            throw new GiveawayException("Discord refused the message. Check the bot can post and embed links in #" + channel.getName() + ".");
        }
    }

    /** The channel, if the bot can post a giveaway there. Asks nothing of Discord, so the website checks with it before queueing a start. */
    public static GuildMessageChannel postable(Guild guild, long channelId) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new GiveawayException("That channel doesn't exist in this server.");
        if (!channel.canTalk()) throw new GiveawayException("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
        return channel;
    }

    /** Refreshes the entry count shown on the button. */
    public void refresh(Guild guild, Giveaway g) {
        if (g.messageId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, g.channelId());
        if (channel == null) return;
        channel.editMessageById(g.messageId(), GiveawayMessage.edit(g, repository.entryCount(g.id()))).queue(ok -> {}, error -> {});
    }

    /** Ends it now (or when due): draws, shows the result on its message, and announces it. */
    public Giveaway end(Guild guild, Giveaway g) {
        if (!g.open()) throw new GiveawayException("That giveaway has already ended.");
        List<Long> winners = draw(guild, g, Set.of()); // only reads: a failure here leaves it open for the sweep
        if (!repository.finishIfOpen(g.id(), winners)) throw new GiveawayException("That giveaway has already ended.");
        Giveaway ended = repository.get(guild.getIdLong(), g.id()).orElseThrow();
        show(guild, ended, winners, false);
        return ended;
    }

    /** Draws new winners for an ended giveaway, never the same people again. */
    public Giveaway reroll(Guild guild, Giveaway g) {
        if (g.open()) throw new GiveawayException("That giveaway is still running. End it first.");
        List<Long> winners = draw(guild, g, new HashSet<>(g.winners()));
        if (winners.isEmpty()) throw new GiveawayException("There is nobody left to draw: everyone else who entered has left, or doesn't qualify.");
        repository.setWinners(g.id(), winners);
        Giveaway updated = repository.get(guild.getIdLong(), g.id()).orElseThrow();
        show(guild, updated, winners, true);
        return updated;
    }

    private List<Long> draw(Guild guild, Giveaway g, Set<Long> exclude) {
        List<Long> entrants = repository.entrants(g.id());
        if (entrants.isEmpty()) return List.of();
        // only people who are still here (and still hold the required role) can win
        List<Long> eligible = new ArrayList<>();
        try {
            for (Member member : guild.retrieveMembersByIds(false, entrants.stream().mapToLong(Long::longValue).toArray()).get()) {
                if (g.requiredRoleId() != null && member.getRoles().stream().noneMatch(r -> r.getIdLong() == g.requiredRoleId())) continue;
                eligible.add(member.getIdLong());
            }
        } catch (Exception e) {
            log.warn("Couldn't check who is still in the server for giveaway {}; drawing from everyone who entered", g.id(), e);
            eligible = entrants;
        }
        return GiveawayDraw.pick(eligible, g.winnerCount(), exclude, random);
    }

    private void show(Guild guild, Giveaway g, List<Long> winners, boolean reroll) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, g.channelId());
        if (channel == null || g.messageId() == null) return;
        channel.editMessageById(g.messageId(), GiveawayMessage.edit(g, repository.entryCount(g.id()))).queue(ok -> {}, error -> {});
        if (!channel.canTalk()) return;
        String text = winners.isEmpty()
                ? "Nobody entered **" + g.prize() + "**, so there is no winner."
                : (reroll ? "🎉 New winner" : "🎉 Congratulations") + (winners.size() == 1 ? " " : "s ") + GiveawayDraw.mentions(winners) + "! You won **" + g.prize() + "**.";
        channel.sendMessage(text).setAllowedMentions(EnumSet.of(Message.MentionType.USER)).mentionUsers(winners.stream().mapToLong(Long::longValue).toArray())
                .setMessageReference(g.messageId()).failOnInvalidReply(false).queue(ok -> {}, error -> {});
        rewardWinners(guild, g, winners);
    }

    /** The winner role and the winner DM, each only if this giveaway asked for it. Neither can stop the announcement or each other. */
    private void rewardWinners(Guild guild, Giveaway g, List<Long> winners) {
        if (winners.isEmpty()) return;
        if (g.winnerRoleId() != null) {
            Role role = guild.getRoleById(g.winnerRoleId());
            if (role != null && MemberApi.safeToAssign(guild, role)) {
                for (long winner : winners) {
                    guild.retrieveMemberById(winner).queue(member -> guild.addRoleToMember(member, role).reason("Won giveaway " + g.id()).queue(ok -> {}, error -> {}), error -> {});
                }
            }
        }
        if (g.dmWinners()) {
            String link = g.messageId() == null ? "" : "\nhttps://discord.com/channels/" + g.guildId() + "/" + g.channelId() + "/" + g.messageId();
            String text = "🎉 You won **" + g.prize() + "** in **" + guild.getName() + "**!" + link;
            for (long winner : winners) {
                // closed DMs are expected: nothing to do about them, the announcement in the channel is the fallback
                guild.getJDA().retrieveUserById(winner).queue(user -> user.openPrivateChannel().queue(dm -> dm.sendMessage(text).queue(ok -> {}, error -> {}), error -> {}), error -> {});
            }
        }
    }

    void sweep(JDA jda) {
        if (AutoPostRouter.diverting()) return; // ending a giveaway posts winners and DMs them
        try {
            for (Giveaway g : repository.due(Instant.now(), 20)) {
                Guild guild = Shards.guild(jda, g.guildId());
                if (guild == null) continue;
                try {
                    end(guild, g);
                } catch (GiveawayException e) {
                    log.debug("Giveaway {} was ended elsewhere a moment earlier; its winners stand", g.id());
                } catch (RuntimeException e) {
                    log.warn("Couldn't end giveaway {}", g.id(), e);
                    try {
                        repository.finishIfOpen(g.id(), List.of()); // don't retry forever; never overwrites winners another end already stored
                    } catch (RuntimeException again) {
                        log.warn("Couldn't mark giveaway {} ended after its end failed", g.id(), again);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Giveaway sweep failed", e);
        }
    }
}
