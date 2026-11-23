import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { auth, loadMe } from '@/shared/store/auth'
import { getRefreshToken, getToken } from '@/shared/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/features/auth/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/features/dashboard/DashboardView.vue') },
      { path: 'classes', name: 'classes', component: () => import('@/features/class/ClassView.vue') },
      { path: 'courses', name: 'courses', component: () => import('@/features/course/CourseView.vue') },
      { path: 'students', name: 'students', component: () => import('@/features/student/StudentView.vue') },
      {
        path: 'teachers',
        name: 'teachers',
        component: () => import('@/features/teacher/TeacherView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/features/profile/TeacherProfileView.vue'),
        meta: { roles: ['TEACHER'] }
      },
      { path: 'sessions', name: 'sessions', component: () => import('@/features/session/SessionListView.vue') },
      { path: 'sessions/new', name: 'session-new', component: () => import('@/features/session/SessionCreateView.vue') },
      {
        path: 'sessions/:id/live',
        name: 'session-live',
        component: () => import('@/features/session/SessionLiveView.vue')
      },
      { path: 'attendance', name: 'attendance', component: () => import('@/features/attendance/AttendanceView.vue') },
      { path: 'appeals', name: 'appeals', component: () => import('@/features/appeal/AppealView.vue') },
      { path: 'agent', name: 'agent', component: () => import('@/features/agent/AgentView.vue') },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/features/system/AuditView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'config',
        name: 'config',
        component: () => import('@/features/system/ConfigView.vue'),
        meta: { roles: ['ADMIN'] }
      }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHistory('/admin/'), routes })

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  // 只有 access 也可能只是过期了：只要 refresh cookie 还在就先放行，
  // loadMe 里会静默续期——直接踢登录页会打断「刷新后仍是当前用户」。
  if (!getToken() && !getRefreshToken()) return { name: 'login', query: { redirect: to.fullPath } }

  // 刷新页面后第一次进入：先补一次 /auth/me，否则角色判断会拿到 null
  if (!auth.ready) await loadMe()
  if (!auth.profile) return { name: 'login', query: { redirect: to.fullPath } }

  const roles = to.meta.roles as string[] | undefined
  if (roles && !roles.includes(auth.profile.role)) {
    // 直接输 URL 也要被拦住，而不是渲染一个空页面（§8.3）
    return { name: 'dashboard' }
  }
  return true
})

export default router
