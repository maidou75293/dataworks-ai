import { useEffect, useRef, useState } from 'react'
import { autoCode } from '../autoCode'
import {
  Alert, Button, Card, Col, Drawer, Form, Input, Modal, Popconfirm, Row, Select, Space,
  Table, Tag, Tooltip, Typography, message,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import * as echarts from 'echarts'
import dayjs from 'dayjs'
import { api, errMsg, del, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import type { DataSource, Project } from '../types'

type ChartType = 'TABLE' | 'LINE' | 'BAR' | 'PIE' | 'KPI'

/** 看板参数：图表 SQL 里写 :name，查看时按这里的类型和默认值绑定。 */
type BiParam = {
  name: string
  type?: string
  required?: boolean
  defaultValue?: string
}

const PARAM_TYPES = [
  { value: 'STRING', label: '文本' },
  { value: 'INT', label: '整数' },
  { value: 'DECIMAL', label: '小数' },
  { value: 'DATE', label: '日期' },
  { value: 'DATETIME', label: '时间' },
]

type BiChart = {
  id?: number
  name: string
  chartType: ChartType
  datasourceId?: number
  sqlText: string
  xColumn?: string
  yColumn?: string
  seriesColumn?: string
}

type BiDashboard = {
  id: number
  project_id: number
  project_name?: string
  code: string
  name: string
  description?: string
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  version: number
  chart_count: number
  release_version?: number
  release_published_at?: string
  draft_dirty?: boolean
  smoke_ok?: boolean
  updated_at: string
  params?: BiParam[]
}

type ChartData = {
  name: string
  chartType: ChartType
  xColumn?: string
  yColumn?: string
  seriesColumn?: string
  columns: string[]
  rows: Record<string, unknown>[]
  rowCount?: number
  truncated?: boolean
  elapsedMs?: number
  error?: string
}

type DashboardData = {
  id: number
  name: string
  env: 'DEV' | 'PRD'
  version?: number
  params?: BiParam[]
  paramValues?: Record<string, unknown>
  charts: ChartData[]
}

const STATUS: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PUBLISHED: { color: 'green', label: '已发布' },
  OFFLINE: { color: 'orange', label: '已下线' },
}

const TYPES: { value: ChartType; label: string }[] = [
  { value: 'TABLE', label: '表格' },
  { value: 'LINE', label: '折线图' },
  { value: 'BAR', label: '柱状图' },
  { value: 'PIE', label: '饼图' },
  { value: 'KPI', label: '指标卡' },
]

/**
 * 数值型单元格：null/空串/非数字返回 null。
 *
 * <p>不能写成 {@code Number(v ?? 0)}：聚合结果的空值（常见于 LEFT JOIN / 分组缺行）
 * 会被渲染成 0，折线/柱状图看不出缺口；KPI 与表格也会显示 "null"/"undefined"。</p>
 */
function numericValue(value: unknown): number | null {
  if (value === null || value === undefined || typeof value === 'boolean') return null
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  if (typeof value === 'string' && value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

function ChartView({ chart }: { chart: ChartData }) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (chart.error || chart.chartType === 'TABLE' || chart.chartType === 'KPI') return
    if (!ref.current) return
    const inst = echarts.init(ref.current)
    const rows = chart.rows || []
    const columns = chart.columns || []
    // 配置的列必须真实存在于结果里，否则退回第一列/自动识别，避免整张图静默全 0。
    const xCol = chart.xColumn && columns.includes(chart.xColumn)
      ? chart.xColumn : (columns[0] || '')
    const seriesCol = chart.seriesColumn && columns.includes(chart.seriesColumn)
      ? chart.seriesColumn : undefined
    const numeric = columns.filter((c) => rows.some((r) => numericValue(r[c]) !== null))
    const configuredY = chart.yColumn
      ? chart.yColumn.split(',').map((s) => s.trim()).filter((c) => columns.includes(c))
      : []
    const yCols = configuredY.length ? configuredY : numeric
    const seriesType = chart.chartType.toLowerCase()
    let option: echarts.EChartsOption

    if (chart.chartType === 'PIE') {
      const yCol = yCols[0] || ''
      const data = rows
        .map((r) => ({ name: String(r[xCol] ?? ''), value: numericValue(yCol ? r[yCol] : null) }))
        .filter((item) => item.value !== null)
      option = {
        tooltip: { trigger: 'item' },
        series: [{
          type: 'pie', radius: '62%',
          data: data.map((item) => ({ name: item.name, value: item.value as number })),
        }],
      }
    } else if (seriesCol) {
      const xValues = Array.from(new Set(rows.map((r) => String(r[xCol] ?? ''))))
      const groups = Array.from(new Set(rows.map((r) => String(r[seriesCol] ?? ''))))
      const yCol = yCols[0] || ''
      option = {
        tooltip: { trigger: 'axis' },
        legend: {},
        xAxis: { type: 'category', data: xValues },
        yAxis: { type: 'value' },
        series: groups.map((g) => ({
          name: g, type: seriesType as 'line' | 'bar',
          // 缺失的分组/行返回 null（折线断开），不要补 0。
          data: xValues.map((x) => {
            const hit = rows.find((r) => String(r[xCol] ?? '') === x
              && String(r[seriesCol] ?? '') === g)
            return hit && yCol ? numericValue(hit[yCol]) : null
          }),
        })),
      }
    } else {
      option = {
        tooltip: { trigger: 'axis' },
        legend: {},
        xAxis: { type: 'category', data: rows.map((r) => String(r[xCol] ?? '')) },
        yAxis: { type: 'value' },
        series: yCols.map((c) => ({
          name: c, type: seriesType as 'line' | 'bar',
          data: rows.map((r) => numericValue(r[c])),
        })),
      }
    }
    inst.setOption(option)
    const onResize = () => inst.resize()
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      inst.dispose()
    }
  }, [chart])

  if (chart.error) {
    return <Alert type="error" showIcon message={chart.error} />
  }
  if (chart.chartType === 'TABLE') {
    return (
      <Table
        size="small"
        rowKey={(_, i) => String(i)}
        dataSource={chart.rows}
        pagination={{ pageSize: 8, hideOnSinglePage: true }}
        scroll={{ x: true }}
        columns={(chart.columns || []).map((col) => ({
          title: col,
          dataIndex: col,
          ellipsis: true,
          // 用 == null 同时覆盖 null 与 undefined；否则缺失值会显示字符串 "undefined"。
          render: (v: unknown) => v == null
            ? <Typography.Text type="secondary">NULL</Typography.Text> : String(v),
        }))}
      />
    )
  }
  if (chart.chartType === 'KPI') {
    const columns = chart.columns || []
    const yCol = (chart.yColumn && columns.includes(chart.yColumn))
      ? chart.yColumn
      : (columns.find((c) => numericValue((chart.rows[0] || {})[c]) !== null) || columns[0] || '')
    const value = yCol ? (chart.rows[0] || {})[yCol] : undefined
    return (
      <div style={{ padding: '24px 8px', textAlign: 'center' }}>
        <div style={{ fontSize: 34, fontWeight: 600 }}>
          {value === undefined || value === null ? '-' : String(value)}
        </div>
        <Typography.Text type="secondary">{yCol || chart.name}</Typography.Text>
      </div>
    )
  }
  return <div ref={ref} style={{ height: 280, width: '100%' }} />
}

export default function Bi() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectFilter, setProjectFilter] = useState<number>()
  const [rows, setRows] = useState<BiDashboard[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [form] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<BiDashboard>()
  const [charts, setCharts] = useState<BiChart[]>([])
  const [params, setParams] = useState<BiParam[]>([])
  const [paramValues, setParamValues] = useState<Record<string, string>>({})
  const [datasources, setDatasources] = useState<DataSource[]>([])
  const [viewOpen, setViewOpen] = useState(false)
  const [viewData, setViewData] = useState<DashboardData>()
  const [viewEnv, setViewEnv] = useState<'PRD' | 'DEV'>('PRD')
  const [viewLoading, setViewLoading] = useState(false)
  const [smokeOpen, setSmokeOpen] = useState(false)
  const [smokeRows, setSmokeRows] = useState<{ name: string; rowCount: number; elapsedMs: number }[]>([])
  const [savedId, setSavedId] = useState<number>()
  // BUG-TC6-3：发布版本列表 + 按版本回滚
  const [verOpen, setVerOpen] = useState(false)
  const [verDash, setVerDash] = useState<BiDashboard>()
  const [versions, setVersions] = useState<{ version: number; published_by?: string
    published_at?: string; chart_count: number; current: boolean }[]>([])
  const [verLoading, setVerLoading] = useState(false)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const [projs, dashes] = await Promise.all([
        api<Project[]>('/api/projects'),
        api<BiDashboard[]>(`/api/bi/dashboards${projectFilter ? `?projectId=${projectFilter}` : ''}`),
      ])
      setProjects(projs || [])
      setRows(dashes || [])
    } catch (e: any) {
      setError(e.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [projectFilter])

  const loadDatasources = async (projectId?: number) => {
    if (!projectId) {
      setDatasources([])
      return
    }
    try {
      setDatasources(await api<DataSource[]>(`/api/projects/${projectId}/datasources`))
    } catch {
      setDatasources([])
    }
  }

  const openCreate = () => {
    setEditing(undefined)
    setSavedId(undefined)
    setCharts([])
    setParams([])
    form.resetFields()
    form.setFieldsValue({ projectId: projectFilter || projects[0]?.id, code: autoCode('dash') })
    loadDatasources(projectFilter || projects[0]?.id)
    setOpen(true)
  }

  const openEdit = async (row: BiDashboard) => {
    setEditing(row)
    setSavedId(row.id)
    try {
      const detail = await api<BiDashboard & { charts: BiChart[] }>(`/api/bi/dashboards/${row.id}`)
      form.setFieldsValue({
        projectId: detail.project_id,
        code: detail.code,
        name: detail.name,
        description: detail.description,
      })
      setCharts((detail.charts || []).map((c) => ({ ...c })))
      setParams((detail.params || []).map((p) => ({ ...p })))
      await loadDatasources(detail.project_id)
      setOpen(true)
    } catch (e: any) {
      message.error(`「看板」加载失败：${e.message || '未知原因'}`)
    }
  }

  const submit = async () => {
    const values = await form.validateFields()
    if (!charts.length) {
      message.error('请至少新增一个图表')
      return
    }
    const body = {
      projectId: values.projectId,
      code: values.code,
      name: values.name,
      description: values.description,
      params: params.filter((p) => p.name?.trim()).map((p) => ({
        name: p.name.trim(),
        type: p.type || 'STRING',
        required: Boolean(p.required),
        defaultValue: p.defaultValue?.trim() || null,
      })),
      charts: charts.map((c) => ({
        chartType: c.chartType,
        name: c.name,
        datasourceId: c.datasourceId,
        sqlText: c.sqlText,
        xColumn: c.xColumn,
        yColumn: c.yColumn,
        seriesColumn: c.seriesColumn,
      })),
    }
    try {
      if (editing) {
        await put(`/api/bi/dashboards/${editing.id}`, body)
        message.success('已保存到开发环境；线上仍是已发布版本')
      } else {
        const created = await post<{ id: number }>('/api/bi/dashboards', body)
        setSavedId(created.id)
        message.success('已创建到开发环境')
      }
      setOpen(false)
      await load()
    } catch (e: any) {
      message.error(e.message || '保存失败')
    }
  }

  const smoke = async (row: BiDashboard) => {
    try {
      const result = await post<{ charts: { name: string; rowCount: number; elapsedMs: number }[] }>(
        `/api/bi/dashboards/${row.id}/smoke`)
      setSmokeRows(result.charts || [])
      setSmokeOpen(true)
      message.success('DEV 跑通通过')
      await load()
    } catch (e: any) {
      message.error(e.message || 'DEV 跑通失败')
    }
  }

  const publish = async (row: BiDashboard) => {
    try {
      const result = await post<{ version: number; unchanged: boolean }>(
        `/api/bi/dashboards/${row.id}/publish`)
      message.success(result.unchanged
        ? `内容未变，线上仍为 v${result.version}`
        : `已发布到线上：v${result.version}`)
      await load()
    } catch (e: any) {
      message.error(e.message || '发布失败')
    }
  }

  const loadVersions = async (row: BiDashboard) => {
    setVerLoading(true)
    try {
      setVersions(await api<any[]>(`/api/bi/dashboards/${row.id}/releases`))
    } catch (e: any) {
      message.error(`「版本历史」加载失败：${e.message || '未知原因'}`)
    } finally {
      setVerLoading(false)
    }
  }

  const openVersions = async (row: BiDashboard) => {
    setVerDash(row)
    setVerOpen(true)
    await loadVersions(row)
  }

  /** 回滚 = 用旧快照的图表/参数/名称生成新版本，并把开发态恢复成那一版。 */
  const rollback = async (version: number) => {
    if (!verDash) return
    try {
      const result = await post<{ version: number }>(
        `/api/bi/dashboards/${verDash.id}/rollback/${version}`)
      message.success(`已回滚到 v${version}，生成新版本 v${result.version}`)
      await loadVersions(verDash)
      await load()
    } catch (e: any) {
      message.error(e.message || '回滚失败')
    }
  }

  const offline = async (row: BiDashboard) => {
    try {
      await post(`/api/bi/dashboards/${row.id}/offline`)
      message.success('已下线')
      await load()
    } catch (e: any) {
      message.error(e.message || '下线失败')
    }
  }

  const remove = async (row: BiDashboard) => {
    try {
      await del(`/api/bi/dashboards/${row.id}`)
      message.success('已删除')
      await load()
    } catch (e: any) {
      message.error(e.message || '删除失败')
    }
  }

  /** 查看参数：先按默认值填入输入框，用户改完点「查询」再带上值。 */
  const applyViewData = (data: DashboardData | undefined) => {
    setViewData(data)
    if (!data) return
    const values: Record<string, string> = {}
    for (const p of data.params || []) {
      const v = data.paramValues?.[p.name] ?? p.defaultValue ?? ''
      values[p.name] = v == null ? '' : String(v)
    }
    setParamValues(values)
  }

  const openView = async (row: BiDashboard, env: 'PRD' | 'DEV') => {
    setViewEnv(env)
    setViewOpen(true)
    setViewData(undefined)
    setViewLoading(true)
    try {
      const data = await api<DashboardData>(`/api/bi/dashboards/${row.id}/data?env=${env}`)
      applyViewData(data)
    } catch (e: any) {
      message.error(`「看板」加载失败：${e.message || '未知原因'}`)
      setViewOpen(false)
    } finally {
      setViewLoading(false)
    }
  }

  // env 显式传入：切换「线上/开发态」时 setViewEnv 还没生效，
  // 直接读闭包里的 viewEnv 会拿旧环境重查（标题变了、数据还是旧的）。
  const reloadView = async (env: 'PRD' | 'DEV' = viewEnv) => {
    if (!viewData) return
    setViewLoading(true)
    try {
      const filled = Object.fromEntries(
        Object.entries(paramValues).filter(([, v]) => v !== '' && v !== undefined),
      )
      const query = Object.keys(filled).length
        ? `&params=${encodeURIComponent(JSON.stringify(filled))}` : ''
      applyViewData(await api<DashboardData>(
        `/api/bi/dashboards/${viewData.id}/data?env=${env}${query}`))
    } catch (e: any) {
      message.error(`「看板数据」加载失败：${e.message || '未知原因'}`)
    } finally {
      setViewLoading(false)
    }
  }

  const publishReady = (row: BiDashboard) =>
    Boolean(row.smoke_ok) || (Boolean(row.release_version) && !row.draft_dirty)

  const updateChart = (index: number, patch: Partial<BiChart>) => {
    setCharts(charts.map((c, i) => (i === index ? { ...c, ...patch } : c)))
  }

  const updateParam = (index: number, patch: Partial<BiParam>) => {
    setParams(params.map((p, i) => (i === index ? { ...p, ...patch } : p)))
  }

  return (
    <div>
      <PageHeader
        title="数据可视化"
        description="用只读 SQL 组装看板：开发态调试 → DEV 跑通 → 发布到线上；线上是发布快照。"
        extra={
          <Space wrap>
            <Select
              allowClear
              style={{ width: 220 }}
              placeholder="全部项目"
              value={projectFilter}
              onChange={setProjectFilter}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
            <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
            {can('bi:write') && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}
                disabled={!projects.length}>新建看板</Button>
            )}
          </Space>
        }
      />

      <Card size="small">
        {error ? (
          <ErrorState message={error} onRetry={load} />
        ) : (
          <Table<BiDashboard>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={rows}
            onRow={(row) => ({
              style: { cursor: 'pointer' },
              onClick: (event) => {
                // REQ-DW-10：点击记录=打开详情。已发布开线上视图，未发布开发态预览；抽屉内可切换环境
                const target = event.target as HTMLElement
                if (target.closest('a, button, input, textarea, .ant-select')) return
                openView(row, row.release_version ? 'PRD' : 'DEV')
              },
            })}
            locale={{ emptyText: tableEmpty('还没有看板', '点击「新建看板」，用只读 SQL 组装图表') }}
            columns={[
              { title: '名称', dataIndex: 'name', ellipsis: true },
              { title: '编码', dataIndex: 'code', width: 150, ellipsis: true },
              { title: '项目', dataIndex: 'project_name', width: 140, ellipsis: true },
              {
                title: '状态', dataIndex: 'status', width: 90,
                render: (v: string) => <Tag color={STATUS[v]?.color}>{STATUS[v]?.label || v}</Tag>,
              },
              {
                title: '图表', dataIndex: 'chart_count', width: 70,
              },
              {
                title: '生产版本', width: 150,
                render: (_, row) => (
                  <Space size={4} wrap>
                    <span>{row.release_version ? `v${row.release_version}` : '未发布'}</span>
                    {row.draft_dirty ? <Tag color="orange">有未发布改动</Tag> : null}
                  </Space>
                ),
              },
              {
                title: '验证', width: 80,
                render: (_, row) => row.smoke_ok
                  ? <Tag color="green">已通过</Tag> : <Tag>未通过</Tag>,
              },
              {
                title: '更新时间', dataIndex: 'updated_at', width: 130,
                render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm') : '-'),
              },
              {
                title: '操作', width: 420,
                render: (_, row) => (
                  <Space size={4} wrap>
                    {/* REQ-DW-10：删独立「查看」按钮，点行即打开（onRow）；开发态预览仍是显式动作 */}
                    {can('bi:write') && (
                      <Button size="small" onClick={() => openView(row, 'DEV')}>预览开发态</Button>
                    )}
                    {can('bi:write') && (
                      <Button size="small" onClick={() => smoke(row)}>DEV 跑通</Button>
                    )}
                    {can('bi:publish') && (row.status === 'PUBLISHED' ? (
                      <Popconfirm title={`下线看板「${row.name}」？`}
                        description="下线后线上不再展示，修改后重新发布可恢复。"
                        okText="下线"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => offline(row)}>
                        <Button size="small">下线</Button>
                      </Popconfirm>
                    ) : (
                      <Tooltip title={publishReady(row) ? '' : '请先在 DEV 跑通'}>
                        <Button size="small" type="primary" ghost
                          disabled={!publishReady(row)}
                          onClick={() => publish(row)}>发布</Button>
                      </Tooltip>
                    ))}
                    {can('bi:write') && (
                      <Button size="small" icon={<EditOutlined />}
                        onClick={() => openEdit(row).catch(() => undefined)}>编辑</Button>
                    )}
                    <Button size="small" onClick={() => openVersions(row)}>版本</Button>
                    {can('bi:write') && (
                      <Popconfirm title={`删除看板「${row.name}」？`} okText="删除" okButtonProps={{ danger: true }} onConfirm={() => remove(row)}>
                        <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Drawer
        title={editing ? `编辑看板 ${editing.name}` : '新建看板'}
        open={open}
        width={980}
        onClose={() => setOpen(false)}
        extra={
          <Button type="primary" onClick={() => submit().catch((e) => message.error(errMsg(e)))}>
            保存
          </Button>
        }
      >
        {editing && editing.status === 'PUBLISHED' && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={`这里是开发环境定义：保存不影响线上；线上当前是 v${editing.release_version}。发布前需要重新在 DEV 跑通。`} />
        )}
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
                <Select
                  disabled={!!editing}
                  options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
                  onChange={(v) => loadDatasources(v)}
                />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="看板编码" name="code" extra="自动生成（前缀_日期_随机后缀），无需填写"
                rules={[{ required: true, message: '看板编码缺失，请关闭后重新新建' }]}>
                <Input disabled />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="看板名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="销售看板" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="说明" name="description">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>

        <Space style={{ marginBottom: 8 }}>
          <Typography.Text strong>参数（{params.length}）</Typography.Text>
          <Button size="small" icon={<PlusOutlined />}
            onClick={() => setParams([...params, { name: '', type: 'STRING', required: false }])}>
            新增参数
          </Button>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            图表 SQL 里写 :参数名；DEV 跑通按默认值执行，查看时可覆盖
          </Typography.Text>
        </Space>

        {params.map((p, index) => (
          <Card key={index} size="small" style={{ marginBottom: 8 }}>
            <Row gutter={12} align="middle">
              <Col span={6}>
                <Input value={p.name} placeholder="参数名，如 day"
                  onChange={(e) => updateParam(index, { name: e.target.value })} />
              </Col>
              <Col span={5}>
                <Select value={p.type || 'STRING'} style={{ width: '100%' }} options={PARAM_TYPES}
                  onChange={(v) => updateParam(index, { type: v })} />
              </Col>
              <Col span={8}>
                <Input value={p.defaultValue} placeholder="默认值（DEV 跑通用它执行）"
                  onChange={(e) => updateParam(index, { defaultValue: e.target.value })} />
              </Col>
              <Col span={3}>
                <Select value={p.required ? 'Y' : 'N'} style={{ width: '100%' }}
                  options={[{ value: 'Y', label: '必填' }, { value: 'N', label: '可选' }]}
                  onChange={(v) => updateParam(index, { required: v === 'Y' })} />
              </Col>
              <Col span={2}>
                <Button type="text" danger size="small" icon={<DeleteOutlined />}
                  onClick={() => setParams(params.filter((_, i) => i !== index))} />
              </Col>
            </Row>
          </Card>
        ))}

        <Space style={{ marginBottom: 8 }}>
          <Typography.Text strong>图表（{charts.length}）</Typography.Text>
          <Button size="small" icon={<PlusOutlined />} onClick={() => setCharts([...charts, {
            name: `图表 ${charts.length + 1}`, chartType: 'TABLE',
            datasourceId: datasources[0]?.id, sqlText: '',
          }])}>新增图表</Button>
        </Space>

        {charts.map((chart, index) => (
          <Card key={index} size="small" style={{ marginBottom: 8 }}
            title={chart.name || `图表 ${index + 1}`}
            extra={
              <Button type="text" danger size="small" icon={<DeleteOutlined />}
                onClick={() => setCharts(charts.filter((_, i) => i !== index))} />
            }>
            <Row gutter={12}>
              <Col span={6}>
                <Input value={chart.name} placeholder="图表名称"
                  onChange={(e) => updateChart(index, { name: e.target.value })} />
              </Col>
              <Col span={5}>
                <Select value={chart.chartType} style={{ width: '100%' }} options={TYPES}
                  onChange={(v) => updateChart(index, { chartType: v })} />
              </Col>
              <Col span={8}>
                <Select value={chart.datasourceId} style={{ width: '100%' }} placeholder="数据源"
                  options={datasources.map((d) => ({ value: d.id, label: d.name }))}
                  onChange={(v) => updateChart(index, { datasourceId: v })} />
              </Col>
              <Col span={5}>
                <Space size={4}>
                  <Input value={chart.xColumn} placeholder="X 列"
                    onChange={(e) => updateChart(index, { xColumn: e.target.value })} />
                  <Input value={chart.yColumn} placeholder="Y 列"
                    onChange={(e) => updateChart(index, { yColumn: e.target.value })} />
                </Space>
              </Col>
            </Row>
            <Input.TextArea
              style={{ marginTop: 8, fontFamily: 'monospace' }}
              rows={3}
              value={chart.sqlText}
              placeholder="select ...（只读单条 SQL）"
              onChange={(e) => updateChart(index, { sqlText: e.target.value })}
            />
          </Card>
        ))}
        {!charts.length && (
          <Alert type="warning" showIcon message="至少新增一个图表才能保存" />
        )}
      </Drawer>

      <Drawer
        title={`${viewData?.name || '看板'} · ${viewEnv === 'PRD' ? '线上' : '开发态'}${
          viewData?.version ? ` v${viewData.version}` : ''}`}
        open={viewOpen}
        width={1180}
        onClose={() => setViewOpen(false)}
        extra={
          <Space>
            {can('bi:write') && (
              <Select value={viewEnv} style={{ width: 140 }}
                onChange={(v) => {
                  setViewEnv(v)
                  // 切环境时参数按目标环境的定义重新取默认值
                  setParamValues({})
                  reloadView(v).catch(() => undefined)
                }}
                options={[{ value: 'PRD', label: '线上版本' }, { value: 'DEV', label: '开发态' }]} />
            )}
            <Button icon={<ReloadOutlined />} onClick={() => reloadView().catch(() => undefined)}
              loading={viewLoading}>刷新</Button>
          </Space>
        }
      >
        {viewLoading && <Typography.Text type="secondary">加载中…</Typography.Text>}
        {viewData && (viewData.params?.length ? (
          <Card size="small" style={{ marginBottom: 12 }} title="参数">
            <Space wrap>
              {viewData.params.map((p) => (
                <Space key={p.name} size={4}>
                  <Typography.Text>{p.name}{p.required ? ' *' : ''}</Typography.Text>
                  <Input
                    style={{ width: 180 }}
                    value={paramValues[p.name] ?? ''}
                    placeholder={p.defaultValue ? `默认 ${p.defaultValue}` : '值'}
                    onChange={(e) => setParamValues({ ...paramValues, [p.name]: e.target.value })}
                    onPressEnter={() => reloadView().catch(() => undefined)}
                  />
                </Space>
              ))}
              <Button type="primary" onClick={() => reloadView().catch(() => undefined)}
                loading={viewLoading}>查询</Button>
            </Space>
          </Card>
        ) : null)}
        {viewData && (
          <Row gutter={[12, 12]}>
            {viewData.charts.map((chart, i) => (
              <Col key={i} span={chart.chartType === 'KPI' ? 6 : 12}>
                <Card size="small" title={chart.name}
                  extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {chart.rowCount === undefined ? '' : `${chart.rowCount} 行`}
                    {chart.truncated ? '（已截断）' : ''}
                  </Typography.Text>}>
                  <ChartView chart={chart} />
                </Card>
              </Col>
            ))}
          </Row>
        )}
      </Drawer>

      <Modal
        title="DEV 跑通结果"
        open={smokeOpen}
        footer={<Button type="primary" onClick={() => setSmokeOpen(false)}>知道了</Button>}
        onCancel={() => setSmokeOpen(false)}
      >
        <Alert type="success" showIcon style={{ marginBottom: 12 }}
          message="全部图表执行成功，可以发布" />
        <Table
          size="small"
          rowKey="name"
          pagination={false}
          dataSource={smokeRows}
          columns={[
            { title: '图表', dataIndex: 'name' },
            { title: '行数', dataIndex: 'rowCount', width: 100 },
            { title: '耗时(ms)', dataIndex: 'elapsedMs', width: 110 },
          ]}
        />
      </Modal>

      {/* BUG-TC6-3：历史版本 + 按版本回滚（恢复旧快照的图表/参数/名称） */}
      <Modal
        title={`版本历史 · ${verDash?.name || ''}`}
        open={verOpen}
        width={820}
        onCancel={() => setVerOpen(false)}
        footer={<Space>
          <Button onClick={() => verDash && loadVersions(verDash)} loading={verLoading}>刷新</Button>
          <Button onClick={() => setVerOpen(false)}>关闭</Button>
        </Space>}
      >
        <Table
          size="small"
          rowKey="version"
          loading={verLoading}
          dataSource={versions}
          pagination={false}
          scroll={{ y: 420 }}
          locale={{ emptyText: tableEmpty('还没有发布过版本', '点「发布」后生成') }}
          columns={[
            {
              title: '版本', dataIndex: 'version', width: 110,
              render: (v: number, row) => (
                <Space size={4}>
                  <span>v{v}</span>
                  {row.current ? <Tag color="green">线上</Tag> : null}
                </Space>
              ),
            },
            { title: '发布人', dataIndex: 'published_by', width: 130 },
            {
              title: '发布时间', dataIndex: 'published_at', width: 160,
              render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
            },
            { title: '图表数', dataIndex: 'chart_count', width: 90 },
            {
              title: '操作', width: 90,
              render: (_, row) => (can('bi:publish') ? (
                <Popconfirm
                  title={`回滚到 v${row.version}？`}
                  description="会生成一个新版本，并把看板内容恢复成那一版"
                  okText="回滚"
                  okButtonProps={{ danger: true }}
                  cancelText="取消"
                  onConfirm={() => rollback(row.version)}
                >
                  <Button size="small" type="link">回滚</Button>
                </Popconfirm>
              ) : <Typography.Text type="secondary">-</Typography.Text>),
            },
          ]}
        />
      </Modal>
    </div>
  )
}
