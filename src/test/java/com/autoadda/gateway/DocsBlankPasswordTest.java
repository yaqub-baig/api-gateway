package com.autoadda.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/** enabled=true without a password must behave exactly like enabled=false. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "gateway.docs.enabled=true", "gateway.docs.password= "})
class DocsBlankPasswordTest {

    @Autowired
    WebTestClient client;

    @Test
    void behavesAsDisabled() {
        client.get().uri("/_docs/").exchange()
                .expectStatus().isNotFound()
                .expectHeader().doesNotExist("WWW-Authenticate");
        client.get().uri("/_docs/").headers(h -> h.setBasicAuth("docs", "")).exchange()
                .expectStatus().isNotFound();
    }
}
