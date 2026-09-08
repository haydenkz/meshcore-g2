import type { EvenAppBridge } from '@evenrealities/even_hub_sdk'

/** The native bridge shares one device link, including persistent-storage calls. */
export function queueBridge(bridge: EvenAppBridge) {
  let tail: Promise<unknown> = Promise.resolve()
  function call<T>(work: () => Promise<T>): Promise<T> {
    const result = tail.then(work)
    // Keep the actual call in the queue even if its caller times out. Starting a
    // second native operation before the first settles can corrupt the link.
    tail = result.catch(() => {})
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(
        () =>
          reject(
            new Error('The glasses connection timed out. Reopen the app.'),
          ),
        10000,
      )
      result
        .then(resolve, reject)
        .finally(() => clearTimeout(timeout))
        .catch(() => {})
    })
  }
  return {
    getLocalStorage: (key: string) => call(() => bridge.getLocalStorage(key)),
    setLocalStorage: (key: string, value: string) =>
      call(() => bridge.setLocalStorage(key, value)),
    createStartUpPageContainer: (
      page: Parameters<EvenAppBridge['createStartUpPageContainer']>[0],
    ) => call(() => bridge.createStartUpPageContainer(page)),
    textContainerUpgrade: (
      page: Parameters<EvenAppBridge['textContainerUpgrade']>[0],
    ) => call(() => bridge.textContainerUpgrade(page)),
    updateImageRawData: (
      image: Parameters<EvenAppBridge['updateImageRawData']>[0],
    ) => call(() => bridge.updateImageRawData(image)),
    shutDownPageContainer: (mode?: number) =>
      call(() => bridge.shutDownPageContainer(mode)),
    onEvenHubEvent: bridge.onEvenHubEvent.bind(bridge),
  }
}
