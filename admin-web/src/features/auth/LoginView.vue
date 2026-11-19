<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/shared/api/client'
import { login } from '@/shared/store/auth'

const router = useRouter()
const route = useRoute()
const form = ref({ username: '', password: '' })
const loading = ref(false)

async function submit() {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    await login(form.value.username, form.value.password)
    const redirect = (route.query.redirect as string) || '/dashboard'
    router.replace(redirect)
  } catch (err) {
    // 账号不存在与密码错误返回同一个 FA-1001，不暴露账号是否存在（§7.3）
    if (err instanceof ApiError && err.code === 'FA-1001') {
      ElMessage.error('账号或密码错误')
    } else if (err instanceof ApiError) {
      ElMessage.error(`${err.message}（${err.code}）`)
    } else {
      ElMessage.error('登录失败，请稍后重试')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="wrap">
    <div class="card">
      <h1>人脸签到 · 管理端</h1>
      <p class="sub">教师与管理员使用同一入口，按角色渲染菜单</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" placeholder="工号或 admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width: 100%" @click="submit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(140deg, #1f2733, #2f6feb 140%);
}
.card {
  width: 360px;
  background: #fff;
  border-radius: 12px;
  padding: 28px 28px 20px;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.25);
}
h1 {
  font-size: 18px;
  margin: 0 0 6px;
}
.sub {
  color: #909399;
  font-size: 12px;
  margin: 0 0 18px;
}
</style>
