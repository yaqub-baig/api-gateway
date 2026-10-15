package com.autoadda.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * The private combined Swagger UI at /_docs. Off by default; DocsAuthFilter answers 404 for
 * every /_docs path while it is off and asks for HTTP Basic while it is on.
 *
 * <pre>
 * GET /_docs                 302 to /_docs/
 * GET /_docs/                the page
 * GET /_docs/initializer.js  starts Swagger UI (external file so script-src stays 'self')
 * GET /_docs/swagger-config  the service list for the dropdown
 * GET /_docs/ui/**           swagger-ui files from the org.webjars:swagger-ui jar
 * GET /_docs/spec/{service}  the service's OpenAPI JSON, servers pointed at /{service}
 * </pre>
 *
 * Functional routes are matched before the gateway's /{service}/** routes, and an upstream
 * named "_docs" is refused at startup, so /_docs is never forwarded to a service.
 */
@Configuration
public class DocsConfig {

    private static final Logger log = LoggerFactory.getLogger(DocsConfig.class);

    static final String PREFIX = "/_docs";
    private static final String WEBJAR_PROPERTIES = "META-INF/maven/org.webjars/swagger-ui/pom.properties";

    @Bean
    public RouterFunction<ServerResponse> docsRoutes(GatewayProperties props, DocsSpecService specs,
            ObjectMapper mapper) throws IOException {
        if (props.upstreams().keySet().stream().anyMatch(n -> n.equalsIgnoreCase("_docs"))) {
            throw new IllegalStateException("gateway.upstreams must not contain a service named _docs");
        }
        GatewayProperties.Docs docs = props.docs();
        if (docs.enabled() && !docs.active()) {
            log.error("gateway.docs.enabled is true but gateway.docs.username/password is blank; "
                    + "/_docs stays disabled");
        } else if (docs.active()) {
            log.info("/_docs is enabled for user '{}' ({} services)", docs.username(), props.upstreams().size());
        }

        byte[] indexHtml = read("docs/index.html");
        byte[] initializerJs = read("docs/initializer.js");
        byte[] swaggerConfig = mapper.writeValueAsBytes(swaggerConfig(props));
        ClassPathResource uiRoot = new ClassPathResource(
                "META-INF/resources/webjars/swagger-ui/" + webjarVersion() + "/");

        return RouterFunctions.route()
                .GET(PREFIX + "/", r -> ServerResponse.ok()
                        .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                        .bodyValue(indexHtml))
                .GET(PREFIX, r -> ServerResponse.status(HttpStatus.FOUND).location(URI.create(PREFIX + "/")).build())
                .GET(PREFIX + "/initializer.js", r -> ServerResponse.ok()
                        .contentType(new MediaType("text", "javascript", StandardCharsets.UTF_8))
                        .bodyValue(initializerJs))
                .GET(PREFIX + "/swagger-config", r -> ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(swaggerConfig))
                .GET(PREFIX + "/spec/{service}", r -> {
                    String service = r.pathVariable("service");
                    if (!specs.knows(service)) {
                        return error(HttpStatus.NOT_FOUND, "unknown_service", null);
                    }
                    return specs.spec(service).flatMap(spec -> spec.isPresent()
                            ? ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(spec.get())
                            : error(HttpStatus.BAD_GATEWAY, "upstream_unavailable", service));
                })
                .add(RouterFunctions.resources(PREFIX + "/ui/**", uiRoot))
                // Second line of defence; DocsAuthFilter already answers 404 while docs are off.
                .filter((request, next) -> docs.active() ? next.handle(request)
                        : ServerResponse.notFound().build())
                .build();
    }

    /** Swagger UI "urls" option: one dropdown entry per upstream, in configuration order. */
    private static Map<String, Object> swaggerConfig(GatewayProperties props) {
        List<Map<String, String>> urls = new ArrayList<>();
        props.upstreams().keySet().forEach(name -> urls.add(Map.of("name", name, "url", PREFIX + "/spec/" + name)));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("urls", urls);
        cfg.put("primaryName", urls.isEmpty() ? "" : urls.get(0).get("name"));
        return cfg;
    }

    private static Mono<ServerResponse> error(HttpStatus status, String code, String service) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", code);
        if (service != null) {
            body.put("service", service);
        }
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    /** The webjar version, read from the jar itself so the pom is the only place it is set. */
    private static String webjarVersion() throws IOException {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource(WEBJAR_PROPERTIES).getInputStream()) {
            p.load(in);
        }
        return p.getProperty("version");
    }

    private static byte[] read(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        }
    }
}
