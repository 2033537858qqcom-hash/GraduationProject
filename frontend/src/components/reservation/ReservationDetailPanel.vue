<script setup lang="ts">
import { CheckCircle2, ClipboardCheck, Clock, Download, Printer, Undo2, X, XCircle } from '@lucide/vue';
import AffairTimeline from '../AffairTimeline.vue';
import DownloadLink from '../DownloadLink.vue';
import ServiceJourney from '../ServiceJourney.vue';
import {
  activityTypes,
  reservationActions,
  reservationPassUrl,
  reservationStatuses,
  type ReservationDetail,
} from '../../reservations';

defineProps<{
  reservation: ReservationDetail;
  activeTab: string;
  canApply: boolean;
  canReview: boolean;
  isOwnReservation: boolean;
  busy: boolean;
  reviewUpdateNotice: string;
  formatTime: (iso: string) => string;
}>();

const reviewComment = defineModel<string>('reviewComment', { required: true });

const emit = defineEmits<{
  close: [];
  review: [action: 'APPROVE' | 'RETURN' | 'REJECT'];
  usage: [action: 'START' | 'COMPLETE'];
  edit: [reservation: ReservationDetail];
  withdraw: [id: number];
  cancel: [id: number];
}>();
</script>

<template>
  <section class="portal-panel reservation-detail-panel" aria-label="预约详细信息">
    <div class="section-heading">
      <div>
        <span class="eyebrow">借用申请 #{{ reservation.id }} · {{ reservation.venueName }}</span>
        <h2>{{ reservation.activityTheme }}</h2>
      </div>
      <div class="detail-header-status">
        <span class="status-label" :class="reservation.status.toLowerCase()">
          {{ reservationStatuses[reservation.status] }} · V{{ reservation.version }}
        </span>
        <button class="text-button" @click="emit('close')">
          <X :size="16" />
          关闭详情
        </button>
      </div>
    </div>

    <p v-if="reviewUpdateNotice" class="alert" role="alert">{{ reviewUpdateNotice }}</p>
    <ServiceJourney kind="RESERVATION" :status="reservation.status" :reviewer="!isOwnReservation" />

    <!-- APPROVED: ELECTRONIC PASS (入场通行电子凭证) -->
    <div
      v-if="['APPROVED', 'IN_USE'].includes(reservation.status) && new Date(reservation.endsAt).getTime() > Date.now()"
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
          <strong>PASS-{{ reservation.id }}-{{ reservation.requestKey.slice(0, 8).toUpperCase() }}</strong>
        </div>
      </div>

      <div class="pass-grid">
        <div class="pass-grid-item">
          <span class="label">借用场地/设备</span>
          <strong class="val">{{ reservation.venueName }}</strong>
          <small>{{ reservation.locationDetail }}</small>
        </div>
        <div class="pass-grid-item">
          <span class="label">准入使用时段</span>
          <strong class="val time-val">
            {{ formatTime(reservation.startsAt) }} 至 {{ formatTime(reservation.endsAt) }}
          </strong>
        </div>
        <div class="pass-grid-item">
          <span class="label">活动申请人</span>
          <span class="val">{{ reservation.applicantName }} ({{ reservation.identityNumber }})</span>
          <small>{{ reservation.departmentName }} · {{ reservation.className || '教师/部门' }}</small>
        </div>
        <div class="pass-grid-item">
          <span class="label">联系电话 / 预计人数</span>
          <span class="val">{{ reservation.contactPhone }} · 约 {{ reservation.expectedParticipants }} 人</span>
        </div>
        <div v-if="reservation.borrowEquipment" class="pass-grid-item pass-wide">
          <span class="label">核准借用配套设备</span>
          <span class="val">{{ reservation.borrowEquipment }}</span>
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
          <a class="button compact" :href="reservationPassUrl(reservation.id, true)" target="_blank" rel="noopener">
            <Printer :size="14" />
            预览 / 打印
          </a>
          <DownloadLink class="button primary compact" :href="reservationPassUrl(reservation.id)">
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
        <dd>{{ reservation.venueName }} ({{ reservation.locationDetail }})</dd>
      </div>
      <div>
        <dt>活动类别</dt>
        <dd>{{ activityTypes[reservation.activityType] || reservation.activityType }}</dd>
      </div>
      <div>
        <dt>使用时段</dt>
        <dd>{{ formatTime(reservation.startsAt) }} 至 {{ formatTime(reservation.endsAt) }}</dd>
      </div>
      <div>
        <dt>预计参与人数</dt>
        <dd>{{ reservation.expectedParticipants }} 人</dd>
      </div>
      <div>
        <dt>借用申请人</dt>
        <dd>{{ reservation.applicantName }} ({{ reservation.departmentName }})</dd>
      </div>
      <div>
        <dt>现场联系人电话</dt>
        <dd>{{ reservation.contactPhone }}</dd>
      </div>
      <div v-if="reservation.borrowEquipment" class="leave-wide">
        <dt>配套设备申请</dt>
        <dd>{{ reservation.borrowEquipment }}</dd>
      </div>
      <div v-if="reservation.processingComment" class="leave-wide">
        <dt>最近审批意见</dt>
        <dd class="preserve-lines">{{ reservation.processingComment }}</dd>
      </div>
    </dl>

    <!-- STAFF REVIEW ACTION FORM -->
    <div
      v-if="canReview && !isOwnReservation && activeTab === 'review' && reservation.status === 'SUBMITTED'"
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
          @click="emit('review', 'APPROVE')"
        >
          <CheckCircle2 :size="15" />
          同意预约并签发通行凭证
        </button>
        <button class="button compact" :disabled="busy || !reviewComment.trim()" @click="emit('review', 'RETURN')">
          <Undo2 :size="15" />
          退回补充材料
        </button>
        <button
          class="button compact danger"
          :disabled="busy || !reviewComment.trim()"
          @click="emit('review', 'REJECT')"
        >
          <XCircle :size="15" />
          驳回借用申请
        </button>
        <button
          class="button compact danger"
          :disabled="busy || !reviewComment.trim()"
          @click="emit('cancel', reservation.id)"
        >
          取消该预约
        </button>
      </div>
    </div>

    <section
      v-if="
        canReview && !isOwnReservation && activeTab === 'review' && ['APPROVED', 'IN_USE'].includes(reservation.status)
      "
      class="leave-review-form reservation-review-box"
      aria-label="使用登记与结束核销"
    >
      <h3>{{ reservation.status === 'IN_USE' ? '使用结束核销' : '现场使用登记' }}</h3>
      <p class="muted">
        {{
          reservation.status === 'IN_USE'
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
          @click="emit('usage', reservation.status === 'IN_USE' ? 'COMPLETE' : 'START')"
        >
          <ClipboardCheck :size="15" />
          {{ reservation.status === 'IN_USE' ? '确认使用结束并核销' : '登记开始使用' }}
        </button>
        <button
          v-if="reservation.status === 'APPROVED'"
          class="button compact danger"
          :disabled="busy || !reviewComment.trim()"
          @click="emit('cancel', reservation.id)"
        >
          取消该预约
        </button>
      </div>
    </section>

    <p v-if="reservation.status === 'COMPLETED'" class="content-context-note">
      本次使用已结束核销。使用登记和现场核对说明已保留，原入场凭证已失效。
    </p>
    <p v-if="reservation.status === 'EXPIRED'" class="content-context-note">
      批准时段已结束且未登记使用，本次预约已过期。如仍需使用，请重新申请。
    </p>

    <!-- STUDENT ACTIONS (Edit, Withdraw, Cancel) -->
    <div v-if="canApply && isOwnReservation" class="form-actions reservation-detail-actions">
      <button
        v-if="['DRAFT', 'RETURNED'].includes(reservation.status)"
        class="button primary compact"
        :disabled="busy"
        @click="emit('edit', reservation)"
      >
        修改预约资料并提交
      </button>
      <button
        v-if="reservation.status === 'SUBMITTED'"
        class="button compact"
        :disabled="busy"
        @click="emit('withdraw', reservation.id)"
      >
        撤回申请
      </button>
      <button
        v-if="reservation.status === 'APPROVED'"
        class="button compact danger"
        :disabled="busy"
        @click="emit('cancel', reservation.id)"
      >
        取消该预约
      </button>
    </div>

    <!-- TIMELINE INTEGRATION -->
    <div class="reservation-timeline-wrap">
      <AffairTimeline v-if="isOwnReservation" kind="RESERVATION" :request-id="reservation.id" />
      <section v-else aria-label="预约办理记录">
        <h3>办理记录</h3>
        <article v-for="record in reservation.actions" :key="record.id" class="resource-history">
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
</template>
