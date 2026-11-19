<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, ApiError, messageOf } from '@/shared/api/client'
import type { ClassRow, CourseRow, SessionRow } from '@/shared/api/types'

const router = useRouter()
const step = ref(0)
const loading = ref(false)
const submitting = ref(false)
const classes = ref<ClassRow[]>([])
const courses = ref<CourseRow[]>([])
const keywordClass = ref('')
const keywordCourse = ref('')

const picked = reactive({
  classRow: null as ClassRow | null,
  courseRow: null as CourseRow | null,
  title: '',
  // el-input-number 清空时会回 undefined，声明成可空才能过类型检查；提交时再兜默认值
  durationMinutes: 90 as number | undefined,
  lateAfterSec: 300 as number | undefined,
  allowAppeal: true
})

const filteredClasses = computed(() => {
  const k = keywordClass.value.trim()
  return k ? classes.value.filter((c) => c.name.includes(k) || c.code.includes(k)) : classes.value
})
const filteredCourses = computed(() => {
  const k = keywordCourse.value.trim()
  return k ? courses.value.filter((c) => c.name.includes(k) || c.code.includes(k)) : courses.value
})

const lateLabel = computed(() => {
  const sec = picked.lateAfterSec ?? 0
  const m = Math.round(sec / 60)
  return m >= 1 ? `${m} 分钟` : `${sec} 秒`
})

async function loadOrg() {
  loading.value = true
  try {
    const [cls, crs] = await Promise.all([
      api.get<{ items: ClassRow[] }>('/api/v1/admin/classes', { size: 200, status: 'ACTIVE' }),
      api.get<{ items: CourseRow[] }>('/api/v1/admin/courses', { size: 200, status: 'ACTIVE' })
    ])
    classes.value = cls.items ?? []
    courses.value = crs.items ?? []
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

function pickClass(c: ClassRow) {
  picked.classRow = c
  // 标题默认「课程 + 日期」，用户可改；换课程时若没手改过就跟着变
  if (!picked.title || picked.title === autoTitle()) picked.title = autoTitle(c)
  step.value = 1
}

function pickCourse(c: CourseRow) {
  picked.courseRow = c
  if (!picked.title || picked.title === autoTitle()) picked.title = autoTitle()
  step.value = 2
}

function autoTitle(cls = picked.classRow) {
  const today = new Date()
  const d = `${today.getMonth() + 1}月${today.getDate()}日`
  return picked.courseRow ? `${picked.courseRow.name} · ${d}` : `${cls?.name ?? ''} 签到 · ${d}`
}

async function submit() {
  if (!picked.classRow || !picked.courseRow) {
    ElMessage.warning('班级与课程都必须选')
    return
  }
  if (!picked.title.trim()) {
    ElMessage.warning('请填写场次标题')
    return
  }
  submitting.value = true
  try {
    const created = await api.post<SessionRow>('/api/v1/admin/sessions', {
      classId: picked.classRow.id,
      courseId: picked.courseRow.id,
      title: picked.title.trim(),
      durationMinutes: picked.durationMinutes ?? 90,
      lateAfterSec: picked.lateAfterSec ?? 300,
      allowAppeal: picked.allowAppeal
    })
    ElMessage.success(`已发起，应到 ${created.expectedCount} 人`)
    router.replace(`/sessions/${created.id}/live`)
  } catch (err) {
    if (err instanceof ApiError && err.code === 'FA-2006') {
      ElMessage.error(
        `未授权：${picked.classRow.name} × ${picked.courseRow.name}。请让管理员为你在「教师与授权」中同时授权该班级与该课程。`
      )
    } else {
      ElMessage.error(messageOf(err))
    }
  } finally {
    submitting.value = false
  }
}

onMounted(loadOrg)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>发起签到</h2>
      <el-button @click="router.push('/sessions')">返回列表</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-steps :active="step" align-center finish-status="success" style="margin-bottom: 24px">
        <el-step title="选班级" description="决定应到名单" />
        <el-step title="选课程" description="仅用于统计口径" />
        <el-step title="设参数" description="时长 / 迟到阈值" />
        <el-step title="确认发起" description="大字核对" />
      </el-steps>

      <!-- 1. 班级 -->
      <div v-show="step === 0" class="step-body">
        <el-input v-model="keywordClass" placeholder="搜索班级名称或班级码" clearable style="width: 280px" />
        <el-empty v-if="!filteredClasses.length" description="没有可选班级，先到「班级管理」建班" />
        <div class="cards">
          <div v-for="c in filteredClasses" :key="c.id" class="pick-card" @click="pickClass(c)">
            <div class="big">{{ c.name }}</div>
            <div class="meta">
              <span>{{ c.code }}</span>
              <span v-if="c.grade">· {{ c.grade }} 级</span>
              <span v-if="c.major">· {{ c.major }}</span>
            </div>
            <div class="count">当前在读 <b>{{ c.studentCount }}</b> 人</div>
          </div>
        </div>
      </div>

      <!-- 2. 课程 -->
      <div v-show="step === 1" class="step-body">
        <div class="picked-bar">
          已选班级：<b>{{ picked.classRow?.name }}</b>
          <el-button link type="primary" @click="step = 0">重选</el-button>
        </div>
        <el-input v-model="keywordCourse" placeholder="搜索课程名称或编码" clearable style="width: 280px" />
        <el-empty v-if="!filteredCourses.length" description="没有可选课程，先到「课程管理」建课" />
        <div class="cards">
          <div v-for="c in filteredCourses" :key="c.id" class="pick-card" @click="pickCourse(c)">
            <div class="big">{{ c.name }}</div>
            <div class="meta">
              <span>{{ c.code }}</span>
              <span v-if="c.semester">· {{ c.semester }}</span>
            </div>
          </div>
        </div>
        <div class="tip">课程只影响统计口径，不影响应到名单 —— 应到名单始终来自所选班级的在读学生。</div>
      </div>

      <!-- 3. 参数 -->
      <div v-show="step === 2" class="step-body">
        <div class="picked-bar">
          <b>{{ picked.classRow?.name }}</b> × <b>{{ picked.courseRow?.name }}</b>
          <el-button link type="primary" @click="step = 0">重选</el-button>
        </div>
        <el-form label-width="110px" style="max-width: 520px">
          <el-form-item label="场次标题">
            <el-input v-model="picked.title" maxlength="120" show-word-limit />
          </el-form-item>
          <el-form-item label="签到时长">
            <el-input-number v-model="picked.durationMinutes" :min="1" :max="240" :step="10" />
            <span class="unit">分钟</span>
          </el-form-item>
          <el-form-item label="迟到阈值">
            <el-input-number v-model="picked.lateAfterSec" :min="0" :max="7200" :step="60" />
            <span class="unit">秒 · 当前 {{ lateLabel }}后签到记为迟到</span>
          </el-form-item>
          <el-form-item label="允许申诉">
            <el-switch v-model="picked.allowAppeal" />
            <span class="unit">关闭后学生无法对本次失败记录发起申诉</span>
          </el-form-item>
        </el-form>
        <el-button type="primary" @click="step = 3">下一步</el-button>
      </div>

      <!-- 4. 大字确认 -->
      <div v-show="step === 3" class="step-body">
        <div class="confirm">
          <div class="confirm-class">{{ picked.classRow?.name }}</div>
          <div class="times">×</div>
          <div class="confirm-course">{{ picked.courseRow?.name }}</div>
        </div>
        <el-descriptions :column="2" border style="margin: 20px auto; max-width: 720px">
          <el-descriptions-item label="场次标题">{{ picked.title }}</el-descriptions-item>
          <el-descriptions-item label="应到人数">
            约 {{ picked.classRow?.studentCount }} 人（创建时快照该班在读学生）
          </el-descriptions-item>
          <el-descriptions-item label="签到时长">{{ picked.durationMinutes ?? 90 }} 分钟</el-descriptions-item>
          <el-descriptions-item label="迟到阈值">{{ lateLabel }}</el-descriptions-item>
          <el-descriptions-item label="允许申诉">{{ picked.allowAppeal ? '是' : '否' }}</el-descriptions-item>
          <el-descriptions-item label="讲师">{{ picked.classRow?.ownerTeacherName || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert type="warning" :closable="false" show-icon style="max-width: 720px; margin: 0 auto 18px">
          请核对「班级 × 课程」是否选反 —— 应到名单由班级决定，签到率统计由课程归类，两者不能互换。
        </el-alert>
        <div style="text-align: center">
          <el-button @click="step = 2">上一步</el-button>
          <el-button type="primary" size="large" :loading="submitting" @click="submit">
            确认发起签到
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.step-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.pick-card {
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.pick-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 10px rgba(64, 158, 255, 0.16);
}
.big {
  font-size: 17px;
  font-weight: 600;
  color: #303133;
}
.meta {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.count {
  margin-top: 8px;
  font-size: 13px;
  color: #606266;
}
.picked-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #606266;
}
.confirm {
  text-align: center;
  line-height: 1.25;
}
.confirm-class,
.confirm-course {
  font-size: 34px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #303133;
}
.confirm-course {
  color: #409eff;
}
.times {
  font-size: 22px;
  color: #c0c4cc;
  margin: 6px 0;
}
.tip {
  font-size: 12px;
  color: #909399;
}
.unit {
  margin-left: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
