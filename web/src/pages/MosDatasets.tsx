import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Button, Checkbox, Divider, Drawer, Input, InputNumber, Modal, Segmented, Select, Space, Steps,
  Table, Tag, Typography, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { MOS_DATA_ORIGIN_PLACEHOLDER } from '../terms'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import {
  dwConfirm, kbActivate, listPagination, useDebouncedValue, useDetailHistory, useGlobalPageSize,
} from '../utils/listPrefs'
import {
  addDatasetItems, bulkDatasetItems, catalogDatasets, catalogDownload, catalogDownloads,
  catalogImport, catalogProbe, createDataset, createDatasource, datasourceFiles,
  datasourceText, datasourceTypes, datasetExportRawUrl, datasetExportUrl,
  datasetItems, datasetQuality, datasetRefs, deleteCatalogDownload, deleteDataset,
  deleteDatasource, deleteDatasourceFiles, deleteDeployment, deleteEval, deleteJob,
  deleteModel, freezeDataset, getDataset,
  listDatasets, listDatasources, listLsProjects, lsSyncMap, lsToDataset, mosMediaUrl,
  patchDatasetItem, patchDatasource, previewDatasource, putDatasourceText, setDatasetOrigin,
  streamvisionCandidates, testDatasource, uploadDatasetItems, uploadDatasourceFiles,
  validateDataset, validateDatasetItem,
} from '../api/mos'
import type {
  MosCatalogBody, MosCatalogEntry, MosCatalogLocal, MosCatalogProbe, MosDataset,
  MosDatasetAddResult, MosDatasetItem, MosDatasetItemsPage, MosDatasetRef, MosDatasource,
  MosLsProject, MosLsSyncEntry, MosLsSyncLast, MosQualityOverview,
} from '../api/mos'

/**
 * 数据集管理（MOS）——REQ-UNIFY-5 重写页：mos/platform/frontend/src/views/Datasets.vue（1872 行）。
 *
 * REQ-UNIFY-5 首批＝列表 / 详情 / 上传入口三件事；BUG-PARITY-9-A（2026-10-29）回搬老页
 * 第一批能力：②条目审核（单条 quick :369-370,1437 + 批量 :323-332,1421-1435）、③条目编辑+
 * human_edited 标（:371,664-672,1506-1515）、④发布 freeze（:181-206,1566-1579）、⑤质检校验
 * （:301-322,1485-1504）、⑥删除前引用清单弹窗（:1252-1270,712-769,1306-1335，2026-10-23 项目负责人
 * 反馈链）、⑦导出 JSONL/原始标注（:199-201）、⑫导入诚实性三分支（:1746-1756）、⑬来源列中文
 * （:955-963）。后端端点全现成（datasets.py），页面零自带 send。
 * BUG-PARITY-9-B（2026-10-30）回搬第二批上：①新建两步向导（四来源模式 :574-662,831-840 + 逻辑
 * :1084-1206：导入文件 / 标注同步（就地选项目同步）/ 数据源生成 / 手工录入；确认页 afterCreateHint
 * :946-951；建后直达引导条 :164-172 + runFirstAction :1171-1175）、⑧公开语料全流程（目录弹窗
 * :397-482 + 逻辑 :1606-1722：选语料→导入、探测、全量下载到本地+分批导入不关窗、导入到当前集
 * importCatalogIntoCurrent 与 source-actions 卡 :208-222）。通道=api/mos.ts catalog* 六封装 +
 * lsToDataset，页面零自带 send。
 * BUG-PARITY-9-C（2026-10-30）回搬第二批下：⑨LS 同步横幅回链（ds-prov 三分支 :131-162 +
 * annotationProjectOf/lastSyncOf :1022-1026 + isAnnotationData 补齐 sync-map 反查半边 :1027-1028，
 * 通道=api/mos.ts lsSyncMap，读失败兜空=MosLabels.tsx:207 同型容错）、⑩数据源就地管理
 * （列表工具栏「数据源 N ▲▼」:29-32 + 详情横幅「管理数据源」:161→DataSources CRUD 弹窗，
 * api/mos.ts datasource* 全套封装本卡接上=首个消费方；关窗回刷列表=Vue toggleDS:881）、
 * ⑪四统计卡（:174-179，口径=既有 stat memo :1004-1010 服务端全量 counts）。
 * 仍在 Vue 原页待回搬：生成/蒸馏卡片（gen-card 不在 9 任何子卡，generate 模式「滚动到生成区」
 * 与横幅「继续生成 →」因此缺落点，照 Vue :1164-1168 防御式 if(el) 静默）；
 * 「用它训练」入口不做（9 卡面点名）；老页 ?view=catalog 深链 :1588-1600 站内无消费方不做。
 *
 * 对照锚点（datasets.py 行号）：列表聚合 163（裸数组，行带 counts/media_counts 聚合）、
 * 建集 192、详情 301（裸行、**无** counts）、属性 282（human 需核验否则 400）、
 * 删除 436（409 带引用清单文案）、条目分页 493（paged=1 → {items,total,all_total,counts}）、
 * 录入 1008、文件上传 1034（multipart 字段 file，>32MB 413，按归一化 q 去重）。
 * 数据/媒体两口径＝列表行同时呈现 counts（草稿/已批/已拒，数据面）与 media_counts（音频/视频/…，
 * 媒体面，datasets.py:180 注释「不用点进去才发现是音频/视频」）；条目行带媒体预览（media_exists
 * 由后端探测，false=文件已不在媒体库，Datasets.vue:677-683 口径）。
 * 术语口径沿用 Datasets.vue:15-19：数据集=一组条目；数据源=造数据的原料；发布只包含「已批」条目。
 * REQ-UX-FLOW-1#1（2026-10-29 收敛）：本页属训练域，ds_ 实体对用户改称「训练集」；「数据集」为旧称，
 * 术语行保留一次映射说明（名实一致不报错）；标注/治理页各自称「标注集/治理对象」。
 * 深链：/mos/datasets?ds=<id>（对照 MosJobs 的 ?job=）。
 */

const ORIGIN: Record<string, { label: string; color: string }> = {
  human: { label: '人工数据', color: 'green' },
  imported_reference: { label: '导入参考', color: 'blue' },
  [MOS_DATA_ORIGIN_PLACEHOLDER]: { label: '占位标签', color: 'red' },
  synthetic_speech: { label: '合成语音', color: 'purple' },
  unknown: { label: '来源未标', color: 'default' },
}
const ORIGIN_OPTIONS = Object.entries(ORIGIN).map(([value, o]) => ({ value, label: o.label }))
const originLabel = (o?: string | null) => (ORIGIN[o || 'unknown'] || ORIGIN.unknown).label
const originColor = (o?: string | null) => (ORIGIN[o || 'unknown'] || ORIGIN.unknown).color

const DS_STATUS_CN: Record<string, string> = { frozen: '已发布', draft: '草稿' }
const dsStatusTag = (s?: string) => (s === 'frozen'
  ? <Tag color="green">已发布</Tag>
  : <Tag color="gold">{DS_STATUS_CN[s || ''] || '草稿'}</Tag>)

const ITEM_STATUS_CN: Record<string, string> = { draft: '草稿', approved: '已批', rejected: '已拒' }
const ITEM_STATUS_COLOR: Record<string, string> = { draft: 'gold', approved: 'green', rejected: 'red' }
const MEDIA_CN: Record<string, string> = { audio: '音频', video: '视频', image: '图片', text: '文本', other: '其他' }
const kindLabel = (k?: string | null) => MEDIA_CN[k || ''] || k || '无'

/** ⑬ 来源列中文（Vue sourceName :955-963）；local=REQ-UX-FLOW-1#4 平台打标值保留 */
const SOURCE_CN: Record<string, string> = {
  label_studio: '标注同步', manual: '手工录入', imported: '导入文件', imported_reference: '导入参考',
  local: '本地文件',
}

/** ① 新建向导四来源模式（Vue SOURCE_MODES :831-840 逐字；圈号即卡面文案，顺序即视觉顺序） */
const SOURCE_MODES = [
  { key: 'import', title: '① 我有现成数据', desc: 'JSONL / TSV / 公开语料；创建后直接导入。' },
  { key: 'annotation', title: '② 从标注项目同步', desc: '选一个已标注的项目，就地同步建集，不用跳页。' },
  { key: 'generate', title: '③ 从数据源生成', desc: '选数据源 → 模型出题；创建后立刻进入生成。' },
  { key: 'manual', title: '④ 手工录入', desc: '少量规范、经验、FAQ，直接粘贴。' },
]
/** ① 两步制（Vue DS_STEPS :933）；StepHeader 的老页等价物=antd Steps，goDsStep 只允许回退 */
const DS_STEPS = ['来源与信息', '确认']
/** ① 确认页文案（Vue afterCreateHint :946-951 逐字；「数据集」按 REQ-UX-FLOW-1#1 本页称「训练集」） */
const AFTER_CREATE_HINT: Record<string, string> = {
  generate: '确认后会创建训练集，并打开「造数据」卡片；先用小样本生成，检查质量后再放量。',
  import: '确认后会创建训练集，并打开文件导入窗口；重复问题会自动跳过。',
  manual: '确认后会创建训练集，并打开手工录入窗口。',
  annotation: '确认后直接把该项目的标注同步成本训练集；先为草稿，审核通过再发布。',
}
/** ① 建后引导条副文案（Vue firstActionText :978-982 逐字；annotation 模式无此条=建完直接开详情） */
const FIRST_ACTION_TEXT: Record<string, string> = {
  generate: '选素材条数 → 点「开始生成」；生成结果先入库为草稿，还要审核发布。',
  import: '导 JSONL / TSV，或从「公开语料」一键导入；重复问题会自动跳过。',
  manual: '粘贴文本，保存后按空行分段入库。',
}

/** ⑪ 四统计卡（Vue .stats :174-179 逐字：标题/副行/ok·warn·bad 三色；口径=服务端全量 counts 的 stat memo） */
const DS_STATS: { lb: string; sb: string; color?: string; v: (s: { total: number; approved: number; draft: number; rejected: number }) => number }[] = [
  { lb: '条目总数', sb: '含草稿 / 已批 / 已拒', v: (s) => s.total },
  { lb: '已批准', sb: '发布会用这些', color: '#389e0d', v: (s) => s.approved },
  { lb: '草稿', sb: '待审核', color: '#d48806', v: (s) => s.draft },
  { lb: '已拒绝', sb: '不入训练', color: '#cf1322', v: (s) => s.rejected },
]

/** ⑩ 上传文档 accept 串（DataSources.vue:105/:141 两处同一份，逐字保留防后端拒收口径漂移） */
const DSM_ACCEPT = '.txt,.md,.markdown,.jsonl,.json,.csv,.tsv,.html,.htm,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.xlsm,.jpg,.jpeg,.png,.webp,.bmp'

/** ⑤ 质检结论展示（Vue VCN/vTag :1469-1471；未检=verdict 空） */
const VCN: Record<string, string> = { pass: '通过', warn: '警告', fail: '严重' }
const VCOLOR: Record<string, string> = { pass: 'green', warn: 'gold', fail: 'red' }
const vCn = (v?: string | null) => VCN[v || ''] || '未检'
const vColor = (v?: string | null) => VCOLOR[v || ''] || 'default'

/** ⑤ 问题码中文（Vue CODE_CN :1472-1475，14 键逐字） */
const CODE_CN: Record<string, string> = {
  empty_q: '问题空', empty_a: '答案空', q_short: '问题短', a_short: '答案短',
  a_long: '答案长', garble: '乱码', answer_leak: '答案泄漏', q_eq_a: '问答相同',
  pronoun: '指代不明', lang_mix: '中英夹杂', dup_q: '问题重复', dup_a: '答案重复',
  unfaithful: '不忠实', not_answerable: '不可独立答',
}
const codeLabel = (c: string) => CODE_CN[c] || c
const parseIssues = (raw?: string | null): { code: string; level?: string; msg?: string }[] => {
  try { return JSON.parse(raw || '[]') || [] } catch (e) { return [] }
}
/** 行内「N 项」title（Vue issueText :1480-1483 逐行「· 标签：msg」） */
const issueText = (it: MosDatasetItem) =>
  parseIssues(it.issues).map((i) => `· ${codeLabel(i.code)}：${i.msg || ''}`).join('\n')

/** ⑥ 引用清单映射（Vue REF_KIND :1221、REF_STATUS :1223-1228） */
const REF_KIND: Record<string, string> = { model: '模型', eval: '评测', job: '训练任务', deployment: '模型服务' }
const refKind = (k: string) => REF_KIND[k] || k
const REF_STATUS: Record<string, [string, string]> = {
  registered: ['已登记', 'green'], running: ['运行中', 'blue'],
  finished: ['已完成', 'green'], done: ['已完成', 'green'],
  failed: ['失败', 'red'], stopped: ['已停止', 'default'],
  draft: ['未跑', 'gold'], queued: ['排队', 'gold'], unknown: ['失联', 'red'],
}
const refStatus = (s?: string | null): [string, string] => REF_STATUS[s || ''] || [s || '—', 'default']

const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }
const clampInt = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, Math.trunc(v)))

/**
 * 确认框（Vue askConfirm 等价物：确认=resolve(true)，取消=resolve(false)）。
 * 批量拒绝 ②、发布前草稿提醒 ④、删除引用/删除数据集 ⑥ 三处都走这里；
 * okText/cancelText 显式给死，不吃 antd 默认 locale（静态 Modal.confirm 不跟随 ConfigProvider）。
 */
function askConfirm(o: { title: string; content: string; okText: string; danger?: boolean }): Promise<boolean> {
  return new Promise((resolve) => {
    // BUG-PARITY-15 ④：换行走平台通用 dwConfirm（≡ ConfirmDialog.vue:39 pre-wrap）；
    // cancelText 仍在本页显式给死（9-A A15：静态 Modal.confirm 不吃 ConfigProvider locale）
    dwConfirm({
      title: o.title,
      content: o.content,
      okText: o.okText,
      cancelText: '取消',
      danger: !!o.danger,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}

function Kv({ k, title, children }: { k: string; title?: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 8, fontSize: 12, lineHeight: '22px' }}>
      <span style={{ color: '#888', flex: '0 0 60px' }}>{k}</span>
      <span title={title} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
        {children}
      </span>
    </div>
  )
}

/** 列表行的媒体徽章：只列数量>0 的 kind（Datasets.vue:59-62 mediaKeys 口径） */
function mediaBadges(d: MosDataset) {
  const keys = Object.keys(d.media_counts || {}).filter((k) => Number(d.media_counts?.[k]) > 0)
  return keys.map((k) => (
    <Tag key={k} title={`${kindLabel(k)} ${d.media_counts![k]} 条`}>{kindLabel(k)} {d.media_counts![k]}</Tag>
  ))
}

export default function MosDatasets() {
  // BUG-PARITY-15 ③：开抽屉=pushState 一条历史，浏览器后退只关抽屉不离开本页
  // （对拍 §三:73——老 Datasets.vue:1340-1341 开/关详情 pushState+「← 返回」钮；
  //  此前全程 replace 使后退直接跳离本页；深链直达 ?ds= 时历史里没有该条，关闭退回 replace 清参）
  const { searchParams, pushDetail, popDetail } = useDetailHistory('ds')
  const navigate = useNavigate()
  // BUG-PARITY-15 ①②：每页条数=平台全局偏好（prefs.js 同键）；列表/条目两张表共用同一个值
  const [globalSize, setGlobalSize] = useGlobalPageSize()

  // —— 列表态 ——
  const [datasets, setDatasets] = useState<MosDataset[]>([])
  const [datasources, setDatasources] = useState<MosDatasource[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<(Error & { status?: number }) | null>(null)
  const [dsQuery, setDsQuery] = useState('')
  const [frozenOnly, setFrozenOnly] = useState(false)
  const [dsPage, setDsPage] = useState(1)
  // —— BUG-PARITY-6/12（wm7）：LS 同步映射 + 数据源就地管理弹窗状态（与上方分页并存）——
  // —— ⑨ LS 同步映射（Vue syncMap/syncLast :845-846；读失败兜空不打断列表=MosLabels.tsx:207 同型）——
  const [syncMap, setSyncMap] = useState<MosLsSyncEntry[]>([])
  const [syncLast, setSyncLast] = useState<Record<string, MosLsSyncLast>>({})
  // —— ⑩ 数据源就地管理弹窗（Vue <DataSources> 内联块 :29-32 + DataSources.vue 全套；React=弹窗）——
  const [dsmOpen, setDsmOpen] = useState(false)

  // —— 详情抽屉态 ——
  const [current, setCurrent] = useState<MosDataset | null>(null)
  const [originDraft, setOriginDraft] = useState('unknown')
  const [page, setPage] = useState<MosDatasetItemsPage>({ items: [], total: 0, all_total: 0, counts: {}, limit: 50, offset: 0 })
  const [itemsLoading, setItemsLoading] = useState(false)
  // BUG-PARITY-15（§三:74）：条目加载失败要「看得见+可重试」，不许静默落回空态=失败与空混淆
  const [itemsError, setItemsError] = useState('')
  const [flt, setFlt] = useState('')
  const [itemQ, setItemQ] = useState('')
  // 条目每页初值=全局偏好（开集时按当时全局值取，≡ prefs.js 全局单值语义）
  const [itemLimit, setItemLimit] = useState(globalSize)
  const [itemPage, setItemPage] = useState(1)
  const currentIdRef = useRef('')
  const openSeqRef = useRef(0)
  const lastSentQRef = useRef('')

  // —— ① 建集两步向导（Vue showForm/form/dsStep/importKind/firstAction/lsProjects 同位）——
  const [createOpen, setCreateOpen] = useState(false)
  const [form, setForm] = useState({ name: '', source_id: '', mode: 'import', project_id: 0 })
  const [creating, setCreating] = useState(false)
  const [dsStep, setDsStep] = useState(0)
  const [importKind, setImportKind] = useState('file')
  /** 建后直达引导条的模式标记；''=不显示。Vue open():1337/closeDetail 都复位，切集不许残留 */
  const [firstAction, setFirstAction] = useState('')
  const [lsProjects, setLsProjects] = useState<MosLsProject[]>([])
  const [lsLoading, setLsLoading] = useState(false)
  // —— ⑧ 公开语料目录（Vue :877-892 全套状态）——
  const [catOpen, setCatOpen] = useState(false)
  const [catalogList, setCatalogList] = useState<MosCatalogEntry[]>([])
  const [cat, setCat] = useState({
    entry_id: '', source: 'hf', repo: '', limit: 500, name: '', qcol: 'instruction', acol: 'output',
  })
  const [catProbe, setCatProbe] = useState<MosCatalogProbe | null>(null)
  const [downloads, setDownloads] = useState<MosCatalogLocal[]>([])
  const [dlDir, setDlDir] = useState('')
  const [dlBusy, setDlBusy] = useState(false)
  const [catBusy, setCatBusy] = useState(false)
  const [catOffset, setCatOffset] = useState(0)
  const [catTotal, setCatTotal] = useState<number | null>(null)
  const [catDatasetId, setCatDatasetId] = useState('')
  const [catLocal, setCatLocal] = useState('')
  const [manOpen, setManOpen] = useState(false)
  const [manMode, setManMode] = useState('one')
  const [man, setMan] = useState({ q: '', a: '', bulk: '' })
  const [manFile, setManFile] = useState<File | null>(null)
  const [importing, setImporting] = useState(false)
  const [mediaOpen, setMediaOpen] = useState(false)
  const [mediaItem, setMediaItem] = useState<MosDatasetItem | null>(null)
  const fileEl = useRef<HTMLInputElement | null>(null)

  // —— ② 选择/批量 ——
  const [checked, setChecked] = useState<string[]>([])
  const [bulkBusy, setBulkBusy] = useState(false)
  // —— ③ 编辑弹窗 ——
  const [editOpen, setEditOpen] = useState(false)
  const [editing, setEditing] = useState<MosDatasetItem | null>(null)
  const [editDraft, setEditDraft] = useState({ q: '', a: '' })
  const [savingEdit, setSavingEdit] = useState(false)
  // —— ④ 发布 ——
  const [freezing, setFreezing] = useState(false)
  // —— ⑤ 质检 ——
  const [showQC, setShowQC] = useState(false)
  const [vApply, setVApply] = useState(false)
  const [vbusy, setVbusy] = useState(false)
  const [quality, setQuality] = useState<MosQualityOverview | null>(null)
  const [vrow, setVrow] = useState<Record<string, boolean>>({})
  // —— ⑥ 引用清单弹窗（Vue :1208-1218 全套状态，refsLoaded=「读成功」与「0 条」必须分开）——
  const [refsOpen, setRefsOpen] = useState(false)
  const [refsTarget, setRefsTarget] = useState<MosDataset | null>(null)
  const [refsList, setRefsList] = useState<MosDatasetRef[]>([])
  const [refsLoaded, setRefsLoaded] = useState(false)
  const [refsError, setRefsError] = useState('')
  const [refsAt, setRefsAt] = useState('')
  const [refsLoading, setRefsLoading] = useState(false)
  const [refsDeleting, setRefsDeleting] = useState(false)
  const [refBusy, setRefBusy] = useState('')

  // —— ⑩ DataSources CRUD 弹窗内部态（DataSources.vue:196-214 同位；列表带 with_stats=1）——
  const [dsmList, setDsmList] = useState<MosDatasource[]>([])
  const [dsmLoading, setDsmLoading] = useState(false)
  const [dsmError, setDsmError] = useState('')
  const [dsmTypes, setDsmTypes] = useState<Record<string, any>[]>([])
  const [dsmBusy, setDsmBusy] = useState<Record<string, boolean>>({}) // 行内「测试中…」
  const [dsmSaving, setDsmSaving] = useState(false) // 建/存/上传共用（Vue saving 单值原语义）
  const [dsmFormOpen, setDsmFormOpen] = useState(false)
  const [dsmEditing, setDsmEditing] = useState<MosDatasource | null>(null)
  const [dsmForm, setDsmForm] = useState({ type: 'streamvision', name: '', config: '', notes: '', text: '' })
  const [svCands, setSvCands] = useState<Record<string, any>[]>([])
  const [svManual, setSvManual] = useState(false)
  const [dsmPicked, setDsmPicked] = useState<File[]>([])
  const dsmPickEl = useRef<HTMLInputElement | null>(null)
  const [dsmFilesOpen, setDsmFilesOpen] = useState(false)
  const [dsmFiles, setDsmFiles] = useState<Record<string, any>[]>([])
  const dsmFileInputEl = useRef<HTMLInputElement | null>(null)
  const [dsmTextOpen, setDsmTextOpen] = useState(false)
  const [dsmTextVal, setDsmTextVal] = useState('')
  const [dsmTextLoading, setDsmTextLoading] = useState(false)
  const [dsmTextError, setDsmTextError] = useState('')
  const [dsmPvOpen, setDsmPvOpen] = useState(false)
  const [dsmPvItems, setDsmPvItems] = useState<Record<string, any>[]>([])
  const dsmCurIdRef = useRef('') // 打开文件/文本子窗的目标；异步回包按 id 校验，晚到的包不许串窗

  // ── 派生：列表筛选（客户端，datasets 列表接口一次全量，datasets.py:163） ──
  const visible = useMemo(() => {
    const q = dsQuery.trim().toLowerCase()
    return datasets.filter((d) => {
      if (frozenOnly && d.status !== 'frozen') return false
      if (!q) return true
      return [d.id, d.name, d.version].map((v) => String(v || '')).join(' ').toLowerCase().includes(q)
    })
  }, [datasets, dsQuery, frozenOnly])
  // BUG-PARITY-15 ②：pager 改受控（有总数就有分页条），筛选变化回第 1 页（8-A 同口径）
  useEffect(() => { setDsPage(1) }, [dsQuery, frozenOnly])

  /** ⑬：打标值中文（SOURCE_CN，Vue :955-957 优先）→ 数据源行反查 → 公开语料/外部导入兜底；
   *  空来源=「未指定」（Vue sourceName :962 逐字，训练集 domain 行 source 可为空串） */
  const sourceName = useCallback((src?: string | null) => {
    if (!src) return '未指定'
    if (SOURCE_CN[src]) return SOURCE_CN[src]
    const d = datasources.find((x) => x.id === src)
    if (d) return d.name || d.id
    return '公开语料 / 外部导入（' + src + '）'
  }, [datasources])

  /** ⑨ 横幅来源副句（Vue sourceHint :966-973 逐字；manual/imported 两固定串之外按数据源命中分派） */
  const sourceHint = useCallback((d?: MosDataset | null) => {
    if (!d) return ''
    if (d.source === 'label_studio') return '标注项目同步进来的条目'
    if (d.source === 'manual') return '手工录入'
    if (d.source === 'imported') return '从文件 / 公开语料导入'
    if (datasources.some((x) => x.id === d.source)) return '从数据源生成'
    return '从公开语料 / 文件导入'
  }, [datasources])

  // ④⑤ 审核口径＝服务端全量 counts（Vue stat :1004-1010：分页壳 counts 优先、fallback 列表行 counts；
  // getDataset 是裸行无 counts，fallback 从 datasets 里取同 id 列表行）
  const stat = useMemo(() => {
    const c = Object.keys(page.counts || {}).length ? page.counts
      : (datasets.find((d) => d.id === current?.id)?.counts) || {}
    const draft = Number(c.draft) || 0
    const approved = Number(c.approved) || 0
    const rejected = Number(c.rejected) || 0
    return { total: draft + approved + rejected, approved, draft, rejected }
  }, [page.counts, datasets, current?.id])

  // ⑨ 数据集→标注项目反查（Vue annotationProjectOf :1022 逐口径：sync-map items 里 dataset_id 命中）
  const annotationProjectOf = useCallback((id?: string | null) =>
    (id ? syncMap.find((x) => String(x.dataset_id) === String(id)) || null : null), [syncMap])

  /** ⑨ 上次同步摘要（Vue lastSyncOf :1023-1026：last 以字符串化 project_id 为键） */
  const lastSyncOf = useCallback((id?: string | null): MosLsSyncLast | null => {
    const e = annotationProjectOf(id)
    return e ? (syncLast[String(e.project_id)] || null) : null
  }, [annotationProjectOf, syncLast])

  // ④⑨ 注脚口径（Vue :1027-1028 全量：source 标了标注，或 sync-map 反查命中——后者覆盖
  // 「先建集再被 to-dataset 绑进同一集」的来源非 label_studio 行；9-A 只做了前者半边，本卡补齐）
  const isAnnotationData = !!current
    && (current.source === 'label_studio' || !!annotationProjectOf(current.id))

  /** ⑧ source-actions 卡分流（Vue canGenerate :1030-1031）：current.source 命中数据源行=能就地生成。
   *  生成卡片（gen-card）属生成/蒸馏批次未迁，本卡只用它决定「导入数据」这张卡出不出。 */
  const canGenerate = !!current && datasources.some((d) => d.id === current.source)

  // ① 向导派生（Vue pickedProject :930-931 / canNextDs :935-943 / sourceModeTitle :944-945）
  const pickedProject = lsProjects.find((p) => p.id === form.project_id) || null
  const canNextDs = form.mode === 'annotation'
    ? (!!pickedProject && pickedProject.annotated > 0 && !!form.name.trim())
    // 公开语料：名称在语料选择器里填，这里只要选到「公开语料」就放行（Vue :940-941）
    : (form.mode === 'import' && importKind === 'catalog' ? true : !!form.name.trim())
  const sourceModeTitle = SOURCE_MODES.find((m) => m.key === form.mode)?.title || ''
  const datasourceName = (id: string) => {
    const d = datasources.find((x) => x.id === id)
    return d ? (d.name || d.id) : (id || '—')
  }
  /** ⑧ 选中语料的一句话说明（Vue catDesc :916-919） */
  const catDesc = catalogList.find((x) => x.id === cat.entry_id)?.desc || ''

  // ── 数据加载（Datasets.vue:1060-1075：数据集/数据源/sync-map 互不依赖，一次并发） ──
  // 返回最新列表行：refreshDetail/doFreeze 要把新鲜行并回 current（Vue 同款取数口径）
  const load = useCallback(async (): Promise<MosDataset[] | null> => {
    if (!datasets.length) setLoading(true)
    try {
      const [ds, dss, sm] = await Promise.all([
        listDatasets(),
        listDatasources().catch(() => []),
        // ⑨ Vue :1069 同型容错：sync-map 读失败=横幅不显，绝不打断列表本身
        lsSyncMap().catch(() => ({ items: [] as MosLsSyncEntry[], last: {} as Record<string, MosLsSyncLast> })),
      ])
      setDatasets(ds)
      setDatasources((dss || []) as MosDatasource[])
      setSyncMap(sm.items || [])
      setSyncLast(sm.last || {})
      setLoadError(null)
      return ds
    } catch (e: any) {
      setLoadError(e)
      return null
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [datasets.length])

  useEffect(() => { void load() }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const loadItems = useCallback(async (dsId: string, opt: { status?: string; q?: string; limit?: number; offset?: number } = {}) => {
    setItemsLoading(true)
    setItemsError('')
    try {
      const r = await datasetItems(dsId, opt)
      if (currentIdRef.current === dsId) setPage(r)
    } catch (e: any) {
      // BUG-PARITY-15（§三:74，Vue :377-382,1400-1407）：先清空——留着上一次结果就是「上一条冒充」；
      // 再给行级重试横幅，失败态不许和空态混同
      if (currentIdRef.current === dsId) {
        setPage((p) => ({ ...p, items: [], total: 0 }))
        setItemsError(String((e && e.message) || e || '未知错误'))
      }
      message.error('条目加载失败: ' + (e?.message || ''))
    } finally {
      setItemsLoading(false)
    }
  }, [])

  /** 打开详情：列表行只有聚合 counts，头部口径以 items 分页壳的 all_total/counts 为准 */
  const openDetail = useCallback(async (rawId: string, sync = true) => {
    const id = String(rawId).trim()
    if (!id || id === currentIdRef.current) return
    const my = ++openSeqRef.current
    setFlt('')
    setItemQ('')
    setItemPage(1)
    setItemLimit(globalSize)
    setItemsError('')
    lastSentQRef.current = ''
    setChecked([]) // Vue open:1340 先清上一条集的选择
    setFirstAction('') // Vue open():1337 复位上一条集的建后引导（create 在其后写回 mode，顺序即 Vue :1195-1196）
    setQuality(null) // Vue open:1354 await loadQuality 同位；上一条集的 chips 不许冒充新集（读失败=未知）
    try {
      const [ds, first, q] = await Promise.all([
        getDataset(id),
        datasetItems(id, { limit: globalSize, offset: 0 }),
        datasetQuality(id).catch(() => null),
      ])
      if (my !== openSeqRef.current) return
      currentIdRef.current = id
      setCurrent(ds)
      setOriginDraft(ds.data_origin || 'unknown')
      setPage(first)
      if (q) setQuality(q)
      if (sync) pushDetail(id)
    } catch (e: any) {
      if (my !== openSeqRef.current) return
      message.error('打开训练集失败: ' + (e?.message || ''))
      if (sync) popDetail()
    }
  }, [searchParams, globalSize, pushDetail, popDetail])

  function closeDetail() {
    openSeqRef.current += 1
    currentIdRef.current = ''
    setCurrent(null)
    setFirstAction('') // Vue closeDetail 同位：返回列表不许还挂着「已创建，接着放第一批数据」
    setPage({ items: [], total: 0, all_total: 0, counts: {}, limit: 50, offset: 0 })
    setItemsError('')
    popDetail()
  }

  // 深链跟随：地址栏 ?ds= 变化即打开对应详情（MosJobs ?job= 同款）
  useEffect(() => {
    const pid = searchParams.get('ds') || ''
    if (pid && pid !== currentIdRef.current) void openDetail(pid, false)
    else if (!pid && currentIdRef.current) { openSeqRef.current += 1; currentIdRef.current = ''; setCurrent(null) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams])

  /** 筛选/翻页/搜索统一走这里（items?paged=1 服务端分页+搜索，datasets.py:493） */
  function queryItems(next: { flt?: string; q?: string; page?: number; limit?: number }) {
    if (!currentIdRef.current) return
    const f = next.flt !== undefined ? next.flt : flt
    const q = next.q !== undefined ? next.q : itemQ
    const p = next.page !== undefined ? next.page : itemPage
    const l = next.limit !== undefined ? next.limit : itemLimit
    setFlt(f); setItemQ(q); setItemPage(p); setItemLimit(l)
    setChecked([]) // Vue setFlt:1466/prevPage:1448/nextPage:1454/onItemSearch:1439 换行集必清选择
    lastSentQRef.current = q
    void loadItems(currentIdRef.current, { status: f, q, limit: l, offset: (p - 1) * l })
  }

  // BUG-PARITY-15 ⑥ 防抖即查（§三:72 ≡ Datasets.vue:289-290,1437-1444：停输 280ms 即查，不再等回车/点按钮）
  const debouncedItemQ = useDebouncedValue(itemQ)
  useEffect(() => {
    if (!currentIdRef.current) return
    if (debouncedItemQ === lastSentQRef.current) return
    queryItems({ q: debouncedItemQ, page: 1 })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debouncedItemQ])

  /**
   * Vue refreshDetail:1455-1465 —— 条目一变后端就把冻结版本作废（status=draft/version=NULL），
   * 光 loadItems+load 不够：抽屉里 current 还拿着旧 frozen 会继续隐藏「发布」。
   * 必须把新鲜列表行并回 current（列表行带 counts，getDataset 裸行没有）。
   */
  async function refreshDetail() {
    if (!currentIdRef.current) return
    await loadItems(currentIdRef.current, { status: flt, q: itemQ, limit: itemLimit, offset: (itemPage - 1) * itemLimit })
    const dsList = await load()
    const fresh = (dsList || []).find((d) => d.id === currentIdRef.current)
    if (fresh) setCurrent((p) => (p && p.id === fresh.id ? { ...p, ...fresh } : p))
  }

  // ── ① 新建两步向导（Vue :1084-1206；建集=datasets.py:194，标注同步=label_studio.py to-dataset） ──
  function openCreateDs() {
    // Vue :1085：source_id 预置第一个数据源。向导里只有「③ 从数据源生成」用得到它，其余模式由
    // create() 按模式写死来源，不会把这个预置值带进库。
    setForm({ name: '', source_id: (datasources[0] || {}).id || '', mode: 'import', project_id: 0 })
    setImportKind('file')
    setDsStep(0)
    setCreateOpen(true)
  }

  function pickMode(key: string) {
    setForm((p) => ({ ...p, mode: key }))
    // 懒加载：LS 引擎没起就是空列表 + 「还没有标注项目」提示（Vue :1092-1098）
    if (key === 'annotation') {
      if (!lsProjects.length) void loadLsProjects()
      else applyProjectName()
    }
  }

  async function loadLsProjects() {
    setLsLoading(true)
    let list: MosLsProject[] = []
    try { list = await listLsProjects() } catch (e) { list = [] }
    finally { setLsLoading(false) }
    setLsProjects(list)
    // project_id 还是 0 时 applyProjectName 自然空转（Vue :1106 同效）
    applyProjectName(form.project_id, list)
  }

  /**
   * 名称跟着项目走：同一项目重复同步必须复用同一份训练集——后端按 name + source='label_studio'
   * 找回原集（label_studio.py:2607-2613），改了这个模板串就会每同步一次多一份集（Vue :1110-1113）。
   * pid/list 显式传入：React state 异步，懒加载回来那一帧 form/lsProjects 还没新值。
   */
  function applyProjectName(pid?: number, list?: MosLsProject[]) {
    const src = list || lsProjects
    const id = pid === undefined ? form.project_id : pid
    const p = src.find((x) => x.id === id)
    if (p) setForm((prev) => ({ ...prev, name: '标注 · ' + p.title + ' (#' + p.id + ')' }))
  }

  function nextDs() {
    if (dsStep !== 0 || !canNextDs) return
    // 公开语料有自己的选择器，不在这个向导里再走一遍确认（Vue :1120-1126）
    if (form.mode === 'import' && importKind === 'catalog') {
      setCreateOpen(false)
      setDsStep(0)
      void openCatalog() // 不带 datasetId＝导入即新建一份
      return
    }
    setDsStep(1)
  }

  function goDsStep(i: number) {
    if (i < dsStep) setDsStep(i) // Vue :1130-1132：只许点回已完成的步骤，不许往前跳
  }

  async function create() {
    if (creating) return
    const name = form.name.trim()
    if (!name) { message.error('请填名称'); return } // Vue :1179 bad('请填名称')
    setCreating(true)
    try {
      const mode = form.mode
      // Vue :1183-1187：来源与属性由「模式」写死，不让用户填——手工=manual/human、
      // 导入=imported/imported_reference、生成=所选数据源/unknown。
      const source = mode === 'generate' ? form.source_id : (mode === 'manual' ? 'manual' : 'imported')
      const r = await createDataset({
        name,
        source_id: source,
        data_origin: mode === 'manual' ? 'human' : (mode === 'import' ? 'imported_reference' : 'unknown'),
      })
      setCreateOpen(false)
      setDsStep(0)
      message.success(mode === 'generate' ? '已创建，接着生成第一批数据' : '已创建，接着导入第一批数据')
      await load()
      // openDetail 内部会把 firstAction 复位（Vue open():1337），所以 mode 必须写在它之后
      // ——这正是 Vue :1195-1196 的先后顺序（await this.open(id) 后才 this.firstAction = mode），
      // 反过来写引导条会被自己清掉（React 同批 setState 后到者生效；T2/T3/T5 实测）。
      await openDetail(r.id)
      setFirstAction(mode)
      if (mode === 'manual') openManual('one')
      else if (mode === 'import') openManual('file')
      else scrollGen()
    } catch (e: any) {
      // 后端校验显式报错（名称空/200 字上限/控制字符，datasets.py:196-202），不许静默吞
      message.error('创建失败: ' + (e?.message || ''))
    } finally {
      setCreating(false)
    }
  }

  async function createFromAnnotation() {
    if (!pickedProject || creating) return
    setCreating(true)
    try {
      const r = await lsToDataset(pickedProject.id, {
        dataset_id: '',
        name: form.name.trim(),
        freeze: false, // 先草稿：同步完让用户看一眼再发布（Vue :1142）
        // 状态必须一起传 draft：不传后端按 approved 入库并把项目绑成「批准入库」，
        // 定时对账一有新增就自动 freeze——用户没点发布却已发布（Vue :1143-1145 口径）。
        status: 'draft',
      })
      setCreateOpen(false)
      setDsStep(0)
      message.success('已同步 ' + r.inserted + ' 条；审核通过后发布')
      const dsList = await load()
      if ((dsList || []).some((d) => d.id === r.dataset_id)) await openDetail(r.dataset_id)
    } catch (e: any) {
      message.error('同步失败: ' + (e?.message || ''))
    } finally {
      setCreating(false)
    }
  }

  /**
   * 直达「造数据」卡片。gen-card 属生成/蒸馏批次（不在 9-A/B/C 任一子卡面），本页还没有这块：
   * 照 Vue :1164-1168 的防御式 `if (el)`——元素不在就静默，绝不假装滚到了。
   */
  function scrollGen() {
    setTimeout(() => {
      const el = document.querySelector('.gen-card')
      if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 0)
  }

  function runFirstAction() {
    if (firstAction === 'manual') openManual('one')
    else if (firstAction === 'import') openManual('file')
    else scrollGen()
  }

  // ── ⑨ 横幅「继续…」钮（Vue continueLabel :975-979 / continueSource :988-993 逐字） ──
  const continueLabel = (current?.source === 'manual' ? '继续录入' : current?.source === 'imported' ? '继续导入' : '继续生成') + ' →'
  function continueSource() {
    const s = current?.source || ''
    if (s === 'manual') openManual('one')
    else if (s === 'imported') openManual('file')
    else scrollGen()
  }

  // ── ⑩ 数据源就地管理（DataSources.vue:240-433 全套；通道=api/mos.ts datasource* 封装，页面零自带 send） ──
  /** Vue load:258-265：with_stats=1（规模列/连通列全靠 stats 与 last_test_*） */
  const dsmLoad = useCallback(async () => {
    setDsmError('')
    try {
      const rows = await listDatasources(1)
      setDsmList(rows || [])
    } catch (e: any) {
      // Vue 原口径：加载失败画「数据源没加载出来+重试」，绝不画成「还没有数据源」
      setDsmError(e?.message || '请求失败')
      message.error('加载失败: ' + (e?.message || ''))
    }
  }, [])

  function openDsMgr() {
    setDsmOpen(true)
    void dsmLoad()
    // Vue loadTypes:267-269 ignore：类型读失败不弹错，新建下拉空着由保存时后端 400 显式兜住
    if (!dsmTypes.length) void datasourceTypes().then((t) => setDsmTypes((t as Record<string, any>[]) || [])).catch(() => { /* ignore */ })
  }
  function closeDsMgr() {
    setDsmOpen(false)
    void load() // Vue toggleDS:881——关窗必回刷训练集列表（数据源改名/删除要落到来源列与 canGenerate）
  }
  function toggleDsMgr() {
    if (dsmOpen) closeDsMgr()
    else openDsMgr()
  }

  /** ⑨ 横幅「管理数据源」（Vue showDataSources :994-1002：先关详情再开面板；React=关抽屉+开弹窗） */
  function showDataSources() {
    closeDetail()
    openDsMgr()
  }

  /** Vue onType :272-283：切类型清 config/text/picked/svManual；streamvision 自动挑最好候选 */
  async function dsmOnType(nextType: string) {
    setDsmForm((p) => ({ ...p, config: '', text: '' }))
    setDsmPicked([])
    setSvManual(false)
    if (nextType !== 'streamvision') return
    let cands = svCands
    if (!cands.length) {
      try { cands = ((await streamvisionCandidates()) || []) as Record<string, any>[] } catch { cands = [] }
      setSvCands(cands)
    }
    const best = (cands || []).find((c) => c.ok) || (cands || [])[0]
    if (best) setDsmForm((p) => ({ ...p, config: String(best.path || '') }))
    else setSvManual(true) // 探测不到=直接落到手动路径分支（Vue :280）
  }

  function dsmOpenCreate() {
    setDsmEditing(null)
    const t0 = String((dsmTypes[0] || {}).type || 'streamvision') // Vue openCreate:286 默认 types[0]
    setDsmForm({ type: t0, name: '', config: '', notes: '', text: '' })
    setDsmPicked([])
    setSvManual(false)
    void dsmOnType(t0)
    setDsmFormOpen(true)
  }

  function dsmOpenEdit(d: MosDatasource) {
    setDsmEditing(d)
    setDsmForm({ type: String(d.type || ''), name: String(d.name || ''), config: String(d.config || ''), notes: String(d.notes || ''), text: '' })
    setDsmPicked([])
    setSvManual(false)
    setDsmFormOpen(true)
  }

  async function dsmSave() {
    if (!dsmForm.name) { message.error('请填名称'); return } // Vue save:302 原文
    setDsmSaving(true)
    try {
      if (dsmEditing) {
        await patchDatasource(dsmEditing.id, { name: dsmForm.name, config: dsmForm.config, notes: dsmForm.notes })
        // 手工源正文随编辑窗一起改（Vue :304-307：text 为空不发 PUT=「没写就不动」）
        if (dsmEditing.type === 'manual' && dsmForm.text) await putDatasourceText(dsmEditing.id, dsmForm.text)
        message.success('已保存')
      } else {
        const r = await createDatasource({ type: dsmForm.type, name: dsmForm.name, config: dsmForm.config, notes: dsmForm.notes, text: dsmForm.text })
        if (dsmForm.type === 'upload' && dsmPicked.length) {
          const fd = new FormData()
          dsmPicked.forEach((f) => fd.append('files', f))
          const up = await uploadDatasourceFiles(r.id, fd)
          const n = ((up.saved || []) as unknown[]).length
          const sk = ((up.skipped || []) as Record<string, any>[]).length
          if (sk) message.error('已入库 ' + n + ' 个；' + sk + ' 个跳过：' + (((up.skipped || [])[0] || {}).error || ''))
          else message.success('已创建并上传 ' + n + ' 个文档')
        } else {
          message.success('已创建')
        }
      }
      setDsmFormOpen(false)
      await dsmLoad()
    } catch (e: any) {
      message.error(e?.message || '')
    } finally {
      setDsmSaving(false)
    }
  }

  async function dsmTest(d: MosDatasource) {
    setDsmBusy((p) => ({ ...p, [d.id]: true }))
    try {
      const r = await testDatasource(d.id)
      const txt = String(d.name || '') + '：' + String(r.message || '')
      if (r.ok) message.success(txt)
      else message.error(txt)
      await dsmLoad() // 结果写回 last_test_* 列，必须重拉（Vue :333）
    } catch (e: any) {
      message.error(e?.message || '')
    } finally {
      setDsmBusy((p) => ({ ...p, [d.id]: false }))
    }
  }

  async function dsmPreview(d: MosDatasource) {
    dsmCurIdRef.current = d.id
    setDsmPvItems([])
    setDsmPvOpen(true)
    try {
      const r = await previewDatasource(d.id, 5) as Record<string, any>
      setDsmPvItems((r.items || []) as Record<string, any>[])
      if (r.error) message.error(String(r.error))
    } catch (e: any) {
      message.error(e?.message || '')
    }
  }

  async function dsmOpenFiles(d: MosDatasource) {
    dsmCurIdRef.current = d.id
    setDsmFiles([])
    setDsmFilesOpen(true)
    try {
      const r = await datasourceFiles(d.id)
      setDsmFiles((r || []) as Record<string, any>[])
    } catch (e: any) {
      message.error(e?.message || '')
    }
  }

  async function dsmUpload(fileArr: File[]) {
    setDsmSaving(true)
    try {
      const fd = new FormData()
      fileArr.forEach((f) => fd.append('files', f))
      const up = await uploadDatasourceFiles(dsmCurIdRef.current, fd)
      const sk = ((up.skipped || []) as Record<string, any>[])
      if (sk.length) message.error('跳过 ' + sk.length + ' 个：' + (sk[0]?.error || ''))
      else message.success('已添加 ' + ((up.saved || []) as unknown[]).length + ' 个文档')
      setDsmFiles(((up.files || []) as Record<string, any>[]))
      await dsmLoad()
    } catch (e: any) {
      message.error(e?.message || '')
    } finally {
      setDsmSaving(false)
    }
  }

  async function dsmDelFile(f: Record<string, any>) {
    if (!(await askConfirm({
      title: '删除文件',
      content: '删除文件「' + f.name + '」？文件会从磁盘删除，不可恢复。',
      okText: '删除',
      danger: true,
    }))) return
    try {
      const r = await deleteDatasourceFiles(dsmCurIdRef.current, String(f.name)) as Record<string, any>
      setDsmFiles((r.files || []) as Record<string, any>[])
      message.success('已删除')
      await dsmLoad()
    } catch (e: any) {
      message.error(e?.message || '')
    }
  }

  /** Vue openText:399-412：异步回包按 id 校验——晚到的包不许串到已换目标/已关的窗 */
  async function dsmOpenText(d: MosDatasource) {
    dsmCurIdRef.current = d.id
    setDsmTextVal('')
    setDsmTextError('')
    setDsmTextLoading(true)
    setDsmTextOpen(true)
    try {
      const r = await datasourceText(d.id) as Record<string, any>
      if (dsmCurIdRef.current === d.id) setDsmTextVal(String(r.text || ''))
    } catch (e: any) {
      if (dsmCurIdRef.current === d.id) setDsmTextError(e?.message || '')
    } finally {
      if (dsmCurIdRef.current === d.id) setDsmTextLoading(false)
    }
  }

  async function dsmSaveText() {
    if (dsmTextLoading || dsmTextError) return
    if (!dsmTextVal.trim() && !(await askConfirm({
      title: '文本为空',
      content: '保存会清空该数据源的正文。确定保存？',
      okText: '仍然保存',
      danger: true,
    }))) return
    setDsmSaving(true)
    try {
      await putDatasourceText(dsmCurIdRef.current, dsmTextVal)
      message.success('已保存')
      setDsmTextOpen(false)
      await dsmLoad()
    } catch (e: any) {
      message.error(e?.message || '')
    } finally {
      setDsmSaving(false)
    }
  }

  async function dsmRemove(d: MosDatasource) {
    if (!(await askConfirm({ title: '删除数据源', content: '删除数据源「' + d.name + '」？', okText: '删除', danger: true }))) return
    try {
      await deleteDatasource(d.id)
      message.success('已删除')
      await dsmLoad()
    } catch (e: any) {
      message.error(e?.message || '')
    }
  }

  // ── ⑧ 公开语料（Vue :1606-1722；通道=api/mos.ts catalog* 六封装，后端 api/catalog.py） ──
  async function openCatalog(datasetId = '') {
    setCatOpen(true)
    setCatProbe(null)
    // 每次打开都从干净状态开始：既不能把上一轮的本地副本带进来，也不能在用户想「新建一份」时
    // 还追加到上次导入的训练集里（Vue :1609-1612）。
    setCatDatasetId(datasetId)
    clearLocal()
    let list = catalogList
    let entryId = cat.entry_id
    if (!list.length) {
      try {
        list = (await catalogDatasets()) || []
        setCatalogList(list)
        // 已选条目还在就保留（深链/重开场景），没有匹配才退回第一条（Vue :1615-1618）
        if (list.length && !list.some((x) => x.id === entryId)) {
          entryId = list[0].id
          setCat((p) => ({ ...p, entry_id: entryId }))
        }
      } catch (e: any) { message.error(e?.message || '公开语料目录读取失败') }
    }
    await loadDownloads()
    // 打开就自动看看这个语料能不能用（Vue :1622）；本帧闭包里的 catLocal/catDatasetId 还是
    // 上一轮的，显式传「开窗后的应有值」：本地=清、追加目标=datasetId 入参
    if (entryId) void probeCatalog(entryId, '', datasetId)
  }

  async function loadDownloads() {
    try {
      const r = await catalogDownloads()
      setDownloads(r.items || [])
      setDlDir(r.dir || '')
    } catch (e) { setDownloads([]) } // Vue :1630：读不到就当空，下载区不挡导入主流程
  }

  /** catBody（Vue :1633-1642）：列名非空才发；填了自定义仓库就忽略预设语料；本地副本在场带 offset；
   *  dataset 在场＝追加进那一份。entryOverride/localOverride/datasetOverride＝React 闭包读 state
   *  有帧延迟（Vue 的 this.* 同步赋值没有）：同 tick 刚清过/刚设过的调用方（换语料探测、开窗探测）
   *  把「当时应该是什么」显式传进来，探测体不许带上刚被清掉的 local_file/dataset_id——后端 probe
   *  只认 entry/repo，带错也不报错，这种静默漂移最难查，口径必须跟 Vue 逐字一致。 */
  function catBody(entryOverride?: string, localOverride?: string, datasetOverride?: string): MosCatalogBody {
    const b: MosCatalogBody = { limit: cat.limit, name: cat.name }
    if (cat.qcol) b.q = cat.qcol
    if (cat.acol) b.a = cat.acol
    if (cat.repo) { b.source = cat.source; b.repo = cat.repo }
    else b.entry_id = entryOverride === undefined ? cat.entry_id : entryOverride
    const local = localOverride === undefined ? catLocal : localOverride
    const dataset = datasetOverride === undefined ? catDatasetId : datasetOverride
    if (local) { b.local_file = local; b.offset = catOffset }
    if (dataset) b.dataset_id = dataset
    return b
  }

  async function doDownload() {
    setDlBusy(true)
    try {
      const b = catBody()
      delete b.offset; delete b.dataset_id; delete b.local_file // 下载与「导进哪份/导到第几行」无关
      const r = await catalogDownload(b) // 大文件可能等几十秒（Vue :1649）
      setCatLocal(r.rel)
      setCatOffset(0)
      setCatTotal(r.rows != null ? r.rows : null)
      message.success('已下载到本地：' + r.rel + '（' + r.size_mb + 'MB'
        + (r.rows != null ? ' · ' + r.rows + ' 行' : '') + '）')
      await loadDownloads()
    } catch (e: any) { message.error('下载失败: ' + (e?.message || '')) }
    finally { setDlBusy(false) }
  }

  function clearLocal() {
    setCatLocal('')
    setCatOffset(0)
    setCatTotal(null)
  }

  function useLocal(d: MosCatalogLocal) {
    setCatLocal(d.rel)
    setCatOffset(0)
    setCatTotal(d.rows != null ? d.rows : null)
    setCatDatasetId('') // 换副本 -> 重新开一份（Vue :1669）
    message.success('导入将走本地副本：' + d.name)
  }

  // 换语料 = 换一次导入：本地副本和追加目标都属于上一份语料，必须清掉（Vue :1673-1679）。
  // 探测必须走 freshEntry=true：clearLocal/setCatDatasetId 与这里是同一拍，闭包里读到的还是旧值。
  function onCatEntryChange(entryId: string) {
    clearLocal()
    setCatDatasetId('')
    setCatProbe(null)
    setCat((p) => ({ ...p, entry_id: entryId }))
    void probeCatalog(entryId, '', '') // 同 tick 清的本地/追加目标不许从旧闭包里漏进探测体
  }

  async function delLocal(d: MosCatalogLocal) {
    if (!(await askConfirm({
      title: '删除本地副本',
      content: '删本地副本「' + d.rel + '」？（可重新下载）',
      okText: '删除',
      danger: true,
    }))) return
    try {
      await deleteCatalogDownload(d.rel)
      if (catLocal === d.rel) { setCatLocal(''); setCatOffset(0); setCatTotal(null) }
      message.success('已删除')
      await loadDownloads()
    } catch (e: any) { message.error(e?.message || '删除失败') }
  }

  async function probeCatalog(entryId?: string, localOverride?: string, datasetOverride?: string) {
    setCatProbe({ ok: false, error: '探测中…' }) // Vue :1697：先占位，慢网络下窗面不能空着
    try {
      const r = await catalogProbe(catBody(entryId, localOverride, datasetOverride))
      setCatProbe(r.probe)
    } catch (e: any) { setCatProbe({ ok: false, error: e?.message || '探测失败' }) }
  }

  async function doCatalogImport() {
    setCatBusy(true)
    try {
      const r = await catalogImport(catBody())
      setCatDatasetId(r.dataset_id) // 后续批次追加进同一份（Vue :1706）
      let msg = '已导入 ' + r.inserted + ' 条 → 「' + r.name + '」'
      if (r.next_offset != null && r.total != null) {
        setCatOffset(r.next_offset)
        setCatTotal(r.total)
        msg += '（本地共 ' + r.total + ' 行，已导到 ' + r.next_offset + '）'
      }
      message.success(msg)
      // 撞名不静默新建：另给一条红提醒，说清「本次新建了一份」（Vue :1714）
      if (r.dup) message.error('注意：已存在同名问答集「' + r.dup.name + '」（' + r.dup.id + '），本次新建了一份')
      if (!r.next_offset) setCatOpen(false) // 本地分批模式不关窗，方便接着导（Vue :1715）
      await load()
      // 导进「当前打开的训练集」时列表和统计要一起刷新，否则新行看不见、用户会再点一次（:1717-1719）
      if (current && r.dataset_id === current.id) await refreshDetail()
    } catch (e: any) { message.error('导入失败: ' + (e?.message || '')) }
    finally { setCatBusy(false) }
  }

  /** source-actions 卡「导入公开语料」＝导进当前集（Vue :983-987：名字预填当前集名 + 带 dataset_id） */
  function importCatalogIntoCurrent() {
    if (!current) return
    setCat((p) => ({ ...p, name: current.name }))
    void openCatalog(current.id)
  }

  async function saveOrigin(v: string) {
    if (!currentIdRef.current) return
    try {
      await setDatasetOrigin(currentIdRef.current, v)
      setOriginDraft(v)
      setCurrent((p) => (p ? { ...p, data_origin: v } : p))
      message.success(`属性已改为「${originLabel(v)}」`)
      await load()
    } catch (e: any) {
      // human 会被 mos 侧核验拒绝（400 带原因，datasets.py:287-289），回滚草稿值
      setOriginDraft(current?.data_origin || 'unknown')
      message.error(e?.message || '属性更新失败')
    }
  }

  // ── ② 审核：单条 quick（Vue quick:1437，无确认无成功 toast）+ 批量 bulkSet（:323-332,1421-1435） ──
  const allChecked = page.items.length > 0 && page.items.every((it) => checked.includes(it.id))

  async function quick(it: MosDatasetItem, st: string) {
    try {
      await patchDatasetItem(it.id, { status: st })
      await refreshDetail()
    } catch (e: any) { message.error(e?.message || '更新失败') }
  }

  function toggleCheck(id: string) {
    setChecked((p) => (p.includes(id) ? p.filter((x) => x !== id) : [...p, id]))
  }
  function toggleAll(on: boolean) {
    setChecked(on ? page.items.map((it) => it.id) : []) // Vue toggleAll:1414=全选「本页」
  }

  async function bulkSet(status: 'approved' | 'rejected') {
    if (!current || !checked.length || bulkBusy) return
    // Vue bulkSet:1422-1427：通过=直接发；拒绝=先确认（误拒成本高）
    if (status === 'rejected' && !(await askConfirm({
      title: '批量拒绝',
      content: '把选中的 ' + checked.length + ' 条标记为已拒绝？',
      okText: '标记为已拒绝',
    }))) return
    setBulkBusy(true)
    try {
      const r = await bulkDatasetItems(current.id, { ids: checked.slice(), status })
      message.success('已更新 ' + (r.updated || 0) + ' 条')
      setChecked([])
      await refreshDetail()
    } catch (e: any) { message.error('批量更新失败: ' + (e?.message || '')) }
    finally { setBulkBusy(false) }
  }

  // ── ③ 条目编辑（Vue startEdit:1506 / saveEdit:1508-1515；q/a 落库即 human_edited=1，datasets.py:609） ──
  function startEdit(it: MosDatasetItem) {
    setEditing(it)
    setEditDraft({ q: it.q, a: it.a })
    setEditOpen(true)
  }

  async function saveEdit() {
    if (!editing) return
    setSavingEdit(true)
    try {
      await patchDatasetItem(editing.id, { q: editDraft.q, a: editDraft.a })
      setEditOpen(false)
      message.success('已保存')
      await refreshDetail()
    } catch (e: any) { message.error(e?.message || '保存失败') }
    finally { setSavingEdit(false) }
  }

  // ── ④ 发布 freeze（Vue doFreeze:1566-1579；0 已批=前端先挡，后端 datasets.py:1220 也 400 硬门槛） ──
  async function doFreeze() {
    if (!current || freezing) return
    if (!stat.approved) { message.error('先把至少 1 条审核为「已批」再发布'); return }
    if (stat.draft && !(await askConfirm({
      title: '还有草稿未审核',
      content: '还有 ' + stat.draft + ' 条草稿未审核，不会进入本次发布。继续？',
      okText: '继续发布',
    }))) return
    setFreezing(true)
    try {
      const r = await freezeDataset(current.id)
      message.success('已发布 ' + r.version + '（' + r.lines + ' 行 · 依据 ' + r.basis + '）')
      // Vue doFreeze:1576-1577：load 后把新鲜列表行换回 current（frozen/version 立刻反映到卡面）
      const dsList = await load()
      const fresh = (dsList || []).find((d) => d.id === current.id)
      if (fresh) setCurrent((p) => (p && p.id === fresh.id ? { ...p, ...fresh } : p))
    } catch (e: any) { message.error('发布失败: ' + (e?.message || '')) }
    finally { setFreezing(false) }
  }

  // ── ⑤ 质检（Vue toggleQC:901 / validate:1485-1494 / validateOne:1496-1504 / loadQuality:1376-1379） ──
  async function loadQuality() {
    const id = currentIdRef.current
    if (!id) return
    try { setQuality(await datasetQuality(id)) } catch (e) { /* Vue catch-ignore：总览读失败不打断列表 */ }
  }

  function toggleQC() {
    const next = !showQC
    setShowQC(next)
    if (next) void loadQuality() // Vue :901 展开即重拉一次
  }

  async function runValidate(mode: 'rules' | 'llm') {
    if (!current || vbusy) return
    setVbusy(true)
    try {
      const r = await validateDataset(current.id, { mode, apply: vApply })
      message.success('校验 ' + r.checked + ' 条：通过 ' + r.pass + ' / 警告 ' + r.warn + ' / 严重 ' + r.fail
        + (r.llm_calls ? '（LLM 调用 ' + r.llm_calls + '）' : '') + (r.applied ? ' · 已剔除 ' + r.applied : ''))
      await loadItems(current.id, { status: flt, q: itemQ, limit: itemLimit, offset: (itemPage - 1) * itemLimit })
      await loadQuality()
      await load()
    } catch (e: any) { message.error('校验失败: ' + (e?.message || '')) }
    finally { setVbusy(false) }
  }

  async function validateOne(it: MosDatasetItem) {
    setVrow((p) => ({ ...p, [it.id]: true }))
    try {
      const r = await validateDatasetItem(it.id, { mode: 'rules' }) // Vue :1497 mode 固定 rules（免费）
      // Vue :1500 直接改行对象；React 行在 page.items 里，必须并回行集才看得见 verdict/issues
      setPage((p) => ({ ...p, items: p.items.map((x) => (x.id === it.id
        ? { ...x, verdict: r.verdict, issues: JSON.stringify(r.issues || []) } : x)) }))
      await loadQuality()
    } catch (e: any) { message.error(e?.message || '校验失败') }
    finally { setVrow((p) => ({ ...p, [it.id]: false })) }
  }

  // ── ⑥ 删除前引用清单（Vue :1208-1334；2026-10-23 项目负责人反馈：清单没读到不许盲删） ──
  /** 读引用清单；返回 {loaded, items} 供 removeDs 同步决策（React state 异步，不能拿 refsLoaded 立即读） */
  async function loadRefs(target?: MosDataset | null): Promise<{ loaded: boolean; items: MosDatasetRef[] }> {
    const d = target || refsTarget
    if (!d) return { loaded: false, items: [] }
    setRefsLoading(true)
    setRefsError('')
    try {
      const r = await datasetRefs(d.id)
      const list = r.items || []
      setRefsList(list)
      setRefsLoaded(true)
      const now = new Date()
      setRefsAt([now.getHours(), now.getMinutes(), now.getSeconds()]
        .map((n) => String(n).padStart(2, '0')).join(':')) // Vue refsAt:1240-1242 HH:MM:SS
      return { loaded: true, items: list }
    } catch (e: any) {
      const msg = e?.message || '读取失败'
      // Vue :1244 注释口径：读不到就保持「未知」，不能伪装成「引用已清空」
      setRefsList([])
      setRefsLoaded(false)
      setRefsError(msg)
      message.error('读取引用失败：' + msg)
      return { loaded: false, items: [] }
    } finally { setRefsLoading(false) }
  }

  async function removeDs(d: MosDataset) {
    setRefsTarget(d)
    setRefsList([])
    setRefsLoaded(false)
    setRefsError('')
    const { loaded, items } = await loadRefs(d)
    if (!loaded) { setRefsOpen(true); return } // 读失败：弹窗里重试，不盲删（Vue :1261）
    if (items.length) { setRefsOpen(true); return } // 有引用：同弹窗逐条解除（Vue :1262）
    if (!(await askConfirm({
      title: '删除训练集',
      content: '删除训练集「' + d.name + '」及其中的 ' + (Number(d.n_items) || 0) + ' 个条目？此操作不可恢复。',
      okText: '删除训练集',
      danger: true,
    }))) return
    await doDelete(d)
  }

  async function doDelete(d: MosDataset) {
    setRefsDeleting(true)
    try {
      await deleteDataset(d.id)
      // Vue doDelete:1274-1276：列表立即摘行——等 load 回来之前行还在就看起来像「假删除」
      setDatasets((p) => p.filter((x) => x.id !== d.id))
      if (currentIdRef.current === d.id) closeDetail()
      setRefsOpen(false)
      setRefsTarget(null)
      message.success('已删除训练集')
      await load()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
      // Vue :1290-1294：检查之后又冒出新引用 → 回弹窗刷新清单继续处理
      if (String(e?.message || '').includes('仍被引用')) {
        setRefsTarget(d)
        await loadRefs(d)
        setRefsOpen(true)
      }
    } finally { setRefsDeleting(false) }
  }

  function deleteNow() {
    if (!refsTarget || refsDeleting) return
    if (!refsLoaded) { void loadRefs(); return } // 先读到真实清单，再决定能不能删（Vue :1299）
    if (refsList.length) return
    void doDelete(refsTarget)
  }

  async function deleteRef(r: MosDatasetRef) {
    const force = !!r.force
    if (!(await askConfirm({
      title: '删除引用',
      content: '删除' + refKind(r.kind) + '「' + r.name + '」？' + (force && r.warn ? r.warn + '。' : ''),
      okText: force ? '删除（含门禁）' : '删除',
      danger: true,
    }))) return
    setRefBusy(r.kind + ':' + r.id)
    try {
      if (r.kind === 'job') await deleteJob(r.id, force) // force=运行中作业的停机删除门禁（datasets.py:352-426）
      else if (r.kind === 'eval') await deleteEval(r.id)
      else if (r.kind === 'deployment') await deleteDeployment(r.id)
      else await deleteModel(r.id)
      message.success('已删除' + refKind(r.kind))
      await loadRefs()
      await load()
    } catch (e: any) { message.error(e?.message || '删除失败') }
    finally { setRefBusy('') }
  }

  function openRef(r: MosDatasetRef) {
    setRefsOpen(false)
    // Vue openRef:1328-1334。deployment 在老页跳 /deploy；React 站内无模型服务页
    // （App.tsx mos 路由只有 models/jobs/datasets/labels/evaluations/label-studio），
    // 行内「查看」对该类不渲染（通道限制，卡面已记账），其余落点参数名照旧。
    if (r.kind === 'job') navigate('/mos/jobs?job=' + encodeURIComponent(r.id))
    else if (r.kind === 'model') navigate('/mos/models?model=' + encodeURIComponent(r.id))
    else navigate('/mos/evaluations')
  }

  // ── 上传入口：单条 / 批量粘贴 / 文件（Datasets.vue:484-516、openManual:1725-1730、saveManual:1758） ──
  /** mode 带默认值＝老页 openManual(mode='one')；建后直达按模式传 'one'/'file'（Vue :1200-1202）。
   *  调用点必须写 `() => openManual()`：直接交引用给 onClick 会把 click 事件当成 mode。 */
  function openManual(mode = 'one') {
    setMan({ q: '', a: '', bulk: '' })
    setManFile(null)
    setManMode(mode)
    setManOpen(true)
  }

  /**
   * ⑫ 导入诚实性三分支（Vue afterImport:1746-1756 逐字）：
   * 有新增=绿（带重复再补一条红）；零新增有重复=红；零新增零重复=红。
   * 「新增 0 也报成功」正是本号要修的回归——导入没进库就必须让用户看见红。
   */
  function afterImport(r: MosDatasetAddResult) {
    const dup = r.duplicated || 0
    if (r.inserted) {
      message.success('导入 ' + r.inserted + ' 条' + (dup ? '，跳过重复 ' + dup + ' 条' : ''))
      if (dup) message.error('有 ' + dup + ' 条与库里已有问题重复，已跳过（未重复入库）')
    } else if (dup) {
      message.error('没有新增：这 ' + dup + ' 条内容库里都有了（已全部跳过，未重复入库）')
    } else {
      message.error('没有解析到有效问答（问题、答案都不能为空）')
    }
  }

  async function saveManual() {
    if (!currentIdRef.current) return
    if (manMode === 'one' && !(man.q.trim() && man.a.trim())) { message.warning('问题和答案都不能为空'); return }
    if (manMode === 'bulk' && !man.bulk.trim()) { message.warning('批量内容为空'); return }
    if (manMode === 'file' && !manFile) { message.warning('请先选择文件'); return }
    setImporting(true)
    try {
      let r: MosDatasetAddResult
      if (manMode === 'file') {
        const fd = new FormData()
        fd.append('file', manFile as File)
        r = await uploadDatasetItems(currentIdRef.current, fd)
      } else {
        r = await addDatasetItems(currentIdRef.current,
          manMode === 'one' ? { q: man.q, a: man.a } : { bulk: man.bulk })
      }
      setManOpen(false)
      afterImport(r)
      // Vue saveManual:1771-1773：toast 之后统一 refreshDetail（条目页+列表行+current 一起新）
      await refreshDetail()
    } catch (e: any) {
      // 「没有解析到问答」等 400 由后端显式给出（datasets.py:1052），原样回显
      message.error('导入失败: ' + (e?.message || ''))
    } finally {
      setImporting(false)
    }
  }

  // ── 列表列定义 ──
  const dsColumns = useMemo<TableColumnsType<MosDataset>>(() => [
    {
      title: '训练集',
      key: 'name',
      render: (_, d) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text strong ellipsis={{ tooltip: d.name }} style={{ maxWidth: 260 }}>{d.name}</Typography.Text>
          <div style={{ ...MONO, fontSize: 11, color: '#999' }}>{d.id}</div>
          <Space size={4} wrap style={{ marginTop: 2 }}>
            {d.data_origin && d.data_origin !== 'unknown' && (
              <Tag color={originColor(d.data_origin)}>{originLabel(d.data_origin)}</Tag>
            )}
            {String(d.name || '').startsWith('relabel-') && (d.n_items ?? 0) === 0 && (
              // REQ-UX-FLOW-1#6：relabel 台账里的 0 条目空行（如 ds_5@0）——列表如实标 stale，不再冒充可用数据；
              // 飞轮开圈下拉已滤掉这类行；数据面清理出清单由运维侧人工处理，不自动删。
              <Tag color="orange" title="重标注台账中的 0 条目空行；数据面清理由运维侧人工处理">stale·0 条目</Tag>
            )}
            {mediaBadges(d)}
          </Space>
        </div>
      ),
    },
    { title: '状态', key: 'status', width: 90, render: (_, d) => dsStatusTag(d.status) },
    {
      title: '条目（草稿/已批/已拒）',
      key: 'items',
      width: 170,
      render: (_, d) => (
        <div>
          <b>{d.n_items ?? 0}</b>
          <div style={{ fontSize: 11, color: '#888' }}>
            {d.counts?.draft ?? 0} / <span style={{ color: '#389e0d' }}>{d.counts?.approved ?? 0}</span>
            {' / '}<span style={{ color: '#cf1322' }}>{d.counts?.rejected ?? 0}</span>
          </div>
        </div>
      ),
    },
    {
      title: '版本', key: 'version', width: 150,
      render: (_, d) => <span style={MONO}>{d.version || '未发布'}</span>,
    },
    {
      title: '来源', key: 'source', width: 150,
      render: (_, d) => (
        <Typography.Text type="secondary" ellipsis={{ tooltip: d.source || '' }} style={{ maxWidth: 140 }}>
          {sourceName(d.source)}
        </Typography.Text>
      ),
    },
    {
      title: '操作', key: 'ops', width: 130,
      render: (_, d) => (
        <Space size={6} onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => void openDetail(d.id)}>打开</Button>
          <Button size="small" danger onClick={() => void removeDs(d)}>删除</Button>
        </Space>
      ),
    },
  ], [openDetail, sourceName])

  // ── 条目列定义（详情抽屉内；服务端分页/搜索/状态过滤）。
  // ②选择列 ③已人工改 ⑤质检列 ⑤行内校验——依赖逐渲染状态，不做 useMemo（列轻，重排成本低） ──
  const itemColumns: TableColumnsType<MosDatasetItem> = [
    {
      title: (
        <Checkbox
          checked={allChecked}
          disabled={!page.items.length}
          onChange={(e) => toggleAll(e.target.checked)}
          title="全选本页"
          aria-label="全选本页条目"
        />
      ),
      key: 'sel',
      width: 34,
      render: (_, it) => (
        <Checkbox
          checked={checked.includes(it.id)}
          onChange={() => toggleCheck(it.id)}
          aria-label={'选择条目 ' + it.id}
        />
      ),
    },
    {
      title: '问题', dataIndex: 'q', key: 'q',
      render: (v: string) => (
        <Typography.Text ellipsis={{ tooltip: v }} style={{ maxWidth: 320, whiteSpace: 'pre-wrap' }}>{v}</Typography.Text>
      ),
    },
    {
      title: '答案', dataIndex: 'a', key: 'a',
      render: (v: string) => (
        <Typography.Text ellipsis={{ tooltip: v }} style={{ maxWidth: 320, whiteSpace: 'pre-wrap' }}>{v}</Typography.Text>
      ),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (v: string, it) => (
        <div>
          <Tag color={ITEM_STATUS_COLOR[v] || 'default'}>{ITEM_STATUS_CN[v] || v}</Tag>
          {it.human_edited ? <div style={{ fontSize: 10.5, color: '#888' }}>已人工改</div> : null}
        </div>
      ),
    },
    {
      // ⑤ 质检列（Vue :341,363-366）：verdict tag + 「N 项」副行 title=逐条「· 标签：msg」
      title: '质检', key: 'qc', width: 80,
      render: (_, it) => {
        const n = parseIssues(it.issues).length
        return (
          <div>
            <Tag color={vColor(it.verdict)}>{vCn(it.verdict)}</Tag>
            {n > 0 ? <div style={{ fontSize: 10.5, color: '#888' }} title={issueText(it)}>{n} 项</div> : null}
          </div>
        )
      },
    },
    {
      title: '媒体', key: 'media', width: 96,
      render: (_, it) => (it.media_kind ? (
        <Button
          size="small"
          danger={it.media_exists === false}
          onClick={() => { setMediaItem(it); setMediaOpen(true) }}
        >
          {it.media_exists === false ? '缺失' : `预览 · ${kindLabel(it.media_kind)}`}
        </Button>
      ) : <span style={{ color: '#bbb' }}>—</span>),
    },
    {
      title: '来源', key: 'ref', width: 140,
      render: (_, it) => (
        <Typography.Text style={{ ...MONO, fontSize: 11, color: '#999' }} ellipsis={{ tooltip: it.source_ref || '' }}>
          {it.source_ref || '—'}
        </Typography.Text>
      ),
    },
    { title: '时间', dataIndex: 'created_at', key: 'created_at', width: 150, render: (v?: string) => <span style={{ ...MONO, fontSize: 11 }}>{v || ''}</span> },
    {
      // Vue :369-373 钮序=通过/拒绝/编辑/校验；quick 无确认、校验在途逐行禁用（vrow）
      title: '操作', key: 'ops', width: 210,
      render: (_, it) => (
        <Space size={4} wrap onClick={(e) => e.stopPropagation()}>
          <Button size="small" onClick={() => void quick(it, 'approved')}>通过</Button>
          <Button size="small" danger onClick={() => void quick(it, 'rejected')}>拒绝</Button>
          <Button size="small" onClick={() => startEdit(it)}>编辑</Button>
          <Button size="small" disabled={!!vrow[it.id]} onClick={() => void validateOne(it)}>
            {vrow[it.id] ? '…' : '校验'}
          </Button>
        </Space>
      ),
    },
  ]

  const itemCounts = page.counts || {}
  const drawerOpen = !!current
  // ⑨ 横幅三分支互斥（Vue :131-162）：反查命中=回链；标了未绑=提示同步；其余=来源行+就地钮
  const provProj = current ? annotationProjectOf(current.id) : null
  const provLast = provProj ? (syncLast[String(provProj.project_id)] || null) : null

  return (
    <div>
      {/* ── 列表 ── */}
      <Space style={{ marginBottom: 8, width: '100%', justifyContent: 'space-between' }} wrap>
        <Space wrap>
          <Tag>共 {datasets.length} 个训练集</Tag>
          <Input
            style={{ width: 220 }}
            placeholder="搜索训练集名称 / ID / 版本"
            value={dsQuery}
            onChange={(e) => setDsQuery(e.target.value)}
            allowClear
          />
          <Checkbox checked={frozenOnly} onChange={(e) => setFrozenOnly(e.target.checked)}>
            <span style={{ fontSize: 12 }}>只看已发布</span>
          </Checkbox>
          <span style={{ fontSize: 12, color: '#888' }}>显示 {visible.length} / {datasets.length}</span>
          {/* ⑩ 数据源就地管理入口（Vue :29-32 逐字：title 带数量、钮面带 ▲▼ 开合态） */}
          <Button size="small" title={'造数据用的数据源，共 ' + datasources.length + ' 个'} onClick={toggleDsMgr}>
            数据源 {datasources.length} {dsmOpen ? '▲' : '▼'}
          </Button>
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" onClick={openCreateDs}>＋ 新建训练集</Button>
        </Space>
      </Space>

      <div style={{ fontSize: 12, color: '#888', marginBottom: 12 }}>
        <b>训练集</b>（旧称「数据集」，同一事物＝ mos 条目集 ds_）= 一组<b>条目</b>（可发布给训练 / 评测）；
        <b>数据源</b> = 造数据的原料（StreamVision 文本知识 / 上传文档 / 手工录入；StreamVision 只出文本知识，图片/视频请走上传或标注）。
        发布只包含「已批」条目。标注页称它「标注集」、飞轮页称它「治理对象」，都是这一个真身。
      </div>

      {loadError ? (
        <ErrorState
          title={loadError.status === 403 ? '没有权限' : '训练集没加载出来'}
          message={loadError.status === 403
            ? '当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。'
            : loadError.message || '未知错误'}
          onRetry={() => void load()}
        />
      ) : loading && datasets.length === 0 ? (
        <LoadingState rows={6} />
      ) : datasets.length === 0 ? (
        <EmptyState
          title="还没有训练集"
          hint="点「＋ 新建训练集」先选数据从哪来：上传 JSONL / TSV、公开语料一键导入、标注项目就地同步、或手工录入"
          action={<Button type="primary" onClick={openCreateDs}>＋ 新建训练集</Button>}
        />
      ) : (
        <Table<MosDataset>
          rowKey="id"
          size="small"
          className="dw-sticky-pager"
          columns={dsColumns}
          dataSource={visible}
          // BUG-PARITY-15 ⑤：行键盘可达（§三:77 ≡ Datasets.vue:48-50 tabindex=0+Enter）；
          // Enter 打开详情走同一 openDetail——后退语义即 ③ 的 pushState（由 useDetailHistory 兑现）
          onRow={(d) => ({
            ...kbActivate(() => void openDetail(d.id)),
            onClick: () => void openDetail(d.id),
            style: { cursor: 'pointer' },
          })}
          pagination={listPagination({
            current: dsPage, total: visible.length, size: globalSize,
            onChange: (p, s) => { setGlobalSize(s); setDsPage(p) },
          })}
        />
      )}

      {/* ── 详情抽屉 ── */}
      <Drawer
        open={drawerOpen}
        onClose={closeDetail}
        width="min(1080px, 94vw)"
        destroyOnClose={false}
        title={(
          <Space wrap size={6} style={{ minWidth: 0 }}>
            <b style={{ fontSize: 15 }}>{current?.name}</b>
            <span style={{ ...MONO, fontSize: 12, color: '#888' }}>{current?.id}</span>
            {dsStatusTag(current?.status)}
            <span style={MONO}>{current?.version || '未发布'}</span>
          </Space>
        )}
        extra={(
          <Space wrap>
            {/* BUG-PARITY-15 ③：后退钮与 pushState 成对（≡ Datasets.vue:109「← 返回」）；
                本次打开若入过历史，closeDetail→popDetail→navigate(-1)，后退再按不会二次离页 */}
            <Button size="small" onClick={closeDetail}>← 返回</Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void refreshDetail()}>重新读取</Button>
            <Button type="primary" onClick={() => openManual()}>＋ 录入 / 导入问答</Button>
            {current && (
              <Button danger onClick={() => void removeDs(current)}>删除训练集</Button>
            )}
          </Space>
        )}
      >
        {current && (
          <>
            {/* ① 头部信息 + 数据属性（human 由 mos 侧核验，datasets.py:282） */}
            <Space wrap size={8} style={{ marginBottom: 8 }}>
              <span style={{ fontSize: 12, color: '#888' }}>数据属性</span>
              <Select
                size="small"
                style={{ width: 140 }}
                value={originDraft}
                onChange={(v) => void saveOrigin(v)}
                options={ORIGIN_OPTIONS}
                title="用于拦截占位标签训练/评测；人工数据需通过 mos 侧人标核验"
              />
            </Space>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '0 16px' }}>
              <Kv k="来源" title={current.source || ''}>{sourceName(current.source)}</Kv>
              <Kv k="条目"><span style={MONO}>{current.n_items ?? 0}</span></Kv>
              <Kv k="通过率"><span style={MONO}>{current.pass_rate != null ? `${Math.round(current.pass_rate * 100)}%` : '—'}</span></Kv>
              <Kv k="创建"><span style={MONO}>{current.created_at || '—'}</span></Kv>
            </div>

            {/* ⑨ 出处横幅三分支（Vue ds-prov :131-162 互斥链逐字；「数据集」按 REQ-UX-FLOW-1#1
                本页口径称「训练集」）。分支一=sync-map 命中：#pid 深链工作台 + 上次同步摘要
                （inserted 为真才带括注段，Vue :138 条件段语义）+ 回标注项目钮；
                分支二=标了 label_studio 但没绑定：告诉用户去标注页点一次同步；
                分支三=普通来源行：属性徽章 + 来源 + sourceHint + 「继续…」/「管理数据源」两钮。 */}
            {provProj ? (
              <div className="ds-prov" style={{
                display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap',
                border: '1px solid #91caff', background: '#e6f4ff', borderRadius: 8, padding: '8px 14px', margin: '10px 0',
              }}
              >
                <Tag color="blue" style={{ marginRight: 0 }}>来自标注</Tag>
                <span>标注项目
                  <a style={{ marginLeft: 4 }} onClick={() => navigate('/mos/label-studio/' + provProj.project_id)}>#{provProj.project_id}</a></span>
                {provLast && (
                  <span style={{ fontSize: 12, color: '#888' }}>
                    · 上次同步 {provLast.at}
                    {provLast.inserted ? `（新增 ${provLast.inserted} · 更新 ${provLast.updated || 0}）` : ''}
                  </span>
                )}
                <span style={{ fontSize: 12, color: '#888' }}>· 标注页改动会自动对账到这里</span>
                <div style={{ flex: 1 }} />
                <Button size="small" onClick={() => navigate('/mos/label-studio/' + provProj.project_id)}>回到标注项目 →</Button>
              </div>
            ) : current.source === 'label_studio' ? (
              <div className="ds-prov plain" style={{
                display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap',
                border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 14px', margin: '10px 0',
              }}
              >
                <Tag style={{ marginRight: 0 }}>标注同步</Tag>
                <span style={{ fontSize: 12, color: '#888' }}>这份训练集来自标注，但还没绑定项目；到标注页点一次「同步」即可建立回链。</span>
                <div style={{ flex: 1 }} />
                <Button size="small" onClick={() => navigate('/mos/labels')}>去标注页 →</Button>
              </div>
            ) : (
              <div className="ds-prov plain" style={{
                display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap',
                border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 14px', margin: '10px 0',
              }}
              >
                {current.data_origin && current.data_origin !== 'unknown' && (
                  <Tag color={originColor(current.data_origin)} style={{ marginRight: 0 }}>{originLabel(current.data_origin)}</Tag>
                )}
                <span>来源：<b>{sourceName(current.source)}</b></span>
                <span style={{ fontSize: 12, color: '#888' }}>· {sourceHint(current)}</span>
                <div style={{ flex: 1 }} />
                <Button size="small" onClick={continueSource}>{continueLabel}</Button>
                <Button size="small" onClick={showDataSources}>管理数据源</Button>
              </div>
            )}

            {/* ① 建后直达引导条（Vue first-action :164-172）：只在「刚建完且一条数据都还没有」时出现，
                stat.total 一有数就自己退场——不是常驻广告位。钮按 mode 分派 runFirstAction（:1171-1175）。 */}
            {firstAction && !stat.total && (
              <div style={{
                display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap',
                border: '1px solid #d9d9d9', borderRadius: 8, padding: '10px 14px', margin: '10px 0',
              }}
              >
                <span style={{ color: '#1677ff', fontWeight: 700 }}>→</span>
                <div style={{ minWidth: 0 }}>
                  <b>训练集已创建，接着放第一批数据</b>
                  <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>{FIRST_ACTION_TEXT[firstAction] || ''}</div>
                </div>
                <div style={{ flex: 1 }} />
                <Button type="primary" size="small" onClick={runFirstAction}>现在开始</Button>
              </div>
            )}

            {/* ⑪ 四统计卡（Vue .stats :174-179；口径=stat memo＝服务端全量 counts、不受本页筛选/
                翻页影响——Vue :1004 注释原话：以前按当前页 200 条统计，361 条的集会显示成 200）。 */}
            <div className="ds-stats" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 10, margin: '10px 0 14px' }}>
              {DS_STATS.map((s) => (
                <div key={s.lb} style={{ border: '1px solid rgba(0,0,0,0.10)', borderRadius: 8, padding: '8px 14px', background: '#fff' }}>
                  <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.55)' }}>{s.lb}</div>
                  <div style={{ fontSize: 20, fontWeight: 600, margin: '2px 0', color: s.color }}>{s.v(stat)}</div>
                  <div style={{ fontSize: 12, color: 'rgba(0,0,0,0.45)' }}>{s.sb}</div>
                </div>
              ))}
            </div>

            {/* ④⑦ 发布卡 + 导出两链（Vue ds-pub :181-206；导出不受发布状态遮挡——未发布点了由后端
                400 显式说明 datasets.py:1267，浏览器页直接看原文，前端不伪装禁用） */}
            <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 14px', margin: '10px 0 14px' }}>
              <Space wrap align="center" style={{ width: '100%', justifyContent: 'space-between' }}>
                <div style={{ minWidth: 0 }}>
                  <b>{current.status === 'frozen' ? '已发布 · ' + (current.version || '') : '还没发布'}</b>
                  <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>
                    {current.status === 'frozen'
                      ? '训练页可以直接选中这个版本；重新发布会生成新版本。'
                      : '发布会只包含「已批」条目；草稿和已拒不进入训练集。'}
                  </div>
                </div>
                <Space wrap>
                  {current.status !== 'frozen' && !stat.approved && stat.draft && (
                    <Button title="跳到下方列表，只看待审核的草稿" onClick={() => queryItems({ flt: 'draft', page: 1 })}>
                      去审核 {stat.draft} 条草稿
                    </Button>
                  )}
                  {current.status !== 'frozen' && (
                    <Button
                      loading={freezing}
                      disabled={!stat.approved}
                      title={stat.approved ? '发布会只包含已批准条目' : '先把至少 1 条审核为「已批」'}
                      onClick={() => void doFreeze()}
                    >发布</Button>
                  )}
                  <Button href={datasetExportUrl(current.id)} target="_blank">下载 JSONL</Button>
                  <Button href={datasetExportRawUrl(current.id)} target="_blank"
                          title="含 media_kind/media_path 和原始标注 result JSON">原始标注</Button>
                </Space>
              </Space>
              {isAnnotationData && (
                <div style={{ fontSize: 12, color: '#888', marginTop: 6 }}>
                  标注同步数据：需要补充生成时，建议新建一个生成训练集，别把人工标注和合成数据混在一起。
                </div>
              )}
            </div>

            {/* ⑧ source-actions 卡（Vue :208-222）：来源不是数据源（!canGenerate）且不是标注同步集时，
                就地给「继续导入 / 导入公开语料」两个入口；标注同步集不出（该回标注页同步）。 */}
            {!isAnnotationData && !canGenerate && (
              <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 14px', margin: '0 0 14px' }}>
                <b>{current.source === 'manual' ? '手工录入数据' : '导入数据'}</b>
                <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>
                  {current.source === 'manual'
                    ? '这份训练集是手工文本；继续粘贴，空行分段后入库。'
                    : '这份训练集来自文件 / 公开语料；可以继续导入，重复问题会自动跳过。'}
                </div>
                <Space wrap size={8} style={{ marginTop: 10 }}>
                  <Button
                    type="primary"
                    size="small"
                    onClick={() => openManual(current.source === 'manual' ? 'one' : 'file')}
                  >
                    {current.source === 'manual' ? '继续录入' : '导入文件'}
                  </Button>
                  <Button size="small" onClick={importCatalogIntoCurrent}>导入公开语料</Button>
                </Space>
              </div>
            )}

            {/* ② 条目区：服务端分页 + 搜索 + 状态过滤（datasets.py:493）；⑤「质检▼」开关（Vue :271） */}
            <Divider orientation="left" plain style={{ fontSize: 13 }}>
              条目（质检 · 审核 · 发布）
              <span style={{ fontWeight: 400, fontSize: 12, color: '#888', marginLeft: 10 }}>
                共 {page.all_total} 条 · 草稿 {itemCounts.draft ?? 0} / 已批 {itemCounts.approved ?? 0} / 已拒 {itemCounts.rejected ?? 0}
                {page.total !== page.all_total ? ` · 当前筛选 ${page.total}` : ''}
              </span>
            </Divider>
            <Space wrap style={{ marginBottom: 10 }}>
              <Button size="small" onClick={toggleQC}>质检{showQC ? ' ▲' : ' ▼'}</Button>
              <Select
                size="small"
                style={{ width: 120 }}
                value={flt}
                onChange={(v) => queryItems({ flt: v, page: 1 })}
                options={[
                  { value: '', label: '全部状态' },
                  { value: 'draft', label: '草稿' },
                  { value: 'approved', label: '已批' },
                  { value: 'rejected', label: '已拒' },
                ]}
              />
              <Input.Search
                size="small"
                style={{ width: 240 }}
                placeholder="搜索问题 / 答案（服务端转义 % _）"
                value={itemQ}
                onChange={(e) => setItemQ(e.target.value)}
                onSearch={() => queryItems({ page: 1 })}
                allowClear
              />
            </Space>
            {showQC && (
              /* ⑤ QC 面板（Vue :301-322 逐字：说明/两校验钮/剔除勾/刷新总览/五 chips/by_code chips） */
              <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 14px', marginBottom: 10 }}>
                <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>规则校验免费；LLM 评判每条一次模型调用。</div>
                <Space wrap size={10}>
                  <Button disabled={vbusy} onClick={() => void runValidate('rules')}>{vbusy ? '校验中…' : '规则校验（免费）'}</Button>
                  <Button disabled={vbusy} onClick={() => void runValidate('llm')}>LLM 评判（慢·计费）</Button>
                  <Checkbox checked={vApply} onChange={(e) => setVApply(e.target.checked)}>
                    <span style={{ fontSize: 12, color: '#888' }}>严重项自动剔除（fail→拒绝）</span>
                  </Checkbox>
                  <Button size="small" onClick={() => void loadQuality()}>刷新总览</Button>
                </Space>
                {quality && (
                  <Space wrap size={8} style={{ marginTop: 8 }}>
                    <Tag>总数 {quality.total}</Tag>
                    <Tag color="green">通过 {quality.verdicts.pass || 0}</Tag>
                    <Tag>警告 {quality.verdicts.warn || 0}</Tag>
                    <Tag color="red">严重 {quality.verdicts.fail || 0}</Tag>
                    <Tag>未检 {quality.verdicts.unchecked || 0}</Tag>
                    {Object.entries(quality.by_code || {}).map(([code, n]) => (
                      <Tag key={code}>{codeLabel(code)} {n}</Tag>
                    ))}
                  </Space>
                )}
              </div>
            )}
            {checked.length > 0 && (
              /* ② 批量条（Vue :323-332）：只出在当前页有选择时；「只作用于当前训练集」明说作用域 */
              <Space wrap size={8} style={{ marginBottom: 10 }}>
                <b>已选 {checked.length} 条</b>
                <span style={{ fontSize: 12, color: '#888' }}>只作用于当前训练集</span>
                <Button size="small" disabled={bulkBusy} onClick={() => void bulkSet('approved')}>
                  {bulkBusy ? '处理中…' : '批量通过'}
                </Button>
                <Button size="small" danger disabled={bulkBusy} onClick={() => void bulkSet('rejected')}>批量拒绝</Button>
                <Button size="small" onClick={() => setChecked([])}>清空选择</Button>
              </Space>
            )}
            {itemsError && (
              /* BUG-PARITY-15（§三:74，Vue :377-382,1400-1407）：行级重试+失败已先清行，失败≠空 */
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 8 }}
                message={'条目没加载出来：' + itemsError}
                action={(
                  <Button
                    size="small"
                    onClick={() => void loadItems(currentIdRef.current, { status: flt, q: itemQ, limit: itemLimit, offset: (itemPage - 1) * itemLimit })}
                  >重试</Button>
                )}
              />
            )}
            <Table<MosDatasetItem>
              rowKey="id"
              size="small"
              loading={itemsLoading}
              className="dw-sticky-pager"
              columns={itemColumns}
              dataSource={page.items}
              locale={{ emptyText: itemsError ? '条目加载失败——点上方「重试」' : (itemQ || flt ? '没有匹配条目（换关键词或切回全部状态）' : '还没有条目——点右上「录入 / 导入问答」') }}
              pagination={listPagination({
                current: itemPage,
                total: page.total,
                size: itemLimit,
                onChange: (p, s) => queryItems({ page: p, limit: s }),
              })}
            />
          </>
        )}
      </Drawer>

      {/* ── ① 新建训练集·两步向导（Vue :574-662）──
          第一步「来源模式」决定后面所有事：走哪条创建通道、要不要选标注项目/数据源、建完直达哪儿；
          第二步核对后才真建。StepHeader 的老页等价物＝antd Steps，goDsStep 只允许回退已完成步骤。
          footer 用自定义钮（不写 cancelText 字面量：askConfirm 那一处是 parity9a A15 的锚）。 ── */}
      <Modal
        open={createOpen}
        title={'新建训练集 · ' + (dsStep + 1) + ' / ' + DS_STEPS.length}
        width={720}
        onCancel={() => setCreateOpen(false)}
        footer={(
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Button onClick={() => setCreateOpen(false)}>取消</Button>
            <div style={{ flex: 1 }} />
            {dsStep > 0 && <Button onClick={() => setDsStep(dsStep - 1)}>← 上一步</Button>}
            {dsStep < DS_STEPS.length - 1 ? (
              <Button type="primary" disabled={!canNextDs} onClick={nextDs}>下一步：确认 →</Button>
            ) : (form.mode === 'annotation' ? (
              <Button type="primary" disabled={creating || !canNextDs} onClick={() => void createFromAnnotation()}>
                {creating ? '同步中…' : '同步并创建'}
              </Button>
            ) : (
              <Button type="primary" disabled={creating} onClick={() => void create()}>
                {creating ? '创建中…' : '创建并继续'}
              </Button>
            ))}
          </div>
        )}
      >
        <Steps
          size="small"
          current={dsStep}
          onChange={goDsStep}
          items={DS_STEPS.map((t) => ({ title: t }))}
          style={{ marginBottom: 16 }}
        />
        {dsStep === 0 && (
          <div>
            {/* 四来源模式卡（Vue src-grid :580-589）；键盘 Enter/Space 与鼠标同权（:583-585） */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
              {SOURCE_MODES.map((m) => (
                <div
                  key={m.key}
                  role="button"
                  tabIndex={0}
                  onClick={() => pickMode(m.key)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); pickMode(m.key) }
                  }}
                  style={{
                    border: '1px solid ' + (form.mode === m.key ? '#1677ff' : '#d9d9d9'),
                    background: form.mode === m.key ? '#e6f4ff' : '#fff',
                    borderRadius: 8, padding: '10px 12px', cursor: 'pointer',
                  }}
                >
                  <b>{m.title}</b>
                  <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>{m.desc}</div>
                </div>
              ))}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 14 }}>
              {/* 标注项目：就地选、就地同步，不再跳到标注页（Vue :591-608） */}
              {form.mode === 'annotation' && (
                <div>
                  <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>标注项目</div>
                  <Select
                    style={{ width: '100%' }}
                    value={form.project_id}
                    onChange={(v) => { const pid = Number(v); setForm((p) => ({ ...p, project_id: pid })); applyProjectName(pid) }}
                    options={[{ value: 0, label: '（选一个已标注的项目）' }]
                      .concat(lsProjects.map((p) => ({
                        value: p.id, label: p.title + ' · 已标 ' + p.annotated + ' / ' + p.task_number,
                      })))}
                  />
                  {lsLoading ? (
                    <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>正在读取标注项目…</div>
                  ) : (!lsProjects.length ? (
                    <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                      还没有标注项目。
                      <Typography.Link onClick={() => { setCreateOpen(false); navigate('/mos/labels') }}>去标注页新建 →</Typography.Link>
                      标完后回到这里，同步会直接生成训练集。
                    </div>
                  ) : (pickedProject && !pickedProject.annotated ? (
                    <div style={{ fontSize: 12, color: '#cf1322', marginTop: 4 }}>这个项目还没有已标注条目，先标完再同步。</div>
                  ) : (pickedProject ? (
                    <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                      将同步 {pickedProject.annotated} 条；训练集先为草稿，审核通过再发布。
                    </div>
                  ) : null)))}
                </div>
              )}
              {/* 公开语料的名字在语料选择器里填，向导这一步不重复要（Vue :609-614） */}
              {!(form.mode === 'import' && importKind === 'catalog') && (
                <div>
                  <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>训练集名称</div>
                  <Input
                    value={form.name}
                    placeholder="如：行业问答 v1"
                    onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))}
                    onPressEnter={nextDs}
                  />
                </div>
              )}
              {form.mode === 'generate' && (
                <div>
                  <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>从哪个数据源生成</div>
                  <Select
                    style={{ width: '100%' }}
                    value={form.source_id || undefined}
                    placeholder="（还没有数据源）"
                    onChange={(v) => setForm((p) => ({ ...p, source_id: String(v ?? '') }))}
                    options={datasources.map((d) => ({
                      // REQ-UX-FLOW-1#2：StreamVision 只产文本知识——名字带「文本」并在下拉里标注，图片/视频类不走它
                      value: d.id,
                      label: (d.name || d.id) + (d.type === 'streamvision' ? '（仅文本）' : ''),
                    }))}
                  />
                  <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>创建后直接进入生成卡片；生成会调用模型，建议先小样本。</div>
                </div>
              )}
              {form.mode === 'import' && (
                <div>
                  <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>导入方式</div>
                  <Segmented
                    value={importKind}
                    onChange={(v) => setImportKind(String(v))}
                    options={[
                      { value: 'file', label: '上传文件' },
                      { value: 'catalog', label: '公开语料' },
                    ]}
                  />
                  <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                    {importKind === 'file'
                      ? 'JSONL / TSV；创建后直接打开导入窗口，重复问题自动跳过。'
                      : '下一步直接打开公开语料选择器，导入时自动建训练集。'}
                  </div>
                </div>
              )}
            </div>
            <div style={{ border: '1px dashed #d9d9d9', borderRadius: 8, padding: '8px 12px', marginTop: 14, fontSize: 12.5 }}>
              <b>先选数据从哪来</b>，再填名称；下一步核对，最后一步才创建。
            </div>
            {/* 来源/数据属性由模式写死（导入=imported/导入参考、手工=manual/人工、
                生成=所选数据源、标注同步=label_studio 由同步接口标）——Vue 向导同样不展示
                这块（:632 只有 wizard-note），照搬不加戏；datasets.py:107 的「不传 source_id
                才默认 local」只在向导外调用方生效。 */}
          </div>
        )}
        {dsStep === 1 && (
          <div>
            <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 14px' }}>
              <Kv k="数据来源"><b>{sourceModeTitle}</b></Kv>
              {pickedProject && (
                <Kv k="标注项目">#{pickedProject.id} {pickedProject.title} · 已标 {pickedProject.annotated} 条</Kv>
              )}
              <Kv k="名称">{form.name.trim()}</Kv>
              {form.mode === 'generate' && <Kv k="数据源">{datasourceName(form.source_id)}</Kv>}
            </div>
            <div style={{
              background: '#f6f8fa', border: '1px solid #eaecef', borderRadius: 8,
              padding: '10px 14px', marginTop: 12, fontSize: 12.5,
            }}
            >
              {AFTER_CREATE_HINT[form.mode] || '确认后创建训练集。'}
            </div>
          </div>
        )}
      </Modal>

      {/* ── ⑧ 导入公开语料（Vue 模板 :397-482 + 逻辑 :1606-1722）──
          两步＝选语料 → 点「导入」。不带 datasetId＝导入即新建一份（向导「公开语料」分支）；
          带＝追加进当前集（source-actions 卡）。本地副本在场时分批导、导完不关窗（:1715）。 ── */}
      <Modal
        open={catOpen}
        title="导入公开语料"
        width={720}
        onCancel={() => setCatOpen(false)}
        footer={(
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Button onClick={() => void probeCatalog()}>先探测一下</Button>
            <div style={{ flex: 1 }} />
            <Button type="primary" disabled={catBusy} onClick={() => void doCatalogImport()}>
              {catBusy ? '导入中…' : (catLocal ? '从本地导入一批' : '导入')}
            </Button>
          </div>
        )}
      >
        <div style={{ fontSize: 12.5, color: '#888', lineHeight: 1.7, marginBottom: 12 }}>
          <b>就两步：选一个语料 → 点「导入」。</b>其他都可以不管。<br />
          拉进来的是一份 <Tag color="gold">草稿</Tag> 问答集，还要过校验/审核才能发布。
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>① 选语料</div>
            <Select
              style={{ width: '100%' }}
              value={cat.entry_id || undefined}
              placeholder={catalogList.length ? undefined : '正在读取公开语料目录…'}
              onChange={(v) => onCatEntryChange(String(v))}
              options={catalogList.map((c) => ({ value: c.id, label: c.label }))}
            />
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>{catDesc}</div>
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>② 导入多少条</div>
              <InputNumber
                style={{ width: '100%' }}
                min={1}
                value={cat.limit}
                onChange={(v) => setCat((p) => ({ ...p, limit: v == null ? 1 : Math.trunc(Number(v)) }))}
              />
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>单次上限 500；想更多就分批再导一次</div>
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>名字（可选）</div>
              <Input
                value={cat.name}
                placeholder="留空 = 就用语料名"
                onChange={(e) => setCat((p) => ({ ...p, name: e.target.value }))}
              />
            </div>
          </div>
        </div>
        <details style={{ marginTop: 10 }}>
          <summary style={{ cursor: 'pointer', fontSize: 12.5, color: '#888' }}>
            高级（不熟就别动）：自定义仓库 / 直链 / 字段映射
          </summary>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 10 }}>
            <div>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>自定义仓库 / 直链</div>
              <div style={{ display: 'flex', gap: 8 }}>
                <Select
                  style={{ width: 150 }}
                  value={cat.source}
                  onChange={(v) => setCat((p) => ({ ...p, source: String(v) }))}
                  options={[
                    { value: 'hf', label: 'HF 镜像' },
                    { value: 'modelscope', label: 'ModelScope' },
                    { value: 'url', label: '直链' },
                  ]}
                />
                <Input
                  value={cat.repo}
                  placeholder="仓库名（如 shibing624/alpaca-zh）或一个直链 URL"
                  onChange={(e) => setCat((p) => ({ ...p, repo: e.target.value }))}
                />
              </div>
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>上面选预设语料就够了；填了这里会忽略上面的选择</div>
            </div>
            <div>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>字段映射</div>
              <div style={{ display: 'flex', gap: 8 }}>
                <Input
                  value={cat.qcol}
                  placeholder="问题列名（默认 instruction）"
                  onChange={(e) => setCat((p) => ({ ...p, qcol: e.target.value }))}
                />
                <Input
                  value={cat.acol}
                  placeholder="答案列名（默认 output）"
                  onChange={(e) => setCat((p) => ({ ...p, acol: e.target.value }))}
                />
              </div>
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                语料表格里哪一列是问题、哪一列是答案；默认值适应大多数 Alpaca 系语料
              </div>
            </div>
          </div>
        </details>
        {catProbe && (
          <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '10px 14px', marginTop: 10 }}>
            <Kv k="探测">
              <span style={{ color: catProbe.ok ? '#389e0d' : '#cf1322' }}>
                {catProbe.ok ? '可用 · ' + catProbe.rows + ' 行' : '不可用'}
              </span>
            </Kv>
            {catProbe.file && <Kv k="文件"><span style={MONO}>{catProbe.file}</span></Kv>}
            {catProbe.sample && (
              <div style={{ fontSize: 12, color: '#888', marginTop: 6 }}>
                Q: {catProbe.sample.q}<br />A: {catProbe.sample.a}
              </div>
            )}
            {catProbe.error && <div style={{ fontSize: 12, color: '#cf1322' }}>{catProbe.error}</div>}
            {catProbe.truncated && (
              <div style={{ fontSize: 12, color: '#cf1322', marginTop: 4 }}>
                已撞到单次下载上限 {catProbe.cap_mb}MB，上面的行数不完整 —— 建议先「全量下载到本地」再导入。
              </div>
            )}
          </div>
        )}
        <div style={{
          display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'space-between',
          flexWrap: 'wrap', marginTop: 12,
        }}
        >
          <span style={{ fontSize: 12, color: '#888' }}>
            本地副本：<b>{catLocal || '（无）'}</b>
            {catTotal ? ' · 已导 ' + catOffset + ' / ' + catTotal : ''}
          </span>
          <Space size={6}>
            {catLocal && <Button size="small" onClick={() => clearLocal()}>不用本地</Button>}
            <Button size="small" disabled={dlBusy || catBusy} onClick={() => void doDownload()}>
              {dlBusy ? '下载中…' : '全量下载到本地'}
            </Button>
          </Space>
        </div>
        {dlDir && (
          <div style={{ fontSize: 11, color: '#888', marginTop: 4 }}>
            下载目录：<code style={MONO}>{dlDir}</code>
          </div>
        )}
        {downloads.length > 0 && (
          <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '6px 14px', marginTop: 8 }}>
            {downloads.map((d) => (
              <div
                key={d.rel}
                style={{ display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'space-between', padding: '5px 0', fontSize: 12 }}
              >
                <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  <b>{d.name}</b>
                  <span style={{ color: '#888' }}>
                    {' · ' + d.size_mb + 'MB · ' + (d.rows != null ? d.rows + ' 行' : '行数未数') + ' · ' + d.mtime}
                  </span>
                </span>
                <Space size={6}>
                  <Button size="small" onClick={() => useLocal(d)}>{catLocal === d.rel ? '在用' : '用这份'}</Button>
                  <Button size="small" danger onClick={() => void delLocal(d)}>删除</Button>
                </Space>
              </div>
            ))}
          </div>
        )}
      </Modal>

      {/* ── 录入 / 导入问答（Datasets.vue:484-516 三模式） ── */}
      <Modal
        open={manOpen}
        title="＋ 录入 / 导入问答"
        width={720}
        onCancel={() => setManOpen(false)}
        onOk={() => void saveManual()}
        okText="导入"
        confirmLoading={importing}
      >
        <Segmented
          value={manMode}
          onChange={(v) => setManMode(String(v))}
          options={[
            { value: 'one', label: '单条' },
            { value: 'bulk', label: '批量粘贴' },
            { value: 'file', label: '上传文件' },
          ]}
          style={{ marginBottom: 12 }}
        />
        {manMode === 'one' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            <div>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>问题</div>
              <Input.TextArea rows={2} value={man.q} onChange={(e) => setMan((p) => ({ ...p, q: e.target.value }))} />
            </div>
            <div>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>答案</div>
              <Input.TextArea rows={4} value={man.a} onChange={(e) => setMan((p) => ({ ...p, a: e.target.value }))} />
            </div>
          </div>
        )}
        {manMode === 'bulk' && (
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>批量内容</div>
            <Input.TextArea
              rows={10}
              style={MONO}
              value={man.bulk}
              onChange={(e) => setMan((p) => ({ ...p, bulk: e.target.value }))}
              placeholder={'每行一条，支持：\nJSONL: {"q":"问题","a":"答案"}\nTSV:\t问题<TAB>答案'}
            />
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>支持 JSONL、JSON 数组、或「问题⇥答案」TSV（一行一条）。</div>
          </div>
        )}
        {manMode === 'file' && (
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>文件</div>
            <input
              ref={fileEl}
              type="file"
              accept=".jsonl,.json,.tsv,.txt,.csv"
              style={{ fontSize: 12 }}
              onChange={(e) => {
                const f = e.target.files && e.target.files[0]
                setManFile(f || null)
              }}
            />
            {manFile && (
              <Space style={{ marginTop: 8 }}>
                <Tag>{manFile.name} · {clampInt(manFile.size / 1024, 0, Number.MAX_SAFE_INTEGER)} KB</Tag>
                <Button size="small" onClick={() => { setManFile(null); if (fileEl.current) fileEl.current.value = '' }}>清除</Button>
              </Space>
            )}
            <div style={{ fontSize: 12, color: '#888', marginTop: 8 }}>
              支持 <b>JSONL</b>（含训练轨迹 completions.jsonl——自动认 messages / prompt+response / teacher 字段）、
              <b>JSON 数组</b>、<b>TSV</b>（问题⇥答案）。已在库的条目<b>按问题自动跳过</b>，不会重复入库。
              上限 32MB，超限请切分。
            </div>
          </div>
        )}
      </Modal>

      {/* ── 媒体预览（数据/媒体两口径的出口；Datasets.vue:675-709） ── */}
      <Modal
        open={mediaOpen}
        title={mediaItem ? `媒体预览 · ${kindLabel(mediaItem.media_kind)}` : '媒体预览'}
        width={760}
        onCancel={() => setMediaOpen(false)}
        footer={(
          <Space>
            {mediaItem && mediaItem.media_exists !== false && (
              <Button href={mosMediaUrl(mediaItem.media_path)} target="_blank">新窗口打开</Button>
            )}
            <Button type="primary" onClick={() => setMediaOpen(false)}>关闭</Button>
          </Space>
        )}
      >
        {mediaItem && (
          <>
            {mediaItem.media_exists === false ? (
              <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '10px 14px', fontSize: 12.5 }}>
                <b>媒体文件已不在媒体库</b>
                <div style={{ color: '#888', marginTop: 4 }}>
                  可能已被删除或清理；这条的问答内容仍然保留。路径：
                  <span style={MONO}>{mediaItem.media_path}</span>
                </div>
              </div>
            ) : (
              <>
                {mediaItem.media_kind === 'audio' && (
                  <audio controls src={mosMediaUrl(mediaItem.media_path)} style={{ width: '100%' }} />
                )}
                {mediaItem.media_kind === 'video' && (
                  <video controls src={mosMediaUrl(mediaItem.media_path)} style={{ width: '100%', maxHeight: '58vh', background: '#000' }} />
                )}
                {mediaItem.media_kind === 'image' && (
                  <img src={mosMediaUrl(mediaItem.media_path)} style={{ maxWidth: '100%', maxHeight: '58vh', display: 'block', margin: '0 auto' }} alt="" />
                )}
                {mediaItem.media_kind === 'text' && (
                  <div style={{ fontSize: 12, color: '#888' }}>
                    文本 / 文档类媒体：<a href={mosMediaUrl(mediaItem.media_path)} target="_blank" rel="noreferrer">在新窗口打开</a>
                  </div>
                )}
                {!['audio', 'video', 'image', 'text'].includes(mediaItem.media_kind || '') && (
                  <div style={{ fontSize: 12, color: '#888' }}>这条没有可预览的媒体文件。</div>
                )}
                <Divider style={{ margin: '12px 0' }} />
                <Kv k="媒体">{kindLabel(mediaItem.media_kind)}</Kv>
                <Kv k="路径" title={mediaItem.media_path || ''}>{mediaItem.media_path || '—'}</Kv>
                <div style={{ margin: '8px 0 2px', fontSize: 12, color: '#888' }}>问题</div>
                <div style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>{mediaItem.q}</div>
                <div style={{ margin: '8px 0 2px', fontSize: 12, color: '#888' }}>答案</div>
                <div style={{ fontSize: 13, whiteSpace: 'pre-wrap' }}>{mediaItem.a}</div>
              </>
            )}
          </>
        )}
      </Modal>

      {/* ── ③ 条目编辑（Vue :664-672：问题 rows3 / 答案 rows6 / 取消 / 保存）──
          q/a 任一落库后端即置 human_edited=1（datasets.py:609），状态列出「已人工改」；
          改动还会作废已冻结版本（datasets.py:614-621），靠 refreshDetail 把新鲜行并回卡面 ── */}
      <Modal
        open={editOpen}
        title="编辑条目"
        width={680}
        onCancel={() => setEditOpen(false)}
        onOk={() => void saveEdit()}
        okText="保存"
        cancelText="取消"
        confirmLoading={savingEdit}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>问题</div>
            <Input.TextArea rows={3} value={editDraft.q} onChange={(e) => setEditDraft((p) => ({ ...p, q: e.target.value }))} />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>答案</div>
            <Input.TextArea rows={6} value={editDraft.a} onChange={(e) => setEditDraft((p) => ({ ...p, a: e.target.value }))} />
          </div>
        </div>
      </Modal>

      {/* ── ⑥ 引用清单弹窗（Vue :712-769）──
          「读取成功」与「0 条」必须分开：refsLoaded 假→删除钮禁用+红块重试，
          不能伪装成「引用已清空」（这正是 2026-10-23 项目负责人反馈的「已删除还提示引用」之根）。
          deployment 行无「查看」：React 站内无模型服务页（通道限制，卡面已记账）。 ── */}
      <Modal
        open={refsOpen}
        title="训练集仍被引用，先解除"
        width={760}
        onCancel={() => setRefsOpen(false)}
        footer={(
          <Space>
            <Button onClick={() => setRefsOpen(false)}>取消</Button>
            <Button disabled={refsLoading} onClick={() => void loadRefs()}>刷新引用</Button>
            <Button
              danger
              type="primary"
              disabled={!refsLoaded || refsList.length > 0 || refsDeleting || !!refsError}
              title={!refsLoaded ? '引用清单未读到，先点「刷新引用」'
                : (refsList.length ? '还有 ' + refsList.length + ' 项引用' : '')}
              onClick={deleteNow}
            >{refsDeleting ? '删除中…' : '删除训练集'}</Button>
          </Space>
        )}
      >
        {refsTarget && (
          <div>
            <div style={{ fontSize: 12.5, marginBottom: 8 }}>
              训练集「<b>{refsTarget.name}</b>」{refsLoaded
                ? <>还被下面 <b>{refsList.length}</b> 项引用或阻挡</>
                : <>的引用清单还没读到</>}。
              删除训练集前要先解除；运行中 / 已部署的会写明原因，其余可以在这里直接删。
              {refsAt ? <span style={{ color: '#888' }}> · 检查于 {refsAt}</span> : null}
            </div>
            {refsError && (
              <div style={{ background: '#fff2f0', border: '1px solid #ffccc7', borderRadius: 8, padding: '8px 12px', marginBottom: 8 }}>
                <b style={{ color: '#cf1322' }}>读取引用失败</b>
                <div style={{ fontSize: 12, color: '#888', margin: '4px 0 6px' }}>{refsError}</div>
                <Button size="small" disabled={refsLoading} onClick={() => void loadRefs()}>重试</Button>
              </div>
            )}
            {refsLoading && <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>读取引用…</div>}
            {refsList.length > 0 && (
              <Table<MosDatasetRef>
                rowKey={(r) => r.kind + ':' + r.id}
                size="small"
                dataSource={refsList}
                pagination={false}
                columns={[
                  { title: '类型', key: 'kind', width: 82, render: (_, r) => <Tag>{refKind(r.kind)}</Tag> },
                  {
                    title: '名称', key: 'name',
                    render: (_, r) => (
                      <div style={{ minWidth: 0 }}>
                        <b style={{ ...MONO, fontSize: 12 }}>{r.name}</b>
                        <div style={{ ...MONO, fontSize: 10.5, color: '#888' }}>{r.id}</div>
                        {r.blocked ? <div style={{ fontSize: 11.5, color: '#cf1322' }}>{r.blocked}</div>
                          : r.warn ? <div style={{ fontSize: 11.5, color: '#d48806' }}>{r.warn}</div> : null}
                      </div>
                    ),
                  },
                  {
                    title: '状态', key: 'status', width: 92,
                    render: (_, r) => {
                      const [cn, color] = refStatus(r.status)
                      return <Tag color={color}>{cn}</Tag>
                    },
                  },
                  {
                    title: '操作', key: 'ops', width: 196,
                    render: (_, r) => {
                      const key = r.kind + ':' + r.id
                      const busy = refBusy === key
                      return (
                        <Space size={6} style={{ whiteSpace: 'nowrap' }}>
                          {r.kind !== 'deployment' && <Button size="small" onClick={() => openRef(r)}>查看</Button>}
                          <Button
                            size="small"
                            danger
                            disabled={!!r.blocked || busy}
                            title={r.blocked || '删除这条引用'}
                            onClick={() => void deleteRef(r)}
                          >
                            {busy ? '删除中…' : (r.force ? '删除（含门禁）' : '删除引用')}
                          </Button>
                        </Space>
                      )
                    },
                  },
                ]}
              />
            )}
            {refsLoaded && !refsLoading && !refsError && !refsList.length && (
              <div style={{ padding: '6px 0' }}>
                <b style={{ color: '#389e0d' }}>引用已清空</b>
                <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>现在可以删除训练集了。</span>
              </div>
            )}
          </div>
        )}
      </Modal>

      {/* ── ⑩ 数据源就地管理弹窗（Vue :29-32 工具栏入口 + DataSources.vue 整页的 React 化；
          关窗=Vue toggleDS 关合语义，必回刷训练集列表。通道=api/mos.ts datasource* 封装，
          页面零自带 send；本页是该封装群的首个消费方） ── */}
      <Modal open={dsmOpen} title="数据源" width={860} onCancel={closeDsMgr}
             footer={<Button onClick={closeDsMgr}>关闭</Button>}
      >
        <div style={{ marginBottom: 8 }}>
          <b>知识从哪来</b>
          <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>
            三种入口：StreamVision 文本知识（默认，只出文本）/ 上传文档 / 手工录入；后面出题、蒸馏、训练都从这里取料
          </span>
        </div>
        <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }} wrap>
          <Space wrap>
            <Tag>共 {dsmList.length} 个数据源</Tag>
            <Tag>可用 {dsmList.filter((d) => !!d.last_test_ok).length}</Tag>
          </Space>
          <Space wrap>
            <Button size="small" onClick={() => void dsmLoad()}>刷新</Button>
            <Button type="primary" size="small" onClick={dsmOpenCreate}>＋ 新建数据源</Button>
          </Space>
        </Space>
        {dsmError && !dsmList.length ? (
          /* 加载失败不能画成「还没有数据源」：那会让人以为数据源被删了（DataSources.vue:15 注释原话） */
          <div role="alert" style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: 16, textAlign: 'center' }}>
            <b>数据源没加载出来</b>
            <div style={{ fontSize: 12, color: '#888', margin: '4px 0 12px' }}>{dsmError}</div>
            <Button type="primary" onClick={() => void dsmLoad()}>重试</Button>
          </div>
        ) : (
          <Table<MosDatasource>
            rowKey="id"
            size="small"
            loading={dsmLoading}
            dataSource={dsmList}
            pagination={{ pageSize: 12, hideOnSinglePage: true }}
            locale={{ emptyText: '还没有数据源' }}
            columns={[
              {
                title: '名称',
                key: 'name', width: '18%',
                render: (_, d) => (
                  <div style={{ minWidth: 0 }}>
                    <b>{d.name}</b>
                    <div style={{ ...MONO, fontSize: 10.5, color: '#888' }}>{d.id}</div>
                  </div>
                ),
              },
              { title: '类型', key: 'type', width: '12%', render: (_, d) => <Tag>{String(d.type_label || d.type || '')}</Tag> },
              {
                title: '来源 / 内容',
                key: 'config',
                render: (_, d) => (
                  <div style={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={String(d.config || '')}>
                    {d.type === 'upload' ? (
                      <>
                        <span style={{ color: '#888' }}>上传目录</span> {String(d.config || '—')}
                        <div style={{ fontSize: 10.5, color: '#888' }}>点「文件」管理已传文档</div>
                      </>
                    ) : d.type === 'manual' ? (
                      <>
                        <span style={{ color: '#888' }}>手工文本</span>
                        <div style={{ fontSize: 10.5, color: '#888' }}>点「文本」编辑内容</div>
                      </>
                    ) : <span style={MONO}>{String(d.config || '—')}</span>}
                  </div>
                ),
              },
              {
                title: '规模',
                key: 'stats', width: '12%',
                render: (_, d) => {
                  const st = (d.stats || {}) as Record<string, any>
                  if (!(d.stats && st.ok)) return <span style={{ color: '#888' }}>—</span>
                  return (
                    <div>
                      <b>{String(st.units ?? '')}</b> <span style={{ color: '#888' }}>单元</span>
                      {st.files ? <div style={{ fontSize: 10.5, color: '#888' }}>{st.files} 文件</div> : null}
                      {st.size_mb ? <div style={{ fontSize: 10.5, color: '#888' }}>{st.size_mb} MB</div> : null}
                    </div>
                  )
                },
              },
              {
                title: '连通',
                key: 'test', width: '11%',
                render: (_, d) => (
                  <div>
                    {d.last_test_ok ? <Tag color="green">可用</Tag>
                      : d.last_test_at ? <Tag color="red">不可用</Tag>
                        : <Tag color="gold">未测</Tag>}
                    {!!d.last_test_at && (
                      <div style={{ fontSize: 10.5, color: '#888' }} title={String(d.last_test_msg || '')}>{String(d.last_test_at)}</div>
                    )}
                  </div>
                ),
              },
              {
                title: '操作',
                key: 'ops', width: 250,
                render: (_, d) => (
                  <Space size={6} wrap>
                    <Button size="small" disabled={!!dsmBusy[d.id]} onClick={() => void dsmTest(d)}>{dsmBusy[d.id] ? '测试中…' : '测试'}</Button>
                    <Button size="small" onClick={() => void dsmPreview(d)}>预览</Button>
                    {d.type === 'upload' && <Button size="small" onClick={() => void dsmOpenFiles(d)}>文件</Button>}
                    {d.type === 'manual' && <Button size="small" onClick={() => void dsmOpenText(d)}>文本</Button>}
                    <Button size="small" onClick={() => dsmOpenEdit(d)}>编辑</Button>
                    <Button size="small" danger onClick={() => void dsmRemove(d)}>删除</Button>
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Modal>

      {/* ⑩ 新建/编辑数据源（DataSources.vue :94-134：类型编辑期锁死；streamvision 点选自动探测
          结果、不让手输路径；upload 建好即传；manual 正文随建/存一起走） */}
      <Modal open={dsmFormOpen} title={dsmEditing ? '编辑数据源' : '新建数据源'} width={660}
             onCancel={() => setDsmFormOpen(false)}
             footer={[
               <Button key="cancel" onClick={() => setDsmFormOpen(false)}>取消</Button>,
               <Button key="save" type="primary" disabled={dsmSaving} onClick={() => void dsmSave()}>
                 {dsmSaving ? '处理中…' : (dsmEditing ? '保存' : '创建')}
               </Button>,
             ]}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px 14px' }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>类型</div>
            <Select
              style={{ width: '100%' }}
              value={dsmForm.type}
              disabled={!!dsmEditing}
              onChange={(v) => { setDsmForm((p) => ({ ...p, type: v })); void dsmOnType(v) }}
              options={dsmTypes.map((t) => ({ value: String(t.type), label: String(t.label || t.type) }))}
            />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>名称</div>
            <Input value={dsmForm.name} placeholder="如：行业规范文档" onChange={(e) => setDsmForm((p) => ({ ...p, name: e.target.value }))} />
          </div>
          {dsmForm.type === 'streamvision' && (
            <div style={{ gridColumn: '1 / -1' }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>选择知识库</div>
              {!!svCands.length && !svManual && (
                <Select
                  style={{ width: '100%' }}
                  value={dsmForm.config || undefined}
                  onChange={(v) => setDsmForm((p) => ({ ...p, config: v }))}
                  options={svCands.map((c) => ({
                    value: String(c.path),
                    label: String(c.path) + (c.ok ? `（${c.units || 0} 条素材）` : '（不可用）'),
                  }))}
                />
              )}
              {!!svCands.length && !svManual && (
                <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>系统自动探测到的，直接选即可；只读接入，不会改动它的库</div>
              )}
              {!svCands.length && !svManual && (
                <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>没探测到现成的知识库 —— 勾下面手动填路径</div>
              )}
              <div style={{ marginTop: 8 }}>
                <Checkbox checked={svManual} onChange={(e) => setSvManual(e.target.checked)}>手动指定路径（探测不到时用）</Checkbox>
              </div>
              {svManual && (
                <Input style={{ ...MONO, marginTop: 6 }} placeholder="/path/to/streamvision.db"
                       value={dsmForm.config} onChange={(e) => setDsmForm((p) => ({ ...p, config: e.target.value }))} />
              )}
            </div>
          )}
          {dsmForm.type === 'upload' && (
            <div style={{ gridColumn: '1 / -1' }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>选择文档</div>
              <input
                ref={dsmPickEl}
                type="file"
                multiple
                accept={DSM_ACCEPT}
                style={{ fontSize: 12 }}
                onChange={(e) => setDsmPicked(Array.from(e.target.files || []))}
              />
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                文本(txt/md/jsonl/csv) · PDF · Word(docx/doc) · PPT(pptx) · Excel(xlsx/xls) · 网页(html) · 图片(截图/扫描件，需本地 VLM 在线)；自动抽正文再切片。可多选。
              </div>
              {!!dsmPicked.length && (
                <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>已选 {dsmPicked.length} 个：{dsmPicked.map((f) => f.name).join('、')}</div>
              )}
            </div>
          )}
          {dsmForm.type === 'manual' && (
            <div style={{ gridColumn: '1 / -1' }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>粘贴文本</div>
              <Input.TextArea
                rows={10}
                value={dsmForm.text}
                placeholder="把规范、说明、经验等直接粘到这里；空行分段，每段会成为一条素材。"
                onChange={(e) => setDsmForm((p) => ({ ...p, text: e.target.value }))}
              />
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>空行分隔的每段 ≥ 40 字，才会被当成一条素材。</div>
            </div>
          )}
          <div style={{ gridColumn: '1 / -1' }}>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>备注</div>
            <Input value={dsmForm.notes} placeholder="可选" onChange={(e) => setDsmForm((p) => ({ ...p, notes: e.target.value }))} />
          </div>
        </div>
      </Modal>

      {/* ⑩ 上传文件管理（DataSources.vue :137-156） */}
      <Modal open={dsmFilesOpen} title={String((dsmList.find((d) => d.id === dsmCurIdRef.current) || {}).name || '')} width={680}
             onCancel={() => setDsmFilesOpen(false)} footer={<Button onClick={() => setDsmFilesOpen(false)}>关闭</Button>}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}>
          <span style={{ fontSize: 12, color: '#888' }}>{dsmFiles.length} 个文件</span>
          <div>
            <input
              ref={dsmFileInputEl}
              type="file"
              multiple
              accept={DSM_ACCEPT}
              style={{ display: 'none' }}
              onChange={(e) => {
                const arr = Array.from(e.target.files || [])
                e.target.value = ''
                if (arr.length) void dsmUpload(arr)
              }}
            />
            <Button size="small" disabled={dsmSaving} onClick={() => dsmFileInputEl.current?.click()}>
              {dsmSaving ? '上传中…' : '＋ 添加文档'}
            </Button>
          </div>
        </div>
        <div style={{ maxHeight: '48vh', overflow: 'auto' }}>
          {dsmFiles.map((f) => (
            <div key={String(f.name)} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 10px', marginBottom: 6 }}>
              <div style={{ minWidth: 0 }}>
                <b style={{ fontSize: 12.5 }}>{String(f.name)}</b>
                <div style={{ fontSize: 10.5, color: '#888' }}>{String(f.size_kb ?? '')} KB · {String(f.mtime || '')}</div>
              </div>
              <Button size="small" danger onClick={() => void dsmDelFile(f)}>删除</Button>
            </div>
          ))}
          {!dsmFiles.length && <div style={{ textAlign: 'center', color: '#888', padding: '12px 0' }}>还没有上传文档</div>}
        </div>
      </Modal>

      {/* ⑩ 手工文本编辑（DataSources.vue :159-171：读失败出红块+重试，绝不让 textarea 假装是空的） */}
      <Modal open={dsmTextOpen} title={String((dsmList.find((d) => d.id === dsmCurIdRef.current) || {}).name || '')} width={720}
             onCancel={() => setDsmTextOpen(false)}
             footer={[
               <Button key="cancel" onClick={() => setDsmTextOpen(false)}>取消</Button>,
               <Button key="save" type="primary" disabled={dsmSaving || dsmTextLoading || !!dsmTextError} onClick={() => void dsmSaveText()}>
                 {dsmTextLoading ? '加载中…' : (dsmSaving ? '保存中…' : '保存文本')}
               </Button>,
             ]}
      >
        {!!dsmTextError && (
          <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 9, flexWrap: 'wrap', border: '1px solid #fecaca', background: '#fff5f5', color: '#b91c1c', borderRadius: 6, padding: '8px 11px', marginBottom: 10, fontSize: 12.5 }}>
            <b>正文没读出来</b>
            <span style={{ color: '#7f1d1d' }}>{dsmTextError}</span>
            <Button size="small" onClick={() => { const d = dsmList.find((x) => x.id === dsmCurIdRef.current); if (d) void dsmOpenText(d) }}>重试</Button>
          </div>
        )}
        <Input.TextArea
          rows={16}
          value={dsmTextVal}
          disabled={dsmTextLoading}
          placeholder={dsmTextLoading ? '加载中…' : '空行分隔的每段 ≥ 40 字，会成为一条素材。'}
          onChange={(e) => setDsmTextVal(e.target.value)}
        />
      </Modal>

      {/* ⑩ 取料预览（DataSources.vue :174-188） */}
      <Modal open={dsmPvOpen} title={'预览 · ' + String((dsmList.find((d) => d.id === dsmCurIdRef.current) || {}).name || '')} width={720}
             onCancel={() => setDsmPvOpen(false)} footer={<Button onClick={() => setDsmPvOpen(false)}>关闭</Button>}
      >
        <div style={{ fontSize: 12, color: '#888', marginBottom: 8 }}>取前 {dsmPvItems.length} 条素材片段（只读）</div>
        <div style={{ maxHeight: '52vh', overflow: 'auto' }}>
          {dsmPvItems.map((it, i) => (
            <div key={i} style={{ border: '1px solid #f0f0f0', borderRadius: 8, padding: '8px 12px', marginBottom: 8 }}>
              <div style={{ ...MONO, fontSize: 10.5, color: '#888', marginBottom: 4 }}>{String(it.ref ?? '')}</div>
              <div style={{ fontSize: 12.5, lineHeight: 1.6, whiteSpace: 'pre-wrap' }}>{String(it.text ?? '')}</div>
            </div>
          ))}
          {!dsmPvItems.length && <div style={{ textAlign: 'center', color: '#888', padding: '12px 0' }}>没有取到素材</div>}
        </div>
      </Modal>
    </div>
  )
}
