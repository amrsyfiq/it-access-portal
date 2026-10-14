# WSO2 API Manager: gateway and OAuth2 setup

How the API is published through **WSO2 API Manager 4.7.0** and secured with OAuth2, how to get a token, and how to run the app in gateway mode. Everything here is scripted; you should not need to click through the WSO2 consoles.

## What it does

```text
Browser (Angular, :4200)                         curl / other clients
   │ 1. login: redirect to WSO2 (authorization code + PKCE)      │ password / client-credentials grant
   │ 2. API calls with  Authorization: Bearer <access token>    │
   ▼                                                              ▼
WSO2 API Manager gateway  https://localhost:8243/accessportal/1.0.0/...
   • validates the access token (signature, expiry, subscription)
   • checks the resource's scope (403 if the token lacks it)
   • strips the Authorization header, adds  X-JWT-Assertion  (JWT signed by WSO2)
   ▼
Spring Boot  http://localhost:8080/api/...   (profile "gateway")
   • GatewayJwtAuthenticationFilter: rejects /api/** without a valid X-JWT-Assertion (401)
   • user = JWT "sub" (WSO2 user id), admin = scope access_request:review
```

| Gateway resource | Scope | Roles allowed to get the scope |
|---|---|---|
| `GET /requests`, `GET /requests/{id}`, `GET /requests/{id}/history`, `GET /requests/{id}/notifications` | `access_request:read` | `portal_employee`, `portal_admin` |
| `POST /requests` | `access_request:submit` | `portal_employee`, `portal_admin` |
| `PATCH /requests/{id}/status` | `access_request:review` | `portal_admin` |
| `GET /events/stream` (live updates, Server-Sent Events) | `access_request:read` | `portal_employee`, `portal_admin` |
| `GET /health` | none (public) | — |

Demo users created by the setup script: **`ted.l`** (`portal_employee`) and **`alice.admin`** (`portal_employee` + `portal_admin`). Passwords come from `.env`.

## Setup (once per fresh WSO2 container)

```bash
# 1. .env must contain WSO2_ADMIN_PASSWORD, WSO2_ENCRYPTION_KEY (32 hex chars),
#    PORTAL_EMPLOYEE_PASSWORD, PORTAL_ADMIN_PASSWORD   (see .env.example)

# 2. Start WSO2 (~2 GB RAM extra, ~90 s until healthy)
docker compose --profile gateway up -d wso2am
docker compose --profile gateway ps          # wait for portal-wso2am "(healthy)"

# 3. Configure it: roles, users, API, scopes, publish, application, keys (~10 s)
node infra/wso2/setup.mjs
```

`setup.mjs` is safe to re-run: it adds resources that are missing from an existing API (e.g. `/events/stream`, added after Phase 4) and deploys a new revision. It writes these git-ignored files:

| File | Contents | Used by |
|---|---|---|
| `infra/wso2/generated/app.env` | client id + secret, token URL, gateway URL | curl examples below |
| `infra/wso2/generated/wso2-signing-cert.pem` | WSO2's public signing certificate | backend, to verify `X-JWT-Assertion` |
| `frontend/public/gateway-config.json` | client id (not a secret: public client) | Angular gateway mode |

WSO2 reveals an application's client **secret only once**, when its keys are generated; later API calls return it masked (`5f4****`). So `setup.mjs` keeps the secret saved by the first run, and on re-runs never overwrites it. If `app.env` was lost, the script warns; delete the application *AccessPortalWeb* in the Developer Portal and re-run to get new keys. The browser login (public client + PKCE) doesn't need the secret.

WSO2's own data (users, API, application) lives inside the container. `docker compose stop`/`start` keeps it. `docker compose rm`/`down` deletes it: re-run `setup.mjs`, which generates **new** client ids.

## Run the app through the gateway

```bash
docker compose up -d oracle kafka
docker compose --profile gateway up -d wso2am && node infra/wso2/setup.mjs   # if not done yet

cd backend  && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,gateway   # terminal 1
cd frontend && npm run start:gateway                                            # terminal 2
```

Open http://localhost:4200. You are redirected to WSO2's login page:

- The first time, the browser warns about WSO2's **self-signed certificate** on `https://localhost:9443`. Choose *Advanced → Continue* (local development only).
- Sign in as `ted.l` or `alice.admin`. On a user's first login, WSO2 asks for consent; choose *Allow*.
- **Sign out** (top right) also ends the WSO2 session (WSO2 asks you to confirm), so you can sign in as the other user.

The dev server proxies `/gateway/*` → `https://localhost:8243` and `/oauth2/token` → `https://localhost:9443` ([proxy.gateway.conf.json](../frontend/proxy.gateway.conf.json)), so background requests are same-origin. Only the login and logout pages are opened on WSO2 directly.

The direct mode (`npm start` + default backend profile, demo-user switcher) still works without WSO2.

## Get a token and call the API (curl)

```bash
set -a; source infra/wso2/generated/app.env; source <(grep PORTAL_ .env); set +a

# Password grant (fine for local testing; the UI uses authorization code + PKCE instead)
TOKEN=$(curl -sk -u "$WSO2_CLIENT_ID:$WSO2_CLIENT_SECRET" \
  -d "grant_type=password&username=alice.admin&password=$PORTAL_ADMIN_PASSWORD" \
  -d "scope=access_request:read access_request:submit access_request:review" \
  "$WSO2_TOKEN_URL" | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -sk -H "Authorization: Bearer $TOKEN" "$WSO2_GATEWAY_URL/requests?status=PENDING"
```

The token response's `scope` shows what was granted. `ted.l` gets `access_request:read access_request:submit` even if he asks for `review`.

## Verified behaviour

| Call | Result |
|---|---|
| Gateway, no token / invalid token | **401** (WSO2) |
| Gateway, `/health` without token | 200 (public resource) |
| Gateway, `ted.l` GET / POST | 200 / 201 (`Location: requests/{id}`, relative) |
| Gateway, `ted.l` PATCH status | **403** (WSO2: token lacks `access_request:review`) |
| Gateway, `alice.admin` PATCH status | 200; history `changedBy` = Alice's WSO2 user id |
| Backend directly, no `X-JWT-Assertion` | **401** |
| Backend directly, `X-Demo-*` headers | **401** (ignored in gateway mode) |
| Backend directly, forged JWT (`alg: none`, or RS256 signed with another key) | **401** |
| Browser: login via WSO2 page, submit as ted, sign out, approve as alice | works; every API call carries `Bearer`, the token request carries no secret |

## Design notes and limitations

- **Why a backend JWT?** The backend never sees the OAuth token. It trusts WSO2's *signed* statement about the caller, verified with WSO2's public certificate (RS256 only, issuer and expiry checked). Calling the backend around the gateway gets you nothing.
- **Actor = WSO2 user id, not username.** WSO2 4.x identifies users by a UUID. With JWT access tokens, the gateway builds the backend JWT only from the token's own claims, and those contain no username ([WSO2 docs](https://github.com/wso2/docs-apim/blob/master/en/docs/api-gateway/passing-enduser-attributes-to-the-backend-via-api-gateway.md); `enable_user_claims` applies to opaque tokens, which 4.7 no longer issues for new applications). So `REQUEST_HISTORY.CHANGED_BY` stores the user id. Options considered and rejected: `include_username_in_access_token` (breaks JWT token issuing in 4.7: HTTP 500); changing the service provider's subject claim over the SOAP admin API (crashed WSO2 with a `StackOverflowError`); a custom gateway JWT generator (a Java extension; possible, but not worth it here).
- **Roles in the UI** come from the granted scopes (`access_request:review` = admin). The backend applies the same rule to the verified JWT, and the gateway enforces the scope per resource, so hiding a button is never the only protection.
- **Public client + PKCE**: the browser has no client secret; PKCE (`pkceMandatory`, S256 only) stops an intercepted authorization code from being redeemed. The client *secret* still exists for the curl/password-grant examples.
- **Token storage**: in `sessionStorage` (survives a reload, cleared when the tab closes). Readable by script on the page, so an XSS bug could steal it. Production SPAs often use a backend-for-frontend instead. There is no refresh-token handling: after expiry (1 h), the next API call gets a 401 and the app sends you to sign in again.
- **Local-only settings**: self-signed certificates, `admin` super-user, H2 database inside the container, single gateway node, WSO2's warning about default internal credentials (WSO2-2023-2617, relevant for production). The setup script disables TLS verification for localhost.
- **CORS**: `[apim.cors]` allows only `http://localhost:4200` on the gateway (verified: other origins get no `Access-Control-Allow-Origin`). CORS on WSO2's own `:9443` endpoints could not be enabled through `deployment.toml` in 4.7, which is why the dev server proxies `/oauth2/token`.
- **Not verified**: refresh tokens, token revocation, and multiple gateway nodes.
