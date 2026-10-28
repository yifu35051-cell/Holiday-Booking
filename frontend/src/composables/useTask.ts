import { ref } from 'vue'
import { ApiError } from '@/api/client'
export function useTask() {
  const busy = ref(false),
    error = ref(''),
    success = ref('')
  async function run(action: () => Promise<unknown>, message = '') {
    if (busy.value) return
    busy.value = true
    error.value = ''
    success.value = ''
    try {
      await action()
      success.value = message
    } catch (e) {
      error.value =
        e instanceof ApiError
          ? `${e.message}${e.traceId ? '（追踪号：' + e.traceId + '）' : ''}`
          : e instanceof Error
            ? e.message
            : '操作失败'
    } finally {
      busy.value = false
    }
  }
  return { busy, error, success, run }
}
