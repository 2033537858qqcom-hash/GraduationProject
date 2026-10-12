<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Building2, CalendarClock, History, Plus, RefreshCw } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { roomStatus, roomTypes } from '../resources'
import type { ClassroomResource, RoomAudit, RoomOccupancy } from '../resources'
import type { Paged } from '../foundation'

const route = useRoute(), router = useRouter(), root = '/admin/resources'
const catalog = ref<{ campuses: { id: number; name: string }[] } | null>(null)
const rooms = ref<Paged<ClassroomResource>>({ items: [], total: 0, page: 1, pageSize: 20 })
const history = ref<Paged<RoomAudit>>({ items: [], total: 0, page: 1, pageSize: 10 })
const occupancy = ref<Paged<RoomOccupancy>>({ items: [], total: 0, page: 1, pageSize: 10 })
const selected = ref<ClassroomResource | null>(null), editing = ref(false), loading = ref(true), busy = ref(false)
const error = ref(''), success = ref('')
const filters = reactive({ q: '', campusId: '', type: '', enabled: '', ready: '' })
const view = computed(() => route.query.view === 'history' ? 'history' : route.query.view === 'occupancy' ? 'occupancy' : 'detail')
const form = reactive({ campusId: 0, code: '', name: '', buildingName: '', roomNumber: '', floorLabel: '', type: 'LECTURE', teachingCapacity: null as number | null, examCapacity: null as number | null, reason: '', version: 0 })
let baseline = '', sequence = 0
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline)
const actionNames = { CREATED: '新建资源', UPDATED: '修改资源', ENABLED: '启用教室', DISABLED: '停用教室' }
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null)
const confirmation = ref(''), stateTarget = ref<ClassroomResource | null>(null), stateReason = ref('')
let resolveConfirmation: ((accept: boolean) => void) | null = null
function time(value: string) { return value.slice(0, 16).replace('T', ' ') }
async function ask(message: string, target: ClassroomResource | null = null) {
  if (resolveConfirmation) finishConfirmation(false)
  confirmation.value = message; stateTarget.value = target; stateReason.value = ''
  return new Promise<boolean>(resolve => { resolveConfirmation = resolve; void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() }) })
}
function finishConfirmation(accept: boolean) { dialog.value?.close(); const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(accept) }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在提交，请等待完成后再离开。'; return false }
  return !dirty.value || await ask('教室资料尚未保存，离开会放弃这些输入。请先保存，或确认放弃修改。')
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; editing.value = false
  try {
    if (!catalog.value) catalog.value = await api(root + '/catalog')
    Object.assign(filters, { q: String(route.query.q ?? ''), campusId: String(route.query.campusId ?? ''), type: String(route.query.type ?? ''), enabled: String(route.query.enabled ?? ''), ready: String(route.query.ready ?? '') })
    const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
    for (const [key, value] of Object.entries(filters)) if (value) params.set(key, value)
    const result = await api<Paged<ClassroomResource>>(root + '?' + params)
    if (request !== sequence) return
    rooms.value = result; selected.value = null
    if (route.query.id) {
      const item = await api<ClassroomResource>(`${root}/${String(route.query.id)}`)
      if (request !== sequence) return
      selected.value = item
      const page = Math.max(1, Number(route.query.detailPage) || 1)
      if (view.value === 'history') {
        const records = await api<Paged<RoomAudit>>(`${root}/${item.id}/history?page=${page}&pageSize=10`)
        if (request === sequence) history.value = records
      } else if (view.value === 'occupancy') {
        const records = await api<Paged<RoomOccupancy>>(`${root}/${item.id}/occupancy?page=${page}&pageSize=10`)
        if (request === sequence) occupancy.value = records
      }
    }
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function refresh() { if (await allowLeave()) { success.value = ''; await load() } }
async function search() { await router.replace({ query: { ...route.query, ...filters, page: '1' } }) }
async function page(number: number, detail = false) { await router.replace({ query: { ...route.query, [detail ? 'detailPage' : 'page']: String(number) } }) }
async function choose(item: ClassroomResource) {
  if (route.query.id === String(item.id) && view.value === 'detail') { if (await allowLeave()) await load() }
  else await router.replace({ query: { ...route.query, id: String(item.id), view: 'detail', detailPage: '1' } })
}
async function changeView(next: string) { await router.replace({ query: { ...route.query, view: next, detailPage: '1' } }) }
async function edit(item: ClassroomResource | null = null) {
  if (!await allowLeave()) return
  selected.value = item
  Object.assign(form, item ? { campusId: item.campusId, code: item.code, name: item.name, buildingName: item.buildingName ?? '', roomNumber: item.roomNumber ?? '', floorLabel: item.floorLabel ?? '', type: item.type, teachingCapacity: item.teachingCapacity, examCapacity: item.examCapacity, reason: '', version: item.version } :
    { campusId: catalog.value?.campuses[0]?.id ?? 0, code: '', name: '', buildingName: '', roomNumber: '', floorLabel: '', type: 'LECTURE', teachingCapacity: null, examCapacity: null, reason: '', version: 0 })
  baseline = JSON.stringify(form); editing.value = true; error.value = ''; success.value = ''
  await nextTick(); document.getElementById('room-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function cancelEdit() { if (await allowLeave()) { editing.value = false; await load() } }
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const item = await api<ClassroomResource>(root + (selected.value ? '/' + selected.value.id : ''), { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify(form) })
    editing.value = false; busy.value = false
    if (route.query.id === String(item.id) && view.value === 'detail') await load()
    else await router.replace({ query: { ...route.query, id: String(item.id), view: 'detail', detailPage: '1' } })
    success.value = '教室资料已保存，教务安排将按当前资源和容量校验。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function changeState(item: ClassroomResource) {
  const message = item.enabled ? `停用「${item.name}」后不能新增课程或发布考试。未结束的课程与已发布考试会阻止停用，历史安排和记录保留。` : `启用「${item.name}」。楼栋、房间号和两种容量完整后，教务才可安排使用。`
  if (busy.value || !await ask(message, item)) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`${root}/${item.id}/state`, { method: 'POST', body: JSON.stringify({ enabled: !item.enabled, version: item.version, reason: stateReason.value }) })
    await load(); success.value = item.enabled ? '教室已停用，历史安排继续保留。' : '教室已启用。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
watch(() => route.fullPath, () => { success.value = ''; void load() })
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { sequence++; finishConfirmation(false); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="resource-management" :aria-busy="loading">
    <dialog ref="dialog" class="draft-confirm" aria-labelledby="room-confirm-title" @cancel.prevent="finishConfirmation(false)"><form @submit.prevent="finishConfirmation(true)"><h2 id="room-confirm-title">{{ stateTarget ? stateTarget.enabled ? '停用教室' : '启用教室' : '处理未保存的资料' }}</h2><p>{{ confirmation }}</p><label v-if="stateTarget">操作原因<textarea v-model="stateReason" required maxlength="480" rows="3" /></label><div class="form-actions"><button ref="cancelButton" type="button" class="button compact" @click="finishConfirmation(false)">{{ stateTarget ? '取消' : '继续编辑' }}</button><button class="button primary compact">{{ stateTarget ? '确认操作' : '放弃修改并继续' }}</button></div></form></dialog>
    <div class="section-heading manager-heading"><div><span class="portal-context">校园资源管理</span><h1>教室资源目录</h1><p class="muted">维护教学地点、容量与使用状态，核对未来占用。</p></div><div class="form-actions"><button class="button compact" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" />刷新目录</button><button class="button primary compact" :disabled="busy || loading" @click="edit()"><Plus :size="15" />新建教室</button></div></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <form class="foundation-filters resource-filters" @submit.prevent="search"><label class="filter-search">搜索教室<input v-model="filters.q" maxlength="100" placeholder="教室名称、编号或楼栋" /></label><label>校区<select v-model="filters.campusId"><option value="">全部校区</option><option v-for="campus in catalog?.campuses" :key="campus.id" :value="String(campus.id)">{{ campus.name }}</option></select></label><label>类型<select v-model="filters.type"><option value="">全部类型</option><option v-for="(name, key) in roomTypes" :key="key" :value="key">{{ name }}</option></select></label><label>启停状态<select v-model="filters.enabled"><option value="">全部状态</option><option value="true">已启用</option><option value="false">已停用</option></select></label><label>安排资格<select v-model="filters.ready"><option value="">全部记录</option><option value="true">可安排</option><option value="false">不能新排</option></select></label><button class="button compact" :disabled="busy || loading">查询</button></form>
    <section class="portal-panel resource-directory" aria-label="教室目录"><div class="section-heading"><h2><Building2 :size="17" />教室目录</h2><small class="muted">共 {{ rooms.total }} 间</small></div><p v-if="loading" class="loading-state" role="status">正在读取教室资源…</p><p v-else-if="!rooms.items.length" class="empty-state">没有符合条件的教室，可调整筛选或新建记录。</p><div v-if="rooms.items.length" class="table-scroll"><table class="campus-table foundation-table resource-table"><thead><tr><th>教室与编号</th><th>校区与楼栋</th><th>类型</th><th>容量</th><th>使用状态</th><th>未结束占用</th><th>操作</th></tr></thead><tbody><tr v-for="room in rooms.items" :key="room.id" :class="{ 'resource-selected': selected?.id === room.id }"><td><strong>{{ room.name }}</strong><small>{{ room.code }}</small></td><td>{{ room.campusName }}<small>{{ room.buildingName ?? '楼栋待补录' }} · {{ room.roomNumber ?? '房间号待补录' }}{{ room.floorLabel ? ' · ' + room.floorLabel : '' }}</small></td><td>{{ roomTypes[room.type] }}</td><td>教学 {{ room.teachingCapacity ?? '待补录' }}<small>考试 {{ room.examCapacity ?? '待补录' }}</small></td><td><span class="status-label">{{ roomStatus(room) }}</span><small v-if="room.origin === 'MIGRATED'">既有安排导入</small></td><td>课程 {{ room.upcomingLessonCount }}<small>已发布考试 {{ room.publishedExamCount }}</small></td><td><button class="text-button" :disabled="busy" @click="choose(room)">查看与维护</button></td></tr></tbody></table></div><div class="foundation-pagination"><span>第 {{ rooms.page }} 页</span><div><button class="button compact" :disabled="loading || busy || rooms.page <= 1" @click="page(rooms.page - 1)">上一页</button><button class="button compact" :disabled="loading || busy || rooms.page * rooms.pageSize >= rooms.total" @click="page(rooms.page + 1)">下一页</button></div></div></section>
    <section v-if="editing" class="portal-panel resource-detail" aria-label="教室资料编辑"><h2 id="room-editor-heading">{{ selected ? '维护教室资料' : '新建教室资源' }}</h2><p v-if="selected?.origin === 'MIGRATED'" class="content-context-note">该条目由既有安排导入。请核对楼栋、房间号和容量后补录；已有课表仍保留原文字。</p><p v-if="selected?.referenced" class="muted">教室已有教学引用，校区、编号与已确定的楼栋房间保持固定。名称和容量变更会记录原因。</p><form class="editor-form" @submit.prevent="save"><fieldset class="foundation-fields" :disabled="busy"><div class="form-grid"><label>教室名称<input v-model="form.name" required maxlength="100" /></label><label>资源编号<input v-model="form.code" required pattern="[A-Za-z0-9_-]{1,64}" maxlength="64" :readonly="selected?.referenced" placeholder="如 EAST-A-201" /></label></div><div class="form-grid"><label>所属校区<select v-model="form.campusId" required :disabled="selected?.referenced"><option v-for="campus in catalog?.campuses" :key="campus.id" :value="campus.id">{{ campus.name }}</option></select></label><label>教室类型<select v-model="form.type"><option v-for="(name, key) in roomTypes" :key="key" :value="key">{{ name }}</option></select></label></div><div class="form-grid"><label>楼栋名称<input v-model="form.buildingName" required maxlength="100" :readonly="selected?.referenced && !!selected.buildingName" /></label><label>房间号<input v-model="form.roomNumber" required maxlength="50" :readonly="selected?.referenced && !!selected.roomNumber" /></label></div><label>楼层说明<input v-model="form.floorLabel" maxlength="30" placeholder="如二层，可不填写" /></label><div class="form-grid"><label>教学容量（人）<input v-model.number="form.teachingCapacity" type="number" min="1" max="1000" step="1" required /></label><label>考试容量（人）<input v-model.number="form.examCapacity" type="number" min="1" :max="form.teachingCapacity ?? 1000" step="1" required /><small class="muted">考试容量不得超过教学容量，发布考试时再次校验。</small></label></div><label>修改或建档原因<textarea v-model="form.reason" required maxlength="480" rows="2" placeholder="说明资料来源或修改原因，供后续追溯" /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存教室资料' }}</button><button class="button compact" type="button" :disabled="busy" @click="cancelEdit">取消编辑</button></div></form></section>
    <section v-else-if="selected" class="portal-panel resource-detail" aria-label="教室详情">
<div class="section-heading"><div><span class="portal-context">{{ selected.campusName }} · {{ selected.code }}</span><h2>{{ selected.name }}</h2></div><span class="status-label">{{ roomStatus(selected) }} · V{{ selected.version }}</span></div><nav class="learning-tabs" aria-label="教室详情栏目"><button :class="{ selected: view === 'detail' }" :disabled="busy" @click="changeView('detail')">教室资料</button><button :class="{ selected: view === 'occupancy' }" :disabled="busy" @click="changeView('occupancy')"><CalendarClock :size="15" />未来占用</button><button :class="{ selected: view === 'history' }" :disabled="busy" @click="changeView('history')"><History :size="15" />修改记录</button></nav>
      <template v-if="view === 'detail'"><dl class="resource-facts"><div><dt>楼栋与房间</dt><dd>{{ selected.buildingName ?? '待补录' }} · {{ selected.roomNumber ?? '待补录' }}</dd></div><div><dt>楼层与类型</dt><dd>{{ selected.floorLabel || '未填楼层' }} · {{ roomTypes[selected.type] }}</dd></div><div><dt>教学容量</dt><dd>{{ selected.teachingCapacity == null ? '待补录' : selected.teachingCapacity + ' 人' }}</dd></div><div><dt>考试容量</dt><dd>{{ selected.examCapacity == null ? '待补录' : selected.examCapacity + ' 人' }}</dd></div><div><dt>未结束的课程／考试</dt><dd>{{ selected.upcomingLessonCount }} 次课程 · {{ selected.publishedExamCount }} 次已发布考试</dd></div><div><dt>待发布考试草稿</dt><dd>{{ selected.draftExamCount }} 次，草稿不占用时间</dd></div></dl><p v-if="selected.enabled && !selected.schedulable" class="content-context-note">资料待补录，当前不能新增课程或发布考试。请先维护楼栋、房间号及教学／考试容量。</p><p class="muted">最近修改：{{ time(selected.updatedAt) }}。历史安排保留当时的地点文字，冲突校验使用固定资源编号。</p><div class="form-actions"><button class="button primary compact" :disabled="busy || loading" @click="edit(selected)">维护教室资料</button><button class="button compact" :disabled="busy || loading" @click="changeState(selected)">{{ selected.enabled ? '停用教室' : '启用教室' }}</button></div></template>
      <template v-else-if="view === 'occupancy'"><p class="muted">显示尚未结束的课程与已发布考试；未发布草稿不占用时间。共 {{ occupancy.total }} 条。</p><p v-if="!loading && !occupancy.items.length" class="empty-state">当前没有未结束的有效占用。</p><div class="table-scroll"><table v-if="occupancy.items.length" class="campus-table"><thead><tr><th>类型与课程</th><th>教学班</th><th>时间</th><th>人数</th></tr></thead><tbody><tr v-for="record in occupancy.items" :key="record.kind + record.id"><td>{{ record.kind === 'EXAM' ? '已发布考试' : '课程安排' }}<small>{{ record.courseName }}</small></td><td>{{ record.className }}</td><td>{{ time(record.startsAt) }}<small>至 {{ time(record.endsAt) }}</small></td><td>{{ record.memberCount }} 人</td></tr></tbody></table></div><div class="foundation-pagination"><span>第 {{ occupancy.page }} 页</span><div><button class="button compact" :disabled="loading || occupancy.page <= 1" @click="page(occupancy.page - 1, true)">上一页</button><button class="button compact" :disabled="loading || occupancy.page * occupancy.pageSize >= occupancy.total" @click="page(occupancy.page + 1, true)">下一页</button></div></div></template>
      <template v-else><p class="muted">保留建档、资料修改和启停原因，共 {{ history.total }} 条。</p><article v-for="record in history.items" :key="record.id" class="foundation-audit"><strong>{{ actionNames[record.action] }} · V{{ record.version }}</strong><p>{{ record.reason }}</p><p>{{ record.detail }}</p><small>{{ record.actorName }} · {{ time(record.createdAt) }}</small></article><p v-if="!loading && !history.items.length" class="empty-state">暂无资源修改记录。由旧安排导入的条目从本次维护开始记录。</p><div class="foundation-pagination"><span>第 {{ history.page }} 页</span><div><button class="button compact" :disabled="loading || history.page <= 1" @click="page(history.page - 1, true)">上一页</button><button class="button compact" :disabled="loading || history.page * history.pageSize >= history.total" @click="page(history.page + 1, true)">下一页</button></div></div></template>
    </section>
    <p class="data-note">楼栋、容量和教室安排为毕业设计演示。管理员维护资源目录，教务维护本学院课程与考试；尚未接入学校真实教室资产。</p>
  </div>
</template>
