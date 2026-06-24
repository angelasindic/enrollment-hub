# ADR-03: Security Architecture — API Gateway, OIDC Login, Prerequisite Tokens

**Status:** Accepted
**Date:** June 2026

## Context

Enrollment requests may be submitted only by an authenticated user who has completed the prerequisite checks the flow
demands — credit-card verification today, eIDAS identity verification later. Those checks are performed by external
services outside this project's scope. The system must confirm the checks happened without coupling to the internal
workings of the services that performed them.

A second boundary runs deeper in the system. Downstream services — Fraud Service, Geo Scoring, Account Service — receive
work from the Decision Engine over the message broker, not from the end user. They act on the Decision Engine's
messages, not on user-originated requests. The two boundaries — user-facing and internal — call for different trust
models, and the architecture keeps them separate rather than propagating one credential through both.

## Decision

A layered security model for the user-facing path, plus an explicit statement of the current internal trust posture and
the decision deferred around it.

### Layer 1 — Edge TLS termination

TLS terminates at the edge; traffic inside the trusted network is plain HTTP. In production an AWS Application Load
Balancer handles termination, path-based routing, health checks, connection draining, and scaling, with AWS Shield for
baseline DDoS protection and AWS WAF available for IP blocking, geo-filtering, and edge rate limiting. Locally the same
role is filled by a Kubernetes Ingress controller (NGINX or Traefik) that terminates TLS and routes to the gateway
inside the cluster. The security model is identical across both — TLS at the edge, plain HTTP within the pod network —
so the deployment target changes the edge component without changing the posture.

### Layer 2 — Spring Cloud Gateway (stateful OIDC login client)

The gateway is a stateful OAuth2 login client, not a stateless token validator. It drives the OIDC `authorization_code`
flow against the authorization server, establishes a server-side session for the logged-in user, and the browser carries
a session cookie rather than a bearer token. On each request to a protected route the gateway relays the user's access
token inward via the `TokenRelay` filter.

This is a deliberate choice for a browser-facing flow. A public web client should not hold or manage raw tokens —
keeping the user's session server-side and exposing only an opaque cookie removes token handling from the browser, which
is the more defensible posture for a first-party enrollment frontend. The cost is that the gateway holds session state
and is therefore stateful:scaling it horizontally requires a shared session store (see Consequences). The gateway
composes no responses and proxies a single route, so it is a stateful authenticating gateway, not a
backend-for-frontend.

The gateway authenticates; it does not authorize. It establishes *who* the user is and relays their token; it does not
inspect prerequisite tokens, which are opaque payload to it. Routing and edge rate limiting also live here.

### Layer 3 — Decision Engine (resource server + prerequisite validation)

The Decision Engine is a Spring Security resource server. It validates the relayed access token — signature against the
authorization server's JWKS (`/oauth2/jwks`), expiry, issuer — and extracts claims for user identification and
role-based access control. JWT validation lives here, at the resource server, not at the gateway.

Beyond authenticating the user, the Decision Engine validates a **prerequisite token** that proves the required external
check was completed. It travels out-of-band from the access token, in a dedicated `X-Prerequisite-Token` header — the
`Authorization: Bearer` slot is reserved for the access token, so the prerequisite assertion rides in its own header,
the same shape DPoP(RFC 9449) uses to carry a second JWT alongside an access token. A single header carries whichever
prerequisite applies; its `type` claim disambiguates which check it attests (credit-card verification, or the eIDAS
identity verification of ADR-05).

Validation runs in order:

1. **Signature** — verified against the prerequisite issuer's JWKS public key.
2. **Expiry** — `exp` is in the future.
3. **Route match** — `type` matches the request's route (`CREDIT_CARD` -> `credit_card_check`,
   `INVOICE` -> `eidas_identity`).
4. **Subject binding** — `sub` equals the authenticated user's `sub`. This is the load-bearing
   authorization check: it binds the proof-of-prerequisite to the caller, so a valid, unexpired,
   correctly-typed token issued for one principal cannot be lifted and replayed by another
   (confused deputy).
5. **Audience** — `aud` names the enrollment API.

Steps 1–2 are validation; steps 3–5 are the authorization decision. If any step fails the request is rejected with `403`
and the attempt is logged — no correlation record is created and no downstream work is dispatched.

### Independent trust roots per prerequisite type

Authentication and each prerequisite type are logically independent issuers, and the architecture keeps them independent
regardless of how deployment co-locates them. Each token carries its own`iss` and is signed with its own key (`kid`):
the authentication token from the authorization server, the credit-card prerequisite from the payment-verification
issuer, the eIDAS prerequisite from a third, separate issuer. The Decision Engine validates each against its own JWKS
endpoint — structurally the same offline pattern in every case (ADR-05), a different issuer and key each time.

The prerequisite token's required claims: `iss` (distinct from the authentication issuer and from any other prerequisite
issuer), `sub` (must equal the authenticated principal's `sub`), `aud` (the enrollment API), `type` (
`credit_card_check` / `eidas_identity`), `iat`, and `exp`. Each is signed with a private key controlled by the issuing
service; the Decision Engine validates against the corresponding public key on that issuer's JWKS endpoint.

Deployment may co-locate an issuer with the authorization server — the credit-card build does exactly this, one
deployable, one JWKS host — without weakening the boundary, because the boundary lives in the token (distinct `iss` and
`kid`), not in the topology. The eIDAS issuer is a genuinely separate endpoint, which makes the same point from the
other direction: trust root and deployment topology are independent axes. Splitting a co-located issuer into a
standalone service later requires no Decision Engine change, only a new issuer URL. Collapsing issuers onto a single
`iss`and key is rejected: it merges authentication and verification attestations into one trust root, which is exactly
the separation the prerequisite-token pattern exists to enforce.

### Prerequisite check services

The Decision Engine integrates with no external verification provider directly. Dedicated backend services absorb
provider-specific details (e.g. Adyen HMAC webhooks, Onfido JWTs) and emit standardized signed JWTs; the Decision Engine
validates only those JWTs. The engine is therefore unaffected if a provider is replaced.

### Internal trust posture (current state and open decision)

The user-facing layers above are authenticated and authorized. The internal path — the Decision Engine dispatching work
to downstream services over RabbitMQ — is not. There is no per-message or per-service authentication: any client able to
connect to the broker can publish and consume any event.

For the local-first deployment this is acceptable. The broker is not exposed beyond the trusted pod network, TLS
terminates at the edge with plain traffic inside (Layer 1), and the user's access token never propagates past the
Decision Engine — internal messages carry no user credential at all. Broker access *is* the trust boundary, and the
network keeps that boundary closed.

This stops being acceptable once the broker is shared, exposed, or multi-tenant, at which point a connected client could
observe or inject enrollment events. Hardening the message-transport boundary is therefore an open decision (see §10.1).
Candidate mechanisms include broker-level authentication with per-vhost authorization, a signed service token carried as
a message header and validated by the consumer, or mTLS on broker connections. The HTTP client-credentials pattern does
not map directly onto message transport, where there is no request to carry a bearer token — so the mechanism is
evaluated in a dedicated ADR before the deployment posture requires it. The required property holds regardless of which
is chosen: a downstream service must be able to establish that a message originated from the Decision Engine, and the
user's credential must never cross the internal boundary.

### Alternative considered — centralized validation at the gateway

Validating prerequisite tokens at the gateway is rejected. Prerequisite checks are service-specific business
preconditions, not cross-cutting authentication concerns; pushing them into the gateway would couple it to the Decision
Engine's domain logic. The gateway stays responsible for authentication and routing; precondition authorization stays
with the service that owns the domain.

## Scope

The initial build targets the credit-card prerequisite route. The eIDAS (`INVOICE`) route follows the same
prerequisite-token contract — same header, same validation order — but is issued under its own trust root, distinct from
both the authentication issuer and the credit-card issuer. That is the pattern working as intended: each prerequisite
type is an independent issuer with its own signing key, so adding a verification type later adds a trust root rather
than changing the model. The architecture supports both prerequisite types; the credit-card path is built first (
ADR-05).

## Consequences

**Gains.** Defense in depth across the user-facing layers. Authentication (gateway) is cleanly separated from user
authorization and prerequisite validation (Decision Engine). The Decision Engine is decoupled from payment/identity
providers. The prerequisite-token pattern closes the gap where an enrollment could be submitted without the required
verification, and subject binding prevents that proof from being replayed across principals. Independent trust roots
keep authentication and each verification attestation from sharing a key, and survive co-location unchanged.

**Loses.** The gateway is stateful, so scaling it horizontally requires a shared/distributed session store. The Decision
Engine must hold each issuer's public-key configuration; the key-distribution mechanism is out of scope and owned by the
external services. Two JWTs per request adds modest complexity to frontend integration. Internal message transport is
currently unauthenticated:security rests on broker network isolation, which must be hardened before any non-isolated
deployment (§10.1, deferred ADR).