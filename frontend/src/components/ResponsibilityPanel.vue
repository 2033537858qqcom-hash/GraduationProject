<script setup lang="ts">
import type { ResponsibilityImpact } from '../foundation'
import { currentUser } from '../stores/auth'
defineProps<{ impact: ResponsibilityImpact; compact?: boolean }>()
</script>
<template>
  <div class="responsibility-panel">
    <p class="muted">负责范围须移交或清空；共享学院队列仅在失去最后一名有效审核人员时阻止调整。在办数量可能因负责范围交叉而重复，不作为事项总数。</p>
    <div class="table-scroll"><table class="campus-table"><thead><tr><th>业务</th><th>负责范围</th><th>未完成事项</th><th v-if="!compact">交接入口</th></tr></thead><tbody><tr v-for="g in impact.groups" :key="g.code"><td>{{ g.name }}</td><td>{{ g.scopeCount }}</td><td>{{ g.pendingCount }}</td><td v-if="!compact"><RouterLink v-if="currentUser?.permissions.includes(g.permission)" :to="g.path" class="text-button">前往配置</RouterLink><small v-else class="muted">由对应业务配置人员办理</small></td></tr></tbody></table></div>
    <p class="muted">教师授课关系及学生在途申请仍需在各业务中核对；本检查覆盖已接入的服务岗位负责关系。</p>
  </div>
</template>
