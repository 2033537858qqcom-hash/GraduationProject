<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Bell, Check, RefreshCw, ArrowUpRight, ChevronLeft, ChevronRight } from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import { formatDateTime } from '../lib/format';
import { useToast } from '../composables/useToast';
import { decrementUnreadNoticeCount, setUnreadNoticeCount } from '../stores/notices';
import BaseSelect from '../components/BaseSelect.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import { safeAffairDestination } from '../lib/navigation';
import type { Notice } from '../types';
import type { Paged } from '../foundation';
const route = useRoute(),
  router = useRouter();
const list = ref<Paged<Notice> & { unreadCount: number }>({
  items: [],
  total: 0,
  page: 1,
  pageSize: 20,
  unreadCount: 0,
});
const filter = ref(''),
  loading = ref(true),
  reading = ref<number | null>(null),
  error = ref('');
const readFilterOptions = [
  { value: '', label: '全部消息' },
  { value: 'false', label: '未读消息' },
  { value: 'true', label: '已读消息' },
];
let sequence = 0;
async function load() {
  const request = ++sequence;
  loading.value = true;
  error.value = '';
  filter.value = String(route.query.read ?? '');
  const params = new URLSearchParams({ page: String(Math.max(1, Number(route.query.page) || 1)), pageSize: '20' });
  if (filter.value) params.set('read', filter.value);
  try {
    const result = await api<typeof list.value>('/notices?' + params);
    if (request === sequence) {
      list.value = result;
      setUnreadNoticeCount(result.unreadCount);
    }
  } catch (cause) {
    if (request === sequence) error.value = errorMessage(cause);
  } finally {
    if (request === sequence) loading.value = false;
  }
}
const toast = useToast();
async function mark(notice: Notice, open = false) {
  reading.value = notice.id;
  error.value = '';
  try {
    if (!notice.read) {
      await api('/notices/' + notice.id + '/read', { method: 'POST' });
      decrementUnreadNoticeCount();
      if (!open) toast.success('已将该消息标记为已读');
    }
    const target = safeAffairDestination(notice.targetPath);
    if (open && target) await router.push(target);
    else await load();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    reading.value = null;
  }
}
async function search() {
  await router.replace({ query: { read: filter.value, page: '1' } });
}
async function page(number: number) {
  await router.replace({ query: { ...route.query, page: String(number) } });
}
onMounted(() => {
  void load();
});
watch(
  () => route.fullPath,
  () => {
    void load();
  },
);
</script>
<template>
  <div class="service-workflow notices-page">
    <ServicePageHeader context="个人消息" title="消息通知" description="阅读发给你的消息，查看对应申请的办理进度。">
      <template #actions>
        <button class="button compact" :disabled="loading || reading !== null" @click="load">
          <RefreshCw :size="15" />
          刷新消息
        </button>
      </template>
    </ServicePageHeader>

    <p v-if="error" class="alert" role="alert">{{ error }}</p>

    <p class="notice-summary">
      <Bell :size="18" />
      <strong>未读 {{ list.unreadCount }} 条</strong>
      <span>当前筛选共 {{ list.total }} 条</span>
    </p>

    <form class="foundation-filters leave-filters" @submit.prevent="search">
      <label>
        阅读状态
        <BaseSelect
          v-model="filter"
          :options="readFilterOptions"
          :disabled="reading !== null"
          compact
          @change="search"
        />
      </label>
      <button class="button compact primary" :disabled="loading || reading !== null">查询</button>
    </form>

    <section class="portal-panel" aria-label="个人消息列表">
      <SkeletonBlock v-if="loading" variant="list" :rows="4" label="正在读取个人消息…" />
      <p v-else-if="!list.items.length" class="empty-state">当前筛选条件下暂无个人消息。</p>
      <article
        v-for="notice in loading ? [] : list.items"
        :key="notice.id"
        class="notice-row"
        :class="{ 'is-unread': !notice.read }"
      >
        <div class="notice-heading">
          <h3>{{ notice.title }}</h3>
          <span class="status-label" :class="{ read: notice.read }">{{ notice.read ? '已读' : '未读' }}</span>
        </div>
        <p>{{ notice.body }}</p>
        <div class="notice-actions">
          <time>{{ formatDateTime(notice.createdAt) }}</time>
          <button
            v-if="safeAffairDestination(notice.targetPath)"
            class="text-button"
            :disabled="reading !== null"
            @click="mark(notice, true)"
          >
            查看对应申请
            <ArrowUpRight :size="14" />
          </button>
          <button v-if="!notice.read" class="text-button" :disabled="reading !== null" @click="mark(notice)">
            <Check :size="14" />
            标为已读
          </button>
        </div>
      </article>

      <div v-if="list.total > 0" class="pagination">
        <span class="pagination-info">
          第 {{ list.page }} / {{ Math.max(1, Math.ceil(list.total / list.pageSize)) }} 页 · 共 {{ list.total }} 条
        </span>
        <div class="pagination-actions">
          <button
            class="button compact"
            :disabled="loading || reading !== null || list.page <= 1"
            @click="page(list.page - 1)"
          >
            <ChevronLeft :size="14" />
            上一页
          </button>
          <button
            class="button compact"
            :disabled="loading || reading !== null || list.page * list.pageSize >= list.total"
            @click="page(list.page + 1)"
          >
            下一页
            <ChevronRight :size="14" />
          </button>
        </div>
      </div>
    </section>

    <p class="data-note">
      仅展示当前账号的个人消息。历史通知保留原始正文；关联校内业务且具备访问权限的消息支持直接跳转对应申请。
    </p>
  </div>
</template>

<style scoped>
.notices-page .notice-row.is-unread {
  background: var(--color-surface-soft);
}
</style>
