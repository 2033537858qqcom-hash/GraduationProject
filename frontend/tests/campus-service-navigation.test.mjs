import test from 'node:test'
import assert from 'node:assert/strict'
import { campusPlaceId, campusReservationLink, campusServiceOrigin, campusExploreReturn, safeCampusExploreDestination } from '../src/lib/campusServiceNavigation.ts'
import { safeLoginDestination } from '../src/lib/navigation.ts'
import { safeAssistantDestination } from '../src/lib/assistant.ts'

test('a selected campus place becomes an explicit resource filter and a reversible origin', () => {
  const link = campusReservationLink({ id: 'east-library', name: '图书馆' }, 'EAST', 'model')
  const url = new URL(link, 'https://campus.invalid')
  assert.equal(url.searchParams.get('venue'), 'east-library')
  assert.equal(url.searchParams.has('resource'), false)
  const origin = campusServiceOrigin(Object.fromEntries(url.searchParams))
  assert.deepEqual(origin, { placeId: 'east-library', campus: 'EAST', view: 'model', name: '图书馆' })
  assert.equal(campusExploreReturn(origin), '/campus/explore?campus=EAST&view=model&focus=east-library')
  assert.equal(safeLoginDestination(link), link)
})
test('clearing the resource filter preserves the original campus and viewing mode', () => {
  const url = new URL(campusReservationLink({ id: 'west-library', name: '西区图书馆' }, 'WEST', 'map'), 'https://campus.invalid')
  url.searchParams.delete('venue')
  const origin = campusServiceOrigin(Object.fromEntries(url.searchParams))
  assert.equal(origin.campus, 'WEST')
  assert.equal(origin.view, 'map')
  assert.match(campusExploreReturn(origin), /focus=west-library$/)
})
test('unknown resource availability is never inferred from a place name or building type', () => {
  const link = campusReservationLink({ id: 'south-gate', name: '南门' }, 'EAST', 'model')
  assert.match(link, /venue=south-gate/)
  assert.ok(!link.includes('resource='))
  assert.ok(!link.includes('submit'))
})
test('malformed, repeated route values and cross-campus context cannot become a return link', () => {
  const base = { origin: 'campus', place: 'west-library', campus: 'WEST', view: 'model' }
  for (const patch of [{ place: ['west-library'] }, { campus: 'EAST' }, { campus: ['WEST'] }, { view: 'webgl' }, { origin: 'https://evil.test' }, { place: '../admin' }]) assert.equal(campusServiceOrigin({ ...base, ...patch }), null)
  for (const value of ['a/b', '%2f', '//evil.test', 'east-library\n', ['east-library'], '', null]) assert.equal(campusPlaceId(value), null)
})
test('orientation links accept legacy assistant navigation and retain an explicit campus/mode', () => {
  for (const path of ['/campus/explore', '/campus/explore?navigate=east-jinxiu', '/campus/explore?campus=WEST&view=model&focus=west-library']) {
    assert.equal(safeCampusExploreDestination(path), path)
    assert.equal(safeAssistantDestination(path), path)
  }
})
test('assistant orientation links reject external targets, extra fields and ambiguous selection', () => {
  for (const path of ['//evil.test', '/campus/explore?redirect=https://evil.test', '/campus/explore?focus=west-library&campus=EAST', '/campus/explore?focus=south-gate&navigate=east-library', '/campus/explore?view=model&view=map', '/campus/explore?focus=%2fadmin', '/campus/explore#other']) assert.equal(safeCampusExploreDestination(path), null)
})
test('display names are bounded text and never participate in return routing', () => {
  const origin = campusServiceOrigin({ origin: 'campus', place: 'east-library', campus: 'EAST', view: 'model', placeName: 'x'.repeat(1000), redirect: 'https://evil.test' })
  assert.equal(origin.name, '所选校园地点')
  assert.ok(!campusExploreReturn(origin).includes('redirect'))
})
