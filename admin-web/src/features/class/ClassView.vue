<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import { auth, isAdmin } from '@/shared/store/auth'
import type { ClassRow, TeacherRow } from '@/shared/api/types'

const router = useRouter()
const loading = ref(false)
const rows = ref<ClassRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({ keyword: '', grade: '', status: 'ACTIVE', showArchived: false })

const dialog = reactive({ open: false, editing: null as ClassRow | null })
const form = reactive({
  code: '',
  name: '',
  grade: '',
  major: '',
  ownerTeacherId: undefined as number | undefined,
  remark: ''
})
const teachers = ref<TeacherRow[]>([])
const saving = ref(false)
const codeError = ref('')
const highlightId = ref<number | null>(null)

/** 行内高亮：把类型标出来，否则模板里的隐式 any 过不了 vue-tsc */
function rowClass({ row }: { row: ClassRow }) {
  return row.id === highlightId.value ? 'hl' : ''
}

const title = computed(() => (dialog.editing ? '编辑班级' : '新建班级'))

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: ClassRow[]; total: number }>('/api/v1/admin/classes', {
      page: page.value,
      size: size.value,
      keyword: query.keyword,
      grade: query.grade,
      // 空串会被 buildUrl 过滤成「没传」，后端就回落默认 ACTIVE —— 开关会失效
      status: query.showArchived ? 'ALL' : query.status
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
    // 教师列表拿不到不影响建班，只是不能选负责教师
  }
}

function openCreate() {
  dialog.editing = null
  Object.assign(form, { code: '', name: '', grade: '', major: '', ownerTeacherId: undefined, remark: '' })
  codeError.value = ''
  dialog.open = true
}

function openEdit(row: ClassRow) {
  dialog.editing = row
  Object.assign(form, {
    code: row.code,
    name: row.name,
    grade: row.grade ?? '',
    major: row.major ?? '',
    ownerTeacherId: row.ownerTeacherId,
    remark: row.remark ?? ''
  })
  codeError.value = ''
  dialog.open = true
}

/** 唯一性在失焦时预检，别等提交才报错（§8.4.3）。 */
async function checkCode() {
  if (!form.code || dialog.editing) return
  if (!/^[A-Za-z0-9]{4,32}$/.test(form.code)) {
    codeError.value = '班级码须为 4~32 位字母或数字'
    return
  }
  try {
    const data = await api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', {
      keyword: form.code,
      size: 10
    })
    codeError.value = (data.items ?? []).some((c) => c.code === form.code) ? '班级码已存在' : ''
  } catch {
    codeError.value = ''
  }
}

async function save() {
  if (codeError.value) return
  if (!form.name) {
    ElMessage.warning('请填写班级名称')
    return
  }
  saving.value = true
  try {
    if (dialog.editing) {
      await api.put(`/api/v1/admin/classes/${dialog.editing.id}`, {
        name: form.name,
        grade: form.grade || undefined,
        major: form.major || undefined,
        ownerTeacherId: form.ownerTeacherId ?? null,
        remark: form.remark || undefined
      })
      highlightId.value = dialog.editing.id
    } else {
      const created = await api.post<ClassRow>('/api/v1/admin/classes', {
        code: form.code,
        name: form.name,
        grade: form.grade || undefined,
        major: form.major || undefined,
        ownerTeacherId: form.ownerTeacherId ?? undefined,
        remark: form.remark || undefined
      })
      highlightId.value = created.id
    }
    dialog.open = false
    await load()
    // 行内高亮 2 秒，避免连续录入时反复弹"保存成功"
    setTimeout(() => (highlightId.value = null), 2000)
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    saving.value = false
  }
}

async function remove(row: ClassRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」吗？班内仍有在读学生时会被拒绝。`,
      '删除班级',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const result = await api.del<{ id: number; archived: boolean }>(`/api/v1/admin/classes/${row.id}`)
    ElMessage.success(result.archived ? '该班级已被会话引用，已转为归档' : '已删除')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function archive(row: ClassRow) {
  try {
    await ElMessageBox.confirm(
      '归档后不能再用于发起签到，历史记录仍可查看。确定归档吗？',
      '归档班级',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await api.put(`/api/v1/admin/classes/${row.id}`, { status: 'ARCHIVED' })
    ElMessage.success('已归档')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

async function copyCode(row: ClassRow) {
  try {
    await navigator.clipboard.writeText(row.code)
    ElMessage.success(`已复制邀请码 ${row.code}`)
  } catch {
    ElMessage.warning(`邀请码：${row.code}`)
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
      <h2>班级管理</h2>
      <el-button type="primary" @click="openCreate">新建班级</el-button>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-input v-model="query.keyword" placeholder="名称 / 班级码" clearable style="width: 200px" @keyup.enter="load" />
        <el-input v-model="query.grade" placeholder="年级" clearable style="width: 120px" />
        <el-switch v-model="query.showArchived" active-text="显示已归档" @change="load" />
        <el-button @click="load">查询</el-button>
        <span class="hint">班级与课程是两个独立主数据，本页不含任何课程字段。</span>
      </div>

      <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
        <el-table-column prop="code" label="班级码" width="120" />
        <el-table-column prop="name" label="班级名称" min-width="180" />
        <el-table-column prop="grade" label="年级" width="90" />
        <el-table-column prop="major" label="专业" width="150" />
        <el-table-column prop="ownerTeacherName" label="负责教师" width="110">
          <template #default="{ row }">{{ row.ownerTeacherName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="studentCount" label="学生数" width="90" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '在读' : '已归档' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170">
          <template #default="{ row }">{{ (row.createdAt || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="router.push(`/students?classId=${row.id}`)">查看学生</el-button>
            <el-button link type="primary" @click="copyCode(row)">复制邀请码</el-button>
            <el-button v-if="row.status === 'ACTIVE'" link type="warning" @click="archive(row)">归档</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有班级，先创建一个班级，再导入学生">
            <el-button type="primary" @click="openCreate">新建班级</el-button>
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
        <el-form-item label="班级码" :error="codeError">
          <!-- 创建后不可改：学生用它激活，改了会让已发的邀请码全部失效 -->
          <el-input v-model="form.code" :disabled="!!dialog.editing" @blur="checkCode" />
        </el-form-item>
        <el-form-item label="班级名称">
          <el-input v-model="form.name" maxlength="120" />
        </el-form-item>
        <el-form-item label="年级">
          <el-input v-model="form.grade" maxlength="16" placeholder="如 2024" />
        </el-form-item>
        <el-form-item label="专业">
          <el-input v-model="form.major" maxlength="120" />
        </el-form-item>
        <el-form-item v-if="isAdmin" label="负责教师">
          <el-select v-model="form.ownerTeacherId" clearable placeholder="可空" style="width: 100%">
            <el-option v-for="t in teachers" :key="t.id" :label="`${t.name}（${t.teacherNo}）`" :value="t.id" />
          </el-select>
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
:deep(.hl) {
  background: #ecf5ff !important;
  transition: background 0.4s;
}
</style>
