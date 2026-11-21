<script setup lang="ts">
/**
 * 申诉（§7.3）。
 *
 * 进来先看这条考勤记录现在到底是什么状态：
 * - 已签成功 / 请假 / 免签 → 不需要申诉；
 * - 缺勤、迟到、**会话进行中但一直没签成功**（后端返回 status=null）→ 可以申诉；
 * - 已有在办申诉（PENDING / APPROVED）→ 展示进度，PENDING 可撤回。
 *
 * 注意：没有 attendance 行也是合法输入。以前 myAttendance 直接抛 FA-2001，
 * 学生刷脸失败后点「提交申诉」只能看到三行「—」和一句"这条记录现在不能申诉"。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, messageOf } from '@/shared/api/client'
import type { Appeal, MobileAttendance } from '@/shared/api/types'

const route = useRoute()
const router = useRouter()
const sid = Number(route.params.sid)
/**
 * 路径参数必须是正整数。`/appeal/null`、`/appeal/undefined` 这类链接
 * 以前会直接发请求，后端 400/404 → 学生只看到「记录加载失败」，
 * 误以为是系统故障。现在就地判为「入场信息已过期」，并给一条可走的路。
 */
const sidValid = Number.isFinite(sid) && sid > 0

const loading = ref(true)
const loadFailed = ref(false)
const invalidSid = ref(false)
const attendance = ref<MobileAttendance | null>(null)
const reason = ref('')
const submitting = ref(false)
const myAppeal = ref<Appeal | null>(null)

const appealStatus = computed(() => myAppeal.value?.status ?? attendance.value?.appealStatus ?? '')
const appealId = computed(() => myAppeal.value?.id ?? attendance.value?.appealId ?? null)
/** 在办申诉：还在处理或已通过，都不该再给一张表单 */
const hasOpenAppeal = computed(
  () => appealStatus.value === 'PENDING' || appealStatus.value === 'APPROVED'
)
const canSubmit = computed(() => attendance.value?.canAppeal === true && !hasOpenAppeal.value)

const statusLabel = computed(() => {
  switch (attendance.value?.status) {
    case 'PRESENT':
      return '已签到'
    case 'LATE':
      return '迟到'
    case 'ABSENT':
      return '缺勤'
    case 'LEAVE':
      return '请假'
    case 'EXEMPT':
      return '免签'
    case null:
    case undefined:
      return '未签到'
    default:
      return '—'
  }
})

/** 不能提交时，把"为什么不能"说清楚，别再让学生猜 */
const emptyText = computed(() => {
  const st = attendance.value?.status
  if (st === 'PRESENT') return '你已经签到成功，不需要申诉'
  if (st === 'LEAVE') return '这场已记请假，不需要申诉'
  if (st === 'EXEMPT') return '这场是免签，不需要申诉'
  if (attendance.value?.canAppeal === false) {
    // 进行中但没签成功（status=null）也走这里：多半是老师没开申诉入口
    return st === 'ABSENT' ||
      st === 'LATE' ||
      st === null ||
      st === undefined
      ? '本次签到没有开放申诉，请联系任课老师'
      : '这条记录现在不能申诉'
  }
  return '这条记录现在不能申诉'
})

const statusText: Record<string, string> = {
  PENDING: '已提交，等老师处理',
  APPROVED: '已通过，考勤已改为已签到',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回'
}

async function load() {
  loading.value = true
  loadFailed.value = false
  invalidSid.value = false
  if (!sidValid) {
    invalidSid.value = true
    loadFailed.value = true
    loading.value = false
    return
  }
  try {
    attendance.value = await api.get<MobileAttendance>(`/api/v1/mobile/attendance/${sid}`)
  } catch (err) {
    loadFailed.value = true
    if (err instanceof ApiError && err.code === 'FA-2001') {
      showToast(err.message || '找不到这场签到')
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
    showToast('你已提交本次申诉')
    await load()
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-5001') {
      // 后端生成列兜底：同一场次只能有一条 PENDING
      showToast('你已提交本次申诉')
    } else {
      showToast(messageOf(err))
    }
  } finally {
    submitting.value = false
  }
}

async function withdraw() {
  const id = appealId.value
  if (!id) return
  submitting.value = true
  try {
    await api.post(`/api/v1/mobile/appeals/${id}/withdraw`)
    showToast('已撤回')
    myAppeal.value = null
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

    <van-empty
      v-else-if="loadFailed"
      :image="invalidSid ? 'error' : 'network'"
      :description="invalidSid ? '签到信息已过期，请从签到页重新进入' : '记录加载失败，请稍后重试'"
    >
      <van-button v-if="invalidSid" round type="primary" size="small" @click="router.replace('/checkin')">
        返回签到页
      </van-button>
      <van-button v-else round type="primary" size="small" @click="load">重新加载</van-button>
    </van-empty>

    <template v-else>
      <!-- 申诉与签到互斥：提交后这场不能再签，把结果说在前面 -->
      <van-notice-bar
        v-if="appealStatus === 'PENDING'"
        wrapable
        :scrollable="false"
        type="warning"
        text="你已提交本次申诉，本次签到不可再次参与。老师处理后会在这里显示结果。"
        style="margin-bottom: 12px"
      />

      <van-cell-group inset style="margin-bottom: 12px">
        <van-cell title="班级" :value="attendance?.className || '—'" />
        <van-cell title="课程" :value="attendance?.courseName || '—'" />
        <van-cell title="当前状态" :value="statusLabel" />
      </van-cell-group>

      <!-- 已有在办申诉：展示进度，PENDING 允许撤回 -->
      <template v-if="hasOpenAppeal">
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
        <p
          v-if="appealStatus === 'REJECTED'"
          class="muted"
          style="margin: 0 6px 10px; color: #ee0a24"
        >
          上次申诉已被驳回，补充说明后可以重新提交。
        </p>
        <van-cell-group inset>
          <van-field
            v-model="reason"
            type="textarea"
            rows="4"
            maxlength="500"
            show-word-limit
            label="情况说明"
            placeholder="例如：当时我就在教室里，刷脸一直提示未通过，请老师核对。"
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

      <van-empty v-else :description="emptyText" />
    </template>
  </div>
</template>
