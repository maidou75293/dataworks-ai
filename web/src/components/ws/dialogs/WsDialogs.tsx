/**
 * WsDialogs.tsx —— 弹窗群＋AI 辅助的 React 壳（REQ-UNIFY-6-6）。
 *
 * 动作全在 wsDialogActions.ts（headless，闸2 直接驱动）；本文件只做渲染接线。
 * 模板对照 LabelStudioWorkbench.vue：predict-box 26-51、AI 结果条 89-101、
 * 同步完成条 103-112、更多菜单 73-81、导入 1026-1064、QC 1067-1103、
 * 设置 1106-1218、AI 映射 1221-1244、预览 1246-1263、ins-sync 面板 966-1020。
 * 导入媒体选择：BUG-PARITY-13-C 恢复原件嵌入（LabelStudioWorkbench.vue:1037
 * `<MediaPicker :accept-kinds="[mode]">`）——MosMediaPicker 目录树+半选递归+服务端分页，
 * 替代此前的「平铺列表＋搜索＋200 封顶」降级壳（doImport 只消费 m.ls_url，口径不变）。
 * BUG-PARITY-3 标注历史回搬：Vue:55-59 常驻「数据」入口 + :943-965「标注历史」列表
 * （载入这条 / 复制到我的标注），loadHistory 语义在 wsLifecycle.ts（对照 Vue:2200-2208）。
 */
import { useEffect, useMemo, useSyncExternalStore } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Button, Checkbox, Drawer, Dropdown, Input, InputNumber, Modal,
  Select, Space, Tag, Typography, message,
} from 'antd'
import MosMediaPicker from '../../MosMediaPicker'
import type { MosMediaItem } from '../../../api/mos'
import type { WorkbenchModel } from '../../../pages/mos/wsLifecycle'
import { WsDialogStore } from './wsDialogActions'
import { dialogTransport } from './dialogApi'
import { READONLY_TITLE, getWriteAccess, subscribeWriteAccess } from '../wsAccess'

/** BUG-LABEL-WRITE-403-1②：只读态=导入/保存设置/AI 标注等写按钮灰且 title 有解释 */
const useReadOnly = () => useSyncExternalStore(subscribeWriteAccess, getWriteAccess) === 'readonly'

// CONFIG_MODES/CONFIG_CONTROLS：Vue 3958-3969，设置弹窗摘要文案用
const CONFIG_MODES: Record<string, string> = {
  text: '文本', audio: '音频', video: '视频', image: '图片', doc: '文档', timeseries: '时序',
}
const CONFIG_CONTROLS: Record<string, string> = {
  textarea: '文本输入', choices: '选择', labels: '标签/区间',
  timelabels: '时间区间', timelinelabels: '时间区间', rectanglelabels: '框选',
  videorectangle: '视频框选',
  rating: '评分', number: '数字', datetime: '日期', taxonomy: '分类树',
  pairwise: '二选一', relations: '关系', polygonlabels: '多边形',
  keypointlabels: '关键点', ellipselabels: '椭圆', brushlabels: '笔刷',
  hypertextlabels: '文档标注', timeserieslabels: '时序区间',
  paragraphlabels: '段落标注', ranker: '排序',
}

function hostOf(model: WorkbenchModel, extraGate: () => boolean) {
  return {
    pid: () => model.pid,
    project: () => model.project,
    schema: () => model.schema,
    selected: () => model.selected,
    dirty: () => model.dirty,
    datasetId: () => model.datasetId,
    syncResult: () => model.syncResult,
    loadStats: () => model.loadStats(),
    loadTasks: () => model.loadTasks(),
    reloadSelected: async () => {
      if (model.selected) await model.pick(model.selected, 0, false)
    },
    setModalGate: (on: boolean) => { model.modalOpen = on || extraGate() },
    // Vue:4033 project 原位回写；React 侧 schema/ctl 由 loadShell 统一重导出
    afterProjectChange: () => model.loadShell(),
    // BUG-PARITY-5：Vue:3987 补拉回填（连带 schema/ctl 重导出走 loadShell 同款三件套）
    adoptProject: (p: Record<string, any>) => model.adoptProject(p),
    hasShapeCtl: () => !!model.ctl.hasShapeCtl,   // Vue hasShapeCtl:1411
  }
}

export function useWsDialogs(model: WorkbenchModel, extraGate: () => boolean = () => false) {
  const store = useMemo(() => new WsDialogStore(dialogTransport, hostOf(model, extraGate), {
    toast: (lv, t) => {
      if (lv === 'ok') message.success(t)
      else if (lv === 'bad') message.error(t)
      else message.warning(t)
    },
    askConfirm: (o) => new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: o.title, content: o.message,
        okText: o.confirmText || '确定',
        okButtonProps: o.danger ? { danger: true } : undefined,
        onOk: () => resolve(true), onCancel: () => resolve(false),
      })
    }),
    copyText: (t) => navigator.clipboard.writeText(t),
  }), [model])
  useSyncExternalStore((cb) => store.subscribe(cb), () => store.version)
  useEffect(() => {
    if (Number.isFinite(model.pid) && model.pid > 0) void store.init()
  }, [store, model.pid])
  return store
}

// ---------- 顶栏：AI 就绪标 + predict-box + 更多菜单（Vue 26-81） ----------
export function WsDialogBar({ model, store }: { model: WorkbenchModel; store: WsDialogStore }) {
  const yoloOk = !!(store.aiStatus.yolo && store.aiStatus.yolo.ok)
  const vlmOk = !!(store.aiStatus.vlm && store.aiStatus.vlm.ok)
  const dis = !store.predictOk
  const readOnly = useReadOnly()
  return (
    <>
      <Tag color={store.aiReady ? 'green' : 'red'} title={store.aiTagTitle}>
        AI {store.aiReady ? '就绪' : '离线'}
      </Tag>
      <Space size={4} title={store.predictOk
        ? 'AI 直接写成标注（识别错就删掉重画）'
        : '当前画布暂不支持 AI 标注（支持图像矩形 / 文本 / 分类）'}>
        <Select size="small" style={{ width: 96 }} value={store.aiEngine} disabled={dis}
                title="AI 引擎：自动=有框用 YOLO、文本用 VLM"
                onChange={(v) => store.setEngine(v)}
                options={[
                  { value: 'auto', label: 'AI 自动' },
                  ...(yoloOk && store.rectLabels.length ? [{ value: 'yolo', label: '检测框' }] : []),
                  ...(vlmOk && !store.rectLabels.length ? [{ value: 'vlm', label: '图片理解' }] : []),
                ]} />
        <span style={{ fontSize: 12, color: '#888' }}>生成</span>
        <InputNumber size="small" min={1} max={50} style={{ width: 56 }} disabled={dis}
                     value={store.predictLimit} title="本次处理几条（AI 结果直接写成标注，1–50）"
                     onChange={(v) => store.setPredictLimit(Number(v) || 5)} />
        <span style={{ fontSize: 12, color: '#888' }}>条 阈值</span>
        <InputNumber size="small" min={0.05} max={0.95} step={0.05} style={{ width: 72 }} disabled={dis}
                     value={store.predictConf}
                     title="YOLO 置信度阈值（0.05–0.95）：调低 → 框更多、误检也多"
                     onChange={(v) => store.setPredictConf(Number(v))} />
        <Button size="small" loading={store.predicting}
                disabled={dis || !model.stats.unannotated || readOnly}
                title={readOnly ? READONLY_TITLE : undefined}
                onClick={() => { void store.predict() }}>
          {store.predicting ? 'AI 标注中…' : 'AI 智能标注'}
        </Button>
      </Space>
      {/* BUG-PARITY-3：回搬 Vue:55-59 常驻「数据」入口——老版注释点名「以前只藏在更多里，
          用户找不到」；标注历史是协作复核链入口，不能再埋回二级菜单。 */}
      <Button size="small" type={store.autoPanelOpen ? 'primary' : 'default'}
              aria-expanded={store.autoPanelOpen}
              title="任务数据 / 标注历史 / 自动同步（右侧抽屉）"
              onClick={() => store.setAutoPanelOpen(!store.autoPanelOpen)}>
        数据
      </Button>
      <Dropdown menu={{ items: [
        { key: 'import', label: '导入任务' },
        { key: 'qc', label: '质量检查' },
        { key: 'auto', label: '任务数据 / 自动同步' },
        { key: 'settings', label: '项目设置' },
      ], onClick: ({ key }) => {
        if (key === 'import') store.setImportOpen(true)
        if (key === 'qc') void store.openQc()
        if (key === 'auto') store.setAutoPanelOpen(true)
        if (key === 'settings') void store.openSettings()
      } }}>
        <Button size="small">更多 ▾</Button>
      </Dropdown>
    </>
  )
}

// ---------- 结果条：AI 运行条（Vue 89-101）+ 同步完成条（103-112，含深链） ----------
export function WsDialogStrips({ model, store }: { model: WorkbenchModel; store: WsDialogStore }) {
  const navigate = useNavigate()
  const r = model.syncResult
  const dsLink = '/mos/datasets?ds=' + encodeURIComponent(r ? String(r.dataset_id || '') : '')
  const trainLink = '/mos/jobs?method=sft&dataset=' + encodeURIComponent(r ? String(r.dataset_id || model.datasetId) : '')
  return (
    <>
      {store.aiRun && (
        <Alert
          type={store.rectLabels.length && !store.aiRun.boxes && store.aiRun.created ? 'warning' : 'info'}
          message={
            <Space size={8} wrap>
              <b>AI 标注</b>
              <span>本次 <b>{store.aiRun.created}</b> 条
                {store.rectLabels.length ? <> / <b>{store.aiRun.boxes}</b> 个框</> : null}</span>
              {store.aiRun.unmapped.length ? (
                <span style={{ color: '#a6600a' }}>未映射：
                  {store.aiRun.unmapped.map((u: Record<string, any>) => u.label + '×' + u.count).join('、')}
                </span>
              ) : null}
              {store.aiRun.note ? (
                <Typography.Text type="secondary" ellipsis title={store.aiRun.note} style={{ maxWidth: 280 }}>
                  {store.aiRun.note}
                </Typography.Text>
              ) : null}
              {store.aiRun.unmapped.length ? (
                <Button size="small" type="primary" onClick={() => store.openAiMap()}>映射标签</Button>
              ) : null}
              <Button size="small" type="text" onClick={() => { store.aiRun = null; store.touch() }}>关闭</Button>
            </Space>
          }
        />
      )}
      {r && (
        <Alert type="success" closable onClose={() => { model.syncResult = null }}
          message={
            <Space size={10} wrap>
              <span>✓ 已同步 <b>{r.inserted}</b> 条到标注集
                <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}> {String(r.dataset_id ?? '')}</span>
              </span>
              <Button size="small" onClick={() => navigate(dsLink)}>查看训练集 →</Button>
              <Button size="small" type="primary" onClick={() => navigate(trainLink)}>去训练 →</Button>
            </Space>
          } />
      )}
    </>
  )
}

// ---------- 弹窗面 ----------
export function WsDialogModals({ model, store }: { model: WorkbenchModel; store: WsDialogStore }) {
  return (
    <>
      <ImportModal model={model} store={store} />
      <QcModal store={store} />
      <SettingsModal store={store} />
      <AiMapModal store={store} />
      <PreviewModal store={store} />
      <AutoPanel model={model} store={store} />
    </>
  )
}

function ImportModal({ model, store }: { model: WorkbenchModel; store: WsDialogStore }) {
  const readOnly = useReadOnly()
  return (
    <Modal open={store.importOpen} title="导入任务" width={820} onCancel={() => store.setImportOpen(false)}
      footer={
        <Space>
          <Button onClick={() => store.setImportOpen(false)}>取消</Button>
          <Button type="primary" loading={store.importing}
                  disabled={readOnly || (store.importSource === 'media') && (!store.importMediaOk || !store.importPicked.length)}
                  title={readOnly ? READONLY_TITLE : undefined}
                  onClick={() => { void store.doImport() }}>
            {store.importing ? '导入中…' : store.importSource === 'media'
              ? `导入 ${store.importPicked.length} 条任务` : '导入到当前项目'}
          </Button>
        </Space>
      }>
      <Space direction="vertical" style={{ width: '100%' }} size={10}>
        <Space>
          <Button size="small" type={store.importSource === 'media' ? 'primary' : 'default'}
                  onClick={() => { store.importSource = 'media'; store.touch() }}>
            从媒体库选择
          </Button>
          <Button size="small" type={store.importSource === 'json' ? 'primary' : 'default'}
                  onClick={() => { store.importSource = 'json'; store.touch() }}>粘贴 JSON</Button>
        </Space>
        {store.importSource === 'media' ? (
          store.importMediaOk ? (
            <>
              <Typography.Text>选择{store.importModeLabel}文件（{store.importPicked.length} 个）</Typography.Text>
              {/* 老 1037 同位：accept-kinds=[mode]，勾文件夹=整夹递归选中；分页走服务端，无 200 封顶 */}
              <MosMediaPicker
                value={store.importPicked as MosMediaItem[]}
                onChange={(l) => store.setImportPicked(l)}
                acceptKinds={[store.mode]}
              />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                按当前画布的字段生成任务，媒体地址直接写入，不会复制文件。
              </Typography.Text>
            </>
          ) : (
            <Alert type="warning" showIcon
              message={`当前项目是${store.importModeLabel}画布`}
              description={
                <>从媒体库导入支持图片 / 音频 / 视频项目。文本和文档任务请用「粘贴 JSON」，
                  或回到「项目」页用媒体文件新建一个项目。
                  <Button size="small" style={{ marginLeft: 8 }} onClick={() => { store.importSource = 'json'; store.touch() }}>改用粘贴 JSON</Button>
                </>} />
          )
        ) : (
          <>
            <Typography.Text>粘贴 JSON 数组</Typography.Text>
            <Input.TextArea rows={10} value={store.importText}
                            placeholder='[{"data":{"instruction":"问题","input":""}}]'
                            onChange={(e) => { store.importText = e.target.value; store.touch() }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {'标注任务格式：'}<code>{'[{"data": {...}}, ...]'}</code>
            </Typography.Text>
          </>
        )}
      </Space>
    </Modal>
  )
}

function QcModal({ store }: { store: WsDialogStore }) {
  const q = store.qc
  return (
    <Modal open={store.qcOpen} title="标注质量检查" width={720} onCancel={() => store.closeQc()}
      footer={<Button onClick={() => store.closeQc()}>关闭</Button>}>
      {store.qcLoading ? '加载中…' : !q ? null : (
        <Space direction="vertical" style={{ width: '100%' }} size={10}>
          <Space size={6} wrap>
            <Tag>任务 {q.project?.tasks}</Tag>
            <Tag>标注 {q.project?.annotations}</Tag>
            <Tag>标注员 {(q.annotators || []).length}</Tag>
            <Tag>lead_time 中位数 {q.lead_time_median ?? '—'}s</Tag>
            <Tag color={q.human_like ? 'green' : 'red'}>{q.human_like ? '像人工标注' : '存在异常'}</Tag>
          </Space>
          {(q.warnings || []).length ? (
            <div>
              <b style={{ color: '#c0392b' }}>异常</b>
              <ul style={{ margin: '6px 0 0 18px', padding: 0 }}>
                {q.warnings.map((w: string) => <li key={w} style={{ fontSize: 13 }}>{w}</li>)}
              </ul>
            </div>
          ) : <Typography.Text type="secondary" style={{ fontSize: 13 }}>没有发现脚本批量写入特征。</Typography.Text>}
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 6 }}>标签分布</div>
            <Space size={6} wrap>
              {Object.keys(q.labels || {}).map((l) => <Tag key={l}>{l}: {q.labels[l]}</Tag>)}
              {!Object.keys(q.labels || {}).length ? <span style={{ color: '#888' }}>暂无标签</span> : null}
            </Space>
          </div>
        </Space>
      )}
    </Modal>
  )
}

function SettingsModal({ store }: { store: WsDialogStore }) {
  const c = store.configCheck
  const readOnly = useReadOnly()
  return (
    <Modal open={store.settingsOpen} title="项目设置" width={680} onCancel={() => store.setSettingsOpen(false)}
      footer={
        <Space>
          <Button onClick={() => store.setSettingsOpen(false)}>取消</Button>
          <Button type="primary" loading={store.savingSettings}
                  disabled={readOnly}
                  title={readOnly ? READONLY_TITLE : undefined}
                  onClick={() => { void store.saveSettings() }}>
            {store.savingSettings ? '保存中…' : '保存设置'}
          </Button>
        </Space>
      }>
      <Space direction="vertical" style={{ width: '100%' }} size={10}>
        <div>
          <div style={{ marginBottom: 4 }}>项目名</div>
          <Input value={store.settings.title}
                 onChange={(e) => { store.settings.title = e.target.value; store.touch() }} />
        </div>
        <div>
          <div style={{ marginBottom: 4 }}>项目说明</div>
          <Input placeholder="一句话说明这个项目标什么" value={store.settings.description}
                 onChange={(e) => { store.settings.description = e.target.value; store.touch() }} />
        </div>
        <div>
          <div style={{ marginBottom: 4 }}>标注说明（Labeling Instructions）</div>
          <Input.TextArea rows={5} placeholder="给标注员看的规则，例如：听不清就标“噪声”"
                          value={store.settings.expert_instruction}
                          onChange={(e) => { store.settings.expert_instruction = e.target.value; store.touch() }} />
        </div>
        <Space size={18} wrap>
          <Checkbox checked={store.settings.show_instruction}
                    onChange={(e) => { store.settings.show_instruction = e.target.checked; store.touch() }}>进入任务时显示说明</Checkbox>
          <Checkbox checked={store.settings.show_skip_button}
                    onChange={(e) => { store.settings.show_skip_button = e.target.checked; store.touch() }}>允许跳过任务</Checkbox>
          <Checkbox checked={store.settings.enable_empty_annotation} title="空标注只表示「已看过」，不会同步进标注集"
                    onChange={(e) => { store.settings.enable_empty_annotation = e.target.checked; store.touch() }}>允许空标注</Checkbox>
        </Space>
        <div>
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <b>标注页面</b>
            <Button size="small" type="text"
                    onClick={() => { store.cfgAdvanced = !store.cfgAdvanced; store.touch() }}>
              {store.cfgAdvanced ? '收起 XML' : '高级：查看 / 修改 XML'}
            </Button>
          </Space>
          <div style={{ fontSize: 12, color: '#888', margin: '4px 0' }}>
            它决定标注页显示什么、标注员能怎么标（输入框 / 分类 / 框选）。只改项目名、说明时<b>不用动它</b>；要加控件或改标签名才展开。
          </div>
          {!c && store.configCheckDenied && (
            // BUG-LABEL-WRITE-403-1②：403 吞掉的预校验也要占徽标位——「不可用」≠「没检查」
            <Tag color="orange" title="底层校验接口 403：服务角色无「标注」写权限（申请路径见工作台顶部横幅）">配置检查不可用（无权限）</Tag>
          )}
          {c && (
            <Space size={8} wrap style={{ marginBottom: 4 }}>
              <Tag color={c.ok ? 'green' : 'red'}>{c.ok ? '配置可用' : (c.errors || []).length + ' 个错误'}</Tag>
              <span style={{ fontSize: 12 }}>
                {(CONFIG_MODES[c.summary?.mode] || c.summary?.mode || '')}画布
                {' · '}{c.summary?.controls} 个控件 · {c.summary?.labels} 个标签
                {' · '}{c.summary?.supported ? '平台画布' : '原生画布'}
                {c.warnings?.length ? ` · ${c.warnings.length} 条提示` : ''}
              </span>
            </Space>
          )}
          {c && !c.ok && !store.cfgAdvanced ? (
            <Space direction="vertical">
              {(c.errors || []).map((e: Record<string, any>) => (
                <div key={e.code + e.message} style={{ color: '#c0392b', fontSize: 13 }}><b>错误</b> {e.message}</div>
              ))}
              <Button size="small" onClick={() => { store.cfgAdvanced = true; store.touch() }}>展开 XML 修正</Button>
            </Space>
          ) : null}
          {store.cfgAdvanced && (
            <>
              <Input.TextArea rows={8} value={store.settings.label_config}
                              style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}
                              onChange={(e) => store.onCfgInput(e.target.value)} />
              <Space style={{ marginTop: 6 }}>
                <Button size="small" loading={store.checkingConfig} onClick={() => { void store.checkConfig() }}>
                  {store.checkingConfig ? '校验中…' : '校验并预览'}
                </Button>
                {c ? <span style={{ fontSize: 12, color: '#888' }}>改完点这里确认；保存前还会自动再校验一次。</span> : null}
              </Space>
              {c && (
                <div style={{ marginTop: 6 }}>
                  {!c.ok && store.settings.label_config === store.origLabelConfig ? (
                    <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>
                      这是项目的历史配置。只改名称/说明可以直接保存；要改画布结构，先按下面的错误修正。
                    </div>
                  ) : null}
                  {(c.errors || []).map((e: Record<string, any>) => (
                    <div key={e.code + e.message} style={{ color: '#c0392b', fontSize: 13 }}><b>错误</b> {e.message}</div>
                  ))}
                  {(c.warnings || []).map((w: Record<string, any>) => (
                    <div key={w.code + w.message} style={{ color: '#a6600a', fontSize: 13 }}><b>提示</b> {w.message}</div>
                  ))}
                  <Space size={6} wrap style={{ marginTop: 6 }}>
                    <Tag>{(CONFIG_MODES[c.summary?.mode] || '文本')}画布</Tag>
                    {(c.schema?.controls || []).map((cc: Record<string, any>) => (
                      <Tag key={cc.from_name}>{CONFIG_CONTROLS[cc.type] || cc.type} <b>{cc.from_name}</b>
                        {cc.options?.length ? <i style={{ fontStyle: 'normal' }}> {cc.options.length}</i> : null}</Tag>
                    ))}
                    {(c.schema?.text_fields || []).length ? (
                      <Tag>正文 {c.schema.text_fields.map((f: Record<string, any>) => f.name).join(' / ')}</Tag>
                    ) : null}
                  </Space>
                </div>
              )}
              <div style={{ fontSize: 12, color: '#888', marginTop: 6 }}>改结构后，已有标注可能显示不全；保存前会自动再校验一次。</div>
            </>
          )}
        </div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          这些设置直接写回平台，不需要再打开底层标注系统。
        </Typography.Text>
      </Space>
    </Modal>
  )
}

function AiMapModal({ store }: { store: WsDialogStore }) {
  return (
    <Modal open={store.aiMapOpen} title="AI 标签映射" width={560} onCancel={() => store.closeAiMap()}
      footer={
        <Space>
          <Button onClick={() => store.closeAiMap()}>取消</Button>
          <Button type="primary" disabled={store.predicting} onClick={() => { void store.saveAiMapAndRerun() }}>
            {store.predicting ? '重新生成中…' : '保存映射并重新生成'}
          </Button>
        </Space>
      }>
      <div style={{ fontSize: 12, color: '#888', marginBottom: 10 }}>
        YOLO 只认 COCO 80 类。下面这些类别在项目里没有对应标签，选一个映射过去；
        留空表示丢弃（不会硬塞进训练数据）。映射会记到这个项目，下次自动用。<br />
        注意：映射是「一整类对一整类」，比如 bottle → 白净瓶，之后所有瓶子都会被标成
        白净瓶；蓝瓶/绿瓶这类细分类别还得人工在图上改。
      </div>
      {store.aiUnmapped.map((u: Record<string, any>) => (
        <Space key={u.label} style={{ width: '100%', justifyContent: 'space-between', marginBottom: 6 }}>
          <span>{u.label} <span style={{ color: '#888' }}>· {u.label_zh} ×{u.count}</span></span>
          <Select size="small" style={{ width: 160 }} value={store.aiMap[u.label] || ''}
                  onChange={(v) => store.setAiMapRow(u.label, v)}
                  options={[{ value: '', label: '（忽略）' },
                            ...store.rectLabels.map((l) => ({ value: l, label: l }))]} />
        </Space>
      ))}
    </Modal>
  )
}

function PreviewModal({ store }: { store: WsDialogStore }) {
  return (
    <Modal open={store.previewOpen} title="训练样本预览" width={780} onCancel={() => store.closePreview()}
      footer={
        <Space>
          <Checkbox checked={store.previewRaw} onChange={(e) => store.setPreviewRaw(e.target.checked)}>
            显示原始标注 JSON（box 的原始结构）
          </Checkbox>
          <Button size="small" onClick={() => { void store.copyPreview() }}>复制 JSON</Button>
          <Button onClick={() => store.closePreview()}>关闭</Button>
        </Space>
      }>
      {store.previewLoading ? '加载中…' : !store.previewData ? null : (
        <Space direction="vertical" style={{ width: '100%' }} size={8}>
          <div style={{ fontSize: 12.5, color: '#666' }}>
            这条任务同步成标注集、再发布之后，训练用的 JSONL 里就是下面这一行。
            <code>messages</code> 是喂给大模型的问答，<code>images</code> 是图片的本机路径。
          </div>
          <pre style={{ background: '#fafafa', border: '1px solid #eee', borderRadius: 4, padding: 8,
                        fontSize: 12, maxHeight: 260, overflow: 'auto', margin: 0 }}>{store.previewJson}</pre>
          {store.previewRaw ? (
            <pre style={{ background: '#fafafa', border: '1px solid #eee', borderRadius: 4, padding: 8,
                          fontSize: 12, maxHeight: 200, overflow: 'auto', margin: 0 }}>{store.previewRawJson}</pre>
          ) : null}
          {store.previewData.note ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{store.previewData.note}</Typography.Text>
          ) : null}
          <div style={{ fontSize: 12, color: '#888' }}>
            注意：标注还没保存时这里看到的是上一条已保存的标注；先在面板里点「保存并下一条」再预览。
          </div>
        </Space>
      )}
    </Modal>
  )
}

/** Vue fmt:3817 —— 历史时间显示（月/日 时:分）；解析不了的串截前 16 字符兜底。 */
function fmtHistoryTime(s: unknown): string {
  if (!s) return ''
  const d = new Date(String(s))
  if (isNaN(d.getTime())) return String(s).slice(0, 16)
  return d.toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit',
                                     hour: '2-digit', minute: '2-digit' })
}

function AutoPanel({ model, store }: { model: WorkbenchModel; store: WsDialogStore }) {
  const navigate = useNavigate()
  const r = model.syncResult
  const anns = model.annotations
  const hist = anns.slice().reverse()   // Vue history:3764 = annotations 倒序（最新在前）
  return (
    <Drawer open={store.autoPanelOpen} title="任务数据" width={420}
            onClose={() => store.setAutoPanelOpen(false)}>
      <Space direction="vertical" style={{ width: '100%' }} size={12}>
        {/* 标注历史（Vue:943-965，BUG-PARITY-3 回搬）：自己的=载入这条；
            别人的=复制到我的标注——老版注释逐句：看别人的标注只能复制一份来改
            （保存时新建自己的），不会覆盖对方。载入链语义在 model.loadHistory。 */}
        <div data-testid="p3-hist-sec"
             style={{ border: '1px solid #d9d9d9', borderRadius: 6, padding: '8px 10px' }}>
          <div style={{ fontWeight: 600, marginBottom: 6 }}>
            标注历史{anns.length ? <Tag style={{ marginLeft: 6 }}>{anns.length}</Tag> : null}
          </div>
          {hist.map((a: Record<string, any>) => (
            <div key={String(a.id)} data-testid={'p3-hist-' + a.id}
                 style={{ borderTop: '1px solid #f0f0f0', padding: '6px 0' }}>
              <Space size={6} wrap>
                <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>#{a.id}</span>
                {a.mine
                  ? <Tag color="green">我的</Tag>
                  : <Tag title={'作者：' + (a.by || '其他标注员')}>{a.by || '其他标注员'}</Tag>}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {fmtHistoryTime(a.updated_at || a.created_at)}
                </Typography.Text>
              </Space>
              <div style={{ margin: '4px 0', fontSize: 12 }}>{a.answer || '（空）'}</div>
              <Button size="small" data-testid={'p3-load-' + a.id}
                      onClick={() => { void model.loadHistory(a) }}>
                {a.mine ? '载入这条' : '复制到我的标注'}
              </Button>
            </div>
          ))}
          {!anns.length
            ? <div style={{ color: '#888', fontSize: 12 }}>还没有人工标注。</div>
            : null}
        </div>
        <div>
          <div style={{ marginBottom: 4 }}>同步目标标注集</div>
          <Select style={{ width: '100%' }} placeholder="（新建标注集）" allowClear
                  value={model.datasetId || undefined}
                  onChange={(v) => { void model.setDataset(v || '') }}
                  options={model.lsDatasets.map((d: Record<string, any>) => ({
                    value: d.id,
                    label: d.name + ' · ' + d.n_items + ' 条 · ' + d.status
                      + (model.datasetOwner(d.id) ? ` · 属于项目 #${model.datasetOwner(d.id)}` : ''),
                  }))} />
          <Button size="small" style={{ marginTop: 8 }} loading={model.syncing}
                  disabled={!model.project?.workbench}
                  onClick={() => { void model.sync() }}>
            {model.syncing ? '同步中…' : '同步'}
          </Button>
        </div>
        <div>
          <Checkbox checked={store.autoSync} onChange={() => { store.autoSync = !store.autoSync; store.touch(); void store.toggleAutoSync() }}>
            自动对账
          </Checkbox>
          <div style={{ fontSize: 12, color: '#888', marginTop: 2 }}>每 {Math.round((store.syncInterval || 300) / 60)} 分钟自动对账一次</div>
          {store.syncLast && (
            <div style={{ fontSize: 12, marginTop: 4 }}>
              上次对账 {store.syncLast.at}{' '}
              <span style={{ color: store.syncLast.ok ? '#1a8a4a' : '#c0392b' }}>
                {store.syncLast.ok
                  ? `新增 ${store.syncLast.inserted || 0} / 更新 ${store.syncLast.updated || 0}`
                  : '失败：' + store.syncLast.error}
              </span>
            </div>
          )}
        </div>
        <div>
          <Space>
            <Checkbox checked={store.autoTrain}
                      onChange={() => { store.autoTrain = !store.autoTrain; store.touch(); void store.toggleAutoTrain() }}>
              自动训练
            </Checkbox>
            <InputNumber size="small" min={1} max={1000} value={store.autoTrainMin}
                         onChange={(v) => store.setAutoTrainInput(Number(v) || 10)}
                         onBlur={() => { void store.saveAutoTrainMin() }}
                         onPressEnter={() => { void store.saveAutoTrainMin() }} />
            <span style={{ fontSize: 12 }}>条新增后发起</span>
          </Space>
          {store.autoTrainInfo && store.autoTrainInfo.last_job_id ? (
            <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
              上次自动训练 {store.autoTrainInfo.last_job_at}{' '}
              <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>{store.autoTrainInfo.last_job_id}</span>
            </div>
          ) : null}
        </div>
        {r && (
          <Alert type="success" message={
            <Space direction="vertical" size={6}>
              <span>新增 {r.inserted} · 更新 {r.updated || 0}
                {r.removed ? ` · 清理旧版本 ${r.removed}` : ''} · 训练集 {String(r.dataset_id ?? '')}</span>
              <Space>
                <Button size="small" onClick={() => navigate('/mos/datasets?ds=' + encodeURIComponent(String(r.dataset_id || '')))}>查看训练集 →</Button>
                <Button size="small" onClick={() => navigate('/mos/jobs?method=sft&dataset=' + encodeURIComponent(String(r.dataset_id || '')))}>去训练 →</Button>
              </Space>
            </Space>
          } />
        )}
      </Space>
    </Drawer>
  )
}
