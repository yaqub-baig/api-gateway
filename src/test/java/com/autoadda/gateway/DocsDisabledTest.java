package com.autoadda.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Docs are off by default: every /_docs path is a 404 and no password is asked for. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class DocsDisabledTest {

    @Autowired
    WebTestClient client;

    @Test
    void docsAreNotFoundWithoutAuthChallenge() {
        for (String path : new String[] {"/_docs", "/_docs/", "/_docs/ui/swagger-ui.css", "/_docs/spec/garage"}) {
            client.get().uri(path).exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().doesNotExist("WWW-Authenticate");
        }
    }

    @Test
    void webjarIsNotServedAtTheDefaultLocation() {
        client.get().uri("/webjars/swagger-ui/5.32.15/index.html").exchange()
                .expectStatus().isNotFound();
    }
}
