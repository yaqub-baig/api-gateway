package com.autoadda.gateway;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Hides internal-only endpoints (role changes, SMS/email send, notification send, swagger,
 * api-docs, actuator) from the public. The services call these over localhost and do not
 * go through the gateway, so blocking here does not affect them.
 *
 * The path is normalised the way Tomcat will read it (percent-decoding, ";params",
 * duplicate slashes, "." and ".." segments) before it is compared, so encoded or
 * dotted variants cannot slip past the pattern.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PathGuardFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(PathGuardFilter.class);

    private final Pattern deny;

    public PathGuardFilter(GatewayProperties props) {
        this.deny = Pattern.compile(props.denyPattern());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String raw = exchange.getRequest().getPath().value();
        String normalised = normalise(raw);
        if (normalised == null || deny.matcher(normalised).find()) {
            log.warn("blocked {} {} from {}",
                    exchange.getRequest().getMethod(),
                    sanitise(raw),
                    sanitise(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For")));
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    /** Returns the canonical path, or null when the path is malformed (treated as blocked). */
    static String normalise(String raw) {
        String p = raw;
        // Decode up to three times so double-encoded input is covered as well.
        for (int i = 0; i < 3; i++) {
            String decoded;
            try {
                decoded = URLDecoder.decode(p.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (decoded.equals(p)) {
                break;
            }
            p = decoded;
        }
        p = p.replace('\\', '/');
        p = p.replaceAll(";[^/]*", "");
        Deque<String> out = new ArrayDeque<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                out.pollLast();
                continue;
            }
            out.addLast(seg);
        }
        return "/" + String.join("/", out);
    }

    private static String sanitise(String s) {
        if (s == null) {
            return "-";
        }
        String t = s.replaceAll("[\\p{Cntrl}]", "?");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
