package com.runeclans.bot.internal;

import com.runeclans.bot.chatlevel.ChatLevelConfig;
import com.runeclans.bot.chatlevel.ChatLevelRepository;
import com.runeclans.bot.chatlevel.ChatLevelRepository.Standing;
import com.runeclans.bot.chatlevel.ChatLevels;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * A server's chat levels for the website: settings with the level roles, the leaderboard, and resetting XP. A save is checked whole before anything is stored.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class ChatLevelAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ChatLevelAdminApi.class);
    static final int MAX_ROLES = 25;

    private final ChatLevelRepository repository;

    public ChatLevelAdminApi(ChatLevelRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("chat-levels")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("top")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return top(guild);
        }
        if (parts.length == 2 && parts[1].equals("reset")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return reset(guild, actor, body.get());
        }
        return null;
    }

    DataObject get(Guild guild) {
        ChatLevelConfig c = repository.config(guild.getIdLong());
        DataArray roles = DataArray.empty();
        repository.roles(guild.getIdLong()).forEach((level, role) -> roles.add(DataObject.empty().put("level", level).put("roleId", Long.toString(role))));
        return DataObject.empty().put("enabled", c.enabled()).put("minXp", c.minXp()).put("maxXp", c.maxXp()).put("cooldownSeconds", c.cooldownSeconds())
                .put("announce", c.announce()).put("announceChannelId", c.announceChannelId() == null ? null : Long.toString(c.announceChannelId())).put("announceMessage", c.announceMessage())
                .put("removeOldRoles", c.removeOldRoles()).put("ignoredChannels", ids(c.ignoredChannels())).put("ignoredRoles", ids(c.ignoredRoles()))
                .put("channelMultipliers", multipliers(c.channelMultipliers(), "channelId")).put("roleMultipliers", multipliers(c.roleMultipliers(), "roleId"))
                .put("levelRoles", roles).put("members", repository.countMembers(guild.getIdLong()))
                .put("limits", DataObject.empty().put("maxXp", ChatLevelConfig.MAX_XP).put("minCooldown", ChatLevelConfig.MIN_COOLDOWN).put("maxCooldown", ChatLevelConfig.MAX_COOLDOWN)
                        .put("maxMultiplier", ChatLevelConfig.MAX_MULTIPLIER).put("maxList", ChatLevelConfig.MAX_LIST).put("maxRoles", MAX_ROLES).put("maxMessage", ChatLevelConfig.MAX_MESSAGE)
                        .put("maxLevel", ChatLevels.MAX_LEVEL));
    }

    private static DataArray ids(Set<Long> ids) {
        return DataArray.fromCollection(ids.stream().map(String::valueOf).toList());
    }

    private static DataArray multipliers(Map<Long, Double> map, String key) {
        DataArray array = DataArray.empty();
        map.forEach((id, m) -> array.add(DataObject.empty().put(key, Long.toString(id)).put("multiplier", m)));
        return array;
    }

    private static Set<Long> idList(DataObject body, String key, Set<Long> current, boolean roles, Guild guild, String label, List<String> problems) {
        if (!body.hasKey(key)) return current;
        Set<Long> out = new LinkedHashSet<>();
        DataArray input = body.getArray(key);
        if (input.length() > ChatLevelConfig.MAX_LIST) problems.add("At most " + ChatLevelConfig.MAX_LIST + " " + label + " can be listed.");
        for (int i = 0; i < input.length() && i < ChatLevelConfig.MAX_LIST; i++) {
            String raw = input.getString(i, "").strip();
            boolean exists = raw.matches("\\d{1,20}") && (roles ? guild.getRoleById(Long.parseLong(raw)) != null : guild.getGuildChannelById(Long.parseLong(raw)) != null);
            if (!exists) problems.add("A listed " + label.replaceAll("s$", "") + " doesn't exist in this server.");
            else out.add(Long.parseLong(raw));
        }
        return out;
    }

    private static Map<Long, Double> multiplierList(DataObject body, String key, String idKey, Map<Long, Double> current, boolean roles, Guild guild, String label, List<String> problems) {
        if (!body.hasKey(key)) return current;
        Map<Long, Double> out = new LinkedHashMap<>();
        DataArray input = body.getArray(key);
        if (input.length() > ChatLevelConfig.MAX_LIST) problems.add("At most " + ChatLevelConfig.MAX_LIST + " " + label + " multipliers can be set.");
        for (int i = 0; i < input.length() && i < ChatLevelConfig.MAX_LIST; i++) {
            DataObject row = input.getObject(i);
            String raw = row.getString(idKey, "").strip();
            double m = row.getDouble("multiplier", -1);
            boolean exists = raw.matches("\\d{1,20}") && (roles ? guild.getRoleById(Long.parseLong(raw)) != null : guild.getGuildChannelById(Long.parseLong(raw)) != null);
            if (!exists) problems.add("A " + label + " with a multiplier doesn't exist in this server.");
            else if (!(m >= 0 && m <= ChatLevelConfig.MAX_MULTIPLIER)) problems.add("A multiplier must be between 0 and " + ChatLevelConfig.MAX_MULTIPLIER + " (0 earns nothing).");
            else out.put(Long.parseLong(raw), Math.round(m * 100) / 100.0);
        }
        return out;
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        ChatLevelConfig current = repository.config(guild.getIdLong());
        List<String> problems = new ArrayList<>();

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        int min = body.hasKey("minXp") ? body.getInt("minXp", -1) : current.minXp();
        int max = body.hasKey("maxXp") ? body.getInt("maxXp", -1) : current.maxXp();
        if (min < 1 || max > ChatLevelConfig.MAX_XP || max < min) problems.add("XP per message must be between 1 and " + ChatLevelConfig.MAX_XP + ", and the most can't be below the least.");
        int cooldown = body.hasKey("cooldownSeconds") ? body.getInt("cooldownSeconds", -1) : current.cooldownSeconds();
        if (cooldown < ChatLevelConfig.MIN_COOLDOWN || cooldown > ChatLevelConfig.MAX_COOLDOWN) problems.add("The wait between XP awards must be between " + ChatLevelConfig.MIN_COOLDOWN + " seconds and an hour.");
        boolean announce = body.hasKey("announce") ? body.getBoolean("announce", false) : current.announce();
        boolean removeOld = body.hasKey("removeOldRoles") ? body.getBoolean("removeOldRoles", false) : current.removeOldRoles();
        String message = body.hasKey("announceMessage") ? body.getString("announceMessage", "").strip() : current.announceMessage();
        if (message.length() > ChatLevelConfig.MAX_MESSAGE) problems.add("The level-up message can be at most " + ChatLevelConfig.MAX_MESSAGE + " characters.");

        Long announceChannel = current.announceChannelId();
        if (body.hasKey("announceChannelId")) {
            String raw = body.isNull("announceChannelId") ? "" : body.getString("announceChannelId", "").strip();
            if (raw.isEmpty()) {
                announceChannel = null;
            } else {
                GuildMessageChannel channel = raw.matches("\\d{1,20}") ? guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw)) : null;
                if (channel == null) problems.add("The level-up channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("The bot can't post in #" + channel.getName() + ".");
                else announceChannel = channel.getIdLong();
            }
        }

        Set<Long> ignoredChannels = idList(body, "ignoredChannels", current.ignoredChannels(), false, guild, "channels", problems);
        Set<Long> ignoredRoles = idList(body, "ignoredRoles", current.ignoredRoles(), true, guild, "roles", problems);
        Map<Long, Double> channelMult = multiplierList(body, "channelMultipliers", "channelId", current.channelMultipliers(), false, guild, "channel", problems);
        Map<Long, Double> roleMult = multiplierList(body, "roleMultipliers", "roleId", current.roleMultipliers(), true, guild, "role", problems);

        Map<Integer, Long> rewards = null;
        if (body.hasKey("levelRoles")) {
            rewards = new TreeMap<>();
            DataArray input = body.getArray("levelRoles");
            if (input.length() > MAX_ROLES) problems.add("At most " + MAX_ROLES + " level roles can be set.");
            for (int i = 0; i < input.length() && i < MAX_ROLES; i++) {
                DataObject row = input.getObject(i);
                int level = row.getInt("level", -1);
                String raw = row.getString("roleId", "").strip();
                Role role = raw.matches("\\d{1,20}") ? guild.getRoleById(Long.parseLong(raw)) : null;
                if (level < 1 || level > ChatLevels.MAX_LEVEL) problems.add("A level role's level must be between 1 and " + ChatLevels.MAX_LEVEL + ".");
                else if (role == null) problems.add("The role for level " + level + " doesn't exist in this server.");
                else if (!MemberApi.safeToAssign(guild, role)) problems.add("Level " + level + ": " + MemberApi.handOutRefusal(guild, role));
                else if (rewards.containsKey(level)) problems.add("Level " + level + " has two roles; give each level one.");
                else rewards.put(level, role.getIdLong());
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.chat_levels_can_t_be_saved"), problems);

        repository.saveConfig(new ChatLevelConfig(guild.getIdLong(), enabled, min, max, cooldown, announce, announceChannel, message, removeOld, ignoredChannels, ignoredRoles, channelMult, roleMult));
        if (rewards != null) repository.saveRoles(guild.getIdLong(), rewards);
        log.info("Dashboard: {} saved chat levels ({}, {}-{} XP, {}s)", actor.getId(), enabled ? "on" : "off", min, max, cooldown);
        return get(guild);
    }

    DataObject top(Guild guild) {
        List<Standing> standings = repository.top(guild.getIdLong(), 20);
        // the cache first, then one lookup for the rest, waited on for a limited time
        Map<Long, String> names = standings.isEmpty() ? Map.of() : CitadelAdminApi.memberNames(guild, standings.stream().map(Standing::userId).toList());
        DataArray rows = DataArray.empty();
        int rank = 1;
        for (Standing s : standings) {
            int level = ChatLevels.levelOf(s.xp());
            rows.add(DataObject.empty().put("rank", rank++).put("userId", Long.toString(s.userId())).put("name", names.getOrDefault(s.userId(), "Former member"))
                    .put("level", level).put("xp", s.xp()).put("messages", s.messages()));
        }
        return DataObject.empty().put("top", rows).put("members", repository.countMembers(guild.getIdLong()));
    }

    DataObject reset(Guild guild, Member actor, DataObject body) {
        if (body.hasKey("userId") && !body.isNull("userId")) {
            String raw = body.getString("userId", "").strip();
            if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_member"));
            boolean gone = repository.reset(guild.getIdLong(), Long.parseLong(raw));
            log.info("Dashboard: {} reset the chat XP of {} ({})", actor.getId(), raw, gone ? "had XP" : "had none");
        } else if (body.getBoolean("all", false)) {
            int removed = repository.resetAll(guild.getIdLong());
            log.info("Dashboard: {} reset everyone's chat XP ({} members)", actor.getId(), removed);
        } else {
            throw new ApiError(400, Text.of(guild, "dashboard.say_whose_xp_to_reset_or"));
        }
        return top(guild);
    }
}
