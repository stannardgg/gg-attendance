<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf, request } from '@/shared/api/client'
import type { ClassRow, FaceStatusDetail, ImportResult, StudentRow } from '@/shared/api/types'

const route = useRoute()
const loading = ref(false)
const rows = ref<StudentRow[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const classes = ref<ClassRow[]>([])
const query = reactive({
  classId: undefined as number | undefined,
  keyword: '',
  status: ''
})

const gradeLabel = computed(() => {
  const c = classes.value.find((x) => x.id === query.classId)
  return c ? `${c.name}（${c.code}）` : ''
})

/* ---------------- 列表 ---------------- */

async function load() {
  loading.value = true
  try {
    const data = await api.get<{ items: StudentRow[]; total: number }>('/api/v1/admin/students', {
      page: page.value,
      size: size.value,
      classId: query.classId,
      keyword: query.keyword,
      status: query.status
    })
    rows.value = data.items ?? []
    total.value = data.total ?? 0
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

async function loadClasses() {
  try {
    const data = await api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', { size: 200, status: 'ACTIVE' })
    classes.value = data.items ?? []
  } catch {
    classes.value = []
  }
}

/* ---------------- 新建 / 编辑 ---------------- */

const dialog = reactive({ open: false, editing: null as StudentRow | null })
const form = reactive({ studentNo: '', name: '', classId: undefined as number | undefined })
const saving = ref(false)
const highlightId = ref<number | null>(null)

/** 行内高亮：把类型标出来，否则模板里的隐式 any 过不了 vue-tsc */
function rowClass({ row }: { row: StudentRow }) {
  return row.id === highlightId.value ? 'hl' : ''
}

const title = computed(() => (dialog.editing ? '编辑学生' : '新建学生'))

function openCreate() {
  dialog.editing = null
  Object.assign(form, { studentNo: '', name: '', classId: query.classId })
  dialog.open = true
}

function openEdit(row: StudentRow) {
  dialog.editing = row
  Object.assign(form, { studentNo: row.studentNo, name: row.name, classId: row.classId })
  dialog.open = true
}

async function save() {
  if (!form.studentNo || !form.name || !form.classId) {
    ElMessage.warning('学号、姓名、班级都要填')
    return
  }
  saving.value = true
  try {
    if (dialog.editing) {
      // 只提交改动过的字段，避免把未变化的 classId 当成"转班"写一条审计
      const patch: Record<string, unknown> = {}
      if (form.name !== dialog.editing.name) patch.name = form.name
      if (form.classId !== dialog.editing.classId) patch.classId = form.classId
      await api.put(`/api/v1/admin/students/${dialog.editing.id}`, patch)
      highlightId.value = dialog.editing.id
    } else {
      const created = await api.post<StudentRow>('/api/v1/admin/students', {
        studentNo: form.studentNo,
        name: form.name,
        classId: form.classId
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

async function remove(row: StudentRow) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.name}」吗？已有考勤记录的学生会转为归档（ARCHIVED），历史记录保留。`,
      '删除学生',
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    const result = await api.del<{ id: number; archived: boolean }>(`/api/v1/admin/students/${row.id}`)
    ElMessage.success(result.archived ? '该学生已有考勤记录，已转为归档' : '已删除')
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

/* ---------------- CSV 导入 ---------------- */

const importer = reactive({ open: false, classCode: '', fileName: '', uploading: false })
const importResult = ref<ImportResult | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
let pickedFile: File | null = null

function openImport() {
  importer.classCode = classes.value.find((c) => c.id === query.classId)?.code ?? ''
  importer.fileName = ''
  importResult.value = null
  pickedFile = null
  importer.open = true
}

function onPick(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] ?? null
  pickedFile = file
  importer.fileName = file?.name ?? ''
  importResult.value = null
}

/** 模板就在浏览器里生成，不依赖服务端静态资源。 */
function downloadTemplate() {
  const bom = '\uFEFF'
  const csv = `${bom}studentNo,name\n2024010101,张三\n2024010102,李四\n`
  const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url
  a.download = 'students-template.csv'
  a.click()
  URL.revokeObjectURL(url)
}

async function doImport() {
  if (!pickedFile) {
    ElMessage.warning('请选择 CSV 文件')
    return
  }
  if (!importer.classCode) {
    ElMessage.warning('请填写班级邀请码')
    return
  }
  importer.uploading = true
  try {
    const body = new FormData()
    body.append('file', pickedFile)
    body.append('classCode', importer.classCode.trim().toUpperCase())
    // request() 认得 FormData，会自动跳过 JSON 序列化，错误码解析与其它接口一致
    const data = await request<ImportResult>('/api/v1/admin/students/import', {
      method: 'POST',
      body
    })
    importResult.value = data
    await load()
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    importer.uploading = false
  }
}

/* ---------------- 人脸审核 ---------------- */

const face = reactive({ open: false, row: null as StudentRow | null, loading: false })
const faceStatus = ref<FaceStatusDetail | null>(null)
const rejectOpen = ref(false)
const rejectReason = ref('')

const faceLabel: Record<string, string> = {
  NONE: '未采集',
  PENDING_REVIEW: '待审核',
  ACTIVE: '已通过',
  REJECTED: '已驳回'
}
type TagType = 'success' | 'warning' | 'danger' | 'info' | 'primary'
const faceTag: Record<string, TagType> = {
  NONE: 'info',
  PENDING_REVIEW: 'warning',
  ACTIVE: 'success',
  REJECTED: 'danger'
}

function openFace(row: StudentRow) {
  face.row = row
  face.open = true
  // 契约没有「人脸详情」读接口，只有 review 会回 FaceStatus，
  // 所以打开时以列表行的 faceStatus 为准，审核完再用返回体补样本数/驳回原因。
  faceStatus.value = null
}

async function review(approved: boolean, reason?: string) {
  if (!face.row) return
  try {
    const data = await api.post<FaceStatusDetail>(`/api/v1/admin/students/${face.row.id}/face/review`, {
      approved,
      rejectReason: reason
    })
    const current = face.row
    faceStatus.value = data
    // 列表行同步刷新，避免关掉弹窗后仍显示旧状态
    current.faceStatus = data.status
    ElMessage.success(approved ? '已通过，该学生可以刷脸签到' : '已驳回')
    rejectOpen.value = false
    rejectReason.value = ''
  } catch (err) {
    ElMessage.error(messageOf(err))
  }
}

function openReject() {
  rejectReason.value = ''
  rejectOpen.value = true
}

async function confirmReject() {
  if (!rejectReason.value.trim()) {
    ElMessage.warning('驳回必须填写原因，学生会看到这条说明')
    return
  }
  await review(false, rejectReason.value.trim())
}

onMounted(async () => {
  const fromQuery = Number(route.query.classId)
  if (Number.isFinite(fromQuery) && fromQuery > 0) query.classId = fromQuery
  await loadClasses()
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>学生管理<span v-if="gradeLabel" class="sub">{{ gradeLabel }}</span></h2>
      <div>
        <el-button :disabled="!query.classId" @click="openImport">CSV 导入</el-button>
        <el-button type="primary" @click="openCreate">新建学生</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-select
          v-model="query.classId"
          clearable
          filterable
          placeholder="全部班级"
          style="width: 220px"
          @change="load"
        >
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="姓名 / 学号" clearable style="width: 200px" @keyup.enter="load" />
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px" @change="load">
          <el-option label="在读" value="ACTIVE" />
          <el-option label="已转出" value="TRANSFERRED" />
          <el-option label="已归档" value="ARCHIVED" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <span class="hint">人脸仅有「已通过」的学生可以刷脸签到。</span>
      </div>

      <el-table :data="rows" v-loading="loading" :row-class-name="rowClass">
        <el-table-column prop="studentNo" label="学号" width="140" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="className" label="班级" min-width="160" />
        <el-table-column label="账号" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.activated ? 'success' : 'info'">
              {{ row.activated ? '已激活' : '未激活' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="人脸" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="faceTag[row.faceStatus] || 'info'">
              {{ faceLabel[row.faceStatus] || row.faceStatus }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="学籍" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '在读' : row.status === 'TRANSFERRED' ? '已转出' : '已归档' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="primary" @click="openFace(row)">人脸</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="query.classId ? '该班级还没有学生，可用 CSV 批量导入' : '请先选择班级，或用 CSV 批量导入'">
            <el-button v-if="query.classId" @click="openImport">CSV 导入</el-button>
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

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="dialog.open" :title="title" width="440px">
      <el-form label-width="70px">
        <el-form-item label="学号">
          <!-- 学号是唯一键且被考勤快照引用，创建后不允许改 -->
          <el-input v-model="form.studentNo" :disabled="!!dialog.editing" maxlength="32" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="班级">
          <el-select v-model="form.classId" filterable placeholder="选择班级" style="width: 100%">
            <el-option v-for="c in classes" :key="c.id" :label="`${c.name}（${c.code}）`" :value="c.id" />
          </el-select>
        </el-form-item>
        <p v-if="dialog.editing" class="tip">改动班级视为转班，会写一条审计记录。</p>
      </el-form>
      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- CSV 导入 -->
    <el-dialog v-model="importer.open" title="CSV 批量导入学生" width="600px">
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
        表头固定 <code>studentNo,name</code>；逐行校验、已成功的行不回滚，
        失败行会带行号（从 1 起，含表头）返回原因。
      </el-alert>
      <el-form label-width="90px">
        <el-form-item label="班级邀请码">
          <el-input v-model="importer.classCode" placeholder="如 CS2401" maxlength="32" style="width: 200px" />
          <span class="tip" style="margin-left: 10px">CSV 内同名列冲突时以此为准</span>
        </el-form-item>
        <el-form-item label="CSV 文件">
          <input ref="fileInput" type="file" accept=".csv,text/csv" style="display: none" @change="onPick" />
          <el-button @click="fileInput?.click()">选择文件</el-button>
          <span v-if="importer.fileName" class="tip" style="margin-left: 10px">{{ importer.fileName }}</span>
          <el-button link type="primary" style="margin-left: 10px" @click="downloadTemplate">下载模板</el-button>
        </el-form-item>
      </el-form>

      <div v-if="importResult" class="result">
        <div class="result-head">
          <el-tag type="success">成功 {{ importResult.created + importResult.updated }}</el-tag>
          <el-tag v-if="importResult.failed" type="danger">失败 {{ importResult.failed }}</el-tag>
          <span class="tip">共 {{ importResult.total }} 行</span>
        </div>
        <el-table v-if="importResult.errors?.length" :data="importResult.errors" size="small" max-height="220">
          <el-table-column prop="line" label="行号" width="70" />
          <el-table-column prop="studentNo" label="学号" width="130">
            <template #default="{ row }">{{ row.studentNo || '—' }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" />
        </el-table>
      </div>

      <template #footer>
        <el-button @click="importer.open = false">关闭</el-button>
        <el-button type="primary" :loading="importer.uploading" @click="doImport">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 人脸审核 -->
    <el-dialog v-model="face.open" title="人脸采集审核" width="480px">
      <div>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="学生">
            {{ face.row?.name }}（{{ face.row?.studentNo }}）
          </el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag size="small" :type="faceTag[face.row?.faceStatus || 'NONE'] || 'info'">
              {{ faceLabel[face.row?.faceStatus || 'NONE'] }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="faceStatus" label="样本数">
            {{ faceStatus.sampleCount ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="faceStatus?.rejectReason" label="驳回原因">
            {{ faceStatus.rejectReason }}
          </el-descriptions-item>
        </el-descriptions>
        <p class="tip" style="margin-top: 12px">
          通过后样本向量才写入特征库，学生立刻就能刷脸签到；驳回必须填写原因。
        </p>
      </div>
      <template #footer>
        <el-button @click="face.open = false">关闭</el-button>
        <el-button :disabled="face.row?.faceStatus !== 'PENDING_REVIEW'" @click="openReject">驳回</el-button>
        <el-button
          type="primary"
          :disabled="face.row?.faceStatus !== 'PENDING_REVIEW'"
          @click="review(true)"
        >
          通过
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectOpen" title="驳回人脸采集" width="420px" append-to-body>
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="255" show-word-limit
        placeholder="如：照片逆光、遮挡面部、非本人" />
      <template #footer>
        <el-button @click="rejectOpen = false">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认驳回</el-button>
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
  margin-left: 10px;
  font-size: 13px;
  font-weight: 400;
  color: #909399;
}
.tip {
  font-size: 12px;
  color: #909399;
}
.result {
  margin-top: 8px;
}
.result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
:deep(.hl) {
  background: #ecf5ff !important;
  transition: background 0.4s;
}
</style>
