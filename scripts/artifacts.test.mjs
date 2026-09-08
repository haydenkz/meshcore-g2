import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createHash } from 'node:crypto'
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { androidMetadata, bundle, names, project } from './artifacts.mjs'

function fixture(t, variant = 'debug') {
  const root = mkdtempSync(join(tmpdir(), 'meshcore-bundle-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  mkdirSync(join(root, 'apps/even'), { recursive: true })
  mkdirSync(join(root, 'dist/packages'), { recursive: true })
  const json = (file, value) =>
    writeFileSync(join(root, file), JSON.stringify(value))
  json('package.json', {
    version: '0.2.2',
    config: { androidVersionCode: 5 },
    dependencies: { '@evenrealities/even_hub_sdk': '0.0.15' },
  })
  json('apps/even/app.json', { version: '0.2.2', min_sdk_version: '0.0.15' })
  writeFileSync(join(root, 'README.md'), 'Installation instructions')
  const product = project(root)
  const files = names(product.version, variant)
  for (const [component, filename] of Object.entries(files)) {
    writeFileSync(join(root, 'dist/packages', filename), component)
    json('dist/packages/' + filename + '.json', {
      ...product,
      component,
      filename,
      variant: component === 'even' ? 'even' : variant,
      sha256: createHash('sha256').update(component).digest('hex'),
      source: { commit: 'test-commit', dirty: false },
    })
  }
  const alter = (component, changes) => {
    const path = 'dist/packages/' + files[component] + '.json'
    json(path, { ...JSON.parse(readFileSync(join(root, path))), ...changes })
  }
  return { root, files, alter, json }
}

test('installer bundle includes both apps, portable instructions and verifiable checksums', (t) => {
  const { root } = fixture(t)
  const out = bundle('debug', root)
  assert.equal(
    readFileSync(join(out, 'INSTALL.md'), 'utf8'),
    'Installation instructions',
  )
  const checksums = readFileSync(join(out, 'SHA256SUMS'), 'utf8')
    .trim()
    .split('\n')
  assert.equal(checksums.length, 5)
  for (const line of checksums) {
    const [hash, file] = line.split('  ')
    assert.equal(
      createHash('sha256')
        .update(readFileSync(join(out, file)))
        .digest('hex'),
      hash,
    )
  }
})

test('bundle rejects modified assets and mixed source revisions', (t) => {
  const { root, files, alter } = fixture(t)
  alter('android', { source: { commit: 'different', dirty: false } })
  assert.throws(() => bundle('debug', root), /same source revision/)
  alter('android', { source: { commit: 'test-commit', dirty: false } })
  writeFileSync(join(root, 'dist/packages', files.even), 'corrupted')
  assert.throws(() => bundle('debug', root), /checksum failure/)
})

test('release bundle rejects development artifacts and dirty sources', (t) => {
  const { root, alter } = fixture(t, 'release')
  alter('android', { variant: 'debug' })
  assert.throws(() => bundle('release', root), /Artifact mismatch/)
  alter('android', {
    variant: 'release',
    source: { commit: 'test-commit', dirty: true },
  })
  alter('even', { source: { commit: 'test-commit', dirty: true } })
  assert.throws(() => bundle('release', root), /clean worktree/)
})

test('version gate rejects incompatible Even and Android release metadata', (t) => {
  const { root, json } = fixture(t)
  json('apps/even/app.json', { version: '9.0.0', min_sdk_version: '0.0.15' })
  assert.throws(() => project(root), /versions must match/)
  const product = { version: '0.2.2', androidVersionCode: 5 }
  const apk = {
    applicationId: 'io.github.haydenkz.meshcorehelper',
    elements: [
      { versionName: '0.2.2', versionCode: 5, outputFile: 'app-release.apk' },
    ],
  }
  assert.equal(androidMetadata(apk, product, 'release'), 'app-release.apk')
  assert.throws(() => androidMetadata(apk, product, 'debug'), /APK metadata/)
  apk.elements[0].outputFile = 'app-release-unsigned.apk'
  assert.throws(() => androidMetadata(apk, product, 'release'), /APK metadata/)
})
