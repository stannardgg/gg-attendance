/**
 * 签到会话上下文。
 *
 * 放 sessionStorage 而不是内存单例：学生在采集页手滑刷新（或 iOS 从后台恢复页面）
 * 不应该被踢回签到入口重新换票。ticket 本身 TTL 只有 60 秒，落 sessionStorage 的风险可控。
 */
import { reactive } from 'vue'
import type { JoinData } from '@/shared/api/types'

const KEY = 'gg.checkin.join'

export interface JoinState {
  sid: number | null
  ticket: string
  ttl: number
  className: string
  courseName: string
  sessionTitle: string
  remainingSec: number
  challengeRequired: boolean
  retryLeft: number
  joinedAt: number
}

export const checkin = reactive<JoinState>({
  sid: null,
  ticket: '',
  ttl: 0,
  className: '',
  courseName: '',
  sessionTitle: '',
  remainingSec: 0,
  challengeRequired: true,
  retryLeft: 0,
  joinedAt: 0
})

export function setJoin(sid: number, data: JoinData) {
  Object.assign(checkin, {
    sid,
    ticket: data.checkinTicket,
    ttl: data.ttl,
    className: data.className,
    courseName: data.courseName,
    sessionTitle: data.sessionTitle ?? '',
    remainingSec: data.remainingSec ?? 0,
    challengeRequired: data.challengeRequired,
    retryLeft: data.retryLeft ?? 0,
    joinedAt: Date.now()
  })
  try {
    sessionStorage.setItem(KEY, JSON.stringify(checkin))
  } catch {
    /* 隐私模式下写不进去，内存态照样能用 */
  }
}

export function restoreJoin(): boolean {
  if (checkin.sid) return true
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return false
    const parsed = JSON.parse(raw) as JoinState
    // ticket 只有 60 秒，过期就别当有效了
    if (!parsed.sid || Date.now() - parsed.joinedAt > parsed.ttl * 1000) {
      clearJoin()
      return false
    }
    Object.assign(checkin, parsed)
    return true
  } catch {
    return false
  }
}

export function clearJoin() {
  Object.assign(checkin, {
    sid: null,
    ticket: '',
    ttl: 0,
    className: '',
    courseName: '',
    sessionTitle: '',
    remainingSec: 0,
    retryLeft: 0,
    joinedAt: 0
  })
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}
