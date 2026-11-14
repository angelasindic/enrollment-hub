# Running locally and manual end-to-end test

Sets up the system on a workstation — services on the host against Docker Compose infrastructure — then
walks the full chain — **browser login → payment-check step → enrollment → asynchronous decision** — and a
few negative checks. The CREDIT_CARD route is the interesting one: it exercises the dual-token custody (login
JWT + `credit_card_check` prerequisite) described in [prerequisite-token-flow.md](prerequisite-token-flow.md).
Steps 5–7 are also scripted, without a browser, in [`scripts/e2e-happy-flow.sh`](../scripts/e2e-happy-flow.sh).

## Topology under test

| Component | Port | Notes |
|---|---|---|
| Authorization Server (IdP + payment-check issuer) | **9000** | login form, `/oauth2/*`, `/payment-check/*` |
| Decision Engine (resource server) | **8080** | `POST /enrollment/public/v1/enrollments` |
| Geo-Scoring (AMQP consumer) | 8081 | needs Redis + Nominatim + libpostal |
| Fraud-Detection (AMQP stub) | 8082 | always replies OK |
| Gateway (edge, token custodian) | **8079** | **access via `http://127.0.0.1:8079`** |
| RabbitMQ management | 15672 | guest / guest |
| Postgres | 5432 | postgres / postgres, db `enrollmenthub` |

> ⚠️ **Use `127.0.0.1`, not `localhost`, for the gateway.** The registered redirect URI is
> `http://127.0.0.1:8079/login/oauth2/code/enrollment-gateway`. Logging in via `localhost:8079`
> fails the redirect-URI match.

## Step 1 — Create `.env`

The secrets listed in `.env.example` have no default; each value is 32 random bytes, base64-encoded. Run from
the repository root:

```bash
{
  printf 'GEOCODING_CACHE_HMAC_SECRET=%s\n'    "$(openssl rand -base64 32)"
  printf 'ENROLLMENT_LOGIN_CLIENT_SECRET=%s\n' "$(openssl rand -base64 32)"
  printf 'PAYMENT_CHECK_CLIENT_SECRET=%s\n'    "$(openssl rand -base64 32)"
} > .env
```

| Variable | Read by | Purpose |
|---|---|---|
| `GEOCODING_CACHE_HMAC_SECRET` | geo-scoring | HMAC-SHA256 pepper for the geocoding cache keys |
| `ENROLLMENT_LOGIN_CLIENT_SECRET` | gateway, authorization-server | Secret of the OIDC login client `enrollment-login-client` |
| `PAYMENT_CHECK_CLIENT_SECRET` | gateway, authorization-server | Secret of the `client_credentials` client `payment-check-client` |

Geo-scoring, the authorization-server and the gateway do not start without their secrets. If the gateway's two
secrets are set but do not match the authorization-server's, login and the payment check fail with `invalid_client`;
the gateway logs each failed login as a `Login failed` warning.

Changing a value after first use:

- **Pepper:** the same address now produces a different cache key, so no existing entry is found again. Each address
  goes to Nominatim once more and is cached under its new key; the old entries expire with their TTL (see
  *Pepper rotation* in [`geo-scoring/design.md`](../geo-scoring/design.md)).
- **Client secrets:** the authorization-server writes them to Postgres when it first registers the clients and does not
  update them afterward. Delete the stored clients, then restart the authorization-server:

  ```bash
  docker compose exec postgres psql -U postgres -d enrollmenthub -c \
    "DELETE FROM authorization_server.oauth2_registered_client WHERE client_id IN ('enrollment-login-client', 'payment-check-client');"
  ```

## Step 2 — Install the contracts library

Each service module resolves `contracts` from the local Maven repository, and `-pl <module> spring-boot:run` does
not build it. Install it together with the parent POM:

```bash
./mvnw -pl contracts -am -DskipTests install
```

Optionally build and verify the whole reactor first with `./mvnw clean verify`.

## Step 3 — Start infrastructure

PostgreSQL, RabbitMQ, Redis, Nominatim, libpostal:

```bash
docker compose up -d --wait
```

> ⏳ **Nominatim caveat.** First boot imports the Netherlands OSM extract (~10 GB,
> `start_period: 10m` and often longer). Geo-Scoring depends on it, but this **does not block**
> the E2E: geo is a `SCORING_SIGNAL` and fails open — if Nominatim isn't ready or the address
> won't geocode, the geo signal degrades; it can never cause a rejection, and the decision still
> completes (worst case via the 1-minute timeout poller). For a fast first run you can start
> Geo-Scoring later, or skip it and let the geo signal time out.

**Optional — observability stack** (OTel Collector, Tempo, Loki, Prometheus, Grafana at `localhost:3000`):

```bash
docker compose -f otel-local/docker-compose.yml up -d
```

## Step 4 — Start the six services (one terminal each)

Maven does not read `.env`. Export it in every shell that runs a service, or add it to the IDE run
configuration's environment:

```bash
set -a; source .env; set +a
```

```bash
./mvnw -pl authorization-server spring-boot:run   # :9000  — start first
./mvnw -pl account-service      spring-boot:run   # :8083
./mvnw -pl geo-scoring          spring-boot:run   # :8081
./mvnw -pl fraud-detection      spring-boot:run   # :8082
./mvnw -pl decision-engine      spring-boot:run   # :8080
./mvnw -pl gateway              spring-boot:run   # :8079  — start last
```

Only the account service has to precede the decision-engine: it declares the queue that
`enrollment.decisions` routes to, and until it has started once against the broker every decision
publish is returned unroutable — the decision-engine sweep logs `NO_ROUTE` on each run and the
decisions stay on the outbox until the queue exists. The signal workers can start in any order:
the decision-engine declares their request queues, and each worker retries its listener until the
queue appears.

Readiness checks:

```bash
curl -s localhost:9000/actuator/health    # auth server
curl -s localhost:8080/actuator/health    # decision engine
curl -s localhost:8083/actuator/health    # account service
curl -s localhost:8079/actuator/health    # gateway
curl -s localhost:9000/payment-check/jwks # payment-check trust root is serving keys
```

## Steps 5–7 scripted

`scripts/e2e-happy-flow.sh` runs the login, the payment check and the CREDIT_CARD enrollment with curl. A cookie
jar holds the session cookies of both hosts, as a browser would; the script submits the login form with its
`_csrf` field, approves the consent screen when it appears, and echoes the `XSRF-TOKEN` cookie in `X-XSRF-TOKEN`.

```bash
./scripts/e2e-happy-flow.sh
```

```
logged in
payment-check: 204
{"enrollmentId":"<uuid>"}
enrollment: 202
```

Continue with [Step 8](#step-8--observe-the-asynchronous-decision). Steps 5–7 below walk the same flow by hand.

## Step 5 — Log in (browser)

1. Open **`http://127.0.0.1:8079/`**.
2. You're redirected to the IdP login form (`:9000/login`). Credentials: **`user` / `password`**
   (seeded by Flyway, BCrypt).
3. Approve the **consent** screen (scopes `openid`, `profile`, `enrollment:write`). The approval is
   stored in Postgres, so the screen appears only on the first login.
4. You land back on the gateway authenticated, on the page you started from. The gateway serves no page
   at `/`, so this is a Whitelabel `404`. The gateway now holds your `OAuth2AuthorizedClient` (access +
   refresh token) in the HTTP session; the browser holds only `JSESSIONID`.

Keep the tab open and open **DevTools → Console**. The requests below run from there so cookies
and CSRF are handled automatically. The gateway set the readable `XSRF-TOKEN` cookie in its login
response, so the token is already available to the page.

## Step 6 — Payment-check step (server-to-server attestation)

> **CSRF setup this step relies on.** The cookie-echo below works because the gateway uses Spring
> Security 7's `csrf().spa()`: a readable `XSRF-TOKEN` cookie and acceptance of the raw value echoed in
> `X-XSRF-TOKEN`, past BREACH/XOR masking. The token is loaded on every request, so the cookie is
> written on a plain `GET` and renewed when the login completes.

```js
// reads the XSRF-TOKEN cookie and sends it as X-XSRF-TOKEN (CookieCsrfTokenRepository.withHttpOnlyFalse)
const xsrf = () => document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN=')).split('=')[1];

await fetch('http://127.0.0.1:8079/payment-check', {
  method: 'POST',
  credentials: 'include',
  headers: { 'X-XSRF-TOKEN': xsrf() }
}).then(r => r.status);   // expect 204
```

**Expected: `204`.** The gateway did a `client_credentials` call to the IdP
(`scope=prerequisite:issue`), then `POST /payment-check/credit-card` for your `sub`, and stored
the resulting `credit_card_check` JWT as a session attribute. The browser never sees the token.

## Step 7 — Submit the enrollment (CREDIT_CARD)

```js
await fetch('http://127.0.0.1:8079/enrollment/public/v1/enrollments', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': xsrf() },
  body: JSON.stringify({
    paymentType: 'CREDIT_CARD',
    person: { firstName: 'Ada', lastName: 'Lovelace',
              emailAddress: 'ada@example.com', phoneNumber: '+31201234567' },
    shippingAddress: { streetLines: ['Dam 1'], postalCode: '1012 JS',
                       city: 'Amsterdam', subregion: 'Noord-Holland', countryCode: 'NL' },
    billingAddress:  { streetLines: ['Dam 1'], postalCode: '1012 JS',
                       city: 'Amsterdam', subregion: 'Noord-Holland', countryCode: 'NL' }
  })
}).then(async r => ({ status: r.status, body: await r.json() }));
```

**Expected: `202` with `{ enrollmentId: "<uuid>" }`.** The gateway relayed the login JWT
(`Authorization: Bearer …`) **and** the `X-Prerequisite-Token`; the decision-engine validated both
against their separate trust roots, created the correlation record, and started scatter-gather.
Note the `enrollmentId`. A NL address is used on purpose so the loaded Nominatim dataset can
geocode it.

## Step 8 — Observe the asynchronous decision

The decision is emitted out-of-band to the account-service stub. Three ways to see it, easiest
first.

**a) Service logs** — the decision-engine logs the publish, the account service the receipt:

```
Published enrollmentDecisionEvent decisionId=<uuid> decision=<APPROVED|CONDITIONAL_APPROVED|...>
Received enrollmentDecisionEvent decisionId=<uuid> decision=<...> signals=[GEO_SCORE, FRAUD_CHECK] ...
```

It appears once both signals settle, or within ~1 min (scatter-gather timeout `1m`, poller every
`10s`).

**b) Postgres correlation record:**

```bash
PGPASSWORD=postgres psql -h localhost -U postgres -d enrollmenthub \
  -c "select enrollment_id, decision_result, signals from enrollment_hub.enrollments order by created_at desc limit 1;"
```

**c) RabbitMQ event** — to inspect the `EnrollmentDecisionEvent` body, stop the account service
and re-run Step 7. The event waits on the durable `account-service.decisions.queue`: open
`http://localhost:15672` (guest/guest) → **Queues → `account-service.decisions.queue` → Get
messages** with *Nack message requeue true*. Restarting the account service consumes it.

## Negative checks

These prove the prerequisite gate and the data-minimisation posture.

1. **CREDIT_CARD without the payment-check step → `403`, no DB record.**
   Log in fresh (or a new private window so there's no `PREREQUISITE_TOKEN` in session),
   **skip Step 6**, run Step 7. Expect `403`. Confirm no new row appears in
   `enrollment_hub.enrollments` — prerequisite rejection never touches the DB (GDPR data
   minimisation).

2. **INVOICE needs no prerequisite → `202`.**
   Skip Step 6, run Step 7 with `paymentType: 'INVOICE'`. Expect `202` — the INVOICE route
   carries no `X-Prerequisite-Token` requirement today (eIDAS deferred).

3. **Unauthenticated → login redirect.** Hit any gateway path in a fresh private window; you're
   bounced to the IdP login.

## Teardown

```bash
# Ctrl-C each mvn process, then:
docker compose down          # keep volumes (Nominatim import survives)
# docker compose down -v     # nuke volumes — forces a full Nominatim re-import next time
```

## Notes

- **curl after a browser login.** To send single requests with curl from a browser session, copy
  `JSESSIONID` (HttpOnly, so from DevTools → Application → Cookies) and `XSRF-TOKEN`, then send both as
  cookies and the token again as `X-XSRF-TOKEN`. Both values change at login.
- **Credentials and IDs** (sandbox only): user `user` / `password`; login client
  `enrollment-login-client` and payment-check (M2M) client `payment-check-client`, with their secrets in
  `.env` (`ENROLLMENT_LOGIN_CLIENT_SECRET`, `PAYMENT_CHECK_CLIENT_SECRET`); scopes `enrollment:write` (login) and
  `prerequisite:issue` (payment-check).
