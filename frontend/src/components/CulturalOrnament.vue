<script setup lang="ts">
defineProps<{
  motif: 'landscape' | 'cloud' | 'corner' | 'birds' | 'tech' | 'skyline' | 'sundial' | 'wenmai' | 'disciplines' | 'education' | 'research' | 'journey' | 'exchange' | 'life' | 'disclosure'
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
  <svg v-else-if="motif === 'tech'" class="cultural-ornament cultural-tech" viewBox="0 0 1200 120" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M120 60H1080" stroke-width="0.8" stroke-dasharray="12 6 2 6" opacity=".55" />
      <path d="M260 26H940M260 94H940" stroke-width="0.6" stroke-dasharray="3 7" opacity=".35" />
      <circle cx="600" cy="60" r="50" stroke-width="1" stroke-dasharray="4 4" opacity=".7" />
      <circle cx="600" cy="60" r="36" stroke-width="0.8" opacity=".6" />
      <circle cx="600" cy="60" r="22" stroke-width="0.6" stroke-dasharray="2 3" opacity=".5" />
      <path d="M600 14V106M554 60H646" stroke-width="0.8" stroke-dasharray="2 4" opacity=".6" />
      <path d="M572 32L628 88M572 88L628 32" stroke-width="0.7" opacity=".4" />
      <ellipse cx="600" cy="60" rx="96" ry="34" transform="rotate(-15 600 60)" stroke-width="0.9" stroke-dasharray="6 4" opacity=".7" />
      <ellipse cx="600" cy="60" rx="90" ry="26" transform="rotate(22 600 60)" stroke-width="0.7" stroke-dasharray="3 5" opacity=".55" />
      <path d="M340 85L410 38L470 75L520 32M410 38L470 32L470 75M340 85L420 94L490 85M420 94L470 75" stroke-width="0.75" opacity=".5" />
      <path d="M680 32L730 75L790 38L860 85M730 32L730 75L790 38M710 85L780 94L860 85M780 94L730 75" stroke-width="0.75" opacity=".5" />
      <path d="M460 55V65M480 57V63M500 54V66M520 57V63M540 54V66M660 54V66M680 57V63M700 54V66M720 57V63M740 55V65" stroke-width="0.9" opacity=".65" />
      <path d="M294 30H306M300 24V36M294 90H306M300 84V96M894 30H906M900 24V36M894 90H906M900 84V96" stroke-width="0.8" opacity=".5" />
      <path d="M240 76Q300 60 360 76T480 76T600 76T720 76T840 76T960 76" stroke-width="0.75" stroke-dasharray="2 4" opacity=".4" />
    </g>
    <g fill="currentColor">
      <circle cx="600" cy="60" r="2.5" opacity=".9" />
      <circle cx="512" cy="40" r="2.5" opacity=".8" />
      <circle cx="688" cy="80" r="2" opacity=".8" />
      <circle cx="536" cy="82" r="2" opacity=".65" />
      <circle cx="664" cy="38" r="2.5" opacity=".75" />
      <circle cx="340" cy="85" r="2" opacity=".7" />
      <circle cx="410" cy="38" r="2.5" opacity=".75" />
      <circle cx="470" cy="32" r="2" opacity=".7" />
      <circle cx="470" cy="75" r="2" opacity=".7" />
      <circle cx="420" cy="94" r="1.5" opacity=".6" />
      <circle cx="520" cy="32" r="2.5" opacity=".75" />
      <circle cx="680" cy="32" r="2.5" opacity=".75" />
      <circle cx="730" cy="75" r="2" opacity=".7" />
      <circle cx="790" cy="38" r="2.5" opacity=".75" />
      <circle cx="860" cy="85" r="2" opacity=".7" />
      <circle cx="780" cy="94" r="1.5" opacity=".6" />
    </g>
  </svg>
  <svg v-else-if="motif === 'skyline'" class="cultural-ornament cultural-skyline" viewBox="0 0 1200 120" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M120 108H1080" stroke-width="1" opacity=".5" />
      <path d="M200 114H1000" stroke-width="0.6" stroke-dasharray="4 6" opacity=".3" />
      <path d="M360 108V56H420V108M420 56H450V28H480V56H510V108" stroke-width="0.9" opacity=".7" />
      <path d="M454 28C454 18 476 18 476 28M465 18V10" stroke-width="0.9" opacity=".7" />
      <circle cx="465" cy="40" r="5" stroke-width="0.8" opacity=".6" />
      <path d="M375 70H405M375 84H405M375 96H405M432 70H445M432 84H445M485 70H498M485 84H498" stroke-width="0.7" opacity=".45" />
      <path d="M545 108V46H560V108M640 108V46H655V108" stroke-width="1" opacity=".75" />
      <path d="M578 108V54H592V108M608 108V54H622V108" stroke-width="0.9" opacity=".7" />
      <path d="M535 46L545 40H655L665 46H535Z" stroke-width="1" opacity=".8" />
      <path d="M568 34L578 28H622L632 34H568Z" stroke-width="1" opacity=".85" />
      <path d="M595 28V20M605 28V20" stroke-width="0.8" opacity=".6" />
      <path d="M690 108V50H740V36H790V108M790 62H840V108" stroke-width="0.9" opacity=".7" />
      <path d="M740 36L765 24L790 36" stroke-width="0.9" opacity=".75" />
      <path d="M705 65H725M705 80H725M705 94H725M805 76H825M805 92H825" stroke-width="0.7" opacity=".45" />
      <path d="M752 50H778M752 64H778M752 78H778M752 92H778" stroke-width="0.7" opacity=".45" />
      <path d="M320 108C328 100 338 100 346 108M522 108C528 102 534 102 540 108M662 108C668 102 674 102 680 108M848 108C854 101 864 101 872 108" stroke-width="0.8" opacity=".5" />
    </g>
  </svg>
  <svg v-else-if="motif === 'sundial'" class="cultural-ornament cultural-sundial" viewBox="0 0 1200 120" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <!-- Horizon datum & solar elevation arcs -->
      <path d="M160 60H1040" stroke-width="0.8" stroke-dasharray="16 6 2 6" opacity=".5" />
      <path d="M420 26Q600 8 780 26" stroke-width="0.8" stroke-dasharray="3 4" opacity=".5" />
      <path d="M360 94Q600 112 840 94" stroke-width="0.7" stroke-dasharray="3 5" opacity=".4" />

      <!-- Central Sundial Dial (Center at 600, 60) -->
      <circle cx="600" cy="60" r="50" stroke-width="1.2" stroke-dasharray="2 3" opacity=".75" />
      <circle cx="600" cy="60" r="42" stroke-width="1" opacity=".7" />
      <circle cx="600" cy="60" r="28" stroke-width="0.8" opacity=".6" />
      <circle cx="600" cy="60" r="14" stroke-width="0.6" stroke-dasharray="2 2" opacity=".5" />
      <circle cx="600" cy="60" r="3.5" fill="currentColor" opacity=".9" />

      <!-- Gnomon needle and casting shadow -->
      <path d="M600 60L628 20" stroke-width="2" opacity=".9" />
      <polygon points="597,60 603,60 628,20" fill="currentColor" opacity=".25" />

      <!-- Twelve Solar Hour Marks (十二时辰时刻刻度) -->
      <path d="M600 18V32M600 88V102M558 60H572M628 60H642" stroke-width="1.1" opacity=".8" />
      <path d="M576 39L586 46M614 74L624 81M586 74L576 81M624 39L614 46" stroke-width="0.9" opacity=".7" />
      <path d="M579 24L586 36M614 84L621 96M621 24L614 36M586 84L579 96" stroke-width="0.9" opacity=".7" />

      <!-- Left Central Plains Thunder-Cloud Meander (华夏青铜云雷回纹带) -->
      <path d="M340 48H360V40H372V48H388V40H400V48H416V40H428V48H444V40H456V48H472V40H484V48H500" stroke-width="0.9" opacity=".6" />
      <path d="M340 72H360V80H372V72H388V80H400V72H416V80H428V72H444V80H456V72H472V80H484V72H500" stroke-width="0.9" opacity=".6" />
      <path d="M350 60H376C388 60 396 54 396 48C396 42 386 42 386 48C386 52 390 54 394 54M446 60H472C484 60 492 54 492 48C492 42 482 42 482 48C482 52 486 54 490 54" stroke-width="0.8" opacity=".55" />

      <!-- Right Central Plains Thunder-Cloud Meander (对称回纹与汉唐云雷) -->
      <path d="M700 48H716V40H728V48H744V40H756V48H772V40H784V48H800V40H812V48H828V40H840V48H860" stroke-width="0.9" opacity=".6" />
      <path d="M700 72H716V80H728V72H744V80H756V72H772V80H784V72H800V80H812V72H828V80H840V72H860" stroke-width="0.9" opacity=".6" />
      <path d="M754 60H728C716 60 708 54 708 48C708 42 718 42 718 48C718 52 714 54 710 54M850 60H824C812 60 804 54 804 48C804 42 814 42 814 48C814 52 810 54 806 54" stroke-width="0.8" opacity=".55" />

      <!-- Jade Bi Discs & Archaic Cords (汉玉璧环饰) -->
      <circle cx="416" cy="60" r="13" stroke-width="0.9" opacity=".65" />
      <circle cx="416" cy="60" r="7" stroke-width="0.8" opacity=".5" />
      <circle cx="784" cy="60" r="13" stroke-width="0.9" opacity=".65" />
      <circle cx="784" cy="60" r="7" stroke-width="0.8" opacity=".5" />

      <!-- Sundial Wing Brackets (拱卫祥云抱柱抱鼓) -->
      <path d="M544 60C530 44 512 48 514 60C516 72 530 74 544 60M656 60C670 44 688 48 686 60C684 72 670 74 656 60" stroke-width="0.85" opacity=".6" />

      <!-- Archaic Measurement Ticks -->
      <path d="M260 56V64M280 57V63M300 55V65M320 57V63M880 57V63M900 55V65M920 57V63M940 56V64" stroke-width="0.8" opacity=".5" />
    </g>
  </svg>
  <svg v-else-if="motif === 'wenmai'" class="cultural-ornament cultural-wenmai" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <!-- Scholarly Bamboo & Calligraphic Whispers (虚心有节，风骨自持 - 文脉根基) -->
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M72 40C73 80 72 120 74 160C73 195 74 225 72 250" stroke-width="1.4" opacity=".45" />
      <path d="M66 90H80M67 145H81M66 200H80" stroke-width="1.2" opacity=".6" />
      <path d="M102 70C103 115 102 165 104 220" stroke-width="1.1" stroke-dasharray="3 5" opacity=".3" />
    </g>
    <g fill="currentColor" stroke="none">
      <path d="M74 90Q105 82 124 96Q102 94 74 90Z" opacity=".3" />
      <path d="M74 90Q110 102 128 116Q105 108 74 90Z" opacity=".26" />
      <path d="M74 145Q100 136 118 148Q98 144 74 145Z" opacity=".3" />
      <path d="M74 145Q52 155 36 166Q54 158 74 145Z" opacity=".24" />
      <path d="M74 200Q98 192 116 204Q96 200 74 200Z" opacity=".26" />
    </g>

    <!-- Unfurling Book Scroll & Cultural Waves (展卷微澜，弦歌不辍 - 文脉相承) -->
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M40 215C130 195 180 235 280 205C380 175 450 220 560 190C670 160 740 195 860 165C900 155 930 160 960 152" stroke-width="1.2" opacity=".55" />
      <path d="M40 228C130 208 180 248 280 218C380 188 450 233 560 203C670 173 740 208 860 178C900 168 930 173 960 165" stroke-width="0.9" opacity=".4" />
      <path d="M80 185C160 170 210 205 300 180C390 155 460 195 560 170C650 145 720 175 820 150" stroke-width="0.8" stroke-dasharray="4 6" opacity=".35" />

      <path d="M140 190V216M165 187V213M190 192V218M215 198V224M240 195V221M265 190V216" stroke-width="0.75" opacity=".35" />
      <path d="M330 175V201M355 172V198M380 174V200M405 180V206M430 185V211M455 182V208" stroke-width="0.75" opacity=".35" />

      <path d="M120 115C150 90 200 90 235 112C270 90 320 90 350 115" stroke-width="1" opacity=".5" />
      <path d="M120 125C150 100 200 100 235 122C270 100 320 100 350 125" stroke-width="0.8" opacity=".38" />
      <path d="M235 112V142" stroke-width="0.9" opacity=".45" />
    </g>

    <!-- Forward Momentum Trajectory & Innovation Vector (破浪致远，笃行向前 - 笃行向新) -->
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M180 200Q360 170 500 105T820 32" stroke-width="1.2" stroke-dasharray="10 5 2 5" opacity=".6" />
      <path d="M220 212Q390 185 520 122T840 48" stroke-width="0.8" stroke-dasharray="3 6" opacity=".35" />

      <path d="M340 152L348 160M420 122L428 130M500 98L508 106M580 76L588 84M660 56L668 64" stroke-width="0.8" opacity=".5" />
      <polygon points="500,101 504,105 500,109 496,105" fill="currentColor" opacity=".65" />
      <polygon points="640,63 644,67 640,71 636,67" fill="currentColor" opacity=".7" />
      <polygon points="760,37 764,41 760,45 756,41" fill="currentColor" opacity=".75" />

      <!-- Ascending Cranes / Soaring Birds (展翅高飞，向新而行) -->
      <path d="M490 85Q520 62 550 74Q535 90 490 85M550 74Q580 52 618 66Q585 80 550 74" stroke-width="1.1" opacity=".75" />
      <path d="M592 52Q618 34 642 45Q628 58 592 52M642 45Q668 28 700 40Q672 52 642 45" stroke-width="1" opacity=".7" />
      <path d="M690 28Q710 14 730 22Q720 32 690 28M730 22Q750 10 776 19Q754 28 730 22" stroke-width="0.9" opacity=".65" />
    </g>
  </svg>
  <svg v-else-if="motif === 'disciplines'" class="cultural-ornament cultural-disciplines" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220H900" stroke-width="1.2" opacity=".5" />
      <path d="M120 70L240 220M240 70L360 220M360 70L480 220M480 70L600 220M600 70L720 220" stroke-width="0.8" stroke-dasharray="3 6" opacity=".35" />
      <path d="M160 220V120H240V220M240 120H320V220M320 120H400V220" stroke-width="0.9" opacity=".55" />
      <path d="M150 120L280 80L410 120H150Z" stroke-width="1" opacity=".65" />
      <path d="M380 180Q460 140 540 180T700 180T860 180" stroke-width="1" opacity=".5" />
      <path d="M380 180Q460 220 540 180T700 180T860 180" stroke-width="0.8" stroke-dasharray="2 4" opacity=".4" />
      <path d="M500 110L525 95V65L500 50L475 65V95L500 110Z" stroke-width="0.9" opacity=".6" />
      <path d="M550 80L575 65V35L550 20L525 35V65L550 80Z" stroke-width="0.8" opacity=".5" />
      <path d="M620 220C620 160 670 110 730 110C770 110 800 140 800 180C800 205 780 225 755 225C740 225 730 215 730 200" stroke-width="1" opacity=".55" />
    </g>
    <g fill="currentColor">
      <circle cx="280" cy="80" r="3" opacity=".8" />
      <circle cx="500" cy="50" r="2.5" opacity=".7" />
      <circle cx="550" cy="20" r="2.5" opacity=".7" />
      <circle cx="730" cy="110" r="2.5" opacity=".75" />
      <circle cx="620" cy="180" r="2" opacity=".6" />
      <circle cx="780" cy="180" r="2" opacity=".6" />
    </g>
  </svg>
  <svg v-else-if="motif === 'education'" class="cultural-ornament cultural-education" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220H180V185H300V150H420V115H540V80H660V45H780V220H920" stroke-width="1.1" opacity=".6" />
      <path d="M180 185H220M300 150H340M420 115H460M540 80H580" stroke-width="0.8" opacity=".4" />
      <path d="M240 220V185M360 220V150M480 220V115M600 220V80M720 220V45" stroke-width="0.8" stroke-dasharray="3 5" opacity=".35" />
      <path d="M420 50L380 140M420 50L460 140M390 110H450" stroke-width="1.2" opacity=".7" />
      <circle cx="420" cy="50" r="4" stroke-width="1" opacity=".85" />
      <circle cx="640" cy="140" r="40" stroke-width="1.2" stroke-dasharray="8 6" opacity=".65" />
      <circle cx="640" cy="140" r="26" stroke-width="0.8" opacity=".5" />
      <circle cx="705" cy="100" r="24" stroke-width="1" stroke-dasharray="6 4" opacity=".55" />
      <circle cx="705" cy="100" r="14" stroke-width="0.7" opacity=".4" />
      <path d="M660 45L720 15M660 45L745 35M660 45L755 60" stroke-width="0.9" opacity=".5" />
    </g>
  </svg>
  <svg v-else-if="motif === 'research'" class="cultural-ornament cultural-research" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 150H900" stroke-width="0.9" stroke-dasharray="14 6 2 6" opacity=".5" />
      <path d="M380 30V240" stroke-width="0.9" stroke-dasharray="14 6 2 6" opacity=".5" />
      <ellipse cx="380" cy="150" rx="90" ry="32" transform="rotate(-30 380 150)" stroke-width="1" stroke-dasharray="5 3" opacity=".75" />
      <ellipse cx="380" cy="150" rx="90" ry="32" transform="rotate(30 380 150)" stroke-width="1" stroke-dasharray="5 3" opacity=".75" />
      <ellipse cx="380" cy="150" rx="88" ry="30" transform="rotate(90 380 150)" stroke-width="0.8" opacity=".6" />
      <path d="M380 150L780 40M380 150L820 220" stroke-width="1" stroke-dasharray="6 4" opacity=".55" />
      <path d="M520 115Q600 80 640 170" stroke-width="0.9" stroke-dasharray="3 4" opacity=".45" />
      <path d="M640 90Q720 55 760 195" stroke-width="0.9" stroke-dasharray="3 4" opacity=".4" />
      <path d="M80 150Q140 110 200 150T320 150T440 150T560 150T680 150T800 150T920 150" stroke-width="0.8" stroke-dasharray="2 4" opacity=".4" />
      <path d="M300 144V156M320 146V154M340 144V156M360 146V154M400 146V154M420 144V156M440 146V154M460 144V156" stroke-width="0.8" opacity=".6" />
    </g>
    <g fill="currentColor">
      <circle cx="380" cy="150" r="4" opacity=".9" />
      <circle cx="310" cy="110" r="2.5" opacity=".8" />
      <circle cx="450" cy="190" r="2.5" opacity=".8" />
      <circle cx="455" cy="110" r="2" opacity=".75" />
      <circle cx="305" cy="190" r="2" opacity=".75" />
      <circle cx="380" cy="62" r="2.5" opacity=".8" />
      <circle cx="780" cy="40" r="3" opacity=".8" />
      <circle cx="820" cy="220" r="3" opacity=".8" />
    </g>
  </svg>
  <svg v-else-if="motif === 'journey'" class="cultural-ornament cultural-journey" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220H900" stroke-width="1.1" opacity=".5" />
      <path d="M120 230H840M180 238H760" stroke-width="0.7" stroke-dasharray="4 8" opacity=".35" />
      <path d="M140 220V100H165V220M245 220V100H270V220" stroke-width="1.2" opacity=".7" />
      <path d="M165 140C165 110 245 110 245 140" stroke-width="1.1" opacity=".65" />
      <path d="M125 100H285V85H125Z" stroke-width="1.2" opacity=".8" />
      <path d="M150 85L205 60L260 85" stroke-width="1.2" opacity=".85" />
      <circle cx="205" cy="115" r="8" stroke-width="0.8" opacity=".6" />
      <circle cx="560" cy="220" r="60" stroke-width="1" stroke-dasharray="4 3" opacity=".55" />
      <path d="M560 145V130M515 160L500 150M605 160L620 150M480 190L465 185M640 190L655 185" stroke-width="0.9" opacity=".5" />
      <path d="M480 215C530 218 570 218 620 215L640 195H460Z" stroke-width="1.2" opacity=".75" />
      <path d="M550 195V105" stroke-width="1.3" opacity=".85" />
      <path d="M550 110Q620 145 550 185Z" stroke-width="1.1" opacity=".65" />
      <path d="M545 125Q490 150 545 185Z" stroke-width="0.9" opacity=".55" />
      <path d="M680 75Q705 55 730 65Q718 78 680 75M730 65Q755 48 785 58Q760 70 730 65" stroke-width="1.1" opacity=".7" />
      <path d="M780 45Q800 30 820 38Q810 48 780 45M820 38Q840 24 865 32Q845 42 820 38" stroke-width="0.9" opacity=".6" />
    </g>
  </svg>
  <svg v-else-if="motif === 'exchange'" class="cultural-ornament cultural-exchange" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220H900" stroke-width="1.1" opacity=".5" />
      <path d="M120 230H840" stroke-width="0.7" stroke-dasharray="5 7" opacity=".35" />
      <path d="M420 220L470 45L520 220" stroke-width="1.5" opacity=".8" />
      <path d="M450 135H490M440 180H500" stroke-width="1" opacity=".6" />
      <path d="M470 65L260 220M470 65L680 220M470 95L300 220M470 95L640 220M470 125L340 220M470 125L600 220M470 155L380 220M470 155L560 220" stroke-width="0.8" opacity=".45" />
      <ellipse cx="680" cy="130" rx="95" ry="95" stroke-width="1" stroke-dasharray="6 4" opacity=".55" />
      <ellipse cx="680" cy="130" rx="45" ry="95" stroke-width="0.8" opacity=".45" />
      <path d="M585 130H775" stroke-width="0.8" opacity=".5" />
      <path d="M595 95Q680 120 765 95M595 165Q680 140 765 165" stroke-width="0.7" stroke-dasharray="3 4" opacity=".4" />
      <path d="M140 160Q260 100 380 160T620 160" stroke-width="1" stroke-dasharray="3 5" opacity=".45" />
    </g>
  </svg>
  <svg v-else-if="motif === 'life'" class="cultural-ornament cultural-life" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220C160 220 240 170 340 170C460 170 520 230 640 230C760 230 840 180 920 180" stroke-width="1.3" opacity=".7" />
      <path d="M60 205C160 205 240 155 340 155C460 155 520 215 640 215C760 215 840 165 920 165" stroke-width="0.9" opacity=".5" />
      <path d="M60 190C160 190 240 140 340 140C460 140 520 200 640 200C760 200 840 150 920 150" stroke-width="0.7" stroke-dasharray="5 5" opacity=".4" />
      <path d="M180 100Q340 40 500 90T820 60" stroke-width="0.9" opacity=".6" />
      <path d="M180 112Q340 52 500 102T820 72" stroke-width="0.7" opacity=".45" />
      <path d="M180 124Q340 64 500 114T820 84" stroke-width="0.7" opacity=".4" />
      <circle cx="320" cy="74" r="5" fill="currentColor" opacity=".7" />
      <path d="M325 74V44M325 44L345 50V80" stroke-width="1.1" opacity=".7" />
      <circle cx="345" cy="80" r="5" fill="currentColor" opacity=".7" />
      <circle cx="480" cy="88" r="4.5" fill="currentColor" opacity=".65" />
      <path d="M484.5 88V58Q498 64 502 72" stroke-width="1" opacity=".65" />
      <circle cx="620" cy="76" r="4.5" fill="currentColor" opacity=".65" />
      <path d="M624.5 76V46" stroke-width="1" opacity=".65" />
    </g>
    <g fill="currentColor" stroke="none">
      <path d="M260 130Q280 110 295 125Q285 140 260 130Z" opacity=".35" />
      <path d="M420 150Q435 132 450 144Q440 158 420 150Z" opacity=".3" />
      <path d="M720 110Q742 90 758 106Q746 122 720 110Z" opacity=".35" />
      <path d="M840 130Q856 116 868 128Q858 140 840 130Z" opacity=".28" />
    </g>
  </svg>
  <svg v-else-if="motif === 'disclosure'" class="cultural-ornament cultural-disclosure" viewBox="0 0 960 260" :preserveAspectRatio="preserveAspectRatio ?? 'xMidYMid meet'" aria-hidden="true" focusable="false">
    <g fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round">
      <path d="M60 220H900" stroke-width="1.2" opacity=".5" />
      <path d="M120 228H840" stroke-width="0.8" stroke-dasharray="6 6" opacity=".35" />
      <path d="M480 220V70" stroke-width="1.5" opacity=".8" />
      <path d="M455 220H505" stroke-width="1.3" opacity=".75" />
      <path d="M370 95L480 80L590 95" stroke-width="1.4" opacity=".85" />
      <circle cx="480" cy="80" r="5" stroke-width="1.2" opacity=".9" />
      <path d="M370 95L345 155M370 95L395 155" stroke-width="0.8" opacity=".6" />
      <path d="M335 155C335 168 405 168 405 155Z" stroke-width="1" opacity=".7" />
      <path d="M590 95L565 155M590 95L615 155" stroke-width="0.8" opacity=".6" />
      <path d="M555 155C555 168 625 168 625 155Z" stroke-width="1" opacity=".7" />
      <circle cx="480" cy="40" r="16" stroke-width="1" stroke-dasharray="3 3" opacity=".6" />
      <path d="M480 18V6M458 24L448 14M502 24L512 14M444 40H430M516 40H530M458 56L448 66M502 56L512 66" stroke-width="0.9" opacity=".55" />
      <path d="M140 140H260V220H140Z" stroke-width="1" opacity=".6" />
      <path d="M155 160H245M155 178H245M155 196H225" stroke-width="0.8" opacity=".45" />
      <path d="M700 140H820V220H700Z" stroke-width="1" opacity=".6" />
      <path d="M715 160H805M715 178H805M715 196H785" stroke-width="0.8" opacity=".45" />
    </g>
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
.cultural-tech, .cultural-skyline, .cultural-sundial, .cultural-wenmai,
.cultural-disciplines, .cultural-education, .cultural-research, .cultural-journey,
.cultural-exchange, .cultural-life, .cultural-disclosure {
  mask-image: linear-gradient(90deg, transparent 0%, rgba(0,0,0,0.18) 16%, #000 36%, #000 68%, rgba(0,0,0,0.18) 88%, transparent 100%);
}
</style>
