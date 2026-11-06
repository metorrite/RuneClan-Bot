package com.runeclans.bot.internal;

import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.SkillWeekRules;
import com.runeclans.bot.events.SkillWeekService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The dashboard's Skill of the week (T-541), reached through {@link TicketAdminApi} (dashboard access already checked, writes audit-logged):
 * <ul>
 *     <li>{@code GET skill-week}: the switch, the rota, the skill next in line, this week and its standings, last week's winners, and the skills to
 *     choose from</li>
 *     <li>{@code PUT skill-week} {@code {enabled, rota: [skillId...], mode, announceChannelId, autoEnrol}}: saves them. Switching on needs the
 *     bot connected to the RuneClans hub (the weeks are counted from its XP ledger) and at least one skill. Switching on opens this week's
 *     competition straight away. The options (T-711) are off unless sent: {@code mode} {@code "rota"} or {@code "vote"} (members vote for next
 *     week's skill among the rota's on the clan website), {@code announceChannelId} a channel of this server (or null for no posts) and
 *     {@code autoEnrol}.</li>
 * </ul>
 */
@BService
public class SkillWeekAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekAdminApi.class);

    private final SkillWeekService weeks;

    public SkillWeekAdminApi(SkillWeekService weeks) {
        this.weeks = weeks;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("skill-week")) return null;
        if (method.equals("PUT")) {
            DataObject sent = body.get();
            Long channel = channelOf(sent);
            // checked only for a new channel: one the bot later lost the right to post in never blocks saving anything else (or turning it off)
            if (channel != null && !channel.equals(savedChannel(guild.getIdLong()))) {
                var target = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channel);
                if (target == null) throw new ApiError(400, "Choose a channel of this server for the announcements.");
                // canTalk, as the announcement job checks (threads need their own send permission, not MESSAGE_SEND)
                if (!target.canTalk()) {
                    throw new ApiError(400, "The bot needs to see and send messages in #" + target.getName() + " to announce the week there.");
                }
            }
            return save(guild.getIdLong(), actor.getIdLong(), sent);
        }
        AdminRouteExtension.requireMethod(method, "GET");
        return overview(guild.getIdLong());
    }

    /** The announcement channel saved now (the hub's), or null for none. */
    private Long savedChannel(long guildId) {
        try {
            String raw = weeks.view(guildId, 1).getString("announceChannelId", null);
            return raw == null || !raw.matches("\\d{1,20}") ? null : Long.parseLong(raw);
        } catch (EventProblem e) {
            return null;
        }
    }

    /** The announcement channel sent, or null for none; a value that isn't a Discord id is refused. */
    static Long channelOf(DataObject body) {
        if (!body.hasKey("announceChannelId") || body.isNull("announceChannelId")) return null;
        String raw = body.get("announceChannelId").toString();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "Choose a channel of this server for the announcements.");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Choose a channel of this server for the announcements.");
        }
    }

    DataObject overview(long guildId) {
        return weeks.view(guildId, 10);
    }

    DataObject save(long guildId, long actorId, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        List<Integer> rota = new ArrayList<>();
        try {
            DataArray raw = body.optArray("rota").orElse(DataArray.empty());
            for (int i = 0; i < raw.length(); i++) rota.add(raw.getInt(i));
        } catch (RuntimeException e) {
            throw new ApiError(400, "Choose each skill from the list.");
        }
        String mode = body.getString("mode", "rota");
        if (!mode.equals("rota") && !mode.equals("vote")) throw new ApiError(400, "Choose the rota or a members' vote.");
        Long channel = channelOf(body);
        List<String> problems = SkillWeekRules.rotaProblems(rota, enabled);
        if (enabled && mode.equals("vote") && rota.size() < 2) problems.add("A vote needs at least two skills to choose from.");
        if (enabled && !weeks.available()) problems.add("Skill of the week counts XP from RuneSync, through the RuneClans hub, and this bot isn't connected to it.");
        if (!problems.isEmpty()) throw new ApiError(400, "Skill of the week can't be saved.", problems);
        DataObject sent = DataObject.empty().put("enabled", enabled).put("rota", DataArray.fromCollection(rota)).put("mode", mode)
                .put("announceChannelId", channel == null ? null : Long.toString(channel)).put("autoEnrol", body.getBoolean("autoEnrol", false));
        DataObject out;
        try {
            out = weeks.save(guildId, actorId, sent); // the hub opens this week straight away when switched on
        } catch (EventProblem e) {
            throw new ApiError(e.status(), e.getMessage(), e.problems());
        }
        log.info("Dashboard: {} saved skill of the week ({}, {} skills, {}, posts {}, auto-enrol {})", actorId, enabled ? "on" : "off", rota.size(), mode,
                channel == null ? "off" : "on", body.getBoolean("autoEnrol", false) ? "on" : "off");
        return out;
    }
}
