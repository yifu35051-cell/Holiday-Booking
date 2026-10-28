import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxy = {
    '/api': {
      target: process.env.BACKEND_URL || env.BACKEND_URL || 'http://127.0.0.1:8080',
      changeOrigin: true,
    },
  }
  return {
    server: { host: 'localhost', port: 5173, strictPort: true, proxy },
    preview: {
      host: 'localhost',
      port: 4173,
      strictPort: true,
      proxy,
      headers: {
        'X-Content-Type-Options': 'nosniff',
        'X-Frame-Options': 'DENY',
        'Referrer-Policy': 'same-origin',
        'Content-Security-Policy':
          "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
      },
    },
    // No client environment variables are needed. Never bundle backend credentials.
    envPrefix: [],
    build: { sourcemap: false, minify: true },
    define: { __VUE_PROD_DEVTOOLS__: false },
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
  }
})
