# Changelog

## v0.2.0-alpha1
- added polling-based remote transport alpha
- added remote config UI for base URL, device ID, bearer token, and poll interval
- added foreground remote polling service with heartbeat, command fetch, execution, and result upload
- introduced reusable Android capability engine for both manual and remote execution paths
- added mock desktop bridge server script for local remote-control testing
- bumped app version to 0.2.0-alpha1

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
