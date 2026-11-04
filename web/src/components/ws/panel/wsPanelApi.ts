/**
 * wsPanelApi.ts —— 面板自己的 mos 通道调用（REQ-UNIFY-6-2）。
 *
 * 卡面「标签改名触达 api/mos.ts labels 组→主控合入」的落地方式 = 沿用 6-1 先例
 * （wsApi.ts 头注：{detail}→Error 在新文件内自实现），api/mos.ts **零改动**；
 * 端点对照 mos/platform/frontend/src/api.js:266 `lsSetLabels`（只读原件）。
 */
import { api } from '../../../api'
import { mosChannelUrl } from '../../../api/mos'

export type LsSetLabelsBody = {
  from_name: string
  control: string
  labels: string[]
  rename: Record<string, string>
}

export type LsSetLabelsResp = {
  project?: Record<string, any>
  migrated?: { annotations?: number; predictions?: number; failed?: number }
}

/** mos 侧错误体是 FastAPI {detail}；转 Error(message)+status（wsApi.ts:14-24 同式）。 */
async function panelCall<T>(mosPath: string, init: RequestInit = {}): Promise<T> {
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
    throw mapped
  }
}

/** POST /api/label-studio/projects/{pid}/labels（Vue api.js:266）。 */
export function lsSetLabels(pid: number, body: LsSetLabelsBody): Promise<LsSetLabelsResp> {
  return panelCall<LsSetLabelsResp>(
    '/api/label-studio/projects/' + pid + '/labels',
    { method: 'POST', body: JSON.stringify(body) },
  )
}
