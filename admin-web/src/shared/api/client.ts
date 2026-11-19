/**
 * 统一 HTTP 客户端。
 *
 * 三条约定（对应 §6.2）：
 * 1. 只按 `code` 判断成败，不解析 message —— 后端文案会改，code 不会。
 * 2. 401 一律清令牌并回登录页：否则页面会停在一堆 401 的空表格上，用户不知道要重新登录。
 * 3. 业务错误抛 ApiError，由调用方决定是 toast 还是就地提示。
 */
import { ERROR_MESSAGES } from './errors'

const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? ''

export class ApiError extends Error {
  code: string
  status: number
  constructor(code: string, message: string, status = 0) {
    super(message)
    this.code = code
    this.status = status
  }
}

export const TOKEN_KEY = 'gg.admin.token'

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

  const resp = await fetch(buildUrl(path, options.query), {
    method: options.method ?? 'GET',
    headers,
    body
  })

  if (resp.status === 401) {
    setToken(null)
    window.dispatchEvent(new CustomEvent('gg:unauthorized'))
    throw new ApiError('FA-1002', ERROR_MESSAGES['FA-1002'], 401)
  }

  if (options.raw) {
    if (!resp.ok) throw new ApiError(`HTTP-${resp.status}`, '下载失败', resp.status)
    return (await resp.blob()) as unknown as T
  }

  let payload: any
  try {
    payload = await resp.json()
  } catch {
    throw new ApiError(`HTTP-${resp.status}`, '服务返回了非 JSON 响应', resp.status)
  }
  const code: string = payload?.code ?? `HTTP-${resp.status}`
  if (code !== 'FA-0000') {
    // 后端 message 更具体时优先用它（例如「仍有 3 名学生」），但 code 永远保留
    const message = payload?.message || ERROR_MESSAGES[code] || '操作失败'
    throw new ApiError(code, message, resp.status)
  }
  return payload.data as T
}

export const api = {
  get: <T>(path: string, query?: Record<string, unknown>) => request<T>(path, { query }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  download: (path: string, query?: Record<string, unknown>) =>
    request<Blob>(path, { query, raw: true })
}

export function messageOf(err: unknown): string {
  if (err instanceof ApiError) return `${err.message}（${err.code}）`
  if (err instanceof Error) return err.message
  return '未知错误'
}
