# Changelog

All notable changes to this project will be documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Remove the battery readout and Stop footer from Android Home. The notification
  retains its Stop action.
- Use the shared logo in the Android header and name both Android editions
  MeshCore G2. Simplify Home by hiding Nearby radios while connected, removing
  the tagline, redundant linking copy, and connection debugging controls.

- Open Channels by default on the glasses, with a logo, radio TX/RX packet totals,
  and native menu entries for Channels and Direct messages. Direct chats are
  ordered by latest activity and open into their message history.
- Browse one message or chat card per swipe, with separate channel, sender/time,
  and message areas, dimmed metadata, and a visible position indicator.
- Show the phone's local time at the right of the glasses header, updating each
  minute. Use 24-hour time throughout the app, and compact large packet totals
  to keep room for the clock.
- Receive channel and direct messages from the radio, save them privately on the
  phone, and expose authenticated, paginated history for the glasses.
- Simplify the Even phone page to radio status and helper setup, with a logo
  heading, version footer, and APK download link. Remove connection diagnostics,
  battery voltage, and manual reconnect; saved links retry automatically when the
  helper returns.
- Simplify the README and contributor setup for the Even simulator, glasses,
  Android emulator, and physical phones.
- Use a shared pixelated green MeshCore icon for the Android launcher and Even
  phone page, with matching monochrome artwork for the Even portal.

- Organize both applications under `apps/`, with one installation README and one
  contributor guide. Replace obsolete demo/research docs and screenshots.
- Coordinate both app versions, collect matching installers with checksums and
  build metadata, and separate Android development installs from signed releases.
- Check both applications in CI and provide one preview download. Add a manual
  signed draft-release workflow and grouped dependency updates.
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

- Add Home and Logs navigation to Android. Logs show the latest 200 received
  radio packets with type, route, signal strength, size, and 24-hour timestamps.
  The live log resets for each radio connection and excludes helper HTTP traffic.

- Minimal Even G2 HUD with a labelled demo/disconnected state and double-tap exit.
- Separate MeshCore data-source interface and mock for hardware-free development.
- Strict TypeScript, Vite, pinned Node LTS, npm lockfile, and development checks.
- Official simulator and Even Hub packaging commands with a valid app manifest.
- CI checks and downloadable packages on PRs, main-branch builds, and manual runs.
- Setup, architecture, connection research, validation notes, and contribution guidance.

[Unreleased]: https://github.com/haydenkz/meshcore-g2/compare/main...HEAD
