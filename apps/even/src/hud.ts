import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  TextContainerUpgrade,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import type { MeshCoreSnapshot } from './meshcore/source.ts'

type HudBridge = Pick<
  EvenAppBridge,
  | 'createStartUpPageContainer'
  | 'onEvenHubEvent'
  | 'shutDownPageContainer'
  | 'textContainerUpgrade'
>

function content(snapshot: MeshCoreSnapshot): string {
  return [
    'MeshCore G2',
    '',
    snapshot.mode === 'demo' ? 'DEMO MODE' : 'PHONE BLE',
    `Companion: ${snapshot.connection}`,
    snapshot.mode === 'demo'
      ? 'No live radio data.'
      : snapshot.deviceName || 'Open the helper on your phone.',
    snapshot.batteryMillivolts === undefined
      ? ''
      : `Battery: ${snapshot.batteryMillivolts} mV`,
    '',
    'Double-tap to exit.',
  ].join('\n')
}

export interface HudController {
  update(snapshot: MeshCoreSnapshot): Promise<void>
  dispose(): void
}

export async function startHud(
  bridge: HudBridge,
  snapshot: MeshCoreSnapshot,
  reportError: (error: unknown) => void,
  onExit: () => void = () => {},
): Promise<HudController> {
  const result = await bridge.createStartUpPageContainer(
    new CreateStartUpPageContainer({
      containerTotalNum: 1,
      textObject: [
        new TextContainerProperty({
          xPosition: 0,
          yPosition: 0,
          width: 576,
          height: 288,
          borderWidth: 0,
          paddingLength: 16,
          containerID: 1,
          containerName: 'meshcorehud',
          isEventCapture: 1,
          content: content(snapshot),
        }),
      ],
    }),
  )
  if (result !== StartUpPageCreateResult.success)
    throw new Error(`HUD page creation failed (SDK result ${result}).`)

  let exiting = false
  let disposed = false
  let lastContent = content(snapshot)
  let sequence = Promise.resolve()
  function enqueue(work: () => Promise<void>) {
    const result = sequence.then(work)
    sequence = result.catch(() => {})
    return result
  }
  function dispose() {
    if (disposed) return
    disposed = true
    unsubscribe()
    onExit()
  }
  const unsubscribe = bridge.onEvenHubEvent((event) => {
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
    if (
      !types.includes(OsEventTypeList.DOUBLE_CLICK_EVENT) ||
      exiting ||
      disposed
    )
      return
    exiting = true
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
  })
  return {
    dispose,
    update(next) {
      const nextContent = content(next)
      return enqueue(async () => {
        if (disposed || exiting || nextContent === lastContent) return
        const success = await bridge.textContainerUpgrade(
          new TextContainerUpgrade({
            containerID: 1,
            containerName: 'meshcorehud',
            contentOffset: 0,
            contentLength: 0,
            content: nextContent,
          }),
        )
        if (!success) throw new Error('The Even host could not update the HUD.')
        lastContent = nextContent
      })
    },
  }
}
