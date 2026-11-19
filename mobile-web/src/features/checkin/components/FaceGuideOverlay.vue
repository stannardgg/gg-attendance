<script setup lang="ts">
/**
 * 取景框与遮罩。
 * 多人时**立即盖住整个画面**并给出唯一处置（找地方单独签），
 * 而不是在学生正对镜头时弹一个小 toast —— 现场根本看不清。
 */
defineProps<{
  blackHint?: boolean
  blocked?: string | null
  message?: string
}>()
</script>

<template>
  <div class="overlay">
    <!-- 取景框：人脸放进来即可，四角比整框更少遮挡画面 -->
    <div v-if="!blocked" class="frame">
      <span class="corner tl" />
      <span class="corner tr" />
      <span class="corner bl" />
      <span class="corner br" />
    </div>

    <div v-if="blackHint && !blocked" class="tip warn">
      画面很暗，往亮处站一点
    </div>

    <div v-if="blocked" class="blocked">
      <div class="icon">!</div>
      <div class="msg">{{ message || '当前无法继续' }}</div>
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.frame {
  position: relative;
  width: 62%;
  aspect-ratio: 3 / 4;
  max-height: 56vh;
  border-radius: 50% / 42%;
  box-shadow: 0 0 0 2000px rgba(0, 0, 0, 0.32);
}
.corner {
  position: absolute;
  width: 26px;
  height: 26px;
  border: 3px solid #07c160;
}
.tl {
  top: 6%;
  left: 8%;
  border-right: 0;
  border-bottom: 0;
  border-radius: 10px 0 0 0;
}
.tr {
  top: 6%;
  right: 8%;
  border-left: 0;
  border-bottom: 0;
  border-radius: 0 10px 0 0;
}
.bl {
  bottom: 6%;
  left: 8%;
  border-right: 0;
  border-top: 0;
  border-radius: 0 0 0 10px;
}
.br {
  bottom: 6%;
  right: 8%;
  border-left: 0;
  border-top: 0;
  border-radius: 0 0 10px 0;
}
.tip {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 12%;
  text-align: center;
  font-size: 14px;
}
.tip.warn {
  color: #ffd666;
}
.blocked {
  pointer-events: auto;
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.82);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  text-align: center;
}
.blocked .icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #ee0a24;
  color: #fff;
  font-size: 28px;
  font-weight: 700;
  line-height: 48px;
}
.blocked .msg {
  color: #fff;
  font-size: 16px;
  line-height: 1.5;
}
</style>
