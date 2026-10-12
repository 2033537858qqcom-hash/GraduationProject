<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ChartColumn, RefreshCw } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { repairCategories, repairInclusiveEnd, repairStatisticsRange, repairStatuses } from '../repairs'
import type { RepairStatistics } from '../repairs'
const result = ref<RepairStatistics | null>(null), loading = ref(true), error = ref(''), from = ref(''), through = ref('')
let sequence = 0
async function load(initial = false) {
  loading.value = true; error.value = ''; const request = ++sequence
  try {
    const range = initial ? null : repairStatisticsRange(from.value, through.value)
    const data = await api<RepairStatistics>('/repairs/dispatch/statistics' + (range ? '?' + new URLSearchParams(range) : ''))
    if (request !== sequence) return
    result.value = data; from.value = data.from; through.value = repairInclusiveEnd(data.to)
  } catch (cause) { if (request === sequence) error.value = errorMessage(cause) }
  finally { if (request === sequence) loading.value = false }
}
function bar(count: number) { return result.value?.total ? Math.min(100, Math.max(0, count / result.value.total * 100)) + '%' : '0%' }
onMounted(() => { void load(true) })
onBeforeUnmount(() => { sequence++ })
</script>
<template>
  <div class="repair-statistics">
    <header class="repair-page-heading"><div><span class="eyebrow">后勤服务概况</span><h1>校园报修统计</h1><p>仅汇总你当前负责范围内，首次提交时间落在所选区间的工单。</p></div><RouterLink class="button compact" to="/logistics/repairs/dispatch">分诊与派单</RouterLink></header>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <form class="repair-statistics-filter" @submit.prevent="load()"><label>首次提交开始日期<input v-model="from" type="date" required :disabled="loading" /></label><label>结束日期（含当天）<input v-model="through" type="date" required :disabled="loading" /></label><button class="button primary compact" :disabled="loading"><RefreshCw :size="15" />{{ loading ? '正在汇总…' : '查询统计' }}</button></form>
    <p v-if="loading" class="loading-state" role="status">正在读取当前范围的统计…</p>
    <template v-else-if="result">
      <div class="repair-statistics-summary"><article><span>已提交工单</span><strong>{{ result.total }}</strong><small>补充重提不重复计数</small></article><article><span>本人已验收完成</span><strong>{{ result.completed }}</strong><small>以报修人确认结果为准</small></article><article><span>后勤已同意取消</span><strong>{{ result.cancelled }}</strong><small>已关闭的取消工单</small></article><article><span>取消待处理</span><strong>{{ result.cancellationPending }}</strong><small>维修和验收暂时暂停</small></article><article class="repair-average"><span>平均处理时长</span><strong>{{ result.averageHours === null ? '暂无样本' : result.averageHours + ' 小时' }}</strong><small>{{ result.averageSampleCount }} 条完成样本 · 首次提交至本人确认</small></article></div>
      <p v-if="!result.total" class="empty-state portal-panel">当前负责范围与日期区间内没有已提交报修。</p>
      <div v-else class="repair-statistics-charts"><section class="portal-panel"><h2><ChartColumn :size="18" />故障类别</h2><article v-for="bucket in result.categories" :key="bucket.code" class="repair-statistic-row"><div><span>{{ repairCategories[bucket.code] }}</span><strong>{{ bucket.count }} 条</strong></div><div class="repair-bar-track" aria-hidden="true"><span :style="{ width: bar(bucket.count) }" /></div></article></section><section class="portal-panel"><h2>当前工单状态</h2><article v-for="bucket in result.statuses.filter(b => b.code !== 'DRAFT')" :key="bucket.code" class="repair-statistic-state"><span>{{ repairStatuses[bucket.code] }}</span><strong>{{ bucket.count }} 条</strong></article></section></div>
      <p class="data-note">{{ result.from }} 至 {{ through }} · 查询时间 {{ result.queriedAt.slice(0, 16).replace('T', ' ') }}。草稿不计入；平均时长只计算区间内已确认完成且时间有效的工单。负责范围调整后统计随当前权限变化。</p>
    </template>
  </div>
</template>
