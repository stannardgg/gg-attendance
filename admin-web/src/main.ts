import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { setToken } from './shared/api/client'

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

app.mount('#app')
