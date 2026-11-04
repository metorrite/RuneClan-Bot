package com.runeclans.bot.runeclans;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Writes the hub couldn't take right now (runeclans-hub PLATFORM-DESIGN 9.4): a button click that writes (join an event, vote) is held here,
 * in memory only, for up to {@link #MAX_AGE} and sent again by {@link #drain}; the member sees "saved, will sync shortly". After
 * {@link #MAX_AGE}, or when {@link #MAX_SIZE} writes already wait, the member is asked to try again instead. Nothing is written to the bot's
 * database. A write the hub answered with a refusal (any 4xx but 429) is never retried: that answer is final.
 */
public final class HubWriteQueue {
    private static final Logger log = LoggerFactory.getLogger(HubWriteQueue.class);
    static final int MAX_SIZE = 500;
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    /** What happened to a write: sent (with the hub's answer), held for later, or refused (try again later, or the hub said no). */
    public enum Outcome { SENT, QUEUED, REFUSED }

    public record Result(Outcome outcome, RuneClansHubClient.Answer answer) {}

    /**
     * One write: method, path and JSON body, plus who to tell how it ended once it is sent or dropped ({@code done}, may be null). Writes
     * sharing a {@code supersedeKey} (one member's answer to one item, such as their vote in one poll) replace each other: a newer one drops
     * any older one still held, so a held write can never land after a newer one and undo it. Null: never replaced.
     */
    public record Write(String method, String path, String body, Consumer<Result> done, String supersedeKey) {
        public Write(String method, String path, String body, Consumer<Result> done) {
            this(method, path, body, done, null);
        }
    }

    private record Held(Write write, Instant queuedAt) {}

    private final RuneClansHubClient.Transport transport;
    private final Clock clock;
    private final int maxSize;
    private final Deque<Held> held = new ArrayDeque<>();

    HubWriteQueue(RuneClansHubClient.Transport transport, Clock clock, int maxSize) {
        this.transport = transport;
        this.clock = clock;
        this.maxSize = maxSize;
    }

    /** Sends {@code write} now; when the hub can't take it, holds it if there is room. */
    Result submit(Write write) {
        List<Held> replaced = write.supersedeKey() == null ? List.of() : dropHeld(write.supersedeKey());
        for (Held h : replaced) tell(h.write(), new Result(Outcome.REFUSED, null));
        RuneClansHubClient.Answer answer = transport.call(write.method(), write.path(), write.body());
        if (!HubRetry.worthRetrying(answer)) return new Result(answer.ok() ? Outcome.SENT : Outcome.REFUSED, answer);
        synchronized (held) {
            if (held.size() >= maxSize) return new Result(Outcome.REFUSED, answer);
            held.addLast(new Held(write, clock.instant()));
        }
        return new Result(Outcome.QUEUED, answer);
    }

    /**
     * Sends what is held, oldest first, stopping at the first one the hub still can't take (it is down; the rest would fail too). Writes
     * older than {@link #MAX_AGE} are dropped and their owners told. Returns how many were sent.
     */
    int drain() {
        int sent = 0;
        List<Held> expired = new ArrayList<>();
        while (true) {
            Held next;
            synchronized (held) {
                Instant now = clock.instant();
                while (!held.isEmpty() && Duration.between(held.peekFirst().queuedAt(), now).compareTo(MAX_AGE) > 0) expired.add(held.pollFirst());
                next = held.peekFirst();
            }
            if (next == null) break;
            RuneClansHubClient.Answer answer = transport.call(next.write().method(), next.write().path(), next.write().body());
            if (HubRetry.worthRetrying(answer)) break;
            synchronized (held) {
                held.remove(next);
            }
            tell(next.write(), new Result(answer.ok() ? Outcome.SENT : Outcome.REFUSED, answer));
            if (answer.ok()) sent++;
        }
        for (Held h : expired) tell(h.write(), new Result(Outcome.REFUSED, null));
        if (!expired.isEmpty()) log.info("RuneClans hub write queue: dropped {} writes older than {} minutes", expired.size(), MAX_AGE.toMinutes());
        return sent;
    }

    /** Takes the held writes a newer one with this key replaces. */
    private List<Held> dropHeld(String key) {
        List<Held> out = new ArrayList<>();
        synchronized (held) {
            held.removeIf(h -> {
                boolean same = key.equals(h.write().supersedeKey());
                if (same) out.add(h);
                return same;
            });
        }
        return out;
    }

    int size() {
        synchronized (held) {
            return held.size();
        }
    }

    private static void tell(Write write, Result result) {
        if (write.done() == null) return;
        try {
            write.done().accept(result);
        } catch (RuntimeException e) {
            log.warn("A hub write callback failed: {}", e.getClass().getSimpleName());
        }
    }
}
