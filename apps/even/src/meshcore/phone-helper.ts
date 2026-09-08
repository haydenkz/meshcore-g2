import type { MeshCoreSnapshot, MeshCoreSource } from './source.ts'
import { normalizeHelperKey } from './helper-link.ts'
import {
  parseAdverts,
  parseChats,
  parseMessages,
  type InboxSource,
} from './inbox.ts'

export const PHONE_HELPER_URL = 'http://127.0.0.1:8765/v1/status'
const connectingStates = [
  'pairing',
  'connecting',
  'discovering',
  'subscribing',
  'initializing',
]

export function parsePhoneSnapshot(value: unknown): MeshCoreSnapshot {
  if (typeof value !== 'object' || value === null)
    throw new Error('Invalid helper response.')
  const data = value as Record<string, unknown>
  if (
    data.schema !== 1 ||
    typeof data.state !== 'string' ||
    typeof data.detail !== 'string' ||
    typeof data.name !== 'string' ||
    data.name.length > 128 ||
    data.detail.length > 500
  ) {
    throw new Error('Update the phone helper: its response is not supported.')
  }
  const connection = connectingStates.includes(data.state)
    ? 'connecting'
    : data.state
  if (
    connection !== 'connecting' &&
    connection !== 'connected' &&
    connection !== 'disconnected' &&
    connection !== 'error'
  ) {
    throw new Error('The phone helper returned an unknown connection state.')
  }
  const snapshot: MeshCoreSnapshot = {
    mode: 'live',
    connection,
    deviceName: data.name
      .replace(/\p{Cc}/gu, ' ')
      .trim()
      .slice(0, 40),
    detail: data.detail,
  }
  // Never retain stale telemetry after a disconnect or accept a partial handshake.
  if (connection !== 'connected') return snapshot
  if (
    typeof data.batteryMillivolts !== 'number' ||
    !Number.isInteger(data.batteryMillivolts) ||
    data.batteryMillivolts < 0 ||
    data.batteryMillivolts > 65535 ||
    typeof data.protocolVersion !== 'number' ||
    !Number.isInteger(data.protocolVersion) ||
    data.protocolVersion < 0 ||
    data.protocolVersion > 255
  ) {
    throw new Error('The phone helper returned incomplete radio information.')
  }
  return {
    ...snapshot,
    batteryMillivolts: data.batteryMillivolts,
    protocolVersion: data.protocolVersion,
    packetsSent: packetCount(data.packetsSent),
    packetsReceived: packetCount(data.packetsReceived),
  }
}

function packetCount(value: unknown): number | undefined {
  return typeof value === 'number' &&
    Number.isInteger(value) &&
    value >= 0 &&
    value <= 0xffffffff
    ? value
    : undefined
}

export class HelperAuthenticationError extends Error {
  constructor() {
    super(
      'Connection key not accepted. Copy the current key from MeshCore G2 for Android and paste it below.',
    )
    this.name = 'HelperAuthenticationError'
  }
}

export class PhoneHelperSource implements MeshCoreSource, InboxSource {
  private readonly key: string
  private readonly fetcher: typeof fetch

  constructor(key: string, fetcher: typeof fetch = fetch) {
    this.key = normalizeHelperKey(key)
    // Browser fetch requires a Window receiver, not this source instance.
    this.fetcher = fetcher.bind(globalThis)
  }

  async readSnapshot(signal?: AbortSignal): Promise<MeshCoreSnapshot> {
    return parsePhoneSnapshot(await this.readJson(PHONE_HELPER_URL, signal))
  }

  async readMessages(
    kind: 'channel' | 'direct',
    peer?: string,
    before?: number,
    signal?: AbortSignal,
  ) {
    const query = new URLSearchParams({ kind })
    if (peer) query.set('peer', peer)
    if (before !== undefined) query.set('before', String(before))
    return parseMessages(
      await this.readJson(`http://127.0.0.1:8765/v1/messages?${query}`, signal),
    )
  }

  async readChats(before?: number, signal?: AbortSignal) {
    const query = before === undefined ? '' : `?before=${before}`
    return parseChats(
      await this.readJson(`http://127.0.0.1:8765/v1/chats${query}`, signal),
    )
  }

  async readAdverts(before?: number, signal?: AbortSignal) {
    const query = before === undefined ? '' : `?before=${before}`
    return parseAdverts(
      await this.readJson(`http://127.0.0.1:8765/v1/adverts${query}`, signal),
    )
  }

  private async readJson(url: string, signal?: AbortSignal): Promise<unknown> {
    const response = await this.fetcher(url, {
      headers: { Authorization: `Bearer ${this.key}` },
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      signal,
    })
    if (response.status === 401) throw new HelperAuthenticationError()
    if (response.status === 404)
      throw new Error('Update MeshCore G2 for Android to read this history.')
    if (!response.ok)
      throw new Error(`Phone helper unavailable (${response.status}).`)
    return response.json()
  }
}
