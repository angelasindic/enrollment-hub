# Security Controls

What the system enforces, where, and how each claim is checked. One row per control. The
layer model and the reasoning are in [architecture.md §8.1](architecture.md) and
[ADR-03](adr/03-security-architecture.md); this page is the index into them.

Columns: **Where** is the code that implements the control; **Pinned by** is the test that fails
if it regresses; **Argued in** is the document that records why; **Status** says what is not
implemented as plainly as what is. Class names are given without paths; each module has one class
of that name.

## Identity and access (user-facing path)

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| Interactive login is OIDC `authorization_code` at the gateway; an unauthenticated request is redirected to the identity provider | `SecurityConfiguration` (gateway), `gateway/src/main/resources/application.yml` | `ApplicationIT.protectedRoute_whenUnauthenticated_redirectsToOauthLogin` (gateway) | ADR-03; §8.1 | Implemented |
| Tokens stay server-side. The browser holds a session cookie; the gateway relays the access token as a bearer header and the attestation as `X-Prerequisite-Token` | `TokenRelay` route filter in the gateway `application.yml`; `PrerequisiteTokenRelayFilter` | `PrerequisiteTokenRelayFilterTest` (three cases); `PaymentCheckControllerIT.storesAttestationInSession` | ADR-18 §Gateway | Implemented |
| CSRF protection on the gateway session: readable `XSRF-TOKEN` cookie, `X-XSRF-TOKEN` header | `SecurityConfiguration` (gateway), `CsrfConfigurer::spa` and `CsrfCookieFilter` | none | Class javadoc | Implemented, not test-pinned |
| Access tokens carrying `enrollment:write` are stamped `aud: enrollment-api`; other tokens keep the default audience | `SecurityConfiguration` (authorization-server), `OAuth2TokenCustomizer` | `AuthorizationCodeFlowIT.authorizationCodeFlow_issuesAccessTokenWithEnrollmentApiAudience`; `AuthorizationCodeFlowIT.machineToken_withoutEnrollmentWrite_keepsDefaultAudience` | ADR-03 | Implemented |
| The decision-engine accepts only tokens that verify against the identity provider's JWKS, match the issuer, carry the audience, and have not expired | `decision-engine/src/main/resources/application.yml` (`jwk-set-uri`, `issuer-uri`, `audiences`) | `EnrollmentControllerTest.returns401WhenNoToken` | ADR-03; §8.1 | Implemented |
| Enrollment submission requires scope `enrollment:write` | `SecurityConfiguration` (decision-engine) | `EnrollmentControllerTest.returns403WhenScopeMissing` | ADR-03 | Implemented |
| Login client and machine client are separate registrations: the machine client cannot obtain `enrollment:write`, the login client cannot use `client_credentials`, a wrong secret is rejected | `SecurityConfiguration` (authorization-server), client seeding | `AuthorizationServerSecurityIT.machineClient_cannotRequestEnrollmentWriteScope`; `AuthorizationServerSecurityIT.loginClient_cannotUseClientCredentialsGrant`; `AuthorizationServerSecurityIT.tokenEndpoint_withInvalidClientSecret_isUnauthorized` | ADR-03 | Implemented |
| Only the `health`, `info` and `prometheus` actuator endpoints are exposed; every other path on every service requires authentication | `management.endpoints.web.exposure` in each `application.yml`; each `SecurityConfiguration` | `ApplicationIT.actuatorHealth_isPublic` (gateway) | none | Implemented. Why the three stay unauthenticated, and why the OpenAPI paths do, is not written down |
| Request-rate limiting on the enrollment route | none | none | §8.1 "Rate limiting" states the intended design | Not implemented |
| TLS termination and network-level filtering at the edge | none in the local stack | none | §7.2; §8.1 layer table | Target environment only |

## Prerequisite attestation (CREDIT_CARD route)

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| The payment-check attestation is minted under a trust root distinct from the identity provider: its own RSA key, issuer and JWKS | `PaymentCheckConfiguration` (authorization-server) | `PrerequisiteTokenIssuanceIT.mintsCreditCardCheck_underDistinctIssuerAndKey`; `PrerequisiteTokenIssuanceIT.jwksIsPublic` | ADR-18 | Implemented |
| Issuance is server-to-server, guarded by a `client_credentials` token with scope `prerequisite:issue`; the browser never presents a payment result | `PaymentCheckConfiguration` filter chain; `PaymentCheckClient` (gateway) | `PrerequisiteTokenIssuanceIT.issuanceRejectsUnauthenticated`; `PaymentCheckControllerIT.unauthenticated_isRedirectedToLogin` | ADR-18 | Implemented |
| The decision-engine validates the attestation against the payment-check JWKS for signature, issuer, audience and expiry, requires `type = credit_card_check`, and binds its subject to the bearer's subject. Failure is a 403 before any record exists | `PrerequisiteTokenValidator`; `EnrollmentController` | `PrerequisiteTokenValidatorTest` (five cases, including `PrerequisiteTokenValidatorTest.rejectsSubjectMismatch` and `PrerequisiteTokenValidatorTest.rejectsWrongType`); `EnrollmentControllerTest.returns403WhenCreditCardPrerequisiteRejected` | ADR-18; ADR-03 | Implemented. "Before any record exists" follows from the call order in the controller and is not asserted by a test |
| The INVOICE route carries no attestation; its eIDAS prerequisite is deferred | `EnrollmentController` | `EnrollmentControllerTest.invoiceRouteSkipsPrerequisiteValidation` | ADR-19 | Deferred by decision |

## Data protection

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| A rejected prerequisite creates no correlation record and publishes nothing | `EnrollmentController` (validation precedes publish) | `EnrollmentControllerTest.returns403WhenCreditCardPrerequisiteRejected` asserts the status only | §8.2 | Implemented; non-persistence is not asserted |
| Each check command carries only what its signal needs; the geo-scoring request holds the shipping address and no identity | `GeoScoreRequest` (contracts) | `EventSerializationTest.geoScoreRequest_roundTrip` | §8.2 "Minimization at the signal boundary" | Implemented. `FraudCheckRequest` carries the full payload by documented exception (§10.1) |
| The decision event never carries the correlation `enrollmentId` | `DecisionEventMapper`; `EnrollmentDecisionEvent` (contracts) | `DecisionEventMapperTest.eventJson_neverContainsTheCorrelationEnrollmentId`; `EventSerializationTest.enrollmentDecisionEvent_json_carriesNoEnrollmentId` | ADR-17 | Implemented |
| The enrollment payload is erased from the correlation row once the decision event is broker-confirmed; the pseudonymous decision record is kept | `PayloadRetentionJob`; `EnrollmentRepository` | `PayloadRetentionIT` (six cases, including `PayloadRetentionIT.dispatched_erasesThePayload_andKeepsTheDecisionRecord` and `PayloadRetentionIT.undispatchedRow_keepsItsPayload_regardlessOfAge`) | ADR-20; §8.2 | Implemented |
| Geocoding cache keys are a peppered HMAC-SHA256 of the normalized address. The pepper has no default; the service refuses to start without it | `GeocodingCacheKeyService`; `geo-scoring/src/main/resources/application.yml` (`hmac-secret`) | `GeocodingCacheKeyServiceTest` (five cases, including `GeocodingCacheKeyServiceTest.keyFor_knownVector`) | `geo-scoring/design.md`; §8.2 | Implemented |
| Geo-index members are single-use enrollment ids in country partitions, expired after 48 hours by a cleanup job | `GeoIndexService`; `GeoIndexCleanupJob`; `GeoIndexKeyStrategy` | `GeoIndexServiceIT.cleanupExpired_removesExpiredEntries_doesNotAffectDensityCounts`; `GeoIndexKeyStrategyTest` | ADR-11; ADR-12; §8.2 | Implemented |
| No log line carries an address, an address fragment or derived coordinates; request URIs are logged path-only and upstream error bodies reduced to their status code | `GeocodingService`; `NominatimGeocodingProvider` | none | §8.2 "Address data never reaches the logs" | Implemented, enforced by review rather than by a test |
| Prerequisite tokens are validated in memory and never persisted | `PrerequisiteTokenValidator` | none | §8.2 | Implemented, not test-pinned |
| Personal data parked in the three PII-bearing dead-letter queues is bounded in time | none | none | ADR-13 | Not implemented |

## Decision integrity

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| The decision is computed once, frozen under a `decisionId`, and republished byte-identically; `dispatched_at` is stamped only after broker confirm and never twice | `EnrollmentService`; `DecisionDispatcher`; `EnrollmentRepository` | `EnrollmentSweepIT.dispatchPhase_publishesThePersistedDecision_sameDecisionId_thenStamps`; `EnrollmentSweepIT.dispatchPhase_markDispatched_guardRejectsASecondStamp` | ADR-17 | Implemented |
| The scoring signal can raise `CONDITIONAL_APPROVED` but can never drive `REJECTED` | `DecisionEngine` | `DecisionEngineTest.scoringSignal_cannotDriveRejected`, plus the outcome matrix in the same class | ADR-14; §8.6 | Implemented |
| A redelivered or duplicate intake message produces exactly one correlation row and one dispatch | `EnrollmentIntakeService`; `EnrollmentCorrelationService` | `redeliveredIntakeMessage_producesExactlyOneRow`; `duplicateDelivery_isIdempotentNoOp`; `redeliveredIntake_completed_acknowledgesWithoutDispatch` | ADR-13; §8.7 | Implemented |
| The identity that submitted an enrollment is recorded on the enrollment | none | none | none | Not implemented. The bearer subject is read for subject binding and discarded |

## Internal transport

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| Per-message or per-service authentication between the decision-engine and its workers | none | none | ADR-03 "Internal trust posture"; §8.1 "Internal transport" | Not implemented. Broker network access is the boundary; the trigger for changing that is a shared, exposed or multi-tenant broker |
| No user credential crosses the broker | The contract records (`GeoScoreRequest`, `FraudCheckRequest`) have no credential field | `EventSerializationTest` pins the wire shapes | ADR-03 | Implemented by construction |

## Secrets, supply chain and build

| Control | Where | Pinned by | Argued in | Status |
|---|---|---|---|---|
| Key material and environment files are ignored by git | `.gitignore` | none | none | Implemented |
| Credentials at rest in the authorization server go through a delegating encoder; the seeded user password is `{bcrypt}` | `SecurityConfiguration` (authorization-server) | none | Class javadoc | The two OAuth client secrets are stored `{noop}` and carry local defaults in the gateway `application.yml`; both must be overridden outside the local stack |
| GitHub Actions pinned to commit SHAs, with least-privilege `permissions` | `.github/workflows/ci.yml` | none | none | Implemented |
| Dependencies watched across Maven, npm and Actions | `.github/dependabot.yml` | none | none | Implemented |
| Test infrastructure images pinned to digests | `BaseIntegrationTest` in each module | the integration suites themselves | Class javadoc | Implemented. The `otel-local` stack uses `latest` tags |
