import {
  CreateStartUpPageContainer,
  OsEventTypeList,
  StartUpPageCreateResult,
  TextContainerProperty,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk'
import type { MeshCoreSnapshot } from './meshcore/source.ts'

type HudBridge = Pick<
  EvenAppBridge,
  'createStartUpPageContainer' | 'onEvenHubEvent' | 'shutDownPageContainer'
>

/** Start one static page. A future live adapter can add text-only updates here. */
export async function startHud(
  bridge: HudBridge,
  snapshot: MeshCoreSnapshot,
  reportError: (error: unknown) => void,
): Promise<() => void> {
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
          content: [
            'MeshCore HUD',
            '',
            snapshot.mode === 'demo' ? 'DEMO MODE' : 'LIVE MODE',
            `Companion: ${snapshot.connection}`,
            snapshot.mode === 'demo' ? 'No live radio data.' : '',
            '',
            'Double-tap to exit.',
          ].join('\n'),
        }),
      ],
    }),
  )
  if (result !== StartUpPageCreateResult.success) {
    throw new Error(`HUD page creation failed (SDK result ${result}).`)
  }

  let exiting = false
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
      unsubscribe()
      return
    }
    if (!types.includes(OsEventTypeList.DOUBLE_CLICK_EVENT) || exiting) return

    exiting = true
    void bridge
      .shutDownPageContainer(0)
      .then((success) => {
        if (!success) throw new Error('The Even host could not close the HUD.')
        unsubscribe()
      })
      .catch(reportError)
      .finally(() => {
        exiting = false
      })
  })
  return unsubscribe
}
