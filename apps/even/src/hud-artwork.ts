import logoUrl from '../../../assets/even-icon-foreground.png'

export const HUD_LOGO_WIDTH = 48
export const HUD_LOGO_HEIGHT = 40

/** Render the existing monochrome artwork at the image container's native size. */
export async function loadHudLogo(): Promise<Uint8Array> {
  const image = new Image()
  image.src = logoUrl
  await image.decode()
  const source = document.createElement('canvas')
  source.width = image.naturalWidth
  source.height = image.naturalHeight
  const pixels = source.getContext('2d')!
  pixels.drawImage(image, 0, 0)
  const data = pixels.getImageData(0, 0, source.width, source.height).data
  let left = source.width,
    top = source.height,
    right = 0,
    bottom = 0
  for (let y = 0; y < source.height; y++)
    for (let x = 0; x < source.width; x++) {
      if (data[(y * source.width + x) * 4]! > 128) {
        left = Math.min(left, x)
        top = Math.min(top, y)
        right = Math.max(right, x)
        bottom = Math.max(bottom, y)
      }
    }
  if (right < left) throw new Error('The glasses logo has no visible pixels.')
  const canvas = document.createElement('canvas')
  canvas.width = HUD_LOGO_WIDTH
  canvas.height = HUD_LOGO_HEIGHT
  const context = canvas.getContext('2d')!
  context.fillStyle = '#000'
  context.fillRect(0, 0, canvas.width, canvas.height)
  context.imageSmoothingEnabled = false
  const scale = Math.min(
    (canvas.width - 4) / (right - left + 1),
    (canvas.height - 4) / (bottom - top + 1),
  )
  const width = Math.round((right - left + 1) * scale),
    height = Math.round((bottom - top + 1) * scale)
  context.drawImage(
    source,
    left,
    top,
    right - left + 1,
    bottom - top + 1,
    Math.floor((canvas.width - width) / 2),
    Math.floor((canvas.height - height) / 2),
    width,
    height,
  )
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(
      (value) =>
        value
          ? resolve(value)
          : reject(new Error('Unable to render the glasses logo.')),
      'image/png',
    ),
  )
  return new Uint8Array(await blob.arrayBuffer())
}
