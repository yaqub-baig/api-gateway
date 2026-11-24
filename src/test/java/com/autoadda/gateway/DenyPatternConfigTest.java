package com.autoadda.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The deny-pattern in application.yml keeps service-to-service endpoints off the internet. */
class DenyPatternConfigTest {

    @SuppressWarnings("unchecked")
    private static Pattern denyPattern() throws Exception {
        try (InputStream in = DenyPatternConfigTest.class.getResourceAsStream("/application.yml")) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> gateway = (Map<String, Object>) root.get("gateway");
            return Pattern.compile((String) gateway.get("deny-pattern"));
        }
    }

    @Test
    void internalAddressLookupIsBlocked() throws Exception {
        Pattern deny = denyPattern();
        assertTrue(deny.matcher("/identity/api/v1/addresses/internal/5").find());
        assertTrue(deny.matcher("/IDENTITY/api/v1/Addresses/Internal/5").find());
        assertTrue(deny.matcher("/identity/api/v1/auth/send-sms").find()); // existing rule still there
    }

    @Test
    void usersOwnAddressesStayReachable() throws Exception {
        Pattern deny = denyPattern();
        assertFalse(deny.matcher("/identity/api/v1/addresses").find());
        assertFalse(deny.matcher("/identity/api/v1/addresses/5").find());
        assertFalse(deny.matcher("/identity/api/v1/addresses/5/default").find());
    }
}
