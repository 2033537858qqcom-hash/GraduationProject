<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { Building2, History, Plus, Search, ShieldCheck, Users } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import BaseSelect from '../components/BaseSelect.vue'
import ResponsibilityPanel from '../components/ResponsibilityPanel.vue'
import ManagedPasswordReset from '../components/ManagedPasswordReset.vue'
import { currentUser } from '../stores/auth'
import type { FoundationAudit, FoundationCatalog, FoundationClass, FoundationDepartment, FoundationMajor, ManagedAccount, Paged, ResponsibilityImpact, ResponsibilityException } from '../foundation'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const route = useRoute(), router = useRouter()
const root = '/admin/foundation'
const tab = computed(() => route.query.tab === 'responsibility' ? 'responsibility' : route.query.tab === 'organization' ? 'organization' : route.query.tab === 'audit' ? 'audit' : 'accounts')
const foundationNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: foundationIndicatorStyle } = useSlidingIndicator(
  foundationNavRef,
  () => tab.value
)
type OrgType = 'departments' | 'majors' | 'class-groups'
const orgType = computed<OrgType>(() => route.query.org === 'majors' ? 'majors' : route.query.org === 'class-groups' ? 'class-groups' : 'departments')
const orgLabel = computed(() => orgType.value === 'departments' ? '学院与部门' : orgType.value === 'majors' ? '专业' : '行政班')
const catalog = ref<FoundationCatalog | null>(null)
const accounts = ref<Paged<ManagedAccount>>({ items: [], total: 0, page: 1, pageSize: 20 })
const audits = ref<Paged<FoundationAudit>>({ items: [], total: 0, page: 1, pageSize: 20 })
const loading = ref(true), busy = ref(false), error = ref(''), success = ref('')
const filters = reactive({ q: '', role: '', departmentId: '', enabled: '' })
const auditType = ref('')
const accountOpen = ref(false), selected = ref<ManagedAccount | null>(null)
const passwordResetAccount = ref<ManagedAccount | null>(null)
const form = reactive({ username: '', fullName: '', identityNumber: '', campusId: 0, departmentId: 0, classGroupId: 0, roles: [] as string[], initialPassword: '', reason: '', version: 0 })
const orgOpen = ref(false), orgId = ref<number | null>(null)
const organization = reactive({ name: '', kind: 'COLLEGE', parentId: 0, version: 0 })
let accountBaseline = '', orgBaseline = '', hydrating = false, sequence = 0
const accountSnapshot = () => JSON.stringify({ ...form, roles: [...form.roles].sort() })
const dirty = computed(() => (accountOpen.value && accountSnapshot() !== accountBaseline) || (orgOpen.value && JSON.stringify(organization) !== orgBaseline))
const student = computed(() => form.roles.includes('STUDENT'))
const academicIdentity = computed(() => form.roles.some(role => ['STUDENT', 'TEACHER', 'ACADEMIC_STAFF'].includes(role)))
const departments = computed(() => catalog.value?.departments.filter(d => !academicIdentity.value || d.kind === 'COLLEGE') ?? [])
const availableClasses = computed(() => catalog.value?.classGroups.filter(group => catalog.value?.majors.find(m => m.id === group.majorId)?.departmentId === form.departmentId) ?? [])
const organizationRows = computed(() => orgType.value === 'departments' ? catalog.value?.departments ?? [] : orgType.value === 'majors' ? catalog.value?.majors ?? [] : catalog.value?.classGroups ?? [])
const shownPage = computed(() => tab.value === 'audit' ? audits.value : accounts.value)
const totalPages = computed(() => Math.max(1, Math.ceil(shownPage.value.total / shownPage.value.pageSize)))
const confirmation = ref(''), confirmationDialog = ref<HTMLDialogElement | null>(null), keepButton = ref<HTMLButtonElement | null>(null)
const impact = ref<ResponsibilityImpact | null>(null), impactAccount = ref<ManagedAccount | null>(null)
const exceptions = ref<ResponsibilityException[]>([]), emergency = ref(false)
const stateCandidate = ref<ManagedAccount | null>(null), stateReason = ref('')
let resolveConfirmation: ((accept: boolean) => void) | null = null
const actionNames: Record<string, string> = { USER_EMERGENCY_DISABLE: '紧急停用账号', VENUE_ROOM_LINK: '关联教学教室', VENUE_CREATE: '新增预约资源', USER_PASSWORD_CHANGE: '本人修改密码', USER_PASSWORD_RESET: '管理员重置密码', USER_CREATE: '新建账号', USER_UPDATE: '修改账号与岗位', USER_ENABLE: '启用账号', USER_DISABLE: '停用账号', DEPARTMENT_CREATE: '新建学院或部门', DEPARTMENT_UPDATE: '修改学院或部门', MAJOR_CREATE: '新建专业', MAJOR_UPDATE: '修改专业', CLASS_GROUP_CREATE: '新建行政班', CLASS_GROUP_UPDATE: '修改行政班', LOCATION_CREATE: '新增报修地点', LOCATION_UPDATE: '修改报修地点', LOCATION_ENABLE: '开放报修地点', LOCATION_DISABLE: '停用报修地点', SCOPE_CREATE: '新增后勤负责范围', SCOPE_UPDATE: '修改后勤负责范围', SCOPE_ENABLE: '启用后勤负责范围', SCOPE_DISABLE: '停用后勤负责范围' }
const typeNames: Record<string, string> = { USER: '账号', DEPARTMENT: '学院与部门', MAJOR: '专业', CLASS_GROUP: '行政班', REPAIR_LOCATION: '报修地点', REPAIR_SCOPE: '后勤负责范围', VENUE: '预约资源' }
const filterRoleOptions = computed(() => [
  { value: '', label: '全部身份' },
  ...(catalog.value?.roles.map(r => ({ value: r.code, label: r.name })) ?? []),
])
const filterDepartmentOptions = computed(() => [
  { value: '', label: '全部组织' },
  ...(catalog.value?.departments.map(d => ({ value: String(d.id), label: d.name })) ?? []),
])
const filterEnabledOptions = [
  { value: '', label: '全部状态' },
  { value: 'true', label: '启用' },
  { value: 'false', label: '停用' },
]
const formCampusOptions = computed(() => [
  ...(catalog.value?.campuses.map(c => ({ value: c.id, label: c.name })) ?? []),
])
const formDepartmentOptions = computed(() => [
  { value: 0, label: '请选择组织', disabled: true },
  ...departments.value.map(d => ({ value: d.id, label: d.name })),
])
const formClassGroupOptions = computed(() => [
  { value: 0, label: '请选择行政班', disabled: true },
  ...availableClasses.value.map(g => ({ value: g.id, label: g.name })),
])
const orgKindOptions = [
  { value: 'COLLEGE', label: '学院' },
  { value: 'ADMIN', label: '管理部门' },
]
const orgParentOptions = computed(() => {
  const list = [{ value: 0, label: '请选择', disabled: true }]
  if (orgType.value === 'majors') {
    return [
      ...list,
      ...(catalog.value?.departments.filter(d => d.kind === 'COLLEGE').map(d => ({ value: d.id, label: d.name })) ?? []),
    ]
  }
  return [
    ...list,
    ...(catalog.value?.majors.map(m => ({
      value: m.id,
      label: `${m.name} · ${catalog.value?.departments.find(d => d.id === m.departmentId)?.name ?? ''}`,
    })) ?? []),
  ]
})
const auditTypeOptions = computed(() => [
  { value: '', label: '全部记录' },
  ...Object.entries(typeNames).map(([key, name]) => ({ value: key, label: name })),
])
function roleName(code: string) { return catalog.value?.roles.find(role => role.code === code)?.name ?? code }
function organizationParent(row: FoundationDepartment | FoundationMajor | FoundationClass) {
  if ('departmentId' in row) return catalog.value?.departments.find(d => d.id === row.departmentId)?.name ?? '未设置学院'
  if ('majorId' in row) return catalog.value?.majors.find(m => m.id === row.majorId)?.name ?? '未设置专业'
  return row.kind === 'COLLEGE' ? '学院' : '管理部门'
}
async function ask(message: string, candidate: ManagedAccount | null = null) {
  if (resolveConfirmation) finishConfirmation(false)
  confirmation.value = message; stateCandidate.value = candidate; stateReason.value = ''; emergency.value = false
  return new Promise<boolean>(resolve => {
    resolveConfirmation = resolve
    void nextTick(() => { confirmationDialog.value?.showModal(); keepButton.value?.focus() })
  })
}
function finishConfirmation(accept: boolean) {
  confirmationDialog.value?.close()
  const resolve = resolveConfirmation; resolveConfirmation = null; confirmation.value = ''
  resolve?.(accept)
}
async function allowNavigation() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在提交，请等待完成后再离开。'; return false }
  return !dirty.value || await ask('当前表单有未保存内容。离开会放弃这些修改，请先保存，或确认继续。')
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
async function navigate(tabName: string, extra: Record<string, string | number | undefined> = {}) {
  await router.replace({ path: root, query: { tab: tabName, ...extra } })
}
async function loadCatalog() { catalog.value = await api<FoundationCatalog>(root + '/catalog') }
async function openPasswordReset(account: ManagedAccount) {
  if (!await allowNavigation()) return
  accountOpen.value = false; orgOpen.value = false; form.initialPassword = ''
  passwordResetAccount.value = { ...account }
}
async function passwordResetSaved() {
  passwordResetAccount.value = null
  await load()
  success.value = '密码已重置，旧会话已失效。请通过核验后的安全渠道交付临时密码；该人员下次登录须改密。'
}
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; accountOpen.value = false; orgOpen.value = false; form.initialPassword = ''
  try {
    if (!catalog.value) await loadCatalog()
    if (request !== sequence) return
    const page = Math.max(1, Number(route.query.page) || 1)
    if (tab.value === 'accounts') {
      Object.assign(filters, { q: String(route.query.q ?? ''), role: String(route.query.role ?? ''), departmentId: String(route.query.departmentId ?? ''), enabled: String(route.query.enabled ?? '') })
      const params = new URLSearchParams({ page: String(page), pageSize: '20' })
      for (const [key, value] of Object.entries(filters)) if (value) params.set(key, value)
      const result = await api<Paged<ManagedAccount>>(root + '/users?' + params)
      if (request === sequence) accounts.value = result
    } else if (tab.value === 'responsibility') {
      const result = await api<ResponsibilityException[]>(root + '/responsibility-exceptions')
      if (request === sequence) exceptions.value = result
    } else if (tab.value === 'audit') {
      auditType.value = String(route.query.entityType ?? '')
      const params = new URLSearchParams({ page: String(page), pageSize: '20' })
      if (auditType.value) params.set('entityType', auditType.value)
      if (route.query.entityId) params.set('entityId', String(route.query.entityId))
      const result = await api<Paged<FoundationAudit>>(root + '/audit?' + params)
      if (request === sequence) audits.value = result
    }
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function openAccount(account: ManagedAccount | null) {
  if (dirty.value && !await ask('打开另一份表单会放弃当前未保存内容。')) return
  hydrating = true; selected.value = account
  Object.assign(form, { username: account?.username ?? '', fullName: account?.fullName ?? '', identityNumber: account?.identityNumber ?? '',
    campusId: account?.campusId ?? catalog.value?.campuses[0]?.id ?? 0, departmentId: account?.departmentId ?? catalog.value?.departments[0]?.id ?? 0,
    classGroupId: account?.classGroupId ?? 0, roles: [...(account?.roles ?? [])], initialPassword: '', reason: '', version: account?.version ?? 0 })
  hydrating = false; accountBaseline = accountSnapshot(); accountOpen.value = true; orgOpen.value = false; error.value = ''; success.value = ''
  await nextTick(); document.getElementById('account-form-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function closeForms() {
  if (dirty.value && !await ask('取消编辑会放弃当前未保存内容。')) return
  accountOpen.value = false; orgOpen.value = false; form.initialPassword = ''
}
async function saveAccount() {
  if (busy.value || !form.roles.length) { if (!form.roles.length) error.value = '请至少选择一种身份或岗位。'; return }
  busy.value = true; error.value = ''; success.value = ''
  try {
    const common = { fullName: form.fullName, identityNumber: form.identityNumber, campusId: form.campusId, departmentId: form.departmentId, classGroupId: student.value ? form.classGroupId || null : null, roles: form.roles }
    const result = await api<ManagedAccount>(selected.value ? root + '/users/' + selected.value.id : root + '/users', {
      method: selected.value ? 'PUT' : 'POST', body: JSON.stringify(selected.value ? { ...common, version: form.version, reason: form.reason } : { ...common, username: form.username, initialPassword: form.initialPassword }),
    })
    accountOpen.value = false; form.initialPassword = ''; await load()
    success.value = `${result.fullName}的账号已${selected.value ? '更新' : '创建'}。角色或归属变更后，该人员需重新登录。`
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function inspectResponsibilities(account: ManagedAccount, scroll = true) {
  error.value = ''; impact.value = null; impactAccount.value = null
  try {
    const result = await api<ResponsibilityImpact>(root + '/users/' + account.id + '/responsibilities')
    if (result.version !== account.version) throw new Error('账号资料已变化，请刷新列表后重新检查。')
    impact.value = result; impactAccount.value = account
    if (scroll) { await nextTick(); document.getElementById('responsibility-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
    return true
  } catch (cause) { error.value = errorMessage(cause); return false }
}
async function changeState(account: ManagedAccount) {
  if (busy.value) return
  if (account.enabled && !await inspectResponsibilities(account, false)) return

  if (busy.value || !await ask(`${account.enabled ? '停用' : '启用'}${account.fullName}的账号。${account.enabled ? '停用后所有旧会话将失效，业务历史继续保留。' : '启用后需重新登录，旧会话不会恢复。'}`, account)) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(root + '/users/' + account.id + '/state', { method: 'POST', body: JSON.stringify({ enabled: !account.enabled, version: account.version, reason: stateReason.value, emergency: emergency.value }) })
    impact.value = null; impactAccount.value = null; await load(); success.value = `${account.fullName}的账号已${account.enabled ? '停用' : '启用'}，修改记录已保存。`
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function openOrganization(row: FoundationDepartment | FoundationMajor | FoundationClass | null) {
  if (dirty.value && !await ask('打开另一份表单会放弃当前未保存内容。')) return
  orgId.value = row?.id ?? null
  Object.assign(organization, { name: row?.name ?? '', kind: row && 'kind' in row ? row.kind : 'COLLEGE',
    parentId: row && 'departmentId' in row ? row.departmentId : row && 'majorId' in row ? row.majorId : orgType.value === 'majors' ? catalog.value?.departments.find(d => d.kind === 'COLLEGE')?.id ?? 0 : catalog.value?.majors[0]?.id ?? 0,
    version: row?.version ?? 0 })
  orgBaseline = JSON.stringify(organization); orgOpen.value = true; accountOpen.value = false; error.value = ''; success.value = ''
  await nextTick(); document.getElementById('organization-form-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function saveOrganization() {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const input = { name: organization.name, version: organization.version, ...(orgType.value === 'departments' ? { kind: organization.kind } : orgType.value === 'majors' ? { departmentId: organization.parentId } : { majorId: organization.parentId }) }
    await api(root + '/' + orgType.value + (orgId.value ? '/' + orgId.value : ''), { method: orgId.value ? 'PUT' : 'POST', body: JSON.stringify(input) })
    orgOpen.value = false; await loadCatalog(); success.value = `${organization.name}已保存，修改记录已更新。`
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function searchAccounts() { void navigate('accounts', { ...filters, page: 1 }) }
function changePage(page: number) { void router.replace({ query: { ...route.query, page } }) }
function showAudit(type: string, id: number) { void navigate('audit', { entityType: type, entityId: id }) }
watch(() => [form.departmentId, student.value], () => {
  if (hydrating) return
  if (!student.value || !availableClasses.value.some(group => group.id === form.classGroupId)) form.classGroupId = 0
})
watch(() => route.fullPath, () => { success.value = ''; void load() })
onBeforeRouteLeave(allowNavigation); onBeforeRouteUpdate(allowNavigation)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { sequence++; finishConfirmation(false); form.initialPassword = ''; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="foundation-page" :aria-busy="loading">
    <dialog ref="confirmationDialog" class="draft-confirm" aria-labelledby="foundation-confirm-title" @cancel.prevent="finishConfirmation(false)">
      <form @submit.prevent="finishConfirmation(true)">
<h2 id="foundation-confirm-title">{{ stateCandidate ? '确认账号状态变更' : '处理未保存的内容' }}</h2><p>{{ confirmation }}</p>
        <template v-if="stateCandidate?.enabled && impact">
          <ResponsibilityPanel :impact="impact" compact />
          <label class="emergency-choice"><input v-model="emergency" type="checkbox" />紧急停用：立即撤销访问，保留负责关系并在责任异常中安排接任</label>
          <p v-if="impact.blockingCount && !emergency" class="alert">尚有负责范围或独有待办，完成交接后可正常停用。</p>
        </template>
        <label v-if="stateCandidate">变更原因<input v-model="stateReason" required maxlength="200" placeholder="说明此次启用或停用的原因" /></label>
        <div class="form-actions"><button ref="keepButton" type="button" class="button compact" @click="finishConfirmation(false)">{{ stateCandidate ? '取消' : '继续编辑' }}</button><button class="button primary compact" :disabled="!!(stateCandidate?.enabled && impact?.blockingCount && !emergency)">{{ stateCandidate ? '确认变更' : '放弃修改并继续' }}</button></div>
      </form>
    </dialog>
    <div class="manager-heading"><span class="portal-context">管理员服务</span><h1>账号与组织管理</h1><p class="muted">维护校园人员、学院专业和行政班，查看身份与归属的修改记录。</p></div>
    <nav ref="foundationNavRef" class="learning-tabs" aria-label="基础管理栏目">
      <RouterLink :to="{ path: root, query: { tab: 'accounts' } }" :class="{ selected: tab === 'accounts' }"><Users :size="16" />账号与岗位</RouterLink>
      <RouterLink :to="{ path: root, query: { tab: 'organization' } }" :class="{ selected: tab === 'organization' }"><Building2 :size="16" />组织目录</RouterLink>
      <RouterLink :to="{ path: root, query: { tab: 'responsibility' } }" :class="{ selected: tab === 'responsibility' }"><ShieldCheck :size="16" />责任异常</RouterLink>
      <RouterLink :to="{ path: root, query: { tab: 'audit' } }" :class="{ selected: tab === 'audit' }"><History :size="16" />修改记录</RouterLink>
      <div class="learning-tabs-indicator" :style="foundationIndicatorStyle" aria-hidden="true"></div>
    </nav>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading && !catalog" class="loading-state" role="status">正在读取管理信息…</p>
    <button v-else-if="!catalog" class="button compact" @click="load">重新读取管理信息</button>
    <template v-else-if="catalog && tab === 'accounts'">
      <section v-if="impact && impactAccount" class="portal-panel"><div class="section-heading"><h2 id="responsibility-heading">{{ impactAccount.fullName }} · 岗位交接检查</h2><button class="text-button" @click="impact = null; impactAccount = null">收起</button></div><ResponsibilityPanel :impact="impact" /></section>
      <section class="portal-panel">
        <div class="section-heading"><div><h2>校园人员账号</h2><p class="muted">共 {{ accounts.total }} 个符合条件的账号</p></div><button class="button primary compact" :disabled="busy" @click="openAccount(null)"><Plus :size="15" />新建账号</button></div>
        <form class="foundation-filters" @submit.prevent="searchAccounts"><label class="filter-search">搜索人员<input v-model="filters.q" maxlength="100" placeholder="姓名、账号或学工号" /></label><label>身份与岗位<BaseSelect v-model="filters.role" :options="filterRoleOptions" /></label><label>所属组织<BaseSelect v-model="filters.departmentId" :options="filterDepartmentOptions" /></label><label>账号状态<BaseSelect v-model="filters.enabled" :options="filterEnabledOptions" /></label><button class="button compact" :disabled="busy"><Search :size="15" />查询</button></form>
        <div v-if="accounts.items.length" class="table-scroll" :class="{ 'is-loading': loading }"><table class="campus-table foundation-table"><thead><tr><th>人员</th><th>组织与行政班</th><th>身份与岗位</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="a in accounts.items" :key="a.id"><td><strong>{{ a.fullName }}</strong><small>账号 {{ a.username }}</small><small>学工号 {{ a.identityNumber }}</small></td><td>{{ a.departmentName ?? '未设置' }}<small>{{ a.className ?? '无行政班' }} · {{ a.campusName ?? '未设置校区' }}</small></td><td><span v-for="r in a.roles" :key="r" class="status-label">{{ roleName(r) }}</span></td><td><span class="status-label" :class="{ read: !a.enabled }">{{ a.enabled ? '启用' : '停用' }}</span><small v-if="a.passwordChangeRequired">下次登录须改密</small></td><td><div class="foundation-row-actions"><button class="text-button" :disabled="busy || loading" @click="inspectResponsibilities(a)">交接检查</button><button class="text-button" :disabled="busy" @click="openAccount(a)">编辑账号</button><button class="text-button" :disabled="busy || accountOpen || a.id === currentUser?.id" :title="a.id === currentUser?.id ? '当前登录管理员不能停用自己' : undefined" @click="changeState(a)">{{ a.enabled ? '停用' : '启用' }}</button><button class="text-button" :disabled="busy || !a.enabled || a.id === currentUser?.id" @click="openPasswordReset(a)">重置密码</button><button class="text-button" :disabled="busy" @click="showAudit('USER', a.id)">修改记录</button></div></td></tr></tbody></table></div>
        <p v-else class="empty-state">没有符合条件的账号，请调整搜索或筛选条件。</p>
      </section>
      <section v-if="accountOpen" class="portal-panel foundation-editor">
        <div class="section-heading"><div><span class="eyebrow">{{ selected ? selected.username : '管理员建档' }}</span><h2 id="account-form-heading">{{ selected ? '编辑人员账号' : '新建校园账号' }}</h2><p class="muted">身份与组织由管理员维护。角色或归属变更后，该人员需要重新登录。</p></div></div>
        <form class="editor-form" @submit.prevent="saveAccount">
<fieldset :disabled="busy" class="foundation-fields">
          <div class="form-grid"><label v-if="!selected">登录账号<input v-model="form.username" required pattern="[A-Za-z0-9][A-Za-z0-9_.-]{2,63}" maxlength="64" autocomplete="off" /></label><label>姓名<input v-model="form.fullName" required maxlength="100" /></label><label>学号 / 工号<input v-model="form.identityNumber" required maxlength="64" /></label><label>所属校区<BaseSelect v-model="form.campusId" :options="formCampusOptions" /></label></div>
          <fieldset class="role-choices"><legend><ShieldCheck :size="15" />身份与岗位</legend><label v-for="r in catalog.roles" :key="r.code" class="role-choice"><input v-model="form.roles" type="checkbox" :value="r.code" :disabled="selected?.id === currentUser?.id && r.code === 'ADMIN'" /><span><strong>{{ r.name }}</strong><small>{{ r.scope }}</small></span></label></fieldset>
          <div class="form-grid"><label>所属学院 / 部门<BaseSelect v-model="form.departmentId" :options="formDepartmentOptions" /></label><label v-if="student">所属行政班<BaseSelect v-model="form.classGroupId" :options="formClassGroupOptions" /><small class="muted">仅显示所选学院下的行政班</small></label></div>
          <label v-if="!selected">初始密码<input v-model="form.initialPassword" type="password" required minlength="10" maxlength="72" autocomplete="new-password" /><small class="muted">至少十位，包含字母和数字，最多 72 个 UTF-8 字节；仅用于首次建档，不在页面或记录中回显。</small></label>
          <label v-else>变更原因<textarea v-model="form.reason" required maxlength="200" rows="2" placeholder="说明人员资料、归属或岗位调整的原因" /></label>
        </fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : selected ? '保存修改' : '创建账号' }}</button><button type="button" class="text-button" :disabled="busy" @click="closeForms">取消编辑</button></div>
</form>
      </section>
      <details class="foundation-role-guide"><summary>查看岗位职责与权限说明</summary><div class="role-guide-grid"><article v-for="r in catalog.roles" :key="r.code"><h3>{{ r.name }}</h3><p>{{ r.scope }}</p><ul><li v-for="p in r.permissions" :key="p.code">{{ p.description }}</li></ul></article></div></details>
    </template>
    <template v-else-if="catalog && tab === 'organization'">
      <section class="portal-panel">
<div class="section-heading"><div><h2>校园组织目录</h2><p class="muted">学院与部门 → 专业 → 行政班；教学班在教务维护中单独管理。</p></div><button class="button primary compact" :disabled="busy" @click="openOrganization(null)"><Plus :size="15" />新建{{ orgType === 'departments' ? '组织' : orgLabel }}</button></div>
        <nav class="organization-tabs" aria-label="组织目录类型"><button v-for="item in [{ key: 'departments', label: '学院与部门' }, { key: 'majors', label: '专业' }, { key: 'class-groups', label: '行政班' }]" :key="item.key" class="button compact" :class="{ chosen: orgType === item.key }" :disabled="busy" @click="navigate('organization', { org: item.key })">{{ item.label }}</button></nav>
        <div v-if="organizationRows.length" class="table-scroll"><table class="campus-table foundation-table"><thead><tr><th>{{ orgLabel }}名称</th><th>{{ orgType === 'departments' ? '组织类型' : '所属' + (orgType === 'majors' ? '学院' : '专业') }}</th><th>操作</th></tr></thead><tbody><tr v-for="row in organizationRows" :key="row.id"><td>{{ row.name }}</td><td>{{ organizationParent(row) }}</td><td><div class="foundation-row-actions"><button class="text-button" :disabled="busy" @click="openOrganization(row)">编辑</button><button class="text-button" :disabled="busy" @click="showAudit(orgType === 'departments' ? 'DEPARTMENT' : orgType === 'majors' ? 'MAJOR' : 'CLASS_GROUP', row.id)">修改记录</button></div></td></tr></tbody></table></div><p v-else class="empty-state">尚未维护{{ orgLabel }}，可从上方新建。</p>
      </section>
      <section v-if="orgOpen" class="portal-panel foundation-editor"><h2 id="organization-form-heading">{{ orgId ? '编辑' : '新建' }}{{ orgType === 'departments' ? '学院或部门' : orgLabel }}</h2><p class="muted">已有人员或班级关联时，所属关系的调整会先进行校验。</p><form class="editor-form" @submit.prevent="saveOrganization"><fieldset :disabled="busy" class="foundation-fields"><label>名称<input v-model="organization.name" required maxlength="100" /></label><label v-if="orgType === 'departments'">组织类型<BaseSelect v-model="organization.kind" :options="orgKindOptions" /></label><label v-else>所属{{ orgType === 'majors' ? '学院' : '专业' }}<BaseSelect v-model="organization.parentId" :options="orgParentOptions" /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存组织信息' }}</button><button type="button" class="text-button" :disabled="busy" @click="closeForms">取消编辑</button></div></form></section>
    </template>
    <section v-else-if="catalog && tab === 'responsibility'" class="portal-panel">
      <div class="section-heading"><div><h2>责任异常</h2><p class="muted">按当前账号、岗位和负责范围检查；接任后刷新即可重新确认。</p></div><button class="button compact" :disabled="loading" @click="load">刷新检查</button></div>
      <p class="content-context-note">请先给接任人员分配所需岗位，再由对应配置人员调整负责范围。维修在办任务还需由有效派工人员重新派单。此处仅显示汇总数量。</p>
      <div class="responsibility-exceptions"><article v-for="item in exceptions" :key="item.code" class="responsibility-exception" :class="{ warning: item.count > 0 }"><div><strong>{{ item.name }}</strong><p class="muted">{{ item.count }} {{ item.unit }}</p></div><RouterLink v-if="currentUser?.permissions.includes(item.permission)" :to="item.path" class="text-button">前往配置</RouterLink><small v-else class="muted">需由具备 {{ item.permission }} 权限的人员配置</small></article></div>
    </section>
    <section v-else-if="catalog && tab === 'audit'" class="portal-panel">
      <div class="section-heading"><div><h2>人员与组织修改记录</h2><p class="muted">保留操作人员、变更前后信息和原因；共 {{ audits.total }} 条记录。</p></div></div>
      <form class="foundation-filters audit-filters" @submit.prevent="navigate('audit', { entityType: auditType, page: 1 })"><label>记录类型<BaseSelect v-model="auditType" :options="auditTypeOptions" /></label><button class="button compact">查询</button><RouterLink v-if="route.query.entityId" :to="{ path: root, query: { tab: 'audit' } }" class="text-button">查看全部修改记录</RouterLink></form>
      <p v-if="!audits.items.length" class="empty-state">当前没有对应的修改记录。</p><div :class="{ 'is-loading': loading }"><article v-for="record in audits.items" :key="record.id" class="foundation-audit"><div class="notice-heading"><h3>{{ actionNames[record.action] ?? '管理操作' }}</h3><span class="status-label">{{ typeNames[record.entityType] }}</span></div><p class="preserve-lines">{{ record.summary }}</p><p class="muted">原因：{{ record.reason }}</p><small>{{ record.actorName }} · {{ record.createdAt.slice(0, 16).replace('T', ' ') }}</small></article></div>
    </section>
    <div v-if="catalog && ['accounts', 'audit'].includes(tab) && shownPage.total > 0" class="foundation-pagination"><span>第 {{ shownPage.page }} / {{ totalPages }} 页</span><div><button class="button compact" :disabled="busy || loading || shownPage.page <= 1" @click="changePage(shownPage.page - 1)">上一页</button><button class="button compact" :disabled="busy || loading || shownPage.page >= totalPages" @click="changePage(shownPage.page + 1)">下一页</button></div></div>
    <ManagedPasswordReset v-if="passwordResetAccount" :account="passwordResetAccount" @close="passwordResetAccount = null" @saved="passwordResetSaved" />
    <p class="data-note">当前人员资料为毕业设计演示数据。账号停用保留业务历史，角色分配不自动授予其他岗位的业务权限。</p>
  </div>
</template>

<style scoped>
.emergency-choice { display: flex; gap: 8px; align-items: flex-start; margin: 16px 0; }
.emergency-choice input { width: auto; margin-top: 4px; }
.draft-confirm { width: min(740px, calc(100vw - 32px)); max-height: calc(100dvh - 40px); overflow: auto; }
.responsibility-exceptions { display: grid; gap: 10px; }
.responsibility-exception { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px; border: 1px solid var(--line, #dfe5ec); border-radius: 8px; }
.responsibility-exception.warning { border-left: 3px solid #b57c28; background: #fffcf5; }
.responsibility-exception p { margin: 6px 0 0; }
@media(max-width: 640px) { .responsibility-exception { align-items: flex-start; flex-direction: column; } }
</style>
