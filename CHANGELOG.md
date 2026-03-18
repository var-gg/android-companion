# Changelog

## v0.1.1
- standardized preferred command envelope to `{ action, params, request_id? }`
- kept backward compatibility for flat v0.1 command shape
- added recent command log panel in the app
- extracted command contract / executor / log storage into separate files
- defaulted self-update lookup to this repository's latest release API
- retained public install flow via GitHub Release APK asset

## v0.1.0
- initial Android Companion MVP
- manual JSON command runner
- core capability execution for app launch, URL open, app listing, usage stats, intents, uninstall request, and self-update flow
- GitHub Actions release workflow with public APK asset publishing
