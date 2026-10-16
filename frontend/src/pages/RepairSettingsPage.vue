<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { History, MapPin, Plus, RefreshCw, UsersRound } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { repairCapabilities, repairKinds, repairPeopleForCapability, repairProvenance, repairSourceIds } from '../repairSettings'
import type { RepairAudit, RepairCatalog, RepairLocation, RepairScope } from '../repairSettings'
import type { Paged } from '../foundation'
const root = '/admin/repair-settings'
const catalog = ref<RepairCatalog | null>(null)
const locations = ref<Paged<RepairLocation>>({ items: [], total: 0, page: 1, pageSize: 20 })
const scopes = ref<Paged<RepairScope>>({ items: [], total: 0, page: 1, pageSize: 20 })
const scopeLocations = ref<RepairLocation[]>([])
const scopeSearch = ref(''), scopeTotal = ref(0), scopeLoading = ref(false), selectedLocationName = ref('')
const history = ref<Paged<RepairAudit>>({ items: [], total: 0, page: 1, pageSize: 10 })
const tab = ref<'LOCATION' | 'SCOPE'>('LOCATION'), editing = ref(false), loading = ref(true), busy = ref(false)
const selectedId = ref<number | null>(null), historyTarget = ref<{ type: string; id: number; name: string } | null>(null)
const error = ref(''), success = ref('')
const form = reactive({ campusId: '', code: '', name: '', kind: 'CAMPUS_PUBLIC', classroomResourceId: '', buildingId: '', dormRoomId: '', detailHint: '', provenance: 'DEMO',
  userId: '', capability: 'DISPATCH', category: 'WATER', locationId: '', version: 0, reason: '' })
const discardDialog = ref<HTMLDialogElement | null>(null), stateDialog = ref<HTMLDialogElement | null>(null)
const stateTarget = ref<{ type: string; id: number; version: number; name: string; enabled: boolean } | null>(null), stateReason = ref('')
let baseline = '', sequence = 0, scopeSequence = 0, resolveDiscard: ((answer: boolean) => void) | null = null
const dirty = computed(() => (editing.value && JSON.stringify(form) !== baseline) || !!stateReason.value.trim())
const sourceRooms = computed(() => catalog.value?.rooms.filter(room => room.campusId === Number(form.campusId) && (room.available || room.id === Number(form.classroomResourceId))) ?? [])
const sourceDormRooms = computed(() => catalog.value?.dormRooms.filter(room => room.campusId === Number(form.campusId) && (room.available || room.id === Number(form.dormRoomId))) ?? [])
const sourceBuildings = computed(() => catalog.value?.buildings.filter(building => building.campusId === Number(form.campusId)) ?? [])
const people = computed(() => repairPeopleForCapability(catalog.value?.people ?? [], form.capability))
function finishDiscard(answer: boolean) { discardDialog.value?.close(); resolveDiscard?.(answer); resolveDiscard = null }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在保存，请稍后离开。'; return false }
  if (!dirty.value) return true
  if (resolveDiscard) return false
  return new Promise<boolean>(resolve => { resolveDiscard = resolve; discardDialog.value?.showModal() })
}
async function load(locationPage = locations.value.page, scopePage = scopes.value.page) {
  const stamp = ++sequence
  loading.value = true; error.value = ''
  try {
    const [choices, places, responsibilities] = await Promise.all([api<RepairCatalog>(root + '/catalog'),
      api<Paged<RepairLocation>>(root + '/locations?page=' + locationPage), api<Paged<RepairScope>>(root + '/scopes?page=' + scopePage)])
    if (stamp !== sequence) return
    catalog.value = choices; locations.value = places; scopes.value = responsibilities
  } catch (cause) { if (stamp === sequence) error.value = errorMessage(cause) }
  finally { if (stamp === sequence) loading.value = false }
}
async function loadScopeLocations() {
  const stamp = ++scopeSequence
  scopeLocations.value = []; scopeTotal.value = 0
  if (!form.campusId) { scopeLocations.value = []; return }
  scopeLoading.value = true
  try {
    const result = await api<Paged<RepairLocation>>(root + '/locations?campusId=' + form.campusId + '&pageSize=100&q=' + encodeURIComponent(scopeSearch.value))
    if (stamp === scopeSequence) { scopeLocations.value = result.items; scopeTotal.value = result.total }
  } catch (cause) { if (stamp === scopeSequence) error.value = errorMessage(cause) }
  finally { if (stamp === scopeSequence) scopeLoading.value = false }
}
function clearEditor() { editing.value = false; selectedId.value = null; form.reason = ''; historyTarget.value = null }
async function switchTab(value: 'LOCATION' | 'SCOPE') { if (!await allowLeave()) return; closeState(); clearEditor(); tab.value = value; success.value = '' }
async function refresh() { if (!await allowLeave()) return; closeState(); clearEditor(); await load() }
async function edit(item?: RepairLocation | RepairScope) {
  if (!await allowLeave()) return
  closeState(); historyTarget.value = null; scopeSearch.value = ''; selectedLocationName.value = ''
  editing.value = false
  Object.assign(form, { campusId: String(catalog.value?.campuses[0]?.id ?? ''), code: '', name: '', kind: 'CAMPUS_PUBLIC', classroomResourceId: '', buildingId: '', dormRoomId: '',
    detailHint: '', provenance: 'DEMO', userId: '', capability: 'DISPATCH', category: 'WATER', locationId: '', version: 0, reason: '' })
  selectedId.value = item?.id ?? null
  if (item && 'kind' in item) Object.assign(form, { campusId: String(item.campusId), code: item.code, name: item.name, kind: item.kind,
    classroomResourceId: String(item.classroomResourceId ?? ''), buildingId: String(item.buildingId ?? ''), dormRoomId: String(item.dormRoomId ?? ''), detailHint: item.detailHint, provenance: item.provenance, version: item.version })
  if (item && 'capability' in item) { Object.assign(form, { campusId: String(item.campusId), userId: String(item.userId), capability: item.capability, category: item.category, locationId: String(item.locationId ?? ''), version: item.version }); selectedLocationName.value = item.locationName ?? '' }
  await nextTick()
  baseline = JSON.stringify(form); editing.value = true; success.value = ''
  if (tab.value === 'SCOPE') await loadScopeLocations()
  await nextTick(); document.getElementById('repair-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function save() {
  if (!editing.value || busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  const path = tab.value === 'LOCATION' ? '/locations' : '/scopes'
  const payload = tab.value === 'LOCATION' ? { campusId: Number(form.campusId), code: form.code, name: form.name, kind: form.kind,
    ...repairSourceIds(form.kind, form.classroomResourceId, form.buildingId, form.dormRoomId), detailHint: form.detailHint, provenance: form.provenance, version: form.version, reason: form.reason }
    : { userId: Number(form.userId), capability: form.capability, campusId: Number(form.campusId), category: form.category, locationId: form.locationId ? Number(form.locationId) : null, version: form.version, reason: form.reason }
  try { await api(root + path + (selectedId.value ? '/' + selectedId.value : ''), { method: selectedId.value ? 'PUT' : 'POST', body: JSON.stringify(payload) }); clearEditor(); await load(); success.value = tab.value === 'LOCATION' ? '报修地点已保存。' : '人员负责范围已保存，权限立即按新范围核对。' }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function changeState(item: RepairLocation | RepairScope) {
  if (!await allowLeave()) return
  clearEditor(); stateReason.value = ''; stateTarget.value = { type: tab.value, id: item.id, version: item.version, name: 'name' in item ? item.name : item.userName, enabled: item.enabled }
  stateDialog.value?.showModal()
}
function closeState() { if (busy.value) return; stateDialog.value?.close(); stateReason.value = ''; stateTarget.value = null }
async function saveState() {
  const target = stateTarget.value
  if (!target || busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(root + (target.type === 'LOCATION' ? '/locations/' : '/scopes/') + target.id + '/state', { method: 'POST', body: JSON.stringify({ enabled: !target.enabled, version: target.version, reason: stateReason.value }) })
    stateDialog.value?.close(); stateReason.value = ''; stateTarget.value = null; await load(); success.value = '启停状态已保存。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function showHistory(item: RepairLocation | RepairScope, page = 1) {
  if (!await allowLeave()) return
  clearEditor(); const target = { type: tab.value, id: item.id, name: 'name' in item ? item.name : item.userName }
  busy.value = true; error.value = ''
  try { history.value = await api<Paged<RepairAudit>>(root + '/history?type=' + target.type + '&id=' + target.id + '&page=' + page); historyTarget.value = target }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function historyPage(page: number) {
  const target = historyTarget.value
  if (!target) return
  busy.value = true; error.value = ''
  try { history.value = await api<Paged<RepairAudit>>(root + '/history?type=' + target.type + '&id=' + target.id + '&page=' + page) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function pageChange(page: number) { if (!await allowLeave()) return; clearEditor(); await load(tab.value === 'LOCATION' ? page : locations.value.page, tab.value === 'SCOPE' ? page : scopes.value.page) }
watch(() => form.campusId, () => { if (!editing.value) return; if (tab.value === 'SCOPE') { form.locationId = ''; void loadScopeLocations() } else if (selectedId.value === null) { form.classroomResourceId = ''; form.buildingId = ''; form.dormRoomId = '' } })
watch(() => form.kind, () => { if (editing.value && selectedId.value === null) { form.classroomResourceId = ''; form.buildingId = ''; form.dormRoomId = '' } })
watch(() => form.capability, () => { if (editing.value) form.userId = '' })
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { sequence++; scopeSequence++; finishDiscard(false); window.removeEventListener('beforeunload', beforeUnload) })
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
</script>
<template>
  <div class="repair-settings">
    <dialog ref="discardDialog" class="foundation-dialog" aria-labelledby="repair-discard-heading" @cancel.prevent="finishDiscard(false)"><h2 id="repair-discard-heading">还有未保存的配置</h2><p>离开后，本次填写的内容不会保存。</p><div class="form-actions"><button class="button compact" @click="finishDiscard(false)">继续编辑</button><button class="button primary compact" @click="finishDiscard(true)">放弃修改并继续</button></div></dialog>
    <dialog ref="stateDialog" class="foundation-dialog" aria-labelledby="repair-state-heading" @cancel.prevent="closeState"><h2 id="repair-state-heading">{{ stateTarget?.enabled ? '停用' : '启用' }}{{ stateTarget?.name }}</h2><form class="editor-form" @submit.prevent="saveState"><label>调整原因<textarea v-model="stateReason" required maxlength="200" rows="3" :disabled="busy" /></label><p v-if="error" class="alert" role="alert">{{ error }}</p><div class="form-actions"><button class="button primary compact" :disabled="busy">确认{{ stateTarget?.enabled ? '停用' : '启用' }}</button><button class="button compact" type="button" :disabled="busy" @click="closeState">取消操作</button></div></form></dialog>
    <div class="page-heading-row"><div><span class="portal-context">后勤服务配置</span><h1>报修地点与人员范围</h1><p class="muted">维护开放地点，配置后勤和维修人员负责的校区、类别及具体地点。</p></div><button class="button compact" :disabled="loading || busy" @click="refresh"><RefreshCw :size="15" />刷新配置</button></div>
    <p v-if="error && !stateTarget" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <div class="repair-tabs" role="group" aria-label="报修配置分类"><button class="button compact" :class="{ primary: tab === 'LOCATION' }" :disabled="busy" @click="switchTab('LOCATION')"><MapPin :size="16" />开放报修地点</button><button class="button compact" :class="{ primary: tab === 'SCOPE' }" :disabled="busy" @click="switchTab('SCOPE')"><UsersRound :size="16" />人员负责范围</button><button class="button compact repair-add" :disabled="loading || busy" @click="edit()"><Plus :size="16" />{{ tab === 'LOCATION' ? '添加地点' : '添加负责范围' }}</button></div>
    <p v-if="loading" role="status">正在读取地点和人员配置…</p>
    <template v-else>
      <p v-if="tab === 'LOCATION' && !locations.items.length" class="empty-state">还没有开放报修地点。可关联已完善的教室，或配置校园公共区域。</p>
      <p v-if="tab === 'SCOPE' && !scopes.items.length" class="empty-state">还没有负责范围。请先在账号与组织管理中赋予后勤或维修岗位。</p>
      <div v-if="tab === 'LOCATION'" class="repair-card-grid"><article v-for="item in locations.items" :key="item.id" class="portal-panel repair-config-card"><div class="section-heading"><h2>{{ item.name }}</h2><span class="repair-status" :class="{ inactive: !item.available }">{{ item.available ? '已开放' : '暂不可用' }}</span></div><p class="repair-card-main">{{ item.campusName }} · {{ repairKinds[item.kind] }}</p><p class="muted">{{ item.sourceName ? '关联：' + item.sourceName : item.detailHint || '校园公共区域' }}</p><p class="repair-meta">{{ repairProvenance[item.provenance] }}<span v-if="item.buildingVerificationStatus && item.buildingVerificationStatus !== 'VERIFIED'"> · 楼栋资料待核验</span></p><p v-if="!item.available" class="repair-meta">{{ item.availabilityMessage }}</p><div class="repair-card-actions"><button class="text-button" :disabled="busy" @click="edit(item)">编辑地点</button><button class="text-button" :disabled="busy" @click="changeState(item)">{{ item.enabled ? '停用地点' : '启用地点' }}</button><button class="text-button" :disabled="busy" @click="showHistory(item)">修改记录</button></div></article></div>
      <div v-else class="repair-card-grid"><article v-for="item in scopes.items" :key="item.id" class="portal-panel repair-config-card"><div class="section-heading"><h2>{{ item.userName }}</h2><span class="repair-status" :class="{ inactive: !item.effective }">{{ item.effectivenessMessage }}</span></div><p class="repair-card-main">{{ repairCapabilities[item.capability] }} · {{ catalog?.categories.find(c => c.code === item.category)?.name }}</p><p class="muted">{{ item.campusName }} · {{ item.locationName ?? '该校区全部开放地点' }}</p><div class="repair-card-actions"><button class="text-button" :disabled="busy" @click="edit(item)">编辑负责范围</button><button class="text-button" :disabled="busy" @click="changeState(item)">{{ item.enabled ? '停用范围' : '启用范围' }}</button><button class="text-button" :disabled="busy" @click="showHistory(item)">修改记录</button></div></article></div>
      <div class="repair-pagination"><span>共 {{ tab === 'LOCATION' ? locations.total : scopes.total }} 条</span><button class="button compact" :disabled="busy || (tab === 'LOCATION' ? locations.page : scopes.page) <= 1" @click="pageChange((tab === 'LOCATION' ? locations.page : scopes.page) - 1)">上一页</button><span>第 {{ tab === 'LOCATION' ? locations.page : scopes.page }} 页</span><button class="button compact" :disabled="busy || (tab === 'LOCATION' ? locations.page * locations.pageSize >= locations.total : scopes.page * scopes.pageSize >= scopes.total)" @click="pageChange((tab === 'LOCATION' ? locations.page : scopes.page) + 1)">下一页</button></div>
    </template>
    <section v-if="editing" class="portal-panel repair-editor">
<h2 id="repair-editor-heading">{{ selectedId ? '编辑' : '添加' }}{{ tab === 'LOCATION' ? '报修地点' : '人员负责范围' }}</h2><form class="editor-form repair-editor-form" @submit.prevent="save">
      <label>负责校区<select v-model="form.campusId" required :disabled="busy || (tab === 'LOCATION' && selectedId !== null)"><option value="">请选择校区</option><option v-for="campus in catalog?.campuses" :key="campus.id" :value="String(campus.id)">{{ campus.name }}</option></select></label>
      <template v-if="tab === 'LOCATION'">
        <label>地点类型<select v-model="form.kind" :disabled="busy || selectedId !== null"><option v-for="(name, kind) in repairKinds" :key="kind" :value="kind">{{ name }}</option></select></label>
        <label>地点编号<input v-model="form.code" required maxlength="64" pattern="[A-Za-z0-9_-]+" :disabled="busy || selectedId !== null" placeholder="例如 PUBLIC-EAST-01" /></label><label>地点名称<input v-model="form.name" required maxlength="100" :disabled="busy" placeholder="填写便于识别的地点名称" /></label>
        <label v-if="form.kind === 'CLASSROOM'">关联教室<select v-model="form.classroomResourceId" required :disabled="busy || selectedId !== null"><option value="">选择本校区已完善的教室</option><option v-for="room in sourceRooms" :key="room.id" :value="String(room.id)">{{ room.name }} · {{ room.buildingName }} {{ room.roomNumber }}{{ room.available ? '' : '（目前不可用）' }}</option></select></label>
        <label v-if="['CLASSROOM', 'BUILDING_COMMON'].includes(form.kind)">{{ form.kind === 'BUILDING_COMMON' ? '关联楼栋' : '关联楼栋（可选）' }}<select v-model="form.buildingId" :required="form.kind === 'BUILDING_COMMON'" :disabled="busy || selectedId !== null"><option value="">{{ form.kind === 'BUILDING_COMMON' ? '选择本校区已建档楼栋' : '暂不关联楼栋' }}</option><option v-for="building in sourceBuildings" :key="building.id" :value="String(building.id)">{{ building.name }}{{ building.verificationStatus === 'VERIFIED' ? '' : '（资料待核验）' }}</option></select></label>
        <p v-if="form.kind === 'BUILDING_COMMON' && !sourceBuildings.length" class="muted repair-form-wide">当前校区没有已建档楼栋，可先关联教室或配置校园公共区域。</p>
        <label v-if="form.kind === 'DORM_ROOM'">关联宿舍房间<select v-model="form.dormRoomId" required :disabled="busy || selectedId !== null"><option value="">选择本校区已建档的宿舍房间</option><option v-for="room in sourceDormRooms" :key="room.id" :value="String(room.id)">{{ room.buildingName }} · {{ room.floorLabel }} · {{ room.name }}{{ room.provenance === 'DEMO' ? ' · 演示' : '' }}{{ room.available ? '' : '（目前不可用）' }}</option></select></label>
        <p v-if="form.kind === 'DORM_ROOM'" class="muted repair-form-wide">仅当前入住该房间的学生可选择此地点。{{ sourceDormRooms.length ? '变更入住后，旧工单保留历史并停止维修办理。' : '暂无可用房间，请先在住宿资源配置中建档。' }}来源选择最多显示前 1000 条已建档房间。</p>
        <label>资料标记<select v-model="form.provenance" :disabled="busy"><option value="DEMO">演示地点</option><option value="CONFIGURED">管理员配置</option></select></label><label class="repair-form-wide">位置提示<textarea v-model="form.detailHint" maxlength="300" rows="2" :disabled="busy" placeholder="例如出入口、楼层或明显标识" /></label>
      </template>
      <template v-else>
        <label>岗位职责<select v-model="form.capability" :disabled="busy"><option value="DISPATCH">后勤分诊与派单</option><option value="WORK">维修处理</option></select></label>
        <label>负责人员<select v-model="form.userId" required :disabled="busy"><option value="">选择具备该岗位的启用人员</option><option v-for="person in people" :key="person.id" :value="String(person.id)">{{ person.name }} · {{ person.departmentName }}</option><option v-if="form.userId && !people.some(p => p.id === Number(form.userId))" :value="form.userId" disabled>原人员当前没有有效岗位，请重新选择</option></select></label>
        <label>故障类别<select v-model="form.category" :disabled="busy"><option v-for="category in catalog?.categories" :key="category.code" :value="category.code">{{ category.name }}</option></select></label>
        <label class="repair-form-wide">查找具体地点<input v-model="scopeSearch" maxlength="100" :disabled="busy" placeholder="按地点名称或编号搜索，可留空" /><button class="text-button" type="button" :disabled="busy || scopeLoading" @click="loadScopeLocations">搜索地点</button></label>
        <label class="repair-form-wide">具体地点（可选）<select v-model="form.locationId" :disabled="busy || scopeLoading" @change="selectedLocationName = scopeLocations.find(item => item.id === Number(form.locationId))?.name ?? ''"><option value="">该校区全部开放地点</option><option v-for="item in scopeLocations" :key="item.id" :value="String(item.id)">{{ item.name }}{{ item.available ? '' : '（目前不可用）' }}</option><option v-if="form.locationId && !scopeLocations.some(item => item.id === Number(form.locationId))" :value="form.locationId">{{ selectedLocationName || '已选择的地点' }}</option></select><small v-if="scopeTotal > 100">共找到 {{ scopeTotal }} 个地点，当前显示前 100 个，请输入名称缩小范围。</small></label>
      </template>
      <label class="repair-form-wide">配置原因<textarea v-model="form.reason" required maxlength="200" rows="2" :disabled="busy" /></label><div class="form-actions repair-form-wide"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存配置' }}</button><button class="button compact" type="button" :disabled="busy" @click="refresh">取消编辑</button></div>
    </form>
</section>
    <section v-if="historyTarget" class="portal-panel repair-history"><h2><History :size="17" />{{ historyTarget.name }} · 修改记录</h2><ol class="repair-history-list"><li v-for="record in history.items" :key="record.id"><strong>{{ record.actorName }} · {{ record.createdAt.slice(0, 16).replace('T', ' ') }}</strong><p>{{ record.summary }}</p><small>原因：{{ record.reason }}</small></li></ol><div class="repair-pagination"><button class="button compact" :disabled="busy || history.page <= 1" @click="historyPage(history.page - 1)">上一页</button><span>第 {{ history.page }} 页 · 共 {{ history.total }} 条</span><button class="button compact" :disabled="busy || history.page * history.pageSize >= history.total" @click="historyPage(history.page + 1)">下一页</button></div></section>
    <p class="data-note">地点关联按明确资源配置；房间文字名称不代表已关联校园楼栋。管理员配置本身不赋予私有工单和联系资料的访问权。</p>
  </div>
</template>
