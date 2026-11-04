/**
 * vitest.config.ts —— REQ-UNIFY-6-5 撤销/重做＋草稿单测配置（放在 history/ 域内，
 * 不动 web 根共享文件；跑法：cd web && npx vitest run --config src/components/ws/history/vitest.config.ts）。
 * 纯状态机/引擎注入测试，environment=node（wsLifecycle/engine 均不触 DOM）。
 */
import { defineConfig } from 'vitest/config'

// vitest 取 root=web/（最近 vite.config 处）；include 相对 root 写全路径（panel 先例同）
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/components/ws/history/__tests__/**/*.test.ts'],
    restoreMocks: true,
  },
})
