# Android Companion MVP v0.1 Architecture

## Product stance
- Thin Android runtime/executor.
- Business logic stays on desktop/server agents.
- App executes JSON commands and returns JSON results.
- Permissions and trust boundaries are explicit.

## MVP capability surface
1. `health_ping`
2. `device_info`
3. `open_url`
4. `launch_app`
5. `list_installed_apps`
6. `usage_stats`
7. `uninstall_app`
8. `open_intent` / `test_intent`
9. `check_self_update`
10. `download_self_update`

## Boundaries
- App does **not** do autonomous background orchestration in v0.1.
- App does **not** include accessibility automation yet.
- App does **not** receive push commands in v0.1.
- Some actions still require OS/user confirmation:
  - uninstall
  - unknown-source APK install prompt
  - usage access settings grant

## Extensibility seams
- `MainActivity.runCommand` is the initial command router.
- Future v0.2+ should extract capabilities into separate classes/modules:
  - `capabilities/device`
  - `capabilities/apps`
  - `capabilities/intents`
  - `capabilities/update`
  - later `capabilities/accessibility`, `transport/fcm`

## JSON contract shape
All actions return machine-readable JSON:

```json
{
  "ok": true,
  "action": "health_ping",
  "timestamp": "2026-03-17T09:00:00Z",
  "device": { "manufacturer": "Google", "model": "Pixel 8" }
}
```

Errors:

```json
{
  "ok": false,
  "error": {
    "code": "permission_required",
    "message": "Grant PACKAGE_USAGE_STATS in system settings"
  },
  "timestamp": "2026-03-17T09:00:00Z"
}
```
