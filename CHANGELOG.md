# Changelog

## v0.2.0-alpha4
- fixed GitHub release workflow so uploaded release assets use the same versioned filename referenced by the update manifest
- updated update manifest for alpha4 with a correct versioned APK URL
- added APK reachability checks in self-update status for easier diagnosis of broken update links
- bumped app version to 0.2.0-alpha4

## v0.2.0-alpha3
- added Korean/English bilingual strings with in-app language selection
- added clearer section descriptions and more explicit button labels
- added permissions/device-setup panel for usage access, unknown app installs, notifications, and battery optimization exemption
- added POST_NOTIFICATIONS and battery optimization request support
- bumped app version to 0.2.0-alpha3

## v0.2.0-alpha2
- added in-app current version display
- added Check update / Update now controls
- added manifest-driven update policy via `update-manifest.json`
- added soft-force update gating using `min_supported_version_code` and `force_update`
- made self-update check return richer version/policy metadata
- bumped app version to 0.2.0-alpha2

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
