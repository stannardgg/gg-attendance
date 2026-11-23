/**
 * 移动端 HTTP 客户端。
 *
 * 与后台那份刻意写成两个文件而不是抽公共包：两者错误处置不同 ——
 * 后台 401 回登录页，移动端 401 还要处理"扫了一半码被踢出去"的场景（见 client 注释）。
 * 抽成包会让这段差异藏进 if，反而难读。
 */
import { ERROR_MESSAGES } from './errors'

const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''
const FACE_BASE = (import.meta.env.VITE_FACE_BASE as string | undefined) ?? ''

export class ApiError extends Error {
  code: string
  status: number
  data: unknown
  constructor(code: string, message: string, status = 0, data: unknown = null) {
    super(message)
    this.code = code
    this.status = status
    this.data = data
  }
  /** 失败门禁（face-api 的 data.rejectGate），用于结果页归因 */
  get gate(): string | null {
    const d = this.data as { rejectGate?: string } | null
    return d?.rejectGate ?? null
  }
}

export const TOKEN_KEY = 'gg.mobile.token'
/** 刷新令牌独立成 cookie：access 过期后靠它静默续期（后端 /auth/refresh 轮换） */
export const REFRESH_KEY = 'gg.mobile.refresh'

/**
 * 登录态落在 **cookie** 而不是 localStorage：这是业主验收的明确口径
 * （「存储登录状态 cookie，刷新后仍然是当前用户」）。令牌本身仍走
 * Authorization 头上行，cookie 只充当持久化介质，服务端不读它。
 * SameSite=Lax：本站的 fetch 都属同站请求，不受影响；JS 可读性与
 * localStorage 相当，威胁模型没有变化。
 */
const ACCESS_MAX_AGE = 1800 // 对齐后端 JWT_ACCESS_TTL 默认值；登录/刷新会用响应里的 expiresIn 覆盖
const REFRESH_MAX_AGE = 604800 // 对齐后端 JWT_REFRESH_TTL（7 天）

function readCookie(name: string): string | null {
  const prefix = `${name}=`
  const hit = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return hit ? decodeURIComponent(hit.slice(prefix.length)) : null
}

function writeCookie(name: string, value: string, maxAge: number) {
  document.cookie = `${name}=${encodeURIComponent(value)}; path=/; max-age=${maxAge}; SameSite=Lax`
}

function readToken(name: string): string | null {
  try {
    return readCookie(name)
  } catch {
    return null
  }
}

function writeToken(name: string, token: string | null, maxAge: number) {
  try {
    if (token) writeCookie(name, token, maxAge)
    else writeCookie(name, '', 0)
  } catch {
    /* 隐私模式下写不进去：本次会话退化为内存态，可用性优先 */
  }
}

export function getToken(): string | null {
  return readToken(TOKEN_KEY)
}

export function setToken(token: string | null, maxAgeSec?: number) {
  writeToken(TOKEN_KEY, token, maxAgeSec ?? ACCESS_MAX_AGE)
}

export function getRefreshToken(): string | null {
  return readToken(REFRESH_KEY)
}

export function setRefreshToken(token: string | null) {
  writeToken(REFRESH_KEY, token, REFRESH_MAX_AGE)
}

/** 登录失效（刷新失败/登出/401 终态）时两把钥匙一起清 */
export function clearTokens() {
  setToken(null)
  setRefreshToken(null)
}

/* ---------------- 静默续期 ---------------- */

let refreshing: Promise<string | null> | null = null

/**
 * 单飞刷新：并发多个 401 只发起一次 /auth/refresh。
 * 后端 refresh 是**轮换**语义（旧 refresh 立即作废），并发复用旧令牌会互相打掉。
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshing) {
    refreshing = doRefresh().finally(() => {
      refreshing = null
    })
  }
  return refreshing
}

async function doRefresh(): Promise<string | null> {
  const rt = getRefreshToken()
  if (!rt) return null
  let resp: Response
  try {
    resp = await fetch(`${BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt })
    })
  } catch {
    // 网络抖动不算登录失效：cookie 原样保留，下一次请求再试
    return null
  }
  let payload: any
  try {
    payload = await resp.json()
  } catch {
    clearTokens()
    return null
  }
  if (resp.status !== 200 || payload?.code !== 'FA-0000' || !payload?.data?.accessToken) {
    // refresh 也失效（过期/改密后作废）：登录态到此为止，两把钥匙一起清
    clearTokens()
    return null
  }
  setToken(payload.data.accessToken, payload.data.expiresIn)
  setRefreshToken(payload.data.refreshToken)
  return payload.data.accessToken as string
}

type Options = {
  method?: string
  body?: unknown
  query?: Record<string, unknown>
  /** true = 直连人脸服务（/face-api），鉴权用 checkinTicket 而不是 JWT */
  face?: boolean
  /** true = 原始 Response（下载场景），这里用不到但保持接口一致 */
  raw?: boolean
  /** 内部标记：401 续期后已重试过一次，防止换出的新令牌仍 401 时无限循环 */
  retried?: boolean
}

function buildUrl(path: string, query?: Record<string, unknown>): string {
  const qs = new URLSearchParams()
  Object.entries(query ?? {}).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    qs.append(k, String(v))
  })
  const suffix = qs.toString() ? `?${qs}` : ''
  return `${BASE}${path}${suffix}`
}

export async function request<T>(path: string, options: Options = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  const token = getToken()
  if (token) headers.Authorization = `Bearer ${token}`
  let body: BodyInit | undefined
  if (options.body instanceof FormData) {
    body = options.body
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  let resp: Response
  try {
    resp = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      body
    })
  } catch {
    // 教室里网络掉线是常态，不要把 "Failed to fetch" 直接甩给学生
    throw new ApiError('NETWORK', '网络不太好，请检查网络后重试', 0)
  }

  if (resp.status === 401) {
    // access 过期是常态而不是异常：先用 refresh cookie 静默续期并重试一次，
    // 换不出来（refresh 也失效/网络断且无可用令牌）才清态回登录页。
    if (!options.retried) {
      const fresh = await refreshAccessToken()
      if (fresh) return request<T>(path, { ...options, retried: true })
    }
    clearTokens()
    window.dispatchEvent(new CustomEvent('gg:unauthorized'))
    throw new ApiError('FA-1002', ERROR_MESSAGES['FA-1002'], 401)
  }

  let payload: any
  try {
    payload = await resp.json()
  } catch {
    throw new ApiError(`HTTP-${resp.status}`, '服务暂时不可用，请稍后重试', resp.status)
  }
  const code: string = payload?.code ?? `HTTP-${resp.status}`
  if (code !== 'FA-0000') {
    const message = payload?.message || ERROR_MESSAGES[code] || '操作失败'
    throw new ApiError(code, message, resp.status, payload?.data ?? null)
  }
  return payload.data as T
}

export const api = {
  get: <T>(path: string, query?: Record<string, unknown>) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body })
}

/* ---------------- 人脸服务 ---------------- */

/** 人脸服务的鉴权是 checkinTicket（不是 JWT），所以单独一套薄封装。 */
export function faceRequest<T>(
  path: string,
  ticket: string,
  init: { method?: string; body?: unknown } = {}
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', Authorization: `Bearer ${ticket}` }
  let body: BodyInit | undefined
  if (init.body instanceof FormData) body = init.body
  else if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(init.body)
  }
  return fetch(`${FACE_BASE}${path}`, { method: init.method ?? 'GET', headers, body }).then(async (resp) => {
    let payload: any
    try {
      payload = await resp.json()
    } catch {
      throw new ApiError(`HTTP-${resp.status}`, ERROR_MESSAGES['FA-9002'], resp.status)
    }
    const code: string = payload?.code ?? `HTTP-${resp.status}`
    if (code !== 'FA-0000') {
      throw new ApiError(code, payload?.message || ERROR_MESSAGES[code] || '人脸服务异常', resp.status, payload?.data ?? null)
    }
    return payload.data as T
  })
}

export function messageOf(err: unknown): string {
  if (err instanceof ApiError) return err.message
  if (err instanceof Error) return err.message
  return '未知错误'
}

/**
 * 采集样本。
 * 注意鉴权用的是**学生 JWT**（`studentJwt` scheme），不是 checkinTicket ——
 * 所以不能复用 faceRequest，否则人脸服务会把 JWT 当票据查 Redis，必然 401。
 */
export async function enrollSample(blob: Blob): Promise<import('./types').EnrollSampleData> {
  const form = new FormData()
  form.append('frame', blob, 'sample.jpg')
  const token = getToken()
  const resp = await fetch(`${FACE_BASE}/face-api/enroll/sample`, {
    method: 'POST',
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    body: form
  })
  let payload: any
  try {
    payload = await resp.json()
  } catch {
    throw new ApiError(`HTTP-${resp.status}`, ERROR_MESSAGES['FA-9002'], resp.status)
  }
  const code: string = payload?.code ?? `HTTP-${resp.status}`
  if (code !== 'FA-0000') {
    throw new ApiError(code, payload?.message || ERROR_MESSAGES[code] || '样本质检失败', resp.status, payload?.data)
  }
  return payload.data as import('./types').EnrollSampleData
}
