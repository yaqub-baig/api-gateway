package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PathGuardFilterTest {

    private static final Pattern DENY = Pattern.compile(
            "(?i)^/[^/]+/api/v1/(auth/(send-sms|send-email|update/role|remove/role)"
                    + "|notifications?/(send|test)|swagger-ui|v3/api-docs|actuator)");

    private static boolean blocked(String raw) {
        String n = PathGuardFilter.normalise(raw);
        return n == null || DENY.matcher(n).find();
    }

    @Test
    void normaliseHandlesTricks() {
        assertEquals("/a/b", PathGuardFilter.normalise("/a//b"));
        assertEquals("/a/b", PathGuardFilter.normalise("/a/./b"));
        assertEquals("/b", PathGuardFilter.normalise("/a/../b"));
        assertEquals("/a/b", PathGuardFilter.normalise("/a;x=1/b"));
        assertEquals("/a/b", PathGuardFilter.normalise("/a/%62"));
        assertEquals("/a/b", PathGuardFilter.normalise("/a/%2562"));
        assertEquals("/", PathGuardFilter.normalise("/"));
        assertNull(PathGuardFilter.normalise("/a/%zz"));
    }

    @Test
    void blocksInternalPathsAndVariants() {
        String[] bad = {
            "/identity/api/v1/auth/update/role/5",
            "/identity/api/v1/auth/remove/role/5",
            "/identity/api/v1//auth/update/role/5",
            "/identity/api/v1/auth/./update/role/5",
            "/identity/api/v1/auth;x=1/update/role/5",
            "/identity/api/v1/auth/%75pdate/role/5",
            "/identity/api/v1/auth/%2575pdate/role/5",
            "/identity/api/v1/AUTH/UPDATE/ROLE/5",
            "/identity/api/v1/x/../auth/update/role/5",
            "/identity/api/v1/auth/send-sms",
            "/identity/api/v1/auth/send-email/12",
            "/identity/api/v1/notifications/send",
            "/identity/api/v1/notifications/test",
            "/otherservice/api/v1/notification/send",
            "/garage/api/v1/swagger-ui/index.html",
            "/garage/api/v1/%73wagger-ui/index.html",
            "/vehicle/api/v1/v3/api-docs",
            "/vehicle/api/v1/v3/api-docs/swagger-config",
            "/quote/api/v1/actuator/health",
            "/quote/api/v1/actuator;x=1/health",
            "/quote/api/v1//actuator/health",
            "/identity/api/v1/auth/%zz"
        };
        for (String p : bad) {
            assertTrue(blocked(p), "should be blocked: " + p);
        }
    }

    @Test
    void allowsNormalTraffic() {
        String[] ok = {
            "/identity/api/v1/auth/user/signin",
            "/identity/api/v1/auth/byNumber/971500000000",
            "/identity/api/v1/auth/42",
            "/identity/api/v1/notifications/subscribe",
            "/garage/api/v1/garages/search/paginated",
            "/vehicle/api/v1/uploads/abc.png",
            "/part/api/v1/products/search",
            "/quote/api/v1/quotations/7",
            "/otherservice/api/v1/vendors"
        };
        for (String p : ok) {
            assertFalse(blocked(p), "should be allowed: " + p);
        }
    }
}
