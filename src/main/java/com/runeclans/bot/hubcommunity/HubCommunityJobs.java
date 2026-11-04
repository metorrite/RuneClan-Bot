package com.runeclans.bot.hubcommunity;

import com.runeclans.bot.commands.poll.PollService;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.discord.Mentions;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.events.EventRules;
import com.runeclans.bot.events.QuickGroupJobs;
import com.runeclans.bot.events.QuickGroupRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The Discord jobs {@link HubCommunityFeed} queues while {@code hub-community} is on (ticket F3):
 * <ul>
 *   <li>{@code hubcommunity.refresh}: redraws a poll's or signup's Discord messages from the hub (or shows it ended). Idempotent.</li>
 *   <li>{@code hubcommunity.group-post}: posts a hub group in the server's group finder channel (the channel stays a bot setting).
 *       Not idempotent: a post is never sent twice.</li>
 *   <li>{@code hubcommunity.group-sync}: edits a hub group's post to how it stands now. Idempotent.</li>
 * </ul>
 */
@BService
public class HubCommunityJobs implements DiscordJobHandler {
    public static final String REFRESH = "hubcommunity.refresh";
    public static final String GROUP_POST = "hubcommunity.group-post";
    public static final String GROUP_SYNC = "hubcommunity.group-sync";
    static final String POLL = "poll";
    static final String SIGNUP = "signup";

    private final PollService polls;
    private final SignupService signups;
    private final HubCommunityClient hub;
    private final HubCommunityMaps maps;
    private final QuickGroupRepository groups;

    public HubCommunityJobs(PollService polls, SignupService signups, HubCommunityClient hub, HubCommunityMaps maps, QuickGroupRepository groups) {
        this.polls = polls;
        this.signups = signups;
        this.hub = hub;
        this.maps = maps;
        this.groups = groups;
    }

    @Override
    public String kind() {
        return REFRESH;
    }

    @Override
    public List<String> kinds() {
        return List.of(REFRESH, GROUP_POST, GROUP_SYNC);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return !GROUP_POST.equals(kind);
    }

    static DataObject refresh(String area, long localId, boolean ended) {
        return DataObject.empty().put("area", area).put("localId", Long.toString(localId)).put("ended", ended);
    }

    static DataObject group(UUID hubId) {
        return DataObject.empty().put("groupId", hubId.toString());
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        return switch (job.kind()) {
            case REFRESH -> refresh(guild, p);
            case GROUP_POST, GROUP_SYNC -> group(guild, job.kind(), UUID.fromString(p.getString("groupId")));
            default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
        };
    }

    private DataObject refresh(Guild guild, DataObject p) {
        long local = Long.parseLong(p.getString("localId"));
        boolean ended = p.getBoolean("ended", false);
        if (POLL.equals(p.getString("area"))) {
            if (ended) {
                polls.showClosed(guild, local);
            } else {
                polls.forget(local);
                polls.updateMessage(guild, local);
            }
        } else {
            if (ended) {
                signups.removeDeleted(guild, local);
            } else {
                signups.forget(local);
                signups.updateMessages(guild, local);
            }
        }
        return DataObject.empty().put("refreshed", true);
    }

    DataObject group(Guild guild, String kind, UUID hubId) {
        Optional<DataObject> found = hub.find("/v1/groups/" + hubId);
        Optional<HubCommunityMaps.GroupPost> post = maps.groupPost(hubId);
        if (found.isEmpty()) {
            // removed on the hub: the post stays as it was last shown, and nothing keeps it up to date any more
            post.ifPresent(x -> maps.forgetGroupPost(hubId));
            return DataObject.empty().put("edited", false);
        }
        DataObject g = found.get();
        MessageEmbed embed = embed(g, guild);
        if (GROUP_POST.equals(kind)) {
            if (post.isPresent()) return DataObject.empty().put("posted", false);
            Long channelId = groups.settings(guild.getIdLong()).channelId();
            if (channelId == null) return DataObject.empty().put("posted", false); // no group finder channel chosen: site only
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
            if (channel == null) throw new DiscordJobs.Refused("The group finder's channel is gone. Choose another on the dashboard.");
            if (!channel.canTalk() || !guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_EMBED_LINKS))
                throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + ". Give it Send Messages and Embed Links there.");
            Message sent = DiscordJobs.send(channel.sendMessageEmbeds(embed).setAllowedMentions(Mentions.NONE));
            maps.saveGroupPost(hubId, guild.getIdLong(), channel.getIdLong(), sent.getIdLong());
            return DataObject.empty().put("posted", true).put("messageId", sent.getId());
        }
        if (post.isEmpty()) return DataObject.empty().put("edited", false);
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, post.get().channelId());
        if (channel == null) {
            maps.forgetGroupPost(hubId);
            return DataObject.empty().put("edited", false);
        }
        try {
            DiscordJobs.send(channel.editMessageEmbedsById(post.get().messageId(), embed).setAllowedMentions(Mentions.NONE));
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
            maps.forgetGroupPost(hubId); // someone deleted the post: nothing left to keep up to date
            return DataObject.empty().put("edited", false);
        }
        return DataObject.empty().put("edited", true);
    }

    /** The hub's group drawn the way the bot's own group finder posts look ({@link QuickGroupJobs#embed}). */
    static MessageEmbed embed(DataObject g, Guild guild) {
        QuickGroupRepository.Group group = asGroup(g, guild.getIdLong());
        return QuickGroupJobs.embed(group, members(g), id -> {
            net.dv8tion.jda.api.entities.Member m = guild.getMemberById(id);
            return m == null ? null : m.getEffectiveName();
        });
    }

    public static QuickGroupRepository.Group asGroup(DataObject g, long guildId) {
        DataArray slots = DataArray.empty();
        DataArray raw = g.optArray("slots").orElse(DataArray.empty());
        for (int i = 0; i < raw.length(); i++) {
            DataObject s = raw.getObject(i);
            slots.add(DataObject.empty().put("role", s.getString("role", "Any")).put("places", s.getInt("places", 1)));
        }
        DataObject quick = DataObject.empty().put("slots", slots);
        if (!g.isNull("link") && g.hasKey("link")) quick.put("link", g.getString("link"));
        String config = DataObject.empty().put("quickGroup", quick).toString();
        boolean open = "OPEN".equalsIgnoreCase(g.getString("status", "OPEN"));
        return new QuickGroupRepository.Group(0L, guildId, g.getString("activity", ""), g.getString("note", ""), instant(g.getString("startsAt", null)),
                instant(g.getString("endsAt", null)), HubPollStore.discordId(g.getString("hostDiscordId", null)), config,
                open ? EventRules.Status.SIGNUPS : EventRules.Status.DONE);
    }

    static List<QuickGroupRepository.Member> members(DataObject g) {
        List<QuickGroupRepository.Member> out = new ArrayList<>();
        DataArray list = g.optArray("members").orElse(DataArray.empty());
        for (int i = 0; i < list.length(); i++) {
            DataObject m = list.getObject(i);
            out.add(new QuickGroupRepository.Member(HubPollStore.discordId(m.getString("discordId", null)), m.getString("role", null),
                    instant(m.getString("joinedAt", null))));
        }
        return out;
    }

    private static Instant instant(String raw) {
        try {
            return raw == null ? null : Instant.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
