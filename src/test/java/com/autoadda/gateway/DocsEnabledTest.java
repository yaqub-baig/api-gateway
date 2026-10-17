package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "gateway.docs.enabled=true",
            "gateway.docs.username=docs",
            "gateway.docs.password=test-only-secret"
        })
class DocsEnabledTest {

    private static final String USER = "docs";
    private static final String PASS = "test-only-secret";

    /** garage: spec at the first candidate path, servers pointing straight at the service. */
    static final StubUpstream GARAGE = new StubUpstream(path -> switch (path) {
        case "/api/v1/v3/api-docs" -> new StubUpstream.Reply(200, """
                {"openapi":"3.0.1","info":{"title":"garage","version":"1"},
                 "servers":[{"url":"http://127.0.0.1:8020","description":"Generated server url"}],
                 "paths":{"/api/v1/garages/{id}":{"get":{"responses":{"200":{"description":"OK"}}}}},
                 "components":{"securitySchemes":{"bearerAuth":{"type":"http","scheme":"bearer"}}}}
                """);
        case "/api/v1/ping" -> new StubUpstream.Reply(200, "{\"pong\":true}");
        default -> null;
    });

    /** vehicle: first candidate 404, second candidate 200 with a context path in servers. */
    static final StubUpstream VEHICLE = new StubUpstream(path -> path.equals("/v3/api-docs")
            ? new StubUpstream.Reply(200, """
                {"openapi":"3.0.1","info":{"title":"vehicle-second","version":"1"},
                 "servers":[{"url":"http://127.0.0.1:8030/api/v1"}],"paths":{}}
                """)
            : null);

    /** identity: answers nothing; used to prove the path guard stops requests before forwarding. */
    static final StubUpstream IDENTITY = new StubUpstream(path -> null);

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry r) {
        r.add("gateway.upstreams.garage", GARAGE::baseUrl);
        r.add("gateway.upstreams.vehicle", VEHICLE::baseUrl);
        r.add("gateway.upstreams.identity", IDENTITY::baseUrl);
        // Nothing listens on port 1: connection refused.
        r.add("gateway.upstreams.quote", () -> "http://127.0.0.1:1");
    }

    @Autowired
    WebTestClient client;

    @LocalServerPort
    int port;

    // ---- authentication ----

    @Test
    void noCredentialsGives401WithChallengeAndBody() {
        String body = client.get().uri("/_docs/").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"AutoAdda docs\".*")
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(body != null && !body.isBlank(), "401 body must not be empty");
    }

    @Test
    void wrongPasswordGives401() {
        client.get().uri("/_docs/").headers(h -> h.setBasicAuth(USER, "wrong")).exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    void correctCredentialsServeThePageWithSecurityHeaders() {
        String html = client.get().uri("/_docs/").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("X-Robots-Tag", "noindex")
                .expectHeader().valueMatches("Content-Security-Policy", ".*script-src 'self';.*")
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(html.contains("/_docs/ui/swagger-ui-bundle.js"));
        assertFalse(html.contains("https://"), "page must not load anything from another host");
    }

    @Test
    void redirectAndStaticFilesAndConfig() {
        client.get().uri("/_docs").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isFound()
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/_docs/");
        // A small file, read in full: an unread multi-MB body keeps the server mid-write and
        // makes graceful shutdown wait 30 s at the end of the test run.
        String css = client.get().uri("/_docs/ui/index.css").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectHeader().contentTypeCompatibleWith("text/css")
                .expectBody(String.class).returnResult().getResponseBody();
        assertTrue(css != null && css.contains("box-sizing"));
        client.get().uri("/_docs/ui/swagger-ui.css").exchange()
                .expectStatus().isUnauthorized();
        Map<?, ?> cfg = client.get().uri("/_docs/swagger-config").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<?> urls = (List<?>) cfg.get("urls");
        assertEquals(6, urls.size());
        assertEquals(((Map<?, ?>) urls.get(0)).get("name"), cfg.get("primaryName"));
        assertTrue(urls.contains(Map.of("name", "garage", "url", "/_docs/spec/garage")));
    }

    @Test
    void encodedDocsPathStillNeedsAuth() {
        client.get().uri(URI.create("http://127.0.0.1:" + port + "/%5Fdocs/spec/garage")).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri(URI.create("http://127.0.0.1:" + port + "/_DOCS/spec/garage")).exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void eleventhFailureFromSameIpIsRateLimited() {
        String ip = "203.0.113.7";
        for (int i = 1; i <= DocsAuthFilter.MAX_FAILURES; i++) {
            client.get().uri("/_docs/")
                    .headers(h -> {
                        h.setBasicAuth(USER, "guess");
                        h.set("X-Forwarded-For", ip);
                    })
                    .exchange().expectStatus().isUnauthorized();
        }
        client.get().uri("/_docs/")
                .headers(h -> {
                    h.setBasicAuth(USER, "guess");
                    h.set("X-Forwarded-For", ip);
                })
                .exchange().expectStatus().isEqualTo(429);
        // Locked out for the window, even with the right password.
        client.get().uri("/_docs/")
                .headers(h -> {
                    h.setBasicAuth(USER, PASS);
                    h.set("X-Forwarded-For", ip);
                })
                .exchange().expectStatus().isEqualTo(429);
        // A client-supplied first entry does not escape the limit: Apache appends the real address last.
        client.get().uri("/_docs/")
                .headers(h -> {
                    h.setBasicAuth(USER, PASS);
                    h.set("X-Forwarded-For", "198.51.100.1, " + ip);
                })
                .exchange().expectStatus().isEqualTo(429);
        // Other clients are unaffected.
        client.get().uri("/_docs/")
                .headers(h -> {
                    h.setBasicAuth(USER, PASS);
                    h.set("X-Forwarded-For", "203.0.113.8");
                })
                .exchange().expectStatus().isOk();
    }

    // ---- spec proxy ----

    @Test
    void unknownServiceIs404() {
        client.get().uri("/_docs/spec/unknown").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("unknown_service");
    }

    @Test
    void serversAreRewrittenToTheGatewayPrefix() {
        client.get().uri("/_docs/spec/garage").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.servers.length()").isEqualTo(1)
                .jsonPath("$.servers[0].url").isEqualTo("/garage")
                .jsonPath("$.servers[0].description").doesNotExist()
                .jsonPath("$.paths['/api/v1/garages/{id}']").exists()
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer");
    }

    @Test
    void secondCandidatePathIsUsedWhenFirstIs404() {
        client.get().uri("/_docs/spec/vehicle").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.info.title").isEqualTo("vehicle-second")
                .jsonPath("$.servers[0].url").isEqualTo("/vehicle/api/v1");
        assertTrue(VEHICLE.seenPaths.contains("/api/v1/v3/api-docs"));
        assertTrue(VEHICLE.seenPaths.contains("/v3/api-docs"));
    }

    @Test
    void upstreamDownIs502() {
        client.get().uri("/_docs/spec/quote").headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                .expectStatus().isEqualTo(502)
                .expectBody().jsonPath("$.error").isEqualTo("upstream_unavailable");
    }

    // ---- existing behaviour is unchanged ----

    @Test
    void pathGuardStillBlocksInternalPaths() {
        for (String p : new String[] {
            "/identity/api/v1/auth/update/role/0",
            "/garage/api/v1/swagger-ui/index.html",
            "/vehicle/api/v1/v3/api-docs"
        }) {
            client.get().uri(p).headers(h -> h.setBasicAuth(USER, PASS)).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().doesNotExist(HttpHeaders.WWW_AUTHENTICATE);
        }
        assertFalse(IDENTITY.seenPaths.stream().anyMatch(s -> s.contains("role")));
        assertFalse(GARAGE.seenPaths.stream().anyMatch(s -> s.contains("swagger-ui")));
    }

    @Test
    void servicePathsDoNotNeedDocsAuth() {
        client.get().uri("/garage/api/v1/ping").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.pong").isEqualTo(true);
    }

    @Test
    void corsAllowsTheGatewayHostAndRejectsOthers() {
        client.post().uri("/garage/api/v1/ping").header(HttpHeaders.ORIGIN, "https://api.autoadda.ae").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://api.autoadda.ae");
        client.options().uri("/garage/api/v1/ping")
                .header(HttpHeaders.ORIGIN, "https://api.autoadda.ae")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.DELETE.name())
                .exchange()
                .expectStatus().isOk();
        client.post().uri("/garage/api/v1/ping").header(HttpHeaders.ORIGIN, "https://evil.example").exchange()
                .expectStatus().isForbidden();
        client.options().uri("/garage/api/v1/ping")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.DELETE.name())
                .exchange()
                .expectStatus().isForbidden();
    }
}
