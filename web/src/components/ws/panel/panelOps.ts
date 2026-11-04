/**
 * panelOps.ts —— 右侧标注面板的纯操作层（REQ-UNIFY-6-2）。
 *
 * 对照 mos/platform/frontend/src/views/LabelStudioWorkbench.vue（只读，行号见各函数注）；
 * JSON 形状一律经 wsSchema 冻结面（buildResult/applyResult），本文件只负责
 * 「用户动作 → WsState/表单态」的迁移与校验文案（校验失败=显式返回 msg，不静默）。
 * 组件层与 unify6-panel-test.py 金样回放共用本模块——金样断言的就是这些迁移。
 */
import {
  Region, SchemaCtl, WsControls, WsState, choicePicked, newSegmentId, newShapeId,
} from '../../../pages/mos/wsSchema'

// ---------- 表单态（Vue 顶层 ref：segLabel:1568? segStart/segEnd/boxLabel/relFrom/relTo） ----------

/** 面板表单态；boxLabel/segLabel 等归画布共享（wsPanelUiStore），此处只定义形状。 */
export type PanelForm = {
  segLabel: string
  segStart: number | string
  segEnd: number | string
  boxLabel: string
  relFrom: string
  relTo: string
}

export function emptyForm(): PanelForm {
  return { segLabel: '', segStart: 0, segEnd: 0, boxLabel: '', relFrom: '', relTo: '' }
}

/** 操作结果：ok=false 时组件必须 toast msg（不许静默，acceptance-rules 4）。 */
export type OpResult = { ok: true } | { ok: false; msg: string }

// ---------- extra 类控件（rating/number/datetime/taxonomy/pairwise，模板 591-628） ----------

/** rating 按钮（:593-595）：点第 n 星即 extra=n；重复点不取消（与 Vue 一致，无 toggle）。 */
export function ratingValue(n: number): number {
  return n
}

/** number 输入（:599-601 v-model.number）：'' 保留为空串；非法数字回退原串（Vue 同语义）。 */
export function numberInputToExtra(raw: string | number): number | string {
  if (raw === '') return ''
  const num = typeof raw === 'number' ? raw : Number(raw)
  return Number.isFinite(num) ? num : raw
}

/** pairwise（模板 :620-628；卡面/评审称 ranker，LS 原件控件名 pairwise）。 */
export type PairwiseSide = 'left' | 'right' | 'none'
export const PAIRWISE_SIDES: PairwiseSide[] = ['left', 'right', 'none']

/** taxValueKey:2832 —— select 的受控 value（路径 JSON 串；非数组一律 '[]'）。 */
export function taxValueKey(c: SchemaCtl, extra: Record<string, unknown>): string {
  const v = extra[c.from_name]
  return JSON.stringify(Array.isArray(v) ? v : [])
}

/** onTaxChange:2827 —— select change 的值解析（''→[]）。 */
export function parseTaxPath(valueJson: string): unknown[] {
  return JSON.parse(valueJson || '[]')
}

export type TaxOption = { path: unknown[]; label: string }

/** flattenTaxonomy:2631 —— 树展平到叶子；label='a / b / c'（taxOptionsOf:1947 同式）。 */
export function flattenTaxonomy(tree: unknown[], base: unknown[] = []): TaxOption[] {
  const out: TaxOption[] = []
  for (const node of tree || []) {
    const n = node as { value?: unknown; children?: unknown[] }
    const path = base.concat(n.value)
    if (n.children && n.children.length) out.push(...flattenTaxonomy(n.children, path))
    else out.push({ path, label: path.join(' / ') })
  }
  return out
}

/** taxOptionsOf:1947 —— 控件未声明 tree（后端按 label_config 透传），取 unknown 槽。 */
export function taxOptionsOf(c: SchemaCtl | null | undefined): TaxOption[] {
  return flattenTaxonomy(((c || {}) as Record<string, any>).tree || [])
}

// ---------- 时间区间（模板 674-712；含 per_region 文本输入所需的校验） ----------

/** segUnit:1415 —— TimelineLabels 存在=按帧，否则按秒。 */
export function segUnitOf(ctl: WsControls): 's' | 'frame' {
  return ctl.timelineCtl ? 'frame' : 's'
}

/** clock:2250 —— 秒 → m:ss.s（区间行等宽时钟显示）。 */
export function clock(sec: number | string): string {
  const s = Math.max(0, Number(sec) || 0)
  const m = Math.floor(s / 60)
  const r = (s % 60).toFixed(1).padStart(4, '0')
  return m + ':' + r
}

/** segFromNow:3206 —— 「用当前时间/帧」的起止默认值（终点=起点+1s 或 1 秒帧数）。 */
export function segFromNow(segUnit: 's' | 'frame', currentTime: number,
                           currentFrame: number, frameRate: number): { start: number; end: number } {
  const now = segUnit === 'frame' ? currentFrame
    : Number(Number(currentTime || 0).toFixed(1))
  return { start: now, end: now + (segUnit === 'frame' ? Math.round(frameRate) : 1) }
}

/** addSegment:3211 —— 校验「先选标签/终点必须大于起点」，通过则入列。 */
export function addSegment(s: WsState, form: PanelForm): OpResult {
  const a = Number(form.segStart) || 0
  const b = Number(form.segEnd) || 0
  if (!form.segLabel) return { ok: false, msg: '先选一个标签' }
  if (b <= a) return { ok: false, msg: '终点必须大于起点' }
  s.segments.push({ id: newSegmentId(), label: form.segLabel, start: a, end: b, text: '' })
  return { ok: true }
}

/** fixSegment:3219 —— 行内改起止后的兜底：非负、终点>起点、按单位精度取整。 */
export function fixSegment(seg: Region, segUnit: 's' | 'frame'): void {
  const step = segUnit === 's' ? 0.1 : 1
  const digits = segUnit === 's' ? 1 : 0
  let a = Number(seg.start)
  let b = Number(seg.end)
  if (!Number.isFinite(a) || a < 0) a = 0
  if (!Number.isFinite(b) || b <= a) b = a + step
  seg.start = Number(a.toFixed(digits))
  seg.end = Number(b.toFixed(digits))
}

// ---------- 文本/文档 span（模板 631-671；capture:2648/2697，add:2663/2712） ----------

export type PendingSpan = { start: number; end: number; text: string }
export type PendingHyper = {
  start: string; end: string; startOffset: number; endOffset: number; text: string
}

/** captureSpan:2648 —— 从正文容器里算选区偏移（DOM 才有意义；node 环境返回 null）。 */
export function captureSpan(container: HTMLElement | null): PendingSpan | null {
  if (typeof window === 'undefined' || !container) return null
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount || sel.isCollapsed) return null
  const range = sel.getRangeAt(0)
  if (!container.contains(range.startContainer) || !container.contains(range.endContainer)) return null
  const pre = document.createRange()
  pre.selectNodeContents(container)
  pre.setEnd(range.startContainer, range.startOffset)
  const start = pre.toString().length
  const snippet = range.toString()
  return snippet.trim() ? { start, end: start + snippet.length, text: snippet } : null
}

/** addTextSpan:2663 —— 用 pending 建 span；无选区显式报错。 */
export function addTextSpan(s: WsState, pending: PendingSpan | null, label: string): OpResult {
  if (!pending) return { ok: false, msg: '先在正文里选中一段文字' }
  s.textSpans.push({ id: newShapeId('span'), label, start: pending.start,
                     end: pending.end, text: pending.text })
  return { ok: true }
}

/** xpathFor:2679 —— HyperText 选区端点定位（元素+文本节点两类兄弟计数）。 */
export function xpathFor(node: Node, root: Node): string {
  const parts: string[] = []
  let n: Node | null = node
  while (n && n !== root) {
    if (n.nodeType === 3) {
      let idx = 1
      let s = n.previousSibling
      while (s) { if (s.nodeType === 3) idx += 1; s = s.previousSibling }
      parts.unshift('text()[' + idx + ']')
    } else if (n.nodeType === 1) {
      let idx = 1
      let s = n.previousSibling
      while (s) {
        if (s.nodeType === 1 && (s as Element).nodeName === (n as Element).nodeName) idx += 1
        s = s.previousSibling
      }
      parts.unshift((n as Element).nodeName.toLowerCase() + '[' + idx + ']')
    }
    n = n.parentNode
  }
  return '/' + parts.join('/')
}

/** captureHyperSpan:2697 —— 文档容器选区 → pending（DOM）。 */
export function captureHyperSpan(container: HTMLElement | null): PendingHyper | null {
  if (typeof window === 'undefined' || !container) return null
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount || sel.isCollapsed) return null
  const range = sel.getRangeAt(0)
  if (!container.contains(range.startContainer) || !container.contains(range.endContainer)) return null
  const text = range.toString()
  if (!text.trim()) return null
  return {
    start: xpathFor(range.startContainer, container),
    end: xpathFor(range.endContainer, container),
    startOffset: range.startOffset,
    endOffset: range.endOffset,
    text,
  }
}

/** addHyperSpan:2712 */
export function addHyperSpan(s: WsState, pending: PendingHyper | null, label: string): OpResult {
  if (!pending) return { ok: false, msg: '先在文档里选中一段文字' }
  // hypertext 的 start/end 是 XPointer 字符串；契约 Region 类型声明 number，
  // applyResult:596-599 同口径用 as 收口（buildResult 原样透传，不参与算术）
  s.hyperSpans.push({ id: newShapeId('hspan'), label, ...pending } as unknown as Region)
  return { ok: true }
}

// ---------- 形状列表（模板 715-852；removeShape:3371；keyframe:3539/3549） ----------

export type ShapeKind = 'box' | 'polygon' | 'keypoint' | 'ellipse' | 'brush'
  | 'span' | 'hyper' | 'segment' | 'ts'

/** shapeLabelCtl:1824 —— 「① 图形」编辑钮对着哪个控件；视频框选类别在同 toName 的 Labels 上。 */
export function shapeLabelCtl(ctl: WsControls): SchemaCtl | null {
  const c = ctl.rectCtl || ctl.polygonCtl || ctl.keypointCtl || ctl.ellipseCtl || ctl.brushCtl
  if (c) return c
  const v = ctl.vrectCtl
  if (v && (v as Record<string, any>).label_from_name) {
    return { type: 'labels', from_name: String((v as Record<string, any>).label_from_name),
             options: (v as Record<string, any>).options || [] }
  }
  return null
}

/** activeToolCtl:1420（面板侧简化版：imageTool 由画布写 store，缺省 'box'）。 */
export function activeToolCtl(ctl: WsControls, mode: string, imageTool: string): SchemaCtl | null {
  if (mode === 'video') return ctl.vrectCtl
  if (imageTool === 'polygon') return ctl.polygonCtl
  if (imageTool === 'point') return ctl.keypointCtl
  if (imageTool === 'ellipse') return ctl.ellipseCtl
  if (imageTool === 'brush') return ctl.brushCtl
  return ctl.rectCtl
}

/** removeShape:3371 —— 行内删除（active 选区清理归调用方/画布缝）。 */
export function removeShape(s: WsState, kind: ShapeKind, i: number): void {
  const arr: Region[] | null =
    kind === 'box' ? s.boxes : kind === 'ellipse' ? s.ellipses
      : kind === 'keypoint' ? s.keypoints : kind === 'polygon' ? s.polygons
        : kind === 'brush' ? s.brushRegions : null
  if (arr && i >= 0 && i < arr.length) arr.splice(i, 1)
}

/** upsertKeyframe:3539 —— 同帧覆盖、按帧排序、time=f/fps 三位小数；框几何同步到当前帧。 */
export function upsertKeyframe(b: Region, frame: number,
                               rect: { x: number; y: number; w: number; h: number },
                               frameRate: number): void {
  if (!Array.isArray(b.sequence)) b.sequence = []
  const kf = { frame, x: rect.x, y: rect.y, w: rect.w, h: rect.h,
               time: Number((frame / frameRate).toFixed(3)) }
  const seq = b.sequence as Record<string, any>[]
  const at = seq.find((k) => k.frame === frame)
  if (at) Object.assign(at, kf)
  else seq.push(kf)
  seq.sort((a, c) => a.frame - c.frame)
  Object.assign(b, { x: kf.x, y: kf.y, w: kf.w, h: kf.h, frame })
}

/** stampKeyframe:3549 —— 「当前帧记一帧」按钮（面板行内可用；rect 取框现有百分比几何）。 */
export function stampKeyframe(b: Region | undefined, currentFrame: number, frameRate: number): OpResult {
  if (!b) return { ok: false, msg: '先选中一个框' }
  upsertKeyframe(b, currentFrame,
                 { x: Number(b.x) || 0, y: Number(b.y) || 0, w: Number(b.w) || 0, h: Number(b.h) || 0 },
                 frameRate)
  return { ok: true }
}

// ---------- 区域关系（模板 855-879；regions:1949 / regionName:2816 / addRelation:2820） ----------

export type RegionRef = { id: string; label: string; kind: string }

/** regions:1949 —— 参与关系的区域（框/区间/多边形/关键点/椭圆/文本 span；无 id 不入列）。 */
export function regionsOf(s: WsState): RegionRef[] {
  return [
    ...s.boxes.map((b) => ({ id: b.id, label: b.label, kind: '框' })),
    ...s.segments.map((x) => ({ id: x.id, label: x.label, kind: '区间' })),
    ...s.polygons.map((p) => ({ id: p.id, label: p.label, kind: '多边形' })),
    ...s.keypoints.map((p) => ({ id: p.id, label: p.label, kind: '关键点' })),
    ...s.ellipses.map((e) => ({ id: e.id, label: e.label, kind: '椭圆' })),
    ...s.textSpans.map((p) => ({ id: p.id, label: p.label, kind: '文本' })),
  ].filter((r) => r.id)
}

/** regionName:2816 —— 「框 · 人物」；查不到回退 id。 */
export function regionName(regions: RegionRef[], id: string): string {
  const r = regions.find((x) => x.id === id)
  return r ? (r.kind + ' · ' + r.label) : id
}

/** addRelation:2820 —— 空端点/自连显式报错；方向默认 right（buildResult:426-434 契约）。 */
export function addRelation(s: WsState, relFrom: string, relTo: string): OpResult {
  if (!relFrom || !relTo) return { ok: false, msg: '选择两个区域' }
  if (relFrom === relTo) return { ok: false, msg: '不能连接同一个区域' }
  s.relations.push({ id: newShapeId('rel'), from_id: relFrom, to_id: relTo, direction: 'right' })
  return { ok: true }
}

// ---------- 区域多类别（模板 749-753 等行内钮 + 弹窗 1295-1317；2386-2431） ----------

/** shapeExtraCount:2381 —— 「多类别 +n」角标计数（labels 除主类别外的附加数）。 */
export function shapeExtraCount(r: Region): number {
  return Array.isArray(r.labels) ? Math.max(0, r.labels.length - 1) : 0
}

/** regionArr:2386 —— kind → 状态数组引用（就地改）。 */
export function regionArr(s: WsState, kind: ShapeKind): Region[] | null {
  return { box: s.boxes, polygon: s.polygons, keypoint: s.keypoints, ellipse: s.ellipses,
           brush: s.brushRegions, span: s.textSpans, hyper: s.hyperSpans,
           ts: s.tsSegments, segment: s.segments }[kind] || null
}

/** regionOptions 映射（:2401-2413）—— 每种 kind 对应控件的 options。 */
export function regionCtlFor(kind: ShapeKind, ctl: WsControls): SchemaCtl | null {
  return kind === 'box' ? (ctl.rectCtl || ctl.vrectCtl)
    : kind === 'polygon' ? ctl.polygonCtl
      : kind === 'keypoint' ? ctl.keypointCtl
        : kind === 'ellipse' ? ctl.ellipseCtl
          : kind === 'brush' ? ctl.brushCtl
            : kind === 'span' ? ctl.textSpanCtl
              : kind === 'hyper' ? ctl.hypertextCtl
                : kind === 'ts' ? ctl.timeseriesCtl
                  : kind === 'segment' ? ctl.segCtl : null
}

/** toggleRegionLabel:2418 —— 勾上=追加；取消=过滤（最后一个不许取消，静默拒绝同 Vue）。 */
export function toggleRegionLabel(target: Region | null, val: string, on: boolean): boolean {
  if (!target) return false
  const cur = (Array.isArray(target.labels) && target.labels.length)
    ? target.labels.filter(Boolean) : (target.label ? [target.label] : [])
  let next: string[]
  if (on) {
    next = cur.includes(val) ? cur : cur.concat(val)
  } else {
    next = cur.filter((x) => x !== val)
    if (!next.length) return false
  }
  target.labels = next
  target.label = next[0]      // 勾掉主类别时，下一个自动顶上（:2430）
  return true
}

// ---------- 类别编辑器（模板 1264-1292；openLabelEditor:1835 / saveLabels:1906 / rename:1854） ----------

export type LabelRow = { uid: number; orig: string; value: string }
let labelRowUid = 0

/** openLabelEditor:1835-1844 —— 行来自控件现 options；uid 单调（key 用）。 */
export function labelEditorRows(ctl: SchemaCtl | null): LabelRow[] {
  return ((ctl && ctl.options) || []).map((o) => o.value).filter(Boolean)
    .map((v) => ({ uid: ++labelRowUid, orig: v, value: v }))
}

/** addLabelRow:1846 */
export function newLabelRow(): LabelRow {
  return { uid: ++labelRowUid, orig: '', value: '' }
}

/**
 * saveLabels:1907-1921 的行→请求体换算（不发请求，请求在 wsPanelApi）：
 * 清空一行=删除该类别（rename[orig]=''）；改名 rename[orig]=value；同名合并去重；
 * 全空报错「至少保留一个类别」。
 */
export function buildLabelSave(rows: LabelRow[]):
  { ok: true; labels: string[]; rename: Record<string, string> } | { ok: false; msg: string } {
  const labels: string[] = []
  const seen = new Set<string>()
  const rename: Record<string, string> = {}
  for (const row of rows) {
    const value = (row.value || '').trim()
    if (!value) {
      if (row.orig) rename[row.orig] = ''
      continue
    }
    if (seen.has(value)) continue
    seen.add(value)
    labels.push(value)
    if (row.orig && row.orig !== value) rename[row.orig] = value
  }
  if (!labels.length) return { ok: false, msg: '至少保留一个类别' }
  return { ok: true, labels, rename }
}

/**
 * applyLabelRename:1854-1903 —— 保存类别后，未保存标注/表单里的旧名就地跟着改。
 * 只动「这次编辑的那个控件」对应的状态（choices 按 fromName、labels 按画布形态分支），
 * 串改会把框类别换成整图类别新名字（:1852 注释的教训）。form 就地更新并返回。
 */
export function applyLabelRename(rename: Record<string, string>, labels: string[],
                                 control: string, fromName: string, s: WsState, ctl: WsControls,
                                 form: PanelForm): void {
  const has = (v: unknown) => typeof v === 'string' &&
    Object.prototype.hasOwnProperty.call(rename, v)
  const pick = (v: unknown) => (has(v) ? (rename[v as string] || labels[0]) : v as string)
  const fixArr = (o: Region) => {
    if (Array.isArray(o.labels)) o.labels = (o.labels as string[]).map(pick).filter(Boolean)
  }
  const fixBox = () => {
    s.boxes.forEach((b) => { b.label = pick(b.label); fixArr(b) })
    if (has(form.boxLabel)) form.boxLabel = pick(form.boxLabel)
  }
  switch (control) {
    case 'choices': {
      const c = ctl.choicesCtls.find((x) => x.from_name === fromName)
      if (c) {
        const cur = choicePicked(c, s).map(pick).filter(Boolean)
        s.choiceVals[c.from_name] = c.multiple ? cur : (cur[0] || '')
      }
      break
    }
    case 'rectanglelabels':
    case 'videorectangle':
      fixBox()
      break
    case 'polygonlabels':
      s.polygons.forEach((p) => { p.label = pick(p.label); fixArr(p) }); break
    case 'keypointlabels':
      s.keypoints.forEach((p) => { p.label = pick(p.label); fixArr(p) }); break
    case 'ellipselabels':
      s.ellipses.forEach((e) => { e.label = pick(e.label); fixArr(e) }); break
    case 'brushlabels':
      s.brushRegions.forEach((r) => { r.label = pick(r.label); fixArr(r) }); break
    case 'hypertextlabels':
      s.hyperSpans.forEach((x) => { x.label = pick(x.label); fixArr(x) }); break
    case 'labels':
      // 音频区间 和 视频框选 共用 <Labels> 控件，看画布是哪种（:1895）
      if (ctl.vrectCtl) fixBox()
      else {
        s.segments.forEach((x) => { x.label = pick(x.label); fixArr(x) })
        s.textSpans.forEach((x) => { x.label = pick(x.label); fixArr(x) })
        if (has(form.segLabel)) form.segLabel = pick(form.segLabel)
      }
      break
  }
}

// ---------- 切任务时的表单默认值（resetAnnotation:2327-2336 的数据态对应） ----------

/**
 * Vue 在 resetAnnotation 里把 segLabel/boxLabel 对齐到当前控件首选项、
 * relFrom/relTo 预填首两个区域；React 面板在 WsPanel 的「任务/详情」变更效应里调用。
 */
export function resetFormForTask(s: WsState, ctl: WsControls, mode: string,
                                 imageTool: string, form: PanelForm): void {
  if (ctl.segCtl) {
    const opts = ctl.segCtl.options || []
    if (!opts.some((o) => o.value === form.segLabel)) form.segLabel = opts.length ? opts[0].value : ''
    form.segStart = 0
    form.segEnd = 0
  }
  const toolCtl = activeToolCtl(ctl, mode, imageTool)
  const opts = (toolCtl && toolCtl.options) || []
  if (!opts.some((o) => o.value === form.boxLabel)) form.boxLabel = opts.length ? opts[0].value : ''
  const regions = regionsOf(s)
  form.relFrom = (regions[0] && regions[0].id) || ''
  form.relTo = (regions[1] && regions[1].id) || ''
}

// ---------- 类别配色（labelColor:1814 + labelColors:1805 面板徽章用；画布若需可复用） ----------

export const FALLBACK_COLORS = ['#1677ff', '#13c2c2', '#52c41a', '#faad14', '#722ed1', '#eb2f96']

export function labelColor(label: string, ctl: WsControls, mode: string, imageTool: string): string {
  for (const c of ctl.schema.controls || []) {
    for (const o of c.options || []) {
      if (o.value === label && (o as Record<string, any>).background) {
        return String((o as Record<string, any>).background)
      }
    }
  }
  const opts = (activeToolCtl(ctl, mode, imageTool) || {}).options || []
  const i = opts.findIndex((o) => o.value === label)
  return i >= 0 ? FALLBACK_COLORS[i % FALLBACK_COLORS.length] : '#1677ff'
}
