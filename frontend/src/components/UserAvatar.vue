<script setup lang="ts">
import { computed, ref, watch } from 'vue';

const props = withDefaults(
  defineProps<{
    src?: string | null;
    name?: string;
    size?: 'sm' | 'md' | 'lg' | 'xl';
  }>(),
  {
    src: null,
    name: '',
    size: 'md',
  },
);

const failed = ref(false);

watch(
  () => props.src,
  () => {
    failed.value = false;
  },
);

const initial = computed(() => {
  const text = (props.name || '').replace(/[（(]演示[)）]\s*$/u, '').trim();
  if (!text) return '学';
  if (/^[\u4e00-\u9fa5]+$/.test(text)) {
    if (props.size === 'sm') return text.slice(0, 1);
    return text.length >= 3 ? text.slice(-2) : text;
  }
  return text.slice(0, 2).toUpperCase();
});
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
  background: linear-gradient(135deg, var(--color-brand) 0%, #446b59 100%);
  color: #f7f9f4;
  font-family: var(--font-family-body);
  font-weight: 500;
  overflow: hidden;
  box-shadow:
    0 1px 3px rgb(39 71 57 / 14%),
    inset 0 0 0 1px rgb(255 255 255 / 18%);
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
  width: 28px;
  height: 28px;
  font-size: var(--text-meta);
}

.avatar-md {
  width: 40px;
  height: 40px;
  font-size: var(--text-body);
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
  box-shadow:
    0 4px 14px rgb(41 62 52 / 16%),
    0 0 0 2px var(--color-white);
}
</style>
