package com.runeclans.bot.internal;

import com.runeclans.bot.commands.signup.SignupPanelType;
import com.runeclans.bot.commands.signup.SignupService;
import com.runeclans.bot.commands.signup.SignupType;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobRepository;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * The Discord side of a signup sheet made on the dashboard ({@link CommunityAdminApi#createSignup}), run by the outbox so the page never waits
 * on Discord and can follow the result (bug hunt B-112, the signup half of B-77/B-81). {@code signup.create} makes a group sheet's role,
 * stores the sheet, then posts its public panel and its admin panel. When Discord certainly refuses the public panel, the sheet (and its
 * role) is removed again, so no open sheet is ever left without a message. When a step has to wait (a rate limit), what is already done goes
 * into the job's payload (the role, the sheet), and the next try carries on from there, so waiting leaves nothing behind twice. Not
 * idempotent: after an unclear failure a second run could post twice, so the outbox never runs it again by itself.
 */
@BService
public class SignupJobs implements DiscordJobHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SignupJobs.class);
    static final String CREATE = "signup.create";
    /** Payload keys: the sheet and the group role a try already made, once it has. */
    static final String SIGNUP_ID = "signupId", ROLE_ID = "roleId";
    /** Payload key: the public panel's message, once a try posted it. */
    static final String PUBLIC_POSTED = "publicMessageId";

    private final SignupService signups;
    private final SiteCache cache;
    private final DiscordJobRepository jobs;

    public SignupJobs(SignupService signups, SiteCache cache, DiscordJobRepository jobs) {
        this.signups = signups;
        this.cache = cache;
        this.jobs = jobs;
    }

    @Override
    public String kind() {
        return CREATE;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    /** Everything a sheet needs, checked by the API first. {@code fields} is the serialized questions of a submission sheet, else null. */
    static DataObject createPayload(SignupType type, long signupChannelId, long adminChannelId, String title, String note, Integer max, String fields) {
        return DataObject.empty().put("type", type.name()).put("signupChannelId", Long.toString(signupChannelId))
                .put("adminChannelId", Long.toString(adminChannelId)).put("title", title).put("note", note).put("max", max).put("fields", fields);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        DataObject p = job.payload();
        Long keptSignup = p.isNull(SIGNUP_ID) ? null : Long.parseLong(p.getString(SIGNUP_ID));
        Long keptRole = p.isNull(ROLE_ID) ? null : Long.parseLong(p.getString(ROLE_ID));
        Guild guild;
        try {
            guild = DiscordJobs.guild(jda, job);
        } catch (DiscordJobs.Refused | DiscordJobs.RetryLater e) {
            // this job ends here, or waits once more: what an earlier try made goes again once the job can't finish, unless it is live
            if (e instanceof DiscordJobs.Refused || DiscordJobs.lastWait(job)) undoIfNotLive(jda, job, p, keptSignup, keptRole);
            throw e;
        }
        SignupType type = SignupType.valueOf(p.getString("type"));
        String title = p.getString("title");

        // a kept sheet that is gone (removed after an earlier refusal, then sent again with Retry) is not posted: this run starts afresh. A
        // failed read throws (the job fails as unclear) rather than counting as gone, so a sheet that may be live is never made twice
        if (keptSignup != null && signups.isGone(keptSignup)) {
            keptSignup = null;
            keptRole = null;
            p.remove(SIGNUP_ID).remove(ROLE_ID).remove(PUBLIC_POSTED);
        }
        // a kept role with no sheet yet that is gone (the try that made it took it down again): a new one is made
        if (keptSignup == null && keptRole != null && guild.getRoleById(keptRole) == null) {
            keptRole = null;
            p.remove(ROLE_ID);
        }

        boolean publicLive = keptSignup != null && publicPosted(p, keptSignup);
        long signupId;
        Message message = null;
        if (publicLive) {
            signupId = keptSignup;
        } else {
            TextChannel signupChannel;
            try {
                signupChannel = postable(guild, p.getLong("signupChannelId"));
                postable(guild, p.getLong("adminChannelId")); // both are checked before anything is made
            } catch (DiscordJobs.Refused e) {
                undoIfNotLive(jda, job, p, keptSignup, keptRole);
                throw e;
            }

            Long roleId = keptRole;
            if (type == SignupType.GROUP && roleId == null) {
                Role role = DiscordJobs.send(guild.createRole().setName(title.length() > 100 ? title.substring(0, 100) : title).setMentionable(true));
                roleId = role.getIdLong();
                keep(job, p.put(ROLE_ID, Long.toString(roleId)), () -> {
                    deleteRole(guild, role.getIdLong());
                    p.remove(ROLE_ID);
                });
            }

            if (keptSignup != null) {
                signupId = keptSignup;
            } else {
                final Long madeRole = roleId;
                try {
                    signupId = signups.storeSignup(guild.getIdLong(), type, title, p.getString("note", null), p.getString("fields", null),
                            p.isNull("max") ? null : p.getInt("max"), job.actorId(), roleId);
                } catch (RuntimeException e) {
                    if (madeRole != null) {
                        deleteRole(guild, madeRole);
                        forgetRole(job, p);
                    }
                    throw e;
                }
                keep(job, p.put(SIGNUP_ID, Long.toString(signupId)), () -> {
                    signups.discard(signupId);
                    if (madeRole != null) deleteRole(guild, madeRole);
                });
            }

            try {
                message = signups.postPanel(signupId, signupChannel, SignupPanelType.PUBLIC, DiscordJobs::send);
            } catch (RuntimeException e) {
                if (e instanceof DiscordJobs.RetryLater && !DiscordJobs.lastWait(job)) {
                    log.debug("Signup {} '{}' waits to be posted: {}", signupId, title, e.getMessage());
                } else if (DiscordJobs.certainlyNotSent(e)) {
                    signups.discard(signupId);
                    if (roleId != null) deleteRole(guild, roleId);
                } else {
                    log.warn("Signup {} '{}' may or may not have been posted in #{}; it stays", signupId, title, signupChannel.getName(), e);
                }
                throw e;
            }
            // named in the payload too, so a later try never posts it again even if the message couldn't be recorded with the sheet
            try {
                jobs.savePayload(job.id(), DataObject.fromJson(p.put(PUBLIC_POSTED, message.getId()).toJson()));
            } catch (RuntimeException e) {
                log.warn("Signup {} was posted but its job {} couldn't note it", signupId, job.id(), e);
            }
        }

        // the sheet is live once its public panel is: a missing admin panel (its channel gone or muted, or Discord refusing it) is said on the
        // page, never a failed job, and never takes the live sheet down; only a rate limit waits for it, and the next try posts it alone
        boolean adminPosted = true;
        if (!signups.hasPanel(signupId, SignupPanelType.ADMIN)) {
            TextChannel adminChannel = guild.getTextChannelById(p.getLong("adminChannelId"));
            if (adminChannel == null || !adminChannel.canTalk()) {
                log.warn("Signup {} is live but its admin channel is gone or muted for the bot", signupId);
                adminPosted = false;
            } else {
                try {
                    signups.postPanel(signupId, adminChannel, SignupPanelType.ADMIN, DiscordJobs::send);
                } catch (DiscordJobs.RetryLater e) {
                    if (!DiscordJobs.lastWait(job)) throw e;
                    adminPosted = false;
                } catch (RuntimeException e) {
                    log.warn("Signup {} was posted but its admin panel in #{} wasn't", signupId, adminChannel.getName(), e);
                    adminPosted = false;
                }
            }
        }
        cache.invalidate("signups");
        log.info("Dashboard: {} made a {} signup {} '{}' (job {})", job.actorId(), type, signupId, title, job.id());
        DataObject result = DataObject.empty().put("signupId", Long.toString(signupId)).put("channelId", p.getString("signupChannelId")).put("adminPanel", adminPosted);
        if (message != null) result.put("messageId", message.getId()).put("jumpUrl", message.getJumpUrl());
        return result;
    }

    /** Whether the sheet's public panel is out: the job noted it, or the sheet has it recorded. */
    private boolean publicPosted(DataObject p, long signupId) {
        return !p.isNull(PUBLIC_POSTED) || signups.hasPanel(signupId, SignupPanelType.PUBLIC);
    }

    /**
     * Takes down what earlier tries made when this job can't finish, but never a sheet whose public panel is out: members may have joined
     * it and hold its role. When that can't be read, nothing is taken down.
     */
    private void undoIfNotLive(JDA jda, DiscordJob job, DataObject p, Long keptSignup, Long keptRole) {
        if (keptSignup == null && keptRole == null) return;
        try {
            if (keptSignup != null && publicPosted(p, keptSignup)) return;
        } catch (RuntimeException e) {
            log.warn("Couldn't tell whether signup {} is live; leaving it", keptSignup, e);
            return;
        }
        if (keptSignup != null) signups.discard(keptSignup);
        if (keptRole != null) deleteRole(jda, job.guildId(), keptRole);
    }

    /** Drops a role this try took down from the stored payload, so Retry makes a new one instead of naming a deleted role. Best effort. */
    private void forgetRole(DiscordJob job, DataObject p) {
        try {
            jobs.savePayload(job.id(), DataObject.fromJson(p.remove(ROLE_ID).toJson()));
        } catch (RuntimeException e) {
            log.warn("Couldn't drop a deleted role from signup job {}", job.id(), e);
        }
    }

    private static TextChannel postable(Guild guild, long channelId) {
        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + " any more.");
        return channel;
    }

    /** Names what this try made in the job's payload, so a later try carries on from it; when that can't be saved, undoes it and stops. */
    private void keep(DiscordJob job, DataObject payload, Runnable undo) {
        boolean saved;
        try {
            saved = jobs.savePayload(job.id(), DataObject.fromJson(payload.toJson()));
        } catch (RuntimeException e) {
            undo.run(); // nothing was posted yet; a later try would not know what this one made
            throw e;
        }
        if (!saved) {
            // the job is no longer running here (its claim expired): another try would make it all a second time
            undo.run();
            throw new IllegalStateException("Signup job " + job.id() + " stopped running before what it made could be named");
        }
    }

    private void deleteRole(JDA jda, long guildId, long roleId) {
        Guild guild = com.runeclans.bot.discord.Shards.guild(jda, guildId);
        if (guild != null) deleteRole(guild, roleId);
    }

    /** Takes down a group role whose sheet never got posted. Only logged when it fails: the post's error matters more. */
    private static void deleteRole(Guild guild, long roleId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) return;
        try {
            DiscordJobs.send(role.delete());
        } catch (RuntimeException e) {
            log.warn("Couldn't delete group role {} after its signup wasn't posted", roleId, e);
        }
    }
}
