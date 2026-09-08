# Development guide

## Setup and simulator

Install Node 24.19.0 from `.nvmrc` and use npm (`11.17.0` is the recorded package
manager). Run `npm ci`, then `npm run dev`. Start `npm run simulate` in a second
terminal. The official simulator is pinned as a dev dependency; no global CLI
installation is needed. It requires a desktop session and its platform GUI
libraries. See the [official simulator guide](https://hub.evenrealities.com/docs/test/simulator).

The HUD should show the project title, demo label, disconnected companion, and
“No live radio data.” Tap and scroll leave it unchanged; double-tap exits
immediately. Restart the simulator to open the demo again. This static milestone
has no timers or mutable state to restore after backgrounding.

Optional automation for inspecting the actual simulator framebuffer:

```sh
npm run simulate -- --automation-port 9898
curl http://127.0.0.1:9898/api/ping
curl http://127.0.0.1:9898/api/console
curl http://127.0.0.1:9898/api/screenshot/glasses -o /tmp/meshcore-hud.png
curl -H 'Content-Type: application/json' \
  -d '{"action":"double_click"}' http://127.0.0.1:9898/api/input
```

Wait for the console message `MeshCore HUD ready: demo / disconnected` before
capturing. Screenshots are transparent RGBA; preserve alpha when viewing them.
A plain browser can display the companion page but cannot validate SDK rendering.

## Device testing

1. Pair the G2 with the Even App **2.2.10 or newer**, the minimum required by SDK
   0.0.15. Use the developer/local-testing flow described in
   [official local testing](https://hub.evenrealities.com/docs/test/local-testing).
2. Run `npm run dev`; the phone must reach the computer's LAN address on port 5173.
3. Run `npm run qr -- --url http://YOUR_LAN_IP:5173` and scan in the Even App.
4. Verify text fits and is readable in both lenses. Check taps, swipes, double-tap
   exit, reopening, and phone background/foreground transitions.

The simulator does not prove BLE timing, device pairing, firmware typography, or
mobile lifecycle behavior. Record phone OS, Even App version, and glasses firmware
when hardware testing. No MeshCore companion is required for this demo.

## Checks and package delivery

`npm run check` runs formatting, lint, strict types, Node behavior tests, and a
production build. Individual commands are listed in [AGENTS.md](../AGENTS.md).
Tests cover demo labelling, failed startup, event routing, exit deduplication,
failed-exit retry, and listener cleanup. They use a fake bridge, so they do not
substitute for SDK or hardware integration checks.

Run `npm run pack` to build and invoke the locked official `evenhub pack` CLI.
The output is `meshcore-hud.ehpk`; `app.json` declares no additional permissions.
Vite emits relative asset paths for packaged hosting. `dist/` and `.ehpk` files
are ignored by Git. Package ID availability on the store has not been checked.
The CLI receives `--sdk-ver 0.0.15` so its compatibility stamp follows the SDK we
build with, rather than a future npm latest version. It queries npm for this
metadata and warns if it falls back to its bundled map offline. Update the flag
and manifest with future SDK upgrades.

The [CI workflow](../.github/workflows/ci.yml) uses one Node version, npm caching,
read-only repository access, and cancellation of superseded runs. PRs and `main`
pushes run all checks. After successful checks, PRs, `main` pushes, and manual runs
package and upload `meshcore-hud-<commit SHA>` with 14-day retention. PR packages
let reviewers try the initialization before it reaches the default branch.

After the initialization PR is merged by a reviewer, open **Actions → CI → Run
workflow** to make a manual package. GitHub requires `workflow_dispatch` to exist
on the default branch before the manual-run UI/API becomes available
([GitHub documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/manually-run-a-workflow)). Until then,
use the PR artifact or `npm run pack`. Download CI packages from the run's
**Artifacts** section. No release, store submission, or publication is
performed by this workflow.
