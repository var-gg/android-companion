# Permissions and Trust Boundaries

## QUERY_ALL_PACKAGES
### Why it exists
Needed to support:
- `list_installed_apps`
- package-based launch lookups

### Boundary
This is strong package visibility and should be treated as a deliberate sideload/personal-use choice.

## PACKAGE_USAGE_STATS
### Why it exists
Needed for `usage_stats`.

### Boundary
This is not a normal runtime permission.
The user must grant usage access in Android system settings.
The app should never imply this is silently available.

## REQUEST_INSTALL_PACKAGES
### Why it exists
Needed for self-update install flow from downloaded APK.

### Boundary
The OS still controls unknown-source install policy and user confirmation.

## Intent execution boundary
Generic intent execution is powerful but limited by Android export rules, activity resolution, and package visibility.
A command request does not guarantee the target can be launched.

## Future high-risk boundaries
If/when the project adds Accessibility, notification listeners, or background remote control, those should be documented as a separate trust tier, not quietly merged into current permissions.
