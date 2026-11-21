/** 小工具：时间格式、设备指纹、本地偏好。 */

export function fmtTime(t?: string | null): string {
  if (!t) return '—'
  return t.replace('T', ' ').slice(0, 19)
}

export function fmtClock(t?: string | null): string {
  if (!t) return '—'
  return t.replace('T', ' ').slice(11, 16)
}

/** 剩余秒 → mm:ss（剩余时间在大屏/入口页都要一眼可读） */
export function mmss(sec: number): string {
  const s = Math.max(0, Math.floor(sec))
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

/**
 * canvas.toDataURL() 返回的是 "data:image/jpeg;base64,<payload>"，
 * 而服务端（契约 face-api.yaml）要的是**纯 base64**。直接透传会让
 * base64.b64decode(validate=True) 报 "Only base64 data is allowed"。
 */
export function stripDataUrl(dataUrl: string): string {
  const comma = dataUrl.indexOf(',')
  return comma >= 0 ? dataUrl.slice(comma + 1) : dataUrl
}

/** 设备指纹：UA + 摄像头 deviceId 的弱哈希，只用于服务端风控归因，不上报硬件唯一标识。 */export function deviceHash(deviceId: string): string {
  const raw = `${navigator.userAgent}|${deviceId}`
  let h1 = 0x811c9dc5
  let h2 = 0x01000193
  for (let i = 0; i < raw.length; i++) {
    const c = raw.charCodeAt(i)
    h1 = (h1 ^ c) * 0x01000193
    h2 = (h2 + c * (i + 7)) >>> 0
  }
  const a = (h1 >>> 0).toString(16).padStart(8, '0')
  const b = (h2 >>> 0).toString(16).padStart(8, '0')
  return `${a}${b}`.slice(0, 32)
}

const DEVICE_PREF = 'gg.mobile.camera'

export function loadDevicePref(): string {
  return localStorage.getItem(DEVICE_PREF) ?? ''
}
export function saveDevicePref(deviceId: string) {
  if (deviceId) localStorage.setItem(DEVICE_PREF, deviceId)
  else localStorage.removeItem(DEVICE_PREF)
}

/** 微信内置浏览器：getUserMedia 基本不可用，必须引导到外部浏览器（§7.1）。 */
export function isWeChat(): boolean {
  return /MicroMessenger/i.test(navigator.userAgent)
}

/** getUserMedia 只在 https 或 localhost 下可用，提前判断比等它抛 NotFoundError 好。 */
export function isSecureContextOk(): boolean {
  return window.isSecureContext || location.hostname === 'localhost' || location.hostname === '127.0.0.1'
}
