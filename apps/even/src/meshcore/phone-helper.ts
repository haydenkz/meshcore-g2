import type { MeshCoreSnapshot, MeshCoreSource } from './source.ts'
import { normalizeHelperKey } from './helper-link.ts'

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
  }
}

export class HelperAuthenticationError extends Error {
  constructor() {
    super(
      'Connection key not accepted. Copy the current key from MeshCore G2 Helper and paste it below.',
    )
    this.name = 'HelperAuthenticationError'
  }
}

export class PhoneHelperSource implements MeshCoreSource {
  private readonly key: string
  private readonly fetcher: typeof fetch

  constructor(key: string, fetcher: typeof fetch = fetch) {
    this.key = normalizeHelperKey(key)
    // Browser fetch requires a Window receiver, not this source instance.
    this.fetcher = fetcher.bind(globalThis)
  }

  async readSnapshot(signal?: AbortSignal): Promise<MeshCoreSnapshot> {
    const response = await this.fetcher(PHONE_HELPER_URL, {
      headers: { Authorization: `Bearer ${this.key}` },
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      signal,
    })
    if (response.status === 401) throw new HelperAuthenticationError()
    if (!response.ok)
      throw new Error(`Phone helper unavailable (${response.status}).`)
    return parsePhoneSnapshot(await response.json())
  }
}
