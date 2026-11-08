package com.runeclans.bot.internal;

import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.configure.WebsiteLink;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.announcement.PostMarkup;
import com.runeclans.bot.announcement.PostTextConverter;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.tracking.TrackingGroup;
import com.runeclans.bot.tracking.TrackingRepository;
import com.runeclans.bot.member.MemberProfileRepository.SelfRole;
import com.runeclans.bot.runescape.ClanPointsRepository;
import com.runeclans.bot.runescape.ClanPointsRepository.RankConfigRow;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The admin dashboard's non-ticket settings: which roles members may give themselves, and the clan's points
 * and rank thresholds. Reached only through {@link TicketAdminApi}, which has already verified the acting user
 * is an Admin or Developer, so nothing here re-checks who is asking.
 */
@BService
public class ClanAdminApi {
    private static final Logger log = LoggerFactory.getLogger(ClanAdminApi.class);
    private static final int MAX_SELF_ROLES = 25;
    private static final long MAX_POINTS = 1_000_000_000L;

    private final MemberProfileRepository members;
    private final ClanPointsRepository points;
    private final SiteNewsService news;
    private final SiteStatsRepository stats;
    private final TrackingRepository tracking;
    private final CommunitySettings communitySettings;
    private final GuildSettingsService guildSettings;
    private final SiteCache cache;
    private final DiscordOutbox outbox;

    public ClanAdminApi(MemberProfileRepository members, ClanPointsRepository points, SiteNewsService news, SiteStatsRepository stats, TrackingRepository tracking,
                        CommunitySettings communitySettings, GuildSettingsService guildSettings, SiteCache cache, DiscordOutbox outbox) {
        this.outbox = outbox;
        this.guildSettings = guildSettings;
        this.cache = cache;
        this.members = members;
        this.points = points;
        this.news = news;
        this.stats = stats;
        this.tracking = tracking;
        this.communitySettings = communitySettings;
    }

    // ---------- the clan's website (the same setting as /configure's Website Link) ----------

    DataObject clanWebsite(Guild guild) {
        return DataObject.empty().put("websiteUrl", guildSettings.getEffective(guild.getIdLong()).websiteUrl());
    }

    DataObject saveClanWebsite(Guild guild, Member actor, DataObject body) {
        String raw = body.isNull("websiteUrl") ? "" : body.getString("websiteUrl", "").strip();
        if (raw.isEmpty()) {
            guildSettings.updateWebsiteUrl(guild.getIdLong(), null);
        } else {
            var url = WebsiteLink.normalize(raw);
            if (url.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_doesn_t_look_like_a", WebsiteLink.MAX_LENGTH));
            guildSettings.updateWebsiteUrl(guild.getIdLong(), url.get());
        }
        log.info("Dashboard: {} set the clan website to {}", actor.getId(), raw.isEmpty() ? "none" : "a new address");
        return clanWebsite(guild);
    }

    // ---------- options for how the public website behaves ----------

    DataObject siteOptions(Guild guild) {
        return DataObject.empty().put("navEventBubble", communitySettings.navEventBubble(guild.getIdLong()));
    }

    DataObject saveSiteOptions(Guild guild, Member actor, DataObject body) {
        communitySettings.setNavEventBubble(guild.getIdLong(), body.getBoolean("navEventBubble", false));
        cache.invalidate("options");
        log.info("Dashboard: {} changed the website's options", actor.getId());
        return siteOptions(guild);
    }

    // ---------- the website's news channels ----------

    DataObject newsChannels(Guild guild) {
        DataArray array = DataArray.empty();
        for (var configured : news.channels(guild.getIdLong())) {
            var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, configured.channelId());
            array.add(DataObject.empty().put("channelId", Long.toString(configured.channelId())).put("name", channel == null ? null : channel.getName())
                    .put("label", configured.label()).put("readable", channel != null && guild.getSelfMember().hasPermission(channel,
                            net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_HISTORY)));
        }
        return DataObject.empty().put("channels", array);
    }

    DataObject saveNewsChannels(Guild guild, Member actor, DataObject body) {
        DataArray input = body.isNull("channels") ? DataArray.empty() : body.getArray("channels");
        if (input.length() > 6) throw new ApiError(400, Text.of(guild, "dashboard.at_most_6_news_channels"));

        List<SiteNewsService.NewsChannel> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            long channelId;
            try {
                channelId = Long.parseLong(item.getString("channelId", ""));
            } catch (NumberFormatException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_channel_isn_t_valid"));
            }
            if (!seen.add(channelId)) continue;
            var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId);
            if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_channel_isn_t_a"));
            if (!guild.getSelfMember().hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_HISTORY)) {
                throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_read_give_it", channel.getName()));
            }
            String label = item.getString("label", "").strip();
            if (label.length() > 40) throw new ApiError(400, Text.of(guild, "dashboard.labels_are_up_to_40_characters"));
            chosen.add(new SiteNewsService.NewsChannel(channelId, label.isEmpty() ? null : label, i));
        }

        news.replaceChannels(guild.getIdLong(), chosen);
        log.info("Dashboard: {} set {} public news channel(s)", actor.getId(), chosen.size());
        return newsChannels(guild);
    }

    // ---------- promotions ----------

    /** Members the points system says are due a rank-up, who then get promoted in game and are cleared here. */
    DataObject promotions(Guild guild) {
        long guildId = guild.getIdLong();
        var ranks = points.getRanksOrdered(guildId);
        var byRsn = new java.util.HashMap<String, SiteStatsRepository.MemberRow>();
        stats.members(guildId).forEach(m -> byRsn.put(m.rsn().toLowerCase(), m));

        DataArray array = DataArray.empty();
        for (var row : points.getAllNeedingPromotion(guildId)) {
            var member = byRsn.get(row.rsn().toLowerCase());
            if (member == null) continue; // left the clan since
            var next = ranks.stream().filter(r -> r.rankOrder() > member.rankOrder()).min(java.util.Comparator.comparingInt(RankConfigRow::rankOrder)).orElse(null);
            array.add(DataObject.empty().put("rsn", member.rsn()).put("rank", member.clanRank()).put("nextRank", next == null ? null : next.rankName())
                    .put("points", row.totalPoints()).put("since", row.promotionNeededSince() == null ? null : row.promotionNeededSince().toString()));
        }
        return DataObject.empty().put("members", array);
    }

    DataObject markPromoted(Guild guild, Member actor, String rsn) {
        if (!points.markPromoted(guild.getIdLong(), rsn)) throw new ApiError(503, Text.of(guild, "dashboard.that_didn_t_save_try_again"));
        log.info("Dashboard: {} marked {} as promoted", actor.getId(), rsn);
        return promotions(guild);
    }

    // ---------- tracking channels (where the bot posts clan events) ----------

    DataObject tracking(Guild guild) {
        DataArray groups = DataArray.empty();
        for (TrackingGroup group : TrackingGroup.values()) {
            DataArray channels = DataArray.empty();
            for (var destination : tracking.getDestinations(guild.getIdLong(), group.name())) {
                var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, destination.channelId());
                channels.add(DataObject.empty().put("channelId", Long.toString(destination.channelId())).put("name", channel == null ? null : channel.getName()));
            }
            groups.add(DataObject.empty().put("key", group.name()).put("source", group.source()).put("name", group.displayName())
                    .put("enabled", tracking.isEnabled(guild.getIdLong(), group.name())).put("channels", channels));
        }
        return DataObject.empty().put("groups", groups);
    }

    DataObject saveTracking(Guild guild, Member actor, String key, DataObject body) {
        TrackingGroup group;
        try {
            group = TrackingGroup.valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new ApiError(404, Text.of(guild, "dashboard.that_isn_t_a_tracking_group"));
        }
        long guildId = guild.getIdLong();

        Set<Long> wanted = new HashSet<>();
        if (!body.isNull("channelIds")) {
            DataArray array = body.getArray("channelIds");
            if (array.length() > 5) throw new ApiError(400, Text.of(guild, "dashboard.at_most_5_channels_per_group"));
            for (int i = 0; i < array.length(); i++) {
                long id;
                try {
                    id = Long.parseLong(array.getString(i));
                } catch (NumberFormatException e) {
                    throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_channel_isn_t_valid"));
                }
                var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, id);
                if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_channel_isn_t_a"));
                if (!channel.canTalk()) throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in_give", channel.getName()));
                wanted.add(id);
            }
        }

        var current = tracking.getDestinations(guildId, group.name());
        Set<Long> have = new HashSet<>();
        for (var destination : current) {
            have.add(destination.channelId());
            if (!wanted.contains(destination.channelId())) tracking.removeDestination(guildId, destination.id());
        }
        for (long id : wanted) if (!have.contains(id)) tracking.addDestination(guildId, group.name(), id);
        tracking.setEnabled(guildId, group.name(), body.getBoolean("enabled", true));

        log.info("Dashboard: {} set tracking group {} to {} channel(s), enabled={}", actor.getId(), group.name(), wanted.size(), body.getBoolean("enabled", true));
        return tracking(guild);
    }

    // ---------- posting a message (the same post markup as the Discord tools) ----------

    /** Posts markup text as a message in a channel; with {@code dryRun} it only checks it and reports any problems. */
    DataObject post(Guild guild, Member actor, DataObject body) {
        String raw = body.getString("text", "");
        if (raw.isBlank()) throw new ApiError(400, Text.of(guild, "dashboard.write_something_to_post"));
        if (raw.length() > 3500) throw new ApiError(400, Text.of(guild, "dashboard.that_too_long_keep_it_under"));
        String text = body.getBoolean("convert", false) ? PostTextConverter.convert(raw) : raw.strip();

        PostMarkup.Parsed parsed = PostMarkup.parse(text, true);
        if (parsed.hasErrors()) throw new ApiError(400, Text.of(guild, "dashboard.nothing_was_posted_fix_these_first"), parsed.errors().stream().map(p -> "line " + p.line() + ": " + p.message()).toList());

        DataArray warnings = DataArray.empty();
        parsed.problems().stream().filter(p -> p.severity() != PostMarkup.Severity.ERROR).forEach(p -> warnings.add("line " + p.line() + ": " + p.message()));
        if (body.getBoolean("dryRun", false)) return DataObject.empty().put("ok", true).put("posted", false).put("warnings", warnings);

        long channelId;
        try {
            channelId = Long.parseLong(body.getString("channelId", ""));
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel"));
        }
        var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_text_channel"));
        if (!channel.canTalk()) throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in", channel.getName()));

        // T-456: queued, so the dashboard answers at once; ChannelPostJob posts it, waiting out Discord's rate limits, and the page follows it
        DiscordJob job = outbox.enqueue(ChannelPostJob.KIND, guild.getIdLong(), actor.getIdLong(), null,
                DataObject.empty().put("channelId", Long.toString(channelId)).put("text", text));
        log.info("Dashboard: {} queued a message for #{} (job {})", actor.getId(), channel.getName(), job.id());
        return DataObject.empty().put("ok", true).put("posted", false).put("queued", true).put("job", job.ticket()).put("warnings", warnings);
    }

    // ---------- community settings ----------

    DataObject community(Guild guild) {
        Long id = communitySettings.pollChannel(guild.getIdLong());
        var channel = id == null ? null : guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, id);
        return DataObject.empty().put("pollChannelId", id == null ? null : Long.toString(id)).put("pollChannelName", channel == null ? null : channel.getName());
    }

    DataObject saveCommunity(Guild guild, Member actor, DataObject body) {
        Long channelId = null;
        if (!body.isNull("pollChannelId") && !body.getString("pollChannelId").isBlank()) {
            try {
                channelId = Long.parseLong(body.getString("pollChannelId"));
            } catch (NumberFormatException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.that_channel_isn_t_valid"));
            }
            var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId);
            if (channel == null || !channel.canTalk()) throw new ApiError(400, Text.of(guild, "dashboard.jonnybot_can_t_post_in_that"));
        }
        communitySettings.setPollChannel(guild.getIdLong(), channelId);
        log.info("Dashboard: {} set the member-poll channel to {}", actor.getId(), channelId);
        return community(guild);
    }

    // ---------- self-assignable roles ----------

    DataObject selfRoles(Guild guild) {
        DataArray array = DataArray.empty();
        for (SelfRole configured : members.selfRoles(guild.getIdLong())) {
            Role role = guild.getRoleById(configured.roleId());
            array.add(DataObject.empty().put("roleId", Long.toString(configured.roleId())).put("name", role == null ? null : role.getName())
                    .put("label", configured.label()).put("description", configured.description())
                    .put("safe", role != null && MemberApi.safeToAssign(guild, role)));
        }
        return DataObject.empty().put("roles", array);
    }

    DataObject saveSelfRoles(Guild guild, Member actor, DataObject body) {
        DataArray input = body.isNull("roles") ? DataArray.empty() : body.getArray("roles");
        if (input.length() > MAX_SELF_ROLES) throw new ApiError(400, Text.of(guild, "dashboard.at_most_self_assignable_roles", MAX_SELF_ROLES));

        List<SelfRole> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            long roleId;
            try {
                roleId = Long.parseLong(item.getString("roleId", ""));
            } catch (NumberFormatException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_role_isn_t_valid"));
            }
            if (!seen.add(roleId)) continue;

            Role role = guild.getRoleById(roleId);
            if (role == null) throw new ApiError(400, Text.of(guild, "dashboard.a_chosen_role_no_longer_exists"));
            if (!MemberApi.safeToAssign(guild, role)) {
                var dangerous = MemberApi.dangerousIn(role.getPermissions());
                throw new ApiError(400, dangerous.isEmpty()
                        ? Text.of(guild, "dashboard.not_self_assignable_managed", role.getName())
                        : Text.of(guild, dangerous.size() > 1 ? "dashboard.not_self_assignable_powerful_more" : "dashboard.not_self_assignable_powerful",
                                role.getName(), dangerous.iterator().next().getName()));
            }
            String label = item.getString("label", "").strip();
            String description = item.getString("description", "").strip();
            if (label.length() > 40 || description.length() > 120) throw new ApiError(400, Text.of(guild, "dashboard.labels_are_up_to_40_characters_2"));
            chosen.add(new SelfRole(roleId, label.isEmpty() ? null : label, description.isEmpty() ? null : description, i));
        }

        members.replaceSelfRoles(guild.getIdLong(), chosen);
        log.info("Dashboard: {} set {} self-assignable role(s)", actor.getId(), chosen.size());
        return selfRoles(guild);
    }

    // ---------- clan points and rank thresholds ----------

    DataObject clanPoints(Guild guild) {
        // the hub's (T-978): with no answer, say so (503) rather than show zeros the page could save back
        DataObject known = points.known(guild.getIdLong());
        if (known == null) throw new ApiError(503, Text.of(guild, "dashboard.clan_points_didn_t_load"));
        var settings = ClanPointsRepository.settingsOf(known);
        DataArray ranks = DataArray.empty();
        for (RankConfigRow rank : ClanPointsRepository.ranksOf(guild.getIdLong(), known)) {
            ranks.add(DataObject.empty().put("id", Long.toString(rank.id())).put("name", rank.rankName()).put("order", rank.rankOrder()).put("threshold", rank.pointThreshold()));
        }
        return DataObject.empty().put("dailyMembershipPoints", settings.dailyMembershipPoints()).put("citadelVisitPoints", settings.citadelVisitPoints())
                .put("citadelCapPoints", settings.citadelCapPoints()).put("ranks", ranks);
    }

    DataObject saveClanPoints(Guild guild, Member actor, DataObject body) {
        // only the amounts the body names are sent: one left out (or null) stays as the hub has it, never 0
        Long daily = givenPoints(body, "dailyMembershipPoints");
        Long visit = givenPoints(body, "citadelVisitPoints");
        Long cap = givenPoints(body, "citadelCapPoints");

        List<RankConfigRow> existing = ClanPointsRepository.ranksOf(guild.getIdLong(), null); // the standard ladder: no hub read
        List<long[]> updates = new ArrayList<>();
        if (!body.isNull("ranks")) {
            DataArray ranks = body.getArray("ranks");
            for (int i = 0; i < ranks.length(); i++) {
                DataObject item = ranks.getObject(i);
                long id;
                try {
                    id = Long.parseLong(item.getString("id", ""));
                } catch (NumberFormatException e) {
                    throw new ApiError(400, Text.of(guild, "dashboard.a_rank_isn_t_valid"));
                }
                if (existing.stream().noneMatch(r -> r.id() == id)) throw new ApiError(400, Text.of(guild, "dashboard.a_rank_doesn_t_belong_to"));
                updates.add(new long[]{id, pointsValue(item, "threshold")});
            }
        }

        java.util.Map<Long, Long> thresholds = new java.util.LinkedHashMap<>();
        for (long[] update : updates) thresholds.put(update[0], update[1]);
        if (points.save(guild.getIdLong(), actor.getIdLong(), daily, visit, cap, thresholds) == null) throw new ApiError(503, Text.of(guild, "dashboard.that_didn_t_save_try_again"));
        log.info("Dashboard: {} updated clan points settings and {} rank threshold(s)", actor.getId(), updates.size());
        return clanPoints(guild);
    }

    private static Long givenPoints(DataObject json, String key) {
        return !json.hasKey(key) || json.isNull(key) ? null : pointsValue(json, key);
    }

    private static long pointsValue(DataObject json, String key) {
        long value;
        try {
            value = json.isNull(key) ? 0 : Long.parseLong(String.valueOf(json.toMap().get(key)).replaceAll("\\.0+$", ""));
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.must_be_a_whole_number_2", key));
        }
        if (value < 0 || value > MAX_POINTS) throw new ApiError(400, Text.en("dashboard.must_be_between_0_and", key, MAX_POINTS));
        return value;
    }
}
