import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

const backend = process.env.BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // The backend packages this directory into its jar at `web/`.
    outDir: 'dist',
    emptyOutDir: true,
  },
  server: {
    // In production the Kotlin backend serves this bundle, so everything is
    // same-origin. In dev we proxy to it and keep the paths identical.
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/ws': { target: backend, ws: true, changeOrigin: true },
    },
  },
})
