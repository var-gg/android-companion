# Permission Center UX (alpha11 target)

## Goal
Make Android Companion's capability boundaries explicit in-app so the phone can become a safe remote execution surface without confusing permission failures.

## Product posture
The app should not pretend Android special permissions are regular runtime permissions.
Instead it should:
- show current permission state
- explain what each permission unlocks
- request directly when Android allows in-app prompts
- deep-link to system settings when Android requires special access pages
- re-check state on resume and after returning from settings

## Why this matters now
Remote transport is now proven for:
- device_info
- list_installed_apps
- launch_app

And capability gating is already visible for:
- usage_stats -> requires PACKAGE_USAGE_STATS
- self-update install flow -> needs unknown app installs
- stable remote polling -> benefits from battery optimization exemption
- foreground remote operation -> benefits from notifications visibility

## MVP screen structure
Add a dedicated top-level card/section named `Permission Center` on the home screen.

For each row show:
- permission name
- current state (`Enabled` / `Needs attention`)
- short why-it-matters sentence
- action button

### Recommended rows
1. Notifications
   - Why: shows remote polling state and errors
   - Type: runtime permission
   - Action: request permission if possible, otherwise open app notification settings

2. Battery optimization exemption
   - Why: reduces chance of remote polling being suspended in background
   - Type: special settings flow
   - Action: open `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

3. Usage access
   - Why: required for `usage_stats` capability and cleanup suggestions
   - Type: special settings flow
   - Action: open `ACTION_USAGE_ACCESS_SETTINGS`

4. Install unknown apps
   - Why: required for self-update install prompt from downloaded APK
   - Type: special settings flow
   - Action: open per-app unknown sources settings

## Capability-to-permission mapping
- `device_info`: no extra permission
- `list_installed_apps`: no extra permission under current package visibility posture
- `launch_app`: no extra permission if app has launch intent
- `open_url`: no extra permission
- `usage_stats`: requires Usage Access
- `download_self_update`: requires Unknown App Sources for install handoff
- `remote polling`: should strongly recommend Notifications + Battery optimization exemption

## UX behavior
### Home summary
Show a compact summary above the detailed rows:
- `Remote-ready`
- `Partially ready`
- `Needs setup`

Suggested logic:
- Remote-ready: notifications + battery optimization ok, and no blocking missing permissions for current enabled features
- Partially ready: polling works but one or more recommended permissions missing
- Needs setup: transport config missing or key recommended permissions missing after pairing

### Pairing-complete flow
After successful pairing import, surface a checklist modal or inline card:
1. Allow notifications
2. Exclude from battery optimization
3. Grant usage access (optional but recommended)
4. Allow installs from this app (optional, for self-update)

Important: mark optional vs required clearly.

### Remote command failure UX
If a remote command fails because of a missing permission, store structured error details and surface a tappable recovery card in the app:
- capability name
- missing permission
- `Fix now` button

Example for `usage_stats`:
- Title: `Usage access required`
- Body: `Grant Usage Access to allow the companion to collect app activity summaries.`
- Action: `Open settings`

## Tone
Keep copy explicit and practical:
- say what data/behavior becomes available
- say whether user confirmation is still required
- avoid implying hidden automation

## Alpha11 implementation checklist
- [ ] Add Permission Center section/header in main screen
- [ ] Add per-permission rationale strings
- [ ] Add summary state (`Remote-ready` / `Partially ready` / `Needs setup`)
- [ ] Re-check permission snapshot in `onResume`
- [ ] Surface last remote permission error as recovery UI
- [ ] Distinguish `required now` vs `optional / unlocks more capability`

## Non-goals for this pass
- No fake one-tap flow for permissions Android only grants in Settings
- No aggressive nag loops
- No bundling accessibility / notification listener until their capabilities are implemented
