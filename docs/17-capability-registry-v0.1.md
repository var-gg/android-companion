# Capability Registry v0.1 Scaffold

This document introduces the first repository scaffold for an Android capability registry.

## Why this exists

The companion runtime should stay thin.
Registry knowledge about app capabilities, invocation surfaces, and verification evidence should live outside the Android executor itself.

## Scope of the scaffold

This first step adds:
- canonical `catalog/` source structure
- draft JSON schemas
- sample app and capability records
- intake issue forms
- placeholder validation/build scripts

It does **not** yet add:
- a public generated catalog site
- automatic freshness checks
- app-side browsing UI for registry entries

## App-side listing: current recommendation

Do not make the registry a first-class in-app browsing surface yet.

Recommended posture for now:
- keep registry canonical data in the repo
- let the Android app remain a runtime/executor
- revisit app-side listing later as a **read-only operator aid** once taxonomy and data quality stabilize

Why:
- semantics are still evolving
- confidence/freshness rules are not final
- exposing unstable registry entries too early risks making the app look like a noisy experimental browser

## When app-side listing becomes reasonable

Consider a read-only in-app registry view later if all of the following become true:
- schema v0.1 has survived several real apps
- confidence and freshness rules are stable
- generated JSON read model exists
- the UI is clearly operator-facing, not consumer-facing

If added later, it should be:
- read-only
- clearly marked with confidence/freshness
- separated from primary execution controls
