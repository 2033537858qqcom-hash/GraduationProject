<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Boxes,
  Building2,
  CalendarCheck,
  Check,
  CloudSun,
  Compass,
  Layers,
  LocateFixed,
  MapPin,
  Minus,
  Moon,
  Plus,
  Search,
  Sun,
  Tag,
  X,
} from '@lucide/vue';
import { campusCategories } from '../campus/manifest';
import { campusEvidence } from '../campus/eastCampusLayout';
import { westUnlocatedBuildings } from '../campus/westCampusData';
import { westPlanEvidence } from '../campus/westCampusPlan';
import { useCampusExplorer } from '../composables/useCampusExplorer';

const {
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
} = useCampusExplorer();
</script>

<template>
  <div class="explore-page page-width campus-explore">
    <div class="explore-context-bar">
      <RouterLink to="/?chapter=life">
        <ArrowLeft :size="13" />
        返回校园生活
      </RouterLink>
      <span>SPACE / LEARNING / EVERYDAY</span>
    </div>
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
          <Compass :size="16" />
          浏览地点
          <ArrowRight :size="15" />
        </button>
      </div>
    </div>

    <div class="exploration-workspace">
      <section class="exploration-viewport" :class="{ evening, 'map-mode': mode === 'map' }" aria-label="三维校园探索">
        <div class="exploration-toolbar">
          <div v-if="mapConfigured" class="view-options mode-options" role="group" aria-label="探索方式">
            <button :aria-pressed="mode === 'map'" @click="changeMode('map')">2.5D高德地图</button>
            <button :aria-pressed="mode === 'model'" title="切换当前校区的三维沙盘" @click="changeMode('model')">
              独立沙盘
            </button>
          </div>

          <!-- 2.5D vs 2D switch -->
          <div v-if="mode === 'map'" class="view-options" role="group" aria-label="视角呈现">
            <button :aria-pressed="is25D" title="2.5D 轴测鸟瞰视角（立体建筑）" @click="toggle25D(true)">
              <Boxes :size="15" />
              <span>2.5D 鸟瞰</span>
            </button>
            <button :aria-pressed="!is25D" title="2D 平面俯视视角" @click="toggle25D(false)">
              <Layers :size="15" />
              <span>2D 俯视</span>
            </button>
          </div>
          <div v-else class="view-options" role="group" aria-label="场景视角">
            <button :aria-pressed="view === 'overview'" :disabled="loading || !!error" @click="overview">
              <LocateFixed :size="15" />
              <span>总览</span>
            </button>
            <button :aria-pressed="view === 'top'" :disabled="loading || !!error" @click="scene?.top()">
              <Layers :size="15" />
              <span>俯视</span>
            </button>
          </div>

          <!-- Map Style Options -->
          <div class="display-options" role="group" aria-label="场景显示">
            <template v-if="mode === 'map'">
              <button
                :aria-pressed="!satellite && mapTheme === 'normal'"
                title="标准彩色矢量地图（显示立体建筑与路网）"
                @click="
                  satellite = false;
                  scene?.setSatellite?.(false);
                  changeTheme('normal');
                "
              >
                标准矢量
              </button>
              <button
                :aria-pressed="!satellite && mapTheme === 'whitesmoke'"
                title="雅致素白轻量地图风格"
                @click="
                  satellite = false;
                  scene?.setSatellite?.(false);
                  changeTheme('whitesmoke');
                "
              >
                雅致素白
              </button>
              <button :aria-pressed="satellite" title="切换卫星航拍影像" @click="setSatellite">卫星影像</button>
            </template>

            <button
              v-if="mode === 'map' && modelReady"
              :disabled="loading || !!error"
              :aria-pressed="buildings"
              aria-label="显示立体建筑沙盘"
              title="立体建筑沙盘"
              @click="setBuildings"
            >
              <Building2 :size="15" />
              <span>建筑沙盘</span>
            </button>
            <button
              :aria-pressed="showLabels"
              :disabled="!!error"
              aria-label="显示地点名称"
              title="地点名称"
              @click="showLabels = !showLabels"
            >
              <Tag :size="15" />
            </button>
            <button
              :aria-pressed="evening"
              :disabled="loading || !!error"
              :aria-label="evening ? '切换日景' : '切换暮色'"
              :title="evening ? '日景' : '暮色'"
              @click="setEvening"
            >
              <Sun v-if="evening" :size="16" />
              <Moon v-else :size="16" />
            </button>
          </div>

          <div
            v-if="weatherInfo && mode === 'map'"
            class="weather-indicator"
            :title="`郑州新郑市实时天气：${weatherInfo.weather} · ${weatherInfo.temperature}℃ · ${weatherInfo.wind}`"
          >
            <CloudSun :size="14" />
            <span>{{ weatherInfo.weather }} {{ weatherInfo.temperature }}℃ · 新郑</span>
          </div>
        </div>

        <div class="exploration-canvas" :aria-busy="loading || moving" :data-camera-moving="moving">
          <div ref="surface" class="exploration-render-host"></div>
          <div v-if="showLabels && !error" class="place-label-layer">
            <template v-for="label in labels" :key="label.id">
              <button
                v-if="label.visible"
                class="place-label"
                :class="{ selected: selected === label.id }"
                :style="{ left: `${label.left}px`, top: `${label.top}px` }"
                :aria-label="`定位${allPlaces.find(place => place.id === label.id)?.name}`"
                @click="locate(label.id)"
              >
                <span class="label-dot"></span>
                {{ allPlaces.find(place => place.id === label.id)?.name }}
              </button>
            </template>
          </div>
          <div v-if="loading" class="exploration-fallback" role="status">
            {{ mode === 'map' ? '正在载入高德地图…' : '正在构建校园场景…' }}
          </div>
          <div v-else-if="error" class="exploration-fallback" role="status">
            <Layers :size="27" />
            <p>{{ error }}</p>
            <button class="button" @click="initScene">重新加载</button>
            <button v-if="mode === 'map'" class="fallback-sandbox" @click="changeMode('model')">使用校园沙盘</button>
          </div>
          <div class="model-caption">
            <span class="model-indicator"></span>
            {{
              mode === 'map'
                ? satellite
                  ? '高德底图 · 卫星航拍影像'
                  : is25D
                    ? currentCampus === 'WEST'
                      ? '高德 2.5D 矢量底图 · 西校区立体建筑'
                      : '高德 2.5D 矢量底图 · 东校区立体建筑'
                    : currentCampus === 'WEST'
                      ? '高德矢量底图 · 西校区平面'
                      : '高德矢量底图 · 东校区平面'
                : currentCampus === 'WEST'
                  ? '校内平面图沙盘 · 非等比例 · 高度未实测'
                  : '校园轮廓示意 · 高度未实测'
            }}
          </div>
          <div v-if="view === 'top' && mode === 'model'" class="north-indicator" aria-label="俯视图上方为北">
            ↑
            <span>北</span>
          </div>
          <div v-if="calibration && mode === 'map'" class="calibration-panel">
            <label>
              地图校准坐标（仅开发环境）
              <output>{{ calibrationCoordinate }}</output>
            </label>
            <div class="calibration-pixels">
              <input v-model.number="calibrationX" type="number" aria-label="参照点画布X" />
              <input v-model.number="calibrationY" type="number" aria-label="参照点画布Y" />
              <button @click="scene?.readCoordinate?.(calibrationX, calibrationY)">读取坐标</button>
            </div>
            <div class="calibration-pixels">
              <input v-model="surveyQuery" aria-label="高德校准检索词" />
              <button :disabled="surveyLoading" @click="surveyPlaces">
                {{ surveyLoading ? '检索中' : '检索高德地点' }}
              </button>
            </div>
            <pre v-if="surveyOutput" class="survey-output" aria-label="高德地点校准结果">{{ surveyOutput }}</pre>
          </div>
          <div class="zoom-options" role="group" aria-label="缩放场景">
            <button aria-label="放大场景" :disabled="loading || !!error" @click="scene?.zoom('in')">
              <Plus :size="17" />
            </button>
            <button aria-label="缩小场景" :disabled="loading || !!error" @click="scene?.zoom('out')">
              <Minus :size="17" />
            </button>
          </div>
          <div v-if="tourStep !== null && !error" class="exploration-tour" aria-label="校园分步导览">
            <button aria-label="上一个导览地点" :disabled="tourStep === 0" @click="tour(tourStep - 1)">
              <ArrowLeft :size="16" />
            </button>
            <div aria-live="polite">
              <small>
                {{ currentCampus === 'WEST' ? '西校区导览' : '东校区导览' }} {{ tourStep + 1 }} /
                {{ activeTour.length }}
              </small>
              <strong>{{ tourPlace?.name }}</strong>
            </div>
            <button v-if="tourStep < activeTour.length - 1" aria-label="下一个导览地点" @click="tour(tourStep + 1)">
              <ArrowRight :size="16" />
            </button>
            <button v-else aria-label="完成导览" @click="closeTour"><Check :size="16" /></button>
            <button class="tour-close" aria-label="结束导览" @click="closeTour"><X :size="14" /></button>
          </div>
          <div
            v-if="routeActive && currentPlace && !error"
            class="spatial-nav-hud"
            aria-label="Agent 空间导航"
            role="status"
          >
            <div class="hud-header">
              <span class="hud-pill">
                <Compass :size="13" class="spin-compass" />
                路线漫游导航
              </span>
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
                <LocateFixed :size="12" />
                <span>视角聚焦</span>
              </button>
              <button class="hud-action-btn" title="退出导航" @click="toggleRoute">
                <X :size="12" />
                <span>结束导航</span>
              </button>
            </div>
          </div>
        </div>
        <div class="exploration-bottom">
          <span class="desktop-instructions">
            {{ mode === 'map' ? '拖动平移 · 滚轮缩放 · 右键旋转俯仰' : '拖动旋转 · 滚轮缩放 · 右键平移' }}
          </span>
          <span class="touch-instructions">
            {{ mode === 'map' ? '单指平移 · 双指缩放与旋转' : '单指旋转 · 双指缩放与平移' }}
          </span>
          <span class="render-state" role="status">
            {{ moving ? '视角切换中' : currentPlace ? currentPlace.name : `自由探索${activeMeta.name}` }}
          </span>
        </div>
      </section>

      <aside class="exploration-directory" aria-label="校园地点目录">
        <div class="directory-heading">
          <h2>
            {{
              currentCampus === 'WEST' ? (mode === 'model' ? '校园分布 · 西区' : '地图收录 · 西区') : '发现校园 · 东区'
            }}
          </h2>
          <span>{{ activeCampusPlaces.length }} 处地点</span>
        </div>
        <label class="exploration-search">
          <Search :size="16" />
          <input v-model="search" aria-label="搜索校园地点" placeholder="搜索建筑、景观或设施" />
          <button v-if="search" aria-label="清空搜索" @click="search = ''"><X :size="14" /></button>
        </label>
        <div class="place-categories" role="group" aria-label="地点分类">
          <button
            v-for="item in campusCategories"
            :key="item.id"
            :aria-pressed="category === item.id"
            @click="category = item.id"
          >
            {{ item.label }}
          </button>
        </div>
        <div class="place-results" aria-label="地点搜索结果">
          <button
            v-for="place in filteredPlaces"
            :key="place.id"
            class="place-directory-item"
            :class="{ selected: selected === place.id }"
            :aria-pressed="selected === place.id"
            @click="choosePlace(place.id)"
          >
            <component :is="placeIcon(place)" :size="17" />
            <span>{{ place.name }}</span>
            <MapPin v-if="selected === place.id" :size="14" />
            <ArrowRight v-else :size="13" class="place-arrow" />
          </button>
          <div v-if="!filteredPlaces.length" class="place-empty">
            <Search :size="22" />
            <p>没有找到匹配地点</p>
            <button
              @click="
                search = '';
                category = 'all';
              "
            >
              查看全部地点
            </button>
          </div>
        </div>
        <div v-if="currentPlace" ref="placeDetail" class="place-detail" aria-live="polite" tabindex="-1">
          <span class="place-category-label">{{ categoryNames[currentPlace.category] }}</span>
          <h3>{{ currentPlace.name }}</h3>
          <p>{{ currentPlace.description }}</p>
          <div class="place-feature-tags">
            <span v-for="feature in currentPlace.features" :key="feature">{{ feature }}</span>
          </div>

          <button v-if="mode === 'map'" class="route-btn" :class="{ active: routeActive }" @click="toggleRoute">
            <Compass :size="13" />
            <span>
              {{
                routeActive
                  ? '清除校内路线'
                  : currentCampus === 'WEST'
                    ? '从校区停车场规划步行路线'
                    : '从正门规划步行路线'
              }}
            </span>
          </button>
          <button v-if="relatedPlace" class="related-place" @click="locate(relatedPlace.id)">
            同组团地点：{{ relatedPlace.name }}
            <ArrowRight :size="12" />
          </button>
          <RouterLink v-if="reservationTarget" :to="reservationTarget" class="reserve-btn">
            <CalendarCheck :size="13" />
            <span>查找此地点的可借用资源</span>
            <ArrowRight :size="12" />
          </RouterLink>
          <small>
            {{
              currentPlace.campusId === 'WEST'
                ? mode === 'model'
                  ? '名称与位置关系依据校内平面示意图，楼体为图示块体；不是测绘轮廓，未赋予精确地图坐标。'
                  : '位置依据高德收录，可能含历史或重名记录；请同时对照校内平面图。'
                : currentPlace.locationType === 'point'
                  ? '符号表示图示位置，未确认独立楼体轮廓、楼层或入口。'
                  : '建筑轮廓与功能分区为示意，高度和楼层未实测；可用场地以预约服务中的清单为准。'
            }}
          </small>
          <a class="place-source" :href="currentPlace.sourceUrl" target="_blank" rel="noopener noreferrer">
            {{
              currentPlace.campusId === 'WEST'
                ? mode === 'model'
                  ? '对照西校区平面图'
                  : '查看高德地点来源'
                : currentPlace.verification === 'reference-located'
                  ? '查看高德地图定位'
                  : '对照实景地图轮廓'
            }}
            <ArrowRight :size="12" />
          </a>
        </div>
        <div v-else-if="currentCampus === 'WEST'" class="west-intro-box">
          <div class="west-intro-tag">西校区概况</div>
          <h4>教学实践与校园生活</h4>
          <p v-if="mode === 'model'">
            按校内平面图补充图书馆、诚毅楼、科技信息楼、工程与实训区、学生公寓及两处餐厅。明德楼内教室、宿舍、医务室以同一楼体关联展示。
          </p>
          <p v-else>这里展示高德收录的地点与坐标。完整楼宇分布可切换独立沙盘查看；两类资料的重名、历史标注分别保留。</p>
          <a :href="westPlanEvidence.url" class="west-plan-link" target="_blank" rel="noopener noreferrer">
            查看西校区平面图
            <ArrowRight :size="12" />
          </a>
          <div class="west-intro-details">
            <div class="west-detail-row">
              <MapPin :size="12" />
              <span>新郑市新村镇107国道路东</span>
            </div>
            <div class="west-detail-row">
              <Compass :size="12" />
              <span>{{ mode === 'model' ? '图示上方为北 · 平面图未注明编制时间' : '地图坐标依据高德收录信息' }}</span>
            </div>
          </div>
        </div>
        <div v-else class="exploration-intro">
          <img :src="campusEvidence.layoutReferenceUrl" alt="东校区校园建筑分布图" />
          <p>
            楼名和功能分布参考校内分布图，建筑外轮廓参考留存卫星影像。H1、H2 为学生公寓图示位置点，楼体轮廓尚待对应。
          </p>
          <a :href="campusEvidence.layoutReferenceUrl" target="_blank" rel="noopener noreferrer">
            查看校园分布图
            <ArrowRight :size="12" />
          </a>
        </div>
        <details v-if="currentCampus === 'WEST'" class="pending-buildings">
          <summary>
            名称待核对
            <span>{{ westUnlocatedBuildings.length }}</span>
          </summary>
          <p>其他学校资料提及、而本张平面图未单独标出的名称，暂不与图中楼宇直接合并。</p>
          <a
            v-for="item in westUnlocatedBuildings"
            :key="item.name"
            :href="item.source"
            target="_blank"
            rel="noopener noreferrer"
          >
            {{ item.name }}
            <ArrowRight :size="12" />
          </a>
        </details>
      </aside>
    </div>
    <div class="exploration-footnote">
      <span>
        <span class="footnote-mark"></span>
        {{
          currentCampus === 'WEST'
            ? mode === 'model'
              ? '西校区平面图提供楼名与相对位置；场景非等比例，高度、楼层和入口未实测，不用于实地导航。'
              : '高德收录坐标与校内平面图分别呈现；地图可能含历史名称，建筑叠加仅使用卫星影像轮廓。'
            : '分布图未注明编制日期。楼层、高度和入口尚待现场资料核对，场景不用于实地导航。'
        }}
      </span>
      <a
        v-if="currentCampus === 'EAST'"
        :href="campusEvidence.layoutReferenceUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        对照校园分布图
        <ArrowRight :size="13" />
      </a>
      <span v-else class="campus-footnote-tag">{{ mode === 'model' ? '西校区独立沙盘' : '高德地图 · 西校区' }}</span>
    </div>
  </div>
</template>

<style scoped src="../styles/pages/campus-explore.css"></style>

<style src="../styles/pages/campus-explore-theme.css"></style>
