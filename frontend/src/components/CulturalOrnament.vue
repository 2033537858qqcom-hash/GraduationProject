<script setup lang="ts">
defineProps<{
  motif: 'landscape' | 'cloud' | 'corner' | 'birds'
  preserveAspectRatio?: string
}>()
</script>

<template>
  <svg v-if="motif === 'landscape'" class="cultural-ornament cultural-landscape" viewBox="0 0 1200 240" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="currentColor" fill-opacity=".16" stroke="none">
      <path d="M0 193C77 185 127 169 186 157C226 148 255 99 280 78C304 103 323 135 353 143C395 157 419 141 457 117C485 99 510 47 535 35C562 59 584 98 622 112C663 129 690 117 726 138C766 163 803 151 843 132C878 115 899 73 922 61C951 84 972 130 1010 151C1061 179 1119 163 1200 189V240H0Z" />
      <path d="M0 216C87 207 126 218 180 192C211 177 232 162 254 163C285 164 302 190 335 188C389 185 406 151 440 143C472 154 500 188 548 186C594 184 617 146 641 144C671 148 693 183 736 182C786 181 816 204 869 199C927 192 970 167 1007 183C1056 205 1098 206 1200 215V240H0Z" />
    </g>
    <g fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round">
      <path d="M0 193C77 185 127 169 186 157C226 148 255 99 280 78C304 103 323 135 353 143C395 157 419 141 457 117C485 99 510 47 535 35C562 59 584 98 622 112C663 129 690 117 726 138C766 163 803 151 843 132C878 115 899 73 922 61C951 84 972 130 1010 151C1061 179 1119 163 1200 189" />
      <path d="M194 167C227 148 250 125 280 78L273 115L289 110L311 149M465 127C489 100 509 80 535 35L525 78L540 70L565 114M842 149C873 121 897 99 922 61L916 102L931 96L953 137" />
      <path d="M244 155L268 132M253 166L283 139M504 132L521 102M515 145L538 108M889 154L910 128M901 164L926 134" stroke-opacity=".48" />
      <path d="M24 213C90 211 126 218 180 192C211 177 232 162 254 163C285 164 302 190 335 188C389 185 406 151 440 143C472 154 500 188 548 186C594 184 617 146 641 144C671 148 693 183 736 182C786 181 816 204 869 199C927 192 970 167 1007 183C1056 205 1098 206 1180 215" stroke-opacity=".62" />
      <path d="M47 227C98 222 138 225 192 228M267 214C322 209 360 211 397 214M443 231C514 226 587 227 658 231M708 215C747 212 776 215 814 219M884 233C944 229 999 228 1060 231" stroke-opacity=".55" />
      <path d="M346 96H401C414 96 419 89 415 84C411 78 400 80 400 87M646 68H708C724 68 730 59 725 52C718 43 702 47 702 56M726 80H762" stroke-opacity=".65" />
    </g>
  </svg>
  <svg v-else-if="motif === 'cloud'" class="cultural-ornament cultural-cloud" viewBox="0 0 420 180" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round">
      <path d="M18 124H252C286 124 312 108 312 82C312 61 296 47 276 47C255 47 241 60 241 77C241 90 251 100 264 100C274 100 282 93 282 85C282 78 277 74 271 74" />
      <path d="M98 108H192C215 108 230 93 230 72C230 57 220 47 205 44C205 20 188 8 168 8C146 8 130 24 130 44C107 37 87 53 87 73C87 88 99 97 111 97C122 97 130 89 130 80C130 72 124 67 118 67" />
      <path d="M193 24C182 16 160 22 153 36C146 51 158 68 173 68C185 68 194 61 194 51C194 41 186 34 178 34C170 34 165 40 165 47C165 54 171 58 177 56" stroke-opacity=".78" />
      <path d="M48 140H255C294 140 327 122 327 86C327 72 322 62 314 54" stroke-opacity=".56" />
      <path d="M265 150H350C379 150 400 135 400 113C400 96 388 84 372 84C356 84 346 94 346 108C346 119 354 126 364 126C373 126 379 120 379 114C379 109 375 106 371 106" stroke-opacity=".72" />
      <path d="M154 160H204M320 169H383M35 95H62M298 26H333C344 26 350 20 348 13C346 6 335 5 330 11" stroke-opacity=".48" />
    </g>
  </svg>
  <svg v-else-if="motif === 'birds'" class="cultural-ornament cultural-birds" viewBox="0 0 112 44" aria-hidden="true" focusable="false">
    <path d="M8 28q7-2 11 3 4-6 11-8M43 16q7 0 11 6 4-8 13-10M81 28q5-1 8 3 3-5 9-7" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
  <svg v-else class="cultural-ornament cultural-corner" viewBox="0 0 80 80" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="square" stroke-linejoin="miter">
      <path d="M8 72V8H72M18 54V18H54" />
      <path d="M8 36H36V8M18 27H27V18" />
      <path d="M8 62V72H18M62 8H72V18" stroke-opacity=".65" />
    </g>
  </svg>
</template>

<style scoped>
.cultural-ornament {
  position: absolute;
  display: block;
  color: var(--culture-color, var(--brand));
  opacity: var(--culture-opacity, .2);
  pointer-events: none;
  user-select: none;
}
.cultural-landscape { mask-image: linear-gradient(90deg,transparent,#000 9%,#000 91%,transparent); }
</style>
