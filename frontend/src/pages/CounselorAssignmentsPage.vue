<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RefreshCw, UsersRound } from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import { useLeaveGuard } from '../composables/useEditProtection';
import BaseSelect from '../components/BaseSelect.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import type { AssignmentCatalog, CounselorAssignment } from '../leave';
const catalog = ref<AssignmentCatalog | null>(null),
  selected = ref<CounselorAssignment | null>(null);
const counselorId = ref(''),
  reason = ref(''),
  busy = ref(false),
  loading = ref(true),
  error = ref(''),
  success = ref('');
const counselorOptions = computed(() => [
  { value: '', label: '暂不分配' },
  ...(catalog.value?.counselors
    .filter(p => p.departmentName === selected.value?.departmentName)
    .map(person => ({ value: String(person.id), label: `${person.name} · ${person.departmentName}` })) ?? []),
]);
const dirty = computed(
  () => !!selected.value && (counselorId.value !== String(selected.value.counselorId ?? '') || !!reason.value.trim()),
);
const { allowLeave } = useLeaveGuard(busy, dirty, '还有未保存的负责人员或修改原因，确定放弃修改并继续？', {
  title: '处理未保存的负责关系',
  routeUpdate: true,
  requireAuth: true,
  onBusy: () => {
    error.value = '正在保存，请稍后离开。';
  },
});
async function load() {
  loading.value = true;
  error.value = '';
  try {
    catalog.value = await api<AssignmentCatalog>('/admin/counselors');
    selected.value = null;
    reason.value = '';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
async function edit(item: CounselorAssignment) {
  if (!(await allowLeave())) return;
  selected.value = item;
  counselorId.value = String(item.counselorId ?? '');
  reason.value = '';
  success.value = '';
}
async function closeEditor() {
  if (await allowLeave()) {
    selected.value = null;
    reason.value = '';
  }
}
async function refresh() {
  if (await allowLeave()) await load();
}
async function save() {
  if (!selected.value) return;
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api('/admin/counselors/' + selected.value.classGroupId, {
      method: 'PUT',
      body: JSON.stringify({
        counselorId: counselorId.value ? Number(counselorId.value) : null,
        version: selected.value.version,
        reason: reason.value,
      }),
    });
    selected.value = null;
    reason.value = '';
    await load();
    success.value = '负责关系已保存，待办与材料权限立即按新关系生效。';
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div>
    <ServicePageHeader
      context="学生事务配置"
      title="辅导员负责班级"
      description="按行政班配置请假处理人员；岗位身份在账号与组织管理中维护。"
    >
      <template #actions>
        <button class="button compact" :disabled="loading || busy" @click="refresh">
          <RefreshCw :size="15" />
          刷新关系
        </button>
      </template>
    </ServicePageHeader>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <section class="portal-panel leave-list">
      <div class="section-heading">
        <h2>
          <UsersRound :size="17" />
          行政班负责关系
        </h2>
      </div>
      <p v-if="loading" role="status">正在读取班级与辅导员…</p>
      <p v-else-if="!catalog?.assignments.length" class="empty-state">还没有行政班，请先在账号与组织管理中建档。</p>
      <div v-if="catalog" class="table-scroll">
        <table class="campus-table foundation-table">
          <thead>
            <tr>
              <th>行政班</th>
              <th>学院</th>
              <th>负责辅导员</th>
              <th>版本</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="item in catalog.assignments" :key="item.classGroupId">
              <tr :class="{ 'resource-selected': selected?.classGroupId === item.classGroupId }">
                <td>{{ item.className }}</td>
                <td>{{ item.departmentName }}</td>
                <td>{{ item.counselorName ?? '未分配，学生暂不能提交' }}</td>
                <td>V{{ item.version }}</td>
                <td>
                  <button
                    class="text-button"
                    :disabled="busy"
                    @click="selected?.classGroupId === item.classGroupId ? closeEditor() : edit(item)"
                  >
                    {{ selected?.classGroupId === item.classGroupId ? '收起维护 ▴' : '维护负责关系 ▾' }}
                  </button>
                </td>
              </tr>
              <tr v-if="selected && selected.classGroupId === item.classGroupId" class="table-folio-row">
                <td colspan="5" class="table-folio-cell">
                  <section class="portal-panel leave-detail service-inline-folio">
                    <div class="section-heading">
                      <div>
                        <h2 id="assignment-editor-heading">{{ selected.className }} · 负责辅导员配置</h2>
                        <p class="muted">
                          {{
                            selected.departmentName
                          }}。移交后旧人员立即失去申请及材料访问权；有未完成申请时不能清空负责关系。
                        </p>
                      </div>
                      <button class="button compact" type="button" :disabled="busy" @click="closeEditor">收起 ×</button>
                    </div>
                    <form class="editor-form" @submit.prevent="save">
                      <label>
                        负责辅导员
                        <BaseSelect v-model="counselorId" :options="counselorOptions" :disabled="busy" />
                      </label>
                      <p class="muted">候选人员须为本学院启用账号，同时具备教师与辅导员身份。</p>
                      <label>
                        <div class="label-heading-row">
                          <span>分配或移交原因</span>
                          <small class="muted">必填 · 最多 200 字</small>
                        </div>
                        <textarea
                          v-model="reason"
                          required
                          maxlength="200"
                          rows="3"
                          :disabled="busy"
                          placeholder="说明负责关系调整或移交的具体原因"
                        />
                      </label>
                      <div class="form-actions">
                        <button
                          class="button primary compact"
                          :disabled="busy || counselorId === String(selected.counselorId ?? '')"
                        >
                          保存负责关系
                        </button>
                        <button class="button compact" type="button" :disabled="busy" @click="closeEditor">
                          取消编辑
                        </button>
                      </div>
                    </form>
                  </section>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </section>
    <p class="data-note">
      管理员只配置负责关系，不自动获得学生请假原因、联系方式和材料。分配记录保存在账号与组织管理的行政班修改记录中。
    </p>
  </div>
</template>
