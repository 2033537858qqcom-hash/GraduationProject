import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { safeLoginDestination } from '../src/lib/navigation.ts'
import { safeAssistantDestination } from '../src/lib/assistant.ts'

test('router configuration file defines all required routes, redirects, and guards', () => {
  const routerCode = fs.readFileSync(new URL('../src/router.ts', import.meta.url), 'utf8')

  // Verify key redirects exist
  assert.match(routerCode, /path:\s*'portal\/timetable',\s*redirect:\s*'\/portal\/learning\/timetable'/)
  assert.match(routerCode, /path:\s*'housing',\s*redirect:\s*'\/housing\/mine'/)
  assert.match(routerCode, /path:\s*'repairs',\s*redirect:\s*'\/repairs\/mine'/)
  assert.match(routerCode, /path:\s*'content',\s*redirect:\s*'\/'/)
  assert.match(routerCode, /path:\s*'campus',\s*redirect:\s*'\/campus\/explore'/)

  // Verify critical routes exist
  const expectedPaths = [
    'portal',
    'portal/learning',
    'portal/learning/timetable',
    'portal/learning/grades',
    'portal/learning/exams',
    'teaching',
    'teaching/timetable',
    'academic',
    'portal/notices',
    'notices',
    'portal/affairs',
    'portal/certificates',
    'student-affairs/certificates',
    'portal/leave',
    'student-affairs/leave',
    'portal/reservations',
    'student-affairs/reservations',
    'profile',
    'workbench',
    'admin/content',
    'admin/foundation',
    'admin/resources',
    'admin/counselors',
    'admin/repair-settings',
    'admin/housing',
    'housing/mine',
    'housing/transfer',
    'student-affairs/housing',
    'housing/assignments',
    'repairs/mine',
    'logistics/repairs/dispatch',
    'logistics/repairs/statistics',
    'logistics/repairs/tasks',
    'logistics/repair-responsibilities',
  ]

  for (const p of expectedPaths) {
    const regex = new RegExp(`path:\\s*'${p.replace(/\//g, '\\/')}'`)
    assert.match(routerCode, regex, `router.ts should define route ${p}`)
  }

  // Verify route guard honors redirect query on login
  assert.match(routerCode, /safeLoginDestination\(to\.query\.redirect\)\s*\?\?\s*landingPage\(\)/)
  // Verify scrollBehavior supports savedPosition and hash
  assert.match(routerCode, /if\s*\(savedPosition\)\s*return\s*savedPosition/)
})

test('safeLoginDestination normalizes trailing slashes and validates all app routes', () => {
  assert.equal(safeLoginDestination('/'), '/')
  assert.equal(safeLoginDestination('/portal'), '/portal')
  assert.equal(safeLoginDestination('/portal/'), '/portal/')
  assert.equal(safeLoginDestination('/workbench/'), '/workbench/')
  assert.equal(safeLoginDestination('/housing'), '/housing')
  assert.equal(safeLoginDestination('/repairs'), '/repairs')
  assert.equal(safeLoginDestination('/campus/explore'), '/campus/explore')
  assert.equal(safeLoginDestination('/verify/certificate'), '/verify/certificate')
  assert.equal(safeLoginDestination('/content/school-introduction'), '/content/school-introduction')
  assert.equal(safeLoginDestination('/student-affairs/housing?id=12&tab=details'), '/student-affairs/housing?id=12&tab=details')
  assert.equal(safeLoginDestination('/portal/reservations?id=5&tab=details'), '/portal/reservations?id=5&tab=details')

  // Block dangerous URLs
  assert.equal(safeLoginDestination('https://external.test'), null)
  assert.equal(safeLoginDestination('//evil.test'), null)
  assert.equal(safeLoginDestination('/\\evil'), null)
  assert.equal(safeLoginDestination('/portal/../../evil'), null)
  assert.equal(safeLoginDestination('/admin/unknown-page'), null)
})

test('safeAssistantDestination validates assistant tool targets across affairs, logistics, and housing', () => {
  assert.equal(safeAssistantDestination('/portal/reservations'), '/portal/reservations')
  assert.equal(safeAssistantDestination('/student-affairs/reservations'), '/student-affairs/reservations')
  assert.equal(safeAssistantDestination('/student-affairs/housing'), '/student-affairs/housing')
  assert.equal(safeAssistantDestination('/workbench'), '/workbench')
  assert.equal(safeAssistantDestination('/admin/content'), '/admin/content')
  assert.equal(safeAssistantDestination('/teaching'), '/teaching')
  assert.equal(safeAssistantDestination('/academic'), '/academic')
  assert.equal(safeAssistantDestination('/housing/transfer?id=8&tab=details'), '/housing/transfer?id=8&tab=details')
  assert.equal(safeAssistantDestination('/portal/reservations?id=2&tab=details'), '/portal/reservations?id=2&tab=details')

  // Malformed or dangerous destinations remain rejected
  assert.equal(safeAssistantDestination('https://evil.test'), null)
  assert.equal(safeAssistantDestination('//evil.test'), null)
  assert.equal(safeAssistantDestination('/housing/assignments'), null)
  assert.equal(safeAssistantDestination('/admin/foundation'), null)
})
