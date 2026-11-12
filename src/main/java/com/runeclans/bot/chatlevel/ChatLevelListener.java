package com.runeclans.bot.chatlevel;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.BotListener;
import com.runeclans.bot.internal.MemberApi;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gives members XP for chatting. A message earns XP at most once per cooldown per member, never from bots, webhooks, direct messages, ignored channels or ignored
 * roles. It only needs to know a message was sent, not what it said, so it works without the Message Content intent. The database write and any level-up
 * work run on one background worker so a busy server never slows the gateway thread. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class ChatLevelListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(ChatLevelListener.class);

    private final ChatLevelRepository repository;
    private final Map<String, Long> lastEarned = new ConcurrentHashMap<>();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(5000), (ThreadFactory) r -> {
        Thread t = new Thread(r, "chat-level-writer");
        t.setDaemon(true);
        return t;
    }, new ThreadPoolExecutor.DiscardPolicy());

    public ChatLevelListener(ChatLevelRepository repository) {
        this.repository = repository;
    }

    /** The channel a message counts as for ignoring and multipliers: a thread counts as the channel it belongs to. */
    static long rootChannelId(GuildChannel channel) {
        return channel instanceof ThreadChannel thread && thread.getParentChannel() != null ? thread.getParentChannel().getIdLong() : channel.getIdLong();
    }

    /** XP for one message: a random amount in the server's range, times the channel's multiplier and the member's best role multiplier (never above the cap). */
    static int xpFor(ChatLevelConfig config, long rootChannelId, List<Long> roleIds, int roll) {
        double multiplier = config.channelMultipliers().getOrDefault(rootChannelId, 1.0);
        double best = 1.0;
        boolean any = false;
        for (long role : roleIds) {
            Double m = config.roleMultipliers().get(role);
            if (m == null) continue;
            best = any ? Math.max(best, m) : m;
            any = true;
        }
        double total = Math.min(ChatLevelConfig.MAX_MULTIPLIER * ChatLevelConfig.MAX_MULTIPLIER, multiplier * best);
        return (int) Math.round(roll * total);
    }

    /** Whether the member may earn again: true (and noted) when the cooldown since their last award has passed. */
    boolean ready(long guildId, long userId, int cooldownSeconds, long now) {
        String key = guildId + ":" + userId;
        Long last = lastEarned.get(key);
        if (last != null && now - last < cooldownSeconds * 1000L) return false;
        lastEarned.put(key, now);
        return true;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (AutoPostRouter.skipSweep("chat levels")) return; // level-up posts and level roles in the real server: only the live bot does that (T-187, B-82)
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage() || event.getMember() == null) return;
        try {
            Guild guild = event.getGuild();
            ChatLevelConfig config = repository.config(guild.getIdLong());
            if (!config.enabled()) return;
            long root = rootChannelId(event.getGuildChannel());
            if (config.ignoredChannels().contains(root)) return;
            Member member = event.getMember();
            List<Long> roleIds = member.getRoles().stream().map(Role::getIdLong).toList();
            if (roleIds.stream().anyMatch(config.ignoredRoles()::contains)) return;
            if (!ready(guild.getIdLong(), member.getIdLong(), config.cooldownSeconds(), System.currentTimeMillis())) return;
            int roll = ThreadLocalRandom.current().nextInt(config.minXp(), config.maxXp() + 1);
            int amount = xpFor(config, root, roleIds, roll);
            if (amount <= 0) return;
            Message message = event.getMessage();
            worker.execute(() -> award(guild, member, message, amount, config));
        } catch (Exception e) {
            log.error("Chat levels failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    private void award(Guild guild, Member member, Message message, int amount, ChatLevelConfig config) {
        try {
            long total = repository.addXp(guild.getIdLong(), member.getIdLong(), amount);
            if (total < 0) return;
            int before = ChatLevels.levelOf(total - amount);
            int after = ChatLevels.levelOf(total);
            if (after > before) {
                if (config.announce()) announce(guild, member, message, after, config);
                giveRoles(guild, member, after, config);
            }
        } catch (Exception e) {
            log.error("Chat level award failed in guild {}", guild.getId(), e);
        }
    }

    private void announce(Guild guild, Member member, Message message, int level, ChatLevelConfig config) {
        GuildMessageChannel channel = config.announceChannelId() == null ? message.getGuildChannel() instanceof GuildMessageChannel c ? c : null
                : guild.getChannelById(GuildMessageChannel.class, config.announceChannelId());
        if (channel == null || !channel.canTalk()) return;
        String text = ChatLevelConfig.fill(config.announceMessage(), member.getAsMention(), level, guild.getName());
        channel.sendMessage(text.length() > 2000 ? text.substring(0, 2000) : text).setAllowedMentions(EnumSet.of(Message.MentionType.USER)).mention(member.getUser())
                .queue(ok -> {}, error -> log.debug("Couldn't announce a level-up in {}", channel.getId()));
    }

    /**
     * The level roles a member of {@code level} should hold: every reward up to their level, or only the highest when old ones are replaced. Only roles that are safe
     * to hand out (no powerful permissions, below JonnyBot's own role) are ever given or taken.
     */
    static List<Long> rolesToHold(Map<Integer, Long> rewards, int level, boolean replace) {
        List<Long> earned = new ArrayList<>();
        rewards.forEach((at, role) -> {
            if (at <= level) earned.add(role);
        });
        return replace && earned.size() > 1 ? List.of(earned.get(earned.size() - 1)) : earned;
    }

    void giveRoles(Guild guild, Member member, int level, ChatLevelConfig config) {
        Map<Integer, Long> rewards = repository.roles(guild.getIdLong());
        if (rewards.isEmpty()) return;
        List<Long> hold = rolesToHold(rewards, level, config.removeOldRoles());
        for (long id : hold) {
            Role role = guild.getRoleById(id);
            if (role == null || !MemberApi.safeToAssign(guild, role) || member.getRoles().contains(role)) continue;
            guild.addRoleToMember(member, role).reason("Reached level " + level).queue(ok -> {}, error -> log.debug("Couldn't give level role {}", id));
        }
        if (config.removeOldRoles()) {
            for (long id : rewards.values()) {
                if (hold.contains(id)) continue;
                Role role = guild.getRoleById(id);
                if (role == null || !MemberApi.safeToAssign(guild, role) || !member.getRoles().contains(role)) continue;
                guild.removeRoleFromMember(member, role).reason("Replaced by a higher level role").queue(ok -> {}, error -> log.debug("Couldn't take level role {}", id));
            }
        }
    }
}
