<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { ClassRow, CourseRow, ScopeRow, TeacherRow } from '@/shared/api/types'

const loading = ref(false)
const rows = ref<TeacherRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({ keyword: '' })

/* ---------------- 列表 ---------------- */

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: TeacherRow[]; total: number }>('/api/v1/admin/teachers', {
      page: page.value,
      size: size.value,
      keyword: query.keyword
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

/* ---------------- 新建 / 编辑 ---------------- */

const dialog = reactive({ open: false, editing: null as TeacherRow | null })
const form = reactive({
  teacherNo: '',
  name: '',
  password: '',
  department: '',
  title: '',
  status: 'ACTIVE' as 'ACTIVE' | 'DISABLED'
})
const saving = ref(false)
const title = computed(() => (dialog.editing ? '编辑教师' : '新建教师'))

function openCreate() {
  dialog.editing = null
  Object.assign(form, { teacherNo: '', name: '', password: '', department: '', title: '', status: 'ACTIVE' })
  dialog.open = true
}

function openEdit(row: TeacherRow) {
  dialog.editing = row
  Object.assign(form, {
    teacherNo: row.teacherNo,
    name: row.name,
    password: '',
    department: row.department ?? '',
    title: row.title ?? '',
    status: (row.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE') as 'ACTIVE' | 'DISABLED'
  })
  dialog.open = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  saving.value = true
  try {
    if (dialog.editing) {
      const patch: Record<string, unknown> = { name: form.name }
      if ((form.department || '') !== (dialog.editing.department ?? '')) patch.department = form.department || null
      if ((form.title || '') !== (dialog.editing.title ?? '')) patch.title = form.title || null
      if (form.status !== dialog.editing.status) patch.status = form.status
      await api.put(`/api/v1/admin/teachers/${dialog.editing.id}`, patch)
    } else {
      if (!form.teacherNo.trim()) {
        ElMessage.warning('请填写工号')
        saving.value = false
        return
      }
      // 契约要求密码 8~64 位；这里先挡一次，别等 400 回来才提示
      if (form.password.length < 8) {
        ElMessage.warning('初始密码至少 8 位')
        saving.value = false
        return
      }
      await api.post('/api/v1/admin/teachers', {
        teacherNo: form.teacherNo.trim(),
        name: form.name.trim(),
        password: form.password,
        department: form.department || null,
        title: form.title || null
      })
    }
    dialog.open = false
    await load()
    ElMessage.success('已保存')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    saving.value = false
  }
}

async function remove(row: TeacherRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」吗？已授权 ${row.classCount} 个班级、${row.courseCount} 门课程，删除后这些授权一并失效。`,
      '删除教师',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.del(`/api/v1/admin/teachers/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

/* ---------------- 授权范围 ---------------- */

const drawer = reactive({ open: false, teacher: null as TeacherRow | null })
const scopes = ref<ScopeRow[]>([])
const scopeLoading = ref(false)
const classes = ref<ClassRow[]>([])
const courses = ref<CourseRow[]>([])
const newScope = reactive({ scopeType: 'CLASS' as 'CLASS' | 'COURSE', scopeId: undefined as number | undefined })

const options = computed(() =>
  newScope.scopeType === 'CLASS'
    ? classes.value.map((c) => ({ id: c.id, label: `${c.name}（${c.code}）` }))
    : courses.value.map((c) => ({ id: c.id, label: `${c.name}（${c.code}）` }))
)

async function openScopes(row: TeacherRow) {
  drawer.teacher = row
  drawer.open = true
  newScope.scopeType = 'CLASS'
  newScope.scopeId = undefined
  await Promise.all([loadScopes(), ensureOrgData()])
}

async function loadScopes() {
  if (!drawer.teacher) return
  scopeLoading.value = true
  try {
    const list = await api.get<ScopeRow[]>('/api/v1/admin/teacher-scopes', { teacherId: drawer.teacher.id })
    scopes.value = list ?? []
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    scopeLoading.value = false
  }
}

async function ensureOrgData() {
  if (classes.value.length && courses.value.length) return
  try {
    const [cls, crs] = await Promise.all([
      api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', { size: 200, status: 'ACTIVE' }),
      api.get<{ items: CourseRow[] }>('/api/v1/admin/courses', { size: 200, status: 'ACTIVE' })
    ])
    classes.value = cls.items ?? []
    courses.value = crs.items ?? []
  } catch {
    // 拿不到组织数据时，下拉为空但已有授权仍可撤销
  }
}

async function addScope() {
  if (!drawer.teacher || !newScope.scopeId) {
    ElMessage.warning('请选择要授权的班级或课程')
    return
  }
  try {
    await api.post('/api/v1/admin/teacher-scopes', {
      teacherId: drawer.teacher.id,
      scopeType: newScope.scopeType,
      scopeId: newScope.scopeId
    })
    newScope.scopeId = undefined
    await loadScopes()
    await load()
    ElMessage.success('已授权')
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function removeScope(row: ScopeRow) {
  try {
    await ElMessageBox.confirm(
      `撤销「${row.teacherName || ''}」对「${row.scopeName || row.scopeId}」的授权吗？撤销后该教师立即看不到这部分数据。`,
      '撤销授权',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.del(`/api/v1/admin/teacher-scopes/${row.id}`)
    await loadScopes()
    await load()
    ElMessage.success('已撤销')
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>教师与授权</h2>
      <el-button type="primary" @click="openCreate">新建教师</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-input v-model="query.keyword" placeholder="姓名 / 工号" clearable style="width: 200px" @keyup.enter="load" />
        <el-button @click="load">查询</el-button>
        <span class="hint">新建教师的角色恒为 TEACHER，本页不提供角色字段（§8.2）。</span>
      </div>

      <el-table :data="rows" v-loading="loading">
        <el-table-column prop="teacherNo" label="工号" width="130" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="department" label="院系" min-width="150">
          <template #default="{ row }">{{ row.department || '—' }}</template>
        </el-table-column>
        <el-table-column prop="title" label="职称" width="110">
          <template #default="{ row }">{{ row.title || '—' }}</template>
        </el-table-column>
        <el-table-column prop="classCount" label="授权班级" width="100" />
        <el-table-column prop="courseCount" label="授权课程" width="100" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openScopes(row)">授权范围</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有教师，先建一个教师账号" />
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
        <el-form-item label="工号">
          <!-- 工号是登录账号，创建后不改 -->
          <el-input v-model="form.teacherNo" :disabled="!!dialog.editing" maxlength="32" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="!dialog.editing" label="初始密码">
          <el-input v-model="form.password" type="password" show-password maxlength="64"
            placeholder="至少 8 位，由教师首次登录后自行修改" />
        </el-form-item>
        <el-form-item label="院系">
          <el-input v-model="form.department" maxlength="120" />
        </el-form-item>
        <el-form-item label="职称">
          <el-input v-model="form.title" maxlength="64" />
        </el-form-item>
        <el-form-item v-if="dialog.editing" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">正常</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawer.open" :title="`授权范围 · ${drawer.teacher?.name || ''}`" size="560px">
      <div class="scope-add">
        <el-radio-group v-model="newScope.scopeType" @change="newScope.scopeId = undefined">
          <el-radio-button value="CLASS">班级</el-radio-button>
          <el-radio-button value="COURSE">课程</el-radio-button>
        </el-radio-group>
        <el-select v-model="newScope.scopeId" filterable placeholder="选择对象" style="flex: 1">
          <el-option v-for="o in options" :key="o.id" :label="o.label" :value="o.id" />
        </el-select>
        <el-button type="primary" @click="addScope">授权</el-button>
      </div>

      <el-table :data="scopes" v-loading="scopeLoading" size="small">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.scopeType === 'CLASS' ? 'primary' : 'success'">
              {{ row.scopeType === 'CLASS' ? '班级' : '课程' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="scopeName" label="名称" min-width="140">
          <template #default="{ row }">{{ row.scopeName || `#${row.scopeId}` }}</template>
        </el-table-column>
        <el-table-column label="授权时间" width="160">
          <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="removeScope(row)">撤销</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有授权，该教师登录后看不到任何班级与课程" />
        </template>
      </el-table>
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
.hint {
  font-size: 12px;
  color: #909399;
}
.scope-add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
</style>
