import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// 本地 5173 开发时，后端实际在 K8s NodePort 上，宿主机 localhost:8080 没有服务。
// 需要连别处时用 VITE_API_TARGET 覆盖，例如：
//   VITE_API_TARGET=http://localhost:8080 npm run dev
const apiTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:30090'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  // `vite preview` 用来验收打包产物：和生产一样走 dist，但仍能代理到已部署后端
  preview: {
    port: 4173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
    },
  },
  // vitest 接线（REQ-OSS-CI2-1，审计 §四：jsdom 没配 DOM 测试必红）：
  // environment=jsdom 让 @testing-library/react 的 render 有 document；
  // setupFiles 收 __tests__/setup.ts（jest-dom 断言 + matchMedia/ResizeObserver 垫片 + 自动 cleanup）。
  // CI 入口 = `npm test`（vitest run）；vite build 不消费本块，互不影响。
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/components/ws/panel/__tests__/setup.ts'],
    restoreMocks: true,
  },
  build: {
    outDir: 'dist',
    // 只做路由级动态 import，让 Rollup 自己拆共享块。
    // 不再按包名 manualChunks：手工把 react / antd / rc-* 分开时出现过
    // "Cannot read properties of undefined (reading 'version')"（React 版本检查拿不到），
    // 整站白屏；默认拆包已经能把编辑器（reactflow）隔离到独立 chunk。
  },
})
