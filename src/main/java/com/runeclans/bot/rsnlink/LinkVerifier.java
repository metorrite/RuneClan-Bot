package com.runeclans.bot.rsnlink;

import java.util.Map;
import java.util.Optional;

/**
 * A check that can raise a link above {@link Verification#IMPLICIT} (LINK-SYSTEM.md section 12). The service runs every registered
 * verifier after a link is made, on each {@code /v1/session} and on each {@code /v1/xp} report; the strongest result wins and is stored with
 * its evidence. The hub verifies links now, so the bot registers none.
 * <p>
 * A verifier must be quick or do its work in the background and report later through the {@link Raiser} it is given; it must never call out
 * to anything while a plugin request waits. It sees only what the service already holds about the link, never a token, code or Discord id.
 */
public interface LinkVerifier {
    /** A short name stored with the evidence, e.g. {@code "xp_first_seen"}. */
    String method();

    /** A result when this check can vouch for the link now, otherwise empty. */
    Optional<VerificationResult> check(LinkContext context);

    /** Whether the plugin should send XP reports for a link at this level ({@code /v1/session} tells the plugin). Default: no. */
    default boolean wantsXpReports(Verification current) {
        return false;
    }

    /** Called once by the service: lets a verifier that works in the background raise a link later. */
    default void attach(Raiser raiser) {}

    /** Small, non-personal state of this check for one link (shown to the player and the owner). Empty when the verifier has none. */
    default Map<String, Object> describe(String linkId) {
        return Map.of();
    }

    /** Service-wide counts for the owner's abuse page. Empty when the verifier has none. */
    default Map<String, Object> overview() {
        return Map.of();
    }

    /** How a background verifier raises a link. The service stores the result and settles any dispute the link is in. */
    interface Raiser {
        void raise(String linkId, VerificationResult result);
    }

    /**
     * What a verifier may look at. {@code report} is what the plugin just sent: null right after linking, {@code {"kind": "session"}} on a
     * login, or {@code {"kind": "xp", "state", "xp10": Map<Integer, Long>}} for an XP report.
     */
    record LinkContext(String linkId, String characterId, String rsnLookup, String rsnDisplay, boolean hasTrackingId, Verification current,
                       java.util.Map<String, Object> report) {}

    /**
     * A verifier's verdict.
     *
     * @param level    the level it vouches for (never {@link Verification#IMPLICIT})
     * @param evidence small JSON describing what was matched, stored in {@code link.verification_data}; no personal data beyond the link's own
     */
    record VerificationResult(Verification level, String method, String evidence) {}
}
