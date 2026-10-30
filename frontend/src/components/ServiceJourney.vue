<script setup lang="ts">
import { computed } from 'vue'
import { Compass, Info } from '@lucide/vue'
import { serviceJourney, type ServiceKind } from '../lib/serviceJourney'

const props = defineProps<{ kind: ServiceKind; status?: string; preparing?: boolean; reviewer?: boolean; cancellationPending?: boolean; loading?: boolean }>()
const journey = computed(() => serviceJourney(props.kind, props.status, props))
</script>
<template>
  <section class="service-journey" aria-label="办理路径与当前提示" :aria-busy="loading" :data-tone="loading ? 'neutral' : journey.tone">
    <div class="journey-title"><span><Compass :size="15" aria-hidden="true" />办理路径</span><small>{{ loading ? '正在核对记录…' : journey.label }}</small></div>
    <ol><li v-for="(step, index) in journey.steps" :key="step" :aria-current="!loading && journey.index === index ? 'step' : undefined"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ step }}</li></ol>
    <p v-if="!loading"><Info :size="14" aria-hidden="true" /><span>{{ journey.note }}</span></p>
  </section>
</template>
