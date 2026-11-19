import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { auth, loadMe } from '@/shared/store/auth'
import { getToken } from '@/shared/api/client'

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
  if (!getToken()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.ready) await loadMe()
  if (!auth.profile) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requireFace && auth.faceStatus?.status !== 'ACTIVE') {
    // 未通过审核 → 引导去采集，而不是让学生开了摄像头再失败
    return { name: 'face-enroll', query: { from: to.fullPath } }
  }
  return true
})

export default router
