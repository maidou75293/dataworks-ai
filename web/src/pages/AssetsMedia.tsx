import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import {
  Alert, Button, Card, Checkbox, Dropdown, Input, Modal, Pagination, Segmented, Select, Space, Spin,
  Tag, Typography, message,
} from 'antd'
import type { MenuProps } from 'antd'
import {
  AppstoreOutlined, AudioOutlined, FileTextOutlined, FolderOutlined,
  PictureOutlined, VideoCameraOutlined,
} from '@ant-design/icons'
import {
  createLsProject, getSystemInfo, listAllMedia, listMedia, mediaCreateFolder, mediaDelete, mediaDeleteFolder,
  mediaDirs, mediaRender, mediaRenameFolder, mediaSubtree, mediaTree, mosMediaThumbUrl, mosMediaUrl, uploadMedia,
} from '../api/mos'
import type {
  MosMediaFolder, MosMediaItem, MosMediaList, MosMediaStoreInfo, MosMediaTree, MosRenderResult,
} from '../api/mos'
import { setPendingPick } from '../mediaPick'
import { TEMPLATES, templatesFor } from '../annotationTemplates'
import { buildMediaTasks } from '../mediaTasks'
import { sanitizeHtml } from '../components/ws/canvas/sanitize'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import { listPagination, useGlobalPageSize } from '../utils/listPrefs'

/**
 * 媒体库面板（Assets 页「媒体库」标签）——REQ-UNIFY-7。
 *
 * 对照 mos/platform/frontend/src/views/Media.vue 的浏览面（列表/类型过滤/搜索/预览/上传），
 * 数据走 /api/mos 通道的 media API（api/mos.ts media 组；端点对照 mos media.py：
 * 列表 :745、上传 :659、缩略图 :920）。Vue 原媒体页保留不删（退役归 RELCLEAN）。
 *
 * 口径要点（都有 mos 侧出处）：
 * - 类型计数 counts 是**全库**口径、不随 kind/q 过滤变化（media.py:745 注释）；页脚总数用
 *   过滤后的 total，另显 all_total，避免「筛完以为全库就这么多」。
 * - 搜索 q 是后端按路径小写包含匹配（media.py:757），回车/清空才发请求，不做逐键请求。
 * - 预览（图/音/视频）走静态读穿 /media/**（main.py:169，支持 Range 可拖动播放），
 *   地址由 mosMediaUrl(item.path) 通道化；**不用 item.url**——那是上游相对地址、缺通道前缀。
 * - 上传即 mos /media/upload，kind=auto 由后端归类，文件本体落 media_store 单源存储
 *   （REQ-UX-FLOW-1#3：现网是 local 本地桶，页头文案取 /api/system/info storage.media 真实值生成，
 *   禁硬编码桶名——旧文案写死「ms-swift-media」与现实不符；FW-5 契约：不另造存储路径）。
 *   服务端限制：0 字节 400、>MEDIA_MAX_MB(默认 500MB) 413，
 *   原样回显 detail，不在前端预演一套平行校验。
 * - BUG-PARITY-14 体验包：
 *   ① 卡片⇄表格双视图（老 Media.vue:104-177 表格列=文件(名+路径)/类型/大小/上传时间/操作，
 *      ≤1400px 收列回落文件副行 :1789-1807；勾选/打开/预览/转可标注/复制/删除同网格）；
 *      视图选择记忆 localStorage `dw_media_layout`，默认卡片（不劣化 13-A/B/C/D 现网口径）。
 *   ② 上传=「＋ 上传文件」弹窗（老 :218-283）：目标目录可选（自动归档/根/已有目录/新建多级）+
 *      媒体类型可手选（api 层 kind+folder 本就透传，media.py:660-675）+ 进度 x/y（老 :83-86,270-273）+
 *      **每个失败文件单独报**、成功汇总一条带去向（老 :1375-1381）+「单个 ≤ 500 MB」明数（老 :225）；
 *      另有页面任意处拖拽+遮罩（老 :2-11,1420-1437），弹窗开着不受理页面拖放。
 *   ③ 预览正文：文本读前 200KB（JSON 美化+截断提示，老 :310-315,1163-1191）、PDF iframe 内嵌（老 :307-309）；
 *      图/音/视加载失败给「媒体文件无法加载/可能已被删除或移动」callout + 防缓存重试（老 :291-297,1145-1154）。
 * - 卡片行含「复制（标注可用地址 ls_url）/删除（409 引用二次确认→force）」，预览弹窗带
 *   平台地址/标注可用地址 kv 与 footer 操作（BUG-PARITY-13-A，对齐老 Media.vue:172-173,333-349,1052-1072）。
 * - 勾选批量（全选/已选 N/取消选择/批量删除/生成标注任务）：BUG-PARITY-13-B，
 *   对齐老 Media.vue:93-101（批量条）、631-639（选择派生）、1242-1307（批量删：逐个删+409 收集
 *   一次确认批量 force+汇总）、1439-1538（选择记忆/建任务模板链），确认框与 toast 文案逐句照抄老版。
 *   「全选」走 listAllMedia（limit=0=当前筛选**所有页**，media.py:445）做并集、取消只摘本批——
 *   不吞别的页/别的筛选下已选（老 toggleAll :1469-1483 同义）。
 * - 目录树多级浏览 + 目录建/改/删 + 「选整个文件夹」递归半选 + 「加入新建项目」跨页交接 =
 *   BUG-PARITY-13-C（老 Media.vue:14-48 树侧栏、:723-877 loadRoot/refreshTree/enterFolder/
 *   loadFolder、:892-1007 建改删与改名 remap、:1444-1467 pickFolder、:1500-1504 sendToWizard、
 *   :87-92 pick 横幅、:1205-1222「目录已空并被移除」）。
 * - 审核模式键盘（←/→ J/K 切当前页、越界自动翻页续看接首/末张、Delete 删当前且删后按 path
 *   接「原位置下一张」、Esc 关、底部键位提示条）+ 预览 footer 操作区（新窗口打开/转可标注/
 *   ← 上一张 · n / 总数 · 下一张 →/删除当前/关闭/复制标注地址）+ 文档转可标注（PDF 逐页预览、
 *   Word 转 HTML，建「文档标注 - 文件名」项目）= BUG-PARITY-13-D（老 Media.vue:328,336-350,
 *   1015-1099,1192-1196,1540-1585；后端 POST /api/media/render=media.py:766；renderSeq 竞态守卫照搬）。
 */

/** 危险确认框（老版 askConfirm 等价；\n 必须 pre-line 显示，裸串会被折叠成一行=PARITY-15 同款坑） */
function askConfirm(opt: { title: string; message: string; okText: string; danger?: boolean }): Promise<boolean> {
  confirmDepth += 1
  return new Promise((resolve) => {
    const done = (v: boolean) => { confirmDepth = Math.max(0, confirmDepth - 1); resolve(v) }
    Modal.confirm({
      title: opt.title,
      content: <div style={{ whiteSpace: 'pre-line' }}>{opt.message}</div>,
      okText: opt.okText,
      okButtonProps: opt.danger ? { danger: true } : undefined,
      cancelText: '取消',
      onOk: () => done(true),
      onCancel: () => done(false),
    })
  })
}

const KIND_ICON: Record<string, React.ReactNode> = {
  image: <PictureOutlined />, audio: <AudioOutlined />, video: <VideoCameraOutlined />,
  text: <FileTextOutlined />, other: <AppstoreOutlined />,
}
const KIND_COLOR: Record<string, string> = {
  image: 'cyan', audio: 'purple', video: 'blue', text: 'green', other: 'default',
}
const KIND_LABEL: Record<string, string> = {
  image: '图片', audio: '音频', video: '视频', text: '文本', other: '其他',
}

const fmtSize = (n: number) => {
  if (!Number.isFinite(n) || n < 0) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** BUG-PARITY-15 ①：本页每页条数不再写死 60——走平台通用包的全局偏好（useGlobalPageSize，
 *  与标注/训练集/任务页同键同值集；老 prefs.js:6-35 本就是一个值管所有列表）。 */

/** 可建标注任务的类型（老 Media.vue:1506 CREATABLE；pdf/docx/other 要先「转可标注」） */
const CREATABLE = new Set(['audio', 'video', 'image', 'text'])

// ---------- 文档类型判定（BUG-PARITY-13-D，老 Media.vue:597-600 逐字搬） ----------
// 靠扩展名而不是 kind（后端 kind 只分图/音/视/文本/其他，pdf/docx 都落 other/text）。
const extOf = (m: { name?: string } | null | undefined) => (((m && m.name) || '').match(/\.[^./]+$/) || [''])[0].toLowerCase()
const isDoc = (m: { name?: string } | null | undefined) => /\.(pdf|docx?)$/i.test((m && m.name) || '')
const isPdf = (m: { name?: string } | null | undefined) => extOf(m) === '.pdf'
const isWord = (m: { name?: string } | null | undefined) => ['.doc', '.docx'].includes(extOf(m))
// 能按文本读出来的：后端标的 text，加上常见代码 / 配置后缀（这些多半落在 other）（老 :601-608 逐字搬）
const TEXT_EXT = new Set(['.txt', '.md', '.json', '.jsonl', '.csv', '.tsv', '.log',
  '.yaml', '.yml', '.xml', '.html', '.htm', '.py', '.js', '.ts', '.tsx', '.jsx',
  '.css', '.sh', '.ini', '.toml', '.conf', '.sql', '.vue', '.go', '.rs', '.java',
  '.c', '.h', '.cpp'])
const isTextPreview = (m: MosMediaItem | null | undefined) => !!m && (m.kind === 'text'
  || TEXT_EXT.has(extOf(m))
  || String(m.content_type || '').startsWith('text/'))

/**
 * 审核键盘的 confirm 闸（老 Media.vue onPreviewKey :1084「confirmState.value 时不受理按键」）：
 * 删除弹二次确认时若还收 Delete/方向键，会在确认框背后连删连翻页。计数增减挂在 askConfirm 上，
 * 预览弹窗的 keydown 处理器读它决定要不要吞按键。
 */
let confirmDepth = 0

/** 预览 kv 行（老 Media.vue:329-334「文件信息与地址」details 的等价版式） */
function Kv({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 8, fontSize: 12, marginBottom: 2 }}>
      <Typography.Text type="secondary" style={{ flex: '0 0 96px' }}>{label}</Typography.Text>
      <span style={{ minWidth: 0, wordBreak: 'break-all', fontFamily: 'ui-monospace, Consolas, monospace' }}>{children}</span>
    </div>
  )
}

/** 平台可访问地址：item.url 是上游相对地址、缺通道前缀（文件头口径 :27），复制必须拼通道。 */
const platformUrl = (p: string) => `${window.location.origin}${mosMediaUrl(p)}`

export default function AssetsMedia() {
  // BUG-PARITY-15 ①：每页条数=全局偏好（键 'ft.prefs'，与其余列表同值）
  const [pageSize, setPageSize] = useGlobalPageSize()
  const navigate = useNavigate()
  const [data, setData] = useState<MosMediaList | null>(null)
  const [kind, setKind] = useState('')
  const [q, setQ] = useState('')
  const [inputKw, setInputKw] = useState('')
  const [page, setPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState<any>(null)
  const [preview, setPreview] = useState<MosMediaItem | null>(null)
  const [thumbFailed, setThumbFailed] = useState<Record<string, boolean>>({})
  const [busyPath, setBusyPath] = useState<string | null>(null)
  // 勾选批量（老 Media.vue:576-578）：路径列表 + 元数据快照分开存，
  // 翻页/切类型后已选样本不丢（selectedMeta 是快照，当前页没有该条目也能建任务）。
  const [selected, setSelected] = useState<string[]>([])
  const [selectedMeta, setSelectedMeta] = useState<Record<string, MosMediaItem>>({})
  const [allBusy, setAllBusy] = useState(false)   // 全选要拉全量列表，防连点
  const [deleting, setDeleting] = useState(false) // 批量删进行中（老版按钮显示「删除中…」）
  const [createOpen, setCreateOpen] = useState(false)
  const [creating, setCreating] = useState(false)
  const [newTitle, setNewTitle] = useState('')
  const [tplKey, setTplKey] = useState('custom')
  // ---------- 目录树与目录管理（BUG-PARITY-13-C，老 Media.vue:513-538） ----------
  // browse=false：「全部文件」服务端扁平分页；browse=true：只看 currentPath 这一层
  // （子目录+文件），多级靠面包屑 / 左侧树进入（老注释口径 :513-514）。
  const [browse, setBrowse] = useState(false)
  const [currentPath, setCurrentPath] = useState('')
  const [currentTree, setCurrentTree] = useState<MosMediaTree | null>(null)
  const [folderPage, setFolderPage] = useState(1)
  const [rootFolders, setRootFolders] = useState<MosMediaFolder[]>([])
  const [childrenByPath, setChildrenByPath] = useState<Record<string, MosMediaFolder[]>>({})
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [folderPicking, setFolderPicking] = useState(false) // 「选整个文件夹」读取中
  const [folderBusy, setFolderBusy] = useState('')          // 目录卡片勾选框 subtree 读取中
  const [dirModal, setDirModal] = useState<{ open: boolean; parent: string }>({ open: false, parent: '' })
  const [dirName, setDirName] = useState('')
  const [dirCreating, setDirCreating] = useState(false)
  const [renameTarget, setRenameTarget] = useState<MosMediaFolder | null>(null)
  const [renameName, setRenameName] = useState('')
  const [renaming, setRenaming] = useState(false)
  // path -> 该目录递归文件缓存（勾选过再点=取消，不重拉；老 MediaPicker :226）
  const folderFiles = useRef<Record<string, MosMediaItem[]>>({})
  // 异步链里要读**最新**树态（state 快照会旧）：ref 与 state 同步双写
  const childrenRef = useRef<Record<string, MosMediaFolder[]>>({})
  const expandedRef = useRef<Record<string, boolean>>({})
  const seqRef = useRef({ load: 0, tree: 0, nav: 0 })
  const location = useLocation()
  // pick 横幅（老 Media.vue:504,87-92）：向导跳来挑样本时明示用途并给「返回项目向导」出口
  const isPicking = new URLSearchParams(location.search).get('pick') === '1'
  // REQ-UX-FLOW-1#3：存储位置文案取后端真实配置（/api/system/info 的 storage.media），禁硬编码桶名。
  // undefined=探测中；null=探测失败（页头如实说读不到，不回退旧硬编码文案、不静默）
  const [mediaStore, setMediaStore] = useState<MosMediaStoreInfo | null | undefined>(undefined)
  useEffect(() => {
    void getSystemInfo()
      .then((s) => setMediaStore(s?.storage?.media ?? null))
      .catch(() => setMediaStore(null))
  }, [])
  // ---------- 上传弹窗 + 页面拖拽（BUG-PARITY-14，老 Media.vue:218-283,1359-1437） ----------
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadPicked, setUploadPicked] = useState<File[]>([])
  const [uploadKind, setUploadKind] = useState('auto')
  const [uploadFolderSel, setUploadFolderSel] = useState('__auto__')
  const [uploadDirs, setUploadDirs] = useState<string[]>([])
  const [uploadNewFolder, setUploadNewFolder] = useState('')
  const [uploadTotal, setUploadTotal] = useState(0)
  const [uploadDone, setUploadDone] = useState(0)
  const [drag, setDrag] = useState(false)
  const dragDepthRef = useRef(0)
  const upInputRef = useRef<HTMLInputElement | null>(null)
  // ---------- 卡片⇄表格视图（BUG-PARITY-14，老表格是唯一形态 :104-177；dw_media_layout 记忆，默认卡片） ----------
  const [layout, setLayout] = useState<'card' | 'table'>(() => {
    try { return localStorage.getItem('dw_media_layout') === 'table' ? 'table' : 'card' } catch { return 'card' }
  })
  // ---------- 预览正文 + 失败重试出口（BUG-PARITY-14，老 :291-315,1145-1191） ----------
  const [previewText, setPreviewText] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState('')
  const [previewTruncated, setPreviewTruncated] = useState(false)
  const [previewMediaError, setPreviewMediaError] = useState('')
  const [pvReload, setPvReload] = useState(0)
  const previewSeqRef = useRef(0)
  // ---------- 审核键盘/预览翻页 与 文档转可标注（BUG-PARITY-13-D，老 Media.vue:1015-1099,1540-1585） ----------
  const [renderOpen, setRenderOpen] = useState(false)
  const [renderName, setRenderName] = useState('')
  const [renderSource, setRenderSource] = useState<MosMediaItem | null>(null)
  const [renderData, setRenderData] = useState<MosRenderResult | null>(null)
  const [rendering, setRendering] = useState(false)
  const [creatingDoc, setCreatingDoc] = useState(false)
  // renderSeq 竞态守卫（老 :1540-1556）：转换慢，关掉再转另一份只认最后一次，
  // 否则 A 的结果挂到 B 标题下，建出内容是 A 的「文档标注 - B」。
  const renderSeqRef = useRef(0)
  // 越界翻页请求中再按 J/K 忽略（老 paging 闸 :1026-1027），并防连点跳页（老 goPage :705）
  const pagingRef = useRef(false)
  const loadedKeyRef = useRef('')
  // 键盘处理器常驻一次，数据从最新渲染快照读（React 闭包会旧；老 Vue 天然读 .value）
  const viewRef = useRef<{ pv: MosMediaItem | null; list: MosMediaItem[]; pageNo: number; pages: number }>({ pv: null, list: [], pageNo: 1, pages: 1 })
  const stepRef = useRef<(d: number) => Promise<void>>(async () => {})
  const delCurrentRef = useRef<() => Promise<void>>(async () => {})

  // 13-D：load **返回拉到的数据**（越界翻页 await 后要拿新一页的首/末张，不能读旧渲染闭包），
  // 成功时把 (kind,q,page) 记进 loadedKeyRef；下方 effect 对已拉过的键不再补发第二次请求。
  const load = useCallback(async (targetPage?: number): Promise<MosMediaList | null> => {
    const p = targetPage ?? page
    setLoading(true)
    setError(null)
    try {
      const d = await listMedia({ kind, q, limit: pageSize, offset: (p - 1) * pageSize })
      setData(d)
      setThumbFailed({})
      loadedKeyRef.current = `${kind}|${q}|${p}|${pageSize}`
      return d
    } catch (e) {
      setError(e)
      return null
    } finally {
      setLoading(false)
    }
    // BUG-PARITY-15 ①：pageSize 进依赖与加载键——每页条数变了要重拉，不能拿旧键判定「已拉过」
  }, [kind, q, page, pageSize])

  useEffect(() => {
    if (loadedKeyRef.current === `${kind}|${q}|${page}|${pageSize}`) return
    void load()
  }, [load, kind, q, page, pageSize])

  // ---------- 目录树（BUG-PARITY-13-C，老 Media.vue:723-890） ----------
  // seq 三连：load/tree/nav 各自只认最后一次请求（老 :539,810-811 快速连点防盖写）。
  const treeSeq = () => ++seqRef.current.tree
  const navSeq = () => ++seqRef.current.nav

  const loadRoot = useCallback(async () => {
    try {
      const d = await mediaTree('', 1)
      setRootFolders(d.folders || [])
      childrenRef.current = { ...childrenRef.current, '': d.folders || [] }
      setChildrenByPath(childrenRef.current)
    } catch { /* 目录树失败不挡住文件列表；进入目录时再报错（老 :729） */ }
  }, [])

  useEffect(() => { void loadRoot() }, [loadRoot])

  const fetchChildren = useCallback(async (path: string) => {
    if (childrenRef.current[path]) return
    try {
      const d = await mediaTree(path, 1)
      childrenRef.current = { ...childrenRef.current, [path]: d.folders || [] }
      setChildrenByPath(childrenRef.current)
    } catch (e: any) { message.error(e?.message || '目录加载失败') }
  }, [])

  // 目录内容分页（老 loadFolder :813-831）：连点只认最后一次；当前页被删空退到最后一页。
  const loadFolder = useCallback(async (path: string, pageNo = 1): Promise<MosMediaTree | null> => {
    const seq = treeSeq()
    const d = await mediaTree(path, pageSize, (pageNo - 1) * pageSize)
    if (seq !== seqRef.current.tree) return null
    setCurrentTree(d)
    setFolderPage(pageNo)
    childrenRef.current = { ...childrenRef.current, [path]: d.folders || [] }
    setChildrenByPath(childrenRef.current)
    const pages = Math.max(1, Math.ceil(Number(d.files_total || 0) / pageSize))
    if (pageNo > pages) return loadFolder(path, pages)
    return d
    // BUG-PARITY-15 ①：目录内分页同样吃全局每页条数
  }, [pageSize])

  // 删除/新建/上传后重建目录树（老 refreshTree :734-780）：根+所有已展开节点全部重拉、
  // 不留旧缓存；父目录的新 children 里没有的节点直接丢弃，不再对已删目录发请求。
  const refreshTree = useCallback(async (extraPaths: string[] = []) => {
    const exp = { ...expandedRef.current }
    const next: Record<string, MosMediaFolder[]> = {}
    let rootOk = false
    try {
      const d = await mediaTree('', 1)
      rootOk = true
      next[''] = d.folders || []
      setRootFolders(d.folders || [])
    } catch { /* 列表还在，树刷新失败不打断删除结果 */ }
    const paths = Object.keys(exp).filter((p) => exp[p])
      .sort((a, b) => a.split('/').length - b.split('/').length)
    for (const p of paths) {
      const parent = p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : ''
      const siblings = next[parent]
      if (!siblings || !siblings.some((f) => f.path === p)) { delete exp[p]; continue }
      try {
        const d = await mediaTree(p, 1)
        next[p] = d.folders || []
      } catch { delete exp[p] } // 目录已不存在（删除或空目录被自动清理）
    }
    // 额外校验路径：父目录折叠时 valid 里本来没有它，补拉一次父目录而不是拿当前目录撞 404（老 :767-771）
    for (const p of extraPaths) {
      const parent = p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : ''
      if (next[parent]) continue
      try { const d = await mediaTree(parent, 1); next[parent] = d.folders || [] } catch { /* ignore */ }
    }
    expandedRef.current = exp
    setExpanded(exp)
    childrenRef.current = next
    setChildrenByPath(next)
    const valid = new Set([''])
    for (const list of Object.values(next)) for (const f of list) valid.add(f.path)
    return { valid, rootOk }
  }, [])

  const showAll = useCallback(async () => {
    navSeq()
    treeSeq() // 丢掉还在路上的目录请求（老 :867）
    setBrowse(false)
    setCurrentPath('')
    setCurrentTree(null)
    setFolderPage(1)
    setPage(1)
    await Promise.all([load(1), loadRoot()])
  }, [load, loadRoot])

  // 进目录（老 enterFolder :833-863）：先清上一个目录的内容，新目录 404 时不能把旧表格挂新路径下；
  // 再把路径上每一级在左树展开，保证当前位置可见。
  const enterFolder = useCallback(async (path: string) => {
    if (!path) { await showAll(); return }
    const nav = navSeq()
    setBrowse(true)
    setCurrentPath(path)
    setCurrentTree(null)
    setError(null)
    try {
      const d = await loadFolder(path, 1)
      if (nav !== seqRef.current.nav || !d) return
      const exp = { ...expandedRef.current }
      let acc = ''
      for (const part of path.split('/')) {
        acc = acc ? acc + '/' + part : part
        exp[acc] = true
        await fetchChildren(acc)
      }
      expandedRef.current = exp
      setExpanded(exp)
    } catch (e) {
      if (nav === seqRef.current.nav) setError(e)
    }
  }, [loadFolder, fetchChildren, showAll])

  const toggleExpand = (path: string) => {
    const next = !expandedRef.current[path]
    expandedRef.current = { ...expandedRef.current, [path]: next }
    setExpanded(expandedRef.current)
    if (next) void fetchChildren(path)
  }

  const refreshFolder = useCallback(async () => {
    if (!browse) return
    try { await loadFolder(currentPath, folderPage) }
    catch (e: any) { message.error(e?.message || '目录刷新失败') }
  }, [browse, currentPath, folderPage, loadFolder])

  // 顶部「刷新」要刷新用户正在看的这一层（老 refreshCurrent :887-890——
  // 目录视图以前去拉全库列表，目录内容永远不变，看起来像刷新没生效）
  const refreshCurrent = () => {
    if (browse) void refreshFolder()
    else void load()
  }

  // ---------- 目录 CRUD（老 Media.vue:892-1007） ----------
  const openFolderModal = (parent = '') => { setDirModal({ open: true, parent }); setDirName('') }

  const createFolder = async () => {
    const name = dirName.trim()
    if (!name || dirCreating) return
    setDirCreating(true)
    const parent = dirModal.parent
    try {
      const r = await mediaCreateFolder({ parent, name })
      message.success('已创建目录：' + r.path)
      setDirModal({ open: false, parent: '' })
      // 多级目录一次建完：把路径上的每一级（除最深那级本身）都展开，树里立刻能看到（老 :908-916）
      const parts = String(r.path || '').split('/').filter(Boolean)
      const exp = { ...expandedRef.current }
      let acc = ''
      for (let i = 0; i < parts.length - 1; i++) {
        acc = acc ? acc + '/' + parts[i] : parts[i]
        exp[acc] = true
      }
      expandedRef.current = exp
      setExpanded(exp)
      await refreshTree()
      if (browse && parent === currentPath) await refreshFolder()
    } catch (e: any) { message.error(e?.message || '创建目录失败') }
    finally { setDirCreating(false) }
  }

  const remapPrefix = (p: string, oldPrefix: string, newPrefix: string) => {
    if (!p) return p
    if (p === oldPrefix) return newPrefix
    if (p.startsWith(oldPrefix + '/')) return newPrefix + p.slice(oldPrefix.length)
    return p
  }

  const openRename = (folder: MosMediaFolder) => { setRenameTarget(folder); setRenameName(folder.name) }

  const doRename = async () => {
    if (!renameTarget || renaming) return
    const name = renameName.trim()
    if (!name) return
    const oldPath = renameTarget.path
    if (name.includes('/') || name.includes('\\')) {
      message.error('新名字不能带 /，只改当前这一级')
      return
    }
    setRenaming(true)
    try {
      const r = await mediaRenameFolder({ path: oldPath, name })
      message.success('已重命名：' + r.path)
      setRenameTarget(null)
      // 展开状态、选中文件、当前目录一起迁移到新路径（老 :953-977）
      const exp: Record<string, boolean> = {}
      for (const [k, v] of Object.entries(expandedRef.current)) exp[remapPrefix(k, oldPath, r.path)] = v
      expandedRef.current = exp
      setExpanded(exp)
      // url / ls_url 是按旧路径生成的字符串，必须一起改：只改 path 的话，
      // 紧接着「生成标注任务」会把旧地址写进任务（文本 fetch 404、媒体打不开）（老 :960-963）。
      // 与老版的差异（刻意）：老版裸 String.replace(oldPath, …)，短目录名会误伤——
      // 例如 'a' 会先命中 '/data/' 里的 a 把地址改坏；这里锚段边界（前导 / ( = 且后随 / 或结尾）。
      const enc = (p: string) => String(p || '').split('/').map(encodeURIComponent).join('/')
      const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const remapUrl = (u?: string) => (u
        ? String(u).replace(new RegExp('([/(=])' + esc(enc(oldPath)) + '(?=/|$)', 'g'), '$1' + enc(r.path))
        : u)
      // 「整文件夹」半选缓存的 key 也是旧路径：不跟着搬，改名后进目录会退化成按计数粗判
      const ff: Record<string, MosMediaItem[]> = {}
      for (const [k, v] of Object.entries(folderFiles.current)) ff[remapPrefix(k, oldPath, r.path)] = v
      folderFiles.current = ff
      setSelected((prev) => prev.map((p) => remapPrefix(p, oldPath, r.path)))
      setSelectedMeta((prev) => {
        const meta: Record<string, MosMediaItem> = {}
        for (const [k, v] of Object.entries(prev)) {
          const nk = remapPrefix(k, oldPath, r.path)
          meta[nk] = { ...v, path: nk, url: remapUrl(v.url) || v.url, ls_url: remapUrl(v.ls_url) || v.ls_url }
        }
        return meta
      })
      const inside = currentPath === oldPath || currentPath.startsWith(oldPath + '/')
      if (inside) {
        const nextPath = remapPrefix(currentPath, oldPath, r.path)
        setCurrentPath(nextPath)
        try { await loadFolder(nextPath, 1) } catch { /* refreshTree 后统一呈现 */ }
      }
      await refreshTree()
      // 「全部文件」视图也要重取：表格里存的还是旧 path/url，不改的话预览/删除/复制地址
      // 全部指向已经不存在的位置（老 :979-982）。
      // 注意：inside 时**不能**走 refreshFolder——它是按渲染时闭包里的 currentPath 拉取的，
      // 旧路径此刻已被后端改名，会 404「目录不存在」弹错；上面 loadFolder(nextPath) 已刷过内容。
      if (inside) { /* 已按新路径重取 */ }
      else if (browse) await refreshFolder()
      else await load()
    } catch (e: any) { message.error(e?.message || '重命名失败') }
    finally { setRenaming(false) }
  }

  const removeFolder = async (path: string) => {
    if (!path) return
    const name = path.split('/').pop()
    const go = await askConfirm({
      title: '删除目录',
      message: '删除目录「' + name + '」？\n\n只能删除空目录；里面的文件会挡住删除，不会自动删文件。',
      okText: '删除目录',
      danger: true,
    })
    if (!go) return
    try {
      await mediaDeleteFolder(path)
      message.success('已删除目录：' + path)
      if (path === currentPath) {
        await showAll()
        await refreshTree()
      } else {
        await refreshTree()
        if (browse) await refreshFolder()
      }
    } catch (e: any) { message.error(e?.message || '删除目录失败') }
  }

  // 目录右键菜单（老 :1308-1344 menuAction）：右键是目录建/删的唯一入口。
  const dirMenu = (folder: MosMediaFolder | null): MenuProps['items'] => [
    ...(folder ? [
      { key: 'open', label: '打开目录', onClick: () => void enterFolder(folder.path) },
      { key: 'rename', label: '重命名目录', onClick: () => openRename(folder) },
    ] : []),
    { key: 'new', label: folder ? '新建子目录' : '新建根目录', onClick: () => openFolderModal(folder?.path || '') },
    ...(folder ? [
      { key: 'delete', label: '删除目录（仅空目录）', danger: true, onClick: () => void removeFolder(folder.path) },
    ] : []),
  ]

  // ---------- 从媒体建样本（老 pickFolder :1444-1467 / sendToWizard :1500-1504） ----------

  const canPick = (m: MosMediaItem) => CREATABLE.has(m.kind)
    && !/^(pdf|doc|docx)$/i.test(String(m.name || '').split('.').pop() || '')

  const pickFolder = async (path: string) => {
    if (!path) return
    setFolderPicking(true)
    try {
      const r = await mediaSubtree(path)
      const list = (r.items || []).filter(canPick)
      if (!list.length) { message.error('这个文件夹（含子目录）里没有可标注的文件'); return }
      const have = new Set(selected)
      const meta = { ...selectedMeta }
      let added = 0
      for (const m of list) {
        if (!have.has(m.path)) { have.add(m.path); added += 1 }
        meta[m.path] = m
      }
      setSelected([...have])
      setSelectedMeta(meta)
      message.success('已从「' + (path.split('/').pop() || '文件夹') + '」加入 ' + added + ' 个文件')
    } catch (e: any) {
      message.error('读取文件夹失败: ' + (e?.message || '未知错误'))
    } finally { setFolderPicking(false) }
  }

  const dropSelectedUnder = (pre: string) => {
    setSelected((prev) => prev.filter((p) => !p.startsWith(pre)))
    setSelectedMeta((prev) => {
      const sm = { ...prev }
      for (const p of Object.keys(sm)) if (p.startsWith(pre)) delete sm[p]
      return sm
    })
  }

  // 目录卡片勾选框：勾=整目录递归选中（subtree），半选=部分已选；再点 all=整目录移除
  const folderState = (f: MosMediaFolder): 'none' | 'some' | 'all' => {
    const pre = f.path + '/'
    const sel = selected.filter((p) => p.startsWith(pre))
    if (!sel.length) return 'none'
    const all = folderFiles.current[f.path]
    if (all) {
      const usableAll = all.filter(canPick)
      return usableAll.length && usableAll.every((m) => selectedSet.has(m.path)) ? 'all' : 'some'
    }
    return sel.length >= f.files ? 'all' : 'some'
  }

  const toggleFolderPick = async (f: MosMediaFolder) => {
    if (folderBusy) return
    const pre = f.path + '/'
    if (folderState(f) === 'all') { dropSelectedUnder(pre); return }
    setFolderBusy(f.path)
    try {
      let items = folderFiles.current[f.path]
      if (!items) {
        const r = await mediaSubtree(f.path)
        items = r.items || []
        folderFiles.current = { ...folderFiles.current, [f.path]: items }
      }
      const have = new Set(selected)
      const add = items.filter((m) => canPick(m) && !have.has(m.path))
      if (!add.length) {
        const sel = selected.filter((p) => p.startsWith(pre))
        if (sel.length) { dropSelectedUnder(pre); message.success('已从「' + f.name + '」移除 ' + sel.length + ' 个文件'); return }
        message.error(items.length ? '这个文件夹里的文件都已选中，或没有当前可用的类型' : '这个文件夹（含子目录）里没有可用文件')
        return
      }
      const meta = { ...selectedMeta }
      for (const m of add) { have.add(m.path); meta[m.path] = m }
      setSelected([...have])
      setSelectedMeta(meta)
      message.success('已从「' + f.name + '」加入 ' + add.length + ' 个文件')
    } catch (e: any) {
      message.error('读取文件夹失败: ' + (e?.message || '未知错误'))
    } finally { setFolderBusy('') }
  }

  // 把勾选样本交给「新建标注项目」向导；项目名/模板在那边再确认（老 :1499-1504）。
  // 交接走 sessionStorage（mediaPick.ts=annotPick.js 移植），跨路由不依赖后端临时状态。
  const sendToWizard = () => {
    if (!selectedItems.length) return
    setPendingPick(selectedItems)
    navigate('/mos/labels?create=1')
  }

  // 删完文件后的目录口径（老 refreshAfterDelete :1205-1222）：删掉最后一个文件时后端会
  // 顺带清掉空目录——先用刷新后的树判断当前目录还在不在，不在就回「全部文件」，
  // 避免再请求一个已删除目录。
  // 13-D：返回 {list=刷新后的可见列表, ctxChanged=浏览上下文变了}——预览里删完要靠它
  // 「接下一张」（老 previewDelete :1096-1114：上下文变了就关窗不瞎跳，同下标兜底）。
  const refreshAfterDelete = async (): Promise<{ list: MosMediaItem[]; ctxChanged: boolean }> => {
    if (!browse) {
      const d = await load()
      await refreshTree()
      return { list: d?.items ?? [], ctxChanged: false }
    }
    const { valid, rootOk } = await refreshTree([currentPath])
    if (rootOk && !valid.has(currentPath)) {
      message.success('目录已空并被移除')
      await showAll()
      return { list: [], ctxChanged: true }
    }
    try {
      const d = await loadFolder(currentPath, folderPage)
      return { list: d?.files ?? [], ctxChanged: false }
    } catch {
      await showAll()
      return { list: [], ctxChanged: true }
    }
  }

  // 上传目标（老 uploadTarget computed :551-560）：__auto__=按类型/年月自动归档；
  // __root__=媒体库根；具体路径=直放该目录；__new__=建在**当前所在目录**下的多级新目录。
  const uploadTarget = (() => {
    if (uploadFolderSel === '__auto__') return ''
    if (uploadFolderSel === '__root__') return '__root__'
    if (uploadFolderSel === '__new__') {
      const p = uploadNewFolder.trim().replace(/^\/+|\/+$/g, '')
      if (!p) return ''
      const base = browse ? currentPath : ''
      return base ? base + '/' + p : p
    }
    return uploadFolderSel
  })()

  // 批量上传链（老 uploadFiles :1359-1391）：逐张串行；进度 x/y（老 :83-86,270-273）；
  // **每个失败文件单独报**、成功汇总一条带去向（老 :1375-1381）。
  // 旧版两处断头在这修掉：①beforeUpload 每文件各起一轮=每文件一条成功 toast；②失败只报第一条。
  const uploadFiles = async (files: File[], kind = 'auto', folder?: string) => {
    const list = Array.from(files || [])
    if (!list.length) return
    // folder 未传（页面拖拽）时沿用当前目录；弹窗上传用用户选的目标（老 :1361）
    const target = folder === undefined ? (browse ? currentPath : '') : folder
    setUploading(true)
    setUploadTotal(list.length)
    setUploadDone(0)
    let finished = 0
    let done = 0
    for (const f of list) {
      try {
        await uploadMedia(f, { kind, folder: target || undefined })
        finished += 1
      } catch (e: any) {
        message.error(`${f.name}：${e?.message || '上传失败'}`)
      }
      done += 1
      setUploadDone(done)
    }
    setUploading(false)
    if (finished) {
      const where = target === '__root__' ? '媒体库根目录' : target
      message.success(`已上传 ${finished} 个文件` + (where ? ` → ${where}` : ''))
      if (browse) await refreshFolder()
      else await load()
      await loadRoot()
      // 上传可能新建了归档目录：树与目录下拉一起重取；失败不挡（老 :1385「下拉稍后刷新」）
      void refreshTree()
      try { setUploadDirs((await mediaDirs()).dirs || []) } catch { /* 下拉稍后刷新 */ }
    }
  }

  const openUpload = async () => {
    setUploadPicked([])
    setUploadKind('auto')
    setUploadNewFolder('')
    setUploadFolderSel(browse ? currentPath : '__auto__')
    setUploadOpen(true)
    try { setUploadDirs((await mediaDirs()).dirs || []) } catch { setUploadDirs([]) }
  }

  const confirmUpload = async () => {
    if (!uploadPicked.length) return
    if (uploadFolderSel === '__new__' && !uploadTarget) { message.error('新目录名不能为空'); return }
    await uploadFiles(uploadPicked, uploadKind, uploadTarget)
    setUploadOpen(false)
  }

  // 弹窗里拖动图片 / 选中文字时，浏览器也可能带一个 File；别把这当成「上传」（老 anyModalOpen :1415-1419）
  const anyModalOpen = () => !!preview || uploadOpen || renderOpen || dirModal.open || !!renameTarget || createOpen

  // 页面任意处拖拽 + 遮罩（老 :2-11,1420-1437）：dragenter 计数防子元素进出闪遮罩；
  // 只认带 Files 的拖拽（拖选文字/拖链接不动）。
  const onDragEnter = (e: React.DragEvent) => {
    if (anyModalOpen()) return
    if (!Array.from(e.dataTransfer?.types || []).includes('Files')) return
    dragDepthRef.current += 1
    setDrag(true)
  }
  const onDragLeave = () => {
    if (dragDepthRef.current > 0) dragDepthRef.current -= 1
    if (!dragDepthRef.current) setDrag(false)
  }
  const onDropPage = (e: React.DragEvent) => {
    dragDepthRef.current = 0
    setDrag(false)
    if (anyModalOpen()) return
    const files = e.dataTransfer?.files
    if (files && files.length) void uploadFiles(Array.from(files), 'auto')
  }

  // 预览媒体加载失败出口（老 :291-297,1145-1154）：图/音/视挂了不能只留破图黑屏，
  // 报「可能已被删除或移动」+ 重试；重试加瞬时参数强制绕开失败缓存。
  const onPreviewMediaError = () => {
    if (!preview || previewMediaError) return
    setPreviewMediaError('文件可能已被删除或移动：' + preview.path)
  }
  const retryPreviewMedia = () => {
    if (!preview) return
    setPreviewMediaError('')
    setPvReload(Date.now())
  }
  const pvSrc = (m: MosMediaItem) => {
    const u = mosMediaUrl(m.path)
    return pvReload ? u + (u.includes('?') ? '&' : '?') + '_retry=' + pvReload : u
  }

  // 复制=老版 copy()（Media.vue:1198-1203）：成功回显前 60 字符，失败提示手动选择。
  // 卡片行与 footer 一律复制 **ls_url**（标注可用地址），不是 path——老版主打卖点就是
  // 「一键拿到 LS/工作台能直接吃的地址」（13-A⑥；新版曾只复制 path=断头）。
  const copyText = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text)
      message.success('已复制：' + text.slice(0, 60))
    } catch {
      message.error('复制失败，请手动选择')
    }
  }

  // 删除链（老 deleteMediaFile，Media.vue:1052-1072）：先不带 force；后端 409=被数据集
  // 引用，必须再确认一次才带 force=1 重发；用户在二次确认里取消则文件保留（cancelled）。
  const deleteMediaFile = async (path: string): Promise<'ok' | 'cancelled' | 'fail'> => {
    try {
      await mediaDelete(path)
      return 'ok'
    } catch (e: any) {
      if (e?.status !== 409) { message.error(e?.message || '删除失败'); return 'fail' }
    }
    const yes = await askConfirm({
      title: '文件被标注集引用',
      message: '这个文件仍被标注集条目引用。\n\n删除后这些条目的媒体会断链'
        + '（训练集导出、标注回看都会缺失）。\n仍然删除？',
      okText: '仍然删除',
      danger: true,
    })
    if (!yes) return 'cancelled'
    try {
      await mediaDelete(path, true)
      return 'ok'
    } catch (e: any) { message.error(e?.message || '删除失败'); return 'fail' }
  }

  const removeMedia = async (m: MosMediaItem) => {
    const fromPreview = preview?.path === m.path
    const go = await askConfirm({
      title: '删除媒体文件',
      message: '永久删除这个媒体文件？\n' + m.path
        + '\n\n无法恢复；引用它的标注任务和标注集会失效。',
      okText: '删除',
      danger: true,
    })
    if (!go) return
    // 删完接哪张（老 previewDelete :1080-1084 教训）：记「原位置的下一条」的 **path**，
    // 不能用下标——删空目录会退回「全部文件」、删空一页会退到上一页，同下标在新列表可能是无关文件。
    const i0 = fileItems.findIndex((x) => x.path === m.path)
    const nextPath = i0 >= 0 && fileItems[i0 + 1] ? fileItems[i0 + 1].path : (i0 > 0 && fileItems[i0 - 1] ? fileItems[i0 - 1].path : '')
    setBusyPath(m.path)
    try {
      const del = await deleteMediaFile(m.path)
      if (del !== 'ok') return
      message.success('已删除')
      // 老 :1097-1100,1232-1233：删除成功把这条从勾选里摘掉，批量条不能还计着它
      setSelected((prev) => prev.filter((p) => p !== m.path))
      setSelectedMeta((prev) => { const sm = { ...prev }; delete sm[m.path]; return sm })
      // 目录口径归 13-C：删的是目录里最后一个文件时，后端顺带清掉空目录，
      // 要「目录已空并被移除」回全部文件，而不是停在一个 404 的路径上（老 :1205-1222）
      const res = await refreshAfterDelete()
      if (fromPreview) {
        // 老 :1101-1114：优先接 nextPath；没有它（比如被别的页引用）退同下标兜底；
        // 上下文变了（目录被清→回全部文件）不瞎跳，直接关预览。
        const target = (nextPath ? res.list.find((x) => x.path === nextPath) : undefined)
          || (!res.ctxChanged && res.list.length ? res.list[Math.min(Math.max(i0, 0), res.list.length - 1)] : undefined)
        setPreview(target ? target : null)
      }
    } finally {
      setBusyPath(null)
    }
  }

  // ---------- 勾选与批量（BUG-PARITY-13-B，老 Media.vue:1439-1538） ----------

  const clearSelection = () => {
    setSelected([])
    setSelectedMeta({})
  }

  // 单卡勾选=老 toggle :1485-1497：选中即快照元数据进 selectedMeta（翻页后仍能建任务/删对数）
  const togglePick = (m: MosMediaItem) => {
    const has = selected.includes(m.path)
    setSelected(has ? selected.filter((p) => p !== m.path) : [...selected, m.path])
    const sm = { ...selectedMeta }
    if (has) delete sm[m.path]
    else sm[m.path] = m
    setSelectedMeta(sm)
  }

  // 全选/取消全选=老 toggleAll :1469-1483 的「跨页版」：对象是当前筛选（kind/q）的
  // **所有页**（listAllMedia limit=0，media.py:445 语义），勾选做**并集**、取消只摘本批——
  // 别的页/别的筛选下已选的样本绝不被吞（老版教训：selected 整表覆盖=换页丢选）。
  const toggleAll = async () => {
    if (browse) {
      // 目录内=老 toggleAll 对 filtered（本层已载一页）的原语义（:1469-1483）；
      // 递归整目录是「选整个文件夹」按钮/目录勾选框的职责，全选不越层。
      const vis = new Set(fileItems.map((m) => m.path))
      if (allChecked) {
        setSelected((prev) => prev.filter((p) => !vis.has(p)))
        setSelectedMeta((prev) => {
          const sm = { ...prev }
          for (const p of vis) delete sm[p]
          return sm
        })
      } else {
        const have = new Set(selected)
        const meta = { ...selectedMeta }
        for (const m of fileItems) { have.add(m.path); meta[m.path] = m }
        setSelected([...have])
        setSelectedMeta(meta)
      }
      return
    }
    setAllBusy(true)
    try {
      const all = await listAllMedia({ kind, q })
      const matched = all.items || []
      if (allChecked) {
        const gone = new Set(matched.map((m) => m.path))
        setSelected((prev) => prev.filter((p) => !gone.has(p)))
        setSelectedMeta((prev) => {
          const sm = { ...prev }
          for (const p of gone) delete sm[p]
          return sm
        })
      } else {
        setSelected((prev) => {
          const have = new Set(prev)
          for (const m of matched) have.add(m.path)
          return [...have]
        })
        setSelectedMeta((prev) => {
          const sm = { ...prev }
          for (const m of matched) sm[m.path] = m
          return sm
        })
      }
    } catch (e: any) {
      message.error(e?.message || '读取媒体列表失败')
    } finally {
      setAllBusy(false)
    }
  }

  // 批量删除=老 removeSelected :1242-1307，文案逐句：
  // 首确认列前 3 名+超 3 折叠；全选到整个媒体库再问一次「确认清空媒体库」；
  // 逐个调单删端点（后端无批量口，老 :1241 注释），409 **收集后一次确认批量 force**（不逐个弹框），
  // 取消=跳过并计入汇总；收尾「已删除 N 个文件」+「N 个删除失败，第一个：…」。
  const removeSelected = async () => {
    const list = selectedItems.slice()
    if (!list.length || deleting) return
    const names = list.slice(0, 3).map((m) => m.name).join('\n')
    const more = list.length > 3 ? '\n… 等 ' + list.length + ' 个' : ''
    const go = await askConfirm({
      title: '批量删除文件',
      message: '只删除勾选的 ' + list.length + ' 个文件（不会删目录）：\n'
        + names + more + '\n\n无法恢复；引用它们的标注任务和数据集会失效。',
      okText: '删除 ' + list.length + ' 个',
      danger: true,
    })
    if (!go) return
    // 全库都被选中时再确认一次：这一步能清空整个媒体库，值得多问一句（老 :1254-1262）
    if ((data?.all_total ?? 0) > 0 && list.length >= (data?.all_total ?? 0)) {
      const sure = await askConfirm({
        title: '确认清空媒体库',
        message: '注意：你选中的是媒体库里的全部 ' + list.length
          + ' 个文件，删除后媒体库会变空。\n\n确定继续？',
        okText: '仍然删除',
        danger: true,
      })
      if (!sure) return
    }
    setDeleting(true)
    let done = 0
    const fails: string[] = []
    const referenced: MosMediaItem[] = []
    const dropLocal = (p: string) => {
      setSelected((prev) => prev.filter((x) => x !== p))
      setSelectedMeta((prev) => {
        const sm = { ...prev }
        delete sm[p]
        return sm
      })
    }
    for (const m of list) {
      try {
        await mediaDelete(m.path)
        done += 1
        dropLocal(m.path)
      } catch (e: any) {
        // 被数据集引用的单独收集：一次确认后带 force=1 重试，而不是逐个弹框（老 :1279-1281）
        if (e?.status === 409) referenced.push(m)
        else fails.push(m.name + '：' + (e?.message || '删除失败'))
      }
    }
    if (referenced.length) {
      const yes = await askConfirm({
        title: '有文件被数据集引用',
        message: '其中 ' + referenced.length + ' 个文件仍被数据集条目引用：\n'
          + referenced.slice(0, 3).map((m) => m.name).join('\n')
          + (referenced.length > 3 ? '\n… 等 ' + referenced.length + ' 个' : '')
          + '\n\n删除后这些条目会断链。仍然删除这些文件？',
        okText: '仍然删除 ' + referenced.length + ' 个',
        danger: true,
      })
      if (yes) {
        for (const m of referenced) {
          try {
            await mediaDelete(m.path, true)
            done += 1
            dropLocal(m.path)
          } catch (e: any) { fails.push(m.name + '：' + (e?.message || '删除失败')) }
        }
      } else {
        fails.push(referenced.length + ' 个被引用文件已跳过')
      }
    }
    setDeleting(false)
    if (done) message.success('已删除 ' + done + ' 个文件')
    if (fails.length) message.error(fails.length + ' 个删除失败，第一个：' + fails[0])
    await refreshAfterDelete()
  }

  // 批量建标注任务=老 openCreate/createProject :1506-1538：
  // 混类型/文档拦下（模板只对单一模态有意义）；默认名「媒体标注 YYYY-MM-DD」；
  // buildMediaTasks 文本读正文（老教训：只写 URL 标注页看到一串链接 :1526）；成功跳工作台项目页。
  const openCreate = () => {
    if (!selectedItems.length) return
    if (!CREATABLE.has(bulkModality)) {
      message.error('只能选同一类型的音频 / 视频 / 图片 / 文本；文档请先「转可标注」')
      return
    }
    const d = new Date()
    setNewTitle('媒体标注 ' + d.getFullYear() + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0'))
    setTplKey(tplOptions[0] ? tplOptions[0].key : 'custom')
    setCreateOpen(true)
  }

  const createProject = async () => {
    if (!newTitle.trim() || !selectedItems.length) return
    setCreating(true)
    try {
      const tasks = await buildMediaTasks(tplKey, selectedItems)
      const p = await createLsProject({
        title: newTitle.trim(),
        label_config: (TEMPLATES[tplKey] || TEMPLATES.custom).config,
        tasks,
      })
      message.success('已创建标注项目 #' + p.id + '，共 ' + tasks.length + ' 条任务')
      setCreateOpen(false)
      clearSelection()
      // 老 router.push('/label-studio/'+id) :1536；中台工作台路由=mos/label-studio/:pid（App.tsx:200）
      navigate('/mos/label-studio/' + p.id)
    } catch (e: any) {
      message.error(e?.message || '创建标注项目失败')
    } finally {
      setCreating(false)
    }
  }

  // ---------- 审核模式：预览翻页 + 键盘（BUG-PARITY-13-D，老 Media.vue:1015-1099,1593） ----------

  // previewStep（老 :1024-1049）：页内直接切；**越界自动续页**——上一页取末张、下一页取首张，
  // 页没真的换掉（请求失败/被 seq 丢弃）就不瞎跳。await 后用**返回的新列表**，不读旧闭包。
  const stepPreview = async (delta: number): Promise<void> => {
    const s = viewRef.current
    if (!s.pv || pagingRef.current) return // 翻页请求没回来再按 J/K：忽略（老 :1025-1027）
    const i = s.list.findIndex((m) => m.path === s.pv!.path)
    if (i < 0) return
    const n = i + delta
    if (n >= 0 && n < s.list.length) { setPreview(s.list[n]); return }
    const to = n < 0 ? (s.pageNo > 1 ? s.pageNo - 1 : 0) : (s.pageNo < s.pages ? s.pageNo + 1 : 0)
    if (!to) return
    pagingRef.current = true
    try {
      let nextList: MosMediaItem[] | null = null
      if (browse) {
        try { const d = await loadFolder(currentPath, to); nextList = d ? (d.files || []) : null }
        catch (e: any) { message.error(e?.message || '翻页失败') }
      } else {
        const d = await load(to)
        if (d) { nextList = d.items || []; setPage(to) }
      }
      if (!nextList || !nextList.length) return
      const pick = n < 0 ? nextList[nextList.length - 1] : nextList[0]
      if (pick) setPreview(pick)
    } finally {
      pagingRef.current = false
    }
  }
  stepRef.current = stepPreview

  // 预览里 Delete=删当前（老 previewDelete :1075-1114 与卡片删除同一条 removeMedia 链）
  delCurrentRef.current = () => (preview ? removeMedia(preview) : Promise.resolve())

  // 预览「转可标注」→ 先关预览再进转换弹窗（老 previewToRender :1192-1196）
  const previewToRender = (m: MosMediaItem) => { setPreview(null); void renderDoc(m) }

  // 文档转可标注（老 renderDoc :1540-1556）：转换慢，关掉再转另一份**只认最后一次**
  // （renderSeq 守卫，否则 A 的结果挂 B 标题下建出错项目）；body={path,dpi:150}=老 api 同参。
  const renderDoc = async (m: MosMediaItem) => {
    const seq = ++renderSeqRef.current
    setRenderName(m.name)
    setRenderSource(m)
    setRenderData(null)
    setRenderOpen(true)
    setRendering(true)
    try {
      const d = await mediaRender({ path: m.path, dpi: 150 })
      if (seq !== renderSeqRef.current) return
      setRenderData(d)
    } catch (e: any) {
      if (seq !== renderSeqRef.current) return
      message.error(e?.message || '文档转换失败')
      setRenderOpen(false)
    } finally {
      if (seq === renderSeqRef.current) setRendering(false)
    }
  }

  // 建「文档标注 - 文件名」项目（老 createDocProject :1557-1585）：
  // PDF=逐页一条任务 data.image=页图 ls_url + image_box；Word=HTML 正文一条 + doc_hypertext。
  const createDocProject = async () => {
    if (!renderData || !renderSource) return
    setCreatingDoc(true)
    try {
      const tpl = renderData.kind === 'pdf' ? TEMPLATES.image_box : TEMPLATES.doc_hypertext
      const tasks = renderData.kind === 'pdf'
        ? (renderData.pages || []).map((p) => ({ data: { image: p.ls_url, prompt: tpl.prompt } }))
        : [{ data: { doc: sanitizeHtml(renderData.html || ''), prompt: tpl.prompt } }]
      const proj = await createLsProject({
        title: '文档标注 - ' + renderSource.name,
        label_config: tpl.config,
        tasks,
      })
      message.success('已创建标注项目 #' + proj.id + '，共 ' + tasks.length + ' 条任务')
      setRenderOpen(false)
      navigate('/mos/label-studio/' + proj.id)
    } catch (e: any) {
      message.error(e?.message || '创建标注项目失败')
    } finally {
      setCreatingDoc(false)
    }
  }

  // 审核键盘挂 document（老 :1593 注册位；组件卸载/关预览即摘）：
  // Esc 不在这吞——antd Modal keyboard 默认 true，按 Esc 走 onCancel 关窗＝老 Modal 的 Esc 行为。
  useEffect(() => {
    if (!preview) return
    const onKey = (e: KeyboardEvent) => {
      if (confirmDepth > 0) return // 删除确认框开着：按键归确认框，不吃（老 :1084 confirmState）
      if (e.ctrlKey || e.metaKey || e.altKey) return
      const tag = ((e.target as HTMLElement | null)?.tagName || '').toLowerCase()
      // 输入框和播放器保留原生按键：方向键在 <video>/<audio> 上是快进/快退（老 :1087-1090）
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || tag === 'video' || tag === 'audio') return
      if (e.key === 'ArrowRight' || e.key === 'j' || e.key === 'J') { e.preventDefault(); void stepRef.current(1) }
      else if (e.key === 'ArrowLeft' || e.key === 'k' || e.key === 'K') { e.preventDefault(); void stepRef.current(-1) }
      else if (e.key === 'Delete') { e.preventDefault(); void delCurrentRef.current() }
      // 只认 Delete：Backspace 是「后退」的肌肉记忆，误删代价太大（老 :1095 注释）
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [preview])

  // 预览正文（老 preview :1160-1191）：文本类 fetch 前 200KB，JSON/JSONL 尝试美化，截断要提示；
  // PDF 走 iframe 不发 fetch。previewSeq 竞态守卫（老 :1157,1174,1182-1189）：连翻快切
  // 只认最后一次打开的正文，慢回包不得盖到后来者身上。关窗（preview→null）一并清全部预览态，
  // 重开同一文件才会重取而不是挂着旧错误。
  useEffect(() => {
    const m = preview
    const seq = ++previewSeqRef.current
    setPreviewMediaError('')
    setPvReload(0)
    setPreviewText('')
    setPreviewError('')
    setPreviewTruncated(false)
    if (!m || isPdf(m) || !isTextPreview(m)) { setPreviewLoading(false); return }
    setPreviewLoading(true)
    fetch(mosMediaUrl(m.path), { cache: 'no-store' })
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status)
        return r.blob()
      })
      .then(async (blob) => {
        const cap = 200 * 1024
        const truncated = blob.size > cap
        const slice = truncated ? blob.slice(0, cap) : blob
        let txt = await slice.text()
        // 截了断就不算完整 JSON，别美化半截（老 :1177 只在未截断时 try 解析）
        if (['.json', '.jsonl'].includes(extOf(m)) && !truncated) {
          try { txt = JSON.stringify(JSON.parse(txt), null, 2) } catch { /* 不是完整 JSON 就按原文 */ }
        }
        if (seq !== previewSeqRef.current) return
        if (truncated) setPreviewTruncated(true)
        setPreviewText(txt)
      })
      .catch((e: any) => {
        if (seq === previewSeqRef.current) setPreviewError('读取内容失败：' + (e?.message || '未知'))
      })
      .finally(() => {
        if (seq === previewSeqRef.current) setPreviewLoading(false)
      })
  // eslint-disable-next-line react-hooks/exhaustive-deps —— 只跟「打开了哪一个」走
  }, [preview?.path])

  const segOptions = [
    { value: '', label: `全部 ${(data?.all_total ?? 0).toLocaleString()}` },
    ...['image', 'audio', 'video', 'text', 'other'].map((k) => ({
      value: k, label: `${KIND_LABEL[k]} ${(data?.counts?.[k] ?? 0).toLocaleString()}`,
    })),
  ]

  const items = data?.items ?? []
  // 网格数据源（老 fileItems :610-611）：browse=当前层文件（服务端分页），否则=扁平全库列表
  const fileItems = browse ? (currentTree?.files ?? []) : items
  const folderRows = browse ? (currentTree?.folders ?? []) : []

  // 可见树行：根 + 递归展开（老 visibleTree :620-630，depth 决定缩进）
  const visibleTree: (MosMediaFolder & { depth: number })[] = []
  {
    const walk = (nodes: MosMediaFolder[], depth: number) => {
      for (const n of nodes || []) {
        visibleTree.push({ ...n, depth })
        if (expanded[n.path]) walk(childrenByPath[n.path] || [], depth + 1)
      }
    }
    walk(rootFolders, 0)
  }

  // 选择态派生（老 Media.vue:631-639）：批量条文案、模板选项都从这里算
  const selectedSet = new Set(selected)
  const selectedItems = selected.map((p) => selectedMeta[p]).filter(Boolean) as MosMediaItem[]
  const selectedKinds = [...new Set(selectedItems.map((m) => m.kind))]
  const bulkModality = selectedKinds.length === 1 ? selectedKinds[0] : 'mixed'
  const tplOptions = bulkModality === 'mixed'
    ? [{ key: 'custom', ...TEMPLATES.custom }]
    : templatesFor(bulkModality)
  // 并集裁决（发布线 v581 轮）：勾选口径取 13-C 的 fileItems（browse 态下网格即本层文件），
  // storeText 取 REQ-UX-FLOW-1#3（存储真实配置文案），crumbs 取 13-C 面包屑
  const allChecked = fileItems.length > 0 && fileItems.every((m) => selectedSet.has(m.path))
  const someChecked = fileItems.some((m) => selectedSet.has(m.path))
  const crumbs = currentTree?.crumbs ?? [{ name: '全部文件', path: '' }]

  // 预览导航派生（老 :1015-1022）：n/总数=**当前页**可见列表里的位置与条数；
  // 边界禁用要算上「还有上一页/下一页」——越界后 previewStep 会自动续页（老主打卖点）。
  const previewIdx = preview ? fileItems.findIndex((m) => m.path === preview.path) : -1
  const pvPageNow = browse ? folderPage : page
  const pvPageCount = Math.max(1, Math.ceil(((browse ? (currentTree?.files_total ?? 0) : (data?.total ?? 0)) || 0) / pageSize))
  const canPrevPv = previewIdx > 0 || pvPageNow > 1
  const canNextPv = previewIdx >= 0 && (previewIdx < fileItems.length - 1 || pvPageNow < pvPageCount)
  viewRef.current = { pv: preview, list: fileItems, pageNo: pvPageNow, pages: pvPageCount }
  // REQ-UX-FLOW-1#3：页头存储位置文案（禁硬编码桶名，main 侧并保）
  const storeText = (mediaStore === undefined
    ? '文件本体存单源存储（正在探测存储类型…）'
    : mediaStore === null
      ? '读取存储配置失败（GET /api/system/info 不可用），暂无法说明文件本体存放位置'
      : mediaStore.store === 's3'
        ? `文件本体存对象存储单源桶（${mediaStore.bucket}${mediaStore.prefix ? `/${mediaStore.prefix}` : ''}）`
        : '文件本体存平台本地媒体目录（单源）')
    + (mediaStore && mediaStore.ok === false ? `；后端报告存储异常：${mediaStore.error || '未知'}` : '')

  return (
    <div
      data-testid="media-page"
      style={{ position: 'relative' }}
      onDragEnter={onDragEnter}
      onDragOver={(e) => e.preventDefault()}
      onDragLeave={onDragLeave}
      onDrop={onDropPage}
    >
      {/* 页面任意处拖拽遮罩（老 drop-overlay :5-11,1667-1675 等价；pointer-events:none 不挡落点） */}
      {drag && (
        <div
          data-testid="drop-overlay"
          style={{
            position: 'absolute', inset: 0, zIndex: 8, border: '2px dashed #91caff', borderRadius: 8,
            background: 'rgba(240, 247, 255, .88)', display: 'grid', placeItems: 'center', pointerEvents: 'none',
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4 }}>
            <b style={{ fontSize: 18 }}>松开就上传</b>
            <span style={{ fontSize: 12 }}>自动识别类型 · 目标：{browse ? currentPath : '按类型 / 年月归档'}</span>
          </div>
        </div>
      )}
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="媒体库是标注/微调（ModelOneStation）的统一素材源"
        description={`${storeText}，与标注、训练集页共用同一份；此处浏览/上传即时生效。类型计数为全库口径，不随筛选变化。`}
      />

      {/* pick 横幅（老 Media.vue:87-92）：向导跳来挑样本时明示用途，出口回向导 */}
      {isPicking && (
        <div
          data-testid="pick-banner"
          style={{
            display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 12,
            border: '1px solid #91caff', background: '#e6f4ff', borderRadius: 8, padding: '6px 10px',
          }}
        >
          <b>正在为新标注项目选样本</b>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            勾选文件后点右侧「加入新建项目」；项目名和模板回到向导里再确认。
          </Typography.Text>
          <span style={{ flex: 1 }} />
          <Button size="small" data-testid="pick-back" onClick={() => navigate('/mos/labels?create=1')}>返回项目向导</Button>
        </div>
      )}

      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        {/* 左：目录树（老 folder-pane :15-48；右键=目录 CRUD 唯一入口） */}
        <aside style={{ flex: '0 0 240px', width: 240 }} data-testid="folder-pane">
          <Dropdown trigger={['contextMenu']} menu={{ items: dirMenu(null) }}>
            <div>
            <Card size="small" styles={{ body: { padding: 8 } }}>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
                <b>目录</b>
                <span style={{ flex: 1 }} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>右键操作</Typography.Text>
              </div>
              <div
                data-testid="folder-all"
                onClick={() => void showAll()}
                style={{
                  display: 'flex', alignItems: 'center', padding: '4px 6px', borderRadius: 6, cursor: 'pointer',
                  background: !browse ? '#e6f4ff' : 'transparent',
                }}
              >
                <span>▤ 全部文件</span>
                <span style={{ flex: 1 }} />
                <span data-testid="tree-all-total" style={{ fontSize: 12, color: '#999' }}>{data?.all_total ?? 0}</span>
              </div>
              <div data-testid="folder-tree">
                {visibleTree.map((f) => (
                  <Dropdown key={f.path} trigger={['contextMenu']} menu={{ items: dirMenu(f) }}>
                    <div
                      data-testid={`tree-${f.path}`}
                      onContextMenu={(e) => e.stopPropagation()} // 只弹本行菜单，别冒泡成「根目录」菜单
                      style={{
                        display: 'flex', alignItems: 'center', gap: 2, paddingLeft: 6 + f.depth * 14,
                        borderRadius: 6, background: browse && currentPath === f.path ? '#e6f4ff' : 'transparent',
                      }}
                    >
                      <Button
                        type="text" size="small"
                        aria-label={expanded[f.path] ? '收起子目录' : '展开子目录'}
                        disabled={!f.has_children}
                        onClick={(e) => { e.stopPropagation(); toggleExpand(f.path) }}
                        style={{ width: 24, flex: 'none' }}
                      >
                        {f.has_children ? (expanded[f.path] ? '▾' : '▸') : '·'}
                      </Button>
                      <Button
                        type="link" size="small" data-testid={`tree-name-${f.path}`} title={f.path}
                        onClick={() => void enterFolder(f.path)}
                        style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', padding: '2px 4px' }}
                      >
                        {f.name}
                      </Button>
                      <span style={{ fontSize: 12, color: '#999', paddingRight: 4 }}>{f.files}</span>
                    </div>
                  </Dropdown>
                ))}
                {!rootFolders.length && (
                  <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', padding: 6 }}>
                    还没有子目录。在目录区右键 → 新建根目录（手机长按「全部文件」）。
                  </Typography.Text>
                )}
              </div>
              <Typography.Text type="secondary" style={{ fontSize: 11, display: 'block', marginTop: 6 }}>
                右键目录：新建子目录 / 删除空目录；手机长按目录。勾选文件后可批量删除或生成标注任务。
              </Typography.Text>
            </Card>
            </div>
          </Dropdown>
        </aside>

        <section style={{ flex: 1, minWidth: 0 }}>
      {/* 工具条（BUG-PARITY-14）：两种浏览态左端各自（面包屑/筛选），右端共用 视图切换+上传入口 */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
      {browse ? (
        <>
          {/* 面包屑（老 crumbs :53-71）：首项=全部文件，末项当前层不可点 */}
          <div data-testid="crumbs" style={{ display: 'inline-flex', alignItems: 'center', gap: 2, flexWrap: 'wrap' }}>
            {crumbs.map((c, i) => (
              <span key={c.path || 'root'} style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
                {i > 0 && <span style={{ color: '#999', fontSize: 12 }}>/</span>}
                <Button
                  size="small" type={i === crumbs.length - 1 ? 'text' : 'link'}
                  data-testid={`crumb-${c.path || 'root'}`}
                  onClick={() => (i === 0 ? void showAll() : void enterFolder(c.path))}
                >
                  {c.name}
                </Button>
              </span>
            ))}
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="list-count">
            共 {currentTree?.total_files ?? 0} 个
            {currentTree?.files_total ? ` · 本层 ${fileItems.length} / ${currentTree?.files_total} 个` : ''}
          </Typography.Text>
          <span style={{ flex: 1 }} />
          {browse && currentPath && (
            <Button
              size="small" data-testid="pick-folder" disabled={folderPicking}
              title={'选中「' + (currentPath || '') + '」下的全部文件（含子目录）'}
              onClick={() => void pickFolder(currentPath)}
            >
              {folderPicking ? '读取中…' : '选整个文件夹'}
            </Button>
          )}
          <Button size="small" onClick={refreshCurrent}>刷新</Button>
        </>
      ) : (
        <>
          <Segmented
            options={segOptions}
            value={kind}
            onChange={(v) => { setKind(String(v)); setPage(1) }}
          />
          <Input.Search
            allowClear
            placeholder="按路径搜索（回车）"
            style={{ width: 240 }}
            value={inputKw}
            onChange={(e) => setInputKw(e.target.value)}
            onSearch={(v) => { setQ(v.trim()); setPage(1) }}
          />
          <Typography.Text type="secondary">
            {loading ? '加载中…' : `当前筛选 ${data?.total ?? 0} 个 · 每页 ${pageSize}`}
          </Typography.Text>
          <span style={{ flex: 1 }} />
        </>
      )}
      {/* 视图切换 + 上传入口（BUG-PARITY-14；两种布局共用工具条右端） */}
      <Segmented
        size="small"
        data-testid="layout-toggle"
        value={layout}
        onChange={(v) => {
          setLayout(v as 'card' | 'table')
          try { localStorage.setItem('dw_media_layout', String(v)) } catch { /* 存储不可用=不记忆 */ }
        }}
        options={[{ value: 'card', label: '卡片' }, { value: 'table', label: '表格' }]}
      />
      <Button
        size="small" type="primary" data-testid="up-open" disabled={uploading}
        onClick={() => void openUpload()}
      >
        {uploading ? '上传中…' : '＋ 上传文件'}
      </Button>
      </div>

      {/* 批量条（老 media-selbar :93-101 + 表头全选框 :108 的卡片网格等价；勾选后才出动作区。
          目录里有已选/半选时即使本页无文件也要挂条，否则「已选 N 个」计数没处显） */}
      {(fileItems.length > 0 || selected.length > 0) && (
        <div
          data-testid="bulk-bar"
          style={{
            display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap',
            border: '1px solid #d9d9d9', borderRadius: 8, background: '#fafafa',
            padding: '6px 10px', marginBottom: 12,
          }}
        >
          <Checkbox
            data-testid="sel-all"
            checked={allChecked}
            indeterminate={someChecked && !allChecked}
            disabled={allBusy}
            onChange={() => void toggleAll()}
          >
            {allBusy ? '全选读取中…' : '全选'}
          </Checkbox>
          {selected.length > 0 && (
            <>
              <b data-testid="sel-count">已选 {selected.length} 个</b>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {selectedKinds.map((k) => KIND_LABEL[k] || '其他').join(' + ') || '未识别类型'}
              </Typography.Text>
              <Button size="small" data-testid="sel-clear" onClick={clearSelection}>取消选择</Button>
            </>
          )}
          {selected.length > 0 && (
            <>
              <span style={{ flex: 1 }} />
              <Button
                size="small"
                danger
                data-testid="sel-del"
                disabled={deleting}
                onClick={() => void removeSelected()}
              >
                {deleting ? '删除中…' : '批量删除'}
              </Button>
              <Button size="small" data-testid="sel-create" onClick={openCreate}>
                生成标注任务
              </Button>
              {/* 加入新建项目（老 :101,1500-1504）：勾选样本经 sessionStorage 交给新建向导 */}
              <Button size="small" type="primary" data-testid="sel-wizard" onClick={sendToWizard}>
                加入新建项目
              </Button>
            </>
          )}
        </div>
      )}

      {error ? (
        <ErrorState
          title={error?.status === 403 ? '没有权限' : '媒体库加载失败'}
          message={error?.status === 403
            ? '当前账号没有媒体库权限（mos:media）。请联系管理员在角色页勾选，或提交权限申请。'
            : (error?.message || '未知错误')}
          onRetry={() => (browse ? void enterFolder(currentPath) : void load())}
        />
      ) : (
        <Spin spinning={loading}>
          {loading && fileItems.length === 0 && folderRows.length === 0 ? (
            <LoadingState rows={4} />
          ) : fileItems.length === 0 && folderRows.length === 0 ? (
            <EmptyState
              title={browse ? '这个目录是空的' : (q || kind ? '没有匹配的媒体' : '媒体库还是空的')}
              hint={browse ? '可右键建子目录，或把文件拖到本页任意处上传'
                : (q || kind ? '换个关键词，或切回「全部」类型' : '把文件拖到本页任意处，或点右上「＋ 上传文件」入库')}
            />
          ) : layout === 'table' ? (
            /* 表格视图（老 Media.vue:104-177）：列=文件(名+路径)/类型/大小/上传时间/操作；
               ≤1400px 收 类型/大小/时间 三列，改由文件副行承接、路径行让位（老 :1789-1807 同款响应式）。 */
            <div data-testid="media-table" style={{ overflowX: 'auto', border: '1px solid #f0f0f0', borderRadius: 8 }}>
              <style>{`
                .media-table-el { width: 100%; min-width: 900px; table-layout: fixed; border-collapse: collapse; font-size: 13px; }
                .media-table-el th, .media-table-el td { padding: 6px 8px; text-align: left; vertical-align: middle; border-top: 1px solid #f0f0f0; }
                .media-table-el thead th { border-top: none; background: #fafafa; font-weight: 600; }
                .media-table-el .mt-check { width: 40px; }
                .media-table-el .mt-ops { width: 220px; white-space: nowrap; }
                .media-table-el .media-table-sub { display: none; }
                @media (max-width: 1400px) {
                  .media-table-el { min-width: 0; }
                  .media-table-el .col-hide { display: none; }
                  .media-table-el .media-table-path { display: none; }
                  .media-table-el .media-table-sub { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-top: 3px; font-size: 11px; color: #8c8c8c; }
                }
              `}</style>
              <table className="media-table-el">
                <thead>
                  <tr>
                    <th className="mt-check">
                      <Checkbox
                        data-testid="t-sel-all"
                        checked={allChecked}
                        indeterminate={someChecked && !allChecked}
                        disabled={allBusy}
                        onChange={() => void toggleAll()}
                      />
                    </th>
                    <th>文件</th>
                    <th className="col-hide" style={{ width: 100 }}>类型</th>
                    <th className="col-hide" style={{ width: 100 }}>大小</th>
                    <th className="col-hide" style={{ width: 150 }}>上传时间</th>
                    <th className="mt-ops">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {folderRows.map((f) => (
                    <tr key={'td-' + f.path} data-testid={`trow-${f.path}`}>
                      <td>
                        <Checkbox
                          data-testid={`dir-pick-${f.path}`}
                          checked={folderState(f) === 'all'}
                          indeterminate={folderState(f) === 'some'}
                          disabled={!f.files || folderBusy === f.path}
                          title={f.files ? '选中整个文件夹（含子目录）' : '空文件夹'}
                          onChange={() => void toggleFolderPick(f)}
                        />
                      </td>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                          <span style={{ fontSize: 20, color: '#d4a017', flex: 'none' }}><FolderOutlined /></span>
                          <div style={{ minWidth: 0 }}>
                            <Button
                              type="link" size="small" data-testid={`tname-${f.path}`} title={f.path}
                              onClick={() => void enterFolder(f.path)}
                              style={{ padding: 0, height: 'auto', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'block' }}
                            >
                              {f.name}
                            </Button>
                            <div style={{ fontSize: 12, color: '#8c8c8c' }}>{f.files} 个文件</div>
                          </div>
                        </div>
                      </td>
                      <td className="col-hide"><Tag>目录</Tag></td>
                      <td className="col-hide" style={{ fontFamily: 'ui-monospace, Consolas, monospace', whiteSpace: 'nowrap' }}>{fmtSize(f.size)}</td>
                      <td className="col-hide" style={{ fontSize: 12, color: '#8c8c8c', whiteSpace: 'nowrap' }}>{f.mtime || '—'}</td>
                      <td className="mt-ops">
                        <Button size="small" data-testid={`topen-${f.path}`} onClick={() => void enterFolder(f.path)}>打开</Button>
                      </td>
                    </tr>
                  ))}
                  {fileItems.map((m) => (
                    <tr key={m.path} data-testid={`trow-${m.path}`} style={{ background: selectedSet.has(m.path) ? '#f0f7ff' : undefined }}>
                      <td>
                        <Checkbox
                          data-testid={`tpick-${m.path}`}
                          checked={selectedSet.has(m.path)}
                          onChange={() => togglePick(m)}
                        />
                      </td>
                      <td>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
                          <span
                            data-testid={`tthumb-${m.path}`}
                            title={'预览 ' + m.name}
                            onClick={() => setPreview(m)}
                            style={{ width: 44, height: 36, flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden', cursor: 'pointer', background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 4 }}
                          >
                            {m.kind === 'image' && !thumbFailed[m.path] ? (
                              <img
                                src={mosMediaThumbUrl(m.path)}
                                alt={m.name}
                                loading="lazy"
                                onError={() => setThumbFailed((p) => ({ ...p, [m.path]: true }))}
                                style={{ objectFit: 'cover', width: '100%', height: '100%' }}
                              />
                            ) : (
                              <span style={{ fontSize: 15, color: '#8c8c8c' }}>{KIND_ICON[m.kind] || KIND_ICON.other}</span>
                            )}
                          </span>
                          <div style={{ minWidth: 0 }}>
                            <Button
                              type="link" size="small" data-testid={`tname-${m.path}`} title={m.name}
                              onClick={() => setPreview(m)}
                              style={{ padding: 0, height: 'auto', maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', display: 'block' }}
                            >
                              {m.name}
                            </Button>
                            <div
                              className="media-table-path" data-testid={`tpath-${m.path}`} title={m.path}
                              style={{ fontSize: 11, color: '#8c8c8c', fontFamily: 'ui-monospace, Consolas, monospace', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                            >
                              {m.path}
                            </div>
                            <div className="media-table-sub">
                              <span>{KIND_LABEL[m.kind] || m.kind}</span>
                              <span>{fmtSize(m.size)}</span>
                              <span>{m.mtime}</span>
                            </div>
                          </div>
                        </div>
                      </td>
                      <td className="col-hide"><Tag color={KIND_COLOR[m.kind] || 'default'}>{KIND_LABEL[m.kind] || m.kind}</Tag></td>
                      <td className="col-hide" style={{ fontFamily: 'ui-monospace, Consolas, monospace', whiteSpace: 'nowrap' }}>{fmtSize(m.size)}</td>
                      <td className="col-hide" style={{ fontSize: 12, color: '#8c8c8c', whiteSpace: 'nowrap' }}>{m.mtime}</td>
                      <td className="mt-ops">
                        <Button size="small" type="text" data-testid={`tprev-${m.path}`} onClick={() => setPreview(m)}>预览</Button>
                        {isDoc(m) && !isWord(m) && (
                          <Button size="small" data-testid={`trender-${m.path}`} onClick={() => void renderDoc(m)}>转可标注</Button>
                        )}
                        <Button
                          size="small" type="text" title="复制标注可用地址" data-testid={`tcopy-${m.path}`}
                          onClick={() => void copyText(m.ls_url)}
                        >
                          复制
                        </Button>
                        <Button
                          size="small" type="text" danger disabled={busyPath === m.path} data-testid={`tdel-${m.path}`}
                          onClick={() => void removeMedia(m)}
                        >
                          删除
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 12 }}>
              {/* 目录行只在「这一层」出现（老 folderRows :642-643）：勾选框=整目录递归半选（13-C） */}
              {folderRows.map((f) => (
                <Dropdown key={'d-' + f.path} trigger={['contextMenu']} menu={{ items: dirMenu(f) }}>
                  <Card
                    data-testid={`dir-card-${f.path}`}
                    size="small"
                    hoverable
                    cover={(
                      <div
                        style={{ position: 'relative', height: 120, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#fafafa', cursor: 'pointer' }}
                        onClick={() => void enterFolder(f.path)}
                      >
                        <span style={{ position: 'absolute', top: 6, left: 6, zIndex: 2 }} onClick={(e) => e.stopPropagation()}>
                          <Checkbox
                            data-testid={`dir-pick-${f.path}`}
                            checked={folderState(f) === 'all'}
                            indeterminate={folderState(f) === 'some'}
                            disabled={!f.files || folderBusy === f.path}
                            title={f.files ? '选中整个文件夹（含子目录）' : '空文件夹'}
                            onChange={() => void toggleFolderPick(f)}
                          />
                        </span>
                        <span style={{ fontSize: 40, color: '#d4a017' }}><FolderOutlined /></span>
                      </div>
                    )}
                    actions={[
                      <Button key="open" type="link" size="small" data-testid={`dir-open-${f.path}`} onClick={() => void enterFolder(f.path)}>
                        打开
                      </Button>,
                      ...(f.has_children ? [<span key="hc" style={{ fontSize: 12, color: '#999' }}>含子目录</span>] : []),
                    ]}
                  >
                    <Card.Meta
                      title={(
                        <Typography.Text ellipsis={{ tooltip: f.path }} style={{ fontSize: 13 }}>
                          {f.name}
                        </Typography.Text>
                      )}
                      description={(
                        <Space size={4} wrap>
                          <Tag>目录</Tag>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{f.files} 个文件</Typography.Text>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fmtSize(f.size)}</Typography.Text>
                        </Space>
                      )}
                    />
                  </Card>
                </Dropdown>
              ))}
              {fileItems.map((m) => (
                <Card
                  key={m.path}
                  size="small"
                  hoverable
                  actions={[
                    <Button
                      key="copy"
                      type="link"
                      size="small"
                      title="复制标注可用地址"
                      data-testid={`copy-${m.path}`}
                      onClick={() => void copyText(m.ls_url)}
                    >
                      复制
                    </Button>,
                    <Button
                      key="del"
                      type="link"
                      size="small"
                      danger
                      disabled={busyPath === m.path}
                      data-testid={`del-${m.path}`}
                      onClick={() => void removeMedia(m)}
                    >
                      删除
                    </Button>,
                  ]}
                  cover={(
                    <div
                      data-testid={`cover-${m.path}`}
                      style={{ position: 'relative', height: 120, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#fafafa', overflow: 'hidden', cursor: 'pointer' }}
                      onClick={() => setPreview(m)}
                    >
                      {/* 勾选框盖在封面左上（老表头 col-check 的网格等价）；stopPropagation 防点开预览 */}
                      <span style={{ position: 'absolute', top: 6, left: 6, zIndex: 2 }} onClick={(e) => e.stopPropagation()}>
                        <Checkbox
                          data-testid={`pick-${m.path}`}
                          checked={selectedSet.has(m.path)}
                          onChange={() => togglePick(m)}
                        />
                      </span>
                      {m.kind === 'image' && !thumbFailed[m.path] ? (
                        <img
                          src={mosMediaThumbUrl(m.path)}
                          alt={m.name}
                          loading="lazy"
                          style={{ objectFit: 'cover', width: '100%', height: 120 }}
                          onError={() => setThumbFailed((p) => ({ ...p, [m.path]: true }))}
                        />
                      ) : (
                        <span style={{ fontSize: 40, color: '#8c8c8c' }}>{KIND_ICON[m.kind] || KIND_ICON.other}</span>
                      )}
                    </div>
                  )}
                >
                  <Card.Meta
                    title={(
                      <Typography.Text ellipsis={{ tooltip: m.path }} style={{ fontSize: 13 }}>
                        {m.name}
                      </Typography.Text>
                    )}
                    description={(
                      <Space size={4} wrap>
                        <Tag color={KIND_COLOR[m.kind] || 'default'}>{KIND_LABEL[m.kind] || m.kind}</Tag>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fmtSize(m.size)}</Typography.Text>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{m.mtime}</Typography.Text>
                      </Space>
                    )}
                  />
                </Card>
              ))}
            </div>
          )}
          {(browse ? (currentTree?.files_total ?? 0) : (data?.total ?? 0)) > 0 && (
            /* BUG-PARITY-15 ②：分页条口径改「有总数就在场」（Pager.vue:2 v-if="total > 0"），
               吸底+总数+每页全局偏好（§五:113——固定 60 且 showSizeChanger=false 属约定回归） */
            <div style={{ textAlign: 'right', marginTop: 12 }} className="dw-sticky-pager" data-testid={browse ? 'dir-pager' : 'flat-pager'}>
              <Pagination
                {...listPagination({
                  current: browse ? folderPage : page,
                  total: browse ? (currentTree?.files_total ?? 0) : (data?.total ?? 0),
                  size: pageSize,
                  label: '个',
                  onChange: (p, s) => {
                    setPageSize(s)
                    if (browse) void loadFolder(currentPath, p).catch((e: any) => message.error(e?.message || '加载目录失败'))
                    else setPage(p)
                  },
                })}
              />
            </div>
          )}
        </Spin>
      )}
        </section>
      </div>

      <Modal
        open={!!preview}
        title={preview?.name}
        width={720}
        onCancel={() => setPreview(null)}
        destroyOnClose
        footer={preview ? (
          // 老 footer :336-350 顺序：新窗口打开 · 转可标注(doc 且非 word=pdf 直转) ·
          // [← 上一张 | n / 总数 | 下一张 →] · 删除当前 · 关闭 · 复制标注地址
          <Space wrap>
            <Button data-testid="pv-open-new" href={platformUrl(preview.path)} target="_blank" rel="noreferrer">
              新窗口打开
            </Button>
            {isDoc(preview) && !isWord(preview) && (
              <Button data-testid="pv-to-render" onClick={() => previewToRender(preview)}>转可标注</Button>
            )}
            <span data-testid="pv-nav" style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
              <Button size="small" data-testid="pv-prev" disabled={!canPrevPv} onClick={() => void stepPreview(-1)}>
                ← 上一张
              </Button>
              <span data-testid="pv-count" style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12, color: '#8c8c8c' }}>
                {previewIdx + 1} / {fileItems.length}
              </span>
              <Button size="small" data-testid="pv-next" disabled={!canNextPv} onClick={() => void stepPreview(1)}>
                下一张 →
              </Button>
            </span>
            <Button danger data-testid="pv-del" disabled={busyPath === preview.path} onClick={() => void removeMedia(preview)}>
              删除当前
            </Button>
            <Button data-testid="pv-close" onClick={() => setPreview(null)}>关闭</Button>
            <Button type="primary" data-testid="pv-copy" onClick={() => void copyText(preview.ls_url)}>复制标注地址</Button>
          </Space>
        ) : null}
      >
        {preview && (
          <>
            <Space size={4} wrap style={{ marginBottom: 8 }}>
              <Tag color={KIND_COLOR[preview.kind] || 'default'}>{KIND_LABEL[preview.kind] || preview.kind}</Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{fmtSize(preview.size)}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{preview.mtime}</Typography.Text>
            </Space>
            <div data-testid="preview-meta" style={{ marginBottom: 10 }}>
              <Kv label="相对路径">
                <Typography.Text code copyable={{ text: preview.path }}>{preview.path}</Typography.Text>
              </Kv>
              <Kv label="平台地址">
                <Typography.Text copyable={{ text: platformUrl(preview.path) }}>{platformUrl(preview.path)}</Typography.Text>
              </Kv>
              <Kv label="标注可用地址">
                <Typography.Text copyable={{ text: preview.ls_url }}>{preview.ls_url}</Typography.Text>
              </Kv>
            </div>
            {/* 媒体挂了要有出口，不能只留破图黑屏（老 pv-media-bad :291-297,1145-1154）：
                「可能已被删除或移动」+ 重试（防缓存瞬时参数）+ 关闭 */}
            {previewMediaError ? (
              <div
                data-testid="pv-media-bad"
                style={{ border: '1px solid #ffccc7', background: '#fff2f0', borderRadius: 8, padding: '10px 12px' }}
              >
                <b>媒体文件无法加载</b>
                <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 2 }}>{previewMediaError}</div>
                <Space style={{ marginTop: 8 }}>
                  <Button size="small" data-testid="pv-retry" onClick={retryPreviewMedia}>重试</Button>
                  <Button size="small" data-testid="pv-bad-close" onClick={() => setPreview(null)}>关闭</Button>
                </Space>
              </div>
            ) : (
              <>
            {preview.kind === 'image' && (
              <div style={{ textAlign: 'center' }}>
                <img src={pvSrc(preview)} alt={preview.name} onError={onPreviewMediaError} style={{ maxWidth: '100%' }} />
              </div>
            )}
            {preview.kind === 'audio' && (
              <audio controls src={pvSrc(preview)} onError={onPreviewMediaError} style={{ width: '100%' }} />
            )}
            {preview.kind === 'video' && (
              <video controls src={pvSrc(preview)} onError={onPreviewMediaError} style={{ width: '100%', maxHeight: 480, background: '#000' }} />
            )}
            {isPdf(preview) && (
              // PDF 内嵌（老 :307-309 iframe .pv-frame 62vh）：静态读穿通道地址浏览器可直渲
              <iframe
                data-testid="pv-frame"
                src={pvSrc(preview)}
                title={preview.name}
                style={{ width: '100%', height: '62vh', border: '1px solid #f0f0f0' }}
              />
            )}
            {!isPdf(preview) && isTextPreview(preview) && (
              // 文本内嵌（老 :310-315,1163-1191）：前 200KB；JSON/JSONL 能解析就美化；读失败给原文
              previewLoading ? (
                <div data-testid="pv-loading" style={{ color: '#8c8c8c', padding: '12px 0' }}>正在读取内容…</div>
              ) : previewError ? (
                <Alert data-testid="pv-text-err" type="error" showIcon message={previewError} />
              ) : (
                <pre
                  data-testid="pv-text"
                  style={{ maxHeight: '56vh', overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: '#fafafa', border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, fontSize: 12 }}
                >
                  {previewText}
                </pre>
              )
            )}
            {previewTruncated && (
              <div data-testid="pv-trunc" style={{ color: '#8c8c8c', fontSize: 12, marginTop: 4 }}>
                文件较大，只显示前 200 KB。
              </div>
            )}
              </>
            )}
            {isWord(preview) && (
              // 老 :316-320：浏览器不能直显 .doc/.docx，给「转可标注并查看内容」入口（⑧的 Word 引导）
              <div data-testid="pv-word" style={{ padding: '12px 0' }}>
                <Typography.Text strong>Word 文档</Typography.Text>
                <div style={{ color: '#8c8c8c', fontSize: 12, margin: '4px 0 8px' }}>
                  浏览器不能直接显示 .doc / .docx。转成可标注页面后可以看全文，并逐段批注。
                </div>
                <Button type="primary" size="small" data-testid="word-to-render" onClick={() => previewToRender(preview)}>
                  转可标注并查看内容
                </Button>
              </div>
            )}
            {preview.kind !== 'image' && preview.kind !== 'audio' && preview.kind !== 'video'
              && !isWord(preview) && !isPdf(preview) && !isTextPreview(preview) && (
              <Alert
                type="info"
                showIcon
                message="该类型不支持内嵌预览"
                description={(
                  <Typography.Link href={mosMediaUrl(preview.path)} target="_blank" rel="noreferrer">
                    在新窗口打开原文件
                  </Typography.Link>
                )}
              />
            )}
            {/* 键位提示条（老 :328 逐字）：审核模式的卖点要让用户看见才会用 */}
            <div data-testid="pv-keys" style={{ color: '#8c8c8c', fontSize: 12, marginTop: 10 }}>
              ← / → 或 J / K 切换上一张 / 下一张 · Delete 删除当前 · Esc 关闭
            </div>
          </>
        )}
      </Modal>

      {/* 上传弹窗（BUG-PARITY-14，老 Media.vue:218-283）：目标目录 + 媒体类型都在这里选，不占页面；
          「单个 ≤ 500 MB」是明数（老 :225；上限=后端 MEDIA_MAX_MB 默认 500，超限服务端 413 回显真数）。 */}
      <Modal
        open={uploadOpen}
        title="上传文件"
        width={560}
        onCancel={() => setUploadOpen(false)}
        footer={(
          <Space>
            <Button disabled={uploading} onClick={() => setUploadOpen(false)}>取消</Button>
            <Button
              type="primary"
              data-testid="up-confirm"
              disabled={uploading || !uploadPicked.length || (uploadFolderSel === '__new__' && !uploadNewFolder.trim())}
              onClick={() => void confirmUpload()}
            >
              {uploading ? '上传中…' : `上传 ${uploadPicked.length} 个文件`}
            </Button>
          </Space>
        )}
      >
        <div style={{ display: 'grid', gap: 12 }}>
          <div
            data-testid="up-pick"
            title="点击选择文件，或把文件拖到这里"
            onClick={() => upInputRef.current?.click()}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault()
              setUploadPicked(Array.from(e.dataTransfer.files || []))
            }}
            style={{ border: '1px dashed #d9d9d9', borderRadius: 8, padding: 16, background: '#fbfcfe', cursor: 'pointer', textAlign: 'left' }}
          >
            <b>{uploadPicked.length ? `已选 ${uploadPicked.length} 个文件` : '点击选择文件，或把文件拖到这里'}</b>
            <div style={{ color: '#8c8c8c', fontSize: 12, marginTop: 3 }} data-testid="up-limit-hint">
              支持音频 / 视频 / 图片 / 文档；单个 ≤ 500 MB，可多选
            </div>
            <input
              ref={upInputRef}
              type="file"
              multiple
              style={{ display: 'none' }}
              data-testid="up-input"
              onChange={(e) => {
                setUploadPicked(Array.from(e.target.files || []))
                e.target.value = ''
              }}
            />
          </div>
          {uploadPicked.length > 0 && (
            <div data-testid="up-list" style={{ display: 'flex', flexWrap: 'wrap', gap: 5, maxHeight: 92, overflow: 'auto' }}>
              {uploadPicked.map((f) => (
                <span
                  key={f.name + ':' + f.size}
                  data-testid="up-chip"
                  style={{ fontSize: 11.5, border: '1px solid #d9d9d9', borderRadius: 10, padding: '2px 8px', maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                >
                  {f.name}
                </span>
              ))}
            </div>
          )}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <div style={{ marginBottom: 4 }}>上传到</div>
              <Select
                style={{ width: '100%' }}
                data-testid="up-folder"
                value={uploadFolderSel}
                onChange={(v) => setUploadFolderSel(String(v))}
                options={[
                  { value: '__auto__', label: '自动归档（按类型 / 年月）' },
                  { value: '__root__', label: '媒体库根目录' },
                  ...uploadDirs.map((d) => ({ value: d, label: d })),
                  { value: '__new__', label: '＋ 新建目录…' },
                ]}
              />
              <div style={{ marginTop: 4 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }} data-testid="up-folder-hint">
                  {uploadFolderSel === '__auto__' ? '不选目录时按类型 / 年月自动归档。'
                    : uploadFolderSel === '__new__'
                      ? `建在当前目录「${browse ? currentPath : '媒体库根目录'}」下，上传时自动创建。`
                      : '文件直接放进这个目录，不再套类型 / 年月。'}
                </Typography.Text>
              </div>
            </div>
            <div>
              <div style={{ marginBottom: 4 }}>媒体类型</div>
              <Select
                style={{ width: '100%' }}
                data-testid="up-kind"
                value={uploadKind}
                onChange={(v) => setUploadKind(String(v))}
                options={[
                  { value: 'auto', label: '自动识别（推荐）' },
                  { value: 'audio', label: '音频' },
                  { value: 'video', label: '视频' },
                  { value: 'image', label: '图片' },
                  { value: 'text', label: '文本' },
                  { value: 'other', label: '其他' },
                ]}
              />
              <div style={{ marginTop: 4 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>只有后缀识别不出来时才需要手动选。</Typography.Text>
              </div>
            </div>
          </div>
          {uploadFolderSel === '__new__' && (
            <div>
              <div style={{ marginBottom: 4 }}>新目录路径</div>
              <Input
                data-testid="up-newfolder"
                value={uploadNewFolder}
                onChange={(e) => setUploadNewFolder(e.target.value)}
                placeholder="例如：客服录音/2026-10（可多级）"
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                写相对当前目录的子路径，用 / 分隔多级，如 客服/2026/10；不存在的层级会自动创建。
              </Typography.Text>
            </div>
          )}
          {uploading && (
            <div data-testid="up-progress" style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ flex: 1, maxWidth: 340, height: 6, background: '#f0f0f0', borderRadius: 3, overflow: 'hidden' }}>
                <div
                  data-testid="up-bar"
                  style={{ height: '100%', width: `${Math.round(uploadDone / Math.max(1, uploadTotal) * 100)}%`, background: '#1677ff', transition: 'width .2s' }}
                />
              </div>
              <span data-testid="up-count" style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}>
                正在上传 {uploadDone} / {uploadTotal}
              </span>
            </div>
          )}
        </div>
      </Modal>

      {/* 文档转可标注弹窗（老 :353-381）：PDF 逐页预览 / Word HTML 预览，确认后建「文档标注 - 文件名」项目 */}
      <Modal
        open={renderOpen}
        title={renderName + ' · 转可标注'}
        width={860}
        onCancel={() => setRenderOpen(false)}
        footer={(
          <Space>
            <Button data-testid="doc-close" disabled={creatingDoc} onClick={() => setRenderOpen(false)}>关闭</Button>
            <Button
              type="primary"
              data-testid="doc-create"
              disabled={!renderData || creatingDoc}
              onClick={() => void createDocProject()}
            >
              {creatingDoc ? '创建中…' : '创建标注项目'}
            </Button>
          </Space>
        )}
      >
        {rendering && (
          <div data-testid="doc-rendering" style={{ padding: '24px 0', color: '#8c8c8c' }}>正在转换…</div>
        )}
        {!rendering && renderData && (
          <>
            {renderData.kind === 'pdf' ? (
              <div data-testid="doc-pages" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(160px,1fr))', gap: 10 }}>
                {(renderData.pages || []).map((p) => (
                  <div key={p.path} style={{ textAlign: 'center' }}>
                    <img src={mosMediaUrl(p.path)} alt={`第 ${p.page} 页`} style={{ maxWidth: '100%', maxHeight: 220, border: '1px solid #f0f0f0' }} />
                    <div style={{ fontSize: 12, color: '#8c8c8c' }}>第 {p.page} 页</div>
                  </div>
                ))}
              </div>
            ) : (
              <div
                data-testid="doc-html"
                style={{ maxHeight: 420, overflow: 'auto', border: '1px solid #f0f0f0', padding: 8 }}
                dangerouslySetInnerHTML={{ __html: sanitizeHtml(renderData.html || '') }}
              />
            )}
            <Alert
              data-testid="doc-callout"
              style={{ marginTop: 12 }}
              type="info"
              message={renderData.kind === 'pdf'
                ? `PDF 共 ${renderData.total_pages ?? 0} 页，已转 ${renderData.rendered ?? 0} 页图片；建项目后每页一条任务，可框选区域。`
                : `Word 已转成 HTML（${(renderData.warnings || []).length} 条转换提示）；建项目后用 HyperText 阅读并写批注。`}
            />
          </>
        )}
      </Modal>

      {/* 从媒体生成标注任务（老 Media.vue:431-469）：文案/字段/提示逐句对齐 */}
      <Modal
        open={createOpen}
        title="从媒体生成标注任务"
        width={720}
        onCancel={() => setCreateOpen(false)}
        footer={(
          <Space>
            <Button disabled={creating} onClick={() => setCreateOpen(false)}>取消</Button>
            <Button
              type="primary"
              data-testid="create-go"
              disabled={creating || !newTitle.trim()}
              onClick={() => void createProject()}
            >
              {creating ? '创建中…' : '创建项目并导入任务'}
            </Button>
          </Space>
        )}
      >
        <div style={{ display: 'grid', gap: 12 }}>
          <div>
            <div style={{ marginBottom: 4 }}>项目名</div>
            <Input
              data-testid="new-title"
              value={newTitle}
              onChange={(e) => setNewTitle(e.target.value)}
              placeholder="例如：客服录音-第一批"
            />
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>标注模板</div>
            <Select
              style={{ width: '100%' }}
              data-testid="tpl-select"
              value={tplKey}
              onChange={(v) => setTplKey(String(v))}
              options={tplOptions.map((t) => ({ value: t.key, label: t.label }))}
            />
            <div style={{ marginTop: 4 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {(TEMPLATES[tplKey] || TEMPLATES.custom).hint}
              </Typography.Text>
            </div>
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>已选媒体（{selectedItems.length} 个）</div>
            <div
              data-testid="sel-list"
              style={{ maxHeight: 160, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: '4px 8px' }}
            >
              {selectedItems.map((m) => (
                <div key={m.path} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                  <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>{m.name}</span>
                  <Typography.Text type="secondary">{m.kind}</Typography.Text>
                </div>
              ))}
            </div>
            {bulkModality === 'mixed' && (
              <div>
                <Typography.Text type="danger" style={{ fontSize: 12 }}>
                  选中的媒体类型不一致（音频 + 视频）。批量生成任务只能选「自定义」，建议按类型分批。
                </Typography.Text>
              </div>
            )}
          </div>
          <Alert
            type="info"
            showIcon
            message={`会创建 1 个标注项目 + ${selectedItems.length} 条任务；媒体路径自动写进任务，创建后直接进平台标注工作台。`}
          />
        </div>
      </Modal>

      {/* 新建目录（老 Media.vue:383-405）：入口统一在右键菜单；多级名一次建完 */}
      <Modal
        open={dirModal.open}
        title={dirModal.parent ? '新建子目录' : '新建根目录'}
        width={480}
        onCancel={() => setDirModal({ open: false, parent: '' })}
        footer={(
          <Space>
            <Button onClick={() => setDirModal({ open: false, parent: '' })}>取消</Button>
            <Button
              type="primary"
              data-testid="dir-create-go"
              disabled={dirCreating || !dirName.trim()}
              onClick={() => void createFolder()}
            >
              {dirCreating ? '创建中…' : '创建目录'}
            </Button>
          </Space>
        )}
      >
        <div style={{ display: 'grid', gap: 10 }}>
          <div>
            <div style={{ marginBottom: 4 }}>位置</div>
            <div data-testid="dir-where" style={{ border: '1px solid #d9d9d9', borderRadius: 6, padding: '4px 10px', fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}>
              {dirModal.parent || '媒体库根目录'}
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>在任意目录上右键 →「新建子目录」就会建在它下面。</Typography.Text>
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>目录名</div>
            <Input
              data-testid="dir-name"
              autoFocus
              value={dirName}
              onChange={(e) => setDirName(e.target.value)}
              placeholder="例如：客服录音/2026-10"
              onPressEnter={() => void createFolder()}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>用 / 分隔可一次建多级，如 客服/2026/10；不存在的上级也会一起创建。</Typography.Text>
          </div>
        </div>
      </Modal>

      {/* 重命名目录（老 :407-430）：只改当前一级，里面的内容跟着走 */}
      <Modal
        open={!!renameTarget}
        title="重命名目录"
        width={480}
        onCancel={() => setRenameTarget(null)}
        footer={(
          <Space>
            <Button onClick={() => setRenameTarget(null)}>取消</Button>
            <Button
              type="primary"
              data-testid="dir-rename-go"
              disabled={renaming || !renameName.trim() || !renameTarget}
              onClick={() => void doRename()}
            >
              {renaming ? '重命名中…' : '保存新名字'}
            </Button>
          </Space>
        )}
      >
        <div style={{ display: 'grid', gap: 10 }}>
          <div>
            <div style={{ marginBottom: 4 }}>原目录</div>
            <div data-testid="rename-old" style={{ border: '1px solid #d9d9d9', borderRadius: 6, padding: '4px 10px', fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}>
              {renameTarget?.path || ''}
            </div>
          </div>
          <div>
            <div style={{ marginBottom: 4 }}>新名字</div>
            <Input
              data-testid="rename-name"
              autoFocus
              value={renameName}
              onChange={(e) => setRenameName(e.target.value)}
              placeholder="例如：2026-10 客服录音"
              onPressEnter={() => void doRename()}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>只改当前这一级；里面的文件和子目录会一起跟着改名后的目录走。</Typography.Text>
          </div>
        </div>
      </Modal>
    </div>
  )
}
