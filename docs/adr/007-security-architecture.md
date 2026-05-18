# ADR-007: Security Architecture — API Gateway, JWT Authentication, Prerequisite Tokens

**Status:** Accepted  
**Date:** May 2026

## Context

The account creation flow requires that only authenticated users who have completed prerequisite checks (credit card verification, and in the future eIDAS identity verification) can submit account creation requests. These prerequisite checks are performed by external services outside this project's scope. The system needs to verify that these steps were completed without coupling to the internal workings of the external services.

## Decision

Use a layered security model with three distinct layers:

**Layer 1 — AWS Application Load Balancer (ALB).** Handles TLS termination: all external traffic arrives over HTTPS and is decrypted at the ALB, so services in the private zone communicate over plain HTTP. The ALB also provides path-based routing to the gateway, health checks, connection draining, and automatic scaling. AWS Shield provides baseline DDoS protection; AWS WAF can be attached for IP blocking, geo-filtering, and network-edge rate limiting.

**Layer 2 — Spring Cloud Gateway.** Handles application-level routing, rate limiting, and JWT authentication. The gateway validates the Keycloak-issued authentication JWT on every inbound request — signature verification, expiry check, issuer check. This is a lightweight, stateless check that rejects invalid or expired tokens before they reach any downstream service. The gateway forwards the validated JWT to downstream services via the Authorization header. The gateway does not inspect prerequisite tokens — those are opaque payload to the gateway.

**Layer 3 — Service-level authorization.** Each service is a Spring Security resource server that extracts claims from the forwarded JWT for authorization decisions (roles, scopes). The decision-engine additionally validates prerequisite tokens: the credit card verification token and (in the future) the eIDAS identity verification token. These are JWTs signed by the respective external services with their own private keys. The decision-engine validates the signature using the corresponding public key, checks expiry, and extracts claims (subject identifier, verification type, timestamp). If any prerequisite token is missing or invalid, the request is rejected and the attempt is logged — no correlation record is created, no downstream checks are dispatched.

> **Not yet implemented.** The decision-engine's `spring-boot-starter-oauth2-resource-server` dependency is currently
> commented out and no prerequisite token validation code exists. The `application.yml` issuer URI is pre-configured
> for when the dependency is activated.

**Prerequisite token contract.** The decision-engine expects prerequisite tokens as JWTs with at minimum: `sub` (subject identifier), `type` (verification type: `credit_card_check` / `eidas_identity`), `iat` (issued-at), and `exp` (expiry). Each token is signed with a private key controlled by the issuing service. The decision-engine validates against the corresponding public key.

**Prerequisite check services.** The decision-engine does not integrate with any external verification provider directly. Dedicated backend services absorb provider-specific details (e.g., Adyen HMAC webhooks, Onfido JWTs) and produce standardized signed JWTs. The decision-engine only validates these JWTs. This decoupling means the decision-engine is unaffected if a prerequisite provider is replaced.

**Alternative considered:** Centralizing all token validation in the gateway. Rejected because prerequisite tokens are service-specific business logic, not cross-cutting authentication concerns. Mixing business-level precondition checks into the gateway would couple the gateway to the decision-engine's domain logic.

## Consequences

**Gains:** Defense in depth — three independent security layers. Clean separation of authentication (gateway) from authorization and prerequisite validation (services). decision-engine decoupled from payment/identity providers. Prerequisite token pattern prevents the vulnerability where account creation could be called without completing payment verification.

**Loses:** Prerequisite token public keys must be configured on the decision-engine. Key distribution mechanism is out of scope and must be defined by external services. Two JWTs per request adds modest complexity to the frontend integration.
