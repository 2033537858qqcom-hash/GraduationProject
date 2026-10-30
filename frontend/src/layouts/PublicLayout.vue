<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Menu, X } from '@lucide/vue'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CampusContour from '../components/CampusContour.vue'
import { activeChapter, chapterReadingProgress, chapters, requestedChapter } from '../lib/publicNavigation'
import { initializePublicMotion, publicMotionPreference, publicReducedMotion, setPublicMotionPreference } from '../lib/publicMotion'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import { currentUser, landingPage } from '../stores/auth'
import { contentOrigin } from '../lib/publicReading'
import '../styles/public-shell.css'

const route = useRoute()
const CampusAssistant = defineAsyncComponent(() => import('../components/AssistantChat.vue'))
const router = useRouter()
const expanded = ref(false)
const compactHeader = ref(false)
const menuButton = ref<HTMLButtonElement>()
const selectedChapter = computed(() => route.path === '/' ? activeChapter.value
  : route.path.startsWith('/content/') ? contentOrigin(route.params.slug, route.query.from).id
  : route.path === '/campus/explore' ? 'life' : null)
let headerFrame = 0
let releaseMotion = () => {}
const schoolNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: schoolIndicatorStyle } = useSlidingIndicator(
  schoolNavRef,
  () => [route.path, selectedChapter.value],
  { activeSelector: 'a[aria-current="location"]' }
)
async function closeMenu() {
  expanded.value = false
  await nextTick()
  menuButton.value?.focus()
}

function updateHeader() {
  if (headerFrame) return
  headerFrame = requestAnimationFrame(() => {
    headerFrame = 0
    compactHeader.value = route.path === '/' && window.scrollY > 72
  })
}
watch(() => route.path, () => { expanded.value = false; updateHeader() })
onMounted(() => {
  releaseMotion = initializePublicMotion()
  updateHeader()
  window.addEventListener('scroll', updateHeader, { passive: true })
})
onUnmounted(() => {
  releaseMotion()
  cancelAnimationFrame(headerFrame)
  window.removeEventListener('scroll', updateHeader)
})

function navigate(event: MouseEvent, id: string) {
  if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  expanded.value = false
  const target = { path: '/', query: id === 'home' ? {} : { chapter: id } }
  if (route.path === '/') {
    if ((route.query.chapter ?? 'home') === id) {
      requestedChapter.value = { id, sequence: requestedChapter.value.sequence + 1 }
    } else void router.replace(target)
  } else void router.push(target)
}
</script>
<template>
  <div class="public-site public-campus-site" :class="{ 'is-homepage': route.path === '/', 'public-motion-reduced': publicReducedMotion }">
    <header class="public-header" :class="{ 'over-photo': route.path === '/', 'is-compact': compactHeader, 'cinema-header-light': route.path === '/' && !['home', 'life'].includes(activeChapter), 'cinema-header-life': route.path === '/' && activeChapter === 'life' }" @keydown.esc.stop.prevent="closeMenu">
      <div class="public-header-bar page-width">
        <RouterLink class="school-brand" to="/" aria-label="郑州工业应用技术学院首页" @click="navigate($event, 'home')">
          <SchoolIdentity />
        </RouterLink>
      <nav id="school-navigation" class="school-navigation" :class="{ expanded }" aria-label="学校官网主导航">
        <div ref="schoolNavRef" class="school-navigation-inner">
          <a v-for="item in chapters" :key="item.id" :href="item.id === 'home' ? '/' : '/?chapter=' + item.id"
            :aria-current="selectedChapter === item.id ? 'location' : undefined"
            @click="navigate($event, item.id)">{{ item.name }}</a>
          <div class="school-nav-indicator" :style="schoolIndicatorStyle" aria-hidden="true"></div>
          <button class="public-motion-toggle mobile-motion-toggle" :aria-pressed="publicMotionPreference === 'reduce'" @click="setPublicMotionPreference(publicMotionPreference === 'reduce' ? 'auto' : 'reduce')"><span class="motion-toggle-track" aria-hidden="true"><i></i></span>减少动态</button>
        </div>
      </nav>
        <div class="public-header-actions">
          <button class="public-motion-toggle" :aria-pressed="publicMotionPreference === 'reduce'" @click="setPublicMotionPreference(publicMotionPreference === 'reduce' ? 'auto' : 'reduce')"><span class="motion-toggle-track" aria-hidden="true"><i></i></span>减少动态</button>
          <RouterLink class="service-link" :to="currentUser ? landingPage() : '/login'">{{ currentUser ? '个人门户' : '校园服务' }} <ArrowUpRight :size="16" /></RouterLink>
          <button ref="menuButton" class="icon-button mobile-menu" :aria-expanded="expanded" aria-controls="school-navigation" :aria-label="expanded ? '收起学校栏目' : '展开学校栏目'" @click="expanded = !expanded"><X v-if="expanded" :size="20" /><Menu v-else :size="20" /></button>
        </div>
      </div>
      <div v-if="route.path === '/'" class="chapter-reading-progress" role="progressbar" :aria-label="(chapters.find(item => item.id === activeChapter)?.name ?? '当前章节') + '阅读进度'" :aria-valuenow="Math.round(chapterReadingProgress * 100)" aria-valuemin="0" aria-valuemax="100"><span :style="{ transform: 'scaleX(' + chapterReadingProgress + ')' }"></span></div>
    </header>
    <main class="public-main" :class="{ 'reading-scene': route.path !== '/', 'homepage-main': route.path === '/' }">
      <RouterView />
    </main>
    <CampusAssistant v-if="route.path === '/campus/explore' && currentUser?.permissions.includes('agent:query')" />
    <footer v-if="route.path !== '/'" class="campus-footer page-width" aria-label="网站信息与链接">
      <CampusContour />
      <div class="campus-footer-row">
        <small>校园综合服务平台 · 毕业设计演示</small>
        <nav class="footer-links" aria-label="页脚链接"><RouterLink to="/campus/explore">校园探索 <ArrowUpRight :size="12" /></RouterLink><a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">学校官方站点 <ArrowUpRight :size="12" /></a></nav>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.public-motion-toggle { display: inline-flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 0; padding: 10px 0; background: none; color: inherit; font-size: 10px; white-space: nowrap; }
.motion-toggle-track { display: block; width: 25px; height: 13px; border: 1px solid currentColor; border-radius: 10px; opacity: .7; }
.motion-toggle-track i { display: block; margin: 2px; width: 7px; height: 7px; border-radius: 50%; background: currentColor; transition: transform .2s ease; }
.public-motion-toggle[aria-pressed="true"] .motion-toggle-track i { transform: translateX(12px); }
.mobile-motion-toggle { display: none; }
.chapter-reading-progress { position: absolute; inset: auto 0 0; height: 1px; background: #b4a17a28; }
.chapter-reading-progress > span { display: block; height: 100%; background: #c6ae7d; transform-origin: left; }
.public-motion-reduced :deep(*), .public-motion-reduced :deep(*::before), .public-motion-reduced :deep(*::after) { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
.public-site :deep(.explore-page) { animation: campus-arrival .45s cubic-bezier(.22, 1, .36, 1) both; }
@keyframes campus-arrival { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: none; } }
@media (max-width: 1180px) {
  .public-header-actions > .public-motion-toggle { display: none; }
  .mobile-motion-toggle { display: inline-flex; grid-column: 1 / -1; justify-content: flex-start; border-top: 1px solid #24455326; padding-block: 16px; margin-top: 3px; }
}
</style>
