import { Fragment, useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Collapse, Row, Space, Statistic, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableProps } from 'antd'
import {
  ApiOutlined, AuditOutlined, BarChartOutlined, BellOutlined, CloudServerOutlined,
  CloseCircleOutlined, CodeOutlined, DatabaseOutlined, DeploymentUnitOutlined,
  FileDoneOutlined, SafetyCertificateOutlined, SearchOutlined, SwapOutlined,
  SyncOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { listNightReports, type NightReport, type NightReportAgg } from '../api/flywheel'

type Overview = {
  env: string
  version: string
  projects: number
  workflows: number
  dataSources: number
  publishedServices: number
  qualityRules: number
  openAlerts: number
  runningInstances: number
  todayInstances: number
  lineageEdges: number
  syncTasks: number
}

type TechRow = {
  layer: string
  component: string
  why: string
}

type Block = {
  title: string
  sub: string
  tip: string
  anchor: string
}

const ACCESS: Block[] = [
  { title: '用户 / Agent', sub: '浏览器 · MCP · REST', tip: '人和智能体通过控制台或 API 访问平台', anchor: 'intro-access' },
  { title: 'Web 控制台', sub: 'React 18 + Ant Design 5', tip: '开发、运维、治理、服务与安全的统一界面', anchor: 'intro-access' },
  { title: '平台 API / MCP', sub: 'REST + 14 个工具', tip: '所有动作都有 JSON Schema 和语义描述，Agent 可直接调用', anchor: 'intro-access' },
  { title: 'Keycloak', sub: 'OIDC / PKCE', tip: '统一身份认证，支持 SSO 与后续 RBAC 映射', anchor: 'intro-access' },
]

const COMPUTE: Block[] = [
  { title: 'Spring Boot 后端', sub: '项目 · 版本 · 发布 · 审计', tip: '平台控制面：元数据、发布、运维、质量、服务、告警与安全', anchor: 'intro-control' },
  { title: 'Prefect 3', sub: '调度 · 运行 · 日志', tip: '运行实例、任务日志、重试与补数都由它执行', anchor: 'intro-compute' },
  { title: 'SeaTunnel', sub: '数据集成', tip: 'JDBC / Doris 同步，后续可扩展 MySQL CDC、Kafka', anchor: 'intro-compute' },
  { title: '数据服务', sub: 'SQL → API', tip: '把只读 SQL 发布成 Token 鉴权的 HTTP 接口', anchor: 'intro-control' },
]

const STORE: Block[] = [
  { title: 'PostgreSQL', sub: '平台元数据', tip: '项目、版本、发布、审计、告警等控制面数据', anchor: 'intro-store' },
  { title: 'Doris', sub: 'OLAP + 向量检索', tip: 'SQL 查询、质量校验与 RAG 向量检索', anchor: 'intro-store' },
  { title: 'MinIO', sub: 'S3 对象存储', tip: '知识库文档、fileset 与导出文件', anchor: 'intro-store' },
  { title: 'Gravitino', sub: 'Catalog / Fileset', tip: '统一纳管 Doris、MinIO 与 AI 模型元数据', anchor: 'intro-store' },
]

const OPS: Block[] = [
  { title: 'Prometheus', sub: '指标采集', tip: 'Doris FE/BE、平台服务指标，保留 7 天', anchor: 'intro-ops' },
  { title: 'Grafana', sub: '看板与趋势', tip: '自动 provision 的 data-ai-agent DEV 看板', anchor: 'intro-ops' },
  { title: '安全审计', sub: '登录 · 发布 · 调用', tip: '所有关键动作留痕，可查 IP 与结果', anchor: 'intro-ops' },
  { title: '告警中心', sub: '失败 · 质量 · SLA', tip: '运行失败、质量未通过、运行超时自动告警', anchor: 'intro-ops' },
]

const TECH: TechRow[] = [
  { layer: '前端', component: 'React 18 + TypeScript + Ant Design 5 + Vite', why: '表格/表单/抽屉等企业组件成熟，界面密度与交互容易做到位；Vite 构建快，产物直接打进后端 jar。' },
  { layer: '后端', component: 'Spring Boot 3.3 + JDK 17', why: '事务、JDBC、定时任务、拦截器与审计生态成熟；单 jar 内嵌前端静态资源，部署只有一个镜像。' },
  { layer: '调度', component: 'Prefect 3（Server + Worker）', why: 'Python 原生，Deployment / Flow Run / 任务日志 API 完整；动态 DAG、重试、补数都能直接映射到平台模型。' },
  { layer: '数据集成', component: 'Apache SeaTunnel 2.3.9（Zeta）', why: '连接器覆盖 JDBC / Doris，后续加 MySQL CDC、Kafka 不需要换引擎；作业以 K8s Job 方式运行。' },
  { layer: '查询 / 向量', component: 'Doris 4.1（FE + 3 BE）', why: 'MySQL 协议、OLAP 性能好，并原生支持向量检索；同一套库同时承载 BI 查询与 RAG 知识库。' },
  { layer: '元数据', component: 'Gravitino 1.3.1', why: '统一纳管 Doris catalog、MinIO fileset 与 AI 模型，提供标准元数据 API，避免自研 catalog。' },
  { layer: '平台元数据库', component: 'PostgreSQL 16', why: 'ACID、JSONB、窗口函数齐全；项目、版本、发布、审计、告警等控制面数据都放这里。' },
  { layer: '对象存储', component: 'MinIO（3 节点）', why: 'S3 兼容、本地部署简单；存放知识库文档、fileset 与数据服务导出文件。' },
  { layer: '认证', component: 'Keycloak 26（OIDC + PKCE）', why: '标准 SSO，前端 Authorization Code + PKCE；后续接平台 RBAC 与角色映射不需要改认证协议。' },
  { layer: 'Agent 网关', component: '自研 MCP 网关（14 个只读工具）', why: '每个动作有名称、描述、JSON Schema、示例与语义元数据，Agent 可以直接理解并调用。' },
  { layer: '可观测', component: 'Prometheus 3 + Grafana 11', why: '指标采集/查询/看板的标准组合，Doris FE/BE 已经接入，避免自建时序存储。' },
  { layer: '容器平台', component: 'K3s（3 server + embedded etcd）', why: '在单机 3 VM 约束下做到控制面多数派；组件少、资源占用低，和标准 K8s API 兼容。' },
  { layer: '数据服务', component: '自研 SQL → API', why: '只读 SQL、命名参数、独立 Token、发布/下线与调用审计，适合把内部表快速开放给其他系统。' },
]

const FLOW = ['数据开发', '版本 / 发布', 'Prefect 调度', 'SQL / 同步任务', 'Doris · PG · MinIO', '质量 / 血缘', '数据服务 / 告警']

function TofuRow({ label, blocks }: { label: string; blocks: Block[] }) {
  const tone = label === '接入' ? 'tofu-access' : label === '执行' ? 'tofu-compute' : 'tofu-store'
  return (
    <div className={`tofu-row ${tone}`}>
      <div className="tofu-label">{label}</div>
      <div className="tofu-blocks">
        {blocks.map((block, idx) => (
          <Fragment key={block.title}>
            <Tooltip title={block.tip}>
              <a className="tofu-block" href={`#${block.anchor}`}>
                <span className="tofu-title">{block.title}</span>
                <span className="tofu-sub">{block.sub}</span>
              </a>
            </Tooltip>
            {idx < blocks.length - 1 ? <span className="tofu-arrow">→</span> : null}
          </Fragment>
        ))}
      </div>
    </div>
  )
}

/** 待办计数三态：undefined=取数中，err=取数失败（不伪装成 0）。 */
type TodoCount = { value?: number; err?: boolean }

const NO_TODO: TodoCount = {}

export default function Home() {
  const nav = useNavigate()
  const [data, setData] = useState<Overview>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  // REQ-UX-HELP-1 首屏改造：待办来自三个额外接口，任一失败只影响对应卡片
  const [approvals, setApprovals] = useState<TodoCount>(NO_TODO)
  const [pendingChanges, setPendingChanges] = useState<TodoCount>(NO_TODO)
  const [failedToday, setFailedToday] = useState<TodoCount>(NO_TODO)

  const load = useCallback(() => {
    setLoading(true)
    setError('')
    api<Overview>('/api/overview')
      .then((d) => setData(d))
      .catch((e: any) => setError(e?.message || '未知原因'))
      .finally(() => setLoading(false))
  }, [])
  useEffect(() => { load() }, [load])

  useEffect(() => {
    // 审批清单接口只返回待审批/待二审，长度即待办数
    api<{ status?: string }[]>('/api/access/requests?scope=approvals')
      .then((rows) => setApprovals({ value: rows.length }))
      .catch(() => setApprovals({ err: true }))
    // 变更单：一次拿全量在前端数 PENDING（与列表页同款口径）
    api<{ status?: string }[]>('/api/changes')
      .then((rows) => setPendingChanges({ value: rows.filter((r) => r.status === 'PENDING').length }))
      .catch(() => setPendingChanges({ err: true }))
    // 今日失败实例：后端按状态过滤，前端按当天业务时间计数
    api<{ started_at?: string }[]>('/api/ops/instances?state=failed&limit=500')
      .then((rows) => {
        const today = dayjs().format('YYYY-MM-DD')
        setFailedToday({ value: rows.filter((r) => (r.started_at || '').startsWith(today)).length })
      })
      .catch(() => setFailedToday({ err: true }))
  }, [])

  // REQ-NIGHT-LEARN-1 夜间学习晨报带：数据源=GET /api/flywheel/night/reports（一夜一岗一行），
  // 权限点位同后端 mos:flywheel——没点位的人这块不出，而不是出了再吃 403。
  // 异常圈单列成独立一栏（不混进「圈数/通过」的正常数字里），点行看该岗异常明细。
  const [night, setNight] = useState<NightReportAgg | null>(null)
  const [nightError, setNightError] = useState('')
  const [nightDetailKey, setNightDetailKey] = useState<string | null>(null)
  const loadNight = useCallback(() => {
    if (!can('mos:flywheel')) return
    listNightReports()
      .then((d) => { setNight(d); setNightError('') })
      .catch((e: any) => {
        // 老后端没这个面（404）也要出声：晨报取不到=夜学有没有在跑的唯一可见面
        setNight(null)
        setNightError(e?.message || '未取到晨报')
      })
  }, [])
  useEffect(() => { loadNight() }, [loadNight])

  const nightRows = night?.rows || []
  const nightDetail = nightRows.find((r) => `${r.nightDate}-${r.labelProjectId}` === nightDetailKey)

  const nightColumns: TableProps<NightReport>['columns'] = [
    {
      title: '岗位（标注项目）',
      key: 'post',
      render: (_: unknown, r: NightReport) => (
        // 行级锚点：晨报带每行一个 testid，验收/回归按岗位号定位行（night-report-row-<岗位号>）
        <span data-testid={`night-report-row-${r.labelProjectId}`}>
          {r.labelProjectTitle || `岗位 #${r.labelProjectId}`}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}> #{r.labelProjectId}</Typography.Text>
        </span>
      ),
    },
    {
      title: '夜间窗',
      key: 'window',
      width: 120,
      render: (_: unknown, r: NightReport) => (r.window || []).join('–') || '—',
    },
    { title: '圈数', dataIndex: 'runs', key: 'runs', width: 70 },
    { title: '通过', dataIndex: 'passed', key: 'passed', width: 70 },
    {
      // 异常单列（PRD 要求）：正常行显示 0，有异常用红 Tag 顶出来，别混在通过数里
      title: '异常圈',
      key: 'abnormal',
      width: 90,
      render: (_: unknown, r: NightReport) => (r.abnormal > 0 ? (
        <Tooltip title={(r.abnormalRuns || []).map((a) => a.reason).join('；')}>
          <Tag color="red" data-testid={`night-report-abnormal-${r.labelProjectId}`}>{r.abnormal}</Tag>
        </Tooltip>
      ) : <Typography.Text type="secondary">0</Typography.Text>),
    },
    {
      title: 'badcase TOP',
      key: 'badcase',
      render: (_: unknown, r: NightReport) => {
        const top = (r.badcaseTop || []).slice(0, 3)
        return top.length
          ? <Typography.Text style={{ fontSize: 12 }}>{top.map((b) => `${b.question}(${b.count})`).join(' · ')}</Typography.Text>
          : <Typography.Text type="secondary">—</Typography.Text>
      },
    },
    {
      title: '新模型',
      key: 'models',
      width: 140,
      render: (_: unknown, r: NightReport) => ((r.newModels || []).length
        ? <Tag color="green">{(r.newModels || []).length} 个</Tag>
        : <Typography.Text type="secondary">—</Typography.Text>),
    },
  ]

  const cards = [
    {
      title: '项目', value: data?.projects ?? 0, icon: <DeploymentUnitOutlined />,
      to: can('user:manage') ? '/admin' : '/dev',
    },
    { title: '文件', value: data?.workflows ?? 0, icon: <ThunderboltOutlined />, to: '/dev' },
    { title: '已发布数据服务', value: data?.publishedServices ?? 0, icon: <ApiOutlined />, to: '/service' },
    { title: '质量规则', value: data?.qualityRules ?? 0, icon: <SafetyCertificateOutlined />, to: '/quality' },
    { title: '今日实例', value: data?.todayInstances ?? 0, icon: <CloudServerOutlined />, to: '/ops/instances' },
    { title: '待处理告警', value: data?.openAlerts ?? 0, icon: <SafetyCertificateOutlined />, to: '/ops/alerts' },
  ]

  const todos = [
    {
      title: '待处理告警', icon: <BellOutlined />, to: '/ops/alerts',
      count: data?.openAlerts, err: loading ? undefined : error ? true : undefined,
      loading, hint: '运行失败、质量不达标、超时都会生成告警，先确认再关闭',
    },
    {
      title: '今日运行失败', icon: <CloseCircleOutlined />, to: '/ops/instances?state=failed',
      count: failedToday.value, err: failedToday.err, loading: failedToday.value === undefined && !failedToday.err,
      hint: '失败实例可重跑；重跑是新建一次运行，原记录保留',
    },
    {
      title: '正在运行的实例', icon: <SyncOutlined />, to: '/ops/instances?state=running',
      count: data?.runningInstances, err: undefined, loading,
      hint: '列表每 10 秒自动刷新，可看任务日志',
    },
    {
      title: '待我审批的申请', icon: <AuditOutlined />, to: '/access',
      count: approvals.value, err: approvals.err, loading: approvals.value === undefined && !approvals.err,
      hint: '库表权限申请通过后即刻生效，驳回会通知本人',
    },
    {
      title: '待审批 SQL 变更', icon: <FileDoneOutlined />, to: '/changes',
      count: pendingChanges.value, err: pendingChanges.err, loading: pendingChanges.value === undefined && !pendingChanges.err,
      hint: '通过即执行、不可撤销，审批前先核对回滚方案',
    },
  ].filter((t) => !t.err)

  const quick = [
    { label: '查数据', desc: '写只读 SQL，马上出结果', to: '/query', icon: <SearchOutlined /> },
    { label: '开发数据', desc: '建文件、编排调度', to: '/dev', icon: <CodeOutlined /> },
    { label: '同步数据', desc: '外部库表批量搬进来', to: '/integration', icon: <SwapOutlined /> },
    { label: '实时流', desc: '消息流实时加工入库', to: '/stream', icon: <ThunderboltOutlined /> },
    { label: '表管理', desc: '建表、结构、预览', to: '/tables', icon: <DatabaseOutlined /> },
    { label: '看板', desc: 'SQL 出图表并发布', to: '/bi', icon: <BarChartOutlined /> },
  ]

  const todoLoaded = todos.length > 0 && todos.every((t) => t.count !== undefined)
  const noTodo = todoLoaded && todos.every((t) => (t.count ?? 0) === 0)

  return (
    <div>
      {/* HOME-MAIN-START 主区=业务待办与常用入口：禁止出现基础设施产品名（REQ-UX-HELP-1 验收按标记段扫描） */}
      <PageHeader
        title="工作台"
        description="先看有什么要处理的，再开始干活。查数、开发、同步、看板的入口都在本页；技术架构介绍收在页面底部，选读。"
        tags={<Tag color="blue">DEV · v{data?.version || '0.1.0'}</Tag>}
      />

      {error ? (
        <ErrorState
          compact
          title="「统计数据」加载失败"
          message={`${error}。下方架构说明不受影响，数字为零只是未取到数据。`}
          onRetry={load}
        />
      ) : null}

      <Card size="small" title="今日待办" style={{ marginBottom: 12 }}>
        <Row gutter={[12, 12]}>
          {todos.map((t) => (
            <Col xs={12} md={8} xl={4} key={t.title}>
              <Card hoverable size="small" onClick={() => nav(t.to)}>
                <Statistic
                  title={t.title}
                  value={t.count ?? 0}
                  loading={t.loading}
                  prefix={t.icon}
                  valueStyle={(t.count ?? 0) > 0 ? { color: '#cf1322' } : undefined}
                />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{t.hint}</Typography.Text>
              </Card>
            </Col>
          ))}
        </Row>
        {noTodo ? (
          <Alert
            type="success"
            showIcon
            style={{ marginTop: 12 }}
            message="暂无待办：告警、失败、审批都干净，可以直接开始干活。"
          />
        ) : null}
      </Card>

      <Card size="small" title="常用入口" style={{ marginBottom: 12 }}>
        <Row gutter={[12, 12]}>
          {quick.map((q) => (
            <Col xs={12} md={8} xl={4} key={q.to}>
              <Card hoverable size="small" onClick={() => nav(q.to)}>
                <Space direction="vertical" size={2}>
                  <Space>
                    {q.icon}
                    <Typography.Text strong>{q.label}</Typography.Text>
                  </Space>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>{q.desc}</Typography.Text>
                </Space>
              </Card>
            </Col>
          ))}
        </Row>
      </Card>
      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {cards.map((card) => (
          <Col xs={12} sm={8} xl={4} key={card.title}>
            <Card hoverable className="stat-card" onClick={() => nav(card.to)}>
              <Statistic
                title={card.title}
                value={card.value}
                loading={loading}
                prefix={card.icon}
              />
            </Card>
          </Col>
        ))}
      </Row>

      {/* REQ-NIGHT-LEARN-1 夜间学习晨报带（数据源=GET /api/flywheel/night/reports，一夜一岗一行）：
          项目负责人零操作跑完一夜，早上在这一块看结果——圈数/通过/异常/badcase TOP/新模型一个不少，
          异常单独成列并可点行展开明细（晨复核入口）。取不到也要出声，不静默留空。 */}
      {can('mos:flywheel') && (
        <Card
          size="small"
          style={{ marginBottom: 12 }}
          data-testid="night-learn-band"
          title={(
            <Space>
              夜间学习晨报
              {night?.nightDate
                ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>{night.nightDate}</Typography.Text>
                : null}
            </Space>
          )}
          extra={<Button size="small" onClick={loadNight}>刷新</Button>}
        >
          {nightError ? (
            <Alert
              type="warning"
              showIcon
              data-testid="night-learn-error"
              message="夜间学习晨报未取到"
              description={`${nightError}（夜环未在跑，或后端还没带夜学晨报面）`}
            />
          ) : !nightRows.length ? (
            <Typography.Text type="secondary" data-testid="night-learn-empty">
              还没有夜间学习记录：到「标注项目」行操作里给岗位打开「夜间学习」，
              下一夜 00:00–06:00 自动跑一圈，早上这里出晨报。
            </Typography.Text>
          ) : (
            <>
              <Space wrap style={{ marginBottom: 8 }} data-testid="night-learn-summary">
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  岗位 {night?.summary.posts ?? 0} · 圈 {night?.summary.runs ?? 0} · 通过 {night?.summary.passed ?? 0}
                </Typography.Text>
                <Tag
                  color={(night?.summary.abnormal ?? 0) > 0 ? 'red' : 'default'}
                  data-testid="night-learn-abnormal-total"
                >
                  异常 {night?.summary.abnormal ?? 0}
                </Tag>
              </Space>
              <Table<NightReport>
                rowKey={(r) => `${r.nightDate}-${r.labelProjectId}`}
                size="small"
                pagination={false}
                dataSource={nightRows}
                columns={nightColumns}
                locale={{ emptyText: tableEmpty('夜里还没有学习记录', '在「标注项目」行操作里打开「夜间学习」') }}
                onRow={(r) => ({
                  style: { cursor: 'pointer' },
                  onClick: () => setNightDetailKey((k) => {
                    const key = `${r.nightDate}-${r.labelProjectId}`
                    return k === key ? null : key
                  }),
                })}
              />
              {nightDetail ? (
                <div data-testid="night-report-detail" style={{ marginTop: 8 }}>
                  <Typography.Text strong>
                    {nightDetail.labelProjectTitle || `岗位 #${nightDetail.labelProjectId}`} · {nightDetail.nightDate}
                  </Typography.Text>
                  {(nightDetail.abnormalRuns || []).length ? (
                    <ul style={{ margin: '6px 0 0', paddingLeft: 20 }}>
                      {(nightDetail.abnormalRuns || []).map((a, i) => (
                        <li key={`${a.kind}-${a.runId ?? 'none'}-${i}`} data-testid={`night-report-abnormal-run-${i}`}>
                          <Tag color="red">{a.kind}</Tag>
                          <Typography.Text style={{ fontSize: 12 }}>
                            {a.reason}{a.step ? `（停在 ${a.step} 段）` : ''}
                          </Typography.Text>
                          {a.runId ? <Button size="small" type="link" onClick={() => nav('/flywheel')}>去数据飞轮看这圈</Button> : null}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <div><Typography.Text type="secondary" style={{ fontSize: 12 }}>该岗本夜没有异常圈。</Typography.Text></div>
                  )}
                  {(nightDetail.newModels || []).length > 0 && (
                    <div style={{ marginTop: 6 }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>新模型：</Typography.Text>
                      {(nightDetail.newModels || []).map((m) => <Tag key={String(m)} color="green">{String(m)}</Tag>)}
                    </div>
                  )}
                  <div style={{ marginTop: 6 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      来源 {nightDetail.source || '—'}
                      {' · 末态 '}{String((nightDetail.detail || {}).state ?? (nightDetail.detail || {}).lastStatus ?? '—')}
                    </Typography.Text>
                  </div>
                </div>
              ) : (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  点一行看该岗异常明细与新模型清单。
                </Typography.Text>
              )}
            </>
          )}
        </Card>
      )}

      {/* 全新部署时六张卡全是 0，页面不能只有架构长文，得给出下一步 */}
      {!loading && !error && data && data.projects === 0 && data.workflows === 0 && (
        <Card size="small" title="还没有数据，从这里开始" style={{ marginBottom: 12 }}>
          <Space wrap>
            {can('user:manage') && (
              <Button type="primary" onClick={() => nav('/admin')}>新建项目</Button>
            )}
            <Button onClick={() => nav('/datasources')}>接入数据源</Button>
            <Button onClick={() => nav('/dev')}>新建文件</Button>
            <Button onClick={() => nav('/query')}>试一条 SQL</Button>
          </Space>
          <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
            推荐顺序：建项目 → 配数据源 → 新建文件 → 配脚本与依赖。数据查询不依赖项目，可以先试。
          </Typography.Text>
        </Card>
      )}

      {/* HOME-MAIN-END 主区（待办/入口/统计/上手引导）到此为止；以下技术介绍降为次级折叠块，默认不展开 */}
      <Collapse
        size="small"
        items={[{
          key: 'tech',
          label: '技术介绍（选读）：架构、选型与部署 · 供平台开发运维同学查看',
          children: (
            <>
      <Card
        size="small"
        title="整体架构"
        extra={<Typography.Text type="secondary">鼠标悬停看说明，点击方块跳到下方展开介绍</Typography.Text>}
        style={{ marginBottom: 12 }}
      >
        <div className="tofu-diagram">
          <TofuRow label="接入" blocks={ACCESS} />
          <TofuRow label="执行" blocks={COMPUTE} />
          <TofuRow label="存储" blocks={STORE} />
          <div className="tofu-row">
            <div className="tofu-label">保障</div>
            <div className="tofu-blocks">
              {OPS.map((block, idx) => (
                <Fragment key={block.title}>
                  <Tooltip title={block.tip}>
                    <a className="tofu-block tofu-block-light" href={`#${block.anchor}`}>
                      <span className="tofu-title">{block.title}</span>
                      <span className="tofu-sub">{block.sub}</span>
                    </a>
                  </Tooltip>
                  {idx < OPS.length - 1 ? <span className="tofu-arrow">·</span> : null}
                </Fragment>
              ))}
            </div>
          </div>
        </div>

        <div className="flow-title">典型数据链路</div>
        <div className="flow-row">
          {FLOW.map((step, idx) => (
            <span key={step} className="flow-item">
              <span className="flow-chip">{step}</span>
              {idx < FLOW.length - 1 ? <span className="flow-arrow">→</span> : null}
            </span>
          ))}
        </div>
      </Card>

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        <Col xs={24} xl={12}>
          <div id="intro-access">
            <Card size="small" title="接入与体验">
              人和 Agent 都从这一层进入。浏览器访问 React 控制台，覆盖数据开发、运维、数据地图、
              质量、数据服务、告警与安全；外部系统走 REST API，Agent 走 MCP 网关的 14 个只读工具。
              身份统一交给 Keycloak（OIDC + PKCE），本地 admin 仅用于开发兜底。
            </Card>
          </div>
        </Col>
        <Col xs={24} xl={12}>
          <div id="intro-control">
            <Card size="small" title="平台控制面">
              Spring Boot 后端承载平台自己的模型：项目、数据源、文件与版本、发布/审批、调度、
              运行实例、质量规则与闸门、数据服务、告警中心、安全审计。数据服务的只读 SQL
              在这里做参数绑定与写操作拦截，所有关键动作落审计日志。
            </Card>
          </div>
        </Col>
        <Col xs={24} xl={12}>
          <div id="intro-compute">
            <Card size="small" title="调度与计算">
              Prefect 3 负责 Deployment / Flow Run / 任务日志、重试和补数；SQL 任务按任务级数据源
              连 PostgreSQL 或 Doris；SeaTunnel 负责库表同步。计算组件都在 K8s 里运行，
              平台只保存“做什么、什么时候做”，不自己实现执行引擎。
            </Card>
          </div>
        </Col>
        <Col xs={24} xl={12}>
          <div id="intro-store">
            <Card size="small" title="存储与治理">
              PostgreSQL 存平台元数据和审计；Doris 承担 OLAP 查询与向量检索；MinIO 提供 S3 对象存储；
              Gravitino 统一纳管 Doris catalog、MinIO fileset 与 AI 模型。血缘在发布时按 SQL
              解析成表级边，数据地图直接读 Gravitino 元数据。
            </Card>
          </div>
        </Col>
        <Col xs={24} xl={12}>
          <div id="intro-ops">
            <Card size="small" title="可观测与安全">
              Prometheus 采集、Grafana 看板；安全中心提供审计、在线会话与失败登录；告警中心对
              运行失败、质量未通过、SLA 超时自动生成事件，可确认、关闭并保留历史。
              登录、发布、工作流回滚、服务调用、告警处理全部可追溯。
            </Card>
          </div>
        </Col>
      </Row>

      <Row gutter={[12, 12]}>
        <Col xs={24} xl={15}>
          <Card size="small" title="技术选型与原因">
            <Table<TechRow>
              rowKey="component"
              size="small"
              dataSource={TECH}
              locale={{ emptyText: tableEmpty('没有选型条目', '内容内置，正常不会为空') }}
              pagination={false}
              columns={[
                { title: '层', dataIndex: 'layer', width: 110 },
                { title: '组件', dataIndex: 'component', width: 260 },
                { title: '为什么选它', dataIndex: 'why' },
              ]}
            />
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Card size="small" title="部署拓扑">
              <div className="topo">
                <div className="topo-host">
                  <DatabaseOutlined /> 单台物理主机 · 28 逻辑核 / 63.8GB
                </div>
                <div className="topo-nodes">
                  <div className="topo-node">
                    <b>dw-k8s-1</b>
                    <span>6 vCPU / 12GB</span>
                    <span>control-plane + etcd</span>
                  </div>
                  <div className="topo-node">
                    <b>dw-k8s-2</b>
                    <span>6 vCPU / 10GB</span>
                    <span>control-plane + etcd</span>
                  </div>
                  <div className="topo-node">
                    <b>dw-k8s-3</b>
                    <span>6 vCPU / 10GB</span>
                    <span>control-plane + etcd</span>
                  </div>
                </div>
                <Typography.Text type="secondary">
                  K3s 3 server 嵌入式 etcd；业务组件全部以 Pod 运行，
                  控制台、MCP、Prometheus、Grafana 通过 NodePort 暴露。
                </Typography.Text>
              </div>
            </Card>

            <Card size="small" title="设计原则">
              <ul className="principle-list">
                <li><b>控制面自研</b>：项目、版本、发布、审批、运维、审计按平台模型自己实现。</li>
                <li><b>执行面用成熟组件</b>：调度 Prefect、集成 SeaTunnel、查询 Doris、元数据 Gravitino。</li>
                <li><b>Agent-first</b>：每个动作先有 JSON Schema 与语义描述，界面只是展示层。</li>
                <li><b>单租户 100 人</b>：先把链路做通，再补 RBAC、限流、告警外发等生产项。</li>
                <li><b>一切可审计</b>：登录、发布、工作流回滚、服务调用、告警处理全部落审计日志。</li>
              </ul>
            </Card>
          </Space>
        </Col>
      </Row>
            </>
          ),
        }]}
      />
    </div>
  )
}
