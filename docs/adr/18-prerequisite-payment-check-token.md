# ADR-18: Prerequisite Payment-Check Token — Server-to-Server Issuance, Gateway Custody

**Status:** Accepted
**Date:** June 2026
**Related ADRs:** ADR-03, ADR-05

## Context

ADR-03 requires that a CREDIT_CARD enrollment carry proof that the credit-card check succeeded,
validated at the decision-engine before any correlation record is created, and that this proof be a
**distinct trust root** from the authentication token (its own `iss`/`kid`). It was never built —
ADR-03 and the README listed it as outstanding.

Two properties constrain the design:

1. **BFF custody.** The gateway is an authenticating edge that holds the user's tokens server-side;
   the browser holds only a session cookie (the reconciliation in ADR-03's 2026-06-16 amendment).
   Token custody must be *consistent* — if the access token is server-held, the prerequisite token
   must be too, not carried by the browser.
2. **A payment result is a server-verified fact.** A credit-card check is not an identity
   authorization; the authoritative result is produced by a payment provider (e.g. Adyen) and reaches
   the backend out-of-band (HMAC-signed webhook / server API call). A browser-presented result is
   never trusted.

## Decision

Issue the `credit_card_check` attestation server-to-server and keep it in the gateway session;
validate it conditionally at the decision-engine.

### Issuer — co-located, distinct trust root (authorization-server)

A simulated payment-check issuer lives in the authorization-server but presents as a separate trust
root (ADR-03): its own RSA key (distinct `kid`), its own issuer claim, its own JWKS at
`/payment-check/jwks`. `POST /payment-check/credit-card` mints a JWT (`iss` = payment-check issuer,
`sub` = the named user, `aud` = `enrollment-api`, `type` = `credit_card_check`, short `exp`), guarded
by a client-credentials access token with scope `prerequisite:issue`. The signing encoder is built
privately, not exposed as a `JwtEncoder` bean — otherwise Spring Authorization Server would adopt it
to sign its own OIDC tokens, collapsing the two trust roots.

### Gateway — server-to-server fetch, session custody, conditional relay

`POST /payment-check` (authenticated) is the "payment check completed" step. The gateway calls the
issuer **server-to-server** with the `client_credentials` grant, passing the logged-in user's `sub`
(so the attestation is subject-bound), and stores the returned JWT as an HTTP **session attribute**.
At enrollment, `TokenRelay` attaches the access token (`Authorization: Bearer`) and a filter attaches
the stored attestation (`X-Prerequisite-Token`). The browser holds neither token. The gateway
forwards; it validates nothing.

### Decision-engine — conditional validation, two decoders

For CREDIT_CARD, the decision-engine validates `X-Prerequisite-Token` with a decoder dedicated to the
payment-check issuer (signature against the payment-check JWKS, issuer, audience, expiry), plus the
prerequisite contract: `type == credit_card_check` and **subject binding** (`prerequisite.sub ==
bearer.sub`). This decoder is kept private so the resource-server's bearer decoder remains the sole
`JwtDecoder` bean. Any failure is a `403` before a correlation record exists. Other routes skip.

The end-to-end flow is documented in [prerequisite-token-flow.md](../prerequisite-token-flow.md).

## Why server-to-server, not a second OAuth2 client

A tempting alternative modelled the check as a *second OAuth2 login client* (the SPA runs an extra
`authorization_code` flow for a `credit_card_check`-scoped token). Rejected:

| | Server-to-server (chosen) | Second OAuth2 client |
|---|---|---|
| Fidelity to a payment check | High — a verified fact fetched by the backend (Adyen webhook/API) | Low — models a payment check as an OIDC login the user "consents" to |
| Where the result originates | Backend (never the browser) | Browser-driven redirect flow |
| Distinct trust root (`iss`/`kid`) | Preserved — minted by the payment-check issuer | Lost — a standard OIDC token shares the auth key (one key per SAS) |
| Subject binding | Preserved (gateway passes the user's `sub`) | Preserved only with `authorization_code`, not `client_credentials` |

The second-client mechanism (incremental authorization) is legitimate for *acquiring additional
scopes/consent*, but it is the wrong fit for a payment attestation.

## Deferred / out of scope

- **eIDAS / INVOICE** (`eidas_identity`) — same shape, a second issuer; not built.
- **Signing-key stabilization** — both the OIDC and payment-check keys are per-startup (ADR-05);
  tracked separately.
- **HA session store.** The gateway session now holds two tokens; a shared store (Valkey/Redis) is
  required for more than one gateway replica. Documented, not implemented.
- **Real Adyen integration.** `POST /payment-check` is the simulation seam for the webhook/HMAC flow.

## Consequences

**Gains:** Consistent BFF custody (browser tokenless); the prerequisite stays a distinct trust root,
validated independently at the decision-engine; subject binding defends against confused-deputy
replay; the credit-card path demonstrates the whole pattern with standard OAuth2 mechanisms
(client_credentials, resource-server JWT validation).

**Loses:** A server-side session that now carries two tokens (HA needs a shared store). The
attestation is short-lived and not refreshed — a stale one means re-running the payment-check step.
The issuer is co-located in the authorization-server (a deliberate MVP simplification; splitting it
out is a new issuer URL, no code change at the decision-engine).

## Related ADRs

- **ADR-03** — Security Architecture. Defines the prerequisite-token requirement, the distinct-issuer
  rule, subject binding, and (2026-06-16 amendment) the authenticating-gateway model this builds on.
- **ADR-05** — Authorization Server Persistence. The same deployable that persists authorization
  state co-locates the payment-check issuer.
