import { useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag,
  Tooltip, Typography, message,
} from 'antd'
import {
  CloudServerOutlined, PlusOutlined, ReloadOutlined, RocketOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, del, post } from '../api'
import PageHeader from '../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import { canAny } from '../permissions'

type Job = {
  id: number
  project_id: number
  project_name?: string
  project_code?: string
  code: string
  name: string
  status: 'DRAFT' | 'SUBMITTING' | 'STARTING' | 'RUNNING' | 'RESTARTING' | 'STOPPING' | 'STOPPED' | 'FAILED'
  flink_job_id?: string
  session_mode?: 'ISOLATED' | 'SHARED'
  env?: 'DEV' | 'PROD'
  published_version?: number
  prod_status?: string
  last_error?: string
  submitted_at?: string
  updated_at?: string
}

type ProjectOption = { id: number; code: string; name: string }

/**
 * 流开发页能拿到的最小集群状态（BUG-TC6-12）：
 * 只有「能不能提交」+ 原因 + 集群模式；槽位/作业数等平台画像仍归系统监控（system:manage）。
 */
type ClusterStatus = {
  ready: boolean
  reason: string
  sessionMode: string
}

const STATUS_META: Record<string, { color: string; text: string }> = {
  DRAFT: { color: 'default', text: '草稿' },
  SUBMITTING: { color: 'processing', text: '提交中' },
  STARTING: { color: 'processing', text: '启动中' },
  RUNNING: { color: 'processing', text: '运行中' },
  // v450：Flink 重启中不再是「运行中」，避免把不可用的作业误认为正常
  RESTARTING: { color: 'warning', text: '重启中' },
  STOPPING: { color: 'processing', text: '停止中' },
  STOPPED: { color: 'default', text: '已停止' },
  FAILED: { color: 'red', text: '失败' },
}

const ACTIVE_STATES = ['SUBMITTING', 'STARTING', 'RUNNING', 'RESTARTING', 'STOPPING']

function fmtTime(value?: string) {
  if (!value) return '-'
  return value.replace('T', ' ').replace(/\.\d+.*$/, '').replace('Z', '')
}

export default function Stream() {
  const nav = useNavigate()
  // REQ-DW-3：数据集成入口可带 ?create=1（可加 ?projectId=）直接进新建
  const [searchParams, setSearchParams] = useSearchParams()
  const canWrite = canAny('flink:write')
  const [projects, setProjects] = useState<ProjectOption[]>([])
  const [projectId, setProjectId] = useState<number | undefined>(() => {
    const fromUrl = Number(searchParams.get('projectId'))
    return Number.isFinite(fromUrl) && fromUrl > 0 ? fromUrl : undefined
  })
  const [jobs, setJobs] = useState<Job[]>([])
  const [clusterStatus, setClusterStatus] = useState<ClusterStatus | null>(null)
  // 取不到状态时的一句人话（403 = 当前角色看不到集群，不是页面坏了）
  const [clusterHint, setClusterHint] = useState('')
  const [loading, setLoading] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  useEffect(() => {
    api<ProjectOption[]>('/api/projects')
      .then((list) => setProjects(Array.isArray(list) ? list : []))
      .catch(() => setProjects([]))
  }, [])

  const load = async (showLoading = true) => {
    if (showLoading) setLoading(true)
    try {
      const path = projectId == null
        ? '/api/flink/jobs'
        : `/api/flink/jobs?projectId=${projectId}`
      setLoadError('')
      const rows = await api<Job[]>(path)
      setJobs(Array.isArray(rows) ? rows : [])
      api<ClusterStatus>('/api/flink/cluster/status')
        .then((value) => { setClusterStatus(value); setClusterHint('') })
        .catch((e: any) => {
          setClusterStatus(null)
          setClusterHint(e?.status === 403
            ? '集群详情需平台管理员查看；当前角色也没有提交流任务的权限，暂不能判断能否提交'
            : `暂时取不到集群状态：${e?.message || '请稍后重试'}`)
        })
    } catch (e: any) {
      setLoadError(e?.message || '加载流任务失败')
      setJobs([])
      message.error(e?.message || '加载流任务失败')
    } finally {
      if (showLoading) setLoading(false)
    }
  }

  useEffect(() => { load() }, [projectId])

  const openCreate = () => {
    form.setFieldsValue({
      projectId: projectId ?? projects[0]?.id,
      name: '',
      code: '',
    })
    setOpen(true)
  }

  // REQ-DW-3：从同步任务入口跳转过来时自动弹新建；消费一次后清掉参数，避免刷新重复弹出
  useEffect(() => {
    if (!searchParams.get('create') || !canWrite || projects.length === 0) return
    openCreate()
    const next = new URLSearchParams(searchParams)
    next.delete('create')
    setSearchParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams, canWrite, projects.length])

  const remove = async (row: Job) => {
    try {
      await del(`/api/flink/jobs/${row.id}`)
      message.success('已删除')
      await load(false)
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    }
  }

  const create = async () => {
    try {
      const values = await form.validateFields()
      setSaving(true)
      const created = await post<Job>('/api/flink/jobs', {
        projectId: values.projectId,
        code: values.code,
        name: values.name,
        description: values.description,
        sqlText: '',
        sessionMode: 'ISOLATED',
      })
      message.success('已创建，进入编辑器')
      setOpen(false)
      nav(`/stream/${created.id}`)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '创建失败')
    } finally {
      setSaving(false)
    }
  }

  const columns = useMemo(() => [
    {
      title: '任务',
      key: 'name',
      render: (_: any, row: Job) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{row.name}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.code}</Typography.Text>
        </Space>
      ),
    },
    { title: '项目', dataIndex: 'project_name', key: 'project_name', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: string, row: Job) => {
        const meta = STATUS_META[value] || STATUS_META.DRAFT
        const tag = <Tag color={meta.color}>{meta.text}</Tag>
        return row.last_error && (value === 'RESTARTING' || value === 'FAILED')
          ? <Tooltip title={row.last_error}>{tag}</Tooltip>
          : tag
      },
    },
    {
      title: '发布',
      key: 'published',
      width: 100,
      render: (_: any, row: Job) => (row.published_version
        ? <Tag color="green">v{row.published_version}</Tag>
        : <Tag>未发布</Tag>),
    },
    {
      title: '会话',
      dataIndex: 'session_mode',
      key: 'session_mode',
      width: 90,
      render: (value: string) => (value === 'SHARED'
        ? <Tooltip title="多个作业共用一个 Flink SQL 会话，建过的表可以复用">共享</Tooltip>
        : <Tooltip title="每次运行单独开会话，互不影响，停止后会话关闭">独立</Tooltip>),
    },
    {
      title: 'Flink Job',
      dataIndex: 'flink_job_id',
      key: 'flink_job_id',
      width: 190,
      render: (value: string) => value
        ? <Typography.Text copyable style={{ fontSize: 12 }}>{value}</Typography.Text>
        : '-',
    },
    { title: '提交时间', dataIndex: 'submitted_at', key: 'submitted_at', width: 165, render: fmtTime },
    {
      title: '操作',
      key: 'actions',
      width: 90,
      render: (_: any, row: Job) => (
        <Space size={0}>
          {/* UX-DW10REST-9（REQ-DW-10 R1）：删红字「打开」入口，点行即进编辑器（onRow+守卫）；删除是动作，保留 */}
          {canWrite && (
            <Popconfirm
              title={`删除任务「${row.name}」？`}
              description="脚本和发布快照会一起删除，不可恢复"
              okText="删除" okButtonProps={{ danger: true }} cancelText="取消"
              disabled={ACTIVE_STATES.includes(row.status)}
              onConfirm={() => remove(row)}
            >
              <Button size="small" type="link" danger
                      disabled={ACTIVE_STATES.includes(row.status)}
                      title={ACTIVE_STATES.includes(row.status)
                        ? '运行中/重启中的任务先停止再删除' : '删除任务'}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ], [canWrite])

  return (
    <div>
      <PageHeader
        title="流开发（FlinkSQL）"
        description="编写、校验、提交 FlinkSQL 流任务；共享/独立会话可选，运行指标与 DAG 在编辑器里查看。"
        extra={(
          <Space>
            <Select allowClear style={{ width: 220 }} placeholder="全部项目"
                    value={projectId} onChange={setProjectId}
                    options={projects.map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))} />
            <Button icon={<ReloadOutlined />} onClick={() => load()}>刷新</Button>
            {canWrite && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建流任务</Button>
            )}
          </Space>
        )}
      />

      <Card
        size="small"
        title={<Space><CloudServerOutlined />Flink 集群</Space>}
        style={{ marginBottom: 16 }}
        extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
          会话集群（主节点 JobManager + 工作节点 TaskManager）
        </Typography.Text>}
      >
        {clusterHint ? (
          <Typography.Text type="secondary">{clusterHint}</Typography.Text>
        ) : !clusterStatus ? (
          <Typography.Text type="secondary">正在检查集群状态…</Typography.Text>
        ) : (
          <Space size={16} wrap>
            <Tag color={clusterStatus.ready ? 'green' : 'orange'}>
              {clusterStatus.ready ? '可提交' : '暂不可提交'}
            </Tag>
            <Typography.Text type="secondary">
              集群模式：{clusterStatus.sessionMode === 'SESSION'
                ? '会话集群（1 个主节点 JobManager + 多个工作节点 TaskManager，作业共享）'
                : clusterStatus.sessionMode}
            </Typography.Text>
            {clusterStatus.ready ? (
              <Typography.Text type="secondary">资源已就绪，提交的作业可直接运行</Typography.Text>
            ) : (
              <Typography.Text>{clusterStatus.reason}</Typography.Text>
            )}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              槽位、作业数等集群详情在「运维 → 系统监控」，仅平台管理员可看
            </Typography.Text>
          </Space>
        )}
      </Card>

      {loadError ? (
        <Card>
          <ErrorState title="「流任务」加载失败" message={loadError} onRetry={() => void load()} />
        </Card>
      ) : loading && jobs.length === 0 ? (
        <Card><LoadingState rows={6} /></Card>
      ) : (
        <Card>
          <Table<Job>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={jobs}
            columns={columns as any}
            onRow={(row) => ({
              style: { cursor: 'pointer' },
              onClick: (event) => {
                // UX-DW10REST-9（REQ-DW-10 R1）：点击记录=进编辑器；行内控件（复制/删除等）保持自身行为
                const target = event.target as HTMLElement
                if (target.closest('a, button, input, textarea, .ant-select')) return
                nav(`/stream/${row.id}`)
              },
            })}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            locale={{ emptyText: (
              <EmptyState title="还没有流任务" hint="新建一个 FlinkSQL 流任务开始"
                          action={<Button type="primary" icon={<RocketOutlined />} onClick={openCreate}>新建流任务</Button>} />
            ) }}
          />
        </Card>
      )}

      <Modal
        open={open} title="新建流任务" onCancel={() => setOpen(false)}
        onOk={create} confirmLoading={saving} okText="创建并编辑"
      >
        <Form form={form} layout="vertical">
          <Form.Item name="projectId" label="项目" rules={[{ required: true, message: '请选择项目' }]}>
            <Select options={projects.map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))} />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}
                       style={{ flex: 1 }}>
              <Input maxLength={128} />
            </Form.Item>
            <Form.Item name="code" label="编码" rules={[
              { required: true, message: '请输入编码' },
              { pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/, message: '字母开头，只含字母/数字/下划线' },
            ]} style={{ flex: 1 }}>
              <Input placeholder="例如 stream_orders" />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="说明">
            <Input maxLength={500} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
