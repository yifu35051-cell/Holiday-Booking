import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '@/api/services'
import { ApiError, clearCsrf } from '@/api/client'
import type { Role, User } from '@/api/types'
export const portal = (role: Role) => '/' + role.toLowerCase()
export const useAuth = defineStore('auth', () => {
  const user = ref<User | null>(null)
  async function refresh() {
    try {
      user.value = await authApi.me()
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) user.value = null
      else throw e
    }
    return user.value
  }
  async function login(role: Role, email: string, password: string) {
    // Same-origin sessions are shared by tabs. Switching identity first closes the old session.
    if (await refresh()) await logout()
    const result = await authApi.login(email, password, role)
    clearCsrf()
    if (result.role !== role) {
      await authApi.logout()
      throw new Error('账户角色与登录入口不匹配')
    }
    user.value = result
  }
  async function logout() {
    await authApi.logout()
    user.value = null
    clearCsrf()
  }
  return { user, refresh, login, logout }
})
