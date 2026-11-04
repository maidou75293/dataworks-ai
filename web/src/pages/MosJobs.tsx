import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { MOS_DATA_ORIGIN_PLACEHOLDER } from '../terms'
import {
  Alert, Button, Card, Checkbox, Divider, Drawer, Input, InputNumber, Modal, Pagination,
  Progress, Select, Space, Steps, Tag, Typography, message,
} from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import {
  createJob, deleteArtifact, deleteJob, getJob, getSettings, jobGate, jobLog, jobPipeline,
  jobSchema, jobTasks, listArtifacts, listDatasets, listDeployments, mergeTaskStage,
  patchSettings, quantizeTaskStage, registerModel, resumeJob, runJobGate, stopJob,
} from '../api/mos'
import type {
  MosArtifact, MosDataset, MosDeploymentLite, MosGate, MosJobDetail, MosJobStage, MosJobTask,
  MosPipeline, MosSchemaGroup, MosSchemaParam,
} from '../api/mos'

/**
 * 训练任务（MOS）——REQ-UNIFY-3 重写页：mos/platform/frontend/src/views/Jobs.vue（1427 行）。
 *
 * REQ-UNIFY-3 首批＝列表 / 详情抽屉 / 日志流 / 停止重试四件事；「发起任务」4 步向导
 * （Jobs.vue:102-262）随 BUG-PARITY-11 落地（本文件 JobWizard 段：4 步可回退 + 末步 dry-run 闸
 * + 预设一键套用 + 存为默认参数三级合并 + 草稿语义，交互稿 docs/交互稿-发起任务向导-20261101.md）。
 * UI 口径按 merge-review-a.md Jobs 行建议向中台 Instances 对齐（列表+详情抽屉+日志）。
 *
 * 对照锚点（行号均为 Jobs.vue）：列表 4-99；向导 102-262（逻辑 513-586,871-1021）；
 * 详情 264-434（状态 285 / 产物链 312 / 只读配置 370 / 门禁 388 / 日志 423）；
 * 门禁弹层 437-469；登记产物 471-487；轮询 1350-1368；停止 1153-1168（逐阶段停，queued 400 不阻断后续）；
 * 续跑 1116-1125；删除 1169-1198（409「门禁记录」→ force=1 二次确认）；产物链槽位 672-733；
 * argv 反解只读配置 734-831。深链：/mos/jobs?job=<根任务或阶段 id>（对照 Vue /jobs/:id）；
 * /mos/jobs?method=&dataset=（「去训练」链）BUG-PARITY-11 起＝真开向导并带入参数
 * （对照 Jobs.vue:1324-1347；-10 的诚实横幅仅剩 export/quant 分支）。
 *
 * BUG-PARITY-12 跨页去向：产物台账＝本页内「全部产物」视图（?view=artifacts，对照老版
 * 独立路由 /artifacts——App.tsx 属共享文件不占路由表，去向方案见
 * docs/对拍方案-产物台账部署训练环境三页去向-20261101.md）；?register=<产物路径> 深链
 * 自动开台账并弹登记窗（对照 Artifacts.vue:179-183）。「部署」「训练环境」两页不迁移，
 * 入口一律诚实提示、不发假链接。
 */

const STATUS_CN: Record<string, string> = { queued: '排队', running: '运行中', finished: '已完成', failed: '失败', stopped: '已停止', unknown: '失联' }
const STATUS_COLOR: Record<string, string> = { queued: 'gold', running: 'blue', finished: 'green', failed: 'red', stopped: 'orange', unknown: 'default' }
const MCN: Record<string, string> = {
  probe: '环境自检', sft: '指令微调', pt: '继续预训练', dpo: '偏好对齐',
  gkd: '白盒蒸馏', export: '融合导出', quant: 'AWQ 量化', embed: '检索适配',
}
// 产物链阶段名（Jobs.vue:647）
const STAGE_NAMES: Record<string, string> = {
  sft: '训练', pt: '训练', dpo: '训练', gkd: '训练',
  embed: '检索适配', export: '融合', quant: '量化', probe: '环境自检',
}
const TRAIN_METHODS = ['sft', 'pt', 'dpo', 'gkd']
const PARAM_CN: Record<string, string> = {
  method: '方法', base: '基座模型', model: '模型', adapters: 'LoRA 适配器',
  dataset: '训练集', out_dir: '输出目录', merged: '融合后的模型目录',
  calib: '校准集', calib_n: '校准样本数', awq_skip: '跳过量化的模块',
  teacher_model: '教师模型', ref_model: '参考模型', gpu_ids: '使用显卡',
}
// 引擎层 flag 名与 schema key 的别名表（Jobs.vue:737-740）
const ARG_ALIAS: Record<string, string> = {
  '--output_dir': 'out_dir', '--dataset': 'dataset',
  '--adapters': 'adapters', '--lora_dir': 'adapters', '--merge_lora': 'merge_lora',
}
const SHORT_KEYS = ['base', 'merged', 'calib', 'adapters']

// —— 发起任务向导（BUG-PARITY-11，Jobs.vue:518 / :559-562 / :565-581）——
const WIZ_STEPS = ['选任务', '调参数', '产物', '核对并确认']
// 发起只列「训练类」方法：融合/量化已并入任务的产物阶段（Vue launchMethods :559-562 同表）
const LAUNCH_KEEP = ['sft', 'pt', 'dpo', 'gkd', 'embed']
// 产物形态三卡（Vue shapeOptions :565-572 逐字：t 标题 / d 描述 / out 产物行）
const SHAPE_OPTIONS: { v: string; t: string; d: string; out: string }[] = [
  { v: 'adapter', t: '只留 LoRA 适配器', d: '体积最小，适合继续调参 / 多次实验', out: '适配器（需融合才能部署）' },
  { v: 'merged', t: '融合成完整模型（默认）', d: 'bf16 完整权重，可直接评测 / 部署', out: '适配器 + 融合模型' },
  { v: 'merged_quant', t: '融合 + AWQ 4bit 量化', d: '显存最省，直接可部署（多花几分钟）', out: '适配器 + 融合 + AWQ 4bit' },
]
const SHAPE_LABEL: Record<string, string> = {
  adapter: '只留 LoRA 适配器', merged: '融合成完整模型', merged_quant: '融合 + AWQ 4bit 量化',
}
const WIZ_NEXT_LABEL: Record<number, string> = { 0: '下一步：参数', 1: '下一步：产物', 2: '下一步：确认' }
const ART_DEFAULT = { shape: 'merged', quant_method: 'awq', calib_n: 128, calib_dataset_id: '' }
const MUT12: React.CSSProperties = { fontSize: 12, color: '#888' }
const WIZ_NOTE: React.CSSProperties = {
  fontSize: 12, color: '#888', background: '#fafafa', border: '1px solid #f0f0f0',
  borderRadius: 6, padding: '6px 10px', marginTop: 10,
}

// 参数字段类型映射（ConfigPanel.vue kindOf/toOpt :125-137 同式）
const WIZ_NUM_KINDS: Record<string, 1> = { int: 1, float: 1, number: 1 }
function wizKindOf(t?: string): 'bool' | 'select' | 'password' | 'textarea' | 'number' | 'text' {
  const k = t || 'text'
  if (k === 'bool' || k === 'boolean') return 'bool'
  if (k === 'select') return 'select'
  if (k === 'password') return 'password'
  if (k === 'textarea') return 'textarea'
  if (WIZ_NUM_KINDS[k]) return 'number'
  return 'text'
}
const wizToOpt = (o: unknown) => (o && typeof o === 'object'
  ? { value: String((o as any).value), label: (o as any).label != null ? String((o as any).label) : String((o as any).value) }
  : { value: String(o), label: String(o) })

const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }

const statusCn = (s: string) => STATUS_CN[s] || s
const methodCn = (m?: string) => MCN[m || ''] || m || ''
const stageName = (m?: string) => STAGE_NAMES[m || ''] || methodCn(m)
const stageGlyph = (s: MosJobStage) => (s.status === 'finished' ? '✓'
  : s.status === 'running' ? (s.pct != null ? `${Math.round(s.pct)}%` : '…')
    : s.status === 'failed' ? '✕' : (s.status === 'queued' ? '…' : '·'))

/** 后端从日志反解的真实百分比优先，jobs.progress 只有 0/100（Jobs.vue:1286） */
function livePct(j?: { pct?: number | null; progress?: number | null } | null) {
  const v = j && j.pct != null ? j.pct : j?.progress
  return Math.max(0, Math.min(100, Math.round(Number(v) || 0)))
}
function fmtLoss(v?: number | string | null) {
  const n = Number(v)
  return isFinite(n) ? n.toFixed(4) : String(v)
}
const baseName = (p?: string) => String(p || '').split('/').filter(Boolean).pop() || ''

function rawBaseOf(j?: { base?: string; params?: string } | null) {
  try { return (j && j.base) || JSON.parse((j && j.params) || '{}').base || '' } catch { return '' }
}
function rawDsOf(j?: { dataset?: string; params?: string } | null) {
  if (j && j.dataset) return j.dataset
  try { return JSON.parse((j && j.params) || '{}').dataset || '' } catch { return '' }
}
const dsOf = (j?: { dataset?: string; params?: string } | null) =>
  baseName(rawDsOf(j)).replace(/\.(jsonl|json|tsv|txt|csv)$/i, '').replace(/-v\d{8}-\d{4,6}$/, '')

/** 文件名反查数据集 id（Jobs.vue:771-786 逐字口径）：任务表只存了数据集文件路径，
 *  用「名称+发布版本」把文件还原回数据集 ID，让卡片能点回它实际吃的这份数据；
 *  发布文件名把名字里的空格/标点换成下划线（后端 _dataset_jsonl 的 safe 规则），
 *  这里用同一条规则把名字也规范化再比。老任务被重新发布后退回按名称匹配。 */
const dsSafe = (s: unknown) => String(s ?? '').replace(/[^\w一-鿿-]+/g, '_').slice(0, 40)
function dsIdOf(j: { dataset?: string; params?: string } | null | undefined, allDs: MosDataset[]): string {
  const raw = rawDsOf(j)
  if (!raw || !allDs.length) return ''
  const file = baseName(raw).replace(/\.(jsonl|json|tsv|txt|csv)$/i, '')
  const exact = allDs.find((d) => d.version && file === dsSafe(d.name) + '-' + d.version)
  if (exact) return exact.id
  const stem = file.replace(/-v\d{8}-\d{4,6}(?:-.*)?$/, '')
  const byName = allDs.find((d) => dsSafe(d.name) === stem || dsSafe(d.name) === file)
  return byName ? byName.id : ''
}

/** 「全部产物」台账排序（Artifacts.vue:157-160 逐字）：最终产物（量化后完整模型）排最前，
 *  其余按时间倒序；不做分组（用户 2026-10-08：要么别给中间物，要么都平铺都行）。 */
const KIND_RANK: Record<string, number> = { awq: 0, merged: 1, output: 2 }
const byFinalFirst = (a: MosArtifact, b: MosArtifact) =>
  ((KIND_RANK[a.kind || ''] ?? 9) - (KIND_RANK[b.kind || ''] ?? 9))
  || String(b.mtime || '').localeCompare(String(a.mtime || ''))

/** 「去部署」去向（BUG-PARITY-12 三页去向之一）：老版 /deploy=训练主机本机起 vLLM 进程，
 *  新平台形态不迁该页（docs/对拍方案-产物台账部署训练环境三页去向-20261101.md）。
 *  不许跳不存在的路由，也不许假装成能用的链接——诚实指当前真路径（模型库→发布到推理端）。 */
function deployHonestText(modelId?: string | null) {
  return 'MOS 部署页（训练主机本机模型服务）未迁移；当前发布方式=模型库 →「发布到推理端」，'
    + '发布后可在 StreamVision 配置页选用' + (modelId ? `（模型 ${modelId}）` : '')
}

function parseParams(j?: { params?: string } | null): Record<string, any> {
  try { return JSON.parse((j && j.params) || '{}') } catch { return {} }
}

/** 卡片耗时（Jobs.vue:1294-1303）：running 时 updated_at 停在起跑时刻，用当前时间 */
function durOf(j?: { created_at?: string | null; updated_at?: string | null; status?: string } | null) {
  if (!j || !j.created_at) return ''
  const end = j.status === 'running' ? new Date() : new Date(String(j.updated_at || '').replace(' ', 'T'))
  const s = (end.getTime() - new Date(String(j.created_at).replace(' ', 'T')).getTime()) / 1000
  if (!isFinite(s) || s < 1) return ''
  if (s < 60) return `${Math.round(s)} 秒`
  if (s < 3600) return `${Math.floor(s / 60)} 分 ${Math.round(s % 60)} 秒`
  return `${Math.round(s / 360) / 10} 小时`
}

function Kv({ k, title, hook, children }: { k: string; title?: string; hook?: string; children: ReactNode }) {
  return (
    // title 挂整行：缺值行的参数说明要悬浮在任何位置都能看到（老版 ConfigPanel 行级 tooltip）
    <div data-rokey={hook} title={title} style={{ display: 'flex', gap: 8, fontSize: 12, lineHeight: '22px' }}>
      <span style={{ color: '#888', flex: '0 0 60px' }}>{k}</span>
      <span title={title} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
        {children}
      </span>
    </div>
  )
}

type Slot = {
  key: string
  name: string
  job: MosJobStage | null
  artifacts: MosArtifact[]
  tag: string
  tagText: string
  state: string
  sub: string
  canMerge: boolean
  canQuant: boolean
}

export default function MosJobs() {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()

  // —— 列表态 ——
  const [tasks, setTasks] = useState<MosJobTask[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<(Error & { status?: number }) | null>(null)
  const [jobsQuery, setJobsQuery] = useState('')
  const [jobsStatus, setJobsStatus] = useState('')
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)

  // —— 详情抽屉态 ——
  const [current, setCurrent] = useState<MosJobDetail | null>(null)
  const [pipeline, setPipeline] = useState<MosPipeline>({ task: null, task_id: '', stages: [], artifacts: [] })
  const [schemaGroups, setSchemaGroups] = useState<MosSchemaGroup[]>([])
  const [argMeta, setArgMeta] = useState<Record<string, { label: string; key: string }>>({})
  const [log, setLog] = useState('')
  const [showLog, setShowLog] = useState(false)
  const [autoFollow, setAutoFollow] = useState(true)
  const [resuming, setResuming] = useState(false)
  const [acting, setActing] = useState(false)

  // —— 评测门禁 ——
  const [gate, setGate] = useState<MosGate | null>(null)
  const [gateOpen, setGateOpen] = useState(false)
  const [gateForm, setGateForm] = useState({ eval_dataset: '', limit: 10, threshold: 0.3, max_new_tokens: 128 })

  // —— 产物登记 / 阶段日志 ——
  const [regOpen, setRegOpen] = useState(false)
  const [reg, setReg] = useState({ path: '', name: '', base: '', method: '', notes: '', kind: '' })
  const [stageLogs, setStageLogs] = useState<Record<string, string>>({})
  const [openStage, setOpenStage] = useState('')

  // —— 「全部产物」台账视图（BUG-PARITY-12，Artifacts.vue 整体回搬；?view=artifacts）——
  const [arts, setArts] = useState<MosArtifact[]>([])
  const [artsRoot, setArtsRoot] = useState('')
  const [artsActive, setArtsActive] = useState<Record<string, { id?: string; method?: string }>>({})
  const [artsLoading, setArtsLoading] = useState(false)
  const [artsError, setArtsError] = useState('')
  const [deps, setDeps] = useState<MosDeploymentLite[]>([])
  const [merging, setMerging] = useState('')
  const [artsPage, setArtsPage] = useState(1)
  const [artsSize, setArtsSize] = useState(20)
  // 老版 regDeepLinkDone（Artifacts.vue:154,180-183）：同一路径的 ?register= 只自动开一次窗
  const regDeepLinkRef = useRef('')

  // —— 详情「本次配置」只读全量呈现（Jobs.vue roGroups :787-795 + ConfigPanel 折叠/搜索）——
  const [roQuery, setRoQuery] = useState('')
  const [roClosed, setRoClosed] = useState<Record<string, boolean>>({})

  // —— 「去训练」深链（BUG-PARITY-10 → 11 兑现）——
  // 发出端（工作台同步/预测完成条 WsDialogs.tsx:160,614 + MosLabels.tsx:789）按老版契约发 ?method=&dataset=。
  // BUG-PARITY-11 起：训练类深链＝真开向导并带入参数（applyTrainDeepLink，对照 Jobs.vue:1333-1347）；
  // export/quant 仍走诚实横幅（产物台账页缺位=PARITY-12，不能像老版跳不存在的 /artifacts），
  // URL 参数一律原样保留（-10 契约「参数语义必须保留」）。
  const [deepLinkHint, setDeepLinkHint] = useState<{ m: string; ds: string } | null>(null)
  const [deepLinkClosed, setDeepLinkClosed] = useState(false)
  const deepLinkHandledRef = useRef('')

  // —— 发起任务 4 步向导态（BUG-PARITY-11，Vue showForm/jobStep/form/artForm/presets :513-545）——
  // 草稿语义照 Vue：openForm 只复位 artForm/步骤/dry（:895-897）、closeForm 只清步骤/dry（:903-908），
  // method/dataset_id/params 跨开关保留=「上次填的还在」；**不许顺手 clear**（parity11 A11 锁）。
  const [wizOpen, setWizOpen] = useState(false)
  const [wizStep, setWizStep] = useState(0)
  const [wizForm, setWizForm] = useState<{ method: string; dataset_id: string; params: Record<string, any> }>({
    method: 'sft', dataset_id: '', params: {},
  })
  const [wizGroups, setWizGroups] = useState<MosSchemaGroup[]>([])
  const [wizPresets, setWizPresets] = useState<Record<string, Record<string, unknown>>>({})
  const [methodsMap, setMethodsMap] = useState<Record<string, string>>({})
  const [allDs, setAllDs] = useState<MosDataset[]>([])
  const [artForm, setArtForm] = useState({ ...ART_DEFAULT })
  const [dry, setDry] = useState('')
  const [dryError, setDryError] = useState('')
  const [dryChecking, setDryChecking] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  // 参数编辑器的组折叠/搜索态（ConfigPanel initial-open=默认全开，open[key]!==false :196）
  const [wizQuery, setWizQuery] = useState('')
  const [wizClosed, setWizClosed] = useState<Record<string, boolean>>({})
  const [advShown, setAdvShown] = useState<Record<string, boolean>>({})
  const wizSchemaSeqRef = useRef(0)
  const wizDrySeqRef = useRef(0)
  const wizFormRef = useRef(wizForm)
  useEffect(() => { wizFormRef.current = wizForm }, [wizForm])

  // 定时器与竞态守卫（Jobs.vue 用 gatePoll/schemaSeq，这里等价：ref 存最新状态 + 序列号）
  const tasksRef = useRef<MosJobTask[]>([])
  const loadErrRef = useRef<Error | null>(null)
  const currentIdRef = useRef('')
  const openSeqRef = useRef(0)
  const showLogRef = useRef(false)
  const autoFollowRef = useRef(true)
  const pipelineRef = useRef<MosPipeline>(pipeline)
  const timerRef = useRef<number | null>(null)
  const gateTimerRef = useRef<number | null>(null)
  const gateSeqRef = useRef(0)
  const gateFailsRef = useRef(0)
  const tickRef = useRef(0)

  useEffect(() => { tasksRef.current = tasks }, [tasks])
  useEffect(() => { loadErrRef.current = loadError }, [loadError])
  useEffect(() => { showLogRef.current = showLog }, [showLog])
  useEffect(() => { autoFollowRef.current = autoFollow }, [autoFollow])
  useEffect(() => { pipelineRef.current = pipeline }, [pipeline])

  // ── 派生展示 ────────────────────────────────────────────
  const runningCount = useMemo(() => tasks.filter((t) => t.status === 'running').length, [tasks])
  const visibleTasks = useMemo(() => {
    const q = jobsQuery.trim().toLowerCase()
    return tasks.filter((t) => {
      if (jobsStatus && t.status !== jobsStatus) return false
      if (!q) return true
      const stageText = (t.stages || [])
        .map((s) => [s.method, s.method_label, s.output_name].join(' ')).join(' ')
      return [t.id, t.title, t.final_name, t.dataset, t.base, stageText]
        .map((v) => String(v || '')).join(' ').toLowerCase().includes(q)
    })
  }, [tasks, jobsQuery, jobsStatus])

  const pageCount = Math.max(1, Math.ceil(visibleTasks.length / pageSize))
  useEffect(() => { if (page > pageCount) setPage(pageCount) }, [page, pageCount])
  const pagedTasks = visibleTasks.slice((page - 1) * pageSize, page * pageSize)

  const jobInfo = useMemo(() => parseParams(current), [current])
  const taskStatus = (pipeline.task && pipeline.task.status) || (current && current.status) || 'queued'
  const taskTitle = (pipeline.task && pipeline.task.title)
    || (current && (current.output_name || methodCn(current.method))) || ''
  const finalName = useMemo(() => {
    const t = pipeline.task
    if (t && t.final_name) return t.final_name
    const full = (pipeline.artifacts || []).filter((a) => !a.adapter)
    return full.length ? (full[full.length - 1].label || '') : ''
  }, [pipeline])

  const stageArts = useCallback((s: MosJobStage) => {
    if (s.artifacts && s.artifacts.length) return s.artifacts
    return (pipelineRef.current.artifacts || []).filter((a) => a.job_id === s.id
      || (s.out_dir && String(a.path || '').startsWith(String(s.out_dir).replace(/\/$/, '') + '/')))
  }, [])

  /** 产物链三槽位：训练 / 融合 / 量化（Jobs.vue:672-733，含续训取最新段的老数据兜底） */
  const pipelineSlots = useMemo<Slot[]>(() => {
    const stages = pipeline.stages || []
    if (!current) return []
    const p = jobInfo || {}
    const lastOf = (pred: (s: MosJobStage) => boolean) => {
      for (let i = stages.length - 1; i >= 0; i -= 1) if (pred(stages[i])) return stages[i]
      return null
    }
    const trainStage = lastOf((s) => TRAIN_METHODS.includes(s.method))
      || lastOf((s) => s.method === 'embed')
    if (!trainStage) {
      // 老数据 / 单独发起的融合、量化任务：有几个阶段就展示几个
      return stages.map((s) => ({
        key: s.id, name: stageName(s.method), job: s, artifacts: stageArts(s),
        tag: s.status, tagText: statusCn(s.status), state: s.status,
        sub: methodCn(s.method), canMerge: false, canQuant: false,
      }))
    }
    const mergeStage = lastOf((s) => s.method === 'export')
    const quantStage = lastOf((s) => s.method === 'quant')
    const arts = pipeline.artifacts || []
    const adapter = arts.find((a) => a.adapter) || null
    const full = arts.find((a) => !a.adapter) || null
    const tuner = String(p.tuner_type || (p.params && p.params.tuner_type) || 'lora').toLowerCase()
    const slots: Slot[] = [{
      key: 'train', name: stageName(trainStage.method), job: trainStage,
      artifacts: stageArts(trainStage), tag: trainStage.status,
      tagText: statusCn(trainStage.status), state: trainStage.status,
      sub: [methodCn(trainStage.method), trainStage.output_name].filter(Boolean).join(' · '),
      canMerge: false, canQuant: false,
    }]
    if (trainStage.method === 'embed') return slots
    if (mergeStage) {
      slots.push({ key: 'merge', name: '融合', job: mergeStage, artifacts: stageArts(mergeStage),
        tag: mergeStage.status, tagText: statusCn(mergeStage.status), state: mergeStage.status,
        sub: mergeStage.output_name || 'LoRA 合并进基座（bf16）', canMerge: false, canQuant: false })
    } else if (tuner === 'full') {
      slots.push({ key: 'merge', name: '融合', job: null, artifacts: [], tag: 'skip',
        tagText: '不需要', state: 'skip', sub: '全参训练直接产出完整模型，不需要融合',
        canMerge: false, canQuant: false })
    } else {
      slots.push({ key: 'merge', name: '融合', job: null, artifacts: [], tag: 'wait',
        tagText: adapter ? '可补跑' : '未开始', state: 'wait',
        sub: adapter ? '把适配器合并进基座，得到可直接部署的完整模型' : '训练产出适配器后可融合',
        canMerge: !!adapter, canQuant: false })
    }
    if (quantStage) {
      slots.push({ key: 'quant', name: '量化', job: quantStage, artifacts: stageArts(quantStage),
        tag: quantStage.status, tagText: statusCn(quantStage.status), state: quantStage.status,
        sub: quantStage.output_name || 'AWQ 4bit（可直接部署）', canMerge: false, canQuant: false })
    } else {
      slots.push({ key: 'quant', name: '量化', job: null, artifacts: [], tag: 'wait',
        tagText: full ? '可补跑' : '待融合', state: 'wait',
        sub: full ? 'AWQ 4bit 静态量化，产出可直接部署的模型文件' : '先有完整模型才能量化',
        canMerge: false, canQuant: !!full })
    }
    return slots
  }, [current, pipeline, jobInfo, stageArts])

  /** argv 反解 key→value（Jobs.vue:756-817）：多值参数合并；--model 含义随方法变 */
  const keyOf = useCallback((flag: string) => {
    const m = String((current && (current.method || current.type)) || '').toLowerCase()
    if (flag === '--model') return m === 'quant' ? 'merged' : 'base'
    return ARG_ALIAS[flag] || (argMeta[flag] || {}).key || flag.replace(/^--/, '')
  }, [current, argMeta])

  const usedValues = useMemo(() => {
    const out: Record<string, unknown> = {}
    const j = jobInfo
    let cur: { flag: string; vals: string[] } | null = null
    const flush = () => {
      if (!cur) return
      const k = keyOf(cur.flag)
      if (k) out[k] = cur.vals.length > 1 ? cur.vals.join(', ') : (cur.vals[0] || '')
    }
    for (const t of (j.argv || [])) {
      if (typeof t === 'string' && t.startsWith('--')) { flush(); cur = { flag: t, vals: [] } }
      else if (cur) cur.vals.push(String(t))
    }
    flush()
    for (const k of Object.keys(j)) {
      if (k === 'argv' || k === 'warnings' || k === 'gpu_busy') continue
      if (!(k in out) && j[k] !== null && j[k] !== undefined && j[k] !== '') out[k] = j[k]
    }
    return out
  }, [jobInfo, keyOf])

  const paramRows = useMemo(() => {
    const out: { k: string; label: string; v: string }[] = []
    for (const k of Object.keys(PARAM_CN)) {
      const v = jobInfo[k]
      if (v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length)) continue
      out.push({ k, label: PARAM_CN[k], v: Array.isArray(v) ? v.join(', ') : String(v) })
    }
    return out
  }, [jobInfo])

  const prettyParams = useMemo(() => {
    if (!current) return ''
    const p = parseParams(current)
    if (p.argv) return p.argv.join(' \\\n  ')
    return JSON.stringify(p, null, 2)
  }, [current])

  const canGate = !!current && TRAIN_METHODS.includes(String(current.method || current.type || '').toLowerCase())
    && taskStatus === 'finished' && current.rc === 0

  /** 日志跟着「真正在跑的阶段」走（Jobs.vue:1140-1147） */
  const logJobId = useMemo(() => {
    const stages = pipeline.stages || []
    for (let i = stages.length - 1; i >= 0; i -= 1) {
      if (stages[i].status === 'running' || stages[i].status === 'queued') return stages[i].id
    }
    return (current && current.id) || ''
  }, [pipeline, current])

  // ── 数据加载与轮询（Jobs.vue:841-869、1350-1368） ────────
  const load = useCallback(async () => {
    if (!tasksRef.current.length) setLoading(true)
    const hadError = !!loadErrRef.current
    try {
      const r = await jobTasks(500)
      const list = r.tasks || []
      setTasks(list)
      setLoadError(null)
      if (hadError) message.success('连接已恢复')
      // 详情页打开时跟随刷新：状态/退出码/阶段链都要跟着列表聚合行走（Jobs.vue:850-861）
      const cid = currentIdRef.current
      if (cid) {
        const t = list.find((x) => x.id === cid)
        if (t) {
          setCurrent((prev) => (prev ? {
            ...prev,
            pct: t.pct, progress: t.progress, steps_done: t.steps_done, steps_total: t.steps_total,
            loss: t.loss, updated_at: t.updated_at, title: t.title, final_name: t.final_name,
            status: t.status, rc: t.rc, alive: t.alive, stages: t.stages,
            base: t.base || prev.base,
          } : prev))
        }
        try { setPipeline(await jobPipeline(cid)) } catch { /* 任务被删时忽略 */ }
      }
    } catch (e: any) {
      setLoadError(e)
      if (!hadError) message.error('加载失败: ' + (e?.message || '')) // 轮询失败不要每 5 秒弹一次
    } finally {
      setLoading(false)
    }
  }, [])

  const refreshLog = useCallback(async () => {
    if (!currentIdRef.current) return
    const target = logJobIdRef.current || currentIdRef.current
    try { setLog(await jobLog(target, 300)) } catch (e: any) { setLog(String(e?.message || e)) }
  }, [])
  const logJobIdRef = useRef('')
  useEffect(() => { logJobIdRef.current = logJobId }, [logJobId])

  // ── 详情打开/关闭 ───────────────────────────────────────
  function stopGatePoll() {
    if (gateTimerRef.current) { clearInterval(gateTimerRef.current); gateTimerRef.current = null }
    gateSeqRef.current += 1
  }

  /** 返回 gate=null 表示没跑过；undefined 表示这次没取到（轮询不能当作结束，Jobs.vue:1056-1068） */
  async function loadGate(jobId?: string): Promise<MosGate | null | undefined> {
    if (!jobId) return undefined
    try {
      const g = (await jobGate(jobId)).gate
      if (currentIdRef.current === jobId) setGate(g || null)
      return g || null
    } catch { return undefined }
  }

  async function openById(rawId: string, sync = true) {
    const id = String(rawId).trim()
    if (!id || id === currentIdRef.current) return
    stopGatePoll()
    gateFailsRef.current = 0
    setGate(null)
    setShowLog(false) // 详情先看产物链，日志按需展开
    setOpenStage('')
    setLog('')
    const my = ++openSeqRef.current
    try {
      const pipe = await jobPipeline(id)
      if (my !== openSeqRef.current) return
      const rootId = pipe.task_id || id
      const job = await getJob(rootId)
      if (my !== openSeqRef.current) return
      currentIdRef.current = rootId
      setCurrent(job)
      setPipeline(pipe)
      // 深链同步 /mos/jobs?job=<根任务>：阶段 id 也归一到根任务（Jobs.vue:1038-1044）
      if (sync && searchParams.get('job') !== rootId) setSearchParams({ job: rootId }, { replace: true })
      void loadArgMeta(job.method || job.type)
      const g = await loadGate(rootId)
      if (my === openSeqRef.current && g && g.status === 'running') startGatePoll(rootId)
    } catch (e: any) {
      if (my !== openSeqRef.current) return
      message.error('打开任务失败: ' + (e?.message || ''))
      if (sync) setSearchParams({}, { replace: true })
    }
  }

  /** 只读配置表单复用「发起任务」同一套 schema（Jobs.vue:742-752） */
  async function loadArgMeta(method?: string) {
    try {
      const s = await jobSchema(method || 'sft')
      setSchemaGroups(s.groups || [])
      const meta: Record<string, { label: string; key: string }> = {}
      for (const g of s.groups || []) {
        for (const p of g.params || []) if (p.arg) meta[p.arg] = { label: p.label, key: p.key }
      }
      setArgMeta(meta)
    } catch { setSchemaGroups([]); setArgMeta({}) }
  }

  function clearDetailState() {
    stopGatePoll()
    gateFailsRef.current = 0
    openSeqRef.current += 1
    currentIdRef.current = ''
    setCurrent(null)
    setPipeline({ task: null, task_id: '', stages: [], artifacts: [] })
    setShowLog(false)
    setLog('')
    setSchemaGroups([])
  }

  function closeDetail() {
    clearDetailState()
    if (searchParams.get('job')) setSearchParams({}, { replace: true })
  }

  // ── 门禁轮询（Jobs.vue:1087-1113：3 秒一次，连续 5 次取不到才放弃） ──
  function startGatePoll(jobId: string) {
    stopGatePoll()
    gateFailsRef.current = 0
    const my = gateSeqRef.current
    gateTimerRef.current = window.setInterval(async () => {
      if (my !== gateSeqRef.current) { // 已切到别的任务：这个轮询作废
        if (gateTimerRef.current) { clearInterval(gateTimerRef.current); gateTimerRef.current = null }
        return
      }
      const g = await loadGate(jobId)
      if (g === undefined) {
        gateFailsRef.current += 1
        if (gateFailsRef.current >= 5) {
          if (gateTimerRef.current) { clearInterval(gateTimerRef.current); gateTimerRef.current = null }
          message.error('门禁状态连续 5 次取不到，已停止刷新')
        }
        return
      }
      gateFailsRef.current = 0
      if (!g || g.status !== 'running') {
        if (gateTimerRef.current) { clearInterval(gateTimerRef.current); gateTimerRef.current = null }
        if (g && g.status === 'done') message.success(g.passed ? `门禁通过：${g.score}` : `门禁未通过：${g.score}`)
        else if (g && g.status === 'failed') message.error(`门禁失败：${g.error || ''}`)
      }
    }, 3000)
  }

  async function runGate() {
    if (!current) return
    const jobId = current.id
    try {
      const r = await runJobGate(jobId, gateForm)
      setGate({ id: r.id, job_id: jobId, status: 'running', threshold: gateForm.threshold })
      setGateOpen(false)
      message.success('门禁已启动：' + r.id)
      startGatePoll(jobId)
    } catch (e: any) { message.error(e?.message || '门禁启动失败') }
  }

  // ── 动作：停止 / 续跑 / 删除（Jobs.vue:1116-1198） ───────
  async function stopTask(j: MosJobTask | MosJobDetail) {
    // 卡片传任务（含阶段），详情传根任务：把正在跑的阶段一起停掉。
    // 逐个停不能串一个 try：queued 阶段会 400「任务未在运行」，不能让后面的 running 不发请求。
    const stages = (j.stages || []).filter((s) => s.status === 'running' || s.status === 'queued')
    const ids = stages.length ? stages.map((s) => s.id) : [j.id]
    let done = 0
    const errs: string[] = []
    for (const id of ids) {
      try { await stopJob(id); done += 1 } catch (e: any) { errs.push(e?.message || '') }
    }
    if (!done && errs.length) message.error('停止失败：' + errs[0])
    else if (errs.length) message.success(`已停止 ${done} 个阶段；其余：${errs[0]}`)
    else message.success('已发送停止')
    await load()
  }

  async function resumeTask(j: MosJobDetail) {
    setResuming(true)
    try {
      const r = await resumeJob(j.id)
      message.success(`已续跑：${r.id}（从 ${String(r.resumed_from || '').split('/').pop()}）`)
      closeDetail()
      await load()
    } catch (e: any) { message.error('续跑失败: ' + (e?.message || '')) }
    finally { setResuming(false) }
  }

  function removeTask(j: MosJobTask | MosJobDetail) {
    Modal.confirm({
      title: '删除训练任务',
      content: `删除任务「${j.title || j.id}」（含训练/融合/量化阶段记录）？\n磁盘上的产物文件保留，可在「全部产物」里删除。`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteJob(j.id)
          if (currentIdRef.current === j.id) closeDetail()
          await load()
        } catch (e: any) {
          // 409「还有 N 条门禁记录」：不带 force=1 永远删不掉（Jobs.vue:1182-1184）
          if (e.status !== 409 || !/门禁/.test(String(e.message))) { message.error(e?.message || ''); return }
          return new Promise<void>((resolve) => {
            Modal.confirm({
              title: '任务还有门禁记录',
              content: `${e.message}\n\n同时删除这些门禁记录并删除任务？`,
              okText: '一并删除',
              okButtonProps: { danger: true },
              onOk: async () => {
                try {
                  await deleteJob(j.id, true)
                  if (currentIdRef.current === j.id) closeDetail()
                  await load()
                  message.success('已删除任务与门禁记录')
                } catch (e2: any) { message.error(e2?.message || '') }
                finally { resolve() }
              },
              onCancel: () => resolve(),
            })
          })
        }
      },
    })
  }

  // ── 产物链阶段动作（Jobs.vue:1200-1253） ─────────────────
  async function runMerge() {
    if (!current) return
    Modal.confirm({
      title: '融合成完整模型',
      content: '把这次训练的 LoRA 适配器合并进基座，得到一个可直接评测 / 部署的完整模型。\n融合完成后会出现在「产物链 · 融合」里。',
      okText: '开始融合',
      onOk: async () => {
        setActing(true)
        try {
          const r = await mergeTaskStage(current.id, { quant: false })
          message.success('已发起融合：' + r.id)
          await load()
        } catch (e: any) { message.error('发起失败: ' + (e?.message || '')) }
        finally { setActing(false) }
      },
    })
  }

  async function runQuantize() {
    if (!current) return
    Modal.confirm({
      title: 'AWQ 4bit 量化',
      content: '对这次的完整模型做 AWQ 4bit 静态量化（默认 128 条校准，用本次训练语料自动生成）。\n产出可直接部署的模型文件。',
      okText: '开始量化',
      onOk: async () => {
        setActing(true)
        try {
          const r = await quantizeTaskStage(current.id, { calib_n: 128 })
          message.success('已发起量化：' + r.id)
          await load()
        } catch (e: any) { message.error('发起失败: ' + (e?.message || '')) }
        finally { setActing(false) }
      },
    })
  }

  async function stopStage(s: MosJobStage) {
    try { await stopJob(s.id); message.success('已发送停止'); await load() }
    catch (e: any) { message.error(e?.message || '') }
  }

  function toggleStageLog(s: MosJobStage) {
    if (openStage === s.id) { setOpenStage(''); return }
    setOpenStage(s.id)
    jobLog(s.id, 300).then((text) => setStageLogs((p) => ({ ...p, [s.id]: text })))
      .catch((e: any) => setStageLogs((p) => ({ ...p, [s.id]: String(e?.message || e) })))
  }

  // ── 产物登记 / 删除（Jobs.vue:1255-1282） ────────────────
  const ledgerMode = (searchParams.get('view') || '') === 'artifacts'

  function openReg(a: MosArtifact) {
    setReg({ path: a.path, name: a.label || a.name || '', base: a.base_model || '', method: a.kind || '', notes: '', kind: a.rel || '' })
    setRegOpen(true)
  }
  async function doRegister() {
    try {
      await registerModel({ name: reg.name, path: reg.path, base: reg.base, method: reg.method, notes: reg.notes })
      message.success(`已登记「${reg.name}」`)
      setRegOpen(false)
      await load()
      if (ledgerMode) await loadArtifacts()
    } catch (e: any) { message.error('登记失败: ' + (e?.message || '')) }
  }
  function dropArt(a: MosArtifact) {
    Modal.confirm({
      title: '删除产物',
      content: `删除产物「${a.rel || a.path}」？目录会从磁盘删除，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try { await deleteArtifact(a.path); message.success('已删除产物'); await load() }
        catch (e: any) { message.error('删除失败: ' + (e?.message || '')) }
      },
    })
  }

  // ── 「全部产物」台账（BUG-PARITY-12，Artifacts.vue 279 行整体回搬） ────
  /** 台账加载：失败**不能画成「还没有产物」**（Artifacts.vue:174-176 注释原话——
   *  那会让用户以为产物被删了）；部署清单单独取，取不到只当没部署（Vue :177 catch→[]）。 */
  async function loadArtifacts() {
    setArtsLoading(true)
    setArtsError('')
    try {
      const r = await listArtifacts()
      const items = r.items || []
      setArts(items)
      setArtsRoot(r.root || '')
      setArtsActive(r.active || {})
      // 老版 /artifacts?register=<path>（Artifacts.vue:179-183）：命中未登记→直接开登记窗，
      // 同一路径只自动开一次（用户取消后不纠缠）
      const want = String(searchParams.get('register') || '')
      if (want && regDeepLinkRef.current !== want) {
        regDeepLinkRef.current = want
        const hit = items.find((a) => a.path === want)
        if (hit && !hit.registered_by) openReg(hit)
      }
    } catch (e: any) {
      setArtsError(String(e?.message || e))
      message.error('读取产物失败: ' + String(e?.message || e))
    } finally { setArtsLoading(false) }
    try { setDeps(await listDeployments()) } catch { setDeps([]) }
  }

  /** 这份产物是否正被某个部署实例用着（= 已发布；Artifacts.vue:186-190 deployedOf） */
  function deployedOf(a: MosArtifact): MosDeploymentLite | null {
    return deps.find((x) => String(x.model_path ?? '') === a.path) || null
  }

  function openLedger() { setSearchParams({ view: 'artifacts' }) }

  /** 一键出可部署版本（Artifacts.vue:196-216 mergeIt）：融合导出 →（成功后）自动接
   *  AWQ 量化（快速 12 条），用户不用管中间过程；active 表防同目录重复提交。 */
  function mergeIt(a: MosArtifact) {
    Modal.confirm({
      title: '出可部署版本',
      cancelText: '取消',
      content: '这条适配器会：① 融合成完整模型；② 自动 AWQ 量化（校准 12 条，先快速出个能跑起来的版本）。'
        + `\n基座：${a.base_model || '训练环境默认'}`
        + '\n产物会出现在下面「最终产物」里。'
        + '\n\n要正式定版的量化（128 条校准），在「+ 发起任务」向导第 3 步选「融合 + AWQ 4bit 量化」，'
        + '或在任务详情产物链「量化」槽位补跑。',
      okText: '开始融合导出',
      onOk: async () => {
        setMerging(a.path)
        try {
          const r = await createJob({
            method: 'export', force: true,
            params: { base: a.base_model || '', adapters: a.path, chain_quant: true, chain_calib_n: 12 },
          })
          message.success('已发起：融合导出 ' + (r.id || '') + '（成功后自动接量化）')
          await loadArtifacts()
        } catch (e: any) { message.error('发起失败: ' + String(e?.message || e)) }
        finally { setMerging('') }
      },
    })
  }

  /** 台账删除（Artifacts.vue:246-268）：409=还登记在模型库里（软链会悬空），
   *  只删磁盘不行；用户确认后连登记一起删（force=1，已发布的同批撤销）。 */
  function ledgerDrop(a: MosArtifact) {
    Modal.confirm({
      title: '删除产物',
      cancelText: '取消',
      content: `删除产物「${a.rel || a.path}」？目录会从磁盘删除，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteArtifact(a.path)
          message.success('已删除产物')
          await loadArtifacts()
        } catch (e: any) {
          if (e.status !== 409) { message.error('删除失败: ' + String(e?.message || e)); return }
          return new Promise<void>((resolve) => {
            Modal.confirm({
              title: '产物已登记为模型',
              cancelText: '取消',
              content: `${e.message}\n\n继续删除会同时移除模型库里的登记；已发布的还会从推理端版本仓库撤销`
                + '（源目录本来就要删，软链会跟着删）。',
              okText: '仍然删除',
              okButtonProps: { danger: true },
              onOk: async () => {
                try {
                  const r: any = await deleteArtifact(a.path, true)
                  message.success('已删除产物' + ((r?.models_removed || []).length
                    ? `，并移除模型登记 ${r.models_removed.length} 条` : ''))
                  await loadArtifacts()
                } catch (e2: any) { message.error('删除失败: ' + String(e2?.message || e2)) }
                finally { resolve() }
              },
              onCancel: () => resolve(),
            })
          })
        }
      },
    })
  }

  // ── 发起任务向导逻辑（BUG-PARITY-11，Vue :871-1021） ────
  const wizTraining = TRAIN_METHODS.includes(wizForm.method)
  const frozenDs = useMemo(() => allDs.filter((d) => !!d.version && d.data_origin !== MOS_DATA_ORIGIN_PLACEHOLDER), [allDs])
  const blockedDsCount = useMemo(
    () => allDs.filter((d) => !!d.version && d.data_origin === MOS_DATA_ORIGIN_PLACEHOLDER).length, [allDs])
  const launchMethods = useMemo(() => Object.keys(methodsMap).filter((m) => LAUNCH_KEEP.includes(m)), [methodsMap])
  // 第 1 步闸：训练方法必须选已发布数据集（Vue canNext :551）
  const canNextWiz = wizStep !== 0 || !wizTraining || !!wizForm.dataset_id
  const wizParamCount = Object.values(wizForm.params || {})
    .filter((v) => v !== '' && v !== null && v !== undefined).length
  const wizIsFullTuner = String((wizForm.params || {}).tuner_type || 'lora').toLowerCase() === 'full'
  const shapeChainText = ({
    adapter: '训练完成即结束（只出适配器）',
    merged: '训练 → 自动融合',
    merged_quant: `训练 → 自动融合 → 自动 AWQ 量化（${artForm.calib_n} 条校准）`,
  } as Record<string, string>)[artForm.shape] || ''
  const wizDatasetName = (id: string) => {
    const d = allDs.find((x) => x.id === id)
    return d ? `${d.name} · ${d.version || '未发布'}` : (id || '—')
  }

  // 全参训练没有 adapter，「只留适配器」不成立（Vue watch :583-586）
  useEffect(() => {
    if (wizIsFullTuner && artForm.shape === 'adapter') setArtForm((p) => ({ ...p, shape: 'merged' }))
  }, [wizIsFullTuner, artForm.shape])

  /** 三级合并（Vue loadSchema :872-888）：schema 默认 < 训练环境默认 < 本次已填；
   *  快速连点方法页签只认最后一次（schemaSeq），旧方法字段不得覆盖新方法 */
  async function wizLoadSchema(method: string) {
    const seq = ++wizSchemaSeqRef.current
    try {
      const s = await jobSchema(method)
      if (seq !== wizSchemaSeqRef.current) return
      setWizGroups(s.groups || [])
      setWizPresets(s.presets || {})
      const def: Record<string, unknown> = {}
      for (const g of s.groups || []) for (const p of g.params || []) {
        if (p.default !== null && p.default !== undefined) def[p.key] = p.default
      }
      setWizForm((prev) => ({ ...prev, params: { ...def, ...(s.lab_defaults || {}), ...prev.params } }))
    } catch (e: any) {
      if (seq === wizSchemaSeqRef.current) message.error('参数加载失败: ' + (e?.message || ''))
    }
  }

  async function wizEnsureDeps() {
    if (!Object.keys(methodsMap).length) {
      try { setMethodsMap((await jobSchema()).methods || {}) } catch { /* ignore（Vue :892-893 同款容错） */ }
    }
    if (!allDs.length) {
      try { setAllDs(await listDatasets()) } catch { /* 空列表=走「去发布」callout，不假装有数据 */ }
    }
  }

  /** 开向导（Vue openForm :891-901）：复位 artForm/步骤/dry，**不清** method/dataset/params（=草稿）。 */
  async function openWizard() {
    await wizEnsureDeps()
    setArtForm({ ...ART_DEFAULT })
    setWizStep(0)
    setDry('')
    setDryError('')
    setWizOpen(true)
    if (!wizGroups.length) await wizLoadSchema(wizFormRef.current.method)
  }

  /** 关向导（Vue closeForm :903-908）：只清步骤/dry；params 留着=下次打开还是你填的（草稿语义） */
  function closeWizard() {
    setWizOpen(false)
    setWizStep(0)
    setDry('')
    setDryError('')
  }

  function wizGoStep(i: number) {
    if (i >= wizStep) return // Vue goStep :930-933：只许点回已完成步骤，不许往前跳
    setWizStep(i)
  }

  async function wizNextStep() {
    if (wizStep === 0) {
      if (!canNextWiz) return
      setWizStep(1)
      return
    }
    if (wizStep === 1) { setWizStep(2); return }
    if (wizStep === 2) {
      setWizStep(3)
      await checkDryWiz() // 末步进入即 dry-run 闸（Vue nextStep :924-927）
    }
  }

  /** 切方法（Vue setMethod :935-940）：非训练方法清数据集；params 重置后重载 schema */
  async function wizSetMethod(m: string) {
    setWizForm((p) => ({ ...p, method: m, dataset_id: TRAIN_METHODS.includes(m) ? p.dataset_id : '', params: {} }))
    await wizLoadSchema(m)
  }

  /** 预设=合并进已填（Vue applyPreset :942 `{...params, ...pv}`），不是整表覆盖 */
  function applyPresetWiz(pv: Record<string, unknown>) {
    setWizForm((p) => ({ ...p, params: { ...p.params, ...pv } }))
    message.success('已套用预设')
  }

  /** 只存这一个方法的默认（Vue :953-957：读 train.defaults → 按 method 覆写 → 整串回 PATCH） */
  function saveAsDefaultWiz() {
    Modal.confirm({
      title: '存为默认参数',
      content: (
        <span style={{ whiteSpace: 'pre-wrap' }}>
          {`把当前参数存为训练环境里 ${wizFormRef.current.method} 的默认值？\n\n会影响以后发起的所有 ${wizFormRef.current.method} 任务。`}
        </span>
      ),
      okText: '存为默认',
      onOk: async () => {
        try {
          const st = await getSettings()
          let cur: Record<string, unknown> = {}
          try { cur = JSON.parse(st['train.defaults'] || '{}') || {} } catch { cur = {} }
          const next = { ...cur, [wizFormRef.current.method]: { ...buildWizParams() } }
          await patchSettings({ 'train.defaults': JSON.stringify(next) })
          message.success(`已存为 ${wizFormRef.current.method} 默认参数`)
        } catch (e: any) { message.error('保存失败: ' + (e?.message || '')) }
      },
    })
  }

  /** 非空才进参数；list 型逗号串→数组；训练方法带 chain_*（Vue buildParams :962-983） */
  function buildWizParams(): Record<string, unknown> {
    const out: Record<string, unknown> = {}
    for (const g of wizGroups) {
      for (const p of g.params || []) {
        const v = wizForm.params[p.key]
        if (v === '' || v === null || v === undefined) continue
        out[p.key] = p.type === 'list' && typeof v === 'string'
          ? v.split(',').map((x) => x.trim()).filter(Boolean) : v
      }
    }
    if (wizTraining) {
      const shape = artForm.shape
      out.chain_export = shape !== 'adapter'
      out.chain_quant = shape === 'merged_quant'
      if (shape === 'merged_quant') {
        out.chain_calib_n = artForm.calib_n
        if (artForm.calib_dataset_id) out.chain_calib_dataset = artForm.calib_dataset_id
      }
    }
    return out
  }

  const wizJobBody = (isDry: boolean) => ({
    method: wizForm.method,
    dataset_id: wizTraining ? wizForm.dataset_id : '',
    params: buildWizParams(), dry_run: !!isDry, force: false,
  })

  /** 命令核对（Vue checkDry :994-1008）：来回切步并发多次只认最后一次（drySeq），
   *  否则确认页可能显示旧参数的命令 */
  async function checkDryWiz() {
    const seq = ++wizDrySeqRef.current
    setDry('')
    setDryError('')
    setDryChecking(true)
    try {
      const r = await createJob(wizJobBody(true))
      if (seq !== wizDrySeqRef.current) return
      setDry((r.argv || []).join(' \\\n  '))
    } catch (e: any) {
      if (seq === wizDrySeqRef.current) setDryError(String(e?.message || e))
    } finally {
      if (seq === wizDrySeqRef.current) setDryChecking(false)
    }
  }

  async function submitWiz() {
    if (submitting) return
    setSubmitting(true)
    try {
      const r = await createJob(wizJobBody(false))
      closeWizard()
      message.success('已发起 ' + (r.id || ''))
      await load()
    } catch (e: any) { message.error('失败: ' + (e?.message || '')) } finally { setSubmitting(false) }
  }

  /** 深链真开向导（Vue applyRouteMethod :1333-1347）：合法方法→切方法重载 schema；
   *  只有 ds 且当前方法非训练→回落 sft；带 ds 直达第 2 步「调参数」（仍可点上一步回退） */
  async function applyTrainDeepLink(m: string, ds: string) {
    await wizEnsureDeps()
    if (m && MCN[m]) {
      if (m !== wizFormRef.current.method) setWizForm((p) => ({ ...p, method: m, params: {} }))
      await wizLoadSchema(m)
    } else if (ds && !TRAIN_METHODS.includes(wizFormRef.current.method)) {
      setWizForm((p) => ({ ...p, method: 'sft', params: {} }))
      await wizLoadSchema('sft')
    }
    if (ds) setWizForm((p) => ({ ...p, dataset_id: ds }))
    setWizStep(ds ? 1 : 0)
    setDry('')
    setDryError('')
    setWizOpen(true)
  }

  // —— 参数编辑器（ConfigPanel.vue 子集回搬：搜索/组折叠/adv 折叠/候选 datalist；
  //    show-keys=false 即 Vue :150 传的口径，不显示键名；initial-open=默认全开） ——
  const wizQueryLower = wizQuery.trim().toLowerCase()
  const wizShownRows = (g: MosSchemaGroup) => (g.params || []).filter((p) => {
    if (wizQueryLower
      && !`${p.label} ${String(p.hint || '')} ${String(p.explain || '')} ${p.key}`.toLowerCase().includes(wizQueryLower)) return false
    if ((p.adv || p.advanced) && !advShown[g.group] && !wizQueryLower) return false
    return true
  })
  const wizAdvHiddenIn = (g: MosSchemaGroup) => (g.params || []).filter((p) => p.adv || p.advanced).length
  const wizGroupOpen = (g: MosSchemaGroup) => (wizQuery.trim() ? true : wizClosed[g.group] !== false)
  const wizVisibleGroups = wizGroups.filter((g) => wizShownRows(g).length > 0)
  const wizAllItemCount = useMemo(() => wizGroups.reduce((n, g) => n + (g.params || []).length, 0), [wizGroups])
  const wizAllOpen = wizGroups.some((g) => wizGroupOpen(g))
  function wizToggleAll() {
    const next: Record<string, boolean> = {}
    for (const g of wizGroups) next[g.group] = wizAllOpen // true=当前开着→全关；反之全开
    setWizClosed(next)
  }
  const wizParamPlaceholder = (p: MosSchemaParam) => (p.ph != null ? String(p.ph)
    : (p.default !== null && p.default !== undefined && p.default !== '' ? '默认 ' + String(p.default) : ''))

  function renderWizParam(p: MosSchemaParam) {
    const kind = wizKindOf(p.type)
    const raw = wizForm.params[p.key]
    const shownVal = raw === undefined || raw === null ? '' : String(raw)
    const set = (v: unknown) => setWizForm((prev) => ({ ...prev, params: { ...prev.params, [p.key]: v } }))
    const options = ((p.options as unknown[] | undefined) || []).map(wizToOpt)
    const candidates = (p.candidates as { v: string; l?: string }[] | undefined) || []
    // 孤儿值：手填过/默认值不在候选里→仍显示为「当前」（ConfigPanel.vue:44,166 口径）
    const orphan = shownVal !== '' && !options.some((o) => o.value === shownVal)
    return (
      <div key={p.key} data-wkey={p.key} style={{ display: 'flex', gap: 10, padding: '6px 0', borderBottom: '1px dashed #f5f5f5' }}>
        <div style={{ flex: '0 0 32%', minWidth: 0 }}>
          <div style={{ fontSize: 12.5 }}>{p.label}</div>
          {p.hint ? <div style={MUT12}>{String(p.hint)}</div> : null}
          {p.explain ? <div style={MUT12}>{String(p.explain)}</div> : null}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          {kind === 'select' && (
            <Select
              style={{ width: '100%' }}
              value={shownVal === '' ? undefined : shownVal}
              placeholder="（选一个）"
              allowClear
              onClear={() => set('')}
              options={options.concat(orphan ? [{ value: shownVal, label: `${shownVal}（当前）` }] : [])}
              onChange={(v) => set(String(v ?? ''))}
            />
          )}
          {kind === 'bool' && (
            <Checkbox checked={!!raw} onChange={(e) => set(e.target.checked)}>启用</Checkbox>
          )}
          {kind === 'textarea' && (
            <Input.TextArea rows={4} value={shownVal} onChange={(e) => set(e.target.value)} />
          )}
          {(kind === 'text' || kind === 'number' || kind === 'password') && (
            <>
              {/* 数字输入存原文字符串不 Number() 回写（ConfigPanel.vue:169-172 注释：
                  否则「0.」「0.0002」中间态被规范掉，学习率根本打不进去；后端 str(v) 拼命令等价） */}
              <Input
                type={kind === 'number' ? 'number' : (kind === 'password' ? 'password' : 'text')}
                step={p.type === 'float' ? 'any' : undefined}
                value={shownVal}
                list={candidates.length ? `wcand-${p.key}` : undefined}
                placeholder={wizParamPlaceholder(p)}
                onChange={(e) => set(e.target.value)}
              />
              {candidates.length > 0 && (
                <datalist id={`wcand-${p.key}`}>
                  {candidates.map((c) => <option key={c.v} value={c.v} label={c.l || ''} />)}
                </datalist>
              )}
            </>
          )}
        </div>
      </div>
    )
  }

  // ── 挂载：列表 + schema 并行、深链、5 秒轮询（Jobs.vue:1350-1373） ──
  useEffect(() => {
    void (async () => {
      // allDs 预载：卡片「训练集」回链要 dsIdOf 文件名反查 id（Jobs.vue:771-786）；
      // 向导打开时 wizEnsureDeps 也补拉，这里先拉一次让回链在列表态就可用
      const [, schema, dsList] = await Promise.all(
        [load(), jobSchema().catch(() => null), listDatasets().catch(() => [] as MosDataset[])])
      if (schema && schema.methods) setMethodsMap(schema.methods)
      if (!allDs.length && dsList.length) setAllDs(dsList)
      const pid = searchParams.get('job') || ''
      if (pid) void openById(pid, false)
      timerRef.current = window.setInterval(async () => {
        if (document.hidden) return
        // 没有排队/运行中的任务时降到 15 秒一次（5s×3 tick）
        const active = tasksRef.current.some((t) => t.status === 'queued' || t.status === 'running')
        if (!active && tickRef.current++ % 3 !== 0) return
        await load()
        if (currentIdRef.current && showLogRef.current && autoFollowRef.current) await refreshLog()
      }, 5000)
    })()
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
      stopGatePoll()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // 「全部产物」台账（BUG-PARITY-12）：?view=artifacts 进台账视图并拉产物。
  // 老版深链 /artifacts?register=<path> 只带 register 不带 view 的等价形态（旧外链/收藏）：
  // 自动补成 view=artifacts&register=，由 loadArtifacts 里的一次性 openReg 接窗。
  useEffect(() => {
    const want = String(searchParams.get('register') || '')
    if (want && !ledgerMode) { setSearchParams({ view: 'artifacts', register: want }, { replace: true }); return }
    if (ledgerMode) void loadArtifacts()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, ledgerMode])

  // 深链跟随：地址栏 ?job= 变化即打开对应详情（对照 Vue watch route）
  useEffect(() => {
    const pid = searchParams.get('job') || ''
    if (pid) { if (pid !== currentIdRef.current) void openById(pid, false) }
    else if (currentIdRef.current) clearDetailState()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  // 「去训练」深链（?method=&dataset=）：训练类=真开向导（-10 预留由 -11 兑现，Vue :1324-1347）；
  // export/quant=并入产物阶段的诚实横幅（产物台账页缺位属 PARITY-12，不发假跳转）。
  // 同参数组只自动开一次：用户取消后不纠缠（对照 Vue 仅 onMounted/watch 触发）。
  useEffect(() => {
    const m = (searchParams.get('method') || '').trim()
    const ds = (searchParams.get('dataset') || '').trim()
    if (!m && !ds) { setDeepLinkHint(null); setDeepLinkClosed(false); return }
    if (m === 'export' || m === 'quant') {
      setDeepLinkHint({ m, ds })
      setDeepLinkClosed(false)
      return
    }
    setDeepLinkHint(null)
    const key = m + '|' + ds
    if (deepLinkHandledRef.current === key) return
    deepLinkHandledRef.current = key
    void applyTrainDeepLink(m, ds)
  }, [searchParams])

  // ── 渲染 ────────────────────────────────────────────────
  function renderCard(t: MosJobTask) {
    return (
      <Card
        key={t.id}
        size="small"
        hoverable
        style={{ cursor: 'pointer' }}
        onClick={() => void openById(t.id)}
        title={(
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Typography.Text strong ellipsis={{ tooltip: t.title || t.id }} style={{ maxWidth: 210 }}>
              {t.title || t.id}
            </Typography.Text>
            <Tag color={STATUS_COLOR[t.status] || 'default'}>{statusCn(t.status)}</Tag>
          </Space>
        )}
      >
        <div style={{ ...MONO, fontSize: 11, color: '#999' }}>{t.id}</div>
        {t.status === 'running' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '6px 0 2px' }}>
            <Progress percent={livePct(t)} showInfo={false} size="small" style={{ flex: 1, margin: 0 }} />
            <span style={{ ...MONO, fontSize: 12, color: '#888' }}>{t.pct == null ? '运行中…' : `${livePct(t)}%`}</span>
          </div>
        )}
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', margin: '6px 0' }}>
          {(t.stages || []).map((s) => (
            <Tag key={s.id} color={STATUS_COLOR[s.status] || 'default'} style={{ marginInlineEnd: 0 }}
                 title={`${s.id} · ${statusCn(s.status)}`}>
              {stageName(s.method)} {stageGlyph(s)}
            </Tag>
          ))}
        </div>
        {t.steps_total ? <Kv k="步数"><span style={MONO}>{t.steps_done || 0} / {t.steps_total}</span></Kv> : null}
        {t.loss != null ? <Kv k="loss"><span style={MONO}>{fmtLoss(t.loss)}</span></Kv> : null}
        {baseOfTask(t) ? <Kv k="基座" title={rawBaseOf(t)}>{baseOfTask(t)}</Kv> : null}
        {dsOf(t) ? (() => {
          // 数据集回链（Jobs.vue:771-786 文件名反查 id；BUG-PARITY-12）：查得到就点回它实际吃的
          // 那份数据；查不到（历史目录/已删数据集）保持纯文本，不发假链接
          const dsId = dsIdOf(t, allDs)
          return dsId ? (
            <Kv k="训练集" title={rawDsOf(t)}>
              <Typography.Link onClick={(e) => {
                e.stopPropagation()
                navigate(`/mos/datasets?ds=${encodeURIComponent(dsId)}`)
              }}>{dsOf(t)}</Typography.Link>
            </Kv>
          ) : <Kv k="训练集" title={rawDsOf(t)}>{dsOf(t)}</Kv>
        })() : null}
        {t.final_name ? <Kv k="最终产物" title={t.final_name}>{t.final_name}</Kv> : null}
        <Kv k="发起"><span style={MONO}>{t.created_at}</span></Kv>
        {durOf(t) ? <Kv k={t.status === 'running' ? '已跑' : '耗时'}><span style={MONO}>{durOf(t)}</span></Kv> : null}
        <Space size={4} wrap style={{ marginTop: 10 }}>
          <Button size="small" onClick={(e) => { e.stopPropagation(); void openById(t.id) }}>打开任务</Button>
          {t.status === 'running' ? (
            <Button size="small" danger onClick={(e) => { e.stopPropagation(); void stopTask(t) }}>停止</Button>
          ) : (
            <Button size="small" danger onClick={(e) => { e.stopPropagation(); removeTask(t) }}>删除</Button>
          )}
        </Space>
      </Card>
    )
  }

  // 列表行与详情行都能用（详情行没有 stages 字段，只取 base/params）
  const baseOfTask = (t: { base?: string; params?: string }) => baseName(rawBaseOf(t))

  function renderSlot(slot: Slot) {
    return (
      <Card key={slot.key} size="small" style={slot.state === 'skip' ? { background: '#fafafa' } : undefined}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }}>
          <b>{slot.name}</b>
          <Tag color={STATUS_COLOR[slot.tag] || 'default'}>{slot.tagText}</Tag>
        </Space>
        <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>{slot.sub}</div>
        {slot.job && slot.job.status === 'running' && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '6px 0 2px' }}>
            <Progress percent={livePct(slot.job)} showInfo={false} size="small" style={{ flex: 1, margin: 0 }} />
            <span style={{ ...MONO, fontSize: 12, color: '#888' }}>{slot.job.pct == null ? '运行中…' : `${livePct(slot.job)}%`}</span>
          </div>
        )}
        {slot.artifacts.map((a) => (
          <div key={a.path} style={{ borderTop: '1px dashed #f0f0f0', marginTop: 8, paddingTop: 8 }}>
            <Space size={4} wrap>
              <Typography.Text strong ellipsis={{ tooltip: a.label || a.name }} style={{ maxWidth: 220 }}>
                {a.label || a.name}
              </Typography.Text>
              {a.adapter && <Tag>LoRA 适配器</Tag>}
              {a.registered_by && <Tag color="green">已登记</Tag>}
            </Space>
            <div style={{ fontSize: 12, color: '#888' }}>
              {a.size_gb} GB · {a.mtime}
              {a.registered_by ? ` · 模型库「${a.registered_by}」` : (!a.adapter ? ' · 未登记' : '')}
            </div>
            <Typography.Text style={{ ...MONO, fontSize: 11, color: '#999' }} ellipsis={{ tooltip: a.path }}>
              {a.rel}
            </Typography.Text>
            <div style={{ display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
              {!a.adapter && !a.registered_by && (
                <Button size="small" type="primary" onClick={() => openReg(a)}>登记到模型库</Button>
              )}
              {a.model_id && (
                <Button size="small" onClick={() => navigate(`/mos/models?model=${encodeURIComponent(a.model_id!)}`)}>
                  在模型库查看
                </Button>
              )}
              {a.model_id && (
                <Button size="small" onClick={() => message.info(deployHonestText(a.model_id))}>
                  去部署
                </Button>
              )}
              <Button size="small" onClick={() => dropArt(a)}>删除产物</Button>
            </div>
          </div>
        ))}
        <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
          {slot.key === 'merge' && !slot.job && slot.canMerge && (
            <Button size="small" disabled={acting} onClick={() => void runMerge()}>融合成完整模型</Button>
          )}
          {slot.key === 'quant' && !slot.job && slot.canQuant && (
            <Button size="small" disabled={acting} onClick={() => void runQuantize()}>AWQ 量化</Button>
          )}
          {slot.job && (
            <Button size="small" onClick={() => toggleStageLog(slot.job!)}>
              {openStage === slot.job.id ? '收起日志' : '看日志'}
            </Button>
          )}
          {slot.job && slot.job.status === 'running' && (
            <Button size="small" danger onClick={() => void stopStage(slot.job!)}>停止这一阶段</Button>
          )}
        </div>
        {slot.job && openStage === slot.job.id && (
          <pre style={preStyle(240)}>{stageLogs[slot.job.id] || '（暂无输出）'}</pre>
        )}
      </Card>
    )
  }

  const preStyle = (maxH?: number): React.CSSProperties => ({
    margin: '8px 0 0',
    padding: 8,
    background: '#fafafa',
    border: '1px solid #f0f0f0',
    borderRadius: 6,
    fontSize: 12,
    lineHeight: 1.6,
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-all',
    maxHeight: maxH || 420,
    overflow: 'auto',
    ...MONO,
  })

  /** 「全部产物」台账视图（BUG-PARITY-12：Artifacts.vue 模板 :1-125 整体回搬到本页内，
   *  不新增路由——App.tsx 路由表是共享文件不能动；Assets.tsx 页内多签是同类先例。
   *  三态分开渲染：失败≠空态（Vue :21-31 用 v-else-if 链，错误绝不落到「还没有任何产物」）。 */
  function renderLedger() {
    const list = [...arts].sort(byFinalFirst)
    const finalItem = arts.find((a) => a.kind === 'awq') || null
    const paged = list.slice((artsPage - 1) * artsSize, (artsPage - 1) * artsSize + artsSize)
    return (
      <div data-testid="artifact-ledger">
        {/* 台账头（Artifacts.vue:3-19 逐字） */}
        <Card size="small" style={{ marginBottom: 12 }}>
          <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
            <b style={{ fontSize: 14 }}>全部产物</b>
            <span style={MUT12}>产物根：<span style={MONO}>{artsRoot || '—'}</span></span>
          </Space>
          <div style={{ ...MUT12, marginTop: 6 }}>
            这里是全部产物台账：本次训练的产物在任务详情的「产物链」里看；没有归属到任务的历史目录也在这里。
          </div>
          <Space wrap size={6} style={{ marginTop: 8 }}>
            <Button size="small" onClick={() => setSearchParams({})}>← 训练任务</Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void loadArtifacts()}>刷新</Button>
          </Space>
        </Card>

        {artsLoading && arts.length === 0 ? (
          <>
            <div style={{ ...MUT12, margin: '2px 0 6px' }}>正在读取产物…</div>
            <LoadingState rows={4} />
          </>
        ) : artsError ? (
          <ErrorState title="产物没加载出来" message={artsError} onRetry={() => void loadArtifacts()} />
        ) : list.length === 0 ? (
          <EmptyState
            title="训练环境里还没有任何产物。"
            hint="先发起一次训练/融合/量化任务；任务结束后这里会自动出现输出目录。"
          />
        ) : (
          <>
            {/* 最终产物一眼卡（Artifacts.vue:34-59）：不做分组，免得中间物抢视线 */}
            {finalItem && (() => {
              const dep = deployedOf(finalItem)
              return (
                <Card size="small" style={{ marginBottom: 12 }} data-testid="artifact-final">
                  <Space style={{ width: '100%', justifyContent: 'space-between' }} align="start" wrap>
                    <div style={{ minWidth: 0 }}>
                      <b style={{ fontSize: 14 }}>最终产物 · {finalItem.label || finalItem.name}</b>
                      <div style={{ ...MUT12, marginTop: 5 }}>
                        基座 {baseName(finalItem.base_model) || '—'} · {finalItem.size_gb} GB · {finalItem.mtime}
                        {dep ? <Tag color="green" style={{ marginLeft: 8 }}>已部署 :{dep.port}</Tag> : null}
                      </div>
                      <div style={{ ...MONO, fontSize: 11, color: '#999', marginTop: 3 }} title={finalItem.path}>
                        {finalItem.rel}
                      </div>
                    </div>
                    <span style={{ ...MUT12, whiteSpace: 'nowrap' }}>部署 / 交付用这份</span>
                  </Space>
                  <Space wrap size={6} style={{ marginTop: 10 }}>
                    {finalItem.model_id ? (
                      <Button size="small"
                              onClick={() => navigate(`/mos/models?model=${encodeURIComponent(finalItem.model_id!)}`)}>
                        在模型库查看 →
                      </Button>
                    ) : (!finalItem.registered_by ? (
                      <Button size="small" type="primary" onClick={() => openReg(finalItem)}>登记入模型库</Button>
                    ) : null)}
                    {!dep && finalItem.model_id ? (
                      <Button size="small" onClick={() => message.info(deployHonestText(finalItem.model_id))}>去部署 →</Button>
                    ) : null}
                  </Space>
                </Card>
              )
            })()}

            {/* 平铺台账（Artifacts.vue:61-104） */}
            <Card size="small">
              <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
                <b>全部产物</b>
                <span style={MUT12}>{list.length} 份 · 最终产物排最前</span>
              </Space>
              {paged.map((a) => {
                const act = artsActive[a.path] || null
                const dep = deployedOf(a)
                return (
                  <div key={a.path} data-apath={a.path}
                       style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '9px 0',
                         borderBottom: '1px dashed #f0f0f0', flexWrap: 'wrap' }}>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <Space size={4} wrap>
                        <b title={a.path} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 280 }}>
                          {a.label || a.name}
                        </b>
                        {a.adapter && <Tag>LoRA 适配器</Tag>}
                        {a.registered_by ? <Tag color="green">已登记 · {a.registered_by}</Tag>
                          : (!a.adapter ? <Tag>未登记</Tag> : null)}
                        {dep && <Tag color="green">已部署 :{dep.port}</Tag>}
                      </Space>
                      <div style={{ ...MUT12, fontSize: 11.5, marginTop: 3 }}>
                        基座：<b>{baseName(a.base_model) || '—'}</b>
                        {a.task_id ? (
                          <Typography.Link
                            style={{ marginLeft: 8 }}
                            title="打开产出它的训练任务（含融合 / 量化阶段）"
                            onClick={() => setSearchParams({ job: a.task_id! })}
                          >来源任务 →</Typography.Link>
                        ) : null}
                      </div>
                      <div style={{ ...MONO, fontSize: 11, color: '#999', marginTop: 2 }} title={a.path}>
                        {a.rel} · {a.size_gb} GB · {a.mtime}
                      </div>
                    </div>
                    <Space size={6} wrap>
                      {a.adapter ? (
                        <Button
                          size="small" type="primary"
                          disabled={merging === a.path || !!act}
                          title={act
                            ? `已有任务在跑（${act.method === 'quant' ? '量化' : '融合'}），完成后再操作`
                            : `一键：先融合成完整模型，再自动 AWQ 量化；基座 ${a.base_model || '按训练环境默认'}`}
                          onClick={() => void mergeIt(a)}
                        >
                          {merging === a.path ? '提交中…'
                            : (act ? (act.method === 'quant' ? '量化中…' : '融合中…') : '出可部署版本')}
                        </Button>
                      ) : a.registered_by ? (
                        <Button size="small"
                                onClick={() => navigate(a.model_id ? `/mos/models?model=${encodeURIComponent(a.model_id)}` : '/mos/models')}>
                          查看
                        </Button>
                      ) : (
                        <Button size="small" onClick={() => openReg(a)}>登记入模型库</Button>
                      )}
                      <Button size="small" title="从磁盘删除（不可恢复）" onClick={() => ledgerDrop(a)}>删除</Button>
                    </Space>
                  </div>
                )
              })}
              <Pagination
                style={{ marginTop: 12, textAlign: 'right' }}
                current={artsPage}
                pageSize={artsSize}
                total={list.length}
                showSizeChanger
                pageSizeOptions={[10, 20, 50]}
                onChange={(p, s) => { setArtsPage(s !== artsSize ? 1 : p); setArtsSize(s) }}
              />
            </Card>
          </>
        )}
      </div>
    )
  }

  return (
    <div>
      {/* 仅 export/quant 深链走横幅（BUG-PARITY-11）：向导已交付，训练类深链直接开窗（见上方 effect）；
          融合/量化不是发起入口（Vue :1327-1331）。BUG-PARITY-12 起台账已落地：
          横幅直接给「全部产物」去向（老版 /artifacts?register= 深链由本页 ?view=artifacts&register= 承接） */}
      {deepLinkHint && !deepLinkClosed && (
        <Alert
          type="info"
          showIcon
          closable
          data-testid="train-deeplink-guide"
          style={{ marginBottom: 12 }}
          onClose={() => setDeepLinkClosed(true)}
          message="融合 / 量化已并入训练任务的产物阶段：请到任务详情里的产物链操作，不再单独发起"
          description={(
            <Space direction="vertical" size={2} style={{ fontSize: 12 }}>
              <span>
                保留的参数：方法={deepLinkHint.m ? `${methodCn(deepLinkHint.m)}（${deepLinkHint.m}）` : '未指定（默认指令微调）'}
                {deepLinkHint.ds ? <> · 训练集=<span style={MONO}>{deepLinkHint.ds}</span></> : null}
              </span>
              <span>还没有可操作的训练任务？点右上「+ 发起任务」，向导第 3 步「产物」里就能勾选融合 / 量化形态。</span>
              <span>
                已有产物目录要单独处理（融合 / 量化 / 登记 / 删除）：
                <Typography.Link onClick={openLedger}>去「全部产物」台账 →</Typography.Link>
              </span>
            </Space>
          )}
        />
      )}
      {ledgerMode ? renderLedger() : (
        <>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }} wrap>
        <Space wrap>
          <Tag>显示 {visibleTasks.length} / {tasks.length} 个任务</Tag>
          <Tag color="green">运行中 {runningCount}</Tag>
          <Input
            style={{ width: 220 }}
            placeholder="搜 ID / 训练集 / 产物"
            value={jobsQuery}
            onChange={(e) => { setJobsQuery(e.target.value); setPage(1) }}
            allowClear
          />
          <Select
            style={{ width: 120 }}
            value={jobsStatus}
            onChange={(v) => { setJobsStatus(v); setPage(1) }}
            options={[
              { value: '', label: '全部状态' },
              { value: 'running', label: '运行中' },
              { value: 'finished', label: '已完成' },
              { value: 'failed', label: '失败' },
            ]}
          />
        </Space>
        <Space wrap>
          {/* 老版页头两钮（Jobs.vue:19-22）：全部产物＝台账去向（BUG-PARITY-12，本页内视图）；
              「训练环境」老版跳 /settings，本页无去向不发假链接——默认超参入口=向导「存为默认参数」
              （docs/对拍方案-产物台账部署训练环境三页去向-20261101.md） */}
          <Button title="全部产物台账：历史产物、未归属目录都在这" onClick={openLedger}>全部产物</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" onClick={() => void openWizard()}>
            + 发起任务
          </Button>
        </Space>
      </Space>

      {loadError ? (
        <ErrorState
          title={loadError.status === 403 ? '没有权限' : '任务列表没加载出来'}
          message={loadError.status === 403
            ? '当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。'
            : loadError.message || '未知错误'}
          onRetry={() => void load()}
        />
      ) : loading && tasks.length === 0 ? (
        <LoadingState rows={6} />
      ) : tasks.length === 0 ? (
        <EmptyState
          title="还没有训练任务"
          hint="选一个已发布的训练集，跑第一次 SFT 训练。训练任务一行 = 训练/融合/量化三段流水线；点「发起训练」走 4 步向导，末步会先核对命令再启动"
          action={(
            <Space wrap>
              <Button type="primary" onClick={() => void openWizard()}>＋ 发起训练</Button>
              <Button onClick={() => navigate('/mos/datasets')}>去训练集页 →</Button>
            </Space>
          )}
        />
      ) : visibleTasks.length === 0 ? (
        <EmptyState
          title="没有匹配的任务"
          hint="换个关键词，或把状态切回「全部」"
          action={<Button onClick={() => { setJobsQuery(''); setJobsStatus(''); setPage(1) }}>清空筛选</Button>}
        />
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
            {pagedTasks.map(renderCard)}
          </div>
          <Pagination
            style={{ marginTop: 12, textAlign: 'right' }}
            current={page}
            pageSize={pageSize}
            total={visibleTasks.length}
            showSizeChanger
            pageSizeOptions={[12, 24, 48]}
            onChange={(p, s) => { setPage(s !== pageSize ? 1 : p); setPageSize(s) }}
          />
        </>
      )}
        </>
      )}

      <Drawer
        open={!!current}
        onClose={closeDetail}
        width="min(1080px, 94vw)"
        destroyOnClose={false}
        title={(
          <Space wrap size={6} style={{ minWidth: 0 }}>
            <b style={{ fontSize: 15 }}>{taskTitle}</b>
            <span style={{ ...MONO, fontSize: 12, color: '#888' }}>{current?.id}</span>
            <Tag color={STATUS_COLOR[taskStatus] || 'default'}>{statusCn(taskStatus)}</Tag>
          </Space>
        )}
        extra={(
          <Space wrap>
            {['failed', 'stopped', 'unknown'].includes(taskStatus) && current && (
              <Button
                type="primary"
                disabled={resuming}
                title="基于该任务的检查点继续训练：连优化器/学习率/随机数状态一起恢复，等价于没中断过"
                onClick={() => void resumeTask(current)}
              >
                {resuming ? '提交中…' : '⟲ 基于检查点继续训练'}
              </Button>
            )}
            {taskStatus === 'running' ? (
              <Button danger onClick={() => current && void stopTask(current)}>停止任务</Button>
            ) : (
              <Button danger onClick={() => current && removeTask(current)}>删除任务</Button>
            )}
          </Space>
        )}
      >
        {current && (
          <>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>
              发起 {current.created_at}{durOf({ ...current, status: taskStatus }) ? ` · ${taskStatus === 'running' ? '已跑' : '耗时'} ${durOf({ ...current, status: taskStatus })}` : ''}
            </div>

            {/* ① 状态摘要 */}
            <Space size={4} wrap style={{ marginBottom: 12 }}>
              <Tag color={STATUS_COLOR[taskStatus] || 'default'}>{statusCn(taskStatus)}</Tag>
              <Tag>{methodCn(current.method || current.type)}</Tag>
              {current.rc !== null && current.rc !== undefined && (
                <Tag color={current.rc === 0 ? 'green' : 'red'}>训练退出码 {current.rc}</Tag>
              )}
              {gate && (
                <Tag color={gate.status === 'done' ? (gate.passed ? 'green' : 'red') : 'blue'}>
                  门禁 {gate.status === 'done' ? `${gate.passed ? '通过' : '未通过'} ${gate.score}` : gate.status}
                </Tag>
              )}
              {baseOfTask(current) && <Tag title={rawBaseOf(current)}>基座 {baseOfTask(current)}</Tag>}
              {dsOf(current) && <Tag title={rawDsOf(current)}>训练集 {dsOf(current)}</Tag>}
              {finalName && <Tag title={finalName}>最终产物 {finalName}</Tag>}
              {current.pid ? <Tag>PID {current.pid}</Tag> : null}
              {taskStatus === 'running' && current.pct != null && <Tag>进度 {livePct(current)}%</Tag>}
              {current.steps_total ? <Tag>步数 {current.steps_done || 0} / {current.steps_total}</Tag> : null}
              {current.loss != null && <Tag>loss {fmtLoss(current.loss)}</Tag>}
            </Space>

            {/* ② 产物链 */}
            <Divider orientation="left" plain style={{ fontSize: 13 }}>产物链</Divider>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>
              {(pipeline.artifacts || []).length} 份产物 · 阶段自动衔接，失败可单独重试
            </div>
            {/* 台账去向说明（Jobs.vue:364-368 逐字；BUG-PARITY-12 起链接为真） */}
            <div style={{ fontSize: 11.5, color: '#888', marginBottom: 8 }}>
              磁盘上的全部产物可在
              <Typography.Link onClick={() => { closeDetail(); openLedger() }}>「全部产物」</Typography.Link>
              查看 / 删除；产物删除不可恢复。
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 10 }}>
              {pipelineSlots.map(renderSlot)}
            </div>

            {/* ③ 本次配置（与「发起任务」同一套 schema，渲染成只读；
                BUG-PARITY-12：对拍口径=全量 schema 行、实跑没设置的也显示「—」
                （ConfigPanel ro :50 `shown(k) || '—'` + Jobs.vue roGroups :787-795），
                而不是只渲染有值项——只渲染有值项会让用户以为这次训练只有这几个参数。 */}
            <div data-testid="ro-config">
              <Divider orientation="left" plain style={{ fontSize: 13 }}>本次配置</Divider>
              <div style={{ ...MUT12, marginBottom: 6 }}>与「发起任务」同一套参数定义 · 实跑值</div>
              {schemaGroups.length > 0 ? (
                <>
                  <Space wrap size={6} style={{ marginBottom: 8 }}>
                    <Input
                      size="small"
                      style={{ width: 240 }}
                      placeholder="搜索配置项（名称、说明或键名）"
                      value={roQuery}
                      onChange={(e) => setRoQuery(e.target.value)}
                      allowClear
                    />
                    <Button size="small" onClick={() => setRoClosed(() => {
                      const anyClosed = schemaGroups.some((g) => roClosed[g.group])
                      const next: Record<string, boolean> = {}
                      if (!anyClosed) schemaGroups.forEach((g) => { next[g.group] = true })
                      return next
                    })}>{schemaGroups.every((g) => !roClosed[g.group]) ? '▾ 全部收起' : '▸ 全部展开'}</Button>
                    <span style={MUT12}>{schemaGroups.reduce((n, g) => n + (g.params || []).length, 0)} 项</span>
                  </Space>
                  {schemaGroups.map((g) => {
                    const q = roQuery.trim().toLowerCase()
                    const rows = (g.params || []).filter((p) => !q
                      || String(p.label || '').toLowerCase().includes(q)
                      || String(p.key).toLowerCase().includes(q)
                      || String(p.hint || '').toLowerCase().includes(q)
                      || String(p.explain || '').toLowerCase().includes(q))
                    if (!rows.length) return null
                    // 搜索时强制展开（ConfigPanel isOpen :194 同式）
                    const closed = !q && !!roClosed[g.group]
                    return (
                      <div key={g.group} style={{ marginBottom: 8 }}>
                        <div
                          data-rogroup={g.group}
                          role="button"
                          style={{ fontSize: 12, color: '#888', borderBottom: '1px dashed #e8e8e8', paddingBottom: 2, marginBottom: 4, cursor: 'pointer' }}
                          onClick={() => setRoClosed((p) => ({ ...p, [g.group]: !p[g.group] }))}
                        >
                          {closed ? '▸' : '▾'} {g.group}
                          <span style={{ marginLeft: 6 }}>（{rows.length} / {(g.params || []).length} 项 · 实跑没设置的显示「—」）</span>
                        </div>
                        {!closed && rows.map((p) => {
                          const raw = usedValues[p.key]
                          const full = raw === undefined || raw === null
                            ? '' : (Array.isArray(raw) ? raw.join(', ') : String(raw))
                          const short = SHORT_KEYS.includes(p.key)
                          const shownVal = short && full ? (baseName(full) || full) : full
                          const tip = full || String(p.hint || p.explain || '')
                          return (
                            <Kv key={p.key} hook={p.key} k={p.label} title={tip || undefined}>
                              {shownVal
                                ? <span style={short ? undefined : MONO}>{shownVal}</span>
                                : <span style={{ color: '#bbb' }}>—</span>}
                            </Kv>
                          )
                        })}
                      </div>
                    )
                  })}
                </>
              ) : (
                <div>
                  {paramRows.map((r) => <Kv key={r.k} k={r.label} title={r.v}><span style={MONO}>{r.v}</span></Kv>)}
                  {paramRows.length === 0 && <div style={{ fontSize: 12, color: '#888' }}>（这条任务没有记录配置）</div>}
                </div>
              )}
            </div>
            <details style={{ marginTop: 8 }}>
              <summary style={{ cursor: 'pointer', fontSize: 12, color: '#888' }}>原始命令行（核对用）</summary>
              <pre style={preStyle(300)}>{prettyParams}</pre>
            </details>

            {/* ④ 评测门禁 */}
            <Divider orientation="left" plain style={{ fontSize: 13 }}>
              评测门禁
              {canGate && (
                <Button size="small" style={{ marginLeft: 10 }} disabled={gate?.status === 'running'} onClick={() => { setGateForm({ eval_dataset: '', limit: 10, threshold: 0.3, max_new_tokens: 128 }); setGateOpen(true) }}>
                  {gate?.status === 'running' ? '评测中…' : '跑评测门禁'}
                </Button>
              )}
            </Divider>
            {gate ? (
              <Space size={4} wrap>
                <Tag color={gate.status === 'running' ? 'blue' : gate.status === 'failed' ? 'red' : 'green'}>
                  {gate.status === 'running' ? '评测中' : gate.status === 'failed' ? '失败' : '已完成'}
                </Tag>
                {gate.score !== null && gate.score !== undefined && <span>char-F1 <b>{gate.score}</b></span>}
                {typeof gate.detail === 'object' && gate.detail?.rouge_l != null && (
                  <span style={{ color: '#888' }}>ROUGE-L {String(gate.detail?.rouge_l)}</span>
                )}
                {gate.threshold !== null && gate.threshold !== undefined && <span style={{ color: '#888' }}>阈值 {gate.threshold}</span>}
                {gate.status === 'done' && <Tag color={gate.passed ? 'green' : 'red'}>{gate.passed ? '通过' : '未通过'}</Tag>}
                {gate.n_items ? <span style={{ color: '#888' }}>{gate.n_items} 条</span> : null}
                {gate.error && <span style={{ color: '#a8071a', fontSize: 12 }}>{gate.error}</span>}
              </Space>
            ) : (
              <div style={{ fontSize: 12, color: '#888' }}>
                {canGate
                  ? '还没有跑过门禁。训练完成后跑一次冻结评测集，未通过会阻止模型发布。'
                  : '（当前任务未到可跑门禁的状态：需训练类方法且成功完成）'}
              </div>
            )}

            {/* ⑤ 运行日志（默认收起） */}
            <Divider orientation="left" plain style={{ fontSize: 13 }}>
              运行日志
              <span style={{ marginLeft: 10 }}>
                {showLog && (
                  <>
                    <Checkbox checked={autoFollow} onChange={(e) => setAutoFollow(e.target.checked)}>
                      <span style={{ fontSize: 12 }}>自动跟随</span>
                    </Checkbox>
                    <Button size="small" style={{ marginLeft: 8 }} onClick={() => void refreshLog()}>刷新</Button>
                  </>
                )}
                <Button size="small" style={{ marginLeft: 8 }} onClick={() => { const nv = !showLog; setShowLog(nv); if (nv) void refreshLog() }}>
                  {showLog ? '收起日志' : '展开日志'}
                </Button>
              </span>
            </Divider>
            {showLog && <pre style={preStyle()}>{log || '（暂无输出）'}</pre>}
          </>
        )}
      </Drawer>

      {/* ── 发起训练 / 产出任务 4 步向导（BUG-PARITY-11，Vue :102-262）──
          标题带步号 / StepHeader 等价＝antd Steps 只许点回已完成步 /
          末步自动 dry-run：命令拼不出来（dryError）或没回（dryChecking）时发起钮 disabled。 */}
      <Modal
        open={wizOpen}
        title={'发起训练 / 产出任务 · ' + (wizStep + 1) + ' / ' + WIZ_STEPS.length}
        width={880}
        maskClosable={false}
        onCancel={closeWizard}
        footer={(
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Button onClick={closeWizard}>取消</Button>
            <div style={{ flex: 1 }} />
            {wizStep > 0 && <Button onClick={() => setWizStep((s) => s - 1)}>← 上一步</Button>}
            {wizStep < WIZ_STEPS.length - 1 ? (
              <Button type="primary" disabled={!canNextWiz} onClick={() => void wizNextStep()}>
                {(WIZ_NEXT_LABEL[wizStep] || '下一步')} →
              </Button>
            ) : (
              <Button type="primary" disabled={submitting || dryChecking || !!dryError} onClick={() => void submitWiz()}>
                {submitting ? '提交中…' : '确认发起训练'}
              </Button>
            )}
          </div>
        )}
      >
        <Steps
          size="small"
          current={wizStep}
          onChange={wizGoStep}
          items={WIZ_STEPS.map((t) => ({ title: t }))}
          style={{ marginBottom: 16 }}
        />

        {/* ① 选任务（Vue :107-141） */}
        {wizStep === 0 && (
          <div>
            <Space wrap size={6} style={{ marginBottom: 10 }}>
              {launchMethods.map((m) => (
                <Button
                  key={m}
                  size="small"
                  type={wizForm.method === m ? 'primary' : 'default'}
                  onClick={() => void wizSetMethod(m)}
                >
                  {m} · {methodCn(m)}
                </Button>
              ))}
            </Space>
            <div style={{ ...WIZ_NOTE, marginTop: 0, marginBottom: 10 }}>
              融合 / 量化不再是单独任务：它们是训练任务里的阶段，在下一步「产物」里选。
              需要单独处理已有目录时，去
              <Typography.Link onClick={() => { closeWizard(); openLedger() }}>全部产物</Typography.Link>
              （融合 / 量化 / 登记 / 删除都在那里）。
            </div>
            {wizTraining && (
              <div style={{ maxWidth: 420 }}>
                <div style={MUT12}>数据集（已发布）</div>
                <Select
                  style={{ width: '100%' }}
                  value={wizForm.dataset_id}
                  onChange={(v) => setWizForm((p) => ({ ...p, dataset_id: String(v ?? '') }))}
                  options={[{ value: '', label: '（选一个）' }]
                    .concat(frozenDs.map((d) => ({ value: d.id, label: `${d.name} · ${d.version}` })))}
                />
                {!frozenDs.length && <div style={{ ...MUT12, marginTop: 4 }}>还没有已发布数据集 —— 先去「训练集」页发布</div>}
                {blockedDsCount > 0 && (
                  <div style={{ fontSize: 12, color: '#cf1322', marginTop: 4 }}>
                    已隐藏 {blockedDsCount} 个占位标签数据集，不能训练
                  </div>
                )}
              </div>
            )}
            {wizTraining && !frozenDs.length ? (
              <Alert
                style={{ marginTop: 8 }}
                type="info"
                showIcon
                message={(
                  <>这里没有可训练的数据。去
                    <Typography.Link onClick={() => { closeWizard(); navigate('/mos/datasets') }}>训练集页</Typography.Link>
                    审核并通过至少 1 条，再「发布」。</>
                )}
              />
            ) : (!wizTraining ? (
              <Alert style={{ marginTop: 8 }} type="info" showIcon
                     message={`${methodCn(wizForm.method)} 用的是已有产物 / 模型，不需要数据集；下一步在参数里选择模型或适配器。`} />
            ) : null)}
            <div style={WIZ_NOTE}>先选做什么、用哪份已发布数据；下一步再改参数。</div>
          </div>
        )}

        {/* ② 调参数（Vue :143-160；预设行 :145-148、存为默认 :156-159） */}
        {wizStep === 1 && (
          <div>
            {Object.keys(wizPresets).length > 0 && (
              <Space wrap size={6} style={{ marginBottom: 8 }}>
                <span style={MUT12}>一键套用：</span>
                {Object.entries(wizPresets).map(([name, pv]) => (
                  <Button key={name} size="small" onClick={() => applyPresetWiz(pv)}>{name}</Button>
                ))}
              </Space>
            )}
            <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
              <Input
                style={{ maxWidth: 260 }}
                placeholder="搜索配置项（名称、说明或键名）"
                value={wizQuery}
                onChange={(e) => setWizQuery(e.target.value)}
                allowClear
              />
              <Button size="small" onClick={wizToggleAll}>{wizAllOpen ? '▾ 全部收起' : '▸ 全部展开'}</Button>
              <span style={MUT12}>{wizAllItemCount} 项</span>
            </div>
            {wizVisibleGroups.map((g) => {
              const rows = wizShownRows(g)
              const open = wizGroupOpen(g)
              const hiddenAdv = wizAdvHiddenIn(g)
              return (
                <div key={g.group} style={{ border: '1px solid #f0f0f0', borderRadius: 6, marginBottom: 8, padding: '0 10px' }}>
                  <div
                    onClick={() => setWizClosed((p) => ({ ...p, [g.group]: open }))}
                    style={{ cursor: 'pointer', display: 'flex', gap: 8, alignItems: 'baseline', padding: '8px 0' }}
                  >
                    <span style={{ fontSize: 12 }}>{open ? '▾' : '▸'}</span>
                    <b style={{ fontSize: 13 }}>{g.group}</b>
                    <span style={MUT12}>{rows.length} / {(g.params || []).length} 项</span>
                  </div>
                  {open && (
                    <>
                      {rows.map(renderWizParam)}
                      {hiddenAdv > 0 && !wizQuery.trim() && (
                        <div style={{ padding: '6px 0' }}>
                          <Button size="small" onClick={() => setAdvShown((p) => ({ ...p, [g.group]: !p[g.group] }))}>
                            {advShown[g.group] ? '▴ 收起高级参数' : `▾ 展开高级参数（${hiddenAdv} 项）`}
                          </Button>
                        </div>
                      )}
                    </>
                  )}
                </div>
              )
            })}
            {!wizVisibleGroups.length && <div style={{ ...MUT12, padding: '8px 0' }}>没有匹配的配置项</div>}
            <div style={{ ...MUT12, marginTop: 10 }}>
              训练 / 量化前请自行腾空 GPU（停 vLLM，约占 13GB）。平台会记录启动时的卡占用，但
              <b>不会替你拦截</b>；显存不足会由训练进程自己报错。
            </div>
            <Space style={{ marginTop: 8 }} wrap>
              <Button size="small" onClick={saveAsDefaultWiz}>存为默认参数</Button>
              <span style={MUT12}>只影响训练环境里 {wizForm.method} 的后续默认值。</span>
            </Space>
          </div>
        )}

        {/* ③ 产物形态（Vue :162-216；全参→adapter 不可选且自动跳形 :583-586） */}
        {wizStep === 2 && (
          <div>
            {wizForm.method === 'embed' ? (
              <Alert type="info" showIcon message="检索适配直接产出 embedding 模型（可直接评测 / 部署），没有融合、量化阶段。" />
            ) : (
              <>
                <div style={{ ...MUT12, marginBottom: 10 }}>
                  产物属于这次训练：跑完后在任务详情里按「训练 → 融合 → 量化」三段展示，不再拆成三个任务。
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 10 }}>
                  {SHAPE_OPTIONS.map((o) => {
                    const off = o.v === 'adapter' && wizIsFullTuner
                    const on = artForm.shape === o.v
                    return (
                      <label
                        key={o.v}
                        style={{
                          border: '1px solid ' + (on ? '#1677ff' : '#d9d9d9'),
                          background: off ? '#f5f5f5' : (on ? '#e6f4ff' : '#fff'),
                          borderRadius: 8, padding: '10px 12px',
                          cursor: off ? 'not-allowed' : 'pointer', opacity: off ? 0.6 : 1,
                        }}
                      >
                        <Space align="start" size={6}>
                          <input
                            type="radio"
                            name="wiz-shape"
                            checked={on}
                            disabled={off}
                            onChange={() => setArtForm((p) => ({ ...p, shape: o.v }))}
                          />
                          <span>
                            <b style={{ fontSize: 13 }}>{o.t}</b>
                            <div style={MUT12}>{o.d}</div>
                            <div style={{ ...MONO, fontSize: 11, color: '#999', marginTop: 4 }}>{o.out}</div>
                          </span>
                        </Space>
                      </label>
                    )
                  })}
                </div>
                {artForm.shape === 'merged_quant' && (
                  <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 12, marginTop: 12 }}>
                    <b style={{ fontSize: 13 }}>量化设置</b>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 8 }}>
                      <div>
                        <div style={MUT12}>量化方式</div>
                        <Select
                          style={{ width: '100%' }}
                          value="awq"
                          disabled
                          options={[{ value: 'awq', label: 'AWQ 4bit（静态量化，产出可直接部署的模型文件）' }]}
                        />
                        <div style={{ ...MUT12, marginTop: 4 }}>部署页的 bnb 4bit 是加载时量化，不改变模型文件，因此不在这里选。</div>
                      </div>
                      <div>
                        <div style={MUT12}>校准条数</div>
                        <Select
                          style={{ width: '100%' }}
                          value={artForm.calib_n}
                          onChange={(v) => setArtForm((p) => ({ ...p, calib_n: Number(v) }))}
                          options={[
                            { value: 128, label: '128 条 · 正式定版（默认）' },
                            { value: 12, label: '12 条 · 快速出可跑版本' },
                          ]}
                        />
                        <div style={{ ...MUT12, marginTop: 4 }}>校准集默认用本次训练语料自动生成。</div>
                      </div>
                    </div>
                    <div style={{ marginTop: 8 }}>
                      <div style={MUT12}>校准语料（可选）</div>
                      <Select
                        style={{ width: '100%' }}
                        value={artForm.calib_dataset_id}
                        onChange={(v) => setArtForm((p) => ({ ...p, calib_dataset_id: String(v ?? '') }))}
                        options={[{ value: '', label: '默认：本次训练语料自动生成' }]
                          .concat(frozenDs.map((d) => ({ value: d.id, label: `${d.name} · ${d.version}` })))}
                      />
                    </div>
                  </div>
                )}
                <Alert
                  style={{ marginTop: 10 }}
                  type="info"
                  showIcon
                  message={`训练成功后自动继续：${shapeChainText}。任何一步失败，任务详情里的「产物链」可以单独重试。`}
                />
              </>
            )}
            <div style={WIZ_NOTE}>
              预估时间：融合取决于模型大小（1.7B 约 1–2 分钟）；AWQ 量化 128 条校准通常 3–8 分钟，
              12 条约 1–2 分钟。都会占用同一张 GPU。
            </div>
          </div>
        )}

        {/* ④ 核对并确认（Vue :218-248；dry-run 闸 :993-1008） */}
        {wizStep === 3 && (
          <div>
            <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 12px' }}>
              <Kv k="任务"><b>{wizForm.method}</b> · {methodCn(wizForm.method)}</Kv>
              {wizTraining && <Kv k="训练集">{wizDatasetName(wizForm.dataset_id)}</Kv>}
              {wizTraining && (
                <Kv k="产物形态">
                  {SHAPE_LABEL[artForm.shape] || '—'}
                  {artForm.shape === 'merged_quant' ? <span style={MUT12}>（校准 {artForm.calib_n} 条）</span> : null}
                </Kv>
              )}
              <Kv k="参数">{wizParamCount} 项（留空的项用默认 / 自动）</Kv>
            </div>
            {dryChecking ? (
              <Alert style={{ marginTop: 12 }} type="info" showIcon message="正在核对命令，不会真正启动…" />
            ) : dryError ? (
              <Alert
                style={{ marginTop: 12 }}
                type="error"
                showIcon
                message={<><b>命令核对没通过，先别发起：</b>{dryError}</>}
                action={<Button size="small" onClick={() => void checkDryWiz()}>重试</Button>}
              />
            ) : dry ? (
              <>
                <Space style={{ margin: '14px 0 6px', width: '100%', justifyContent: 'space-between' }} wrap>
                  <b style={{ fontSize: 13 }}>训练阶段命令（后续阶段自动执行）</b>
                  <Space>
                    <Tag color="green">核对通过</Tag>
                    <Button size="small" onClick={() => void checkDryWiz()}>重新核对</Button>
                  </Space>
                </Space>
                <pre style={preStyle(240)}>{dry}</pre>
              </>
            ) : (
              /* 没核对成功就不该亮「核对通过」绿标（正常流程进末步必自动核对，走到这里
                  =自动核对没发生/空命令回包，显示诚实的未核对态，不假装核验过） */
              <Alert
                style={{ marginTop: 12 }}
                type="warning"
                showIcon
                message="命令还没核对过：点「重新核对」生成命令，核对通过后发起的才是这条命令。"
                action={<Button size="small" onClick={() => void checkDryWiz()}>重新核对</Button>}
              />
            )}
            <div style={{ ...MUT12, marginTop: 10 }}>
              确认后任务在后台启动，可以离开本页；进度、日志和停止都在任务卡里。
            </div>
          </div>
        )}
      </Modal>

      {/* 跑评测门禁（Jobs.vue:437-469） */}
      <Modal
        open={gateOpen}
        title="跑评测门禁"
        width={560}
        onCancel={() => setGateOpen(false)}
        onOk={() => void runGate()}
        okText="开始评测"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>冻结评测集（JSONL，含 prompt / reference）</div>
            <Input
              value={gateForm.eval_dataset}
              placeholder="留空 = data/public/eval_alpaca_zh_30.jsonl"
              onChange={(e) => setGateForm((p) => ({ ...p, eval_dataset: e.target.value }))}
            />
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>评测条数</div>
              <InputNumber min={1} max={50} style={{ width: '100%' }} value={gateForm.limit}
                           onChange={(v) => setGateForm((p) => ({ ...p, limit: Number(v ?? 10) }))} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>通过阈值（char-F1）</div>
              <InputNumber min={0} max={1} step={0.01} style={{ width: '100%' }} value={gateForm.threshold}
                           onChange={(v) => setGateForm((p) => ({ ...p, threshold: Number(v ?? 0.3) }))} />
            </div>
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>最大生成长度</div>
            <InputNumber min={16} max={512} style={{ width: 200 }} value={gateForm.max_new_tokens}
                         onChange={(v) => setGateForm((p) => ({ ...p, max_new_tokens: Number(v ?? 128) }))} />
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>pt 后端较慢；先用 5–10 条验证链路，正式门禁再加条数。</div>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            跑完会记录在任务里；模型发布时如果最近一次门禁未通过，会被拒绝。
          </Typography.Text>
        </div>
      </Modal>

      {/* 产物就地登记到模型库（Jobs.vue:471-487） */}
      <Modal
        open={regOpen}
        title="登记产物到模型库"
        width={560}
        onCancel={() => setRegOpen(false)}
        onOk={() => void doRegister()}
        okText="登记"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>产物目录</div>
            <Input value={reg.path} style={MONO} disabled />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>模型名</div>
            <Input value={reg.name} onChange={(e) => setReg((p) => ({ ...p, name: e.target.value }))} />
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>基座</div>
              <Input value={reg.base} placeholder="留空可后补" onChange={(e) => setReg((p) => ({ ...p, base: e.target.value }))} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>方法</div>
              <Input value={reg.method} placeholder={reg.kind} onChange={(e) => setReg((p) => ({ ...p, method: e.target.value }))} />
            </div>
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>备注</div>
            <Input value={reg.notes} onChange={(e) => setReg((p) => ({ ...p, notes: e.target.value }))} />
          </div>
        </div>
      </Modal>
    </div>
  )
}
