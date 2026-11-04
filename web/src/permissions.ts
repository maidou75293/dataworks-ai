import { storageGet, storageRemove, storageSet } from './api'

/** 旧的角色枚举（app_user.role）：只用于用户管理页的下拉与旧会话兜底。 */
export type Role = 'ADMIN' | 'DEVELOPER' | 'OPERATOR' | 'VIEWER'

/**
 * 前端权限判定已切到**权限点**（与后端 60 个权限点一致）。
 *
 * 旧版按角色矩阵裁剪界面，第四期的 APPROVER / STEWARD / SECURITY_ADMIN / AUDITOR
 * 不在矩阵里，会被当成 VIEWER——安全中心打不开、审批按钮消失。现在改为：
 * 登录 / me 接口返回 `atoms`，界面按权限点裁剪；`atoms` 缺失时按旧角色兜底。
 */
const LEGACY_ATOMS: Record<Role, string[]> = {
  VIEWER: [
    'project:read', 'workflow:read', 'monitor:read', 'sync:read', 'datasource:read',
    'query:run', 'catalog:read', 'lineage:read', 'quality:read', 'service:read',
    'alert:read', 'access:request', 'agent:chat',
  ],
  OPERATOR: [
    'project:read', 'workflow:read', 'monitor:read', 'sync:read', 'datasource:read',
    'query:run', 'catalog:read', 'lineage:read', 'quality:read', 'service:read',
    'alert:read', 'access:request', 'agent:chat',
    'ops:run', 'ops:backfill', 'ops:stop', 'sync:run', 'quality:run',
    'alert:manage', 'alert:ack',
  ],
  DEVELOPER: [
    'project:read', 'workflow:read', 'monitor:read', 'sync:read', 'datasource:read',
    'query:run', 'catalog:read', 'lineage:read', 'quality:read', 'service:read',
    'alert:read', 'access:request', 'agent:chat',
    'ops:run', 'ops:backfill', 'ops:stop', 'sync:run', 'quality:run',
    'alert:manage', 'alert:ack',
    'workflow:create', 'workflow:update', 'workflow:delete', 'workflow:publish',
    'schedule:update', 'sync:write', 'sync:delete', 'datasource:write', 'datasource:delete',
    'catalog:manage', 'service:write', 'service:publish', 'service:token', 'service:delete',
    'bi:write', 'bi:publish',
    'quality:write', 'agent:memory:write', 'agent:action:request',
  ],
  ADMIN: ['*'],
}

export const ROLE_LABEL: Record<Role, string> = {
  ADMIN: '平台管理员',
  DEVELOPER: '数据开发',
  OPERATOR: '运维',
  VIEWER: '只读访客',
}

export const ROLE_COLOR: Record<Role, string> = {
  ADMIN: 'red',
  DEVELOPER: 'blue',
  OPERATOR: 'orange',
  VIEWER: 'default',
}

/** 列表筛选/展示用的历史列选项：V130（REQ-DW-12）收敛后 OPERATOR 不再产生新数据。 */
export const ROLE_OPTIONS: { value: Role; label: string }[] = [
  { value: 'ADMIN', label: ROLE_LABEL.ADMIN },
  { value: 'DEVELOPER', label: ROLE_LABEL.DEVELOPER },
  { value: 'VIEWER', label: ROLE_LABEL.VIEWER },
]

/**
 * REQ-DW-12 收敛后的 5 个内置角色（与 V130 迁移、/api/rbac/roles 同口径）：
 * 建用户 / 改角色下拉用这个，不能再发 OPERATOR 等历史码（后端会 400）。
 */
export const BUILTIN_ROLE_LABEL: Record<string, string> = {
  ADMIN: '平台管理员',
  OWNER: '项目Owner',
  SECURITY_ADMIN: '安全审核员',
  DEVELOPER: '数据开发',
  VIEWER: '只读访客',
}

export const BUILTIN_ROLE_OPTIONS = Object.entries(BUILTIN_ROLE_LABEL)
  .map(([value, label]) => ({ value, label }))

/** 角色码 → 中文名：先查内置 5 角色，再回退历史列枚举（OPERATOR 等旧会话数据）。 */
export function roleLabelOf(code: string | undefined): string {
  if (!code) return '—'
  return BUILTIN_ROLE_LABEL[code] ?? ROLE_LABEL[code as Role] ?? code
}

/** 内置 5 角色的展示色（角色标签用；REQ-DW-12 同步）。 */
const ROLE_TAG_COLOR: Record<string, string> = {
  ADMIN: 'red',
  OWNER: 'purple',
  DEVELOPER: 'blue',
  SECURITY_ADMIN: 'volcano',
  VIEWER: 'default',
}

function readJson<T>(key: string, fallback: T): T {
  const raw = storageGet(key)
  if (!raw) return fallback
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as T) : fallback
  } catch {
    return fallback
  }
}

/** 登录 / me 响应写入本地；字段缺失时保留旧值，避免一次抖动清空菜单。 */
export function applyIdentity(info: {
  atoms?: string[]
  roles?: string[]
  roleLabel?: string
  role?: string
  tenant?: string
}): void {
  // 平台级开关（如数据脱敏网关）只有默认租户能改，界面要按租户裁剪
  if (info.tenant) {
    storageSet('dw_tenant', info.tenant)
  }
  if (Array.isArray(info.atoms)) {
    storageSet('dw_atoms', JSON.stringify(info.atoms))
  }
  if (Array.isArray(info.roles)) {
    storageSet('dw_roles', JSON.stringify(info.roles))
  }
  if (info.roleLabel) {
    storageSet('dw_role_label', info.roleLabel)
  }
  if (info.role) {
    storageSet('dw_role', info.role)
  }
}

export function clearIdentity(): void {
  storageRemove('dw_atoms')
  storageRemove('dw_roles')
  storageRemove('dw_role_label')
  storageRemove('dw_role')
  storageRemove('dw_tenant')
}

export function currentAtoms(): string[] {
  return readJson<string[]>('dw_atoms', [])
}

/** 是否有某个权限点（`*` 表示全权，仅旧会话的 ADMIN 兜底使用）。 */
export function can(atom: string): boolean {
  const atoms = currentAtoms()
  if (atoms.length > 0) {
    return atoms.includes('*') || atoms.includes(atom)
  }
  return LEGACY_ATOMS[currentRole()].includes(atom)
    || LEGACY_ATOMS[currentRole()].includes('*')
}

/** 任一权限点即可（菜单/路由这种「多个入口都对」的判定）。 */
export function canAny(...atoms: string[]): boolean {
  return atoms.some((atom) => can(atom))
}

/** 管理类入口（用户/角色/租户/部门）的判定。 */
export function isAdmin(): boolean {
  return canAny('user:manage', 'role:manage', 'tenant:manage', 'department:manage')
}

/** 当前租户编码（登录/me 返回；缺失时按默认租户兜底）。 */
export function currentTenant(): string {
  return storageGet('dw_tenant') || 'default'
}

/** 是否平台管理员所在的默认租户：平台级配置只给它改。 */
export function isPlatformTenant(): boolean {
  return currentTenant() === 'default'
}

export function currentRoles(): string[] {
  return readJson<string[]>('dw_roles', [])
}

export function currentRoleLabel(): string {
  const label = storageGet('dw_role_label')
  if (label) return label
  const roles = currentRoles()
  return roles.length > 0 ? roles.join(' / ') : ROLE_LABEL[currentRole()]
}

export function roleColor(): string {
  for (const code of currentRoles()) {
    if (ROLE_TAG_COLOR[code]) return ROLE_TAG_COLOR[code]
  }
  return ROLE_COLOR[currentRole()]
}

/** 旧字段：app_user.role，仅作为没有权限点时的兜底。 */
export function currentRole(): Role {
  let value: string | null = null
  try {
    value = localStorage.getItem('dw_role')
  } catch {
    // 存储被禁用时按最小权限处理
  }
  return value && value in LEGACY_ATOMS ? (value as Role) : 'VIEWER'
}
