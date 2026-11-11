package com.runeclans.bot.internal;

import com.runeclans.bot.autorole.AutoroleConfig;
import com.runeclans.bot.autorole.AutoroleRepository;
import com.runeclans.bot.autorole.AutoroleService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's autoroles for the website. Everything in a save is checked before anything is changed, so a refused save changes
 * nothing and says why; only ordinary roles the bot may hand out are accepted. Reached only through {@link TicketAdminApi}, which
 * has already checked the asker may use the dashboard in this server and which audit-logs every change.
 */
@BService
public class AutoroleAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AutoroleAdminApi.class);

    private final AutoroleRepository repository;

    public AutoroleAdminApi(AutoroleRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("autoroles")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        AutoroleConfig config = repository.get(guild.getIdLong());
        DataArray issues = DataArray.empty();
        for (long id : config.memberRoleIds()) addIssue(guild, "People", id, issues);
        for (long id : config.botRoleIds()) addIssue(guild, "Bots", id, issues);
        for (long id : config.memberRemoveRoleIds()) addRemovalIssue(guild, "People (take away)", id, issues);
        for (long id : config.botRemoveRoleIds()) addRemovalIssue(guild, "Bots (take away)", id, issues);
        return DataObject.empty().put("enabled", config.enabled()).put("delaySeconds", config.delaySeconds())
                .put("memberRoleIds", strings(config.memberRoleIds())).put("botRoleIds", strings(config.botRoleIds()))
                .put("memberRemoveRoleIds", strings(config.memberRemoveRoleIds())).put("botRemoveRoleIds", strings(config.botRemoveRoleIds()))
                .put("maxRoles", AutoroleConfig.MAX_ROLES).put("maxDelaySeconds", AutoroleConfig.MAX_DELAY_SECONDS).put("issues", issues);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        AutoroleConfig current = repository.get(guild.getIdLong());

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        int delay = body.hasKey("delaySeconds") ? body.getInt("delaySeconds", 0) : current.delaySeconds();
        if (delay < 0 || delay > AutoroleConfig.MAX_DELAY_SECONDS) problems.add("The delay can be at most 7 days.");
        List<Long> memberRoles = body.hasKey("memberRoleIds") ? roles(guild, body.getArray("memberRoleIds"), "People", problems) : current.memberRoleIds();
        List<Long> botRoles = body.hasKey("botRoleIds") ? roles(guild, body.getArray("botRoleIds"), "Bots", problems) : current.botRoleIds();

        List<Long> memberRemove = body.hasKey("memberRemoveRoleIds") ? removals(guild, body.getArray("memberRemoveRoleIds"), "People (take away)", problems) : current.memberRemoveRoleIds();
        List<Long> botRemove = body.hasKey("botRemoveRoleIds") ? removals(guild, body.getArray("botRemoveRoleIds"), "Bots (take away)", problems) : current.botRemoveRoleIds();
        for (long id : memberRemove) if (memberRoles.contains(id)) problems.add("People: a role can't be both given and taken away.");
        for (long id : botRemove) if (botRoles.contains(id)) problems.add("Bots: a role can't be both given and taken away.");

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_autoroles_can_t_be_saved"), problems);

        repository.save(new AutoroleConfig(guild.getIdLong(), enabled, delay, memberRoles, botRoles, memberRemove, botRemove));
        log.info("Dashboard: {} saved the autoroles ({}, {} for people, {} for bots, delay {}s)", actor.getId(), enabled ? "on" : "off", memberRoles.size(), botRoles.size(), delay);
        return get(guild);
    }

    /** Roles to take away from joiners: any ordinary role the bot is allowed to manage (it need not be safe to hand out). */
    private static List<Long> removals(Guild guild, DataArray raw, String who, List<String> problems) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            String text = raw.getString(i, "").strip();
            if (!text.matches("\\d{1,20}")) {
                problems.add(who + ": a role id isn't valid.");
                continue;
            }
            ids.add(Long.parseLong(text));
        }
        if (ids.size() > AutoroleConfig.MAX_ROLES) problems.add(who + ": at most " + AutoroleConfig.MAX_ROLES + " roles.");
        for (long id : ids) {
            Role role = guild.getRoleById(id);
            if (role == null) problems.add(who + ": a chosen role doesn't exist in this server.");
            else if (!AutoroleService.canTakeAway(guild, role)) problems.add(who + ": @" + role.getName() + " can't be taken away (it is @everyone, managed by an integration, or above the bot's role).");
        }
        return new ArrayList<>(ids);
    }

    private static void addRemovalIssue(Guild guild, String who, long id, DataArray issues) {
        Role role = guild.getRoleById(id);
        if (role == null) issues.add(who + ": a chosen role was deleted, so it is skipped.");
        else if (!AutoroleService.canTakeAway(guild, role)) issues.add(who + ": @" + role.getName() + " can't be taken away right now, so it is skipped.");
    }

    private static List<Long> roles(Guild guild, DataArray raw, String who, List<String> problems) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            String text = raw.getString(i, "").strip();
            if (!text.matches("\\d{1,20}")) {
                problems.add(who + ": a role id isn't valid.");
                continue;
            }
            ids.add(Long.parseLong(text));
        }
        if (ids.size() > AutoroleConfig.MAX_ROLES) problems.add(who + ": at most " + AutoroleConfig.MAX_ROLES + " roles.");
        for (long id : ids) {
            Role role = guild.getRoleById(id);
            if (role == null) problems.add(who + ": a chosen role doesn't exist in this server.");
            else if (!MemberApi.safeToAssign(guild, role)) problems.add(who + ": " + MemberApi.handOutRefusal(guild, role));
        }
        return new ArrayList<>(ids);
    }

    private static void addIssue(Guild guild, String who, long id, DataArray issues) {
        Role role = guild.getRoleById(id);
        if (role == null) issues.add(who + ": a chosen role was deleted, so it is skipped.");
        else if (!MemberApi.safeToAssign(guild, role)) issues.add(who + ": @" + role.getName() + " can't be handed out right now, so it is skipped.");
    }

    private static DataArray strings(List<Long> ids) {
        DataArray array = DataArray.empty();
        for (long id : ids) array.add(Long.toString(id));
        return array;
    }
}
