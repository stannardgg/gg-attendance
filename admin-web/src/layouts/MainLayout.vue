<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, logout } from '@/shared/store/auth'
import { api, messageOf } from '@/shared/api/client'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const pendingAppeals = ref(0)

const menus = computed(() => {
  const isAdmin = auth.profile?.role === 'ADMIN'
  const isTeacher = auth.profile?.role === 'TEACHER'
  return [
    { path: '/dashboard', label: '概览', icon: 'Odometer' },
    { path: '/classes', label: '班级管理', icon: 'School' },
    { path: '/courses', label: '课程管理', icon: 'Notebook' },
    { path: '/students', label: '学生管理', icon: 'User' },
    ...(isAdmin ? [{ path: '/teachers', label: '教师与授权', icon: 'Avatar' }] : []),
    ...(isTeacher ? [{ path: '/profile', label: '我的资料', icon: 'Postcard' }] : []),
    { path: '/sessions/new', label: '发起签到', icon: 'VideoPlay' },
    { path: '/sessions', label: '签到场次', icon: 'List' },
    { path: '/attendance', label: '考勤查询', icon: 'DataLine' },
    { path: '/appeals', label: '申诉处理', icon: 'ChatDotRound', badge: pendingAppeals.value },
    { path: '/agent', label: '智能助手', icon: 'MagicStick' },
    ...(isAdmin
      ? [
          { path: '/audit', label: '审计日志', icon: 'Document' },
          { path: '/config', label: '系统配置', icon: 'Setting' }
        ]
      : [])
  ]
})

function activePath(): string {
  // /sessions/new 与 /sessions 是两个菜单项，需要精确区分
  return route.path
}

async function loadPending() {
  try {
    const data = await api.get<{ pendingAppeals: number }>('/api/v1/admin/stats/overview')
    pendingAppeals.value = data.pendingAppeals ?? 0
  } catch (err) {
    // 概览失败不影响导航；红点缺失比整页报错好
  }
}

async function onLogout() {
  await logout()
  ElMessage.success('已退出')
  router.replace({ name: 'login' })
}

onMounted(loadPending)
</script>

<template>
  <el-container style="height: 100%">
    <el-aside width="212px" class="aside">
      <div class="brand">人脸签到 · 管理端</div>
      <el-menu :default-active="activePath()" router class="menu">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <span>{{ m.label }}</span>
          <el-badge
            v-if="m.badge"
            :value="m.badge"
            :max="99"
            style="margin-left: auto"
            type="danger"
          />
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="who">
          <strong>{{ auth.profile?.name || auth.profile?.username }}</strong>
          <el-tag size="small" :type="auth.profile?.role === 'ADMIN' ? 'danger' : 'success'">
            {{ auth.profile?.role === 'ADMIN' ? '管理员' : '教师' }}
          </el-tag>
        </div>
        <el-button link type="primary" @click="onLogout">退出登录</el-button>
      </el-header>
      <el-main style="padding: 0">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.aside {
  background: #1f2733;
  display: flex;
  flex-direction: column;
}
.brand {
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  padding: 18px 20px;
  letter-spacing: 0.5px;
}
.menu {
  border-right: none;
  background: transparent;
  --el-menu-text-color: #c3cad6;
  --el-menu-hover-bg-color: #2b3545;
  --el-menu-active-color: #ffffff;
}
.menu :deep(.el-menu-item.is-active) {
  background: #2f6feb;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e8eaef;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.who {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
