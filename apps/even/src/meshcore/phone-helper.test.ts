import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  PhoneHelperSource,
  HelperAuthenticationError,
  PHONE_HELPER_URL,
  parsePhoneSnapshot,
} from './phone-helper.ts'

const connected = {
  schema: 1,
  state: 'connected',
  detail: 'Connected',
  name: 'Trail radio',
  batteryMillivolts: 3700,
  protocolVersion: 8,
}

test('packet totals preserve unsigned radio counters, allow older helpers, and clear on disconnect', () => {
  const snapshot = parsePhoneSnapshot({
    ...connected,
    packetsSent: 0,
    packetsReceived: 4294967295,
  })
  assert.equal(snapshot.packetsSent, 0)
  assert.equal(snapshot.packetsReceived, 4294967295)
  assert.equal(parsePhoneSnapshot(connected).packetsSent, undefined)
  for (const value of [-1, 4294967296, 1.5, '12', null]) {
    assert.equal(
      parsePhoneSnapshot({ ...connected, packetsSent: value }).packetsSent,
      undefined,
    )
  }
  const offline = parsePhoneSnapshot({
    ...connected,
    state: 'disconnected',
    packetsSent: 12,
    packetsReceived: 34,
  })
  assert.equal(offline.packetsSent, undefined)
  assert.equal(offline.packetsReceived, undefined)
})

test('connected status requires complete radio information and preserves voltage units', () => {
  const snapshot = parsePhoneSnapshot(connected)
  assert.equal(snapshot.mode, 'live')
  assert.equal(snapshot.connection, 'connected')
  assert.equal(snapshot.batteryMillivolts, 3700)
  assert.throws(() =>
    parsePhoneSnapshot({ ...connected, batteryMillivolts: null }),
  )
  assert.throws(() =>
    parsePhoneSnapshot({ ...connected, protocolVersion: '8' }),
  )
  assert.throws(() =>
    parsePhoneSnapshot({ ...connected, batteryMillivolts: -1 }),
  )
})

test('pairing and service discovery never claim a connected radio', () => {
  for (const state of [
    'pairing',
    'connecting',
    'discovering',
    'subscribing',
    'initializing',
  ]) {
    const snapshot = parsePhoneSnapshot({ ...connected, state })
    assert.equal(snapshot.connection, 'connecting')
    assert.equal(snapshot.batteryMillivolts, undefined)
  }
})

test('disconnect removes stale telemetry and radio names cannot inject HUD lines', () => {
  const snapshot = parsePhoneSnapshot({
    ...connected,
    state: 'disconnected',
    name: 'Radio\nDEMO\u0000',
  })
  assert.equal(snapshot.connection, 'disconnected')
  assert.equal(snapshot.batteryMillivolts, undefined)
  assert.equal(snapshot.protocolVersion, undefined)
  assert.equal(snapshot.deviceName, 'Radio DEMO')
})

test('rejects malformed and incompatible helper responses', () => {
  for (const value of [
    null,
    [],
    'ready',
    { ...connected, schema: 2 },
    { ...connected, state: 'ready' },
  ]) {
    assert.throws(() => parsePhoneSnapshot(value))
  }
})

test('key is sent only as a header to the fixed loopback endpoint with redirects disabled', async () => {
  const key = 'ab'.repeat(32)
  const controller = new AbortController()
  const source = new PhoneHelperSource(key, async (url, options) => {
    assert.equal(url, PHONE_HELPER_URL)
    assert.equal(
      new Headers(options?.headers).get('Authorization'),
      `Bearer ${key}`,
    )
    assert.equal(options?.redirect, 'error')
    assert.equal(options?.credentials, 'omit')
    assert.equal(options?.cache, 'no-store')
    assert.equal(options?.signal, controller.signal)
    return Response.json(connected)
  })
  assert.equal(
    (await source.readSnapshot(controller.signal)).connection,
    'connected',
  )
})

test('invalid keys and unauthorized helper responses are actionable errors', async () => {
  assert.throws(() => new PhoneHelperSource('short'), /full connection key/)
  const source = new PhoneHelperSource(
    'ab'.repeat(32),
    async () => new Response('', { status: 401 }),
  )
  await assert.rejects(source.readSnapshot(), HelperAuthenticationError)
})

test('fetch keeps its browser receiver when called through the source', async () => {
  const source = new PhoneHelperSource('ab'.repeat(32), async function (
    this: unknown,
  ) {
    // Node fetch permits receivers that Chromium rejects before any request.
    assert.equal(this, globalThis)
    return Response.json(connected)
  })
  assert.equal((await source.readSnapshot()).connection, 'connected')
})
