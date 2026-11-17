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
    // Middleware tests own independent optimizer lifecycles and must never replace the live app cache.
    cacheDir: fileURLToPath(new URL(`node_modules/.vite-app-${mode}`, import.meta.url)),
    optimizeDeps: { include: ['gsap', 'gsap/ScrollTrigger'] },
    build: {
      rolldownOptions: {
        output: {
          codeSplitting: {
            groups: [
              {
                name: 'three-vendor',
                test: /node_modules[\\/]three/,
                priority: 3,
              },
              {
                name: 'gsap-vendor',
                test: /node_modules[\\/]gsap/,
                priority: 2,
              },
              {
                name: 'icons-vendor',
                test: /node_modules[\\/]@lucide[\\/]vue/,
                priority: 1,
              },
            ],
          },
        },
      },
    },
    plugins: [vue(), amapProxyGuard(mapSecret)],
    server: { port: 18090, strictPort: true, proxy, fs: { allow: ['..'] } },
    preview: { proxy },
  }
})
