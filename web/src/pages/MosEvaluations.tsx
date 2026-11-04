import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, AutoComplete, Button, Card, Collapse, Divider, Dropdown, Form, Input,
  InputNumber, Modal, Pagination, Progress, Segmented, Select, Space, Table, Tag,
  Typography, message,
} from 'antd'
import type { MenuProps, TableProps } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import {
  createEval, deleteEval, getEval, getSettings, judgeSelfcheck, listDatasets, listDeployments,
  listEvals, runEval, stopEval,
} from '../api/mos'
import type {
  MosDatasetLite, MosDeploymentLite, MosEval, MosEvalItemResult, MosEvalSummary, MosJudgeSelfcheck,
} from '../api/mos'

/**
 * 模型评测（MOS）——REQ-UNIFY-4 重写页。
 * 功能对照 mos/platform/frontend/src/views/Evaluations.vue（429 行）逐动作等价：
 * 列表/状态 chips/运行中 2.5s 轮询/新建评测（数据集+端点候选+可选对比对象）/运行/停止/
 * 行刷新/删除/裁判引擎切换+自检弹窗/进度条/汇总均分条/逐题明细/基准集体检提示/
 * 深链带参发起（?url=&name=&dataset=，「部署」页「去评测 →」口径）。
 * 三态铺满、右键上下文菜单、写操作确认均按 REQ-UNIFY-2 沉淀 checklist（v437 基准）。
 * 通道：/api/mos/evals/**（会话代持 + mos:access，REQ-UNIFY-1）；api_key 只进不出
 * （evals.py:51 _public_judge 把 key 换成 key_set 标记才回传，页面不显示明文）。
 */

const STATUS_LABEL: Record<string, string> = { draft: '未跑', running: '运行中', done: '已完成', failed: '失败' }
const STATUS_COLOR: Record<string, string> = { draft: 'default', running: 'processing', done: 'success', failed: 'error' }

const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }

/** 逐题得分着色带：与 Evaluations.vue:104 同一阈值（≥8 好 / ≥5 及格 / 其余差 / 未判定）。 */
function scoreTag(nm: string, score: number | null | undefined) {
  const label = score === null || score === undefined ? `${nm} · 未判定` : `${nm} · ${score} 分`
  if (score === null || score === undefined) return <Tag color="red">{label}</Tag>
  if (score >= 8) return <Tag color="green">{label}</Tag>
  if (score >= 5) return <Tag color="gold">{label}</Tag>
  return <Tag color="red">{label}</Tag>
}

type JudgeDefaults = { ext_base_url: string; ext_model: string; ext_has_key: boolean; llm_base_url: string }

type EvalFormValues = {
  name: string
  dataset_id: string
  limit: number
  a_name: string
  a_url: string
  a_model?: string
  b_name?: string
  b_url?: string
  b_model?: string
}

function defaultsFrom(s: Record<string, string> | null): JudgeDefaults {
  return {
    ext_base_url: s?.['ext.base_url'] || '',
    ext_model: s?.['ext.model'] || '',
    // 密钥字段服务端只回掩码（stubs.py SECRET_MASK），非空即「已配置」；Evaluations.vue:298 同口径
    ext_has_key: !!s?.['ext.api_key'],
    llm_base_url: s?.['llm.base_url'] || '',
  }
}

export default function MosEvaluations() {
  const [searchParams] = useSearchParams()
  const [evals, setEvals] = useState<MosEval[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<(Error & { status?: number }) | null>(null)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [creating, setCreating] = useState(false)
  const [runningId, setRunningId] = useState('')
  const [stoppingId, setStoppingId] = useState('')
  const [datasets, setDatasets] = useState<MosDatasetLite[]>([])
  const [showForm, setShowForm] = useState(false)
  const [showB, setShowB] = useState(false)
  const [engine, setEngine] = useState<'external' | 'local'>('external')
  const [defaults, setDefaults] = useState<JudgeDefaults>({ ext_base_url: '', ext_model: '', ext_has_key: false, llm_base_url: '' })
  const [endpoints, setEndpoints] = useState<{ value: string; label: string }[]>([])
  // 裁判自检弹窗
  const [scOpen, setScOpen] = useState(false)
  const [scBusy, setScBusy] = useState(false)
  const [scResult, setScResult] = useState<MosJudgeSelfcheck | null>(null)
  const [scError, setScError] = useState('')
  const [form] = Form.useForm<EvalFormValues>()
  const loadedOnce = useRef(false)
  const bootRef = useRef(false)

  const runningCount = useMemo(() => evals.filter((e) => e.status === 'running').length, [evals])

  const load = useCallback(async () => {
    // 首屏才进 LoadingState；轮询/重刷不闪骨架（对照 Vue 的 if (!evals.length) loading=true）
    if (!loadedOnce.current) setLoading(true)
    try {
      setEvals(await listEvals())
      setLoadError(null)
    } catch (e: any) {
      // 加载失败不能画成「还没有评测」：用户会以为数据被清空、去重复创建（Evaluations.vue:323 同教训）
      setLoadError(e)
      message.error('加载失败: ' + (e?.message || '未知错误'))
    } finally {
      loadedOnce.current = true
      setLoading(false)
    }
    // 数据集候选失败不影响评测列表（对照 Evaluations.vue:327 的 try/catch）
    try { setDatasets(await listDatasets()) } catch { /* ignore */ }
  }, [])

  /** 引擎地址候选：部署列表（平台自建模型服务）+ 现役端点 + 外部端点（Evaluations.vue:278-292）。 */
  const loadEndpoints = useCallback(async (d: JudgeDefaults) => {
    const out: { value: string; label: string }[] = []
    try {
      const deps: MosDeploymentLite[] = await listDeployments()
      for (const dep of deps) {
        // swift_pt 实例跑在代理机器上：后端给出可直接访问的 base_url，前端不能拼 127.0.0.1
        // （否则评测全部连接失败，Evaluations.vue:283-286 注释）
        out.push({
          value: dep.base_url || `http://127.0.0.1:${dep.port}/v1`,
          label: `${dep.model_name || dep.model_id || ''} ${dep.model_version || ''} · :${dep.port}${dep.alive ? ' · 运行中' : ''}`.trim(),
        })
      }
    } catch { /* 读不到就只给下面两项 */ }
    if (d.llm_base_url) out.push({ value: d.llm_base_url, label: '本机现役端点（部署页写入）' })
    if (d.ext_base_url) out.push({ value: d.ext_base_url, label: `外部模型 · ${d.ext_model || ''}` })
    setEndpoints(out)
  }, [])

  // 挂载序 = Evaluations.vue:409-422：列表 → 设置默认值 → 端点候选 → 深链带参直接开单
  useEffect(() => {
    if (bootRef.current) return
    bootRef.current = true
    void (async () => {
      await load()
      const s = await getSettings().catch(() => null)
      const d = defaultsFrom(s)
      setDefaults(d)
      await loadEndpoints(d)
      const url = searchParams.get('url') || ''
      if (url) {
        setShowB(false)
        form.setFieldsValue({
          name: '',
          dataset_id: searchParams.get('dataset') || '',
          limit: 10,
          a_name: searchParams.get('name') || '现役模型(本地)',
          a_url: url,
          a_model: '',
          b_name: '',
          b_url: d.llm_base_url || 'http://127.0.0.1:8000/v1',
          b_model: '',
        })
        setShowForm(true)
      }
    })()
  }, [load, loadEndpoints, form, searchParams])

  // 有运行中的评测就 2.5s 轮询进度（Evaluations.vue:320-321 同款节流；只拉列表，失败等下一轮）
  useEffect(() => {
    if (!runningCount) return
    const t = window.setInterval(() => {
      listEvals().then(setEvals).catch(() => { /* 轮询失败静默，下一轮再试 */ })
    }, 2500)
    return () => window.clearInterval(t)
  }, [runningCount])

  const pageCount = Math.max(1, Math.ceil(evals.length / pageSize))
  // 列表变少/每页条数变大时当前页可能已不存在，退回最后一页（对齐对方 usePager 的 watch）
  useEffect(() => { if (page > pageCount) setPage(pageCount) }, [page, pageCount])
  const paged = evals.slice((page - 1) * pageSize, page * pageSize)

  const judgeLabel = engine === 'external' ? '外部模型（更准，要钱）' : '本地模型（离线、免费）'

  /** 打开「新建评测」：默认只评一个对象（预填现役本地引擎）；对比对象可选展开（Evaluations.vue:303-312）。 */
  function openForm() {
    const base = defaults.llm_base_url || 'http://127.0.0.1:8000/v1'
    setShowB(false)
    form.setFieldsValue({
      name: '',
      dataset_id: form.getFieldValue('dataset_id') || '',
      limit: 10,
      a_name: '现役模型(本地)', a_url: base, a_model: '',
      b_name: '', b_url: base, b_model: '',
    })
    setShowForm(true)
  }

  async function submitCreate() {
    if (creating) return
    let v: EvalFormValues
    try { v = await form.validateFields() } catch { return }
    const cands = [{ name: v.a_name || '评测对象', base_url: v.a_url, model: v.a_model || '' }]
    if (showB) cands.push({ name: v.b_name || '对比对象', base_url: v.b_url || '', model: v.b_model || '' })
    setCreating(true)
    try {
      await createEval({
        name: v.name,
        dataset_id: v.dataset_id,
        candidates: cands,
        // 地址/模型/Key 全由服务端按 engine 从设置解析，浏览器不接触 Key（Evaluations.vue:266-275）
        judge: { engine },
        limit: Math.max(1, Math.min(1000, Number(v.limit) || 10)),
      })
      setShowForm(false)
      message.success('已创建，点「运行」开跑')
      await load()
    } catch (e: any) {
      message.error('创建失败: ' + (e?.message || '未知错误'))
    } finally {
      setCreating(false)
    }
  }

  async function doRun(e: MosEval) {
    if (runningId) return
    setRunningId(e.id)
    try {
      const limit = e.eval_limit || 10
      await runEval(e.id, limit)
      message.success(`已开跑（最多 ${limit} 题）`)
      await load()
    } catch (err: any) { message.error(err?.message || '发起运行失败') }
    finally { setRunningId('') }
  }

  function doStop(e: MosEval) {
    if (stoppingId) return
    Modal.confirm({
      title: '停止评测',
      content: `停止评测「${e.name}」？当前正在等待的模型调用会先结束。`,
      okText: '停止',
      okButtonProps: { danger: true },
      onOk: async () => {
        setStoppingId(e.id)
        try {
          await stopEval(e.id)
          message.success('已请求停止，正在收尾')
          await load()
        } catch (err: any) { message.error(err?.message || '停止失败') }
        finally { setStoppingId('') }
      },
    })
  }

  /** 行刷新：走详情接口（带服务端实时 running 标记，evals.py:130；Vue 的 refresh(e) 同款）。 */
  async function doRefreshRow(e: MosEval) {
    try {
      const d = await getEval(e.id)
      setEvals((rows) => rows.map((x) => (x.id === e.id ? d : x)))
    } catch (err: any) { message.error(err?.message || '刷新失败') }
  }

  function doRemove(e: MosEval) {
    if (e.status === 'running') { message.error('评测在运行中，先点「停止」'); return }
    Modal.confirm({
      title: '删除评测',
      content: `删除评测「${e.name}」？`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteEval(e.id)
          message.success('已删除')
          await load()
        } catch (err: any) { message.error(err?.message || '删除失败') }
      },
    })
  }

  async function doSelfcheck() {
    setScOpen(true)
    setScBusy(true)
    setScResult(null)
    setScError('')
    try {
      const r = await judgeSelfcheck({ judge: { engine } })
      setScResult(r)
      if (r.ok) message.success(r.verdict); else message.error(r.verdict)
    } catch (e: any) {
      // 兜底文案必须写进 scError —— Vue 版从不赋值、永远显示「未知错误」的老坑（Evaluations.vue:402-404）
      setScError(e?.message || '未知错误')
      message.error('自检失败: ' + (e?.message || '未知错误'))
    } finally {
      setScBusy(false)
    }
  }

  function goDataset(e: MosEval) {
    // 题目/数据集页（Vue 的 /qa）属后续批次重写卡；本批不挂死链（checklist 第 4 条）
    message.info(`MOS 训练集页正在迁移，稍后可在此查看训练集「${e.dataset_id}」的题目与发布状态`)
  }

  function goDeployHint() {
    // 部署页（Vue 的 /deploy）同属后续批次；深链 ?url= 已预留，页面迁移后直接挂
    message.info('MOS 部署页正在迁移：本地模型要先在部署页启动，评测对象才连得上；外部模型可直接填 base_url')
  }

  /** 卡片右键上下文菜单：与底部按钮同源（v437 基准 / MosModels cardMenu 模板）。 */
  const cardMenu = (e: MosEval): MenuProps => ({
    items: [
      e.status === 'running' ? { key: 'stop', label: '停止' } : { key: 'run', label: '运行' },
      { key: 'refresh', label: '刷新' },
      { type: 'divider' },
      { key: 'remove', label: '删除', danger: true, disabled: e.status === 'running' },
    ],
    onClick: ({ key }) => {
      if (key === 'run') void doRun(e)
      else if (key === 'stop') doStop(e)
      else if (key === 'refresh') void doRefreshRow(e)
      else if (key === 'remove') doRemove(e)
    },
  })

  /** 汇总列：「领先」徽标只在对比局（summary.length>1）且第一名已出分时给（Evaluations.vue:84）。 */
  const summaryCols = (total: number): TableProps<MosEvalSummary>['columns'] => [
    {
      title: '评测对象',
      dataIndex: 'name',
      render: (name: string, s: MosEvalSummary, i: number) => (
        <Space size={4} wrap>
          <b>{name}</b>
          {i === 0 && total > 1 && s.avg !== null ? <Tag color="green">领先</Tag> : null}
          {s.err ? <Tag color="red">{s.err} 题裁判失败</Tag> : null}
        </Space>
      ),
    },
    {
      title: '均分',
      dataIndex: 'avg',
      width: '34%',
      render: (avg: number | null) => (
        <Space size={9} style={{ width: '100%' }}>
          <div style={{ flex: 1, minWidth: 80 }}>
            {avg === null || avg === undefined ? '—' : (
              <Progress percent={Math.max(0, Math.min(100, (avg / 10) * 100))} showInfo={false} size="small" />
            )}
          </div>
          <span style={{ ...MONO, fontSize: 12 }}>{avg === null || avg === undefined ? '—' : avg}</span>
        </Space>
      ),
    },
    { title: '胜场', dataIndex: 'win', align: 'right', width: 70 },
    { title: '题数', dataIndex: 'n', align: 'right', width: 70 },
  ]

  function renderItems(items: MosEvalItemResult[]) {
    return (
      <div>
        {items.map((it) => (
          <Card key={it.item_id} size="small" style={{ marginBottom: 8 }}>
            <div style={{ fontWeight: 600 }}>Q：{it.q}</div>
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', margin: '4px 0' }}>
              参考答案：{it.ref}
            </Typography.Text>
            {Object.entries(it.answers || {}).map(([nm, a]) => (
              <div key={nm} style={{ marginTop: 6 }}>
                {scoreTag(nm, it.scores?.[nm])}
                <div style={{ fontSize: 12, color: '#888', marginTop: 3 }}>{a || '(无回答)'}</div>
                {it.errors?.[nm] ? <div style={{ fontSize: 11.5, color: '#cf1322' }}>{it.errors[nm]}</div> : null}
              </div>
            ))}
          </Card>
        ))}
      </div>
    )
  }

  function renderCard(e: MosEval) {
    const summary = e.result?.summary
    const items = e.result?.items
    const warn = e.result?.ref_warn
    return (
      <Dropdown key={e.id} trigger={['contextMenu']} menu={cardMenu(e)}>
        <Card
          size="small"
          hoverable
          id={`eval-${e.id}`}
          title={<span style={{ fontWeight: 600, fontSize: 14 }}>{e.name}</span>}
          extra={<Tag color={STATUS_COLOR[e.status] || 'default'}>{STATUS_LABEL[e.status] || e.status}</Tag>}
        >
          <div style={{ ...MONO, fontSize: 11, color: '#999' }}>
            {e.id} ·{' '}
            <Typography.Link onClick={() => goDataset(e)} title="回到训练集，查看题目和发布状态">
              训练集 {e.dataset_id}
            </Typography.Link>
            {' '}· 最多 {e.eval_limit || 10} 题
          </div>

          <Space size={4} wrap style={{ marginTop: 10 }}>
            {(e.candidates || []).map((c) => (
              <Tag key={c.name}>{c.name} <span style={MONO}>{c.model || 'auto'}</span></Tag>
            ))}
          </Space>

          {e.status === 'running' && (
            <div style={{ marginTop: 12 }}>
              <div style={{ fontSize: 12.5, color: '#888', marginBottom: 4 }}>进度 {Math.round((e.progress || 0) * 100)}%</div>
              <Progress percent={Math.round((e.progress || 0) * 100)} showInfo={false} size="small" />
            </div>
          )}

          {warn && warn.count ? (
            <Alert
              style={{ marginTop: 12 }}
              type="warning"
              showIcon
              message={`基准集体检：${warn.count} 题参考答案过短/为空`}
              description={(
                <div style={{ fontSize: 11.5 }}>
                  这种题的「参考答案」本身没内容，裁判无从判分 —— 本次均分仅供参考。例：
                  {(warn.samples || []).map((w, i) => (
                    <span key={i}>「{(w.q || '').slice(0, 18)}… → {w.ref || '（空）'}」 </span>
                  ))}
                </div>
              )}
            />
          ) : null}

          {summary && (
            <div style={{ marginTop: 14 }}>
              <Table size="small" rowKey="name" columns={summaryCols(summary.length)} dataSource={summary} pagination={false} />
              {items && items.length > 0 && (
                <Collapse
                  size="small"
                  ghost
                  style={{ marginTop: 10 }}
                  items={[{ key: 'items', label: `查看逐题明细（${items.length} 题）`, children: renderItems(items) }]}
                />
              )}
            </div>
          )}

          {e.error ? <div style={{ color: '#cf1322', marginTop: 10, fontSize: 12.5 }}>{e.error}</div> : null}

          <Space size={4} wrap style={{ marginTop: 12 }}>
            {e.status === 'running' ? (
              <Button size="small" danger loading={stoppingId === e.id} onClick={() => doStop(e)}>
                {stoppingId === e.id ? '停止中…' : '停止'}
              </Button>
            ) : (
              <Button size="small" type="primary" loading={runningId === e.id} onClick={() => void doRun(e)}>
                {runningId === e.id ? '启动中…' : '运行'}
              </Button>
            )}
            <Button size="small" onClick={() => void doRefreshRow(e)}>刷新</Button>
            <Button size="small" danger disabled={e.status === 'running'} onClick={() => doRemove(e)}>删除</Button>
          </Space>
        </Card>
      </Dropdown>
    )
  }

  return (
    <div>
      <Space style={{ marginBottom: 8, width: '100%', justifyContent: 'space-between' }} wrap>
        <Space wrap>
          <Tag>共 {evals.length} 次评测</Tag>
          <Tag color="blue">运行中 {runningCount}</Tag>
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openForm}>新建评测</Button>
        </Space>
      </Space>

      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 12 }}>
        评测对象是已启动的模型服务：本地模型先在
        <Typography.Link onClick={goDeployHint}> 部署 </Typography.Link>
        启动；部署卡上的「去评测 →」会自动带上端点（本页支持 ?url=&amp;name=&amp;dataset= 深链）。外部模型可直接填 base_url。
      </Typography.Text>

      <Card
        size="small"
        title="裁判引擎"
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>谁来打分 —— 新建评测和「测裁判」都用这里的设置</Typography.Text>}
        style={{ marginBottom: 12 }}
      >
        <Space wrap style={{ alignItems: 'center' }}>
          <Segmented
            value={engine}
            onChange={(v) => setEngine(v as 'external' | 'local')}
            options={[
              { value: 'external', label: '外部模型（更准）' },
              { value: 'local', label: '本地模型（离线免费）' },
            ]}
          />
          <Button onClick={() => void doSelfcheck()}>测一下裁判可靠吗</Button>
        </Space>
        <div style={{ fontSize: 11.5, color: '#888', marginTop: 6 }}>
          {engine === 'external' ? (
            <>
              用「设置 → 外部模型」：<span style={MONO}>{defaults.ext_base_url || '（未配置）'}</span> ·{' '}
              {defaults.ext_model || '—'} ·{' '}
              {defaults.ext_has_key
                ? <span style={{ color: '#389e0d' }}>Key 已配置</span>
                : <span style={{ color: '#cf1322' }}>Key 未配置 → 到 mos「设置」页填写（该页在后续迁移批次）</span>}
            </>
          ) : (
            <>
              用「设置」里的本地推理端点：<span style={MONO}>{defaults.llm_base_url || '—'}</span>
              （由「部署」页「设为当前使用」写入）
            </>
          )}
        </div>
      </Card>

      {/* 三态铺满（v437 / REQ-UNIFY-2 checklist）：错误 > 首屏加载 > 空态 > 内容 */}
      {loadError ? (
        <ErrorState
          title={loadError.status === 403 ? '没有权限' : '评测列表加载失败'}
          message={loadError.status === 403
            ? '当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。'
            : loadError.message || '未知错误'}
          onRetry={() => void load()}
        />
      ) : loading && evals.length === 0 ? (
        <LoadingState rows={6} />
      ) : evals.length === 0 ? (
        <EmptyState
          title="还没有评测"
          hint="拿同一批题给模型打分，想看差异就加一个对比对象。"
          action={<Button type="primary" icon={<PlusOutlined />} onClick={openForm}>新建评测</Button>}
        />
      ) : (
        <>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {paged.map(renderCard)}
          </div>
          <Pagination
            style={{ marginTop: 12, textAlign: 'right' }}
            current={page}
            pageSize={pageSize}
            total={evals.length}
            showSizeChanger
            pageSizeOptions={[10, 20, 50]}
            onChange={(p, s) => { setPage(s !== pageSize ? 1 : p); setPageSize(s) }}
          />
        </>
      )}

      <Modal
        open={showForm}
        title="新建评测"
        width={720}
        okText={creating ? '创建中…' : '创建'}
        confirmLoading={creating}
        onCancel={() => setShowForm(false)}
        onOk={() => void submitCreate()}
      >
        <Form form={form} layout="vertical" requiredMark={false}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', columnGap: 12 }}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '名称必填' }]}>
              <Input placeholder="如：LoRA-AWQ vs 官方 AWQ" />
            </Form.Item>
            <Form.Item name="dataset_id" label="训练集" rules={[{ required: true, message: '训练集必选' }]}>
              <Select
                placeholder="— 选训练集 —"
                options={datasets.map((d) => ({ value: d.id, label: `${d.name}（${d.n_items ?? 0} 题）` }))}
              />
            </Form.Item>
            <Form.Item name="limit" label="题数上限">
              <InputNumber min={1} max={1000} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item label="裁判" extra="在页面顶部「裁判引擎」里选 / 改">
              <Input value={judgeLabel} disabled />
            </Form.Item>
          </div>

          <Divider style={{ margin: '4px 0 12px' }}>评测对象</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr 1fr', columnGap: 12 }}>
            <Form.Item name="a_name" label="名称"><Input /></Form.Item>
            <Form.Item name="a_url" label="引擎地址" rules={[{ required: true, message: '引擎地址必填' }]}>
              <AutoComplete options={endpoints} placeholder="从部署列表选，或手填" />
            </Form.Item>
            <Form.Item name="a_model" label="模型名"><Input placeholder="留空 = 自动发现" /></Form.Item>
          </div>
          <Space style={{ marginBottom: 12 }}>
            {showB
              ? <Button size="small" onClick={() => setShowB(false)}>去掉对比对象</Button>
              : <Button size="small" onClick={() => setShowB(true)}>+ 加一个对比对象（可选）</Button>}
          </Space>

          {showB && (
            <>
              <Divider style={{ margin: '4px 0 12px' }}>对比对象</Divider>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr 1fr', columnGap: 12 }}>
                <Form.Item name="b_name" label="名称"><Input placeholder="如：官方 AWQ 版" /></Form.Item>
                <Form.Item name="b_url" label="引擎地址">
                  <AutoComplete options={endpoints} placeholder="从部署列表选，或手填" />
                </Form.Item>
                <Form.Item name="b_model" label="模型名"><Input placeholder="留空 = 自动发现" /></Form.Item>
              </div>
              <Typography.Text type="secondary" style={{ fontSize: 11.5 }}>
                注：同一台 vLLM 同时只 serve 一个模型 —— 比两个本地版本要先切版本再评；也可以一个本地、一个外部。
              </Typography.Text>
            </>
          )}
        </Form>
      </Modal>

      <Modal
        open={scOpen}
        title="裁判自检（裁判可靠吗？）"
        width={740}
        onCancel={() => setScOpen(false)}
        footer={[
          <Button key="rerun" disabled={scBusy} onClick={() => void doSelfcheck()}>重跑</Button>,
          <Button key="close" type="primary" onClick={() => setScOpen(false)}>关闭</Button>,
        ]}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12.5 }}>
          用固定样本验裁判：正样本（照抄参考答案）该高分（≥7），负样本（答错对象/编造/不相关）该低分（≤4）。
          <b>裁判不合格 = 评测结果不可信</b>，先修裁判或换更强模型（如外部 API）再评。
        </Typography.Paragraph>
        {scResult ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <Tag color={scResult.ok ? 'success' : 'error'} style={{ alignSelf: 'flex-start' }}>{scResult.verdict}</Tag>
            {(scResult.cases || []).map((c, i) => (
              <Card key={i} size="small">
                <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
                  <b style={{ fontSize: 12.5 }}>{c.name}</b>
                  <Space size={8}>
                    <Tag color={c.pass ? 'success' : 'error'}>{c.pass ? '通过' : '不合格'}</Tag>
                    <span style={MONO}>{c.score === null || c.score === undefined ? '未评分' : `${c.score} 分`}</span>
                  </Space>
                </Space>
                <div style={{ fontSize: 11.5, color: '#888', marginTop: 5 }}>
                  期望：{c.expect === 'high' ? '高分' : '低分'}　裁判判定：{c.verdict || '—'}
                </div>
                <div style={{ fontSize: 11.5, color: '#888' }}>理由：{c.reason || '—'}</div>
              </Card>
            ))}
          </div>
        ) : scBusy ? (
          <div style={{ fontSize: 12.5, color: '#888' }}>⏳ 正在跑 4 个样本…（约 10~30 秒）</div>
        ) : (
          <div style={{ fontSize: 12.5, color: '#cf1322' }}>
            自检没有跑起来：{scError || '未知错误'}。检查裁判端点 / Key 后点「重跑」。
          </div>
        )}
      </Modal>
    </div>
  )
}
