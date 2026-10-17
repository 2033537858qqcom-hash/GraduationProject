import { ShapeUtils, Vector2 } from 'three'

const cross = (a, b, p) => (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])
const area = points => points.reduce((sum, p, i) => { const q = points[(i + 1) % points.length]; return sum + p[0] * q[1] - q[0] * p[1] }, 0) / 2

function clip(subject, boundary) {
  let output = subject
  const sign = Math.sign(area(boundary))
  for (let i = 0; i < boundary.length && output.length; i++) {
    const a = boundary[i], b = boundary[(i + 1) % boundary.length], input = output
    output = []
    let previous = input.at(-1)
    for (const point of input) {
      const s = cross(a, b, previous) * sign, e = cross(a, b, point) * sign
      if ((s >= 0) !== (e >= 0)) {
        const t = s / (s - e)
        output.push([previous[0] + (point[0] - previous[0]) * t, previous[1] + (point[1] - previous[1]) * t])
      }
      if (e >= 0) output.push(point)
      previous = point
    }
  }
  return output
}

function triangles(feature) {
  const outline = feature.outline.map(p => new Vector2(...p))
  const holes = (feature.holes ?? []).map(hole => hole.map(p => new Vector2(...p)))
  const vertices = [...outline, ...holes.flat()]
  return ShapeUtils.triangulateShape(outline, holes).map(triangle => triangle.map(i => [vertices[i].x, vertices[i].y]))
}

// Area is in source screenshot px². Courtyard holes are excluded.
export function intersectionArea(a, b) {
  const tb = triangles(b)
  return triangles(a).reduce((sum, ta) => sum + tb.reduce((subtotal, t) => {
    const points = clip(ta, t)
    return subtotal + (points.length < 3 ? 0 : Math.abs(area(points)))
  }, 0), 0)
}

export function containsPoint(feature, point) {
  function inside(outline) {
    let hit = false
    for (let i = 0, j = outline.length - 1; i < outline.length; j = i++) {
      const a = outline[i], b = outline[j]
      if ((a[1] > point[1]) !== (b[1] > point[1]) && point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) hit = !hit
    }
    return hit
  }
  return inside(feature.outline) && !(feature.holes ?? []).some(inside)
}
