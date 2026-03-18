# 15. Signing and release channel

## Goal
Use GitHub Releases as the public install/update channel **without reinstall churn**.

For Android sideload distribution, that requires a **stable signing key**.
A release APK is not special because it is called "release"; it is special because it is the build we keep signing with the same key across versions.

## Required invariant
All user-installable APKs in the official channel must have:
1. the same `applicationId`
2. the same signing certificate

If either changes, update install-over will fail and users may need uninstall/reinstall.

## Official channel
Official/public install and self-update channel:
- GitHub Release asset named `android-companion-vX.Y.Z.apk`
- built from `assembleRelease`
- signed with the project's stable personal keystore

## Non-official channel
Debug builds are for:
- local dev
- adb install testing
- temporary diagnostics

Debug builds should **not** be treated as the long-term public update line.
Even when they install, debug-key continuity is the wrong foundation for user-facing patching.

## CI policy
Tag releases must only publish a signed release APK.

If signing secrets are missing on a tag push, CI should fail instead of publishing an unsigned or debug fallback asset.
That is safer because it prevents a broken install path from becoming the latest public version.

Manual workflow runs may still produce debug artifacts for local testing, but those are not the official install/update path.

## Required GitHub secrets
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Keystore guidance
Use one stable keystore for this app line and back it up securely.
Losing the keystore means future updates cannot install over existing user installs.

Suggested handling:
- create one personal release keystore
- store the `.jks` file securely offline
- store GitHub Actions secrets from that keystore
- never rotate casually

## Operational rule
Before cutting a public tag:
1. confirm signing secrets exist
2. confirm `assembleRelease` produces a signed APK in CI
3. only then publish/tag a user-facing release

This keeps the public APK path compatible with in-app self-update and browser-downloaded install-over flows.
