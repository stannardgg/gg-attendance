/**
 * 学生端 WebSocket：订阅"老师发起了签到"。
 *
 * 只有两条事件（EVT_SESSION_STARTED / EVT_SESSION_CLOSED），所以不引任何库：
 * 一个重连退避 + 一个心跳足够。浏览器 WS 不能设请求头，JWT 只能走 query（契约已登记）。
 */
import { getToken } from '@/shared/api/client'

export interface SessionEvent {
  type: 'EVT_SESSION_STARTED' | 'EVT_SESSION_CLOSED'
  data?: { sessionId?: number; title?: string; className?: string; courseName?: string }
}

type Handler = (e: SessionEvent) => void

export class NoticeSocket {
  private ws: WebSocket | null = null
  private retry = 0
  private closed = false
  private timer: number | undefined
  private ping: number | undefined

  constructor(private readonly onEvent: Handler) {}

  connect() {
    const token = getToken()
    if (!token || this.closed) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const url = `${proto}://${location.host}/api/v1/mobile/ws?token=${encodeURIComponent(token)}`
    try {
      this.ws = new WebSocket(url)
    } catch {
      this.schedule()
      return
    }
    this.ws.onopen = () => {
      this.retry = 0
      // 心跳：教室 WiFi 的 NAT 超时很短，不发心跳会被静默断开
      this.ping = window.setInterval(() => this.ws?.readyState === 1 && this.ws.send('{"type":"ping"}'), 25000)
    }
    this.ws.onmessage = (evt) => {
      let msg: any
      try {
        msg = JSON.parse(evt.data)
      } catch {
        return
      }
      const type = msg?.type ?? msg?.event
      if (type === 'EVT_SESSION_STARTED' || type === 'EVT_SESSION_CLOSED') {
        this.onEvent({ type, data: msg.data ?? msg })
      }
    }
    this.ws.onclose = () => {
      window.clearInterval(this.ping)
      this.schedule()
    }
    this.ws.onerror = () => this.ws?.close()
  }

  private schedule() {
    if (this.closed) return
    if (this.retry >= 6) return
    this.retry += 1
    this.timer = window.setTimeout(() => this.connect(), Math.min(1000 * 2 ** this.retry, 20000))
  }

  close() {
    this.closed = true
    window.clearTimeout(this.timer)
    window.clearInterval(this.ping)
    this.ws?.close()
    this.ws = null
  }
}
