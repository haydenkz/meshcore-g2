import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import {
  copyFileSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { basename, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const repo = fileURLToPath(new URL('..', import.meta.url))
const readJson = (path) => JSON.parse(readFileSync(path, 'utf8'))
const writeJson = (path, value) =>
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n')
const digest = (path) =>
  createHash('sha256').update(readFileSync(path)).digest('hex')

export function project(root = repo) {
  const pkg = readJson(join(root, 'package.json'))
  const manifest = readJson(join(root, 'apps/even/app.json'))
  if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(pkg.version))
    throw new Error('Use an x.y.z product version in package.json.')
  if (manifest.version !== pkg.version)
    throw new Error('Even manifest and product versions must match.')
  if (
    manifest.min_sdk_version !== pkg.dependencies['@evenrealities/even_hub_sdk']
  )
    throw new Error('Even manifest must match the pinned SDK version.')
  const code = pkg.config?.androidVersionCode
  if (!Number.isInteger(code) || code < 1 || code > 2100000000)
    throw new Error(
      'config.androidVersionCode must be a positive Android version code.',
    )
  return {
    version: pkg.version,
    androidVersionCode: code,
    sdk: manifest.min_sdk_version,
  }
}

export function names(version, variant) {
  if (!['debug', 'release'].includes(variant))
    throw new Error('Choose debug or release.')
  return {
    even: `meshcore-g2-even-${version}.ehpk`,
    android: `meshcore-g2-android-${version}${variant === 'debug' ? '-dev' : ''}.apk`,
  }
}

function source() {
  return {
    commit: execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: repo,
      encoding: 'utf8',
    }).trim(),
    dirty:
      execFileSync('git', ['status', '--porcelain'], {
        cwd: repo,
        encoding: 'utf8',
      }).trim() !== '',
  }
}

function record(path, component, variant, product) {
  writeJson(path + '.json', {
    ...product,
    component,
    variant,
    filename: basename(path),
    sha256: digest(path),
    source: source(),
  })
}

export function androidMetadata(metadata, product, variant) {
  names(product.version, variant)
  const expectedId =
    'io.github.haydenkz.meshcorehelper' + (variant === 'debug' ? '.debug' : '')
  const expectedVersion = product.version + (variant === 'debug' ? '-dev' : '')
  const [apk] = metadata.elements ?? []
  if (
    metadata.applicationId !== expectedId ||
    metadata.elements?.length !== 1 ||
    apk.versionName !== expectedVersion ||
    apk.versionCode !== product.androidVersionCode ||
    typeof apk.outputFile !== 'string' ||
    basename(apk.outputFile) !== apk.outputFile ||
    !apk.outputFile.endsWith('.apk') ||
    apk.outputFile.includes('unsigned')
  )
    throw new Error(
      'Android APK metadata does not match the requested product/version/variant.',
    )
  return apk.outputFile
}

export function bundle(variant, root = repo) {
  const product = project(root)
  const files = names(product.version, variant)
  const packages = join(root, 'dist/packages')
  const records = {}
  for (const [component, filename] of Object.entries(files)) {
    const metadata = readJson(join(packages, filename + '.json'))
    if (
      metadata.component !== component ||
      metadata.filename !== filename ||
      metadata.version !== product.version ||
      metadata.sdk !== product.sdk ||
      metadata.androidVersionCode !== product.androidVersionCode ||
      metadata.variant !== (component === 'even' ? 'even' : variant) ||
      metadata.sha256 !== digest(join(packages, filename))
    )
      throw new Error(`Artifact mismatch or checksum failure: ${filename}`)
    records[component] = metadata
  }
  if (
    !records.even.source?.commit ||
    records.even.source.commit !== records.android.source?.commit ||
    records.even.source.dirty !== records.android.source.dirty
  )
    throw new Error(
      'Both applications must come from the same source revision.',
    )
  if (variant === 'release' && records.even.source.dirty)
    throw new Error('Release bundles require a clean worktree.')
  const out = join(root, 'dist/bundle')
  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })
  for (const filename of Object.values(files))
    copyFileSync(join(packages, filename), join(out, filename))
  copyFileSync(join(root, 'README.md'), join(out, 'INSTALL.md'))
  writeJson(join(out, 'bundle.json'), {
    ...product,
    variant,
    applications: records,
  })
  writeFileSync(
    join(out, 'RELEASE_NOTES.md'),
    `MeshCore G2 ${product.version}\n\n` +
      `This pair contains the Android phone helper and the Even Hub plugin.\n\n` +
      `- Android: \`${files.android}\`\n- Even Hub: \`${files.even}\`\n` +
      `- Source: \`${records.even.source.commit}\`\n\n` +
      `Follow INSTALL.md to install and link both apps. The Even package requires Even Hub beta access or your own developer project; downloading it alone does not install it.\n\n` +
      (variant === 'debug'
        ? 'Development APK: separate app ID, debug signature. Stop any other helper before starting it.\n'
        : 'Maintainer: assign this exact Even package to the beta group and test this pair before publishing the draft.\n'),
  )
  const included = [
    ...Object.values(files),
    'INSTALL.md',
    'bundle.json',
    'RELEASE_NOTES.md',
  ]
  writeFileSync(
    join(out, 'SHA256SUMS'),
    included.map((file) => `${digest(join(out, file))}  ${file}\n`).join(''),
  )
  return out
}

function main() {
  const [command, variant] = process.argv.slice(2)
  const product = project()
  const packages = join(repo, 'dist/packages')
  if (command === 'check')
    return console.log(
      `Both applications: ${product.version}; Android code ${product.androidVersionCode}`,
    )
  if (command === 'even') {
    mkdirSync(packages, { recursive: true })
    const out = join(packages, names(product.version, 'release').even)
    execFileSync(
      process.execPath,
      [
        join(repo, 'node_modules/@evenrealities/evenhub-cli/main.js'),
        'pack',
        'apps/even/app.json',
        'dist/even',
        '-o',
        out,
        '--sdk-ver',
        product.sdk,
      ],
      { cwd: repo, stdio: 'inherit' },
    )
    record(out, 'even', 'even', product)
  } else if (command === 'android') {
    const filename = names(product.version, variant).android
    const output = join(repo, 'apps/android/app/build/outputs/apk', variant)
    const apk = androidMetadata(
      readJson(join(output, 'output-metadata.json')),
      product,
      variant,
    )
    mkdirSync(packages, { recursive: true })
    const out = join(packages, filename)
    copyFileSync(join(output, apk), out)
    record(out, 'android', variant, product)
  } else if (command === 'bundle') {
    console.log(`Paired installer files: ${bundle(variant)}`)
  } else {
    throw new Error(
      'Usage: node scripts/artifacts.mjs check|even|android debug|android release|bundle debug|bundle release',
    )
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
)
  main()
