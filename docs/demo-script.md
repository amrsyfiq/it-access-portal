# End-to-end demo script

A manual walkthrough that proves the workflow end to end. Each step says what to do and what you should see.
Steps marked *(Phase 3)* / *(Phase 4)* need RabbitMQ / WSO2, which are not built yet.

## 0. Start everything

```bash
docker compose up -d oracle            # wait for "(healthy)" in: docker compose ps
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

## 3. *(Phase 3)* Event published, consumed and logged

Not built yet: RabbitMQ management UI, consumer log line, `NOTIFICATION_LOG` row.

## 4. Administrator approves

1. Switch the demo user to **Alice Admin · ADMIN**. The sidebar gains **Review queue**, and the open request now shows a **Review this request** panel.
2. Enter a comment and click **Approve**.
3. **Expected:** "Request approved.", status **APPROVED**, a new history entry "PENDING → APPROVED by alice.admin" with the comment, and the review panel disappears.
4. Reload the page (F5). The status is still APPROVED, because it is read back from the database.
5. Try to review it again via the API (`docs/api-examples.http`, "Review again"). **Expected:** `409 Conflict`.

## 5. List, filters, review queue

1. **All requests** lists every request with ID, requester, system, access level, status and creation date.
2. Change the **Status** filter; the URL changes to `?status=...` and the list reloads. Reloading the page keeps the filter.
3. **Review queue** (`?status=PENDING`) shows pending requests, or a "No requests found." empty state.
4. Stop the backend and reload the list. **Expected:** "Cannot reach the server. Check that the backend is running." with a **Try again** button.

## 6. *(Phase 4)* Through the WSO2 gateway

Not built yet.

---

### Automated version of steps 1, 4 and 5

During Phase 2 the same flow was run in headless Microsoft Edge with a Playwright script (kept out of the repo to avoid an extra dependency). It also checked the 390 px-wide mobile layout (no horizontal scroll) and that the browser console showed no errors. That run found a real bug that the jsdom unit tests could not reproduce: `<select [value]>` showed the wrong option after a page load, because the `@for` options are rendered after the value binding is applied. The fix binds `[selected]` on each option.
