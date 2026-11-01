package com.runeclans.bot.plugin;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets the plugin ingest accept RuneSync uploads signed through RuneLink (RUNESYNC-DESIGN.md section 3, ticket T-200):
 * {@code Authorization: RuneLink <assertion>} (or the older {@code RSLink}), a compact JWS the RuneLink service signed for the plugin "runesync".
 * <ol>
 *   <li>The signature and expiry are checked here, against the link service's public key set ({@code /internal/rsnlink/v1/keys}, cached
 *       10 minutes and fetched again for a key id it hasn't seen).</li>
 *   <li>The link service is then asked, over its internal first-party route {@code POST /internal/rsnlink/resolve}, which Discord account
 *       and RSN the alias belongs to. It checks the assertion again and that the link is still active.</li>
 * </ol>
 * Both calls go over HTTP to the link service's internal contract, never to its classes, so the service can move to its own process by
 * changing {@code LINK_INTERNAL_URL} (default: this bot's own internal API on 127.0.0.1). Answers are cached 60 seconds per assertion, so a
 * plugin sending a batch a minute costs one resolve per batch at most. Assertions and Discord ids are never logged.
 */
@BService
public class RsLinkAuth {
    private static final Logger log = LoggerFactory.getLogger(RsLinkAuth.class);
    /** The {@code Authorization} scheme RuneSync sends its RuneLink assertion with. */
    public static final String SCHEME = "RuneLink ";
    /** The scheme's name before RuneLink became RuneLink; still accepted (T-659). */
    public static final String LEGACY_SCHEME = "RSLink ";
    static final String PLUGIN = "runesync";
    private static final Duration KEYS_FOR = Duration.ofMinutes(10);
    private static final Duration RESOLVED_FOR = Duration.ofSeconds(60);
    /** X.509 SubjectPublicKeyInfo header for a raw 32-byte Ed25519 key. */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    /** Who an assertion speaks for: the Discord account to credit, the linked RSN, and how strongly the link is known. */
    public record Identity(long discordUserId, String rsn, String verification) {}

    /** Calls the link service. Replaceable in tests. */
    interface Transport {
        /** The JSON answer and its status, or null when the service can't be reached. */
        Answer call(String method, String path, String body);
    }

    record Answer(int status, DataObject json) {}

    /** The link service couldn't be reached: the upload should be retried later, not treated as a bad assertion. */
    public static final class Unavailable extends RuntimeException {
        Unavailable() {
            super("RuneLink unavailable", null, false, false);
        }
    }

    private final Verifier verifier;

    public RsLinkAuth(BotConfig config, RuneClansHubClient hub) {
        boolean legacy = !hub.enabled();
        if (legacy) {
            // T-811: players link on runeclans.com, whose links live in the RuneClans hub; without the hub client every RuneSync upload
            // and the clan page fail quietly (panelAvailable=false), so say so once at start.
            log.warn("RuneSync assertions are checked against the bot's own RuneLink keys because the hub client is off (RUNECLANS_HUB_ENABLED, "
                    + "RUNECLANS_HUB_URL, RUNECLANS_HUB_API_KEY). Links made on runeclans.com can't be verified until it is on (docs/DEV-SETUP.md 3c).");
        }
        this.verifier = new Verifier(legacy ? httpTransport(config) : hubTransport(hub), Clock.systemUTC(), legacy);
    }

    /**
     * The same two calls against the RuneClans hub when {@code RUNECLANS_HUB_ENABLED} is on: its public key set at {@code /v1/link/keys} and its
     * first-party {@code /v1/link/resolve}, with JonnyBot's API key instead of the internal secret. The answers have the same shape.
     */
    static Transport hubTransport(RuneClansHubClient hub) {
        return (method, path, body) -> {
            String hubPath = switch (path) {
                case "/internal/rsnlink/v1/keys" -> "/v1/link/keys";
                case "/internal/rsnlink/resolve" -> "/v1/link/resolve";
                default -> null;
            };
            if (hubPath == null) return null;
            RuneClansHubClient.Answer a = hub.call(method, hubPath, body);
            return a == null ? null : new Answer(a.status(), a.json());
        };
    }

    /** The assertion in an {@code Authorization} header, or null when the header isn't the RuneLink scheme (or the older RSLink one). */
    public static String assertionFrom(String authorization) {
        if (authorization == null) return null;
        String scheme = authorization.startsWith(SCHEME) ? SCHEME : authorization.startsWith(LEGACY_SCHEME) ? LEGACY_SCHEME : null;
        if (scheme == null) return null;
        String value = authorization.substring(scheme.length()).strip();
        return value.isEmpty() || value.length() > 4096 ? null : value;
    }

    /** See {@link Verifier#authenticate}. */
    public Optional<Identity> authenticate(String assertion) {
        return verifier.authenticate(assertion);
    }

    /** The checks themselves, apart from the service wiring so tests can give them a fake link service and clock. */
    static final class Verifier {
        private final Transport transport;
        private final Clock clock;
        private volatile Map<String, PublicKey> keys = Map.of();
        private volatile Instant keysFetchedAt = Instant.EPOCH;
        private final ConcurrentHashMap<String, Cached> resolved = new ConcurrentHashMap<>();

        private record Cached(Optional<Identity> identity, Instant until) {}

        /** T-811: how often "an assertion signed with a key this bot doesn't know" is logged while the hub client is off. */
        static final Duration UNKNOWN_KEY_WARN_EVERY = Duration.ofHours(1);
        /** True while assertions are checked against the bot's own keys (no hub client): an unknown key id then likely means a hub link. */
        private final boolean legacy;
        private volatile Instant lastUnknownKeyWarning = null;
        /** How many times the unknown-key warning was logged (for tests). */
        volatile int unknownKeyWarnings;

        Verifier(Transport transport, Clock clock) {
            this(transport, clock, false);
        }

        Verifier(Transport transport, Clock clock, boolean legacy) {
            this.transport = transport;
            this.clock = clock;
            this.legacy = legacy;
        }

        /** T-811: an assertion signed with a key this bot's own link service doesn't have, without the hub client: say why, at most hourly. */
        private void warnUnknownKey(Instant now) {
            if (!legacy) return;
            Instant last = lastUnknownKeyWarning;
            if (last != null && last.plus(UNKNOWN_KEY_WARN_EVERY).isAfter(now)) return;
            lastUnknownKeyWarning = now;
            unknownKeyWarnings++;
            log.warn("A RuneSync upload carried a RuneLink assertion signed with a key the bot's own link service doesn't have, so it was refused. "
                    + "Links made on runeclans.com live in the RuneClans hub: turn the hub client on (RUNECLANS_HUB_ENABLED, RUNECLANS_HUB_URL, "
                    + "RUNECLANS_HUB_API_KEY). Logged at most once an hour.");
        }

        /**
         * Who a RuneSync assertion speaks for, or empty when it is forged, expired, for another plugin, or the link has ended. Throws
         * {@link Unavailable} when the link service can't be reached (nothing is cached then, so the next try asks again).
         */
        public Optional<Identity> authenticate(String assertion) {
            if (assertion == null) return Optional.empty();
            Instant now = clock.instant();
            String cacheKey = HexFormat.of().formatHex(sha256(assertion));
            Cached cached = resolved.get(cacheKey);
            if (cached != null && cached.until().isAfter(now)) return cached.identity();
            if (resolved.size() > 10_000) resolved.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));

            Optional<Identity> identity = verifyLocally(assertion, now) ? resolveRemotely(assertion) : Optional.empty();
            resolved.put(cacheKey, new Cached(identity, now.plus(RESOLVED_FOR)));
            return identity;
        }

        /** Signature, plugin and expiry, checked against the public key set. */
        boolean verifyLocally(String assertion, Instant now) {
            String[] parts = assertion.split("\\.");
            if (parts.length != 3) return false;
            try {
                Base64.Decoder b64 = Base64.getUrlDecoder();
                DataObject header = DataObject.fromJson(b64.decode(parts[0]));
                DataObject claims = DataObject.fromJson(b64.decode(parts[1]));
                if (!"EdDSA".equals(header.getString("alg", null))) return false;
                PublicKey key = key(header.getString("kid", ""), now);
                if (key == null) {
                    warnUnknownKey(now);
                    return false;
                }
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (!verifier.verify(b64.decode(parts[2]))) return false;
                return PLUGIN.equals(claims.getString("plugin", null)) && claims.getLong("exp", 0) > now.getEpochSecond();
            } catch (Unavailable e) {
                throw e;
            } catch (Exception e) {
                return false;
            }
        }

        private Optional<Identity> resolveRemotely(String assertion) {
            Answer answer = transport.call("POST", "/internal/rsnlink/resolve", DataObject.empty().put("assertion", assertion).toString());
            if (answer == null || answer.status() >= 500) {
                log.warn("RuneLink couldn't be reached to resolve a plugin upload");
                throw new Unavailable();
            }
            if (answer.status() != 200 || answer.json() == null) return Optional.empty();
            try {
                DataObject json = answer.json();
                return Optional.of(new Identity(Long.parseLong(json.getString("discordId")), json.getString("rsn"), json.getString("verification", "implicit")));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        /** The public key with this id, refetching the key set when it is old or the id is new (a key rotation). */
        private PublicKey key(String kid, Instant now) {
            PublicKey key = keys.get(kid);
            if (key != null && keysFetchedAt.plus(KEYS_FOR).isAfter(now)) return key;
            if (keysFetchedAt.plusSeconds(5).isAfter(now) && key == null) return null; // don't hammer the service for an unknown id
            Answer answer = transport.call("GET", "/internal/rsnlink/v1/keys", null);
            if (answer == null || answer.status() != 200 || answer.json() == null) {
                if (keys.isEmpty()) throw new Unavailable();
                return key;
            }
            Map<String, PublicKey> fresh = new ConcurrentHashMap<>();
            try {
                DataArray list = answer.json().getArray("keys");
                for (int i = 0; i < list.length(); i++) {
                    DataObject k = list.getObject(i);
                    if (!"OKP".equals(k.getString("kty", null)) || !"Ed25519".equals(k.getString("crv", null))) continue;
                    byte[] raw = Base64.getUrlDecoder().decode(k.getString("x"));
                    if (raw.length != 32) continue;
                    byte[] encoded = new byte[ED25519_X509_PREFIX.length + 32];
                    System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
                    System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, 32);
                    fresh.put(k.getString("kid"), KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
                }
            } catch (Exception e) {
                return key;
            }
            keys = Map.copyOf(fresh);
            keysFetchedAt = now;
            return keys.get(kid);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The link service over HTTP: {@code LINK_INTERNAL_URL}, or this bot's own internal API on 127.0.0.1. */
    private static Transport httpTransport(BotConfig config) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        return (method, path, body) -> {
            String secret = config.getInternalApiSecret();
            if (secret == null) return null;
            String base = config.getLinkSetting("LINK_INTERNAL_URL");
            if (base == null) base = "http://127.0.0.1:" + config.getInternalApiPort();
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path)).timeout(Duration.ofSeconds(5))
                        .header("X-Internal-Secret", secret).header("Content-Type", "application/json");
                request = body == null ? request.GET() : request.method(method, HttpRequest.BodyPublishers.ofString(body));
                HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                DataObject json = null;
                try {
                    json = DataObject.fromJson(response.body());
                } catch (RuntimeException ignored) {
                    // a non-JSON answer counts as no answer body
                }
                return new Answer(response.statusCode(), json);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return null;
            }
        };
    }
}
