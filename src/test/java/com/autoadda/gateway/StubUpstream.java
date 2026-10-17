package com.autoadda.gateway;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** One received request: path (with query) and its headers, lower-cased names. */
    record Seen(String path, Map<String, String> headers) {
    }

    final List<String> seenPaths = new CopyOnWriteArrayList<>();
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final DisposableServer server;

    StubUpstream(Function<String, Reply> replies) {
        this.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .handle((req, res) -> {
                    String path = req.path().isEmpty() ? "/" : "/" + req.path();
                    seenPaths.add(path);
                    Map<String, String> headers = new HashMap<>();
                    req.requestHeaders().forEach(e -> headers.merge(
                            e.getKey().toLowerCase(Locale.ROOT), e.getValue(), (a, b) -> a + "," + b));
                    seen.add(new Seen(req.uri(), headers));
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

    /** The last request whose path starts with the given prefix, or null. */
    Seen last(String pathPrefix) {
        Seen found = null;
        for (Seen s : seen) {
            if (s.path().startsWith(pathPrefix)) {
                found = s;
            }
        }
        return found;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }
}
