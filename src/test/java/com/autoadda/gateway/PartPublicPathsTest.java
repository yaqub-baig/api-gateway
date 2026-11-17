package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.util.unit.DataSize;
import org.yaml.snakeyaml.Yaml;

import com.autoadda.gateway.GatewayProperties.Auth;
import com.autoadda.gateway.GatewayProperties.Auth.Mode;

/** The part website-search entries in application.yml: open what the website needs, nothing more. */
class PartPublicPathsTest {

    private static final String UUID = "e123566a-77ec-41c7-b508-0c56af9ff4e7";

    @SuppressWarnings("unchecked")
    private static JwtAuthFilter filterFromConfig() throws Exception {
        try (InputStream in = PartPublicPathsTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> gateway = (Map<String, Object>) root.get("gateway");
            Map<String, Object> auth = (Map<String, Object>) gateway.get("auth");
            List<String> publicPaths = (List<String>) auth.get("public-paths");
            GatewayProperties.Docs docs = new GatewayProperties.Docs(false, "docs", "", List.of(), 60, 3,
                    DataSize.ofMegabytes(5));
            return new JwtAuthFilter(new GatewayProperties(Map.of(), List.of(), "^$", docs,
                    new Auth(Mode.ENFORCE, TestTokens.SECRET, publicPaths, List.of(), 30, false)));
        }
    }

    private static boolean open(JwtAuthFilter f, HttpMethod method, String path) {
        return f.isPublic(method, path, path);
    }

    @Test
    void websiteSearchIsPublic() throws Exception {
        JwtAuthFilter f = filterFromConfig();
        String p = "/part/api/v1";
        assertTrue(open(f, HttpMethod.POST, p + "/products/search/paginated"));
        assertTrue(open(f, HttpMethod.POST, p + "/products/search/advanced"));
        for (String by : List.of("by-vehicle", "by-name", "by-part-number", "by-category", "by-manufacturer",
                "by-tag", "by-other-names")) {
            assertTrue(open(f, HttpMethod.GET, p + "/products/search/" + by), by);
        }
        assertTrue(open(f, HttpMethod.GET, p + "/products/search/" + UUID));
        assertTrue(open(f, HttpMethod.GET, p + "/products/search/" + UUID + "/suppliers"));
        assertTrue(open(f, HttpMethod.GET, p + "/models/make/" + UUID));
        assertTrue(open(f, HttpMethod.GET, p + "/engines/model/" + UUID));
        assertTrue(open(f, HttpMethod.GET, p + "/attributes"));
        assertTrue(open(f, HttpMethod.GET, p + "/enquiries/matching-shops/count"));
    }

    @Test
    void everythingElseStaysPrivate() throws Exception {
        JwtAuthFilter f = filterFromConfig();
        String p = "/part/api/v1";
        assertFalse(open(f, HttpMethod.GET, p + "/products/search"));               // all products, unpaged
        assertFalse(open(f, HttpMethod.GET, p + "/products/search/not-a-uuid"));
        assertFalse(open(f, HttpMethod.GET, p + "/products/search/" + UUID + "/other"));
        assertFalse(open(f, HttpMethod.DELETE, p + "/products/search/" + UUID));
        assertFalse(open(f, HttpMethod.GET, p + "/models/make/123"));
        assertFalse(open(f, HttpMethod.POST, p + "/attributes"));
        assertFalse(open(f, HttpMethod.POST, p + "/enquiries/owner/42"));
        assertFalse(open(f, HttpMethod.GET, p + "/enquiries/owner/42"));
        assertFalse(open(f, HttpMethod.POST, p + "/cart/42/add"));
        assertFalse(open(f, HttpMethod.GET, p + "/orders/7"));
        assertFalse(open(f, HttpMethod.POST, p + "/suppliers/coverage/backfill"));
    }
}
