package com.runeclans.bot.signup;

import com.runeclans.bot.commands.signup.SignupEntry;
import com.runeclans.bot.commands.signup.SignupMessage;
import com.runeclans.bot.commands.signup.SignupSession;
import com.runeclans.bot.commands.signup.SignupType;

import java.time.Instant;
import java.util.List;

/**
 * Where {@code SignupService} keeps signups (runeclans-hub PHASE-3 F3): the bot's own tables ({@link SignupRepository}) or the RuneClans hub
 * ({@code hubcommunity.HubSignupStore}), picked per call by the {@code hub-community} switch. Signups are named by whole numbers either way
 * (Discord buttons carry them); members by Discord user id. The messages showing a signup are Discord's and stay with the bot either way.
 */
public interface SignupStore {
    /** The hub's side (the hub runs its own upkeep, so the bot's sweep stands down). */
    default boolean hub() {
        return false;
    }

    long createSignup(long guildId, String title, SignupType type, String notificationMessage, String submissionFieldLabel, Integer maxSignups,
                      long createdByUserId, Long groupRoleId);

    void updateGroupRoleId(long signupId, long roleId);

    List<SignupSession> getOpenSignups();

    SignupSession getOpenSignupById(long signupId);

    List<SignupSession> getInactiveSignups(Instant cutoff);

    int purgeDeletedBefore(Instant cutoff);

    String getSignupStatus(long signupId);

    void deleteSignupForAdminArchive(long signupId);

    void setSignupStatus(long signupId, String status);

    void softDeleteSignup(long signupId);

    List<SignupSession> getVisibleSignups(long guildId);

    List<SignupSession> searchVisibleSignups(long guildId, String query);

    int getEntryCount(long signupId);

    boolean addEntry(long signupId, long discordUserId, String rsn, String submissionValue, long addedByUserId, SignupType type);

    /**
     * {@link #addEntry} on a sheet with a cap: false, adding nothing, when it already holds {@code maxEntries} (null = no cap). The bot's own
     * tables check the count under the same lock as the insert, so two joins for the last place can't both get it (B-39); the hub checks the
     * cap itself on every join, so this default (a count first, then the add) only saves it a refused call.
     */
    default boolean addEntry(long signupId, long discordUserId, String rsn, String submissionValue, long addedByUserId, SignupType type, Integer maxEntries) {
        if (maxEntries != null && getEntryCount(signupId) >= maxEntries) return false;
        return addEntry(signupId, discordUserId, rsn, submissionValue, addedByUserId, type);
    }

    boolean removeEntryByUserId(long signupId, long discordUserId);

    List<SignupEntry> getEntries(long signupId);

    SignupEntry getFirstEntry(long signupId);

    SignupEntry next(long signupId);

    SignupEntry removeFirst(long signupId);

    SignupEntry skipFirst(long signupId);

    void clearEntries(long signupId);

    void saveMessage(long signupId, long guildId, long channelId, long messageId, String messageType);

    List<SignupMessage> getActiveMessages(long signupId);

    List<SignupMessage> getActivePingMessages(long signupId);

    SignupMessage getFirstActivePublicMessage(long signupId);

    void markMessageInactive(long messageId);
}
