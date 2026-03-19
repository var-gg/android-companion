# NAVER Map remote intent smoke test — 2026-03-19

## Context
- Companion app executes commands through `RemotePollingService` -> `RemoteTransportClient` -> `AndroidCapabilityEngine.execute()`.
- Bridge endpoint observed live at `http://127.0.0.1:8787`.
- Registered device id: `android-861c75dd-6dad-4ae0-9a38-6345d43fc210`.

## Device sample
- manufacturer: samsung
- model: SM-S928N
- android: 14
- api level: 34
- locale: ko-KR
- companion version: 0.2.0-alpha10
- NAVER Map version: 6.4.0.7 (versionCode 60400006)

## Test 1 — search-query
Request id: `nmap-search-20260319-01`

Enqueued action:
```json
{
  "device_id": "android-861c75dd-6dad-4ae0-9a38-6345d43fc210",
  "request_id": "nmap-search-20260319-01",
  "action": "open_intent",
  "params": {
    "action": "android.intent.action.VIEW",
    "uri": "nmap://search?query=%EA%B0%95%EB%82%A8%EC%97%AD&appname=ai.openclaw.androidcompanion",
    "package": "com.nhn.android.nmap"
  }
}
```

Observed machine-readable result:
```json
{
  "ok": true,
  "action": "open_intent",
  "request_id": "nmap-search-20260319-01",
  "intent_action": "android.intent.action.VIEW",
  "data": "nmap://search?query=%EA%B0%95%EB%82%A8%EC%97%AD&appname=ai.openclaw.androidcompanion",
  "package": "com.nhn.android.nmap"
}
```

Interpretation:
- Companion successfully received and executed the intent.
- Package-pinned NAVER Map deep link resolution worked.
- Final landing screen still needs human visual confirmation.

## Test 2 — route-transit
Request id: `nmap-route-transit-20260319-01`

Enqueued action:
```json
{
  "device_id": "android-861c75dd-6dad-4ae0-9a38-6345d43fc210",
  "request_id": "nmap-route-transit-20260319-01",
  "action": "open_intent",
  "params": {
    "action": "android.intent.action.VIEW",
    "uri": "nmap://route/public?slat=37.570377&slng=126.982204&sname=%EC%A2%85%EA%B0%81%EC%97%AD&dlat=37.497175&dlng=127.027926&dname=%EA%B0%95%EB%82%A8%EC%97%AD&appname=ai.openclaw.androidcompanion",
    "package": "com.nhn.android.nmap"
  }
}
```

Observed machine-readable result:
```json
{
  "ok": true,
  "action": "open_intent",
  "request_id": "nmap-route-transit-20260319-01",
  "intent_action": "android.intent.action.VIEW",
  "data": "nmap://route/public?slat=37.570377&slng=126.982204&sname=%EC%A2%85%EA%B0%81%EC%97%AD&dlat=37.497175&dlng=127.027926&dname=%EA%B0%95%EB%82%A8%EC%97%AD&appname=ai.openclaw.androidcompanion",
  "package": "com.nhn.android.nmap"
}
```

Interpretation:
- Companion successfully received and executed the transit route intent.
- Package-pinned NAVER Map deep link resolution worked.
- Final route landing still needs human visual confirmation.

## Current conclusion
This is no longer just a documentation-only capability.

Confirmed facts:
1. The Android companion can execute package-pinned NAVER Map intents through the remote polling bridge.
2. `nmap://search?...` resolves through `open_intent`.
3. `nmap://route/public?...` resolves through `open_intent`.

Not yet closed:
1. exact final UI landing for each command
2. whether route/public always lands directly on public transit results across app versions
3. edge-case handling for missing or conflicting route parameters
