# End-to-end demo script

A manual walkthrough that proves the workflow end to end. Each step says what to do and what you should see.
Step 6 runs the same app through the WSO2 API Manager gateway with a real OAuth2 login.

## 0. Start everything

```bash
docker compose up -d oracle kafka      # wait for "(healthy)" in: docker compose ps
cd backend && ./mvnw spring-boot:run   # terminal 1, http://localhost:8080
cd frontend && npm start               # terminal 2, http://localhost:4200
```

## 1. Employee submits a request (UI)

1. Open http://localhost:4200. The demo user (top right) is **Ted L · EMPLOYEE**.
2. Click **Request access**. You see a grid of system cards. Type `snow` in the search box: only Snowflake (and "Another system") remain.
3. Click **Jira**. The form opens with *System = Jira* and *Requester = Ted L* already filled in.
4. Click **Submit request** without filling anything in. *Access level* and *Business justification* show "This field is required." and no request is sent.
5. Choose `READ`, enter a reason of at least 10 characters, and submit.
6. **Expected:** you land on `/requests/{id}` with "Request submitted.", status **PENDING**, a history entry "Submitted by ted.l", and "Waiting for an administrator to review this request". No approve/reject buttons are shown.

## 2. The request is stored in Oracle

```bash
docker exec -it portal-oracle sqlplus -s portal/"$DB_PASSWORD"@FREEPDB1 <<'SQL'
SELECT ID, SYSTEM_NAME, STATUS, CREATED_AT FROM ACCESS_REQUEST ORDER BY ID DESC FETCH FIRST 3 ROWS ONLY;
SELECT REQUEST_ID, OLD_STATUS, NEW_STATUS, CHANGED_BY FROM REQUEST_HISTORY ORDER BY ID DESC FETCH FIRST 3 ROWS ONLY;
SQL
```

**Expected:** the new row has `STATUS = PENDING`, and there is one `REQUEST_HISTORY` row with `OLD_STATUS` null, `NEW_STATUS = PENDING`, `CHANGED_BY = ted.l`.

> IDs may jump (e.g. 1 → 21). Oracle IDENTITY columns hand out IDs in blocks of 20, and the unused rest of a block is discarded when the database restarts. This is normal; IDs are unique, not gap-free.

## 3. Event published, consumed and logged (Kafka)

1. **In the UI:** the request page has a **Notifications** panel. Within a second it shows **Submitted · PROCESSED** (click **Refresh** if it is not there yet; the consumer runs asynchronously).
2. **Backend log:** the event's journey, one line per step. `access-request.events-2@0` means partition 2, offset 0:

   ```text
   AccessRequestEventPublisher : Published AccessRequestSubmitted <eventId> for request 41 to access-request.events-2@0
   NotificationListener        : Received AccessRequestSubmitted <eventId> for request 41 from partition 2 offset 0
   NotificationService         : [SIMULATED EMAIL] New access request #41 for Datadog submitted by ted.l
   NotificationService         : Notification for AccessRequestSubmitted <eventId> on request 41 recorded as PROCESSED
   ```

   After the admin approves (step 4), the *reviewed* event for the same request lands on the **same partition** at the next offset (`-2@1`), because the record key is the request id.
3. **Kafka UI** (optional, `docker compose --profile ui up -d`): http://localhost:8081
   - *Topics* → `access-request.events` has 3 partitions; *Messages* shows each event's key (request id) and JSON value.
   - *Consumers* → group `notification-service`, lag 0 on every partition.
4. **Or the Kafka command-line tools** inside the container:

   ```bash
   docker exec portal-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
     --describe --group notification-service      # CURRENT-OFFSET = LOG-END-OFFSET, LAG 0
   ```

5. **Oracle:**

   ```sql
   SELECT EVENT_ID, EVENT_TYPE, PROCESSING_STATUS, PROCESSED_AT, ERROR_MESSAGE
   FROM NOTIFICATION_LOG WHERE REQUEST_ID = <id> ORDER BY ID;
   ```

   **Expected:** one `AccessRequestSubmitted` row, `PROCESSED`, with `PROCESSED_AT` set.
6. Same via the API: `GET http://localhost:8080/api/requests/<id>/notifications`.

### 3b. Failure path: retries, then the dead-letter topic

`.env` sets `NOTIFICATIONS_FAIL_FOR_SYSTEM=Legacy Mainframe`, which makes the simulated email fail for that system name (demo switch; leave it empty to turn it off).

1. Request access via **Another system** with *System* = `Legacy Mainframe`.
2. **Backend log:** `Received … from partition N offset M` appears **3 times with the same offset** (1 attempt + 2 retries, about 1 s and 2 s apart: Kafka retries the record in place), then `KafkaConfig : Giving up on AccessRequestSubmitted …`.
3. **UI Notifications panel:** **Submitted · FAILED** with "Simulated notification failure for system 'Legacy Mainframe'".
4. **Dead-letter topic:** Kafka UI → *Topics* → `access-request.events.DLT` → *Messages*, or:

   ```bash
   docker exec portal-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
     --topic access-request.events.DLT --from-beginning --timeout-ms 5000 \
     --property print.key=true --property print.headers=true --property print.partition=true
   ```

   **Expected:** one record on the same partition number as the original, key = request id, value = the event JSON (its `eventId` equals the FAILED row's `EVENT_ID`), and headers such as `kafka_dlt-original-topic:access-request.events` and `kafka_dlt-exception-message`.
5. The request itself is unaffected (still PENDING, reviewable): notifications are a side effect and never roll back the business change.

### 3c. What does *not* get published

A review that fails and rolls back (e.g. the transaction-rollback integration test) raises its event inside the transaction, but the event is only sent **after commit**, so nothing is published. This is covered by `MessagingIntegrationTest.rolledBackReviewPublishesNothing`.

## 4. Administrator approves

1. Switch the demo user to **Alice Admin · ADMIN**. The sidebar gains **Review queue**, and the open request now shows a **Review this request** panel.
2. Enter a comment and click **Approve**.
3. **Expected:** "Request approved.", status **APPROVED**, a new history entry "PENDING → APPROVED by alice.admin" with the comment, and the review panel disappears. After a moment (or **Refresh**), the Notifications panel also shows **Reviewed · PROCESSED**.
4. Reload the page (F5). The status is still APPROVED, because it is read back from the database.
5. Try to review it again via the API (`docs/api-examples.http`, "Review again"). **Expected:** `409 Conflict`.

## 5. List, filters, review queue

1. **All requests** lists every request with ID, requester, system, access level, status and creation date.
2. Change the **Status** filter; the URL changes to `?status=...` and the list reloads. Reloading the page keeps the filter.
3. **Review queue** (`?status=PENDING`) shows pending requests, or a "No requests found." empty state.
4. Stop the backend and reload the list. **Expected:** "Cannot reach the server. Check that the backend is running." with a **Try again** button.

## 6. Through the WSO2 gateway (OAuth2)

Setup and background: [wso2-setup.md](wso2-setup.md). Start WSO2 (`docker compose --profile gateway up -d wso2am`, then `node infra/wso2/setup.mjs`), the backend with `-Dspring-boot.run.profiles=dev,gateway`, and the UI with `npm run start:gateway`.

1. Open http://localhost:4200/requests. **Expected:** you are redirected to WSO2's login page on `https://localhost:9443` (accept the self-signed certificate once).
2. Sign in as `ted.l` (password `PORTAL_EMPLOYEE_PASSWORD` from `.env`), and choose *Allow* on the consent page. **Expected:** back in the app, the header shows **Signed in via WSO2 · EMPLOYEE**, and there is no *Review queue*.
3. Submit a request (step 1). Open the browser dev tools → *Network*: calls go to `/gateway/accessportal/1.0.0/...` with `Authorization: Bearer …`. The earlier `/oauth2/token` call has **no** client secret (public client + PKCE).
4. **Sign out** → confirm on WSO2's logout page → sign in as `alice.admin`. **Expected:** **ADMIN**, the review panel is shown; approve. The history's *by* shows WSO2 user ids (see wso2-setup.md: WSO2 4.x does not pass usernames to the backend).
5. **Security checks from the command line** (token commands in wso2-setup.md):
   - gateway without a token → `401`;
   - `ted.l`'s token on `PATCH …/status` → `403` from WSO2 (token lacks `access_request:review`);
   - Spring Boot directly (`curl localhost:8080/api/requests`) → `401 Missing X-JWT-Assertion`, also with `X-Demo-*` headers or a forged JWT.

---

### Automated version of steps 1, 4 and 5

During Phase 2 the same flow was run in headless Microsoft Edge with a Playwright script (kept out of the repo to avoid an extra dependency). It also checked the 390 px-wide mobile layout (no horizontal scroll) and that the browser console showed no errors. That run found a real bug that the jsdom unit tests could not reproduce: `<select [value]>` showed the wrong option after a page load, because the `@for` options are rendered after the value binding is applied. The fix binds `[selected]` on each option.
