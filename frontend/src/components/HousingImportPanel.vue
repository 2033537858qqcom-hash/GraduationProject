<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { Check, ChevronDown, Download, FileSpreadsheet, Upload } from '@lucide/vue'
import { api, ApiError, errorMessage } from '../lib/api'
import { downloadFile } from '../lib/download'
import { housingWhen } from '../housing'
import { decodeHousingImport, housingImportCanConfirm, housingImportFileIssue, type HousingImportBatch, type HousingImportSummary } from '../lib/housingImports'
const props = defineProps<{ buildingId: number; busy: boolean }>()
const emit = defineEmits<{ changed: []; working: [value: boolean]; pending: [value: boolean] }>()
const open = ref(false), working = ref(false), csv = ref(''), source = ref('粘贴的入住名单.csv'), error = ref(''), notice = ref('')
const fileInput = ref<HTMLInputElement | null>(null), batch = ref<HousingImportBatch | null>(null), recent = ref<HousingImportSummary[]>([])
const stamp = ref(''), now = ref(Date.now()), uncertain = ref(false)
const fingerprint = computed(() => JSON.stringify([props.buildingId, csv.value, source.value]))
const confirmable = computed(() => housingImportCanConfirm(batch.value, props.buildingId, stamp.value === fingerprint.value, now.value))
const retryable = computed(() => uncertain.value && batch.value?.status === 'PREVIEW' && !!batch.value.token && batch.value.buildingId === props.buildingId && stamp.value === fingerprint.value)
const pending = computed(() => !!csv.value.trim())
let sequence = 0, timer: ReturnType<typeof setInterval> | undefined
function setWorking(value: boolean) { working.value = value; emit('working', value) }
function clear() { csv.value = ''; batch.value = null; stamp.value = ''; error.value = ''; notice.value = ''; uncertain.value = false; if (fileInput.value) fileInput.value.value = '' }
async function choose(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || working.value || props.busy) return
  error.value = ''; notice.value = ''; batch.value = null; uncertain.value = false
  const issue = housingImportFileIssue(file)
  if (issue) { error.value = issue; (event.target as HTMLInputElement).value = ''; return }
  setWorking(true)
  try { csv.value = decodeHousingImport(new Uint8Array(await file.arrayBuffer())); source.value = file.name }
  catch (cause) { error.value = errorMessage(cause) }
  finally { setWorking(false) }
}
async function history() {
  const attempt = ++sequence
  try { const rows = await api<HousingImportSummary[]>('/housing/imports?buildingId=' + props.buildingId); if (attempt === sequence) recent.value = rows }
  catch (cause) { if (attempt === sequence) error.value = errorMessage(cause) }
}
async function toggle() { if (working.value) return; open.value = !open.value; if (open.value) await history() }
async function download() {
  if (working.value || props.busy) return
  setWorking(true); error.value = ''
  try {
    await downloadFile('/api/housing/imports/template?buildingId=' + props.buildingId, '住宿入住模板.csv', 'text/csv')
    notice.value = '模板已下载。填写学号和入住说明后，选择文件进行校验。'
  } catch (cause) { error.value = errorMessage(cause) }
  finally { setWorking(false) }
}
async function preview() {
  if (working.value || props.busy || !csv.value.trim()) return
  setWorking(true); error.value = ''; notice.value = ''; batch.value = null; uncertain.value = false
  const current = fingerprint.value
  try {
    const result = await api<HousingImportBatch>('/housing/imports/preview', { method: 'POST', body: JSON.stringify({ buildingId: props.buildingId, csv: csv.value, sourceName: source.value }) })
    if (current !== fingerprint.value) return
    batch.value = result; stamp.value = current; now.value = Date.now()
  } catch (cause) { error.value = errorMessage(cause) }
  finally { setWorking(false) }
}
async function confirm() {
  now.value = Date.now()
  if (working.value || props.busy || (!confirmable.value && !retryable.value) || !batch.value?.token) return
  setWorking(true); error.value = ''; notice.value = ''
  try {
    batch.value = await api<HousingImportBatch>('/housing/imports/' + batch.value.token + '/confirm', { method: 'POST' })
    uncertain.value = false; csv.value = ''; if (fileInput.value) fileInput.value.value = ''
    notice.value = '整批入住已完成，学生已收到入住通知。'; emit('changed'); await history()
  } catch (cause) {
    uncertain.value = !(cause instanceof ApiError) || cause.status === 0 || cause.status >= 500
    error.value = errorMessage(cause) + (uncertain.value ? '。可用同一批次重试确认，系统会返回已有结果。' : '')
    if (cause instanceof ApiError && cause.status === 409 && batch.value) batch.value.canConfirm = false
  } finally { setWorking(false) }
}
async function receipt(token: string) {
  if (working.value || props.busy || (pending.value && !window.confirm('当前名单尚未完成，打开回执会清除本次输入。继续？'))) return
  setWorking(true); error.value = ''; notice.value = ''
  try { const result = await api<HousingImportBatch>('/housing/imports/' + token); clear(); batch.value = result }
  catch (cause) { error.value = errorMessage(cause) }
  finally { setWorking(false) }
}
function beforeUnload(event: BeforeUnloadEvent) { if (pending.value || working.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(() => !working.value && (!pending.value || window.confirm('入住名单尚未完成，离开后需要重新选择或粘贴。确认离开？')))
watch(() => props.buildingId, () => { sequence++; batch.value = null; recent.value = []; stamp.value = ''; uncertain.value = false; if (open.value) { notice.value = csv.value ? '负责楼栋已切换，请核对名单并重新校验。' : ''; void history() } })
watch(fingerprint, () => { uncertain.value = false })
watch(pending, value => emit('pending', value))
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); timer = setInterval(() => { now.value = Date.now() }, 30000) })
onBeforeUnmount(() => { sequence++; clearInterval(timer); window.removeEventListener('beforeunload', beforeUnload); emit('working', false); emit('pending', false) })
</script>
<template>
  <section class="portal-panel housing-import" aria-label="批量登记入住">
    <button class="housing-import-heading" type="button" :aria-expanded="open" :disabled="working" @click="toggle"><FileSpreadsheet :size="18" /><span><strong>批量登记入住</strong><small>下载本楼空闲床位模板，校验名单后整批登记</small></span><ChevronDown :size="16" :class="{ expanded: open }" /></button>
    <div v-if="open" class="housing-import-body">
      <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="notice" class="success-message" role="status">{{ notice }}</p>
      <template v-if="batch?.status !== 'COMPLETED'">
      <div class="housing-import-intro"><div><h3>1. 准备名单</h3><p class="muted">模板最多含本楼前 200 个空闲床位。填写学号和入住说明，未填写行会跳过；房间及床位编号须与配置一致。</p></div><button type="button" class="button compact" :disabled="working || busy" @click="download"><Download :size="14" />下载本楼模板</button></div>
      <p class="data-note">仅支持 CSV UTF-8，最多 100 KB、200 名学生。学号列请设为文本，保留开头的 0。此操作用于初始入住；已有住宿请使用调宿流程。</p>
      <label class="housing-csv-file">选择入住名单<input ref="fileInput" type="file" accept=".csv,text/csv" :disabled="working || busy" @change="choose" /></label>
      <label class="housing-csv-input">或粘贴 CSV 内容<textarea v-model="csv" rows="4" maxlength="102400" spellcheck="false" :disabled="working || busy" placeholder="学号,房间编号,床位编号,入住说明" /></label>
      <div class="form-actions"><button type="button" class="button compact" :disabled="working || busy || !csv.trim()" @click="preview"><Upload :size="14" />{{ working ? '正在处理…' : '校验并预览' }}</button><button v-if="csv || batch" type="button" class="text-button" :disabled="working || busy" @click="clear">清除本次输入</button><span v-if="csv" class="muted">{{ source }}</span></div>
      </template>
      <section v-if="batch" class="housing-import-preview" aria-label="名单校验结果">
        <h3>{{ batch.status === 'COMPLETED' ? '入住回执 #' + batch.id : '2. 核对校验结果' }}</h3><p>{{ batch.campusName }} · {{ batch.buildingName }} · 共 {{ batch.total }} 人<span v-if="batch.status !== 'COMPLETED'">，通过 {{ batch.valid }} 行，待修正 {{ batch.invalid }} 行</span><span v-if="batch.ignored">，跳过 {{ batch.ignored }} 行</span></p>
        <p v-if="batch.status === 'COMPLETED'" class="muted">{{ batch.sourceName }} · {{ housingWhen(batch.completedAt) }} · 已登记 {{ batch.total }} 人</p>
        <p v-else-if="stamp !== fingerprint" class="alert" role="status">名单已修改，请重新校验。以下为修改前的预览。</p>
        <p v-else-if="batch.status === 'INVALID'" class="data-note">请修正以下问题后重新校验。当前未登记任何入住记录。</p>
        <p v-else class="data-note">预览有效至 {{ housingWhen(batch.expiresAt) }}。确认前会再次核对权限、学生和床位；任何一行不通过，整批不入库。</p>
        <div class="housing-import-table" tabindex="0" role="region" aria-label="逐行校验，可横向或纵向滚动"><table><thead><tr><th>CSV 行</th><th>学生</th><th>目标床位</th><th>入住说明</th><th>{{ batch.status === 'COMPLETED' ? '入住记录' : '校验结果' }}</th></tr></thead><tbody><tr v-for="row in batch.rows" :key="row.line"><td>{{ row.line }}</td><td>{{ row.studentName || '未匹配' }}<small>{{ row.username || '缺少学号' }}</small></td><td>{{ row.place || '未匹配床位' }}<small>{{ row.roomCode }} / {{ row.bedCode }}</small></td><td>{{ row.reason }}</td><td><template v-if="row.assignmentId">#{{ row.assignmentId }} · 已登记</template><ul v-else-if="row.errors.length" class="housing-import-errors"><li v-for="issue in row.errors" :key="issue">{{ issue }}</li></ul><span v-else class="housing-import-valid"><Check :size="13" />通过</span></td></tr></tbody></table></div>
        <div v-if="batch.status === 'PREVIEW'" class="form-actions"><button type="button" class="button primary compact" :disabled="working || busy || (!confirmable && !retryable)" @click="confirm">{{ working ? '正在提交…' : uncertain ? '重试确认（同一批次）' : '确认导入 ' + batch.total + ' 条' }}</button><span v-if="!confirmable && !retryable && stamp === fingerprint" class="muted">预览已失效，请重新校验。</span></div>
        <div v-if="batch.status === 'COMPLETED'" class="form-actions"><button type="button" class="button compact" :disabled="working || busy" @click="clear">准备下一批名单</button><span class="muted">回执已保存，可在下方重新查看。</span></div>
      </section>
      <section class="housing-import-recent" aria-label="最近导入回执"><h3>最近完成的导入</h3><p v-if="!recent.length" class="muted">当前楼栋还没有由你完成的导入。</p><ul v-else><li v-for="item in recent" :key="item.id"><span>#{{ item.id }} · {{ item.sourceName }}<small>{{ item.total }} 人 · {{ housingWhen(item.completedAt) }}</small></span><button type="button" class="text-button" :disabled="working || busy" @click="receipt(item.token)">查看回执</button></li></ul></section>
    </div>
  </section>
</template>
<style scoped>
.housing-import { margin-block: 18px; padding: 0; overflow: hidden; }
.housing-import-heading { width: 100%; display: flex; align-items: center; gap: 12px; padding: 18px 20px; border: 0; background: transparent; color: inherit; text-align: left; cursor: pointer; }
.housing-import-heading > span { flex: 1; min-width: 0; }
.housing-import-heading strong { display: block; font-size: 15px; font-weight: 600; }
.housing-import-heading small { display: block; margin-top: 5px; color: var(--muted); font-size: 12px; line-height: 1.6; }
.housing-import-heading svg { flex-shrink: 0; }
.housing-import-heading .expanded { transform: rotate(180deg); }
.housing-import-body { padding: 0 20px 20px; border-top: 1px solid var(--line); }
.housing-import-body h3 { font-size: 14px; margin: 18px 0 10px; }
.housing-import-intro { display: flex; align-items: center; gap: 18px; justify-content: space-between; }
.housing-import-intro .button { flex-shrink: 0; }
.housing-import-body .muted, .housing-import-body .data-note { font-size: 12px; line-height: 1.8; }
.housing-csv-file, .housing-csv-input { display: grid; gap: 8px; margin-block: 14px; font-size: 13px; }
.housing-csv-file input { width: 100%; min-width: 0; }
.housing-csv-input textarea { width: 100%; min-width: 0; resize: vertical; font: 12px/1.8 ui-monospace, monospace; }
.housing-import-body .form-actions { flex-wrap: wrap; gap: 12px; }
.housing-import-body .form-actions .muted { overflow-wrap: anywhere; }
.housing-import-table { max-width: 100%; overflow: auto; max-height: 350px; margin-block: 14px; border: 1px solid var(--line); }
.housing-import-table table { width: 100%; min-width: 660px; border-collapse: collapse; font-size: 12px; text-align: left; }
.housing-import-table th { background: var(--surface, #f6f8fa); position: sticky; top: 0; z-index: 1; font-weight: 500; }
.housing-import-table td, .housing-import-table th { padding: 12px; border-bottom: 1px solid var(--line); vertical-align: top; line-height: 1.7; overflow-wrap: anywhere; max-width: 240px; }
.housing-import-table small { display: block; color: var(--muted); margin-top: 3px; }
.housing-import-errors { margin: 0; padding-left: 14px; color: #a23b35; }
.housing-import-valid { display: inline-flex; align-items: center; gap: 4px; color: #34634e; }
.housing-import-recent { margin-top: 22px; border-top: 1px solid var(--line); }
.housing-import-recent ul { margin: 0; padding: 0; list-style: none; }
.housing-import-recent li { display: flex; justify-content: space-between; align-items: center; gap: 14px; padding-block: 10px; border-bottom: 1px solid var(--line); font-size: 13px; }
.housing-import-recent li span { min-width: 0; overflow-wrap: anywhere; }
.housing-import-recent small { display: block; color: var(--muted); margin-top: 4px; font-size: 12px; }
.housing-import-recent button { flex-shrink: 0; }
@media (max-width: 600px) { .housing-import-heading { padding: 16px; } .housing-import-body { padding-inline: 14px; } .housing-import-intro { display: block; } .housing-import-intro .button { margin-top: 8px; } }
</style>
