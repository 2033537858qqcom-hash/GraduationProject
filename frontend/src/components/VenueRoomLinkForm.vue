<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import BaseSelect from './BaseSelect.vue'
import { api, errorMessage } from '../lib/api'
import { classroomOptions } from '../reservations'
import type { VenueCreationCatalog, VenueManagementItem } from '../reservations'
const props = defineProps<{ venue: VenueManagementItem; catalog: VenueCreationCatalog }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const room = ref(props.venue.classroomResourceId ?? 0), reason = ref(''), busy = ref(false), error = ref('')
const options = computed(() => classroomOptions(props.catalog.classrooms, props.venue.campusId, props.venue.classroomResourceId ?? 0))
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
function close() { if (!busy.value) emit('close') }
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    await api(`/reservations/venues/${props.venue.id}/classroom`, { method: 'PUT', body: JSON.stringify({ classroomResourceId: room.value || null, version: props.venue.version, reason: reason.value }) })
    emit('saved')
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="room-link-dialog" aria-labelledby="room-link-title" @cancel.prevent="close">
    <div class="section-heading"><h2 id="room-link-title">关联教学教室</h2><button class="text-button" :disabled="busy" @click="close">关闭</button></div>
    <p><strong>{{ venue.name }}</strong> · {{ venue.campusName }}</p>
    <p class="muted">请核对是否为同一实际房间。关联后课程、已发布考试和预约共享占用规则；每间教室只能关联一个预约资源。有未结束预约时须先完成或取消，再变更关联。</p>
    <form class="editor-form" @submit.prevent="save">
      <label>教学教室<BaseSelect v-model="room" :options="options" :disabled="busy" /></label>
      <label>变更原因<textarea v-model="reason" required maxlength="200" rows="3" :disabled="busy" /></label>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在保存…' : '保存关联' }}</button><button class="button compact" type="button" :disabled="busy" @click="close">取消</button></div>
    </form>
  </dialog>
</template>
<style scoped>
.room-link-dialog { width: min(620px, calc(100vw - 32px)); max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid #dfe5ec; border-radius: 12px; padding: 28px; color: var(--ink); }
.room-link-dialog::backdrop { background: rgb(20 35 55 / 40%); }
</style>
