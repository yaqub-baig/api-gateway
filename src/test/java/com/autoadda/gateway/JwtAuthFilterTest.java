package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.autoadda.gateway.GatewayProperties.Auth;
import com.autoadda.gateway.GatewayProperties.Auth.Mode;

class JwtAuthFilterTest {

    private static GatewayProperties props(Mode mode, String secret, List<String> publicPaths) {
        GatewayProperties.Docs docs = new GatewayProperties.Docs(false, "docs", "", List.of(), 60, 3,
                DataSize.ofMegabytes(5));
        Auth auth = new Auth(mode, secret, publicPaths, List.of(), 30, false);
        return new GatewayProperties(Map.of(), List.of(), "^$", docs, auth);
    }

    @Test
    void blankSecretTurnsChecksOff() {
        assertEquals(Mode.OFF, new JwtAuthFilter(props(Mode.ENFORCE, "", List.of())).mode());
        assertEquals(Mode.OFF, new JwtAuthFilter(props(Mode.ENFORCE, "  ", List.of())).mode());
    }

    @Test
    void shortSecretTurnsChecksOff() {
        assertEquals(Mode.OFF, new JwtAuthFilter(props(Mode.ENFORCE, "short", List.of())).mode());
    }

    @Test
    void validSecretKeepsTheMode() {
        assertEquals(Mode.ENFORCE, new JwtAuthFilter(props(Mode.ENFORCE, TestTokens.SECRET, List.of())).mode());
    }

    @Test
    void malformedPublicEntryFailsStartup() {
        assertThrows(IllegalArgumentException.class,
                () -> new JwtAuthFilter(props(Mode.ENFORCE, TestTokens.SECRET, List.of("/no/method"))));
    }
}
