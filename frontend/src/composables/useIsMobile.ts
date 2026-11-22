/**
 * 移动端判定 composable（<=768px）。
 * 窗口尺寸变化实时响应；页面内可用于切换栅格列数/描述列数等布局参数。
 */
import { onMounted, onUnmounted, ref } from 'vue'

export function useIsMobile(breakpoint = 768) {
  const isMobile = ref(window.innerWidth <= breakpoint)
  let handler: () => void
  onMounted(() => {
    handler = () => {
      isMobile.value = window.innerWidth <= breakpoint
    }
    window.addEventListener('resize', handler)
  })
  onUnmounted(() => {
    window.removeEventListener('resize', handler)
  })
  return { isMobile }
}
