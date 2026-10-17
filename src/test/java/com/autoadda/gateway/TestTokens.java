package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

/** Mints tokens the way identity-api's JwtUtil.createToken does (jjwt 0.11.5, HS256). */
final class TestTokens {

    static final String SECRET = "test-only-secret-0123456789-abcdefghijklmnop";

    private TestTokens() {
    }

    static String token(String userId, Set<String> roles, long expiresInMillis) {
        return token(SECRET, userId, roles, expiresInMillis);
    }

    static String token(String secret, String userId, Set<String> roles, long expiresInMillis) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("roles", roles);
        claims.put("userId", userId);
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setClaims(claims)
                .setSubject("user" + userId + "@example.test")
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expiresInMillis))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }

    static String user(String userId) {
        return token(userId, Set.of("ROLE_USER"), 60_000);
    }

    static String admin(String userId) {
        return token(userId, Set.of("ROLE_ADMIN"), 60_000);
    }

    static String expired(String userId) {
        // Past the 30 s clock skew.
        return token(userId, Set.of("ROLE_USER"), -120_000);
    }

    /** An unsigned token claiming to be an admin. */
    static String algNone(String userId) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String body = b64.encodeToString(("{\"sub\":\"x\",\"userId\":\"" + userId + "\",\"roles\":[\"ROLE_ADMIN\"],\"exp\":"
                + (System.currentTimeMillis() / 1000 + 600) + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + body + ".";
    }

    /** A valid token whose payload was swapped for another user's, keeping the old signature. */
    static String tampered(String userId, String otherUserId) {
        String[] good = user(userId).split("\\.");
        String[] other = user(otherUserId).split("\\.");
        return good[0] + "." + other[1] + "." + good[2];
    }
}
