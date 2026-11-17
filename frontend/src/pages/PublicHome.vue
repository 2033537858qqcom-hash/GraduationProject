<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ArrowUpRight } from '@lucide/vue';
import { gsap } from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';
import { api, errorMessage } from '../lib/api';
import { activeChapter, chapterReadingProgress, chapters, requestedChapter } from '../lib/publicNavigation';
import { publicReducedMotion } from '../lib/publicMotion';
import { currentUser, landingPage } from '../stores/auth';
import SchoolStory from '../components/SchoolStory.vue';
import CampusContour from '../components/CampusContour.vue';
import ChapterDirectory from '../components/ChapterDirectory.vue';
import { formatPublicDate, publicContentLink } from '../lib/publicReading';
import type { PublicContent, School } from '../types';
import { schoolMedia } from '../content/schoolMedia';
import '../styles/public-cinema.css';

const route = useRoute();
const router = useRouter();
const stage = ref<HTMLElement>();
const school = ref<School | null>(null);
const contents = ref<PublicContent[]>([]);
const error = ref('');
const loading = ref(true);
const slide = ref(0);
const previousSlide = ref(-1);
const active = ref(0);
const heroPaused = ref(false);
const hoveredDept = ref(0);
const disclosureCategory = ref<'all' | 'notice' | 'about'>('all');
const images = [schoolMedia.overview, schoolMedia.mingde, schoolMedia.spring];
const panels = [
  {
    id: 'about',
    eyebrow: 'ABOUT ZUIT',
    title: ['认识学校', '连接未来'],
    body: '学校概况与东、西校区介绍来自公开资料，校园动态与专题内容将持续更新发布。',
    media: schoolMedia.lakeside,
    link: '认识郑州工业应用技术学院',
    slug: 'school-introduction',
    tone: 'surface',
  },
  {
    id: 'departments',
    eyebrow: 'SCHOOLS & PROGRAMS',
    title: ['从专业出发', '在实践中成长'],
    body: '在不同的学科之间，探索自己的学习方向。',
    tone: 'surface',
  },
  {
    id: 'education',
    eyebrow: 'LEARNING & EDUCATION',
    title: ['让知识', '与实践相连'],
    body: '课程、实践与持续学习，共同构成校园中的成长旅程。',
    media: schoolMedia.practice,
    secondaryMedia: schoolMedia.architecture,
    tone: 'surface',
  },
  {
    id: 'research',
    eyebrow: 'RESEARCH & DISCOVERY',
    title: ['从一个问题', '走向新的发现'],
    body: '汇聚科研平台建设、学术交流活动与研究动态，伴随校园公开信息持续更新。',
    media: schoolMedia.library,
    tone: 'surface',
  },
  {
    id: 'admissions',
    eyebrow: 'ADMISSIONS & CAREERS',
    title: ['了解学校', '开启下一段旅程'],
    body: '学校公开招生信息、培养特色与校园风貌，可通过官方招生与校园站点查阅。',
    media: schoolMedia.freshmen,
    tone: 'surface',
    external: 'https://zsb.zzuit.edu.cn/',
  },
  {
    id: 'exchange',
    eyebrow: 'CONNECTION & PARTNERSHIPS',
    title: ['让交流', '打开新的视野'],
    body: '关注学校合作交流信息，在更广阔的对话中理解校园与世界的连接。',
    media: schoolMedia.boardwalk,
    tone: 'surface',
  },
  {
    id: 'life',
    eyebrow: 'LIFE ON CAMPUS',
    title: ['把热爱', '写进校园日常'],
    body: '运动、阅读、艺术与伙伴，在校园中找到更多可能。',
    media: schoolMedia.performance,
    tone: 'photo',
  },
  {
    id: 'disclosure',
    eyebrow: 'PUBLIC INFORMATION',
    title: ['公开信息', '清晰可达'],
    body: '从学校资料到平台说明，快速查阅已发布的公开信息与核验入口。',
    tone: 'surface',
  },
];
const departments = [
  {
    name: '信息工程学院',
    english: 'INFORMATION ENGINEERING',
    focus: '软件工程 · 人工智能 · 物联网与电子信息',
    note: '面向数字时代与智能计算，强化工程实训与创新实践。',
    media: schoolMedia.mingde,
    url: 'https://zsb.zzuit.edu.cn/',
  },
  {
    name: '机电工程学院',
    english: 'MECHANICAL & ELECTRICAL',
    focus: '智能制造 · 自动化控制 · 工业机器人',
    note: '以实验平台和工程装备为依托，培养扎实的应用型工程人才。',
    media: schoolMedia.practice,
    url: 'https://zsb.zzuit.edu.cn/',
  },
  {
    name: '建筑工程学院',
    english: 'CIVIL & CONSTRUCTION',
    focus: '工程造价 · 土木建造 · 城乡空间设计',
    note: '将结构力学、智能建造与人居空间美学融入课堂与工坊。',
    media: schoolMedia.lakeside,
    url: 'https://zsb.zzuit.edu.cn/',
  },
  {
    name: '医学院',
    english: 'MEDICAL SCIENCES',
    focus: '护理学 · 康复治疗 · 医学检验与健康管理',
    note: '秉持仁心精术，对接临床实践与区域健康服务需求。',
    media: schoolMedia.library,
    url: 'https://zsb.zzuit.edu.cn/',
  },
  {
    name: '管理学院',
    english: 'BUSINESS & MANAGEMENT',
    focus: '财务管理 · 数字商贸 · 现代物流与运营',
    note: '结合产业数字化实践，培养兼具洞察力与执行力的管理人才。',
    media: schoolMedia.boardwalk,
    url: 'https://zsb.zzuit.edu.cn/',
  },
  {
    name: '艺术设计学院',
    english: 'ART & DESIGN',
    focus: '视觉传达 · 环境设计 · 数字媒体艺术',
    note: '在艺术楼光影与工坊创作中，把东方意蕴与当代设计相连。',
    media: schoolMedia.architecture,
    url: 'https://zsb.zzuit.edu.cn/',
  },
];
const activeDepartment = computed(() => departments[hoveredDept.value] ?? departments[0]!);
const lifePillars = [
  { label: '阅读', line: '清晨图书馆台阶上的日光与墨香，陪你度过每一次安静求索。' },
  { label: '运动', line: '红体馆与绿茵场上的汗水，记录青春里最纯粹的奔跑。' },
  { label: '艺术', line: '艺术楼窗畔与舞台聚光灯下的热爱，让日常自带光芒。' },
  { label: '伙伴', line: '在东、西校区并肩前行的同窗，构成最难忘的校园记忆。' },
];
const activePillar = ref(0);
const publishedNotices = computed(() => contents.value.filter(item => item.category === 'notice'));
const filteredContents = computed(() => {
  const list =
    disclosureCategory.value === 'all'
      ? contents.value
      : contents.value.filter(item => item.category === disclosureCategory.value);
  return list.slice(0, 5);
});
const darkChapter = computed(() => active.value === 0 || chapters[active.value]?.id === 'life');
let media: gsap.MatchMedia | undefined;
let slideTimer: ReturnType<typeof setInterval> | undefined;
let scrollFrame = 0;
let resizeFrame = 0;
let motionFrame = 0;
let viewportWidth = 0;
let resize: ResizeObserver | undefined;
let mounted = false;
let motionReady = false;

function selectSlide(index: number) {
  const next = (index + images.length) % images.length;
  if (next !== slide.value) {
    previousSlide.value = slide.value;
    slide.value = next;
  }
}
function startSlideTimer() {
  stopSlideTimer();
  if (publicReducedMotion.value) return;
  slideTimer = setInterval(() => {
    if (!heroPaused.value && !document.hidden && active.value === 0) selectSlide(slide.value + 1);
  }, 7500);
}
function stopSlideTimer() {
  if (slideTimer) {
    clearInterval(slideTimer);
    slideTimer = undefined;
  }
}
function targetTop(index: number) {
  const panel = stage.value?.querySelectorAll<HTMLElement>('[data-chapter]')[index];
  const header = document.querySelector<HTMLElement>('.public-header');
  const offset = header
    ? Number.parseFloat(getComputedStyle(header).getPropertyValue('--chapter-offset')) || header.offsetHeight
    : 0;
  return panel ? panel.getBoundingClientRect().top + window.scrollY - (index ? offset : 0) : 0;
}
function setActive(index: number) {
  active.value = index;
  activeChapter.value = chapters[index]?.id ?? 'home';
}
function go(index: number, instant = false) {
  if (!stage.value || !mounted) return;
  const clamped = Math.max(0, Math.min(chapters.length - 1, index));
  window.scrollTo({
    top: Math.max(0, targetTop(clamped)),
    behavior: publicReducedMotion.value || instant ? 'instant' : 'smooth',
  });
}
function selectChapter(index: number) {
  const chapter = chapters[index];
  if (!chapter) return;
  if ((route.query.chapter ?? 'home') === chapter.id) go(index);
  else void router.replace({ path: '/', query: chapter.id === 'home' ? {} : { chapter: chapter.id } });
}
function scrollChanged() {
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    // Keep the reading chapter while a width change reflows the page.
    if (resizeFrame || motionFrame || window.innerWidth !== viewportWidth) return;
    const sections = stage.value?.querySelectorAll<HTMLElement>('[data-chapter]');
    if (!sections) return;
    let index = 0;
    for (let i = 0; i < sections.length; i++) {
      if (sections[i]!.getBoundingClientRect().top <= window.innerHeight * 0.42) index = i;
    }
    setActive(index);
    const current = sections[index]!.getBoundingClientRect();
    const next = sections[index + 1]?.getBoundingClientRect();
    const header = document.querySelector<HTMLElement>('.public-header');
    const offset = index
      ? Number.parseFloat(header ? getComputedStyle(header).getPropertyValue('--chapter-offset') : '') || 0
      : 0;
    const distance = (next ? next.top - current.top : current.height) - window.innerHeight * 0.42 + offset;
    chapterReadingProgress.value = Math.max(0, Math.min(1, (offset - current.top) / Math.max(1, distance)));
  });
}
function viewportResized() {
  if (window.innerWidth === viewportWidth) return;
  const chapter = active.value;
  viewportWidth = window.innerWidth;
  cancelAnimationFrame(resizeFrame);
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = 0;
    if (!mounted) return;
    ScrollTrigger.refresh();
    go(chapter, true);
    scrollChanged();
  });
}
function setupMotion() {
  if (!stage.value) return;
  gsap.registerPlugin(ScrollTrigger);
  if (publicReducedMotion.value) return;
  media = gsap.matchMedia(stage.value);
  media.add({ desktop: '(min-width: 900px)', motion: '(prefers-reduced-motion: no-preference)' }, context => {
    if (!context.conditions?.motion) return;
    const root = stage.value!;
    gsap.from(root.querySelectorAll('.hero-title-line > span'), {
      yPercent: 105,
      duration: 1.35,
      stagger: 0.16,
      ease: 'power3.out',
    });
    // Images move within their own frames; text remains in the document flow.
    if (context.conditions.desktop) {
      const hero = root.querySelector('.hero-chapter');
      gsap.to(root.querySelector('.hero-backdrop'), {
        yPercent: 5,
        scale: 1.13,
        ease: 'none',
        scrollTrigger: { trigger: hero, start: 'top top', end: 'bottom 30%', scrub: 0.8 },
      });
      gsap.to(root.querySelector('.hero-copy'), {
        y: -85,
        opacity: 0,
        ease: 'none',
        scrollTrigger: { trigger: hero, start: 'top top', end: 'bottom 40%', scrub: 0.6 },
      });
      for (const selector of ['.story-image-plane', '.life-photo img']) {
        const image = root.querySelector(selector);
        const isLife = selector === '.life-photo img';
        if (image)
          gsap.fromTo(
            image,
            { yPercent: isLife ? -0.6 : -3, scale: isLife ? 1.025 : 1.08 },
            {
              yPercent: isLife ? 0.6 : 3,
              scale: isLife ? 1.025 : 1.08,
              ease: 'none',
              scrollTrigger: { trigger: image.parentElement, start: 'top bottom', end: 'bottom top', scrub: 0.8 },
            },
          );
      }
    }
    const timeline = root.querySelector('.story-timeline');
    if (timeline) {
      gsap.from(timeline.children, {
        y: 28,
        opacity: 0,
        stagger: 0.18,
        duration: 0.85,
        ease: 'power2.out',
        scrollTrigger: { trigger: timeline, start: 'top 88%', once: true },
      });
      gsap.from(root.querySelector('.story-history-rule'), {
        scaleX: 0,
        transformOrigin: 'left',
        ease: 'none',
        scrollTrigger: { trigger: timeline, start: 'top 90%', end: 'bottom 55%', scrub: 0.5 },
      });
    }
    for (const element of root.querySelectorAll<HTMLElement>('[data-reveal]')) {
      gsap.from(element, {
        y: 24,
        opacity: 0,
        duration: 0.9,
        ease: 'power2.out',
        scrollTrigger: { trigger: element, start: 'top 92%', once: true },
      });
    }
    for (const frame of root.querySelectorAll<HTMLElement>('[data-photo-reveal]')) {
      const target = frame.querySelector('.campus-photo, .life-photo') ?? frame;
      const img = target.querySelector('img');
      gsap.from(target, {
        clipPath: 'inset(10% 0% 0% 0%)',
        opacity: 0,
        duration: 1.1,
        ease: 'power3.out',
        clearProps: 'clipPath',
        scrollTrigger: { trigger: frame, start: 'top 90%', once: true },
      });
      if (img) {
        gsap.from(img, {
          scale: 1.07,
          duration: 1.35,
          ease: 'power2.out',
          clearProps: 'transform',
          scrollTrigger: { trigger: frame, start: 'top 90%', once: true },
        });
      }
    }
  });
}
async function load() {
  error.value = '';
  loading.value = true;
  try {
    const [identity, content] = await Promise.all([
      api<School>('/public/school', { cacheTtl: 60_000 }),
      api<PublicContent[]>('/public/content', { cacheTtl: 30_000 }),
    ]);
    school.value = identity;
    contents.value = content;
    await nextTick();
    if (mounted && route.query.chapter) {
      const index = chapters.findIndex(chapter => chapter.id === route.query.chapter);
      if (index > 0) go(index, true);
    }
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
watch(requestedChapter, request => go(chapters.findIndex(chapter => chapter.id === request.id)), { flush: 'post' });
watch(
  () => route.query.chapter,
  id => {
    const index = chapters.findIndex(chapter => chapter.id === (id ?? 'home'));
    if (index >= 0) go(index);
  },
  { flush: 'post' },
);
watch(
  publicReducedMotion,
  async () => {
    if (!mounted || !motionReady) return;
    const chapter = active.value;
    cancelAnimationFrame(motionFrame);
    stopSlideTimer();
    media?.revert();
    media = undefined;
    await nextTick();
    if (!mounted) return;
    setupMotion();
    startSlideTimer();
    // The sticky opening changes height. Restore the anchor after the new layout settles.
    motionFrame = requestAnimationFrame(() => {
      motionFrame = 0;
      if (!mounted) return;
      ScrollTrigger.refresh();
      go(chapter, true);
      scrollChanged();
    });
  },
  { flush: 'post' },
);
onMounted(async () => {
  mounted = true;
  viewportWidth = window.innerWidth;
  void load();
  await nextTick();
  if (!mounted) return;
  window.addEventListener('scroll', scrollChanged, { passive: true });
  window.addEventListener('resize', viewportResized);
  setupMotion();
  startSlideTimer();
  const index = chapters.findIndex(chapter => chapter.id === route.query.chapter);
  if (index >= 0) go(index, true);
  scrollChanged();
  resize = new ResizeObserver(() => {
    ScrollTrigger.refresh();
    scrollChanged();
  });
  if (stage.value) resize.observe(stage.value);
  motionReady = true;
});
onUnmounted(() => {
  mounted = false;
  motionReady = false;
  stopSlideTimer();
  cancelAnimationFrame(scrollFrame);
  cancelAnimationFrame(resizeFrame);
  cancelAnimationFrame(motionFrame);
  media?.revert();
  resize?.disconnect();
  window.removeEventListener('scroll', scrollChanged);
  window.removeEventListener('resize', viewportResized);
});
</script>
<template>
  <div
    class="homepage-stage-wrap cinema-home"
    :class="{
      'is-dark-chapter': darkChapter,
      'is-opening-chapter': active === 0,
      'has-story-motion': !publicReducedMotion,
    }"
  >
    <div ref="stage" class="chapter-stage" aria-label="学校官网章节浏览">
      <section
        id="home"
        data-chapter="home"
        class="campus-chapter hero-chapter"
        aria-label="首页"
        @mouseenter="heroPaused = true"
        @mouseleave="heroPaused = false"
        @focusin="heroPaused = true"
        @focusout="heroPaused = false"
        @keydown.left="selectSlide(slide - 1)"
        @keydown.right="selectSlide(slide + 1)"
      >
        <div class="hero-scene">
          <div class="hero-backdrop">
            <div class="hero-slides" aria-hidden="true">
              <div
                v-for="(image, index) in images"
                :key="image.url"
                class="hero-slide"
                :class="{ current: slide === index, retiring: previousSlide === index && slide !== index }"
              >
                <img
                  class="hero-slide-image"
                  :src="image.url"
                  alt=""
                  :fetchpriority="index === 0 ? 'high' : 'low'"
                  decoding="async"
                  :style="{ objectPosition: image.position }"
                />
              </div>
            </div>
          </div>
          <div class="hero-shade"></div>
          <span class="hero-side-note" aria-hidden="true">新郑 · 始于一九九七 · ZHENGZHOU</span>
          <div class="hero-copy page-width">
            <span class="cinema-eyebrow">在校园中，开启自己的成长旅程</span>
            <h1>
              <span class="hero-title-line"><span>励学敦行</span></span>
              <span class="hero-title-line"><span>明理至善</span></span>
            </h1>
            <p class="hero-caption">
              让每一次探索，
              <br />
              成为走向未来的起点。
            </p>
            <div class="hero-cta-row">
              <RouterLink class="hero-cta-primary" :to="publicContentLink('school-introduction', 'home')">
                认识郑工
                <ArrowUpRight :size="17" />
              </RouterLink>
              <RouterLink class="hero-cta-secondary" to="/campus/explore?view=model">
                三维校园漫游
                <ArrowUpRight :size="15" />
              </RouterLink>
            </div>
          </div>
          <div class="hero-controls page-width">
            <span>
              {{ String(slide + 1).padStart(2, '0') }}
              <small>/ {{ String(images.length).padStart(2, '0') }}</small>
            </span>
            <div>
              <button class="icon-button" aria-label="上一张校园图片" @click="selectSlide(slide - 1)">
                <ArrowLeft :size="17" />
              </button>
              <button
                v-for="(image, index) in images"
                :key="image.url"
                class="slide-selector"
                :class="{ current: slide === index }"
                :aria-label="'切换到' + image.label"
                :aria-pressed="slide === index"
                @click="selectSlide(index)"
              ></button>
              <button class="icon-button" aria-label="下一张校园图片" @click="selectSlide(slide + 1)">
                <ArrowRight :size="17" />
              </button>
              <small class="hero-image-label">{{ images[slide]?.label }}</small>
            </div>
          </div>
          <a class="hero-photo-credit" :href="images[slide]?.sourceUrl" target="_blank" rel="noopener noreferrer">
            {{ images[slide]?.label }} · 学校官网影像
            <ArrowUpRight :size="12" />
          </a>
          <button class="hero-scroll-cue" @click="go(1)">
            <span class="scroll-line" aria-hidden="true"></span>
            向下探索
            <ArrowDown :size="13" />
          </button>
        </div>
      </section>
      <section
        v-for="(panel, index) in panels"
        :id="panel.id"
        :key="panel.id"
        :data-chapter="panel.id"
        class="campus-chapter theme-chapter"
        :class="['tone-' + panel.tone, 'chapter-' + panel.id, { 'is-active': active === index + 1 }]"
        :aria-label="chapters[index + 1]?.name"
      >
        <SchoolStory
          v-if="panel.id === 'about'"
          :media="schoolMedia.lakeside"
          :campuses="school?.campuses ?? []"
          :summary="
            contents.find(item => item.slug === 'school-introduction')?.summary ??
            '郑州工业应用技术学院位于河南省新郑市，设东、西两个校区。'
          "
        />
        <div v-else-if="panel.id === 'life'" class="life-story page-width">
          <div class="life-heading" data-reveal>
            <span class="cinema-eyebrow">07 / LIFE ON CAMPUS</span>
            <p>学习之外，还有生活的万千可能。</p>
          </div>
          <div class="life-composition">
            <figure class="life-visual" data-photo-reveal>
              <div class="life-photo">
                <img
                  :src="schoolMedia.performance.url"
                  :alt="schoolMedia.performance.alt"
                  :width="schoolMedia.performance.width"
                  :height="schoolMedia.performance.height"
                  loading="lazy"
                  decoding="async"
                />
              </div>
              <figcaption>
                <span>舞台上的热爱，日常里的光。</span>
                <a :href="schoolMedia.performance.sourceUrl" target="_blank" rel="noopener noreferrer">
                  迎新文艺演出 · 学校官网影像
                  <ArrowUpRight :size="12" />
                </a>
              </figcaption>
            </figure>
            <div class="life-copy" data-reveal>
              <span class="life-word" aria-hidden="true">
                LIVE
                <br />
                YOUR
                <br />
                PASSION.
              </span>
              <h2>
                把热爱
                <br />
                <em>写进日常</em>
              </h2>
              <p>{{ panel.body }}</p>
              <div class="life-pillars" role="group" aria-label="校园生活面向">
                <button
                  v-for="(pillar, pillarIndex) in lifePillars"
                  :key="pillar.label"
                  type="button"
                  :aria-pressed="activePillar === pillarIndex"
                  @mouseenter="activePillar = pillarIndex"
                  @click="activePillar = pillarIndex"
                >
                  {{ pillar.label }}
                </button>
              </div>
              <Transition :name="publicReducedMotion ? '' : 'pillar-fade'" mode="out-in">
                <p :key="activePillar" class="life-pillar-note">{{ lifePillars[activePillar]?.line }}</p>
              </Transition>
            </div>
          </div>
          <div class="life-explore-bridge" data-reveal>
            <div>
              <span class="cinema-eyebrow">CONTINUE YOUR JOURNEY</span>
              <h3>下一站，走进校园空间。</h3>
              <p>转动视角，在三维校园中认识楼宇与地点。</p>
            </div>
            <RouterLink class="explore-journey-link" to="/campus/explore?view=model">
              <CampusContour />
              <span>
                进入三维校园
                <ArrowUpRight :size="18" />
              </span>
            </RouterLink>
          </div>
        </div>
        <div v-else class="editorial-chapter page-width">
          <div class="editorial-heading" data-reveal>
            <span class="cinema-eyebrow">{{ String(index + 1).padStart(2, '0') }} / {{ panel.eyebrow }}</span>
            <span>{{ chapters[index + 1]?.name }}</span>
          </div>
          <div
            class="chapter-body"
            :class="{
              split: panel.media && panel.tone !== 'photo',
              'directory-layout': panel.id === 'departments' || panel.id === 'disclosure',
            }"
          >
            <div class="chapter-copy" data-reveal>
              <span class="chapter-watermark" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
              <h2 class="chapter-title">
                {{ panel.title[0] }}
                <br />
                {{ panel.title[1] }}
              </h2>
              <p>{{ panel.body }}</p>
              <ul v-if="panel.id === 'education'" class="editorial-pillars" aria-label="教学特色">
                <li>
                  <span>01</span>
                  工程实训与项目化课堂
                </li>
                <li>
                  <span>02</span>
                  校企协同与产教深度融合
                </li>
                <li>
                  <span>03</span>
                  理工实践与人文美育并重
                </li>
              </ul>
              <ul v-if="panel.id === 'research'" class="editorial-pillars" aria-label="科研与学术支撑">
                <li>
                  <span>01</span>
                  重点（培育）学科与平台建设
                </li>
                <li>
                  <span>02</span>
                  工程技术研发与产学研协作
                </li>
                <li>
                  <span>03</span>
                  东、西校区馆藏与数字文献支撑
                </li>
              </ul>
              <div v-if="panel.id === 'admissions'" class="admissions-facts" aria-label="学校办学概览">
                <div>
                  <strong>东 / 西</strong>
                  <span>新郑双校区联动育人</span>
                </div>
                <div>
                  <strong>6 大学院</strong>
                  <span>工 · 医 · 管 · 艺多学科布局</span>
                </div>
              </div>
              <div v-if="panel.id === 'departments'" class="department-spotlight">
                <div class="department-spotlight-frame">
                  <img
                    v-for="(dept, deptIndex) in departments"
                    :key="dept.name"
                    :class="{ 'is-active': hoveredDept === deptIndex }"
                    :src="dept.media.url"
                    :alt="dept.media.alt"
                    :width="dept.media.width"
                    :height="dept.media.height"
                    :style="{ objectPosition: dept.media.position }"
                    loading="lazy"
                    decoding="async"
                  />
                  <div class="department-spotlight-overlay">
                    <small>{{ activeDepartment.english }}</small>
                    <strong>{{ activeDepartment.name }}</strong>
                    <p>{{ activeDepartment.note }}</p>
                  </div>
                </div>
              </div>
              <RouterLink v-if="panel.slug" class="chapter-link" :to="publicContentLink(panel.slug, panel.id)">
                {{ panel.link }}
                <ArrowUpRight :size="16" />
              </RouterLink>
              <RouterLink v-if="panel.id === 'education'" class="chapter-link" to="/campus/explore?view=model">
                探索教学楼宇与空间
                <ArrowUpRight :size="16" />
              </RouterLink>
              <div v-if="panel.id === 'research'" class="chapter-action-row">
                <a class="chapter-link" href="https://kwc.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">
                  学校科研处
                  <ArrowUpRight :size="16" />
                </a>
                <a class="chapter-link" href="https://lib.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">
                  图书馆资源
                  <ArrowUpRight :size="16" />
                </a>
              </div>
              <div v-if="panel.external" class="chapter-action-row">
                <a
                  class="chapter-link chapter-link-framed"
                  :href="panel.external"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  学校招生信息网
                  <ArrowUpRight :size="16" />
                </a>
                <a class="chapter-link" href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">
                  校园动态与服务
                  <ArrowUpRight :size="16" />
                </a>
              </div>
              <a
                v-if="panel.id === 'departments'"
                class="chapter-link"
                href="https://zsb.zzuit.edu.cn/"
                target="_blank"
                rel="noopener noreferrer"
              >
                查阅招生与专业资料
                <ArrowUpRight :size="16" />
              </a>
              <div v-if="panel.id === 'exchange'" class="exchange-line">
                <span>交流信息以学校官方发布为准</span>
                <a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">
                  学校官方站点
                  <ArrowUpRight :size="15" />
                </a>
              </div>
            </div>
            <figure
              v-if="panel.media && panel.tone !== 'photo'"
              class="chapter-visual"
              :style="{ maxWidth: panel.media.width + 'px' }"
              data-photo-reveal
            >
              <div
                class="chapter-media campus-photo"
                :style="{ '--photo-aspect': panel.media.width + ' / ' + panel.media.height }"
              >
                <img
                  :src="panel.media.url"
                  :alt="panel.media.alt"
                  :width="panel.media.width"
                  :height="panel.media.height"
                  :style="{ objectPosition: panel.media.position }"
                  loading="lazy"
                  decoding="async"
                />
              </div>
              <div v-if="panel.secondaryMedia" class="education-inset-visual">
                <div class="campus-photo">
                  <img
                    :src="panel.secondaryMedia.url"
                    :alt="panel.secondaryMedia.alt"
                    :width="panel.secondaryMedia.width"
                    :height="panel.secondaryMedia.height"
                    loading="lazy"
                    decoding="async"
                  />
                </div>
                <small>{{ panel.secondaryMedia.label }} · 美育空间</small>
              </div>
              <figcaption class="campus-caption">
                <span>{{ panel.media.label }}</span>
                <small>
                  <a :href="panel.media.sourceUrl" target="_blank" rel="noopener noreferrer">
                    学校官网影像
                    <ArrowUpRight :size="12" aria-hidden="true" />
                  </a>
                </small>
              </figcaption>
            </figure>
            <div v-if="panel.id === 'departments'" class="department-grid" data-reveal>
              <a
                v-for="(department, departmentIndex) in departments"
                :key="department.name"
                :class="{ 'is-highlighted': hoveredDept === departmentIndex }"
                :href="department.url"
                target="_blank"
                rel="noopener noreferrer"
                @mouseenter="hoveredDept = departmentIndex"
                @focus="hoveredDept = departmentIndex"
              >
                <small class="department-number" aria-hidden="true">
                  {{ String(departmentIndex + 1).padStart(2, '0') }}
                </small>
                <div class="department-item-copy">
                  <span>{{ department.name }}</span>
                  <small>{{ department.focus }}</small>
                </div>
                <ArrowUpRight :size="15" />
              </a>
              <small>院系名称参考学校公开招生目录；悬停可预览学院风物，点击前往学校招生信息网。</small>
            </div>
            <div v-if="panel.id === 'disclosure'" class="public-content-list" data-reveal>
              <div class="disclosure-filters" role="group" aria-label="公开信息分类筛选">
                <button type="button" :aria-pressed="disclosureCategory === 'all'" @click="disclosureCategory = 'all'">
                  全部
                </button>
                <button
                  type="button"
                  :aria-pressed="disclosureCategory === 'notice'"
                  @click="disclosureCategory = 'notice'"
                >
                  通知公告
                </button>
                <button
                  type="button"
                  :aria-pressed="disclosureCategory === 'about'"
                  @click="disclosureCategory = 'about'"
                >
                  学校资料
                </button>
              </div>
              <div v-if="error" class="public-api-inline-error" role="alert">
                <span>{{ error }}</span>
                <button class="text-button" @click="load">重新加载</button>
              </div>
              <RouterLink
                v-for="item in filteredContents"
                :key="item.id"
                :to="publicContentLink(item.slug, 'disclosure')"
              >
                <div class="public-content-copy">
                  <small>
                    <span>
                      {{ item.category === 'notice' ? '通知公告' : '学校资料' }} ·
                      {{ item.sourceType === 'DEMO' ? '演示内容' : '公开资料' }}
                    </span>
                    <time v-if="item.publishedAt" :datetime="item.publishedAt">
                      {{ formatPublicDate(item.publishedAt) }}
                    </time>
                  </small>
                  <span>{{ item.title }}</span>
                  <p>{{ item.summary }}</p>
                </div>
                <ArrowUpRight :size="16" />
              </RouterLink>
              <p v-if="loading" class="muted" role="status">正在加载公开资料…</p>
              <p v-else-if="!error && !filteredContents.length" class="muted">当前分类暂无已发布公开内容。</p>
              <div class="disclosure-footer-links">
                <RouterLink class="public-catalog-source" to="/verify/certificate">
                  <span>在校证明防伪验真</span>
                  <ArrowUpRight :size="13" />
                </RouterLink>
                <a
                  class="public-catalog-source"
                  href="https://www.zzuit.edu.cn/"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <span>更多学校公开信息</span>
                  <ArrowUpRight :size="13" />
                </a>
              </div>
            </div>
          </div>
        </div>
      </section>
      <footer class="cinema-closing page-width">
        <CampusContour class="closing-campus-contour" />
        <span>下一段校园故事，由你开启。</span>
        <div class="closing-paths">
          <RouterLink to="/campus/explore?view=model">
            <small>01 / EXPLORE</small>
            <span>
              继续探索校园
              <ArrowUpRight :size="18" />
            </span>
            <p>转动视角，认识校园楼宇与公共空间。</p>
          </RouterLink>
          <RouterLink :to="currentUser ? landingPage() : '/login'">
            <small>02 / CAMPUS SERVICES</small>
            <span>
              {{ currentUser ? '进入个人门户' : '进入校园服务' }}
              <ArrowUpRight :size="18" />
            </span>
            <p>
              {{
                currentUser ? '已登录校园账号，立即查看课表、日程与日常办事。' : '登录个人门户，查看学习与办事信息。'
              }}
            </p>
          </RouterLink>
        </div>
        <div class="closing-meta">
          <div v-if="school?.campuses.length" class="closing-campuses">
            <span v-for="campus in school.campuses" :key="campus.id">
              <strong>{{ campus.name }}</strong>
              {{ campus.address }}
            </span>
          </div>
          <div class="closing-links">
            <RouterLink to="/verify/certificate">
              证明验真
              <ArrowUpRight :size="12" />
            </RouterLink>
            <a href="https://www.zzuit.edu.cn/" target="_blank" rel="noopener noreferrer">
              学校官网
              <ArrowUpRight :size="12" />
            </a>
            <small>校园综合服务平台 · 毕业设计演示</small>
          </div>
        </div>
      </footer>
    </div>
    <div class="chapter-progress page-width">
      <ChapterDirectory @select="selectChapter" />
      <div>
        <RouterLink
          v-for="item in publishedNotices.slice(0, 1)"
          :key="item.id"
          :to="publicContentLink(item.slug, chapters[active]?.id ?? 'home')"
        >
          通知公告
        </RouterLink>
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
    <span class="sr-only" aria-live="polite">
      {{ active === 0 ? '首页序章' : `${chapters[active]?.name}，第 ${active} 章，共 ${chapters.length - 1} 章。` }}
    </span>
  </div>
</template>
