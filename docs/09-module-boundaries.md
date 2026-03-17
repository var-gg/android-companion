# Module Boundaries

## Current reality
The first cut keeps command routing and capability logic together for speed.
That is acceptable for MVP, but not the target long-term shape.

## Desired split
### contract
JSON request/response models, result codes, and eventually schema validation.

### capabilities/device
- health ping
- device info

### capabilities/apps
- app launch
- list installed apps
- usage stats
- uninstall request

### capabilities/intents
- generic intent execution
- test intent helpers

### capabilities/update
- release lookup
- APK download
- install handoff

### transport
- future FCM/WebSocket/HTTP ingress
- authentication and request verification

## Design rule
New product behavior should prefer adding or composing capabilities rather than burying custom app-specific workflows in the Android client.
