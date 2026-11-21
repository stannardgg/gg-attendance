<script setup lang="ts">
// 微信内置浏览器全局引导：扫码最常从微信进来，摄像头页在微信内必挂，
// 所以在站点入口就把人引导到外部浏览器（软提示，可关闭继续浏览）。
import WeChatGuard from '@/shared/components/WeChatGuard.vue'
</script>

<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
  <WeChatGuard />
</template>

<style>
:root {
  --page-bg: #f7f8fa;
}
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', sans-serif;
  background: var(--page-bg);
  color: #323233;
  -webkit-tap-highlight-color: transparent;
}
.page {
  min-height: 100%;
  padding: 12px 14px calc(20px + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.card-gap {
  margin-bottom: 12px;
}
.muted {
  color: #969799;
  font-size: 12px;
}
.center {
  display: flex;
  align-items: center;
  justify-content: center;
}
</style>
