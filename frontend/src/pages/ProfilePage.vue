<script setup lang="ts">
import { ref } from 'vue'
import { ArrowUpRight, Camera, Trash2, Upload } from '@lucide/vue'
import { currentUser } from '../stores/auth'
import { api, errorMessage } from '../lib/api'
import { schoolMedia } from '../content/schoolMedia'
import UserAvatar from '../components/UserAvatar.vue'
import type { Profile } from '../types'

const roleNames: Record<string, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  COUNSELOR: '辅导员',
  STUDENT_AFFAIRS: '学生事务人员',
  ACADEMIC_STAFF: '教务人员',
  SERVICE_STAFF: '校园服务人员',
  ADMIN: '管理员',
  CONTENT_EDITOR: '内容编辑',
  CONTENT_REVIEWER: '内容审核',
}

const uploading = ref(false)
const feedback = ref('')
const fileInput = ref<HTMLInputElement | null>(null)

async function handleAvatarChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  feedback.value = ''
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    feedback.value = '仅支持 PNG、JPEG 或 WebP 格式的头像图片'
    input.value = ''
    return
  }
  if (file.size > 2 * 1024 * 1024) {
    feedback.value = '头像图片不能超过 2 MB'
    input.value = ''
    return
  }

  const formData = new FormData()
  formData.append('file', file)

  uploading.value = true
  try {
    const updated = await api<Profile>('/me/avatar', {
      method: 'POST',
      body: formData,
    })
    currentUser.value = updated
    feedback.value = ''
  } catch (cause) {
    feedback.value = errorMessage(cause)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function handleRemoveAvatar() {
  if (!currentUser.value?.avatarUrl) return
  uploading.value = true
  feedback.value = ''
  try {
    const updated = await api<Profile>('/me/avatar', {
      method: 'DELETE',
    })
    currentUser.value = updated
    feedback.value = ''
  } catch (cause) {
    feedback.value = errorMessage(cause)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="profile-split-grid">
    <section class="portal-panel profile-info-panel">
      <div class="profile-avatar-header">
        <div class="avatar-container">
          <UserAvatar :src="currentUser?.avatarUrl" :name="currentUser?.fullName ?? ''" size="xl" />
          <label class="avatar-camera-btn" :class="{ 'is-loading': uploading }" title="点击更换头像">
            <Camera :size="15" aria-hidden="true" />
            <input
              ref="fileInput"
              type="file"
              class="sr-only"
              accept="image/png,image/jpeg,image/webp"
              :disabled="uploading"
              @change="handleAvatarChange"
            />
          </label>
        </div>
        <div class="avatar-header-meta">
          <div class="eyebrow-row">
            <span class="eyebrow">账号档案 · 个人门户</span>
          </div>
          <h1>{{ currentUser?.fullName }}</h1>
          <p class="account-identity-row">
            <span class="user-id">学号 / 工号：{{ currentUser?.identityNumber }}</span>
            <span class="role-pill">{{ currentUser?.roles.map(role => roleNames[role] ?? role).join(' · ') }}</span>
          </p>
          <div class="avatar-action-row">
            <button
              class="button avatar-btn upload"
              type="button"
              :disabled="uploading"
              @click="fileInput?.click()"
            >
              <Upload :size="14" aria-hidden="true" />
              <span>{{ uploading ? '正在上传…' : '更换头像' }}</span>
            </button>
            <button
              v-if="currentUser?.avatarUrl"
              class="button avatar-btn remove"
              type="button"
              :disabled="uploading"
              @click="handleRemoveAvatar"
            >
              <Trash2 :size="14" aria-hidden="true" />
              <span>恢复默认</span>
            </button>
          </div>
          <p v-if="feedback" class="feedback-banner error" role="alert">
            {{ feedback }}
          </p>
        </div>
      </div>

      <dl v-if="currentUser" class="profile-list">
        <div><dt>姓名</dt><dd>{{ currentUser.fullName }}</dd></div>
        <div><dt>账号</dt><dd>{{ currentUser.username }}</dd></div>
        <div><dt>学号 / 工号</dt><dd>{{ currentUser.identityNumber }}</dd></div>
        <div><dt>所属校区</dt><dd>{{ currentUser.campusName ?? '未设置' }}</dd></div>
        <div><dt>所属院系</dt><dd>{{ currentUser.departmentName ?? '未设置' }}</dd></div>
        <div><dt>行政班</dt><dd>{{ currentUser.className ?? '不适用' }}</dd></div>
        <div class="profile-full-item"><dt>身份角色</dt><dd>{{ currentUser.roles.map(role => roleNames[role] ?? role).join('、') }}</dd></div>
      </dl>
      <p class="data-note">当前资料为毕业设计演示账号信息。</p>
    </section>

    <aside class="profile-scenic-card">
      <div class="scenic-photo-wrap">
        <img :src="schoolMedia.architecture.url" :alt="schoolMedia.architecture.alt" width="800" height="518" />
        <span class="scenic-badge">校园风光 · 东校区</span>
      </div>
      <div class="scenic-body">
        <div>
          <div class="scenic-motto">励学 · 敦行 · 明理 · 至善</div>
          <p class="scenic-desc">郑州工业应用技术学院坐落于郑州市南大学城。笃学尚行、求真至善，在静谧优雅的校园环境中，专注探求学业新知与专业实践。</p>
        </div>
        <RouterLink to="/campus/explore" class="scenic-link">
          进入 3D 校园探索 <ArrowUpRight :size="14" aria-hidden="true" />
        </RouterLink>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.profile-split-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(340px, 1fr);
  gap: 24px;
  align-items: stretch;
}

.profile-info-panel {
  padding: 30px 34px;
}

/* Avatar header layout */
.profile-avatar-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid #edf2f7;
  margin-bottom: 8px;
}

.avatar-container {
  position: relative;
  flex-shrink: 0;
}

.avatar-camera-btn {
  position: absolute;
  right: -2px;
  bottom: 0px;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--brand, #244b78);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.18), 0 0 0 2px #ffffff;
  transition: transform 0.15s ease, background-color 0.15s ease;
}

.avatar-camera-btn:hover {
  background: #183354;
  transform: scale(1.06);
}

.avatar-header-meta {
  flex: 1;
  min-width: 0;
}

.eyebrow-row {
  margin-bottom: 4px;
}

.profile-avatar-header h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 600;
  color: #1e293b;
}

.account-identity-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin: 0 0 14px;
  font-size: 13px;
  color: #64748b;
}

.user-id {
  font-variant-numeric: tabular-nums;
}

.role-pill {
  font-size: 11px;
  background: #f1f5f9;
  color: #475569;
  padding: 2px 8px;
  border-radius: 4px;
}

.avatar-action-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.avatar-btn {
  padding: 6px 14px;
  font-size: 12px;
  height: 32px;
}

.avatar-btn.upload {
  background: #f8fafc;
  border-color: #cbd5e1;
  color: #334155;
}

.avatar-btn.upload:hover {
  background: #f1f5f9;
  color: #1e293b;
}

.avatar-btn.remove {
  background: transparent;
  border-color: #e2e8f0;
  color: #64748b;
}

.avatar-btn.remove:hover {
  background: #fff1f2;
  border-color: #fecdd3;
  color: #e11d48;
}

.feedback-banner {
  margin: 10px 0 0;
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.feedback-banner.error {
  background: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

.profile-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 32px;
  margin: 16px 0 0;
  padding: 0;
}

.profile-list > div {
  padding: 14px 0;
  border-top: 1px solid #edf2f7;
}

.profile-list dt {
  font-size: 12px;
  color: #788494;
  margin-bottom: 4px;
}

.profile-list dd {
  margin: 0;
  font-size: 14px;
  color: #1e293b;
  font-weight: 500;
}

.profile-full-item {
  grid-column: 1 / -1;
}

.data-note {
  font-size: 11px;
  color: #94a3b8;
  margin-top: 20px;
  border-top: 1px dashed #edf2f7;
  padding-top: 12px;
}

/* Right: Campus scenic card */
.profile-scenic-card {
  background: #fff;
  border: 1px solid #dfe5ec;
  border-radius: 9px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.scenic-photo-wrap {
  position: relative;
  height: 220px;
  background: #244b78;
  overflow: hidden;
}

.scenic-photo-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.scenic-badge {
  position: absolute;
  top: 14px;
  left: 14px;
  background: rgba(20, 35, 55, 0.72);
  backdrop-filter: blur(8px);
  color: #fff;
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 4px;
  letter-spacing: 1px;
}

.scenic-body {
  padding: 24px 26px;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.scenic-motto {
  font-family: 'Songti SC', 'STSong', 'SimSun', serif;
  font-size: 18px;
  font-weight: 600;
  color: #243c55;
  letter-spacing: .06em;
  margin-bottom: 8px;
}

.scenic-desc {
  font-size: 12px;
  color: #64748b;
  line-height: 1.8;
  margin: 0;
}

.scenic-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: #244b78;
  text-decoration: none;
  font-weight: 600;
  margin-top: 18px;
  transition: color 0.15s ease;
}

.scenic-link:hover {
  text-decoration: underline;
}

@media (max-width: 900px) {
  .profile-split-grid {
    grid-template-columns: 1fr;
  }
  .profile-avatar-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
