# Capability Contract

## Contract goals
- predictable machine-readable responses
- low ambiguity for server/desktop agents
- minimal hidden UI assumptions
- explicit errors when Android blocks or requires consent

## Request shape

```json
{
  "action": "launch_app",
  "package": "com.android.settings"
}
```

## Response shape

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
    "code": "app_not_found",
    "message": "No launch intent for com.example.missing"
  },
  "timestamp": "2026-03-17T09:00:00Z"
}
```

## v0.1 actions
- `health_ping`
- `device_info`
- `open_url`
- `launch_app`
- `list_installed_apps`
- `usage_stats`
- `uninstall_app`
- `open_intent`
- `test_intent`
- `check_self_update`
- `download_self_update`

## Notable response conventions
- `ok: false` always indicates machine-checkable failure
- `error.code` is intended to be stable-ish across minor app changes
- permission-dependent actions should include enough context for the caller to decide next step
- actions that open confirmation UIs should state that confirmation is still required
