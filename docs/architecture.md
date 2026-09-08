# Connection architecture

The Android helper's foreground service owns scanning, pairing, the BLE command
queue, and a loopback-only HTTP server. The Even WebView polls
`http://127.0.0.1:8765/v1/status` with a random connection key in Authorization.
`/health` is public and contains no telemetry. The Even SDK does not expose
arbitrary BLE companion access.

The source converts helper replies into transport-independent snapshots; the HUD
renders identity, connection state, and battery voltage. Disconnects clear stale
telemetry. Saved links use Even host storage.

Preserve these integration details:

- Bind browser fetch to its global receiver to avoid Chromium Illegal invocation.
- Reply to CORS preflights, but never gzip an empty NanoHTTPD 204 response.
- Serialize BLE commands and wait for write acknowledgement plus the expected
  reply. MTU 93 covers the initial handshake; this does not establish support for
  every larger message frame. MTU 176 is valid for this handshake.
- Keep helper access authenticated and restricted to loopback. Reachable health
  does not establish that the saved key and status request succeed.

Sources: [Even architecture](https://hub.evenrealities.com/docs/get-started/architecture),
[Even networking](https://hub.evenrealities.com/docs/build/networking),
[MeshCore companion firmware](https://github.com/meshcore-dev/MeshCore/tree/main/examples/companion_radio).
