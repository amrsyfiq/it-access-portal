# IT Access Request Portal

Employees request access to internal systems (GitLab, Jira, AWS, ...). Administrators approve or reject the requests, and every status change is recorded in a history table.

Learning and portfolio project. Stack: Spring Boot 4.1 · MyBatis 3 · HikariCP · Oracle Database Free 23ai · Flyway · RabbitMQ *(Phase 3)* · Angular 22 *(Phase 2)* · WSO2 API Manager *(Phase 4)*.

> Status: **backend (Phase 1) working and tested against Oracle.** Frontend, messaging and gateway are not built yet. See [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

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

# 2. Start Oracle (first start pulls ~1 GB). Wait until STATUS shows "(healthy)".
docker compose up -d oracle
docker compose ps

# 3. Start the backend. Flyway creates the tables on first start.
cd backend
./mvnw spring-boot:run          # Windows cmd/PowerShell: mvnw.cmd spring-boot:run

# 4. Check it
curl http://localhost:8080/api/health     # {"status":"UP","database":"UP"}
```

Then try the requests in [docs/api-examples.http](docs/api-examples.http).

Stop with `Ctrl+C` (backend) and `docker compose stop` (Oracle). `docker compose down -v` also **deletes the database volume**.

## Tests

```bash
cd backend && ./mvnw test        # JUnit: unit, web-layer, Oracle integration
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
| `java -version` shows 1.8 | Open a new terminal after installing JDK 21, or set `JAVA_HOME` to the JDK 21 folder |
