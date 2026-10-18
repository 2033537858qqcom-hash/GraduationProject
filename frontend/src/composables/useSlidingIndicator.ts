import { ref, watch, onMounted, onBeforeUnmount, nextTick, getCurrentInstance, type Ref } from 'vue'

export interface SlidingIndicatorOptions {
  activeSelector?: string
  scrollContainer?: boolean
}

export function useSlidingIndicator(
  containerRef: Ref<HTMLElement | null>,
  triggerWatcher?: () => unknown,
  options: SlidingIndicatorOptions = {}
) {
  const activeSelector = options.activeSelector ?? '.selected, .active, [aria-selected="true"]'
  let isInitialized = false
  let unmountPending = false
  let mounted = false

  const indicatorStyle = ref<Record<string, string | number>>({
    transform: 'translateX(0px)',
    width: '0px',
    opacity: 0,
    transition: 'none',
  })

  let resizeObserver: ResizeObserver | null = null
  let pendingUpdate = false

  function performUpdate() {
    if (unmountPending) return
    const container = containerRef.value
    if (!container) {
      hideIndicator()
      return
    }

    const activeEl = container.querySelector(activeSelector) as HTMLElement | null
    if (!activeEl) {
      hideIndicator()
      return
    }

    const containerRect = container.getBoundingClientRect()
    const activeRect = activeEl.getBoundingClientRect()

    const left = activeRect.left - containerRect.left + (container.scrollLeft || 0)
    const width = activeRect.width

    if (width <= 0) {
      hideIndicator()
      return
    }

    if (!isInitialized) {
      // First placement: snap directly without fly-in from (0,0) or opacity fade flicker
      indicatorStyle.value = {
        transform: `translateX(${Math.round(left)}px)`,
        width: `${Math.round(width)}px`,
        opacity: 1,
        transition: 'none',
      }
      isInitialized = true

      // Enable CSS transitions on the very next animation frame
      if (typeof requestAnimationFrame !== 'undefined') {
        requestAnimationFrame(() => {
          if (!unmountPending) {
            indicatorStyle.value = {
              ...indicatorStyle.value,
              transition: '',
            }
          }
        })
      } else {
        indicatorStyle.value = {
          ...indicatorStyle.value,
          transition: '',
        }
      }
    } else {
      // Subsequent tab switches: animated smooth glide
      indicatorStyle.value = {
        transform: `translateX(${Math.round(left)}px)`,
        width: `${Math.round(width)}px`,
        opacity: 1,
        transition: '',
      }
    }
  }

  function hideIndicator() {
    isInitialized = false
    indicatorStyle.value = { ...indicatorStyle.value, opacity: 0, transition: 'none' }
  }

  function scheduleUpdate() {
    if (pendingUpdate || unmountPending) return
    pendingUpdate = true

    nextTick(() => {
      pendingUpdate = false
      performUpdate()
    })
  }

  function observeContainer(container: HTMLElement | null, previous: HTMLElement | null = null) {
    previous?.removeEventListener('scroll', scheduleUpdate)
    resizeObserver?.disconnect()
    if (!mounted) return
    container?.addEventListener('scroll', scheduleUpdate, { passive: true })
    if (container) resizeObserver?.observe(container)
  }

  // v-if can create a new tab bar while its selected value stays unchanged.
  watch(containerRef, (container, previous) => {
    observeContainer(container, previous)
    hideIndicator()
    scheduleUpdate()
  }, { flush: 'post' })

  if (triggerWatcher) {
    // flush: 'post' ensures the watcher executes AFTER the DOM has updated
    // with the new active/selected class, preventing 1-frame stale measurements
    watch(triggerWatcher, () => {
      scheduleUpdate()
    }, { flush: 'post' })
  }

  if (getCurrentInstance()) {
    onMounted(() => {
      mounted = true
      if (typeof ResizeObserver !== 'undefined') {
        resizeObserver = new ResizeObserver(() => scheduleUpdate())
      }
      observeContainer(containerRef.value)
      scheduleUpdate()
      if (typeof document !== 'undefined' && 'fonts' in document) {
        document.fonts.ready.then(scheduleUpdate).catch(() => {})
      }
      window.addEventListener('resize', scheduleUpdate)
    })

    onBeforeUnmount(() => {
      unmountPending = true
      mounted = false
      window.removeEventListener('resize', scheduleUpdate)
      containerRef.value?.removeEventListener('scroll', scheduleUpdate)
      resizeObserver?.disconnect()
    })
  }

  return {
    indicatorStyle,
    updateIndicator: scheduleUpdate,
  }
}
