# Evidence: NAVER Map official URL Scheme docs

- Date: 2026-03-19
- Source: NAVER Cloud guide docs
- Korean title: `지도 앱 연동 URL Scheme`
- English title: `URL Scheme for integration of Maps app`
- Canonical URLs observed:
  - https://guide.ncloud-docs.com/docs/maps-url-scheme
  - https://guide.ncloud-docs.com/docs/en/maps-url-scheme
  - https://guide.ncloud-docs.com/docs/maps-overview

## Key facts captured from the official page

- The NAVER Maps app URL Scheme begins with `nmap://`.
- Generic syntax includes `appname={YOUR_APP_NAME}`.
- Android intent-style launch is documented with package `com.nhn.android.nmap`.
- Example surfaces shown in the official guide include:
  - `nmap://map`
  - `nmap://search?query=...`
  - `nmap://search/bus?query=...`
  - `nmap://place?lat=...&lng=...&name=...`
  - `nmap://route/public?...`
  - `nmap://route/car?...`
  - `nmap://route/walk?...`
  - `nmap://route/bicycle?...`
  - `nmap://navigation?...`

## Captured official Android fallback form

```text
intent://actionPath?parameter=value&appname={YOUR_APP_NAME}#Intent;scheme=nmap;action=android.intent.action.VIEW;category=android.intent.category.BROWSABLE;package=com.nhn.android.nmap;end
```

## Notes

- The docs live under `Maps (deprecated)` in the NCloud docs information architecture, but the URL Scheme page is still publicly accessible and useful as official evidence.
- Runtime verification on the user's own installed app should be added as a second evidence tier after canonical records land.
