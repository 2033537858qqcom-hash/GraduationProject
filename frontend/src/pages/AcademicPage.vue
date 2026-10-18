<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { BookOpen, FileText, CalendarDays, Settings2, Download, Upload } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { roomOption } from '../resources'
import type { AcademicCatalog, AcademicGrade, AcademicExam, TeachingDetail } from '../types'
import BaseSelect from '../components/BaseSelect.vue'
import { useSlidingIndicator } from '../composables/useSlidingIndicator'

const catalog = ref<AcademicCatalog | null>(null)
const selected = ref(0)
const detail = ref<TeachingDetail | null>(null)
const tab = ref('classes')
const academicNavRef = ref<HTMLElement | null>(null)
const { indicatorStyle: academicIndicatorStyle } = useSlidingIndicator(
  academicNavRef,
  () => tab.value
)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const success = ref('')
const termForm = reactive({ name: '', startsOn: '', endsOn: '' })
const courseForm = reactive({ code: '', name: '', credits: 3 })
const classForm = reactive({ termId: 0, courseId: 0, name: '', teacherIds: [] as number[], studentIds: [] as number[] })
const lessonForm = reactive({ teacherId: 0, campusId: 1, roomId: 0, startsAt: '', endsAt: '' })
const examForm = reactive({ campusId: 1, roomId: 0, startsAt: '', endsAt: '', version: 0 })
const lessonRooms = computed(() => catalog.value?.rooms.filter(room => room.campusId === lessonForm.campusId && room.schedulable) ?? [])
const examRooms = computed(() => catalog.value?.rooms.filter(room => room.campusId === examForm.campusId && (room.schedulable || room.id === examForm.roomId)) ?? [])
const examRoom = computed(() => catalog.value?.rooms.find(room => room.id === examForm.roomId))
watch(() => lessonForm.campusId, () => { lessonForm.roomId = 0 })
watch(() => examForm.campusId, () => { if (!catalog.value?.rooms.some(room => room.id === examForm.roomId && room.campusId === examForm.campusId)) examForm.roomId = 0 })
const memberId = ref(0)
const instructorId = ref(0)
const classPickerOptions = computed(() => [
  ...(catalog.value?.classes.map(c => ({ value: c.id, label: `${c.name} · ${c.courseName}` })) ?? []),
])
const instructorOptions = computed(() => [
  { value: 0, label: '选择本学院教师' },
  ...(catalog.value?.teachers.map(p => ({ value: p.id, label: `${p.fullName} · ${p.identityNumber}` })) ?? []),
])
const memberOptions = computed(() => [
  { value: 0, label: '选择本学院学生' },
  ...(catalog.value?.students.map(p => ({ value: p.id, label: `${p.fullName} · ${p.identityNumber}` })) ?? []),
])
const lessonTeacherOptions = computed(() => [
  ...(detail.value?.instructors.map(p => ({ value: p.id, label: p.fullName })) ?? []),
])
const campusOptions = computed(() => [
  ...(catalog.value?.campuses.map(c => ({ value: c.id, label: c.name })) ?? []),
])
const lessonRoomOptions = computed(() => [
  { value: 0, label: '请选择可安排的教室', disabled: true },
  ...lessonRooms.value.map(room => {
    const insufficient = (room.teachingCapacity ?? 0) < (detail.value?.info.memberCount ?? 0)
    return {
      value: room.id,
      label: `${roomOption(room)}${insufficient ? ' · 容量不足' : ''}`,
      disabled: insufficient,
    }
  }),
])
const examRoomOptions = computed(() => [
  { value: 0, label: '请选择足够考试容量的教室', disabled: true },
  ...examRooms.value.map(room => {
    const insufficient = (room.examCapacity ?? 0) < (detail.value?.info.memberCount ?? 0)
    return {
      value: room.id,
      label: `${roomOption(room, true)}${room.schedulable && insufficient ? ' · 容量不足' : ''}`,
      disabled: !room.schedulable || insufficient,
    }
  }),
])
const termOptions = computed(() => [
  ...(catalog.value?.terms.map(t => ({ value: t.id, label: t.name })) ?? []),
])
const courseOptions = computed(() => [
  ...(catalog.value?.courses.map(c => ({ value: c.id, label: `${c.code} · ${c.name}` })) ?? []),
])
const gradeText = ref('')
const validatedRows = ref<{ studentId: number; score: number; version: number }[] | null>(null)
const classTerm = computed(() => catalog.value?.terms.find(t => t.id === detail.value?.info.termId))
let sequence = 0
watch(gradeText, () => { validatedRows.value = null })
async function loadCatalog() {
  catalog.value = await api<AcademicCatalog>('/academic/catalog')
  classForm.termId ||= catalog.value.terms[0]?.id ?? 0
  classForm.courseId ||= catalog.value.courses[0]?.id ?? 0
  if (!catalog.value.classes.some(c => c.id === selected.value)) selected.value = catalog.value.classes[0]?.id ?? 0
}
async function loadDetail() {
  const request = ++sequence
  detail.value = null; loading.value = true; error.value = ''; validatedRows.value = null
  try {
    if (!selected.value) return
    const result = await api<TeachingDetail>(`/academic/classes/${selected.value}`)
    if (request !== sequence) return
    detail.value = result
    const exam = result.exams[0]
    Object.assign(examForm, { campusId: exam?.campusId ?? 1, roomId: exam?.roomId ?? 0, startsAt: exam?.startsAt.slice(0, 16) ?? '', endsAt: exam?.endsAt.slice(0, 16) ?? '', version: exam?.version ?? 0 })
    lessonForm.teacherId = result.instructors[0]?.id ?? 0
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
async function load() {
  loading.value = true; error.value = ''
  try { await loadCatalog(); await loadDetail() }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
async function mutate(action: () => Promise<unknown>, message: string) {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''
  try { await action(); await loadCatalog(); await loadDetail(); success.value = message }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function createTerm() { void mutate(() => api('/academic/terms', { method: 'POST', body: JSON.stringify(termForm) }), '学期已建立。') }
function createCourse() { void mutate(() => api('/academic/courses', { method: 'POST', body: JSON.stringify(courseForm) }), '课程已建立。') }
function createClass() {
  void mutate(async () => {
    const result = await api<TeachingDetail>('/academic/classes', { method: 'POST', body: JSON.stringify(classForm) })
    selected.value = result.info.id; classForm.name = ''; classForm.teacherIds = []; classForm.studentIds = []
  }, '教学班与授课、成员关系已建立。')
}
function relation(userId: number, kind: 'members' | 'instructors', active: boolean) {
  void mutate(() => api(`/academic/classes/${selected.value}/${kind}`, { method: 'POST', body: JSON.stringify({ userId, active }) }), active ? '教学关系已生效。' : '教学关系已停用，后续访问不再授权。')
}
function addLesson() { void mutate(() => api(`/academic/classes/${selected.value}/lessons`, { method: 'POST', body: JSON.stringify({ teacherId: lessonForm.teacherId, roomId: lessonForm.roomId, startsAt: lessonForm.startsAt, endsAt: lessonForm.endsAt }) }), '课程安排已添加，学生课表与通知已同步。') }
function parseGrades() {
  if (!detail.value) throw new Error('请先选择教学班。')
  const lines = gradeText.value.trim().split(/\r?\n/).filter(line => line.trim())
  if (lines[0]?.replaceAll(' ', '') === '学号,成绩') lines.shift()
  if (!lines.length) throw new Error('请输入至少一行成绩。')
  return lines.map((line, index) => {
    const cells = line.split(/[,，\t]/).map(s => s.trim())
    const student = detail.value!.members.find(p => p.identityNumber === cells[0])
    if (cells.length !== 2 || !student || !cells[1] || !/^\d+(\.\d)?$/.test(cells[1]) || Number(cells[1]) > 100) throw new Error(`第 ${index + 1} 行有误，请使用当前教学班学号和 0—100 的成绩，最多一位小数。`)
    return { studentId: student.id, score: Number(cells[1]), version: detail.value!.grades.find(g => g.studentId === student.id)?.version ?? 0 }
  })
}
async function previewGrades() {
  if (busy.value) return
  busy.value = true; error.value = ''; success.value = ''; validatedRows.value = null
  try {
    const rows = parseGrades()
    await api(`/academic/classes/${selected.value}/grades/import`, { method: 'POST', body: JSON.stringify({ rows, preview: true }) })
    validatedRows.value = rows
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
function importGrades() {
  if (!validatedRows.value) return
  const rows = [...validatedRows.value]
  void mutate(() => api(`/academic/classes/${selected.value}/grades/import`, { method: 'POST', body: JSON.stringify({ rows, preview: false }) }), '成绩草稿已保存。逐条发布后学生才可查看。')
}
async function csvFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file) return
  try {
    if (file.size > 100000) throw new Error('成绩 CSV 最大为 100 KB。')
    gradeText.value = (await file.text()).replace(/^\uFEFF/, '')
  } catch (cause) { error.value = errorMessage(cause) }
}
function template() {
  const text = '学号,成绩\n' + (detail.value?.members.map(p => `${p.identityNumber},`).join('\n') ?? '')
  const url = URL.createObjectURL(new Blob(['\uFEFF' + text], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = '教学班成绩模板.csv'; link.click(); URL.revokeObjectURL(url)
}
function gradeState(grade: AcademicGrade) { void mutate(() => api(`/academic/grades/${grade.id}/state`, { method: 'POST', body: JSON.stringify({ status: grade.status === 'DRAFT' ? 'PUBLISHED' : 'DRAFT', version: grade.version }) }), grade.status === 'DRAFT' ? '成绩已发布并通知对应学生。' : '成绩已撤下，学生查询已更新。') }
function saveExam() { void mutate(() => api(`/academic/classes/${selected.value}/exam`, { method: 'PUT', body: JSON.stringify({ roomId: examForm.roomId, startsAt: examForm.startsAt, endsAt: examForm.endsAt, version: examForm.version }) }), '考试草稿已保存，核对后可发布。') }
function examState(exam: AcademicExam) { void mutate(() => api(`/academic/exams/${exam.id}/state`, { method: 'POST', body: JSON.stringify({ status: exam.status === 'DRAFT' ? 'PUBLISHED' : 'DRAFT', version: exam.version }) }), exam.status === 'DRAFT' ? '考试已发布，学生查询与通知已同步。' : '考试已撤下，学生查询与通知已同步。') }
onMounted(load)
</script>

<template>
  <div class="learning-page">
    <div class="learning-heading"><div><span class="eyebrow">ACADEMIC SERVICES</span><h1>教务信息维护</h1><p class="muted">维护本学院教学关系，核对并发布教学信息</p></div><button class="button compact" :disabled="busy || loading" @click="load">刷新数据</button></div>
    <nav ref="academicNavRef" class="learning-tabs" aria-label="教务维护栏目">
      <button :class="{ selected: tab === 'classes' }" :disabled="busy" @click="tab = 'classes'"><BookOpen :size="16" />教学班</button>
      <button :class="{ selected: tab === 'grades' }" :disabled="busy" @click="tab = 'grades'"><FileText :size="16" />成绩维护</button>
      <button :class="{ selected: tab === 'exams' }" :disabled="busy" @click="tab = 'exams'"><CalendarDays :size="16" />考试安排</button>
      <button :class="{ selected: tab === 'foundation' }" :disabled="busy" @click="tab = 'foundation'"><Settings2 :size="16" />基础目录</button>
      <div class="learning-tabs-indicator" :style="academicIndicatorStyle" aria-hidden="true"></div>
    </nav>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <label v-if="catalog && tab !== 'foundation'" class="class-picker">当前教学班<BaseSelect v-model="selected" :options="classPickerOptions" :disabled="busy || loading" @change="gradeText = ''; loadDetail()" /></label>
    <p v-if="loading" class="loading-state" role="status">正在读取教务信息…</p>
    <p v-else-if="!detail && tab !== 'foundation'" class="empty-state">暂无教学班，可在基础目录中建立。</p>
    <template v-if="!loading && detail && catalog">
      <section v-if="tab === 'classes'" class="portal-panel">
        <div class="section-heading"><div><h2>{{ detail.info.name }}</h2><p class="muted">{{ detail.info.departmentName }} · {{ detail.info.courseName }} · {{ classTerm?.name }}</p></div></div>
        <h3 class="subsection-heading">任课教师</h3><div class="people-list"><div v-for="p in detail.instructors" :key="p.id"><span>{{ p.fullName }} <small>{{ p.identityNumber }}</small></span><button class="text-button" :disabled="busy" @click="relation(p.id, 'instructors', false)">停用关系</button></div></div>
        <form class="relation-form" @submit.prevent="relation(instructorId, 'instructors', true)"><label>教师<BaseSelect v-model="instructorId" :options="instructorOptions" :disabled="busy" /></label><button class="button compact" :disabled="busy || !instructorId">添加 / 恢复关联</button></form>
        <h3 class="subsection-heading">教学班成员 · {{ detail.members.length }} 人</h3><div class="table-scroll"><table class="campus-table"><thead><tr><th>姓名</th><th>学号</th><th>行政班</th><th>操作</th></tr></thead><tbody><tr v-for="p in detail.members" :key="p.id"><td>{{ p.fullName }}</td><td>{{ p.identityNumber }}</td><td>{{ p.className }}</td><td><button class="text-button" :disabled="busy" @click="relation(p.id, 'members', false)">停用关系</button></td></tr></tbody></table></div>
        <form class="relation-form" @submit.prevent="relation(memberId, 'members', true)"><label>学生<BaseSelect v-model="memberId" :options="memberOptions" :disabled="busy" /></label><button class="button compact" :disabled="busy || !memberId">添加 / 恢复关联</button></form>
<details class="academic-section"><summary>添加一次课程安排</summary><form class="learning-form" @submit.prevent="addLesson"><fieldset class="form-grid" :disabled="busy"><label>授课教师<BaseSelect v-model="lessonForm.teacherId" :options="lessonTeacherOptions" /></label><label>校区<BaseSelect v-model="lessonForm.campusId" :options="campusOptions" /></label><label>上课教室<BaseSelect v-model="lessonForm.roomId" :options="lessonRoomOptions" /><small class="muted">当前教学班 {{ detail.info.memberCount }} 人；教室目录由管理员维护。</small></label><label>开始时间<input v-model="lessonForm.startsAt" type="datetime-local" required /></label><label>结束时间<input v-model="lessonForm.endsAt" type="datetime-local" required /></label></fieldset><p v-if="!lessonRooms.length" class="content-context-note">该校区没有可安排的教室，请管理员补录资料或启用资源后刷新。</p><div class="form-actions"><button class="button primary compact" :disabled="busy || !detail.instructors.length || !lessonForm.roomId">保存课程安排</button><span class="muted">保存时校验教师、学生、资源容量及教室占用</span></div></form></details>
      </section>
      <section v-if="tab === 'grades'" class="portal-panel">
<div class="section-heading"><div><h2>成绩导入与发布</h2><p class="muted">{{ detail.info.name }} · {{ detail.members.length }} 名有效学生</p></div><button class="text-button" :disabled="busy" @click="template"><Download :size="14" />下载模板</button></div>
        <form class="learning-form" @submit.prevent="previewGrades"><fieldset :disabled="busy"><label>成绩 CSV<input type="file" accept=".csv,.txt" @change="csvFile" /></label><label>或粘贴成绩<textarea v-model="gradeText" rows="4" placeholder="学号,成绩&#10;20260001,88" required /></label></fieldset><p class="muted">仅接受当前教学班学号。导入后保存为草稿；更正已发布成绩时，原查询结果先撤下，再核对发布。</p><button class="button compact" :disabled="busy"><Upload :size="14" />校验并预览</button></form>
        <div v-if="validatedRows" class="grade-preview"><h3>校验通过 · {{ validatedRows.length }} 条</h3><div class="table-scroll"><table class="campus-table"><thead><tr><th>学生</th><th>成绩</th></tr></thead><tbody><tr v-for="r in validatedRows" :key="r.studentId"><td>{{ detail.members.find(p => p.id === r.studentId)?.fullName }}</td><td>{{ r.score }}</td></tr></tbody></table></div><button class="button primary compact" :disabled="busy" @click="importGrades">确认保存草稿</button></div>
        <h3 class="subsection-heading">当前成绩记录</h3><p v-if="!detail.grades.length" class="empty-state">尚未导入成绩。</p><div v-else class="table-scroll"><table class="campus-table"><thead><tr><th>学生</th><th>成绩</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="g in detail.grades" :key="g.id"><td>{{ g.studentName }}</td><td>{{ g.score }}</td><td><span class="status-label">{{ g.status === 'DRAFT' ? '草稿' : '已发布' }} · v{{ g.version + 1 }}</span></td><td><button class="text-button" :disabled="busy" @click="gradeState(g)">{{ g.status === 'DRAFT' ? '发布' : '撤下' }}</button></td></tr></tbody></table></div>
      </section>
      <section v-if="tab === 'exams'" class="portal-panel">
<div class="section-heading"><div><h2>考试安排</h2><p class="muted">{{ detail.info.name }} · {{ classTerm?.name }}</p></div><span v-if="detail.exams[0]" class="status-label">{{ detail.exams[0].status === 'DRAFT' ? '草稿' : '已发布' }}</span></div>
<form class="learning-form" @submit.prevent="saveExam"><fieldset class="form-grid" :disabled="busy"><label>校区<BaseSelect v-model="examForm.campusId" :options="campusOptions" /></label><label>考试教室<BaseSelect v-model="examForm.roomId" :options="examRoomOptions" /><small class="muted">当前教学班 {{ detail.info.memberCount }} 人，使用考试容量校验。</small></label><label>开始时间<input v-model="examForm.startsAt" type="datetime-local" required /></label><label>结束时间<input v-model="examForm.endsAt" type="datetime-local" required /></label></fieldset><p v-if="examRoom && !examRoom.schedulable" class="content-context-note">当前考场已停用或资料待补录。请选择可安排的资源，或请管理员核对后补录。</p><p class="muted">修改后保存为草稿。核对并发布后，学生查询与消息同步更新；发布时再次校验资源状态、人数和占用。</p><div class="form-actions"><button class="button compact" :disabled="busy || !examRoom?.schedulable">保存草稿</button><button v-if="detail.exams[0]" class="button primary compact" type="button" :disabled="busy" @click="examState(detail.exams[0])">{{ detail.exams[0].status === 'DRAFT' ? '发布已保存的安排' : '撤下已发布的安排' }}</button></div></form>
      </section>
    </template>
    <section v-if="catalog && tab === 'foundation'" class="portal-panel">
      <h2>教学基础目录</h2><p class="muted">学期与课程使用统一目录，教学班及人员关系限定在你的学院。</p>
      <details class="academic-section"><summary>建立学期</summary><form class="learning-form" @submit.prevent="createTerm"><fieldset class="form-grid" :disabled="busy"><label>学期名称<input v-model="termForm.name" required maxlength="100" /></label><label>开始日期<input v-model="termForm.startsOn" type="date" required /></label><label>结束日期<input v-model="termForm.endsOn" type="date" required /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">建立学期</button></div></form></details>
      <details class="academic-section"><summary>建立课程</summary><form class="learning-form" @submit.prevent="createCourse"><fieldset class="form-grid" :disabled="busy"><label>课程编码<input v-model="courseForm.code" required maxlength="40" pattern="[A-Za-z0-9_-]+" placeholder="如 DEMO-WEB" /></label><label>课程名称<input v-model="courseForm.name" required maxlength="100" /></label><label>学分<input v-model.number="courseForm.credits" type="number" min="0.1" max="30" step="0.1" required /></label></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy">建立课程</button></div></form></details>
      <details class="academic-section" open><summary>建立教学班与人员关系</summary><form class="learning-form" @submit.prevent="createClass"><fieldset :disabled="busy"><div class="form-grid"><label>学期<BaseSelect v-model="classForm.termId" :options="termOptions" /></label><label>课程<BaseSelect v-model="classForm.courseId" :options="courseOptions" /></label><label>教学班名称<input v-model="classForm.name" required maxlength="100" /></label></div><div class="form-grid"><div><h3>授课教师</h3><label v-for="p in catalog.teachers" :key="p.id" class="checkbox-label"><input v-model="classForm.teacherIds" type="checkbox" :value="p.id" />{{ p.fullName }} · {{ p.identityNumber }}</label></div><div><h3>教学班学生</h3><label v-for="p in catalog.students" :key="p.id" class="checkbox-label"><input v-model="classForm.studentIds" type="checkbox" :value="p.id" />{{ p.fullName }} · {{ p.identityNumber }}</label></div></div></fieldset><div class="form-actions"><button class="button primary compact" :disabled="busy || !classForm.teacherIds.length || !classForm.studentIds.length">建立教学班</button><span class="muted">不改变学生的行政班归属</span></div></form></details>
    </section>
    <p class="data-note">当前记录为毕业设计演示。系统管理员、普通教师与服务人员不自动拥有教务维护权限。</p>
  </div>
</template>
