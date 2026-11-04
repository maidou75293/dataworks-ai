import { useEffect, useState } from 'react'

/**
 * 全局「当前项目」：数据开发、数据源、数据质量共用同一个选择。
 * 存在 localStorage 里，切页面、刷新浏览器都保持；同一页面内多处选择器
 * 通过订阅同步，避免两个下拉框显示不同项目。
 */
const STORAGE_KEY = 'dw_project'

const listeners = new Set<(id: number | undefined) => void>()

export function readProjectId(): number | undefined {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const id = Number(raw)
    return raw && Number.isFinite(id) && id > 0 ? id : undefined
  } catch {
    // 隐私模式下 localStorage 不可用：本次会话内仍能正常选择
    return undefined
  }
}

export function setGlobalProject(id: number | undefined) {
  try {
    if (id) localStorage.setItem(STORAGE_KEY, String(id))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 写不进去不影响当前页面
  }
  listeners.forEach((notify) => notify(id))
}

/** [当前项目, 切换项目]；多个页面/组件共用同一份状态。 */
export function useGlobalProject(): [number | undefined, (id: number | undefined) => void] {
  const [id, setId] = useState<number | undefined>(readProjectId)
  useEffect(() => {
    listeners.add(setId)
    // 挂载时再读一次：别的页面可能已经切过项目
    setId(readProjectId())
    return () => {
      listeners.delete(setId)
    }
  }, [])
  return [id, setGlobalProject]
}
