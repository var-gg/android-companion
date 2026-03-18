# Android Companion (v0.2.0-alpha7)

Thin Android runtime / executor for a personal agent system.

This app is intentionally **not** a fat automation brain. It is a small Android-side execution surface that:
- accepts JSON actions,
- performs bounded device capabilities,
- returns machine-readable JSON results,
- leaves orchestration and decision logic to desktop/server agents.

## MVP scope
Implemented in v0.2.0-alpha2:
1. `health_ping`
2. `device_info`
3. `open_url`
4. `launch_app(package)`
5. `list_installed_apps`
6. `usage_stats` (requires user-granted usage access)
7. `uninstall_app` (Intent-based, user confirmation required)
8. `open_intent` / `test_intent`
9. `check_self_update` (GitHub Releases latest endpoint)
10. `download_self_update` (downloads APK and opens install prompt)
11. JSON-first success/error output
12. In-app recent command log panel for manual testing
13. Remote transport config UI
14. Polling-based remote command fetch / result upload alpha
15. Foreground remote polling service
16. Current app version display
17. Check update / Update now controls
18. Manifest-driven soft-force update policy

## Product principles
- Thin runtime on device
- Capability-first instead of hardcoded feature flows
- JSON command contract first
- Explicit permission / consent boundaries
- Safe path for future expansion into Accessibility / FCM

## Repository layout
- `app/` Android app
- `docs/` product / architecture / trust-boundary / roadmap docs
- `.github/workflows/android-release.yml` CI/CD for APK build + release asset publishing
- `scripts/release.ps1` helper for tag-based release flow
- `scripts/generate-update-manifest.mjs` helper for release manifest generation

Start with [`docs/README.md`](./docs/README.md) for the doc-first reading path.

## Local development
### Requirements
- JDK 17+ (Java 21 also works locally for wrapper generation, CI uses JDK 17)
- Android SDK installed locally
- `local.properties` created from `local.properties.example`

### Setup
```powershell
copy local.properties.example local.properties
# then edit sdk.dir
```

### Build debug APK locally
```powershell
.\gradlew.bat assembleDebug
```

### Install on connected device
```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### Preferred public install path
Use the signed GitHub Release APK for normal user installation and in-app self-update.
ADB is developer tooling, not the primary install path.

## Permission notes
### `QUERY_ALL_PACKAGES`
Used for `list_installed_apps` and app launching by package name.
This is sensitive on Play-distributed apps, but acceptable for a personal sideloaded companion runtime.

### `PACKAGE_USAGE_STATS`
Needed for `usage_stats`.
Android does not grant this like a normal runtime permission. The user must explicitly allow usage access in system settings.

### `REQUEST_INSTALL_PACKAGES`
Needed for self-update install prompt when sideloading newer APKs.

## JSON command examples
Preferred envelope in v0.1.1:

```json
{
  "action": "open_url",
  "request_id": "demo-001",
  "params": {
    "url": "https://docs.openclaw.ai"
  }
}
```

Legacy flat v0.1 shape is still accepted for backward compatibility.

### Health ping
```json
{
  "action": "health_ping",
  "params": {}
}
```

### Device info
```json
{
  "action": "device_info",
  "params": {}
}
```

### Open URL
```json
{
  "action": "open_url",
  "params": {
    "url": "https://docs.openclaw.ai"
  }
}
```

### Launch app
```json
{
  "action": "launch_app",
  "params": {
    "package": "com.android.settings"
  }
}
```

### List installed apps
```json
{
  "action": "list_installed_apps",
  "params": {
    "include_system": false
  }
}
```

### Usage stats
```json
{
  "action": "usage_stats",
  "params": {
    "hours": 12
  }
}
```

### Uninstall app request
```json
{
  "action": "uninstall_app",
  "params": {
    "package": "com.example.target"
  }
}
```

### Generic intent
```json
{
  "action": "open_intent",
  "params": {
    "action": "android.settings.APPLICATION_DETAILS_SETTINGS",
    "uri": "package:com.android.chrome"
  }
}
```

### Self update check
```json
{
  "action": "check_self_update",
  "params": {
    "release_api_url": "https://api.github.com/repos/var-gg/android-companion/releases/latest"
  }
}
```

### Self update download
```json
{
  "action": "download_self_update",
  "params": {
    "apk_url": "https://github.com/var-gg/android-companion/releases/download/v0.1.0/app-debug.apk"
  }
}
```

## Response contract
### Success
```json
{
  "ok": true,
  "action": "launch_app",
  "timestamp": "2026-03-17T09:00:00Z",
  "package": "com.android.settings"
}
```

### Error
```json
{
  "ok": false,
  "error": {
    "code": "missing_package",
    "message": "package is required"
  },
  "timestamp": "2026-03-17T09:00:00Z"
}
```

## Update model
The app now supports a simple in-app update UX for sideload releases:
- shows current installed version and versionCode
- checks `update-manifest.json` first for update policy
- supports `force_update` and `min_supported_version_code` soft-gating
- can download the latest APK and open Android's install prompt

Current manifest source:
- `https://raw.githubusercontent.com/var-gg/android-companion/main/update-manifest.json`

Soft-force update means the app can block normal command execution and remote start/register actions until the minimum supported version is installed, while still allowing update actions.

## Remote control alpha
The app includes a polling-based remote transport alpha:
- configure base URL / device ID / bearer token / poll interval in the app
- register the device to a bridge endpoint
- start the foreground polling service
- app fetches remote commands, executes them, and uploads results

Reference:
- [`docs/13-remote-transport-alpha.md`](./docs/13-remote-transport-alpha.md)
- [`docs/14-pairing-over-tailscale.md`](./docs/14-pairing-over-tailscale.md)
- [`scripts/mock-remote-bridge.mjs`](./scripts/mock-remote-bridge.mjs)
- [`scripts/generate-pairing-link.mjs`](./scripts/generate-pairing-link.mjs)
- [`scripts/generate-pairing-page.mjs`](./scripts/generate-pairing-page.mjs)

### Persistence model
Transport settings are stored in app-local persistent storage.
This is the recommended default for a public sideloaded Android companion app:
- `base_url`: persisted
- `device_id`: persisted
- `poll_interval_seconds`: persisted
- `token`: currently persisted in app storage

Notes:
- `device_id` is auto-generated on first load if the field is empty.
- Normal app updates should preserve stored settings.
- App uninstall, package name changes, or switching between different package ids can reset stored data.
- For long-term hardening, sensitive values like tokens should move to encrypted storage / Keystore-backed storage.

### Agent-oriented setup contract
Assume many users of this app will also use an AI agent system such as OpenClaw.
An AI agent helping a user set up this app should gather or derive these values and then give the user copy/paste-ready inputs.

Required inputs for the app:
1. `Remote Base URL`
2. `Device ID`
3. `Bearer Token` (recommended; effectively required for any non-trusted network)
4. `Poll interval seconds`

### What an agent should provide to the user
When assisting with setup, the agent should explicitly provide:
- the exact `Remote Base URL` to paste into the app
- whether the user should keep the auto-generated `Device ID` (default: yes)
- the exact `Bearer Token` to paste only if auth is enabled; otherwise explicitly say to leave it blank
- the recommended `Poll interval seconds`
- whether the bridge is LAN-only, reverse-proxied, or public
- whether additional Android permissions/settings should be enabled for stable polling

### Recommended operator flow
1. Start or identify the bridge endpoint.
2. Determine the phone-reachable URL.
3. Choose a stable `device_id`.
4. Generate or retrieve a bearer token.
5. Tell the user exactly what to paste into each app field.
6. Ask the user to tap `Save`, `Register device`, and `Start remote`.
7. Validate that the bridge sees the device and that commands round-trip successfully.

### Quick local bridge test
Start a local mock bridge on the desktop.
For the easiest local/LAN test, do **not** set a token at all:
```powershell
node .\scripts\mock-remote-bridge.mjs
```

Then provide these values to the user:
- `Remote Base URL`: `http://<desktop-ip>:8787`
- `Device ID`: keep the app-generated value
- `Bearer Token`: leave blank for local/LAN dev mode
- `Poll interval seconds`: `10`

Then enqueue a command from desktop:
```powershell
$headers = @{ Authorization = "Bearer dev-secret" }
$body = @{
  device_id = "<your-device-id>"
  action = "list_installed_apps"
  params = @{ include_system = $false }
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "http://<desktop-ip>:8787/api/v1/commands/enqueue" -Headers $headers -ContentType "application/json" -Body $body
```

Inspect uploaded results:
```powershell
Invoke-RestMethod -Uri "http://<desktop-ip>:8787/api/v1/results" -Headers $headers
```

## GitHub Actions release flow
Workflow: `.github/workflows/android-release.yml`

### Trigger
- push tag: `v0.1.0`
- manual workflow dispatch

### Behavior
- builds debug APK always
- builds signed release APK when signing secrets are configured
- uploads APK as workflow artifact
- on tag push, publishes the APK into GitHub Releases

## GitHub Secrets
Optional for signed release APKs:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Without these, CI still publishes a debug APK so install/testing is not blocked.

## Recommended GitHub release naming
- repo tag: `v0.1.0`
- release asset (signed): `android-companion-v0.1.0.apk`
- release asset (fallback debug): `android-companion-v0.1.0-debug.apk`

## Testing checklist
- [ ] app opens on device
- [ ] `health_ping` returns JSON
- [ ] `device_info` returns model/sdk/package/version
- [ ] `open_url` opens browser
- [ ] `launch_app` launches settings or another installed app
- [ ] `list_installed_apps` returns packages
- [ ] `usage_stats` returns permission error before grant
- [ ] grant usage access, then `usage_stats` returns data
- [ ] `uninstall_app` opens uninstall confirmation UI
- [ ] `open_intent` can open settings or target activity
- [ ] `check_self_update` detects latest GitHub Release
- [ ] `download_self_update` downloads APK and opens install prompt

## Known limitations in v0.1.1
- No background transport yet (no FCM/WebSocket command ingress)
- No authenticated remote command channel yet
- Self-update uses GitHub Releases and sideload flow, not Play updates
- Release signing depends on secrets being configured in GitHub
- Capability handlers are partially extracted, but the app is still not fully modularized into separate Android modules yet

## v0.2 candidates
- extract capability handlers into modules/packages
- add authenticated HTTP/WebSocket or FCM transport
- richer intent result reporting
- package search / filtering / label lookup
- Accessibility automation bridge with explicit consent model
- foreground service + task queue for remote command execution
