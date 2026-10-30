<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowUpRight, Bell, LogOut, Menu, X } from '@lucide/vue'
import { currentUser, logout } from '../stores/auth'
import { errorMessage } from '../lib/api'
import { currentPortalNavigation, portalNavigation } from '../lib/portalNavigation'
import SchoolIdentity from '../components/SchoolIdentity.vue'
import CampusContour from '../components/CampusContour.vue'
import PortalNavigation from '../components/PortalNavigation.vue'
import AssistantChat from '../components/AssistantChat.vue'
import UserAvatar from '../components/UserAvatar.vue'
import { initializePublicMotion, publicMotionPreference, publicReducedMotion, setPublicMotionPreference } from '../lib/publicMotion'

const router = useRouter()
const route = useRoute()
const error = ref('')
const leaving = ref(false)
const opened = ref(false)
const drawer = ref<HTMLDialogElement>()
const menuButton = ref<HTMLButtonElement>()
const groups = computed(() => portalNavigation(currentUser.value?.permissions ?? []))
const selected = computed(() => currentPortalNavigation(groups.value, route.path))
const student = computed(() => currentUser.value?.permissions.includes('portal:read'))
let previousOverflow: string | undefined
let desktop: MediaQueryList | undefined
let releaseMotion = () => {}
function unlockScroll() {
  if (previousOverflow === undefined) return
  document.body.style.overflow = previousOverflow
  previousOverflow = undefined
}
async function openMenu() {
  if (!drawer.value || drawer.value.open) return
  drawer.value?.showModal()
  previousOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
  opened.value = true
  await nextTick()
  drawer.value?.querySelector<HTMLAnchorElement>('[aria-current="page"]')?.focus()
}
function closeMenu() { drawer.value?.close() }
function menuClosed() {
  unlockScroll()
  opened.value = false
  if (menuButton.value?.getClientRects().length) menuButton.value.focus({ preventScroll: true })
}
function desktopChanged() { if (desktop?.matches) closeMenu() }
watch(() => route.fullPath, closeMenu)
onMounted(() => {
  releaseMotion = initializePublicMotion()
  desktop = window.matchMedia('(min-width: 1025px)')
  desktop.addEventListener('change', desktopChanged)
})
onBeforeUnmount(() => {
  releaseMotion()
  closeMenu()
  unlockScroll()
  desktop?.removeEventListener('change', desktopChanged)
})
async function leave() {
  leaving.value = true
  error.value = ''
  try { await logout(); await router.push('/login') } catch (cause) { error.value = errorMessage(cause) }
  finally { leaving.value = false }
}
</script>

<template>
  <div class="portal-shell campus-portal" :class="{ 'public-motion-reduced': publicReducedMotion }">
    <a class="portal-skip-link" href="#portal-main">跳到主要内容</a>
    <aside class="portal-sidebar" aria-label="校园服务侧栏">
      <RouterLink to="/" class="portal-sidebar-brand" aria-label="郑州工业应用技术学院官网"><SchoolIdentity /><span>校园综合服务平台</span></RouterLink>
      <PortalNavigation :groups="groups" :active="selected?.item.to" label="校园服务导航" />
      <div class="portal-sidebar-foot"><CampusContour /><p>励学敦行 · 明理至善</p><RouterLink to="/">访问学校官网 <ArrowUpRight :size="13" /></RouterLink></div>
    </aside>
    <div class="portal-workspace">
      <header class="portal-header portal-toolbar">
        <button ref="menuButton" class="portal-menu-button" aria-label="打开服务导航" aria-haspopup="dialog" aria-controls="portal-mobile-navigation" :aria-expanded="opened" @click="openMenu"><Menu :size="21" /></button>
        <div class="portal-location"><span>{{ selected?.group.label ?? '校园服务' }}</span><strong>{{ selected?.item.label ?? route.meta.title ?? '校园综合服务平台' }}</strong></div>
        <div class="portal-account-controls">
          <button class="portal-motion-toggle" :aria-pressed="publicMotionPreference === 'reduce'" @click="setPublicMotionPreference(publicMotionPreference === 'reduce' ? 'auto' : 'reduce')"><span aria-hidden="true"></span>减少动态</button>
          <RouterLink :to="student ? '/portal/notices' : '/notices'" class="portal-account-action" aria-label="消息通知"><Bell :size="18" /><span>消息</span></RouterLink>
          <RouterLink to="/profile" class="portal-account-action portal-profile-action" :aria-label="'个人资料：' + (currentUser?.fullName ?? '')"><UserAvatar :src="currentUser?.avatarUrl" :name="currentUser?.fullName ?? ''" size="sm" /><span>{{ currentUser?.fullName }}</span></RouterLink>
          <button class="portal-account-action portal-logout" :disabled="leaving" :aria-label="leaving ? '正在退出登录' : '退出登录'" @click="leave"><LogOut :size="17" /><span>{{ leaving ? '退出中…' : '退出' }}</span></button>
        </div>
      </header>
      <p v-if="error" class="alert page-width" role="alert">{{ error }}</p>
      <main id="portal-main" class="portal-main page-width" tabindex="-1"><RouterView /></main>
      <footer class="portal-footer page-width"><span>校园综合服务平台 <small>毕业设计演示 · 个人记录为演示数据</small></span><RouterLink to="/">学校官网 <ArrowUpRight :size="12" /></RouterLink></footer>
    </div>
    <dialog id="portal-mobile-navigation" ref="drawer" class="portal-navigation-drawer" aria-labelledby="portal-drawer-title" @close="menuClosed" @click.self="closeMenu">
      <div class="portal-drawer-sheet"><div class="portal-drawer-heading"><div><span>CAMPUS SERVICES</span><h2 id="portal-drawer-title">校园服务导航</h2></div><button aria-label="关闭服务导航" @click="closeMenu"><X :size="22" /></button></div><PortalNavigation :groups="groups" :active="selected?.item.to" label="手机校园服务导航" @navigate="closeMenu" /><button class="portal-motion-toggle portal-drawer-motion" :aria-pressed="publicMotionPreference === 'reduce'" @click="setPublicMotionPreference(publicMotionPreference === 'reduce' ? 'auto' : 'reduce')"><span aria-hidden="true"></span>减少动态</button><RouterLink class="portal-drawer-home" to="/" @click="closeMenu">返回学校官网 <ArrowUpRight :size="14" /></RouterLink></div>
    </dialog>
    <AssistantChat />
  </div>
</template>
