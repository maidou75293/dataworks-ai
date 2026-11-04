/**
 * LabelEditorModal.tsx —— 「编辑类别」弹窗（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :1264-1292；逻辑 openLabelEditor:1835 /
 * addLabelRow:1846 / saveLabels:1906-1940；请求走 wsPanelApi.lsSetLabels
 * （POST /api/label-studio/projects/{pid}/labels，Vue api.js:266 同端点）。
 * 保存成功后：project/schema 就地更新（adoptProject）→ applyLabelRename 把
 * 未保存标注/表单里的旧名一起改掉（:1850 注释的教训），顺序与 Vue 1930-1931 一致。
 * rows 由 WsPanel 持有（openLabelEditor:1840 打开时按控件现 options 重建）。
 */
import { useState } from 'react'
import { Button, Modal, Tag } from 'antd'
import type { SchemaCtl } from '../../../pages/mos/wsSchema'
import { adoptProject } from './panelBridge'
import { usePanelCtx } from './PanelContext'
import { applyLabelRename, buildLabelSave, newLabelRow, type LabelRow } from './panelOps'
import { lsSetLabels } from './wsPanelApi'

export type LabelEditorReq = { ctl: SchemaCtl; title: string; seq: number }

const ROW: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 6 }
const INPUT: React.CSSProperties = {
  flex: 1, border: '1px solid #d9d9d9', borderRadius: 4, padding: '2px 8px', fontSize: 13,
}

export function LabelEditorModal({ req, rows, setRows, onClose }: {
  req: LabelEditorReq
  rows: LabelRow[]
  setRows: (next: LabelRow[]) => void
  onClose: () => void
}) {
  const { model, ui, commit, patchForm, toast } = usePanelCtx()
  const [busy, setBusy] = useState(false)
  const control = String(req.ctl.type || '')

  const save = async () => {
    const built = buildLabelSave(rows)
    if (!built.ok) { toast('bad', built.msg); return }
    const { labels, rename } = built
    setBusy(true)
    try {
      const d = await lsSetLabels(model.pid, {
        from_name: req.ctl.from_name, control, labels, rename,
      })
      adoptProject(model, d.project || model.project || {})
      // applyLabelRename:1854 —— 只改这次编辑的那个控件对应的状态
      const form = { ...ui.form }
      applyLabelRename(rename, labels, control, req.ctl.from_name, model.s, model.ctl, form)
      patchForm(form)
      commit(() => {})
      const moved = (d.migrated?.annotations || 0) + (d.migrated?.predictions || 0)
      const failed = d.migrated?.failed || 0
      toast('ok', moved ? `类别已保存，同步更新了 ${moved} 条已有标注` : '类别已保存')
      if (failed) toast('bad', `有 ${failed} 条标注没改成功，刷新页面后可重试`)
      onClose()
    } catch (e) {
      toast('bad', (e as Error).message)   // saveLabels:1938
    } finally {
      setBusy(false)
    }
  }

  const addRow = () => setRows(rows.concat(newLabelRow()))

  return (
    <Modal open title="编辑类别" width={520} onCancel={onClose}
           footer={[
             <Button key="c" disabled={busy} onClick={onClose}>取消</Button>,
             <Button key="s" type="primary" disabled={busy} onClick={() => void save()}>
               {busy ? '保存中…' : '保存类别'}
             </Button>,
           ]}>
      <div style={{ fontSize: 12.5, color: '#666', lineHeight: 1.7, marginBottom: 10 }}>
        {req.title}的类别。清空某一行 = 删除这个类别，改名 = 换一个名字；
        保存时会同步改掉已有标注（含 AI 写入的）里的旧名字，不会留下对不上的标签。
      </div>
      {rows.map((row, i) => (
        <div key={row.uid} style={ROW} data-testid={`lab-row-${i}`}>
          <span style={{ width: 18, color: '#888', fontSize: 12 }}>{i + 1}</span>
          <input style={INPUT} value={row.value} placeholder={row.orig ? '' : '新类别名称'}
                 onChange={(e) => setRows(rows.map((r) => (r.uid === row.uid ? { ...r, value: e.target.value } : r)))}
                 onKeyUp={(e) => { if (e.key === 'Enter') addRow() }} />
          {row.orig && !row.value.trim() ? <Tag color="orange">删除</Tag>
            : row.orig && row.value.trim() !== row.orig ? <Tag color="blue">改名</Tag>
              : !row.orig && row.value.trim() ? <Tag color="green">新增</Tag> : null}
          <Button size="small" type="text" title="移除这一行"
                  onClick={() => setRows(rows.filter((r) => r.uid !== row.uid))}>×</Button>
        </div>
      ))}
      <Button size="small" onClick={addRow}>+ 添加类别</Button>
      <div style={{ fontSize: 12, color: '#999', marginTop: 10 }}>
        顺序就是面板里的显示顺序；同名类别会自动合并。
      </div>
    </Modal>
  )
}
