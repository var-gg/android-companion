# Android Companion v0.2.0-alpha7

## Highlights
- Adds QR image import so a captured pairing QR can be imported from screenshots or gallery images
- Normalizes GitHub Releases to publish release APK assets as the primary install and self-update path
- Keeps Tailscale-first pairing flow and remote bootstrap path intact

## Why this release matters
- Fixes the confusing debug-APK release path for normal users
- Makes remote pairing workable even when the desktop QR is only available as a screenshot/image

## Operator flow
1. Open the GitHub Release APK link
2. Install Android Companion
3. Install/sign in to Tailscale on desktop and phone
4. Generate a pairing QR or captured image on desktop
5. In the app, use Scan pairing QR, Import QR image, or paste the acpair link
6. Test connection
7. Register device
8. Start remote
