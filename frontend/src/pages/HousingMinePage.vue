<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { BedDouble, RefreshCw, Wrench } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { housingWhen, type HousingMine } from '../housing'
import { currentUser } from '../stores/auth'
import type { DormRepairEligibility } from '../repairSettings'
const data = ref<HousingMine | null>(null)
const repair = ref<DormRepairEligibility | null>(null), repairError = ref('')
const loading = ref(true)
const error = ref('')
let sequence = 0
async function load() {
  const current = ++sequence
  loading.value = true
  error.value = ''
  repair.value = null; repairError.value = ''
  try {
    const result = await api<HousingMine>('/housing/mine')
    if (current === sequence) data.value = result
    if (currentUser.value?.permissions.includes('repair:apply')) {
      try { const eligibility = await api<DormRepairEligibility>('/repairs/my-dorm'); if (current === sequence) repair.value = eligibility }
      catch (cause) { if (current === sequence) repairError.value = errorMessage(cause) }
    }
  } catch (cause) { if (current === sequence) error.value = errorMessage(cause) }
  finally { if (current === sequence) loading.value = false }
}
onMounted(load)
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <div class="repair-settings housing-page">
    <div class="repair-page-heading"><div><span class="portal-context">学生服务中心</span><h1>我的住宿</h1><p>查看本人当前床位、楼栋负责人和入住历史。这里不显示其他住宿者。</p></div><button class="button compact" :disabled="loading" @click="load"><RefreshCw :size="15" />刷新住宿</button></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取住宿记录…</p>
    <template v-else-if="data">
      <article v-if="data.current" class="portal-panel repair-config-card"><div class="section-heading"><h2><BedDouble :size="18" />当前入住</h2></div><p class="repair-card-main">{{ data.current.campusName }} · {{ data.current.buildingName }} · {{ data.current.roomName }} · {{ data.current.floorLabel }} · {{ data.current.bedLabel }}</p><p class="muted">入住时间 {{ housingWhen(data.current.startedAt) }}</p><p>{{ data.current.managers.length ? '本楼宿管：' + data.current.managers.join('、') : '本楼暂无有效宿管，请联系管理员。' }}</p></article>
      <p v-else class="empty-state">目前没有在住记录。初始入住由宿舍管理员登记。</p>
      <p v-if="data.current"><RouterLink class="text-button" to="/housing/transfer">申请调宿</RouterLink></p>
      <article v-if="repair || repairError" class="portal-panel repair-config-card"><div class="section-heading"><h2><Wrench :size="18" />宿舍设施报修</h2></div><p>{{ repair?.message || repairError }}</p><p v-if="repair?.location" class="muted">{{ repair.location.campusName }} · {{ repair.location.name }}{{ repair.location.provenance === 'DEMO' ? ' · 演示地点' : '' }}</p><RouterLink v-if="repair?.location" class="button primary compact" :to="{ path: '/repairs/mine', query: { new: '1', location: String(repair.location.id) } }">为当前宿舍报修</RouterLink></article>
      <section class="repair-history"><h2>入住历史</h2><p v-if="!data.history.length" class="empty-state">还没有已结束的住宿记录。</p><ul v-else class="repair-history-list"><li v-for="item in data.history" :key="item.id"><strong>{{ item.campusName }} · {{ item.buildingName }} · {{ item.roomName }} · {{ item.bedLabel }}</strong><p>{{ housingWhen(item.startedAt) }} 至 {{ housingWhen(item.endedAt) }}</p><small>{{ item.endReason }}</small></li></ul></section>
    </template>
  </div>
</template>
