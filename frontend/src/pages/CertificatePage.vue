<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { FileCheck2, History, Plus, RefreshCw, Download } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { useEditProtection } from '../composables/useEditProtection'
import { certificateStatuses, certificateActions } from '../certificates'
import type { CertificateEligibility, CertificateRequest, CertificateRevision } from '../certificates'
import type { Paged } from '../foundation'

const route = useRoute(), router = useRouter()
const review = computed(() => route.path.startsWith('/student-affairs'))
const mode = computed(() => review.value ? 'review' : 'mine'), root = computed(() => '/certificates/' + mode.value)
const historyView = computed(() => route.query.tab === 'history')
const eligibility = ref<CertificateEligibility | null>(null)
const items = ref<Paged<CertificateRequest>>({ items: [], total: 0, page: 1, pageSize: 20 })
const history = ref<Paged<CertificateRevision>>({ items: [], total: 0, page: 1, pageSize: 10 })
const selected = ref<CertificateRequest | null>(null)
const status = ref(''), loading = ref(true), busy = ref(false), editing = ref(false), error = ref(''), success = ref(''), reviewComment = ref('')
const form = reactive({ requestKey: '', purpose: '', deliveryMethod: 'DIGITAL', identityConfirmed: false, version: 0 })
let baseline = '', sequence = 0
const dirty = computed(() => editing.value && JSON.stringify(form) !== baseline || !!reviewComment.value.trim())
const editable = computed(() => selected.value && ['DRAFT', 'RETURNED'].includes(selected.value.status))
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null)
const { confirmMessage, confirmKind, dialogComment, ask, finish, allowLeave } = useEditProtection(busy, dirty, dialog, cancelButton)
function time(value: string) { return value.slice(0, 16).replace('T', ' ') }
async function load() {
  const request = ++sequence; loading.value = true; error.value = ''; editing.value = false; reviewComment.value = ''
  try {
    if (!review.value) {
      try { eligibility.value = await api<CertificateEligibility>('/certificates/mine/eligibility') }
      catch (cause) { eligibility.value = null; error.value = errorMessage(cause) }
    }
    status.value = String(route.query.status ?? (review.value ? 'SUBMITTED' : ''))
    const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' })
    if (status.value) params.set('status', status.value)
    const list = await api<Paged<CertificateRequest>>(root.value + '?' + params)
    if (request !== sequence) return
    items.value = list; selected.value = null
    if (route.query.id) {
      const detail = await api<CertificateRequest>(root.value + '/' + String(route.query.id))
      if (request !== sequence) return
      selected.value = detail
      if (historyView.value) {
        const records = await api<Paged<CertificateRevision>>(`${root.value}/${detail.id}/history?page=${Math.max(1, Number(route.query.historyPage) || 1)}&pageSize=10`)
        if (request === sequence) history.value = records
      }
    }
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function refresh() { if (await allowLeave()) { success.value = ''; await load() } }
async function choose(item: CertificateRequest) {
  if (String(item.id) === route.query.id && !historyView.value) { if (await allowLeave()) await load() }
  else await router.replace({ query: { ...route.query, id: String(item.id), tab: 'details', historyPage: '1' } })
}
async function view(tab: string) { await router.replace({ query: { ...route.query, tab, historyPage: '1' } }) }
async function search() { await router.replace({ query: { ...route.query, status: status.value, page: '1' } }) }
async function page(number: number, record = false) { await router.replace({ query: { ...route.query, [record ? 'historyPage' : 'page']: String(number) } }) }
async function edit(item: CertificateRequest | null = null) {
  if (!await allowLeave()) return
  if (!eligibility.value) { error.value = '请先核对本人登记资料，完善资料后再申请。'; return }
  selected.value = item; reviewComment.value = ''; error.value = ''; success.value = ''
  Object.assign(form, { requestKey: item?.requestKey ?? crypto.randomUUID(), purpose: item?.purpose ?? '', deliveryMethod: 'DIGITAL', identityConfirmed: false, version: item?.version ?? 0 })
  baseline = JSON.stringify(form); editing.value = true
  await nextTick(); document.getElementById('certificate-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function cancelEdit() { if (await allowLeave()) await load() }
async function save() {
  busy.value = true; error.value = ''; success.value = ''
  try {
    const result = await api<CertificateRequest>(selected.value ? root.value + '/' + selected.value.id : root.value, { method: selected.value ? 'PUT' : 'POST', body: JSON.stringify({ ...form, expectedIdentity: eligibility.value?.identity }) })
    editing.value = false; baseline = JSON.stringify(form); busy.value = false
    if (route.query.id === String(result.id)) await load()
    else await router.replace({ query: { ...route.query, id: String(result.id), tab: 'details', historyPage: '1' } })
    success.value = '资料已保存，核对后再提交审核。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function transition(action: 'submit' | 'withdraw') {
  const item = selected.value; if (!item) return
  if (!await ask(action === 'submit' ? `确认提交证明申请 #${item.id}？由所属学院的学生事务人员核对登记信息与用途。` : `确认撤回证明申请 #${item.id}？历史保留，重新办理须新建申请。`, action)) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`${root.value}/${item.id}/${action}`, { method: 'POST', body: JSON.stringify({ version: item.version, comment: action === 'submit' ? '本人核对登记资料后提交' : dialogComment.value }) })
    await load(); success.value = action === 'submit' ? '申请已提交，可在我的办事中查询进度。' : '申请已撤回，原待办已关闭。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function decide(action: string) {
  const item = selected.value, comment = reviewComment.value.trim(); if (!item || !comment) return
  const label = action === 'APPROVE' ? '通过并生成演示证明' : action === 'RETURN' ? '退回补交' : '拒绝申请'
  if (!await ask(`确认对 ${item.identity.studentName} 的证明申请 #${item.id} 执行“${label}”？意见与结果将保留。`, 'decision')) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`${root.value}/${item.id}/decision`, { method: 'POST', body: JSON.stringify({ version: item.version, action, comment }) })
    reviewComment.value = ''; await load(); success.value = '处理已保存，学生已收到消息，原待办已关闭。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
onMounted(() => { void load() }); watch(() => route.fullPath, () => { void load() })
</script>

<template>
  <div class="leave-page">
    <dialog ref="dialog" class="foundation-dialog" aria-labelledby="certificate-dialog-heading" @cancel.prevent="finish(false)">
      <h2 id="certificate-dialog-heading">{{ confirmKind === 'leave' ? '处理未保存的输入' : '核对本次操作' }}</h2><p>{{ confirmMessage }}</p>
      <label v-if="confirmKind === 'withdraw'">撤回原因<textarea v-model="dialogComment" rows="2" maxlength="1000" /></label>
      <div class="form-actions"><button ref="cancelButton" class="button compact" @click="finish(false)">{{ confirmKind === 'leave' ? '继续编辑' : '取消' }}</button><button class="button primary compact" :disabled="confirmKind === 'withdraw' && !dialogComment.trim()" @click="finish(true)">{{ confirmKind === 'leave' ? '放弃修改并继续' : '确认操作' }}</button></div>
    </dialog>
    <div class="page-heading-row"><div><span class="portal-context">{{ review ? '学生事务处理' : '学生办事' }}</span><h1>{{ review ? '学院证明申请处理' : '在校证明申请' }}</h1><p class="muted">{{ review ? '核对所属学院的登记资料与申请用途，记录意见并生成演示结果。' : '确认本人登记信息，申请并下载受控的演示证明。' }}</p></div><div class="form-actions"><button class="button compact" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" />刷新记录</button><button v-if="!review" class="button primary compact" :disabled="busy || loading || !eligibility" @click="edit()"><Plus :size="15" />新建证明申请</button></div></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="!review && eligibility" class="leave-eligibility">{{ eligibility.identity.departmentName }} · {{ eligibility.message }}</p>
    <form class="foundation-filters leave-filters" @submit.prevent="search"><label>申请状态<select v-model="status" :disabled="busy"><option value="">全部记录</option><option v-for="(label, key) in certificateStatuses" v-show="!review || key !== 'DRAFT'" :key="key" :value="key">{{ label }}</option></select></label><button class="button compact" :disabled="busy || loading">查询</button><p class="muted">{{ review ? '待办只包含本学院已提交且可由本人处理的申请。' : '草稿保存后再提交，补交沿用原办理记录。' }}</p></form>
    <section class="portal-panel leave-list" aria-label="证明申请列表"><div class="section-heading"><h2><FileCheck2 :size="17" />{{ review ? '学院证明申请' : '本人证明申请' }}</h2><span class="muted">共 {{ items.total }} 条</span></div><p v-if="loading" role="status">正在读取申请…</p><p v-else-if="!items.items.length" class="empty-state">{{ review ? '当前筛选下没有可处理申请。' : '当前筛选下没有记录，可新建证明草稿。' }}</p><div v-else class="table-scroll"><table class="campus-table foundation-table"><thead><tr><th>申请</th><th v-if="review">学生与行政班</th><th>状态</th><th>最近更新</th><th>操作</th></tr></thead><tbody><tr v-for="item in items.items" :key="item.id"><td><strong>在校证明 #{{ item.id }}</strong><small>线上下载</small></td><td v-if="review">{{ item.identity.studentName }}<small>{{ item.identity.identityNumber }} · {{ item.identity.className }}</small></td><td><span class="status-label">{{ certificateStatuses[item.status] }}</span></td><td>{{ time(item.updatedAt) }}</td><td><button class="text-button" :disabled="busy" @click="choose(item)">{{ review ? '查看与处理' : '查看申请' }}</button></td></tr></tbody></table></div><div class="pagination"><span>第 {{ items.page }} 页</span><button class="button compact" :disabled="busy || loading || items.page <= 1" @click="page(items.page - 1)">上一页</button><button class="button compact" :disabled="busy || loading || items.page * items.pageSize >= items.total" @click="page(items.page + 1)">下一页</button></div></section>
    <section v-if="editing && eligibility" class="portal-panel leave-detail" aria-label="证明资料编辑"><h2 id="certificate-editor-heading">{{ selected ? '修改证明申请' : '新建证明草稿' }}</h2><p class="muted">登记资料来自本人账号；如有错误，请先联系管理员核对。保存时采用最新资料。</p><dl class="resource-facts"><div><dt>姓名 / 学号</dt><dd>{{ eligibility.identity.studentName }} / {{ eligibility.identity.identityNumber }}</dd></div><div><dt>学院 / 专业</dt><dd>{{ eligibility.identity.departmentName }} / {{ eligibility.identity.majorName }}</dd></div><div><dt>行政班</dt><dd>{{ eligibility.identity.className }}</dd></div><div><dt>校区</dt><dd>{{ eligibility.identity.campusName }}</dd></div></dl><form class="editor-form certificate-form" @submit.prevent="save"><label>申请用途<textarea v-model="form.purpose" required maxlength="300" rows="4" :disabled="busy" placeholder="说明证明用途，避免填写无关的个人敏感信息" /></label><p class="muted">领取方式：线上下载 PDF。文件将标注“毕业设计演示”，用于项目功能展示。</p><label class="confirmation-check"><input v-model="form.identityConfirmed" type="checkbox" required :disabled="busy" /><span>已核对以上本人登记资料，了解结果为演示证明。</span></label><div class="form-actions"><button class="button primary compact" :disabled="busy">保存证明资料</button><button class="button compact" type="button" :disabled="busy" @click="cancelEdit">取消编辑</button></div></form></section>
    <section v-else-if="selected" class="portal-panel leave-detail" aria-label="证明申请详情">
<div class="section-heading"><div><span class="eyebrow">申请 #{{ selected.id }} · {{ selected.identity.departmentName }}</span><h2>{{ review ? '在校证明 · ' + selected.identity.studentName : '在校证明申请' }}</h2></div><span class="status-label">{{ certificateStatuses[selected.status] }} · V{{ selected.version }}</span></div><nav class="learning-tabs" aria-label="证明详情栏目"><button :class="{ active: !historyView }" :disabled="busy" @click="view('details')">申请资料</button><button :class="{ active: historyView }" :disabled="busy" @click="view('history')"><History :size="15" />办理记录</button></nav>
      <template v-if="historyView"><p class="muted">保存、补交和处理均保留当时的资料，共 {{ history.total }} 条。</p><div class="leave-history"><article v-for="record in history.items" :key="record.id"><div class="section-heading"><h3>{{ certificateActions[record.action] }} · V{{ record.version }}</h3><span class="status-label">{{ certificateStatuses[record.status] }}</span></div><p>{{ record.comment }}</p><small>{{ record.actorName }} · {{ time(record.createdAt) }}</small><details><summary>查看当时登记资料与用途</summary><p>{{ record.studentName }} · {{ record.identityNumber }}</p><p>{{ record.departmentName }} / {{ record.majorName }} / {{ record.className }} / {{ record.campusName }}</p><p class="preserve-lines">{{ record.purpose }}</p></details></article></div><div class="pagination"><span>第 {{ history.page }} 页</span><button class="button compact" :disabled="busy || loading || history.page <= 1" @click="page(history.page - 1, true)">上一页</button><button class="button compact" :disabled="busy || loading || history.page * history.pageSize >= history.total" @click="page(history.page + 1, true)">下一页</button></div></template>
      <template v-else>
<dl class="resource-facts"><div><dt>姓名 / 学号</dt><dd>{{ selected.identity.studentName }} / {{ selected.identity.identityNumber }}</dd></div><div><dt>学院 / 专业</dt><dd>{{ selected.identity.departmentName }} / {{ selected.identity.majorName }}</dd></div><div><dt>行政班 / 校区</dt><dd>{{ selected.identity.className }} / {{ selected.identity.campusName }}</dd></div><div><dt>领取方式</dt><dd>线上下载 PDF</dd></div><div class="leave-wide"><dt>申请用途</dt><dd class="preserve-lines">{{ selected.purpose }}</dd></div><div class="leave-wide"><dt>最近处理意见</dt><dd class="preserve-lines">{{ selected.processingComment ?? '尚无处理意见' }}</dd></div></dl>
        <div v-if="selected.status === 'COMPLETED'" class="certificate-result"><FileCheck2 :size="24" /><div><h3>证明已生成，可下载</h3><p>{{ selected.documentNumber }} · {{ selected.issuedAt ? time(selected.issuedAt) : '' }}</p><small>结果保留生成时的资料，不随之后的账号修改而变化。</small></div><a class="button primary compact" :href="`/api/certificates/${mode}/${selected.id}/result`"><Download :size="15" />下载演示证明</a></div>
        <div v-if="!review" class="form-actions"><button v-if="editable" class="button compact" :disabled="busy || loading" @click="edit(selected)">修改申请资料</button><button v-if="editable" class="button primary compact" :disabled="busy || loading || !eligibility?.canSubmit" @click="transition('submit')">{{ selected.status === 'RETURNED' ? '补交后重新提交' : '提交审核' }}</button><button v-if="['DRAFT','SUBMITTED','RETURNED'].includes(selected.status)" class="text-button" :disabled="busy || loading" @click="transition('withdraw')">撤回申请</button></div>
        <div v-if="review && selected.status === 'SUBMITTED'" class="leave-review-form"><label>处理意见<textarea v-model="reviewComment" maxlength="1000" rows="3" :disabled="busy" placeholder="填写核对结果；退回时说明需要补充的内容" /></label><div class="form-actions"><button class="button primary compact" :disabled="busy || loading || !reviewComment.trim()" @click="decide('APPROVE')">已核对，生成证明</button><button class="button compact" :disabled="busy || loading || !reviewComment.trim()" @click="decide('RETURN')">退回补交</button><button class="button compact" :disabled="busy || loading || !reviewComment.trim()" @click="decide('REJECT')">拒绝申请</button></div></div>
        <p v-if="['COMPLETED','REJECTED','WITHDRAWN'].includes(selected.status)" class="muted">该记录已关闭，资料与历史保留。需要重新办理时请新建申请。</p>
      </template>
    </section>
    <p class="data-note">毕业设计演示流程，结果非学校正式证明。资料和下载仅向本人及当前授权的学院学生事务人员开放。</p>
  </div>
</template>

