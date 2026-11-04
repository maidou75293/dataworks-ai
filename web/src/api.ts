const TOKEN_KEY = 'dw_token'

/** 存储被浏览器策略禁用时不能把整个应用带崩。 */
export function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function storageSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 隐私模式/禁用存储：退化为内存态（本次会话内有效）
  }
}

export function storageRemove(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // ignore
  }
}

export function getToken(): string | null {
  return storageGet(TOKEN_KEY)
}

export function setToken(token: string): void {
  storageSet(TOKEN_KEY, token)
}

export function clearToken(): void {
  storageRemove(TOKEN_KEY)
}

/**
 * REQ-UX-ERRMSG-1：后端没有返回 message 时按 HTTP 状态码给用户中文降级文案。
 * 禁止回落 res.statusText —— 那是英文裸词（Conflict/Forbidden/Internal Server Error），
 * 会原样直达 toast。error.status 仍然挂在 Error 上供调用方区分（如 409 乐观锁）。
 */
const STATUS_ZH: Record<number, string> = {
  400: '请求未被识别，请检查填写内容后重试',
  401: '未登录或登录已过期',
  403: '您没有该操作的权限，请联系管理员申请',
  404: '请求的资源不存在或已被删除',
  405: '该操作暂不支持',
  408: '请求超时，请稍后重试',
  409: '数据刚被他人修改，请刷新页面后重试',
  413: '提交内容过大，请精简后重试',
  422: '内容校验未通过，请检查填写项',
  429: '操作过于频繁，请稍后再试',
  500: '服务器内部错误，请稍后重试',
  501: '该功能暂未提供',
  502: '服务暂时不可用，请稍后重试',
  503: '服务繁忙，请稍后重试',
  504: '服务器响应超时，请稍后重试',
}

export function statusFallbackMessage(status: number): string {
  return STATUS_ZH[status] || `请求失败（HTTP ${status}）`
}

/**
 * toast 兜底文案统一入口（REQ-UX-ERRMSG-1）：
 * 错误对象带非空 message 就用它，否则给中文兜底，杜绝空 toast。
 */
export function errMsg(e: unknown, fallback = '操作失败，请稍后重试'): string {
  const m = (e as { message?: unknown } | null | undefined)?.message
  return typeof m === 'string' && m.trim() ? m : fallback
}

export async function api<T = any>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  let res: Response
  try {
    res = await fetch(path, { ...init, headers })
  } catch {
    // 浏览器原生报错是英文 Failed to fetch，对用户没有意义
    throw new Error('无法连接服务器，请检查网络或稍后重试')
  }
  const text = await res.text()
  let data: any = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (res.status === 401) {
    clearToken()
    localStorage.removeItem('dw_role')
    localStorage.removeItem('dw_roles')
    localStorage.removeItem('dw_role_label')
    localStorage.removeItem('dw_atoms')
    localStorage.removeItem('dw_display')
    if (!location.pathname.startsWith('/login')) {
      // 登录后回到当前页，而不是每次都回到首页
      const next = encodeURIComponent(location.pathname + location.search)
      location.href = `/login?redirect=${next}`
    }
    throw new Error(data?.message || statusFallbackMessage(401))
  }
  if (!res.ok) {
    // 调用方需要区分 409（并发冲突）等状态，因此在 Error 上附带 status
    const error = new Error(data?.message || statusFallbackMessage(res.status)) as Error & {
      status?: number
      data?: unknown
    }
    error.status = res.status
    error.data = data
    throw error
  }
  return data as T
}

export function post<T = any>(path: string, body?: unknown): Promise<T> {
  return api<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })
}

export function put<T = any>(path: string, body?: unknown): Promise<T> {
  return api<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
}

export function del<T = any>(path: string): Promise<T> {
  return api<T>(path, { method: 'DELETE' })
}

/** POST /api/sql/classify 的判定结果：needReview=true 时不允许在编辑器里直接执行。 */
export type SqlClassify = {
  needReview: boolean
  tables: string[]
  reason: string
  datasourceId: number
  projectId: number
  guardEnabled: boolean
}

/** 提交前判定（不落库）；项目关闭守卫时后端一律返回 needReview=false。 */
export function classifySql(datasourceId: number, sql: string, projectId?: number): Promise<SqlClassify> {
  return post<SqlClassify>('/api/sql/classify', { datasourceId, projectId, sql })
}

/** POST /api/changes 返回的单据（列表接口返回同一形状）。 */
export type ChangeRequest = {
  id: number
  tenantId: string
  projectId: number
  datasourceId: number
  targetTable?: string | null
  sqlText: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'DONE' | 'FAILED'
  requester: string
  reviewer?: string | null
  reviewComment?: string | null
  backupTable?: string | null
  execResult?: string | null
  createdAt?: string
  reviewedAt?: string | null
  executedAt?: string | null
}

/** 命中审批规则才建单；无需审批或项目关闭守卫时后端 409，调用方按错误提示处理。 */
export function createChange(datasourceId: number, sql: string, projectId?: number): Promise<ChangeRequest> {
  return post<ChangeRequest>('/api/changes', { datasourceId, projectId, sql })
}
