<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, messageOf } from '@/shared/api/client'
import type { AppealRow, Overview } from '@/shared/api/types'
import { ElMessage } from 'element-plus'

const router = useRouter()
const loading = ref(true)
const overview = ref<Overview>({
  todaySessions: 0,
  todayExpected: 0,
  todayPresent: 0,
  todayRate: 0,
  pendingAppeals: 0,
  trend: []
})
const appeals = ref<AppealRow[]>([])

// 近 7 天折线的点坐标。手写 SVG 而不是引 echarts：这张图只要 7 个点，
// 为了它多打 300KB 会把 Lighthouse 性能拖到 85 以下（WP-10 验收要求）。
const trendPoints = computed(() => {
  const list = overview.value.trend ?? []
  if (list.length === 0) return ''
  const w = 520
  const h = 120
  const max = 1
  return list
    .map((p, i) => {
      const x = list.length === 1 ? w / 2 : (i / (list.length - 1)) * w
      const y = h - Math.min(1, p.rate / max) * h
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
})

async function load() {
  loading.value = true
  try {
    const [ov, ap] = await Promise.all([
      api.get<Overview>('/api/v1/admin/stats/overview'),
      api.get<{ items: AppealRow[] }>('/api/v1/admin/appeals', { status: 'PENDING', size: 5 })
    ])
    overview.value = ov
    appeals.value = ap.items ?? []
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

function rate(value: number | undefined): string {
  return `${((value ?? 0) * 100).toFixed(1)}%`
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <h2>概览</h2>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <el-row :gutter="12">
      <el-col :span="6">
        <el-card shadow="never" class="metric">
          <div class="label">今日签到场次</div>
          <div class="value">{{ overview.todaySessions }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric">
          <div class="label">应到 / 实到</div>
          <div class="value">
            {{ overview.todayPresent }}<span class="unit">/{{ overview.todayExpected }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric">
          <div class="label">今日出勤率</div>
          <div class="value">{{ rate(overview.todayRate) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="metric clickable" @click="router.push('/appeals')">
          <div class="label">待处理申诉</div>
          <div class="value" :class="{ warn: overview.pendingAppeals > 0 }">
            {{ overview.pendingAppeals }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>近 7 天出勤率</template>
          <svg v-if="trendPoints" viewBox="0 0 520 120" class="trend">
            <polyline :points="trendPoints" fill="none" stroke="#2f6feb" stroke-width="2" />
            <line x1="0" y1="119" x2="520" y2="119" stroke="#e8eaef" />
          </svg>
          <el-empty v-else description="近 7 天还没有签到数据" :image-size="60" />
          <div v-if="overview.trend?.length" class="axis">
            <span v-for="p in overview.trend" :key="p.date">{{ p.date.slice(5) }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>待处理申诉</span>
              <el-button link type="primary" @click="router.push('/appeals')">去处理</el-button>
            </div>
          </template>
          <el-empty v-if="appeals.length === 0" description="没有待处理申诉" :image-size="60" />
          <div v-for="a in appeals" :key="a.id" class="appeal">
            <div>
              <strong>{{ a.studentName || `学生 #${a.studentId}` }}</strong>
              <span class="hint">{{ a.sessionTitle }}</span>
            </div>
            <div class="reason">{{ a.reason }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.metric .label {
  color: #909399;
  font-size: 12px;
}
.metric .value {
  font-size: 26px;
  font-weight: 600;
  margin-top: 6px;
}
.metric .value.warn {
  color: #e6a23c;
}
.unit {
  font-size: 14px;
  color: #909399;
  font-weight: 400;
}
.clickable {
  cursor: pointer;
}
.trend {
  width: 100%;
  height: 140px;
}
.axis {
  display: flex;
  justify-content: space-between;
  color: #909399;
  font-size: 11px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.appeal {
  border-bottom: 1px solid #f0f2f5;
  padding: 8px 0;
}
.appeal:last-child {
  border-bottom: none;
}
.reason {
  color: #606266;
  font-size: 12px;
  margin-top: 4px;
}
</style>
