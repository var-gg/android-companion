# Product Principles

1. **Thin runtime first**  
   Keep orchestration, planning, ranking, and policy logic off-device whenever practical.

2. **Capability-first, not feature-first**  
   Prefer reusable actions like `open_url` or `launch_app` over one-off hardcoded workflows.

3. **JSON contracts are primary**  
   Every action should accept structured input and return machine-readable output.

4. **Explicit trust boundaries**  
   If an action depends on Android permissions, install policy, package visibility, accessibility, or user confirmation, that must be surfaced clearly.

5. **Personal-use practicalness over Play-store polish**  
   Sideloaded personal companion constraints are acceptable in v0.1 if documented honestly.

6. **Extensibility without premature bloat**  
   Leave clean seams for Accessibility, FCM, and richer command ingress later without forcing them into the first release.
