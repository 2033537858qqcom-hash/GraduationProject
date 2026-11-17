<script setup lang="ts">
import { useLeaveGuard } from '../composables/useEditProtection';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api, ApiError, errorMessage } from '../lib/api';
import type { PaymentBill } from '../lib/payment';
import { formatDateTime, formatMoney as money } from '../lib/format';
import { dormQuantity, dormUtilityName, type DormQuote, type DormRechargeOverview } from '../dormRecharge';
import BaseSelect from './BaseSelect.vue';

const emit = defineEmits<{ created: [bill: PaymentBill] }>();
const route = useRoute();
const overview = ref<DormRechargeOverview | null>(null),
  loading = ref(true),
  busy = ref(false),
  error = ref(''),
  noDorm = ref(false),
  collapsed = ref(false);
const meterId = ref(''),
  mode = ref('AMOUNT'),
  value = ref(''),
  quote = ref<DormQuote | null>(null),
  requestKey = ref(crypto.randomUUID());
const meters = computed(() => (Array.isArray(overview.value?.meters) ? overview.value.meters : []));
const meter = computed(() => meters.value.find(item => String(item.id) === meterId.value));
const choices = computed(() =>
  meters.value
    .filter(item => item.enabled && item.standardAvailable)
    .map(item => ({
      value: String(item.id),
      label: `${dormUtilityName(item.category)} · ${item.building} ${item.room}`,
    })),
);
watch([meterId, mode, value, () => meter.value?.unitPriceCents, () => meter.value?.standardId], () => {
  quote.value = null;
  requestKey.value = crypto.randomUUID();
});
async function refresh() {
  loading.value = true;
  error.value = '';
  noDorm.value = false;
  try {
    overview.value = await api<DormRechargeOverview>('/payments/mine/dorm-recharge');
    const qCat = typeof route.query.dormCategory === 'string' ? route.query.dormCategory : '';
    const qMode = typeof route.query.dormMode === 'string' ? route.query.dormMode : '';
    const qVal = typeof route.query.dormValue === 'string' ? route.query.dormValue : '';
    if (qCat) {
      const matched = meters.value.find(item => item.category === qCat && item.enabled && item.standardAvailable);
      if (matched) meterId.value = String(matched.id);
    }
    if (qMode === 'AMOUNT' || qMode === 'QUANTITY') mode.value = qMode;
    if (qVal && /^[0-9]+(?:\.[0-9]+)?$/.test(qVal)) value.value = qVal;
    if (!choices.value.some(item => item.value === meterId.value)) meterId.value = choices.value[0]?.value ?? '';
  } catch (cause) {
    overview.value = null;
    if (cause instanceof ApiError && cause.code === 'NO_CURRENT_DORM') noDorm.value = true;
    else error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
function payload() {
  return {
    requestKey: requestKey.value,
    meterId: Number(meterId.value),
    mode: mode.value,
    value: value.value.trim(),
    expectedUnitPriceCents: meter.value?.unitPriceCents,
  };
}
async function preview() {
  if (!meter.value || busy.value) return;
  busy.value = true;
  error.value = '';
  quote.value = null;
  try {
    quote.value = await api<DormQuote>('/payments/mine/dorm-recharge/quote', {
      method: 'POST',
      body: JSON.stringify(payload()),
    });
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function create() {
  if (!quote.value || busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const bill = await api<PaymentBill>('/payments/mine/dorm-recharge', {
      method: 'POST',
      body: JSON.stringify(payload()),
    });
    value.value = '';
    quote.value = null;
    busy.value = false;
    emit('created', bill);
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
useLeaveGuard(
  busy,
  computed(() => Boolean(value.value.trim())),
  '还有未创建订单的宿舍充值输入，确定离开？',
  { routeUpdate: true },
);
onMounted(refresh);
defineExpose({ refresh });
</script>

<template>
  <section class="portal-panel finance-panel dorm-recharge" aria-labelledby="dorm-recharge-heading">
    <div class="finance-heading">
      <div>
        <h2 id="dorm-recharge-heading">宿舍水电预充值</h2>
        <p class="muted" style="margin: 2px 0 0">一位室友为宿舍充值，宿舍成员共享到账记录。</p>
      </div>
      <div class="finance-actions">
        <button class="button compact" :disabled="loading || busy" @click="refresh">刷新宿舍</button>
        <button type="button" class="button compact" @click="collapsed = !collapsed">
          {{ collapsed ? '展开水电充值 ▾' : '收起水电充值 ▴' }}
        </button>
      </div>
    </div>
    <template v-if="!collapsed">
      <p class="finance-note">
        当前为演示充值，暂未对接实体水电表设备。充值后仅记录演示到账额度，不会扣减实际用量；宿舍成员间费用分摊请自行协商。
      </p>
      <p v-if="loading" role="status">正在核对当前宿舍…</p>
      <p v-else-if="noDorm" class="empty-state">尚无有效的宿舍入住记录，请先联系宿管办理入住。</p>
      <p v-if="error" class="alert" role="alert">{{ error }}</p>
      <template v-if="overview && !loading">
        <p v-if="!meters.length" class="empty-state">当前宿舍尚未绑定水电表，请联系宿管和财务配置。</p>
        <div v-else class="dorm-recharge-body">
          <div class="dorm-meter-grid">
            <article v-for="item in meters" :key="item.id" class="dorm-meter-card">
              <span>{{ item.building }} · {{ item.room }} · {{ dormUtilityName(item.category) }}</span>
              <strong>
                {{ dormQuantity(item.balance) }}
                <small>{{ item.unit }}</small>
              </strong>
              <small>演示可用量 · 表计 {{ item.meterCode }}</small>
              <p>{{ money(item.unitPriceCents) }}/{{ item.unit }} · {{ item.standardName }}</p>
              <span v-if="!item.enabled || !item.standardAvailable" class="finance-warning">当前暂停充值</span>
            </article>
          </div>
          <form v-if="choices.length" class="finance-form dorm-recharge-form" @submit.prevent="preview">
            <fieldset :disabled="busy">
              <label>
                充值宿舍表计
                <BaseSelect v-model="meterId" :options="choices" aria-label="充值宿舍表计" />
              </label>
              <label>
                充值方式
                <BaseSelect
                  v-model="mode"
                  :options="[
                    { value: 'AMOUNT', label: '按金额充值' },
                    { value: 'QUANTITY', label: '按用量充值' },
                  ]"
                  aria-label="充值方式"
                />
              </label>
              <label>
                {{ mode === 'AMOUNT' ? '充值金额（元）' : `充值用量（${meter?.unit ?? ''}）` }}
                <input
                  v-model="value"
                  required
                  inputmode="decimal"
                  maxlength="20"
                  :placeholder="mode === 'AMOUNT' ? '例如 10.00' : '例如 20'"
                />
              </label>
              <div class="finance-actions" style="align-self: end">
                <button class="button primary" :disabled="!meterId || !value.trim()">
                  {{ busy ? '正在核对…' : '核对充值' }}
                </button>
              </div>
            </fieldset>
          </form>
        </div>
        <div v-if="quote && meter" class="finance-subsection" aria-live="polite">
          <p>
            <strong>{{ meter.building }} {{ meter.room }} · {{ dormUtilityName(meter.category) }}</strong>
          </p>
          <p>
            应付 {{ money(quote.amountCents) }}，演示到账 {{ dormQuantity(quote.quantity) }}
            {{ meter.unit }}。创建后请在本人账单中完成沙箱支付。
          </p>
          <button class="button primary" :disabled="busy" @click="create">创建充值订单</button>
        </div>
        <section v-if="overview.history?.length" class="finance-subsection">
          <h3>本宿舍最近充值记录</h3>
          <article v-for="item in overview.history" :key="item.id" class="finance-record">
            <div>
              <strong>{{ dormUtilityName(item.category) }} · {{ money(item.amountCents) }}</strong>
              <p>{{ item.payer }} · {{ formatDateTime(item.createdAt) }}</p>
              <small>
                {{
                  item.reversedAt
                    ? '已退款并冲正'
                    : item.creditedAt
                      ? '已到账（演示）'
                      : item.status === 'CLOSED'
                        ? '订单已关闭'
                        : item.status === 'PAID'
                          ? '到账待核对'
                          : '尚未支付'
                }}
              </small>
            </div>
          </article>
        </section>
      </template>
    </template>
  </section>
</template>

<style scoped>
.dorm-recharge .finance-note {
  margin-top: 14px;
  font-size: var(--text-supporting);
}
.dorm-recharge-body {
  display: grid;
  grid-template-columns: minmax(240px, 0.9fr) minmax(0, 1.4fr);
  gap: 18px;
  align-items: start;
  margin-top: 14px;
}
.dorm-meter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px;
}
.dorm-recharge-form {
  margin-top: 0;
  padding: 14px 16px;
  background: var(--color-paper);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-xs);
}
.dorm-meter-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-top: 2px solid var(--color-brand);
  border-radius: var(--radius-xs);
  background: var(--color-paper);
  color: var(--color-ink);
}
.dorm-meter-card strong {
  font-family: var(--campus-display-serif);
  font-size: 22px;
  font-variant-numeric: tabular-nums;
  color: var(--color-heading);
  overflow-wrap: anywhere;
}
.dorm-meter-card small {
  font-size: var(--text-meta);
  font-weight: 400;
}
.dorm-meter-card p {
  margin: 0;
  font-size: var(--text-supporting);
}

@media (max-width: 600px) {
  .dorm-meter-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
