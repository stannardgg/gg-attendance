<script setup lang="ts">
/**
 * 智能助手（§8.2 / D6）。
 *
 * 不把 JWT 放进 URL：先换一次性 code（60 秒、单次消费），
 * 让 Streamlit 自己拿 code 去 /internal/agent/sso/exchange 换会话 token。
 * iframe 被安全头拦时降级为新标签页（§11.1）。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { AgentCode } from '@/shared/api/types'

const agentUrl = ref('')
const expiresAt = ref('')
const left = ref(0)
const loaded = ref(false)
const blocked = ref(false)
const connecting = ref(false)
let ticker: number | undefined
let watchdog: number | undefined

const src = computed(() => agentUrl.value)

const quickQuestions = [
  '今天有哪些签到',
  '导出近两天考勤记录',
  '有哪些待处理申诉',
  '本周各班出勤率是多少',
  '帮我给计算机 2024 级 1 班发起高等数学（上）签到'
]

async function connect(question?: string) {
  connecting.value = true
  loaded.value = false
  blocked.value = false
  window.clearTimeout(watchdog)
  try {
    const data = await api.post<AgentCode>('/api/v1/agent/session')
    expiresAt.value = data.expiresAt
    // 服务端给的是 /agent/?code=...，追加 q 让 Streamlit 直接把这句话发出去
    const url = new URL(data.agentUrl, location.origin)
    if (question) url.searchParams.set('q', question)
    agentUrl.value = url.toString()
    tick()
    // 8 秒还没 onload，基本可以判定被 X-Frame-Options / CSP 拦了
    watchdog = window.setTimeout(() => {
      if (!loaded.value) blocked.value = true
    }, 8000)
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    connecting.value = false
  }
}

function tick() {
  if (!expiresAt.value) {
    left.value = 0
    return
  }
  left.value = Math.max(0, Math.round((new Date(expiresAt.value).getTime() - Date.now()) / 1000))
}

function openNewTab() {
  if (agentUrl.value) window.open(agentUrl.value, '_blank', 'noopener')
  else connect()
}

async function ask(q: string) {
  // 每次快捷问句都换一个新 code：旧 code 已被上一次加载消费掉了
  await connect(q)
}

onMounted(() => {
  connect()
  ticker = window.setInterval(tick, 1000)
})

onBeforeUnmount(() => {
  window.clearInterval(ticker)
  window.clearTimeout(watchdog)
})
</script>

<template>
  <div class="page agent-page">
    <div class="page-head">
      <h2>智能助手</h2>
      <div class="ops">
        <span class="ttl">凭证 {{ left }}s</span>
        <el-button size="small" :loading="connecting" @click="connect()">重新连接</el-button>
        <el-button size="small" @click="openNewTab">新标签页打开</el-button>
      </div>
    </div>

    <div class="quick">
      <span class="label">快捷问句</span>
      <el-tag
        v-for="q in quickQuestions"
        :key="q"
        class="q"
        size="large"
        effect="plain"
        @click="ask(q)"
      >
        {{ q }}
      </el-tag>
      <span class="hint">点一下会重连并直接发问，不需要模型也能跑通只读查询。</span>
    </div>

    <el-alert
      v-if="blocked"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 10px"
    >
      嵌入被浏览器的安全策略拦住了。<el-link type="primary" @click="openNewTab">在新标签页打开</el-link>
      即可正常使用（nginx 侧需移除 X-Frame-Options，见 §12.3）。
    </el-alert>

    <div class="frame-wrap">
      <div v-if="!agentUrl" class="placeholder" v-loading="connecting">正在换取一次性凭证…</div>
      <iframe
        v-else
        :src="src"
        class="frame"
        title="智能助手"
        referrerpolicy="no-referrer"
        @load="loaded = true"
      />
    </div>
  </div>
</template>

<style scoped>
.agent-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.ops {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ttl {
  font-size: 12px;
  color: #909399;
  font-variant-numeric: tabular-nums;
}
.quick {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.quick .label {
  font-size: 13px;
  color: #606266;
}
.quick .q {
  cursor: pointer;
}
.quick .hint {
  font-size: 12px;
  color: #a8abb2;
}
.frame-wrap {
  flex: 1 1 auto;
  min-height: 420px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}
.frame {
  width: 100%;
  height: 100%;
  border: 0;
  display: block;
}
.placeholder {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 14px;
}
</style>
