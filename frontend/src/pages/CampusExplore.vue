<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, ArrowRight, BedDouble, Boxes, Building2, CalendarCheck, Check, CloudSun, Compass, Dumbbell, Layers, LocateFixed, MapPin, Minus, Moon, Plus, Search, Stethoscope, Sun, Tag, Trees, Utensils, X } from '@lucide/vue'
import { campusCategories, campusPlaces, campusTour } from '../campus/manifest'
import type { CampusCategory, CampusPlace } from '../campus/manifest'
import { createCampusScene } from '../campus/createCampusScene'
import type { CampusView, PlaceLabel } from '../campus/createCampusScene'
import { campusEvidence } from '../campus/eastCampusLayout'
import { westCampusMeta, eastCampusMeta, westCampusPlaces, westCampusTour } from '../campus/westCampusData'
import { createAMapCampusScene } from '../campus/createAMapCampusScene'

const route = useRoute()
const surface = ref<HTMLDivElement>()
const mapKey = import.meta.env.VITE_AMAP_KEY ?? ''
const mapConfigured = /^[a-f\d]{32}$/i.test(mapKey)
const mode = ref<'map' | 'model'>(mapConfigured ? 'map' : 'model')

// Dual campus state
const initialQueryCampus = (route.query.campus === 'WEST' || (typeof route.query.navigate === 'string' && route.query.navigate.startsWith('west-'))) ? 'WEST' : 'EAST'
const currentCampus = ref<'EAST' | 'WEST'>(initialQueryCampus)
const activeMeta = computed(() => currentCampus.value === 'WEST' ? westCampusMeta : eastCampusMeta)
const activeCampusPlaces = computed(() => currentCampus.value === 'WEST' ? westCampusPlaces : campusPlaces)
const activeTour = computed(() => currentCampus.value === 'WEST' ? westCampusTour : campusTour)
const allPlaces = computed(() => [...campusPlaces, ...westCampusPlaces])

// 2.5D Vector default settings (satellite defaults to false as preferred by user)
const is25D = ref(true)
const mapTheme = ref<'normal' | 'whitesmoke'>('normal')
const satellite = ref(false)

const modelReady = ref(false), buildings = ref(false)
const calibration = import.meta.env.DEV && new URLSearchParams(window.location.search).has('calibrate')
const calibrationCoordinate = ref('在地图上点击实物参照点')
const calibrationX = ref(0), calibrationY = ref(0)
const error = ref(''), loading = ref(true), search = ref('')
const category = ref<CampusCategory | 'all'>('all')
const selected = ref<string | null>(null)
const view = ref<CampusView>('overview'), moving = ref(false), showLabels = ref(true), evening = ref(false)
const labels = ref<PlaceLabel[]>([])
const tourStep = ref<number | null>(null)
const weatherInfo = ref<{ weather: string; temperature: number; wind: string } | null>(null)
const routeActive = ref(false)

type MapScene = Awaited<ReturnType<typeof createAMapCampusScene>>
let scene: (ReturnType<typeof createCampusScene> & Partial<Pick<MapScene, 'setSatellite' | 'setBuildings' | 'readCoordinate' | 'planRoute' | 'clearRoute' | 'getWeather' | 'switchCampus' | 'getCampus' | 'set25D' | 'setTheme'>>) | undefined
let generation = 0
let sceneAbort: AbortController | undefined

const currentPlace = computed(() => allPlaces.value.find(place => place.id === selected.value))
const filteredPlaces = computed(() => activeCampusPlaces.value.filter(place =>
  (category.value === 'all' || place.category === category.value) &&
  `${place.name}${place.description}${place.features.join('')}`.includes(search.value.trim()),
))

const categoryIcons = { learning: Building2, housing: BedDouble, services: MapPin, sports: Dumbbell, landscape: Trees }
const categoryNames = { learning: '校园楼宇', housing: '学生公寓', services: '校园服务', sports: '文体场馆', landscape: '景观与入口' }
const relatedPlace = computed(() => allPlaces.value.find(place => place.id === currentPlace.value?.relatedPlaceId))

function placeIcon(place: CampusPlace) {
  if (['east-jinxiu-dining', 'east-canteen-wenhua', 'west-canteen'].includes(place.id)) return Utensils
  if (place.id === 'east-clinic') return Stethoscope
  return categoryIcons[place.category]
}

const tourPlace = computed(() => tourStep.value === null ? undefined : allPlaces.value.find(place => place.id === activeTour.value[tourStep.value!]))

function locate(id: string, fromTour = false) {
  if (!fromTour) tourStep.value = null
  selected.value = id
  scene?.select(id)
  if (routeActive.value) {
    scene?.planRoute?.(id)
  }
}

function overview() {
  tourStep.value = null; selected.value = null; scene?.select(null)
  if (routeActive.value) {
    routeActive.value = false
    scene?.clearRoute?.()
  }
}

function switchCampus(campus: 'EAST' | 'WEST') {
  if (currentCampus.value === campus) return
  currentCampus.value = campus
  selected.value = null
  tourStep.value = null
  category.value = 'all'
  search.value = ''
  if (routeActive.value) {
    routeActive.value = false
    scene?.clearRoute?.()
  }
  if (mode.value === 'model' && campus === 'WEST') {
    mode.value = 'map'
    void initScene()
    return
  }
  scene?.switchCampus?.(campus)
}

function toggleRoute() {
  if (!selected.value) return
  routeActive.value = !routeActive.value
  if (routeActive.value) {
    scene?.planRoute?.(selected.value)
  } else {
    scene?.clearRoute?.()
  }
}

function applyNavigation(navId: string | null) {
  if (!navId) return
  const place = allPlaces.value.find(p => p.id === navId)
  if (place) {
    if (place.campusId && place.campusId !== currentCampus.value) {
      currentCampus.value = place.campusId
      scene?.switchCampus?.(place.campusId)
    }
    locate(place.id)
    routeActive.value = true
    scene?.planRoute?.(place.id)
  }
}

function toggle25D(enabled: boolean) {
  is25D.value = enabled
  scene?.set25D?.(enabled)
}

function changeTheme(theme: 'normal' | 'whitesmoke') {
  mapTheme.value = theme
  scene?.setTheme?.(theme)
}

function setEvening() { evening.value = !evening.value; scene?.setEvening(evening.value) }
function setSatellite() {
  satellite.value = !satellite.value
  scene?.setSatellite?.(satellite.value)
}
function setBuildings() { buildings.value = !buildings.value; scene?.setBuildings?.(buildings.value) }
function changeMode(next: 'map' | 'model') {
  if (currentCampus.value === 'WEST' && next === 'model') return
  if (next !== mode.value) { mode.value = next; void initScene() }
}
function tour(step: number) {
  tourStep.value = Math.max(0, Math.min(activeTour.value.length - 1, step))
  locate(activeTour.value[tourStep.value]!, true)
}
function closeTour() { tourStep.value = null }

async function initScene() {
  const run = ++generation
  sceneAbort?.abort(); sceneAbort = new AbortController(); modelReady.value = false; buildings.value = false
  scene?.dispose(); scene = undefined; labels.value = []; error.value = ''; loading.value = true
  if (!surface.value) return
  // Each renderer owns an empty host, never Vue's labels or controls.
  const host = document.createElement('div')
  host.className = mode.value === 'map' ? 'amap-host' : 'sandbox-host'
  surface.value.replaceChildren(host)
  try {
    const callbacks = {
      select: id => locate(id), labels: value => { labels.value = value },
      view: value => { view.value = value }, moving: value => { moving.value = value },
      unavailable: message => { error.value = message; labels.value = [] },
    } satisfies import('../campus/createCampusScene').SceneCallbacks
    const created = mode.value === 'map'
      ? await createAMapCampusScene(host, callbacks, {
        key: mapKey, initialCampus: currentCampus.value, signal: sceneAbort.signal,
        onCalibration: calibration ? coordinate => { calibrationCoordinate.value = coordinate } : undefined,
        onModelStatus: ready => { if (run === generation) modelReady.value = ready },
      })
      : createCampusScene(host, callbacks)
    if (run !== generation) { created.dispose(); host.remove(); return }
    scene = created
    scene.setEvening(evening.value)
    scene.set25D?.(is25D.value)
    scene.setTheme?.(mapTheme.value)
    scene.setSatellite?.(satellite.value)
    const initialNav = typeof route.query.navigate === 'string' ? route.query.navigate : null
    if (initialNav) {
      applyNavigation(initialNav)
    } else if (selected.value) {
      scene.select(selected.value)
      if (routeActive.value) scene.planRoute?.(selected.value)
    }
    if (mode.value === 'map') {
      void scene.getWeather?.().then(w => { if (run === generation) weatherInfo.value = w })
    }
  } catch (reason) {
    if (run !== generation) return
    host.remove()
    error.value = mode.value === 'map' && reason instanceof Error ? reason.message : '此设备暂时无法显示三维场景，仍可通过地点目录查看介绍。'
  } finally { if (run === generation) loading.value = false }
}

watch(() => route.query.navigate, newNav => {
  if (typeof newNav === 'string') {
    applyNavigation(newNav)
  }
})

watch(() => route.query.campus, newCampus => {
  if (newCampus === 'WEST' || newCampus === 'EAST') {
    switchCampus(newCampus)
  }
})

onMounted(initScene)
onUnmounted(() => { generation++; sceneAbort?.abort(); scene?.dispose() })
</script>

<template>
  <div class="explore-page page-width">
    <div class="exploration-heading">
      <div class="heading-meta">
        <span class="eyebrow">{{ activeMeta.englishName }} · 郑州工业应用技术学院</span>
        <h1>校园探索</h1>
        <p>{{ activeMeta.description }}</p>
      </div>

      <div class="heading-actions">
        <!-- Dual campus switcher -->
        <div class="campus-selector" role="group" aria-label="校区切换">
          <button
            class="campus-tab-btn"
            :class="{ active: currentCampus === 'EAST' }"
            :aria-pressed="currentCampus === 'EAST'"
            @click="switchCampus('EAST')"
          >
            <span class="campus-badge">主校区</span>
            <span class="campus-title">东校区（学院路）</span>
          </button>
          <button
            class="campus-tab-btn"
            :class="{ active: currentCampus === 'WEST' }"
            :aria-pressed="currentCampus === 'WEST'"
            @click="switchCampus('WEST')"
          >
            <span class="campus-badge">新村校区</span>
            <span class="campus-title">西校区（107国道）</span>
          </button>
        </div>

        <button class="explore-tour-start" :disabled="loading || !!error" @click="tour(0)">
          <Compass :size="16" />浏览地点<ArrowRight :size="15" />
        </button>
      </div>
    </div>

    <div class="exploration-workspace">
      <section class="exploration-viewport" :class="{ evening, 'map-mode': mode === 'map' }" aria-label="三维校园探索">
        <div class="exploration-toolbar">
          <div v-if="mapConfigured" class="view-options mode-options" role="group" aria-label="探索方式">
            <button :aria-pressed="mode === 'map'" @click="changeMode('map')">2.5D高德地图</button>
            <button
              :aria-pressed="mode === 'model'"
              :disabled="currentCampus === 'WEST'"
              :title="currentCampus === 'WEST' ? '西校区3D沙盘测绘中，推荐使用高德2.5D地图漫游' : '切换校园三维沙盘'"
              @click="changeMode('model')"
            >
              独立沙盘
            </button>
          </div>

          <!-- 2.5D vs 2D switch -->
          <div v-if="mode === 'map'" class="view-options" role="group" aria-label="视角呈现">
            <button :aria-pressed="is25D" title="2.5D 轴测鸟瞰视角（立体建筑）" @click="toggle25D(true)">
              <Boxes :size="15" /><span>2.5D 鸟瞰</span>
            </button>
            <button :aria-pressed="!is25D" title="2D 平面俯视视角" @click="toggle25D(false)">
              <Layers :size="15" /><span>2D 俯视</span>
            </button>
          </div>
          <div v-else class="view-options" role="group" aria-label="场景视角">
            <button :aria-pressed="view === 'overview'" :disabled="loading || !!error" @click="overview"><LocateFixed :size="15" /><span>总览</span></button>
            <button :aria-pressed="view === 'top'" :disabled="loading || !!error" @click="scene?.top()"><Layers :size="15" /><span>俯视</span></button>
          </div>

          <!-- Map Style Options -->
          <div class="display-options" role="group" aria-label="场景显示">
            <template v-if="mode === 'map'">
              <button
                :aria-pressed="!satellite && mapTheme === 'normal'"
                title="标准彩色矢量地图（显示立体建筑与路网）"
                @click="satellite = false; scene?.setSatellite?.(false); changeTheme('normal')"
              >
                标准矢量
              </button>
              <button
                :aria-pressed="!satellite && mapTheme === 'whitesmoke'"
                title="雅致素白轻量地图风格"
                @click="satellite = false; scene?.setSatellite?.(false); changeTheme('whitesmoke')"
              >
                雅致素白
              </button>
              <button
                :aria-pressed="satellite"
                title="切换卫星航拍影像"
                @click="setSatellite"
              >
                卫星影像
              </button>
            </template>

            <button v-if="mode === 'map' && modelReady && currentCampus === 'EAST'" :disabled="loading || !!error" :aria-pressed="buildings" aria-label="显示立体建筑沙盘" title="立体建筑沙盘" @click="setBuildings"><Building2 :size="15" /><span>精细沙盘</span></button>
            <button :aria-pressed="showLabels" :disabled="!!error" aria-label="显示地点名称" title="地点名称" @click="showLabels = !showLabels"><Tag :size="15" /></button>
            <button :aria-pressed="evening" :disabled="loading || !!error" :aria-label="evening ? '切换日景' : '切换暮色'" :title="evening ? '日景' : '暮色'" @click="setEvening"><Sun v-if="evening" :size="16" /><Moon v-else :size="16" /></button>
          </div>

          <div v-if="weatherInfo && mode === 'map'" class="weather-indicator" :title="`郑州新郑市实时天气：${weatherInfo.weather} · ${weatherInfo.temperature}℃ · ${weatherInfo.wind}`">
            <CloudSun :size="14" />
            <span>{{ weatherInfo.weather }} {{ weatherInfo.temperature }}℃ · 新郑</span>
          </div>
        </div>

        <div class="exploration-canvas" :aria-busy="loading || moving" :data-camera-moving="moving">
          <div ref="surface" class="exploration-render-host"></div>
          <div v-if="showLabels && !error" class="place-label-layer">
            <template v-for="label in labels" :key="label.id">
              <button v-if="label.visible" class="place-label" :class="{ selected: selected === label.id }" :style="{ left: `${label.left}px`, top: `${label.top}px` }" :aria-label="`定位${allPlaces.find(place => place.id === label.id)?.name}`" @click="locate(label.id)">
                <span class="label-dot"></span>{{ allPlaces.find(place => place.id === label.id)?.name }}
              </button>
            </template>
          </div>
          <div v-if="loading" class="exploration-fallback" role="status">{{ mode === 'map' ? '正在载入高德地图…' : '正在构建校园场景…' }}</div>
          <div v-else-if="error" class="exploration-fallback" role="status"><Layers :size="27" /><p>{{ error }}</p><button class="button" @click="initScene">重新加载</button><button v-if="mode === 'map'" class="fallback-sandbox" @click="changeMode('model')">使用校园沙盘</button></div>
          <div class="model-caption">
            <span class="model-indicator"></span>
            {{ mode === 'map'
              ? (satellite
                  ? '高德底图 · 卫星航拍影像'
                  : (is25D
                      ? (currentCampus === 'WEST' ? '高德 2.5D 矢量底图 · 西校区立体建筑' : '高德 2.5D 矢量底图 · 东校区立体建筑')
                      : (currentCampus === 'WEST' ? '高德矢量底图 · 西校区平面' : '高德矢量底图 · 东校区平面')))
              : '校园轮廓示意 · 高度未实测' }}
          </div>
          <div v-if="view === 'top' && mode === 'model'" class="north-indicator" aria-label="俯视图上方为北">↑<span>北</span></div>
          <div v-if="calibration && mode === 'map'" class="calibration-panel"><label>地图校准坐标（仅开发环境）<output>{{ calibrationCoordinate }}</output></label><div class="calibration-pixels"><input v-model.number="calibrationX" type="number" aria-label="参照点画布X" /><input v-model.number="calibrationY" type="number" aria-label="参照点画布Y" /><button @click="scene?.readCoordinate?.(calibrationX, calibrationY)">读取坐标</button></div></div>
          <div class="zoom-options" role="group" aria-label="缩放场景"><button aria-label="放大场景" :disabled="loading || !!error" @click="scene?.zoom('in')"><Plus :size="17" /></button><button aria-label="缩小场景" :disabled="loading || !!error" @click="scene?.zoom('out')"><Minus :size="17" /></button></div>
          <div v-if="tourStep !== null && !error" class="exploration-tour" aria-label="校园分步导览">
            <button aria-label="上一个导览地点" :disabled="tourStep === 0" @click="tour(tourStep - 1)"><ArrowLeft :size="16" /></button>
            <div aria-live="polite"><small>{{ currentCampus === 'WEST' ? '西校区导览' : '东校区导览' }} {{ tourStep + 1 }} / {{ activeTour.length }}</small><strong>{{ tourPlace?.name }}</strong></div>
            <button v-if="tourStep < activeTour.length - 1" aria-label="下一个导览地点" @click="tour(tourStep + 1)"><ArrowRight :size="16" /></button>
            <button v-else aria-label="完成导览" @click="closeTour"><Check :size="16" /></button>
            <button class="tour-close" aria-label="结束导览" @click="closeTour"><X :size="14" /></button>
          </div>
          <div v-if="routeActive && currentPlace && !error" class="spatial-nav-hud" aria-label="Agent 空间导航" role="status">
            <div class="hud-header">
              <span class="hud-pill"><Compass :size="13" class="spin-compass" /> 路线漫游导航</span>
              <span class="hud-cat">{{ categoryNames[currentPlace.category] }}</span>
            </div>
            <div class="hud-body">
              <div class="hud-destination">
                <span class="hud-prefix">导航目的地</span>
                <strong class="hud-name">{{ currentPlace.name }}</strong>
              </div>
              <p class="hud-desc">{{ currentPlace.description }}</p>
            </div>
            <div class="hud-footer">
              <button class="hud-action-btn primary" title="在场景中重新定位" @click="locate(currentPlace.id)">
                <LocateFixed :size="12" /><span>视角聚焦</span>
              </button>
              <button class="hud-action-btn" title="退出导航" @click="toggleRoute">
                <X :size="12" /><span>结束导航</span>
              </button>
            </div>
          </div>
        </div>
        <div class="exploration-bottom"><span class="desktop-instructions">{{ mode === 'map' ? '拖动平移 · 滚轮缩放 · 右键旋转俯仰' : '拖动旋转 · 滚轮缩放 · 右键平移' }}</span><span class="touch-instructions">{{ mode === 'map' ? '单指平移 · 双指缩放与旋转' : '单指旋转 · 双指缩放与平移' }}</span><span class="render-state" role="status">{{ moving ? '视角切换中' : currentPlace ? currentPlace.name : `自由探索${activeMeta.name}` }}</span></div>
      </section>

      <aside class="exploration-directory" aria-label="校园地点目录">
        <div class="directory-heading">
          <h2>发现校园 · {{ currentCampus === 'WEST' ? '西区' : '东区' }}</h2>
          <span>{{ activeCampusPlaces.length }} 处地点</span>
        </div>
        <label class="exploration-search"><Search :size="16" /><input v-model="search" aria-label="搜索校园地点" placeholder="搜索建筑、景观或设施" /><button v-if="search" aria-label="清空搜索" @click="search = ''"><X :size="14" /></button></label>
        <div class="place-categories" role="group" aria-label="地点分类"><button v-for="item in campusCategories" :key="item.id" :aria-pressed="category === item.id" @click="category = item.id">{{ item.label }}</button></div>
        <div class="place-results" aria-label="地点搜索结果">
          <button v-for="place in filteredPlaces" :key="place.id" class="place-directory-item" :class="{ selected: selected === place.id }" :aria-pressed="selected === place.id" @click="locate(place.id)"><component :is="placeIcon(place)" :size="17" /><span>{{ place.name }}</span><MapPin v-if="selected === place.id" :size="14" /><ArrowRight v-else :size="13" class="place-arrow" /></button>
          <div v-if="!filteredPlaces.length" class="place-empty"><Search :size="22" /><p>没有找到匹配地点</p><button @click="search = ''; category = 'all'">查看全部地点</button></div>
        </div>
        <div v-if="currentPlace" class="place-detail" aria-live="polite">
          <span class="place-category-label">{{ categoryNames[currentPlace.category] }}</span>
          <h3>{{ currentPlace.name }}</h3>
          <p>{{ currentPlace.description }}</p>
          <div class="place-feature-tags">
            <span v-for="feature in currentPlace.features" :key="feature">{{ feature }}</span>
          </div>

          <button v-if="mode === 'map'" class="route-btn" :class="{ active: routeActive }" @click="toggleRoute">
            <Compass :size="13" />
            <span>{{ routeActive ? '清除校内路线' : (currentCampus === 'WEST' ? '从西校区主大门规划步行路线' : '从正门规划步行路线') }}</span>
          </button>
          <button v-if="relatedPlace" class="related-place" @click="locate(relatedPlace.id)">同组团地点：{{ relatedPlace.name }}<ArrowRight :size="12" /></button>
          <router-link to="/portal/reservations" class="reserve-btn"><CalendarCheck :size="13" /><span>场地预约服务</span><ArrowRight :size="12" /></router-link>
          <small>{{ currentPlace.locationType === 'point' ? '符号表示图示位置，未确认独立楼体轮廓、楼层或入口。' : '建筑轮廓与功能分区为示意，高度和楼层未实测；可用场地以预约服务中的清单为准。' }}</small>
          <a class="place-source" :href="currentPlace.sourceUrl" target="_blank" rel="noopener noreferrer">{{ currentPlace.verification === 'reference-located' ? '查看高德地图定位' : '对照实景地图轮廓' }}<ArrowRight :size="12" /></a>
        </div>
        <div v-else-if="currentCampus === 'WEST'" class="west-intro-box">
          <div class="west-intro-tag">西校区概况</div>
          <h4>新村校区 · 产教工科实践重地</h4>
          <p>西校区位于新郑市新村镇107国道路东，涵盖现代化教学楼群、3号实验实训中心、图书馆分馆、多层郑工餐厅、学生生活园区及标准田径场，是大一新生基础教学与工科实践的重要基地。</p>
          <div class="west-intro-details">
            <div class="west-detail-row"><MapPin :size="12" /><span>新郑市新村镇107国道路东</span></div>
            <div class="west-detail-row"><Compass :size="12" /><span>高德基准：113.7121°E, 34.4296°N</span></div>
          </div>
        </div>
        <div v-else class="exploration-intro"><img :src="campusEvidence.layoutReferenceUrl" alt="东校区校园建筑分布图" /><p>楼名和功能分布参考校内分布图，建筑外轮廓参考留存卫星影像。H1、H2 为学生公寓图示位置点，楼体轮廓尚待对应。</p><a :href="campusEvidence.layoutReferenceUrl" target="_blank" rel="noopener noreferrer">查看校园分布图<ArrowRight :size="12" /></a></div>
      </aside>
    </div>
    <div class="exploration-footnote">
      <span><span class="footnote-mark"></span>{{ currentCampus === 'WEST' ? '西校区地标经纬度基于高德地图 GCJ-02 坐标体系实测收录，建筑高度与楼层未实测，场景不用于现场施工。' : '分布图未注明编制日期。楼层、高度和入口尚待现场资料核对，场景不用于实地导航。' }}</span>
      <a v-if="currentCampus === 'EAST'" :href="campusEvidence.layoutReferenceUrl" target="_blank" rel="noopener noreferrer">对照校园分布图<ArrowRight :size="13" /></a>
      <span v-else class="campus-footnote-tag">高德 2.5D 矢量底图支持</span>
    </div>
  </div>
</template>

<style scoped>
.explore-page { padding-top: 36px; }
.exploration-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 24px; flex-wrap: wrap; }
.heading-meta { flex: 1; min-width: 260px; }
.exploration-heading h1 { font-family: var(--site-serif); font-size: 32px; margin: 6px 0; color: #243c55; }
.exploration-heading p { font-size: 12px; color: var(--muted); margin: 0; line-height: 1.8; }
.heading-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }

/* Refined academic dual-campus selector */
.campus-selector {
  display: inline-flex;
  align-items: center;
  background: #edf3f7;
  border: 1px solid #c5d6e2;
  border-radius: 6px;
  padding: 3px;
  gap: 3px;
}
.campus-tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  font-size: 12px;
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  color: #4b6272;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  white-space: nowrap;
}
.campus-tab-btn:hover:not(.active) {
  background: #f7fafc;
  color: #1e3a53;
}
.campus-tab-btn.active {
  background: #ffffff;
  color: #1c4b73;
  border-color: #b5cbdb;
  box-shadow: 0 1px 4px rgba(27, 57, 82, 0.08);
  font-weight: 500;
}
.campus-badge {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: #e2ecf2;
  color: #3e6079;
}
.campus-tab-btn.active .campus-badge {
  background: #23547d;
  color: #ffffff;
}
.campus-title {
  font-family: inherit;
}

.explore-tour-start { display: flex; align-items: center; gap: 9px; padding: 8px 14px; font-size: 12px; border: 1px solid #b5c7d5; border-radius: 4px; color: var(--brand); background: #ffffffb0; white-space: nowrap; }
.explore-tour-start:hover:not(:disabled) { background: #e8eef3; }
.exploration-workspace { display: grid; grid-template-columns: minmax(0,1fr) 282px; gap: 18px; align-items: stretch; }
.exploration-viewport { min-width: 0; background: #e8eef0; border: 1px solid #ccd8df; display: flex; flex-direction: column; overflow: hidden; border-radius: 6px; }
.exploration-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 7px; padding: 12px 14px 10px; background: #e8eef0; }
.weather-indicator { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: #436480; background: #eaf1f7; padding: 4px 9px; border-radius: 4px; border: 1px solid #c8d9e6; }
.evening .weather-indicator { background: #354b64; color: #bbd3e8; border-color: #61778a; }
.route-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; width: 100%; padding: 6px 10px; margin-bottom: 8px; background: #eff5fa; color: #2e597c; border: 1px solid #bfd3e3; border-radius: 3px; font-size: 11px; cursor: pointer; transition: all 0.15s ease; box-sizing: border-box; }
.route-btn:hover { background: #e0ecf5; color: #1e4566; }
.route-btn.active { background: #2a618d; color: #ffffff; border-color: #2a618d; }
.mode-options { margin-right: auto; }
.view-options, .display-options { display: flex; align-items: center; padding: 3px; border: 1px solid #c9d4da; border-radius: 5px; background: #f5f8f8c7; gap: 3px; }
.exploration-toolbar button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: 30px; padding: 0 11px; color: #607484; font-size: 11px; border: 0; background: transparent; cursor: pointer; }
.exploration-toolbar button[aria-pressed="true"] { background: #fff; color: var(--brand); box-shadow: 0 1px 5px #2e455014; }
.display-options button { padding: 0 9px; }
.exploration-canvas { position: relative; height: 535px; min-width: 0; touch-action: none; isolation: isolate; }
.exploration-render-host, .exploration-render-host :deep(.amap-host), .exploration-render-host :deep(.sandbox-host) { position: absolute; inset: 0; }
.exploration-canvas :deep(.sandbox-host canvas) { display: block; width: 100%; height: 100%; cursor: grab; }
.exploration-canvas :deep(.sandbox-host canvas:active) { cursor: grabbing; }
.map-mode .model-caption { background: #ffffffeb; padding: 6px 8px; border-radius: 3px; color: #405563; z-index: 2; }
.calibration-panel { position: absolute; left: 12px; bottom: 50px; z-index: 3; background: #fff; padding: 8px; font-size: 11px; }
.calibration-panel output { display: block; user-select: all; margin-top: 5px; }
.calibration-pixels { display: flex; gap: 5px; margin-top: 5px; }
.calibration-pixels input { width: 55px; padding: 3px; }
.calibration-pixels button { font-size: 10px; border: 1px solid #ccd8df; background: #eef3f6; }
.fallback-sandbox { margin-top: 14px; border: 0; background: transparent; color: var(--brand); font-size: 12px; }
.place-label-layer { position: absolute; inset: 0; z-index: 2; pointer-events: none; overflow: hidden; }
.place-label { position: absolute; transform: translate(-50%,-100%); display: inline-flex; align-items: center; gap: 5px; border: 1px solid #ffffffb3; border-radius: 3px; color: #425869; background: #ffffffe0; padding: 5px 7px; box-shadow: 0 2px 8px #283c4a12; font-size: 10px; white-space: nowrap; pointer-events: auto; }
.place-label:hover, .place-label.selected { color: #fff; background: #315e8b; border-color: #315e8b; }
.label-dot { width: 4px; height: 4px; background: #6c8fa1; border-radius: 50%; }
.place-label.selected .label-dot { background: #b9d9ef; }
.model-caption { position: absolute; top: 14px; left: 18px; display: flex; align-items: center; gap: 6px; color: #758a94; font-size: 10px; pointer-events: none; }
.model-indicator { width: 5px; height: 5px; border-radius: 50%; background: #7b9c94; }
.north-indicator { position: absolute; top: 18px; right: 22px; display: grid; justify-items: center; gap: 3px; color: #5f7781; font-size: 20px; pointer-events: none; }
.north-indicator span { font-size: 10px; }
.place-source { display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; font-size: 10px; color: var(--brand); }
.zoom-options { position: absolute; right: 14px; bottom: 15px; border: 1px solid #c4d0d7; border-radius: 5px; overflow: hidden; background: #ffffffd9; }
.zoom-options button { display: flex; align-items: center; justify-content: center; padding: 0; width: 32px; height: 32px; border: 0; background: transparent; color: #47677e; cursor: pointer; }
.zoom-options button + button { border-top: 1px solid #dce4e9; }
.zoom-options button:hover:not(:disabled) { background: #e6edf3; }
.exploration-bottom { display: flex; justify-content: space-between; gap: 12px; font-size: 10px; padding: 12px 17px; color: #758894; border-top: 1px solid #d7e1e4; margin-top: auto; }
.touch-instructions { display: none; }
.render-state { color: #4f6c7f; text-align: right; }
.exploration-directory { height: 632px; display: flex; flex-direction: column; min-width: 0; padding: 20px 17px 16px; border: 1px solid var(--line); background: #fffdfb; border-radius: 6px; }
.directory-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-bottom: 18px; }
.directory-heading h2 { font-size: 16px; font-family: var(--site-serif); margin: 0; color: #243c55; }
.directory-heading > span { font-size: 10px; color: #87939d; }
.exploration-search { display: flex; flex-direction: row; align-items: center; gap: 8px; padding: 8px 9px; border: 1px solid #dfe5e9; border-radius: 4px; background: #f6f8f9; color: #8494a2; }
.exploration-search input { min-width: 0; border: 0; background: transparent; padding: 0; font-size: 11px; line-height: 1.8; border-radius: 0; }
.exploration-search input:focus-visible { outline-offset: 7px; }
.exploration-search button { display: flex; padding: 0; border: 0; background: none; color: #738895; cursor: pointer; }
.place-categories { display: flex; align-items: center; gap: 4px; margin: 13px 0 12px; }
.place-categories button { flex: 1; min-width: 0; padding: 7px 0; border: 0; font-size: 11px; background: transparent; color: #80909b; border-radius: 3px; cursor: pointer; }
.place-categories button[aria-pressed="true"] { background: #eaf0f4; color: var(--brand); }
.place-results { flex: 1; overflow: auto; min-height: 160px; scrollbar-width: thin; scrollbar-color: #c6d2da transparent; margin-inline: -5px; padding-inline: 5px; }
.place-directory-item { display: flex; align-items: center; gap: 11px; width: 100%; padding: 12px 8px; border: 0; background: transparent; font-size: 11px; text-align: left; color: #647e8c; cursor: pointer; }
.place-directory-item span { flex: 1; color: #405563; }
.place-directory-item.selected { background: #eaf0f5; color: #315d88; }
.place-directory-item.selected span { color: #244b78; }
.place-directory-item:hover { background: #f0f4f6; }
.place-arrow { color: #a9b6be; }
.place-detail, .exploration-intro, .west-intro-box { border-top: 1px solid #e5e9ec; margin-top: 14px; padding-top: 17px; }
.place-category-label { font-size: 9px; letter-spacing: 1px; color: #8596a1; }
.place-detail h3 { font-size: 17px; margin: 5px 0 8px; font-family: var(--site-serif); color: #2f4e68; }
.place-detail p { font-size: 11px; color: #72828d; line-height: 1.95; margin-bottom: 12px; }
.place-feature-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 10px; }
.place-feature-tags span { padding: 3px 6px; background: #f1f4f5; color: #758a96; font-size: 9px; border-radius: 2px; }
.related-place { display: flex; align-items: center; gap: 6px; width: 100%; border: 0; border-radius: 3px; padding: 8px; margin: 6px 0 12px; background: #edf2f5; color: #315d7e; text-align: left; font-size: 11px; cursor: pointer; }
.reserve-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; width: 100%; padding: 6px 10px; margin-bottom: 12px; background: var(--brand); color: #ffffff; border-radius: 3px; font-size: 11px; text-decoration: none; transition: background-color 0.15s ease; box-sizing: border-box; }
.reserve-btn:hover { background: #1c3e60; color: #ffffff; }
.place-detail small { display: block; font-size: 9px; color: #97a2a9; line-height: 1.8; }
.exploration-intro img { display: block; width: 100%; height: 120px; object-fit: contain; border-radius: 3px; background: #f1f4f2; }
.exploration-intro p { font-size: 10px; line-height: 1.8; color: #81909b; margin: 10px 0 5px; }
.exploration-intro a { display: flex; align-items: center; gap: 6px; color: #58748d; font-size: 10px; }

/* West campus intro card in directory */
.west-intro-box { display: flex; flex-direction: column; gap: 8px; }
.west-intro-tag { display: inline-block; align-self: flex-start; font-size: 9px; padding: 2px 6px; border-radius: 3px; background: #e3edf4; color: #23547d; letter-spacing: 0.5px; font-weight: 500; }
.west-intro-box h4 { font-family: var(--site-serif); font-size: 14px; margin: 0; color: #22425d; }
.west-intro-box p { font-size: 10.5px; color: #6f808d; line-height: 1.8; margin: 0; }
.west-intro-details { display: flex; flex-direction: column; gap: 5px; margin-top: 4px; padding-top: 8px; border-top: 1px dashed #dbe4ea; }
.west-detail-row { display: flex; align-items: center; gap: 6px; font-size: 9.5px; color: #6b8294; }

.place-empty { padding: 22px 8px; text-align: center; color: #96a4ac; }
.place-empty p { font-size: 12px; margin: 10px 0; }
.place-empty button { background: none; border: 0; font-size: 11px; color: var(--brand); padding: 8px; cursor: pointer; }
.exploration-footnote { display: flex; align-items: start; justify-content: space-between; gap: 25px; margin-top: 18px; color: #89979f; font-size: 10px; line-height: 1.9; }
.exploration-footnote > span, .exploration-footnote a { display: flex; align-items: center; gap: 7px; }
.exploration-footnote a { color: #667f92; white-space: nowrap; }
.campus-footnote-tag { font-size: 9px; color: #285d88; background: #ebf3f8; padding: 2px 7px; border-radius: 3px; border: 1px solid #c8dae7; }
.footnote-mark { width: 4px; height: 4px; background: #8ba3b1; flex-shrink: 0; }
.exploration-tour { position: absolute; left: 50%; bottom: 18px; transform: translateX(-50%); display: flex; align-items: center; gap: 15px; padding: 10px 12px; background: #fffffff0; box-shadow: 0 4px 22px #183b5720; border: 1px solid #d3dfe7; border-radius: 5px; min-width: 230px; z-index: 4; }
.exploration-tour button { background: #f0f4f7; border: 0; display: flex; align-items: center; justify-content: center; width: 29px; height: 29px; padding: 0; color: #365e80; flex-shrink: 0; cursor: pointer; }
.exploration-tour div { flex: 1; white-space: nowrap; }
.exploration-tour small { display: block; color: #8a9aa6; font-size: 9px; margin-bottom: 4px; }
.exploration-tour strong { color: #345574; font-size: 12px; font-weight: 500; }
.exploration-tour .tour-close { position: absolute; right: -6px; top: -7px; width: 20px; height: 20px; background: #fff; border: 1px solid #dbe3e9; border-radius: 50%; }
.spatial-nav-hud { position: absolute; top: 14px; right: 14px; max-width: 290px; background: rgba(255, 255, 255, 0.95); backdrop-filter: blur(8px); -webkit-backdrop-filter: blur(8px); border: 1px solid #b6ccdb; box-shadow: 0 6px 24px rgba(24, 59, 87, 0.16); border-radius: 6px; padding: 11px 13px; z-index: 4; animation: hudSlide 0.25s ease-out; }
.evening .spatial-nav-hud { background: rgba(28, 45, 64, 0.94); border-color: #4a6884; box-shadow: 0 6px 24px rgba(0, 0, 0, 0.35); color: #e2edf6; }
@keyframes hudSlide { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: translateY(0); } }
.hud-header { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 7px; }
.hud-pill { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; font-weight: 600; color: #1c527e; background: #e3eff7; padding: 2px 7px; border-radius: 12px; }
.evening .hud-pill { background: #2a4663; color: #9fd2f6; }
.spin-compass { animation: pulseCompass 2.4s ease-in-out infinite; }
@keyframes pulseCompass { 0%, 100% { transform: rotate(0deg); } 50% { transform: rotate(45deg); } }
.hud-cat { font-size: 10px; color: #768a98; }
.evening .hud-cat { color: #8ea4b5; }
.hud-destination { display: flex; align-items: baseline; gap: 6px; margin-bottom: 4px; }
.hud-prefix { font-size: 10px; color: #8799a5; }
.hud-name { font-size: 14px; color: #1b3851; font-family: var(--site-serif); }
.evening .hud-name { color: #f0f6fa; }
.hud-desc { font-size: 10px; color: #607280; line-height: 1.6; margin: 0 0 9px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.evening .hud-desc { color: #a4b7c6; }
.hud-footer { display: flex; gap: 7px; }
.hud-action-btn { display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 4px 8px; font-size: 10px; border: 1px solid #c2d4e2; background: #f3f7fa; color: #325878; border-radius: 4px; cursor: pointer; transition: all 0.15s ease; flex: 1; }
.hud-action-btn:hover { background: #e5eff6; color: #1c3e5d; }
.hud-action-btn.primary { background: #265f8c; color: #ffffff; border-color: #265f8c; }
.hud-action-btn.primary:hover { background: #1c4b70; }
.evening .hud-action-btn { background: #283e54; border-color: #4b6680; color: #c4d7e6; }
.evening .hud-action-btn.primary { background: #3576a8; color: #ffffff; border-color: #3576a8; }
.exploration-fallback { position: absolute; inset: 0; z-index: 5; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 35px; text-align: center; font-size: 13px; color: #778d9c; background: #e8eef0; }
.exploration-fallback p { max-width: 320px; line-height: 2; margin-top: 15px; }
.evening, .evening .exploration-toolbar { background: #263b55; }
.evening .exploration-bottom { border-color: #ffffff12; color: #aabed0; }
.evening .model-caption, .evening .render-state { color: #bbccda; }
.evening .view-options, .evening .display-options { background: #354b64; border-color: #61778a; }
.evening .exploration-toolbar button { color: #c0cfdb; }
.evening .exploration-toolbar button[aria-pressed="true"] { background: #e0e9f0; color: #315677; }

@media (max-width: 1100px) {
  .exploration-workspace { grid-template-columns: minmax(0,1fr) 258px; gap: 14px; }
  .exploration-directory { padding-inline: 14px; }
  .exploration-canvas { height: 510px; }
  .exploration-directory { height: 607px; }
}
@media (max-width: 850px) {
  .exploration-workspace { grid-template-columns: 1fr; }
  .exploration-canvas { height: 490px; }
  .exploration-directory { height: auto; display: grid; grid-template-columns: 1fr 1fr; gap: 0 24px; }
  .directory-heading, .exploration-search, .place-categories, .place-results { grid-column: 1; }
  .place-results { max-height: 260px; }
  .place-detail, .exploration-intro, .west-intro-box { grid-column: 2; grid-row: 1 / span 4; align-self: start; margin-top: 0; border: 0; padding-top: 2px; }
  .exploration-intro img { height: 140px; }
}
@media (max-width: 600px) {
  .explore-page { padding-top: 24px; }
  .exploration-heading { gap: 14px; align-items: stretch; margin-bottom: 20px; }
  .exploration-heading h1 { font-size: 26px; }
  .exploration-heading p { font-size: 11px; }
  .heading-actions { justify-content: space-between; }
  .campus-selector { width: 100%; justify-content: space-between; }
  .campus-tab-btn { flex: 1; justify-content: center; }
  .explore-tour-start { font-size: 11px; padding: 7px 10px; gap: 6px; }
  .explore-tour-start > svg:last-child { display: none; }
  .exploration-canvas { height: 340px; }
  .exploration-toolbar { padding: 10px 10px 0; }
  .exploration-toolbar button { padding-inline: 9px; height: 29px; }
  .display-options button { padding-inline: 7px; }
  .model-caption { left: 13px; top: 13px; font-size: 9px; }
  .place-label { font-size: 9px; padding: 4px 6px; }
  .exploration-bottom { font-size: 9px; gap: 8px; padding: 10px 12px; }
  .desktop-instructions { display: none; }
  .touch-instructions { display: inline; }
  .zoom-options { right: 10px; bottom: 12px; }
  .zoom-options button { width: 29px; height: 29px; }
  .spatial-nav-hud { top: auto; bottom: 58px; right: 10px; left: 10px; max-width: none; }
  .exploration-tour { min-width: 207px; gap: 10px; bottom: 14px; left: calc(50% - 14px); }
  .exploration-directory { display: flex; gap: 0; padding: 19px 16px; }
  .place-results { min-height: 0; max-height: 238px; flex: auto; }
  .place-detail, .exploration-intro, .west-intro-box { border-top: 1px solid #e5e9ec; margin-top: 15px; padding-top: 16px; align-self: stretch; }
  .exploration-intro img { height: 105px; }
  .exploration-footnote { flex-direction: column; gap: 7px; font-size: 9px; }
  .exploration-footnote > span { align-items: baseline; }
}
@media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto; } }
</style>
