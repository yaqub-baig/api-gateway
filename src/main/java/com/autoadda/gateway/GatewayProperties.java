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
 * @param auth           JWT check for the /{service}/** routes (off by default)
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        Map<String, String> upstreams,
        List<String> allowedOrigins,
        String denyPattern,
        @DefaultValue Docs docs,
        @DefaultValue Auth auth) {

    /**
     * Settings under "gateway.auth.".
     *
     * @param mode             off: no checks; report: log what would be rejected, never block;
     *                         enforce: reject. Treated as off while jwtSecret is blank.
     * @param jwtSecret        the HS256 secret identity-api signs with; set on the server only
     * @param publicPaths      "METHOD /pattern" entries that need no token (METHOD may be ANY)
     * @param roleRules        paths that need a role on top of a valid token
     * @param clockSkewSeconds tolerance for exp/nbf
     * @param stripRolePrefix  send "ADMIN" instead of "ROLE_ADMIN" in X-User-Role
     */
    public record Auth(
            @DefaultValue("off") Mode mode,
            @DefaultValue("") String jwtSecret,
            @DefaultValue({}) List<String> publicPaths,
            @DefaultValue({}) List<RoleRule> roleRules,
            @DefaultValue("30") int clockSkewSeconds,
            @DefaultValue("false") boolean stripRolePrefix) {

        public enum Mode { OFF, REPORT, ENFORCE }

        /** Any method on a path matching pattern requires role (e.g. ROLE_ADMIN). */
        public record RoleRule(String pattern, String role) {
        }
    }

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
