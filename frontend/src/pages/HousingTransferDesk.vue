<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api, errorMessage } from '../lib/api';
import BaseSelect from '../components/BaseSelect.vue';
import '../styles/repair-workflow.css';
import {
  bedCanAssign,
  housingRequestKey,
  housingWhen,
  transferStatus,
  type DormBed,
  type DormScope,
  type HousingPage,
  type HousingResponsibilities,
  type HousingTransfer,
} from '../housing';
const emit = defineEmits<{ changed: [] }>();
const route = useRoute();
const waiting = ref<HousingTransfer[]>([]);
const reserved = ref<HousingTransfer[]>([]);
const selected = ref<HousingTransfer | null>(null);
const items = computed(() =>
  [...(selected.value ? [selected.value] : []), ...waiting.value, ...reserved.value].filter(
    (item, index, rows) => rows.findIndex(row => row.id === item.id) === index,
  ),
);
const scopes = ref<DormScope[]>([]);
const beds = ref<DormBed[]>([]);
const buildingId = ref('');
const bedId = ref('');
const comments = ref<Record<number, string>>({});
const extendUntil = ref<Record<number, string>>({});
const error = ref('');
const success = ref('');
const busy = ref(false);
const loading = ref(true);
const scopeOptions = computed(() =>
  scopes.value.map(scope => ({ value: String(scope.buildingId), label: scope.buildingName })),
);
const bedOptions = computed(() => {
  if (!beds.value.length) return [{ value: '', label: '当前楼栋没有空闲床位' }];
  return beds.value.map(bed => ({ value: String(bed.id), label: `${bed.roomName} · ${bed.label}` }));
});
let key = housingRequestKey(),
  sequence = 0;
async function load() {
  const stamp = ++sequence;
  loading.value = true;
  selected.value = null;
  waiting.value = [];
  reserved.value = [];
  beds.value = [];
  try {
    const [scope, pending, active] = await Promise.all([
      api<HousingResponsibilities>('/housing/responsibilities'),
      api<HousingTransfer[]>('/housing/transfers/assign?queue=waiting'),
      api<HousingTransfer[]>('/housing/transfers/assign?queue=reserved'),
    ]);
    const effective = scope.scopes.filter(item => item.effective);
    const id = route.query.id;
    let detail: HousingTransfer | null = null;
    if (id !== undefined) {
      if (typeof id !== 'string' || !/^[1-9]\d*$/.test(id) || route.query.tab !== 'details')
        throw new Error('申请链接无效，请从消息重新打开。');
      detail = await api<HousingTransfer>('/housing/transfers/assign/' + id);
    }
    const building = effective.some(item => String(item.buildingId) === buildingId.value)
      ? buildingId.value
      : String(effective[0]?.buildingId ?? '');
    const page = building
      ? await api<HousingPage<DormBed>>('/housing/beds?buildingId=' + building + '&pageSize=100')
      : null;
    if (stamp !== sequence) return;
    waiting.value = pending;
    reserved.value = active;
    selected.value = detail;
    for (const item of [...pending, ...active, ...(detail ? [detail] : [])]) {
      if (item.status === 'AWAITING_CHECKIN' && !extendUntil.value[item.id]) {
        const base = item.reserveUntil ? new Date(item.reserveUntil) : new Date();
        const next = new Date(Math.max(base.getTime(), Date.now()) + 24 * 60 * 60 * 1000);
        const pad = (n: number) => String(n).padStart(2, '0');
        extendUntil.value[item.id] =
          `${next.getFullYear()}-${pad(next.getMonth() + 1)}-${pad(next.getDate())}T${pad(next.getHours())}:${pad(next.getMinutes())}`;
      }
    }
    scopes.value = effective;
    buildingId.value = building;
    beds.value = page?.items.filter(bedCanAssign) ?? [];
    if (!beds.value.some(bed => bed.id === Number(bedId.value))) bedId.value = String(beds.value[0]?.id ?? '');
    if (id) {
      await nextTick();
      if (stamp === sequence) document.getElementById('assigned-transfer-' + id)?.scrollIntoView({ block: 'start' });
    }
  } catch (cause) {
    if (stamp === sequence) error.value = errorMessage(cause);
  } finally {
    if (stamp === sequence) loading.value = false;
  }
}
async function refresh() {
  error.value = '';
  await load();
}
async function run(path: string, body: object, itemId?: number) {
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api(path, { method: 'POST', body: JSON.stringify(body) });
    key = housingRequestKey();
    if (itemId !== undefined) comments.value[itemId] = '';
    success.value = '调宿处理已记录。';
    emit('changed');
    await load();
  } catch (cause) {
    error.value = errorMessage(cause);
    key = housingRequestKey();
  } finally {
    busy.value = false;
  }
}
function reserve(item: HousingTransfer) {
  if (!bedId.value) {
    error.value = '请选择空闲床位';
    return;
  }
  return run(
    '/housing/transfers/assign/' + item.id + '/reserve',
    {
      requestKey: key,
      version: item.version,
      bedId: Number(bedId.value),
      reason: (comments.value[item.id] ?? '').trim() || '安排空闲床位',
    },
    item.id,
  );
}
function post(item: HousingTransfer, action: string) {
  const note = (comments.value[item.id] ?? '').trim();
  if (!note) {
    error.value = '请填写说明';
    return;
  }
  return run(
    '/housing/transfers/assign/' + item.id + '/' + action,
    {
      requestKey: key,
      version: item.version,
      comment: note,
    },
    item.id,
  );
}
function extendDeadline(item: HousingTransfer) {
  const note = (comments.value[item.id] ?? '').trim();
  const target = (extendUntil.value[item.id] ?? '').trim();
  if (!note) {
    error.value = '请填写延期说明';
    return;
  }
  if (!target) {
    error.value = '请选择新的最晚入住时间';
    return;
  }
  const reserveUntil = target.length === 16 ? `${target}:00` : target;
  return run(
    '/housing/transfers/assign/' + item.id + '/extend',
    {
      requestKey: key,
      version: item.version,
      reason: note,
      reserveUntil,
    },
    item.id,
  );
}
onMounted(refresh);
watch(() => route.fullPath, refresh);
onBeforeUnmount(() => {
  sequence++;
});
</script>
<template>
  <section class="repair-history">
    <div class="section-heading">
      <h2>调宿安排</h2>
      <button class="text-button" :disabled="busy || loading" @click="refresh">刷新调宿</button>
    </div>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading" role="status">正在读取调宿安排…</p>
    <template v-else>
      <label v-if="scopes.length">
        用于预留的楼栋
        <BaseSelect v-model="buildingId" :options="scopeOptions" :disabled="busy" @change="refresh" />
      </label>
      <p class="muted">
        待安排 {{ waiting.length }} 份，负责楼栋待入住 {{ reserved.length }} 份。预留后 72
        小时内确认入住，到期释放预留并保留原床位。
      </p>
      <p v-if="!items.length && !error" class="empty-state">当前没有需要处理的调宿申请。</p>
      <article
        v-for="item in items"
        :id="'assigned-transfer-' + item.id"
        :key="item.id"
        class="portal-panel repair-config-card"
        :aria-label="'调宿申请 #' + item.id"
      >
        <p v-if="item.id === selected?.id" class="data-note">当前选择的调宿申请 #{{ item.id }}</p>
        <div class="section-heading">
          <h2>{{ item.studentName }} · {{ item.studentUsername }}</h2>
          <span class="repair-status">{{ transferStatus[item.status] }}</span>
        </div>
        <p>原住宿 {{ item.sourcePlace }}</p>
        <p class="muted">意向 {{ item.preferredArea }} · {{ item.reason }}</p>
        <p v-if="item.targetPlace">安排床位 {{ item.targetPlace }}</p>
        <p v-if="item.reserveUntil && item.status === 'AWAITING_CHECKIN'" class="muted">
          请于 {{ housingWhen(item.reserveUntil) }} 前入住{{ item.cancelRequested ? ' · 学生请求取消' : '' }}
        </p>
        <template v-if="item.allowedActions.includes('reserve')">
          <label>
            空闲床位
            <BaseSelect v-model="bedId" :options="bedOptions" :disabled="busy" />
          </label>
          <button class="button compact" type="button" :disabled="busy || !bedId" @click="reserve(item)">
            预留 72 小时
          </button>
        </template>
        <div v-if="item.status === 'AWAITING_CHECKIN'" class="repair-card-actions">
          <button
            v-if="item.allowedActions.includes('check-in')"
            class="text-button"
            type="button"
            :disabled="busy || item.cancelRequested"
            @click="post(item, 'check-in')"
          >
            确认入住
          </button>
          <button
            v-if="item.allowedActions.includes('release')"
            class="text-button"
            type="button"
            :disabled="busy"
            @click="post(item, 'release')"
          >
            退回待安排
          </button>
          <button
            v-if="item.allowedActions.includes('accept-cancel')"
            class="text-button"
            type="button"
            :disabled="busy"
            @click="post(item, 'cancel')"
          >
            同意取消
          </button>
          <button
            v-if="item.allowedActions.includes('keep')"
            class="text-button"
            type="button"
            :disabled="busy"
            @click="post(item, 'keep')"
          >
            不同意取消
          </button>
          <div v-if="item.allowedActions.includes('extend')" class="extend-deadline-inline">
            <input
              v-model="extendUntil[item.id]"
              type="datetime-local"
              :disabled="busy"
              aria-label="延长入住截止时间"
            />
            <button class="text-button" type="button" :disabled="busy" @click="extendDeadline(item)">延长预留</button>
          </div>
        </div>
        <div v-if="item.allowedActions.length" style="margin-top: 12px">
          <label>
            <div class="label-heading-row">
              <span>处理说明 / 分配意见</span>
              <small class="muted">
                {{ item.allowedActions.includes('reserve') ? '预留选填 · 最多 200 字' : '必填 · 最多 200 字' }}
              </small>
            </div>
            <textarea
              v-model="comments[item.id]"
              maxlength="200"
              rows="2"
              :disabled="busy"
              placeholder="在此填写分配说明、延期原因或处理意见…"
            ></textarea>
          </label>
        </div>
      </article>
    </template>
  </section>
</template>
