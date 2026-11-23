<script setup lang="ts">
/**
 * 签到入口。
 *
 * 取消二维码后这一步的职责变简单了：学生点哪一场就换哪一场的票据，
 * 换到票据后**直接进采集页**，不再要求额外点一次"开始"——点场次本身就是意图。
 * 采集页顶部常驻「班级 · 课程」，所以这里不需要再放一层确认页。
 * 没带 sid（直接访问 /checkin）时，把本班进行中的场次列出来供选择。
 * 换不到票据时按错误码给下一步 —— "签到失败"四个字对学生没有任何用。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, messageOf } from '@/shared/api/client'
import { refreshFaceStatus } from '@/shared/store/auth'
import { mmss } from '@/shared/utils'
import type { ActiveSession, JoinData } from '@/shared/api/types'
import { setJoin } from '../store'

const route = useRoute()
const router = useRouter()

const sid = ref(Number(route.query.sid))
const loading = ref(true)
const failCode = ref('')
const failText = ref('')
const sessions = ref<ActiveSession[]>([])

const hasSession = computed(() => Number.isFinite(sid.value) && sid.value > 0)

async function join(target = sid.value) {
  if (!Number.isFinite(target) || target <= 0) {
    loading.value = false
    await loadSessions()
    return
  }
  loading.value = true
  failCode.value = ''
  failText.value = ''
  try {
    // 无请求体：学生身份来自登录态，会话来自路径
    const data = await api.post<JoinData>(`/api/v1/mobile/sessions/${target}/join`)
    sid.value = target
    setJoin(target, data)
    // 人脸状态在入口处核实一次：审核被驳回的学生不该走到采集页才失败
    void refreshFaceStatus()
    // 直接进采集页：多一次"开始"点击没有任何信息增量
    router.replace(`/checkin/${target}`)
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
    sessions.value = await api.get<ActiveSession[]>('/api/v1/mobile/sessions/active')
  } catch {
    sessions.value = []
  }
}

function enter(s: ActiveSession) {
  // 已提交待处理申诉：本场不可再参与。后端 join 也会以 FA-5001 拒绝，
  // 前端先拦一道只是省掉一次往返，并把「为什么点不动」当场说清楚。
  if (s.appealStatus === 'PENDING') {
    showToast('你已提交本次申诉')
    return
  }
  if (s.myStatus === 'PRESENT' || s.myStatus === 'LATE') {
    showToast('这场你已经签过了')
    return
  }
  // 识别失败已达上限（CR-023）：join 会以 FA-3005 拒绝，别再送学生进采集页。
  // 与首页同策略：点这里直接去申诉——锁死之后唯一有意义的动作。
  // 文案按业主验收口径：「超过三次识别失败，联系管理员」。
  if ((s.retryLeft ?? 3) <= 0) {
    showToast({ message: '超过三次识别失败，联系管理员', duration: 2200 })
    router.push(`/appeal/${s.sessionId}`)
    return
  }
  void join(s.sessionId)
}

/** 申诉中优先于签到状态，失败达上限次之：标签不能与真实可做的事矛盾 */
function tagText(s: ActiveSession) {
  if (s.appealStatus === 'PENDING') return '申诉中'
  if (s.myStatus === 'PRESENT') return '已签到'
  if (s.myStatus === 'LATE') return '迟到'
  if ((s.retryLeft ?? 3) <= 0) return '识别失败'
  return '待签到'
}

function tagType(s: ActiveSession): 'primary' | 'success' | 'warning' | 'danger' {
  if (s.appealStatus === 'PENDING') return 'warning'
  if (s.myStatus === 'PRESENT' || s.myStatus === 'LATE') return 'success'
  if ((s.retryLeft ?? 3) <= 0) return 'danger'
  return 'primary'
}

const faceHint = computed(() => {
  if (failCode.value === 'FA-4006') return '你的人脸档案还没通过审核，先完成采集与审核再来签到。'
  return ''
})

onMounted(() => {
  void join()
})

const advice: Record<string, string> = {
  'FA-3001': '这场签到已经结束了，下一次课再签。',
  'FA-3003': '你已经签过了，不用重复签。',
  'FA-3004': '这场的应到名单里没有你，说明你不是这个班的学生。请联系任课老师核对名单。',
  'FA-3005': '本场签到的识别失败次数已达上限，不能再刷脸了。',
  'FA-4006': '人脸档案未通过审核，先采集并通过审核。',
  'FA-5001': '你已提交本次申诉，等老师处理即可，这场不用再签到。'
}

/** 失败次数达上限时，入口页直接给申诉按钮：这是该场次唯一有意义的动作 */
const canAppealHere = computed(() => failCode.value === 'FA-3005' && hasSession.value)
</script>

<template>
  <div class="page">
    <div v-if="loading" class="center" style="height: 60vh">
      <van-loading size="24">正在进入签到…</van-loading>
    </div>

    <!-- 换不到票据：按错误码给不同的下一步 -->
    <template v-else>
      <template v-if="hasSession">
        <van-empty image="error" :description="failText" />
        <p class="muted" style="text-align: center; margin: -12px 20px 18px">
          {{ advice[failCode] || '如果反复失败，请联系任课老师。' }}
        </p>

        <div v-if="faceHint" style="margin: 0 16px 14px">
          <van-button round block type="primary" @click="router.push('/face/enroll')">
            去采集人脸
          </van-button>
        </div>

        <div v-if="canAppealHere" style="margin: 0 16px 14px">
          <van-button round block type="primary" @click="router.push(`/appeal/${sid}`)">
            提交申诉，让老师核对
          </van-button>
        </div>
      </template>

      <div class="section-title">当前进行中的签到</div>
      <van-empty
        v-if="!sessions.length"
        description="现在没有进行中的签到，等老师发起后再来"
      />
      <van-cell-group v-for="s in sessions" :key="s.sessionId" inset>
        <van-cell
          :title="s.className"
          :label="`${s.courseName}${s.title ? ' · ' + s.title : ''}`"
          is-link
          @click="enter(s)"
        >
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

      <div style="margin: 22px 16px 0">
        <van-button round block plain type="primary" @click="router.replace('/')">回到首页</van-button>
      </div>
    </template>
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
</style>
