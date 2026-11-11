package com.runeclans.bot.internal;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A member's own look in one server, as the website changes it: the nickname and the name-colour role. Shared by the home site's
 * {@code /internal/member}, {@code /internal/nickname} and {@code /internal/color-role} ({@link InternalApiServer}) and a clan site's
 * {@code sites/{slug}/me/server-profile|nickname|color-role} ({@link DirectoryClanSiteMemberRoutes}, T-369), so the rules are the same in
 * both. The user id always comes from the caller's verified login session; every call acts on that one member in the one server given.
 * <p>
 * A change is checked here and then queued as a Discord job (T-456), so the website answers at once; {@link NicknameJob} and
 * {@link ColorRoleJob} make it in Discord, waiting out rate limits, and the website follows the job's state.
 */
final class ServerProfile {
    static final int MAX_NICKNAME_LENGTH = 32; // Discord's own limit

    private ServerProfile() {}

    /** A refusal the website shows as it is. */
    static final class Refused extends RuntimeException {
        final int status;

        Refused(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Cache-first, then a one-off REST fetch on a miss: the bot caches only online members, and these calls are rare website actions, so
     * the occasional extra round trip is fine. {@code null} if the user isn't in the server at all; {@link DiscordLookups.Slow} (a 503) when
     * Discord didn't answer in time.
     */
    static Member resolveMember(Guild guild, long userId) {
        return DiscordLookups.memberOrSlow(guild, userId);
    }

    /** The member's names, avatar and current colour key. */
    static DataObject info(Member member) {
        String colorRoleKey = null;
        for (Role role : member.getRoles()) {
            String key = ColorRoleCatalog.keyFromRoleName(role.getName());
            if (key != null) {
                colorRoleKey = key;
                break;
            }
        }
        return DataObject.empty()
                .put("id", member.getId())
                .put("username", member.getUser().getName())
                .put("nickname", member.getNickname())
                .put("displayName", member.getEffectiveName())
                .put("avatarUrl", member.getEffectiveAvatarUrl())
                .put("colorRole", colorRoleKey);
    }

    static final String NICKNAME_JOB = "member.nickname";
    static final String COLOR_ROLE_JOB = "member.color-role";

    /**
     * Queues setting (or with a blank or null {@code nickname}, clearing) the member's nickname. Answers the nickname asked for and the job
     * that makes it; a nickname change still waiting is replaced by this one.
     */
    static DataObject setNickname(DiscordOutbox outbox, Guild guild, Member member, DataObject body) {
        String nickname = body.isNull("nickname") ? null : body.getString("nickname").trim();
        if (nickname != null && nickname.isEmpty()) nickname = null;
        if (nickname != null && nickname.length() > MAX_NICKNAME_LENGTH) {
            throw new Refused(400, "Nickname must be " + MAX_NICKNAME_LENGTH + " characters or fewer");
        }
        long guildId = guild.getIdLong();
        DiscordJob job = outbox.enqueue(NICKNAME_JOB, guildId, member.getIdLong(), "nickname:" + guildId + ":" + member.getIdLong(),
                DataObject.empty().put("userId", Long.toString(member.getIdLong())).put("nickname", nickname));
        return DataObject.empty().put("nickname", nickname).put("job", job.ticket());
    }

    /** Queues swapping the member's colour role for {@code color} (a {@link ColorRoleCatalog} key), or removing it for null. */
    static DataObject setColorRole(DiscordOutbox outbox, Guild guild, Member member, DataObject body) {
        String colorKey = body.isNull("color") ? null : body.getString("color");
        if (colorKey != null && !ColorRoleCatalog.isValidKey(colorKey)) throw new Refused(400, "Unknown color: " + colorKey);
        if (colorKey != null) {
            // B-03: an existing "Color: <x>" role is reused, so it must pass the same bar as every other role the bot hands out
            String refusal = existingColorRoleRefusal(guild, colorKey);
            if (refusal != null) throw new Refused(403, "That colour can't be picked here. " + refusal);
        }
        DiscordJob job = outbox.enqueue(COLOR_ROLE_JOB, guild.getIdLong(), member.getIdLong(), "color-role:" + guild.getIdLong() + ":" + member.getIdLong(),
                DataObject.empty().put("userId", Long.toString(member.getIdLong())).put("color", colorKey));
        return DataObject.empty().put("colorRole", colorKey).put("job", job.ticket());
    }

    /** The job's half: sets the nickname in Discord. */
    static void applyNickname(Member member, String nickname) {
        DiscordJobs.send(member.modifyNickname(nickname));
    }

    /** The job's half: swaps the colour role in Discord, creating the role if the server doesn't have it yet. */
    static void applyColorRole(Guild guild, Member member, String colorKey) {
        List<Role> toRemove = new ArrayList<>();
        for (Role role : member.getRoles()) {
            if (ColorRoleCatalog.isColorRoleName(role.getName()) && (colorKey == null || !role.getName().equalsIgnoreCase(ColorRoleCatalog.roleName(colorKey)))
                    && MemberApi.safeToAssign(guild, role)) {
                toRemove.add(role);
            }
        }
        List<Role> toAdd = new ArrayList<>();
        if (colorKey != null) {
            Role wanted = ensureColorRole(guild, colorKey);
            if (!member.getRoles().contains(wanted)) toAdd.add(wanted);
        }
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) DiscordJobs.send(guild.modifyMemberRoles(member, toAdd, toRemove));
    }

    /** Why the server's existing role for {@code colorKey} can't be handed out, or null (also when there is none yet: a new one is made safe). */
    static String existingColorRoleRefusal(Guild guild, String colorKey) {
        List<Role> existing = guild.getRolesByName(ColorRoleCatalog.roleName(colorKey), true);
        return existing.isEmpty() ? null : MemberApi.handOutRefusal(guild, existing.getFirst());
    }

    private static Role ensureColorRole(Guild guild, String colorKey) {
        String roleName = ColorRoleCatalog.roleName(colorKey);
        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) {
            // checked again here: the role may have been moved or given permissions since the change was queued
            String refusal = MemberApi.handOutRefusal(guild, existing.getFirst());
            if (refusal != null) throw new DiscordJobs.Refused(refusal);
            return existing.getFirst();
        }
        return DiscordJobs.send(guild.createRole()
                .setName(roleName)
                .setColor(ColorRoleCatalog.colorFor(colorKey))
                .setMentionable(false)
                .setHoisted(false));
    }
}
