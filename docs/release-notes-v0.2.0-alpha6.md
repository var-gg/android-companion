# Android Companion v0.2.0-alpha6

## Highlights
- Adds Tailscale-first pairing bootstrap for remote setup
- Adds `acpair://` deep-link import
- Adds in-app pairing QR scanning
- Adds remote connection testing before register/start
- Hardens pairing payload validation for transport mode, host shape, and token expiry expectations
- Adds desktop helper scripts for pairing QR generation, command enqueue, and result waiting
- Keeps GitHub Releases as the primary install path for users

## Operator flow
1. Install Android Companion from GitHub Release APK
2. Install/sign in to Tailscale on desktop and phone
3. Generate pairing QR page on desktop
4. Scan pairing QR in Android Companion
5. Test connection
6. Register device
7. Start remote
8. Enqueue a smoke-test command and confirm results

## Included tooling
- `scripts/generate-pairing-link.mjs`
- `scripts/generate-pairing-page.mjs`
- `scripts/enqueue-command.mjs`
- `scripts/await-result.mjs`
- `scripts/mock-remote-bridge.mjs`
- `scripts/generate-update-manifest.mjs`

## Notes
- GitHub Release is the primary public install path.
- In-app self-update remains the path for subsequent versions.
- If signing secrets are absent in CI, the published asset may still be a debug APK for install/testing.
