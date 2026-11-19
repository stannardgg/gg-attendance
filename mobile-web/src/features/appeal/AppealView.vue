<script setup lang="ts">
/**
 * 申诉（§7.3）。
 * 进来先看这条考勤记录现在到底是什么状态 —— 已经签到成功的人不该看到申诉表单，
 * 已经申诉过的人要看到进度与撤回入口。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, messageOf } from '@/shared/api/client'
import type { Appeal, MobileAttendance } from '@/shared/api/types'

const route = useRoute()
const router = useRouter()
const sid = Number(route.params.sid)

const loading = ref(true)
const attendance = ref<MobileAttendance | null>(null)
const reason = ref('')
const submitting = ref(false)
const myAppeal = ref<Appeal | null>(null)

const canSubmit = computed(
  () =>
    attendance.value?.canAppeal === true &&
    (attendance.value.status === 'ABSENT' || attendance.value.status === 'LATE') &&
    !attendance.value.appealId
)
const appealStatus = computed(() => attendance.value?.appealStatus ?? '')
const statusText: Record<string, string> = {
  PENDING: '已提交，等老师处理',
  APPROVED: '已通过，考勤已改为已签到',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回'
}

async function load() {
  loading.value = true
  try {
    attendance.value = await api.get<MobileAttendance>(`/api/v1/mobile/attendance/${sid}`)
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-2001') {
      showToast('找不到这条签到记录')
    } else {
      showToast(messageOf(err))
    }
  } finally {
    loading.value = false
  }
}

async function submit() {
  const text = reason.value.trim()
  if (text.length < 5) {
    showToast('请把情况写清楚一点，至少 5 个字')
    return
  }
  submitting.value = true
  try {
    myAppeal.value = await api.post<Appeal>('/api/v1/mobile/appeals', { sessionId: sid, reason: text })
    showToast('已提交，等老师处理')
    await load()
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-5001') {
      showToast('这条记录已经有一条待处理的申诉了')
    } else {
      showToast(messageOf(err))
    }
  } finally {
    submitting.value = false
  }
}

async function withdraw() {
  const id = attendance.value?.appealId
  if (!id) return
  submitting.value = true
  try {
    await api.post(`/api/v1/mobile/appeals/${id}/withdraw`)
    showToast('已撤回')
    await load()
  } catch (err) {
    showToast(messageOf(err))
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="提交申诉" left-arrow @click-left="router.back()" />

    <van-loading v-if="loading" class="center" style="height: 40vh" />

    <template v-else>
      <van-cell-group inset style="margin-bottom: 12px">
        <van-cell title="班级" :value="attendance?.className || '—'" />
        <van-cell title="课程" :value="attendance?.courseName || '—'" />
        <van-cell
          title="当前状态"
          :value="
            attendance?.status === 'LATE'
              ? '迟到'
              : attendance?.status === 'ABSENT'
                ? '缺勤'
                : attendance?.status === 'PRESENT'
                  ? '已签到'
                  : attendance?.status === 'LEAVE'
                    ? '请假'
                    : '免签'
          "
        />
      </van-cell-group>

      <!-- 已有申诉：展示进度，PENDING 允许撤回 -->
      <template v-if="attendance?.appealId || myAppeal">
        <van-cell-group inset>
          <van-cell title="申诉状态" :value="statusText[appealStatus] || appealStatus" />
          <van-cell v-if="myAppeal?.reason" title="我的说明" :label="myAppeal.reason" />
        </van-cell-group>
        <div v-if="appealStatus === 'PENDING'" style="margin: 16px">
          <van-button round block plain type="danger" :loading="submitting" @click="withdraw">
            撤回申诉
          </van-button>
        </div>
      </template>

      <template v-else-if="canSubmit">
        <van-cell-group inset>
          <van-field
            v-model="reason"
            type="textarea"
            rows="4"
            maxlength="500"
            show-word-limit
            label="情况说明"
            placeholder="例如：当时我就在教室里，戴了帽子可能影响识别，请老师核对。"
          />
        </van-cell-group>
        <p class="muted" style="margin: 10px 6px">
          申诉由任课老师人工核对。提交前请确认说明属实 —— 恶意申诉会被记录。
        </p>
        <div style="margin: 16px">
          <van-button round block type="primary" :loading="submitting" @click="submit">
            提交申诉
          </van-button>
        </div>
      </template>

      <van-empty
        v-else
        image="info-o"
        :description="
          attendance?.status === 'PRESENT'
            ? '你已经签到成功，不需要申诉'
            : attendance?.canAppeal === false
              ? '本次签到没有开放申诉'
              : '这条记录现在不能申诉'
        "
      />
    </template>
  </div>
</template>
