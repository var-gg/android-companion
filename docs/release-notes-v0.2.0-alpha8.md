# Android Companion v0.2.0-alpha8

## Highlights
- Moves the app onto a stable signed release line for GitHub APK install and future install-over updates
- Prevents unsigned/debug fallback APKs from being published as official release assets
- Keeps QR image import support for captured pairing QR screenshots/images

## Why this release matters
- Establishes the long-term update chain required for browser-downloaded APK installs without repeated reinstall churn
- Makes official GitHub Releases match the intended self-update path

## User expectation
- If a previous install came from a different signing key or a debug build, one cleanup reinstall may still be required for the first migration onto the signed release line
- After that, future signed releases should support normal install-over updates
