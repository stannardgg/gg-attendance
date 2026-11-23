<script setup lang="ts">
/**
 * 人脸采集（§7.3 / §5.4 / CR-028）。
 *
 * 四条规则：
 * 1. **单独同意前置**：进入本页先看《人脸信息采集与使用告知（v2）》全文，
 *    点「同意并开始采集」才允许打开摄像头（PIPL §29——勾选框不构成单独同意）；
 *    同意事件由服务端落 `face_consent_record`，本页不留本地开关；
 * 2. 凑够 5~10 张通过质检的样本才能提交；
 * 3. 不达标的样本不报错，而是逐条告诉学生该怎么做（服务端返回 reasons + advice）；
 * 4. 已有人脸数据的学生可随时在本页行使删除权（删向量 + 撤同意，历史签到不受影响）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, ApiError, enrollSample, messageOf } from '@/shared/api/client'
import { auth, loadMe, refreshFaceStatus } from '@/shared/store/auth'
import { useCamera } from '@/features/checkin/composables/useCamera'
import { isWeChat } from '@/shared/utils'
import WeChatGuard from '@/shared/components/WeChatGuard.vue'
import type { EnrollReason, FaceStatusDetail } from '@/shared/api/types'

const router = useRouter()
const cam = useCamera()
const { video: videoEl, state: camState, error: camError, refreshDevices, open: openCamera, close: closeCamera } = cam

const samples = ref<string[]>([])
const busy = ref(false)
const advice = ref('')
const quality = ref<{ laplacian: number; brightness: number; faceWidth: number } | null>(null)
const submitting = ref(false)
const showConsent = ref(false)
const consenting = ref(false)
const deleting = ref(false)
const status = computed(() => auth.faceStatus?.status ?? 'NONE')
const consentAtDay = computed(() => {
  const at = auth.faceStatus?.consentAt
  return at ? at.slice(0, 10) : ''
})

/** 与后端 FaceEnrollService.CONSENT_VERSION_CURRENT 必须一致；升版时同步改告知全文。 */
const CONSENT_VERSION = 'v2'

/** 同意弹窗正文：与 docs/compliance/face-consent-notice-v2.md 同源，改这里必须先改文档。 */
const CONSENT_PARAS = [
  '1. 谁在处理：本系统由学校（个人信息处理者）运营，你的人脸信息仅在学校内部使用，不向任何第三方提供。',
  '2. 用途：仅用于你在本系统的课程签到身份核验（活体比对与识别），不做其他任何用途，不用于模型训练或商业目的。',
  '3. 采集内容：采集时相机画面只在内存中做质量检测与特征提取，原始画面不保存；入库的是加密的 512 维特征向量与质量参数，不含照片。',
  '4. 保留期限：特征向量保存到你撤回同意、行使删除权，或因学籍异动被清除为止；采集过程中的临时缓存 15 分钟自动销毁；历史签到记录属考勤数据，按学校制度保留。',
  '5. 你的权利：可随时在本页「删除我的人脸数据」一键删除并撤回同意；撤回不影响你已有的签到记录。删除后如需人脸签到，须重新同意并采集。',
  '6. 安全措施：特征向量 AES-256 加密存储、备份加密、访问全程留审计；微信内置浏览器禁止采集。',
  '7. 不同意的后果：不采集人脸将无法使用人脸签到，可请老师用人工核对等替代方式。',
  '8. 联系我们：个人信息保护相关问题请联系学校负责人【联系方式】。'
]

const MIN_SAMPLES = 5
const MAX_SAMPLES = 10

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

async function acceptConsent() {
  if (consenting.value) return
  consenting.value = true
  try {
    auth.faceStatus = await api.post<FaceStatusDetail>('/api/v1/mobile/face/consent', {
      consentVersion: CONSENT_VERSION
    })
    showConsent.value = false
    await startCamera()
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    consenting.value = false
  }
}

function declineConsent() {
  // 不同意就离开：不启动摄像头、不采集、不落任何数据
  showToast('未取得单独同意，无法采集人脸')
  router.back()
}

async function deleteData() {
  try {
    await showConfirmDialog({
      title: '删除人脸数据',
      message:
        '将删除你的人脸向量与档案，并撤回采集同意。\n\n历史签到记录不受影响。\n删除后如需人脸签到，须重新同意并采集。\n\n该操作不可恢复。',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消'
    })
  } catch {
    return // 用户取消
  }
  deleting.value = true
  try {
    auth.faceStatus = await api.post<FaceStatusDetail>('/api/v1/mobile/face/data-deletion')
    showToast('已删除人脸数据并撤回同意')
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    deleting.value = false
  }
}

async function submit() {
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
    } else if (err instanceof ApiError && err.code === 'FA-4010') {
      // 同意缺失/版本不符（CR-028 服务端强校验）：引导回同意弹窗
      showToast(messageOf(err))
      showConsent.value = true
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

onMounted(async () => {
  // 微信内摄像头必挂：不启动摄像头，直接展示引导（与签到采集页同一道硬拦）
  if (isWeChat()) return
  try {
    await refreshFaceStatus()
  } catch {
    advice.value = '人脸状态加载失败，请退出本页重试'
    return
  }
  // 已同意当前版本 → 直接开摄像头；否则先弹单独同意，同意前不开摄像头（CR-028）
  if (auth.faceStatus?.consentVersion === CONSENT_VERSION) {
    void startCamera()
  } else {
    showConsent.value = true
  }
})
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
      <p style="margin: 0">
        人脸样本仅用于本人签到身份核验；原始画面不保存，入库特征向量加密存储（告知 v2）。
        <span v-if="consentAtDay">你于 {{ consentAtDay }} 同意本告知。</span>
      </p>
    </div>

    <div style="margin: 16px 0 12px">
      <van-button
        round
        block
        type="success"
        :disabled="samples.length < MIN_SAMPLES"
        :loading="submitting"
        @click="submit"
      >
        提交审核（{{ samples.length }}/{{ MIN_SAMPLES }}）
      </van-button>
    </div>

    <div v-if="status !== 'NONE'" style="margin: 0 0 24px">
      <van-button round block plain type="danger" :loading="deleting" @click="deleteData">
        删除我的人脸数据并撤回同意
      </van-button>
    </div>

    <!-- 单独同意弹窗（CR-028）：点遮罩不关闭，必须显式二选一 -->
    <van-popup
      v-model:show="showConsent"
      position="bottom"
      round
      :style="{ height: '74%' }"
      :close-on-click-overlay="false"
    >
      <div class="consent-pop">
        <h3 class="consent-title">人脸信息采集与使用告知（{{ CONSENT_VERSION }}）</h3>
        <div class="consent-body">
          <p v-for="(para, i) in CONSENT_PARAS" :key="i">{{ para }}</p>
        </div>
        <div class="consent-actions">
          <van-button plain block style="flex: 1" @click="declineConsent">不同意并退出</van-button>
          <van-button type="primary" block style="flex: 2" :loading="consenting" @click="acceptConsent">
            同意并开始采集
          </van-button>
        </div>
      </div>
    </van-popup>

    <!-- 微信内硬拦：摄像头必挂，不给继续选项 -->
    <WeChatGuard v-if="isWeChat()" hard />
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
.consent-pop {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 18px 16px 14px;
}
.consent-title {
  margin: 0 0 10px;
  font-size: 16px;
}
.consent-body {
  flex: 1;
  overflow-y: auto;
  font-size: 13px;
  line-height: 1.7;
  color: #555;
  padding-right: 4px;
}
.consent-body p {
  margin: 0 0 10px;
}
.consent-actions {
  display: flex;
  gap: 10px;
  padding-top: 12px;
}
</style>
