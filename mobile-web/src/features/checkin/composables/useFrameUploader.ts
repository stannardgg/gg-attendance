/**
 * 采集 + 上传状态机（§7.4）。
 *
 *   IDLE → REQUESTING → READY → BASELINE → CAPTURING → SUBMITTING → DONE / ERROR
 *
 * 三条不能改的设计：
 * 1. 采集节奏由**本地 250ms 定时器**保证，上传串行排队，队列最多积压 4 帧。
 *    改成"上一帧返回再采下一帧"，实际帧率会被 RTT 拖到 2~3 fps，
 *    2 秒窗口只剩 5~6 帧，服务端 MIN_FRAMES=8 永远达不到，动作判据直接失效。
 * 2. BASELINE 阶段不许显示指令；指令渲染与首帧采集必须在同一帧触发（§4.4.3）。
 * 3. 多脸要立刻停——继续上传只会继续被拒，还会白烧流量。
 */
import { ref, shallowRef } from 'vue'
import { ApiError, faceRequest } from '@/shared/api/client'
import { ERROR_MESSAGES } from '@/shared/api/errors'
import { stripDataUrl } from '@/shared/utils'
import type { ChallengeStartData, FinishData, FrameData } from '@/shared/api/types'
import type { SmallFrame } from './useCamera'

export type UploaderState =
  | 'IDLE'
  | 'REQUESTING'
  | 'READY'
  | 'BASELINE'
  | 'CAPTURING'
  | 'SUBMITTING'
  | 'DONE'
  | 'ERROR'

/** 4 fps 固定节奏，与 §7.1 一致 */
export const FRAME_INTERVAL = 250
/** 队列上限：超过就丢最旧的一帧，并累计 droppedFrames 便于排查 */
export const MAX_QUEUE = 4

interface Options {
  ticket: string
  /** 取当前设备指纹。写成函数是因为开流之前 deviceId 还是空的 */
  deviceHash: () => string
  capture: () => SmallFrame | null
  toBlob: (f: SmallFrame) => Promise<Blob | null>
  onMultiFace?: () => void
}

export function useFrameUploader(opts: Options) {
  const state = ref<UploaderState>('IDLE')
  const instruction = ref('')
  const hint = ref('')
  const progress = ref(0)
  const detected = ref(0)
  const required = ref(0)
  const windowLeft = ref(0)
  const droppedFrames = ref(0)
  const sentFrames = ref(0)
  const errorText = ref('')
  const blockedGate = ref<string | null>(null)
  /** 服务端判定失败次数已达上限（CR-023）：FA-3005 或 finish.retryLeft=0 */
  const retryBlocked = ref(false)
  const challenge = shallowRef<ChallengeStartData | null>(null)
  const finish = shallowRef<FinishData | null>(null)
  const paused = ref(false)

  let seq = 0
  let timer: number | undefined
  let windowTimer: number | undefined
  let uploading = false
  let queue: { seq: number; blob: Promise<Blob | null> }[] = []

  function reset() {
    state.value = 'IDLE'
    instruction.value = ''
    hint.value = ''
    progress.value = 0
    detected.value = 0
    required.value = 0
    windowLeft.value = 0
    droppedFrames.value = 0
    sentFrames.value = 0
    errorText.value = ''
    blockedGate.value = null
    retryBlocked.value = false
    challenge.value = null
    finish.value = null
    paused.value = false
    seq = 0
    queue = []
    uploading = false
    clearTimers()
  }

  function clearTimers() {
    if (timer) window.clearTimeout(timer)
    if (windowTimer) window.clearInterval(windowTimer)
    timer = undefined
    windowTimer = undefined
  }

  /**
   * 领挑战。基线帧在指令出现之前抓，抓完立刻提交；
   * 服务端返回后**同一帧**渲染指令并发首帧 —— 中间不许插"请看清指令"的等待动画。
   */
  async function begin() {
    reset()
    state.value = 'BASELINE'
    const baseline = opts.capture()
    if (!baseline) {
      state.value = 'ERROR'
      errorText.value = '画面还没准备好，请稍等再试'
      return
    }
    const body: Record<string, unknown> = {
      // 契约要纯 base64；toDataURL 带 "data:image/jpeg;base64," 前缀，必须剥掉
      baselineFrame: stripDataUrl(baseline.canvas.toDataURL('image/jpeg', 0.7)),
      deviceHash: opts.deviceHash()
    }
    try {
      const data = await faceRequest<ChallengeStartData>('/face-api/challenge/start', opts.ticket, {
        method: 'POST',
        body
      })
      challenge.value = data
      required.value = data.needCount ?? 0
      // ↓↓↓ 从这里开始两手同时动：指令进响应式状态、采集立刻开跑，中间没有任何 await
      instruction.value = data.instruction
      state.value = 'CAPTURING'
      startWindow(data.windowMs)
      tick()
    } catch (err) {
      fail(err)
    }
  }

  function startWindow(windowMs: number) {
    const end = Date.now() + windowMs
    windowLeft.value = Math.ceil(windowMs / 1000)
    if (windowTimer) window.clearInterval(windowTimer)
    windowTimer = window.setInterval(() => {
      windowLeft.value = Math.max(0, Math.ceil((end - Date.now()) / 1000))
      if (Date.now() >= end) {
        window.clearInterval(windowTimer)
        windowTimer = undefined
        // 窗口到期即收卷：即便动作没够也交上去，由服务端按 action_count 门禁判
        void submit()
      }
    }, 200)
  }

  function tick() {
    if (state.value !== 'CAPTURING' || paused.value) return
    const f = opts.capture()
    if (f) enqueue(f)
    timer = window.setTimeout(tick, FRAME_INTERVAL)
  }

  function enqueue(f: SmallFrame) {
    // 队列满时丢弃**当前这一帧**，绝不能丢已编号的旧帧。
    // seq 一旦出现空洞，服务端 st.seq 就永远追不上，之后每一帧都会被当乱序丢弃，
    // 整场挑战直接报废——而前端对此完全无感（accepted=false 只是静默返回）。
    if (queue.length >= MAX_QUEUE) {
      droppedFrames.value += 1
      return
    }
    queue.push({ seq: seq++, blob: opts.toBlob(f) })
    void drain()
  }

  async function drain() {
    if (uploading) return
    uploading = true
    try {
      while (queue.length) {
        const item = queue.shift()
        if (!item) break
        const blob = await item.blob
        if (!blob) continue
        const form = new FormData()
        form.append('frame', blob, `f${item.seq}.jpg`)
        form.append('seq', String(item.seq))
        try {
          const data = await faceRequest<FrameData>(
            `/face-api/challenge/${challenge.value?.challengeId}/frame`,
            opts.ticket,
            { method: 'POST', body: form }
          )
          sentFrames.value += 1
          onFrame(data)
        } catch (err) {
          fail(err)
          return
        }
        if (state.value !== 'CAPTURING' && state.value !== 'SUBMITTING') return
      }
    } finally {
      uploading = false
    }
  }

  /** 等上传队列排空。设上限是为了不让交卷被网络问题无限拖住。
   *  上限按最坏情况留够：尺度动作窗口 2.5s、单帧 CPU 推理 ~0.85s，排队积压约 10s。
   *  （CR-018 前最坏情况是 MOUTH_TWICE 的 3s 窗口，上限按更长的那个留即可。） */
  async function drainSettled(timeoutMs = 15000) {
    const deadline = Date.now() + timeoutMs
    while ((queue.length > 0 || uploading) && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 50))
    }
  }

  function onFrame(d: FrameData) {
    if (!d.accepted) return // 乱序/过期帧，服务端已丢，前端不必反应
    hint.value = d.hint ?? ''
    progress.value = d.progress ?? 0
    if (typeof d.detectedCount === 'number') detected.value = d.detectedCount
    if (typeof d.requiredCount === 'number' && d.requiredCount) required.value = d.requiredCount

    // 服务端在 400 里会明确给 FA-4002；但 200 里出现 faceCount≥2 时也要立刻收手
    if ((d.faceCount ?? 0) >= 2) {
      blockMultiFace()
      return
    }
    if ((d.progress ?? 0) >= 1) void submit()
  }

  function blockMultiFace() {
    clearTimers()
    queue = []
    state.value = 'ERROR'
    blockedGate.value = 'multi_face'
    errorText.value = ERROR_MESSAGES['FA-4002']
    opts.onMultiFace?.()
  }

  async function submit() {
    if (state.value === 'SUBMITTING' || state.value === 'DONE' || state.value === 'ERROR') return
    clearTimers()
    state.value = 'SUBMITTING'
    // 交卷前必须把已采集的帧送完。服务端的动作计数按收到的帧序累计，
    // 交卷早于尾帧到达会让这几帧永远进不了判定——用户看到的就是「动作没检测到」。
    await drainSettled()
    try {
      const data = await faceRequest<FinishData>(
        `/face-api/challenge/${challenge.value?.challengeId}/finish`,
        opts.ticket,
        { method: 'POST' }
      )
      finish.value = data
      state.value = data.passed ? 'DONE' : 'ERROR'
      if (!data.passed) {
        blockedGate.value = data.rejectGate ?? 'match'
        errorText.value = ERROR_MESSAGES[gateCode(data.rejectGate)] ?? '签到未通过'
        // CR-023：权威计数在服务端。retryLeft=0 时隐藏重试按钮——
        // 本地 attempts 只是展示兜底，刷新页面就清零，不能当门禁用。
        if (typeof data.retryLeft === 'number' && data.retryLeft <= 0) {
          retryBlocked.value = true
        }
      }
    } catch (err) {
      fail(err)
    }
  }

  function fail(err: unknown) {
    clearTimers()
    queue = []
    state.value = 'ERROR'
    if (err instanceof ApiError) {
      blockedGate.value = err.gate
      errorText.value = err.message || ERROR_MESSAGES[err.code] || '签到失败'
      const data = err.data as { retryLeft?: number; hint?: string } | null
      if (data?.hint) hint.value = data.hint
      // CR-023：start 阶段被 FA-3005 拒（旧票据偷跑第 4 次）同样锁死重试
      if (err.code === 'FA-3005') {
        retryBlocked.value = true
      }
      if (err.code === 'FA-4002') {
        blockedGate.value = 'multi_face'
        opts.onMultiFace?.()
      }
      // finish 的 400 里带着完整门禁明细，尽量留给结果页归因
      if (err.code.startsWith('FA-40') && !finish.value) {
        finish.value = { passed: false, rejectGate: (err.gate as FinishData['rejectGate']) ?? null }
      }
    } else {
      errorText.value = '网络异常，签到未完成'
    }
  }

  function gateCode(gate?: string | null): string {
    switch (gate) {
      case 'liveness':
        return 'FA-4004'
      case 'identity':
        return 'FA-4007'
      case 'action_count':
        return 'FA-4003'
      case 'multi_face':
        return 'FA-4002'
      case 'no_face':
        return 'FA-4001'
      default:
        return 'FA-4005'
    }
  }

  /** 页面切后台就暂停：iOS 会把 setTimeout 节流到 1s 以上，继续采只会攒一堆废帧 */
  function setPaused(v: boolean) {
    paused.value = v
    if (v) {
      clearTimers()
    } else if (state.value === 'CAPTURING') {
      tick()
    }
  }

  function abort() {
    clearTimers()
    queue = []
    if (state.value !== 'DONE') state.value = 'IDLE'
  }

  return {
    state,
    instruction,
    hint,
    progress,
    detected,
    required,
    windowLeft,
    droppedFrames,
    sentFrames,
    errorText,
    blockedGate,
    retryBlocked,
    challenge,
    finish,
    paused,
    begin,
    submit,
    abort,
    reset,
    setPaused
  }
}
