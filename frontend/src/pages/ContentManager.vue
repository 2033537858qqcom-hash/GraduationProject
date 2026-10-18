<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Check, FilePlus2, History, RefreshCw, Send } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import BaseSelect from '../components/BaseSelect.vue'
import { contentActions } from '../lib/contentWorkflow'
import { currentUser } from '../stores/auth'
import type { ContentHistory, ContentRevision, ManagedContent } from '../types'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const route = useRoute(), router = useRouter()
const items = ref<ManagedContent[]>([]), selection = ref<ManagedContent | null>(null)
const editing = ref(false), busy = ref(false), loading = ref(true), historyLoading = ref(false)
const error = ref(''), success = ref('')
const editable = computed(() => !!currentUser.value?.permissions.includes('content:edit'))
const actions = computed(() => selection.value ? contentActions(selection.value, currentUser.value) : null)
const statusNames: Record<string, string> = { DRAFT: '草稿', IN_REVIEW: '待审核', PUBLISHED: '已发布', REJECTED: '已驳回', WITHDRAWN: '已撤下' }
const actionNames: Record<string, string> = { CREATED: '创建草稿', UPDATED: '保存工作稿', SUBMITTED: '送交审核', RECALLED: '撤回送审', REJECTED: '审核驳回', REVISION_STARTED: '开始更正', PUBLISHED: '审核发布', UNPUBLISHED: '撤下公开内容', BASELINE: '迁移前版本' }
const filterStatusOptions = computed(() => [
  { value: '', label: '全部状态' },
  ...Object.entries(statusNames).map(([key, name]) => ({ value: key, label: name })),
])
const filterCategoryOptions = [
  { value: '', label: '全部栏目' },
  { value: 'about', label: '学校概况' },
  { value: 'news', label: '校园新闻' },
  { value: 'notice', label: '通知公告' },
]
const formCategoryOptions = [
  { value: 'notice', label: '通知公告' },
  { value: 'news', label: '校园新闻' },
  { value: 'about', label: '学校概况' },
]
const formSourceTypeOptions = [
  { value: 'DEMO', label: '演示内容' },
  { value: 'PUBLIC_SOURCE', label: '学校公开资料' },
]
const filters = reactive({ q: '', status: '', category: '' })
const filtered = computed(() => items.value.filter(item => (!filters.status || item.status === filters.status) && (!filters.category || item.category === filters.category) && (item.title + ' ' + item.slug).toLowerCase().includes(filters.q.toLowerCase().trim())))
const view = computed(() => route.query.view === 'history' ? 'history' : route.query.view === 'live' ? 'live' : 'work')
const contentNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: contentIndicatorStyle } = useSlidingIndicator(
  contentNavRef,
  () => view.value
)
const form = reactive({ slug: '', category: 'notice', title: '', summary: '', body: '', sourceUrl: '', sourceType: 'DEMO', version: 0 })
let baseline = '', sequence = 0, historySequence = 0
const snapshot = () => JSON.stringify(form)
const dirty = computed(() => editing.value && snapshot() !== baseline)
const history = ref<ContentHistory>({ items: [], total: 0, page: 1, pageSize: 20 })
const revision = ref<ContentRevision | null>(null)
const shown = computed(() => view.value === 'live' ? selection.value?.live : selection.value)
type Action = 'submit' | 'publish' | 'recall' | 'reject' | 'revise' | 'unpublish'
const reasonActions: Action[] = ['recall', 'reject', 'revise', 'unpublish']
const dialog = ref<HTMLDialogElement | null>(null), cancelButton = ref<HTMLButtonElement | null>(null)
const confirmation = ref(''), confirmationTitle = ref(''), confirmationAction = ref<Action | null>(null), reason = ref('')
let resolveConfirmation: ((accept: boolean) => void) | null = null
function categoryName(category: string) { return category === 'about' ? '学校概况' : category === 'news' ? '校园新闻' : '通知公告' }
function formatTime(value: string) { return value.slice(0, 16).replace('T', ' ') }
async function ask(message: string, action: Action | null = null) {
  if (resolveConfirmation) finishConfirmation(false)
  confirmation.value = message; confirmationAction.value = action; reason.value = ''
  confirmationTitle.value = action ? ({ submit: '确认送交审核', publish: '确认审核发布', recall: '撤回送审', reject: '驳回工作稿', revise: '开始内容更正', unpublish: '撤下官网内容' })[action] : '处理未保存的内容'
  return new Promise<boolean>(resolve => {
    resolveConfirmation = resolve
    void nextTick(() => { dialog.value?.showModal(); cancelButton.value?.focus() })
  })
}
function finishConfirmation(accept: boolean) { dialog.value?.close(); const resolve = resolveConfirmation; resolveConfirmation = null; resolve?.(accept) }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在提交，请等待完成后再离开。'; return false }
  return !dirty.value || await ask('当前内容尚未保存，离开会放弃这些输入。请先保存草稿，或确认放弃。')
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
async function loadHistory(page = 1) {
  if (!selection.value) return
  const id = selection.value.id, request = ++historySequence
  historyLoading.value = true; revision.value = null
  try {
    const result = await api<ContentHistory>(`/admin/content/${id}/history?page=${page}&pageSize=20`)
    if (request === historySequence && selection.value?.id === id) history.value = result
  } catch (cause) { if (request === historySequence) error.value = errorMessage(cause) }
  finally { if (request === historySequence) historyLoading.value = false }
}
async function load() {
  const request = ++sequence
  loading.value = true; error.value = ''; editing.value = false; revision.value = null
  try {
    const result = await api<ManagedContent[]>('/admin/content')
    if (request !== sequence) return
    items.value = result
    Object.assign(filters, { q: String(route.query.q ?? ''), status: String(route.query.status ?? ''), category: String(route.query.category ?? '') })
    selection.value = items.value.find(item => String(item.id) === route.query.id) ?? null
    if (view.value === 'history' && selection.value) await loadHistory()
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function refresh() { if (await allowLeave()) { success.value = ''; await load() } }
async function choose(item: ManagedContent) {
  if (route.query.id === String(item.id) && (!route.query.view || route.query.view === 'work')) { if (await allowLeave()) await load() }
  else await router.replace({ query: { ...route.query, id: String(item.id), view: 'work' } })
}
async function changeView(next: string) { await router.replace({ query: { ...route.query, view: next } }) }
async function filterItems() { await router.replace({ query: { ...route.query, ...filters } }) }
async function edit(item?: ManagedContent) {
  if (!await allowLeave()) return
  selection.value = item ?? null
  Object.assign(form, item ? { slug: item.slug, category: item.category, title: item.title, summary: item.summary, body: item.body, sourceUrl: item.sourceUrl ?? '', sourceType: item.sourceType, version: item.version } :
    { slug: '', category: 'notice', title: '', summary: '', body: '', sourceUrl: '', sourceType: 'DEMO', version: 0 })
  baseline = snapshot(); editing.value = true; error.value = ''; success.value = ''
  await nextTick(); document.getElementById('content-editor-title')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
async function cancelEdit() { if (await allowLeave()) editing.value = false }
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const id = selection.value?.id
    const saved = await api<ManagedContent>('/admin/content' + (id ? '/' + id : ''), { method: id ? 'PUT' : 'POST', body: JSON.stringify({ ...form, sourceUrl: form.sourceUrl || null }) })
    editing.value = false; await load(); selection.value = saved; busy.value = false
    await router.replace({ query: { ...route.query, id: String(saved.id), view: 'work' } })
    success.value = '草稿已保存。送审并通过审核后才会更新官网。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function transition(action: Action) {
  if (busy.value || !selection.value) return
  const item = selection.value
  const messages: Record<Action, string> = {
    submit: `将工作稿 V${item.version} 送交审核。${item.live?.visible ? '官网继续显示原公开版本。' : '审核通过前不会公开显示。'}`,
    publish: `请确认已核对正文和资料来源。批准工作稿 V${item.version} 后，官网将显示这版内容。`,
    recall: '撤回审核中的工作稿，返回草稿继续编辑。已经公开的版本保持可访问。',
    reject: '说明需要修改的问题，编辑人员将依据你的意见修改并重新送审。',
    revise: item.status === 'WITHDRAWN' ? '重新建立工作稿。保存并经其他审核人员批准后才能恢复公开。' : '开始更正并由你负责编辑；原公开版本持续显示，审核通过后更新。',
    unpublish: `撤下官网公开版本 V${item.live?.version}「${item.live?.title}」。官网列表及详情将停止显示；已有工作稿内容和历史记录保留，恢复须重新送审。`,
  }
  if (!await ask(messages[action], action)) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    const result = await api<ManagedContent>(`/admin/content/${item.id}/${action}`, { method: 'POST', body: JSON.stringify({ version: item.version, reason: reason.value }) })
    await load(); selection.value = result; busy.value = false
    if (action === 'revise') await edit(result)
    success.value = ({ submit: '已送交审核。', publish: '审核通过，官网已显示新版本。', recall: '已撤回送审，可以继续编辑。', reject: '已驳回，修改意见已记录。', revise: '更正稿已建立，请修改内容并保存。', unpublish: '公开内容已撤下，历史记录继续保留。' })[action]
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function showRevision(version: number) {
  if (!selection.value) return
  const id = selection.value.id, request = ++historySequence
  historyLoading.value = true; error.value = ''
  try {
    const result = await api<ContentRevision>(`/admin/content/${id}/revisions/${version}`)
    if (request === historySequence && selection.value?.id === id) {
      revision.value = result
      await nextTick(); document.getElementById('content-revision-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }
  catch (cause) { if (request === historySequence) error.value = errorMessage(cause) }
  finally { if (request === historySequence) historyLoading.value = false }
}
watch(() => route.fullPath, () => { success.value = ''; void load() })
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { sequence++; historySequence++; finishConfirmation(false); window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="content-management" :aria-busy="loading">
    <dialog ref="dialog" class="draft-confirm" aria-labelledby="content-confirm-title" @cancel.prevent="finishConfirmation(false)">
      <form @submit.prevent="finishConfirmation(true)">
        <h2 id="content-confirm-title">{{ confirmationTitle }}</h2><p>{{ confirmation }}</p>
        <label v-if="confirmationAction && reasonActions.includes(confirmationAction)">操作原因<textarea v-model="reason" required maxlength="480" rows="3" placeholder="填写具体原因，供编辑、审核与后续追溯使用" /></label>
        <div class="form-actions"><button ref="cancelButton" type="button" class="button compact" @click="finishConfirmation(false)">{{ confirmationAction ? '取消' : '继续编辑' }}</button><button class="button primary compact">{{ confirmationAction ? '确认操作' : '放弃修改并继续' }}</button></div>
      </form>
    </dialog>
    <div class="section-heading manager-heading"><div><span class="portal-context">官网内容管理</span><h1>内容审核与发布</h1><p class="muted">编辑工作稿、处理审核意见，维护官网公开版本。</p></div><div class="form-actions"><button class="button compact" :disabled="busy || loading" @click="refresh"><RefreshCw :size="15" />刷新目录</button><button v-if="editable" class="button primary compact" :disabled="busy" @click="edit()"><FilePlus2 :size="15" />新建草稿</button></div></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <form class="content-filters foundation-filters" @submit.prevent="filterItems">
      <label class="filter-search">搜索内容<input v-model="filters.q" maxlength="160" placeholder="标题或内容标识" /></label>
      <label>稿件状态<BaseSelect v-model="filters.status" :options="filterStatusOptions" /></label>
      <label>所属栏目<BaseSelect v-model="filters.category" :options="filterCategoryOptions" /></label><button class="button compact" :disabled="busy">查询</button>
    </form>
    <div class="content-manager-grid">
      <section class="portal-panel content-list" aria-label="内容目录">
        <div class="content-list-heading"><h2>内容目录</h2><small>{{ filtered.length }} 篇内容</small></div>
        <p v-if="loading && !filtered.length" class="loading-state" role="status">正在读取内容…</p><p v-else-if="!loading && !filtered.length" class="empty-state">没有符合条件的内容，可调整筛选或新建草稿。</p>
        <div v-else class="content-list-scroll" :class="{ 'is-loading': loading }">
          <button v-for="item in filtered" :key="item.id" class="content-list-item" :class="{ chosen: selection?.id === item.id }" :disabled="busy" :aria-pressed="selection?.id === item.id" @click="choose(item)">
            <strong>{{ item.title }}</strong><small>{{ categoryName(item.category) }} · {{ statusNames[item.status] }}</small><small>{{ item.live?.visible ? '官网显示 V' + item.live.version : item.live ? '已停止公开' : '尚未公开' }}{{ item.createdBy === currentUser?.id ? ' · 我负责的稿件' : '' }}</small>
          </button>
        </div>
      </section>
      <section class="portal-panel content-editor">
        <form v-if="editing" class="editor-form" @submit.prevent="save">
          <h2 id="content-editor-title">{{ selection ? '编辑工作稿' : '新建草稿' }}</h2><p class="muted">保存草稿后可继续编辑；送审通过后才会公开。未保存的输入请在离开前保存。</p>
          <p v-if="selection?.reviewReason" class="content-review-note">最近处理意见：{{ selection.reviewReason }}</p>
          <fieldset class="foundation-fields" :disabled="busy">
<label>标题<input v-model="form.title" maxlength="160" required /></label>
            <div class="form-grid"><label>栏目<BaseSelect v-model="form.category" :options="formCategoryOptions" /></label><label>内容标识<input v-model="form.slug" pattern="[a-z0-9][a-z0-9-]{1,79}" maxlength="80" :readonly="!!selection?.live" placeholder="如 campus-notice" required /><small v-if="selection?.live" class="muted">公开链接固定，标题和正文可通过更正稿修改。</small></label></div>
            <label>摘要<textarea v-model="form.summary" rows="2" maxlength="400" required /></label><label>正文<textarea v-model="form.body" rows="8" maxlength="20000" required /></label>
            <div class="form-grid"><label>资料类型<BaseSelect v-model="form.sourceType" :options="formSourceTypeOptions" /></label><label>来源链接<input v-model="form.sourceUrl" type="url" maxlength="1000" :required="form.sourceType === 'PUBLIC_SOURCE'" placeholder="https://" /></label></div>
          </fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存草稿' }}</button><button type="button" class="button compact" :disabled="busy" @click="cancelEdit">取消编辑</button></div>
        </form>
        <template v-else-if="selection">
          <div class="content-version-bar"><span class="status-label">{{ statusNames[selection.status] }} · 工作稿 V{{ selection.version }}</span><span>{{ selection.live?.visible ? '官网公开 V' + selection.live.version : selection.live ? '公开版本已撤下' : '尚未公开' }}</span></div>
          <nav ref="contentNavRef" class="learning-tabs" aria-label="内容版本视图">
            <button class="text-button" :class="{ selected: view === 'work' }" :disabled="busy" @click="changeView('work')">工作稿</button>
            <button class="text-button" :class="{ selected: view === 'live' }" :disabled="busy || !selection.live" @click="changeView('live')">{{ selection.live?.visible ? '官网公开版本' : '最近公开版本' }}</button>
            <button class="text-button" :class="{ selected: view === 'history' }" :disabled="busy" @click="changeView('history')"><History :size="15" />操作与版本记录</button>
            <div class="learning-tabs-indicator" :style="contentIndicatorStyle" aria-hidden="true"></div>
          </nav>
          <template v-if="view === 'history'">
            <h2>操作与版本记录</h2><p class="muted">每次保存和状态变化保留记录。历史全文仅供查看。</p><p v-if="historyLoading" role="status">正在读取版本记录…</p>
            <article v-for="record in history.items" :key="record.version" class="content-history-row"><div><strong>{{ actionNames[record.action] ?? record.action }} · V{{ record.version }}</strong><p>{{ record.reason }}</p><small>{{ record.actorName }} · {{ formatTime(record.recordedAt) }}</small></div><button v-if="record.hasSnapshot" class="text-button" :disabled="historyLoading" @click="showRevision(record.version)">查看全文</button><span v-else class="muted">早期全文未保留</span></article>
            <p v-if="!historyLoading && !history.items.length" class="empty-state">暂无操作记录。</p>
            <div class="foundation-pagination"><span>共 {{ history.total }} 条 · 第 {{ history.page }} 页</span><div><button class="button compact" :disabled="historyLoading || history.page <= 1" @click="loadHistory(history.page - 1)">上一页</button><button class="button compact" :disabled="historyLoading || history.page * history.pageSize >= history.total" @click="loadHistory(history.page + 1)">下一页</button></div></div>
            <aside v-if="revision" class="content-revision-preview" aria-label="历史版本全文"><div class="section-heading"><h3 id="content-revision-heading">历史全文 · V{{ revision.content.version }}</h3><button class="text-button" @click="revision = null">关闭全文</button></div><small>{{ statusNames[revision.content.status] }} · {{ revision.actorName }} · {{ formatTime(revision.recordedAt) }}</small><h2>{{ revision.content.title }}</h2><p class="article-summary">{{ revision.content.summary }}</p><div class="article-body">{{ revision.content.body }}</div><p class="muted">{{ categoryName(revision.content.category) }} · {{ revision.content.sourceType === 'DEMO' ? '演示内容' : '学校公开资料' }}</p><a v-if="revision.content.sourceUrl" class="quiet-link" :href="revision.content.sourceUrl" target="_blank" rel="noopener noreferrer">该版本资料来源 <ArrowUpRight :size="14" /></a></aside>
          </template>
          <template v-else>
            <p v-if="view === 'work' && selection.reviewReason" class="content-review-note">{{ selection.status === 'WITHDRAWN' ? '撤下原因' : '最近审核意见' }}：{{ selection.reviewReason }}</p>
            <p v-if="view === 'work' && selection.changeReason" class="content-context-note">本次更正：{{ selection.changeReason }}</p>
            <p v-if="view === 'work' && selection.live?.visible && selection.status !== 'PUBLISHED'" class="content-context-note">这是尚未批准的工作稿，官网继续显示公开版本 V{{ selection.live.version }}。</p>
            <p v-if="view === 'live' && !selection.live?.visible" class="content-review-note">此版本已撤下，仅供后台核对，官网无法访问。</p>
            <template v-if="shown"><h2>{{ shown.title }}</h2><p class="article-summary">{{ shown.summary }}</p><div class="article-body">{{ shown.body }}</div><p class="data-note">{{ shown.sourceType === 'DEMO' ? '演示内容' : '学校公开资料' }}</p><a v-if="shown.sourceUrl" class="quiet-link" :href="shown.sourceUrl" target="_blank" rel="noopener noreferrer">资料来源 <ArrowUpRight :size="14" /></a></template>
            <div v-if="view === 'work'" class="form-actions content-workflow-actions">
              <button v-if="actions?.edit" class="button compact" :disabled="busy" @click="edit(selection)">编辑草稿</button><button v-if="actions?.submit" class="button primary compact" :disabled="busy" @click="transition('submit')"><Send :size="15" />送交审核</button>
              <button v-if="actions?.recall" class="button compact" :disabled="busy" @click="transition('recall')">撤回送审</button><button v-if="actions?.publish" class="button primary compact" :disabled="busy" @click="transition('publish')"><Check :size="15" />审核并发布</button><button v-if="actions?.reject" class="button compact" :disabled="busy" @click="transition('reject')">驳回修改</button>
              <button v-if="actions?.revise" class="button compact" :disabled="busy" @click="transition('revise')">{{ selection.status === 'WITHDRAWN' ? '修订并重新送审' : '创建更正稿' }}</button><button v-if="actions?.unpublish" class="button compact" :disabled="busy" @click="transition('unpublish')">撤下公开内容</button>
              <p v-if="selection.status === 'IN_REVIEW' && selection.createdBy === currentUser?.id" class="muted">等待其他审核人员处理，你可以撤回后修改。</p>
            </div><RouterLink v-if="actions?.publicLink" class="quiet-link content-public-link" :to="'/content/' + selection.live!.slug">查看官网公开页面 <ArrowUpRight :size="15" /></RouterLink>
          </template>
        </template>
        <p v-else class="empty-state">从内容目录选择一篇文章，或创建新的草稿。</p>
      </section>
    </div>
  </div>
</template>
