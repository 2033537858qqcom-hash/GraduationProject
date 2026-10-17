import { computed, ref } from 'vue'
import { errorMessage } from '../lib/api.ts'
import { getTaskPage, type AggregatedTask, type TaskDomain, type TaskPage, type TaskSourceError, type WorkbenchOverview } from '../workbench.ts'

export function useTaskQueue(fetchPage = getTaskPage, pageSize = 8) {
  const tasks = ref<AggregatedTask[]>([])
  const totalPending = ref(0)
  const taskCounts = ref<Record<string, number>>({})
  const taskFilter = ref<TaskDomain>('ALL')
  const currentPage = ref(1)
  const total = ref(0)
  const loading = ref(false)
  const error = ref('')
  const complete = ref(false)
  const sourceErrors = ref<TaskSourceError[]>([])
  const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
  let revision = 0

  function apply(result: TaskPage) {
    tasks.value = result.tasks
    totalPending.value = result.totalPending
    taskCounts.value = result.domainCounts
    total.value = result.total
    currentPage.value = result.page
    complete.value = result.complete
    sourceErrors.value = result.sourceErrors
  }

  function initialize(result: WorkbenchOverview) {
    revision++
    taskFilter.value = 'ALL'
    apply({ ...result, domainCounts: result.taskCounts, total: result.totalPending, page: 1, pageSize })
    error.value = ''
    loading.value = false
  }

  async function load(domain: TaskDomain = taskFilter.value, page = 1): Promise<void> {
    const request = ++revision
    taskFilter.value = domain
    currentPage.value = page
    loading.value = true
    error.value = ''
    try {
      const result = await fetchPage(domain, page, pageSize)
      if (request !== revision) return
      const lastPage = Math.max(1, Math.ceil(result.total / pageSize))
      if (page > lastPage) return await load(domain, lastPage)
      apply(result)
    } catch (cause) {
      if (request !== revision) return
      tasks.value = []
      complete.value = false
      error.value = errorMessage(cause)
    } finally {
      if (request === revision) loading.value = false
    }
  }

  function invalidate() { revision++ }
  return { tasks, totalPending, taskCounts, taskFilter, currentPage, total, loading, error, complete, sourceErrors, pageCount, initialize, load, invalidate }
}
