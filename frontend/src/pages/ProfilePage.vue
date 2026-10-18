<script setup lang="ts">
import { ArrowUpRight } from '@lucide/vue'
import { currentUser } from '../stores/auth'
import { schoolMedia } from '../content/schoolMedia'

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
</script>

<template>
  <div class="profile-split-grid">
    <section class="portal-panel profile-info-panel">
      <span class="eyebrow">账号档案</span>
      <h1>个人资料</h1>
      <p class="muted">查看当前登录账号的身份与所属组织信息</p>
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

.profile-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 32px;
  margin: 24px 0 0;
  padding: 0;
}

.profile-list > div {
  padding: 15px 0;
  border-top: 1px solid #edf2f7;
}

.profile-list dt {
  font-size: 12px;
  color: #788494;
  margin-bottom: 6px;
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
}
</style>
