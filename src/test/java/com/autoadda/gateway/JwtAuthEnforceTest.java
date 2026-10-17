package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "gateway.auth.mode=enforce",
            "gateway.auth.jwt-secret=" + TestTokens.SECRET
        })
class JwtAuthEnforceTest {

    /** Answers 200 to everything and records what it received. */
    static final StubUpstream ECHO = new StubUpstream(path -> new StubUpstream.Reply(200, "{\"ok\":true}"));

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        for (String svc : new String[] {"identity", "garage", "vehicle", "quote", "part", "otherservice"}) {
            r.add("gateway.upstreams." + svc, ECHO::baseUrl);
        }
    }

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    private static final String PROTECTED = "/garage/api/v1/work-orders/by-garage/5";

    // ---- protected paths ----

    @Test
    void validTokenPassesAndIdentityComesFromTheToken() {
        client.get().uri(PROTECTED + "?t=valid")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .header("X-Auto-Adda-Id", "1")
                .header("X-User-Role", "ROLE_ADMIN")
                .header("X-User-Id", "1")
                .exchange()
                .expectStatus().isOk();
        StubUpstream.Seen seen = ECHO.last("/api/v1/work-orders/by-garage/5?t=valid");
        assertEquals("42", seen.headers().get("x-auto-adda-id"), "client-sent id must be replaced");
        assertEquals("ROLE_USER", seen.headers().get("x-user-role"), "client-sent role must be replaced");
        assertNull(seen.headers().get("x-user-id"));
        // The token itself still reaches the service (identity-api checks it too).
        assertTrue(seen.headers().get("authorization").startsWith("Bearer ey"));
    }

    @Test
    void missingTokenIs401WithBodyAndNothingIsForwarded() {
        String body = client.get().uri(PROTECTED + "?t=none")
                .header("X-Auto-Adda-Id", "1")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"autoadda\"")
                .expectBody(String.class).returnResult().getResponseBody();
        assertEquals("{\"error\":\"unauthorized\"}", body);
        assertNull(ECHO.last("/api/v1/work-orders/by-garage/5?t=none"));
    }

    @Test
    void badTokensAre401() {
        String[] bad = {
            TestTokens.expired("42"),
            TestTokens.algNone("42"),
            TestTokens.tampered("42", "7"),
            TestTokens.token("wrong-secret-0123456789-abcdefghijklmnop", "42", java.util.Set.of("ROLE_ADMIN"), 60_000),
            "garbage"
        };
        for (int i = 0; i < bad.length; i++) {
            String tag = "?t=bad" + i;
            client.get().uri(PROTECTED + tag)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bad[i])
                    .exchange()
                    .expectStatus().isUnauthorized()
                    .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE,
                            "Bearer realm=\"autoadda\", error=\"invalid_token\"");
            assertNull(ECHO.last("/api/v1/work-orders/by-garage/5" + tag), "forwarded with bad token " + i);
        }
    }

    // ---- public paths ----

    @Test
    void publicPathNeedsNoTokenAndIsAnonymous() {
        client.get().uri("/garage/api/v1/garages/search/paginated?t=anon")
                .header("X-Auto-Adda-Id", "1")
                .header("X-User-Role", "ROLE_ADMIN")
                .exchange()
                .expectStatus().isOk();
        StubUpstream.Seen seen = ECHO.last("/api/v1/garages/search/paginated?t=anon");
        assertNull(seen.headers().get("x-auto-adda-id"), "client-sent id must be removed");
        assertNull(seen.headers().get("x-user-role"));
    }

    @Test
    void publicPathWithExpiredTokenIsServedAnonymously() {
        client.get().uri("/vehicle/api/v1/vehicle-listing/paginated?t=stale")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.expired("42"))
                .header("X-Auto-Adda-Id", "42")
                .exchange()
                .expectStatus().isOk();
        assertNull(ECHO.last("/api/v1/vehicle-listing/paginated?t=stale").headers().get("x-auto-adda-id"));
    }

    @Test
    void publicPathWithValidTokenKnowsTheUser() {
        client.get().uri("/garage/api/v1/garages/12?t=known")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .exchange()
                .expectStatus().isOk();
        assertEquals("42", ECHO.last("/api/v1/garages/12?t=known").headers().get("x-auto-adda-id"));
    }

    @Test
    void numericIdPatternsDoNotOpenNamedSubPaths() {
        client.get().uri("/garage/api/v1/garages/12").exchange().expectStatus().isOk();
        client.get().uri("/garage/api/v1/garages/search").exchange().expectStatus().isUnauthorized();
        client.get().uri("/vehicle/api/v1/car-rental-listings/7").exchange().expectStatus().isOk();
        client.get().uri("/vehicle/api/v1/car-rental-listings/my-listings").exchange().expectStatus().isUnauthorized();
        client.post().uri("/garage/api/v1/garages/12").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void identityPublicEndpointsAndResetPassword() {
        client.post().uri("/identity/api/v1/auth/user/signin").exchange().expectStatus().isOk();
        client.post().uri("/identity/api/v1/auth/user/refresh-token?refreshToken=x").exchange().expectStatus().isOk();
        client.post().uri("/identity/api/v1/auth/reset-password").exchange().expectStatus().isUnauthorized();
        client.get().uri("/identity/api/v1/auth/5").exchange().expectStatus().isUnauthorized();
        client.post().uri("/identity/api/v1/auth/logout").header("X-Auto-Adda-Id", "5").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void uploadsArePublicOnEveryService() {
        client.get().uri("/vehicle/api/v1/uploads/2026/10/car.jpg").exchange().expectStatus().isOk();
        client.get().uri("/part/api/v1/mediafiles/9").exchange().expectStatus().isOk();
    }

    @Test
    void dottedOrEncodedPathsCannotBorrowAPublicEntry() {
        WebTestClient raw = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        String[] tricks = {
            "/garage/api/v1/uploads/../work-orders/by-garage/5",
            "/garage/api/v1/uploads/%2e%2e/work-orders/by-garage/5",
            "/garage/api/v1/uploads/..%2fwork-orders/by-garage/5",
            "/garage/api/v1/garages/search/paginated/../../../work-orders/by-garage/5",
            "/%67arage/api/v1/work-orders/by-garage/5",
            "/garage;x=1/api/v1/work-orders/by-garage/5",
            "/x/../garage/api/v1/work-orders/by-garage/5"
        };
        for (String t : tricks) {
            int status = raw.get().uri(URI.create("http://127.0.0.1:" + port + t)).exchange()
                    .expectBody().returnResult().getStatus().value();
            assertFalse(status == 200, "trick reached upstream without a token: " + t);
        }
    }

    // ---- roles ----

    @Test
    void adminPathsNeedTheAdminRole() {
        client.post().uri("/garage/api/v1/admin/garages/3/approve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(String.class).isEqualTo("{\"error\":\"forbidden\"}");
        client.get().uri("/identity/api/v1/auth/ADMIN/users")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.user("42"))
                .exchange()
                .expectStatus().isForbidden();
        client.post().uri("/garage/api/v1/admin/garages/3/approve?t=admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin("1"))
                .exchange()
                .expectStatus().isOk();
        assertEquals("1", ECHO.last("/api/v1/admin/garages/3/approve?t=admin").headers().get("x-auto-adda-id"));
    }

    // ---- other filters still in charge of their paths ----

    @Test
    void pathGuardStillAnswersFirst() {
        client.put().uri("/identity/api/v1/auth/update/role/0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.admin("1"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void corsPreflightNeedsNoToken() {
        client.options().uri(PROTECTED)
                .header(HttpHeaders.ORIGIN, "https://autoadda.ae")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-auto-adda-id")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void docsAndHealthAreNotAffected() {
        client.get().uri("/_docs/").exchange().expectStatus().isNotFound();
        WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + managementPort).build()
                .get().uri("/actuator/health").exchange().expectStatus().isOk();
    }
}
