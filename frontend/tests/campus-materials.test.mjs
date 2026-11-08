import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import * as THREE from 'three'
import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'

let server, buildCampus
before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)),
    configFile: false,
    cacheDir: fileURLToPath(new URL('../node_modules/.vite-tests/campus-materials', import.meta.url)),
    logLevel: 'error',
    server: { middlewareMode: true, hmr: false, ws: false },
  })
  ;({ buildCampus } = await server.ssrLoadModule('/src/campus/buildCampus.ts'))
})
after(async () => {
  await server?.close()
})

test('campus roofs sample plain stone while facades retain windows without extra meshes', () => {
  for (const campusId of ['EAST', 'WEST']) {
    const scene = new THREE.Group()
    const model = buildCampus(scene, { campusId })
    let facades = 0,
      roofs = 0
    try {
      scene.traverse(object => {
        if (!(object instanceof THREE.Mesh)) return
        const material = object.material
        if (!material.map?.name.startsWith('campus-facade-')) return
        facades++
        const normals = object.geometry.getAttribute('normal'),
          uv = object.geometry.getAttribute('uv')
        for (let i = 0; i < normals.count; i++)
          if (Math.abs(normals.getY(i)) > 0.9) {
            roofs++
            assert.equal(uv.getX(i), 0, `${campusId}: roof must sample plain stone`)
            assert.equal(uv.getY(i), 0, `${campusId}: roof must sample plain stone`)
          }
      })
      assert.ok(facades > 0, `${campusId}: facade detail missing`)
      assert.ok(roofs > 0, `${campusId}: roof faces missing`)
      if (campusId === 'EAST')
        assert.ok(
          scene.children.filter(object => object instanceof THREE.Mesh).length < 60,
          'keep the scene batching budget',
        )
      assert.ok(model.pickables.length > 0, `${campusId}: places must remain selectable`)
    } finally {
      scene.traverse(object => {
        if (object instanceof THREE.Mesh) {
          object.geometry.dispose()
          object.material.dispose()
        }
      })
      model.dispose()
    }
  }
})
