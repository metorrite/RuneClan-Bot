package com.runeclans.bot.internal;

import com.runeclans.bot.configure.GuildSettings;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.configure.InviteLink;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.runescape.ClanVerificationService;
import com.runeclans.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A server's basic setup for the website: the same settings {@code /configure} edits in Discord (its clan, where link requests
 * are reviewed, and the roles verification hands out). Who counts as staff lives with the permission groups instead
 * ({@link PermissionsAdminApi}), since that is more than one role per level. A freshly installed server starts with
 * none of these, so this is the first thing its owner needs. Reached only through {@link TicketAdminApi}, which has already
 * checked the acting user may use the dashboard in this server and which writes every change to the audit log.
 * <p>
 * Everything in a save is checked before anything is changed, so a refused save changes nothing. Setting a clan is verified
 * exactly like {@code /configure} does it: the clan must exist and the person must be an Admin-or-higher member of it, so a
 * server can't claim someone else's clan.
 */
@BService
public class ServerSetupAdminApi {
    private static final Logger log = LoggerFactory.getLogger(ServerSetupAdminApi.class);

    private final GuildSettingsService settings;
    private final ClanVerificationService clanVerification;
    private final PlayerLinkService links;

    public ServerSetupAdminApi(GuildSettingsService settings, ClanVerificationService clanVerification, PlayerLinkService links) {
        this.settings = settings;
        this.clanVerification = clanVerification;
        this.links = links;
    }

    DataObject get(Guild guild) {
        GuildSettings s = settings.getEffective(guild.getIdLong());
        return DataObject.empty()
                .put("clanName", s.savedClanName())
                .put("clanEnabled", s.clanEnabled())
                .put("clanActive", s.clanActive())
                .put("verificationReviewChannelId", idOrNull(s.verificationReviewChannelId()))
                .put("renameAlertChannelId", idOrNull(s.renameAlertChannelId()))
                .put("verifiedClanRoleId", idOrNull(s.verifiedClanRoleId()))
                .put("verifiedNonClanRoleId", idOrNull(s.verifiedNonClanRoleId()))
                .put("unverifiedRoleId", idOrNull(s.unverifiedRoleId()))
                .put("onboardingRoleId", idOrNull(s.onboardingRoleId()))
                .put("inviteUrl", s.inviteUrl());
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        long guildId = guild.getIdLong();
        GuildSettings current = settings.getEffective(guildId);
        List<String> problems = new ArrayList<>();

        Field clan = text(body, "clanName", problems);
        Field clanEnabled = flag(body, "clanEnabled");
        Field reviewChannel = id(body, "verificationReviewChannelId", problems);
        Field renameChannel = id(body, "renameAlertChannelId", problems);
        Field verifiedClanRole = id(body, "verifiedClanRoleId", problems);
        Field verifiedNonClanRole = id(body, "verifiedNonClanRoleId", problems);
        Field unverifiedRole = id(body, "unverifiedRoleId", problems);
        Field onboardingRole = id(body, "onboardingRoleId", problems);

        String invite = null;
        boolean settingInvite = body.hasKey("inviteUrl");
        if (settingInvite) {
            String raw = body.isNull("inviteUrl") ? "" : body.getString("inviteUrl", "").strip();
            if (!raw.isEmpty()) {
                var cleaned = InviteLink.normalize(raw);
                if (cleaned.isEmpty()) problems.add("Invite link: use a link like https://discord.gg/abc123 or https://discord.com/invite/abc123.");
                else invite = cleaned.get();
            }
        }

        checkRole(guild, "Verified clan member role", verifiedClanRole, true, problems);
        checkRole(guild, "Verified, not in the clan role", verifiedNonClanRole, true, problems);
        checkRole(guild, "Unverified role", unverifiedRole, true, problems);
        checkRole(guild, "Onboarding role", onboardingRole, true, problems);
        checkChannel(guild, "Review channel", reviewChannel, problems);
        checkChannel(guild, "Rename alert channel", renameChannel, problems);

        String newClan = clan.present() ? clan.text() : null;
        boolean settingClan = clan.present() && newClan != null && !newClan.equalsIgnoreCase(current.savedClanName() == null ? "" : current.savedClanName());
        if (settingClan) {
            // the same checks as /configure: it exists, and the person asking is an Admin-or-higher member of it
            links.adoptAccounts(guildId, actor.getIdLong());
            ClanVerificationService.Result result = clanVerification.verify(guildId, actor.getIdLong(), newClan);
            if (!result.allMet()) {
                for (var check : result.checks()) {
                    if (!check.met()) problems.add(check.requirement().replace("**", "") + ": " + check.detail().replace("**", ""));
                }
            }
        }

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved"), problems);

        if (clan.present()) {
            settings.updateClanName(guildId, newClan);
            if (settingClan && !clanEnabled.present()) settings.setClanEnabled(guildId, true);
        }
        if (clanEnabled.present()) settings.setClanEnabled(guildId, clanEnabled.flag());
        if (reviewChannel.present()) settings.updateVerificationSettings(guildId, reviewChannel.value());
        if (renameChannel.present()) settings.updateRenameAlertChannel(guildId, renameChannel.value());
        if (verifiedClanRole.present() || verifiedNonClanRole.present() || unverifiedRole.present()) {
            // the three are stored together, so any the request leaves out keep what the server has now
            settings.updateVerificationRoleSettings(guildId,
                    verifiedClanRole.present() ? verifiedClanRole.value() : current.verifiedClanRoleId(),
                    verifiedNonClanRole.present() ? verifiedNonClanRole.value() : current.verifiedNonClanRoleId(),
                    unverifiedRole.present() ? unverifiedRole.value() : current.unverifiedRoleId());
        }
        if (onboardingRole.present()) settings.updateOnboardingRole(guildId, onboardingRole.value());
        if (settingInvite) settings.updateInviteUrl(guildId, invite);

        log.info("Dashboard: {} saved the server setup (clan {})", actor.getId(), clan.present() ? (newClan == null ? "cleared" : "set") : "unchanged");
        return get(guild);
    }

    // ---------- reading the request ----------

    /** One optional field of a save: absent (leave it alone), present and empty (clear it), or present with a value. */
    record Field(boolean present, Long value, String text, boolean flag) {
        static final Field ABSENT = new Field(false, null, null, false);
    }

    /** A Discord id: absent, JSON null or "" clears it, otherwise digits. */
    static Field id(DataObject body, String key, List<String> problems) {
        if (!body.hasKey(key)) return Field.ABSENT;
        if (body.isNull(key)) return new Field(true, null, null, false);
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return new Field(true, null, null, false);
        if (!raw.matches("\\d{1,20}")) {
            problems.add("A value for " + key + " isn't a valid Discord id.");
            return Field.ABSENT;
        }
        try {
            return new Field(true, Long.parseLong(raw), null, false);
        } catch (NumberFormatException e) {
            problems.add("A value for " + key + " isn't a valid Discord id.");
            return Field.ABSENT;
        }
    }

    /** Free text (the clan name): absent, or present and blank to clear it. Capped at the length RuneScape allows. */
    static Field text(DataObject body, String key, List<String> problems) {
        if (!body.hasKey(key)) return Field.ABSENT;
        String raw = body.isNull(key) ? "" : body.getString(key, "").strip();
        if (raw.length() > 30) {
            problems.add("A clan name is at most 30 characters.");
            return Field.ABSENT;
        }
        return new Field(true, null, raw.isEmpty() ? null : raw, false);
    }

    static Field flag(DataObject body, String key) {
        return body.hasKey(key) ? new Field(true, null, null, body.getBoolean(key, false)) : Field.ABSENT;
    }

    // ---------- checking it against this server ----------

    private static void checkRole(Guild guild, String label, Field field, boolean handedOut, List<String> problems) {
        if (!field.present() || field.value() == null) return;
        Role role = guild.getRoleById(field.value());
        if (role == null) {
            problems.add(label + ": that role doesn't exist in this server.");
        } else if (role.isPublicRole()) {
            problems.add(label + ": @everyone can't be used here.");
        } else if (handedOut && !MemberApi.safeToAssign(guild, role)) {
            // the same bar as every other role the bot hands out (B-55): no managed, higher, powerful or staff-group role
            problems.add(label + ": " + MemberApi.handOutRefusal(guild, role));
        }
    }

    private static void checkChannel(Guild guild, String label, Field field, List<String> problems) {
        if (!field.present() || field.value() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, field.value());
        if (channel == null) problems.add(label + ": that channel doesn't exist in this server.");
        else if (!channel.canTalk()) problems.add(label + ": the bot can't post in #" + channel.getName() + ". It needs permission to view and send messages there.");
    }

    private static String idOrNull(Long id) {
        return id == null ? null : Long.toString(id);
    }
}
