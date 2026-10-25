<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, errorMessage } from '../lib/api'
import BaseSelect from './BaseSelect.vue'
import type { VenueCreationCatalog, VenueManagerItem, VenueSummary } from '../reservations'
const props = defineProps<{ catalog: VenueCreationCatalog; candidates: VenueManagerItem[] }>()
const emit = defineEmits<{ close: []; saved: [venue: VenueSummary] }>()
const dialog = ref<HTMLDialogElement | null>(null), busy = ref(false), error = ref('')
const form = reactive({ campusId: props.catalog.campuses[0]?.id ?? 0, code: '', name: '', category: 'VENUE', placeId: '', buildingName: '', locationDetail: '', capacity: 30, availableEquipment: '', rules: '', enabled: false, managerUserIds: [] as number[] })
const campusCode = computed(() => props.catalog.campuses.find(c => c.id === form.campusId)?.code)
const placeOptions = computed(() => [{ value: '', label: '暂不绑定地图地点' }, ...props.catalog.places.filter(p => p.campusCode === campusCode.value).map(p => ({ value: p.id, label: p.name }))])
watch(() => form.campusId, () => { form.placeId = ''; form.buildingName = '' })
watch(() => form.placeId, id => { const place = props.catalog.places.find(p => p.id === id && p.campusCode === campusCode.value); if (place) form.buildingName = place.name })
function close() { if (!busy.value) emit('close') }
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
async function save() {
  if (busy.value) return
  error.value = ''
  if (form.enabled && !form.managerUserIds.length) { error.value = '开放资源前请至少配置一名有效办理人员'; return }
  busy.value = true
  try { emit('saved', await api<VenueSummary>('/reservations/venues', { method: 'POST', body: JSON.stringify(form) })) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <dialog ref="dialog" class="venue-create-dialog" aria-labelledby="venue-create-heading" @cancel.prevent="close">
    <div class="section-heading"><div><span class="eyebrow">资源建档</span><h2 id="venue-create-heading">新增场地或设备资源</h2></div><button class="text-button" :disabled="busy" @click="close">关闭</button></div>
    <p class="muted">地点名称来自现有校园场景目录；房间、容量与设施须由管理人员核实录入。可先保存为待开放资源。</p>
    <form class="editor-form" @submit.prevent="save"><fieldset :disabled="busy" class="venue-create-fields">
      <div class="form-grid"><label>所属校区<BaseSelect v-model="form.campusId" :options="catalog.campuses.map(c => ({ value: c.id, label: c.name }))" /></label><label>资源类别<BaseSelect v-model="form.category" :options="[{ value: 'VENUE', label: '场地空间' }, { value: 'EQUIPMENT', label: '整套设备' }]" /></label></div>
      <div class="form-grid"><label>资源编号<input v-model="form.code" required pattern="[A-Za-z0-9][A-Za-z0-9_-]{1,63}" maxlength="64" placeholder="校区内唯一，例如 WEST-LIB-101" /></label><label>资源名称<input v-model="form.name" required maxlength="120" /></label></div>
      <label>关联地图地点<BaseSelect v-model="form.placeId" :options="placeOptions" /></label>
      <div class="form-grid"><label>所在建筑或区域<input v-model="form.buildingName" required maxlength="100" /></label><label>具体位置<input v-model="form.locationDetail" required maxlength="200" placeholder="楼层、房间或交接地点" /></label></div>
      <label>{{ form.category === 'VENUE' ? '容纳人数' : '资源容量标记' }}<input v-model.number="form.capacity" type="number" min="1" required /><small v-if="form.category === 'EQUIPMENT'" class="muted">设备作为整套资源预约，本项不提供逐件库存领还。</small></label>
      <label>配套设施说明<textarea v-model="form.availableEquipment" maxlength="500" rows="2" /></label><label>使用守则<textarea v-model="form.rules" maxlength="1000" rows="2" /></label>
      <fieldset class="venue-owner-choices"><legend>办理人员</legend><label v-for="person in candidates" :key="person.userId" class="venue-owner-choice"><input v-model="form.managerUserIds" type="checkbox" :value="person.userId" />{{ person.fullName }} · {{ person.departmentName }}</label><p v-if="!candidates.length" class="muted">尚无有效办理人员，可先保存为待开放资源。</p></fieldset>
      <label class="venue-owner-choice"><input v-model="form.enabled" type="checkbox" />创建后立即开放预约</label>
    </fieldset><p v-if="error" class="alert" role="alert">{{ error }}</p><div class="form-actions"><button class="button primary compact" :disabled="busy">{{ busy ? '正在创建…' : form.enabled ? '创建并开放预约' : '保存待开放资源' }}</button><button type="button" class="button compact" :disabled="busy" @click="close">取消</button></div></form>
  </dialog>
</template>
<style scoped>
.venue-create-dialog { width: min(760px, calc(100vw - 32px)); max-height: calc(100dvh - 40px); overflow: auto; border: 1px solid #dfe5ec; border-radius: 12px; padding: clamp(20px, 3vw, 30px); color: var(--ink, #203650); }
.venue-create-dialog::backdrop { background: rgb(20 35 55 / 40%); }
.venue-create-fields { border: 0; padding: 0; margin: 0; display: grid; gap: 14px; min-width: 0; }
.venue-owner-choices { border: 1px solid #dfe5ec; border-radius: 8px; padding: 14px; }
.venue-owner-choice { display: flex; align-items: center; gap: 8px; margin: 8px 0; }
.venue-owner-choice input { width: auto; }
</style>
