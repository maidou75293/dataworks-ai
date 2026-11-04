import { useEffect, useMemo, useState } from 'react'
import {
  Button, Card, DatePicker, Select, Space, Table, Tabs, Tag, Timeline, Tooltip,
  Typography, message,
} from 'antd'
import {
  ClockCircleOutlined, ReloadOutlined, SettingOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import { api, post } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'

type BaselineMember = {
  workflowId: number
  code: string
  name: string
  state: string
  predictedFinish?: string | null
  fallback: boolean
  failed: boolean
  detail: string
}

type BaselineRow = {
  id: number
  projectId: number
  projectCode?: string
  projectName?: string
  name: string
  deadlineTime: string
  warnBeforeMin: number
  level: string
  owner?: string | null
  enabled: boolean
  memberCount: number
  members: BaselineMember[]
  statDate: string
  deadlineAt: string
  warnAt: string
  predictedFinish?: string | null
  lateMinutes?: number | null
  status: string
  detail: string
}

type BaselineEvent = {
  id: number
  statDate: string
  predictedFinish?: string | null
  deadlineAt?: string | null
  lateMinutes?: number | null
  kind: string
  notifiedAt: string
  detail: string
}

const STATUS_LABEL: Record<string, string> = {
  DONE: '已完成',
  BREACH: '已违约',
  WARN: '预警中',
  FAILED: '运行失败',
  RUNNING: '运行中',
  NOT_STARTED: '未开始',
  DISABLED: '已停用',
}

const STATUS_COLOR: Record<string, string> = {
  DONE: 'green',
  BREACH: 'red',
  WARN: 'gold',
  FAILED: 'red',
  RUNNING: 'blue',
  NOT_STARTED: 'default',
  DISABLED: 'default',
}

const KIND_LABEL: Record<string, string> = {
  PREDICT_LATE: '预计迟到',
  BREACH: '承诺违约',
  RECOVERED: '已恢复',
}

const KIND_COLOR: Record<string, string> = {
  PREDICT_LATE: 'gold',
  BREACH: 'red',
  RECOVERED: 'green',
}

const KIND_DOT: Record<string, string> = {
  PREDICT_LATE: 'orange',
  BREACH: 'red',
  RECOVERED: 'green',
}

function fmt(value?: string | null, pattern = 'MM-DD HH:mm') {
  return value ? dayjs(value).format(pattern) : '-'
}

function latencyText(minutes?: number | null) {
  if (minutes === null || minutes === undefined) return '-'
  return minutes > 0 ? `迟到 ${minutes} 分钟` : `提前 ${-minutes} 分钟`
}

/**
 * 任务基线（SLA 预警）：只读列表 + 事件时间线（REQ-DW-11）。
 * 新建/编辑/删除入口已移到任务详情右侧「配置」面板，这里改配置一律跳转任务。
 */
export default function Baselines() {
  const nav = useNavigate()
  const [rows, setRows] = useState<BaselineRow[]>([])
  const [events, setEvents] = useState<BaselineEvent[]>([])
  const [activeId, setActiveId] = useState<number>()
  const [eventDate, setEventDate] = useState<Dayjs>(dayjs())
  const [tab, setTab] = useState('list')
  const [loading, setLoading] = useState(false)
  const [eventsLoading, setEventsLoading] = useState(false)
  const [error, setError] = useState('')
  const writable = can('ops:run')

  const load = async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      setRows(await api<BaselineRow[]>('/api/baselines'))
      setError('')
    } catch (e) {
      if (!silent) setError(e instanceof Error ? e.message : '加载基线失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }

  const loadEvents = async (id = activeId, date = eventDate) => {
    if (!id) return
    setEventsLoading(true)
    try {
      setEvents(await api<BaselineEvent[]>(
        `/api/baselines/${id}/events?date=${date.format('YYYY-MM-DD')}`))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载事件失败')
    } finally {
      setEventsLoading(false)
    }
  }

  useEffect(() => {
    load()
    const timer = window.setInterval(() => load(true), 30000)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(() => {
    if (tab === 'events' && activeId) loadEvents(activeId)
  }, [tab, activeId, eventDate])

  const active = useMemo(() => rows.find((row) => row.id === activeId), [rows, activeId])

  const evaluate = async (row: BaselineRow) => {
    try {
      const result = await post<BaselineRow>(`/api/baselines/${row.id}/evaluate`)
      message.success(`已评估：${STATUS_LABEL[result.status] || result.status}`)
      await load(true)
      if (activeId === row.id) await loadEvents(row.id)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '评估失败')
    }
  }

  const gotoTask = (workflowId: number) => {
    nav(`/dev/${workflowId}`)
  }

  const columns = [
    {
      title: '基线',
      render: (_: unknown, row: BaselineRow) => (
        <Space direction="vertical" size={0}>
          <Space>
            <Typography.Text strong>{row.name}</Typography.Text>
            <Tag color={row.level === 'P1' ? 'red' : row.level === 'P2' ? 'blue' : 'default'}>
              {row.level}
            </Tag>
            {!row.enabled && <Tag>已停用</Tag>}
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {row.projectName || row.projectCode} · {row.memberCount} 个工作流
            {row.owner ? ` · 负责人 ${row.owner}` : ''}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '承诺 / 预警',
      width: 150,
      render: (_: unknown, row: BaselineRow) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{row.deadlineTime}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            提前 {row.warnBeforeMin} 分钟
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '今日状态',
      width: 130,
      render: (_: unknown, row: BaselineRow) => (
        <Tooltip title={row.detail}>
          <Tag color={STATUS_COLOR[row.status] || 'default'}>
            {STATUS_LABEL[row.status] || row.status}
          </Tag>
        </Tooltip>
      ),
    },
    {
      title: '预计完成',
      width: 190,
      render: (_: unknown, row: BaselineRow) => (
        <Space direction="vertical" size={0}>
          <span>{fmt(row.predictedFinish, 'HH:mm')}</span>
          <Typography.Text
            type={row.lateMinutes && row.lateMinutes > 0 ? 'danger' : 'secondary'}
            style={{ fontSize: 12 }}
          >
            {latencyText(row.lateMinutes)}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '操作',
      width: 260,
      render: (_: unknown, row: BaselineRow) => {
        const first = row.members[0]
        const configTip = row.members.length === 0
          ? '基线没有成员，请先到任务右侧「配置」里启用基线'
          : row.members.length > 1
            ? `含 ${row.members.length} 个任务，进入任一任务详情改这条基线`
            : '进入任务详情右侧「配置」修改'
        return (
          <Space size={4} wrap>
            <Button
              size="small"
              icon={<ClockCircleOutlined />}
              onClick={() => { setActiveId(row.id); setTab('events') }}
            >
              事件
            </Button>
            {writable && (
              <Button size="small" icon={<ThunderboltOutlined />} onClick={() => evaluate(row)}>
                评估
              </Button>
            )}
            <Tooltip title={configTip}>
              <Button
                size="small"
                icon={<SettingOutlined />}
                disabled={!first}
                onClick={() => gotoTask(first.workflowId)}
              >
                去任务配置
              </Button>
            </Tooltip>
          </Space>
        )
      },
    },
  ]

  return (
    <div>
      <PageHeader
        title="任务基线"
        description="承诺完成时间 + 历史 P90 耗时 + 关键路径预测；每分钟评估一次，提前预警、违约与恢复都会留事件。本页只读，修改请进任务详情右侧「配置」"
        extra={(
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => load()}>刷新</Button>
        )}
      />

      {error ? (
        <Card>
          <ErrorState title="「任务基线」加载失败" message={error} onRetry={() => void load()} />
        </Card>
      ) : loading && rows.length === 0 ? (
        <Card><LoadingState rows={6} /></Card>
      ) : (
      <Card>
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: 'list',
              label: '基线列表',
              children: (
                <Table<BaselineRow>
                  rowKey="id"
                  size="small"
                  loading={loading}
                  dataSource={rows}
                  pagination={false}
                  scroll={{ x: 1000 }}
                  locale={{
                    emptyText: tableEmpty(
                      '还没有基线',
                      '在数据开发打开任务，右侧「配置」面板的「任务基线」里设置承诺时间',
                    ),
                  }}
                  expandable={{
                    expandedRowRender: (row) => (
                      <Table<BaselineMember>
                        rowKey="workflowId"
                        size="small"
                        pagination={false}
                        dataSource={row.members}
                        columns={[
                          { title: '工作流', render: (_, m) => `${m.name} (${m.code})` },
                          { title: '今日状态', dataIndex: 'state', width: 110 },
                          {
                            title: '预计完成', width: 150,
                            render: (_, m) => fmt(m.predictedFinish, 'HH:mm'),
                          },
                          {
                            title: '估算依据', width: 240,
                            render: (_, m) => (
                              <Tooltip title={m.detail}>
                                <span>
                                  {m.fallback ? '当前均值兜底' : '历史 P90'}
                                  {m.failed ? ' · 运行失败' : ''}
                                </span>
                              </Tooltip>
                            ),
                          },
                          {
                            title: '操作', width: 90,
                            render: (_, m) => (
                              <Button
                                size="small"
                                type="link"
                                onClick={() => gotoTask(m.workflowId)}
                              >
                                配置
                              </Button>
                            ),
                          },
                        ]}
                      />
                    ),
                  }}
                  columns={columns}
                />
              ),
            },
            {
              key: 'events',
              label: '事件时间线',
              children: (
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <Space wrap>
                    <Select
                      style={{ minWidth: 240 }}
                      placeholder="选择基线"
                      value={activeId}
                      onChange={setActiveId}
                      options={rows.map((row) => ({
                        value: row.id,
                        label: `${row.name}（${row.deadlineTime}）`,
                      }))}
                    />
                    <DatePicker
                      value={eventDate}
                      onChange={(value) => setEventDate(value || dayjs())}
                      allowClear={false}
                    />
                    <Button
                      icon={<ReloadOutlined />}
                      loading={eventsLoading}
                      disabled={!activeId}
                      onClick={() => loadEvents()}
                    >
                      刷新
                    </Button>
                  </Space>
                  {active ? (
                    <Typography.Text type="secondary">
                      承诺 {active.deadlineTime} · 提前 {active.warnBeforeMin} 分钟 ·
                      今日状态 {STATUS_LABEL[active.status] || active.status}
                    </Typography.Text>
                  ) : null}
                  {!activeId ? (
                    <Card size="small">
                      <Typography.Text type="secondary">先选择一条基线查看事件</Typography.Text>
                    </Card>
                  ) : events.length === 0 ? (
                    <Card size="small">
                      <Typography.Text type="secondary">
                        {eventDate.format('MM-DD')} 还没有事件；进入预警窗口或违约后会自动写入
                      </Typography.Text>
                    </Card>
                  ) : (
                    <Timeline
                      items={events.map((event) => ({
                        color: KIND_DOT[event.kind] || 'gray',
                        children: (
                          <Space direction="vertical" size={2}>
                            <Space wrap>
                              <Tag color={KIND_COLOR[event.kind] || 'default'}>
                                {KIND_LABEL[event.kind] || event.kind}
                              </Tag>
                              <Typography.Text type="secondary">
                                {fmt(event.notifiedAt, 'MM-DD HH:mm:ss')}
                              </Typography.Text>
                              {event.lateMinutes !== null && event.lateMinutes !== undefined && (
                                <Typography.Text
                                  type={event.lateMinutes > 0 ? 'danger' : 'secondary'}
                                >
                                  {latencyText(event.lateMinutes)}
                                </Typography.Text>
                              )}
                            </Space>
                            <span>{event.detail}</span>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              预计完成 {fmt(event.predictedFinish, 'MM-DD HH:mm')} · 承诺{' '}
                              {fmt(event.deadlineAt, 'MM-DD HH:mm')}
                            </Typography.Text>
                          </Space>
                        ),
                      }))}
                    />
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
      )}
    </div>
  )
}
