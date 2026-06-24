# ADR-02: Account Service Boundary

**Status:** Accepted  
**Date:** June 2026

## Context

The decision-engine coordinates a workflow — it should not own account data (name, email, address, business details). In
a real enrollment platform, the Account Service already exists and is maintained by another team. This project's scope
begins when enrollment data is submitted and a fraud check is needed.

## Decision

Define an Account Service as the system of record for account data. It is explicitly out of scope for this project.

The Account Service owns the account lifecycle and data persistence. The decision-engine never writes to the Account
Service's data store — it only emits a final decision (`APPROVED` / `REJECTED` / `CONDITIONAL_APPROVED`);
the Account Service consumes that decision and updates account status on its own authority.

Project scope begins at enrollment submission and ends at decision emission. The Account Service consuming that decision
is not provided — EnrollmentDecisionEvent is published to RabbitMQ but not consumed within this project. 

### EnrollmentDecisionEvent Delivery

The decision-engine publishes `EnrollmentDecisionEvent` to RabbitMQ after the completion predicate is satisfied. 
The Account Service consumes it asynchronously. This keeps the entire pipeline async end-to-end: the decision-engine 
never makes a synchronous call to the Account Service and is therefore unaffected by its availability. 
The decision-engine uses the persist-then-publish relay, so a crash between decision and publish doesn't lose the event, 
and the consumer must be idempotent.

## Consequences

**Gains:** Clean separation of concerns. The decision-engine owns workflow state, not account data. Adding the Account
Service as a downstream consumer requires no changes to the decision-engine's internal logic or the geo-scoring service.

**Loses:** No real account persistence in the MVP. Acceptable because account data management is commodity functionality
that doesn't differentiate the project.
