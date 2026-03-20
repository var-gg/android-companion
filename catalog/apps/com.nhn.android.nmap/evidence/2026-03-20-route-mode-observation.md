# NAVER Map route mode / initial tab observation — 2026-03-20

## What was checked

- Existing official URL-scheme evidence already in this repo documents distinct route paths:
  - `nmap://route/car?...`
  - `nmap://route/public?...`
  - `nmap://route/walk?...`
  - `nmap://route/bicycle?...`
- User-side runtime observation reported that recent launches landed with the **driving/car tab selected**, even while the workstream goal was to open directly into a transit/subway-like route view.
- During this pass, no additional documented parameter was found in the repo evidence set that selects a **sub-mode inside public transit** (for example, forcing subway-first rather than a broader transit chooser), and no documented override field was found that reliably forces the initial visible tab when runtime behavior disagrees with the route path.

## Verified conclusion for cataloging

1. **Route family selection is documented at the path level** (`route/car`, `route/public`, `route/walk`, `route/bicycle`).
2. **Initial UI tab/mode is not yet trustworthy enough to model as guaranteed** from current evidence alone.
3. Specifically, this repo does **not** currently have verified evidence for:
   - forcing a subway-only / subway-first sub-tab within `route/public`
   - forcing the first visible route tab when NAVER Map chooses differently at runtime
4. Therefore, downstream command design should treat route mode as:
   - `drive` → use `nmap://route/car?...`
   - `transit` → use `nmap://route/public?...`
   - but **do not promise** that the first visible in-app tab will always match the requested family without human confirmation

## Current recommendation

- Keep command shape conservative.
- Model only the documented route-family surface.
- Record a limitation that final landing may drift by app version, runtime heuristics, or in-app chooser behavior.
- If later device testing proves a stable subway-specific selector, add it as a new capability instead of overloading the current `route-transit` record.
