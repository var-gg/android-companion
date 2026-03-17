# Risk and Safety

## Core risks
- Overreaching package visibility and device-control expectations
- Confusion between personal sideload use and Play-distributed app rules
- User surprise around usage access or package uninstall prompts
- Future remote-control features drifting into unsafe territory without explicit controls

## Safeguards in v0.1
- Local/manual action execution only
- Explicit JSON action naming
- JSON error responses for unsupported or blocked actions
- Usage access requires manual system grant
- Uninstall still requires OS confirmation
- APK install still follows Android sideload prompt flow

## Honest capability posture
The app can request and trigger actions, but Android remains the security boundary.
Where the OS requires consent or confirmation, the app does not pretend otherwise.

## Future safety requirements for v0.2+
- authenticated command origin
- replay protection / nonce model
- audit log of executed commands
- clearer allow/deny capability policy
- foreground execution and visible user affordances for higher-risk actions
