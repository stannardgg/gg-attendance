<script setup lang="ts">
/**
 * 扫码入口（§7.3）。
 *
 * 这一步只做三件事：换票据、把**班级与课程同时**摆给学生看、然后进采集页。
 * 换不到票据时每一类错误都要给出不同的下一步 —— "签到失败" 四个字对学生没有任何用。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, messageOf } from '@/shared/api/client'
import { refreshFaceStatus } from '@/shared/store/auth'
import { mmss } from '@/shared/utils'
import type { ActiveSession, JoinData, MobileAttendance } from '@/shared/api/types'
import { setJoin } from '../store'

const route = useRoute()
const router = useRouter()

const sid = Number(route.query.sid)
const tk = String(route.query.tk ?? '')
const loading = ref(true)
const joined = ref<JoinData | null>(null)
const failCode = ref('')
const failText = ref('')
const sessions = ref<ActiveSession[]>([])

const hasCode = computed(() => Number.isFinite(sid) && sid > 0 && tk.length >= 16)

async function join() {
  loading.value = true
  failCode.value = ''
  try {
    const data = await api.post<JoinData>(`/api/v1/mobile/sessions/${sid}/join`, { qrToken: tk })
    joined.value = data
    setJoin(sid, data)
    // 人脸状态在入口处核实一次：审核被驳回的学生不该走到采集页才失败
    await refreshFaceStatus()
  } catch (err) {
    if (err instanceof ApiError) {
      failCode.value = err.code
      failText.value = err.message || messageOf(err)
    } else {
      failCode.value = 'NETWORK'
      failText.value = messageOf(err)
    }
    await loadSessions()
  } finally {
    loading.value = false
  }
}

async function loadSessions() {
  try {
    // 同 HomeView：契约是「单条或 null」，不是数组
    const current = await api.get<ActiveSession | null>('/api/v1/mobile/sessions/active')
    sessions.value = current ? [current] : []
  } catch {
    sessions.value = []
  }
}

function start() {
  router.push(`/checkin/${sid}`)
}

/** 已签到过：直接跳到结果页的等价视图（用 attendance 接口取真实状态） */
async function checkDone() {
  try {
    const a = await api.get<MobileAttendance>(`/api/v1/mobile/attendance/${sid}`)
    if (a.status === 'PRESENT' || a.status === 'LATE') {
      showToast(`你已经签到（${a.status === 'LATE' ? '迟到' : '已签到'}）`)
    }
  } catch {
    /* 取不到就按普通失败处理 */
  }
}

const faceHint = computed(() => {
  if (failCode.value === 'FA-4006') return '你的人脸档案还没通过审核，先完成采集与审核再来签到。'
  return ''
})

onMounted(async () => {
  if (!hasCode.value) {
    loading.value = false
    failCode.value = 'NO_CODE'
    failText.value = '没有读到二维码内容，可以扫老师大屏上的二维码，或从下面选一场签到。'
    await loadSessions()
    return
  }
  await join()
})

const advice: Record<string, string> = {
  'FA-3001': '这场签到已经结束了，下一次课再签。',
  'FA-3002': '二维码每 15 秒换一次，你扫到的已经过期了。对着大屏上的新码再扫一次。',
  'FA-3003': '你已经签过了，不用重复签。',
  'FA-3004': '这场的应到名单里没有你，说明你不是这个班的学生。请确认扫的是本班的码。',
  'FA-3005': '这台手机上失败的次数太多了，稍等一会儿再试，或者提交申诉让老师核对。',
  'FA-4006': '人脸档案未通过审核，先采集并通过审核。'
}
</script>

<template>
  <div class="page">
    <div v-if="loading" class="center" style="height: 60vh">
      <van-loading size="24">正在核对二维码…</van-loading>
    </div>

    <!-- 换到票据：把班级与课程并列摆出来，别让学生签错课 -->
    <template v-else-if="joined">
      <div class="panel">
        <div class="label">本次签到</div>
        <div class="cls">{{ joined.className }}</div>
        <div class="crs">{{ joined.courseName }}</div>
        <div class="meta">
          <span v-if="joined.sessionTitle">{{ joined.sessionTitle }}</span>
          <span v-if="joined.remainingSec">剩余 {{ mmss(joined.remainingSec) }}</span>
        </div>
      </div>

      <van-notice-bar
        v-if="!joined.challengeRequired"
        wrapable
        :scrollable="false"
        type="primary"
        text="本次签到已简化，直接确认即可完成。"
        style="margin: 12px 0"
      />

      <div style="margin-top: 18px">
        <van-button round block type="primary" size="large" @click="start">
          开始签到
        </van-button>
      </div>
      <p class="muted" style="text-align: center; margin-top: 12px">
        开始后请正对镜头，按屏幕提示连续做两次张嘴动作。
      </p>
    </template>

    <!-- 换不到票据：按错误码给不同的下一步 -->
    <template v-else>
      <van-empty image="error" :description="failText" />
      <p class="muted" style="text-align: center; margin: -12px 20px 18px">
        {{ advice[failCode] || '如果反复失败，请联系任课老师。' }}
      </p>

      <div v-if="faceHint" style="margin: 0 16px 14px">
        <van-button round block type="primary" @click="router.push('/face/enroll')">
          去采集人脸
        </van-button>
      </div>

      <template v-if="sessions.length">
        <div class="section-title">当前进行中的签到</div>
        <van-cell-group v-for="s in sessions" :key="s.sessionId" inset>
          <van-cell :title="s.className" :label="s.courseName">
            <template #value>
              <span class="muted">{{ mmss(s.remainingSec) }}</span>
            </template>
          </van-cell>
          <div class="row">
            <van-button size="small" type="primary" plain @click="router.push('/')">
              回首页
            </van-button>
            <van-button size="small" plain @click="checkDone">我已签过</van-button>
          </div>
        </van-cell-group>
      </template>

      <div style="margin: 22px 16px 0">
        <van-button round block plain type="primary" @click="router.replace('/')">回到首页</van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.panel {
  margin-top: 24px;
  background: #fff;
  border-radius: 14px;
  padding: 22px 18px;
  text-align: center;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.04);
}
.label {
  font-size: 12px;
  color: #969799;
  letter-spacing: 2px;
}
.cls {
  margin-top: 10px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.3;
}
.crs {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 700;
  color: #1989fa;
  line-height: 1.3;
}
.meta {
  margin-top: 10px;
  display: flex;
  justify-content: center;
  gap: 12px;
  font-size: 12px;
  color: #969799;
}
.section-title {
  font-size: 13px;
  color: #969799;
  margin: 4px 4px 8px;
}
.row {
  display: flex;
  gap: 10px;
  padding: 0 16px 12px;
}
</style>
