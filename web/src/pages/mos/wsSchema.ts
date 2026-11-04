/**
 * wsSchema.ts —— 标注结果契约冻结模块（REQ-UNIFY-6-1，核心义务）。
 *
 * 来源对照（只读取证，mos/platform/frontend/src/views/LabelStudioWorkbench.vue）：
 *   buildResult:2837-3033 / applyResult:2451-2624 / resetAnnotation:2256-2359 /
 *   snapshot:2028-2050 / choicePicked:2221-2233 / anyChoicePicked:2234 /
 *   hasExtraValue:2237-2244 / labelsOf|outLabels|setPrimaryLabel:2363-2380 /
 *   newShapeId|newSegmentId:2625-2630 / isoToLocalInput:2442-2450 /
 *   控件派生 controlOf/controlsOf/…:1338-1446 / canSave:1998-2023 / emptyAllowed:1996。
 *
 * 约定：6-2（面板）～6-5（撤销）一律向本模块对齐；JSON 形状原样移植，
 * 改动 result 结构 = 契约破坏，必须先过架构评审。mos 后端不改。
 * 与 Vue 的唯一有意偏差用「移植注:」标出（均为被排除到后续卡片的 UI 态）。
 */

// ---------- schema / 控件（后端 project.schema 原样，Vue:1338） ----------

export type CtlOption = { value: string; [k: string]: unknown }

export type SchemaCtl = {
  type: string
  from_name: string
  to_name?: string
  options?: CtlOption[]
  multiple?: boolean
  per_region?: boolean
  default?: unknown
  max?: number
  min?: number
  step?: number
  [k: string]: unknown
}

export type WsSchema = {
  mode?: string
  controls?: SchemaCtl[]
  text_fields?: { field: string; name?: string }[]
  hypertext_fields?: { field: string }[]
  timeseries_fields?: { field: string }[]
  media?: { field?: string } | null
  frame_rate?: number
  reason?: string
}

export const EMPTY_SCHEMA: WsSchema = {
  mode: 'text', controls: [], text_fields: [], hypertext_fields: [],
  media: null, frame_rate: 25,
}

/** 派生控件集：与 Vue computeds 一一对应（LabelStudioWorkbench.vue:1348-1446）。 */
export type WsControls = ReturnType<typeof deriveControls>

export function deriveControls(schema: WsSchema) {
  const list = schema.controls || []
  const controlOf = (t: string) => list.find((c) => c.type === t) || null
  const controlsOf = (t: string) => list.filter((c) => c.type === t)
  const mode = schema.mode || 'text'
  const textareaCtl = controlOf('textarea')
  const choicesCtls = controlsOf('choices')
  const labelsCtl = controlOf('labels')
  const timelineCtl = controlOf('timelinelabels')
  const rectCtl = controlOf('rectanglelabels')
  const vrectCtl = controlOf('videorectangle')
  // textSpanControl:1391 —— Labels 的 to_name 落在 text_fields 里才是文本区间
  const names = (schema.text_fields || []).map((f) => f.name)
  const textSpanCtl = mode === 'text' && labelsCtl && names.includes(labelsCtl.to_name || '')
    ? labelsCtl : null
  const rangeCtl = textSpanCtl ? null : labelsCtl
  const segCtl = rangeCtl || timelineCtl
  return {
    mode,
    schema,
    textareaCtl,
    // textareas:1355 —— 非 per_region 的文本框（多个按 from_name 分存）
    textareas: controlsOf('textarea').filter((c) => !c.per_region),
    choicesCtls,
    choiceCtl: choicesCtls[0] || null,
    labelsCtl, timelineCtl, rectCtl, vrectCtl, textSpanCtl, rangeCtl, segCtl,
    ratingCtls: controlsOf('rating'),
    numberCtls: controlsOf('number'),
    datetimeCtls: controlsOf('datetime'),
    taxonomyCtls: controlsOf('taxonomy'),
    pairwiseCtls: controlsOf('pairwise'),
    relationsCtl: controlOf('relations'),
    polygonCtl: controlOf('polygonlabels'),
    keypointCtl: controlOf('keypointlabels'),
    ellipseCtl: controlOf('ellipselabels'),
    brushCtl: controlOf('brushlabels'),
    hypertextCtl: controlOf('hypertextlabels'),
    timeseriesCtl: controlOf('timeserieslabels'),
    // boxCtl:1419 / perRegionText:1417 / hasShapeCtl:1411
    boxCtl: rectCtl || vrectCtl,
    perRegionText: !!(textareaCtl && textareaCtl.per_region && segCtl && rangeCtl),
    hasShapeCtl: !!(rectCtl || controlOf('polygonlabels') || controlOf('keypointlabels')
      || controlOf('ellipselabels') || controlOf('brushlabels')),
    controlOf, controlsOf,
  }
}

// ---------- 标注态（Vue refs 1457-1562 的纯数据化） ----------

export type Region = {
  id: string
  label: string
  labels?: string[]
  start?: number
  end?: number
  text?: string
  x?: number
  y?: number
  w?: number
  h?: number
  frame?: number | null
  sequence?: unknown[]
  points?: number[][]
  startOffset?: number | string
  endOffset?: number | string
  from_id?: string
  to_id?: string
  direction?: string
  rle?: number[]
  original_width?: number
  original_height?: number
  [k: string]: unknown
}

export type WsState = {
  texts: Record<string, string>            // Vue texts:1457（含 per_region 文本框）
  choiceVals: Record<string, string | string[]> // Vue choiceVals:1557
  extra: Record<string, unknown>           // rating/number/datetime/taxonomy/pairwise
  segments: Region[]                        // Vue segments:1558（labels/timelinelabels）
  boxes: Region[]                           // Vue boxes:1559
  polygons: Region[]
  keypoints: Region[]
  ellipses: Region[]
  textSpans: Region[]
  hyperSpans: Region[]
  relations: { id?: string; from_id: string; to_id: string; direction?: string }[]
  tsSegments: Region[]
  brushRegions: Region[]
  // 画布草稿态（6-3 填 UI；dirty/快照口径照 Vue:2055/2046-2047 保留字段）
  polyDraft: unknown[]
  brushDraft: unknown[]
  // 标注归属（Vue annotationId/annotationMine/viewingOther:2314-2324）
  annotationId: string | number | null
  annotationMine: boolean
  viewingOther: string
  restored: boolean
  // buildResult 的几何基准来自媒体元素（Vue naturalSize:2432 / duration / frameRate）
  imgNatural: { w: number; h: number }
  duration: number
  frameRate: number
}

export function emptyState(): WsState {
  return {
    texts: {}, choiceVals: {}, extra: {},
    segments: [], boxes: [], polygons: [], keypoints: [], ellipses: [],
    textSpans: [], hyperSpans: [], relations: [], tsSegments: [], brushRegions: [],
    polyDraft: [], brushDraft: [],
    annotationId: null, annotationMine: false, viewingOther: '', restored: false,
    imgNatural: { w: 0, h: 0 }, duration: 0, frameRate: 25,
  }
}

// ---------- 小工具（原样移植） ----------

export function newSegmentId(): string {
  return 'seg-' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-3)
}
export function newShapeId(prefix: string): string {
  return prefix + '-' + Math.random().toString(36).slice(2, 10)
}

/** isoToLocalInput:2442 —— 带秒；LS 存 ISO，datetime-local 只认本地串 */
export function isoToLocalInput(raw: unknown): string {
  if (!raw) return ''
  const d = new Date(raw as string)
  if (isNaN(d.getTime())) return String(raw)
  const p = (n: number) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate())
    + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

/** labelsOf:2363 / outLabels:2367 / setPrimaryLabel:2375（多类别保留口径） */
export function labelsOf(s: Region): string[] {
  if (Array.isArray(s.labels) && s.labels.length) return s.labels.filter(Boolean) as string[]
  return s.label ? [s.label] : []
}
export function outLabels(s: Region): string[] {
  if (Array.isArray(s.labels) && s.labels.length) {
    if (!s.label) return s.labels.filter(Boolean) as string[]
    return [s.label, ...s.labels.slice(1).filter((x) => x && x !== s.label)]
  }
  return s.label ? [s.label] : []
}
export function setPrimaryLabel(s: Region, val: string): void {
  s.label = val
  if (Array.isArray(s.labels) && s.labels.length) {
    s.labels = [val, ...s.labels.slice(1).filter((x: string) => x && x !== val)]
  }
}

/** choicePicked:2221 —— 多选按选项顺序输出，保证同一次标注结果确定 */
export function choicePicked(c: SchemaCtl, s: WsState): string[] {
  const v = s.choiceVals[c.from_name]
  const arr = Array.isArray(v) ? v.filter(Boolean) : (v ? [v] : [])
  if (!c.multiple || arr.length < 2) return arr
  const order = (c.options || []).map((o) => o.value)
  return arr.slice().sort((a, b) => {
    const ia = order.indexOf(a)
    const ib = order.indexOf(b)
    return (ia < 0 ? 1e9 : ia) - (ib < 0 ? 1e9 : ib)
  })
}
export function anyChoicePicked(ctls: SchemaCtl[], s: WsState): boolean {
  return ctls.some((c) => choicePicked(c, s).length > 0)
}
/** hasExtraValue:2237 */
export function hasExtraValue(extra: Record<string, unknown>): boolean {
  return Object.keys(extra).some((k) => {
    const v = extra[k]
    return v !== '' && v !== null && v !== undefined && !(Array.isArray(v) && !v.length)
  })
}

/** snapshot:2028 —— 键序与 Vue 完全一致；labels[] 必须在快照内（2033-2034 的教训） */
export function snapshotOf(s: WsState, ctl: WsControls): string {
  const first = ctl.textareaCtl
  return JSON.stringify({
    text: first ? (s.texts[first.from_name] || '') : '',
    texts: ctl.textareas.map((c) => [c.from_name, s.texts[c.from_name] || '']),
    choices: ctl.choicesCtls.map((c) => [c.from_name, s.choiceVals[c.from_name]]),
    segments: s.segments.map((r) => [r.id, r.label, r.labels || [], r.start, r.end, r.text || '']),
    boxes: s.boxes.map((b) => [b.id, b.label, b.labels || [], b.x, b.y, b.w, b.h, b.frame, b.sequence]),
    polygons: s.polygons.map((p) => [p.id, p.label, p.labels || [], p.points]),
    keypoints: s.keypoints.map((p) => [p.id, p.label, p.labels || [], p.x, p.y]),
    ellipses: s.ellipses.map((p) => [p.id, p.label, p.labels || [], p.x, p.y, p.w, p.h]),
    spans: s.textSpans.map((r) => [r.id, r.label, r.labels || [], r.start, r.end, r.text]),
    hyper: s.hyperSpans.map((r) => [r.id, r.label, r.labels || [], r.start, r.end,
      r.startOffset, r.endOffset, r.text]),
    relations: s.relations.map((r) => [r.from_id, r.to_id, r.direction]),
    ts: s.tsSegments.map((r) => [r.id, r.label, r.labels || [], r.start, r.end]),
    brush: s.brushRegions.map((r) => [r.id, r.label, r.labels || [], r.w, r.h,
      (r.rle || []).length, (r.rle || [])[0] || 0, (r.rle || [])[(r.rle || []).length - 1] || 0]),
    polyDraft: s.polyDraft,
    brushDraft: s.brushDraft,
    extra: { ...s.extra },
  })
}

/**
 * dirty:2055 —— 快照差异 ∨ 未完成草稿几何。
 * （polyDraft/brushDraft 的 UI 编辑在 6-3；字段与口径在此冻结。）
 */
export function isDirty(s: WsState, ctl: WsControls, baseline: string,
                        hasSelection: boolean): boolean {
  if (!hasSelection) return false
  return snapshotOf(s, ctl) !== baseline || s.polyDraft.length > 0 || s.brushDraft.length > 0
}

// ---------- buildResult（:2837-3033 原样移植） ----------

/** state → LS result[]。与 Vue 相同：会就地补 id（!r.id 时写入 state）。 */
export function buildResult(s: WsState, ctl: WsControls): Record<string, unknown>[] {
  const out: Record<string, unknown>[] = []
  if (!ctl.perRegionText) {
    for (const c of ctl.textareas) {
      const v = String(s.texts[c.from_name] || '')
      if (v.trim()) {
        out.push({ from_name: c.from_name, to_name: c.to_name,
                   type: 'textarea', value: { text: [v] } })
      }
    }
  }
  for (const c of ctl.choicesCtls) {
    const picked = choicePicked(c, s)
    if (picked.length) {
      out.push({ from_name: c.from_name, to_name: c.to_name,
                 type: 'choices', value: { choices: picked } })
    }
  }
  for (const c of ctl.ratingCtls) {
    const v = s.extra[c.from_name]
    if (v != null && v !== '') {
      out.push({ from_name: c.from_name, to_name: c.to_name,
                 type: 'rating', value: { rating: Number(v) } })
    }
  }
  for (const c of ctl.numberCtls) {
    const v = s.extra[c.from_name]
    if (v != null && v !== '') {
      out.push({ from_name: c.from_name, to_name: c.to_name,
                 type: 'number', value: { number: Number(v) } })
    }
  }
  for (const c of ctl.datetimeCtls) {
    const raw = s.extra[c.from_name]
    if (!raw) continue
    let iso = raw
    const dt = new Date(raw as string)
    if (!isNaN(dt.getTime())) iso = dt.toISOString()
    out.push({ from_name: c.from_name, to_name: c.to_name,
               type: 'datetime', value: { datetime: iso } })
  }
  for (const c of ctl.taxonomyCtls) {
    const path = s.extra[c.from_name]
    if (Array.isArray(path) && path.length) {
      out.push({ from_name: c.from_name, to_name: c.to_name,
                 type: 'taxonomy', value: { taxonomy: [path] } })
    }
  }
  for (const c of ctl.pairwiseCtls) {
    if (s.extra[c.from_name]) {
      out.push({ from_name: c.from_name, to_name: c.to_name,
                 type: 'pairwise', value: { selected: [s.extra[c.from_name]] } })
    }
  }
  if (ctl.textSpanCtl) {
    for (const sp of s.textSpans) {
      if (!sp.id) sp.id = newShapeId('span')
      out.push({ from_name: ctl.textSpanCtl.from_name, to_name: ctl.textSpanCtl.to_name,
                 type: 'labels', id: sp.id,
                 value: { start: sp.start, end: sp.end, text: sp.text, labels: outLabels(sp) } })
    }
  }
  if (ctl.brushCtl) {
    for (const r of s.brushRegions) {
      if (!r.id) r.id = newShapeId('brush')
      out.push({ from_name: ctl.brushCtl.from_name, to_name: ctl.brushCtl.to_name,
                 type: 'brushlabels', id: r.id,
                 original_width: r.w, original_height: r.h, image_rotation: 0,
                 value: { format: 'rle', rle: r.rle, brushlabels: outLabels(r) } })
    }
  }
  if (ctl.timeseriesCtl) {
    for (const seg of s.tsSegments) {
      if (!seg.id) seg.id = newShapeId('ts')
      out.push({ from_name: ctl.timeseriesCtl.from_name,
                 to_name: ctl.timeseriesCtl.to_name,
                 type: 'timeserieslabels', id: seg.id,
                 value: { start: seg.start, end: seg.end, instant: false,
                          timeserieslabels: outLabels(seg) } })
    }
  }
  if (ctl.hypertextCtl) {
    for (const sp of s.hyperSpans) {
      if (!sp.id) sp.id = newShapeId('hspan')
      out.push({ from_name: ctl.hypertextCtl.from_name, to_name: ctl.hypertextCtl.to_name,
                 type: 'hypertextlabels', id: sp.id,
                 value: { start: sp.start, end: sp.end,
                          startOffset: sp.startOffset, endOffset: sp.endOffset,
                          text: sp.text, hypertextlabels: outLabels(sp) } })
    }
  }
  if (ctl.rangeCtl) {
    for (const seg of s.segments) {
      const id = seg.id || newSegmentId()
      seg.id = id
      out.push({ from_name: ctl.rangeCtl.from_name, to_name: ctl.rangeCtl.to_name,
                 type: 'labels', id,
                 value: { start: Number(seg.start), end: Number(seg.end),
                          channel: 0, labels: outLabels(seg) } })
      // perRegion 文本：与区间共用同一个 id（Label Studio 原生口径，2936 注释）
      if (ctl.perRegionText && String(seg.text || '').trim() && ctl.textareaCtl) {
        out.push({ from_name: ctl.textareaCtl.from_name, to_name: ctl.textareaCtl.to_name,
                   type: 'textarea', id,
                   value: { start: Number(seg.start), end: Number(seg.end),
                            channel: 0, text: [seg.text] } })
      }
    }
  }
  if (ctl.timelineCtl) {
    for (const seg of s.segments) {
      out.push({ from_name: ctl.timelineCtl.from_name, to_name: ctl.timelineCtl.to_name,
                 type: 'timelinelabels',
                 value: { ranges: [{ start: Math.round(Number(seg.start)),
                                     end: Math.round(Number(seg.end)) }],
                          timelinelabels: outLabels(seg) } })
    }
  }
  if (ctl.rectCtl) {
    const nat = s.imgNatural
    for (const b of s.boxes) {
      if (!b.id) b.id = newShapeId('box')
      out.push({ from_name: ctl.rectCtl.from_name, to_name: ctl.rectCtl.to_name,
                 type: 'rectanglelabels', id: b.id,
                 original_width: b.original_width || nat.w,
                 original_height: b.original_height || nat.h,
                 value: { x: b.x, y: b.y, width: b.w, height: b.h, rotation: 0,
                          rectanglelabels: outLabels(b) } })
    }
  }
  if (ctl.polygonCtl) {
    const nat = s.imgNatural
    for (const p of s.polygons) {
      if (!p.id) p.id = newShapeId('poly')
      out.push({ from_name: ctl.polygonCtl.from_name, to_name: ctl.polygonCtl.to_name,
                 type: 'polygonlabels', id: p.id,
                 original_width: nat.w, original_height: nat.h,
                 value: { points: p.points, polygonlabels: outLabels(p) } })
    }
  }
  if (ctl.keypointCtl) {
    const nat = s.imgNatural
    for (const p of s.keypoints) {
      if (!p.id) p.id = newShapeId('kp')
      out.push({ from_name: ctl.keypointCtl.from_name, to_name: ctl.keypointCtl.to_name,
                 type: 'keypointlabels', id: p.id,
                 original_width: nat.w, original_height: nat.h,
                 value: { x: p.x, y: p.y, width: 0.5, keypointlabels: outLabels(p) } })
    }
  }
  if (ctl.ellipseCtl) {
    const nat = s.imgNatural
    for (const e of s.ellipses) {
      if (!e.id) e.id = newShapeId('ell')
      out.push({ from_name: ctl.ellipseCtl.from_name, to_name: ctl.ellipseCtl.to_name,
                 type: 'ellipselabels', id: e.id,
                 original_width: nat.w, original_height: nat.h,
                 value: { x: (e.x || 0) + (e.w || 0) / 2, y: (e.y || 0) + (e.h || 0) / 2,
                          radiusX: (e.w || 0) / 2, radiusY: (e.h || 0) / 2, rotation: 0,
                          ellipselabels: outLabels(e) } })
    }
  }
  if (ctl.relationsCtl) {
    for (const r of s.relations) {
      out.push({ from_name: ctl.relationsCtl.from_name,
                 to_name: ctl.relationsCtl.to_name,
                 type: 'relation',
                 value: { from_id: r.from_id, to_id: r.to_id,
                          direction: r.direction || 'right' } })
    }
  }
  if (ctl.vrectCtl) {
    const fps = s.frameRate || 25
    const dur = Number(s.duration) || 0
    for (const b of s.boxes) {
      if (!b.id) b.id = newShapeId('box')
      const seq = (b.sequence && b.sequence.length)
        ? b.sequence.slice().sort((a, c) => Number((a as Region).frame) - Number((c as Region).frame))
        : [{ frame: Number(b.frame) || 0, x: b.x, y: b.y, w: b.w, h: b.h,
             time: Number((((Number(b.frame) || 0) / fps)).toFixed(3)) } as unknown as Region]
      const last = (seq[seq.length - 1] || { frame: 0 }) as Region
      out.push({ from_name: ctl.vrectCtl.from_name, to_name: ctl.vrectCtl.to_name,
                 type: 'videorectangle', id: b.id,
                 value: {
                   framesCount: Math.max(Number(last.frame) + 1, Math.round(dur * fps) || 1),
                   duration: Number(dur.toFixed(2)),
                   sequence: seq.map((k0) => {
                     const k = k0 as Region
                     return {
                       frame: k.frame, enabled: true, rotation: 0,
                       x: k.x, y: k.y, width: k.w, height: k.h,
                       time: Number((Number(k.frame) / fps).toFixed(3)),
                     }
                   }),
                   labels: outLabels(b),
                 } })
    }
  }
  return out
}

// ---------- applyResult（:2451-2624 原样移植，就地写入 state） ----------

export function applyResult(result: Record<string, unknown>[] | undefined | null,
                            s: WsState, ctl: WsControls): void {
  const items = result || []
  const regionText: Record<string, string> = {}
  const looseTexts: string[] = []
  // regionIds:2457 —— 只有「id 与某区域相同」的 textarea 才是 perRegion 文本
  const regionIds = new Set<string>()
  for (const item of items) {
    if (!item || typeof item !== 'object' || item.type === 'textarea') continue
    if (item.id) regionIds.add(String(item.id))
  }
  for (const item of items) {
    if (!item || typeof item !== 'object') continue
    if (item.type === 'textarea') {
      const v = item.value || {}
      const t = (((v as Record<string, unknown>).text) as string[] || []).join('\n')
      if (item.id && regionIds.has(String(item.id))) {
        regionText[String(item.id)] = t
      } else {
        const c2 = ctl.textareas.find((c) => c.from_name === item.from_name)
        if (c2) s.texts[c2.from_name] = t
        else looseTexts.push(t)
      }
    }
  }
  for (const item of items) {
    if (!item || typeof item !== 'object') continue
    const val = (item.value || {}) as Record<string, unknown>
    if (item.type === 'textarea') {
      continue
    } else if (item.type === 'choices') {
      const v = val.choices
      const c2 = ctl.choicesCtls.find((c) => c.from_name === item.from_name) || ctl.choiceCtl
      if (c2) {
        // 单选存字符串、多选存数组（2486 注释：否则重载后勾不上）
        s.choiceVals[c2.from_name] = c2.multiple
          ? (Array.isArray(v) ? v as string[] : (v ? [String(v)] : []))
          : (Array.isArray(v) ? ((v as string[])[0] || '') : String(v || ''))
      }
    } else if (item.type === 'labels') {
      if (ctl.textSpanCtl && item.to_name === ctl.textSpanCtl.to_name) {
        const raw = val.text
        const snippet = Array.isArray(raw) ? raw.join(' ') : String(raw || '')
        const labs = ((val.labels || []) as string[]).filter(Boolean)
        s.textSpans.push({ id: (item.id || newShapeId('span')) as string, label: labs[0] || '',
                           labels: labs,
                           start: Number(val.start) || 0, end: Number(val.end) || 0, text: snippet })
      } else {
        const labs = ((val.labels || []) as string[]).filter(Boolean)
        s.segments.push({
          id: (item.id || '') as string,
          label: labs[0] || '', labels: labs,
          start: Number(val.start) || 0, end: Number(val.end) || 0,
          text: item.id ? (regionText[String(item.id)] || '') : '',
        })
      }
    } else if (item.type === 'timelinelabels') {
      const labels = ((val.timelinelabels || []) as string[]).filter(Boolean)
      const ranges = (val.ranges || []) as { start: number; end: number }[]
      ranges.forEach((r, i) => {
        s.segments.push({ id: (item.id || '') as string, label: labels[i] || labels[0] || '',
                          labels, start: Number(r.start) || 0, end: Number(r.end) || 0, text: '' })
      })
    } else if (item.type === 'rectanglelabels') {
      const rlabs = ((val.rectanglelabels || []) as string[]).filter(Boolean)
      s.boxes.push({
        id: (item.id || newShapeId('box')) as string,
        label: rlabs[0] || '', labels: rlabs,
        x: Number(val.x) || 0, y: Number(val.y) || 0,
        w: Number(val.width) || 0, h: Number(val.height) || 0,
        frame: null,
        original_width: Number(item.original_width) || 0,
        original_height: Number(item.original_height) || 0,
      })
    } else if (item.type === 'rating') {
      s.extra[String(item.from_name)] = Number(val.rating) || 0
    } else if (item.type === 'number') {
      s.extra[String(item.from_name)] = Number(val.number) || 0
    } else if (item.type === 'datetime') {
      s.extra[String(item.from_name)] = isoToLocalInput(val.datetime || '')
    } else if (item.type === 'taxonomy') {
      const paths = (val.taxonomy || []) as unknown[]
      if (paths.length) s.extra[String(item.from_name)] = paths[0]
    } else if (item.type === 'pairwise') {
      const sel = Array.isArray(val.selected) ? (val.selected as unknown[])[0] : val.selected
      s.extra[String(item.from_name)] = (sel as string) || ''
    } else if (item.type === 'polygonlabels') {
      const plabs = ((val.polygonlabels || []) as string[]).filter(Boolean)
      s.polygons.push({
        id: (item.id || newShapeId('poly')) as string,
        label: plabs[0] || '', labels: plabs,
        points: ((val.points || []) as number[][]).map((p) => [Number(p[0]) || 0, Number(p[1]) || 0]),
      })
    } else if (item.type === 'keypointlabels') {
      const klabs = ((val.keypointlabels || []) as string[]).filter(Boolean)
      s.keypoints.push({
        id: (item.id || newShapeId('kp')) as string,
        label: klabs[0] || '', labels: klabs,
        x: Number(val.x) || 0, y: Number(val.y) || 0,
      })
    } else if (item.type === 'ellipselabels') {
      // 2558：LS 存圆心+半径，内部存左上角+宽高
      const rx = Number(val.radiusX || 0) || Number(val.width || 0) / 2
      const ry = Number(val.radiusY || 0) || Number(val.height || 0) / 2
      const elabs = ((val.ellipselabels || []) as string[]).filter(Boolean)
      s.ellipses.push({
        id: (item.id || newShapeId('ell')) as string,
        label: elabs[0] || '', labels: elabs,
        x: (Number(val.x) || 0) - rx, y: (Number(val.y) || 0) - ry,
        w: rx * 2, h: ry * 2,
      })
    } else if (item.type === 'brushlabels') {
      const blabs = ((val.brushlabels || []) as string[]).filter(Boolean)
      s.brushRegions.push({
        id: (item.id || newShapeId('brush')) as string,
        label: blabs[0] || '', labels: blabs,
        rle: (val.rle || []) as number[],
        w: Number(item.original_width) || 0,
        h: Number(item.original_height) || 0,
      })
    } else if (item.type === 'timeserieslabels') {
      const tlabs = ((val.timeserieslabels || []) as string[]).filter(Boolean)
      s.tsSegments.push({
        id: (item.id || newShapeId('ts')) as string,
        label: tlabs[0] || '', labels: tlabs,
        start: Number(val.start) || 0, end: Number(val.end) || 0,
      })
    } else if (item.type === 'hypertextlabels') {
      const hlabs = ((val.hypertextlabels || []) as string[]).filter(Boolean)
      s.hyperSpans.push({
        id: (item.id || newShapeId('hspan')) as string,
        label: hlabs[0] || '', labels: hlabs,
        start: (val.start || '') as number, end: (val.end || '') as number,
        startOffset: Number(val.startOffset) || 0, endOffset: Number(val.endOffset) || 0,
        text: (val.text || '') as string,
      })
    } else if (item.type === 'relation') {
      s.relations.push({
        id: (item.id || newShapeId('rel')) as string,
        from_id: (val.from_id || '') as string, to_id: (val.to_id || '') as string,
        direction: (val.direction || 'right') as string,
      })
    } else if (item.type === 'videorectangle') {
      const seq = ((val.sequence || []) as Record<string, unknown>[]).map((k) => ({
        frame: Number(k.frame) || 0,
        x: Number(k.x) || 0, y: Number(k.y) || 0,
        w: Number(k.width) || 0, h: Number(k.height) || 0,
        time: Number(k.time) || 0,
      })).sort((a, b) => a.frame - b.frame)
      const first = seq[0] || ({} as { frame?: number; x?: number; y?: number; w?: number; h?: number })
      const vlabs = ((val.labels || []) as string[]).filter(Boolean)
      s.boxes.push({
        id: (item.id || newShapeId('box')) as string,
        label: vlabs[0] || '', labels: vlabs,
        x: Number(first.x) || 0, y: Number(first.y) || 0,
        w: Number(first.w) || 0, h: Number(first.h) || 0,
        frame: first.frame != null ? Number(first.frame) : null,
        sequence: seq,
        original_width: 0, original_height: 0,
      })
    }
  }
  if (ctl.textareaCtl && !ctl.perRegionText && looseTexts.length && ctl.textareaCtl) {
    s.texts[ctl.textareaCtl.from_name] = looseTexts.filter(Boolean).join('\n')
  }
}

// ---------- resetAnnotation（:2256-2359 移植；UI 选择态注记见「移植注」） ----------

export type TaskDetail = {
  annotations?: Record<string, any>[]
  answer?: string
  [k: string]: unknown
}

export function resetAnnotation(s: WsState, ctl: WsControls, d: TaskDetail | null): void {
  for (const k of Object.keys(s.texts)) delete s.texts[k]
  for (const c of ctl.textareas) if (s.texts[c.from_name] == null) s.texts[c.from_name] = ''
  // 2261 注释：不要在这里先塞 d.answer —— 带 result 的标注由 applyResult 按控件回填
  for (const k of Object.keys(s.choiceVals)) delete s.choiceVals[k]
  for (const c of ctl.choicesCtls) {
    if (s.choiceVals[c.from_name] == null) s.choiceVals[c.from_name] = c.multiple ? [] : ''
  }
  s.segments = []; s.boxes = []; s.polygons = []; s.keypoints = []
  s.ellipses = []; s.textSpans = []; s.hyperSpans = []; s.relations = []
  s.polyDraft = []; s.brushDraft = []
  for (const k of Object.keys(s.extra)) delete s.extra[k]
  s.tsSegments = []; s.brushRegions = []
  s.restored = false
  // 移植注：segLabel/boxLabel/relFrom/relTo/imageTool/zoom/timeseries 的 loadTimeseries
  // 都是画布 UI 态，归 6-3/6-5；这里只保留数据态（2289-2304 的对应物）。
  // 配置里的默认值（同类型多个控件逐个填，2304-2313）
  for (const c of ctl.ratingCtls) if (c.default != null) s.extra[c.from_name] = Number(c.default)
  for (const c of ctl.numberCtls) if (c.default != null) s.extra[c.from_name] = Number(c.default)
  for (const c of ctl.datetimeCtls) if (c.default) s.extra[c.from_name] = isoToLocalInput(c.default)
  s.annotationId = null
  s.annotationMine = false
  s.viewingOther = ''
  const anns = (d && d.annotations) || []
  const mine = anns.filter((a) => a.mine)
  const latestAny = anns.length ? anns[anns.length - 1] : null
  // 2320：优先显示「我自己」的标注；没有我的才显示最后一份（只读参考）
  const latest = mine.length ? mine[mine.length - 1] : latestAny
  s.annotationId = latest && latest.mine ? latest.id : null
  s.annotationMine = !!(latest && latest.mine)
  s.viewingOther = (!mine.length && latestAny) ? (latestAny.by || '其他标注员') : ''
  if (latest && Array.isArray(latest.result) && latest.result.length) {
    applyResult(latest.result, s, ctl)
  } else if (d && d.answer && !ctl.perRegionText && ctl.textareaCtl) {
    // applyLegacyAnswer:2437
    s.texts[ctl.textareaCtl.from_name] = String(d.answer || '')
  }
  // 移植注：2339-2357 的 nextTick 媒体补判/resetHistory 归 6-3/6-4。
}

// ---------- canSave / emptyAllowed（:1996-2023） ----------

export function emptyAllowedOf(project: Record<string, any> | null): boolean {
  return !!(project && project.enable_empty_annotation)
}

export type CanSaveCtx = {
  detailLoaded: boolean          // detailLoading 完成且有 detail
  project: Record<string, any> | null
  ctl: WsControls
  s: WsState
}

export function canSaveOf({ detailLoaded, project, ctl, s }: CanSaveCtx): boolean {
  // 1999 注释：详情没到之前不能保存 —— 会把任务从待标注队列移走、原标注只能去历史里找
  if (!detailLoaded) return false
  if (!project || !project.workbench) return false
  if (ctl.perRegionText && s.segments.some((seg) => !String(seg.text || '').trim())) return false
  let any = false
  if (!ctl.perRegionText
      && ctl.textareas.some((c) => String(s.texts[c.from_name] || '').trim())) any = true
  if (anyChoicePicked(ctl.choicesCtls, s)) any = true
  if (ctl.segCtl && s.segments.length) any = true
  if (ctl.boxCtl && s.boxes.length) any = true
  if (ctl.polygonCtl && s.polygons.length) any = true
  if (ctl.keypointCtl && s.keypoints.length) any = true
  if (ctl.ellipseCtl && s.ellipses.length) any = true
  if (ctl.textSpanCtl && s.textSpans.length) any = true
  if (ctl.hypertextCtl && s.hyperSpans.length) any = true
  if (ctl.timeseriesCtl && s.tsSegments.length) any = true
  if (ctl.brushCtl && s.brushRegions.length) any = true
  if (ctl.relationsCtl && s.relations.length) any = true
  if (hasExtraValue(s.extra)) any = true
  return any || emptyAllowedOf(project)
}
