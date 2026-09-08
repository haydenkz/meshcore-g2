import { waitForEvenAppBridge } from '@evenrealities/even_hub_sdk'
import { startHud } from './hud.ts'
import { mockMeshCoreSource } from './meshcore/mock.ts'
import './style.css'

const status = document.querySelector<HTMLParagraphElement>('#status')!

function reportError(error: unknown) {
  console.error('MeshCore HUD:', error)
  status.textContent =
    'Unable to display or close the HUD. Reopen the app to retry.'
}

async function main() {
  const snapshot = await mockMeshCoreSource.readSnapshot()
  const bridge = await waitForEvenAppBridge()
  const dispose = await startHud(bridge, snapshot, reportError)
  window.addEventListener('pagehide', dispose, { once: true })
  import.meta.hot?.dispose(dispose)
  status.textContent =
    'Demo HUD ready. Companion disconnected; no live radio data.'
  console.info('MeshCore HUD ready: demo / disconnected')
}

void main().catch(reportError)
