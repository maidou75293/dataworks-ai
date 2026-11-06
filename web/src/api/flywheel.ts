// REQ-FW-4 数据飞轮编排接口（/api/flywheel，权限点 mos:flywheel）。
// 只走平台后端（../api 的 api/post/put），不碰 mos 桥接通道（api/mos.ts）。
import { api, post, put } from '../api'

export type FlywheelRun = {
  id: number
  status: 'RUNNING' | 'WAIT_GATE' | 'CLOSED' | 'FAILED' | string
  currentStep: string
  gateWait?: string | null
  dsId?: string | null
  datasetVersion?: string | null
  jobExtId?: string | null
  evalExtId?: string | null
  modelExtId?: string | null
  modelName?: string | null
  deployRef?: string | null
  relabelBatchId?: number | null
  relabelTaskRef?: string | null
  childRunId?: number | null
  parentRunId?: number | null
  lastError?: string | null
  createdBy?: string | null
  createdMs?: number
  deployTsMs?: number | null
}

export type FlywheelEvent = {
  id: number
  step: string
  type: 'INFO' | 'WARN' | 'ERROR' | 'GATE' | string
  message: string
  metrics?: string | null
  tsMs?: number
}

/** 七步泳道（顺序=后端 FlywheelOrchestrator.STEPS，改一边必须改另一边）。 */
export const FLYWHEEL_STEPS: { key: string; label: string }[] = [
  { key: 'TABLE', label: '选表入环' },
  { key: 'ANNOTATE', label: '标注' },
  { key: 'TRAIN', label: '训练' },
  { key: 'EVAL', label: '评测' },
  { key: 'DEPLOY', label: '部署上岗' },
  { key: 'BADCASE', label: 'badcase 池' },
  { key: 'RELABEL', label: '回环重标' },
]

export const GATE_LABEL: Record<string, string> = {
  ANNOTATE: '标注完成',
  EVAL: '评测准入',
}

export function listRuns(openOnly = false): Promise<FlywheelRun[]> {
  return api<FlywheelRun[]>(`/api/flywheel/runs?openOnly=${openOnly}`)
}

export function runDetail(id: number): Promise<FlywheelRun & { events: FlywheelEvent[] }> {
  return api<FlywheelRun & { events: FlywheelEvent[] }>(`/api/flywheel/runs/${id}`)
}

export function startRun(body: Record<string, unknown>): Promise<FlywheelRun> {
  return post<FlywheelRun>('/api/flywheel/runs', body)
}

export function decideGate(
  id: number, gate: string, decision: 'approve' | 'reject', reason?: string,
): Promise<FlywheelRun> {
  return post<FlywheelRun>(`/api/flywheel/runs/${id}/gate`, { gate, decision, reason })
}

export function pumpTick(): Promise<Record<string, number>> {
  return post<Record<string, number>>('/api/flywheel/tick')
}

export function loadSettings(): Promise<Record<string, string | null>> {
  return api<Record<string, string | null>>('/api/flywheel/settings')
}

export function saveSettings(body: Record<string, string>): Promise<Record<string, string | null>> {
  return put<Record<string, string | null>>('/api/flywheel/settings', body)
}

// ---------- 开圈向导 · 可视化选表（REQ-FLYWHEEL-ENTRY-1） ----------
//
// 业务用户不写 SQL：项目→库→表→列/分区→样例预览，全部只读复用表管理端点
// （与 web/src/pages/Tables.tsx 同一批 /api/tables*，不新增后端接口、不写数据）。
// 表结构走 direct=true：Gravitino 缓存路径的 ddl 可能为空串（2026-11-04 实测三张表
// 均空），分区判定（PARTITION BY）必须直连 Doris 才取得到；只查选中这一张，成本可接受。

export type FwProject = { id: number; name: string; code?: string }
export type FwDatabaseRow = { database: string; tables?: number | null }
export type FwTableRow = { table: string; comment?: string | null; rows?: number | null }
export type FwColumn = { name: string; type?: string; nullable?: boolean; comment?: string | null }
export type FwTableInfo = { columns: FwColumn[]; ddl?: string | null }
export type FwSample = { columns: string[]; rows: string[][]; rowCount: number }

const enc = encodeURIComponent

/** /api/tables* 的项目参数拼接：不传=默认仓库（同 Tables.tsx 的 q 口径）。 */
function fwPickerQ(projectId?: number, extra?: string): string {
  const parts = [projectId != null ? `projectId=${projectId}` : '', extra || ''].filter(Boolean)
  return parts.length ? `?${parts.join('&')}` : ''
}

/** 表管理端点响应一会是数组、一会是 {databases|tables: [...]}，这里归一（同 Tables.tsx 读法）。 */
function fwPickerList(payload: unknown, key: string): unknown[] {
  if (Array.isArray(payload)) return payload
  const arr = (payload as Record<string, unknown> | null)?.[key]
  return Array.isArray(arr) ? arr : []
}

const fwStr = (v: unknown) => (v == null ? '' : String(v))

export async function fwPickerProjects(): Promise<FwProject[]> {
  return fwPickerList(await api<unknown>('/api/projects'), 'projects') as FwProject[]
}

export async function fwPickerDatabases(projectId?: number): Promise<FwDatabaseRow[]> {
  const payload = await api<unknown>(`/api/tables/databases${fwPickerQ(projectId)}`)
  return fwPickerList(payload, 'databases') as FwDatabaseRow[]
}

export async function fwPickerTables(projectId: number | undefined, database: string): Promise<FwTableRow[]> {
  const payload = await api<unknown>(`/api/tables${fwPickerQ(projectId, `database=${enc(database)}`)}`)
  return fwPickerList(payload, 'tables') as FwTableRow[]
}

export async function fwPickerTableInfo(
  projectId: number | undefined, database: string, table: string,
): Promise<FwTableInfo> {
  const payload = await api<Record<string, unknown>>(
    `/api/tables/${enc(database)}/${enc(table)}${fwPickerQ(projectId, 'direct=true')}`)
  const cols = Array.isArray(payload?.columns) ? (payload.columns as FwColumn[]) : []
  return { columns: cols, ddl: typeof payload?.ddl === 'string' ? payload.ddl : null }
}

/** 样例预览（后端 query:run 权限点；rows 实测为字符串数组，兼容对象行两种形态）。 */
export async function fwPickerSample(
  projectId: number | undefined, database: string, table: string, limit = 20,
): Promise<FwSample> {
  const payload = await post<Record<string, unknown>>(
    `/api/tables/${enc(database)}/${enc(table)}/preview${fwPickerQ(projectId, `limit=${limit}`)}`)
  const columns = Array.isArray(payload?.columns) ? (payload.columns as string[]).map(fwStr) : []
  const raw = Array.isArray(payload?.rows) ? (payload.rows as unknown[]) : []
  const rows = raw.map((r) => (Array.isArray(r)
    ? r.map(fwStr)
    : Object.values((r ?? {}) as Record<string, unknown>).map(fwStr)))
  return {
    columns,
    rows,
    rowCount: typeof payload?.rowCount === 'number' ? payload.rowCount : rows.length,
  }
}

// ---------- 学习（REQ-NIGHT-LEARN-1；权限沿用 mos:flywheel，后端类级收口，未新立点位） ----------
//
// 岗位=1 个标注项目（labelProjectId 是 MOS 标注项目号，不是平台项目号）。
// 端点串与 FlywheelOrchestratorController 逐字对齐，判据 N2 两边字面比钉（拼错=假绿）。

export type NightPost = {
  labelProjectId: number
  labelProjectTitle?: string | null
  platformProjectId?: number | null
  enabled: boolean
  windowStart: string
  windowEnd: string
  evalMinScore: number
  featureSql?: string | null
  qColumn?: string | null
  aColumn?: string | null
  refColumn?: string | null
  maxRows: number
  updatedBy?: string | null
  updatedMs?: number | null
}

/** 异常圈单列条目；kind 白名单见后端 FlywheelNightService.requireAbnormalKind。 */
export type NightAbnormalRun = {
  runId?: number | null
  kind: string
  reason: string
  step?: string | null
}

export type NightReport = {
  nightDate: string
  labelProjectId: number
  labelProjectTitle?: string | null
  window?: string[]
  runs: number
  passed: number
  abnormal: number
  badcaseTop?: { question: string; count: number }[]
  newModels?: string[]
  abnormalRuns?: NightAbnormalRun[]
  detail?: Record<string, unknown>
  source?: string | null
  updatedMs?: number | null
}

export type NightReportAgg = {
  nightDate?: string | null
  rows: NightReport[]
  summary: { posts: number; abnormalPosts: number; runs: number; passed: number; abnormal: number }
  source?: string
}

export function listNightPosts(): Promise<NightPost[]> {
  return api<NightPost[]>('/api/flywheel/night/posts')
}

export function saveNightPost(
  labelProjectId: number, body: Partial<NightPost> & Record<string, unknown>,
): Promise<NightPost> {
  return put<NightPost>(`/api/flywheel/night/posts/${labelProjectId}`, body)
}

/** 晨报复核用：夜环写入口（引擎侧调用），页面不直接调，留这里作同一契约面的字面对齐。 */
export function writeNightReport(body: Record<string, unknown>): Promise<
  { nightDate: string; labelProjectId: number; written: boolean }> {
  return post<{ nightDate: string; labelProjectId: number; written: boolean }>(
    '/api/flywheel/night/report', body)
}

export function listNightReports(date?: string, days = 1): Promise<NightReportAgg> {
  const q = `days=${days}${date ? `&date=${encodeURIComponent(date)}` : ''}`
  return api<NightReportAgg>(`/api/flywheel/night/reports?${q}`)
}

export function nightBadcaseTop(
  sinceMs: number, limit = 10, projectId?: number,
): Promise<{ question: string; count: number }[]> {
  const q = `sinceMs=${sinceMs}&limit=${limit}${projectId != null ? `&projectId=${projectId}` : ''}`
  return api<{ question: string; count: number }[]>(`/api/flywheel/badcase/top?${q}`)
}
