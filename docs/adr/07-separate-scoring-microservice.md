# ADR-07: Separate Scoring Microservice (Event-Driven)

**Status:** Accepted

**Date:** June 2026

## Context
Geo-Scoring could remain an in-process module withing the decision-engine.
However, two constraints make extraction the right choice:

1. **Independent failure requirement.** If Redis is unreachable or the Nominatim geocoding service is down, the
   Geo-Scoring capability should fail without stalling the entire enrollment pipeline. Other checks should
   continue independently.

2. **Async enrollment.** The enrollment process is not request-response — the user submits data and receives
   a decision later via email. The decision-engine manages a multi-step pipeline where Geo-Scoring is one of several
   parallel checks.

## Decision

Extract the Geo-Scoring logic into a separate microservice, communicating with the decision-engine via asynchronous
events (RabbitMQ).

The Geo-Scoring service consumes a `GeoScoreRequest` command, performs geocoding + GEOSEARCH + threshold evaluation,
and emits a `GeoScoreResult` event. It owns its Redis instance, its Nominatim instance, its libpostal normalization
service, and its threshold configuration. It can be restarted, redeployed, or scaled without affecting the decision-engine.

## Consequences

**Gains:** Failure isolation — Redis, libpostal normalization nor Nominatim outages block enrollment. Independent
scaling and deployment lifecycle. Clean service boundary justified by operational constraints, not language boundaries.

**Loses:** Operational complexity of a distributed system (event contracts, message broker dependency, eventual
consistency). Acceptable at this scale and justified by the failure isolation requirement.
