import { useEffect, useMemo, useState } from 'react'
import { autoCode } from '../autoCode'
import {
  Alert, Button, Card, Col, Descriptions, Drawer, Form, Input, Modal, Popconfirm,
  Row, Select, Space, Switch, Table, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  ApiOutlined, DeleteOutlined, EditOutlined, HistoryOutlined, PlayCircleOutlined,
  PlusOutlined, ReloadOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { api, errMsg, del, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import type { DataService, DataSource, Project, ServiceParam } from '../types'

type TestResult = {
  ok: boolean
  columns: string[]
  rows: Record<string, unknown>[]
  rowCount: number
  truncated: boolean
  elapsedMs: number
}

type CallLog = {
  id: number
  caller: string
  ip: string
  status_code: number
  rows_returned: number
  elapsed_ms: number
  error?: string
  created_at: string
}

const STATUS: Record<string, { color: string; label: string }> = {
  DRAFT: { color: 'default', label: '草稿' },
  PUBLISHED: { color: 'green', label: '已发布' },
  OFFLINE: { color: 'orange', label: '已下线' },
}

const PARAM_TYPES = ['STRING', 'INT', 'LONG', 'DECIMAL', 'BOOL', 'DATE', 'DATETIME']

const SQL_SAMPLE = `select id, state, biz_date, started_at
from platform.wf_instance
where biz_date = :biz_date
order by id desc`

export default function Service() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectFilter, setProjectFilter] = useState<number>()
  const [rows, setRows] = useState<DataService[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataService>()
  const [datasources, setDatasources] = useState<DataSource[]>([])
  const [testing, setTesting] = useState(false)
  const [testValues, setTestValues] = useState<Record<string, string>>({})
  const [testResult, setTestResult] = useState<TestResult>()
  const [smokeOpen, setSmokeOpen] = useState(false)
  const [smokeSvc, setSmokeSvc] = useState<DataService>()
  const [smoking, setSmoking] = useState(false)
  const [smokeResult, setSmokeResult] = useState<TestResult>()
  const [callOpen, setCallOpen] = useState(false)
  const [callSvc, setCallSvc] = useState<DataService>()
  // Token 默认打码，避免调用说明弹窗一打开就把凭据摊在屏幕上
  const [tokenVisible, setTokenVisible] = useState(false)
  const [logOpen, setLogOpen] = useState(false)
  const [logSvc, setLogSvc] = useState<DataService>()
  const [calls, setCalls] = useState<CallLog[]>([])
  // BUG-TC6-3：发布版本列表 + 按版本回滚
  const [verOpen, setVerOpen] = useState(false)
  const [verSvc, setVerSvc] = useState<DataService>()
  const [versions, setVersions] = useState<{ version: number; method: string; datasource_id?: number
    published_by?: string; published_at?: string; sql_length: number; sql_preview?: string
    current: boolean }[]>([])
  const [verLoading, setVerLoading] = useState(false)
  const [form] = Form.useForm()
  const [smokeForm] = Form.useForm()
  const params = Form.useWatch('params', form) as ServiceParam[] | undefined
  const formProjectId = Form.useWatch('projectId', form) as number | undefined

  const loadProjects = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
  }

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const query = projectFilter ? `?projectId=${projectFilter}` : ''
      setRows(await api<DataService[]>(`/api/services${query}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载数据服务失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadProjects().catch((e) => message.error(errMsg(e)))
  }, [])

  useEffect(() => {
    load()
  }, [projectFilter])

  useEffect(() => {
    if (!formProjectId) {
      setDatasources([])
      return
    }
    api<DataSource[]>(`/api/projects/${formProjectId}/datasources`)
      .then(setDatasources)
      .catch(() => setDatasources([]))
  }, [formProjectId])

  const openCreate = () => {
    setEditing(undefined)
    setTestResult(undefined)
    setTestValues({})
    form.resetFields()
    form.setFieldsValue({
      projectId: projectFilter || projects[0]?.id,
      code: autoCode('svc'),
      method: 'GET',
      params: [],
      sqlText: SQL_SAMPLE,
    })
    setOpen(true)
  }

  const openEdit = (row: DataService) => {
    setEditing(row)
    setTestResult(undefined)
    setTestValues({})
    // 先清掉上一个服务的校验错误与未提交字段，避免串数据
    form.resetFields()
    form.setFieldsValue({
      projectId: row.project_id,
      code: row.code,
      name: row.name,
      method: row.method,
      datasourceId: row.datasource_id,
      description: row.description,
      sqlText: row.sql_text,
      params: row.params || [],
    })
    setOpen(true)
  }

  const payload = (values: any) => ({
    projectId: values.projectId,
    code: values.code,
    name: values.name,
    method: values.method,
    datasourceId: values.datasourceId,
    description: values.description,
    sqlText: values.sqlText,
    params: (values.params || []).filter((p: ServiceParam) => p?.name),
  })

  const submit = async () => {
    const values = await form.validateFields()
    try {
      if (editing) {
        await put(`/api/services/${editing.id}`, payload(values))
        if (editing.status === 'PUBLISHED') {
          message.success('已保存到开发环境；生产调用仍使用已发布版本，发布后才会生效')
        } else {
          message.success('已保存到开发环境')
        }
      } else {
        await post('/api/services', payload(values))
        message.success('已创建')
      }
      setOpen(false)
      await load()
    } catch (e: any) {
      message.error(e.message || '保存失败')
    }
  }

  /** 有 DEV 跑通结果，或内容与生产版本一致（例如下线后重新发布），才允许发布。 */
  const publishReady = (row: DataService) =>
    Boolean(row.smoke_ok) || (Boolean(row.release_version) && !row.draft_dirty)

  const openSmoke = (row: DataService) => {
    setSmokeSvc(row)
    smokeForm.resetFields()
    setSmokeResult(undefined)
    setSmokeOpen(true)
  }

  const runSmoke = async () => {
    if (!smokeSvc) return
    // BUG-TC6-13 ③：先按声明的必填参数做前端校验，缺了直接标红，不用「跑一次才知道少填」。
    let values: Record<string, string>
    try {
      values = await smokeForm.validateFields()
    } catch {
      return
    }
    setSmoking(true)
    setSmokeResult(undefined)
    try {
      const result = await post<TestResult & { smokeOk: boolean }>(
        `/api/services/${smokeSvc.id}/smoke`, { values })
      setSmokeResult(result)
      message.success(`DEV 跑通：${result.rowCount} 行，可以发布`)
      await load()
    } catch (e: any) {
      message.error(e.message || 'DEV 跑通失败')
    } finally {
      setSmoking(false)
    }
  }

  const runTest = async () => {
    const values = await form.validateFields(['projectId', 'datasourceId', 'sqlText', 'params'])
    if (!values.datasourceId) {
      message.error('请先选择数据源')
      return
    }
    setTesting(true)
    setTestResult(undefined)
    try {
      const result = await post<TestResult>('/api/services/test', {
        projectId: values.projectId,
        datasourceId: values.datasourceId,
        sqlText: values.sqlText,
        params: (values.params || []).filter((p: ServiceParam) => p?.name),
        values: testValues,
      })
      setTestResult(result)
    } catch (e: any) {
      message.error(e.message || '测试失败')
    } finally {
      setTesting(false)
    }
  }

  const publish = async (row: DataService) => {
    try {
      await post(`/api/services/${row.id}/publish`)
      message.success('已发布，可用 Token 调用')
      await load()
    } catch (e: any) {
      message.error(e.message || '发布失败')
    }
  }

  const offline = async (row: DataService) => {
    try {
      await post(`/api/services/${row.id}/offline`)
      message.success('已下线')
      await load()
    } catch (e: any) {
      message.error(e.message || '下线失败')
    }
  }

  const loadVersions = async (svc: DataService) => {
    setVerLoading(true)
    try {
      setVersions(await api<any[]>(`/api/services/${svc.id}/releases`))
    } catch (e: any) {
      message.error(e.message || '加载版本历史失败')
    } finally {
      setVerLoading(false)
    }
  }

  const openVersions = async (row: DataService) => {
    setVerSvc(row)
    setVerOpen(true)
    await loadVersions(row)
  }

  /** 回滚 = 用旧快照内容生成新版本，并把开发态同步成那一版（历史版本不覆盖）。 */
  const rollback = async (version: number) => {
    if (!verSvc) return
    try {
      const result = await post<{ version: number }>(`/api/services/${verSvc.id}/rollback/${version}`)
      message.success(`已回滚到 v${version}，生成新版本 v${result.version}`)
      await loadVersions(verSvc)
      await load()
    } catch (e: any) {
      message.error(e.message || '回滚失败')
    }
  }

  const remove = async (row: DataService) => {
    try {
      await del(`/api/services/${row.id}`)
      message.success('已删除')
      await load()
    } catch (e: any) {
      message.error(e.message || '删除失败')
    }
  }

  const openCall = async (row: DataService) => {
    try {
      setTokenVisible(false)
      setCallSvc(await api<DataService>(`/api/services/${row.id}`))
      setCallOpen(true)
    } catch (e: any) {
      message.error(e.message || '加载调用信息失败')
    }
  }

  const openLogs = async (row: DataService) => {
    try {
      setLogSvc(row)
      setCalls(await api<CallLog[]>(`/api/services/${row.id}/calls?limit=50`))
      setLogOpen(true)
    } catch (e: any) {
      message.error(e.message || '加载调用记录失败')
    }
  }

  const rotate = async () => {
    if (!callSvc) return
    try {
      const result = await post<{ token: string }>(`/api/services/${callSvc.id}/token`)
      setCallSvc({ ...callSvc, api_token: result.token })
      // 新 Token 只在这一刻能看到，直接展开让用户复制
      setTokenVisible(true)
      message.success('Token 已重置并显示新 Token，旧 Token 立即失效')
    } catch (e: any) {
      message.error(e.message || '重置失败')
    }
  }

  const endpoint = (row: DataService) => `${location.origin}/api/ds/${row.code}`

  const curl = useMemo(() => {
    if (!callSvc) return ''
    const url = endpoint(callSvc)
    const token = callSvc.api_token && tokenVisible ? callSvc.api_token : '<Token>'
    const sample = (callSvc.params || []).reduce<Record<string, string>>((acc, p) => {
      acc[p.name] = p.defaultValue || `<${p.name}>`
      return acc
    }, {})
    if (callSvc.method === 'GET') {
      const query = new URLSearchParams(sample).toString()
      return `curl -X GET '${url}${query ? `?${query}` : ''}' \\\n  -H 'X-API-Token: ${token}'`
    }
    return `curl -X POST '${url}' \\\n  -H 'Content-Type: application/json' \\\n  -H 'X-API-Token: ${token}' \\\n  -d '${JSON.stringify({ params: sample })}'`
  }, [callSvc, tokenVisible])

  return (
    <div>
      <PageHeader
        title="数据服务"
        description="把只读 SQL 发布成 HTTP API：支持命名参数、Token 鉴权、调用记录与审计。"
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
            <Button icon={<ReloadOutlined />} onClick={() => load()} loading={loading}>刷新</Button>
            {can('service:write') && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!projects.length}>
                新建服务
              </Button>
            )}
          </Space>
        }
      />

      <Card size="small">
        {error ? (
          <ErrorState message={error} onRetry={() => load()} />
        ) : (
          <Table<DataService>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={rows}
            tableLayout="fixed"
            scroll={{ x: 1210 }}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            locale={{ emptyText: tableEmpty('还没有数据服务', '点击「新建服务」，写一条 SELECT 就能发布成 API') }}
            columns={[
              {
                title: '名称 / 编码', width: 200,
                render: (_, row) => (
                  <div>
                    <div>{row.name}</div>
                    <Typography.Text type="secondary" code style={{ fontSize: 12 }}>{row.code}</Typography.Text>
                  </div>
                ),
              },
              { title: '项目', dataIndex: 'project_name', width: 100, ellipsis: true },
              {
                title: '方法', dataIndex: 'method', width: 70,
                render: (v: string) => <Tag color={v === 'GET' ? 'blue' : 'purple'}>{v}</Tag>,
              },
              {
                title: '状态', dataIndex: 'status', width: 90,
                render: (v: string) => <Tag color={STATUS[v]?.color}>{STATUS[v]?.label || v}</Tag>,
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
                render: (_, row) => (
                  row.smoke_ok
                    ? <Tag color="green">已通过</Tag>
                    : <Tag>未通过</Tag>
                ),
              },
              {
                title: '数据源', width: 110, ellipsis: true,
                render: (_, row) => row.datasource_name || <Typography.Text type="danger">未绑定</Typography.Text>,
              },
              {
                title: '24h 调用', width: 100,
                render: (_, row) => (
                  <span>
                    {row.calls_24h || 0}
                    {row.errors_24h ? <Typography.Text type="danger"> / {row.errors_24h} 错</Typography.Text> : null}
                  </span>
                ),
              },
              {
                title: '更新时间', dataIndex: 'updated_at', width: 130,
                render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
              },
              {
                title: '操作', width: 430,
                render: (_, row) => (
                  <Space size={4}>
                    {can('service:publish') && (row.status === 'PUBLISHED' ? (
                      <Popconfirm title={`下线服务「${row.name}」？`}
                        description="下线后调用会返回 403，修改后重新发布可恢复。"
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
                    {can('service:write') && (
                      <Button size="small" onClick={() => openSmoke(row)}>DEV 跑通</Button>
                    )}
                    {can('service:write') && (
                      <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
                    )}
                    <Button size="small" icon={<ApiOutlined />} onClick={() => openCall(row)}>调用</Button>
                    <Tooltip title="调用记录">
                      <Button size="small" icon={<HistoryOutlined />} onClick={() => openLogs(row)} />
                    </Tooltip>
                    <Button size="small" onClick={() => openVersions(row)}>版本</Button>
                    {can('service:write') && (
                      <Popconfirm title={`删除服务「${row.name}」？`} okText="删除" okButtonProps={{ danger: true }} onConfirm={() => remove(row)}>
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
        title={editing ? `编辑服务 ${editing.name}` : '新建数据服务'}
        open={open}
        width={880}
        onClose={() => { setOpen(false); setTestResult(undefined) }}
        extra={
          <Space>
            <Button icon={<PlayCircleOutlined />} loading={testing} onClick={() => runTest().catch((e) => message.error(errMsg(e)))}>
              测试
            </Button>
            <Button type="primary" onClick={() => submit().catch((e) => message.error(errMsg(e)))}>
              保存
            </Button>
          </Space>
        }
      >
        {editing && editing.status === 'PUBLISHED' && (
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={`这里是开发环境定义：保存不会影响线上；线上当前是 v${editing.release_version}。发布前需要重新在 DEV 跑通。`} />
        )}
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label="项目" name="projectId" rules={[{ required: true, message: '请选择项目' }]}>
                <Select
                  options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
                  onChange={() => form.setFieldValue('datasourceId', undefined)}
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="服务编码（即调用地址 /api/ds/{code}）"
                name="code"
                extra="自动生成（前缀_日期_随机后缀），无需填写"
                rules={[
                  { required: true, message: '服务编码缺失，请关闭后重新新建' },
                  { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/, message: '字母开头，只含字母/数字/下划线，2-64 位' },
                ]}
              >
                <Input disabled style={{ fontFamily: 'monospace' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label="服务名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="运行实例查询" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item label="请求方法" name="method" rules={[{ required: true }]}>
                <Select options={[{ value: 'GET' }, { value: 'POST' }]} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item label="数据源" name="datasourceId" rules={[{ required: true, message: '请选择数据源' }]}>
                <Select
                  placeholder="选择数据源"
                  options={datasources.map((d) => ({ value: d.id, label: `${d.name} · ${d.ds_type}` }))}
                />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} placeholder="给调用方看的说明" />
          </Form.Item>

          <Form.Item label="参数定义（SQL 里用 :参数名 引用）">
            <Form.List name="params">
              {(fields, { add, remove }) => (
                <div>
                  {fields.map((field) => (
                    <Space key={field.key} align="baseline" style={{ display: 'flex', marginBottom: 4 }}>
                      <Form.Item
                        name={[field.name, 'name']}
                        rules={[{ pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '参数名不合法' }]}
                        style={{ marginBottom: 0 }}
                      >
                        <Input placeholder="参数名，如 biz_date" style={{ width: 180, fontFamily: 'monospace' }} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'type']} initialValue="STRING" style={{ marginBottom: 0 }}>
                        <Select style={{ width: 120 }} options={PARAM_TYPES.map((value) => ({ value }))} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'required']} valuePropName="checked" style={{ marginBottom: 0 }}>
                        <Switch checkedChildren="必填" unCheckedChildren="可选" />
                      </Form.Item>
                      <Form.Item name={[field.name, 'defaultValue']} style={{ marginBottom: 0 }}>
                        <Input placeholder="默认值" style={{ width: 160 }} />
                      </Form.Item>
                      <Button size="small" danger onClick={() => remove(field.name)}>删除</Button>
                    </Space>
                  ))}
                  <Button size="small" icon={<PlusOutlined />} onClick={() => add({ type: 'STRING' })}>
                    新增参数
                  </Button>
                  <div style={{ marginTop: 4 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      与日期/时间列比较时请选 DATE / DATETIME：PostgreSQL 不会把字符串隐式转成日期。
                    </Typography.Text>
                  </div>
                </div>
              )}
            </Form.List>
          </Form.Item>

          <Form.Item
            label="SQL（只读：SELECT / WITH，最多返回 200 行）"
            name="sqlText"
            rules={[{ required: true, message: '请输入 SQL' }]}
          >
            <Input.TextArea
              rows={10}
              style={{ fontFamily: 'monospace', fontSize: 13 }}
              placeholder="select ... from ... where dt = :dt"
            />
          </Form.Item>

          <Card size="small" title="测试" style={{ marginBottom: 8 }}>
            {(params || []).filter((p) => p?.name).length > 0 && (
              <Space wrap style={{ marginBottom: 12 }}>
                {(params || []).filter((p) => p?.name).map((p) => (
                  <Input
                    key={p.name}
                    addonBefore={p.name}
                    placeholder={p.defaultValue || (p.required ? '必填' : '可选')}
                    value={testValues[p.name] || ''}
                    onChange={(e) => setTestValues({ ...testValues, [p.name]: e.target.value })}
                    style={{ width: 240 }}
                  />
                ))}
              </Space>
            )}
            {/* REQ-DW-8：结果不再内联在抽屉底部（用户看不到），改为执行成功后弹窗展示 */}
            <Typography.Text type="secondary">
              点右上角「测试」用当前表单执行（不会保存），最多返回 200 行；结果弹窗展示。
            </Typography.Text>
          </Card>
        </Form>
      </Drawer>

      <Modal
        title="测试结果"
        open={!!testResult}
        width={860}
        zIndex={1100}
        footer={<Button onClick={() => setTestResult(undefined)}>关闭</Button>}
        onCancel={() => setTestResult(undefined)}
      >
        {testResult && (
          <>
            <Alert
              type="success"
              showIcon
              message={`执行成功：返回 ${testResult.rowCount} 行，耗时 ${testResult.elapsedMs} ms${testResult.truncated ? '（已截断到 200 行）' : ''}`}
              style={{ marginBottom: 8 }}
            />
            <Table
              rowKey={(_, i) => String(i)}
              size="small"
              dataSource={testResult.rows}
              pagination={false}
              scroll={{ x: 'max-content', y: 420 }}
              columns={testResult.columns.map((col) => ({
                title: col,
                dataIndex: col,
                ellipsis: true,
                render: (v: unknown) => v === null ? <Typography.Text type="secondary">NULL</Typography.Text> : String(v),
              }))}
            />
          </>
        )}
      </Modal>

      <Modal
        title={`调用说明 · ${callSvc?.name || ''}`}
        open={callOpen}
        width={760}
        footer={null}
        onCancel={() => setCallOpen(false)}
      >
        {callSvc && (
          <>
            <Descriptions size="small" column={2} bordered style={{ marginBottom: 12 }}>
              <Descriptions.Item label="地址" span={2}>
                <Typography.Text copyable code>{endpoint(callSvc)}</Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="方法">{callSvc.method}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS[callSvc.status]?.color}>{STATUS[callSvc.status]?.label}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Token" span={2}>
                <Space wrap>
                  {callSvc.api_token ? (
                    <>
                      <Typography.Text code copyable={tokenVisible ? { text: callSvc.api_token } : false}>
                        {tokenVisible
                          ? callSvc.api_token
                          : `${callSvc.api_token.slice(0, 6)}••••••${callSvc.api_token.slice(-4)}`}
                      </Typography.Text>
                      <Button size="small" onClick={() => setTokenVisible((v) => !v)}>
                        {tokenVisible ? '隐藏' : '显示'}
                      </Button>
                    </>
                  ) : (
                    <Typography.Text code>{callSvc.token_masked || '无权限查看'}</Typography.Text>
                  )}
                  {can('service:publish') && (
                    <Popconfirm title="重置后旧 Token 立即失效，确定？" onConfirm={() => rotate()}>
                      <Button size="small">重置 Token</Button>
                    </Popconfirm>
                  )}
                </Space>
                <div style={{ fontSize: 12, color: 'var(--dw-text-3)', marginTop: 4 }}>
                  Token 默认隐藏；调用示例在显示后才会带上真实 Token。
                </div>
              </Descriptions.Item>
              {(callSvc.params || []).length > 0 && (
                <Descriptions.Item label="参数" span={2}>
                  {(callSvc.params || []).map((p) => (
                    <Tag key={p.name} color={p.required ? 'blue' : 'default'}>
                      {p.name}: {p.type}{p.required ? ' · 必填' : ''}{p.defaultValue ? ` · 默认 ${p.defaultValue}` : ''}
                    </Tag>
                  ))}
                </Descriptions.Item>
              )}
            </Descriptions>
            {callSvc.status !== 'PUBLISHED' && (
              <Alert type="warning" showIcon message="服务尚未发布，调用会返回 403。请先在列表点「发布」。" style={{ marginBottom: 12 }} />
            )}
            {callSvc.draft_dirty && (
              <Alert type="info" showIcon style={{ marginBottom: 12 }}
                message={`开发态有未发布的改动；线上仍返回 v${callSvc.release_version} 的结果。`} />
            )}
            <Typography.Text type="secondary">调用示例</Typography.Text>
            <pre className="code-block" style={{ marginTop: 8 }}>{curl}</pre>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              鉴权：请求头 X-API-Token 或 Authorization: Bearer {'<token>'}；最多返回 200 行；调用会记入安全中心审计。
            </Typography.Text>
          </>
        )}
      </Modal>

      <Modal
        title={`DEV 跑通 · ${smokeSvc?.name || ''}`}
        open={smokeOpen}
        width={720}
        okText="执行 DEV 跑通"
        confirmLoading={smoking}
        onOk={() => runSmoke()}
        onCancel={() => setSmokeOpen(false)}
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="用已保存的开发态 SQL 真实执行一次；通过后才能发布，修改任何内容都需要重新测试。" />
        {(smokeSvc?.params || []).length > 0 && (
          <Form form={smokeForm} layout="vertical" style={{ marginBottom: 12 }}>
            {(smokeSvc?.params || []).map((p) => (
              <Form.Item key={p.name} name={p.name}
                label={`${p.name}（${p.type}${p.required ? ' · 必填' : ''}）`}
                rules={[{ required: !!p.required, message: `请填写必填参数 ${p.name}` }]}>
                <Input placeholder={p.defaultValue ? `默认 ${p.defaultValue}` : ''} />
              </Form.Item>
            ))}
          </Form>
        )}
        {smokeResult && (
          <>
            <Alert type="success" showIcon style={{ marginBottom: 8 }}
              message={`DEV 跑通：${smokeResult.rowCount} 行，${smokeResult.elapsedMs} ms`} />
            <Table
              size="small"
              rowKey={(_, i) => String(i)}
              dataSource={smokeResult.rows}
              pagination={false}
              scroll={{ x: true }}
              columns={smokeResult.columns.map((col) => ({
                title: col,
                dataIndex: col,
                ellipsis: true,
                render: (v: unknown) => v === null
                  ? <Typography.Text type="secondary">NULL</Typography.Text> : String(v),
              }))}
            />
          </>
        )}
      </Modal>

      <Drawer
        title={`调用记录 · ${logSvc?.name || ''}`}
        open={logOpen}
        width={820}
        onClose={() => setLogOpen(false)}
      >
        <Table<CallLog>
          rowKey="id"
          size="small"
          dataSource={calls}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          locale={{ emptyText: tableEmpty('还没有调用记录', '发布后通过 /api/ds/{code} 调用') }}
          columns={[
            {
              title: '时间', dataIndex: 'created_at', width: 160,
              render: (v) => dayjs(v).format('MM-DD HH:mm:ss'),
            },
            { title: '调用方', dataIndex: 'caller', width: 120 },
            { title: 'IP', dataIndex: 'ip', width: 130 },
            {
              title: '结果', dataIndex: 'status_code', width: 90,
              render: (v: number) => <Tag color={v < 400 ? 'green' : 'red'}>{v}</Tag>,
            },
            { title: '行数', dataIndex: 'rows_returned', width: 80 },
            { title: '耗时(ms)', dataIndex: 'elapsed_ms', width: 90 },
            { title: '错误', dataIndex: 'error', ellipsis: true },
          ]}
        />
      </Drawer>

      {/* BUG-TC6-3：历史版本 + 按版本回滚 */}
      <Modal
        title={`版本历史 · ${verSvc?.name || ''}`}
        open={verOpen}
        width={860}
        onCancel={() => setVerOpen(false)}
        footer={<Space>
          <Button onClick={() => verSvc && loadVersions(verSvc)} loading={verLoading}>刷新</Button>
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
              title: '版本', dataIndex: 'version', width: 100,
              render: (v: number, row) => (
                <Space size={4}>
                  <span>v{v}</span>
                  {row.current ? <Tag color="green">线上</Tag> : null}
                </Space>
              ),
            },
            { title: '方法', dataIndex: 'method', width: 80 },
            { title: '发布人', dataIndex: 'published_by', width: 120 },
            {
              title: '发布时间', dataIndex: 'published_at', width: 150,
              render: (v: string) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
            },
            { title: 'SQL 长度', dataIndex: 'sql_length', width: 90 },
            { title: 'SQL 预览', dataIndex: 'sql_preview', ellipsis: true },
            {
              title: '操作', width: 90,
              render: (_, row) => (can('service:publish') ? (
                <Popconfirm
                  title={`回滚到 v${row.version}？`}
                  description="会生成一个新版本，并把开发态内容改成那一版"
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
