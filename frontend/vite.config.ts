import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { amapProxy, amapProxyGuard } from './server/amapProxy.ts'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, fileURLToPath(new URL('.', import.meta.url)), '')
  const mapSecret = env.AMAP_SECURITY_JSCODE ?? ''
  const proxy = {
    '/api': { target: process.env.CAMPUS_API_TARGET ?? 'http://127.0.0.1:7080', changeOrigin: true },
    ...amapProxy(mapSecret),
  }
  return {
    plugins: [vue(), amapProxyGuard(mapSecret)],
    server: { port: 5173, strictPort: true, proxy, fs: { allow: ['..'] } },
    preview: { proxy },
    build: {
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules/three')) {
              return 'three-vendor'
            }
            if (id.includes('node_modules/@lucide/vue')) {
              return 'lucide-vendor'
            }
          },
        },
      },
    },
  }
})
