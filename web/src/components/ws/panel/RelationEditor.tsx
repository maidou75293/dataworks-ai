/**
 * RelationEditor.tsx —— 区域间关系（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :855-879；addRelation:2820（空端点/自连显式报错，
 * direction 固定 'right'，buildResult:426-434 按 relation 项输出，契约冻结）。
 * 区域下拉来源 regionsOf（:1949：框/区间/多边形/关键点/椭圆/文本 span，须已有 id）。
 */
import { Button, Select } from 'antd'
import { usePanelCtx } from './PanelContext'
import { addRelation, regionName, regionsOf } from './panelOps'
import { BORDER, MONO } from './panelStyles'

const FORM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }
const ITEM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', padding: '4px 0', borderTop: BORDER }

export function RelationEditor() {
  const { model, ui, commit, patchForm, toast } = usePanelCtx()
  const relCtl = model.ctl.relationsCtl
  if (!relCtl) return null

  const regions = regionsOf(model.s)
  const form = ui.form
  const opts = regions.map((r) => ({
    value: r.id,
    label: `${r.kind} · ${r.label} (#${r.id})`,
  }))
  // Vue 在 resetAnnotation:2335-2336 预填首两个区域；面板切任务后由 WsPanel 统一重算。
  // 这里兜底显示：表单里存的 id 已不在（区域被删）就当未选，下拉不显示失效 id。
  const val = (id: string) => (regions.some((r) => r.id === id) ? id : undefined)

  return (
    <div style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
      <div style={FORM}>
        <span style={{ fontSize: 12, color: '#666', marginRight: 4 }}>关系</span>
        <Select size="small" style={{ minWidth: 150 }} placeholder="起点"
                value={val(form.relFrom)}
                onChange={(v: string) => patchForm({ relFrom: v })}
                options={opts} />
        <span style={{ color: '#888' }}>→</span>
        <Select size="small" style={{ minWidth: 150 }} placeholder="终点"
                value={val(form.relTo)}
                onChange={(v: string) => patchForm({ relTo: v })}
                options={opts} />
        <Button size="small" type="primary" onClick={() => {
          const r = addRelation(model.s, form.relFrom, form.relTo)
          if (!r.ok) toast('bad', r.msg)
          else commit(() => {})
        }}>添加关系</Button>
      </div>
      {model.s.relations.map((r, i) => (
        <div key={r.id || i} style={ITEM} data-testid={`rel-row-${i}`}>
          <span style={MONO}>{regionName(regions, r.from_id)} → {regionName(regions, r.to_id)}</span>
          <span style={{ flex: 1 }} />
          <Button size="small" type="text"
                  onClick={() => { commit((st) => { st.relations.splice(i, 1) }) }}>删除</Button>
        </div>
      ))}
      {!regions.length && (
        <div style={{ fontSize: 12, color: '#999' }}>先画框 / 区间 / 文本，再添加关系。</div>
      )}
    </div>
  )
}
