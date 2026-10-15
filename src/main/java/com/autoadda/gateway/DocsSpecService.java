package com.autoadda.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fetches a service's OpenAPI JSON from its loopback upstream for the /_docs page, points
 * "servers" at the gateway prefix (so "Try it out" goes through the gateway), and caches it.
 *
 * The upstream URL comes only from gateway.upstreams and gateway.docs.api-docs-paths;
 * the service name from the request is used only as a map key.
 */
@Component
public class DocsSpecService {

    private static final Logger log = LoggerFactory.getLogger(DocsSpecService.class);

    /** A server path we are willing to copy into the rewritten "servers" entry. */
    private static final Pattern SAFE_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)*");

    private final Map<String, String> upstreams;
    private final GatewayProperties.Docs docs;
    private final WebClient client;
    private final ObjectMapper mapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public DocsSpecService(GatewayProperties props, WebClient.Builder builder, ObjectMapper mapper) {
        this.upstreams = props.upstreams();
        this.docs = props.docs();
        this.mapper = mapper;
        int maxBytes = (int) Math.min(Integer.MAX_VALUE, docs.maxSpecBytes().toBytes());
        this.client = builder.clone()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxBytes))
                .build();
    }

    public boolean knows(String service) {
        return upstreams.containsKey(service);
    }

    /** The rewritten spec, or empty when no candidate path gave a usable answer. */
    public Mono<Optional<byte[]>> spec(String service) {
        String base = upstreams.get(service);
        if (base == null) {
            return Mono.just(Optional.empty());
        }
        Cached hit = cache.get(service);
        if (hit != null && hit.expiresAt > System.currentTimeMillis()) {
            return Mono.just(Optional.of(hit.json));
        }
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return Flux.fromIterable(docs.apiDocsPaths())
                .concatMap(path -> fetch(service, root, path))
                .next()
                .map(json -> {
                    cache.put(service, new Cached(json,
                            System.currentTimeMillis() + docs.cacheSeconds() * 1000L));
                    return Optional.of(json);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("docs spec {}: no candidate path returned an OpenAPI document", service);
                    return Optional.empty();
                }));
    }

    private Mono<byte[]> fetch(String service, String root, String path) {
        return client.get()
                .uri(URI.create(root + path))
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(resp -> {
                    if (resp.statusCode().value() != HttpStatus.OK.value()) {
                        log.info("docs spec {} {}: HTTP {}", service, path, resp.statusCode().value());
                        return resp.releaseBody().then(Mono.empty());
                    }
                    return resp.bodyToMono(byte[].class);
                })
                .timeout(Duration.ofSeconds(docs.fetchTimeoutSeconds()))
                .flatMap(body -> Mono.justOrEmpty(rewrite(service, path, body)))
                .doOnNext(json -> log.info("docs spec {} {}: OK", service, path))
                .onErrorResume(e -> {
                    log.warn("docs spec {} {}: {}", service, path, e.getClass().getSimpleName());
                    return Mono.empty();
                });
    }

    /**
     * Replaces the top-level "servers" with one entry under the gateway prefix. Everything
     * else is left as it is. Returns null when the body is not an OpenAPI JSON object.
     */
    byte[] rewrite(String service, String path, byte[] body) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            log.info("docs spec {} {}: not JSON", service, path);
            return null;
        }
        if (!(node instanceof ObjectNode spec) || !(spec.has("openapi") || spec.has("swagger"))) {
            log.info("docs spec {} {}: not an OpenAPI document", service, path);
            return null;
        }
        ArrayNode servers = mapper.createArrayNode();
        servers.addObject().put("url", "/" + service + serverPath(spec));
        spec.set("servers", servers);
        try {
            return mapper.writeValueAsBytes(spec);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Keeps the path part of the service's own first server URL. A service running with
     * context path /api/v1 publishes "http://127.0.0.1:8020/api/v1" and paths without the
     * prefix; dropping "/api/v1" there would send "Try it out" to the wrong place.
     * A plain "http://127.0.0.1:8020" gives "" so the result is just "/garage".
     */
    private static String serverPath(ObjectNode spec) {
        JsonNode first = spec.path("servers").path(0).path("url");
        if (!first.isTextual()) {
            return "";
        }
        String p;
        try {
            p = URI.create(first.asText()).getRawPath();
        } catch (IllegalArgumentException e) {
            return "";
        }
        if (p == null) {
            return "";
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return SAFE_PATH.matcher(p).matches() ? p : "";
    }

    private record Cached(byte[] json, long expiresAt) {
    }
}
