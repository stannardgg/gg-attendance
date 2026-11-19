import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 代理表：dev 与 preview 共用一份。
// preview 不会读 server.proxy，只配 server 的话 `vite preview` 下所有 /api 都会 404。
const proxy = {
  '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, ws: true },
  '/face-api': { target: 'http://127.0.0.1:8000', changeOrigin: true }
}

// base 用 /admin/：nginx 把管理端挂在子路径下（§12.2）
export default defineConfig({
  base: '/admin/',
  plugins: [vue()],
  resolve: {
    // 与 tsconfig 的 paths 保持一致，否则打包时找不到 @/xxx
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) }
  },
  server: { port: 5173, proxy },
  preview: { port: 5173, proxy },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // 管理端是内网桌面工具，不受移动端 200KB 首屏预算约束（§7.4.1 只约束移动端）。
    // Element Plus 全量引入约 950KB / gzip 306KB，切成独立 chunk 后长期命中缓存。
    chunkSizeWarningLimit: 1000,
    rollupOptions: {
      output: {
        // Element Plus 体积大且几乎不变，单独切一个 chunk 才能长期命中缓存
        manualChunks: {
          vue: ['vue', 'vue-router'],
          element: ['element-plus']
        }
      }
    }
  }
})
