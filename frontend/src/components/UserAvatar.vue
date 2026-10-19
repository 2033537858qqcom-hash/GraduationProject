<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    src?: string | null
    name?: string
    size?: 'sm' | 'md' | 'lg' | 'xl'
  }>(),
  {
    src: null,
    name: '',
    size: 'md',
  }
)

const failed = ref(false)

watch(
  () => props.src,
  () => {
    failed.value = false
  }
)

const initial = computed(() => {
  const text = (props.name || '').trim()
  if (!text) return '学'
  if (/^[\u4e00-\u9fa5]+$/.test(text)) {
    return text.length >= 3 ? text.slice(-2) : text
  }
  return text.slice(0, 2).toUpperCase()
})
</script>

<template>
  <div class="user-avatar" :class="['avatar-' + size, { 'has-image': src && !failed }]">
    <img
      v-if="src && !failed"
      :src="src"
      :alt="name ? `${name} 的头像` : '用户头像'"
      class="avatar-img"
      loading="lazy"
      decoding="async"
      @error="failed = true"
    />
    <span v-else class="avatar-fallback" aria-hidden="true">{{ initial }}</span>
  </div>
</template>

<style scoped>
.user-avatar {
  position: relative;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  user-select: none;
  background: linear-gradient(135deg, #183354 0%, #274b75 100%);
  color: #f7f9fc;
  font-family: var(--site-serif, "Noto Serif SC", SimSun, serif);
  font-weight: 500;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(18, 38, 63, 0.12), inset 0 0 0 1px rgba(255, 255, 255, 0.2);
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.avatar-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  letter-spacing: 0.5px;
  line-height: 1;
}

/* Sizes */
.avatar-sm {
  width: 26px;
  height: 26px;
  font-size: 11px;
}

.avatar-md {
  width: 40px;
  height: 40px;
  font-size: 14px;
}

.avatar-lg {
  width: 64px;
  height: 64px;
  font-size: 20px;
}

.avatar-xl {
  width: 96px;
  height: 96px;
  font-size: 26px;
  box-shadow: 0 4px 14px rgba(18, 38, 63, 0.16), 0 0 0 2px #ffffff;
}
</style>
