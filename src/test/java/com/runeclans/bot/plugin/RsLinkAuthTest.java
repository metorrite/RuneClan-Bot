package com.runeclans.bot.plugin;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RuneSync uploads signed through RS Link. Keys are generated per run; the link service is a fake that answers like the real routes. */
class RsLinkAuthTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
    private static final String DISCORD = "100000000000000001";

    private KeyPair key;
    private KeyPair otherKey;
    private final AtomicInteger resolves = new AtomicInteger();
    private int resolveStatus = 200;
    private boolean serviceDown = false;
    private RsLinkAuth.Verifier auth;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        key = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
        RsLinkAuth.Transport fake = (method, path, body) -> {
            if (serviceDown) return null;
            if (path.equals("/internal/rsnlink/v1/keys")) {
                byte[] encoded = key.getPublic().getEncoded();
                String x = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
                return new RsLinkAuth.Answer(200, DataObject.empty().put("keys", DataArray.empty().add(DataObject.empty()
                        .put("kty", "OKP").put("crv", "Ed25519").put("kid", "k1").put("x", x))));
            }
            resolves.incrementAndGet();
            if (resolveStatus != 200) return new RsLinkAuth.Answer(resolveStatus, DataObject.empty().put("error", "invalid_assertion"));
            return new RsLinkAuth.Answer(200, DataObject.empty().put("discordId", DISCORD).put("rsn", "Zezima").put("verification", "implicit"));
        };
        auth = new RsLinkAuth.Verifier(fake, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String assertion(KeyPair signer, String kid, String plugin, long exp) throws Exception {
        String header = b64(DataObject.empty().put("alg", "EdDSA").put("kid", kid).toJson());
        String claims = b64(DataObject.empty().put("plugin", plugin).put("alias", "a1_x").put("rsn", "zezima").put("ver", "implicit")
                .put("iat", NOW.getEpochSecond()).put("exp", exp).toJson());
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(signer.getPrivate());
        s.update((header + "." + claims).getBytes(StandardCharsets.US_ASCII));
        return header + "." + claims + "." + b64(s.sign());
    }

    private long later() {
        return NOW.getEpochSecond() + 600;
    }

    @Test
    void theHeaderSchemeIsRecognised() {
        assertEquals("abc.def.ghi", RsLinkAuth.assertionFrom("RSLink abc.def.ghi"));
        assertNull(RsLinkAuth.assertionFrom("Bearer abc"));
        assertNull(RsLinkAuth.assertionFrom(null));
        assertNull(RsLinkAuth.assertionFrom("RSLink "));
        assertEquals("abc.def.ghi", RsLinkAuth.assertionFrom("RuneLink abc.def.ghi"), "the RuneLink scheme");
        assertNull(RsLinkAuth.assertionFrom("RuneLink "));
        assertNull(RsLinkAuth.assertionFrom("RuneLinkabc"));
    }

    @Test
    void aGoodAssertionResolvesToTheLinkedAccountAndIsCached() throws Exception {
        String a = assertion(key, "k1", "runesync", later());
        var identity = auth.authenticate(a).orElseThrow();
        assertEquals(Long.parseLong(DISCORD), identity.discordUserId());
        assertEquals("Zezima", identity.rsn());
        auth.authenticate(a);
        assertEquals(1, resolves.get(), "the second batch within a minute is answered from the cache");
    }

    @Test
    void expiredForgedOrForeignAssertionsAreRefusedWithoutAskingTheService() throws Exception {
        assertTrue(auth.authenticate(assertion(key, "k1", "runesync", NOW.getEpochSecond() - 1)).isEmpty(), "expired");
        assertTrue(auth.authenticate(assertion(otherKey, "k1", "runesync", later())).isEmpty(), "signed by another key");
        assertTrue(auth.authenticate(assertion(key, "k1", "otherplugin", later())).isEmpty(), "made for another plugin");
        assertTrue(auth.authenticate("not.a.jws").isEmpty());
        assertEquals(0, resolves.get());
    }

    @Test
    void anEndedLinkIsRefused() throws Exception {
        resolveStatus = 401;
        assertTrue(auth.authenticate(assertion(key, "k1", "runesync", later())).isEmpty());
    }

    @Test
    void anUnreachableServiceIsNotABadAssertion() throws Exception {
        serviceDown = true;
        String a = assertion(key, "k1", "runesync", later());
        assertThrows(RsLinkAuth.Unavailable.class, () -> auth.authenticate(a));
        serviceDown = false;
        assertEquals("Zezima", auth.authenticate(a).orElseThrow().rsn(), "nothing was cached while it was down");
    }

    @Test
    void withoutTheHubClientAnUnknownKeyIsLoggedAtMostHourly() throws Exception {
        // T-811: a hub-signed assertion checked against the bot's own keys fails; the log says why, once an hour, never silently
        Instant[] now = {NOW};
        Clock moving = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        RsLinkAuth.Transport keysOnly = (method, path, body) -> {
            byte[] encoded = key.getPublic().getEncoded();
            String x = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
            return new RsLinkAuth.Answer(200, DataObject.empty().put("keys", DataArray.empty().add(DataObject.empty()
                    .put("kty", "OKP").put("crv", "Ed25519").put("kid", "k1").put("x", x))));
        };
        RsLinkAuth.Verifier legacy = new RsLinkAuth.Verifier(keysOnly, moving, true);
        assertTrue(legacy.authenticate(assertion(otherKey, "hub-key", "runesync", later())).isEmpty());
        assertEquals(1, legacy.unknownKeyWarnings);
        now[0] = NOW.plus(RsLinkAuth.Verifier.UNKNOWN_KEY_WARN_EVERY).minusSeconds(1);
        assertTrue(legacy.authenticate(assertion(otherKey, "hub-key", "runesync", later() + 7200)).isEmpty());
        assertEquals(1, legacy.unknownKeyWarnings, "not again within the hour");
        now[0] = NOW.plus(RsLinkAuth.Verifier.UNKNOWN_KEY_WARN_EVERY).plusSeconds(1);
        assertTrue(legacy.authenticate(assertion(otherKey, "hub-key", "runesync", later() + 7300)).isEmpty());
        assertEquals(2, legacy.unknownKeyWarnings, "again after it");

        RsLinkAuth.Verifier withHub = new RsLinkAuth.Verifier(keysOnly, moving, false);
        assertTrue(withHub.authenticate(assertion(otherKey, "hub-key", "runesync", later() + 7400)).isEmpty());
        assertEquals(0, withHub.unknownKeyWarnings, "with the hub client an unknown key is just a bad assertion");
    }
}
