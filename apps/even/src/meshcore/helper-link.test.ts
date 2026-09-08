import assert from 'node:assert/strict'
import { test } from 'node:test'
import { HELPER_LINK_STORAGE_KEY, HelperLinkStore } from './helper-link.ts'

test('restores a saved helper across launches using Even host storage', async () => {
  const values = new Map<string, string>()
  const storage = {
    async getLocalStorage(key: string) {
      return values.get(key) ?? ''
    },
    async setLocalStorage(key: string, value: string) {
      values.set(key, value)
      return true
    },
  }
  const firstLaunch = new HelperLinkStore(storage)
  assert.equal(await firstLaunch.load(), undefined)
  assert.equal(await firstLaunch.save('ab'.repeat(32)), true)
  const secondLaunch = new HelperLinkStore(storage)
  assert.equal(await secondLaunch.load(), 'ab'.repeat(32))
  await secondLaunch.save()
  assert.equal(await new HelperLinkStore(storage).load(), undefined)
})

test('forget waits for a pending save so the old key cannot be restored later', async () => {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  const writes: string[] = []
  const store = new HelperLinkStore({
    async getLocalStorage() {
      return ''
    },
    async setLocalStorage(key, value) {
      assert.equal(key, HELPER_LINK_STORAGE_KEY)
      if (value) await gate
      writes.push(value)
      return true
    },
  })
  const save = store.save('ab'.repeat(32))
  const forget = store.save()
  await Promise.resolve()
  assert.deepEqual(writes, [])
  release()
  await Promise.all([save, forget])
  assert.deepEqual(writes, ['ab'.repeat(32), ''])
})

test('storage failures are reported and do not prevent a later save', async () => {
  let attempts = 0
  const store = new HelperLinkStore({
    async getLocalStorage() {
      return 'invalid saved value'
    },
    async setLocalStorage() {
      if (++attempts === 1) throw new Error('Host unavailable')
      return false
    },
  })
  await assert.rejects(store.load(), /full connection key/)
  assert.equal(await store.save('ab'.repeat(32)), false)
  assert.equal(await store.save(), false)
  assert.equal(attempts, 2)
})
