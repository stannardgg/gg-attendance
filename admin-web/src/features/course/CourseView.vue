<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import { isAdmin } from '@/shared/store/auth'
import type { CourseRow, TeacherRow } from '@/shared/api/types'

const router = useRouter()
const loading = ref(false)
const rows = ref<CourseRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({ keyword: '', semester: '', showArchived: false })

const dialog = reactive({ open: false, editing: null as CourseRow | null })
const form = reactive({
  code: '',
  name: '',
  semester: '',
  ownerTeacherId: undefined as number | undefined,
  remark: ''
})
const teachers = ref<TeacherRow[]>([])
const saving = ref(false)
const codeError = ref('')
const highlightId = ref<number | null>(null)

/** 行内高亮：把类型标出来，否则模板里的隐式 any 过不了 vue-tsc */
function rowClass({ row }: { row: CourseRow }) {
  return row.id === highlightId.value ? 'hl' : ''
}
const title = computed(() => (dialog.editing ? '编辑课程' : '新建课程'))

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: CourseRow[]; total: number }>('/api/v1/admin/courses', {
      page: page.value,
      size: size.value,
      keyword: query.keyword,
      semester: query.semester,
      // 同上：必须显式传 ALL，不能指望空串
      status: query.showArchived ? 'ALL' : 'ACTIVE'
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

async function loadTeachers() {
  if (!isAdmin.value) return
  try {
    const data = await api.get<{ items: TeacherRow[] }>('/api/v1/admin/teachers', { size: 100 })
    teachers.value = data.items ?? []
  } catch {
    /* 拿不到教师列表不影响建课 */
  }
}

function openCreate() {
  dialog.editing = null
  Object.assign(form, { code: '', name: '', semester: '', ownerTeacherId: undefined, remark: '' })
  codeError.value = ''
  dialog.open = true
}

function openEdit(row: CourseRow) {
  dialog.editing = row
  Object.assign(form, {
    code: row.code,
    name: row.name,
    semester: row.semester ?? '',
    ownerTeacherId: row.ownerTeacherId,
    remark: row.remark ?? ''
  })
  codeError.value = ''
  dialog.open = true
}

async function checkCode() {
  if (!form.code || dialog.editing) return
  if (!/^[A-Z0-9]{2,32}$/.test(form.code)) {
    codeError.value = '课程编码须为 2~32 位大写字母或数字'
    return
  }
  try {
    const data = await api.get<{ items: CourseRow[] }>('/api/v1/admin/courses', {
      keyword: form.code,
      size: 10
    })
    codeError.value = (data.items ?? []).some((c) => c.code === form.code) ? '课程编码已存在' : ''
  } catch {
    codeError.value = ''
  }
}

async function save() {
  if (codeError.value) return
  if (!form.name) {
    ElMessage.warning('请填写课程名称')
    return
  }
  saving.value = true
  try {
    if (dialog.editing) {
      await api.put(`/api/v1/admin/courses/${dialog.editing.id}`, {
        name: form.name,
        semester: form.semester || undefined,
        ownerTeacherId: form.ownerTeacherId ?? null,
        remark: form.remark || undefined
      })
      highlightId.value = dialog.editing.id
    } else {
      const created = await api.post<CourseRow>('/api/v1/admin/courses', {
        code: form.code,
        name: form.name,
        semester: form.semester || undefined,
        ownerTeacherId: form.ownerTeacherId ?? undefined,
        remark: form.remark || undefined
      })
      highlightId.value = created.id
    }
    dialog.open = false
    await load()
    setTimeout(() => (highlightId.value = null), 2000)
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    saving.value = false
  }
}

async function remove(row: CourseRow) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.name}」吗？已被会话引用的课程会转为归档。`, '删除课程', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    const result = await api.del<{ id: number; archived: boolean }>(`/api/v1/admin/courses/${row.id}`)
    ElMessage.success(result.archived ? '该课程已被会话引用，已转为归档' : '已删除')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function archive(row: CourseRow) {
  try {
    await ElMessageBox.confirm('归档后不能再用于发起签到，历史记录仍可查看。确定归档吗？', '归档课程', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await api.put(`/api/v1/admin/courses/${row.id}`, { status: 'ARCHIVED' })
    ElMessage.success('已归档')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

onMounted(() => {
  load()
  loadTeachers()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>课程管理</h2>
      <el-button type="primary" @click="openCreate">新建课程</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-input v-model="query.keyword" placeholder="名称 / 编码" clearable style="width: 200px" @keyup.enter="load" />
        <el-input v-model="query.semester" placeholder="学期，如 2026-2027-1" clearable style="width: 190px" />
        <el-switch v-model="query.showArchived" active-text="显示已归档" @change="load" />
        <el-button @click="load">查询</el-button>
        <span class="hint">课程不含任何班级字段；一门课可以给任意班级使用。</span>
      </div>

      <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
        <el-table-column prop="code" label="课程编码" width="130" />
        <el-table-column prop="name" label="课程名称" min-width="200" />
        <el-table-column prop="semester" label="学期" width="140">
          <template #default="{ row }">{{ row.semester || '—' }}</template>
        </el-table-column>
        <el-table-column prop="ownerTeacherName" label="开课教师" width="120">
          <template #default="{ row }">{{ row.ownerTeacherName || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '在用' : '已归档' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="router.push(`/attendance?courseId=${row.id}`)">签到记录</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="archive(row)">归档</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有课程，先创建一门课程，即可发起签到">
            <el-button type="primary" @click="openCreate">新建课程</el-button>
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

    <el-dialog v-model="dialog.open" :title="title" width="460px">
      <el-form label-width="90px">
        <el-form-item label="课程编码" :error="codeError">
          <el-input v-model="form.code" :disabled="!!dialog.editing" @blur="checkCode" />
        </el-form-item>
        <el-form-item label="课程名称">
          <el-input v-model="form.name" maxlength="120" />
        </el-form-item>
        <el-form-item label="学期">
          <el-input v-model="form.semester" placeholder="2026-2027-1" />
        </el-form-item>
        <el-form-item v-if="isAdmin" label="开课教师">
          <el-select v-model="form.ownerTeacherId" clearable placeholder="可空" style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.name}（${t.teacherNo}）`" :value="t.id" />
          </el-select>
          <div class="field-tip">
            仅作归属标注，不等于数据授权；教师能否看到本课程由「教师管理 → 授权范围」决定
          </div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
.field-tip {
  width: 100%;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  padding-top: 2px;
}
:deep(.hl) {
  background: #ecf5ff !important;
}
</style>
