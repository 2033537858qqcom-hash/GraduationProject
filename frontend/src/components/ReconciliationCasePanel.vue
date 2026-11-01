<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import { reconActions, reconResults, reconStatuses, type ReconciliationCase, type ReconciliationCasePage, type ReconciliationHistory } from '../reconciliationCases'
import type { StatementPreview } from '../finance'
import BaseSelect from './BaseSelect.vue'

const props = defineProps<{ csv: string; preview: StatementPreview | null; canManage: boolean; canReview: boolean }>()
const emit = defineEmits<{ openBill: [id: number] }>()
const route=useRoute()
const cases=ref<ReconciliationCasePage>({ items: [], total: 0, page: 1, pageSize: 20 }), focused=ref<ReconciliationCase | null>(null)
const loading=ref(true), busy=ref(false), error=ref(''), message=ref(''), importKey=ref(crypto.randomUUID())
const filter=ref('')
const filterOptions=[{value:'',label:'全部状态'},{value:'OPEN',label:'待核查'},{value:'IN_PROGRESS',label:'核查中'},{value:'PENDING_REVIEW',label:'待复核'},{value:'CLOSED',label:'已关闭'}]
const target=ref<ReconciliationCase | null>(null), action=ref(''), note=ref(''), actionKey=ref(crypto.randomUUID())
const historyId=ref<number | null>(null), history=ref<ReconciliationHistory[]>([])
const dirty=computed(() => Boolean(target.value && note.value.trim()))
const displayed=computed(() => focused.value && (!filter.value || focused.value.status===filter.value) && !cases.value.items.some(item=>item.id===focused.value?.id) ? [focused.value, ...cases.value.items] : cases.value.items)
const savable=computed(() => props.preview && props.preview.differenceCount+props.preview.notFoundCount+props.preview.invalidCount+props.preview.duplicateCount>0)
watch(() => props.csv, () => { importKey.value=crypto.randomUUID() })
watch(() => props.preview, () => { importKey.value=crypto.randomUUID() })
watch(() => route.query.case, () => { void load(1) })
watch(filter, () => { void load(1) })
watch(note, () => { actionKey.value=crypto.randomUUID() })
let sequence=0
async function load(page=cases.value.page) {
  const next=++sequence;loading.value=true;error.value=''
  try {
    const result=await api<ReconciliationCasePage>(`/finance/payments/reconciliation/cases?page=${page}&pageSize=20&status=${encodeURIComponent(filter.value)}`)
    if(next!==sequence) return
    const id=Number(route.query.case)
    const selected=Number.isSafeInteger(id) && id>0 ? await api<ReconciliationCase>(`/finance/payments/reconciliation/cases/${id}`) : null
    if(next!==sequence) return
    cases.value=result;focused.value=selected
  } catch(cause) { if(next===sequence) error.value=errorMessage(cause) }
  finally { if(next===sequence) loading.value=false }
}
async function save() {
  if(!props.canManage || !savable.value || busy.value) return
  busy.value=true;error.value='';message.value=''
  try {
    const result=await api<{ batchId: number; items: ReconciliationCase[] }>('/finance/payments/reconciliation/cases',{method:'POST',body:JSON.stringify({requestKey:importKey.value,csv:props.csv})})
    message.value=`已保存 ${result.items.length} 项待核查差异。`;await load(1)
  } catch(cause) { error.value=errorMessage(cause) }
  finally { busy.value=false }
}
function choose(item: ReconciliationCase,kind: string) {
  if(dirty.value && !window.confirm('还有未提交的核查说明，确定切换？')) return
  target.value=item;action.value=kind;note.value='';actionKey.value=crypto.randomUUID();error.value=''
}
async function submit() {
  if(!target.value || busy.value) return
  busy.value=true;error.value='';message.value=''
  try {
    await api(`/finance/payments/reconciliation/cases/${target.value.id}/actions`,{method:'POST',body:JSON.stringify({action:action.value,version:target.value.version,requestKey:actionKey.value,reason:note.value.trim()})})
    target.value=null;note.value='';message.value='核查进度已保存，账单支付状态未改变。';await load()
  } catch(cause) { error.value=errorMessage(cause) }
  finally { busy.value=false }
}
async function showHistory(item: ReconciliationCase) {
  if(historyId.value===item.id) { historyId.value=null;history.value=[];return }
  try { history.value=await api<ReconciliationHistory[]>(`/finance/payments/reconciliation/cases/${item.id}/history`);historyId.value=item.id }
  catch(cause) { error.value=errorMessage(cause) }
}
function leave() { return !busy.value && (!dirty.value || window.confirm('还有未提交的对账核查说明，确定离开？')) }
function beforeUnload(event: BeforeUnloadEvent) { if(busy.value || dirty.value) { event.preventDefault();event.returnValue='' } }
onBeforeRouteLeave(leave)
onBeforeRouteUpdate(leave)
onMounted(() => { window.addEventListener('beforeunload',beforeUnload);void load() })
onBeforeUnmount(() => { sequence++;window.removeEventListener('beforeunload',beforeUnload) })
</script>

<template>
  <section class="finance-subsection" aria-labelledby="reconciliation-cases-heading">
    <div class="finance-heading"><div><h3 id="reconciliation-cases-heading">差异跟踪</h3><p class="muted">保存时会按当前账单重新核对，只保存异常行；原始文件不会保存，过长的异常字段会截短。</p></div><button class="button compact" :disabled="loading || busy" @click="load()">刷新事项</button></div>
    <p class="finance-note">核查结论和复核关闭只记录处理过程，不改变账单支付或退款状态。需要核验到账时，请在原账单向支付平台查单。</p>
    <label class="recon-filter">处理状态<BaseSelect v-model="filter" :options="filterOptions" aria-label="对账事项状态" compact /></label>
    <p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="message" class="finance-success" role="status">{{ message }}</p>
    <button v-if="canManage && preview" class="button primary" :disabled="busy || !savable" @click="save">保存差异并跟踪</button>
    <p v-if="preview && !savable" class="muted">本次没有需要跟踪的差异。</p>
    <form v-if="target" class="finance-confirm recon-action" @submit.prevent="submit"><h3>{{ reconActions[action] }} · 事项 #{{ target.id }}</h3><p>订单 {{ target.orderNo || '无法识别' }}；该操作不会更改账单支付状态。</p><label>核查说明<textarea v-model="note" required minlength="5" maxlength="500" rows="3" :disabled="busy" /></label><div class="finance-actions"><button class="button primary" :disabled="busy">确认{{ reconActions[action] }}</button><button type="button" class="button" :disabled="busy" @click="target=null;note=''">取消</button></div></form>
    <p v-if="loading" role="status">正在读取差异事项…</p><p v-else-if="!displayed.length && !error" class="empty-state">当前状态下暂无对账差异事项。</p>
    <article v-for="item in displayed" :key="item.id" class="finance-record" :class="{ 'finance-selected': String(item.id) === String(route.query.case) }">
      <div>
        <span class="finance-badge">{{ reconResults[item.result] || item.result }} · {{ reconStatuses[item.status] }}</span><h3>事项 #{{ item.id }} · {{ item.orderNo || '订单号无法识别' }}</h3><p>导入第 {{ item.line }} 行 · 明细金额 {{ item.amount || '—' }} 元 · 保存时本地金额 {{ item.localAmountCents === null ? '—' : '¥' + (item.localAmountCents / 100).toFixed(2) }}</p><p>明细状态 {{ item.statementStatus || '—' }} · 交易号 {{ item.tradeNo || '—' }} · 保存时本地状态 {{ item.localStatus || '—' }}</p><p>{{ item.explanation }}</p><small class="muted">{{ item.assigneeName ? '核查人：' + item.assigneeName : '尚未分配核查人' }}<template v-if="item.lastNote"> · 最近说明：{{ item.lastNote }}</template></small>
        <ol v-if="historyId === item.id" class="finance-history"><li v-for="(event,index) in history" :key="index"><strong>{{ reconActions[event.action] || event.action }}</strong><span>{{ event.actorName || '系统' }} · {{ event.createdAt.slice(0,16).replace('T',' ') }}</span><p>{{ event.reason }}</p></li></ol>
      </div>
      <div class="finance-actions"><button v-if="item.billId" class="button compact" :disabled="busy" @click="emit('openBill',item.billId)">查看原账单</button><button class="button compact" :disabled="busy" @click="showHistory(item)">{{ historyId === item.id ? '收起记录' : '查看记录' }}</button><button v-if="canManage && item.status === 'OPEN'" class="button compact" :disabled="busy" @click="choose(item,'START')">开始核查</button><button v-if="canManage && item.status === 'IN_PROGRESS' && item.assigneeId === currentUser?.id" class="button compact" :disabled="busy" @click="choose(item,'SUBMIT')">提交复核</button><button v-if="canManage && item.status === 'IN_PROGRESS' && item.assigneeId !== currentUser?.id" class="button compact" :disabled="busy" @click="choose(item,'TAKE_OVER')">接手核查</button><template v-if="canReview && item.status === 'PENDING_REVIEW' && item.assigneeId !== currentUser?.id"><button class="button compact" :disabled="busy" @click="choose(item,'CLOSE')">复核关闭</button><button class="button compact" :disabled="busy" @click="choose(item,'RETURN')">退回核查</button></template><button v-if="canManage && item.status === 'CLOSED'" class="button compact" :disabled="busy" @click="choose(item,'REOPEN')">重新打开</button></div>
    </article>
    <div v-if="cases.total" class="finance-pagination"><span>共 {{ cases.total }} 项 · 第 {{ cases.page }} / {{ Math.ceil(cases.total / cases.pageSize) }} 页</span><div class="finance-actions"><button class="button compact" :disabled="loading || cases.page <= 1" @click="load(cases.page-1)">上一页</button><button class="button compact" :disabled="loading || cases.page * cases.pageSize >= cases.total" @click="load(cases.page+1)">下一页</button></div></div>
  </section>
</template>
<style scoped>
.finance-note { margin: 12px 0; font-size: 12px; }
.recon-action { display: grid; gap: 12px; margin: 18px 0; padding: 18px; border: 1px solid #dce4d0; border-radius: 6px; }
.recon-action p { margin: 0; }
.recon-filter { width: 160px; max-width: 100%; margin: 14px 0; }
</style>
