package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Guards everything under /_docs: 404 while docs are off, HTTP Basic while they are on,
 * and a per-IP limit on wrong passwords. Also sets the security headers on every /_docs
 * response. Other paths pass through untouched.
 *
 * Runs after PathGuardFilter, so a path that normalises to a blocked one is still blocked first.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DocsAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(DocsAuthFilter.class);

    static final int MAX_FAILURES = 10;
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_TRACKED_IPS = 10_000;

    private static final String CSP = "default-src 'none'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; "
            + "font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    private final GatewayProperties.Docs docs;
    private final byte[] expectedUser;
    private final byte[] expectedPassword;
    private final Clock clock;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    @Autowired
    public DocsAuthFilter(GatewayProperties props) {
        this(props, Clock.systemUTC());
    }

    DocsAuthFilter(GatewayProperties props, Clock clock) {
        this.docs = props.docs();
        this.clock = clock;
        this.expectedUser = bytes(docs.username());
        this.expectedPassword = bytes(docs.password());
    }

    /**
     * True for /_docs and anything below it. Checked on the raw path and on the normalised
     * path (decoded, dot segments removed), case-insensitive, so no spelling of the path can
     * reach the docs handlers without passing this filter.
     */
    static boolean isDocsPath(String raw) {
        if (startsWithDocs(raw)) {
            return true;
        }
        String normalised = PathGuardFilter.normalise(raw);
        return normalised != null && startsWithDocs(normalised);
    }

    private static boolean startsWithDocs(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        return l.equals("/_docs") || l.startsWith("/_docs/");
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (!isDocsPath(path)) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            HttpHeaders h = response.getHeaders();
            h.set(HttpHeaders.CACHE_CONTROL, "no-store");
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Content-Security-Policy", CSP);
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Robots-Tag", "noindex");
            h.set("X-Frame-Options", "DENY");
            return Mono.empty();
        });

        if (!docs.active()) {
            return text(response, HttpStatus.NOT_FOUND, "Not found\n");
        }

        String ip = clientIp(request);
        long now = clock.millis();
        if (isLocked(ip, now)) {
            log.warn("docs rate-limited {} {}", ip, sanitise(path));
            return text(response, HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts. Try again later.\n");
        }

        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isBlank()) {
            log.info("docs 401 (no credentials) {} {}", ip, sanitise(path));
            return unauthorized(response);
        }
        if (!credentialsMatch(header)) {
            int count = recordFailure(ip, now);
            log.warn("docs 401 (bad credentials, {} in window) {} {}", count, ip, sanitise(path));
            return unauthorized(response);
        }
        return chain.filter(exchange);
    }

    private boolean credentialsMatch(String header) {
        if (header.length() < 6 || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return false;
        }
        // Compare both parts every time so the timing does not reveal which one was wrong.
        boolean userOk = MessageDigest.isEqual(bytes(decoded.substring(0, colon)), expectedUser);
        boolean passOk = MessageDigest.isEqual(bytes(decoded.substring(colon + 1)), expectedPassword);
        return userOk & passOk;
    }

    private boolean isLocked(String ip, long now) {
        Failures f = failures.get(ip);
        if (f == null) {
            return false;
        }
        if (now - f.windowStart >= WINDOW_MILLIS) {
            failures.remove(ip, f);
            return false;
        }
        return f.count >= MAX_FAILURES;
    }

    private int recordFailure(String ip, long now) {
        if (failures.size() >= MAX_TRACKED_IPS && !failures.containsKey(ip)) {
            evict(now);
        }
        Failures f = failures.compute(ip, (k, old) ->
                old == null || now - old.windowStart >= WINDOW_MILLIS
                        ? new Failures(now, 1)
                        : new Failures(old.windowStart, old.count + 1));
        return f.count;
    }

    /** Drops expired entries; if the map is still full, drops the oldest one. */
    private void evict(long now) {
        failures.values().removeIf(f -> now - f.windowStart >= WINDOW_MILLIS);
        if (failures.size() < MAX_TRACKED_IPS) {
            return;
        }
        String oldestIp = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Failures> e : failures.entrySet()) {
            if (e.getValue().windowStart < oldest) {
                oldest = e.getValue().windowStart;
                oldestIp = e.getKey();
            }
        }
        if (oldestIp != null) {
            failures.remove(oldestIp);
        }
    }

    /**
     * The client address as Apache saw it. Apache appends the peer address to any
     * X-Forwarded-For the client sent, so the last entry is the one that cannot be forged.
     */
    static String clientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            String last = parts[parts.length - 1].trim();
            if (!last.isEmpty()) {
                return sanitise(last);
            }
        }
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "-";
    }

    private static Mono<Void> unauthorized(ServerHttpResponse response) {
        response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"AutoAdda docs\", charset=\"UTF-8\"");
        // Non-empty body on purpose: Apache replaces empty error bodies with its own page.
        return text(response, HttpStatus.UNAUTHORIZED, "Authentication required.\n");
    }

    private static Mono<Void> text(ServerHttpResponse response, HttpStatus status, String body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8));
        DataBuffer buf = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buf));
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }

    private static String sanitise(String s) {
        String t = s.replaceAll("[\\p{Cntrl}]", "?");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private record Failures(long windowStart, int count) {
    }
}
