package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.autoadda.gateway.GatewayProperties.Auth.Mode;

import reactor.core.publisher.Mono;

/**
 * Checks the identity-api JWT on the /{service}/** routes and tells the services who the
 * caller is.
 *
 * The services take the caller's user id from the X-Auto-Adda-Id header. Clients used to
 * set it themselves, so anyone could act as anyone. With a valid token the gateway now
 * replaces X-Auto-Adda-Id (and X-User-Role) with the values from the token.
 *
 * <ul>
 *   <li>off: nothing is checked or changed.</li>
 *   <li>report: never blocks. Logs what enforce would reject. Headers are replaced only when
 *       the token is valid; otherwise the request goes through exactly as before.</li>
 *   <li>enforce: a protected path without a valid token gets 401, a missing role 403.
 *       Public paths are served anonymously: client identity headers are removed, and a
 *       bad or expired token is ignored rather than rejected.</li>
 * </ul>
 *
 * Runs after CORS, PathGuardFilter and DocsAuthFilter. Only paths whose first segment is a
 * configured upstream are checked; /_docs and the management port are left alone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class JwtAuthFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    static final String USER_ID_HEADER = "X-Auto-Adda-Id";
    static final String ROLE_HEADER = "X-User-Role";
    /** Identity headers a client must never be able to set for itself. */
    static final List<String> IDENTITY_HEADERS = List.of(USER_ID_HEADER, ROLE_HEADER, "X-User-Id", "X-User-Roles");

    private record PublicPath(HttpMethod method, PathPattern pattern) {
    }

    private record RoleRule(PathPattern pattern, String role) {
    }

    private final Mode mode;
    private final JwtVerifier verifier;
    private final List<PublicPath> publicPaths;
    private final List<RoleRule> roleRules;
    private final boolean stripRolePrefix;
    private final Set<String> services;

    @Autowired
    public JwtAuthFilter(GatewayProperties props) {
        this(props, Clock.systemUTC());
    }

    JwtAuthFilter(GatewayProperties props, Clock clock) {
        GatewayProperties.Auth auth = props.auth();
        PathPatternParser parser = new PathPatternParser();
        this.publicPaths = auth.publicPaths().stream().map(e -> parsePublic(parser, e)).toList();
        this.roleRules = auth.roleRules().stream()
                .map(r -> new RoleRule(parser.parse(r.pattern()), r.role()))
                .toList();
        this.stripRolePrefix = auth.stripRolePrefix();
        this.services = props.upstreams().keySet().stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        Mode configured = auth.mode();
        JwtVerifier v = null;
        if (configured != Mode.OFF) {
            if (auth.jwtSecret() == null || auth.jwtSecret().isBlank()) {
                log.error("gateway.auth.mode is {} but gateway.auth.jwt-secret is blank; JWT checks are OFF",
                        configured);
                configured = Mode.OFF;
            } else {
                try {
                    v = new JwtVerifier(auth.jwtSecret(), auth.clockSkewSeconds(), clock);
                } catch (IllegalArgumentException e) {
                    log.error("{}; JWT checks are OFF", e.getMessage());
                    configured = Mode.OFF;
                }
            }
        }
        this.mode = configured;
        this.verifier = v;
        log.info("JWT auth mode {} ({} public paths, {} role rules)", mode, publicPaths.size(), roleRules.size());
    }

    Mode mode() {
        return mode;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (mode == Mode.OFF) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String raw = request.getPath().value();
        String normalised = PathGuardFilter.normalise(raw);
        // Only routed paths are checked. Anything else is never forwarded to a service
        // (/_docs, /actuator on the management port, unknown prefixes that 404).
        if (!isServicePath(raw) && !isServicePath(normalised)) {
            return chain.filter(exchange);
        }
        boolean isPublic = isPublic(request.getMethod(), raw, normalised);

        String token = bearerToken(request);
        Optional<JwtVerifier.Principal> principal = token == null ? Optional.empty() : verifier.verify(token);

        if (principal.isPresent()) {
            JwtVerifier.Principal p = principal.get();
            String sent = request.getHeaders().getFirst(USER_ID_HEADER);
            if (sent != null && !sent.equals(p.userId())) {
                log.warn("x-auto-adda-id mismatch: sent {} token {} {} {} from {}", sanitise(sent), p.userId(),
                        request.getMethod(), sanitise(normalised), DocsAuthFilter.clientIp(request));
            }
            String missingRole = missingRole(normalised, p.roles());
            if (missingRole != null) {
                if (mode == Mode.ENFORCE) {
                    log.info("auth 403 (needs {}) user {} {} {} from {}", missingRole, p.userId(),
                            request.getMethod(), sanitise(normalised), DocsAuthFilter.clientIp(request));
                    return reject(exchange.getResponse(), HttpStatus.FORBIDDEN, "forbidden", null);
                }
                log.warn("would-403 (needs {}) user {} {} {} from {}", missingRole, p.userId(),
                        request.getMethod(), sanitise(normalised), DocsAuthFilter.clientIp(request));
            }
            return chain.filter(withIdentity(exchange, p));
        }

        if (isPublic) {
            // Public paths are anonymous. In report mode the request is left as it was.
            return chain.filter(mode == Mode.ENFORCE ? withIdentity(exchange, null) : exchange);
        }

        String reason = token == null ? "no token" : "invalid token";
        if (mode == Mode.ENFORCE) {
            log.info("auth 401 ({}) {} {} from {}", reason, request.getMethod(), sanitise(normalised),
                    DocsAuthFilter.clientIp(request));
            return reject(exchange.getResponse(), HttpStatus.UNAUTHORIZED, "unauthorized",
                    token == null ? "Bearer realm=\"autoadda\"" : "Bearer realm=\"autoadda\", error=\"invalid_token\"");
        }
        log.warn("would-401 ({}) {} {} from {}", reason, request.getMethod(), sanitise(normalised),
                DocsAuthFilter.clientIp(request));
        return chain.filter(exchange);
    }

    /** True when the first segment names an upstream (case-insensitive, to be safe). */
    private boolean isServicePath(String path) {
        if (path == null || path.length() < 2) {
            return false;
        }
        int end = path.indexOf('/', 1);
        String first = (end < 0 ? path.substring(1) : path.substring(1, end)).toLowerCase(Locale.ROOT);
        return services.contains(first);
    }

    /**
     * Public only when both the raw path and the normalised path match the same entry,
     * so encoded or dotted spellings cannot borrow a public entry for a protected path.
     */
    boolean isPublic(HttpMethod method, String raw, String normalised) {
        if (normalised == null) {
            return false;
        }
        PathContainer rawPath = PathContainer.parsePath(raw);
        PathContainer normPath = PathContainer.parsePath(normalised);
        for (PublicPath p : publicPaths) {
            if ((p.method() == null || p.method().equals(method))
                    && p.pattern().matches(rawPath) && p.pattern().matches(normPath)) {
                return true;
            }
        }
        return false;
    }

    /** The first role a matching rule requires that the caller lacks, or null. */
    private String missingRole(String normalised, List<String> roles) {
        if (normalised == null) {
            return null;
        }
        PathContainer path = PathContainer.parsePath(normalised.toLowerCase(Locale.ROOT));
        for (RoleRule r : roleRules) {
            if (r.pattern().matches(path) && !roles.contains(r.role())) {
                return r.role();
            }
        }
        return null;
    }

    /** Removes client identity headers; with a principal, sets them from the token. */
    private ServerWebExchange withIdentity(ServerWebExchange exchange, JwtVerifier.Principal p) {
        return exchange.mutate().request(r -> r.headers(h -> {
            IDENTITY_HEADERS.forEach(h::remove);
            if (p != null) {
                h.set(USER_ID_HEADER, p.userId());
                if (!p.roles().isEmpty()) {
                    h.set(ROLE_HEADER, p.roles().stream()
                            .map(role -> stripRolePrefix && role.startsWith("ROLE_") ? role.substring(5) : role)
                            .collect(Collectors.joining(",")));
                }
            }
        })).build();
    }

    private static String bearerToken(ServerHttpRequest request) {
        String h = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (h == null || h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String t = h.substring(7).trim();
        return t.isEmpty() ? null : t;
    }

    private static Mono<Void> reject(ServerHttpResponse response, HttpStatus status, String error, String challenge) {
        response.setStatusCode(status);
        if (challenge != null) {
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        // Non-empty body on purpose: Apache replaces empty error bodies with its own page.
        DataBuffer buf = response.bufferFactory()
                .wrap(("{\"error\":\"" + error + "\"}").getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buf));
    }

    private static PublicPath parsePublic(PathPatternParser parser, String entry) {
        String[] parts = entry.trim().split("\\s+", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("gateway.auth.public-paths entry must be \"METHOD /path\": " + entry);
        }
        HttpMethod method = parts[0].equalsIgnoreCase("ANY") ? null : HttpMethod.valueOf(parts[0].toUpperCase(Locale.ROOT));
        return new PublicPath(method, parser.parse(parts[1]));
    }

    private static String sanitise(String s) {
        if (s == null) {
            return "-";
        }
        String t = s.replaceAll("[\\p{Cntrl}]", "?");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
