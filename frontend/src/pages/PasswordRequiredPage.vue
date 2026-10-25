<script setup lang="ts">
import { useRouter } from 'vue-router'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import AccountPasswordForm from '../components/AccountPasswordForm.vue'
import { currentUser, logout } from '../stores/auth'
const router = useRouter()
async function exit() { await logout(); await router.replace('/login') }
</script>
<template>
  <main class="password-required-page">
    <div class="password-required-header"><RouterLink to="/" aria-label="学校官网"><SchoolIdentity /></RouterLink><button class="text-button" @click="exit">退出登录</button></div>
    <section class="portal-panel password-required-panel">
      <span class="eyebrow">账号安全</span><h1>设置你自己的登录密码</h1>
      <p>{{ currentUser?.fullName }}，你的账号使用初始密码或管理员重置的密码。请先完成修改，再进入校园服务。</p>
      <AccountPasswordForm stacked />
    </section>
  </main>
</template>
<style scoped>
.password-required-page { min-height: 100dvh; background: #f3f6f9; padding: clamp(20px, 4vw, 48px); }
.password-required-header { display: flex; justify-content: space-between; align-items: center; gap: 16px; max-width: 960px; margin: 0 auto 32px; }
.password-required-panel { max-width: 680px; padding: clamp(22px, 4vw, 40px); margin: auto; }
.password-required-panel h1 { font-size: clamp(24px, 4vw, 32px); margin: 12px 0; }
</style>
