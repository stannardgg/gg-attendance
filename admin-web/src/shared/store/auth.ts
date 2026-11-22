/**
 * 登录态。刻意不引 Pinia：这个应用只有一份全局状态，
 * 一个 reactive 对象比"再学一个状态库"更省事，也更容易在路由守卫里同步读取。
 */
import { computed, reactive } from 'vue'
import { api, getToken, setToken, ApiError } from '@/shared/api/client'
import type { LoginResult, Me } from '@/shared/api/types'

interface AuthState {
  profile: Me['profile'] | null
  permissions: string[]
  scope: { classIds: number[]; courseIds: number[] }
  ready: boolean
}

export const auth = reactive<AuthState>({
  profile: null,
  permissions: [],
  scope: { classIds: [], courseIds: [] },
  ready: false
})

export const isAdmin = computed(() => auth.profile?.role === 'ADMIN')

/** 权限点来自 /auth/me；前端隐藏只是体验优化，后端仍会二次校验（§8.3）。 */
export function can(permission: string): boolean {
  return auth.permissions.includes(permission)
}

export async function login(username: string, password: string): Promise<void> {
  const data = await api.post<LoginResult>('/api/v1/auth/login', { username, password })
  setToken(data.accessToken)
  await loadMe()
}

export async function loadMe(): Promise<void> {
  if (!getToken()) {
    // 令牌已不存在（本页登出，或别的标签页登出）：身份必须一起清掉，
    // 否则菜单还按旧身份渲染，点进去只会收到 401
    auth.profile = null
    auth.permissions = []
    auth.scope = { classIds: [], courseIds: [] }
    auth.ready = true
    return
  }
  try {
    const me = await api.get<Me>('/api/v1/auth/me')
    auth.profile = me.profile
    auth.permissions = me.permissions ?? []
    auth.scope = me.scope ?? { classIds: [], courseIds: [] }
  } catch (err) {
    // 令牌失效时不要静默：清掉并让守卫把用户送回登录页
    if (err instanceof ApiError && err.code === 'FA-1002') setToken(null)
    auth.profile = null
  } finally {
    auth.ready = true
  }
}

export async function logout(): Promise<void> {
  try {
    await api.post('/api/v1/auth/logout')
  } catch {
    // 登出失败也要清本地状态：否则用户会卡在"看起来已登录但其实没有令牌"的状态
  }
  setToken(null)
  auth.profile = null
  auth.permissions = []
}
