/**
 * ShapeList.tsx —— 图形列表（框/多边形/关键点/椭圆/笔刷）（REQ-UNIFY-6-2）。
 * 对照 LabelStudioWorkbench.vue 模板 :715-852：只做「列表 + 行内类别/多类别/删除/清空/
 * 新框默认类别/视频关键帧计数与记帧」；画布绘制、拖拽、高亮本体归 6-3/6-4，
 * 选中态经 wsPanelUiStore.active 双向同步（行点击=选中，画布点击亦写同一缝）。
 * brush 行的删除只动 s.brushRegions（redrawBrush 由 6-4 画布随状态重渲染）。
 *
 * BUG-PARITY-6：新增 **hoverBox** 缝（Vue :716 容器 + :738 行的 mouseenter/mouseleave），
 * 只写悬停位、不动 active——悬停是「这一行对应图上哪个框」的提示，顺手改选中会污染保存语义。
 *
 * ⚠ 上面「画布点击亦写同一缝」这句经 2026-11-02 实测不成立：canvas/WsMediaStage 对 ui.active
 * 只读不写（canvas/ 内 grep `active:` 0 命中，读点在 WsMediaStage:284 `engine.active.box`），
 * 引擎那套 active.box 与面板 ui.active 是两个状态 → 点行只亮行、图上不亮框，反向也不通。
 * 本卡范围只有「行悬停联动」（对拍 §一 行 36）；点击双向同步属 6-2/6-3 接缝缺陷，
 * 按 §六 纪律写清「模块+现象+证据」交回主控转派，不在本卡顺手改别人模块的选中语义。
 */
import { Button, Select, Tag } from 'antd'
import { setPrimaryLabel } from '../../../pages/mos/wsSchema'
import { usePanelCtx } from './PanelContext'
import {
  activeToolCtl, labelColor, removeShape, shapeExtraCount, shapeLabelCtl, stampKeyframe,
} from './panelOps'
import { BORDER, MONO, MUT } from './panelStyles'

const SEC: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }
const ITEM: React.CSSProperties = { display: 'flex', gap: 6, alignItems: 'center', padding: '4px 0', borderTop: BORDER, cursor: 'pointer' }

export function ShapeList() {
  const { model, ui, commit, patchUi, patchForm, toast, openLabelEditor, openRegionLabels } = usePanelCtx()
  const ctl = model.ctl
  if (!(ctl.boxCtl || ctl.polygonCtl || ctl.keypointCtl || ctl.ellipseCtl || ctl.brushCtl)) return null

  const s = model.s
  const form = ui.form
  const shapeCtl = shapeLabelCtl(ctl)
  const toolCtl = activeToolCtl(ctl, model.mode, ui.imageTool)
  const activeOptions = (toolCtl && toolCtl.options) || []
  const boxOptions = ((ctl.rectCtl || ctl.vrectCtl) || {}).options || []
  const polyOptions = ctl.polygonCtl?.options || []
  const kpOptions = ctl.keypointCtl?.options || []
  const ellOptions = ctl.ellipseCtl?.options || []
  const brushOptions = ctl.brushCtl?.options || []
  const total = s.boxes.length + s.polygons.length + s.keypoints.length + s.ellipses.length
  const fps = s.frameRate || ctl.schema.frame_rate || 25
  const currentFrame = Math.round((Number(ui.currentTime) || 0) * fps)
  const activeBox = ui.active?.kind === 'box' ? ui.active.idx : null

  const sel = (kind: string, i: number) => patchUi({ active: { kind: kind as never, idx: i } })

  return (
    <div data-ws="box-list"
         onMouseLeave={() => patchUi({ hoverBox: null })}
         style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 10 }}>
      {/* 列表头（:718-734）：图形计数 / 类别编辑 / 新框默认类别 / 清空 */}
      <div style={SEC}>
        <b style={{ fontSize: 13 }}>① 图形（{total}）</b>
        {shapeCtl && (
          <Button size="small" type="text" title="增删 / 改名框选类别（行首下拉可以改单个图形的类别）"
                  onClick={() => openLabelEditor(shapeCtl, '图形')}>编辑</Button>
        )}
        <span style={{ flex: 1 }} />
        {activeOptions.length > 0 && (
          <label style={{ display: 'inline-flex', gap: 4, alignItems: 'center', fontSize: 12 }}
                 title="继续画新框时默认用这个类别，画完仍可在行首改">
            新框
            <Select size="small" style={{ minWidth: 110 }} value={form.boxLabel || undefined}
                  onChange={(v: string) => patchForm({ boxLabel: v })}
                  options={activeOptions.map((o) => ({ value: o.value, label: o.value }))} />
          </label>
        )}
        {ctl.vrectCtl && activeBox != null && (
          <Button size="small" onClick={() => patchUi({ active: null })}>新建框</Button>
        )}
        {s.boxes.length > 0 && (
          <Button size="small" type="text" onClick={() => { commit((st) => { st.boxes = [] }); patchUi({ active: null }) }}>清空</Button>
        )}
      </div>

      {/* 框行（:735-775）；悬停两端都写（Vue :738 @mouseenter/@mouseleave）→ 画布对应框轻提示 */}
      {s.boxes.map((b, i) => (
        <div key={b.id || i} style={{ ...ITEM, background: activeBox === i ? '#e6f4ff' : undefined }}
             onClick={() => sel('box', i)} data-testid={`box-row-${i}`}
             onMouseEnter={() => patchUi({ hoverBox: i })}
             onMouseLeave={() => patchUi({ hoverBox: null })}>
          <span style={{ ...BX_NO, background: labelColor(b.label, ctl, model.mode, ui.imageTool) }}
                title={`图上第 ${i + 1} 个框`}>{i + 1}</span>
          <Select size="small" style={{ minWidth: 96 }} value={b.label || undefined}
                  title={`图上第 ${i + 1} 个框的类型：${b.label}`}
                  onChange={(v: string) => commit(() => { setPrimaryLabel(b, v) })}
                  onClick={(e) => e.stopPropagation()}
                  options={boxOptions.map((o) => ({ value: o.value, label: o.value }))} />
          {(ctl.rectCtl || ctl.vrectCtl)?.multiple ? (
            <Button size="small" type="text" title="这个框可以同时属于多个类别；点开勾选附加类别"
                    onClick={(e) => { e.stopPropagation(); openRegionLabels('box', i, `第 ${i + 1} 个框`) }}>
              多类别{shapeExtraCount(b) > 0 ? ` +${shapeExtraCount(b)}` : ''}
            </Button>
          ) : shapeExtraCount(b) > 0 ? (
            <Tag title={((b.labels || []) as string[]).join('、')}>+{shapeExtraCount(b)}</Tag>
          ) : null}
          {ctl.vrectCtl ? (
            <>
              <span style={MONO}>关键帧 {(b.sequence || []).length || 1}</span>
              <span style={{ ...MONO, ...MUT }}>当前帧 {currentFrame}</span>
            </>
          ) : (
            <span style={{ ...MONO, ...MUT }}
                  title={`左上 ${(b.x || 0).toFixed(1)}%,${(b.y || 0).toFixed(1)}%　大小 ${(b.w || 0).toFixed(1)}% × ${(b.h || 0).toFixed(1)}%`}>
              {(b.x || 0).toFixed(0)},{(b.y || 0).toFixed(0)} · {(b.w || 0).toFixed(0)}×{(b.h || 0).toFixed(0)}%
            </span>
          )}
          <Tag color={model.dirty ? 'gold' : 'green'}
               title={model.dirty ? '还在画布上，没保存' : '已经随这条任务保存'}>
            {model.dirty ? '未保存' : '已标注'}
          </Tag>
          <span style={{ flex: 1 }} />
          {ctl.vrectCtl && activeBox === i && (
            <Button size="small" onClick={(e) => {
              e.stopPropagation()
              const r = stampKeyframe(s.boxes[i], currentFrame, fps)
              if (!r.ok) toast('bad', r.msg)
              else commit(() => {})
            }}>当前帧记一帧</Button>
          )}
          <Button size="small" type="text" title="删掉这个框"
                  onClick={(e) => { e.stopPropagation(); commit((st) => removeShape(st, 'box', i)); patchUi({ active: null }) }}>×</Button>
        </div>
      ))}
      {!s.boxes.length && (
        <div style={{ fontSize: 12, color: '#999' }}>
          {model.mode === 'image'
            ? '在图片上拖拽画框（压在已有框上也能画，重叠不要紧）；点一下框选中后才能拖动/缩放'
            : '暂停到目标帧，点「在画面上画框」后拖拽'}
        </div>
      )}
      {ctl.vrectCtl && s.boxes.length ? (
        <div style={{ fontSize: 12, color: '#999', marginBottom: 6 }}>
          选中一个框后，到目标帧画框 = 给这个框加关键帧；不选则新建框。
          不进入画框模式时可拖动/缩放选中框（写入当前帧），Delete 删除。
        </div>
      ) : null}

      {/* 多边形行（:785-800） */}
      {s.polygons.map((p, i) => (
        <div key={p.id || i} style={ITEM} onClick={() => sel('polygon', i)}>
          <Select size="small" style={{ minWidth: 96 }} value={p.label || undefined}
                  title={`这个多边形的类型：${p.label}`}
                  onChange={(v: string) => commit(() => { setPrimaryLabel(p, v) })}
                  onClick={(e) => e.stopPropagation()}
                  options={polyOptions.map((o) => ({ value: o.value, label: o.value }))} />
          {ctl.polygonCtl?.multiple && (
            <Button size="small" type="text" title="这个多边形可以同时属于多个类别"
                    onClick={(e) => { e.stopPropagation(); openRegionLabels('polygon', i, `第 ${i + 1} 个多边形`) }}>
              多类别{shapeExtraCount(p) > 0 ? ` +${shapeExtraCount(p)}` : ''}
            </Button>
          )}
          <span style={{ ...MONO, ...MUT }}>{(p.points || []).length} 个顶点</span>
          <span style={{ flex: 1 }} />
          <Button size="small" type="text" onClick={(e) => { e.stopPropagation(); commit((st) => removeShape(st, 'polygon', i)); patchUi({ active: null }) }}>删除</Button>
        </div>
      ))}

      {/* 关键点行（:801-816） */}
      {s.keypoints.map((p, i) => (
        <div key={p.id || i} style={ITEM} onClick={() => sel('keypoint', i)}>
          <Select size="small" style={{ minWidth: 96 }} value={p.label || undefined}
                  title={`这个关键点的类型：${p.label}`}
                  onChange={(v: string) => commit(() => { setPrimaryLabel(p, v) })}
                  onClick={(e) => e.stopPropagation()}
                  options={kpOptions.map((o) => ({ value: o.value, label: o.value }))} />
          {ctl.keypointCtl?.multiple && (
            <Button size="small" type="text" title="这个关键点可以同时属于多个类别"
                    onClick={(e) => { e.stopPropagation(); openRegionLabels('keypoint', i, `第 ${i + 1} 个关键点`) }}>
              多类别{shapeExtraCount(p) > 0 ? ` +${shapeExtraCount(p)}` : ''}
            </Button>
          )}
          <span style={{ ...MONO, ...MUT }}>{(p.x || 0).toFixed(1)}% / {(p.y || 0).toFixed(1)}%</span>
          <span style={{ flex: 1 }} />
          <Button size="small" type="text" onClick={(e) => { e.stopPropagation(); commit((st) => removeShape(st, 'keypoint', i)); patchUi({ active: null }) }}>删除</Button>
        </div>
      ))}

      {/* 椭圆行（:817-833） */}
      {s.ellipses.map((e0, i) => (
        <div key={e0.id || i} style={ITEM} onClick={() => sel('ellipse', i)}>
          <Select size="small" style={{ minWidth: 96 }} value={e0.label || undefined}
                  title={`这个椭圆的类型：${e0.label}`}
                  onChange={(v: string) => commit(() => { setPrimaryLabel(e0, v) })}
                  onClick={(e) => e.stopPropagation()}
                  options={ellOptions.map((o) => ({ value: o.value, label: o.value }))} />
          {ctl.ellipseCtl?.multiple && (
            <Button size="small" type="text" title="这个椭圆可以同时属于多个类别"
                    onClick={(ev) => { ev.stopPropagation(); openRegionLabels('ellipse', i, `第 ${i + 1} 个椭圆`) }}>
              多类别{shapeExtraCount(e0) > 0 ? ` +${shapeExtraCount(e0)}` : ''}
            </Button>
          )}
          <span style={{ ...MONO, ...MUT }}>x {(e0.x || 0).toFixed(1)}% · y {(e0.y || 0).toFixed(1)}% · {(e0.w || 0).toFixed(1)}% × {(e0.h || 0).toFixed(1)}%</span>
          <span style={{ flex: 1 }} />
          <Button size="small" type="text" onClick={(ev) => { ev.stopPropagation(); commit((st) => removeShape(st, 'ellipse', i)); patchUi({ active: null }) }}>删除</Button>
        </div>
      ))}

      {/* 笔刷行（:834-851；redrawBrush 归 6-4：画布随 s.brushRegions 重渲染） */}
      {s.brushRegions.map((r, i) => (
        <div key={r.id || i} style={ITEM}>
          <Tag>笔刷</Tag>
          <Select size="small" style={{ minWidth: 96 }} value={r.label || undefined}
                  title={`这笔涂抹的类型：${r.label}`}
                  onChange={(v: string) => commit(() => { setPrimaryLabel(r, v) })}
                  options={brushOptions.map((o) => ({ value: o.value, label: o.value }))} />
          {ctl.brushCtl?.multiple && (
            <Button size="small" type="text" title="这块涂抹可以同时属于多个类别"
                    onClick={() => openRegionLabels('brush', i, `第 ${i + 1} 块笔刷`)}>
              多类别{shapeExtraCount(r) > 0 ? ` +${shapeExtraCount(r)}` : ''}
            </Button>
          )}
          <span style={{ ...MONO, ...MUT }}>{r.w}×{r.h} · {(r.rle || []).length} 段</span>
          <span style={{ flex: 1 }} />
          <Button size="small" type="text" onClick={() => { commit((st) => removeShape(st, 'brush', i)) }}>删除</Button>
        </div>
      ))}
    </div>
  )
}

const BX_NO: React.CSSProperties = {
  color: '#fff', borderRadius: 4, padding: '0 6px', fontSize: 12, lineHeight: '18px',
  fontFamily: 'ui-monospace, Consolas, monospace',
}
