<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, KeyRound } from '@lucide/vue'
import { errorMessage } from '../lib/api'
import { landingPage, login } from '../stores/auth'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CulturalFrame from '../components/CulturalFrame.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
import { schoolMedia } from '../content/schoolMedia'
import { safeLoginDestination } from '../lib/navigation'
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
async function submit() {
  busy.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
    await router.push(safeLoginDestination(route.query.redirect) ?? landingPage())
  } catch (cause) { error.value = errorMessage(cause) }
  finally { busy.value = false }
}
</script>
<template>
  <main class="login-page">
    <section class="login-intro" :style="{ backgroundImage: 'linear-gradient(100deg,#173858eb,#17385860),url(' + schoolMedia.overview.url + ')' }">
      <CulturalFrame class="login-photo-frame" />
      <RouterLink class="login-school" to="/" aria-label="郑州工业应用技术学院官网"><SchoolIdentity /></RouterLink>
      <span class="eyebrow">CAMPUS SERVICES</span>
      <h1>连接校园，<br>从这里开始。</h1>
      <p>学习安排、个人消息与校园事务，<br>在同一处清晰可达。</p>
      <RouterLink class="quiet-link" to="/"><ArrowLeft :size="16" /> 学校官网</RouterLink>
      <small class="login-photo-note"><a :href="schoolMedia.overview.sourceUrl" target="_blank" rel="noopener noreferrer">全景校园 · 学校官网影像</a></small>
    </section>
    <section class="login-form-area">
      <CulturalFrame class="login-form-frame" />
      <CulturalOrnament motif="cloud" class="login-cloud" />
      <form class="login-form" @submit.prevent="submit">
        <KeyRound :size="26" class="text-brand" aria-hidden="true" />
        <h2>校园服务登录</h2>
        <p class="muted">使用你的校园服务账号进入个人门户</p>
        <p v-if="route.query.expired" class="alert" role="status">会话已过期，请重新登录。</p>
        <label>账号<input v-model="username" name="username" autocomplete="username" maxlength="64" placeholder="请输入账号" required /></label>
        <label>密码<input v-model="password" name="password" type="password" autocomplete="current-password" maxlength="72" placeholder="请输入密码" required /></label>
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <button class="button primary" :disabled="busy">{{ busy ? '正在登录…' : '登录' }} <ArrowRight :size="16" /></button>
        <details class="demo-accounts">
          <summary>本地演示账号</summary>
          <p>学生：20260001 / 20260002<br>教师：teacher1001 / teacher1002<br>辅导员：counselor1001<br>学生事务：affairs1001<br>教务：academic1001<br>后勤派单：dispatcher1001<br>维修人员：technician1001<br>服务人员：staff1001<br>内容编辑：editor · 审核：reviewer<br>管理员：admin</p>
          <p>本地初始密码：<code>Demo@2026</code><br>账号与个人记录均为演示数据。</p>
        </details>
      </form>
    </section>
  </main>
</template>
