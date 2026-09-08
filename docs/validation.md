# Initialization validation

Recorded **2026-09-08**, on Linux x64 with Node **24.19.0**, npm **11.17.0**,
Even SDK **0.0.15**, CLI **0.1.14**, and official simulator **0.9.5**.

## Verified locally

| Check              | Result                                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------------------------- |
| `npm ci`           | Installs the committed lockfile without peer-dependency overrides                                          |
| `npm run check`    | Formatting, lint, strict types, all 9 behavior tests, and production build pass                            |
| `npm run pack`     | Official CLI produces `meshcore-hud.ehpk`, stamped for Even App 2.2.10 / SDK 0.0.15                        |
| Simulator startup  | SDK creates the HUD; console reports `MeshCore HUD ready: demo / disconnected`                             |
| HUD inspection     | Title, demo label, disconnected companion, no-live-data text, and exit instruction fit the display         |
| Tap / up / down    | Framebuffer stays unchanged, as intended for the static demo                                               |
| Double-tap         | Display clears; no simulator console errors during the input sequence                                      |
| Phone page         | Shows demo/disconnected status after successful SDK startup                                                |
| Production preview | Built `dist/` loads in the simulator with no console errors; HUD framebuffer matches the development build |

The following is an **actual simulator framebuffer capture**, composited onto
black for visibility. The original output uses a transparent background.

![MeshCore HUD in the official simulator](images/hud-simulator.png)

## CI and delivery

The initialization PR runs the same quality checks and packages the app. Its
workflow artifact provides the downloadable distributable; `main` pushes and
manual runs use the same steps. Manual dispatch only becomes available once the
workflow exists on the default branch. The PR remains open for review.

## Still needs hardware

- Actual G2 display readability, touchpad/ring behavior, BLE timing, and reopening.
- Phone background/resume and locked-phone behavior in an installed package.
- Even App permissions and package installation on Android/iOS.
- Any MeshCore connection or telemetry; the source is a mock with no radio access.

SDK result checks and unit tests cannot establish those hardware behaviors.
Store package-ID availability, Even Hub submission, and publication are outside
this milestone. The next connection experiment is described in
[architecture and connection research](architecture.md#next-practical-step).
