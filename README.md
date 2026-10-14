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
- CORS in one place (`CorsConfig`): only the origins in `gateway.allowed-origins`. A request from another browser origin gets 403. Preflight is answered here. If a service also sends `Access-Control-Allow-Origin`, the gateway keeps its own value (no duplicate header).
- Blocks internal endpoints (`PathGuardFilter`): role changes, SMS/email send, notification send/test, swagger, api-docs, actuator. Responds 404. The path is decoded and normalised before matching (`%75pdate`, `//`, `;x=1`, `/./`, `/../`).
- Not in version 1: JWT checks, rate limiting. The five services without authentication still trust the caller. That comes in a later phase.

## Build and test

```
mvn -B package          # runs PathGuardFilterTest as part of the build
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
