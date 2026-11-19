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
    // 契约写的是「最多一条，没有则 data=null」（java-api.yaml sessions/active），
    // 不是数组。当成数组直接 .map() 会在没有签到任务时白屏。
    const current = await api.get<ActiveSession | null>('/api/v1/mobile/sessions/active')
    sessions.value = current ? [current] : []
    await refreshFaceStatus()
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    loading.value = false
  }
}

function go(s: ActiveSession) {
  if (s.myStatus === 'PRESENT' || s.myStatus === 'LATE') {
    showToast('这场你已经签过了')
    return
  }
  // 首页进入也需要 qrToken，所以只能引导去扫码；这里给出明确指引而不是死链
  showToast('请扫老师大屏上的二维码进入')
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
            <van-tag
              :type="s.myStatus === 'PRESENT' || s.myStatus === 'LATE' ? 'success' : 'primary'"
              plain
            >
              {{
                s.myStatus === 'PRESENT'
                  ? '已签到'
                  : s.myStatus === 'LATE'
                    ? '迟到'
                    : s.myStatus === 'IN_PROGRESS'
                      ? '进行中'
                      : '待签到'
              }}
            </van-tag>
          </div>
        </template>
      </van-cell>
    </van-cell-group>

    <div class="bottom">
      <van-button icon="scan" type="primary" round block @click="router.push('/checkin')">
        扫二维码签到
      </van-button>
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
.links {
  margin-top: 10px;
  display: flex;
  justify-content: center;
  gap: 10px;
}
</style>
