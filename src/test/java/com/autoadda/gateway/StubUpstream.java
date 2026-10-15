package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** A tiny loopback HTTP server standing in for a backend service in tests. */
final class StubUpstream {

    /** What to answer for a path; null means 404. */
    record Reply(int status, String json) {
    }

    final List<String> seenPaths = new CopyOnWriteArrayList<>();
    private final DisposableServer server;

    StubUpstream(Function<String, Reply> replies) {
        this.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((req, res) -> {
                    String path = req.path().isEmpty() ? "/" : "/" + req.path();
                    seenPaths.add(path);
                    Reply r = replies.apply(path);
                    if (r == null) {
                        return res.status(HttpResponseStatus.NOT_FOUND).sendString(Mono.just("not found"));
                    }
                    return res.status(r.status())
                            .header("Content-Type", "application/json")
                            .sendByteArray(Mono.just(r.json().getBytes(StandardCharsets.UTF_8)));
                })
                .bindNow();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }
}
