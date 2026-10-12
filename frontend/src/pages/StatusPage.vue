<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { landingPage, currentUser } from '../stores/auth'
import CulturalFrame from '../components/CulturalFrame.vue'
import CulturalOrnament from '../components/CulturalOrnament.vue'
const route = useRoute()
const message = computed(() => route.path === '/forbidden'
  ? '当前账号没有访问此页面的权限。'
  : route.path === '/unavailable' ? '暂时无法连接校园服务，请确认后端已启动后重试。' : '你访问的页面不存在。')
</script>
<template>
  <div class="status-scene"><CulturalFrame /><CulturalOrnament motif="landscape" class="status-landscape" /><main class="status-page"><span class="eyebrow">CAMPUS SERVICES</span><h1>{{ route.meta.title }}</h1><p>{{ message }}</p><div><RouterLink class="button primary" :to="currentUser ? landingPage() : '/login'">{{ currentUser ? '个人门户' : '校园服务登录' }}</RouterLink><RouterLink class="quiet-link" to="/">学校官网</RouterLink></div></main></div>
</template>
