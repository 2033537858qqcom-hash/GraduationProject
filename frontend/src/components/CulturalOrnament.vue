<script setup lang="ts">
import { defineAsyncComponent } from 'vue';
import type { SecondaryMotif } from './SecondaryCulturalOrnament.vue';

defineProps<{
  motif: 'landscape' | 'corner' | SecondaryMotif;
  preserveAspectRatio?: string;
}>();

const SecondaryCulturalOrnament = defineAsyncComponent(() => import('./SecondaryCulturalOrnament.vue'));
</script>

<template>
  <svg
    v-if="motif === 'landscape'"
    class="cultural-ornament cultural-landscape"
    viewBox="0 0 1200 240"
    :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'"
    aria-hidden="true"
    focusable="false"
  >
    <g fill="currentColor" fill-opacity=".16" stroke="none">
      <path
        d="M0 193C77 185 127 169 186 157C226 148 255 99 280 78C304 103 323 135 353 143C395 157 419 141 457 117C485 99 510 47 535 35C562 59 584 98 622 112C663 129 690 117 726 138C766 163 803 151 843 132C878 115 899 73 922 61C951 84 972 130 1010 151C1061 179 1119 163 1200 189V240H0Z"
      />
      <path
        d="M0 216C87 207 126 218 180 192C211 177 232 162 254 163C285 164 302 190 335 188C389 185 406 151 440 143C472 154 500 188 548 186C594 184 617 146 641 144C671 148 693 183 736 182C786 181 816 204 869 199C927 192 970 167 1007 183C1056 205 1098 206 1200 215V240H0Z"
      />
    </g>
    <g fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
      <path
        d="M0 193C77 185 127 169 186 157C226 148 255 99 280 78C304 103 323 135 353 143C395 157 419 141 457 117C485 99 510 47 535 35C562 59 584 98 622 112C663 129 690 117 726 138C766 163 803 151 843 132C878 115 899 73 922 61C951 84 972 130 1010 151C1061 179 1119 163 1200 189"
      />
      <path
        d="M194 167C227 148 250 125 280 78L273 115L289 110L311 149M465 127C489 100 509 80 535 35L525 78L540 70L565 114M842 149C873 121 897 99 922 61L916 102L931 96L953 137"
      />
      <path
        d="M244 155L268 132M253 166L283 139M504 132L521 102M515 145L538 108M889 154L910 128M901 164L926 134"
        stroke-opacity=".48"
      />
      <path
        d="M24 213C90 211 126 218 180 192C211 177 232 162 254 163C285 164 302 190 335 188C389 185 406 151 440 143C472 154 500 188 548 186C594 184 617 146 641 144C671 148 693 183 736 182C786 181 816 204 869 199C927 192 970 167 1007 183C1056 205 1098 206 1180 215"
        stroke-opacity=".62"
      />
      <path
        d="M47 227C98 222 138 225 192 228M267 214C322 209 360 211 397 214M443 231C514 226 587 227 658 231M708 215C747 212 776 215 814 219M884 233C944 229 999 228 1060 231"
        stroke-opacity=".55"
      />
      <path
        d="M346 96H401C414 96 419 89 415 84C411 78 400 80 400 87M646 68H708C724 68 730 59 725 52C718 43 702 47 702 56M726 80H762"
        stroke-opacity=".65"
      />
    </g>
  </svg>
  <svg
    v-else-if="motif === 'corner'"
    class="cultural-ornament cultural-corner"
    viewBox="0 0 80 80"
    aria-hidden="true"
    focusable="false"
  >
    <g fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="square" stroke-linejoin="miter">
      <path d="M8 72V8H72M18 54V18H54" />
      <path d="M8 36H36V8M18 27H27V18" />
      <path d="M8 62V72H18M62 8H72V18" stroke-opacity=".65" />
    </g>
  </svg>
  <SecondaryCulturalOrnament v-else :motif="motif" :preserve-aspect-ratio="preserveAspectRatio" />
</template>

<style scoped>
.cultural-ornament {
  position: absolute;
  display: block;
  color: var(--culture-color, var(--brand));
  opacity: var(--culture-opacity, 0.2);
  pointer-events: none;
  user-select: none;
}
.cultural-landscape {
  mask-image: linear-gradient(90deg, transparent, #000 9%, #000 91%, transparent);
}
</style>
