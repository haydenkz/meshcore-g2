import assert from 'node:assert/strict'
import { setImmediate } from 'node:timers/promises'
import { mock, test } from 'node:test'
import {
  evenHubEventFromJson,
  validateEvenHubPageContainer,
  OsEventTypeList,
  ImageRawDataUpdateResult,
  type ImageRawDataUpdate,
  StartUpPageCreateResult,
  type CreateStartUpPageContainer,
  type TextContainerUpgrade,
  type EvenHubEvent,
} from '@evenrealities/even_hub_sdk'
import { getTextWidth, measureTextWrap } from '@evenrealities/pretext'
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
    updateImageRawData: mock.fn((_image: ImageRawDataUpdate) => {
      void _image
      return Promise.resolve(ImageRawDataUpdateResult.success)
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

test('the initial HUD opens Channels with branding, packet counters, and both menu entries', async () => {
  const { bridge } = host()
  await startHud(bridge, await mockMeshCoreSource.readSnapshot(), (error) =>
    assert.fail(String(error)),
  )
  const page = bridge.createStartUpPageContainer.mock.calls[0]?.arguments[0]
  const text = page?.textObject?.map((item) => item.content).join('\n') ?? ''
  assert.match(text, /MeshCore G2/)
  assert.match(text, /Channels/)
  assert.match(text, /TX — · RX —/)
  assert.deepEqual(
    page?.menuObject?.menuItems?.map((item) => item.itemName),
    ['Channels', 'Direct messages'],
  )
  assert.equal(page?.imageObject?.[0]?.containerName, 'logo')
  assert.deepEqual(validateEvenHubPageContainer(page!), { valid: true })
  assert.equal(
    page?.textObject?.filter((item) => item.isEventCapture === 1).length,
    1,
  )
})

test('each swipe shows one card with separate channel, metadata and message; refresh keeps the selected message', async () => {
  const { bridge, emit } = host()
  let items = [
    received(3),
    { ...received(2), conversationName: 'Local', senderName: 'Bob' },
    received(1),
  ]
  const inbox = {
    async readMessages() {
      return { items, hasMore: false }
    },
    async readChats() {
      return { items: [], hasMore: false }
    },
  }
  const hud = await startHud(
    bridge,
    { mode: 'live', connection: 'connected' },
    (e) => assert.fail(String(e)),
    () => {},
    { inbox: () => inbox },
  )
  await hud.refreshInbox()
  const page = bridge.createStartUpPageContainer.mock.calls[0]!.arguments[0]
  assert.equal(page.textObject?.length, 8)
  assert.equal(
    page.textObject?.find((t) => t.isEventCapture === 1)?.content,
    ' ',
  )
  assert.equal(contentOf(bridge, 7), 'Channel: Public')
  assert.match(contentOf(bridge, 8), /^From Alice · \d{2}:\d{2}$/)
  assert.equal(contentOf(bridge, 9), 'Message 3')
  assert.match(contentOf(bridge, 4), /1\/3$/)
  emit({ textEvent: { eventType: OsEventTypeList.SCROLL_BOTTOM_EVENT } })
  await setImmediate()
  assert.equal(contentOf(bridge, 7), 'Channel: Local')
  assert.match(contentOf(bridge, 8), /^From Bob · /)
  assert.equal(contentOf(bridge, 9), 'Message 2')
  assert.match(contentOf(bridge, 4), /2\/3$/)
  items = [received(4), ...items]
  await hud.refreshInbox()
  assert.equal(contentOf(bridge, 9), 'Message 2')
  emit({ textEvent: { eventType: OsEventTypeList.SCROLL_TOP_EVENT } })
  await setImmediate()
  assert.equal(contentOf(bridge, 9), 'Message 3')
  assert.equal(bridge.createStartUpPageContainer.mock.callCount(), 1)
  hud.dispose()
})

test('long previews and metadata fit their own areas and full message pages preserve all text', async () => {
  const { bridge, emit } = host()
  const original = 'A long radio message with several words and a destination. '
    .repeat(8)
    .trim()
  const inbox = {
    async readMessages() {
      return {
        items: [
          {
            ...received(1),
            text: original,
            senderName: 'Long sender name '.repeat(4),
            conversationName: 'Long channel name '.repeat(4),
          },
        ],
        hasMore: false,
      }
    },
    async readChats() {
      return { items: [], hasMore: false }
    },
  }
  const hud = await startHud(
    bridge,
    { mode: 'live', connection: 'connected' },
    (e) => assert.fail(String(e)),
    () => {},
    { inbox: () => inbox },
  )
  await hud.refreshInbox()
  assert.match(contentOf(bridge, 9), /(…|\.\.\.)$/)
  for (const id of [7, 8]) assert.ok(getTextWidth(contentOf(bridge, id)) <= 520)
  assert.ok(measureTextWrap(contentOf(bridge, 9), 520).height <= 81)
  emit({ sysEvent: {} })
  await setImmediate()
  const pages = Number(contentOf(bridge, 4).split('/').at(-1))
  const chunks = [contentOf(bridge, 9)]
  for (let i = 1; i < pages; i++) {
    emit({ textEvent: { eventType: OsEventTypeList.SCROLL_BOTTOM_EVENT } })
    await setImmediate()
    assert.ok(measureTextWrap(contentOf(bridge, 9), 520).height <= 81)
    chunks.push(contentOf(bridge, 9))
  }
  assert.equal(chunks.join(' ').replace(/\s+/g, ' '), original)
  emit({ sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } })
  await setImmediate()
  assert.match(contentOf(bridge, 5), /Tap: read/)
  hud.dispose()
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

test('packet changes update only the counters without recreating the page or resending the logo', async () => {
  const { bridge } = host()
  const hud = await startHud(
    bridge,
    await mockMeshCoreSource.readSnapshot(),
    (e) => assert.fail(String(e)),
    () => {},
    { logo: new Uint8Array([1, 2, 3]) },
  )
  const live = {
    mode: 'live',
    connection: 'connected',
    packetsSent: 17,
    packetsReceived: 42,
  } as const
  await hud.update(live)
  await hud.update(live)
  assert.equal(bridge.createStartUpPageContainer.mock.callCount(), 1)
  assert.equal(bridge.updateImageRawData.mock.callCount(), 1)
  assert.equal(bridge.textContainerUpgrade.mock.callCount(), 1)
  assert.match(
    bridge.textContainerUpgrade.mock.calls[0]!.arguments[0].content!,
    /^TX 17 · RX 42 +/,
  )
  await hud.update({ mode: 'live', connection: 'disconnected' })
  assert.match(
    bridge.textContainerUpgrade.mock.calls[1]!.arguments[0].content!,
    /^TX — · RX — +/,
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
  const update = hud.update({
    mode: 'live',
    connection: 'connected',
    packetsSent: 1,
    packetsReceived: 2,
  })
  await setImmediate()
  emit({ sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } })
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 0)
  release(true)
  await update
  await setImmediate()
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 1)
})

test('the header clock advances while disconnected and large packet totals leave it at the right edge', async (t) => {
  t.mock.timers.enable({
    apis: ['Date'],
    now: new Date(2026, 8, 8, 23, 59, 30),
  })
  const { bridge } = host()
  const offline = { mode: 'live', connection: 'disconnected' } as const
  const hud = await startHud(bridge, offline, (e) => assert.fail(String(e)))
  assert.ok(contentOf(bridge, 3).endsWith('23:59'))
  t.mock.timers.tick(60000)
  await hud.update(offline)
  assert.ok(contentOf(bridge, 3).endsWith('00:00'))
  assert.deepEqual(
    bridge.textContainerUpgrade.mock.calls.map(
      (c) => c.arguments[0].containerID,
    ),
    [3],
  )
  for (const count of [0, 17, 999999, 999999999, 4294967295]) {
    await hud.update({
      mode: 'live',
      connection: 'connected',
      packetsSent: count,
      packetsReceived: count,
    })
    const text = contentOf(bridge, 3)
    assert.ok(text.endsWith('00:00'))
    assert.ok(getTextWidth(text) <= 320)
    assert.ok(getTextWidth(text) > 320 - getTextWidth(' '))
  }
  assert.match(contentOf(bridge, 3), /^TX 4\.3B · RX 4\.3B +/)
  hud.dispose()
})

const received = (id: number, kind: 'channel' | 'direct' = 'channel') => ({
  id,
  kind,
  conversationId: kind === 'channel' ? 'radio:0' : 'radio:alice',
  conversationName: kind === 'channel' ? 'Public' : 'Alice',
  senderName: 'Alice',
  text: `Message ${id}`,
  sentAt: 1700000000000 + id * 1000,
  receivedAt: 1700000000000 + id * 1000,
})
function contentOf(
  bridge: ReturnType<typeof host>['bridge'],
  id: number,
): string {
  return (
    bridge.textContainerUpgrade.mock.calls
      .filter((call) => call.arguments[0].containerID === id)
      .at(-1)?.arguments[0].content ??
    bridge.createStartUpPageContainer.mock.calls[0]?.arguments[0].textObject?.find(
      (item) => item.containerID === id,
    )?.content ??
    ''
  )
}

test('Channels excludes direct chats; the menu opens chats and a tap opens the selected conversation', async () => {
  const { bridge, emit } = host()
  const requests: string[] = []
  const inbox = {
    async readMessages(kind: 'channel' | 'direct', peer?: string) {
      requests.push(`${kind}:${peer ?? ''}`)
      return {
        items: [received(kind === 'channel' ? 1 : 2, kind)],
        hasMore: false,
      }
    },
    async readChats() {
      return {
        items: [
          {
            id: 'radio:alice',
            name: 'Alice',
            lastMessageId: 2,
            updatedAt: 1700000002000,
            preview: 'Message 2',
          },
        ],
        hasMore: false,
      }
    },
  }
  const hud = await startHud(
    bridge,
    { mode: 'live', connection: 'connected' },
    (e) => assert.fail(String(e)),
    () => {},
    { inbox: () => inbox },
  )
  await hud.refreshInbox()
  assert.match(contentOf(bridge, 4), /^Channels/)
  assert.match(contentOf(bridge, 7), /Channel: Public/)
  emit({ menuItemClickEvent: { itemID: 2 } })
  await setImmediate()
  assert.match(contentOf(bridge, 4), /^Direct messages/)
  assert.match(contentOf(bridge, 7), /Chat: Alice/)
  assert.match(contentOf(bridge, 8), /^Updated \d{2}:\d{2}$/)
  emit({ sysEvent: {} }) // Protobuf omits single-click's zero value.
  await setImmediate()
  assert.match(contentOf(bridge, 4), /^Alice/)
  assert.deepEqual(requests, ['channel:', 'direct:radio:alice'])
  assert.match(contentOf(bridge, 9), /Message 2/)
  emit({ sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } })
  await setImmediate()
  assert.match(contentOf(bridge, 4), /^Direct messages/)
  assert.equal(bridge.shutDownPageContainer.mock.callCount(), 0)
  emit({ menuItemClickEvent: { itemID: 1 } })
  await setImmediate()
  assert.match(contentOf(bridge, 4), /^Channels/)
  hud.dispose()
})

test('pagination keeps all received messages reachable and message details return to the same feed', async () => {
  const { bridge, emit } = host()
  const cursors: (number | undefined)[] = []
  const inbox = {
    async readMessages(
      _kind: 'channel' | 'direct',
      _peer?: string,
      before?: number,
    ) {
      cursors.push(before)
      return {
        items: [received(before ? 1 : 2)],
        hasMore: before === undefined,
      }
    },
    async readChats() {
      return { items: [], hasMore: false }
    },
  }
  const hud = await startHud(
    bridge,
    { mode: 'live', connection: 'connected' },
    (e) => assert.fail(String(e)),
    () => {},
    { inbox: () => inbox },
  )
  await hud.refreshInbox()
  emit({ textEvent: { eventType: OsEventTypeList.SCROLL_BOTTOM_EVENT } })
  emit({ sysEvent: {} })
  await setImmediate()
  assert.deepEqual(cursors, [undefined, 2])
  assert.match(contentOf(bridge, 7), /Newer messages/)
  emit({ textEvent: { eventType: OsEventTypeList.SCROLL_BOTTOM_EVENT } })
  emit({ sysEvent: {} })
  await setImmediate()
  assert.match(contentOf(bridge, 5), /Double-tap to go back/)
  emit({ sysEvent: { eventType: OsEventTypeList.DOUBLE_CLICK_EVENT } })
  await setImmediate()
  assert.equal(contentOf(bridge, 9), 'Message 1')
  hud.dispose()
})

test('late history responses cannot replace a newer screen and forgetting clears private messages', async () => {
  const { bridge, emit } = host()
  let release!: (value: {
    items: ReturnType<typeof received>[]
    hasMore: boolean
  }) => void
  const inbox = {
    readMessages: () =>
      new Promise<{ items: ReturnType<typeof received>[]; hasMore: boolean }>(
        (resolve) => {
          release = resolve
        },
      ),
    async readChats() {
      return {
        items: [
          {
            id: 'radio:alice',
            name: 'Alice',
            lastMessageId: 2,
            updatedAt: 1700000002000,
            preview: 'Private',
          },
        ],
        hasMore: false,
      }
    },
  }
  let current: typeof inbox | undefined = inbox
  const hud = await startHud(
    bridge,
    { mode: 'live', connection: 'connected' },
    (e) => assert.fail(String(e)),
    () => {},
    { inbox: () => current },
  )
  const pending = hud.refreshInbox()
  emit({ menuItemClickEvent: { itemID: 2 } })
  await setImmediate()
  release({ items: [received(1)], hasMore: false })
  await pending
  assert.match(contentOf(bridge, 4), /^Direct messages/)
  assert.match(contentOf(bridge, 9), /Private/)
  current = undefined
  await hud.refreshInbox()
  assert.doesNotMatch(contentOf(bridge, 9), /Private/)
  hud.dispose()
})
