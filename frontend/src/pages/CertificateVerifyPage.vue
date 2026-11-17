<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertCircle, ArrowLeft, CheckCircle2, Copy, Download, Search, ShieldCheck, X } from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import { formatDateTime as formatTime } from '../lib/format';
import { useToast } from '../composables/useToast';
import DownloadLink from '../components/DownloadLink.vue';

interface VerificationResult {
  valid: boolean;
  documentNumber: string;
  studentName: string;
  identityNumber: string;
  departmentName: string;
  majorName: string;
  className: string;
  campusName: string;
  issuedAt: string | null;
  message: string;
}

const route = useRoute();
const router = useRouter();
const inputCode = ref('');
const loading = ref(false);
const searched = ref(false);
const error = ref('');
const copied = ref(false);
const result = ref<VerificationResult | null>(null);

async function doVerify(codeToVerify?: string) {
  const code = (codeToVerify ?? inputCode.value).trim();
  if (!code) {
    error.value = '请输入在校证明防伪编码';
    result.value = null;
    return;
  }
  loading.value = true;
  error.value = '';
  searched.value = true;
  try {
    const data = await api<VerificationResult>(`/public/certificates/verify?number=${encodeURIComponent(code)}`);
    result.value = data;
    if (!data.valid) {
      error.value = data.message || '未检索到有效证明';
    } else {
      inputCode.value = data.documentNumber;
    }
  } catch (cause) {
    error.value = errorMessage(cause);
    result.value = null;
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  if (inputCode.value.trim()) {
    void router.replace({ query: { number: inputCode.value.trim() } });
    void doVerify(inputCode.value.trim());
  }
}

function fillSampleCode() {
  const sample = 'DEMO-20261017-000001';
  inputCode.value = sample;
  void router.replace({ query: { number: sample } });
  void doVerify(sample);
}

function clearCode() {
  inputCode.value = '';
  error.value = '';
  searched.value = false;
  result.value = null;
  void router.replace({ query: {} });
}

const toast = useToast();

async function copyShareLink() {
  const url =
    window.location.origin + '/verify/certificate?number=' + encodeURIComponent(result.value?.documentNumber ?? '');
  try {
    await navigator.clipboard.writeText(url);
    copied.value = true;
    toast.success('验真链接已复制到剪贴板，可供扫码或转发核验');
    setTimeout(() => {
      copied.value = false;
    }, 2500);
  } catch {
    toast.info('请直接复制浏览器地址栏链接进行核验');
  }
}

onMounted(() => {
  const queryNum = String(route.query.number ?? route.query.code ?? '').trim();
  if (queryNum) {
    inputCode.value = queryNum;
    void doVerify(queryNum);
  }
});

watch(
  () => route.query.number,
  next => {
    const val = String(next ?? '').trim();
    if (val && val !== inputCode.value) {
      inputCode.value = val;
      void doVerify(val);
    }
  },
);
</script>

<template>
  <div class="verify-page page-width">
    <div class="verify-context-bar no-print">
      <RouterLink to="/?chapter=disclosure">
        <ArrowLeft :size="13" />
        返回信息公开
      </RouterLink>
      <span>PUBLIC VERIFICATION / ARCHIVE</span>
    </div>

    <!-- Non-print Search Header -->
    <header class="verify-header no-print">
      <div class="verify-title-row">
        <div class="verify-title-copy">
          <span class="verify-eyebrow">公开核验服务 · 郑州工业应用技术学院</span>
          <h1>在校证明核验</h1>
          <p class="muted">输入证明编号或扫描二维码，查询平台中的演示证明记录。公开信息已脱敏。</p>
        </div>
      </div>

      <form class="verify-search-box" @submit.prevent="handleSearch">
        <label for="code-input" class="sr-only">证明防伪编码</label>
        <div class="search-input-wrap">
          <Search :size="18" class="search-icon" />
          <input
            id="code-input"
            v-model="inputCode"
            type="text"
            maxlength="60"
            placeholder="例如：DEMO-20261017-000001"
            :disabled="loading"
          />
          <button
            v-if="inputCode && !loading"
            type="button"
            class="search-clear-btn"
            aria-label="清空防伪编码"
            @click="clearCode"
          >
            <X :size="14" />
          </button>
        </div>
        <button type="submit" class="button primary" :disabled="loading || !inputCode.trim()">
          {{ loading ? '正在核验…' : '立即核验' }}
        </button>
      </form>
    </header>

    <!-- Error Prompt -->
    <div v-if="error && !loading" class="verify-alert alert no-print" role="alert">
      <AlertCircle :size="18" />
      <div>
        <strong>核验未通过</strong>
        <p>{{ error }}</p>
      </div>
    </div>

    <!-- Valid Certificate Report (Printable Card) -->
    <article v-if="result && result.valid" class="verify-card printable-sheet">
      <!-- Watermark background -->
      <div class="verify-watermark" aria-hidden="true">
        <span>校园综合服务平台 · 毕业设计演示</span>
      </div>

      <!-- Card Top Bar -->
      <div class="verify-card-header">
        <div class="school-emboss">
          <span class="univ-cn">郑州工业应用技术学院</span>
          <span class="univ-en">Zhengzhou University of Industrial Technology</span>
        </div>
        <div class="verify-badge-pill">
          <CheckCircle2 :size="15" />
          <span>已匹配演示记录</span>
        </div>
      </div>

      <div class="verify-card-divider"></div>

      <!-- Document Title -->
      <div class="verify-doc-title">
        <h2>在校证明核验报告（演示）</h2>
        <div class="verify-meta-line">
          <span>
            防伪核验编号：
            <strong>{{ result.documentNumber }}</strong>
          </span>
          <span>生成时间：{{ formatTime(result.issuedAt) }}</span>
        </div>
      </div>

      <!-- Key Student Data Table -->
      <div class="verify-facts-grid">
        <div class="fact-cell">
          <span class="fact-label">学生姓名</span>
          <strong class="fact-val">{{ result.studentName }}</strong>
        </div>
        <div class="fact-cell">
          <span class="fact-label">学号 / 考生号</span>
          <strong class="fact-val">{{ result.identityNumber }}</strong>
        </div>
        <div class="fact-cell">
          <span class="fact-label">所属学院</span>
          <span class="fact-val">{{ result.departmentName }}</span>
        </div>
        <div class="fact-cell">
          <span class="fact-label">就读专业</span>
          <span class="fact-val">{{ result.majorName }}</span>
        </div>
        <div class="fact-cell">
          <span class="fact-label">行政班级</span>
          <span class="fact-val">{{ result.className }}</span>
        </div>
        <div class="fact-cell">
          <span class="fact-label">就读校区</span>
          <span class="fact-val">{{ result.campusName }}</span>
        </div>
      </div>

      <!-- Verification Statement & Digital Seal -->
      <div class="verify-statement-row">
        <div class="verify-statement-text">
          <p class="statement-lead">核验证明结论：</p>
          <p>已在校园综合服务平台中匹配到已办结的演示证明记录。上方信息来自申请办理时的登记快照，姓名和学号已脱敏。</p>
          <p class="statement-legal">
            本报告用于毕业设计功能展示，不代表学校正式学籍认证、电子签章或官方存证，不替代学校正式证明。
          </p>
        </div>
        <div class="verify-seal-emblem" aria-hidden="true">
          <ShieldCheck :size="22" />
          <strong>演示记录已核验</strong>
          <small>Z.Z.U.I.T · VERIFIED</small>
        </div>
      </div>

      <!-- Footer Note & Actions -->
      <footer class="verify-card-footer">
        <div class="audit-foot-info">
          <span>
            记录状态：
            <strong>平台演示记录已办结</strong>
          </span>
          <span>下载 PDF 后可打开打印</span>
        </div>
        <div class="verify-actions no-print">
          <button class="button compact" @click="copyShareLink">
            <Copy :size="14" />
            <span>{{ copied ? '链接已复制' : '复制验真链接' }}</span>
          </button>
          <DownloadLink
            class="button primary compact"
            :href="`/api/public/certificates/report?number=${encodeURIComponent(result.documentNumber)}`"
            expected-type="application/pdf"
          >
            <Download :size="14" />
            下载报告 PDF
          </DownloadLink>
        </div>
      </footer>
    </article>

    <!-- Empty State Guide -->
    <div v-else-if="!searched && !loading" class="verify-placeholder no-print">
      <div class="placeholder-icon-ring">
        <ShieldCheck :size="38" />
      </div>
      <h3>演示证明记录查询</h3>
      <p>
        请输入校园综合服务平台演示证明中的编号（如
        <code>DEMO-20261017-000001</code>
        ）进行真伪查验。
      </p>
      <button type="button" class="verify-sample-btn" @click="fillSampleCode">填入示例编号并核验</button>
    </div>
  </div>
</template>

<style scoped>
.verify-page {
  padding-block: 32px 64px;
  max-width: 940px;
  margin-inline: auto;
}

.verify-context-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 24px;
  color: var(--color-text-secondary);
  font-size: var(--text-meta);
  letter-spacing: 1.5px;
}

.verify-context-bar a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: var(--text-meta);
  letter-spacing: 0;
}

.verify-context-bar a:hover {
  color: var(--brand);
}

.verify-header {
  margin-bottom: 32px;
}

.verify-title-row {
  margin-bottom: 24px;
}

.verify-title-copy::before {
  content: '';
  display: block;
  width: 35px;
  height: 1px;
  background: var(--color-gold);
  margin-bottom: 12px;
}

.verify-eyebrow {
  display: block;
  color: var(--color-text-secondary);
  font-size: var(--text-eyebrow);
  letter-spacing: 1.4px;
  margin-bottom: 6px;
}

.verify-title-row h1 {
  font-family: var(--site-serif);
  font-size: clamp(28px, 3vw, 35px);
  font-weight: 400;
  letter-spacing: 2px;
  margin: 0 0 8px;
  color: var(--color-heading);
}

.verify-search-box {
  display: flex;
  gap: 12px;
  max-width: 680px;
}

.search-input-wrap {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
}

.search-input-wrap .search-icon {
  position: absolute;
  left: 14px;
  color: var(--muted);
  pointer-events: none;
}

.search-input-wrap input {
  width: 100%;
  padding: 12px 38px 12px 42px;
  font-size: var(--text-body);
  font-family: inherit;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: var(--color-white);
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.search-input-wrap input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: var(--focus-ring);
}

.search-clear-btn {
  position: absolute;
  right: 10px;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--color-text-tertiary);
  cursor: pointer;
}

.search-clear-btn:hover {
  background: var(--color-surface-soft);
  color: var(--color-ink);
}

.verify-alert {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  background: var(--color-danger-soft);
  border: 1px solid var(--color-danger-border);
  color: var(--color-danger-strong);
  padding: 16px 20px;
  border-radius: var(--radius-md);
  margin-bottom: 28px;
}

.verify-card {
  position: relative;
  background: var(--color-white);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xs);
  padding: 36px 42px;
  box-shadow: var(--shadow-panel);
  overflow: hidden;
}

.verify-watermark {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  opacity: 0.035;
  user-select: none;
  transform: rotate(-25deg);
  font-size: 44px;
  font-weight: 700;
  white-space: nowrap;
}

.verify-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.school-emboss .univ-cn {
  display: block;
  font-family: var(--site-serif);
  font-size: 20px;
  font-weight: 500;
  color: var(--brand);
  letter-spacing: 2px;
}

.school-emboss .univ-en {
  display: block;
  font-size: var(--text-meta);
  color: var(--muted);
  letter-spacing: 0.6px;
  margin-top: 3px;
}

.verify-badge-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--color-success-soft);
  color: var(--color-success);
  border: 1px solid var(--color-border-strong);
  padding: 4px 10px;
  border-radius: var(--radius-xs);
  font-size: var(--text-meta);
  font-weight: 500;
}

.verify-card-divider {
  height: 2px;
  background: linear-gradient(90deg, var(--color-brand) 0%, var(--color-gold) 48%, var(--color-border) 100%);
  margin-block: 20px 24px;
}

.verify-doc-title h2 {
  font-family: var(--site-serif);
  font-size: 22px;
  font-weight: 400;
  letter-spacing: 1px;
  color: var(--site-ink);
  margin: 0 0 10px;
}

.verify-meta-line {
  display: flex;
  gap: 24px;
  font-size: var(--text-supporting);
  color: var(--muted);
  flex-wrap: wrap;
}

.verify-meta-line strong {
  color: var(--brand);
  font-family: monospace;
  font-size: var(--text-supporting);
}

.verify-facts-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: var(--color-surface-soft);
  border: 1px solid var(--color-border-subtle);
  margin-block: 28px;
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.fact-cell {
  background: var(--color-white);
  padding: 15px 18px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.fact-label {
  font-size: var(--text-meta);
  color: var(--muted);
}

.fact-val {
  font-size: var(--text-body);
  color: var(--color-ink);
}

.verify-statement-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 30px;
  margin-block: 20px 30px;
}

.verify-statement-text {
  flex: 1;
  font-size: var(--text-supporting);
  line-height: 1.85;
  color: var(--color-text-secondary);
}

.statement-lead {
  font-weight: 600;
  color: var(--brand);
  margin-bottom: 6px;
}

.statement-legal {
  font-size: var(--text-meta);
  color: var(--muted);
  margin-top: 8px;
}

.verify-seal-emblem {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 16px 20px;
  border: 1px dashed var(--color-gold);
  border-radius: var(--radius-md);
  background: var(--color-surface-soft);
  color: var(--brand);
  text-align: center;
}

.verify-seal-emblem strong {
  font-family: var(--site-serif);
  font-size: var(--text-supporting);
  font-weight: 500;
  letter-spacing: 1px;
}

.verify-seal-emblem small {
  font-size: var(--text-eyebrow);
  letter-spacing: 1.2px;
  color: var(--color-text-tertiary);
}

.verify-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid var(--color-border);
  padding-top: 20px;
  gap: 16px;
  flex-wrap: wrap;
}

.audit-foot-info {
  display: flex;
  gap: 10px 20px;
  flex-wrap: wrap;
  font-size: var(--text-meta);
  color: var(--muted);
}

.verify-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.verify-placeholder {
  text-align: center;
  padding: 52px 24px;
  background: rgb(253 253 249 / 75%);
  border: 1px dashed var(--line);
  border-radius: var(--radius-xs);
}

.placeholder-icon-ring {
  width: 64px;
  height: 64px;
  margin: 0 auto 16px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-soft);
  border: 1px solid var(--color-border-subtle);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
}

.verify-placeholder h3 {
  font-family: var(--site-serif);
  font-size: 20px;
  font-weight: 400;
  letter-spacing: 1px;
  margin: 0 0 10px;
  color: var(--brand);
}

.verify-placeholder p {
  color: var(--muted);
  font-size: var(--text-supporting);
  max-width: 500px;
  margin: 0 auto;
  line-height: 1.8;
}

.verify-placeholder code {
  color: var(--brand);
  background: var(--color-surface-soft);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
}

.verify-sample-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 18px;
  padding: 0 0 6px;
  border: 0;
  border-bottom: 1px solid var(--color-border-strong);
  border-radius: 0;
  background: transparent;
  color: var(--brand);
  font-size: var(--text-meta);
  cursor: pointer;
  transition:
    border-color 0.18s ease,
    color 0.18s ease;
}

.verify-sample-btn:hover {
  border-bottom-color: var(--brand);
}

/* Print Friendly Styles */
@media print {
  .no-print {
    display: none !important;
  }
  .verify-page {
    padding: 0;
    max-width: 100%;
  }
  .verify-card {
    border: none;
    box-shadow: none;
    padding: 20px 0;
  }
}

@media (max-width: 680px) {
  .verify-card {
    padding: 20px;
  }
  .verify-facts-grid {
    grid-template-columns: 1fr;
  }
  .verify-statement-row {
    flex-direction: column-reverse;
    align-items: flex-start;
  }
}
</style>

<style>
@media print {
  body:has(.verify-page) .public-site > :not(.public-main),
  body:has(.verify-page) .public-main > :not(.verify-page),
  body:has(.verify-page) .toast-container {
    display: none !important;
  }
  body:has(.verify-page) .public-main {
    padding: 0 !important;
    min-height: 0 !important;
    background: white !important;
  }
}
</style>
