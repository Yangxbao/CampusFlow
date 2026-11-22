/**
 * 认证状态：token + 用户信息 + 角色判断。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi, type UserVO } from '@/api/modules'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('campusflow_token') || '')
  const user = ref<UserVO | null>(JSON.parse(localStorage.getItem('campusflow_user') || 'null'))

  const isLoggedIn = computed(() => !!token.value)
  const isStudent = computed(() => user.value?.roles.includes('STUDENT') || (!user.value?.roles.includes('ORGANIZER') && !user.value?.roles.includes('ADMIN')))
  const isOrganizer = computed(() => !!user.value?.roles.includes('ORGANIZER'))
  const isAdmin = computed(() => !!user.value?.roles.includes('ADMIN'))

  async function login(username: string, password: string) {
    const r = await authApi.login(username, password)
    token.value = r.data.token
    user.value = r.data.user
    localStorage.setItem('campusflow_token', r.data.token)
    localStorage.setItem('campusflow_user', JSON.stringify(r.data.user))
  }

  async function register(body: { username: string; password: string; nickname: string; role: string }) {
    await authApi.register(body)
  }

  async function refreshMe() {
    try {
      const r = await authApi.me()
      user.value = r.data
      localStorage.setItem('campusflow_user', JSON.stringify(r.data))
    } catch {
      // token 失效由拦截器处理
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('campusflow_token')
    localStorage.removeItem('campusflow_user')
  }

  return { token, user, isLoggedIn, isStudent, isOrganizer, isAdmin, login, register, refreshMe, logout }
})
