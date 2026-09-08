import { waitForEvenAppBridge } from '@evenrealities/even_hub_sdk'
import { startHud, type HudController } from './hud.ts'
import { mockMeshCoreSource } from './meshcore/mock.ts'
import { HelperLinkStore, normalizeHelperKey } from './meshcore/helper-link.ts'
import {
  PhoneHelperSource,
  PHONE_HELPER_HEALTH_URL,
} from './meshcore/phone-helper.ts'
import type { MeshCoreSnapshot } from './meshcore/source.ts'
import { version } from '../../../package.json'
import appIconUrl from '../../../assets/meshcore-g2.png'
import './style.css'

const status = document.querySelector<HTMLParagraphElement>('#status')!
const hudStatus = document.querySelector<HTMLParagraphElement>('#hud-status')!
const linkStatus = document.querySelector<HTMLParagraphElement>('#link-status')!
const keyInput = document.querySelector<HTMLInputElement>('#helper-key')!
const form = document.querySelector<HTMLFormElement>('#helper-form')!
const linkedActions = document.querySelector<HTMLDivElement>('#linked-actions')!
const connectionCheck =
  document.querySelector<HTMLParagraphElement>('#connection-check')!
let hud: HudController | undefined
let current: MeshCoreSnapshot = { mode: 'demo', connection: 'disconnected' }
let source: PhoneHelperSource | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let request: AbortController | undefined
let savedLink: HelperLinkStore | undefined
let selectedKey: string | undefined
let selectionRevision = 0
let pendingSave = false
let disposed = false
let blockedPolicy = ''
let helperReached = false

document.querySelector<HTMLImageElement>('#app-icon')!.src = appIconUrl
document.querySelector<HTMLLinkElement>('#app-favicon')!.href = appIconUrl

document.querySelector('#app-version')!.textContent = `MeshCore G2 · ${version}`

function stopPolling() {
  source = undefined
  helperReached = false
  clearTimeout(timer)
  request?.abort()
}
function render(snapshot: MeshCoreSnapshot) {
  current = snapshot
  const state = snapshot.mode === 'demo' ? 'demo' : snapshot.connection
  document.querySelector<HTMLElement>('.radio-card')!.dataset.state = state
  document.querySelector('#connection-badge')!.textContent = {
    demo: 'Demo',
    connected: 'Connected',
    connecting: 'Connecting',
    disconnected: 'Disconnected',
    error: 'Needs attention',
  }[state]
  status.textContent =
    snapshot.mode === 'demo'
      ? 'No live radio data. Link your phone helper to get started.'
      : snapshot.connection === 'connected'
        ? 'Connected through your phone.'
        : (snapshot.detail ?? 'Connect your radio in the phone helper.')
  document.querySelector('#radio-name')!.textContent =
    snapshot.deviceName ||
    (snapshot.connection === 'error'
      ? helperReached
        ? 'Radio needs attention'
        : 'Helper unavailable'
      : snapshot.connection === 'connecting'
        ? 'Making a connection'
        : 'Connect your radio')
  document.querySelector('#battery')!.textContent =
    snapshot.batteryMillivolts === undefined
      ? '—'
      : `${(snapshot.batteryMillivolts / 1000).toFixed(3)} V`
  document.querySelector('#radio-diagnostics')!.textContent = [
    snapshot.detail,
    snapshot.protocolVersion === undefined
      ? ''
      : `Radio protocol: ${snapshot.protocolVersion}.`,
  ]
    .filter(Boolean)
    .join(' ')
  form.hidden = selectedKey !== undefined
  linkedActions.hidden = selectedKey === undefined
  document.querySelector<HTMLButtonElement>('#forget-helper')!.hidden =
    selectedKey === undefined
  document.querySelector<HTMLSpanElement>('#helper-indicator')!.hidden =
    !helperReached
  document.querySelector('#helper-summary')!.textContent =
    snapshot.mode === 'demo'
      ? 'Demo is showing. Reconnect to use your radio.'
      : helperReached
        ? 'Keep the helper running on this phone.'
        : 'Waiting for the helper on this phone…'
  document.querySelector<HTMLParagraphElement>('#helper-summary')!.hidden =
    helperReached
  document.querySelector<HTMLButtonElement>('#reconnect')!.hidden =
    source !== undefined && snapshot.connection !== 'error'
  void hud?.update(snapshot).catch(reportError)
}
async function poll(active: PhoneHelperSource) {
  const controller = new AbortController()
  request = controller
  const deadline = setTimeout(() => controller.abort(), 4000)
  try {
    const snapshot = await active.readSnapshot(controller.signal)
    if (source === active) {
      helperReached = true
      render(snapshot)
    }
  } catch (error) {
    if (source === active) {
      helperReached = false
      render({
        mode: 'live',
        connection: 'error',
        detail:
          error instanceof Error &&
          !(error instanceof TypeError) &&
          error.name !== 'AbortError'
            ? error.message
            : 'Open MeshCore G2 Helper, then reconnect. More help is in Connection details.',
      })
    }
  } finally {
    clearTimeout(deadline)
    if (source === active)
      timer = setTimeout(() => {
        void poll(active)
      }, 1500)
  }
}
function connect(key: string) {
  const next = new PhoneHelperSource(key)
  stopPolling()
  source = next
  selectedKey = normalizeHelperKey(key)
  keyInput.value = ''
  render({
    mode: 'live',
    connection: 'connecting',
    detail: 'Connecting to the helper on this phone…',
  })
  void poll(next)
}
async function rememberSelection() {
  if (!savedLink) {
    pendingSave = true
    linkStatus.textContent =
      'Link is active for this session. Open in the Even App to remember it.'
    return
  }
  pendingSave = false
  const key = selectedKey
  const saved = await savedLink.save(key)
  if (disposed || key !== selectedKey) return
  linkStatus.textContent = saved
    ? key
      ? 'Helper link saved. It reconnects automatically when you open this app.'
      : 'Helper forgotten. Paste a connection key to link again.'
    : 'The Even App could not save this change. Your previous link may return when you reopen the app.'
}
form.addEventListener('submit', (event) => {
  event.preventDefault()
  try {
    const key = normalizeHelperKey(keyInput.value)
    selectionRevision++
    connect(key)
    linkStatus.textContent = 'Remembering this helper…'
    void rememberSelection()
  } catch (error) {
    render({
      mode: 'live',
      connection: 'error',
      detail:
        error instanceof Error ? error.message : 'Unable to link the helper.',
    })
  }
})
document.querySelector('#reconnect')!.addEventListener('click', () => {
  selectionRevision++
  if (selectedKey) connect(selectedKey)
})
document.querySelector('#forget-helper')!.addEventListener('click', () => {
  selectionRevision++
  stopPolling()
  selectedKey = undefined
  linkStatus.textContent = 'Forgetting this helper…'
  void rememberSelection()
  render({
    mode: 'live',
    connection: 'disconnected',
    detail: 'Link a phone helper to connect.',
  })
})
document.querySelector('#demo')!.addEventListener('click', () => {
  selectionRevision++
  stopPolling()
  void mockMeshCoreSource.readSnapshot().then(render)
})
window.addEventListener('securitypolicyviolation', (event) => {
  if (event.blockedURI.startsWith('http://127.0.0.1:8765'))
    blockedPolicy = event.effectiveDirective
})
document.querySelector('#check-connection')!.addEventListener('click', () => {
  void checkConnection()
})
async function checkConnection() {
  const button = document.querySelector<HTMLButtonElement>('#check-connection')!
  button.disabled = true
  blockedPolicy = ''
  connectionCheck.textContent = 'Checking access to the helper…'
  const controller = new AbortController()
  const deadline = setTimeout(() => controller.abort(), 4000)
  try {
    const response = await fetch(PHONE_HELPER_HEALTH_URL, {
      cache: 'no-store',
      credentials: 'omit',
      redirect: 'error',
      signal: controller.signal,
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const text = await response.text()
    if (!text.startsWith('MeshCore phone helper is reachable.'))
      throw new Error('Unexpected health response')
    if (selectedKey) {
      await new PhoneHelperSource(selectedKey).readSnapshot(controller.signal)
      connectionCheck.textContent =
        'Helper reachable. Your connection key is accepted and radio status can be read.'
    } else {
      connectionCheck.textContent =
        'Helper reachable. Paste its connection key above to link it.'
    }
  } catch (error) {
    connectionCheck.textContent = blockedPolicy
      ? `The host blocked the helper through its ${blockedPolicy} policy. Changing the key will not fix this.`
      : error instanceof Error &&
          !(error instanceof TypeError) &&
          error.name !== 'AbortError'
        ? error.message
        : 'Connection check failed. Keep the helper running and check its Connection details for received requests.'
  } finally {
    clearTimeout(deadline)
    button.disabled = false
  }
}
function reportError(error: unknown) {
  console.error('MeshCore G2:', error)
  hudStatus.textContent =
    'Unable to display or close the HUD. Reopen the app to retry.'
  document.querySelector('#glasses-state')!.textContent = 'Unavailable'
}
async function restoreLink(store: HelperLinkStore, revision: number) {
  try {
    const key = await store.load()
    // A manual link, demo selection, or forget action takes precedence over a late restore.
    if (disposed || revision !== selectionRevision) return
    if (key) {
      connect(key)
      linkStatus.textContent = 'Using your saved helper link.'
    }
  } catch {
    if (!disposed && revision === selectionRevision)
      linkStatus.textContent =
        'Could not restore the helper link. Paste its connection key to link again.'
  }
}
async function main() {
  const bridge = await waitForEvenAppBridge()
  if (disposed) return
  savedLink = new HelperLinkStore(bridge)
  if (selectionRevision === 0) void restoreLink(savedLink, selectionRevision)
  else if (pendingSave) void rememberSelection()
  hud = await startHud(bridge, current, reportError, stopPolling)
  await hud.update(current)
  hudStatus.textContent = 'Glasses HUD ready. Double-tap the glasses to exit.'
  document.querySelector('#glasses-state')!.textContent = 'Ready'
  console.info('MeshCore G2 ready')
}
function dispose() {
  disposed = true
  stopPolling()
  hud?.dispose()
}
window.addEventListener('pagehide', dispose, { once: true })
import.meta.hot?.dispose(dispose)
render(current)
void main().catch(reportError)
