# Security Policy

Report security findings privately through GitHub's advisory workflow,
<https://github.com/angelasindic/enrollment-hub/security/advisories/new>,
rather than as a public issue.

## Scope

The code in this repository, on `main`.

The Docker Compose stack and the `application.yml` defaults describe a local
development topology, not a deployment. Findings that depend on that topology
being reachable from outside a trusted network are out of scope.

What the design enforces, where, and how each control is tested is in
[docs/security-controls.md](docs/security-controls.md). Its Status column
lists the known limits, among them no rate limiting, an unauthenticated
internal broker path, per-start signing keys, and the fraud-detection stub.
Those are documented decisions, not findings. A report that reaches one of
them from the user-facing path, or gets past a control the map marks as
implemented, is in scope.
