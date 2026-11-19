<script setup lang="ts">
/**
 * 动作指令区。
 * 指令文字要一眼可读（学生举着手机看），所以字号跟视口走、不放在小标签里；
 * 进度条只反映服务端回传的 progress，不做本地"猜"的动画。
 */
defineProps<{
  instruction: string
  hint?: string
  progress: number
  windowLeft: number
  detected: number
  required: number
}>()
</script>

<template>
  <div class="prompt">
    <div class="instr">{{ instruction || '请按提示完成动作' }}</div>
    <div v-if="hint" class="hint">{{ hint }}</div>

    <div class="bar">
      <div class="fill" :style="{ width: `${Math.round(Math.min(1, Math.max(0, progress)) * 100)}%` }" />
    </div>

    <div class="meta">
      <span v-if="required">已识别 {{ detected }} / {{ required }} 次</span>
      <span>{{ windowLeft }}s</span>
    </div>
  </div>
</template>

<style scoped>
.prompt {
  background: rgba(0, 0, 0, 0.55);
  border-radius: 12px;
  padding: 12px 14px;
  color: #fff;
  backdrop-filter: blur(2px);
}
.instr {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.35;
  letter-spacing: 0.5px;
}
.hint {
  margin-top: 4px;
  font-size: 14px;
  color: #ffd666;
}
.bar {
  margin-top: 10px;
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.22);
  overflow: hidden;
}
.fill {
  height: 100%;
  background: #07c160;
  transition: width 0.25s linear;
}
.meta {
  margin-top: 6px;
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.78);
  font-variant-numeric: tabular-nums;
}
</style>
