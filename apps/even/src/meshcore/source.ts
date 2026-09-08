export interface MeshCoreSnapshot {
  readonly mode: 'demo' | 'live'
  readonly connection: 'disconnected' | 'connecting' | 'connected' | 'error'
  readonly deviceName?: string
  readonly batteryMillivolts?: number
  readonly protocolVersion?: number
  readonly detail?: string
}

export interface MeshCoreSource {
  readSnapshot(signal?: AbortSignal): Promise<MeshCoreSnapshot>
}
