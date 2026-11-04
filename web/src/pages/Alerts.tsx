import { useEffect, useRef, useState } from 'react'
import {
  Alert, Button, Card, Col, DatePicker, Descriptions, Drawer, Form, Input, InputNumber, Modal,
  Popconfirm, Row, Select, Space, Statistic, Switch, Table, Tabs, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  BellOutlined, CheckOutlined, DeleteOutlined, PlusOutlined, ReloadOutlined, StopOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { api, errMsg, del, post, put } from '../api'
import { can, canAny } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ImeSearch, useDebounced } from '../components/ImeInput'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import type { AlertEvent, AlertRule, AlertSummary, Project, QualityRule, Workflow } from '../types'

const TYPE_LABEL: Record<string, string> = {
  WORKFLOW_FAILED: '运行失败',
  QUALITY_FAILED: '质量未通过',
  SLA_TIMEOUT: 'SLA 超时',
  SYNC_TASK_FAILED: '采集任务失败',
  SYSTEM: '系统监控',
}

const TYPE_COLOR: Record<string, string> = {
  WORKFLOW_FAILED: 'red',
  QUALITY_FAILED: 'orange',
  SLA_TIMEOUT: 'gold',
  SYNC_TASK_FAILED: 'red',
  SYSTEM: 'volcano',
}

const STATUS_LABEL: Record<string, string> = {
  OPEN: '待处理',
  ACKED: '已确认',
  CLOSED: '已关闭',
}

const STATUS_COLOR: Record<string, string> = {
  OPEN: 'red',
  ACKED: 'orange',
  CLOSED: 'default',
}

const SEVERITY_LABEL: Record<string, string> = {
  CRITICAL: '严重',
  WARNING: '警告',
}

/** BUG-TC6-10：处置说明下限，与后端 MIN_CLOSE_REASON 同口径（≥5 字）。 */
const MIN_CLOSE_REASON = 5

/**
 * D50：静默窗口与升级策略字段。后端 V114 起随规则返回；
 * 本卡文件域不含 `web/src/types.ts`，所以在这里本地扩展，不动共享类型。
 */
type RulePolicy = {
  silenceUntil?: string | null
  escalateAfterMinutes?: number | null
  escalateTo?: string | null
  silenced?: boolean
}
type AlertRuleEx = AlertRule & RulePolicy
/** BUG-TC6-10：closeReason 由 V117 起随事件返回；本卡文件域不含 types.ts，本地扩展。 */
type AlertEventEx = AlertEvent & {
  escalatedAt?: string | null
  escalatedTo?: string | null
  closeReason?: string | null
  /** BUG-TC6-8：事件行直接带投递计数，列表里就能看出「这条没发出去」。 */
  deliverySent?: number
  deliveryFailed?: number
  deliveryExhausted?: number
}

/** BUG-TC6-8：GET /api/alert-deliveries 的行；types.ts 不在本卡文件域，本地定义。 */
type DeliveryRow = {
  id: number
  eventId: number
  eventTitle?: string | null
  channelId: number
  channelName?: string | null
  status: string
  httpStatus?: number | null
  eventSeq?: number | null
  response?: string | null
  attempts?: number | null
  nextRetryAt?: string | null
  createdAt?: string | null
}

/** BUG-TC6-8：汇总新增投递失败/耗尽两个计数；同样不动共享的 AlertSummary。 */
type AlertSummaryEx = AlertSummary & {
  deliveryFailed?: number
  deliveryExhausted?: number
}

/** 自动重试上限，与 NotifyService.deliverPending 的 attempts >= 5 同口径。 */
const MAX_DELIVERY_ATTEMPTS = 5

type RuleForm = {
  projectId: number
  name: string
  ruleType: string
  workflowId?: number
  qualityRuleId?: number
  thresholdMinutes?: number
  severity: string
  enabled: boolean
  silenceUntil?: dayjs.Dayjs
  escalateAfterMinutes?: number
  escalateTo?: string
}

export default function Alerts() {
  const [events, setEvents] = useState<AlertEventEx[]>([])
  const [rules, setRules] = useState<AlertRuleEx[]>([])
  const [summary, setSummary] = useState<AlertSummaryEx>()
  const [projects, setProjects] = useState<Project[]>([])
  const [workflows, setWorkflows] = useState<Workflow[]>([])
  const [qualityRules, setQualityRules] = useState<QualityRule[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [tab, setTab] = useState('events')
  const [status, setStatus] = useState<string>()
  const [type, setType] = useState<string>()
  const [severity, setSeverity] = useState<string>()
  const [kwInput, setKwInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [createdToday, setCreatedToday] = useState(false)
  const [closedToday, setClosedToday] = useState(false)
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  // BUG-TC6-10：单项/批量关闭共用同一个「填写处置说明」弹窗；null = 弹窗关闭
  const [closeTarget, setCloseTarget] = useState<AlertEventEx[] | null>(null)
  const [closeSkipped, setCloseSkipped] = useState(0)
  const [closeReason, setCloseReason] = useState('')
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [editing, setEditing] = useState<AlertRuleEx>()
  const [saving, setSaving] = useState(false)
  const [detail, setDetail] = useState<AlertEventEx>()
  const nav = useNavigate()
  const [chOpen, setChOpen] = useState(false)
  // BUG-TC6-8：投递记录页签（数据源 GET /api/alert-deliveries，失败原因 + 重投入口）
  const [deliveries, setDeliveries] = useState<DeliveryRow[]>([])
  const [delLoading, setDelLoading] = useState(false)
  const [delError, setDelError] = useState('')
  const [channels, setChannels] = useState<any[]>([])
  const [chName, setChName] = useState('')
  const [chType, setChType] = useState('WEBHOOK')
  const [chUrl, setChUrl] = useState('')
  const [chSecret, setChSecret] = useState('')
  const [chHost, setChHost] = useState('')
  const [chPort, setChPort] = useState('25')
  const [chUser, setChUser] = useState('')
  const [chPass, setChPass] = useState('')
  const [chFrom, setChFrom] = useState('')
  const [chTo, setChTo] = useState('')

  const loadChannels = async () => {
    try {
      const rows = await api<any[]>('/api/notify-channels')
      setChannels(rows || [])
    } catch (e: any) {
      message.error(e.message || '读取通知渠道失败')
    }
  }

  /** BUG-TC6-8：投递记录列表（后端固定返回最近 100 条）。 */
  const loadDeliveries = async () => {
    setDelLoading(true)
    setDelError('')
    try {
      const res = await api<{ count: number; deliveries: DeliveryRow[] }>('/api/alert-deliveries')
      setDeliveries(res?.deliveries || [])
    } catch (e: any) {
      setDelError(e.message || '读取投递记录失败')
    } finally {
      setDelLoading(false)
    }
  }

  /** BUG-TC6-8：失败投递重投（后端 attempts 归零 + next_retry_at=now，30 秒内重试）。 */
  const retryDelivery = async (row: DeliveryRow) => {
    try {
      const res = await post<{ ok: boolean; message?: string }>(
        `/api/alert-deliveries/${row.id}/retry`, {})
      message.success(res.message || '已排入重投队列')
      loadDeliveries()
      api<AlertSummaryEx>('/api/alerts/summary').then(setSummary).catch(() => undefined)
    } catch (e: any) {
      message.error(e.message || '重投失败')
    }
  }

  const createChannel = async () => {
    try {
      const body: any = { name: chName, type: chType, secret: chSecret || undefined }
      if (chType === 'EMAIL') {
        body.config = {
          host: chHost, port: Number(chPort) || 25, username: chUser || undefined,
          password: chPass || undefined, from: chFrom, to: chTo,
        }
      } else {
        body.url = chUrl
      }
      await post('/api/notify-channels', body)
      message.success('渠道已创建')
      setChName(''); setChUrl(''); setChSecret('')
      setChHost(''); setChPort('25'); setChUser(''); setChPass(''); setChFrom(''); setChTo('')
      loadChannels()
    } catch (e: any) {
      message.error(e.message || '创建渠道失败')
    }
  }
  const [form] = Form.useForm<RuleForm>()
  const ruleType = Form.useWatch('ruleType', form)
  const projectId = Form.useWatch('projectId', form)

  // 筛选切换与 30 秒轮询可能并发；只接受最后一次请求，避免旧筛选结果覆盖新筛选
  const eventsSeq = useRef(0)
  const loadEvents = async (silent = false) => {
    const seq = ++eventsSeq.current
    if (!silent) setLoading(true)
    setError('')
    try {
      const qs = new URLSearchParams()
      if (status) qs.set('status', status)
      if (type) qs.set('type', type)
      if (severity) qs.set('severity', severity)
      if (keyword) qs.set('keyword', keyword)
      if (createdToday) qs.set('createdToday', 'true')
      if (closedToday) qs.set('closedToday', 'true')
      qs.set('limit', '200')
      const rows = await api<AlertEvent[]>(`/api/alerts?${qs.toString()}`)
      if (seq !== eventsSeq.current) return
      setEvents(rows)
    } catch (e) {
      if (seq !== eventsSeq.current) return
      // 后台轮询失败不覆盖页面上的旧数据，也不弹错误打扰正在处理告警的人
      if (!silent) setError(e instanceof Error ? e.message : '加载告警失败')
    } finally {
      if (seq === eventsSeq.current && !silent) setLoading(false)
    }
  }

  const loadAll = async () => {
    try {
      const [s, r, p] = await Promise.all([
        api<AlertSummaryEx>('/api/alerts/summary'),
        api<AlertRule[]>('/api/alert-rules'),
        api<Project[]>('/api/projects'),
      ])
      setSummary(s)
      setRules(r)
      setProjects(p)
      await loadEvents()
      loadDeliveries()
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载告警中心失败')
    }
  }

  useEffect(() => {
    loadAll()
  }, [])

  // v452：keyword 触发接口请求，300ms 防抖
  const debouncedKeyword = useDebounced(keyword, 300)

  useEffect(() => {
    // 筛选条件变了，之前勾选的告警可能已经不在列表里
    setSelectedIds([])
    loadEvents()
  }, [status, type, severity, debouncedKeyword, createdToday, closedToday])

  // 值班页面常驻：30 秒静默刷新列表与统计，新告警不用手点刷新
  // BUG-TC6-8：投递记录页签打开时也跟随静默刷新，重投结果不用手点
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.hidden) return
      loadEvents(true)
      api<AlertSummaryEx>('/api/alerts/summary').then(setSummary).catch(() => undefined)
      if (tab === 'deliveries') loadDeliveries()
    }, 30_000)
    return () => window.clearInterval(timer)
  }, [status, type, severity, debouncedKeyword, createdToday, closedToday, tab])

  const loadProjectAssets = async (pid?: number) => {
    if (!pid) {
      setWorkflows([])
      setQualityRules([])
      return
    }
    const [wf, qr] = await Promise.all([
      api<Workflow[]>(`/api/projects/${pid}/workflows`).catch(() => []),
      api<QualityRule[]>(`/api/projects/${pid}/quality/rules`).catch(() => []),
    ])
    setWorkflows(wf)
    setQualityRules(qr)
  }

  useEffect(() => {
    if (drawerOpen) loadProjectAssets(projectId)
  }, [drawerOpen, projectId])

  const openCreate = () => {
    setEditing(undefined)
    form.resetFields()
    form.setFieldsValue({
      ruleType: 'WORKFLOW_FAILED',
      severity: 'CRITICAL',
      enabled: true,
      projectId: projects[0]?.id,
      // D50：默认 30 分钟没人确认就升级；清空「静默到」表示不静默
      escalateAfterMinutes: 30,
    })
    setDrawerOpen(true)
  }

  const openEdit = (row: AlertRuleEx) => {
    setEditing(row)
    // 先清掉上一条规则的校验错误与未提交字段，避免串数据
    form.resetFields()
    form.setFieldsValue({
      projectId: row.projectId,
      name: row.name,
      ruleType: row.ruleType,
      workflowId: row.workflowId,
      qualityRuleId: row.qualityRuleId,
      thresholdMinutes: row.thresholdMinutes,
      severity: row.severity,
      enabled: row.enabled,
      // D50：静默时间回显成 dayjs 给 DatePicker；升级留空按默认 30 分钟显示
      silenceUntil: row.silenceUntil ? dayjs(row.silenceUntil) : undefined,
      escalateAfterMinutes: row.escalateAfterMinutes ?? 30,
      escalateTo: row.escalateTo ?? undefined,
    })
    setDrawerOpen(true)
  }

  const saveRule = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload = {
        ...values,
        silenceUntil: values.silenceUntil ? values.silenceUntil.toISOString() : undefined,
        thresholdMinutes: values.ruleType === 'SLA_TIMEOUT' ? values.thresholdMinutes : undefined,
        workflowId: values.ruleType === 'QUALITY_FAILED' ? undefined : values.workflowId,
        qualityRuleId: values.ruleType === 'QUALITY_FAILED' ? values.qualityRuleId : undefined,
      }
      if (editing) {
        await put(`/api/alert-rules/${editing.id}`, payload)
        message.success('告警规则已更新')
      } else {
        await post('/api/alert-rules', payload)
        message.success('告警规则已创建')
      }
      setDrawerOpen(false)
      setRules(await api<AlertRule[]>('/api/alert-rules'))
      await loadAll()
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message || '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const removeRule = async (row: AlertRule) => {
    try {
      // del 调用点（D54）：列表行 Popconfirm，文案写明代价是「以后不再产生新告警」；
      // 规则可重建、历史告警保留，属中危，不需手输
      await del(`/api/alert-rules/${row.id}`)
      message.success('规则已删除，历史告警保留')
      setRules(await api<AlertRule[]>('/api/alert-rules'))
      await loadAll()
    } catch (e: any) {
      message.error(e.message || '删除失败')
    }
  }

  /**
   * BUG-TC6-10：关闭请求体。后端从 V117 起要求 ≥5 字处置说明，
   * 与变更单/重命名的理由口径一致；前端先拦一次，避免一次点击换来一个 400。
   */
  const closePayload = (closeReason: string) => ({ reason: closeReason.trim() })

  /** 打开关闭弹窗（单条传 [row]，批量传目标行）。关闭是状态机动作，一律先确认再提交。 */
  const openCloseModal = (rows: AlertEventEx[], skipped = 0) => {
    setCloseTarget(rows)
    setCloseSkipped(skipped)
    setCloseReason('')
  }

  const submitClose = async () => {
    if (!closeTarget || closeTarget.length === 0) return
    const reason = closeReason.trim()
    if (reason.length < MIN_CLOSE_REASON) {
      message.warning(`请填写处置说明（至少 ${MIN_CLOSE_REASON} 个字）`)
      return
    }
    let done = 0
    const failures: string[] = []
    for (const row of closeTarget) {
      try {
        await post(`/api/alerts/${row.id}/close`, closePayload(reason))
        done += 1
      } catch (e: any) {
        // 单条失败不打断整批，最后按成功/失败分别提示
        failures.push(`${row.ruleName || `#${row.id}`}：${e?.message || '关闭失败'}`)
      }
    }
    if (failures.length === 0) {
      message.success(`已关闭 ${done} 条`)
    } else if (done === 0) {
      message.error(`关闭失败 ${failures.length} 条：${failures.slice(0, 2).join('；')}${failures.length > 2 ? ' 等' : ''}`)
    } else {
      message.warning(`已关闭 ${done} 条，${failures.length} 条失败：${failures.slice(0, 2).join('；')}${failures.length > 2 ? ' 等' : ''}`)
    }
    setCloseTarget(null)
    setSelectedIds([])
    // 统计卡片、页签计数与列表一起刷新，别让顶部数字停在旧值
    await loadAll()
  }

  /** 告警常成批出现；勾选后一次处理，避免 38 条逐条点。单条失败不影响其余。 */
  const bulkAct = async (action: 'ack' | 'close') => {
    const selected = events.filter((row) => selectedIds.includes(row.id))
    // 状态机（BUG-TC6-10 起）：确认只允许 OPEN，关闭只允许 ACKED（先确认才能关闭）
    const targets = selected.filter((row) => (action === 'ack'
      ? row.status === 'OPEN'
      : row.status === 'ACKED'))
    const skipped = selected.length - targets.length
    const verb = action === 'ack' ? '确认' : '关闭'
    if (targets.length === 0) {
      message.info(action === 'ack'
        ? '选中的告警都不是「待处理」状态，无法确认'
        : '选中的告警都不是「已确认」状态；需要先确认才能关闭')
      return
    }
    if (action === 'close') {
      // 关闭比确认危险（虽然可重开，但仍要留理由）：与单条走同一个处置说明弹窗
      openCloseModal(targets, skipped)
      return
    }
    Modal.confirm({
      title: `批量${verb} ${targets.length} 条告警？`,
      content: skipped > 0
        ? `另有 ${skipped} 条状态不符（已确认或已关闭），会自动跳过。`
        : undefined,
      okText: `批量${verb}`,
      onOk: async () => {
        let done = 0
        const failures: string[] = []
        for (const row of targets) {
          try {
            await post(`/api/alerts/${row.id}/ack`, {})
            done += 1
          } catch (e: any) {
            failures.push(`${row.ruleName || `#${row.id}`}：${e?.message || '处理失败'}`)
          }
        }
        if (failures.length === 0) {
          message.success(`已${verb} ${done} 条`)
        } else if (done === 0) {
          message.error(`${verb}失败 ${failures.length} 条：${failures.slice(0, 2).join('；')}${failures.length > 2 ? ' 等' : ''}`)
        } else {
          message.warning(`已${verb} ${done} 条，${failures.length} 条失败：${failures.slice(0, 2).join('；')}${failures.length > 2 ? ' 等' : ''}`)
        }
        setSelectedIds([])
        // 统计卡片、页签计数与列表一起刷新，别让顶部数字停在旧值
        await loadAll()
      },
    })
  }

  const ackEvent = async (row: AlertEvent) => {
    try {
      await post(`/api/alerts/${row.id}/ack`, {})
      message.success('已确认')
      await loadAll()
    } catch (e: any) {
      message.error(e.message || '操作失败')
    }
  }

  /** BUG-TC6-10 ②：关闭不是终态，重开回到「已确认」，可以继续处置。 */
  const reopenEvent = async (row: AlertEvent) => {
    try {
      await post(`/api/alerts/${row.id}/reopen`, {})
      message.success('已重开，状态回到「已确认」')
      await loadAll()
    } catch (e: any) {
      message.error(e.message || '重开失败')
    }
  }

  const removeEvent = async (row: AlertEvent) => {
    try {
      // del 调用点（D54）：只对已关闭的告警记录开放，Popconfirm 确认；
      // 删的是通知流水（不影响被监控对象），低风险
      await del(`/api/alerts/${row.id}`)
      message.success('已删除')
      await loadAll()
    } catch (e: any) {
      message.error(e.message || '删除失败')
    }
  }

  /**
   * BUG-TC6-13 ④：「立即检查」支持按规则圈范围——想只测当前这条规则，不该被拖成全平台扫描。
   * 传 ruleId 走单规则评估；不传保持全局（顶部按钮口径）。
   */
  const evaluateNow = async (ruleId?: number) => {
    try {
      const result = ruleId !== undefined
        ? await post<{ total: number }>('/api/alerts/evaluate', { ruleId })
        : await post<{ total: number }>('/api/alerts/evaluate', {})
      message.success(result.total > 0 ? `新产生 ${result.total} 条告警` : '检查完成，没有新告警')
      await loadAll()
    } catch (e: any) {
      message.error(e.message || '检查失败')
    }
  }

  /** 统计卡点击：一次性重置所有筛选，保证列表与卡片数字同一口径 */
  const applyCard = (next: {
    status?: string; severity?: string; createdToday?: boolean; closedToday?: boolean
  }) => {
    setTab('events')
    setStatus(next.status)
    setSeverity(next.severity)
    setType(undefined)
    setKeyword('')
    setKwInput('')
    setCreatedToday(Boolean(next.createdToday))
    setClosedToday(Boolean(next.closedToday))
  }

  const cards = summary ? [
    { title: '待处理', value: summary.open, onClick: () => applyCard({ status: 'OPEN' }) },
    { title: '严重待处理', value: summary.criticalOpen, onClick: () => applyCard({ status: 'OPEN', severity: 'CRITICAL' }) },
    { title: '今日新增', value: summary.todayNew, onClick: () => applyCard({ createdToday: true }) },
    { title: '今日已关闭', value: summary.todayClosed, onClick: () => applyCard({ status: 'CLOSED', closedToday: true }) },
  ] : []

  return (
    <div>
      <PageHeader
        title="告警中心"
        description="运行失败、质量未通过、运行超时自动生成告警；确认是关闭的前置，告警需先确认才能填写处置说明并关闭；关闭后可重开。列表每 30 秒自动刷新。"
        extra={(
          <Space>
            {can('alert:manage') && (
              <Button onClick={() => { setChOpen(true); loadChannels() }}>通知渠道</Button>
            )}
            {can('ops:run') && <Button icon={<BellOutlined />} onClick={() => evaluateNow()}>立即检查</Button>}
            <Button icon={<ReloadOutlined />} onClick={() => loadAll()}>刷新</Button>
          </Space>
        )}
      />

      {error && <Card><ErrorState message={error} onRetry={() => loadAll()} /></Card>}

      {/* BUG-TC6-8 ③：重试耗尽不能再只写库——告警中心顶部常驻提示，点一下就进投递记录 */}
      {summary && (summary.deliveryExhausted ?? 0) > 0 && (
        <Alert
          type="error"
          showIcon
          banner
          style={{ marginBottom: 12 }}
          message={`有 ${summary.deliveryExhausted} 条告警投递重试已耗尽（最多 5 次），已放弃自动发送，值班员可能根本没收到`}
          description="先在「通知渠道」修好渠道（地址/密钥/网络），再到「投递记录」对失败条目点「重投」补发。"
          action={<Button size="small" danger onClick={() => setTab('deliveries')}>查看投递记录</Button>}
        />
      )}
      {summary && (summary.deliveryExhausted ?? 0) === 0 && (summary.deliveryFailed ?? 0) > 0 && (
        <Alert
          type="warning"
          showIcon
          banner
          style={{ marginBottom: 12 }}
          message={`有 ${summary.deliveryFailed} 条告警投递失败，退避重试中（1/2/4/8/16 分钟，最多 5 次）`}
          description="耗尽后不会再自动重试，可在「投递记录」查看失败原因。"
          action={<Button size="small" onClick={() => setTab('deliveries')}>投递记录</Button>}
        />
      )}

      <Row gutter={[12, 12]} style={{ marginBottom: 12 }}>
        {cards.map((card) => (
          <Col xs={12} sm={12} xl={6} key={card.title}>
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
              key: 'events',
              label: `告警事件${summary ? ` (${summary.open} 待处理)` : ''}`,
              children: (
                <>
                  <Space wrap style={{ marginBottom: 12 }}>
                    <Select
                      allowClear
                      style={{ width: 130 }}
                      placeholder="状态"
                      value={status}
                      onChange={(value) => {
                        setStatus(value)
                        setCreatedToday(false)
                        setClosedToday(false)
                      }}
                      options={Object.entries(STATUS_LABEL).map(([value, label]) => ({ value, label }))}
                    />
                    <Select
                      allowClear
                      style={{ width: 150 }}
                      placeholder="类型"
                      value={type}
                      onChange={(value) => {
                        setType(value)
                        setCreatedToday(false)
                        setClosedToday(false)
                      }}
                      options={Object.entries(TYPE_LABEL).map(([value, label]) => ({ value, label }))}
                    />
                    <Select
                      allowClear
                      style={{ width: 120 }}
                      placeholder="级别"
                      value={severity}
                      onChange={(value) => {
                        setSeverity(value)
                        setCreatedToday(false)
                        setClosedToday(false)
                      }}
                      options={Object.entries(SEVERITY_LABEL).map(([value, label]) => ({ value, label }))}
                    />
                    <ImeSearch
                      allowClear
                      style={{ width: 240 }}
                      placeholder="标题 / 详情 / 规则名"
                      value={kwInput}
                      onChange={(v) => {
                        setKwInput(v)
                        if (!v) setKeyword('')
                      }}
                      onSearch={(v) => setKeyword(v)}
                    />
                    <Button onClick={() => {
                      setStatus(undefined)
                      setType(undefined)
                      setSeverity(undefined)
                      setKwInput('')
                      setKeyword('')
                      setCreatedToday(false)
                      setClosedToday(false)
                    }}>
                      重置
                    </Button>
                  </Space>
                  {can('alert:ack') && selectedIds.length > 0 ? (
                    <Alert
                      type="info"
                      showIcon
                      style={{ marginBottom: 12 }}
                      message={`已选 ${selectedIds.length} 条告警`}
                      action={(
                        <Space>
                          <Button size="small" icon={<CheckOutlined />} onClick={() => bulkAct('ack')}>
                            批量确认
                          </Button>
                          <Button size="small" icon={<StopOutlined />} onClick={() => bulkAct('close')}>
                            批量关闭
                          </Button>
                          <Button size="small" type="text" onClick={() => setSelectedIds([])}>
                            取消选择
                          </Button>
                        </Space>
                      )}
                    />
                  ) : null}
                  <Table<AlertEvent>
                    rowKey="id"
                    size="small"
                    loading={loading}
                    dataSource={events}
                    rowSelection={can('alert:ack') ? {
                      selectedRowKeys: selectedIds,
                      onChange: (keys) => setSelectedIds(keys.map(Number)),
                      getCheckboxProps: (row) => ({ disabled: row.status === 'CLOSED' }),
                    } : undefined}
                    onRow={(row) => ({
                      style: { cursor: 'pointer' },
                      onClick: (event) => {
                        // REQ-DW-10：点击记录=打开详情；行内控件（勾选框/按钮/下拉）保持自身行为
                        const target = event.target as HTMLElement
                        if (target.closest('a, button, input, textarea, .ant-select')) return
                        setDetail(row)
                      },
                    })}
                    pagination={{ pageSize: 20, showSizeChanger: false }}
                    scroll={{ x: 1220 }}
                    locale={{ emptyText: tableEmpty('没有告警', '规则触发后会出现在这里') }}
                    columns={[
                      {
                        title: '级别', dataIndex: 'severity', width: 80,
                        render: (v: string) => (
                          <Tag color={v === 'CRITICAL' ? 'red' : 'gold'}>{SEVERITY_LABEL[v] || v}</Tag>
                        ),
                      },
                      {
                        title: '标题', dataIndex: 'title', width: 260, ellipsis: true,
                        render: (v: string, row) => (
                          <Tooltip title={row.detail || v}>
                            <span>{v}</span>
                          </Tooltip>
                        ),
                      },
                      {
                        title: '类型', dataIndex: 'ruleType', width: 110,
                        render: (v: string) => <Tag color={TYPE_COLOR[v] || 'default'}>{TYPE_LABEL[v] || v}</Tag>,
                      },
                      {
                        title: '项目 / 文件', width: 180, ellipsis: true,
                        render: (_, row) => (
                          <span>
                            {row.projectName || '-'}
                            {row.workflowName ? ` / ${row.workflowName}` : ''}
                          </span>
                        ),
                      },
                      {
                        title: '状态', dataIndex: 'status', width: 90,
                        render: (v: string, row: AlertEventEx) => (
                          <Space size={4}>
                            <Tag color={STATUS_COLOR[v] || 'default'}>{STATUS_LABEL[v] || v}</Tag>
                            {row.escalatedAt
                              ? (
                                <Tooltip title={`已升级给 ${row.escalatedTo || '未指定'}（${row.escalatedAt}）`}>
                                  <Tag color="volcano">已升级</Tag>
                                </Tooltip>
                              )
                              : null}
                          </Space>
                        ),
                      },
                      {
                        // BUG-TC6-8 C5：值班员在列表里就该看出「这条没发出去」
                        title: '投递', width: 100,
                        render: (_, row: AlertEventEx) => {
                          const exhausted = row.deliveryExhausted ?? 0
                          const failed = row.deliveryFailed ?? 0
                          if (exhausted > 0) {
                            return (
                              <Tooltip title={`${exhausted} 个渠道重试已耗尽（最多 5 次），已放弃自动发送，请到「投递记录」重投`}>
                                <Tag color="red">未送达</Tag>
                              </Tooltip>
                            )
                          }
                          if (failed > 0) {
                            return (
                              <Tooltip title={`${failed} 个渠道投递失败，退避重试中`}>
                                <Tag color="orange">投递失败</Tag>
                              </Tooltip>
                            )
                          }
                          if ((row.deliverySent ?? 0) > 0) return <Tag color="green">已送达</Tag>
                          return <span style={{ color: '#999' }}>-</span>
                        },
                      },
                      {
                        title: '创建时间', dataIndex: 'createdAt', width: 150,
                        render: (v) => dayjs(v).format('MM-DD HH:mm:ss'),
                      },
                      {
                        title: '操作', width: 210, fixed: 'right',
                        render: (_, row) => (
                          <Space size={4}>
                            {/* REQ-DW-10：删独立「详情」按钮，点行即开详情（onRow） */}
                            {/* ack/close/delete 后端都是 @RequireAtom("alert:ack")
                                （AlertController.java:194/215/236），前端用同一个原子，
                                避免角色只发 alert:ack 时「看得见、点了 403」。 */}
                            {can('alert:ack') && row.status === 'OPEN' && (
                              <Button size="small" type="link" icon={<CheckOutlined />}
                                onClick={() => ackEvent(row)}>确认</Button>
                            )}
                            {/* 发布线合并口径：权限原子取 main 侧 BUG-TC6-11（与后端 @RequireAtom 一致），
                                状态条件取 wm2 侧 BUG-TC6-10（关闭须先确认，CLOSED 可重开） */}
                            {can('alert:ack') && row.status === 'ACKED' && (
                              <Button size="small" type="link" icon={<StopOutlined />}
                                onClick={() => openCloseModal([row])}>关闭</Button>
                            )}
                            {can('alert:ack') && row.status === 'CLOSED' && (
                              <Popconfirm title="重开这条告警？重开后回到「已确认」，可继续处置"
                                onConfirm={() => reopenEvent(row)}>
                                <Button size="small" type="link" icon={<ReloadOutlined />}>重开</Button>
                              </Popconfirm>
                            )}
                            {can('alert:ack') && row.status === 'CLOSED' && (
                              <Popconfirm title="删除这条告警记录？删除后界面不可恢复（审计仍保留）"
                                okText="删除"
                                okButtonProps={{ danger: true }}
                                onConfirm={() => removeEvent(row)}>
                                <Button size="small" type="link" danger icon={<DeleteOutlined />}>删除</Button>
                              </Popconfirm>
                            )}
                          </Space>
                        ),
                      },
                    ]}
                  />
                </>
              ),
            },
            {
              key: 'rules',
              label: `告警规则 (${rules.length})`,
              children: (
                <>
                  <Space style={{ marginBottom: 12 }}>
                    {can('alert:manage') && (
                      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建规则</Button>
                    )}
                    <Typography.Text type="secondary">
                      规则每 30 秒评估一次；同一规则对同一实例/质量结果只告警一次。
                    </Typography.Text>
                  </Space>
                  <Table<AlertRule>
                    rowKey="id"
                    size="small"
                    dataSource={rules}
                    pagination={{ pageSize: 20, showSizeChanger: false }}
                    scroll={{ x: 1050 }}
                    locale={{ emptyText: tableEmpty('还没有告警规则', '点击「新建规则」开始') }}
                    columns={[
                      { title: '名称', dataIndex: 'name', width: 220, ellipsis: true },
                      {
                        title: '类型', dataIndex: 'ruleType', width: 110,
                        render: (v: string) => <Tag color={TYPE_COLOR[v] || 'default'}>{TYPE_LABEL[v] || v}</Tag>,
                      },
                      { title: '项目', dataIndex: 'projectName', width: 140, ellipsis: true },
                      {
                        title: '范围', width: 200, ellipsis: true,
                        render: (_, row) => (
                          row.ruleType === 'QUALITY_FAILED'
                            ? (row.qualityRuleName || '全部质量规则')
                            : (row.workflowName || '全部文件')
                        ),
                      },
                      {
                        title: '阈值', dataIndex: 'thresholdMinutes', width: 100,
                        render: (v?: number) => v == null ? '-' : `${v} 分钟`,
                      },
                      {
                        title: '级别', dataIndex: 'severity', width: 80,
                        render: (v: string) => (
                          <Tag color={v === 'CRITICAL' ? 'red' : 'gold'}>{SEVERITY_LABEL[v] || v}</Tag>
                        ),
                      },
                      {
                        // D50：静默中与升级策略要在列表里直接看得见，不然只有编辑弹窗里才知道
                        title: '静默 / 升级', width: 190,
                        render: (_, row: AlertRuleEx) => (
                          <Space size={4} wrap>
                            {row.silenced
                              ? (
                                <Tooltip title={`静默到 ${row.silenceUntil || ''}，期间命中不生成事件（有审计）`}>
                                  <Tag color="purple" icon={<StopOutlined />}>静默中</Tag>
                                </Tooltip>
                              )
                              : null}
                            {row.escalateAfterMinutes === 0
                              ? <Tag>不升级</Tag>
                              : (
                                <Tag color="blue">
                                  {row.escalateAfterMinutes ?? 30} 分钟未确认升级
                                  {row.escalateTo ? `→${row.escalateTo}` : ''}
                                </Tag>
                              )}
                          </Space>
                        ),
                      },
                      {
                        title: '启用', dataIndex: 'enabled', width: 80,
                        render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag>,
                      },
                      {
                        title: '操作', width: 180, fixed: 'right',
                        render: (_, row) => (
                          <Space size={4}>
                            {/* BUG-TC6-13 ④：按规则「检查」——只评估这条，不再顺带扫全平台 */}
                            {can('ops:run') && (
                              <Button size="small" type="link" onClick={() => evaluateNow(row.id)}>检查</Button>
                            )}
                            {can('alert:manage') ? (
                              <>
                                <Button size="small" type="link" onClick={() => openEdit(row)}>编辑</Button>
                                {/* D54 文案（main 侧 BUG-TC6-11 合入，发布线合并保留）：
                                    确认框必须写后果——删规则真正的代价是「以后不会再为这类问题产生任何告警」 */}
                                <Popconfirm
                                  title={`删除告警规则「${row.name}」？`}
                                  description={(
                                    <>
                                      已产生的历史告警保留，但<b>这条规则以后不再产生新告警</b>，
                                      对应问题（如工作流失败、质量不达标）将不再通知到任何渠道。
                                      只是暂时不想收通知的话，请改用「停用」。
                                    </>
                                  )}
                                  okText="删除"
                                  okButtonProps={{ danger: true }}
                                  onConfirm={() => removeRule(row)}
                                >
                                  <Button size="small" type="link" danger>删除</Button>
                                </Popconfirm>
                              </>
                            ) : !can('ops:run') ? <span style={{ color: '#999' }}>只读</span> : null}
                          </Space>
                        ),
                      },
                    ]}
                  />
                </>
              ),
            },
            {
              // BUG-TC6-8 ①：投递记录独立页签——时间/渠道/事件/状态/HTTP/次数/下次重试/失败原因/重投
              key: 'deliveries',
              label: `投递记录${summary && (summary.deliveryFailed ?? 0) > 0 ? ` (${summary.deliveryFailed} 失败)` : ''}`,
              children: (
                <>
                  <Space style={{ marginBottom: 12 }} wrap>
                    <Button icon={<ReloadOutlined />} onClick={loadDeliveries}>刷新</Button>
                    <Typography.Text type="secondary">
                      最近 100 条投递；失败自动重试退避 1/2/4/8/16 分钟，最多 {MAX_DELIVERY_ATTEMPTS} 次，耗尽后需手动「重投」。
                    </Typography.Text>
                  </Space>
                  {delError
                    ? <Card style={{ marginBottom: 12 }}><ErrorState message={delError} onRetry={loadDeliveries} /></Card>
                    : null}
                  <Table<DeliveryRow>
                    rowKey="id"
                    size="small"
                    loading={delLoading}
                    dataSource={deliveries}
                    pagination={{ pageSize: 20, showSizeChanger: false }}
                    scroll={{ x: 1080 }}
                    locale={{ emptyText: tableEmpty('暂无投递记录', '产生告警且配置了通知渠道后，这里会记录每次投递结果') }}
                    columns={[
                      {
                        title: '时间', dataIndex: 'createdAt', width: 140,
                        render: (v) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
                      },
                      { title: '渠道', dataIndex: 'channelName', width: 140, ellipsis: true },
                      {
                        title: '事件', dataIndex: 'eventTitle', width: 240, ellipsis: true,
                        render: (v: string | null | undefined, row) => (
                          <Tooltip title={`事件 #${row.eventId}${row.eventSeq != null ? `（第 ${row.eventSeq} 次触发）` : ''}`}>
                            <span>{v || `事件 #${row.eventId}`}</span>
                          </Tooltip>
                        ),
                      },
                      {
                        title: '状态', dataIndex: 'status', width: 90,
                        render: (v: string) => (v === 'SENT'
                          ? <Tag color="green">已发送</Tag>
                          : v === 'FAILED' ? <Tag color="red">失败</Tag> : <Tag>{v}</Tag>),
                      },
                      {
                        title: 'HTTP', dataIndex: 'httpStatus', width: 80,
                        render: (v?: number | null) => (v == null ? '-' : String(v)),
                      },
                      {
                        title: '次数', dataIndex: 'attempts', width: 90,
                        render: (v: number | null | undefined, row) => (
                          (v ?? 0) >= MAX_DELIVERY_ATTEMPTS && row.status === 'FAILED'
                            ? (
                              <Tooltip title="重试已耗尽：不会再自动发送，修复渠道后点「重投」">
                                <Tag color="red">{v} 次·耗尽</Tag>
                              </Tooltip>
                            )
                            : `${v ?? 0}/${MAX_DELIVERY_ATTEMPTS}`
                        ),
                      },
                      {
                        title: '下次重试', dataIndex: 'nextRetryAt', width: 130,
                        render: (v?: string | null) => (v ? dayjs(v).format('MM-DD HH:mm:ss') : '-'),
                      },
                      {
                        title: '失败原因', dataIndex: 'response', width: 240, ellipsis: true,
                        render: (v?: string | null) => (v
                          ? <Tooltip title={v}><span style={{ color: '#d4380d' }}>{v}</span></Tooltip>
                          : (
                            <Tooltip title="发送成功无原因；或当前角色（无 notify:manage / alert:manage）不可见失败原因原文">
                              <span style={{ color: '#999' }}>-</span>
                            </Tooltip>
                          )),
                      },
                      {
                        title: '操作', width: 90, fixed: 'right',
                        render: (_, row) => (
                          row.status === 'FAILED' && canAny('alert:manage', 'notify:manage') ? (
                            <Popconfirm
                              title="重投这条失败的投递？"
                              description="将重置重试计数并立即排入投递队列（约 30 秒内发出）"
                              onConfirm={() => retryDelivery(row)}
                            >
                              <Button size="small" type="link" icon={<ReloadOutlined />}>重投</Button>
                            </Popconfirm>
                          ) : <span style={{ color: '#999' }}>-</span>
                        ),
                      },
                    ]}
                  />
                </>
              ),
            },
          ]}
        />
      </Card>

      <Drawer
        open={drawerOpen}
        width={520}
        title={editing ? `编辑告警规则：${editing.name}` : '新建告警规则'}
        onClose={() => setDrawerOpen(false)}
        extra={(
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={saveRule}>保存</Button>
          </Space>
        )}
      >
        <Form form={form} layout="vertical" requiredMark>
          <Form.Item name="projectId" label="项目" rules={[{ required: true, message: '请选择项目' }]}>
            <Select
              placeholder="选择项目"
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
              onChange={() => {
                form.setFieldsValue({ workflowId: undefined, qualityRuleId: undefined })
              }}
            />
          </Form.Item>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：核心任务失败告警" maxLength={128} />
          </Form.Item>
          <Form.Item name="ruleType" label="触发条件" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'WORKFLOW_FAILED', label: '运行失败' },
                { value: 'QUALITY_FAILED', label: '质量规则未通过' },
                { value: 'SLA_TIMEOUT', label: '运行时长超出 SLA' },
                { value: 'SYNC_TASK_FAILED', label: '采集任务失败/超时' },
              ]}
            />
          </Form.Item>
          {ruleType === 'QUALITY_FAILED' ? (
            <Form.Item name="qualityRuleId" label="质量规则（留空表示全部）">
              <Select
                allowClear
                placeholder="全部质量规则"
                options={qualityRules.map((q) => ({ value: q.id, label: `${q.name} (${q.code})` }))}
              />
            </Form.Item>
          ) : (
            <Form.Item name="workflowId" label="文件（留空表示全部）">
              <Select
                allowClear
                placeholder="全部文件"
                options={workflows.map((w) => ({ value: w.id, label: `${w.name} (${w.code})` }))}
              />
            </Form.Item>
          )}
          {ruleType === 'SLA_TIMEOUT' && (
            <Form.Item
              name="thresholdMinutes"
              label="超时阈值（分钟）"
              rules={[{ required: true, message: '请填写超时阈值' }]}
              extra="实例运行时长超过该值即告警；0 表示任何已完成实例都算超时（测试用）。"
            >
              <InputNumber min={0} max={10080} style={{ width: '100%' }} placeholder="例如：60" />
            </Form.Item>
          )}
          <Form.Item name="severity" label="告警级别" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'CRITICAL', label: '严重' },
                { value: 'WARNING', label: '警告' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="silenceUntil"
            label="静默到"
            tooltip="维护窗口：这个时间点之前规则命中不生成告警，但会写一条「被静默」审计，事后能查为什么没告警"
          >
            <DatePicker showTime style={{ width: '100%' }} placeholder="留空＝不静默" />
          </Form.Item>
          <Form.Item
            name="escalateAfterMinutes"
            label="升级等待（分钟）"
            tooltip="事件产生后多久仍没人确认就升级；0＝该规则不升级"
          >
            <InputNumber min={0} max={10080} style={{ width: '100%' }} placeholder="默认 30" />
          </Form.Item>
          <Form.Item
            name="escalateTo"
            label="升级接收人"
            tooltip="用户名或角色码，会写进升级事件与通知内容；投递仍走现有通知渠道"
          >
            <Input placeholder="如 ops_lead（留空＝只生成升级事件）" maxLength={64} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Drawer>

      <Drawer
        title="通知渠道（Webhook）"
        placement="right"
        width={760}
        open={chOpen}
        onClose={() => setChOpen(false)}
      >
        <Space style={{ width: '100%', marginBottom: 12 }} size="middle" wrap>
          <Input placeholder="渠道名称" value={chName} style={{ width: 160 }}
            onChange={(e) => setChName(e.target.value)} />
          <Select value={chType} style={{ width: 140 }} onChange={setChType}
            options={[
              { value: 'WEBHOOK', label: 'Webhook' },
              { value: 'DINGTALK', label: '钉钉机器人' },
              { value: 'FEISHU', label: '飞书机器人' },
              { value: 'EMAIL', label: '邮件' },
            ]} />
          {chType === 'EMAIL' ? (
            <>
              <Input placeholder="SMTP 主机" value={chHost} style={{ width: 170 }}
                onChange={(e) => setChHost(e.target.value)} />
              <Input placeholder="端口" value={chPort} style={{ width: 80 }}
                onChange={(e) => setChPort(e.target.value)} />
              <Input placeholder="用户名（可选）" value={chUser} style={{ width: 150 }}
                onChange={(e) => setChUser(e.target.value)} />
              <Input.Password placeholder="密码（可选）" value={chPass} style={{ width: 150 }}
                onChange={(e) => setChPass(e.target.value)} />
              <Input placeholder="发件人 from@x.com" value={chFrom} style={{ width: 190 }}
                onChange={(e) => setChFrom(e.target.value)} />
              <Input placeholder="收件人 a@x.com,b@x.com" value={chTo} style={{ width: 220 }}
                onChange={(e) => setChTo(e.target.value)} />
            </>
          ) : (
            <>
              <Input placeholder="https://example.com/webhook" value={chUrl} style={{ width: 320 }}
                onChange={(e) => setChUrl(e.target.value)} />
              <Input placeholder="签名密钥（钉钉/飞书填写）" value={chSecret}
                style={{ width: 220 }} onChange={(e) => setChSecret(e.target.value)} />
            </>
          )}
          <Button type="primary" onClick={createChannel}>新建渠道</Button>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          告警事件产生后，平台会向启用的渠道 POST JSON（type/title/detail/severity），
          投递结果记录在 alert_delivery。
        </Typography.Text>
        <Table
          size="small"
          rowKey="id"
          style={{ marginTop: 12 }}
          dataSource={channels}
          pagination={false}
          locale={{ emptyText: tableEmpty('还没有通知渠道', '填名称和 Webhook 地址后点「新建渠道」') }}
          columns={[
            { title: '名称', dataIndex: 'name' },
            { title: '类型', dataIndex: 'type', width: 100 },
            { title: '地址', dataIndex: 'url', ellipsis: true },
            { title: '启用', dataIndex: 'enabled', width: 90,
              render: (v: boolean, r: any) => (
                <Switch size="small" checked={v} onChange={async (checked) => {
                  try {
                    await post(`/api/notify-channels/${r.id}/enabled`, { enabled: checked })
                    loadChannels()
                  } catch (e: any) { message.error(e.message || '切换失败') }
                }} />
              ) },
            { title: '操作', key: 'ops', width: 150,
              render: (_: unknown, r: any) => (
                <Space>
                  <Button size="small" onClick={async () => {
                    try {
                      const res: any = await post(`/api/notify-channels/${r.id}/test`)
                      res.ok ? message.success(`发送成功（HTTP ${res.httpStatus}）`)
                             : message.error(`发送失败：${res.response || res.httpStatus}`)
                    } catch (e: any) { message.error(e.message || '测试失败') }
                  }}>测试</Button>
                  <Popconfirm title={`删除通知渠道「${r.name}」？`} okText="删除" okButtonProps={{ danger: true }} onConfirm={async () => {
                    // del 调用点（D54）：Popconfirm 确认；渠道配置可重建，且投递记录留在 alert_delivery
                    try { await del(`/api/notify-channels/${r.id}`); loadChannels() }
                    catch (e: any) { message.error(errMsg(e)) }
                  }}>
                    <Button size="small" danger>删除</Button>
                  </Popconfirm>
                </Space>
              ) },
          ]}
        />
      </Drawer>

      {/* BUG-TC6-10 ①：关闭必须二次确认 + 处置说明；单项与批量共用这一个弹窗 */}
      <Modal
        open={!!closeTarget}
        title={closeTarget && closeTarget.length > 1
          ? `批量关闭 ${closeTarget.length} 条告警`
          : '关闭告警'}
        okText="确认关闭"
        okButtonProps={{ danger: true, disabled: closeReason.trim().length < MIN_CLOSE_REASON }}
        onOk={submitClose}
        onCancel={() => setCloseTarget(null)}
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {closeTarget && closeTarget.length === 1 && (
            <Typography.Text strong>{closeTarget[0].title}</Typography.Text>
          )}
          {closeSkipped > 0 && (
            <Typography.Text type="secondary">
              另有 {closeSkipped} 条不是「已确认」状态，已自动跳过（需先确认才能关闭）。
            </Typography.Text>
          )}
          <Alert
            type="info"
            showIcon
            message="关闭要留下处置说明，事后复盘只看这里"
            description="关闭不是终态：误关可以在列表里点「重开」回到「已确认」，原处置说明会保留在审计里。"
          />
          <Input.TextArea
            rows={4}
            maxLength={500}
            showCount
            value={closeReason}
            onChange={(e) => setCloseReason(e.target.value)}
            placeholder={`处置说明（必填，至少 ${MIN_CLOSE_REASON} 个字）：为什么可以关闭这条告警`}
          />
        </Space>
      </Modal>

      <Modal
        open={!!detail}
        title={detail?.title}
        footer={<Button onClick={() => setDetail(undefined)}>关闭</Button>}
        onCancel={() => setDetail(undefined)}
        width={640}
      >
        {detail && (
          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="类型">{TYPE_LABEL[detail.ruleType] || detail.ruleType}</Descriptions.Item>
            <Descriptions.Item label="级别">
              <Tag color={detail.severity === 'CRITICAL' ? 'red' : 'gold'}>
                {SEVERITY_LABEL[detail.severity] || detail.severity}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="规则">{detail.ruleName}</Descriptions.Item>
            <Descriptions.Item label="项目">{detail.projectName || '-'}</Descriptions.Item>
            <Descriptions.Item label="文件">{detail.workflowName || '-'}</Descriptions.Item>
            <Descriptions.Item label="实例">
              {detail.instanceId
                ? <a onClick={() => nav(`/ops/instances?instance=${detail.instanceId}`)}>#{detail.instanceId}</a>
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label="详情">{detail.detail || '-'}</Descriptions.Item>
            <Descriptions.Item label="状态">
              <Tag color={STATUS_COLOR[detail.status]}>{STATUS_LABEL[detail.status] || detail.status}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm:ss')}
            </Descriptions.Item>
            <Descriptions.Item label="处理记录">
              {detail.ackedBy ? `${detail.ackedBy} 于 ${dayjs(detail.ackedAt).format('MM-DD HH:mm')} 确认` : '未确认'}
              {detail.closedBy ? `；${detail.closedBy} 于 ${dayjs(detail.closedAt).format('MM-DD HH:mm')} 关闭` : ''}
            </Descriptions.Item>
            <Descriptions.Item label="处置说明">
              {detail.closeReason || (detail.status === 'CLOSED' ? '未填写（该功能发布前关闭的历史事件）' : '-')}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </div>
  )
}
