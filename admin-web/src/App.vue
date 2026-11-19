<template>
  <router-view />
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  background: #f5f7fa;
  color: #1f2d3d;
}
.page {
  padding: 16px 20px 32px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.page-head h2 {
  font-size: 18px;
  margin: 0;
}
.hint {
  color: #909399;
  font-size: 12px;
}
</style>
