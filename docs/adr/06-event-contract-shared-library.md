# ADR-06: Event Contract Ownership — Shared Library Module

**Status:** Accepted

**Date:** June 2026

## Context

Several event types cross service boundaries over RabbitMQ. Each is published by one service and consumed by one or more others. The system is a mono-repo maintained by a single team, with independently deployable services. These contracts need a single definition that keeps producer and consumer from drifting apart.

## Decision

Place all cross-service event records and shared enums in a dedicated Maven module, `dev.sindic.enrollmenthub:contracts`. The producer owns each contract and every consumer depends on the module, so a mismatch fails at compile time within the mono-repo build. The rejected alternatives are recorded under Options considered.

**Producer owns the contract.** Each event is defined once by its producer. Consumers reference the shared type rather than redeclaring it, which removes the silent drift that per-service copies allow.

**Additive-only evolution.** New fields are added with default values, and consumers ignore unknown fields, so a field addition is backward and forward compatible on the wire. An old consumer reads a payload carrying a new field without change, and a consumer is rebuilt against the new module version only when it needs the added field. Removing or renaming a field is a breaking change and requires a coordinated deployment. This additive rule is what keeps the services independently deployable despite the shared compile-time dependency.

**camelCase wire format.** The serialized field names are camelCase, the Jackson default with no naming strategy, matching the record component names. No Jackson configuration is required, since all consumers are Java services in the same repository.

**decision-engine-internal types stay out.** Signal lifecycle state, signal configuration and classification, and the correlation record are implementation details of the decision-engine and are not part of the shared module. The shared enums (`RiskLevel`, `SignalOutcome`, `DecisionResult`) are mirrored in the decision-engine domain and kept in alignment with the module.

## Options considered

| Option | Verdict | Reason |
|---|---|---|
| Shared library module | Accepted | Single source of truth, with compile-time enforcement across the mono-repo build. Simpler than a schema registry at the current scale |
| Schema registry (Avro or Protobuf) | Rejected | Enables fully independent, multi-team deployment, but its runtime registration and wire-compatibility machinery is not justified at a single team and a small number of event types |
| Inline definition per service | Rejected | No shared dependency, but contracts drift silently once more than one service consumes an event |

## Revisit when

The shared module is the right choice while the team and the event set stay small. Migrate to a schema registry when any of the following holds: ownership spans multiple teams, the event types exceed roughly eight, or independent deployment without recompilation becomes a requirement. The migration is low-cost, since the records extract to Avro or Protobuf schemas once the trigger is met.

## Consequences

**Gains:** A single source of truth for every cross-service event schema, with mismatches caught at compile time. Event records are documented once and referenced from the architecture document rather than maintained as inline pseudo-schemas.

**Loses:** Version coupling at the dependency level, since all services share one module. A breaking change to a contract forces a coordinated deployment. Both are acceptable for a single-team mono-repo, and the trigger to move to a schema registry is recorded above.

**Reversibility:** The records map directly onto Avro or Protobuf schemas, so the shared-library decision can be replaced without revisiting the event model itself.