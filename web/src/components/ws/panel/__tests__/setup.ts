/** vitest setup：jest-dom 断言 + jsdom 缺的两件（matchMedia/ResizeObserver，antd v5 依赖）。 */
import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

if (!window.matchMedia) {
  (window as any).matchMedia = (query: string) => ({
    matches: false, media: query, onchange: null,
    addListener: () => {}, removeListener: () => {},
    addEventListener: () => {}, removeEventListener: () => {},
    dispatchEvent: () => false,
  })
}
if (!(window as any).ResizeObserver) {
  class RO { observe() {} unobserve() {} disconnect() {} }
  ;(window as any).ResizeObserver = RO
}

afterEach(() => cleanup())
