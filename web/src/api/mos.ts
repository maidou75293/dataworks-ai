import { api, getToken } from '../api'

/**
 * mos（标注+微调平台 labelstudio-ms-swift）统一请求层 —— REQ-UNIFY-2。
 *
 * 通道口径（REQ-UNIFY-1 已交付 + BUG-VER-V555-2 收口）：浏览器永不直连 mos；请求统一
 * 走中台前缀 `/api/mos/**`，由中台代理做会话代持、权限点（mos:access）放行与审计。
 * 代理映射规则只有一条——`/api/mos/<x>` 直转 `<base>/<x>`，**不做「自动补 /api」**
 * （MosProxyController.java:26-31、docs/桥接网关-REQ-UNIFY-1-20261028.md §三），
 * 而对方业务路由全带 `/api` 前缀（vendor mos/platform/backend/main.py:165
 * include_router prefix="/api"），另有 `/media/*`、`/docs/api` 等无前缀路由。
 * 所以浏览器侧必须写 `/api/mos` + 对方路径原样（如 `/api/mos/api/models`），
 * 本文件的路径字面量一律与对方 api.js 串一致、原样拼接，不剥 /api。
 * 开发态：默认 `npm run dev` 即可——vite 代理 `/api` → VITE_API_TARGET（默认 :30090，
 * 通道已交付）；UNIFY-1 交付前「VITE_MOS_BASE=/api 直连本地 :8090」的旧配方作废。
 *
 * models / data-sources 两组（端点对照 mos/platform/frontend/src/api.js:91-105,180-189）
 * + jobs 组（REQ-UNIFY-3，对照 api.js:158-178）+ datasets 组（REQ-UNIFY-5）；
 * 其余接口组按后续批次卡增量补充。
 * mos 侧错误体是 FastAPI 口径 {detail}，不是中台口径 {message}，在 call() 里统一转译。
 */
const MOS_BASE: string = (import.meta as any).env?.VITE_MOS_BASE || '/api/mos'

/** 把对方路径（/api/models/…）映射为通道路径（/api/mos/api/models/…）。原样拼接、不剥 /api（见文件头口径）。 */
export function mosChannelUrl(mosPath: string): string {
  return MOS_BASE + mosPath
}

/**
 * 上游路径 → 浏览器通道路径解析（REQ-UNIFY-7）。
 * mos 业务路由全在 /api 前缀下（main.py:165），静态媒体挂根（main.py:169）。
 * 新式调用传**完整上游路径**（以 /api/ 开头）：
 *   通道模式（默认 MOS_BASE=/api/mos）→ /api/mos/api/media/…（与 MosProxyController
 *     契约、单测 /api/mos/api/auth/me 同形：剥掉 /api/mos 后原样转发）；
 *   开发直连模式（VITE_MOS_BASE=/api）→ 原样 /api/media/…，不再叠加。
 * 旧式路径（/datasets 等不带 /api，UNIFY-2/3/5 存量组）维持原拼接口径，本卡不动。
 */
function upstreamUrl(p: string): string {
  if (p.startsWith('/api/') && (MOS_BASE === '/api' || MOS_BASE.endsWith('/api/'))) return p
  return MOS_BASE + p
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  try {
    return await api<T>(upstreamUrl(path), init)
  } catch (e) {
    const err = e as Error & { status?: number; data?: any }
    if (typeof err?.data?.detail === 'string' && err.data.detail) {
      const mapped = new Error(err.data.detail) as Error & { status?: number; data?: unknown }
      mapped.status = err.status
      mapped.data = err.data
      throw mapped
    }
    throw e
  }
}

function get<T>(path: string): Promise<T> {
  return call<T>(path)
}

function send<T>(method: string, path: string, body?: unknown): Promise<T> {
  return call<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) })
}

// ---------- 模型（models 组） ----------
// 对方 REST 主键是字符串 mdl_xxx/ds_xxx；按数字 ID 请求必 404
// （docs/integration/labelstudio-ms-swift-contract.md:113，api/models.py:162）。

export type MosModel = {
  /** mdl_xxx 字符串主键 */
  id: string
  name: string
  status: string
  /** null/缺省 = 「未定版」；定版后为版本号（进 models/releases/ 软链） */
  version?: string | null
  size_gb?: number | null
  keys_total?: number | null
  architectures?: string | null
  method?: string | null
  job_id?: string | null
  dataset_id?: string | null
  base?: string | null
  notes?: string | null
  path?: string | null
  capabilities?: Record<string, number> | null
}

export type MosSuggestions = {
  base_models?: { v: string; l: string }[]
  adapters?: { v: string; l: string }[]
  gpus?: unknown[]
}

export type MosScanInfo = { size_gb?: number | null; keys_total?: number | null }

export type MosRegisterPayload = {
  name: string
  path: string
  base?: string
  method?: string
  notes?: string
  dataset_id?: string
  job_id?: string
}

export type MosRegisterResult = { id: string; status?: string; scan?: MosScanInfo }

export type MosModelManifest = {
  /** mos 侧相对路径（/api/models/…），展示/复制前用 mosChannelUrl() 转成通道路径 */
  archive_url?: string
  total_gb?: number
  file_count?: number
  [k: string]: unknown
}

export type MosReconcile = {
  ok: boolean
  verdict: string
  base?: { caps?: Record<string, number> }
  product?: { caps?: Record<string, number> }
}

export type MosPublishResult = { dest_name?: string; mode?: string; version?: string }

export type MosDeleteResult = { released?: { removed?: string[] } }

export function listModels(): Promise<MosModel[]> { return get('/api/models') }
export function getModel(id: string): Promise<MosModel> { return get(`/api/models/${encodeURIComponent(id)}`) }
export function registerModel(b: MosRegisterPayload): Promise<MosRegisterResult> { return send('POST', '/api/models', b) }
export function patchModel(id: string, b: Partial<MosRegisterPayload>): Promise<MosModel> {
  return send('PATCH', `/api/models/${encodeURIComponent(id)}`, b)
}
export function deleteModel(id: string): Promise<MosDeleteResult> { return send('DELETE', `/api/models/${encodeURIComponent(id)}`) }
export function rescanModel(id: string): Promise<MosScanInfo> { return send('POST', `/api/models/${encodeURIComponent(id)}/scan`, {}) }
export function reconcileModel(id: string): Promise<MosReconcile> { return get(`/api/models/${encodeURIComponent(id)}/reconcile`) }
export function modelManifest(id: string): Promise<MosModelManifest> { return get(`/api/models/${encodeURIComponent(id)}/manifest`) }
export function publishModel(id: string, b: { status?: string }): Promise<MosPublishResult> {
  return send('POST', `/api/models/${encodeURIComponent(id)}/publish`, b)
}
export function unpublishModel(id: string, version: string): Promise<unknown> {
  return send('POST', `/api/models/${encodeURIComponent(id)}/unpublish?version=${encodeURIComponent(version)}`, {})
}
export function suggestions(): Promise<MosSuggestions> { return get('/api/suggestions') }

// ---------- 数据源（data-sources 组；本批无页面重写，先行铺层备用） ----------

export type MosDatasource = {
  /** ds_xxx 字符串主键 */
  id: string
  name?: string
  type?: string
  status?: string
  [k: string]: unknown
}

export function listDatasources(withStats = 0): Promise<MosDatasource[]> {
  return get(`/api/datasources?with_stats=${withStats}`)
}
export function datasourceTypes(): Promise<unknown> { return get('/api/datasources/types') }
export function streamvisionCandidates(): Promise<unknown> { return get('/api/datasources/streamvision_candidates') }
export function getDatasource(id: string): Promise<MosDatasource> { return get(`/api/datasources/${encodeURIComponent(id)}`) }
export function createDatasource(b: Record<string, unknown>): Promise<MosDatasource> {
  return send('POST', '/api/datasources', b)
}
export function patchDatasource(id: string, b: Record<string, unknown>): Promise<MosDatasource> {
  return send('PATCH', `/api/datasources/${encodeURIComponent(id)}`, b)
}
export function deleteDatasource(id: string): Promise<unknown> {
  return send('DELETE', `/api/datasources/${encodeURIComponent(id)}`)
}
/** BUG-PARITY-9-C ⑩ 首个消费方接入，返回形状按 datasources.py:458/480/506/:587 写实 */
export function testDatasource(id: string): Promise<{ ok: boolean; message?: string; stats?: Record<string, unknown> }> {
  return send('POST', `/api/datasources/${encodeURIComponent(id)}/test`, {})
}
export function previewDatasource(id: string, limit = 5): Promise<{ items?: Record<string, unknown>[]; count?: number; error?: string }> {
  return get(`/api/datasources/${encodeURIComponent(id)}/preview?limit=${limit}`)
}
export function datasourceFiles(id: string): Promise<{ name: string; size_kb?: number; mtime?: string }[]> {
  return get(`/api/datasources/${encodeURIComponent(id)}/files`)
}
export function deleteDatasourceFiles(id: string, name: string): Promise<{ files?: { name: string; size_kb?: number; mtime?: string }[] }> {
  return send('DELETE', `/api/datasources/${encodeURIComponent(id)}/files`, { name })
}
export function datasourceText(id: string): Promise<{ text?: string }> {
  return get(`/api/datasources/${encodeURIComponent(id)}/text`)
}
export function putDatasourceText(id: string, text: string): Promise<unknown> {
  return send('PUT', `/api/datasources/${encodeURIComponent(id)}/text`, { text })
}

/**
 * multipart 上传。绕过 api() 的 JSON 封装：FormData 不能手动设
 * Content-Type（fetch 需要自己带 boundary），其余口径与 call() 一致。
 */
async function postForm(path: string, fd: FormData): Promise<any> {
  const headers = new Headers()
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let res: Response
  try {
    res = await fetch(upstreamUrl(path), { method: 'POST', headers, body: fd })
  } catch {
    throw new Error('无法连接服务器，请确认中台后端与 mos 服务在运行')
  }
  const text = await res.text()
  let data: any = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  if (!res.ok) {
    const err = new Error(typeof data?.detail === 'string' ? data.detail : `请求失败（${res.status}）`) as Error & { status?: number }
    err.status = res.status
    throw err
  }
  return data
}

export function uploadDatasourceFiles(id: string, fd: FormData): Promise<any> {
  return postForm(`/api/datasources/${encodeURIComponent(id)}/files`, fd)
}

// ---------- 训练任务（jobs 组，REQ-UNIFY-3） ----------
// 端点对照 mos/platform/backend/api/jobs.py：schema:461、tasks:911、detail:1267、
// pipeline:1023、log:1311（纯文本）、stop:1341、delete:1349、gate:1393/1403、
// resume:1190、merge:956、quantize:986。

export type MosJobStage = {
  id: string
  method: string
  method_label?: string
  status: string
  alive?: boolean
  rc?: number | null
  pct?: number | null
  progress?: number | null
  steps_done?: number | null
  steps_total?: number | null
  loss?: number | null
  created_at?: string | null
  updated_at?: string | null
  out_dir?: string
  own_dir?: string
  output_name?: string
  base?: string
  /** pipeline 响应里按阶段回填（jobs.py:906） */
  artifacts?: MosArtifact[]
}

/** 任务 = 训练/融合/量化聚合的一行（_task_summary，jobs.py:800-845） */
export type MosJobTask = Omit<MosJobStage, 'id' | 'method' | 'status'> & {
  id: string
  root_id?: string
  method?: string
  type?: string
  status: string
  output_kind?: string
  final_name?: string
  dataset?: string
  title?: string
  stages: MosJobStage[]
}

/** 任务详情 = jobs 表整行 + 派生字段（jobs.py:1267）。params 是 JSON 字符串；没有 stages 字段 */
export type MosJobDetail = Omit<MosJobTask, 'stages'> & {
  params?: string
  log_path?: string
  pid?: number | null
  out_dir?: string
  own_dir?: string
  stages?: MosJobStage[]
}

export type MosArtifact = {
  path: string
  name?: string
  label?: string
  rel?: string
  adapter?: boolean
  registered_by?: string | null
  model_id?: string | null
  size_gb?: number | null
  mtime?: string
  base_model?: string
  kind?: string
  /** output/merged/awq 的中文名（artifacts.py:58 KINDS） */
  kind_label?: string
  /** 目录含 config.json=可加载（artifacts.py 头部维护提示） */
  loadable?: boolean
  job_id?: string
  task_id?: string
}

export type MosPipeline = {
  task: MosJobTask | null
  task_id: string
  stages: MosJobStage[]
  artifacts: MosArtifact[]
}

export type MosGate = {
  id?: string
  job_id?: string
  status?: string
  score?: number | null
  threshold?: number | null
  passed?: boolean | null
  error?: string | null
  n_items?: number | null
  detail?: { rouge_l?: number | string; [k: string]: unknown } | string | null
}

export type MosSchemaParam = { key: string; label: string; arg?: string; type?: string; default?: unknown; [k: string]: unknown }
export type MosSchemaGroup = { group: string; params: MosSchemaParam[] }
export type MosJobSchema = {
  method?: string
  label?: string
  groups?: MosSchemaGroup[]
  presets?: Record<string, Record<string, unknown>>
  lab_defaults?: Record<string, unknown>
  methods?: Record<string, string>
}

export function jobSchema(method = ''): Promise<MosJobSchema> {
  return get(`/api/jobs/schema?method=${encodeURIComponent(method)}`)
}
/** 发起/核对任务（BUG-PARITY-11，对照 api.js:171 + jobs.py:1142）。
 *  dry_run:true 只拼命令不建任务，回 {dry_run, method, argv}（jobs.py:1073-1074）；
 *  非 dry 回 {id, method, argv, log_path}。参数错走 4xx，detail=后端原文。 */
export function createJob(b: {
  method: string; dataset_id?: string; params: Record<string, unknown>; dry_run?: boolean; force?: boolean
}): Promise<{ id?: string; dry_run?: boolean; method?: string; argv?: string[] }> {
  return send('POST', '/api/jobs', b)
}
/** 发起/核对任务（BUG-PARITY-11，对照 api.js:171 + jobs.py:1142）。
 *  dry_run:true 只拼命令不建任务，回 {dry_run, method, argv}（jobs.py:1073-1074）；
 *  非 dry 回 {id, method, argv, log_path}。参数错走 4xx，detail=后端原文。 */
export function jobTasks(limit = 500): Promise<{ tasks: MosJobTask[]; count: number }> {
  return get(`/api/jobs/tasks?limit=${limit}`)
}
export function getJob(id: string): Promise<MosJobDetail> { return get(`/api/jobs/${encodeURIComponent(id)}`) }
export function jobPipeline(id: string): Promise<MosPipeline> { return get(`/api/jobs/${encodeURIComponent(id)}/pipeline`) }
/** /jobs/{id}/log 返回纯文本（jobs.py:1311）；api() 对非 JSON 响应原样给回字符串 */
export function jobLog(id: string, tail = 300): Promise<string> {
  return get<string>(`/api/jobs/${encodeURIComponent(id)}/log?tail=${tail}`)
}
export function stopJob(id: string): Promise<{ ok: boolean }> { return send('POST', `/api/jobs/${encodeURIComponent(id)}/stop`, {}) }
export function resumeJob(id: string): Promise<{ id: string; resumed_from?: string }> {
  return send('POST', `/api/jobs/${encodeURIComponent(id)}/resume`, {})
}
export function deleteJob(id: string, force = false): Promise<{ ok: boolean; deleted?: string[] }> {
  return send('DELETE', `/api/jobs/${encodeURIComponent(id)}${force ? '?force=1' : ''}`)
}
export function mergeTaskStage(id: string, b: Record<string, unknown> = {}): Promise<{ id: string; task_id?: string }> {
  return send('POST', `/api/jobs/${encodeURIComponent(id)}/merge`, b)
}
export function quantizeTaskStage(id: string, b: Record<string, unknown> = {}): Promise<{ id: string; task_id?: string }> {
  return send('POST', `/api/jobs/${encodeURIComponent(id)}/quantize`, b)
}
export function jobGate(id: string): Promise<{ gate: MosGate | null }> { return get(`/api/jobs/${encodeURIComponent(id)}/gate`) }
export function runJobGate(id: string, b: Record<string, unknown>): Promise<{ id: string }> {
  return send('POST', `/api/jobs/${encodeURIComponent(id)}/gate`, b)
}
/**
 * 产物删除（对照 api.js:125-126；Jobs 详情产物链就地删除与台账页共用）。
 * force=1：产物已登记为模型时连模型库登记一起删（artifacts.py:307 起 409 口径；
 * 调用方必须先二次确认，否则已登记的产物永远删不掉且模型行 path 悬空）。
 */
export function deleteArtifact(path: string, force = false): Promise<{ ok?: boolean; models_removed?: string[] }> {
  return send('DELETE', `/api/artifacts?path=${encodeURIComponent(path)}${force ? '&force=1' : ''}`)
}

/**
 * 产物台账列表（BUG-PARITY-12，对照 api.js:123 artifacts()；后端 artifacts.py:279 list_artifacts）。
 * 返回：root=产物根；items=扫描行（kind output/merged/awq）；counts=按 kind 计数；
 * active=正在跑的融合/量化（输入目录 path → {id, method}，**每次现算不吃 3 分钟扫描缓存**，
 * 台账页「出可部署版本」按钮的禁用/「融合中…」文案就靠它，jobs.py 同口径注释）。
 */
export type MosArtifactList = {
  root: string
  items: MosArtifact[]
  counts: Record<string, number>
  active: Record<string, { id: string; method: string }>
}
export function listArtifacts(): Promise<MosArtifactList> {
  return get('/api/artifacts')
}

/**
 * 独立发起任务（对照 api.js:171 createJob）。台账页「出可部署版本」=
 * method:'export' + params.chain_quant/chain_calib_n（融合成功后自动接量化）——
 * **走这个入口而不是任务详情的 /merge**（后端 jobs.py:1146 注释原话：产物页「出可部署版本」
 * 走的就是这里，并带同输入查重 409）。dry_run 供发起前预检（向导卡 PARITY-11 用，本页不传）。
 */
export function createMosJob(b: {
  method: string
  force?: boolean
  params?: Record<string, unknown>
  dataset_id?: string
  dry_run?: boolean
}): Promise<{ id: string; task_id?: string }> {
  return send('POST', '/api/jobs', b)
}

// ---------- 数据集（datasets 组，REQ-UNIFY-5） ----------
// 端点对照 mos/platform/backend/api/datasets.py：list:163、create:192、detail:301、
// origin:282、refs:426、delete:436（409 带引用清单文本）、items:493、
// add_items:1008（q/a 或 bulk）、upload:1034（multipart 字段名 file，32MB 上限）。
// 行结构对照 db.py:42-50：datasets(id,name,version,source,data_origin,n_items,pass_rate,status,created_at)。

export type MosDataset = {
  /** ds_xxx 字符串主键 */
  id: string
  name: string
  /** null = 未发布（冻结时生成 <name>-<version>.jsonl） */
  version?: string | null
  /** 数据源 id（列名是 source，不是 source_id —— db.py:43） */
  source?: string | null
  data_origin?: string | null
  status: string
  n_items?: number
  pass_rate?: number | null
  created_at?: string | null
  /** 仅列表聚合接口返回（datasets.py:163：一次分组查询 counts/media_counts）；GET 详情是裸行、无这两字段 */
  counts?: Record<string, number>
  media_counts?: Record<string, number>
}

export type MosDatasetItem = {
  id: string
  dataset_id: string
  q: string
  a: string
  status: string
  score?: number | null
  tags?: string | null
  human_edited?: number
  source_ref?: string | null
  media_kind?: string | null
  media_path?: string | null
  /** 有媒体字段的条目由后端探测文件是否还在（datasets.py:527）；false = 媒体文件已不在媒体库 */
  media_exists?: boolean
  /** 质检结论（qa_validate 写回，datasets.py:906-909）：pass/warn/fail，NULL=未检 */
  verdict?: string | null
  /** 质检问题清单 JSON 串 [{code,level,msg}]（datasets.py:906）；渲染层 JSON.parse 展示 */
  issues?: string | null
  validated_at?: string | null
  faithfulness?: number | null
  created_at?: string | null
}

/** items?paged=1 的分页壳（datasets.py:544-547） */
export type MosDatasetItemsPage = {
  items: MosDatasetItem[]
  total: number
  all_total: number
  counts: Record<string, number>
  limit: number
  offset: number
}

/** 导入结果；_insert_pairs 的去重口径：按归一化 q，同批内也去重（datasets.py:1114） */
export type MosDatasetAddResult = {
  ok: boolean
  inserted: number
  duplicated: number
  empty: number
  skipped: number
  total: number
  file?: string
}

export function listDatasets(): Promise<MosDataset[]> { return get('/api/datasets') }
export function getDataset(id: string): Promise<MosDataset> { return get(`/api/datasets/${encodeURIComponent(id)}`) }
export function createDataset(b: { name: string; source_id?: string; data_origin?: string }): Promise<{ id: string; source?: string }> {
  return send('POST', '/api/datasets', b)
}
export function deleteDataset(id: string): Promise<{ ok: boolean }> {
  return send('DELETE', `/api/datasets/${encodeURIComponent(id)}`)
}
/** 数据属性；human 需 mos 侧「像人标」核验，不通过直接 400（datasets.py:282-291），页面原样回显原因 */
export function setDatasetOrigin(id: string, dataOrigin: string): Promise<{ ok: boolean; data_origin: string }> {
  return send('POST', `/api/datasets/${encodeURIComponent(id)}/origin`, { data_origin: dataOrigin })
}
export function datasetItems(
  id: string,
  opt: { status?: string; q?: string; limit?: number; offset?: number } = {},
): Promise<MosDatasetItemsPage> {
  const p = new URLSearchParams({ paged: '1' })
  if (opt.status) p.set('status', opt.status)
  if (opt.q) p.set('q', opt.q)
  p.set('limit', String(opt.limit ?? 200))
  p.set('offset', String(opt.offset ?? 0))
  return get(`/api/datasets/${encodeURIComponent(id)}/items?${p.toString()}`)
}
export function addDatasetItems(id: string, b: { q?: string; a?: string; bulk?: string }): Promise<MosDatasetAddResult> {
  return send('POST', `/api/datasets/${encodeURIComponent(id)}/items`, b)
}
export function uploadDatasetItems(id: string, fd: FormData): Promise<MosDatasetAddResult> {
  return postForm(`/api/datasets/${encodeURIComponent(id)}/items/upload`, fd)
}
/**
 * 条目审核/编辑（BUG-PARITY-9-A ②③，对照 api.js:143）。
 * q/a 任一在场后端就置 human_edited=1（datasets.py:609）；q/a/status 真变了才作废
 * 已发布版本（同值保存不作废，datasets.py:615-621）；空 q/a 由后端 400 显式拒绝。
 */
export function patchDatasetItem(itemId: string, b: { q?: string; a?: string; status?: string }): Promise<{ ok: boolean; updated: string[] }> {
  return send('PATCH', `/api/datasets/items/${encodeURIComponent(itemId)}`, b)
}
/** 批量审核（②，api.js:144；只作用于本数据集内选中 id，datasets.py:569-594；同值不计更新） */
export function bulkDatasetItems(dsId: string, b: { ids: string[]; status: string }): Promise<{ ok: boolean; updated: number; counts: Record<string, number> }> {
  return send('PATCH', `/api/datasets/${encodeURIComponent(dsId)}/items/bulk`, b)
}
/** 发布冻结（④，api.js:149；0 条已批 400 硬门槛 datasets.py:1220；并发被改 409 重试 :1240） */
export function freezeDataset(id: string): Promise<{ ok: boolean; version: string; path: string; lines: number; basis: string; approved: number; total: number }> {
  return send('POST', `/api/datasets/${encodeURIComponent(id)}/freeze`, {})
}
/** 导出下载通道地址（⑦，api.js:150-151 两链接；未发布 export 端点 400 原文由浏览器呈现） */
export function datasetExportUrl(id: string): string {
  return mosChannelUrl(`/api/datasets/${encodeURIComponent(id)}/export`)
}
export function datasetExportRawUrl(id: string): string {
  return mosChannelUrl(`/api/datasets/${encodeURIComponent(id)}/export_raw`)
}
/** 删除前引用清单（⑥，api.js:132；_dataset_refs datasets.py:352——行带 blocked/warn/force 语义） */
export type MosDatasetRef = {
  /** model / eval / job / deployment */
  kind: string
  id: string
  name: string
  status?: string | null
  /** 非空=现在删不掉的原因（运行中/被模型引用），弹窗里删除钮禁用并做 title */
  blocked?: string | null
  /** 任务带门禁记录=删除需 force=1（后端连带删门禁，datasets.py:414-419） */
  force?: boolean
  warn?: string | null
}
export function datasetRefs(id: string): Promise<{ items: MosDatasetRef[]; total: number; counts: Record<string, number> }> {
  return get(`/api/datasets/${encodeURIComponent(id)}/refs`)
}
/** 批量质检（⑤，api.js:155；apply=true 时 fail 且草稿→rejected，datasets.py:871-875） */
export type MosValidateSummary = {
  ok: boolean
  checked: number
  pass: number
  warn: number
  fail: number
  by_code: Record<string, number>
  llm_calls: number
  applied: number
}
export function validateDataset(id: string, b: { mode: string; apply?: boolean }): Promise<MosValidateSummary> {
  return send('POST', `/api/datasets/${encodeURIComponent(id)}/validate`, b)
}
/** 单条质检（api.js:156；返回体=规则结论 issues/verdict/score，datasets.py:882-913） */
export function validateDatasetItem(itemId: string, b: { mode?: string } = {}): Promise<{ ok: boolean; verdict: string; issues: { code: string; level?: string; msg?: string }[]; score?: number }> {
  return send('POST', `/api/datasets/items/${encodeURIComponent(itemId)}/validate`, b)
}
/** 质检分布总览（datasets.py:915；verdicts 含 unchecked 桶，Vue :313-319 chips 数据源） */
export type MosQualityOverview = {
  total: number
  verdicts: Record<string, number>
  status: Record<string, number>
  by_code: Record<string, number>
}
export function datasetQuality(id: string): Promise<MosQualityOverview> {
  return get(`/api/datasets/${encodeURIComponent(id)}/quality`)
}
/**
 * 媒体地址通道化：media_path 存媒体根相对路径（也可能是外部 URL / 绝对路径，原样透传）。
 * 上游 /media/{path} 挂在根而非 /api（main.py:169）；代理 tail=/media/… → 上游 /media/…，
 * 所以拼 MOS_BASE 即可同时满足线上通道与开发态直连。
 */
export function mosMediaUrl(mediaPath?: string | null): string {
  const p = String(mediaPath || '')
  if (!p) return ''
  if (/^https?:\/\//i.test(p) || p.startsWith('/')) return p
  return `${MOS_BASE}/media/${p.replace(/^media\//, '')}`
}
// ---------- 公开语料（catalog 组）· BUG-PARITY-9-B ⑧ ----------
// 端点对照 mos/platform/backend/api/catalog.py:87-230 与 mos/platform/frontend/src/api.js:107-121。
// 路径字面量与老 api.js 逐字一致（通道口径见文件头）；单次导入上限 500 由后端夹
// （catalog.py:121 max(1,min(limit,500))），页面文案只提示不双标。

export type MosCatalogEntry = {
  id: string
  label: string
  source?: string
  repo?: string
  file?: string
  fmt?: string
  q?: string
  a?: string
  extra?: string
  desc?: string
  rows_hint?: number
  license?: string
  lang?: string
}

export type MosCatalogProbe = {
  ok: boolean
  /** 仓库内文件路径（探测块「文件」行，Vue :440） */
  file?: string
  url?: string
  rows?: number
  /** 撞单次下载字节上限=行数不完整（Vue :444-446 红条） */
  truncated?: boolean
  cap_mb?: number
  sample?: { q?: string; a?: string }
  error?: string
}

/**
 * 请求体口径=Vue catBody()（Datasets.vue:1633-1642）：q/a 非空才发；repo 在场用 source+repo
 * （高级自定义仓库/直链，忽略预设选择）、否则 entry_id；local_file 在场=走本地副本分批
 * （带 offset）；dataset_id 在场=追加进已有集、不新建。
 */
export type MosCatalogBody = {
  limit?: number
  name?: string
  q?: string
  a?: string
  source?: string
  repo?: string
  entry_id?: string
  local_file?: string
  offset?: number
  dataset_id?: string
}

export type MosCatalogImportResult = {
  ok: boolean
  dataset_id: string
  name: string
  fetched: number
  inserted: number
  source?: string
  /** 同名重复提醒（新建一份不覆盖，catalog.py:154-155） */
  dup?: { id: string; name: string } | null
  next_offset?: number | null
  total?: number | null
  note?: string
}

export type MosCatalogLocal = {
  name: string
  /** 语料目录相对路径（导入/删除都用它，catalog.py:216） */
  rel: string
  path?: string
  size_mb: number
  mtime: string
  rows?: number | null
  repo?: string
}

export function catalogDatasets(): Promise<MosCatalogEntry[]> { return get('/api/catalog/datasets') }
export function catalogProbe(b: MosCatalogBody): Promise<{ entry?: Record<string, string>; probe: MosCatalogProbe }> {
  return send('POST', '/api/catalog/probe', b)
}
export function catalogImport(b: MosCatalogBody): Promise<MosCatalogImportResult> {
  return send('POST', '/api/catalog/import', b)
}
/** 全量下载到本地（不设字节上限，大文件可能等几十秒；catalog.py:196-205） */
export function catalogDownload(b: MosCatalogBody): Promise<{
  ok?: boolean; rel: string; name?: string; size_mb?: number; rows?: number | null
}> {
  return send('POST', '/api/catalog/download', b)
}
export function catalogDownloads(): Promise<{ dir: string; items: MosCatalogLocal[] }> {
  return get('/api/catalog/downloads')
}
/** 删本地副本（限语料目录内；副本可重下，直接删，catalog.py:207-230） */
export function deleteCatalogDownload(rel: string): Promise<unknown> {
  return send('DELETE', '/api/catalog/downloads?rel=' + encodeURIComponent(rel))
}

// ---------- 评测（evals 组）· REQ-UNIFY-4 ----------
// 端点对照 mos/platform/backend/api/evals.py:73-181 与 mos/platform/frontend/src/api.js:191-197。
// 主键 ev_xxx 字符串；run 的题数走 query ?limit=（evals.py:135，不传沿用建单 eval_limit）。
// api_key 只进不出：服务端 _public_judge（evals.py:51）把 candidates/judge 里的 key 换成
// key_set 布尔再回传，前端永远拿不到明文，展示只能显示「已配置」。

export type MosEvalCandidate = {
  name: string
  base_url: string
  /** 空 = 从该端点 /v1/models 自动发现（evals.py:32） */
  model?: string
  /** 只随创建请求上行；响应里被服务端替换为 key_set */
  api_key?: string
  key_set?: boolean
}

export type MosEvalJudge = {
  /** local=平台本地 LLM / external=外部模型；留空 base_url/model/api_key 时服务端按设置解析（evals.py:36-40） */
  engine?: 'local' | 'external'
  base_url?: string
  model?: string
  api_key?: string
  key_set?: boolean
}

export type MosEvalSummary = { name: string; avg: number | null; win: number; n: number; err?: number }

export type MosEvalItemResult = {
  item_id: string
  q: string
  ref: string
  answers: Record<string, string | null>
  scores: Record<string, number | null>
  errors?: Record<string, string>
}

export type MosEvalResult = {
  summary?: MosEvalSummary[]
  items?: MosEvalItemResult[]
  /** 基准集体检：参考答案过短/为空的题，本次均分仅供参考（Evaluations.vue:70-77） */
  ref_warn?: { count?: number; samples?: { q?: string; ref?: string }[] }
}

export type MosEval = {
  /** ev_xxx 字符串主键 */
  id: string
  name: string
  dataset_id: string
  /** draft=未跑 / running=运行中 / done=已完成 / failed=失败 */
  status: string
  eval_limit?: number | null
  progress?: number | null
  error?: string | null
  candidates?: MosEvalCandidate[] | null
  judge?: MosEvalJudge | null
  result?: MosEvalResult | null
  /** 仅 getEval 详情带（list_evals 不带，evals.py:130）；列表轮询用 status 判断即可 */
  running?: boolean
  created_at?: string
  updated_at?: string
  n_items?: number | null
}

export type MosEvalCreate = {
  name: string
  dataset_id: string
  candidates: MosEvalCandidate[]
  judge?: MosEvalJudge
  /** 建单时确定的题数上限，服务端钳到 1–1000（evals.py:111） */
  limit?: number
}

export type MosJudgeSelfcheckCase = {
  name: string
  pass: boolean
  score: number | null
  expect: string
  verdict?: string
  reason?: string
}

export type MosJudgeSelfcheck = { ok: boolean; verdict: string; cases?: MosJudgeSelfcheckCase[] }

export function listEvals(): Promise<MosEval[]> { return get('/api/evals') }
export function getEval(id: string): Promise<MosEval> { return get(`/api/evals/${encodeURIComponent(id)}`) }
export function createEval(b: MosEvalCreate): Promise<{ id: string }> { return send('POST', '/api/evals', b) }
export function runEval(id: string, limit: number): Promise<{ ok: boolean; limit: number; status: string }> {
  return send('POST', `/api/evals/${encodeURIComponent(id)}/run?limit=${encodeURIComponent(String(limit))}`, {})
}
export function stopEval(id: string): Promise<{ ok: boolean; status: string }> {
  return send('POST', `/api/evals/${encodeURIComponent(id)}/stop`, {})
}
export function deleteEval(id: string): Promise<{ ok?: boolean }> { return send('DELETE', `/api/evals/${encodeURIComponent(id)}`) }
export function judgeSelfcheck(b?: { judge?: MosEvalJudge }): Promise<MosJudgeSelfcheck> {
  return send('POST', '/api/evals/judge-selfcheck', b || {})
}

// ---------- 标注项目（label-studio projects 组，REQ-UNIFY-6-2 入口最小闭环） ----------
// 端点对照 mos/platform/backend/api/label_studio.py：list:255（分页聚合回全量数组）、
// create:700（title 必填；label_config 空则用 DEFAULT_LABEL_CONFIG；tasks 失败会连带删项目）。
// 行结构对照 label_studio.py:277-291。工作台深链由调用方拼 /mos/label-studio/{id}。

export type MosLsProject = {
  id: number
  title: string
  created_at?: string
  /** REQ-UX-FLOW-1#5：LS updated（缺则回退 created_at），清理空题测试项目用 */
  last_activity?: string
  task_number: number
  annotated: number
  unannotated: number
  progress: number
  /** 平台内置画布是否支持该项目的 label_config；false 时工作台回退原生画布 */
  workbench?: boolean
  has_config?: boolean
  /** BUG-PARITY-8-B ⑧：label_config 里是否有标注控件（三态判定第二支，Vue:128-131；
   *  后端 label_studio.py:291 一直返回，此前类型声明漏了） */
  has_controls?: boolean
  mode?: string
}

export function listLsProjects(): Promise<MosLsProject[]> { return get('/api/label-studio/projects') }
export function createLsProject(b: { title: string; label_config?: string; tasks?: unknown[] }): Promise<{ id: number; title?: string }> {
  return send('POST', '/api/label-studio/projects', b)
}
/**
 * BUG-PARITY-8-A：删除项目（老版 api.js:274 同通道）。
 * 后端 label_studio.py:728：写锁等在途保存后删 LS 项目，并清同步映射/自动训练策略/
 * ai_runs 台账/项目级 AI 配置。失败原文（引擎 404、写锁超时 503 等）经 call() 的
 * FastAPI {detail} 转译进 e.message——调用方必须逐字回显，不许兜底文案吞掉。
 */
export function deleteLsProject(pid: number): Promise<{ ok?: boolean; id?: number }> {
  return send('DELETE', '/api/label-studio/projects/' + pid)
}
/**
 * 标注项目就地同步成训练集（BUG-PARITY-9-B ① 向导 annotation 模式；老 api.js:309 同通道，
 * 后端 label_studio.py:2584）。向导固定发 {name, dataset_id:'', freeze:false, status:'draft'}：
 * 不传 status 会被当 approved 绑定并连带自动 freeze（用户没点发布却已发布，Datasets.vue:1143-1145
 * 注释原话）；freeze:true+status:'draft' 组合被 _require_approved_for_freeze :2549 显式 400。
 * 同名（且 source='label_studio'）复用同一数据集=重复同步不建重复集（:2607-2613），
 * 所以调用方名称必须用「标注 · {title} (#{id})」模板（applyProjectName，Vue :1111-1113）。
 */
export function lsToDataset(pid: number, b: {
  name?: string; dataset_id?: string; status?: string; freeze?: boolean; data_origin?: string
}): Promise<{
  inserted: number; updated?: number; removed?: number; dataset_id: string; source?: string
  freeze?: unknown; freeze_skipped?: string; freeze_error?: string
}> {
  return send('POST', '/api/label-studio/projects/' + pid + '/to-dataset', b)
}

/**
 * BUG-PARITY-9-C ⑨：项目↔数据集同步映射（老 api.js:269 / 新 wsApi.ts:71 同通道；
 * 后端 label_studio.py:2447）。items=绑定行（project_id/dataset_id），last=按
 * project_id（字符串键）的近一次同步摘要 {at, inserted, updated, removed, frozen_lines}。
 * 数据集侧「来自标注」回链与 isAnnotationData 的 annotationProjectOf 反查都以它为准；
 * 读失败（引擎离线/缺表）由调用方兜空、不打断列表（MosLabels.tsx:207-214 同型容错）。
 */
export type MosLsSyncEntry = { project_id: number; dataset_id: string; [k: string]: unknown }
export type MosLsSyncLast = { at?: string; inserted?: number; updated?: number; removed?: number; frozen_lines?: number }
export function lsSyncMap(): Promise<{ items: MosLsSyncEntry[]; last: Record<string, MosLsSyncLast> }> {
  return get('/api/label-studio/sync-map')
}

/**
 * BUG-PARITY-8-B ⑥：连接设置（老 api.js:261-262 同通道；后端 label_studio.py
 * GET:222 / POST:234）。**Token 服务端专用，响应不含 token，只有 token_set 布尔**
 * （_public_cfg:215-220）；留空=不修改由服务端判（POST:238 `if body.token != ""`），
 * 所以提交空串是「保持原值」而不是「清空」。connected 是服务端实测引擎 /health 的结果。
 */
export type MosLsConfig = {
  url?: string
  ml_url?: string
  project_id?: number | string | null
  token_set?: boolean
  connected?: boolean
  [k: string]: unknown
}
export function lsConfig(): Promise<MosLsConfig> { return get('/api/label-studio/config') }
export function saveLsConfig(b: {
  url?: string; token?: string; ml_url?: string; project_id?: number | null
}): Promise<MosLsConfig> {
  return send('POST', '/api/label-studio/config', b)
}

/**
 * BUG-PARITY-8-B ⑦：AI 标注台账（老 api.js:296-300 同参数口径：pid=0 时**不带** pid 查询串
 * =全局；后端 ai-stats:1480 / ai-runs:1510）。days 服务端钳 1–365、limit 钳 1–500。
 */
export type MosLsAiStats = {
  runs?: number
  tasks?: number
  boxes?: number
  results?: number
  adopted?: number
  last_at?: string | null
  by_engine?: Record<string, unknown>[]
  by_day?: Record<string, unknown>[]
  [k: string]: unknown
}
export type MosLsAiRun = {
  id: number
  created_at?: string
  project_id?: number
  task_id?: number | string
  engine?: string
  model_version?: string | null
  boxes?: number
  results?: number
  adopted?: number | boolean
  [k: string]: unknown
}
export function aiStats(pid = 0, days = 30): Promise<MosLsAiStats> {
  const p = new URLSearchParams()
  if (pid) p.set('pid', String(pid))
  p.set('days', String(days))
  return get(`/api/label-studio/ai-stats?${p.toString()}`)
}
export function aiRuns(pid = 0, limit = 100): Promise<{ items: MosLsAiRun[]; count: number }> {
  const p = new URLSearchParams()
  if (pid) p.set('pid', String(pid))
  p.set('limit', String(limit))
  return get(`/api/label-studio/ai-runs?${p.toString()}`)
}

// ---------- 评测页辅助组（datasets / deployments / settings 只读取候选值）----------
// 对照 Evaluations.vue:278-300：数据集下拉、端点候选、裁判默认值。字段只声明用到的，
// 其余保持透传（mos 行是 SELECT *，加字段不用动前端类型）。

export type MosDatasetLite = { id: string; name: string; n_items?: number | null; [k: string]: unknown }
export type MosDeploymentLite = {
  id?: string
  model_id?: string
  model_name?: string | null
  model_version?: string | null
  port?: number | null
  base_url?: string | null
  alive?: boolean
  [k: string]: unknown
}

// listDatasets 去重：以 REQ-UNIFY-5 全量版（MosDataset[]，见上方 datasets 组）为准，
// MosDataset 为 type alias、字段覆盖 Lite 口径，MosEvaluations 直接复用。
export function listDeployments(): Promise<MosDeploymentLite[]> { return get('/api/deployments') }
/** 删除模型服务（BUG-PARITY-9-A ⑥ 引用清单弹窗就地解除用；对照 api.js:206；后端有运行中守卫） */
export function deleteDeployment(id: string): Promise<{ ok?: boolean }> {
  return send('DELETE', `/api/deployments/${encodeURIComponent(id)}`)
}
/** GET /api/settings 全量键值（api.js:213）；密钥字段服务端已掩码（stubs.py SECRET_MASK） */
export function getSettings(): Promise<Record<string, string>> { return get('/api/settings') }
/** PATCH /api/settings 批量改设置（api.js:232，stubs.py:67：只接受已存在的键，train.defaults=db.py:231 预置；
 *  BUG-PARITY-11「存为默认参数」=读现值按 method 覆写后整串 JSON 回写，Vue Jobs.vue:953-957） */
export function patchSettings(b: Record<string, string>): Promise<{ ok: boolean; updated: string[] }> {
  return send('PATCH', '/api/settings', b)
}
/** PATCH /api/settings 批量改设置（api.js:232，stubs.py:67：只接受已存在的键，train.defaults=db.py:231 预置；
 *  BUG-PARITY-11「存为默认参数」=读现值按 method 覆写后整串 JSON 回写，Vue Jobs.vue:953-957） */

// ---------- 媒体库（media 组，REQ-UNIFY-7） ----------
// 端点对照 mos/platform/backend/api/media.py：列表 :745、上传 :659（multipart file+kind+folder；
// 0 字节 400、>MEDIA_MAX_MB(默认 500MB) 413）、缩略图 :920、删除 :1048；行结构=_meta_from_info :203。
// 本组路径带完整上游前缀（/api/media…，见 upstreamUrl）：通道模式落 /api/mos/api/media/…，
// 与 MosProxyController 单测契约同形；models/jobs/datasets 存量组的旧式路径本卡不动。
// 文件本体只经 mos media_store（FW-5 契约），前端不另造存储通道。
// REQ-UX-FLOW-1#3：桶名/目录不硬编码——真实配置走 getSystemInfo() 的 storage.media（svc 代持可读，
// auth.py SHARED_READ_PREFIXES 含 /api/system；现网实测 store=local）。

export type MosMediaStoreInfo = {
  store?: string
  root?: string
  endpoint?: string
  bucket?: string
  prefix?: string
  ok?: boolean
  error?: string
}
export function getSystemInfo(): Promise<{ storage?: { media?: MosMediaStoreInfo } & Record<string, unknown> }> {
  return get('/api/system/info')
}

export type MosMediaItem = {
  /** 媒体根相对路径，删除/详情都以它为键 */
  path: string
  name: string
  /** audio/video/image/text/other（media.py:81 KINDS） */
  kind: string
  size: number
  mtime: string
  /** 上游静态读穿地址（已 URL 引号化；拼通道请传 path 给 mosMediaUrl，勿直接用本字段） */
  url: string
  /**
   * 标注可用地址（media.py:221 _ls_url :102-122）：后端按 MEDIA_LS_MODE 决定形态——
   * local 模式是 LS 的相对地址 /data/local-files/?d=media/<rel>，S3/公网模式是绝对 URL。
   * **勿在前端重拼**（重拼会把模式判断抄第二份，两边漂移）；工作台导入建任务直接写这个字段。
   */
  ls_url: string
  content_type?: string
}

export type MosMediaList = {
  items: MosMediaItem[]
  total: number
  offset: number
  limit: number
  /** 全库类型计数，不随 kind/q 过滤变化（media.py:745 注释口径） */
  counts: Record<string, number>
  all_total: number
}

export const MOS_MEDIA_KINDS: { value: string; label: string }[] = [
  { value: 'image', label: '图片' },
  { value: 'audio', label: '音频' },
  { value: 'video', label: '视频' },
  { value: 'text', label: '文本' },
  { value: 'other', label: '其他' },
]

export function listMedia(opt: { kind?: string; q?: string; limit?: number; offset?: number } = {}): Promise<MosMediaList> {
  const p = new URLSearchParams()
  if (opt.kind) p.set('kind', opt.kind)
  if (opt.q) p.set('q', opt.q)
  p.set('limit', String(opt.limit ?? 200))
  p.set('offset', String(opt.offset ?? 0))
  return get(`/api/media?${p.toString()}`)
}

/**
 * 当前筛选（kind/q）的**全量**列表——批量条「全选」必须覆盖所有页（BUG-PARITY-13-B，
 * 老版 Media.vue:1469 toggleAll 是对整份已载数据操作，新版分页后不能退化成「只勾当前页」）。
 * 分页语义走后端统一口径（media.py:445 _page_params）：**limit=0=不分页返回全部**、
 * limit>0 封顶 1000、负数 400——所以这里传 0，而不是猜一个「够大」的 limit。
 */
export function listAllMedia(opt: { kind?: string; q?: string } = {}): Promise<MosMediaList> {
  const p = new URLSearchParams()
  if (opt.kind) p.set('kind', opt.kind)
  if (opt.q) p.set('q', opt.q)
  p.set('limit', '0')
  p.set('offset', '0')
  return get(`/api/media?${p.toString()}`)
}

/** 单个文件上传（kind=auto 由后端按扩展名/MIME 归类，folder 留空=按 kind/年/月 自动归档）。 */
export function uploadMedia(file: File, opt: { kind?: string; folder?: string } = {}): Promise<MosMediaItem> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('kind', opt.kind ?? 'auto')
  if (opt.folder) fd.append('folder', opt.folder)
  return postForm('/api/media/upload', fd)
}

/** 列表缩略图（media.py:920，服务端按路径+mtime+size 缓存）；非图片/无派生图会 404，前端 onError 回退图标。 */
export function mosMediaThumbUrl(path: string, size = 160): string {
  return upstreamUrl(`/api/media/thumb?path=${encodeURIComponent(path)}&size=${size}`)
}

/**
 * 删除单个媒体（media.py:1049）。被数据集条目引用时后端回 409
 * （call() 转译后 err.status=409、err.message=「这个文件被 N 条数据集条目引用…」原文），
 * 调用方**必须二次确认后再带 force=true 重发**——老版教训（Media.vue:1051 注释）：
 * 确认框写着「引用会失效」但请求从不带 force，被引用的文件在页面上永远删不掉。
 */
export function mediaDelete(path: string, force = false): Promise<{ ok: boolean; path: string }> {
  return send('DELETE', `/api/media?path=${encodeURIComponent(path)}${force ? '&force=1' : ''}`)
}

/**
 * 文档转可标注（BUG-PARITY-13-D，端点=mos media.py:766；老 api.js mediaRender :246 同形）。
 * PDF 逐页转 PNG：回 kind=pdf + pages[]（每页含媒体根相对 path、上游 url、标注可用 ls_url）；
 * Word 转 HTML：回 kind=docx + html（正文片段，建任务用）+ html_path/url/ls_url + warnings。
 * 老版教训（Media.vue:1541-1544）：转换慢，关掉再转另一份时**调用方必须带 seq 竞态守卫**，
 * 只认最后一次，否则 A 的结果会挂到 B 的标题下。
 */
export type MosRenderPage = {
  page: number
  /** 转换产物在媒体根下的相对路径（拼通道给 mosMediaUrl，勿直接用 url） */
  path: string
  url: string
  ls_url: string
  width?: number
  height?: number
}
export type MosRenderResult = {
  kind: 'pdf' | 'docx'
  source: string
  /** pdf：原总页数 / 实际转出页数（页数上限 max_pages，media.py:789） */
  total_pages?: number
  rendered?: number
  pages?: MosRenderPage[]
  /** docx：mammoth 正文 HTML + 落盘路径/地址 + 转换提示 */
  html?: string
  html_path?: string
  url?: string
  ls_url?: string
  warnings?: string[]
}
export function mediaRender(body: { path: string; dpi?: number; max_pages?: number }): Promise<MosRenderResult> {
  return send('POST', '/api/media/render', body)
}

// ---------- 目录树与目录管理（BUG-PARITY-13-C，端点对照 mos media.py）----------
// tree=当前层（子目录+文件，可分页/按 kind 过滤），subtree=某目录递归全部文件
// （「选整个文件夹」用），dirs=全库目录路径（上传下拉用），folders 建/改/删三个写口。

/** 目录行（tree.folders；media_store.py:152/453）：files=含子目录的**递归**数 */
export type MosMediaFolder = {
  name: string
  path: string
  /** 该目录下递归文件数（含子目录）——勾选框半选态判据用它 */
  files: number
  size: number
  mtime?: string
  has_children?: boolean
}

export type MosMediaCrumb = { name: string; path: string }

/** GET /media/tree 响应（media.py:480，_tree_page :461 分页后附 files_total/has_more） */
export type MosMediaTree = {
  path: string
  name: string
  crumbs: MosMediaCrumb[]
  folders: MosMediaFolder[]
  files: MosMediaItem[]
  /** 含子目录的本目录文件总数（media.py:522 total_files：子目录递归数 + 本层文件） */
  total_files: number
  total_size: number
  files_total?: number
  files_offset?: number
  files_limit?: number
  has_more?: boolean
}

/**
 * 当前目录的直接子目录 + 文件（media.py:480）。limit=0 不分页（返回全部，兼容树侧栏）；
 * kind 在分页前过滤（保证「第 2 页」仍是该类型的第 2 页，media.py:461 _tree_page）。
 * 老 api.js:239 同口径：(path, limit, offset, kind)。
 */
export function mediaTree(path = '', limit = 0, offset = 0, kind = ''): Promise<MosMediaTree> {
  const p = new URLSearchParams()
  p.set('path', path)
  p.set('limit', String(limit))
  p.set('offset', String(offset))
  if (kind) p.set('kind', kind)
  return get(`/api/media/tree?${p.toString()}`)
}

/** 某目录下的全部文件（递归，media.py:538）；「选整个文件夹」按前缀一次取回，不逐层点。 */
export function mediaSubtree(path = '', kind = ''): Promise<{ path: string; items: MosMediaItem[]; total: number }> {
  const p = new URLSearchParams()
  p.set('path', path)
  if (kind) p.set('kind', kind)
  p.set('limit', '0')
  return get(`/api/media/subtree?${p.toString()}`)
}

/** 全库目录路径（不含隐藏目录，media.py:560），上传弹窗「上传到」下拉用。 */
export function mediaDirs(): Promise<{ dirs: string[] }> {
  return get('/api/media/dirs')
}

/** 建目录（media.py:576）：parent+name，多级 name（如 a/b）会一次建完；保留名 400、已存在 409。 */
export function mediaCreateFolder(body: { parent?: string; name: string }): Promise<{ ok: boolean; path: string; name: string }> {
  return send('POST', '/api/media/folders', { parent: body.parent || '', name: body.name })
}

/** 改名（media.py:601）：同层改当前一级，里面的文件和子目录跟着走；返回新 path/old_path。 */
export function mediaRenameFolder(body: { path: string; name: string }): Promise<{ ok: boolean; path: string; name: string; old_path: string; refs_updated?: number }> {
  return send('POST', '/api/media/folders/rename', body)
}

/** 删目录（media.py:637）：**只能删空目录**，非空回 409「目录不为空，先删除或移动里面的文件」。 */
export function mediaDeleteFolder(path: string): Promise<{ ok: boolean; path: string }> {
  return send('DELETE', `/api/media/folders?path=${encodeURIComponent(path)}`)
}
