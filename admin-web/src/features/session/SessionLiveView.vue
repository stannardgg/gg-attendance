<script setup lang="ts">
/**
 * 签到实时大屏。
 *
 * 两个硬约束（§14.3 WP-10 验收）：
 * 1. 1920×1080 与 1366×768 下都不出现滚动条 —— 所以整页 overflow:hidden，
 *    字号用 clamp() 跟视口走，名单区放不下就用「还有 N 人」收口而不是滚动。
 * 2. 班级名与课程名必须并列常驻 —— 教师最容易出的错就是选反了班级和课程。
 *
 * 取消二维码后左栏不再是码，而是「学生端怎么进来」的三步指引。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, getToken, messageOf } from '@/shared/api/client'
import type { AttendanceRow, SessionRow } from '@/shared/api/types'

const route = useRoute()
const router = useRouter()
const sessionId = Number(route.params.id)

const session = ref<SessionRow | null>(null)
const roster = ref<AttendanceRow[]>([])
const now = ref(Date.now())
const connected = ref(false)
const lastHit = ref<{ name: string; status: string; at: number } | null>(null)
const loading = ref(true)
/** 学生端入口：与 deploy/nginx 的 /m/ 前缀一致 */
const mobileUrl = `${location.origin}/m/`

let ws: WebSocket | null = null
let rosterTimer: number | undefined
let clock: number | undefined
let retry = 0
let alive = true

/* ---------------- 数据 ---------------- */

const present = computed(() => roster.value.filter((r) => r.status === 'PRESENT').length)
const late = computed(() => roster.value.filter((r) => r.status === 'LATE').length)
const absent = computed(() => roster.value.filter((r) => r.status === 'ABSENT').length)
const arrived = computed(() => present.value + late.value)
const expected = computed(() => roster.value.length)
const rate = computed(() =>
  expected.value ? `${(((present.value + late.value) / expected.value) * 100).toFixed(0)}%` : '—'
)

/** 已到的人排在前面，未到的按学号 —— 大屏只需回答「谁还没来」。 */
const sortedRoster = computed(() =>
  [...roster.value].sort((a, b) => {
    const rank = (s: string) => (s === 'PRESENT' ? 0 : s === 'LATE' ? 1 : 2)
    const d = rank(a.status) - rank(b.status)
    return d !== 0 ? d : a.studentNo.localeCompare(b.studentNo)
  })
)
const visibleNames = computed(() => sortedRoster.value.filter((r) => r.status !== 'ABSENT'))
const hiddenCount = computed(() => sortedRoster.value.length - visibleNames.value.length)

async function loadRoster() {
  try {
    const data = await api.get<{ items: AttendanceRow[]; total: number }>('/api/v1/admin/attendance', {
      sessionId,
      page: 1,
      size: 100
    })
    let items = data.items ?? []
    // 一个班超过 100 人时补拉，契约上限就是 100
    if ((data.total ?? 0) > items.length) {
      const pages = Math.ceil(data.total / 100)
      for (let p = 2; p <= pages; p++) {
        const more = await api.get<{ items: AttendanceRow[] }>('/api/v1/admin/attendance', {
          sessionId,
          page: p,
          size: 100
        })
        items = items.concat(more.items ?? [])
      }
    }
    roster.value = items
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

/**
 * 契约没有「按 id 读单个会话」的接口（已记 CR-007），
 * 所以靠列表倒序翻几页把本场找回来；翻不到就退化成只有考勤快照可看。
 */
async function loadSession() {
  try {
    for (let p = 1; p <= 3; p++) {
      const data = await api.get<{ items: SessionRow[]; total: number }>('/api/v1/admin/sessions', {
        page: p,
        size: 100
      })
      const hit = (data.items ?? []).find((s) => s.id === sessionId)
      if (hit) {
        session.value = hit
        return
      }
      if ((data.total ?? 0) <= p * 100) break
    }
  } catch {
    // 拿不到会话头不影响大屏主要功能
  }
}

/* ---------------- WebSocket ---------------- */

function connect() {
  if (!getToken()) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const url = `${proto}://${location.host}/api/v1/admin/sessions/${sessionId}/live?token=${encodeURIComponent(getToken() as string)}`
  try {
    ws = new WebSocket(url)
  } catch {
    return
  }

  ws.onopen = () => {
    connected.value = true
    retry = 0
  }
  ws.onmessage = (evt) => {
    let msg: {
      type?: string
      data?: { studentName?: string; status?: string; reason?: string }
    }
    try {
      msg = JSON.parse(evt.data)
    } catch {
      return
    }
    const type = msg.type ?? (msg as { event?: string }).event
    if (type === 'EVT_CHECKIN_SUCCESS') {
      const name = msg.data?.studentName ?? '有学生'
      const status = msg.data?.status ?? 'PRESENT'
      lastHit.value = { name, status, at: Date.now() }
      // 就地更新，先让大屏「跳」起来；再拉一次做对账，避免漏推送导致数字停住
      const row = roster.value.find((r) => r.studentName === name && r.status !== status)
      if (row) row.status = status as AttendanceRow['status']
      window.clearTimeout(rosterTimer)
      rosterTimer = window.setTimeout(loadRoster, 1200)
    } else if (type === 'EVT_APPEAL_CREATED') {
      // 16.3 场景 9：申诉要在 1 秒内被教师看到，不能等下次刷新列表
      ElMessage.warning(`${msg.data?.studentName ?? '有学生'} 提交了申诉，请到「申诉」页处理`)
    } else if (type === 'EVT_SESSION_CLOSED') {
      ElMessage.info('本次签到已被结束')
      loadRoster()
      loadSession()
    }
  }
  ws.onclose = () => {
    connected.value = false
    if (!alive) return
    // 指数退避，最多 5 次 —— 大屏通常挂在教室，网络抖一下就永久断开会很难看
    if (retry < 5) {
      retry += 1
      window.setTimeout(() => alive && connect(), Math.min(1000 * 2 ** retry, 15000))
    }
  }
  ws.onerror = () => ws?.close()
}

/* ---------------- 操作 ---------------- */

async function endSession() {
  try {
    await ElMessageBox.confirm(
      `结束后，未签到的 ${expected.value - arrived.value} 人会补记为缺勤，且不能再签到。确定结束吗？`,
      '结束签到',
      { type: 'warning', confirmButtonText: '结束签到' }
    )
  } catch {
    return
  }
  try {
    await api.post(`/api/v1/admin/sessions/${sessionId}/close`)
    ElMessage.success('已结束')
    await loadSession()
    await loadRoster()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else document.documentElement.requestFullscreen().catch(() => ElMessage.warning('浏览器不允许全屏'))
}

/* ---------------- 生命周期 ---------------- */

onMounted(async () => {
  loading.value = true
  await Promise.all([loadSession(), loadRoster()])
  loading.value = false
  clock = window.setInterval(() => (now.value = Date.now()), 1000)
  connect()
})

onBeforeUnmount(() => {
  alive = false
  window.clearTimeout(rosterTimer)
  window.clearInterval(clock)
  ws?.close()
})

const statusLabel = computed(() => {
  const s = session.value?.status
  return s === 'ACTIVE' ? '进行中' : s === 'CLOSED' ? '已结束' : s ? '已取消' : ''
})

/**
 * 剩余时间由本地时钟算，不依赖后端轮询。
 * 大屏必须让最后一排学生也看得清还剩多久 —— 这是 §8.2 对「大字号剩余时间」的要求。
 */
const remainSec = computed(() => {
  if (!session.value) return 0
  return Math.max(0, Math.round((new Date(session.value.endAt).getTime() - now.value) / 1000))
})
const remainText = computed(() => {
  const s = remainSec.value
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
})
</script>

<template>
  <div class="screen" v-loading="loading">
    <!-- 头部：班级 × 课程 并列常驻，不允许只显示其一 -->
    <header class="head">
      <div class="titles">
        <div class="pair">
          <span class="cls">{{ session?.className || `班级 #${sessionId}` }}</span>
          <span class="x">×</span>
          <span class="crs">{{ session?.courseName || '课程' }}</span>
        </div>
        <div class="sub">
          {{ session?.title || '签到中' }}
          <span v-if="session"> · {{ session.startAt.replace('T', ' ').slice(0, 16) }} 起</span>
          <el-tag v-if="statusLabel" size="small" :type="session?.status === 'ACTIVE' ? 'success' : 'info'">
            {{ statusLabel }}
          </el-tag>
          <span class="dot" :class="{ on: connected }">{{ connected ? '实时连接' : '重连中…' }}</span>
        </div>
      </div>
      <div class="ops">
        <el-button size="small" @click="toggleFullscreen">全屏</el-button>
        <el-button v-if="session?.status === 'ACTIVE'" size="small" type="warning" @click="endSession">
          结束签到
        </el-button>
        <el-button size="small" @click="router.push('/sessions')">返回</el-button>
      </div>
    </header>

    <main class="body">
      <!-- 左：学生端入口指引（不再投屏二维码） -->
      <section class="entry-panel">
        <div class="entry-title">学生端直接签到</div>
        <div class="remain">
          <span class="num" :class="{ over: remainSec === 0 }">{{ remainText }}</span>
          <span class="unit">{{ remainSec === 0 ? '已结束' : '剩余' }}</span>
        </div>
        <ol class="steps">
          <li>用手机打开学生端并登录</li>
          <li>在「进行中的签到」里点本场</li>
          <li>按提示正对镜头刷脸</li>
        </ol>
        <div class="entry-url">{{ mobileUrl }}</div>
        <div class="entry-hint">无需扫码 · 人脸活体与动作校验</div>
      </section>

      <!-- 右：实时人数与姓名墙 -->
      <section class="live-panel">
        <div class="stats">
          <div class="stat">
            <div class="k">应到</div>
            <div class="v">{{ expected }}</div>
          </div>
          <div class="stat ok">
            <div class="k">已到</div>
            <div class="v">{{ arrived }}</div>
          </div>
          <div class="stat warn">
            <div class="k">迟到</div>
            <div class="v">{{ late }}</div>
          </div>
          <div class="stat">
            <div class="k">未到</div>
            <div class="v">{{ expected - arrived }}</div>
          </div>
          <div class="stat">
            <div class="k">签到率</div>
            <div class="v">{{ rate }}</div>
          </div>
        </div>

        <transition name="pop">
          <div v-if="lastHit" :key="lastHit.at" class="hit">
            <span class="plus">+1</span>
            <span class="who">{{ lastHit.name }}</span>
            <span class="kind" :class="lastHit.status === 'LATE' ? 'late' : 'present'">
              {{ lastHit.status === 'LATE' ? '迟到' : '签到成功' }}
            </span>
          </div>
        </transition>

        <div class="wall">
          <div
            v-for="r in visibleNames"
            :key="r.studentId"
            class="chip"
            :class="{ late: r.status === 'LATE', leave: r.status === 'LEAVE' || r.status === 'EXEMPT' }"
          >
            {{ r.studentName }}
          </div>
          <div v-if="!visibleNames.length" class="empty">等待第一位同学签到…</div>
          <div v-if="hiddenCount > 0" class="chip more">还有 {{ hiddenCount }} 人未到</div>
        </div>
      </section>
    </main>
  </div>
</template>

<style scoped>
.screen {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #0f172a;
  color: #e5e7eb;
  overflow: hidden;
}

/* ---------- 头部 ---------- */
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 1.6vh 2vw;
  flex: 0 0 auto;
}
.pair {
  display: flex;
  align-items: baseline;
  gap: 1vw;
  flex-wrap: wrap;
}
.cls {
  font-size: clamp(22px, 2.6vw, 46px);
  font-weight: 700;
  color: #f8fafc;
  letter-spacing: 1px;
}
.x {
  font-size: clamp(16px, 1.4vw, 26px);
  color: #64748b;
}
.crs {
  font-size: clamp(22px, 2.6vw, 46px);
  font-weight: 700;
  color: #38bdf8;
  letter-spacing: 1px;
}
.sub {
  margin-top: 0.8vh;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: clamp(12px, 0.95vw, 16px);
  color: #94a3b8;
}
.dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #f59e0b;
}
.dot::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}
.dot.on {
  color: #22c55e;
}
.ops {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

/* ---------- 主体 ---------- */
.body {
  flex: 1 1 auto;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(300px, 36%) 1fr;
  gap: 2vw;
  padding: 0 2vw 2vh;
}

.entry-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1.6vh;
  background: #111c33;
  border-radius: 16px;
  padding: 2vh 2vw;
  min-height: 0;
  text-align: center;
}
.entry-title {
  font-size: clamp(20px, 2vw, 36px);
  font-weight: 700;
  color: #f8fafc;
  letter-spacing: 1px;
}
.steps {
  margin: 0;
  padding-left: 1.2em;
  text-align: left;
  color: #cbd5e1;
  font-size: clamp(13px, 1.05vw, 20px);
  line-height: 2;
}
.remain {
  display: flex;
  align-items: baseline;
  gap: 0.6vw;
  margin: 0.4vh 0 0.2vh;
}
.remain .num {
  font-size: clamp(38px, 4.6vw, 88px);
  font-weight: 700;
  color: #38bdf8;
  font-variant-numeric: tabular-nums;
  letter-spacing: 2px;
  line-height: 1;
}
.remain .num.over {
  color: #64748b;
}
.remain .unit {
  font-size: clamp(12px, 1vw, 18px);
  color: #64748b;
}
.entry-url {
  margin-top: 0.6vh;
  padding: 0.9vh 1vw;
  border-radius: 8px;
  background: #16233d;
  border: 1px solid #22304d;
  color: #38bdf8;
  font-size: clamp(12px, 1vw, 18px);
  word-break: break-all;
}
.entry-hint {
  color: #64748b;
  font-size: clamp(11px, 0.9vw, 15px);
}

.live-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 1.4vh;
}
.stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 1vw;
  flex: 0 0 auto;
}
.stat {
  background: #111c33;
  border-radius: 12px;
  padding: 1.2vh 0.8vw;
  text-align: center;
}
.stat .k {
  font-size: clamp(11px, 0.85vw, 15px);
  color: #94a3b8;
}
.stat .v {
  margin-top: 0.3vh;
  font-size: clamp(24px, 2.8vw, 52px);
  font-weight: 700;
  line-height: 1.1;
  font-variant-numeric: tabular-nums;
  color: #f8fafc;
}
.stat.ok .v {
  color: #22c55e;
}
.stat.warn .v {
  color: #f59e0b;
}

.hit {
  display: flex;
  align-items: center;
  gap: 12px;
  background: linear-gradient(90deg, rgba(34, 197, 94, 0.22), rgba(34, 197, 94, 0));
  border-left: 4px solid #22c55e;
  border-radius: 8px;
  padding: 0.8vh 1vw;
  flex: 0 0 auto;
}
.hit .plus {
  font-size: clamp(18px, 1.6vw, 30px);
  font-weight: 800;
  color: #22c55e;
}
.hit .who {
  font-size: clamp(18px, 1.7vw, 32px);
  font-weight: 700;
  color: #f8fafc;
}
.hit .kind {
  font-size: clamp(11px, 0.9vw, 15px);
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(34, 197, 94, 0.18);
  color: #22c55e;
}
.hit .kind.late {
  background: rgba(245, 158, 11, 0.18);
  color: #f59e0b;
}

.wall {
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 0.9vh 0.7vw;
}
.chip {
  font-size: clamp(13px, 1.25vw, 24px);
  font-weight: 600;
  padding: 0.5vh 0.8vw;
  border-radius: 8px;
  background: #16233d;
  color: #e2e8f0;
  border: 1px solid #22304d;
  white-space: nowrap;
}
.chip.late {
  color: #f59e0b;
  border-color: rgba(245, 158, 11, 0.5);
}
.chip.leave {
  color: #38bdf8;
  border-color: rgba(56, 189, 248, 0.5);
}
.chip.more {
  color: #94a3b8;
  background: transparent;
  border-style: dashed;
}
.empty {
  color: #64748b;
  font-size: clamp(14px, 1.2vw, 22px);
  padding-top: 2vh;
}

.pop-enter-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.pop-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}

@media (max-width: 1024px) {
  .body {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
