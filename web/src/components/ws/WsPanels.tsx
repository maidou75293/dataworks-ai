/**
 * WsPanels.tsx —— 工作台壳内各面板（REQ-UNIFY-6-1）。
 * 对照 LabelStudioWorkbench.vue 模板（只读）：问题卡 253-262、草稿条 228-244、
 * 分类组 570-590、文本答案 882-893、他人标注徽标 555-561。
 * 画布几何类控件（区间/框选/多边形…）归 6-2/6-3；本文件只放文本类与生命周期面板。
 */
import { Alert, Button, Checkbox, Modal, Radio, Tag, Input } from 'antd'
import type { WorkbenchModel } from '../../pages/mos/wsLifecycle'
import type { SchemaCtl } from '../../pages/mos/wsSchema'
import { captureSpan } from './panel/panelOps'
import { panelUiStore } from './panel/wsPanelUiStore'

/** fmtDraft（Vue:2072） */
export function fmtDraft(at: unknown): string {
  const d = new Date(Number(at) || 0)
  if (isNaN(d.getTime()) || !at) return ''
  return d.toLocaleString('zh-CN', {
    hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

/** 问题/文本卡（Vue:253-262） */
export function QuestionCard({ model }: { model: WorkbenchModel }) {
  const data = (model.selected && model.selected.data) || {}
  // BUG-PARITY-2：Vue:256 正文容器 @mouseup/@keyup="captureSpan" 在容器层定格 pendingSpan
  // （captureSpan:2648 对塌陷/越界/空白选区显式置 null，顺带清掉过期 pending）。
  // React 移植后这条缝一直没人写：SpanEditor 只在点类别现场抓一次，选区一旦在
  // 「选完→点类别」之间被扰动（点答案框、正文内再单击、键盘扩展后焦点在 BODY），
  // 现场兜底抓空 → 恒报「先在正文里选中一段文字」。
  // tabIndex=-1：可点聚焦、不可 Tab 到达。探针实测（web/.tmp-parity2/kb-probe3.py）
  // 容器不带 tabIndex 时点击后焦点停 BODY，Shift+方向键扩展选区的 keyup 不会路由
  // 进容器——它是「纯键盘扩展也能定格」的 Chromium 前提（Vue 未带此属性，其键盘
  // 路径实际依赖点击现场兜底，本卡验收要求纯键盘同过，故按实测行为补齐）。
  const spanOn = !!model.ctl.textSpanCtl
  const snapSpan = (el: HTMLElement) => panelUiStore(model).patch({ pendingSpan: captureSpan(el) })
  return (
    <section style={{
      border: '1px solid #d9d9d9', borderRadius: 6, padding: '10px 12px', marginBottom: 10,
      background: '#fff',
    }}>
      <div style={{ fontSize: 11, color: '#888', letterSpacing: 1 }}>
        {model.mode === 'text' ? '问题 / 文本' : '任务说明'}
      </div>
      <div style={{ fontSize: 14, lineHeight: '22px', whiteSpace: 'pre-wrap', marginTop: 4 }}>
        {model.promptText}
      </div>
      {model.mode === 'text' && (data as Record<string, any>).input ? (
        // data-ws-text-body：6-2 面板 captureSpan 取选区用（主控合入清单 2，容器实渲染在此）
        <div data-ws-text-body
             tabIndex={-1}
             onMouseUp={spanOn ? (e) => snapSpan(e.currentTarget) : undefined}
             onKeyUp={spanOn ? (e) => snapSpan(e.currentTarget) : undefined}
             style={{ fontSize: 13, color: '#555', marginTop: 6, whiteSpace: 'pre-wrap' }}>
          {(data as Record<string, any>).input}
        </div>
      ) : null}
    </section>
  )
}

/** 草稿条（Vue:228-244）：冲突时让用户明确选「用草稿/用服务器」，不再静默覆盖 */
export function DraftBar({ model }: { model: WorkbenchModel }) {
  if (!model.draftOffer) return null
  const conflict = model.draftConflict
  return (
    <Alert type={conflict ? 'warning' : 'info'} showIcon style={{ marginBottom: 10 }}
      message={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <b>{conflict ? '发现未保存的草稿' : '已恢复未保存的草稿'}</b>
          <span style={{ color: '#888', fontSize: 12 }}>
            {conflict ? '服务器上这条标注已更新，请选择保留哪一份'
              : `保存于 ${fmtDraft(model.draftOffer.at)} · 还没写入服务器${model.draftOffer.restored ? ' · 基于历史版本' : ''}`}
          </span>
          <span style={{ flex: 1 }} />
          {conflict ? (
            <>
              <Button size="small" onClick={() => model.applyDraft()}>用我的草稿</Button>
              <Button size="small" onClick={() => model.dropDraft()}>用服务器版本</Button>
            </>
          ) : (
            <>
              <Button size="small" onClick={() => model.dismissDraft()}>保留草稿</Button>
              <Button size="small" onClick={() => model.dropDraft()}>改用服务器版本</Button>
            </>
          )}
        </div>
      }
    />
  )
}

/** 分类组（Vue:570-590）：多个 Choices 逐个渲染；单组才显示数字键角标 */
export function ChoiceGroups({ model }: { model: WorkbenchModel }) {
  const ctls = model.ctl.choicesCtls
  const multiGroup = ctls.length > 1
  return (
    <>
      {ctls.map((c: SchemaCtl) => {
        const v = model.s.choiceVals[c.from_name]
        return (
          <div key={'ch' + c.from_name} style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', marginBottom: 6 }}>
              <b style={{ fontSize: 13 }}>{multiGroup ? c.from_name : '整图类别'}</b>
              <span style={{ color: '#888', fontSize: 12 }}>
                {c.multiple ? '可多选，选的是整张图' : '单选，选的是整张图'}
              </span>
            </div>
            {c.multiple ? (
              <Checkbox.Group
                value={Array.isArray(v) ? v : (v ? [v as string] : [])}
                onChange={(vals) => model.setChoice(c.from_name, vals as string[])}
                options={(c.options || []).map((o, i) => ({
                  label: multiGroup || ctls.length > 1 || i > 8
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
                    {i < 9 && ctls.length === 1 ? <kbd style={KBD} title={`快捷键 ${i + 1}`}>{i + 1}</kbd> : null}
                  </Radio>
                ))}
              </Radio.Group>
            )}
          </div>
        )
      })}
    </>
  )
}

const KBD: React.CSSProperties = {
  border: '1px solid #d9d9d9', borderRadius: 3, padding: '0 4px', fontSize: 10,
  color: '#888', background: '#fafafa', fontFamily: 'ui-monospace, Consolas, monospace',
}

/** 文本答案区（Vue:882-893）：多 TextArea 按 from_name 分存 */
export function TextAnswers({ model, areaRef }: {
  model: WorkbenchModel
  // antd TextArea 实例（带 focus()），类型随版本变，这里不钉死
  areaRef?: React.MutableRefObject<any>
}) {
  const ctls = model.ctl.textareas
  return (
    <>
      {ctls.map((c: SchemaCtl, i) => (
        <div key={'tx' + c.from_name} style={{ marginBottom: 10 }}>
          {ctls.length > 1 ? (
            <div style={{ fontSize: 12, color: '#666', marginBottom: 4 }}>{c.from_name}</div>
          ) : null}
          <Input.TextArea
            ref={i === 0 ? areaRef : undefined}
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
    </>
  )
}

/** 他人标注只读提示（Vue:555-561） */
export function ViewingTag({ model }: { model: WorkbenchModel }) {
  if (!model.selected || !model.detail) return null
  if (model.s.annotationMine || model.s.annotationId) {
    return <Tag color="blue">编辑我的标注{model.s.restored ? '（基于草稿）' : ''}</Tag>
  }
  if (model.s.viewingOther) {
    const my = ((model.detail.annotations || []) as Record<string, any>[])
      .filter((a) => a.mine).slice(-1)[0]
    return (
      <Tag title={`正在看 ${model.s.viewingOther} 的标注；保存只写你的那份，不会改它`}>
        正在看 {model.s.viewingOther} 的标注，保存只写我的{my ? `（覆盖 #${my.id}）` : '（新建）'}
      </Tag>
    )
  }
  return <Tag color="orange">将新增标注</Tag>
}

/** ? 快捷键帮助（Vue 模板 929 的快捷键条；本壳补一个可弹出面板）。
 * BUG-PARITY-5 文案诚实：每行都必须是真绑定的键——'?' 已由 MosWorkbench 补上真绑定；
 * 画布键行按引擎实支持面补齐（canvas/keys.ts:45-58），措辞对齐画布工具条既有 title。 */
export function HelpModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const rows: [string, string][] = [
    ['Ctrl+Enter / Ctrl+S', '保存（待标注页签＝保存并下一条）'],
    ['Ctrl+Z', '撤销（一次拖动/一段连续输入算一步；文本框内保留浏览器原生撤销）'],
    ['Ctrl+Shift+Z / Ctrl+Y', '重做'],
    ['J / K', '下一条 / 上一条'],
    ['S', '跳过这条（会先确认）'],
    ['/', '焦点进答案输入框'],
    ['1-9', '选分类选项（仅单组分类时生效；多选再按一次取消）'],
    ['F / Shift+F', '画布专注 / 最大化（Esc 退出）'],
    ['+ / − / 0 / W', '图片放大 / 缩小 / 恢复适应窗口 / 适应宽度'],
    [', / .', '视频逐帧（按住 Shift 一次 10 帧）'],
    ['Delete', '删除当前选中的框'],
    ['Esc', '退出专注 / 最大化 / 取消选中'],
    ['?', '打开/关闭本帮助'],
  ]
  return (
    <Modal open={open} onCancel={onClose} footer={null} title="快捷键" width={420}>
      <table style={{ width: '100%', fontSize: 13 }}>
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <td style={{ padding: '6px 8px 6px 0', whiteSpace: 'nowrap' }}><kbd style={KBD}>{k}</kbd></td>
              <td style={{ color: '#555' }}>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Modal>
  )
}
