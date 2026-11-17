<script setup lang="ts">
import { MapPin, RefreshCw } from '@lucide/vue';
import { api } from '../lib/api';
import { useRequest } from '../composables/useRequest';
import { repairCapabilities } from '../repairSettings';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import type { RepairCategory, RepairScope } from '../repairSettings';
import '../styles/repair-workflow.css';
import '../styles/repair-settings.css';
const {
  data,
  loading,
  error,
  run: load,
} = useRequest(signal =>
  api<{ categories: RepairCategory[]; scopes: RepairScope[] }>('/repairs/responsibilities', { signal }),
);
</script>
<template>
  <div class="repair-settings">
    <ServicePageHeader
      context="后勤服务"
      title="我的负责范围"
      description="核对当前负责的校区、故障类别与具体地点。岗位及范围由管理员维护。"
    >
      <template #actions>
        <button class="button compact" :disabled="loading" @click="load">
          <RefreshCw :size="15" />
          刷新范围
        </button>
      </template>
    </ServicePageHeader>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在读取负责范围…</p>
    <p v-else-if="!data?.scopes.length" class="empty-state">还没有配置负责范围，请联系平台管理员。</p>
    <div v-else class="repair-card-grid">
      <article v-for="scope in data?.scopes" :key="scope.id" class="portal-panel repair-config-card">
        <div class="section-heading">
          <h2>
            <MapPin :size="17" />
            {{ scope.campusName }}
          </h2>
          <span class="repair-status" :class="{ inactive: !scope.effective }">{{ scope.effectivenessMessage }}</span>
        </div>
        <p class="repair-card-main">
          {{ data?.categories.find(c => c.code === scope.category)?.name }} · {{ repairCapabilities[scope.capability] }}
        </p>
        <p class="muted">{{ scope.locationName ?? '该校区全部开放的报修地点' }}</p>
      </article>
    </div>
  </div>
</template>
