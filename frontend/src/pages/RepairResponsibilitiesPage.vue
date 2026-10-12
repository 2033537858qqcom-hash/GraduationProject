<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { MapPin, RefreshCw } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { repairCapabilities } from '../repairSettings'
import type { RepairCategory, RepairScope } from '../repairSettings'
const data = ref<{ categories: RepairCategory[]; scopes: RepairScope[] } | null>(null)
const loading = ref(true), error = ref('')
let sequence = 0
async function load() {
  const current = ++sequence
  loading.value = true; error.value = ''
  try { const result = await api<{ categories: RepairCategory[]; scopes: RepairScope[] }>('/repairs/responsibilities'); if (current === sequence) data.value = result }
  catch (cause) { if (current === sequence) error.value = errorMessage(cause) }
  finally { if (current === sequence) loading.value = false }
}
onMounted(load)
</script>
<template>
  <div class="repair-settings">
    <div class="page-heading-row"><div><span class="portal-context">后勤服务</span><h1>我的负责范围</h1><p class="muted">核对当前负责的校区、故障类别与具体地点。岗位及范围由管理员维护。</p></div><button class="button compact" :disabled="loading" @click="load"><RefreshCw :size="15" />刷新范围</button></div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取负责范围…</p>
    <p v-else-if="!data?.scopes.length" class="empty-state">还没有配置负责范围，请联系平台管理员。</p>
    <div v-else class="repair-card-grid"><article v-for="scope in data?.scopes" :key="scope.id" class="portal-panel repair-config-card"><div class="section-heading"><h2><MapPin :size="17" />{{ scope.campusName }}</h2><span class="repair-status" :class="{ inactive: !scope.effective }">{{ scope.effectivenessMessage }}</span></div><p class="repair-card-main">{{ data?.categories.find(c => c.code === scope.category)?.name }} · {{ repairCapabilities[scope.capability] }}</p><p class="muted">{{ scope.locationName ?? '该校区全部开放的报修地点' }}</p></article></div>
  </div>
</template>
