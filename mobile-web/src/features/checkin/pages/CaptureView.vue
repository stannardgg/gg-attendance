<script setup lang="ts">
/**
 * 采集页 —— 全流程的核心。
 *
 * 顺序是死的（§4.4.3 / §7.4）：
 *   开流 → 本地探测连续两次通过 → 静默抓基线帧 → 同一帧出指令并开始 4fps 采集
 *   窗口到点或动作达标 → finish → 三道门禁判定 → 结果
 *
 * 三个"看起来能跑但现场会翻车"的点，代码里都留了注释：
 * 1. 设备枚举必须在开流之前（§7.4.1 #1）
 * 2. 流断了要直接重连，不能拿亮度当断流依据（§7.4.1 #2）
 * 3. 采集节奏由本地定时器保证，不能被 RTT 拖住（§7.4）
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { useCamera } from '../composables/useCamera'
import { useFrameUploader } from '../composables/useFrameUploader'
import ActionPrompt from '../components/ActionPrompt.vue'
import FaceGuideOverlay from '../components/FaceGuideOverlay.vue'
import ResultPanel from '../components/ResultPanel.vue'
import { checkin, clearJoin, restoreJoin } from '../store'
import { auth } from '@/shared/store/auth'
import { deviceHash, isSecureContextOk, isWeChat } from '@/shared/utils'
import WeChatGuard from '@/shared/components/WeChatGuard.vue'

const router = useRouter()
const cam = useCamera()

/* 解构出来才能在模板里自动解包（嵌套在对象里的 ref 不会自动 unwrap） */
const {
  video: videoEl,
  state: camState,
  error: camError,
  devices,
  activeDeviceId,
  blackHint,
  refreshDevices,
  open: openCamera,
  close: closeCamera,
  switchDevice
} = cam

const uploader = useFrameUploader({
  ticket: checkin.ticket,
  deviceHash: () => deviceHash(activeDeviceId.value),
  capture: () => cam.captureSmall(),
  toBlob: (f) => cam.toBlob(f),
  onMultiFace: () => {
    // 多脸是硬阻断：立刻停采集 + 关流，别让摄像头继续开着（§7.4）
    closeCamera()
    phase.value = 'result'
  }
})

const {
  state: upState,
  instruction,
  hint,
  progress,
  detected,
  required: needCount,
  windowLeft,
  sentFrames,
  droppedFrames,
  errorText,
  blockedGate,
  retryBlocked,
  finish,
  paused,
  begin,
  abort: abortUpload,
  reset: resetUploader,
  setPaused
} = uploader

const attempts = ref(0)
const cooldownUntil = ref(0)
const backoffLeft = ref(0)
const phase = ref<'preparing' | 'probing' | 'capturing' | 'result'>('preparing')
const probeText = ref('正在准备摄像头…')
const needTap = ref(false)
const deviceSheet = ref(false)
const pausedByHidden = ref(false)

let probeTimer: number | undefined
let cooldownTimer: number | undefined
let faceAbsentSince = 0

const blocked = computed(() => (blockedGate.value === 'multi_face' ? 'multi_face' : null))
const showResult = computed(
  () => phase.value === 'result' && (upState.value === 'DONE' || upState.value === 'ERROR')
)
const deviceLabel = computed(
  () => devices.value.find((d) => d.deviceId === activeDeviceId.value)?.label || '默认摄像头'
)

/* ---------------- 摄像头 ---------------- */

function setVideo(el: unknown) {
  videoEl.value = (el as HTMLVideoElement | null) ?? null
}

async function startCamera() {
  needTap.value = false
  // ★ 先枚举（此时还没有流），再开流。顺序反了会掐断正在用的流。
  await refreshDevices()
  const ok = await openCamera()
  if (!ok) {
    needTap.value = true
    probeText.value = camError.value || '摄像头打开失败'
    return
  }
  phase.value = 'probing'
  startProbe()
}

/**
 * 本地探测：够亮、画面有内容、连续两次稳定才拉起验证（§7.4.2 #1）。
 * 人脸尺寸只有服务端能判，这里不下"太远/太近"的结论。
 */
function startProbe() {
  stopProbe()
  cam.resetProbe()
  probeTimer = window.setInterval(() => {
    if (phase.value !== 'probing' || paused.value) return
    if (Date.now() < cooldownUntil.value) return
    const r = cam.probe()
    probeText.value = r.ok ? '画面已就绪，即将开始' : r.reason
    if (!r.ok) {
      // 人离开镜头就重置失败计数，别让下一个人背前一个人的次数（§7.4.2 #2）
      if (r.reason.includes('没有人')) {
        if (!faceAbsentSince) faceAbsentSince = Date.now()
        if (Date.now() - faceAbsentSince > 2500) attempts.value = 0
      } else {
        faceAbsentSince = 0
      }
      return
    }
    faceAbsentSince = 0
    void startChallenge()
  }, 400)
}

function stopProbe() {
  if (probeTimer) window.clearInterval(probeTimer)
  probeTimer = undefined
}

async function startChallenge() {
  stopProbe()
  phase.value = 'capturing'
  await begin()
}

/** 采集结束统一收口：成功冷却 6 秒，失败按次数递增退避（§7.4.1 #5）。 */
watch(upState, (st) => {
  if (st === 'DONE') {
    phase.value = 'result'
    cooldownUntil.value = Date.now() + 6000
  } else if (st === 'ERROR') {
    attempts.value += 1
    phase.value = 'result'
    if (blockedGate.value !== 'multi_face') {
      const wait = Math.min(6000, 1800 + 900 * attempts.value)
      cooldownUntil.value = Date.now() + wait
      startBackoffCounter(wait)
    }
  }
})

function startBackoffCounter(wait: number) {
  window.clearInterval(cooldownTimer)
  backoffLeft.value = Math.ceil(wait / 1000)
  cooldownTimer = window.setInterval(() => {
    backoffLeft.value = Math.max(0, Math.ceil((cooldownUntil.value - Date.now()) / 1000))
    if (backoffLeft.value <= 0) window.clearInterval(cooldownTimer)
  }, 300)
}

/* ---------------- 结果页交互 ---------------- */

async function retry() {
  if (Date.now() < cooldownUntil.value) {
    showToast(`请等 ${backoffLeft.value} 秒再试`)
    return
  }
  resetUploader()
  phase.value = 'probing'
  probeText.value = '把脸放进取景框'
  if (camState.value !== 'READY') await startCamera()
  else startProbe()
}

function goAppeal() {
  // ★ 顺序不能反：clearJoin() 会把 checkin.sid 置为 null，先清再读就会跳到
  // /appeal/null → 详情接口拿 NaN 路径参数 → 学生只看到「记录加载失败」（线上复现过）。
  const sid = checkin.sid
  clearJoin()
  if (!sid) {
    showToast('签到信息已过期，请重新进入')
    router.replace('/checkin')
    return
  }
  router.replace(`/appeal/${sid}`)
}

async function restartAfterMultiFace() {
  resetUploader()
  attempts.value = 0
  await startCamera()
}

/* ---------------- 设备切换（签到中允许热切，§7.4.3） ---------------- */

async function pickDevice(index: number) {
  deviceSheet.value = false
  const deviceId = devices.value[index]?.deviceId
  if (!deviceId || deviceId === activeDeviceId.value) return
  phase.value = 'preparing'
  probeText.value = '正在切换摄像头…'
  abortUpload()
  const ok = await switchDevice(deviceId)
  if (!ok) {
    needTap.value = true
    probeText.value = camError.value || '切换失败，请重试'
    return
  }
  // 切换后旧挑战作废，需要重新领
  resetUploader()
  phase.value = 'probing'
  startProbe()
}

/* ---------------- 生命周期 ---------------- */

function onVisibility() {
  if (document.hidden) {
    pausedByHidden.value = true
    setPaused(true)
    stopProbe()
  } else {
    pausedByHidden.value = false
    setPaused(false)
    if (phase.value === 'probing') startProbe()
  }
}

onMounted(async () => {
  // 微信 WebView 没有 getUserMedia，开了也白开。软引导（App.vue）可能已被学生
  // 关掉，这里是硬拦：不进任何摄像头流程，只给复制链接。
  if (isWeChat()) return
  if (!restoreJoin()) {
    showToast('签到信息已过期，请重新进入')
    router.replace('/checkin')
    return
  }
  if (!isSecureContextOk()) {
    needTap.value = true
    probeText.value = '摄像头只能在 https 或 localhost 下使用，请用 https 打开本页'
    return
  }
  document.addEventListener('visibilitychange', onVisibility)
  await startCamera()
})

onBeforeUnmount(() => {
  stopProbe()
  window.clearInterval(cooldownTimer)
  document.removeEventListener('visibilitychange', onVisibility)
  abortUpload()
  closeCamera()
})
</script>

<template>
  <div class="capture">
    <!-- 上方信息条：班级 · 课程必须同显，学生要确认自己签的是这门课 -->
    <div class="head">
      <div class="pair">
        <span class="cls">{{ checkin.className || '本次签到' }}</span>
        <span class="dot">·</span>
        <span class="crs">{{ checkin.courseName }}</span>
      </div>
      <div class="head-actions">
        <!-- 常驻申诉入口：摄像头打不开/一直探测不过的学生，不能只有「关闭」这一条路 -->
        <span class="appeal-link" @click="goAppeal">申诉</span>
        <van-icon name="cross" size="20" color="#fff" @click="router.replace('/')" />
      </div>
    </div>

    <div class="stage">
      <video :ref="setVideo" class="video" playsinline muted autoplay />

      <FaceGuideOverlay
        v-if="phase !== 'result'"
        :black-hint="blackHint"
        :blocked="blocked"
        :message="errorText"
      >
        <template #action>
          <van-button size="small" type="primary" @click="restartAfterMultiFace">
            我已经一个人了，重新开始
          </van-button>
          <van-button size="small" plain style="margin-top: 8px" @click="router.replace('/')">
            回到首页
          </van-button>
        </template>
      </FaceGuideOverlay>

      <div v-if="needTap && phase !== 'result'" class="tap-layer">
        <van-button round type="primary" @click="startCamera">点击开启摄像头</van-button>
        <p class="tip">{{ probeText }}</p>
        <!-- 摄像头打不开是「签不了」的常见原因，这里必须直接给申诉，而不是让学生自己找入口 -->
        <van-button size="small" plain @click="goAppeal">摄像头用不了？提交申诉</van-button>
      </div>

      <div v-if="pausedByHidden" class="tap-layer">
        <van-icon name="pause-circle-o" size="34" color="#fff" />
        <p class="tip">已暂停采集，回到本页会自动继续</p>
      </div>

      <!-- 指令区：只在 CAPTURING 出现，此前一律不显示 -->
      <div v-if="upState === 'CAPTURING'" class="prompt-wrap">
        <ActionPrompt
          :instruction="instruction"
          :hint="hint"
          :progress="progress"
          :window-left="windowLeft"
          :detected="detected"
          :required="needCount"
        />
      </div>

      <div v-else-if="phase === 'probing' || phase === 'preparing'" class="probe-wrap">
        <van-loading color="#fff" size="18" />
        <span>{{ probeText }}</span>
      </div>
    </div>

    <div class="foot">
      <van-button size="small" plain icon="exchange" @click="deviceSheet = true">
        {{ deviceLabel }}
      </van-button>
      <span class="stat">
        已采 {{ sentFrames }} 帧<template v-if="droppedFrames"> · 丢 {{ droppedFrames }}</template>
      </span>
    </div>

    <!-- 结果层 -->
    <div v-if="showResult" class="result-layer">
      <ResultPanel
        :finish="finish"
        :gate="blockedGate"
        :error-text="errorText"
        :class-name="checkin.className"
        :course-name="checkin.courseName"
        :student-name="auth.profile?.name"
        :attempts="attempts"
        :can-appeal="true"
        :retry-left="finish?.retryLeft"
        :retry-blocked="retryBlocked"
        @retry="retry"
        @appeal="goAppeal"
      />
    </div>

    <van-action-sheet
      v-model:show="deviceSheet"
      :actions="devices.map((d) => ({ name: d.label, subname: d.deviceId === activeDeviceId ? '当前使用' : '' }))"
      cancel-text="取消"
      close-on-click-action
      title="选择摄像头"
      @select="(_a: unknown, i: number) => pickDevice(i)"
    />

    <!-- 微信内硬拦：摄像头必挂，不给继续选项 -->
    <WeChatGuard v-if="isWeChat()" hard />
  </div>
</template>

<style scoped>
.capture {
  position: fixed;
  inset: 0;
  background: #000;
  display: flex;
  flex-direction: column;
  color: #fff;
}
.head {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(10px + env(safe-area-inset-top)) 14px 8px;
}
.pair {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
}
.head-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: 0 0 auto;
}
.appeal-link {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.88);
  padding: 4px 2px;
}
.cls,
.crs {
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.crs {
  color: #6ecbff;
}
.dot {
  color: rgba(255, 255, 255, 0.5);
}
.stage {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}
.video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scaleX(-1); /* 自拍视角：镜像后学生抬手方向和画面一致 */
}
.prompt-wrap {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 14px;
}
.probe-wrap {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 16%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  color: #fff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.7);
}
.tap-layer {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
  text-align: center;
  z-index: 3;
}
.tap-layer .tip {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.8);
  line-height: 1.5;
  margin: 0;
}
.foot {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 14px calc(10px + env(safe-area-inset-bottom));
}
.stat {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.55);
  font-variant-numeric: tabular-nums;
}
.result-layer {
  position: absolute;
  inset: 0;
  background: #f7f8fa;
  color: #323233;
  overflow-y: auto;
  z-index: 5;
}
</style>
