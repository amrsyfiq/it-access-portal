# Concepts: how and why it works

Short explanations of the ideas this project is built on, each pointing at the real code. They are written for someone who knows another backend framework; comparisons use Laravel where it helps. Read alongside [architecture.md](architecture.md).

---

## 1. Controller → Service → Mapper

One request (`PATCH /api/requests/7/status`) passes three layers, each with one job:

| Layer | File | Job | Laravel equivalent |
|---|---|---|---|
| Controller | [AccessRequestController](../backend/src/main/java/com/itaccess/portal/controller/AccessRequestController.java) | Map the URL to a method, validate input (`@Valid`), choose the HTTP status | Controller + Form Request |
| Service | [AccessRequestService](../backend/src/main/java/com/itaccess/portal/service/AccessRequestService.java) | Business rules ("only admins", "only PENDING"), transaction boundary | A service / action class |
| Mapper | [AccessRequestMapper](../backend/src/main/java/com/itaccess/portal/mapper/AccessRequestMapper.java) + [XML](../backend/src/main/resources/mapper/AccessRequestMapper.xml) | Run SQL, map rows to objects | Query builder / repository |

**Why bother?** Each layer can change without touching the others, and each can be tested alone: the service tests mock the mappers (no database), the controller tests mock the service (no business logic), the mapper tests hit real Oracle.

**Dependency injection:** nothing calls `new AccessRequestService(...)`. Spring builds every `@Service`/`@Component` at startup and passes them into constructors that need them (*constructor injection*). Same idea as Laravel's service container.

## 2. DTO vs domain object

There are two "access request" classes on purpose:

- [`AccessRequest`](../backend/src/main/java/com/itaccess/portal/domain/AccessRequest.java) (**domain**): mirrors a database row; MyBatis fills it; never leaves the service layer.
- [`AccessRequestResponse`](../backend/src/main/java/com/itaccess/portal/dto/response/AccessRequestResponse.java) / [`CreateAccessRequest`](../backend/src/main/java/com/itaccess/portal/dto/request/CreateAccessRequest.java) (**DTOs**): the public JSON contract, as Java `record`s.

Renaming a column changes the domain class and the SQL, but not the API clients use. The DTO also decides what is accepted: `CreateAccessRequest` has no `status` field, so a client cannot submit a request that is already `APPROVED`. In Laravel terms: an API Resource for output and a Form Request for input, instead of returning the Eloquent model.

## 3. MyBatis vs Spring Data JPA (and why MyBatis here)

| | Spring Data JPA / Hibernate | MyBatis (this project) |
|---|---|---|
| You write | Entity classes + annotations; SQL is generated | The SQL itself, in XML |
| Loading related data | Lazy or eager associations, automatically | You write the join and the `<resultMap>` |
| Saving | Change an entity, and Hibernate decides what UPDATE to run | You call `updateStatusIfPending(...)`, which runs exactly the SQL in the XML |
| Good at | CRUD-heavy domains, rapid development | Hand-tuned or database-specific SQL; teams that want to review the SQL |
| Closest Laravel analogue | Eloquent | Query builder / raw SQL in a repository |

This project uses Oracle features deliberately: IDENTITY keys returned through `getGeneratedKeys`, `OFFSET … FETCH` paging, `MERGE` (insert-or-update in one statement, in [NotificationLogMapper.xml](../backend/src/main/resources/mapper/NotificationLogMapper.xml)), and `TIMESTAMP WITH TIME ZONE`. With MyBatis, what you read is what runs.

**Interface + XML:** `AccessRequestMapper` is just a Java interface. MyBatis generates its implementation at startup by matching each method name to a `<select id="...">` / `<update id="...">` in the XML with the same namespace. `#{id}` becomes a JDBC `?` parameter, so values are bound, never pasted into the SQL (no SQL injection). `${...}` would paste text, and is not used anywhere.

**One join instead of N queries:** `findByIdWithHistory` joins `ACCESS_REQUEST` with `REQUEST_HISTORY`. The `<resultMap>` with `<collection>` folds the many result rows back into one request with a history list.

## 4. `@Transactional`

```java
@Transactional
public AccessRequestDetailResponse review(long id, ReviewRequest command, CurrentUser user) {
    requestMapper.updateStatusIfPending(...);   // statement 1
    historyMapper.insert(...);                  // statement 2
}
```

Spring wraps the method: it opens a transaction before the method runs (borrowing one connection from the pool for the whole method), **commits** if the method returns normally, and **rolls back** if it throws a `RuntimeException`. So the status change and its history row are saved together or not at all. The test `failedHistoryInsertRollsBackStatusChange` proves it against Oracle. In Laravel this is `DB::transaction(function () { ... })`, declared with an annotation instead of a closure.

Two things that surprise people:

- It works through a **proxy**: calling a `@Transactional` method from *another method in the same class* skips the proxy, so you get no transaction. Keep transactional methods public and call them from outside.
- `@Transactional(readOnly = true)` on read methods is a hint (and documentation), not a lock.

**Race-free without locks:** `UPDATE … WHERE ID = ? AND STATUS = 'PENDING'` checks and changes the status in one statement. If two admins review at once, Oracle blocks the second UPDATE until the first commits, re-checks the WHERE, and updates 0 rows, which the service turns into **409 Conflict**.

## 5. HikariCP: connection vs connection pool

- A **connection** is one open network session to Oracle (TCP plus login plus session memory on the server). Opening one takes tens of milliseconds; Oracle limits how many can exist.
- A **pool** keeps a few connections open and *lends* them out. A request borrows one, uses it, and gives it back, so no request pays the cost of opening one.

Settings ([application.yml](../backend/src/main/resources/application.yml)):

| Setting | Value | Meaning |
|---|---|---|
| `maximum-pool-size` | 10 | Never more than 10 connections; request 11 waits |
| `minimum-idle` | 2 | Keep 2 ready even when quiet |
| `connection-timeout` | 30 s | How long a request waits for a free connection before failing |
| `max-lifetime` | 30 min | Retire old connections before a firewall or the DB kills them silently |

Watch it live: `GET /actuator/metrics/hikaricp.connections.active` (dev profile). PHP-FPM usually opens one connection per request; Java apps are long-running processes, so a pool is the norm.

## 6. Kafka: producer, topic, consumer

- **Producer** ([AccessRequestEventPublisher](../backend/src/main/java/com/itaccess/portal/messaging/AccessRequestEventPublisher.java)): sends events to the topic `access-request.events`. It runs **after the database commit** (`@TransactionalEventListener(AFTER_COMMIT)`), so a rolled-back change never produces a message.
- **Topic + partitions:** an append-only log split into 3 partitions. Records with the same **key** (the request id) always land in the same partition and are read in order, so "submitted" always comes before "reviewed".
- **Consumer group** ([NotificationListener](../backend/src/main/java/com/itaccess/portal/messaging/NotificationListener.java)): Kafka spreads the partitions over the group's members. Each member remembers its position, the **offset**. Unlike a classic queue, reading does not delete the message; another group (say, audit) could read the same events independently.
- **At least once:** after a crash or rebalance a record can be delivered again, so the consumer is **idempotent**: `UNIQUE(EVENT_ID)` makes a repeat a no-op.
- **Retries and dead letters** ([KafkaConfig](../backend/src/main/java/com/itaccess/portal/messaging/KafkaConfig.java)): a failing record is retried in place (1 + 2 times), then recorded as `FAILED` and copied to `access-request.events.DLT`, and the consumer moves on.

Laravel analogy: dispatching a queued job after commit (`afterCommit()`) to a queue worker; `failed_jobs` plays the role of the DLT. The difference is that Kafka keeps the log, and many independent consumers can read it.

**Known gap:** the DB commit and the Kafka send are two systems with no shared transaction; if the app crashes in between, that event is lost. The standard fix is a **transactional outbox**: write the event to a table in the same DB transaction, and have a separate relay publish it.

## 7. WSO2 gateway vs backend

- The **gateway** (WSO2 API Manager) sits in front of the API: it checks the OAuth2 token, checks that the token has the scope this resource needs (`access_request:review` for approve), applies throttling, and forwards the call.
- The **backend** (Spring Boot) contains the business logic. In gateway mode it accepts a call only with the **signed JWT** the gateway adds (`X-JWT-Assertion`), verified with WSO2's public certificate ([GatewayJwtVerifier](../backend/src/main/java/com/itaccess/portal/security/GatewayJwtVerifier.java)). That proves the call came through the gateway; a direct call, or a forged token, gets 401.
- **Scopes vs roles:** users have roles in WSO2 (`portal_employee`, `portal_admin`); each scope is bound to roles, so WSO2 only grants an employee `read` and `submit`. The UI shows the approve button based on the scopes too, but hiding a button is never the protection: the gateway (403) and the backend check it again.
- **PKCE login:** the browser cannot keep a client secret, so it proves "I started this login" with a random *verifier*. It sends only the verifier's hash when the login starts, and the verifier itself when it redeems the code ([auth.ts](../frontend/src/app/core/auth.ts)).

Laravel analogy: the gateway is like auth + throttle middleware (think Sanctum or Passport), moved out of the application into a separate server that many APIs can share.

## 8. Angular service vs component

- A **component** ([request-detail.ts](../frontend/src/app/features/requests/request-detail.ts)) owns one piece of screen: its state (`loading` / `loaded` / `error` as a signal) and its template.
- A **service** ([access-request-api.ts](../frontend/src/app/core/access-request-api.ts)) is a shared singleton with no UI: it is the only class that knows the API's URLs. Components ask it for data.
- **Interceptors** add cross-cutting things to every HTTP call: the demo headers, or the `Bearer` token.

Because components never build URLs, switching the whole app from the backend to the WSO2 gateway was a configuration change (`environment.gateway.ts`), not edits across pages. In Blade terms: the component is a view plus its controller logic; the service is a shared API client.

## 9. Flyway migrations

Schema changes are numbered SQL files ([`db/migration/V1__create_schema.sql`](../backend/src/main/resources/db/migration/V1__create_schema.sql)). On startup Flyway applies the ones not yet in `flyway_schema_history`, in order. Never edit an applied migration (Flyway detects the checksum change); add `V2__...sql` instead. Same idea as `php artisan migrate`, but the migrations are plain SQL.

## 10. Testing strategy

| Level | Example | Needs | Proves |
|---|---|---|---|
| Unit | `AccessRequestServiceTest`, `NotificationServiceTest`, `GatewayJwtVerifierTest` | nothing | Business rules, security checks |
| Web slice | `AccessRequestControllerTest`, `GatewayModeControllerTest` | nothing | HTTP contract: status codes, JSON, validation, filters |
| Integration | `AccessRequestMapperIntegrationTest`, `AccessRequestWorkflowIntegrationTest`, `MessagingIntegrationTest` | Docker (Testcontainers) | Real Oracle SQL, real transactions, real Kafka |
| Frontend | `*.spec.ts` (Vitest) | nothing | Forms, rendering states, role visibility, PKCE |
| End to end | [demo-script.md](demo-script.md) | everything running | The whole workflow in a browser |

There is no H2 database: an in-memory database would "prove" SQL that Oracle might reject. If Docker is not running, the integration tests are reported as **skipped**, never as passed.
