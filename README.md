# IT Access Request Portal

Employees request access to internal systems (GitLab, Jira, AWS, ...). Administrators approve or reject the requests, every status change is recorded in a history table, and each change sends an event through Kafka that a consumer turns into a (simulated) notification. Optionally, the whole API runs behind a WSO2 API Manager gateway with OAuth2 login.

Learning and portfolio project: small enough to run on a laptop and explain in an interview, but built with enterprise patterns (layered backend, explicit SQL, transactions, asynchronous messaging, API gateway).

| Area | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1.1, Spring Web MVC, Bean Validation |
| Persistence | MyBatis 3.5 (mybatis-spring-boot-starter 4.1.0), HikariCP 7, Flyway 12, Oracle JDBC (ojdbc17) |
| Database | Oracle Database Free 23ai (`gvenzl/oracle-free:23-slim-faststart`) |
| Messaging | Apache Kafka 4.2 (KRaft), Spring for Apache Kafka 4.1 |
| API gateway | WSO2 API Manager 4.7.0, OAuth2 (authorization code + PKCE), signed backend JWT |
| Frontend | Angular 22.2, TypeScript 6, Reactive Forms, signals, plain CSS |
| Tests | JUnit 6, Mockito, Testcontainers 2 (Oracle, Kafka), Vitest |

**Documentation**

| Read this | For |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Diagrams (system, layers, request and login sequences, data model), key decisions, limitations and production improvements |
| [docs/concepts.md](docs/concepts.md) | How things work and why: controller/service/mapper, DTOs, MyBatis vs JPA, `@Transactional`, connection pool, Kafka, gateway, Angular services |
| [docs/demo-script.md](docs/demo-script.md) | Step-by-step end-to-end demonstration, with what you should see |
| [docs/wso2-setup.md](docs/wso2-setup.md) | Gateway mode: setup, tokens, verified security behaviour |
| [docs/api-examples.http](docs/api-examples.http) | Ready-to-run API requests (VS Code REST Client / IntelliJ) |
| [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) | How it was built, phase by phase: decisions, problems found, verification log |

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | `java -version` must show 21. Maven is **not** needed: use the `mvnw` wrapper |
| Docker Desktop | recent | Runs Oracle, Kafka and (optionally) WSO2. Give it ≥ 8 GB RAM; WSO2 alone needs ~2 GB more |
| Node.js | 22.22+ | For the Angular UI and the setup scripts |

Ports used: 1521 (Oracle), 9092 (Kafka), 8080 (backend), 4200 (UI); gateway mode adds 9443, 8243 and 8280; the optional Kafka UI uses 8081.

## Quick start (direct mode)

```bash
# 1. Local secrets (once). Creates a git-ignored .env with random passwords.
node infra/init-env.mjs

# 2. Oracle + Kafka (first start pulls ~1.5 GB). Wait until both show "(healthy)".
docker compose up -d oracle kafka
docker compose ps

# 3. Backend (terminal 1). Flyway creates the tables on first start.
cd backend
./mvnw spring-boot:run              # Windows cmd/PowerShell: mvnw.cmd spring-boot:run
curl http://localhost:8080/api/health    # {"status":"UP","database":"UP"}

# 4. UI (terminal 2)
cd frontend
npm install                         # first time only
npm start                           # http://localhost:4200
```

Open http://localhost:4200 and follow the [demo script](docs/demo-script.md). In direct mode you pick who you are with the **Demo user** switcher (top right): **Ted L** (employee) or **Alice Admin** (admin). This switch is for development only; it is not security.

`ng serve` proxies `/api/*` to `http://localhost:8080` ([proxy.conf.json](frontend/proxy.conf.json)), so the browser sees one origin and the backend needs no CORS configuration.

**Stop:** `Ctrl+C` in both terminals, then `docker compose stop`. `docker compose down -v` also **deletes the database volume**; Kafka's records live in the container and are removed by `down`.

## Gateway mode (WSO2 API Manager)

The same API, published through WSO2 API Manager: WSO2 validates OAuth2 tokens and checks a scope per resource before forwarding the call to Spring Boot. Full guide: **[docs/wso2-setup.md](docs/wso2-setup.md)**.

```bash
docker compose --profile gateway up -d wso2am       # ~90 s until healthy
node infra/wso2/setup.mjs                           # users, API, scopes, app, keys (~10 s)
cd backend  && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,gateway
cd frontend && npm run start:gateway                # sign in as ted.l / alice.admin (passwords in .env)
```

| | Direct mode (default) | Gateway mode |
|---|---|---|
| UI calls | `/api` → Spring Boot :8080 | `/gateway/accessportal/1.0.0` → WSO2 :8243 → Spring Boot |
| Who you are | Demo-user switcher (`X-Demo-*` headers, **not security**) | Sign-in on WSO2's login page (authorization code + PKCE) |
| Backend trusts | The headers | Only a WSO2-signed `X-JWT-Assertion` (401 otherwise, also for direct calls) |
| Needs | Oracle, Kafka | + WSO2 (`--profile gateway`) and `node infra/wso2/setup.mjs` |

Roles become OAuth2 scopes: employees get `access_request:read` and `access_request:submit`; admins also get `access_request:review` (approve/reject). The gateway rejects a missing scope with 403 before the backend is reached.

## What's in the box

| Page | Route | What it shows |
|---|---|---|
| Request access | `/catalog` | Searchable grid of systems; clicking one opens the form |
| New request | `/requests/new?system=Jira` | Reactive form with client-side and server-side validation errors |
| All requests / Review queue | `/requests`, `/requests?status=PENDING` | Table with status filter and search; filters live in the URL |
| Request details | `/requests/:id` | Details, history timeline, Kafka notifications; **Approve / Reject** only for admins on PENDING requests |

**Messaging, in short:** submitting or reviewing publishes `AccessRequestSubmitted` / `AccessRequestReviewed` to the topic `access-request.events` *after* the database commit, keyed by request id (so one request's events stay in order). The `notification-service` consumer records each one in `NOTIFICATION_LOG`, skips duplicates, retries failures in place, and moves records that still fail to `access-request.events.DLT`. To demonstrate the failure path, request access to the system **`Legacy Mainframe`** (configured by `NOTIFICATIONS_FAIL_FOR_SYSTEM`). Details: [architecture.md §3](docs/architecture.md#3-submitting-a-request-and-what-happens-afterwards). Optional Kafka UI: `docker compose --profile ui up -d`, then http://localhost:8081.

### Project layout

```text
backend/                      Spring Boot application (Maven wrapper)
  src/main/java/com/itaccess/portal/
    controller/               REST endpoints (HTTP only)
    service/                  business rules, @Transactional
    mapper/                   MyBatis mapper interfaces
    domain/                   one class per table row
    dto/request, dto/response the JSON contract
    messaging/                Kafka config, producer, consumer, event
    security/                 demo-header and WSO2-JWT identity
    exception/                RFC 9457 error responses
    config/                   web, gateway security, clock
  src/main/resources/
    mapper/*.xml              the SQL
    db/migration/             Flyway migrations
    application.yml           settings and profiles (dev, gateway)
frontend/                     Angular 22 app
  src/app/core/               API service, DTO types, auth (PKCE), session, interceptors
  src/app/features/           pages (requests, auth callback)
infra/
  init-env.mjs                creates .env with random secrets
  wso2/                       WSO2 deployment.toml + setup.mjs
docs/                         architecture, concepts, demo script, WSO2 guide, API examples
docker-compose.yml            oracle, kafka, kafka-ui (profile ui), wso2am (profile gateway)
```

## Tests

```bash
cd backend  && ./mvnw test                  # 56 JUnit tests: unit, web slice, Oracle + Kafka integration
cd frontend && npx ng test --watch=false    # 31 Vitest tests: forms, states, roles, PKCE
```

- Unit and web-slice tests need nothing else.
- Integration tests start throwaway Oracle and Kafka containers through **Testcontainers** (the first run pulls images and takes a few minutes). If Docker isn't running, they are reported as **skipped**, so check that the summary says `Skipped: 0`. No H2 is used: Oracle-specific SQL is tested on Oracle.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/requests` | Submit a request → 201 + `Location: requests/{id}` (relative) |
| GET | `/api/requests?status=&q=&page=&size=` | List; `q` matches requester or system name |
| GET | `/api/requests/{id}` | Details + history (one SQL join) |
| PATCH | `/api/requests/{id}/status` | `{"status":"APPROVED"\|"REJECTED","comment":"..."}`: admin only; 409 if no longer PENDING |
| GET | `/api/requests/{id}/history` | Status history |
| GET | `/api/requests/{id}/notifications` | Notifications recorded by the Kafka consumer (demo/diagnostics) |
| GET | `/api/health` | App + database check (public) |

Errors use RFC 9457 problem details: `{"title","status","detail","instance","errors"?}`. Through the gateway the same paths live under `https://localhost:8243/accessportal/1.0.0` (without `/api`).

## Configuration

Values come from environment variables or the repo-root `.env` file ([.env.example](.env.example); create `.env` with `node infra/init-env.mjs`):

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | JDBC URL (service name `FREEPDB1`) |
| `DB_USERNAME` / `DB_PASSWORD` | `portal` / — | App schema user (created by the Oracle container) |
| `ORACLE_PASSWORD` | — | Oracle `SYS`/`SYSTEM` password (container setup) |
| `DB_POOL_MAX_SIZE` | 10 | Hikari: max open connections |
| `DB_POOL_MIN_IDLE` | 2 | Hikari: idle connections kept ready |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 30000 | Hikari: max wait for a free connection |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address(es) |
| `NOTIFICATIONS_FAIL_FOR_SYSTEM` | empty | Demo switch: notifications for this system name fail → retries → dead-letter topic |
| `WSO2_ADMIN_PASSWORD` / `WSO2_ENCRYPTION_KEY` | — | WSO2 super-admin password / 32-hex-char key WSO2 encrypts stored secrets with (gateway profile) |
| `PORTAL_EMPLOYEE_PASSWORD` / `PORTAL_ADMIN_PASSWORD` | — | Passwords of the WSO2 demo users `ted.l` / `alice.admin`, created by `infra/wso2/setup.mjs` |
| `WSO2_SIGNING_CERT` | `file:../infra/wso2/generated/wso2-signing-cert.pem` | Certificate the backend verifies `X-JWT-Assertion` with (gateway profile) |

Pool metrics (dev profile): `GET /actuator/metrics/hikaricp.connections.active`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ORA-12541: no listener` / connection refused on 1521 | Oracle container not running or still starting: `docker compose ps`, wait for `(healthy)` |
| `ORA-01017: invalid username/password` | `.env` changed after the container was first created. The app user is only created on the first start. Either restore the old password or `docker compose down -v` (deletes data) and start again |
| `ORA-12514: listener does not currently know of service` | Wrong service name in `DB_URL`: use `/FREEPDB1`, not `/FREE` |
| `SQLTransientConnectionException: AccessPortalPool - Connection is not available` | Pool exhausted or DB down. Check `/actuator/metrics/hikaricp.connections.pending` |
| `Invalid column type: 1111` | A null parameter without a jdbcType. Keep `mybatis.configuration.jdbc-type-for-null=NULL` |
| Tests skipped | Docker Desktop isn't running (it does not start automatically after a reboot) |
| UI shows "Cannot reach the server" | Backend not running on :8080, or the UI was opened some other way than `npm start` (the proxy only exists in `ng serve`) |
| UI shows HTTP 504 from the proxy | Backend still starting, or it crashed; check its terminal |
| Log: `Could not publish … event lost (no outbox yet)` | Kafka was unreachable when the change committed. The change itself is saved; only the notification is missing ([architecture.md §8](docs/architecture.md#8-known-limitations-and-production-improvements)) |
| Log: `Connection to node -1 (localhost/127.0.0.1:9092) could not be established` | Kafka not running: `docker compose up -d kafka`. The app still starts and serves the API; the consumer reconnects automatically |
| Records in `access-request.events.DLT` | Expected for failed notifications. Inspect them in Kafka UI (*Topics → access-request.events.DLT → Messages*; headers show the exception) or with `kafka-console-consumer.sh` (see demo script) |
| Consumer seems idle for a few seconds after startup | The consumer group is joining (partition assignment). Normal; records produced meanwhile are read once it has joined |
| `UnknownTopicOrPartitionException` | Topics are created by the app at startup (auto-create is off in Compose). Start the backend once before producing by hand |
| Gateway mode: backend fails to start with "WSO2 signing certificate not found" | Run `node infra/wso2/setup.mjs` (it exports the certificate) with WSO2 running |
| Gateway mode: every API call returns 401 `Missing X-JWT-Assertion` | You are calling Spring Boot directly; in gateway mode go through `https://localhost:8243/accessportal/1.0.0` (or the UI's `/gateway` proxy) |
| Gateway mode: 401 from the gateway after an hour | The access token expired (no refresh-token handling); the UI sends you to sign in again |
| Gateway mode: 403 `900910` "access token does not allow you to access the requested resource" | The user's role lacks the scope (e.g. an employee approving). Expected |
| Browser: "Your connection isn't private" on `localhost:9443` | WSO2's self-signed certificate; continue once (local development only) |
| UI shows "Could not reach the sign-in server" | WSO2 not running/healthy, or `setup.mjs` not run (`frontend/public/gateway-config.json` missing) |
| After `docker compose rm/down` of WSO2, login fails with an unknown client | WSO2 was recreated: run `node infra/wso2/setup.mjs` again (new client id), then reload the UI |
| `java -version` shows 1.8 | Open a new terminal after installing JDK 21, or set `JAVA_HOME` to the JDK 21 folder |
