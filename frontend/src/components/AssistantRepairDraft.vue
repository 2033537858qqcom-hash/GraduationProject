<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowUpRight, ClipboardCheck, Send } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { emptyRepairPreview, repairPreviewFingerprint } from '../lib/assistantRepair'
import type { AssistantRepairPreview, RepairPlace, RepairPreviewFields } from '../lib/assistantRepair'
import type { AssistantTaskView } from '../lib/assistantSessions'
import { useAssistantDraft } from '../lib/useAssistantDraft'
import { repairCategories, repairStatuses } from '../repairs'
import BaseSelect from './BaseSelect.vue'
const categoryOptions = [
  { value: '', label: '请选择类别' },
  ...Object.entries(repairCategories).map(([key, label]) => ({ value: key, label })),
]

const props = defineProps<{ active: boolean; mode: 'LOCAL' | 'DEEPSEEK'; modelReady: boolean; sessionId: number | null; resumeTaskId: number | null }>()
const emit = defineEmits<{ saving: [value: boolean]; working: [value: boolean]; updated: [value: AssistantTaskView] }>()
const { fields, preview, saved, record, question, latest, consent, confirmed, busy, saving, error, uncertain, showComposer, checked, canSend, canSave, prepare, restore, save, finishLeave, allowLeave, keydown } = useAssistantDraft<RepairPreviewFields, AssistantRepairPreview>({
  props, kind: 'REPAIR', empty: emptyRepairPreview, fingerprint: repairPreviewFingerprint,
  emitSaving: value => emit('saving', value), emitWorking: value => emit('working', value), emitUpdated: value => emit('updated', value),
})
defineExpose({ allowLeave })
const places = ref<RepairPlace[]>([]), campuses = ref<{ id: number; name: string }[]>([]), campusId = ref(''), placeNote = ref('')
const visiblePlaces = computed(() => places.value.filter(item => !campusId.value || String(item.campusId) === campusId.value))
const campusOptions = computed(() => [
  { value: '', label: '请选择校区' },
  ...campuses.value.map(campus => ({ value: String(campus.id), label: campus.name })),
])
const locationOptions = computed(() => [
  { value: '', label: '请选择开放地点' },
  ...(fields.locationId && !visiblePlaces.value.some(item => item.id === fields.locationId)
    ? [{ value: String(fields.locationId), label: `${preview.value?.locationName || '当前选择'}（当前选择）` }]
    : []),
  ...visiblePlaces.value.map(place => ({ value: String(place.id), label: place.name })),
])
const selectedLocationId = computed({
  get: () => fields.locationId ? String(fields.locationId) : '',
  set: (val: string | number) => {
    fields.locationId = val ? Number(val) : null
  },
})
async function loadPlaces() {
  try {
    const catalog = await api<{ campuses: { id: number; name: string }[] }>('/repairs/catalog')
    campuses.value = catalog.campuses
    const page = await api<{ items: RepairPlace[]; total: number }>('/repairs/locations?pageSize=100')
    places.value = page.items
    placeNote.value = page.total > page.items.length ? '开放地点超过 100 条，请到报修页面搜索后办理。' : ''
    const selected = page.items.find(item => item.id === fields.locationId)
    if (selected) campusId.value = String(selected.campusId)
    else if (!campusId.value) campusId.value = String(catalog.campuses[0]?.id ?? '')
  } catch (cause) { placeNote.value = errorMessage(cause) }
}
function onCampusChange() {
  if (fields.locationId && !places.value.some(item => item.id === fields.locationId && String(item.campusId) === campusId.value)) {
    fields.locationId = null
  }
}
watch(() => props.active, active => { if (active) void loadPlaces() }, { immediate: true })
watch(() => fields.locationId, id => { const selected = places.value.find(item => item.id === id); if (selected) campusId.value = String(selected.campusId) })
</script>
<template>
  <div class="assistant-draft">
    <div ref="scrollArea" class="assistant-draft-scroll">
      <div v-if="saved" class="assistant-draft-saved" role="status">
        <ClipboardCheck :size="24" /><h2>报修草稿已建档</h2><p>报修 #{{ saved.id }} · {{ repairStatuses[saved.status] ?? saved.status }} · V{{ saved.version }}。当前状态已重新核对。</p>
        <RouterLink :to="`/repairs/mine?id=${saved.id}&tab=details`">打开原报修继续办理 <ArrowUpRight :size="14" /></RouterLink>
        <button type="button" @click="restore(true)">再准备一份</button>
      </div>
      <template v-else>
        <div class="assistant-draft-intro"><h2>准备报修草稿</h2><p>补齐现象 → 选择地点 → 核对后保存草稿</p><small v-if="!latest && !fields.title">文字只整理类别、标题和现象。地点和联系方式留在本地，刷新后重新核对。</small></div>
        <details v-if="latest" class="assistant-draft-question"><summary>本次补充文字</summary><p>{{ latest }}</p></details>
        <p class="assistant-draft-reply" role="status">{{ busy ? '正在整理与核对…' : preview && !checked ? '资料已修改，请重新核对。' : preview?.ready ? '信息已补齐，请核对后确认保存草稿。' : preview?.summary ?? '先说明故障类别、标题和现象，地点与联系方式在下方填写。' }}</p>
        <ul v-if="checked && preview?.issues.length" class="assistant-draft-issues"><li v-for="issue in preview.issues" :key="issue">{{ issue }}</li></ul>
        <form @submit.prevent="prepare(false)">
          <fieldset :disabled="busy || saving || uncertain">
            <label>故障类别<BaseSelect v-model="fields.category" :options="categoryOptions" /></label>
            <label>故障标题<input v-model="fields.title" type="text" maxlength="100" placeholder="用简短标题说明故障" /></label>
            <label>故障现象<textarea v-model="fields.description" rows="2" maxlength="2000" placeholder="只保留实际看到的现象" /></label>
            <div class="assistant-draft-grid"><label>所在校区<BaseSelect v-model="campusId" :options="campusOptions" @change="onCampusChange" /></label><label>报修地点<BaseSelect v-model="selectedLocationId" :options="locationOptions" /></label></div>
            <label>具体位置<input v-model="fields.locationDetail" type="text" maxlength="300" placeholder="可选，例如楼层或方位，不由模型猜测" /></label>
            <label>联系手机或邮箱<input v-model="fields.contact" type="text" maxlength="80" placeholder="仅在本地表单填写" autocomplete="off" /></label>
            <label>方便联系的时间<input v-model="fields.contactWindow" type="text" maxlength="100" placeholder="可选，仅在本地填写" /></label>
          </fieldset>
          <p v-if="checked && preview?.locationName" class="assistant-draft-meta">已选地点：{{ preview.locationName }}</p>
          <p v-if="placeNote" class="assistant-draft-meta">{{ placeNote }}</p>
          <button type="submit" class="assistant-draft-check" :disabled="busy || saving || uncertain">核对并暂存</button><span v-if="!checked && preview" class="assistant-draft-meta">已修改，需重新核对</span>
        </form>
        <p v-if="preview?.eligibilityMessage" class="assistant-draft-meta">{{ preview.eligibilityMessage }}</p>
        <RouterLink v-if="preview?.source" class="assistant-draft-source" :to="preview.source.targetPath">演示办理指南 · V{{ preview.source.version }} <ArrowUpRight :size="12" /></RouterLink>
        <p v-if="uncertain" class="assistant-draft-issues">保存结果未确认。可以用相同资料重试，或先到<RouterLink to="/repairs/mine">我的校园报修</RouterLink>核对，避免重复建档。</p>
      </template>
    </div>
    <div v-if="!saved" class="assistant-draft-bottom">
      <button v-if="mode === 'DEEPSEEK' && !uncertain" class="assistant-draft-compose-toggle" :disabled="busy || saving" @click="showComposer = !showComposer">{{ showComposer ? '收起文字补充' : '继续用文字调整' }}</button>
      <form v-if="mode === 'DEEPSEEK' && !uncertain && showComposer" class="assistant-draft-compose" @submit.prevent="prepare(true)">
        <label class="assistant-draft-consent"><input v-model="consent" type="checkbox" :disabled="busy || saving" />同意仅将本次补充文字发送至 DeepSeek。地点、联系方式和已填表单留在本地。</label>
        <div class="assistant-draft-input"><textarea ref="input" v-model="question" aria-label="补充报修信息" rows="2" maxlength="1000" placeholder="例如：教学楼走廊灯不亮，标题先写照明故障" :disabled="busy || saving" @keydown="keydown" /><button type="submit" aria-label="发送报修补充" :disabled="!canSend"><Send :size="16" /></button></div>
      </form>
      <label v-if="checked && preview?.ready" class="assistant-draft-consent assistant-draft-confirm"><input v-model="confirmed" type="checkbox" :disabled="saving || busy" />我已核对类别、现象、开放地点和联系方式，确认保存为本人草稿。</label>
      <button class="assistant-draft-save" :disabled="!canSave" @click="save">{{ saving ? '正在保存…' : uncertain ? '重试保存相同草稿' : '确认并保存草稿' }}</button>
    </div>
    <button v-if="record && !saved" class="assistant-draft-recover" :disabled="busy || saving" @click="restore()">恢复最新进度</button>
    <p v-if="error" class="assistant-draft-error" role="alert">{{ error }}</p>
    <Teleport to="body"><dialog ref="dialog" class="assistant-draft-dialog" aria-labelledby="assistant-repair-draft-dialog-title" @cancel.prevent="finishLeave(false)"><h2 id="assistant-repair-draft-dialog-title">报修预览有未暂存修改</h2><p>未暂存的修改会丢失；已核对的内容可从历史对话恢复。</p><div><button ref="cancelButton" @click="finishLeave(false)">继续填写</button><button @click="finishLeave(true)">放弃修改并离开</button></div></dialog></Teleport>
  </div>
</template>
<style scoped src="../styles/assistant-draft.css"></style>
