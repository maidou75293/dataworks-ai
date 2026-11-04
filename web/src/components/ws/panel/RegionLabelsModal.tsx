/**
 * RegionLabelsModal.tsx —— 「区域类别」多类别勾选弹窗（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :1295-1317；逻辑 regionArr:2386 /
 * regionOptions:2401 / regionChecked:2414 / toggleRegionLabel:2418。
 * 行首下拉改「主类别」（wsSchema.setPrimaryLabel），附加类别在这里勾；
 * 勾掉主类别后下一个自动顶上（:2430）；最后一个类别不允许取消（静默拒绝=Vue 同式，
 * 这里给出禁用态而不是点击后什么都不说）。
 */
import { Button, Checkbox, Modal, Tag } from 'antd'
import { labelsOf } from '../../../pages/mos/wsSchema'
import { usePanelCtx } from './PanelContext'
import { regionArr, regionCtlFor, toggleRegionLabel } from './panelOps'

export type RegionLabelsReq = { kind: Parameters<typeof regionCtlFor>[0]; idx: number; title: string; seq: number }

export function RegionLabelsModal({ req, onClose }: { req: RegionLabelsReq; onClose: () => void }) {
  const { model, commit } = usePanelCtx()
  const arr = regionArr(model.s, req.kind)
  const target = arr ? arr[req.idx] : null
  if (!target) return null

  const ctl = regionCtlFor(req.kind, model.ctl)
  const options = (ctl && ctl.options) || []
  const checked = labelsOf(target)

  return (
    <Modal open title="区域类别" width={440} onCancel={onClose}
           footer={<Button type="primary" onClick={onClose}>完成</Button>}>
      <div style={{ fontSize: 12.5, color: '#666', lineHeight: 1.7, marginBottom: 10 }}>
        {req.title}：可以勾多个类别。第一项是「主类别」，行首下拉改的就是它；
        勾掉主类别后，剩下的第一个自动顶上。至少保留一个类别。
      </div>
      {options.map((o) => {
        const isChecked = checked.includes(o.value)
        const isLast = isChecked && checked.length <= 1
        return (
          <div key={o.value} style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 6 }}>
            <Checkbox checked={isChecked} disabled={isLast}
                      onChange={(e) => {
                        commit((s) => {
                          const a = regionArr(s, req.kind)
                          if (a) toggleRegionLabel(a[req.idx], o.value, e.target.checked)
                        })
                      }} />
            <span>{o.value}</span>
            {checked[0] === o.value ? <Tag color="green">主类别</Tag>
              : isChecked ? <Tag color="blue">附加</Tag> : null}
          </div>
        )
      })}
      <div style={{ fontSize: 12, color: '#999', marginTop: 10 }}>
        保存后写回 Label Studio：`labels` 数组按「主类别 + 附加类别」写出；
        同步到训练数据时多个类别写成 `类别A|类别B`。
      </div>
    </Modal>
  )
}
