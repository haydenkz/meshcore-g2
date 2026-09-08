import js from '@eslint/js'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default defineConfig(
  globalIgnores(['dist/**', 'apps/android/**']),
  js.configs.recommended,
  tseslint.configs.recommended,
  {
    files: ['apps/even/src/**/*.ts'],
    languageOptions: { globals: globals.browser },
  },
  {
    files: [
      '*.js',
      'scripts/**/*.mjs',
      'apps/even/vite.config.ts',
      'apps/even/src/**/*.test.ts',
    ],
    languageOptions: { globals: globals.node },
  },
)
