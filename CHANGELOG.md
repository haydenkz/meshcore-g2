# Changelog

All notable changes to this project will be documented here, following
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Open Android to Contacts with searchable users, repeaters, sensors, room servers,
  and unknown nodes. Show type icons, full public keys, and last detection times,
  including unnamed nodes and offline history. Move radio, glasses, and notification
  controls into Profile, opened from the header.
- Save the newest 100 received radio packets privately on the phone across
  reconnects and app restarts. Retain packet details and source companion identity,
  automatically evict older packets, and keep capture running while the log view
  is paused.

- Make Android chats more compact with flat conversation rows, smaller message
  bubbles, grouped senders, date dividers, and a jump to the latest messages.
  Fade between tabs and swipe across tabs or adjacent chats while retaining drafts
  and search state. Keep chat browsing order stable as new messages arrive.
- Expand Android radio logs with millisecond timestamps, packet headers, path
  hashes, transport codes, payload sizes, and selectable raw packet hex. Add packet
  filters and pause/resume for inspecting saved packets.

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

- Load saved companion adverts when connecting, keep all known nodes without an
  age or count cutoff, and sort both apps by advert time instead of insertion order.
- Keep the Android header fixed when the keyboard opens, with keyboard spacing
  applied to the chat and search content.
- Snap Android chats to the newest message after sending, once the shared
  history has loaded the outgoing message.
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

- Notify on newly saved channel and direct messages, with separate Android alert
  settings and a tap opening the matching saved chat. Suppress duplicate deliveries
  and alerts for the chat currently being read; hide message previews on private
  lock screens. Enable or manage message notifications from Home.

- Add Android Channels and DMs with saved chat history, message composition, and
  send status. Share incoming and outgoing messages with the glasses; direct
  messages show delivery confirmation when the radio receives an acknowledgement.
- Add Recent adverts to the glasses menu and Android Logs, showing each node's
  latest advert, name, type, and timestamp. Save adverts across restarts and import
  the companion's saved advert timestamps when connecting.
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
