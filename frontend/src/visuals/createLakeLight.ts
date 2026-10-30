import * as THREE from 'three'

export interface LakeLightEffect {
  setRunning: (running: boolean) => void
  dispose: () => void
}

// This mask follows the water inside campus-lakeside.webp, in source-image coordinates.
// It stays clear of the building, trees and the fence on the near shore.
const fragmentShader = `
  uniform float uTime;
  uniform float uFrameAspect;
  uniform float uImageAspect;
  varying vec2 vUv;
  void main() {
    vec2 imageUv = vUv - 0.5;
    if (uFrameAspect > uImageAspect) imageUv.y *= uImageAspect / uFrameAspect;
    else imageUv.x *= uFrameAspect / uImageAspect;
    imageUv += 0.5;
    float x = imageUv.x;
    float y = 1.0 - imageUv.y;
    float farBank = 0.662 - 0.010 * x;
    float nearBank = 0.773 - 0.45 * pow(x - 0.64, 2.0);
    float mask = smoothstep(0.16, 0.21, x) * (1.0 - smoothstep(0.90, 0.94, x));
    mask *= smoothstep(farBank + 0.004, farBank + 0.018, y);
    mask *= 1.0 - smoothstep(nearBank - 0.018, nearBank, y);
    float a = 0.5 + 0.5 * sin(x * 170.0 + y * 510.0 + uTime * 0.85 + sin(x * 35.0 - uTime * 0.4) * 2.0);
    float b = 0.5 + 0.5 * sin(x * 66.0 - y * 720.0 - uTime * 1.1);
    float sunlight = exp(-pow((x - 0.70) * 3.0, 2.0));
    float shimmer = pow(a * b, 2.5) * (0.08 + sunlight * 0.18);
    gl_FragColor = vec4(0.98, 0.92, 0.72, mask * shimmer);
  }
`

export function createLakeLight(host: HTMLElement, imageAspect: number, unavailable: () => void): LakeLightEffect {
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: false, powerPreference: 'low-power' })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.25))
  renderer.setClearColor(0, 0)
  renderer.domElement.setAttribute('aria-hidden', 'true')
  renderer.domElement.dataset.effect = 'lake-light'
  const scene = new THREE.Scene()
  const camera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
  const geometry = new THREE.PlaneGeometry(2, 2)
  const material = new THREE.ShaderMaterial({
    transparent: true,
    depthTest: false,
    depthWrite: false,
    uniforms: {
      uTime: { value: 0 },
      uFrameAspect: { value: 1 },
      uImageAspect: { value: imageAspect },
    },
    vertexShader: 'varying vec2 vUv; void main() { vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }',
    fragmentShader,
  })
  const plane = new THREE.Mesh(geometry, material)
  plane.frustumCulled = false
  scene.add(plane)
  let running = false
  let disposed = false
  let lost = false
  let frame = 0
  let lastTime = 0

  function resize() {
    const width = Math.max(1, host.clientWidth), height = Math.max(1, host.clientHeight)
    renderer.setSize(width, height, false)
    material.uniforms.uFrameAspect!.value = width / height
  }
  function render(time: number) {
    frame = 0
    if (!running || disposed || lost) return
    if (lastTime) material.uniforms.uTime!.value += Math.min((time - lastTime) / 1000, 0.05)
    lastTime = time
    renderer.render(scene, camera)
    frame = requestAnimationFrame(render)
  }
  function setRunning(next: boolean) {
    if (disposed || lost) return
    running = next
    lastTime = 0
    cancelAnimationFrame(frame)
    frame = next ? requestAnimationFrame(render) : 0
    renderer.domElement.dataset.running = String(next)
  }
  function contextLost(event: Event) {
    event.preventDefault()
    setRunning(false)
    lost = true
    renderer.domElement.style.display = 'none'
    unavailable()
  }
  renderer.domElement.addEventListener('webglcontextlost', contextLost)
  host.appendChild(renderer.domElement)
  const observer = new ResizeObserver(resize)
  observer.observe(host)
  resize()

  return {
    setRunning,
    dispose() {
      if (disposed) return
      setRunning(false)
      disposed = true
      observer.disconnect()
      renderer.domElement.removeEventListener('webglcontextlost', contextLost)
      geometry.dispose()
      material.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
    },
  }
}
