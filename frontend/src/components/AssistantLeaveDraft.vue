<script setup lang="ts">
import { ArrowUpRight, ClipboardCheck, Send } from '@lucide/vue'
import { emptyLeavePreview, leavePreviewFingerprint } from '../lib/assistantLeave'
import type { LeavePreviewFields, AssistantLeavePreview } from '../lib/assistantLeave'
import type { AssistantTaskView } from '../lib/assistantSessions'
import { useAssistantDraft } from '../lib/useAssistantDraft'
import { leaveTypes, leaveStatuses } from '../leave'
import BaseSelect from './BaseSelect.vue'
const statusNames: Record<string, string> = leaveStatuses
const reasonTypeOptions = [
  { value: '', label: '请选择类型' },
  ...Object.entries(leaveTypes).map(([key, label]) => ({ value: key, label })),
]

const props = defineProps<{ active: boolean; mode: 'LOCAL' | 'DEEPSEEK'; modelReady: boolean; sessionId: number | null; resumeTaskId: number | null }>()
const emit = defineEmits<{ saving: [value: boolean]; working: [value: boolean]; updated: [value: AssistantTaskView] }>()
const { fields, preview, saved, record, question, latest, consent, confirmed, busy, saving, error, uncertain, showComposer, checked, canSend, canSave, prepare, restore, save, finishLeave, allowLeave, keydown } = useAssistantDraft<LeavePreviewFields, AssistantLeavePreview>({
  props, kind: 'LEAVE', empty: emptyLeavePreview, fingerprint: leavePreviewFingerprint,
  emitSaving: value => emit('saving', value), emitWorking: value => emit('working', value), emitUpdated: value => emit('updated', value),
})
defineExpose({ allowLeave })
</script>
<template>
  <div class="assistant-draft">
    <div ref="scrollArea" class="assistant-draft-scroll">
      <div v-if="saved" class="assistant-draft-saved" role="status">
        <ClipboardCheck :size="24" /><h2>请假申请已建档</h2><p>申请 #{{ saved.id }} · {{ statusNames[saved.status] ?? saved.status }} · V{{ saved.version }}。当前状态已重新核对。</p>
        <RouterLink :to="`/portal/leave?id=${saved.id}&tab=details`">打开原申请继续办理 <ArrowUpRight :size="14" /></RouterLink>
        <button type="button" @click="restore(true)">再准备一份</button>
      </div>
      <template v-else>
        <div class="assistant-draft-intro"><h2>准备请假申请</h2><p>补齐信息 → 核对预览 → 确认保存草稿</p><small v-if="!latest && !fields.startDate">核对后的进度保存到本人对话，刷新可恢复。未核对的修改仍需暂存。</small></div>
        <details v-if="latest" class="assistant-draft-question"><summary>本次补充文字</summary><p>{{ latest }}</p></details>
        <p class="assistant-draft-reply" role="status">{{ busy ? '正在整理与核对…' : preview && !checked ? '资料已修改，请重新核对。' : preview?.ready ? '信息已补齐，请核对后确认保存草稿。' : preview?.summary ?? '先说明请假日期、时间、类型和事由，也可以直接填写下方表单。' }}</p>
        <ul v-if="checked && preview?.issues.length" class="assistant-draft-issues"><li v-for="issue in preview.issues" :key="issue">{{ issue }}</li></ul>
        <form @submit.prevent="prepare(false)">
          <fieldset :disabled="busy || saving || uncertain">
            <label>请假类型<BaseSelect v-model="fields.reasonType" :options="reasonTypeOptions" /></label>
            <div class="assistant-draft-grid"><label>开始日期<input v-model="fields.startDate" type="date" /></label><label>开始时间<input v-model="fields.startTime" type="time" /></label><label>结束日期<input v-model="fields.endDate" type="date" /></label><label>结束时间<input v-model="fields.endTime" type="time" /></label></div>
            <label>请假事由<textarea v-model="fields.reason" rows="2" maxlength="2000" placeholder="说明实际事由，确认模型提取的文字" /></label>
            <label>联系手机或邮箱<input v-model="fields.contact" type="text" maxlength="80" placeholder="仅在本地表单填写" autocomplete="off" /></label>
          </fieldset>
          <p v-if="checked && preview?.duration" class="assistant-draft-meta">请假时长：{{ preview.duration }}</p>
          <button type="submit" class="assistant-draft-check" :disabled="busy || saving || uncertain">核对并暂存</button><span v-if="!checked && preview" class="assistant-draft-meta">已修改，需重新核对</span>
        </form>
        <p v-if="preview?.eligibilityMessage" class="assistant-draft-meta">{{ preview.eligibilityMessage }}</p>
        <RouterLink v-if="preview?.source" class="assistant-draft-source" :to="preview.source.targetPath">演示办理指南 · V{{ preview.source.version }} <ArrowUpRight :size="12" /></RouterLink>
        <p v-if="uncertain" class="assistant-draft-issues">保存结果未确认。可以用相同资料重试，或先到<RouterLink to="/portal/leave">我的请假</RouterLink>核对，避免重复建档。</p>
      </template>
    </div>
    <div v-if="!saved" class="assistant-draft-bottom">
      <button v-if="mode === 'DEEPSEEK' && !uncertain" class="assistant-draft-compose-toggle" :disabled="busy || saving" @click="showComposer = !showComposer">{{ showComposer ? '收起文字补充' : '继续用文字调整' }}</button>
      <form v-if="mode === 'DEEPSEEK' && !uncertain && showComposer" class="assistant-draft-compose" @submit.prevent="prepare(true)">
        <label class="assistant-draft-consent"><input v-model="consent" type="checkbox" :disabled="busy || saving" />同意仅将本次补充文字发送至 DeepSeek。表单、联系方式与历史补充留在本地。</label>
        <div class="assistant-draft-input"><textarea ref="input" v-model="question" aria-label="补充请假信息" rows="2" maxlength="1000" placeholder="例如：下周一到周三，先帮我准备请假" :disabled="busy || saving" @keydown="keydown" /><button type="submit" aria-label="发送请假补充" :disabled="!canSend"><Send :size="16" /></button></div>
      </form>
      <label v-if="checked && preview?.ready" class="assistant-draft-consent assistant-draft-confirm"><input v-model="confirmed" type="checkbox" :disabled="saving || busy" />我已核对类型、起止时间、事由和联系方式，确认保存为本人草稿。</label>
      <button class="assistant-draft-save" :disabled="!canSave" @click="save">{{ saving ? '正在保存…' : uncertain ? '重试保存相同草稿' : '确认并保存草稿' }}</button>
    </div>
    <button v-if="record && !saved" class="assistant-draft-recover" :disabled="busy || saving" @click="restore()">恢复最新进度</button>
    <p v-if="error" class="assistant-draft-error" role="alert">{{ error }}</p>
    <Teleport to="body"><dialog ref="dialog" class="assistant-draft-dialog" aria-labelledby="assistant-draft-dialog-title" @cancel.prevent="finishLeave(false)"><h2 id="assistant-draft-dialog-title">请假预览有未暂存修改</h2><p>未暂存的修改会丢失；已核对的内容可从历史对话恢复。</p><div><button ref="cancelButton" @click="finishLeave(false)">继续填写</button><button @click="finishLeave(true)">放弃修改并离开</button></div></dialog></Teleport>
  </div>
</template>
<style scoped src="../styles/assistant-draft.css"></style>
