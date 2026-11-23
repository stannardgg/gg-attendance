<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import { isAdmin } from '@/shared/store/auth'
import type { ClassRow, CourseGrantRow, CourseRow, StudentRow, TeacherRow } from '@/shared/api/types'

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
  // 学期拆成「学年 + 上/下半年」两个选择器，落库仍是规范格式 2026-2027-1（-1 上半年、-2 下半年）
  semYear: undefined as number | undefined,
  semHalf: undefined as 1 | 2 | undefined,
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

// ---- 学期选择器：学年（起始年）+ 上/下半年 ----
// 取值口径与 Agent 归一化一致（planner._normalize_semester）：裸年份 = 学年起始年，-1 上半年、-2 下半年。
const yearOptions = computed(() => {
  const now = new Date().getFullYear()
  const list: number[] = []
  for (let y = now - 5; y <= now + 2; y++) list.push(y)
  return list
})
const semesterPreview = computed(() =>
  form.semYear && (form.semHalf === 1 || form.semHalf === 2)
    ? `${form.semYear}-${form.semYear + 1}-${form.semHalf}`
    : ''
)
// 编辑旧数据时学期可能是非规范自由文本：不选则保存时不动它（undefined = 后端不更新）
const semesterLegacy = ref('')
function defaultSemester(): { year: number; half: 1 | 2 } {
  const now = new Date()
  const autumn = now.getMonth() + 1 >= 8
  return { year: autumn ? now.getFullYear() : now.getFullYear() - 1, half: autumn ? 1 : 2 }
}

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
  const d = defaultSemester()
  Object.assign(form, { code: '', name: '', semYear: d.year, semHalf: d.half, ownerTeacherId: undefined, remark: '' })
  semesterLegacy.value = ''
  codeError.value = ''
  dialog.open = true
}

function openEdit(row: CourseRow) {
  dialog.editing = row
  // 规范学期拆回选择器；非规范旧值保留原文，不选则不更新
  const m = /^(\d{4})-(\d{4})-([12])$/.exec(row.semester ?? '')
  const canonical = !!m && Number(m[2]) === Number(m[1]) + 1
  Object.assign(form, {
    code: row.code,
    name: row.name,
    semYear: canonical ? Number(m![1]) : undefined,
    semHalf: canonical ? (Number(m![3]) as 1 | 2) : undefined,
    ownerTeacherId: row.ownerTeacherId,
    remark: row.remark ?? ''
  })
  semesterLegacy.value = canonical ? '' : (row.semester ?? '')
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
        semester: semesterPreview.value || undefined,
        ownerTeacherId: form.ownerTeacherId ?? null,
        remark: form.remark || undefined
      })
      highlightId.value = dialog.editing.id
    } else {
      const created = await api.post<CourseRow>('/api/v1/admin/courses', {
        code: form.code,
        name: form.name,
        semester: semesterPreview.value || undefined,
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

// ---- CR-026 授权学生：班级名单之外的课程级签到授权 ----
// 后端语义：发起签到时授权学生并入应到快照（追加口径）；已存续会话不追溯。
const grantDialog = reactive({ open: false, course: null as CourseRow | null })
const GRANT_PAGE_SIZE = 10
const grantLoading = ref(false)
const grants = ref<CourseGrantRow[]>([])
const grantTotal = ref(0)
const grantPage = ref(1)
// 「选择班级 → 选学生」两级联动：classId 只用于筛选学生，不随授权提交
const grantClasses = ref<ClassRow[]>([])
const grantClassId = ref<number | undefined>()
const grantStudents = ref<StudentRow[]>([])
const grantStudentId = ref<number | undefined>()
const studentsLoading = ref(false)
const granting = ref(false)

async function openGrant(row: CourseRow) {
  grantDialog.course = row
  grantDialog.open = true
  grantPage.value = 1
  grantClassId.value = undefined
  grantStudentId.value = undefined
  grantStudents.value = []
  loadGrants()
  loadGrantClasses()
}

async function loadGrants() {
  if (!grantDialog.course) return
  grantLoading.value = true
  try {
    const data = await api.get<{ items: CourseGrantRow[]; total: number }>(
      `/api/v1/admin/courses/${grantDialog.course.id}/grants`,
      { page: grantPage.value, size: GRANT_PAGE_SIZE }
    )
    grants.value = data.items ?? []
    grantTotal.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    grantLoading.value = false
  }
}

async function loadGrantClasses() {
  try {
    const data = await api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', {
      size: 200,
      status: 'ACTIVE'
    })
    grantClasses.value = data.items ?? []
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function onGrantClassChange() {
  grantStudentId.value = undefined
  grantStudents.value = []
  if (!grantClassId.value) return
  studentsLoading.value = true
  try {
    const data = await api.get<{ items: StudentRow[] }>('/api/v1/admin/students', {
      classId: grantClassId.value,
      status: 'ACTIVE',
      size: 200
    })
    grantStudents.value = data.items ?? []
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    studentsLoading.value = false
  }
}

async function addGrant() {
  if (!grantDialog.course || !grantStudentId.value) return
  granting.value = true
  try {
    await api.post<CourseGrantRow>(`/api/v1/admin/courses/${grantDialog.course.id}/grants`, {
      studentId: grantStudentId.value
    })
    ElMessage.success('已授权')
    grantStudentId.value = undefined
    await loadGrants()
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    granting.value = false
  }
}

async function removeGrant(row: CourseGrantRow) {
  if (!grantDialog.course) return
  try {
    await ElMessageBox.confirm(
      `撤销后该学生不能进入「${grantDialog.course.name}」新发起的签到，历史考勤不受影响。确定撤销吗？`,
      '撤销授权',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.del<void>(`/api/v1/admin/courses/${grantDialog.course.id}/grants/${row.studentId}`)
    ElMessage.success('已撤销')
    await loadGrants()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}
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
        <el-table-column label="操作" width="310" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="openGrant(row)">授权学生</el-button>
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
          <div class="sem-picker">
            <el-select v-model="form.semYear" clearable placeholder="学年" style="flex: 1.3">
              <el-option v-for="y in yearOptions" :key="y" :label="`${y}-${y + 1} 学年`" :value="y" />
            </el-select>
            <el-select v-model="form.semHalf" clearable placeholder="上半年 / 下半年" style="flex: 1">
              <el-option label="上半年" :value="1" />
              <el-option label="下半年" :value="2" />
            </el-select>
          </div>
          <div v-if="semesterPreview" class="field-tip">将保存为：{{ semesterPreview }}（-1 上半年、-2 下半年）</div>
          <div v-else-if="semesterLegacy" class="field-tip">
            当前值「{{ semesterLegacy }}」不是标准学年格式；保持不选则不修改，重新选择后将覆盖。
          </div>
        </el-form-item>
        <el-form-item v-if="isAdmin" label="开课教师">
          <el-select v-model="form.ownerTeacherId" clearable placeholder="可空" style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.name}（${t.teacherNo}）`" :value="t.id" />
          </el-select>
          <div class="field-tip">
            保存后自动为该教师授予本课程的数据权限（等同「教师与授权 → 授权范围」的授予，教师端立即可见）；撤销请到授权范围中操作
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

    <!-- CR-026 授权学生抽屉 -->
    <el-drawer v-model="grantDialog.open" :title="`授权学生 · ${grantDialog.course?.name ?? ''}`" size="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="班级学生默认可签到；这里给班级之外的散生（跨班选修、转班补授）单独授权，发起签到时会并入应到名单。授权变动只影响之后新发起的签到，已存续会话不受影响。"
        style="margin-bottom: 12px"
      />
      <div class="grant-picker">
        <el-select v-model="grantClassId" placeholder="选择班级" filterable style="flex: 1" @change="onGrantClassChange">
          <el-option v-for="c in grantClasses" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select
          v-model="grantStudentId"
          placeholder="选择学生"
          filterable
          :loading="studentsLoading"
          :disabled="!grantClassId"
          style="flex: 1"
        >
          <el-option v-for="s in grantStudents" :key="s.id" :label="`${s.name}（${s.studentNo}）`" :value="s.id" />
        </el-select>
        <el-button type="primary" :disabled="!grantStudentId" :loading="granting" @click="addGrant">授权</el-button>
      </div>

      <el-table :data="grants" v-loading="grantLoading" size="small">
        <el-table-column prop="studentName" label="学生" width="90">
          <template #default="{ row }">{{ row.studentName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="studentNo" label="学号" width="110" />
        <el-table-column prop="className" label="所在班级" min-width="120">
          <template #default="{ row }">{{ row.className || '—' }}</template>
        </el-table-column>
        <el-table-column label="授权时间" width="100">
          <template #default="{ row }">{{ (row.createdAt || '').slice(0, 10) }}</template>
        </el-table-column>
        <el-table-column label="" width="70">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeGrant(row)">移除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有授权学生" :image-size="60" />
        </template>
      </el-table>
      <el-pagination
        v-model:current-page="grantPage"
        :page-size="GRANT_PAGE_SIZE"
        :total="grantTotal"
        layout="total, prev, pager, next"
        style="margin-top: 10px; justify-content: flex-end"
        @current-change="loadGrants"
      />
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
.field-tip {
  width: 100%;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  padding-top: 2px;
}
.sem-picker {
  display: flex;
  gap: 8px;
  width: 100%;
}
.grant-picker {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
:deep(.hl) {
  background: #ecf5ff !important;
}
</style>
