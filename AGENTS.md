# Working in meshcore-g2

Read the README and relevant `docs/` before changing behavior. This milestone is
a demo scaffold; real MeshCore connectivity belongs in a later PR.

- Use feature branches and focused commits. Open PRs against `main`; do not push
  implementation directly to `main`, merge, or enable auto-merge unless requested.
  The initialization branch is `chore/initialize-even-meshcore`.
- Preserve unrelated work. Keep the app small: strict TypeScript, Vite, npm, and
  the official Even SDK. Keep radio data access in `src/meshcore/` and rendering in
  `src/hud.ts`.
- Check current official SDK/docs before changing platform integration. Never
  assume Web Bluetooth support or a direct glasses-to-radio connection.
- Use `.nvmrc` (Node 24.19.0) and commit `package-lock.json` with dependency changes.
- Update `CHANGELOG.md` under `Unreleased` for relevant behavior, tooling, and
  documentation changes. Keep entries brief and understandable; explain in the PR
  if a changelog update is unnecessary.
- Run the checks below before opening/updating a PR. Add meaningful behavior tests
  when needed; avoid placeholder tests. Report simulator and hardware verification
  separately. Update docs when commands or constraints change.

| Command                                       | Purpose                                          |
| --------------------------------------------- | ------------------------------------------------ |
| `npm ci`                                      | Install the locked dependencies                  |
| `npm run dev`                                 | Vite on port 5173, accessible on the LAN         |
| `npm run simulate`                            | Official simulator; run Vite in another terminal |
| `npm run qr -- --url http://YOUR_LAN_IP:5173` | Device-testing QR                                |
| `npm run typecheck`                           | Strict TypeScript checks                         |
| `npm run lint`                                | ESLint with zero warnings                        |
| `npm run format` / `npm run format:check`     | Apply / check Prettier formatting                |
| `npm test`                                    | Node behavior tests                              |
| `npm run build`                               | Type-check and build `dist/`                     |
| `npm run check`                               | All local quality checks and build               |
| `npm run pack`                                | Build and package `meshcore-hud.ehpk`            |
| `npm run preview`                             | Serve the production build locally               |

CI has read-only contents permission. Delivery is an Actions artifact, with no
store publication or deployment credentials. Keep generated builds and packages
out of Git.
