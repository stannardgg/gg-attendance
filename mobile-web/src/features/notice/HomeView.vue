<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, messageOf } from '@/shared/api/client'
import { NoticeSocket } from '@/shared/ws/notice'
import { auth, refreshFaceStatus } from '@/shared/store/auth'
import { mmss } from '@/shared/utils'
import type { ActiveSession } from '@/shared/api/types'

const router = useRouter()
const loading = ref(false)
const sessions = ref<ActiveSession[]>([])
const faceOk = computed(() => auth.faceStatus?.status === 'ACTIVE')
const faceLabel = computed(() => {
  switch (auth.faceStatus?.status) {
    case 'ACTIVE':
      return '人脸已通过审核'
    case 'PENDING_REVIEW':
      return '人脸待老师审核'
    case 'REJECTED':
      return '人脸被驳回，需要重新采集'
    default:
      return '还没采集人脸'
  }
})

let socket: NoticeSocket | null = null
let ticker: number | undefined

async function load() {
  loading.value = true
  try {
    // 契约 sessions/active 返回列表（可为空数组），不是单条也不是 null
    sessions.value = await api.get<ActiveSession[]>('/api/v1/mobile/sessions/active')
    await refreshFaceStatus()
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    loading.value = false
  }
}

function go(s: ActiveSession) {
  // 已提交待处理申诉 → 本场不可再参与（后端 join 亦以 FA-5001 拒绝）
  if (s.appealStatus === 'PENDING') {
    showToast('你已提交本次申诉')
    return
  }
  if (s.myStatus === 'PRESENT' || s.myStatus === 'LATE') {
    showToast('这场你已经签过了')
    return
  }
  // 识别失败已达上限（CR-023）：签到这条路已经锁死（join 会以 FA-3005 拒绝），
  // 首页不能还把学生往采集页送。此时该场次唯一有意义的动作是申诉，
  // 所以点击直接带去申诉页——不是「禁入」，是换一条唯一的正路。
  if ((s.retryLeft ?? 3) <= 0) {
    showToast({ message: '识别失败次数已达上限，请提交申诉让老师核对', duration: 2200 })
    router.push(`/appeal/${s.sessionId}`)
    return
  }
  // 取消二维码后不再要求扫码：直接带 sid 进入签到入口，由入口页换票据
  router.push({ path: '/checkin', query: { sid: String(s.sessionId) } })
}

/** 申诉中优先于签到状态，失败达上限次之：标签不能与真实可做的事矛盾 */
function tagText(s: ActiveSession) {
  if (s.appealStatus === 'PENDING') return '申诉中'
  if (s.myStatus === 'PRESENT') return '已签到'
  if (s.myStatus === 'LATE') return '迟到'
  if ((s.retryLeft ?? 3) <= 0) return '识别失败'
  if (s.myStatus === 'IN_PROGRESS') return '进行中'
  return '待签到'
}

function tagType(s: ActiveSession): 'primary' | 'success' | 'warning' | 'danger' {
  if (s.appealStatus === 'PENDING') return 'warning'
  if (s.myStatus === 'PRESENT' || s.myStatus === 'LATE') return 'success'
  if ((s.retryLeft ?? 3) <= 0) return 'danger'
  return 'primary'
}

onMounted(() => {
  load()
  socket = new NoticeSocket((e) => {
    if (e.type === 'EVT_SESSION_STARTED') {
      showToast('老师发起了签到')
      load()
    } else {
      load()
    }
  })
  socket.connect()
  // 剩余时间本地倒计时，别每秒打一次接口
  ticker = window.setInterval(() => {
    sessions.value = sessions.value.map((s) => ({ ...s, remainingSec: Math.max(0, s.remainingSec - 1) }))
  }, 1000)
})

onBeforeUnmount(() => {
  socket?.close()
  window.clearInterval(ticker)
})
</script>

<template>
  <div class="page">
    <van-cell-group inset style="margin-bottom: 12px">
      <van-cell :title="auth.profile?.name || '同学'" :label="auth.profile?.className || ''">
        <template #value>
          <van-tag :type="faceOk ? 'success' : 'warning'" plain>{{ faceLabel }}</van-tag>
        </template>
      </van-cell>
    </van-cell-group>

    <van-notice-bar
      v-if="!faceOk"
      wrapable
      :scrollable="false"
      type="warning"
      :text="`${faceLabel}。未通过审核不能刷脸签到，点这里去采集。`"
      style="margin-bottom: 12px"
      @click="router.push('/face/enroll')"
    />

    <div class="section-title">进行中的签到</div>
    <van-empty v-if="!sessions.length && !loading" description="现在没有进行中的签到" />

    <van-cell-group v-for="s in sessions" :key="s.sessionId" inset class="card-gap">
      <van-cell :title="s.className" :label="s.courseName" is-link @click="go(s)">
        <template #value>
          <div class="right">
            <span class="clock">{{ mmss(s.remainingSec) }}</span>
            <van-tag :type="tagType(s)" plain>
              {{ tagText(s) }}
            </van-tag>
          </div>
        </template>
      </van-cell>
    </van-cell-group>

    <div class="bottom">
      <p class="hint">老师发起签到后，这场课会自动出现在上方，点进去刷脸即可。</p>
      <div class="links">
        <van-button size="small" plain @click="router.push('/me')">我的</van-button>
        <van-button size="small" plain @click="router.push('/face/enroll')">人脸采集</van-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.section-title {
  font-size: 13px;
  color: #969799;
  margin: 4px 4px 8px;
}
.right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.clock {
  font-variant-numeric: tabular-nums;
  color: #1989fa;
  font-size: 14px;
}
.bottom {
  margin-top: 20px;
}
.hint {
  margin: 0 16px;
  font-size: 12px;
  line-height: 1.6;
  color: #969799;
  text-align: center;
}
.links {
  margin-top: 10px;
  display: flex;
  justify-content: center;
  gap: 10px;
}
</style>
