<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { AttendanceRow, ClassRow, CourseRow } from '@/shared/api/types'

const route = useRoute()
const loading = ref(false)
const rows = ref<AttendanceRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const classes = ref<ClassRow[]>([])
const courses = ref<CourseRow[]>([])
const query = reactive({
  sessionId: undefined as number | undefined,
  classId: undefined as number | undefined,
  courseId: undefined as number | undefined,
  status: '',
  studentKeyword: '',
  range: [] as string[]
})

const statusLabel: Record<string, string> = {
  PRESENT: '已签到',
  LATE: '迟到',
  ABSENT: '缺勤',
  LEAVE: '请假',
  EXEMPT: '免签'
}
type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'
const statusType: Record<string, TagType> = {
  PRESENT: 'success',
  LATE: 'warning',
  ABSENT: 'danger',
  LEAVE: 'primary',
  EXEMPT: 'info'
}
/** 失败门禁 → 中文，教师判申诉时靠它区分「误伤」和「真没到」。 */
const gateLabel: Record<string, string> = {
  liveness: '活体未过',
  identity: '身份不符',
  action_count: '动作未完成',
  multi_face: '画面多人',
  no_face: '未检测到人脸',
  match: '比对不通过'
}

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: AttendanceRow[]; total: number }>('/api/v1/admin/attendance', {
      page: page.value,
      size: size.value,
      sessionId: query.sessionId,
      classId: query.classId,
      courseId: query.courseId,
      status: query.status,
      studentKeyword: query.studentKeyword,
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
      api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', { size: 200 }),
      api.get<{ items: CourseRow[] }>('/api/v1/admin/courses', { size: 200 })
    ])
    classes.value = cls.items ?? []
    courses.value = crs.items ?? []
  } catch {
    /* 只影响筛选下拉 */
  }
}

/* ---------------- 手动改状态 ---------------- */

const patch = reactive({
  open: false,
  row: null as AttendanceRow | null,
  status: 'LEAVE' as string,
  reason: '',
  saving: false
})

function openPatch(row: AttendanceRow) {
  if (row.id === null) {
    ElMessage.info('这位同学还没签到，没有可调整的考勤记录；请用 Agent 的「标记请假」或等结束后补录')
    return
  }
  patch.row = row
  patch.status = row.status
  patch.reason = ''
  patch.open = true
}

async function savePatch() {
  if (!patch.row) return
  if (patch.reason.trim().length < 2) {
    ElMessage.warning('请填写改动原因，至少 2 个字（会进审计日志）')
    return
  }
  patch.saving = true
  try {
    const updated = await api.patch<AttendanceRow>(`/api/v1/admin/attendance/${patch.row.id}`, {
      status: patch.status,
      adjustReason: patch.reason.trim()
    })
    patch.row.status = updated.status
    patch.row.adjustReason = updated.adjustReason
    patch.open = false
    ElMessage.success('已更新')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    patch.saving = false
  }
}

/* ---------------- 导出 ---------------- */

async function exportXlsx() {
  try {
    const blob = await api.download('/api/v1/admin/attendance/export', {
      sessionId: query.sessionId,
      classId: query.classId,
      courseId: query.courseId,
      dateFrom: query.range?.[0],
      dateTo: query.range?.[1]
    })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `attendance-${new Date().toISOString().slice(0, 10)}.xlsx`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出（缺勤学生整行红底高亮）')
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

function reset() {
  Object.assign(query, {
    sessionId: undefined,
    classId: undefined,
    courseId: undefined,
    status: '',
    studentKeyword: '',
    range: []
  })
  page.value = 1
  load()
}

function fmt(t?: string) {
  return (t || '').replace('T', ' ').slice(0, 19) || '—'
}

onMounted(async () => {
  const sid = Number(route.query.sessionId)
  if (Number.isFinite(sid) && sid > 0) query.sessionId = sid
  await loadOrg()
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>考勤查询</h2>
      <el-button @click="exportXlsx">导出 Excel</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-select v-model="query.classId" clearable filterable placeholder="全部班级" style="width: 170px" @change="load">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.courseId" clearable filterable placeholder="全部课程" style="width: 170px" @change="load">
          <el-option v-for="c in courses" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 120px" @change="load">
          <el-option v-for="(v, k) in statusLabel" :key="k" :label="v" :value="k" />
        </el-select>
        <el-input v-model="query.studentKeyword" placeholder="姓名 / 学号" clearable style="width: 170px" @keyup.enter="load" />
        <el-date-picker v-model="query.range" type="daterange" value-format="YYYY-MM-DD"
          start-placeholder="开始" end-placeholder="结束" style="width: 240px" @change="load" />
        <el-button type="primary" @click="load">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-input v-if="query.sessionId" :model-value="`场次 #${query.sessionId}`" readonly style="width: 130px" />
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="学生" width="160">
          <template #default="{ row }">
            <div>{{ row.studentName }}</div>
            <div class="sub">{{ row.studentNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="班级 / 课程" min-width="200">
          <template #default="{ row }">
            <div>{{ row.className || '—' }}</div>
            <div class="sub">{{ row.courseName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="sessionTitle" label="场次" min-width="150">
          <template #default="{ row }">{{ row.sessionTitle || `#${row.sessionId}` }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType[row.status]">{{ statusLabel[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="签到时间" width="170">
          <template #default="{ row }">{{ fmt(row.checkinAt) }}</template>
        </el-table-column>
        <el-table-column label="比对 / 活体" width="140">
          <template #default="{ row }">
            <span v-if="row.similarity != null">{{ row.similarity.toFixed(3) }}</span>
            <span v-else class="sub">—</span>
            <span v-if="row.livenessMedian != null" class="sub"> / {{ row.livenessMedian.toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="失败门禁" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.rejectGate" size="small" type="danger">
              {{ gateLabel[row.rejectGate] || row.rejectGate }}
            </el-tag>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="改动说明" min-width="150">
          <template #default="{ row }">
            <span v-if="row.adjustReason">{{ row.adjustReason }}</span>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.id === null" @click="openPatch(row)">
              改状态
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的考勤记录" />
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

    <el-dialog v-model="patch.open" title="手动改考勤状态" width="440px">
      <el-descriptions :column="1" border size="small" style="margin-bottom: 14px">
        <el-descriptions-item label="学生">
          {{ patch.row?.studentName }}（{{ patch.row?.studentNo }}）
        </el-descriptions-item>
        <el-descriptions-item label="当前状态">
          {{ statusLabel[patch.row?.status || ''] }}
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="90px">
        <el-form-item label="改为">
          <el-radio-group v-model="patch.status">
            <el-radio value="PRESENT">已签到</el-radio>
            <el-radio value="LATE">迟到</el-radio>
            <el-radio value="ABSENT">缺勤</el-radio>
            <el-radio value="LEAVE">请假</el-radio>
            <el-radio value="EXEMPT">免签</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="改动原因">
          <el-input v-model="patch.reason" type="textarea" :rows="3" maxlength="255" show-word-limit
            placeholder="如：学生已提交病假条纸质版" />
        </el-form-item>
      </el-form>
      <p class="tip">改动会写入 adjusted_by / adjust_reason 并留审计，学生端会看到原因。</p>
      <template #footer>
        <el-button @click="patch.open = false">取消</el-button>
        <el-button type="primary" :loading="patch.saving" @click="savePatch">保存</el-button>
      </template>
    </el-dialog>
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
.tip {
  font-size: 12px;
  color: #909399;
}
</style>
