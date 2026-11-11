package com.runeclans.bot.discord.jobs;

import com.runeclans.bot.discord.ShardRouting;
import com.runeclans.bot.discord.Shards;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.requests.RestAction;

import java.time.Duration;
import java.time.Instant;

/**
 * The outbox's rules for talking to Discord (T-456): how a job sends a request, and what happens after one goes wrong. Pure, so the tests can
 * check every case without Discord.
 */
public final class DiscordJobs {
    /** Tries for a fault that may pass (Discord 5xx, a dropped connection) before a job ends as failed. */
    public static final int MAX_ATTEMPTS = 6;
    /** Rate-limit waits are Discord saying "later", not a failure; a job waits out at most this many before it gives up. */
    public static final int MAX_RATE_LIMIT_WAITS = 30;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    static final Duration MIN_RATE_LIMIT_WAIT = Duration.ofMillis(500);

    private DiscordJobs() {}

    /** A reason trying again won't fix, in words for the person who asked. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /** Discord (or the job) says: not now, try again after {@code wait}. */
    public static final class RetryLater extends RuntimeException {
        public final Duration wait;

        public RetryLater(Duration wait, String message) {
            super(message, null, false, false);
            this.wait = wait;
        }
    }

    /**
     * Sends one request without queueing behind a rate limit: when Discord's bucket for it is empty, JDA throws instead of waiting, and this
     * turns that into {@link RetryLater} so the job shows "retrying" and its lane stays free for other jobs.
     */
    public static <T> T send(RestAction<T> action) {
        try {
            return action.complete(false);
        } catch (RateLimitedException e) {
            throw rateLimited(Duration.ofMillis(e.getRetryAfter()));
        }
    }

    /**
     * The job's server; refuses only once the bot has certainly left it ({@link ShardRouting#gone}), and waits while it can't tell: Discord
     * has the server unavailable, or its shard isn't connected (yet) in this process (T-696).
     */
    public static Guild guild(JDA jda, DiscordJob job) {
        Guild guild = Shards.guild(jda, job.guildId());
        if (guild != null) return guild;
        if (Shards.unavailable(jda, job.guildId())) throw new RetryLater(Duration.ofSeconds(30), "That server is unavailable on Discord right now; trying again soon.");
        if (ShardRouting.gone(jda, job.guildId())) throw new Refused("The bot isn't in that server any more.");
        throw new RetryLater(Duration.ofSeconds(30), "The bot is still connecting to that server; trying again soon.");
    }

    /** A member of the job's server: from the cache, else asked of Discord (the job may wait; a website request never does). */
    public static Member member(Guild guild, long userId) {
        Member cached = guild.getMemberById(userId);
        return cached != null ? cached : send(guild.retrieveMemberById(userId));
    }

    /** Whether a {@link RetryLater} from this try of {@code job} ends it as failed: it has already waited as often as a job may. */
    public static boolean lastWait(DiscordJob job) {
        return job.waits() >= MAX_RATE_LIMIT_WAITS;
    }

    static RetryLater rateLimited(Duration wait) {
        long seconds = Math.max(1, (wait.toMillis() + 999) / 1000);
        return new RetryLater(wait, "Discord asked the bot to wait " + seconds + " s; it will try again by itself.");
    }

    /** What happens to a job after a try threw. */
    public sealed interface Next permits Retry, Fail {}

    public record Retry(Instant at, String reason, boolean rateLimit) implements Next {}

    public record Fail(String reason) implements Next {}

    /** The decision after try number {@code attempts} (1 for the first) threw {@code error}. */
    public static Next after(Throwable error, int attempts, int rateLimitWaits, boolean idempotent, Instant now) {
        Throwable e = unwrap(error);
        if (e instanceof Refused) return new Fail(e.getMessage());
        if (e instanceof RetryLater later) {
            if (rateLimitWaits >= MAX_RATE_LIMIT_WAITS) return new Fail("Discord kept asking the bot to wait, so this was given up. Try again later.");
            Duration wait = later.wait.compareTo(MIN_RATE_LIMIT_WAIT) < 0 ? MIN_RATE_LIMIT_WAIT : later.wait;
            return new Retry(now.plus(wait), later.getMessage(), true);
        }
        if (e instanceof RateLimitedException limited) return after(rateLimited(Duration.ofMillis(limited.getRetryAfter())), attempts, rateLimitWaits, idempotent, now);
        if (e instanceof InsufficientPermissionException missing) {
            return new Fail("The bot is missing the " + missing.getPermission().getName() + " permission for this. A server admin can give it in Discord.");
        }
        if (e instanceof HierarchyException) {
            return new Fail("The bot's own role is below the role or person this changes. Move the bot's role higher in Server Settings > Roles.");
        }
        if (e instanceof ErrorResponseException response) {
            if (response.getErrorResponse() != null && response.getErrorCode() > 0 && !response.isServerError()) return new Fail(refusal(response));
            if (!idempotent) return new Fail("Discord had a problem with this, so it may or may not have happened. Check Discord before trying again.");
        } else if (!idempotent) {
            return new Fail("The bot lost touch with Discord while doing this, so it may or may not have happened. Check Discord before trying again.");
        }
        if (attempts >= MAX_ATTEMPTS) return new Fail("Discord didn't answer after " + attempts + " tries. Try again in a few minutes.");
        return new Retry(now.plus(backoff(attempts)), "Discord didn't answer; trying again soon.", false);
    }

    /** 2 s, 4 s, 8 s … at most 5 minutes. */
    static Duration backoff(int attempts) {
        int doublings = Math.max(0, Math.min(attempts - 1, 20));
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    /** Discord's own "no", said plainly. */
    static String refusal(ErrorResponseException e) {
        return switch (e.getErrorResponse()) {
            case MISSING_PERMISSIONS -> "The bot doesn't have permission to do this in that server or channel.";
            case MISSING_ACCESS -> "The bot can't see that channel.";
            case UNKNOWN_MEMBER -> "That person isn't in the server any more.";
            case UNKNOWN_USER -> "Discord doesn't know that user.";
            case UNKNOWN_ROLE -> "That role no longer exists.";
            case UNKNOWN_CHANNEL -> "That channel no longer exists.";
            case UNKNOWN_GUILD -> "The bot isn't in that server any more.";
            case CANNOT_SEND_TO_USER -> "That person doesn't accept direct messages from the bot.";
            default -> "Discord refused this: " + e.getMeaning();
        };
    }

    private static Throwable unwrap(Throwable error) {
        Throwable e = error;
        while ((e instanceof java.util.concurrent.CompletionException || e instanceof java.util.concurrent.ExecutionException) && e.getCause() != null) e = e.getCause();
        return e;
    }
}
