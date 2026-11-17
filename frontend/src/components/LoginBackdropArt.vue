<script setup lang="ts">
import type { CampusAtmosphere } from '../lib/campusTheme';
import CulturalOrnament from './CulturalOrnament.vue';

withDefaults(
  defineProps<{
    side: 'dark' | 'paper';
    theme?: CampusAtmosphere;
  }>(),
  {
    theme: 'day',
  },
);

// Dense intertwined Guilloché wave clusters for rich vector engraving texture
const darkRibbons = Array.from({ length: 24 }, (_, i) => {
  const t = i / 23;
  const y0 = 510 + t * 290;
  const c1y = 330 + Math.sin(t * Math.PI * 1.15) * 115 + t * 145;
  const c2y = 780 - Math.cos(t * Math.PI * 0.9) * 140 + t * 85;
  const y1 = 380 + t * 340;
  const opacity = (0.09 + Math.sin(t * Math.PI) * 0.24).toFixed(3);
  const width = (0.65 + Math.sin(t * Math.PI) * 0.55).toFixed(2);
  return {
    d: `M -40 ${y0.toFixed(1)} C 170 ${c1y.toFixed(1)}, 360 ${c2y.toFixed(1)}, 640 ${y1.toFixed(1)}`,
    opacity,
    width,
  };
});

const darkCounterRibbons = Array.from({ length: 12 }, (_, i) => {
  const t = i / 11;
  const x0 = -30 + t * 160;
  const y1 = 190 + t * 240;
  const opacity = (0.06 + Math.sin(t * Math.PI) * 0.14).toFixed(3);
  return {
    d: `M ${x0.toFixed(1)} 920 C 180 ${(690 - t * 90).toFixed(1)}, 390 ${(490 + t * 65).toFixed(1)}, 640 ${y1.toFixed(1)}`,
    opacity,
  };
});

const paperUpperRibbons = Array.from({ length: 20 }, (_, i) => {
  const t = i / 19;
  const xStart = 240 + t * 260;
  const yEnd = 170 + t * 260;
  const c1x = 490 + Math.cos(t * 2.4) * 75;
  const c1y = 55 + t * 120;
  const c2x = 760 + Math.sin(t * 2.6) * 80;
  const c2y = 140 + t * 140;
  const opacity = (0.075 + Math.sin(t * Math.PI) * 0.165).toFixed(3);
  return {
    d: `M ${xStart.toFixed(1)} -24 C ${c1x.toFixed(1)} ${c1y.toFixed(1)}, ${c2x.toFixed(1)} ${c2y.toFixed(1)}, 1040 ${yEnd.toFixed(1)}`,
    opacity,
  };
});

const paperLeftRibbons = Array.from({ length: 14 }, (_, i) => {
  const t = i / 13;
  const y0 = 120 + t * 240;
  const c1x = 130 + Math.sin(t * Math.PI) * 70;
  const c2x = 290 + Math.cos(t * Math.PI) * 60;
  const opacity = (0.06 + Math.sin(t * Math.PI) * 0.13).toFixed(3);
  return {
    d: `M -24 ${y0.toFixed(1)} C ${c1x.toFixed(1)} ${(y0 - 95).toFixed(1)}, ${c2x.toFixed(1)} ${(y0 + 125).toFixed(1)}, 520 ${(360 + t * 190).toFixed(1)}`,
    opacity,
  };
});

const paperLowerRibbons = Array.from({ length: 24 }, (_, i) => {
  const t = i / 23;
  const y0 = 590 + t * 220;
  const c1y = 480 + Math.sin(t * Math.PI * 1.15) * 95 + t * 145;
  const c2y = 855 - Math.cos(t * Math.PI) * 105 + t * 75;
  const y1 = 510 + t * 285;
  const opacity = (0.075 + Math.sin(t * Math.PI) * 0.165).toFixed(3);
  return {
    d: `M -40 ${y0.toFixed(1)} C 280 ${c1y.toFixed(1)}, 680 ${c2y.toFixed(1)}, 1040 ${y1.toFixed(1)}`,
    opacity,
  };
});

const radialTicks = Array.from({ length: 24 }, (_, i) => {
  const angle = (i * 15 * Math.PI) / 180;
  const r1 = i % 3 === 0 ? 112 : 120;
  const r2 = 130;
  return {
    x1: (240 + Math.cos(angle) * r1).toFixed(1),
    y1: (120 + Math.sin(angle) * r1).toFixed(1),
    x2: (240 + Math.cos(angle) * r2).toFixed(1),
    y2: (120 + Math.sin(angle) * r2).toFixed(1),
    major: i % 3 === 0,
  };
});
</script>

<template>
  <div v-if="side === 'dark'" class="backdrop-art backdrop-art--dark" :class="`is-${theme}`" aria-hidden="true">
    <svg class="backdrop-draft-grid" viewBox="0 0 600 900" preserveAspectRatio="none" fill="none">
      <g stroke="currentColor" stroke-width="0.6">
        <path d="M52 0V900M548 0V900M0 108H600M0 788H600" opacity="0.12" />
        <path d="M298 0V900" stroke-dasharray="3 7" opacity="0.08" />
        <path d="M0 440C200 390 400 490 600 440" stroke-dasharray="6 5" opacity="0.12" />
      </g>
    </svg>

    <svg class="backdrop-celestial" viewBox="0 0 380 380" fill="none">
      <g stroke="currentColor" stroke-linecap="round">
        <circle cx="240" cy="120" r="158" stroke-width="0.7" stroke-dasharray="3 5" opacity="0.26" />
        <circle cx="240" cy="120" r="130" stroke-width="0.95" opacity="0.34" />
        <circle cx="240" cy="120" r="96" stroke-width="0.7" stroke-dasharray="10 4 2 4" opacity="0.28" />
        <circle cx="240" cy="120" r="62" stroke-width="0.85" opacity="0.3" />
        <circle cx="240" cy="120" r="32" stroke-width="0.65" stroke-dasharray="2 3" opacity="0.25" />
        <line
          v-for="(tick, idx) in radialTicks"
          :key="'t' + idx"
          :x1="tick.x1"
          :y1="tick.y1"
          :x2="tick.x2"
          :y2="tick.y2"
          :stroke-width="tick.major ? 1 : 0.65"
          :opacity="tick.major ? 0.38 : 0.2"
        />
        <ellipse cx="240" cy="120" rx="176" ry="58" transform="rotate(-28 240 120)" stroke-width="0.9" opacity="0.34" />
        <ellipse
          cx="240"
          cy="120"
          rx="166"
          ry="46"
          transform="rotate(24 240 120)"
          stroke-width="0.75"
          stroke-dasharray="5 5"
          opacity="0.26"
        />
        <ellipse cx="240" cy="120" rx="144" ry="34" transform="rotate(64 240 120)" stroke-width="0.65" opacity="0.2" />
        <path d="M240 -52V292M68 120H412" stroke-width="0.65" stroke-dasharray="2 6" opacity="0.22" />
        <circle cx="132" cy="182" r="2.8" fill="currentColor" opacity="0.65" />
        <circle cx="326" cy="64" r="2.2" fill="currentColor" opacity="0.6" />
        <circle cx="240" cy="120" r="3.2" fill="currentColor" opacity="0.7" />
      </g>
    </svg>

    <svg class="backdrop-ribbons-dark" viewBox="0 0 600 900" preserveAspectRatio="none" fill="none">
      <defs>
        <linearGradient id="darkSilkFill" x1="0%" y1="100%" x2="100%" y2="0%">
          <stop offset="0%" stop-color="currentColor" stop-opacity="0.16" />
          <stop offset="55%" stop-color="currentColor" stop-opacity="0.05" />
          <stop offset="100%" stop-color="currentColor" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path d="M-40 510 C 170 330, 360 640, 640 380 L 640 900 L -40 900 Z" fill="url(#darkSilkFill)" />
      <path
        v-for="(line, idx) in darkCounterRibbons"
        :key="'dc' + idx"
        :d="line.d"
        stroke="currentColor"
        stroke-width="0.6"
        :stroke-opacity="line.opacity"
        stroke-dasharray="4 4"
      />
      <path
        v-for="(line, idx) in darkRibbons"
        :key="'dr' + idx"
        :d="line.d"
        stroke="currentColor"
        :stroke-width="line.width"
        :stroke-opacity="line.opacity"
      />
    </svg>

    <CulturalOrnament motif="landscape" class="backdrop-landscape" preserve-aspect-ratio="xMidYMax slice" />
  </div>

  <div v-else class="backdrop-art backdrop-art--paper" :class="`is-${theme}`" aria-hidden="true">
    <div class="backdrop-plate-frame">
      <CulturalOrnament motif="corner" class="backdrop-corner-tl" />
      <CulturalOrnament motif="corner" class="backdrop-corner-tr" />
      <CulturalOrnament motif="corner" class="backdrop-corner-bl" />
      <CulturalOrnament motif="corner" class="backdrop-corner-br" />
    </div>

    <svg class="backdrop-ribbons-paper" viewBox="0 0 1000 900" preserveAspectRatio="none" fill="none">
      <defs>
        <linearGradient id="paperWashTop" x1="100%" y1="0%" x2="20%" y2="65%">
          <stop offset="0%" stop-color="var(--login-accent)" stop-opacity="0.16" />
          <stop offset="65%" stop-color="var(--login-accent)" stop-opacity="0.04" />
          <stop offset="100%" stop-color="var(--login-accent)" stop-opacity="0" />
        </linearGradient>
        <linearGradient id="paperWashBottom" x1="0%" y1="100%" x2="100%" y2="25%">
          <stop offset="0%" stop-color="var(--login-wave-ink)" stop-opacity="0.12" />
          <stop offset="65%" stop-color="var(--login-wave-ink)" stop-opacity="0.04" />
          <stop offset="100%" stop-color="var(--login-wave-ink)" stop-opacity="0" />
        </linearGradient>
      </defs>
      <path d="M 240 -24 C 490 55, 760 140, 1040 170 L 1040 -24 Z" fill="url(#paperWashTop)" />
      <path
        v-for="(line, idx) in paperUpperRibbons"
        :key="'pu' + idx"
        :d="line.d"
        stroke="var(--login-accent)"
        stroke-width="0.9"
        :stroke-opacity="line.opacity"
      />
      <path
        v-for="(line, idx) in paperLeftRibbons"
        :key="'pl' + idx"
        :d="line.d"
        stroke="var(--login-wave-ink)"
        stroke-width="0.7"
        :stroke-opacity="line.opacity"
      />
      <path d="M -40 590 C 280 480, 680 750, 1040 510 L 1040 920 L -40 920 Z" fill="url(#paperWashBottom)" />
      <path
        v-for="(line, idx) in paperLowerRibbons"
        :key="'pb' + idx"
        :d="line.d"
        stroke="var(--login-wave-ink)"
        stroke-width="0.9"
        :stroke-opacity="line.opacity"
      />
    </svg>

    <svg class="backdrop-halo" viewBox="0 0 640 640" fill="none">
      <g stroke="currentColor" stroke-linecap="round">
        <circle cx="320" cy="320" r="296" stroke-width="0.75" stroke-dasharray="4 6" opacity="0.2" />
        <circle cx="320" cy="320" r="268" stroke-width="0.9" opacity="0.14" />
        <ellipse
          cx="320"
          cy="320"
          rx="310"
          ry="170"
          transform="rotate(-18 320 320)"
          stroke-width="0.7"
          stroke-dasharray="8 6"
          opacity="0.15"
        />
        <ellipse cx="320" cy="320" rx="300" ry="140" transform="rotate(22 320 320)" stroke-width="0.7" opacity="0.12" />
      </g>
    </svg>

    <CulturalOrnament motif="cloud" class="backdrop-cloud" />
    <CulturalOrnament motif="sundial" class="backdrop-sundial" preserve-aspect-ratio="xMidYMid slice" />
    <CulturalOrnament motif="wenmai" class="backdrop-wenmai" preserve-aspect-ratio="xMidYMax slice" />
  </div>
</template>

<style scoped>
.backdrop-art {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  user-select: none;
}

.backdrop-art--dark {
  color: var(--login-accent, var(--color-gold));
}

.backdrop-draft-grid,
.backdrop-ribbons-dark,
.backdrop-ribbons-paper {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.backdrop-celestial {
  position: absolute;
  top: -32px;
  right: -46px;
  width: 360px;
  height: 360px;
  color: var(--login-accent, var(--color-gold));
}

.backdrop-landscape {
  inset: auto -10% 38px;
  width: 120%;
  height: 205px;

  --culture-color: var(--login-accent, var(--color-gold));
  --culture-opacity: 0.34;
}

.backdrop-art--paper {
  color: var(--login-wave-ink, var(--color-brand));
}

.backdrop-plate-frame {
  position: absolute;
  inset: 22px;
  border: 1px solid var(--login-plate-border, var(--color-border-subtle));
  border-radius: var(--radius-xs);
  transition: border-color 480ms var(--ease-campus);
}

.backdrop-corner-tl,
.backdrop-corner-tr,
.backdrop-corner-bl,
.backdrop-corner-br {
  width: 66px;
  height: 66px;
}

.backdrop-corner-tl {
  top: 10px;
  left: 10px;

  --culture-color: var(--login-wave-ink, var(--color-brand));
  --culture-opacity: 0.24;
}

.backdrop-corner-tr {
  top: 10px;
  right: 10px;
  transform: rotate(90deg);

  --culture-color: var(--login-accent, var(--color-gold));
  --culture-opacity: 0.44;
}

.backdrop-corner-bl {
  bottom: 10px;
  left: 10px;
  transform: rotate(-90deg);

  --culture-color: var(--login-wave-ink, var(--color-brand));
  --culture-opacity: 0.24;
}

.backdrop-corner-br {
  right: 10px;
  bottom: 10px;
  transform: rotate(180deg);

  --culture-color: var(--login-accent, var(--color-gold));
  --culture-opacity: 0.4;
}

.backdrop-halo {
  position: absolute;
  top: 50%;
  left: 50%;
  width: min(640px, 86%);
  height: auto;
  transform: translate(-50%, -50%);
  color: var(--login-accent, var(--color-gold));
}

.backdrop-cloud {
  top: 38px;
  right: 68px;
  width: 300px;

  --culture-color: var(--login-accent, var(--color-gold));
  --culture-opacity: 0.26;
}

.backdrop-sundial {
  top: 46px;
  left: 50%;
  width: min(720px, 92%);
  transform: translateX(-50%);

  --culture-color: var(--login-wave-ink, var(--color-brand));
  --culture-opacity: 0.1;
}

.backdrop-wenmai {
  bottom: 0;
  left: 0;
  width: 100%;
  height: 230px;

  --culture-color: var(--login-wave-ink, var(--color-brand));
  --culture-opacity: 0.19;
}

@media (max-width: 760px) {
  .backdrop-celestial,
  .backdrop-cloud,
  .backdrop-sundial,
  .backdrop-halo,
  .backdrop-corner-tl,
  .backdrop-corner-tr,
  .backdrop-corner-bl,
  .backdrop-corner-br {
    display: none;
  }

  .backdrop-plate-frame {
    inset: 10px;
  }

  .backdrop-landscape {
    bottom: 0;
    height: 130px;

    --culture-opacity: 0.22;
  }

  .backdrop-wenmai {
    height: 140px;

    --culture-opacity: 0.12;
  }
}
</style>
