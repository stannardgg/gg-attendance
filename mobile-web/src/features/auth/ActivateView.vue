<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, ApiError, setToken } from '@/shared/api/client'
import { loadMe } from '@/shared/store/auth'
import type { LoginResult } from '@/shared/api/types'

const router = useRouter()
const studentNo = ref('')
const name = ref('')
const classCode = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  if (!studentNo.value || !name.value || !classCode.value || !password.value) {
    showToast('请把四项都填完')
    return
  }
  // 契约要求至少 8 位且含字母与数字，先本地挡一次，别等 400 才说
  if (password.value.length < 8 || !/[a-zA-Z]/.test(password.value) || !/\d/.test(password.value)) {
    showToast('密码至少 8 位，且要同时包含字母和数字')
    return
  }
  loading.value = true
  try {
    const data = await api.post<LoginResult>('/api/v1/auth/student/activate', {
      studentNo: studentNo.value.trim(),
      name: name.value.trim(),
      classCode: classCode.value.trim().toUpperCase(),
      password: password.value
    })
    setToken(data.accessToken)
    await loadMe()
    showToast('激活成功')
    // 激活后直接去采集人脸：没通过审核是刷不了脸的（FA-4006）
    router.replace('/face/enroll')
  } catch (err) {
    if (err instanceof ApiError && (err.code === 'FA-1001' || err.code === 'FA-2001')) {
      // 三者任一不匹配都是同一句模糊提示，不暴露哪个错了
      showToast('学号、姓名或班级码不匹配')
    } else if (err instanceof ApiError && err.code === 'FA-1004') {
      showToast('这个学号已经激活过了，直接登录即可')
    } else {
      showToast(err instanceof Error ? err.message : '激活失败')
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <h2 style="margin: 24px 0 6px">激活账号</h2>
    <p class="muted" style="margin-top: 0">
      用「学号 + 姓名 + 班级邀请码」核对身份。三项都对上才能激活，所以别把班级码抄错。
    </p>

    <van-form @submit="submit" style="margin-top: 16px">
      <van-cell-group inset>
        <van-field v-model="studentNo" label="学号" placeholder="如 2024010101" clearable />
        <van-field v-model="name" label="姓名" placeholder="与学籍一致" clearable />
        <van-field v-model="classCode" label="班级码" placeholder="老师给的 4~32 位邀请码" clearable />
        <van-field
          v-model="password"
          type="password"
          label="设置密码"
          placeholder="至少 8 位，含字母与数字"
        />
      </van-cell-group>
      <div style="margin: 20px 16px">
        <van-button round block type="primary" native-type="submit" :loading="loading">激活并进入</van-button>
      </div>
    </van-form>

    <div class="center">
      <van-button size="small" plain @click="router.replace('/login')">已有账号，去登录</van-button>
    </div>
  </div>
</template>
