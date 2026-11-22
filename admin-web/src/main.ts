import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { setToken, TOKEN_KEY } from './shared/api/client'
import { auth, loadMe } from './shared/store/auth'

const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)

// 令牌失效时由 client 派发该事件，统一回登录页；避免每个页面各写一遍
window.addEventListener('gg:unauthorized', () => {
  setToken(null)
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})

/**
 * 别的标签页登录/退出会改写同一个 localStorage 令牌，但本页的 auth.profile
 * 是内存里的旧身份 —— 于是会出现"右上角写着管理员，点进教师管理却 403"。
 * 监听 storage 事件（只在其他标签页触发）把身份重新拉齐，并按新身份纠正路由。
 */
window.addEventListener('storage', async (e) => {
  if (e.key !== TOKEN_KEY) return
  const route = router.currentRoute.value
  const roles = route.meta.roles as string[] | undefined
  await loadMe()
  if (!auth.profile) {
    if (route.name !== 'login') {
      ElMessage.warning('登录状态已失效，请重新登录')
      router.replace({ name: 'login', query: { redirect: route.fullPath } })
    }
    return
  }
  if (roles && !roles.includes(auth.profile.role)) {
    ElMessage.warning(`登录账号已在其他标签页切换为「${auth.profile.name || auth.profile.username}」，已回到概览`)
    router.replace({ name: 'dashboard' })
  }
})

app.mount('#app')
