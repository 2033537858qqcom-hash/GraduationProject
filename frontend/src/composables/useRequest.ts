import { getCurrentScope, onScopeDispose, ref, shallowRef } from 'vue'
import { errorMessage, isAbortError } from '../lib/api.ts'

export interface UseRequestOptions {
  /** Run the request immediately. Defaults to true. */
  immediate?: boolean
  /** Keep the previous data while a new request is running. Defaults to true. */
  keepPrevious?: boolean
}

/**
 * Shared loading / error / data state for a request.
 * Older requests are aborted when a new one starts or the owning component unmounts,
 * so stale responses can never overwrite newer results.
 */
export function useRequest<T, A extends unknown[] = []>(
  fn: (signal: AbortSignal, ...args: A) => Promise<T>,
  options: UseRequestOptions = {},
) {
  const { immediate = true, keepPrevious = true } = options
  const data = shallowRef<T | null>(null)
  const loading = ref(false)
  const error = ref('')
  let controller: AbortController | null = null

  async function run(...args: A): Promise<T | undefined> {
    controller?.abort()
    const current = new AbortController()
    controller = current
    loading.value = true
    error.value = ''
    if (!keepPrevious) data.value = null
    try {
      const result = await fn(current.signal, ...args)
      if (controller === current) data.value = result
      return result
    } catch (cause) {
      if (!isAbortError(cause) && controller === current) error.value = errorMessage(cause)
      return undefined
    } finally {
      if (controller === current) loading.value = false
    }
  }

  function abort() {
    controller?.abort()
    controller = null
    loading.value = false
  }

  if (getCurrentScope()) onScopeDispose(abort)
  if (immediate) void run(...([] as unknown as A))

  return { data, loading, error, run, abort }
}
