# Architecture

## Top-level shape

```text
Desktop/Server Agent
  -> prepares JSON action
  -> decides policy / business logic

Android Companion App
  -> receives JSON action
  -> routes to capability handler
  -> invokes Android APIs / intents / package manager
  -> returns structured JSON result

Android OS
  -> remains final permission / confirmation boundary
```

## v0.1 implementation shape
- `MainActivity` hosts a testing UI
- command router dispatches actions
- capability logic currently lives in a single Kotlin file for speed
- GitHub Releases act as the update source of truth

## Desired next modular shape
- `capabilities/device`
- `capabilities/apps`
- `capabilities/intents`
- `capabilities/update`
- `transport/` for future ingress
- `contract/` for JSON schemas and type-safe result models

## Why thin-runtime matters
If the device app starts owning planning logic, workflow branching, or domain-specific behavior, it becomes harder to evolve, audit, and secure.
The Android app should stay close to the OS boundary and expose reusable primitives upward.
