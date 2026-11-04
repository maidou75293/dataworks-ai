/**
 * panelBridge.ts —— 面板改区域态的提交缝（REQ-UNIFY-6-2）。
 *
 * 背景：6-1 冻结的 WorkbenchModel 只暴露 setText/setChoice/setExtra 三个公共写口，
 * notify/markEdited 是私有（wsLifecycle.ts:139/209）。6-2 文件域只有 panel/，
 * 不能改 wsLifecycle（6-3 同期只在它尾部加 touch()，两泳道避免同文件竞争）。
 * 这里经类型断言复用私有 markEdited —— 语义等价 Vue：所有面板动作直接改 ref，
 * 由 watch(snapshot)（:2196）统一做 600ms 草稿落盘 + 重渲染；不新增第 2 套脏检查。
 * ⚠ 若 wsLifecycle 将来公开 touch()/markRegionEdit()，把本文件的 cast 换过去即可（单点）。
 */
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import { deriveControls } from '../../../pages/mos/wsSchema'
import type { WsState, WsControls } from '../../../pages/mos/wsSchema'

export type PanelEdit = (s: WsState, ctl: WsControls) => void

/** 就地执行 edit 并走 model 的「编辑后」通道（notify + 草稿 timer）。 */
export function commitEdit(model: WorkbenchModel, edit: PanelEdit): void {
  edit(model.s, model.ctl)
  ;(model as unknown as { markEdited(): void }).markEdited()
}

/**
 * 类别编辑器保存后就地换 schema/派生控件（Vue 1930 `project.value = d.project` 的
 * React 等价；project/schema/ctl 都是 model 公共字段，不算动私有面）。
 * 快照/基线不受影响：snapshotOf 按控件 from_name 取值，控件表换了键序不变。
 */
export function adoptProject(model: WorkbenchModel, project: Record<string, any>): void {
  model.project = project
  model.schema = (project && project.schema) || model.schema
  model.ctl = deriveControls(model.schema)
}
