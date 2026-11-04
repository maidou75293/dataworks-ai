/**
 * brush.ts —— 画笔层：屏幕涂抹 + 区域重绘 + 收笔成 mask（REQ-UNIFY-6-4）。
 *
 * 逐行对照 LabelStudioWorkbench.vue（只读原件）：ensureBrushCanvas:3600 /
 *   brushLine:3610 / brushDot:3623 / redrawBrush:3655 / finishBrush:3680。
 * 框架无关口径与 6-3 EngineEnv 同族：canvas 接触面全部走 BrushEnv 注入 ——
 *   浏览器由 WsMediaStage 喂真 <canvas>（getContext('2d') 结构子集天然满足）；
 *   node 单测喂纯 JS 栅格化假画布（胶囊/圆盘覆盖：像素中心距 ≤ 半径即 alpha=255，
 *   与浏览器 AA 边缘差 ≤1px；往返断言只依赖 codec，不依赖 AA）。
 *
 * 坐标系：笔迹点为 stage 百分比 [x%,y%]（stagePoint:3230，6-3 已交付）；
 *   屏幕画布按显示尺寸（Vue img.clientWidth），离屏按 naturalSize:2432 出 mask。
 * brushDraft（= Vue brushPaths:1611）只在涂抹期存在，收笔转 brushRegions 后清空；
 *   dirty/undo 序列化已由 6-1 契约冻结（wsSchema snapshotOf:244-247 / isDirty:259）。
 */
import { newShapeId } from '../../../pages/mos/wsSchema'
import type { Region } from '../../../pages/mos/wsSchema'
import type { CanvasHost } from './engine'
import { maskToRle, rleToMask } from './rle'

export interface BrushImageDataLike { data: Uint8ClampedArray }

export interface BrushCtxLike {
  strokeStyle: string
  fillStyle: string
  lineWidth: number
  lineCap: string
  lineJoin: string
  beginPath(): void
  moveTo(x: number, y: number): void
  lineTo(x: number, y: number): void
  stroke(): void
  fill(): void
  arc(x: number, y: number, r: number, a0: number, a1: number): void
  clearRect(x: number, y: number, w: number, h: number): void
  createImageData(w: number, h: number): BrushImageDataLike
  putImageData(d: BrushImageDataLike, x: number, y: number): void
  getImageData(x: number, y: number, w: number, h: number): BrushImageDataLike
  drawImage(src: BrushCanvasLike, dx: number, dy: number, dw: number, dh: number): void
}

export interface BrushCanvasLike {
  width: number
  height: number
  /** 屏幕覆盖画布才有（Vue ensureBrushCanvas:3604 用 img.clientWidth/clientHeight） */
  clientWidth?: number
  clientHeight?: number
  getContext(kind: '2d'): BrushCtxLike | null
}

export interface BrushEnv {
  /** stage 上的覆盖 <canvas>（Vue brushCanvas:1609 ref；无元素返回 null 即静默降级） */
  screen(): BrushCanvasLike | null
  /** 离屏画布（Vue document.createElement('canvas'):3657/3687） */
  create(w: number, h: number): BrushCanvasLike
}

export type BrushStroke = { size: number; points: number[][] }

const BLUE = 'rgba(22,119,255,.5)'        // 3611/3626 涂抹预览色

export class BrushLayer {
  constructor(private host: CanvasHost, private env: BrushEnv | undefined) {}

  /** ensureBrushCanvas:3600-3609 —— backing store 跟显示尺寸对齐，不等比拉伸 */
  private ensure(): BrushCanvasLike | null {
    const c = this.env ? this.env.screen() : null
    if (!c) return null
    const nat = this.host.s.imgNatural || { w: 0, h: 0 }
    const w = c.clientWidth || nat.w
    const h = c.clientHeight || nat.h
    if (!w || !h) return null
    if (c.width !== w || c.height !== h) { c.width = w; c.height = h }
    return c
  }

  /** brushLine:3610-3622 —— a/b 为 [x%,y%]，size 为画布宽度百分比 */
  line(a: number[], b: number[], size: number): void {
    const c = this.ensure()
    if (!c) return
    const ctx = c.getContext('2d')
    if (!ctx) return
    ctx.strokeStyle = BLUE
    ctx.lineWidth = Math.max(1, (size / 100) * c.width)
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.beginPath()
    ctx.moveTo((a[0] / 100) * c.width, (a[1] / 100) * c.height)
    ctx.lineTo((b[0] / 100) * c.width, (b[1] / 100) * c.height)
    ctx.stroke()
  }

  /** brushDot:3623-3632 —— 单点按下先落一个圆点 */
  dot(p: number[], size: number): void {
    const c = this.ensure()
    if (!c) return
    const ctx = c.getContext('2d')
    if (!ctx) return
    ctx.fillStyle = BLUE
    ctx.beginPath()
    ctx.arc((p[0] / 100) * c.width, (p[1] / 100) * c.height,
      Math.max(1, (size / 100) * c.width / 2), 0, Math.PI * 2)
    ctx.fill()
  }

  /** redrawBrush:3655-3679 —— 全部区域按 rle 重铺到屏幕画布（1:1 之外的显示缩放由 drawImage 完成） */
  redraw(): void {
    const c = this.ensure()
    if (!c) return
    const ctx = c.getContext('2d')
    if (!ctx) return
    ctx.clearRect(0, 0, c.width, c.height)
    if (!this.env) return
    for (const r of this.host.s.brushRegions) {
      if (!r.w || !r.h) continue
      const mask = rleToMask(r.rle, r.w, r.h)
      const off = this.env.create(r.w, r.h)
      const octx = off.getContext('2d')
      if (!octx) continue
      const data = octx.createImageData(r.w, r.h)
      for (let i = 0; i < mask.length; i += 1) {
        if (!mask[i]) continue
        const o = i * 4
        data.data[o] = 22
        data.data[o + 1] = 119
        data.data[o + 2] = 255
        data.data[o + 3] = 125
      }
      octx.putImageData(data, 0, 0)
      ctx.drawImage(off, 0, 0, c.width, c.height)
    }
  }

  /** finishBrush:3680-3720 —— 笔迹按 naturalSize 白描离屏 → alpha>0 成 mask → RLE 入区域 */
  finish(nat: { w: number; h: number }, label: string): boolean {
    const strokes = this.host.s.brushDraft as BrushStroke[]
    if (!strokes.length) { this.host.toast?.('bad', '先画一笔'); return false }   // 3681
    if (!nat.w || !nat.h) { this.host.toast?.('bad', '图片还没加载完'); return false }  // 3683
    if (!this.env) return false
    const off = this.env.create(nat.w, nat.h)
    const ctx = off.getContext('2d')
    if (!ctx) return false
    ctx.strokeStyle = '#fff'           // 3688-3691 收笔走白描，mask 只读 alpha
    ctx.fillStyle = '#fff'
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    for (const path of strokes) {
      const lw = Math.max(1, (path.size / 100) * nat.w)
      ctx.lineWidth = lw
      if (path.points.length === 1) {
        const [x, y] = path.points[0]
        ctx.beginPath()
        ctx.arc((x / 100) * nat.w, (y / 100) * nat.h, lw / 2, 0, Math.PI * 2)
        ctx.fill()
      } else {
        ctx.beginPath()
        path.points.forEach((pt, i) => {
          const x = (pt[0] / 100) * nat.w
          const y = (pt[1] / 100) * nat.h
          if (i) ctx.lineTo(x, y); else ctx.moveTo(x, y)
        })
        ctx.stroke()
      }
    }
    const pixels = ctx.getImageData(0, 0, nat.w, nat.h).data
    const mask = new Uint8Array(nat.w * nat.h)
    for (let i = 0; i < mask.length; i += 1) mask[i] = pixels[i * 4 + 3] > 0 ? 1 : 0
    this.host.s.brushRegions.push({
      id: newShapeId('brush'),
      label,                                        // 3715：调用方给 boxLabel ‖ brushCtl 首项
      rle: maskToRle(mask), w: nat.w, h: nat.h,
    })
    this.host.s.brushDraft = []                     // 3718
    this.redraw()
    return true
  }

  /** 面板删行（Vue brushRegions.splice + redrawBrush:850） */
  removeRegion(i: number): void {
    this.host.s.brushRegions.splice(i, 1)
    this.redraw()
  }
}
