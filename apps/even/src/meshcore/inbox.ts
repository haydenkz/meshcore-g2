export interface ReceivedMessage {
  readonly id: number
  readonly kind: 'channel' | 'direct'
  readonly conversationId: string
  readonly conversationName: string
  readonly senderName: string
  readonly text: string
  readonly sentAt: number
  readonly receivedAt: number
  readonly direction?: 'in' | 'out'
  readonly delivery?: string
}

export interface RecentAdvert {
  readonly id: number
  readonly name: string
  readonly publicKeyPrefix: string
  readonly nodeType: string
  readonly receivedAt: number
}

export interface DirectChat {
  readonly id: string
  readonly name: string
  readonly lastMessageId: number
  readonly updatedAt: number
  readonly preview: string
}

export interface InboxPage<T> {
  readonly items: readonly T[]
  readonly hasMore: boolean
}

export interface InboxSource {
  readMessages(
    kind: 'channel' | 'direct',
    peer?: string,
    before?: number,
    signal?: AbortSignal,
  ): Promise<InboxPage<ReceivedMessage>>
  readChats(
    before?: number,
    signal?: AbortSignal,
  ): Promise<InboxPage<DirectChat>>
  readAdverts?(
    before?: number,
    signal?: AbortSignal,
  ): Promise<InboxPage<RecentAdvert>>
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value))
    throw new Error('Invalid message history.')
  return value as Record<string, unknown>
}
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max)
    throw new Error('Invalid message history.')
  return value.replace(/\p{Cc}/gu, ' ').trim()
}
function number(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0)
    throw new Error('Invalid message history.')
  return value
}
function page<T>(value: unknown, parse: (item: unknown) => T): InboxPage<T> {
  const data = object(value)
  if (
    data.schema !== 1 ||
    !Array.isArray(data.items) ||
    data.items.length > 16 ||
    typeof data.hasMore !== 'boolean'
  )
    throw new Error('Update the phone helper to read message history.')
  return { items: data.items.map(parse), hasMore: data.hasMore }
}
export function parseMessages(value: unknown): InboxPage<ReceivedMessage> {
  return page(value, (value) => {
    const item = object(value)
    if (item.kind !== 'channel' && item.kind !== 'direct')
      throw new Error('Invalid message type.')
    if (
      item.direction !== undefined &&
      item.direction !== 'in' &&
      item.direction !== 'out'
    )
      throw new Error('Invalid message direction.')
    if (
      item.delivery !== undefined &&
      ![
        'received',
        'queued',
        'sending',
        'sent',
        'awaiting_ack',
        'delivered',
        'failed',
        'unconfirmed',
      ].includes(String(item.delivery))
    )
      throw new Error('Invalid delivery status.')
    return {
      id: number(item.id),
      kind: item.kind,
      conversationId: text(item.conversationId, 80),
      conversationName: text(item.conversationName, 80),
      senderName: text(item.senderName, 80),
      text: text(item.text, 1024),
      sentAt: number(item.sentAt),
      receivedAt: number(item.receivedAt),
      direction: item.direction === 'out' ? 'out' : 'in',
      delivery:
        item.delivery === undefined ? 'received' : text(item.delivery, 24),
    }
  })
}

export function parseAdverts(value: unknown): InboxPage<RecentAdvert> {
  return page(value, (value) => {
    const item = object(value)
    const prefix = text(item.publicKeyPrefix, 12)
    if (!/^[a-f0-9]{12}$/.test(prefix))
      throw new Error('Invalid advert identity.')
    return {
      id: number(item.id),
      name: text(item.name, 80),
      publicKeyPrefix: prefix,
      nodeType: text(item.nodeType, 32),
      receivedAt: number(item.receivedAt),
    }
  })
}
export function parseChats(value: unknown): InboxPage<DirectChat> {
  return page(value, (value) => {
    const item = object(value)
    return {
      id: text(item.id, 80),
      name: text(item.name, 80),
      lastMessageId: number(item.lastMessageId),
      updatedAt: number(item.updatedAt),
      preview: text(item.preview, 1024),
    }
  })
}
