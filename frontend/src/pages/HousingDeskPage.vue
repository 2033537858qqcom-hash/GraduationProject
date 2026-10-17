<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { BedDouble, RefreshCw, Search } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import BaseSelect from '../components/BaseSelect.vue'
import HousingTransferDesk from './HousingTransferDesk.vue'
import HousingImportPanel from '../components/HousingImportPanel.vue'
import { bedCanAssign, housingBedStatus, housingRequestKey, housingWhen, type DormBed, type HousingAssignment, type HousingPage, type HousingResponsibilities, type HousingStudent } from '../housing'
const responsibilities = ref<HousingResponsibilities | null>(null)
const buildingId = ref('')
const beds = ref<DormBed[]>([])
const bedTotal = ref(0)
const history = ref<HousingAssignment[]>([])
const username = ref('')
const student = ref<HousingStudent | null>(null)
const bedChoice = ref('')
const reason = ref('')
const checkoutReason = ref<Record<number, string>>({})
const loading = ref(true)
const busy = ref(false)
const importWorking = ref(false), importPending = ref(false)
const deskBusy = computed(() => busy.value || importWorking.value)
const error = ref('')
const success = ref('')
let checkInKey = housingRequestKey()
let buildingSequence = 0
const checkoutKeys: Record<number, string> = {}
const buildings = computed(() => responsibilities.value?.scopes.filter(scope => scope.effective) ?? [])
const vacantBeds = computed(() => beds.value.filter(bedCanAssign))
const buildingOptions = computed(() => buildings.value.map(scope => ({ value: String(scope.buildingId), label: scope.buildingName })))
const bedChoiceOptions = computed(() => {
  if (!vacantBeds.value.length) return [{ value: '', label: '当前楼栋没有可入住床位' }]
  return vacantBeds.value.map(bed => ({ value: String(bed.id), label: `${bed.roomName} · ${bed.floorLabel} · ${bed.label}` }))
})
async function loadResponsibilities() {
  loading.value = true
  error.value = ''
  try {
    responsibilities.value = await api<HousingResponsibilities>('/housing/responsibilities')
    if (!buildings.value.some(scope => String(scope.buildingId) === buildingId.value)) buildingId.value = buildings.value[0] ? String(buildings.value[0].buildingId) : ''
    await loadBuilding()
  } catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
async function loadBuilding() {
  const attempt = ++buildingSequence, selected = buildingId.value
  if (!buildingId.value) { beds.value = []; history.value = []; return }
  const [bedPage, historyPage] = await Promise.all([
    api<HousingPage<DormBed>>('/housing/beds?buildingId=' + buildingId.value + '&pageSize=100'),
    api<HousingPage<HousingAssignment>>('/housing/history?buildingId=' + buildingId.value + '&pageSize=20'),
  ])
  if (attempt !== buildingSequence || selected !== buildingId.value) return
  beds.value = bedPage.items
  bedTotal.value = bedPage.total
  history.value = historyPage.items
  if (!vacantBeds.value.some(bed => bed.id === Number(bedChoice.value))) bedChoice.value = vacantBeds.value[0] ? String(vacantBeds.value[0].id) : ''
}
async function selectBuilding() {
  error.value = ''
  try { await loadBuilding() }
  catch (cause) { error.value = errorMessage(cause) }
}
async function reloadDesk() {
  responsibilities.value = await api<HousingResponsibilities>('/housing/responsibilities')
  if (!buildings.value.some(scope => String(scope.buildingId) === buildingId.value)) buildingId.value = buildings.value[0] ? String(buildings.value[0].buildingId) : ''
  await loadBuilding()
}
async function afterImport() {
  student.value = null
  try { await reloadDesk() }
  catch (cause) { error.value = '记录已提交，床位列表刷新失败：' + errorMessage(cause) }
}
async function refresh() {
  success.value = ''
  await loadResponsibilities()
}
async function lookup() {
  busy.value = true
  error.value = ''
  success.value = ''
  student.value = null
  try { student.value = await api<HousingStudent>('/housing/students?username=' + encodeURIComponent(username.value.trim())) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function checkIn() {
  if (!student.value || !bedChoice.value) return
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await api('/housing/assignments', { method: 'POST', body: JSON.stringify({ requestKey: checkInKey, studentId: student.value.id, bedId: Number(bedChoice.value), reason: reason.value.trim() }) })
    checkInKey = housingRequestKey()
    success.value = '已登记入住。学生可在我的住宿中查看。'
    student.value = null
    username.value = ''
    reason.value = ''
    await reloadDesk()
  } catch (cause) { error.value = errorMessage(cause); checkInKey = housingRequestKey() }
  finally { busy.value = false }
}
async function checkOut(bed: DormBed) {
  if (!bed.assignmentId || bed.assignmentVersion == null) return
  const note = (checkoutReason.value[bed.id] ?? '').trim()
  if (!note) { error.value = '请填写退宿原因'; return }
  checkoutKeys[bed.id] ??= housingRequestKey()
  busy.value = true
  error.value = ''
  success.value = ''
  try {
    await api('/housing/assignments/' + bed.assignmentId + '/checkout', { method: 'POST', body: JSON.stringify({ requestKey: checkoutKeys[bed.id], version: bed.assignmentVersion, reason: note }) })
    delete checkoutKeys[bed.id]
    checkoutReason.value[bed.id] = ''
    success.value = '已办理退宿，床位已释放。'
    await reloadDesk()
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
onMounted(loadResponsibilities)
watch(username, () => { student.value = null })
</script>
<template>
  <div class="repair-settings housing-page">
    <div class="repair-page-heading"><div><span class="portal-context">宿舍管理</span><h1>住宿办理</h1><p>在当前有效负责楼栋登记入住、退宿，或处理已通过学院审核的调宿安排。</p></div><button class="button compact" :disabled="loading || deskBusy || importPending" @click="refresh"><RefreshCw :size="15" />刷新床位</button></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading" role="status">正在读取负责楼栋…</p>
    <p v-else-if="!responsibilities?.scopes.length" class="empty-state">还没有分配楼栋，请联系管理员。</p>
    <template v-else>
      <p class="muted">当前有效范围内在住 {{ responsibilities?.occupied ?? 0 }} 个床位，可入住 {{ responsibilities?.vacant ?? 0 }} 个床位。</p>
      <div class="repair-card-grid"><article v-for="scope in responsibilities?.scopes" :key="scope.id" class="portal-panel repair-config-card"><h2>{{ scope.campusName }} · {{ scope.buildingName }}</h2><p class="repair-status" :class="{ inactive: !scope.effective }">{{ scope.effectivenessMessage }}</p></article></div>
      <div v-if="buildings.length" class="portal-panel repair-editor"><div class="repair-editor-form"><label>负责楼栋<BaseSelect v-model="buildingId" :options="buildingOptions" :disabled="deskBusy" @change="selectBuilding" /></label></div></div>
      <HousingImportPanel v-if="buildings.length" :building-id="Number(buildingId)" :busy="busy" @working="importWorking = $event" @pending="importPending = $event" @changed="afterImport" />
      <form v-if="buildings.length" class="portal-panel repair-editor" @submit.prevent="checkIn"><h2><BedDouble :size="18" />登记入住</h2><div class="repair-editor-form"><label>学生学号<input v-model="username" maxlength="64" :disabled="deskBusy" placeholder="输入完整学号" /></label><div class="repair-form-wide"><button class="button compact" type="button" :disabled="deskBusy || !username.trim()" @click="lookup"><Search :size="15" />查找学生</button><p v-if="student" class="repair-card-main">{{ student.fullName }} · {{ student.username }}{{ student.housed ? '（已有在住记录）' : '' }}</p></div><label class="repair-form-wide">空闲床位<BaseSelect v-model="bedChoice" :options="bedChoiceOptions" :disabled="deskBusy || !vacantBeds.length" /></label><label class="repair-form-wide">入住说明<textarea v-model="reason" required maxlength="200" rows="2" :disabled="deskBusy" /></label><div class="form-actions repair-form-wide"><button class="button primary compact" :disabled="deskBusy || !student || student.housed || !bedChoice">{{ busy ? '正在登记…' : '确认入住' }}</button></div></div><p v-if="bedTotal > beds.length" class="muted">本楼共 {{ bedTotal }} 个床位，当前显示前 {{ beds.length }} 个。</p></form>
      <section class="repair-card-grid" aria-label="床位状态"><article v-for="bed in beds" :key="bed.id" class="portal-panel repair-config-card"><div class="section-heading"><h2>{{ bed.roomName }} · {{ bed.label }}</h2><span class="repair-status" :class="{ inactive: bed.status !== 'OCCUPIED' }">{{ housingBedStatus[bed.status] ?? bed.status }}</span></div><p class="muted">{{ bed.floorLabel }} · {{ bed.code }}{{ bed.enabled && bed.roomEnabled && bed.buildingEnabled ? '' : ' · 已停用' }}</p><p v-if="bed.occupantName">{{ bed.occupantName }} · {{ bed.occupantUsername }}</p><form v-if="bed.assignmentId" class="repair-card-actions" @submit.prevent="checkOut(bed)"><label>退宿原因<input v-model="checkoutReason[bed.id]" maxlength="200" required :disabled="deskBusy" /></label><button class="button compact" :disabled="deskBusy">确认退宿</button></form></article></section>
      <HousingTransferDesk @changed="afterImport" />
      <section class="repair-history"><h2>本楼住宿记录</h2><p v-if="!history.length" class="empty-state">还没有入住或退宿记录。</p><ul v-else class="repair-history-list"><li v-for="item in history" :key="item.id"><strong>{{ item.studentName }} · {{ item.studentUsername }}</strong><p>{{ item.roomName }} · {{ item.bedLabel }} · {{ item.status === 'CURRENT' ? '在住' : '已退宿' }}</p><small><template v-if="item.importBatchId">批量导入 #{{ item.importBatchId }} · </template>{{ housingWhen(item.startedAt) }}<template v-if="item.endedAt"> 至 {{ housingWhen(item.endedAt) }} · {{ item.endReason }}</template></small></li></ul></section>
    </template>
  </div>
</template>
