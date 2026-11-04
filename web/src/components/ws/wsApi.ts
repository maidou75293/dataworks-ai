/**
 * wsApi.ts —— 标注工作台的通道 transport 实现（REQ-UNIFY-6-1）。
 *
 * 通道口径（REQ-UNIFY-1）：浏览器不直连 mos，一律走中台 `/api/mos/**` 代理
 * （web/src/api/mos.ts:8 注释）；这里复用其导出的 mosChannelUrl 与中台 api()，
 * 不改共享文件。端点对照 mos/platform/frontend/src/api.js:261-292（只读原件）。
 * mos 侧错误体是 FastAPI `{detail}`，转成 Error(message)+status 供 save() 判 409。
 */
import { api } from '../../api'
import { mosChannelUrl } from '../../api/mos'
import type { Transport } from '../../pages/mos/wsLifecycle'
import { applyProbe, classifyWhoami, markReadOnlyIfForbidden, whoamiInfo } from './wsAccess'

async function wsCall<T>(mosPath: string, init: RequestInit = {}): Promise<T> {
  try {
    return await api<T>(mosChannelUrl(mosPath), init)
  } catch (e) {
    const err = e as Error & { status?: number; data?: any }
    const detail = err?.data?.detail
    const msg = typeof detail === 'string' && detail
      ? detail
      : (err?.data?.message || err?.message || '请求失败')
    const mapped = new Error(msg) as Error & { status?: number }
    mapped.status = err.status
    // BUG-LABEL-WRITE-403-1②：写链吃到「没有「标注」模块」403 即翻只读态
    // （常驻横幅+灰按钮的触发源之一；探测失败/其它模块 403 不动状态）
    markReadOnlyIfForbidden(mapped.status, mapped.message)
    throw mapped
  }
}

/**
 * 进入工作台的写权限探测（BUG-LABEL-WRITE-403-1②）：whoami 走同一 /api/mos 通道，
 * 吃 mos me_payload.perms.label（auth.py:314）。任何失败=unknown——**探测不通不等于只读**
 * （军规同 mediaHealth），按钮保持乐观可用，等真实 403 由 wsCall/dlgCall chokepoint 翻转。
 */
export async function probeWriteAccess(): Promise<void> {
  try {
    const d = await get<Record<string, any>>('/api/auth/me')
    applyProbe(classifyWhoami(d), whoamiInfo(d))
  } catch {
    applyProbe('unknown')
  }
}

const get = <T>(p: string): Promise<T> => wsCall<T>(p)
const send = <T>(m: string, p: string, b?: unknown): Promise<T> =>
  wsCall<T>(p, { method: m, body: b === undefined ? undefined : JSON.stringify(b) })

/** 查询串拼接与 Vue 侧 api.js:279-284 逐参数一致（顺序也一致，便于对拍）。 */
function tasksQuery(status: string, limit: number, offset: number, q: string,
                    mine: 0 | 1, after: number): string {
  return '?status=' + status + '&limit=' + limit + '&offset=' + offset
    + (after ? '&after=' + after : '')
    + (q ? '&q=' + encodeURIComponent(q) : '')
    + (mine ? '&mine=1' : '')
}

export const wsTransport: Transport = {
  lsConfig: () => get('/api/label-studio/config'),
  lsProject: (pid) => get('/api/label-studio/projects/' + pid),
  lsStats: (pid, mine = 0) => get('/api/label-studio/projects/' + pid + '/stats'
    + (mine ? '?mine=1' : '')),
  lsTasks: (pid, status, limit, offset, q, mine, after) =>
    get('/api/label-studio/projects/' + pid + '/tasks'
      + tasksQuery(status, limit, offset, q, mine, after)),
  lsTask: (pid, tid) => get('/api/label-studio/projects/' + pid + '/tasks/' + tid),
  lsAnnotate: (pid, tid, b) =>
    send('POST', '/api/label-studio/projects/' + pid + '/tasks/' + tid + '/annotations', b),
  datasets: () => get('/api/datasets'),
  lsSyncMap: () => get('/api/label-studio/sync-map'),
  lsToDataset: (pid, b) => send('POST', '/api/label-studio/projects/' + pid + '/to-dataset', b),
}

/** localStorage → WsStorage（含 key(i) 遍历，对齐 scanDrafts:2079 的用法）。 */
export function wsStorage(): import('../../pages/mos/wsLifecycle').WsStorage {
  return {
    getItem: (k) => { try { return localStorage.getItem(k) } catch { return null } },
    setItem: (k, v) => { localStorage.setItem(k, v) },
    removeItem: (k) => { try { localStorage.removeItem(k) } catch { /* ignore */ } },
    keys: () => {
      const out: string[] = []
      try {
        for (let i = 0; i < localStorage.length; i++) {
          const k = localStorage.key(i)
          if (k) out.push(k)
        }
      } catch { /* ignore */ }
      return out
    },
  }
}
