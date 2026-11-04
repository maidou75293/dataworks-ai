/**
 * SegmentEditor.tsx —— 时间区间/时间段编辑（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :674-712（segCtl 段）；
 * 播放头/当前帧从 wsPanelUiStore 读（6-3 画布接媒体 onTime 后写同一个缝）。
 * per_region 文本（音频分段+说话人，perRegionText:1417）：区间行内写 seg.text，
 * buildResult:364-370 与区间共用同一 id 输出（6-1 契约冻结口径）。
 */
import { Button, Select, Tag } from 'antd'
import { usePanelCtx } from './PanelContext'
import { addSegment, clock, fixSegment, segUnitOf, shapeExtraCount } from './panelOps'
import { BORDER, MUT } from './panelStyles'

const ROW: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }
const ITEM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', padding: '4px 0', borderTop: BORDER }
const NUM: React.CSSProperties = { width: 84, border: BORDER, borderRadius: 4, padding: '1px 6px', fontSize: 13 }

export function SegmentEditor() {
  const { model, ui, commit, patchForm, toast, openRegionLabels } = usePanelCtx()
  const segCtl = model.ctl.segCtl
  if (!segCtl) return null

  const unit = segUnitOf(model.ctl)
  const isSec = unit === 's'
  const step = isSec ? 0.1 : 1
  const form = ui.form
  const fps = model.s.frameRate || model.ctl.schema.frame_rate || 25
  const currentFrame = Math.round((Number(ui.currentTime) || 0) * fps)
  const segs = model.s.segments

  return (
    <div style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
      {/* 表单行（:675-687） */}
      <div style={ROW}>
        <Select size="small" style={{ minWidth: 120 }} value={form.segLabel || undefined}
                placeholder="标签"
                onChange={(v: string) => patchForm({ segLabel: v })}
                options={(segCtl.options || []).map((o) => ({ value: o.value, label: o.value }))} />
        <input style={NUM} type="number" step={step}
               value={form.segStart == null || Number.isNaN(Number(form.segStart)) ? '' : String(form.segStart)}
               onChange={(e) => patchForm({ segStart: Number(e.target.value) })} />
        <span style={MUT}>—</span>
        <input style={NUM} type="number" step={step}
               value={form.segEnd == null || Number.isNaN(Number(form.segEnd)) ? '' : String(form.segEnd)}
               onChange={(e) => patchForm({ segEnd: Number(e.target.value) })} />
        <span style={MUT}>{isSec ? '秒' : '帧'}</span>
        {/* segFromNow:3206 —— 用当前时间/帧（画布没接媒体前 currentTime=0，行为同 Vue 未播放态） */}
        <Button size="small" onClick={() => {
          const now = isSec ? Number((Number(ui.currentTime) || 0).toFixed(1)) : currentFrame
          patchForm({ segStart: now, segEnd: now + (isSec ? 1 : Math.round(fps)) })
        }}>用当前{isSec ? '时间' : '帧'}</Button>
        {/* addSegment:3211 —— 「先选一个标签 / 终点必须大于起点」显式报错 */}
        <Button size="small" type="primary" onClick={() => {
          const r = addSegment(model.s, form)
          if (!r.ok) { toast('bad', r.msg); return }
          commit(() => {})          // addSegment 已就地入列，这里只走 notify+草稿通道
          patchForm({ segStart: 0, segEnd: 0 })
        }}>添加</Button>
      </div>

      {/* 区间行（:688-708） */}
      {segs.map((s, i) => (
        <div key={s.id || i} style={ITEM} data-testid={`seg-row-${i}`}>
          <Tag>{s.label}</Tag>
          {segCtl.multiple && (
            <Button size="small" type="text"
                    title="这个时间段可以同时属于多个类别"
                    onClick={() => openRegionLabels('segment', i, `第 ${i + 1} 个时间段`)}>
              多类别{shapeExtraCount(s) > 0 ? ` +${shapeExtraCount(s)}` : ''}
            </Button>
          )}
          <input style={NUM} type="number" step={step}
                 value={Number(s.start)}
                 onChange={(e) => { s.start = Number(e.target.value); fixSegment(s, unit); commit(() => {}) }} />
          <span style={MUT}>—</span>
          <input style={NUM} type="number" step={step}
                 value={Number(s.end)}
                 onChange={(e) => { s.end = Number(e.target.value); fixSegment(s, unit); commit(() => {}) }} />
          <span style={MUT}>{isSec ? '秒' : '帧'}</span>
          {isSec ? <span style={{ fontSize: 12, color: '#666' }}>
            {clock(s.start as number)} → {clock(s.end as number)}
          </span> : null}
          {model.ctl.perRegionText && (
            <input style={{ ...NUM, width: 180 }} value={String(s.text || '')}
                   placeholder="这一段说了什么"
                   onChange={(e) => { s.text = e.target.value; commit(() => {}) }} />
          )}
          <span style={{ flex: 1 }} />
          <Button size="small" type="text" onClick={() => { segs.splice(i, 1); commit(() => {}) }}>删除</Button>
        </div>
      ))}
      {!segs.length && (
        <div style={{ fontSize: 12, color: '#999' }}>
          {model.mode === 'audio' ? '播放音频，在起始/结束处记时间后添加' : '播放视频，按帧添加时间段'}
        </div>
      )}
    </div>
  )
}
