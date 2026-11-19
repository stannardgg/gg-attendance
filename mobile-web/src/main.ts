import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setToken } from './shared/api/client'

createApp(App).use(router).mount('#app')

// 令牌失效统一回登录页；扫码进行中被踢出去时也要能回到原页面
window.addEventListener('gg:unauthorized', () => {
  setToken(null)
  if (router.currentRoute.value.name !== 'login') {
    router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
})
