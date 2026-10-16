<script setup lang="ts">
import { ArrowUpRight, ClipboardCheck, Send } from '@lucide/vue'
import { emptyHousingPreview, housingPreviewFingerprint } from '../lib/assistantHousing'
import type { HousingPreviewFields, AssistantHousingPreview } from '../lib/assistantHousing'
import type { AssistantTaskView } from '../lib/assistantSessions'
import { useAssistantDraft } from '../lib/useAssistantDraft'
import { transferStatus } from '../housing'
const props = defineProps<{ active: boolean; mode: 'LOCAL' | 'DEEPSEEK'; modelReady: boolean; sessionId: number | null; resumeTaskId: number | null }>()
const emit = defineEmits<{ saving: [value: boolean]; working: [value: boolean]; updated: [value: AssistantTaskView] }>()
const { fields, preview, saved, record, question, latest, consent, confirmed, busy, saving, error, uncertain, showComposer, checked, canSend, canSave, prepare, restore, save, finishLeave, allowLeave, keydown } = useAssistantDraft<HousingPreviewFields, AssistantHousingPreview>({
  props, kind: 'HOUSING', empty: emptyHousingPreview, fingerprint: housingPreviewFingerprint,
  emitSaving: value => emit('saving', value), emitWorking: value => emit('working', value), emitUpdated: value => emit('updated', value),
})
defineExpose({ allowLeave })
</script>
<template>
  <div class="assistant-draft" aria-label="调宿草稿准备">
    <div ref="scrollArea" class="assistant-draft-scroll">
      <div v-if="saved" class="assistant-draft-saved" role="status"><ClipboardCheck :size="24" /><h2>调宿草稿已保存</h2><p>申请 #{{ saved.id }} · {{ transferStatus[saved.status] ?? saved.status }}。请进入原申请核对、修改并亲自提交。</p><RouterLink :to="saved.targetPath">打开调宿申请 <ArrowUpRight :size="14" /></RouterLink></div>
      <template v-else>
        <div class="assistant-draft-intro"><h2>准备调宿草稿</h2><p>填写实际原因 → 核对当前住宿 → 确认保存草稿</p><small>意向区域只作参考，具体床位由宿管安排。</small></div>
        <details v-if="latest" class="assistant-draft-question"><summary>本次补充文字</summary><p>{{ latest }}</p></details>
        <p class="assistant-draft-reply" role="status">{{ busy ? '正在核对住宿和申请状态…' : preview && !checked ? '内容已修改，请重新核对。' : preview?.summary ?? '填写原因、意向区域和已准备的材料说明。' }}</p>
        <p v-if="preview?.currentPlace" class="assistant-draft-meta"><strong>当前住宿：</strong>{{ preview.currentPlace }}</p>
        <ul v-if="checked && preview?.issues.length" class="assistant-draft-issues"><li v-for="issue in preview.issues" :key="issue">{{ issue }}</li></ul>
        <form @submit.prevent="prepare(false)"><fieldset :disabled="busy || saving || uncertain"><label>调宿原因<textarea v-model="fields.reason" rows="2" maxlength="500" placeholder="说明真实的调整原因" /></label><label>意向区域<input v-model="fields.preferredArea" maxlength="200" placeholder="例如东校区低楼层，不代表床位已安排" /></label><label>材料说明<textarea v-model="fields.materialNote" rows="2" maxlength="500" placeholder="说明已准备的材料，附件在原调宿草稿页面上传" /></label></fieldset><button class="assistant-draft-check" type="submit" :disabled="busy || saving || uncertain">核对并暂存</button></form>
        <p v-if="preview?.eligibilityMessage" class="assistant-draft-meta">{{ preview.eligibilityMessage }}</p>
        <RouterLink v-if="preview?.source" class="assistant-draft-source" :to="preview.source.targetPath">演示办理指南 · V{{ preview.source.version }} <ArrowUpRight :size="12" /></RouterLink>
        <p v-if="uncertain" class="assistant-draft-issues">保存结果未确认，可使用相同资料重试，或到<RouterLink to="/housing/transfer">调宿申请</RouterLink>核对已有记录。</p>
      </template>
    </div>
    <div v-if="!saved" class="assistant-draft-bottom">
      <button v-if="mode === 'DEEPSEEK' && !uncertain" class="assistant-draft-compose-toggle" :disabled="busy || saving" @click="showComposer = !showComposer">{{ showComposer ? '收起文字补充' : '继续用文字调整' }}</button>
      <form v-if="mode === 'DEEPSEEK' && !uncertain && showComposer" class="assistant-draft-compose" @submit.prevent="prepare(true)"><label class="assistant-draft-consent"><input v-model="consent" type="checkbox" :disabled="busy || saving" />同意仅将本次补充文字发送至 DeepSeek。当前住宿、历史和已填表单留在本地。</label><div class="assistant-draft-input"><textarea ref="input" v-model="question" aria-label="补充调宿信息" rows="2" maxlength="1000" :disabled="busy || saving" @keydown="keydown" /><button type="submit" aria-label="发送调宿补充" :disabled="!canSend"><Send :size="16" /></button></div></form>
      <label v-if="checked && preview?.ready" class="assistant-draft-consent assistant-draft-confirm"><input v-model="confirmed" type="checkbox" :disabled="saving || busy" />我已核对当前住宿、调宿原因和材料说明，确认保存本人草稿。</label>
      <button class="assistant-draft-save" :disabled="!canSave" @click="save">{{ saving ? '正在保存…' : uncertain ? '重试保存相同草稿' : '确认并保存草稿' }}</button>
    </div>
    <button v-if="record && !saved" class="assistant-draft-recover" :disabled="busy || saving" @click="restore()">恢复最新进度</button>
    <p v-if="error" class="assistant-draft-error" role="alert">{{ error }}</p>
    <Teleport to="body"><dialog ref="dialog" class="assistant-draft-dialog" aria-labelledby="assistant-housing-dialog-title" @cancel.prevent="finishLeave(false)"><h2 id="assistant-housing-dialog-title">调宿预览有未暂存修改</h2><p>离开会丢失未暂存内容，已核对内容可从历史对话恢复。</p><div><button ref="cancelButton" @click="finishLeave(false)">继续填写</button><button @click="finishLeave(true)">放弃修改并离开</button></div></dialog></Teleport>
  </div>
</template>
<style scoped src="../styles/assistant-draft.css"></style>
