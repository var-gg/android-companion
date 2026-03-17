# Implementation Roadmap

## Phase 0 — foundation
- create Android app project
- establish docs index and core design docs
- define initial capability contract
- set up GitHub repository and Actions workflow

## Phase 1 — MVP runtime
- implement local JSON command executor
- implement required v0.1 capabilities
- verify local debug APK build
- document permissions and testing flow

## Phase 2 — release loop
- push repo public
- create GitHub tag release
- publish installable APK asset
- validate self-update check against release API

## Phase 3 — hardening
- extract capability handlers from `MainActivity`
- introduce structured result types / schemas
- tighten error-code conventions
- add automated tests for command routing

## Phase 4 — v0.2 candidates
- authenticated transport layer
- task queue / foreground service
- accessibility bridge with explicit trust tier
- notification or FCM-based remote triggers
