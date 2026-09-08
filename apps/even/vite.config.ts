import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  base: './',
  server: { host: true, port: 5173, strictPort: true },
  build: {
    target: 'es2022',
    outDir: fileURLToPath(new URL('../../dist/even', import.meta.url)),
    emptyOutDir: true,
  },
})
