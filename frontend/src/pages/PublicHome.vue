<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ArrowUpRight } from '@lucide/vue'
import { gsap } from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'
import { api, errorMessage } from '../lib/api'
import { activeChapter, chapterReadingProgress, chapters, requestedChapter } from '../lib/publicNavigation'
import { publicReducedMotion } from '../lib/publicMotion'
import SchoolStory from '../components/SchoolStory.vue'
import CampusContour from '../components/CampusContour.vue'
import ChapterDirectory from '../components/ChapterDirectory.vue'
import { formatPublicDate, publicContentLink } from '../lib/publicReading'
import type { PublicContent, School } from '../types'
import { schoolMedia } from '../content/schoolMedia'
import '../styles/public-cinema.css'

const route = useRoute()
const router = useRouter()
const stage = ref<HTMLElement>()
const school = ref<School | null>(null)
const contents = ref<PublicContent[]>([])
const error = ref('')
const loading = ref(true)
const slide = ref(0)
const previousSlide = ref(-1)
const active = ref(0)
const images = [schoolMedia.overview, schoolMedia.learning]
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
const darkChapter = computed(() => active.value === 0 || chapters[active.value]?.id === 'life')
let media: gsap.MatchMedia | undefined
let scrollFrame = 0
let resizeFrame = 0
let motionFrame = 0
let viewportWidth = 0
let resize: ResizeObserver | undefined
let mounted = false
let motionReady = false

function selectSlide(index: number) {
  const next = (index + images.length) % images.length
  if (next !== slide.value) { previousSlide.value = slide.value; slide.value = next }
}
function targetTop(index: number) {
  const panel = stage.value?.querySelectorAll<HTMLElement>('[data-chapter]')[index]
  const header = document.querySelector<HTMLElement>('.public-header')
  const offset = header ? Number.parseFloat(getComputedStyle(header).getPropertyValue('--chapter-offset')) || header.offsetHeight : 0
  return panel ? panel.getBoundingClientRect().top + window.scrollY - (index ? offset : 0) : 0
}
function setActive(index: number) {
  active.value = index
  activeChapter.value = chapters[index]?.id ?? 'home'
}
function go(index: number, instant = false) {
  if (!stage.value || !mounted) return
  const clamped = Math.max(0, Math.min(chapters.length - 1, index))
  window.scrollTo({ top: Math.max(0, targetTop(clamped)), behavior: publicReducedMotion.value || instant ? 'instant' : 'smooth' })
}
function selectChapter(index: number) {
  const chapter = chapters[index]
  if (!chapter) return
  if ((route.query.chapter ?? 'home') === chapter.id) go(index)
  else void router.replace({ path: '/', query: chapter.id === 'home' ? {} : { chapter: chapter.id } })
}
function scrollChanged() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    // Keep the reading chapter while a width change reflows the page.
    if (resizeFrame || motionFrame || window.innerWidth !== viewportWidth) return
    const sections = stage.value?.querySelectorAll<HTMLElement>('[data-chapter]')
    if (!sections) return
    let index = 0
    for (let i = 0; i < sections.length; i++) {
      if (sections[i]!.getBoundingClientRect().top <= window.innerHeight * 0.42) index = i
    }
    setActive(index)
    const current = sections[index]!.getBoundingClientRect()
    const next = sections[index + 1]?.getBoundingClientRect()
    const header = document.querySelector<HTMLElement>('.public-header')
    const offset = index ? Number.parseFloat(header ? getComputedStyle(header).getPropertyValue('--chapter-offset') : '') || 0 : 0
    const distance = (next ? next.top - current.top : current.height) - window.innerHeight * 0.42 + offset
    chapterReadingProgress.value = Math.max(0, Math.min(1, (offset - current.top) / Math.max(1, distance)))
  })
}
function viewportResized() {
  if (window.innerWidth === viewportWidth) return
  const chapter = active.value
  viewportWidth = window.innerWidth
  cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0
    if (!mounted) return
    ScrollTrigger.refresh()
    go(chapter, true)
    scrollChanged()
  })
}
function setupMotion() {
  if (!stage.value) return
  gsap.registerPlugin(ScrollTrigger)
  if (publicReducedMotion.value) return
  media = gsap.matchMedia(stage.value)
  media.add({ desktop: '(min-width: 900px)', motion: '(prefers-reduced-motion: no-preference)' }, context => {
    if (!context.conditions?.motion) return
    const root = stage.value!
    gsap.from(root.querySelectorAll('.hero-title-line > span'), {
      yPercent: 105, duration: 1.35, stagger: 0.16, ease: 'power3.out',
    })
    // Images move within their own frames; text remains in the document flow.
    if (context.conditions.desktop) {
      const hero = root.querySelector('.hero-chapter')
      gsap.to(root.querySelector('.hero-backdrop'), {
        yPercent: 5, scale: 1.13, ease: 'none',
        scrollTrigger: { trigger: hero, start: 'top top', end: 'bottom 30%', scrub: 0.8 },
      })
      gsap.to(root.querySelector('.hero-copy'), {
        y: -85, opacity: 0, ease: 'none',
        scrollTrigger: { trigger: hero, start: 'top top', end: 'bottom 40%', scrub: 0.6 },
      })
      for (const selector of ['.story-image-plane', '.life-photo img']) {
        const image = root.querySelector(selector)
        const isLife = selector === '.life-photo img'
        if (image) gsap.fromTo(image, { yPercent: isLife ? -0.6 : -3, scale: isLife ? 1.025 : 1.08 }, {
          yPercent: isLife ? 0.6 : 3, scale: isLife ? 1.025 : 1.08, ease: 'none',
          scrollTrigger: { trigger: image.parentElement, start: 'top bottom', end: 'bottom top', scrub: 0.8 },
        })
      }
    }
    const timeline = root.querySelector('.story-timeline')
    if (timeline) {
      gsap.from(timeline.children, {
        y: 28, opacity: 0, stagger: 0.18, duration: 0.85, ease: 'power2.out',
        scrollTrigger: { trigger: timeline, start: 'top 88%', once: true },
      })
      gsap.from(root.querySelector('.story-history-rule'), {
        scaleX: 0, transformOrigin: 'left', ease: 'none',
        scrollTrigger: { trigger: timeline, start: 'top 90%', end: 'bottom 55%', scrub: 0.5 },
      })
    }
    for (const element of root.querySelectorAll<HTMLElement>('[data-reveal]')) {
      gsap.from(element, {
        y: 26, opacity: 0, duration: 0.9, ease: 'power2.out',
        scrollTrigger: { trigger: element, start: 'top 94%', once: true },
      })
    }
  })
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
watch(requestedChapter, request => go(chapters.findIndex(chapter => chapter.id === request.id)), { flush: 'post' })
watch(() => route.query.chapter, id => {
  const index = chapters.findIndex(chapter => chapter.id === (id ?? 'home'))
  if (index >= 0) go(index)
}, { flush: 'post' })
watch(publicReducedMotion, async () => {
  if (!mounted || !motionReady) return
  const chapter = active.value
  cancelAnimationFrame(motionFrame)
  media?.revert()
  media = undefined
  await nextTick()
  if (!mounted) return
  setupMotion()
  // The sticky opening changes height. Restore the anchor after the new layout settles.
  motionFrame = requestAnimationFrame(() => {
    motionFrame = 0
    if (!mounted) return
    ScrollTrigger.refresh()
    go(chapter, true)
    scrollChanged()
  })
}, { flush: 'post' })
onMounted(async () => {
  mounted = true
  viewportWidth = window.innerWidth
  void load()
  await nextTick()
  if (!mounted) return
  window.addEventListener('scroll', scrollChanged, { passive: true })
  window.addEventListener('resize', viewportResized)
  setupMotion()
  const index = chapters.findIndex(chapter => chapter.id === route.query.chapter)
  if (index >= 0) go(index, true)
  scrollChanged()
  resize = new ResizeObserver(() => {
    ScrollTrigger.refresh()
    scrollChanged()
  })
  if (stage.value) resize.observe(stage.value)
  motionReady = true
})
onUnmounted(() => {
  mounted = false
  motionReady = false
  cancelAnimationFrame(scrollFrame)
  cancelAnimationFrame(resizeFrame)
  cancelAnimationFrame(motionFrame)
  media?.revert()
  resize?.disconnect()
  window.removeEventListener('scroll', scrollChanged)
  window.removeEventListener('resize', viewportResized)
})
</script>
<template>
  <div class="homepage-stage-wrap cinema-home" :class="{ 'is-dark-chapter': darkChapter, 'has-story-motion': !publicReducedMotion }">
    <div v-if="error" class="public-api-error page-width" role="alert">{{ error }} <button class="text-button" @click="load">重新加载</button></div>
    <div ref="stage" class="chapter-stage" aria-label="学校官网章节浏览">
      <section id="home" data-chapter="home" class="campus-chapter hero-chapter" aria-label="首页">
        <div class="hero-scene">
        <div class="hero-backdrop"><div class="hero-slides" aria-hidden="true"><div v-for="(image, index) in images" :key="image.url" class="hero-slide" :class="{ current: slide === index, retiring: previousSlide === index && slide !== index }" :style="{ backgroundImage: 'url(' + image.url + ')', backgroundPosition: image.position }"></div></div></div>
        <div class="hero-shade"></div>
        <span class="hero-side-note" aria-hidden="true">ZHENGZHOU · A JOURNEY OF GROWTH</span>
        <div class="hero-copy page-width"><span class="cinema-eyebrow">在校园中，开启自己的成长旅程</span><h1><span class="hero-title-line"><span>励学敦行</span></span><span class="hero-title-line"><span>明理至善</span></span></h1><p class="hero-caption">让每一次探索，<br>成为走向未来的起点。</p><RouterLink :to="publicContentLink('school-introduction', 'home')">认识郑工 <ArrowUpRight :size="17" /></RouterLink></div>
        <div class="hero-controls page-width"><span>{{ String(slide + 1).padStart(2, '0') }} <small>/ {{ String(images.length).padStart(2, '0') }}</small></span><div><button class="icon-button" aria-label="上一张校园图片" @click="selectSlide(slide - 1)"><ArrowLeft :size="17" /></button><button v-for="(image,index) in images" :key="image.url" class="slide-selector" :class="{ current: slide === index }" :aria-label="'切换到' + image.label" :aria-pressed="slide === index" @click="selectSlide(index)"></button><button class="icon-button" aria-label="下一张校园图片" @click="selectSlide(slide + 1)"><ArrowRight :size="17" /></button><small class="hero-image-label">校园影像</small></div></div>
        <a class="hero-photo-credit" :href="images[slide]?.sourceUrl" target="_blank" rel="noopener noreferrer">{{ images[slide]?.label }} · 学校官网影像 <ArrowUpRight :size="12" /></a>
        <button class="hero-scroll-cue" @click="go(1)"><span class="scroll-line" aria-hidden="true"></span>向下探索 <ArrowDown :size="13" /></button>
        </div>
      </section>
      <section v-for="(panel,index) in panels" :id="panel.id" :key="panel.id" :data-chapter="panel.id" class="campus-chapter theme-chapter" :class="['tone-' + panel.tone, 'chapter-' + panel.id, { 'is-active': active === index + 1 }]" :aria-label="chapters[index + 1]?.name">
        <SchoolStory v-if="panel.id === 'about'" :media="schoolMedia.lakeside" :campuses="school?.campuses ?? []"
          :summary="contents.find(item => item.slug === 'school-introduction')?.summary ?? '郑州工业应用技术学院位于河南省新郑市，设东、西两个校区。'" />
        <div v-else-if="panel.id === 'life'" class="life-story page-width">
          <div class="life-heading" data-reveal><span class="cinema-eyebrow">07 / LIFE ON CAMPUS</span><p>学习之外，还有生活的万千可能。</p></div>
          <div class="life-composition">
            <figure class="life-visual"><div class="life-photo"><img :src="schoolMedia.performance.url" :alt="schoolMedia.performance.alt" :width="schoolMedia.performance.width" :height="schoolMedia.performance.height" loading="lazy" decoding="async" /></div><figcaption><span>舞台上的热爱，日常里的光。</span><a :href="schoolMedia.performance.sourceUrl" target="_blank" rel="noopener noreferrer">迎新文艺演出 · 学校官网影像 <ArrowUpRight :size="12" /></a></figcaption></figure>
            <div class="life-copy" data-reveal><span class="life-word" aria-hidden="true">LIVE<br>YOUR<br>PASSION.</span><h2>把热爱<br><em>写进日常</em></h2><p>{{ panel.body }}</p></div>
          </div>
          <div class="life-footnote"><span>在这里，发现课堂之外的自己。</span><span>阅读 · 运动 · 艺术 · 伙伴</span></div>
          <div class="life-explore-bridge" data-reveal><div><span class="cinema-eyebrow">CONTINUE YOUR JOURNEY</span><h3>下一站，走进校园空间。</h3><p>转动视角，在三维校园中认识楼宇与地点。</p></div><RouterLink class="explore-journey-link" to="/campus/explore?view=model"><CampusContour /><span>进入三维校园 <ArrowUpRight :size="18" /></span></RouterLink></div>
        </div>
        <div v-else class="editorial-chapter page-width">
          <div class="editorial-heading" data-reveal><span class="cinema-eyebrow">{{ String(index + 1).padStart(2, '0') }} / {{ panel.eyebrow }}</span><span>{{ chapters[index + 1]?.name }}</span></div>
          <div class="chapter-body" :class="{ split: panel.media && panel.tone !== 'photo', 'directory-layout': panel.id === 'departments' || panel.id === 'disclosure' }">
          <div class="chapter-copy" data-reveal>
            <h2 class="chapter-title">{{ panel.title[0] }}<br>{{ panel.title[1] }}</h2><p>{{ panel.body }}</p>
            <RouterLink v-if="panel.slug" class="chapter-link" :to="publicContentLink(panel.slug, panel.id)">{{ panel.link }} <ArrowUpRight :size="16" /></RouterLink>
            <a v-if="panel.external" class="chapter-link" :href="panel.external" target="_blank" rel="noopener noreferrer">学校招生信息网 <ArrowUpRight :size="16" /></a>
            <a v-if="panel.id === 'departments'" class="chapter-link" href="https://zsb.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">查阅招生与专业资料 <ArrowUpRight :size="16" /></a>
            <div v-if="panel.id === 'exchange'" class="exchange-line"><span>交流信息以学校发布为准</span><a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">学校官方站点 <ArrowUpRight :size="15" /></a></div>
          </div>
          <figure v-if="panel.media && panel.tone !== 'photo'" class="chapter-visual" :style="{ maxWidth: panel.media.width + 'px' }" data-reveal>
            <div class="chapter-media campus-photo" :style="{ '--photo-aspect': panel.media.width + ' / ' + panel.media.height }"><img :src="panel.media.url" :alt="panel.media.alt" :width="panel.media.width" :height="panel.media.height" :style="{ objectPosition: panel.media.position }" loading="lazy" decoding="async" /></div>
            <figcaption class="campus-caption"><span>{{ panel.media.label }}</span><small><a :href="panel.media.sourceUrl" target="_blank" rel="noopener noreferrer">学校官网影像 <ArrowUpRight :size="12" aria-hidden="true" /></a></small></figcaption>
          </figure>
          <div v-if="panel.id === 'departments'" class="department-grid" data-reveal><a v-for="(department,departmentIndex) in departments" :key="department" href="https://zsb.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer"><small class="department-number" aria-hidden="true">{{ String(departmentIndex + 1).padStart(2,'0') }}</small><span>{{ department }}</span><ArrowUpRight :size="15" /></a><small>院系名称参考学校公开招生目录；点击前往学校招生信息网。</small></div>
          <div v-if="panel.id === 'disclosure'" class="public-content-list" data-reveal>
            <RouterLink v-for="item in contents" :key="item.id" :to="publicContentLink(item.slug, 'disclosure')"><div class="public-content-copy"><small><span>{{ item.sourceType === 'DEMO' ? '演示内容' : '学校资料' }}</span><time v-if="item.publishedAt" :datetime="item.publishedAt">{{ formatPublicDate(item.publishedAt) }}</time></small><span>{{ item.title }}</span><p>{{ item.summary }}</p></div><ArrowUpRight :size="16" /></RouterLink>
            <p v-if="loading" class="muted" role="status">正在加载公开资料…</p>
            <p v-else-if="error" class="muted">公开资料暂时无法加载，可通过学校官方站点查阅。</p>
            <p v-else-if="!contents.length" class="muted">暂无已发布公开内容。</p>
            <a class="public-catalog-source" href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer"><span>更多学校公开信息</span><ArrowUpRight :size="13" /></a>
          </div>
          </div>
        </div>
      </section>
      <footer class="cinema-closing page-width"><CampusContour class="closing-campus-contour" /><span>下一段校园故事，由你开启。</span><div class="closing-paths"><RouterLink to="/campus/explore?view=model"><small>01 / EXPLORE</small><span>继续探索校园 <ArrowUpRight :size="18" /></span><p>转动视角，认识校园楼宇与公共空间。</p></RouterLink><RouterLink to="/login"><small>02 / CAMPUS SERVICES</small><span>进入校园服务 <ArrowUpRight :size="18" /></span><p>登录个人门户，查看学习与办事信息。</p></RouterLink></div><small>校园综合服务平台 · 毕业设计演示</small></footer>
    </div>
    <div class="chapter-progress page-width">
      <ChapterDirectory @select="selectChapter" />
      <div>
        <RouterLink v-for="item in publishedNotices.slice(0,1)" :key="item.id" :to="publicContentLink(item.slug, chapters[active]?.id ?? 'home')">通知公告</RouterLink>
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
