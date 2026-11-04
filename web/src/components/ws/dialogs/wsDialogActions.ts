/**
 * wsDialogActions.ts —— Workbench 弹窗群＋AI 辅助的动作层（REQ-UNIFY-6-6）。
 *
 * 口径：Vue 原件 LabelStudioWorkbench.vue 的「打开→提交→刷新」逐函数移植，
 * 载荷字段/夹取边界/提示文案与原件行号一一对应（行号注释在各动作上）；
 * 画布渲染（predict 框回显）归 6-3，本层只保证「结果落到 model 再 reload」。
 * 纯 TS、不 import react/antd —— 闸2 用同一份代码注入 fake transport 对拍。
 *
 * 通道：所有请求经 dialogApi.ts → /api/mos/api/label-studio/*（REQ-UNIFY-1 已通）。
 */
import { WRITE_BLOCKED_TEXT, getWriteAccess, isWriteForbidden } from '../wsAccess'

export type ConfirmReq = {
  title: string
  message: string
  confirmText?: string
  danger?: boolean
}

/** 弹窗层需要的端点（对照 mos/platform/frontend/src/api.js:261-309 台账）。 */
export interface DialogTransport {
  lsQc(pid: number): Promise<Record<string, any>>                                  // api.js:280
  validateLsConfig(b: Record<string, unknown>): Promise<Record<string, any>>       // api.js:275
  patchLsProject(pid: number, b: Record<string, unknown>): Promise<Record<string, any>> // api.js:267
  /** BUG-PARITY-5：openSettings 补拉用（Vue:3987 api.lsProject；同 wsApi.ts:61 端点）。 */
  lsProject(pid: number): Promise<Record<string, any>>
  lsProjectImport(pid: number, b: Record<string, unknown>): Promise<Record<string, any>> // api.js:291
  lsPreview(pid: number, tid: number): Promise<Record<string, any>>                // api.js:288
  lsPredict(pid: number, b: Record<string, unknown>): Promise<Record<string, any>> // api.js:292
  aiStatus(): Promise<Record<string, any>>                                         // api.js:295
  aiConfig(pid: number): Promise<Record<string, any>>                              // api.js:296
  saveAiConfig(pid: number, b: Record<string, unknown>): Promise<unknown>          // api.js:297
  aiStats(pid: number, days: number): Promise<Record<string, any>>                 // api.js:298
  lsAutoTrain(): Promise<Record<string, any>>                                      // api.js:271
  saveLsAutoTrain(b: Record<string, unknown>): Promise<Record<string, any>>        // api.js:272
  lsSyncMap(): Promise<Record<string, any>>                                        // api.js:269
  saveLsSyncMap(b: Record<string, unknown>): Promise<Record<string, any>>          // api.js:270
  mediaList(kind: string, q: string, limit: number): Promise<Record<string, any>>  // api.js:233
}

/** 弹窗层向 WorkbenchModel 要的最小读面/动作面（React 适配器注入；闸2 注入 fake）。 */
export interface DialogHost {
  pid(): number
  project(): Record<string, any> | null
  schema(): { mode?: string; controls?: Array<Record<string, any>>; media?: Record<string, any> | null }
  selected(): Record<string, any> | null
  dirty(): boolean
  datasetId(): string
  syncResult(): Record<string, any> | null
  loadStats(): Promise<void>
  loadTasks(): Promise<void>
  /** 4470：跑完/同步完重新拉当前任务（Vue pick(selected,0,false)）。 */
  reloadSelected(): Promise<void>
  /** 弹窗闸门（Vue:4637 三弹窗 + .modal-mask；React 统一走 model.modalOpen）。 */
  setModalGate(on: boolean): void
  /** saveSettings 后回写 project（Vue:4033 project.value=p；React 走整壳重载保 schema/ctl 一致）。 */
  afterProjectChange(): Promise<void>
  /** BUG-PARITY-5：openSettings 补拉成功后的回填（Vue:3987 project.value=p 的 React 等价，
   *  连带 schema/ctl 重导出——裸赋值会留下 project 新、ctl 旧的半初始化壳）。 */
  adoptProject(p: Record<string, any>): void
  /** 一键修复的前置判据（Vue hasShapeCtl:1411 / :3904）：画布已有框选类控件就不用再补。 */
  hasShapeCtl(): boolean
}

export interface DialogIo {
  toast: (lv: 'ok' | 'bad' | 'warn', t: string) => void
  askConfirm: (o: ConfirmReq) => Promise<boolean>
  now?: () => number
  copyText?: (t: string) => Promise<void>
}

const IMPORT_MODES: Record<string, string> = { image: '图片', audio: '音频', video: '视频' } // Vue:1447
const CONF_MIN = 0.05   // Vue:4420
const CONF_MAX = 0.95   // Vue:4421

function errText(e: unknown): string {
  const x = e as Error
  return x && x.message ? x.message : String(x)
}

export class WsDialogStore {
  private t: DialogTransport
  private h: DialogHost
  private io: DialogIo
  private now: () => number

  version = 0
  private listeners = new Set<() => void>()
  constructor(t: DialogTransport, h: DialogHost, io: DialogIo) {
    this.t = t
    this.h = h
    this.io = io
    this.now = io.now ?? (() => Date.now())
  }
  subscribe(cb: () => void): () => void {
    this.listeners.add(cb)
    return () => { this.listeners.delete(cb) }
  }
  private notify() { this.version++; this.listeners.forEach((cb) => cb()) }
  /** 受控输入回调后触发重渲染（React 侧直接改字段时用）。 */
  touch() { this.notify() }

  // ---------- 状态（Vue refs 对照：1490-1546） ----------
  aiStatus: Record<string, any> = { yolo: { ok: false }, vlm: { ok: false } } // 1493
  aiStats: Record<string, any> = {}                                            // 1492
  aiEngine = 'auto'                                                             // 1492
  predictLimit = 5                                                              // 1494
  predictConf = 0.35                                                            // 1497
  predicting = false                                                            // 1490
  aiRun: Record<string, any> | null = null                                      // 1500
  aiMapOpen = false                                                             // 1501
  aiUnmapped: Record<string, any>[] = []                                        // 1495?（1611 区）
  aiMap: Record<string, string> = {}                                            // 1502

  importOpen = false        // 1510
  importing = false         // 1511
  importText = ''           // 1512
  importSource: 'media' | 'json' = 'media'  // 1513
  importPicked: Record<string, any>[] = []  // 1514
  // BUG-PARITY-13-C：mediaItems/mediaQ/mediaLoading（平铺 200 封顶降级壳的残留态）已删，
  // 媒体浏览/分页归 MosMediaPicker 自持（老 MediaPicker.vue 就是组件内自加载）。
  // DialogTransport.mediaList 保留在台账里（api.js:233 对照），UI 侧已无调用方。

  qcOpen = false            // 1526
  qcLoading = false         // 1528
  qc: Record<string, any> | null = null  // 1525

  settingsOpen = false       // 1516
  savingSettings = false     // 1517
  configCheck: Record<string, any> | null = null // 1518
  /** BUG-LABEL-WRITE-403-1②：validate-config 因无写权限 403——徽标位要显示
   * 「配置检查不可用（无权限）」而不是消失（silent 预校验也计）。 */
  configCheckDenied = false
  cfgAdvanced = false        // 1519
  checkingConfig = false     // 1520
  // BUG-PARITY-5 一键修复在途位（Vue refs:1637-1638；横幅钮 disabled+「添加中…」）
  fixingDesc = false         // 1637 给旧图片模板补描述输入框
  fixingBox = false          // 1638 给只有分类的图片项目补框选控件
  settings = { title: '', description: '', expert_instruction: '', label_config: '',
               show_instruction: true, show_skip_button: true, enable_empty_annotation: true } // 1521-1524

  previewOpen = false        // 1527
  previewLoading = false     // 1528-1529
  previewRaw = false
  previewData: Record<string, any> | null = null // 1530

  autoPanelOpen = false
  autoSync = false           // 1524-1525 区
  autoTrain = false          // 1544
  autoTrainMin = 10          // 1545
  autoTrainInfo: Record<string, any> | null = null // 1546
  syncLast: Record<string, any> | null = null      // 1529
  syncInterval = 300         // syncInfo.interval:1527

  private previewSeq = 0     // 4524 let previewSeq

  // ---------- 派生（Vue computed 对照） ----------
  get mode(): string { return this.h.schema().mode || 'text' }
  get importMediaOk() { return !!IMPORT_MODES[this.mode] }            // 1449
  get importModeLabel() { return IMPORT_MODES[this.mode] || '文本' }   // 1450
  get importDataKey(): string {                                       // 1451
    const m = this.h.schema().media
    return (m && m.field) || this.mode
  }
  get rectLabels(): string[] {                                        // 3733-3736
    const c = (this.h.schema().controls || []).find((x) => x.type === 'rectanglelabels')
    return ((c && c.options) || []).map((o: Record<string, any>) => o.value)
  }
  get predictOk(): boolean {                                          // 3727-3731
    const kinds = (this.h.schema().controls || []).map((c) => c.type)
    return kinds.length > 0
      && kinds.every((t) => ['textarea', 'choices', 'rectanglelabels'].includes(t))
  }
  get aiReady(): boolean {                                            // 3746-3747
    return !!(this.aiStatus.yolo && this.aiStatus.yolo.ok)
      || !!(this.aiStatus.vlm && this.aiStatus.vlm.ok)
  }
  get aiStatusText(): string {                                        // 3748-3754
    const y = this.aiStatus.yolo && this.aiStatus.yolo.ok
    const v = this.aiStatus.vlm && this.aiStatus.vlm.ok
    return [y ? 'YOLO 检测框可用' : 'YOLO 未安装',
            v ? ('VLM 在线' + (this.aiStatus.vlm.model ? '（' + this.aiStatus.vlm.model + '）' : ''))
              : 'VLM 未配置（文本/描述预标注不可用）'].join(' · ')
  }
  get aiTagTitle(): string {                                          // 3755-3763
    const s = this.aiStats && this.aiStats.runs
      ? ' · 本项目已生成 ' + this.aiStats.runs + ' 次 / ' + (this.aiStats.boxes || 0) + ' 框'
        + (this.aiStats.adopted ? '，已写成标注 ' + this.aiStats.adopted : '')
      : ''
    return this.aiStatusText + s
  }
  get syncEvery(): string {                                           // 3804（模板 :990「每 {{syncEvery}}」）
    const m = Math.round((this.syncInterval || 300) / 60)
    return '每 ' + m + ' 分钟自动对账一次'
  }
  get previewJson(): string {                                         // 4551-4552
    return this.previewData ? JSON.stringify(this.previewData.record, null, 2) : ''
  }
  get previewRawJson(): string {                                      // 4553-4554
    return this.previewData ? JSON.stringify(this.previewData.raw_result || [], null, 2) : ''
  }

  private confValue(): number {                                       // 4422-4426
    const v = Number(this.predictConf)
    if (!Number.isFinite(v)) return 0.35
    return Math.max(CONF_MIN, Math.min(CONF_MAX, v))
  }
  /** 3738-3744 watch(rectLabels)：框选项目不用 VLM、文本项目不用 YOLO。 */
  private normalizeEngine() {
    if (this.rectLabels.length && this.aiEngine === 'vlm') this.aiEngine = 'auto'
    if (!this.rectLabels.length && this.aiEngine === 'yolo') this.aiEngine = 'auto'
  }

  /** 挂载/切项目：ai-status / ai-config 记忆 / ai-stats / auto-train / sync-map（Vue loadShell:3830 弹窗侧半边 + loadSyncMap:3930）。 */
  async init() {
    const pid = this.h.pid()
    if (!Number.isFinite(pid) || pid <= 0) return
    this.aiRun = null            // 3835：换项目后上一次的结果条不留着
    try { this.aiStatus = await this.t.aiStatus() } catch { /* 3851 catch(()=>null)：拉不到保持默认 */ }
    try {
      const aic = await this.t.aiConfig(pid)          // 3857-3859
      if (aic && Number(aic.conf) > 0) this.predictConf = Number(aic.conf)
      if (aic && ['auto', 'yolo', 'vlm'].includes(aic.engine)) this.aiEngine = aic.engine
    } catch { /* ignore */ }
    this.normalizeEngine()
    void this.loadAiStats()
    await this.loadAutoState()
    this.notify()
  }
  async loadAiStats() {                                               // 3826-3828
    try { this.aiStats = await this.t.aiStats(this.h.pid(), 30) } catch { /* 统计不影响标注 */ }
    this.notify()
  }
  /** loadSyncMap:3930 的 autoSync/autoTrain 半边（sync-map 数据面 6-1 已在 model）。 */
  async loadAutoState() {
    try {
      const d = await this.t.lsSyncMap()
      this.syncInterval = d.interval || 300
      const ent = (d.items || []).find((e: Record<string, any>) => Number(e.project_id) === this.h.pid())
      // 3934-3935：关闭自动对账只把 enabled 置 false，不能拿「有没有映射」判断开关
      this.autoSync = !!ent && ent.enabled !== false
      this.syncLast = (d.last || {})[String(this.h.pid())] || null
    } catch { /* ignore */ }
    try {
      const a = await this.t.lsAutoTrain()                            // 3941-3945
      const pol = (a.items || []).find((x: Record<string, any>) => Number(x.project_id) === this.h.pid())
      this.autoTrain = !!pol
      this.autoTrainMin = pol ? Number(pol.min_new) || 10 : 10
      this.autoTrainInfo = pol || null
    } catch { /* ignore */ }
    this.notify()
  }

  // ---------- 导入（模板 1026-1064 / doImport:4594 / watch:4587） ----------
  setImportOpen(v: boolean) {
    if (v) { // 4586-4590：每次打开回到「从媒体库选择」，避免上次 JSON 残留
      this.importSource = 'media'
      this.importPicked = []
      this.importText = ''
    }
    this.importOpen = v
    this.h.setModalGate(this.anyOpen())
    this.notify()
    // BUG-PARITY-13-C：不再预拉平铺 200 条——MosMediaPicker 挂载即按目录自加载（老同构）
  }
  /** 媒体树选择器整体回填（替代逐条 toggleMediaPick；doImport 仍只吃 m.ls_url，:4600 口径不变）。 */
  setImportPicked(list: Record<string, any>[]) {
    this.importPicked = list
    this.notify()
  }
  async doImport() {                                                  // 4594-4615
    // BUG-LABEL-WRITE-403-1②：只读态拦下写请求（按钮已灰，此处兜住回车/竞态路径）
    if (getWriteAccess() === 'readonly') { this.io.toast('bad', WRITE_BLOCKED_TEXT); return }
    this.importing = true
    this.notify()
    try {
      let list: Record<string, any>[]
      if (this.importSource === 'media') {
        if (!this.importMediaOk) {
          throw new Error('当前项目是' + this.importModeLabel + '画布，请改用「粘贴 JSON」') // 4598
        }
        if (!this.importPicked.length) throw new Error('先从媒体库选至少 1 个文件')          // 4599
        list = this.importPicked.map((m) => ({ data: { [this.importDataKey]: m.ls_url } }))  // 4600
      } else {
        try {
          list = JSON.parse(this.importText || '[]')                                          // 4603
        } catch (e) {
          throw new Error('JSON 格式不对：' + errText(e))                                     // 4605
        }
        if (!Array.isArray(list) || !list.length) throw new Error('请粘贴非空 JSON 数组')      // 4606
        const badIdx = list.findIndex((x) => !x || typeof x.data !== 'object' || !x.data)      // 4607
        if (badIdx >= 0) throw new Error('第 ' + (badIdx + 1) + ' 条任务缺少 data 对象')       // 4608
      }
      const r = await this.t.lsProjectImport(this.h.pid(), { tasks: list })                   // 4610
      this.io.toast('ok', '导入完成：' + (r.task_count ?? list.length) + ' 条')                // 4611
      this.importOpen = false
      this.importPicked = []
      await Promise.all([this.h.loadStats(), this.h.loadTasks()])                             // 4614
    } catch (e) {
      this.io.toast('bad', errText(e))
    } finally {
      this.importing = false
      this.h.setModalGate(this.anyOpen())
      this.notify()
    }
  }

  // ---------- 质量检查（模板 1067-1103 / openQc:3949） ----------
  async openQc() {
    this.qcLoading = true
    this.qcOpen = true
    this.qc = null          // 3952-3953：先清空，失败时不能把上个项目的统计当成本项目的
    this.h.setModalGate(true)
    this.notify()
    try { this.qc = await this.t.lsQc(this.h.pid()) } catch (e) {
      this.qc = null
      this.io.toast('bad', errText(e))                                     // 3955
    } finally {
      this.qcLoading = false
      this.h.setModalGate(this.anyOpen())
      this.notify()
    }
  }
  closeQc() { this.qcOpen = false; this.h.setModalGate(this.anyOpen()); this.notify() }

  // ---------- 项目设置（模板 1106-1218 / openSettings:3981 / saveSettings:4008 / checkConfig:3971） ----------
  setSettingsOpen(v: boolean) { this.settingsOpen = v; this.h.setModalGate(v); this.notify() }
  async openSettings() {
    const pid = this.h.pid()
    if (!Number.isFinite(pid) || pid <= 0) return                          // 3982-3983
    // BUG-PARITY-5：回搬 Vue:3984-3992 的「补一次加载」——项目还没回来点「项目设置」
    // 也要点得开，失败给后端原文 toast。旧写法 `if (!this.h.project()) return` 静默，
    // 正是老版专门修过的「按钮没反应」坑重引入；旧注释「顶栏入口随 project 渲染」
    // 也与事实不符（WsDialogBar 在 MosWorkbench.tsx:190 无条件渲染）。
    if (!this.h.project()) {
      try {
        this.h.adoptProject(await this.t.lsProject(pid))                    // 3987
      } catch (e) {
        this.io.toast('bad', errText(e))                                    // 3989
        return
      }
    }
    // 3997-4005：三个 !==false 默认（历史项目字段缺失按开启）
    const p = this.h.project() as Record<string, any>
    this.settings = {
      title: p.title || '',
      description: p.description || '',
      expert_instruction: p.expert_instruction || '',
      label_config: p.label_config || '',
      show_instruction: p.show_instruction !== false,
      show_skip_button: p.show_skip_button !== false,
      enable_empty_annotation: p.enable_empty_annotation !== false,
    }
    this.configCheck = null
    this.configCheckDenied = false
    this.cfgAdvanced = false
    this.settingsOpen = true
    this.h.setModalGate(true)
    this.notify()
    void this.checkConfig(true)                                            // 4006 静默预校验
  }
  async checkConfig(silent = false) {                                      // 3971-3979
    this.checkingConfig = true
    this.notify()
    try {
      this.configCheck = await this.t.validateLsConfig({ label_config: this.settings.label_config })
      this.configCheckDenied = false
    } catch (e: any) {
      if (!silent) this.io.toast('bad', errText(e))
      // 无写权限 403：徽标位留常驻说明，不静默消失（卡面②「配置检查不可用」）
      this.configCheckDenied = isWriteForbidden(e && e.status, errText(e))
      this.configCheck = null
    } finally {
      this.checkingConfig = false
      this.notify()
    }
  }
  onCfgInput(v: string) {                                                  // 模板 @input：configCheck=null
    this.settings.label_config = v
    this.configCheck = null
    this.configCheckDenied = false
    this.notify()
  }
  async saveSettings() {                                                   // 4008-4037
    if (getWriteAccess() === 'readonly') { this.io.toast('bad', WRITE_BLOCKED_TEXT); return }
    this.savingSettings = true
    this.notify()
    try {
      const original = (this.h.project() && (this.h.project() as Record<string, any>).label_config) || ''
      const cfgChanged = this.settings.label_config !== original           // 4010
      if (cfgChanged) {
        const check = await this.t.validateLsConfig({ label_config: this.settings.label_config }) // 4012
        this.configCheck = check
        if (!check.ok) {
          this.io.toast('bad', 'label_config 有错误，先按提示修正')          // 4015
          return
        }
      }
      const payload: Record<string, unknown> = {                           // 4020-4026
        title: this.settings.title.trim(),
        description: this.settings.description,
        expert_instruction: this.settings.expert_instruction,
        show_instruction: this.settings.show_instruction,
        show_skip_button: this.settings.show_skip_button,
        enable_empty_annotation: this.settings.enable_empty_annotation,
      }
      if (cfgChanged) payload.label_config = this.settings.label_config    // 4027-4028：没改结构不回写
      await this.t.patchLsProject(this.h.pid(), payload)                   // 4029
      this.io.toast('ok', '项目设置已保存')                                 // 4031
      this.settingsOpen = false
      await this.h.afterProjectChange()                                    // 4033 project 回写（React 走整壳重载）
    } catch (e) {
      this.io.toast('bad', errText(e))                                     // 4036
    } finally {
      this.savingSettings = false
      this.h.setModalGate(this.anyOpen())
      this.notify()
    }
  }

  // ---------- 缺控件一键修复（addImageDescription:3878-3897 / addImageBoxControl:3901-3928） ----------
  /** 旧版图片模板只有 RectangleLabels、任务却显示「请描述这张图片」：只追加一个 TextArea，
   *  不碰已有控件（可逆）。Vue 头注 :3876-3877。 */
  async addImageDescription() {
    const p = this.h.project()                                              // 3879
    if (!p || this.fixingDesc) return
    if (getWriteAccess() === 'readonly') { this.io.toast('bad', WRITE_BLOCKED_TEXT); return }
    const cfg = String(p.label_config || '')                                // 3880
    if (/<textarea\b/i.test(cfg)) return                                    // 3881
    if (!/<\/View>\s*$/i.test(cfg)) {                                       // 3882-3884
      this.io.toast('bad', '这条配置不是标准 <View> 结构，无法自动添加；请到项目设置里手动加 TextArea')
      return
    }
    const add = '\n  <TextArea name="description" toName="image" rows="3" editable="true"'
      + ' placeholder="用一句话描述这张图片 / 框选结果"/>'                   // 3886-3887
    this.fixingDesc = true
    this.notify()
    try {
      await this.t.patchLsProject(this.h.pid(), {                           // 3890-3892
        label_config: cfg.replace(/<\/View>\s*$/i, add + '\n</View>'),
      })
      this.io.toast('ok', '已加上描述输入框；已有框选标注不受影响')            // 3893
      await this.h.afterProjectChange()                                     // React 整壳重载保 schema/ctl 一致
    } catch (e) {
      this.io.toast('bad', '添加失败：' + errText(e))                        // 3895
    } finally {
      this.fixingDesc = false
      this.notify()
    }
  }

  /** 图片项目只有 Choices/TextArea 时，在图上拖拽不会有任何反应：
   *  用现有类别生成 RectangleLabels（可逆，不动已有控件）。Vue 头注 :3899-3900。 */
  async addImageBoxControl() {
    const p = this.h.project()                                              // 3902
    if (!p || this.fixingBox) return
    if (getWriteAccess() === 'readonly') { this.io.toast('bad', WRITE_BLOCKED_TEXT); return }
    if (this.h.hasShapeCtl()) return                                        // 3904
    const cfg = String(p.label_config || '')                                // 3903
    const choices = (this.h.schema().controls || [])
      .find((c: Record<string, any>) => c.type === 'choices')               // 3905
    const labels = ((choices && choices.options) || [])
      .map((o: Record<string, any>) => o.value).filter(Boolean)             // 3906
    if (!labels.length) {                                                   // 3907-3910
      this.io.toast('bad', '这个项目没有类别可选，无法自动生成框选；请到项目设置里手动添加 RectangleLabels')
      return
    }
    const block = '\n  <RectangleLabels name="bbox" toName="image">'        // 3911-3913
      + labels.map((l: string) => '<Label value="' + l + '"/>').join('')
      + '</RectangleLabels>'
    const next = /<Image\b[^>]*\/>/i.test(cfg)                              // 3914-3916
      ? cfg.replace(/(<Image\b[^>]*\/>)/i, '$1' + block)
      : cfg.replace(/<\/View>\s*$/i, block + '\n</View>')
    if (next === cfg) {                                                     // 3917-3920
      this.io.toast('bad', '这条配置不是标准 <View> 结构，无法自动添加；请到项目设置里手动加 RectangleLabels')
      return
    }
    this.fixingBox = true
    this.notify()
    try {
      await this.t.patchLsProject(this.h.pid(), { label_config: next })     // 3923
      this.io.toast('ok', '已用现有类别加上框选控件，现在可以直接在图上拖拽画框') // 3924
      await this.h.afterProjectChange()                                     // React 整壳重载保 schema/ctl 一致
    } catch (e) {
      this.io.toast('bad', '添加失败：' + errText(e))                        // 3926
    } finally {
      this.fixingBox = false
      this.notify()
    }
  }

  // ---------- AI 标注（predict:4427） ----------
  setEngine(v: string) { this.aiEngine = v; this.notify() }
  setPredictLimit(v: number) { this.predictLimit = v; this.notify() }
  setPredictConf(v: number) { this.predictConf = v; this.notify() }
  async predict() {
    if (this.predicting) return                                            // 4428
    // AI 标注会直接写成标注，属写链：只读态拦下
    if (getWriteAccess() === 'readonly') { this.io.toast('bad', WRITE_BLOCKED_TEXT); return }
    if (this.h.dirty() && !(await this.io.askConfirm({                     // 4431-4437
      title: '当前标注还没保存',
      message: 'AI 标注会给未标注的图直接写入标注；跑完当前这条会重新加载，'
        + '画布上没保存的改动会被覆盖（草稿里还能恢复）。先保存再跑更稳妥。',
      confirmText: '仍然运行 AI', danger: true,
    }))) return
    this.predicting = true
    this.notify()
    try {
      const r = await this.t.lsPredict(this.h.pid(), {                     // 4439-4443
        limit: Math.max(1, Math.min(50, Number(this.predictLimit) || 5)),
        only_unannotated: true,
        engine: this.aiEngine,
        conf: this.confValue(),
      })
      const boxes = (r.predictions || []).reduce((n: number, x: Record<string, any>) => n + (Number(x.boxes) || 0), 0) // 4444
      if (r.requested) {                                                   // 4445-4449
        this.aiRun = {
          created: Number(r.created) || 0, boxes,
          unmapped: r.unmapped || [], note: r.note || '', at: this.now(),
        }
      }
      const rl = this.rectLabels                                           // 4450-4472 分支文案
      if (!r.requested) this.io.toast('warn', r.message || '没有待标注任务')
      else if (boxes > 0) {
        this.io.toast('ok', 'AI 标注完成：' + r.created + ' 条，共 ' + boxes + ' 个框，已直接写成标注'
          + (r.errors?.length ? '，失败 ' + r.errors.length : '')
          + (r.unmapped?.length ? '，有 ' + r.unmapped.length + ' 类没映射' : ''))
      } else if (r.created > 0 && !rl.length) {
        this.io.toast('ok', 'AI 标注完成：' + r.created + ' 条，已直接写成标注'
          + (r.errors?.length ? '，失败 ' + r.errors.length : ''))
      } else if (r.unmapped?.length) {
        this.io.toast('warn', 'AI 生成了 ' + r.created + ' 条，但 0 个框：YOLO 检出的 '
          + r.unmapped.map((u: Record<string, any>) => u.label).join('、')
          + ' 对不上项目标签，点「映射标签」选好后再生成')
      } else if (!r.created) {
        this.io.toast('warn', '这 ' + r.requested + ' 张都没检出可用目标（已记下，改阈值或映射后会自动重试）')
      } else {
        this.io.toast('warn', 'AI 生成了 ' + r.created + ' 条，但 0 个框：YOLO 在图上没检出可用目标，'
          + '把阈值调低（如 0.1）再试')
      }
      await Promise.all([this.h.loadStats(), this.h.loadTasks(), this.loadAiStats()]) // 4473
      if (this.h.selected()) await this.h.reloadSelected()                             // 4474
      if (r.unmapped?.length && rl.length) {                                           // 4475-4479
        this.aiUnmapped = r.unmapped
        this.aiMap = {}
        this.aiMapOpen = true
        this.h.setModalGate(true)
      }
    } catch (e) {
      this.io.toast('bad', errText(e))                                   // 4481
    } finally {
      this.predicting = false
      this.notify()
    }
  }

  // ---------- AI 标签映射（模板 1221-1244 / openAiMap:1503 / saveAiMapAndRerun:4483） ----------
  openAiMap() {                                                          // 1503-1507
    this.aiUnmapped = (this.aiRun && this.aiRun.unmapped) || []
    this.aiMap = {}
    this.aiMapOpen = true
    this.h.setModalGate(true)
    this.notify()
  }
  closeAiMap() { this.aiMapOpen = false; this.h.setModalGate(false); this.notify() }
  setAiMapRow(label: string, v: string) { this.aiMap[label] = v; this.notify() }
  async saveAiMapAndRerun() {
    const map: Record<string, string> = {}                               // 4484-4486
    for (const [k, v] of Object.entries(this.aiMap || {})) if (v) map[k] = v
    try {                                                                // 4488-4492：后端整份替换，先并进已存映射
      const cfg = await this.t.aiConfig(this.h.pid())
      Object.assign(map, (cfg && cfg.label_map) || {}, map)
    } catch { /* 读不到就只提交本次映射 */ }
    const sel = this.h.selected()                                        // 4494-4500
    if (sel && (sel.annotated || this.h.dirty()) && !(await this.io.askConfirm({
      title: '会覆盖当前这条的标注',
      message: '「保存映射并重新生成」会用 AI 结果覆盖当前任务已有的标注'
        + '（包括人工画的框）。确定继续吗？',
      confirmText: '覆盖并重新生成', danger: true,
    }))) return
    this.predicting = true
    this.notify()
    try {
      await this.t.saveAiConfig(this.h.pid(), { label_map: map })        // 4502
      this.aiMapOpen = false
      const rl = this.rectLabels                                         // 4503-4519
      if (sel) {
        const r = await this.t.lsPredict(this.h.pid(), {
          task_ids: [sel.id], only_unannotated: false, engine: this.aiEngine,
        })
        const left = r.unmapped || []
        const boxN = (r.predictions || []).reduce((n: number, x: Record<string, any>) => n + (Number(x.boxes) || 0), 0)
        this.aiRun = { created: Number(r.created) || 0, boxes: boxN,
                       unmapped: left, note: r.note || '', at: this.now() }
        this.io.toast('ok', left.length ? ('已重新生成，仍有未映射：' + left.map((u: Record<string, any>) => u.label).join('、'))
          : (rl.length ? ('已重新生成 ' + boxN + ' 个框') : ('已重新生成 ' + (Number(r.created) || 0) + ' 条标注')))
        await Promise.all([this.h.loadStats(), this.h.loadTasks(), this.loadAiStats()])
        await this.h.reloadSelected()
      }
    } catch (e) {
      this.io.toast('bad', '保存映射失败: ' + errText(e))                // 4521
    } finally {
      this.predicting = false
      this.h.setModalGate(this.anyOpen())
      this.notify()
    }
  }

  // ---------- 自动对账 / 自动训练（toggleAutoSync:4070 / toggleAutoTrain:4037 / saveAutoTrainMin:4065） ----------
  setAutoPanelOpen(v: boolean) {
    this.autoPanelOpen = v
    this.h.setModalGate(v)
    this.notify()
    if (v) void this.loadAutoState()
  }
  private autoDid(): string {                                            // 4040/4071 did 同源
    return this.h.datasetId() || ((this.h.syncResult() || {}) as Record<string, any>).dataset_id || ''
  }
  async toggleAutoTrain(fromMin = false) {
    const onlyMin = fromMin === true                                     // 4038-4039
    const did = this.autoDid()
    if (this.autoTrain && !did) {                                        // 4041-4044
      this.autoTrain = false
      this.io.toast('bad', '先同步一次或选择一个标注集，再开启自动训练')
      this.notify()
      return
    }
    try {
      const d = await this.t.saveLsAutoTrain({                           // 4046-4049
        project_id: this.h.pid(), dataset_id: did || '',
        enabled: this.autoTrain, min_new: Math.max(1, Number(this.autoTrainMin) || 10),
        method: 'sft', params: { max_steps: 50, max_length: 1024 },
      })
      this.autoTrainInfo = (d.items || []).find((x: Record<string, any>) => Number(x.project_id) === this.h.pid()) || null
      this.io.toast('ok', this.autoTrain ? '已开启自动训练（新增 ≥ ' + this.autoTrainMin + ' 条）' : '已关闭自动训练') // 4052
    } catch (e) {
      if (onlyMin) {
        // 4054-4057：只改条数失败不能把开关静默翻成关闭 —— 重读状态为准
        await this.loadAutoState()
      } else {
        this.autoTrain = !this.autoTrain                                 // 4058-4059
      }
      this.io.toast('bad', errText(e))
    } finally {
      this.notify()
    }
  }
  setAutoTrainInput(v: number) { this.autoTrainMin = v; this.notify() }
  async saveAutoTrainMin() {                                             // 4065-4068
    if (!this.autoTrain) return
    await this.toggleAutoTrain(true)
  }
  async toggleAutoSync() {
    const did = this.autoDid()
    if (this.autoSync && !did) {                                         // 4071-4075
      this.autoSync = false
      this.io.toast('bad', '先同步一次或选择一个标注集，再开启自动对账')
      this.notify()
      return
    }
    try {
      const d = await this.t.saveLsSyncMap({                             // 4077-4079
        project_id: this.h.pid(), dataset_id: did || '', enabled: this.autoSync,
      })
      this.syncInterval = d.interval || this.syncInterval                // 4080-4081
      this.syncLast = (d.last || {})[String(this.h.pid())] || null
      this.io.toast('ok', this.autoSync ? '已开启自动对账' : '已关闭自动对账') // 4082
    } catch (e) {
      this.autoSync = !this.autoSync                                     // 4083-4085
      this.io.toast('bad', errText(e))
    } finally {
      this.notify()
    }
  }

  // ---------- 预览训练样本（模板 1246-1263 / openPreview:4525 / copyPreview:4545） ----------
  async openPreview() {
    const tid = this.h.selected() && (this.h.selected() as Record<string, any>).id  // 4526-4527
    if (!tid) return
    const seq = ++this.previewSeq                                        // 4528
    this.previewOpen = true
    this.previewLoading = true
    this.previewData = null
    this.h.setModalGate(true)
    this.notify()
    try {
      const d = await this.t.lsPreview(this.h.pid(), tid)                // 4531-4536
      if (seq === this.previewSeq) this.previewData = d
    } catch (e) {
      if (seq === this.previewSeq) {                                     // 4537-4541
        this.io.toast('bad', '预览失败：' + errText(e))
        this.previewOpen = false
      }
    } finally {
      if (seq === this.previewSeq) {
        this.previewLoading = false
        this.h.setModalGate(this.anyOpen())
        this.notify()
      }
    }
  }
  closePreview() { this.previewOpen = false; this.h.setModalGate(false); this.notify() }
  setPreviewRaw(v: boolean) { this.previewRaw = v; this.notify() }
  async copyPreview() {                                                  // 4545-4550
    try {
      await (this.io.copyText ?? (async () => { throw new Error('clipboard 不可用') }))(this.previewJson)
      this.io.toast('ok', '训练 JSON 已复制')
    } catch (e) {
      this.io.toast('bad', '复制失败：' + errText(e))
    }
  }

  /** 模板 :1166 历史配置提示判定用（settings.label_config === project.label_config）。 */
  get origLabelConfig(): string { return (this.h.project() || {}).label_config || '' }

  /** 任一弹窗开着（onKey 闸门；Vue:4637 显式三弹窗 + :9 帮助/映射/预览/面板同闸）。 */
  anyOpen(): boolean {
    return this.importOpen || this.qcOpen || this.settingsOpen
      || this.aiMapOpen || this.previewOpen || this.autoPanelOpen
  }
}
