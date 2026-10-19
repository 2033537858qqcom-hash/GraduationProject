import * as THREE from 'three'

export const overviewTarget = new THREE.Vector3(-3, -10, 0)

// Fit the entire model envelope, including its base and tallest roof, at any viewport ratio.
export function campusOverviewPosition(aspect: number, bounds = { minX: -128, maxX: 114, minZ: -102, maxZ: 99 }, target = overviewTarget) {
  const probe = new THREE.PerspectiveCamera(38, aspect, 0.5, 1200)
  const position = new THREE.Vector3(145, 175, 170)
  const point = new THREE.Vector3()
  for (let attempt = 0; attempt < 40; attempt++) {
    probe.position.copy(position); probe.lookAt(target); probe.updateMatrixWorld()
    let fits = true
    for (const x of [bounds.minX, bounds.maxX]) for (const z of [bounds.minZ, bounds.maxZ]) for (const y of [-4, 8]) {
      point.set(x, y, z).project(probe)
      if (Math.abs(point.x) > 0.88 || Math.abs(point.y) > 0.88) fits = false
    }
    if (fits) break
    position.multiplyScalar(1.04)
  }
  return position
}
