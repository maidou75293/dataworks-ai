import { useEffect, useRef, useState } from 'react'
import {
  Alert, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, Tooltip,
  Typography, message,
} from 'antd'
import {
  ApiOutlined, DeleteOutlined, EyeOutlined, QuestionCircleOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { useNavigate } from 'react-router-dom'
import { api, errMsg, del, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import PageActions from '../components/PageActions'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import SourceStructurePreview from '../components/SourceStructurePreview'
import { useGlobalProject } from '../project'
import type { Project } from '../types'

type DataSource = {
  id: number
  project_id: number
  name: string
  ds_type: string
  jdbc_url?: string
  username?: string
  config?: Record<string, any>
  /** V98：内置 Doris 由平台维护，不可删除。 */
  builtin?: boolean
  /** V98：DORIS 数据源的默认存储（存于对应数仓连接）。 */
  storage?: { type?: string | null; endpoint?: string | null; path?: string | null } | null
  /** V99：是否支持 SQL 查询（Kafka / 文件类为 false，只能入湖或导入）。 */
  queryable?: boolean
  /** V99：执行引擎分类 doris / kafka / fileset / jdbc / lake。 */
  engine?: string
  /** V100：Iceberg 湖数据源在 Doris 中的 Catalog（status: available / error / unknown）。 */
  lakeCatalog?: string | null
  lakeStatus?: string
  lakeStatusMessage?: string
  lakeSyncedAt?: string | null
  lake?: { catalog?: string | null; status?: string; message?: string; syncedAt?: string | null }
  /** V101：DORIS 数据源同步到 Gravitino 的 Catalog（status: synced / error / disabled / unknown）。 */
  gravitinoCatalog?: string | null
  gravitinoStatus?: string
  gravitinoMessage?: string
  gravitinoSyncedAt?: string | null
  gravitino?: {
    catalog?: string | null
    status?: string
    message?: string | null
    syncedAt?: string | null
    operation?: string
  }
  created_at: string
  last_test_at?: string
  last_test_ok?: boolean
  last_test_message?: string
}

type TestResult = {
  ok: boolean
  product?: string
  version?: string
  elapsedMs?: number
  error?: string
}

type MetadataSource = {
  source: string
  options?: { value: string; label: string; description?: string }[]
  gravitino?: { baseUrl?: string; metalake?: string }
  /** 第二批：后端元数据读取的实时状态（gravitino 不可用时会自动回退直连）。 */
  metadataSource?: string
  degraded?: boolean
  degradedCount?: number
  degradedCountHour?: string
  lastDegradedAt?: string | null
  lastDegradedReason?: string
  cacheTtlSeconds?: number
  cachedEntries?: number
}

const TYPE_META: Record<string, { label: string; color: string; template: string }> = {
  POSTGRESQL: {
    label: 'PostgreSQL',
    color: 'blue',
    template: 'jdbc:postgresql://postgres.dw-dev.svc.cluster.local:5432/dataworks_dev',
  },
  DORIS: {
    label: 'Doris',
    color: 'geekblue',
    template: 'jdbc:mysql://<doris-fe-host>:9030/<db>',
  },
  MYSQL: {
    label: 'MySQL',
    color: 'orange',
    template: 'jdbc:mysql://mysql-host:3306/database',
  },
  // Spark Thrift Server（HiveServer2 协议）；连接测试由后端 DriverManager + Kyuubi 驱动完成
  SPARK: {
    label: 'Spark Thrift',
    color: 'volcano',
    template: 'jdbc:hive2://spark-thrift.dw-spark.svc.cluster.local:10000/default',
  },
  S3FILE: { label: 'S3 / MinIO', color: 'cyan', template: '' },
  LOCALFILE: { label: '本地文件', color: 'default', template: '' },
  MONGODB: { label: 'MongoDB', color: 'green', template: '' },
  REDIS: { label: 'Redis', color: 'red', template: '' },
  CLICKHOUSE: { label: 'ClickHouse', color: 'gold', template: '' },
  KAFKA: { label: 'Kafka', color: 'purple', template: '' },
  ICEBERG: { label: 'Iceberg 湖', color: 'lime', template: '' },
}

const JDBC_TYPES = new Set(['POSTGRESQL', 'DORIS', 'MYSQL', 'SPARK'])
// REQ-DW-16：未保存表单可预览结构的类型（与后端 SourceMetadataProbe 支持面一致；SPARK 不放开）
const PREVIEW_TYPES = new Set(['POSTGRESQL', 'DORIS', 'MYSQL', 'KAFKA'])
// Spark Thrift 默认不认证：允许空口令（其余 JDBC 方言新建时必须填密码）
const OPTIONAL_PASSWORD_TYPES = new Set(['SPARK'])

type ConfigField = {
  key: string
  label: string
  placeholder?: string
  optional?: boolean
  hint?: string
  /** REQ-DW-5：专业名词的悬停说明（是什么、从哪获取）。 */
  tip?: string
  options?: { value: string; label: string }[]
}

/** REQ-DW-5：术语统一带问号 tooltip，用户不用猜 Access Key / Endpoint 是什么。 */
function LabelWithTip({ text, tip }: { text: string; tip: string }) {
  return (
    <span>
      {text}
      <Tooltip title={tip}>
        <QuestionCircleOutlined style={{ marginLeft: 6, color: 'var(--muted)' }} />
      </Tooltip>
    </span>
  )
}

const CONFIG_FIELDS: Record<string, ConfigField[]> = {
  S3FILE: [
    {
      key: 'endpoint', label: 'Endpoint', placeholder: 'http://minio.dw-dev.svc.cluster.local:9000',
      tip: 'Endpoint：对象存储的 API 访问地址（MinIO 默认 http://<主机>:9000），不是网页控制台地址；向存储管理员或部署文档获取。',
    },
    {
      key: 'bucket', label: 'Bucket', placeholder: 'dataworks-dev',
      tip: 'Bucket：对象存储里放数据文件的最外层容器（类似盘符），需先在存储服务端创建。',
    },
    { key: 'basePath', label: '基础路径', placeholder: '/corpus' },
  ],
  LOCALFILE: [{ key: 'path', label: '基础路径', placeholder: '/data/corpus' }],
  MONGODB: [
    { key: 'uri', label: 'MongoDB URI', placeholder: 'mongodb://mongo.dw-dev.svc.cluster.local:27017' },
    { key: 'database', label: '数据库', placeholder: 'corpus' },
  ],
  REDIS: [
    { key: 'host', label: 'Host', placeholder: 'redis.dw-dev.svc.cluster.local' },
    { key: 'port', label: 'Port', placeholder: '6379' },
  ],
  CLICKHOUSE: [
    { key: 'host', label: 'Host', placeholder: 'clickhouse.dw-dev.svc.cluster.local' },
    { key: 'port', label: 'Port', placeholder: '8123' },
    { key: 'database', label: '数据库', placeholder: 'default' },
  ],
  KAFKA: [
    {
      key: 'bootstrap.servers', label: 'Bootstrap Servers',
      placeholder: 'kafka-demo.dw-dev.svc.cluster.local:9092',
      tip: 'Bootstrap Servers：Kafka 集群的入口地址（host:port，多个用逗号分隔）；向运维或集群配置 server.properties 获取。',
    },
    { key: 'consumer.group', label: '默认消费组 Group ID（可选）', optional: true,
      placeholder: '例如 dw_dev_orders_reader',
      hint: '任务/预览没单独填消费组时用它；Kafka 各环境独立，按环境各配各的组' },
  ],
  // V100：保存后后端用管理员账号 CREATE CATALOG + GRANT SELECT_PRIV。
  ICEBERG: [
    { key: 'catalog', label: 'Catalog 名称', placeholder: 'iceberg_lake',
      hint: 'Doris 里的外部 Catalog 名；删除/查询时都用它（catalog.db.table）' },
    {
      key: 'catalogType', label: 'Catalog 类型', optional: true,
      options: [
        { value: 'hadoop', label: 'Hadoop（S3/MinIO 元数据）' },
        { value: 'hive', label: 'Hive Metastore' },
        { value: 'rest', label: 'Iceberg REST' },
        { value: 'glue', label: 'AWS Glue' },
      ],
    },
    {
      key: 'warehouse', label: 'Warehouse', placeholder: 's3://lake/warehouse',
      tip: 'Warehouse：Iceberg 表数据与元数据的存放根路径（如 s3://lake/warehouse），由数据湖部署方提供。',
    },
    {
      key: 'endpoint', label: 'S3 Endpoint', placeholder: 'http://minio.dw-dev.svc.cluster.local:9000',
      tip: 'S3 Endpoint：湖数据所在对象存储的 API 地址（MinIO 默认 http://<主机>:9000）；向存储管理员获取。',
    },
    { key: 'region', label: 'Region（可选）', optional: true, placeholder: 'us-east-1' },
    { key: 'uri', label: 'Metastore / REST URI（hive / rest 必填）', optional: true,
      placeholder: 'thrift://hive-metastore:9083' },
    { key: 'usePathStyle', label: 'Path Style（MinIO 建议 true）', optional: true,
      placeholder: 'true' },
  ],
}

const ACCESS_KEY_TYPES = new Set(['S3FILE', 'ICEBERG'])

function configSummary(row: DataSource): string {
  if (JDBC_TYPES.has(row.ds_type)) return row.jdbc_url || '-'
  const c = row.config || {}
  if (row.ds_type === 'KAFKA') {
    const bootstrap = c['bootstrap.servers'] || c.bootstrapServers || '-'
    const group = c['consumer.group'] || c.consumerGroup
    return group ? `${bootstrap} · 组 ${group}` : bootstrap
  }
  if (row.ds_type === 'S3FILE') return `${c.endpoint || ''}/${c.bucket || ''}`
  if (row.ds_type === 'ICEBERG') {
    return `${row.lakeCatalog || c.catalog || '-'} · ${c.warehouse || '-'}`
  }
  if (row.ds_type === 'MONGODB') return `${c.uri || ''}/${c.database || ''}`
  if (row.ds_type === 'REDIS' || row.ds_type === 'CLICKHOUSE') {
    return `${c.host || ''}:${c.port || ''}`
  }
  return c.path || '-'
}

/** D48：数仓连接（platform.warehouse_connection，HDFS / S3 / Doris 直连）。 */
interface WarehouseConn {
  id: number
  name: string
  type: string
  provider?: string | null
  endpoint?: string | null
  username?: string | null
  bucket?: string | null
  region?: string | null
  status?: string | null
  createdBy?: string | null
  createdAt?: string | null
}

interface ConnTestResult {
  ok: boolean
  detail?: string | null
  message?: string | null
  elapsedMs?: number | null
}

export default function DataSources() {
  const nav = useNavigate()
  const [projects, setProjects] = useState<Project[]>([])
  // 当前项目全局共享：在这里切了，数据开发和数据质量也跟着切
  const [projectId, setProjectId] = useGlobalProject()
  const [rows, setRows] = useState<DataSource[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataSource>()
  const [testing, setTesting] = useState(false)
  const [rowTesting, setRowTesting] = useState<number>()
  const [lakeBusy, setLakeBusy] = useState<number>()
  const [testResult, setTestResult] = useState<TestResult>()
  // REQ-DW-16：未保存表单的临时结构预览（有值=弹框打开）
  const [previewReq, setPreviewReq] = useState<Record<string, unknown>>()
  const [metadata, setMetadata] = useState<MetadataSource>()
  const [metadataBusy, setMetadataBusy] = useState(false)
  const [form] = Form.useForm()
  // D48：数仓连接的测试/删除入口（以前后端有接口、界面 0 处引用）
  const [conns, setConns] = useState<WarehouseConn[]>([])
  const [connsLoading, setConnsLoading] = useState(false)
  const [connsError, setConnsError] = useState('')
  const [connTesting, setConnTesting] = useState<number>()
  const [connResults, setConnResults] = useState<Record<number, ConnTestResult>>({})
  const type = Form.useWatch('type', form)
  const storageType = Form.useWatch('storageType', form)
  // REQ-DW-5：Doris 未勾选「作为开发数仓」= 仅采集，存储配置项禁用
  const devWarehouse = Form.useWatch('devWarehouse', form)
  const isJdbc = JDBC_TYPES.has(type || '') || !type

  const loadProjects = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    const valid = list.some((item) => item.id === projectId)
    if (!valid && list.length > 0) setProjectId(list[0].id)
  }

  /** D48：数仓连接列表（租户级，不随项目切换）。 */
  const loadConns = async () => {
    setConnsLoading(true)
    setConnsError('')
    try {
      setConns(await api<WarehouseConn[]>('/api/warehouse-connections'))
    } catch (e) {
      setConnsError(e instanceof Error ? e.message : '加载数仓连接失败')
    } finally {
      setConnsLoading(false)
    }
  }

  /** 测试连接：耗时与失败原因都原样展示，不把 message 吞成一句「失败」。 */
  const testConn = async (row: WarehouseConn) => {
    setConnTesting(row.id)
    try {
      const result = await post<ConnTestResult>(`/api/warehouse-connections/${row.id}/test`, {})
      setConnResults((prev) => ({ ...prev, [row.id]: result }))
      if (result.ok) {
        message.success(`连接成功：${result.detail || ''}（${result.elapsedMs ?? '-'} ms）`)
      } else {
        message.error(`连接失败（${result.elapsedMs ?? '-'} ms）：${result.message || '未知错误'}`)
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '测试连接失败')
    } finally {
      setConnTesting(undefined)
    }
  }

  /** 删除：二次确认；被项目引用时后端 400 并列出项目名，这里原样弹出。 */
  const removeConn = (row: WarehouseConn) => {
    Modal.confirm({
      title: `删除数仓连接「${row.name}」？`,
      okText: '删除',
      okButtonProps: { danger: true },
      content: (
        <div>
          <p style={{ marginBottom: 6 }}>
            类型 {row.type}，地址 {row.endpoint || '-'}。删除后依赖它的项目将无法再解析外部数仓。
          </p>
          <p style={{ margin: 0, color: '#8c8c8c' }}>
            若仍被项目引用，后端会拒绝并列出项目名（400）。
          </p>
        </div>
      ),
      onOk: async () => {
        try {
          await del(`/api/warehouse-connections/${row.id}`)
          message.success('数仓连接已删除')
          setConnResults((prev) => {
            const next = { ...prev }
            delete next[row.id]
            return next
          })
          await loadConns()
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除数仓连接失败')
          throw e
        }
      },
    })
  }

  const loadSeq = useRef(0)
  const projectIdRef = useRef(projectId)
  projectIdRef.current = projectId
  const load = async () => {
    const pid = projectIdRef.current
    const seq = ++loadSeq.current
    if (!pid) {
      setRows([])
      return
    }
    setLoading(true)
    setError('')
    try {
      const rows = await api<DataSource[]>(`/api/projects/${pid}/datasources`)
      if (seq !== loadSeq.current) return
      setRows(rows)
    } catch (e) {
      if (seq !== loadSeq.current) return
      setError(e instanceof Error ? e.message : '加载数据源失败')
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }

  const loadMetadata = async () => {
    try {
      setMetadata(await api<MetadataSource>('/api/metadata/source'))
    } catch {
      // 开关接口不可用时不影响数据源列表：按默认（Gravitino）展示，不弹错
    }
  }

  const changeMetadata = async (next: string) => {
    if (metadataBusy) return
    setMetadataBusy(true)
    try {
      const saved = await put<MetadataSource>('/api/metadata/source', { source: next })
      setMetadata(saved)
      message.success(next === 'gravitino'
        ? '元数据来源已切换为 Gravitino（保存 DORIS 数据源时同步 Catalog）'
        : '元数据来源已切换为直连（跳过后端 Gravitino 同步）')
    } catch (e: any) {
      message.error(e.message || '切换元数据来源失败')
    } finally {
      setMetadataBusy(false)
    }
  }

  useEffect(() => {
    loadProjects().catch((e) => message.error(errMsg(e)))
    loadMetadata()
    loadConns()
  }, [])

  useEffect(() => {
    // 切换项目先清空：加载期间/失败后不能继续显示并可操作上一个项目的数据源
    setRows([])
    load()
  }, [projectId])

  const openCreate = () => {
    setEditing(undefined)
    setTestResult(undefined)
    form.resetFields()
    form.setFieldsValue({
      type: 'POSTGRESQL',
      jdbcUrl: TYPE_META.POSTGRESQL.template,
      storageType: 'none',
      devWarehouse: false,
    })
    setOpen(true)
  }

  const openEdit = (row: DataSource) => {
    setEditing(row)
    setTestResult(undefined)
    // REQ-DW-5：开关优先读 config.devWarehouse；存量行没有该键时按已配存储推断
    // （已配存储=开发数仓，否则=仅采集）。内置 Doris 本来就是平台开发数仓，强制为开。
    const storedDev = (row.config as any)?.devWarehouse
    form.setFieldsValue({
      name: row.name,
      type: row.ds_type,
      jdbcUrl: row.jdbc_url || '',
      username: row.username || '',
      password: '',
      config: row.config || {},
      storageType: row.storage?.type || 'none',
      storageEndpoint: row.storage?.endpoint || '',
      storagePath: row.storage?.path || '',
      devWarehouse: row.builtin
        ? true
        : typeof storedDev === 'boolean'
          ? storedDev : (row.storage?.type || 'none') !== 'none',
    })
    setOpen(true)
  }

  const submit = async () => {
    const values = await form.validateFields()
    const currentType = values.type
    const needsSecret = (JDBC_TYPES.has(currentType) && !OPTIONAL_PASSWORD_TYPES.has(currentType))
      || ACCESS_KEY_TYPES.has(currentType)
    if (!editing && needsSecret && !values.password) {
      message.error(ACCESS_KEY_TYPES.has(currentType)
        ? `新建 ${TYPE_META[currentType]?.label || currentType} 数据源必须填写 Secret Key`
        : '新建数据源必须填写密码')
      return
    }
    // REQ-DW-5：devWarehouse 开关落进 config（DataSourceRequest 是严格 record，
    // 顶层多一个未知字段会被 Jackson 400），config_json 整列自由 Map 可透传。
    const { devWarehouse: devFlag, ...rest } = values
    const payload = {
      ...rest,
      config: {
        ...(rest.config || {}),
        ...(currentType === 'DORIS' ? { devWarehouse: !!devFlag } : {}),
      },
    }
    try {
      const res: any = editing
        ? await put(`/api/datasources/${editing.id}`, {
          ...payload,
          password: payload.password || '',
        })
        : await post(`/api/projects/${projectIdRef.current}/datasources`, payload)
      setOpen(false)
      // V100：保存成功但 Catalog 下发失败时，把可诊断原因直接弹出来，不用等用户去点测试
      if (res?.lake && res.lake.status !== 'available') {
        Modal.error({
          title: '数据源已保存，但湖 Catalog 未就绪',
          content: res.lake.error || res.lake.statusMessage || '请检查 Catalog / S3 配置后点「刷新 Catalog」重试',
          width: 720,
        })
      } else if (res?.gravitino && res.gravitino.status === 'error') {
        // V101：数据源本身已保存成功，失败的是元数据投影；把错误码/原因直接展示
        Modal.error({
          title: '数据源已保存，但 Gravitino Catalog 同步失败',
          content: res.gravitino.message || '请检查 Gravitino 服务与 metadata.source 开关后重新保存',
          width: 720,
        })
      } else {
        message.success(editing ? '已保存' : '已创建')
      }
      // 保存会重建/改名 Catalog 并清后端缓存，顺带刷新降级状态
      await Promise.all([load(), loadMetadata()])
    } catch (e: any) {
      message.error(e.message || '保存失败')
    }
  }

  const testForm = async () => {
    const currentType = form.getFieldValue('type')
    if (!JDBC_TYPES.has(currentType) && currentType !== 'ICEBERG') {
      setTestResult({ ok: false, error: '该类型暂不支持连接测试，请用同步任务验证' })
      return
    }
    const values = currentType === 'ICEBERG'
      ? await form.validateFields()
      : await form.validateFields(['name', 'type', 'jdbcUrl', 'username'])
    setTesting(true)
    setTestResult(undefined)
    try {
      const result = await post<TestResult & { note?: string }>('/api/datasources/test', {
        ...values,
        password: form.getFieldValue('password') || '',
        id: editing?.id,
      })
      setTestResult(result)
    } catch (e: any) {
      setTestResult({ ok: false, error: e.message || '测试失败' })
    } finally {
      setTesting(false)
    }
  }

  /**
   * REQ-DW-16：连接参数还没保存就预览结构——把表单当前值直送临时预览接口
   * （POST /api/integration/datasource/preview/*，不落库、不保存凭据），
   * 弹框复用 REQ-DW-15 的 SourceStructurePreview 同一组件。
   */
  const previewForm = async () => {
    const currentType = form.getFieldValue('type')
    if (!PREVIEW_TYPES.has(currentType)) {
      message.error(`${TYPE_META[currentType]?.label || '该'}类型暂不支持结构预览`
        + '（支持 MySQL / PostgreSQL / Doris / Kafka）')
      return
    }
    // KAFKA 的连接参数在 config 子字段里，按整表校验；JDBC 与「测试连接」同口径只校验必要项
    const values = currentType === 'KAFKA'
      ? await form.validateFields()
      : await form.validateFields(['name', 'type', 'jdbcUrl', 'username'])
    setPreviewReq({
      name: values.name || '',
      type: currentType,
      jdbcUrl: values.jdbcUrl || '',
      username: values.username || '',
      password: form.getFieldValue('password') || '',
      config: values.config || {},
      id: editing?.id,
    })
  }

  const testRow = async (row: DataSource) => {
    if (!JDBC_TYPES.has(row.ds_type) && row.ds_type !== 'ICEBERG') {
      Modal.info({ title: '暂不支持连接测试', content: '该类型请用同步任务验证连接' })
      return
    }
    // 不可达地址要等 connectTimeout + socketTimeout，期间按钮必须转圈并禁用
    if (rowTesting === row.id) return
    setRowTesting(row.id)
    try {
      const result = await post<TestResult>(`/api/datasources/${row.id}/test`)
      if (result.ok) {
        Modal.success({
          title: '连接成功',
          content: `${result.product || '数据库'} ${result.version || ''}，耗时 ${result.elapsedMs ?? '-'} ms`,
        })
      } else {
        Modal.error({ title: '连接失败', content: result.error || '未知错误' })
      }
    } catch (e: any) {
      Modal.error({ title: '连接失败', content: e.message || '测试失败' })
    } finally {
      setRowTesting(undefined)
      // 测试结果已落库（last_test_*），刷新列表让「连接状态」列立即更新
      load()
    }
  }

  const remove = async (row: DataSource) => {
    try {
      await del(`/api/datasources/${row.id}`)
      message.success('已删除')
      // 删除会清理 Gravitino Catalog 与元数据缓存，刷新降级状态
      await Promise.all([load(), loadMetadata()])
    } catch (e: any) {
      message.error(e.message || '删除失败')
    }
  }

  /** V100：刷新 Catalog（重新 CREATE/ALTER/REFRESH + 授权），失败弹可诊断原因。 */
  const refreshLake = async (row: DataSource) => {
    if (lakeBusy === row.id) return
    setLakeBusy(row.id)
    try {
      const res = await post<any>(`/api/lake/datasources/${row.id}/refresh`)
      if (res?.status === 'available') {
        message.success(`Catalog ${res.catalog} 已刷新，已授权库：${(res.databases || []).join('、') || '-'}`)
      } else {
        Modal.error({
          title: 'Catalog 刷新失败',
          content: res?.error || '请检查 Catalog / S3 配置与 DORIS_ADMIN_USER / DORIS_ADMIN_PASSWORD',
          width: 720,
        })
      }
      await load()
    } catch (e: any) {
      message.error(e.message || '刷新失败')
    } finally {
      setLakeBusy(undefined)
    }
  }

  /** 删除 Doris 里的 Catalog（表单里的 Popconfirm 已做一次确认；接口还要 confirm=名字）。 */
  const dropLakeCatalog = async (row: DataSource) => {
    const catalog = row.lakeCatalog || row.lake?.catalog
    if (!catalog) {
      message.warning('该数据源还没有创建 Catalog')
      return
    }
    setLakeBusy(row.id)
    try {
      await del(`/api/lake/datasources/${row.id}/catalog?confirm=${encodeURIComponent(catalog)}`)
      message.success('Catalog 已删除（Iceberg 数据与 Warehouse 文件保留）')
      await load()
    } catch (e: any) {
      message.error(e.message || '删除 Catalog 失败')
    } finally {
      setLakeBusy(undefined)
    }
  }

  const onTypeChange = (next: string) => {
    if (next === 'DORIS' && !form.getFieldValue('storageType')) {
      form.setFieldValue('storageType', 'none')
    }
    if (next === 'ICEBERG') {
      form.setFieldValue('config', { catalogType: 'hadoop', usePathStyle: 'true' })
      form.setFieldValue('username', undefined)
      form.setFieldValue('password', undefined)
      form.setFieldValue('jdbcUrl', undefined)
      setTestResult(undefined)
      return
    }
    if (JDBC_TYPES.has(next)) {
      const current = form.getFieldValue('jdbcUrl')
      const templates = Object.values(TYPE_META).map((m) => m.template).filter(Boolean)
      if (!current || templates.includes(current)) {
        form.setFieldValue('jdbcUrl', TYPE_META[next]?.template)
      }
      form.setFieldValue('config', {})
    } else {
      form.setFieldValue('jdbcUrl', undefined)
      form.setFieldValue('config', {})
    }
    setTestResult(undefined)
  }

  return (
    <div>
      <PageHeader
        title="数据源"
        description="统一管理数据库、消息队列和对象存储的连接；保存前可以先点「测试连接」确认可用。"
        extra={
          <Space wrap>
            <Select
              style={{ width: 220 }}
              value={projectId}
              placeholder="选择项目"
              onChange={setProjectId}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
            {can('system:manage') && metadata && (
              <Tooltip title={metadata.gravitino
                ? `Gravitino ${metadata.gravitino.baseUrl || ''} / metalake ${metadata.gravitino.metalake || ''}`
                : '元数据来源开关'}>
                <Select
                  style={{ width: 200 }}
                  value={metadata.source}
                  loading={metadataBusy}
                  onChange={changeMetadata}
                  options={(metadata.options || []).map((o) => ({
                    value: o.value,
                    label: `元数据：${o.label}`,
                  }))}
                />
              </Tooltip>
            )}
          </Space>
        }
        actions={(
          /* D37：刷新/新建收敛到标准操作区 */
          <PageActions
            refresh={{ onClick: () => { load(); loadMetadata() }, loading }}
            create={{ label: '新建数据源', onClick: openCreate, disabled: !projectId, show: can('datasource:write') }}
          />
        )}
      />

      {metadata?.source === 'gravitino' && metadata?.degraded && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="元数据降级"
          description={`Gravitino 暂不可用，库/表/字段信息已自动回退到直连 Doris。本小时降级 ${metadata.degradedCount ?? 0} 次${metadata.lastDegradedReason ? `；最近原因：${metadata.lastDegradedReason}` : ''}。`}
        />
      )}

      <Card size="small">
        {error ? (
          <ErrorState message={error} onRetry={() => load()} />
        ) : (
          <Table<DataSource>
            rowKey="id"
            size="small"
            loading={loading}
            dataSource={rows}
            tableLayout="fixed"
            scroll={{ x: 1300 }}
            pagination={false}
            locale={{ emptyText: tableEmpty('还没有数据源', '点击右上角「新建数据源」，先测试连接再保存') }}
            columns={[
              {
                title: '名称',
                dataIndex: 'name',
                width: 190,
                ellipsis: true,
                render: (value: string, row: DataSource) => (
                  <Space size={4}>
                    <span>{value}</span>
                    {row.builtin && <Tag color="geekblue">内置</Tag>}
                  </Space>
                ),
              },
              {
                title: '类型 / 查询能力',
                dataIndex: 'ds_type',
                width: 200,
                render: (v: string, row: DataSource) => (
                  <Space size={4}>
                    <Tag color={TYPE_META[v]?.color || 'default'}>{TYPE_META[v]?.label || v}</Tag>
                    {/* V99：一开始就让用户知道这个源能不能直接写 SQL，避免查了才 400 */}
                    {row.queryable === false ? (
                      <Tooltip title="Kafka / 文件类数据源没有 SQL 执行链路，请先入湖或导入后再查询">
                        <Tag color="orange">仅导入</Tag>
                      </Tooltip>
                    ) : (
                      <Tag color="green">可查询</Tag>
                    )}
                  </Space>
                ),
              },
              {
                title: '连接地址',
                key: 'endpoint',
                ellipsis: true,
                render: (_: unknown, row: DataSource) => {
                  const text = configSummary(row)
                  // 地址通常很长：截断展示，悬停看全文，右侧按钮复制
                  return (
                    <Typography.Text copyable={{ text }} ellipsis={{ tooltip: text }}>
                      {text}
                    </Typography.Text>
                  )
                },
              },
              { title: '用户名', dataIndex: 'username', width: 130, ellipsis: true },
              {
                title: '元数据同步',
                key: 'gravitino',
                width: 185,
                render: (_: unknown, row: DataSource) => {
                  if (row.ds_type !== 'DORIS') {
                    return <span style={{ color: 'var(--muted)' }}>-</span>
                  }
                  const status = row.gravitinoStatus || row.gravitino?.status || 'unknown'
                  const label = status === 'synced' ? '已同步'
                    : status === 'error' ? '同步失败'
                      : status === 'disabled' ? '跳过同步' : '未同步'
                  const color = status === 'synced' ? 'green'
                    : status === 'error' ? 'red' : 'default'
                  return (
                    <Tooltip title={row.gravitinoMessage || row.gravitino?.message || ''}>
                      <Space size={4} wrap>
                        <Tag color={color}>{label}</Tag>
                        {(row.gravitinoCatalog || row.gravitino?.catalog) && (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {row.gravitinoCatalog || row.gravitino?.catalog}
                          </Typography.Text>
                        )}
                      </Space>
                    </Tooltip>
                  )
                },
              },
              {
                title: '连接状态',
                key: 'lastTest',
                width: 170,
                render: (_: unknown, row: DataSource) => {
                  // V100：湖数据源看 Catalog 状态（available / error / unknown）
                  if (row.ds_type === 'ICEBERG') {
                    const status = row.lakeStatus || row.lake?.status || 'unknown'
                    const label = status === 'available' ? '可用'
                      : status === 'error' ? '异常' : '未检测'
                    const color = status === 'available' ? 'green'
                      : status === 'error' ? 'red' : 'default'
                    return (
                      <Tooltip title={row.lakeStatusMessage || row.lake?.message || ''}>
                        <Space size={4}>
                          <Tag color={color}>{label}</Tag>
                          {row.lakeCatalog && (
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {row.lakeCatalog}
                            </Typography.Text>
                          )}
                        </Space>
                      </Tooltip>
                    )
                  }
                  return row.last_test_at ? (
                    <Tooltip title={row.last_test_message || ''}>
                      <Tag color={row.last_test_ok ? 'green' : 'red'}>
                        {row.last_test_ok ? '正常' : '异常'}
                      </Tag>
                      <span style={{ color: 'var(--muted)' }}>
                        {dayjs(row.last_test_at).format('MM-DD HH:mm')}
                      </span>
                    </Tooltip>
                  ) : <Tag>未检测</Tag>
                },
              },
              {
                title: '创建时间',
                dataIndex: 'created_at',
                width: 160,
                render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
              },
              {
                title: '操作',
                width: 360,
                fixed: 'right',
                render: (_, row) => (
                  can('datasource:write') ? (
                    // 表格里三列描边按钮每行都红一片；改成文字按钮，危险动作保留红色文字
                    <Space size={0} wrap>
                      <Button
                        type="text"
                        size="small"
                        icon={<ApiOutlined />}
                        loading={rowTesting === row.id}
                        disabled={!!rowTesting && rowTesting !== row.id}
                        onClick={() => testRow(row)}
                      >
                        测试
                      </Button>
                      {row.ds_type === 'ICEBERG' ? (
                        /* D37：Catalog 级刷新/删除也走标准动作（label 覆盖，语义是刷/删 Catalog 而非列表） */
                        <PageActions
                          size="small"
                          buttonType="text"
                          refresh={{
                            label: '刷新 Catalog',
                            onClick: () => refreshLake(row),
                            loading: lakeBusy === row.id,
                            disabled: !!lakeBusy && lakeBusy !== row.id,
                          }}
                          delete={{
                            label: '删除 Catalog',
                            confirmTitle: `删除 Doris Catalog ${row.lakeCatalog || ''}？`,
                            confirmDescription: '仅删除 Catalog 映射与授权，Iceberg 数据与 Warehouse 文件保留。',
                            onClick: () => dropLakeCatalog(row),
                            disabled: !row.lakeCatalog || (!!lakeBusy && lakeBusy !== row.id),
                            show: can('datasource:delete'),
                          }}
                        />
                      ) : (
                        <Button
                          type="text"
                          size="small"
                          onClick={() => nav(`/integration?create=1&sourceDatasourceId=${row.id}`
                            + `&projectId=${row.project_id}`)}
                        >
                          建同步任务
                        </Button>
                      )}
                      <PageActions
                        size="small"
                        buttonType="text"
                        edit={{ onClick: () => openEdit(row) }}
                        delete={{
                          confirmTitle: `删除数据源 ${row.name}？`,
                          onClick: () => remove(row),
                          disabled: row.builtin,
                          disabledTip: row.builtin ? '内置 Doris 由平台维护，不能删除' : undefined,
                        }}
                      />
                    </Space>
                  ) : <span style={{ color: '#999' }}>只读</span>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Card
        size="small"
        title="数仓连接（HDFS / S3 / Doris 直连）"
        style={{ marginTop: 16 }}
        extra={<Button size="small" onClick={() => loadConns()}>刷新</Button>}
      >
        {connsError ? <Alert type="error" showIcon message={connsError} /> : null}
        <Table<WarehouseConn>
          rowKey="id"
          size="small"
          loading={connsLoading}
          dataSource={conns}
          pagination={false}
          locale={{ emptyText: tableEmpty('还没有数仓连接', '外部数仓（HDFS/S3/直连 Doris）在这里登记后可被项目引用') }}
          columns={[
            { title: '名称', dataIndex: 'name', width: 160 },
            {
              title: '类型', dataIndex: 'type', width: 90,
              render: (v: string, row) => <Tag>{`${v}${row.provider ? ` / ${row.provider}` : ''}`}</Tag>,
            },
            { title: '地址', dataIndex: 'endpoint', ellipsis: true },
            { title: '状态', dataIndex: 'status', width: 90,
              render: (v?: string) => (v ? <Tag color={v === 'ok' ? 'green' : 'orange'}>{v}</Tag>
                : <Typography.Text type="secondary">未测</Typography.Text>) },
            {
              title: '测试结果', width: 300,
              render: (_, row) => {
                const r = connResults[row.id]
                if (!r) return <Typography.Text type="secondary">-</Typography.Text>
                return r.ok
                  ? <Typography.Text type="success">{r.detail || 'ok'}（{r.elapsedMs ?? '-'} ms）</Typography.Text>
                  : <Typography.Text type="danger">{r.message || '未知错误'}（{r.elapsedMs ?? '-'} ms）</Typography.Text>
              },
            },
            {
              title: '操作', width: 170,
              render: (_, row) => (
                <Space size={4}>
                  <Button size="small" icon={<ApiOutlined />} loading={connTesting === row.id}
                          onClick={() => testConn(row)}>测试连接</Button>
                  {can('datasource:write') ? (
                    <Button size="small" danger icon={<DeleteOutlined />}
                            onClick={() => removeConn(row)}>删除</Button>
                  ) : null}
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title={editing ? `编辑数据源 ${editing.name}` : '新建数据源'}
        open={open}
        width={680}
        destroyOnClose
        onCancel={() => setOpen(false)}
        footer={
          <Space>
            <Button icon={<ApiOutlined />} loading={testing} onClick={() => testForm().catch((e) => message.error(errMsg(e)))}>
              测试连接
            </Button>
            <Button icon={<EyeOutlined />} onClick={() => previewForm().catch((e: any) => message.error(e.message || '请先补全表单必填项'))}>
              测试并预览结构
            </Button>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" onClick={() => submit().catch((e) => message.error(errMsg(e)))}>
              {editing ? '保存' : '创建'}
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          // 改过地址/账号/密码后，上次「连接成功」就不再可信，立即清掉
          onValuesChange={() => setTestResult(undefined)}
        >
          {editing?.builtin && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="内置 Doris"
              description="内置 Doris 由平台迁移维护（地址固定指向平台 Doris）；这里只能修改默认存储，且不能删除。"
            />
          )}
          <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入数据源名称' }]}>
            <Input placeholder="例如 doris_kb_dev" disabled={editing?.builtin} />
          </Form.Item>
          <Form.Item label="类型" name="type" rules={[{ required: true }]}>
            <Select
              disabled={editing?.builtin}
              onChange={onTypeChange}
              options={Object.entries(TYPE_META).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </Form.Item>
          {type === 'SPARK' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="Spark Thrift Server"
              description="JDBC URL 用 jdbc:hive2://host:10000/db（HiveServer2 协议）；默认不认证，密码可留空。连接测试由后端用 Kyuubi 驱动直连。"
            />
          )}
          {type === 'MYSQL' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="MySQL 版本支持"
              description="批量采集：5.6 / 5.7 / 8.0 / 8.4；CDC：5.6 / 5.7 / 8.0 / 8.4（SeaTunnel 2.3.13 起已适配 8.4）。5.5 及更早、9.x、MariaDB 未验证。详见 docs/数据集成-MySQL版本矩阵-20261020.md"
            />
          )}
          {isJdbc && (
            <Form.Item
              label={<LabelWithTip text="JDBC URL"
                tip="JDBC 连接串，格式 jdbc:方言://主机:端口/库名。Doris 填 FE 查询端口（默认 9030）、PostgreSQL 5432、MySQL 3306；地址向 DBA 或部署文档获取。" />}
              name="jdbcUrl"
              rules={[
                { required: true, message: '请输入 JDBC URL' },
                { pattern: /^jdbc:/, message: 'JDBC URL 必须以 jdbc: 开头' },
              ]}
            >
              <Input
                placeholder="jdbc:postgresql://host:5432/db"
                style={{ fontFamily: 'monospace' }}
                disabled={editing?.builtin}
              />
            </Form.Item>
          )}
          {type === 'ICEBERG' && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 12 }}
              message="Iceberg 湖：保存时自动创建 Doris Catalog"
              description="后端使用 DORIS_ADMIN_USER / DORIS_ADMIN_PASSWORD（默认 root / 空密码）执行 CREATE CATALOG，并只授予平台业务账号 SELECT_PRIV；湖表查询走 catalog.db.table。"
            />
          )}
          {!isJdbc && (CONFIG_FIELDS[type || ''] || []).map((f) => (
            <Form.Item
              key={f.key}
              label={f.tip ? <LabelWithTip text={f.label} tip={f.tip} /> : f.label}
              name={['config', f.key]}
              extra={f.hint}
              rules={f.optional ? [] : [{ required: true, message: `请输入 ${f.label}` }]}
            >
              {f.options ? (
                <Select options={f.options} />
              ) : (
                <Input placeholder={f.placeholder} />
              )}
            </Form.Item>
          ))}
          <Space style={{ width: '100%' }} size="middle">
            <Form.Item
              label={<LabelWithTip
                text={ACCESS_KEY_TYPES.has(type || '') ? 'Access Key' : '用户名'}
                tip={ACCESS_KEY_TYPES.has(type || '')
                  ? 'Access Key ID：访问对象存储（S3/MinIO/OSS）密钥对的「身份」一半，在存储控制台「访问密钥 / Service Account」生成，可多次创建；Secret Key 与它配对使用。'
                  : '登录数据库的用户名，需对目标库有读写权限；向 DBA 或数据源负责人获取。'} />}
              name="username"
              rules={isJdbc || ACCESS_KEY_TYPES.has(type || '') || type === 'CLICKHOUSE'
                ? [{ required: true }] : []}
              style={{ width: 260 }}
            >
              <Input autoComplete="off" disabled={editing?.builtin} />
            </Form.Item>
            <Form.Item
              label={<LabelWithTip
                text={(editing ? '（留空不变）' : '')
                  + (ACCESS_KEY_TYPES.has(type || '') ? 'Secret Key' : '密码')}
                tip={ACCESS_KEY_TYPES.has(type || '')
                  ? 'Secret Key：与 Access Key 配对的「秘密」一半，只在生成时显示一次，丢失只能重新创建；平台加密保存，之后不会再回显。'
                  : '数据库登录密码，平台加密保存；编辑时留空表示不修改原密码。'} />}
              name="password"
              style={{ width: 260 }}
            >
              <Input.Password
                autoComplete="new-password"
                placeholder={editing ? '不修改则留空' : ''}
                disabled={editing?.builtin}
              />
            </Form.Item>
          </Space>
          {type === 'DORIS' && (
            <>
              <Typography.Title level={5} style={{ marginTop: 4 }}>开发数仓与默认存储</Typography.Title>
              <Form.Item
                name="devWarehouse"
                valuePropName="checked"
                label={<LabelWithTip text="作为开发数仓"
                  tip="勾选＝这个 Doris 数据源作为开发数仓使用，可以继续配置默认存储（数据源方式 / 存储地址 / 默认路径）；不勾选＝仅采集，只能被同步任务引用，存储配置不可改。内置 Doris 由平台维护，默认已是开发数仓。" />}
              >
                <Switch
                  disabled={editing?.builtin}
                  onChange={(checked) => {
                    // REQ-DW-5：关掉开关就把存储配置打回「未配置」，避免留下禁用但已填的脏值
                    if (!checked) {
                      form.setFieldValue('storageType', 'none')
                      form.setFieldValue('storageEndpoint', '')
                      form.setFieldValue('storagePath', '')
                    }
                  }}
                />
              </Form.Item>
              {!devWarehouse && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="仅采集数据源"
                  description="未勾选「作为开发数仓」时，本数据源只能作为同步任务的源/目标；存储配置项已禁用，需要配存储请先勾选。"
                />
              )}
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="默认存储归属数仓数据源"
                description="项目不感知存储；该数据源被多个项目引用时，它们使用同一份默认存储。冷热分层在建表时按 storage_medium / storage_cooldown_time 配置。"
              />
              <Space size={12} style={{ display: 'flex' }}>
                <Form.Item
                  name="storageType"
                  label={<LabelWithTip text="数据源方式"
                    tip="访问开发数仓存储用哪种方式：HDFS＝Hadoop 文件系统（填 NameNode 地址）；S3＝MinIO/OSS 等兼容 S3 协议的对象存储（需 Access Key/Secret Key）；SSO＝单点登录方式接入云上存储（不再单独发密钥）。只是访问方式的选择，不影响表数据本身。" />}
                  style={{ width: 200 }}
                >
                  <Select disabled={!devWarehouse} options={[
                    { value: 'none', label: '未配置' },
                    { value: 'hdfs', label: 'HDFS' },
                    { value: 's3', label: 'S3 / MinIO / OSS' },
                    { value: 'sso', label: 'SSO（单点登录）' },
                  ]} />
                </Form.Item>
                {storageType && storageType !== 'none' && (
                  <Form.Item
                    name="storageEndpoint"
                    label={<LabelWithTip text="存储地址"
                      tip="与所选数据源方式对应的服务地址：HDFS 填 http://namenode:9870，S3 填 API 访问地址（如 http://minio:9000，不是控制台地址），SSO 填单点登录入口；向存储管理员或部署文档获取。" />}
                    style={{ flex: 1 }}
                    rules={[{ required: true, message: '请输入存储地址' }]}
                  >
                    <Input
                      disabled={!devWarehouse}
                      placeholder={storageType === 'hdfs'
                        ? 'http://namenode:9870'
                        : storageType === 'sso' ? 'https://sso.example.com' : 'http://minio:9000'}
                    />
                  </Form.Item>
                )}
              </Space>
              {storageType && storageType !== 'none' && (
                <Form.Item
                  name="storagePath"
                  label={<LabelWithTip text="默认路径"
                    tip="新建表数据文件的落盘根路径，如 HDFS 的 /warehouse、S3 的 s3://warehouse；留空跟随集群默认。" />}
                >
                  <Input
                    disabled={!devWarehouse}
                    placeholder={storageType === 'hdfs' ? '/warehouse' : 's3://warehouse'}
                  />
                </Form.Item>
              )}
            </>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            测试连接由后端 Pod 发起：集群内地址用 svc DNS，宿主机 / 外部地址用节点 IP。
          </Typography.Text>
          {testResult ? (
            <div style={{ marginTop: 12 }}>
              {testResult.ok ? (
                <Alert
                  type="success"
                  showIcon
                  message={`连接成功：${testResult.product || '数据库'} ${testResult.version || ''}`}
                  description={`耗时 ${testResult.elapsedMs ?? '-'} ms`}
                />
              ) : (
                <Alert type="error" showIcon message="连接失败" description={testResult.error || '未知错误'} />
              )}
            </div>
          ) : null}
        </Form>
      </Modal>
      {/* REQ-DW-16：与 REQ-DW-15 同一预览组件；这里传未保存的表单参数（临时试连） */}
      {!!previewReq && (
        <SourceStructurePreview
          open
          adhocRequest={previewReq}
          datasourceName={`${(previewReq.name as string) || '未命名'}（未保存）`}
          onClose={() => setPreviewReq(undefined)}
        />
      )}
    </div>
  )
}
