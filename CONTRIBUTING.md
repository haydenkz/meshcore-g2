# Contributing

Start with [README.md](README.md) for installation and current capabilities.
This is one product with two applications and one coordinated release version.
There is one npm lockfile for the Even app/tooling and one Gradle wrapper for Android.

## Repository layout

| Location                  | Responsibility                                                        |
| ------------------------- | --------------------------------------------------------------------- |
| `apps/even/`              | Even manifest, Vite/TypeScript config, phone page, and glasses HUD.   |
| `apps/even/src/meshcore/` | Helper client, saved link, and transport-independent snapshots.       |
| `apps/android/`           | Native BLE/HTTP foreground service and Jetpack Compose interface.     |
| `assets/`                 | Shared green app icon and monochrome Even portal artwork.             |
| `scripts/`                | Shared Android commands and paired artifact validation/packaging.     |
| `.github/workflows/`      | Checks, preview installers, and signed draft releases.                |
| `dist/`                   | Generated web builds, packages, and installer bundle; ignored by Git. |

## Local setup

Install Node **24.19.0** from `.nvmrc`, **JDK 17**, and Android SDK platform 35,
build-tools 35.0.0, and platform-tools. Android Studio's SDK Manager can install
these packages. Accept the Android SDK licenses.

```sh
npm ci
npm run dev
```

The Even development server listens on port 5173. In another terminal,
`npm run simulate` opens the official simulator. Node is development tooling;
neither installed phone app needs a Node process.

For Android, open `apps/android` in Android Studio, or set `ANDROID_HOME` to your
SDK directory. You can instead create ignored `apps/android/local.properties`
with `sdk.dir=/absolute/path/to/android-sdk`. Ensure `JAVA_HOME` points to JDK 17.

| Command, from the repository root | Result                                                                                      |
| --------------------------------- | ------------------------------------------------------------------------------------------- |
| `npm run check`                   | All Even/repository checks plus Android tests, lint, and debug build.                       |
| `npm run check:even`              | Version checks, formatting, ESLint, TypeScript, Node tests, and Vite build.                 |
| `npm run check:android`           | Gradle unit tests, Android lint, and debug APK.                                             |
| `npm run format`                  | Format supported repository files; native source follows existing style.                    |
| `npm run pack:even`               | Even package and build metadata in `dist/packages/`.                                        |
| `npm run pack`                    | Build both development apps and collect installers, guide, and checksums in `dist/bundle/`. |
| `npm run preview`                 | Serve the built Even app locally.                                                           |

For direct Gradle work: `cd apps/android`, then `./gradlew TASK` (`gradlew.bat TASK`
on Windows). The root Android commands select the correct wrapper automatically.
Run the checks before packaging a candidate for other people.

Development APKs have application ID `io.github.haydenkz.meshcorehelper.debug`
and launcher name **MeshCore G2 Helper (Dev)**. Release APKs use
`io.github.haydenkz.meshcorehelper`. This keeps routine developer installs separate
from releases. Both listen on the same phone-local port, so stop one helper before
starting the other. A new CI runner may use a different debug signing key; uninstall
the previous Dev APK if Android rejects its update, then link again.

## Test on hardware

For rapid iteration, start `npm run dev`, then run:

```sh
npm run qr -- --url http://YOUR_COMPUTER_IP:5173
```

Scan in the Even App's developer flow. The phone must reach that address; a shared
LAN or Tailscale works for development. The production installation uses packaged
files and runs entirely on the phone.

To test the packaged plugin in your own Even project:

1. Run `npm run check` and `npm run pack`.
2. Install the APK from `dist/bundle/` on the phone.
3. Sign into the [Even developer portal](https://hub.evenrealities.com/login) with
   the same account used in the Even App. Upload the `.ehpk` under your project's
   **Private builds**.
4. In the Even App's developer-enabled Even Hub tab, go to **Me → Apps → Private
   builds** and install it. If creating a separate project, use a unique package ID
   you own in `apps/even/app.json`; an already-claimed ID cannot be reused.
5. Follow the README to connect the radio and link the two apps.

Private builds are limited to their owner's account. For other testers, create a
**Beta group** in the portal, add their Even account emails, upload the matching
`.ehpk` under **Builds**, and assign it to that group. Users install from
**Me → Beta tester**. Do not publish testers' emails in issues.
See [private testing](https://hub.evenrealities.com/docs/test/private-testing) and
[beta testing](https://hub.evenrealities.com/docs/test/beta-testing).

USB or wireless ADB is optional for developer installation: `adb install -r
/path/to/helper.apk`. Wireless debugging has separate pairing and connection
ports: use `adb pair PHONE_IP:PAIRING_PORT`, then `adb connect PHONE_IP:DEBUG_PORT`.
Never commit pairing codes, helper keys, keystores, or machine-specific addresses.

The initial pair was confirmed working by the hardware tester on **2026-09-08**:
Pixel 10 Pro XL / Android 17, Even App 2.2.10, BLE MTU 176, protocol 13, and real
battery telemetry. That result does not cover every Android version or firmware.
For each release candidate, record the app pair/version, phone, Even App, radio
firmware, and results in the PR or release notes. Check:

- Pairing and readable radio identity/battery on both phone apps and glasses.
- Disconnect/reconnect and loss of helper access without stale battery data.
- Remembered links after reopening; forget/relink and denied permissions.
- Glasses double-tap exit and subsequent app launches.
- Background/foreground and at least five minutes locked, using an Even beta build.

Unit tests cover protocol, HTTP framing/authentication, fetch binding, link
persistence, HUD lifecycle, and bundle consistency. They do not prove BLE timing
or Even's native-host behavior. The simulator is useful for rendering/input, but
real-radio and locked-phone checks remain manual.

## Connection design

The native foreground service owns BLE; the Even WebView reads status from
`http://127.0.0.1:8765/v1/status`. The helper binds only to loopback and requires a
random connection key in the Authorization header. The Even host stores that key
for later launches. `/health` is public and contains no radio telemetry.

The Even SDK does not expose arbitrary radio BLE access. The plugin still obeys
Even's network whitelist and browser CORS. Preserve these protocol details:

- Bind browser `fetch` to its global receiver. Calling native fetch as a source
  instance method throws before any HTTP request is sent.
- Respond to Authorization preflights. Keep compression disabled for small helper
  replies: NanoHTTPD 2.3.1 otherwise gzip-encodes empty 204 responses and corrupts
  the next response on a persistent connection.
- Serialize BLE handshake commands and wait for write acknowledgement plus the
  expected reply. MTU 93 covers the initial handshake; that does not establish
  support for all larger message frames. Do not blame firmware for MTU 176.
- Clear telemetry on disconnect and retain voltage in millivolts internally.

Sources: [Even architecture](https://hub.evenrealities.com/docs/get-started/architecture),
[Even networking](https://hub.evenrealities.com/docs/build/networking),
[MeshCore companion firmware](https://github.com/meshcore-dev/MeshCore/tree/main/examples/companion_radio).

## App icon

`assets/meshcore-g2.png` is the shared icon. Vite imports it into the phone page
and favicon; Android copies it into generated resources for its adaptive launcher
icon. Edit the shared source rather than the build output.

The pixelated broadcast symbol was generated with the built-in image tool using
[this MeshCore logo](https://play-lh.googleusercontent.com/7oguUQWSITt0-s4nkEHVNtlj3nPylr6svq4ksZ_HRTxM3Hcwhoib1WmHEhi4zxNowB-ZqoC1DREv2uwDQX6X)
as the reference. The prompt calls for its central disc and two arcs per side,
crisp square pixel steps, a centered symmetric silhouette, Even display green
(`#3CFA44`) on near-black, generous launcher margins, and no text or effects.
The monochrome variant uses the same composition with a white symbol on black.

Even's [store listing rules](https://hub.evenrealities.com/docs/ship/app-submission)
require grayscale foreground and background artwork. Upload
`assets/even-icon-foreground.png` and `assets/even-icon-background.png` to those
portal fields. These are portal metadata, not `app.json` fields; packing an
`.ehpk` does not update the store icon. The green version is used on the phone.

## CI and releases

CI runs on PRs, `main`, and manual dispatch. Even and Android checks run separately;
**Ready** passes only when both succeed and their paired bundle validates. Use
**Ready** as the required branch-protection check. Android reports are retained
for failures. The `meshcore-g2-preview-…` artifact contains both test installers,
`INSTALL.md`, `bundle.json`, and `SHA256SUMS` for 30 days.

Actions are pinned to full commit SHAs. Dependabot groups weekly npm, Gradle, and
Actions updates. PR builds have read-only repository permissions and no signing
secrets. The Gradle action validates the wrapper and caches dependencies; signing
builds disable that cache.

### Versioning

Both apps use `package.json`'s product version. Before a release, update:

1. `package.json` version and `apps/even/app.json` version to the same `x.y.z`.
2. `package.json` → `config.androidVersionCode` to a larger integer. Android reads
   both values directly; never reuse an Android version code for a new release.
3. Run `npm install --package-lock-only --ignore-scripts` to synchronize the lockfile.
4. Update `CHANGELOG.md` and run `npm run check`.

CI rejects mismatched versions, SDK stamps, APK variants, checksums, and source
revisions. `bundle.json` records both application versions and the source commit.
Release bundles require a clean worktree. Older test builds used separate versions
(Even 0.1.2 and helper 0.2.1); coordinated versioning starts with 0.2.2.

### One-time release signing setup

Public APKs need a stable, private signing key so later versions can update the
installed application. A debug certificate is unsuitable for releases. See
[Android signing](https://developer.android.com/studio/publish/app-signing.html).

Create and back up a release keystore **outside this repository**, keeping its
passwords in your password manager. For example, JDK's `keytool` can create one
interactively:

```sh
keytool -genkeypair -storetype JKS -keystore /secure/path/meshcore-g2-release.jks \
  -alias meshcore-g2 -keyalg RSA -keysize 3072 -validity 10000
```

Create a GitHub Actions environment named **release**, restrict it to `main`, and
configure these environment secrets:

| Secret                       | Value                                                 |
| ---------------------------- | ----------------------------------------------------- |
| `MESHCORE_KEYSTORE_BASE64`   | Keystore file encoded as a single-line base64 string. |
| `MESHCORE_KEYSTORE_PASSWORD` | Keystore password.                                    |
| `MESHCORE_KEY_ALIAS`         | Signing key alias.                                    |
| `MESHCORE_KEY_PASSWORD`      | Signing key password.                                 |

For local signed builds, set `MESHCORE_KEYSTORE_PATH` to the keystore's absolute
path and the other three signing variables above, then run
`node scripts/android.mjs release`. Missing signing configuration fails the
release build; it never falls back to a debug signature or an unsigned download.

Early hardware-test APKs used the release app ID with a local debug key. Their
first migration to a properly signed release requires uninstalling that old
helper and linking again. Normal signed release updates keep the key. Do not
replace or rotate the release signing key between versions.

### Prepare and distribute a release

1. Merge the tested change to `main`. In Actions, run **Prepare release** from
   `main`. It reruns CI, builds and verifies a signed APK, and creates a **draft**
   GitHub release `vVERSION` containing the paired installers and checksums.
2. Download that exact candidate pair. Upload its `.ehpk` to Even and assign it to
   the beta group. Even distribution has no scripted install path; CI does not
   upload to Even, Google Play, or publish the GitHub draft.
3. Complete the hardware checks above. Add the results and product changes to the
   draft's release notes, then publish the draft when ready. If a workflow failed
   after creating a draft, remove only that unfinished draft before retrying;
   published version tags are not overwritten.

GitHub Releases is the durable download location; CI artifacts are temporary
previews. Both installers belong to one release, even when only one app changes.
Future public Even Hub distribution should link to its catalog entry in the README.
The remaining first-release setup is maintainer signing credentials and Even beta
access, not a computer or relay running alongside the installed apps.
