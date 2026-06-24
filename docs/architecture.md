# Architecture: Event-Driven Enrollment

## Overview

**About this Project**

This is a portfolio project. I built it out far enough to showcase the real engineering decisions behind a resilient,
event-driven system — one that guarantees message delivery even when downstream dependencies are slow or unreliable. The
README has the longer version of why I built it and what it's meant to show. This document is the architecture itself.

**Architecture At a Glance**

The Enrollment Hub accepts registration requests, scores each one for fraud, and emits a decision that can be audited
later. It sits between the registration frontend and the services that fulfil an enrollment.

At an estimated five requests a second, throughput was never the constraint. I designed the system around two hard
operational requirements instead: an accepted request must never be lost, and the back-office services it depends on are
slow at the tail (over a second) and prone to downtime.

One central design decision follows from that: the hub accepts requests immediately and durably, moves scoring to the
background, and delivers the decision asynchronously.

Four decisions drive this design:

- Accepting a request and scoring it are separated by a durable queue, so a slow or failing downstream service never
  reaches the applicant (ADR-14).
- Each fraud check runs as its own service. The decision engine dispatches the checks in parallel, gathers the results,
  and aggregates a final decision (ADR-08).
- A slow or unresponsive check never blocks an enrollment. The system emits a decision and explicitly notes the missing
  signal, so the gap stays traceable (ADR-16).
- Requests are written to durable storage before any check begins, guaranteeing no check runs against a record that does
  not yet exist — and without a separate outbox table (§8.7).

**Safety & Validation by Design**

One property I chose to enforce by construction rather than convention: geo-scoring can flag an enrollment for human
review, but it can never reject one on its own. By constraining the aggregation branch to only raise review flags, the
worst a misconfigured threshold can do is grow the review queue — it cannot cause a wrongful automated rejection (§8.6).

I also wanted the design validated by data, not just asserted. Geo-scoring is deployed with cautious thresholds
alongside a falsifiable hypothesis, so production data can confirm or refute the system's efficacy before it affects
real users (see the Geo-Scoring Business Analysis, §5).

**Scope**

This is a portfolio project, so it runs locally on single-instance Postgres, RabbitMQ, and Redis. That is not the
production posture; what I would change for production is documented in §7.4, and where a decision would shift at higher
volume, the relevant ADR says so.

---

## 1. Introduction & Goals

### 1.1 Architectural Drivers

Four forces shape the structure. Each names a pressure first, then the architectural response to it.

**Extensibility.** Fraud is an evolving threat, so new checks are added over time. Each check runs as its own service
that joins the scatter-gather as a listener, so adding one is a localized change — a `SignalConfig` entry, a request
builder, a result listener — never a rewrite of the scoring logic.

**Fault isolation.** The pipeline depends on external services — geocoding, spatial indexing, fraud scoring — that lack
strong SLAs and fail independently. Because each signal is augmentative rather than essential, each is isolated behind
its own process boundary and the system fails open, so a Nominatim outage or a fraud-service failure degrades detection
without stalling enrollment.

**Durability.** Enrollment is a write-once event: once the applicant has seen `202 Accepted`, the work must survive a JVM
restart, a broker partition, or database lock contention. An intake queue and a durable correlation record hold that
state, removing the lost-update risk of in-memory `@Async` processing.

**Latency isolation.** The downstream services carry P99 latencies over a second at best-effort availability. A
synchronous call chain would pass those delays to the applicant or drop the enrollment during an outage, so ingress is
decoupled from evaluation by durable messaging and the decision is delivered out of band.

---

### 1.2 Quality Goals

The quality attributes from §1.1 resolve into concrete goals here — some measurable targets, some design guarantees the structure must hold to.

| Quality Goal        | Target / Guarantee                                                                                                                                                                    |
|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Scoring Latency** | **Bounded latency:** P95 ≤ 2 seconds for `GeoScoreResult` events under peak load                                                                                                      |
| **Resiliency**      | **Zero-block intake:** downstream detection failures trigger a fail-open state rather than blocking enrollment (§8.6)                                                                 | 
| **Privacy**         | **48-hour TTL:** automatic eviction of PII and spatial data from the geo-index to minimize GDPR exposure                                                                              |
| **Integrity**       | **Strict gatekeeping:** no enrollment is scored until its prerequisite payment and identity assertions are verified (§8.1, ADR-03)                                                   |
| **Reproducibility** | **Deterministic scoring:** identical request and geo-index state always yield the same score — no ML model, no run-to-run drift — decision is replayable and auditable after the fact |

---
### 1.3 Scale and When It Changes

The numbers the design is sized against: up to 50,000 enrollments a day, peaking around five requests a second. Within a
48-hour window the spatial index holds on the order of 100,000 points. The internal SLA for a decision is seconds; the
business window to act on a fraud ring is the 48 hours before the data expires from the index.


| Downstream Service | Median Latency | P99 Latency   | Availability Target | Impact if Synchronous                         |
|--------------------|----------------|---------------|---------------------|-----------------------------------------------|
| Account Service    | ~200 ms        | **>1,000 ms** | 99.9%               | Applicant timeout; enrollment dropped on blip |
| Fraud Detection    | Variable       | **>1,000 ms** | Best-effort         | Enrollment blocked during outage              |

The five-requests-a-second figure describes ingress volume only. Internal concurrency is driven by downstream tail
latency and fan-out to several checks per request, not by request rate. Where a decision would change at higher volume,
the relevant ADR records the trigger:

- Sustained load at or above 50 requests a second changes the timeout and scaling story (ADR-16).
- At roughly ten times peak, the single Redis instance moves to cluster mode (ADR-12).
- At or above 50 requests a second, lock contention on the correlation record needs revisiting (ADR-17).

---

## 2. Constraints

The drivers in §1 describe what the system must achieve; the constraints below describe what it must work within.

### 2.1 Technical & Infrastructure Constraints

**Local-first deployment.** The whole system must run on a single local machine in containers, which rules out
managed cloud services and forces portable open-source components — PostgreSQL, RabbitMQ, Redis.

**Current Java runtime.** The project commits to JDK 25, which constrains the concurrency model to virtual threads (
Project Loom) — well suited to the I/O-bound scatter-gather, avoiding one platform thread per in-flight request.

**Transient storage only.** Scoring runs statelessly: no fraud evidence is written to local disk, and any data needed for
correlation or aggregation is held only in transient, TTL-bound remote storage.

### 2.2 Regulatory & Compliance Constraints

**Data minimization.** GDPR forbids long-term retention of high-precision spatial data, so anything used for signal
derivation expires under an automatic, irreversible 48-hour policy — no permanent geographic map of users is created.

**Decision traceability.** GDPR Art. 22 requires that automated decisions affecting a person be reconstructable after
the fact — which request, which signals settled, and why the outcome was reached. Trace context propagates across every
service and message boundary, and the decision rationale is recorded alongside the outcome (§8.3)

**Identity isolation.** Spatial data and identity data are held in separate logical silos, and spatial signals use
anonymous identifiers, so a single-service compromise cannot correlate location back to a person.

**Delegated trust.** The hub cannot verify identity itself; it trusts only externally signed assertions — eIDAS or
payment JWTs — and authenticated sessions. If the trust chain breaks, intake halts rather than proceeding on unverified
input.

### 2.3 Security & Trust Boundary

**Delegated trust.** The hub does not authenticate users or verify identity itself. Authentication happens at the
gateway against the identity provider; the decision engine then admits a request to an enrollment route only if its
token carries the scope that route requires. A valid session with the wrong scope for its route is rejected before any
scoring begins (§8.1, ADR-03).

### 2.4 Operational & Business Constraints

**Asynchronous outcome.** The final outcome is delivered out of band, so the public API must not hold synchronous wait
loops.

**Resource efficiency.** Despite being distributed, the system must stay efficient at its baseline volume and must not
demand a disproportionate infrastructure footprint to run reliably.

---

## 3. Context & Scope

### 3.1 System Context (C4 Level 1)

![System Context — Enrollment Hub](./structurizr/images/EnrollmentHubLandscape.png "System Context — Enrollment Hub")

Five external parties interact with the hub. The registration frontend submits enrollment requests on behalf of an
applicant. An identity provider authenticates that applicant and issues the session token each request carries. Separate
verification services — a payment-check service, an eIDAS identity connector — issue the signed prerequisite tokens the
hub requires before any processing begins. Once a decision is reached, the Account Service consumes it and owns
everything that follows: provisioning, fulfilment, and the long-term enrollment record.

That last party also marks the scope boundary. The hub decides; it does not fulfil. It never touches raw card data, so
it stays outside PCI-DSS. It does not verify identity itself — it delegates that to the external services and trusts only
their signed assertions (§2.2). It does not own the enrollment record; that belongs to the Account Service (ADR-02).
What the hub owns is the decision and the short-lived state needed to reach it.

---

## 4. Solution Strategy

An assumed adversary is capable of:

- **Synthetic Identity Proliferation** — building many synthetic personas by faking what is cheap to vary (IP, device,
  email) and spreading genuine credentials (real payment instruments, verified identities) across them, so each
  enrollment passes per-request validation while the physical address — needed to receive mail and documents — stays
  invariant across the cluster
- **Single-Vector Evasion** — defeating any one detection layer by diversifying instruments or rotating fingerprints, so
  that uncorrelated checks each pass it in isolation.
- **Iterative Threshold Probing** — submitting repeatedly to identify the threshold a cluster can stay under.
- **Exploitation of Latency** — exploiting the latency window between enrollment and detection.

Each capability maps to an architectural safeguard:

| Adversary Capability                 | Architectural Safeguard                                                                                                                                                                                   | 
|--------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Synthetic Identity Proliferation** | **Geo-temporal density analysis:** per-request validation can't catch valid credentials reused across personas, but physical-address density over a short window does.                                    | 
| **Single-Vector Evasion**            | **Scatter-gather with durable correlation:** all applicable signals run concurrently and aggregate on one durable correlation record, defeating one check does not defeat the combined assessment.        | 
| **Iterative Threshold Probing**      | **Unconditional indexing:** every scored enrollment is indexed regardless of its outcome, so each probe raises the local density it is measured against; the atomic check closes the burst-timing bypass. | 
| **Exploitation of Latency**          | **Real-time scoring:** designed to reach a decision within 48 hours.                                                                                                                                      |

A Note on Why Geo-Scoring is needed:

Payment verification alone cannot catch synthetic identity proliferation, which is why a non-payment signal is needed.
AVS is primarily a North American/UK construct; most European issuers do not support it, so it cannot be relied on in the
European market — and where it does operate, it matches only at street and postcode level, too coarse to separate
adjacent addresses. SCA verifies the cardholder but does not prevent one fraudster from
using multiple legitimate instruments to build a cluster of synthetic accounts. Geo-temporal density is therefore the primary invariant signal in
the adjacent-address scenario.

---
