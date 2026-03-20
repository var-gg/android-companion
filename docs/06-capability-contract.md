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

## `open_intent` delivery routing
`open_intent` accepts `params.delivery_policy` with:
- `direct`
- `notify`
- `auto`

`auto` should be the normal remote default. It prefers direct launch only when the app is foreground and otherwise routes through a notification-mediated user tap path.

Representative response fields for `open_intent`:
- `delivery_policy_requested`
- `delivery_policy_effective`
- `delivery_channel` (`direct` or `notification`)
- `delivery_status` (`launched_direct`, `notification_posted`, `pending_user_action`, etc.)
- `user_action_required`
- `direct_launch_attempted`
- `direct_launch_succeeded`
- `notification_posted`
- `notification_id`
- `suspected_background_launch_blocked`

Notification-mediated execution is intentionally modeled as a valid outcome rather than a failure when user action is still pending.

## Notable response conventions
- `ok: false` always indicates machine-checkable failure
- `error.code` is intended to be stable-ish across minor app changes
- permission-dependent actions should include enough context for the caller to decide next step
- actions that open confirmation UIs should state that confirmation is still required
