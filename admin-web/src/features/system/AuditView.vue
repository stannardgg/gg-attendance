<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { AuditRow } from '@/shared/api/types'

const loading = ref(false)
const rows = ref<AuditRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({ action: '', targetType: '', range: [] as string[] })

// 后端 AuditWriter 写的是 CLASS_CREATE 这种「大写下划线」；
// 早期前端按 class.create 写的映射，两边对不上会导致中文标签全部失效。
// 这里两种都收，新数据一律以后端格式为准。
const actionHints: Record<string, string> = {
  CLASS_CREATE: '新建班级',
  CLASS_UPDATE: '编辑班级',
  CLASS_DELETE: '删除班级',
  CLASS_ARCHIVE: '归档班级',
  COURSE_CREATE: '新建课程',
  COURSE_UPDATE: '编辑课程',
  COURSE_DELETE: '删除课程',
  COURSE_ARCHIVE: '归档课程',
  STUDENT_CREATE: '新建学生',
  STUDENT_UPDATE: '编辑学生',
  STUDENT_TRANSFER: '学生转班',
  STUDENT_DELETE: '删除学生',
  STUDENT_IMPORT: 'CSV 导入',
  FACE_REVIEW: '人脸审核',
  SESSION_CREATE: '发起签到',
  SESSION_CLOSE: '结束签到',
  SESSION_ROTATE_QR: '强制换码',
  ATTENDANCE_ADJUST: '手动改考勤',
  CHECKIN_COMMIT: '学生签到',
  APPEAL_HANDLE: '处理申诉',
  TEACHER_CREATE: '新建教师',
  TEACHER_UPDATE: '编辑教师',
  TEACHER_DELETE: '删除教师',
  SCOPE_GRANT: '授权范围',
  SCOPE_REVOKE: '撤销授权',
  CONFIG_UPDATE: '修改配置',
  AGENT_WRITE: 'Agent 写操作',
  AGENT_UNDO: 'Agent 撤销'
}

/** 兼容历史小写点号格式（class.create）；查不到就原样返回，不吞掉信息。 */
function actionLabel(action?: string): string {
  if (!action) return '—'
  return actionHints[action] || actionHints[action.toUpperCase().replace(/\./g, '_')] || action
}

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: AuditRow[]; total: number }>('/api/v1/admin/audit-logs', {
      page: page.value,
      size: size.value,
      action: query.action,
      targetType: query.targetType,
      // 契约这里要的是 date-time，不是 date
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

const detail = reactive({ open: false, row: null as AuditRow | null })

function openDetail(row: AuditRow) {
  detail.row = row
  detail.open = true
}

function pretty(v: unknown) {
  if (v === null || v === undefined) return '—'
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

function fmt(t?: string) {
  return (t || '').replace('T', ' ').slice(0, 19) || '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>审计日志</h2>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-input v-model="query.action" placeholder="动作，如 CLASS_UPDATE" clearable style="width: 240px"
          @keyup.enter="load" />
        <el-input v-model="query.targetType" placeholder="对象类型，如 class" clearable style="width: 180px" />
        <el-date-picker
          v-model="query.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          style="width: 340px"
          @change="load"
        />
        <el-button type="primary" @click="load">查询</el-button>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="150">
          <template #default="{ row }">
            <div>{{ row.actorName || '系统' }}</div>
            <div class="sub">{{ row.actorRole || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="动作" min-width="180">
          <template #default="{ row }">
            <div>{{ actionLabel(row.action) }}</div>
            <div class="sub">{{ row.action }}</div>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="180">
          <template #default="{ row }">
            <span v-if="row.targetType">{{ row.targetType }}#{{ row.targetId ?? '—' }}</span>
            <span v-else class="sub">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="clientIp" label="来源 IP" width="140">
          <template #default="{ row }">{{ row.clientIp || '—' }}</template>
        </el-table-column>
        <el-table-column prop="traceId" label="traceId" width="160">
          <template #default="{ row }">
            <span class="mono">{{ row.traceId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="没有符合条件的审计记录" />
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

    <el-drawer v-model="detail.open" title="审计详情" size="560px">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="时间">{{ fmt(detail.row?.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="操作人">
          {{ detail.row?.actorName || '系统' }}（{{ detail.row?.actorRole || '—' }}）
        </el-descriptions-item>
        <el-descriptions-item label="动作">
          {{ actionLabel(detail.row?.action) }}
        </el-descriptions-item>
        <el-descriptions-item label="对象">
          {{ detail.row?.targetType || '—' }} #{{ detail.row?.targetId ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="来源 IP">{{ detail.row?.clientIp || '—' }}</el-descriptions-item>
        <el-descriptions-item label="traceId">
          <span class="mono">{{ detail.row?.traceId || '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="json-block">
        <div class="json-title">变更前</div>
        <pre>{{ pretty(detail.row?.before) }}</pre>
      </div>
      <div class="json-block">
        <div class="json-title">变更后</div>
        <pre>{{ pretty(detail.row?.after) }}</pre>
      </div>
    </el-drawer>
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
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.json-block {
  margin-top: 14px;
}
.json-title {
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}
.json-block pre {
  margin: 0;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 10px;
  font-size: 12px;
  line-height: 1.6;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
