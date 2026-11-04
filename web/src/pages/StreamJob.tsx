import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Col, Descriptions, Form, Input, Modal, Radio, Row, Select,
  Space, Table, Tag, Typography, message,
} from 'antd'
import {
  CheckCircleOutlined, ExperimentOutlined, FileTextOutlined, HistoryOutlined,
  LeftOutlined, NodeIndexOutlined, PlayCircleOutlined, RocketOutlined,
  SettingOutlined, StopOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import ReactFlow, { Background, Controls, MarkerType } from 'reactflow'
import 'reactflow/dist/style.css'
import { api, post, put } from '../api'
import { tableEmpty } from '../components/StateBlocks'
import PageHeader from '../components/PageHeader'
import PageActions from '../components/PageActions'
import CodeEditor from '../components/CodeEditor'
import StreamTree from '../components/StreamTree'
import KafkaSqlHelp from '../components/KafkaSqlHelp'
import { canAny } from '../permissions'

type Job = {
  id: number
  project_id: number
  project_name?: string
  project_code?: string
  code: string
  name: string
  description?: string
  folder?: string
  sql_text?: string
  status: 'DRAFT' | 'SUBMITTING' | 'STARTING' | 'RUNNING' | 'RESTARTING' | 'STOPPING' | 'STOPPED' | 'FAILED'
  env?: 'DEV' | 'PROD'
  smoke_at?: string
  /** 冒烟/校验是否对「当前这版 SQL」有效（后端按指纹算，改名字不会失效）。 */
  smoke_ok?: boolean
  validate_at?: string
  validate_ok?: boolean
  published_version?: number
  published_at?: string
  prod_status?: string
  prod_job_id?: string
  flink_job_id?: string
  session_mode?: 'ISOLATED' | 'SHARED'
  parallelism?: number
  consumer_group?: string
  last_error?: string
  submitted_at?: string
  updated_at?: string
}

type ProjectOption = { id: number; code: string; name: string }
type DataSource = { id: number; name: string; ds_type: string; config?: Record<string, any> }

const TEMPLATE = `-- datagen → print：不依赖外部系统，可直接提交验证流任务链路
CREATE TABLE source_table (
  id INT,
  name STRING
) WITH (
  'connector' = 'datagen',
  'rows-per-second' = '1'
);

CREATE TABLE sink_table (
  id INT,
  name STRING
) WITH (
  'connector' = 'print'
);

INSERT INTO sink_table
SELECT id, name FROM source_table;`

const STATUS_META: Record<string, { color: string; text: string }> = {
  DRAFT: { color: 'default', text: '草稿' },
  SUBMITTING: { color: 'processing', text: '提交中' },
  STARTING: { color: 'processing', text: '启动中' },
  RUNNING: { color: 'processing', text: '运行中' },
  RESTARTING: { color: 'warning', text: '重启中' },
  STOPPING: { color: 'processing', text: '停止中' },
  STOPPED: { color: 'default', text: '已停止' },
  FAILED: { color: 'red', text: '失败' },
}

const ACTIVE_STATES = ['SUBMITTING', 'STARTING', 'RUNNING', 'RESTARTING', 'STOPPING']
// v467 G-P1-1：线上作业在启动/重启/停止过程中也要能点「停止线上」，
// 之前只有 PROD=RUNNING 才显示入口，一进 RESTARTING 就再也停不下来。
const PROD_ACTIVE_STATES = ['SUBMITTING', 'STARTING', 'RUNNING', 'RESTARTING', 'STOPPING']

function fmtTime(value?: string | number) {
  if (value == null || value === '') return '-'
  if (typeof value === 'number') {
    return new Date(value).toLocaleString()
  }
  return value.replace('T', ' ').replace(/\.\d+.*$/, '').replace('Z', '')
}

function fmtDuration(ms?: number) {
  if (!ms || ms < 0) return '-'
  const seconds = Math.floor(ms / 1000)
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = seconds % 60
  if (h > 0) return `${h}小时 ${m}分`
  if (m > 0) return `${m}分 ${s}秒`
  return `${s}秒`
}

function buildGraph(graph: any) {
  const nodes = graph?.nodes || []
  const edges = graph?.edges || []
  const incoming = new Map<string, number>()
  nodes.forEach((n: any) => incoming.set(n.id, 0))
  edges.forEach((e: any) => incoming.set(e.target, (incoming.get(e.target) || 0) + 1))
  const levels = new Map<string, number>()
  const queue: string[] = nodes.filter((n: any) => (incoming.get(n.id) || 0) === 0)
    .map((n: any) => n.id)
  queue.forEach((id) => levels.set(id, 0))
  // 简单 BFS：无入边的算子为第 0 层
  const outgoing = new Map<string, string[]>()
  edges.forEach((e: any) => {
    const list = outgoing.get(e.source) || []
    list.push(e.target)
    outgoing.set(e.source, list)
  })
  const levelQueue = [...queue]
  while (levelQueue.length) {
    const id = levelQueue.shift()!
    const level = levels.get(id) || 0
    ;(outgoing.get(id) || []).forEach((next) => {
      const candidate = level + 1
      if (!levels.has(next) || candidate > (levels.get(next) || 0)) {
        levels.set(next, candidate)
      }
      levelQueue.push(next)
    })
  }
  const perLevel = new Map<number, number>()
  const rfNodes = nodes.map((n: any) => {
    const level = levels.get(n.id) ?? 0
    const index = perLevel.get(level) || 0
    perLevel.set(level, index + 1)
    const title = n.name || n.contents || n.type || n.id
    const metrics = `${n.status || ''}${n.recordsIn != null ? ` · 入 ${n.recordsIn}` : ''}`
      + `${n.recordsOut != null ? ` · 出 ${n.recordsOut}` : ''}`
    return {
      id: n.id,
      position: { x: level * 260, y: index * 130 },
      data: {
        label: (
          <div style={{ textAlign: 'left' }}>
            <div style={{ fontWeight: 600, fontSize: 12, whiteSpace: 'normal',
              overflowWrap: 'anywhere' }}>{title}</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>{metrics}</div>
          </div>
        ),
      },
      style: { width: 220, border: '1px solid #94a3b8', borderRadius: 6, padding: 6 },
    }
  })
  const rfEdges = edges.map((e: any, i: number) => ({
    id: `e${i}`,
    source: e.source,
    target: e.target,
    markerEnd: { type: MarkerType.ArrowClosed },
    style: { stroke: '#94a3b8' },
  }))
  return { nodes: rfNodes, edges: rfEdges }
}

/** 从脚本里的 CREATE TABLE 解析补全用的表/字段（FlinkSQL 没有可查的元数据接口）。 */
function parseFlinkTables(sql: string) {
  const out: { database: string; table: string; columns: { name: string; type?: string }[] }[] = []
  const pattern = /create\s+table\s+(?:if\s+not\s+exists\s+)?([`"\w.]+)\s*\(([\s\S]*?)\)\s*with/gi
  const nl = String.fromCharCode(10)
  let match: RegExpExecArray | null
  while ((match = pattern.exec(sql)) !== null) {
    const table = match[1].replace(/[`"]/g, '').split('.').pop() || ''
    const columns: { name: string; type?: string }[] = []
    match[2].split(nl).forEach((line) => {
      const trimmed = line.trim().replace(/,$/, '')
      if (!trimmed) return
      const lower = trimmed.toLowerCase()
      if (lower.startsWith('primary') || lower.startsWith('watermark')
          || lower.startsWith('constraint')) return
      const col = trimmed.match(/^[`"]?([A-Za-z_][A-Za-z0-9_]*)[`"]?\s+([A-Za-z0-9_()]+)/)
      if (col) columns.push({ name: col[1], type: col[2] })
    })
    if (table) out.push({ database: '', table, columns })
  }
  return out
}

export default function StreamJob() {
  const { id } = useParams()
  const nav = useNavigate()
  const canWrite = canAny('flink:write')
  const canRun = canAny('flink:run')
  const canPublish = canAny('flink:publish')
  const [job, setJob] = useState<Job | null>(null)
  const [projects, setProjects] = useState<ProjectOption[]>([])
  const [sources, setSources] = useState<DataSource[]>([])
  const [sql, setSql] = useState('')
  const [saving, setSaving] = useState(false)
  const [busy, setBusy] = useState('')
  const [error, setError] = useState('')
  const [ok, setOk] = useState('')
  const [metrics, setMetrics] = useState<any>(null)
  // v467 G-P1-3/4/5：远端状态单独存，轮询里同步更新；不再依赖手动刷新才可见。
  const [remoteState, setRemoteState] = useState<string | null>(null)
  const [prodRemoteState, setProdRemoteState] = useState<string | null>(null)
  const [graph, setGraph] = useState<any>(null)
  const [graphOpen, setGraphOpen] = useState(false)
  const [configOpen, setConfigOpen] = useState(true)
  const [logsOpen, setLogsOpen] = useState(false)
  const [logs, setLogs] = useState('')
  // BUG-TC6-3：发布版本列表 + 按版本回滚（以前只有「已发布 vN」标签，看不到历史）
  const [versionsOpen, setVersionsOpen] = useState(false)
  const [versions, setVersions] = useState<any[]>([])
  const [versionsLoading, setVersionsLoading] = useState(false)
  const [rollingBack, setRollingBack] = useState<number | null>(null)
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewRows, setPreviewRows] = useState<any[]>([])
  const [previewTopic, setPreviewTopic] = useState<string | undefined>(undefined)
  const [topics, setTopics] = useState<string[]>([])
  const [kafkaSourceId, setKafkaSourceId] = useState<number | undefined>(undefined)
  const [previewGroup, setPreviewGroup] = useState('')
  const [previewOffset, setPreviewOffset] = useState<'earliest' | 'latest'>('earliest')
  const [previewGroupUsed, setPreviewGroupUsed] = useState<{
    group?: string; ephemeral?: boolean; offset?: string
  }>({})
  // 从任务/数据源自动带出的组：数据源一换就跟着换（用户手填过的不会覆盖）
  const [autoGroup, setAutoGroup] = useState('')
  const [form] = Form.useForm()
  // 编辑器内容和库里的不一致 = 有未保存修改：执行动作前自动保存，关页面前给提醒
  const dirty = Boolean(job) && sql !== (job?.sql_text || '')

  const load = useCallback(async () => {
    if (!id) return
    try {
      const detail = await api<Job>(`/api/flink/jobs/${id}`)
      setJob(detail)
      setSql(detail.sql_text || '')
      form.setFieldsValue({
        name: detail.name,
        code: detail.code,
        projectId: detail.project_id,
        description: detail.description,
        folder: detail.folder || '',
        env: detail.env || 'DEV',
        sessionMode: detail.session_mode || 'ISOLATED',
      })
    } catch (e: any) {
      message.error(e?.message || '读取流任务失败')
    }
  }, [id])

  useEffect(() => {
    if (!dirty) return
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handler)
    return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])

  const loadMetrics = useCallback(async () => {
    if (!id) return
    try {
      const value = await api<any>(`/api/flink/jobs/${id}/metrics`)
      setMetrics(value)
    } catch {
      setMetrics(null)
    }
  }, [id])

  useEffect(() => {
    api<ProjectOption[]>('/api/projects')
      .then((list) => setProjects(list || []))
      .catch((e) => message.error(`「项目列表」加载失败：${e instanceof Error ? e.message : String(e)}`))
    load()
  }, [load])

  useEffect(() => {
    if (!job) return
    const devActive = ACTIVE_STATES.includes(job.status)
    const prodActive = PROD_ACTIVE_STATES.includes(job.prod_status || '')
    // 任一侧在跑就轮询：DEV 停了但线上还在跑时，详情页也必须自己刷新。
    if (!devActive && !prodActive) return
    const tick = async () => {
      try {
        const value = await api<any>(`/api/flink/jobs/${id}/status`)
        setRemoteState(value.remoteState ?? null)
        setJob((prev) => (prev ? {
          ...prev,
          status: value.status,
          last_error: value.error || prev.last_error,
        } : prev))
        if (ACTIVE_STATES.includes(value.status)) loadMetrics()
      } catch {
        // 瞬时失败不打断轮询
      }
      if (prodActive) {
        try {
          const prod = await api<any>(`/api/flink/jobs/${id}/prod/status`)
          setProdRemoteState(prod.remoteState ?? null)
          setJob((prev) => (prev ? {
            ...prev,
            prod_status: prod.status,
            prod_job_id: prod.jobId ?? prev.prod_job_id,
          } : prev))
        } catch {
          // 同上
        }
      }
    }
    tick()
    const timer = setInterval(tick, 10_000)
    return () => clearInterval(timer)
  }, [job?.status, job?.prod_status, id, loadMetrics])

  useEffect(() => {
    if (!job || !ACTIVE_STATES.includes(job.status)) return
    loadMetrics()
  }, [job?.flink_job_id, job?.status, loadMetrics])

  const refreshStatus = async () => {
    if (!id) return
    try {
      const status = await api<any>(`/api/flink/jobs/${id}/status`)
      setRemoteState(status.remoteState ?? null)
      setJob((prev) => (prev ? { ...prev, status: status.status,
        last_error: status.error || prev.last_error } : prev))
      if (status.duration != null) {
        setMetrics((prev: any) => ({ ...(prev || {}), uptime: status.duration,
          remoteState: status.remoteState }))
      }
      if (ACTIVE_STATES.includes(status.status)) {
        loadMetrics()
      } else if (status.note) {
        message.info(status.note)
      }
    } catch (e: any) {
      message.error(e?.message || '刷新状态失败')
    }
  }

  const save = async (): Promise<boolean> => {
    if (!id) return false
    try {
      const values = await form.validateFields()
      setSaving(true)
      await put<Job>(`/api/flink/jobs/${id}`, {
        projectId: job?.project_id,
        code: job?.code,
        name: values.name,
        description: values.description,
        folder: values.folder || '',
        env: values.env || 'DEV',
        sessionMode: values.sessionMode,
        // REQ-DW-4：并行度/消费组不再是表单字段，改在 FlinkSQL 里配置（SET + WITH）。
        // 保存时原样带回库里的存量值：老任务不清空、预览仍能用它带出默认组；新任务保持缺省。
        parallelism: job?.parallelism ?? 1,
        consumerGroup: job?.consumer_group ?? '',
        sqlText: sql,
      })
      // 保存后重新拉详情：校验/冒烟的「是否对当前 SQL 有效」由后端按指纹算
      await load()
      message.success('已保存')
      return true
    } catch (e: any) {
      if (e?.errorFields) return false
      message.error(e?.message || '保存失败')
      return false
    } finally {
      setSaving(false)
    }
  }

  /**
   * 执行类动作前先落盘：校验/冒烟/运行/发布用的都是库里的 SQL，
   * 编辑器里改了没保存就会跑旧版本（还会被 load() 覆盖掉刚写的 SQL）。
   */
  const ensureSaved = async (): Promise<boolean> => {
    if (!dirty) return true
    if (!canWrite) {
      message.warning('有未保存的修改，但当前角色没有保存权限；请先复制备份')
      return false
    }
    return save()
  }

  const runAction = async (action: 'validate' | 'submit' | 'stop') => {
    if (!id) return
    if (ACTIVE_STATES.includes(job?.status || '') && action !== 'stop') {
      message.warning('运行中不能校验/重复提交，先停止')
      return
    }
    if (action !== 'stop' && !(await ensureSaved())) return
    setBusy(action)
    setError('')
    setOk('')
    try {
      const result = await post<any>(`/api/flink/jobs/${id}/${action}`)
      if (action === 'validate') {
        setOk(`校验通过（${result?.statements ?? 1} 条语句）`)
      } else if (action === 'submit') {
        setOk(`DEV 作业已启动：${result?.flink_job_id || ''}`)
      } else {
        setOk(result?.note ? `已停止：${result.note}` : '已停止')
      }
      await load()
      if (action === 'submit') {
        setTimeout(() => { loadMetrics() }, 1500)
      }
    } catch (e: any) {
      setError(e?.message || '操作失败')
    } finally {
      setBusy('')
    }
  }

  const runSmoke = async () => {
    if (!id) return
    if (!(await ensureSaved())) return
    setBusy('smoke')
    setError('')
    setOk('')
    try {
      const result = await post<any>(`/api/flink/jobs/${id}/smoke`)
      setOk(`DEV 跑通：作业已运行并取消（${result?.state || ''}）`)
      await load()
    } catch (e: any) {
      setError(e?.message || 'DEV 跑通失败')
      await load()
    } finally {
      setBusy('')
    }
  }

  /** 一个按钮管发布：必要时生成快照（冒烟指纹校验在服务端），然后拉起线上作业。 */
  const runProdUp = async () => {
    if (!id) return
    if (!(await ensureSaved())) return
    setBusy('prodUp')
    setError('')
    setOk('')
    try {
      const result = await post<any>(`/api/flink/jobs/${id}/prod/up`)
      setOk(`已发布 v${result?.version}，线上作业：${result?.jobId || ''}`)
      await load()
    } catch (e: any) {
      setError(e?.message || '发布失败')
    } finally {
      setBusy('')
    }
  }

  const runProdStop = async () => {
    if (!id) return
    setBusy('prodStop')
    try {
      const result = await post<any>(`/api/flink/jobs/${id}/prod/stop`)
      setOk(result?.note ? `已停止：${result.note}` : '线上作业已停止')
      await load()
    } catch (e: any) {
      setError(e?.message || '停止线上失败')
    } finally {
      setBusy('')
    }
  }

  const loadVersions = async () => {
    if (!id) return
    setVersionsLoading(true)
    try {
      setVersions(await api<any[]>(`/api/flink/jobs/${id}/releases`))
    } catch (e: any) {
      message.error(e?.message || '读取版本历史失败')
    } finally {
      setVersionsLoading(false)
    }
  }

  const openVersions = () => {
    setVersionsOpen(true)
    loadVersions()
  }

  /** 回滚只切快照与草稿，不自动重启线上作业；线上有作业时提示先停止线上。 */
  const rollbackTo = (version: number) => {
    Modal.confirm({
      title: `回滚到 v${version}？`,
      content: '会用该版本的 SQL 生成一个新版本，并把编辑器内容改回那一版（未保存的修改会丢失）。'
        + '线上作业不会自动重启——需要时再点「发布线上」。',
      okText: '回滚',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        if (!id) return
        setRollingBack(version)
        setError('')
        setOk('')
        try {
          const result = await post<any>(`/api/flink/jobs/${id}/rollback/${version}`)
          setOk(`已回滚到 v${version}，生成新版本 v${result?.version}`
            + (result?.prodRestartRequired ? '；线上作业仍在跑旧内容，请先「停止线上」再「发布线上」' : ''))
          setVersionsOpen(false)
          await load()
        } catch (e: any) {
          setError(e?.message || '回滚失败')
        } finally {
          setRollingBack(null)
        }
      },
    })
  }

  const openLogs = async () => {
    setLogsOpen(true)
    setLogs('加载中…')
    try {
      const value = await api<{ logs: string }>(`/api/flink/jobs/${id}/logs?lines=300`)
      setLogs(value?.logs || '（暂无日志）')
    } catch (e: any) {
      setLogs(`读取日志失败：${e?.message || e}`)
    }
  }

  const openGraph = async () => {
    setGraphOpen(true)
    try {
      const value = await api<any>(`/api/flink/jobs/${id}/graph`)
      setGraph(value)
    } catch (e: any) {
      message.error(e?.message || '读取 DAG 失败')
    }
  }

  // ---------------- Kafka 数据预览 ----------------
  useEffect(() => {
    if (!job?.project_id) return
    api<DataSource[]>(`/api/projects/${job.project_id}/datasources`)
      .then((list) => {
        const kafka = (list || []).filter((ds) => ds.ds_type === 'KAFKA')
        setSources(kafka)
        if (kafka.length && kafkaSourceId == null) {
          setKafkaSourceId(kafka[0].id)
        }
      })
      .catch((e) => message.error(`「Kafka 数据源」加载失败：${e instanceof Error ? e.message : String(e)}`))
  }, [job?.project_id])

  useEffect(() => {
    if (!kafkaSourceId) {
      setTopics([])
      return
    }
    // 任务配置 > 数据源默认；都没有就留空走一次性临时组
    const ds = sources.find((s) => s.id === kafkaSourceId)
    const group = job?.consumer_group
      || ds?.config?.['consumer.group'] || ds?.config?.consumerGroup
    const next = group ? String(group) : ''
    if (!previewGroup || previewGroup === autoGroup) {
      setPreviewGroup(next)
      setAutoGroup(next)
    }
    api<string[]>(`/api/datasources/${kafkaSourceId}/kafka/topics`)
      .then((value) => {
        const list = Array.isArray(value) ? value : []
        setTopics(list)
        setPreviewTopic(list[0])
      })
      .catch(() => setTopics([]))
  }, [kafkaSourceId, sources, job?.consumer_group])

  const previewKafka = async () => {
    if (!kafkaSourceId || !previewTopic) {
      message.warning('先选择 Kafka 数据源和 topic')
      return
    }
    try {
      const query = new URLSearchParams({ topic: previewTopic || '', limit: '20', offset: previewOffset })
      if (previewGroup.trim()) query.set('groupId', previewGroup.trim())
      const value = await api<any>(
        `/api/datasources/${kafkaSourceId}/kafka/preview?${query.toString()}`)
      setPreviewRows(value?.messages || [])
      setPreviewGroupUsed({
        group: value?.groupId,
        ephemeral: Boolean(value?.ephemeralGroup),
        offset: value?.offset,
      })
      setPreviewOpen(true)
    } catch (e: any) {
      message.error(e?.message || '预览失败')
    }
  }

  const rf = useMemo(() => buildGraph(graph), [graph])
  const flinkTables = useMemo(() => parseFlinkTables(sql), [sql])
  const statusMeta = STATUS_META[job?.status || 'DRAFT'] || STATUS_META.DRAFT
  const running = ACTIVE_STATES.includes(job?.status || '')
  const submitting = job?.status === 'SUBMITTING'
  /** 提交中也不能编辑/保存/再点运行 */
  const locked = running || submitting
  // 以 SQL 指纹为准（后端算好 smoke_ok/validate_ok）：改名字/改目录不会让状态失效
  const smokeOk = job?.smoke_ok !== undefined ? Boolean(job.smoke_ok) : Boolean(job?.smoke_at)
  const validateOk = Boolean(job?.validate_ok)
  const smokeStale = Boolean(job?.smoke_at) && !smokeOk
  const canPublishNow = smokeOk
  const watermarkText = metrics?.watermarkTime
    ? `${fmtTime(metrics.watermarkTime)}${metrics.watermarkLagSeconds != null
      ? `（滞后 ${metrics.watermarkLagSeconds}s）` : ''}`
    : '-'

  return (
    <div>
      <PageHeader
        title={job ? `流任务：${job.name}` : '流任务'}
        description={job ? `${job.project_name || ''} / ${job.code}` : ''}
        backTo="/stream"
        tags={job && (
          <Space size={4}>
            <Tag color="blue">DEV</Tag>
            {job.published_version ? <Tag color="green">已发布 v{job.published_version}</Tag> : null}
            <Tag color={statusMeta.color}>{statusMeta.text}</Tag>
          </Space>
        )}
        extra={(
          <Space wrap>
            {/* D37：刷新入口收敛（保持 D36 口径——真刷新状态，不是开日志；
                不做整页重载是因为 dirty 未保存内容会被覆盖） */}
            <PageActions refresh={{ label: '刷新状态', onClick: refreshStatus }} />
            {(job?.flink_job_id || job?.prod_job_id) && (
              <>
                <Button icon={<NodeIndexOutlined />} onClick={openGraph}>DAG</Button>
                <Button icon={<FileTextOutlined />} onClick={openLogs}>日志</Button>
              </>
            )}
            {job && (
              <Button icon={<HistoryOutlined />} onClick={openVersions}
                      title="查看历史发布版本，按版本回滚">版本</Button>
            )}
            {canRun && (
              <Button icon={<CheckCircleOutlined />} loading={busy === 'validate'}
                      disabled={locked} onClick={() => runAction('validate')}>校验</Button>
            )}
            {canRun && (
              <Button icon={<ExperimentOutlined />} loading={busy === 'smoke'}
                      title="真起一次作业：批作业跑完、流作业稳定 5 秒即通过，随后自动取消；通过才能发布（与数据开发「DEV 跑通才能发布」同一条规则）"
                      disabled={locked || !sql.trim()} onClick={runSmoke}>DEV 跑通</Button>
            )}
            {/* 顺序固定为：保存 → 运行（DEV 调试，倒数第二）→ 发布线上/停止线上（最后） */}
            <PageActions save={{
              label: dirty ? '保存 *' : '保存',
              onClick: save, loading: saving, disabled: locked,
              title: dirty ? '有未保存的修改' : '保存',
              show: canWrite,
            }} />
            {canRun && submitting && (
              <Button loading disabled title="正在向 Flink 提交，请稍候">提交中…</Button>
            )}
            {canRun && !submitting && (running
              ? <Button danger icon={<StopOutlined />} loading={busy === 'stop'}
                        onClick={() => runAction('stop')}>停止运行</Button>
              : <Button type="primary" icon={<PlayCircleOutlined />} loading={busy === 'submit'}
                        disabled={!sql.trim()}
                        title={sql.trim()
                          ? '在 DEV 会话集群起一个调试作业，常驻到手动停止'
                          : '先写 SQL 才能运行'}
                        onClick={() => runAction('submit')}>运行（DEV 调试）</Button>)}
            {/* 发布和调试分开：发布是高风险动作，放在最右并二次确认 */}
            {(canRun || canPublish) && (
              <span style={{ width: 1, height: 22, background: '#e5e7eb', margin: '0 2px' }} />
            )}
            {/* 「停止线上」走 /prod/stop（flink:run）：线上只要有作业就必须能停，
                不能因为角色没有发布权限就把入口藏起来（以前会看到按钮但点了 403） */}
            {canRun && PROD_ACTIVE_STATES.includes(job?.prod_status || '') && (
              <Button danger type="primary" icon={<StopOutlined />} loading={busy === 'prodStop'}
                      title="停止线上作业（不改快照）"
                      onClick={runProdStop}>停止线上</Button>
            )}
            {canPublish && !PROD_ACTIVE_STATES.includes(job?.prod_status || '') && (
              <Button type="primary" danger icon={<RocketOutlined />} loading={busy === 'prodUp'}
                      disabled={locked || !canPublishNow}
                      title={canPublishNow
                        ? '发布当前 SQL 快照并启动线上作业'
                        : '先 DEV 跑通，才能发布'}
                      onClick={() => {
                        Modal.confirm({
                          title: '发布到线上并启动？',
                          content: '会把当前 SQL 发布成一个新的线上版本并立即启动，'
                            + '线上作业会开始读源表、写目标表。',
                          okText: '发布并启动',
                          okButtonProps: { danger: true },
                          cancelText: '取消',
                          onOk: runProdUp,
                        })
                      }}>发布线上</Button>
            )}
          </Space>
        )}
      />

      {error && (
        <Alert type="error" showIcon style={{ marginBottom: 12 }} message="操作失败"
               description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{error}</pre>} />
      )}
      {ok && <Alert type="success" showIcon style={{ marginBottom: 12 }} message={ok} />}

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <div style={{ width: 260, flex: '0 0 260px', height: 720 }}>
          <StreamTree
            currentId={Number(id)}
            beforeNavigate={() => !dirty
              || window.confirm('当前脚本有未保存的修改，离开会丢失。继续？')}
            refreshKey={job?.updated_at}
          />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <div style={{ flex: '1 1 0', minWidth: 0 }}>
          <Card size="small" title="FlinkSQL 脚本"
                // 校验对当前 SQL 通过时整张卡片淡绿底：一眼看出「这版 SQL 是好的」
                style={validateOk ? {
                  background: '#f6ffed', borderColor: '#b7eb8f',
                } : undefined}
                extra={(
                  <Space size={8}>
                    {validateOk
                      ? <Tag color="green">校验通过 {fmtTime(job?.validate_at)}</Tag>
                      : <Tag>{job?.validate_at ? '校验未通过/已失效' : '未校验'}</Tag>}
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      多条语句；最后一条需要 INSERT INTO ... SELECT
                    </Typography.Text>
                    <KafkaSqlHelp />
                  </Space>
                )}>
            <CodeEditor
              label="FlinkSQL"
              height={640}
              language="SQL"
              value={sql}
              onChange={setSql}
              readOnly={locked || !canWrite}
              tables={flinkTables}
              placeholder="CREATE TABLE ... WITH (...); INSERT INTO ... SELECT ..."
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              连接凭据可用占位符 DORIS_HOST / DORIS_USER / DORIS_PASSWORD（提交时由后端替换），
              库里的 SQL 不落明文。每个会话里的 TABLE 只在本次作业生命周期内有效。
            </Typography.Text>
          </Card>
        </div>
        {configOpen ? (
        <div style={{ flex: '0 0 400px', width: 400 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 4 }}>
            <Button size="small" type="text" icon={<LeftOutlined />}
                    onClick={() => setConfigOpen(false)}>收起配置</Button>
          </div>
          <Card size="small" title="基本信息" style={{ marginBottom: 12 }}>
            <Form form={form} layout="vertical" disabled={locked || !canWrite}>
              <Form.Item name="name" label="名称" rules={[{ required: true }]}>
                <Input maxLength={128} />
              </Form.Item>
              <Space size={8} style={{ display: 'flex' }}>
                <Form.Item name="code" label="编码" style={{ flex: 1 }}>
                  <Input disabled />
                </Form.Item>
                <Form.Item name="projectId" label="项目" style={{ flex: 1 }}>
                  <Select disabled options={projects.map((p) => ({ value: p.id, label: p.name }))} />
                </Form.Item>
              </Space>
              <Form.Item name="folder" label="目录（相对项目根，可留空）">
                <Input placeholder="例如 实时/订单" />
              </Form.Item>
              <Form.Item name="description" label="说明">
                <Input maxLength={500} />
              </Form.Item>
              <Form.Item name="sessionMode" label="会话模式"
                         tooltip="独立：每次提交新建 Gateway 会话，停止时关闭；共享：复用平台级会话，会话失效会自动重建">
                <Radio.Group disabled={running}>
                  <Radio.Button value="ISOLATED">独立会话</Radio.Button>
                  <Radio.Button value="SHARED">共享会话</Radio.Button>
                </Radio.Group>
              </Form.Item>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                并行度、消费组不再在这里配置：请在 FlinkSQL 脚本里设置
                （SET 'parallelism.default' / Kafka 源 WITH 的 'properties.group.id'），
                见编辑器上方「脚本帮助 → 并行度 / 消费组」。
              </Typography.Text>
            </Form>
          </Card>

          <Card size="small" title="运行状态" style={{ marginBottom: 12 }}>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="Flink Job">
                {job?.flink_job_id
                  ? <Typography.Text copyable style={{ fontSize: 12 }}>{job.flink_job_id}</Typography.Text>
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="提交时间">{fmtTime(job?.submitted_at)}</Descriptions.Item>
              <Descriptions.Item label="运行时长">{fmtDuration(metrics?.uptime)}</Descriptions.Item>
              <Descriptions.Item label="远端状态">{remoteState || '-'}</Descriptions.Item>
              {job?.prod_status && job.prod_status !== 'OFFLINE' && (
                <Descriptions.Item label="线上远端状态">{prodRemoteState || job.prod_status}</Descriptions.Item>
              )}
              {job?.last_error && (
                <Descriptions.Item label="错误">
                  <Typography.Text type="danger" style={{ fontSize: 12 }}>{job.last_error}</Typography.Text>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>

          <Card size="small" title="发布状态"
                style={{
                  marginBottom: 12,
                  // 冒烟对当前 SQL 有效 → 绿底；冒烟过但 SQL 改了 → 黄底提示重新冒烟
                  ...(smokeOk ? { background: '#f6ffed', borderColor: '#b7eb8f' }
                    : smokeStale ? { background: '#fffbe6', borderColor: '#ffe58f' } : {}),
                }}
                extra={smokeOk
                  ? <Tag color="green">DEV 跑通</Tag>
                  : smokeStale ? <Tag color="orange">需重新跑通</Tag> : <Tag>未跑通</Tag>}>
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="DEV 跑通">
                {job?.smoke_at
                  ? <span>{fmtTime(job.smoke_at)}{smokeStale ? '（SQL 已修改，需重新跑通）' : ''}</span>
                  : '未通过'}
              </Descriptions.Item>
              <Descriptions.Item label="发布版本">
                {job?.published_version ? `v${job.published_version}（${fmtTime(job.published_at)}）` : '未发布'}
              </Descriptions.Item>
              <Descriptions.Item label="线上作业">
                {job?.prod_status || 'OFFLINE'}
                {job?.prod_job_id
                  ? <Typography.Text copyable style={{ fontSize: 12, marginLeft: 6 }}>{job.prod_job_id}</Typography.Text>
                  : null}
              </Descriptions.Item>
            </Descriptions>
            {!job?.smoke_at && (
              <Alert type="info" showIcon message="先做一次 DEV 跑通，通过后才能发布" />
            )}
            {job?.smoke_at && smokeStale && (
              <Alert type="warning" showIcon message="SQL 在 DEV 跑通后修改过，请重新跑通再发布" />
            )}
          </Card>

          <Card size="small" title="运行指标" style={{ marginBottom: 12 }}
                extra={(
                  <Space size={6}>
                    {metrics?.history ? <Tag>历史作业（已停止）</Tag> : null}
                    {/* D36：与工具栏「刷新状态」区分作用域——这里只重拉运行指标 */}
                    <Button size="small" type="link" onClick={loadMetrics}>刷新指标</Button>
                  </Space>
                )}>
            <Row gutter={8}>
              <Col span={12}><Typography.Text type="secondary">处理记录（入/出）</Typography.Text>
                <div><b>{metrics?.recordsIn ?? '-'}</b> / <b>{metrics?.recordsOut ?? '-'}</b></div></Col>
              <Col span={12}><Typography.Text type="secondary">速率（条/秒）</Typography.Text>
                <div><b>{metrics?.recordsInPerSecond ?? '-'}</b> / <b>{metrics?.recordsOutPerSecond ?? '-'}</b></div></Col>
            </Row>
            <Row gutter={8} style={{ marginTop: 8 }}>
              <Col span={12}><Typography.Text type="secondary">水位（数据处理到的时刻）</Typography.Text>
                <div style={{ fontSize: 12 }}>{watermarkText}</div></Col>
              <Col span={12}><Typography.Text type="secondary">背压（下游消化不过来的程度）</Typography.Text>
                <div>{metrics?.backpressure ?? '未知'}</div></Col>
            </Row>
            <Row gutter={8} style={{ marginTop: 8 }}>
              <Col span={12}><Typography.Text type="secondary">积压（pending / lag）</Typography.Text>
                <div><b>{metrics?.pendingRecords ?? '-'}</b> / <b>{metrics?.recordsLagMax ?? '-'}</b></div></Col>
              <Col span={12}><Typography.Text type="secondary">Checkpoint（故障恢复快照，成功/失败）</Typography.Text>
                <div><b>{metrics?.checkpoints?.completed ?? '-'}</b> / <b>{metrics?.checkpoints?.failed ?? '-'}</b></div></Col>
            </Row>
            {metrics?.vertices?.length ? (
              <Table
                style={{ marginTop: 8 }}
                size="small" pagination={false} rowKey="id"
                dataSource={metrics.vertices}
                locale={{ emptyText: tableEmpty('没有算子统计', '作业进入 RUNNING 后由 Flink 上报') }}
                columns={[
                  { title: '算子', dataIndex: 'name', key: 'name', ellipsis: true },
                  { title: '入', dataIndex: 'recordsIn', key: 'recordsIn', width: 70 },
                  { title: '出', dataIndex: 'recordsOut', key: 'recordsOut', width: 70 },
                  { title: '积压', dataIndex: 'pendingRecords', key: 'pendingRecords', width: 70 },
                ]}
              />
            ) : null}
          </Card>

          <Card size="small" title="数据预览（Kafka · DEV）">
            <Space direction="vertical" style={{ width: '100%' }} size={8}>
              <Select style={{ width: '100%' }} placeholder="Kafka 数据源"
                      value={kafkaSourceId} onChange={setKafkaSourceId}
                      options={sources.map((s) => ({ value: s.id, label: s.name }))} />
              <Space.Compact style={{ width: '100%' }}>
                <Select style={{ width: '70%' }} placeholder="topic" value={previewTopic}
                        onChange={setPreviewTopic}
                        options={topics.map((t) => ({ value: t, label: t }))} />
                <Button type="primary" onClick={previewKafka} style={{ width: '30%' }}>预览</Button>
              </Space.Compact>
              <Space.Compact style={{ width: '100%' }}>
                <Input
                  style={{ width: '68%' }}
                  placeholder="消费组 Group ID（可不填，用一次性临时组）"
                  value={previewGroup}
                  onChange={(e) => setPreviewGroup(e.target.value)}
                />
                <Select
                  style={{ width: '32%' }}
                  value={previewOffset}
                  onChange={setPreviewOffset}
                  options={[
                    { value: 'earliest', label: '从最早读' },
                    { value: 'latest', label: '从最新读' },
                  ]}
                />
              </Space.Compact>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                预览不提交位点：填了消费组也只读不写，不会顶掉采集任务进度。
              </Typography.Text>
              {!sources.length && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  先在「数据源」里建一个 Kafka 数据源
                </Typography.Text>
              )}
            </Space>
          </Card>
        </div>
        ) : (
        <div style={{ flex: '0 0 52px', width: 52 }}>
          <Card size="small" styles={{ body: { padding: 4 } }}>
            <Button type="text" title="展开配置"
                    onClick={() => setConfigOpen(true)}
                    style={{ height: 520, width: '100%', display: 'flex',
                      flexDirection: 'column', alignItems: 'center', gap: 6 }}>
              <SettingOutlined />
              <span style={{ writingMode: 'vertical-rl', letterSpacing: 2 }}>展开配置</span>
            </Button>
          </Card>
        </div>
        )}
      </div>
        </div>
      </div>

      {/* D36：弹窗内的刷新要说清刷的是日志，别和工具栏「刷新状态」混成一个意思 */}
      <Modal
        open={logsOpen} width={920} title="作业日志"
        onCancel={() => setLogsOpen(false)}
        footer={<Space><Button onClick={openLogs}>刷新日志</Button>
          <Button onClick={() => setLogsOpen(false)}>关闭</Button></Space>}
      >
        <pre style={{
          margin: 0, maxHeight: '60vh', overflow: 'auto', background: '#0f172a',
          color: '#e2e8f0', padding: 12, borderRadius: 6, fontSize: 12,
          whiteSpace: 'pre-wrap', wordBreak: 'break-all',
        }}>{logs}</pre>
      </Modal>

      {/* BUG-TC6-3：版本历史 + 按版本回滚（回滚 = 以旧快照生成新版本，不覆盖历史） */}
      <Modal
        open={versionsOpen} width={880} title="历史发布版本"
        onCancel={() => setVersionsOpen(false)}
        footer={<Space>
          <Button onClick={loadVersions} loading={versionsLoading}>刷新</Button>
          <Button onClick={() => setVersionsOpen(false)}>关闭</Button>
        </Space>}
      >
        <Table
          size="small" rowKey="version" loading={versionsLoading}
          dataSource={versions} pagination={false} scroll={{ y: 420 }}
          locale={{ emptyText: tableEmpty('还没有发布过版本', '点「发布线上」后生成第一个版本') }}
          columns={[
            { title: '版本', dataIndex: 'version', width: 90,
              render: (v: number, r: any) => (
                <Space size={4}>
                  <span>v{v}</span>
                  {r.current ? <Tag color="green">线上</Tag> : null}
                </Space>
              ) },
            { title: '模式', dataIndex: 'session_mode', width: 90 },
            { title: '发布人', dataIndex: 'created_by', width: 120 },
            { title: '发布时间', dataIndex: 'created_at', width: 160,
              render: (v: string) => fmtTime(v) },
            { title: 'SQL 长度', dataIndex: 'sql_length', width: 90 },
            { title: 'SQL 预览', dataIndex: 'sql_preview', ellipsis: true },
            { title: '操作', width: 100,
              render: (_: unknown, r: any) => (
                canPublish
                  ? (
                    <Button size="small" type="link" loading={rollingBack === r.version}
                            onClick={() => rollbackTo(r.version)}>回滚</Button>
                  )
                  : <Typography.Text type="secondary">-</Typography.Text>
              ) },
          ]}
        />
      </Modal>

      <Modal
        open={graphOpen} width={1000} title="作业 DAG" style={{ top: 40 }}
        onCancel={() => setGraphOpen(false)} footer={null}
      >
        <div style={{ height: 560 }}>
          {rf.nodes.length ? (
            <ReactFlow nodes={rf.nodes as any} edges={rf.edges as any} fitView nodesDraggable={false}
                       nodesConnectable={false} elementsSelectable={false}>
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          ) : (
            <Typography.Text type="secondary">提交作业后才能看到 DAG</Typography.Text>
          )}
        </div>
      </Modal>

      <Modal
        open={previewOpen} width={900} title={`Kafka 预览 · ${previewTopic || ''}`}
        onCancel={() => setPreviewOpen(false)}
        footer={<Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            消费组：{previewGroupUsed.group || '-'}
            {previewGroupUsed.ephemeral ? '（一次性临时组，不提交位点）' : '（只读，不提交位点）'}
            {' · '}起始位点：{(previewGroupUsed.offset || previewOffset) === 'latest' ? '最新' : '最早'}
          </Typography.Text>
          <Button onClick={() => setPreviewOpen(false)}>关闭</Button>
        </Space>}
      >
        <Table
          size="small" rowKey={(r: any) => `${r.partition}-${r.offset}`}
          dataSource={previewRows} pagination={false}
          locale={{ emptyText: tableEmpty('没有拉到消息', '选好 topic 与起始位点后点「拉取预览」') }}
          scroll={{ y: 420 }}
          columns={[
            { title: '分区', dataIndex: 'partition', key: 'partition', width: 70 },
            { title: 'Offset', dataIndex: 'offset', key: 'offset', width: 90 },
            { title: '时间', dataIndex: 'timestamp', key: 'timestamp', width: 170,
              render: (v: number) => fmtTime(v) },
            { title: '消息', dataIndex: 'value', key: 'value',
              render: (v: string) => <Typography.Text style={{ fontSize: 12, wordBreak: 'break-all' }}>{v}</Typography.Text> },
          ]}
        />
      </Modal>
    </div>
  )
}
