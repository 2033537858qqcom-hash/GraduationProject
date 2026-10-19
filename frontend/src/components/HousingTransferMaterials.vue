<script setup lang="ts">
import DownloadLink from '../components/DownloadLink.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { FileText, Paperclip, Upload } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { housingRequestKey, housingWhen, type HousingTransfer } from '../housing'
import { housingMaterialIssue, housingMaterialSize, housingMaterialUrl, type HousingMaterials } from '../lib/housingMaterials'
const props = defineProps<{ request: HousingTransfer; view: 'mine' | 'review'; busy: boolean; initialOpen: boolean }>()
const emit = defineEmits<{ changed: [version: number]; pending: [value: boolean]; working: [value: boolean] }>()
const opened = ref(props.initialOpen), data = ref<HousingMaterials | null>(null), loading = ref(false), saving = ref(false), error = ref(''), success = ref('')
const file = ref<File | null>(null), input = ref<HTMLInputElement | null>(null)
const editable = computed(() => props.view === 'mine' && ['DRAFT', 'RETURNED'].includes(props.request.status))
let sequence = 0, uploadKey = housingRequestKey()
const removeKeys = new Map<string, string>()
function clear() { file.value = null; if (input.value) input.value.value = ''; emit('pending', false); uploadKey = housingRequestKey() }
function choose(event: Event) {
  error.value = ''; success.value = ''
  const selected = (event.target as HTMLInputElement).files?.[0] ?? null
  if (selected) { const issue = housingMaterialIssue(selected); if (issue) { clear(); error.value = issue; return } }
  file.value = selected; uploadKey = housingRequestKey(); emit('pending', !!selected)
}
async function load(page = 1) {
  const stamp = ++sequence; loading.value = true; error.value = ''; data.value = null
  try { const result = await api<HousingMaterials>(`/housing/transfers/${props.view}/${props.request.id}/materials?page=${page}`); if (stamp === sequence) data.value = result }
  catch (cause) { if (stamp === sequence) error.value = errorMessage(cause) }
  finally { if (stamp === sequence) loading.value = false }
}
async function toggle() { if (saving.value) return; opened.value = !opened.value; if (opened.value) await load(data.value?.page ?? 1) }
async function upload() {
  if (!file.value || props.busy || saving.value || !data.value) return
  if (data.value.version !== props.request.version) { error.value = '申请已更新，请刷新整份申请后再维护材料。'; return }
  saving.value = true; emit('working', true); error.value = ''; success.value = ''
  const payload = new FormData(); payload.set('file', file.value); payload.set('version', String(data.value.version)); payload.set('requestKey', uploadKey)
  try {
    data.value = await api<HousingMaterials>(`/housing/transfers/mine/${props.request.id}/materials`, { method: 'POST', body: payload })
    clear(); emit('changed', data.value.version); success.value = '材料已上传，核对后再提交申请。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { saving.value = false; emit('working', false) }
}
async function remove(id: number) {
  if (!data.value || props.busy || saving.value || file.value) return
  if (data.value.version !== props.request.version) { error.value = '申请已更新，请刷新整份申请后再维护材料。'; return }
  saving.value = true; emit('working', true); error.value = ''; success.value = ''
  const fingerprint = `${id}:${data.value.version}`
  if (!removeKeys.has(fingerprint)) removeKeys.set(fingerprint, housingRequestKey())
  try {
    data.value = await api<HousingMaterials>(`/housing/transfers/mine/${props.request.id}/materials/${id}/remove`, { method: 'POST', body: JSON.stringify({ requestKey: removeKeys.get(fingerprint), version: data.value.version, comment: '从当前材料清单移除，保留已提交版本' }) })
    emit('changed', data.value.version); success.value = '已移出当前清单，已提交版本中的材料仍保留。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { saving.value = false; emit('working', false) }
}
watch(() => [props.request.id, props.request.version, props.view], () => { if (opened.value && !saving.value && data.value?.version !== props.request.version) void load() })
onMounted(() => { if (opened.value) void load() })
onBeforeUnmount(() => { sequence++; emit('pending', false); emit('working', false) })
</script>
<template>
  <section class="housing-materials" :aria-label="'申请 #' + request.id + ' 的材料'">
    <button type="button" class="text-button" :aria-expanded="opened" :disabled="saving" @click="toggle"><Paperclip :size="15" />申请材料与提交版本</button>
    <div v-if="opened" class="housing-material-body">
      <p v-if="error" class="alert" role="alert">{{ error }}<button class="text-button" type="button" :disabled="saving || busy" @click="load()">重新读取材料</button></p>
      <p v-if="success" class="success-message" role="status">{{ success }}</p>
      <p v-if="loading" role="status">正在读取材料…</p>
      <template v-else-if="data">
        <template v-if="view === 'mine'">
          <h3>{{ editable ? '当前待提交材料' : '当前材料清单' }}</h3>
          <p v-if="!data.current.length" class="muted">尚无附件，可使用材料说明继续办理。</p>
          <ul v-else class="housing-file-list"><li v-for="attachment in data.current" :key="attachment.id"><DownloadLink :href="housingMaterialUrl(view, request.id, attachment.id) ?? undefined" class="housing-file-link"><FileText :size="15" /><span>{{ attachment.name }}<small>{{ housingMaterialSize(attachment.size) }}</small></span></DownloadLink><button v-if="editable" class="text-button" type="button" :disabled="saving || busy || !!file" @click="remove(attachment.id)">移出清单</button></li></ul>
          <form v-if="editable" class="housing-upload" @submit.prevent="upload"><label>添加材料<input ref="input" type="file" accept=".pdf,.png,.jpg,.jpeg" :disabled="saving || busy || data.current.length >= 3" @change="choose" /></label><p class="muted">PDF、JPEG 或 PNG，单份最多 2 MB，当前最多 3 份。PDF 不加密且最多 20 页。提交后冻结当次清单。</p><div class="form-actions"><button class="button compact" :disabled="saving || busy || !file"><Upload :size="14" />{{ saving ? '正在保存…' : '上传材料' }}</button><button v-if="file" type="button" class="text-button" :disabled="saving" @click="clear">清除选择</button></div><p v-if="file" class="data-note">{{ file.name }} 尚未上传，请先上传或清除选择再提交申请。</p></form>
        </template>
        <h3>已提交材料版本 <small>共 {{ data.total }} 版</small></h3>
        <p v-if="!data.total" class="muted">{{ request.status === 'DRAFT' ? '提交申请后生成第一份材料快照。' : '尚无留存的提交快照，历史申请不会补造旧材料版本。' }}</p>
        <p v-else-if="view === 'review'" class="muted">仅显示已提交版本；学生待补交时的新材料，重新提交后才可查看。</p>
        <details v-for="(submission, index) in data.submissions" :key="submission.id" class="housing-submission" :open="index === 0"><summary>材料版本 {{ submission.revision }} · {{ housingWhen(submission.createdAt) }}</summary><p class="muted">对应申请 V{{ submission.requestVersion }}</p><p>{{ submission.reason }}</p><p class="muted">{{ submission.preferredArea }} · {{ submission.materialNote }}</p><p v-if="!submission.files.length" class="muted">该版本未提交附件。</p><ul v-else class="housing-file-list"><li v-for="attachment in submission.files" :key="attachment.id"><DownloadLink :href="housingMaterialUrl(view, request.id, attachment.id, submission.id) ?? undefined" class="housing-file-link"><FileText :size="15" /><span>{{ attachment.name }}<small>{{ housingMaterialSize(attachment.size) }}</small></span></DownloadLink></li></ul></details>
        <div v-if="data.total > data.pageSize" class="pagination"><span>第 {{ data.page }} 页</span><button class="text-button" :disabled="loading || saving || busy || data.page <= 1" @click="load(data.page - 1)">上一页</button><button class="text-button" :disabled="loading || saving || busy || data.page * data.pageSize >= data.total" @click="load(data.page + 1)">下一页</button></div>
        <p class="data-note">材料仅供本人和当前有权初审的学院人员下载；不发送给模型。移出当前清单不会删除已提交的历史快照。</p>
      </template>
    </div>
  </section>
</template>
<style scoped>
.housing-materials { margin-top: 14px; border-top: 1px solid var(--line); padding-top: 12px; }
.housing-material-body { padding-block: 12px 0; }
.housing-material-body h3 { font-size: 14px; margin: 12px 0; }
.housing-material-body h3 small { margin-left: 6px; color: var(--muted); font-weight: 400; }
.housing-file-list { list-style: none; padding: 0; margin: 10px 0; }
.housing-file-list li { display: flex; align-items: center; gap: 12px; justify-content: space-between; padding: 9px 0; border-bottom: 1px solid var(--line); }
.housing-file-link { display: flex; align-items: flex-start; gap: 7px; min-width: 0; color: var(--brand); }
.housing-file-link svg { flex-shrink: 0; margin-top: 3px; }
.housing-file-link span { overflow-wrap: anywhere; }
.housing-file-link small { display: block; color: var(--muted); margin-top: 3px; }
.housing-upload { padding-block: 8px; }
.housing-upload input { max-width: 100%; }
.housing-submission { border: 1px solid var(--line); margin: 10px 0; padding: 12px; }
.housing-submission summary { cursor: pointer; font-size: 13px; }
</style>
