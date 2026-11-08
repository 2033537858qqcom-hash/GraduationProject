import { computed, nextTick, onMounted, onUnmounted, ref, watch, useTemplateRef, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { BedDouble, Building2, Compass, Dumbbell, MapPin, Stethoscope, Trees, Utensils } from '@lucide/vue'
import { campusPlaces, campusTour } from '../campus/manifest'
import type { CampusCategory, CampusPlace } from '../campus/manifest'
import { createCampusScene } from '../campus/createCampusScene'
import type { CampusView, PlaceLabel } from '../campus/createCampusScene'
import {
  westCampusMeta,
  eastCampusMeta,
  westCampusPlaces,
  westCampusTour,
  westMapPlaces,
  westMapTour,
} from '../campus/westCampusData'
import { createAMapCampusScene } from '../campus/createAMapCampusScene'
import { loadAMap } from '../campus/amapSdk'
import { campusPlaceId, campusReservationLink } from '../lib/campusServiceNavigation'
import { publicReducedMotion } from '../lib/publicMotion'

/** State and effects for CampusExplore; visual markup lives in the page and its components. */
export function useCampusExplorer() {
  const route = useRoute()
  const router = useRouter()
  const surface = useTemplateRef<HTMLDivElement>('surface')
  const mapKey = import.meta.env.VITE_AMAP_KEY ?? ''
  const mapConfigured = /^[a-f\d]{32}$/i.test(mapKey)
  const requestedNavigation =
    typeof route.query.navigate === 'string'
      ? route.query.navigate
      : typeof route.query.focus === 'string'
        ? route.query.focus
        : ''
  const referenceOnlyNavigation =
    westCampusPlaces.some(p => p.id === requestedNavigation) && !westMapPlaces.some(p => p.id === requestedNavigation)
  const mode = ref<'map' | 'model'>(
    mapConfigured && route.query.view !== 'model' && !referenceOnlyNavigation ? 'map' : 'model',
  )

  // Dual campus state
  const initialQueryCampus = route.query.campus === 'WEST' || requestedNavigation.startsWith('west-') ? 'WEST' : 'EAST'
  const currentCampus = ref<'EAST' | 'WEST'>(initialQueryCampus)
  const activeMeta = computed(() => (currentCampus.value === 'WEST' ? westCampusMeta : eastCampusMeta))
  const activeWestPlaces = computed(() => (mode.value === 'model' ? westCampusPlaces : westMapPlaces))
  const activeCampusPlaces = computed(() => (currentCampus.value === 'WEST' ? activeWestPlaces.value : campusPlaces))
  const activeTour = computed(() =>
    currentCampus.value === 'WEST' ? (mode.value === 'model' ? westCampusTour : westMapTour) : campusTour,
  )
  const allPlaces = computed(() => [...campusPlaces, ...activeWestPlaces.value])

  // 2.5D Vector default settings (satellite defaults to false as preferred by user)
  const is25D = ref(true)
  const mapTheme = ref<'normal' | 'whitesmoke'>('normal')
  const satellite = ref(false)

  const modelReady = ref(false),
    buildings = ref(false)
  const calibration = import.meta.env.DEV && new URLSearchParams(window.location.search).has('calibrate')
  const calibrationCoordinate = ref('在地图上点击实物参照点')
  const calibrationX = ref(0),
    calibrationY = ref(0)
  const surveyQuery = ref('郑州工业应用技术学院')
  const surveyOutput = ref(''),
    surveyLoading = ref(false)
  async function surveyPlaces() {
    if (!calibration || surveyLoading.value) return
    surveyLoading.value = true
    try {
      const sdk = await loadAMap(mapKey)
      if (!sdk.PlaceSearch) await new Promise<void>(resolve => sdk.plugin?.(['AMap.PlaceSearch'], resolve))
      if (!sdk.PlaceSearch) throw new Error('地点检索插件未加载')
      const search = new sdk.PlaceSearch({ city: '新郑', citylimit: true, pageSize: 50, extensions: 'base' })
      const response = await new Promise<{ status: string; result: import('../campus/amapSdk').AMapSearchResult }>(
        resolve => {
          const done = (status: string, result: import('../campus/amapSdk').AMapSearchResult) =>
            resolve({ status, result })
          if (/^B[A-Z0-9]+$/.test(surveyQuery.value)) search.getDetails(surveyQuery.value, done)
          else search.searchNearBy(surveyQuery.value, activeMeta.value.center, 1000, done)
        },
      )
      surveyOutput.value = JSON.stringify(
        {
          status: response.status,
          info: response.result.info,
          count: response.result.poiList?.count,
          pois: response.result.poiList?.pois.map(poi => ({
            id: poi.id,
            name: poi.name,
            address: poi.address,
            type: poi.type,
            lnglat: [poi.location.getLng(), poi.location.getLat()],
          })),
        },
        null,
        2,
      )
    } catch (reason) {
      surveyOutput.value = reason instanceof Error ? reason.message : '检索失败'
    } finally {
      surveyLoading.value = false
    }
  }
  const error = ref(''),
    loading = ref(true),
    search = ref('')
  const category = ref<CampusCategory | 'all'>('all')
  const selected = ref<string | null>(campusPlaceId(requestedNavigation))
  const view = ref<CampusView>('overview'),
    moving = ref(false),
    showLabels = ref(true),
    evening = ref(false)
  const labels = ref<PlaceLabel[]>([])
  const tourStep = ref<number | null>(null)
  const weatherInfo = ref<{ weather: string; temperature: number; wind: string } | null>(null)
  const routeActive = ref(false)

  type MapScene = Awaited<ReturnType<typeof createAMapCampusScene>>
  const scene = shallowRef<
    | (ReturnType<typeof createCampusScene> &
        Partial<
          Pick<
            MapScene,
            | 'setSatellite'
            | 'setBuildings'
            | 'readCoordinate'
            | 'planRoute'
            | 'clearRoute'
            | 'getWeather'
            | 'switchCampus'
            | 'getCampus'
            | 'set25D'
            | 'setTheme'
          >
        >)
    | undefined
  >()
  let generation = 0
  let sceneAbort: AbortController | undefined

  const currentPlace = computed(() => allPlaces.value.find(place => place.id === selected.value))
  const placeDetail = useTemplateRef<HTMLElement>('placeDetail')
  const reservationTarget = computed(() =>
    currentPlace.value ? campusReservationLink(currentPlace.value, currentCampus.value, mode.value) : null,
  )
  const filteredPlaces = computed(() =>
    activeCampusPlaces.value.filter(
      place =>
        (category.value === 'all' || place.category === category.value) &&
        `${place.name}${place.description}${place.features.join('')}`.includes(search.value.trim()),
    ),
  )

  const categoryIcons = {
    learning: Building2,
    housing: BedDouble,
    services: MapPin,
    sports: Dumbbell,
    landscape: Trees,
  }
  const categoryNames = {
    learning: '校园楼宇',
    housing: '学生公寓',
    services: '校园服务',
    sports: '文体场馆',
    landscape: '景观与入口',
  }
  const relatedPlace = computed(() => allPlaces.value.find(place => place.id === currentPlace.value?.relatedPlaceId))

  function placeIcon(place: CampusPlace) {
    if (place.id.startsWith('west-road-')) return Compass
    if (
      [
        'east-jinxiu-dining',
        'east-canteen-wenhua',
        'west-canteen',
        'west-steel-canteen',
        'west-siyuan-canteen',
        'west-food-street',
      ].includes(place.id)
    )
      return Utensils
    if (place.id === 'east-clinic') return Stethoscope
    return categoryIcons[place.category]
  }

  const tourPlace = computed(() =>
    tourStep.value === null ? undefined : allPlaces.value.find(place => place.id === activeTour.value[tourStep.value!]),
  )

  function locate(id: string, fromTour = false, syncQuery = true) {
    if (!fromTour) tourStep.value = null
    selected.value = id
    scene.value?.select(id)
    if (routeActive.value) {
      scene.value?.planRoute?.(id)
    }
    if (syncQuery && (route.query.focus !== id || route.query.navigate))
      void router.replace({
        query: { ...route.query, campus: currentCampus.value, view: mode.value, focus: id, navigate: undefined },
      })
  }
  async function choosePlace(id: string) {
    locate(id)
    await nextTick()
    if (window.innerWidth <= 600) {
      placeDetail.value?.scrollIntoView({ behavior: publicReducedMotion.value ? 'auto' : 'smooth', block: 'start' })
      placeDetail.value?.focus({ preventScroll: true })
    }
  }

  function overview() {
    tourStep.value = null
    selected.value = null
    scene.value?.select(null)
    void router.replace({ query: { ...route.query, focus: undefined, navigate: undefined } })
    if (routeActive.value) {
      routeActive.value = false
      scene.value?.clearRoute?.()
    }
  }

  function switchCampus(campus: 'EAST' | 'WEST', preserveNavigation = false) {
    if (currentCampus.value === campus) return
    currentCampus.value = campus
    void router.replace({
      query: {
        ...route.query,
        campus,
        navigate: preserveNavigation ? route.query.navigate : undefined,
        focus: preserveNavigation ? route.query.focus : undefined,
      },
    })
    selected.value = null
    tourStep.value = null
    category.value = 'all'
    search.value = ''
    if (routeActive.value) {
      routeActive.value = false
      scene.value?.clearRoute?.()
    }
    if (mode.value === 'model') {
      void initScene()
      return
    }
    scene.value?.switchCampus?.(campus)
  }

  function toggleRoute() {
    if (!selected.value) return
    routeActive.value = !routeActive.value
    if (routeActive.value) {
      scene.value?.planRoute?.(selected.value)
    } else {
      scene.value?.clearRoute?.()
    }
  }

  function applyNavigation(navId: string | null, walking = true) {
    if (!navId) return
    const referencePlace = westCampusPlaces.some(p => p.id === navId),
      mapPlace = westMapPlaces.some(p => p.id === navId)
    if (mode.value === 'map' && referencePlace && !mapPlace) {
      changeMode('model', true)
      return
    }
    if (mode.value === 'model' && mapPlace && !referencePlace && mapConfigured) {
      changeMode('map', true)
      return
    }
    const place = allPlaces.value.find(p => p.id === navId)
    if (place) {
      if (place.campusId && place.campusId !== currentCampus.value) {
        switchCampus(place.campusId, true)
        if (mode.value === 'model') {
          selected.value = place.id
          return
        }
      }
      if (!walking && routeActive.value) {
        scene.value?.clearRoute?.()
        routeActive.value = false
      }
      locate(place.id, false, false)
      routeActive.value = walking && mode.value === 'map'
      if (routeActive.value) scene.value?.planRoute?.(place.id)
    }
  }

  function toggle25D(enabled: boolean) {
    is25D.value = enabled
    scene.value?.set25D?.(enabled)
  }

  function changeTheme(theme: 'normal' | 'whitesmoke') {
    mapTheme.value = theme
    scene.value?.setTheme?.(theme)
  }

  function setEvening() {
    evening.value = !evening.value
    scene.value?.setEvening(evening.value)
  }
  function setSatellite() {
    satellite.value = !satellite.value
    scene.value?.setSatellite?.(satellite.value)
  }
  function setBuildings() {
    buildings.value = !buildings.value
    scene.value?.setBuildings?.(buildings.value)
  }
  function changeMode(next: 'map' | 'model', preserveNavigation = false) {
    if (next !== mode.value) {
      routeActive.value = false
      selected.value = null
      tourStep.value = null
      search.value = ''
      mode.value = next
      void router.replace({
        query: {
          ...route.query,
          campus: currentCampus.value,
          view: next === 'model' ? 'model' : undefined,
          navigate: preserveNavigation ? route.query.navigate : undefined,
          focus: preserveNavigation ? route.query.focus : undefined,
        },
      })
      void initScene()
    }
  }
  function tour(step: number) {
    tourStep.value = Math.max(0, Math.min(activeTour.value.length - 1, step))
    locate(activeTour.value[tourStep.value]!, true)
  }
  function closeTour() {
    tourStep.value = null
  }

  async function initScene() {
    const run = ++generation
    sceneAbort?.abort()
    sceneAbort = new AbortController()
    modelReady.value = false
    buildings.value = false
    scene.value?.dispose()
    scene.value = undefined
    labels.value = []
    error.value = ''
    loading.value = true
    if (!surface.value) return
    // Each renderer owns an empty host, never Vue's labels or controls.
    const host = document.createElement('div')
    host.className = mode.value === 'map' ? 'amap-host' : 'sandbox-host'
    surface.value.replaceChildren(host)
    try {
      const callbacks = {
        select: id => locate(id),
        labels: value => {
          labels.value = value
        },
        view: value => {
          view.value = value
        },
        moving: value => {
          moving.value = value
        },
        unavailable: message => {
          error.value = message
          labels.value = []
        },
      } satisfies import('../campus/createCampusScene').SceneCallbacks
      const created =
        mode.value === 'map'
          ? await createAMapCampusScene(host, callbacks, {
              key: mapKey,
              initialCampus: currentCampus.value,
              signal: sceneAbort.signal,
              onCalibration: calibration
                ? coordinate => {
                    calibrationCoordinate.value = coordinate
                  }
                : undefined,
              onModelStatus: ready => {
                if (run === generation) modelReady.value = ready
              },
            })
          : createCampusScene(host, callbacks, { campusId: currentCampus.value })
      if (run !== generation) {
        created.dispose()
        host.remove()
        return
      }
      scene.value = created
      scene.value.setEvening(evening.value)
      scene.value.set25D?.(is25D.value)
      scene.value.setTheme?.(mapTheme.value)
      scene.value.setSatellite?.(satellite.value)
      const initialNav = typeof route.query.navigate === 'string' ? route.query.navigate : null
      if (initialNav) {
        applyNavigation(initialNav)
      } else if (typeof route.query.focus === 'string') {
        applyNavigation(route.query.focus, false)
      } else if (selected.value) {
        scene.value.select(selected.value)
        if (routeActive.value) scene.value.planRoute?.(selected.value)
      }
      if (mode.value === 'map') {
        void scene.value.getWeather?.().then(w => {
          if (run === generation) weatherInfo.value = w
        })
      }
    } catch (reason) {
      if (run !== generation) return
      host.remove()
      error.value =
        mode.value === 'map' && reason instanceof Error
          ? reason.message
          : '此设备暂时无法显示三维场景，仍可通过地点目录查看介绍。'
    } finally {
      if (run === generation) loading.value = false
    }
  }

  watch(
    () => route.query.navigate,
    newNav => {
      if (typeof newNav === 'string') {
        applyNavigation(newNav)
      }
    },
  )
  watch(
    () => route.query.focus,
    focus => {
      if (typeof focus === 'string' && focus !== selected.value) applyNavigation(focus, false)
    },
  )

  watch(
    () => route.query.campus,
    newCampus => {
      if (newCampus === 'WEST' || newCampus === 'EAST') {
        switchCampus(newCampus, true)
      }
    },
  )
  watch(
    () => route.query.view,
    next => {
      const target = mapConfigured && next !== 'model' ? 'map' : 'model'
      if (target !== mode.value) changeMode(target, true)
    },
  )

  onMounted(initScene)
  onUnmounted(() => {
    generation++
    sceneAbort?.abort()
    scene.value?.dispose()
  })

  return {
    activeCampusPlaces,
    activeMeta,
    activeTour,
    allPlaces,
    buildings,
    calibration,
    calibrationCoordinate,
    calibrationX,
    calibrationY,
    category,
    categoryNames,
    changeMode,
    changeTheme,
    choosePlace,
    closeTour,
    currentCampus,
    currentPlace,
    error,
    evening,
    filteredPlaces,
    initScene,
    is25D,
    labels,
    loading,
    locate,
    mapConfigured,
    mapTheme,
    mode,
    modelReady,
    moving,
    overview,
    placeIcon,
    relatedPlace,
    reservationTarget,
    routeActive,
    satellite,
    scene,
    search,
    selected,
    setBuildings,
    setEvening,
    setSatellite,
    showLabels,
    surveyLoading,
    surveyOutput,
    surveyPlaces,
    surveyQuery,
    switchCampus,
    toggle25D,
    toggleRoute,
    tour,
    tourPlace,
    tourStep,
    view,
    weatherInfo,
  }
}
