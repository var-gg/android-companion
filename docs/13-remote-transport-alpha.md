# Remote Transport Alpha

## Goal
Turn Android Companion from a local manual executor into a remotely driven Android execution surface.

## v0.2-alpha posture
This alpha uses a simple polling transport.
The phone periodically asks a configured bridge server for the next command, executes it locally, and uploads the structured result.

## Why polling first
- faster to ship than FCM
- no Firebase setup required for first remote tests
- enough to validate real remote app-inventory and uninstall-assist scenarios

## Control loop
```text
Desktop/Server agent
  -> enqueue command for device_id

Bridge server
  -> stores queued command

Android Companion
  -> register / heartbeat
  -> poll next command
  -> execute capability
  -> upload result
```

## HTTP contract
### Register
`POST /api/v1/register`

```json
{
  "device_id": "android-123",
  "device": {
    "ok": true,
    "action": "device_info",
    "app": { "version_name": "0.2.0-alpha1" }
  }
}
```

### Poll next command
`GET /api/v1/commands/next?device_id=android-123`

Response with work:
```json
{
  "ok": true,
  "command": {
    "id": "cmd-001",
    "request_id": "req-001",
    "action": "list_installed_apps",
    "params": {
      "include_system": false
    }
  }
}
```

Response with no work:
```json
{
  "ok": true,
  "command": null
}
```

### Upload result
`POST /api/v1/commands/:id/result`

```json
{
  "device_id": "android-123",
  "result": {
    "ok": true,
    "action": "list_installed_apps",
    "count": 123,
    "apps": []
  }
}
```

### Heartbeat
`POST /api/v1/heartbeat`

## Current trust model
Bearer token is optional in local/LAN alpha mode.
For a same-network personal setup, the easiest path is:
- paste the desktop bridge URL into the app
- keep the auto-generated device ID
- leave token blank
- start remote polling

This is convenient for development and first-user testing, but it is not strong enough for hostile-network production use.
Future versions should add:
- per-device registration secret
- signed requests
- nonce/replay protection
- tighter result provenance

## First remote scenario
1. register device
2. poll for `list_installed_apps`
3. upload app inventory
4. poll for `usage_stats`
5. upload usage history
6. server computes cleanup candidates
7. enqueue `uninstall_app`
8. phone opens Android uninstall confirmation UI
