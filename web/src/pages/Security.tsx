import { useEffect, useState } from 'react'
import {
  Button, Card, Col, Form, Input, InputNumber, Modal, Popconfirm, Radio, Row, Select, Space, Statistic,
  Table, Tabs, Tag, Tooltip, Typography, message,
} from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { api, del, post, put } from '../api'
import PageHeader from '../components/PageHeader'
import { ImeSearch, useDebounced } from '../components/ImeInput'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { can, canAny, isPlatformTenant } from '../permissions'

type Summary = {
  loginSuccessToday: number
  loginFailedToday: number
  sensitiveToday: number
  failedToday: number
  auditToday: number
  activeSessions: number
  publishedServices: number
  serviceCallsToday: number
  serviceErrorsToday: number
  plaintextPasswords: number
}

type AuditRow = {
  id: number
  username: string
  action: string
  target?: string
  detail?: string
  result: string
  ip?: string
  actor_type?: string
  created_at: string
}

type SessionRow = {
  id: number
  username: string
  created_at: string
  expires_at: string
  active: boolean
}

const ACTION_LABEL: Record<string, string> = {
  LOGIN: '登录', LOGIN_FAILED: '登录失败', LOGIN_SSO: 'SSO 登录',
  PROJECT_CREATE: '新建项目', PROJECT_UPDATE: '修改项目', PROJECT_DELETE: '删除项目',
  DATASOURCE_CREATE: '新建数据源', DATASOURCE_UPDATE: '修改数据源', DATASOURCE_DELETE: '删除数据源',
  WORKFLOW_CREATE: '新建文件', WORKFLOW_UPDATE: '修改文件',
  VERSION_CREATE: '创建版本', PUBLISH_REQUEST: '申请发布', ROLLBACK: '回滚',
  SCHEDULE_UPDATE: '修改调度', TRIGGER: '手动触发', BACKFILL: '补数',
  SERVICE_CREATE: '新建服务', SERVICE_UPDATE: '修改服务', SERVICE_PUBLISH: '发布服务',
  SERVICE_OFFLINE: '下线服务', SERVICE_DELETE: '删除服务', SERVICE_TOKEN_ROTATE: '重置 Token',
  SERVICE_TEST: '测试服务', SERVICE_CALL: '接口调用', SESSION_REVOKE: '吊销会话',
  ALERT_RULE_CREATE: '新建告警规则', ALERT_RULE_UPDATE: '修改告警规则',
  ALERT_RULE_DELETE: '删除告警规则', ALERT_ACK: '确认告警',
  ALERT_CLOSE: '关闭告警', ALERT_DELETE: '删除告警记录',
  COLUMN_LEVEL_UPDATE: '列等级变更',
  DATA_GUARD_MODE: '切换数据网关', DATA_GUARD_DENY: '数据访问拦截',
  DATA_GUARD_MASK: '数据脱敏',
  ACCESS_REQUEST_CREATE: '提交权限申请', ACCESS_REQUEST_APPROVE_1: '权限申请一审',
  ACCESS_REQUEST_APPROVED: '权限申请通过', ACCESS_REQUEST_REJECTED: '权限申请驳回',
  ACCESS_REQUEST_REVOKE: '回收权限', ACCESS_REQUEST_CANCEL: '撤销权限申请',
}

type ColumnRow = {
  columnName: string
  dataType?: string
  sensitivity: string
  maskRule: string
  dataLevel?: string
}

type ClassRow = {
  id: number
  catalog: string
  schemaName: string
  tableName: string
  ownerUser: string
  dataLevel: string
  columnCount: number
  columns: ColumnRow[]
}

const LEVEL_META: Record<string, { color: string; label: string }> = {
  L1: { color: 'green', label: 'L1 公开' },
  L2: { color: 'blue', label: 'L2 内部' },
  L3: { color: 'orange', label: 'L3 敏感' },
  L4: { color: 'red', label: 'L4 机密' },
}

const SENSITIVITY_OPTIONS = [
  { value: 'NORMAL', label: '普通' },
  { value: 'PII', label: '个人信息' },
  { value: 'PHONE', label: '手机号' },
  { value: 'ID_CARD', label: '身份证' },
  { value: 'EMAIL', label: '邮箱' },
  { value: 'NAME', label: '姓名' },
  { value: 'AMOUNT', label: '金额' },
]

const MASK_OPTIONS = [
  { value: 'NONE', label: '不脱敏（L3/L4 用默认）' },
  { value: 'PLAIN', label: '明文' },
  { value: 'MASK', label: '遮盖 ***' },
  { value: 'TOKEN', label: '稳定令牌' },
  { value: 'PARTIAL', label: '部分遮盖' },
  { value: 'FULL', label: '全部遮盖' },
  { value: 'HASH', label: '哈希' },
  { value: 'NULL', label: '置空' },
]

const COLUMN_LEVEL_OPTIONS = Object.entries(LEVEL_META).map(([code, meta]) => ({
  value: code, label: meta.label,
}))

const ACTION_COLOR: Record<string, string> = {
  LOGIN_FAILED: 'red', PUBLISH_REQUEST: 'blue', ROLLBACK: 'orange',
  SERVICE_PUBLISH: 'green', SERVICE_OFFLINE: 'orange', SERVICE_TOKEN_ROTATE: 'gold',
  SERVICE_DELETE: 'red', SESSION_REVOKE: 'volcano',
  PROJECT_DELETE: 'red', DATASOURCE_DELETE: 'red',
  ALERT_RULE_CREATE: 'blue', ALERT_RULE_DELETE: 'red', ALERT_ACK: 'green',
}

export default function Security() {
  const [summary, setSummary] = useState<Summary>()
  const [audit, setAudit] = useState<AuditRow[]>([])
  const [sessions, setSessions] = useState<SessionRow[]>([])
  const [options, setOptions] = useState<{ actions: string[]; users: string[] }>({ actions: [], users: [] })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [username, setUsername] = useState<string>()
  const [userInput, setUserInput] = useState('')
  const [action, setAction] = useState<string>()
  const [result, setResult] = useState<string>()
  const [sensitive, setSensitive] = useState(false)
  const [kwInput, setKwInput] = useState('')
  const [keyword, setKeyword] = useState('')
  // 有 security:read 的人默认看审计；只有 data:classify / mask:manage 的人直接进数据分级
  const [tab, setTab] = useState(() => (can('security:read') ? 'audit' : 'classification'))
  // 数据分级与脱敏
  const [classRows, setClassRows] = useState<ClassRow[]>([])
  const [classLoading, setClassLoading] = useState(false)
  const [guardMode, setGuardMode] = useState('LOG')
  const [guardModes, setGuardModes] = useState<{ code: string; name: string; description: string }[]>([])
  const [editingTable, setEditingTable] = useState<ClassRow | null>(null)
  const [columnDraft, setColumnDraft] = useState<ColumnRow[]>([])
  const [registerOpen, setRegisterOpen] = useState(false)
  const [registerForm] = Form.useForm()

  const loadAudit = async () => {
    setLoading(true)
    setError('')
    try {
      const qs = new URLSearchParams()
      if (username) qs.set('username', username)
      if (action) qs.set('action', action)
      if (result) qs.set('result', result)
      if (sensitive) qs.set('sensitive', 'true')
      if (keyword) qs.set('keyword', keyword)
      qs.set('limit', '200')
      setAudit(await api<AuditRow[]>(`/api/security/audit?${qs.toString()}`))
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载审计日志失败')
    } finally {
      setLoading(false)
    }
  }

  const loadAll = async () => {
    try {
      const [s, opts, ss] = await Promise.all([
        api<Summary>('/api/security/summary'),
        api<{ actions: string[]; users: string[] }>('/api/security/audit/options'),
        api<SessionRow[]>('/api/security/sessions'),
      ])
      setSummary(s)
      setOptions(opts)
      setSessions(ss)
      await loadAudit()
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载安全中心失败')
    }
  }

  const loadClassification = async () => {
    setClassLoading(true)
    try {
      const [tableRes, guardRes] = await Promise.all([
        api<{ tables: ClassRow[] }>('/api/data-classification/tables'),
        api<{ mode: string; modes: { code: string; name: string; description: string }[] }>(
          '/api/data-classification/guard'),
      ])
      setClassRows(tableRes.tables || [])
      setGuardMode(guardRes.mode)
      setGuardModes(guardRes.modes || [])
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载数据分级失败')
    } finally {
      setClassLoading(false)
    }
  }

  const submitGuardMode = async (mode: string) => {
    try {
      // 切到 OFF = 全平台停用脱敏，后端要求 confirm=true 二次确认
      await put('/api/data-classification/guard', { dataGuardMode: mode, confirm: true })
      setGuardMode(mode)
      message.success(mode === 'ENFORCE'
        ? '已切到强制模式：无权限的表会被拦截，敏感列会脱敏'
        : mode === 'LOG'
          ? '已切到只记录：只记录会拦截/脱敏什么，数据原样返回'
          : '已关闭数据分级网关')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '切换失败')
    }
  }

  const saveGuardMode = (mode: string) => {
    if (mode === 'OFF') {
      Modal.confirm({
        title: '关闭数据分级网关？',
        content: '关闭后全平台所有租户的敏感列都会以明文返回，且不再拦截越权查询。'
          + '该操作会写入审计日志。',
        okText: '关闭',
        okButtonProps: { danger: true },
        cancelText: '取消',
        onOk: () => submitGuardMode(mode),
        onCancel: () => setGuardMode(guardMode),
      })
      return
    }
    void submitGuardMode(mode)
  }

  const openColumns = (row: ClassRow) => {
    setColumnDraft(row.columns.length
      ? row.columns.map((c) => ({ ...c, dataLevel: c.dataLevel || 'L2' }))
      : [{ columnName: '', sensitivity: 'NORMAL', maskRule: 'NONE', dataLevel: 'L2' }])
    setEditingTable(row)
  }

  // 改列等级是敏感操作（L3→L2 等于放开脱敏），先二次确认再落草稿。
  const changeColumnLevel = (index: number, level: string, columnName: string) => {
    const name = columnName || `第 ${index + 1} 列`
    Modal.confirm({
      title: `修改字段「${name}」等级为 ${LEVEL_META[level]?.label || level}？`,
      content: 'L1/L2 列会直接返回明文；L3/L4 列只对负责人、有列授权或有「查看未脱敏明文」权限的人返回明文。',
      okText: '修改',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => {
        setColumnDraft((prev) => prev.map((c, i) =>
          i === index ? { ...c, dataLevel: level } : c))
      },
    })
  }

  const saveColumns = async () => {
    if (!editingTable) return
    const columns = columnDraft.filter((c) => c.columnName.trim())
    try {
      await put(`/api/data-classification/tables/${editingTable.id}/columns`, { columns })
      message.success('字段分级已保存')
      setEditingTable(null)
      await loadClassification()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    }
  }

  const submitRegister = async () => {
    const values = await registerForm.validateFields()
    try {
      await post('/api/data-classification/tables', values)
      message.success('表已登记')
      setRegisterOpen(false)
      await loadClassification()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '登记失败')
    }
  }

  // ---------------- v482 权限控制总开关 ----------------
  const [permMode, setPermMode] = useState<'OFF' | 'TABLE' | 'COLUMN'>('TABLE')
  const [permEnforce, setPermEnforce] = useState<'LOG' | 'ENFORCE'>('ENFORCE')
  const [permThreshold, setPermThreshold] = useState(10)
  const [permSaving, setPermSaving] = useState(false)
  const [offOpen, setOffOpen] = useState(false)
  const [offInput, setOffInput] = useState('')

  useEffect(() => {
    api<{ mode: 'OFF' | 'TABLE' | 'COLUMN'; enforce: 'LOG' | 'ENFORCE'; smallResultThreshold: number }>(
      '/api/security/permission-mode')
      .then((v) => {
        setPermMode(v.mode)
        setPermEnforce(v.enforce)
        setPermThreshold(v.smallResultThreshold ?? 10)
      })
      .catch(() => { /* 非管理员或未启用时静默 */ })
  }, [])

  const savePermission = async (mode = permMode, confirm?: string) => {
    setPermSaving(true)
    try {
      const next = await put<any>('/api/security/permission-mode', {
        mode, enforce: permEnforce, smallResultThreshold: permThreshold, confirm,
      })
      setPermMode(next.mode)
      setPermEnforce(next.enforce)
      setPermThreshold(next.smallResultThreshold)
      message.success(mode === 'OFF' ? '已关闭权限控制（高风险操作，已记审计）' : '权限控制已更新')
      setOffOpen(false)
      setOffInput('')
    } catch (e: any) {
      message.error(e?.message || '保存失败')
    } finally {
      setPermSaving(false)
    }
  }

  useEffect(() => {
    // 按权限点决定加载哪些接口：STEWARD 没有 security:read，不应该在进入页面时收到 403
    if (can('security:read')) loadAll()
    if (canAny('data:classify', 'mask:manage', 'security:read')) loadClassification()
  }, [])

  // v452：keyword 触发审计接口请求，300ms 防抖
  const debouncedKeyword = useDebounced(keyword, 300)

  useEffect(() => {
    if (can('security:read')) loadAudit()
  }, [username, action, result, sensitive, debouncedKeyword])

  const focusAudit = (patch: { action?: string; result?: string; sensitive?: boolean }) => {
    setTab('audit')
    setUsername(undefined)
    setUserInput('')
    setAction(patch.action)
    setResult(patch.result)
    setSensitive(Boolean(patch.sensitive))
    setKeyword('')
    setKwInput('')
  }

  const revoke = async (row: SessionRow) => {
    try {
      await del(`/api/security/sessions/${row.id}`)
      message.success(`已吊销 ${row.username} 的会话`)
      const [nextSummary, nextSessions] = await Promise.all([
        api<Summary>('/api/security/summary'),
        api<SessionRow[]>('/api/security/sessions'),
      ])
      setSummary(nextSummary)
      setSessions(nextSessions)
      await loadAudit()
    } catch (e: any) {
      message.error(e.message || '吊销失败')
    }
  }

  const cards = summary ? [
    { title: '今日登录成功', value: summary.loginSuccessToday, onClick: () => focusAudit({ action: 'LOGIN' }) },
    { title: '今日登录失败', value: summary.loginFailedToday, onClick: () => focusAudit({ action: 'LOGIN_FAILED' }) },
    { title: '今日敏感操作', value: summary.sensitiveToday, onClick: () => focusAudit({ sensitive: true }) },
    { title: '在线会话', value: summary.activeSessions, onClick: () => setTab('sessions') },
    { title: '今日接口调用', value: summary.serviceCallsToday, onClick: () => focusAudit({ action: 'SERVICE_CALL' }) },
    { title: '今日失败审计', value: summary.failedToday, onClick: () => focusAudit({ result: 'FAILED' }) },
  ] : []

  return (
    <div>
      <PageHeader
        title="安全中心"
        description="登录、发布、回滚、数据服务调用等关键动作全部留痕；可查看并吊销在线会话。"
        extra={can('security:read')
          ? <Button icon={<ReloadOutlined />} onClick={() => loadAll()}>刷新</Button>
          : undefined}
      />

      {can('system:manage') && (
        <Card size="small" title="权限控制" style={{ marginBottom: 12 }}>
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <div>
              <Typography.Text strong>控制粒度</Typography.Text>
              <Radio.Group
                style={{ marginLeft: 12 }}
                value={permMode}
                onChange={(e) => {
                  const value = e.target.value as 'OFF' | 'TABLE' | 'COLUMN'
                  if (value === 'OFF') { setOffOpen(true); return }
                  setPermMode(value)
                }}
              >
                <Radio.Button value="OFF">关闭</Radio.Button>
                <Radio.Button value="TABLE">表级</Radio.Button>
                <Radio.Button value="COLUMN">字段级</Radio.Button>
              </Radio.Group>
            </div>
            <Typography.Text type="secondary">
              {permMode === 'OFF' && '关闭：所有数据对登录用户可见，仅留审计。'}
              {permMode === 'TABLE' && '表级：按表授权，表中所有列一致对待。'}
              {permMode === 'COLUMN' && '字段级：按列分级，L3/L4 列需申请授权。'}
            </Typography.Text>
            <div>
              <Typography.Text strong>生效方式</Typography.Text>
              <Radio.Group
                style={{ marginLeft: 12 }}
                value={permEnforce}
                onChange={(e) => setPermEnforce(e.target.value)}
              >
                <Radio.Button value="LOG">仅记录</Radio.Button>
                <Radio.Button value="ENFORCE">强制执行</Radio.Button>
              </Radio.Group>
            </div>
            <div>
              <Typography.Text strong>小结果集阈值</Typography.Text>
              <InputNumber
                style={{ marginLeft: 12, width: 120 }}
                min={1}
                max={100000}
                value={permThreshold}
                onChange={(v) => setPermThreshold(Number(v ?? 10))}
              />
              <Typography.Text type="secondary" style={{ marginLeft: 8 }}>
                （命中行数小于该值时按敏感定向访问记账）
              </Typography.Text>
            </div>
            <Button type="primary" loading={permSaving} onClick={() => savePermission()}>
              保存
            </Button>
          </Space>
        </Card>
      )}

      <Modal
        open={offOpen}
        title={<span style={{ color: '#cf1322' }}>关闭权限控制（高风险）</span>}
        okText="确认关闭"
        okButtonProps={{ danger: true, disabled: offInput.trim() !== 'CONFIRM' }}
        confirmLoading={permSaving}
        onOk={() => savePermission('OFF', offInput.trim())}
        onCancel={() => { setOffOpen(false); setOffInput('') }}
      >
        <p>关闭后所有登录用户都能看到明文数据，不再拦截越权查询，仅保留审计。</p>
        <p>请输入 <Typography.Text code>CONFIRM</Typography.Text> 确认：</p>
        <Input value={offInput} onChange={(e) => setOffInput(e.target.value)} placeholder="CONFIRM" />
      </Modal>

      {error && <Card><ErrorState message={error} onRetry={() => loadAll()} /></Card>}

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {cards.map((card) => (
          <Col xs={12} sm={8} xl={4} key={card.title}>
            <Card hoverable className="stat-card" onClick={card.onClick}>
              <Statistic title={card.title} value={card.value} />
            </Card>
          </Col>
        ))}
      </Row>

      <Card size="small">
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            {
              key: 'audit',
              atoms: ['security:read'],
              label: `审计日志${summary ? ` (${summary.auditToday} 今日)` : ''}`,
              children: (
                <>
                  <Space wrap style={{ marginBottom: 12 }}>
                    <Input.Search
                      allowClear
                      style={{ width: 200 }}
                      placeholder="用户"
                      value={userInput}
                      onChange={(e) => {
                        const v = e.target.value
                        // 输入框用独立 state，避免受控值被上次搜索词锁死
                        setUserInput(v)
                        if (!v) setUsername(undefined)
                      }}
                      onSearch={(v) => {
                        setUsername(v || undefined)
                        setSensitive(false)
                      }}
                    />
                    <Select
                      allowClear
                      style={{ width: 190 }}
                      placeholder="动作"
                      value={action}
                      onChange={(value) => {
                        setAction(value)
                        setSensitive(false)
                      }}
                      options={options.actions.map((v) => ({
                        value: v,
                        label: `${ACTION_LABEL[v] || v}${ACTION_LABEL[v] ? ` (${v})` : ''}`,
                      }))}
                    />
                    <Select
                      allowClear
                      style={{ width: 130 }}
                      placeholder="结果"
                      value={result}
                      onChange={(value) => {
                        setResult(value)
                        setSensitive(false)
                      }}
                      options={[{ value: 'SUCCESS', label: '成功' }, { value: 'FAILED', label: '失败' }]}
                    />
                    <ImeSearch
                      allowClear
                      style={{ width: 240 }}
                      placeholder="对象 / 详情 / IP"
                      value={kwInput}
                      onChange={(v) => {
                        setKwInput(v)
                        if (!v) setKeyword('')
                      }}
                      onSearch={(v) => {
                        setKeyword(v)
                        setSensitive(false)
                      }}
                    />
                    <Button onClick={() => {
                      setUsername(undefined)
                      setUserInput('')
                      setAction(undefined)
                      setResult(undefined)
                      setSensitive(false)
                      setKwInput('')
                      setKeyword('')
                    }}>
                      重置
                    </Button>
                  </Space>
                  <Table<AuditRow>
                    rowKey="id"
                    size="small"
                    loading={loading}
                    dataSource={audit}
                    pagination={{ pageSize: 20, showSizeChanger: false }}
                    scroll={{ x: 1040 }}
                    locale={{ emptyText: tableEmpty('没有匹配的审计记录', '换个筛选条件再试') }}
                    columns={[
                      {
                        title: '时间', dataIndex: 'created_at', width: 160,
                        render: (v) => dayjs(v).format('MM-DD HH:mm:ss'),
                      },
                      { title: '用户', dataIndex: 'username', width: 130, ellipsis: true },
                      {
                        title: '动作', dataIndex: 'action', width: 170,
                        render: (v: string) => (
                          <Tag color={ACTION_COLOR[v] || 'default'}>
                            {ACTION_LABEL[v] || v}
                          </Tag>
                        ),
                      },
                      { title: '对象', dataIndex: 'target', width: 160, ellipsis: true },
                      {
                        title: '结果', dataIndex: 'result', width: 80,
                        render: (v: string) => <Tag color={v === 'SUCCESS' ? 'green' : 'red'}>{v === 'SUCCESS' ? '成功' : '失败'}</Tag>,
                      },
                      { title: 'IP', dataIndex: 'ip', width: 130, ellipsis: true },
                      {
                        title: '详情', dataIndex: 'detail', ellipsis: true,
                        render: (v: string) => v ? (
                          <Tooltip title={v}><span>{v}</span></Tooltip>
                        ) : <Typography.Text type="secondary">-</Typography.Text>,
                      },
                    ]}
                  />
                </>
              ),
            },
            {
              key: 'sessions',
              atoms: ['security:read'],
              label: `在线会话 (${summary?.activeSessions ?? 0})`,
              children: (
                <>
                  <Space style={{ marginBottom: 12 }}>
                    <Typography.Text type="secondary">
                      仅显示会话元数据，不展示 Token 本身。吊销后该会话立即失效。
                    </Typography.Text>
                  </Space>
                  <Table<SessionRow>
                    rowKey="id"
                    size="small"
                    dataSource={sessions}
                    pagination={{ pageSize: 20, showSizeChanger: false }}
                    locale={{ emptyText: tableEmpty('没有在线会话') }}
                    columns={[
                      { title: '用户', dataIndex: 'username', width: 180 },
                      {
                        title: '登录时间', dataIndex: 'created_at', width: 180,
                        render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
                      },
                      {
                        title: '过期时间', dataIndex: 'expires_at', width: 180,
                        render: (v) => dayjs(v).format('YYYY-MM-DD HH:mm:ss'),
                      },
                      {
                        title: '状态', dataIndex: 'active', width: 100,
                        render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '有效' : '已过期'}</Tag>,
                      },
                      {
                        title: '操作', width: 120,
                        render: (_, row) => (
                          <Popconfirm title={`吊销用户「${row.username}」的会话？`} okText="吊销" okButtonProps={{ danger: true }} onConfirm={() => revoke(row)}>
                            <Button size="small" danger icon={<DeleteOutlined />}>吊销</Button>
                          </Popconfirm>
                        ),
                      },
                    ]}
                  />
                </>
              ),
            },
            {
              key: 'classification',
              atoms: ['data:classify', 'mask:manage', 'security:read'],
              label: '数据分级',
              children: (
                <>
                  <Card size="small" title="查询网关" style={{ marginBottom: 12 }}>
                    <Space wrap>
                      <Select
                        style={{ width: 160 }}
                        value={guardMode}
                        disabled={!can('system:manage') || !isPlatformTenant()}
                        onChange={saveGuardMode}
                        options={guardModes.map((m) => ({ value: m.code, label: m.name }))}
                      />
                      <Typography.Text type="secondary">
                        {guardModes.find((m) => m.code === guardMode)?.description}
                      </Typography.Text>
                      {!isPlatformTenant() && (
                        <Typography.Text type="secondary">
                          该开关由平台管理员统一管理
                        </Typography.Text>
                      )}
                    </Space>
                    <div style={{ marginTop: 8, fontSize: 12, color: '#8c8c8c' }}>
                      表级等级由列等级推导：L1/L2 直接可查，L3/L4 需审批通过的授权
                      （负责人与安全角色除外）。
                      字段级：L1/L2 一律明文；L3/L4 按脱敏规则返回，负责人、持有表/列授权
                      或有「查看未脱敏明文」权限的人能看到原值。
                    </div>
                  </Card>
                  <Card
                    size="small"
                    title="表与字段定级"
                    extra={can('data:classify') ? (
                      <Button type="primary" icon={<PlusOutlined />}
                        onClick={() => { registerForm.resetFields(); setRegisterOpen(true) }}>
                        登记表
                      </Button>
                    ) : undefined}
                  >
                    <Table<ClassRow>
                      rowKey="id"
                      size="small"
                      loading={classLoading}
                      dataSource={classRows}
                      pagination={{ pageSize: 10, showSizeChanger: false }}
                      locale={{ emptyText: tableEmpty('还没有登记的表') }}
                      columns={[
                        {
                          title: '表', width: 260,
                          render: (_, row) => `${row.schemaName}.${row.tableName}`,
                        },
                        { title: '负责人', dataIndex: 'ownerUser', width: 140 },
                        {
                          title: '级别（由列推导）', dataIndex: 'dataLevel', width: 170,
                          render: (level: string) => {
                            const meta = LEVEL_META[level]
                            return (
                              <Tooltip title="表级等级不再手工设置，取该表字段等级的最大值；没有字段时使用登记时的值。">
                                <Tag color={meta?.color}>{meta?.label || level || '未定级'}</Tag>
                              </Tooltip>
                            )
                          },
                        },
                        {
                          title: '字段', dataIndex: 'columnCount', width: 90,
                          render: (n: number) => `${n} 个`,
                        },
                        {
                          title: '操作', width: 130,
                          render: (_, row) => (can('mask:manage')
                            ? <Button size="small" onClick={() => openColumns(row)}>字段分级</Button>
                            : <Typography.Text type="secondary">无脱敏配置权限</Typography.Text>),
                        },
                      ]}
                    />
                  </Card>
                </>
              ),
            },
            {
              key: 'posture',
              atoms: ['security:read'],
              label: '安全状况',
              children: summary ? (
                <Row gutter={[12, 12]}>
                  <Col xs={24} md={12}>
                    <Card size="small" title={<Space><SafetyOutlined />数据服务</Space>}>
                      <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 2 }}>
                        <li>已发布服务：<b>{summary.publishedServices}</b> 个</li>
                        <li>今日调用：<b>{summary.serviceCallsToday}</b> 次，失败 <b>{summary.serviceErrorsToday}</b> 次</li>
                        <li>鉴权方式：<code>X-API-Token</code> / <code>Bearer</code>，服务下线立即 403</li>
                        <li>SQL 限制：只读 SELECT / WITH，单条语句，最多 200 行、15 秒超时</li>
                      </ul>
                    </Card>
                  </Col>
                  <Col xs={24} md={12}>
                    <Card size="small" title={<Space><SafetyOutlined />已知风险</Space>}>
                      <ul style={{ margin: 0, paddingLeft: 18, lineHeight: 2 }}>
                        <li>
                          数据源密码明文存储：<b>{summary.plaintextPasswords}</b> 条
                          <Typography.Text type="secondary">（生产应改 Secret/KMS）</Typography.Text>
                        </li>
                        <li>RBAC 已启用：角色属于租户、权限点可配置；助手与查询网关各有独立开关</li>
                        <li>本地 admin 登录仍开启，生产应仅保留 SSO</li>
                        <li>审计日志暂无自动归档/保留期策略</li>
                      </ul>
                    </Card>
                  </Col>
                </Row>
              ) : null,
            },
          ].filter((item) => canAny(...item.atoms))}
        />
      </Card>

      <Modal
        open={!!editingTable}
        title={editingTable
          ? `字段分级：${editingTable.schemaName}.${editingTable.tableName}` : ''}
        onCancel={() => setEditingTable(null)}
        onOk={saveColumns}
        width={760}
        destroyOnClose
      >
        <Typography.Text type="secondary">
          列等级决定是否会脱敏：L1/L2 一律明文；L3/L4 默认按脱敏规则输出，
          只有负责人、持有该表/列有效授权或有「查看未脱敏明文」权限的人能看到原值。
        </Typography.Text>
        <Table<ColumnRow>
          rowKey={(_, index) => String(index)}
          size="small"
          pagination={false}
          style={{ marginTop: 8 }}
          dataSource={columnDraft}
          columns={[
            {
              title: '字段名', width: 170,
              render: (_, row, index) => (
                <Input
                  size="small"
                  value={row.columnName}
                  placeholder="例如 phone"
                  onChange={(e) => setColumnDraft((prev) => prev.map((c, i) =>
                    i === index ? { ...c, columnName: e.target.value } : c))}
                />
              ),
            },
            {
              title: '等级', width: 120,
              render: (_, row, index) => (
                <Select
                  size="small"
                  style={{ width: '100%' }}
                  value={row.dataLevel || 'L2'}
                  options={COLUMN_LEVEL_OPTIONS}
                  onChange={(value) => changeColumnLevel(index, value, row.columnName)}
                />
              ),
            },
            {
              title: '敏感类型', width: 160,
              render: (_, row, index) => (
                <Select
                  size="small"
                  style={{ width: '100%' }}
                  value={row.sensitivity}
                  options={SENSITIVITY_OPTIONS}
                  onChange={(value) => setColumnDraft((prev) => prev.map((c, i) =>
                    i === index ? { ...c, sensitivity: value } : c))}
                />
              ),
            },
            {
              title: '脱敏规则', width: 190,
              render: (_, row, index) => (
                <Select
                  size="small"
                  style={{ width: '100%' }}
                  value={row.maskRule}
                  options={MASK_OPTIONS}
                  onChange={(value) => setColumnDraft((prev) => prev.map((c, i) =>
                    i === index ? { ...c, maskRule: value } : c))}
                />
              ),
            },
            {
              title: '', width: 60,
              render: (_, __, index) => (
                <Button size="small" danger icon={<DeleteOutlined />}
                  aria-label="删除这一列" title="删除这一列"
                  onClick={() => setColumnDraft((prev) => prev.filter((_, i) => i !== index))} />
              ),
            },
          ]}
        />
        <Button
          type="dashed"
          block
          icon={<PlusOutlined />}
          style={{ marginTop: 8 }}
          onClick={() => setColumnDraft((prev) => [...prev, {
            columnName: '', sensitivity: 'NORMAL', maskRule: 'NONE', dataLevel: 'L2',
          }])}
        >
          新增字段
        </Button>
      </Modal>

      <Modal
        open={registerOpen}
        title="登记表（纳管并定级）"
        onCancel={() => setRegisterOpen(false)}
        onOk={submitRegister}
        destroyOnClose
      >
        <Form form={registerForm} layout="vertical"
          initialValues={{ catalog: 'Doris' }}>
          <Form.Item name="catalog" label="类型" rules={[{ required: true }]}>
            <Select options={[{ value: 'Doris', label: 'Doris' }, { value: 'PG', label: 'PostgreSQL' }]} />
          </Form.Item>
          <Form.Item name="schemaName" label="库名" rules={[{ required: true, message: '库名必填' }]}>
            <Input placeholder="例如 platform_dev" />
          </Form.Item>
          <Form.Item name="tableName" label="表名" rules={[{ required: true, message: '表名必填' }]}>
            <Input placeholder="例如 st_assets_mysql" />
          </Form.Item>
          <Form.Item
            label="级别"
            extra="表级等级由字段等级推导，登记后到「字段分级」配置列等级即可。"
          >
            <Typography.Text type="secondary">登记后默认 L2（内部）</Typography.Text>
          </Form.Item>
          <Form.Item name="ownerUser" label="负责人（可选）"
            extra="留空则为当前登录用户；负责人可直接读写该表。">
            <Input placeholder="例如 admin" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
