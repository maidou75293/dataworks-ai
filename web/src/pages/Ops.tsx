import { useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select, Space,
  Statistic, Table, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  AlertOutlined, ArrowRightOutlined, CloudServerOutlined, PlayCircleOutlined,
  ReloadOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { api, errMsg, post } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { ENV_OPTIONS, ENV_BACKFILL_HINT } from '../envOptions'
import { RunStateTag, formatDuration } from '../status'
import type { Instance, Project, Workflow } from '../types'

type Summary = {
  projects?: number
  workflows?: number
  running?: number
  failed?: number
  today?: number
  qualityFailures?: number
}

type AlertSummary = {
  open?: number
  criticalOpen?: number
}

/**
 * D47：生命周期执行记录（`GET /api/ops/lifecycle/logs`，`system:manage`）。
 *
 * <p>列集合来自 `platform.lifecycle_job_log`（V108），接口是 `select *`，
 * 所以这里按真实能力渲染，**不照抄卡面文案**：
 * ① 没有「归档」这个 action（只有 DROP_PARTITION / EXPIRE_MARK / SKIP / ERROR，
 *    转冷只体现在策略计算里，从不写日志）；
 * ② 没有耗时列、没有 run 分组（只有 created_at 一个时间列）；
 * ③ 服务端只认 tableId / limit，表名与时间过滤只能在前端对已取的 ≤500 行做
 *    —— 界面必须写明，别让人以为查到了全部。
 * ④ SQL 不带租户条件（跨租户全量，靠 system:manage 收口），所以 tenant 列要显示出来。</p>
 */
type LifecycleLog = {
  id: number
  table_id?: number | null
  tenant?: string | null
  catalog?: string | null
  schema_name?: string | null
  table_name?: string | null
  partition_name?: string | null
  action?: string | null
  detail?: string | null
  created_at?: string | null
}

/** D51：待确认清单里的一行（到期但没人确认过的分区）。 */
interface LifecyclePending {
  tableId: number
  tenant?: string | null
  database?: string | null
  table?: string | null
  partition?: string | null
  expireDay?: string | null
  retentionDays?: number | null
  ownerUser?: string | null
}

/** D51：回收中的分区。 */
interface LifecycleTrash extends LifecyclePending {
  trashName?: string | null
  deletedAt?: string | null
  deletedDetail?: string | null
}

const LIFECYCLE_ACTIONS: Record<string, { text: string; color: string }> = {
  DROP_PARTITION: { text: '删分区', color: 'red' },
  EXPIRE_MARK: { text: '标记到期', color: 'orange' },
  SKIP: { text: '跳过', color: 'default' },
  ERROR: { text: '失败', color: 'volcano' },
}

type SystemOverview = {
  summary: {
    nodesReady: number
    nodesTotal: number
    podsAbnormal: number
    coveredNamespaces?: string[]
  }
}

/**
 * 运维总览：只做「一眼看清 + 进哪个模块」，实例明细在 /ops/instances。
 * 四个入口卡是运维监控的统一入口，侧栏子菜单和这里指向同一批页面。
 */
export default function Ops() {
  const nav = useNavigate()
  const [summary, setSummary] = useState<Summary>({})
  const [alertSummary, setAlertSummary] = useState<AlertSummary>({})
  const [sys, setSys] = useState<SystemOverview['summary']>()
  const [failures, setFailures] = useState<Instance[]>([])
  const [trendSource, setTrendSource] = useState<Instance[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [backfillOpen, setBackfillOpen] = useState(false)
  const [projects, setProjects] = useState<Project[]>([])
  const [workflows, setWorkflows] = useState<Workflow[]>([])
  const [backfillForm] = Form.useForm()
  // D47：生命周期执行记录（只有 system:manage 能读，接口跨租户返回）
  const [lifecycleLogs, setLifecycleLogs] = useState<LifecycleLog[]>([])
  const [lifecycleLoading, setLifecycleLoading] = useState(false)
  const [lifecycleError, setLifecycleError] = useState('')
  const [lifecycleTableId, setLifecycleTableId] = useState<number>()
  const [lifecycleKeyword, setLifecycleKeyword] = useState('')
  const [lifecycleRange, setLifecycleRange] = useState<[string, string] | null>(null)
  // D51：待确认清单 / 回收站
  const [pendingRows, setPendingRows] = useState<LifecyclePending[]>([])
  const [pendingLoading, setPendingLoading] = useState(false)
  const [pendingError, setPendingError] = useState('')
  const [pendingSel, setPendingSel] = useState<Record<string, LifecyclePending>>({})
  const [trashRows, setTrashRows] = useState<LifecycleTrash[]>([])

  // 10 秒轮询只拉两张轻表：统计与最近失败；K8s/告警/趋势 60 秒一次
  const loadCore = async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const [s, f] = await Promise.all([
        api<Summary>('/api/ops/summary'),
        api<Instance[]>('/api/ops/instances?state=failed,upstream_failed&limit=5'),
      ])
      setSummary(s)
      setFailures(f)
      setError('')
    } catch (e) {
      if (!silent) setError(e instanceof Error ? e.message : '加载失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }

  // 三个来源互相独立：系统监控/告警挂了不能把整页变成错误页
  const loadInfra = async () => {
    const [a, s, all] = await Promise.allSettled([
      api<AlertSummary>('/api/alerts/summary'),
      api<SystemOverview>('/api/system/overview'),
      api<Instance[]>('/api/ops/instances?limit=500'),
    ])
    if (a.status === 'fulfilled') setAlertSummary(a.value)
    if (s.status === 'fulfilled') setSys(s.value.summary)
    if (all.status === 'fulfilled') setTrendSource(all.value)
  }

  /** D51：待确认清单 + 回收站（只读拉取，写操作走确认/恢复按钮）。 */
  const loadLifecycleGate = async () => {
    if (!can('system:manage') && !can('project:manage')) return
    setPendingLoading(true)
    setPendingError('')
    try {
      const [pending, trash] = await Promise.all([
        api<LifecyclePending[]>('/api/ops/lifecycle/pending'),
        api<LifecycleTrash[]>('/api/ops/lifecycle/trashed'),
      ])
      setPendingRows(pending)
      setTrashRows(trash)
    } catch (e) {
      setPendingError(e instanceof Error ? e.message : '加载生命周期待确认清单失败')
    } finally {
      setPendingLoading(false)
    }
  }

  const confirmPending = () => {
    const picked = Object.values(pendingSel)
    if (picked.length === 0) {
      message.warning('先勾选要确认删除的分区')
      return
    }
    const byTable = new Map<number, string[]>()
    for (const row of picked) {
      byTable.set(row.tableId, [...(byTable.get(row.tableId) || []), row.partition || ''])
    }
    Modal.confirm({
      title: `删除 ${picked.length} 个分区？`,
      okText: '删除',
      okButtonProps: { danger: true },
      content: (
        <div>
          <p style={{ marginBottom: 6 }}>
            确认后这些分区会被<b>改名进回收</b>（保留 30 天，期间可恢复），
            期满才物理删除。删除不可逆，请核对表名与分区。
          </p>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            {picked.slice(0, 8).map((row) => (
              <li key={`${row.tableId}-${row.partition}`}>
                {row.database}.{row.table} / {row.partition}（到期 {row.expireDay}）
              </li>
            ))}
            {picked.length > 8 ? <li>…共 {picked.length} 个</li> : null}
          </ul>
        </div>
      ),
      onOk: async () => {
        try {
          for (const [tableId, partitions] of byTable) {
            await post('/api/ops/lifecycle/confirm', { tableId, partitions })
          }
          // 确认只是记账；真正执行仍要手动跑一轮（或等定时任务生成清单后由负责人触发）
          message.success('已确认，接下来执行一轮才会进回收')
          setPendingSel({})
          await Promise.all([loadLifecycleGate(), loadLifecycle()])
        } catch (e) {
          message.error(e instanceof Error ? e.message : '确认失败')
          throw e
        }
      },
    })
  }

  const recoverTrash = (row: LifecycleTrash) => {
    Modal.confirm({
      title: `恢复分区 ${row.partition}？`,
      okText: '恢复',
      content: `把回收名 ${row.trashName} 改回原名，数据立即回到可用状态。`,
      onOk: async () => {
        try {
          await post('/api/ops/lifecycle/recover', { tableId: row.tableId, trashName: row.trashName })
          message.success('分区已恢复')
          await Promise.all([loadLifecycleGate(), loadLifecycle()])
        } catch (e) {
          message.error(e instanceof Error ? e.message : '恢复失败')
          throw e
        }
      },
    })
  }

  /** D47：服务端只认 tableId / limit；表名与时间是前端过滤（仅作用于已取的 500 条）。 */
  const loadLifecycle = async (tableId?: number) => {
    if (!can('system:manage')) return
    setLifecycleLoading(true)
    setLifecycleError('')
    try {
      const query = tableId ? `?tableId=${tableId}&limit=500` : '?limit=500'
      setLifecycleLogs(await api<LifecycleLog[]>(`/api/ops/lifecycle/logs${query}`))
    } catch (e) {
      setLifecycleError(e instanceof Error ? e.message : '加载生命周期执行记录失败')
    } finally {
      setLifecycleLoading(false)
    }
  }

  useEffect(() => {
    loadLifecycle()
    loadLifecycleGate()
    loadCore()
    loadInfra()
    const core = window.setInterval(() => loadCore(true), 10000)
    const infra = window.setInterval(loadInfra, 60000)
    return () => {
      window.clearInterval(core)
      window.clearInterval(infra)
    }
  }, [])

  const lifecycleFiltered = useMemo(() => lifecycleLogs.filter((row) => {
    const name = `${row.schema_name || ''}.${row.table_name || ''}`
    if (lifecycleKeyword && !name.toLowerCase().includes(lifecycleKeyword.toLowerCase())
      && !String(row.partition_name || '').toLowerCase().includes(lifecycleKeyword.toLowerCase())) {
      return false
    }
    if (lifecycleRange && row.created_at) {
      const at = dayjs(row.created_at)
      if (at.isBefore(dayjs(lifecycleRange[0])) || at.isAfter(dayjs(lifecycleRange[1]))) return false
    }
    return true
  }), [lifecycleLogs, lifecycleKeyword, lifecycleRange])

  const openBackfill = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    setWorkflows([])
    backfillForm.resetFields()
    if (list.length > 0) {
      backfillForm.setFieldsValue({ projectId: list[0].id })
      setWorkflows(await api<Workflow[]>(`/api/projects/${list[0].id}/workflows`))
    }
    setBackfillOpen(true)
  }

  const [backfillBusy, setBackfillBusy] = useState(false)
  const doBackfill = async () => {
    if (backfillBusy) return
    const values = await backfillForm.validateFields()
    setBackfillBusy(true)
    try {
      const result = await post<{ jobId: number; totalDays: number }>(
        `/api/workflows/${values.workflowId}/backfill`, {
          env: values.env,
          dateStart: values.range[0].format('YYYY-MM-DD'),
          dateEnd: values.range[1].format('YYYY-MM-DD'),
          parallelism: values.parallelism,
        })
      message.success(`已提交补数作业 #${result.jobId}（${result.totalDays} 天），进度在工作流页「补数据」查看`)
      setBackfillOpen(false)
      await loadCore()
    } finally {
      setBackfillBusy(false)
    }
  }

  const entries = useMemo(() => {
    type Entry = {
      key: string
      icon: React.ReactNode
      title: string
      desc: string
      metrics: { label: string; value: number | string }[]
      danger: boolean
      onClick: () => void
    }
    const list: Entry[] = [
      {
        key: 'instances',
        icon: <PlayCircleOutlined />,
        title: '运行实例',
        desc: '查看、重跑、任务日志与补数',
        metrics: [
          { label: '运行中/排队', value: summary.running ?? '-' },
          { label: '今日', value: summary.today ?? '-' },
        ],
        danger: false,
        onClick: () => nav('/ops/instances'),
      },
      {
        key: 'alerts',
        icon: <AlertOutlined />,
        title: '告警中心',
        desc: '失败、质量、SLA 告警与规则',
        metrics: [
          { label: '待处理', value: alertSummary.open ?? '-' },
          { label: '严重', value: alertSummary.criticalOpen ?? '-' },
        ],
        danger: (alertSummary.open ?? 0) > 0,
        onClick: () => nav('/ops/alerts'),
      },
      {
        key: 'system',
        icon: <CloudServerOutlined />,
        title: '系统监控',
        // 覆盖面跟着后端走：kube-system 曾不在监控范围，入口 Pod 卡死 34 分钟页面看不见
        desc: sys?.coveredNamespaces
          ? `节点、Pod、组件与 SeaTunnel（覆盖 ${sys.coveredNamespaces.join(' / ')}）`
          : '节点、Pod、组件与 SeaTunnel',
        metrics: [
          { label: '异常 Pod', value: sys?.podsAbnormal ?? '-' },
          { label: '节点', value: sys ? `${sys.nodesReady}/${sys.nodesTotal}` : '-' },
        ],
        danger: (sys?.podsAbnormal ?? 0) > 0,
        onClick: () => nav('/ops/system'),
      },
    ]
    if (can('ops:run')) {
      list.push({
        key: 'backfill',
        icon: <ThunderboltOutlined />,
        title: '批量补数',
        desc: '按日期区间为文件补跑历史实例',
        metrics: [{ label: '按天补跑', value: '' }],
        danger: false,
        onClick: () => openBackfill().catch((e) => message.error(errMsg(e))),
      })
    }
    return list
  }, [summary, alertSummary, sys, nav])

  const cards = useMemo(() => ([
    {
      title: '项目', value: summary.projects || 0,
      onClick: () => nav(can('user:manage') ? '/admin' : '/dev'),
    },
    { title: '文件', value: summary.workflows || 0, onClick: () => nav('/dev') },
    {
      title: '运行中/排队', value: summary.running || 0,
      onClick: () => nav('/ops/instances?state=running,queued'),
    },
    {
      title: '失败', value: summary.failed || 0,
      onClick: () => nav('/ops/instances?state=failed'),
    },
    { title: '今日实例', value: summary.today || 0, onClick: () => nav('/ops/instances') },
    { title: '近 7 天质量失败', value: summary.qualityFailures || 0, onClick: () => nav('/quality') },
  ]), [summary, nav])

  // 近 7 天运行趋势（纯前端聚合，避免额外后端接口）
  const trend = useMemo(() => {
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = dayjs().subtract(6 - i, 'day')
      return { date: d.format('YYYY-MM-DD'), label: d.format('MM-DD'), success: 0, failed: 0, other: 0 }
    })
    const index = new Map(days.map((d) => [d.date, d]))
    trendSource.forEach((row) => {
      const date = row.created_at ? dayjs(row.created_at).format('YYYY-MM-DD') : ''
      const day = index.get(date)
      if (!day) return
      if (row.state === 'success') day.success += 1
      else if (row.state === 'failed' || row.state === 'upstream_failed') day.failed += 1
      else day.other += 1
    })
    const max = Math.max(1, ...days.map((d) => d.success + d.failed + d.other))
    return { days, max }
  }, [trendSource])

  const entrySpan = entries.length >= 4 ? 6 : 8

  return (
    <div>
      <PageHeader
        title="运维总览"
        description="实例、告警与系统状态一页看清；每 10 秒自动刷新"
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => { loadCore(); loadInfra() }}>
              刷新
            </Button>
          </Space>
        )}
      />

      {error ? <ErrorState message={error} onRetry={() => loadCore()} /> : null}

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        {entries.map((entry) => (
          <Col xs={24} sm={12} xl={entrySpan} key={entry.key}>
            <Card
              hoverable
              className={`ops-entry-card${entry.danger ? ' ops-entry-danger' : ''}`}
              onClick={entry.onClick}
            >
              <div className="ops-entry">
                <span className="ops-entry-icon">{entry.icon}</span>
                <div className="ops-entry-main">
                  <div className="ops-entry-title">
                    {entry.title}
                    <ArrowRightOutlined className="ops-entry-arrow" />
                  </div>
                  <div className="ops-entry-metrics">
                    {entry.metrics.map((m) => (
                      <span key={m.label} className="ops-entry-metric">
                        <b>{m.value}</b> {m.label}
                      </span>
                    ))}
                  </div>
                  <div className="ops-entry-desc">{entry.desc}</div>
                </div>
              </div>
            </Card>
          </Col>
        ))}
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        {cards.map((card) => (
          <Col xs={12} sm={8} xl={4} key={card.title}>
            <Card hoverable className="stat-card" onClick={card.onClick}>
              <Statistic title={card.title} value={card.value} />
            </Card>
          </Col>
        ))}
      </Row>

      <Card
        size="small"
        title="近 7 天运行趋势"
        style={{ marginBottom: 16 }}
        extra={(
          <Space size={12}>
            {[
              { kind: 'success', label: '成功' },
              { kind: 'failed', label: '失败' },
              { kind: 'other', label: '其他' },
            ].map((item) => (
              <span key={item.kind} className="trend-legend">
                <i className={`trend-dot ${item.kind}`} />
                {item.label}
              </span>
            ))}
          </Space>
        )}
      >
        <div className="trend">
          {trend.days.map((d) => {
            const total = d.success + d.failed + d.other
            const scale = (n: number) => `${(n / trend.max) * 100}%`
            return (
              <div className="trend-row" key={d.date}>
                <span className="trend-label">{d.label}</span>
                <div className="trend-track">
                  <div className="trend-bar success" style={{ width: scale(d.success) }} title={`成功 ${d.success}`} />
                  <div className="trend-bar failed" style={{ width: scale(d.failed) }} title={`失败 ${d.failed}`} />
                  <div className="trend-bar other" style={{ width: scale(d.other) }} title={`其他 ${d.other}`} />
                </div>
                <span className="trend-count">{total} 次</span>
              </div>
            )
          })}
        </div>
      </Card>

      <Card
        size="small"
        title="最近失败"
        extra={<Button type="link" size="small" onClick={() => nav('/ops/instances?state=failed')}>查看全部</Button>}
      >
        <Table<Instance>
          rowKey="id"
          size="small"
          loading={loading && !failures.length}
          dataSource={failures}
          pagination={false}
          scroll={{ x: 900 }}
          locale={{ emptyText: tableEmpty('最近没有失败实例', '运行失败后会在这里出现，附具体原因') }}
          onRow={(row) => ({
            style: { cursor: 'pointer' },
            onClick: (event) => {
              // UX-DW10REST-9（REQ-DW-10 R1）：点击记录=打开详情（实例回看）；行内控件保持自身行为
              const target = event.target as HTMLElement
              if (target.closest('a, button, input, textarea, .ant-select')) return
              nav(`/ops/instances?instance=${row.id}`)
            },
          })}
          columns={[
            { title: '项目 / 文件', render: (_, row) => `${row.project_name} / ${row.workflow_name}` },
            { title: '业务日期', dataIndex: 'biz_date', width: 110 },
            { title: '状态', dataIndex: 'state', width: 110, render: (state) => <RunStateTag state={state} /> },
            {
              title: '失败原因', dataIndex: 'error_message',
              ellipsis: { showTitle: false },
              render: (text: string | null) => text ? (
                <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                  <span className="failure-reason-text">{text}</span>
                </Tooltip>
              ) : '-',
            },
            {
              title: '耗时', width: 90,
              render: (_, row) => formatDuration(row.started_at, row.ended_at, row.state),
            },
            {
              title: '开始时间', dataIndex: 'started_at', width: 150,
              render: (v) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
            },
            // UX-DW10REST-9（REQ-DW-10 R1）：删独立「详情」操作列（列内只有入口按钮），点行即回看实例（onRow+守卫）
          ]}
        />
      </Card>

      {can('system:manage') ? (
        <Card
          size="small"
          title="生命周期执行记录"
          extra={(
            <Space wrap>
              <InputNumber
                size="small"
                style={{ width: 120 }}
                placeholder="表 id"
                value={lifecycleTableId}
                onChange={(v) => {
                  setLifecycleTableId(v ?? undefined)
                  loadLifecycle(v ?? undefined)
                }}
              />
              <Input
                size="small"
                style={{ width: 160 }}
                allowClear
                placeholder="表名/分区（本地）"
                value={lifecycleKeyword}
                onChange={(e) => setLifecycleKeyword(e.target.value)}
              />
              <DatePicker.RangePicker
                size="small"
                showTime
                onChange={(v) => {
                  const [from, to] = v || []
                  setLifecycleRange(from && to ? [from.toISOString(), to.toISOString()] : null)
                }}
              />
              <Button size="small" icon={<ReloadOutlined />} loading={lifecycleLoading}
                      onClick={() => loadLifecycle(lifecycleTableId)}>刷新</Button>
            </Space>
          )}
        >
          {lifecycleError ? <Alert type="error" showIcon message={lifecycleError} /> : null}
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 8 }}
            message="口径说明（不是界面偷懒，是接口只有这些）"
            description={(
              <ul style={{ margin: 0, paddingLeft: 18 }}>
                <li>动作只有 删分区 / 标记到期 / 跳过 / 失败 四种；
                  <b>没有「归档」动作</b>——转冷只体现在策略计算里，从不写日志。</li>
                <li>接口<b>没有耗时列</b>，也没有一次执行的分组号（只有 created_at）。</li>
                <li>表 id 走服务端过滤；<b>表名与时间只对本次已取的最近 500 条生效</b>，
                  超出部分要输表 id 精确查。</li>
                <li>该接口跨租户返回（靠 system:manage 收口），所以「租户」列要看着判断归属。</li>
              </ul>
            )}
          />
          <Table<LifecycleLog>
            rowKey="id"
            size="small"
            loading={lifecycleLoading}
            dataSource={lifecycleFiltered}
            pagination={{ pageSize: 10, showSizeChanger: false }}
            scroll={{ x: 1020 }}
            locale={{
              emptyText: tableEmpty(
                lifecycleLogs.length && !lifecycleFiltered.length
                  ? '当前条件下没有记录' : '还没有生命周期执行记录',
                '定时任务每天 02:30（UTC，容器无 TZ）扫描一次纳管表后才写记录；'
                + '刚发布或没跨过触发点时为空是正常的，不是坏了。'),
            }}
            expandable={{
              // 失败与 dry-run 的完整信息都在 detail 里，展开看原文
              rowExpandable: (row) => Boolean(row.detail),
              expandedRowRender: (row) => (
                <Typography.Paragraph style={{ margin: 0 }} copyable={{ text: row.detail || '' }}>
                  {row.detail || '-'}
                </Typography.Paragraph>
              ),
            }}
            columns={[
              {
                title: '时间', dataIndex: 'created_at', width: 160,
                render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
              },
              { title: '租户', dataIndex: 'tenant', width: 90, render: (v) => v || '-' },
              {
                title: '目标表', width: 220,
                render: (_, row) => `${row.schema_name || '-'}.${row.table_name || '-'}`,
              },
              {
                title: '分区', dataIndex: 'partition_name', width: 140,
                render: (v) => v || <Typography.Text type="secondary">表级</Typography.Text>,
              },
              {
                title: '动作', dataIndex: 'action', width: 150,
                render: (v: string | null, row) => {
                  const meta = LIFECYCLE_ACTIONS[v || ''] || { text: v || '-', color: 'default' }
                  return (
                    <Space size={4}>
                      <Tag color={meta.color}>{meta.text}</Tag>
                      {String(row.detail || '').startsWith('dry-run') ? <Tag>试运行</Tag> : null}
                    </Space>
                  )
                },
              },
              {
                title: '结果 / 原因', dataIndex: 'detail',
                ellipsis: { showTitle: false },
                render: (text: string | null, row) => {
                  if (!text) return '-'
                  // 只有真失败才用失败配色：SKIP / dry-run 的 detail 不是错误，
                  // 套上 failure-reason-text 会让人以为跳过=失败
                  if (row.action === 'ERROR') {
                    return (
                      <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                        <span className="failure-reason-text">
                          <Typography.Text type="danger">{text}</Typography.Text>
                        </span>
                      </Tooltip>
                    )
                  }
                  return (
                    <Tooltip title={text}>
                      <Typography.Text ellipsis style={{ maxWidth: 320 }}>{text}</Typography.Text>
                    </Tooltip>
                  )
                },
              },
            ]}
          />
        </Card>
      ) : null}

      {(can('system:manage') || can('project:manage')) ? (
        <Card
          size="small"
          title="生命周期待确认清单"
          style={{ marginTop: 16 }}
          extra={(
            <Space>
              <Button size="small" icon={<ReloadOutlined />} loading={pendingLoading}
                      onClick={() => loadLifecycleGate()}>刷新</Button>
              <Button size="small" danger disabled={Object.keys(pendingSel).length === 0}
                      onClick={confirmPending}>
                确认删除（{Object.keys(pendingSel).length}）
              </Button>
            </Space>
          )}
        >
          {pendingError ? <Alert type="error" showIcon message={pendingError} /> : null}
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 8 }}
            message="这里的分区已到期，但不会被自动删除"
            description={'勾选并「确认删除」后，执行一轮（手动或定时）才会把它们改名进回收；'
              + '回收保留 30 天，期间可在下面的回收站里恢复。没有确认就永远不会动。'}
          />
          <Table<LifecyclePending>
            rowKey={(row) => `${row.tableId}:${row.partition}`}
            size="small"
            loading={pendingLoading}
            dataSource={pendingRows}
            pagination={{ pageSize: 8, showSizeChanger: false }}
            scroll={{ x: 900 }}
            locale={{ emptyText: tableEmpty('没有到期待确认的分区', '纳管表到期后会在这里出现，等负责人确认') }}
            rowSelection={{
              selectedRowKeys: Object.keys(pendingSel),
              onSelect: (row, selected) => setPendingSel((prev) => {
                const next = { ...prev }
                const key = `${row.tableId}:${row.partition}`
                if (selected) next[key] = row
                else delete next[key]
                return next
              }),
            }}
            columns={[
              { title: '库.表', render: (_, row) => `${row.database}.${row.table}`, width: 220 },
              { title: '分区', dataIndex: 'partition', width: 160 },
              { title: '到期日', dataIndex: 'expireDay', width: 110 },
              { title: '保留天数', dataIndex: 'retentionDays', width: 90 },
              { title: '表负责人', dataIndex: 'ownerUser', width: 110,
                render: (v) => v || '-' },
              { title: '租户', dataIndex: 'tenant', width: 90, render: (v) => v || '-' },
            ]}
          />
        </Card>
      ) : null}

      {(can('system:manage') || can('project:manage')) && trashRows.length > 0 ? (
        <Card size="small" title="生命周期回收站（30 天内可恢复）" style={{ marginTop: 16 }}>
          <Table<LifecycleTrash>
            rowKey={(row) => `${row.tableId}:${row.trashName}`}
            size="small"
            dataSource={trashRows}
            pagination={false}
            scroll={{ x: 980 }}
            columns={[
              { title: '库.表', render: (_, row) => `${row.database}.${row.table}`, width: 220 },
              { title: '原分区', dataIndex: 'partition', width: 150 },
              { title: '回收名', dataIndex: 'trashName', width: 220,
                render: (v) => <Typography.Text code>{v}</Typography.Text> },
              { title: '删除时间', dataIndex: 'deletedAt', width: 160,
                render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-') },
              { title: '删除记录（含确认人）', dataIndex: 'deletedDetail', ellipsis: true },
              { title: '操作', width: 90,
                render: (_, row) => (
                  <Button size="small" onClick={() => recoverTrash(row)}>恢复</Button>
                ) },
            ]}
          />
        </Card>
      ) : null}

      <Modal
        title="批量补数"
        open={backfillOpen}
        onOk={() => doBackfill().catch((e) => message.error(errMsg(e)))}
        onCancel={() => setBackfillOpen(false)}
        confirmLoading={backfillBusy}
        okText="创建补数"
      >
        <Form form={backfillForm} layout="vertical">
          <Form.Item label="项目" name="projectId" rules={[{ required: true }]}>
            <Select
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
              onChange={async (projectId) => {
                // 先清空再加载：加载期间/失败时工作流下拉里不能留着上一个项目的流程
                setWorkflows([])
                backfillForm.setFieldValue('workflowId', undefined)
                try {
                  setWorkflows(await api<Workflow[]>(`/api/projects/${projectId}/workflows`))
                } catch (e) {
                  message.error(e instanceof Error ? e.message : '加载文件失败')
                }
              }}
            />
          </Form.Item>
          <Form.Item label="文件" name="workflowId" rules={[{ required: true }]}>
            <Select options={workflows.map((w) => ({ value: w.id, label: `${w.name} (${w.code})` }))} />
          </Form.Item>
          <Form.Item label="环境" name="env" initialValue="DEV" extra={ENV_BACKFILL_HINT}>
            <Select options={ENV_OPTIONS} />
          </Form.Item>
          <Form.Item label="日期区间" name="range" rules={[{ required: true }]}>
            <DatePicker.RangePicker style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item label="并行度" name="parallelism" initialValue={2} extra="同时最多跑几天，1–4">
            <InputNumber min={1} max={4} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
