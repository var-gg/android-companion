# Release / Secrets

## GitHub Secrets expected
Optional signing path:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If secrets are absent, workflow still publishes a debug APK asset for install/testing.

## Public release strategy
- Tag push like `v0.1.0`
- GitHub Actions builds APK
- Workflow uploads APK to GitHub Release assets
- App can query `releases/latest` for self-update

## Why debug fallback exists
For a fresh open-source MVP, release signing may not be ready on day 1.
A public debug APK keeps install/test loop unblocked while preserving a clean path to signed releases via secrets.
