# ADR-011: eIDAS Integration via Connector-Issued JWT, Not In-Pipeline Async Check

**Status:** Accepted
**Date:** May 2026

## Context

The INVOICE route requires eIDAS identity verification at Level of Assurance: High before
an account can be created. The question is where in the architecture this verification sits
and what integration model it follows.

Two models were considered.

**Model A — Prerequisite gate (JWT validation at decision-engine entry):** eIDAS verification
completes before the account creation request is submitted. A dedicated eIDAS Connector
service handles the SAML exchange with the national eIDAS node, validates the assertion,
and issues a signed JWT containing the verified identity claims. The user attaches this JWT
to the account creation request. The decision-engine validates it offline at the PEP/PDP
boundary, identically to how it validates the Credit_Card_JWT.

**Model B — In-pipeline async check:** The decision-engine dispatches a check command
to an Identity service, which performs eIDAS verification as a scatter-gather
participant. The Identity service emits `IdentityCheckResult` asynchronously, which the
decision-engine correlates via the durable correlation record.

The decision between these models is not primarily a design preference — it is determined
by the eIDAS protocol itself.

The eIDAS network operates as a **browser-redirect SAML 2.0 flow**. The service provider
generates a signed `AuthnRequest` specifying the required LoA and minimum data set
attributes. The user's browser is redirected to the national eIDAS Connector node, which
routes to the citizen's home country IdP. The citizen authenticates using their national
eID credential. The SAML assertion travels back through the eIDAS node network to the SP's
Assertion Consumer Service endpoint. This entire exchange requires an active browser
session and completes in real time before the user can proceed.

This protocol structure makes Model B impossible. There is no point at which a
check command can be dispatched and an eIDAS result awaited asynchronously — the
identity assertion arrives at the ACS endpoint during the user's session, not as a response
to a later async dispatch. A timeout on the SAML redirect does not produce a pipeline
timeout; it produces a browser-level error that prevents the user from completing the
prerequisite flow at all.

Model A is therefore not a design choice — it is the correct mapping of the eIDAS protocol
onto the architecture.

## Decision

Implement eIDAS integration via a dedicated **eIDAS Connector service** that acts as a
SAML 2.0 Service Provider. This service manages the browser-redirect exchange with the
national eIDAS node, validates the SAML assertion (signature, LoA, minimum data set
attributes), and issues a signed JWT containing the verified identity claims (`sub`,
`type: eidas_identity`, `iat`, `exp`, verified attributes). The user attaches this JWT to
the account creation request alongside the other required fields.

The decision-engine validates the eIDAS JWT offline at the PEP/PDP boundary, using the eIDAS
Connector's public key. This is structurally identical to how the Credit_Card_JWT is
validated — a different issuer, a different `type` claim, but the same validation pattern.

eIDAS identity verification is therefore a **prerequisite gate**, not a scatter-gather
participant. The INVOICE route scatter-gather contains one active check: Internal Fraud
Detection.

For the MVP 2 implementation, the EU's pre-production eIDAS test environment
(`eidas.ec.europa.eu/EidasNode/`) is available for exercising the full SAML flow without
requiring national SP registration. This permits end-to-end validation of the Connector
service and JWT issuance path before production onboarding.

**eIDAS 2.0 forward compatibility:** The eIDAS 2 regulation (EU 2024/1183) shifts the
presentation protocol toward the EUDI Wallet and ISO/IEC 18013-5 / OID4VP, moving away
from SAML. The eIDAS Connector service boundary is the correct seam to absorb this
evolution — the protocol changes inside the Connector; the JWT-to-decision-engine interface
does not change. Private sector relying parties of non-trivial scale are expected to be
required to accept the EUDI Wallet by 2026–2027.

## Consequences

**Gains:** The decision-engine's prerequisite validation logic is symmetric across both routes
— one JWT per payment type, validated by the same offline pattern. The correlation record
is simpler: the INVOICE route initialises one PENDING signal (fraud detection) rather than
two (geo-scoring + fraud detection on the CREDIT_CARD route). The eIDAS
Connector service owns the SAML complexity entirely — the decision-engine is unaware of SAML
and unaffected by protocol evolution inside the Connector.

**Loses:** The eIDAS Connector service is a non-trivial component to implement: SAML SP
registration with a national node, assertion validation, JWT issuance, and key management.
This is Phase 2 scope. In MVP 1 the eIDAS_JWT prerequisite check is scaffolded as a
validation stub at the decision-engine boundary — the Connector service itself is not yet
built.

**What this does not solve:** A malicious actor who obtains or forges a valid eIDAS JWT.
Prerequisite token signature validation and short expiry windows mitigate this, but key
distribution for the Connector's signing key is an operational concern outside this ADR's
scope.
