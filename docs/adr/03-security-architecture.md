# ADR-03: Security Architecture — API Gateway, OIDC Login, Prerequisite Tokens

**Status:** Accepted

**Date:** June 2026

## Context

Enrollment requests may be submitted only by an authenticated user who has completed prerequisite checks for credit-card verification and eIDAS identity verification flow. Those checks are performed by external services outside this project's scope; the system must confirm they happened without coupling to how they work.

A second boundary is internal: downstream services — Fraud Service, Geo Scoring, Account Service — receive work from the Decision Engine over the message broker, not from the end user. The user-facing and internal boundaries call for different trust models, and the architecture keeps them separate rather than propagating one credential through both.

## Decision

The decision comprises a layered security model for the user-facing path, plus an explicit statement of the current internal trust posture and the decision deferred around it as follows:

### Layer 1 — Edge TLS termination

TLS terminates at the edge; traffic inside the trusted network is plain HTTP. In production an AWS load balancer fills this role, with edge DDoS protection and a WAF available; locally a Kubernetes Ingress controller terminates TLS and routes to the gateway inside the cluster. The posture is identical across both — TLS at the edge, plain HTTP within the pod network — so the deployment target changes the edge component without changing the security model.

### Layer 2 — Spring Cloud Gateway (stateful OIDC login client)

The gateway is a stateful OAuth2 login client, not a stateless token validator. It drives the OIDC `authorization_code` flow against the authorization server, establishes a server-side session for the logged-in user, and the browser carries a session cookie rather than a bearer token. On each request to a protected route it relays the user's access token inward via the `TokenRelay` filter.

This is a deliberate choice for a browser-facing flow: a public web client should not hold or manage raw tokens, so keeping the session server-side and exposing only an opaque cookie is the more defensible posture for a first-party enrollment frontend. The cost is statefulness, so scaling it horizontally requires a shared session store (see Consequences). It proxies a single route and composes no responses, so it is a stateful authenticating gateway, not a backend-for-frontend.

The gateway authenticates but does not authorize: it establishes who the user is and relays their token, and does not inspect prerequisite tokens, which are opaque to it. Routing and edge rate limiting sit here too.

### Layer 3 — Decision Engine (resource server + prerequisite validation)

The Decision Engine is a Spring Security resource server. It validates the relayed access token (signature against the authorization server's JWKS, expiry, issuer) and extracts claims for user identification and role-based access control. Validating that token is the resource server's responsibility, not the gateway's.

It then validates a **prerequisite token** that proves the required external check was completed. The token travels out-of-band from the access token, in a dedicated `X-Prerequisite-Token` header, so the `Authorization: Bearer` slot stays reserved for the access token.

Beyond the standard JWT checks, two validations make up the authorization decision: route match (the token's `type` matches the requested route) and subject binding (`sub` equals the authenticated user's `sub`). Subject binding prevents a valid token issued for one principal being used by another (confused-deputy / token-replay problem). Any failure rejects the request with `403` and logs the attempt — no correlation record is created and no downstream work is dispatched.

### Independent trust roots per prerequisite type

Authentication and each prerequisite type are logically independent issuers, and the architecture keeps them independent regardless of how deployment co-locates them. Each token carries its own iss and is signed with its own key (kid) — the authentication token from the authorization server, the credit-card prerequisite from the payment-verification issuer, the eIDAS prerequisite from a third, separate issuer — and the Decision Engine validates each against its own JWKS endpoint: the same offline pattern, a different issuer and key each time.

Collapsing issuers onto a single iss and key is rejected: it would merge authentication and verification attestations into one trust root — the exact separation the prerequisite-token pattern exists to enforce. Keeping each issuer on its own iss and kid is also what makes co-location safe: the boundary is the token, not the host, so an issuer can share a deployable with the authorization server (as the credit-card build does) without weakening it.

### Prerequisite check services

The Decision Engine integrates with no external verification provider directly. Dedicated backend services absorb provider-specific details and emit standardized signed JWTs; the engine validates only those JWTs and is therefore unaffected if a provider is replaced.

### Separate registered clients for login and machine issuance

The gateway holds two OAuth2 identities. As a login client it drives the `authorization_code` flow and relays the user's access token (Layer 2). It is also a `client_credentials` client: to obtain the credit-card attestation it calls the payment-check issuer server-to-server, authenticating as itself with no user token involved. These are two separate registered clients with **disjoint scopes** — the login client carries `openid`, `profile`, `enrollment:write`; the machine client carries `prerequisite:issue` and nothing else.

The split is a scope-isolation requirement, not an organizational convenience. In Spring Authorization Server a scope is a property of the registered client, not of the grant type, so a client authorized for both `authorization_code` and `client_credentials` may request any of its scopes under either grant. Giving one client the union of login and issuance scopes therefore lets its `client_credentials` grant mint an `enrollment:write` token with no user login — collapsing the `authorization_code` gate Layer 2 exists to enforce. Disjoint clients make that escalation impossible by construction: the identity that can issue prerequisites cannot request `enrollment:write`, and the identity that can request `enrollment:write` can obtain it only by carrying a real user through the login flow.

**Rejected alternative — one client, two grants, superset scopes.** A single registered client authorized for `authorization_code` + `refresh_token` + `client_credentials`, carrying the union of scopes (`openid`, `profile`, `enrollment:write`, `prerequisite:issue`) and separated only by which grant each caller uses. Rejected because grant type is not a scope boundary. Any holder of that client's secret can present it under `client_credentials` and request `enrollment:write` directly, skipping user authentication entirely. The subject-binding guard at the Decision Engine (Layer 3) does not save it: a `client_credentials` token's `sub` is the client id, and because the payment-check issuer binds its attestation to a caller-supplied subject, the same actor can request a `credit_card_check` for `sub = <client id>` that subject binding then accepts — so the bypass reaches the guarded CREDIT_CARD route, not only the unguarded INVOICE route. A single leaked secret would be enough to forge a fully-authorized enrollment. Splitting the identities removes the overlap the attack depends on.

### Internal trust posture (current state and open decision)

The user-facing layers are authenticated and authorized; the internal path — the Decision Engine dispatching work to downstream services over RabbitMQ — is not. There is no per-message or per-service authentication: any client able to connect to the broker can publish and consume any event.

For the local-first deployment this is acceptable. The broker is not exposed beyond the trusted pod network, and the user's access token never propagates past the Decision Engine — internal messages carry no user credential at all. Broker access is the trust boundary, and the network keeps that boundary closed.

This stops being acceptable once the broker is shared, exposed, or multi-tenant, at which point a connected client could observe or inject enrollment events. Hardening the internal message-transport boundary is therefore an open decision, evaluated in a dedicated ADR before the deployment posture requires it. The required property holds regardless of which mechanism is chosen: a downstream service must be able to establish that a message originated from the Decision Engine, and the user's credential must never cross the internal boundary.

### Alternative considered — centralized validation at the gateway

Validating prerequisite tokens at the gateway is rejected. Prerequisite checks are service-specific business preconditions, not cross-cutting authentication concerns; pushing them into the gateway would couple it to the Decision Engine's domain logic. The gateway stays responsible for authentication and routing; precondition authorization stays with the service that owns the domain.

## Scope

The initial build targets the credit-card prerequisite route. The eIDAS (`INVOICE`) route (ADR-19) follows the same prerequisite-token contract — same header, same validation order — but is issued under its own trust root, distinct from both the authentication issuer and the credit-card issuer. Adding a verification type later adds a trust root rather than changing the model. The architecture supports both prerequisite types; the credit-card path is built first.

## Consequences

**Gains.** Defense in depth across the user-facing layers. Authentication (gateway) is cleanly separated from user authorization and prerequisite validation (Decision Engine), and the Decision Engine is decoupled from payment/identity providers. The prerequisite-token pattern closes the gap where an enrollment could be submitted without the required verification, and subject binding prevents that proof from being replayed across principals. Independent trust roots keep authentication and each verification attestation from sharing a key, and survive co-location unchanged. Separating login and machine-to-machine issuance into two registered clients with disjoint scopes removes the privilege-escalation path where a `client_credentials` grant could mint an `enrollment:write` token without a user login.

**Loses.** The gateway is stateful, so scaling it horizontally requires a shared session store. The Decision Engine must hold each issuer's public-key configuration; the key-distribution mechanism is out of scope and owned by the external services. Two JWTs per request adds modest complexity to frontend integration. A second client secret — the machine client's — must be provisioned and rotated independently of the login client's. Internal message transport is currently unauthenticated, resting on broker network isolation, which must be hardened before any non-isolated deployment (deferred ADR).
