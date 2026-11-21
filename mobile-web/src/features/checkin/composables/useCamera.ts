/**
 * 摄像头封装。这里几乎每一段都对应 §7.4.1 的一条真机问题，
 * 改之前先读那张表 —— 六条都是课堂现场会直接让学生签不上到的问题。
 */
import { onBeforeUnmount, ref, shallowRef } from 'vue'
import { loadDevicePref, saveDevicePref } from '@/shared/utils'

export type CameraState = 'IDLE' | 'REQUESTING' | 'READY' | 'ERROR'

export interface DeviceOption {
  deviceId: string
  label: string
}

/** 采集与上传的尺寸参数。★ 探测帧与校验帧必须共用这一个常量（§7.4.1 #4）。 */
export const CAPTURE = { width: 640, height: 480, quality: 0.7 } as const

/** 单帧的轻量统计：亮度均值用来看黑屏，方差用来看"镜头前到底有没有东西"。 */
export interface SmallFrame {
  canvas: HTMLCanvasElement
  mean: number
  variance: number
}

const BLACK_MEAN = 12 // 低于这个亮度基本就是全黑
const HINT_AFTER_BLANK = 3 // 连续 3 帧取不到才提示（单帧失败是曝光/挥手，正常）
const RECONNECT_AFTER_BLANK = 8 // 连续 8 帧仍取不到 → 判驱动假死，重连
const MAX_RECONNECT = 2 // 最多重连 2 次，无限重连会打断暗光下的正常使用

export function useCamera() {
  const video = ref<HTMLVideoElement | null>(null)
  const stream = shallowRef<MediaStream | null>(null)
  const state = ref<CameraState>('IDLE')
  const error = ref('')
  const devices = ref<DeviceOption[]>([])
  const activeDeviceId = ref('')
  const blackHint = ref(false)

  /** 程序内改选中项时要屏蔽 change 回调，否则会误触发一次重开流（§7.4.1 #6）。 */
  let selfSwitch = false
  let blankStreak = 0
  let reconnects = 0
  let watchdog: number | undefined
  let lastMean = -1
  let stableStreak = 0

  /* ---------------- 设备枚举 ---------------- */

  /**
   * ★ 只能在**没有活动流**的时候调用（§7.4.1 #1）。
   * 首次运行时 label 是空的，需要一个"临时开流 → 立刻停 → 再枚举"的解锁动作，
   * 这个动作同样发生在真正开流之前，所以不违反约束。
   */
  async function refreshDevices(): Promise<DeviceOption[]> {
    if (!navigator.mediaDevices?.enumerateDevices) return []
    if (stream.value) {
      // 有活动流时绝不枚举：直接把上次的列表还回去
      return devices.value
    }
    let list = await navigator.mediaDevices.enumerateDevices()
    let cams = list.filter((d) => d.kind === 'videoinput')
    if (cams.length && cams.every((c) => !c.label)) {
      try {
        const tmp = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
        tmp.getTracks().forEach((t) => t.stop())
        list = await navigator.mediaDevices.enumerateDevices()
        cams = list.filter((d) => d.kind === 'videoinput')
      } catch {
        // 用户拒绝授权时留空 label，后面 open() 会给出明确提示
      }
    }
    devices.value = cams.map((c, i) => ({ deviceId: c.deviceId, label: c.label || `摄像头 ${i + 1}` }))
    return devices.value
  }

  /* ---------------- 开流 / 关流 ---------------- */

  async function open(preferred?: string): Promise<boolean> {
    if (!navigator.mediaDevices?.getUserMedia) {
      state.value = 'ERROR'
      error.value = '当前浏览器不支持摄像头，请用系统浏览器或 Chrome / Safari 打开'
      return false
    }
    state.value = 'REQUESTING'
    error.value = ''
    blackHint.value = false
    blankStreak = 0

    // 设备偏好来自上次会话；设备被拔掉/换过后要能回退默认（§7.4.3）
    const wanted = preferred || activeDeviceId.value || loadDevicePref()
    if (wanted && devices.value.length && !devices.value.some((d) => d.deviceId === wanted)) {
      saveDevicePref('')
    }

    const video0: MediaTrackConstraints = { width: { ideal: 640 }, height: { ideal: 480 } }
    try {
      stream.value = await navigator.mediaDevices.getUserMedia({
        video: wanted ? { ...video0, deviceId: { exact: wanted } } : { ...video0, facingMode: { ideal: 'user' } },
        audio: false
      })
    } catch (err) {
      if (wanted) {
        // 指定设备打不开就回退默认，别让学生卡在"设备无效"上
        saveDevicePref('')
        return open('')
      }
      state.value = 'ERROR'
      error.value = describeError(err)
      return false
    }

    const track = stream.value.getVideoTracks()[0]
    activeDeviceId.value = track?.getSettings().deviceId || wanted
    if (activeDeviceId.value) saveDevicePref(activeDeviceId.value)

    if (video.value) {
      video.value.srcObject = stream.value
      try {
        await video.value.play()
      } catch {
        /* iOS 需要用户手势，页面里点过"开始"就已有手势上下文 */
      }
    }
    state.value = 'READY'
    reconnects = 0
    startWatchdog()
    return true
  }

  function close() {
    stopWatchdog()
    stream.value?.getTracks().forEach((t) => t.stop())
    stream.value = null
    if (video.value) video.value.srcObject = null
    state.value = 'IDLE'
  }

  function describeError(err: unknown): string {
    const name = (err as { name?: string })?.name
    if (name === 'NotAllowedError') return '你拒绝了摄像头权限。请点浏览器地址栏的图标允许摄像头后重试'
    if (name === 'NotFoundError') return '没有找到可用的摄像头'
    if (name === 'NotReadableError') return '摄像头被其它程序占用了，关掉其它用摄像头的应用再试'
    if (name === 'OverconstrainedError') return '当前摄像头不支持所需分辨率'
    return '摄像头打开失败，请刷新页面重试'
  }

  /**
   * 热切换设备：允许在签到进行中切，切完由调用方重新进入 READY（§7.4.3）。
   * 这里先关流再开流，天然满足"枚举只在无流时进行"。
   */
  async function switchDevice(deviceId: string): Promise<boolean> {
    if (deviceId === activeDeviceId.value) return true
    selfSwitch = true
    close()
    const ok = await open(deviceId)
    // 异步复位标志：同一次事件循环里丢掉浏览器"误发"的 change（§7.4.1 #6）
    setTimeout(() => (selfSwitch = false), 0)
    return ok
  }

  /* ---------------- 取帧 ---------------- */

  let scratch: HTMLCanvasElement | null = null
  let probeCtx: CanvasRenderingContext2D | null = null
  const probeCanvas = document.createElement('canvas')
  probeCanvas.width = 32
  probeCanvas.height = 32
  const probeCtx2 = probeCanvas.getContext('2d', { willReadFrequently: true })

  /**
   * ★ 唯一的取帧入口，探测与采集都走它。参数只有一份，不存在"两处用了不同降采样"的可能。
   */
  function captureSmall(): SmallFrame | null {
    const el = video.value
    if (!el || !el.videoWidth || !el.videoHeight) return null
    if (!scratch) {
      scratch = document.createElement('canvas')
      scratch.width = CAPTURE.width
      scratch.height = CAPTURE.height
      probeCtx = scratch.getContext('2d')
    }
    const ctx = probeCtx
    if (!ctx) return null
    ctx.drawImage(el, 0, 0, CAPTURE.width, CAPTURE.height)

    // 用一个 32×32 的缩略图算亮度/方差，比 getImageData 全图快两个数量级
    if (!probeCtx2) return { canvas: scratch, mean: 128, variance: 0 }
    probeCtx2.drawImage(scratch, 0, 0, 32, 32)
    const { data } = probeCtx2.getImageData(0, 0, 32, 32)
    let sum = 0
    let sumSq = 0
    for (let i = 0; i < data.length; i += 4) {
      const y = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]
      sum += y
      sumSq += y * y
    }
    const n = data.length / 4
    const mean = sum / n
    return { canvas: scratch, mean, variance: sumSq / n - mean * mean }
  }

  /** 注意：返回的是完整 data URL（带 "data:image/jpeg;base64," 前缀）。
   *  要交给 face-api 时先用 shared/utils 的 stripDataUrl 剥前缀，契约收的是纯 base64。 */
  function toDataUrl(f: SmallFrame): string {
    return f.canvas.toDataURL('image/jpeg', CAPTURE.quality)
  }

  function toBlob(f: SmallFrame): Promise<Blob | null> {
    return new Promise((resolve) => f.canvas.toBlob((b) => resolve(b), 'image/jpeg', CAPTURE.quality))
  }

  /* ---------------- 流健康监控 ---------------- */

  function startWatchdog() {
    stopWatchdog()
    watchdog = window.setInterval(() => {
      const st = stream.value
      const track = st?.getVideoTracks()[0]
      if (!track || !st) return

      // ★ 先判"流是不是断了"，再判"这一帧是不是偏黑"——两者不能混为一谈（§7.4.1 #2）
      const broken = track.readyState !== 'live' || (video.value?.videoWidth ?? 0) === 0
      if (broken) {
        if (reconnects < MAX_RECONNECT) {
          reconnects += 1
          error.value = '画面中断，正在重新连接摄像头…'
          close()
          open(activeDeviceId.value)
        } else {
          state.value = 'ERROR'
          error.value = '摄像头无响应，请刷新页面后重试'
        }
        return
      }

      const f = captureSmall()
      if (!f || f.mean < BLACK_MEAN) {
        blankStreak += 1
        if (blankStreak >= RECONNECT_AFTER_BLANK && reconnects < MAX_RECONNECT) {
          reconnects += 1
          blankStreak = 0
          error.value = '画面一直没有出来，正在重新连接摄像头…'
          close()
          open(activeDeviceId.value)
        } else if (blankStreak >= HINT_AFTER_BLANK) {
          blackHint.value = true
        }
        return
      }
      blankStreak = 0
      blackHint.value = false
      lastMean = f.mean
    }, 500)
  }

  function stopWatchdog() {
    if (watchdog) window.clearInterval(watchdog)
    watchdog = undefined
  }

  /**
   * 本地"可以开始"判断：够亮、画面有内容、连续两次稳定。
   * ★ 连续两次都通过才拉起验证（§7.4.2 #1）—— 单帧误判不该把学生拖进一次 3 秒采集。
   * 注意：人脸尺寸只有服务端能判，这里不做"太远/太近"的结论。
   */
  function probe(): { ok: boolean; reason: string } {
    const f = captureSmall()
    if (!f) return { ok: false, reason: '画面还没出来' }
    if (f.mean < 45) {
      stableStreak = 0
      return { ok: false, reason: '光线太暗，请往亮处站一点' }
    }
    if (f.mean > 225) {
      stableStreak = 0
      return { ok: false, reason: '光线太强，请避开强光' }
    }
    if (f.variance < 12) {
      stableStreak = 0
      return { ok: false, reason: '镜头前看起来没有人' }
    }
    const moved = lastMean >= 0 && Math.abs(f.mean - lastMean) > 6
    lastMean = f.mean
    if (moved) {
      stableStreak = 0
      return { ok: false, reason: '请保持不动' }
    }
    stableStreak += 1
    return { ok: stableStreak >= 2, reason: stableStreak >= 2 ? '' : '正在确认画面…' }
  }

  function resetProbe() {
    stableStreak = 0
    lastMean = -1
  }

  onBeforeUnmount(() => {
    // 不 stop 会残留设备占用，下次打不开摄像头（§7.4.3）
    close()
  })

  return {
    video,
    stream,
    state,
    error,
    devices,
    activeDeviceId,
    blackHint,
    isSelfSwitch: () => selfSwitch,
    refreshDevices,
    open,
    close,
    switchDevice,
    captureSmall,
    toDataUrl,
    toBlob,
    probe,
    resetProbe
  }
}
