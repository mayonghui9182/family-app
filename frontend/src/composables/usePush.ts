import { ref, onMounted } from 'vue'

type NotificationPermission = 'default' | 'granted' | 'denied'

export function usePush() {
  const notificationPermission = ref<NotificationPermission>('default')
  const isSupported = ref(false)
  const isServiceWorkerRegistered = ref(false)

  const checkSupport = () => {
    isSupported.value = 'Notification' in window
  }

  const checkPermission = (): NotificationPermission => {
    if (!isSupported.value) return 'denied'
    notificationPermission.value = Notification.permission as NotificationPermission
    return notificationPermission.value
  }

  const requestNotificationPermission = async (): Promise<boolean> => {
    if (!isSupported.value) {
      console.warn('浏览器不支持通知功能')
      return false
    }

    try {
      const result = await Notification.requestPermission()
      notificationPermission.value = result as NotificationPermission
      return result === 'granted'
    } catch (e) {
      console.error('请求通知权限失败:', e)
      return false
    }
  }

  const showNotification = (title: string, options?: NotificationOptions) => {
    if (!isSupported.value || notificationPermission.value !== 'granted') {
      console.warn('无法显示通知：不支持或未授权')
      return null
    }

    try {
      const notification = new Notification(title, options)
      notification.onclick = () => {
        window.focus()
        notification.close()
      }
      return notification
    } catch (e) {
      console.error('显示通知失败:', e)
      return null
    }
  }

  const registerServiceWorker = async (): Promise<boolean> => {
    if (!('serviceWorker' in navigator)) {
      console.warn('浏览器不支持 Service Worker')
      return false
    }

    try {
      // const registration = await navigator.serviceWorker.register('/sw.js')
      // isServiceWorkerRegistered.value = true
      // console.log('Service Worker 注册成功:', registration)
      console.log('Service Worker 注册功能预留')
      return true
    } catch (e) {
      console.error('Service Worker 注册失败:', e)
      return false
    }
  }

  const unsubscribeServiceWorker = async (): Promise<boolean> => {
    if (!('serviceWorker' in navigator)) return false

    try {
      const registration = await navigator.serviceWorker.ready
      if (registration) {
        await registration.unregister()
        isServiceWorkerRegistered.value = false
        return true
      }
      return false
    } catch (e) {
      console.error('取消 Service Worker 失败:', e)
      return false
    }
  }

  onMounted(() => {
    checkSupport()
    if (isSupported.value) {
      checkPermission()
    }
  })

  return {
    isSupported,
    notificationPermission,
    isServiceWorkerRegistered,
    checkPermission,
    requestNotificationPermission,
    showNotification,
    registerServiceWorker,
    unsubscribeServiceWorker,
  }
}
