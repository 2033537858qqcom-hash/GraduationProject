<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Menu, X } from '@lucide/vue'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CulturalFrame from '../components/CulturalFrame.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
import { activeChapter, chapters, requestedChapter } from '../lib/publicNavigation'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'
import { currentUser, landingPage } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const expanded = ref(false)
const schoolNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: schoolIndicatorStyle } = useSlidingIndicator(
  schoolNavRef,
  () => [route.path, activeChapter.value],
  { activeSelector: 'a[aria-current="location"]' }
)
function navigate(event: MouseEvent, id: string) {
  if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  expanded.value = false
  const target = { path: '/', query: id === 'home' ? {} : { chapter: id } }
  if (route.path === '/') {
    requestedChapter.value = { id, sequence: requestedChapter.value.sequence + 1 }
    void router.replace(target)
  } else void router.push(target)
}
</script>
<template>
  <div class="public-site">
    <header class="public-header" :class="{ 'over-photo': route.path === '/' }">
      <CulturalFrame class="brand-frame" />
      <CulturalOrnament motif="cloud" class="brand-cloud" />
      <div class="school-brand-row page-width">
        <RouterLink class="school-brand" to="/" aria-label="郑州工业应用技术学院首页">
          <SchoolIdentity />
        </RouterLink>
        <span class="school-motto">励学 · 敦行 · 明理 · 至善</span>
        <RouterLink class="service-link" :to="currentUser ? landingPage() : '/login'">{{ currentUser ? '个人门户' : '校园服务' }} <ArrowUpRight :size="16" /></RouterLink>
        <button class="icon-button mobile-menu" :aria-expanded="expanded" aria-controls="school-navigation"
          :aria-label="expanded ? '收起学校栏目' : '展开学校栏目'" @click="expanded = !expanded">
          <X v-if="expanded" :size="20" /><Menu v-else :size="20" />
        </button>
      </div>
      <nav id="school-navigation" class="school-navigation" :class="{ expanded }" aria-label="学校官网主导航">
        <div ref="schoolNavRef" class="page-width">
          <a v-for="item in chapters" :key="item.id" :href="item.id === 'home' ? '/' : '/?chapter=' + item.id"
            :aria-current="route.path === '/' && activeChapter === item.id ? 'location' : undefined"
            @click="navigate($event, item.id)">{{ item.name }}</a>
          <div class="school-nav-indicator" :style="schoolIndicatorStyle" aria-hidden="true"></div>
        </div>
      </nav>
    </header>
    <main class="public-main" :class="{ 'reading-scene': route.path !== '/' }">
      <template v-if="route.path !== '/'">
        <CulturalFrame class="reading-frame" :bottom="false" />
        <CulturalOrnament motif="cloud" class="reading-cloud" />
      </template>
      <RouterView />
    </main>
    <footer class="public-footer-wrap" aria-label="网站信息与链接">
      <div class="public-footer page-width">
        <small>校园综合服务平台 · 毕业设计演示</small>
        <nav class="footer-links" aria-label="页脚链接"><RouterLink to="/campus/explore">校园探索 <ArrowUpRight :size="12" /></RouterLink><a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">学校官方站点 <ArrowUpRight :size="12" /></a></nav>
      </div>
    </footer>
  </div>
</template>
