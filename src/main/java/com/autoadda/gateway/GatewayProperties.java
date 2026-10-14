package com.autoadda.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the "gateway." prefix (see application.yml).
 *
 * @param upstreams      service name to base URL; the name becomes the public path prefix (/garage/...)
 * @param allowedOrigins browser origins allowed by CORS
 * @param denyPattern    regex matched against the normalised public path; matches get a 404
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Map<String, String> upstreams,
        List<String> allowedOrigins,
        String denyPattern) {
}
