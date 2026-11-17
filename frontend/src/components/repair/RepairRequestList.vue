<script setup lang="ts">
import { ClipboardCheck, MapPin, User, Wrench } from '@lucide/vue';
import SkeletonBlock from '../SkeletonBlock.vue';
import type { Paged } from '../../foundation';
import { repairCategories, repairStatuses, type RepairItem } from '../../repairs';
defineProps<{
  list: Paged<RepairItem>;
  selectedId?: number;
  mode: string;
  busy: boolean;
  loading: boolean;
  stepTime: (value: string | null | undefined) => string | null;
}>();
const emit = defineEmits<{ choose: [item: RepairItem]; page: [page: number]; resetFilter: [] }>();
</script>
<template>
  <section class="portal-panel repair-request-list" aria-label="报修记录列表" :aria-busy="loading">
    <div class="repair-list-heading">
      <h2>{{ mode === 'work' ? '任务列表' : '报修记录' }}</h2>
      <span>{{ loading ? '更新中…' : `共 ${list.total} 条` }}</span>
    </div>
    <SkeletonBlock v-if="loading && !list.items.length" variant="list" :rows="4" label="正在读取报修记录…" />
    <div v-else-if="!loading && !list.items.length" class="repair-list-empty">
      <ClipboardCheck :size="26" />
      <strong>暂无相关工单</strong>
      <p>{{ mode === 'mine' ? '试试其他筛选，或新建一份报修。' : '当前筛选下没有你负责的工单。' }}</p>
      <button class="text-button" :disabled="busy" @click="emit('resetFilter')">查看全部工单</button>
    </div>
    <div v-else class="repair-cards-scroll" :class="{ 'is-loading': loading }">
      <button
        v-for="item in list.items"
        :key="item.id"
        class="repair-request-card"
        :class="{ selected: selectedId === item.id }"
        :aria-current="selectedId === item.id ? 'true' : undefined"
        :disabled="busy || loading"
        @click="emit('choose', item)"
      >
        <span class="repair-card-top">
          <strong>{{ item.title || '待补充故障标题' }}</strong>
          <span class="repair-status" :class="`status-${item.status.toLowerCase()}`">
            {{ repairStatuses[item.status] }}
          </span>
        </span>
        <span class="repair-card-location">
          <MapPin :size="13" />
          <span>{{ item.campusName }} · {{ item.locationName || '待选择地点' }}</span>
        </span>
        <span v-if="item.cancellationPending" class="repair-card-attention">取消申请待处理 · 办理已暂停</span>
        <span v-else-if="item.confirmationOverdue" class="repair-card-attention">等待验收，请及时核对维修结果</span>
        <span class="repair-card-person">
          <User v-if="mode !== 'mine'" :size="12" />
          <span v-if="mode !== 'mine'">{{ item.reporterName }}{{ mode === 'dispatch' ? ' · ' : '' }}</span>
          <Wrench v-if="mode === 'mine'" :size="12" />
          <span v-if="mode !== 'work'">{{ item.technicianName || '等待派工' }}</span>
        </span>
        <span class="repair-card-footer">
          <span>#{{ item.id }} · {{ repairCategories[item.category] || '待分类' }}</span>
          <time :datetime="item.updatedAt">{{ stepTime(item.updatedAt) }} 更新</time>
        </span>
      </button>
    </div>
    <div v-if="list.total > list.pageSize" class="pagination">
      <span>第 {{ list.page }} 页</span>
      <button class="button compact" :disabled="busy || loading || list.page <= 1" @click="emit('page', list.page - 1)">
        上一页
      </button>
      <button
        class="button compact"
        :disabled="busy || loading || list.page * list.pageSize >= list.total"
        @click="emit('page', list.page + 1)"
      >
        下一页
      </button>
    </div>
  </section>
</template>
