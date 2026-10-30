<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref } from 'vue'
import { ArrowUpRight, List, X } from '@lucide/vue'
import { activeChapter, chapters } from '../lib/publicNavigation'

const emit = defineEmits<{ select: [index: number] }>()
const dialog = ref<HTMLDialogElement>()
const trigger = ref<HTMLButtonElement>()
const opened = ref(false)

async function open() {
  dialog.value?.showModal()
  opened.value = true
  await nextTick()
  dialog.value?.querySelector<HTMLAnchorElement>('[aria-current="location"]')?.focus()
}
function close() { dialog.value?.close() }
function closed() {
  opened.value = false
  trigger.value?.focus({ preventScroll: true })
}
function select(event: MouseEvent, index: number) {
  if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  close()
  emit('select', index)
}
onBeforeUnmount(() => { if (dialog.value?.open) dialog.value.close() })
</script>

<template>
  <div class="chapter-directory">
    <button ref="trigger" class="chapter-directory-trigger" aria-haspopup="dialog" aria-controls="campus-chapter-directory" :aria-expanded="opened" :aria-label="'打开章节目录，当前' + (chapters.find(item => item.id === activeChapter)?.name ?? '首页')" @click="open"><span>{{ String(chapters.findIndex(item => item.id === activeChapter) + 1).padStart(2, '0') }} / {{ String(chapters.length).padStart(2, '0') }}</span><span>{{ chapters.find(item => item.id === activeChapter)?.name }}</span><List :size="15" /></button>
    <dialog id="campus-chapter-directory" ref="dialog" class="chapter-directory-dialog" aria-labelledby="chapter-directory-title" @close="closed" @click.self="close">
      <div class="directory-sheet">
        <div class="directory-heading"><div><span>BROWSE CAMPUS</span><h2 id="chapter-directory-title">章节目录</h2></div><button class="directory-close" aria-label="关闭章节目录" @click="close"><X :size="20" /></button></div>
        <nav class="directory-chapters" aria-label="学校官网章节"><a v-for="(chapter,index) in chapters" :key="chapter.id" :href="chapter.id === 'home' ? '/' : '/?chapter=' + chapter.id" :aria-current="chapter.id === activeChapter ? 'location' : undefined" @click="select($event, index)"><small>{{ String(index + 1).padStart(2, '0') }}</small><span>{{ chapter.name }}</span><ArrowUpRight :size="14" /></a></nav>
        <p class="directory-hint">选择章节，继续校园漫游。</p>
      </div>
    </dialog>
  </div>
</template>

<style scoped>
.chapter-directory-trigger { display: inline-flex; align-items: center; justify-content: space-between; gap: 13px; min-height: 30px; border: 0; border-radius: 0; padding: 0 5px; background: none; color: inherit; cursor: pointer; }
.chapter-directory-trigger > span:first-child { font-size: 10px; letter-spacing: 1px; font-variant-numeric: tabular-nums; }
.chapter-directory-trigger > span:nth-child(2) { font-size: 11px; }
.chapter-directory-dialog { width: min(430px, calc(100vw - 36px)); max-height: min(84svh, 760px); padding: 0; border: 1px solid #c5cdbb; border-radius: 0; color: #315244; background: #f3f1eb; box-shadow: 0 24px 80px #102a2d26; overflow-y: auto; }
.chapter-directory-dialog::backdrop { background: #102a2d70; backdrop-filter: blur(3px); }
.directory-sheet { padding: 25px 30px 18px; }
.directory-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; padding-bottom: 20px; }
.directory-heading span { font-size: 9px; letter-spacing: 2px; color: #879575; }
.directory-heading h2 { font-family: var(--site-serif); font-size: 25px; font-weight: 400; letter-spacing: 3px; margin: 10px 0 0; }
.directory-close { display: grid; place-items: center; width: 32px; height: 32px; padding: 0; border: 0; border-radius: 0; color: inherit; background: none; }
.directory-chapters { display: grid; }
.directory-chapters a { display: grid; grid-template-columns: 24px 1fr 18px; align-items: center; gap: 16px; min-height: 47px; border-top: 1px solid #cdd4c4; font-size: 13px; }
.directory-chapters small { color: #899679; font-size: 10px; font-variant-numeric: tabular-nums; }
.directory-chapters a[aria-current="location"] { color: #2c5739; background: #c4ceb129; }
.directory-chapters a[aria-current="location"] > span { font-weight: 600; }
.directory-hint { margin: 20px 0 0; color: #809071; font-size: 10px; letter-spacing: 1px; }
.chapter-directory a:focus-visible, .chapter-directory button:focus-visible { outline: 2px solid #94a47c; outline-offset: 3px; }
@media (max-width: 760px) {
  .chapter-directory-trigger { gap: 10px; }
  .chapter-directory-trigger > span:nth-child(2) { font-size: 10px; }
  .directory-sheet { padding: 22px 24px 18px; }
  .directory-chapters a { min-height: 45px; }
}
</style>
