/**
 * ExtraControls.tsx —— 评分/数值/日期/层级分类/成对比较（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :591-628；值一律写 s.extra（wsSchema 冻结槽），
 * buildResult 的 rating/number/datetime/taxonomy/pairwise 分支按 from_name 读取。
 * 卡面/评审里的「ranker」= 原件的 Pairwise（对比排序）控件，模板 :620-628。
 */
import { Button, Select } from 'antd'
import type { SchemaCtl } from '../../../pages/mos/wsSchema'
import { usePanelCtx } from './PanelContext'
import { numberInputToExtra, parseTaxPath, PAIRWISE_SIDES, ratingValue, taxOptionsOf, taxValueKey } from './panelOps'
import { BORDER, FIELD_LABEL } from './panelStyles'

const ROW: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 10, flexWrap: 'wrap' }

export function ExtraControls() {
  const { model } = usePanelCtx()
  const ctl = model.ctl
  const extra = model.s.extra

  const hasAny = ctl.ratingCtls.length + ctl.numberCtls.length + ctl.datetimeCtls.length
    + ctl.taxonomyCtls.length + ctl.pairwiseCtls.length > 0
  if (!hasAny) return null

  return (
    <>
      {/* rating（:591-596）：1..max 按钮，选中态 primary */}
      {ctl.ratingCtls.map((c: SchemaCtl) => (
        <div key={'r' + c.from_name} style={ROW} data-testid={'rating-' + c.from_name}>
          <span style={FIELD_LABEL}>{c.from_name}</span>
          {Array.from({ length: Number(c.max) || 0 }, (_, i) => i + 1).map((n) => (
            <Button key={n} size="small"
                    type={Number(extra[c.from_name]) === n ? 'primary' : 'default'}
                    onClick={() => model.setExtra(c.from_name, ratingValue(n))}>{n}</Button>
          ))}
        </div>
      ))}

      {/* number（:597-605）：min/max/step 提示；'' 清空=该控件不出现在 result */}
      {ctl.numberCtls.map((c: SchemaCtl) => (
        <div key={'n' + c.from_name} style={ROW}>
          <span style={FIELD_LABEL}>{c.from_name}</span>
          <input className="ant-input" style={numBox} type="number"
                 min={c.min as number | undefined} max={c.max as number | undefined}
                 step={c.step ?? 1}
                 value={extra[c.from_name] == null ? '' : String(extra[c.from_name])}
                 onChange={(e) => model.setExtra(c.from_name, numberInputToExtra(e.target.value))} />
          {c.min != null || c.max != null ? (
            <span style={{ fontSize: 12, color: '#999' }}>
              {c.min ?? ''} ~ {c.max ?? ''}
            </span>
          ) : null}
        </div>
      ))}

      {/* datetime（:606-610）：原生 datetime-local + step=1（带秒；isoToLocalInput:2442 同格式）。
          不用 antd DatePicker：它的 dayjs 链路会改秒/时区口径，金样对拍必须逐字符同 Vue。 */}
      {ctl.datetimeCtls.map((c: SchemaCtl) => (
        <div key={'d' + c.from_name} style={ROW}>
          <span style={FIELD_LABEL}>{c.from_name}</span>
          <input style={numBox} type="datetime-local" step={1}
                 value={String(extra[c.from_name] || '')}
                 onChange={(e) => model.setExtra(c.from_name, e.target.value)} />
        </div>
      ))}

      {/* taxonomy（:611-619）：树展平成叶子路径下拉；值=路径数组（buildResult:306-312） */}
      {ctl.taxonomyCtls.map((c: SchemaCtl) => {
        const opts = taxOptionsOf(c)
        return (
          <div key={'t' + c.from_name} style={ROW} data-testid={'taxonomy-' + c.from_name}>
            <span style={FIELD_LABEL}>{c.from_name}</span>
            <Select size="small" style={{ minWidth: 220 }}
                    value={taxValueKey(c, extra)}
                    onChange={(v: string) => model.setExtra(c.from_name, parseTaxPath(v))}
                    options={[
                      { value: '[]', label: '（未选择）' },
                      ...opts.map((t) => ({ value: JSON.stringify(t.path), label: t.label })),
                    ]} />
          </div>
        )
      })}

      {/* pairwise / ranker（:620-628）：左侧更好/右侧更好/持平 */}
      {ctl.pairwiseCtls.map((c: SchemaCtl) => (
        <div key={'p' + c.from_name} style={ROW} data-testid={'pairwise-' + c.from_name}>
          <span style={FIELD_LABEL}>
            {(((c as Record<string, any>).to_names || []) as string[]).join(' vs ') || c.from_name}
          </span>
          {PAIRWISE_SIDES.map((side) => (
            <Button key={side} size="small"
                    type={extra[c.from_name] === side ? 'primary' : 'default'}
                    onClick={() => model.setExtra(c.from_name, side)}>
              {side === 'left' ? '左侧更好' : side === 'right' ? '右侧更好' : '持平'}
            </Button>
          ))}
        </div>
      ))}
    </>
  )
}

const numBox: React.CSSProperties = {
  border: BORDER, borderRadius: 4, padding: '2px 8px', minWidth: 110, fontSize: 13,
}
