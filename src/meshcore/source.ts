/** Only the state used by this milestone; add telemetry with its real adapter. */
export interface MeshCoreSnapshot {
  readonly mode: 'demo' | 'live'
  readonly connection: 'disconnected' | 'connected'
}

export interface MeshCoreSource {
  readSnapshot(): Promise<MeshCoreSnapshot>
}
