# Update and Release Model

## Release source of truth
GitHub Releases are the source of truth for public APK distribution.
The public install path should assume GitHub Release first, then in-app self-update for later versions.

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
