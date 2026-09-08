import { waitForEvenAppBridge } from '@evenrealities/even_hub_sdk'
import { startHud, type HudController } from './hud.ts'
import { HelperLinkStore, normalizeHelperKey } from './meshcore/helper-link.ts'
import {
  PhoneHelperSource,
  HelperAuthenticationError,
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
const forgetButton =
  document.querySelector<HTMLButtonElement>('#forget-helper')!
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
let helperReached = false
let keyRejected = false

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
  const state = snapshot.mode === 'demo' ? 'disconnected' : snapshot.connection
  document.querySelector<HTMLElement>('.radio-card')!.dataset.state = state
  const badge = document.querySelector<HTMLSpanElement>('#connection-badge')!
  badge.hidden = state !== 'connected' && state !== 'connecting'
  badge.textContent = state === 'connected' ? 'Connected' : 'Connecting'
  document.querySelector('#radio-name')!.textContent =
    state === 'connected'
      ? snapshot.deviceName || 'Radio connected'
      : state === 'connecting'
        ? snapshot.deviceName || 'Connecting…'
        : 'Not connected'
  status.textContent =
    state === 'connected'
      ? 'Connected through your phone.'
      : state === 'connecting'
        ? 'Connecting to your radio through the phone helper…'
        : state === 'error'
          ? snapshot.detail || 'Check your radio in MeshCore G2 Helper.'
          : selectedKey
            ? helperReached
              ? 'Connect your radio in MeshCore G2 Helper.'
              : 'Open MeshCore G2 Helper. Your link will reconnect automatically.'
            : 'Link your phone helper to get started.'
  form.hidden = selectedKey !== undefined && !keyRejected
  forgetButton.hidden = selectedKey === undefined
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
      keyRejected = false
      render(snapshot)
    }
  } catch (error) {
    if (source === active) {
      helperReached = false
      const unavailable =
        error instanceof TypeError ||
        (error instanceof Error && error.name === 'AbortError')
      if (error instanceof HelperAuthenticationError) keyRejected = true
      render({
        mode: 'live',
        connection: unavailable ? 'disconnected' : 'error',
        detail: unavailable
          ? 'Open MeshCore G2 Helper on your phone.'
          : error instanceof Error
            ? error.message
            : 'Check MeshCore G2 Helper on your phone.',
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
  keyRejected = false
  keyInput.value = ''
  render({ mode: 'live', connection: 'connecting' })
  void poll(next)
}
async function rememberSelection() {
  if (!savedLink) {
    pendingSave = true
    linkStatus.textContent =
      'Open in the Even App to save this link for next time.'
    return
  }
  pendingSave = false
  const key = selectedKey
  const saved = await savedLink.save(key)
  if (disposed || key !== selectedKey) return
  linkStatus.textContent = saved
    ? ''
    : 'Could not save this change. Your previous link may return when you reopen the app.'
}
form.addEventListener('submit', (event) => {
  event.preventDefault()
  try {
    const key = normalizeHelperKey(keyInput.value)
    selectionRevision++
    connect(key)
    linkStatus.textContent = ''
    void rememberSelection()
  } catch (error) {
    linkStatus.textContent =
      error instanceof Error ? error.message : 'Unable to link the helper.'
  }
})
forgetButton.addEventListener('click', () => {
  selectionRevision++
  stopPolling()
  selectedKey = undefined
  keyRejected = false
  keyInput.value = ''
  linkStatus.textContent = ''
  void rememberSelection()
  render({ mode: 'live', connection: 'disconnected' })
  keyInput.focus()
})
function reportError(error: unknown) {
  console.error('MeshCore G2:', error)
  hudStatus.textContent = 'Glasses unavailable. Reopen the app to retry.'
}
async function restoreLink(store: HelperLinkStore, revision: number) {
  try {
    const key = await store.load()
    // A manual link or forget action takes precedence over a late restore.
    if (disposed || revision !== selectionRevision) return
    if (key) connect(key)
  } catch {
    if (!disposed && revision === selectionRevision)
      linkStatus.textContent =
        'Could not restore your link. Paste the helper key again.'
  }
}
async function main() {
  const bridge = await waitForEvenAppBridge()
  if (disposed) return
  savedLink = new HelperLinkStore(bridge)
  if (selectionRevision === 0) await restoreLink(savedLink, selectionRevision)
  else if (pendingSave) await rememberSelection()
  hud = await startHud(bridge, current, reportError, stopPolling)
  await hud.update(current)
  hudStatus.textContent = 'Glasses ready'
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
