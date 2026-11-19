<script setup lang="ts">
/**
 * 人脸采集（§7.3 / §5.4）。
 *
 * 三条规则：
 * 1. 凑够 5~10 张通过质检的样本才能提交；
 * 2. 不达标的样本不报错，而是逐条告诉学生该怎么做（服务端返回 reasons + advice）；
 * 3. 提交前必须有「单独同意」—— 这是合规硬要求（§13.4），不是可选勾选。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, enrollSample, messageOf } from '@/shared/api/client'
import { auth, loadMe } from '@/shared/store/auth'
import { useCamera } from '@/features/checkin/composables/useCamera'
import type { EnrollReason, FaceStatusDetail } from '@/shared/api/types'

const router = useRouter()
const cam = useCamera()
const { video: videoEl, state: camState, error: camError, refreshDevices, open: openCamera, close: closeCamera } = cam

const samples = ref<string[]>([])
const consent = ref(false)
const busy = ref(false)
const advice = ref('')
const quality = ref<{ laplacian: number; brightness: number; faceWidth: number } | null>(null)
const submitting = ref(false)
const status = computed(() => auth.faceStatus?.status ?? 'NONE')

const MIN_SAMPLES = 5
const MAX_SAMPLES = 10
const CONSENT_VERSION = 'v1'

const reasonLabel: Record<EnrollReason, string> = {
  BLURRY: '画面糊了，手别抖',
  TOO_DARK: '太暗了，往亮处站一点',
  TOO_BRIGHT: '太亮了，别背对着强光',
  FACE_TOO_SMALL: '离远了一点，靠近些',
  YAW_TOO_LARGE: '别侧头，正对镜头',
  PITCH_TOO_LARGE: '别仰头或低头，平视镜头',
  MULTI_FACE: '画面里只能有你一个人',
  NO_FACE: '没看到你的脸，把脸放进取景框',
  DUPLICATE: '这张和之前太像了，稍微换个角度'
}

function setVideo(el: unknown) {
  videoEl.value = (el as HTMLVideoElement | null) ?? null
}

async function startCamera() {
  await refreshDevices()
  const ok = await openCamera()
  if (!ok) advice.value = camError.value || '摄像头打开失败'
}

async function grab() {
  if (busy.value || samples.value.length >= MAX_SAMPLES) return
  const frame = cam.captureSmall()
  if (!frame) {
    advice.value = '画面还没出来，稍等一下'
    return
  }
  busy.value = true
  advice.value = ''
  try {
    const blob = await cam.toBlob(frame)
    if (!blob) throw new Error('截帧失败')
    const data = await enrollSample(blob)
    quality.value = data.quality
      ? { laplacian: data.quality.laplacian, brightness: data.quality.brightness, faceWidth: data.quality.faceWidth }
      : null
    if (data.passed && data.sampleKey) {
      samples.value = [...samples.value, data.sampleKey]
      advice.value = `第 ${samples.value.length} 张合格`
    } else {
      const reasons = (data.reasons ?? []).map((r) => reasonLabel[r] || r).join('；')
      advice.value = data.advice || reasons || '这张没通过，换个角度再来一次'
    }
  } catch (err) {
    advice.value = messageOf(err)
  } finally {
    busy.value = false
  }
}

async function submit() {
  if (!consent.value) {
    showToast('请先勾选同意《人脸信息采集与使用告知》')
    return
  }
  if (samples.value.length < MIN_SAMPLES) {
    showToast(`还需要 ${MIN_SAMPLES - samples.value.length} 张合格样本`)
    return
  }
  submitting.value = true
  try {
    const data = await api.post<FaceStatusDetail>('/api/v1/mobile/face/enroll', {
      sampleKeys: samples.value,
      consentVersion: CONSENT_VERSION
    })
    auth.faceStatus = data
    closeCamera()
    showToast('已提交，等老师审核')
    await loadMe()
    router.replace('/')
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-4002') {
      showToast('画面里有多个人，请单独拍摄')
    } else if (err instanceof ApiError && err.code === 'FA-1003') {
      // 只会在登录态不是学生时出现（登录页/路由守卫已拦，这里是最后防线）
      showToast('当前账号没有采集权限，请退出后用学号重新登录')
    } else {
      showToast(messageOf(err))
    }
  } finally {
    submitting.value = false
  }
}

function removeLast() {
  samples.value = samples.value.slice(0, -1)
}

onMounted(startCamera)
onBeforeUnmount(closeCamera)
</script>

<template>
  <div class="page">
    <van-nav-bar title="人脸采集" left-arrow @click-left="router.back()" />

    <van-notice-bar
      v-if="status === 'PENDING_REVIEW'"
      wrapable
      :scrollable="false"
      type="warning"
      text="你已提交过样本，正在等老师审核。重新提交会覆盖上一组样本。"
      style="margin-bottom: 10px"
    />
    <van-notice-bar
      v-else-if="status === 'REJECTED'"
      wrapable
      :scrollable="false"
      type="danger"
      :text="`上次采集被驳回：${auth.faceStatus?.rejectReason || '照片不符合要求'}。请重新采集。`"
      style="margin-bottom: 10px"
    />

    <div class="stage">
      <video :ref="setVideo" class="video" playsinline muted autoplay />
      <div class="frame" />
      <div v-if="camState !== 'READY'" class="mask">
        <van-loading color="#fff" size="20" />
        <span style="margin-left: 8px">正在打开摄像头…</span>
      </div>
    </div>

    <div class="counter">
      <span>已采集 <b>{{ samples.length }}</b> / {{ MAX_SAMPLES }} 张</span>
      <span class="muted">至少 {{ MIN_SAMPLES }} 张才能提交</span>
    </div>

    <div v-if="advice" class="advice">{{ advice }}</div>
    <div v-if="quality" class="muted" style="margin: 6px 2px 0">
      清晰度 {{ quality.laplacian.toFixed(0) }} · 亮度 {{ quality.brightness.toFixed(0) }} · 脸宽
      {{ quality.faceWidth }}px
    </div>

    <div class="actions">
      <van-button
        round
        block
        type="primary"
        :loading="busy"
        :disabled="samples.length >= MAX_SAMPLES"
        @click="grab"
      >
        {{ samples.length >= MAX_SAMPLES ? '已够 10 张' : '拍一张' }}
      </van-button>
      <van-button v-if="samples.length" size="small" plain style="margin-top: 10px" @click="removeLast">
        删掉最后一张
      </van-button>
    </div>

    <div class="consent">
      <van-checkbox v-model="consent" icon-size="16px" shape="square">
        我已阅读并同意《人脸信息采集与使用告知》（{{ CONSENT_VERSION }}）
      </van-checkbox>
      <p class="muted" style="margin: 6px 0 0">
        人脸样本仅用于本人签到时的身份核验，加密存储；可联系老师申请删除。
      </p>
    </div>

    <div style="margin: 16px 0 24px">
      <van-button
        round
        block
        type="success"
        :disabled="samples.length < MIN_SAMPLES || !consent"
        :loading="submitting"
        @click="submit"
      >
        提交审核（{{ samples.length }}/{{ MIN_SAMPLES }}）
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.stage {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  background: #000;
  border-radius: 12px;
  overflow: hidden;
  margin-bottom: 10px;
}
.video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transform: scaleX(-1);
}
.frame {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 44%;
  aspect-ratio: 3 / 4;
  transform: translate(-50%, -50%);
  border: 2px dashed rgba(255, 255, 255, 0.75);
  border-radius: 50% / 42%;
}
.mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
}
.counter {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 13px;
  margin-bottom: 6px;
}
.advice {
  background: #fff7e6;
  color: #d46b08;
  border-radius: 8px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.5;
}
.actions {
  margin: 14px 0;
}
.consent {
  background: #fff;
  border-radius: 10px;
  padding: 12px;
  font-size: 13px;
  line-height: 1.5;
}
</style>
