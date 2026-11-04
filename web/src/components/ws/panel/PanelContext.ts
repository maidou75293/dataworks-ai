/**
 * PanelContext.ts —— 面板内部共享上下文（REQ-UNIFY-6-2）。
 * 根组件 WsPanel 注入；子控件（ExtraControls/SegmentEditor/ShapeList/…）经
 * usePanelCtx() 拿 model/UI 缝/两个弹窗的打开动作，避免逐层透传。
 */
import { createContext, useContext } from 'react'
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import type { SchemaCtl } from '../../../pages/mos/wsSchema'
import type { PanelEdit } from './panelBridge'
import type { PanelForm, ShapeKind } from './panelOps'
import type { PanelUi } from './wsPanelUiStore'

export type PanelCtx = {
  model: WorkbenchModel
  ui: PanelUi
  /** 区域态变更唯一通道：就地 mutate + notify + 草稿 timer（panelBridge.commitEdit） */
  commit: (edit: PanelEdit) => void
  patchUi: (p: Partial<PanelUi>) => void
  patchForm: (p: Partial<PanelForm>) => void
  toast: (level: 'ok' | 'bad', msg: string) => void
  /** 类别编辑器（openLabelEditor:1835） */
  openLabelEditor: (ctl: SchemaCtl | null, title: string) => void
  /** 区域多类别弹窗（openRegionLabels:2391） */
  openRegionLabels: (kind: ShapeKind, idx: number, title: string) => void
}

const Ctx = createContext<PanelCtx | null>(null)

export const PanelProvider = Ctx.Provider

export function usePanelCtx(): PanelCtx {
  const v = useContext(Ctx)
  if (!v) throw new Error('WsPanel 子控件必须在 PanelProvider 内渲染')
  return v
}
