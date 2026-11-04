/**
 * wsAccess.ts —— 标注通道写权限的纯逻辑＋共享状态（BUG-LABEL-WRITE-403-1 修复层②）。
 *
 * 背景：全体中台用户共享服务账号 svc-dataworks 代持 mos（MosClient.java:69），
 * 前端按「有 mos:access 即可用」呈现，mos 角色 svc-bridge 若是 label:view，
 * 四类写操作全 403 且只剩一闪 toast——权限与 UI 脱节。本模块把「能不能写」变成
 * **显式三态**并常驻呈现：
 *   - 进入工作台探测 `GET /api/mos/api/auth/me`（whoami，perms.label）；
 *   - 任何写请求吃到 403「没有「标注」模块」也即时翻成 readonly（chokepoint：
 *     wsApi.ts wsCall / dialogs/dialogApi.ts dlgCall）；
 *   - **unknown ≠ readonly**：探测没回来/形态不符判「无法判定」，不误伤成只读
 *     （军规同 mediaHealth：守卫要红得起来，但不能把环境噪声当坏数据）。
 * 本文件不碰 fetch/react/antd——闸3 node 对拍直接 import 跑（scripts/e2e/label-write-403-test.py）；
 * fetch 接线在 wsApi.ts probeWriteAccess()，UI 在 MosWorkbench.tsx。
 */

export type WriteAccess = 'unknown' | 'writable' | 'readonly'

/** 档位序与 mos auth.py LEVEL_RANK 对齐（none<view<edit）。 */
const LEVEL_RANK: Record<string, number> = { none: 0, view: 1, edit: 2 }

export interface WhoamiInfo {
  username: string
  role_id: string
  role_name: string
}

/**
 * whoami 响应（mos auth.py:314 me_payload：`{user:{…}, perms:{12 模块全展开}}`）→ 三态。
 * - `*`=edit（synthetic/老数据形态）按可写；
 * - perms 缺失/label 缺失/档位不认识 → unknown（不猜）；
 * - label=none/view → readonly。
 */
export function classifyWhoami(data: unknown): WriteAccess {
  const d = data as Record<string, any> | null
  const perms = d && (d.perms ?? (d.user && d.user.perms))
  if (!perms || typeof perms !== 'object' || Array.isArray(perms)) return 'unknown'
  if ((perms as Record<string, any>)['*'] === 'edit') return 'writable'
  const lv = (perms as Record<string, any>).label
  if (typeof lv !== 'string' || !(lv in LEVEL_RANK)) return 'unknown'
  return LEVEL_RANK[lv] >= LEVEL_RANK.edit ? 'writable' : 'readonly'
}

/** whoami 里给横幅用的账号信息（取不到就回空串，文案兜底默认名）。 */
export function whoamiInfo(data: unknown): WhoamiInfo | null {
  const d = data as Record<string, any> | null
  const u = d && d.user
  if (!u || typeof u !== 'object') return null
  return {
    username: String(u.username ?? ''),
    role_id: String(u.role_id ?? ''),
    role_name: String(u.role_name ?? ''),
  }
}

/**
 * mos 写 403 识别：auth.py forbidden_detail 固定前缀「没有「标注」模块的…」；
 * 其它模块（如媒体库）的 403 不算标注只读。status 缺失/非 403 一律 false。
 */
export function isWriteForbidden(status: number | undefined, message = ''): boolean {
  return status === 403 && String(message).includes('没有「标注」模块')
}

/** 横幅标题（闸3 精确断言；常驻 Alert，替代一闪 toast）。 */
export const READONLY_TITLE = '当前账号对标注只读，写操作已被底层服务拦截'

/** 申请路径文案（卡面③：说清找谁/哪个权限点，不「请联系管理员」四个字了事）。 */
export function readonlyBannerText(info: WhoamiInfo | null): string {
  const user = info?.username || 'svc-dataworks'
  const role = info?.role_id || 'svc-bridge'
  return '中台全体用户共享服务账号「' + user + '」（角色 ' + role + '）代持 mos，'
    + '该角色「标注」模块当前只有只读档（label=view），保存标注/导入任务/保存项目设置都会被拒。'
    + '申请路径：平台管理员在 mos 管理面把角色 ' + role + ' 的「标注」升为可编辑'
    + '（档位 label=edit；一条命令 scripts/ops/mos-bridge-role.py --apply）。'
    + '中台侧的准入边界（mos:access 权限点＋租户隔离）不变，不需要给你个人开权限。'
}

/** 写动作被只读闸拦下时的即时提示（按钮已灰，快捷键路径兜底）。 */
export const WRITE_BLOCKED_TEXT = '当前标注通道只读（服务角色无 label:edit）：改动已存在本地草稿，不会丢；开通写权限后即可保存'

// ---------- 共享状态（模块级单例；MosWorkbench / wsLifecycle / WsDialogStore 同读） ----------

let access: WriteAccess = 'unknown'
let whoami: WhoamiInfo | null = null
const listeners = new Set<() => void>()

function notify() { for (const cb of Array.from(listeners)) cb() }

export function getWriteAccess(): WriteAccess { return access }
export function getWhoamiInfo(): WhoamiInfo | null { return whoami }

/** 探测结果落状态；unknown 不覆盖已知结论（探测失败≠权限变了）。 */
export function applyProbe(a: WriteAccess, info: WhoamiInfo | null = null) {
  if (info) whoami = info
  if (a === 'unknown') return
  if (access !== a) { access = a; notify() }
}

export function setWriteAccess(a: WriteAccess) {
  if (access === a) return
  access = a
  notify()
}

/**
 * 写请求错误 chokepoint：403+「标注」→ 翻 readonly（返回 true 表示已翻）。
 * 其余状态/模块一律不动声色（409 冲突、媒体库 403 等各有归宿）。
 */
export function markReadOnlyIfForbidden(status: number | undefined, message = ''): boolean {
  if (!isWriteForbidden(status, message)) return false
  if (access !== 'readonly') { access = 'readonly'; notify() }
  return true
}

export function subscribeWriteAccess(cb: () => void): () => void {
  listeners.add(cb)
  return () => { listeners.delete(cb) }
}

/** 仅供闸3 node 对拍重置单例（运行时不调用）。 */
export function __resetForTest(resetTo: WriteAccess = 'unknown') {
  access = resetTo
  whoami = null
}
