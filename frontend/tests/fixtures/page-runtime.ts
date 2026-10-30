import { createRenderer, defineComponent, h, nextTick, ssrContextKey } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
import PersonalSchedulePage from '../../src/pages/PersonalSchedulePage.vue'
import ReservationPage from '../../src/pages/ReservationPage.vue'
import WorkbenchPage from '../../src/pages/WorkbenchPage.vue'
import StudentPortal from '../../src/pages/StudentPortal.vue'
import LearningPage from '../../src/pages/LearningPage.vue'
import LeavePage from '../../src/pages/LeavePage.vue'
import CertificatePage from '../../src/pages/CertificatePage.vue'
import HousingMinePage from '../../src/pages/HousingMinePage.vue'
import RepairPage from '../../src/pages/RepairPage.vue'
import AcademicPage from '../../src/pages/AcademicPage.vue'
import ContentManager from '../../src/pages/ContentManager.vue'
import FoundationPage from '../../src/pages/FoundationPage.vue'
import ResourcesPage from '../../src/pages/ResourcesPage.vue'
import CampusExplore from '../../src/pages/CampusExplore.vue'

const pages = {
  '/campus/explore': CampusExplore,
  '/portal/schedule': PersonalSchedulePage,
  '/portal/reservations': ReservationPage,
  '/workbench': WorkbenchPage,
  '/portal': StudentPortal,
  '/portal/learning': LearningPage,
  '/portal/leave': LeavePage,
  '/portal/certificates': CertificatePage,
  '/housing/mine': HousingMinePage,
  '/repairs/mine': RepairPage,
  '/academic': AcademicPage,
  '/admin/content': ContentManager,
  '/admin/foundation': FoundationPage,
  '/admin/resources': ResourcesPage,
}

// Use client setup semantics: SSR deliberately skips non-immediate watchers.
export async function checkPageSetup(path: string, destination?: string, inspect?: (bindings: Record<string, unknown>) => Promise<void>) {
  const basePath = path.split('?')[0]!
  const page = pages[basePath as keyof typeof pages]
  const errors: unknown[] = []
  let bindings: Record<string, unknown> | undefined
  const probe = defineComponent({
    setup(props, context) {
      bindings = page.setup?.(props, context) as Record<string, unknown> | undefined
      return () => h('div')
    },
  })
  const renderer = createRenderer({
    createElement: () => ({}),
    createText: () => ({}),
    createComment: () => ({}),
    insert: () => {},
    remove: () => {},
    setElementText: () => {},
    setText: () => {},
    patchProp: () => {},
    parentNode: () => null,
    nextSibling: () => null,
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: basePath, component: probe }],
  })
  const app = renderer.createApp(RouterView).use(router)
  // Vite's SFC module adds SSR metadata; setup itself still runs as a client.
  app.provide(ssrContextKey, { modules: new Set() })
  app.config.errorHandler = error => { errors.push(error) }
  await router.push(path)
  await router.isReady()
  app.mount({})
  await nextTick()
  if (destination) {
    for (let n = 0; n < 20; n++) { await Promise.resolve(); await nextTick() }
    await router.push(destination)
    for (let n = 0; n < 30; n++) { await Promise.resolve(); await nextTick() }
  }
  try { if (inspect && bindings) await inspect(bindings) }
  finally { app.unmount() }
  return errors
}
