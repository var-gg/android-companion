# Pairing over Tailscale

## Goal
Make Android Companion remote setup feel close to one flow even though the system still uses two installs:
1. Android Companion APK
2. Tailscale app

The recommended setup is:
- install Android Companion from GitHub APK
- install/sign in to Tailscale on phone and desktop
- generate a pairing code on desktop
- open or scan the `acpair://` link on the phone
- let the app import remote transport settings automatically
- register and start remote polling

## Product boundary
Android Companion remains a thin runtime:
- capability executor
- remote polling client
- pairing payload importer

Tailscale remains the private network layer.
The desktop side generates pairing payloads and QR/deep-link surfaces.

## Pairing payload schema v1
Example JSON:

```json
{
  "type": "android-companion-pairing",
  "version": 1,
  "label": "home-desktop",
  "transport": {
    "mode": "tailscale",
    "base_url": "http://100.101.102.103:8787",
    "token": "",
    "poll_interval_seconds": 10
  },
  "device": {
    "suggested_device_id": "android-main-phone"
  },
  "meta": {
    "generated_at": "2026-03-19T01:00:00Z",
    "expires_at": "2026-03-19T01:30:00Z"
  }
}
```

## Transport format
For import and QR transport, wrap the JSON as:

```text
acpair://v1/<base64url-json>
```

Why:
- easy for Android deep-link routing
- easy to embed inside QR codes
- easy to paste manually when camera flow is unavailable

## App flow
The app now supports:
- Tailscale install/open buttons
- package detection for `com.tailscale.ipn`
- in-app QR scanning for pairing codes
- manual pairing code import
- `acpair://` deep-link import into MainActivity
- auto-fill for base URL / token / poll interval / suggested device id

Current easiest test path:
- render the pairing link as a QR or clickable link on desktop
- scan it in Android Companion or open it as a deep link
- let the app fill the remote config

## Recommended operator flow
1. Make sure desktop Tailscale is online.
2. Start the bridge server on desktop.
3. Generate a pairing link with `scripts/generate-pairing-link.mjs` or a browser-ready QR page with `scripts/generate-pairing-page.mjs`.
4. Show the link or QR on desktop.
5. On Android, import the link.
6. Verify imported base URL.
7. Tap `Register device`.
8. Tap `Start remote`.

## Security notes
Current alpha posture:
- Tailscale strongly preferred
- bearer token optional for local/dev bridge mode
- use short expiry when including tokens in pairing payloads
- expired payloads should be rejected by the app

Future hardening:
- signed payloads
- short-lived registration tokens
- device-bound bootstrap secrets
- richer pairing state validation
