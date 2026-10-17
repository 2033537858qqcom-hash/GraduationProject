<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertCircle, CheckCircle2, Copy, Printer, Search, ShieldCheck } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { useToast } from '../composables/useToast'

interface VerificationResult {
  valid: boolean
  documentNumber: string
  studentName: string
  identityNumber: string
  departmentName: string
  majorName: string
  className: string
  campusName: string
  issuedAt: string | null
  message: string
}

const route = useRoute()
const router = useRouter()
const inputCode = ref('')
const loading = ref(false)
const searched = ref(false)
const error = ref('')
const copied = ref(false)
const result = ref<VerificationResult | null>(null)

function formatTime(iso: string | null) {
  if (!iso) return ''
  return iso.slice(0, 16).replace('T', ' ')
}

async function doVerify(codeToVerify?: string) {
  const code = (codeToVerify ?? inputCode.value).trim()
  if (!code) {
    error.value = '请输入在校证明防伪编码'
    result.value = null
    return
  }
  loading.value = true
  error.value = ''
  searched.value = true
  try {
    const data = await api<VerificationResult>(`/public/certificates/verify?number=${encodeURIComponent(code)}`)
    result.value = data
    if (!data.valid) {
      error.value = data.message || '未检索到有效证明'
    } else {
      inputCode.value = data.documentNumber
    }
  } catch (cause) {
    error.value = errorMessage(cause)
    result.value = null
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  if (inputCode.value.trim()) {
    void router.replace({ query: { number: inputCode.value.trim() } })
    void doVerify(inputCode.value.trim())
  }
}

const toast = useToast()

async function copyShareLink() {
  const url = window.location.origin + '/verify/certificate?number=' + encodeURIComponent(result.value?.documentNumber ?? '')
  try {
    await navigator.clipboard.writeText(url)
    copied.value = true
    toast.success('验真链接已复制到剪贴板，可供扫码或转发核验')
    setTimeout(() => { copied.value = false }, 2500)
  } catch {
    toast.info('请直接复制浏览器地址栏链接进行核验')
  }
}

function printReport() {
  window.print()
}

onMounted(() => {
  const queryNum = String(route.query.number ?? route.query.code ?? '').trim()
  if (queryNum) {
    inputCode.value = queryNum
    void doVerify(queryNum)
  }
})

watch(() => route.query.number, next => {
  const val = String(next ?? '').trim()
  if (val && val !== inputCode.value) {
    inputCode.value = val
    void doVerify(val)
  }
})

const verifyYear = computed(() => {
  if (!result.value?.issuedAt) return new Date().getFullYear()
  return new Date(result.value.issuedAt).getFullYear()
})
</script>

<template>
  <div class="verify-page page-width">
    <!-- Non-print Search Header -->
    <header class="verify-header no-print">
      <div class="verify-title-row">
        <ShieldCheck :size="32" class="shield-brand-icon" />
        <div>
          <h1>在校证明防伪验真中心</h1>
          <p class="muted">输入证明防伪编码或扫描证明二维码，核验学生在籍在校事实与数字存证信息</p>
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
        <span>郑州工业应用技术学院 · 官方学籍备案核验</span>
      </div>

      <!-- Card Top Bar -->
      <div class="verify-card-header">
        <div class="school-emboss">
          <span class="univ-cn">郑州工业应用技术学院</span>
          <span class="univ-en">Zhengzhou University of Industrial Technology</span>
        </div>
        <div class="verify-badge-pill">
          <CheckCircle2 :size="15" />
          <span>学籍备案核验通过</span>
        </div>
      </div>

      <div class="verify-card-divider"></div>

      <!-- Document Title -->
      <div class="verify-doc-title">
        <h2>普通全日制在籍在校证明 · 数字化备案凭证</h2>
        <div class="verify-meta-line">
          <span>防伪核验编号：<strong>{{ result.documentNumber }}</strong></span>
          <span>签发归档时间：{{ formatTime(result.issuedAt) }}</span>
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
          <p>
            经郑州工业应用技术学院校园综合服务平台数据中心比对，上述学生在校身份与学籍信息属实，审核流程完备，已完成学院学生事务办公室及学生处审核备案。
          </p>
          <p class="statement-legal">
            本电子凭证与纸质在校证明具备同等核验效力。出具证明之日起 {{ verifyYear }} 年度内有效。
          </p>
        </div>

        <!-- Authentic Red Digital Seal (SVG) -->
        <div class="verify-seal-wrap" title="郑州工业应用技术学院学生事务电子专用章">
          <svg class="official-seal-svg" viewBox="0 0 160 160">
            <circle cx="80" cy="80" r="74" fill="none" stroke="#d32f2f" stroke-width="4.5" />
            <circle cx="80" cy="80" r="70" fill="none" stroke="#d32f2f" stroke-width="1.2" stroke-dasharray="2 2" />
            <polygon points="80,50 86,68 105,68 89,80 95,98 80,86 65,98 71,80 55,68 74,68" fill="#d32f2f" />
            <path id="seal-text-path" d="M 22,80 A 58,58 0 1,1 138,80" fill="none" />
            <text fill="#d32f2f" font-size="12.5" font-weight="bold" letter-spacing="2">
              <textPath href="#seal-text-path" startOffset="50%" text-anchor="middle">
                郑州工业应用技术学院
              </textPath>
            </text>
            <text x="80" y="128" fill="#d32f2f" font-size="10.5" font-weight="bold" text-anchor="middle" letter-spacing="1">
              学生事务电子专用章
            </text>
          </svg>
        </div>
      </div>

      <!-- Footer Note & Actions -->
      <footer class="verify-card-footer">
        <div class="audit-foot-info">
          <span>防伪状态：<strong>加密数字防篡改已存证</strong></span>
          <span>验真端点：Campus Digital Trust Gateway</span>
        </div>
        <div class="verify-actions no-print">
          <button class="button compact" @click="copyShareLink">
            <Copy :size="14" />
            <span>{{ copied ? '链接已复制' : '复制验真链接' }}</span>
          </button>
          <button class="button primary compact" @click="printReport">
            <Printer :size="14" />
            <span>打印防伪报告</span>
          </button>
        </div>
      </footer>
    </article>

    <!-- Empty State Guide -->
    <div v-else-if="!searched && !loading" class="verify-placeholder no-print">
      <div class="placeholder-icon-ring">
        <ShieldCheck :size="48" />
      </div>
      <h3>官方防伪凭证核验</h3>
      <p>若您持有由本校学生综合服务平台出具的《在校信息证明》，请输入证明左下方的防伪编码（如 <code>DEMO-20261017-000001</code>）进行真伪查验。</p>
    </div>
  </div>
</template>

<style scoped>
.verify-page {
  padding-block: 40px 60px;
  max-width: 920px;
  margin-inline: auto;
}

.verify-header {
  margin-bottom: 32px;
}

.verify-title-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}

.shield-brand-icon {
  color: var(--brand);
  flex-shrink: 0;
}

.verify-title-row h1 {
  font-size: 26px;
  margin: 0 0 4px;
  color: var(--brand);
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
  padding: 12px 14px 12px 42px;
  font-size: 14px;
  font-family: inherit;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fff;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.search-input-wrap input:focus {
  outline: none;
  border-color: var(--brand);
  box-shadow: 0 0 0 3px rgba(36, 75, 120, 0.15);
}

.verify-alert {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  background: #fdf3f2;
  border: 1px solid #f5c6cb;
  color: #721c24;
  padding: 16px 20px;
  border-radius: 6px;
  margin-bottom: 28px;
}

.verify-card {
  position: relative;
  background: #fff;
  border: 1px solid #d4deea;
  border-radius: 8px;
  padding: 36px 42px;
  box-shadow: 0 8px 30px rgba(20, 43, 69, 0.06);
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
  font-size: 18px;
  font-weight: 600;
  color: var(--brand);
  letter-spacing: 1px;
}

.school-emboss .univ-en {
  display: block;
  font-size: 10px;
  color: var(--muted);
  letter-spacing: 0.5px;
  margin-top: 2px;
}

.verify-badge-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: #eef8f1;
  color: #1e7e34;
  border: 1px solid #c3e6cb;
  padding: 5px 12px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
}

.verify-card-divider {
  height: 2px;
  background: linear-gradient(90deg, var(--brand) 0%, #d4deea 100%);
  margin-block: 20px 24px;
}

.verify-doc-title h2 {
  font-size: 20px;
  color: var(--site-ink);
  margin: 0 0 10px;
}

.verify-meta-line {
  display: flex;
  gap: 24px;
  font-size: 12px;
  color: var(--muted);
  flex-wrap: wrap;
}

.verify-meta-line strong {
  color: var(--brand);
  font-family: monospace;
  font-size: 13px;
}

.verify-facts-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1px;
  background: #e5ebf3;
  border: 1px solid #e5ebf3;
  margin-block: 28px;
  border-radius: 4px;
  overflow: hidden;
}

.fact-cell {
  background: #fff;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.fact-label {
  font-size: 11px;
  color: var(--muted);
}

.fact-val {
  font-size: 14px;
  color: #24292e;
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
  font-size: 13px;
  line-height: 1.8;
  color: #444d56;
}

.statement-lead {
  font-weight: 600;
  color: var(--brand);
  margin-bottom: 6px;
}

.statement-legal {
  font-size: 11px;
  color: var(--muted);
  margin-top: 8px;
}

.verify-seal-wrap {
  width: 140px;
  height: 140px;
  flex-shrink: 0;
  opacity: 0.92;
  transform: rotate(-10deg);
}

.official-seal-svg {
  width: 100%;
  height: 100%;
}

.verify-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #edf0f4;
  padding-top: 20px;
  gap: 16px;
  flex-wrap: wrap;
}

.audit-foot-info {
  display: flex;
  gap: 20px;
  font-size: 11px;
  color: var(--muted);
}

.verify-actions {
  display: flex;
  gap: 10px;
}

.verify-placeholder {
  text-align: center;
  padding: 60px 20px;
  background: #fff;
  border: 1px dashed var(--line);
  border-radius: 8px;
}

.placeholder-icon-ring {
  width: 72px;
  height: 72px;
  margin: 0 auto 16px;
  border-radius: 50%;
  background: #eef3f8;
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
}

.verify-placeholder h3 {
  font-size: 18px;
  margin: 0 0 10px;
  color: var(--brand);
}

.verify-placeholder p {
  color: var(--muted);
  font-size: 13px;
  max-width: 500px;
  margin: 0 auto;
  line-height: 1.7;
}

.verify-placeholder code {
  color: var(--brand);
  background: #edf3f9;
  padding: 2px 6px;
  border-radius: 4px;
}

/* Print Friendly Styles */
@media print {
  .no-print,
  .public-header,
  .public-footer,
  .school-navigation {
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
