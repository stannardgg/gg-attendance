<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { AppealRow, ClassRow, CourseRow } from '@/shared/api/types'

const loading = ref(false)
const rows = ref<AppealRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const classes = ref<ClassRow[]>([])
const courses = ref<CourseRow[]>([])
const query = reactive({ status: 'PENDING', classId: undefined as number | undefined, courseId: undefined as number | undefined })

const statusLabel: Record<string, string> = {
  PENDING: '待处理',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回'
}
type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'
const statusType: Record<string, TagType> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  WITHDRAWN: 'info'
}
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
    const data = await api.get<{ items: AppealRow[]; total: number }>('/api/v1/admin/appeals', {
      page: page.value,
      size: size.value,
      status: query.status,
      classId: query.classId,
      courseId: query.courseId
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
    /* 只影响筛选 */
  }
}

/* ---------------- 处理 ---------------- */

const handle = reactive({ open: false, row: null as AppealRow | null, approved: true, reply: '', saving: false })
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'PENDING').length)

function openHandle(row: AppealRow) {
  handle.row = row
  handle.approved = true
  handle.reply = ''
  handle.open = true
}

function presetReply() {
  if (!handle.row) return
  handle.reply = handle.approved
    ? '已核实你当时在现场且身份无误，本次记为已签到。'
    : '经核查比对记录，本次签到确为本人失败，维持原记录。'
}

async function submit() {
  if (!handle.row) return
  if (handle.reply.trim().length < 2) {
    ElMessage.warning('无论通过还是驳回都必须给理由，至少 2 个字')
    return
  }
  handle.saving = true
  try {
    const updated = await api.post<AppealRow>(`/api/v1/admin/appeals/${handle.row.id}/handle`, {
      approved: handle.approved,
      reply: handle.reply.trim()
    })
    handle.row.status = updated.status
    handle.row.reply = updated.reply
    handle.open = false
    ElMessage.success(handle.approved ? '已通过，考勤已改为已签到' : '已驳回')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    handle.saving = false
  }
}

function fmt(t?: string) {
  return (t || '').replace('T', ' ').slice(0, 19) || '—'
}

onMounted(() => {
  load()
  loadOrg()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>申诉处理<span v-if="pendingCount" class="badge">{{ pendingCount }} 待处理</span></h2>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-radio-group v-model="query.status" @change="load">
          <el-radio-button value="PENDING">待处理</el-radio-button>
          <el-radio-button value="APPROVED">已通过</el-radio-button>
          <el-radio-button value="REJECTED">已驳回</el-radio-button>
          <el-radio-button value="">全部</el-radio-button>
        </el-radio-group>
        <el-select v-model="query.classId" clearable filterable placeholder="全部班级" style="width: 170px" @change="load">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="query.courseId" clearable filterable placeholder="全部课程" style="width: 170px" @change="load">
          <el-option v-for="c in courses" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="学生" width="150">
          <template #default="{ row }">
            <div>{{ row.studentName || '—' }}</div>
            <div class="sub">{{ row.studentNo || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="班级 / 课程" min-width="190">
          <template #default="{ row }">
            <div>{{ row.className || '—' }}</div>
            <div class="sub">{{ row.courseName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="sessionTitle" label="场次" min-width="140">
          <template #default="{ row }">{{ row.sessionTitle || `#${row.sessionId}` }}</template>
        </el-table-column>
        <el-table-column label="原失败门禁" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.rejectGate" size="small" type="danger">
              {{ gateLabel[row.rejectGate] || row.rejectGate }}
            </el-tag>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="reason" label="申诉理由" min-width="200" show-overflow-tooltip />
        <el-table-column label="凭证" width="80">
          <template #default="{ row }">
            <a v-if="row.evidenceUrl" :href="row.evidenceUrl" target="_blank" rel="noopener">查看</a>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusType[row.status]">{{ statusLabel[row.status] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="primary" @click="openHandle(row)">处理</el-button>
            <el-button v-else link type="primary" @click="openHandle(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="query.status === 'PENDING' ? '没有待处理的申诉' : '没有符合条件的申诉'" />
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

    <el-dialog v-model="handle.open" :title="handle.row?.status === 'PENDING' ? '处理申诉' : '申诉详情'" width="520px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="学生">
          {{ handle.row?.studentName }}（{{ handle.row?.studentNo }}）
        </el-descriptions-item>
        <el-descriptions-item label="场次">
          {{ handle.row?.sessionTitle }} · {{ handle.row?.className }} / {{ handle.row?.courseName }}
        </el-descriptions-item>
        <el-descriptions-item label="申诉理由">{{ handle.row?.reason }}</el-descriptions-item>
        <el-descriptions-item label="原失败门禁">
          {{ handle.row?.rejectGate ? gateLabel[handle.row.rejectGate] || handle.row.rejectGate : '—' }}
        </el-descriptions-item>
        <el-descriptions-item v-if="handle.row?.reply" label="已有回复">
          {{ handle.row.reply }}
        </el-descriptions-item>
      </el-descriptions>

      <template v-if="handle.row?.status === 'PENDING'">
        <el-form label-width="80px" style="margin-top: 16px">
          <el-form-item label="结论">
            <el-radio-group v-model="handle.approved">
              <el-radio :value="true">通过（记为已签到）</el-radio>
              <el-radio :value="false">驳回</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="回复学生">
            <el-input v-model="handle.reply" type="textarea" :rows="3" maxlength="500" show-word-limit />
            <el-button link type="primary" style="margin-top: 4px" @click="presetReply">填入常用说明</el-button>
          </el-form-item>
        </el-form>
        <p class="tip">通过后考勤状态在同一事务内改为已签到，学生会收到结果。</p>
      </template>

      <template #footer>
        <el-button @click="handle.open = false">关闭</el-button>
        <el-button
          v-if="handle.row?.status === 'PENDING'"
          type="primary"
          :loading="handle.saving"
          @click="submit"
        >
          {{ handle.approved ? '通过申诉' : '驳回申诉' }}
        </el-button>
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
.badge {
  margin-left: 10px;
  font-size: 12px;
  font-weight: 500;
  color: #e6a23c;
}
</style>
