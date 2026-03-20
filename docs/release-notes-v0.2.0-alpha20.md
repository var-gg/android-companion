# Release notes - v0.2.0-alpha20

## What changed
- fixed the Logs tab's stored-log panel touch handoff so the inner log list can scroll directly without the outer page stealing the gesture
- expanded NAVER Map route/public catalog notes and evidence so documented route-family selection is kept separate from unverified first-visible tab behavior
- kept the transit capability conservative: `nmap://route/public?...` stays modeled as the documented transit-family entrypoint, but subway-first / transit-tab forcing is still not claimed
