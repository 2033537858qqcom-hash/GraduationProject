<script setup lang="ts">
import { ArrowUpRight, MapPin } from '@lucide/vue'
import { schoolHistory, schoolHistorySource } from '../content/schoolHistory'
import type { School } from '../types'
import type { SchoolPhoto } from '../content/schoolMedia'
import CulturalOrnament from './CulturalOrnament.vue'
import CulturalFrame from './CulturalFrame.vue'

defineProps<{
  summary: string
  media: SchoolPhoto
  campuses: School['campuses']
}>()
</script>

<template>
  <div class="school-story">
    <CulturalFrame :bottom="false" />
    <CulturalOrnament motif="cloud" class="story-cloud" />
    <CulturalOrnament motif="birds" class="story-birds" />
    <div class="chapter-body page-width school-story-inner">
      <div class="story-composition">
        <div class="story-introduction">
          <span class="eyebrow">ABOUT ZUIT · SINCE 1997</span>
          <h2 class="chapter-title">文脉相承<br>笃行向新</h2>
          <p class="story-summary">{{ summary }}</p>
          <div v-if="campuses.length" class="story-campuses" aria-label="学校校区">
            <MapPin :size="14" aria-hidden="true" /><span v-for="campus in campuses" :key="campus.id">{{ campus.name }}</span>
          </div>
          <RouterLink class="chapter-link" to="/content/school-introduction">认识郑州工业应用技术学院 <ArrowUpRight :size="15" aria-hidden="true" /></RouterLink>
        </div>
        <figure class="chapter-visual">
          <div class="story-photo-frame campus-photo">
            <img :src="media.url" :alt="media.alt" :width="media.width" :height="media.height" loading="lazy" decoding="async" />
          </div>
          <figcaption class="campus-caption"><span>{{ media.label }}</span><small><a :href="media.sourceUrl" target="_blank" rel="noopener noreferrer">学校官网影像 <ArrowUpRight :size="12" aria-hidden="true" /></a></small></figcaption>
        </figure>
      </div>
      <div class="story-history">
        <div class="story-history-heading"><span>时光为序</span><small>HISTORY & MILESTONES</small></div>
        <ol class="story-timeline" aria-label="学校历史沿革">
          <li v-for="event in schoolHistory" :key="event.year">
            <span class="story-year">{{ event.year }}</span>
            <div><h3>{{ event.title }}</h3><p>{{ event.detail }}</p></div>
          </li>
        </ol>
        <a class="story-source" :href="schoolHistorySource.url" target="_blank" rel="noopener noreferrer">{{ schoolHistorySource.label }} <ArrowUpRight :size="12" aria-hidden="true" /></a>
      </div>
    </div>
  </div>
</template>

<style scoped>
.school-story {
  position: relative;
  isolation: isolate;
  background: var(--site-surface);
  color: var(--site-ink);
}
.story-birds { width: 96px; left: 36%; top: 62px; --culture-opacity: .25; }
.story-cloud { width: 120px; left: 22%; top: 55px; --culture-opacity: .11; }
.school-story-inner { position: relative; z-index: 1; padding-block: 76px 32px; gap: 24px; }
.story-composition { display: grid; grid-template-columns: .82fr 1.18fr; gap: 5%; align-items: center; }
.story-introduction { min-width: 0; }
.story-summary { max-width: 390px; color: var(--muted); font-size: 13px; line-height: 2.2; margin-bottom: 15px; }
.story-campuses { display: flex; align-items: center; gap: 13px; color: var(--muted); font-size: 11px; }
.story-campuses span + span { padding-left: 13px; border-left: 1px solid var(--line); }
.story-photo-frame img { display: block; width: 100%; height: 100%; object-fit: cover; object-position: 65% 50%; }
.story-photo-frame { height: clamp(210px,21vw,300px); }
.story-history { padding-top: 20px; border-top: 1px solid var(--line); }
.story-history-heading { display: flex; align-items: center; gap: 16px; color: var(--muted); }
.story-history-heading > span { font-family: var(--site-serif); font-size: 15px; letter-spacing: 2px; color: var(--brand); }
.story-history-heading small { font-size: 10px; letter-spacing: 1.3px; }
.story-timeline { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 30px; list-style: none; padding: 0; margin: 16px 0 8px; }
.story-timeline li { display: flex; align-items: flex-start; gap: 18px; min-width: 0; }
.story-year { display: block; color: var(--brand); font-family: Georgia,"Times New Roman",serif; font-size: clamp(28px,3vw,42px); font-variant-numeric: lining-nums; line-height: 1.05; letter-spacing: -1px; }
.story-timeline h3 { font-size: 12px; margin: 0 0 6px; font-weight: 500; }
.story-timeline p { color: var(--muted); font-size: 11px; line-height: 1.8; margin: 0; }
.story-source { display: flex; align-items: center; gap: 6px; width: fit-content; margin-left: auto; padding-top: 10px; color: var(--muted); font-size: 11px; }
.story-source:hover { color: var(--brand); }
@media (max-width: 1100px) {
  .story-composition { gap: 5%; }
  .story-timeline { gap: 22px; }
  .story-timeline li { flex-direction: column; gap: 10px; }
}
@media (max-width: 760px) {
  .story-cloud { width: 100px; left: auto; right: -8px; top: -10px; --culture-opacity: .11; }
  .story-birds { width: 72px; left: auto; right: 6%; top: 14px; --culture-opacity: .2; }
  .school-story-inner { padding-block: 30px 40px; gap: 24px; }
  .story-composition { grid-template-columns: 1fr; gap: 26px; }
  .story-photo-frame { height: 210px; }
  .story-summary { max-width: 100%; font-size: 12px; }
  .story-history { padding-top: 16px; }
  .story-timeline { grid-template-columns: 1fr; gap: 0; margin-top: 18px; }
  .story-timeline li { position: relative; display: grid; grid-template-columns: 82px 1fr; align-items: start; gap: 20px; padding: 0 0 23px; }
  .story-timeline li:last-child { padding-bottom: 4px; }
  .story-timeline li::before { content: ""; position: absolute; left: 83px; top: 5px; bottom: -5px; width: 1px; background: var(--line); }
  .story-timeline li:last-child::before { bottom: 7px; }
  .story-year { font-size: 31px; }
  .story-timeline h3 { font-size: 13px; }
  .story-timeline p { font-size: 12px; }
  .story-source { font-size: 11px; margin-top: 8px; margin-left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .school-story-inner { opacity: 1; transform: none; }
}
</style>
