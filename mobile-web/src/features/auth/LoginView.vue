<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { login, logout, auth } from '@/shared/store/auth'
import { ApiError } from '@/shared/api/client'
import { isWeChat } from '@/shared/utils'

const router = useRouter()
const route = useRoute()
const studentNo = ref('')
const password = ref('')
const loading = ref(false)
const wechat = isWeChat()

async function submit() {
  if (!studentNo.value || !password.value) return
  loading.value = true
  try {
    await login(studentNo.value.trim(), password.value)
    // 移动端是学生端（/api/v1/mobile/** 只放行 STUDENT）：教师/管理员登进来
    // 会在签到、采集等每个接口上撞 403「权限不足」。在源头就拦下并登出，
    // 不给"能进页面但处处报错"的死路。
    if (auth.profile && auth.profile.role !== 'STUDENT') {
      await logout()
      showToast('移动端仅限学生使用，请用学号登录')
      return
    }
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (err) {
    // §7.3：错误提示不暴露账号是否存在，1001 一律同一句话
    if (err instanceof ApiError && (err.code === 'FA-1001' || err.code === 'FA-2001')) {
      showToast('学号或密码不正确')
    } else {
      showToast(err instanceof Error ? err.message : '登录失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page login">
    <div class="brand">
      <h1>人脸签到</h1>
      <p class="muted">用学号登录，扫码完成签到</p>
    </div>

    <van-notice-bar
      v-if="wechat"
      wrapable
      :scrollable="false"
      type="warning"
      text="微信内置浏览器无法使用摄像头，请点右上角「⋯」选择「在浏览器打开」后再签到。"
    />

    <van-form @submit="submit" style="margin-top: 20px">
      <van-cell-group inset>
        <van-field
          v-model="studentNo"
          name="studentNo"
          label="学号"
          placeholder="请输入学号"
          autocomplete="username"
          clearable
        />
        <van-field
          v-model="password"
          type="password"
          name="password"
          label="密码"
          placeholder="请输入密码"
          autocomplete="current-password"
        />
      </van-cell-group>
      <div style="margin: 20px 16px">
        <van-button round block type="primary" native-type="submit" :loading="loading" :disabled="wechat">
          登录
        </van-button>
      </div>
    </van-form>

    <div class="center" style="gap: 8px">
      <span class="muted">第一次使用？</span>
      <van-button size="small" plain type="primary" @click="router.push('/activate')">
        激活账号
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.login {
  padding-top: 48px;
}
.brand {
  text-align: center;
  margin-bottom: 20px;
}
.brand h1 {
  font-size: 24px;
  margin: 0 0 6px;
  letter-spacing: 2px;
}
</style>
