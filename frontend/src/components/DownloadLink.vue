<script setup lang="ts">
import { ref } from 'vue'
import { downloadFile } from '../lib/download'
import { errorMessage } from '../lib/api'
import { useToast } from '../composables/useToast'

const props = defineProps<{ href?: string; filename?: string; expectedType?: string }>()
const busy = ref(false)
const toast = useToast()
async function download() {
  if (busy.value || !props.href) return
  busy.value = true
  try { await downloadFile(props.href, props.filename, props.expectedType) }
  catch (cause) { toast.error(errorMessage(cause)) }
  finally { busy.value = false }
}
</script>

<template>
  <a :href="href" :aria-disabled="busy || !href" :aria-busy="busy" @click.prevent="download"><slot /><span v-if="busy" class="sr-only">，正在下载</span></a>
</template>
