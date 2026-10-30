<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import {
  BedDouble,
  Clock,
  History,
  Info,
  RefreshCw,
  Users,
  Wrench,
} from '@lucide/vue'
import HousingTransferMaterials from '../components/HousingTransferMaterials.vue'
import ServiceJourney from '../components/ServiceJourney.vue'
import { api, errorMessage } from '../lib/api'
import {
  housingRequestKey,
  housingWhen,
  transferStatus,
  type HousingMine,
  type HousingTransfer,
} from '../housing'
import { currentUser } from '../stores/auth'
import type { DormRepairEligibility } from '../repairSettings'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const route = useRoute()
const router = useRouter()

// 标签切换：'stay' (我的住宿与档案) | 'transfer' (调宿申请与进度)
const activeTab = ref<'stay' | 'transfer'>('stay')
const housingControlRef = ref<HTMLElement | null>(null)
const { indicatorStyle: housingPillStyle } = useSlidingIndicator(
  housingControlRef,
  () => activeTab.value,
  { activeSelector: '.service-segment-btn.active' }
)

// 住宿档案数据
const stayData = ref<HousingMine | null>(null)
const repair = ref<DormRepairEligibility | null>(null)
const repairError = ref('')

// 调宿申请数据
const transferItems = ref<HousingTransfer[]>([])
const canCreate = computed(() => !transferItems.value.some(item => ['DRAFT', 'SUBMITTED', 'RETURNED', 'AWAITING_ASSIGNMENT', 'AWAITING_CHECKIN'].includes(item.status)))
const activeTransfer = computed(() => transferItems.value.find(item => ['DRAFT', 'SUBMITTED', 'RETURNED', 'AWAITING_ASSIGNMENT', 'AWAITING_CHECKIN'].includes(item.status)))
const activeTransferCount = computed(() => transferItems.value.filter(item => ['DRAFT', 'SUBMITTED', 'RETURNED', 'AWAITING_ASSIGNMENT', 'AWAITING_CHECKIN'].includes(item.status)).length)

// 全局加载与操作状态
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const success = ref('')

// 调宿表单与编辑状态
const form = ref({ reason: '', preferredArea: '', materialNote: '' })
const comment = ref('')
const editing = ref<HousingTransfer | null>(null)
let key = housingRequestKey()
let sequence = 0

// 申请材料与页面离开防护
const pendingMaterials = ref<Record<number, boolean>>({})
const workingMaterials = ref<Record<number, boolean>>({})
const hasPendingMaterials = computed(() => Object.values(pendingMaterials.value).some(Boolean))
const materialBusy = computed(() => Object.values(workingMaterials.value).some(Boolean))

function canLeave() {
  return !busy.value && !materialBusy.value && (!hasPendingMaterials.value || window.confirm('所选材料尚未上传，离开后需要重新选择。确认离开？'))
}

function beforeUnload(event: BeforeUnloadEvent) {
  if (hasPendingMaterials.value || materialBusy.value || busy.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}

function initTabFromRoute() {
  if (route.query.tab === 'transfer' || route.path.endsWith('/transfer') || (route.query.id !== undefined && route.query.tab === 'details')) {
    activeTab.value = 'transfer'
  } else if (route.query.tab === 'stay') {
    activeTab.value = 'stay'
  } else {
    activeTab.value = route.path.endsWith('/transfer') ? 'transfer' : 'stay'
  }
}

function switchTab(tab: 'stay' | 'transfer') {
  if (activeTab.value === tab) return
  if (activeTab.value === 'transfer' && (materialBusy.value || hasPendingMaterials.value)) {
    if (!window.confirm('所选材料尚未上传，离开后需要重新选择。确认切换？')) return
  }
  activeTab.value = tab
  const currentQuery = { ...route.query }
  if (tab === 'stay' && route.path.endsWith('/mine')) {
    delete currentQuery.tab
  } else {
    currentQuery.tab = tab
  }
  void router.replace({ path: route.path, query: currentQuery })
}

function goToTransferApply() {
  switchTab('transfer')
  if (!activeTransfer.value) {
    void nextTick(() => {
      document.getElementById('housing-form-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  } else {
    void nextTick(() => {
      document.getElementById('transfer-' + activeTransfer.value?.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    })
  }
}

async function load() {
  const current = ++sequence
  loading.value = true
  error.value = ''
  success.value = ''
  repair.value = null
  repairError.value = ''

  try {
    // 1. 获取住宿档案
    const stayPromise = api<HousingMine>('/housing/mine')
      .then(result => { if (current === sequence) stayData.value = result })
      .catch(cause => { if (current === sequence) error.value = errorMessage(cause) })

    // 2. 若具有报修权限，查询宿舍报修资格
    let repairPromise: Promise<void> = Promise.resolve()
    if (currentUser.value?.permissions.includes('repair:apply')) {
      repairPromise = api<DormRepairEligibility>('/repairs/my-dorm')
        .then(eligibility => { if (current === sequence) repair.value = eligibility })
        .catch(cause => { if (current === sequence) repairError.value = errorMessage(cause) })
    }

    // 3. 获取个人调宿申请列表
    const transferPromise = (async () => {
      try {
        const result = await api<HousingTransfer[]>('/housing/transfers/mine')
        const id = route.query.id
        if (id !== undefined) {
          if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || route.query.tab !== 'details') {
            throw new Error('申请链接无效，请从办事记录或消息重新打开。')
          }
          const detail = await api<HousingTransfer>('/housing/transfers/mine/' + id)
          result.unshift(detail)
        }
        if (current === sequence) {
          transferItems.value = result.filter((item, index) => result.findIndex(row => row.id === item.id) === index)
          if (id) {
            activeTab.value = 'transfer'
            await nextTick()
            if (current === sequence) document.getElementById('transfer-' + id)?.scrollIntoView({ block: 'start' })
          }
        }
      } catch (cause) {
        if (current === sequence && !error.value) error.value = errorMessage(cause)
      }
    })()

    await Promise.all([stayPromise, repairPromise, transferPromise])
  } finally {
    if (current === sequence) loading.value = false
  }
}

function editDraft(item: HousingTransfer) {
  editing.value = item
  form.value = { reason: item.reason, preferredArea: item.preferredArea, materialNote: item.materialNote }
  key = housingRequestKey()
  activeTab.value = 'transfer'
  void nextTick(() => document.getElementById('housing-form-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

function cancelEdit() {
  editing.value = null
  form.value = { reason: '', preferredArea: '', materialNote: '' }
  key = housingRequestKey()
}

async function create() {
  if (materialBusy.value || hasPendingMaterials.value) {
    error.value = '请先上传或清除所选材料。'
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    if (editing.value) {
      await api('/housing/transfers/mine/' + editing.value.id, {
        method: 'PUT',
        body: JSON.stringify({ requestKey: key, version: editing.value.version, ...form.value }),
      })
      cancelEdit()
      success.value = '资料修改已保存，请核对材料后再提交或补交。'
      await load()
      return
    }
    const created = await api<HousingTransfer>('/housing/transfers/mine', {
      method: 'POST',
      body: JSON.stringify({ requestKey: key, version: 0, ...form.value }),
    })
    key = housingRequestKey()
    form.value = { reason: '', preferredArea: '', materialNote: '' }
    success.value = '调宿草稿已保存，请在下方申请卡片中核对或上传附件材料，确认无误后点击提交。'
    await load()
    await nextTick()
    document.getElementById('transfer-' + created.id)?.scrollIntoView({ block: 'start' })
  } catch (cause) {
    error.value = errorMessage(cause)
    key = housingRequestKey()
  } finally {
    busy.value = false
  }
}

async function act(item: HousingTransfer, action: string) {
  if (hasPendingMaterials.value || materialBusy.value || editing.value) {
    error.value = '请先保存资料修改，并上传或清除所选材料。'
    return
  }
  if (!comment.value.trim() && !['submit', 'supplement'].includes(action)) {
    error.value = '请填写说明'
    return
  }
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    const path = '/housing/transfers/mine/' + item.id + '/' + action
    const body = action === 'supplement'
      ? { requestKey: key, version: item.version, reason: item.reason, preferredArea: item.preferredArea, materialNote: item.materialNote }
      : { requestKey: key, version: item.version, comment: comment.value.trim() || '提交' }
    await api(path, { method: 'POST', body: JSON.stringify(body) })
    key = housingRequestKey()
    comment.value = ''
    success.value = '已记录本次处理。'
    await load()
    await nextTick()
    document.getElementById('transfer-' + item.id)?.scrollIntoView({ block: 'start' })
  } catch (cause) {
    error.value = errorMessage(cause)
    key = housingRequestKey()
  } finally {
    busy.value = false
  }
}

onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(canLeave)

onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload)
  initTabFromRoute()
  void load()
})

watch(() => route.fullPath, () => {
  initTabFromRoute()
  cancelEdit()
  void load()
})

onBeforeUnmount(() => {
  sequence++
  window.removeEventListener('beforeunload', beforeUnload)
})
</script>

<template>
  <div class="repair-settings housing-page service-workflow service-housing">
    <!-- 页面标题与操作 -->
    <div class="repair-page-heading">
      <div>
        <span class="portal-context">学生服务中心</span>
        <h1>我的住宿与调宿</h1>
        <p>一站式查看当前床位档案、本楼宿管与入住历史，在线提交调宿申请并跟进审批与换宿进度。</p>
      </div>
      <button
        class="button compact"
        :disabled="loading || busy || materialBusy || hasPendingMaterials"
        @click="load"
      >
        <RefreshCw :size="15" />
        刷新数据
      </button>
    </div>

    <!-- Apple 风格分段切换控制器 -->
    <div class="housing-tab-toolbar">
      <div ref="housingControlRef" class="service-segmented-control" role="tablist" aria-label="住宿与调宿服务切换">
        <button
          type="button"
          role="tab"
          class="service-segment-btn"
          :class="{ active: activeTab === 'stay' }"
          :aria-selected="activeTab === 'stay'"
          @click="switchTab('stay')"
        >
          <BedDouble :size="14" style="vertical-align: -2px; margin-right: 4px;" />
          我的住宿与档案
        </button>
        <button
          type="button"
          role="tab"
          class="service-segment-btn"
          :class="{ active: activeTab === 'transfer' }"
          :aria-selected="activeTab === 'transfer'"
          @click="switchTab('transfer')"
        >
          <RefreshCw :size="13" style="vertical-align: -2px; margin-right: 4px;" />
          调宿申请与进度
          <span v-if="activeTransferCount" class="tab-counter-badge">{{ activeTransferCount }}</span>
        </button>
        <div class="service-segment-pill" :style="housingPillStyle" aria-hidden="true"></div>
      </div>
    </div>

    <!-- 消息提示栏 -->
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>

    <!-- 加载中指示 -->
    <p v-if="loading" role="status">正在读取住宿与调宿数据…</p>

    <template v-else>
      <!-- TAB 1: 我的住宿与档案 -->
      <section v-if="activeTab === 'stay'" class="housing-stay-section">
        <template v-if="stayData">
          <!-- 当前入住核心卡片 -->
          <article v-if="stayData.current" class="portal-panel repair-config-card housing-stay-hero">
            <div class="section-heading">
              <h2>
                <span class="service-entry-icon icon-theme-teal" style="width: 32px; height: 32px; border-radius: 8px; display: inline-flex; align-items: center; justify-content: center; margin-right: 8px;">
                  <BedDouble :size="18" />
                </span>
                当前入住床位
              </h2>
              <span class="service-badge badge-success">在住中</span>
            </div>

            <p class="repair-card-main housing-bed-highlight">
              {{ stayData.current.campusName }} · {{ stayData.current.buildingName }} · {{ stayData.current.roomName }} · {{ stayData.current.floorLabel }} · {{ stayData.current.bedLabel }}
            </p>

            <div class="housing-stay-meta">
              <p class="muted">
                <Clock :size="14" style="vertical-align: -2px; margin-right: 4px;" />
                入住时间：{{ housingWhen(stayData.current.startedAt) }}
              </p>
              <p>
                <Users :size="14" style="vertical-align: -2px; margin-right: 4px;" />
                {{ stayData.current.managers.length ? '本楼宿管老师：' + stayData.current.managers.join('、') : '本楼暂无有效宿管，请联系管理员。' }}
              </p>
            </div>

            <!-- 住宿与调宿快速联动操作栏 -->
            <div class="housing-stay-actions">
              <button
                type="button"
                class="button primary compact"
                @click="goToTransferApply"
              >
                <RefreshCw :size="14" />
                {{ activeTransfer ? `查看调宿办理进度 (${transferStatus[activeTransfer.status] || activeTransfer.status})` : '申请调整宿舍 / 床位' }}
              </button>
            </div>
          </article>

          <p v-else class="empty-state">
            目前没有在住记录。初始入住由宿舍管理员统一登记办理。
          </p>

          <!-- 宿舍报修卡片 -->
          <article v-if="repair || repairError" class="portal-panel repair-config-card">
            <div class="section-heading">
              <h2><Wrench :size="18" />宿舍设施报修</h2>
            </div>
            <p>{{ repair?.message || repairError }}</p>
            <p v-if="repair?.location" class="muted">
              {{ repair.location.campusName }} · {{ repair.location.name }}{{ repair.location.provenance === 'DEMO' ? ' · 演示地点' : '' }}
            </p>
            <RouterLink
              v-if="repair?.location"
              class="button primary compact"
              :to="{ path: '/repairs/mine', query: { new: '1', location: String(repair.location.id) } }"
            >
              为当前宿舍报修
            </RouterLink>
          </article>

          <!-- 入住历史记录 -->
          <section class="repair-history">
            <div class="section-heading">
              <h2><History :size="18" />入住历史档案</h2>
            </div>
            <p v-if="!stayData.history.length" class="empty-state">还没有已结束的历史住宿记录。</p>
            <ul v-else class="repair-history-list">
              <li v-for="item in stayData.history" :key="item.id">
                <strong>{{ item.campusName }} · {{ item.buildingName }} · {{ item.roomName }} · {{ item.bedLabel }}</strong>
                <p>{{ housingWhen(item.startedAt) }} 至 {{ housingWhen(item.endedAt) }}</p>
                <small>{{ item.endReason }}</small>
              </li>
            </ul>
          </section>
        </template>
      </section>

      <!-- TAB 2: 调宿申请与办理进度 -->
      <section v-else-if="activeTab === 'transfer'" class="housing-transfer-section">
        <ServiceJourney kind="HOUSING" :status="activeTransfer?.status" :preparing="!!editing" :cancellation-pending="activeTransfer?.cancelRequested" />
        <!-- 当前在住床位上下文提示条 -->
        <div class="housing-context-banner">
          <Info :size="16" />
          <span>
            当前原床位：<strong>{{ stayData?.current ? `${stayData.current.campusName} · ${stayData.current.buildingName} · ${stayData.current.roomName} · ${stayData.current.bedLabel}` : '暂无在住床位记录' }}</strong>
            （调宿申请审批通过后，将由宿管老师安排新床位并办理入住交接）
          </span>
        </div>

        <!-- 调宿申请表单（新建草稿或修改已有草稿/补交） -->
        <form
          v-if="editing || canCreate"
          class="portal-panel repair-editor"
          @submit.prevent="create"
        >
          <h2 id="housing-form-heading">
            {{ editing ? (editing.status === 'RETURNED' ? '编辑补交资料 #' : '修改调宿草稿 #') + editing.id : '新建调宿申请' }}
          </h2>
          <div class="repair-editor-form">
            <label class="repair-form-wide">
              <div class="label-heading-row">
                <span>调宿原因</span>
                <small class="muted">必填 · 最多 500 字</small>
              </div>
              <textarea
                v-model="form.reason"
                required
                maxlength="500"
                rows="3"
                :disabled="busy || materialBusy"
                placeholder="请详细说明调宿的具体原因（例如：作息时间差异、生活习惯调整、身体健康需要低楼层等）"
              />
            </label>
            <label>
              <div class="label-heading-row">
                <span>意向区域</span>
                <small class="muted">必填 · 最多 200 字</small>
              </div>
              <input
                v-model="form.preferredArea"
                required
                maxlength="200"
                :disabled="busy || materialBusy"
                placeholder="例如：东校区低楼层、静音作息宿舍区"
              />
            </label>
            <label>
              <div class="label-heading-row">
                <span>材料说明</span>
                <small class="muted">必填 · 最多 500 字</small>
              </div>
              <input
                v-model="form.materialNote"
                required
                maxlength="500"
                :disabled="busy || materialBusy"
                placeholder="说明辅导员与宿管核验材料用途（证明附件保存草稿后在下方卡片中添加）"
              />
            </label>
            <div class="form-actions repair-form-wide">
              <button class="button primary compact" :disabled="busy || materialBusy">
                {{ busy ? '正在保存…' : editing ? '保存资料修改' : '保存调宿草稿' }}
              </button>
              <button
                v-if="editing"
                type="button"
                class="button compact"
                :disabled="busy || materialBusy"
                @click="cancelEdit"
              >
                取消修改
              </button>
            </div>
          </div>
        </form>

        <!-- 调宿进行中提示（已有活跃申请时不能重复新建） -->
        <div v-else-if="activeTransfer" class="housing-notice-banner">
          <Info :size="16" />
          <span>您当前已有进行中的调宿申请（<strong>#{{ activeTransfer.id }} · {{ transferStatus[activeTransfer.status] || activeTransfer.status }}</strong>），需等待当前流程办结或撤回后方可发起新申请。</span>
        </div>

        <!-- 调宿记录列表 -->
        <p v-if="!transferItems.length" class="empty-state">
          还没有调宿申请记录。如需更换宿舍或床位，可在上方填写原因并发起调宿申请。
        </p>

        <div v-else class="repair-card-grid housing-transfer-list" :class="{ single: transferItems.length === 1 }">
          <article
            v-for="item in transferItems"
            :id="'transfer-' + item.id"
            :key="item.id"
            class="portal-panel repair-config-card"
            :aria-label="'调宿申请 #' + item.id"
          >
            <p v-if="String(item.id) === route.query.id" class="data-note">
              当前选择的调宿申请 #{{ item.id }} · 请核对下方原始资料
            </p>
            <div class="section-heading">
              <h2>申请 #{{ item.id }}</h2>
              <span class="repair-status">{{ transferStatus[item.status] || item.status }}</span>
            </div>
            <p><strong>原宿舍床位：</strong>{{ item.sourcePlace }}</p>
            <p class="muted"><strong>意向区域：</strong>{{ item.preferredArea }}</p>
            <p><strong>调宿原因：</strong>{{ item.reason }}</p>
            <p v-if="item.materialNote" class="muted"><strong>材料说明：</strong>{{ item.materialNote }}</p>
            <p v-if="item.reviewComment"><strong>初审意见：</strong>{{ item.reviewComment }}</p>
            <p v-if="item.targetPlace">
              <strong>{{ item.status === 'COMPLETED' ? '新住宿床位：' : item.status === 'AWAITING_CHECKIN' ? '预留床位：' : '原安排床位：' }}</strong>
              {{ item.targetPlace }}
              <template v-if="item.reserveUntil && item.status === 'AWAITING_CHECKIN'">
                ，请于 {{ housingWhen(item.reserveUntil) }} 前办理入住
              </template>
            </p>
            <p v-if="item.cancelRequested && item.status === 'AWAITING_CHECKIN'" class="muted">
              已请求取消入住，等待宿管老师确认。
            </p>

            <div class="repair-card-actions">
              <button
                v-if="item.allowedActions.includes('edit') || item.allowedActions.includes('supplement')"
                class="text-button"
                type="button"
                :disabled="busy || materialBusy"
                @click="editDraft(item)"
              >
                {{ item.status === 'RETURNED' ? '编辑补交资料' : '修改草稿' }}
              </button>
              <button
                v-if="item.allowedActions.includes('submit')"
                class="text-button"
                type="button"
                :disabled="busy || materialBusy || hasPendingMaterials || !!editing"
                @click="act(item, 'submit')"
              >
                核对后提交
              </button>
              <button
                v-if="item.allowedActions.includes('supplement')"
                class="text-button"
                type="button"
                :disabled="busy || materialBusy || hasPendingMaterials || !!editing"
                @click="act(item, 'supplement')"
              >
                核对后补交
              </button>
              <button
                v-if="item.allowedActions.includes('withdraw')"
                class="text-button"
                type="button"
                :disabled="busy || materialBusy"
                @click="act(item, 'withdraw')"
              >
                撤回
              </button>
              <button
                v-if="item.allowedActions.includes('cancel-request')"
                class="text-button"
                type="button"
                :disabled="busy || materialBusy"
                @click="act(item, 'cancel-request')"
              >
                请求取消
              </button>
            </div>

            <!-- 材料上传与管理附件组件 -->
            <HousingTransferMaterials
              :request="item"
              view="mine"
              :busy="busy || materialBusy"
              :initial-open="String(item.id) === route.query.id"
              @changed="version => item.version = version"
              @pending="value => pendingMaterials[item.id] = value"
              @working="value => workingMaterials[item.id] = value"
            />
          </article>
        </div>

        <!-- 撤回/取消处理说明输入 -->
        <label v-if="transferItems.some(item => item.allowedActions.length)" class="repair-editor" style="margin-top: 18px;">
          <div class="label-heading-row">
            <span>处理说明</span>
            <small class="muted">最多 200 字</small>
          </div>
          <textarea
            v-model="comment"
            maxlength="200"
            rows="3"
            :disabled="busy || materialBusy"
            placeholder="撤回申请或请求取消时填写说明原因"
          />
        </label>
      </section>
    </template>
  </div>
</template>

<style scoped>
.housing-tab-toolbar {
  margin-bottom: 22px;
}
.tab-counter-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  margin-left: 6px;
  font-size: 11px;
  font-weight: 600;
  border-radius: 999px;
  background: #2563eb;
  color: #ffffff;
}
.housing-stay-hero {
  border: 1px solid #cbd5e1;
  background: linear-gradient(180deg, #ffffff 0%, #f8fafc 100%);
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.04);
}
.housing-bed-highlight {
  font-size: 16px;
  font-weight: 650;
  color: #0f172a;
  margin: 10px 0 14px;
  letter-spacing: -0.01em;
}
.housing-stay-meta {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  font-size: 13.5px;
}
.housing-stay-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 16px;
  border-top: 1px solid #e2e8f0;
}
.housing-context-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f0fdfa;
  border: 1px solid #ccfbf1;
  color: #0f766e;
  padding: 12px 16px;
  border-radius: 10px;
  margin-bottom: 20px;
  font-size: 13.5px;
  line-height: 1.5;
}
.housing-notice-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  color: #475569;
  padding: 12px 16px;
  border-radius: 10px;
  margin-bottom: 20px;
  font-size: 13px;
}
.housing-transfer-list.single {
  grid-template-columns: minmax(0, 1fr);
}
.housing-transfer-list {
  margin-top: 18px;
}
</style>
