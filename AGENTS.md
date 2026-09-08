# Working in meshcore-g2

Read README.md for user-facing behavior and CONTRIBUTING.md for development and
release instructions. This product has an Even Hub app in `apps/even` and a native
Android helper in `apps/android`; keep changes scoped to the appropriate app.

- Work on feature branches and open PRs against `main`. Do not merge or publish
  without authorization. Preserve unrelated local work.
- Keep Even transport code in `apps/even/src/meshcore` and glasses rendering in
  `apps/even/src/hud.ts`. The helper owns radio BLE and the loopback status server.
- Verify official SDK/platform docs before changing integration assumptions.
  Preserve fetch binding, authenticated loopback access, CORS, and HTTP framing.
- Use pinned Node/JDK/SDK versions and lockfiles/wrappers. Keep the two product
  versions synchronized and increment Android's version code for releases.
- Run `npm run check` and `npm run pack` for changes affecting both apps or
  packaging. App-specific checks are described in CONTRIBUTING.md.
- Update CHANGELOG.md for meaningful changes. Keep user setup in README.md and
  developer/release instructions in CONTRIBUTING.md; avoid duplicate guides.
- Report automated, simulator, and hardware validation separately. CI success
  does not establish radio timing or Even background behavior.
- Never commit generated builds, credentials, helper keys, signing keys, pairing
  codes, or local environment files. Public releases require stable signing;
  development APKs use a separate app ID.
