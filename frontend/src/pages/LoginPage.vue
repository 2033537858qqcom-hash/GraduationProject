<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ArrowRight, ArrowUpRight, ChevronDown, Eye, EyeOff } from '@lucide/vue';
import { errorMessage } from '../lib/api';
import { campusAtmosphere, initializeCampusAtmosphere } from '../lib/campusTheme';
import { initializePublicMotion, publicReducedMotion } from '../lib/publicMotion';
import { landingPage, login } from '../stores/auth';
import AtmosphereOverlay from '../components/AtmosphereOverlay.vue';
import SchoolIdentity from '../components/SchoolIdentity.vue';
import LoginBackdropArt from '../components/LoginBackdropArt.vue';
import '../styles/portal-experience.css';
import { safeLoginDestination } from '../lib/navigation';
const route = useRoute();
const router = useRouter();
const username = ref('');
const password = ref('');
const showPassword = ref(false);
const busy = ref(false);
const error = ref('');
let releaseAtmosphere = () => {};
let releaseMotion = () => {};
onMounted(() => {
  releaseAtmosphere = initializeCampusAtmosphere();
  releaseMotion = initializePublicMotion();
});
onBeforeUnmount(() => {
  releaseAtmosphere();
  releaseMotion();
});

const demoRoles = [
  { label: '学生', account: '20260001' },
  { label: '学生 2', account: '20260002' },
  { label: '教师', account: 'teacher1001' },
  { label: '辅导员', account: 'counselor1001' },
  { label: '学生事务', account: 'affairs1001' },
  { label: '教务', account: 'academic1001' },
  { label: '后勤派单', account: 'dispatcher1001' },
  { label: '维修人员', account: 'technician1001' },
  { label: '财务经办', account: 'finance1001' },
  { label: '财务复核', account: 'finance2001' },
  { label: '宿管服务', account: 'dorm1001' },
  { label: '基础教职工', account: 'staff1001' },
  { label: '内容编辑', account: 'editor' },
  { label: '内容审核', account: 'reviewer' },
  { label: '管理员', account: 'admin' },
];

function fillDemoAccount(account: string) {
  username.value = account;
  password.value = 'Demo@2026';
  error.value = '';
}
async function submit() {
  busy.value = true;
  error.value = '';
  try {
    await login(username.value, password.value);
    await router.push(safeLoginDestination(route.query.redirect) ?? landingPage());
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="login-page campus-login" :data-atmosphere="campusAtmosphere">
    <AtmosphereOverlay :atmosphere="campusAtmosphere" :reduced-motion="publicReducedMotion" surface="login" />
    <aside class="login-intro" aria-label="学校形象与平台导览">
      <LoginBackdropArt side="dark" :theme="campusAtmosphere" />
      <RouterLink class="login-school" to="/" aria-label="郑州工业应用技术学院官网">
        <SchoolIdentity />
      </RouterLink>

      <div class="login-intro-copy">
        <span class="eyebrow">统一身份门户</span>
        <h1>
          让校园日常，
          <br />
          更从容。
        </h1>
        <p>课表成绩、后勤报修、场馆预约与校园事务统一入口。</p>
      </div>

      <div class="login-image-footer">
        <span>励学敦行 · 明理至善</span>
        <RouterLink class="login-back-link" to="/" aria-label="返回学校官网首页">
          <span>返回学校官网</span>
          <ArrowUpRight :size="14" aria-hidden="true" />
        </RouterLink>
      </div>
    </aside>

    <section class="login-form-area" aria-label="登录表单">
      <LoginBackdropArt side="paper" :theme="campusAtmosphere" />
      <form class="login-form" @submit.prevent="submit">
        <div class="login-form-kicker">
          <span class="login-gold-rule" aria-hidden="true" />
          <span class="login-form-eyebrow">身份核验</span>
        </div>
        <div class="login-title-row">
          <h2>校园服务登录</h2>
        </div>
        <p v-if="route.query.passwordChanged" class="success-message" role="status">
          密码已修改，请使用新密码重新登录。
        </p>
        <p class="muted">请使用学号、工号或校园账号登录。</p>
        <p v-if="route.query.expired" class="alert" role="status">会话已过期，请重新登录。</p>
        <label>
          <span>账号</span>
          <input
            v-model="username"
            name="username"
            autocomplete="username"
            maxlength="64"
            placeholder="请输入学号、工号或账号"
            required
          />
        </label>
        <div class="login-field-head">
          <label for="campus-login-password">密码</label>
          <span class="login-help-inline">忘记密码请联系管理员重置</span>
        </div>
        <div class="login-password-field">
          <input
            id="campus-login-password"
            v-model="password"
            name="password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            maxlength="72"
            placeholder="请输入密码"
            required
          />
          <button
            type="button"
            :aria-label="showPassword ? '隐藏密码' : '显示密码'"
            :aria-pressed="showPassword"
            @click="showPassword = !showPassword"
          >
            <EyeOff v-if="showPassword" :size="17" />
            <Eye v-else :size="17" />
          </button>
        </div>
        <p v-if="error" class="alert" role="alert">{{ error }}</p>
        <button class="button primary" :disabled="busy">
          <span>{{ busy ? '正在核验通行…' : '进入校园服务' }}</span>
          <ArrowRight :size="17" />
        </button>
        <details class="demo-accounts">
          <summary>
            <span>本地演示账号</span>
            <span class="demo-accounts-meta">
              <small>快速填入 · {{ demoRoles.length }}</small>
              <ChevronDown :size="14" class="demo-accounts-chevron" aria-hidden="true" />
            </span>
          </summary>
          <div class="demo-account-grid" role="group" aria-label="演示账号快捷填入">
            <button
              v-for="item in demoRoles"
              :key="item.account"
              type="button"
              class="demo-account-chip"
              :class="{ active: username === item.account }"
              @click="fillDemoAccount(item.account)"
            >
              <span>{{ item.label }}</span>
              <code>{{ item.account }}</code>
            </button>
          </div>
          <p class="demo-accounts-note">
            初始密码：
            <code>Demo@2026</code>
            · 数据仅用于本地演示
          </p>
        </details>
      </form>
      <footer class="login-form-footer">
        <small>郑州工业应用技术学院 · 校园综合服务平台</small>
      </footer>
    </section>
  </main>
</template>
