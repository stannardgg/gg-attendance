import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { VantResolver } from 'unplugin-vue-components/resolvers'

// 代理表：dev 与 preview 共用一份。
// preview 不会读 server.proxy，只配 server 的话 `vite preview` 下所有 /api 都会 404。
const proxy = {
  '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, ws: true },
  '/face-api': { target: 'http://127.0.0.1:8000', changeOrigin: true }
}

// base 用 /m/：nginx 把移动端挂在子路径下（§12.2）
export default defineConfig({
  base: '/m/',
  plugins: [
    vue(),
    // Vant 按需引入：只把用到的组件与样式打进来，否则首屏必然超 200KB（§7.1）
    Components({ resolvers: [VantResolver()], dts: 'src/components.d.ts' })
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: { host: true, port: 5174, proxy },
  preview: { port: 5174, proxy },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // 首屏 < 200KB gzip 是硬指标，超过 220KB 就必须回头看引了什么
    chunkSizeWarningLimit: 220,
    rollupOptions: {
      output: {
        manualChunks: { vue: ['vue', 'vue-router'] }
      }
    }
  }
})
