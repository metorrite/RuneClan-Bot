package com.runeclans.bot.goodbye;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.welcome.WelcomeTemplate;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/** Posts a server's goodbye line when someone leaves. Bots are never mentioned in one, and nothing in it can ping anybody. */
@BService
public class GoodbyeService {
    private static final Logger log = LoggerFactory.getLogger(GoodbyeService.class);

    private final GoodbyeRepository repository;

    public GoodbyeService(GoodbyeRepository repository) {
        this.repository = repository;
    }

    /** What a goodbye says for one leaver. Pure apart from reading the server's name and size, so it is testable. */
    public static String render(String template, String username, String avatarUrl, String serverName, String channelName, int memberCount) {
        return WelcomeTemplate.render(template, lookup(username, avatarUrl, serverName, channelName, memberCount));
    }

    /**
     * How a template is filled for someone who is no longer in the server (a leaver, a banned member): {user} is their name in bold, because a
     * mention of someone who has left would only show a bare number, and nothing else can mention anybody.
     */
    public static WelcomeTemplate.Lookup lookup(String username, String avatarUrl, String serverName, String channelName, int memberCount) {
        return new WelcomeTemplate.Lookup() {
            @Override
            public String userMention() {
                return "**" + username + "**";
            }

            @Override
            public String username() {
                return username;
            }

            @Override
            public String avatarUrl() {
                return avatarUrl;
            }

            @Override
            public String serverName() {
                return serverName;
            }

            @Override
            public String channelName() {
                return channelName;
            }

            @Override
            public int memberCount() {
                return memberCount;
            }

            @Override
            public String findUser(String name) {
                return null;
            }

            @Override
            public String findRole(String name) {
                return null;
            }

            @Override
            public String findChannel(String name) {
                return null;
            }
        };
    }

    /** A real leave: posts this server's goodbye if it is switched on. Never throws; a failure is logged. */
    public void onLeave(Guild guild, User user) {
        if (user.isBot()) return;
        if (AutoPostRouter.skipSweep("goodbye")) return; // posts in the real server: only the live bot does that (T-187, B-82)
        GoodbyeConfig config = repository.get(guild.getIdLong());
        if (!config.enabled() || config.channelId() == null) return;

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
        if (channel == null || !channel.canTalk()) {
            log.warn("The goodbye message is on for guild {} but its channel is missing or JonnyBot can't post there; {}'s leaving wasn't announced.", guild.getId(), user.getId());
            return;
        }
        String text = render(config.content().isBlank() ? GoodbyeConfig.DEFAULT_CONTENT : config.content(), user.getName(), user.getEffectiveAvatarUrl(), guild.getName(), channel.getName(), guild.getMemberCount());
        if (text.isBlank()) return;
        channel.sendMessage(text.length() > 2000 ? text.substring(0, 2000) : text).setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class))
                .queue(sent -> {}, error -> log.warn("Couldn't post the goodbye for {} in #{}", user.getId(), channel.getName(), error));
    }
}
