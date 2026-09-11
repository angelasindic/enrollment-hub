# Manual end-to-end test runbook

Walks the full chain — **browser login → payment-check step → enrollment → asynchronous
decision** — then a few negative checks. The CREDIT_CARD route is the interesting one: it
exercises the dual-token custody (login JWT + `credit_card_check` prerequisite) described in
[prerequisite-token-flow.md](prerequisite-token-flow.md).

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

## Step 0 — Install contract module
Optional: Build and verify whole reactor first
```bash
cd <root enrollment-hub>
./mvnw clean verify
```
Then install contracts (and parent pom)
```bash
cd /Users/angela/portfolio/enrollment-hub
./mvnw -pl contracts -am -DskipTests install     # installs `contracts` so each module resolves it
```

## Step 1 — Start infrastructure

```bash
docker compose up -d --wait
```

> ⏳ **Nominatim caveat.** First boot imports the Netherlands OSM extract (~10 GB,
> `start_period: 10m` and often longer). Geo-Scoring depends on it, but this **does not block**
> the E2E: geo is a `SCORING_SIGNAL` and fails open — if Nominatim isn't ready or the address
> won't geocode, the geo signal degrades; it can never cause a rejection, and the decision still
> completes (worst case via the 1-minute timeout poller). For a fast first run you can start
> Geo-Scoring later, or skip it and let the geo signal time out.

## Step 2 — Start the five services (one terminal each)

```bash
./mvnw -pl authorization-server spring-boot:run   # :9000  — start first
./mvnw -pl decision-engine      spring-boot:run   # :8080
./mvnw -pl fraud-detection      spring-boot:run   # :8082
./mvnw -pl geo-scoring          spring-boot:run   # :8081
./mvnw -pl gateway              spring-boot:run   # :8079  — start last
```

Readiness checks:

```bash
curl -s localhost:9000/actuator/health    # auth server
curl -s localhost:8080/actuator/health    # decision engine
curl -s localhost:8079/actuator/health    # gateway
curl -s localhost:9000/payment-check/jwks # payment-check trust root is serving keys
```

## Step 3 — Log in (browser)

1. Open **`http://127.0.0.1:8079/`**.
2. You're redirected to the IdP login form (`:9000/login`). Credentials: **`user` / `password`**
   (seeded by Flyway, BCrypt).
3. Approve the **consent** screen (scopes `openid`, `profile`, `enrollment:write` — consent is
   required on first login).
4. await fetch('http://127.0.0.1:8079/actuator/health', { credentials: 'include' })
5.You land back on the gateway authenticated. The gateway now holds your
   `OAuth2AuthorizedClient` (access + refresh token) in the HTTP session; the browser holds only
   `JSESSIONID` and a readable `XSRF-TOKEN` cookie.

Keep the tab open and open **DevTools → Console**. The two POSTs below run from there so cookies
and CSRF are handled automatically.

## Step 4 — Payment-check step (server-to-server attestation)

> **CSRF setup this step relies on.** The cookie-echo below works because the gateway pairs Spring
> Security 7's `csrf().spa()` (readable `XSRF-TOKEN` cookie + acceptance of the raw value echoed in
> `X-XSRF-TOKEN`, past BREACH/XOR masking) with a small `CsrfCookieFilter` that writes the lazily
> resolved token on the first authenticated `GET` — `spa()` alone does not issue the cookie on a GET.
> If `xsrf()` throws (no cookie) or the POST returns `403` despite sending the header, you are running a
> gateway built without these — rebuild the gateway.

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

## Step 5 — Submit the enrollment (CREDIT_CARD)

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

## Step 6 — Observe the asynchronous decision

The decision is emitted out-of-band (no account-service consumer exists yet). Three ways to see
it, easiest first.

**a) Decision-engine log** — watch its terminal for:

```
Published enrollmentDecisionEvent decisionId=<uuid> decision=<APPROVED|CONDITIONAL_APPROVED|...>
```

It appears once both signals settle, or within ~1 min (scatter-gather timeout `1m`, poller every
`10s`).

**b) Postgres correlation record:**

```bash
PGPASSWORD=postgres psql -h localhost -U postgres -d enrollmenthub \
  -c "select enrollment_id, decision_result, signal_states from enrollment_hub.enrollments order by created_at desc limit 1;"
```

**c) RabbitMQ event** — to capture the `EnrollmentDecisionEvent` itself (nothing consumes
`enrollment.decisions`): open `http://localhost:15672` (guest/guest) → **Queues → Add a queue**
(e.g. `e2e.spy`) → on the **`enrollment.decisions`** exchange add a binding to `e2e.spy` with routing
key **`enrollment.decision.#`** (it is a *topic* exchange; the published key is `enrollment.decision.completed`) →
re-run Step 5 → **Get messages** on `e2e.spy`. Bind *before* submitting — the exchange won't
retain past messages.

## Negative checks

These prove the prerequisite gate and the data-minimisation posture.

1. **CREDIT_CARD without the payment-check step → `403`, no DB record.**
   Log in fresh (or a new private window so there's no `PREREQUISITE_TOKEN` in session),
   **skip Step 4**, run Step 5. Expect `403`. Confirm no new row appears in
   `enrollment_hub.enrollments` — prerequisite rejection never touches the DB (GDPR data
   minimisation).

2. **INVOICE needs no prerequisite → `202`.**
   Skip Step 4, run Step 5 with `paymentType: 'INVOICE'`. Expect `202` — the INVOICE route
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

- **curl instead of DevTools.** Possible, but you must copy `JSESSIONID` + `XSRF-TOKEN` out of the
  browser after the interactive login — the OIDC `authorization_code` form login can't be scripted
  cleanly with curl. The DevTools-console approach avoids that.
- **Credentials and IDs** (sandbox only): user `user` / `password`; login client
  `enrollment-login-client` / `enrollment-login-client-secret`; payment-check (M2M) client
  `payment-check-client` / `payment-check-client-secret`; scopes `enrollment:write` (login) and
  `prerequisite:issue` (payment-check).
