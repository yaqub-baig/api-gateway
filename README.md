# GatewayApi

Public entry point for the AutoAdda backend services. One host (`api.autoadda.ae`), one CORS policy, internal-only endpoints hidden.

```
Browser -> https://api.autoadda.ae/<service>/api/v1/...
        -> Apache (TLS) -> 127.0.0.1:8070 (this app)
        -> strips "/<service>" and forwards to 127.0.0.1:<service port>
```

| Public prefix | Upstream |
|---|---|
| `/identity` | 127.0.0.1:8010 |
| `/garage` | 127.0.0.1:8020 |
| `/vehicle` | 127.0.0.1:8030 |
| `/quote` | 127.0.0.1:8050 |
| `/part` | 127.0.0.1:8060 |
| `/otherservice` | 127.0.0.1:9010 |

Example: `https://api.autoadda.ae/garage/api/v1/garages/search/paginated` reaches garage as `/api/v1/garages/search/paginated`.

## What it does (version 1)

- Routes by service prefix (`RoutesConfig`).
- CORS in one place (`CorsConfig`): only the origins in `gateway.allowed-origins` (this includes `https://api.autoadda.ae` itself, because behind Apache the gateway sees plain http and would otherwise treat same-origin calls from `/_docs` as cross-origin). A request from another browser origin gets 403. Preflight is answered here. If a service also sends `Access-Control-Allow-Origin`, the gateway keeps its own value (no duplicate header).
- Blocks internal endpoints (`PathGuardFilter`): role changes, SMS/email send, notification send/test, swagger, api-docs, actuator. Responds 404. The path is decoded and normalised before matching (`%75pdate`, `//`, `;x=1`, `/./`, `/../`).
- Private API docs at `/_docs/` (off by default, see below).
- JWT check on the service routes, and the caller's identity passed to the services from the token (`JwtAuthFilter`, off by default, see "Authentication").
- Not done yet: rate limiting on the service routes.

## Authentication (`gateway.auth`)

The gateway checks the access token identity-api issues (HS256, shared secret) on every `/<service>/...` request, except a list of public paths. **It is off by default.**

**Why the identity headers matter:** the services take the caller's user id from the `X-Auto-Adda-Id` header. Before this, clients set it themselves, so anyone could act as any user. With a valid token the gateway now **removes any client-sent `X-Auto-Adda-Id`, `X-User-Role`, `X-User-Id`, `X-User-Roles` and sets `X-Auto-Adda-Id` (the token's `userId`) and `X-User-Role` (its roles) itself.** Services can trust these headers only because the gateway sets them, so the services must stay reachable through the gateway only (bound to 127.0.0.1 or firewalled).

| `mode` | What happens |
|---|---|
| `off` | No checks, nothing changed. |
| `report` | Never blocks. Logs `would-401` / `would-403` and `x-auto-adda-id mismatch` lines. Identity headers are replaced only when the token is valid; requests without a valid token go through exactly as before. |
| `enforce` | Protected path without a valid token: `401 {"error":"unauthorized"}`. Missing role: `403`. Public paths are served anonymously: client identity headers are removed, and a bad or expired token is ignored, not rejected (so a stale token in the browser does not bounce a visitor off a public page). |

- `jwt-secret` must be the same secret identity-api signs with. **Never commit it.** A blank secret (or one shorter than 32 bytes) logs an error and turns the check off.
- `public-paths`: `METHOD /pattern` entries (`ANY` for every method). Patterns use Spring path syntax: `*` is one segment, `**` the rest, and `{id:\d+}` a numeric id only (so `/garages/{id:\d+}` does not also open `/garages/search`). A request is public only when both its raw and its normalised path match, so `..` or percent-encoded spellings cannot borrow a public entry.
- `role-rules`: `{pattern, role}`; any method on a matching path needs that role on top of a valid token (`ROLE_ADMIN` for the admin APIs).
- `X-User-Role` is sent comma-separated without the `ROLE_` prefix (`ADMIN,GARAGE_OWNER`; `strip-role-prefix: true`, the default). quote-api is the only service that reads it.
- Only paths that start with a configured service are checked. `/_docs` has its own auth, and the management port (`/actuator/health`) is not affected.
- Tokens are never logged.

Enable on the server in the same out-of-repo file as the docs settings (mode 600):

```yaml
gateway:
  auth:
    mode: report          # then enforce, after reviewing the logs
    jwt-secret: <same secret as identity-api, set on the server only>
```

Rollout: run `report` for a few days and check `grep -E 'would-40|mismatch' ~/.pm2/logs/gateway-out.log`. Add missing public paths or fix the app calls, then switch to `enforce` and `pm2 restart gateway`. To roll back, switch to `report`. No rebuild is needed either way. Quote `mode: "off"` in YAML, since a bare `off` is read as a boolean.

The default public list in `application.yml` was built from the website, panel and mobile-app endpoint audits (2026-10-17).

## API docs (`/_docs`)

One Swagger UI page for all six services, served by the gateway itself at `https://api.autoadda.ae/_docs/`, with a dropdown to pick the service. **It is OFF by default** and, when on, is protected by HTTP Basic auth.

- `GET /_docs/` is the page. The swagger-ui files come from the `org.webjars:swagger-ui` jar (served under `/_docs/ui/`). Nothing is loaded from other hosts, and the validator badge is off.
- `GET /_docs/spec/<service>` fetches the service's OpenAPI JSON from its loopback upstream. It tries each path in `api-docs-paths` in order and uses the first one that returns a 200 OpenAPI JSON. The result is cached for `cache-seconds`. If the service is down the answer is 502 `{"error":"upstream_unavailable"}`, and an unknown name gets 404.
- The spec's top-level `servers` is replaced with `/<service>`, so **"Try it out" goes through the gateway**: `https://api.autoadda.ae/<service>/api/v1/...`. It therefore passes the path guard and CORS like any public request, which makes it a real test of the public surface. If the service publishes a context path in its server URL (`http://127.0.0.1:8020/api/v1`), that path is kept (`/garage/api/v1`). Paths, components and security schemes are untouched, so **Authorize** with a JWT works.
- After 10 wrong passwords from one client IP in 15 minutes, that IP gets 429 for the rest of the window. The IP is the last `X-Forwarded-For` entry, the one Apache adds.
- `/_docs` responses carry `Cache-Control: no-store`, a strict `Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` and `X-Robots-Tag: noindex`.
- The public `/<service>/.../swagger-ui` and `/v3/api-docs` paths stay blocked as before.

Settings under `gateway.docs`:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `false` | Serve `/_docs` at all. |
| `username` | `docs` | Basic-auth user. |
| `password` | empty | Basic-auth password. **If it is blank, docs stay off** even with `enabled: true` (an error is logged). |
| `api-docs-paths` | `/api/v1/v3/api-docs`, `/v3/api-docs` | Paths tried on each upstream, in order. |
| `cache-seconds` | `60` | How long a fetched spec is reused. |
| `fetch-timeout-seconds` | `3` | Timeout per upstream fetch. |
| `max-spec-bytes` | `5MB` | Larger specs are rejected. |

To enable it on the server without a rebuild, keep a file **outside the repo** (`chmod 600`), for example `/home/adminautoadda/apps/gateway.yml`:

```yaml
gateway:
  docs:
    enabled: true
    username: docs
    password: <set on the server only>
```

and start the jar with `--spring.config.additional-location=file:/home/adminautoadda/apps/gateway.yml` (add it after `-jar ...` in the PM2 command, then `pm2 restart gateway`).

> **Never commit the docs password** (or any other credential) to this repo. `application.yml` ships with docs off and an empty password on purpose.

Quick check once enabled:

```
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8070/_docs/                 # 401
curl -s -o /dev/null -w '%{http_code}\n' -u docs:PASSWORD http://127.0.0.1:8070/_docs/ # 200
curl -s -u docs:PASSWORD http://127.0.0.1:8070/_docs/spec/garage | head -c 300
```

## Build and test

```
mvn -B package          # runs PathGuardFilterTest and the Docs*Test classes as part of the build
```

The jar is `target/GatewayApi-0.0.1-SNAPSHOT.jar`.

## Run (PM2, as adminautoadda)

```
pm2 start /usr/bin/java --name gateway --interpreter none --time -- \
  -Xms256m -Xmx512m -XX:+UseG1GC \
  -jar /home/adminautoadda/apps/backend/gateway/target/GatewayApi-0.0.1-SNAPSHOT.jar
pm2 save
```

Listens on 127.0.0.1:8070 (public traffic) and 127.0.0.1:8071 (`/actuator/health` only).

## Quick checks

```
curl -s http://127.0.0.1:8071/actuator/health                       # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8070/identity/api/v1/auth/update/role/0   # 404 (blocked)
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8070/nosuch/x                              # 404 (no route)
curl -si -H 'Origin: https://autoadda.ae' http://127.0.0.1:8070/garage/api/v1/garages/search/paginated | head -20
```

## Settings

Everything is in `src/main/resources/application.yml` under `gateway.*`. To override on the server without rebuilding, add `--spring.config.additional-location=file:/home/adminautoadda/apps/gateway.yml` to the PM2 command.
