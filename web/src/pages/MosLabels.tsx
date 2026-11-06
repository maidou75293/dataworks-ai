import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Checkbox, Form, Input, InputNumber, Modal, Progress, Segmented, Select, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { TableProps } from 'antd'
import { PlusOutlined, ReloadOutlined, TagsOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import MosMediaPicker from '../components/MosMediaPicker'
import {
  aiRuns as fetchAiRuns, aiStats as fetchAiStats, createLsProject, deleteLsProject,
  listLsProjects, lsConfig, saveLsConfig,
} from '../api/mos'
import { wsTransport } from '../components/ws/wsApi'
import type {
  MosLsAiRun, MosLsAiStats, MosLsConfig, MosLsProject, MosMediaItem,
} from '../api/mos'
import { TEMPLATES, templatesFor } from '../annotationTemplates'
import { kbActivate, listPagination, useGlobalPageSize } from '../utils/listPrefs'
import { canAny } from '../permissions'
import { listNightPosts, saveNightPost, type NightPost } from '../api/flywheel'
import { buildMediaTasks } from '../mediaTasks'
import { clearPendingPick, getPendingPick, setCreateDraft, takeCreateDraft } from '../mediaPick'

/**
 * 标注项目列表（MOS）——REQ-UNIFY-6-2 入口最小闭环。
 *
 * 背景（2026-10-29 用户报「标注入口很难找」）：工作台 /mos/label-studio/:pid 在 REQ-UNIFY-6-1
 * 已移植，但 vendor LabelStudio.vue（项目列表+新建）一直没重写进中台，站内零入口，
 * 用户只能手输带 pid 的 URL。本页补上「菜单 → 列表 → 进工作台/新建项目」的链路；
 * 完整对齐 vendor（设置页 ?settings=1、从数据集/媒体建项目等）仍属后续批次。
 *
 * 口径：数据走 /api/mos/api/label-studio/projects 通道（会话代持 + mos:access，REQ-UNIFY-1）；
 * 端点对照 mos/platform/backend/api/label_studio.py（list:255、create:700）。
 * 三态铺满按 REQ-UNIFY-2 checklist（错误 > 首屏加载 > 空态 > 内容）。
 *
 * BUG-PARITY-7（2026-10-29）：行操作恢复「同步」——原生画布（workbench=false）项目
 * 在工作台被 `disabled={!project?.workbench}`（MosWorkbench.tsx）拦死，列表又没有同步，
 * 已标数据进不了数据集闭环。端点走 wsApi 现成同步通道（wsTransport.lsToDataset，
 * 同错误体口径：mos 侧 FastAPI {detail} 转 Error.message=后端原文）。
 * 语义照老版 LabelStudio.vue:149-154,973-991：全局并发闸（同一时间只跑一个，防对同一
 * 项目并发发两个 to-dataset）、禁用原因 tooltip、「同步中…」文案、成功后刷新 sync-map。
 * 血缘列先做最小版（数据集名+上次同步时间）供同步结果可见；可点跳转/完整口径归 PARITY-8。
 *
 * BUG-PARITY-13-C（老 LabelStudio.vue:241-261,1148-1204,1250-1256）：
 * 「从媒体建样本」的落地端——媒体库勾完点「加入新建项目」→ ?create=1 打开本向导，
 * takeCreateDraft 恢复离开前的项目名/模板、getPendingPick 预填样本（mediaPick.ts，
 * 老 annotPick.js:5-31 同款 sessionStorage 键）；向导内嵌 MosMediaPicker 可继续挑
 * （老 :252 `<MediaPicker v-model="pickedMedia">` 同位），创建走
 * buildMediaTasks+createLsProject({label_config,tasks})，成功清 pendingPick（老 :1200）。
 * 混类型拦截沿用 13-B 文案（老 callout :256-261 的「一个项目只能用一种画布」约束）。
 *
 * BUG-PARITY-8-A（老 LabelStudio.vue 列表交互回搬，卡号即回归台账）：
 * ① 搜索 :18,851-855——placeholder「搜索项目名 / ID…」，过滤式含 `#id`（纯数字关键词
 *    命中项目号）；② 筛选分段 :19-24,855-857——全部/工作台/未支持/未完成（native=
 *    !workbench、todo=unannotated>0，均为老版原语义）；搜索/筛选变化回第 1 页
 *    （usePager track :861-865 同款）；「显示 X / Y 个」计数 :85。
 * ③ 删除 :155,994-1011,545-561——行内 danger「删除」→受控确认弹窗（文案逐字），
 *    走 api/mos.ts deleteLsProject（后端 label_studio.py:728），成功「已删除项目 #id」
 *    重拉列表、失败出后端原文且弹窗不关（老版 :1010 口径）；按钮序 进入/同步/删除
 *    照 Vue:143-155。
 * ⑩ Enter 提交：老版向导 Enter=下一步（Vue:240），新版无弹窗向导=Enter 直接建
 *    （Form onFinish，校验未过不发请求；顺带修评审「validateFields 裸 await」的
 *    UnhandledRejection——onFinish 只在通过后回调）。
 * 连带·落点接住：?q= 预填搜索（老版靠搜索接住飞轮裸跳）、?pid= 深链直达
 *    （口径同 MosDatasets ?ds= 深链跟随 :203-209：列表加载后命中才跳、未命中停留）。
 *
 * BUG-PARITY-8-B（老 LabelStudio.vue 连接/台账/三态回搬，封装现成端点）：
 * ⑥ 连接设置 :16,193-227,879-884,960-971——工具栏「连接设置」入口 + 弹窗三字段（底层标注
 *    引擎地址 / API Token / 预标注服务 ML Backend）+ callout「平台是唯一入口…」:212-218 逐字。
 *    GET/POST /api/label-studio/config（api/mos.ts 补 lsConfig/saveLsConfig）：响应只有
 *    token_set 布尔（Token 服务端专用），留空=不改（服务端判，POST 传空串即保持原值）；
 *    「保存并测试」在 savingCfg 期文案「测试中…」；connected→「标注引擎已连接」+关窗+重拉
 *    列表，connected=false→「连接失败，检查地址和 Token」且窗不关，两分支都重拉（Vue:968-970）。
 *    老弹窗内「预标注服务 在线/离线」提示与页头 aiStatus(VLM/YOLO) chips 依赖 lsMl/aiStatus
 *    端点，不在本卡面声明端点内——**本卡不做，如实记账**（ML Backend 地址字段本身照常保存）。
 * ⑦ AI 标注台账 :15,564-601,890-900——工具栏「AI 标注记录」→「AI 自动标注记录」弹窗：统计
 *    chips + 说明逐字 + 台账表（时间/项目/任务/引擎+model_version/框/结果/写入标注二态），
 *    空态「还没有 AI 标注记录」；ai-stats?days=30、ai-runs?limit=100（pid=0=全局、查询串
 *    省略 pid，老 api.js:296-300 同口径）；项目列本地反查 title（Vue projectTitle :902-905）。
 * ⑧ 画布三态 :126-131,143-148,948-956——「画布」列=类型标签（CFG_MODES :809-812）+三态副行
 *    （workbench→平台画布；has_controls→未支持；否则→无标注控件，tooltip 逐字）；操作列三分：
 *    平台画布→进工作台（钮面沿用新版「进入工作台」，老版「继续标注」属文案域不随本卡改）；
 *    未支持→原生标注 ↗（nativeCanvasUrl：cfg.url 去尾斜杠 + 127.0.0.1→localhost 改写
 *    ——LS 登录 Cookie 绑 localhost，Vue:946-952 原注）；无控件→设置画布（?settings=1 深链，
 *    承接端=MosWorkbench.tsx:85 现成）。数据早已在列表响应（label_studio.py:287-291），
 *    api/mos.ts MosLsProject 缺 has_controls 声明——本卡补齐。
 * 错误横幅 :75-79（评审 :50 同族条）：列表加载失败专属「连接不上标注引擎」+后端原文+
 *    「检查连接设置」按钮接 ⑥ 弹窗（重试走页头「刷新」，老版横幅只有这一个钮）；不再叠
 *    「标注项目加载失败」toast（老版 loadProjects catch 只置 error，横幅是唯一载体）；
 *    403 是中台通道权限（mos:access）不是引擎不可达，维持 REQ-UNIFY 三态「没有权限」不回归。
 *
 * BUG-PARITY-8-C（原卡收尾：统计卡④ / 血缘完整口径⑤ / 默认名⑨残段）：
 * ④ 六统计卡 :37-72,867-876——项目/任务/已标注/待标注/AI 自动标注/整体进度；汇总口径
 *    =**全量 projects**（Vue:869 直接 reduce projects.value，搜索/筛选中数值不动）；AI 卡可点
 *    →打开台账（:58-63 @click="openAi"），副行「N 框 · 已写入 N 条」；进度=Math.round 取整
 *    （:874）。ai-stats 随列表加载刷新（Vue loadProjects:936 并行 loadAi，读失败兜 {} :892）。
 * ⑤ 同步结果块 lastSync :178-190,987,1008——同步成功成块显示「已同步到平台数据集」+
 *    「新增 X · 更新 Y [· 清理旧版本 Z] · 数据集 {name} [· 已发布 N 行]」+「查看数据集 →」
 *    （/mos/datasets?ds=，与血缘列同通道）+「去训练 →」（/mos/jobs?method=sft&dataset=，
 *    WsDialogs.tsx:157,611 现成契约）；删除项目时块若指向该项目即清。后端 to-dataset 响应
 *    **不含 project_id**（label_studio.py:2584-2660）→ Vue:1008 的清理在原版是死条件，
 *    React 落 lastSync 时补记 project_id，让卡面「删已同步项目即清」真实成立（如实记账）。
 *    行内血缘未同步副文案补逐字「→ 还没同步到数据集，点右侧「同步」」（Vue:121-123；
 *    老口径优先，同 8-A 删除确认「数据集」措辞先例，不随 06c5baa 改名）。
 * ⑨ 默认名 :743-756,1151,239,1133——勾了样本、没手输过项目名（titleTouched）→ 自动填
 *    defaultTitle（混型/无映射=「标注项目-MMDD」）；用户一改名即不再覆盖；草稿恢复带 title
 *    视同已手输；openCreate 重置 titleTouched。（跨页草稿主链 ?create=1/takeCreateDraft/
 *    getPendingPick 已由 13-C 落地，本条只补默认名残段。）
 *
 * REQ-NIGHT-LEARN-1（学习，项目负责人信 ③/24④c）：本页行操作加「夜间学习」弹窗
 *  = 岗位配置入口（1 个标注项目=1 个岗位）。PRD 原文写「标注项目详情页」，但详情页画布是
 *  vendor LabelStudio 内嵌页（?settings=1，见上方 ⑧ 注），我方无法往 vendor 页注入控件，
 *  故落列表行操作弹窗、字段与语义等同（偏差已在 docs/学习-20261104.md §三 如实记账）。
 *  入口按权限点 mos:flywheel 裁剪（后端类级 @RequireAtom 同源，未新立点位）：没有该点位的人
 *  看不到钮，而不是看到钮再吃 403。存的是「夜里怎么学」——窗、岗位准入分、特征 SQL 与列映射，
 *  跑圈由 cc-ctrld 夜环 postlearn 负责，页面不代跑。
 */

const KIND_LABEL: Record<string, string> = { audio: '音频', video: '视频', image: '图片', text: '文本' }

/**
 * REQ-NIGHT-LEARN-1 岗位夜学配置默认值——窗 00:00–06:00、准入分 7.0，两处都与后端
 * V212 列默认值、cc-ctrld 夜环 DEF_WINDOW 同口径（改一边就会「页面上没配、夜里按另一套跑」）。
 */
const NIGHT_DEFAULTS: NightPost = {
  labelProjectId: 0, enabled: false, windowStart: '00:00', windowEnd: '06:00',
  evalMinScore: 7, featureSql: '', qColumn: '', aColumn: '', refColumn: '', maxRows: 200,
}

/** 后端回来的岗位行 → 表单态（null 归空串：Input 受控值给 null 会退化成非受控，改了看不见）。 */
function nightFormOf(p: NightPost | undefined, base: NightPost): NightPost {
  if (!p) return base
  return {
    ...base,
    ...p,
    labelProjectId: base.labelProjectId,
    enabled: !!p.enabled,
    evalMinScore: Number(p.evalMinScore ?? NIGHT_DEFAULTS.evalMinScore),
    maxRows: Number(p.maxRows ?? NIGHT_DEFAULTS.maxRows),
    featureSql: p.featureSql ?? '', qColumn: p.qColumn ?? '',
    aColumn: p.aColumn ?? '', refColumn: p.refColumn ?? '',
  }
}

/**
 * BUG-PARITY-8-C ⑨ 默认名（Vue defaultTitle:745-751 逐行）：勾了样本、没手输过项目名时
 * 自动填一个可改的名字，别让「下一步」无声地灰着（Vue:743 原注）。
 * 单一 kind 且在 KIND_LABEL 有映射 →「<类型>标注-MMDD」；混型/无映射 →「标注项目-MMDD」。
 */
function defaultTitle(list: MosMediaItem[]) {
  const kinds = [...new Set((list || []).map((m) => m.kind))]
  const k = kinds.length === 1 ? (KIND_LABEL[kinds[0]] || '') : ''
  const d = new Date()
  const stamp = String(d.getMonth() + 1).padStart(2, '0') + String(d.getDate()).padStart(2, '0')
  return (k ? k + '标注-' : '标注项目-') + stamp
}

/** 画布列的类型标签（老 CFG_MODES Vue:809-812 逐键；未知 mode 原样显示，兜底「文本」） */
const CFG_MODES: Record<string, string> = {
  text: '文本', audio: '音频', video: '视频', image: '图片',
  doc: '文档', timeseries: '时序',
}

/* BUG-PARITY-8-C ④ 统计卡样式常数（Vue .stat/.lb/.vl/.sb 的等价件；卡片描边清晰=本机口径）。 */
const statBox: CSSProperties = {
  border: '1px solid rgba(0,0,0,0.10)', borderRadius: 8, padding: '8px 14px',
  minWidth: 120, background: '#fff',
}
const lb: CSSProperties = { fontSize: 12, color: 'rgba(0,0,0,0.55)' }
const vl: CSSProperties = { fontSize: 20, fontWeight: 600, margin: '2px 0' }
const sb: CSSProperties = { fontSize: 12, color: 'rgba(0,0,0,0.45)' }
const em: CSSProperties = { fontSize: 12, fontStyle: 'normal', fontWeight: 400, marginLeft: 4, color: 'rgba(0,0,0,0.45)' }

export default function MosLabels() {
  const nav = useNavigate()
  const location = useLocation()
  const [projects, setProjects] = useState<MosLsProject[]>([])
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState<Error & { status?: number } | null>(null)
  const [showForm, setShowForm] = useState(false)
  const [creating, setCreating] = useState(false)
  // BUG-PARITY-7 同步链：并发闸是全局单值（Vue:975 原语义——同一时间只允许一个同步在跑）
  const [syncingId, setSyncingId] = useState<number | null>(null)
  const [syncLinks, setSyncLinks] = useState<Record<string, any>[]>([])
  const [syncLast, setSyncLast] = useState<Record<string, any>>({})
  const [dsNames, setDsNames] = useState<Record<string, string>>({})
  const [form] = Form.useForm<{ title: string }>()
  const loadedOnce = useRef(false)
  // BUG-PARITY-13-C 向导样本态（老 pickedMedia/tpl :1100 段）
  const [picked, setPicked] = useState<MosMediaItem[]>([])
  const [tpl, setTpl] = useState('custom')
  const openedWithPick = useRef(false)
  // REQ-UX-FLOW-1#5：空题项目（标题为空的测试残留）默认隐藏，可勾选显示；
  // 删除动作留人工确认——清理清单见 docs/integration/UX乱点收敛-存量数据清单-20261029.md，worker 不直删。
  const [showEmpty, setShowEmpty] = useState(false)
  // BUG-PARITY-8-A ①②：搜索词与筛选分段（值=老版 viewFilter 原语义 Vue:849-857）
  const [search, setSearch] = useState('')
  const [viewFilter, setViewFilter] = useState<'all' | 'workbench' | 'native' | 'todo'>('all')
  // 搜索/筛选变化回第 1 页（Vue usePager track :861-865）
  const [page, setPage] = useState(1)
  // BUG-PARITY-15 ①：每页条数=平台全局偏好（prefs.js:6-35 同键同值集；:148 写死 20+hideOnSinglePage 属回归）
  const [pageSize, setPageSize] = useGlobalPageSize()
  // BUG-PARITY-8-A ③：删除链（受控弹窗，文案逐字 Vue:545-561）
  const [deleteTarget, setDeleteTarget] = useState<MosLsProject | null>(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // ?pid= 深链待跳转值（列表首载后消费一次）
  const pendingPidRef = useRef<number | null>(null)
  // BUG-PARITY-8-B ⑥：连接设置（Vue:621 cfg reactive；GET 不含 token，token 只存本态、
  // 留空照原样提交=服务端「不改」；cfg.url 另供操作列 nativeCanvasUrl 落点）
  const [cfg, setCfg] = useState<MosLsConfig & { token: string }>({
    url: '', token: '', ml_url: '', token_set: false, connected: false,
  })
  const [connectOpen, setConnectOpen] = useState(false)
  const [savingCfg, setSavingCfg] = useState(false)
  // BUG-PARITY-8-B ⑦：AI 标注台账（弹窗打开时拉、页内「刷新」同 openAi 重拉；Vue:623-627,890-900）
  const [aiOpen, setAiOpen] = useState(false)
  const [aiStats, setAiStats] = useState<MosLsAiStats>({})
  const [aiRuns, setAiRuns] = useState<MosLsAiRun[]>([])
  // BUG-PARITY-8-C ⑤：同步结果块（Vue lastSync:636,987——口径=**本次 to-dataset 响应**，
  // 不依赖 sync-map 回读；块里 project_id 由 React 落库时补记，见文件头死条件记账）
  const [lastSync, setLastSync] = useState<Record<string, any> | null>(null)
  // BUG-PARITY-8-C ⑨：用户是否手动动过项目名（Vue titleTouched:744——改名即不再自动覆盖）
  const titleTouched = useRef(false)
  // REQ-NIGHT-LEARN-1：岗位=标注项目，这里只管「夜里怎么学」（窗/准入分/特征 SQL/列映射），
  // 跑圈归 cc-ctrld 夜环。入口按 mos:flywheel 裁剪=后端类级权限点同源，没点位的人看不到钮。
  const canNight = canAny('mos:flywheel')
  // 全租户岗位行（随列表一起拉，一次点清「谁在夜里学」；读失败不拦列表，见 loadNight）
  const [nightMap, setNightMap] = useState<Record<string, NightPost>>({})
  const [nightOpen, setNightOpen] = useState(false)
  const [nightTarget, setNightTarget] = useState<MosLsProject | null>(null)
  const [nightSaving, setNightSaving] = useState(false)
  const [nightForm, setNightForm] = useState<NightPost>(NIGHT_DEFAULTS)

  /** 岗位夜学配置随列表拉一次（点位在才拉；读不到不拦列表——夜环配置不是标注链路的前置）。 */
  const loadNight = useCallback(async () => {
    if (!canNight) return
    try {
      const posts = await listNightPosts()
      setNightMap(Object.fromEntries((posts || []).map((p) => [String(p.labelProjectId), p])))
    } catch { /* 静默：列表可用优先，弹窗按默认值填 */ }
  }, [canNight])

  /** 打开岗位配置弹窗即回显现值（nightMap 里按岗位号命中；没配过=与后端同口径的默认值）。 */
  const openNight = useCallback((r: MosLsProject) => {
    const base: NightPost = { ...NIGHT_DEFAULTS, labelProjectId: Number(r.id), labelProjectTitle: r.title }
    setNightTarget(r)
    setNightForm(nightFormOf(nightMap[String(r.id)], base))
    setNightOpen(true)
  }, [nightMap])

  const saveNight = useCallback(async () => {
    if (!nightTarget) return
    const id = Number(nightTarget.id)
    setNightSaving(true)
    try {
      const saved = await saveNightPost(id, { ...nightForm, labelProjectTitle: nightTarget.title })
      setNightMap((m) => ({ ...m, [String(id)]: saved }))
      setNightForm(nightFormOf(saved, { ...nightForm, labelProjectId: id }))
      message.success(saved.enabled
        ? `岗位 #${id} 夜间学习已开启（${saved.windowStart}–${saved.windowEnd}，准入分 ${saved.evalMinScore}）`
        : `岗位 #${id} 夜间学习已关闭`)
      setNightOpen(false)
    } catch (e: any) {
      // 窗/准入分/列映射非法=后端当场 400 报当前值；弹窗不关，原文显示（同 8-A 删除确认口径）
      message.error(e?.message || '夜间学习配置保存失败')
    } finally {
      setNightSaving(false)
    }
  }, [nightForm, nightTarget])

  const load = useCallback(async () => {
    if (!loadedOnce.current) setLoading(true)
    try {
      setProjects(await listLsProjects())
      setLoadError(null)
      // BUG-PARITY-8-C ④：Vue loadProjects:936 拉到项目后并行 loadAi——AI 统计卡随列表刷新；
      // 读失败照老 loadAi:892 兜 {}（不影响列表本身）。
      fetchAiStats(0, 30).then(setAiStats).catch(() => setAiStats({}))
    } catch (e: any) {
      // 加载失败不能画成「还没有项目」：用户会重复创建（同 MosEvaluations 的教训口径）。
      // BUG-PARITY-8-B：失败文案由专属横幅承接（Vue:75-79 只置 error、无 toast——横幅就是唯一载体），
      // 原先这里叠一条「标注项目加载失败」toast 属新版自造文案，撤掉。
      setLoadError(e)
    } finally {
      loadedOnce.current = true
      setLoading(false)
    }
  }, [])

  /** 项目→数据集血缘：sync-map + 数据集名（Vue loadLinks:907-929 容错口径——
   *  标注引擎离线/缺表不影响列表本身，只让血缘列空着）。 */
  const loadLinks = useCallback(async () => {
    try {
      const sm = await wsTransport.lsSyncMap()
      setSyncLinks(sm.items || [])
      setSyncLast(sm.last || {})
    } catch { /* 引擎离线：保留旧数据，不打断列表 */ }
    try {
      const ds = await wsTransport.datasets()
      setDsNames(Object.fromEntries((ds || []).map((d: Record<string, any>) => [d.id, d.name])))
    } catch { /* 只用于显示名字 */ }
  }, [])

  /** BUG-PARITY-8-B ⑥：引擎连接配置回显（Vue loadConfig:879-884——GET 不含 token，
   *  合并时保留本地已输入 token；读失败不影响列表，老版同款 ignore） */
  const loadConfig = useCallback(async () => {
    try {
      const c = await lsConfig()
      setCfg((prev) => ({ ...prev, ...c }))
    } catch { /* 配置读不到：弹窗打开是空的、原生标注落点回退默认 8080，都不拦列表 */ }
  }, [])

  // 老 onMounted:1147-1150：配置与项目列表互不依赖，并行走
  useEffect(() => { void load(); void loadLinks(); void loadConfig(); void loadNight() },
    [load, loadLinks, loadConfig, loadNight])

  const isEmptyTitle = (p: MosLsProject) => !(p.title || '').trim()
  const visible = useMemo(() => {
    // 过滤式逐字对齐 Vue:851-857：q 命中「title + ' #' + id」（纯数字搜到 #id）；
    // 空题隐藏（REQ-UX-FLOW-1#5）在搜索之前叠加，两闸共用一个 visible 链。
    const q = search.trim().toLowerCase()
    return projects.filter((p) => {
      if (!showEmpty && isEmptyTitle(p)) return false
      if (q && !((p.title || '') + ' #' + p.id).toLowerCase().includes(q)) return false
      if (viewFilter === 'workbench' && !p.workbench) return false
      if (viewFilter === 'native' && p.workbench) return false
      if (viewFilter === 'todo' && !(Number(p.unannotated) > 0)) return false
      return true
    })
  }, [projects, showEmpty, search, viewFilter])
  const hiddenCount = useMemo(
    () => (showEmpty ? 0 : projects.filter(isEmptyTitle).length),
    [projects, showEmpty],
  )
  useEffect(() => { setPage(1) }, [search, viewFilter, showEmpty])

  // BUG-PARITY-8-C ④ 六统计卡汇总（Vue totals:867-876 逐行）：口径=**全量 projects**——
  // Vue:869 直接 reduce projects.value，**不受搜索/筛选影响**（可见行变了数值也不动）。
  // progress=Math.round(annotated/total*100)，total=0 记 0（:874）。
  const totals = useMemo(() => {
    const t = { total: 0, annotated: 0, unannotated: 0, progress: 0 }
    for (const p of projects) {
      t.total += Number(p.task_number) || 0
      t.annotated += Number(p.annotated) || 0
      t.unannotated += Number(p.unannotated) || 0
    }
    t.progress = t.total ? Math.round((t.annotated / t.total) * 100) : 0
    return t
  }, [projects])

  const linkOf = (p: MosLsProject) =>
    syncLinks.find((x) => Number(x.project_id) === Number(p.id)) || null

  /** 同步已标数据到平台数据集（Vue syncProject:973-991 同款：并发闸→复用绑定回写→刷血缘） */
  const doSync = async (p: MosLsProject) => {
    if (syncingId) return
    setSyncingId(p.id)
    try {
      const link = linkOf(p)
      const r = await wsTransport.lsToDataset(p.id, {
        // 已绑定数据集就继续写回同一个（老版教训：只按名字找，改名就新建孤儿数据集）
        dataset_id: link ? link.dataset_id : '',
        name: '标注 · ' + p.title + ' (#' + p.id + ')',
        freeze: true,
      })
      // BUG-PARITY-8-C ⑤：Vue:987 先置 lastSync 再刷血缘（结果块口径=本次响应）。
      // project_id 由前端补记——后端响应没这个字段（label_studio.py:2584-2660），
      // 原版 Vue:1008 靠它做删除清理是死条件；补记后「删已同步项目即清」才真实成立。
      setLastSync(r ? { ...r, project_id: p.id } : r)
      // to-dataset 已重绑，必须立刻刷映射，血缘列才是「更新」不是「画一次就旧」
      await loadLinks()
      message.success('已同步 ' + (r?.inserted ?? 0) + ' 条到标注集'
        + (r?.removed ? '，清理旧版本 ' + r.removed + ' 条' : ''))
    } catch (e: any) {
      // 写链失败出后端原文：wsCall 已把 FastAPI {detail} 转进 e.message
      message.error(e?.message || '同步失败')
    } finally {
      setSyncingId(null)
    }
  }

  /** BUG-PARITY-8-A ③ 删除项目（Vue:994-1011）：确认弹窗→DELETE→成功「已删除项目 #id」
   *  重拉列表；失败出后端原文（引擎 404/409/写锁 503 等 detail 逐字）且弹窗不关（老 :1010 只 bad()）。 */
  const askDelete = (p: MosLsProject) => { setDeleteTarget(p); setDeleteOpen(true) }

  const doDelete = async () => {
    const p = deleteTarget
    if (!p) return
    setDeleting(true)
    try {
      await deleteLsProject(p.id)
      message.success('已删除项目 #' + p.id)
      setDeleteOpen(false)
      setDeleteTarget(null)
      // BUG-PARITY-8-C ⑤（Vue:1008）：结果块若指向刚删的项目即清——否则「查看数据集/去训练」
      // 会通向一个已经没有来源项目的孤儿块（lastSync.project_id 由 doSync 落库时补记）。
      if (lastSync && Number(lastSync.project_id) === Number(p.id)) setLastSync(null)
      await load()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    } finally {
      setDeleting(false)
    }
  }

  /** BUG-PARITY-8-B ⑥：保存并测试（Vue saveConfig:960-971 同款：POST→合并→两分支文案→
   *  成功后关窗、**两个分支都重拉列表**）。老版并行还调 loadMl()（弹窗在线/离线提示）——
   *  lsMl 端点不在本卡面声明内，见文件头记账。 */
  const doSaveConfig = async () => {
    setSavingCfg(true)
    try {
      const c = await saveLsConfig({
        url: cfg.url || '', token: cfg.token || '', ml_url: cfg.ml_url || '', project_id: null,
      })
      setCfg((prev) => ({ ...prev, ...c }))
      if (c.connected) { message.success('标注引擎已连接'); setConnectOpen(false) }
      else message.error('连接失败，检查地址和 Token')
      await load()
    } catch (e: any) {
      // 保存失败逐字回后端原文（call() 已把 FastAPI {detail} 转进 e.message）
      message.error(e?.message || '保存失败')
    } finally {
      setSavingCfg(false)
    }
  }

  /** BUG-PARITY-8-B ⑦：打开/刷新台账（Vue openAi:897-903 + loadAi:890-895——
   *  ai-stats 全局 30 天、ai-runs 全局最近 100 条；单端点失败按老版兜 {} / [] 不拦弹窗） */
  const openAi = async () => {
    setAiOpen(true)
    const [st, runs] = await Promise.all([
      fetchAiStats(0, 30).catch(() => ({} as MosLsAiStats)),
      fetchAiRuns(0, 100).then((r) => r.items || []).catch(() => [] as MosLsAiRun[]),
    ])
    setAiStats(st)
    setAiRuns(runs)
  }

  /** 台账「项目」列本地反查（Vue projectTitle:902-905 逐字：title + ' #' + pid，查无回 '#pid'） */
  const projectTitle = (pid?: number) => {
    const p = projects.find((x) => Number(x.id) === Number(pid))
    return p ? (p.title + ' #' + pid) : ('#' + pid)
  }

  /**
   * BUG-PARITY-8-B ⑧：未支持配置的落点（Vue nativeCanvasUrl:948-952 逐行——去尾斜杠 +
   * `://127.0.0.1` 改写 `://localhost`：**Label Studio 的登录 Cookie 绑在 localhost，
   * 127.0.0.1 打开会登不上**；cfg.url 缺时回退默认 8080）。
   */
  const nativeCanvasUrl = (p: MosLsProject) => {
    let base = (cfg.url || 'http://127.0.0.1:8080').replace(/\/$/, '')
    base = base.replace('://127.0.0.1', '://localhost')
    return base + '/projects/' + p.id + '/data'
  }

  // 样本类型派生（老 syncTplToMedia）：单一模态才给整套模板；mixed 只剩自定义，
  // 创建时直接拦下（老 callout :256-261「一个项目只能用一种画布」，拦下文案沿用 13-B）。
  const pickedKinds = [...new Set(picked.map((m) => m.kind))]
  const mixed = picked.length > 0 && pickedKinds.length > 1
  const tplOptions = !picked.length
    ? []
    : mixed
      ? [{ key: 'custom', ...TEMPLATES.custom }]
      : templatesFor(pickedKinds[0])

  // 换样本后当前模板不在可选项里 → 回第一项（老 syncTplToMedia 同款纠正）
  useEffect(() => {
    if (tplOptions.length && !tplOptions.some((o) => o.key === tpl)) setTpl(tplOptions[0].key)
  }, [tplOptions, tpl])

  const openCreate = () => {
    form.resetFields()
    setPicked([])
    setTpl('custom')
    // BUG-PARITY-8-C ⑨（Vue openCreate:1133）：每次打开向导重置「手输过名字」标记
    titleTouched.current = false
    setShowForm(true)
  }

  // 媒体库「加入新建项目」落地（老 openCreateWithPick :1148-1165）：
  // 先恢复离开前的项目名/模板草稿，再把 pendingPick 样本填进向导。
  const openCreateWithPick = () => {
    openCreate()
    const draft = takeCreateDraft()
    if (draft) {
      if (draft.title) {
        form.setFieldsValue({ title: draft.title })
        // BUG-PARITY-8-C ⑨（Vue:1151）：草稿恢复带项目名 **视同已手输**——
        // 后续再勾样本不许把用户离开前写的名字盖成默认名
        titleTouched.current = true
      }
      if (draft.tpl && TEMPLATES[draft.tpl]) setTpl(draft.tpl)
    }
    const pending = getPendingPick()
    if (pending.length) setPicked(pending)
  }

  // BUG-PARITY-8-C ⑨（Vue watch(pickedMedia):752-756）：勾了样本、项目名还空着、
  // 且没手输过 → 自动填默认名；用户一改名（包括清空）即不再覆盖（titleTouched）。
  useEffect(() => {
    if (!picked.length) return
    const cur = String(form.getFieldValue('title') || '')
    if (cur.trim() || titleTouched.current) return
    form.setFieldsValue({ title: defaultTitle(picked) })
  }, [picked, form])

  // 老 onMounted :1250-1256：?create=1 开向导并立即 replace 清 query（F5 不复活）；
  // ref 守卫挡 StrictMode 双调用（双调用之间 ref 保留，只消费一次 draft）。
  useEffect(() => {
    if (openedWithPick.current) return
    if (new URLSearchParams(location.search).get('create') === '1') {
      openedWithPick.current = true
      openCreateWithPick()
      nav('/mos/labels', { replace: true })
    }
  }, [location.search, nav])

  // BUG-PARITY-8-A · 落点接住：?q= 预填搜索框（老版靠搜索接住飞轮裸跳，现在由参数接住）；
  // ?pid= 深链直达，口径同 MosDatasets ?ds= 深链跟随（:203-209）——首载命中才
  // replace 跳工作台，未命中/加载失败留在列表不瞎跳。
  useEffect(() => {
    const sp = new URLSearchParams(location.search)
    const q = sp.get('q')
    if (q) setSearch(q)
    const pid = Number(sp.get('pid') || '')
    pendingPidRef.current = Number.isInteger(pid) && pid > 0 ? pid : null
  }, [location.search])

  useEffect(() => {
    const pid = pendingPidRef.current
    if (pid == null || loading || loadError || projects.length === 0) return
    pendingPidRef.current = null
    if (projects.some((p) => Number(p.id) === pid)) nav(`/mos/label-studio/${pid}`, { replace: true })
  }, [projects, loading, loadError, nav])

  // 「去媒体库挑」= 存草稿跳媒体页 pick 态（老版是页内嵌挑，中台两条路都给：
  // 媒体页勾完点「加入新建项目」原路带回；Assets.tsx 初始 view 认 ?view=media）
  const goPickMedia = () => {
    setCreateDraft({ title: String(form.getFieldValue('title') || '').trim(), tpl })
    setShowForm(false)
    nav('/assets?view=media&pick=1')
  }

  // BUG-PARITY-8-A ⑩：创建走 Form onFinish（校验通过才回调=Enter/确定同一入口；
  // 原 validateFields 裸 await 的 UnhandledRejection 一并消掉）。老版向导 Enter=下一步
  // （Vue:240），新版无向导步骤，Enter=直接提交。
  const doCreate = async (v: { title: string }) => {
    if (mixed) {
      message.error('只能选同一类型的音频 / 视频 / 图片 / 文本；文档请先「转可标注」')
      return
    }
    setCreating(true)
    try {
      let p
      if (picked.length) {
        // 老 createProject :1167-1204：样本随项目一起建任务（文本读正文），成功清 pendingPick
        const tasks = await buildMediaTasks(tpl, picked)
        p = await createLsProject({
          title: v.title.trim(),
          label_config: (TEMPLATES[tpl] || TEMPLATES.custom).config,
          tasks,
        })
        clearPendingPick()
        message.success('已创建项目 #' + p.id + (tasks.length ? '，' + tasks.length + ' 条任务' : ''))
      } else {
        p = await createLsProject({ title: v.title.trim() })
        message.success(`项目已创建（#${p.id}）`)
      }
      setShowForm(false)
      setPicked([])
      // 建完直接进工作台（vendor LabelStudio.vue:1198 同款动线），空项目也能先进去导任务
      nav(`/mos/label-studio/${p.id}`)
    } catch (e: any) {
      message.error(e?.message || '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const columns: TableProps<MosLsProject>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 72 },
    {
      title: '项目名',
      dataIndex: 'title',
      render: (t: string, r) => (
        <Space size={4}>
          {/* BUG-PARITY-15 ⑤：项目名键盘可达（老版 :104-105 是真 button；无 href 的 Link 不进 Tab 序） */}
          <Typography.Link
            {...kbActivate(() => nav(`/mos/label-studio/${r.id}`))}
            onClick={() => nav(`/mos/label-studio/${r.id}`)}
          >{t || `项目 #${r.id}`}</Typography.Link>
          {!t?.trim() ? <Tag title="标题为空的测试残留，默认隐藏；删除需人工确认">空题</Tag> : null}
        </Space>
      ),
    },
    { title: '任务数', dataIndex: 'task_number', width: 90, sorter: (a, b) => a.task_number - b.task_number },
    {
      title: '标注进度',
      dataIndex: 'progress',
      width: 180,
      render: (p: number, r) => (
        <Space size={8}>
          <Progress percent={p} size="small" style={{ width: 96 }} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.annotated}/{r.task_number}</Typography.Text>
        </Space>
      ),
      sorter: (a, b) => a.progress - b.progress,
    },
    {
      // BUG-PARITY-8-B ⑧（Vue:126-131 逐字）：一个布尔混同三态——回搬「类型标签 + 三态副行」。
      // 判定序=老版真值序：workbench → has_controls → 无控件；tooltip 三串逐字。
      title: '画布',
      key: 'canvas',
      width: 130,
      render: (_: unknown, r) => (
        <div data-testid={`canvas-${r.id}`} style={{ lineHeight: 1.6 }}>
          <Tag>{CFG_MODES[r.mode || ''] || r.mode || '文本'}</Tag>
          <div
            data-testid={`canvas-sub-${r.id}`}
            title={r.workbench ? '平台内置画布' : (r.has_controls ? '自定义控件，平台画布暂不支持' : 'label_config 里没有标注控件')}
            style={{ fontSize: 12, color: 'rgba(0,0,0,0.55)' }}
          >
            {r.workbench ? '平台画布' : (r.has_controls ? '未支持' : '无标注控件')}
          </div>
        </div>
      ),
    },
    {
      // BUG-PARITY-7 最小血缘（数据集名+上次同步时间）；可点跳转等完整口径归 PARITY-8⑤
      title: '血缘',
      key: 'lineage',
      width: 200,
      render: (_: unknown, r) => {
        const link = linkOf(r)
        if (link?.dataset_id) {
          const at = (syncLast[String(r.id)] || {}).at
          const dsName = dsNames[link.dataset_id] || String(link.dataset_id)
          return (
            <div data-testid={`lineage-${r.id}`} style={{ lineHeight: 1.5 }}>
              <Typography.Text ellipsis={{ tooltip: dsName }} style={{ maxWidth: 180 }}>
                → <Typography.Link
                  title="到训练集页查看这个标注集（旧称「数据集」，同一实体）"
                  onClick={() => nav('/mos/datasets?ds=' + encodeURIComponent(String(link.dataset_id)))}
                >{dsName}</Typography.Link>
              </Typography.Text>
              <br />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {at ? String(at).slice(5, 16) + ' 已同步' : '已绑定标注集'}
              </Typography.Text>
            </div>
          )
        }
        // BUG-PARITY-8-C ⑤：未绑定且有已标量时的副文案逐字回搬 Vue:121-123（老口径优先，
        // 「数据集」不随 06c5baa 改名——同 8-A 删除确认豁免句先例）
        return (
          <Typography.Text type="secondary" data-testid={`lineage-${r.id}`}>
            {r.annotated ? '→ 还没同步到数据集，点右侧「同步」' : '—'}
          </Typography.Text>
        )
      },
    },
    { title: '创建时间', dataIndex: 'created_at', width: 170, render: (t?: string) => t || '—' },
    // REQ-UX-FLOW-1#5：清理单口径「项目+题目数+最后活动时间」
    {
      title: '最后活动',
      dataIndex: 'last_activity',
      width: 170,
      render: (t?: string) => (t ? String(t).slice(0, 19).replace('T', ' ') : '—'),
    },
    {
      title: '操作',
      key: 'ops',
      width: 320,
      render: (_: unknown, r) => (
        <Space size={6}>
          {/* BUG-PARITY-8-B ⑧ 操作列三分（Vue:143-148）：平台画布→进工作台；未支持→原生标注
              ↗（新窗口，别让人进了工作台才发现渲染不全——Vue:145 原注）；无控件→设置画布
              （?settings=1 深链，承接端 MosWorkbench.tsx:85 现成）。钮面沿用新版「进入工作台」。 */}
          {r.workbench ? (
            <Button size="small" type="primary" ghost onClick={() => nav(`/mos/label-studio/${r.id}`)}>进入工作台</Button>
          ) : r.has_controls ? (
            <Button size="small" data-testid={`native-${r.id}`} href={nativeCanvasUrl(r)}
              target="_blank" rel="noopener">原生标注 ↗</Button>
          ) : (
            <Button size="small" data-testid={`settings-${r.id}`}
              onClick={() => nav(`/mos/label-studio/${r.id}?settings=1`)}>设置画布</Button>
          )}
          <Button
            size="small"
            disabled={!!syncingId || !r.annotated}
            loading={syncingId === r.id}
            title={r.annotated ? '同步已标注数据到平台标注集并冻结' : '还没有已标注任务，先继续标注'}
            onClick={() => void doSync(r)}
          >{syncingId === r.id ? '同步中…' : '同步'}</Button>
          {/* REQ-NIGHT-LEARN-1：岗位夜学配置入口（按 mos:flywheel 裁剪，没点位不出钮，
              不让人点了再吃 403）；已开启的岗位在钮面上带窗，列表一眼能数清谁在夜里学 */}
          {canNight && (
            <Button
              size="small"
              data-testid={`night-learn-btn-${r.id}`}
              title={nightMap[String(r.id)]?.enabled
                ? `已在夜间学习：${nightMap[String(r.id)].windowStart}–${nightMap[String(r.id)].windowEnd}，准入分 ${nightMap[String(r.id)].evalMinScore}`
                : '夜里自动学一圈（逐岗开圈 + 晨报）'}
              onClick={() => openNight(r)}
            >{nightMap[String(r.id)]?.enabled
              ? `夜间 ${nightMap[String(r.id)].windowStart}–${nightMap[String(r.id)].windowEnd}`
              : '夜间学习'}</Button>
          )}
          {/* BUG-PARITY-8-A ③：行内 danger「删除」（老版 :143-155 钮序=操作列末位），
              点击只开确认弹窗，真正的 DELETE 在弹窗「确认删除」之后 */}
          <Button
            size="small"
            danger
            title="删除项目"
            data-testid={`del-${r.id}`}
            onClick={() => askDelete(r)}
          >删除</Button>
        </Space>
      ),
    },
  ]

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <Card size="small">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
          <Space>
            <TagsOutlined style={{ fontSize: 18, color: 'var(--dw-primary)' }} />
            <Typography.Text strong style={{ fontSize: 16 }}>标注项目</Typography.Text>
            <Typography.Text type="secondary">给数据打标 → 攒成标注集 → 发布投训练，闭环从这里开始</Typography.Text>
          </Space>
          <Space size={10} wrap>
            {/* BUG-PARITY-8-B ⑥⑦ 入口（Vue:15-16 老工具栏同位=搜索框之前；Settings/System 页
                零入口是对拍复核结论，本列表工具栏即老版唯一入口） */}
            <Button size="small" data-testid="ls-ai-btn" onClick={() => void openAi()}>AI 标注记录</Button>
            <Button size="small" data-testid="ls-connect-btn" onClick={() => setConnectOpen(true)}>连接设置</Button>
            {/* BUG-PARITY-8-A ①②：搜索 + 筛选分段（placeholder/段名列=老版 Vue:18-24 逐字；
                老工具栏在行尾，新版并入页头操作区，语义不变） */}
            <Input
              allowClear
              data-testid="ls-search"
              placeholder="搜索项目名 / ID…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ width: 220 }}
            />
            <Segmented
              data-testid="ls-filter"
              options={[
                { label: '全部', value: 'all' },
                { label: '工作台', value: 'workbench' },
                { label: '未支持', value: 'native' },
                { label: '未完成', value: 'todo' },
              ]}
              value={viewFilter}
              onChange={(v) => setViewFilter(v as typeof viewFilter)}
            />
            {/* REQ-UX-FLOW-1#5：默认隐藏空题项目（标题为空的测试残留），开关可查看 */}
            <Checkbox checked={showEmpty} onChange={(e) => setShowEmpty(e.target.checked)}>显示空题项目</Checkbox>
            {hiddenCount > 0 && !showEmpty ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                已隐藏 {hiddenCount} 个空题项目；删除见清理清单，需人工确认
              </Typography.Text>
            ) : null}
            <Button icon={<ReloadOutlined />} onClick={() => void load()} loading={loading && projects.length > 0}>刷新</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建标注项目</Button>
          </Space>
        </div>
      </Card>

      {/* BUG-PARITY-8-C ④ 六统计卡（Vue:37-72）：数值口径=全量 totals（搜索/筛选中不动）；
          AI 卡整卡可点=老卡 @click="openAi"（:58-63）；进度条宽=totals.progress%（Math.round）。 */}
      <div data-testid="ls-stats" style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <div data-testid="stat-projects" style={{ ...statBox }}>
          <div style={lb}>项目</div>
          <div style={vl}>{projects.length}</div>
          <div style={sb}>标注项目</div>
        </div>
        <div data-testid="stat-tasks" style={{ ...statBox }}>
          <div style={lb}>任务</div>
          <div style={vl}>{totals.total}</div>
          <div style={sb}>全部任务</div>
        </div>
        <div data-testid="stat-annotated" style={{ ...statBox }}>
          <div style={lb}>已标注</div>
          <div style={{ ...vl, color: '#0f7b5f' }}>{totals.annotated}</div>
          <div style={sb}>可同步进数据集</div>
        </div>
        <div data-testid="stat-unannotated" style={{ ...statBox }}>
          <div style={lb}>待标注</div>
          <div style={{ ...vl, color: totals.unannotated ? '#ad6800' : undefined }}>{totals.unannotated}</div>
          <div style={sb}>继续推进</div>
        </div>
        <div data-testid="stat-ai" title="点击查看 AI 标注明细"
          style={{ ...statBox, cursor: 'pointer' }} onClick={() => void openAi()}>
          <div style={lb}>AI 自动标注</div>
          <div style={vl}>{aiStats.tasks || 0}<em style={em}>任务</em></div>
          <div style={sb}>{aiStats.boxes || 0} 框 · 已写入 {aiStats.adopted || 0} 条</div>
        </div>
        <div data-testid="stat-progress" style={{ ...statBox, minWidth: 200, flex: '1 1 200px', maxWidth: 340 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
            <span style={lb}>整体进度</span>
            <span data-testid="stat-progress-sub" style={sb}>{totals.annotated} / {totals.total}</span>
          </div>
          <div data-testid="stat-progress-pct" style={vl}>{totals.progress}<em style={em}>%</em></div>
          <Progress percent={totals.progress} showInfo={false} size="small" />
        </div>
      </div>

      {/* 「显示 X / Y 个」计数（Vue:85 逐字；X=搜索/筛选后的可见数，Y=全量） */}
      {!loadError && projects.length > 0 ? (
        <Typography.Text type="secondary" data-testid="ls-count" style={{ fontSize: 12 }}>
          显示 {visible.length} / {projects.length} 个
        </Typography.Text>
      ) : null}

      {/* 三态铺满（v437 / REQ-UNIFY-2 checklist）：错误 > 首屏加载 > 空态 > 内容 */}
      {loadError ? (
        loadError.status === 403 ? (
          // 403=中台通道权限（mos:access），不是「引擎连不上」：维持 REQ-UNIFY 三态口径，
          // 不吃老版横幅文案（卡面明令不回归）
          <ErrorState
            title="没有权限"
            message="当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。"
            onRetry={() => void load()}
          />
        ) : (
          // BUG-PARITY-8-B 错误横幅（Vue:75-79 逐字）：mos 通道加载失败=引擎侧问题的专属文案，
          // 后端原文照排；「检查连接设置」直接开 ⑥ 弹窗。老版横幅上没有重试钮——重试走页头「刷新」。
          <Card size="small" data-testid="ls-err-banner">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-start' }}>
              <Typography.Text strong type="danger">连接不上标注引擎</Typography.Text>
              <Typography.Text type="secondary">{loadError.message || '未知错误'}</Typography.Text>
              <Button size="small" data-testid="ls-check-connect" onClick={() => setConnectOpen(true)}>检查连接设置</Button>
            </div>
          </Card>
        )
      ) : loading && projects.length === 0 ? (
        <LoadingState rows={6} />
      ) : visible.length === 0 ? (
        (search.trim() || viewFilter !== 'all') && projects.length > 0 ? (
          // Vue:170-172：搜索/筛选无结果专属空态（与「只剩空题」「还没有」区分开）
          <EmptyState title="没有匹配项目" hint="换个关键词或筛选条件" />
        ) : projects.length > 0 ? (
          <EmptyState
            title="当前只剩空题项目"
            hint="标题为空的测试残留已默认隐藏；勾选「显示空题项目」可查看。删除前请按清理清单人工确认。"
            action={<Button onClick={() => setShowEmpty(true)}>显示空题项目</Button>}
          />
        ) : (
          <EmptyState
            title="还没有标注项目"
            hint="新建一个项目就能开始标注；任务可以从媒体库或标注集导入（工作台内操作）。"
            action={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建标注项目</Button>}
          />
        )
      ) : (
        <Table<MosLsProject>
          rowKey="id"
          size="middle"
          className="dw-sticky-pager"
          columns={columns}
          dataSource={visible}
          pagination={listPagination({
            current: page, total: visible.length, size: pageSize,
            onChange: (p, s) => { setPageSize(s); setPage(p) },
          })}
        />
      )}

      {/* BUG-PARITY-8-C ⑤ 同步结果块（Vue:178-190 逐句）：文案「已同步到平台数据集」；
          「新增 X · 更新 Y」恒显（updated 缺省按 0，:180），「清理旧版本 Z」「已发布 N 行」
          是条件段（:181/:184 template v-if——removed/freeze 为假时整段不出现）；数据集名走
          dsName 反查（查无回 id，同血缘列）。两个去向=「查看数据集 →」/mos/datasets?ds=
          （与血缘列同参口径）、「去训练 →」/mos/jobs?method=sft&dataset=（WsDialogs.tsx:157,611
          现成契约，MosJobs.tsx:32 认参）。删除指向的项目后整块清除（doDelete，Vue:1008）。 */}
      {lastSync ? (
        <Card size="small" data-testid="ls-lastsync">
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <Typography.Text strong type="success">已同步到平台数据集</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="lastsync-detail">
              新增 {lastSync.inserted} · 更新 {lastSync.updated || 0}
              {lastSync.removed ? ` · 清理旧版本 ${lastSync.removed}` : ''} · 数据集{' '}
              <Typography.Link
                title={String(lastSync.dataset_id)}
                onClick={() => nav('/mos/datasets?ds=' + encodeURIComponent(String(lastSync.dataset_id)))}
              >{dsNames[lastSync.dataset_id] || String(lastSync.dataset_id)}</Typography.Link>
              {lastSync.freeze ? ` · 已发布 ${lastSync.freeze.lines} 行` : ''}
            </Typography.Text>
            <div style={{ flex: 1 }} />
            <Button size="small" data-testid="lastsync-view"
              onClick={() => nav('/mos/datasets?ds=' + encodeURIComponent(String(lastSync.dataset_id)))}
            >查看数据集 →</Button>
            <Button size="small" data-testid="lastsync-jobs"
              onClick={() => nav('/mos/jobs?method=sft&dataset=' + encodeURIComponent(String(lastSync.dataset_id)))}
            >去训练 →</Button>
          </div>
        </Card>
      ) : null}

      <Modal
        title="新建标注项目"
        open={showForm}
        onOk={() => form.submit()}
        onCancel={() => setShowForm(false)}
        okText={picked.length ? '按样本创建并进入工作台' : '创建并进入工作台'}
        cancelText="取消"
        confirmLoading={creating}
        destroyOnClose
        width={820}
      >
        {/* BUG-PARITY-8-C ⑨（Vue:239 @input="titleTouched = true"）：项目名一改即记「手输过」
            （onValuesChange=Form 官方通道，含清空；与默认名自动填写互不触发） */}
        <Form form={form} layout="vertical"
          onValuesChange={(chg) => { if ('title' in chg) titleTouched.current = true }}
          onFinish={(v) => void doCreate(v)}>
          <Form.Item
            label="项目名"
            name="title"
            rules={[{ required: true, whitespace: true, message: '请输入项目名' }]}
            extra="画布与标签使用平台默认配置；需要定制标签结构可在创建后于工作台调整。"
          >
            <Input placeholder="例如：商品图缺陷标注-10月" autoFocus maxLength={80} />
          </Form.Item>
        </Form>

        {/* 样本（老 pick-block :244-252）：内嵌目录树选择器，勾文件夹=整夹递归选中 */}
        <div data-testid="wizard-pick">
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 4 }}>
            <b>样本</b>
            <span data-testid="wizard-picked-count" style={{ fontSize: 12, color: picked.length ? '#0f7b5f' : '#999' }}>
              {picked.length ? `已选 ${picked.length} 个样本` : '还没选样本'}
            </span>
            <span style={{ flex: 1 }} />
            <Button size="small" data-testid="wizard-go-media" onClick={goPickMedia}>去媒体库挑</Button>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
            勾文件夹前面的复选框 = 选中整个文件夹（含子目录）；也可以进目录挑单个文件，支持跨目录多选。
          </Typography.Text>
          <MosMediaPicker value={picked} onChange={setPicked} />
          {tplOptions.length > 0 && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 13 }}>标注方式</span>
              <Select
                data-testid="wizard-tpl"
                style={{ minWidth: 280 }}
                value={tpl}
                onChange={(v) => setTpl(String(v))}
                options={tplOptions.map((o) => ({ value: o.key, label: o.label }))}
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {(TEMPLATES[tpl] || TEMPLATES.custom).hint}
              </Typography.Text>
            </div>
          )}
          {mixed && (
            <div data-testid="wizard-mixed" style={{ marginTop: 8 }}>
              <Typography.Text type="danger">
                选中的文件类型不一致（{pickedKinds.map((k) => KIND_LABEL[k] || '其他').join(' / ')}）——
                一个项目只能用一种画布，请只保留一种类型。
              </Typography.Text>
            </div>
          )}
        </div>
      </Modal>

      {/* BUG-PARITY-8-A ③：删除确认弹窗——文案逐字照搬老版 Vue:545-561（计数=该项目实时行值） */}
      <Modal
        title="删除标注项目"
        open={deleteOpen}
        onCancel={() => setDeleteOpen(false)}
        width={520}
        footer={[
          <Button key="cancel" onClick={() => setDeleteOpen(false)}>取消</Button>,
          <Button key="del" danger disabled={deleting} data-testid="del-confirm" onClick={() => void doDelete()}>
            {deleting ? '删除中…' : '确认删除'}
          </Button>,
        ]}
      >
        {deleteTarget ? (
          <div>
            <p style={{ margin: '0 0 8px' }}>
              确定删除 <b>{deleteTarget.title || `项目 #${deleteTarget.id}`}</b>（#{deleteTarget.id}）？
            </p>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              会同时删除标注引擎里的 {deleteTarget.task_number} 条任务和 {deleteTarget.annotated} 条标注，且不可恢复。
              已经同步出来的平台数据集不受影响。
            </Typography.Text>
          </div>
        ) : null}
      </Modal>

      {/* BUG-PARITY-8-B ⑥：连接设置弹窗（Vue:193-227——三字段标签/placeholder、callout、
          页脚钮全部逐字；宽 620 同老版）。Token 只进请求体不进回显（GET 无 token、
          密码型输入），留空提交=服务端保持原值（label_studio.py:238）。 */}
      <Modal
        title="连接设置"
        open={connectOpen}
        onCancel={() => setConnectOpen(false)}
        width={620}
        footer={[
          <Button key="cancel" onClick={() => setConnectOpen(false)}>取消</Button>,
          <Button key="save" type="primary" disabled={savingCfg} data-testid="cfg-save"
            onClick={() => void doSaveConfig()}
          >{savingCfg ? '测试中…' : '保存并测试'}</Button>,
        ]}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <div style={{ marginBottom: 4 }}>底层标注引擎地址（平台自动调用，一般不用改）</div>
            <Input data-testid="cfg-url" value={cfg.url || ''} placeholder="http://127.0.0.1:8080"
              onChange={(e) => setCfg({ ...cfg, url: e.target.value })} />
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>API Token</div>
            <Input.Password data-testid="cfg-token" value={cfg.token}
              placeholder={cfg.token_set ? '已配置（留空不修改）' : 'Token ...'}
              onChange={(e) => setCfg({ ...cfg, token: e.target.value })} />
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>预标注服务（ML Backend）</div>
            <Input data-testid="cfg-ml" value={cfg.ml_url || ''} placeholder="http://127.0.0.1:9090"
              onChange={(e) => setCfg({ ...cfg, ml_url: e.target.value })} />
            {/* 老版此下有「预标注服务 在线/离线」提示（Vue:208-211，依赖 lsMl 端点）——
                不在本卡面声明端点内，见文件头记账 */}
          </div>
          <div data-testid="cfg-callout" style={{ border: '1px solid rgba(0,0,0,0.08)', borderRadius: 8, padding: '10px 12px', background: 'rgba(0,0,0,0.02)' }}>
            <b>平台是唯一入口</b>
            <p style={{ margin: '4px 0 0', fontSize: 12, color: 'rgba(0,0,0,0.65)' }}>
              项目、任务、标注回写、AI 预标注、同步数据集都在平台内完成；底层引擎只负责存储，
              地址和 Token 仅由平台服务端使用，浏览器不直接调用。
              只有平台不认识的控件（如 ParagraphLabels、Ranker 或 per-region 表单）
              才会回退原生画布。
            </p>
          </div>
        </div>
      </Modal>

      {/* BUG-PARITY-8-B ⑦：AI 自动标注记录（Vue:564-601——chips/说明/表头/二态/空态逐字；
          宽 820、页脚只有「关闭」同老版）。统计口径：全局（pid 省略）、最近 30 天。 */}
      <Modal
        title="AI 自动标注记录"
        open={aiOpen}
        onCancel={() => setAiOpen(false)}
        width={820}
        footer={[<Button key="close" onClick={() => setAiOpen(false)}>关闭</Button>]}
      >
        <Space size={8} wrap style={{ marginBottom: 10 }} data-testid="ai-chips">
          <Tag>生成 {aiStats.runs || 0} 次</Tag>
          <Tag>覆盖 {aiStats.tasks || 0} 个任务</Tag>
          <Tag>{aiStats.boxes || 0} 个框</Tag>
          <Tag>{aiStats.results || 0} 条结果</Tag>
          <Tag color="green">已写成标注 {aiStats.adopted || 0} 条</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>最近：{aiStats.last_at || '—'}</Typography.Text>
          <Button size="small" data-testid="ai-refresh" onClick={() => void openAi()}>刷新</Button>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 8 }} data-testid="ai-hint">
          每次「AI 智能标注」直接写成标注，记一行台账（不再有「采用」这一步）。
          统计范围：最近 30 天。
        </Typography.Text>
        <div style={{ maxHeight: 420, overflow: 'auto' }}>
          <Table<MosLsAiRun>
            rowKey="id"
            size="small"
            pagination={false}
            dataSource={aiRuns}
            locale={{ emptyText: '还没有 AI 标注记录' }}
            columns={[
              { title: '时间', dataIndex: 'created_at', width: 150 },
              {
                title: '项目',
                key: 'project',
                render: (_: unknown, r) => (
                  <span title={projectTitle(r.project_id)}>{projectTitle(r.project_id)}</span>
                ),
              },
              { title: '任务', dataIndex: 'task_id', width: 80 },
              {
                title: '引擎',
                key: 'engine',
                render: (_: unknown, r) => (
                  <>{r.engine} <Typography.Text type="secondary">{r.model_version}</Typography.Text></>
                ),
              },
              { title: '框', dataIndex: 'boxes', width: 60 },
              { title: '结果', dataIndex: 'results', width: 60 },
              {
                title: '写入标注',
                key: 'adopted',
                width: 90,
                render: (_: unknown, r) => (
                  <Typography.Text type={r.adopted ? 'success' : 'secondary'}>
                    {r.adopted ? '已写入' : '未写入'}
                  </Typography.Text>
                ),
              },
            ]}
          />
        </div>
      </Modal>

      {/* REQ-NIGHT-LEARN-1：岗位夜学配置弹窗（=PRD 要的「夜间学习开关」入口，落点偏差见文件头注）。
          这里存的是「夜里怎么学」（窗/准入分/特征 SQL/列映射），跑圈与晨报归 cc-ctrld 夜环 postlearn，
          本页不代跑圈——点保存只写岗位行，夜环下一拍自取。 */}
      <Modal
        title={`夜间学习${nightTarget ? ` · ${nightTarget.title || `项目 #${nightTarget.id}`}` : ''}`}
        open={nightOpen}
        onCancel={() => setNightOpen(false)}
        width={660}
        footer={[
          <Button key="cancel" onClick={() => setNightOpen(false)}>取消</Button>,
          <Button key="save" type="primary" loading={nightSaving} onClick={() => void saveNight()}>
            {nightSaving ? '保存中…' : '保存配置'}
          </Button>,
        ]}
      >
        <div data-testid="night-learn-modal" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <Alert
            type="info"
            showIcon
            data-testid="night-learn-callout"
            message="夜里自动学一圈，早上看晨报复核"
            description={<>
              到点自动开圈（选表→标注→训练→评测→部署→badcase→回环），标注完成闸夜间直接放行；
              <b>评测分低于岗位准入线时不放行</b>，停在评测闸等晨复核——全自动不等于无条件放行。
            </>}
          />
          <Space size={12} wrap align="center" data-testid="night-learn-enabled-row">
            <Typography.Text>夜间学习开关</Typography.Text>
            <Switch
              data-testid="night-learn-enabled"
              checked={!!nightForm.enabled}
              onChange={(v) => setNightForm((f) => ({ ...f, enabled: v }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>关掉后夜环不再为该岗开圈</Typography.Text>
          </Space>
          <Space size={12} wrap align="center">
            <Typography.Text>夜间窗</Typography.Text>
            <Input
              data-testid="night-learn-window-start"
              style={{ width: 92 }}
              value={nightForm.windowStart}
              placeholder="00:00"
              onChange={(e) => setNightForm((f) => ({ ...f, windowStart: e.target.value }))}
            />
            <Typography.Text type="secondary">至</Typography.Text>
            <Input
              data-testid="night-learn-window-end"
              style={{ width: 92 }}
              value={nightForm.windowEnd}
              placeholder="06:00"
              onChange={(e) => setNightForm((f) => ({ ...f, windowEnd: e.target.value }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>HH:MM；止早于起=跨零点同一夜</Typography.Text>
          </Space>
          <Space size={12} wrap align="center">
            <Typography.Text>评测准入分</Typography.Text>
            <span data-testid="night-learn-min-score">
              <InputNumber
                min={0}
                max={10}
                step={0.1}
                value={nightForm.evalMinScore}
                onChange={(v) => setNightForm((f) => ({ ...f, evalMinScore: Number(v ?? NIGHT_DEFAULTS.evalMinScore) }))}
              />
            </span>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>0–10，缺省 7.0</Typography.Text>
            <Typography.Text>单次取数上限</Typography.Text>
            <span data-testid="night-learn-max-rows">
              <InputNumber
                min={1}
                max={500}
                step={10}
                value={nightForm.maxRows}
                onChange={(v) => setNightForm((f) => ({ ...f, maxRows: Number(v ?? NIGHT_DEFAULTS.maxRows) }))}
              />
            </span>
          </Space>
          <div>
            <Typography.Text style={{ fontSize: 12 }}>特征数据 SQL（选表入环）</Typography.Text>
            <Input.TextArea
              data-testid="night-learn-feature-sql"
              rows={3}
              value={nightForm.featureSql ?? ''}
              placeholder="select question, answer from demo.xxx where labeled_at >= ..."
              onChange={(e) => setNightForm((f) => ({ ...f, featureSql: e.target.value }))}
            />
          </div>
          <Space size={12} wrap>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>问题列 qColumn</Typography.Text>
              <Input
                data-testid="night-learn-q-column"
                style={{ width: 160 }}
                value={nightForm.qColumn ?? ''}
                placeholder="question"
                onChange={(e) => setNightForm((f) => ({ ...f, qColumn: e.target.value }))}
              />
            </div>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>答案列 aColumn</Typography.Text>
              <Input
                data-testid="night-learn-a-column"
                style={{ width: 160 }}
                value={nightForm.aColumn ?? ''}
                placeholder="answer"
                onChange={(e) => setNightForm((f) => ({ ...f, aColumn: e.target.value }))}
              />
            </div>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>引用列 refColumn（可空）</Typography.Text>
              <Input
                data-testid="night-learn-ref-column"
                style={{ width: 160 }}
                value={nightForm.refColumn ?? ''}
                onChange={(e) => setNightForm((f) => ({ ...f, refColumn: e.target.value }))}
              />
            </div>
          </Space>
          <Space size={12} wrap align="center">
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>平台项目号（Doris 路由，可空=沿用默认）</Typography.Text>
            <span data-testid="night-learn-platform-project">
              <InputNumber
                min={1}
                style={{ width: 140 }}
                value={nightForm.platformProjectId ?? null}
                onChange={(v) => setNightForm((f) => ({ ...f, platformProjectId: v == null ? null : Number(v) }))}
              />
            </span>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="night-learn-footnote">
            保存只写岗位配置，圈由夜环下一拍自取；窗/准入分/列映射不合规会当场 400 报当前值
            （夜里才发现配错=白跑一夜，晨报还会多记一条 CONFIG_INVALID 假异常）。
          </Typography.Text>
        </div>
      </Modal>
    </div>
  )
}
