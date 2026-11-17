<script setup lang="ts">
import RepairProgress from '../components/repair/RepairProgress.vue';
import RepairRequestList from '../components/repair/RepairRequestList.vue';
import ServicePageHeader from '../components/ServicePageHeader.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import DownloadLink from '../components/DownloadLink.vue';
import ServiceJourney from '../components/ServiceJourney.vue';
import '../styles/repair-workflow.css';
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  Award,
  Camera,
  CheckCircle2,
  ClipboardCheck,
  Clock,
  FileText,
  History,
  MapPin,
  Plus,
  RefreshCw,
  SlidersHorizontal,
  User,
  UserCheck,
  Wrench,
  X,
  ZoomIn,
} from '@lucide/vue';
import { cancellationStatuses, repairActions, repairCategories, repairSnapshot, repairStatuses } from '../repairs';
import BaseSelect from '../components/BaseSelect.vue';
import ContactTimeSelect from '../components/ContactTimeSelect.vue';
import { useRepairWorkspace } from '../composables/useRepairWorkspace';

const {
  alertState,
  busy,
  campusId,
  campusOptions,
  can,
  cancellationFilter,
  cancellationOptions,
  categoryOptions,
  changeCampus,
  changeCancellationFilter,
  choose,
  clearFile,
  closeAlert,
  closePreview,
  comment,
  confirmation,
  currentFaultImageCount,
  currentTask,
  edit,
  editing,
  error,
  existingFaultImages,
  faultImages,
  file,
  filterQueue,
  filtersOpen,
  finish,
  form,
  handleCurrentTask,
  historyActorRole,
  historyView,
  imageUrl,
  intro,
  isClosed,
  list,
  loading,
  locationOptions,
  locations,
  maintenanceImages,
  mobileDetailOpen,
  mode,
  navigate,
  newDraft,
  onSelectFormFiles,
  operationActions,
  page,
  pendingFaultFiles,
  previewImage,
  previewModalUrl,
  primaryAction,
  priority,
  priorityOptions,
  purpose,
  purposeOptions,
  queueItems,
  quickFilters,
  records,
  refresh,
  removable,
  removeImage,
  removePendingFile,
  repairIndicatorStyle,
  route,
  save,
  search,
  selectFile,
  selected,
  selectedLocationHint,
  showList,
  starOptions,
  stars,
  status,
  statusOptions,
  stepTime,
  stepperOverview,
  success,
  technicianId,
  technicianOptions,
  technicians,
  time,
  title,
  transition,
  upload,
  workflowSteps,
} = useRepairWorkspace();
</script>

<template>
  <div
    class="repair-workflow service-workflow service-repair"
    :class="{ 'is-detail-open': mobileDetailOpen, 'is-editing': editing }"
  >
    <ServicePageHeader context="校园设施服务" :title="title" :description="intro">
      <template #actions>
        <div class="repair-inline-actions">
          <RouterLink v-if="mode === 'dispatch'" class="button compact" to="/logistics/repairs/statistics">
            报修统计
          </RouterLink>
          <button class="button compact repair-refresh" :disabled="busy || loading" @click="refresh">
            <RefreshCw :size="15" :class="{ 'is-refreshing': loading }" />
            刷新
          </button>
          <button v-if="mode === 'mine' && !editing" class="button primary compact" :disabled="busy" @click="newDraft">
            <Plus :size="15" />
            新建报修
          </button>
        </div>
      </template>
    </ServicePageHeader>
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>
    <div v-if="queueItems.length && !editing" class="repair-queue-note">
      <ClipboardCheck :size="16" />
      <span>待办提醒</span>
      <button
        v-for="item in queueItems"
        :key="item.label"
        :disabled="busy || loading"
        @click="filterQueue(item.status, item.cancellation)"
      >
        {{ item.label }}
        <strong>{{ item.count }}</strong>
        <ArrowRight :size="13" />
      </button>
    </div>
    <div v-if="!editing" class="repair-queue-toolbar">
      <nav class="repair-queue-tabs" aria-label="工单快捷筛选">
        <button
          v-for="filter in quickFilters"
          :key="filter.value"
          :aria-current="status === filter.value && !cancellationFilter ? 'true' : undefined"
          :class="{ selected: status === filter.value && !cancellationFilter }"
          :disabled="busy || loading"
          @click="filterQueue(filter.value)"
        >
          {{ filter.label }}
        </button>
      </nav>
      <button
        class="repair-filter-toggle"
        :class="{
          active:
            filtersOpen || cancellationFilter || (status && !quickFilters.some(filter => filter.value === status)),
        }"
        :aria-expanded="filtersOpen"
        aria-controls="repair-advanced-filters"
        :disabled="busy || loading"
        @click="filtersOpen = !filtersOpen"
      >
        <SlidersHorizontal :size="15" />
        更多筛选
        <span
          v-if="cancellationFilter || (status && !quickFilters.some(filter => filter.value === status))"
          class="repair-filter-dot"
          aria-label="有筛选条件"
        />
      </button>
    </div>
    <div v-if="filtersOpen && !editing" id="repair-advanced-filters" class="repair-list-filter">
      <label>
        工单状态
        <BaseSelect v-model="status" :options="statusOptions" :disabled="busy || loading" compact @change="search" />
      </label>
      <label>
        取消申请
        <BaseSelect
          v-model="cancellationFilter"
          :options="cancellationOptions"
          :disabled="busy || loading"
          compact
          @change="changeCancellationFilter"
        />
      </label>
      <button class="text-button" :disabled="busy || loading" @click="filterQueue('')">清除筛选</button>
    </div>
    <div class="repair-workflow-grid">
      <RepairRequestList
        :list="list"
        :selected-id="selected?.id"
        :mode="mode"
        :busy="busy"
        :loading="loading"
        :step-time="stepTime"
        @choose="choose"
        @page="page"
        @reset-filter="filterQueue('')"
      />
      <section class="portal-panel repair-request-detail" aria-label="报修资料与处理" :aria-busy="loading">
        <button v-if="!editing" class="text-button repair-mobile-back" :disabled="busy" @click="showList">
          <ArrowLeft :size="16" />
          工单列表
        </button>
        <form v-if="editing" class="repair-workflow-form" @submit.prevent="save">
          <ServiceJourney kind="REPAIR" :status="selected?.status" preparing />
          <div class="section-heading">
            <h2>{{ selected ? '编辑报修资料' : '新建校园报修' }}</h2>
            <button type="button" class="text-button" :disabled="busy" @click="showList">
              <ArrowLeft :size="14" />
              工单列表
            </button>
          </div>
          <p class="muted">先保存资料，核对后再提交。提交后地点与类别固定，联系方式仅对相关经办人员可见。</p>
          <fieldset :disabled="busy">
            <div class="repair-form-group-heading">
              <span>01</span>
              <h3>报修地点</h3>
            </div>
            <div class="form-grid">
              <label>
                所在校区
                <BaseSelect
                  v-model="campusId"
                  :options="campusOptions"
                  :disabled="!!selected?.submittedAt"
                  @change="changeCampus"
                />
              </label>
              <label>
                报修地点
                <BaseSelect v-model="form.locationId" :options="locationOptions" :disabled="!!selected?.submittedAt" />
              </label>
            </div>
            <p v-if="selectedLocationHint" class="data-note">地点指引：{{ selectedLocationHint }}</p>
            <p v-else-if="!loading && !locations.total" class="data-note">
              当前校区还没有开放地点，可先保存故障描述，联系管理员配置后再提交。
            </p>
            <label>
              具体位置补充
              <input
                v-model="form.locationDetail"
                maxlength="300"
                :placeholder="
                  selectedLocationHint ? `例如：${selectedLocationHint}` : '例如：三楼 302 教室，进门右侧第二盏灯'
                "
              />
            </label>
            <div class="repair-form-group-heading">
              <span>02</span>
              <h3>故障描述与照片</h3>
            </div>
            <div class="form-grid">
              <label>
                故障类别
                <BaseSelect v-model="form.category" :options="categoryOptions" :disabled="!!selected?.submittedAt" />
              </label>
              <label>
                故障标题
                <input v-model="form.title" maxlength="100" placeholder="例如：教室右侧照明灯不亮" />
              </label>
            </div>
            <label>
              <span class="label-heading-row">
                <span>故障现象与影响</span>
                <small class="muted">最多 2000 字</small>
              </span>
              <textarea
                v-model="form.description"
                maxlength="2000"
                rows="4"
                placeholder="描述故障现象、出现时间及影响，方便维修人员提前准备"
              />
            </label>
            <div class="repair-form-images-section">
              <div class="label-heading-row">
                <span>故障现场图片</span>
                <small class="muted">{{ currentFaultImageCount }} / 6 张（单张 2 MB 以内 JPG/PNG）</small>
              </div>
              <div class="repair-image-thumbnails-grid">
                <div v-for="image in existingFaultImages" :key="image.id" class="repair-thumb-card">
                  <button
                    type="button"
                    class="repair-thumb-open"
                    :aria-label="`查看大图：${image.name}`"
                    @click="previewImage(imageUrl(image))"
                  >
                    <img :src="imageUrl(image)" :alt="image.name" />
                  </button>
                  <button
                    type="button"
                    class="repair-thumb-remove"
                    title="从工单移除此图片"
                    :disabled="busy"
                    @click="removeImage(image)"
                  >
                    <X :size="12" />
                  </button>
                  <span class="repair-thumb-badge">已保存</span>
                </div>
                <div v-for="(item, idx) in pendingFaultFiles" :key="idx" class="repair-thumb-card">
                  <button
                    type="button"
                    class="repair-thumb-open"
                    :aria-label="`查看大图：${item.file.name}`"
                    @click="previewImage(item.preview)"
                  >
                    <img :src="item.preview" :alt="item.file.name" />
                  </button>
                  <button
                    type="button"
                    class="repair-thumb-remove"
                    title="取消添加此图片"
                    :disabled="busy"
                    @click="removePendingFile(idx)"
                  >
                    <X :size="12" />
                  </button>
                  <span class="repair-thumb-badge pending">待上传</span>
                </div>
                <label v-if="currentFaultImageCount < 6" class="repair-upload-picker" :class="{ disabled: busy }">
                  <Camera :size="24" />
                  <span>选择图片</span>
                  <small>支持多选</small>
                  <input
                    type="file"
                    multiple
                    accept="image/jpeg,image/png"
                    :disabled="busy"
                    @change="onSelectFormFiles"
                  />
                </label>
              </div>
              <p class="repair-image-tip">
                提示：添加现场照片有助于维修人员快速判断故障类型与备件需求。所选图片将在保存报修资料时一并上传。
              </p>
            </div>
            <div class="repair-form-group-heading">
              <span>03</span>
              <h3>联系安排</h3>
            </div>
            <div class="form-grid">
              <label>
                联系手机或邮箱
                <input
                  v-model="form.contact"
                  maxlength="80"
                  autocomplete="off"
                  placeholder="填写可联系到你的手机或邮箱"
                />
              </label>
              <label>
                方便联系的时间
                <ContactTimeSelect v-model="form.contactWindow" :disabled="busy" />
              </label>
            </div>
          </fieldset>
          <div class="repair-form-footer">
            <span>保存后可继续补充，核对无误再提交。</span>
            <button class="button primary compact" :disabled="busy" type="submit">
              {{ busy ? '正在保存…' : selected?.submittedAt ? '保存补充资料' : '保存报修草稿' }}
            </button>
          </div>
        </form>
        <template v-else-if="selected">
          <p v-if="!selected.locationEligible" class="data-note" role="status">
            {{ selected.locationEligibilityMessage }}
          </p>
          <div class="repair-detail-heading">
            <div>
              <div class="repair-detail-identity">
                <span>报修 #{{ selected.id }}</span>
                <span>{{ repairCategories[selected.category] || '待分类' }}</span>
              </div>
              <h2 class="repair-detail-title" tabindex="-1">{{ selected.title || '待填写故障标题' }}</h2>
            </div>
            <span class="repair-status" :class="`status-${selected.status.toLowerCase()}`">
              {{ repairStatuses[selected.status] }}
            </span>
          </div>
          <p class="repair-detail-location">
            <MapPin :size="15" />
            <span>
              {{ selected.campusName }} · {{ selected.locationName || '待填写地点' }}
              <strong v-if="selected.locationDetail">{{ selected.locationDetail }}</strong>
            </span>
          </p>
          <RepairProgress
            :closed="isClosed"
            :paused="selected.cancellation?.status === 'PENDING'"
            :overview="stepperOverview"
            :updated-at-text="time(selected.updatedAt)"
            :current-task="currentTask"
            :disabled="busy || loading"
            :steps="workflowSteps"
            @action="handleCurrentTask"
          />
          <nav ref="repairNavRef" class="repair-detail-tabs" aria-label="报修详情栏目">
            <button
              class="text-button"
              :class="{ selected: !historyView }"
              :aria-current="!historyView ? 'true' : undefined"
              :disabled="busy || loading"
              @click="navigate({ ...(route.query as Record<string, string>), tab: 'details' })"
            >
              <FileText :size="15" />
              报修详情
            </button>
            <button
              class="text-button"
              :class="{ selected: historyView }"
              :aria-current="historyView ? 'true' : undefined"
              :disabled="busy || loading"
              @click="navigate({ ...(route.query as Record<string, string>), tab: 'history', historyPage: '1' })"
            >
              <History :size="15" />
              办理记录
            </button>
            <div class="learning-tabs-indicator" :style="repairIndicatorStyle" aria-hidden="true"></div>
          </nav>
          <template v-if="historyView">
            <article v-for="record in records.items" :key="record.id" class="repair-history-card">
              <div class="history-card-top">
                <strong>
                  {{ repairActions[record.action] || record.action }} · {{ repairStatuses[record.status] }}
                </strong>
                <span class="history-role-badge" :class="historyActorRole(record).class">
                  {{ historyActorRole(record).label }} · {{ record.actorName }}
                </span>
              </div>
              <small>{{ time(record.createdAt) }}</small>
              <p v-if="record.comment" class="history-comment-text">{{ record.comment }}</p>
              <details v-if="repairSnapshot(record.snapshot)">
                <summary>查看当时保存的资料与图片</summary>
                <p>{{ repairSnapshot(record.snapshot)?.title }}</p>
                <p class="preserve-lines">{{ repairSnapshot(record.snapshot)?.description }}</p>
                <p>具体位置：{{ repairSnapshot(record.snapshot)?.locationDetail || '未填写' }}</p>
                <p>联系方式：{{ repairSnapshot(record.snapshot)?.contact || '未填写' }}</p>
                <DownloadLink
                  v-for="image in repairSnapshot(record.snapshot)?.images"
                  :key="image.id"
                  class="text-button"
                  :href="imageUrl(image)"
                >
                  下载当时图片：{{ image.name }}
                </DownloadLink>
              </details>
            </article>
            <div v-if="records.total > 0" class="pagination">
              <span>共 {{ records.total }} 条记录</span>
              <button
                class="button compact"
                :disabled="records.page <= 1 || busy"
                @click="page(records.page - 1, true)"
              >
                上一页记录
              </button>
              <button
                class="button compact"
                :disabled="records.page * records.pageSize >= records.total || busy"
                @click="page(records.page + 1, true)"
              >
                下一页记录
              </button>
            </div>
          </template>
          <template v-else>
            <section
              v-if="selected.cancellation"
              class="repair-cancellation"
              :class="{ pending: selected.cancellation.status === 'PENDING' }"
            >
              <h3>{{ cancellationStatuses[selected.cancellation.status] }}</h3>
              <small>{{ selected.cancellation.requesterName }} · {{ time(selected.cancellation.createdAt) }}</small>
              <p>申请原因：{{ selected.cancellation.reason }}</p>
              <p v-if="selected.cancellation.status === 'PENDING'">
                取消等待后勤处理，维修、资料更新和本人验收已暂停。报修人可撤销此申请。
              </p>
              <template v-else>
                <p>{{ selected.cancellation.decisionActorName }} · {{ time(selected.cancellation.resolvedAt) }}</p>
                <p>处理说明：{{ selected.cancellation.decisionComment }}</p>
              </template>
            </section>
            <p v-if="selected.confirmationOverdue" class="repair-overdue-note">
              本次维修结果已等待验收较久，请核对维修情况后及时确认或反馈问题；系统提醒不会自动完结工单。
            </p>

            <section class="repair-role-section applicant-role-card">
              <div class="role-section-header">
                <h3>故障信息</h3>
                <span class="repair-section-caption">
                  提交于 {{ time(selected.submittedAt || selected.createdAt) }}
                </span>
              </div>
              <div class="role-section-body">
                <div class="repair-bubble-box fault-bubble">
                  <p class="preserve-lines">{{ selected.description || '申报人暂未补充故障描述。' }}</p>
                </div>

                <div class="repair-role-images-block">
                  <div class="images-block-header">
                    <div class="images-block-title">
                      <Camera :size="14" />
                      <strong>
                        现场照片
                        <span>{{ faultImages.length }}</span>
                      </strong>
                    </div>
                    <span v-if="faultImages.length" class="muted-note">点击查看原图</span>
                  </div>
                  <div v-if="faultImages.length" class="repair-gallery-grid">
                    <div v-for="image in faultImages" :key="image.id" class="repair-gallery-card">
                      <button
                        class="repair-gallery-media"
                        :aria-label="`查看现场照片：${image.name}`"
                        @click="previewImage(imageUrl(image))"
                      >
                        <img :src="imageUrl(image)" :alt="image.name" loading="lazy" />
                        <div class="repair-gallery-hover-overlay">
                          <ZoomIn :size="20" />
                          <span>查看大图</span>
                        </div>
                      </button>
                      <div class="repair-gallery-info">
                        <span class="repair-gallery-name" :title="image.name">{{ image.name }}</span>
                        <div class="repair-gallery-actions">
                          <DownloadLink class="text-button" :href="imageUrl(image)">下载原图</DownloadLink>
                          <button
                            v-if="removable(image)"
                            class="text-button danger"
                            :disabled="busy"
                            @click="removeImage(image)"
                          >
                            移除
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <p v-else class="empty-media-note">申报人未上传现场照片。</p>
                </div>
                <details class="repair-metadata" :open="mode !== 'mine'">
                  <summary>
                    <User :size="14" />
                    联系信息与报修资料
                  </summary>
                  <dl class="repair-facts-grid">
                    <div class="fact-item">
                      <dt>报修人</dt>
                      <dd>{{ selected.reporterName }}</dd>
                    </div>
                    <div class="fact-item">
                      <dt>联系方式</dt>
                      <dd>{{ selected.contact || '未填写' }}</dd>
                    </div>
                    <div class="fact-item">
                      <dt>方便联系的时间</dt>
                      <dd>{{ selected.contactWindow || '未填写' }}</dd>
                    </div>
                    <div class="fact-item">
                      <dt>具体位置</dt>
                      <dd>{{ selected.locationDetail || '未补充具体位置' }}</dd>
                    </div>
                  </dl>
                </details>
              </div>
            </section>

            <section class="repair-role-section technician-role-card">
              <div class="role-section-header">
                <h3>维修进展与结果</h3>
                <span class="repair-section-caption">
                  {{ selected.technicianName ? `维修人员：${selected.technicianName}` : '尚未指派维修人员' }}
                </span>
              </div>
              <div class="role-section-body">
                <dl v-if="mode !== 'mine'" class="repair-facts-grid maintenance-facts">
                  <div class="fact-item">
                    <dt>当前维修人员</dt>
                    <dd>
                      <strong v-if="selected.technicianName" class="technician-highlight">
                        <UserCheck :size="14" />
                        {{ selected.technicianName }}
                      </strong>
                      <span v-else class="muted">尚未派工，等待后勤分诊指派</span>
                    </dd>
                  </div>
                  <div class="fact-item">
                    <dt>工单优先级</dt>
                    <dd>
                      <span class="priority-pill" :class="selected.priority.toLowerCase()">
                        {{ { LOW: '低优先级', NORMAL: '普通优先级', HIGH: '高优先级 (紧急)' }[selected.priority] }}
                      </span>
                    </dd>
                  </div>
                  <div class="fact-item">
                    <dt>维修完成轮次</dt>
                    <dd>{{ selected.completionRound > 0 ? `第 ${selected.completionRound} 轮维修` : '尚未完工' }}</dd>
                  </div>
                  <div class="fact-item">
                    <dt>派工指派记录</dt>
                    <dd>
                      {{
                        selected.assignments?.length ? `共派单/改派 ${selected.assignments.length} 次` : '暂无派单记录'
                      }}
                    </dd>
                  </div>
                </dl>

                <div v-if="selected.processingComment" class="repair-bubble-box processing-bubble">
                  <div class="bubble-title">
                    <Clock :size="14" />
                    <strong>最新处理说明</strong>
                  </div>
                  <p class="preserve-lines">{{ selected.processingComment }}</p>
                </div>

                <details v-if="selected.assignments?.length" class="repair-assignments-subblock repair-metadata">
                  <summary>
                    <ClipboardCheck :size="14" />
                    派工记录
                    <span>{{ selected.assignments.length }} 次</span>
                  </summary>
                  <div class="repair-assignment-list">
                    <article
                      v-for="(asn, idx) in selected.assignments"
                      :key="asn.id"
                      class="repair-assignment-card"
                      :class="{ current: !asn.endedAt }"
                    >
                      <div class="assignment-header">
                        <span class="assignment-index">第 {{ idx + 1 }} 次派工</span>
                        <span class="assignment-badge" :class="asn.endedAt ? 'ended' : 'active'">
                          {{ asn.endedAt ? '已结束' : '当前指派' }}
                        </span>
                      </div>
                      <div class="assignment-body">
                        <div class="assignment-row">
                          <span>
                            <strong>维修人员：</strong>
                            {{ asn.technicianName }}
                          </span>
                          <span style="margin-left: 12px">
                            <strong>调度人员：</strong>
                            {{ asn.actorName }}
                          </span>
                        </div>
                        <div class="assignment-row">
                          <span>
                            <strong>派单时间：</strong>
                            <small>{{ time(asn.assignedAt) }}</small>
                          </span>
                          <span v-if="asn.endedAt" style="margin-left: 12px">
                            <strong>结束时间：</strong>
                            <small>{{ time(asn.endedAt) }}</small>
                          </span>
                        </div>
                        <p v-if="asn.comment" class="assignment-comment">
                          <strong>派工／改派说明：</strong>
                          {{ asn.comment }}
                        </p>
                      </div>
                    </article>
                  </div>
                </details>

                <div v-if="selected.completions?.length" class="repair-completions-subblock">
                  <article
                    v-for="completion in selected.completions"
                    :key="completion.resultRound"
                    class="repair-completion-item"
                  >
                    <div class="completion-header">
                      <strong>第 {{ completion.resultRound }} 次维修结果</strong>
                      <small>{{ completion.technicianName }} · {{ time(completion.createdAt) }}</small>
                    </div>
                    <p class="preserve-lines">{{ completion.comment }}</p>
                    <div v-if="completion.images?.length" class="completion-images-links">
                      <DownloadLink
                        v-for="image in completion.images"
                        :key="image.id"
                        class="text-button"
                        :href="imageUrl(image)"
                      >
                        {{ image.name }} · 下载完工图
                      </DownloadLink>
                    </div>
                  </article>
                </div>

                <div class="repair-role-images-block">
                  <div class="images-block-header">
                    <div class="images-block-title">
                      <Camera :size="14" />
                      <strong>
                        维修照片
                        <span>{{ maintenanceImages.length }}</span>
                      </strong>
                    </div>
                    <span v-if="maintenanceImages.length" class="muted-note">维修过程与完工凭证</span>
                  </div>
                  <div v-if="maintenanceImages.length" class="repair-gallery-grid">
                    <div v-for="image in maintenanceImages" :key="image.id" class="repair-gallery-card">
                      <button
                        class="repair-gallery-media"
                        :aria-label="`查看维修照片：${image.name}`"
                        @click="previewImage(imageUrl(image))"
                      >
                        <span class="repair-gallery-tag" :class="image.purpose.toLowerCase()">
                          {{ image.purpose === 'PROGRESS' ? '维修进展' : `第 ${image.resultRound} 轮完工凭证` }}
                        </span>
                        <img :src="imageUrl(image)" :alt="image.name" loading="lazy" />
                        <div class="repair-gallery-hover-overlay">
                          <ZoomIn :size="20" />
                          <span>查看大图</span>
                        </div>
                      </button>
                      <div class="repair-gallery-info">
                        <span class="repair-gallery-name" :title="image.name">{{ image.name }}</span>
                        <div class="repair-gallery-actions">
                          <DownloadLink class="text-button" :href="imageUrl(image)">下载原图</DownloadLink>
                          <button
                            v-if="removable(image)"
                            class="text-button danger"
                            :disabled="busy"
                            @click="removeImage(image)"
                          >
                            移除
                          </button>
                        </div>
                      </div>
                    </div>
                  </div>
                  <p v-else class="empty-media-note">
                    {{
                      selected.status === 'COMPLETED' || selected.status === 'AWAITING_CONFIRMATION'
                        ? '维修人员未上传完工照片。'
                        : selected.status === 'IN_PROGRESS'
                          ? '暂无维修照片，上传后将在这里展示。'
                          : '暂无维修照片。'
                    }}
                  </p>
                </div>
              </div>
            </section>

            <!-- 补充图片 -->
            <section v-if="can('upload-fault') || can('upload-result')" class="repair-upload">
              <h3>{{ mode === 'mine' ? '补充现场故障照片' : '上传维修进展／完工照片' }}</h3>
              <label v-if="mode === 'work'">
                图片用途
                <BaseSelect v-model="purpose" :options="purposeOptions" :disabled="busy" />
              </label>
              <label>
                选择 JPEG 或 PNG 图片
                <input
                  ref="fileInput"
                  type="file"
                  accept="image/jpeg,image/png"
                  :disabled="busy"
                  @change="selectFile"
                />
              </label>
              <small>单张最多 2 MB；故障最多 6 张，进展与本轮结果各最多 3 张。刷新后待上传图片须重新选择。</small>
              <div class="repair-inline-actions">
                <button class="button compact" :disabled="busy || !file" @click="upload">上传并保存图片</button>
                <button v-if="file" class="text-button" :disabled="busy" @click="clearFile">清除选择</button>
              </div>
            </section>

            <!-- 板块 3：办结核验与服务评价（由报修人验收） -->
            <section
              v-if="selected.evaluation || selected.status === 'AWAITING_CONFIRMATION' || selected.confirmedAt"
              class="repair-role-section evaluation-role-card"
            >
              <div class="role-section-header">
                <div class="role-section-title">
                  <div class="role-avatar-badge evaluation">
                    <CheckCircle2 :size="16" />
                  </div>
                  <div>
                    <h3>验收与服务评价</h3>
                  </div>
                </div>
              </div>
              <div class="role-section-body">
                <div v-if="selected.status === 'AWAITING_CONFIRMATION'" class="awaiting-confirmation-alert">
                  <Award :size="20" />
                  <div>
                    <strong>请核对维修结果</strong>
                    <p>
                      {{
                        mode === 'mine'
                          ? '现场确认设施恢复正常后即可验收；若仍有问题，请填写说明并反馈。'
                          : '等待报修人核验设施是否恢复正常。'
                      }}
                    </p>
                  </div>
                </div>
                <div v-if="selected.evaluation" class="evaluation-display-box">
                  <div class="evaluation-stars-row">
                    <span class="stars-label">服务满意度评分：</span>
                    <span class="stars-visual">
                      <span v-for="s in 5" :key="s" class="star" :class="{ filled: s <= selected.evaluation.stars }">
                        ★
                      </span>
                    </span>
                    <strong class="stars-number">{{ selected.evaluation.stars }} 星</strong>
                    <small class="evaluation-time">{{ time(selected.evaluation.createdAt) }}</small>
                  </div>
                  <p v-if="selected.evaluation.comment" class="evaluation-comment">{{ selected.evaluation.comment }}</p>
                  <p v-else class="muted-note">（报修人未填写文字评价）</p>
                </div>
                <div class="repair-timestamps">
                  <span>报修首次提交：{{ time(selected.submittedAt) }}</span>
                  <span v-if="selected.confirmedAt">本人确认办结：{{ time(selected.confirmedAt) }}</span>
                </div>
              </div>
            </section>

            <!-- 操作面板 -->
            <section
              v-if="operationActions.length || can('edit')"
              ref="operationPanel"
              class="repair-operation-panel"
              tabindex="-1"
              aria-labelledby="repair-operation-title"
            >
              <div class="repair-operation-heading">
                <Wrench :size="16" />
                <h3 id="repair-operation-title">
                  {{
                    can('confirm')
                      ? '维修验收与反馈'
                      : can('evaluation')
                        ? '评价本次维修'
                        : mode === 'mine'
                          ? '办理此工单'
                          : '处理此工单'
                  }}
                </h3>
              </div>
              <button v-if="can('edit')" class="button compact" :disabled="busy" @click="edit">修改报修资料</button>
              <label v-if="can('assign') || can('reassign')">
                维修人员
                <BaseSelect v-model="technicianId" :options="technicianOptions" :disabled="busy" />
              </label>
              <p v-if="(can('assign') || can('reassign')) && !technicians.length" class="data-note">
                尚无当前范围匹配的有效维修人员，请联系管理员配置。
              </p>
              <label v-if="can('priority')">
                处理优先级
                <BaseSelect v-model="priority" :options="priorityOptions" :disabled="busy" />
              </label>
              <label v-if="can('evaluation')">
                服务评分
                <BaseSelect v-model="stars" :options="starOptions" :disabled="busy" />
              </label>
              <label>
                <div class="label-heading-row">
                  <span>处理说明{{ can('evaluation') ? '／评价（可选）' : '' }}</span>
                  <small class="muted">最多 {{ can('evaluation') ? 500 : 2000 }} 字</small>
                </div>
                <textarea
                  ref="commentTextareaRef"
                  v-model="comment"
                  :maxlength="can('evaluation') ? 500 : 2000"
                  rows="3"
                  :disabled="busy"
                  :placeholder="
                    can('evaluation')
                      ? '说说本次维修体验（选填）'
                      : can('confirm')
                        ? '若问题仍未解决，请说明具体情况；确认修复可直接办理'
                        : mode === 'mine'
                          ? '撤回或取消报修时，请说明原因'
                          : '请填写派工、维修进展、处理结果或退回原因'
                  "
                />
              </label>
              <div class="repair-inline-actions">
                <button
                  v-for="action in operationActions"
                  :key="action"
                  class="button compact"
                  :class="{ primary: action === primaryAction }"
                  :disabled="busy || (['assign', 'reassign'].includes(action) && !technicians.length)"
                  @click="transition(action)"
                >
                  {{ repairActions[action] }}
                </button>
              </div>
            </section>
          </template>
        </template>
        <div v-else-if="loading" class="repair-empty-detail">
          <SkeletonBlock variant="detail" label="正在读取工单详情…" />
        </div>
        <div v-else class="repair-empty-detail">
          <Wrench :size="32" />
          <h2>{{ mode === 'mine' ? '每一次报修，都有可追踪的进度' : '选择一条工单查看资料' }}</h2>
          <p>
            {{
              mode === 'mine'
                ? '从左侧查看已有记录，或新建报修描述故障。'
                : '处理操作根据当前状态、负责范围与分配人员显示。'
            }}
          </p>
        </div>
      </section>
    </div>
    <dialog
      ref="dialog"
      class="confirmation-dialog"
      aria-labelledby="repair-dialog-title"
      @cancel.prevent="finish(false)"
    >
      <div class="dialog-header">
        <div class="dialog-icon-badge">
          <AlertCircle :size="20" />
        </div>
        <h2 id="repair-dialog-title">请核对本次操作</h2>
      </div>
      <p>{{ confirmation }}</p>
      <div class="dialog-actions">
        <button ref="cancelButton" type="button" class="button compact dialog-btn-cancel" @click="finish(false)">
          取消，继续编辑
        </button>
        <button type="button" class="button primary compact dialog-btn-confirm" @click="finish(true)">确认继续</button>
      </div>
    </dialog>
    <dialog
      ref="alertDialog"
      class="confirmation-dialog alert-modal-dialog"
      aria-labelledby="repair-alert-title"
      @cancel.prevent="closeAlert"
    >
      <div class="dialog-header">
        <div class="dialog-icon-badge" :class="alertState.type">
          <AlertTriangle v-if="alertState.type === 'warning'" :size="20" />
          <AlertCircle v-else :size="20" />
        </div>
        <h2 id="repair-alert-title">{{ alertState.title }}</h2>
      </div>
      <p>{{ alertState.message }}</p>
      <div class="dialog-actions">
        <button ref="alertOkButton" type="button" class="button primary compact dialog-btn-confirm" @click="closeAlert">
          我知道了
        </button>
      </div>
    </dialog>
    <dialog
      ref="imagePreviewDialog"
      class="repair-image-modal"
      @click.self="closePreview"
      @cancel.prevent="closePreview"
    >
      <div v-if="previewModalUrl" class="repair-image-modal-content">
        <button type="button" class="repair-image-modal-close" title="关闭预览" @click="closePreview">
          <X :size="20" />
        </button>
        <img :src="previewModalUrl" class="repair-image-modal-full" alt="图片放大预览" />
      </div>
    </dialog>
  </div>
</template>

<style src="../styles/repair-workspace.css"></style>
