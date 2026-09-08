# MeshCore G2

Read your MeshCore companion's connection status and battery voltage on Even
Realities G2 glasses. This initial connection uses two apps on one Android phone:

- **MeshCore G2 Helper** in `android-helper/` owns the radio's BLE connection.
- **MeshCore G2** in this directory runs inside the Even App and reads the helper
  over an authenticated connection to `127.0.0.1` on the same phone.

No computer or cloud relay is needed while using the installed pair. Sending and
receiving messages are not implemented. Battery voltage is read when connecting.
iOS is not supported.

## Install and connect

The current tested pair is Even plugin **0.1.2** and helper **0.2.1**. You need an
Android 8+ phone, a radio running MeshCore companion BLE firmware, G2 glasses, and
Even App 2.2.10+.

1. Install the helper's debug APK from the build below. These early test APKs use
   a development signing key; no production release has been published yet.
2. Open MeshCore G2 Helper. Grant Nearby devices and notifications. Android 11
   and earlier also need Location permission and Location enabled for scanning.
3. Disconnect other MeshCore apps from the radio, tap **Find a radio**, and select
   your companion. Complete Android's pairing prompt if shown.
4. Install the Even package through your own Even developer project's private
   builds, or ask the maintainer for beta group access. Downloading an `.ehpk`
   alone does not install it. See [Even beta testing](https://hub.evenrealities.com/docs/test/beta-testing).
5. When the helper shows Connected and battery voltage, tap **Copy connection
   key**. Paste it into MeshCore G2 inside Even and tap **Link phone helper**.

The link is remembered in the Even host. Connection details provides a health and
key check, reconnect, forget, and demo controls. Keep the helper service running
while using the glasses. Reliable locked-phone operation still needs beta-build
hardware coverage.

## Build and validate

Install Node 24.19.0, JDK 17, Android SDK platform 35 and build-tools 35.0.0.
Set `ANDROID_HOME`, or add ignored `android-helper/local.properties` with your
`sdk.dir`. Accept Android SDK licenses.

```sh
npm ci
npm run check
npm run pack
cd android-helper
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Even installer is `meshcore-hud.ehpk`; the APK is
`android-helper/app/build/outputs/apk/debug/app-debug.apk`. On Windows use
`gradlew.bat`. Install both on the same phone. Never commit generated packages,
connection keys, wireless debugging codes, or signing keys.

For development, `npm run dev` serves the phone UI and `npm run simulate` opens
the official simulator. Run `npm run qr -- --url http://YOUR_COMPUTER_IP:5173` for
Even's local testing flow. A packaged Even beta build is needed for background
and locked-phone testing.

See [architecture](docs/architecture.md), [development](docs/development.md), and
[validation](docs/validation.md). CI currently checks and packages the Even app;
Android tests and builds are run locally until the paired CI workflow lands.

Independent project; not affiliated with MeshCore or Even Realities. The Even
starter's license is in [THIRD_PARTY_NOTICES.md](public/THIRD_PARTY_NOTICES.md).
