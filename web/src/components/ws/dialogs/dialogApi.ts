/**
 * dialogApi.ts —— 弹窗群端点的真实通道实现（REQ-UNIFY-6-6）。
 *
 * 与 wsApi.ts 同一口径：浏览器不直连 mos，一律 `/api/mos/**` 代理
 * （api/mos.ts:8），错误体 FastAPI `{detail}` 转 Error+status。
 * 端点逐条对照 mos/platform/frontend/src/api.js:261-309 台账；
 * wsApi.ts 的 Transport 只声明 6-1 队列/保存面（共享文件不动），
 * 弹窗面在此独立成 DialogTransport 实现。
 */
import { api } from '../../../api'
import { mosChannelUrl } from '../../../api/mos'
import { markReadOnlyIfForbidden } from '../wsAccess'
import type { DialogTransport } from './wsDialogActions'

async function dlgCall<T>(mosPath: string, init: RequestInit = {}): Promise<T> {
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
    // BUG-LABEL-WRITE-403-1②：与 wsApi.ts wsCall 同一 chokepoint 口径
    markReadOnlyIfForbidden(mapped.status, mapped.message)
    throw mapped
  }
}

const get = <T>(p: string): Promise<T> => dlgCall<T>(p)
const send = <T>(m: string, p: string, b?: unknown): Promise<T> =>
  dlgCall<T>(p, { method: m, body: b === undefined ? undefined : JSON.stringify(b) })

/** 查询串与 api.media（api.js:234-238）逐参数一致：kind/q 缺省不带，limit/offset 恒带。 */
function mediaQuery(kind: string, q: string, limit: number, offset = 0): string {
  const sp = new URLSearchParams()
  if (kind) sp.set('kind', kind)
  if (q) sp.set('q', q)
  sp.set('limit', String(limit))
  sp.set('offset', String(offset))
  return '/api/media?' + sp.toString()
}

/** /api/label-studio/ai-stats 查询（api.js:298-301：pid 为 0 时不带 pid）。 */
function aiStatsQuery(pid: number, days: number): string {
  const sp = new URLSearchParams()
  if (pid) sp.set('pid', String(pid))
  sp.set('days', String(days))
  return '/api/label-studio/ai-stats?' + sp.toString()
}

export const dialogTransport: DialogTransport = {
  lsQc: (pid) => get('/api/label-studio/projects/' + pid + '/qc'),
  validateLsConfig: (b) => send('POST', '/api/label-studio/validate-config', b),
  patchLsProject: (pid, b) => send('PATCH', '/api/label-studio/projects/' + pid, b),
  // BUG-PARITY-5：openSettings「补一次加载」+ 一键修复前置判据用（Vue api.js lsProject 同端点）
  lsProject: (pid) => get('/api/label-studio/projects/' + pid),
  lsProjectImport: (pid, b) => send('POST', '/api/label-studio/projects/' + pid + '/import', b),
  lsPreview: (pid, tid) => get('/api/label-studio/projects/' + pid + '/tasks/' + tid + '/preview'),
  lsPredict: (pid, b) => send('POST', '/api/label-studio/projects/' + pid + '/predict', b),
  aiStatus: () => get('/api/label-studio/ai-status'),
  aiConfig: (pid) => get('/api/label-studio/projects/' + pid + '/ai-config'),
  saveAiConfig: (pid, b) => send('PUT', '/api/label-studio/projects/' + pid + '/ai-config', b),
  aiStats: (pid, days) => get(aiStatsQuery(pid, days)),
  lsAutoTrain: () => get('/api/label-studio/auto-train'),
  saveLsAutoTrain: (b) => send('POST', '/api/label-studio/auto-train', b),
  lsSyncMap: () => get('/api/label-studio/sync-map'),
  saveLsSyncMap: (b) => send('POST', '/api/label-studio/sync-map', b),
  mediaList: (kind, q, limit) => get(mediaQuery(kind, q, limit)),
}
