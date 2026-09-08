# Validation

The initial pair was confirmed working by the hardware tester on 2026-09-08:
Even plugin 0.1.2, Android helper 0.2.1, Pixel 10 Pro XL / Android 17, Even App
2.2.10, BLE MTU 176, protocol 13, and real battery telemetry. The exact radio
firmware version was not recorded. This does not cover every Android or firmware
version, and it does not establish reliable locked-phone operation.

Automated tests cover protocol framing, helper authentication/CORS and reused
HTTP sockets, fetch receiver binding, link persistence, and HUD lifecycle.
Before merging, run both apps' checks from the README. Before a release, test:

- Pairing, identity, battery, disconnect/reconnect, and stale-data clearing.
- Saved links, forget/relink, denied permissions, and unavailable helpers.
- Glasses double-tap exit and subsequent launches.
- Background/foreground and five minutes locked with an Even beta build.

Record the exact app pair and hardware results in the PR. QR local testing stops
when the WebView backgrounds, so it cannot validate the locked-phone case.
