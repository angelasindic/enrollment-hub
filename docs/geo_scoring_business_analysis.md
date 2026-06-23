# Geo-Scoring for Enrollment Fraud Detection

## 1. Context

We are extending the enrollment application with a Geo-Scoring module providing more advanced fraud detection.

The Enrollment Hub is an asynchronous pipeline that connects the registration frontend with the downstream fulfillment.
It accepts enrollment requests durably, runs risk checks in parallel, and communicates decisions out of band. 

A Fraud Detection service is already included in the checks; it evaluates various data points but lacks a signal that
measures physical proximity to detect anomalous geo clustering of enrollments.
Geo-Scoring adds this kind of signal.

The hub's mechanics (messaging topology, durable correlation, fail-open logic, integration patterns) are covered in the
companion Architecture Document. This document is limited to the business case, rollout plan, and operational parameters
for Geo-Scoring itself.

---

## 2. The Business Problem

### 2.1 The Synthetic Identity Fraud Pattern

Any platform that requires a verified physical address at enrollment is vulnerable to fraud. Personas can be fabricated
from a mix of real and synthetic attributes, to be used to open legitimate-looking accounts.

Fraudsters use real addresses to receive verification materials, such as physical identity documents,
hardware tokens, or postal one-time codes. Nearby addresses streamline this process by allowing a single actor to manage
multiple locations centrally. This proximity enables them to run dozens of verified enrollments across a handful of
adjacent units, build synthetic transaction histories, and exploit the platform's trust before detection catches up.

Grouping these addresses by when and where they appear exposes the single, coordinated attack hiding behind
what look like separate accounts.

This fraud strategy fuels mass incentive abuse and erodes trust. It
also spikes the manual overhead of auditing enrollments that already cleared initial verification. Even worse, if a
fraud ring manages to unlock perks like net-30 invoicing, higher limits, or partner status, they’ve already secured
credit or access. The clawback process is costly and disruptive.

### 2.2 Why Existing Defenses Miss This Pattern

Two gaps let this pattern stay undetected, deferring effective countermeasures. 

The first is that payment verification confirms the cardholder, it doesn't check the coordination.

In European markets, Strong Customer Authentication (SCA) under PSD2 confirms the
cardholder. It says nothing about whether the registrant is a real business or one of dozens the same operator is running. 

Address Verification Service (AVS) helps even less here: it's mainly available in US, UK and Canada, and where it does run, it
matches at street-number and postcode granularity. That's far too coarse to tell apart adjacent addresses inside a
cluster. A fraudster with a valid card and an SCA-verified identity sails through with nothing in place to catch the clustering, 
even when nineteen other enrollments on nineteen other valid cards sit within fifty meters.

The second gap is that our existing fraud detection isn't geo-targeted. It pulls together digital signals (IP, device,
email patterns, velocity) and fails to treat geographic proximity as a major indicator. In dense city centers, where
legitimate businesses naturally pile up, that weighting dilutes the geo signal until genuine coordination gets
deprioritized or missed outright. A ring that varies its digital fingerprints but stays physically clustered slips past
these checks and reaches trust-gated features before broader behavioral signals ever fire.

### 2.3 Why Geography Is the Most Reliable Signal

Fraudsters study the systems they want to exploit, and they adapt cheaply. But not every attribute is equally cheap to
change, and that asymmetry is the whole reason geographic clustering holds up better than digital signals.

Text fields are the cheapest thing to vary. The fraudster types whatever they like, so a naive blacklist that flags "101
Main St" is beaten by "101 Main Streett" or "101 Main Street, Unit B". Digital fingerprints (IP, device, email domain)
cost a bit more, but proxy networks, device farms, and disposable email services make them cheap enough at scale.
Physical proximity is the expensive one. To vary it, the fraudster has to physically receive deliveries at each address
and sustain that across all of them. They can move addresses around a city, but only at
real operational cost, and never without leaving a coordination pattern behind.

Once a fraudster has diversified their digital fingerprints to beat the existing checks, physical proximity is what's left. 
And because flagging one address doesn't automatically clear the rest of the cluster, a geo-temporal layer keeps imposing cost on a ring even after part of it is discovered.

### 2.4 Why This Detection Must Be Asynchronous

Geocoding (turning an address into coordinates) and geo-indexing both add latency, and a synchronous enrollment flow
can't absorb that without pushing applicants to abandon the form. The hub's asynchronous evaluation window, which
already exists to tolerate downstream service latency (see the Architecture Document), is the one place we can slot
Geo-Scoring in without adding any delay the applicant can feel.

That's why the feature is tied to the hub specifically.
Async durability, the property that keeps the hub resilient to slow downstream services, makes a heavier detection layer a feasible addition.

---

## 3. The Opportunity

Treating geo-temporal clustering as a **primary signal**, rather than one weak input among many, closes the
adjacent-address gap. By looking at how densely enrollments pack into a small area over a short time window, and
calibrating that against what's normal locally, the platform can spot coordinated rings at enrollment instead of after
the chargebacks pile up.

### 3.1 Strategic Positioning: Shift Left

This pulls fraud detection *left* in the customer lifecycle: away from post-transaction chargeback analysis and broad
similarity scoring, toward geographic anomaly detection at the moment of enrollment. Catching a ring before incentives
go out, and before it clears the trust thresholds for elevated features, is cheaper than catching it afterward.

Geo-Scoring doesn't replace payment checks or the existing fraud service. It's an interpretable early-warning
layer added on top, with ROI you can measure: lower fraud losses and more efficient manual review.

### 3.2 The Core Hypothesis

The whole investment rests on one claim, and it's a falsifiable one: **fraud rings leave geo-temporal signatures
that legitimate enrollment patterns don't, even in address-dense environments**. If production data shows that
legitimate clusters in German, Dutch, and Austrian cities can't be told apart from synthetic ones, the hypothesis is
wrong. The rollout in §5 is built to find this out from real operational data rather than argument. It's also built to fail
safely: if the hypothesis does fail, it shows up as an inability to usefully tighten thresholds, not as harm to
customers.

### 3.3 What the Module Produces, and How the Decision Engine Uses It

Geo-Scoring outputs a geo density risk level per enrollment (LOW, MEDIUM, HIGH, or EXTREME), scored against the
local 48-hour enrollment density. It reaches the Decision Engine as an asynchronous event, arriving alongside the
existing fraud signal.

The Decision Engine treats that score asymmetrically by design.
The asymmetric aggregation is structural, it doesn't change between rollout phases, unlike thresholds which are adjustable.

The existing fraud signal owns the final outcome: APPROVED, CONDITIONAL_APPROVED, or REJECTED. 
A HIGH geo-score attaches a `cluster_review_required` flag and a `decision_reason`
annotation to the `EnrollmentDecisionEvent`, but it can't on its own turn an APPROVED into a REJECTED.
This is a general rule: Geo-Scoring can only escalate review routing; it never upgrades or downgrades the base fraud decision.

Downstream consumers of the event (fraud operations tooling, cluster-level review queues) use those annotations to route flagged
enrollments into review. The hub publishes what review routing needs; it doesn't own the review workflow.

The asymmetric aggregation is what makes the threshold-based rollout in §5 safe. The score's only operational lever is review
routing, so tuning thresholds changes how much the review queue fills up, not how often someone gets wrongly rejected.

---

## 4. Business Impact

| Risk Mitigated                                 | Current State                                                                                                             | With Geo-Scoring                                                                                               |
|------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| **Incentive abuse at scale**                   | Fraudsters create dozens of enrollments to harvest sign-up credits; detection is reactive, post-cash-out                  | Geographic clusters surfaced at enrollment, before incentives are distributed                                  |
| **Account farming for privilege escalation**   | Rings build synthetic history, then exploit trust-gated features (net-30 invoicing, elevated limits); caught after losses | Geographic clustering surfaces coordination before trust thresholds are reached                                |
| **Manual review without coordination context** | Analysts investigate enrollments individually, lacking context on coordinated operations                                  | Cluster-level review groups related enrollments, reducing per-enrollment review time and error rates           |
| **Resource reuse across enrollments**          | Fraudsters reuse addresses, devices, and verification materials with limited detection                                    | Geographic clustering links related enrollments; so a single flag jeopardizes the whole cluster, not just one  |

---

## 5. Rollout Strategy

Geo-Scoring rolls out in three phases, and the sequencing follows one rule: **commit to observability before committing
to response**. Each phase only promises what the evidence available at its start can back up. 
Phase 1 runs in production under cautious thresholds. Phase 2 recalibrates from what Phase 1 measured. Phase 3
watches for the conditions that would justify changing the detection later, without promising the changes
themselves up front.

### Phase 1 — Conservative Deployment

Geo-Scoring runs in production from day one, with the Decision Engine applying the asymmetric aggregation from §3.3.
Thresholds start deliberately high, set so that flagging is rare and dominated by clusters large enough that a
coordinated origin is the overwhelmingly likely explanation. The score still gets computed for every enrollment, so the
density data we need to test the core hypothesis (§3.2) and tune later thresholds starts accumulating immediately.

Why this shape: deployment state shouldn't be the thing that bounds operational risk; threshold choice should. Running
live from the start also means the density distributions and false-positive evidence come from real traffic, which is
the only thing later threshold decisions can honestly rest on.

Exit criteria: enough density data across the three target countries to support tightening decisions; a measured
false-positive rate from the small set of flagged enrollments; and either confidence that the core hypothesis holds or
evidence that it doesn't (see §7).

### Phase 2 — Calibrated Thresholds

Phase 2 tunes thresholds per region (urban versus rural baselines, calibrated separately for each target country) using
what Phase 1 collected. We tighten to whatever the data supports, accepting more flagging in exchange for catching more
real clusters. This is a config change, not a code change; the architecture doesn't move.

The payoff: cut the review burden per cluster, and surface a larger share of genuine coordinated activity. We'd expect a
moderate rise in flagged enrollments, concentrated where coordination is more probable, alongside falling per-case
review time as the analyst tooling matures.

Exit criteria: move to Phase 3 once thresholds have held stable for a longer period and adaptation monitoring is in place.

### Phase 3 — Adversarial Monitoring and Response Planning

Once calibrated thresholds have been live long enough that sophisticated rings might start adapting, Phase 3 commits to
**observability for signs of adaptation** rather than to specific countermeasures. The signals worth watching:
enrollment time-distributions per cluster (to catch slow-roll rings spreading activity past the 48-hour window),
geographic dispersion (rings spreading wider than the fixed-radius scan), and threshold-probing (clusters that
consistently park just under the flagging line).

It also commits to a **triggered planning cycle**. When the adaptation signals drift meaningfully, we kick off a
dedicated scoping and design effort built around the behavior we're seeing. The candidate countermeasures (
multi-scale temporal checks, macro-clustering, dynamic thresholds) wait for that cycle instead of being pre-designed
against guesswork.

The point: stay ready for the normal adversarial back-and-forth without sinking engineering effort into attacker
behaviors that may never show up. The 48-hour TTL (§6.2) and the fixed-radius scan are Phase 1 trade-offs; whether they
still make sense is exactly what the planning cycle decides if the monitoring triggers it.

---

## 6. Operational Posture

### 6.1 Fail-Open Continuity

Geo-Scoring is meant to augment the existing fraud layer, not gate it. If the Geo-Scoring service is down for any
reason (a geocoding API outage, a Redis partition, scoring-service degradation), the hub carries on with the existing
fraud signal alone and emits the decision with an `APPROVED_SCORE_MISSING` reason code. Enrollments decided without a
geo-score get flagged for cluster-level review, not rejected. 

The point of failing open is that a new detection layer shouldn't become a new single point of failure. The Architecture
Document covers the implementation and the fail-open timeout policy in §6.4.

### 6.2 The Dual Role of the 48-Hour TTL

The 48-hour TTL on the geo-index keeps the GDPR exposure low by making sure no persistent
geographic map of users is ever built - GDPR principle of data minimization. And it sets the window in which geo clustering can be
detected at all: coordinated enrollments spread over a longer period fall outside the detection scope by design. 
So the TTL is both a privacy commitment and a deliberate choice about detection scope, and Phase 3 monitoring (§5.3) is how
that choice gets revisited if the adversarial behavior we observe warrants it. The Architecture Document treats the TTL
as a Phase 1 trade-off, not a value set in stone.

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

**What's in scope.** The Geo-Scoring module inside the Enrollment Hub. That means the geo density algorithm, the
48-hour TTL geo-index, the integration with the Decision Engine using asynchronous events, and the three-phase rollout
from conservative deployment through adversarial monitoring.

**What's out of scope here.** The hub's architectural mechanics (messaging topology, durable correlation,
scatter-gather, prerequisite-token validation, fail-open policy) belong to the Architecture Document. The internal Fraud
Detection service's signal taxonomy belongs to the existing fraud stack that Geo-Scoring augments rather than replaces.
The downstream analyst review workflow (queue management, allowlist mechanics, analyst tooling) is owned by fraud
operations; the hub publishes the routing information review needs but doesn't run the workflow. For implementation
notes — what's fully built, what's a stub, and how a real production deployment would differ — see the project README.

**Deferred on purpose, not by phase.** Rejecting an enrollment on the geo-score alone. Geo-Scoring only ever contributes
to flagging and review routing, and that's a structural cxonsequence of the asymmetric aggregation rule, not a limitation
we plan to lift later. Specific adversarial countermeasures (multi-scale temporal and geo detection) are held back
for the triggered planning cycle in §5.3, to be designed against behavior we've observed rather than behavior we've
guessed at.

---

## 9. References

- **Project README:** Overall project narrative, portfolio implementation notes, tech stack, and running instructions.
  Entry point for the repository.
- **Architecture Document:** *Enrollment Hub — Event-Driven Async Pipeline*. Specifically: durable correlation record (
  §5.3, §8.6), scatter-gather topology (§6), fail-open logic (§6.4), 48-hour TTL privacy strategy (§8.2),
  atomic geo-index operations, entry-point durability and causal ordering (§8.6).
