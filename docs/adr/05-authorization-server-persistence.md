# ADR-05: Authorization Server Persistence — JDBC-backed Authorization State and User Store

**Status:** Accepted

**Date:** June 2026

## Context

The authorization-server is the enrollment-hub identity provider and issues the tokens consumed by ADR-03 Layer 2. Its durable state, which comprises refresh tokens, in-flight authorization codes, and recorded consent, is operationally critical. Spring Authorization Server (SAS) defaults to in-memory implementations for this state, which is unsuitable for production for two reasons. 

* A restart discards all issued refresh tokens, in-flight authorization codes, and consent records, forcing every user to re-authenticate on each deploy. 

* Two replicas hold disjoint state, so an authorization code issued by one instance cannot be exchanged at another, and the `authorization_code` flow breaks once the IdP runs behind a load balancer.

SAS exposes three stateful components: the registered-client repository, the authorization service covering tokens, codes, and flow state, and the consent service. A user store sits alongside them.

## Decision

Persist authorization state in PostgreSQL using the JDBC implementations SAS ships as its supported durable mechanism, back the user store with the JDBC user-details store, and place both in a service-owned `authorization_server` schema. The rejected alternatives and their reasons are recorded under Options considered.

**Persist all three SAS components, not the client repository alone.** The authorization and consent services hold state that must survive restarts and replicate. The client repository does not require this, since it holds a single client that is seeded once and never mutated at runtime. It is persisted alongside the other two for uniform storage and to establish a durable client registry for later client administration (RFC 7591, see Deferred). Providing the JDBC implementations is sufficient for the authorization-server configurer to use them in place of the in-memory defaults.

**Schema-per-service, not a second database.** The decision-engine convention applies. One PostgreSQL instance hosts one schema per service, each with its own Flyway history. The authorization-server owns the `authorization_server` schema within the existing `enrollmenthub` database. Because the SAS JDBC services emit unqualified SQL, the active schema is set on the datasource connection rather than through JPA configuration.

**Idempotent client seeding.** The database row is the source of truth. A startup routine persists the typed client definition only when it is absent, so the seed is idempotent across restarts and the typed definition remains authoritative.

**A single credential encoder for both stores.** User passwords and the client secret use different encoding schemes, yet SAS resolves one `PasswordEncoder` bean for client authentication, so both must be served by that single bean. A delegating encoder that selects the scheme per stored value satisfies both. It also makes a later change of scheme, such as hashing the client secret, a stored-value change rather than a code change.

## Options considered

| Option | Verdict | Reason |
|---|---|---|
| In-memory state (including the client declared via properties) | Rejected | No restart survival and no multi-replica support. The property form remains in-memory |
| Registered client moved to JDBC only | Rejected | Hardens the least critical component, leaving tokens and consent ephemeral |
| Hand-rolled JPA implementations | Rejected | SAS ships no JPA implementation by design, as its domain types are persistence-agnostic. The JDBC services are the supported path and avoid mapping those types to entities |
| Persist all three components plus JDBC user store, own schema | Accepted | Restart survival and multi-replica safety for the components that require it, consistent with the schema-per-service convention |
| Separate database for the IdP | Rejected | The owned schema and migration history already provide bounded-context isolation. A second database adds operational surface without benefit |

## Deferred

Signing-key stabilization and rotation. The RSA signing key is generated per startup, which independently breaks token validation across replicas and is unaffected by this change. It is tracked separately, because persistence and key management are orthogonal.

Deployment hygiene. A TLS/HTTPS issuer, a hashed client secret, and an externalized issuer URI remain outstanding. The client secret is currently stored unhashed and the issuer is the local development URL.

User federation. A single Flyway-seeded demo user is provided. A production user store or identity federation is out of scope.

Client administration and dynamic registration (RFC 7591), multi-tenancy, and HSM- or Vault-backed keys are out of scope.

## Consequences

Authorization codes, refresh tokens, and consent now survive restarts and replicate, so the IdP can run more than one instance once the signing key is stabilized.

The authorization-server now requires PostgreSQL to start and is no longer a zero-dependency process. Integration tests provision a Testcontainers PostgreSQL instance. The datasource configuration deviates from the decision-engine setup, a consequence of the SAS components emitting unqualified SQL.

Access tokens are an explicit caveat. They default to self-contained JWTs and are validated by signature rather than by a store lookup. Persisting the authorization service therefore does not preserve them across a restart on its own. Because the signing key changes on each restart while stabilization remains deferred, a JWT issued before a restart fails validation afterward. Authorization codes, refresh tokens, and consent are server-side lookups and do survive.

Reversal requires bean wiring and two Flyway migrations. The deferred items layer onto this decision without revisiting it.

## Related ADRs

- **ADR-01** — PostgreSQL and Flyway, the persistence and migration tooling adopted here for the IdP.
- **ADR-03** — The authorization-server is the Layer 2 token issuer, and this decision makes its authorization state durable. Co-located prerequisite-token issuance shares this deployable and persistence.