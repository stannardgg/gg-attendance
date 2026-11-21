<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, messageOf } from '@/shared/api/client'
import { auth, logout, refreshFaceStatus } from '@/shared/store/auth'
import { fmtTime } from '@/shared/utils'
import type { MobileAttendance } from '@/shared/api/types'

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

onMounted(() => {
  load(true)
  refreshFaceStatus()
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
</style>
