package com.runeclans.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Thin client for RuneScape 3's public web endpoints — no API key, no OSRS. Both endpoints are
 * undocumented-but-stable public JSON/image APIs used by the RuneScape website itself; RuneMetrics'
 * own website was retired but its API endpoints keep working as of this writing, so failures are
 * handled as "this data just isn't available right now" rather than something to crash over.
 */
@BService
public class RuneScapeApiClient {
    private static final Logger log = LoggerFactory.getLogger(RuneScapeApiClient.class);

    // activities=N genuinely controls how many recent-activity entries come back (verified live —
    // activities=1 returns exactly 1, activities=20 returns up to 20), not a boolean "include
    // activities" flag as the original value here assumed. 20 is comfortably above what RuneMetrics'
    // own rolling feed tends to hold at once.
    private static final String PROFILE_URL = "https://apps.runescape.com/runemetrics/profile/profile?user=%s&activities=20";
    private static final String AVATAR_URL = "https://secure.runescape.com/m=avatar-rs/%s/chat.png";
    private static final String HISCORES_URL = "https://secure.runescape.com/m=hiscore/index_lite.ws?player=%s";
    private static final String CLAN_HISCORES_URL = "https://secure.runescape.com/m=clan-hiscores/members_lite.ws?clanName=%s";

    // NORMAL is required: every one of these endpoints (avatar image especially) responds with an
    // HTTP redirect rather than the resource directly (verified live), and HttpClient's default
    // policy when unset is Redirect.NEVER — without this, every avatar fetch silently "failed"
    // with a bare 302 and no body, never actually reaching the image.
    /** Clan rosters fetched in the last 90 seconds, by lower-cased clan name; only successful, non-empty fetches are kept. */
    private final com.runeclans.bot.util.TtlCache<String, List<ClanMember>> clanRosterCache = new com.runeclans.bot.util.TtlCache<>(Duration.ofSeconds(90));

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Empty if the player doesn't exist, has their profile set to private, or the request failed — see {@link #fetchProfileResult} to tell those apart. */
    public Optional<RuneScapeProfile> fetchProfile(String rsn) {
        return fetchProfileResult(rsn) instanceof ProfileResult.Found(var profile) ? Optional.of(profile) : Optional.empty();
    }

    // A clan-wide sync means dozens of these requests back to back; verified live during a real
    // 61-member sync that a couple of them hit purely transient network faults — an
    // HttpTimeoutException and an SSLException ("bad_record_mac" tag mismatch, consistent with a
    // stale pooled connection the server had already dropped) — neither of which recurred on a
    // fresh attempt. Both are already caught below (both extend IOException) and were never
    // crashing anything, just silently counting as a failed poll; retrying once, after a brief
    // pause, is what actually recovers them instead of just failing gracefully.
    private static final int MAX_ATTEMPTS = 2;
    private static final Duration RETRY_DELAY = Duration.ofMillis(500);

    private final BooleanSupplier runeMetricsOn = RuneMetricsSwitch.fromEnv();
    private final RuneMetricsBreaker breaker = new RuneMetricsBreaker();
    private final AtomicBoolean loggedSwitchedOff = new AtomicBoolean();

    /**
     * Same fetch as {@link #fetchProfile}, but keeps the reason a failure happened instead of collapsing it to empty. Answers
     * {@link ProfileResult.Unavailable} without a request when RuneMetrics is switched off ({@link RuneMetricsSwitch}) or has just failed
     * many times in a row ({@link RuneMetricsBreaker}).
     */
    public ProfileResult fetchProfileResult(String rsn) {
        if (!runeMetricsOn.getAsBoolean()) {
            if (loggedSwitchedOff.compareAndSet(false, true)) {
                log.warn("RuneMetrics is switched off (RUNEMETRICS=off): profile polls now answer 'unavailable' without asking.");
            }
            return new ProfileResult.Unavailable();
        }
        if (!breaker.allow(System.currentTimeMillis())) return new ProfileResult.Unavailable();
        ProfileResult result = fetchProfileResultUncounted(rsn);
        if (result instanceof ProfileResult.Unavailable) {
            if (breaker.recordFailure(System.currentTimeMillis())) {
                log.warn("RuneMetrics failed {} times in a row; pausing profile polls for {} minutes, then trying one.",
                        RuneMetricsBreaker.FAILURES_TO_OPEN, RuneMetricsBreaker.PAUSE_MS / 60_000);
            }
        } else if (breaker.recordAnswer()) {
            log.info("RuneMetrics is answering again; profile polls resume.");
        }
        return result;
    }

    private ProfileResult fetchProfileResultUncounted(String rsn) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return attemptFetchProfile(rsn);
            } catch (IOException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("Failed to fetch RuneMetrics profile for '{}' (retry also failed)", rsn, e);
                    return new ProfileResult.Unavailable();
                }
                log.info("Transient error fetching RuneMetrics profile for '{}' ({}) — retrying once", rsn, e.getClass().getSimpleName());
                try {
                    Thread.sleep(RETRY_DELAY.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new ProfileResult.Unavailable();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Failed to fetch RuneMetrics profile for '{}'", rsn, e);
                return new ProfileResult.Unavailable();
            } catch (Exception e) {
                // Not retried — a malformed response won't be fixed by asking again.
                log.warn("Failed to parse RuneMetrics profile for '{}'", rsn, e);
                return new ProfileResult.Unavailable();
            }
        }
        return new ProfileResult.Unavailable(); // unreachable (the loop always returns), kept for the compiler
    }

    private ProfileResult attemptFetchProfile(String rsn) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(PROFILE_URL.formatted(encode(rsn))))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
            Duration retryAfter = parseRetryAfter(response);
            log.info("RuneMetrics profile request for '{}' was rate-limited (HTTP 429){}", rsn,
                    retryAfter != null ? ", server asked for " + retryAfter : "");
            return new ProfileResult.RateLimited(retryAfter);
        }
        if (response.statusCode() / 100 != 2) {
            log.warn("RuneMetrics profile request for '{}' returned HTTP {}", rsn, response.statusCode());
            return new ProfileResult.Unavailable();
        }

        DataObject json = DataObject.fromJson(response.body());
        if (json.hasKey("error")) {
            String error = json.getString("error", "unknown");
            log.info("RuneMetrics profile for '{}' unavailable: {}", rsn, error);
            return switch (error) {
                case "PROFILE_PRIVATE" -> new ProfileResult.Private();
                case "NO_PROFILE" -> new ProfileResult.NotFound();
                default -> new ProfileResult.Unavailable();
            };
        }

        List<SkillValue> skills = new ArrayList<>();
        DataArray skillArray = json.getArray("skillvalues");
        for (int i = 0; i < skillArray.length(); i++) {
            DataObject skill = skillArray.getObject(i);
            // RuneMetrics reports each skill's xp at 10x true XP (verified live: a level-99-capped
            // skill's true XP is exactly 200,000,000, but this field reads 2,000,000,000 for the
            // same skill at the same moment — confirmed exact-10x on two independently-capped
            // skills, cross-referenced against the classic hiscores' true value for the same
            // account). totalxp at the top level is NOT affected, only these per-skill values.
            skills.add(new SkillValue(skill.getInt("id"), skill.getInt("level"), skill.getLong("xp") / 10, skill.getInt("rank")));
        }

        List<PlayerActivity> activities = new ArrayList<>();
        if (json.hasKey("activities")) {
            DataArray activityArray = json.getArray("activities");
            for (int i = 0; i < activityArray.length(); i++) {
                DataObject activity = activityArray.getObject(i);
                activities.add(new PlayerActivity(
                        activity.getString("date", ""),
                        activity.getString("text", ""),
                        activity.getString("details", "")));
            }
        }

        return new ProfileResult.Found(new RuneScapeProfile(
                json.getString("name"),
                json.getInt("totalskill"),
                json.getLong("totalxp"),
                json.getInt("combatlevel"),
                json.getInt("questscomplete"),
                json.getInt("questsstarted"),
                json.getInt("questsnotstarted"),
                skills,
                activities
        ));
    }

    /**
     * The player's current chat-head avatar (reflects their actual in-game customization, not
     * achievements or activity) — see {@link AvatarResult} for the three possible outcomes. An
     * invalid/unrecognized RSN redirects to the same shared placeholder as a real account that's
     * never set a custom look, so those two report as the same {@link AvatarResult.NotCustomized}
     * outcome — this method can't tell them apart, and callers should say so rather than imply the
     * name was necessarily wrong.
     */
    public AvatarResult fetchAvatarImage(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AVATAR_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                log.warn("Avatar image request for '{}' returned HTTP {}", rsn, response.statusCode());
                return new AvatarResult.Unavailable();
            }

            if (response.uri().getPath().endsWith("default_chat.png")) {
                return new AvatarResult.NotCustomized();
            }

            return new AvatarResult.Found(response.body());

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch avatar image for '{}'", rsn, e);
            return new AvatarResult.Unavailable();
        }
    }

    /**
     * The "Overall" line plus all 29 individual skill lines from the classic hiscores CSV — a
     * separate, older endpoint from RuneMetrics, kept independently toggleable by players, so it's
     * a useful fallback for a player whose RuneMetrics profile is private but hiscores aren't.
     * <p>
     * Line order (0 = Overall, 1-29 = skills in {@link RuneScapeSkillCatalog} order) confirmed live
     * by cross-referencing a real account's per-line rank against RuneMetrics' {@code skillvalues}
     * ranks for the same account — every one of the 29 skills matched by rank in sequence, not
     * assumed from documentation. Lines after the 30th (minigames/bosses/clue scrolls) aren't
     * parsed — that list is far less stable and wasn't verified with the same confidence.
     */
    public Optional<HiscoresOverall> fetchHiscoresOverall(String rsn) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(HISCORES_URL.formatted(encode(rsn))))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Hiscores request for '{}' returned HTTP {}", rsn, response.statusCode());
                return Optional.empty();
            }

            List<String> lines = response.body().lines().toList();
            if (lines.isEmpty()) return Optional.empty();

            String[] overallParts = lines.getFirst().split(",");
            if (overallParts.length < 3) return Optional.empty();

            long rank = Long.parseLong(overallParts[0].trim());
            int totalLevel = Integer.parseInt(overallParts[1].trim());
            long totalXp = Long.parseLong(overallParts[2].trim());

            // -1 is the hiscores sentinel for "unranked" (also used for a nonexistent player).
            if (rank < 0) return Optional.empty();

            List<SkillValue> skills = new ArrayList<>();
            int skillLines = Math.min(RuneScapeSkillCatalog.skillCount(), lines.size() - 1);
            for (int skillId = 0; skillId < skillLines; skillId++) {
                String[] parts = lines.get(skillId + 1).split(",");
                if (parts.length < 3) continue;

                int skillRank = Integer.parseInt(parts[0].trim());
                int skillLevel = Integer.parseInt(parts[1].trim());
                long skillXp = Long.parseLong(parts[2].trim());
                skills.add(new SkillValue(skillId, skillLevel, skillXp, skillRank));
            }

            return Optional.of(new HiscoresOverall(rank, totalLevel, totalXp, skills));

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch hiscores for '{}'", rsn, e);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse hiscores for '{}'", rsn, e);
            return Optional.empty();
        }
    }

    /** One row of the Clan Hiscores' member roster — see {@link #fetchClanRoster}. */
    public record ClanMember(String rsn, String clanRank, long totalXp, long kills) {}

    /**
     * The clan's current member roster — verified live against clan "Younglings" (CSV: {@code
     * Clanmate, Clan Rank, Total XP, Kills}). No join dates: nothing in this response, the classic
     * hiscores, or the public clan website surfaces when a member joined — that's only visible
     * in-game. {@code kills} came back {@code 0} for every member tested, so it may no longer be
     * populated by Jagex; kept anyway since it costs nothing to parse.
     * <p>
     * Empty if the clan doesn't exist or the request failed — not something to crash a sync over.
     */
    public List<ClanMember> fetchClanRoster(String clanName) {
        // Two servers tracking the same clan hit this within moments of each other at the daily sync (and a self-refresh can follow
        // it), so a roster fetched in the last minute and a half is reused instead of asking Jagex again.
        String key = clanName.strip().toLowerCase(java.util.Locale.ROOT);
        var cached = clanRosterCache.get(key);
        if (cached.isPresent()) return cached.get();
        List<ClanMember> roster = fetchClanRosterUncached(clanName);
        if (!roster.isEmpty()) clanRosterCache.put(key, roster);
        return roster;
    }

    private List<ClanMember> fetchClanRosterUncached(String clanName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CLAN_HISCORES_URL.formatted(encode(clanName))))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            // ISO-8859-1, not the default UTF-8 — the response declares no charset (verified live:
            // "Content-Type: text/comma-separated-values", nothing else), and the name-space byte
            // (0xA0) is invalid standalone UTF-8. Decoded as UTF-8 it silently becomes U+FFFD before
            // this method ever sees it, which would make the   replacement below never match.
            // ISO-8859-1 maps every byte 1:1 to the same-numbered code point, so 0xA0 comes through
            // as the real U+00A0 non-breaking space.
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.ISO_8859_1));
            if (response.statusCode() / 100 != 2) {
                log.warn("Clan hiscores request for '{}' returned HTTP {}", clanName, response.statusCode());
                return List.of();
            }
            if (!isRosterAnswer(response.headers().firstValue("Content-Type").orElse(""))) return List.of();

            List<String> lines = response.body().lines().toList();
            if (lines.size() < 2) return List.of();

            List<ClanMember> members = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) { // line 0 is the header row
                String[] parts = lines.get(i).split(",");
                if (parts.length < 4) continue;

                // Jagex encodes spaces in clan member names as U+00A0 (non-breaking space), not a
                // plain space — verified live in the raw response for clan "Younglings".
                String rsn = parts[0].replace(' ', ' ').trim();
                members.add(new ClanMember(rsn, parts[1].trim(),
                        Long.parseLong(parts[2].trim()), Long.parseLong(parts[3].trim())));
            }
            return members;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to fetch clan roster for '{}'", clanName, e);
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to parse clan roster for '{}'", clanName, e);
            return List.of();
        }
    }

    /**
     * Jagex answers a clan name it doesn't know with a redirect to its ranking page, which the client follows (verified live 2026-10-29:
     * 302 to {@code /m=clan-hiscores/ranking}, then 200 {@code text/html}), so an HTML answer means "no such clan", never rows to parse.
     */
    static boolean isRosterAnswer(String contentType) {
        return !contentType.toLowerCase(java.util.Locale.ROOT).contains("html");
    }

    /** Only the plain-seconds form of {@code Retry-After} is parsed — the alternative HTTP-date form is rare enough on this kind of endpoint that it's not worth the extra parsing surface; a miss just falls back to the poll coordinator's own default backoff. */
    private static Duration parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Long.parseLong(value.trim()));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
    }

    private static String encode(String rsn) {
        return URLEncoder.encode(rsn, StandardCharsets.UTF_8);
    }
}
