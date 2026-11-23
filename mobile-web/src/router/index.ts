import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { auth, loadMe, logout } from '@/shared/store/auth'
import { getRefreshToken, getToken } from '@/shared/api/client'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/features/auth/LoginView.vue'), meta: { public: true } },
  {
    path: '/activate',
    name: 'activate',
    component: () => import('@/features/auth/ActivateView.vue'),
    meta: { public: true }
  },
  { path: '/', name: 'home', component: () => import('@/features/notice/HomeView.vue') },
  {
    path: '/checkin',
    name: 'checkin-entry',
    component: () => import('@/features/checkin/pages/CheckinEntry.vue')
  },
  {
    path: '/checkin/:sid',
    name: 'checkin-capture',
    component: () => import('@/features/checkin/pages/CaptureView.vue'),
    // 采集页必须有可用人脸档案，否则开了摄像头也会被 FA-4006 拒
    meta: { requireFace: true }
  },
  {
    path: '/face/enroll',
    name: 'face-enroll',
    component: () => import('@/features/face/EnrollView.vue')
  },
  { path: '/appeal/:sid', name: 'appeal', component: () => import('@/features/appeal/AppealView.vue') },
  { path: '/me', name: 'me', component: () => import('@/features/profile/MeView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory('/m/'), routes })

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  // 只有 access 也可能只是过期了：只要 refresh cookie 还在就先放行，
  // loadMe 里会静默续期——直接踢登录页会打断「刷新后仍是当前用户」。
  if (!getToken() && !getRefreshToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.ready) await loadMe()
  if (!auth.profile) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  // 学生端按契约只服务 STUDENT。教师/管理员即使带着有效 token 打开移动端
  //（比如误在 /m/login 登录过），所有 /api/v1/mobile/** 都会 403，页面全是死路——
  // 在守卫这里就地登出并送回登录页，而不是让用户在采集到最后一步时撞"权限不足"。
  if (auth.profile.role !== 'STUDENT') {
    await logout()
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requireFace && auth.faceStatus?.status !== 'ACTIVE') {
    // 未通过审核 → 引导去采集，而不是让学生开了摄像头再失败
    return { name: 'face-enroll', query: { from: to.fullPath } }
  }
  return true
})

export default router
