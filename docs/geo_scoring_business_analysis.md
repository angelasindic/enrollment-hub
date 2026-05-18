# Geo-Scoring for Enrollment Fraud Detection

## 1. Context

The organization is introducing a **Geo-Scoring module** into the Enrollment Hub, the asynchronous, event-driven pipeline that mediates between the registration frontend and downstream fulfillment services. The hub accepts enrollment requests durably, orchestrates parallel risk evaluation, and delivers decisions out-of-band. Within this pipeline, the existing Fraud Detection service evaluates payment instruments and digital fingerprints. The Geo-Scoring module adds a **physical-proximity signal** — detecting anomalous spatial clustering of enrollments — that the current fraud layer cannot see.

The technical architecture of the hub (messaging topology, durable correlation, fail-open logic, integration patterns) is specified in the companion **Architecture Document**. This analysis is restricted to the business justification, rollout, and operational parameters for the Geo-Scoring module itself.

---

## 2. The Business Problem

### 2.1 The Synthetic Identity Fraud Pattern

Platforms requiring verified physical addresses at enrollment face a specific, costly vulnerability: **synthetic identity fraud (SIF)**, in which fabricated personas are constructed from real and synthetic attribute combinations and used to enroll legitimate-looking accounts. Because SIF requires physical addresses to receive verification materials — postal PIN codes, identity documents, hardware tokens — fraudsters must occupy real geographic space, and they do so by distributing registrations across adjacent addresses they can manage centrally. A single actor can maintain dozens of verified enrollments from a cluster of nearby buildings or units, build synthetic transaction history, and exploit platform trust before detection catches up.

Clustering these addresses is the indicator that exposes seemingly separate identities as a coordinated ring. The financial impact extends well beyond direct fraud loss to chargebacks, sign-up incentive abuse at scale, eroded trust metrics, and the operational cost of manual reviews on enrollments that already passed initial verification. By the time a ring exploits trust-gated features — net-30 invoicing, elevated transaction limits, partner-tier access — the platform has already extended credit or privilege that is expensive to claw back.

### 2.2 Why Existing Defenses Miss This Pattern

Two gaps in the current defense stack leave this pattern visible only after losses occur.

**Payment verification is insufficient.** In European markets, Strong Customer Authentication (SCA) under PSD2 verifies the cardholder, not whether the registrant represents a legitimate business or is part of a coordinated ring. Address Verification Service (AVS) is primarily a US/UK/Canada construct and, where available, operates at street-number and postcode granularity — too coarse to distinguish adjacent addresses within a cluster. A fraudster using a valid payment instrument and a verified-via-SCA cardholder identity passes both checks, even when nineteen other enrollments using nineteen other valid instruments cluster within fifty meters.

**Existing fraud detection is not geo-targeted.** The current internal fraud service aggregates digital signals — IP, device, email patterns, velocity — and weights geographic proximity as one input among many. In dense urban areas where legitimate businesses naturally cluster, this dilution means geo-anomalies indicating coordinated fraud are deprioritized or missed entirely. Fraudsters who diversify their digital fingerprints but remain physically clustered pass these checks and exploit trust-gated features before broader behavioral signals trigger.

### 2.3 Why Geography Is the Invariant Signal

Fraudsters understand current detection systems and adapt cheaply. Different attributes carry different costs to vary, and this cost asymmetry is what makes geographic clustering a more durable signal than digital ones.

Text fields are the cheapest attribute to vary: a fraudster types whatever they want, so simple text-match filters that flag "101 Main St" against a blacklist are defeated by "101 Main Streett" or "101 Main Street, Unit B". Digital fingerprints — IP, device, email domain — are moderately cheap to vary at scale through proxy networks, device farms, and disposable email services. Physical proximity, however, is *structurally* expensive to vary: the fraudster must actually receive postal mail at each address, take delivery of hardware tokens, and manage the operation logistically. The fraudster can move addresses across a city, but only at substantial operational cost and not without leaving a coordination pattern.

When digital fingerprints are diversified to defeat existing checks, physical proximity becomes the invariant that remains — and the fact that one address being flagged does not automatically protect the rest of the cluster means a geo-temporal layer also imposes ongoing cost on operating a discovered ring.

### 2.4 Why This Detection Must Be Asynchronous

Geocoding (address-to-coordinates resolution) and spatial indexing introduce latency that a synchronous enrollment flow cannot absorb without driving applicant abandonment. The Enrollment Hub's asynchronous evaluation window — already established for downstream service latency reasons documented in the Architecture Document — is the only place geo-scoring can be inserted without introducing new applicant-visible delay. This is why the geo-scoring feature is structurally tied to the hub: the same architectural property (async durability) that makes the hub resilient to downstream slowness also makes it the only viable host for a non-trivial new detection layer.

---

## 3. The Opportunity

A detection layer that treats **geo-temporal clustering as a primary signal** — using non-payment, non-digital data to establish risk — closes the adjacent-address gap. By analyzing the spatial density of enrollments within short time windows, calibrated against local legitimate baselines, the platform can identify coordinated fraud rings at the enrollment stage rather than after chargebacks accumulate.

### 3.1 Strategic Positioning: Shift Left

This approach shifts fraud detection *left* in the customer lifecycle: from post-transaction chargeback analysis and broad similarity scoring to enrollment-time geographic anomaly detection. Catching a fraud ring at enrollment, before incentives are distributed and before trust thresholds for elevated features are reached, is structurally cheaper than catching it after losses occur. The geo-scoring layer is not a replacement for payment checks or the existing fraud service — it is a targeted, interpretable early-warning addition to the anti-fraud stack with measurable ROI in reduced fraud losses and improved manual-review efficiency.

### 3.2 The Core Hypothesis

The entire investment rests on a single falsifiable claim: **fraud rings exhibit spatial-temporal signatures that deviate from legitimate enrollment patterns even in address-dense environments**. If real production data shows that legitimate clusters in DE/NL/AT cities are statistically indistinguishable from synthetic ones, the hypothesis fails. The rollout described in §5 is designed so that this can be discovered through operational data rather than speculation, and so that hypothesis failure manifests as inability to tighten thresholds usefully rather than as customer harm.

### 3.3 What the Module Produces, and How the Decision Engine Uses It

The Geo-Scoring module produces a spatial density risk level (LOW / MEDIUM / HIGH) per enrollment, calculated against the local 48-hour enrollment density. The score is delivered to the Decision Engine as an asynchronous event alongside the existing fraud signal.

The Decision Engine's treatment of the score is **asymmetric by design**, and this asymmetry is a permanent property of the system rather than a phase-specific behavior. The existing fraud signal drives the terminal outcome (APPROVED, WARNING, or REJECTED). A HIGH geo-score adds a `cluster_review_required` flag and a `decision_reason` annotation to the `AccountDecision` event but cannot by itself escalate an APPROVED outcome into a REJECTED one. Downstream consumers of the decision event — fraud operations tooling, analyst review queues — route flagged enrollments into review based on these annotations. The Enrollment Hub publishes the information needed for review routing but does not own the review workflow itself.

This asymmetry is what makes the threshold-based rollout described in §5 safe: the score's only operational effect is review routing, so threshold tuning controls the rate of review-queue inflation rather than the rate of wrongful rejection.

---

## 4. Business Impact

| Risk Mitigated                               | Current State                                                                                                            | With Geo-Scoring                                                                                                                          |
|----------------------------------------------|--------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **Incentive abuse at scale**                 | Fraudsters create dozens of enrollments to harvest sign-up credits; detection is reactive, post-cash-out                 | Clusters surfaced at enrollment, before incentives are distributed                                                                        |
| **Account farming for privilege escalation** | Rings build synthetic history, then exploit trust-gated features (net-30 invoicing, elevated limits); caught after losses | Geographic concentration surfaces coordination before trust thresholds are reached                                                        |
| **Manual review efficiency**                 | Analysts investigate enrollments individually, lacking context on coordinated operations                                 | Cluster-level review groups related enrollments, reducing per-enrollment review time and error rates                                      |
| **Resource invalidation**                    | Fraudsters reuse addresses, devices, and verification materials with limited detection                                   | Geographic clustering links related enrollments; one address flagged exposes the rest of the cluster, raising the operating cost of fraud |

---

## 5. Rollout Strategy

Geo-scoring is rolled out in three phases. The structure follows a principle worth naming explicitly: **commit to observability before committing to response**. Each phase commits only to what can be defended with evidence available at the time the phase starts. The first phase commits to operational deployment under conservative thresholds; the second commits to calibration based on data gathered during the first; the third commits to monitoring the conditions that would justify future detection changes, without pre-committing to the changes themselves.

### Phase 1 — Conservative Deployment

Geo-Scoring is deployed operationally from day one, with the Decision Engine using the score according to the asymmetric aggregation described in §3.3. **Thresholds are set conservatively** — high enough that flagging is rare and dominated by clusters whose size makes coordinated origin disproportionately likely. The score is computed for every enrollment, so the density-distribution data needed to validate the core hypothesis (§3.2) and tune future thresholds accumulates from the first day of operation.

**Business driver.** Deploy the feature with operational risk bounded by threshold choice rather than by deployment state. Begin gathering real density distributions and false-positive evidence from production traffic, against which subsequent threshold decisions can be made.

**Exit criteria.** Sufficient density-distribution data across the three target countries to support threshold-tightening decisions; a measured false-positive rate from the small set of flagged enrollments; confidence that the core hypothesis is supported (or, conversely, evidence that it is not — see §7).

### Phase 2 — Calibrated Thresholds

Per-region threshold tuning (urban versus rural baselines, with separate calibration per target country) refines the signal based on Phase 1 evidence. Thresholds are tightened to the level supported by observed data, accepting a higher flagging rate in exchange for catching more genuine clusters. The change is a configuration update rather than a code change; the architecture is invariant across this transition.

**Business driver.** Reduce manual review burden per cluster and increase the proportion of real coordinated activity that's surfaced. The expected outcome is a moderate increase in flagged enrollments concentrated in cases where coordination is more probable, paired with a reduction in per-case review time as the analyst tooling matures.

### Phase 3 — Adversarial Monitoring and Response Planning

Once the geo-scoring layer has been operating with calibrated thresholds for long enough that sophisticated rings might begin to adapt, the third phase commits to **observability for adaptation indicators** rather than to specific countermeasures. The relevant indicators include enrollment time-distributions per cluster (to detect slow-roll behavior, where rings spread enrollments over longer windows than the 48-hour detection scope), geographic dispersion patterns (to detect spreading across larger areas than the fixed-radius scan), and threshold-probing behavior (clusters that consistently sit just below the flagging threshold).

The phase also commits to a **triggered planning cycle**: when adaptation indicators show meaningful drift, a dedicated scoping and design effort is initiated to design the response against the actually observed behavior. Specific countermeasures — multi-scale temporal checks, macro-clustering, dynamic threshold adjustment — are deferred to that planning cycle rather than pre-designed against speculation.

**Business driver.** Stay ready for the natural adversarial cycle without over-committing engineering work to speculative attacker behaviors. The 48-hour TTL (see §6.2) and the fixed-radius detection are Phase 1 trade-offs; whether they remain appropriate is a question the planning cycle will answer if observability indicators trigger it.

---

## 6. Operational Posture

### 6.1 Fail-Open Continuity

The Geo-Scoring module is designed to augment, not gate, the existing fraud detection layer. If the geo-scoring service is temporarily unavailable — geocoding API outage, Redis partition, scoring service degradation — the Enrollment Hub proceeds with the existing fraud signal alone and emits the decision with an `APPROVED_SCORE_MISSING` reason code. Enrollments decided without a geo-score are flagged for operational review rather than rejected.

This fail-open posture ensures that adding a new detection layer does not introduce a new single point of failure. The Architecture Document specifies the implementation in §6.4 and the timeout policy in ADR-010.

### 6.2 The Dual Role of the 48-Hour TTL

The 48-hour TTL on the geo-index serves two purposes that the architecture and business commitments share. It minimizes GDPR exposure by ensuring no persistent geographic map of users is built (data minimization). It also defines the time window within which spatial clustering can be detected — coordinated enrollments spread over longer windows fall outside the detection scope by design. The TTL is therefore both a privacy commitment and a detection-scope choice, and Phase 3 monitoring (§5.3) is the mechanism by which the choice gets revisited if observed adversarial behavior warrants it. The architectural decision (ADR-004) acknowledges the TTL as a deliberate Phase 1 trade-off rather than a permanent value.

---

## 7. Business Risks & Mitigations

| Risk                            | Business Impact                                                                                                 | Mitigation                                                                                                                                                                                                      |
|---------------------------------|-----------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Dense urban false positives** | Legitimate businesses in city centers trigger cluster alerts, inflating review queues                           | Conservative Phase 1 thresholds; per-region tuning in Phase 2; 48-hour TTL prevents long-term accumulation                                                                                                      |
| **Core hypothesis failure**     | Phase 1 evidence shows legitimate and fraudulent clusters are not statistically separable in target geographies | Manifests as inability to tighten thresholds without unacceptable false-positive rates; feature effectively retires by leaving thresholds at conservative settings; no customer-facing harm in the failure mode |
| **Adversarial adaptation**      | Rings adapt to evade fixed-radius, 48-hour detection through slow-roll or dispersion                            | Phase 3 observability for adaptation indicators; triggered planning cycle when drift is observed; countermeasure design deferred to evidence rather than committed speculatively                                |
| **Geocoding capacity**          | Self-hosted Nominatim may bottleneck at peak volume                                                             | Geocoding cache (Architecture Document §10.2); horizontal scaling if hit rate drops below threshold                                                                                                             |

---

## 8. Scope Boundary

**In scope.** The Geo-Scoring module within the Enrollment Hub. The spatial density algorithm, 48-hour TTL geo-index, integration with the Decision Engine via the asynchronous event bus, and the three-phase rollout from conservative deployment through adversarial monitoring.

**Outside this document's scope.** The hub's architectural mechanics — messaging topology, durable correlation, scatter-gather pattern, prerequisite-token validation, and fail-open policy — are specified in the companion Architecture Document. The Internal Fraud Detection service's signal taxonomy belongs to the pre-existing fraud stack that geo-scoring augments rather than replaces. The downstream analyst review workflow — queue management, allowlist mechanics, analyst tooling — is an operational concern owned by the fraud operations function; the hub publishes the routing information needed for review but does not own the workflow. For project implementation notes — what is fully implemented, what runs as a stub, and how the system would differ in a production deployment — see the project README.

**Deferred by permanent design.** Automated enrollment rejection driven by geo-score alone: geo-scoring contributes to flagging and review routing only, as a structural property of the asymmetric aggregation rule rather than a phased commitment. Specific adversarial countermeasures — multi-scale temporal and spatial detection — are reserved for the triggered planning cycle described in §5.3, to be designed against observed behaviour rather than predicted behaviour.

---

## 9. References

- **Project README:** Overall project narrative, portfolio implementation notes, tech stack, and running instructions. Entry point for the repository.
- **Architecture Document:** *Enrollment Hub — Event-Driven Async Pipeline*. Specifically: durable correlation record (§5.3, §8.6), scatter-gather topology (§6), fail-open logic (§6.4, ADR-010), 48-hour TTL privacy strategy (§8.2, ADR-004), atomic geo-index operations (ADR-014), entry-point durability and causal ordering (§8.6, ADR-003).
 