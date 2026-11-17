<script setup lang="ts">
import DownloadLink from '../components/DownloadLink.vue';
import FileDropZone from '../components/FileDropZone.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router';
import { confirmDialog } from '../composables/useConfirm';
import {
  BookOpen,
  CalendarDays,
  ChevronLeft,
  ChevronRight,
  Download,
  FileText,
  Bell,
  ArrowUpRight,
  ClipboardList,
  GraduationCap,
} from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import { shanghaiToday } from '../lib/format';
import { currentUser } from '../stores/auth';
import { readTeachingDraft, writeTeachingDraft } from '../lib/teachingDrafts';
import type { TeachingDraft } from '../lib/teachingDrafts';
import { learningLinkSelection } from '../lib/learningLink';
import { TIMETABLE_PERIODS, TIMETABLE_WEEKDAYS, lessonsInSlot, type TimetablePeriodKey } from '../lib/timetable';
import type {
  AcademicTerm,
  TeachingClass,
  TeachingLesson,
  TeachingDetail,
  CourseMaterial,
  AcademicGrade,
  AcademicExam,
} from '../types';
import BaseSelect from '../components/BaseSelect.vue';
import { useSlidingIndicator } from '../composables/useSlidingIndicator';

const route = useRoute();
const router = useRouter();
const navRef = ref<HTMLElement | null>(null);
const { indicatorStyle } = useSlidingIndicator(navRef, () => route.path, {
  activeSelector:
    '.learning-tabs a.selected, .learning-tabs a.active, .learning-tabs button.selected, .learning-tabs button.active',
});
const teacher = computed(() => route.path.startsWith('/teaching'));
const base = computed(() => (teacher.value ? '/teaching' : '/portal/learning'));
const endpoint = computed(() => (teacher.value ? '/teaching' : '/learning'));
const tab = computed(() =>
  route.path.endsWith('/timetable')
    ? 'timetable'
    : route.path.endsWith('/grades')
      ? 'grades'
      : route.path.endsWith('/exams')
        ? 'exams'
        : 'courses',
);
const pageTitle = computed(() =>
  tab.value === 'timetable'
    ? teacher.value
      ? '每周授课安排'
      : '每周课程表'
    : tab.value === 'grades'
      ? '课程成绩查询'
      : tab.value === 'exams'
        ? '考试时间与地点'
        : teacher.value
          ? '教学班与课程资料'
          : '课程与学习资料',
);
const pageDescription = computed(() =>
  tab.value === 'timetable'
    ? '按周查看时间、教室和' + (teacher.value ? '授课教学班。' : '任课教师。')
    : tab.value === 'grades'
      ? '查询本学期已发布的本人成绩与课程学分。'
      : tab.value === 'exams'
        ? '查看本人已发布的考试日期、时间和考场。'
        : teacher.value
          ? '查看教学班学生名单，编写资料并发布班级通知。'
          : '查看本学期课程，阅读教师发布的学习资料与班级通知。',
);
const terms = ref<AcademicTerm[]>([]);
const termId = ref(0);
const classes = ref<TeachingClass[]>([]);
const lessons = ref<TeachingLesson[]>([]);
const grades = ref<AcademicGrade[]>([]);
const exams = ref<AcademicExam[]>([]);
const detail = ref<TeachingDetail | null>(null);
const loading = ref(true);
const detailLoading = ref(false);
const error = ref('');
const success = ref('');
const busy = ref(false);
const weekStart = ref('');
const editing = ref<CourseMaterial | null>(null);
const editorOpen = ref(false);
const noticeFormOpen = ref(false);
const expandedMaterialIds = ref<Record<number, boolean>>({});
function toggleMaterialBody(id: number) {
  expandedMaterialIds.value[id] = !expandedMaterialIds.value[id];
}
const form = reactive({ title: '', body: '' });
const attachment = ref<File | null>(null);
const fileInput = ref<InstanceType<typeof FileDropZone> | null>(null);
const notice = reactive({ title: '', body: '', requestKey: crypto.randomUUID() });
const restoredDraft = ref(false);
const storageAvailable = ref(true);
const missingAttachment = ref('');
let hydratingDraft = false;
const materialDirty = computed(
  () =>
    editorOpen.value &&
    (form.title !== (editing.value?.title ?? '') ||
      form.body !== (editing.value?.body ?? '') ||
      Boolean(attachment.value || missingAttachment.value)),
);
const noticeDirty = computed(() => Boolean(notice.title || notice.body));
const hasDraft = computed(() => teacher.value && (materialDirty.value || noticeDirty.value));
let loadSequence = 0;
let detailSequence = 0;
const term = computed(() => terms.value.find(t => t.id === termId.value));
const days = computed(() => Array.from({ length: 7 }, (_, n) => addDays(weekStart.value, n)));
const termMonday = computed(() => monday(term.value?.startsOn ?? ''));
const lastMonday = computed(() => monday(term.value?.endsOn ?? ''));
function addDays(value: string, n: number) {
  if (!value) return '';
  const day = new Date(value + 'T12:00:00');
  day.setDate(day.getDate() + n);
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
}
function monday(value: string) {
  return value ? addDays(value, -((new Date(value + 'T12:00:00').getDay() + 6) % 7)) : '';
}
function displayDate(value: string) {
  return value.slice(0, 10).replaceAll('-', '/');
}
function slotLessons(day: string, period: TimetablePeriodKey) {
  return lessonsInSlot(lessons.value, day, period);
}
async function selectClass(id: number) {
  if (id === detail.value?.info.id) return;
  await router.replace({ path: base.value, query: { class: id } });
}
function persistDraft() {
  if (hydratingDraft || !teacher.value || !detail.value || !currentUser.value) return;
  const draft: TeachingDraft = {};
  if (materialDirty.value)
    draft.material = {
      source: editing.value,
      title: form.title,
      body: form.body,
      attachmentName: attachment.value?.name ?? missingAttachment.value,
    };
  if (noticeDirty.value) draft.notice = { ...notice };
  storageAvailable.value = writeTeachingDraft(currentUser.value.id, detail.value.info.id, draft, attachment.value);
}
function resetForms() {
  hydratingDraft = true;
  editing.value = null;
  editorOpen.value = false;
  form.title = '';
  form.body = '';
  attachment.value = null;
  missingAttachment.value = '';
  restoredDraft.value = false;
  notice.title = '';
  notice.body = '';
  notice.requestKey = crypto.randomUUID();
  hydratingDraft = false;
}
function restoreDraft(id: number) {
  if (!teacher.value || !currentUser.value) return;
  const saved = readTeachingDraft(currentUser.value.id, id);
  if (!saved) return;
  hydratingDraft = true;
  if (saved.draft.material) {
    const material = saved.draft.material;
    editing.value = material.source;
    editorOpen.value = true;
    form.title = material.title;
    form.body = material.body;
    attachment.value = saved.file;
    missingAttachment.value = saved.file ? '' : material.attachmentName;
  }
  if (saved.draft.notice) Object.assign(notice, saved.draft.notice);
  restoredDraft.value = true;
  hydratingDraft = false;
}
async function loadDetail(id: number) {
  persistDraft();
  const sequence = ++detailSequence;
  detail.value = null;
  detailLoading.value = true;
  resetForms();
  try {
    const result = await api<TeachingDetail>(`${endpoint.value}/classes/${id}`);
    if (sequence === detailSequence) {
      detail.value = result;
      restoreDraft(id);
    }
  } catch (cause) {
    if (sequence === detailSequence) error.value = errorMessage(cause);
  } finally {
    if (sequence === detailSequence) detailLoading.value = false;
  }
}
async function load() {
  persistDraft();
  const sequence = ++loadSequence;
  detailSequence++;
  loading.value = true;
  error.value = '';
  success.value = '';
  try {
    if (!terms.value.length) terms.value = await api<AcademicTerm[]>('/learning/terms');
    const linked = learningLinkSelection(terms.value, route.query.term, route.query.week);
    if (linked) termId.value = linked.termId;
    if (!termId.value) termId.value = terms.value[0]?.id ?? 0;
    if (!termId.value) return;
    const [classRows, lessonRows, gradeRows, examRows] = await Promise.all([
      api<TeachingClass[]>(`${endpoint.value}/classes?termId=${termId.value}`),
      api<TeachingLesson[]>(`${endpoint.value}/timetable?termId=${termId.value}`),
      teacher.value ? Promise.resolve([]) : api<AcademicGrade[]>(`/learning/grades?termId=${termId.value}`),
      teacher.value ? Promise.resolve([]) : api<AcademicExam[]>(`/learning/exams?termId=${termId.value}`),
    ]);
    if (sequence !== loadSequence) return;
    classes.value = classRows;
    lessons.value = lessonRows;
    grades.value = gradeRows;
    exams.value = examRows;
    const today = shanghaiToday();
    weekStart.value = monday(
      linked?.date ??
        (today < (term.value?.startsOn ?? '')
          ? term.value!.startsOn
          : today > (term.value?.endsOn ?? '')
            ? term.value!.endsOn
            : today),
    );
    const selected = Number(route.query.class);
    const id = classRows.find(c => c.id === selected)?.id ?? classRows[0]?.id;
    if (id) await loadDetail(id);
  } catch (cause) {
    if (sequence === loadSequence) error.value = errorMessage(cause);
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}
async function changeTerm() {
  if (route.query.term !== undefined || route.query.week !== undefined)
    await router.replace({ query: { ...route.query, term: undefined, week: undefined } });
  else await load();
}
function requestConfirmation(message: string) {
  return confirmDialog(message, {
    title: '处理未保存的内容',
    confirmText: '确认继续',
    cancelText: '继续编辑',
    danger: true,
  });
}
async function openEditor(material: CourseMaterial | null) {
  if (
    materialDirty.value &&
    !(await requestConfirmation('当前资料有未保存内容。继续会放弃这些修改，并打开另一份资料；教学班通知会保留。'))
  )
    return;
  hydratingDraft = true;
  editing.value = material;
  editorOpen.value = true;
  form.title = material?.title ?? '';
  form.body = material?.body ?? '';
  attachment.value = null;
  missingAttachment.value = '';
  hydratingDraft = false;
  persistDraft();
  fileInput.value?.clear();
  error.value = '';
  success.value = '';
}
async function saveMaterial() {
  if (!detail.value || busy.value) return;
  if (missingAttachment.value) {
    error.value = '请重新选择待上传附件，或移除待上传附件后再保存。';
    return;
  }
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    const c = detail.value.info.id;
    const saved = await api<CourseMaterial>(
      editing.value ? `/teaching/classes/${c}/materials/${editing.value.id}` : `/teaching/classes/${c}/materials`,
      {
        method: editing.value ? 'PUT' : 'POST',
        body: JSON.stringify({ ...form, version: editing.value?.version ?? 0 }),
      },
    );
    editing.value = saved;
    if (attachment.value) {
      const data = new FormData();
      data.append('file', attachment.value);
      data.append('version', String(saved.version));
      editing.value = await api<CourseMaterial>(`/teaching/materials/${saved.id}/file`, { method: 'POST', body: data });
    }
    editorOpen.value = false;
    attachment.value = null;
    missingAttachment.value = '';
    persistDraft();
    await loadDetail(c);
    success.value = '草稿已保存，发布后学生可查看。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function changeState(material: CourseMaterial, status: CourseMaterial['status']) {
  if (busy.value || !detail.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api(`/teaching/materials/${material.id}/state`, {
      method: 'POST',
      body: JSON.stringify({ status, version: material.version }),
    });
    await loadDetail(detail.value.info.id);
    success.value =
      status === 'PUBLISHED'
        ? '课程资料已发布。'
        : status === 'WITHDRAWN'
          ? '资料已撤下，学生不再可见。'
          : '已转为草稿，可继续修订。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function publishNotice() {
  if (busy.value || !detail.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api(`/teaching/classes/${detail.value.info.id}/notices`, { method: 'POST', body: JSON.stringify(notice) });
    hydratingDraft = true;
    notice.title = '';
    notice.body = '';
    notice.requestKey = crypto.randomUUID();
    hydratingDraft = false;
    persistDraft();
    await loadDetail(detail.value.info.id);
    success.value = '通知已发布，并发送到当前教学班学生的个人消息。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
function fileChanged(event: Event) {
  attachment.value = (event.target as HTMLInputElement).files?.[0] ?? null;
  missingAttachment.value = '';
}
function removePendingAttachment() {
  attachment.value = null;
  missingAttachment.value = '';
  fileInput.value?.clear();
}
async function cancelEditor() {
  if (materialDirty.value && !(await requestConfirmation('继续会放弃当前资料的未保存内容，教学班通知会保留。'))) return;
  editorOpen.value = false;
  attachment.value = null;
  missingAttachment.value = '';
  persistDraft();
}
async function allowNavigation() {
  if (!currentUser.value) return true; // Session expiry and logout must always reach the login page.
  if (busy.value) {
    error.value = '正在提交，请等待操作完成后再离开。';
    return false;
  }
  persistDraft();
  if (hasDraft.value && !storageAvailable.value)
    return requestConfirmation('浏览器无法暂存草稿。当前页面内切换可保留，离开或刷新可能丢失未提交内容。');
  return true;
}
function beforeUnload(event: BeforeUnloadEvent) {
  persistDraft();
  if (busy.value || (hasDraft.value && (!storageAvailable.value || attachment.value))) {
    event.preventDefault();
    event.returnValue = '';
  }
}
watch(
  () => route.query.class,
  value => {
    const id = Number(value);
    if (classes.value.some(c => c.id === id)) {
      error.value = '';
      success.value = '';
      void loadDetail(id);
    }
  },
);
watch([() => route.query.term, () => route.query.week], () => {
  void load();
});
watch(
  () => [notice.title, notice.body],
  () => {
    if (!hydratingDraft) notice.requestKey = crypto.randomUUID();
  },
  { flush: 'sync' },
);
watch(
  () => [
    form.title,
    form.body,
    editorOpen.value,
    editing.value,
    attachment.value,
    missingAttachment.value,
    notice.title,
    notice.body,
    notice.requestKey,
  ],
  persistDraft,
  { flush: 'sync' },
);
onBeforeRouteLeave(allowNavigation);
onBeforeRouteUpdate(allowNavigation);
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload);
  void load();
});
onBeforeUnmount(() => {
  persistDraft();
  detailSequence++;
  loadSequence++;
  window.removeEventListener('beforeunload', beforeUnload);
});
</script>

<template>
  <div class="learning-page" :aria-busy="loading">
    <ServicePageHeader
      :context="teacher ? '教师教学服务' : '学生学习服务'"
      :title="pageTitle"
      :description="pageDescription"
    >
      <template #actions>
        <div class="term-picker">
          <span class="picker-label">学期</span>
          <BaseSelect
            v-model="termId"
            :options="terms.map(t => ({ value: t.id, label: t.name }))"
            :disabled="loading || busy"
            @change="changeTerm"
          />
        </div>
      </template>
    </ServicePageHeader>
    <nav ref="navRef" class="learning-tabs" :aria-label="teacher ? '教学服务栏目' : '学习服务栏目'">
      <RouterLink v-if="!teacher" to="/portal/schedule">
        <CalendarDays :size="16" aria-hidden="true" />
        我的日程
      </RouterLink>
      <RouterLink :to="base" :class="{ selected: tab === 'courses' }">
        <BookOpen :size="16" aria-hidden="true" />
        {{ teacher ? '教学班与资料' : '课程与资料' }}
      </RouterLink>
      <RouterLink :to="base + '/timetable'" :class="{ selected: tab === 'timetable' }">
        <CalendarDays :size="16" aria-hidden="true" />
        {{ teacher ? '每周授课安排' : '每周课程表' }}
      </RouterLink>
      <RouterLink v-if="!teacher" :to="base + '/grades'" :class="{ selected: tab === 'grades' }">
        <GraduationCap :size="16" aria-hidden="true" />
        课程成绩查询
      </RouterLink>
      <RouterLink v-if="!teacher" :to="base + '/exams'" :class="{ selected: tab === 'exams' }">
        <ClipboardList :size="16" aria-hidden="true" />
        考试时间与地点
      </RouterLink>
      <div class="learning-tabs-indicator" :style="indicatorStyle" aria-hidden="true"></div>
    </nav>
    <p v-if="error" class="alert" role="alert">
      {{ error }}
      <button class="text-button" :disabled="busy" @click="load">刷新数据</button>
    </p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <SkeletonBlock v-if="loading && !terms.length" variant="split" :rows="4" label="正在读取教学信息…" />
    <p v-else-if="!terms.length" class="empty-state">尚未维护学期，请联系教务信息维护岗位。</p>
    <template v-else-if="tab === 'courses'">
      <p v-if="!classes.length" class="empty-state">本学期暂无{{ teacher ? '有效授课' : '已关联' }}教学班。</p>
      <div v-else class="learning-grid">
        <aside class="course-directory" aria-label="教学班目录">
          <button
            v-for="c in classes"
            :key="c.id"
            class="course-choice"
            :aria-pressed="detail?.info.id === c.id"
            :disabled="busy || detailLoading"
            @click="selectClass(c.id)"
          >
            <BookOpen :size="18" />
            <span>
              <strong>{{ c.courseName }}</strong>
              <small>{{ c.name }}</small>
              <small>{{ c.credits }} 学分{{ teacher ? ' · ' + c.memberCount + ' 名学生' : '' }}</small>
            </span>
            <ArrowUpRight :size="14" />
          </button>
        </aside>
        <section class="portal-panel course-detail">
          <SkeletonBlock v-if="detailLoading" variant="detail" label="正在读取教学班…" />
          <template v-else-if="detail">
            <p v-if="teacher && hasDraft" class="draft-hint" role="status">
              {{ restoredDraft ? '已恢复此教学班未提交的内容。' : '未提交内容已暂存。'
              }}{{
                storageAvailable
                  ? '切换教学班或页面后可继续编辑，文字在当前浏览器标签页保留 24 小时；退出登录后清除。'
                  : '浏览器暂存不可用，请先完成提交再关闭或刷新。'
              }}
            </p>
            <div class="section-heading">
              <div>
                <span class="eyebrow">{{ detail.info.name }}</span>
                <h2>{{ detail.info.courseName }}</h2>
                <p class="muted">
                  {{ detail.instructors.map(i => i.fullName).join('、') }} · {{ detail.info.credits }} 学分
                </p>
              </div>
              <button v-if="teacher" class="button" :disabled="busy" @click="openEditor(null)">新建资料</button>
            </div>
            <form v-if="teacher && editorOpen" class="learning-form" @submit.prevent="saveMaterial">
              <div class="section-heading">
                <h3>{{ editing ? '编辑课程资料' : '新建课程资料' }}</h3>
                <button type="button" class="button compact" :disabled="busy" @click="cancelEditor">收起表单 ×</button>
              </div>
              <fieldset :disabled="busy">
                <label>
                  资料标题
                  <input v-model="form.title" required maxlength="160" />
                </label>
                <label>
                  正文
                  <textarea v-model="form.body" required maxlength="20000" rows="5" />
                </label>
                <div class="learning-attachment-field">
                  <FileDropZone
                    ref="fileInput"
                    compact
                    accept=".pdf,.txt"
                    :file-name="attachment?.name ?? ''"
                    title="选择或拖入课程附件（可选）"
                    :hint="`PDF 或 UTF-8 文本，最大 2 MB${editing?.fileName ? '；已有附件：' + editing.fileName : ''}`"
                    @change="fileChanged"
                    @clear="
                      attachment = null;
                      missingAttachment = '';
                    "
                  />
                </div>
              </fieldset>
              <p v-if="attachment || missingAttachment" class="muted">
                {{
                  missingAttachment ? '刷新后需重新选择附件：' + missingAttachment : '待上传附件：' + attachment?.name
                }}
                <button type="button" class="text-button" :disabled="busy" @click="removePendingAttachment">
                  移除待上传附件
                </button>
              </p>
              <div class="form-actions">
                <button class="button primary" :disabled="busy">{{ busy ? '正在保存…' : '保存草稿' }}</button>
                <button type="button" class="text-button" :disabled="busy" @click="cancelEditor">取消编辑</button>
              </div>
            </form>
            <h3 class="subsection-heading">
              <FileText :size="16" />
              课程资料
            </h3>
            <p v-if="!detail.materials.length" class="empty-state">
              {{ teacher ? '尚未添加课程资料。' : '教师尚未发布课程资料。' }}
            </p>
            <article v-for="m in detail.materials" :key="m.id" class="learning-entry">
              <div class="notice-heading">
                <h3>{{ m.title }}</h3>
                <span v-if="teacher" class="status-label">
                  {{ m.status === 'PUBLISHED' ? '已发布' : m.status === 'WITHDRAWN' ? '已撤下' : '草稿' }} · v{{
                    m.version + 1
                  }}
                </span>
              </div>
              <p class="preserve-lines">
                {{ m.body.length > 180 && !expandedMaterialIds[m.id] ? m.body.slice(0, 180).trimEnd() + '…' : m.body }}
              </p>
              <button
                v-if="m.body.length > 180"
                type="button"
                class="text-button"
                style="margin-bottom: 6px"
                @click="toggleMaterialBody(m.id)"
              >
                {{ expandedMaterialIds[m.id] ? '收起正文 ▴' : '展开全文 ▾' }}
              </button>
              <small class="muted">{{ m.authorName }} · {{ displayDate(m.updatedAt) }}</small>
              <div class="entry-actions">
                <DownloadLink v-if="m.fileName" class="text-button" :href="'/api/teaching-files/' + m.id">
                  <Download :size="14" />
                  {{ m.fileName }}
                </DownloadLink>
                <template v-if="teacher && m.authorId === currentUser?.id">
                  <button v-if="m.status === 'DRAFT'" class="text-button" :disabled="busy" @click="openEditor(m)">
                    编辑
                  </button>
                  <button
                    v-if="m.status === 'DRAFT'"
                    class="button compact"
                    :disabled="busy"
                    @click="changeState(m, 'PUBLISHED')"
                  >
                    发布资料
                  </button>
                  <button
                    v-if="m.status !== 'DRAFT'"
                    class="text-button"
                    :disabled="busy"
                    @click="changeState(m, 'DRAFT')"
                  >
                    转为草稿
                  </button>
                  <button
                    v-if="m.status === 'PUBLISHED'"
                    class="text-button"
                    :disabled="busy"
                    @click="changeState(m, 'WITHDRAWN')"
                  >
                    撤下
                  </button>
                </template>
              </div>
            </article>
            <div class="section-heading" style="margin-top: 20px">
              <h3 class="subsection-heading" style="margin: 0">
                <Bell :size="16" />
                教学班通知
              </h3>
              <button
                v-if="teacher"
                type="button"
                class="button compact"
                :disabled="busy"
                @click="noticeFormOpen = !noticeFormOpen"
              >
                {{ noticeFormOpen ? '收起通知表单 ×' : '发布班级通知' }}
              </button>
            </div>
            <form v-if="teacher && noticeFormOpen" class="learning-form" @submit.prevent="publishNotice">
              <fieldset :disabled="busy">
                <label>
                  通知标题
                  <input v-model="notice.title" required maxlength="160" />
                </label>
                <label>
                  通知内容
                  <textarea v-model="notice.body" required maxlength="10000" rows="3" />
                </label>
              </fieldset>
              <div class="form-actions">
                <button class="button primary compact" :disabled="busy">发布通知</button>
                <span class="muted">发送给当前教学班 {{ detail.members.length }} 名有效学生</span>
              </div>
            </form>
            <p v-if="!detail.notices.length" class="empty-state">暂无教学班通知。</p>
            <article v-for="n in detail.notices" :key="n.id" class="learning-entry">
              <h3>{{ n.title }}</h3>
              <p class="preserve-lines">{{ n.body }}</p>
              <small class="muted">{{ n.authorName }} · {{ displayDate(n.createdAt) }}</small>
            </article>
            <template v-if="teacher">
              <h3 class="subsection-heading">教学班学生 · {{ detail.members.length }} 人</h3>
              <div class="table-scroll">
                <table class="campus-table">
                  <thead>
                    <tr>
                      <th>姓名</th>
                      <th>学号</th>
                      <th>行政班</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="p in detail.members" :key="p.id">
                      <td>{{ p.fullName }}</td>
                      <td>{{ p.identityNumber }}</td>
                      <td>{{ p.className ?? '未分配' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </template>
        </section>
      </div>
    </template>
    <section v-else-if="tab === 'timetable'" class="portal-panel timetable-print-card">
      <div class="section-heading schedule-heading">
        <div>
          <h2>每周{{ teacher ? '授课' : '课程' }}安排</h2>
          <p class="muted">
            {{ displayDate(weekStart) }} — {{ displayDate(addDays(weekStart, 6)) }} · {{ term?.name ?? '' }}
          </p>
        </div>
        <div class="week-controls">
          <button
            class="button compact no-print"
            :disabled="weekStart <= termMonday"
            @click="weekStart = addDays(weekStart, -7)"
          >
            <ChevronLeft :size="16" aria-hidden="true" />
            上一周
          </button>
          <button
            class="button compact no-print"
            :disabled="weekStart >= lastMonday"
            @click="weekStart = addDays(weekStart, 7)"
          >
            下一周
            <ChevronRight :size="16" aria-hidden="true" />
          </button>
          <DownloadLink
            v-if="term && weekStart"
            class="button compact no-print"
            :href="`/api${endpoint}/timetable/export?termId=${termId}&from=${weekStart}`"
            expected-type="application/pdf"
          >
            <Download :size="15" />
            下载课表 PDF
          </DownloadLink>
        </div>
      </div>
      <p class="muted no-print">导出当前所选周的课程安排；下载 PDF 后可用阅读器打印。</p>
      <div class="week-scroll">
        <table class="week-schedule-table" aria-label="每周时段课程表">
          <thead>
            <tr>
              <th scope="col" class="week-period-col">时段</th>
              <th v-for="(day, n) in days" :key="day" scope="col" class="week-day-heading">
                {{ TIMETABLE_WEEKDAYS[n] }}
                <small>{{ day.slice(5).replace('-', '/') }}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="period in TIMETABLE_PERIODS" :key="period.key">
              <th scope="row" class="week-period-heading">
                <strong>{{ period.label }}</strong>
                <small>{{ period.range }}</small>
              </th>
              <td v-for="day in days" :key="`${day}-${period.key}`" class="week-slot-cell">
                <div v-if="slotLessons(day, period.key).length" class="week-slot-list">
                  <article v-for="l in slotLessons(day, period.key)" :key="l.id" class="week-lesson">
                    <small>{{ l.startsAt.slice(11, 16) }}—{{ l.endsAt.slice(11, 16) }}</small>
                    <strong>{{ l.courseName }}</strong>
                    <span>{{ l.classroom }}</span>
                    <span>{{ l.campusName }}</span>
                    <span>{{ teacher ? l.className : l.teacherName }}</span>
                  </article>
                </div>
                <span v-else class="week-cell-empty" aria-label="本时段无课程">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-else-if="tab === 'grades'" class="portal-panel">
      <div class="section-heading">
        <div>
          <span class="eyebrow">成绩记录</span>
          <h2>已发布成绩</h2>
        </div>
        <span class="muted">{{ grades.length }} 门课程</span>
      </div>
      <p v-if="!grades.length" class="empty-state">本学期暂无已发布的本人成绩。</p>
      <div v-else class="table-scroll">
        <table class="campus-table">
          <thead>
            <tr>
              <th>课程</th>
              <th>学分</th>
              <th>成绩</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="g in grades" :key="g.id">
              <td>{{ g.courseName }}</td>
              <td>{{ g.credits }}</td>
              <td class="score-cell">{{ g.score }}</td>
              <td>{{ displayDate(g.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <section v-else class="portal-panel">
      <div class="section-heading">
        <div>
          <span class="eyebrow">考务安排</span>
          <h2>考试安排</h2>
        </div>
        <span class="muted">{{ exams.length }} 场已发布考试</span>
      </div>
      <p v-if="!exams.length" class="empty-state">本学期暂无已发布的本人考试安排。</p>
      <article v-for="e in exams" :key="e.id" class="lesson-row">
        <div class="lesson-time">
          <strong>{{ displayDate(e.startsAt) }}</strong>
          <span>{{ e.startsAt.slice(11, 16) }}—{{ e.endsAt.slice(11, 16) }}</span>
        </div>
        <div>
          <h3>{{ e.courseName }}</h3>
          <p>{{ e.campusName }} · {{ e.classroom }}</p>
        </div>
      </article>
    </section>
    <p class="data-note">课程、成绩和考试均为毕业设计演示记录。教学安排以教务发布的数据为准。</p>
  </div>
</template>
