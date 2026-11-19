<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { ClassRow, CourseRow, SessionRow } from '@/shared/api/types'

const router = useRouter()
const loading = ref(false)
const rows = ref<SessionRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({
  classId: undefined as number | undefined,
  courseId: undefined as number | undefined,
  status: '',
  range: [] as string[]
})
const classes = ref<ClassRow[]>([])
const courses = ref<CourseRow[]>([])

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: SessionRow[]; total: number }>('/api/v1/admin/sessions', {
      page: page.value,
      size: size.value,
      classId: query.classId,
      courseId: query.courseId,
      status: query.status,
      dateFrom: query.range?.[0],
      dateTo: query.range?.[1]
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

async function loadOrg() {
  try {
    const [cls, crs] = await Promise.all([
      api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', { size: 200, status: 'ACTIVE' }),
      api.get<{ items: CourseRow[] }>('/api/v1/admin/courses', { size: 200, status: 'ACTIVE' })
    ])
    classes.value = cls.items ?? []
    courses.value = crs.items ?? []
  } catch {
    // 下拉为空只影响筛选，不影响列表
  }
}

async function close(row: SessionRow) {
  try {
    await ElMessageBox.confirm(
      `结束「${row.title}」后，未签到的 ${row.expectedCount - row.presentCount - row.lateCount} 人会补记为缺勤，且不能再签到。确定结束吗？`,
      '结束签到',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.post(`/api/v1/admin/sessions/${row.id}/close`)
    ElMessage.success('已结束，未签到者已补记缺勤')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function rotate(row: SessionRow) {
  try {
    await ElMessageBox.confirm(
      '换码后旧二维码立即失效，大屏会在下一次刷新时显示新码。适合怀疑二维码被拍照外传时使用。',
      '强制换码',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.post(`/api/v1/admin/sessions/${row.id}/rotate-qr`)
    ElMessage.success('已换码，旧码失效')
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function exportCsv(row: SessionRow) {
  try {
    const blob = await api.download('/api/v1/admin/attendance/export', { sessionId: row.id })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.className || 'class'}-${row.title}-${row.startAt.slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

function statusLabel(s: string) {
  return s === 'ACTIVE' ? '进行中' : s === 'CLOSED' ? '已结束' : '已取消'
}
function statusType(s: string) {
  return s === 'ACTIVE' ? 'success' : s === 'CLOSED' ? 'info' : 'warning'
}
function rate(row: SessionRow) {
  if (!row.expectedCount) return '—'
  return `${(((row.presentCount + row.lateCount) / row.expectedCount) * 100).toFixed(1)}%`
}

onMounted(() => {
  load()
  loadOrg()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>签到场次</h2>
      <el-button type="primary" @click="router.push('/sessions/new')">发起签到</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-select v-model="query.classId" clearable filterable placeholder="全部班级" style="width: 180px" @change="load">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.courseId" clearable filterable placeholder="全部课程" style="width: 180px" @change="load">
          <el-option v-for="c in courses" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px" @change="load">
          <el-option label="进行中" value="ACTIVE" />
          <el-option label="已结束" value="CLOSED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-date-picker
          v-model="query.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 250px"
          @change="load"
        />
        <el-button @click="load">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="title" label="场次" min-width="170" />
        <el-table-column label="班级 / 课程" min-width="220">
          <template #default="{ row }">
            <div>{{ row.className || `班级#${row.classId}` }}</div>
            <div class="sub">{{ row.courseName || `课程#${row.courseId}` }}</div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="230">
          <template #default="{ row }">
            <div>{{ (row.startAt || '').replace('T', ' ').slice(0, 16) }}</div>
            <div class="sub">至 {{ (row.endAt || '').replace('T', ' ').slice(11, 16) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="应到 / 实到" width="130">
          <template #default="{ row }">
            <span class="num">{{ row.presentCount + row.lateCount }}</span>
            <span class="sub"> / {{ row.expectedCount }}</span>
          </template>
        </el-table-column>
        <el-table-column label="签到率" width="90">
          <template #default="{ row }">{{ rate(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/sessions/${row.id}/live`)">大屏</el-button>
            <el-button link type="primary" @click="router.push(`/attendance?sessionId=${row.id}`)">名单</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="rotate(row)">换码</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="close(row)">结束</el-button>
            <el-button link type="primary" @click="exportCsv(row)">导出</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有签到记录">
            <el-button type="primary" @click="router.push('/sessions/new')">发起签到</el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="load"
      />
    </el-card>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sub {
  font-size: 12px;
  color: #909399;
}
.num {
  font-weight: 600;
  color: #303133;
}
</style>
