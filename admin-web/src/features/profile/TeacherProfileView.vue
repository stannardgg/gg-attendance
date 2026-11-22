<script setup lang="ts">
/**
 * 我的资料（教师端）。
 *
 * 教师改不了 teacher/account：姓名、院系、职称要经管理员审核才生效（CR-019），
 * 登录用户名与密码也走同一条申请链（CR-022）。这里只做三件事——看当前资料、
 * 提交申请、撤回申请。
 *
 * 改用户名或改密码必须先输入当前密码：教室里经常是教师本人开着电脑，
 * 不能让路过的人顺手把登录名换掉。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import { auth } from '@/shared/store/auth'
import type { ProfileRequestRow, TeacherRow } from '@/shared/api/types'

const loading = ref(false)
const profile = ref<TeacherRow | null>(null)
const rows = ref<ProfileRequestRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

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

const pending = computed(() => rows.value.find((r) => r.status === 'PENDING'))

async function loadProfile() {
  loading.value = true
  try {
    profile.value = await api.get<TeacherRow>('/api/v1/teacher/profile')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

async function loadRequests() {
  try {
    const data = await api.get<{ items: ProfileRequestRow[]; total: number }>(
      '/api/v1/teacher/profile-requests',
      { page: page.value, size: size.value }
    )
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

/* ---------------- 提交申请 ---------------- */

const dlg = reactive({
  open: false,
  username: '',
  name: '',
  department: '',
  title: '',
  reason: '',
  changePassword: false,
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
  saving: false
})

function openSubmit() {
  if (!profile.value) return
  dlg.username = profile.value.teacherNo ?? ''
  dlg.name = profile.value.name ?? ''
  dlg.department = profile.value.department ?? ''
  dlg.title = profile.value.title ?? ''
  dlg.reason = ''
  dlg.changePassword = false
  dlg.currentPassword = ''
  dlg.newPassword = ''
  dlg.confirmPassword = ''
  dlg.open = true
}

async function submit() {
  if (!profile.value) return
  if (!dlg.name.trim()) {
    ElMessage.warning('姓名不能为空')
    return
  }
  const username = dlg.username.trim()
  if (!username) {
    ElMessage.warning('登录用户名不能为空')
    return
  }
  if (/\s/.test(username)) {
    ElMessage.warning('登录用户名不能包含空格')
    return
  }

  const usernameChanged = username !== (profile.value.teacherNo ?? '')
  const accountChange = usernameChanged || dlg.changePassword

  if (accountChange && !dlg.currentPassword) {
    ElMessage.warning('修改登录用户名或密码需要先输入当前密码')
    return
  }
  if (dlg.changePassword) {
    if (dlg.newPassword.length < 8) {
      ElMessage.warning('新密码至少 8 位')
      return
    }
    if (!/[A-Za-z]/.test(dlg.newPassword) || !/\d/.test(dlg.newPassword)) {
      ElMessage.warning('新密码须同时包含字母与数字')
      return
    }
    if (dlg.newPassword !== dlg.confirmPassword) {
      ElMessage.warning('两次输入的新密码不一致')
      return
    }
  }

  const payload: Record<string, unknown> = {}
  if (usernameChanged) payload.username = username
  if (dlg.name.trim() !== (profile.value.name ?? '')) payload.name = dlg.name.trim()
  // 空串 = 清空院系/职称（后端约定），null 表示"本次不改"
  if ((dlg.department || '') !== (profile.value.department ?? '')) payload.department = dlg.department || ''
  if ((dlg.title || '') !== (profile.value.title ?? '')) payload.title = dlg.title || ''
  if (dlg.changePassword) payload.newPassword = dlg.newPassword
  if (accountChange) payload.currentPassword = dlg.currentPassword

  if (Object.keys(payload).length === 0) {
    ElMessage.warning('没有修改任何内容，无需提交申请')
    return
  }
  if (dlg.reason.trim()) payload.reason = dlg.reason.trim()

  dlg.saving = true
  try {
    await api.post('/api/v1/teacher/profile-requests', payload)
    dlg.open = false
    await loadRequests()
    ElMessage.success('已提交，等待管理员审核')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    dlg.saving = false
  }
}

async function withdraw(row: ProfileRequestRow) {
  try {
    await ElMessageBox.confirm('撤回这条申请吗？撤回后可以重新提交。', '撤回申请', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.post(`/api/v1/teacher/profile-requests/${row.id}/withdraw`, {})
    await loadRequests()
    ElMessage.success('已撤回')
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

/* ---------------- 展示辅助 ---------------- */

interface DiffLine {
  label: string
  from: string
  to: string
}

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

onMounted(async () => {
  await Promise.all([loadProfile(), loadRequests()])
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>我的资料</h2>
      <el-button type="primary" :disabled="!!pending" @click="openSubmit">
        {{ pending ? '有待审核的申请' : '申请修改' }}
      </el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="登录用户名">
          {{ profile?.teacherNo || auth.profile?.username || '—' }}
          <span class="hint">（登录用，可申请变更）</span>
        </el-descriptions-item>
        <el-descriptions-item label="姓名">{{ profile?.name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="院系">{{ profile?.department || '—' }}</el-descriptions-item>
        <el-descriptions-item label="职称">{{ profile?.title || '—' }}</el-descriptions-item>
      </el-descriptions>
      <p class="hint" style="margin: 12px 0 0">
        姓名、院系、职称、登录用户名与密码都由管理员审核后生效；提交申请时只会上报改动过的项。
        用户名或密码通过审核后，已登录的会话会立即失效，需要用新凭据重新登录。
      </p>
    </el-card>

    <el-card shadow="never" style="margin-top: 12px">
      <div class="section-title">我的申请记录</div>
      <el-table :data="rows">
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
        <el-table-column prop="reason" label="说明" min-width="140">
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
        <el-table-column label="审核意见" min-width="180">
          <template #default="{ row }">
            <div>{{ row.reviewNote || '—' }}</div>
            <div v-if="row.reviewedAt" class="hint">{{ shortTime(row.reviewedAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="danger" @click="withdraw(row)">撤回</el-button>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="还没有提交过修改申请" />
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        :page-size="size"
        :total="total"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadRequests"
      />
    </el-card>

    <el-dialog v-model="dlg.open" title="申请修改" width="500px">
      <el-form label-width="100px">
        <el-divider content-position="left">资料</el-divider>
        <el-form-item label="登录用户名">
          <el-input v-model="dlg.username" maxlength="32" placeholder="登录用，不能含空格" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="dlg.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="院系">
          <el-input v-model="dlg.department" maxlength="120" placeholder="留空表示清空该字段" />
        </el-form-item>
        <el-form-item label="职称">
          <el-input v-model="dlg.title" maxlength="64" placeholder="留空表示清空该字段" />
        </el-form-item>

        <el-divider content-position="left">账号安全</el-divider>
        <el-form-item label="修改密码">
          <el-switch v-model="dlg.changePassword" />
          <span class="hint" style="margin-left: 8px">通过审核后生效</span>
        </el-form-item>
        <template v-if="dlg.changePassword">
          <el-form-item label="新密码">
            <el-input v-model="dlg.newPassword" type="password" show-password maxlength="64"
              placeholder="至少 8 位，须同时包含字母与数字" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="dlg.confirmPassword" type="password" show-password maxlength="64" />
          </el-form-item>
        </template>
        <el-form-item v-if="dlg.changePassword || dlg.username.trim() !== (profile?.teacherNo ?? '')" label="当前密码">
          <el-input v-model="dlg.currentPassword" type="password" show-password maxlength="64"
            placeholder="修改用户名或密码时必填，用于确认本人操作" />
        </el-form-item>

        <el-form-item label="说明">
          <el-input v-model="dlg.reason" type="textarea" :rows="3" maxlength="500" show-word-limit
            placeholder="例如：职称晋升、工号调整，便于管理员核对" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg.open = false">取消</el-button>
        <el-button type="primary" :loading="dlg.saving" @click="submit">提交申请</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hint {
  font-size: 12px;
  color: #909399;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 10px;
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
  min-width: 42px;
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
</style>
