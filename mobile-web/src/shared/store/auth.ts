import { computed, reactive } from 'vue'
import {
  api,
  ApiError,
  clearTokens,
  getRefreshToken,
  getToken,
  refreshAccessToken,
  setRefreshToken,
  setToken
} from '@/shared/api/client'
import type { FaceStatusDetail, LoginResult, Me } from '@/shared/api/types'

interface AuthState {
  profile: Me['profile'] | null
  faceStatus: FaceStatusDetail | null
  ready: boolean
}

export const auth = reactive<AuthState>({ profile: null, faceStatus: null, ready: false })

export const isStudent = computed(() => auth.profile?.role === 'STUDENT')

/** 只有 ACTIVE 才能刷脸签到；其余状态一律拦到采集页（FA-4006）。 */
export const faceReady = computed(() => auth.faceStatus?.status === 'ACTIVE')

export async function login(studentNo: string, password: string): Promise<void> {
  const data = await api.post<LoginResult>('/api/v1/auth/login', { username: studentNo, password })
  // 双令牌都落 cookie：access 短命（秒级 TTL 用服务端返回值），refresh 7 天
  setToken(data.accessToken, data.expiresIn)
  setRefreshToken(data.refreshToken)
  await loadMe()
}

export async function loadMe(): Promise<void> {
  if (!getToken() && !getRefreshToken()) {
    auth.ready = true
    return
  }
  if (!getToken()) {
    // 只剩 refresh cookie（隔天打开页面、access 已到期）：先静默换新再取身份，
    // 学生刷新页面不该看到登录页——这是「刷新后仍然是当前用户」的验收口径。
    const fresh = await refreshAccessToken()
    if (!fresh) {
      clearTokens()
      auth.profile = null
      auth.ready = true
      return
    }
  }
  try {
    const me = await api.get<Me>('/api/v1/auth/me')
    auth.profile = me.profile
    // 人脸状态单独取：/auth/me 里的 faceStatus 可能是缓存值，签到前必须以档案为准
    await refreshFaceStatus()
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-1002') clearTokens()
    auth.profile = null
  } finally {
    auth.ready = true
  }
}

export async function refreshFaceStatus(): Promise<void> {
  try {
    auth.faceStatus = await api.get<FaceStatusDetail>('/api/v1/mobile/face/status')
  } catch {
    auth.faceStatus = null
  }
}

export async function logout(): Promise<void> {
  try {
    await api.post('/api/v1/auth/logout')
  } catch {
    /* 令牌可能已过期，本地照样清 */
  }
  clearTokens()
  auth.profile = null
  auth.faceStatus = null
}
