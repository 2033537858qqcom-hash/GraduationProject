<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { formatMoney as money } from '../../lib/format';
import type { StatementPreview } from '../../finance';
import DownloadLink from '../DownloadLink.vue';
import FileDropZone from '../FileDropZone.vue';
import ReconciliationCasePanel from '../ReconciliationCasePanel.vue';

const statementResults: Record<string, string> = {
  MATCH: '一致',
  DIFFERENCE: '存在差异',
  NOT_FOUND: '本地无此订单',
  INVALID: '格式待核对',
  DUPLICATE: '重复订单',
};

defineProps<{
  preview: StatementPreview | null;
  canManage: boolean;
  canReview: boolean;
  busy: boolean;
}>();

const csv = defineModel<string>('csv', { required: true });

const emit = defineEmits<{
  readStatement: [event: Event];
  compareStatement: [];
  openBill: [id: number];
}>();

const fileDrop = ref<InstanceType<typeof FileDropZone> | null>(null);
const loadedFileName = ref('');

const csvSummary = computed(() => {
  const raw = csv.value.trim();
  if (!raw) return '未载入内容，可直接拖入 CSV 或手动粘贴明细';
  const rows = raw.split(/\r?\n/).filter(line => line.trim()).length;
  const bytes = new Blob([csv.value]).size;
  const size = bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`;
  return `共 ${rows} 行 · ${size}`;
});

watch(csv, value => {
  if (!value) loadedFileName.value = '';
});

function handleStatementFile(event: Event) {
  const target = event.target as HTMLInputElement;
  loadedFileName.value = target.files?.[0]?.name ?? '';
  emit('readStatement', event);
}

function clearCsv() {
  csv.value = '';
  loadedFileName.value = '';
  fileDrop.value?.clear();
}
</script>

<template>
  <section class="portal-panel finance-panel">
    <div class="finance-heading">
      <h2>交易明细核对</h2>
      <DownloadLink
        href="/api/finance/payments/reconciliation/template"
        expected-type="text/csv"
        class="button compact"
      >
        下载核对模板
      </DownloadLink>
    </div>
    <div class="finance-reconcile-intro">
      <p class="muted">
        将支付平台导出的交易明细按模板整理为 UTF-8 CSV，可核对订单号、金额、交易号及支付状态。每批最多 256 KB、2000 笔。
      </p>
      <p class="finance-note">
        导入明细只用于发现差异，不会将账单标记为已支付。发现差异后，请进入原账单使用“核验支付结果”向支付平台查单。
      </p>
    </div>
    <form class="finance-form finance-reconcile-form" @submit.prevent="emit('compareStatement')">
      <FileDropZone
        ref="fileDrop"
        accept=".csv,text/csv"
        kind="spreadsheet"
        :disabled="busy"
        :file-name="loadedFileName"
        title="点击选择或拖入交易明细 CSV 文件"
        hint="支持 UTF-8 CSV（订单号、金额、交易号、支付状态），每批最多 256 KB"
        @change="handleStatementFile"
        @clear="clearCsv"
      />
      <div class="csv-workbench">
        <div class="csv-workbench-head">
          <label for="finance-reconcile-csv">交易明细内容</label>
          <div class="csv-workbench-meta">
            <span>{{ csvSummary }}</span>
            <button v-if="csv.trim()" type="button" class="text-button" :disabled="busy" @click="clearCsv">
              清空内容
            </button>
          </div>
        </div>
        <textarea
          id="finance-reconcile-csv"
          v-model="csv"
          class="csv-workbench-input"
          required
          maxlength="262144"
          rows="6"
          spellcheck="false"
          :disabled="busy"
          placeholder="订单号,金额（元）,交易号,支付状态&#10;FIN-20261101-00301,50.00,202611012200140001,TRADE_SUCCESS"
        />
      </div>
      <div class="finance-actions finance-reconcile-actions">
        <button class="button primary" :disabled="busy">{{ busy ? '正在核对…' : '生成差异核对结果' }}</button>
        <span class="muted">仅生成比对预检结果，可复核后再建差异单跟踪。</span>
      </div>
    </form>
    <section v-if="preview" class="finance-subsection">
      <p>
        一致 {{ preview.matchedCount }} 笔 · 差异 {{ preview.differenceCount }} 笔 · 本地无订单
        {{ preview.notFoundCount }} 笔 · 格式异常 {{ preview.invalidCount }} 笔 · 重复 {{ preview.duplicateCount }} 笔
      </p>
      <article v-for="row in preview.items" :key="row.line" class="finance-record">
        <div>
          <span class="finance-badge">第 {{ row.line }} 行 · {{ statementResults[row.result] }}</span>
          <h3>{{ row.orderNo }}</h3>
          <p>
            明细金额 {{ row.amount }} 元 · 本地金额
            {{ row.localAmountCents === null ? '—' : money(row.localAmountCents) }}
          </p>
          <p>{{ row.explanation }}</p>
        </div>
        <button v-if="row.billId" class="button compact" :disabled="busy" @click="emit('openBill', row.billId)">
          查看原账单
        </button>
      </article>
    </section>
    <ReconciliationCasePanel
      :csv="csv"
      :preview="preview"
      :can-manage="canManage"
      :can-review="canReview"
      @open-bill="emit('openBill', $event)"
    />
  </section>
</template>
