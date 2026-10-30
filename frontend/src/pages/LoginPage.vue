<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Eye, EyeOff, KeyRound } from '@lucide/vue'
import { errorMessage } from '../lib/api'
import { landingPage, login } from '../stores/auth'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CampusContour from '../components/CampusContour.vue'
import { schoolMedia } from '../content/schoolMedia'
import { safeLoginDestination } from '../lib/navigation'
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const showPassword = ref(false)
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
  <main class="login-page campus-login">
    <section class="login-intro" :style="{ backgroundImage: 'linear-gradient(180deg,#102d35aa 0%,#102f32a6 50%,#0e2b2cef 100%),url(' + schoolMedia.overview.url + ')' }">
      <a class="login-school" href="/" aria-label="郑州工业应用技术学院官网"><SchoolIdentity /></a>
      <span class="eyebrow">A PLACE TO LEARN. A SPACE TO GROW.</span>
      <h1>让校园日常，<br>更从容。</h1>
      <p>从一堂课，到一次新的探索。<br>学习、生活与办事，在这里相连。</p>
      <a class="quiet-link" href="/" aria-label="返回学校官网首页"><ArrowLeft :size="16" /> 返回学校官网</a>
      <div class="login-image-footer"><span>励学敦行 · 明理至善</span><a :href="schoolMedia.overview.sourceUrl" target="_blank" rel="noopener noreferrer">全景校园 · 学校官网影像</a></div>
    </section>
    <section class="login-form-area">
      <form class="login-form" @submit.prevent="submit">
        <span class="login-form-eyebrow">YOUR CAMPUS, CONNECTED</span>
        <div class="login-title-row"><h2>校园服务登录</h2><KeyRound :size="21" aria-hidden="true" /></div>
        <p v-if="route.query.passwordChanged" class="success-message" role="status">密码已修改，请使用新密码重新登录。</p>
        <p class="muted">欢迎回来，使用校园服务账号继续。</p>
        <p v-if="route.query.expired" class="alert" role="status">会话已过期，请重新登录。</p>
        <label>账号<input v-model="username" name="username" autocomplete="username" maxlength="64" placeholder="请输入账号" required /></label>
        <label for="campus-login-password">密码</label>
        <div class="login-password-field"><input id="campus-login-password" v-model="password" name="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" maxlength="72" placeholder="请输入密码" required /><button type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" :aria-pressed="showPassword" @click="showPassword = !showPassword"><EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" /></button></div>
        <small class="login-help">忘记密码？请联系账号管理员核验身份并重置。</small>
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <button class="button primary" :disabled="busy">{{ busy ? '正在登录…' : '进入校园服务' }} <ArrowRight :size="17" /></button>
        <details class="demo-accounts">
          <summary>本地演示账号</summary>
          <p>学生：20260001 / 20260002<br>教师：teacher1001 / teacher1002<br>辅导员：counselor1001<br>学生事务：affairs1001<br>教务：academic1001<br>后勤派单：dispatcher1001<br>维修人员：technician1001<br>服务人员：staff1001<br>内容编辑：editor · 审核：reviewer<br>管理员：admin</p>
          <p>本地初始密码：<code>Demo@2026</code><br>账号与个人记录均为演示数据。</p>
        </details>
      </form>
      <div class="login-form-footer"><CampusContour /><small>校园综合服务平台 · 毕业设计演示</small></div>
    </section>
  </main>
</template>
