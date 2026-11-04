/**
 * geometry.ts —— 画布纯几何/解析层（REQ-UNIFY-6-3）。
 *
 * 来源对照（只读，mos/platform/frontend/src/views/LabelStudioWorkbench.vue）：
 *   clampPct|round2:3340-3341 / stagePoint:3230-3248 / boxRect:3528-3537 /
 *   upsertKeyframe:3539-3548 / activeKeyframes:3185-3192 / jumpKeyframe:3194-3202 /
 *   totalFrames|goFrame:3170-3183 / parseSeries:2730-2754 / tsDomain|tsPolyline:1376-1390 /
 *   tsRect:2810-2814 / tsRatio:2778-2781 / clock|baseName:2246-2255 /
 *   CORNERS:1565 / labelColors|FALLBACK_COLORS|labelColor:1805-1821 /
 *   segFromNow|addSegment|fixSegment:3206-3229。
 *
 * 约定：全部无 DOM 依赖，node 里可直跑（unify6-canvas-test.py 的断言对象）。
 * 坐标口径与 6-1 契约一致：区域 x/y/w/h、points 均为相对 stage 的百分比 0-100。
 */
import type { Region } from '../../../pages/mos/wsSchema'

export type RectLike = { left: number; top: number; width: number; height: number }

export const clampPct = (v: number) => Math.max(0, Math.min(100, v))   // 3340
export const round2 = (v: number) => Number(v.toFixed(2))              // 3341

/** stagePointAt:3241-3248 —— 屏幕坐标 → stage 百分比坐标（夹 0-100） */
export function stagePointAt(rect: RectLike, clientX: number, clientY: number) {
  const width = Math.max(1, rect.width)
  const height = Math.max(1, rect.height)
  return { x: clampPct((clientX - rect.left) / width * 100),
           y: clampPct((clientY - rect.top) / height * 100) }
}

// ---------- 视频关键帧（3170-3202 / 3528-3548） ----------

export type Keyframe = {
  frame: number; x?: number; y?: number; w?: number; h?: number; time?: number
  [k: string]: unknown
}

export function num(v: unknown): number { return Number(v) || 0 }

/** boxRect:3528-3537 —— 视频框取「距当前帧最近」的关键帧矩形；无 sequence 回退框自身 */
export function boxRectAt(video: boolean, b: Region, currentFrame: number): Keyframe {
  const seq = (b.sequence || []) as Keyframe[]
  if (video && seq.length) {
    let best = seq[0]
    for (const k of seq) {
      if (Math.abs(num(k.frame) - currentFrame) < Math.abs(num(best.frame) - currentFrame)) best = k
    }
    return best
  }
  return b as Keyframe
}

/** upsertKeyframe:3539-3548 —— 同帧覆盖、按 frame 排序、time=frame/fps 保 3 位 */
export function upsertKeyframe(b: Region, frame: number,
                              rect: { x: number; y: number; w: number; h: number },
                              frameRate: number): void {
  if (!Array.isArray(b.sequence)) b.sequence = []
  const kf: Keyframe = { frame, x: rect.x, y: rect.y, w: rect.w, h: rect.h,
                         time: Number((frame / frameRate).toFixed(3)) }
  const seq = b.sequence as Keyframe[]
  const at = seq.find((k) => k.frame === frame)
  if (at) Object.assign(at, kf)
  else seq.push(kf)
  seq.sort((a, c) => a.frame - c.frame)
  Object.assign(b, { x: kf.x, y: kf.y, w: kf.w, h: kf.h, frame })
}

/** activeKeyframes:3185-3192 —— sequence∪frame，去重升序 */
export function keyframesOf(b: Region | null): number[] {
  if (!b) return []
  const frames = ((b.sequence || []) as Keyframe[])
    .map((k) => Number(k.frame)).filter(Number.isFinite)
  if (!frames.length && b.frame != null) frames.push(Number(b.frame))
  return Array.from(new Set(frames)).sort((a, x) => a - x)
}

/** jumpKeyframe:3194-3202 —— 找不到就回绕到首/末 */
export function jumpTarget(frames: number[], cur: number, dir: number): number | null {
  if (!frames.length) return null
  const target = dir > 0
    ? (frames.find((f) => f > cur) ?? frames[frames.length - 1])
    : ([...frames].reverse().find((f) => f < cur) ?? frames[0])
  return target
}

/** totalFrames:3170-3174 */
export function totalFramesOf(duration: number, frameRate: number): number {
  const sec = Number(duration) || 0
  return Math.max(1, Math.round(sec * (Number(frameRate) || 25)))
}

/** goFrame:3179 的夹取口径（0 ≤ nf ≤ totalFrames-1，非整数帧四舍五入） */
export function clampFrame(frame: number, duration: number, frameRate: number): number {
  return Math.max(0, Math.min(totalFramesOf(duration, frameRate) - 1,
    Math.round(Number(frame) || 0)))
}

// ---------- 时间序列（2730-2814 / 1376-1390） ----------

export type TsPoint = { x: number; y: number }
export type TsDomain = { x0: number; x1: number; y0: number; y1: number }

/** parseSeries:2730-2754 —— JSON 二维/对象数组优先，CSV 带分号制表符与无表头兜底 */
export function parseSeries(raw: unknown): TsPoint[] {
  const text = String(raw ?? '').trim()
  if (!text) return []
  try {
    if (text.startsWith('[')) {
      const arr = JSON.parse(text)
      if (Array.isArray(arr)) {
        return (arr as any[]).map((row) => Array.isArray(row)
          ? { x: Number(row[0]), y: Number(row[1]) }
          : { x: Number(row.x ?? row.time), y: Number(row.y ?? row.value) })
          .filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y))
      }
    }
  } catch { /* CSV fallback：2743 */ }
  const lines = text.split(/\r?\n/).filter((l) => l.trim())
  if (lines.length < 2) return []
  const sep = lines[0].includes('\t') ? '\t' : (lines[0].includes(';') ? ';' : ',')
  const rows = lines.slice(1).map((l) => l.split(sep).map((x) => x.trim()))
  let pts = rows.map((r) => ({ x: Number(r[0]), y: Number(r[1]) }))
    .filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y))
  if (!pts.length) {
    pts = lines.map((l) => l.split(sep)).map((r) => ({ x: Number(r[0]), y: Number(r[1]) }))
      .filter((p) => Number.isFinite(p.x) && Number.isFinite(p.y))
  }
  return pts
}

/** tsDomain:1376-1382 —— 空点集给 0..1 单位域；全同值 x1/y1 用 min+1 兜底 */
export function tsDomainOf(pts: TsPoint[]): TsDomain {
  const xs = pts.map((p) => p.x)
  const ys = pts.map((p) => p.y)
  if (!xs.length) return { x0: 0, x1: 1, y0: 0, y1: 1 }
  return { x0: Math.min(...xs), x1: Math.max(...xs) || Math.min(...xs) + 1,
           y0: Math.min(...ys), y1: Math.max(...ys) || Math.min(...ys) + 1 }
}

/** tsPolyline:1383-1390 —— viewBox 0 0 1000 320；y=310-归一*290 */
export function tsPolylineOf(pts: TsPoint[], d: TsDomain): string {
  return pts.map((p) => {
    const x = ((p.x - d.x0) / (d.x1 - d.x0 || 1)) * 1000
    const y = 310 - ((p.y - d.y0) / (d.y1 - d.y0 || 1)) * 290
    return x.toFixed(1) + ',' + y.toFixed(1)
  }).join(' ')
}

/** tsRect:2810-2814 —— 区间 → SVG 千分位矩形 */
export function tsRectOf(s: { start?: number; end?: number }, d: TsDomain) {
  const a = (((num(s.start)) - d.x0) / (d.x1 - d.x0 || 1)) * 1000
  const b = (((num(s.end)) - d.x0) / (d.x1 - d.x0 || 1)) * 1000
  return { left: Math.min(a, b), width: Math.abs(b - a) }
}

/** tsRatio:2778-2781 —— 横向拖拽比例（夹 0-1） */
export function tsRatio(rect: RectLike, clientX: number): number {
  return Math.max(0, Math.min(1, (clientX - rect.left) / rect.width))
}

// ---------- 杂项（2246-2255 / 1565 / 1805-1821 / 3206-3229） ----------

export function baseName(u: unknown): string {
  const s = String(u || '').split('?')[0]
  try { return decodeURIComponent(s.split('/').pop() || '') } catch { return s.split('/').pop() || '' }
}

export function clock(sec: unknown): string {
  const s = Math.max(0, Number(sec) || 0)
  const m = Math.floor(s / 60)
  const r = (s % 60).toFixed(1).padStart(4, '0')
  return m + ':' + r
}

export const CORNERS = ['nw', 'ne', 'sw', 'se'] as const      // 1565

export const FALLBACK_COLORS = ['#1677ff', '#13c2c2', '#52c41a', '#faad14', '#722ed1', '#eb2f96']  // 1809

/** labelColors:1805-1813 —— 每个控件 options 的 background 汇总成一张表 */
export function labelColorsOf(controls: { options?: { value?: string; background?: string }[] }[]):
  Record<string, string> {
  const out: Record<string, string> = {}
  for (const c of controls || []) {
    for (const o of c.options || []) {
      if (o.value && o.background) out[o.value] = o.background
    }
  }
  return out
}

/** labelColor:1815-1821 —— 无自定义色时按当前工具控件选项序取调色板，兜底蓝 */
export function labelColorFor(label: string, colors: Record<string, string>,
                              options: { value?: string }[]): string {
  if (colors[label]) return colors[label]
  const i = (options || []).findIndex((o) => o.value === label)
  return i >= 0 ? FALLBACK_COLORS[i % FALLBACK_COLORS.length] : '#1677ff'
}

/** segFromNow:3206-3210 —— 以「当前时刻」为起点开一个区间（frame 单位取整秒长） */
export function segFromNow(unit: 'frame' | 's', currentFrame: number,
                           currentTime: number, frameRate: number) {
  const start = unit === 'frame' ? currentFrame : Number(currentTime.toFixed(1))
  const end = start + (unit === 'frame' ? Math.round(frameRate || 25) : 1)
  return { start, end }
}

/** addSegment:3211-3217 的校验段（UI 在 6-2 面板，逻辑归画布时钟域） */
export function segAddError(label: string, a: unknown, b: unknown): string {
  if (!label) return '先选一个标签'
  if ((Number(b) || 0) <= (Number(a) || 0)) return '终点必须大于起点'
  return ''
}

/** fixSegment:3219-3229 —— 直接改起止时间后的兜底（就地修正） */
export function fixSegmentIn(s: { start?: number; end?: number }, unit: 'frame' | 's'): void {
  const step = unit === 's' ? 0.1 : 1
  const digits = unit === 's' ? 1 : 0
  let a = Number(s.start)
  let b = Number(s.end)
  if (!Number.isFinite(a) || a < 0) a = 0
  if (!Number.isFinite(b) || b <= a) b = a + step
  s.start = Number(a.toFixed(digits))
  s.end = Number(b.toFixed(digits))
}
