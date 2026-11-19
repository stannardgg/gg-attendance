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

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}
export function setToken(token: string | null) {
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

type Options = {
  method?: string
  body?: unknown
  query?: Record<string, unknown>
  /** true = 直连人脸服务（/face-api），鉴权用 checkinTicket 而不是 JWT */
  face?: boolean
  /** true = 原始 Response（下载场景），这里用不到但保持接口一致 */
  raw?: boolean
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
    setToken(null)
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
