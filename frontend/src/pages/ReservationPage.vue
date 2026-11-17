<script setup lang="ts">
import ServicePageHeader from '../components/ServicePageHeader.vue';
import ServiceJourney from '../components/ServiceJourney.vue';
import SkeletonBlock from '../components/SkeletonBlock.vue';
import {
  AlertTriangle,
  Building2,
  CalendarCheck,
  CalendarDays,
  CheckCircle2,
  Clock,
  Edit3,
  MapPin,
  Plus,
  RefreshCw,
  Search,
  Settings,
  ShieldAlert,
  ShieldCheck,
  ToggleLeft,
  ToggleRight,
  UserCheck,
  Users,
  X,
} from '@lucide/vue';
import BaseSelect from '../components/BaseSelect.vue';
import VenueRoomLinkForm from '../components/VenueRoomLinkForm.vue';
import VenueCreateForm from '../components/VenueCreateForm.vue';
import ReservationDetailPanel from '../components/reservation/ReservationDetailPanel.vue';
import ReservationScheduleModal from '../components/reservation/ReservationScheduleModal.vue';
import ReservationVenueEditModal from '../components/reservation/ReservationVenueEditModal.vue';
import ReservationManagerModal from '../components/reservation/ReservationManagerModal.vue';
import { activityTypes, reservationStatuses } from '../reservations';
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
      <button v-if="canReview" :class="{ active: activeTab === 'review' }" @click="switchTab('review')">
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

      <SkeletonBlock v-if="loading && !venues.length" variant="cards" :rows="6" label="正在读取场地与设备资源…" />
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

      <SkeletonBlock
        v-if="loading && !filteredManagedVenues.length"
        variant="cards"
        :rows="4"
        label="正在读取场地资源配置与责任人信息…"
      />
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

      <SkeletonBlock v-if="loading && !reservations.length" variant="table" :rows="5" label="正在读取借用记录…" />
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
            <template v-for="item in reservations" :key="item.id">
              <tr :class="{ 'row-selected': selectedReservation?.id === item.id }">
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
                    <button
                      class="text-button"
                      @click="
                        selectedReservation?.id === item.id ? closeReservationDetail() : chooseReservation(item.id)
                      "
                    >
                      {{
                        selectedReservation?.id === item.id
                          ? '收起详情 ▴'
                          : item.status === 'APPROVED'
                            ? '查看凭证与详情 ▾'
                            : '查看详情 ▾'
                      }}
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
              <tr v-if="selectedReservation && selectedReservation.id === item.id" class="table-folio-row">
                <td colspan="6" class="table-folio-cell">
                  <ReservationDetailPanel
                    v-model:review-comment="reviewComment"
                    class="service-inline-folio"
                    :reservation="selectedReservation"
                    :active-tab="activeTab"
                    :can-apply="!!canApply"
                    :can-review="!!canReview"
                    :is-own-reservation="isOwnReservation"
                    :busy="busy"
                    :review-update-notice="reviewUpdateNotice"
                    :format-time="formatTime"
                    @close="closeReservationDetail"
                    @review="handleReview"
                    @usage="handleUsage"
                    @edit="editExistingReservation"
                    @withdraw="handleWithdraw"
                    @cancel="handleCancel"
                  />
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </section>

    <ReservationDetailPanel
      v-if="
        selectedReservation &&
        (activeTab === 'venues' || !reservations.some(item => item.id === selectedReservation?.id))
      "
      v-model:review-comment="reviewComment"
      :reservation="selectedReservation"
      :active-tab="activeTab"
      :can-apply="!!canApply"
      :can-review="!!canReview"
      :is-own-reservation="isOwnReservation"
      :busy="busy"
      :review-update-notice="reviewUpdateNotice"
      :format-time="formatTime"
      @close="closeReservationDetail"
      @review="handleReview"
      @usage="handleUsage"
      @edit="editExistingReservation"
      @withdraw="handleWithdraw"
      @cancel="handleCancel"
    />

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

    <ReservationScheduleModal
      v-model:date="scheduleDate"
      :show="showScheduleModal"
      :venue="scheduleVenue"
      :loading="scheduleLoading"
      :slots="scheduleSlots"
      :format-hour="formatHour"
      @close="showScheduleModal = false"
      @refresh="fetchSchedule"
      @book="initBookingForm"
    />

    <ReservationVenueEditModal
      v-model:name="venueEditForm.name"
      v-model:capacity="venueEditForm.capacity"
      v-model:available-equipment="venueEditForm.availableEquipment"
      v-model:rules="venueEditForm.rules"
      v-model:enabled="venueEditForm.enabled"
      :show="showVenueEditModal"
      :venue="editingVenue"
      :busy="busy"
      @close="showVenueEditModal = false"
      @save="saveVenueEdit"
    />

    <ReservationManagerModal
      v-model:candidate-id="selectedCandidateId"
      :show="showManagerModal"
      :venue="managingVenue"
      :busy="busy"
      :error="error"
      :success="success"
      :candidate-options="candidateOptions"
      @close="showManagerModal = false"
      @add-manager="handleAddManager"
      @remove-manager="handleRemoveManager"
    />
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

<style src="../styles/pages/reservation.css"></style>

<style src="../styles/pages/reservation-print.css"></style>
