# Docs Index

This directory contains the public product and engineering design documents for Android Companion.

## Positioning in one page

Android Companion is building:
- a **personal Android-side runtime / executor**,
- with a **JSON-first capability contract**,
- while keeping orchestration and decision logic on desktop/server agents.

The app is intentionally thin.
It should remain understandable as a device capability surface, not drift into a giant all-in-one automation product.

## Recommended reading order

1. [00-overview.md](./00-overview.md)
2. [01-product-principles.md](./01-product-principles.md)
3. [03-scope-and-non-goals.md](./03-scope-and-non-goals.md)
4. [05-architecture.md](./05-architecture.md)
5. [06-capability-contract.md](./06-capability-contract.md)
6. [07-permissions-and-trust-boundaries.md](./07-permissions-and-trust-boundaries.md)
7. [10-v0.1-prd.md](./10-v0.1-prd.md)
8. [11-implementation-roadmap.md](./11-implementation-roadmap.md)
9. [12-backlog.md](./12-backlog.md)
10. [13-remote-transport-alpha.md](./13-remote-transport-alpha.md)
11. [14-pairing-over-tailscale.md](./14-pairing-over-tailscale.md)

## Reading layers

### If you want the product framing
Read:
1. [00-overview.md](./00-overview.md)
2. [01-product-principles.md](./01-product-principles.md)
3. [02-user-journey.md](./02-user-journey.md)
4. [03-scope-and-non-goals.md](./03-scope-and-non-goals.md)

### If you want the technical contract
Read:
1. [05-architecture.md](./05-architecture.md)
2. [06-capability-contract.md](./06-capability-contract.md)
3. [08-update-and-release-model.md](./08-update-and-release-model.md)
4. [09-module-boundaries.md](./09-module-boundaries.md)
5. [13-remote-transport-alpha.md](./13-remote-transport-alpha.md)
6. [14-pairing-over-tailscale.md](./14-pairing-over-tailscale.md)

### If you want trust, permissions, and risk
Read:
1. [04-risk-and-safety.md](./04-risk-and-safety.md)
2. [07-permissions-and-trust-boundaries.md](./07-permissions-and-trust-boundaries.md)

### If you want execution planning
Read:
1. [10-v0.1-prd.md](./10-v0.1-prd.md)
2. [11-implementation-roadmap.md](./11-implementation-roadmap.md)
3. [12-backlog.md](./12-backlog.md)

## Document map

### Product foundation
- [00-overview.md](./00-overview.md) — high-level product definition
- [01-product-principles.md](./01-product-principles.md) — guiding principles
- [02-user-journey.md](./02-user-journey.md) — end-user and operator journey
- [03-scope-and-non-goals.md](./03-scope-and-non-goals.md) — v0.1 boundaries

### Risk, trust, and permissions
- [04-risk-and-safety.md](./04-risk-and-safety.md) — risk posture and safeguards
- [07-permissions-and-trust-boundaries.md](./07-permissions-and-trust-boundaries.md) — Android permission and consent boundaries

### Architecture and contracts
- [05-architecture.md](./05-architecture.md) — top-level system shape
- [06-capability-contract.md](./06-capability-contract.md) — JSON action and response contract
- [08-update-and-release-model.md](./08-update-and-release-model.md) — self-update and GitHub release strategy
- [09-module-boundaries.md](./09-module-boundaries.md) — future extraction boundaries for capabilities and transport

### Delivery and planning
- [10-v0.1-prd.md](./10-v0.1-prd.md) — MVP product requirements
- [11-implementation-roadmap.md](./11-implementation-roadmap.md) — phased build path
- [12-backlog.md](./12-backlog.md) — near-term open work
- [13-remote-transport-alpha.md](./13-remote-transport-alpha.md) — polling-based remote control alpha contract
- [14-pairing-over-tailscale.md](./14-pairing-over-tailscale.md) — Tailscale-first pairing bootstrap and acpair link contract
- [17-capability-registry-v0.1.md](./17-capability-registry-v0.1.md) — capability registry scaffold and operating model

## Reading philosophy
If you only read one product document, read [00-overview.md](./00-overview.md).
If you only read one technical contract document, read [06-capability-contract.md](./06-capability-contract.md).
If you only read one execution document, read [10-v0.1-prd.md](./10-v0.1-prd.md).
