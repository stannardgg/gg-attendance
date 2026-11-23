<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError, messageOf } from '@/shared/api/client'
import { auth, loadMe, logout } from '@/shared/store/auth'
import type { ClassRow, CourseRow, ProfileRequestRow, ScopeRow, TeacherRow } from '@/shared/api/types'

const router = useRouter()

const loading = ref(false)
const rows = ref<TeacherRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const query = reactive({ keyword: '' })

/**
 * 403 的两种可能：真没权限，或本页内存里的身份已经过期（例如别的标签页换成了
 * 教师账号，localStorage 的令牌被换掉，而本页还写着"管理员"）。
 * 直接弹"权限不足"对第二种毫无帮助，所以先回服务端确认一次身份，再决定是
 * 留在本页还是送去重新登录。
 */
async function explainDenied(): Promise<void> {
  await loadMe()
  if (auth.profile?.role === 'ADMIN') return
  const who = auth.profile
    ? `${auth.profile.name || auth.profile.username}（${auth.profile.role === 'TEACHER' ? '教师' : '学生'}）`
    : '当前账号'
  ElMessage.error(`${who} 没有教师管理权限，请用管理员账号重新登录`)
  await logout()
  router.replace({ name: 'login', query: { redirect: '/teachers' } })
}

/* ---------------- 教师列表 ---------------- */

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
    if (err instanceof ApiError && err.code === 'FA-1003') {
      await explainDenied()
      return
    }
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
  if (dialog.editing && form.password) {
    if (form.password.length < 8 || !/[A-Za-z]/.test(form.password) || !/\d/.test(form.password)) {
      ElMessage.warning('重置密码需至少 8 位，且同时包含字母与数字')
      return
    }
  }
  saving.value = true
  try {
    if (dialog.editing) {
      const patch: Record<string, unknown> = { name: form.name }
      // 空串 = 清空（后端约定），null 表示"不改"
      if ((form.department || '') !== (dialog.editing.department ?? '')) patch.department = form.department || ''
      if ((form.title || '') !== (dialog.editing.title ?? '')) patch.title = form.title || ''
      if (form.status !== dialog.editing.status) patch.status = form.status
      // 留空 = 不改密码；填写即重置，且该教师已登录的会话会立即失效
      if (form.password) patch.password = form.password
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

/* ---------------- 账号与资料修改申请（CR-019 / CR-022） ---------------- */

const tab = ref<'teachers' | 'requests'>('teachers')
const requests = ref<ProfileRequestRow[]>([])
const reqTotal = ref(0)
const reqPage = ref(1)
const reqSize = ref(10)
const reqLoading = ref(false)
const pendingCount = ref(0)
const reqQuery = reactive({ status: 'PENDING', keyword: '' })

const statusLabel: Record<string, string> = {
  PENDING: '待审核',
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

interface DiffLine {
  label: string
  from: string
  to: string
}

/** 只显示真正申请了的字段；空串是「清空」的约定。密码只显示"申请重置"，看不到也不该看到明文。 */
function diffOf(row: ProfileRequestRow): DiffLine[] {
  const lines: DiffLine[] = []
  const shown = (v?: string | null) => (v === null || v === undefined || v === '' ? '—' : v)
  const target = (v?: string | null) => (v === '' ? '（清空）' : (v ?? ''))
  if (row.newUsername !== null && row.newUsername !== undefined) {
    lines.push({ label: '用户名', from: shown(row.oldUsername), to: target(row.newUsername) })
  }
  if (row.newName !== null && row.newName !== undefined) {
    lines.push({ label: '姓名', from: shown(row.oldName), to: target(row.newName) })
  }
  if (row.newDepartment !== null && row.newDepartment !== undefined) {
    lines.push({ label: '院系', from: shown(row.oldDepartment), to: target(row.newDepartment) })
  }
  if (row.newTitle !== null && row.newTitle !== undefined) {
    lines.push({ label: '职称', from: shown(row.oldTitle), to: target(row.newTitle) })
  }
  if (row.passwordChange) {
    lines.push({ label: '登录密码', from: '••••••', to: '（申请重置）' })
  }
  return lines
}

function shortTime(value?: string): string {
  return (value || '').replace('T', ' ').slice(0, 16)
}

async function loadRequests() {
  reqLoading.value = true
  try {
    const data = await api.get<{ items: ProfileRequestRow[]; total: number }>(
      '/api/v1/admin/teacher-profile-requests',
      {
        page: reqPage.value,
        size: reqSize.value,
        // "全部"用空串：client 的 buildUrl 会过滤空值
        status: reqQuery.status === 'ALL' ? '' : reqQuery.status,
        keyword: reqQuery.keyword
      }
    )
    requests.value = data.items ?? []
    reqTotal.value = data.total ?? 0
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-1003') {
      await explainDenied()
      return
    }
    ElMessage.error(messageOf(err))
  } finally {
    reqLoading.value = false
  }
}

/** 标签上的待审数量：单独查一次 total，不跟当前筛选混在一起。 */
async function loadPendingCount() {
  try {
    const data = await api.get<{ total: number }>('/api/v1/admin/teacher-profile-requests', {
      page: 1,
      size: 1,
      status: 'PENDING'
    })
    pendingCount.value = data.total ?? 0
  } catch {
    // 红点缺失比整页报错好
  }
}

function onTabChange(name: string | number) {
  if (name === 'requests' && requests.value.length === 0) {
    void loadRequests()
  }
}

const handleDlg = reactive({
  open: false,
  row: null as ProfileRequestRow | null,
  approved: true,
  note: '',
  saving: false
})

function openHandle(row: ProfileRequestRow) {
  handleDlg.row = row
  handleDlg.approved = true
  handleDlg.note = ''
  handleDlg.open = true
}

async function submitHandle() {
  if (!handleDlg.row) return
  if (!handleDlg.approved && !handleDlg.note.trim()) {
    ElMessage.warning('驳回时要写清理由，教师端会看到这段话')
    return
  }
  handleDlg.saving = true
  try {
    await api.post(`/api/v1/admin/teacher-profile-requests/${handleDlg.row.id}/handle`, {
      approved: handleDlg.approved,
      note: handleDlg.note.trim() || null
    })
    handleDlg.open = false
    ElMessage.success(handleDlg.approved ? '已通过，修改已生效' : '已驳回')
    await Promise.all([loadRequests(), loadPendingCount(), load()])
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    handleDlg.saving = false
  }
}

onMounted(async () => {
  await load()
  await loadPendingCount()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>教师与授权</h2>
      <el-button v-if="tab === 'teachers'" type="primary" @click="openCreate">新建教师</el-button>
    </div>

    <el-card shadow="never">
      <el-tabs v-model="tab" @tab-change="onTabChange">
        <el-tab-pane name="teachers" label="教师列表">
          <div class="filters">
            <el-input v-model="query.keyword" placeholder="姓名 / 工号" clearable style="width: 200px" @keyup.enter="load" />
            <el-button @click="load">查询</el-button>
            <span class="hint">新建教师的角色恒为 TEACHER，本页不提供角色字段（§8.2）。</span>
          </div>

          <el-table :data="rows" v-loading="loading">
            <el-table-column prop="teacherNo" label="登录用户名" width="140" />
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
        </el-tab-pane>

        <el-tab-pane name="requests">
          <template #label>
            <span>账号与资料申请</span>
            <el-badge
              v-if="pendingCount"
              :value="pendingCount"
              :max="99"
              type="danger"
              style="margin-left: 6px; vertical-align: 1px"
            />
          </template>

          <div class="filters">
            <el-select v-model="reqQuery.status" style="width: 140px" @change="reqPage = 1; loadRequests()">
              <el-option label="待审核" value="PENDING" />
              <el-option label="已通过" value="APPROVED" />
              <el-option label="已驳回" value="REJECTED" />
              <el-option label="已撤回" value="WITHDRAWN" />
              <el-option label="全部" value="ALL" />
            </el-select>
            <el-input
              v-model="reqQuery.keyword"
              placeholder="教师姓名 / 工号"
              clearable
              style="width: 200px"
              @keyup.enter="reqPage = 1; loadRequests()"
            />
            <el-button @click="reqPage = 1; loadRequests()">查询</el-button>
            <span class="hint">教师可申请改姓名 / 院系 / 职称 / 登录用户名 / 密码；通过审核才生效。</span>
          </div>

          <el-table :data="requests" v-loading="reqLoading">
            <el-table-column label="教师" width="150">
              <template #default="{ row }">
                <div>{{ row.teacherName || '—' }}</div>
                <div class="sub">{{ row.teacherNo || '' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="申请变更" min-width="260">
              <template #default="{ row }">
                <div v-for="line in diffOf(row)" :key="line.label" class="diff">
                  <span class="diff-label">{{ line.label }}</span>
                  <span class="diff-from">{{ line.from }}</span>
                  <span class="diff-arrow">→</span>
                  <span class="diff-to">{{ line.to }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="reason" label="申请说明" min-width="160">
              <template #default="{ row }">{{ row.reason || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType[row.status]">{{ statusLabel[row.status] || row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="提交时间" width="150">
              <template #default="{ row }">{{ shortTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="审核意见" min-width="160">
              <template #default="{ row }">
                <div>{{ row.reviewNote || '—' }}</div>
                <div v-if="row.reviewerUsername" class="sub">{{ row.reviewerUsername }} · {{ shortTime(row.reviewedAt) }}</div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'PENDING'" link type="primary" @click="openHandle(row)">审核</el-button>
                <span v-else class="sub">—</span>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="没有待处理的修改申请" />
            </template>
          </el-table>

          <el-pagination
            v-model:current-page="reqPage"
            :page-size="reqSize"
            :total="reqTotal"
            layout="total, prev, pager, next"
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadRequests"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="dialog.open" :title="title" width="460px">
      <el-form label-width="90px">
        <el-form-item label="工号">
          <!-- 工号即登录名，创建后改动要留下"谁申请、谁批准"的痕迹 -->
          <el-input v-model="form.teacherNo" :disabled="!!dialog.editing" maxlength="32" />
          <div v-if="dialog.editing" class="sub">
            工号即登录用户名，如需变更请让教师在「我的资料」提交申请，审核后生效。
          </div>
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
        <el-form-item v-if="dialog.editing" label="重置密码">
          <el-input v-model="form.password" type="password" show-password maxlength="64"
            placeholder="留空表示不改；教师忘记密码时用这里重置" />
          <div class="sub">填写后该教师所有已登录会话立即失效，需用新密码重新登录。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="handleDlg.open" title="审核修改申请" width="520px">
      <div class="review-head">
        <strong>{{ handleDlg.row?.teacherName }}</strong>
        <span class="sub">（{{ handleDlg.row?.teacherNo }}）</span>
      </div>
      <div class="review-diff">
        <div v-for="line in handleDlg.row ? diffOf(handleDlg.row) : []" :key="line.label" class="diff">
          <span class="diff-label">{{ line.label }}</span>
          <span class="diff-from">{{ line.from }}</span>
          <span class="diff-arrow">→</span>
          <span class="diff-to">{{ line.to }}</span>
        </div>
        <div v-if="handleDlg.row?.reason" class="review-reason">申请说明：{{ handleDlg.row.reason }}</div>
      </div>
      <el-alert
        v-if="handleDlg.row?.passwordChange || handleDlg.row?.newUsername"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 10px"
        title="通过后该教师的登录凭据立即变更，已登录的会话会失效，需要用新凭据重新登录。"
      />
      <el-form label-width="80px" style="margin-top: 12px">
        <el-form-item label="结论">
          <el-radio-group v-model="handleDlg.approved">
            <el-radio :value="true">通过</el-radio>
            <el-radio :value="false">驳回</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="handleDlg.approved ? '备注' : '理由'">
          <el-input
            v-model="handleDlg.note"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="handleDlg.approved ? '可选，教师端可见' : '必填，教师端会看到这段话'"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleDlg.open = false">取消</el-button>
        <el-button type="primary" :loading="handleDlg.saving" @click="submitHandle">
          {{ handleDlg.approved ? '通过并生效' : '驳回' }}
        </el-button>
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
      <p class="hint" style="margin: 8px 0 12px">
        课程的「开课教师」保存时会自动在这里生成授权（CR-027），可在此撤销；班级「负责教师」仅是归属标注。教师看不到数据时，也可在这里手动添加授权。
      </p>

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
.sub {
  font-size: 12px;
  color: #909399;
}
.scope-add {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.diff {
  display: flex;
  align-items: baseline;
  gap: 6px;
  font-size: 13px;
  line-height: 1.7;
}
.diff-label {
  color: #909399;
  min-width: 32px;
}
.diff-from {
  color: #909399;
  text-decoration: line-through;
}
.diff-arrow {
  color: #c0c4cc;
}
.diff-to {
  color: #1f2d3d;
  font-weight: 500;
}
.review-head {
  margin-bottom: 8px;
}
.review-diff {
  background: #f7f8fa;
  border-radius: 6px;
  padding: 10px 12px;
}
.review-reason {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}
</style>
