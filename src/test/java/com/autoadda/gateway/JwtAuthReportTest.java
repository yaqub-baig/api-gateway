package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Report mode never blocks and leaves requests without a valid token untouched. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "gateway.auth.mode=report",
            "gateway.auth.jwt-secret=" + TestTokens.SECRET
        })
class JwtAuthReportTest {

    static final StubUpstream ECHO = new StubUpstream(path -> new StubUpstream.Reply(200, "{\"ok\":true}"));

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        r.add("gateway.upstreams.garage", ECHO::baseUrl);
    }

    @Autowired
    WebTestClient client;

    @Test
    void missingTokenPassesUnchangedAndIsLogged(CapturedOutput out) {
        client.get().uri("/garage/api/v1/work-orders/by-garage/5?t=r1")
                .header("X-Auto-Adda-Id", "7")
                .exchange()
                .expectStatus().isOk();
        assertEquals("7", ECHO.last("/api/v1/work-orders/by-garage/5?t=r1").headers().get("x-auto-adda-id"));
        assertTrue(out.getOut().contains("would-401 (no token) GET /garage/api/v1/work-orders/by-garage/5"));
    }

    @Test
    void validTokenReplacesTheIdAndLogsTheMismatch(CapturedOutput out) {
        client.get().uri("/garage/api/v1/work-orders/by-garage/5?t=r2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .header("X-Auto-Adda-Id", "1")
                .exchange()
                .expectStatus().isOk();
        assertEquals("42", ECHO.last("/api/v1/work-orders/by-garage/5?t=r2").headers().get("x-auto-adda-id"));
        assertTrue(out.getOut().contains("x-auto-adda-id mismatch: sent 1 token 42"));
    }

    @Test
    void missingRoleIsLoggedNotBlocked(CapturedOutput out) {
        client.post().uri("/garage/api/v1/admin/garages/3/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .exchange()
                .expectStatus().isOk();
        assertTrue(out.getOut().contains("would-403 (needs ROLE_ADMIN) user 42"));
    }

    @Test
    void tokensAreNeverLogged(CapturedOutput out) {
        String token = TestTokens.expired("42");
        client.get().uri("/garage/api/v1/work-orders/by-garage/5?t=r4")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertTrue(out.getOut().contains("would-401 (invalid token)"));
        assertTrue(!out.getOut().contains(token.substring(0, 40)));
    }
}
