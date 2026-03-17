# Android Companion MVP (v0.1)

Thin Android runtime / executor for a personal agent system.

This app is intentionally **not** a fat automation brain. It is a small Android-side execution surface that:
- accepts JSON actions,
- performs bounded device capabilities,
- returns machine-readable JSON results,
- leaves orchestration and decision logic to desktop/server agents.

## MVP scope
Implemented in v0.1:
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
### Health ping
```json
{
  "action": "health_ping"
}
```

### Device info
```json
{
  "action": "device_info"
}
```

### Open URL
```json
{
  "action": "open_url",
  "url": "https://docs.openclaw.ai"
}
```

### Launch app
```json
{
  "action": "launch_app",
  "package": "com.android.settings"
}
```

### List installed apps
```json
{
  "action": "list_installed_apps",
  "include_system": false
}
```

### Usage stats
```json
{
  "action": "usage_stats",
  "hours": 12
}
```

### Uninstall app request
```json
{
  "action": "uninstall_app",
  "package": "com.example.target"
}
```

### Generic intent
```json
{
  "action": "open_intent",
  "intent_action": "android.settings.APPLICATION_DETAILS_SETTINGS",
  "data": "package:com.android.chrome"
}
```

### Self update check
```json
{
  "action": "check_self_update",
  "release_api_url": "https://api.github.com/repos/OWNER/REPO/releases/latest"
}
```

### Self update download
```json
{
  "action": "download_self_update",
  "apk_url": "https://github.com/OWNER/REPO/releases/download/v0.1.0/android-companion-v0.1.0.apk"
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

## Known limitations in v0.1
- No background transport yet (no FCM/WebSocket command ingress)
- No authenticated remote command channel yet
- Self-update uses GitHub Releases and sideload flow, not Play updates
- Release signing depends on secrets being configured in GitHub
- Current command router is single-activity based and should be modularized in v0.2

## v0.2 candidates
- extract capability handlers into modules/packages
- add authenticated HTTP/WebSocket or FCM transport
- richer intent result reporting
- package search / filtering / label lookup
- Accessibility automation bridge with explicit consent model
- foreground service + task queue for remote command execution
