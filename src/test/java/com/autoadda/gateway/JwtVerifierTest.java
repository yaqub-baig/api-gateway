package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class JwtVerifierTest {

    private final JwtVerifier verifier = new JwtVerifier(TestTokens.SECRET, 30, Clock.systemUTC());

    @Test
    void acceptsTokensMintedLikeIdentityApi() {
        JwtVerifier.Principal p = verifier.verify(TestTokens.admin("42")).orElseThrow();
        assertEquals("42", p.userId());
        assertEquals(List.of("ROLE_ADMIN"), p.roles());
    }

    @Test
    void rejectsForgedExpiredAndMalformedTokens() {
        assertTrue(verifier.verify(TestTokens.token("wrong-secret-0123456789-abcdefghijklmnop", "42",
                Set.of("ROLE_ADMIN"), 60_000)).isEmpty(), "wrong secret");
        assertTrue(verifier.verify(TestTokens.algNone("42")).isEmpty(), "alg none");
        assertTrue(verifier.verify(TestTokens.tampered("42", "7")).isEmpty(), "tampered payload");
        assertTrue(verifier.verify(TestTokens.expired("42")).isEmpty(), "expired");
        assertTrue(verifier.verify("not-a-jwt").isEmpty(), "garbage");
        assertTrue(verifier.verify("").isEmpty(), "empty");
    }

    @Test
    void allowsSmallClockSkew() {
        // Expired 10 s ago, inside the 30 s skew.
        assertTrue(verifier.verify(TestTokens.token("42", Set.of("ROLE_USER"), -10_000)).isPresent());
    }

    @Test
    void userIdMustBeNumeric() {
        assertTrue(verifier.verify(TestTokens.token("1\r\nX-Evil: 1", Set.of(), 60_000)).isEmpty());
        assertTrue(verifier.verify(TestTokens.token("abc", Set.of(), 60_000)).isEmpty());
    }

    @Test
    void oddRoleNamesAreDropped() {
        JwtVerifier.Principal p = verifier.verify(
                TestTokens.token("42", Set.of("ROLE_USER", "bad,role", "x\ny"), 60_000)).orElseThrow();
        assertEquals(List.of("ROLE_USER"), p.roles());
    }

    @Test
    void shortSecretIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new JwtVerifier("too-short", 30, Clock.systemUTC()));
    }
}
