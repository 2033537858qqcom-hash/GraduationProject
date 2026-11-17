<script setup lang="ts">
import { useLeaveGuard } from '../composables/useEditProtection';
import { computed, onMounted, ref } from 'vue';
import { Building2, Plus, RefreshCw } from '@lucide/vue';
import { api, errorMessage } from '../lib/api';
import BaseSelect from '../components/BaseSelect.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import '../styles/repair-workflow.css';
import '../styles/repair-settings.css';
import {
  housingBedStatus,
  type DormBed,
  type DormBuilding,
  type DormRoom,
  type DormScope,
  type HousingAssignment,
  type HousingCatalog,
  type HousingPage,
} from '../housing';
const catalog = ref<HousingCatalog | null>(null);
const tab = ref<'BUILDING' | 'ROOM' | 'BED' | 'SCOPE' | 'STAY'>('BUILDING');
const buildings = ref<DormBuilding[]>([]);
const rooms = ref<DormRoom[]>([]);
const beds = ref<DormBed[]>([]);
const scopes = ref<DormScope[]>([]);
const stays = ref<HousingAssignment[]>([]);
const buildingId = ref('');
const roomId = ref('');
const query = ref('');
const loading = ref(true);
const busy = ref(false);
const error = ref('');
const success = ref('');
const buildingForm = ref({ campusId: '', code: '', name: '', provenance: 'DEMO', reason: '' });
const roomForm = ref({ code: '', name: '', floorLabel: '', reason: '' });
const bedForm = ref({ code: '', label: '', reason: '' });
const scopeForm = ref({ userId: '', buildingId: '', reason: '' });
const stateTarget = ref<{
  kind: 'buildings' | 'rooms' | 'beds' | 'scopes';
  id: number;
  enabled: boolean;
  version: number;
  name: string;
} | null>(null);
const stateReason = ref('');
const stateDialog = ref<HTMLDialogElement | null>(null);
const createOpen = ref<Record<string, boolean>>({});
const maintainTarget = ref<DormBed | null>(null);
const maintainReason = ref('');
const tabs = [
  { id: 'BUILDING', name: '宿舍楼' },
  { id: 'ROOM', name: '房间' },
  { id: 'BED', name: '床位' },
  { id: 'SCOPE', name: '楼栋负责人' },
  { id: 'STAY', name: '住宿记录' },
] as const;
const roomChoices = computed(() => rooms.value);
const campusOptions = computed(() => [
  { value: '', label: '请选择校区' },
  ...(catalog.value?.campuses.map(c => ({ value: String(c.id), label: c.name })) ?? []),
]);
const provenanceOptions = [
  { value: 'DEMO', label: '演示数据' },
  { value: 'CONFIGURED', label: '正式配置' },
];
const buildingRoomOptions = computed(() =>
  buildings.value.map(item => ({ value: String(item.id), label: `${item.campusName} · ${item.name}` })),
);
const buildingSimpleOptions = computed(() =>
  buildings.value.map(item => ({ value: String(item.id), label: item.name })),
);
const roomOptions = computed(() =>
  rooms.value.map(item => ({ value: String(item.id), label: `${item.floorLabel} · ${item.name}` })),
);
const scopeManagerOptions = computed(() => [
  { value: '', label: '选择已授予宿管岗位的人员' },
  ...(catalog.value?.managers.map(person => ({
    value: String(person.id),
    label: `${person.name}${person.enabled ? '' : '（已停用）'}`,
  })) ?? []),
]);
const scopeBuildingOptions = computed(() => [
  { value: '', label: '选择宿舍楼' },
  ...(catalog.value?.buildings.map(item => ({
    value: String(item.id),
    label: `${item.name}${item.enabled ? '' : '（已停用）'}`,
  })) ?? []),
]);
async function loadBuildings() {
  const page = await api<HousingPage<DormBuilding>>(
    '/admin/housing/buildings?pageSize=100&q=' + encodeURIComponent(query.value),
  );
  buildings.value = page.items;
  if (!buildingId.value && page.items[0]) buildingId.value = String(page.items[0].id);
}
async function loadRooms() {
  if (!buildingId.value) {
    rooms.value = [];
    return;
  }
  const page = await api<HousingPage<DormRoom>>(
    '/admin/housing/rooms?buildingId=' + buildingId.value + '&pageSize=100',
  );
  rooms.value = page.items;
  if (!roomChoices.value.some(room => room.id === Number(roomId.value)))
    roomId.value = page.items[0] ? String(page.items[0].id) : '';
}
async function loadBeds() {
  if (!roomId.value) {
    beds.value = [];
    return;
  }
  beds.value = (await api<HousingPage<DormBed>>('/admin/housing/beds?roomId=' + roomId.value + '&pageSize=100')).items;
}
async function loadScopes() {
  scopes.value = (await api<HousingPage<DormScope>>('/admin/housing/scopes?pageSize=100')).items;
}
async function loadStays() {
  if (!buildingId.value) {
    stays.value = [];
    return;
  }
  stays.value = (
    await api<HousingPage<HousingAssignment>>(
      '/admin/housing/assignments?buildingId=' + buildingId.value + '&pageSize=50',
    )
  ).items;
}
async function refresh() {
  loading.value = true;
  error.value = '';
  try {
    catalog.value = await api<HousingCatalog>('/admin/housing/catalog');
    await loadBuildings();
    await loadRooms();
    await Promise.all([loadBeds(), loadScopes(), loadStays()]);
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
function openState(target: NonNullable<typeof stateTarget.value>) {
  maintainTarget.value = null;
  maintainReason.value = '';
  stateTarget.value = target;
  stateReason.value = '';
  error.value = '';
}
function closeState() {
  stateDialog.value?.close();
  stateTarget.value = null;
  stateReason.value = '';
}
function openMaintain(bed: DormBed) {
  stateTarget.value = null;
  stateReason.value = '';
  maintainTarget.value = bed;
  maintainReason.value = '';
  error.value = '';
}
const unsaved = computed(
  () =>
    Object.entries(buildingForm.value).some(([key, value]) =>
      key === 'provenance' ? value !== 'DEMO' : Boolean(value),
    ) ||
    Object.values(roomForm.value).some(Boolean) ||
    Object.values(bedForm.value).some(Boolean) ||
    Object.values(scopeForm.value).some(Boolean) ||
    Boolean(stateTarget.value && stateReason.value.trim()),
);
useLeaveGuard(busy, unsaved, '还有未保存的宿舍资源输入（楼栋、房间、床位、负责人或调整原因），确定离开并放弃？');
async function saveState() {
  if (!stateTarget.value) return;
  busy.value = true;
  error.value = '';
  try {
    await api('/admin/housing/' + stateTarget.value.kind + '/' + stateTarget.value.id + '/state', {
      method: 'POST',
      body: JSON.stringify({
        enabled: !stateTarget.value.enabled,
        version: stateTarget.value.version,
        reason: stateReason.value.trim(),
      }),
    });
    closeState();
    success.value = '状态已更新。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function createBuilding() {
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api('/admin/housing/buildings', {
      method: 'POST',
      body: JSON.stringify({ ...buildingForm.value, campusId: Number(buildingForm.value.campusId), version: 0 }),
    });
    buildingForm.value = { campusId: '', code: '', name: '', provenance: 'DEMO', reason: '' };
    success.value = '宿舍楼已建档。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function createRoom() {
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api('/admin/housing/rooms', {
      method: 'POST',
      body: JSON.stringify({ ...roomForm.value, buildingId: Number(buildingId.value), version: 0 }),
    });
    roomForm.value = { code: '', name: '', floorLabel: '', reason: '' };
    success.value = '房间已建档。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function createBed() {
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api('/admin/housing/beds', {
      method: 'POST',
      body: JSON.stringify({ ...bedForm.value, roomId: Number(roomId.value), version: 0 }),
    });
    bedForm.value = { code: '', label: '', reason: '' };
    success.value = '床位已建档。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function createScope() {
  busy.value = true;
  error.value = '';
  success.value = '';
  try {
    await api('/admin/housing/scopes', {
      method: 'POST',
      body: JSON.stringify({
        userId: Number(scopeForm.value.userId),
        buildingId: Number(scopeForm.value.buildingId),
        version: 0,
        reason: scopeForm.value.reason,
      }),
    });
    scopeForm.value = { userId: '', buildingId: '', reason: '' };
    success.value = '楼栋负责人已配置。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function maintain(bed: DormBed) {
  const reason = maintainReason.value.trim();
  if (!reason) return;
  busy.value = true;
  error.value = '';
  try {
    await api('/admin/housing/beds/' + bed.id + '/maintenance', {
      method: 'POST',
      body: JSON.stringify({
        status: bed.status === 'MAINTENANCE' ? 'VACANT' : 'MAINTENANCE',
        version: bed.version,
        reason,
      }),
    });
    maintainTarget.value = null;
    maintainReason.value = '';
    success.value = '床位状态已更新。';
    await refresh();
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
async function changeBuilding() {
  await loadRooms();
  await loadBeds();
  await loadStays();
}
onMounted(refresh);
</script>
<template>
  <div class="repair-settings housing-page">
    <ServicePageHeader
      context="宿舍资源配置"
      title="宿舍楼、房间与床位"
      description="维护住宿资源并指定楼栋负责人。已有记录不能删除；有人入住时不能停用。"
    >
      <template #actions>
        <button class="button compact" :disabled="loading || busy" @click="refresh">
          <RefreshCw :size="15" />
          刷新配置
        </button>
      </template>
    </ServicePageHeader>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <p v-if="loading" role="status">正在读取宿舍资源…</p>
    <div class="repair-tabs" role="tablist">
      <button
        v-for="item in tabs"
        :key="item.id"
        class="button compact"
        :class="{ primary: tab === item.id }"
        type="button"
        @click="tab = item.id"
      >
        {{ item.name }}
      </button>
      <button
        v-if="tab !== 'STAY'"
        type="button"
        class="button compact repair-add"
        :disabled="busy || loading"
        @click="createOpen[tab] = !createOpen[tab]"
      >
        <Plus :size="15" />
        {{
          createOpen[tab]
            ? '收起新建表单'
            : tab === 'BUILDING'
              ? '新建宿舍楼'
              : tab === 'ROOM'
                ? '新建房间'
                : tab === 'BED'
                  ? '新建床位'
                  : '指定楼栋负责人'
        }}
      </button>
    </div>
    <section v-if="tab === 'BUILDING'">
      <form v-if="createOpen.BUILDING" class="portal-panel repair-editor" @submit.prevent="createBuilding">
        <div class="section-heading">
          <h2>
            <Plus :size="18" />
            新建宿舍楼
          </h2>
          <button type="button" class="button compact" :disabled="busy" @click="createOpen.BUILDING = false">
            收起 ×
          </button>
        </div>
        <div class="repair-editor-form">
          <label>
            校区
            <BaseSelect v-model="buildingForm.campusId" :options="campusOptions" :disabled="busy" />
          </label>
          <label>
            楼栋编号
            <input
              v-model="buildingForm.code"
              required
              maxlength="64"
              pattern="[A-Za-z0-9_\-]+"
              :disabled="busy"
              placeholder="例如 DORM-EAST-1"
            />
          </label>
          <label>
            楼栋名称
            <input v-model="buildingForm.name" required maxlength="100" :disabled="busy" />
          </label>
          <label>
            资料标记
            <BaseSelect v-model="buildingForm.provenance" :options="provenanceOptions" :disabled="busy" />
          </label>
          <label class="repair-form-wide">
            配置原因
            <textarea v-model="buildingForm.reason" required maxlength="200" rows="2" :disabled="busy" />
          </label>
          <div class="form-actions repair-form-wide">
            <button class="button primary compact" :disabled="busy">保存楼栋</button>
          </div>
        </div>
      </form>
      <label style="display: block; max-width: 340px; margin-bottom: 14px">
        查找楼栋
        <input v-model="query" maxlength="100" placeholder="名称或编号" @change="loadBuildings" />
      </label>
      <div class="repair-card-grid">
        <article
          v-for="item in buildings"
          :key="item.id"
          class="portal-panel repair-config-card"
          :class="{ 'is-selected': stateTarget?.kind === 'buildings' && stateTarget.id === item.id }"
        >
          <div class="section-heading">
            <h2>
              <Building2 :size="17" />
              {{ item.name }}
            </h2>
            <span class="repair-status" :class="{ inactive: !item.enabled }">{{ item.enabled ? '启用' : '停用' }}</span>
          </div>
          <p>{{ item.campusName }} · {{ item.code }}</p>
          <p class="muted">
            房间 {{ item.roomCount }} · 在住床位 {{ item.occupiedCount }} ·
            {{ item.provenance === 'DEMO' ? '演示数据' : '正式配置' }}
          </p>
          <div class="repair-card-actions">
            <button
              class="text-button"
              type="button"
              :disabled="busy"
              @click="
                stateTarget?.kind === 'buildings' && stateTarget.id === item.id
                  ? closeState()
                  : openState({
                      kind: 'buildings',
                      id: item.id,
                      enabled: item.enabled,
                      version: item.version,
                      name: item.name,
                    })
              "
            >
              {{
                stateTarget?.kind === 'buildings' && stateTarget.id === item.id
                  ? '收起操作 ▴'
                  : item.enabled
                    ? '停用楼栋 ▾'
                    : '启用楼栋 ▾'
              }}
            </button>
          </div>
          <form
            v-if="stateTarget?.kind === 'buildings' && stateTarget.id === item.id"
            class="editor-form"
            style="margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--color-border)"
            @submit.prevent="saveState"
          >
            <label>
              {{ stateTarget.enabled ? '停用' : '启用' }}原因
              <textarea v-model="stateReason" required maxlength="200" rows="2" :disabled="busy" />
            </label>
            <div class="form-actions">
              <button class="button primary compact" :disabled="busy">确认</button>
              <button class="button compact" type="button" :disabled="busy" @click="closeState()">取消</button>
            </div>
          </form>
        </article>
      </div>
    </section>
    <section v-else-if="tab === 'ROOM'">
      <label style="display: block; max-width: 340px; margin-bottom: 14px">
        所属楼栋
        <BaseSelect v-model="buildingId" :options="buildingRoomOptions" :disabled="busy" @change="changeBuilding" />
      </label>
      <form v-if="createOpen.ROOM" class="portal-panel repair-editor" @submit.prevent="createRoom">
        <div class="section-heading">
          <h2>新建房间</h2>
          <button type="button" class="button compact" :disabled="busy" @click="createOpen.ROOM = false">收起 ×</button>
        </div>
        <div class="repair-editor-form">
          <label>
            房间编号
            <input
              v-model="roomForm.code"
              required
              maxlength="64"
              pattern="[A-Za-z0-9_\-]+"
              :disabled="busy || !buildingId"
            />
          </label>
          <label>
            房间名称
            <input v-model="roomForm.name" required maxlength="100" :disabled="busy || !buildingId" />
          </label>
          <label>
            楼层
            <input
              v-model="roomForm.floorLabel"
              required
              maxlength="20"
              :disabled="busy || !buildingId"
              placeholder="例如 3 层"
            />
          </label>
          <label class="repair-form-wide">
            配置原因
            <textarea v-model="roomForm.reason" required maxlength="200" rows="2" :disabled="busy" />
          </label>
          <div class="form-actions repair-form-wide">
            <button class="button primary compact" :disabled="busy || !buildingId">保存房间</button>
          </div>
        </div>
      </form>
      <div class="repair-card-grid">
        <article
          v-for="item in rooms"
          :key="item.id"
          class="portal-panel repair-config-card"
          :class="{ 'is-selected': stateTarget?.kind === 'rooms' && stateTarget.id === item.id }"
        >
          <div class="section-heading">
            <h2>{{ item.name }}</h2>
            <span class="repair-status" :class="{ inactive: !item.enabled }">{{ item.enabled ? '启用' : '停用' }}</span>
          </div>
          <p>{{ item.floorLabel }} · {{ item.code }}</p>
          <p class="muted">床位 {{ item.bedCount }} · 在住 {{ item.occupiedCount }}</p>
          <div class="repair-card-actions">
            <button
              class="text-button"
              type="button"
              :disabled="busy"
              @click="
                stateTarget?.kind === 'rooms' && stateTarget.id === item.id
                  ? closeState()
                  : openState({
                      kind: 'rooms',
                      id: item.id,
                      enabled: item.enabled,
                      version: item.version,
                      name: item.name,
                    })
              "
            >
              {{
                stateTarget?.kind === 'rooms' && stateTarget.id === item.id
                  ? '收起操作 ▴'
                  : item.enabled
                    ? '停用房间 ▾'
                    : '启用房间 ▾'
              }}
            </button>
          </div>
          <form
            v-if="stateTarget?.kind === 'rooms' && stateTarget.id === item.id"
            class="editor-form"
            style="margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--color-border)"
            @submit.prevent="saveState"
          >
            <label>
              {{ stateTarget.enabled ? '停用' : '启用' }}原因
              <textarea v-model="stateReason" required maxlength="200" rows="2" :disabled="busy" />
            </label>
            <div class="form-actions">
              <button class="button primary compact" :disabled="busy">确认</button>
              <button class="button compact" type="button" :disabled="busy" @click="closeState()">取消</button>
            </div>
          </form>
        </article>
      </div>
    </section>
    <section v-else-if="tab === 'BED'">
      <div class="repair-editor-form" style="margin-bottom: 14px">
        <label>
          所属楼栋
          <BaseSelect v-model="buildingId" :options="buildingSimpleOptions" :disabled="busy" @change="changeBuilding" />
        </label>
        <label>
          所属房间
          <BaseSelect v-model="roomId" :options="roomOptions" :disabled="busy" @change="loadBeds" />
        </label>
      </div>
      <form v-if="createOpen.BED" class="portal-panel repair-editor" @submit.prevent="createBed">
        <div class="section-heading">
          <h2>新建床位</h2>
          <button type="button" class="button compact" :disabled="busy" @click="createOpen.BED = false">收起 ×</button>
        </div>
        <div class="repair-editor-form">
          <label>
            床位编号
            <input
              v-model="bedForm.code"
              required
              maxlength="32"
              pattern="[A-Za-z0-9_\-]+"
              :disabled="busy || !roomId"
            />
          </label>
          <label>
            床位名称
            <input
              v-model="bedForm.label"
              required
              maxlength="40"
              :disabled="busy || !roomId"
              placeholder="例如 1 号床"
            />
          </label>
          <label class="repair-form-wide">
            配置原因
            <textarea v-model="bedForm.reason" required maxlength="200" rows="2" :disabled="busy" />
          </label>
          <div class="form-actions repair-form-wide">
            <button class="button primary compact" :disabled="busy || !roomId">保存床位</button>
          </div>
        </div>
      </form>
      <div class="repair-card-grid">
        <article
          v-for="item in beds"
          :key="item.id"
          class="portal-panel repair-config-card"
          :class="{
            'is-selected':
              (stateTarget?.kind === 'beds' && stateTarget.id === item.id) || maintainTarget?.id === item.id,
          }"
        >
          <div class="section-heading">
            <h2>{{ item.label }}</h2>
            <span class="repair-status" :class="{ inactive: item.status !== 'VACANT' || !item.enabled }">
              {{ housingBedStatus[item.status] ?? item.status }}{{ item.enabled ? '' : ' · 停用' }}
            </span>
          </div>
          <p class="muted">
            {{ item.code }}
            <template v-if="item.occupantName">· {{ item.occupantName }} · {{ item.occupantUsername }}</template>
          </p>
          <div class="repair-card-actions">
            <button
              class="text-button"
              type="button"
              :disabled="busy"
              @click="
                stateTarget?.kind === 'beds' && stateTarget.id === item.id
                  ? closeState()
                  : openState({
                      kind: 'beds',
                      id: item.id,
                      enabled: item.enabled,
                      version: item.version,
                      name: item.label,
                    })
              "
            >
              {{
                stateTarget?.kind === 'beds' && stateTarget.id === item.id
                  ? '收起操作 ▴'
                  : item.enabled
                    ? '停用床位 ▾'
                    : '启用床位 ▾'
              }}
            </button>
            <button
              v-if="item.status === 'VACANT' || item.status === 'MAINTENANCE'"
              class="text-button"
              type="button"
              :disabled="busy"
              @click="maintainTarget?.id === item.id ? (maintainTarget = null) : openMaintain(item)"
            >
              {{
                maintainTarget?.id === item.id
                  ? '收起维护 ▴'
                  : item.status === 'MAINTENANCE'
                    ? '恢复为空闲 ▾'
                    : '设为维护 ▾'
              }}
            </button>
          </div>
          <form
            v-if="stateTarget?.kind === 'beds' && stateTarget.id === item.id"
            class="editor-form"
            style="margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--color-border)"
            @submit.prevent="saveState"
          >
            <label>
              {{ stateTarget.enabled ? '停用' : '启用' }}原因
              <textarea v-model="stateReason" required maxlength="200" rows="2" :disabled="busy" />
            </label>
            <div class="form-actions">
              <button class="button primary compact" :disabled="busy">确认</button>
              <button class="button compact" type="button" :disabled="busy" @click="closeState()">取消</button>
            </div>
          </form>
          <form
            v-if="maintainTarget?.id === item.id"
            class="editor-form"
            style="margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--color-border)"
            @submit.prevent="maintain(item)"
          >
            <label>
              {{ item.status === 'MAINTENANCE' ? '恢复为空闲的原因' : '设为维护的原因' }}
              <input
                v-model="maintainReason"
                required
                maxlength="200"
                :disabled="busy"
                placeholder="请说明维护或恢复原因"
              />
            </label>
            <div class="form-actions">
              <button class="button primary compact" :disabled="busy">确认更新状态</button>
              <button class="button compact" type="button" :disabled="busy" @click="maintainTarget = null">取消</button>
            </div>
          </form>
        </article>
      </div>
    </section>
    <section v-else-if="tab === 'SCOPE'">
      <form v-if="createOpen.SCOPE" class="portal-panel repair-editor" @submit.prevent="createScope">
        <div class="section-heading">
          <h2>指定楼栋负责人</h2>
          <button type="button" class="button compact" :disabled="busy" @click="createOpen.SCOPE = false">
            收起 ×
          </button>
        </div>
        <div class="repair-editor-form">
          <label>
            宿管
            <BaseSelect v-model="scopeForm.userId" :options="scopeManagerOptions" :disabled="busy" />
          </label>
          <label>
            宿舍楼
            <BaseSelect v-model="scopeForm.buildingId" :options="scopeBuildingOptions" :disabled="busy" />
          </label>
          <label class="repair-form-wide">
            配置原因
            <textarea v-model="scopeForm.reason" required maxlength="200" rows="2" :disabled="busy" />
          </label>
          <div class="form-actions repair-form-wide">
            <button class="button primary compact" :disabled="busy">保存负责人</button>
          </div>
        </div>
      </form>
      <div class="repair-card-grid">
        <article
          v-for="item in scopes"
          :key="item.id"
          class="portal-panel repair-config-card"
          :class="{ 'is-selected': stateTarget?.kind === 'scopes' && stateTarget.id === item.id }"
        >
          <div class="section-heading">
            <h2>{{ item.userName }}</h2>
            <span class="repair-status" :class="{ inactive: !item.effective }">{{ item.effectivenessMessage }}</span>
          </div>
          <p>{{ item.campusName }} · {{ item.buildingName }}</p>
          <div class="repair-card-actions">
            <button
              class="text-button"
              type="button"
              :disabled="busy"
              @click="
                stateTarget?.kind === 'scopes' && stateTarget.id === item.id
                  ? closeState()
                  : openState({
                      kind: 'scopes',
                      id: item.id,
                      enabled: item.enabled,
                      version: item.version,
                      name: item.userName,
                    })
              "
            >
              {{
                stateTarget?.kind === 'scopes' && stateTarget.id === item.id
                  ? '收起操作 ▴'
                  : item.enabled
                    ? '停用负责范围 ▾'
                    : '启用负责范围 ▾'
              }}
            </button>
          </div>
          <form
            v-if="stateTarget?.kind === 'scopes' && stateTarget.id === item.id"
            class="editor-form"
            style="margin-top: 12px; padding-top: 12px; border-top: 1px dashed var(--color-border)"
            @submit.prevent="saveState"
          >
            <label>
              {{ stateTarget.enabled ? '停用' : '启用' }}原因
              <textarea v-model="stateReason" required maxlength="200" rows="2" :disabled="busy" />
            </label>
            <div class="form-actions">
              <button class="button primary compact" :disabled="busy">确认</button>
              <button class="button compact" type="button" :disabled="busy" @click="closeState()">取消</button>
            </div>
          </form>
        </article>
      </div>
    </section>
    <section v-else>
      <label>
        宿舍楼
        <BaseSelect v-model="buildingId" :options="buildingSimpleOptions" @change="loadStays" />
      </label>
      <p v-if="!stays.length" class="empty-state">这座楼还没有住宿记录。</p>
      <ul v-else class="repair-history-list">
        <li v-for="item in stays" :key="item.id">
          <strong>{{ item.studentName }} · {{ item.studentUsername }}</strong>
          <p>
            {{ item.buildingName }} · {{ item.roomName }} · {{ item.bedLabel }} ·
            {{ item.status === 'CURRENT' ? '在住' : '已退宿' }}
          </p>
          <small>{{ item.endReason }}</small>
        </li>
      </ul>
    </section>
    <dialog ref="stateDialog" class="foundation-dialog" @cancel.prevent="closeState()">
      <h2>{{ stateTarget?.enabled ? '停用' : '启用' }}{{ stateTarget?.name }}</h2>
      <form class="editor-form" @submit.prevent="saveState">
        <label>
          调整原因
          <textarea v-model="stateReason" required maxlength="200" rows="3" :disabled="busy" />
        </label>
        <div class="form-actions">
          <button class="button primary compact" :disabled="busy">确认</button>
          <button class="button compact" type="button" @click="closeState()">取消</button>
        </div>
      </form>
    </dialog>
  </div>
</template>
