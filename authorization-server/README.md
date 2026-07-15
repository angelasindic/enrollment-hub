# Authorization-Server

The enrollment-hub **identity provider (IdP)**, built on Spring Authorization Server (Spring Security 7). It authenticates end users and runs the OAuth2/OIDC `authorization_code` and consent flow for the [gateway](../gateway). It publishes its signing keys at `/oauth2/jwks`, the URL the [decision-engine](../decision-engine) validates JWTs against.

## What it exposes

| Endpoint | Purpose |
|---|---|
| `/oauth2/authorize` | start the `authorization_code` flow |
| `/login`, consent | end-user authentication and scope approval |
| `/oauth2/token` | back-channel exchange of code for tokens |
| `/oauth2/jwks` | public signing keys (resource servers point `jwk-set-uri` here) |
| `/.well-known/openid-configuration` | OIDC discovery metadata |
| `POST /payment-check/credit-card` | mint a `credit_card_check` attestation (scope `prerequisite:issue`) — simulated issuer |
| `GET /payment-check/jwks` | verification keys for that attestation (a trust root separate from `/oauth2/jwks`) |

## Registered client

| Client | Grant | Scopes | Redirect |
|---|---|---|---|
| `enrollment-login-client` | `authorization_code`, `refresh_token` | `openid`, `profile`, `enrollment:write` | `http://127.0.0.1:8079/login/oauth2/code/enrollment-gateway` |
| `payment-check-client` | `client_credentials` | `prerequisite:issue` | — (server-to-server, no redirect) |

Two clients with **disjoint scopes** (ADR-03): the login client carries `enrollment:write` for the user flow, the machine client carries `prerequisite:issue` only for the server-to-server payment-check call (below). Keeping them separate stops a `client_credentials` token from ever holding `enrollment:write`. The login registration key remains `enrollment-gateway` (hence the redirect path); only the client-ids differ.

Consent is required, so the user approves scopes on first login.

The redirect URI uses the loopback IP `127.0.0.1` rather than `localhost`. Spring Security rejects `localhost` redirect URIs per the OAuth best-practice guidance (RFC 8252), since `localhost` is resolver-dependent while the loopback IP is not.

---

## Simulated payment-check issuer (credit-card prerequisite)

The credit-card route requires a signed `credit_card_check` attestation before an enrollment is accepted (ADR-03). A **simulated** issuer is co-located here as a trust root fully separate from the OIDC issuer — its own `iss`, `kid`, and JWKS — standing in for an external payment provider (e.g. Adyen). Its classes live in the [`simulation`](src/main/java/dev/sindic/enrollmenthub/authorizationserver/simulation) package so the intent is explicit; replace them with a real integration without touching the OIDC core.

- `POST /payment-check/credit-card` mints the attestation. It requires a `client_credentials` access token with scope `prerequisite:issue` and a `{"subject":"<user>"}` body. The token carries `iss=http://localhost:9000/payment-check`, `aud=enrollment-api`, claim `type=credit_card_check`, RS256, 10-minute TTL.
- `GET /payment-check/jwks` publishes the verification key (distinct from `/oauth2/jwks`); the decision-engine validates against it.

In the normal flow the gateway calls this server-to-server and holds the JWT in the user's session (BFF custody — see the [gateway README](../gateway/README.md)). To mint one directly for local testing:

```
# 1) client_credentials token carrying the issue scope
TOKEN=$(curl -s -u payment-check-client:payment-check-client-secret \
  -d grant_type=client_credentials -d scope=prerequisite:issue \
  http://localhost:9000/oauth2/token | jq -r .access_token)

# 2) mint the credit_card_check attestation
curl -s -X POST http://localhost:9000/payment-check/credit-card \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"subject":"user"}'
# → {"token":"<credit_card_check JWT>"}
```

---

## Persistence

Registered clients, authorizations (codes, access and refresh tokens, flow state), user consent, and the user store are persisted in PostgreSQL via the SAS JDBC services and `JdbcUserDetailsManager`, so authorization state survives restarts and is available to every replica (ADR-05). The service owns the `authorization_server` schema in the `enrollmenthub` database, and Flyway manages it. The `enrollment-login-client` and `payment-check-client` clients are seeded idempotently on startup, and the demo user is seeded by Flyway.

Token validation does not yet share this property. The RSA signing key is regenerated on each startup, so a JWT signed before a restart, or by a different replica, fails validation until the deferred persistent signing key is in place (ADR-05).

---

## Configuration

| Env var                          | Default                          | Purpose                                                             |
|----------------------------------|----------------------------------|---------------------------------------------------------------------|
| `PAYMENT_CHECK_CLIENT_SECRET`    | `payment-check-client-secret `   | payment-check (M2M) client secret (must match the gateway)                    |
| `ENROLLMENT_LOGIN_CLIENT_SECRET` | `enrollment-login-client-secret` | OAuth2 login secret (must match the gateway)                        |
| `DB_HOST`                        | `localhost`                      | PostgreSQL host (`enrollmenthub` DB, `authorization_server` schema) |
| `DB_USER`                        | `postgres`                       | datasource username                                                 |
| `DB_PASSWORD`                    | `postgres`                       | datasource password                                                 |

> **Sandbox only:** the demo user (username `user`, password `password`), the `{noop}` client secret, and the per-startup RSA signing key are not production-grade. A persistent signing key (keystore) and a real user store or identity federation remain deferred (ADR-05).

## Run

Requires PostgreSQL, started with `docker compose up -d postgres`. The issuer is `http://localhost:9000` and the service listens on port `9000`. Actuator endpoints `/actuator/health` and `/actuator/info` are public.
