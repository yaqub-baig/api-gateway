package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Verifies the access tokens identity-api issues: HS256 with the shared secret, claims
 * sub, userId, roles, iat, exp. Anything else (other algorithms, "none", missing exp or
 * userId, a userId that is not a number) is rejected.
 */
public class JwtVerifier {

    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,18}");

    /** The parts of a valid token the gateway uses. */
    public record Principal(String userId, List<String> roles) {
    }

    private final MACVerifier verifier;
    private final long skewMillis;
    private final Clock clock;

    /**
     * @throws IllegalArgumentException when the secret is shorter than 256 bits (HS256 minimum,
     *                                  which identity-api's jjwt enforces as well)
     */
    public JwtVerifier(String secret, int clockSkewSeconds, Clock clock) {
        try {
            // identity-api builds its key from secret.getBytes(); same bytes on a UTF-8 JVM.
            this.verifier = new MACVerifier(secret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalArgumentException("gateway.auth.jwt-secret must be at least 32 bytes", e);
        }
        this.skewMillis = clockSkewSeconds * 1000L;
        this.clock = clock;
    }

    /** The principal of a valid token, or empty for anything that does not verify. */
    public Optional<Principal> verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            long now = clock.millis();
            Date exp = claims.getExpirationTime();
            if (exp == null || now >= exp.getTime() + skewMillis) {
                return Optional.empty();
            }
            Date nbf = claims.getNotBeforeTime();
            if (nbf != null && now < nbf.getTime() - skewMillis) {
                return Optional.empty();
            }
            Object rawId = claims.getClaim("userId");
            String userId = rawId == null ? null : rawId.toString();
            if (userId == null || !USER_ID.matcher(userId).matches()) {
                return Optional.empty();
            }
            return Optional.of(new Principal(userId, roles(claims)));
        } catch (ParseException | JOSEException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static List<String> roles(JWTClaimsSet claims) {
        Object raw = claims.getClaim("roles");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                // Roles end up in a header; keep only plain names.
                .filter(r -> r.matches("[A-Za-z0-9_]{1,64}"))
                .toList();
    }
}
