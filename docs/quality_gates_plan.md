# Quality Gates Plan

The pipeline checks that exist today, the structure they follow, and the checks planned next. Status
as of 2026-11-16.

## Current state

| Workflow or action | Purpose |
|---|---|
| `.github/workflows/ci.yml` | Orchestrator. Triggers, concurrency, permissions and the job graph |
| `.github/workflows/changes.yml` | Reusable workflow. Resolves the changed Maven modules from path filters and returns them as `list` |
| `.github/actions/setup-java-maven` | Composite action. Temurin JDK with the Maven dependency cache |
| `.github/actions/publish-test-reports` | Composite action. Publishes the Surefire and Failsafe reports as check runs |

Jobs in `ci.yml`:

- `changes` calls `changes.yml`.
- `build-test` runs `./mvnw -pl <list> -am verify`. Unit tests and integration tests run in one
  reactor invocation, so no `install` into the runner's local repository is needed.
- `static-analysis` runs `./mvnw -pl <list> -am process-test-classes -Pquality`.

The `quality` profile in the root `pom.xml` binds Checkstyle to `validate` and PMD to
`process-test-classes`. Plugin versions and configuration sit in `<build>`; the profile only adds the
executions. `./mvnw verify` without the profile is unchanged, and `./mvnw verify -Pquality` runs
everything locally.

Rules live in `config/checkstyle.xml` and `config/pmd-ruleset.xml`. Both cover correctness and
maintainability and do not enforce formatting. Checkstyle's `sun_checks.xml` reports 5,214 findings on
the current code and `google_checks.xml` 7,298, mostly line length, indentation (the code uses four
spaces, Google style two) and Javadoc. PMD's `quickstart.xml` reports 371, 307 of them for test method
names. None of the three serves as a gate without a larger rewrite of the code or of the rule set.

### Resolved findings

The 35 findings present when the rules were introduced (20 Checkstyle, 15 PMD) were fixed on
2026-11-16. `./mvnw verify -Pquality` passes, including the integration tests.

Two of them were more than style. The `switch` statements in `DecisionEngine.aggregate` over
`GateClassification` and in `CheckRequestPublisher.dispatch` over `SignalConfig` had no `default`. A
`switch` statement with arrow cases over an enum is not checked for exhaustiveness by the compiler. A
constant added later would have been skipped silently: in the aggregation, a failed signal of a new
classification would not reject the enrollment. Both switches stay without a `default`. PMD's
`NonExhaustiveSwitch` reports a missing constant in the analysis step, which was confirmed by removing
a case. PMD's `ExhaustiveSwitchHasDefault` rejects a `default` on an exhaustive switch, so Checkstyle's
`MissingSwitchDefault` is not enabled.

`PrerequisiteTokenValidator` now attaches the `JwtException` to the `PrerequisiteValidationException`
and logs its message. The cause is not part of the 403 response.

### Known limitation

Maven skips every module that depends on a failed module, and nearly all modules depend on
`contracts`. `--fail-at-end` therefore reports the findings of the first failing module only. The full
list comes from `./mvnw checkstyle:checkstyle pmd:pmd`, then
`*/target/checkstyle-result.xml` and `*/target/pmd.xml`.

## Pipeline structure

Checks run in stages. A stage starts only when the cheaper stage before it passed.

1. `changes` resolves the modules.
2. `build-test` and `static-analysis` run in parallel. Each job runs on its own runner and repeats the
   checkout and Java setup, about 30 to 60 seconds with a warm cache.
3. A summary job `ci-ok` is the only required check in branch protection. Path filters skip jobs, and
   a skipped required check blocks a merge. `ci-ok` fails on `failure` or `cancelled` in `needs` and
   accepts `skipped`.

Splitting integration tests into a job behind the unit tests saves their cost when unit tests fail. It
requires a second compilation or an artifact transfer, because a fresh runner has neither `target/`
nor the sibling modules. It is deferred until the integration tests are slow enough to justify that.

Reusable workflows hold whole jobs with their own runner. Composite actions hold step sequences that
several jobs repeat. Slow checks (mutation tests, full vulnerability scans, load tests) belong in a
scheduled `nightly.yml`, not in the pull request path.

## Planned checks

Ordered by value for this repository.

| # | Check | Scope | Prerequisite |
|---|---|---|---|
| 1 | Add `ci-ok` and switch branch protection to it | Required check no longer names individual jobs | None |
| 2 | CodeQL | Standard `github/codeql-action` workflow with its own triggers and schedule; results in the Security tab | None |
| 3 | `dependency-review-action` | Pull requests only; blocks vulnerable or disallowed-licence dependencies. Dependabot is already configured | None |
| 4 | JaCoCo | Coverage report per module with a threshold; summary in `$GITHUB_STEP_SUMMARY` | Threshold set from the measured baseline |
| 5 | Compose smoke test | `docker compose up`, wait for the health checks, run `scripts/e2e-happy-flow.sh` | The script runs unattended and returns a non-zero exit code on failure; to be confirmed |
| 6 | `actionlint` job and zizmor | Workflow syntax and workflow security (script injection, token permissions); triggered by changes under `.github/**` | None |
| 7 | OpenSSF Scorecard | Repository hygiene score and badge | Public repository |
| 8 | SpotBugs | Bytecode analysis; extends the `quality` profile or runs as a job of its own | Compiled classes, as for PMD |
| 9 | Markdown link check (`lychee`) | `README.md` and `docs/` | None |
| 10 | Image build, Trivy scan, SBOM, provenance | Container images pushed to GHCR | Image definition (see below) and a deployment target |
| 11 | Nightly: PIT mutation tests, full dependency scan, load test | `nightly.yml` | Load-test scripts do not exist yet |

### Notes on individual checks

- **Compose smoke test.** It is the only check that exercises the services together. The unit and
  integration tests cover each service on its own. `docker-compose.yml` defines health checks, which
  allow waiting on readiness without fixed sleeps.
- **Image build.** The repository has no Dockerfile. Each service module applies
  `spring-boot-maven-plugin`, so `spring-boot:build-image` (Cloud Native Buildpacks) is an option that
  needs no Dockerfile. This is an open design choice, not a decision.
- **Kubernetes manifests.** `deploy/k8s` is not in the repository. Manifest validation (`kubeconform`)
  follows the manifests.
- **SonarCloud** would overlap with Checkstyle, PMD, SpotBugs and JaCoCo and is not planned.
- **Permissions.** Each job and each reusable workflow declares the minimum. `checks: write` belongs to
  the test-report job and `security-events: write` to CodeQL, not to the workflow level.

## Open decisions

1. Whether Checkstyle should adopt a standard rule set. Adopting `google_checks.xml` requires
   reformatting the code or overriding indentation and line length, which leaves little of the standard
   set.
2. How images are built (buildpacks or Dockerfile), once a deployment target exists.
