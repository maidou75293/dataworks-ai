/**
 * WsMediaStage.tsx —— Workbench 画布 React 壳（REQ-UNIFY-6-3）。
 *
 * 渲染对照（只读原件 LabelStudioWorkbench.vue）：音频 266-276 / 视频 278-330 /
 *   图片 332-427（含缩放条 390-398、工具条 400-426）/ 文档 429-433 /
 *   时间序列 435-472 / 媒体缺失横幅 215-224 / 无框选控件提示 474-488。
 * 交互全部下沉到 engine.ts（框架无关），本文件只做：
 *   1) CanvasHost/EngineEnv 装配（DOM 引用注入，SSR 安全）；
 *   2) React 事件→引擎方法转发（PointerEvent 原生对象直接喂 PointerLike）；
 *   3) 任务切换→resetForTask + Bearer blob 取址（mediaSrc.ts）；
 *   4) 快捷键：capture 阶段先于壳层（MosWorkbench 的 window keydown 在 bubble 阶段，
 *      模型 onKey 里画布键「本壳不拦」wsLifecycle:831 —— 由本层消费后 stop，
 *      顺序等价 Vue 单函数 onKey:4634-4714 的画布段在前）。
 *
 * 边界（卡面排除项/泳道裁决，详见 docs/integration/REQ-UNIFY-6-3）：
 *   brush 画布/工具按钮=6-4 已接（覆盖 canvas + 滑杆/完成笔刷 + env.brush 注入）；
 *   拖拽历史=6-5（引擎 3447/3525 接缝已注释）；
 *   区间行编辑/多类别按钮=6-2；MediaPicker（导入弹窗）=6-6；
 *   「用现有类别加上框选」需改项目 schema → 回调由宿主注入（onAddBox/onOpenSettings），
 *   未注入时只给提示文案不给按钮，与「画布引擎不碰项目配置」一致。
 *   BUG-PARITY-5：宿主已接线（MosWorkbench→dialogs store，Vue :3878-3928 等价动作），
 *   并回搬第二形态「加上描述框」横幅（Vue :489-500）；在途钮 disabled+「添加中…」。
 * 修复（随 6-4 带出）：BUG-FW6-3 pl() 自递归（6-3 交付笔误，组件未挂载故未爆）——
 *   原实现每次指针事件都会 RangeError；改为 React.PointerEvent 结构化断言。
 *
 * BUG-PARITY-6 四处接缝（都在本文件与顶栏/面板之间）：
 *   ① 视图态上抛：maximized/focusMode 的真身在 engine.ts:704-735，挂载时把动作与读面
 *      注册进 model.canvasView，引擎每次 emit 回写镜像 → 顶钮与键位共用一份状态；
 *   ② 全屏目标改成本壳根（getFsRoot）：原件 .wb.wb-max 加在整工作台上、顶栏跟着一起铺满
 *      （Vue:5519-5524），本组件原先自带 S.rootMax（fixed + z-index 2000）会把顶栏盖掉
 *      =「退出最大化」鼠标点不到，与本卡要修的可发现性缺陷同源，故删除该层；
 *   ③ 切条预取相邻 ±1（Vue:4163-4177 的 new Image()）：本壳媒体要 Bearer 头，
 *      等价物是 resolver.resolve()（取回即进 objectURL 缓存，J/K 秒开）；
 *   ④ 区域行悬停：读面板写进 wsPanelUiStore 的 hoverBox，图上对应框画虚线+光晕
 *      （Vue .obox.hover:5287-5292）。
 */
import { useEffect, useMemo, useRef, useSyncExternalStore } from 'react'
import type * as React from 'react'
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import type { Region } from '../../../pages/mos/wsSchema'
import { CanvasEngine } from './engine'
import type { CanvasHost, EngineEnv, PointerLike, ScrollLike } from './engine'
import type { BrushCanvasLike } from './brush'

/** React 合成事件派发期 nativeEvent.currentTarget 必为元素；PointerLike 的
 *  getBoundingClientRect 面在引擎内已 typeof 守卫，这里只做边界断言 */
function pl(ev: React.PointerEvent): PointerLike {
  return ev as unknown as PointerLike
}
import { makeDomResolver } from './mediaSrc'
import { sanitizeHtml } from './sanitize'
// BUG-PARITY-2：文档正文容器要往面板 pendingHyper 缝里写选区（与 WsPanels 正文容器同式；
// panel/ 不 import canvas/，无环）
import { captureHyperSpan } from '../panel/panelOps'
// 两条 import 不合并：parity2 A6 逐字锁 `import { panelUiStore } from '../panel/wsPanelUiStore'`
// （别人锁的锚，改它不如守它）
import { panelUiStore } from '../panel/wsPanelUiStore'
import { usePanelUi } from '../panel/wsPanelUiStore'
import {
  baseName, clock, labelColorsOf, labelColorFor, num, tsPolylineOf, tsRectOf,
} from './geometry'

const BORDER = '1px solid #d9d9d9'

const S: Record<string, React.CSSProperties> = {
  root: { border: BORDER, borderRadius: 6, background: '#fff', minWidth: 0 },
  // BUG-PARITY-6：原先这里有 rootMax（fixed inset 0 / z-index 2000）画在**画布根**上，
  // 最大化时把顶栏整个盖掉——「退出最大化」钮鼠标点不到，只能靠 Shift+F/Esc。
  // 原件的 .wb-max 是加在整工作台根上的（Vue:5519-5524，顶栏一起铺满），
  // 现在那层样式挪到 MosWorkbench 的 data-ws="wb-root"，本组件不再自己叠固定层。
  oboxHover: { borderStyle: 'dashed', boxShadow: '0 0 0 2px rgba(22, 119, 255, .28)' },   // Vue:5287-5289
  oboxHoverSel: { borderStyle: 'dashed', boxShadow: '0 0 0 2px rgba(22, 119, 255, .55)' }, // Vue:5290-5292
  card: { padding: 10, display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 },
  scroll: { overflow: 'auto', maxHeight: '72vh', position: 'relative' },
  stage: { position: 'relative', display: 'inline-block', lineHeight: 0,
    touchAction: 'none', userSelect: 'none' },
  media: { width: '100%', maxHeight: '64vh', display: 'block', background: '#000' },
  img: { width: '100%', display: 'block', userSelect: 'none' },
  boxLayer: { position: 'absolute', inset: 0 },
  brushCanvas: { position: 'absolute', inset: 0, width: '100%', height: '100%',
    pointerEvents: 'none' },   // Vue .brush-canvas:5326
  btnPrimary: { borderColor: '#1677ff', background: '#1677ff', color: '#fff' },   // Vue btn primary
  shapeSvg: { position: 'absolute', inset: 0, width: '100%', height: '100%',
    pointerEvents: 'none', overflow: 'visible' },
  // BUG-PARITY-6：border 拆成 longhand。原先写简写 `border: 2px solid var(--c)`，
  // 而 hover 态叠加的是 longhand `borderStyle: 'dashed'`——React 的 inline style diff
  // 在 hover 结束时把消失的 borderStyle 清成 ''，会把简写设好的实线一起抹掉（实测框变成
  // 无边框 borderStyle:none）。键一致才谈得上覆盖：悬停加/撤必须与常态同一组 longhand。
  obox: { position: 'absolute', borderWidth: 2, borderStyle: 'solid',
    borderColor: 'var(--c, #1677ff)',
    background: 'color-mix(in srgb, var(--c, #1677ff) 12%, transparent)',
    minWidth: 0, minHeight: 0, touchAction: 'none' },
  oboxLabel: { position: 'absolute', top: -18, left: -2, fontSize: 11, color: '#fff',
    padding: '0 4px', borderRadius: 3, whiteSpace: 'nowrap', lineHeight: '16px' },
  handle: { position: 'absolute', width: 10, height: 10, background: '#fff',
    border: '2px solid var(--c, #1677ff)', borderRadius: 2, transform: 'translate(-50%,-50%)',
    touchAction: 'none' },
  ellipse: { position: 'absolute', border: '2px solid var(--c, #1677ff)',
    borderRadius: '50%', background: 'color-mix(in srgb, var(--c, #1677ff) 12%, transparent)',
    touchAction: 'none' },
  kp: { position: 'absolute', width: 10, height: 10, borderRadius: '50%',
    background: 'var(--c, #1677ff)', border: '2px solid #fff',
    transform: 'translate(-50%,-50%)', touchAction: 'none' },
  pvert: { position: 'absolute', width: 9, height: 9, background: '#fff',
    border: '2px solid var(--c, #1677ff)', borderRadius: 2,
    transform: 'translate(-50%,-50%)', touchAction: 'none' },
  draft: { opacity: 0.7, borderStyle: 'dashed' },
  meta: { display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap',
    fontSize: 12, color: '#555' },
  mono: { fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 },
  grow: { flex: 1 },
  btn: { border: BORDER, borderRadius: 5, background: '#fafafa', padding: '2px 8px',
    fontSize: 12, cursor: 'pointer' },
  btnOn: { borderColor: '#1677ff', color: '#1677ff', background: '#e6f4ff' },
  input: { border: BORDER, borderRadius: 5, padding: '2px 6px', fontSize: 12 },
  seg: { display: 'inline-flex', border: BORDER, borderRadius: 5, overflow: 'hidden' },
  segBtn: { border: 'none', borderRight: BORDER, background: '#fff', padding: '2px 10px',
    fontSize: 12, cursor: 'pointer' },
  tag: { border: '1px solid #d9d9d9', borderRadius: 3, padding: '0 6px',
    fontSize: 11, background: '#f5f5f5' },
  segItem: { display: 'flex', alignItems: 'center', gap: 8, border: BORDER,
    borderRadius: 5, padding: '4px 8px', fontSize: 12 },
  tsSvg: { width: '100%', height: 180, display: 'block', touchAction: 'none',
    border: BORDER, borderRadius: 5, background: '#fbfcfe' },
  doc: { border: BORDER, borderRadius: 5, padding: 12, maxHeight: '60vh',
    overflow: 'auto', background: '#fff', lineHeight: 1.7, fontSize: 14 },
  loading: { position: 'absolute', inset: 0, display: 'flex', alignItems: 'center',
    justifyContent: 'center', gap: 8, background: 'rgba(255,255,255,0.7)',
    fontSize: 12, color: '#555', lineHeight: 1.5 },
  missing: { display: 'flex', alignItems: 'center', gap: 10, border: '1px solid #ffccc7',
    background: '#fff2f0', borderRadius: 5, padding: '6px 10px', fontSize: 12 },
  repair: { display: 'flex', alignItems: 'flex-start', gap: 10, border: '1px solid #ffe58f',
    background: '#fffbe6', borderRadius: 5, padding: '8px 10px', fontSize: 12 },
  empty: { color: '#888', fontSize: 12, padding: '18px 0', textAlign: 'center' },
}

const HANDLE_POS: Record<string, React.CSSProperties> = {
  nw: { left: 0, top: 0 }, ne: { left: '100%', top: 0 },
  sw: { left: 0, top: '100%' }, se: { left: '100%', top: '100%' },
}
const HANDLE_CURSOR: Record<string, string> = {
  nw: 'nwse-resize', ne: 'nesw-resize', sw: 'nesw-resize', se: 'nwse-resize',
}

export interface WsMediaStageProps {
  model: WorkbenchModel
  toast?: (level: 'ok' | 'bad' | 'warn', text: string) => void
  /** 「用现有类别加上框选」/「项目设置」由宿主注入（改 schema 不归画布卡） */
  onAddBox?: () => void
  onOpenSettings?: () => void
  /** BUG-PARITY-5：「加上描述框」同理由宿主注入；fixing* = 在途位（Vue fixingBox/fixingDesc:1637-1638） */
  onAddDescription?: () => void
  fixingBox?: boolean
  fixingDesc?: boolean
  /**
   * BUG-PARITY-6：原生全屏的目标元素由宿主给（=整工作台根，Vue toggleMax:3134 对
   * wbRoot 请求全屏）。默认回退画布根本体——那会让全屏时顶栏被切在元素之外，
   * 「退出最大化」钮看不见也点不到，所以宿主必须传。
   */
  getFsRoot?: () => HTMLElement | null
}

export function WsMediaStage({ model, toast, onAddBox, onOpenSettings,
  onAddDescription, fixingBox, fixingDesc, getFsRoot }: WsMediaStageProps) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const stageRef = useRef<HTMLDivElement | null>(null)
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const mediaRef = useRef<HTMLVideoElement | HTMLAudioElement | null>(null)
  const brushRef = useRef<HTMLCanvasElement | null>(null)   // Vue brushCanvas:1609（347 inset 覆盖层）
  const resolver = useMemo(() => makeDomResolver(), [])
  // env 在 useMemo([model]) 里一次性装配，宿主每次渲染都递新闭包 → 用 ref 取最新版，
  // 免得第一帧的 getFsRoot 被永久钉进引擎（BUG-PARITY-6）
  const fsRootRef = useRef<WsMediaStageProps['getFsRoot']>(getFsRoot)
  fsRootRef.current = getFsRoot

  const engine = useMemo(() => {
    const host: CanvasHost = {
      get s() { return model.s },
      get ctl() { return model.ctl },
      get mode() { return model.mode },
      get selected() { return model.selected as Record<string, any> | null },
      touch() { model.touch() },
      toast,
      // 6-5 撤销接缝：拖拽事务（beginShapeDrag/endShapeDrag 两处钉死的调用点）
      beginDragHistory: () => model.beginDragHistory(),
      endDragHistory: () => model.endDragHistory(),
    }
    const canDom = typeof window !== 'undefined'
    const env: EngineEnv = {
      stageRect: () => (stageRef.current ? stageRef.current.getBoundingClientRect() : null),
      scroll: () => (scrollRef.current as unknown as ScrollLike) || null,
      media: () => mediaRef.current,
      win: canDom
        ? (window as unknown as EngineEnv['win'])
        : { innerWidth: 1200,
            addEventListener: () => {}, removeEventListener: () => {} },
      fullscreen: {
        request: () => {
          // BUG-PARITY-6：全屏对象=宿主给的工作台根（Vue toggleMax:3134-3136 对 wbRoot 请求，
          // 顶栏一起进全屏）；没宿主时才回退画布根。选项与原件逐字 navigationUI:'hide'。
          const el = canDom ? (fsRootRef.current?.() || rootRef.current) : null
          if (!el?.requestFullscreen) throw new Error('no fullscreen')
          el.requestFullscreen({ navigationUI: 'hide' })?.catch?.((err: unknown) =>
            console.warn('[workbench] 全屏被拒绝，改用整窗模式', err))
        },
        exit: () => { if (canDom && document.fullscreenElement) void document.exitFullscreen() },
        active: () => canDom && !!document.fullscreenElement,
      },
      // timeseries URL 与媒体同闸：通道地址要 Bearer 头 + /api/mos 前缀（mediaSrc 统一口径）
      fetchText: (u: string) => resolver.fetchText(u),
      // 画笔注入面（6-4）：真 <canvas> 的 getContext('2d') 天然满足 BrushCtxLike 结构子集
      brush: canDom ? {
        screen: () => brushRef.current as unknown as BrushCanvasLike | null,
        create: (w: number, h: number) => {
          const c = document.createElement('canvas')
          c.width = w; c.height = h
          return c as unknown as BrushCanvasLike
        },
      } : undefined,
      afterRender: canDom ? (fn: () => void) => { window.requestAnimationFrame(fn) } : undefined,
    }
    return new CanvasEngine(host, env)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model])

  // 6-5：撤销/重做与草稿恢复后，画布选中索引可能越界（Vue applyHistory:1728 clearActiveShape）
  useEffect(() => {
    model.onActiveInvalidate = () => engine.clearActiveShape()
    return () => { model.onActiveInvalidate = undefined }
  }, [engine, model])

  // BUG-PARITY-6：把画布视图态接到壳层——顶栏「最大化/专注」两钮在 MosWorkbench，
  // 状态与动作在引擎（engine.ts:704-735），Vue 原件两者同作用域（:60-71 + :3130-3155），
  // 拆开后就没人上抛 → 键在钮不在。注册口在 model（纯状态机不 import 本目录）。
  useEffect(() => {
    model.setCanvasView({
      toggleMax: () => engine.toggleMax(),
      toggleFocus: () => engine.toggleFocus(),
      maximized: () => engine.maximized,
      focusMode: () => engine.focusMode,
    })
    return () => model.setCanvasView(undefined)
  }, [engine, model])

  // 第三参 getServerSnapshot：SSR/静态渲染走这条（renderToStaticMarkup 冒烟用）
  useSyncExternalStore((cb) => engine.subscribe(cb), () => engine.version, () => engine.version)

  // BUG-PARITY-6：引擎 emit（键位走 handleKey 也会 emit）后把标志镜像回壳层；
  // model.syncCanvasView 自己判「真变了才 notify」，缩放这类高频 emit 不带着壳层重渲染。
  useEffect(() => { model.syncCanvasView() }, [engine, engine.version, model])   // eslint-disable-line react-hooks/exhaustive-deps

  // ---------- 事件接线 ----------
  useEffect(() => {
    if (typeof window === 'undefined') return
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null
      // BUG-PARITY-1：Vue:4642 typing 只认真输入元素——BUTTON 曾误入豁免集，
      // 点过队列项/↶↷/上下条后焦点停在按钮上，画布键全哑（老版照常生效）。
      const typing = !!t && (/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)
        || t.isContentEditable === true)
      // panelModalOpen：面板「编辑类别/区域多类别」弹窗同闸（Vue:4640 .modal-mask 全挡）
      const act = engine.handleKey(e, { typing,
        blocked: model.modalOpen || model.confirmOpen || model.panelModalOpen })
      if (act) { e.preventDefault(); e.stopPropagation() }
    }
    const onFsChange = () => engine.onFullscreenChange()
    const onResize = () => engine.onWindowResize()
    // capture：画布键先于壳层（等价 Vue onKey 单入口的段内顺序）
    window.addEventListener('keydown', onKey, true)
    document.addEventListener('fullscreenchange', onFsChange)
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      document.removeEventListener('fullscreenchange', onFsChange)
      window.removeEventListener('resize', onResize)
    }
  }, [engine, model])

  // Ctrl+滚轮缩放要 preventDefault：React onWheel 是 passive，必须原生挂
  useEffect(() => {
    const el = scrollRef.current
    if (!el || typeof el.addEventListener !== 'function') return
    const onWheel = (e: WheelEvent) => engine.onImageWheel(e)
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [engine, model.mode])

  useEffect(() => {
    const el = scrollRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    // BUG-PARITY-1 附带修复（验收 ± 项前提）：Vue 原件 RO 回调= rAF fitImage()（:4785-4790，
    // 不带 reset，zoom≠100 即冻结基准）；移植时误接 afterSizeChange()= fitImage(true)——
    // 放大撑高容器→RO 触发→zoom 被打回 100，±/0/w/滚轮缩放全部空转（点过才生效一帧就回滚）。
    const ro = new ResizeObserver(() => engine.fitImage())
    ro.observe(el)
    return () => ro.disconnect()
  }, [engine, model.mode])

  // 任务切换：画布复位 + 通道媒体经 Bearer fetch → objectURL（AuthInterceptor 只认头）
  const selId = model.selected ? String(model.selected.id) : ''
  const selRaw = model.selected ? String((model.selected as any).media_url || '') : ''
  useEffect(() => {
    engine.resetForTask()
    if (!selRaw) { engine.setSrc(''); return }
    let alive = true
    resolver.resolve(selRaw).then(
      (u) => { if (alive) engine.setSrc(u) },
      () => { if (alive) engine.onMediaError() },
    )
    return () => { alive = false }
  }, [selId, selRaw, engine, resolver])

  // BUG-PARITY-6：切条预取相邻 ±1 图片（Vue watch(selected.media_url):4162-4175 的 new Image()）。
  // 本壳媒体通道要 Bearer 头（mediaSrc 头注），裸 new Image() 只会拿到 401，等价物是
  // resolver.resolve()——取回即进 objectURL 缓存，按 J/K 切过去时上面那条 effect 直接命中缓存。
  // 单独一条 effect 而不并进任务切换 effect：那条带 engine.resetForTask()（清在画的草稿、
  // 清 tsPoints），列表晚到（loadTasks 第二段 / 加载更多）时重跑会打断用户手上的动作。
  // 失败静默：预取不该惊动用户，真切过去那一条自然会走缺图横幅。
  // 依赖数组在**渲染期**求值，而本组件允许挂在「只有画布所需字段」的半只 model 上
  // （unify6-3/6-4 的 node 侧 SSR 探针就是这么喂的，effect 不跑但表达式会跑），
  // 所以 tasks 一律判空取，别写 model.tasks.length 直接炸渲染。
  useEffect(() => {
    if (engine.mode !== 'image' || !selRaw) return
    const list = model.tasks || []
    const i = list.findIndex((t) => model.selected && t.id === model.selected.id)
    for (const j of [i + 1, i - 1]) {
      const t = list[j]
      const raw = t ? String(t.media_url || '') : ''
      if (raw && raw !== selRaw) void resolver.resolve(raw).catch(() => {})
    }
  }, [selId, selRaw, (model.tasks || []).length, engine, resolver, model])   // eslint-disable-line react-hooks/exhaustive-deps

  // BUG-PARITY-6：原先这里在卸载时 resolver.releaseAll()。但本组件挂在
  // `{model.selected && model.detail && …}`（MosWorkbench:409）里，**每切一条任务都会
  // 卸载重挂一次**（pick 先置 detail=null）——卸载即清空 = 预取与已看的图全丢，
  // 下一次切回还要重下（实测按 J 时三条图各重发一次 GET）。缓存已改为页面级共享 +
  // LRU 上限（mediaSrc.ts CACHE_MAX），内存由上限兜，不在这里整批撤销。
  // 卸载时不再 revoke：URL.revokeObjectURL 只在该对象 URL 真没人用时才该调。

  // 画笔区域重铺（Vue redrawBrush 各调用点 3048/3091/3124/3127/1733/2356 的统一等价物）：
  // 引擎每次 emit 都 bump version，effect 在 DOM 布局后重画，backing 尺寸与显示尺寸同步。
  // 有未完成草稿时绝不重铺 —— Vue 在 brushPaths 未收笔期间从不触发 redrawBrush
  // （起笔 emit 后若重铺会把刚落的预览点清掉，涂抹就"看不见"）；收笔 finishBrush 内部
  // 自带 redraw（brush.ts:174），effect 再铺一次结果一致。
  // 6-5 注意：model 侧恢复历史（applyHistory）不 bump 引擎 version，撤销接线时须调
  // engine.brushRedraw()（引擎接缝注释同此）。
  useEffect(() => {
    if (!(engine.s.brushDraft as unknown[]).length) engine.brushRedraw()
  }, [engine, engine.version])   // eslint-disable-line react-hooks/exhaustive-deps

  // ---------- 渲染 ----------
  const e = engine
  const ui = usePanelUi(model)   // BUG-PARITY-6：只读面板的 hoverBox（画布不写这条缝）
  const ctl = e.ctl
  const mode = e.mode
  const mediaName = baseName(selRaw)
  const labelColors = labelColorsOf(
    [ctl.rectCtl, ctl.polygonCtl, ctl.keypointCtl, ctl.ellipseCtl, ctl.vrectCtl]
      .filter(Boolean) as { options?: { value?: string; background?: string }[] }[])
  const colorOf = (label: string) => labelColorFor(label, labelColors, e.shapeOptions())
  const activeBox = e.active.box
  // BUG-PARITY-6：面板列表行悬停 → 图上对应框轻提示（Vue hoverBox:1561 + CSS :5287-5292）。
  // 已选中的那个不吃 hover（原件 :not(.sel)：实线粗框=选中，虚线光晕=悬停，两套要分得开）；
  // 有选中时光晕加亮一档（Vue .has-sel 变体 alpha .28→.55，否则暗掉的框堆里认不出悬停的是哪个）。
  const hoverIdx = ui.hoverBox
  const hoverStyle = (i: number): React.CSSProperties | null =>
    hoverIdx === i && activeBox !== i
      ? (activeBox != null ? S.oboxHoverSel : S.oboxHover)
      : null
  const sel = (b: Region): React.CSSProperties => ({ '--c': colorOf(String(b.label || '')) } as
    React.CSSProperties)

  const missingBanner = e.mediaMissing ? (
    <div style={S.missing} role="alert">
      <div>
        <b>媒体文件已不在媒体库</b>
        <span style={{ color: '#888', marginLeft: 6 }}>
          这条任务引用的 <span style={S.mono}>{mediaName || '媒体文件'}</span>
          可能已被删除，或路径已失效；无法在这一条上标注。
        </span>
      </div>
      <div style={S.grow} />
      <button style={S.btn} onClick={() => (model as any).skip?.()}>跳过这条</button>
      <button style={S.btn} onClick={() => (model as any).back?.()}>返回项目</button>
    </div>
  ) : null

  const shapesLayer = (
    <div style={S.boxLayer}>
      <svg style={S.shapeSvg} viewBox="0 0 100 100" preserveAspectRatio="none">
        {e.s.polygons.map((p, i) => (
          <polygon key={p.id || i}
            points={(p.points || []).map((pt) => pt.join(',')).join(' ')}
            fill={colorOf(p.label) + '22'} stroke={colorOf(p.label)} strokeWidth={0.6}
            vectorEffect="non-scaling-stroke"
            style={{ pointerEvents: 'auto', cursor: 'move',
              outline: e.active.polygon === i ? '2px solid #1677ff' : 'none' }}
            onPointerDown={(ev) => { ev.stopPropagation(); e.beginShapeDrag(pl(ev), 'polygon', i) }} />
        ))}
        {e.polyDraft.length > 0 && (
          <polyline points={e.polyDraft.map((pt) => pt.join(',')).join(' ')}
            fill="none" stroke="#1677ff" strokeWidth={0.6} strokeDasharray="2 1.5"
            vectorEffect="non-scaling-stroke" />
        )}
      </svg>
      {e.active.polygon != null && e.s.polygons[e.active.polygon] ? (
        <div>
          {(e.s.polygons[e.active.polygon].points || []).map((pt, j) => (
            <i key={j} style={{ ...S.pvert, left: pt[0] + '%', top: pt[1] + '%',
              '--c': colorOf(e.s.polygons[e.active.polygon!].label) } as React.CSSProperties}
              onPointerDown={(ev) => {
                ev.stopPropagation()
                e.beginShapeDrag(pl(ev), 'polygon', e.active.polygon!, 'vertex', '', j)
              }} />
          ))}
        </div>
      ) : null}
      {e.s.ellipses.map((el, i) => (
        <div key={el.id || i}
          style={{ ...S.ellipse, ...sel(el), left: num(el.x) + '%', top: num(el.y) + '%',
            width: num(el.w) + '%', height: num(el.h) + '%',
            outline: e.active.ellipse === i ? '2px solid #1677ff' : 'none' }}
          onPointerDown={(ev) => { ev.stopPropagation(); e.beginShapeDrag(pl(ev), 'ellipse', i) }}>
          <span style={{ ...S.oboxLabel, background: colorOf(el.label) }}>{el.label}</span>
          {e.active.ellipse === i && (e.CORNERS as readonly string[]).map((h) => (
            <i key={h} style={{ ...S.handle, ...HANDLE_POS[h], cursor: HANDLE_CURSOR[h],
              '--c': colorOf(el.label) } as React.CSSProperties}
              onPointerDown={(ev) => {
                ev.stopPropagation()
                e.beginShapeDrag(pl(ev), 'ellipse', i, 'resize', h)
              }} />
          ))}
        </div>
      ))}
      {e.s.keypoints.map((k, i) => (
        <div key={k.id || i} title={k.label}
          style={{ ...S.kp, ...sel(k), left: num(k.x) + '%', top: num(k.y) + '%',
            outline: e.active.keypoint === i ? '2px solid #1677ff' : 'none' }}
          onPointerDown={(ev) => { ev.stopPropagation(); e.beginShapeDrag(pl(ev), 'keypoint', i) }} />
      ))}
    </div>
  )

  const boxesLayer = (video: boolean) => (
    <div style={{ ...S.boxLayer, pointerEvents: video && e.drawMode ? 'none' : 'auto' }}>
      {e.s.boxes.map((b, i) => (
        <div key={b.id || 'b' + i} data-ws={'box-' + i} data-ws-hover={hoverIdx === i ? '1' : undefined}
          style={{ ...S.obox, ...sel(b), ...e.boxStyle(b), cursor: 'move',
            ...hoverStyle(i),
            outline: activeBox === i ? '2px solid #1677ff' : 'none' }}
          onPointerDown={(ev) => { ev.stopPropagation(); e.onBoxDown(pl(ev), i) }}>
          <span style={{ ...S.oboxLabel, background: colorOf(b.label) }}>
            {i + 1} · {b.label}
          </span>
          {activeBox === i && !e.drawMode && (e.CORNERS as readonly string[]).map((h) => (
            <i key={h} style={{ ...S.handle, ...HANDLE_POS[h], cursor: HANDLE_CURSOR[h],
              '--c': colorOf(b.label) } as React.CSSProperties}
              onPointerDown={(ev) => {
                ev.stopPropagation()
                e.beginShapeDrag(pl(ev), 'box', i, 'resize', h)
              }} />
          ))}
        </div>
      ))}
      {e.draw.active && (
        <div className="draft" style={{ ...S.obox, ...S.draft, ...e.boxStyle(e.draw as unknown as Region),
          '--c': colorOf(e.boxLabel) } as React.CSSProperties} />
      )}
    </div>
  )

  const cursorForTool: Record<string, string> = {
    box: 'crosshair', polygon: 'crosshair', point: 'crosshair', ellipse: 'crosshair',
    brush: 'crosshair',
  }

  let body: React.ReactNode = null
  if (mode === 'audio') {
    body = (
      <section style={S.card} data-ws="audio">
        <audio ref={mediaRef as React.RefObject<HTMLAudioElement>}
          src={e.displaySrc || undefined} controls preload="metadata"
          onTimeUpdate={(ev) => e.onTime({ currentTime: ev.currentTarget.currentTime,
            duration: ev.currentTarget.duration })}
          onSeeked={(ev) => e.onTime({ currentTime: ev.currentTarget.currentTime })}
          onLoadedMetadata={(ev) => {
            e.onMeta({ duration: ev.currentTarget.duration, playbackRate: 0 })
            if (mediaRef.current) mediaRef.current.playbackRate = e.playbackRate
          }}
          onError={() => e.onMediaError()}
          onCanPlay={() => { e.mediaMissing = false; e.emit() }} />
        <div style={S.meta}>
          <span style={S.mono}>{mediaName}</span>
          <div style={S.grow} />
          <span style={S.mono}>{clock(e.currentTime)} / {clock(e.duration)}</span>
        </div>
      </section>
    )
  } else if (mode === 'video') {
    body = (
      <section style={S.card} data-ws="video">
        <div ref={stageRef} className={'video-stage' + (e.drawMode ? ' drawing' : '')}
          style={{ ...S.stage, width: '100%', cursor: e.drawMode ? 'crosshair' : 'default' }}
          onPointerDown={(ev) => e.startDraw(pl(ev))}>
          <video ref={mediaRef as React.RefObject<HTMLVideoElement>}
            src={e.displaySrc || undefined} controls preload="metadata"
            style={S.media}
            onTimeUpdate={(ev) => e.onTime({ currentTime: ev.currentTarget.currentTime,
              duration: ev.currentTarget.duration })}
            onSeeked={(ev) => e.onTime({ currentTime: ev.currentTarget.currentTime })}
            onLoadedMetadata={(ev) => {
              e.onMeta({ duration: ev.currentTarget.duration, playbackRate: 0 })
              if (mediaRef.current) mediaRef.current.playbackRate = e.playbackRate
              e.mediaMissing = false
            }}
            onError={() => e.onMediaError()} />
          {boxesLayer(true)}
        </div>
        <div style={S.meta}>
          <span style={S.mono}>{mediaName}</span>
          <span style={S.mono}>{clock(e.currentTime)} / {clock(e.duration)}</span>
          <span style={S.mono}>帧 {e.currentFrame()} / {e.frameCount()} · {e.fps()}fps</span>
          <div style={S.grow} />
          <span style={{ display: 'inline-flex', gap: 4, alignItems: 'center' }}>
            <button style={S.btn} title="上一帧（,）" onClick={() => e.stepFrame(-1)}>−1 帧</button>
            <button style={S.btn} title="下一帧（.）" onClick={() => e.stepFrame(1)}>+1 帧</button>
            <select style={S.input} title="播放速度" value={e.playbackRate}
              onChange={(ev) => e.setPlaybackRate(Number(ev.currentTarget.value))}>
              {[0.5, 1, 1.5, 2].map((r) => <option key={r} value={r}>{r}×</option>)}
            </select>
            {ctl.vrectCtl && activeBox != null && (
              <>
                <button style={S.btn} disabled={!e.activeKeyframes().length}
                  title="跳到选中框的上一个关键帧" onClick={() => e.jumpKeyframe(-1)}>◀ 关键帧</button>
                <button style={S.btn} disabled={!e.activeKeyframes().length}
                  title="跳到选中框的下一个关键帧" onClick={() => e.jumpKeyframe(1)}>关键帧 ▶</button>
                {e.onKeyframe() && <span style={S.tag}>当前帧已有关键帧</span>}
              </>
            )}
          </span>
          {ctl.boxCtl && (
            <button style={{ ...S.btn, ...(e.drawMode ? S.btnOn : {}) }}
              onClick={() => { e.drawMode = !e.drawMode; e.emit() }}>
              {e.drawMode ? '退出画框' : '在画面上画框'}
            </button>
          )}
        </div>
        <div style={{ fontSize: 11.5, color: '#888' }}>
          暂停后按逗号 / 句号逐帧（按住 Shift 一次 10 帧）；选中已有关键帧的框后，可跳到它的上一 / 下一关键帧。
        </div>
      </section>
    )
  } else if (mode === 'image') {
    body = (
      <section style={S.card} data-ws="image">
        <div>
          <div ref={scrollRef}
            style={{ ...S.scroll, overflow: e.imageZoom > 100 ? 'auto' : 'hidden' }}>
            <div ref={stageRef}
              style={{ ...S.stage, ...e.imageStageStyle(),
                cursor: ctl.hasShapeCtl ? (cursorForTool[e.imageTool] || 'default') : 'not-allowed' }}
              onPointerDown={(ev) => e.startDraw(pl(ev))}>
              <img src={e.displaySrc || undefined} style={S.img} decoding="async"
                fetchPriority="high" draggable={false} alt=""
                onLoad={(ev) => e.onImgLoad(ev.currentTarget.naturalWidth, ev.currentTarget.naturalHeight)}
                onError={() => e.onMediaError()} />
              {e.imgLoading && (
                <div style={S.loading}><span>图片加载中…</span></div>
              )}
              {/* 画笔覆盖层（Vue:347）：笔迹/RLE 区域都画在这张 canvas 上，不吃指针事件 */}
              <canvas ref={brushRef} data-ws="brush-canvas" style={S.brushCanvas} />
              {ctl.hasShapeCtl && shapesLayer}
              {boxesLayer(false)}
            </div>
          </div>
          {ctl.rectCtl && (
            <div style={{ ...S.meta, marginTop: 6, gap: 4 }} data-ws="zoom-bar">
              <button style={S.btn} title="适应宽度（W）" onClick={() => e.fitWidth()}>宽度</button>
              <button style={S.btn} disabled={e.imageZoom <= 25} title="缩小（-）"
                aria-label="缩小" onClick={() => e.zoomBy(-25)}>−</button>
              <button style={{ ...S.btn, ...S.mono }} title="恢复适应窗口（0）"
                onClick={() => e.resetZoom()}>{e.imageZoom}%</button>
              <button style={S.btn} disabled={e.imageZoom >= 400} title="放大（+）"
                aria-label="放大" onClick={() => e.zoomBy(25)}>＋</button>
            </div>
          )}
        </div>
        <div style={S.meta}>
          <span style={S.mono}>{mediaName}</span>
          {e.imageTools().length > 1 && (
            <span style={S.seg} data-ws="tool-seg">
              {e.imageTools().map((t) => (
                <button key={t.key} style={{ ...S.segBtn,
                  ...(e.imageTool === t.key ? S.btnOn : {}) }}
                onClick={() => e.setTool(t.key)}>{t.label}</button>
              ))}
            </span>
          )}
          {e.imageTool === 'polygon' && ctl.polygonCtl && (
            <button style={S.btn} disabled={e.polyDraft.length < 3}
              onClick={() => e.finishPolygon()}>
              完成多边形（{e.polyDraft.length} 点）
            </button>
          )}
          {/* 笔刷工具条（Vue 410-414）：滑杆 1-15%，「完成笔刷」只在有笔迹时可点 */}
          {e.imageTool === 'brush' && ctl.brushCtl && (
            <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }} data-ws="brush-bar">
              <span style={{ color: '#888' }}>笔刷 {e.brushSize}%</span>
              <input type="range" min={1} max={15} value={e.brushSize}
                style={{ width: 90 }}
                onChange={(ev) => e.setBrushSize(Number(ev.currentTarget.value))} />
              <button style={{ ...S.btn, ...S.btnPrimary }}
                disabled={!(e.s.brushDraft as unknown[]).length}
                onClick={() => e.finishBrush()}>完成笔刷</button>
            </span>
          )}
          <div style={S.grow} />
          <span style={{ fontSize: 12, color: '#888' }}
            title="拖拽画框；选中后可拖动/拖角缩放，Delete 删除；Ctrl+滚轮或 +/− 缩放">
            {e.imageTool === 'polygon' ? '依次点击顶点，完成后点「完成多边形」；选中可拖顶点或整体移动'
              : e.imageTool === 'point' ? '点击画面添加关键点；选中后可拖动'
                : e.imageTool === 'ellipse' ? '拖拽画椭圆；选中后可拖动/拖角缩放'
                  : e.imageTool === 'brush' ? '按住拖拽涂抹，完成后点「完成笔刷」'   // Vue:422
                    : '拖拽画框；选中后可拖动/拖角缩放，Delete 删除'}
            {' · Ctrl+滚轮或 +/− 缩放 · 触屏按住拖动'}
          </span>
        </div>
      </section>
    )
  } else if (mode === 'doc') {
    const f = ((ctl.schema as any).hypertext_fields || [])[0]
    const raw = f && model.selected ? String(((model.selected as any).data || {})[f.field] || '') : ''
    const safe = sanitizeHtml(raw)
    body = (
      <section style={S.card} data-ws="doc">
        {safe
          // data-ws-doc：6-2 面板 captureHyperSpan 取选区 + 6-3/6-4 画布高亮同款钩子（主控合入清单 2）
          // BUG-PARITY-2：Vue:432 容器 @mouseup/@keyup="captureHyperSpan"（capture:2697 对塌陷/
          // 越界/空白显式置 null 清过期 pending）。tabIndex=-1 理由同正文容器（kb-probe3 实测：
          // 焦点停 BODY 时 keyup 不进容器，纯键盘扩展定不住）。
          ? <div data-ws-doc tabIndex={-1} style={S.doc} className="doc-html"
              onMouseUp={(ev) => panelUiStore(model).patch({ pendingHyper: captureHyperSpan(ev.currentTarget) })}
              onKeyUp={(ev) => panelUiStore(model).patch({ pendingHyper: captureHyperSpan(ev.currentTarget) })}
              dangerouslySetInnerHTML={{ __html: safe }} />
          : <div style={S.empty}>没有可显示的安全内容{raw ? '（原文经清洗后为空）' : ''}</div>}
      </section>
    )
  } else if (mode === 'timeseries') {
    const dom = e.tsDomain()
    const tsCtl = ctl.timeseriesCtl
    body = (
      <section style={{ ...S.card }} data-ws="timeseries">
        {e.tsPoints.length ? (
          <>
            <svg style={S.tsSvg} viewBox="0 0 1000 320" preserveAspectRatio="none"
              onPointerDown={(ev) => e.tsDown(pl(ev))}
              onPointerMove={(ev) => e.tsMove(pl(ev))}
              onPointerUp={(ev) => e.tsUp(pl(ev))}
              onPointerCancel={(ev) => e.tsUp(pl(ev))}
              onPointerLeave={(ev) => e.tsUp(pl(ev))}>
              <polyline points={tsPolylineOf(e.tsPoints, dom)} fill="none"
                stroke="#1677ff" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
              {e.s.tsSegments.map((s) => {
                const r = tsRectOf(s, dom)
                return <rect key={s.id} x={r.left} y={10} width={r.width} height={300}
                  fill={colorOf(s.label) + '33'} stroke={colorOf(s.label)} />
              })}
              {e.tsDrag.active && (
                <rect x={Math.min(e.tsDrag.x0, e.tsDrag.x1) * 1000} y={10}
                  width={Math.abs(e.tsDrag.x1 - e.tsDrag.x0) * 1000} height={300}
                  fill="#1677ff22" stroke="#1677ff" strokeDasharray="6 4" />
              )}
            </svg>
            <div style={S.meta}>
              <span style={S.mono}>{((ctl.schema as any).timeseries_fields || [])[0]?.field || ''}
                {' · '}{e.tsPoints.length} 点</span>
              {tsCtl && (
                <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
                  <select style={S.input} value={e.tsLabel}
                    onChange={(ev) => { e.tsLabel = ev.currentTarget.value; e.emit() }}>
                    {(tsCtl.options || []).map((o: any) => (
                      <option key={o.value} value={o.value}>{o.value}</option>
                    ))}
                  </select>
                  <span style={{ color: '#888' }}>在图上拖拽划区间</span>
                </span>
              )}
            </div>
            {e.s.tsSegments.map((s, i) => (
              <div key={s.id} style={S.segItem}>
                <span style={S.tag}>{s.label}</span>
                <span style={S.mono}>{s.start} → {s.end}</span>
                <div style={S.grow} />
                <button style={S.btn} onClick={() => e.removeTsSegment(i)}>删除</button>
              </div>
            ))}
          </>
        ) : (
          <div style={S.empty}>{e.tsError || '没有时间序列数据'}</div>
        )}
      </section>
    )
  }

  const repair = mode === 'image' && !ctl.hasShapeCtl ? (
    <div style={S.repair} data-ws="repair-no-box">
      <span style={{ fontWeight: 700, color: '#ad6800' }}>!</span>
      <div>
        <b>这个图片项目不能画框</b>
        <div style={{ color: '#888', marginTop: 2 }}>
          画布配置里只有分类 / 描述，没有 RectangleLabels 之类的框选控件，
          所以在图上拖拽什么也不会发生。
          {onAddBox ? '可以一键用现有类别补上框选（可逆），或到项目设置里自己加。' : ''}
        </div>
      </div>
      <div style={S.grow} />
      {onAddBox && (
        <button style={S.btn} disabled={fixingBox} onClick={onAddBox}>
          {fixingBox ? '添加中…' : '用现有类别加上框选'}
        </button>
      )}
      {onOpenSettings && <button style={S.btn} onClick={onOpenSettings}>项目设置</button>}
    </div>
  ) : null

  // BUG-PARITY-5：Vue :489-500 第二形态——旧「只框选」模板没有文字输入框，补一个描述框
  const repairDesc = mode === 'image' && !ctl.textareaCtl ? (
    <div style={S.repair} data-ws="repair-no-desc">
      <span style={{ fontWeight: 700, color: '#ad6800' }}>!</span>
      <div>
        <b>这个图片项目没有文字输入框</b>
        <div style={{ color: '#888', marginTop: 2 }}>
          它用的是旧版「只框选」模板。加上描述框后，可以一边框选一边写描述；已有框选标注不受影响。
        </div>
      </div>
      {onAddDescription && (
        <button style={S.btn} disabled={fixingDesc} onClick={onAddDescription}>
          {fixingDesc ? '添加中…' : '加上描述框'}
        </button>
      )}
    </div>
  ) : null

  if (!model.selected && mode !== 'text') return null

  return (
    // BUG-PARITY-6：最大化不再在本组件叠固定层（旧 S.rootMax 会盖掉顶栏，
    // 「退出最大化」鼠标点不到）；整壳根样式与全屏目标都在 MosWorkbench。
    <div ref={rootRef} data-ws="media-stage" data-mode={mode} style={S.root}>
      {missingBanner}
      {body}
      {repair}
      {repairDesc}
    </div>
  )
}

export default WsMediaStage
