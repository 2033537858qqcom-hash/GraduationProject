import type { ProxyOptions, Plugin, ViteDevServer } from 'vite'

export const amapServicePrefix = '/_AMapService'

// Fixed upstreams only. The browser never receives the JS security secret.
export function amapProxyPath(path: string, secret: string) {
  if (!path.startsWith(`${amapServicePrefix}/`)) throw new Error('Invalid map service path')
  const url = new URL(path.slice(amapServicePrefix.length), 'https://restapi.amap.com')
  if (url.origin !== 'https://restapi.amap.com') throw new Error('Invalid map upstream')
  url.searchParams.set('jscode', secret)
  return `${url.pathname}${url.search}`
}

export function amapProxy(secret: string): Record<string, ProxyOptions> {
  const options = (target: string): ProxyOptions => ({
    target,
    changeOrigin: true,
    secure: true,
    rewrite: path => amapProxyPath(path, secret),
    configure(proxy) {
      proxy.on('proxyReq', request => {
        for (const header of ['cookie', 'authorization', 'proxy-authorization', 'x-csrf-token', 'x-xsrf-token']) request.removeHeader(header)
      })
      proxy.on('proxyRes', response => { delete response.headers['set-cookie'] })
    },
  })
  return {
    [`${amapServicePrefix}/v4/map/styles`]: options('https://webapi.amap.com'),
    [amapServicePrefix]: options('https://restapi.amap.com'),
  }
}

export function amapProxyGuard(secret: string): Plugin {
  const install = (server: Pick<ViteDevServer, 'middlewares'>) => {
    server.middlewares.use((request, response, next) => {
      if (!request.url?.startsWith(`${amapServicePrefix}/`)) return next()
      if (!['GET', 'HEAD'].includes(request.method ?? '')) {
        response.writeHead(405, { Allow: 'GET, HEAD' }); response.end(); return
      }
      try { amapProxyPath(request.url, secret) } catch {
        response.writeHead(400); response.end('Invalid map service path'); return
      }
      if (!secret) { response.writeHead(503); response.end('Map service is not configured'); return }
      next()
    })
  }
  return { name: 'campus-amap-service-guard', configureServer: install, configurePreviewServer: install }
}
