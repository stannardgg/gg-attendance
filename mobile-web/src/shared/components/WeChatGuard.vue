<script setup lang="ts">
/**
 * 微信内置浏览器引导（§7.1）。
 *
 * 微信 WebView 不支持 getUserMedia（iOS 完全没有，安卓 XWeb 极不稳定），
 * 学生在微信里打开链接后摄像头页必挂。本组件提供两道防线：
 * - 软模式（默认）：进入站点时全屏提示一次「请在外部浏览器打开」，可关闭继续浏览；
 *   关闭状态记 sessionStorage——刷新/重新进入视为新会话会再次提示，
 *   不用 localStorage 持久关闭，防止学生忘了这回事到签到时又失败。
 * - 硬模式（hard）：摄像头页的第二道防线。学生可能已关掉软提示才走到这里，
 *   此时摄像头必然打不开，不给「继续」选项，只提供复制链接。
 */
import { computed, ref } from 'vue'
import { isWeChat } from '@/shared/utils'

const props = defineProps<{ hard?: boolean }>()

const DISMISS_KEY = 'gg.wechat-guard.dismissed'

const ios = /iphone|ipad|ipod/i.test(navigator.userAgent)
const dismissed = !props.hard && sessionStorage.getItem(DISMISS_KEY) === '1'
const visible = ref(isWeChat() && !dismissed)

/** iOS 微信的菜单项叫「在 Safari 中打开」，安卓叫「在浏览器打开」 */
const menuName = computed(() => (ios ? '在 Safari 中打开' : '在浏览器打开'))

const copied = ref(false)
function copyLink() {
  const url = location.href
  const done = () => {
    copied.value = true
    setTimeout(() => (copied.value = false), 2500)
  }
  if (navigator.clipboard?.writeText) {
    navigator.clipboard.writeText(url).then(done).catch(() => fallbackCopy(url, done))
  } else {
    fallbackCopy(url, done)
  }
}

/** 微信 WebView 可能没有 clipboard API：textarea + execCommand 兜底 */
function fallbackCopy(text: string, done: () => void) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
    done()
  } catch {
    /* 复制失败时按钮保持原样，学生仍可手动对照地址栏 */
  }
  document.body.removeChild(ta)
}

function close() {
  if (props.hard) return
  sessionStorage.setItem(DISMISS_KEY, '1')
  visible.value = false
}
</script>

<template>
  <div v-if="visible" class="wxg">
    <!-- 右上角引导箭头：微信的「···」菜单就在那里 -->
    <div class="wxg-arrow">
      <svg width="46" height="52" viewBox="0 0 46 52" fill="none" aria-hidden="true">
        <path
          d="M6 44 C 14 34, 26 24, 38 12"
          stroke="#fff"
          stroke-width="3.5"
          stroke-linecap="round"
          fill="none"
        />
        <path d="M38 12 L 24 16 M38 12 L 35 26" stroke="#fff" stroke-width="3.5" stroke-linecap="round" fill="none" />
      </svg>
      <div class="wxg-arrow-label">点右上角</div>
    </div>

    <div class="wxg-card">
      <van-icon name="warning-o" size="34" color="#ff976a" />
      <h3 class="wxg-title">请在外部浏览器打开</h3>
      <p class="wxg-sub">
        微信内无法调用摄像头，<br />人脸签到与人脸采集都不能进行。
      </p>

      <ol class="wxg-steps">
        <li>点击屏幕右上角 <b>「···」</b> 菜单</li>
        <li>选择 <b>「{{ menuName }}」</b></li>
        <li>在新浏览器里重新登录并签到</li>
      </ol>

      <van-button round block type="primary" size="normal" @click="copyLink">
        {{ copied ? '已复制，去浏览器粘贴打开' : '复制本页链接' }}
      </van-button>

      <button v-if="!props.hard" class="wxg-continue" type="button" @click="close">
        仍要继续浏览（签到不可用）&gt;
      </button>
      <p v-else class="wxg-hard-tip">本页必须使用摄像头，复制链接到浏览器打开后即可继续。</p>
    </div>
  </div>
</template>

<style scoped>
.wxg {
  position: fixed;
  inset: 0;
  z-index: 2500;
  background: rgba(0, 0, 0, 0.62);
}

.wxg-arrow {
  position: absolute;
  top: 4px;
  right: 22px;
  text-align: center;
  animation: wxg-bob 1.2s ease-in-out infinite;
}
.wxg-arrow-label {
  margin-top: -2px;
  font-size: 12px;
  color: #fff;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.8);
}
@keyframes wxg-bob {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(7px);
  }
}

.wxg-card {
  position: absolute;
  left: 22px;
  right: 22px;
  top: 50%;
  transform: translateY(-50%);
  background: #fff;
  border-radius: 16px;
  padding: 24px 20px 16px;
  text-align: center;
}
.wxg-title {
  margin: 8px 0 0;
  font-size: 19px;
  color: #323233;
}
.wxg-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: #646566;
  line-height: 1.6;
}
.wxg-steps {
  margin: 14px 6px 18px;
  padding: 12px 14px 12px 30px;
  background: #f7f8fa;
  border-radius: 10px;
  text-align: left;
  font-size: 13px;
  color: #323233;
  line-height: 2;
}
.wxg-steps b {
  color: #1989fa;
}
.wxg-continue {
  display: block;
  margin: 12px auto 0;
  border: 0;
  background: none;
  font-size: 12px;
  color: #969799;
  padding: 4px;
}
.wxg-continue:active {
  color: #1989fa;
}
.wxg-hard-tip {
  margin: 12px 0 0;
  font-size: 12px;
  color: #969799;
  line-height: 1.5;
}
</style>
