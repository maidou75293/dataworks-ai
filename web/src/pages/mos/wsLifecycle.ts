/**
 * wsLifecycle.ts —— 工作台壳 + 队列 + 任务生命周期的纯状态机（REQ-UNIFY-6-1）。
 *
 * 来源对照（LabelStudioWorkbench.vue，只读）：loadShell:3830 / loadStats:4092 /
 * loadTasks:4134 / pick:4179 / setFilter:4224 / save:4255 / skip:4388 /
 * navigate:4414 / confirmDiscard:2057 / beforeUnload:4627 / onKey:4634 /
 * 草稿 offerDraft:2138、persistDraftNow:2101、scanDrafts:2079 / sync:4558。
 *
 * 设计：不依赖 React/DOM（transport/storage/确认框注入），
 * unify6-shell-test.py 直接在 node 里断言「键事件序列 → 状态迁移」与 Vue 原件一致。
 * UI 态（queueOpen/queueClosed、弹窗开关）保留最小标志位供分发守卫与断言。
 */

import {
  WsControls, WsState, deriveControls, emptyState, resetAnnotation, snapshotOf,
  isDirty, canSaveOf, emptyAllowedOf, buildResult,
} from './wsSchema'
import type { WsSchema } from './wsSchema'
// BUG-LABEL-WRITE-403-1②：只读态写闸（同为纯模块，不破「不依赖 React/DOM」口径；
// 默认 unknown，闸2 的 fake transport 注入测试不受影响）
import { WRITE_BLOCKED_TEXT, getWriteAccess } from '../../components/ws/wsAccess'
// BUG-PARITY-4：浏览器前进/后退的离开询问挂在页面级模块（wsLeaveGuard），本文件只在
// 「改了一笔 / 同意离开 / 撤销后退」三个时点给它递状态。该模块的 DOM 触点全在函数里，
// node 侧纯状态机测试（unify6-shell-test.py）不 import 它的导出即不受影响，本文件的
// 「不依赖 React/DOM」口径仍然守得住（这里 import 的是两个纯函数，不调用就不碰 window）。
import { armMosLeaveGuard, disarmMosLeaveGuard } from './wsLeaveGuard'

// ---------- 注入面 ----------

export interface Transport {
  lsConfig(): Promise<any>
  lsProject(pid: number): Promise<any>
  lsStats(pid: number, mine?: 0 | 1): Promise<any>
  lsTasks(pid: number, status: string, limit: number, offset: number, q: string,
          mine: 0 | 1, after: number): Promise<any>
  lsTask(pid: number, tid: number): Promise<any>
  lsAnnotate(pid: number, tid: number, body: Record<string, unknown>): Promise<any>
  datasets(): Promise<any[]>
  lsSyncMap(): Promise<any>
  lsToDataset(pid: number, body: Record<string, unknown>): Promise<any>
}

export interface WsStorage {
  getItem(k: string): string | null
  setItem(k: string, v: string): void
  removeItem(k: string): void
  keys(): string[]
}

export type ConfirmReq = {
  title: string
  message: string
  confirmText?: string
  danger?: boolean
}

/**
 * BUG-PARITY-6：画布视图动作口（实现方=components/ws/canvas/engine.ts 的 toggleMax /
 * toggleFocus + 两个标志读面，由 WsMediaStage 在挂载时注册）。本文件不 import canvas/
 * ——保持「纯状态机可在 node 里跑」口径（unify6-shell-test.py），动作只走这个窄接口。
 */
export type CanvasViewActions = {
  toggleMax(): void
  toggleFocus(): void
  maximized(): boolean
  focusMode(): boolean
}

export type ModelOpts = {
  transport: Transport
  storage?: WsStorage | null
  now?: () => number
  askConfirm?: (o: ConfirmReq) => Promise<boolean>
  toast?: (level: 'ok' | 'bad' | 'warn', text: string) => void
  focusAnswer?: () => void
}

const DRAFT_PREFIX = 'ls.draft.v1.'      // Vue:1644，键格式与旧端互通
const TASKS_LIMIT = 1000                 // Vue loadTasks 传参
const DRAFT_MAX = 300000                 // Vue:2105
const HISTORY_MAX = 30                   // Vue:1656
const HISTORY_BURST_MS = 320             // Vue:1799 watch(historyState) 合并窗口

function clone<T>(v: T): T { return JSON.parse(JSON.stringify(v == null ? null : v)) }  // Vue:1679

type TaskItem = Record<string, any>

// ---------- 状态机 ----------

export class WorkbenchModel {
  transport: Transport
  private storage: WsStorage | null
  private now: () => number
  private ask: (o: ConfirmReq) => Promise<boolean>
  private toast: (level: 'ok' | 'bad' | 'warn', text: string) => void
  private focusAnswer: () => void

  pid = 0
  project: Record<string, any> | null = null
  /** loadShell 拉到的 LS 服务地址（原生画布跳转用，Vue cfg:1335/nativeUrl:3796） */
  lsUrl = ''
  schema: WsSchema = { mode: 'text', controls: [], text_fields: [], hypertext_fields: [],
                      media: null, frame_rate: 25 }
  ctl: WsControls = deriveControls(this.schema)
  s: WsState = emptyState()
  baseline = ''

  stats = { total: 0, annotated: 0, unannotated: 0 }
  myStats: Record<string, any> | null = null
  filter = 'unannotated'
  onlyMine: boolean
  q = ''
  tasks: TaskItem[] = []
  matched = 0
  selected: TaskItem | null = null
  detail: Record<string, any> | null = null
  loading = false
  detailLoading = false
  saving = false
  syncing = false
  loadError = ''
  detailError = ''
  failedTask: TaskItem | null = null

  // UI 标志（分发守卫 + 渲染用；绘制/弹窗本体不在本卡）
  /**
   * BUG-PARITY-6：窄屏（≤700px）队列**抽屉**开关（Vue queueOpen:1620 同职责）。
   * 原症=本字段只被 pick:417 写 false、从不写 true、UI 无人读 →
   * MosWorkbench 的 Esc 队列分支是死码。现在壳层按 isNarrow 渲染抽屉+遮罩，
   * Esc/遮罩/点任务都能关它（toggleQueue 见下）。
   */
  queueOpen = false
  /** BUG-PARITY-6：桌面端「收起任务队列，把宽度让给图」（Vue queueClosed:1624）。 */
  queueClosed = false
  /**
   * BUG-PARITY-6：窄屏判定（Vue isNarrow:1626 = window.innerWidth<=700）。
   * 本文件口径=不依赖 React/DOM，所以阈值读取留在壳层 resize，这里只存结论。
   */
  isNarrow = false
  /**
   * BUG-PARITY-6：画布视图态**镜像**——单一来源在 canvas/engine.ts（maximized:713-735 /
   * focusMode:704-709，键位 keys.ts:45-61），Vue 原件把顶栏钮和引擎放同一组件作用域
   * （:60-71 + :3130-3155），React 拆成「壳层顶栏 / 画布引擎」两文件后钮拿不到状态
   * =整功能对鼠标用户不可达。WsMediaStage 挂载时注册动作、引擎每次 emit 回写标志；
   * 壳层读这两个标志画钮与收起队列，动作经 canvasView 转发回引擎。
   */
  maximized = false
  focusMode = false
  canvasView?: CanvasViewActions
  modalOpen = false
  confirmOpen = false
  /** BUG-PARITY-1：面板两弹窗（编辑类别/区域多类别，WsPanel 自持状态）写此位，
   * 与 modalOpen 并列进键闸——Vue:4640 .modal-mask 挡全部键，面板弹窗同用该组件。 */
  panelModalOpen = false

  draftIds: Record<string, boolean> = {}
  draftOffer: Record<string, any> | null = null
  draftConflict = false
  syncResult: Record<string, any> | null = null
  datasets: Record<string, any>[] = []
  datasetId = ''
  syncInfo: Record<string, any> = { interval: 300, last: {}, items: [] }

  version = 0
  private listeners = new Set<() => void>()
  private shellSeq = 0
  private tasksSeq = 0
  private statsSeq = 0
  private skipConfirmed = false        // Vue:4386
  // BUG-PARITY-4：离开确认只问一次——守卫/返回钮问过后，紧随的路由换 pid（onBeforeRouteUpdate
  // 那条在 React 里是同一次导航的下游）不能再问第二遍；下一次编辑（markEdited/touch）复位。
  private leaveConfirmed = false
  private draftWarned = false
  private draftTimer: ReturnType<typeof setTimeout> | null = null

  // ---------- 撤销 / 重做（REQ-UNIFY-6-5，Vue:1648-1794） ----------
  // 一次拖动或一段连续输入合并成一步：burst=首次变更前的全量序列化，320ms 无新变更即落一步。
  private undoStack: string[] = []
  private redoStack: string[] = []
  private applyingHistory = false          // Vue applyingHistory:1651（恢复期间不再记步）
  private historyMuted = false             // Vue historyMuted:1652（拖拽事务期间静默）
  private historyBurst: string | null = null   // Vue historyBurst:1653
  private historyTimer: ReturnType<typeof setTimeout> | null = null
  private dragCheckpoint: string | null = null // Vue dragCheckpoint:1655
  private historyAnchor = ''               // 上次入栈/落定的全量序列化（Vue watch 的 before 等价）
  private snapAnchor = ''                  // 上次看到的 snapshot（watch(snapshot) 的 before 等价）
  /** 撤销/重做后画布选中态失效的回调（Vue applyHistory:1728 clearActiveShape；由 WsMediaStage 装配） */
  onActiveInvalidate?: () => void

  constructor(opts: ModelOpts) {
    this.transport = opts.transport
    this.storage = opts.storage ?? null
    this.now = opts.now ?? (() => Date.now())
    this.ask = opts.askConfirm ?? (async () => false)
    this.toast = opts.toast ?? (() => {})
    this.focusAnswer = opts.focusAnswer ?? (() => {})
    this.onlyMine = this.storage?.getItem('wb.onlyMine') === '1'
  }

  // ---------- 订阅（React 侧 useSyncExternalStore） ----------
  subscribe(cb: () => void): () => void {
    this.listeners.add(cb)
    return () => this.listeners.delete(cb)
  }
  private notify() { this.version++; this.listeners.forEach((cb) => cb()) }
  /** REQ-UNIFY-6-3 画布接缝：引擎改动区域后触发壳层重算（dirty/按钮态自动跟）；
      REQ-UNIFY-6-5：等价 Vue 双 watch——historyState:1795（拖拽静音）+ snapshot:2196（草稿，
      拖拽期间照常重排；播放/缩放这类「快照没变」的 emit 由 snapAnchor 拦下，不虚写存储）。 */
  touch() {
    this.leaveConfirmed = false
    armMosLeaveGuard(this)   // BUG-PARITY-4：画布/引擎改动也算「改了一笔」，浏览器后退要重新武装
    this.noteDataChange(); this.kickDraftTimer(); this.notify()
  }

  /** 包一层 confirmOpen：站内确认框开着时，S/J/Delete 不能落到背后画布（Vue:4639） */
  private async askx(o: ConfirmReq): Promise<boolean> {
    this.confirmOpen = true
    this.notify()
    try { return await this.ask(o) } finally { this.confirmOpen = false; this.notify() }
  }

  // ---------- 派生读面 ----------
  get mode() { return this.schema.mode || 'text' }
  get annotations(): Record<string, any>[] { return (this.detail && this.detail.annotations) || [] }
  get taskIndex() {
    return this.tasks.findIndex((t) => t.id === (this.selected || { id: undefined }).id)
  }
  get indexLabel() {
    const i = this.taskIndex
    return i >= 0 ? `第 ${i + 1} / ${this.tasks.length} 条` : ''
  }
  get hasPrev() { return this.taskIndex > 0 }
  get hasNext() { return this.taskIndex >= 0 && this.taskIndex < this.tasks.length - 1 }
  get dirty() {
    return isDirty(this.s, this.ctl, this.baseline, !!this.selected)
  }
  get emptyAllowed() { return emptyAllowedOf(this.project) }
  get canSave() {
    return canSaveOf({ detailLoaded: !this.detailLoading && !!this.detail,
                       project: this.project, ctl: this.ctl, s: this.s })
  }
  get saveLabel() {
    if (this.saving) return '保存中…'
    return this.filter === 'unannotated' ? '保存并下一条' : '保存'
  }
  get tabCounts() {
    const st = this.stats
    if (!this.onlyMine || !this.myStats) {
      return { unannotated: st.unannotated, annotated: st.annotated, total: st.total }
    }
    return { unannotated: this.myStats.my_unannotated,
             annotated: this.myStats.my_annotated, total: st.total }
  }
  get promptText(): string {
    // Vue promptText:1976（text 模式走 text_fields，兜底 question）
    const d = (this.selected && this.selected.data) || {}
    if (this.mode === 'text') {
      for (const f of this.schema.text_fields || []) {
        if ((d as Record<string, any>)[f.field]) return (d as Record<string, any>)[f.field]
      }
      return (this.selected && this.selected.question) || ''
    }
    for (const k of ['prompt', 'instruction', 'question', 'text']) {
      if ((d as Record<string, any>)[k]) return (d as Record<string, any>)[k]
    }
    return (this.selected && this.selected.question) || ''
  }

  // ---------- 编辑入口（6-2 面板走这些口，dirty/草稿由本层统一管） ----------
  setText(fromName: string, v: string) {
    this.s.texts[fromName] = v
    this.markEdited()
  }
  setChoice(fromName: string, v: string | string[]) {
    this.s.choiceVals[fromName] = v
    this.markEdited()
  }
  setExtra(fromName: string, v: unknown) {
    this.s.extra[fromName] = v
    this.markEdited()
  }
  /** watch(snapshot) 600ms 落草稿（Vue:2196）+ watch(historyState) 320ms 入栈（Vue:1795）；
   *  切任务路径另有同步落盘。 */
  private markEdited() {
    this.leaveConfirmed = false   // BUG-PARITY-4：又改了一笔=上次「确认离开」作废，下次离开重新问
    armMosLeaveGuard(this)        // BUG-PARITY-4：同上，浏览器后退通道也要重新武装
    this.noteDataChange()
    this.kickDraftTimer()
    this.notify()
  }
  /** watch(snapshot):2196 等价——只有快照真的变了才重排 600ms 落盘；
   *  applyingHistory 闸=Vue:2196 `!selected || applyingHistory`（恢复草稿不算「又改了」）。 */
  private kickDraftTimer(): void {
    if (!this.selected || this.applyingHistory) return
    const snap = snapshotOf(this.s, this.ctl)
    if (snap === this.snapAnchor) return
    this.snapAnchor = snap
    if (this.draftTimer) clearTimeout(this.draftTimer)
    this.draftTimer = setTimeout(() => this.persistDraftNow(), 600)
  }

  // ---------- loadShell:3830 ----------
  async loadShell() {
    // 3831：pid 非法时不发请求（/projects/NaN/stats 两条 422 的教训）
    if (!Number.isFinite(this.pid) || this.pid <= 0) return
    const seq = ++this.shellSeq
    this.loading = true
    try {
      const [c, p] = await Promise.all([
        this.transport.lsConfig().catch(() => null),
        this.transport.lsProject(this.pid),
      ])
      if (seq !== this.shellSeq) return
      if (c) this.lsUrl = String(c.url || '')
      // 移植注：AI 状态/配置（aiStatus/aiConfig/loadAiStats）属后续 AI 卡，本壳不拉。
      this.project = p
      this.schema = (p && p.schema) || this.schema
      this.ctl = deriveControls(this.schema)
      await Promise.all([this.loadStats(), this.loadDatasets(), this.loadSyncMap()])
      if (seq !== this.shellSeq) return
      this.applyBoundDataset()
      // 3858：全部标完时默认看「全部」，否则打开工作台会是一片空白
      if (this.filter === 'unannotated' && this.stats.total > 0 && this.stats.unannotated === 0) {
        this.filter = 'all'
      }
      // 3863：原生画布项目没有平台队列，不拉任务
      if (p && p.workbench) await this.loadTasks()
    } catch (e: any) {
      if (seq === this.shellSeq) this.loadError = e.message
    } finally {
      if (seq === this.shellSeq) { this.loading = false; this.notify() }
    }
  }

  /**
   * BUG-PARITY-5 —— openSettings 补拉（Vue openSettings:3985-3987 `project.value = p`）的
   * React 等价。不能裸赋 project：schema/ctl 是 loadShell:281-283 的三件套，漏掉重导出
   * 就留下「project 新、ctl 旧」的半初始化壳（画布按旧控件渲染=静默错位）。
   */
  adoptProject(p: Record<string, any>): void {
    this.project = p
    this.schema = (p && p.schema) || this.schema
    this.ctl = deriveControls(this.schema)
    this.notify()
  }

  /**
   * watch(pid):4748 + onBeforeRouteUpdate:4762-4768 —— 切项目：脏数据先落旧 pid 草稿，
   * 再**必须问一次**「仍然离开？」；返回 false=用户取消，状态一个都不动（老版靠 Router 中止，
   * URL 也不变；新壳 URL 已变，取消由调用方 replace 回旧 pid）。离开确认刚问过的
   * （leaveConfirmed，BUG-PARITY-4）不二次弹——老版同一次导航只有一个守卫链，不会问两遍。
   */
  async setPid(next: number): Promise<boolean> {
    if (next === this.pid) return true
    const old = this.pid
    if (this.selected && this.dirty && old > 0) {
      this.persistDraftNow(old)
      if (this.draftTimer) clearTimeout(this.draftTimer)
      if (this.leaveConfirmed) this.leaveConfirmed = false
      else if (!(await this.confirmDiscard())) return false
    }
    if (this.draftTimer) clearTimeout(this.draftTimer)
    this.selected = null
    this.detail = null
    this.draftOffer = null
    this.detailError = ''
    this.failedTask = null
    this.loadError = ''
    this.project = null
    this.modalOpen = false
    this.datasetId = ''
    this.syncResult = null
    this.tasks = []
    this.matched = 0
    this.stats = { total: 0, annotated: 0, unannotated: 0 }
    this.myStats = null
    this.q = ''
    this.filter = 'unannotated'
    this.pid = next
    this.s = emptyState()
    this.ctl = deriveControls(this.schema)
    this.baseline = snapshotOf(this.s, this.ctl)
    this.snapAnchor = this.baseline
    this.resetHistory()
    this.leaveConfirmed = false
    this.scanDrafts()
    await this.loadShell()
    return true
  }

  // ---------- loadStats:4092 ----------
  async loadStats() {
    if (!Number.isFinite(this.pid) || this.pid <= 0) return
    const seq = ++this.statsSeq
    try {
      const st = await this.transport.lsStats(this.pid)
      if (seq === this.statsSeq) this.stats = st
    } catch { /* ignore（Vue 同） */ }
    // 「只看我的」计数单独算（Vue:4102）
    if (this.onlyMine) {
      try {
        const m = await this.transport.lsStats(this.pid, 1)
        if (seq === this.statsSeq) this.myStats = m
      } catch { if (seq === this.statsSeq) this.myStats = null }
    } else if (seq === this.statsSeq) this.myStats = null
    this.notify()
  }

  // ---------- loadTasks:4134 ----------
  async loadTasks(append = false) {
    if (!Number.isFinite(this.pid) || this.pid <= 0) return
    const seq = ++this.tasksSeq
    this.loading = true
    try {
      const offset = append ? this.tasks.length : 0
      // 4140：续翻用最后一条 id 做游标，offset 在两次请求间删任务会漏项
      const after = append && this.tasks.length ? this.tasks[this.tasks.length - 1].id : 0
      const d = await this.transport.lsTasks(this.pid, this.filter, TASKS_LIMIT, offset,
                                             this.q, this.onlyMine ? 1 : 0, after)
      if (seq !== this.tasksSeq) return          // 旧响应不覆盖新列表
      const page = d.items || []
      this.tasks = append ? this.tasks.concat(page) : page
      this.matched = d.matched ?? this.tasks.length
      if (this.selected) {
        const still = this.tasks.find((t) => t.id === this.selected!.id)
        if (!still) { this.selected = null; this.detail = null; this.draftOffer = null }
      }
      // 4153：首屏自动选第一条，focus=false 不关队列抽屉
      if (!append && !this.selected && this.tasks.length) await this.pick(this.tasks[0], 0, false)
    } catch (e: any) {
      if (seq === this.tasksSeq) this.toast('bad', e.message)
    } finally {
      if (seq === this.tasksSeq) { this.loading = false; this.notify() }
    }
  }

  loadMoreTasks() {
    if (!this.loading && this.tasks.length < this.matched) void this.loadTasks(true)
  }

  // ---------- 队列收起 / 画布视图态（BUG-PARITY-6） ----------
  /** 壳层 resize 同步窄屏判定（Vue onWindowResize:3162-3166 的 isNarrow 段：
      进窄屏不保留桌面「收起」位，抽屉自己管开合）。 */
  setNarrow(n: boolean) {
    if (this.isNarrow === n) return
    this.isNarrow = n
    if (n) this.queueClosed = false
    this.notify()
  }

  /**
   * Vue toggleQueue:1627-1634 逐字移植——**一个按钮管两种形态**：
   * 窄屏开/关抽屉（顺带清桌面收起位），桌面端收起/展开左栏。
   * Vue:10-11 的注释点名过原由：「以前有两个按钮、各管一套状态，手机上点错那个
   * 会让抽屉再也打不开」，所以这里不拆成两个动作。
   */
  toggleQueue() {
    if (this.isNarrow) {
      this.queueOpen = !this.queueOpen
      if (this.queueOpen) this.queueClosed = false
    } else {
      this.queueClosed = !this.queueClosed
      this.queueOpen = false
    }
    this.notify()
  }

  /** 遮罩/Esc 收抽屉：只在真开着时动状态（不 notify 空转）。 */
  closeQueueDrawer() {
    if (!this.queueOpen) return
    this.queueOpen = false
    this.notify()
  }

  /**
   * BUG-PARITY-6：画布挂载/卸载时注册视图动作（引擎=单一来源，本方法只存转发口）。
   * 卸载清标志：画布不在了还留着「已最大化」=顶栏挂个按不动的假钮。
   */
  setCanvasView(v: CanvasViewActions | undefined) {
    this.canvasView = v
    if (v) this.syncCanvasView()
    else { this.maximized = false; this.focusMode = false; this.notify() }
  }

  /**
   * 引擎每次 emit 后回写镜像；**只在标志真的变了才 notify**——缩放/移动这类高频
   * emit 不该带着壳层重渲染（引擎版本号才是那条快通道）。
   */
  syncCanvasView() {
    const v = this.canvasView
    if (!v) return
    const m = v.maximized()
    const f = v.focusMode()
    if (m === this.maximized && f === this.focusMode) return
    this.maximized = m
    this.focusMode = f
    this.notify()
  }

  toggleMax() { this.canvasView?.toggleMax() }
  toggleFocus() { this.canvasView?.toggleFocus() }

  // ---------- pick:4179 ----------
  async pick(t: TaskItem, _i = 0, focus = true) {
    this.detailError = ''
    this.failedTask = null
    if (this.selected && this.selected.id !== t.id) {
      // 先落草稿再问离开：「切走」不等于「丢掉」（4183 注释）
      this.persistDraftNow()
      if (this.draftTimer) clearTimeout(this.draftTimer)
      if (!(await this.confirmDiscard())) { this.notify(); return }
      this.draftOffer = null
    }
    if (focus) this.queueOpen = false
    this.selected = t
    this.detail = null
    this.detailLoading = true
    this.resetForTask(null)
    this.notify()
    try {
      const d = await this.transport.lsTask(this.pid, t.id)
      // 4197：只接受仍指向当前任务的响应（快速切换/慢响应不覆盖）
      if (!this.selected || this.selected.id !== t.id) return
      this.detail = d
      this.selected = { ...t, ...d }
      this.resetForTask(d)
      this.offerDraft(t.id)
    } catch (e: any) {
      if (!this.selected || this.selected.id !== t.id) return
      // 4204：详情没拿到不能按「将新增标注」打开
      this.detailError = e.message || '任务详情加载失败'
      this.failedTask = t
      this.selected = null
      this.detail = null
    } finally {
      if (!this.selected || this.selected.id === t.id) {
        this.detailLoading = false
        this.notify()
      }
    }
    // BUG-PARITY-1（Vue:4215 原条件）：图片+有框选控件的任务切换**不抢焦点**——
    // 焦点留在队列按钮上，画布键才能照常活（`/` 想聚焦时另有按键路径 :4679）。
    if (focus && (this.mode !== 'image' || !this.ctl.hasShapeCtl)) this.focusAnswer()
  }

  /** resetAnnotation 语义 = schema 冻结模块的重置 + 新基线 + 清撤销栈（Vue:2357） */
  private resetForTask(d: Record<string, any> | null) {
    resetAnnotation(this.s, this.ctl, d)
    this.baseline = snapshotOf(this.s, this.ctl)
    this.snapAnchor = this.baseline
    this.resetHistory()
  }

  retryDetail() {
    if (this.failedTask) void this.pick(this.failedTask, 0)
  }

  // ---------- 队列筛选 ----------
  async setFilter(f: string) {
    if (f === this.filter) {
      // 4226：再点当前页签=刷新；不能走清空逻辑（会扔掉未保存且不弹确认）
      this.persistDraftNow()
      await this.loadTasks()
      return
    }
    this.persistDraftNow()
    if (this.draftTimer) clearTimeout(this.draftTimer)
    if (!(await this.confirmDiscard())) return
    this.filter = f
    this.selected = null
    this.detail = null
    this.draftOffer = null
    this.detailError = ''
    this.failedTask = null
    await this.loadTasks()
    this.notify()
  }

  /** onSearch:4245 的立即部分（280ms 防抖归组件） */
  async searchNow() {
    if (this.dirty) this.persistDraftNow()
    await this.loadTasks()
  }

  async toggleOnlyMine(next: boolean) {
    if (next === this.onlyMine) return
    if (this.dirty) this.persistDraftNow()
    this.onlyMine = next
    try { this.storage?.setItem('wb.onlyMine', next ? '1' : '0') } catch { /* ignore */ }
    await this.loadStats()
    await this.loadTasks()
    this.notify()
  }

  // ---------- confirmDiscard:2057 ----------
  async confirmDiscard(): Promise<boolean> {
    // skip() 已就「跳过+未保存」问过，pick 不再问第二遍（2058 注释）
    if (this.skipConfirmed || !this.dirty) return true
    return this.askx({
      title: '还有未保存的标注',
      message: '当前标注还没保存。切走后可以在这条任务的草稿里恢复，仍要离开吗？',
      confirmText: '仍然离开',
      danger: true,
    })
  }

  /**
   * BUG-PARITY-4 —— 离开前兜底落盘（onUnmounted:4819-4821 的 persistDraftNow + clearTimeout）。
   * 600ms 防抖（kickDraftTimer）还没跑就离开时，打字内容靠这一步进草稿；
   * 守卫确认（confirmLeave）、菜单同步通道（setLeaveGuard）、卸载清理三处复用。
   */
  persistOnLeave(): void {
    this.persistDraftNow()
    if (this.draftTimer) { clearTimeout(this.draftTimer); this.draftTimer = null }
  }

  /**
   * BUG-PARITY-4 —— 路由离开守卫（onBeforeRouteLeave:4770-4776 逐字）：脏数据先同步落盘、
   * 停掉防抖，再问「仍然离开？」。确认过且原本脏 → 记 leaveConfirmed，紧随的同路由换 pid
   * （setPid）不再问第二遍（老版一次导航只有一条守卫链）。
   * 覆盖不了 Router 守卫的地方（本应用是 BrowserRouter，useBlocker 直接 invariant 报错）：
   * ① 浏览器前进/后退 → **页面级** wsLeaveGuard 的 popstate 通道（组件里注册监听轮不到被调用：
   * BrowserRouter 用 useSyncExternalStore，handlePop 跑完组件在这次派发里就卸载了，见该文件头注；
   * 这条也拦不住导航，故语义是「导航后撤销这一步历史」，取消=history.go 退回原条目 + 草稿回放）；
   * ② 平台内菜单/Link 跳转 → unsaved.ts setLeaveGuard（同步通道，只能 window.confirm，
   * 与 WorkflowEditor 同型，这条是真·不导航）；
   * ③「← 返回」钮 → 本方法；④ 刷新/关标签 → beforeUnload（已有 :1045 一带）。
   */
  async confirmLeave(): Promise<boolean> {
    const wasDirty = this.dirty
    this.persistOnLeave()
    const go = await this.confirmDiscard()
    if (go && wasDirty) {
      this.leaveConfirmed = true
      disarmMosLeaveGuard()   // BUG-PARITY-4：人已同意离开，页面级守卫不能再追着想问
    }
    return go
  }

  // ---------- save:4255 ----------
  async save(): Promise<void> {
    if (!this.selected || this.saving || this.detailLoading || !this.detail) return
    // 只读态拦下键盘路径（按钮已灰，Ctrl+Enter 仍可到这里；草稿照常留本地）
    if (getWriteAccess() === 'readonly') { this.toast('bad', WRITE_BLOCKED_TEXT); return }
    // 4258：快捷键以前绕过 canSave —— 按钮灰的键盘却能存
    if (!this.canSave) {
      this.toast('bad', this.ctl.perRegionText && this.s.segments.some((x) => !String(x.text || '').trim())
        ? '还有时间段没填文字' : '还不能保存（标注未准备好或内容为空）')
      return
    }
    const result = buildResult(this.s, this.ctl)
    if (!result.length && !this.emptyAllowed) { this.toast('bad', '还没有标注内容'); return }
    this.saving = true
    this.notify()
    const savedId = this.selected.id
    // 4269：在途可继续编辑，回包不得覆盖保存后新输入
    const snapAtSave = snapshotOf(this.s, this.ctl)
    const expected = (() => {
      const id = this.s.restored ? null : this.s.annotationId
      if (id) {
        const a = this.annotations.find((x) => String(x.id) === String(id))
        return a ? (a.updated_at || '') : ''
      }
      const mine = this.annotations.filter((a) => a.mine)
      return mine.length ? (mine[mine.length - 1].updated_at || '') : null
    })()
    try {
      let r: Record<string, any>
      for (let attempt = 0; ; attempt++) {
        try {
          r = await this.transport.lsAnnotate(this.pid, savedId, {
            result,
            dataset_id: this.datasetId,
            annotation_id: this.s.restored ? null : this.s.annotationId,
            version_check: true,
            expected_updated_at: expected,
            force: attempt > 0,
          })
          break
        } catch (e: any) {
          if (e.status === 409 && attempt === 0) {
            // Vue 用 window.confirm；此处统一走注入的确认框
            const go = await this.askx({
              title: '版本冲突',
              message: e.message + '\n\n仍要用当前内容覆盖服务器上的版本吗？',
              confirmText: '覆盖', danger: true,
            })
            if (!go) { this.toast('bad', '未覆盖：请刷新页面合并服务器版本'); return }
            continue
          }
          throw e
        }
      }
      this.toast('ok', (r.updated ? '已更新 #' : '已保存 #') + r.annotation_id
        + (r.empty ? '（空标注）' : '') + (r.dataset ? '，已同步标注集' : ''))
      this.clearDraft(savedId)
      this.draftOffer = null
      if (this.draftTimer) clearTimeout(this.draftTimer)
      await this.loadStats()
      // 4311：保存期间可能已切任务，只更新队列，不回写当前编辑区
      const stillHere = !!(this.selected && this.selected.id === savedId)
      if (stillHere) {
        this.baseline = snapAtSave
        if (r.annotation_id) this.s.annotationId = r.annotation_id
        this.s.annotationMine = true
        this.s.viewingOther = ''
        if (r.annotation_id) {
          const hit = this.annotations.find((a) => a.id === r.annotation_id)
          if (hit) {
            hit.answer = r.answer || ''
            hit.result = result      // 4330：历史回放读 result，只改 answer 会载旧几何
            hit.mine = true
            if (r.updated_at) hit.updated_at = r.updated_at
          } else if (this.detail) {
            const cu = (this.detail.current_user as Record<string, any>) || {}
            this.detail.annotations = (this.detail.annotations || []).concat([{
              id: r.annotation_id, answer: r.answer || '', by: cu.email || '',
              result, mine: true, updated_at: r.updated_at || '',
            }])
          }
        }
        this.s.restored = false
        this.selected.annotated = true
      }
      // 4345：必须在新基线之后再判 dirty
      const typedDuringSave = !!(stillHere && (
        snapshotOf(this.s, this.ctl) !== snapAtSave
        || this.s.polyDraft.length > 0 || this.s.brushDraft.length > 0))
      if (this.filter === 'unannotated') {
        const idx = this.tasks.findIndex((t) => t.id === savedId)
        this.tasks = this.tasks.filter((t) => t.id !== savedId)
        this.matched = Math.max(0, this.matched - 1)
        if (stillHere && !typedDuringSave) {
          this.selected = null
          this.detail = null
          this.resetForTask(null)
          const next = this.tasks[Math.min(Math.max(idx, 0), this.tasks.length - 1)]
          if (next) await this.pick(next, 0)
        }
      } else {
        // 4364：队列就地更新，否则统计与列表口径互相矛盾
        const t = this.tasks.find((x) => x.id === savedId)
        if (t) {
          t.annotated = true
          if (!t.mine) {
            t.mine = true
            t.annotators = Math.max(1, Number(t.annotators) || 0)
            if (this.myStats) {
              this.myStats.my_annotated = (Number(this.myStats.my_annotated) || 0) + 1
              this.myStats.my_unannotated = Math.max(0,
                (Number(this.myStats.my_unannotated) || 0) - 1)
            }
          }
          if (r.answer) t.answer = r.answer
          if (r.annotation_id) t.annotation_id = r.annotation_id
        }
      }
    } catch (e: any) {
      this.toast('bad', e.message)
    } finally {
      this.saving = false
      this.notify()
    }
  }

  // ---------- skip:4388 ----------
  async skip(): Promise<void> {
    if (this.project && this.project.show_skip_button === false) return
    const i = this.tasks.findIndex((t) => t.id === (this.selected || { id: undefined }).id)
    const nextIndex = i + 1 < this.tasks.length ? i + 1 : 0
    const next = this.tasks[nextIndex]
    // 4393：只有一条任务时按 S 会把编辑区重置成服务端版本，看起来像丢内容
    if (!next || next.id === (this.selected || { id: undefined }).id) {
      this.toast('bad', '当前筛选下没有其他任务可跳过')
      this.notify()
      return
    }
    const go = await this.askx({
      title: '跳过这条任务？',
      message: this.dirty
        ? '当前标注还没保存。跳过会切到下一条，未保存的内容已留在本地草稿里，重新进入可恢复。'
        : '将切到下一条任务，这条保持「待标注」。',
      confirmText: '跳过',
    })
    if (!go) return
    if (this.dirty) {
      this.persistDraftNow()
      if (this.draftTimer) clearTimeout(this.draftTimer)
    }
    this.skipConfirmed = true
    try { await this.pick(next, nextIndex) } finally { this.skipConfirmed = false }
  }

  // ---------- navigate:4414 ----------
  navigate(delta: number) {
    const i = this.tasks.findIndex((t) => t.id === (this.selected || { id: undefined }).id)
    const next = this.tasks[Math.min(Math.max(i + delta, 0), this.tasks.length - 1)]
    if (next && (!this.selected || next.id !== this.selected.id)) void this.pick(next)
  }

  /** navigate 的可等待版本（e2e 断言键序列→状态迁移用） */
  async navigateAsync(delta: number) {
    const i = this.tasks.findIndex((t) => t.id === (this.selected || { id: undefined }).id)
    const next = this.tasks[Math.min(Math.max(i + delta, 0), this.tasks.length - 1)]
    if (next && (!this.selected || next.id !== this.selected.id)) await this.pick(next)
  }

  // ---------- 草稿（persistDraftNow:2101 / offerDraft:2138 / scanDrafts:2079） ----------
  private draftKey(taskId: number | string, pidOverride?: number): string {
    const p = pidOverride === undefined ? this.pid : pidOverride
    return DRAFT_PREFIX + p + '.' + taskId
  }
  // ---------- 撤销 / 重做（REQ-UNIFY-6-5，Vue:1657-1794） ----------
  /** historyState:1657 —— 撤销栈与草稿共用的全量序列化；键序/内容与 Vue 原件一致
   *  （含 text 镜像、polyDraft/brushDraft、extra——「序列化对象=全集」是 6-5 的收口前提）。 */
  historyState(): string {
    const s = this.s
    const first = this.ctl.textareaCtl
    return JSON.stringify({
      text: first ? (s.texts[first.from_name] || '') : '',
      texts: { ...s.texts }, choices: { ...s.choiceVals },
      segments: s.segments, boxes: s.boxes, polygons: s.polygons, keypoints: s.keypoints,
      ellipses: s.ellipses, spans: s.textSpans, hyper: s.hyperSpans, relations: s.relations,
      ts: s.tsSegments, brush: s.brushRegions,
      polyDraft: s.polyDraft, brushDraft: s.brushDraft,
      extra: { ...s.extra },
    })
  }
  /** applyHistory:1680 —— 恢复一帧全量状态；恢复期间不记步、不落草稿。
   *  旧端（Vue）草稿兼容：texts 缺→text 回填首框；choices 数组/字符串→回填首组。 */
  applyHistory(raw: string): boolean {
    let v: Record<string, any>
    try { v = JSON.parse(raw) } catch { return false }
    this.applyingHistory = true
    try {
      const st = this.s
      for (const k of Object.keys(st.texts)) delete st.texts[k]
      for (const c of this.ctl.textareas) st.texts[c.from_name] = ''
      if (v.texts && typeof v.texts === 'object' && !Array.isArray(v.texts)) {
        for (const c of this.ctl.textareas) {
          if (Object.prototype.hasOwnProperty.call(v.texts, c.from_name)) {
            st.texts[c.from_name] = String(v.texts[c.from_name] || '')
          }
        }
      } else if (this.ctl.textareaCtl) {
        st.texts[this.ctl.textareaCtl.from_name] = String(v.text || '')
      }
      const histChoices = clone(v.choices)
      for (const k of Object.keys(st.choiceVals)) delete st.choiceVals[k]
      for (const c of this.ctl.choicesCtls) st.choiceVals[c.from_name] = c.multiple ? [] : ''
      if (Array.isArray(histChoices) || typeof histChoices === 'string') {
        const c0 = this.ctl.choiceCtl
        if (c0) {
          st.choiceVals[c0.from_name] = c0.multiple
            ? (Array.isArray(histChoices) ? histChoices : (histChoices ? [histChoices] : []))
            : (Array.isArray(histChoices) ? ((histChoices as string[])[0] || '') : (histChoices || ''))
        }
      } else if (histChoices && typeof histChoices === 'object') {
        for (const c of this.ctl.choicesCtls) {
          if (Object.prototype.hasOwnProperty.call(histChoices, c.from_name)) {
            const x = (histChoices as Record<string, string | string[]>)[c.from_name]
            st.choiceVals[c.from_name] = c.multiple
              ? (Array.isArray(x) ? x : (x ? [x] : []))
              : (Array.isArray(x) ? (x[0] || '') : (x || ''))
          }
        }
      }
      st.segments = clone(v.segments) || []
      st.boxes = clone(v.boxes) || []
      st.polygons = clone(v.polygons) || []
      st.keypoints = clone(v.keypoints) || []
      st.ellipses = clone(v.ellipses) || []
      st.textSpans = clone(v.spans) || []
      st.hyperSpans = clone(v.hyper) || []
      st.relations = clone(v.relations) || []
      st.tsSegments = clone(v.ts) || []
      st.brushRegions = clone(v.brush) || []
      for (const k of Object.keys(st.extra)) delete st.extra[k]
      Object.assign(st.extra, clone(v.extra) || {})
      st.polyDraft = clone(v.polyDraft) || []
      st.brushDraft = clone(v.brushDraft) || []
      // Vue:1728 clearActiveShape——恢复后画布选中的下标可能越界/指向别的形状
      this.onActiveInvalidate?.()
    } finally {
      this.applyingHistory = false
      this.historyAnchor = this.historyState()
      // 恢复动作本身不算「用户又改了一笔」（Vue:2196 applyingHistory 闸）；
      // 但草稿恢复后状态≠服务器基线=脏，快照锚点必须跟进，否则后续纯视图 emit 会误排 600ms。
      this.snapAnchor = snapshotOf(this.s, this.ctl)
      this.notify()
    }
    return true
  }
  /** watch(historyState):1795 等价——数据变更入口（touch/markEdited 共用）。
   *  applyingHistory=Vue:1651 闸；historyMuted=拖拽事务闸（:1652）。 */
  private noteDataChange(): void {
    if (this.applyingHistory || this.historyMuted || !this.selected) return
    const cur = this.historyState()
    if (cur === this.historyAnchor) return
    if (this.historyBurst == null) this.historyBurst = this.historyAnchor
    // Vue watch(historyState, (now, before))：watcher 每笔都带最新「变更前」值——
    // 锚点必须当场跟进，否则隔开的第二笔会把 burst 再次锚到旧值，重复帧吃掉中间步。
    this.historyAnchor = cur
    if (this.historyTimer) clearTimeout(this.historyTimer)
    this.historyTimer = setTimeout(() => this.flushHistory(), HISTORY_BURST_MS)
  }
  /** flushHistory:1736 —— 一段连续输入合并成一步；无差异不入栈。 */
  flushHistory(): void {
    if (this.historyTimer) { clearTimeout(this.historyTimer); this.historyTimer = null }
    if (this.historyBurst == null) return
    const before = this.historyBurst
    this.historyBurst = null
    if (before === this.historyState()) return
    this.undoStack.push(before)
    if (this.undoStack.length > HISTORY_MAX) this.undoStack.shift()
    this.redoStack = []
    this.notify()   // 定时器路径落在 notify 之外：入栈后必须重算 ↶/↷ 按钮态
  }
  /** resetHistory:1747 —— 换任务/换项目：栈与在途 burst 全清。 */
  resetHistory(): void {
    if (this.historyTimer) { clearTimeout(this.historyTimer); this.historyTimer = null }
    this.historyBurst = null
    this.dragCheckpoint = null
    this.applyingHistory = false
    this.historyMuted = false
    this.undoStack = []
    this.redoStack = []
    this.historyAnchor = this.historyState()
  }
  undo(): boolean {
    if (!this.selected) return false                       // Vue:1758
    this.flushHistory()
    if (!this.undoStack.length) return false
    const cur = this.historyState()
    const prev = this.undoStack.pop() as string
    this.redoStack.push(cur)
    this.applyHistory(prev)
    return true
  }
  redo(): boolean {
    if (!this.selected) return false                       // Vue:1768
    this.flushHistory()
    if (!this.redoStack.length) return false
    const cur = this.historyState()
    const next = this.redoStack.pop() as string
    this.undoStack.push(cur)
    this.applyHistory(next)
    return true
  }
  /** beginDragHistory:1777 —— 拖拽=一次事务：先落定在途 burst，再静音并打 checkpoint。 */
  beginDragHistory(): void {
    if (this.dragCheckpoint != null) return
    this.flushHistory()
    this.dragCheckpoint = this.historyState()
    this.historyMuted = true
  }
  /** endDragHistory:1783 —— 有位移才入栈一步；Vue 用 nextTick 解锁防把中间态再记一步，
   *  React 侧恢复在事件回调里同步完成，microtask 后解锁等价。 */
  endDragHistory(): void {
    if (this.dragCheckpoint == null) return
    const before = this.dragCheckpoint
    this.dragCheckpoint = null
    const cur = this.historyState()
    if (before !== cur) {
      this.undoStack.push(before)
      if (this.undoStack.length > HISTORY_MAX) this.undoStack.shift()
      this.redoStack = []
      this.notify()   // 同上：拖拽收尾也要刷按钮态
    }
    this.historyAnchor = cur
    Promise.resolve().then(() => { this.historyMuted = false })
  }
  get undoCount(): number { return this.undoStack.length }
  get redoCount(): number { return this.redoStack.length }
  scanDrafts() {
    if (!this.storage) return
    for (const k of Object.keys(this.draftIds)) delete this.draftIds[k]
    try {
      const prefix = DRAFT_PREFIX + this.pid + '.'
      const stale: string[] = []
      for (const k of this.storage.keys()) {
        if (!k || !k.startsWith(prefix)) continue
        const tid = k.slice(prefix.length)
        if (!/^\d+$/.test(tid)) continue
        let v: Record<string, any> | null = null
        try { v = JSON.parse(this.storage.getItem(k) || 'null') } catch { /* 坏数据清掉 */ }
        if (!v || !v.state || this.now() - Number(v.at || 0) > 30 * 24 * 3600 * 1000) stale.push(k)
        else this.draftIds[tid] = true
      }
      for (const k of stale) this.storage.removeItem(k)
    } catch { /* localStorage 不可用时不阻断标注（Vue:2095） */ }
  }
  clearDraft(taskId: number | string) {
    try { this.storage?.removeItem(this.draftKey(taskId)) } catch { /* ignore */ }
    delete this.draftIds[String(taskId)]
  }
  persistDraftNow(pidOverride?: number) {
    const task = this.selected
    if (!task || !this.dirty || !this.storage) return
    const state = this.historyState()      // Vue:2104 state=historyState()（含 text 镜像，键序一致）
    if (state.length > DRAFT_MAX) {
      if (!this.draftWarned) {
        this.draftWarned = true
        this.toast('bad', '这条标注太大，草稿未自动保存；请及时点保存')
      }
      return
    }
    const curAnn = (this.detail && (this.detail.annotations || [])
      .find((a: Record<string, any>) => String(a.id) === String(this.s.annotationId))) || null
    // 2114：草稿基线 = 正在编辑的标注，其次「我的」最新一份；用于跨标签页冲突判定
    const mineAnn = (!curAnn && this.detail)
      ? ((this.detail.annotations || []).filter((a: Record<string, any>) => a.mine && a.updated_at)
          .slice(-1)[0] || null)
      : null
    try {
      this.storage.setItem(this.draftKey(task.id, pidOverride), JSON.stringify({
        at: this.now(),
        state,
        snap: snapshotOf(this.s, this.ctl),
        restored: this.s.restored,
        annotation_id: this.s.restored ? null : this.s.annotationId,
        server_at: (curAnn && curAnn.updated_at) || (mineAnn && mineAnn.updated_at) || '',
      }))
      this.draftIds[String(task.id)] = true
    } catch {
      if (!this.draftWarned) {
        this.draftWarned = true
        this.toast('bad', '浏览器存储已满，草稿未保存；请及时点保存')
      }
    }
    this.notify()
  }
  offerDraft(taskId: number | string) {
    this.draftOffer = null
    this.draftConflict = false
    if (!this.storage) { this.notify(); return }
    let v: Record<string, any> | null = null
    try { v = JSON.parse(this.storage.getItem(this.draftKey(taskId)) || 'null') } catch { /* ignore */ }
    if (!v || !v.state) {
      if (v) this.clearDraft(taskId)
      this.notify()
      return
    }
    if (v.snap && v.snap === snapshotOf(this.s, this.ctl)) { this.clearDraft(taskId); this.notify(); return }
    // 2147：服务器在这份草稿之后变过 → 不再自动覆盖，让用户选
    let serverChanged = false
    if (v.annotation_id) {
      const cur = (this.detail && (this.detail.annotations || [])
        .find((a: Record<string, any>) => String(a.id) === String(v!.annotation_id))) || null
      serverChanged = !cur || !!(v.server_at && cur.updated_at && v.server_at !== cur.updated_at)
    } else {
      // 2160：打草稿时没有「我的」标注、之后别的标签页存出一份 → 同样是冲突
      const mineNow = ((this.detail && this.detail.annotations) || [])
        .filter((a: Record<string, any>) => a.mine && a.updated_at)
      const newest = mineNow.length ? mineNow[mineNow.length - 1] : null
      serverChanged = v.server_at
        ? (!newest || newest.updated_at !== v.server_at)
        : !!newest
    }
    this.draftOffer = v
    if (serverChanged) {
      this.draftConflict = true
      this.baseline = snapshotOf(this.s, this.ctl)   // 当前视图=服务器版本；草稿未应用
      this.notify()
      return
    }
    // BUG-PARITY-4（口径钉在这里，红检 R11 盯着）：草稿回放**不能**顺手把基线钉成回放后的内容。
    // 基线保持服务器版本（resetForTask 刚钉过），这份内容就还是「未保存」——浏览器后退选了
    // 「别走」的人、以及任何带着草稿回来的人，看到的仍是「未保存」标、下次离开仍会被问。
    // 一旦这里改成 `this.baseline = snapshotOf(...)`，「取消离开」就等于悄悄当已保存（丢语义回来）。
    this.applyHistory(v.state)
    this.s.restored = !!v.restored
    this.notify()
  }
  /** 「保留草稿」= 收起提示条但不清存储（Vue watch(draftOffer) 顺带清冲突,2194） */
  dismissDraft() {
    this.draftOffer = null
    this.draftConflict = false
    this.notify()
  }
  applyDraft() {
    if (!this.draftOffer) return
    this.draftConflict = false
    this.applyHistory(this.draftOffer.state)
    this.s.restored = true
    this.notify()
  }
  dropDraft() {
    if (!this.selected) return
    this.clearDraft(this.selected.id)
    this.draftOffer = null
    this.draftConflict = false
    this.resetForTask(this.detail)   // 回服务器版本；baseline 一并重置（Vue:2185）
    this.notify()
  }

  /**
   * loadHistory:2200-2208 —— 抽屉「标注历史」的「载入这条 / 复制到我的标注」（BUG-PARITY-3）。
   * 先 persistDraftNow 再问离开：取消「仍然离开」时内容已留本地，回来还能 offerDraft 捞回；
   * 确认后 clearDraft——载入历史即「弃当前内容」的明确决定，旧草稿不再顶着（resetAnnotation:2287
   * 的 draftOffer 复位也在同一处）。
   * 单条回放走 resetForTask：result 含几何按控件回填，无 result 才落 legacy answer
   * （:4328 注——只更新 answer 会载入旧几何，再保存把旧内容写回去）。
   * restored=true 让保存固定 annotation_id=null：复制别人的标注由后端「一人一份」
   * 新建/更新到我名下（:3772 注），不会覆盖对方那条。
   * 基线重置：载入后不判脏（内容就是刚回放的服务器版本），改一笔才算未保存。
   */
  async loadHistory(a: Record<string, any>): Promise<void> {
    this.persistDraftNow()
    if (this.draftTimer) clearTimeout(this.draftTimer)
    if (!(await this.confirmDiscard())) { this.notify(); return }
    if (this.selected) this.clearDraft(this.selected.id)
    this.draftOffer = null
    this.draftConflict = false
    this.resetForTask({ annotations: [a], answer: a.answer })
    this.s.restored = true
    this.notify()
  }

  // ---------- 数据集绑定（同步层） ----------
  async loadDatasets() {
    try { this.datasets = await this.transport.datasets() } catch { /* ignore */ }
    if (this.applyBoundDataset()) { this.notify(); return }
    if (!this.datasetId) {
      const own = this.lsDatasets.find((d: Record<string, any>) => d.name === 'LS 项目 ' + this.pid)
      if (own) this.datasetId = own.id
    }
    this.notify()
  }
  /**
   * 「同步目标数据集」下拉的数据源＝**全部数据集**（BUG-LABEL-DROPDOWN-1，2026-10-29）。
   *
   * 旧判据照抄 Vue 原件（LabelStudioWorkbench.vue:3776）`d.source === 'label_studio'`，
   * 与现网数据不符：数据集 source 由 mos 侧建集时打标（label_studio.py:1908 同步建集
   * 才写 'label_studio'；datasets.py:105 其余入口曾默认 'streamvision_kb'，REQ-UX-FLOW-1#4
   * 2026-10-29 已改默认 'local'，存量错默认值由运维侧人工清理），现网 3 个
   * 全是 streamvision_kb（probe 2026-10-29），首批同步跑之前下拉恒空；且 sync-map 若
   * 绑过非 label_studio 数据集，applyBoundDataset 也会把它吞掉。
   * 后端 project_to_dataset（label_studio.py:2597）对任意 dataset_id 一律接受、不校验
   * source——不存在「仅某 source 可作目标」的口径，「可同步」＝全部数据集。source 值的
   * 定义权在 mos 侧（卡面处置：谁定义谁改、禁两处各硬编码），前端不再二次判定。
   * 名字保留 lsDatasets：两个调用点（MosWorkbench.tsx:152 / WsDialogs.tsx:513）在本卡
   * 文件域外，改名会越界；语义以本注释为准。
   */
  get lsDatasets(): Record<string, any>[] { return this.datasets }
  get boundDatasetId(): string {
    const e = (this.syncInfo.items || []).find((x: Record<string, any>) => Number(x.project_id) === this.pid)
    return (e && e.dataset_id) || ''
  }
  datasetOwner(dsId: string): number | null {
    const e = (this.syncInfo.items || []).find((x: Record<string, any>) =>
      x.dataset_id === dsId && Number(x.project_id) !== this.pid)
    return e ? Number(e.project_id) : null
  }
  applyBoundDataset(): boolean {
    const b = this.boundDatasetId
    if (b && this.lsDatasets.some((d: Record<string, any>) => d.id === b)) {
      this.datasetId = b
      return true
    }
    return false
  }
  /** watch(datasetId):4803 —— 选到别人项目占用的数据集要确认 */
  async setDataset(id: string) {
    if (!id) { this.datasetId = ''; this.notify(); return }
    const owner = this.datasetOwner(id)
    if (owner) {
      const go = await this.askx({
        title: '标注集已被别的项目占用',
        message: `标注集 #${id} 已绑定项目 ${owner}，继续会把两个项目的标注写进同一个标注集，确定吗？`,
        confirmText: '仍然绑定', danger: true,
      })
      if (!go) return
    }
    this.datasetId = id
    this.notify()
  }
  async loadSyncMap() {
    try {
      const d = await this.transport.lsSyncMap()
      this.syncInfo = d
      // 3934：enabled=false 也保留绑定，不能拿「有没有映射」当开关
    } catch { /* ignore */ }
    this.notify()
  }
  // ---------- sync():4558 ----------
  async sync() {
    if (this.syncing) return
    if (getWriteAccess() === 'readonly') { this.toast('bad', '标注通道当前只读，无法同步标注集'); return }
    this.syncing = true
    this.notify()
    try {
      const r = await this.transport.lsToDataset(this.pid, {
        name: this.project && this.project.title
          ? '标注 · ' + this.project.title + ' (#' + this.pid + ')'
          : 'LS 项目 ' + this.pid,
        dataset_id: this.datasetId,
        freeze: true,
      })
      this.datasetId = r.dataset_id || this.datasetId
      this.syncResult = r
      // 4569：to-dataset 已重绑，必须刷映射，否则保存写旧库、对账写新库
      await this.loadSyncMap()
      this.toast('ok', '已同步 ' + r.inserted + ' 条到标注集'
        + (r.removed ? '，清理旧版本 ' + r.removed + ' 条' : ''))
      await this.loadDatasets()
    } catch (e: any) {
      this.toast('bad', e.message)
    } finally {
      this.syncing = false
      this.notify()
    }
  }

  // ---------- beforeUnload:4627 ----------
  /** 返回 true 表示需要拦下（组件做 e.preventDefault(); e.returnValue=''）。 */
  beforeUnload(): boolean {
    if (!this.dirty) return false
    this.persistDraftNow()
    return true
  }

  // ---------- onKey:4634 ----------
  /**
   * 键分发。evt.tagName 为事件源元素标签（组件传入），inModal=事件源在弹窗遮罩内。
   * 返回值=实际执行的动作（null=被闸门拦下），e2e 用它断言「键序列→状态迁移」。
   * 移植注：Delete 删区域与图片/视频变焦（6-3）在此只留闸位不执行；Ctrl+Z/Y 撤销重做已接真栈（6-5）。
   */
  async onKey(evt: {
    key: string; ctrlKey?: boolean; metaKey?: boolean; shiftKey?: boolean; altKey?: boolean
    tagName?: string; inModal?: boolean
  }): Promise<string | null> {
    // 4637：弹窗打开时只让弹窗自己处理；背后的画布不接受 S/J/Ctrl+Enter
    // BUG-PARITY-1：panelModalOpen（面板编辑类别/区域多类别）并入同一闸（Vue .modal-mask 全挡）
    if (this.modalOpen || this.confirmOpen || this.panelModalOpen || evt.inModal) return null
    const ctrl = !!(evt.ctrlKey || evt.metaKey)
    const tag = (evt.tagName || '').toLowerCase()
    const typing = tag === 'input' || tag === 'textarea' || tag === 'select'
    if (ctrl && evt.key === 'Enter') { await this.save(); return 'save' }
    if (ctrl && String(evt.key || '').toLowerCase() === 's') { await this.save(); return 'save' }
    if (ctrl && !typing) {
      // Vue:4649-4652：文本框内保留浏览器原生撤销；画布/空白处才拦截。
      // shift+Z=重做、Y=重做、Z=撤销；执行与否都消费按键（preventDefault 归组件，与原件一致）
      const k = String(evt.key || '').toLowerCase()
      if (k === 'z') return evt.shiftKey ? (this.redo() ? 'redo' : 'redo-empty')
        : (this.undo() ? 'undo' : 'undo-empty')
      if (k === 'y') return this.redo() ? 'redo' : 'redo-empty'
    }
    if (evt.key === 'Escape') return 'escape'              // 组件：关帮助/队列抽屉
    if (typing) return null
    if (!ctrl && !evt.altKey) {
      if (evt.key === '/' ) { this.focusAnswer(); return 'focus-answer' }
      // f/F 专注、图片缩放 +-=0w、视频 ,. 逐帧：均归画布卡（6-3/6-5），本壳不拦
    }
    if (evt.key === 'j' || evt.key === 'J') { await this.navigateAsync(1); return 'next' }
    if (evt.key === 'k' || evt.key === 'K') { await this.navigateAsync(-1); return 'prev' }
    if (evt.key === 's' || evt.key === 'S') { await this.skip(); return 'skip' }
    if (/^[1-9]$/.test(evt.key)) {
      // 4683：有两组以上分类时不绑数字键，否则按 1 会悄悄改到第一组
      const n = Number(evt.key)
      const opt = this.ctl.choicesCtls.length === 1 && this.ctl.choiceCtl
        && (this.ctl.choiceCtl.options || [])[n - 1]
      if (opt) {
        const name = this.ctl.choiceCtl!.from_name
        if (this.ctl.choiceCtl!.multiple) {
          const arr = Array.isArray(this.s.choiceVals[name])
            ? (this.s.choiceVals[name] as string[]).slice() : []
          const i2 = arr.indexOf(opt.value)
          if (i2 >= 0) arr.splice(i2, 1)
          else arr.push(opt.value)
          this.setChoice(name, arr)
        } else {
          this.setChoice(name, opt.value)   // 单选绑字符串，radio 才勾得上
        }
        return 'choice:' + opt.value
      }
      return null
    }
    if (evt.key === 'Delete') return 'delete-deferred'      // 6-3
    return null
  }
}
