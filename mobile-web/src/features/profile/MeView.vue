<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, messageOf } from '@/shared/api/client'
import { auth, logout, refreshFaceStatus } from '@/shared/store/auth'
import { fmtTime } from '@/shared/utils'
import type { MobileAttendance, StudentProfileRequest } from '@/shared/api/types'

const router = useRouter()
const items = ref<MobileAttendance[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const filter = ref('')

const statusLabel: Record<string, string> = {
  PRESENT: '已签到',
  LATE: '迟到',
  ABSENT: '缺勤',
  LEAVE: '请假',
  EXEMPT: '免签'
}
const statusColor: Record<string, string> = {
  PRESENT: '#07c160',
  LATE: '#ff976a',
  ABSENT: '#ee0a24',
  LEAVE: '#1989fa',
  EXEMPT: '#969799'
}

const faceLabel = computed(() => {
  switch (auth.faceStatus?.status) {
    case 'ACTIVE':
      return '已通过'
    case 'PENDING_REVIEW':
      return '待审核'
    case 'REJECTED':
      return '已驳回'
    default:
      return '未采集'
  }
})

async function load(reset = false) {
  if (reset) page.value = 1
  loading.value = true
  try {
    const data = await api.get<{ items: MobileAttendance[]; total: number }>(
      '/api/v1/mobile/attendance/history',
      { page: page.value, size: 20, status: filter.value }
    )
    items.value = reset ? data.items ?? [] : [...items.value, ...(data.items ?? [])]
    total.value = data.total ?? 0
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    loading.value = false
  }
}

function loadMore() {
  if (items.value.length >= total.value) return
  page.value += 1
  load()
}

async function onLogout() {
  await logout()
  router.replace('/login')
}

/* ---------------- 修改密码（CR-024）：提交申请 → 管理员审核 ---------------- */

const pwOpen = ref(false)
const pwSaving = ref(false)
const pwRequests = ref<StudentProfileRequest[]>([])
const pwForm = reactive({ current: '', next: '', confirm: '', reason: '' })

const pwStatusLabel: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回'
}

const pendingPw = computed(() => pwRequests.value.find((r) => r.status === 'PENDING'))

async function loadPwRequests() {
  try {
    const data = await api.get<{ items: StudentProfileRequest[]; total: number }>(
      '/api/v1/student/profile-requests',
      { page: 1, size: 10 }
    )
    pwRequests.value = data.items ?? []
  } catch {
    // 红点缺失比整页报错好；入口在「我的」页，失败不阻塞签到
    pwRequests.value = []
  }
}

function openPw() {
  pwForm.current = ''
  pwForm.next = ''
  pwForm.confirm = ''
  pwForm.reason = ''
  pwOpen.value = true
  loadPwRequests()
}

async function submitPw() {
  if (pendingPw.value) {
    showToast('已有一条待审核的申请，请等待处理或先撤回')
    return
  }
  if (!pwForm.current) {
    showToast('请输入当前密码')
    return
  }
  if (pwForm.next.length < 8 || !/[A-Za-z]/.test(pwForm.next) || !/\d/.test(pwForm.next)) {
    showToast('新密码至少 8 位，须同时包含字母与数字')
    return
  }
  if (pwForm.next !== pwForm.confirm) {
    showToast('两次输入的新密码不一致')
    return
  }
  pwSaving.value = true
  try {
    await api.post('/api/v1/student/profile-requests', {
      currentPassword: pwForm.current,
      newPassword: pwForm.next,
      reason: pwForm.reason.trim() || undefined
    })
    pwOpen.value = false
    showToast('已提交，等待管理员审核')
    await loadPwRequests()
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    pwSaving.value = false
  }
}

async function withdrawPw() {
  const row = pendingPw.value
  if (!row) return
  try {
    await api.post(`/api/v1/student/profile-requests/${row.id}/withdraw`, {})
    showToast('已撤回')
    await loadPwRequests()
  } catch (err) {
    showToast(messageOf(err))
  }
}

onMounted(() => {
  load(true)
  refreshFaceStatus()
  loadPwRequests()
})
</script>

<template>
  <div class="page">
    <van-cell-group inset style="margin-bottom: 12px">
      <van-cell :title="auth.profile?.name || '同学'" :label="auth.profile?.className || ''">
        <template #value>
          <van-tag plain :type="auth.faceStatus?.status === 'ACTIVE' ? 'success' : 'warning'">
            人脸 {{ faceLabel }}
          </van-tag>
        </template>
      </van-cell>
      <van-cell
        title="人脸采集"
        is-link
        :label="auth.faceStatus?.rejectReason || ''"
        @click="router.push('/face/enroll')"
      />
      <van-cell
        title="修改密码"
        is-link
        :label="pendingPw ? '有一条申请正在等待管理员审核' : ''"
        @click="openPw"
      >
        <template #value>
          <van-tag v-if="pendingPw" plain type="warning">审核中</van-tag>
        </template>
      </van-cell>
    </van-cell-group>

    <div class="head">
      <span class="title">签到历史（{{ total }}）</span>
      <van-dropdown-menu>
        <van-dropdown-item
          v-model="filter"
          :options="[
            { text: '全部', value: '' },
            { text: '已签到', value: 'PRESENT' },
            { text: '迟到', value: 'LATE' },
            { text: '缺勤', value: 'ABSENT' }
          ]"
          @change="load(true)"
        />
      </van-dropdown-menu>
    </div>

    <van-empty v-if="!items.length && !loading" description="还没有签到记录" />

    <van-cell-group v-for="(it, i) in items" :key="`${it.sessionId}-${i}`" inset class="card-gap">
      <van-cell :title="it.className" :label="it.courseName">
        <template #value>
          <span :style="{ color: it.status ? statusColor[it.status] : '#969799', fontSize: '13px' }">
            {{ it.status ? statusLabel[it.status] : '未签到' }}
          </span>
        </template>
      </van-cell>
      <van-cell>
        <template #title>
          <span class="muted">{{ fmtTime(it.checkinAt) }}</span>
        </template>
        <template #value>
          <van-button
            v-if="it.canAppeal && !it.appealId"
            size="mini"
            plain
            type="primary"
            @click="router.push(`/appeal/${it.sessionId}`)"
          >
            申诉
          </van-button>
          <van-tag v-else-if="it.appealId" size="medium" plain type="warning">
            {{ it.appealStatus === 'PENDING' ? '申诉中' : it.appealStatus === 'APPROVED' ? '申诉通过' : '已驳回' }}
          </van-tag>
        </template>
      </van-cell>
    </van-cell-group>

    <div v-if="items.length < total" style="margin: 12px 0">
      <van-button block plain :loading="loading" @click="loadMore">加载更多</van-button>
    </div>

    <div style="margin: 20px 0 30px">
      <van-button round block plain type="danger" @click="onLogout">退出登录</van-button>
    </div>

    <!-- 修改密码：提交申请 → 管理员审核 → 通过后旧密码与旧会话立即失效 -->
    <van-popup v-model:show="pwOpen" round position="bottom" style="padding: 20px 16px; max-height: 80%">
      <div class="pw-title">修改密码</div>
      <van-notice-bar
        v-if="pendingPw"
        wrapable
        :scrollable="false"
        type="warning"
        text="已提交申请，等待管理员审核。通过后旧密码立即失效，需要用新密码重新登录。"
        style="margin-bottom: 12px"
      />
      <van-cell-group inset>
        <van-field
          v-model="pwForm.current"
          type="password"
          label="当前密码"
          placeholder="用于确认本人操作"
          maxlength="64"
        />
        <van-field
          v-model="pwForm.next"
          type="password"
          label="新密码"
          placeholder="至少 8 位，含字母与数字"
          maxlength="64"
        />
        <van-field
          v-model="pwForm.confirm"
          type="password"
          label="确认新密码"
          maxlength="64"
        />
        <van-field
          v-model="pwForm.reason"
          type="textarea"
          rows="2"
          maxlength="500"
          show-word-limit
          label="说明"
          placeholder="选填，便于管理员核对"
        />
      </van-cell-group>
      <p class="muted" style="margin: 10px 6px; font-size: 12px; color: #969799">
        密码修改由管理员审核后生效，不会直接改密码。
      </p>
      <div v-if="pendingPw" style="margin: 8px 0">
        <van-button round block plain type="danger" @click="withdrawPw">撤回申请</van-button>
      </div>
      <div v-else style="margin: 8px 0">
        <van-button round block type="primary" :loading="pwSaving" @click="submitPw">提交申请</van-button>
      </div>

      <div v-if="pwRequests.length" class="pw-history">
        <div class="pw-history-title">申请记录</div>
        <div v-for="r in pwRequests" :key="r.id" class="pw-row">
          <span class="pw-time">{{ fmtTime(r.createdAt) }}</span>
          <span
            class="pw-status"
            :style="{ color: r.status === 'APPROVED' ? '#07c160' : r.status === 'REJECTED' ? '#ee0a24' : '#969799' }"
          >
            {{ pwStatusLabel[r.status] || r.status }}
          </span>
          <span v-if="r.reviewNote" class="pw-note">{{ r.reviewNote }}</span>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 4px 8px;
}
.title {
  font-size: 13px;
  color: #969799;
}
.pw-title {
  font-size: 16px;
  font-weight: 600;
  margin: 0 4px 14px;
}
.pw-history {
  margin-top: 14px;
  border-top: 1px solid #f0f0f0;
  padding-top: 10px;
}
.pw-history-title {
  font-size: 13px;
  color: #969799;
  margin: 0 4px 8px;
}
.pw-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 4px 4px;
  font-size: 13px;
}
.pw-time {
  color: #969799;
  flex-shrink: 0;
}
.pw-status {
  flex-shrink: 0;
}
.pw-note {
  color: #969799;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
