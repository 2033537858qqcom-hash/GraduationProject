<script setup lang="ts">
import ServicePageHeader from '../components/ServicePageHeader.vue';
import ServiceJourney from '../components/ServiceJourney.vue';
import DownloadLink from '../components/DownloadLink.vue';
import {
  AlertTriangle,
  Building2,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  ClipboardCheck,
  Clock,
  Download,
  Edit3,
  MapPin,
  Plus,
  Printer,
  RefreshCw,
  Search,
  Settings,
  ShieldAlert,
  ShieldCheck,
  ToggleLeft,
  ToggleRight,
  Undo2,
  UserCheck,
  Users,
  X,
  XCircle,
} from '@lucide/vue';
import AffairTimeline from '../components/AffairTimeline.vue';
import BaseSelect from '../components/BaseSelect.vue';
import VenueRoomLinkForm from '../components/VenueRoomLinkForm.vue';
import VenueCreateForm from '../components/VenueCreateForm.vue';
import {
  activityTypes,
  reservationStatuses,
  occupancyStatus,
  reservationActions,
  reservationPassUrl,
} from '../reservations';
import { useReservationWorkspace } from '../composables/useReservationWorkspace';

const {
  activeTab,
  applyQuickTime,
  bookingForm,
  busy,
  canApply,
  canManage,
  canReview,
  candidateOptions,
  categoryPillStyle,
  checkingConflict,
  chooseReservation,
  closeBookingForm,
  closeReservationDetail,
  confirmKind,
  confirmMessage,
  conflictCheck,
  creationCatalog,
  dialogComment,
  editExistingReservation,
  editReservation,
  editingReservationId,
  editingVenue,
  error,
  exploreOrigin,
  exploreReturn,
  fetchSchedule,
  filterStatus,
  filteredManagedVenues,
  finish,
  formatDate,
  formatHour,
  formatTime,
  handleAddManager,
  handleCancel,
  handleRemoveManager,
  handleReview,
  handleSaveDraft,
  handleSubmit,
  handleUsage,
  handleWithdraw,
  initBookingForm,
  isOwnReservation,
  loadManagementData,
  loadReservations,
  loadVenues,
  loading,
  manageKeyword,
  managingVenue,
  openManagerModal,
  openSchedule,
  openVenueEdit,
  reservationIndicatorStyle,
  reservations,
  reviewComment,
  reviewMode,
  reviewUpdateNotice,
  reviewerCandidates,
  roomLinkVenue,
  route,
  router,
  saveVenueEdit,
  scheduleDate,
  scheduleLoading,
  scheduleSlots,
  scheduleVenue,
  selectedCandidateId,
  selectedReservation,
  showBookingModal,
  showManagerModal,
  showScheduleModal,
  showVenueCreate,
  showVenueEditModal,
  success,
  switchTab,
  toggleVenueEnabled,
  totalItems,
  venueCategory,
  venueCreated,
  venueEditForm,
  venueFilter,
  venueKeyword,
  venues,
} = useReservationWorkspace();
</script>

<template>
  <div class="reservation-page service-workflow service-reservation">
    <!-- Confirmation Dialog -->
    <dialog ref="dialog" class="foundation-dialog" aria-labelledby="res-dialog-heading" @cancel.prevent="finish(false)">
      <h2 id="res-dialog-heading">{{ confirmKind === 'leave' ? '处理未保存的内容' : '操作确认' }}</h2>
      <p>{{ confirmMessage }}</p>
      <label v-if="confirmKind === 'cancel'">
        <div class="label-heading-row">
          <span>取消原因说明</span>
          <small class="muted">选填 · 最多 300 字</small>
        </div>
        <textarea v-model="dialogComment" rows="2" maxlength="300" placeholder="说明取消预约的原因" />
      </label>
      <div class="form-actions">
        <button ref="cancelButton" class="button compact" @click="finish(false)">
          {{ confirmKind === 'leave' ? '继续编辑' : '取消' }}
        </button>
        <button class="button primary compact" @click="finish(true)">
          {{ confirmKind === 'leave' ? '放弃并离开' : '确认执行' }}
        </button>
      </div>
    </dialog>

    <!-- Page Header -->
    <ServicePageHeader
      :context="reviewMode ? '学生事务中心 · 空间与资源审核' : '学生服务中心 · 资源借用'"
      :title="reviewMode ? '公共场地与设备借用审批' : '公共场地与设备借用'"
      :description="
        reviewMode
          ? '办理本人负责场地与设备的借用申请，核验活动安排及使用时段；本人的申请由其他负责人审批。'
          : '在线查询场地与设备开放情况，提交借用申请并查看办理结果。'
      "
    >
      <template #actions>
        <RouterLink to="/campus/explore" class="button compact">
          <MapPin :size="15" />
          三维沙盘实景查看
        </RouterLink>
        <button
          v-if="!reviewMode && canApply"
          class="button primary compact"
          :disabled="busy || loading"
          @click="initBookingForm()"
        >
          <Plus :size="15" />
          新建借用申请
        </button>
        <button
          class="button compact"
          :disabled="busy || loading"
          @click="
            activeTab === 'venues' ? loadVenues() : activeTab === 'manage' ? loadManagementData() : loadReservations()
          "
        >
          <RefreshCw :size="15" />
          刷新数据
        </button>
      </template>
    </ServicePageHeader>

    <!-- Alert / Success Messages -->
    <p v-if="error" class="alert" role="alert">{{ error }}</p>
    <p v-if="success" class="success-message" role="status">{{ success }}</p>

    <!-- Navigation Tabs -->
    <nav ref="reservationNavRef" class="learning-tabs reservation-nav-tabs" aria-label="场地借用栏目切换">
      <button v-if="!reviewMode" :class="{ active: activeTab === 'venues' }" @click="switchTab('venues')">
        <Building2 :size="15" />
        场地与设备目录
      </button>
      <button v-if="!reviewMode" :class="{ active: activeTab === 'mine' }" @click="switchTab('mine')">
        <CalendarCheck :size="15" />
        我的借用申请
      </button>
      <button v-if="reviewMode || canReview" :class="{ active: activeTab === 'review' }" @click="switchTab('review')">
        <Clock :size="15" />
        借用审批待办
      </button>
      <button v-if="canManage" :class="{ active: activeTab === 'manage' }" @click="switchTab('manage')">
        <Settings :size="15" />
        场地资源配置
      </button>
      <div class="learning-tabs-indicator" :style="reservationIndicatorStyle" aria-hidden="true"></div>
    </nav>

    <!-- ==================== TAB 1: VENUE & EQUIPMENT CATALOG ==================== -->
    <section v-if="activeTab === 'venues'" class="portal-panel venue-catalog-section" aria-label="场地与设备目录">
      <div class="foundation-filters venue-filters">
        <div ref="categoryPillsRef" class="category-pills">
          <button
            :class="{ active: venueCategory === '' }"
            @click="
              venueCategory = '';
              loadVenues();
            "
          >
            全部资源
          </button>
          <button
            :class="{ active: venueCategory === 'VENUE' }"
            @click="
              venueCategory = 'VENUE';
              loadVenues();
            "
          >
            活动场地与场馆
          </button>
          <button
            :class="{ active: venueCategory === 'EQUIPMENT' }"
            @click="
              venueCategory = 'EQUIPMENT';
              loadVenues();
            "
          >
            专用器材与设备
          </button>
          <div class="category-pill-indicator" :style="categoryPillStyle" aria-hidden="true"></div>
        </div>
        <div class="search-input-group">
          <Search :size="15" class="search-icon" />
          <input v-model="venueKeyword" placeholder="搜索场地名称、所在建筑或配套设备" @keyup.enter="loadVenues" />
          <button class="button compact" @click="loadVenues">搜索</button>
        </div>
      </div>

      <div v-if="exploreOrigin && exploreReturn" class="campus-return-banner">
        <div>
          <small>来自校园探索</small>
          <strong>{{ exploreOrigin.name }} · {{ exploreOrigin.campus === 'WEST' ? '西校区' : '东校区' }}</strong>
          <p>
            {{
              venueFilter
                ? '查看此地点关联的可借用资源，时段与办理条件以当前校验为准。'
                : '已清除地点筛选，当前显示全部可见资源。'
            }}
          </p>
        </div>
        <RouterLink :to="exploreReturn">
          返回所选地点
          <MapPin :size="14" />
        </RouterLink>
      </div>
      <div v-if="venueFilter" class="filter-tag-tip">
        <span>{{ exploreOrigin ? '已按所选地点筛选' : '已启用地点筛选' }}</span>
        <button class="text-button" @click="router.replace({ query: { ...route.query, venue: undefined } })">
          清除地点筛选
        </button>
      </div>

      <p v-if="loading && !venues.length" class="loading-state" role="status">正在读取场地与设备资源…</p>
      <p v-else-if="!loading && !venues.length" class="empty-state">
        {{
          venueFilter
            ? '此地点暂无匹配的可借用资源，可清除地点筛选后查找其他场地。'
            : '未查询到符合条件的场地或设备资源。'
        }}
      </p>

      <div v-else class="venue-cards-grid" :class="{ 'is-loading': loading }">
        <article v-for="venue in venues" :key="venue.id" class="venue-card">
          <div class="venue-card-header">
            <span class="venue-category-badge" :class="venue.category.toLowerCase()">
              {{ venue.category === 'VENUE' ? '场地空间' : '器材设备' }}
            </span>
            <span class="venue-code">{{ venue.code }}</span>
          </div>

          <h3 class="venue-title">{{ venue.name }}</h3>

          <div class="venue-meta">
            <span class="meta-item">
              <MapPin :size="14" />
              {{ venue.campusName }} · {{ venue.buildingName || '校园公共区' }} · {{ venue.locationDetail }}
            </span>
            <span v-if="venue.capacity" class="meta-item">
              <Users :size="14" />
              容纳人数：约 {{ venue.capacity }} 人
            </span>
          </div>

          <div v-if="venue.availableEquipment" class="venue-equipment-box">
            <span class="box-label">包含配套：</span>
            <p>{{ venue.availableEquipment }}</p>
          </div>

          <div v-if="venue.rules" class="venue-rules-box">
            <span class="box-label">借用守则：</span>
            <p>{{ venue.rules }}</p>
          </div>

          <div class="venue-card-actions">
            <button class="button compact" @click="openSchedule(venue)">
              <CalendarDays :size="14" />
              查看排期占用
            </button>
            <button class="button primary compact" :disabled="!venue.enabled" @click="initBookingForm(venue)">
              <Plus :size="14" />
              申请借用
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- ==================== TAB 4: VENUE MANAGEMENT (RESOURCE MANAGERS) ==================== -->
    <section v-else-if="activeTab === 'manage'" class="portal-panel venue-manage-section" aria-label="场地资源配置">
      <div class="section-heading">
        <div>
          <h2>校园场地与设备目录</h2>
          <p class="muted">按校区、地点和负责人员维护可借用资源。</p>
        </div>
        <button
          class="button primary compact"
          :disabled="busy || loading || !creationCatalog"
          @click="showVenueCreate = true"
        >
          <Plus :size="15" />
          新增资源
        </button>
      </div>
      <div class="foundation-filters venue-filters">
        <div class="manage-header-desc">
          <p class="muted">
            管理全校公共场地与设备的开放状态、容纳人数、配套设施、使用守则及
            <strong>指定责任审批人</strong>
            。 提交借用时将仅路由给本场地配置的责任人，防止审批无序扩散。
          </p>
        </div>
        <div class="search-input-group">
          <Search :size="15" class="search-icon" />
          <input v-model="manageKeyword" placeholder="搜索场地名称、编号或所在位置" />
        </div>
      </div>

      <p v-if="loading && !filteredManagedVenues.length" class="loading-state" role="status">
        正在读取场地资源配置与责任人信息…
      </p>
      <p v-else-if="!loading && !filteredManagedVenues.length" class="empty-state">没有符合搜索条件的场地资源。</p>

      <div v-else class="venue-cards-grid manage-cards-grid" :class="{ 'is-loading': loading }">
        <article
          v-for="venue in filteredManagedVenues"
          :key="venue.id"
          class="venue-card manage-card"
          :class="{ disabled: !venue.enabled }"
        >
          <div class="venue-card-header">
            <div class="badge-group">
              <span class="venue-category-badge" :class="venue.category.toLowerCase()">
                {{ venue.category === 'VENUE' ? '场地空间' : '器材设备' }}
              </span>
              <span class="venue-status-tag" :class="venue.enabled ? 'enabled' : 'disabled'">
                {{ venue.enabled ? '对外开放中' : '暂停借用' }}
              </span>
            </div>
            <span class="venue-code">{{ venue.code }}</span>
          </div>

          <h3 class="venue-title">{{ venue.name }}</h3>

          <div class="venue-meta">
            <span class="meta-item">
              <MapPin :size="14" />
              {{ venue.campusName }} · {{ venue.buildingName || '校园公共区' }} · {{ venue.locationDetail }}
            </span>
            <span v-if="venue.capacity" class="meta-item">
              <Users :size="14" />
              容纳人数：约 {{ venue.capacity }} 人
            </span>
          </div>

          <div v-if="venue.availableEquipment" class="venue-equipment-box">
            <span class="box-label">包含配套：</span>
            <p>{{ venue.availableEquipment }}</p>
          </div>

          <div v-if="venue.rules" class="venue-rules-box">
            <span class="box-label">借用守则：</span>
            <p>{{ venue.rules }}</p>
          </div>

          <div v-if="venue.category === 'VENUE'" class="venue-rules-box">
            <span class="box-label">物理房间关联</span>
            <p>{{ venue.classroomName || '尚未关联教学教室，当前仅检查本资源预约占用。' }}</p>
            <button class="text-button compact" :disabled="busy || !creationCatalog" @click="roomLinkVenue = venue">
              关联教学教室
            </button>
          </div>
          <!-- Managers Box -->
          <div class="venue-managers-box">
            <div class="managers-box-header">
              <span class="box-label">
                <ShieldCheck :size="14" />
                有效办理人员 {{ venue.activeReviewerCount }} / 已配置 {{ venue.managers.length }}
              </span>
              <button class="text-button compact" :disabled="busy" @click="openManagerModal(venue)">
                <UserCheck :size="14" />
                配置人员
              </button>
            </div>
            <div v-if="venue.activeReviewerCount === 0" class="no-manager-alert">
              <ShieldAlert :size="14" />
              <span>暂无有效审批人，请配置已启用且具备审批权限的人员。</span>
            </div>
            <div v-if="venue.unattendedTaskCount > 0" class="no-manager-alert" role="status">
              <ShieldAlert :size="14" />
              <span>{{ venue.unattendedTaskCount }} 条未完成预约无人办理，请配置接任人员。</span>
            </div>
            <p class="muted">
              待审批 {{ venue.pendingReviewCount }} 条 · 待使用 {{ venue.pendingUsageCount }} 条 · 使用中
              {{ venue.activeUseCount }} 条
            </p>
            <div v-if="venue.managers.length" class="manager-tags-list">
              <span
                v-for="m in venue.managers"
                :key="m.userId"
                class="manager-tag"
                :class="{ 'is-inactive': !m.eligible }"
              >
                <strong>{{ m.fullName }}</strong>
                <small v-if="!m.eligible">已失效</small>
                <small>({{ m.departmentName }})</small>
              </span>
            </div>
          </div>

          <!-- Actions -->
          <div class="venue-card-actions manage-actions">
            <button class="button compact" :disabled="busy" @click="openVenueEdit(venue)">
              <Edit3 :size="14" />
              编辑配置
            </button>
            <button
              class="button compact"
              :class="{ 'warning-btn': venue.enabled }"
              :disabled="busy"
              @click="toggleVenueEnabled(venue)"
            >
              <ToggleRight v-if="venue.enabled" :size="15" />
              <ToggleLeft v-else :size="15" />
              {{ venue.enabled ? '暂停开放' : '恢复开放' }}
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- ==================== TAB 2 & 3: RESERVATIONS LIST (MINE / REVIEW) ==================== -->
    <section v-else class="portal-panel reservation-list-section" aria-label="借用记录列表">
      <div class="foundation-filters">
        <label>
          申请状态
          <BaseSelect
            v-model="filterStatus"
            :options="[
              { value: '', label: '全部状态' },
              ...Object.entries(reservationStatuses).map(([key, label]) => ({ value: key, label })),
            ]"
            @change="loadReservations"
          />
        </label>
        <button class="button compact" :disabled="loading" @click="loadReservations">查询</button>
        <span class="muted" style="margin-left: auto">共 {{ totalItems }} 条申请</span>
      </div>

      <p v-if="loading && !reservations.length" class="loading-state" role="status">正在读取借用记录…</p>
      <p v-else-if="!loading && !reservations.length" class="empty-state">当前没有符合筛选条件的借用申请。</p>

      <div v-else class="table-scroll" :class="{ 'is-loading': loading }">
        <table class="campus-table foundation-table">
          <thead>
            <tr>
              <th>申请编号</th>
              <th>借用场地 / 设备</th>
              <th>活动主题与类别</th>
              <th>使用时段</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in reservations"
              :key="item.id"
              :class="{ 'row-selected': selectedReservation?.id === item.id }"
            >
              <td>
                <strong>#{{ item.id }}</strong>
                <small>V{{ item.version }}</small>
              </td>
              <td>
                <strong>{{ item.venueName }}</strong>
                <small>{{ item.locationDetail }}</small>
              </td>
              <td>
                <span>{{ item.activityTheme }}</span>
                <small>
                  {{ activityTypes[item.activityType] || item.activityType }} · 约 {{ item.expectedParticipants }} 人
                </small>
              </td>
              <td>
                <div class="time-range-cell">
                  <span>{{ formatDate(item.startsAt) }}</span>
                  <small>{{ formatHour(item.startsAt) }} ~ {{ formatHour(item.endsAt) }}</small>
                </div>
              </td>
              <td>
                <span class="status-label" :class="item.status.toLowerCase()">
                  {{ reservationStatuses[item.status] || item.status }}
                </span>
              </td>
              <td>
                <div class="table-action-btns">
                  <button class="text-button" @click="chooseReservation(item.id)">
                    {{ item.status === 'APPROVED' ? '查看凭证与详情' : '查看详情' }}
                  </button>
                  <button
                    v-if="activeTab === 'mine' && ['DRAFT', 'RETURNED'].includes(item.status)"
                    class="text-button"
                    :disabled="loading || busy"
                    @click="editReservation(item.id)"
                  >
                    修改资料
                  </button>
                  <button
                    v-if="activeTab === 'mine' && item.status === 'SUBMITTED'"
                    class="text-button"
                    @click="handleWithdraw(item.id)"
                  >
                    撤回
                  </button>
                  <button
                    v-if="activeTab === 'mine' && item.status === 'APPROVED'"
                    class="text-button cancel-text-btn"
                    @click="handleCancel(item.id)"
                  >
                    取消预约
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ==================== SELECTED RESERVATION DETAIL DRAWER/CARD ==================== -->
    <section v-if="selectedReservation" class="portal-panel reservation-detail-panel" aria-label="预约详细信息">
      <div class="section-heading">
        <div>
          <span class="eyebrow">借用申请 #{{ selectedReservation.id }} · {{ selectedReservation.venueName }}</span>
          <h2>{{ selectedReservation.activityTheme }}</h2>
        </div>
        <div class="detail-header-status">
          <span class="status-label" :class="selectedReservation.status.toLowerCase()">
            {{ reservationStatuses[selectedReservation.status] }} · V{{ selectedReservation.version }}
          </span>
          <button class="text-button" @click="closeReservationDetail">
            <X :size="16" />
            关闭详情
          </button>
        </div>
      </div>

      <p v-if="reviewUpdateNotice" class="alert" role="alert">{{ reviewUpdateNotice }}</p>
      <ServiceJourney kind="RESERVATION" :status="selectedReservation.status" :reviewer="!isOwnReservation" />

      <!-- APPROVED: ELECTRONIC PASS (入场通行电子凭证) -->
      <div
        v-if="
          ['APPROVED', 'IN_USE'].includes(selectedReservation.status) &&
          new Date(selectedReservation.endsAt).getTime() > Date.now()
        "
        id="venue-access-pass"
        class="access-pass-card"
      >
        <div class="pass-watermark">郑州工业应用技术学院</div>
        <div class="pass-header">
          <div class="pass-badge">
            <CheckCircle2 :size="24" />
            <div>
              <h3>公共场地借用电子通行凭证</h3>
              <small>郑州工业应用技术学院 · 数字化空间服务</small>
            </div>
          </div>
          <div class="pass-number">
            <span>凭证编号</span>
            <strong>
              PASS-{{ selectedReservation.id }}-{{ selectedReservation.requestKey.slice(0, 8).toUpperCase() }}
            </strong>
          </div>
        </div>

        <div class="pass-grid">
          <div class="pass-grid-item">
            <span class="label">借用场地/设备</span>
            <strong class="val">{{ selectedReservation.venueName }}</strong>
            <small>{{ selectedReservation.locationDetail }}</small>
          </div>
          <div class="pass-grid-item">
            <span class="label">准入使用时段</span>
            <strong class="val time-val">
              {{ formatTime(selectedReservation.startsAt) }} 至 {{ formatTime(selectedReservation.endsAt) }}
            </strong>
          </div>
          <div class="pass-grid-item">
            <span class="label">活动申请人</span>
            <span class="val">{{ selectedReservation.applicantName }} ({{ selectedReservation.identityNumber }})</span>
            <small>{{ selectedReservation.departmentName }} · {{ selectedReservation.className || '教师/部门' }}</small>
          </div>
          <div class="pass-grid-item">
            <span class="label">联系电话 / 预计人数</span>
            <span class="val">
              {{ selectedReservation.contactPhone }} · 约 {{ selectedReservation.expectedParticipants }} 人
            </span>
          </div>
          <div v-if="selectedReservation.borrowEquipment" class="pass-grid-item pass-wide">
            <span class="label">核准借用配套设备</span>
            <span class="val">{{ selectedReservation.borrowEquipment }}</span>
          </div>
        </div>

        <div class="pass-footer">
          <div class="pass-instructions">
            <p>
              <strong>使用提示：</strong>
              活动开始前15分钟请向场地楼管/管理员出示此凭证开启场地门锁；遵守校内声环境与消防安全管理制度，结束时清理卫生并切断电源。
            </p>
            <p class="pass-export-note no-print">
              可下载 PDF 后打开打印；凭证仅适用于所载时段，取消或变更以系统最新状态为准。
            </p>
          </div>
          <div class="pass-actions no-print">
            <a
              class="button compact"
              :href="reservationPassUrl(selectedReservation.id, true)"
              target="_blank"
              rel="noopener"
            >
              <Printer :size="14" />
              预览 / 打印
            </a>
            <DownloadLink class="button primary compact" :href="reservationPassUrl(selectedReservation.id)">
              <Download :size="14" />
              下载 PDF
            </DownloadLink>
          </div>
        </div>
      </div>

      <!-- FACTS GRID -->
      <dl class="resource-facts reservation-facts">
        <div>
          <dt>借用场地</dt>
          <dd>{{ selectedReservation.venueName }} ({{ selectedReservation.locationDetail }})</dd>
        </div>
        <div>
          <dt>活动类别</dt>
          <dd>{{ activityTypes[selectedReservation.activityType] || selectedReservation.activityType }}</dd>
        </div>
        <div>
          <dt>使用时段</dt>
          <dd>{{ formatTime(selectedReservation.startsAt) }} 至 {{ formatTime(selectedReservation.endsAt) }}</dd>
        </div>
        <div>
          <dt>预计参与人数</dt>
          <dd>{{ selectedReservation.expectedParticipants }} 人</dd>
        </div>
        <div>
          <dt>借用申请人</dt>
          <dd>{{ selectedReservation.applicantName }} ({{ selectedReservation.departmentName }})</dd>
        </div>
        <div>
          <dt>现场联系人电话</dt>
          <dd>{{ selectedReservation.contactPhone }}</dd>
        </div>
        <div v-if="selectedReservation.borrowEquipment" class="leave-wide">
          <dt>配套设备申请</dt>
          <dd>{{ selectedReservation.borrowEquipment }}</dd>
        </div>
        <div v-if="selectedReservation.processingComment" class="leave-wide">
          <dt>最近审批意见</dt>
          <dd class="preserve-lines">{{ selectedReservation.processingComment }}</dd>
        </div>
      </dl>

      <!-- STAFF REVIEW ACTION FORM -->
      <div
        v-if="canReview && !isOwnReservation && activeTab === 'review' && selectedReservation.status === 'SUBMITTED'"
        class="leave-review-form reservation-review-box"
      >
        <h3>
          <Clock :size="16" />
          审核意见与决定
        </h3>
        <label>
          <div class="label-heading-row">
            <span>审批处理意见 / 附言</span>
            <small class="muted">必填 · 最多 1000 字</small>
          </div>
          <textarea
            v-model="reviewComment"
            :disabled="busy"
            rows="3"
            maxlength="1000"
            placeholder="同意时可注明场地安全与钥匙借用指引；退回或驳回时请详述原因或修改建议"
          />
        </label>
        <div class="form-actions">
          <button
            class="button primary compact"
            :disabled="busy || !reviewComment.trim()"
            @click="handleReview('APPROVE')"
          >
            <CheckCircle2 :size="15" />
            同意预约并签发通行凭证
          </button>
          <button class="button compact" :disabled="busy || !reviewComment.trim()" @click="handleReview('RETURN')">
            <Undo2 :size="15" />
            退回补充材料
          </button>
          <button
            class="button compact danger"
            :disabled="busy || !reviewComment.trim()"
            @click="handleReview('REJECT')"
          >
            <XCircle :size="15" />
            驳回借用申请
          </button>
        </div>
      </div>

      <section
        v-if="
          canReview &&
          !isOwnReservation &&
          activeTab === 'review' &&
          ['APPROVED', 'IN_USE'].includes(selectedReservation.status)
        "
        class="leave-review-form reservation-review-box"
        aria-label="使用登记与结束核销"
      >
        <h3>{{ selectedReservation.status === 'IN_USE' ? '使用结束核销' : '现场使用登记' }}</h3>
        <p class="muted">
          {{
            selectedReservation.status === 'IN_USE'
              ? '请核实场地、设施及借用设备已交接后结束本次使用；逾时仍需在此核销。'
              : '请在批准的使用时段内核对申请人及现场人数后登记开始使用。'
          }}
        </p>
        <label>
          现场核对说明
          <textarea
            v-model="reviewComment"
            rows="3"
            maxlength="1000"
            :disabled="busy"
            placeholder="填写现场核对、设施或设备交接情况"
          />
        </label>
        <div class="form-actions">
          <button
            class="button primary compact"
            :disabled="busy || !reviewComment.trim()"
            @click="handleUsage(selectedReservation.status === 'IN_USE' ? 'COMPLETE' : 'START')"
          >
            <ClipboardCheck :size="15" />
            {{ selectedReservation.status === 'IN_USE' ? '确认使用结束并核销' : '登记开始使用' }}
          </button>
        </div>
      </section>
      <p v-if="selectedReservation.status === 'COMPLETED'" class="content-context-note">
        本次使用已结束核销。使用登记和现场核对说明已保留，原入场凭证已失效。
      </p>
      <p v-if="selectedReservation.status === 'EXPIRED'" class="content-context-note">
        批准时段已结束且未登记使用，本次预约已过期。如仍需使用，请重新申请。
      </p>

      <!-- STUDENT ACTIONS (Edit, Withdraw, Cancel) -->
      <div v-if="canApply && isOwnReservation" class="form-actions reservation-detail-actions">
        <button
          v-if="['DRAFT', 'RETURNED'].includes(selectedReservation.status)"
          class="button primary compact"
          :disabled="busy"
          @click="editExistingReservation(selectedReservation)"
        >
          修改预约资料并提交
        </button>
        <button
          v-if="selectedReservation.status === 'SUBMITTED'"
          class="button compact"
          :disabled="busy"
          @click="handleWithdraw(selectedReservation.id)"
        >
          撤回申请
        </button>
        <button
          v-if="selectedReservation.status === 'APPROVED'"
          class="button compact danger"
          :disabled="busy"
          @click="handleCancel(selectedReservation.id)"
        >
          取消该预约
        </button>
      </div>

      <!-- TIMELINE INTEGRATION -->
      <div class="reservation-timeline-wrap">
        <AffairTimeline v-if="isOwnReservation" kind="RESERVATION" :request-id="selectedReservation.id" />
        <section v-else aria-label="预约办理记录">
          <h3>办理记录</h3>
          <article v-for="record in selectedReservation.actions" :key="record.id" class="resource-history">
            <div class="section-heading">
              <strong>{{ reservationActions[record.action] || record.action }}</strong>
              <span class="status-label">{{ reservationStatuses[record.status] || record.status }}</span>
            </div>
            <p class="preserve-lines">{{ record.comment }}</p>
            <small class="muted">{{ record.actorName }} · {{ formatTime(record.createdAt) }}</small>
          </article>
        </section>
      </div>
    </section>

    <!-- ==================== BOOKING MODAL (DRAWER/POPUP) ==================== -->
    <dialog
      v-if="showBookingModal"
      ref="bookingDialog"
      class="modal-overlay booking-dialog"
      aria-labelledby="booking-modal-title"
      @cancel.prevent="closeBookingForm"
      @click.self="closeBookingForm"
    >
      <div class="modal-card booking-modal-card">
        <div class="modal-header">
          <h2 id="booking-modal-title">
            <CalendarCheck :size="18" />
            {{ editingReservationId ? `修改借用申请 #${editingReservationId}` : '新建公共场地与设备借用' }}
          </h2>
          <button type="button" class="close-icon-btn" aria-label="关闭借用申请表单" @click="closeBookingForm">
            <X :size="18" />
          </button>
        </div>

        <ServiceJourney kind="RESERVATION" preparing />
        <form class="editor-form booking-form" @submit.prevent="handleSubmit">
          <div class="form-grid-two">
            <label>
              目标场地 / 设备
              <BaseSelect
                v-model="bookingForm.venueId"
                :options="
                  venues.map(v => ({
                    value: v.id,
                    label: `${v.name} (${v.buildingName || '公共区'} · ${v.category === 'VENUE' ? `容纳${v.capacity}人` : '设备'})`,
                  }))
                "
                :disabled="busy"
                placeholder="请选择目标场地或设备"
              />
            </label>

            <label>
              活动类型
              <BaseSelect
                v-model="bookingForm.activityType"
                :options="Object.entries(activityTypes).map(([val, name]) => ({ value: val, label: name }))"
                :disabled="busy"
                placeholder="请选择活动类型"
              />
            </label>
          </div>

          <label>
            活动主题
            <input
              v-model="bookingForm.activityTheme"
              required
              maxlength="150"
              placeholder="例：2026届计算机学院毕业设计开题答辩研讨会"
              :disabled="busy"
            />
          </label>

          <div class="form-grid-two">
            <label>
              预计参与人数
              <input
                v-model.number="bookingForm.expectedParticipants"
                type="number"
                min="1"
                max="2000"
                required
                :disabled="busy"
              />
            </label>

            <label>
              现场负责人员电话
              <input
                v-model="bookingForm.contactPhone"
                type="tel"
                required
                maxlength="15"
                placeholder="11位手机号码"
                :disabled="busy"
              />
            </label>
          </div>

          <!-- TIME SELECTOR -->
          <div class="time-selection-section">
            <div class="time-inputs-row">
              <label>
                借用开始时间
                <input v-model="bookingForm.startsAt" type="datetime-local" required :disabled="busy" />
              </label>

              <label>
                借用结束时间
                <input v-model="bookingForm.endsAt" type="datetime-local" required :disabled="busy" />
              </label>
            </div>

            <!-- QUICK TIME CHIPS -->
            <div class="quick-chips-row">
              <span class="chips-label">快速填充：</span>
              <button type="button" class="chip-btn" @click="applyQuickTime('tomorrow-am')">
                明天上午 09:00~11:30
              </button>
              <button type="button" class="chip-btn" @click="applyQuickTime('tomorrow-pm')">
                明天下午 14:30~17:00
              </button>
              <button type="button" class="chip-btn" @click="applyQuickTime('afternoon')">后天下午 14:00~17:30</button>
              <button type="button" class="chip-btn" @click="applyQuickTime('weekend')">本周末上午 09:00~12:00</button>
            </div>

            <!-- CONFLICT CHECK STATUS -->
            <div class="conflict-check-banner">
              <div v-if="checkingConflict" class="checking-state">
                <RefreshCw :size="14" class="spinning" />
                <span>正在校验全校场地排期冲突…</span>
              </div>
              <div v-else-if="conflictCheck?.hasConflict" class="conflict-alert">
                <AlertTriangle :size="16" />
                <div>
                  <strong>时段冲突警告：{{ conflictCheck.message }}</strong>
                  <ul v-if="conflictCheck.conflictingSlots.length" class="conflict-slot-list">
                    <li v-for="slot in conflictCheck.conflictingSlots" :key="slot.startsAt">
                      已占用：{{ formatTime(slot.startsAt) }} ~ {{ formatHour(slot.endsAt) }}（{{
                        slot.activityTheme
                      }}）
                    </li>
                  </ul>
                </div>
              </div>
              <div v-else-if="conflictCheck && !conflictCheck.hasConflict" class="conflict-clear">
                <CheckCircle2 :size="15" />
                <span>当前时段未发现系统占用，提交时将再次校验资源及审批条件。</span>
              </div>
            </div>
          </div>

          <label>
            <div class="label-heading-row">
              <span>需要借用的配套器材 / 附加需求 (选填)</span>
              <small class="muted">选填 · 最多 400 字</small>
            </div>
            <textarea
              v-model="bookingForm.borrowEquipment"
              rows="3"
              maxlength="400"
              placeholder="例：无线手持麦克风2支、激光翻页笔1支、活动展架2套"
              :disabled="busy"
            />
          </label>

          <p class="data-note">
            请确认活动符合校规校纪。审批通过后系统将即时生成入场通行电子凭证，请于使用前联系管理员准入。
          </p>

          <div class="form-actions modal-actions">
            <button type="button" class="button compact" :disabled="busy" @click="handleSaveDraft">保存为草稿</button>
            <button type="submit" class="button primary compact" :disabled="busy || conflictCheck?.hasConflict">
              正式提交预约
            </button>
            <button type="button" class="button compact" :disabled="busy" @click="closeBookingForm">取消</button>
          </div>
        </form>
      </div>
    </dialog>

    <!-- ==================== SCHEDULE VIEWER MODAL ==================== -->
    <div
      v-if="showScheduleModal && scheduleVenue"
      class="modal-overlay"
      @click.self="showScheduleModal = false"
      @keydown.esc="showScheduleModal = false"
    >
      <div
        class="modal-card schedule-modal-card"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        aria-labelledby="schedule-modal-title"
      >
        <div class="modal-header">
          <h2 id="schedule-modal-title">
            <CalendarDays :size="18" />
            {{ scheduleVenue.name }} · 排期占用情况
          </h2>
          <button class="close-icon-btn" @click="showScheduleModal = false">
            <X :size="18" />
          </button>
        </div>

        <div class="schedule-controls">
          <label>
            查询日期
            <input v-model="scheduleDate" type="date" @change="fetchSchedule" />
          </label>
          <button class="button compact" :disabled="scheduleLoading" @click="fetchSchedule">
            <RefreshCw :size="14" />
            刷新排期
          </button>
        </div>

        <div class="schedule-content">
          <p v-if="scheduleLoading" class="loading-state">正在查询当天排期…</p>
          <div v-else-if="!scheduleSlots.length" class="empty-state">
            <CheckCircle2 :size="24" style="color: #10b981; margin-bottom: 8px" />
            <p>该日期目前全天无任何预约占用，时段充裕！</p>
          </div>
          <ul v-else class="schedule-slots-timeline">
            <li v-for="slot in scheduleSlots" :key="slot.startsAt" class="schedule-slot-item">
              <div class="slot-time">
                <strong>{{ formatHour(slot.startsAt) }} - {{ formatHour(slot.endsAt) }}</strong>
                <span class="slot-status-badge">{{ occupancyStatus(slot.status) }}</span>
              </div>
              <div class="slot-desc">
                <h4>{{ slot.activityTheme }}</h4>
              </div>
            </li>
          </ul>
        </div>

        <div class="modal-footer">
          <button
            class="button primary compact"
            @click="
              showScheduleModal = false;
              initBookingForm(scheduleVenue);
            "
          >
            预约该场地
          </button>
          <button class="button compact" @click="showScheduleModal = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- ==================== MODAL: EDIT VENUE CONFIGURATION ==================== -->
    <div
      v-if="showVenueEditModal && editingVenue"
      class="modal-overlay"
      @click.self="showVenueEditModal = false"
      @keydown.esc="showVenueEditModal = false"
    >
      <div
        class="modal-card venue-edit-modal"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        aria-labelledby="venue-edit-title"
      >
        <div class="modal-header">
          <h2 id="venue-edit-title">
            <Edit3 :size="18" />
            修改场地资源配置 · {{ editingVenue.name }}
          </h2>
          <button class="close-icon-btn" @click="showVenueEditModal = false">
            <X :size="18" />
          </button>
        </div>

        <form class="booking-form" @submit.prevent="saveVenueEdit">
          <div class="form-grid-two">
            <label>
              场地/设备名称
              <input v-model="venueEditForm.name" required maxlength="100" />
            </label>
            <label>
              容纳人数（人）
              <input v-model.number="venueEditForm.capacity" type="number" min="1" max="10000" />
            </label>
          </div>

          <label>
            可用配套设备与技术规格
            <input
              v-model="venueEditForm.availableEquipment"
              maxlength="500"
              placeholder="例如：投影仪、无线麦克风*2、调音台"
            />
          </label>

          <label>
            <div class="label-heading-row">
              <span>借用守则与场地公约</span>
              <small class="muted">最多 1000 字</small>
            </div>
            <textarea
              v-model="venueEditForm.rules"
              rows="3"
              maxlength="1000"
              placeholder="填写使用要求、保洁守则、禁止携带外卖等约定"
            />
          </label>

          <label
            class="checkbox-label"
            style="display: flex; align-items: center; gap: 8px; margin-top: 8px; cursor: pointer"
          >
            <input v-model="venueEditForm.enabled" type="checkbox" />
            <span>开启该场地/设备的对外预约开放（取消勾选将暂停开放）</span>
          </label>
          <p v-if="!venueEditForm.enabled" class="alert" role="status">
            停用会取消尚未开始的待审和已批准预约，并通知申请人。正在使用的场地须先完成结束核销，才能停用。
          </p>

          <div class="form-actions modal-actions" style="margin-top: 18px">
            <button type="submit" class="button primary compact" :disabled="busy">保存配置</button>
            <button type="button" class="button compact" @click="showVenueEditModal = false">取消</button>
          </div>
        </form>
      </div>
    </div>

    <!-- ==================== MODAL: MANAGE VENUE REVIEWERS ==================== -->
    <div
      v-if="showManagerModal && managingVenue"
      class="modal-overlay"
      @click.self="showManagerModal = false"
      @keydown.esc="showManagerModal = false"
    >
      <div
        class="modal-card manager-modal-card"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        aria-labelledby="manager-modal-title"
      >
        <div class="modal-header">
          <h2 id="manager-modal-title">
            <ShieldCheck :size="18" />
            配置责任审批人 · {{ managingVenue.name }}
          </h2>
          <button class="close-icon-btn" @click="showManagerModal = false">
            <X :size="18" />
          </button>
        </div>

        <div class="modal-body-content" style="display: flex; flex-direction: column; gap: 16px">
          <p class="muted" style="font-size: 13px; line-height: 1.6">
            未完成预约需要至少一位有效且非申请人本人的办理人员。添加负责人会通知其接手待审批、待使用及使用中的预约；请先添加接任人员，再解除原负责人。
          </p>
          <p v-if="error" class="alert" role="alert">{{ error }}</p>
          <p v-if="success" class="success-message" role="status">{{ success }}</p>
          <p class="muted">
            待审批 {{ managingVenue.pendingReviewCount }} 条 · 待使用 {{ managingVenue.pendingUsageCount }} 条 · 使用中
            {{ managingVenue.activeUseCount }} 条
          </p>
          <p v-if="managingVenue.unattendedTaskCount > 0" class="alert" role="status">
            当前 {{ managingVenue.unattendedTaskCount }} 条未完成预约无人办理，请添加接任人员。
          </p>

          <!-- Current Assigned Reviewers -->
          <div class="assigned-managers-list">
            <h4 style="font-size: 14px; margin-bottom: 8px; font-weight: 600">
              已指定责任审批人 ({{ managingVenue.managers.length }})
            </h4>
            <div
              v-if="!managingVenue.managers.length"
              class="empty-state"
              style="padding: 16px; background: #fff1f2; border: 1px dashed #f43f5e; border-radius: 6px"
            >
              <span style="color: #e11d48; font-size: 13px">
                目前尚未指定任何审批责任人！未配置责任人前该场地无法受理申请。
              </span>
            </div>
            <ul
              v-else
              class="manager-item-list"
              style="list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px"
            >
              <li
                v-for="m in managingVenue.managers"
                :key="m.userId"
                class="manager-row-item"
                style="
                  display: flex;
                  justify-content: space-between;
                  align-items: center;
                  padding: 10px 14px;
                  background: #f8fafc;
                  border: 1px solid #e2e8f0;
                  border-radius: 6px;
                "
              >
                <div style="display: flex; flex-direction: column; gap: 2px">
                  <strong style="font-size: 14px; color: #1e293b">
                    {{ m.fullName }} · {{ m.eligible ? '可审批' : '已失效：账号停用或缺少审批权限' }}
                  </strong>
                  <span style="font-size: 12px; color: #64748b">
                    账号：{{ m.username }} · 部门：{{ m.departmentName }} · 工号：{{ m.identityNumber }}
                  </span>
                </div>
                <button
                  class="text-button"
                  style="color: #ef4444"
                  :disabled="busy"
                  @click="handleRemoveManager(m.userId)"
                >
                  解除指定
                </button>
              </li>
            </ul>
          </div>

          <!-- Add Reviewer -->
          <div
            class="add-manager-box"
            style="
              padding: 14px;
              background: #f1f5f9;
              border-radius: 6px;
              display: flex;
              flex-direction: column;
              gap: 10px;
            "
          >
            <h4 style="font-size: 13px; font-weight: 600; color: #334155">添加新的责任审批人</h4>
            <div style="display: flex; gap: 10px; align-items: center">
              <div style="flex: 1">
                <BaseSelect
                  v-model="selectedCandidateId"
                  :options="[{ value: '', label: '从具备借用审批权限的人员中选择…' }, ...candidateOptions]"
                />
              </div>
              <button class="button primary compact" :disabled="!selectedCandidateId || busy" @click="handleAddManager">
                添加指定
              </button>
            </div>
          </div>
        </div>

        <div class="modal-footer" style="margin-top: 16px">
          <button class="button compact" @click="showManagerModal = false">完成并关闭</button>
        </div>
      </div>
    </div>
    <VenueRoomLinkForm
      v-if="roomLinkVenue && creationCatalog"
      :venue="roomLinkVenue"
      :catalog="creationCatalog"
      @close="roomLinkVenue = null"
      @saved="
        roomLinkVenue = null;
        loadManagementData();
      "
    />
    <VenueCreateForm
      v-if="showVenueCreate && creationCatalog"
      :catalog="creationCatalog"
      :candidates="reviewerCandidates"
      @close="showVenueCreate = false"
      @saved="venueCreated"
    />
  </div>
</template>

<style scoped src="../styles/pages/reservation.css"></style>

<style src="../styles/pages/reservation-print.css"></style>
