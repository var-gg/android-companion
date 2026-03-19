# Update and Release Model

## Release source of truth
GitHub Releases are the source of truth for public APK distribution.
The public install path should assume GitHub Release first, then in-app self-update for later versions.

## Operating rule
For this project, user-facing completion is **not** "code merged" or "APK built locally".
The default completion bar for anything the user is expected to try on-device is:
1. release metadata updated
2. signed release artifact available on the official channel
3. in-app update flow should be able to discover that version

If a change has only been committed locally, it should be described as **not yet deployed**.
If the user would normally tap the app's own update button to receive the patch, then publishing the release/update metadata is part of the task, not a separate optional follow-up.

## Build path
- push code to GitHub
- create tag like `v0.1.0`
- GitHub Actions builds APK
- workflow uploads APK to the GitHub Release asset list

## Signing model
If Android signing secrets are configured in GitHub, CI can produce a signed release APK.
The intended install/update path is the signed release package. Debug artifacts should not be treated as the normal user update channel.

## App-side self-update flow
1. App calls `releases/latest` GitHub API endpoint.
2. App compares local `versionName` with latest `tag_name`.
3. App finds an APK asset URL.
4. App downloads APK.
5. App opens the Android install prompt.

## Honest limits
- v0.1 does not implement delta updates
- v0.1 does not bypass sideload prompts
- v0.1 does not manage staged rollouts
