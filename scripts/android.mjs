import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const tasks = {
  check: ['testDebugUnitTest', 'lintDebug', 'assembleDebug'],
  build: ['assembleDebug'],
  release: ['assembleRelease', 'lintRelease'],
}[process.argv[2]]
if (!tasks)
  throw new Error('Usage: node scripts/android.mjs check|build|release')

const windows = process.platform === 'win32'
const result = spawnSync(
  windows ? 'gradlew.bat' : './gradlew',
  [...tasks, '--console=plain', '--no-daemon'],
  {
    cwd: fileURLToPath(new URL('../apps/android', import.meta.url)),
    stdio: 'inherit',
    shell: windows,
  },
)
if (result.error) throw result.error
process.exit(result.status ?? 1)
