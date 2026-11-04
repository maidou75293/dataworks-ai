/**
 * WsPanel.tsx —— 右侧标注面板根（REQ-UNIFY-6-2）。
 *
 * 对照 LabelStudioWorkbench.vue 模板 :545-908 的「标注」卡：
 * 整图类别（含编辑钮）→ 评分/数值/日期/层级/对比 → 正文/文档 span → 时间区间（含
 * per_region 文本）→ 图形列表 → 关系 → 文本答案/图片描述条。
 * 挂载（MosWorkbench.tsx:200-234 把 ChoiceGroups+TextAnswers 换成本组件）涉及共享组件文件
 * → 走「主控合入清单」，与 6-3 的画布挂载 diff 同批合并；本文件不在 6-2 域内改它。
 */
import { useEffect, useRef, useState } from 'react'
import { Button, Checkbox, Empty, Input, Radio, message } from 'antd'
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import type { SchemaCtl } from '../../../pages/mos/wsSchema'
import { choicePicked } from '../../../pages/mos/wsSchema'
import { commitEdit } from './panelBridge'
import { PanelProvider } from './PanelContext'
import { ExtraControls } from './ExtraControls'
import { RelationEditor } from './RelationEditor'
import { SegmentEditor } from './SegmentEditor'
import { ShapeList } from './ShapeList'
import { SpanEditor } from './SpanEditor'
import { LabelEditorModal, type LabelEditorReq } from './LabelEditorModal'
import { RegionLabelsModal, type RegionLabelsReq } from './RegionLabelsModal'
import { emptyForm, labelEditorRows, resetFormForTask, type LabelRow } from './panelOps'
import { panelUiStore, usePanelUi } from './wsPanelUiStore'
import { BORDER, KBD, MUT } from './panelStyles'

export default function WsPanel({ model, answerRef }: {
  model: WorkbenchModel
  /** antd TextArea 实例 ref（'/' 聚焦）：由宿主传入（MosWorkbench:46 focusAnswer） */
  answerRef?: React.MutableRefObject<any>
}) {
  const ui = usePanelUi(model)
  const st = panelUiStore(model)
  const seq = useRef(0)
  const [labelReq, setLabelReq] = useState<{ req: LabelEditorReq; rows: LabelRow[] } | null>(null)
  const [relReq, setRelReq] = useState<RegionLabelsReq | null>(null)

  const ctls = model.ctl
  const choicesCtls = ctls.choicesCtls
  const multiGroup = choicesCtls.length > 1

  // openLabelEditor:1835-1844 —— rows 按控件现 options 重建
  const openLabelEditor = (ctl: SchemaCtl | null, title: string) => {
    if (!ctl || !ctl.from_name) return
    setLabelReq({ req: { ctl, title, seq: ++seq.current }, rows: labelEditorRows(ctl) })
  }
  // openRegionLabels:2391-2396
  const openRegionLabels = (kind: RegionLabelsReq['kind'], idx: number, title: string) => {
    setRelReq({ kind, idx, title, seq: ++seq.current })
  }

  // BUG-PARITY-1：面板弹窗打开=画布/壳层键闸必须吃进（Vue:4640 .modal-mask 挡全部键）。
  // 本组件自持 labelReq/relReq，写入 model.panelModalOpen；卸载（切项目等）自动落闸。
  useEffect(() => {
    model.panelModalOpen = !!labelReq || !!relReq
    return () => { model.panelModalOpen = false }
  }, [model, labelReq, relReq])

  // 切任务/详情到达 = resetAnnotation:2275-2278/2292/2327-2336 的面板侧等价
  const taskId = model.selected?.id ?? 0
  const detailLoaded = !!model.detail
  useEffect(() => {
    st.resetForTask()
    const form = emptyForm()
    resetFormForTask(model.s, model.ctl, model.mode, st.get().imageTool, form)
    st.patchForm(form)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [taskId, detailLoaded])

  const ctx = {
    model, ui,
    commit: (edit: Parameters<typeof commitEdit>[1]) => commitEdit(model, edit),
    patchUi: st.patch,
    patchForm: st.patchForm,
    toast: (lv: 'ok' | 'bad', msg: string) => {
      if (lv === 'ok') message.success(msg); else message.error(msg)
    },
    openLabelEditor,
    openRegionLabels,
  }

  return (
    <PanelProvider value={ctx}>
      {/* 分类（:570-588）：多组逐个渲染；数字键角标只在单组时出现（onKey:4683 同闸） */}
      {choicesCtls.map((c) => {
        const v = model.s.choiceVals[c.from_name]
        const picked = choicePicked(c, model.s)
        return (
          <div key={'ch' + c.from_name} style={{ marginBottom: 12 }} data-testid={'choices-' + c.from_name}>
            <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', marginBottom: 6 }}>
              <b style={{ fontSize: 13 }}>{multiGroup ? c.from_name : '整图类别'}</b>
              <span style={MUT}>{c.multiple ? '可多选，选的是整张图' : '单选，选的是整张图'}</span>
              <span style={{ flex: 1 }} />
              <Button size="small" type="text" title="增删 / 改名这个分类"
                      onClick={() => openLabelEditor(c, '整图类别')}>编辑</Button>
            </div>
            {c.multiple ? (
              <Checkbox.Group
                value={picked}
                onChange={(vals) => model.setChoice(c.from_name, vals as string[])}
                options={(c.options || []).map((o, i) => ({
                  label: multiGroup || i > 8
                    ? o.value
                    : <span>{o.value} <kbd style={KBD}>{i + 1}</kbd></span>,
                  value: o.value,
                }))}
              />
            ) : (
              <Radio.Group
                value={Array.isArray(v) ? v[0] : (v || '')}
                onChange={(e) => model.setChoice(c.from_name, e.target.value)}
              >
                {(c.options || []).map((o, i) => (
                  <Radio key={o.value} value={o.value} style={{ marginRight: 14 }}>
                    {o.value}
                    {i < 9 && !multiGroup ? <kbd style={KBD} title={`快捷键 ${i + 1}`}>{i + 1}</kbd> : null}
                  </Radio>
                ))}
              </Radio.Group>
            )}
          </div>
        )
      })}

      <ExtraControls />
      <SpanEditor />
      <SegmentEditor />
      <ShapeList />
      <RelationEditor />

      {/* 文本答案（:882-890）：非图片、非 per_region；多个按 from_name 分存 */}
      {!ctls.perRegionText && model.mode !== 'image' && ctls.textareas.map((c, i) => (
        <div key={'tx' + c.from_name} style={{ marginBottom: 10 }}>
          {ctls.textareas.length > 1 ? (
            <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>{c.from_name}</div>
          ) : null}
          <Input.TextArea
            ref={i === 0 ? answerRef : undefined}
            value={model.s.texts[c.from_name] || ''}
            onChange={(e) => model.setText(c.from_name, e.target.value)}
            spellCheck={false}
            autoSize={{ minRows: 3, maxRows: 10 }}
            placeholder={model.mode === 'doc'
              ? '写批注 / 抽取的字段…  Ctrl+Enter 或 Ctrl+S 保存'
              : '写下或修正答案…  Ctrl+Enter 或 Ctrl+S 保存，J/K 切换任务'}
          />
        </div>
      ))}

      {/* 图片描述固定条（:893-906）：mode=image 且有非 per_region 文本框。
          BUG-PARITY-1：answerRef 也挂首个——老版 `/` 的 DOM 查询覆盖描述条（:1548-1550），
          不挂则 image 模式「/ 聚焦」与切任务自动聚焦全部空转。 */}
      {model.mode === 'image' && !ctls.perRegionText && ctls.textareas.map((c, i) => (
        <div key={'img-tx' + c.from_name} style={{ border: BORDER, borderRadius: 6, padding: '8px 10px', marginBottom: 8 }}>
          <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', marginBottom: 6 }}>
            <b>{ctls.textareas.length > 1
              ? c.from_name
              : '② ' + (c.from_name === 'description' ? '图片描述' : '标注文字')}</b>
            <span style={MUT}>输入后点下方「保存」，或 Ctrl+Enter</span>
            <span style={{ flex: 1 }} />
            <span style={{ fontSize: 11.5, color: '#888', fontFamily: 'ui-monospace, Consolas, monospace' }}>
              {(model.s.texts[c.from_name] || '').length} 字
            </span>
          </div>
          <Input.TextArea
            ref={i === 0 ? answerRef : undefined}
            value={model.s.texts[c.from_name] || ''}
            onChange={(e) => model.setText(c.from_name, e.target.value)}
            spellCheck={false} rows={2}
            placeholder="写一句描述… Ctrl+Enter 保存"
          />
        </div>
      ))}

      {labelReq && (
        <LabelEditorModal
          key={labelReq.req.seq} req={labelReq.req} rows={labelReq.rows}
          setRows={(rows) => setLabelReq((cur) => (cur ? { ...cur, rows } : cur))}
          onClose={() => setLabelReq(null)}
        />
      )}
      {relReq && (
        <RegionLabelsModal key={relReq.seq} req={relReq} onClose={() => setRelReq(null)} />
      )}

      {/* 空态兜底（:935-938：右侧面板未选任务时；宿主一般不渲染面板，此分支防误挂） */}
      {!model.selected && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={<span><b>选一条任务开始</b><br />左侧队列挑任务；选中后这里显示类别、图形和描述。</span>} />}
    </PanelProvider>
  )
}
