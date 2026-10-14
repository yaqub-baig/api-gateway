package com.autoadda.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One route per backend service: /{service}/** is forwarded to the service with the
 * first path segment removed, so /garage/api/v1/garages/1 reaches garage as /api/v1/garages/1.
 *
 * Routes are defined in Java (not in YAML) so they do not depend on the property prefix,
 * which changed between Spring Cloud releases.
 */
@Configuration
public class RoutesConfig {

    @Bean
    public RouteLocator serviceRoutes(RouteLocatorBuilder builder, GatewayProperties props) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        props.upstreams().forEach((name, url) ->
                routes.route(name, r -> r
                        .path("/" + name + "/**")
                        .filters(f -> f
                                .stripPrefix(1)
                                // The services still send their own CORS headers (allow-origin "*").
                                // Keep the gateway's value, drop the duplicate from the service.
                                .dedupeResponseHeader(
                                        "Access-Control-Allow-Origin Access-Control-Allow-Credentials "
                                                + "Access-Control-Expose-Headers",
                                        "RETAIN_FIRST"))
                        .uri(url)));
        return routes.build();
    }
}
