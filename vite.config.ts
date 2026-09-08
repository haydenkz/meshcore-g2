import { defineConfig } from 'vite'

export default defineConfig({
  base: './',
  server: { host: true, port: 5173, strictPort: true },
  build: { target: 'es2022' },
})
