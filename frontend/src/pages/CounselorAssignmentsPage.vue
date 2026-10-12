<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { RefreshCw, UsersRound } from '@lucide/vue'
import { api, errorMessage } from '../lib/api'
import { currentUser } from '../stores/auth'
import type { AssignmentCatalog, CounselorAssignment } from '../leave'
const catalog = ref<AssignmentCatalog | null>(null), selected = ref<CounselorAssignment | null>(null)
const counselorId = ref(''), reason = ref(''), busy = ref(false), loading = ref(true), error = ref(''), success = ref('')
const dialog = ref<HTMLDialogElement | null>(null)
const dirty = computed(() => !!selected.value && (counselorId.value !== String(selected.value.counselorId ?? '') || !!reason.value.trim()))
let resolveLeave: ((result: boolean) => void) | null = null
function finish(value: boolean) { dialog.value?.close(); resolveLeave?.(value); resolveLeave = null }
async function allowLeave() {
  if (!currentUser.value) return true
  if (busy.value) { error.value = '正在保存，请稍后离开。'; return false }
  if (!dirty.value) return true
  return new Promise<boolean>(resolve => { resolveLeave = resolve; dialog.value?.showModal() })
}
async function load() { loading.value = true; error.value = ''; try { catalog.value = await api<AssignmentCatalog>('/admin/counselors'); selected.value = null; reason.value = '' } catch (cause) { error.value = errorMessage(cause) } finally { loading.value = false } }
async function edit(item: CounselorAssignment) { if (!await allowLeave()) return; selected.value = item; counselorId.value = String(item.counselorId ?? ''); reason.value = ''; success.value = ''; await nextTick(); document.getElementById('assignment-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
async function refresh() { if (await allowLeave()) await load() }
async function save() {
  if (!selected.value) return
  busy.value = true; error.value = ''; success.value = ''
  try { await api('/admin/counselors/' + selected.value.classGroupId, { method: 'PUT', body: JSON.stringify({ counselorId: counselorId.value ? Number(counselorId.value) : null, version: selected.value.version, reason: reason.value }) }); selected.value = null; reason.value = ''; await load(); success.value = '负责关系已保存，待办与材料权限立即按新关系生效。' } catch (cause) { error.value = errorMessage(cause) } finally { busy.value = false }
}
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value || busy.value) { event.preventDefault(); event.returnValue = '' } }
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); void load() })
onBeforeUnmount(() => { window.removeEventListener('beforeunload', beforeUnload); finish(false) })
onBeforeRouteLeave(allowLeave); onBeforeRouteUpdate(allowLeave)
</script>
<template>
  <div>
<dialog ref="dialog" class="foundation-dialog" aria-labelledby="assignment-dialog-heading" @cancel.prevent="finish(false)"><h2 id="assignment-dialog-heading">处理未保存的负责关系</h2><p>还有未保存的负责人员或修改原因。</p><div class="form-actions"><button class="button compact" @click="finish(false)">继续编辑</button><button class="button primary compact" @click="finish(true)">放弃修改并继续</button></div></dialog>
    <div class="page-heading-row"><div><span class="portal-context">学生事务配置</span><h1>辅导员负责班级</h1><p class="muted">按行政班配置请假处理人员；岗位身份在账号与组织管理中维护。</p></div><button class="button compact" :disabled="loading || busy" @click="refresh"><RefreshCw :size="15" />刷新关系</button></div><p v-if="error" class="alert" role="alert">{{ error }}</p><p v-if="success" class="success-message" role="status">{{ success }}</p>
    <section class="portal-panel leave-list"><div class="section-heading"><h2><UsersRound :size="17" />行政班负责关系</h2></div><p v-if="loading" role="status">正在读取班级与辅导员…</p><p v-else-if="!catalog?.assignments.length" class="empty-state">还没有行政班，请先在账号与组织管理中建档。</p><div v-if="catalog" class="table-scroll"><table class="campus-table foundation-table"><thead><tr><th>行政班</th><th>学院</th><th>负责辅导员</th><th>版本</th><th>操作</th></tr></thead><tbody><tr v-for="item in catalog.assignments" :key="item.classGroupId"><td>{{ item.className }}</td><td>{{ item.departmentName }}</td><td>{{ item.counselorName ?? '未分配，学生暂不能提交' }}</td><td>V{{ item.version }}</td><td><button class="text-button" :disabled="busy" @click="edit(item)">维护负责关系</button></td></tr></tbody></table></div></section>
    <section v-if="selected" class="portal-panel leave-detail"><h2 id="assignment-editor-heading">{{ selected.className }}</h2><p class="muted">{{ selected.departmentName }}。移交后旧人员立即失去申请及材料访问权；有未完成申请时不能清空负责关系。</p><form class="editor-form" @submit.prevent="save"><label>负责辅导员<select v-model="counselorId" :disabled="busy"><option value="">暂不分配</option><option v-for="person in catalog?.counselors.filter(p => p.departmentName === selected?.departmentName)" :key="person.id" :value="String(person.id)">{{ person.name }} · {{ person.departmentName }}</option></select></label><p class="muted">候选人员须为本学院启用账号，同时具备教师与辅导员身份。</p><label>分配或移交原因<textarea v-model="reason" required maxlength="200" rows="2" :disabled="busy" /></label><div class="form-actions"><button class="button primary compact" :disabled="busy || counselorId === String(selected.counselorId ?? '')">保存负责关系</button><button class="button compact" type="button" :disabled="busy" @click="refresh">取消编辑</button></div></form></section>
    <p class="data-note">管理员只配置负责关系，不自动获得学生请假原因、联系方式和材料。分配记录保存在账号与组织管理的行政班修改记录中。</p>
  </div>
</template>
