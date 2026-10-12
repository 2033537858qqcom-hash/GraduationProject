<script setup lang="ts">
import { currentUser } from '../stores/auth'
const roleNames: Record<string,string> = { STUDENT: '学生', TEACHER: '教师', COUNSELOR: '辅导员', STUDENT_AFFAIRS: '学生事务人员', ACADEMIC_STAFF: '教务人员', SERVICE_STAFF: '校园服务人员', ADMIN: '管理员', CONTENT_EDITOR: '内容编辑', CONTENT_REVIEWER: '内容审核' }
</script>
<template>
  <section class="portal-panel profile-panel">
    <span class="eyebrow">账号信息</span><h1>个人资料</h1>
    <p class="muted">查看当前登录账号的身份与所属组织</p>
    <dl v-if="currentUser" class="profile-list">
      <div><dt>姓名</dt><dd>{{ currentUser.fullName }}</dd></div>
      <div><dt>账号</dt><dd>{{ currentUser.username }}</dd></div>
      <div><dt>学号 / 工号</dt><dd>{{ currentUser.identityNumber }}</dd></div>
      <div><dt>所属校区</dt><dd>{{ currentUser.campusName ?? '未设置' }}</dd></div>
      <div><dt>所属院系</dt><dd>{{ currentUser.departmentName ?? '未设置' }}</dd></div>
      <div><dt>行政班</dt><dd>{{ currentUser.className ?? '不适用' }}</dd></div>
      <div><dt>身份</dt><dd>{{ currentUser.roles.map(role => roleNames[role] ?? role).join('、') }}</dd></div>
    </dl>
    <p class="data-note">当前资料为毕业设计演示账号信息。</p>
  </section>
</template>
