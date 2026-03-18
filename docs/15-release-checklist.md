# Release Checklist

## Goal
Ship Android Companion through GitHub Releases as the primary public install path.

## Standard release path
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build locally for confidence:
   - `./gradlew assembleDebug`
   - `./gradlew assembleRelease` when signing is available
3. Generate `update-manifest.json`:
   - `node ./scripts/generate-update-manifest.mjs --version 0.2.0-alpha6 --versionCode 7 --debug true`
4. Commit the version bump and manifest.
5. Push to `main`.
6. Create and push tag `v0.2.0-alpha6`.
7. Let GitHub Actions publish the APK asset.
8. Verify:
   - Release page exists
   - APK asset is attached
   - `update-manifest.json` points to the release asset
   - App `Check update` sees the new version

## Current intended user flow
1. User opens GitHub Release page.
2. User downloads APK.
3. User installs Android Companion.
4. User installs/signs into Tailscale.
5. Operator generates pairing QR page.
6. User scans pairing QR in the app.
7. User taps `Test connection`.
8. User taps `Register device`.
9. User taps `Start remote`.

## Operator smoke test after release
1. Start `scripts/mock-remote-bridge.mjs`.
2. Generate QR page with `scripts/generate-pairing-page.mjs`.
3. Import on phone.
4. Confirm `/api/v1/devices` shows registration.
5. Enqueue `health_ping` or `list_installed_apps`.
6. Confirm `/api/v1/results` and `/api/v1/history` show round-trip.

## Notes
- GitHub Releases are the primary install path.
- In-app self-update should target the release package path.
- ADB is optional developer tooling, not the core user install path.
- QR pairing can come from live scan, pasted link text, or a screenshot/image import.
