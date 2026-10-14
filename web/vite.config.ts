import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the Ktor API runs on :8080; in production Ktor serves this build directly.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
