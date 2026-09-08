# Development

See the [README](../README.md#build-and-validate) for exact setup and build commands.
The Even app uses Node 24.19.0 and the pinned npm lockfile. The native helper uses
JDK 17 and the Gradle wrapper in `android-helper/`.

Even transport code belongs in `src/meshcore/`; glasses rendering belongs in
`src/hud.ts`. The Android foreground service owns BLE and the local HTTP server.
Keep protocol and server fixes covered by meaningful regression tests.

Run `npm run check` and `npm run pack` for the Even app, and
`./gradlew testDebugUnitTest lintDebug assembleDebug` from `android-helper/`.
Simulator and browser checks complement real-radio testing; they do not prove
BLE timing or locked-phone behavior.

Wireless ADB uses separate pairing and connection ports. Pair and connect using
the addresses shown by Android, then `adb install -r /path/to/app-debug.apk`.
Keep those addresses, codes, helper keys, and local SDK paths out of Git.
