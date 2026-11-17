export interface LakeLightEffect {
  setRunning: (running: boolean) => void
  dispose: () => void
}

// This mask follows the water inside campus-lakeside.webp, in source-image coordinates.
// It stays clear of the building, trees and the fence on the near shore.
const vertexShaderSource = `
  attribute vec2 aPosition;
  varying vec2 vUv;
  void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
`

const fragmentShaderSource = `
  precision mediump float;
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

function compileShader(gl: WebGLRenderingContext, type: number, source: string): WebGLShader {
  const shader = gl.createShader(type)
  if (!shader) throw new Error('Unable to create WebGL shader')
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
    const info = gl.getShaderInfoLog(shader) || 'Shader compilation failed'
    gl.deleteShader(shader)
    throw new Error(info)
  }
  return shader
}

export function createLakeLight(host: HTMLElement, imageAspect: number, unavailable: () => void): LakeLightEffect {
  const canvas = document.createElement('canvas')
  canvas.setAttribute('aria-hidden', 'true')
  canvas.dataset.effect = 'lake-light'

  const contextOptions: WebGLContextAttributes = {
    alpha: true,
    antialias: false,
    depth: false,
    stencil: false,
    powerPreference: 'low-power',
  }
  const gl =
    (canvas.getContext('webgl2', contextOptions) as WebGLRenderingContext | null) ??
    (canvas.getContext('webgl', contextOptions) as WebGLRenderingContext | null)
  if (!gl) throw new Error('WebGL context unavailable')

  const vertexShader = compileShader(gl, gl.VERTEX_SHADER, vertexShaderSource)
  const fragmentShader = compileShader(gl, gl.FRAGMENT_SHADER, fragmentShaderSource)
  const program = gl.createProgram()
  if (!program) {
    gl.deleteShader(vertexShader)
    gl.deleteShader(fragmentShader)
    throw new Error('Unable to create WebGL program')
  }
  gl.attachShader(program, vertexShader)
  gl.attachShader(program, fragmentShader)
  gl.linkProgram(program)
  if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
    const info = gl.getProgramInfoLog(program) || 'Program link failed'
    gl.deleteProgram(program)
    gl.deleteShader(vertexShader)
    gl.deleteShader(fragmentShader)
    throw new Error(info)
  }

  const quad = gl.createBuffer()
  gl.bindBuffer(gl.ARRAY_BUFFER, quad)
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW)

  const aPosition = gl.getAttribLocation(program, 'aPosition')
  const uTimeLoc = gl.getUniformLocation(program, 'uTime')
  const uFrameAspectLoc = gl.getUniformLocation(program, 'uFrameAspect')
  const uImageAspectLoc = gl.getUniformLocation(program, 'uImageAspect')

  let uTime = 0
  let frameAspect = 1
  let running = false
  let disposed = false
  let lost = false
  let frame = 0
  let lastTime = 0

  function draw() {
    if (!gl || disposed || lost) return
    gl.viewport(0, 0, canvas.width, canvas.height)
    gl.clearColor(0, 0, 0, 0)
    gl.clear(gl.COLOR_BUFFER_BIT)
    gl.useProgram(program)
    gl.enable(gl.BLEND)
    gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
    gl.bindBuffer(gl.ARRAY_BUFFER, quad)
    gl.enableVertexAttribArray(aPosition)
    gl.vertexAttribPointer(aPosition, 2, gl.FLOAT, false, 0, 0)
    gl.uniform1f(uTimeLoc, uTime)
    gl.uniform1f(uFrameAspectLoc, frameAspect)
    gl.uniform1f(uImageAspectLoc, imageAspect)
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4)
  }

  function resize() {
    const width = Math.max(1, host.clientWidth)
    const height = Math.max(1, host.clientHeight)
    const ratio = Math.min(window.devicePixelRatio || 1, 1.25)
    canvas.width = Math.max(1, Math.round(width * ratio))
    canvas.height = Math.max(1, Math.round(height * ratio))
    frameAspect = width / height
    if (running) draw()
  }

  function render(time: number) {
    frame = 0
    if (!running || disposed || lost) return
    if (lastTime) uTime += Math.min((time - lastTime) / 1000, 0.05)
    lastTime = time
    draw()
    frame = requestAnimationFrame(render)
  }

  function setRunning(next: boolean) {
    if (disposed || lost) return
    running = next
    lastTime = 0
    cancelAnimationFrame(frame)
    frame = next ? requestAnimationFrame(render) : 0
    canvas.dataset.running = String(next)
  }

  function contextLost(event: Event) {
    event.preventDefault()
    setRunning(false)
    lost = true
    canvas.style.display = 'none'
    unavailable()
  }

  canvas.addEventListener('webglcontextlost', contextLost)
  host.appendChild(canvas)
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
      canvas.removeEventListener('webglcontextlost', contextLost)
      gl.deleteBuffer(quad)
      gl.deleteProgram(program)
      gl.deleteShader(vertexShader)
      gl.deleteShader(fragmentShader)
      gl.getExtension('WEBGL_lose_context')?.loseContext()
      canvas.remove()
    },
  }
}
