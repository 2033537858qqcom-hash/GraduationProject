<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ArrowUpRight } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { activeChapter, chapters, requestedChapter } from '../lib/publicNavigation'
import SchoolStory from '../components/SchoolStory.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
import CulturalFrame from '../components/CulturalFrame.vue'
import type { PublicContent, School } from '../types'
import { schoolMedia } from '../content/schoolMedia'

const route = useRoute()
const stage = ref<HTMLElement>()
const school = ref<School | null>(null)
const contents = ref<PublicContent[]>([])
const error = ref('')
const loading = ref(true)
const slide = ref(0)
const previousSlide = ref(-1)
const active = ref(0)
const hovered = ref(false)
const focused = ref(false)
const images = [schoolMedia.overview, schoolMedia.learning, schoolMedia.spring]
const panels = [
  { id: 'about', eyebrow: 'ABOUT ZUIT', title: ['认识学校', '连接未来'], body: '学校名称与校区信息来自公开资料，更多内容将由后台审核发布。', media: schoolMedia.lakeside, link: '认识郑州工业应用技术学院', slug: 'school-introduction', tone: 'surface' },
  { id: 'departments', eyebrow: 'SCHOOLS & PROGRAMS', title: ['从专业出发', '在实践中成长'], body: '在不同的学科之间，探索自己的学习方向。', tone: 'surface' },
  { id: 'education', eyebrow: 'LEARNING & EDUCATION', title: ['让知识', '与实践相连'], body: '课程、实践与持续学习，共同构成校园中的成长旅程。', media: schoolMedia.practice, tone: 'surface' },
  { id: 'research', eyebrow: 'RESEARCH & DISCOVERY', title: ['从一个问题', '走向新的发现'], body: '科研平台、学术交流与研究动态，将通过公开内容目录持续完善。', media: schoolMedia.seminar, tone: 'surface' },
  { id: 'admissions', eyebrow: 'ADMISSIONS & CAREERS', title: ['了解学校', '开启下一段旅程'], body: '学校公开招生信息与专业资料，可通过官方招生网站查阅。', media: schoolMedia.freshmen, tone: 'surface', external: 'https://zsb.zzuit.edu.cn/' },
  { id: 'exchange', eyebrow: 'CONNECTION & PARTNERSHIPS', title: ['让交流', '打开新的视野'], body: '关注学校合作交流信息，了解校园与社会的连接。', media: schoolMedia.international, tone: 'surface' },
  { id: 'life', eyebrow: 'LIFE ON CAMPUS', title: ['把热爱', '写进校园日常'], body: '运动、阅读、艺术与伙伴，在校园中找到更多可能。', media: schoolMedia.performance, tone: 'photo' },
  { id: 'disclosure', eyebrow: 'PUBLIC INFORMATION', title: ['公开信息', '清晰可达'], body: '从学校资料到平台说明，找到已经发布的公开内容。', tone: 'surface' },
]
const departments = ['信息工程学院', '机电工程学院', '建筑工程学院', '医学院', '管理学院', '艺术设计学院']
const publishedNotices = computed(() => contents.value.filter(item => item.category === 'notice'))
function getPanelMotif(id: string): 'disciplines' | 'education' | 'research' | 'journey' | 'exchange' | 'life' | 'disclosure' | 'cloud' {
  switch (id) {
    case 'departments': return 'disciplines'
    case 'education': return 'education'
    case 'research': return 'research'
    case 'admissions': return 'journey'
    case 'exchange': return 'exchange'
    case 'life': return 'life'
    case 'disclosure': return 'disclosure'
    default: return 'cloud'
  }
}
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)')
let motion = 0
let moving = false
let lockedUntil = 0
let lastWheel = 0
let carousel: ReturnType<typeof setInterval>
let resize: ResizeObserver | undefined
let stageWidth = 0
let stageHeight = 0

function selectSlide(index: number) {
  const next = (index + images.length) % images.length
  if (next !== slide.value) { previousSlide.value = slide.value; slide.value = next }
  resetCarousel()
}
function resetCarousel() {
  clearInterval(carousel)
  carousel = setInterval(() => {
    if (!reduced.matches && !hovered.value && !focused.value && active.value === 0 && !document.hidden) {
      selectSlide(slide.value + 1)
    }
  }, 7000)
}
function stopMotion() { cancelAnimationFrame(motion); moving = false }
function targetTop(index: number) {
  const viewport = stage.value
  const panel = viewport?.children[index] as HTMLElement | undefined
  return viewport && panel ? panel.getBoundingClientRect().top - viewport.getBoundingClientRect().top + viewport.scrollTop : 0
}
function setActive(index: number) {
  active.value = index
  activeChapter.value = chapters[index]?.id ?? 'home'
}
function go(index: number, instant = false) {
  const viewport = stage.value
  if (!viewport) return
  stopMotion()
  const clamped = Math.max(0, Math.min(chapters.length - 1, index))
  const destination = targetTop(clamped)
  setActive(clamped)
  if (reduced.matches || instant) { viewport.scrollTop = destination; return }
  const from = viewport.scrollTop
  const start = performance.now()
  moving = true
  function frame(now: number) {
    const progress = Math.min(1, (now - start) / 520)
    viewport!.scrollTop = from + (destination - from) * progress * progress * (3 - 2 * progress)
    if (progress < 1) motion = requestAnimationFrame(frame)
    else moving = false
  }
  motion = requestAnimationFrame(frame)
}
function scrollChanged() {
  if (moving || !stage.value) return
  // A responsive reflow must settle before interpreting its scroll position as navigation.
  if (stage.value.clientWidth !== stageWidth || stage.value.clientHeight !== stageHeight) return
  const midpoint = stage.value.scrollTop + stage.value.clientHeight * 0.4
  let index = 0
  for (let i = 0; i < chapters.length; i++) if (targetTop(i) <= midpoint) index = i
  setActive(index)
}
function wheel(event: WheelEvent) {
  if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY) || !event.deltaY || !stage.value) return
  const now = performance.now()
  const direction = Math.sign(event.deltaY)
  const viewport = stage.value
  const current = viewport.children[active.value] as HTMLElement | undefined
  const start = targetTop(active.value)
  const finish = start + (current ? current.offsetHeight - viewport.clientHeight : 0)
  const canRead = direction > 0 ? viewport.scrollTop < finish - 2 : viewport.scrollTop > start + 2
  if (!moving && canRead) return
  if ((active.value === 0 && direction < 0) || (active.value === chapters.length - 1 && direction > 0 && !moving)) {
    event.preventDefault()
    return
  }
  event.preventDefault()
  if (moving || now < lockedUntil || now - lastWheel < 120) { lastWheel = now; return }
  lastWheel = now
  lockedUntil = now + 480
  go(active.value + direction)
}
function keyboard(event: KeyboardEvent) {
  if (['PageDown', 'PageUp', 'ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    const target = event.target as HTMLElement | null
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) return
    event.preventDefault()
    if (event.key === 'Home') go(0)
    else if (event.key === 'End') go(chapters.length - 1)
    else {
      const direction = (event.key === 'PageDown' || event.key === 'ArrowDown') ? 1 : -1
      go(active.value + direction)
    }
  }
}
async function load() {
  error.value = ''
  loading.value = true
  try {
    const [identity, content] = await Promise.all([api<School>('/public/school'), api<PublicContent[]>('/public/content')])
    school.value = identity
    contents.value = content
  } catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
watch(requestedChapter, request => go(chapters.findIndex(chapter => chapter.id === request.id)))
watch(() => route.query.chapter, id => {
  const index = chapters.findIndex(chapter => chapter.id === (id ?? 'home'))
  if (index >= 0 && index !== active.value) go(index)
})
watch([hovered, focused, active], resetCarousel)
onMounted(async () => {
  void load()
  await nextTick()
  if (typeof window !== 'undefined') {
    window.addEventListener('wheel', wheel, { passive: false })
    window.addEventListener('keydown', keyboard)
  }
  stageWidth = stage.value?.clientWidth ?? 0
  stageHeight = stage.value?.clientHeight ?? 0
  const index = chapters.findIndex(chapter => chapter.id === route.query.chapter)
  go(index < 0 ? 0 : index, true)
  resize = new ResizeObserver(() => {
    stageWidth = stage.value?.clientWidth ?? 0
    stageHeight = stage.value?.clientHeight ?? 0
    go(active.value, true)
  })
  if (stage.value) resize.observe(stage.value)
  resetCarousel()
})
onUnmounted(() => {
  stopMotion()
  clearInterval(carousel)
  resize?.disconnect()
  if (typeof window !== 'undefined') {
    window.removeEventListener('wheel', wheel)
    window.removeEventListener('keydown', keyboard)
  }
})
</script>
<template>
  <div class="homepage-stage-wrap">
    <div v-if="error" class="public-api-error page-width" role="alert">{{ error }} <button class="text-button" @click="load">重新加载</button></div>
    <div ref="stage" class="chapter-stage" role="region" tabindex="0" aria-label="学校官网章节浏览" @scroll="scrollChanged" @keydown="keyboard" @pointerdown="stopMotion">
      <section class="campus-chapter hero-chapter" aria-label="首页" @mouseenter="hovered = true" @mouseleave="hovered = false"
        @focusin="focused = true" @focusout="focused = false">
        <CulturalFrame class="photo-frame" :bottom="false" />
        <div class="hero-slides" aria-hidden="true"><div v-for="(image, index) in images" :key="image.url" class="hero-slide" :class="{ current: slide === index, retiring: previousSlide === index && slide !== index }" :style="{ backgroundImage: 'url(' + image.url + ')', backgroundPosition: image.position }"></div></div>
        <div class="hero-shade"></div>
        <div class="hero-copy page-width"><span>在校园中，开启自己的成长旅程</span><h1>励学敦行<br>明理至善</h1><RouterLink to="/content/school-introduction">认识学校 <ArrowUpRight :size="17" /></RouterLink></div>
        <div class="hero-controls page-width"><span>{{ String(slide + 1).padStart(2, '0') }} / 03 <small><a :href="images[slide]?.sourceUrl" target="_blank" rel="noopener noreferrer">{{ images[slide]?.label }} · 学校官网影像</a></small></span><div><button class="icon-button" aria-label="上一张校园图片" @click="selectSlide(slide - 1)"><ArrowLeft :size="17" /></button><button v-for="(image,index) in images" :key="image.url" class="slide-selector" :class="{ current: slide === index }" :aria-label="'切换到' + image.label" :aria-pressed="slide === index" @click="selectSlide(index)"></button><button class="icon-button" aria-label="下一张校园图片" @click="selectSlide(slide + 1)"><ArrowRight :size="17" /></button></div></div>
      </section>
      <section v-for="(panel,index) in panels" :key="panel.id" class="campus-chapter theme-chapter" :class="['tone-' + panel.tone, 'chapter-' + panel.id, { 'is-active': active === index + 1 }]" :aria-label="chapters[index + 1]?.name">
        <CulturalFrame v-if="panel.id !== 'about'" :bottom="false" :class="{ 'photo-frame': panel.tone === 'photo' }" />
        <div v-if="panel.tone === 'photo' && panel.media" class="chapter-photo" role="img" :aria-label="panel.media.alt" :style="{ backgroundImage: 'linear-gradient(90deg,#142b45d9,#142b4550),url(' + panel.media.url + ')', backgroundPosition: panel.media.position }"></div>
        <a v-if="panel.tone === 'photo' && panel.media" class="chapter-photo-credit" :href="panel.media.sourceUrl" target="_blank" rel="noopener noreferrer">{{ panel.media.label }} · 学校官网影像 <ArrowUpRight :size="12" aria-hidden="true" /></a>
        <CulturalOrnament v-if="panel.id !== 'about'" :motif="getPanelMotif(panel.id)" class="chapter-ornament" preserveAspectRatio="none" />
        <SchoolStory v-if="panel.id === 'about'" :media="schoolMedia.lakeside" :campuses="school?.campuses ?? []"
          :summary="contents.find(item => item.slug === 'school-introduction')?.summary ?? '郑州工业应用技术学院位于河南省新郑市，设东、西两个校区。'" />
        <div v-else class="chapter-body page-width" :class="{ split: panel.media && panel.tone !== 'photo', 'directory-layout': panel.id === 'departments' || panel.id === 'disclosure' }">
          <div class="chapter-copy">
            <span class="eyebrow">{{ panel.eyebrow }}</span><h2 class="chapter-title">{{ panel.title[0] }}<br>{{ panel.title[1] }}</h2><p>{{ panel.body }}</p>
            <RouterLink v-if="panel.slug" class="chapter-link" :to="'/content/' + panel.slug">{{ panel.link }} <ArrowUpRight :size="16" /></RouterLink>
            <a v-if="panel.external" class="chapter-link" :href="panel.external" target="_blank" rel="noopener noreferrer">学校招生信息网 <ArrowUpRight :size="16" /></a>
            <a v-if="panel.id === 'departments'" class="chapter-link" href="https://zsb.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">查阅招生与专业资料 <ArrowUpRight :size="16" /></a>
            <RouterLink v-if="panel.id === 'life'" class="chapter-link" to="/campus/explore">探索校园空间 <ArrowUpRight :size="16" /></RouterLink>
            <div v-if="panel.id === 'exchange'" class="exchange-line"><span>交流信息以学校发布为准</span><a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">学校官方站点 <ArrowUpRight :size="15" /></a></div>
          </div>
          <figure v-if="panel.media && panel.tone !== 'photo'" class="chapter-visual">
            <div class="chapter-media campus-photo" role="img" :aria-label="panel.media.alt" :style="{ backgroundImage: 'url(' + panel.media.url + ')', backgroundPosition: panel.media.position }"></div>
            <figcaption class="campus-caption"><span>{{ panel.media.label }}</span><small><a :href="panel.media.sourceUrl" target="_blank" rel="noopener noreferrer">学校官网影像 <ArrowUpRight :size="12" aria-hidden="true" /></a></small></figcaption>
          </figure>
          <div v-if="panel.id === 'departments'" class="department-grid"><a v-for="(department,departmentIndex) in departments" :key="department" href="https://zsb.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer"><small class="department-number" aria-hidden="true">{{ String(departmentIndex + 1).padStart(2,'0') }}</small><span>{{ department }}</span><ArrowUpRight :size="15" /></a><small>院系名称参考学校公开招生目录；点击前往学校招生信息网。</small></div>
          <div v-if="panel.id === 'disclosure'" class="public-content-list">
            <RouterLink v-for="item in contents" :key="item.id" :to="'/content/' + item.slug"><div class="public-content-copy"><small>{{ item.sourceType === 'DEMO' ? '演示内容' : '学校资料' }}</small><span>{{ item.title }}</span><p>{{ item.summary }}</p></div><ArrowUpRight :size="16" /></RouterLink>
            <p v-if="loading" class="muted" role="status">正在加载公开资料…</p>
            <p v-else-if="error" class="muted">公开资料暂时无法加载，可通过学校官方站点查阅。</p>
            <p v-else-if="!contents.length" class="muted">暂无已发布公开内容。</p>
            <a class="public-catalog-source" href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer"><span>更多学校公开信息</span><ArrowUpRight :size="13" /></a>
          </div>
        </div>
      </section>
    </div>
    <div class="chapter-progress page-width">
      <span>{{ String(active + 1).padStart(2,'0') }} / 09</span>
      <small>{{ active === 0 ? '向下滚动，探索校园' : chapters[active]?.name }}</small>
      <div>
        <RouterLink v-for="item in publishedNotices.slice(0,1)" :key="item.id" :to="'/content/' + item.slug">通知公告</RouterLink>
        <button
          class="icon-button"
          :aria-label="active === chapters.length - 1 ? '回到首屏' : '进入下一章节'"
          @click="go(active === chapters.length - 1 ? 0 : active + 1)"
        >
          <ArrowUp v-if="active === chapters.length - 1" :size="18" />
          <ArrowDown v-else :size="18" />
        </button>
      </div>
    </div>
    <span class="sr-only" aria-live="polite">{{ chapters[active]?.name }}，第 {{ active + 1 }} 章，共 9 章。</span>
  </div>
</template>
<style scoped>
.chapter-photo-credit { position: absolute; right: 5%; bottom: 22px; z-index: 2; display: inline-flex; align-items: center; gap: 6px; font-size: 11px; line-height: 1.8; color: #edf2f7; text-shadow: 0 1px 4px #142b4580; }
.chapter-photo-credit:hover { text-decoration: underline; text-underline-offset: 4px; }
.chapter-photo-credit:focus-visible { outline: 2px solid #fff; outline-offset: 4px; }
@media (max-width: 760px) { .chapter-photo-credit { bottom: 18px; font-size: 10px; } }
</style>
