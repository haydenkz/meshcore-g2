import type { MeshCoreSource } from './source.ts'

export const mockMeshCoreSource: MeshCoreSource = {
  readSnapshot() {
    return Promise.resolve({ mode: 'demo', connection: 'disconnected' })
  },
}
