# Scope and Non-Goals

## v0.1 in scope
- Local Android app project
- JSON action input + JSON result output
- Device health and device info
- Open URL
- Launch app by package
- List installed apps
- Query usage stats when user enables access
- Request uninstall via Intent
- Generic intent test surface
- Self-update check against GitHub Releases
- APK download + install prompt
- GitHub Actions APK build/release flow

## v0.1 out of scope
- Accessibility automation
- Push command transport (FCM/WebSocket)
- Background queueing and retries
- Per-user auth/account system
- End-to-end encrypted remote command channel
- Screen scraping or hidden background capture
- Silent app install or uninstall bypasses

## Why this scope is intentionally narrow
The first release should prove:
1. the device capability model,
2. the JSON contract style,
3. the permission/trust posture,
4. the release/install/update loop.
