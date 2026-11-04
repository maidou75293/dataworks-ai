import {
  api, clearToken, getToken, post, setToken, storageGet, storageRemove, storageSet,
} from './api'
import { applyIdentity, clearIdentity } from './permissions'

export interface SessionInfo {
  token?: string
  username: string
  displayName?: string
  /** 旧字段：app_user.role（历史枚举），新界面按 atoms 判定 */
  role?: string
  roleLabel?: string
  roles?: string[]
  roleLabels?: string[]
  atoms?: string[]
  tenant?: string
  /** 旧字段：READ / DEV_WRITE 等，保留给升级前的客户端 */
  permissions?: string[]
}

export function isLoggedIn(): boolean {
  return Boolean(getToken())
}

export function rememberSession(info: SessionInfo): void {
  if (info.token) {
    setToken(info.token)
  }
  storageSet('dw_user', info.username)
  storageSet('dw_display', info.displayName || info.username)
  applyIdentity(info)
}

export async function login(username: string, password: string): Promise<SessionInfo> {
  const result = await post<SessionInfo>('/api/auth/login', { username, password })
  rememberSession(result)
  return result
}

/** 刷新角色：管理员改了角色/禁用后，界面下个页面加载就能看到。 */
export async function refreshMe(): Promise<SessionInfo> {
  const me = await api<SessionInfo>('/api/auth/me')
  rememberSession(me)
  return me
}

/**
 * 切换租户（MT-P1-1）：后端签发绑定新租户的新 token 并吊销旧 token。
 * 调用后需要刷新页面，让所有列表按新租户重新加载。
 */
export async function switchTenant(tenant: string): Promise<SessionInfo> {
  const result = await post<SessionInfo>('/api/auth/switch-tenant', { tenant })
  rememberSession(result)
  return result
}

export function logout(): void {
  void post('/api/auth/logout').catch(() => undefined)
  clearToken()
  clearIdentity()
  storageRemove('dw_display')
}

export function currentUser(): string {
  return storageGet('dw_user') || 'admin'
}

export function currentDisplayName(): string {
  return storageGet('dw_display') || currentUser()
}

export function rememberUser(username: string): void {
  storageSet('dw_user', username)
}
