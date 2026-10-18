<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { BookOpen, CalendarDays, ChevronLeft, ChevronRight, Download, FileText, Bell, ArrowUpRight, ClipboardList, GraduationCap, Printer } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { readTeachingDraft, writeTeachingDraft } from '../lib/teachingDrafts'
import type { TeachingDraft } from '../lib/teachingDrafts'
import { learningLinkSelection } from '../lib/learningLink'
import type { AcademicTerm, TeachingClass, TeachingLesson, TeachingDetail, CourseMaterial, AcademicGrade, AcademicExam } from '../types'
import BaseSelect from '../components/BaseSelect.vue'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const route = useRoute()
const router = useRouter()
const navRef = ref<HTMLElement | null>(null)
const { indicatorStyle } = useSlidingIndicator(
  navRef,
  () => route.path,
  { activeSelector: '.learning-tabs a.selected, .learning-tabs a.active, .learning-tabs button.selected, .learning-tabs button.active' }
)
const teacher = computed(() => route.path.startsWith('/teaching'))
const base = computed(() => teacher.value ? '/teaching' : '/portal/learning')
const endpoint = computed(() => teacher.value ? '/teaching' : '/learning')
const tab = computed(() => route.path.endsWith('/timetable') ? 'timetable' : route.path.endsWith('/grades') ? 'grades' : route.path.endsWith('/exams') ? 'exams' : 'courses')
const pageTitle = computed(() => tab.value === 'timetable' ? (teacher.value ? '每周授课安排' : '每周课程表') : tab.value === 'grades' ? '课程成绩查询' : tab.value === 'exams' ? '考试时间与地点' : teacher.value ? '教学班与课程资料' : '课程与学习资料')
const pageDescription = computed(() => tab.value === 'timetable' ? '按周查看时间、教室和' + (teacher.value ? '授课教学班。' : '任课教师。') : tab.value === 'grades' ? '查询本学期已发布的本人成绩与课程学分。' : tab.value === 'exams' ? '查看本人已发布的考试日期、时间和考场。' : teacher.value ? '查看教学班学生名单，编写资料并发布班级通知。' : '查看本学期课程，阅读教师发布的学习资料与班级通知。')
const terms = ref<AcademicTerm[]>([])
const termId = ref(0)
const classes = ref<TeachingClass[]>([])
const lessons = ref<TeachingLesson[]>([])
const grades = ref<AcademicGrade[]>([])
const exams = ref<AcademicExam[]>([])
const detail = ref<TeachingDetail | null>(null)
const loading = ref(true)
const detailLoading = ref(false)
const error = ref('')
const success = ref('')
const busy = ref(false)
const weekStart = ref('')
const editing = ref<CourseMaterial | null>(null)
const editorOpen = ref(false)
const form = reactive({ title: '', body: '' })
const attachment = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const notice = reactive({ title: '', body: '', requestKey: crypto.randomUUID() })
const restoredDraft = ref(false)
const storageAvailable = ref(true)
const missingAttachment = ref('')
const confirmation = ref('')
const confirmationDialog = ref<HTMLDialogElement | null>(null)
const keepEditingButton = ref<HTMLButtonElement | null>(null)
let resolveConfirmation: ((accepted: boolean) => void) | null = null
let hydratingDraft = false
const materialDirty = computed(() => editorOpen.value && (form.title !== (editing.value?.title ?? '')
  || form.body !== (editing.value?.body ?? '') || Boolean(attachment.value || missingAttachment.value)))
const noticeDirty = computed(() => Boolean(notice.title || notice.body))
const hasDraft = computed(() => teacher.value && (materialDirty.value || noticeDirty.value))
let loadSequence = 0
let detailSequence = 0
const term = computed(() => terms.value.find(t => t.id === termId.value))
const days = computed(() => Array.from({ length: 7 }, (_, n) => addDays(weekStart.value, n)))
const termMonday = computed(() => monday(term.value?.startsOn ?? ''))
const lastMonday = computed(() => monday(term.value?.endsOn ?? ''))
function addDays(value: string, n: number) {
  if (!value) return ''
  const day = new Date(value + 'T12:00:00'); day.setDate(day.getDate() + n)
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
}
function monday(value: string) { return value ? addDays(value, -((new Date(value + 'T12:00:00').getDay() + 6) % 7)) : '' }
function displayDate(value: string) { return value.slice(0, 10).replaceAll('-', '/') }
function dayLessons(day: string) { return lessons.value.filter(l => l.startsAt.slice(0, 10) === day) }
function printTimetable() { window.print() }
async function selectClass(id: number) {
  if (id === detail.value?.info.id) return
  await router.replace({ path: base.value, query: { class: id } })
}
function persistDraft() {
  if (hydratingDraft || !teacher.value || !detail.value || !currentUser.value) return
  const draft: TeachingDraft = {}
  if (materialDirty.value) draft.material = { source: editing.value, title: form.title, body: form.body, attachmentName: attachment.value?.name ?? missingAttachment.value }
  if (noticeDirty.value) draft.notice = { ...notice }
  storageAvailable.value = writeTeachingDraft(currentUser.value.id, detail.value.info.id, draft, attachment.value)
}
function resetForms() {
  hydratingDraft = true
  editing.value = null; editorOpen.value = false; form.title = ''; form.body = ''
  attachment.value = null; missingAttachment.value = ''; restoredDraft.value = false
  notice.title = ''; notice.body = ''; notice.requestKey = crypto.randomUUID()
  hydratingDraft = false
}
function restoreDraft(id: number) {
  if (!teacher.value || !currentUser.value) return
  const saved = readTeachingDraft(currentUser.value.id, id)
  if (!saved) return
  hydratingDraft = true
  if (saved.draft.material) {
    const material = saved.draft.material
    editing.value = material.source; editorOpen.value = true
    form.title = material.title; form.body = material.body; attachment.value = saved.file
    missingAttachment.value = saved.file ? '' : material.attachmentName
  }
  if (saved.draft.notice) Object.assign(notice, saved.draft.notice)
  restoredDraft.value = true
  hydratingDraft = false
}
async function loadDetail(id: number) {
  persistDraft()
  const sequence = ++detailSequence
  detail.value = null; detailLoading.value = true; resetForms()
  try {
    const result = await api<TeachingDetail>(`${endpoint.value}/classes/${id}`)
    if (sequence === detailSequence) { detail.value = result; restoreDraft(id) }
  } catch (cause) { if (sequence === detailSequence) error.value = errorMessage(cause) }
  finally { if (sequence === detailSequence) detailLoading.value = false }
}
async function load() {
  persistDraft()
  const sequence = ++loadSequence
  detailSequence++; loading.value = true; error.value = ''; success.value = ''
  try {
    if (!terms.value.length) terms.value = await api<AcademicTerm[]>('/learning/terms')
    const linked = learningLinkSelection(terms.value, route.query.term, route.query.week)
    if (linked) termId.value = linked.termId
    if (!termId.value) termId.value = terms.value[0]?.id ?? 0
    if (!termId.value) return
    const [classRows, lessonRows, gradeRows, examRows] = await Promise.all([
      api<TeachingClass[]>(`${endpoint.value}/classes?termId=${termId.value}`),
      api<TeachingLesson[]>(`${endpoint.value}/timetable?termId=${termId.value}`),
      teacher.value ? Promise.resolve([]) : api<AcademicGrade[]>(`/learning/grades?termId=${termId.value}`),
      teacher.value ? Promise.resolve([]) : api<AcademicExam[]>(`/learning/exams?termId=${termId.value}`),
    ])
    if (sequence !== loadSequence) return
    classes.value = classRows; lessons.value = lessonRows; grades.value = gradeRows; exams.value = examRows
    const today = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
    weekStart.value = monday(linked?.date ?? (today < (term.value?.startsOn ?? '') ? term.value!.startsOn : today > (term.value?.endsOn ?? '') ? term.value!.endsOn : today))
    const selected = Number(route.query.class)
    const id = classRows.find(c => c.id === selected)?.id ?? classRows[0]?.id
    if (id) await loadDetail(id)
  } catch (cause) { if (sequence === loadSequence) error.value = errorMessage(cause) }
  finally { if (sequence === loadSequence) loading.value = false }
}
async function changeTerm() {
  if (route.query.term !== undefined || route.query.week !== undefined) await router.replace({ query: { ...route.query, term: undefined, week: undefined } })
  else await load()
}
async function requestConfirmation(message: string) {
  if (resolveConfirmation) finishConfirmation(false)
  confirmation.value = message
  return new Promise<boolean>(resolve => {
    resolveConfirmation = resolve
    void nextTick(() => { confirmationDialog.value?.showModal(); keepEditingButton.value?.focus() })
  })
}
function finishConfirmation(accepted: boolean) {
  confirmationDialog.value?.close()
  const resolve = resolveConfirmation; resolveConfirmation = null; confirmation.value = ''
  resolve?.(accepted)
}
async function openEditor(material: CourseMaterial | null) {
  if (materialDirty.value && !await requestConfirmation('当前资料有未保存内容。继续会放弃这些修改，并打开另一份资料；教学班通知会保留。')) return
  hydratingDraft = true
  editing.value = material; editorOpen.value = true
  form.title = material?.title ?? ''; form.body = material?.body ?? ''; attachment.value = null; missingAttachment.value = ''
  hydratingDraft = false; persistDraft()
  if (fileInput.value) fileInput.value.value = ''
  error.value = ''; success.value = ''
}
async function saveMaterial() {
  if (!detail.value || busy.value) return
  if (missingAttachment.value) { error.value = '请重新选择待上传附件，或移除待上传附件后再保存。'; return }
  busy.value = true; error.value = ''; success.value = ''
  try {
    const c = detail.value.info.id
    const saved = await api<CourseMaterial>(editing.value ? `/teaching/classes/${c}/materials/${editing.value.id}` : `/teaching/classes/${c}/materials`, {
      method: editing.value ? 'PUT' : 'POST', body: JSON.stringify({ ...form, version: editing.value?.version ?? 0 }),
    })
    editing.value = saved
    if (attachment.value) {
      const data = new FormData(); data.append('file', attachment.value); data.append('version', String(saved.version))
      editing.value = await api<CourseMaterial>(`/teaching/materials/${saved.id}/file`, { method: 'POST', body: data })
    }
    editorOpen.value = false; attachment.value = null; missingAttachment.value = ''; persistDraft()
    await loadDetail(c); success.value = '草稿已保存，发布后学生可查看。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function changeState(material: CourseMaterial, status: CourseMaterial['status']) {
  if (busy.value || !detail.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`/teaching/materials/${material.id}/state`, { method: 'POST', body: JSON.stringify({ status, version: material.version }) })
    await loadDetail(detail.value.info.id); success.value = status === 'PUBLISHED' ? '课程资料已发布。' : status === 'WITHDRAWN' ? '资料已撤下，学生不再可见。' : '已转为草稿，可继续修订。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function publishNotice() {
  if (busy.value || !detail.value) return
  busy.value = true; error.value = ''; success.value = ''
  try {
    await api(`/teaching/classes/${detail.value.info.id}/notices`, { method: 'POST', body: JSON.stringify(notice) })
    hydratingDraft = true
    notice.title = ''; notice.body = ''; notice.requestKey = crypto.randomUUID()
    hydratingDraft = false; persistDraft()
    await loadDetail(detail.value.info.id); success.value = '通知已发布，并发送到当前教学班学生的个人消息。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function fileChanged(event: Event) {
  attachment.value = (event.target as HTMLInputElement).files?.[0] ?? null
  missingAttachment.value = ''
}
function removePendingAttachment() {
  attachment.value = null; missingAttachment.value = ''
  if (fileInput.value) fileInput.value.value = ''
}
async function cancelEditor() {
  if (materialDirty.value && !await requestConfirmation('继续会放弃当前资料的未保存内容，教学班通知会保留。')) return
  editorOpen.value = false; attachment.value = null; missingAttachment.value = ''; persistDraft()
}
async function allowNavigation() {
  if (!currentUser.value) return true // Session expiry and logout must always reach the login page.
  if (busy.value) { error.value = '正在提交，请等待操作完成后再离开。'; return false }
  persistDraft()
  if (hasDraft.value && !storageAvailable.value) return requestConfirmation('浏览器无法暂存草稿。当前页面内切换可保留，离开或刷新可能丢失未提交内容。')
  return true
}
function beforeUnload(event: BeforeUnloadEvent) {
  persistDraft()
  if (busy.value || (hasDraft.value && (!storageAvailable.value || attachment.value))) { event.preventDefault(); event.returnValue = '' }
}
watch(() => route.query.class, value => {
  const id = Number(value)
  if (classes.value.some(c => c.id === id)) { error.value = ''; success.value = ''; void loadDetail(id) }
})
watch([() => route.query.term, () => route.query.week], () => { void load() })
watch(() => [notice.title, notice.body], () => { if (!hydratingDraft) notice.requestKey = crypto.randomUUID() }, { flush: 'sync' })
watch(() => [form.title, form.body, editorOpen.value, editing.value, attachment.value, missingAttachment.value, notice.title, notice.body, notice.requestKey], persistDraft, { flush: 'sync' })
onBeforeRouteLeave(allowNavigation)
onBeforeRouteUpdate(allowNavigation)
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { persistDraft(); finishConfirmation(false); detailSequence++; loadSequence++; window.removeEventListener('beforeunload', beforeUnload) })
</script>

<template>
  <div class="learning-page" :aria-busy="loading">
    <dialog ref="confirmationDialog" class="draft-confirm" aria-labelledby="draft-confirm-title" @cancel.prevent="finishConfirmation(false)">
      <h2 id="draft-confirm-title">处理未保存的内容</h2><p>{{ confirmation }}</p>
      <div class="form-actions"><button ref="keepEditingButton" type="button" class="button primary compact" @click="finishConfirmation(false)">继续编辑</button><button type="button" class="button compact" @click="finishConfirmation(true)">确认继续</button></div>
    </dialog>
    <div class="learning-heading">
      <div><span class="portal-context">{{ teacher ? '教师教学服务' : '学生学习服务' }}</span><h1>{{ pageTitle }}</h1><p class="muted">{{ pageDescription }}</p></div>
      <div class="term-picker">
        <span class="picker-label">学期</span>
        <BaseSelect
          v-model="termId"
          :options="terms.map(t => ({ value: t.id, label: t.name }))"
          :disabled="loading || busy"
          @change="changeTerm"
        />
      </div>
    </div>
    <nav ref="navRef" class="learning-tabs" :aria-label="teacher ? '教学服务栏目' : '学习服务栏目'">
      <RouterLink :to="base" :class="{ selected: tab === 'courses' }"><BookOpen :size="16" aria-hidden="true" />{{ teacher ? '教学班与资料' : '课程与资料' }}</RouterLink>
      <RouterLink :to="base + '/timetable'" :class="{ selected: tab === 'timetable' }"><CalendarDays :size="16" aria-hidden="true" />{{ teacher ? '每周授课安排' : '每周课程表' }}</RouterLink>
      <RouterLink v-if="!teacher" :to="base + '/grades'" :class="{ selected: tab === 'grades' }"><GraduationCap :size="16" aria-hidden="true" />课程成绩查询</RouterLink>
      <RouterLink v-if="!teacher" :to="base + '/exams'" :class="{ selected: tab === 'exams' }"><ClipboardList :size="16" aria-hidden="true" />考试时间与地点</RouterLink>
      <div class="learning-tabs-indicator" :style="indicatorStyle" aria-hidden="true"></div>
    </nav>
    <p v-if="error" class="alert" role="alert">{{ error }} <button class="text-button" :disabled="busy" @click="load">刷新数据</button></p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading && !terms.length" class="loading-state" role="status">正在读取教学信息…</p>
    <p v-else-if="!terms.length" class="empty-state">尚未维护学期，请联系教务信息维护岗位。</p>
    <template v-else-if="tab === 'courses'">
      <p v-if="!classes.length" class="empty-state">本学期暂无{{ teacher ? '有效授课' : '已关联' }}教学班。</p>
      <div v-else class="learning-grid">
        <aside class="course-directory" aria-label="教学班目录">
          <button v-for="c in classes" :key="c.id" class="course-choice" :aria-pressed="detail?.info.id === c.id" :disabled="busy || detailLoading" @click="selectClass(c.id)"><BookOpen :size="18" /><span><strong>{{ c.courseName }}</strong><small>{{ c.name }}</small><small>{{ c.credits }} 学分{{ teacher ? ' · ' + c.memberCount + ' 名学生' : '' }}</small></span><ArrowUpRight :size="14" /></button>
        </aside>
        <section class="portal-panel course-detail">
          <p v-if="detailLoading" class="loading-state" role="status">正在读取教学班…</p>
          <template v-else-if="detail">
            <p v-if="teacher && hasDraft" class="draft-hint" role="status">{{ restoredDraft ? '已恢复此教学班未提交的内容。' : '未提交内容已暂存。' }}{{ storageAvailable ? '切换教学班或页面后可继续编辑，文字在当前浏览器标签页保留 24 小时；退出登录后清除。' : '浏览器暂存不可用，请先完成提交再关闭或刷新。' }}</p>
            <div class="section-heading"><div><span class="eyebrow">{{ detail.info.name }}</span><h2>{{ detail.info.courseName }}</h2><p class="muted">{{ detail.instructors.map(i => i.fullName).join('、') }} · {{ detail.info.credits }} 学分</p></div><button v-if="teacher" class="button" :disabled="busy" @click="openEditor(null)">新建资料</button></div>
            <form v-if="teacher && editorOpen" class="learning-form" @submit.prevent="saveMaterial">
              <h3>{{ editing ? '编辑课程资料' : '新建课程资料' }}</h3>
              <fieldset :disabled="busy"><label>资料标题<input v-model="form.title" required maxlength="160" /></label><label>正文<textarea v-model="form.body" required maxlength="20000" rows="5" /></label><label>附件（可选）<input ref="fileInput" type="file" accept=".pdf,.txt" @change="fileChanged" /><small class="muted">PDF 或 UTF-8 文本，最大 2 MB{{ editing?.fileName ? '；已有附件：' + editing.fileName : '' }}</small></label></fieldset>
              <p v-if="attachment || missingAttachment" class="muted">{{ missingAttachment ? '刷新后需重新选择附件：' + missingAttachment : '待上传附件：' + attachment?.name }} <button type="button" class="text-button" :disabled="busy" @click="removePendingAttachment">移除待上传附件</button></p>
              <div class="form-actions"><button class="button primary" :disabled="busy">{{ busy ? '正在保存…' : '保存草稿' }}</button><button type="button" class="text-button" :disabled="busy" @click="cancelEditor">取消编辑</button></div>
            </form>
            <h3 class="subsection-heading"><FileText :size="16" />课程资料</h3>
            <p v-if="!detail.materials.length" class="empty-state">{{ teacher ? '尚未添加课程资料。' : '教师尚未发布课程资料。' }}</p>
            <article v-for="m in detail.materials" :key="m.id" class="learning-entry">
              <div class="notice-heading"><h3>{{ m.title }}</h3><span v-if="teacher" class="status-label">{{ m.status === 'PUBLISHED' ? '已发布' : m.status === 'WITHDRAWN' ? '已撤下' : '草稿' }} · v{{ m.version + 1 }}</span></div>
              <p class="preserve-lines">{{ m.body }}</p><small class="muted">{{ m.authorName }} · {{ displayDate(m.updatedAt) }}</small>
              <div class="entry-actions">
<a v-if="m.fileName" class="text-button" :href="'/api/teaching-files/' + m.id"><Download :size="14" />{{ m.fileName }}</a>
                <template v-if="teacher && m.authorId === currentUser?.id">
                  <button v-if="m.status === 'DRAFT'" class="text-button" :disabled="busy" @click="openEditor(m)">编辑</button>
                  <button v-if="m.status === 'DRAFT'" class="button compact" :disabled="busy" @click="changeState(m, 'PUBLISHED')">发布资料</button>
                  <button v-if="m.status !== 'DRAFT'" class="text-button" :disabled="busy" @click="changeState(m, 'DRAFT')">转为草稿</button>
                  <button v-if="m.status === 'PUBLISHED'" class="text-button" :disabled="busy" @click="changeState(m, 'WITHDRAWN')">撤下</button>
                </template>
              </div>
            </article>
            <h3 class="subsection-heading"><Bell :size="16" />教学班通知</h3>
            <form v-if="teacher" class="learning-form" @submit.prevent="publishNotice"><fieldset :disabled="busy"><label>通知标题<input v-model="notice.title" required maxlength="160" /></label><label>通知内容<textarea v-model="notice.body" required maxlength="10000" rows="3" /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">发布通知</button><span class="muted">发送给当前教学班 {{ detail.members.length }} 名有效学生</span></div></form>
            <p v-if="!detail.notices.length" class="empty-state">暂无教学班通知。</p>
            <article v-for="n in detail.notices" :key="n.id" class="learning-entry"><h3>{{ n.title }}</h3><p class="preserve-lines">{{ n.body }}</p><small class="muted">{{ n.authorName }} · {{ displayDate(n.createdAt) }}</small></article>
            <template v-if="teacher"><h3 class="subsection-heading">教学班学生 · {{ detail.members.length }} 人</h3><div class="table-scroll"><table class="campus-table"><thead><tr><th>姓名</th><th>学号</th><th>行政班</th></tr></thead><tbody><tr v-for="p in detail.members" :key="p.id"><td>{{ p.fullName }}</td><td>{{ p.identityNumber }}</td><td>{{ p.className ?? '未分配' }}</td></tr></tbody></table></div></template>
          </template>
        </section>
      </div>
    </template>
    <section v-else-if="tab === 'timetable'" class="portal-panel timetable-print-card">
      <div class="section-heading schedule-heading"><div><h2>每周{{ teacher ? '授课' : '课程' }}安排</h2><p class="muted">{{ displayDate(weekStart) }} — {{ displayDate(addDays(weekStart, 6)) }} · {{ term?.name ?? '' }}</p></div><div class="week-controls"><button class="button compact no-print" :disabled="weekStart <= termMonday" @click="weekStart = addDays(weekStart, -7)"><ChevronLeft :size="16" aria-hidden="true" />上一周</button><button class="button compact no-print" :disabled="weekStart >= lastMonday" @click="weekStart = addDays(weekStart, 7)">下一周<ChevronRight :size="16" aria-hidden="true" /></button><button class="button compact no-print" @click="printTimetable"><Printer :size="15" />打印课表</button></div></div>
      <div class="week-scroll"><div class="week-grid"><div v-for="(day, n) in days" :key="day" class="week-day"><div class="week-day-heading">{{ ['周一', '周二', '周三', '周四', '周五', '周六', '周日'][n] }}<small>{{ day.slice(5).replace('-', '/') }}</small></div><article v-for="l in dayLessons(day)" :key="l.id" class="week-lesson"><small>{{ l.startsAt.slice(11, 16) }}—{{ l.endsAt.slice(11, 16) }}</small><strong>{{ l.courseName }}</strong><span>{{ l.classroom }}</span><span>{{ l.campusName }}</span><span>{{ teacher ? l.className : l.teacherName }}</span></article><p v-if="!dayLessons(day).length" class="day-empty">无课程</p></div></div></div>
    </section>
    <section v-else-if="tab === 'grades'" class="portal-panel"><div class="section-heading"><div><span class="eyebrow">ACADEMIC RECORD</span><h2>已发布成绩</h2></div><span class="muted">{{ grades.length }} 门课程</span></div><p v-if="!grades.length" class="empty-state">本学期暂无已发布的本人成绩。</p><div v-else class="table-scroll"><table class="campus-table"><thead><tr><th>课程</th><th>学分</th><th>成绩</th><th>更新时间</th></tr></thead><tbody><tr v-for="g in grades" :key="g.id"><td>{{ g.courseName }}</td><td>{{ g.credits }}</td><td class="score-cell">{{ g.score }}</td><td>{{ displayDate(g.updatedAt) }}</td></tr></tbody></table></div></section>
    <section v-else class="portal-panel"><div class="section-heading"><div><span class="eyebrow">EXAM SCHEDULE</span><h2>考试安排</h2></div><span class="muted">{{ exams.length }} 场已发布考试</span></div><p v-if="!exams.length" class="empty-state">本学期暂无已发布的本人考试安排。</p><article v-for="e in exams" :key="e.id" class="lesson-row"><div class="lesson-time"><strong>{{ displayDate(e.startsAt) }}</strong><span>{{ e.startsAt.slice(11, 16) }}—{{ e.endsAt.slice(11, 16) }}</span></div><div><h3>{{ e.courseName }}</h3><p>{{ e.campusName }} · {{ e.classroom }}</p></div></article></section>
    <p class="data-note">课程、成绩和考试均为毕业设计演示记录。教学安排以教务发布的数据为准。</p>
  </div>
</template>
