import assert from 'node:assert/strict'
import { setImmediate } from 'node:timers/promises'
import { mock, test } from 'node:test'
import {
  evenHubEventFromJson,
  OsEventTypeList,
  StartUpPageCreateResult,
  type CreateStartUpPageContainer,
  type TextContainerUpgrade,
  type EvenHubEvent,
} from '@evenrealities/even_hub_sdk'
import { startHud } from './hud.ts'
import { mockMeshCoreSource } from './meshcore/mock.ts'

function host(result = StartUpPageCreateResult.success) {
  let listener: ((event: EvenHubEvent) => void) | undefined
  const unsubscribe = mock.fn(() => {
    listener = undefined
  })
  const bridge = {
    createStartUpPageContainer: mock.fn((_page: CreateStartUpPageContainer) => {
      void _page
      return Promise.resolve(result)
    }),
    onEvenHubEvent: mock.fn((callback: (event: EvenHubEvent) => void) => {
      listener = callback
      return unsubscribe
    }),
    textContainerUpgrade: mock.fn((_page: TextContainerUpgrade) => {
      void _page
      return Promise.resolve(true)
    }),
    shutDownPageContainer: mock.fn((_mode?: number) => {
      void _mode
      return Promise.resolve(true)
    }),
  }
  return {
    bridge,
    unsubscribe,
    emit: (event: Record<string, unknown>) => {
      if (Object.keys(event).length === 0) listener?.({})
      for (const [type, jsonData] of Object.entries(event)) {
        listener?.(evenHubEventFromJson({ type, jsonData }))
      }
    },
  }
}

test('the mock HUD identifies demo data and a disconnected companion', async () => {
  const { bridge } = host()
  await startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
    assert.fail(String(error)),
  )
  const page = bridge.createStartUpPageContainer.mock.calls[0]?.arguments[0]
  const text = page?.textObject?.[0]?.content ?? ''
  assert.match(text, /MeshCore G2/)
  assert.match(text, /DEMO MODE/)
  assert.match(text, /Companion: disconnected/)
  assert.match(text, /No live radio data/)
})

test('failed page creation stops startup before input is registered', async () => {
  const { bridge } = host(StartUpPageCreateResult.invalid)
  await assert.rejects(
    startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
      assert.fail(String(error)),
    ),
    /HUD page creation failed/,
  )
  assert.equal(bridge.onEvenHubEvent.mock.callCount(), 0)
})

test('taps, scrolls, and foreground changes do not close or recreate the HUD', async () => {
  const { bridge, emit } = host()
  await startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
    assert.fail(String(error)),
  )
  for (const event of [
    {},
    { sysEvent: {} }, // Protobuf omits eventType for a single tap (zero).
    { sysEvent: { eventType: OsEventTypeList.CLICK_EVENT } },
    { textEvent: { eventType: OsEventTypeList.SCROLL_TOP_EVENT } },
    { textEvent: { eventType: OsEventTypeList.SCROLL_BOTTOM_EVENT } },
    { sysEvent: { eventType: OsEventTypeList.FOREGROUND_EXIT_EVENT } },
    { sysEvent: { eventType: OsEventTypeList.FOREGROUND_ENTER_EVENT } },
  ])
    emit(event)
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 0)
  assert.equal(bridge.createStartUpPageContainer.mock.callCount(), 1)
})

for (const envelope of ['sysEvent', 'textEvent', 'listEvent'] as const) {
  test(`double-tap in ${envelope} exits once and releases the listener`, async () => {
    const { bridge, emit, unsubscribe } = host()
    await startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
      assert.fail(String(error)),
    )
    const event = {
      [envelope]: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT },
    }
    emit(event)
    emit(event)
    await setImmediate()
    assert.equal(bridge.shutDownPageContainer.mock.callCount(), 1)
    assert.equal(bridge.shutDownPageContainer.mock.calls[0]?.arguments[0], 0)
    await setImmediate()
    assert.equal(unsubscribe.mock.callCount(), 1)
    emit(event)
    assert.equal(bridge.shutDownPageContainer.mock.callCount(), 1)
  })
}

test('failed shutdown reports the error and lets the user retry', async () => {
  const { bridge, emit, unsubscribe } = host()
  bridge.shutDownPageContainer.mock.mockImplementationOnce(() =>
    Promise.resolve(false),
  )
  const errors = mock.fn()
  await startHud(bridge, await mockMeshCoreSource.readSnapshot(), errors)
  const event = { sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } }
  emit(event)
  await setImmediate()
  assert.equal(errors.mock.callCount(), 1)
  assert.equal(unsubscribe.mock.callCount(), 0)
  emit(event)
  await setImmediate()
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 2)
  assert.equal(unsubscribe.mock.callCount(), 1)
})

for (const eventType of [
  OsEventTypeList.SYSTEM_EXIT_EVENT,
  OsEventTypeList.ABNORMAL_EXIT_EVENT,
]) {
  test(`host exit ${eventType} releases the listener`, async () => {
    const { bridge, emit, unsubscribe } = host()
    await startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
      assert.fail(String(error)),
    )
    emit({ sysEvent: { eventType } })
    assert.equal(unsubscribe.mock.callCount(), 1)
    assert.equal(bridge.shutDownPageContainer.mock.callCount(), 0)
  })
}

test('live updates replace text without recreating the page or duplicating unchanged snapshots', async () => {
  const { bridge } = host()
  const hud = await startHud(
    bridge,
    await mockMeshCoreSource.readSnapshot(),
    (e) => assert.fail(String(e)),
  )
  const live = {
    mode: 'live',
    connection: 'connected',
    deviceName: 'Trail radio',
    batteryMillivolts: 3700,
  } as const
  await hud.update(live)
  await hud.update(live)
  assert.equal(bridge.createStartUpPageContainer.mock.callCount(), 1)
  assert.equal(bridge.textContainerUpgrade.mock.callCount(), 1)
  const page = bridge.textContainerUpgrade.mock.calls[0]?.arguments[0]
  assert.match(page?.content ?? '', /Trail radio/)
  assert.match(page?.content ?? '', /3700 mV/)
  await hud.update({ mode: 'live', connection: 'error' })
  assert.doesNotMatch(
    bridge.textContainerUpgrade.mock.calls[1]?.arguments[0]?.content ?? '',
    /3700/,
  )
  hud.dispose()
  await hud.update(live)
  assert.equal(bridge.textContainerUpgrade.mock.callCount(), 2)
})

test('shutdown waits for the in-flight text update and stops later updates', async () => {
  const { bridge, emit } = host()
  let release!: (value: boolean) => void
  bridge.textContainerUpgrade.mock.mockImplementationOnce(
    () =>
      new Promise<boolean>((resolve) => {
        release = resolve
      }),
  )
  const hud = await startHud(
    bridge,
    await mockMeshCoreSource.readSnapshot(),
    (e) => assert.fail(String(e)),
  )
  const update = hud.update({ mode: 'live', connection: 'connecting' })
  await setImmediate()
  emit({ sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } })
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 0)
  release(true)
  await update
  await setImmediate()
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 1)
})
