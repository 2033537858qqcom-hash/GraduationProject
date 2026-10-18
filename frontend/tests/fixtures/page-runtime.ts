import { createRenderer, defineComponent, h, nextTick, ssrContextKey } from 'vue'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'
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

const pages = {
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
export async function checkPageSetup(path: string) {
  const page = pages[path as keyof typeof pages]
  const errors: unknown[] = []
  const probe = defineComponent({
    setup(props, context) {
      page.setup?.(props, context)
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
    routes: [{ path, component: probe }],
  })
  const app = renderer.createApp(RouterView).use(router)
  // Vite's SFC module adds SSR metadata; setup itself still runs as a client.
  app.provide(ssrContextKey, { modules: new Set() })
  app.config.errorHandler = error => { errors.push(error) }
  await router.push(path)
  await router.isReady()
  app.mount({})
  await nextTick()
  app.unmount()
  return errors
}
