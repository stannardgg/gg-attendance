import { computed, reactive } from 'vue'
import { api, ApiError, getToken, setToken } from '@/shared/api/client'
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
  setToken(data.accessToken)
  await loadMe()
}

export async function loadMe(): Promise<void> {
  if (!getToken()) {
    auth.ready = true
    return
  }
  try {
    const me = await api.get<Me>('/api/v1/auth/me')
    auth.profile = me.profile
    // 人脸状态单独取：/auth/me 里的 faceStatus 可能是缓存值，签到前必须以档案为准
    await refreshFaceStatus()
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-1002') setToken(null)
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
  setToken(null)
  auth.profile = null
  auth.faceStatus = null
}
