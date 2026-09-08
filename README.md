<p align="center">
  <img src="https://raw.githubusercontent.com/haydenkz/meshcore-g2/main/assets/meshcore-g2.png" width="88" height="88" alt="MeshCore G2 logo" />
</p>

<h1 align="center">MeshCore G2</h1>

<p align="center">MeshCore on Even Realities G2 glasses.</p>

<p align="center">
  <a href="https://github.com/haydenkz/meshcore-g2/actions/workflows/ci.yml"><img src="https://github.com/haydenkz/meshcore-g2/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Android-8%2B-3DDC84?logo=android&amp;logoColor=white" alt="Android 8 or newer" />
  <img src="https://img.shields.io/badge/Even-G2-3CFA44" alt="Even G2" />
</p>

The Android helper connects to a MeshCore companion over Bluetooth. The Even Hub
plugin reads the helper on the same phone and displays channel messages and direct
message chats on the glasses. Received messages are saved by the helper.

## Setup

Requires Android 8+, Even App 2.2.10+, G2 glasses, and a radio running MeshCore
companion BLE firmware.

1. Download a matching pair from a successful [CI run](https://github.com/haydenkz/meshcore-g2/actions/workflows/ci.yml)
   under **Artifacts → meshcore-g2-preview-…**. Extract the archive.
2. Install the Android APK. Install the Even plugin through your Even beta
   invitation or [developer project](https://github.com/haydenkz/meshcore-g2/blob/main/CONTRIBUTING.md#test-on-hardware).
   Upload the `.ehpk` file to Even portal and set it as the beta release.
3. Open **MeshCore G2** on Android, grant its permissions, and tap **Find a radio**.
   Once connected, tap **Copy connection key**. Paste it into **MeshCore G2** in
   the Even App and tap **Link phone helper**.

Keep the helper running while using the glasses.

On the glasses, tap then hold to switch between **Channels** and **Direct messages**.
Swipe between message cards, tap to open, and double-tap to go back. TX/RX show the radio's
sent and received packet totals; unavailable counts appear as dashes.

## Development

To run the Even app locally with Node 24:

```sh
npm ci
npm run dev
```

See [CONTRIBUTING.md](https://github.com/haydenkz/meshcore-g2/blob/main/CONTRIBUTING.md)
for simulator setup and testing on glasses or Android.

[Releases](https://github.com/haydenkz/meshcore-g2/releases) ·
[Changelog](https://github.com/haydenkz/meshcore-g2/blob/main/CHANGELOG.md) ·
[Issues](https://github.com/haydenkz/meshcore-g2/issues)

Independent project; not affiliated with MeshCore or Even Realities.
[Third-party notices](https://github.com/haydenkz/meshcore-g2/blob/main/apps/even/public/THIRD_PARTY_NOTICES.md).
