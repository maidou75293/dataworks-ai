import { useEffect, useRef, useState } from 'react'
import {
  Alert, Button, Card, Descriptions, Drawer, Input, Modal, Popconfirm, Select, Space, Table, Tooltip,
  message,
} from 'antd'
import {
  EyeOutlined, ExportOutlined, ReloadOutlined, RollbackOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, post } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import MemoTableRow from '../components/MemoTableRow'
import { ImeSearch, useDebounced } from '../components/ImeInput'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { RunStateTag, canRerun, formatDuration, runStateOptions } from '../status'
import type { Instance, TaskInstance, Workflow } from '../types'

const STATES = ['queued', 'running', 'success', 'failed', 'cancelled', 'upstream_failed']
const DONE_STATES = ['success', 'failed', 'cancelled', 'upstream_failed']

export default function Instances() {
  const nav = useNavigate()
  const [params, setParams] = useSearchParams()
  // 支持从工作流编辑器 / 数据开发 / 告警直接深链进来：
  // /instances?workflowId=12&state=failed&instance=345
  // 已打开过的深链实例：避免 URL 回写后重复打开，同时允许后续 ?instance= 变化
  const deepInstance = useRef(0)
  const [instances, setInstances] = useState<Instance[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [wfFilter, setWfFilter] = useState<number | undefined>(
    () => Number(params.get('workflowId')) || undefined)
  const [wfName, setWfName] = useState('')
  const [stateFilter, setStateFilter] = useState<string | undefined>(
    () => params.get('state') || undefined)
  const [projectFilter, setProjectFilter] = useState<number | undefined>(
    () => Number(params.get('projectId')) || undefined)
  const [projects, setProjects] = useState<{ id: number; code: string; name: string }[]>([])
  const [kwInput, setKwInput] = useState(() => params.get('keyword') || '')
  const [keyword, setKeyword] = useState(() => params.get('keyword') || '')
  const [detail, setDetail] = useState<(Instance & { tasks: TaskInstance[] })>()
  const [detailOpen, setDetailOpen] = useState(false)
  const [rerunBusy, setRerunBusy] = useState<number>()
  const [log, setLog] = useState('')
  const [logOpen, setLogOpen] = useState(false)

  // 轮询和手动改筛选会并发：只接受最后一次请求的结果，
  // 否则旧筛选的响应回来会把新筛选的结果覆盖掉
  const loadSeq = useRef(0)
  // silent=true 用于 10 秒轮询：不闪加载遮罩，失败也不把已有列表替换成错误页
  const load = async (silent = false) => {
    const seq = ++loadSeq.current
    if (!silent) setLoading(true)
    try {
      // 筛选必须下发到后端：只取 200 条再在前端过滤，会漏掉更早的匹配实例
      const qs = new URLSearchParams({ limit: '500' })
      if (stateFilter) qs.set('state', stateFilter)
      if (projectFilter) qs.set('projectId', String(projectFilter))
      if (wfFilter) qs.set('workflowId', String(wfFilter))
      if (keyword) qs.set('keyword', keyword)
      const rows = await api<Instance[]>(`/api/ops/instances?${qs.toString()}`)
      if (seq !== loadSeq.current) return
      setInstances(rows)
      setError('')
    } catch (e) {
      if (seq !== loadSeq.current) return
      if (!silent) setError(e instanceof Error ? e.message : '未知原因')
    } finally {
      if (seq === loadSeq.current && !silent) setLoading(false)
    }
  }

  // v452：keyword 触发接口请求，300ms 防抖
  const debouncedKeyword = useDebounced(keyword, 300)

  useEffect(() => {
    load()
    const timer = window.setInterval(() => load(true), 10000)
    return () => window.clearInterval(timer)
  }, [stateFilter, projectFilter, debouncedKeyword, wfFilter])

  useEffect(() => {
    api<{ id: number; code: string; name: string }[]>('/api/projects')
      .then(setProjects)
      .catch(() => setProjects([]))
  }, [])

  // 只看某个工作流时，顶部说明里要显示工作流名字（列表里的行也能拿到，但空列表时拿不到）
  useEffect(() => {
    if (!wfFilter) {
      setWfName('')
      return
    }
    let alive = true
    api<Workflow>(`/api/workflows/${wfFilter}`)
      .then((wf) => { if (alive) setWfName(wf.name) })
      .catch(() => { if (alive) setWfName('') })
    return () => { alive = false }
  }, [wfFilter])

  // 筛选条件回写 URL：刷新不丢、可以把链接发给别人一起看
  useEffect(() => {
    const next = new URLSearchParams()
    if (wfFilter) next.set('workflowId', String(wfFilter))
    if (projectFilter) next.set('projectId', String(projectFilter))
    if (stateFilter) next.set('state', stateFilter)
    if (keyword) next.set('keyword', keyword)
    // 详情抽屉打开时保留 instance，刷新/分享链接后才能恢复到同一条实例
    if (detailOpen && detail?.id) next.set('instance', String(detail.id))
    setParams(next, { replace: true })
  }, [wfFilter, projectFilter, stateFilter, keyword, detailOpen, detail?.id])

  const openDetail = async (row: Instance) => {
    try {
      setDetail(await api<Instance & { tasks: TaskInstance[] }>(`/api/ops/instances/${row.id}`))
      setDetailOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载实例详情失败')
    }
  }

  // 深链带 instance=… 时直接打开详情；同一路由内后续变化也要响应
  const instanceParam = Number(params.get('instance')) || 0
  useEffect(() => {
    if (!instanceParam) {
      deepInstance.current = 0
      return
    }
    if (instanceParam === deepInstance.current) return
    deepInstance.current = instanceParam
    openDetail({ id: instanceParam } as Instance)
  }, [instanceParam])

  // 详情打开后如果实例还没跑完，3 秒拉一次：重跑新建的实例会从
  // 排队 → 运行 → 成功/失败自己走完，不用用户手动点刷新。
  const detailId = detail?.id
  const detailState = detail?.state
  useEffect(() => {
    if (!detailOpen || !detailId || DONE_STATES.includes(detailState || '')) return
    let alive = true
    const timer = window.setInterval(async () => {
      try {
        const fresh = await api<Instance & { tasks: TaskInstance[] }>(`/api/ops/instances/${detailId}`)
        if (alive) setDetail(fresh)
      } catch {
        // 单次轮询失败不打断页面，下一轮继续
      }
    }, 3000)
    return () => { alive = false; window.clearInterval(timer) }
  }, [detailOpen, detailId, detailState])

  const rerun = async (row: Instance) => {
    setRerunBusy(row.id)
    try {
      const result = await post<{ runId: string; instanceId?: number }>(
        `/api/ops/instances/${row.id}/rerun`)
      message.success(result.instanceId
        ? `已重跑，新实例 #${result.instanceId}`
        : `已重跑 ${result.runId}`)
      await load()
      // 展开的详情直接切到新实例：用户重跑就是为了看这次跑得怎么样，
      // 让抽屉还停在旧的失败实例上等于没反馈。
      if (result.instanceId && result.instanceId > 0) {
        await openDetail({ id: result.instanceId } as Instance)
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重跑失败')
    } finally {
      setRerunBusy(undefined)
    }
  }

  const openLog = async (instanceId: number, taskCode: string) => {
    try {
      const data = await api<{ log: string }>(`/api/ops/instances/${instanceId}/tasks/${taskCode}/log`)
      setLog(data.log || '（空日志）')
      setLogOpen(true)
    } catch (e) {
      message.error(`「任务日志」加载失败：${e instanceof Error ? e.message : '未知原因'}`)
    }
  }

  return (
    <div>
      <PageHeader
        title="运行实例"
        description="所有文件的运行实例；点击任意一行查看任务明细与日志（每 10 秒刷新）"
        extra={
          <Space wrap>
            <ImeSearch
              allowClear
              style={{ width: 220 }}
              placeholder="文件 / DAG / 运行 ID"
              value={kwInput}
              onChange={(v) => {
                setKwInput(v)
                if (!v) setKeyword('')
              }}
              onSearch={(value) => setKeyword(value.trim())}
            />
            <Select
              allowClear
              style={{ width: 180 }}
              placeholder="项目"
              value={projectFilter}
              onChange={setProjectFilter}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
            <Select
              allowClear
              style={{ width: 160 }}
              placeholder="状态"
              value={stateFilter}
              onChange={setStateFilter}
              options={runStateOptions(STATES)}
            />
            <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
          </Space>
        }
      />

      {wfFilter ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={wfName ? `只看文件「${wfName}」的运行实例` : `只看文件 #${wfFilter} 的运行实例`}
          action={(
            <Space>
              <Button size="small" onClick={() => nav(`/dev/${wfFilter}`)}>打开文件</Button>
              <Button size="small" type="link" onClick={() => setWfFilter(undefined)}>
                查看全部实例
              </Button>
            </Space>
          )}
        />
      ) : null}

      <Card>
        {error ? (
          <ErrorState title="「运行实例列表」加载失败" message={error} onRetry={() => load()} />
        ) : (
          <Table
            components={{ body: { row: MemoTableRow } }}
            rowKey="id"
            loading={loading}
            dataSource={instances}
            pagination={{ pageSize: 20 }}
            scroll={{ x: 1320 }}
            locale={{
              emptyText: wfFilter
                ? tableEmpty('这个文件还没有运行实例', '在编辑器里点「触发」，或等待定时调度运行')
                : tableEmpty('还没有运行实例', '发布文件后触发，或等待定时调度'),
            }}
            onRow={(row) => ({
              style: { cursor: 'pointer' },
              onClick: (event) => {
                // UX-DW10REST-9（REQ-DW-10 R1）：点击记录=打开详情；行内控件保持自身行为
                const target = event.target as HTMLElement
                if (target.closest('a, button, input, textarea, .ant-select')) return
                openDetail(row)
              },
            })}
            columns={[
              {
                title: '项目 / 文件', width: 220,
                render: (_, row) => `${row.project_name || row.project_code} / ${row.workflow_name || row.workflow_code}`,
              },
              { title: '业务日期', dataIndex: 'biz_date', width: 110 },
              { title: '状态', dataIndex: 'state', width: 120, render: (state) => <RunStateTag state={state} /> },
              {
                // 失败原因直接列出来；长了截断，悬停看全文
                title: '失败原因', dataIndex: 'error_message', width: 240,
                ellipsis: { showTitle: false },
                render: (text: string | null) => text ? (
                  <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                    <span className="failure-reason-text">{text}</span>
                  </Tooltip>
                ) : '-',
              },
              {
                title: '开始时间', dataIndex: 'started_at', width: 160,
                render: (v) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
              },
              {
                title: '结束时间', dataIndex: 'ended_at', width: 160,
                render: (v) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
              },
              // DAG ID 在列表里又长又难扫，详情抽屉里有；换成更常用的耗时
              {
                title: '耗时', width: 100,
                render: (_, row) => formatDuration(row.started_at, row.ended_at, row.state),
              },
              {
                title: '操作', width: 110,
                render: (_, row) => (
                  <Space>
                    {/* UX-DW10REST-9：删独立「详情」按钮，点行即开详情（onRow+守卫）；重跑是动作，保留 */}
                    {can('ops:run') && canRerun(row.state) && (
                      // 这个业务日期已经有在跑/排队的实例时不能再重跑：
                      // 两个 run 会同时写同一批目标表。后端也会拦，这里提前置灰并说明原因。
                      row.active_instance_id && row.active_instance_id !== row.id ? (
                        <Tooltip
                          title={`业务日期 ${row.biz_date || '当天'} 已有运行中的实例 #${row.active_instance_id}，等它结束后再重跑`}
                        >
                          <Button size="small" icon={<RollbackOutlined />} disabled>
                            重跑
                          </Button>
                        </Tooltip>
                      ) : (
                        <Popconfirm
                          title="重跑这个实例？"
                          description={`会按业务日期 ${row.biz_date || '当天'} 新建一次运行，原实例记录保留。`}
                          okText="重跑"
                          onConfirm={() => rerun(row)}
                        >
                          <Button
                            size="small"
                            icon={<RollbackOutlined />}
                            loading={rerunBusy === row.id}
                            onClick={(e) => e.stopPropagation()}
                          >
                            重跑
                          </Button>
                        </Popconfirm>
                      )
                    )}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Drawer
        title={detail
          ? `实例 #${detail.id} · ${detail.workflow_name} / ${detail.biz_date || ''}`
          : '实例详情'}
        open={detailOpen}
        width={820}
        onClose={() => setDetailOpen(false)}
      >
        {detail && (
          <>
            <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="状态"><RunStateTag state={detail.state} /></Descriptions.Item>
              <Descriptions.Item label="业务日期">{detail.biz_date}</Descriptions.Item>
              <Descriptions.Item label="项目 / 文件" span={2}>
                {detail.project_name || detail.project_code} /{' '}
                <a onClick={() => nav(`/dev/${detail.workflow_id}`)}>{detail.workflow_name}</a>
                {detail.workflow_id !== wfFilter ? (
                  <Button
                    size="small"
                    type="link"
                    icon={<ExportOutlined />}
                    onClick={() => {
                      setWfFilter(detail.workflow_id)
                      setDetailOpen(false)
                    }}
                  >
                    只看这个文件
                  </Button>
                ) : null}
              </Descriptions.Item>
              <Descriptions.Item label="DAG" span={2}>{detail.dag_id}</Descriptions.Item>
              <Descriptions.Item label="运行 ID" span={2}>{detail.run_id}</Descriptions.Item>
              <Descriptions.Item label="开始时间">
                {detail.started_at ? dayjs(detail.started_at).format('YYYY-MM-DD HH:mm:ss') : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="结束时间">
                {detail.ended_at ? dayjs(detail.ended_at).format('YYYY-MM-DD HH:mm:ss') : '-'}
              </Descriptions.Item>
            </Descriptions>
            {detail.error_message ? (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 16 }}
                message="失败原因"
                description={<div className="failure-reason">{detail.error_message}</div>}
              />
            ) : null}
            <Table<TaskInstance>
              rowKey="id"
              dataSource={detail.tasks}
              pagination={false}
              size="small"
              locale={{ emptyText: tableEmpty('暂无任务明细', '任务状态由调度服务轮询同步') }}
              expandable={{
                rowExpandable: (row) => Boolean(row.error_message),
                expandedRowRender: (row) => <div className="failure-reason">{row.error_message}</div>,
              }}
              columns={[
                { title: '任务', dataIndex: 'task_code' },
                { title: '状态', dataIndex: 'state', render: (state) => <RunStateTag state={state} /> },
                { title: '尝试', dataIndex: 'try_number', width: 80 },
                {
                  title: '失败原因', dataIndex: 'error_message', width: 220,
                  ellipsis: { showTitle: false },
                  render: (text: string | null) => text ? (
                    <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                      <span className="failure-reason-text">{text}</span>
                    </Tooltip>
                  ) : '-',
                },
                {
                  title: '操作', width: 100,
                  render: (_, row) => (
                    <Button size="small" icon={<EyeOutlined />} onClick={() => openLog(detail.id, row.task_code)}>
                      日志
                    </Button>
                  ),
                },
              ]}
            />
          </>
        )}
      </Drawer>

      <Modal title="任务日志" open={logOpen} width={900} footer={null} onCancel={() => setLogOpen(false)}>
        <pre className="code-block log-block">{log}</pre>
      </Modal>
    </div>
  )
}
