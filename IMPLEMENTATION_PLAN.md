# Implementation Plan — IT Access Request Portal

Status: **All phases (0–5) complete** (Oracle 23ai, Apache Kafka 4.2, WSO2 API Manager 4.7; verified from a fresh clone and in a real browser, 2026-10-14). See §7.
Last updated: 2026-10-13

Decisions confirmed by the user: Docker Desktop route, JDK 21 install, static frontend system catalog, package `com.itaccess.portal`. **2026-10-14: messaging changed from RabbitMQ to Apache Kafka** (team/company standard). This intentionally overrides the brief's "do not replace requested technologies" for this one component; RabbitMQ is no longer part of the project.

## Progress log

| Date | Item | Result |
|---|---|---|
| 2026-10-13 | Temurin JDK 21.0.12 installed (winget); machine `JAVA_HOME` updated by installer | ✅ |
| 2026-10-13 | Docker Desktop 4.95.0 installed; Virtual Machine Platform enabled (DISM exit 3010) | ⏳ **reboot required** before Docker/WSL2 work |
| 2026-10-13 | Backend generated (Initializr, Boot 4.1.1) + MyBatis starter 4.1.0 added by hand (Initializr metadata still lists it as incompatible with 4.1.1; the starter itself is built against Boot 4.1.0) | ✅ compiles |
| 2026-10-13 | `mvnw test`: 18 unit/web-slice tests pass; 10 Oracle Testcontainers tests **skipped** (no Docker) | ⚠️ partial |
| 2026-10-13 | After reboot: Docker 29.8.2 running, `docker compose up -d oracle` healthy | ✅ |
| 2026-10-13 | `mvnw test` against Oracle: one failure. A mapper test counted rows committed by the workflow test in the shared container. Fixed by scoping that test to a unique tag | ✅ fixed |
| 2026-10-13 | Manual smoke test vs compose Oracle: Flyway applied V1 to schema `PORTAL`; health 200, create 201 + Location, validation 400, employee review 403, approve 200 (history 2 rows), second review 409, list filter 200, unknown id 404, Hikari metrics visible | ✅ |
| 2026-10-13 | Bug found in smoke test: the POST response had nanosecond timestamps, but Oracle stores microseconds, so a later GET returned a different value. Service now truncates to micros; unit test added | ✅ fixed |
| 2026-10-13 | Final `mvnw test`: **29 run, 0 failures, 0 skipped** | ✅ Phase 1 exit criteria met |
| 2026-10-13 | Phase 2: Angular 22.2 app generated (`npx @angular/cli@22 new`, zoneless, standalone, Vitest); dev proxy `/api` → :8080 | ✅ |
| 2026-10-13 | Pages: catalog (screenshot-inspired card grid), form, list (filters in URL), detail + history, admin review panel; demo-user switcher + interceptor | ✅ `ng build` clean, no warnings |
| 2026-10-13 | `ng test`: 20 tests pass (form validation + server errors, list render/empty/error/retry, review visibility by role/status, approve flow, 409 reload, app shell). Mutation check: breaking the `canReview` rule makes 4 tests fail | ✅ |
| 2026-10-13 | Browser walkthrough (headless Edge via Playwright, against the real backend + Oracle): submit → PENDING → switch to admin → approve → APPROVED survives reload; review queue empty state; 390 px layout has no horizontal scroll; no console errors | ✅ Phase 2 exit criteria met |
| 2026-10-13 | Bug found in the browser (not reproducible in jsdom): `<select [value]>` showed the wrong option on page load (demo user + status filter). Fixed with `[selected]` per option; verified in Edge before and after | ✅ fixed |
| 2026-10-13 | Phases 1–2 merged to `main` (PR #1, #2) | ✅ |
| 2026-10-13 | Phase 3: Spring AMQP 4.1.1. Topic exchange + `notification.queue` + DLX/`notification.dlq`; Jackson 3 JSON converter (trusted package limited to the event package); publish via `@TransactionalEventListener(AFTER_COMMIT)`; `@RabbitListener` consumer; retries via Boot properties (`max-retries=2`, as `max-attempts` was replaced in Boot 4); custom `MessageRecoverer` records FAILED (Oracle `MERGE`) then dead-letters; dedup on `UNIQUE(EVENT_ID)` | ✅ |
| 2026-10-13 | Added read-only `GET /api/requests/{id}/notifications` + UI Notifications panel to show publish → consume → log (brief §10 "clear way to demonstrate") | ✅ |
| 2026-10-13 | First test run: test Oracle container hit Testcontainers' 60 s startup timeout while Docker was also pulling RabbitMQ → raised to 4 min; one unit test's mock did not set a generated id → fixed | ✅ fixed |
| 2026-10-13 | `mvnw test`: **41 run, 0 failures, 0 skipped** (incl. 5 messaging integration tests on real RabbitMQ + Oracle: publish→consume→log, reviewed event, duplicate delivery stored once, retries→FAILED→DLQ with matching message id, rollback publishes nothing). Log trace: failing event received exactly 3 times, then dead-lettered | ✅ |
| 2026-10-13 | Live check vs compose RabbitMQ: bindings as designed; happy path PROCESSED ×2; `Legacy Mainframe` → FAILED + 1 msg in DLQ with `x-death reason=rejected`. (Management API showed a stale "1 message" in the main queue; `rabbitmqctl` confirmed 0.) | ✅ |
| 2026-10-13 | `ng test`: 23 pass; browser check in Edge: Notifications panel shows Submitted/Reviewed PROCESSED and the FAILED one with its error; no console errors | ✅ Phase 3 exit criteria met |
| 2026-10-13 | PR #3 (RabbitMQ) opened; not merged | — |
| 2026-10-14 | **Decision: replace RabbitMQ with Apache Kafka** (user: team/company standard). PR #3 to be closed in favour of the Kafka PR. Broker-independent parts kept as-is: `NOTIFICATION_LOG` + `MERGE`, dedup, after-commit publishing, notifications endpoint and UI panel | ✅ |
| 2026-10-14 | Kafka implementation: Spring Kafka 4.1.1 / client 4.2.1; topics `access-request.events` + `.DLT` (3 partitions, created by `NewTopic` beans); key = request id; `KafkaTemplate` async send with fail-fast producer timeouts; `@KafkaListener` group `notification-service`; `DefaultErrorHandler` (1 + 2 retries, exponential) + recoverer (FAILED row) + `DeadLetterPublishingRecoverer` with an explicit DLT name; `ErrorHandlingDeserializer` against poison pills; retry settings as a typed `@ConfigurationProperties` record. Compose: `apache/kafka:4.2.2` KRaft single node, optional kafbat Kafka UI | ✅ |
| 2026-10-14 | First test run reported BUILD SUCCESS but **15 tests skipped**: Docker Desktop was not running after a restart. Started it, re-ran | ⚠️ → ✅ |
| 2026-10-14 | `mvnw test`: **41 run, 0 failures, 0 skipped** on real Kafka 4.2.2 + Oracle. Log: failing event received 3× at the **same partition/offset**, then dead-lettered; events spread over all 3 partitions; exactly 1 runtime duplicate skip (the deliberate one) | ✅ |
| 2026-10-14 | Live check vs compose Kafka: request 41's submitted + reviewed events on the same partition (offsets 0, 1); `Legacy Mainframe` → FAILED; Kafka CLI: both topics 3 partitions, consumer-group lag 0, DLT record on the original partition with `kafka_dlt-original-topic` header and matching `eventId`; Kafka UI cluster ONLINE | ✅ |
| 2026-10-14 | `ng test`: 23 pass; Edge check of the Notifications panel passes, no console errors (an empty list seen mid-Refresh was the loading state, confirmed with a separate check) | ✅ Phase 3 (Kafka) exit criteria met |
| — | Not verified: replaying a dead-lettered record (re-publish it from the DLT to `access-request.events`). The FAILED → PROCESSED code path for a replay is unit-tested only | ⚠️ open |
| 2026-10-14 | Phase 3 (Kafka) merged (PR #4); RabbitMQ container, volume and `.env` lines removed | ✅ |
| 2026-10-14 | Phase 4: WSO2 APIM 4.7.0 in Compose (profile `gateway`), healthy in ~90 s, ~1.4 GB RAM. First start logged "Automatic encryption key generation failed" (it tried to write into the read-only config) → `[encryption] key` from `.env` → clean log | ✅ fixed |
| 2026-10-14 | User/role management: SCIM `/scim2/Users` 404, `/scim2/v2/*` 401 (user-management scopes not granted) → used the `RemoteUserStoreManagerService` SOAP admin service (basic auth) | ✅ |
| 2026-10-14 | Inspected the backend JWT with a temporary echo server: RS256, `sub` = user UUID, `scope` present, **no username**, `Authorization` header stripped. Tried: opaque-token app (4.7 ignores `tokenType: OAUTH`), `include_username_in_access_token` (token endpoint HTTP 500 → reverted), SP subject claim via SOAP (`StackOverflowError` in WSO2). Official 4.x docs confirm: JWT tokens → backend JWT built from the token's claims only. **User decision: use the WSO2 user id as the actor; PKCE login for Angular** | ✅ decided |
| 2026-10-14 | Gateway security matrix (curl): gateway no/invalid token 401; `/health` public 200; ted GET/POST 200/201; ted PATCH 403 (WSO2 scope check); alice PATCH 200; direct backend without JWT, with `X-Demo-*` headers, with `alg:none` or a foreign-key RS256 JWT → 401 | ✅ |
| 2026-10-14 | Bug: `Location` header leaked the internal backend URL (`http://host.docker.internal:8080/...`) through the gateway → relative `requests/{id}` (RFC 3986), correct both directly and via the gateway | ✅ fixed |
| 2026-10-14 | Backend tests: 15 new (verifier: valid / expired / skew / foreign key / `alg:none` / HS256-with-public-key / issuer / subject / garbage; gateway-mode MVC slice). Mutation check: skipping the signature check fails 2 tests. Full suite **56 run, 0 failures, 0 skipped** | ✅ |
| 2026-10-14 | Frontend: auth tests (PKCE vs the RFC 7636 test vector, authorize URL, code exchange without a secret, state mismatch, WSO2 error, scope→role, Bearer only on API calls, logout) → **31 pass**; `ng build` clean in both configurations (a type import broke the gateway build because `fileReplacements` swaps `environment.ts` → moved types to `environment.model.ts`) | ✅ |
| 2026-10-14 | Browser (Edge) through the real WSO2 login page: ted signs in (consent) → EMPLOYEE, submits; sign-out via WSO2 logout page; alice → ADMIN, approves; notifications via Kafka through the gateway; every API call Bearer, the token call without a secret. Console errors only from WSO2's own login page (`<svg>` attribute) | ✅ |
| 2026-10-14 | Reproducibility: WSO2 container deleted and recreated, `setup.mjs` once (10 s), the security matrix and browser flow re-run → identical results. Demo mode regression walkthrough passes | ✅ Phase 4 exit criteria met |
| — | Not verified in Phase 4: refresh tokens, token revocation, multiple gateway nodes; WSO2's "default internal credentials" warning left as-is (local only) | ⚠️ open |
| 2026-10-14 | Phase 4 merged (PR #5). Phase 5 audit: `.env.example`'s `WSO2_ENCRYPTION_KEY` placeholder was not hex (copying it verbatim would break WSO2) → `infra/init-env.mjs` creates `.env` with correctly formatted random secrets, never overwrites | ✅ fixed |
| 2026-10-14 | Docs: `docs/architecture.md` (6 Mermaid diagrams: system, layers, submit sequence, PKCE/gateway sequence, ER model, lifecycle; decisions; limitations and production improvements), `docs/concepts.md` (brief §8/§11/§18 explanations), README restructured (quick start, docs index, stack, layout). Diagrams rendered in Edge: one parse error (`;` ends a statement in sequence diagrams) and clipped titles fixed. Link checker: 38 links + anchors OK | ✅ |
| 2026-10-14 | Fresh clone (no `.env`, `node_modules`, generated files): `init-env` → `npm ci` → 31 tests + both builds; `./mvnw test` 56/0/0 | ✅ |
| 2026-10-14 | Final acceptance in gateway mode: browser flow OK; failure path FAILED after 3 deliveries. The notifications panel looked empty or stuck on "Loading…" in two runs: caused by the test script reading mid-reload (and once by a regex the shell mangled), confirmed by sampling the panel at +100 ms / +500 ms / +3 s (both PROCESSED each time). Not an app bug | ✅ Phase 5 exit criteria met |

---

## 1. Environment inspection (Phase 0 findings)

| Tool | Found | Needed | Action |
|---|---|---|---|
| Repository | Empty folder, **not a git repo** | git repo | `git init` at start of Phase 1 |
| Java | JRE 1.8.0_503 only. `JAVA_HOME` points to `C:\Program Files\Java\jdk-19`, which **does not exist** | JDK 21 | Install Temurin 21 (`winget install EclipseAdoptium.Temurin.21.JDK`), fix `JAVA_HOME` |
| Maven | Not installed | Maven 3.9+ | **Not required** — project ships the Maven Wrapper (`mvnw.cmd`) |
| Node.js / npm | v22.23.2 / 10.9.8 | Node `^22.22.3` for Angular 22 | OK |
| Angular CLI | Not installed globally | Angular CLI 22 | Use `npx @angular/cli@22` (no global install needed) |
| Docker | Not installed | Docker Desktop (for Kafka, Oracle, WSO2, Testcontainers) | Install Docker Desktop; requires enabling **Virtual Machine Platform** (WSL2 currently reports it as disabled) + reboot |
| Oracle DB | No local service, nothing on port 1521 | Oracle Database Free 23ai | Run via Docker (`gvenzl/oracle-free`), or native Windows installer, or an existing DB |
| Kafka *(was RabbitMQ until 2026-10-14)* | Not installed | Apache Kafka 4.x | Docker (`apache/kafka`, KRaft) |
| WSO2 API Manager | Not installed | WSO2 APIM 4.7.0 | Docker (`wso2/wso2am:4.7.0`) as an optional Compose profile |
| winget | Available | — | Used for installs |
| Hardware | 16 GB RAM, 8 logical CPUs, ~49 GB free on C:, BIOS virtualization enabled | — | Enough for Oracle (~2 GB) + Kafka (~500 MB) + WSO2 (~2–3 GB) + IDE, but WSO2 should be started only when needed |

**Blocking for Phase 1:** JDK 21 (to compile) and an Oracle database (to run migrations and integration tests).
Code can be written before Oracle exists; the exit criteria cannot be met until it does.

## 2. Selected versions (verified against Maven Central / npm / Docker Hub on 2026-10-13)

| Component | Version | Why / compatibility notes |
|---|---|---|
| Java | **21 LTS** (Temurin) | Brief default. Spring Boot 4.x requires Java 17+. |
| Spring Boot | **4.1.1** | Latest stable. |
| MyBatis Spring Boot Starter | **4.1.0** | Built against Spring Boot 4.1.0 (MyBatis 3.5.19, mybatis-spring 4.1.0). The 3.0.x line is for Boot 3 and is **not** compatible. |
| Spring for Apache Kafka | 4.1.1 (Kafka client 4.2.1) | Managed by Boot. *(Replaced Spring AMQP 4.1.1 on 2026-10-14.)* |
| HikariCP | 7.0.2 | Managed by Boot (default pool for `spring-boot-starter-jdbc`). |
| Oracle JDBC | `ojdbc17` 23.26.3.0.0 | Managed by Boot; `ojdbc17` targets JDK 17+. |
| Flyway | 12.4.0 + `flyway-database-oracle` | Managed by Boot. Flyway Community supports recent Oracle releases incl. 23ai. |
| JUnit | 6.0.3 (Jupiter) | Managed by Boot 4.1. |
| Testcontainers | 2.0.5 | Managed by Boot; used for Oracle + Kafka integration tests. |
| Angular / CLI | **22.2.x** | Latest stable; standalone components, signals, Vitest as default test runner. |
| Oracle DB | Oracle Database Free 23ai (`gvenzl/oracle-free:23-slim-faststart`) | Public Docker Hub image, no Oracle account login needed. Official alternative: `container-registry.oracle.com/database/free`. |
| Apache Kafka | `apache/kafka:4.2.2` (KRaft, single node) + optional `ghcr.io/kafbat/kafka-ui:v1.5.0` | Same 4.2 line as the client. Kafka UI on :8081 (profile `ui`). |
| WSO2 API Manager | **4.7.0** (`wso2/wso2am:4.7.0`) | Latest GA (2026-04-29). This is API Manager, not Identity Server; its built-in Key Manager issues the OAuth2 tokens. |

Exact versions are pinned in `pom.xml` / `package.json` / `docker-compose.yml` once generated; this table will be kept in sync.

## 3. Repository layout

```
it-access-portal/
├── IMPLEMENTATION_PLAN.md
├── README.md
├── .gitignore
├── .env.example                  # placeholders only
├── docker-compose.yml            # oracle, kafka (+ kafka-ui profile "ui"); wso2 under profile "gateway"
├── docs/
│   ├── architecture.md           # diagram (Mermaid) + request/event flow
│   ├── api-examples.http         # sample requests (direct + gateway)
│   ├── demo-script.md            # end-to-end smoke test (brief §15)
│   └── wso2-setup.md
├── backend/
│   ├── mvnw, mvnw.cmd, pom.xml
│   └── src/
│       ├── main/java/com/itaccess/portal/
│       │   ├── AccessPortalApplication.java
│       │   ├── controller/       # RequestController, HealthController
│       │   ├── service/          # AccessRequestService (@Transactional boundaries)
│       │   ├── mapper/           # AccessRequestMapper, RequestHistoryMapper, NotificationLogMapper
│       │   ├── domain/           # AccessRequest, RequestHistory, NotificationLog, RequestStatus
│       │   ├── dto/request/      # CreateAccessRequest, ReviewRequest, RequestFilter
│       │   ├── dto/response/     # AccessRequestResponse, HistoryEntryResponse, ...
│       │   ├── messaging/        # KafkaConfig, EventPublisher, NotificationListener, events/
│       │   ├── security/         # CurrentUser resolution (dev headers → later WSO2 JWT)
│       │   ├── exception/        # NotFound, InvalidStatusTransition, GlobalExceptionHandler
│       │   └── config/           # MyBatis/Hikari/CORS config
│       ├── main/resources/
│       │   ├── application.yml, application-dev.yml
│       │   ├── mapper/*.xml      # MyBatis SQL (resources, so Maven packages it)
│       │   └── db/migration/V1__create_schema.sql
│       └── test/java/...
└── frontend/                     # Angular 22 app
    └── src/app/
        ├── core/                 # api services, models (typed DTOs), interceptors, role state
        ├── features/requests/    # list, new (catalog + form), detail (+ admin review panel)
        └── shared/               # status badge, loading/empty/error blocks
```

> The brief lists `mapper/xml/` under the Java packages. The XML files go in `src/main/resources/mapper/` instead. Maven does not copy non-Java files from `src/main/java` unless the build is reconfigured, so `resources` is the conventional location. The mapper *interfaces* stay in `com.itaccess.portal.mapper`.

## 4. Key design decisions

### Database (Oracle)
- **Primary keys:** `NUMBER(19) GENERATED BY DEFAULT AS IDENTITY`. MyBatis reads the generated ID back with `useGeneratedKeys="true" keyProperty="id" keyColumn="ID"`. The Oracle driver requires the explicit `keyColumn`.
- **`COMMENT` is a reserved word in Oracle**, so the history column is named `REVIEW_COMMENT`. (Quoting `"COMMENT"` everywhere would also work, but it is error-prone.)
- **Status:** `VARCHAR2(20)` + `CHECK (STATUS IN ('PENDING','APPROVED','REJECTED'))` ↔ Java enum `RequestStatus` (MyBatis's built-in `EnumTypeHandler` maps it by name).
- **Timestamps (revised in Phase 1):** `TIMESTAMP(6) WITH TIME ZONE`, mapped to `java.time.OffsetDateTime` via MyBatis's built-in `OffsetDateTimeTypeHandler`. The offset is stored with the value, so correctness does not depend on the JVM or DB server time zone (no `-Duser.timezone` flag needed). The service writes UTC from an injected `Clock` (testable); `DEFAULT SYSTIMESTAMP` is only a safety net.
- **Oracle null parameters:** `mybatis.configuration.jdbc-type-for-null=NULL`. MyBatis's default (`OTHER`) makes the Oracle driver fail on a null bind (`Invalid column type: 1111`), e.g. `OLD_STATUS` in the first history row.
- **Constraints/indexes:** FK `REQUEST_HISTORY.REQUEST_ID → ACCESS_REQUEST.ID`; FK `NOTIFICATION_LOG.REQUEST_ID → ACCESS_REQUEST.ID`; `UNIQUE(NOTIFICATION_LOG.EVENT_ID)` (dedup); indexes on `ACCESS_REQUEST(STATUS, CREATED_AT)`, `REQUEST_HISTORY(REQUEST_ID)`.
- **No H2**: integration tests run against real Oracle via Testcontainers. If Docker is absent they are *skipped* (`@Testcontainers(disabledWithoutDocker = true)`) and that is reported, never silently passed.

### API
- Endpoints exactly as in the brief's §6. List filters: `status`, `q` (case-insensitive match on requester or system name), and simple `page`/`size`.
- **Error format:** Spring's built-in RFC 9457 `ProblemDetail` (`type`, `title`, `status`, `detail`, `instance`) plus an `errors` map for field validation failures. 400 validation, 404 not found, **409 invalid transition**.
- **Safe status transition:** `UPDATE ACCESS_REQUEST SET STATUS = #{newStatus}, UPDATED_AT = ... WHERE ID = #{id} AND STATUS = 'PENDING'`. If 0 rows are updated, the service checks whether the request exists → 404 or 409. This also covers two admins reviewing the same request at once.
- **Join demo:** `GET /api/requests/{id}` uses one query that joins `ACCESS_REQUEST` and `REQUEST_HISTORY`, mapped by a MyBatis `<resultMap>` with `<collection>`.

### Roles before WSO2 (MVP)
- The Angular header has a clearly labelled **"Demo user" switcher** (Employee / Admin). An interceptor sends `X-Demo-User` and `X-Demo-Role`.
- The backend resolves a `CurrentUser` from those headers while `portal.auth.mode=demo-headers` and enforces it in the service: only `ADMIN` can PATCH a status (403 otherwise). The acting user is recorded in `REQUEST_HISTORY.CHANGED_BY`.
- *Revised in Phase 1:* both roles can list all requests. Filtering employees to "their own" requests would need a `REQUESTED_BY` column that is separate from the free-text requester name, which isn't in the brief's schema. It can be added later if wanted.
- This is **not security**. It is documented as such and replaced in Phase 4.

### Transactions & messaging consistency
- `create()`: insert request + insert initial history row (`OLD_STATUS = NULL`) in one `@Transactional` method.
- `review()`: conditional status update + history insert in one `@Transactional` method. Both commit or both roll back.
- Events are published **after commit** via `@TransactionalEventListener(phase = AFTER_COMMIT)`, so a rolled-back transaction never emits an event.
- **Known gap (documented):** if the app crashes between the DB commit and the Kafka publish, that event is lost. The fix is a transactional outbox (optional, after the MVP).

### Kafka topology (revised 2026-10-14: replaces the original RabbitMQ design)
```
topic access-request.events        3 partitions, key = request id (keeps one request's events in order)
   └─► consumer group notification-service  (@KafkaListener → NotificationService → NOTIFICATION_LOG)
          │ still failing after 1 attempt + 2 retries (DefaultErrorHandler, exponential back-off)
          ▼
topic access-request.events.DLT    3 partitions; failed record copied to the same partition number
```
- JSON payloads (`JacksonJsonSerializer` / `JacksonJsonDeserializer` wrapped in `ErrorHandlingDeserializer`, no type headers; the consumer's target type is fixed and the trusted package is limited to the event package). Events: `AccessRequestSubmitted` and `AccessRequestReviewed`, each with `eventId (UUID)`, `requestId`, `eventType`, `occurredAt`, plus minimal metadata (actor, system, new status).
- **Consumer:** `@KafkaListener` → `NotificationService`, which inserts into `NOTIFICATION_LOG` (status `PROCESSED`) and logs a simulated email.
- **Retry/DLT:** `DefaultErrorHandler` retries the record in place (`portal.kafka.retry.*`); then a `ConsumerRecordRecoverer` records `FAILED` (Oracle `MERGE`) and `DeadLetterPublishingRecoverer` copies the record to the DLT with `kafka_dlt-*` headers. Unreadable JSON goes straight to the DLT.
- **Dedup:** the `UNIQUE(EVENT_ID)` insert is the guard. A `DuplicateKeyException` means "already processed" → skip (offset still committed).
- **How to show it working:** app logs (published to partition/offset → received → recorded), Kafka UI (topics, messages, consumer-group lag), Kafka CLI in the container, the UI Notifications panel and a `SELECT` on `NOTIFICATION_LOG` (demo script step 3).

### HikariCP
`spring.datasource.hikari.*` set explicitly in `application.yml`, with values from env vars: `pool-name=AccessPortalPool`, `maximum-pool-size=10`, `minimum-idle=2`, `connection-timeout=30000`, `idle-timeout=600000`, `max-lifetime=1800000`. Pool metrics are exposed in `dev` through Actuator (`/actuator/metrics/hikaricp.connections.active`, etc.). Credentials never appear in metrics or logs.

### Secrets
`.env` (git-ignored) is loaded by Compose. The backend reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` from env vars (Spring's `spring.config.import=optional:file:.env[.properties]` for convenience). `.env.example` holds placeholders only.

### Frontend (Angular 22)
- Standalone components, signals for component state, Reactive Forms, plain CSS with custom properties (no UI framework). Responsive layout.
- **UI direction from the attached screenshot:** an Okta-style layout with a left sidebar (My Requests / Request Access / Review Queue for admins), a top-right user/role switcher, and the "Request access" page as a **searchable grid of system cards** (icon, type label, name, description). Choosing a card opens the submit form with `systemName` pre-filled, and the form also lets the user pick the access level and enter a reason.
  - The card catalog is a **static, typed list in the frontend** (GitLab, Jira, HR System, AWS, Datadog, Snowflake, …). It is a list of choices, not fake API data. The backend still validates `systemName` as a required string. *Alternative (needs your OK — it changes the schema):* a `SYSTEM_CATALOG` table + `GET /api/systems`.
- Routes: `/requests` (list + status filter + search), `/requests/new` (catalog → form), `/requests/:id` (details + history timeline + admin review panel).
- All HTTP calls go through `AccessRequestApiService`. The base URL comes from `environment.ts`:
  - `environment.development.ts` → `/api`, proxied to `http://localhost:8080` by `proxy.conf.json` (no CORS needed).
  - `environment.gateway.ts` → `https://localhost:8243/accessportal/1.0.0` (WSO2), selected with `ng serve --configuration gateway`.
- Tests: Vitest (Angular 22 default) for form validation, list rendering, error state, and the visibility of the approve/reject controls.

### WSO2 (Phase 4), as built (2026-10-14; details in docs/wso2-setup.md)
- **Scripted setup** (`infra/wso2/setup.mjs`, idempotent, ~10 s) instead of importing an OpenAPI spec: roles and demo users via the `RemoteUserStoreManagerService` admin service (the SCIM endpoints did not accept the available credentials in 4.7); the API is created with explicit resources and scopes through the Publisher REST API v4, deployed as a revision and published; the Developer Portal application is subscribed and given keys.
- **Scopes:** `access_request:read` (all GETs), `access_request:submit` (POST) and `access_request:review` (PATCH status), bound to roles `portal_employee` / `portal_admin`; `GET /health` is public.
- **Backend JWT** (`[apim.jwt]`, base64url, RS256): the backend's `gateway` profile accepts `/api/**` only with a valid `X-JWT-Assertion`, verified with WSO2's exported certificate (nimbus-jose-jwt; RS256 only; issuer, expiry and subject checked). Actor = `sub` (WSO2 user UUID: 4.x does not pass usernames to the backend with JWT access tokens; three workarounds tried and rejected, see wso2-setup.md). Admin = scope `access_request:review`.
- **Angular:** `npm run start:gateway` swaps the environment; authorization code + PKCE (S256) as a public client (`pkceMandatory`, `bypassClientCredentials`); the token lives in `sessionStorage`; a Bearer interceptor, a sign-in guard and OIDC logout. The dev server proxies `/gateway` and `/oauth2/token` (CORS on WSO2's :9443 could not be enabled via `deployment.toml`).
- Config: `deployment.toml` mounted read-only; admin password and an `[encryption] key` come from `.env` (4.7 otherwise tries to write a generated key into the read-only file).

## 5. Phases & exit criteria (summary)

| Phase | Deliverable | Exit criteria | Depends on |
|---|---|---|---|
| 1 | Spring Boot + Flyway + MyBatis + Hikari + REST + tests | Create/list/detail/review/history work against Oracle; unit + mapper integration tests pass | JDK 21, Oracle |
| 2 | Angular app | Full workflow in the browser | Phase 1 |
| 3 | Kafka producer/consumer, DLT, dedup *(first built on RabbitMQ)* | Event traced: publish → consume → `NOTIFICATION_LOG` | Kafka |
| 4 | WSO2 gateway + OAuth2 | Authorized call OK, unauthorized rejected, Angular via gateway | WSO2 container |
| 5 | Docs, diagram, demo script, final test run | Another dev can reproduce the demo from the README | All |

## 6. Decisions (all resolved)

| # | Question (Phase 0) | Decision |
|---|---|---|
| 1 | How to run the infrastructure | Docker Desktop (Virtual Machine Platform enabled, reboot); Compose for Oracle, Kafka, WSO2 |
| 2 | System catalog source | Static, typed list in the frontend (`system-catalog.ts`); not mock API data |
| 3 | Base Java package | `com.itaccess.portal` |
| 4 | Messaging broker (2026-10-14) | **Apache Kafka** instead of RabbitMQ (team standard; overrides the brief for this one component) |
| 5 | Actor identity in gateway mode (2026-10-14) | WSO2 user id (4.7 passes no username with JWT access tokens) |
| 6 | Angular login in gateway mode (2026-10-14) | Authorization code + PKCE, public client |

## 7. Final state (Phase 5, 2026-10-14)

All phases complete. Final verification on this date:

| Check | Result |
|---|---|
| `./mvnw test` (working copy and a **fresh clone**) | 56 run, 0 failures, **0 skipped** (Oracle 23ai + Kafka 4.2 via Testcontainers) |
| `ng test` / `ng build` both configurations (fresh clone after `npm ci`) | 31 pass / clean |
| Fresh clone needs nothing beyond `node infra/init-env.mjs` | ✅ (no hidden dependency on local files) |
| Browser, gateway mode: WSO2 login (PKCE) → submit (employee) → sign out → approve (admin) → Kafka notifications | ✅ |
| Failure path through the gateway (`Legacy Mainframe`) | FAILED after exactly 3 deliveries, dead-lettered |
| Browser, direct mode (demo users), incl. 390 px layout | ✅ (after Phase 4) |
| Docs: 6 Mermaid diagrams render; 38 relative links + anchors resolve | ✅ |

Known limitations and production improvements: [docs/architecture.md §8](docs/architecture.md#8-known-limitations-and-production-improvements).
