<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" />
  </router-view>
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
