/**
 * SpanEditor.tsx —— 正文 span / 文档 HyperText span 列表（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :631-671。
 * 选区来源：Vue 在正文容器 mouseup 里 captureSpan:2648 算 pending；本壳把 pending
 * 放进 wsPanelUiStore（6-3 文档/正文渲染落地后由画布写同一个缝）。点类别按钮时：
 * 画布已写 pending → 直接用；没有则面板自行对 `[data-ws-text-body]` 容器抓一次选区
 * （与 Vue 同式 captureSpan），两处都没有才报「先在正文里选中一段文字」——不静默。
 */
import { Button, Tag } from 'antd'
import { usePanelCtx } from './PanelContext'
import { addHyperSpan, addTextSpan, captureHyperSpan, captureSpan, shapeExtraCount } from './panelOps'
import { BORDER, ELL, MONO, MUT } from './panelStyles'

const FORM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }
const ITEM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', padding: '4px 0', borderTop: BORDER }

export function SpanEditor() {
  const { model, ui, commit, patchUi, toast, openRegionLabels } = usePanelCtx()
  const ctl = model.ctl
  const spanCtl = ctl.textSpanCtl
  const hyperCtl = ctl.hypertextCtl
  if (!spanCtl && !hyperCtl) return null

  return (
    <>
      {spanCtl && (
        <div style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
          <div style={FORM}>
            <span style={{ fontSize: 12, color: '#666', marginRight: 4 }}>选中正文后点类别</span>
            {(spanCtl.options || []).map((o) => (
              <Button key={o.value} size="small" onClick={() => {
                const pend = ui.pendingSpan
                  || captureSpan(document.querySelector('[data-ws-text-body]'))
                const r = addTextSpan(model.s, pend, o.value)
                if (!r.ok) { toast('bad', r.msg); return }
                patchUi({ pendingSpan: null })
                commit(() => {})
                const sel = typeof window !== 'undefined' ? window.getSelection() : null
                sel?.removeAllRanges()
              }}>{o.value}</Button>
            ))}
          </div>
          {model.s.textSpans.map((s, i) => (
            <div key={s.id || i} style={ITEM} data-testid={`span-row-${i}`}>
              <Tag>{s.label}</Tag>
              {spanCtl.multiple && (
                <Button size="small" type="text" title="这段文字可以同时属于多个类别"
                        onClick={() => openRegionLabels('span', i, `第 ${i + 1} 段标注`)}>
                  多类别{shapeExtraCount(s) > 0 ? ` +${shapeExtraCount(s)}` : ''}
                </Button>
              )}
              <span style={{ ...MONO, ...MUT }}>[{s.start}-{s.end}]</span>
              <span style={ELL} title={s.text}>{s.text}</span>
              <span style={{ flex: 1 }} />
              <Button size="small" type="text" onClick={() => { model.s.textSpans.splice(i, 1); commit(() => {}) }}>删除</Button>
            </div>
          ))}
          {!model.s.textSpans.length && (
            <div style={{ fontSize: 12, color: '#999' }}>在正文里选中一段文字，再点一个类别。</div>
          )}
        </div>
      )}

      {hyperCtl && (
        <div style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
          <div style={FORM}>
            <span style={{ fontSize: 12, color: '#666', marginRight: 4 }}>选中文档文字后点类别</span>
            {(hyperCtl.options || []).map((o) => (
              <Button key={o.value} size="small" onClick={() => {
                const pend = ui.pendingHyper
                  || captureHyperSpan(document.querySelector('[data-ws-doc]'))
                const r = addHyperSpan(model.s, pend, o.value)
                if (!r.ok) { toast('bad', r.msg); return }
                patchUi({ pendingHyper: null })
                commit(() => {})
                const sel = typeof window !== 'undefined' ? window.getSelection() : null
                sel?.removeAllRanges()
              }}>{o.value}</Button>
            ))}
          </div>
          {model.s.hyperSpans.map((s, i) => (
            <div key={s.id || i} style={ITEM} data-testid={`hyper-row-${i}`}>
              <Tag>{s.label}</Tag>
              {hyperCtl.multiple && (
                <Button size="small" type="text" title="这段文档文字可以同时属于多个类别"
                        onClick={() => openRegionLabels('hyper', i, `第 ${i + 1} 段文档标注`)}>
                  多类别{shapeExtraCount(s) > 0 ? ` +${shapeExtraCount(s)}` : ''}
                </Button>
              )}
              <span style={ELL} title={s.text}>{s.text}</span>
              <span style={{ flex: 1 }} />
              <Button size="small" type="text" onClick={() => { model.s.hyperSpans.splice(i, 1); commit(() => {}) }}>删除</Button>
            </div>
          ))}
          {!model.s.hyperSpans.length && (
            <div style={{ fontSize: 12, color: '#999' }}>在左侧文档里选中文字，再点类别。</div>
          )}
        </div>
      )}
    </>
  )
}
