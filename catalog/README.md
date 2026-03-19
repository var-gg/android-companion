# Android Capability Registry (v0.1 scaffold)

This directory is the canonical source-of-truth for app capability registry data.

## Purpose

The registry is not just a deep-link list.
It is a capability-oriented catalog that stores:
- app identity
- capability meaning
- invocation surfaces (scheme / app link / intent / web)
- verification evidence
- runtime confidence and freshness
- negative knowledge (partial / broken / stale / unknown semantics)

## Design rules

- Canonical edit format: YAML in `catalog/apps/**`
- Generated machine-consumption format: JSON in `docs/catalog/**` (later step)
- `package_name` is the primary app key
- `signing_cert_sha256` is a stronger identity field when known
- Capability semantics must remain conservative early on
- `observed`, `resolves`, and `verified` must not be treated as the same thing
- Prefer deep, end-to-end app profiles over shallow broad coverage across many apps
- Records should distinguish documented facts, runtime-verified facts, and still-open questions
- Human-readable fields should support at least Korean and English when practical, while technical keys stay language-neutral

## v0.1 goals

- establish schema and folder structure
- validate a few real sample records
- support unknown / partial / broken states
- prepare for issue-form based intake
- keep Android companion runtime separate from registry data
- land real first-party samples such as NAVER Map with official URL Scheme evidence

## Not in scope yet

- public catalog site generation
- app-side registry browsing UI
- automatic store version freshness sync
- large-scale community contribution workflow
