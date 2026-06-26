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

## Registered client

| Client | Grant | Scopes | Redirect |
|---|---|---|---|
| `enrollment-gateway` | `authorization_code`, `refresh_token` | `openid`, `profile`, `enrollment:write` | `http://127.0.0.1:8079/login/oauth2/code/enrollment-gateway` |

Consent is required, so the user approves scopes on first login.

The redirect URI uses the loopback IP `127.0.0.1` rather than `localhost`. Spring Security rejects `localhost` redirect URIs per the OAuth best-practice guidance (RFC 8252), since `localhost` is resolver-dependent while the loopback IP is not.

---

## Persistence

Registered clients, authorizations (codes, access and refresh tokens, flow state), user consent, and the user store are persisted in PostgreSQL via the SAS JDBC services and `JdbcUserDetailsManager`, so authorization state survives restarts and is available to every replica (ADR-05). The service owns the `authorization_server` schema in the `enrollmenthub` database, and Flyway manages it. The `enrollment-gateway` client is seeded idempotently on startup, and the demo user is seeded by Flyway.

Token validation does not yet share this property. The RSA signing key is regenerated on each startup, so a JWT signed before a restart, or by a different replica, fails validation until the deferred persistent signing key is in place (ADR-05).

---

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `GATEWAY_CLIENT_SECRET` | `enrollment-secret` | secret for the `enrollment-gateway` client (must match the gateway) |
| `DB_HOST` | `localhost` | PostgreSQL host (`enrollmenthub` DB, `authorization_server` schema) |
| `DB_USER` | `postgres` | datasource username |
| `DB_PASSWORD` | `postgres` | datasource password |

> **Sandbox only:** the demo user (username `user`, password `password`), the `{noop}` client secret, and the per-startup RSA signing key are not production-grade. A persistent signing key (keystore) and a real user store or identity federation remain deferred (ADR-05).

## Run

Requires PostgreSQL, started with `docker compose up -d postgres`. The issuer is `http://localhost:9000` and the service listens on port `9000`. Actuator endpoints `/actuator/health` and `/actuator/info` are public.

<!-- TODO: add the command that starts the service itself (build-tool entry point). -->