/**
 * engine.ts —— Workbench 画布引擎（REQ-UNIFY-6-3，框架无关）。
 *
 * 来源对照（只读，LabelStudioWorkbench.vue）：缩放/全屏 3063-3168 /
 *   视频帧与关键帧 3170-3229 / 绘制 3230-3341 / 选中与拖动 3343-3526 /
 *   boxRect|upsertKeyframe|stampKeyframe 3528-3554 / endDraw 3555-3595 /
 *   时间序列 2720-2814 / 媒体事件 3035-3056 / 快捷键执行 4655-4714。
 *
 * 设计（评审口径「基本不依赖 Vue 响应式」→ 引擎零框架依赖）：
 *   - host = 6-1 WorkbenchModel 的结构子集（s/ctl/mode/selected/touch），区域写回
 *     model.s.*，保存/契约（wsSchema.buildResult）自动吃到；
 *   - env = DOM/时钟注入面：浏览器由 WsMediaStage 提供，node 单测喂合成 PointerEvent；
 *   - brush（6-4 已接）：笔迹=env.brush 注入画布 + brush.ts；brushDraft 写回
 *     model.s.brushDraft（Vue brushPaths:1611 同语义，dirty/undo 口径 2046/1675 已含）；
 *     beginDragHistory/endDragHistory（3447/3525 调用点）= 6-5 撤销接缝，注释钉死位置。
 *   - polyDraft 存在 model.s.polyDraft（Vue:1600 同语义，dirty 口径 2046-2047 已含）。
 */
import type { Region, WsControls, WsState } from '../../../pages/mos/wsSchema'
import { newSegmentId, newShapeId } from '../../../pages/mos/wsSchema'
import { BrushLayer } from './brush'
import type { BrushEnv, BrushStroke } from './brush'
import {
  CORNERS, clampPct, round2, stagePointAt, boxRectAt, upsertKeyframe, keyframesOf,
  jumpTarget, clampFrame, totalFramesOf, tsDomainOf, tsRatio, parseSeries, segFromNow,
  segAddError, fixSegmentIn, num,
} from './geometry'
import type { RectLike, Keyframe, TsPoint } from './geometry'
import { canvasKeyAction, frameDelta } from './keys'
import type { CanvasKeyEvt, CanvasKeyAction } from './keys'

export type { CanvasKeyAction }

export interface PointerLike {
  clientX: number
  clientY: number
  pointerId?: number
  pointerType?: string
  isPrimary?: boolean
  button?: number
  preventDefault(): void
  stopPropagation(): void
  currentTarget?: {
    getBoundingClientRect(): RectLike
    setPointerCapture?(id: number): void
    releasePointerCapture?(id: number): void
  } | null
}

/** 6-1 WorkbenchModel 的结构子集（不 import 类，保持引擎可脱离壳单测） */
export interface CanvasHost {
  s: WsState
  ctl: WsControls
  mode: string
  selected: Record<string, any> | null
  touch(): void
  toast?: (level: 'ok' | 'bad' | 'warn', text: string) => void
  /** 6-5 撤销接缝：拖拽=一次事务（beginDragHistory:1777 / endDragHistory:1783）。缺省=不记步。 */
  beginDragHistory?(): void
  endDragHistory?(): void
}

export interface MediaLike {
  currentTime: number
  duration: number
  pause(): void
  play?(): void
  playbackRate: number
}

export interface ScrollLike {
  clientWidth: number
  clientHeight: number
  scrollWidth: number
  scrollHeight: number
  scrollLeft: number
  scrollTop: number
}

export interface EngineEnv {
  /** 画布 stage（图片 img/视频 video 的父容器；Vue stageEl():3343） */
  stageRect(): RectLike | null
  /** 图片滚动容器（Vue imageScroll:1579，fitImage/zoomTo 用） */
  scroll(): ScrollLike | null
  media(): MediaLike | null
  win: {
    innerWidth: number
    addEventListener(t: string, fn: (e: any) => void): void
    removeEventListener(t: string, fn: (e: any) => void): void
  }
  fullscreen: { request(): void; exit(): void; active(): boolean }
  fetchText(url: string): Promise<string>
  /** 画笔注入面（6-4）：屏幕覆盖画布 + 离屏画布；缺失时笔刷静默不落地 */
  brush?: BrushEnv
  /** Vue nextTick 位（React 侧 = rAF/立即；单测立即） */
  afterRender?(fn: () => void): void
}

type ShapeKind = 'box' | 'polygon' | 'keypoint' | 'ellipse'

export class CanvasEngine {
  // ---------- 视图态（Vue refs 1566-1599 对应） ----------
  version = 0
  imageTool = 'box'                                   // 1599 box|polygon|point|ellipse（brush=6-4）
  drawMode = false                                    // 1571 视频「在画面上画框」
  boxLabel = ''                                       // 1572 新图形默认类别
  segLabel = ''                                       // 1573（区间 UI 在 6-2，时钟值归本引擎）
  tsLabel = ''                                        // 1606
  imageZoom = 100                                     // 1581
  imgFit = { w: 0, h: 0 }                             // 1582
  imgLoading = false                                  // 1584
  mediaMissing = false                                // 1454
  currentTime = 0                                     // 1576
  duration = 0                                        // 1577 → 同步写 host.s.duration
  playbackRate = 1                                    // 1585
  focusMode = false                                   // 1583
  maximized = false                                   // 3119
  focusBeforeMax = false                              // 3120
  displaySrc = ''
  tsPoints: TsPoint[] = []                            // 1604
  tsError = ''                                        // 1607
  /** 1600 多边形草稿：**单一来源=host.s.polyDraft**（6-5：撤销/草稿恢复会整体换数组，
   *  本地镜像会悬空——Vue 原件本就是同一个 ref，这里用 getter 对齐）。 */
  get polyDraft(): number[][] { return this.s.polyDraft as number[][] }
  brushSize = 4                                       // 1610 笔刷粗细 %（工具条滑杆 1-15，6-4 消费）
  brushDrawing = false                                // 1613 一次涂抹的按住态
  readonly CORNERS = CORNERS

  draw = { active: false, x0: 0, y0: 0, x: 0, y: 0, w: 0, h: 0, pointerType: 'mouse' }
  boxPress = { active: false, i: -1, sx: 0, sy: 0, slop: 3 }
  shapeDrag = {
    active: false, kind: '' as string, index: -1, mode: 'move', handle: '', vertex: -1,
    sx: 0, sy: 0, ox: 0, oy: 0, ow: 0, oh: 0, points: [] as number[][],
    left: 0, top: 0, width: 1, height: 1,
  }
  tsDrag = { active: false, x0: 0, x1: 0 }
  active: { box: number | null; ellipse: number | null; keypoint: number | null; polygon: number | null } =
    { box: null, ellipse: null, keypoint: null, polygon: null }

  private listeners: Array<() => void> = []
  private taskSeq = 0
  private BOX_PRESS_SLOP = 3                          // 3394
  private BOX_PRESS_SLOP_TOUCH = 10                   // 3396

  /** 画笔层（6-4）：canvas 接触面全部经 env.brush 注入（brush.ts 头注口径） */
  readonly brushLayer: BrushLayer

  constructor(private host: CanvasHost, private env: EngineEnv) {
    this.brushLayer = new BrushLayer(host, env.brush)
  }

  // ---------- 订阅 ----------
  subscribe = (cb: () => void): (() => void) => {
    this.listeners.push(cb)
    return () => { this.listeners = this.listeners.filter((x) => x !== cb) }
  }
  /** 区域/视图变更后统一发射：本地重绘 + 壳层 dirty/按钮重算（touch=6-1 接缝） */
  emit(): void {
    this.version++
    this.host.touch()
    this.listeners.forEach((cb) => cb())
  }
  private tick(fn: () => void): void {
    if (this.env.afterRender) this.env.afterRender(fn)
    else fn()
  }

  // ---------- 派生（Vue computeds 1398-1442 的画布子集） ----------
  get mode(): string { return this.host.mode }
  get s(): WsState { return this.host.s }
  get ctl(): WsControls { return this.host.ctl }
  get vrectCtl() { return this.ctl.vrectCtl }
  /** imageTools:1398-1404（6-4 恢复 brush:1403，顺序照旧：brush 末位） */
  imageTools(): { key: string; label: string }[] {
    return [
      this.ctl.rectCtl ? { key: 'box', label: '画框' } : null,
      this.ctl.polygonCtl ? { key: 'polygon', label: '多边形' } : null,
      this.ctl.keypointCtl ? { key: 'point', label: '关键点' } : null,
      this.ctl.ellipseCtl ? { key: 'ellipse', label: '椭圆' } : null,
      this.ctl.brushCtl ? { key: 'brush', label: '笔刷' } : null,
    ].filter(Boolean) as { key: string; label: string }[]
  }
  /** activeToolCtl:1420-1427 —— brush 分支在 ellipse 后、box 兜底前（1425） */
  activeToolCtl() {
    if (this.mode === 'video') return this.ctl.vrectCtl
    if (this.imageTool === 'polygon') return this.ctl.polygonCtl
    if (this.imageTool === 'point') return this.ctl.keypointCtl
    if (this.imageTool === 'ellipse') return this.ctl.ellipseCtl
    if (this.imageTool === 'brush') return this.ctl.brushCtl
    return this.ctl.rectCtl
  }
  /** activeOptions:1428 */
  activeOptions(): { value?: string }[] {
    return (this.activeToolCtl() && this.activeToolCtl()!.options) || []
  }
  /** shapeLabelCtl:1822-1834 —— 视频框类别在同 toName/label_from_name 的 Labels 上；链尾含 brushCtl（1826） */
  shapeOptions(): { value?: string }[] {
    const c = this.ctl.rectCtl || this.ctl.polygonCtl || this.ctl.keypointCtl
      || this.ctl.ellipseCtl || this.ctl.brushCtl
    if (c) return c.options || []
    const v = this.ctl.vrectCtl
    if (v) return (v.options as { value?: string }[]) || []
    return []
  }
  /** frameRate:1971 —— schema 为准，同步进契约 s.frameRate（buildResult:436 用它） */
  fps(): number { return Number(this.ctl.schema && (this.ctl.schema as any).frame_rate) || 25 }
  /** currentFrame:1972 */
  currentFrame(): number { return Math.round((Number(this.currentTime) || 0) * this.fps()) }
  /** frameCount:1973 */
  frameCount(): number { return Math.max(1, Math.round((Number(this.duration) || 0) * this.fps())) }
  totalFrames(): number { return totalFramesOf(this.duration, this.fps()) }             // 3170
  /** imageStageStyle:3059-3062 */
  imageStageStyle(): { width?: string } {
    if (this.mode !== 'image' || !this.imgFit.w) return {}
    return { width: Math.round(this.imgFit.w * this.imageZoom / 100) + 'px' }
  }
  /** boxStyle:3596-3599（渲染层用；video 走最近关键帧） */
  boxRect(b: Region): Keyframe { return boxRectAt(this.mode === 'video', b, this.currentFrame()) }
  boxStyle(b: Region): { left: string; top: string; width: string; height: string } {
    const r = this.boxRect(b)
    return { left: num(r.x) + '%', top: num(r.y) + '%', width: num(r.w) + '%', height: num(r.h) + '%' }
  }

  // ---------- 绘制（3230-3341 / 3555-3595） ----------
  private stageRectFor(ev: PointerLike): RectLike | null {
    // stagePoint:3230-3238 —— 挂在元素上时用 currentTarget，window 兜底 stage
    if (ev.currentTarget && typeof ev.currentTarget.getBoundingClientRect === 'function') {
      return ev.currentTarget.getBoundingClientRect()
    }
    return this.env.stageRect()
  }
  stagePoint(ev: PointerLike): { x: number; y: number } {
    const r = this.stageRectFor(ev)
    if (!r) return { x: 0, y: 0 }
    return stagePointAt(r, ev.clientX, ev.clientY)
  }
  private stagePointAtXY(clientX: number, clientY: number) {
    const r = this.env.stageRect()
    return r ? stagePointAt(r, clientX, clientY) : null
  }
  beginDrawAt(clientX: number, clientY: number, pointerType = 'mouse'): void {   // 3249
    const p = this.stagePointAtXY(clientX, clientY)
    if (!p) return
    Object.assign(this.draw, { active: true, x0: p.x, y0: p.y, x: p.x, y: p.y, w: 0, h: 0, pointerType })
  }
  updateDrawAt(clientX: number, clientY: number): void {                          // 3255
    if (!this.draw.active) return
    const p = this.stagePointAtXY(clientX, clientY)
    if (!p) return
    this.draw.x = Math.min(p.x, this.draw.x0)
    this.draw.y = Math.min(p.y, this.draw.y0)
    this.draw.w = Math.abs(p.x - this.draw.x0)
    this.draw.h = Math.abs(p.y - this.draw.y0)
  }
  /** attachDrawTracking:3271-3281 —— move/up 挂 window，拖出画布也继续（Pointer Events 同套回调） */
  private attachDrawTracking(): void {
    this.env.win.addEventListener('pointermove', this.moveDraw)
    this.env.win.addEventListener('pointerup', this.endDraw)
    this.env.win.addEventListener('pointercancel', this.endDraw)
    this.env.win.addEventListener('blur', this.endDraw)
  }
  private detachDrawTracking(): void {
    this.env.win.removeEventListener('pointermove', this.moveDraw)
    this.env.win.removeEventListener('pointerup', this.endDraw)
    this.env.win.removeEventListener('pointercancel', this.endDraw)
    this.env.win.removeEventListener('blur', this.endDraw)
  }
  /** startDraw:3283-3326 —— 画布 pointerdown 入口（image stage / video stage） */
  startDraw = (ev: PointerLike): void => {
    if (ev.isPrimary === false) return                       // 多指只认第一根
    if (ev.pointerType === 'mouse' && ev.button !== 0) return
    if (this.mode !== 'video') this.clearActiveShape()       // 3287
    if (this.mode === 'video') {
      if (!this.vrectCtl || !this.drawMode) return           // 3288-3289
    } else if (this.mode === 'image') {
      if (!this.ctl.rectCtl && !this.ctl.polygonCtl && !this.ctl.keypointCtl
          && !this.ctl.ellipseCtl && !this.ctl.brushCtl) return   // 3290-3291（6-4 补 brushCtl）
      const p = this.stagePoint(ev)
      if (this.imageTool === 'brush' && this.ctl.brushCtl) {      // 3294-3301（6-4）
        ev.preventDefault()
        this.brushDrawing = true
        this.attachDrawTracking()                                 // 3296：move/up 挂 window，拖出画布继续涂
        const path: BrushStroke = { size: this.brushSize, points: [[p.x, p.y]] }
        ;(this.s.brushDraft as BrushStroke[]).push(path)
        this.brushLayer.dot([p.x, p.y], path.size)                 // 3299 brushDot
        // Vue 靠响应式点亮「完成笔刷」按钮与 dirty；React 侧等价物=这一次 emit
        this.emit(); return
      }
      if (this.imageTool === 'point' && this.ctl.keypointCtl) {
        ev.preventDefault()
        this.s.keypoints.push({
          id: newShapeId('kp'),
          label: this.boxLabel || ((this.activeOptions()[0] || {}).value || ''),
          x: Number(p.x.toFixed(2)), y: Number(p.y.toFixed(2)),
        })
        this.active.keypoint = this.s.keypoints.length - 1
        this.emit(); return
      }
      if (this.imageTool === 'polygon' && this.ctl.polygonCtl) {
        ev.preventDefault()
        const pt = [Number(p.x.toFixed(2)), Number(p.y.toFixed(2))]
        this.polyDraft.push(pt)   // getter 即 host.s.polyDraft，就地追加
        this.emit(); return
      }
      if (this.imageTool === 'ellipse' && !this.ctl.ellipseCtl) return
      if (this.imageTool === 'box' && !this.ctl.rectCtl) return
    } else {
      return                                                  // 3320（其它模式不画）
    }
    ev.preventDefault()
    this.attachDrawTracking()
    this.beginDrawAt(ev.clientX, ev.clientY, ev.pointerType || 'mouse')
    this.emit()
  }
  moveDraw = (ev: PointerLike): void => {                     // 3327-3335（6-4 补 brush 分支，先于框拖判定）
    if (this.brushDrawing) {
      const p = this.stagePoint(ev)
      const draft = this.s.brushDraft as BrushStroke[]
      const path = draft[draft.length - 1]
      if (!path) return
      const prev = path.points[path.points.length - 1]
      path.points.push([p.x, p.y])                            // 3333：原值入列，不 toFixed（照抄）
      this.brushLayer.line(prev, [p.x, p.y], path.size)       // 3334
      return                                                  // 3335：无 emit——按钮态在起笔已点亮
    }
    if (!this.draw.active) return
    this.updateDrawAt(ev.clientX, ev.clientY)
    this.emit()
  }
  endDraw = (): void => {                                     // 3555-3595
    this.detachDrawTracking()
    if (this.brushDrawing) { this.brushDrawing = false; return }  // 3557：一笔抬起只收尾，不成框
    if (!this.draw.active) return
    this.draw.active = false
    if (this.draw.w < 1 || this.draw.h < 1) { this.emit(); return }
    // 3563-3566：触屏轻点抖动 <12px 当误触丢掉；鼠标保持 1% 判定
    if (this.draw.pointerType === 'touch') {
      const r = this.env.stageRect()
      if (r && (this.draw.w * r.width / 100 < 12 || this.draw.h * r.height / 100 < 12)) { this.emit(); return }
    }
    const label = this.boxLabel || ((this.activeOptions()[0] || {}).value || '')   // 3568
    const rect = {
      x: Number(this.draw.x.toFixed(2)), y: Number(this.draw.y.toFixed(2)),
      w: Number(this.draw.w.toFixed(2)), h: Number(this.draw.h.toFixed(2)),
    }
    if (this.mode === 'image' && this.imageTool === 'ellipse' && this.ctl.ellipseCtl) {   // 3573
      this.s.ellipses.push({ id: newShapeId('ell'), label, ...rect })
      this.active.ellipse = this.s.ellipses.length - 1
      this.emit(); return
    }
    if (this.mode === 'video' && this.vrectCtl) {                                          // 3578
      if (this.active.box != null && this.s.boxes[this.active.box]) {
        upsertKeyframe(this.s.boxes[this.active.box], this.currentFrame(), rect, this.fps())
      } else {
        const kf = { frame: this.currentFrame(), time: Number(this.currentTime.toFixed(3)), ...rect }
        this.s.boxes.push({ id: newShapeId('box'), label, ...rect, frame: this.currentFrame(),
                            sequence: [kf], original_width: 0, original_height: 0 })
        this.active.box = this.s.boxes.length - 1
      }
      this.emit(); return
    }
    const nat = { w: this.s.imgNatural.w, h: this.s.imgNatural.h }                          // naturalSize:2432
    this.s.boxes.push({ id: newShapeId('box'), label, ...rect, frame: null,
                        original_width: nat.w, original_height: nat.h })
    // 3593：画完不自动选中，重叠框才能继续画
    this.emit()
  }

  /** finishPolygon:2720-2729 */
  finishPolygon(): boolean {
    if (!this.ctl.polygonCtl) return false
    if (this.polyDraft.length < 3) {
      this.host.toast?.('bad', '多边形至少需要 3 个点')
      return false
    }
    this.s.polygons.push({
      id: newShapeId('poly'),
      label: this.boxLabel || ((this.ctl.polygonCtl.options || [{}])[0].value || ''),
      points: this.polyDraft.slice(),
    })
    this.active.polygon = this.s.polygons.length - 1
    this.s.polyDraft = []
    this.emit(); return true
  }

  // ---------- 画笔（3600-3720，6-4；逻辑实体在 brush.ts，此处为接线面） ----------
  /** finishBrush:3680 —— 工具条「完成笔刷」入口；toast 文案照抄 3681/3683 */
  finishBrush(): boolean {
    if (!this.ctl.brushCtl) return false
    const nat = { w: this.s.imgNatural.w, h: this.s.imgNatural.h }   // naturalSize:2432
    const label = this.boxLabel || ((this.ctl.brushCtl.options || [{}])[0].value || '')  // 3715
    const done = this.brushLayer.finish(nat, label)
    if (done) this.emit()             // Vue 响应式自动跟；React 侧收笔/删除必须显式 bump
    return done
  }
  /** 面板笔刷行删除（Vue:850 splice+redrawBrush）—— 6-2 行 UI 经此入口接 */
  deleteBrushRegion(i: number): void {
    this.brushLayer.removeRegion(i)
    this.emit()
  }
  setBrushSize(v: number): void {
    this.brushSize = Number(v)
    this.emit()
  }
  /** 区域态被外部改写（undo/applyResult=6-5 接缝）后的整层重铺；组件按 version 变化也会调 */
  brushRedraw(): void { this.brushLayer.redraw() }

  /** setTool:2640-2646 */
  setTool(key: string): void {
    this.imageTool = key
    this.s.polyDraft = []
    this.clearActiveShape()
    const opts = this.activeOptions()
    this.boxLabel = opts.length ? (opts[0].value || '') : ''
    this.emit()
  }

  // ---------- 选中/删除（3343-3389） ----------
  clearActiveShape(): void {                                  // 3347
    this.active.box = null
    this.active.ellipse = null
    this.active.keypoint = null
    this.active.polygon = null
  }
  selectShape(kind: ShapeKind, i: number, toggle = true): void {   // 3353
    const cur = kind === 'box' ? this.active.box
      : kind === 'ellipse' ? this.active.ellipse
        : kind === 'keypoint' ? this.active.keypoint : this.active.polygon
    if (toggle && cur === i) { this.clearActiveShape(); this.emit(); return }
    if (this.mode === 'image') {
      const tool = ({ box: 'box', ellipse: 'ellipse', keypoint: 'point', polygon: 'polygon' } as
        Record<string, string>)[kind]
      if (tool && this.imageTool !== tool && this.imageTools().some((t) => t.key === tool)) {
        this.setTool(tool)
      }
    }
    this.clearActiveShape()
    if (kind === 'box') this.active.box = i
    else if (kind === 'ellipse') this.active.ellipse = i
    else if (kind === 'keypoint') this.active.keypoint = i
    else this.active.polygon = i
    this.emit()
  }
  removeShape(kind: ShapeKind, i: number): void {             // 3371
    if (kind === 'box') { this.s.boxes.splice(i, 1); this.active.box = null }
    else if (kind === 'ellipse') { this.s.ellipses.splice(i, 1); this.active.ellipse = null }
    else if (kind === 'keypoint') { this.s.keypoints.splice(i, 1); this.active.keypoint = null }
    else if (kind === 'polygon') { this.s.polygons.splice(i, 1); this.active.polygon = null }
    this.emit()
  }
  deleteActiveShape(): boolean {                              // 3377
    if (this.active.box != null) { this.removeShape('box', this.active.box); return true }
    if (this.active.ellipse != null) { this.removeShape('ellipse', this.active.ellipse); return true }
    if (this.active.keypoint != null) { this.removeShape('keypoint', this.active.keypoint); return true }
    if (this.active.polygon != null) { this.removeShape('polygon', this.active.polygon); return true }
    return false
  }
  shapeBox(kind: ShapeKind, i: number) {                      // 3384
    if (kind === 'box') return this.boxRect(this.s.boxes[i]) as Region
    if (kind === 'ellipse') return this.s.ellipses[i]
    if (kind === 'keypoint') return this.s.keypoints[i]
    return null
  }

  // ---------- 框上的按下=选中或起新框（3390-3437） ----------
  onBoxDown = (ev: PointerLike, i: number): void => {         // 3397
    if (ev.isPrimary === false) return
    if (ev.pointerType === 'mouse' && ev.button !== 0) return
    if (this.mode !== 'image' || this.imageTool !== 'box' || !this.ctl.rectCtl
        || this.active.box === i) {                           // 3401 视频/非画框工具：按下即拖
      this.beginShapeDrag(ev, 'box', i)
      return
    }
    ev.preventDefault()
    ev.stopPropagation()
    Object.assign(this.boxPress, { active: true, i, sx: ev.clientX, sy: ev.clientY,
      slop: ev.pointerType === 'touch' ? this.BOX_PRESS_SLOP_TOUCH : this.BOX_PRESS_SLOP })
    this.env.win.addEventListener('pointermove', this.onBoxPressMove)
    this.env.win.addEventListener('pointerup', this.onBoxPressUp)
    this.env.win.addEventListener('pointercancel', this.onBoxPressUp)
  }
  endBoxPress(): void {                                       // 3415
    this.boxPress.active = false
    this.env.win.removeEventListener('pointermove', this.onBoxPressMove)
    this.env.win.removeEventListener('pointerup', this.onBoxPressUp)
    this.env.win.removeEventListener('pointercancel', this.onBoxPressUp)
  }
  onBoxPressMove = (ev: PointerLike): void => {               // 3421
    if (!this.boxPress.active) return
    if (Math.abs(ev.clientX - this.boxPress.sx) < this.boxPress.slop
        && Math.abs(ev.clientY - this.boxPress.sy) < this.boxPress.slop) return
    const { sx, sy } = this.boxPress
    this.endBoxPress()
    this.clearActiveShape()
    this.attachDrawTracking()
    this.beginDrawAt(sx, sy, ev.pointerType || 'mouse')
    this.updateDrawAt(ev.clientX, ev.clientY)
    this.emit()
  }
  onBoxPressUp = (): void => {                                // 3433
    if (!this.boxPress.active) return
    const i = this.boxPress.i
    this.endBoxPress()
    this.selectShape('box', i, false)
  }

  // ---------- 图形拖动/缩放（3439-3526） ----------
  beginShapeDrag(ev: PointerLike, kind: ShapeKind, i: number,
                 dragMode = 'move', handle = '', vertex = -1): void {   // 3439
    if (ev.isPrimary === false) return
    if (ev.pointerType === 'mouse' && ev.button !== 0) return
    if (kind === 'box' && this.mode === 'video' && this.drawMode) return  // 3442
    const stage = this.env.stageRect()
    if (!stage) return
    ev.preventDefault()
    ev.stopPropagation()
    // 3447 beginDragHistory()：拖拽=一次撤销事务（REQ-UNIFY-6-5 已接：拖动中的 emit 全静音，
    // 落点有位移才入栈一步）
    this.host.beginDragHistory?.()
    this.selectShape(kind, i, false)
    const p = stagePointAt(stage, ev.clientX, ev.clientY)
    const b = this.shapeBox(kind, i)
    Object.assign(this.shapeDrag, {
      active: true, kind, index: i, mode: dragMode, handle, vertex,
      sx: p.x, sy: p.y, ox: b ? num(b.x) : 0, oy: b ? num(b.y) : 0,
      ow: b ? num(b.w) : 0, oh: b ? num(b.h) : 0,
      points: kind === 'polygon' ? (this.s.polygons[i].points || []).map((q) => [...q]) : [],
      left: stage.left, top: stage.top,
      width: Math.max(1, stage.width), height: Math.max(1, stage.height),
    })
    this.env.win.addEventListener('pointermove', this.onShapeDrag)
    this.env.win.addEventListener('pointerup', this.endShapeDrag)
    this.env.win.addEventListener('pointercancel', this.endShapeDrag)
  }
  onShapeDrag = (ev: { clientX: number; clientY: number; preventDefault?(): void }): void => {  // 3466
    if (!this.shapeDrag.active) return
    if (ev.preventDefault) ev.preventDefault()
    const x = clampPct((ev.clientX - this.shapeDrag.left) / this.shapeDrag.width * 100)
    const y = clampPct((ev.clientY - this.shapeDrag.top) / this.shapeDrag.height * 100)
    if (this.shapeDrag.kind === 'keypoint') {                 // 3471
      const k = this.s.keypoints[this.shapeDrag.index]
      if (k) { k.x = round2(x); k.y = round2(y) }
      this.emit(); return
    }
    if (this.shapeDrag.kind === 'polygon') {                  // 3476
      const poly = this.s.polygons[this.shapeDrag.index]
      if (!poly || !poly.points) return
      if (this.shapeDrag.mode === 'vertex' && this.shapeDrag.vertex >= 0) {
        const q = poly.points[this.shapeDrag.vertex]
        if (q) { q[0] = round2(x); q[1] = round2(y) }
      } else {
        let dx = x - this.shapeDrag.sx, dy = y - this.shapeDrag.sy
        const xs = this.shapeDrag.points.map((q) => q[0]), ys = this.shapeDrag.points.map((q) => q[1])
        dx = Math.max(-Math.min(...xs), Math.min(100 - Math.max(...xs), dx))
        dy = Math.max(-Math.min(...ys), Math.min(100 - Math.max(...ys), dy))
        poly.points = this.shapeDrag.points.map((q) => [round2(q[0] + dx), round2(q[1] + dy)])
      }
      this.emit(); return
    }
    const dx = x - this.shapeDrag.sx, dy = y - this.shapeDrag.sy   // 3491 move/resize
    let nx = this.shapeDrag.ox, ny = this.shapeDrag.oy
    let nw = this.shapeDrag.ow, nh = this.shapeDrag.oh
    if (this.shapeDrag.mode === 'move') {
      nx = clampPct(this.shapeDrag.ox + dx); ny = clampPct(this.shapeDrag.oy + dy)
      nx = Math.min(nx, 100 - nw); ny = Math.min(ny, 100 - nh)
    } else {
      const h = this.shapeDrag.handle
      if (h.includes('w')) {
        nx = Math.max(0, Math.min(this.shapeDrag.ox + dx, this.shapeDrag.ox + this.shapeDrag.ow - 1))
        nw = this.shapeDrag.ox + this.shapeDrag.ow - nx
      }
      if (h.includes('e')) nw = Math.max(1, Math.min(this.shapeDrag.ow + dx, 100 - this.shapeDrag.ox))
      if (h.includes('n')) {
        ny = Math.max(0, Math.min(this.shapeDrag.oy + dy, this.shapeDrag.oy + this.shapeDrag.oh - 1))
        nh = this.shapeDrag.oy + this.shapeDrag.oh - ny
      }
      if (h.includes('s')) nh = Math.max(1, Math.min(this.shapeDrag.oh + dy, 100 - this.shapeDrag.oy))
    }
    const rect = { x: round2(nx), y: round2(ny), w: round2(nw), h: round2(nh) }
    if (this.shapeDrag.kind === 'box') {                      // 3510
      const b = this.s.boxes[this.shapeDrag.index]
      if (!b) return
      if (this.mode === 'video') upsertKeyframe(b, this.currentFrame(), rect, this.fps())
      else Object.assign(b, rect)
    } else if (this.shapeDrag.kind === 'ellipse') {           // 3515
      const e = this.s.ellipses[this.shapeDrag.index]
      if (e) Object.assign(e, rect)
    }
    this.emit()
  }
  endShapeDrag = (): void => {                                // 3520
    this.shapeDrag.active = false
    this.env.win.removeEventListener('pointermove', this.onShapeDrag)
    this.env.win.removeEventListener('pointerup', this.endShapeDrag)
    this.env.win.removeEventListener('pointercancel', this.endShapeDrag)
    // 3525 endDragHistory()：与 3447 配对（REQ-UNIFY-6-5）——先落一步再 emit 重绘
    this.host.endDragHistory?.()
    this.emit()
  }

  /** stampKeyframe:3549-3554 —— 面板「标记当前帧」入口（6-2 接线，逻辑归本引擎） */
  stampKeyframe(i: number): void {
    const b = this.s.boxes[i]
    if (!b) return
    const r = this.boxRect(b)
    upsertKeyframe(b, this.currentFrame(), { x: num(r.x), y: num(r.y), w: num(r.w), h: num(r.h) }, this.fps())
    this.emit()
  }

  // ---------- 视频逐帧（3175-3202） ----------
  goFrame(frame: number): void {                              // 3175
    if (this.mode !== 'video') return
    const el = this.env.media()
    if (!el) return
    const nf = clampFrame(frame, this.duration, this.fps())
    el.pause()
    el.currentTime = nf / this.fps()
    this.currentTime = el.currentTime
    this.emit()
  }
  stepFrame(delta: number): void { this.goFrame(this.currentFrame() + delta) }   // 3184
  /** activeKeyframes:3185 —— 选中框的关键帧列表 */
  activeKeyframes(): number[] {
    if (this.mode !== 'video' || this.active.box == null) return []
    return keyframesOf(this.s.boxes[this.active.box] || null)
  }
  /** onKeyframe:3193 */
  onKeyframe(): boolean { return this.activeKeyframes().includes(this.currentFrame()) }
  jumpKeyframe(dir: number): void {                           // 3194
    const frames = this.activeKeyframes()
    if (!frames.length) return
    const target = jumpTarget(frames, this.currentFrame(), dir)
    if (target != null) this.goFrame(target)
  }
  /** watch(playbackRate):2003-2005 */
  setPlaybackRate(r: number): void {
    this.playbackRate = Number(r) || 1
    const el = this.env.media()
    if (el) el.playbackRate = Number(r) || 1
    this.emit()
  }

  // ---------- 区间时钟（3206-3229，UI 在 6-2，逻辑在此） ----------
  segFromNow(): { start: number; end: number } {
    return segFromNow(this.segUnit(), this.currentFrame(), this.currentTime, this.fps())
  }
  segUnit(): 'frame' | 's' { return this.ctl.timelineCtl ? 'frame' : 's' }       // 3215→1415
  /** addSegment:3211-3217 —— 返回错误文案（''=成功）；区间行 UI 在 6-2，经此入口写回 */
  addSegmentAt(a: number, b: number): string {
    const err = segAddError(this.segLabel, a, b)
    if (err) return err
    this.s.segments.push({ id: newSegmentId(), label: this.segLabel,
                           start: Number(a) || 0, end: Number(b) || 0, text: '' })
    this.emit(); return ''
  }
  /** fixSegment:3219-3229 —— 面板改起止后的就地兜底 */
  fixSegment(s: Region): void {
    fixSegmentIn(s, this.segUnit())
    this.emit()
  }

  // ---------- 缩放/全屏（3063-3166） ----------
  fitImage(reset = false): void {                             // 3063-3079
    if (this.mode !== 'image') return
    if (reset) this.imageZoom = 100
    if (!reset && this.imageZoom !== 100) return              // 3068 已缩放时冻结基准
    const box = this.env.scroll()
    const nat = { w: this.s.imgNatural.w, h: this.s.imgNatural.h }
    if (!box || !nat.w || !nat.h) return
    const availW = Math.max(160, box.clientWidth - 4)
    const availH = Math.max(160, box.clientHeight - 4)
    const scale = Math.min(1, availW / nat.w, availH / nat.h)
    this.imgFit.w = Math.max(40, Math.round(nat.w * scale))
    this.imgFit.h = Math.max(30, Math.round(nat.h * scale))
    this.emit()
  }
  zoomTo(z: number): void {                                   // 3080-3093
    if (this.mode !== 'image') return
    const next = Math.max(25, Math.min(400, Math.round(Number(z) || 100)))
    if (next === this.imageZoom) return
    this.imageZoom = next
    this.tick(() => {
      const box = this.env.scroll()
      if (box) {
        box.scrollLeft = Math.max(0, (box.scrollWidth - box.clientWidth) / 2)
        box.scrollTop = Math.max(0, (box.scrollHeight - box.clientHeight) / 2)
      }
      this.emit()
    })
  }
  zoomBy(delta: number): void { this.zoomTo(this.imageZoom + delta) }            // 3094
  resetZoom(): void { this.zoomTo(100); this.tick(() => this.fitImage(true)) }   // 3095
  fitWidth(): void {                                          // 3097-3103
    const box = this.env.scroll()
    if (!box || !this.imgFit.w) return
    let z = Math.round((box.clientWidth - 2) / this.imgFit.w * 100)
    if (z > 200) z = 100                                      // 竖图按宽度放大离谱 → 保持整图
    this.zoomTo(Math.min(400, Math.max(25, z)))
  }
  onImageWheel(e: { ctrlKey?: boolean; metaKey?: boolean; deltaY: number; preventDefault(): void }): void {
    if (!(e.ctrlKey || e.metaKey) || this.mode !== 'image') return              // 3105
    e.preventDefault()
    this.zoomTo(this.imageZoom + (e.deltaY < 0 ? 10 : -10))
  }
  toggleFocus(): void {                                       // 3109-3115
    if (this.env.win.innerWidth <= 700) return
    this.focusMode = !this.focusMode
    this.tick(() => this.fitImage(true))
    this.emit()
  }
  afterSizeChange(): void {                                   // 3121-3129（redrawBrush：组件按 version effect 重铺）
    this.tick(() => { this.fitImage(true); this.emit() })
  }
  toggleMax(): void {                                         // 3130-3151
    if (!this.maximized) {
      this.maximized = true
      this.focusBeforeMax = this.focusMode
      this.focusMode = true                                   // 3134 最大化同时收起队列
      try { this.env.fullscreen.request() } catch (err) {
        // 3139 浏览器拒绝时退回 CSS 整屏，功能不受影响
        console.warn('[workbench] 全屏被拒绝，改用整窗模式', err)
      }
      this.afterSizeChange()
    } else {
      try { if (this.env.fullscreen.active()) this.env.fullscreen.exit() } catch (err) {
        console.warn('[workbench] 退出全屏失败', err)
      }
      this.exitMaxState()
    }
    this.emit()
  }
  exitMaxState(): void {                                      // 3152-3156
    this.maximized = false
    this.focusMode = this.focusBeforeMax
    this.afterSizeChange()
  }
  onFullscreenChange(): void {                                // 3157-3160
    if (!this.env.fullscreen.active() && this.maximized) this.exitMaxState()
  }
  onWindowResize(): void {                                    // 3161-3166（窄屏抽屉态归壳）
    if (this.env.win.innerWidth <= 700 && this.focusMode) this.focusMode = false
    this.fitImage()
  }

  // ---------- 时间序列（2756-2814） ----------
  tsDomain() { return tsDomainOf(this.tsPoints) }
  /** loadTimeseries:2756-2777 —— 内联直接用；/media、http、data: 先取文本 */
  async loadTimeseries(): Promise<void> {
    const f = (this.ctl.schema.timeseries_fields || [])[0]
    const task = this.host.selected
    if (!f || !task) return
    const seq = this.taskSeq
    let raw: unknown = (task.data || {})[f.field]
    if (raw == null) return
    try {
      if (/^(https?:|\/|data:)/.test(String(raw))) {
        raw = await this.env.fetchText(String(raw))
      }
      if (seq !== this.taskSeq || !this.host.selected) return // 慢响应丢弃（2771）
      this.tsPoints = parseSeries(raw)
      if (!this.tsPoints.length) this.tsError = '时间序列数据解析失败（支持内联 CSV 或 URL）'
      else this.tsError = ''
    } catch (e) {
      this.tsError = '读取时间序列失败：' + (e as Error).message
    }
    this.emit()
  }
  tsRatio(ev: { clientX: number; currentTarget?: { getBoundingClientRect(): RectLike } | null }): number {
    const r = (ev.currentTarget && ev.currentTarget.getBoundingClientRect()) || this.env.stageRect()
    if (!r) return 0
    return tsRatio(r, ev.clientX)                              // 2778
  }
  tsValue(ratio: number): number {                             // 2782
    const d = this.tsDomain()
    return d.x0 + ratio * (d.x1 - d.x0)
  }
  tsDown(ev: PointerLike): void {                              // 2786
    if (!this.ctl.timeseriesCtl) return
    if (ev.isPrimary === false) return
    if (ev.pointerType === 'mouse' && ev.button !== 0) return
    try { if (ev.currentTarget && ev.currentTarget.setPointerCapture
      && ev.pointerId != null) ev.currentTarget.setPointerCapture(ev.pointerId) } catch { /* 旧浏览器忽略 */ }
    this.tsDrag.active = true
    this.tsDrag.x0 = this.tsRatio(ev)
    this.tsDrag.x1 = this.tsDrag.x0
    this.emit()
  }
  tsMove(ev: PointerLike): void {                              // 2796
    if (!this.tsDrag.active) return
    this.tsDrag.x1 = this.tsRatio(ev)
    this.emit()
  }
  tsUp(ev: PointerLike): void {                                // 2800-2809
    if (!this.tsDrag.active) return
    try { if (ev.currentTarget && ev.currentTarget.releasePointerCapture
      && ev.pointerId != null) ev.currentTarget.releasePointerCapture(ev.pointerId) } catch { /* 已释放 */ }
    this.tsDrag.active = false
    const a = this.tsValue(Math.min(this.tsDrag.x0, this.tsDrag.x1))
    const b = this.tsValue(Math.max(this.tsDrag.x0, this.tsDrag.x1))
    if (Math.abs(b - a) <= 0) { this.emit(); return }
    this.s.tsSegments.push({ id: newShapeId('ts'), label: this.tsLabel,
                             start: Number(a.toFixed(3)), end: Number(b.toFixed(3)) })
    this.emit()
  }

  // ---------- 媒体元素事件（3035-3056） ----------
  onTime(t: { currentTime: number; duration?: number }): void {
    this.currentTime = Number(t.currentTime) || 0
    if (Number.isFinite(t.duration as number) && (t.duration as number) > 0) {
      this.duration = t.duration as number
      this.s.duration = this.duration
    }
    this.emit()
  }
  onMeta(t: { duration: number; playbackRate: number }): void {   // 3039
    if (Number.isFinite(t.duration)) {
      this.duration = t.duration
      this.s.duration = t.duration
    }
    t.playbackRate = this.playbackRate
    this.emit()
  }
  onImgLoad(w: number, h: number): void {                        // 3043-3048（redrawBrush：组件 version effect 重铺）
    this.imgLoading = false
    this.mediaMissing = false
    this.s.imgNatural = { w: w || 0, h: h || 0 }
    this.s.frameRate = this.fps()
    this.tick(() => this.fitImage())
    this.emit()
  }
  onMediaError(): void {                                         // 3051
    if (this.host.selected) this.mediaMissing = true
    this.imgLoading = false                                      // 3055 图片失败别把加载态盖死
    this.emit()
  }
  setSrc(src: string): void {
    this.displaySrc = src
    if (this.mode === 'image') this.imgLoading = src !== ''
    this.emit()
  }

  // ---------- 任务切换（组件在 selected.id 变化时调用） ----------
  resetForTask(): void {                                         // resetAnnotation 画布侧伴生
    this.taskSeq++
    this.draw.active = false
    this.detachDrawTracking()
    this.boxPress.active = false
    this.shapeDrag.active = false
    this.brushDrawing = false                                    // 2286（brushDraft/Regions 由 model resetAnnotation 清）
    this.tsDrag = { active: false, x0: 0, x1: 0 }
    this.s.polyDraft = []
    this.tsPoints = []
    this.tsError = ''
    this.currentTime = 0
    this.duration = this.s.duration || 0
    this.displaySrc = ''
    this.mediaMissing = false
    this.clearActiveShape()
    this.s.frameRate = this.fps()
    const opts = this.shapeOptions()                             // 2334：不在选项里就回退首个
    this.boxLabel = opts.some((o) => o.value === this.boxLabel)
      ? this.boxLabel : (opts.length ? (opts[0].value || '') : '')
    const tsOpts = (this.ctl.timeseriesCtl && this.ctl.timeseriesCtl.options) || []
    this.tsLabel = tsOpts.length ? (tsOpts[0].value || '') : ''  // 2290
    if (this.mode === 'timeseries') void this.loadTimeseries()
    this.emit()
  }

  // ---------- 快捷键执行（onKey:4655-4714 画布段） ----------
  keyCtx(extra: { typing?: boolean; blocked?: boolean } = {}) {
    return {
      mode: this.mode,
      typing: !!extra.typing,
      blocked: !!extra.blocked,
      maximized: this.maximized,
      fullscreenActive: this.env.fullscreen.active(),
      hasActiveShape: this.active.box != null || this.active.ellipse != null
        || this.active.keypoint != null || this.active.polygon != null,
      focusMode: this.focusMode,
    }
  }
  /** 返回被执行的动作（null=不消费，落回壳层）；调用方对非 null 结果做 preventDefault/stop */
  handleKey(evt: CanvasKeyEvt, extra: { typing?: boolean; blocked?: boolean } = {}): CanvasKeyAction | null {
    const action = canvasKeyAction(evt, this.keyCtx(extra))
    if (!action) return null
    switch (action) {
      case 'escapeMax': this.toggleMax(); return action
      case 'toggleMax': this.toggleMax(); return action
      case 'toggleFocus': this.toggleFocus(); return action
      case 'zoomIn': this.zoomBy(25); return action
      case 'zoomOut': this.zoomBy(-25); return action
      case 'resetZoom': this.resetZoom(); return action
      case 'fitWidth': this.fitWidth(); return action
      case 'frameBack': case 'frameFwd':
        this.stepFrame(frameDelta(evt, action)); return action
      case 'deleteShape': return this.deleteActiveShape() ? action : null   // 4707 只在真删了才拦
      case 'escapeShape': this.clearActiveShape(); this.emit(); return action
      case 'escapeFocus': this.toggleFocus(); return action
      default: return null
    }
  }

  /** 时间序列/区间列表的行删除（模板 468：tsSegments.splice） */
  removeTsSegment(i: number): void {
    this.s.tsSegments.splice(i, 1)
    this.emit()
  }
}
