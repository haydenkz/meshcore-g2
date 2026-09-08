import {
  CreateStartUpPageContainer,
  ImageContainerProperty,
  ImageRawDataUpdate,
  ImageRawDataUpdateResult,
  MenuContainerProperty,
  MenuItemProperty,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import { getTextWidth, pxTruncate } from '@evenrealities/pretext'
import type { MeshCoreSnapshot } from './meshcore/source.ts'
import type {
  DirectChat,
  InboxSource,
  ReceivedMessage,
} from './meshcore/inbox.ts'

type HudBridge = Pick<
  EvenAppBridge,
  | 'createStartUpPageContainer'
  | 'onEvenHubEvent'
  | 'shutDownPageContainer'
  | 'textContainerUpgrade'
  | 'updateImageRawData'
>
type Screen =
  | { kind: 'channel' }
  | { kind: 'chats' }
  | { kind: 'direct'; peer: string; name: string }
type Entry = {
  title?: string
  message?: ReceivedMessage
  chat?: DirectChat
  page?: 'older' | 'newer'
}
export interface HudOptions {
  logo?: Uint8Array
  inbox?: () => InboxSource | undefined
}
export interface HudController {
  update(snapshot: MeshCoreSnapshot): Promise<void>
  refreshInbox(): Promise<void>
  dispose(): void
}

function headerStatus(snapshot: MeshCoreSnapshot): string {
  const live = snapshot.mode === 'live' && snapshot.connection === 'connected'
  const sent = live ? snapshot.packetsSent : undefined
  const received = live ? snapshot.packetsReceived : undefined
  const clock = time(Date.now())
  let totals = `TX ${sent ?? '—'} · RX ${received ?? '—'}`
  if (getTextWidth(totals) + getTextWidth(clock) + 16 > 320)
    totals = `TX ${compactCount(sent)} · RX ${compactCount(received)}`
  let gap = Math.max(
    2,
    Math.floor(
      (320 - getTextWidth(totals) - getTextWidth(clock)) / getTextWidth(' '),
    ),
  )
  // Measure the complete string too: the font applies spacing across the join.
  while (gap > 2 && getTextWidth(`${totals}${' '.repeat(gap)}${clock}`) > 320)
    gap--
  return `${totals}${' '.repeat(gap)}${clock}`
}
function compactCount(value: number | undefined): string {
  if (value === undefined) return '—'
  for (const [scale, suffix] of [
    [1e9, 'B'],
    [1e6, 'M'],
    [1e3, 'k'],
  ] as const) {
    if (value >= scale)
      return `${(value / scale).toFixed(1).replace(/\.0$/, '')}${suffix}`
  }
  return String(value)
}
function time(timestamp: number): string {
  const date = new Date(timestamp)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}
function wrap(text: string, width: number): string[] {
  const lines: string[] = []
  for (const paragraph of text.split('\n')) {
    let rest = paragraph
    while (getTextWidth(rest) > width) {
      let end = 0
      for (const character of rest) {
        if (getTextWidth(rest.slice(0, end + character.length)) > width) break
        end += character.length
      }
      const space = rest.lastIndexOf(' ', end)
      if (space > 0) end = space
      lines.push(rest.slice(0, Math.max(end, 1)))
      rest = rest.slice(Math.max(end, 1)).trimStart()
    }
    lines.push(rest)
  }
  return lines
}

export async function startHud(
  bridge: HudBridge,
  initial: MeshCoreSnapshot,
  reportError: (error: unknown) => void,
  onExit: () => void = () => {},
  options: HudOptions = {},
): Promise<HudController> {
  let snapshot = initial
  let screen: Screen = { kind: 'channel' }
  let entries: Entry[] = []
  let before: number | undefined
  let cursors: (number | undefined)[] = []
  let selected = 0
  let message: ReceivedMessage | undefined
  let messagePage = 0
  let oldest: number | undefined
  let notice = 'No channel messages yet.'
  let activeSource: InboxSource | undefined
  let request: AbortController | undefined
  let revision = 0
  let exiting = false
  let disposed = false
  let sequence = Promise.resolve()
  const last = new Map<number, string>()
  const names: Record<number, string> = {
    1: 'card',
    2: 'brand',
    3: 'header-status',
    4: 'screen',
    5: 'hint',
    7: 'conversation',
    8: 'metadata',
    9: 'message',
  }
  function enqueue(work: () => Promise<void>): Promise<void> {
    const result = sequence.then(work)
    sequence = result.catch(() => {})
    return result
  }
  function texts(): Map<number, string> {
    const title =
      screen.kind === 'channel'
        ? 'Channels'
        : screen.kind === 'chats'
          ? 'Direct messages'
          : screen.name
    selected = Math.min(selected, Math.max(0, entries.length - 1))
    const entry = entries[selected]
    const currentMessage = message ?? entry?.message
    let conversation = ''
    let metadata = ''
    let body = notice
    let hint =
      screen.kind === 'direct'
        ? 'Double-tap to go back'
        : 'Tap, then hold for the menu'
    let position = ''
    if (currentMessage) {
      conversation =
        currentMessage.kind === 'channel'
          ? `Channel: ${currentMessage.conversationName}`
          : 'Direct message'
      const timestamp = time(currentMessage.sentAt)
      // Reserve the timestamp's width so a long sender name cannot hide it.
      metadata = `${pxTruncate(`From ${currentMessage.senderName || 'Unknown sender'}`, 520 - getTextWidth(` · ${timestamp}`))} · ${timestamp}`
      const lines = wrap(currentMessage.text, 520)
      const pages = Math.max(1, Math.ceil(lines.length / 3))
      messagePage = Math.min(messagePage, pages - 1)
      if (message) {
        body = lines.slice(messagePage * 3, messagePage * 3 + 3).join('\n')
        position = `Read ${messagePage + 1}/${pages}`
        hint =
          pages > 1
            ? 'Swipe to read · Double-tap to go back'
            : 'Double-tap to go back'
      } else {
        const preview = lines.slice(0, 3)
        if (lines.length > 3) preview[2] = pxTruncate(`${preview[2]}…`, 520)
        body = preview.join('\n')
        hint = 'Swipe: next / previous · Tap: read'
      }
    } else if (entry?.chat) {
      conversation = `Chat: ${entry.chat.name}`
      metadata = `Updated ${time(entry.chat.updatedAt)}`
      const lines = wrap(entry.chat.preview, 520).slice(0, 3)
      if (wrap(entry.chat.preview, 520).length > 3)
        lines[2] = pxTruncate(`${lines[2]}…`, 520)
      body = lines.join('\n')
      hint = 'Swipe: next / previous · Tap: open chat'
    } else if (entry?.page) {
      conversation = entry.title ?? 'Message history'
      metadata = 'Message history'
      body =
        entry.page === 'older'
          ? 'Continue to earlier messages.'
          : 'Return to more recent messages.'
      hint = 'Tap to load · Swipe to go back'
    }
    if (entry && !message) position = `${selected + 1}/${entries.length}`
    return new Map([
      // An empty, fixed capture surface has no native text scroll offset. Each
      // swipe changes exactly one card; the body is displayed in static containers.
      [1, ' '],
      [2, 'MeshCore G2'],
      [3, headerStatus(snapshot)],
      [
        4,
        position
          ? `${pxTruncate(title, 544 - getTextWidth(` · ${position}`))} · ${position}`
          : title,
      ],
      [5, pxTruncate(hint, 544)],
      [7, pxTruncate(conversation, 520) || ' '],
      [8, pxTruncate(metadata, 520) || ' '],
      [9, body || ' '],
    ])
  }
  const initialText = texts()
  const text = (
    id: number,
    x: number,
    y: number,
    width: number,
    height: number,
    extra = {},
  ) =>
    new TextContainerProperty({
      containerID: id,
      containerName: names[id]!,
      xPosition: x,
      yPosition: y,
      width,
      height,
      borderWidth: 0,
      paddingLength: 0,
      isEventCapture: id === 1 ? 1 : 0,
      content: initialText.get(id)!,
      ...extra,
    })
  const result = await bridge.createStartUpPageContainer(
    new CreateStartUpPageContainer({
      containerTotalNum: 9,
      textObject: [
        text(1, 16, 89, 544, 164, {
          borderWidth: 1,
          borderColor: 5,
          borderRadius: 8,
        }),
        text(2, 76, 21, 150, 27),
        text(3, 240, 21, 320, 27),
        text(4, 16, 57, 544, 27, { textColor: 3 }),
        text(5, 16, 261, 544, 27, { textColor: 2 }),
        text(7, 28, 97, 520, 27, { textColor: 3 }),
        text(8, 28, 128, 520, 27, { textColor: 2 }),
        text(9, 28, 164, 520, 81, { textColor: 4 }),
      ],
      imageObject: [
        new ImageContainerProperty({
          containerID: 6,
          containerName: 'logo',
          xPosition: 16,
          yPosition: 14,
          width: 48,
          height: 40,
        }),
      ],
      menuObject: new MenuContainerProperty({
        menuItems: [
          new MenuItemProperty({ itemID: 1, itemName: 'Channels' }),
          new MenuItemProperty({ itemID: 2, itemName: 'Direct messages' }),
        ],
      }),
    }),
  )
  if (result !== StartUpPageCreateResult.success)
    throw new Error(`HUD page creation failed (SDK result ${result}).`)
  for (const [id, content] of initialText) last.set(id, content)
  function render(): Promise<void> {
    return enqueue(async () => {
      if (disposed || exiting) return
      for (const [id, content] of texts()) {
        if (disposed || exiting) return
        if (last.get(id) === content) continue
        const success = await bridge.textContainerUpgrade(
          new TextContainerUpgrade({
            containerID: id,
            containerName: names[id]!,
            contentOffset: 0,
            contentLength: 0,
            content,
          }),
        )
        if (!success) throw new Error('The Even host could not update the HUD.')
        last.set(id, content)
      }
    })
  }
  function cancelRequest() {
    revision++
    request?.abort()
    request = undefined
  }
  function dispose() {
    if (disposed) return
    disposed = true
    cancelRequest()
    unsubscribe()
    onExit()
  }
  async function refreshInbox(): Promise<void> {
    if (disposed || exiting) return
    const source = options.inbox?.()
    if (source !== activeSource) {
      cancelRequest()
      activeSource = source
      screen = { kind: 'channel' }
      entries = []
      before = undefined
      cursors = []
      selected = 0
      message = undefined
    }
    if (!source) {
      notice = 'Link your phone helper to read messages.'
      await render()
      return
    }
    if (request || message) return
    const controller = new AbortController()
    request = controller
    const currentRevision = revision
    const deadline = setTimeout(() => controller.abort(), 4000)
    try {
      const page =
        screen.kind === 'chats'
          ? await source.readChats(before, controller.signal)
          : await source.readMessages(
              screen.kind,
              screen.kind === 'direct' ? screen.peer : undefined,
              before,
              controller.signal,
            )
      if (disposed || currentRevision !== revision) return
      const previous = entries[selected]
      const next: Entry[] = page.items.map((item) =>
        'lastMessageId' in item ? { chat: item } : { message: item },
      )
      const final = page.items.at(-1)
      oldest = final
        ? 'lastMessageId' in final
          ? final.lastMessageId
          : final.id
        : undefined
      if (cursors.length)
        next.unshift({
          title: 'Newer messages',
          page: 'newer',
        })
      if (page.hasMore)
        next.push({
          title: 'Older messages',
          page: 'older',
        })
      if (previous) {
        const index = next.findIndex((item) =>
          previous.message
            ? item.message?.id === previous.message.id
            : previous.chat
              ? item.chat?.id === previous.chat.id
              : item.page === previous.page,
        )
        if (index >= 0) selected = index
      }
      entries = next
      notice =
        screen.kind === 'channel'
          ? 'No channel messages yet.'
          : screen.kind === 'chats'
            ? 'No direct message chats yet.'
            : 'No messages in this chat yet.'
      await render()
    } catch (error) {
      if (disposed || currentRevision !== revision) return
      notice =
        error instanceof TypeError ||
        (error instanceof Error && error.name === 'AbortError')
          ? 'Open MeshCore G2 for Android to receive messages.'
          : error instanceof Error
            ? error.message
            : 'Unable to load messages.'
      await render()
    } finally {
      clearTimeout(deadline)
      if (request === controller) request = undefined
    }
  }
  function open(next: Screen) {
    cancelRequest()
    screen = next
    entries = []
    before = undefined
    cursors = []
    selected = 0
    message = undefined
    notice = 'Loading messages…'
    void render().catch(reportError)
    void refreshInbox().catch(reportError)
  }
  function select() {
    const entry = entries[selected]
    if (!entry) return
    if (entry.chat) {
      open({ kind: 'direct', peer: entry.chat.id, name: entry.chat.name })
      return
    }
    if (entry.message) {
      cancelRequest()
      message = entry.message
      messagePage = 0
      void render().catch(reportError)
      return
    }
    if (entry.page) {
      cancelRequest()
      if (entry.page === 'older') {
        cursors.push(before)
        before = oldest
      } else before = cursors.pop()
      selected = 0
      entries = []
      notice = 'Loading messages…'
      void render().catch(reportError)
      void refreshInbox().catch(reportError)
    }
  }
  const unsubscribe = bridge.onEvenHubEvent((event) => {
    if (disposed || exiting) return
    const types = [
      event.sysEvent?.eventType,
      event.textEvent?.eventType,
      event.listEvent?.eventType,
    ]
    if (
      types.includes(OsEventTypeList.SYSTEM_EXIT_EVENT) ||
      types.includes(OsEventTypeList.ABNORMAL_EXIT_EVENT)
    ) {
      dispose()
      return
    }
    if (event.menuItemClickEvent) {
      if (event.menuItemClickEvent.itemID === 1) open({ kind: 'channel' })
      else if (event.menuItemClickEvent.itemID === 2) open({ kind: 'chats' })
      return
    }
    if (types.includes(OsEventTypeList.DOUBLE_CLICK_EVENT)) {
      if (message) {
        message = undefined
        void render().catch(reportError)
        return
      }
      if (screen.kind === 'direct') {
        open({ kind: 'chats' })
        return
      }
      exiting = true
      cancelRequest()
      void enqueue(async () => {
        if (disposed) return
        const success = await bridge.shutDownPageContainer(0)
        if (!success) throw new Error('The Even host could not close the HUD.')
        dispose()
      })
        .catch(reportError)
        .finally(() => {
          exiting = false
        })
      return
    }
    const direction = types.includes(OsEventTypeList.SCROLL_BOTTOM_EVENT)
      ? 1
      : types.includes(OsEventTypeList.SCROLL_TOP_EVENT)
        ? -1
        : 0
    if (direction) {
      if (message) messagePage = Math.max(0, messagePage + direction)
      else
        selected = Math.max(
          0,
          Math.min(entries.length - 1, selected + direction),
        )
      void render().catch(reportError)
    } else if (
      (event.sysEvent && (event.sysEvent.eventType ?? 0) === 0) ||
      (event.textEvent && (event.textEvent.eventType ?? 0) === 0)
    )
      select()
  })
  if (options.logo) {
    await enqueue(async () => {
      if (disposed) return
      const result = await bridge.updateImageRawData(
        new ImageRawDataUpdate({
          containerID: 6,
          containerName: 'logo',
          imageData: options.logo,
        }),
      )
      if (result !== ImageRawDataUpdateResult.success)
        throw new Error(`Unable to display the glasses logo (${result}).`)
    }).catch(reportError)
  }
  return {
    dispose,
    refreshInbox,
    async update(next) {
      snapshot = next
      await render()
    },
  }
}
