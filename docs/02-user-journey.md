# User Journey

## v0.1 operator journey
1. Clone or download the repo.
2. Install the APK from GitHub Releases.
3. Open the app and paste a JSON action.
4. Execute the action.
5. Inspect the JSON result.
6. Grant extra permissions only when needed for specific capabilities.
7. Use self-update check to move to newer APKs.

## Example first session
- Run `health_ping`
- Run `device_info`
- Run `list_installed_apps`
- Run `usage_stats` and grant usage access if desired
- Run `open_url` or `open_intent` to validate intent execution

## Future journey (v0.2+)
- Remote agent sends authenticated command payload
- App validates and queues the command
- Device executes capability
- App returns structured result to server/desktop agent
