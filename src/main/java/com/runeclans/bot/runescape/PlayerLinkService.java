package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@BService
public class PlayerLinkService {
    private static final Logger log = LoggerFactory.getLogger(PlayerLinkService.class);

    public static final String METHOD_MAKEOVER_MAGE = "MAKEOVER_MAGE";
    public static final String METHOD_ADMIN_MANUAL = "ADMIN_MANUAL";

    /** How often a member can click "Poll Now" themselves under {@code /rs} — irrespective of admin or auto polls, which don't touch this clock at all. */
    public static final Duration SELF_POLL_COOLDOWN = Duration.ofMinutes(30);

    private final PlayerLinkRepository repository;
    /** The bot's links in the RuneClans hub (T-839): told after each change commits, sends on its own lane, a no-op while its switch is off. */
    private final HubLinkSync hubLinks;

    public PlayerLinkService(PlayerLinkRepository repository, HubLinkSync hubLinks) {
        this.repository = repository;
        this.hubLinks = hubLinks;
    }

    /** {@link PlayerLinkRepository#createLink}, then the hub told: the name's previous owner (staff moving it) loses it there too. */
    private void createLink(long guildId, long discordUserId, String rsn, String method) {
        PlayerLinkRepository.PlayerAccount before = repository.getAccountForRsn(rsn);
        repository.createLink(guildId, discordUserId, rsn, method);
        if (before != null && before.discordUserId() != discordUserId) hubLinks.unlinked(before.discordUserId(), before.rsn());
        hubLinks.linked(discordUserId, rsn);
    }

    /** Starts a new verification attempt with a freshly-randomized appearance to apply in-game. */
    public VerificationAttempt startVerification(long guildId, long discordUserId, String rsn) {
        MakeoverAppearance appearance = MakeoverAppearance.random();
        long attemptId = repository.createAttempt(guildId, discordUserId, rsn,
                appearance.hairstyle(), appearance.hairColor(), appearance.skinTone());
        return repository.getAttempt(attemptId);
    }

    public VerificationAttempt getAttempt(long attemptId) {
        return repository.getAttempt(attemptId);
    }

    public VerificationAttempt getPendingAttemptForUser(long guildId, long discordUserId) {
        return repository.getPendingAttemptForUser(guildId, discordUserId);
    }

    /** Lets a user abandon their own pending attempt (e.g. a mistyped RSN) without needing an admin. */
    public boolean cancelOwn(long attemptId, long discordUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status()) || attempt.discordUserId() != discordUserId) {
            return false;
        }

        repository.resolveAttempt(attemptId, "CANCELLED", discordUserId);
        return true;
    }

    public List<VerificationAttempt> getPendingAttempts(long guildId) {
        return repository.getPendingAttempts(guildId);
    }

    public void setReviewCard(long attemptId, long channelId, long messageId) {
        repository.setReviewCard(attemptId, channelId, messageId);
    }

    public PlayerLinkRepository.ReviewCard getReviewCard(long attemptId) {
        return repository.getReviewCard(attemptId);
    }

    public PlayerLinkRepository.Resolution getResolution(long attemptId) {
        return repository.getResolution(attemptId);
    }

    /**
     * Closes a pending request because an admin linked that same RSN to the same person by hand
     * ({@link #manualLink}), so it doesn't sit in the queue for someone to approve again. Unlike
     * {@link #approve} it creates no link, which the manual link already did and must not be overwritten.
     * Returns false if the attempt is gone or already resolved.
     */
    public boolean closeAsLinkedByAdmin(long attemptId, long adminUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status())) return false;

        repository.resolveAttempt(attemptId, "APPROVED", adminUserId);
        return true;
    }

    /**
     * Approves the attempt and creates the confirmed link. Returns false if the attempt is gone or already resolved.
     *
     * @throws PlayerLinkRepository.RsnTakenException if the name is already registered to someone else in another server;
     *                                                the request is left pending so nothing is half done
     */
    public boolean approve(long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status())) return false;

        createLink(attempt.guildId(), attempt.discordUserId(), attempt.rsn(), METHOD_MAKEOVER_MAGE);
        repository.resolveAttempt(attemptId, "APPROVED", resolvedByUserId);
        log.info("Verification attempt {} approved by {} — linked '{}' to {}",
                attemptId, resolvedByUserId, attempt.rsn(), attempt.discordUserId());
        return true;
    }

    /** Returns false if the attempt is gone or already resolved. */
    public boolean reject(long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status())) return false;

        repository.resolveAttempt(attemptId, "REJECTED", resolvedByUserId);
        return true;
    }

    /**
     * Links an RSN straight to a Discord user, bypassing the makeover-mage flow entirely — for an
     * admin who already knows a name is theirs and doesn't need the appearance-comparison dance.
     * Same underlying {@code createLink} as approving a real verification attempt, so it overwrites
     * any existing link for that RSN exactly the same way (see {@code PlayerLinkRepository#createLink}'s
     * {@code ON CONFLICT}).
     */
    public void manualLink(long guildId, long discordUserId, String rsn, long adminUserId) throws PlayerLinkRepository.RsnTakenException {
        createLink(guildId, discordUserId, rsn, METHOD_ADMIN_MANUAL);
        log.info("RSN '{}' manually linked to {} by admin {}", rsn, discordUserId, adminUserId);
    }

    /**
     * Repoints a link at a new RSN in place — for a real in-game name change, confirmed either by
     * {@link RsnRenameService} or an admin's manual "Update RSN" action. Unlike unlink+re-verify,
     * this keeps the same {@code link_id} and doesn't touch verification method/timestamp.
     */
    public void renameLink(long guildId, long linkId, String newRsn) {
        PlayerLink before = repository.getAllLinks(guildId).stream().filter(l -> l.linkId() == linkId).findFirst().orElse(null);
        repository.updateRsn(guildId, linkId, newRsn);
        if (before != null && !before.rsn().equals(newRsn)) {
            hubLinks.unlinked(before.discordUserId(), before.rsn());
            hubLinks.linked(before.discordUserId(), newRsn);
        }
    }

    public List<PlayerLink> getLinksForUser(long guildId, long discordUserId) {
        return repository.getLinksForUser(guildId, discordUserId);
    }

    public PlayerLink getLinkForRsn(long guildId, String rsn) {
        return repository.getLinkForRsn(guildId, rsn);
    }

    /** Who owns this RS name with JonnyBot (in any server), or null if nobody has linked it. */
    public PlayerLinkRepository.PlayerAccount getAccountForRsn(String rsn) {
        return repository.getAccountForRsn(rsn);
    }

    /**
     * Registers the accounts this person already linked in another server into this one — what running {@code /rs} here
     * does, because linking belongs to JonnyBot and not to a server. Returns the names that were added.
     */
    public List<String> adoptAccounts(long guildId, long discordUserId) {
        return repository.adoptAccounts(guildId, discordUserId);
    }

    public List<PlayerLink> getAllLinks(long guildId) {
        return repository.getAllLinks(guildId);
    }

    /** Every RS name registered with JonnyBot, in any server. */
    public List<String> getAllAccountRsns() {
        return repository.getAllAccountRsns();
    }

    /** True if this link's own last self-poll was far enough back (or has never happened) to allow another. */
    public boolean canSelfPoll(PlayerLink link) {
        return selfPollCooldownRemaining(link).isZero() || selfPollCooldownRemaining(link).isNegative();
    }

    /** How much longer until this link's self-poll cooldown clears — zero or negative means it's available now. */
    public Duration selfPollCooldownRemaining(PlayerLink link) {
        if (link.lastSelfPollAt() == null) return Duration.ZERO;
        Duration elapsed = Duration.between(link.lastSelfPollAt(), OffsetDateTime.now());
        return SELF_POLL_COOLDOWN.minus(elapsed);
    }

    /** Stamps this link's self-poll clock — call only after an actual self-service poll succeeds. */
    public void recordSelfPoll(long linkId) {
        repository.recordSelfPoll(linkId);
    }

    /**
     * The owner unlinking their own account: it comes off JonnyBot, so out of every server. Returns false if no such
     * link exists for that user (nothing to unlink).
     */
    public boolean unlink(long guildId, long discordUserId, long linkId) {
        List<PlayerLink> links = repository.getLinksForUser(guildId, discordUserId);
        PlayerLink owned = links.stream().filter(link -> link.linkId() == linkId).findFirst().orElse(null);
        if (owned == null) return false;

        repository.deleteAccount(guildId, linkId);
        hubLinks.unlinked(discordUserId, owned.rsn());
        return true;
    }

    /**
     * A server's admin removing a link from their own server. The account stays with its owner and in their other
     * servers; here it stays gone until they link it again on purpose. Returns false if there was no such link.
     */
    public boolean removeFromServer(long guildId, long linkId) {
        List<PlayerLink> links = repository.getAllLinks(guildId);
        if (links.stream().noneMatch(link -> link.linkId() == linkId)) return false;

        repository.deleteLink(guildId, linkId);
        return true;
    }
}
