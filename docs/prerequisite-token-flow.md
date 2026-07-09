# Credit-card prerequisite-token end-to-end flow

What happens from the payment-check step through enrollment for the **CREDIT_CARD** route: how the
gateway obtains the `credit_card_check` attestation server-to-server, holds it in the session
alongside the login token, relays both downstream, and how the decision-engine validates each against
its own trust root (ADR-03, ADR-18).

> **Precondition — the user is already logged in.** Login is the standard OIDC `authorization_code`
> flow: the gateway (an authenticating edge, not a stateless validator) holds the login
> `OAuth2AuthorizedClient` (access + refresh token) in the HTTP session; the browser holds only a
> `JSESSIONID` cookie. This diagram picks up after that.

## Participants

| Component | Port | Role |
|---|---|---|
| Browser (SPA) | — | Drives the flow: login → payment-check → enroll |
| Gateway | 8079 | Spring Cloud Gateway Server WebMVC **+** OAuth2 login client (authenticating edge / token custodian) |
| Authorization Server | 9000 | OIDC issuer **+** co-located payment-check issuer (a **distinct** `iss`/`kid`) |
| Decision Engine | 8080 | Resource server — validates the bearer JWT **and** the prerequisite JWT |

## Sequence

```mermaid
sequenceDiagram
    autonumber
    actor U as User (Browser)
    participant G as Gateway (8079)
    participant AS as Authorization Server (9000)
    participant DE as Decision Engine (8080)

    Note over U,DE: Precondition — logged in. The gateway holds the login OAuth2AuthorizedClient (access token) in the HTTP session, and the browser holds only JSESSIONID.

    rect rgb(232, 244, 255)
    Note over U,AS: 1 — Payment-check step (server-to-server, the browser never sees the attestation)
    U->>G: POST /payment-check (Cookie JSESSIONID, X-XSRF-TOKEN)
    Note over G: Authenticated session. Reads the logged-in user's sub from the OidcUser principal.
    G->>AS: POST /oauth2/token (client_credentials, scope=prerequisite:issue, HTTP Basic enrollment-gateway)
    AS-->>G: 200 access_token (M2M, scope prerequisite:issue)
    G->>AS: POST /payment-check/credit-card (subject=user-sub, Authorization: Bearer M2M)
    Note over AS: Mints a credit_card_check JWT signed by the payment-check key.<br/>Distinct iss (.../payment-check) and distinct kid, sub=user, aud=enrollment-api, short exp.
    AS-->>G: 200 token = credit_card_check JWT
    Note over G: Stores the JWT as an HTTP session attribute (PREREQUISITE_TOKEN).<br/>The browser still holds only JSESSIONID.
    G-->>U: 204 No Content
    end

    rect rgb(232, 255, 240)
    Note over U,DE: 2 — Enrollment, the gateway relays BOTH tokens
    U->>G: POST /enrollment/public/v1/enrollments (Cookie JSESSIONID, X-XSRF-TOKEN, paymentType=CREDIT_CARD)
    Note over G: Matches route Path=/enrollment/**.<br/>TokenRelay adds Authorization: Bearer ACCESS_TOKEN (from the session AuthorizedClientRepository).<br/>PrerequisiteTokenRelayFilter adds X-Prerequisite-Token CREDIT_CARD_CHECK (from the session attribute).
    G->>DE: POST /enrollment/... (Authorization: Bearer ACCESS_TOKEN, X-Prerequisite-Token CREDIT_CARD_CHECK)
    end

    rect rgb(245, 240, 255)
    Note over DE,AS: 3 — Decision-engine validates both tokens against two independent trust roots
    Note over DE: Resource server validates the bearer JWT — signature via the OIDC JWKS, issuer, expiry, scope enrollment:write.
    DE->>AS: GET /oauth2/jwks (first call only, then cached)
    AS-->>DE: JWK Set (OIDC keys)
    Note over DE: CREDIT_CARD route → PrerequisiteTokenValidator validates X-Prerequisite-Token.<br/>Signature via the payment-check JWKS, issuer, audience, expiry, type=credit_card_check, and sub == bearer sub (subject binding).
    DE->>AS: GET /payment-check/jwks (first call only, then cached)
    AS-->>DE: JWK Set (payment-check key)
    Note over DE: Both valid → persist the correlation record and start the scatter-gather.<br/>Any prerequisite failure → 403, no record created.
    DE-->>G: 202 Accepted (enrollmentId)
    G-->>U: 202 Accepted (proxied)
    end
```

## What gets stored, and where

| Location | What | Lifetime |
|---|---|---|
| Browser cookie | `JSESSIONID` | Session |
| Gateway session → `OAuth2AuthorizedClientRepository` | login `OAuth2AuthorizedClient` (access + refresh token) | Session |
| Gateway session attribute | `credit_card_check` JWT (`PREREQUISITE_TOKEN`) | Session (until enrollment / token `exp`) |
| Authorization Server | OIDC signing key **and** payment-check signing key (distinct `kid`) | Key lifetime |
| Decision Engine | cached OIDC JWKS **and** payment-check JWKS | Cached in memory |

The browser never holds either token — both live server-side in the gateway session (BFF custody).
The two tokens are signed by **different keys under different issuers**, and the decision-engine
validates them with **two separate decoders**.

## Subsequent calls

The payment-check step (1) runs once per enrollment; its attestation is short-lived and one-shot.
The login token is reused from the session and refreshed via its refresh token as needed. A later
enrollment on a route with no prerequisite (e.g. INVOICE today) skips steps 1 and the
`X-Prerequisite-Token` header entirely.

## Footnotes

- **Distinct trust roots.** The access token and the `credit_card_check` token are *not*
  interchangeable: different issuer, different signing key. This is deliberate (ADR-03) — it keeps
  authentication and payment attestation as separate trust authorities, even though the payment-check
  issuer is co-located in the authorization-server for the demo.
- **Simulation seam.** In production the user submits card details to the provider (e.g. Adyen) and
  the backend learns the verified result via an HMAC-signed webhook. Here `POST /payment-check` stands
  in for "the check completed → fetch the attestation"; nothing trusts a browser-presented result.
- **Subject binding.** The `credit_card_check` `sub` must equal the authenticated caller's `sub`, so a
  valid attestation issued for one user cannot be lifted and replayed by another (confused deputy).
- **eIDAS / INVOICE** uses the same shape with a second issuer (`eidas_identity`) and is deferred.
