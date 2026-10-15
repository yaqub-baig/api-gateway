package com.autoadda.gateway;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings under the "gateway." prefix (see application.yml).
 *
 * @param upstreams      service name to base URL; the name becomes the public path prefix (/garage/...)
 * @param allowedOrigins browser origins allowed by CORS
 * @param denyPattern    regex matched against the normalised public path; matches get a 404
 * @param docs           the private combined Swagger UI at /_docs (off by default)
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Map<String, String> upstreams,
        List<String> allowedOrigins,
        String denyPattern,
        @DefaultValue Docs docs) {

    /**
     * Settings under "gateway.docs.".
     *
     * @param enabled             serve /_docs at all; ignored (treated as false) while password is blank
     * @param username            HTTP Basic user name
     * @param password            HTTP Basic password; set on the server only, never in the repo
     * @param apiDocsPaths        paths tried in order on each upstream; the first 200 JSON answer wins
     * @param cacheSeconds        how long a fetched spec is reused
     * @param fetchTimeoutSeconds timeout for one upstream fetch
     * @param maxSpecBytes        larger specs are rejected
     */
    public record Docs(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("docs") String username,
            @DefaultValue("") String password,
            @DefaultValue({"/api/v1/v3/api-docs", "/v3/api-docs"}) List<String> apiDocsPaths,
            @DefaultValue("60") int cacheSeconds,
            @DefaultValue("3") int fetchTimeoutSeconds,
            @DefaultValue("5MB") DataSize maxSpecBytes) {

        /** True only when enabled and a password is configured. */
        public boolean active() {
            return enabled && password != null && !password.isBlank()
                    && username != null && !username.isBlank();
        }
    }
}
