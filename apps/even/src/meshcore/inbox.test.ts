import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseChats, parseMessages } from './inbox.ts'
import { PhoneHelperSource } from './phone-helper.ts'

const message = {
  id: 4,
  kind: 'direct',
  conversationId: 'radio:alice',
  conversationName: 'Alice',
  senderName: 'Alice',
  text: 'Hello\nthere',
  sentAt: 1700000000000,
  receivedAt: 1700000001000,
}
test('history keeps direct messages and channel messages distinct and rejects malformed pages', () => {
  const result = parseMessages({ schema: 1, items: [message], hasMore: true })
  assert.equal(result.items[0]?.kind, 'direct')
  assert.equal(result.items[0]?.text, 'Hello there')
  assert.equal(result.hasMore, true)
  for (const value of [
    null,
    { schema: 2, items: [], hasMore: false },
    { schema: 1, items: [{ ...message, kind: 'other' }], hasMore: false },
    { schema: 1, items: [{ ...message, id: -1 }], hasMore: false },
    { schema: 1, items: Array(17).fill(message), hasMore: true },
  ])
    assert.throws(() => parseMessages(value))
})
test('chat previews preserve latest-activity order', () => {
  const result = parseChats({
    schema: 1,
    items: [
      {
        id: 'bob',
        name: 'Bob',
        lastMessageId: 6,
        updatedAt: 6000,
        preview: 'Newest',
      },
      {
        id: 'alice',
        name: 'Alice',
        lastMessageId: 4,
        updatedAt: 4000,
        preview: 'Earlier',
      },
    ],
    hasMore: false,
  })
  assert.deepEqual(
    result.items.map((item) => item.name),
    ['Bob', 'Alice'],
  )
})
test('history requests preserve bound fetch, authentication and read-only cursor queries', async () => {
  const urls: string[] = []
  const source = new PhoneHelperSource('ab'.repeat(32), async function (
    this: unknown,
    url,
    options,
  ) {
    assert.equal(this, globalThis)
    assert.equal(
      new Headers(options?.headers).get('Authorization'),
      `Bearer ${'ab'.repeat(32)}`,
    )
    assert.equal(options?.redirect, 'error')
    assert.equal(options?.credentials, 'omit')
    urls.push(String(url))
    return Response.json({ schema: 1, items: [], hasMore: false })
  })
  await source.readMessages('channel')
  await source.readMessages('direct', 'radio:alice', 4)
  await source.readChats(6)
  assert.deepEqual(urls, [
    'http://127.0.0.1:8765/v1/messages?kind=channel',
    'http://127.0.0.1:8765/v1/messages?kind=direct&peer=radio%3Aalice&before=4',
    'http://127.0.0.1:8765/v1/chats?before=6',
  ])
})

test('sent-message direction and delivery status survive syncing to the glasses', () => {
  const result = parseMessages({
    schema: 1,
    items: [{ ...message, direction: 'out', delivery: 'delivered' }],
    hasMore: false,
  })
  assert.equal(result.items[0]?.direction, 'out')
  assert.equal(result.items[0]?.delivery, 'delivered')
  assert.equal(
    parseMessages({ schema: 1, items: [message], hasMore: false }).items[0]
      ?.direction,
    'in',
  )
  for (const change of [{ direction: 'unknown' }, { delivery: 'invented' }])
    assert.throws(() =>
      parseMessages({
        schema: 1,
        items: [{ ...message, ...change }],
        hasMore: false,
      }),
    )
})
test('an older helper gives an actionable update message instead of an empty inbox', async () => {
  const source = new PhoneHelperSource(
    'ab'.repeat(32),
    async () => new Response('', { status: 404 }),
  )
  await assert.rejects(
    source.readMessages('channel'),
    /Update MeshCore G2 for Android/,
  )
})
