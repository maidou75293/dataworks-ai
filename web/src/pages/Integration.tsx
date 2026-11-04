import { useEffect, useMemo, useRef, useState } from 'react'
import dayjs from 'dayjs'
import {
  Alert, Button, Card, Descriptions, Drawer, Dropdown, Form, Input, InputNumber, Modal, Pagination,
  Popconfirm, Select, Space, Switch, Table, Tabs, Tag, Timeline, Tooltip, Typography, message,
} from 'antd'
import { DownOutlined, MoreOutlined, PlayCircleOutlined, PlusOutlined, SearchOutlined, StopOutlined } from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, errMsg, del, post, put } from '../api'
import { ACTION_TERMS } from '../terms'
import { can, canAny } from '../permissions'
import { setLeaveGuard } from '../unsaved'
import PageHeader from '../components/PageHeader'
import MemoTableRow from '../components/MemoTableRow'
import ImeInput from '../components/ImeInput'
import SearchableSelect from '../components/SearchableSelect'
import SourceStructurePreview from '../components/SourceStructurePreview'
import { EmptyState, ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'

type DataSource = {
  id: number
  name: string
  ds_type: string
  jdbc_url?: string
  config?: Record<string, any>
}

type Connector = {
  name: string
  source: boolean
  sink: boolean
  category?: string
  status: 'READY' | 'NEEDS_JAR' | 'DEFERRED'
}

type Task = {
  id: number
  projectId: number
  code: string
  name: string
  sourceType?: string
  sinkType?: string
  sourceConfig?: Record<string, any>
  sinkConfig?: Record<string, any>
  jobMode?: string
  sourceDatasourceId?: number
  sinkDatasourceId?: number
  sourceDatasourceName?: string
  sinkDatasourceName?: string
  sourceJdbcUrl?: string
  sourceDriver?: string
  sourceUser?: string
  sourceQuery?: string
  sinkFenodes?: string
  sinkDatabase?: string
  sinkTable?: string
  sinkUser?: string
  parallelism: number
  taskType?: string
  scheduleEnabled?: boolean
  scheduleCron?: string
  scheduleTimezone?: string
  retryTimes?: number
  timeoutMinutes?: number
  lastJobName?: string
  lastStatus?: string
  lastRunAt?: string
  lastScheduleAt?: string
  scheduleRetryCount?: number
  nextRunAt?: string
  dependsOnTaskId?: number
  released?: boolean
  releasedAt?: string
  releasedBy?: string
  lastSuccessAt?: string
}

const PARAM_HELP_DECL = [
  '[',
  '  {"name": "start_time", "type": "datetime", "required": true, "default": "${bizdatetime}"},',
  '  {"name": "limit_rows", "type": "int", "required": false, "default": "1000"}',
  ']',
].join('\n')

const PARAM_HELP_SQL = [
  'select id, code, name',
  'from platform.workflow',
  "where updated_at >= '${start_time}'",
  'limit ${limit_rows}',
].join('\n')

const CONNECTOR_BY_DS: Record<string, string> = {
  POSTGRESQL: 'Jdbc',
  MYSQL: 'Jdbc',
  DORIS: 'Doris',
  S3FILE: 'S3File',
  LOCALFILE: 'LocalFile',
  SFTPFILE: 'SftpFile',
  FTPFILE: 'FtpFile',
  MONGODB: 'MongoDB',
  REDIS: 'Redis',
  CLICKHOUSE: 'ClickHouse',
}

const FILE_SOURCES = ['SftpFile', 'FtpFile', 'LocalFile']

// Kafka 源必须声明字段（SeaTunnel 用它解析 JSON）；给一份和 demo_orders 对齐的样例。
const KAFKA_SCHEMA_SAMPLE = `{
  "fields": {
    "order_id": "string",
    "user_id": "string",
    "product_id": "string",
    "city": "string",
    "category": "string",
    "amount": "double",
    "order_ts": "bigint"
  }
}`

const MULTIMODAL_TYPES = ['IMAGE', 'AUDIO', 'VIDEO', 'DOCUMENT']
const MULTIMODAL_META = 'asset_type / mime_type / size_bytes / sha256 / width / height / duration_ms / page_count / language / dataset / source_uri / captured_at / tags'

// ---- REQ-DW-17：同步运行结果摘要统一口径（一次性试运行 / 手动提交 / 周期最近一次运行共用一套 UI） ----
const SUMMARY_TERMINAL = ['SUCCESS', 'FAILED', 'TIMEOUT', 'STOPPED', 'GONE', 'CANCELLED', 'NEVER_RUN', 'SKIPPED']
const SUMMARY_STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green', FAILED: 'red', TIMEOUT: 'orange', STOPPED: 'default',
  RUNNING: 'processing', PENDING: 'blue', SUBMITTED: 'blue', QUEUED: 'blue',
  SKIPPED: 'gold', CANCELLED: 'default', GONE: 'default', NEVER_RUN: 'default',
}
const SUMMARY_RUN_TYPE: Record<string, string> = {
  MANUAL: '手动', SCHEDULE: '周期调度', BACKFILL: '补数', API: 'API',
}

function SummaryBody({ s, waited }: { s: any; waited: number }) {
  const running = !SUMMARY_TERMINAL.includes(String(s.status))
  const fields = Array.isArray(s.fields) ? s.fields : []
  return (
    <div>
      {running && (
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message={`作业运行中（${s.status}），每 2.5 秒自动刷新状态；已等待约 ${Math.round(waited)} 秒`} />
      )}
      {!s.runId && s.status === 'NEVER_RUN' && (
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="任务还没有运行过；以下为配置的目标落点与字段清单，运行后会补齐记录数" />
      )}
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="状态">
          <Space size={4} wrap>
            <Tag color={SUMMARY_STATUS_COLOR[String(s.status)] || 'default'}>{String(s.status)}</Tag>
            {s.runType ? <Tag>{SUMMARY_RUN_TYPE[String(s.runType)] || String(s.runType)}</Tag> : null}
            {s.durationMs != null
              ? <Typography.Text type="secondary">耗时 {(Number(s.durationMs) / 1000).toFixed(1)} 秒</Typography.Text>
              : null}
          </Space>
        </Descriptions.Item>
        <Descriptions.Item label="同步记录数">
          {s.records != null && s.records !== ''
            ? <Typography.Text strong>{s.records} 条</Typography.Text>
            : <Typography.Text type="secondary">暂未记录</Typography.Text>}
          <Typography.Text type="secondary" style={{ marginLeft: 6 }}>
            （读取 {s.rows?.read ?? '-'} / 写入 {s.rows?.written ?? '-'}）
          </Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="字段清单">
          {fields.length > 0 ? (
            <Space size={4} wrap>
              {fields.map((f: any) => (
                <Tag key={String(f.name)}>{f.type ? `${f.name} · ${f.type}` : String(f.name)}</Tag>
              ))}
            </Space>
          ) : (
            <Typography.Text type="secondary">{s.fieldsNote || '无字段清单'}</Typography.Text>
          )}
        </Descriptions.Item>
        <Descriptions.Item label="目标落点">
          <Typography.Text strong>{s.landing?.path || '-'}</Typography.Text>
        </Descriptions.Item>
        <Descriptions.Item label="目标表不存在时">{s.tableBehavior?.text || '-'}</Descriptions.Item>
        {s.failure ? (
          <Descriptions.Item label="失败">
            <Space size={4}>
              <Tag color="red">{String(s.failure.stage)}</Tag>
              <Typography.Text>{String(s.failure.reason)}</Typography.Text>
            </Space>
          </Descriptions.Item>
        ) : null}
        {s.verifyResult ? (
          <Descriptions.Item label="行数校验">{String(s.verifyResult)}</Descriptions.Item>
        ) : null}
      </Descriptions>
    </div>
  )
}

function connectorOf(ds?: DataSource): string {
  return ds ? CONNECTOR_BY_DS[ds.ds_type] || ds.ds_type : ''
}

const jsonValidator = {
  validator: (_: any, value: any) => {
    if (!value || !value.trim()) return Promise.resolve()
    try {
      JSON.parse(value)
      return Promise.resolve()
    } catch (e: any) {
      return Promise.reject(new Error(`JSON 格式不合法：${e.message}`))
    }
  },
}

function configText(type?: string, config?: Record<string, any>): string {
  if (!type || !config) return ''
  if (type === 'S3File') return `${config.path || ''} (${config.fileFormat || 'binary'})`
  if (type === 'MongoDB') return config.collection || ''
  if (type === 'Redis') return config.keys || config.dataType || ''
  if (type === 'ClickHouse' || type === 'LocalFile') return config.table || config.path || ''
  return ''
}

const STATUS_LABEL: Record<string, string> = {
  NEVER_RUN: '未运行',
  PENDING: '等待中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  STOPPED: '已停止',
  GONE: '已结束',
  UNKNOWN: '未知',
}

const STATUS_COLOR: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
  RUNNING: 'blue',
  PENDING: 'orange',
  NEVER_RUN: 'default',
  GONE: 'default',
  STOPPED: 'default',
}

function databaseFromJdbc(url: string): string | undefined {
  const match = url.match(/^jdbc:[^:]+:\/\/[^/]+\/([^?]+)/)
  return match?.[1]
}

/** JDBC URL → host:port/db，用于列表里展示连接地址。 */
function urlSummary(url?: string): string {
  if (!url) return ''
  const match = url.match(/^jdbc:[^:]+:\/\/([^/?#]+)(?:\/([^?#]*))?/)
  if (!match) return url
  return match[2] ? `${match[1]}/${match[2]}` : match[1]
}

function tableSummary(value: any): string {
  if (!value) return ''
  if (Array.isArray(value)) {
    return value.map((v) => (typeof v === 'string' ? v : v?.table || '')).filter(Boolean).join(', ')
  }
  return String(value)
}

type ConnDetail = {
  dsName?: string
  type: string
  address: string
  database: string
  table: string
  user: string
  query: string
  rest: Record<string, any>
}

/** 统一解析源/目标的连接详情；手填连接也能拿到 host、库表与用户。 */
function connDetail(task: Task, side: 'source' | 'sink'): ConnDetail {
  const cfg = (side === 'source' ? task.sourceConfig : task.sinkConfig) || {}
  const url = String((side === 'source' ? task.sourceJdbcUrl || cfg.url : cfg.url) || '')
  const fenodes = String(cfg.fenodes || (side === 'sink' ? task.sinkFenodes : '') || '')
  const host = String(cfg.host || cfg.endpoint || cfg.uri || '')
  const address = urlSummary(url)
    || fenodes
    || (host ? `${host}${cfg.port ? `:${cfg.port}` : ''}` : '')
    || String(cfg.path || cfg.bucket || '')
  const database = String((side === 'sink' ? task.sinkDatabase || cfg.database : cfg.database)
    || databaseFromJdbc(url) || '')
  const table = tableSummary(side === 'sink'
    ? task.sinkTable || cfg.table || cfg.tables
    : cfg.table || cfg.tables)
  const user = String((side === 'source'
    ? task.sourceUser || cfg.user || cfg.username
    : task.sinkUser || cfg.user || cfg.username) || '')
  const query = String((side === 'source' ? task.sourceQuery || cfg.query : '') || '')
  const rest: Record<string, any> = { ...cfg }
  ;['url', 'user', 'username', 'password', 'secretKey', 'secret_key', 'auth', 'driver', 'query',
    'database', 'table', 'tables', 'fenodes'].forEach((key) => { delete rest[key] })
  return {
    dsName: side === 'source' ? task.sourceDatasourceName : task.sinkDatasourceName,
    type: (side === 'source' ? task.sourceType : task.sinkType) || (side === 'source' ? 'Jdbc' : 'Doris'),
    address, database, table, user, query, rest,
  }
}

function connLabel(task: Task, side: 'source' | 'sink'): string {
  const detail = connDetail(task, side)
  if (detail.dsName) return detail.dsName
  // JDBC 地址在卡片上只展示 host:port，库名留给第二行/详情，避免一行过长
  const host = detail.address.match(/^([^/]*:\d+)\//)?.[1]
  return host || detail.address || detail.table || detail.type
}

function connSummary(task: Task, side: 'source' | 'sink'): string {
  const detail = connDetail(task, side)
  const cfg = (side === 'source' ? task.sourceConfig : task.sinkConfig) || {}
  const rawUrl = String((side === 'source' ? task.sourceJdbcUrl || cfg.url : cfg.url) || '')
  const parts: string[] = []
  // JDBC 地址本身已经说明连接器；再写一个 "Jdbc" 只会挤占库表名的空间
  if (!rawUrl.startsWith('jdbc:')) parts.push(detail.type)
  // 源端不重复库名；目标端展示 库.表，一眼看清写到哪里
  if (side === 'sink') {
    if (detail.database && detail.table) parts.push(`${detail.database}.${detail.table}`)
    else if (detail.database) parts.push(detail.database)
    else if (detail.table) parts.push(detail.table)
  }
  const extra = configText(side === 'source' ? task.sourceType : task.sinkType,
    side === 'source' ? task.sourceConfig : task.sinkConfig)
  if (extra && !parts.includes(extra)) parts.push(extra)
  return parts.filter(Boolean).join(' · ')
}

export default function Integration() {
  const nav = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [tasks, setTasks] = useState<Task[]>([])
  const [taskTab, setTaskTab] = useState('all')
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState('ALL')
  const [page, setPage] = useState(1)
  const [projects, setProjects] = useState<any[]>([])
  const [datasources, setDatasources] = useState<DataSource[]>([])
  const [sourcePreviewOpen, setSourcePreviewOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Task>()
  const [logs, setLogs] = useState('')
  const [logsOpen, setLogsOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [backfillOpen, setBackfillOpen] = useState(false)
  const [backfillTask, setBackfillTask] = useState<Task>()
  const [backfillStart, setBackfillStart] = useState('')
  const [backfillEnd, setBackfillEnd] = useState('')
  const [historyRows, setHistoryRows] = useState<any[]>([])
  const [historyTask, setHistoryTask] = useState<Task>()
  const [runDetail, setRunDetail] = useState<any>()
  const [runEvents, setRunEvents] = useState<any[]>([])
  const [runMetricSamples, setRunMetricSamples] = useState<any[]>([])
  const [runLogText, setRunLogText] = useState('')
  const [runLogOpen, setRunLogOpen] = useState(false)
  const [connectors, setConnectors] = useState<Connector[]>([])
  const [manualSourceMode, setManualSourceMode] = useState(false)
  const [manualSinkMode, setManualSinkMode] = useState(false)
  const [detailTask, setDetailTask] = useState<Task>()
  const [detailSide, setDetailSide] = useState<'source' | 'sink'>('source')
  const [runTask, setRunTask] = useState<Task>()
  const [previewSql, setPreviewSql] = useState('')
  const [running, setRunning] = useState(false)
  // P2-2：保存/校验都要有 in-flight 状态；只靠 disabled 挡不住同一事件循环里的双击
  // REQ-UX-TERMS-1：动作词收敛单点=terms.ts ACTION_TERMS（校验配置/试运行）
  const [saving, setSaving] = useState(false)
  const [validating, setValidating] = useState(false)
  const [validateOk, setValidateOk] = useState(false)
  const [checks, setChecks] = useState<any[]>([])
  const [configChecks, setConfigChecks] = useState<any[]>([])
  const [paramHelpOpen, setParamHelpOpen] = useState(false)
  // 表单是否有未保存改动：弹窗遮罩/ESC/取消都可能误触，需要先确认再丢
  const [formDirty, setFormDirty] = useState(false)
  const checksRef = useRef<HTMLDivElement>(null)
  // P2-2：同步锁，避免双击在 setState 生效前进入两次
  const savingRef = useRef(false)
  const validatingRef = useRef(false)
  // P2-3：数据源列表的请求序号，只接受最后一次响应，避免 A 项目的结果覆盖 B 项目
  const datasourceSeq = useRef(0)
  // 新建/编辑共用一个打开序号：后点的操作赢，晚到的旧回填直接丢弃
  const openSeq = useRef(0)
  // 浏览器刷新/关闭/后退不会走 Modal 的 requestClose，这里补全局守卫
  useEffect(() => {
    if (!open || !formDirty) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    setLeaveGuard(() => window.confirm('同步任务还有未保存的填写内容，离开会丢失。确定离开吗？'))
    return () => {
      window.removeEventListener('beforeunload', handler)
      setLeaveGuard(undefined)
    }
  }, [open, formDirty])
  // P2-4：浏览器后退是同一文档内的 history 导航，不触发 beforeunload。
  // 在 capture 阶段先拦：取消时阻止 Router 处理，并把 URL 恢复成当前页。
  useEffect(() => {
    if (!open || !formDirty) return
    const onPop = (event: PopStateEvent) => {
      if (!open || !formDirty) return
      if (window.confirm('同步任务还有未保存的填写内容，离开会丢失。确定离开吗？')) {
        window.removeEventListener('popstate', onPop, true)
        return
      }
      event.stopImmediatePropagation()
      window.history.pushState(null, '', window.location.href)
    }
    window.addEventListener('popstate', onPop, true)
    return () => window.removeEventListener('popstate', onPop, true)
  }, [open, formDirty])
  const [form] = Form.useForm()
  const [runForm] = Form.useForm()
  const sourceDsId = Form.useWatch('sourceDatasourceId', form)
  const sinkDsId = Form.useWatch('sinkDatasourceId', form)
  // 项目对应的 Doris 库与表前缀：目标库/目标表默认按项目生成
  const watchedProjectId = Form.useWatch('projectId', form)
  const formProject = projects.find((p) => p.id === watchedProjectId)
  const manualSourceType = Form.useWatch('sourceType', form)
  const manualSinkType = Form.useWatch('sinkType', form)
  // REQ-DW-17：调度类型切换配置区——开=周期字段；关=隐藏周期字段、出现「试运行」
  const scheduleOn = Form.useWatch('scheduleEnabled', form)
  const sourceDs = datasources.find((d) => d.id === sourceDsId)
  const sinkDs = datasources.find((d) => d.id === sinkDsId)
  const sourceConnector = manualSourceMode ? manualSourceType : connectorOf(sourceDs)
  const sinkConnector = manualSinkMode ? manualSinkType : connectorOf(sinkDs)
  // 历史任务可能是手填连接（没有数据源 id），编辑时展示连接字段而不是强制重绑
  const manualSource = Boolean(editing && !editing.sourceDatasourceId)
  const manualSink = Boolean(editing && !editing.sinkDatasourceId)

  const load = async () => {
    setLoading(true)
    try {
      const res = await api<{ tasks: Task[] }>('/api/integration/tasks')
      setTasks(res.tasks || [])
      setError('')
    } catch (e: any) {
      setError(e.message || '加载同步任务失败')
    } finally {
      setLoading(false)
    }
  }

  const loadProjects = async () => {
    try {
      const res = await api<any[]>('/api/projects')
      setProjects(res || [])
      if (res && res.length > 0) await loadDatasources(res[0].id)
    } catch {
      setProjects([])
    }
  }

  const loadDatasources = async (projectId: number) => {
    const seq = ++datasourceSeq.current
    if (!projectId) {
      setDatasources([])
      return []
    }
    try {
      const list = await api<DataSource[]>(`/api/projects/${projectId}/datasources`)
      // P2-3：快速切项目/连续点编辑时，晚到的旧响应不能覆盖新项目的数据源
      if (seq === datasourceSeq.current) setDatasources(list || [])
      return list || []
    } catch {
      if (seq === datasourceSeq.current) setDatasources([])
      return []
    }
  }

  useEffect(() => {
    const active = tasks.filter((t) => t.lastStatus === 'PENDING' || t.lastStatus === 'RUNNING')
    if (active.length === 0) return
    const timer = setInterval(async () => {
      for (const t of active) {
        try {
          const res = await api<{ status: string }>(`/api/integration/tasks/${t.id}/status`)
          setTasks((prev) => prev.map((x) => x.id === t.id
            ? { ...x, lastStatus: res.status } : x))
        } catch {
          // 轮询失败不打扰用户
        }
      }
    }, 4000)
    return () => clearInterval(timer)
  }, [tasks.map((t) => `${t.id}:${t.lastStatus}`).join('|')])

  useEffect(() => {
    load()
    loadProjects()
    api<{ connectors: Connector[] }>('/api/integration/connectors')
      .then((res) => setConnectors(res.connectors || []))
      .catch(() => setConnectors([]))
  }, [])

  // 数据源页「建同步任务」深链：/integration?create=1&sourceDatasourceId=&projectId=
  // 打开一次后清掉查询串，刷新/返回不会再弹一次
  useEffect(() => {
    if (searchParams.get('create') !== '1' || projects.length === 0) return
    const sourceId = Number(searchParams.get('sourceDatasourceId')) || undefined
    const projectId = Number(searchParams.get('projectId')) || undefined
    const next = new URLSearchParams(searchParams)
    next.delete('create')
    next.delete('sourceDatasourceId')
    next.delete('projectId')
    setSearchParams(next, { replace: true })
    openCreate(sourceId, projectId).catch((e) => message.error(errMsg(e)))
  }, [projects.length, searchParams])

  const openCreate = async (prefillSourceId?: number, prefillProjectId?: number) => {
    const projectId = prefillProjectId || projects[0]?.id
    const seq = ++openSeq.current
    const list = await loadDatasources(projectId)
    // 连续点了别的编辑/新建时，这次晚到的回填不能把新表单覆盖掉
    if (seq !== openSeq.current) return
    setEditing(undefined)
    setManualSourceMode(false)
    setManualSinkMode(false)
    setConfigChecks([])
    setFormDirty(false)
    form.resetFields()
    // 从数据源页「建同步任务」进来时预填该数据源；找不到才退回第一个
    const source = (prefillSourceId && list.find((d) => d.id === prefillSourceId)) || list[0]
    const sink = list.find((d) => d.ds_type === 'DORIS')
    const project = projects.find((p) => p.id === projectId)
    form.setFieldsValue({
      projectId,
      sourceDatasourceId: source?.id,
      sinkDatasourceId: sink?.id,
      // 项目新建时已建 gongxiang_dev / gongxiang_prd，优先落到项目自己的 DEV 库
      sinkDatabase: project?.dev_database
        || (sink ? databaseFromJdbc(sink.jdbc_url || '') : undefined),
      parallelism: 1,
      jobMode: 'BATCH',
      taskType: 'OFFLINE',
      scheduleEnabled: false,
      scheduleTimezone: 'Asia/Shanghai',
      retryTimes: 0,
      timeoutMinutes: 60,
      dependsOnTaskId: undefined,
      sourceType: connectorOf(source),
      sinkType: connectorOf(sink),
      sourceConfig: {},
      sinkConfig: {},
      sourceSchemaJson: '',
    })
    setOpen(true)
  }

  // 连续点两个任务的「编辑」时，只有最后一次点击能回填表单；
  // 否则 A 的异步结果会晚到并覆盖 B 的表单，保存时把 A 的配置写进 B
  const openEdit = async (task: Task) => {
    const seq = ++openSeq.current
    setEditing(task)
    const sourceManual = !task.sourceDatasourceId
    const sinkManual = !task.sinkDatasourceId
    setManualSourceMode(sourceManual)
    setManualSinkMode(sinkManual)
    setConfigChecks([])
    setFormDirty(false)
    await loadDatasources(task.projectId)
    if (seq !== openSeq.current) return
    // 先清空再回填：否则上一个任务手填的密码等字段会残留在新表单里
    form.resetFields()
    form.setFieldsValue({
      sourceConfigJson: JSON.stringify(task.sourceConfig || {}, null, 2),
      sinkConfigJson: JSON.stringify(task.sinkConfig || {}, null, 2),
      projectId: task.projectId,
      code: task.code,
      name: task.name,
      sourceDatasourceId: task.sourceDatasourceId,
      sinkDatasourceId: task.sinkDatasourceId,
      sourceParamsJson: Array.isArray(task.sourceConfig?.params)
        ? JSON.stringify(task.sourceConfig?.params, null, 2) : '',
      sourceSchemaJson: task.sourceConfig?.schema
        ? JSON.stringify(task.sourceConfig.schema, null, 2) : '',
      sourceType: task.sourceType || 'Jdbc',
      sinkType: task.sinkType || 'Doris',
      sourceConfig: task.sourceConfig || {},
      sinkConfig: task.sinkConfig || {},
      jobMode: task.jobMode || 'BATCH',
      sourceJdbcUrl: task.sourceJdbcUrl,
      sourceDriver: task.sourceDriver,
      sourceUser: task.sourceUser,
      sourceQuery: task.sourceQuery,
      sinkFenodes: task.sinkFenodes,
      sinkDatabase: task.sinkDatabase,
      sinkTable: task.sinkTable,
      sinkUser: task.sinkUser,
      parallelism: task.parallelism ?? 1,
      taskType: task.taskType || 'OFFLINE',
      scheduleEnabled: !!task.scheduleEnabled,
      scheduleCron: task.scheduleCron,
      scheduleTimezone: task.scheduleTimezone || 'Asia/Shanghai',
      retryTimes: task.retryTimes ?? 0,
      timeoutMinutes: task.timeoutMinutes ?? 60,
      dependsOnTaskId: task.dependsOnTaskId,
    })
    setOpen(true)
  }

  // 关闭前拦截：表单填了一半时点遮罩、按 ESC 或点取消都不该直接清空
  const requestClose = () => {
    // 保存请求还没回来时不允许关闭，否则会出现「已关闭又弹成功」的错觉
    if (savingRef.current) return
    if (!formDirty) {
      setOpen(false)
      return
    }
    Modal.confirm({
      title: '放弃未保存的修改？',
      content: '当前同步任务还有未保存的填写内容，关闭后需要重新填写。',
      okText: '放弃修改',
      okButtonProps: { danger: true },
      cancelText: '继续编辑',
      onOk: () => setOpen(false),
    })
  }

  const parseConfig = (raw?: string): Record<string, any> => {
    if (!raw || !raw.trim()) return {}
    try {
      const parsed = JSON.parse(raw)
      return parsed && typeof parsed === 'object' ? parsed : {}
    } catch {
      return {}
    }
  }

  const buildPayload = (values: any) => {
    const payload: any = { ...values }
    delete payload.sourceConfigJson
    delete payload.sinkConfigJson
    delete payload.sourceParamsJson
    const sourceSchemaJson = values.sourceSchemaJson
    delete payload.sourceSchemaJson
    const kafkaSource = !manualSourceMode && sourceConnector === 'Kafka'
    let params: any[] = []
    if (values.sourceParamsJson && values.sourceParamsJson.trim()) {
      params = parseConfig(values.sourceParamsJson) as any
      if (!Array.isArray(params)) {
        throw new Error('运行参数声明必须是 JSON 数组')
      }
    }
    if (manualSourceMode) {
      payload.sourceDatasourceId = null
      const manualConfig = parseConfig(values.sourceConfigJson)
      // 手动模式不渲染 sourceParamsJson；空值时要保留 JSON 里已有的 params，
      // 否则保存会把它覆盖成空数组，运行弹窗再也拿不到参数。
      const preserved = params.length > 0 ? params
        : (Array.isArray(manualConfig.params) ? manualConfig.params : [])
      payload.sourceConfig = { ...manualConfig, params: preserved }
    } else {
      // P2-1：数据源模式下也要合并表单里的嵌套 sourceConfig（path/fileFormat/
      // collection 等）。之前只带 query/params，用户改了这些字段保存后仍是旧值。
      const formConfig = values.sourceConfig && typeof values.sourceConfig === 'object'
        ? { ...values.sourceConfig } : {}
      const merged: Record<string, any> = { ...(editing?.sourceConfig || {}), ...formConfig }
      if (values.sourceQuery !== undefined && !kafkaSource) merged.query = values.sourceQuery
      // 非 Kafka 的 SQL 参数框清空 = 明确要清空；Kafka 不渲染参数框，保留已有声明
      merged.params = params.length > 0 || !kafkaSource
        ? params
        : (Array.isArray(merged.params) ? merged.params : [])
      if (kafkaSource) {
        // 字段定义在表单里是 JSON 文本，落库要变成对象（渲染器按嵌套块输出）
        const schema = parseConfig(sourceSchemaJson)
        if (Object.keys(schema).length > 0) merged.schema = schema
        else delete merged.schema
      }
      // 新建任务也要落 sourceConfig（S3File/MongoDB 等连接器只靠嵌套字段）
      if (Object.keys(merged).length > 0 || editing) payload.sourceConfig = merged
    }
    if (manualSinkMode) {
      payload.sinkDatasourceId = null
      payload.sinkConfig = parseConfig(values.sinkConfigJson)
    } else {
      // 目标端同理：S3File/MongoDB/Redis 的 path/collection/dataType 都在嵌套配置里
      const sinkFormConfig = values.sinkConfig && typeof values.sinkConfig === 'object'
        ? { ...values.sinkConfig } : {}
      const mergedSink: Record<string, any> = { ...(editing?.sinkConfig || {}), ...sinkFormConfig }
      if (Object.keys(mergedSink).length > 0 || editing) payload.sinkConfig = mergedSink
    }
    return payload
  }

  const validateConfigForm = async () => {
    // P2-2：校验请求进行中直接忽略重复点击，避免叠加请求和结果乱序
    if (validatingRef.current) return
    validatingRef.current = true
    setValidating(true)
    try {
      const values = await form.validateFields()
      const res = await post<{ ok: boolean; checks: any[] }>(
        '/api/integration/tasks/validate', buildPayload(values))
      const list = res.checks || []
      setConfigChecks(list)
      const failed = list.filter((c) => !c.ok).length
      if (res.ok) message.success(`校验通过：${list.length} 项`)
      else message.error(`校验未通过：${failed} 项失败，见弹窗底部「校验结果」`)
      // 结果区在表单最下方，校验后滚过去，避免用户只看到一条 toast
      window.setTimeout(() => {
        checksRef.current?.scrollIntoView({ block: 'center' })
      }, 0)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message || '校验失败')
    } finally {
      validatingRef.current = false
      setValidating(false)
    }
  }

  const submit = async () => {
    // P2-2：双击保存时第二次会在第一次成功前再发一次 POST，用户看到 409
    // 「任务编码已存在」，误以为创建失败。这里用同步锁直接吞掉重复点击。
    if (savingRef.current) return
    savingRef.current = true
    setSaving(true)
    try {
      let values: any
      try {
        values = await form.validateFields()
      } catch {
        // 字段错误已就地标红，不再弹一条内容为空的 error toast
        return
      }
      let payload: any
      try {
        payload = buildPayload(values)
      } catch (e: any) {
        message.error(errMsg(e))
        return
      }
      if (editing) {
        await put(`/api/integration/tasks/${editing.id}`, payload)
        message.success('已保存')
      } else {
        await post('/api/integration/tasks', payload)
        message.success('已创建')
      }
      setFormDirty(false)
      setOpen(false)
      load()
    } catch (e: any) {
      message.error(e.message || '保存失败')
    } finally {
      savingRef.current = false
      setSaving(false)
    }
  }

  const paramsOf = (task?: Task): any[] =>
    Array.isArray(task?.sourceConfig?.params) ? task!.sourceConfig!.params : []

  const submitRun = async (task: Task, params: Record<string, any>) => {
    if (!validateOk) {
      message.warning('请先点「校验配置」，校验通过后才能提交')
      return
    }
    try {
      setRunning(true)
      const res = await post<{ jobName?: string; status?: string; message?: string; runId?: number }>(
        `/api/integration/tasks/${task.id}/run`, { params })
      // 文件增量没有新文件时后端不建作业，只返回 SKIPPED；这里不能显示 undefined
      if (res.status === 'SKIPPED') {
        message.info(res.message || '没有新增文件，本次未提交作业')
      } else if (res.runId) {
        // REQ-DW-17：提交后直接展示统一结果摘要（与一次性「试运行」同口径）
        openSummaryPoll(task.id, Number(res.runId))
      } else {
        message.success(`已提交：${res.jobName}`)
      }
      setRunTask(undefined)
      load()
    } catch (e: any) {
      message.error(e.message || '提交失败')
    } finally {
      setRunning(false)
    }
  }

  const run = async (task: Task) => {
    const specs = paramsOf(task)
    const init: Record<string, any> = {}
    specs.forEach((p) => {
      if (p.default !== undefined && p.default !== null) {
        // 参数默认值常写成字符串 "false"/"0"；Boolean("false") 是 true
        init[p.name] = p.type === 'bool' || p.type === 'boolean'
          ? (p.default === true || String(p.default).toLowerCase() === 'true')
          : p.default
      }
    })
    runForm.resetFields()
    runForm.setFieldsValue(init)
    setPreviewSql('')
    setValidateOk(false)
    setChecks([])
    setRunTask(task)
  }

  // ---- REQ-DW-17：结果摘要弹窗 + 轮询（试运行 / 手动提交 / 周期最近一次运行共用） ----
  const [summary, setSummary] = useState<any>()
  const [summaryOpen, setSummaryOpen] = useState(false)
  const [summaryWaited, setSummaryWaited] = useState(0)
  const [runningNow, setRunningNow] = useState(false)
  const summaryTimer = useRef<number | null>(null)
  const stopSummaryPoll = () => {
    if (summaryTimer.current !== null) {
      window.clearTimeout(summaryTimer.current)
      summaryTimer.current = null
    }
  }
  useEffect(() => {
    stopSummaryPoll()
  }, [])
  const pollSummary = async (taskId: number, runId: number, waited: number) => {
    if (waited >= 600) {
      // 10 分钟封顶：不无限转；用户可稍后从卡片「结果摘要」再拉
      stopSummaryPoll()
      return
    }
    try {
      // 先把 SeaTunnel 真实状态刷回库（refreshStatus 口径），再读摘要，看到的才是新数
      await api(`/api/integration/tasks/${taskId}/status`).catch(() => undefined)
      const s = await api<any>(`/api/integration/tasks/runs/${runId}/summary`)
      setSummary(s)
      setSummaryWaited(waited)
      if (SUMMARY_TERMINAL.includes(String(s.status))) {
        stopSummaryPoll()
        load()
        return
      }
    } catch (e: any) {
      if (String(e?.message || '').includes('不存在')) {
        stopSummaryPoll()
        return
      }
      // 后端瞬断不打死轮询，到封顶时间自然收口
    }
    summaryTimer.current = window.setTimeout(
      () => { pollSummary(taskId, runId, waited + 2.5) }, 2500)
  }
  const openSummaryPoll = (taskId: number, runId: number) => {
    stopSummaryPoll()
    setSummaryWaited(0)
    setSummaryOpen(true)
    pollSummary(taskId, runId, 0)
  }
  const directRun = async (task: Task) => {
    // REQ-DW-17：「试运行」——运行参数按声明的默认值预填；
    // 缺必填参数由后端 400 报人话，不静默吞
    const init: Record<string, any> = {}
    paramsOf(task).forEach((p) => {
      if (p.default !== undefined && p.default !== null) {
        init[p.name] = p.type === 'bool' || p.type === 'boolean'
          ? (p.default === true || String(p.default).toLowerCase() === 'true')
          : p.default
      }
    })
    const res = await post<{ jobName?: string; status?: string; message?: string; runId?: number }>(
      `/api/integration/tasks/${task.id}/run`, { params: init })
    if (res.status === 'SKIPPED') {
      message.info(res.message || '没有新增文件，本次未提交作业')
    } else if (res.runId) {
      openSummaryPoll(task.id, Number(res.runId))
    } else {
      message.success(`已提交：${res.jobName}`)
    }
    load()
  }
  const runNowFromForm = async () => {
    if (!editing) return
    if (formDirty) {
      message.warning('配置还没保存：先保存，再点「试运行」')
      return
    }
    try {
      setRunningNow(true)
      await directRun(editing)
    } catch (e: any) {
      message.error(e.message || '试运行提交失败')
    } finally {
      setRunningNow(false)
    }
  }
  const showSummary = async (task: Task) => {
    // REQ-DW-17：周期任务「最近一次运行」同口径回看
    try {
      const s = await api<any>(`/api/integration/tasks/${task.id}/summary`)
      setSummaryWaited(0)
      setSummary(s)
      setSummaryOpen(true)
      if (s.status === 'NEVER_RUN') {
        message.info('任务还没有运行过')
        return
      }
      if (s.runId && !s.terminal) {
        openSummaryPoll(Number(s.taskId ?? task.id), Number(s.runId))
      }
    } catch (e: any) {
      message.error(e.message || '读取结果摘要失败')
    }
  }

  const publishTask = async (task: Task) => {
    try {
      await post(`/api/integration/tasks/${task.id}/publish`)
      // REQ-UX-FLOWLINK-1（审计§三12）：「可按调度运行」不能再是断链 toast——
      // 对齐数据开发侧「在运行实例页打开」（Development.tsx）的做法，带上链接直接
      // 打开本任务的「运行实例」模态；时长放宽到 8s 保证来得及点。
      message.success({
        duration: 8,
        content: (
          <span>
            已发布到生产环境：配置已冻结，可按调度运行。
            <Typography.Link onClick={() => void showHistory(task)}>查看运行实例</Typography.Link>
          </span>
        ),
      })
      load()
    } catch (e: any) {
      message.error(e.message || '发布失败')
    }
  }

  const offlineTask = async (task: Task) => {
    try {
      await post(`/api/integration/tasks/${task.id}/offline`)
      message.success('已下线：回到开发态，可继续修改')
      load()
    } catch (e: any) {
      message.error(e.message || '下线失败')
    }
  }

  const validateRun = async () => {
    if (!runTask) return
    try {
      const values = await runForm.validateFields()
      const res = await post<{ ok: boolean; checks: any[] }>(
        `/api/integration/tasks/${runTask.id}/validate`, { params: values })
      setChecks(res.checks || [])
      setValidateOk(Boolean(res.ok))
      if (res.ok) {
        message.success('校验通过，可以提交运行')
      } else {
        message.error('校验未通过，请按提示修正')
      }
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message || '校验失败')
    }
  }

  const previewSqlNow = async () => {
    if (!runTask) return
    try {
      const values = await runForm.validateFields()
      const res = await post<{ sql: string[] }>(
        `/api/integration/tasks/${runTask.id}/preview`, { params: values })
      setPreviewSql((res.sql || []).join('\n\n'))
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e.message || '预览失败')
    }
  }

  const stopTask = async (task: Task) => {
    try {
      await post(`/api/integration/tasks/${task.id}/stop`, {})
      message.success('已停止当前作业')
      await load()
    } catch (e: any) {
      message.error(e.message || '停止失败')
    }
  }

  const refreshStatus = async (task: Task) => {
    try {
      const res = await api<{ status: string }>(`/api/integration/tasks/${task.id}/status`)
      message.info(`状态：${res.status}`)
      load()
    } catch (e: any) {
      message.error(`「任务状态」加载失败：${e.message || '未知原因'}`)
    }
  }

  const showLogs = async (task: Task) => {
    try {
      const text = await api<string>(`/api/integration/tasks/${task.id}/logs`)
      setLogs(typeof text === 'string' ? text : JSON.stringify(text, null, 2))
      setLogsOpen(true)
    } catch (e: any) {
      message.error(e.message || '读取日志失败')
    }
  }

  const openBackfill = (task: Task) => {
    setBackfillTask(task)
    // REQ-DW-14：默认补 T-7 ~ T-1，与任务编辑器「补数据」同一口径（当天通常还在跑，不抢日常调度）
    setBackfillStart(dayjs().subtract(7, 'day').format('YYYY-MM-DD'))
    setBackfillEnd(dayjs().subtract(1, 'day').format('YYYY-MM-DD'))
    setBackfillOpen(true)
  }

  const [backfillBusy, setBackfillBusy] = useState(false)
  const submitBackfill = async () => {
    if (!backfillTask || backfillBusy) return
    // REQ-DW-14：不再把空串原样发给后端（后端也会 400，但这里先给一句人话）
    const isoDate = /^\d{4}-\d{2}-\d{2}$/
    if (!isoDate.test(backfillStart) || !isoDate.test(backfillEnd)) {
      message.warning('开始/结束日期都要填，格式 yyyy-MM-dd')
      return
    }
    if (backfillStart > backfillEnd) {
      message.warning('开始日期不能晚于结束日期')
      return
    }
    setBackfillBusy(true)
    try {
      const res = await post<any>(`/api/integration/tasks/${backfillTask.id}/backfill`, {
        start: backfillStart, end: backfillEnd,
      })
      message.success(`已提交 ${res.submitted} 个补数作业`)
      setBackfillOpen(false)
      load()
    } catch (e: any) {
      message.error(e.message || '补数提交失败')
    } finally {
      setBackfillBusy(false)
    }
  }

  const showHistory = async (task: Task) => {
    // 换任务时清掉上一个任务的运行详情，否则 B 的抽屉里显示 A 的卡片
    setRunDetail(undefined)
    setRunEvents([])
    setRunMetricSamples([])
    setRunLogText('')
    try {
      const res = await api<{ runs: any[] }>(`/api/integration/tasks/${task.id}/runs`)
      setHistoryRows(res.runs || [])
      setHistoryTask(task)
      setHistoryOpen(true)
    } catch (e: any) {
      message.error(e.message || '读取运行历史失败')
    }
  }

  const openRun = async (runId: number) => {
    try {
      const [detail, events, metrics] = await Promise.all([
        api<any>(`/api/integration/tasks/runs/${runId}`),
        api<any>(`/api/integration/tasks/runs/${runId}/events`),
        api<any>(`/api/integration/tasks/runs/${runId}/metrics`),
      ])
      setRunDetail(detail)
      setRunEvents(events?.events || [])
      setRunMetricSamples(metrics?.samples || [])
    } catch (e: any) {
      message.error(e.message || '读取运行实例失败')
    }
  }

  const rerun = async (run: any) => {
    try {
      const params = run?.params && typeof run.params === 'object' ? run.params : {}
      await post(`/api/integration/tasks/${run.taskId}/run`, { params })
      message.success('已按原参数重新提交')
      if (historyTask) {
        const res = await api<{ runs: any[] }>(`/api/integration/tasks/${historyTask.id}/runs`)
        setHistoryRows(res.runs || [])
      }
      load()
    } catch (e: any) {
      message.error(e.message || '重跑失败')
    }
  }

  const showRunLogs = async (runId: number) => {
    try {
      const res = await api<any>(`/api/integration/tasks/runs/${runId}/logs`)
      setRunLogText(res?.log || '')
      setRunLogOpen(true)
    } catch (e: any) {
      message.error(e.message || '读取运行日志失败')
    }
  }

  const offline = tasks.filter((t) => (t.taskType || 'OFFLINE') !== 'REALTIME')
  const realtime = tasks.filter((t) => t.taskType === 'REALTIME')
  const shownTasks = taskTab === 'OFFLINE' ? offline : taskTab === 'REALTIME' ? realtime : tasks
  const term = keyword.trim().toLowerCase()
  const statusCounts = {
    ALL: shownTasks.length,
    FAILED: shownTasks.filter((t) => t.lastStatus === 'FAILED').length,
    SUCCESS: shownTasks.filter((t) => t.lastStatus === 'SUCCESS').length,
    RUNNING: shownTasks.filter((t) => t.lastStatus === 'RUNNING' || t.lastStatus === 'PENDING').length,
    NEVER_RUN: shownTasks.filter((t) => !t.lastStatus || t.lastStatus === 'NEVER_RUN').length,
  }
  // v452：过滤与分页结果缓存，输入关键字时只重算这一块
  const statusScoped = useMemo(() => (statusFilter === 'ALL' ? shownTasks : shownTasks.filter((t) => {
    if (statusFilter === 'RUNNING') return t.lastStatus === 'RUNNING' || t.lastStatus === 'PENDING'
    if (statusFilter === 'NEVER_RUN') return !t.lastStatus || t.lastStatus === 'NEVER_RUN'
    return t.lastStatus === statusFilter
  })), [shownTasks, statusFilter])
  const filteredTasks = useMemo(() => (term
    ? statusScoped.filter((t) => `${t.name} ${t.code} ${connLabel(t, 'source')} ${connLabel(t, 'sink')}`
      .toLowerCase().includes(term))
    : statusScoped), [statusScoped, term])
  // 卡片一次最多渲染 20 条，任务多时不再整页铺开
  const PAGE_SIZE = 20
  const totalPages = Math.max(1, Math.ceil(filteredTasks.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages)
  const pagedTasks = useMemo(
    () => filteredTasks.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE),
    [filteredTasks, currentPage])

  return (
    <div>
      <PageHeader
        title="数据集成"
        description="选择源数据源与 Doris 目标数据源，平台渲染成 SeaTunnel conf 并提交到 Zeta 集群。"
        extra={
          <Space wrap>
            {can('sync:write') && (
              <Dropdown
                menu={{
                  items: [
                    { key: 'sync', label: '同步任务（离线/增量，SeaTunnel）' },
                    {
                      key: 'realtime',
                      label: '实时任务（FlinkSQL，进流开发创建）',
                      disabled: !canAny('flink:write'),
                    },
                  ],
                  onClick: ({ key }) => {
                    if (key === 'sync') {
                      openCreate().catch((e) => message.error(errMsg(e)))
                    } else {
                      // REQ-DW-3：实时任务复用流开发创建链路，不重复实现后端
                      nav('/stream?create=1')
                    }
                  },
                }}
              >
                <Button type="primary" icon={<PlusOutlined />}>
                  新建任务 <DownOutlined />
                </Button>
              </Dropdown>
            )}
            <ImeInput
              allowClear
              prefix={<SearchOutlined />}
              placeholder="搜索名称 / 编码 / 源 / 目标"
              style={{ width: 240 }}
              value={keyword}
              onChange={(v) => {
                setKeyword(v)
                setPage(1)
              }}
            />
            <Button onClick={load} loading={loading}>刷新</Button>
            <Typography.Text type="secondary">
              {term || statusFilter !== 'ALL'
                ? `${filteredTasks.length} / ${shownTasks.length}` : tasks.length} 个任务
            </Typography.Text>
          </Space>
        }
      />
      <Card size="small">
        {error ? <ErrorState message={error} onRetry={load} /> : (
          <>
          <Tabs
            size="small"
            activeKey={taskTab}
            onChange={(key) => {
              setTaskTab(key)
              setPage(1)
            }}
            items={[
              { key: 'all', label: `全部 ${tasks.length}` },
              { key: 'OFFLINE', label: `离线任务 ${offline.length}` },
              { key: 'REALTIME', label: `实时任务 ${realtime.length}` },
            ]}
          />
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            gap: 8, marginBottom: 8, flexWrap: 'wrap',
          }}>
            <Space wrap size={8}>
              <Select
                size="small"
                style={{ width: 160 }}
                value={statusFilter}
                onChange={(value) => {
                  setStatusFilter(value)
                  setPage(1)
                }}
                options={[
                  { value: 'ALL', label: `全部状态 ${statusCounts.ALL}` },
                  { value: 'FAILED', label: `失败 ${statusCounts.FAILED}` },
                  { value: 'SUCCESS', label: `成功 ${statusCounts.SUCCESS}` },
                  { value: 'RUNNING', label: `运行中 ${statusCounts.RUNNING}` },
                  { value: 'NEVER_RUN', label: `未运行 ${statusCounts.NEVER_RUN}` },
                ]}
              />
              {statusFilter !== 'ALL' ? (
                <Button
                  size="small"
                  type="link"
                  onClick={() => {
                    setStatusFilter('ALL')
                    setPage(1)
                  }}
                >
                  清除状态筛选
                </Button>
              ) : null}
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              显示 {filteredTasks.length} / {shownTasks.length} 个任务
            </Typography.Text>
          </div>
          {loading && tasks.length === 0 ? (
            <LoadingState rows={5} />
          ) : filteredTasks.length === 0 ? (
            <EmptyState
              compact
              title={term || statusFilter !== 'ALL' ? '没有匹配的任务' : '还没有同步任务'}
              hint={term || statusFilter !== 'ALL'
                ? '换个关键字，或调整状态筛选'
                : '点击右上角「新建同步任务」，先选数据源'}
            />
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {pagedTasks.map((r) => {
                const active = r.lastStatus === 'PENDING' || r.lastStatus === 'RUNNING'
                const openDetail = (side: 'source' | 'sink') => {
                  setDetailTask(r)
                  setDetailSide(side)
                }
                // UX-DW10REST-9（REQ-DW-10 R2）：卡片操作菜单单一定义点——⋮「更多」点击与卡片右键并存
                // （同 Assets/Development 树口径）；动作闭包读本卡 r，不依赖先选中。
                const taskMenu = {
                  items: [
                    { key: 'status', label: '刷新状态' },
                    ...(can('ops:run') ? [{ key: 'backfill', label: '补数' }] : []),
                    ...(can('sync:write') ? [
                      { type: 'divider' as const },
                      { key: 'delete', label: '删除任务', danger: true },
                    ] : []),
                  ],
                  onClick: ({ key }: any) => {
                    if (key === 'status') refreshStatus(r)
                    if (key === 'backfill') openBackfill(r)
                    if (key === 'delete') {
                      Modal.confirm({
                        title: `删除同步任务「${r.code}」？`,
                        content: '删除后任务与调度一并移除，不可恢复。',
                        okText: '删除',
                        okButtonProps: { danger: true },
                        onOk: async () => {
                          try {
                            await del(`/api/integration/tasks/${r.id}`)
                            message.success('已删除')
                            load()
                          } catch (e: any) {
                            message.error(e.message || '删除失败')
                            throw e
                          }
                        },
                      })
                    }
                  },
                }
                return (
                  <Dropdown key={r.id} menu={taskMenu} trigger={['contextMenu']}>
                  <Card size="small" styles={{ body: { padding: '10px 12px' } }}>
                    {/* 第一行：状态 + 名称 + 操作 */}
                    <div style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      gap: 12, flexWrap: 'wrap',
                    }}>
                      <Space wrap size={8}>
                        <Tag color={STATUS_COLOR[(r.lastStatus || 'NEVER_RUN')] || 'default'}>
                          {STATUS_LABEL[(r.lastStatus || 'NEVER_RUN')] || r.lastStatus || '未运行'}
                        </Tag>
                        <Typography.Text strong style={{ fontSize: 14 }}>{r.name}</Typography.Text>
                        {r.code !== r.name ? <Typography.Text code>{r.code}</Typography.Text> : null}
                        <Tag color={r.taskType === 'REALTIME' ? 'purple' : 'default'}>
                          {r.taskType === 'REALTIME' ? '实时' : '离线'}
                        </Tag>
                        <Tag color={r.released ? 'green' : 'default'}>
                          {r.released ? '生产已发布' : '开发态'}
                        </Tag>
                        {r.scheduleEnabled && r.scheduleCron ? (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            cron {r.scheduleCron}
                          </Typography.Text>
                        ) : null}
                        {r.scheduleEnabled && r.nextRunAt && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            下次 {new Date(r.nextRunAt).toLocaleString()}
                          </Typography.Text>
                        )}
                        {r.dependsOnTaskId && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            依赖 #{r.dependsOnTaskId}
                          </Typography.Text>
                        )}
                      </Space>
                      <Space wrap size={4}>
                        {can('ops:run') && (
                          <Button
                            size="small"
                            icon={<PlayCircleOutlined />}
                            disabled={active}
                            loading={active}
                            onClick={() => run(r)}
                          >
                            {active ? '运行中' : '运行'}
                          </Button>
                        )}
                        {can('sync:run') && active && (
                          <Popconfirm
                            title={`停止任务「${r.code}」当前作业？`}
                            description="会删除 k8s Job，SeaTunnel 客户端退出后 Zeta 作业随之取消。"
                            okText="停止"
                            okButtonProps={{ danger: true }}
                            onConfirm={() => stopTask(r)}
                          >
                            <Button size="small" danger icon={<StopOutlined />}>停止</Button>
                          </Popconfirm>
                        )}
                        <Button size="small" onClick={() => showLogs(r)}>日志</Button>
                        <Button size="small" onClick={() => showHistory(r)}>运行实例</Button>
                        {can('sync:write') && (
                          <Tooltip title={r.released ? '已发布，请先下线再修改' : ''}>
                            <Button size="small" disabled={r.released}
                              onClick={() => openEdit(r).catch(() => undefined)}>编辑</Button>
                          </Tooltip>
                        )}
                        {can('sync:write') && (r.released ? (
                          <Popconfirm title={`下线任务「${r.code}」？`}
                            description="下线后周期调度停止，可继续修改；重新发布后恢复。"
                            okText="下线"
                            okButtonProps={{ danger: true }}
                            onConfirm={() => offlineTask(r)}>
                            <Button size="small">下线</Button>
                          </Popconfirm>
                        ) : (
                          <Popconfirm
                            title={`发布任务「${r.code}」？`}
                            description="发布后配置冻结，周期调度只跑已发布任务；发布前需试运行成功。"
                            okText="发布"
                            onConfirm={() => publishTask(r)}>
                            <Button size="small" type="primary" ghost>发布</Button>
                          </Popconfirm>
                        ))}
                        <Dropdown trigger={['click']} menu={taskMenu}>
                          <Button size="small" icon={<MoreOutlined />}>更多</Button>
                        </Dropdown>
                      </Space>
                    </div>
                    {/* 第二行：源 → 目标 + 调度信息 */}
                    <div style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                      gap: 12, flexWrap: 'wrap', marginTop: 8,
                    }}>
                      <Space wrap size={6}>
                        <Typography.Text type="secondary">源</Typography.Text>
                        <Typography.Link
                          onClick={() => openDetail('source')}
                          title={`${connLabel(r, 'source')}（点击查看详情）`}
                          style={{
                            display: 'inline-block', maxWidth: 320, verticalAlign: 'bottom',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }}
                        >
                          {connLabel(r, 'source')}
                        </Typography.Link>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {connSummary(r, 'source')}
                        </Typography.Text>
                        <Typography.Text type="secondary">→</Typography.Text>
                        <Typography.Text type="secondary">目标</Typography.Text>
                        <Typography.Link
                          onClick={() => openDetail('sink')}
                          title={`${connLabel(r, 'sink')}（点击查看详情）`}
                          style={{
                            display: 'inline-block', maxWidth: 320, verticalAlign: 'bottom',
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                          }}
                        >
                          {connLabel(r, 'sink')}
                        </Typography.Link>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {connSummary(r, 'sink')}
                        </Typography.Text>
                      </Space>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        最近运行 {r.lastRunAt ? new Date(r.lastRunAt).toLocaleString() : '-'}
                        {r.lastStatus ? (
                          <Typography.Link style={{ marginLeft: 6 }} onClick={() => showSummary(r)}>
                            结果摘要
                          </Typography.Link>
                        ) : null}
                      </Typography.Text>
                    </div>
                  </Card>
                  </Dropdown>
                )
              })}
            </div>
          )}
          {filteredTasks.length > PAGE_SIZE ? (
            <Pagination
              size="small"
              style={{ marginTop: 12, textAlign: 'right' }}
              current={currentPage}
              pageSize={PAGE_SIZE}
              total={filteredTasks.length}
              showSizeChanger={false}
              showTotal={(total) => `共 ${total} 条`}
              onChange={setPage}
            />
          ) : null}
          </>
        )}
      </Card>

      <Modal
        title={editing ? `编辑 ${editing.code}` : '新建同步任务'}
        open={open}
        onCancel={requestClose}
        width={760}
        destroyOnClose
        // 表单比视口高：只滚内容区，标题和保存按钮始终可见
        styles={{ body: { maxHeight: 'calc(100vh - 210px)', overflowY: 'auto', paddingRight: 10 } }}
        footer={[
          <Button key="cancel" onClick={requestClose} disabled={saving || validating}>取消</Button>,
          <Button key="check" onClick={validateConfigForm} loading={validating} disabled={saving}>
            {ACTION_TERMS.validate}
          </Button>,
          <Button key="save" type="primary" onClick={submit} loading={saving} disabled={validating}>
            保存
          </Button>,
        ]}
      >
        <Form
          form={form}
          layout="vertical"
          onValuesChange={() => {
            setFormDirty(true)
            // 任何改动都会让上一次的校验结果失效
            setConfigChecks([])
          }}
        >
          <div className="form-section-title">
            基本信息<span>任务标识与所属项目，编码创建后不可修改</span>
          </div>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="projectId" label="项目" rules={[{ required: true }]} style={{ width: 220 }}>
              <SearchableSelect
                options={projects.map((p) => ({ value: p.id, label: `${p.code} / ${p.name}` }))}
                disabled={!!editing}
                onChange={async (projectId) => {
                  await loadDatasources(projectId)
                  const project = projects.find((p) => p.id === projectId)
                  form.setFieldsValue({
                    sourceDatasourceId: undefined,
                    sinkDatasourceId: undefined,
                    sinkDatabase: project?.dev_database,
                  })
                }}
              />
            </Form.Item>
            <Form.Item name="code" label="编码" rules={[{ required: true, pattern: /^[A-Za-z_][A-Za-z0-9_]{0,63}$/, message: '字母开头，仅字母数字下划线' }]} style={{ width: 210 }}>
              <Input disabled={!!editing} placeholder="sync_demo" />
            </Form.Item>
            <Form.Item name="name" label="名称" rules={[{ required: true }]} style={{ width: 210 }}>
              <Input placeholder="示例同步" />
            </Form.Item>
          </Space>

          <div className="form-section-title">
            源<span>从哪里读取数据</span>
            <Switch
              className="form-section-switch"
              checked={manualSourceMode}
              onChange={(v) => {
                setManualSourceMode(v)
                setFormDirty(true)
                setConfigChecks([])
                if (v) form.setFieldValue('sourceDatasourceId', undefined)
              }}
              checkedChildren="手动配置"
              unCheckedChildren="用数据源"
            />
          </div>
          {!manualSourceMode && (
            <>
              <Form.Item
                name="sourceDatasourceId"
                label="源数据源"
                rules={manualSource ? [] : [{ required: true, message: '请选择源数据源' }]}
                extra={sourceDs ? (
                  <Space size={8}>
                    <Tag color="blue">{sourceConnector || sourceDs.ds_type}</Tag>
                    <Button size="small" type="link" style={{ padding: 0 }}
                      onClick={() => setSourcePreviewOpen(true)}>预览结构</Button>
                  </Space>
                ) : (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    没有可选数据源？先到 <a href="/datasources" target="_blank" rel="noreferrer">数据源</a> 新建
                  </Typography.Text>
                )}
                style={{ marginTop: 8 }}
              >
                <SearchableSelect
                  placeholder="选择数据源"
                  options={datasources.map((d) => ({
                    value: d.id,
                    label: `${d.name} · ${d.ds_type}`,
                  }))}
                  onChange={(value: number) => {
                    const ds = datasources.find((d) => d.id === value)
                    form.setFieldValue('sourceConfig', {})
                    if (ds?.ds_type === 'KAFKA') {
                      // Kafka 源天然是常驻流任务：默认按实时 + STREAMING 起
                      form.setFieldsValue({
                        sourceType: 'Kafka',
                        jobMode: 'STREAMING',
                        taskType: 'REALTIME',
                      })
                      if (!form.getFieldValue('sourceSchemaJson')) {
                        form.setFieldValue('sourceSchemaJson', KAFKA_SCHEMA_SAMPLE)
                      }
                      const group = ds.config?.['consumer.group']
                      if (group) {
                        form.setFieldValue(['sourceConfig', 'consumer.group'], String(group))
                      }
                    }
                  }}
                />
              </Form.Item>
              <SourceStructurePreview
                open={sourcePreviewOpen}
                datasourceId={sourceDs?.id}
                datasourceName={sourceDs?.name}
                onClose={() => setSourcePreviewOpen(false)}
              />
              {manualSource && (
                <Space style={{ width: '100%' }} size="middle" align="start">
                  <Form.Item name="sourceJdbcUrl" label="源 JDBC URL" style={{ width: 340 }}>
                    <Input placeholder="jdbc:postgresql://host:5432/db" />
                  </Form.Item>
                  <Form.Item name="sourceUser" label="源用户名" style={{ width: 160 }}>
                    <Input />
                  </Form.Item>
                  <Form.Item name="sourcePassword" label="源密码（留空不修改）" style={{ width: 190 }}>
                    <Input.Password />
                  </Form.Item>
                </Space>
              )}
            </>
          )}
          {manualSourceMode && (
            <>
              <Form.Item name="sourceType" label="源连接器" rules={[{ required: true, message: '请选择源连接器' }]}>
                <Select
                  showSearch
                  placeholder="选择 SeaTunnel Source 连接器"
                  optionFilterProp="label"
                  options={connectors.filter((c) => c.source).map((c) => ({
                    value: c.name,
                    label: `${c.name}（${c.status === 'READY' ? '可运行' : c.status === 'NEEDS_JAR' ? '需补 JAR' : '延后'}）`,
                    disabled: c.status !== 'READY',
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="sourceConfigJson"
                label="源配置（JSON）"
                rules={[jsonValidator]}
                extra={(
                  <div>
                    <div>字段名与 SeaTunnel 官方文档一致；未安装 JAR 的连接器不可保存。</div>
                    <div style={{ marginTop: 4 }}>多表 / 增量示例：</div>
                    <pre className="form-json-example">{`{
  "tables": [
    {"table": "db.t1", "incremental": {"column": "updated_at", "initial": "2026-01-01 00:00:00"}},
    {"table": "db.t2"}
  ],
  "mapping": {"include": ["id", "name"], "rename": {"name": "full_name"}}
}`}</pre>
                  </div>
                )}
              >
                <Input.TextArea rows={6} style={{ fontFamily: 'monospace' }} placeholder={'{\n  "bucket": "s3a://bucket",\n  "path": "/corpus"\n}'} />
              </Form.Item>
            </>
          )}
          {!manualSourceMode && (!sourceDs || sourceConnector === 'Jdbc' || manualSource) && (
            <>
              <Form.Item name="sourceQuery" label="查询 SQL" rules={[{ required: true }]}>
                <Input.TextArea rows={3} placeholder="select id, code, name from platform.workflow where updated_at >= '${start_time}'" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
              <Form.Item
                name="sourceParamsJson"
                label="运行参数声明（JSON 数组，可选）"
                extra={
                  <span>
                    {'在 SQL 中用 ${name} 引用；运行时按声明生成输入框，${bizdate}/${now} 等内置变量无需声明。'}
                    <Typography.Link onClick={() => setParamHelpOpen(true)} style={{ marginLeft: 8 }}>
                      查看说明
                    </Typography.Link>
                  </span>
                }
              >
                <Input.TextArea rows={3} style={{ fontFamily: 'monospace' }}
                  placeholder={'[\n  {"name": "start_time", "type": "datetime", "required": true}\n]'} />
              </Form.Item>
            </>
          )}
          {!manualSourceMode && sourceConnector === 'Kafka' && (
            <>
              <Form.Item name={['sourceConfig', 'topic']} label="Topic" rules={[{ required: true, message: '请填写 topic' }]}>
                <Input placeholder="demo_orders" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
              <Space style={{ width: '100%' }} size="middle" align="start">
                <Form.Item
                  name={['sourceConfig', 'consumer.group']}
                  label="消费组 consumer.group"
                  rules={[{ required: true, message: '请填写消费组' }]}
                  tooltip="位点与积压都按这个组统计；每个环境、每个任务用不同的组，别和别人共用"
                  style={{ width: 320 }}
                >
                  <Input placeholder="dw_dev_demo_orders" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
                <Form.Item name={['sourceConfig', 'start_mode']} label="起始位点" initialValue="earliest" style={{ width: 180 }}>
                  <Select options={[
                    { value: 'earliest', label: 'earliest（从头）' },
                    { value: 'latest', label: 'latest（只读新）' },
                    { value: 'group_offsets', label: 'group_offsets（按组位点）' },
                  ]} />
                </Form.Item>
                <Form.Item name={['sourceConfig', 'format']} label="消息格式" initialValue="json" style={{ width: 150 }}>
                  <Select options={['json', 'text', 'canal_json'].map((v) => ({ value: v, label: v }))} />
                </Form.Item>
              </Space>
              <Form.Item
                name="sourceSchemaJson"
                label="消息字段定义（SeaTunnel schema，JSON）"
                rules={[jsonValidator]}
                extra="按 topic 里的 JSON 字段填；类型写 string / int / bigint / double / boolean / timestamp"
              >
                <Input.TextArea rows={6} style={{ fontFamily: 'monospace' }} placeholder={KAFKA_SCHEMA_SAMPLE} />
              </Form.Item>
            </>
          )}
          {!manualSourceMode && sourceConnector === 'S3File' && (
            <>
              <Form.Item name={['sourceConfig', 'path']} label="源路径" rules={[{ required: true }]}>
                <Input placeholder="/corpus/t1/dataset/IMAGE" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
              <Space style={{ width: '100%' }} size="middle">
                <Form.Item name={['sourceConfig', 'fileFormat']} label="文件格式" initialValue="binary" style={{ width: 180 }}>
                  <Select options={['binary', 'json', 'csv', 'text'].map((v) => ({ value: v, label: v }))} />
                </Form.Item>
                <Form.Item name={['sourceConfig', 'assetTypes']} label="语料类型" style={{ width: 360 }}>
                  <Select mode="multiple" options={MULTIMODAL_TYPES.map((v) => ({ value: v, label: v }))} />
                </Form.Item>
              </Space>
              <Alert
                type="info"
                showIcon
                message="多模态路径与元数据"
                description={`路径约定：s3://bucket/basePath/tenant/dataset/assetType/yyyy/mm/dd/sha256.ext；字段：${MULTIMODAL_META}`}
                style={{ marginBottom: 12 }}
              />
              <Space style={{ width: '100%' }} size="middle" align="start">
                <Form.Item
                  name={['sourceConfig', 'file_incremental']}
                  label="文件级增量"
                  valuePropName="checked"
                  tooltip="开启后记录已处理对象，成功运行后复制到 <path>_done/yyyy-MM-dd/ 并删除原对象"
                >
                  <Switch checkedChildren="开" unCheckedChildren="关" />
                </Form.Item>
                <Form.Item
                  name={['sourceConfig', 'archive_path']}
                  label="归档前缀（可选）"
                  style={{ width: 420 }}
                  tooltip="默认 <path>_done/yyyy-MM-dd"
                >
                  <Input placeholder="/corpus_done" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Space>
            </>
          )}
          {!manualSourceMode && FILE_SOURCES.includes(sourceConnector) && (
            <>
              <Space style={{ width: '100%' }} size="middle" align="start">
                <Form.Item name={['sourceConfig', 'path']} label="源路径" rules={[{ required: true }]} style={{ width: 420 }}>
                  <Input placeholder="/upload/inbox" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
                <Form.Item
                  name={['sourceConfig', 'file_filter_pattern']}
                  label="文件名过滤（正则，可选）"
                  style={{ width: 280 }}
                >
                  <Input placeholder=".*\.csv$" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Space>
              <Space style={{ width: '100%' }} size="middle" align="start">
                <Form.Item
                  name={['sourceConfig', 'file_incremental']}
                  label="文件级增量"
                  valuePropName="checked"
                  tooltip="开启后平台记录已处理文件，成功运行后把文件移到归档目录；下次只读新增文件"
                >
                  <Switch checkedChildren="开" unCheckedChildren="关" />
                </Form.Item>
                <Form.Item
                  name={['sourceConfig', 'archive_path']}
                  label="归档目录（可选）"
                  style={{ width: 420 }}
                  tooltip="默认 <源路径>_done/yyyy-MM-dd，必须在源目录之外"
                >
                  <Input placeholder="/upload/done" style={{ fontFamily: 'monospace' }} />
                </Form.Item>
              </Space>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="文件级增量的语义"
                description="开启后：运行前登记目录里的文件；没有新文件时本次运行跳过（SKIPPED）；成功后已处理文件移到归档目录（默认 <源路径>_done/yyyy-MM-dd，不能放在源目录内——文件源会递归读取）。失败时文件留在原目录，下次会重读（追加写入的目标需自行去重）。"
              />
            </>
          )}
          {!manualSourceMode && sourceConnector === 'MongoDB' && (
            <Form.Item name={['sourceConfig', 'collection']} label="集合" rules={[{ required: true }]}>
              <Input placeholder="assets" />
            </Form.Item>
          )}
          {!manualSourceMode && sourceConnector === 'Redis' && (
            <Space style={{ width: '100%' }} size="middle">
              <Form.Item name={['sourceConfig', 'keys']} label="Keys / Pattern" rules={[{ required: true }]} style={{ width: 280 }}>
                <Input placeholder="corpus:*" />
              </Form.Item>
              <Form.Item name={['sourceConfig', 'dataType']} label="数据类型" initialValue="string" style={{ width: 180 }}>
                <Select options={['string', 'hash', 'list', 'set', 'zset'].map((v) => ({ value: v, label: v }))} />
              </Form.Item>
            </Space>
          )}
          {!manualSourceMode && sourceConnector === 'ClickHouse' && (
            <Form.Item name={['sourceConfig', 'table']} label="源表" rules={[{ required: true }]}>
              <Input placeholder="default.assets" />
            </Form.Item>
          )}
          {!manualSourceMode && sourceConnector === 'Doris' && (
            <Form.Item name={['sourceConfig', 'table']} label="源表" rules={[{ required: true }]}>
              <Input placeholder="db.table" />
            </Form.Item>
          )}

          <div className="form-section-title">
            目标<span>写入到哪里（Doris / ClickHouse / 文件 / Redis 等）</span>
            <Switch
              className="form-section-switch"
              checked={manualSinkMode}
              onChange={(v) => {
                setManualSinkMode(v)
                setFormDirty(true)
                setConfigChecks([])
                if (v) form.setFieldValue('sinkDatasourceId', undefined)
              }}
              checkedChildren="手动配置"
              unCheckedChildren="用数据源"
            />
          </div>
          {!manualSinkMode && (
            <>
              <Form.Item
                name="sinkDatasourceId"
                label="目标数据源"
                rules={manualSink ? [] : [{ required: true, message: '请选择目标数据源' }]}
                extra={sinkDs ? <Tag color="geekblue">{sinkConnector || sinkDs.ds_type}</Tag> : undefined}
                style={{ marginTop: 8 }}
              >
                <SearchableSelect
                  placeholder="选择数据源"
                  options={datasources.map((d) => ({ value: d.id, label: `${d.name} · ${d.ds_type}` }))}
                  onChange={(id) => {
                    const ds = datasources.find((d) => d.id === id)
                    form.setFieldValue('sinkConfig', {})
                    if (ds) {
                      form.setFieldValue('sinkDatabase', databaseFromJdbc(ds.jdbc_url || ''))
                    }
                  }}
                />
              </Form.Item>
              {manualSink && (
                <Space style={{ width: '100%' }} size="middle" align="start">
                  <Form.Item name="sinkFenodes" label="Doris FE" style={{ width: 220 }}>
                    <Input placeholder="host:8030" />
                  </Form.Item>
                  <Form.Item name="sinkUser" label="目标用户名" style={{ width: 160 }}>
                    <Input />
                  </Form.Item>
                  <Form.Item name="sinkPassword" label="目标密码（留空不修改）" style={{ width: 190 }}>
                    <Input.Password />
                  </Form.Item>
                </Space>
              )}
            </>
          )}
          {manualSinkMode && (
            <>
              <Form.Item name="sinkType" label="目标连接器" rules={[{ required: true, message: '请选择目标连接器' }]}>
                <Select
                  showSearch
                  placeholder="选择 SeaTunnel Sink 连接器"
                  optionFilterProp="label"
                  options={connectors.filter((c) => c.sink).map((c) => ({
                    value: c.name,
                    label: `${c.name}（${c.status === 'READY' ? '可运行' : c.status === 'NEEDS_JAR' ? '需补 JAR' : '延后'}）`,
                    disabled: c.status !== 'READY',
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="sinkConfigJson"
                label="目标配置（JSON）"
                rules={[jsonValidator]}
                extra={(
                  <div>
                    <div>字段名与 SeaTunnel 官方文档一致；未安装 JAR 的连接器不可保存。多表时 tables 与源表一一对应。</div>
                    <pre className="form-json-example">{`{
  "tables": [{"table": "db.t1_dst"}, {"table": "db.t2_dst"}],
  "primary_keys": ["id"]
}`}</pre>
                  </div>
                )}
              >
                <Input.TextArea rows={6} style={{ fontFamily: 'monospace' }} placeholder={'{\n  "host": "redis",\n  "port": 6379\n}'} />
              </Form.Item>
            </>
          )}
          {!manualSinkMode && sinkConnector === 'S3File' && (
            <>
              <Form.Item name={['sinkConfig', 'path']} label="目标路径" rules={[{ required: true }]}>
                <Input placeholder="/corpus-target/t1/dataset/IMAGE" style={{ fontFamily: 'monospace' }} />
              </Form.Item>
              <Form.Item name={['sinkConfig', 'fileFormat']} label="文件格式" initialValue="binary" style={{ width: 180 }}>
                <Select options={['binary', 'json', 'csv', 'text'].map((v) => ({ value: v, label: v }))} />
              </Form.Item>
            </>
          )}
          {!manualSinkMode && sinkConnector === 'LocalFile' && (
            <Form.Item name={['sinkConfig', 'path']} label="目标路径" rules={[{ required: true }]}>
              <Input placeholder="/data/corpus-out" />
            </Form.Item>
          )}
          {!manualSinkMode && sinkConnector === 'MongoDB' && (
            <Form.Item name={['sinkConfig', 'collection']} label="集合" rules={[{ required: true }]}>
              <Input placeholder="assets" />
            </Form.Item>
          )}
          {!manualSinkMode && sinkConnector === 'Redis' && (
            <Space style={{ width: '100%' }} size="middle" align="start">
              <Form.Item label="写入键">
                <Typography.Text type="secondary">
                  取上游数据里的 key 字段，value 字段作为值
                </Typography.Text>
              </Form.Item>
              <Form.Item name={['sinkConfig', 'dataType']} label="数据类型" initialValue="string" style={{ width: 180 }}>
                <Select options={['string', 'hash', 'list', 'set', 'zset'].map((v) => ({ value: v, label: v }))} />
              </Form.Item>
            </Space>
          )}
          {!manualSinkMode && sinkConnector === 'ClickHouse' && (
            <Form.Item name={['sinkConfig', 'table']} label="目标表" rules={[{ required: true }]}>
              <Input placeholder="default.assets" />
            </Form.Item>
          )}
          {!manualSinkMode && (sinkConnector === 'Doris' || sinkConnector === 'Jdbc' || manualSink) && (
            <Space style={{ width: '100%' }} size="middle">
              <Form.Item name="sinkDatabase" label="目标库" rules={[{ required: true }]} style={{ width: 180 }}>
                <Input placeholder={formProject?.dev_database || 'gongxiang_dev'} />
              </Form.Item>
              <Form.Item
                name="sinkTable"
                label="目标表"
                rules={[{ required: true }]}
                style={{ width: 180 }}
                tooltip={formProject?.table_prefix
                  ? `项目默认表名前缀：${formProject.table_prefix}` : undefined}
              >
                <Input placeholder={formProject?.table_prefix
                  ? `${formProject.table_prefix}orders` : 'gx_orders'} />
              </Form.Item>
            </Space>
          )}
          <div className="form-section-title">
            运行与调度<span>一次性任务用「试运行」跑一次；周期任务按周期自动触发</span>
          </div>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="jobMode" label="作业模式" initialValue="BATCH" style={{ width: 160 }}>
              <Select options={[{ value: 'BATCH', label: 'BATCH' }, { value: 'STREAMING', label: 'STREAMING' }]} />
            </Form.Item>
            {/* REQ-DW-14：初值与后端缺省同源（SyncTaskController.parallelism 缺省 1） */}
            <Form.Item name="parallelism" label="并行度" initialValue={1} style={{ width: 120 }}>
              <InputNumber min={1} max={8} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="taskType" label="任务类型" initialValue="OFFLINE" style={{ width: 150 }}>
              <Select options={[
                { value: 'OFFLINE', label: '离线任务' },
                { value: 'REALTIME', label: '实时任务' },
              ]} />
            </Form.Item>
            <Form.Item name="dependsOnTaskId" label="上游依赖" style={{ width: 260 }}
              tooltip="上游最近一次运行成功后才触发本任务（只影响调度触发）">
              <Select allowClear placeholder="无"
                options={tasks
                  .filter((t) => t.id !== editing?.id && (t.taskType || 'OFFLINE') !== 'REALTIME')
                  .map((t) => ({ value: t.id, label: `${t.code} · ${t.name}` }))} />
            </Form.Item>
          </Space>
          {/* REQ-DW-17：按调度类型差异化配置区。开=周期字段；关=隐藏周期字段，出现「试运行」 */}
          <Space style={{ width: '100%' }} size="middle" align="start">
            <Form.Item name="scheduleEnabled" label="调度类型" valuePropName="checked" initialValue={false}>
              {/* REQ-DW-14：切回周期且还没填周期时，预填「每天 02:00」常用模板 */}
              <Switch checkedChildren="周期" unCheckedChildren="一次性" onChange={(checked) => {
                if (checked && !form.getFieldValue('scheduleCron')) {
                  form.setFieldValue('scheduleCron', '0 2 * * *')
                }
              }} />
            </Form.Item>
            {scheduleOn ? (
              <>
                <Form.Item name="scheduleCron" label="采集周期" style={{ width: 260 }}
                  tooltip="5 段：分 时 日 月 周（如 0 2 * * * 表示每天 02:00）；也可填 6 段带秒的表达式">
                  <Input placeholder="0 2 * * *" allowClear />
                </Form.Item>
                <Form.Item label="快速选择" style={{ width: 180 }}>
                  <Select placeholder="常用周期" allowClear
                    options={[
                      { value: '0 * * * *', label: '每小时' },
                      { value: '0 */6 * * *', label: '每 6 小时' },
                      { value: '0 2 * * *', label: '每天 02:00' },
                      { value: '0 2 * * 1', label: '每周一 02:00' },
                      { value: '0 2 1 * *', label: '每月 1 号 02:00' },
                    ]}
                    onChange={(v?: string) => { if (v) form.setFieldValue('scheduleCron', v) }} />
                </Form.Item>
              </>
            ) : (
              <div style={{ paddingTop: 2 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', maxWidth: 420 }}>
                  一次性/手动调度：不配置周期。保存后点「试运行」跑一次，完成后展示结果摘要
                </Typography.Text>
                <Space size={8} style={{ marginTop: 6 }} align="center">
                  {editing && can('sync:run') ? (
                    <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />}
                      disabled={formDirty} loading={runningNow}
                      onClick={() => { runNowFromForm().catch(() => undefined) }}>
                      {ACTION_TERMS.trialRun}
                    </Button>
                  ) : (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {editing ? '当前账号没有执行权限（sync:run），仅可查看配置'
                        : '新建任务保存后即可试运行'}
                    </Typography.Text>
                  )}
                  {editing && formDirty ? (
                    <Typography.Text type="warning" style={{ fontSize: 12 }}>
                      有未保存改动，先保存再执行
                    </Typography.Text>
                  ) : null}
                </Space>
              </div>
            )}
          </Space>
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item name="retryTimes" label="失败重试次数" initialValue={0} style={{ width: 160 }}
              tooltip="作业 FAILED/TIMEOUT 后立即重跑几次，0 表示不重试；新的周期触发时计数归零">
              <InputNumber min={0} max={10} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="timeoutMinutes" label="超时（分钟）" initialValue={60} style={{ width: 160 }}
              tooltip="超过该时长仍未结束，调度器会删除 Job 并标记 TIMEOUT">
              <InputNumber min={1} max={1440} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
        </Form>
        {configChecks.length > 0 && (
          <div className="config-checks" ref={checksRef}>
            <div className="config-checks-head">
              <Typography.Text strong>校验结果</Typography.Text>
              <Tag color={configChecks.some((c) => !c.ok) ? 'red' : 'green'}>
                通过 {configChecks.filter((c) => c.ok).length}/{configChecks.length}
              </Tag>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                改动表单后需重新校验
              </Typography.Text>
            </div>
            {configChecks.map((c, i) => (
              <div key={i} className="config-check-row">
                <Tag color={c.ok ? 'green' : 'red'}>{c.ok ? '通过' : '失败'}</Tag>
                <Typography.Text strong>{c.name}</Typography.Text>
                <Typography.Text type="secondary">：{c.detail}</Typography.Text>
              </div>
            ))}
          </div>
        )}
      </Modal>

      <Modal
        title={`运行：${runTask?.name || ''}`}
        open={!!runTask}
        onCancel={() => setRunTask(undefined)}
        width={620}
        footer={[
          <Button key="cancel" onClick={() => setRunTask(undefined)}>取消</Button>,
          <Button key="check" onClick={validateRun}>{ACTION_TERMS.validate}</Button>,
          <Button key="preview" onClick={previewSqlNow}>预览 SQL</Button>,
          <Button key="run" type="primary" loading={running} disabled={!validateOk}
            onClick={() => runTask && runForm.validateFields()
              .then((values) => submitRun(runTask, values))
              .catch(() => undefined)}>
            提交运行
          </Button>,
        ]}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="参数会按类型渲染进 SQL；先点「校验配置」，SQL、源库连接、目标表都通过后才允许提交。"
        />
        <Form form={runForm} layout="vertical"
          onValuesChange={() => { setValidateOk(false); setChecks([]); setPreviewSql('') }}>
          {paramsOf(runTask).map((p) => {
            const numeric = ['int', 'integer', 'long', 'decimal', 'number', 'double', 'float']
              .includes(String(p.type || 'string'))
            const bool = p.type === 'bool' || p.type === 'boolean'
            return (
              <Form.Item
                key={p.name}
                name={p.name}
                label={`${p.name}（${p.type || 'string'}）${p.required ? ' *' : ''}`}
                rules={p.required ? [{ required: true, message: '必填' }] : []}
              >
                {numeric ? <InputNumber style={{ width: '100%' }} />
                  : bool ? <Switch />
                    : <Input placeholder={p.description || ''} />}
              </Form.Item>
            )
          })}
        </Form>
        {checks.length > 0 && (
          <div style={{ marginBottom: 8 }}>
            {checks.map((c, i) => (
              <div key={i} style={{ fontSize: 12, marginBottom: 4 }}>
                <Tag color={c.ok ? 'green' : 'red'}>{c.ok ? '通过' : '失败'}</Tag>
                <Typography.Text strong>{c.name}</Typography.Text>
                <Typography.Text type="secondary">：{c.detail}</Typography.Text>
              </div>
            ))}
          </div>
        )}
        {previewSql && <pre className="code-block" style={{ marginTop: 8 }}>{previewSql}</pre>}
      </Modal>

      <Modal
        title="运行参数声明说明"
        open={paramHelpOpen}
        onCancel={() => setParamHelpOpen(false)}
        footer={null}
        width={720}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="声明后，点「运行」会按声明逐项生成输入框；值按类型校验后替换 SQL 里的 ${name}。"
        />
        <Typography.Title level={5} style={{ marginTop: 12 }}>1. 声明</Typography.Title>
        <pre className="code-block">{PARAM_HELP_DECL}</pre>
        <Typography.Title level={5} style={{ marginTop: 12 }}>2. SQL 引用</Typography.Title>
        <pre className="code-block">{PARAM_HELP_SQL}</pre>
        <Typography.Title level={5} style={{ marginTop: 12 }}>3. 字段</Typography.Title>
        <ul style={{ margin: 0, paddingLeft: 20, lineHeight: 1.9 }}>
          <li><Typography.Text code>name</Typography.Text>：必填，字母/数字/下划线，不能带点</li>
          <li><Typography.Text code>type</Typography.Text>：string（默认）、int/long/number、bool、date、datetime、ident（动态表名）</li>
          <li><Typography.Text code>required</Typography.Text>：为 true 时值为空直接报错</li>
          <li><Typography.Text code>default</Typography.Text>：默认值，可引用内置变量，如 {'"${bizdatetime}"'}</li>
          <li><Typography.Text code>description</Typography.Text>：运行时输入框的占位提示</li>
        </ul>
        <Typography.Title level={5} style={{ marginTop: 12 }}>4. 内置变量（无需声明）</Typography.Title>
        <Typography.Paragraph style={{ marginBottom: 8 }}>
          <Typography.Text code>{'${bizdate}'}</Typography.Text>（T-1，yyyyMMdd）、
          <Typography.Text code>{'${bizdatetime}'}</Typography.Text>、
          <Typography.Text code>{'${now}'}</Typography.Text>、
          <Typography.Text code>{'${hour}'}</Typography.Text>、
          <Typography.Text code>{'${timestamp}'}</Typography.Text>、
          <Typography.Text code>{'${task_id}'}</Typography.Text>、
          <Typography.Text code>{'${project_id}'}</Typography.Text>、
          <Typography.Text code>{'${env}'}</Typography.Text>
        </Typography.Paragraph>
        <Typography.Paragraph>
          支持偏移：<Typography.Text code>{'${bizdate-1d}'}</Typography.Text>、
          <Typography.Text code>{'${now-2h}'}</Typography.Text>，单位 d/h/m/M。
        </Typography.Paragraph>
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 8 }}
          message="动态表名必须用 ident"
          description="用 string 会被当成字符串加引号，导致 SQL 语法错误。"
        />
        <Typography.Text type="secondary">完整说明见仓库 docs/数据集成-运行参数声明.md</Typography.Text>
      </Modal>

      <Modal
        title={`补数 · ${backfillTask?.code || ''}`}
        open={backfillOpen}
        onCancel={() => setBackfillOpen(false)}
        onOk={submitBackfill}
        confirmLoading={backfillBusy}
        okText="提交补数"
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="按业务日期逐天重跑"
          description="每天提交一次运行，运行参数只传 bizdate（SQL 里用 ${bizdate} 引用）。一次最多 10 天，日期格式 yyyy-MM-dd。"
        />
        <Space style={{ width: '100%' }} size="middle">
          <div>
            <Typography.Text type="secondary">开始日期</Typography.Text>
            <Input placeholder="2026-10-01" value={backfillStart}
              onChange={(e) => setBackfillStart(e.target.value)} />
          </div>
          <div>
            <Typography.Text type="secondary">结束日期</Typography.Text>
            <Input placeholder="2026-10-01" value={backfillEnd}
              onChange={(e) => setBackfillEnd(e.target.value)} />
          </div>
        </Space>
      </Modal>

      <Drawer
        title={`运行实例 · ${historyTask?.code || ''}`}
        placement="right"
        width={900}
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
      >
        <Table
          size="small"
          components={{ body: { row: MemoTableRow } }}
          rowKey={(r: any) => String(r.id)}
          dataSource={historyRows}
          pagination={{ pageSize: 10 }}
          locale={{ emptyText: tableEmpty('还没有运行记录',
            '周期任务发布后按调度自动运行，触发后这里会记录参数与结果；一次性任务可点「试运行」先跑一次') }}
          onRow={(r: any) => ({ onClick: () => openRun(r.id), style: { cursor: 'pointer' } })}
          columns={[
            { title: '时间', dataIndex: 'createdAt', width: 165,
              render: (v?: string) => (v ? new Date(v).toLocaleString() : '-') },
            { title: '类型', dataIndex: 'runType', width: 90,
              render: (v?: string) => <Tag>{v || 'MANUAL'}</Tag> },
            { title: '状态', dataIndex: 'status', width: 95,
              render: (v?: string) => (
                <Tag color={v === 'SUCCESS' ? 'green' : v === 'FAILED' ? 'red' : v === 'SKIPPED' ? 'blue' : 'default'}>
                  {v || 'PENDING'}
                </Tag>
              ) },
            { title: '读取/写入', key: 'rows', width: 120,
              render: (_: unknown, r: any) => `${r.rowsRead ?? '-'} / ${r.rowsWritten ?? '-'}` },
            { title: '耗时', dataIndex: 'durationMs', width: 90,
              render: (v?: number) => (v ? `${(v / 1000).toFixed(1)}s` : '-') },
            { title: '校验', dataIndex: 'verifyResult', width: 95,
              render: (v?: string) => (v
                ? <Tag color={v === 'PASSED' ? 'green' : v === 'FAILED' ? 'red' : 'default'}>{v}</Tag>
                : '-') },
          ]}
        />

        {runDetail && (
          <Card
            size="small"
            style={{ marginTop: 12 }}
            title={`运行 #${runDetail.id} · ${runDetail.jobName}`}
            extra={(
              <Space>
                {can('ops:run') && (
                  <Button size="small" type="primary" onClick={() => rerun(runDetail)}>重跑</Button>
                )}
                <Button size="small" onClick={() => showRunLogs(runDetail.id)}>日志</Button>
                <Button size="small" type="link"
                  onClick={() => {
                    setRunDetail(undefined); setRunEvents([]); setRunMetricSamples([])
                  }}>收起</Button>
              </Space>
            )}
          >
            {runDetail.status === 'PENDING' && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="正在提交到集群（通常 10–30 秒）"
                description="提交完成后会自动进入运行中；不用重复点「运行」。"
              />
            )}
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="状态">
                <Tag color={runDetail.status === 'SUCCESS' ? 'green'
                  : runDetail.status === 'FAILED' ? 'red'
                    : runDetail.status === 'PENDING' ? 'orange' : 'default'}>
                  {runDetail.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="触发方式">{runDetail.runType}</Descriptions.Item>
              <Descriptions.Item label="读取 / 写入">
                {`${runDetail.rowsRead ?? '-'} / ${runDetail.rowsWritten ?? '-'}`}
              </Descriptions.Item>
              <Descriptions.Item label="耗时">
                {runDetail.durationMs ? `${(runDetail.durationMs / 1000).toFixed(1)} 秒` : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="校验">
                {runDetail.verifyMode} / {runDetail.verifyResult || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="开始时间">
                {runDetail.startedAt ? new Date(runDetail.startedAt).toLocaleString() : '-'}
              </Descriptions.Item>
              {runDetail.failureReason && (
                <Descriptions.Item label="失败原因" span={2}>
                  <Typography.Text type="danger">{runDetail.failureReason}</Typography.Text>
                </Descriptions.Item>
              )}
            </Descriptions>
            {runMetricSamples.length > 0 && (
              <Card size="small" type="inner" title="实时采样（最近 3 条）"
                style={{ marginTop: 12 }}>
                <Space direction="vertical" size={2}>
                  {runMetricSamples.slice(0, 3).map((m: any) => (
                    <Typography.Text key={m.id} style={{ fontSize: 12 }}>
                      {new Date(m.tsMs || m.ts).toLocaleTimeString()} · {JSON.stringify(m.metrics)}
                    </Typography.Text>
                  ))}
                  {runMetricSamples[0]?.metrics?.checkpointSupported === false && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      checkpoint 指标当前引擎未暴露（仅有 JVM 指标），采样里明确标注 unsupported
                    </Typography.Text>
                  )}
                </Space>
              </Card>
            )}
            <Timeline
              style={{ marginTop: 16 }}
              items={runEvents.map((e: any) => ({
                color: e.type === 'ERROR' ? 'red' : e.phase === 'done' ? 'green' : 'blue',
                children: (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{e.phase} · {e.message}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {new Date(e.tsMs || e.ts).toLocaleString()}
                      {e.metrics ? ` · ${JSON.stringify(e.metrics)}` : ''}
                    </Typography.Text>
                  </Space>
                ),
              }))}
            />
            {!runEvents.length && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                暂无事件（历史运行或在本次升级前触发）
              </Typography.Text>
            )}
          </Card>
        )}
      </Drawer>

      <Modal
        title={`运行日志 · #${runDetail?.id || ''}`}
        open={runLogOpen}
        onCancel={() => setRunLogOpen(false)}
        footer={<Button onClick={() => setRunLogOpen(false)}>关闭</Button>}
        width={900}
      >
        <Typography.Paragraph copyable={{ text: runLogText }} />
        <pre className="code-block log-block">{runLogText || '暂无日志'}</pre>
      </Modal>

      <Drawer
        title={detailTask ? `连接详情 · ${detailTask.code}` : '连接详情'}
        placement="right"
        width={620}
        open={Boolean(detailTask)}
        onClose={() => setDetailTask(undefined)}
      >
        {detailTask && (detailSide === 'source' ? ['source', 'sink'] as const : ['sink', 'source'] as const)
          .map((side) => {
            const detail = connDetail(detailTask, side)
            const current = side === detailSide
            return (
              <Card
                key={side}
                size="small"
                style={{ marginBottom: 12 }}
                title={side === 'source' ? '源连接' : '目标连接'}
                extra={current ? <Tag color="blue">当前查看</Tag> : undefined}
              >
                <Descriptions size="small" column={1} bordered>
                  <Descriptions.Item label="绑定方式">
                    {detail.dsName ? (
                      <Space wrap>
                        <Tag color="green">数据源</Tag>
                        <span>{detail.dsName}</span>
                        <a href="/datasources" target="_blank" rel="noreferrer">数据源管理</a>
                      </Space>
                    ) : <Tag>手动连接</Tag>}
                  </Descriptions.Item>
                  <Descriptions.Item label="连接器">{detail.type}</Descriptions.Item>
                  <Descriptions.Item label="连接地址">{detail.address || '-'}</Descriptions.Item>
                  <Descriptions.Item label="库 / 表">
                    {[detail.database, detail.table].filter(Boolean).join(' / ') || '-'}
                  </Descriptions.Item>
                  <Descriptions.Item label="用户名">{detail.user || '-'}</Descriptions.Item>
                  {detail.query && (
                    <Descriptions.Item label="SQL / 查询">
                      <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto' }}>
                        {detail.query}
                      </pre>
                    </Descriptions.Item>
                  )}
                </Descriptions>
                {Object.keys(detail.rest).length > 0 && (
                  <div style={{ marginTop: 8 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>其他配置</Typography.Text>
                    <pre className="code-block" style={{ marginTop: 4, maxHeight: 200, overflow: 'auto' }}>
                      {JSON.stringify(detail.rest, null, 2)}
                    </pre>
                  </div>
                )}
              </Card>
            )
          })}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          密码等敏感字段不展示；绑定数据源的任务在运行时才读取完整连接信息。
        </Typography.Text>
      </Drawer>

      <Modal
        title={`同步结果 · ${summary?.taskName || summary?.taskCode || ''}`}
        open={summaryOpen}
        onCancel={() => { stopSummaryPoll(); setSummaryOpen(false) }}
        width={640}
        footer={
          <Space>
            {summary?.runId ? (
              <Button onClick={() => showRunLogs(Number(summary.runId))}>查看运行日志</Button>
            ) : null}
            <Button type="primary"
              onClick={() => { stopSummaryPoll(); setSummaryOpen(false) }}>关闭</Button>
          </Space>
        }
      >
        {summary
          ? <SummaryBody s={summary} waited={summaryWaited} />
          : <LoadingState />}
      </Modal>

      <Drawer title="作业日志" placement="right" width={900} open={logsOpen} onClose={() => setLogsOpen(false)}>
        {logs ? (
          <>
            <Typography.Paragraph copyable={{ text: logs }} />
            <pre className="code-block log-block">{logs}</pre>
          </>
        ) : (
          <EmptyState compact title="暂无日志" hint="作业刚提交时日志可能还没产出，稍后重试" />
        )}
      </Drawer>
    </div>
  )
}
