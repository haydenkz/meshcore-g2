# Contributing

Clone the whole repository; the Android project uses files from the repo root.
Run the npm commands below from that root directory.

## Even plugin

Install Node **24.19.0** (see `.nvmrc`), then start the development server:

```sh
npm ci
npm run dev
```

### Simulator

Leave the dev server running. In a second terminal, run:

```sh
npm run simulate
```

The Even simulator opens the Channels screen. No glasses are needed; live messages
require the phone helper and a radio. Edit files in `apps/even/src/` and save to
reload the app.

### Test on hardware

1. Pair your G2 glasses with the Even App **2.2.10+** and
   [enable Developer Mode](https://hub.evenrealities.com/docs/get-started/quickstart/hardware#enable-developer-mode).
2. Put your phone and computer on the same Wi-Fi network. Keep `npm run dev`
   running, then generate a QR code in another terminal:

   ```sh
   npm run qr -- --url http://YOUR_COMPUTER_IP:5173
   ```

3. In the Even App, open **Even Hub → Scan QR** and scan the code. Use your
   computer's network IP in the URL; `localhost` would point at the phone.
4. Save changes to reload them on the glasses. For live radio data, run the
   Android helper on the same phone and link it as described below.

See [Even's local testing guide](https://hub.evenrealities.com/docs/test/local-testing)
if the phone cannot reach the dev server.

For a packaged build, set a package ID you own in `apps/even/app.json`, then run
`npm run pack:even`. Upload the `.ehpk` from `dist/packages/` to your own project's
**Private builds** in the Even portal and follow the
[private installation steps](https://hub.evenrealities.com/docs/test/private-testing).
Local QR testing stops when the phone backgrounds the app; use
[beta testing](https://hub.evenrealities.com/docs/test/beta-testing) for locked-phone tests.

## Android helper

1. Install [Android Studio](https://developer.android.com/studio). In **SDK Manager**,
   install Android SDK Platform **35**, Build-Tools **35.0.0**, and Platform-Tools.
2. Open `apps/android` as the project. Set **Gradle JDK** to **JDK 17** in Android
   Studio's Gradle settings, then sync the project.
3. Select the **app** run configuration and the **debug** build variant.

### Emulator

1. Open **Device Manager → Create Virtual Device**, choose a phone, and download
   an Android **API 35** system image for your computer.
2. Select that virtual device in the toolbar and click **Run**.

Use the emulator to check the helper's UI. Use a real phone for MeshCore BLE
pairing and the complete glasses connection. See the
[Android emulator guide](https://developer.android.com/studio/run/emulator) for setup help.

### Phone

1. On an Android **8+** phone, enable **Developer options → USB debugging**.
2. Connect it to your computer by USB and accept the debugging prompt. Select
   the phone in Android Studio and click **Run**. You can also
   [pair over Wi-Fi](https://developer.android.com/studio/run/device).
3. Open **MeshCore G2**, tap **Profile** in the header, grant its permissions,
   and tap **Find a radio**.
   Disconnect other MeshCore apps from the radio first. Android 11 and earlier
   also need Location permission and Location enabled for scanning.
4. Once connected, tap **Copy connection key**. Paste it into **MeshCore G2** in
   the Even App and tap **Link phone helper**.

Keep the helper running, and stop any other installed helper edition first.

Run `npm run check:even` or `npm run check:android` for the app you changed; use
`npm run check` and `npm run pack` for changes across both apps. With an emulator
or phone connected, run `./gradlew connectedDebugAndroidTest` from `apps/android`
to test saved contacts, the 100-packet retention limit and restart persistence,
profile navigation, and chat history. These tests use synthetic messages and do not send radio messages. Notification
tests require notifications enabled for the development helper; they create and
remove only their own test alerts and message rows. UI captures are written to the
app's external `files/screenshots` directory.
Describe automated, simulator, and real-hardware results separately.
