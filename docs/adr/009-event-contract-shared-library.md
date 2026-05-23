# ADR-009: Event Contract Ownership — Shared Library Module

**Status:** Accepted
**Date:** May 2026


## Context

Several event types cross service boundaries via RabbitMQ, each is published by one service and consumed by one or more
others. The system is a mono-repo maintained by a single team with independently deployable services.

Three options were considered for managing these contracts:

1. **Shared library (Maven module).** Event records and shared enums live in a dedicated module
   (`dev.sindic.enrollmenthub:contracts`). All services depend on it. Contract mismatches break at compile time.
2. **Schema registry (Avro/Protobuf).** Schemas registered externally. Contract enforcement at runtime via
   serialization/deserialization. Enables fully independent deployment.
3. **Inline definition per service.** Each service defines its own copy of the event classes. No shared dependency.
   Contracts can drift silently.

## Decision

```
Decision:        Shared library module (dev.sindic.enrollmenthub:contracts) containing
                 all cross-service event records and shared enums. Producer
                 owns the contract; consumers depend on the library.
Solves:          Single source of truth for event schemas. Compile-time
                 breakage on contract mismatch.
Doesn't solve:   Runtime schema evolution for independently deployed services
                 across teams. Wire-level compatibility checking.
Trade-off:       All services must coordinate on the shared module version.
                 A field addition requires recompiling all consumers.
Simpler first:   At ≤5 event types and a single team, a shared module is
                 simpler than a schema registry.
Trigger to add
schema registry: Multi-team ownership, >8 event types, or a requirement
                 for independent deployment without recompilation.
Reversibility:   Easy — extract records to Avro/Protobuf schemas when the
                 trigger conditions are met.
Simplicity gate: Would inline definitions suffice? No — with multiple services
                 consuming shared events, silent drift is a real risk even
                 for a single team. Compile-time enforcement is worth the
                 shared dependency.
```

---

## Module Contents

The `dev.sindic.enrollmenthub:contracts` module contains all cross-service event records and shared enums,
organised under `dev.sindic.enrollmenthub.contracts.events` and `dev.sindic.enrollmenthub.contracts.domain`.

### JSON Naming Convention

Field names use **camelCase** in the JSON wire format — the Jackson default with no naming strategy annotation. All
consumers are Java services in the same mono-repo; camelCase matches the record component names and requires zero
Jackson configuration. The field tables below use `snake_case` as a documentation convention for readability; the
actual serialized field names are camelCase (e.g., `enrollmentId`, `paymentType`, `countryCode`).

All events are Java records, Jackson-serializable.

### Shared Enums

Shared enums are defined in the contracts module as the canonical
source of truth. Values and semantics are documented in ADR-018 (classification model) and in the enum Javadoc.

### Not Included (orchestrator-internal)

Orchestrator-internal types — signal lifecycle state, signal configuration and classification, correlation record
entity — are not part of the shared contracts' module. They are implementation details of the orchestrator's
scatter-gather pipeline documented in ADR-018. The shared enums (`RiskLevel`, `SignalOutcome`, `DecisionResult`)
are mirrored in the orchestrator domain and kept in alignment with the contracts' module.

---

## Schema Evolution Strategy

Field addition only (backward-compatible). New fields are added with default values; all consumers are configured
to ignore unknown fields. Removing or renaming fields is a breaking change requiring coordinated deployment.

---

## Consequences

**Gains:** Single source of truth for all cross-service event schemas. Compile-time contract enforcement. Event
records documented once and referenced from architecture.md rather than maintained as inline pseudo-schemas.

**Loses:** Version coupling — all services must use the same version of the shared module. Acceptable for a
single-team mono-repo. The trigger to migrate to a schema registry is documented above.
