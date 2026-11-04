/**
 * keys.ts —— 画布快捷键纯判定（REQ-UNIFY-6-3，onKey:4634-4714 的画布段）。
 *
 * 与 6-1 的分工（wsLifecycle.onKey 移植注:831「变焦/帧键/删区域归画布卡」）：
 *   壳层管 Ctrl+Enter/Ctrl+S/J/K/S//1-9 与 Escape 兜底；本层管
 *   Escape-最大化(4655)、f/F(4660)、图片 +-=0w(4666-4670)、视频 ,.<.>(4672-4677)、
 *   Delete(4706)、Escape-选中/专注(4709-4713)。
 * 判定顺序必须与 Vue 一致：blocked → Escape-最大化 → typing → 无修饰键画布键 → Delete → Escape 三级。
 */

export type CanvasKeyEvt = {
  key: string
  shiftKey?: boolean
  ctrlKey?: boolean
  metaKey?: boolean
  altKey?: boolean
}

export type CanvasKeyCtx = {
  /** schema.mode（'image' 才认缩放键，'video' 才认帧键） */
  mode: string
  /** 事件源是 input/textarea/select（Vue:4642） */
  typing?: boolean
  /** 弹窗/站内确认框打开（Vue:4637-4640，React 侧 = model.modalOpen/confirmOpen/inModal） */
  blocked?: boolean
  maximized?: boolean
  fullscreenActive?: boolean
  hasActiveShape?: boolean
  focusMode?: boolean
}

export type CanvasKeyAction =
  | 'escapeMax' | 'toggleMax' | 'toggleFocus'
  | 'zoomIn' | 'zoomOut' | 'resetZoom' | 'fitWidth'
  | 'frameBack' | 'frameFwd'
  | 'deleteShape' | 'escapeShape' | 'escapeFocus'

/** 返回 null = 本层不消费（落到壳层 model.onKey / 浏览器默认） */
export function canvasKeyAction(evt: CanvasKeyEvt, ctx: CanvasKeyCtx): CanvasKeyAction | null {
  if (ctx.blocked) return null                                        // 4637-4640
  // 4655：最大化退回（浏览器全屏由 Esc 原生处理；CSS 整屏模式要自己收）
  if (evt.key === 'Escape' && ctx.maximized && !ctx.fullscreenActive) return 'escapeMax'
  if (ctx.typing) return null                                         // 4658
  if (!evt.ctrlKey && !evt.metaKey && !evt.altKey) {                  // 4659
    if (evt.key === 'f' || evt.key === 'F') return evt.shiftKey ? 'toggleMax' : 'toggleFocus'  // 4660-4664
    if (ctx.mode === 'image') {                                       // 4666-4671
      if (evt.key === '+' || evt.key === '=') return 'zoomIn'
      if (evt.key === '-' || evt.key === '_') return 'zoomOut'
      if (evt.key === '0') return 'resetZoom'
      if (evt.key === 'w' || evt.key === 'W') return 'fitWidth'
    }
    if (ctx.mode === 'video' && (evt.key === ',' || evt.key === '<'
        || evt.key === '.' || evt.key === '>')) {                     // 4672-4677
      const back = evt.key === ',' || evt.key === '<'
      return back ? 'frameBack' : 'frameFwd'
    }
  }
  if (evt.key === 'Delete') return 'deleteShape'                      // 4706-4708
  if (evt.key === 'Escape') {                                         // 4709-4714
    if (ctx.hasActiveShape) return 'escapeShape'
    if (ctx.focusMode) return 'escapeFocus'
    return null
  }
  return null
}

/** 帧步进倍率（4675：按住 Shift 一次 10 帧） */
export function frameDelta(evt: CanvasKeyEvt, action: CanvasKeyAction): number {
  const mult = evt.shiftKey ? 10 : 1
  if (action === 'frameBack') return -mult
  if (action === 'frameFwd') return mult
  return 0
}
