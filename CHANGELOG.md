# Changelog

All notable changes to this project will be documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Use a shared pixelated green MeshCore icon for the Android launcher and Even
  phone page, with matching monochrome artwork for the Even portal.
- Simplify the Even phone page with radio and battery cards matching the Android
  helper, a compact linked state, and collapsed connection details.

- Rename the app to MeshCore G2 across the phone and glasses UI, package, and
  Android helper instructions. The Android companion is named MeshCore G2 Helper.
- Remember the phone helper link in Even host storage after one setup, with
  reconnect and forget actions.
- Replace the Android helper's platform widgets with Jetpack Compose and Material 3. Add lifecycle-aware state, nearby-radio cards, separate radio/plugin status,
  and expandable connection diagnostics.

### Fixed

- Bind the status client's browser fetch to the window. Calling it with the
  source object caused Chromium's “Illegal invocation” before any request,
  even when the health check succeeded. Connection checks now verify the saved
  key and status response as well as helper availability.
- Disable compression for the helper's small local responses. NanoHTTPD compressed
  empty CORS preflights, corrupting reused browser connections with invalid HTTP
  responses. Helper 0.2.1 includes a regression test using the same socket.
- Accept MeshCore ESP32 BLE MTUs of 172/176 for the initial identity and battery
  handshake; report actual negotiation failures without blaming radio firmware.
- Add phone-side connection checks and helper request diagnostics to investigate
  failures reaching localhost from the Even App. The initial connection has now
  been confirmed on Android hardware.

### Added

- Minimal Even G2 HUD with a labelled demo/disconnected state and double-tap exit.
- Separate MeshCore data-source interface and mock for hardware-free development.
- Strict TypeScript, Vite, pinned Node LTS, npm lockfile, and development checks.
- Official simulator and Even Hub packaging commands with a valid app manifest.
- CI checks and downloadable packages on PRs, main-branch builds, and manual runs.
- Setup, architecture, connection research, validation notes, and contribution guidance.

[Unreleased]: https://github.com/haydenkz/meshcore-g2/compare/main...HEAD
