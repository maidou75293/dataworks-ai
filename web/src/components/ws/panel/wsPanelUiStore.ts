/**
 * wsPanelUiStore.ts —— 面板 ↔ 画布共享 UI 态缝（REQ-UNIFY-6-2 建，6-3/6-4 复用）。
 *
 * 为什么放这里：6-2 文件域只有 panel/，6-3 文件域只有 canvas/，而 wsLifecycle.ts 的
 * 契约面（buildResult/applyResult/resetAnnotation）不含纯 UI 草稿态；Vue 原件把这些
 * 放在顶层 ref（currentTime:1565 / imageTool:1592 / pendingSpan:1601 / pendingHyper:1603 /
 * activeBox:1563 / segLabel/segStart/segEnd/boxLabel/relFrom/relTo）。这里按 model 实例
 * 建一个可订阅的等价容器（WeakMap，切项目自动回收），面板与画布同一入口读写，
 * 避免两边各自私存造成「面板显示旧值」。
 */
import { useSyncExternalStore } from 'react'
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import type { PanelForm, PendingHyper, PendingSpan, ShapeKind } from './panelOps'
import { emptyForm } from './panelOps'

export type ActiveSel = { kind: ShapeKind; idx: number } | null

export type PanelUi = {
  /** 播放头秒数：6-3 接 <audio>/<video> onTime 后写；面板 segFromNow/当前帧 显示读它 */
  currentTime: number
  /** 画布当前选中的图形（面板行高亮/视频「当前帧记一帧」按钮显隐读） */
  active: ActiveSel
  /**
   * BUG-PARITY-6：鼠标停在图形列表的哪一行（Vue hoverBox:1561）——面板写、画布读，
   * 图上对应那个框画虚线细框+光晕做「这一行说的是哪个框」的轻提示。
   * 与 active 分开存：原件也是两套状态（选中 vs 悬停），hover 不该改选中态
   * （:5285 注释点名「不能做成和选中一样的粗框，否则『选中一个 + 悬停另一个』看起来像选中了两个」）。
   */
  hoverBox: number | null
  /** 图片工具（box/polygon/point/ellipse/brush）：决定 activeOptions；6-3 工具条写 */
  imageTool: string
  /** 正文/文档里刚选中、还没点类别的选区（Vue pendingSpan:1601 / pendingHyper:1603） */
  pendingSpan: PendingSpan | null
  pendingHyper: PendingHyper | null
  /** 表单态（segLabel/boxLabel/relFrom…，与画布共享：新框默认类别等） */
  form: PanelForm
}

export function initialUi(): PanelUi {
  return { currentTime: 0, active: null, hoverBox: null, imageTool: 'box',
           pendingSpan: null, pendingHyper: null, form: emptyForm() }
}

class UiStore {
  private v: PanelUi = initialUi()
  private listeners = new Set<() => void>()
  subscribe = (cb: () => void) => {
    this.listeners.add(cb)
    return () => { this.listeners.delete(cb) }
  }
  get = (): PanelUi => this.v
  /** 就地浅合并并广播（只在「用户动作/画布事件」里用，不在 render 里用） */
  patch = (p: Partial<PanelUi>) => {
    this.v = { ...this.v, ...p }
    this.listeners.forEach((cb) => cb())
  }
  patchForm = (p: Partial<PanelForm>) => {
    this.v = { ...this.v, form: { ...this.v.form, ...p } }
    this.listeners.forEach((cb) => cb())
  }
  /** 切任务=Vue resetAnnotation 清 pending/active/currentTime/表单（2275-2278/2292-2296/2327-2336） */
  resetForTask = () => {
    this.v = { ...initialUi(), imageTool: this.v.imageTool }
    this.listeners.forEach((cb) => cb())
  }
}

const stores = new WeakMap<object, UiStore>()

/** 每个 WorkbenchModel 实例一个 UI store（面板与画布取到的是同一个）。 */
export function panelUiStore(model: WorkbenchModel): UiStore {
  let st = stores.get(model)
  if (!st) { st = new UiStore(); stores.set(model, st) }
  return st
}

/** React 订阅面：组件里 const ui = usePanelUi(model)。 */
export function usePanelUi(model: WorkbenchModel): PanelUi {
  const st = panelUiStore(model)
  // 第三参 getServerSnapshot（BUG-PARITY-6 起画布也读这条缝）：unify6-3/6-4 的 node 侧探针用
  // renderToStaticMarkup 直接渲染 WsMediaStage，useSyncExternalStore 没有 server snapshot
  // 会在渲染期抛「Missing getServerSnapshot」。get() 返回缓存对象、引用稳定 → 客户端行为不变。
  return useSyncExternalStore(st.subscribe, st.get, st.get)
}
