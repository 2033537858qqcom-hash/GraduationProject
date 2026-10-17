<script setup lang="ts">
import { ArrowUpRight, ClipboardCheck, Send } from '@lucide/vue'
import { certificatePurposeFingerprint } from '../lib/assistantCertificate'
import type { CertificatePreviewFields, AssistantCertificatePreview } from '../lib/assistantCertificate'
import type { AssistantTaskView } from '../lib/assistantSessions'
import { useAssistantDraft } from '../lib/useAssistantDraft'
import { certificateStatuses } from '../certificates'

const props = defineProps<{ active: boolean; mode: 'LOCAL' | 'DEEPSEEK'; modelReady: boolean; sessionId: number | null; resumeTaskId: number | null }>()
const emit = defineEmits<{ saving: [value: boolean]; working: [value: boolean]; updated: [value: AssistantTaskView] }>()
const { fields, preview, saved, record, question, latest, consent, confirmed, busy, saving, error, uncertain, showComposer, checked, canSend, canSave, prepare, restore, save, finishLeave, allowLeave, keydown } = useAssistantDraft<CertificatePreviewFields, AssistantCertificatePreview>({
  props, kind: 'CERTIFICATE', empty: () => ({ purpose: '' }), fingerprint: certificatePurposeFingerprint,
  emitSaving: value => emit('saving', value), emitWorking: value => emit('working', value), emitUpdated: value => emit('updated', value),
})
defineExpose({ allowLeave })
</script>
<template>
  <div class="assistant-draft">
    <div ref="scrollArea" class="assistant-draft-scroll">
      <div v-if="saved" class="assistant-draft-saved" role="status">
        <ClipboardCheck :size="24" /><h2>证明申请已建档</h2><p>申请 #{{ saved.id }} · {{ certificateStatuses[saved.status] ?? saved.status }} · V{{ saved.version }}。当前状态已重新核对。</p>
        <RouterLink :to="`/portal/certificates?id=${saved.id}&tab=details`">打开原申请继续办理 <ArrowUpRight :size="14" /></RouterLink>
        <p class="assistant-draft-meta">由所属学院审核。办理完成后可在申请详情下载演示证明。</p>
        <button type="button" @click="restore(true)">再准备一份</button>
      </div>
      <template v-else>
        <div class="assistant-draft-intro"><h2>准备在校证明</h2><p>说明用途 → 核对本人资料 → 保存草稿</p><small>核对后的进度保存到本人对话，刷新可恢复。</small></div>
        <details v-if="latest" class="assistant-draft-question"><summary>本次用途说明</summary><p>{{ latest }}</p></details>
        <p class="assistant-draft-reply" role="status">{{ busy ? '正在整理与核对…' : preview && !checked ? '用途已修改，请重新核对。' : preview?.summary ?? '请说明这份证明的用途，也可以直接填写。' }}</p>
        <ul v-if="checked && preview?.issues.length" class="assistant-draft-issues"><li v-for="issue in preview.issues" :key="issue">{{ issue }}</li></ul>
        <form @submit.prevent="prepare(false)">
          <fieldset :disabled="busy || saving || uncertain"><label><div class="label-heading-row"><span>证明用途</span><small class="muted">必填 · 最多 300 字</small></div><textarea v-model="fields.purpose" rows="3" maxlength="300" placeholder="例如：供用人单位或签证机构核验本人在校身份" /></label></fieldset>
          <button type="submit" class="assistant-draft-check" :disabled="busy || saving || uncertain">核对并暂存</button><span v-if="!checked && preview" class="assistant-draft-meta">已修改，需重新核对</span>
        </form>
        <section v-if="preview?.identity" class="certificate-identity" aria-label="本人登记资料">
          <h3>本人登记资料</h3><dl><dt>姓名 / 学号</dt><dd>{{ preview.identity.studentName }} / {{ preview.identity.identityNumber }}</dd><dt>学院 / 专业</dt><dd>{{ preview.identity.departmentName }} / {{ preview.identity.majorName }}</dd><dt>班级 / 校区</dt><dd>{{ preview.identity.className }} / {{ preview.identity.campusName }}</dd></dl>
          <p>资料由系统读取；如有错误，请先联系学院核对。</p>
        </section>
        <p v-if="preview?.eligibilityMessage" class="assistant-draft-meta">{{ preview.eligibilityMessage }}</p>
        <p class="assistant-draft-meta">完成后提供电子 PDF；当前为毕业设计演示流程。</p>
        <RouterLink v-if="preview?.source" class="assistant-draft-source" :to="preview.source.targetPath">演示办理指南 · V{{ preview.source.version }} <ArrowUpRight :size="12" /></RouterLink>
        <p v-if="uncertain" class="assistant-draft-issues">保存结果未确认。可用相同资料重试，或先到<RouterLink to="/portal/certificates">我的证明</RouterLink>核对，避免重复建档。</p>
      </template>
    </div>
    <div v-if="!saved" class="assistant-draft-bottom">
      <button v-if="mode === 'DEEPSEEK' && !uncertain" class="assistant-draft-compose-toggle" :disabled="busy || saving" @click="showComposer = !showComposer">{{ showComposer ? '收起用途说明' : '继续用文字调整' }}</button>
      <form v-if="mode === 'DEEPSEEK' && !uncertain && showComposer" class="assistant-draft-compose" @submit.prevent="prepare(true)">
        <label class="assistant-draft-consent"><input v-model="consent" type="checkbox" :disabled="busy || saving" />同意仅将本次文字发送至 DeepSeek。系统登记资料、表单和历史补充留在本地。</label>
        <div class="assistant-draft-input"><textarea ref="input" v-model="question" aria-label="说明证明用途" rows="2" maxlength="1000" placeholder="例如：实习单位要求核验在校身份" :disabled="busy || saving" @keydown="keydown" /><button type="submit" aria-label="发送证明用途" :disabled="!canSend"><Send :size="16" /></button></div>
      </form>
      <label v-if="checked && preview?.ready" class="assistant-draft-consent assistant-draft-confirm"><input v-model="confirmed" type="checkbox" :disabled="saving || busy" />我已核对本人姓名、学号、学院、专业、班级、校区及用途，确认保存为本人草稿。</label>
      <button class="assistant-draft-save" :disabled="!canSave" @click="save">{{ saving ? '正在保存…' : uncertain ? '重试保存相同草稿' : '确认并保存草稿' }}</button>
    </div>
    <button v-if="record && !saved" class="assistant-draft-recover" :disabled="busy || saving" @click="restore()">恢复最新进度</button>
    <p v-if="error" class="assistant-draft-error" role="alert">{{ error }}</p>
    <Teleport to="body"><dialog ref="dialog" class="assistant-draft-dialog" aria-labelledby="assistant-certificate-dialog-title" @cancel.prevent="finishLeave(false)"><h2 id="assistant-certificate-dialog-title">证明预览有未暂存修改</h2><p>未暂存的修改会丢失；已核对的内容可从历史对话恢复。</p><div><button ref="cancelButton" @click="finishLeave(false)">继续填写</button><button @click="finishLeave(true)">放弃修改并离开</button></div></dialog></Teleport>
  </div>
</template>
<style scoped src="../styles/assistant-draft.css"></style>
<style scoped>
.certificate-identity{margin:14px 0 8px;padding:10px;border:1px solid #e2e9f0;border-radius:7px;background:#fafbfd}.certificate-identity h3{font-size:11px;color:#344d69;margin:0 0 8px}.certificate-identity dl{display:grid;gap:3px;margin:0}.certificate-identity dt{font-size:10px;color:#8392a3;margin-top:5px}.certificate-identity dd{margin:0;font-size:11px;color:#405b76;overflow-wrap:anywhere;line-height:1.6}.certificate-identity p{font-size:10px;color:#8392a3;line-height:1.7;margin:8px 0 0}
</style>
