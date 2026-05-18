# ADR-006: Account Service Boundary

**Status:** Accepted  
**Date:** May 2026

## Context

The decision-engine coordinates a workflow — it should not own account data (name, email, address, business details). In a real logistics platform, the Account Service already exists and is maintained by another team. This project's scope begins when account creation data is submitted and a fraud check is needed.

## Decision

Define an Account Service as the system of record for account data. It is explicitly out of scope for this project.

The Account Service owns the account lifecycle and data persistence. The decision-engine never writes to the Account Service's data store — it only produces a final decision (`APPROVED` / `REJECTED` / `CONDITIONAL_APPROVED`) that the Account Service consumes to update account status.

For the MVP, the Account Service is simulated: a REST endpoint on the decision-engine accepts a test payload and kicks off the pipeline directly. In production, the REST endpoint would be fronted by the Gateway, and the Account Service would be a downstream consumer of `EnrollmentDecisionEvent`.

## EnrollmentDecisionEvent Delivery

The decision-engine publishes `EnrollmentDecisionEvent` to RabbitMQ after the completion predicate fires. The Account Service
consumes it asynchronously. This keeps the entire pipeline async end-to-end: the decision-engine never makes a synchronous
call to the Account Service and is therefore unaffected by its availability.

For the MVP, the simulated Account Service consumer is not implemented — `EnrollmentDecisionEvent` is published but not
consumed within this project.

## Consequences

**Gains:** Clean separation of concerns. The decision-engine owns workflow state, not account data. Replacing the simulated endpoint with a real Account Service requires no changes to the decision-engine's internal logic or the geo-scoring service.

**Loses:** No real account persistence in the MVP. Acceptable because account data management is commodity functionality that doesn't differentiate the project.
