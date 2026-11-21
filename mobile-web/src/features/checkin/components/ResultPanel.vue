<script setup lang="ts">
/**
 * 结果面板。
 * 失败必须给"下一步"：是重试还是申诉，不能只写"签到失败"（§7.3）。
 * 门禁明细默认折叠 —— 学生不需要看阈值，但老师问「为什么失败」时要能翻出来。
 */
import { computed, ref, watch } from 'vue'
import { GATE_ADVICE, GATE_LABEL } from '@/shared/api/errors'
import type { FinishData } from '@/shared/api/types'

const props = defineProps<{
  finish: FinishData | null
  gate?: string | null
  errorText?: string
  className?: string
  courseName?: string
  /** 当前登录学生的姓名。学生看自己的签到结果，姓名取自登录态，不额外请求。 */
  studentName?: string
  attempts: number
  canAppeal: boolean
}>()

const emit = defineEmits<{ (e: 'retry'): void; (e: 'appeal'): void }>()

const passed = computed(() => !!props.finish?.passed)
const gateKey = computed(() => props.gate ?? props.finish?.rejectGate ?? '')
const gateTitle = computed(() => (gateKey.value ? GATE_LABEL[gateKey.value] || '签到未通过' : '签到未通过'))
const advice = computed(() =>
  gateKey.value ? GATE_ADVICE[gateKey.value] || '请调整后重试，或提交申诉。' : '请重试，或提交申诉。'
)
/** 失败 3 次后隐藏重试，只留申诉（§7.4） */
const canRetry = computed(() => !passed.value && props.attempts < 3)

/**
 * 成功页的时间以服务端 `checkinAt` 为准（契约 FinishData.checkinAt）。
 * 旧版人脸服务不回这个字段时退回「首次看到成功结果」的本机时间——仅用于展示，
 * 不参与任何判定；权威记录始终是 attendance.checkin_at。
 */
const localCheckinAt = ref('')
watch(
  () => props.finish?.passed,
  (ok) => {
    if (!ok) {
      // 重试会把 finish 置空，不清掉的话下一次成功仍显示上一轮的时间
      localCheckinAt.value = ''
      return
    }
    if (!localCheckinAt.value && !props.finish?.checkinAt) {
      localCheckinAt.value = formatTime(new Date())
    }
  },
  { immediate: true }
)

const checkinAtText = computed(() => {
  const raw = props.finish?.checkinAt
  if (raw) return raw.replace('T', ' ').slice(0, 19)
  return localCheckinAt.value || '—'
})

function formatTime(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

const gateRows = computed(() => {
  const g = props.finish?.gates
  if (!g) return []
  const rows: { label: string; value: string; ok: boolean }[] = []
  if (g.liveness) {
    rows.push({
      label: '活体检测',
      value: g.liveness.value != null ? g.liveness.value.toFixed(3) : '—',
      ok: g.liveness.passed
    })
  }
  if (g.identity) {
    rows.push({
      label: '身份一致性',
      value: g.identity.value != null ? g.identity.value.toFixed(3) : '—',
      ok: g.identity.passed
    })
  }
  if (g.actionCount) {
    rows.push({
      label: '动作次数',
      value: `${g.actionCount.detected ?? '—'} / ${g.actionCount.required ?? '—'}`,
      ok: g.actionCount.passed
    })
  }
  return rows
})
</script>

<template>
  <div class="result">
    <template v-if="passed">
      <div class="badge ok">✓</div>
      <h2 class="title">签到成功</h2>
      <p class="sub">
        {{ className }} · {{ courseName }}
      </p>
      <van-cell-group inset style="margin-top: 18px; text-align: left">
        <van-cell v-if="studentName" title="姓名" :value="studentName" />
        <van-cell title="状态" :value="finish?.status === 'LATE' ? '迟到' : '已签到'" />
        <van-cell title="时间" :value="checkinAtText" />
        <van-cell v-if="finish?.baselineDegraded" title="基线帧" value="已降级（不影响结果）" />
      </van-cell-group>
      <div style="margin-top: 22px">
        <van-button round block type="primary" @click="$router.replace('/')">完成</van-button>
      </div>
    </template>

    <template v-else>
      <div class="badge fail">×</div>
      <h2 class="title">{{ gateTitle }}</h2>
      <p class="sub">{{ errorText || advice }}</p>

      <van-notice-bar
        v-if="advice && errorText"
        wrapable
        :scrollable="false"
        type="warning"
        :text="advice"
        style="margin-top: 14px; text-align: left"
      />

      <van-collapse v-if="gateRows.length" style="margin-top: 12px; text-align: left">
        <van-collapse-item title="门禁明细" name="gates">
          <van-cell v-for="r in gateRows" :key="r.label" :title="r.label" :value="r.value">
            <template #right-icon>
              <span :class="r.ok ? 'ok-mark' : 'fail-mark'">{{ r.ok ? '通过' : '未过' }}</span>
            </template>
          </van-cell>
        </van-collapse-item>
      </van-collapse>

      <div class="actions">
        <van-button v-if="canRetry" round block type="primary" @click="emit('retry')">
          再试一次（第 {{ attempts + 1 }} 次）
        </van-button>
        <van-button
          v-if="canAppeal"
          round
          block
          :type="canRetry ? 'default' : 'primary'"
          style="margin-top: 10px"
          @click="emit('appeal')"
        >
          提交申诉
        </van-button>
        <p v-if="!canRetry && !canAppeal" class="muted" style="margin-top: 12px">
          本次签到的申诉入口未开启。请联系任课老师说明情况。
        </p>
        <p v-if="!canRetry && canAppeal" class="muted" style="margin-top: 10px">
          已经试过 3 次，请提交申诉让老师人工核对。
        </p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.result {
  padding: 28px 18px 24px;
  text-align: center;
}
.badge {
  width: 68px;
  height: 68px;
  margin: 0 auto 14px;
  border-radius: 50%;
  font-size: 38px;
  font-weight: 700;
  line-height: 68px;
  color: #fff;
}
.badge.ok {
  background: #07c160;
}
.badge.fail {
  background: #ee0a24;
}
.title {
  font-size: 21px;
  margin: 0 0 6px;
}
.sub {
  color: #646566;
  font-size: 14px;
  line-height: 1.5;
  margin: 0;
}
.actions {
  margin-top: 22px;
}
.ok-mark {
  color: #07c160;
  font-size: 13px;
}
.fail-mark {
  color: #ee0a24;
  font-size: 13px;
}
</style>
