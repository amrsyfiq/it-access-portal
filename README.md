# IT Access Request Portal

Employees request access to internal systems (GitLab, Jira, AWS, ...). Administrators approve or reject the requests, and every status change is recorded in a history table.

Learning and portfolio project. Stack: Spring Boot 4.1 · MyBatis 3 · HikariCP · Oracle Database Free 23ai · Flyway · RabbitMQ · Angular 22 · WSO2 API Manager *(Phase 4)*.

> Status: **backend (Phase 1), Angular UI (Phase 2) and RabbitMQ messaging (Phase 3) working and tested against Oracle and RabbitMQ.** The WSO2 gateway (Phase 4) is not built yet. See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) and the [demo script](docs/demo-script.md).

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | `java -version` must show 21. Maven is **not** needed: use the `mvnw` wrapper |
| Docker Desktop | recent | WSL2 backend; runs Oracle (and later RabbitMQ / WSO2) |
| Node.js | 22.22+ | Phase 2 |

## Run locally

```bash
# 1. Secrets (once): copy and fill in placeholder passwords. .env is git-ignored.
cp .env.example .env

# 2. Start Oracle and RabbitMQ (first start pulls ~1.3 GB). Wait until both show "(healthy)".
docker compose up -d oracle rabbitmq
docker compose ps

# 3. Start the backend. Flyway creates the tables on first start.
cd backend
./mvnw spring-boot:run          # Windows cmd/PowerShell: mvnw.cmd spring-boot:run

# 4. Check it
curl http://localhost:8080/api/health     # {"status":"UP","database":"UP"}
```

Then try the requests in [docs/api-examples.http](docs/api-examples.http).

```bash
# 5. Start the Angular UI (second terminal)
cd frontend
npm install                     # first time only
npm start                       # http://localhost:4200
```

`ng serve` proxies `/api/*` to `http://localhost:8080` ([proxy.conf.json](frontend/proxy.conf.json)), so the browser talks to a single origin and the backend needs no CORS configuration. The API base URL is set in [environment.ts](frontend/src/environments/environment.ts).

Stop with `Ctrl+C` (backend and UI) and `docker compose stop` (Oracle, RabbitMQ). `docker compose down -v` also **deletes the database and broker volumes**.

## Frontend

| Page | Route | What it shows |
|---|---|---|
| Request access | `/catalog` | Searchable grid of systems; clicking one opens the form |
| New request | `/requests/new?system=Jira` | Reactive form with client-side and server-side validation errors |
| All requests / Review queue | `/requests`, `/requests?status=PENDING` | Table with status filter and search; filters live in the URL |
| Request details | `/requests/:id` | Details and history timeline; **Approve / Reject** only for admins on PENDING requests |

Structure: `core/` (typed DTOs, `AccessRequestApi`, the only class that makes HTTP calls; error mapping; demo user and interceptor), `features/requests/` (pages), `shared/` (status badge). Plain CSS with custom properties; no UI framework.

The system catalog is a fixed list in [system-catalog.ts](frontend/src/app/core/system-catalog.ts). It is a list of choices, not mock API data.

## Messaging (RabbitMQ)

Submitting or reviewing a request publishes an event; a consumer "sends" a notification (a log line, no real email) and records the result in `NOTIFICATION_LOG`. The HTTP request never waits for the notification.

```text
AccessRequestService (DB transaction)
  └─ publishEvent(AccessRequestEvent) ── held until COMMIT ──► AccessRequestEventPublisher
        RabbitTemplate ──► topic exchange  access-request.events
                              └─ access-request.*  ──► queue notification.queue ──► NotificationListener
                                                          │                          └─ NotificationService
                                                          │ rejected after retries        └─ NOTIFICATION_LOG
                                                          ▼
                           direct exchange access-request.dlx ──► queue notification.dlq
```

| Piece | Where | What it guarantees |
|---|---|---|
| Event JSON | [AccessRequestEvent](backend/src/main/java/com/itaccess/portal/messaging/event/AccessRequestEvent.java) | `eventId`, `eventType` (`AccessRequestSubmitted` / `AccessRequestReviewed`), `requestId`, `occurredAt`, plus actor, system and new status |
| Publish after commit | `@TransactionalEventListener(AFTER_COMMIT)` | A rolled-back change never produces a message |
| Retries | `spring.rabbitmq.listener.simple.retry` | 1 attempt + 2 retries (1 s, 2 s) inside the consumer |
| Dead-letter queue | queue arguments + [NotificationFailureRecoverer](backend/src/main/java/com/itaccess/portal/messaging/NotificationFailureRecoverer.java) | After the last retry: a `FAILED` row with the error is written, and the message is moved to `notification.dlq` |
| Deduplication | `UNIQUE(EVENT_ID)` + check in [NotificationService](backend/src/main/java/com/itaccess/portal/service/NotificationService.java) | RabbitMQ delivers *at least once*; a redelivered event is acknowledged and skipped |

**Known gap (by design for the MVP):** the database commit and the RabbitMQ publish are two separate systems, with no shared transaction. If the app crashes or the broker is down *between* the commit and the publish, that event is lost; the change itself is safe, and the failure is logged. A transactional outbox (write the event to a table in the same transaction, publish from there) would close the gap; it is listed as an optional improvement.

Management UI: http://localhost:15672 (credentials from `.env`). Walkthrough: [demo script, step 3](docs/demo-script.md#3-event-published-consumed-and-logged-rabbitmq).

## Tests

```bash
cd backend && ./mvnw test        # JUnit: unit, web-layer, Oracle integration
cd frontend && npx ng test --watch=false   # Vitest: components against a mocked HTTP backend
```

- Unit and web-layer tests need nothing else.
- Oracle integration tests (mapper SQL, the full HTTP workflow, transaction rollback) start their own throwaway Oracle container through **Testcontainers**. If Docker isn't running, they are reported as *skipped* rather than passed. No H2 is used, because Oracle-specific SQL has to be tested on Oracle.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/requests` | Submit a request → 201 + `Location` |
| GET | `/api/requests?status=&q=&page=&size=` | List; `q` matches requester or system name |
| GET | `/api/requests/{id}` | Details + history (one SQL join) |
| PATCH | `/api/requests/{id}/status` | `{"status":"APPROVED"\|"REJECTED","comment":"..."}`: admin only; 409 if no longer PENDING |
| GET | `/api/requests/{id}/history` | Status history |
| GET | `/api/health` | App + database check |
| GET | `/api/requests/{id}/notifications` | Notifications recorded by the RabbitMQ consumer (demo/diagnostics) |

Errors use RFC 9457 problem details: `{"title","status","detail","instance","errors"?}`.

**Roles (development only):** send `X-Demo-User: <name>` and `X-Demo-Role: ADMIN|EMPLOYEE`. These headers are **not security**; anyone can set them. Phase 4 replaces them with OAuth2 tokens validated by the WSO2 gateway.

## Configuration

Values come from environment variables or the repo-root `.env` file (see [.env.example](.env.example)):

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` | JDBC URL (service name `FREEPDB1`) |
| `DB_USERNAME` / `DB_PASSWORD` | `portal` / — | App schema user (created by the Oracle container) |
| `DB_POOL_MAX_SIZE` | 10 | Hikari: max open connections |
| `DB_POOL_MIN_IDLE` | 2 | Hikari: idle connections kept ready |
| `DB_POOL_CONNECTION_TIMEOUT_MS` | 30000 | Hikari: max wait for a free connection |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | Broker address |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | — | Broker user (created by the RabbitMQ container); also the management UI login |
| `NOTIFICATIONS_FAIL_FOR_SYSTEM` | empty | Demo switch: notifications for this system name fail → retries → dead-letter queue |

Pool metrics (dev profile): `GET /actuator/metrics/hikaricp.connections.active`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `ORA-12541: no listener` / connection refused on 1521 | Oracle container not running or still starting: `docker compose ps`, wait for `(healthy)` |
| `ORA-01017: invalid username/password` | `.env` changed after the container was first created. The app user is only created on the first start. Either restore the old password or `docker compose down -v` (deletes data) and start again |
| `ORA-12514: listener does not currently know of service` | Wrong service name in `DB_URL`: use `/FREEPDB1`, not `/FREE` |
| `SQLTransientConnectionException: AccessPortalPool - Connection is not available` | Pool exhausted or DB down. Check `/actuator/metrics/hikaricp.connections.pending` |
| `Invalid column type: 1111` | A null parameter without a jdbcType. Keep `mybatis.configuration.jdbc-type-for-null=NULL` |
| Tests skipped | Docker Desktop isn't running |
| UI shows "Cannot reach the server" | Backend not running on :8080, or the UI was opened some other way than `npm start` (the proxy only exists in `ng serve`) |
| UI shows HTTP 504 from the proxy | Backend still starting, or it crashed; check its terminal |
| Log: `Could not publish … event lost (no outbox yet)` | RabbitMQ was down when the change committed. The change itself is saved; only the notification is missing (see *Messaging* → known gap) |
| Log: `Connection refused` from `CachingConnectionFactory` | RabbitMQ not running: `docker compose up -d rabbitmq`. The app still starts and serves the API; the listener reconnects automatically |
| `ACCESS_REFUSED - Login was refused` | `.env` RabbitMQ credentials changed after the container was first created; the user is only created on first start. Restore them or `docker compose down -v` |
| Message stuck in `notification.dlq` | Expected for failed notifications. Inspect it in the management UI (*Queues → notification.dlq → Get messages*) |
| `java -version` shows 1.8 | Open a new terminal after installing JDK 21, or set `JAVA_HOME` to the JDK 21 folder |
