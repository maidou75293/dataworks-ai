/**
 * vitest.config.ts —— REQ-UNIFY-6-2 面板组件单测配置（放在 panel/ 域内，
 * 不动 web 根共享文件；跑法：cd web && npx vitest run --config src/components/ws/panel/vitest.config.ts）。
 * devDeps（vitest/jsdom/@testing-library/*）涉及 package.json = 共享文件 → 走「主控合入清单」。
 */
import { defineConfig } from 'vitest/config'

// vitest 取 root=web/（最近 vite.config 处）；include/setupFiles 相对 root 写全路径
export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['src/components/ws/panel/__tests__/setup.ts'],
    include: ['src/components/ws/panel/__tests__/**/*.test.ts',
              'src/components/ws/panel/__tests__/**/*.test.tsx'],
    restoreMocks: true,
  },
})
