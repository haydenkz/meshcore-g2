# Android helper API

Build a dashboard, archive messages, or integrate MeshCore history into another app
using the Android helper's local HTTP API. Clients do not need the Even App or G2
glasses. The helper owns the BLE connection and saves radio history on the phone.

**Base URL:** `http://127.0.0.1:8765`  
**Contract:** `/v1` endpoints return JSON with `"schema": 1`.  
**Machine-readable reference:** [OpenAPI 3.0 document](openapi.json), suitable for
import into API tools and client generators.

## Connect a client

Follow the [API setup in the README](../README.md#use-the-api) to start the helper
and obtain a connection key. The HTTP service must be running, even when you only
want saved history. Disconnecting the radio leaves history readable while the
service runs; stopping the helper makes the API unavailable.

The server binds only to IPv4 loopback on the Android device. An app on the same
phone uses the base URL above. A laptop, another phone, or a cloud service cannot
connect directly to the phone's Wi-Fi address. Requests must have the exact
`Host: 127.0.0.1:8765` header; use `127.0.0.1`, not `localhost` or `[::1]`.

### Develop from a computer

With Android debugging connected, forward the computer's port to the helper:

```sh
adb devices
adb -s DEVICE_SERIAL forward --no-rebind tcp:8765 tcp:8765
curl --noproxy 127.0.0.1 --max-time 4 http://127.0.0.1:8765/health
```

Replace `DEVICE_SERIAL` with the serial from `adb devices`. Port 8765 must be free
on the computer. Keeping the same port preserves the required Host header. Remove
this forwarding rule when finished:

```sh
adb -s DEVICE_SERIAL forward --remove tcp:8765
```

See Android's [port forwarding documentation](https://developer.android.com/tools/adb#forwardports).
Forwarding does not start the helper or connect its radio.

### Authentication

Every `GET /v1/*` request requires:

```http
Authorization: Bearer YOUR_CONNECTION_KEY
```

Use the exact copied key: 64 lowercase hexadecimal characters. The `Bearer `
prefix is case-sensitive. Do not put the key in URLs, logs, screenshots, or source
control. It grants access to saved messages and history across all radios used by
that helper installation. The development and release editions have separate
keys and storage; run only one helper edition at a time.

`GET /health` and preflight `OPTIONS` requests to supported `/v1/*` routes do not
require authentication. There is no HTTP endpoint to issue, retrieve, or rotate
a key. Clearing the helper's app data removes its key and saved history.

For the examples below, read the key into an environment variable in **Bash** so
it is not written into your shell history:

```bash
read -r -s -p 'Connection key: ' MESHCORE_KEY
printf '\n'
export MESHCORE_KEY
curl --noproxy 127.0.0.1 --max-time 4 --fail-with-body \
  -H "Authorization: Bearer $MESHCORE_KEY" \
  http://127.0.0.1:8765/v1/status
```

## Endpoints

| Method  | Path                       | Result                                                       |
| ------- | -------------------------- | ------------------------------------------------------------ |
| GET     | `/health`                  | Public reachability check; plain text.                       |
| GET     | `/v1/status`               | Current companion connection state and telemetry.            |
| GET     | `/v1/messages`             | Saved channel messages or messages from one DM conversation. |
| GET     | `/v1/chats`                | DM conversations that have at least one saved message.       |
| GET     | `/v1/adverts`              | Latest saved advert observation per radio and node.          |
| OPTIONS | Any supported `/v1/*` path | Browser preflight; HTTP 204 with no body.                    |

The API is read-only. It has no send-message, connect, disconnect, configuration,
full contact-directory, raw packet-history, WebSocket, or server-sent event
endpoint. Additional screens and data in the Android UI are not automatically
available through HTTP. `/v1/adverts` exposes a public-key prefix, not the full
advertised node key.

### GET /health

Returns HTTP 200 and this plain-text body while the service is reachable:

```text
MeshCore phone helper is reachable. Return to the Even App and link it with your HUD key.
```

The reference to the Even App is informational; other clients use the same API.
A successful health check says nothing about radio connectivity or authentication.

### GET /v1/status

Example response:

```json
{
  "schema": 1,
  "state": "connected",
  "detail": "BLE companion connected (MTU 247).",
  "name": "Trail companion",
  "protocolVersion": 8,
  "batteryMillivolts": 3840,
  "packetsSent": 120,
  "packetsReceived": 845
}
```

| Field               | Type            | Meaning                                                                                                         |
| ------------------- | --------------- | --------------------------------------------------------------------------------------------------------------- |
| `schema`            | integer         | Response format version; currently `1`.                                                                         |
| `state`             | string          | `disconnected`, `pairing`, `connecting`, `discovering`, `subscribing`, `initializing`, `connected`, or `error`. |
| `detail`            | string          | Human-readable status; do not parse it as a stable error code.                                                  |
| `name`              | string          | Companion name; may be empty before connection.                                                                 |
| `protocolVersion`   | integer or null | Negotiated companion protocol version; not the HTTP schema version.                                             |
| `batteryMillivolts` | integer or null | Companion battery voltage in mV.                                                                                |
| `packetsSent`       | integer or null | Radio-reported cumulative TX count, not an HTTP-request count.                                                  |
| `packetsReceived`   | integer or null | Radio-reported cumulative RX count, not the number of saved messages.                                           |

Treat telemetry as available only when `state` is `connected`. Packet counts may
still be null if the firmware cannot provide them, and can reset when the radio
restarts. Intermediate states indicate connection setup, not a usable radio yet.
Earlier helper builds may omit packet counts; treat missing counts as unavailable.

### GET /v1/messages

| Query parameter | Required     | Meaning                                                                                                     |
| --------------- | ------------ | ----------------------------------------------------------------------------------------------------------- |
| `kind`          | No           | `channel` (default) or `direct`; case-sensitive.                                                            |
| `peer`          | For `direct` | Complete conversation ID. Omit for `channel` to read channel messages across all saved radios and channels. |
| `before`        | No           | Exclusive cursor for older messages; use the last item's `id`.                                              |

A conversation ID identifies both the companion radio and its channel or peer:

- Channel: `<64-character radio public key>:<channel index>`. The index is 1–3 decimal digits, e.g. `0`.
- DM: `<64-character radio public key>:<12-character remote public-key prefix>`.

All hexadecimal characters must be lowercase. Prefer IDs returned in message
`conversationId` or chat `id` fields over constructing them yourself. A bare
channel number or bare remote key is not a valid `peer` value. The API does not
list configured channels that have no messages.

Read the newest channel messages:

```sh
curl --noproxy 127.0.0.1 --max-time 4 --fail-with-body \
  -H "Authorization: Bearer $MESHCORE_KEY" \
  'http://127.0.0.1:8765/v1/messages?kind=channel'
```

Example response with synthetic identifiers:

```json
{
  "schema": 1,
  "items": [
    {
      "id": 42,
      "kind": "channel",
      "conversationId": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:0",
      "conversationName": "Public",
      "senderName": "Alice",
      "text": "Meet at the trailhead",
      "sentAt": 1788891900000,
      "receivedAt": 1788891960000,
      "direction": "in",
      "delivery": "received"
    }
  ],
  "hasMore": false
}
```

| Item field         | Type    | Meaning                                                                                                                         |
| ------------------ | ------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `id`               | integer | Stable message ID within this helper's database.                                                                                |
| `kind`             | string  | `channel` or `direct`.                                                                                                          |
| `conversationId`   | string  | ID for subsequent filtered history requests.                                                                                    |
| `conversationName` | string  | Saved name, or channel/key-prefix fallback.                                                                                     |
| `senderName`       | string  | Display name; outgoing messages use `You`. This is not a full sender identity or public key.                                    |
| `text`             | string  | Saved message text. Render as text, not HTML.                                                                                   |
| `sentAt`           | integer | Message timestamp, milliseconds since Unix epoch; radio/sender clocks can differ from the phone.                                |
| `receivedAt`       | integer | Time the phone saved the message, milliseconds since Unix epoch. For outgoing rows, this is when they entered the local outbox. |
| `direction`        | string  | `in` or `out`. Earlier builds may omit it; default to `in`.                                                                     |
| `delivery`         | string  | Delivery state below. Earlier builds may omit it; default to `received`.                                                        |

| Delivery       | Meaning                                                                            |
| -------------- | ---------------------------------------------------------------------------------- |
| `received`     | Saved incoming message.                                                            |
| `queued`       | Outgoing message is waiting locally.                                               |
| `sending`      | Send command is in progress.                                                       |
| `sent`         | Radio accepted the send; this does not establish remote receipt.                   |
| `awaiting_ack` | Waiting for a matching acknowledgement.                                            |
| `delivered`    | A matching acknowledgement was received.                                           |
| `failed`       | The send failed.                                                                   |
| `unconfirmed`  | Receipt could not be confirmed, including interruption or acknowledgement timeout. |

Messages are ordered by decreasing `id` (phone insertion order), not by `sentAt`.
The same message row can change delivery state without receiving a new ID. A
well-formed conversation with no saved messages returns an empty page, not 404.

### GET /v1/chats

Returns only DM conversations with saved incoming or outgoing messages. It is not
a list of every discovered user. Accepts optional `before`; use the last item's
**`lastMessageId`**, not its string `id`, for the next page.

```json
{
  "schema": 1,
  "items": [
    {
      "id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:112233445566",
      "name": "Alice",
      "lastMessageId": 43,
      "updatedAt": 1788891960000,
      "preview": "See you there"
    }
  ],
  "hasMore": false
}
```

| Item field      | Type    | Meaning                                                                       |
| --------------- | ------- | ----------------------------------------------------------------------------- |
| `id`            | string  | Complete DM conversation ID; pass it as `peer` to `/v1/messages?kind=direct`. |
| `name`          | string  | Saved contact name or key-prefix fallback.                                    |
| `lastMessageId` | integer | Latest saved message ID; also the chat pagination cursor.                     |
| `updatedAt`     | integer | Latest message's phone save time, milliseconds since Unix epoch.              |
| `preview`       | string  | Latest message text, incoming or outgoing.                                    |

Chats are ordered by decreasing `lastMessageId`. To read one, replace the synthetic
ID below with an `id` from your `/v1/chats` response:

```sh
MESHCORE_CHAT='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:112233445566'
curl --noproxy 127.0.0.1 --max-time 4 --fail-with-body --get \
  -H "Authorization: Bearer $MESHCORE_KEY" \
  --data-urlencode 'kind=direct' \
  --data-urlencode "peer=$MESHCORE_CHAT" \
  http://127.0.0.1:8765/v1/messages
```

### GET /v1/adverts

Returns the latest observation for each `(companion radio, advertised full public
key)` pair. Repeated adverts update the existing row's time; this is not an append-only
log of every advert. Accepts optional `before`; use the last item's `id`.

```json
{
  "schema": 1,
  "items": [
    {
      "id": 17,
      "publicKeyPrefix": "112233445566",
      "name": "Hill repeater",
      "nodeType": "Repeater",
      "receivedAt": 1788891960000
    }
  ],
  "hasMore": false
}
```

| Item field        | Type    | Meaning                                                                                                    |
| ----------------- | ------- | ---------------------------------------------------------------------------------------------------------- |
| `id`              | integer | Stable advert row ID in this helper's database; use it to distinguish observations.                        |
| `publicKeyPrefix` | string  | First 12 lowercase hex characters of the advertised node's public key. Prefixes are not unique identities. |
| `name`            | string  | Latest saved node name or key-prefix fallback.                                                             |
| `nodeType`        | string  | `Chat node`, `Repeater`, `Room server`, `Sensor`, or `Unknown node`.                                       |
| `receivedAt`      | integer | Last known detection time, milliseconds since Unix epoch.                                                  |

Detection time comes from the radio's last-advert timestamp during contact sync,
or the phone's receipt time for a live advert push. Sync can therefore populate
adverts heard before the phone connected. Missing radio timestamps are not
replaced with the time of sync. A later stale sync cannot move detection backwards.

Order is decreasing `receivedAt`, then decreasing `id`. The `before` cursor looks
up that row's detection time; do not substitute a timestamp or decrement an ID.
The response does not include the companion identity or full advertised public
key, so two observations can have the same prefix and name.

## Pagination and polling

All three history endpoints return `{"schema":1,"items":[],"hasMore":false}` when
empty and at most **16 items** per page. The page size is fixed; there is no
`limit`, `offset`, `since`, or date-range parameter.

1. Request the first page without `before`.
2. Process its items. If `hasMore` is false, stop.
3. Pass the final item's cursor in `before`, keeping the same endpoint and filters.
4. Repeat until `hasMore` is false. Treat `hasMore: true` with no items as an invalid response.

| Endpoint       | Next-page cursor                                                |
| -------------- | --------------------------------------------------------------- |
| `/v1/messages` | Last item's `id`.                                               |
| `/v1/chats`    | Last item's `lastMessageId`.                                    |
| `/v1/adverts`  | Last item's `id`, interpreted using its current detection time. |

A supplied `before` must be a positive base-10 integer representable as a signed
64-bit integer. Cursors are exclusive. Keep them with their endpoint and helper
installation; do not reuse them after clearing app data. JSON IDs are 64-bit
integers, so clients must avoid rounding values beyond their language's exact
integer range.

Pagination is not a frozen snapshot. Chats and adverts can move to the front as
new data arrives, including while you page through older results. Deduplicate by
row/conversation ID and periodically reread from the first page. For message
archiving, walk backward from the newest page until reaching a previously saved
ID; reading only one page can miss messages when more than 16 arrive between
polls. Refresh recent outgoing rows to pick up delivery changes too.

The bundled Even client requests status immediately on linking, then waits
**1.5 seconds after each status request finishes** before the next attempt. Each
cycle also refreshes the currently viewed history endpoint unless a history
request is already running. Requests use a 4-second timeout. Navigation can
trigger an additional immediate history read.

Other clients can start with the same interval, avoid overlapping reads, slow
retries when the helper is unavailable, and stop polling when their view or task
is inactive. The API has no push subscription or documented rate-limit contract.

## Client examples

### JavaScript: read status and the latest channel messages

This function works with a browser's native `fetch` or Node 24. Pass the copied key
from your client's settings; the invocation shown uses the environment variable
from the Bash example. Keep browser fetch bound to its global receiver.

```javascript
const helperFetch = globalThis.fetch.bind(globalThis)

async function readHelper(path, key) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 4000)
  try {
    const response = await helperFetch(`http://127.0.0.1:8765${path}`, {
      headers: { Authorization: `Bearer ${key}` },
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new Error(
        `Helper HTTP ${response.status}: ${await response.text()}`,
      )
    }
    const data = await response.json()
    if (data.schema !== 1) throw new Error('Unsupported helper schema')
    return data
  } finally {
    clearTimeout(timeout)
  }
}

// Node: save as client.mjs and run after setting MESHCORE_KEY.
const key = process.env.MESHCORE_KEY
if (!key) throw new Error('Set MESHCORE_KEY first')
console.log(await readHelper('/v1/status', key))
console.log(await readHelper('/v1/messages?kind=channel', key))
```

### Python: export saved channel messages

Uses only the Python 3 standard library. Save as `export_messages.py`, set
`MESHCORE_KEY`, then run `python3 export_messages.py > messages.json`. The output is
newest first. To export one DM, change the initial query to
`{"kind": "direct", "peer": "<id from /v1/chats>"}`.

```python
import json
import os
from urllib.parse import urlencode
from urllib.request import ProxyHandler, Request, build_opener

key = os.environ["MESHCORE_KEY"]
opener = build_opener(ProxyHandler({}))  # Keep loopback requests off proxies.
query = {"kind": "channel"}
messages = {}

while True:
    url = "http://127.0.0.1:8765/v1/messages?" + urlencode(query)
    request = Request(url, headers={"Authorization": "Bearer " + key})
    with opener.open(request, timeout=4) as response:
        page = json.load(response)
    if page["schema"] != 1:
        raise ValueError("Unsupported helper schema")
    for item in page["items"]:
        messages[item["id"]] = item
    if not page["hasMore"]:
        break
    if not page["items"]:
        raise ValueError("Invalid empty continuation page")
    cursor = page["items"][-1]["id"]
    if "before" in query and cursor >= query["before"]:
        raise ValueError("Pagination cursor did not advance")
    query["before"] = cursor

print(json.dumps(sorted(messages.values(), key=lambda item: item["id"], reverse=True), indent=2))
```

## Browser and native Android clients

All responses include `Cache-Control: no-store` and these CORS headers:

```http
Access-Control-Allow-Origin: *
Access-Control-Allow-Methods: GET, OPTIONS
Access-Control-Allow-Headers: Authorization
Access-Control-Allow-Private-Network: true
```

Preflights on supported API routes return 204 with no body. Use an Authorization
header and omit cookies; CORS does not replace key authentication. Browser or
WebView rules can still restrict loopback HTTP or local-network access. Do not
assume that a hosted web page can reach this URL in every runtime. Test in the
browser or host app that will run your client.

A native Android client needs `android.permission.INTERNET` and a network policy
that permits HTTP to this loopback address. Scope any necessary cleartext
exception to `127.0.0.1` in your app's network security configuration; see Android's
[network security configuration reference](https://developer.android.com/privacy-and-security/security-config#CleartextTrafficPermitted).
The helper's Android permissions and network settings do not grant access on
behalf of another app.

Any authenticated API read updates the helper's recent-reader indicator, which
the Android UI labels as an Even plugin link. That indicator is not proof that a
client is the Even App.

## Errors and compatibility

Successful `/v1/*` reads use `application/json`. Errors use **plain text**, not a
JSON error object; check the HTTP status before decoding JSON.

| HTTP status | Body                             | Meaning/action                                                                         |
| ----------- | -------------------------------- | -------------------------------------------------------------------------------------- |
| 400         | `Invalid history query`          | Invalid `before`, unsupported `kind`, or missing/malformed `peer`.                     |
| 401         | `Invalid HUD key`                | Missing or incorrect Authorization header; copy the key from this helper installation. |
| 404         | `Not found`                      | Unsupported path, incorrect Host header, or an older helper without that endpoint.     |
| 405         | `Read only`                      | A method other than GET/OPTIONS was used on a supported API route.                     |
| 500         | `Unable to read message history` | The helper could not complete the read; retry later and check phone storage.           |

A connection refusal or timeout is a transport failure, not a JSON response. Check
that the helper service is running, the URL uses the correct loopback address,
and any adb forwarding points to the intended device. An empty history response
can mean there is no saved data yet; check `/v1/status` to distinguish that from
radio disconnection.

Support `schema: 1`, tolerate additional response fields, and treat unfamiliar
status labels conservatively. The API version and response schema are separate
from the app release version. This reference describes the implementation in this
checkout; older installed APKs can have fewer endpoints or fields.

Source of truth: [HTTP routing and authentication](../apps/android/app/src/main/java/io/github/haydenkz/meshcorehelper/StatusServer.java),
[history queries](../apps/android/app/src/main/java/io/github/haydenkz/meshcorehelper/MessageStore.java),
[status production](../apps/android/app/src/main/java/io/github/haydenkz/meshcorehelper/HelperService.java),
and the [existing TypeScript client](../apps/even/src/meshcore/phone-helper.ts).
