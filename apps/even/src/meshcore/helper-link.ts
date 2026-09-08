export const HELPER_LINK_STORAGE_KEY = 'meshcore-g2.phone-helper-key.v1'

export interface HelperStorage {
  getLocalStorage(key: string): Promise<string>
  setLocalStorage(key: string, value: string): Promise<boolean>
}

export function normalizeHelperKey(value: string): string {
  const key = value.trim()
  if (!/^[a-f0-9]{64}$/.test(key))
    throw new Error('Paste the full connection key from the Android helper.')
  return key
}

/** Even host storage survives restarts. Serialize changes so forgetting wins
 * over an earlier save that is still in flight. Keys never enter a URL. */
export class HelperLinkStore {
  private writes: Promise<boolean> = Promise.resolve(true)
  private readonly storage: HelperStorage
  constructor(storage: HelperStorage) {
    this.storage = storage
  }

  async load(): Promise<string | undefined> {
    const value = await this.storage.getLocalStorage(HELPER_LINK_STORAGE_KEY)
    if (!value) return undefined
    return normalizeHelperKey(value)
  }

  save(key?: string): Promise<boolean> {
    const value = key === undefined ? '' : normalizeHelperKey(key)
    this.writes = this.writes
      .then(() => this.storage.setLocalStorage(HELPER_LINK_STORAGE_KEY, value))
      .catch(() => false)
    return this.writes
  }
}
