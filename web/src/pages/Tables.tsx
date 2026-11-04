import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, AutoComplete, Button, Card, Checkbox, Col, Descriptions, Drawer, Form, Input,
  InputNumber, Modal, Popconfirm, Row, Segmented, Select, Space, Spin, Steps, Switch, Table,
  Tabs, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  CopyOutlined, DatabaseOutlined, DeleteOutlined, EditOutlined, EyeOutlined, FileAddOutlined,
  PlusOutlined, QuestionCircleOutlined, SearchOutlined, SwapOutlined, TableOutlined,
} from '@ant-design/icons'
import { api, del, post } from '../api'
import { useDebounced } from '../components/ImeInput'
import ErrorText, { errorTip } from '../components/ErrorText'
import PageHeader from '../components/PageHeader'
import PageActions from '../components/PageActions'
import { EmptyState, ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'
import { canAny } from '../permissions'

// REQ-DW-6：business / dev_database / prod_database 用于「新建库」的命名规则与前缀灰显
type ProjectOption = {
  id: number; code: string; name: string; warehouseDatasourceId?: number
  business?: string; dev_database?: string; prod_database?: string
}
/** 第二批：元数据来源与降级标记（接口新增字段，老响应没有时按直连处理）。 */
type MetaInfo = { metadataSource?: 'gravitino' | 'doris'; degraded?: boolean; metadataCached?: boolean }
type DbRow = { database: string; tables: number }
type TableRow = { table: string; comment?: string; rows?: number }
type ColumnRow = { name: string; type: string; nullable?: boolean; key?: string; comment?: string }

type EditOperation = 'ADD_COLUMN' | 'MODIFY_COLUMN' | 'DROP_COLUMN' | 'SET_PROPERTIES'

const TYPES = [
  'BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'LARGEINT', 'FLOAT', 'DOUBLE',
  'DECIMAL(12,2)', 'DECIMAL(18,4)', 'VARCHAR(32)', 'VARCHAR(64)', 'VARCHAR(128)',
  'VARCHAR(255)', 'STRING', 'TEXT', 'DATE', 'DATETIME', 'DATETIME(3)', 'JSON',
]

const OPERATION_OPTIONS = [
  { value: 'ADD_COLUMN', label: '新增字段' },
  { value: 'MODIFY_COLUMN', label: '修改字段' },
  { value: 'DROP_COLUMN', label: '删除字段' },
  { value: 'SET_PROPERTIES', label: '表属性' },
]

// REQ-UX-FLOWLINK-1（审计§三9）：表结构变更单（table_ddl_job）的状态与操作口径，
// 与后端 V88__table_ddl_job.sql 的两个 CHECK 约束一一对应，详情弹窗与 toast 共用。
// 术语统一：这类单据在平台里的实名是「变更单」（SQL 变更页同款口径），
// 旧文案里的另一个单据叫法在用户侧没有任何对应页面，一律改成实名。
const DDL_JOB_STATUS_META: Record<string, { text: string; color: string }> = {
  PENDING_APPROVAL: { text: '待审批', color: 'gold' },
  PENDING: { text: '排队中', color: 'blue' },
  RUNNING: { text: '执行中', color: 'processing' },
  FINISHED: { text: '已完成', color: 'success' },
  FAILED: { text: '失败', color: 'error' },
  CANCELLED: { text: '已取消', color: 'default' },
  REJECTED: { text: '已驳回', color: 'error' },
}
const DDL_JOB_OPERATION_LABEL: Record<string, string> = {
  CREATE_TABLE: '建表',
  CREATE_DATABASE: '建库',
  DROP_TABLE: '删表',
  ALTER_ADD_COLUMN: '新增字段',
  ALTER_MODIFY_COLUMN: '修改字段',
  ALTER_DROP_COLUMN: '删除字段',
  ALTER_SET_PROPERTIES: '表属性',
  ALTER_RENAME_TABLE: '重命名表',
  TRUNCATE_TABLE: '清空数据',
}
/** 未终态：详情弹窗打开期间每 5 秒自动轮询（终态后停表，只留手动刷新）。 */
const DDL_JOB_ACTIVE_STATUS = ['PENDING_APPROVAL', 'PENDING', 'RUNNING']

// Doris 4.1 支持 RANGE / LIST / 动态分区；NONE = 不分区（老行为）
const PARTITION_OPTIONS = [
  { value: 'NONE', label: '不分区' },
  { value: 'RANGE', label: 'RANGE 静态' },
  { value: 'DYNAMIC', label: '动态分区' },
  { value: 'LIST', label: 'LIST' },
]

const TIME_UNIT_OPTIONS = ['HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR'].map((v) => ({ value: v, label: v }))

const MODEL_OPTIONS = [
  { value: 'DUPLICATE', label: '明细（重复）' },
  { value: 'UNIQUE', label: '唯一键（聚合结果）' },
  { value: 'AGGREGATE', label: '聚合（AGGREGATE）' },
]

// REQ-DW-6：二级索引类型白名单，与后端 TableCreateDdl.INDEX_TYPES 同一份口径。
// BloomFilter 不是 Doris 的建表索引（是表属性 bloom_filter_columns），所以这里不给选项。
const INDEX_TYPE_OPTIONS = [
  { value: 'BITMAP', label: 'BITMAP（等值/范围过滤）' },
  { value: 'INVERTED', label: 'INVERTED（全文/模糊检索）' },
  { value: 'NGRAM_BF', label: 'NGRAM_BF（n-gram 短文本）' },
]

// AGGREGATE 模型下 value 列可选的聚合类型（后端 TableCreateDdl 同步校验）
const AGG_TYPE_OPTIONS = [
  'SUM', 'REPLACE', 'MIN', 'MAX', 'REPLACE_IF_NOT_NULL', 'HLL_UNION', 'BITMAP_UNION',
  'QUANTILE_UNION',
].map((v) => ({ value: v, label: v }))

// SQL 模式 / 帮助弹窗共用的建表模板（表名带库名，复制即可执行）
const tableTemplates = (db: string) => [
  {
    key: 'log',
    label: '日志表（按天动态分区）',
    desc: '明细流水，DUPLICATE 模型 + 按天自动建分区，适合应用日志/埋点。',
    sql: `CREATE TABLE \`${db}\`.\`ods_app_log\` (
  \`log_date\` DATE NOT NULL COMMENT '日志日期',
  \`app\` VARCHAR(64) NULL COMMENT '应用名',
  \`level\` VARCHAR(16) NULL COMMENT '日志级别',
  \`msg\` STRING NULL COMMENT '日志内容',
  \`ts\` DATETIME(3) NULL COMMENT '时间'
) ENGINE=OLAP
DUPLICATE KEY(\`log_date\`, \`app\`)
COMMENT '应用日志（按天动态分区）'
PARTITION BY RANGE(\`log_date\`) ()
DISTRIBUTED BY HASH(\`app\`) BUCKETS 4
PROPERTIES (
  "replication_allocation" = "tag.location.default: 1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.buckets" = "4"
);`,
  },
  {
    key: 'order',
    label: '订单表（UNIQUE + 分桶）',
    desc: '按主键覆盖写，同一 order_id 重复导入只保留最新一条。',
    sql: `CREATE TABLE \`${db}\`.\`dwd_order\` (
  \`order_id\` BIGINT NOT NULL COMMENT '订单号',
  \`user_id\` BIGINT NULL COMMENT '用户',
  \`amount\` DECIMAL(12,2) NULL COMMENT '金额',
  \`status\` VARCHAR(16) NULL COMMENT '状态',
  \`created_at\` DATETIME(3) NULL COMMENT '创建时间',
  \`updated_at\` DATETIME(3) NULL COMMENT '更新时间'
) ENGINE=OLAP
UNIQUE KEY(\`order_id\`)
COMMENT '订单表（主键覆盖写）'
DISTRIBUTED BY HASH(\`order_id\`) BUCKETS 8
PROPERTIES ("replication_allocation" = "tag.location.default: 1");`,
  },
  {
    key: 'dim',
    label: '维表（DUPLICATE + Bitmap）',
    desc: '小表全量重灌；低基数列 category 建 Bitmap 索引加速过滤。索引只能走 SQL 模式。',
    sql: `CREATE TABLE \`${db}\`.\`dim_product\` (
  \`product_id\` BIGINT NOT NULL COMMENT '商品ID',
  \`product_name\` VARCHAR(128) NULL COMMENT '名称',
  \`category\` VARCHAR(64) NULL COMMENT '类目',
  \`brand\` VARCHAR(64) NULL COMMENT '品牌',
  \`price\` DECIMAL(12,2) NULL COMMENT '价格',
  INDEX idx_category (\`category\`) USING BITMAP COMMENT '低基数类目'
) ENGINE=OLAP
DUPLICATE KEY(\`product_id\`)
COMMENT '商品维表'
DISTRIBUTED BY HASH(\`product_id\`) BUCKETS 4
PROPERTIES ("replication_allocation" = "tag.location.default: 1");`,
  },
]

/** SQL 模式预览返回体（与 TableSqlParser.Result 对齐）。 */
type SqlParseResult = {
  ddl: string
  table?: string
  model: any
  unsupported?: string[]
  warnings?: string[]
}

/** 切回表单前发现「表单不支持」时弹的选择框。 */
type DiscardPrompt = {
  list: string[]
  model: any
  formDdl: string
  diff: boolean
}

/** 帮助弹窗：两列小表（场景 / 说明）。 */
type HelpRow = { k: string; v: string }

const MODEL_HELP: HelpRow[] = [
  { k: 'DUPLICATE（明细）', v: '不去重、不聚合，写入什么就存什么。日志、埋点、流水、维表全量重灌用它；拿不准就选它，不会丢行。' },
  { k: 'UNIQUE（唯一键）', v: '按 KEY 覆盖写（Merge-on-Write），同一主键只保留最新一条。订单、用户、商品等需要 upsert 的表。' },
  { k: 'AGGREGATE（聚合）', v: '按 KEY 预聚合，value 列写 SUM / REPLACE / MAX / HLL_UNION。适合报表汇总层，但明细不可还原。' },
  { k: 'KEY 列规则', v: 'KEY 列必须排在字段最前面，Doris 强制非空且不能设默认值；分区列、分桶列都必须取自 KEY 列。' },
]

// REQ-DW-18：选中模型后在「模型与键」步骤内联一句该模型的行为说明（与 MODEL_HELP 同口径）
const MODEL_DESC: Record<string, string> = {
  DUPLICATE: '不去重、不聚合，写入什么存什么；日志、埋点、流水拿不准就选它。',
  UNIQUE: '按 KEY 列去重覆盖写（upsert），同一主键只保留最新一条；订单、用户等结果表用它。',
  AGGREGATE: '按 KEY 预聚合，value 列要在「字段列表」里选 SUM/REPLACE/MAX 等聚合类型；适合报表汇总层，明细不可还原。',
}

const PARTITION_HELP: HelpRow[] = [
  { k: '不分区（默认）', v: '小表（几百万行以内）、维表、临时表。最简单，后续再改要重建表。' },
  { k: 'RANGE 静态', v: '自己写死每个分区的上界（VALUES LESS THAN）。分区数固定、不会持续增长的历史表。' },
  { k: '动态分区', v: '按时间自动建/删分区，适合按天增长的日志、订单。DDL 写 PARTITION BY RANGE(列) () 再加 dynamic_partition.* 属性。' },
  { k: 'LIST', v: '按枚举值分区（region / 渠道 / 租户）。只支持单列，值必须写字符串常量。' },
  { k: '共同限制', v: '分区列必须是 KEY 列且只支持单列；动态分区的列必须是 DATE / DATETIME 类型。' },
]

const DYNAMIC_HELP: HelpRow[] = [
  { k: 'dynamic_partition.time_unit', v: '分区粒度：HOUR / DAY / WEEK / MONTH / YEAR。日志按天就写 DAY。' },
  { k: 'dynamic_partition.start', v: '保留多少历史分区，必须 <= 0。-30 表示保留最近 30 个历史分区，更早的自动删。' },
  { k: 'dynamic_partition.end', v: '提前建多少个未来分区，必须 >= 0。写 3 表示总是预建未来 3 个分区。' },
  { k: 'dynamic_partition.prefix', v: '分区名前缀，只能是字母/数字/下划线。前缀 p + DAY = p20260101。' },
  { k: 'dynamic_partition.buckets', v: '单个分区的分桶数；不写就沿用表的分桶数。' },
  { k: 'create_history_partition', v: '建表时是否把 start..end 范围内的历史分区一次建出来；不写只建未来的。' },
]

const BUCKET_HELP: HelpRow[] = [
  { k: '分桶数', v: '1–64。按「单个分区数据量 ÷ 1GB」估，建议单桶 1–10GB；BE 数量少时取 BE 数的倍数，避免倾斜。' },
  { k: '分桶列', v: '必须是 KEY 列，选高基数列（订单号 / id）。分桶列太集中会让数据全压在一个桶上。' },
  { k: '副本数', v: '1–10，且不能超过当前存活 BE 数（预检会拦）。生产建议 3（对应 3 台 BE），开发环境 1 即可。' },
  { k: '副本的作用', v: '副本数 = 数据拷贝份数。副本越多读取并发越高、坏盘越安全，但写入放大和存储也按倍数增长。' },
]

const INDEX_HELP: HelpRow[] = [
  { k: 'Bitmap 索引', v: '低基数列（性别、状态、类目、渠道）的等值 / IN 过滤。占用小，建在枚举、字典列上。' },
  { k: 'BloomFilter 索引', v: '高基数列的等值查询（订单号、用户 ID）。只判断「可能存在」，不加速范围查询。' },
  { k: '倒排索引（INVERTED）', v: '文本列的等值 / 全文检索（MATCH_ALL / MATCH_ANY），日志、标题搜索用它。' },
  { k: 'NGram 索引', v: 'LIKE %关键词% 的模糊匹配，默认 2–4 gram。中文按字符切，会明显增加存储与写入开销。' },
  { k: '怎么加', v: '表单模式暂不支持索引；切到 SQL 模式，在字段列表末尾加 INDEX idx_name (列) USING BITMAP / BLOOMFILTER / INVERTED / NGRAM_BF。' },
]

/** 7 条预检：与 TableCreateDdl 的校验一一对应。 */
const PRECHECK_HELP: HelpRow[] = [
  { k: '1. 副本数超过存活 BE 数', v: '提示「副本数 N 超过当前 Doris 存活 BE 数 M」。调低副本数，或先恢复 BE 节点。' },
  { k: '2. 分桶列不是 KEY 列', v: '提示「分桶列必须是 key 列」。把该列加入主键，或换一个 key 列分桶。' },
  { k: '3. KEY 列写了聚合类型 / 默认值', v: 'key 列的聚合类型会被忽略；key 列设默认值直接 400（Doris sort key 不允许）。' },
  { k: '4. 类型不符合白名单', v: 'VARCHAR 长度必须 1–65533；DECIMAL 精度 <= 38 且小数位 <= 精度；不支持 ARRAY/MAP 等复杂类型。' },
  { k: '5. 分区列不是 KEY 列', v: '提示「分区列必须是 key 列」。Doris 要求分区列来自主键。' },
  { k: '6. 表名或字段名是保留字', v: 'select / order / table / key 等保留字会被拦，请加下划线或换名。' },
  { k: '7. 动态分区参数不成组或不合法', v: 'time_unit 必填、start <= 0 <= end、prefix 只能是字母/数字/下划线，否则 400。' },
]

const helpColumns = [
  { title: '场景', dataIndex: 'k', key: 'k', width: 240 },
  { title: '说明 / 怎么选', dataIndex: 'v', key: 'v' },
]

const diffPreStyle = {
  margin: '6px 0 12px', padding: 8, maxHeight: 180, overflow: 'auto',
  fontSize: 12, lineHeight: 1.5, background: 'rgba(0,0,0,0.03)',
  borderRadius: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
} as const

const enc = encodeURIComponent

/** 标识符加反引号；库表名来自元数据，正常不含反引号，这里仍做一次转义。 */
const quoteIdent = (value: string) => '`' + value.replace(/`/g, '``') + '`'

/** 表详情「如何查询」的示例 SQL：有字段时给一条带 where 的写法。 */
function tableSelectSql(database: string, table: string, column?: string) {
  const from = `${quoteIdent(database)}.${quoteIdent(table)}`
  return column
    ? `select *\nfrom ${from}\nwhere ${quoteIdent(column)} is not null\nlimit 100`
    : `select *\nfrom ${from}\nlimit 100`
}

/** http 页面没有 navigator.clipboard，退回 execCommand 才能真的复制成功。 */
async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch {
      ok = false
    }
    document.body.removeChild(area)
    return ok
  }
}

const sqlBlockStyle = {
  margin: '6px 0 0',
  padding: 8,
  fontSize: 12,
  lineHeight: 1.6,
  background: 'rgba(0,0,0,0.03)',
  borderRadius: 4,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
} as const

// BUG-TC6-15：建表弹窗草稿。关闭（取消/ESC/点遮罩）但没提交时把表单值存进 sessionStorage，
// 重开同一库的建表弹窗可以恢复；只活在本次浏览器会话，不落服务端、不跨标签页。
const CREATE_DRAFT_PREFIX = 'tables.create.draft.'

type CreateDraft = {
  values: any
  sqlText: string
  mode: 'FORM' | 'SQL'
  /** 便于提示条显示「上次保留时间」，不是判定条件 */
  savedAt: string
}

const createDraftKey = (database?: string) => `${CREATE_DRAFT_PREFIX}${database || '-'}`

/** 读写都吞异常：隐私模式/配额满时草稿只是便利功能，不能因此挡住建表。 */
const readCreateDraft = (database?: string): CreateDraft | null => {
  try {
    const raw = sessionStorage.getItem(createDraftKey(database))
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || !parsed.values) return null
    return parsed as CreateDraft
  } catch {
    return null
  }
}

const writeCreateDraft = (database: string | undefined, draft: CreateDraft) => {
  try {
    sessionStorage.setItem(createDraftKey(database), JSON.stringify(draft))
  } catch {
    /* 存不下就放弃草稿，不影响关闭弹窗 */
  }
}

const clearCreateDraft = (database?: string) => {
  try {
    sessionStorage.removeItem(createDraftKey(database))
  } catch {
    /* 同上 */
  }
}

// V100：Iceberg 列类型（与后端 LakeDdl 的白名单一致；不含 Doris 特有的 TEXT/DATETIME）
const LAKE_TYPES = [
  'BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'FLOAT', 'DOUBLE',
  'DECIMAL(12,2)', 'DECIMAL(18,4)', 'DATE', 'TIMESTAMP', 'STRING',
  'VARCHAR(32)', 'VARCHAR(64)', 'VARCHAR(255)', 'BINARY',
]

type LakeCatalogRow = {
  datasourceId: number
  projectId: number
  projectCode?: string
  name: string
  catalog: string
  catalogType?: string
  warehouse?: string
  endpoint?: string
  status?: string
  statusMessage?: string
}

type LakeTableRow = { table: string; type?: string | null; comment?: string | null; rows?: string | number | null }

/** 建表请求体：表单和预览共用，避免两边字段漂移。 */
function lakePayload(values: any) {
  const partition = values?.partition || {}
  return {
    table: values?.table,
    comment: values?.comment,
    columns: (values?.columns || []).map((c: any) => ({
      name: c?.name,
      type: c?.type,
      nullable: c?.nullable !== false,
      comment: c?.comment,
    })),
    partition: {
      kind: partition.kind || 'NONE',
      column: partition.column,
      buckets: partition.buckets,
    },
    formatVersion: values?.formatVersion || 2,
  }
}

/**
 * V100：Iceberg 湖表浏览/建表。
 *
 * 与 Doris 内表分开：内表走 /api/tables，湖表走 /api/lake/catalogs/{catalog}/databases/...；
 * 这样「湖 Catalog 里的库」不会被误当成 Doris 内库，建表也不会套用副本/分桶概念。
 */
/** TC-8 界面卫生：Doris / 湖表两个列表的「结构」入口只保留一个定义点；
 *  权限、行对象与点击行为由调用方传入，渲染逻辑不变。 */
function StructLinkButton({ row, onOpen }: { row: any; onOpen: (row: any) => void }) {
  return (
    <Button size="small" type="link" icon={<EyeOutlined />} onClick={() => onOpen(row)}>
      结构
    </Button>
  )
}

function LakeTablesPanel({ projectId }: { projectId?: number }) {
  const canDdl = canAny('table:ddl')
  const canClassify = canAny('data:classify')
  const [catalogs, setCatalogs] = useState<LakeCatalogRow[]>([])
  const [catalog, setCatalog] = useState<string>()
  const [databases, setDatabases] = useState<string[]>([])
  const [database, setDatabase] = useState<string>()
  const [tables, setTables] = useState<LakeTableRow[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<any>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [createOpen, setCreateOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [ddlPreview, setDdlPreview] = useState('')
  const [previewWarnings, setPreviewWarnings] = useState<string[]>([])
  const [dbOpen, setDbOpen] = useState(false)
  const [dbName, setDbName] = useState('')
  const [registerTarget, setRegisterTarget] = useState<LakeTableRow | null>(null)
  // BUG-TC6-5②：湖表删除确认强度对齐 Doris 表——手打表名（后端本就校验 confirm=表名，
  // 此前前端自动把表名塞进 URL，闸门形同虚设）。
  const [lakeDropTarget, setLakeDropTarget] = useState<LakeTableRow | null>(null)
  const [lakeDropConfirm, setLakeDropConfirm] = useState('')
  const [registerLevel, setRegisterLevel] = useState('L2')
  const [registerOwner, setRegisterOwner] = useState('')
  const [createForm] = Form.useForm()
  const [refreshTick, setRefreshTick] = useState(0)
  const columnsWatch = (Form.useWatch('columns', createForm) || []) as any[]
  const partitionKind = Form.useWatch(['partition', 'kind'], createForm) as string | undefined

  const loadCatalogs = async () => {
    setError('')
    try {
      const rows = await api<LakeCatalogRow[]>('/api/lake/catalogs')
      const list = Array.isArray(rows) ? rows : []
      setCatalogs(list)
      const preferred = list.find((row) => row.projectId === projectId) || list[0]
      if (!catalog || !list.some((row) => row.catalog === catalog)) {
        setCatalog(preferred?.catalog)
      }
    } catch (e: any) {
      setError(e?.message || '加载湖 Catalog 失败')
      setCatalogs([])
    }
  }

  useEffect(() => { loadCatalogs() }, [projectId])

  useEffect(() => {
    setDatabases([])
    setDatabase(undefined)
    setTables([])
    if (!catalog) return
    let alive = true
    api<any>(`/api/lake/catalogs/${enc(catalog)}/databases`)
      .then((res) => {
        if (!alive) return
        const list = (res?.databases || []).map((d: any) => d.database).filter(Boolean)
        setDatabases(list)
        setDatabase(list[0])
      })
      .catch((e) => {
        if (!alive) return
        setError(e?.message || '加载湖库失败')
      })
    return () => { alive = false }
  }, [catalog, refreshTick])

  useEffect(() => {
    setTables([])
    if (!catalog || !database) return
    let alive = true
    setLoading(true)
    setError('')
    // count=true：湖表行数需要 COUNT(*)（后端最多统计 50 张，失败为 null）
    api<any>(`/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables?count=true`)
      .then((res) => {
        if (!alive) return
        setTables(Array.isArray(res?.tables) ? res.tables : [])
      })
      .catch((e) => {
        if (!alive) return
        setError(e?.message || '加载湖表失败')
      })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [catalog, database])

  const openDetail = async (row: LakeTableRow) => {
    if (!catalog || !database) return
    setDetailOpen(true)
    setDetail(null)
    setDetailLoading(true)
    try {
      const data = await api<any>(
        `/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables/${enc(row.table)}`)
      setDetail(data)
    } catch (e: any) {
      message.error(e?.message || '读取湖表结构失败')
    } finally {
      setDetailLoading(false)
    }
  }

  const openCreate = () => {
    createForm.resetFields()
    setDdlPreview('')
    setPreviewWarnings([])
    createForm.setFieldsValue({
      formatVersion: 2,
      columns: [{ name: 'id', type: 'BIGINT', nullable: false }],
      partition: { kind: 'NONE' },
    })
    setCreateOpen(true)
  }

  const previewDdl = async () => {
    if (!catalog || !database) return
    try {
      const values = await createForm.validateFields()
      const res = await post<any>(
        `/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables/preview`,
        lakePayload(values))
      setDdlPreview(res?.ddl || '')
      setPreviewWarnings(Array.isArray(res?.warnings) ? res.warnings : [])
    } catch (e: any) {
      message.error(e?.message || 'DDL 预览失败')
    }
  }

  const createTable = async () => {
    if (!catalog || !database) return
    let values: any
    try {
      values = await createForm.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      const res = await post<any>(
        `/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables`,
        lakePayload(values))
      setDdlPreview(res?.ddl || '')
      setCreateOpen(false)
      message.success(`湖表 ${database}.${res?.table || values.table} 已创建，并已纳管（${res?.dataLevel || 'L2'}）`)
      // 重新加载表列表
      const list = await api<any>(
        `/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables?count=true`)
      setTables(Array.isArray(list?.tables) ? list.tables : [])
    } catch (e: any) {
      message.error(e?.message || '建表失败')
    } finally {
      setSaving(false)
    }
  }

  const createDatabase = async () => {
    if (!catalog || !dbName.trim()) return
    setSaving(true)
    try {
      await post(`/api/lake/catalogs/${enc(catalog)}/databases`,
        { database: dbName.trim() })
      message.success('湖库已创建')
      setDbOpen(false)
      setDbName('')
      const res = await api<any>(`/api/lake/catalogs/${enc(catalog)}/databases`)
      const list = (res?.databases || []).map((d: any) => d.database).filter(Boolean)
      setDatabases(list)
      setDatabase(dbName.trim() || list[0])
    } catch (e: any) {
      message.error(e?.message || '建库失败')
    } finally {
      setSaving(false)
    }
  }

  const dropTable = async (row: LakeTableRow) => {
    if (!catalog || !database) return
    try {
      await del(`/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables/${enc(row.table)}`
        + `?confirm=${enc(row.table)}`)
      message.success('湖表已删除')
      setTables((prev) => prev.filter((item) => item.table !== row.table))
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    }
  }

  const registerTable = async () => {
    if (!catalog || !database || !registerTarget) return
    setSaving(true)
    try {
      const res = await post<any>(
        `/api/lake/catalogs/${enc(catalog)}/databases/${enc(database)}/tables/${enc(registerTarget.table)}/register`,
        { dataLevel: registerLevel, ownerUser: registerOwner || undefined })
      message.success(`已登记纳管（${res?.dataLevel || registerLevel}），可在数据分级里配置列级 L3/L4`)
      setRegisterTarget(null)
    } catch (e: any) {
      message.error(e?.message || '登记失败')
    } finally {
      setSaving(false)
    }
  }

  const current = catalogs.find((row) => row.catalog === catalog)

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="Iceberg 湖表（Doris 外部 Catalog）"
        description="湖表用 catalog.db.table 查询；建表只生成 Iceberg DDL（列 + identity/bucket/day 分区），不写 Doris 的 DISTRIBUTED/BUCKETS/副本。建表成功后自动按 L2 纳管，敏感列请到数据分级配置 L3/L4 与 MASK/TOKEN。"
      />
      <Space wrap style={{ marginBottom: 12 }}>
        <Select
          style={{ width: 280 }}
          placeholder="选择湖 Catalog"
          value={catalog}
          onChange={setCatalog}
          options={catalogs.map((row) => ({
            value: row.catalog,
            label: `${row.catalog}（${row.catalogType || 'iceberg'}）`,
          }))}
        />
        <Select
          style={{ width: 220 }}
          placeholder="选择湖库"
          value={database}
          onChange={setDatabase}
          options={databases.map((db) => ({ value: db, label: db }))}
        />
        {current && (
          <Tag color={current.status === 'available' ? 'green'
            : current.status === 'error' ? 'red' : 'default'}>
            {current.status === 'available' ? '可用' : current.status === 'error' ? '异常' : '未检测'}
          </Tag>
        )}
        {/* D37：湖表面板同样走标准操作区（刷新/新建库/新建表） */}
        <PageActions
          refresh={{ onClick: async () => { setRefreshTick((v) => v + 1); await loadCatalogs() } }}
          create={[
            { label: '新建湖库', icon: <DatabaseOutlined />, type: 'default', disabled: !catalog, onClick: () => setDbOpen(true), show: canDdl },
            { label: '新建湖表', onClick: openCreate, disabled: !catalog || !database, show: canDdl },
          ]}
        />
      </Space>

      {error ? (
        <ErrorState title="湖表加载失败" message={<ErrorText error={error} />}
                    onRetry={() => loadCatalogs()} />
      ) : (
        <Table<LakeTableRow>
          rowKey="table"
          size="small"
          loading={loading}
          dataSource={tables}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          onRow={(row) => ({
            style: { cursor: 'pointer' },
            onClick: (event) => {
              // UX-DW10REST-9（REQ-DW-10 R1）：点行=开湖表结构弹窗；行内控件保持自身行为
              const target = event.target as HTMLElement
              if (target.closest('a, button, input, textarea, .ant-select')) return
              openDetail(row)
            },
          })}
          locale={{ emptyText: <EmptyState title="这个湖库还没有表" hint="点右上角「新建湖表」开始" /> }}
          columns={[
            {
              title: '表名', dataIndex: 'table', key: 'table',
              render: (value: string) => (
                <Space size={6}>
                  <TableOutlined />
                  {value}
                  <Tag color="lime">Iceberg</Tag>
                </Space>
              ),
            },
            { title: '类型', dataIndex: 'type', key: 'type', width: 140, render: (v: string) => v || '-' },
            { title: '行数', dataIndex: 'rows', key: 'rows', width: 120, render: (v: any) => v ?? '-' },
            {
              title: '操作', key: 'actions', width: 330,
              render: (_: any, row: LakeTableRow) => (
                <Space size={4} wrap>
                  <StructLinkButton row={row} onOpen={openDetail} />
                  {canClassify && (
                    <Button size="small" type="link"
                            onClick={() => { setRegisterTarget(row); setRegisterLevel('L2'); setRegisterOwner('') }}>
                      登记纳管
                    </Button>
                  )}
                  <PageActions size="small" buttonType="link"
                               delete={{
                                 title: '删除 Iceberg 表元数据；底层文件按 Iceberg 规则清理',
                                 onClick: () => { setLakeDropTarget(row); setLakeDropConfirm('') },
                                 show: canDdl,
                               }} />
                </Space>
              ),
            },
          ]}
        />
      )}

      <Modal open={detailOpen} title={detail ? `${detail.catalog}.${detail.database}.${detail.table}` : '湖表结构'}
             width={900} footer={<Button onClick={() => setDetailOpen(false)}>关闭</Button>}
             onCancel={() => setDetailOpen(false)}>
        {detailLoading ? <Spin /> : detail ? (
          <>
            <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="位置">{detail.location || '-'}</Descriptions.Item>
              <Descriptions.Item label="行数">{detail.rows ?? '未知（大表 COUNT 可能超时）'}</Descriptions.Item>
              <Descriptions.Item label="分区" span={2}>
                {detail.partition?.available
                  ? `${detail.partition.source}：${(detail.partition.partitions || []).slice(0, 20)
                    .map((p: any) => typeof p === 'string' ? p : (p.partition || '-')).join('、') || '-'}`
                  : (detail.partition?.reason || '当前版本拿不到分区信息')}
              </Descriptions.Item>
            </Descriptions>
            <Table size="small" rowKey="name" pagination={false}
                   dataSource={detail.columns || []}
                   locale={{ emptyText: tableEmpty('这个湖表没有字段信息', '点「刷新」重新读取元数据') }}
                   columns={[
                     { title: '字段', dataIndex: 'name' },
                     { title: '类型', dataIndex: 'type' },
                     { title: '可空', dataIndex: 'nullable', width: 80,
                       render: (v: boolean) => (v ? '是' : '否') },
                     { title: '注释', dataIndex: 'comment', render: (v: string) => v || '-' },
                   ]} />
            {(detail.warnings || []).length > 0 && (
              <Alert style={{ marginTop: 12 }} type="warning" showIcon
                     message={(detail.warnings || []).join('；')} />
            )}
            {detail.ddl && (
              <Card size="small" title="DDL" style={{ marginTop: 12 }}>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{detail.ddl}</pre>
              </Card>
            )}
          </>
        ) : null}
      </Modal>

      <Modal
        open={!!lakeDropTarget} title={`删除湖表 ${lakeDropTarget?.table || ''}`}
        onCancel={() => setLakeDropTarget(null)}
        okText="删除" okButtonProps={{ danger: true, disabled: lakeDropConfirm !== lakeDropTarget?.table }}
        onOk={async () => {
          if (!lakeDropTarget) return
          await dropTable(lakeDropTarget)
          setLakeDropTarget(null)
          setLakeDropConfirm('')
        }}
      >
        <Typography.Paragraph>
          删除不可恢复（确认强度与 Doris 内表一致，BUG-TC6-5②）。
          请输入表名 <Typography.Text code>{lakeDropTarget?.table}</Typography.Text> 确认。
        </Typography.Paragraph>
        <Input value={lakeDropConfirm} onChange={(e) => setLakeDropConfirm(e.target.value)}
               placeholder={lakeDropTarget?.table} />
      </Modal>

      <Modal open={createOpen} title="新建 Iceberg 湖表" width={1000}
             onCancel={() => setCreateOpen(false)}
             footer={[
               <Button key="cancel" onClick={() => setCreateOpen(false)}>取消</Button>,
               <Button key="preview" onClick={() => previewDdl()}>预览 DDL</Button>,
               <Button key="ok" type="primary" loading={saving} onClick={() => createTable()}>创建表</Button>,
             ]}>
        <Form form={createForm} layout="vertical">
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item label="库" style={{ flex: 1 }}>
              <Input value={database} disabled />
            </Form.Item>
            <Form.Item name="table" label="表名" style={{ flex: 1 }}
                        rules={[{ required: true, message: '请输入表名' },
                          { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '字母或下划线开头' }]}>
              <Input placeholder="例如 ods_orders" />
            </Form.Item>
            <Form.Item name="formatVersion" label="format-version" style={{ width: 160 }}>
              <Select options={[{ value: 1, label: '1' }, { value: 2, label: '2（推荐）' }]} />
            </Form.Item>
          </Space>
          <Form.Item name="comment" label="表注释（不写入 DDL，仅随接口返回）">
            <Input />
          </Form.Item>

          <Typography.Title level={5}>字段</Typography.Title>
          <Form.List name="columns">
            {(fields, { add, remove }) => (
              <>
                <Space style={{ display: 'flex', marginBottom: 8 }}>
                  <Button size="small" icon={<PlusOutlined />} onClick={() => add({ type: 'STRING', nullable: true })}>
                    新增字段
                  </Button>
                </Space>
                {fields.map((field) => (
                  <Space key={field.key} size={8} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
                    <Form.Item name={[field.name, 'name']} rules={[{ required: true, message: '字段名' }]}
                               style={{ width: 200 }}>
                      <Input placeholder="字段名" />
                    </Form.Item>
                    <Form.Item name={[field.name, 'type']} rules={[{ required: true, message: '类型' }]}
                               style={{ width: 180 }}>
                      <Select showSearch options={LAKE_TYPES.map((v) => ({ value: v, label: v }))} />
                    </Form.Item>
                    <Form.Item name={[field.name, 'nullable']} valuePropName="checked">
                      <Checkbox>可空</Checkbox>
                    </Form.Item>
                    <Form.Item name={[field.name, 'comment']} style={{ width: 260 }}>
                      <Input placeholder="注释（可选）" />
                    </Form.Item>
                    <Button size="small" type="link" danger onClick={() => remove(field.name)}>删除</Button>
                  </Space>
                ))}
              </>
            )}
          </Form.List>

          <Typography.Title level={5}>分区</Typography.Title>
          <Space size={12} style={{ display: 'flex' }} align="baseline">
            <Form.Item name={['partition', 'kind']} label="分区方式" style={{ width: 220 }}>
              <Select options={[
                { value: 'NONE', label: '不分区' },
                { value: 'IDENTITY', label: 'identity（按列值）' },
                { value: 'BUCKET', label: 'bucket（哈希分桶）' },
                { value: 'DAY', label: 'day（按天，DATE/TIMESTAMP）' },
              ]} />
            </Form.Item>
            {partitionKind && partitionKind !== 'NONE' && (
              <Form.Item name={['partition', 'column']} label="分区列"
                         rules={[{ required: true, message: '请选择分区列' }]} style={{ width: 220 }}>
                <Select options={columnsWatch
                  .filter((c: any) => c?.name)
                  .map((c: any) => ({ value: c.name, label: `${c.name}（${c.type}）` }))} />
              </Form.Item>
            )}
            {partitionKind === 'BUCKET' && (
              <Form.Item name={['partition', 'buckets']} label="分桶数" initialValue={8}
                         style={{ width: 140 }}>
                <InputNumber min={1} max={128} style={{ width: '100%' }} />
              </Form.Item>
            )}
          </Space>
          {ddlPreview && (
            <Card size="small" title="DDL 预览"
                  extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    不含 DISTRIBUTED / BUCKETS / 副本
                  </Typography.Text>}>
              <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{ddlPreview}</pre>
              {previewWarnings.length > 0 && (
                <Alert style={{ marginTop: 8 }} type="warning" showIcon message={previewWarnings.join('；')} />
              )}
            </Card>
          )}
        </Form>
      </Modal>

      <Modal open={dbOpen} title="新建湖库" onCancel={() => setDbOpen(false)}
             onOk={createDatabase} confirmLoading={saving} okText="创建">
        <Input placeholder="库名，例如 ods" value={dbName}
               onChange={(e) => setDbName(e.target.value)} />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          在 {catalog || '-'} 下创建 Iceberg 命名空间；只允许字母/数字/下划线。
        </Typography.Text>
      </Modal>

      <Modal open={!!registerTarget} title={`登记纳管 ${registerTarget?.table || ''}`}
             onCancel={() => setRegisterTarget(null)} confirmLoading={saving}
             onOk={registerTable} okText="登记">
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              catalog = {catalog}，schema = {database}；登记后可在「数据安全 → 数据分级」配置列级 L3/L4 与 MASK/TOKEN。
            </Typography.Text>
          </div>
          <Select value={registerLevel} onChange={setRegisterLevel}
                  options={[
                    { value: 'L1', label: 'L1 公开' },
                    { value: 'L2', label: 'L2 内部' },
                    { value: 'L3', label: 'L3 敏感' },
                    { value: 'L4', label: 'L4 机密' },
                  ]} />
          <Input placeholder="负责人（可选，默认当前用户）" value={registerOwner}
                 onChange={(e) => setRegisterOwner(e.target.value)} />
        </Space>
      </Modal>
    </div>
  )
}

export default function Tables() {
  const nav = useNavigate()
  const canDdl = canAny('table:ddl')
  const canQuery = canAny('query:run')
  const [projects, setProjects] = useState<ProjectOption[]>([])
  const [projectId, setProjectId] = useState<number | undefined>(undefined)
  /** V100：Doris 内表 / Iceberg 湖表两种浏览口径；湖表走 /api/lake/**。 */
  const [storageMode, setStorageMode] = useState<'doris' | 'lake'>('doris')
  // 025：所选项目的数据源是否配置了冷存储（null=未知/未加载）
  const [coldAvailable, setColdAvailable] = useState<boolean | null>(null)
  const [databases, setDatabases] = useState<DbRow[]>([])
  const [database, setDatabase] = useState<string | undefined>(undefined)
  const [tables, setTables] = useState<TableRow[]>([])
  const [loading, setLoading] = useState(false)
  // 加载失败必须和「真的没有表」区分开：之前两者共用空态，API 挂了也显示"还没有表"
  const [dbError, setDbError] = useState('')
  const [tablesError, setTablesError] = useState('')
  // 第二批：Gravitino 不可用时后端自动回退直连 Doris，页面显示「元数据降级」
  const [meta, setMeta] = useState<MetaInfo>({})

  // REQ-UX-FLOWLINK-1（审计§三9）：表结构变更单详情弹窗。table_ddl_job 此前只有
  // 接口没有任何页面——toast 报单据号后无处可查=死链。下面挂既有
  // GET /api/tables/ddl-jobs/{id} + /{id}/events 两个只读接口；审批/驳回入口不在
  // 本卡范围（不扩权，另行立项）。
  const [ddlJobId, setDdlJobId] = useState<number | null>(null)
  const [ddlJob, setDdlJob] = useState<any>(null)
  const [ddlJobEvents, setDdlJobEvents] = useState<any[]>([])
  const [ddlJobError, setDdlJobError] = useState('')
  const ddlOpenId = useRef<number | null>(null)
  const ddlTimer = useRef<number | null>(null)
  const loadDdlJob = async (id: number, silent = false) => {
    try {
      const [job, evs] = await Promise.all([
        api<any>(`/api/tables/ddl-jobs/${id}`),
        api<any[]>(`/api/tables/ddl-jobs/${id}/events`).catch(() => []),
      ])
      if (ddlOpenId.current !== id) return // 弹窗已关/已切单：丢弃过期响应
      setDdlJob(job)
      setDdlJobEvents(Array.isArray(evs) ? evs : [])
      setDdlJobError('')
      if (ddlTimer.current !== null) window.clearTimeout(ddlTimer.current)
      if (DDL_JOB_ACTIVE_STATUS.includes(String(job?.status))) {
        ddlTimer.current = window.setTimeout(() => void loadDdlJob(id, true), 5000)
      }
    } catch (e: any) {
      if (ddlOpenId.current === id && !silent) setDdlJobError(e?.message || '加载变更单失败')
    }
  }
  useEffect(() => {
    ddlOpenId.current = ddlJobId
    if (!ddlJobId) {
      setDdlJob(null)
      setDdlJobEvents([])
      setDdlJobError('')
      return undefined
    }
    void loadDdlJob(ddlJobId)
    return () => {
      if (ddlTimer.current !== null) window.clearTimeout(ddlTimer.current)
    }
  }, [ddlJobId])
  const fmtDdlTime = (v?: string | null) => (v ? String(v).replace('T', ' ').slice(0, 19) : '-')

  const [createOpen, setCreateOpen] = useState(false)
  const [dbOpen, setDbOpen] = useState(false)
  const [dbName, setDbName] = useState('')
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm()

  // REQ-DW-6：建表单页化——基本信息/字段/索引/分区/DDL 预览同页，没有「下一步」
  const [previewDdl, setPreviewDdl] = useState('')
  const [previewWarnings, setPreviewWarnings] = useState<string[]>([])
  const [previewError, setPreviewError] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewTick, setPreviewTick] = useState(0)

  // 建表模式：表单 / SQL。两者共用同一份结构化 model，切换不重置已填内容。
  const [createMode, setCreateMode] = useState<'FORM' | 'SQL'>('FORM')
  const [sqlText, setSqlText] = useState('')
  const [sqlPreview, setSqlPreview] = useState<SqlParseResult | null>(null)
  // 已经解析过的 SQL 文本：用来判断「SQL 改过没重新解析」
  const [sqlPreviewedText, setSqlPreviewedText] = useState('')
  const [sqlPreviewError, setSqlPreviewError] = useState('')
  const [sqlLoading, setSqlLoading] = useState(false)
  // 表单在 SQL 模式之后有没有再改过：改过才用表单值覆盖 SQL 文本，没改就保留用户编辑
  const [createFormDirty, setCreateFormDirty] = useState(false)
  // 切回表单前「表单不支持」的选择框（保留 SQL / 丢弃后切回）
  const [discardPrompt, setDiscardPrompt] = useState<DiscardPrompt | null>(null)
  // BUG-TC6-15：重开建表弹窗时发现的会话草稿（提示恢复 / 放弃）
  const [draftPrompt, setDraftPrompt] = useState<CreateDraft | null>(null)
  const [helpOpen, setHelpOpen] = useState(false)

  const [detailOpen, setDetailOpen] = useState(false)
  const [detail, setDetail] = useState<any>(null)
  // 加载失败必须单独记录：否则抽屉会一直停在「加载中…」，用户以为接口没返回
  const [detailError, setDetailError] = useState('')
  const [detailTarget, setDetailTarget] = useState<{ database: string; table: string } | null>(null)
  const [preview, setPreview] = useState<any>(null)
  const [previewLoadError, setPreviewLoadError] = useState('')
  const [dropTarget, setDropTarget] = useState<TableRow | null>(null)
  const [dropConfirm, setDropConfirm] = useState('')
  // BUG-TC6-14：批量删表（仅 Doris 内表列表）。后端逐项复用单项 drop 的闸门与审计，
  // 一次性总确认 = 手打全部 `库.表` 清单；上限 100 与后端一致。
  const [selectedTables, setSelectedTables] = useState<string[]>([])
  const [bulkDropOpen, setBulkDropOpen] = useState(false)
  const [bulkDropConfirm, setBulkDropConfirm] = useState('')
  const [bulkBusy, setBulkBusy] = useState(false)
  // WIRING-TC13-1（BUG-TC1-3① 界面侧）：删库入口——后端 DELETE /api/tables/databases/{db}
  // 早已就绪但只有回归脚本在用，测试残留库在页面上清不掉；确认强度对齐删表（手打库名）。
  const [dropDbTarget, setDropDbTarget] = useState<string | null>(null)
  const [dropDbConfirm, setDropDbConfirm] = useState('')

  // ---------------------------------------------------------------- 表编辑
  const [editOpen, setEditOpen] = useState(false)
  const [editTarget, setEditTarget] = useState('')
  const [editColumns, setEditColumns] = useState<ColumnRow[]>([])
  const [operation, setOperation] = useState<EditOperation>('ADD_COLUMN')
  const [precheck, setPrecheck] = useState<any>(null)
  const [prechecking, setPrechecking] = useState(false)
  const [editSaving, setEditSaving] = useState(false)
  const [editForm] = Form.useForm()
  const [editVersion, setEditVersion] = useState(0)
  const [ackWarnings, setAckWarnings] = useState(false)
  const dropColumnConfirm = Form.useWatch(['drop', 'confirm'], editForm) || ''

  // ---------------------------------------------------------------- 重命名/清空
  const [renameTarget, setRenameTarget] = useState<TableRow | null>(null)
  const [renameName, setRenameName] = useState('')
  const [renameConfirm, setRenameConfirm] = useState('')
  const [renameReason, setRenameReason] = useState('')
  const [truncateTarget, setTruncateTarget] = useState<TableRow | null>(null)
  const [truncateConfirm, setTruncateConfirm] = useState('')
  const [truncateReason, setTruncateReason] = useState('')
  const [dangerSaving, setDangerSaving] = useState(false)

  const q = projectId == null ? '' : `?projectId=${projectId}`
  const metaSep = q ? '&' : '?'
  const editProd = !!database && database.toLowerCase().endsWith('_prd')

  /** 兼容两种响应：老接口是数组；withMeta=true 时是 {数据..., metadataSource, degraded}。 */
  const applyMeta = (payload: any) => {
    if (payload && !Array.isArray(payload)) {
      setMeta({
        metadataSource: payload.metadataSource,
        degraded: !!payload.degraded,
        metadataCached: !!payload.metadataCached,
      })
    }
  }

  useEffect(() => {
    api<ProjectOption[]>('/api/projects')
      .then((list) => setProjects(Array.isArray(list) ? list : []))
      .catch(() => setProjects([]))
  }, [])

  const loadDatabases = async () => {
    setDbError('')
    try {
      const payload = await api<any>(`/api/tables/databases${q}${metaSep}withMeta=true`)
      const rows: DbRow[] = Array.isArray(payload)
        ? payload
        : (Array.isArray(payload?.databases) ? payload.databases : [])
      applyMeta(payload)
      setDatabases(rows)
      if (!database && rows.length) {
        setDatabase(rows[0].database)
      } else if (database && !rows.some((r) => r.database === database)) {
        setDatabase(rows[0]?.database)
      }
    } catch (e: any) {
      setDbError(e?.message || '加载库列表失败')
      setDatabases([])
      message.error(e?.message || '加载库列表失败')
    }
  }

  const loadTables = async () => {
    if (!database) {
      setTables([])
      return
    }
    setLoading(true)
    setTablesError('')
    try {
      const sep = q ? '&' : '?'
      const payload = await api<any>(
        `/api/tables${q}${sep}database=${enc(database)}&withMeta=true`)
      const rows: TableRow[] = Array.isArray(payload)
        ? payload
        : (Array.isArray(payload?.tables) ? payload.tables : [])
      applyMeta(payload)
      setTables(rows)
    } catch (e: any) {
      setTablesError(e?.message || '加载表列表失败')
      setTables([])
      message.error(e?.message || '加载表列表失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadDatabases() }, [projectId])
  useEffect(() => {
    const project = projects.find((p) => p.id === projectId)
    const datasourceId = project?.warehouseDatasourceId
    if (!datasourceId) {
      setColdAvailable(null)
      return
    }
    api<any[]>('/api/datasources?type=DORIS')
      .then((list) => {
        const ds = (list || []).find((d: any) => d.id === datasourceId)
        const type = ds?.storage?.type
        setColdAvailable(type ? type !== 'none' : null)
      })
      .catch(() => setColdAvailable(null))
  }, [projectId, projects])
  useEffect(() => { loadTables() }, [database, projectId])

  const openDetail = async (row: TableRow, db?: string) => {
    const targetDb = db || database
    if (!targetDb) return
    setPreview(null)
    setPreviewLoadError('')
    setDetail(null)
    setDetailError('')
    setDetailTarget({ database: targetDb, table: row.table })
    setDetailOpen(true)
    try {
      const data = await api<any>(
        `/api/tables/${enc(targetDb)}/${enc(row.table)}${q}`)
      // 表结构响应同样带 metadataSource/degraded（纯新增字段）
      applyMeta(data)
      setDetail(data)
    } catch (e: any) {
      setDetailError(e?.message || '读取表结构失败')
    }
  }

  const loadPreview = async () => {
    if (!detail) return
    // 预览走 POST /api/tables/{db}/{t}/preview，后端要 query:run（不是 table:ddl）；
    // 这里再挡一道，避免「重试」按钮等非按钮入口绕过裁剪后撞 403。
    if (!canQuery) {
      message.warning('当前账号没有查询权限（query:run），无法预览数据；请先提交权限申请或联系管理员')
      return
    }
    setPreviewLoadError('')
    try {
      const sep = q ? '&' : '?'
      const data = await post<any>(
        `/api/tables/${enc(detail.database)}/${enc(detail.table)}/preview${q}${sep}limit=20`)
      setPreview(data)
    } catch (e: any) {
      setPreviewLoadError(e?.message || '预览失败')
      message.error(errorTip(e, e?.status))
    }
  }

  /** 表详情里的示例 SQL：复制到剪贴板，失败时提示手动选中。 */
  const copyDetailSql = async (sqlText: string, label: string) => {
    const ok = await copyToClipboard(sqlText)
    if (ok) message.success(`${label}已复制，可到「数据查询」粘贴执行`)
    else message.error('浏览器不允许自动复制，请手动选中 SQL 复制')
  }

  /** 带 SQL 跳到查询页。Query 页读取 ?sql= 后会从地址栏移除，避免刷新覆盖手改内容。 */
  const goQueryWith = (sqlText: string) => {
    if (!canQuery) {
      message.warning('当前账号没有查询权限（query:run），请先提交权限申请或联系管理员')
      return
    }
    setDetailOpen(false)
    const params = new URLSearchParams({ sql: sqlText })
    // 带项目：查询页会用同一项目绑定的 Doris，而不是静默退回内置 Doris
    if (projectId != null) params.set('projectId', String(projectId))
    nav(`/query?${params.toString()}`)
  }

  // REQ-DW-6：新建库命名规则 = 业务英文名_后缀（docs/部门与项目建库-20261023.md 同族口径）。
  // 有 business 时输入框只填后缀、前缀灰显；冲突（已在库列表里）即时提示、创建按钮禁用。
  const currentProject = projects.find((p) => p.id === projectId)
  const dbPrefix = currentProject?.business ? `${String(currentProject.business).toLowerCase()}_` : ''
  const dbSuffix = dbPrefix && dbName.startsWith(dbPrefix) ? dbName.slice(dbPrefix.length) : dbName
  const dbConflict = !!dbName
    && databases.some((d) => (d.database || '').toLowerCase() === dbName.toLowerCase())
  const dbBadName = !!dbName && (dbPrefix
    ? !/^[a-z0-9_]{1,60}$/.test(dbSuffix)
    : !/^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/.test(dbName))

  const createDatabase = async () => {
    if (!dbName.trim()) return
    setSaving(true)
    try {
      const res = await post<any>('/api/tables/databases',
        { projectId, database: dbName.trim() })
      message.success(res?.created ? '库已创建' : '库已存在')
      setDbOpen(false)
      setDbName('')
      setDatabase(res?.database)
      await loadDatabases()
    } catch (e: any) {
      message.error(e?.message || '建库失败')
    } finally {
      setSaving(false)
    }
  }

  /** 建表请求体：预览和真正建表共用同一份，避免两边字段漂移。 */
  const buildCreatePayload = (values: any) => {
    const p = values?.partition || {}
    const mode = p.mode || 'NONE'
    // 列级属性：key 列不允许默认值（Doris），且只有 AGGREGATE 模型的 value 列带聚合类型；
    // 主键没选时后端默认第一个字段为 key，这里保持同一口径
    const rawColumns: any[] = Array.isArray(values?.columns) ? values.columns : []
    const rawKeys: string[] = Array.isArray(values?.keys) ? values.keys : []
    const payloadKeys = rawKeys.length ? rawKeys : rawColumns[0]?.name ? [rawColumns[0].name] : []
    const columns = rawColumns.map((c: any) => {
      const isKey = !!c?.name && payloadKeys.includes(c.name)
      const rawDefault = c?.defaultValue == null ? '' : String(c.defaultValue)
      return {
        name: c?.name,
        type: c?.type,
        comment: c?.comment || null,
        // 老行为：未显式关闭可空时按可空处理
        nullable: c?.nullable !== false,
        aggType: !isKey && (values?.model || 'DUPLICATE') === 'AGGREGATE'
          ? (c?.aggType || null) : null,
        defaultValue: !isKey && rawDefault !== '' ? rawDefault : null,
      }
    })
    const partition = mode === 'NONE' ? null : {
      mode,
      // 分区列界面上是单选，后端契约统一是数组
      columns: p.columns ? (Array.isArray(p.columns) ? p.columns : [p.columns]) : [],
      ranges: mode === 'RANGE'
        ? (p.ranges || []).filter((r: any) => r?.name).map((r: any) => ({
            name: r.name,
            lessThan: r.lessThan == null ? '' : String(r.lessThan),
          }))
        : undefined,
      lists: mode === 'LIST'
        ? (p.lists || []).filter((l: any) => l?.name).map((l: any) => ({
            name: l.name,
            values: l.values || [],
          }))
        : undefined,
      dynamic: mode === 'DYNAMIC' ? {
        timeUnit: p.dynamic?.timeUnit,
        // 后端接受字符串，这里统一转字符串
        start: p.dynamic?.start == null ? undefined : String(p.dynamic.start),
        end: p.dynamic?.end == null ? undefined : String(p.dynamic.end),
        prefix: p.dynamic?.prefix,
        buckets: p.dynamic?.buckets == null ? undefined : p.dynamic.buckets,
        createHistory: !!p.dynamic?.createHistory,
      } : undefined,
    }
    return {
      projectId,
      database: values?.database,
      table: values?.table,
      comment: values?.comment,
      model: values?.model,
      columns,
      keys: values?.keys,
      buckets: values?.buckets,
      replication: values?.replication,
      partition,
      // 分桶列缺省由后端取第一个 key 列
      distributionColumns: values?.distributionColumns,
      // REQ-DW-6：字段同页配置的二级索引；空行过滤掉，类型留空由后端按 BITMAP 缺省
      indexes: (Array.isArray(values?.indexes) ? values.indexes : [])
        .filter((x: any) => x?.name || x?.column)
        .map((x: any) => ({
          name: x.name || null, column: x.column || null,
          type: x.type || null, comment: x.comment || null,
        })),
      // V98：表级冷热分层，所有分区继承；不填 cooldown = 不下沉
      storageMedium: values?.storageMedium || null,
      storageCooldownTime: values?.storageCooldownTime || null,
    }
  }

  const openCreate = (initialSql?: string) => {
    const ds = database
    // 模板「填入」带着明确的 SQL 进来，按模板走，不拿旧草稿覆盖
    const draft = initialSql ? null : readCreateDraft(ds)
    form.resetFields()
    form.setFieldsValue({
      database: ds, table: '', comment: '', buckets: 1, replication: 1, model: 'DUPLICATE',
      storageMedium: '', storageCooldownTime: '',
      columns: [
        { name: 'id', type: 'BIGINT', comment: '主键', nullable: false },
        { name: 'name', type: 'VARCHAR(128)', comment: '', nullable: true },
      ],
      keys: ['id'],
      distributionColumns: [],
      indexes: [],
      partition: {
        mode: 'NONE',
        columns: [],
        ranges: [],
        lists: [],
        dynamic: {
          timeUnit: 'DAY', start: -7, end: 3, prefix: 'p', buckets: 1, createHistory: false,
        },
      },
    })
    setPreviewDdl('')
    setPreviewWarnings([])
    setPreviewError('')
    setPreviewLoading(false)
    // 模板「填入」直接带着 SQL 打开；普通新建回到表单模式
    setCreateMode(initialSql ? 'SQL' : 'FORM')
    setSqlText(initialSql || '')
    setSqlPreview(null)
    setSqlPreviewedText('')
    setSqlPreviewError('')
    setDiscardPrompt(null)
    setCreateFormDirty(false)
    setDraftPrompt(draft)
    // REQ-DW-18：重新打开回到第一步，并清空行级高亮的基准
    setCreateStep(0)
    previewDdlRef.current = ''
    prevPreviewDdlRef.current = ''
    setCreateOpen(true)
  }

  /** 关闭建表弹窗（取消/ESC/遮罩）：没提交就把当前内容存成本次会话草稿。 */
  const closeCreate = () => {
    const values = form.getFieldsValue(true)
    const dbName = values?.database || database
    const hasInput = createFormDirty || (createMode === 'SQL' && sqlText.trim() !== '')
    if (hasInput) {
      writeCreateDraft(dbName, {
        values,
        sqlText,
        mode: createMode,
        savedAt: new Date().toLocaleString('zh-CN', { hour12: false }),
      })
      // 别默默丢：明确告诉用户内容还在，重开能恢复
      message.info('已填内容已保留到本次会话，重新打开「新建表」可恢复')
    }
    setCreateOpen(false)
    setDraftPrompt(null)
  }

  /** 恢复草稿：表单值（含列数组）与 SQL 文本按关闭时的模式回填。 */
  const restoreCreateDraft = () => {
    const draft = draftPrompt
    if (!draft) return
    if (draft.values) {
      form.resetFields()
      form.setFieldsValue(draft.values)
    }
    if (draft.sqlText) setSqlText(draft.sqlText)
    setCreateMode(draft.mode === 'SQL' ? 'SQL' : 'FORM')
    // 恢复的内容算用户输入：再关闭时要继续保留
    setCreateFormDirty(true)
    setPreviewTick((v) => v + 1)
    setDraftPrompt(null)
    message.success(`已恢复 ${draft.savedAt || '上次'} 暂存的表结构`)
  }

  /** 放弃草稿：删掉存档，表单保持默认值。 */
  const discardCreateDraft = () => {
    clearCreateDraft(draftPrompt?.values?.database || database)
    setDraftPrompt(null)
  }

  const createTable = async () => {
    try {
      const values = await form.validateFields()
      setSaving(true)
      const res = await post<any>('/api/tables', buildCreatePayload(values))
      message.success('表已创建')
      // 已提交成功：草稿没有恢复价值，顺手清掉
      clearCreateDraft(values.database || database)
      setCreateOpen(false)
      setDatabase(values.database)
      await loadDatabases()
      await loadTables()
      if (res?.database && res?.table) {
        setTimeout(() => {
          openDetail({ table: res.table }, res.database)
        }, 200)
      }
    } catch (e: any) {
      if (e?.errorFields) {
        // REQ-DW-18：分步表单——把第一个出错字段映射到所属步骤，切过去并滚动定位；
        // 错误文案仍显示在字段下方（antd rules），这里只做「跳步骤 + 滚到可见错误」
        const head = String((e.errorFields[0]?.name as any[])?.[0] || '')
        const stepOf: Record<string, number> = {
          columns: 0, table: 0, database: 0,
          model: 1, keys: 1,
          partition: 2, distributionColumns: 2, buckets: 2,
          indexes: 3, replication: 3, storageMedium: 3, storageCooldownTime: 3,
        }
        setCreateStep(stepOf[head] ?? 0)
        setTimeout(() => {
          const els = Array.from(
            document.querySelectorAll('.ant-form-item-explain-error'),
          ) as HTMLElement[]
          const visible = els.find((el) => el.offsetParent !== null)
          if (visible) visible.scrollIntoView({ block: 'center', behavior: 'smooth' })
        }, 120)
        message.warning('有必填项没填完，已定位到出错的位置')
        return
      }
      message.error(e?.message || '建表失败')
    } finally {
      setSaving(false)
    }
  }

  // ---------------------------------------------------------------- 表单 ↔ SQL 双向切换

  /** 结构化 model → 建表请求体（与 buildCreatePayload 输出同形）。 */
  const payloadFromModel = (model: any) => {
    const partition = model?.partition
    return {
      projectId,
      database: model?.database || database,
      table: model?.table,
      comment: model?.comment || null,
      model: model?.model || 'DUPLICATE',
      columns: (model?.columns || []).map((c: any) => ({
        name: c?.name,
        type: c?.type,
        comment: c?.comment || null,
        nullable: c?.nullable !== false,
        aggType: c?.aggType || null,
        defaultValue: c?.defaultValue == null ? null : String(c.defaultValue),
      })),
      keys: model?.keys || [],
      buckets: model?.buckets ?? 1,
      replication: model?.replication ?? 1,
      distributionColumns: model?.distributionColumns || [],
      // REQ-DW-6：SQL 解析出的可表达索引原样带回请求体（预览/提交同形）
      indexes: (model?.indexes || []).map((x: any) => ({
        name: x?.name || null, column: x?.column || null,
        type: x?.type || null, comment: x?.comment || null,
      })),
      storageMedium: model?.storageMedium || '',
      storageCooldownTime: model?.storageCooldownTime || null,
      partition: partition && (partition.mode || 'NONE') !== 'NONE' ? {
        mode: partition.mode,
        columns: partition.columns || [],
        ranges: partition.ranges || undefined,
        lists: partition.lists || undefined,
        dynamic: partition.dynamic || undefined,
      } : null,
    }
  }

  /** 把解析出的 model 回填表单；分区列界面上是单选，取第一个。 */
  const applyModelToForm = (model: any) => {
    const partition = model?.partition || null
    const toNumber = (value: any, fallback: number) => {
      const parsed = Number(value)
      return Number.isFinite(parsed) ? parsed : fallback
    }
    form.resetFields()
    form.setFieldsValue({
      database: model?.database || database,
      table: model?.table,
      comment: model?.comment || '',
      model: model?.model || 'DUPLICATE',
      columns: (model?.columns || []).map((c: any) => ({
        name: c?.name,
        type: c?.type,
        comment: c?.comment || '',
        nullable: c?.nullable !== false,
        defaultValue: c?.defaultValue == null ? '' : String(c.defaultValue),
        aggType: c?.aggType || undefined,
      })),
      keys: model?.keys || [],
      buckets: model?.buckets ?? 1,
      replication: model?.replication ?? 1,
      distributionColumns: model?.distributionColumns || [],
      indexes: (model?.indexes || []).map((x: any) => ({
        name: x?.name || '', column: x?.column, type: x?.type || 'BITMAP', comment: x?.comment || '',
      })),
      storageMedium: model?.storageMedium || '',
      storageCooldownTime: model?.storageCooldownTime || '',
      partition: {
        mode: partition?.mode || 'NONE',
        columns: Array.isArray(partition?.columns) && partition.columns.length
          ? partition.columns[0] : undefined,
        ranges: partition?.ranges || [],
        lists: partition?.lists || [],
        dynamic: partition?.dynamic ? {
          timeUnit: partition.dynamic.timeUnit,
          start: toNumber(partition.dynamic.start, -7),
          end: toNumber(partition.dynamic.end, 3),
          prefix: partition.dynamic.prefix,
          buckets: partition.dynamic.buckets ?? 1,
          createHistory: !!partition.dynamic.createHistory,
        } : { timeUnit: 'DAY', start: -7, end: 3, prefix: 'p', buckets: 1, createHistory: false },
      },
    })
    setCreateMode('FORM')
    setCreateFormDirty(false)
    setPreviewTick((v) => v + 1)
  }

  /**
   * REQ-DW-18：建表表单内的模板入口——先解析成结构化 model 直接回填表单
   * （改字段名即可创建）；解析不成再退回 SQL 模式（和帮助弹窗「填入」同行为），不静默。
   */
  const applyCreateTemplate = async (key: string) => {
    if (!database) {
      message.warning('请先在页面左上角选择库')
      return
    }
    const tpl = tableTemplates(database).find((t) => t.key === key)
    if (!tpl) return
    setSqlLoading(true)
    try {
      const parsed = await post<any>('/api/tables/sql/preview', { projectId, database, sql: tpl.sql })
      if (parsed?.model) {
        applyModelToForm(parsed.model)
        setCreateStep(0)
        message.success(`已套用模板「${tpl.label}」，可直接改字段名后创建`)
      } else {
        openCreate(tpl.sql)
        message.warning('该模板暂时无法转成表单，已放入 SQL 模式')
      }
    } catch (e: any) {
      openCreate(tpl.sql)
      message.warning(`${e?.message || '模板解析失败'}，已放入 SQL 模式`)
    } finally {
      setSqlLoading(false)
    }
  }

  /** 用表单当前值生成 SQL（服务端同一套生成器，保证与表单建表一致）。 */
  const generateSqlFromForm = async (): Promise<string | null> => {
    const values = form.getFieldsValue(true)
    const payload = buildCreatePayload(values)
    if (!payload?.table || !payload?.columns?.length) return null
    const res = await post<any>('/api/tables/preview', payload)
    return res?.ddl || null
  }

  /** 表单 → SQL：实时用当前表单值生成 SQL 文本，可继续编辑。 */
  const switchToSqlMode = async () => {
    // 表单没改过就保留用户编辑过的 SQL，避免来回切换把改动刷掉
    if (sqlText.trim() && !createFormDirty) {
      setCreateMode('SQL')
      return
    }
    setSqlLoading(true)
    try {
      const ddl = await generateSqlFromForm()
      if (ddl) {
        setSqlText(ddl)
        setSqlPreview(null)
        setSqlPreviewedText('')
        setSqlPreviewError('')
        setCreateFormDirty(false)
      } else if (!sqlText.trim()) {
        message.warning('请先填好表名与字段，再切换到 SQL 模式')
        return
      }
      setCreateMode('SQL')
    } catch (e: any) {
      // 表单不完整时保留已有 SQL 文本，避免把用户写的内容清掉
      if (sqlText.trim()) {
        setCreateMode('SQL')
      } else {
        message.warning(e?.message || '生成 SQL 失败，请补全表名与字段')
      }
    } finally {
      setSqlLoading(false)
    }
  }

  /** 「从表单重新生成」：SQL 模式下也能把表单值刷成 SQL。 */
  const regenerateSqlFromForm = async () => {
    setSqlLoading(true)
    try {
      const ddl = await generateSqlFromForm()
      if (!ddl) {
        message.warning('请先在表单模式填好表名与字段')
        return
      }
      setSqlText(ddl)
      setSqlPreview(null)
      setSqlPreviewedText('')
      setSqlPreviewError('')
    } catch (e: any) {
      message.error(e?.message || '生成 SQL 失败')
    } finally {
      setSqlLoading(false)
    }
  }

  /**
   * 语义比对：SQL 原文 vs 表单会生成的 DDL。
   * 文本归一化后相同就算一致；否则把表单 DDL 再解析一次，比较两次的结构化 model，
   * 避免只是大小写/换行不同就反复弹「会变」。
   */
  const sameSemantics = async (sqlDdl: string, formDdl: string, model: any) => {
    const norm = (value: string) => String(value || '').replace(/\s+/g, ' ').trim().toLowerCase()
    if (norm(sqlDdl) === norm(formDdl)) return true
    try {
      const again = await post<any>('/api/tables/sql/preview', {
        projectId, database, sql: formDdl,
      })
      return JSON.stringify(again?.model || null) === JSON.stringify(model || null)
    } catch {
      return false
    }
  }

  /** 差异确认：DDL 语义会变时把两边都摊开给用户看。 */
  const showDiffConfirm = (model: any, sqlDdl: string, formDdl: string) => {
    Modal.confirm({
      title: '切回表单后 DDL 会变',
      width: 760,
      content: (
        <div style={{ fontSize: 12 }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
            当前 SQL 和表单能表达的写法不一致，切回表单后按「表单会生成」那一份建表。
          </Typography.Paragraph>
          <Typography.Text strong>当前 SQL</Typography.Text>
          <pre style={diffPreStyle}>{sqlDdl}</pre>
          <Typography.Text strong>表单会生成</Typography.Text>
          <pre style={diffPreStyle}>{formDdl || '（表单生成失败，说明还有表单不支持的结构）'}</pre>
        </div>
      ),
      okText: '仍然切回表单',
      cancelText: '留在 SQL 模式',
      onOk: () => applyModelToForm(model),
    })
  }

  /** SQL → 表单：先解析，再按「不支持项 / 语义差异」提示，最后才回填。 */
  const switchToFormMode = async () => {
    if (!sqlText.trim()) {
      setCreateMode('FORM')
      return
    }
    setSqlLoading(true)
    try {
      const parsed: SqlParseResult = sqlPreview && sqlPreviewedText === sqlText
        ? sqlPreview
        : await post<any>('/api/tables/sql/preview', {
          projectId, database, sql: sqlText, targetTable: form.getFieldValue('table'),
        })
      setSqlPreview(parsed)
      setSqlPreviewedText(sqlText)
      setSqlPreviewError('')
      if (!parsed?.model) {
        // LIKE 这类无法转表单的形式：留在 SQL 模式，但要把原因说清楚
        message.warning(
          (parsed?.unsupported && parsed.unsupported[0])
          || '这条 SQL 无法转成表单（例如 LIKE 形式），请继续用 SQL 模式')
        return
      }
      let formDdl = ''
      let same = false
      try {
        const formRes = await post<any>('/api/tables/preview', payloadFromModel(parsed.model))
        formDdl = formRes?.ddl || ''
        same = await sameSemantics(parsed.ddl, formDdl, parsed.model)
      } catch {
        // 表单连预览都生成不出来 = 一定有差异
        same = false
      }
      const unsupported = Array.isArray(parsed.unsupported) ? parsed.unsupported : []
      if (unsupported.length > 0) {
        setDiscardPrompt({ list: unsupported, model: parsed.model, formDdl, diff: !same })
        return
      }
      if (!same) {
        showDiffConfirm(parsed.model, parsed.ddl, formDdl)
        return
      }
      applyModelToForm(parsed.model)
    } catch (e: any) {
      message.error(e?.message || 'SQL 解析失败，无法切回表单')
    } finally {
      setSqlLoading(false)
    }
  }

  /** 「丢弃不支持项并切回表单」的二次确认。 */
  const confirmDiscardUnsupported = () => {
    if (!discardPrompt) return
    Modal.confirm({
      title: '丢弃 SQL 中表单不支持的项？',
      content: '这些内容不会进入表单，表单生成的 DDL 与当前 SQL 语义不同。建议先复制一份 SQL。',
      okText: '丢弃并切回表单',
      okButtonProps: { danger: true },
      cancelText: '保留 SQL 模式',
      onOk: () => {
        applyModelToForm(discardPrompt.model)
        setDiscardPrompt(null)
      },
    })
  }

  /** SQL 模式建表：与表单同一套权限（table:ddl），执行前先过一遍解析。 */
  const createTableBySql = async () => {
    const text = sqlText.trim()
    if (!text) {
      message.warning('请输入 CREATE TABLE 语句')
      return
    }
    setSaving(true)
    try {
      if (!sqlPreview || sqlPreviewedText !== text) {
        const parsed = await post<any>('/api/tables/sql/preview', {
          projectId, database, sql: text, targetTable: form.getFieldValue('table'),
        })
        setSqlPreview(parsed)
        setSqlPreviewedText(text)
      }
      const res = await post<any>('/api/tables/sql', {
        projectId, database, sql: text, targetTable: form.getFieldValue('table'),
      })
      message.success('表已创建')
      clearCreateDraft(database)
      setCreateOpen(false)
      setDatabase(res?.database || database)
      await loadDatabases()
      await loadTables()
      if (res?.database && res?.table) {
        setTimeout(() => {
          openDetail({ table: res.table }, res.database)
        }, 200)
      }
    } catch (e: any) {
      message.error(e?.message || '建表失败')
    } finally {
      setSaving(false)
    }
  }

  const dropTable = async () => {
    if (!dropTarget || dropConfirm !== dropTarget.table) return
    try {
      await del(`/api/tables/${enc(database!)}/${enc(dropTarget.table)}${q}${q ? '&' : '?'}confirm=${enc(dropConfirm)}`)
      message.success('表已删除')
      setDropTarget(null)
      setDropConfirm('')
      loadTables()
      loadDatabases()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    }
  }

  /** 一次性总确认串：与批量请求体同序的 `库.表` 逗号清单（后端按同一口径逐字符比对）。 */
  const bulkDropExpected = selectedTables.map((t) => `${database}.${t}`).join(',')

  const bulkDropTable = async () => {
    if (!database || !selectedTables.length) return
    if (bulkDropConfirm.trim() !== bulkDropExpected) return
    setBulkBusy(true)
    try {
      const res = await post<{ total: number; succeeded: number; failed: number;
        results: { database: string; table: string; ok: boolean; message?: string }[] }>(
        '/api/tables/bulk-drop',
        { projectId: projectId ?? undefined,
          items: selectedTables.map((t) => ({ database, table: t })),
          confirm: bulkDropConfirm.trim() },
      )
      setBulkDropOpen(false)
      setBulkDropConfirm('')
      setSelectedTables([])
      loadTables()
      loadDatabases()
      const failedRows = (res?.results || []).filter((r) => !r.ok)
      if (!res || res.failed === 0) {
        message.success(`已批量删除 ${res?.succeeded ?? 0} 张表`)
      } else {
        // 失败不许静默：逐条原因带回界面
        const why = failedRows.slice(0, 3)
          .map((r) => `${r.database}.${r.table}：${r.message || '删除失败'}`).join('；')
        message.warning(
          `删除成功 ${res.succeeded} 张、失败 ${res.failed} 张。${why}`
          + (failedRows.length > 3 ? `…（其余 ${failedRows.length - 3} 张见列表刷新结果）` : ''),
          8,
        )
      }
    } catch (e: any) {
      // 整体预检失败（空/超 100/重复/确认串不符）走这里：后端一张都没删
      message.error(e?.message || '批量删除失败')
    } finally {
      setBulkBusy(false)
    }
  }

  const dropDatabase = async () => {
    if (!dropDbTarget || dropDbConfirm.trim() !== dropDbTarget) return
    try {
      // confirm 必须等于库名（后端非空一律 400 并回显表数，原文直接展示给用户）
      // 注：URL 片段里不能出现带空格的三元表达式——接线检查器按无空格串提取调用路径
      const sep = q ? '&' : '?'
      await del(`/api/tables/databases/${enc(dropDbTarget)}${q}${sep}confirm=${enc(dropDbTarget)}`)
      message.success(`库 ${dropDbTarget} 已删除`)
      setDropDbTarget(null)
      setDropDbConfirm('')
      await loadDatabases()
      loadTables()
    } catch (e: any) {
      message.error(e?.message || '删除库失败')
    }
  }

  // ---------------------------------------------------------------- 编辑表单

  const openEdit = async (row: TableRow) => {
    if (!database) return
    setEditTarget(row.table)
    setOperation('ADD_COLUMN')
    setPrecheck(null)
    setAckWarnings(false)
    setEditColumns([])
    editForm.resetFields()
    editForm.setFieldsValue({
      add: { type: 'VARCHAR(64)', nullable: true, position: 'APPEND' },
      modify: {},
      drop: { confirm: '' },
      properties: { replication: 1 },
    })
    setEditOpen(true)
    try {
      // 编辑必须拿 Doris 原始类型：Gravitino 的类型是归一化的（long/string），
      // 直接回填会把「只改注释」提交成类型变更（VARCHAR(64) → STRING）。
      const sep = q ? '&' : '?'
      const data = await api<any>(
        `/api/tables/${enc(database)}/${enc(row.table)}${q}${sep}direct=true`)
      const cols: ColumnRow[] = data?.columns || []
      setEditColumns(cols)
      const first = cols[0]
      if (first) {
        editForm.setFieldsValue({
          modify: {
            name: first.name, type: first.type,
            nullable: !!first.nullable, comment: first.comment,
          },
          drop: { name: first.name, confirm: '' },
        })
      }
      setEditVersion((v) => v + 1)
    } catch (e: any) {
      message.error(e?.message || '读取表结构失败')
    }
  }

  const buildAlterBody = (): any | null => {
    const v = editForm.getFieldsValue()
    if (operation === 'ADD_COLUMN') {
      const c = v.add || {}
      if (!c.name || !c.type) return null
      return {
        operation,
        column: {
          name: String(c.name).trim(),
          type: String(c.type).trim(),
          nullable: c.nullable !== false,
          defaultValue: c.defaultValue || null,
          comment: c.comment || null,
          position: c.position || 'APPEND',
          afterColumn: c.afterColumn || null,
        },
      }
    }
    if (operation === 'MODIFY_COLUMN') {
      const c = v.modify || {}
      if (!c.name) return null
      return {
        operation,
        column: {
          name: c.name,
          type: c.type ? String(c.type).trim() : null,
          nullable: c.nullable === true,
          comment: c.comment ?? null,
        },
      }
    }
    if (operation === 'DROP_COLUMN') {
      const c = v.drop || {}
      if (!c.name) return null
      return {
        operation,
        column: { name: c.name },
        confirm: c.confirm || '',
      }
    }
    const replication = (v.properties || {}).replication
    if (!replication) return null
    return {
      operation,
      properties: { replication_allocation: `tag.location.default: ${replication}` },
    }
  }

  const runPrecheck = async () => {
    if (!database || !editTarget) return
    const body = buildAlterBody()
    if (!body) {
      setPrecheck(null)
      return
    }
    setPrechecking(true)
    try {
      const data = await post<any>(
        `/api/tables/${enc(database)}/${enc(editTarget)}/alter/precheck`,
        { projectId, ...body })
      setPrecheck(data)
      setAckWarnings(false)
    } catch (e: any) {
      setPrecheck({
        allowed: false,
        blocking: [{ code: 'PRECHECK_FAILED', message: e?.message || '预检失败' }],
        warnings: [],
        ddl: null,
      })
    } finally {
      setPrechecking(false)
    }
  }

  useEffect(() => {
    if (!editOpen) return
    const timer = setTimeout(() => { runPrecheck() }, 300)
    return () => clearTimeout(timer)
  }, [editOpen, operation, editVersion, database, projectId, editTarget])

  const warnings = precheck?.warnings || []
  const canExecute = !!precheck?.allowed
    && !prechecking
    && (warnings.length === 0 || ackWarnings)
    && !(operation === 'DROP_COLUMN'
      && dropColumnConfirm !== `${database}.${editTarget}`)

  const watchAsyncJob = async (jobId: number, db: string, target: string) => {
    // REQ-UX-FLOWLINK-1：单据术语统一为「变更单」（平台实名），且每条结果 toast
    // 都带「查看变更单」链接——此前报 #id 后无处可查（审计§三9）。
    const key = `ddl-job-${jobId}`
    const viewLink = (
      <Typography.Link onClick={() => setDdlJobId(jobId)}>查看变更单</Typography.Link>
    )
    message.loading({ content: `Doris schema change 执行中（变更单 #${jobId}）…`, key, duration: 0 })
    for (let i = 0; i < 300; i += 1) {
      await new Promise((resolve) => setTimeout(resolve, 2000))
      try {
        const job = await api<any>(`/api/tables/ddl-jobs/${jobId}`)
        if (job.status === 'FINISHED') {
          message.open({
            key, type: 'success', duration: 8,
            content: <span>表结构已更新。{viewLink}</span>,
          })
          await loadTables()
          openDetail({ table: target }, db)
          return
        }
        if (job.status === 'FAILED' || job.status === 'CANCELLED') {
          message.open({
            key, type: 'error', duration: 8,
            content: <span>{job.errorMessage || 'schema change 失败'}{viewLink}</span>,
          })
          return
        }
        const progress = job.dorisProgress ? ` ${job.dorisProgress}` : ''
        message.open({
          key, type: 'loading', duration: 0,
          content: `Doris ${job.dorisState || '执行中'}${progress}（变更单 #${jobId}）`,
        })
      } catch {
        // 轮询失败继续，避免一次网络抖动中断观察
      }
    }
    message.open({
      key, type: 'warning', duration: 8,
      content: <span>变更单 #{jobId} 仍在执行，可稍后刷新。{viewLink}</span>,
    })
  }

  const submitAlter = async () => {
    if (!database || !editTarget || !precheck?.allowed) return
    const body = buildAlterBody()
    if (!body) {
      message.warning('请先填写变更内容')
      return
    }
    setEditSaving(true)
    try {
      const res = await post<any>(
        `/api/tables/${enc(database)}/${enc(editTarget)}/alter`,
        {
          projectId,
          ...body,
          baseDdlHash: precheck.baseDdlHash,
          ddlHash: precheck.ddlHash,
          ackWarnings: warnings.length === 0 || ackWarnings,
        })
      const target = editTarget
      const db = database
      if (res?.pendingApproval) {
        // REQ-UX-FLOWLINK-1：术语改「变更单」+ 链接直达详情（三处同文案：改列/重命名/清空）
        message.info({
          duration: 8,
          content: (
            <span>
              已提交审批（变更单 #{res.job?.id}），审批通过后执行。
              <Typography.Link onClick={() => setDdlJobId(res.job?.id ?? null)}>查看变更单</Typography.Link>
            </span>
          ),
        })
      } else if (res?.job?.status === 'RUNNING') {
        message.info({
          duration: 8,
          content: (
            <span>
              已提交，Doris schema change 执行中（变更单 #{res.job.id}）。
              <Typography.Link onClick={() => setDdlJobId(res.job.id)}>查看变更单</Typography.Link>
            </span>
          ),
        })
      } else {
        // REQ-UX-FLOWLINK-1：后端 alter 同步成功也带 job（TableDdlService:533），
        // 此前此分支裸 toast 无单号无链接——「提 DDL→点进变更单详情」的判据在 DEV
        // 直执行路径断链；统一为「变更单 #id + 查看变更单」，与审批/异步两分支同款。
        message.success({
          duration: 8,
          content: (
            <span>
              {res?.job?.id ? `表结构已更新（变更单 #${res.job.id}）。` : '表结构已更新。'}
              {res?.job?.id ? (
                <Typography.Link onClick={() => setDdlJobId(res.job.id)}>查看变更单</Typography.Link>
              ) : null}
            </span>
          ),
        })
      }
      setEditOpen(false)
      await loadTables()
      if (!res?.pendingApproval && res?.job?.status === 'RUNNING') {
        watchAsyncJob(res.job.id, db, target).catch(() => undefined)
      } else if (!res?.pendingApproval) {
        openDetail({ table: target }, db)
      }
    } catch (e: any) {
      message.error(e?.message || '变更失败')
    } finally {
      setEditSaving(false)
    }
  }

  const submitRename = async () => {
    if (!database || !renameTarget) return
    if (!renameName.trim() || renameConfirm.trim() !== renameName.trim()) {
      message.warning('请填写新表名，并再次输入新表名确认')
      return
    }
    if (editProd && renameReason.trim().length < 5) {
      message.warning('生产库重命名必须填写原因（至少 5 个字）')
      return
    }
    setDangerSaving(true)
    try {
      const res = await post<any>(
        `/api/tables/${enc(database)}/${enc(renameTarget.table)}/rename`,
        {
          projectId,
          newName: renameName.trim(),
          confirm: renameConfirm.trim(),
          reason: renameReason.trim() || null,
          ackWarnings: true,
        })
      if (res?.pendingApproval) {
        // REQ-UX-FLOWLINK-1：术语改「变更单」+ 链接直达详情（三处同文案：改列/重命名/清空）
        message.info({
          duration: 8,
          content: (
            <span>
              已提交审批（变更单 #{res.job?.id}），审批通过后执行。
              <Typography.Link onClick={() => setDdlJobId(res.job?.id ?? null)}>查看变更单</Typography.Link>
            </span>
          ),
        })
      } else {
        // REQ-UX-FLOWLINK-1：同步成功带单号与链接（后端 rename 同样回 job），与审批分支同口径
        message.success({
          duration: 8,
          content: (
            <span>
              {res?.job?.id ? `表已重命名（变更单 #${res.job.id}）。` : '表已重命名。'}
              {res?.job?.id ? (
                <Typography.Link onClick={() => setDdlJobId(res.job.id)}>查看变更单</Typography.Link>
              ) : null}
            </span>
          ),
        })
      }
      setRenameTarget(null)
      setRenameName('')
      setRenameConfirm('')
      setRenameReason('')
      setDetailOpen(false)
      await loadTables()
      await loadDatabases()
    } catch (e: any) {
      message.error(e?.message || '重命名失败')
    } finally {
      setDangerSaving(false)
    }
  }

  const submitTruncate = async () => {
    if (!database || !truncateTarget) return
    if (truncateConfirm.trim() !== truncateTarget.table) {
      message.warning('请输入表名确认')
      return
    }
    if (editProd && truncateReason.trim().length < 5) {
      message.warning('生产库清空数据必须填写原因（至少 5 个字）')
      return
    }
    setDangerSaving(true)
    try {
      const res = await post<any>(
        `/api/tables/${enc(database)}/${enc(truncateTarget.table)}/truncate`,
        {
          projectId,
          confirm: truncateConfirm.trim(),
          reason: truncateReason.trim() || null,
        })
      if (res?.pendingApproval) {
        // REQ-UX-FLOWLINK-1：术语改「变更单」+ 链接直达详情（三处同文案：改列/重命名/清空）
        message.info({
          duration: 8,
          content: (
            <span>
              已提交审批（变更单 #{res.job?.id}），审批通过后执行。
              <Typography.Link onClick={() => setDdlJobId(res.job?.id ?? null)}>查看变更单</Typography.Link>
            </span>
          ),
        })
      } else {
        // REQ-UX-FLOWLINK-1：同上，同步成功带单号与链接
        message.success({
          duration: 8,
          content: (
            <span>
              {res?.job?.id ? `表数据已清空（变更单 #${res.job.id}）。` : '表数据已清空。'}
              {res?.job?.id ? (
                <Typography.Link onClick={() => setDdlJobId(res.job.id)}>查看变更单</Typography.Link>
              ) : null}
            </span>
          ),
        })
      }
      setTruncateTarget(null)
      setTruncateConfirm('')
      setTruncateReason('')
    } catch (e: any) {
      message.error(e?.message || '清空失败')
    } finally {
      setDangerSaving(false)
    }
  }

  const columnsWatch = Form.useWatch('columns', form) || []
  const keyOptions = useMemo(
    () => (columnsWatch as any[]).filter((c) => c?.name).map((c) => ({ value: c.name, label: c.name })),
    [columnsWatch])

  // ---------------------------------------------------------------- 建表：分区 + DDL 预览
  const createWatch = Form.useWatch((values) => values, form) as any
  const partitionMode: string = (Form.useWatch(['partition', 'mode'], form) as any) || 'NONE'
  const createKeys: string[] = (Form.useWatch('keys', form) as any) || []
  // 分区列必须是 key 列（Doris 要求），下拉只给 key 字段；
  // 主键没选时后端默认第一个字段为 key，这里同样放行第一个字段
  const effectiveKeys = createKeys.length ? createKeys : keyOptions.slice(0, 1).map((o) => o.value)
  const partitionColumnOptions = keyOptions.filter((o) => effectiveKeys.includes(o.value))
  const modelWatch: string = (Form.useWatch('model', form) as any) || 'DUPLICATE'
  const isAggregate = modelWatch === 'AGGREGATE'
  // REQ-DW-18：建表表单分四步（字段列表/模型与键/分区与分桶/高级属性）。
  // 四个面板全部常驻挂载、只切 display：切走的步骤照样参与 validateFields，不会漏校验。
  const [createStep, setCreateStep] = useState(0)
  // 预览行级高亮：prevPreviewDdlRef 存「上一次的 DDL」，本次新增/变化的行标黄
  const previewDdlRef = useRef('')
  const prevPreviewDdlRef = useRef('')
  const previewPrevLines = useMemo(
    () => new Set(prevPreviewDdlRef.current.split('\n')),
    // 依赖只跟预览文本走；ref 内容在预览成功时更新，此处不参与依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [previewDdl])
  // RANGE/LIST 至少要配 1 个分区：提交前就在步骤里红字提示，不再等后端报错
  const partitionRangesWatch = Form.useWatch(['partition', 'ranges'], form) || []
  const partitionListsWatch = Form.useWatch(['partition', 'lists'], form) || []
  const namedPartitions = (Array.isArray(partitionRangesWatch) ? partitionRangesWatch : [])
    .filter((r: any) => r?.name).length
    + (Array.isArray(partitionListsWatch) ? partitionListsWatch : [])
      .filter((l: any) => l?.name).length
  // 非主键列全默认（未显式勾「非空」）时提示：事实表通常至少时间/金额列 NOT NULL
  const nonNullableCount = (columnsWatch as any[])
    .filter((c: any) => c?.name && !effectiveKeys.includes(c.name) && c?.nullable === false).length
  // Form.List 的行只有索引，用整列 watch 反查字段名；key 列要隐藏聚合类型、禁用默认值
  const columnNameAt = (index: number): string => (columnsWatch as any[])?.[index]?.name || ''
  const isKeyColumn = (name: string) => !!name && effectiveKeys.includes(name)
  const previewSignature = createOpen ? JSON.stringify(createWatch || null) : ''
  const debouncedSignature = useDebounced(previewSignature, 400)

  useEffect(() => {
    // REQ-DW-6：单页表单——预览不再等「第 2 步」，填了表名/字段就实时生成
    if (!createOpen) return
    const values = createWatch
    const hasColumns = Array.isArray(values?.columns)
      && values.columns.some((c: any) => c?.name && c?.type)
    if (!values?.database || !values?.table || !hasColumns) {
      setPreviewDdl('')
      setPreviewWarnings([])
      setPreviewError('')
      setPreviewLoading(false)
      return
    }
    let alive = true
    setPreviewLoading(true)
    const timer = setTimeout(() => {
      post<any>('/api/tables/preview', buildCreatePayload(values))
        .then((res) => {
          if (!alive) return
          const ddl = res?.ddl || ''
          // 记录上一版 DDL：右侧预览用行级对比把「相对上次变了哪些行」标黄
          if (ddl !== previewDdlRef.current) {
            prevPreviewDdlRef.current = previewDdlRef.current
            previewDdlRef.current = ddl
          }
          setPreviewDdl(ddl)
          setPreviewWarnings(Array.isArray(res?.warnings) ? res.warnings : [])
          setPreviewError('')
        })
        .catch((e: any) => {
          if (!alive) return
          previewDdlRef.current = ''
          prevPreviewDdlRef.current = ''
          setPreviewDdl('')
          setPreviewWarnings([])
          setPreviewError(e?.message || '预览 DDL 失败')
        })
        .finally(() => { if (alive) setPreviewLoading(false) })
    }, 120)
    return () => { alive = false; clearTimeout(timer) }
    // 预览只跟随 debounce 后的表单快照，不直接依赖 createWatch 对象
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [createOpen, debouncedSignature, previewTick, projectId])

  // SQL 模式：边输入边解析（debounce）。错误只显示在面板里，不逐字弹 toast。
  const debouncedSqlText = useDebounced(sqlText, 500)
  useEffect(() => {
    if (!createOpen || createMode !== 'SQL') return
    const text = debouncedSqlText
    if (!text.trim() || !database) {
      setSqlPreview(null)
      setSqlPreviewError('')
      return
    }
    let alive = true
    setSqlLoading(true)
    post<any>('/api/tables/sql/preview', { projectId, database, sql: text })
      .then((res) => {
        if (!alive) return
        setSqlPreview(res)
        setSqlPreviewedText(text)
        setSqlPreviewError('')
      })
      .catch((e: any) => {
        if (!alive) return
        setSqlPreview(null)
        setSqlPreviewError(e?.message || 'SQL 解析失败')
      })
      .finally(() => { if (alive) setSqlLoading(false) })
    return () => { alive = false }
  }, [createOpen, createMode, debouncedSqlText, projectId, database])

  const columnOptions = editColumns.map((c) => ({
    value: c.name,
    label: `${c.name}（${c.type}${String(c.key).toLowerCase() === 'true' ? '，主键' : ''}）`,
  }))
  const modifyColumnName = Form.useWatch(['modify', 'name'], editForm)
  const modifyColumn = editColumns.find((c) => c.name === modifyColumnName)
  const modifyNullableDisabled = modifyColumn ? modifyColumn.nullable === false : false

  return (
    <div>
      <PageHeader
        title="表管理"
        description="区分 Doris 内表与 Iceberg 湖表：内表按项目连接的 Doris 浏览/建表；湖表按 catalog.db.table 浏览/建表。DDL 需要 table:ddl 权限。"
        extra={(
          <Space wrap>
            <Segmented
              value={storageMode}
              onChange={(value) => setStorageMode(value as 'doris' | 'lake')}
              options={[
                { value: 'doris', label: 'Doris 内表' },
                { value: 'lake', label: 'Iceberg 湖表' },
              ]}
            />
            <Select allowClear style={{ width: 200 }} placeholder="内置 Doris"
                    value={projectId}
                    onChange={(v) => { setProjectId(v); setSelectedTables([]) }}
                    options={projects.map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))} />
            {storageMode === 'doris' && (
              <>
                <Select style={{ width: 220 }} placeholder="选择库" value={database}
                        onChange={(v) => { setDatabase(v); setSelectedTables([]) }}
                        options={databases.map((d) => ({
                          value: d.database,
                          label: `${d.database}${d.database.toLowerCase().endsWith('_prd') ? '（PROD）' : ''}${d.tables ? `（${d.tables}）` : ''}`,
                        }))} />
                <Button icon={<QuestionCircleOutlined />} onClick={() => setHelpOpen(true)}>建表帮助</Button>
                {canDdl && (
                  <Button danger icon={<DeleteOutlined />} disabled={!database}
                          onClick={() => { setDropDbTarget(database ?? null); setDropDbConfirm('') }}>删除库</Button>
                )}
              </>
            )}
          </Space>
        )}
        actions={storageMode === 'doris' ? (
          /* D37：刷新/新建收敛到标准操作区（新建库/新建表是不同目标，仍是两个入口） */
          <PageActions
            refresh={{ onClick: () => Promise.all([loadDatabases(), loadTables()]) }}
            create={[
              { label: '新建库', icon: <DatabaseOutlined />, type: 'default', onClick: () => setDbOpen(true), show: canDdl },
              { label: '新建表', onClick: () => openCreate(), disabled: !database, show: canDdl },
            ]}
          />
        ) : null}
      />

      {storageMode === 'doris' && meta.degraded && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="元数据降级"
          description="Gravitino 暂不可用或被拒绝，库/表/字段信息已自动回退到直连 Doris。内容仍可用，但可能与统一元数据存在短暂差异。"
        />
      )}

      <Card>
        {storageMode === 'lake' ? (
          <LakeTablesPanel projectId={projectId} />
        ) : dbError ? (
          <ErrorState title="库列表加载失败" message={<ErrorText error={dbError} />}
                      onRetry={() => loadDatabases()} />
        ) : tablesError ? (
          <ErrorState title="表列表加载失败" message={<ErrorText error={tablesError} />}
                      onRetry={() => loadTables()} />
        ) : (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
        {canDdl && selectedTables.length > 0 ? (
          <Space wrap>
            <Typography.Text type="secondary">已选 {selectedTables.length} 张表</Typography.Text>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              disabled={selectedTables.length > 100}
              onClick={() => { setBulkDropConfirm(''); setBulkDropOpen(true) }}
            >
              批量删除
            </Button>
            <Button size="small" onClick={() => setSelectedTables([])}>清除选择</Button>
            {selectedTables.length > 100 ? (
              <Typography.Text type="danger">单次最多 100 张，请减少选择</Typography.Text>
            ) : null}
          </Space>
        ) : null}
        <Table<TableRow>
          rowKey="table"
          size="small"
          loading={loading}
          dataSource={tables}
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          onRow={(row) => ({
            style: { cursor: 'pointer' },
            onClick: (event) => {
              // UX-DW10REST-9（REQ-DW-10 R1）：点行=开表结构抽屉（抽屉内「预览 20 行」看数据）；
              // 行内控件（勾选框/操作链接）保持自身行为
              const target = event.target as HTMLElement
              if (target.closest('a, button, input, textarea, .ant-select')) return
              openDetail(row)
            },
          })}
          rowSelection={canDdl ? {
            selectedRowKeys: selectedTables,
            onChange: (keys) => setSelectedTables(keys as string[]),
          } : undefined}
          locale={{ emptyText: <EmptyState title="这个库还没有表" hint="点右上角「新建表」开始；如果刚在别处建过表，先点「刷新」。" /> }}
          columns={[
            {
              title: '表名', dataIndex: 'table', key: 'table',
              render: (value: string) => <Space size={6}><TableOutlined />{value}</Space>,
            },
            { title: '注释', dataIndex: 'comment', key: 'comment', render: (v: string) => v || '-' },
            { title: '行数（估算）', dataIndex: 'rows', key: 'rows', width: 130, render: (v: number) => v ?? '-' },
            {
              title: '操作', key: 'actions', width: 330,
              render: (_: any, row: TableRow) => (
                <Space size={4} wrap>
                  <StructLinkButton row={row} onOpen={openDetail} />
                  <PageActions size="small" buttonType="link"
                               edit={{ onClick: () => openEdit(row), show: canDdl }} />
                  {canDdl && (
                    <Button size="small" type="link" icon={<SwapOutlined />}
                            onClick={() => {
                              setRenameTarget(row)
                              setRenameName('')
                              setRenameConfirm('')
                              setRenameReason('')
                            }}>重命名</Button>
                  )}
                  {canDdl && (
                    <Button size="small" type="link" danger
                            onClick={() => {
                              setTruncateTarget(row)
                              setTruncateConfirm('')
                              setTruncateReason('')
                            }}>清空</Button>
                  )}
                  {canDdl && (
                    <Button size="small" type="link" danger icon={<DeleteOutlined />}
                            onClick={() => { setDropTarget(row); setDropConfirm('') }}>删除</Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
        </Space>
        )}
      </Card>

      <Modal
        open={dbOpen} title="新建库"
        onCancel={() => { setDbOpen(false); setDbName('') }}
        onOk={createDatabase} confirmLoading={saving} okText="创建"
        okButtonProps={{ disabled: !dbName || dbConflict || dbBadName }}
      >
        {dbPrefix ? (
          <Input addonBefore={dbPrefix} placeholder="后缀，例如 report" value={dbSuffix}
                 onChange={(e) => setDbName(dbPrefix + e.target.value.trim().toLowerCase())} />
        ) : (
          <Input placeholder="库名，例如 demo_retail" value={dbName}
                 onChange={(e) => setDbName(e.target.value)} />
        )}
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
          {dbPrefix
            ? `附加库命名规则：${dbPrefix}+后缀（小写字母/数字/下划线）；项目环境库后缀是 dev / prd`
            : '只允许字母/数字/下划线，字母或下划线开头。'}
        </Typography.Paragraph>
        {dbConflict && (
          <Alert style={{ marginTop: 8 }} type="error" showIcon
                 message={`库 ${dbName} 已存在，请改后缀，或直接从左侧列表选择它`} />
        )}
        {dbBadName && (
          <Alert style={{ marginTop: 8 }} type="warning" showIcon
                 message={dbPrefix
                   ? '后缀不合法：只允许小写字母/数字/下划线'
                   : '库名不合法：字母或下划线开头，只含字母/数字/下划线'} />
        )}
        {currentProject && !dbPrefix && (
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 4, marginBottom: 0 }}>
            该项目没有业务英文名（历史项目）：附加库命名以后端校验为准，建议先补全项目业务英文名。
          </Typography.Paragraph>
        )}
      </Modal>

      <Modal
        open={createOpen} title="新建表" width={1380}
        onCancel={closeCreate}
        footer={[
          <Button key="cancel" onClick={closeCreate}>取消</Button>,
          <Button key="ok" type="primary" loading={saving}
                  onClick={createMode === 'SQL' ? createTableBySql : createTable}>创建表</Button>,
        ]}
      >
        {/* BUG-TC6-15：重开时若有会话草稿，先问「恢复还是放弃」，别默认丢弃 */}
        {draftPrompt && (
          <Alert
            type="info" showIcon style={{ marginBottom: 12 }}
            message="恢复上次未提交的表结构？"
            description={(
              <span>
                上次关闭弹窗时填写的内容已保留（{draftPrompt.savedAt || '本次会话'}）。
                <Button type="link" size="small" onClick={restoreCreateDraft}>恢复</Button>
                <Button type="link" size="small" onClick={discardCreateDraft}>放弃</Button>
              </span>
            )}
          />
        )}
        {/* 两种模式共用同一份结构化模型：切模式只换展示，不清空表单值/SQL 文本 */}
        <Space wrap style={{ marginBottom: 12 }}>
          <Segmented
            value={createMode}
            options={[{ value: 'FORM', label: '表单模式' }, { value: 'SQL', label: 'SQL 模式' }]}
            onChange={(value) => {
              if (value === createMode) return
              if (value === 'SQL') {
                switchToSqlMode().catch(() => undefined)
              } else {
                switchToFormMode().catch(() => undefined)
              }
            }}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            切换模式不会清空已填内容；SQL → 表单前会先比对 DDL，差异会提示。
            关闭弹窗不会丢失已填内容（保留到本次会话），重新打开可恢复。
          </Typography.Text>
        </Space>

        <div style={{ display: createMode === 'FORM' ? 'block' : 'none' }}>
        {/* REQ-DW-18：建表分步 + DDL 预览常驻——左列四步（字段列表/模型与键/分区与分桶/高级属性），
            右列 sticky 实时预览；面板全部常驻挂载、只切 display，保证跨步骤校验不失效 */}
        <Form form={form} layout="vertical"
              onValuesChange={() => setCreateFormDirty(true)}>
          <Row gutter={16} align="top">
            <Col span={15} style={{ minWidth: 0 }}>
              <Space size={12} style={{ display: 'flex' }}>
                <Form.Item name="database" label="库" rules={[{ required: true }]} style={{ flex: 1 }}>
                  <Input disabled />
                </Form.Item>
                <Form.Item name="table" label="表名" rules={[
                  { required: true, message: '请输入表名' },
                  { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '字母或下划线开头' },
                ]} style={{ flex: 1 }}>
                  <Input placeholder="例如 ods_order" />
                </Form.Item>
                <Form.Item name="comment" label="表注释" style={{ flex: 1 }}>
                  <Input placeholder="这张表存什么、给谁用" />
                </Form.Item>
                <Tooltip title="按场景模板直接生成表结构，套用后回到表单继续改字段名即可创建">
                  <Select
                    placeholder="套用模板"
                    style={{ width: 210 }}
                    value={undefined}
                    onChange={(key) => { applyCreateTemplate(String(key)).catch(() => undefined) }}
                    options={tableTemplates(database || 'your_db').map((t) => ({ value: t.key, label: t.label }))}
                  />
                </Tooltip>
              </Space>
              <Steps
                size="small"
                current={createStep}
                onChange={setCreateStep}
                style={{ marginBottom: 4 }}
                items={[
                  { title: '字段列表' },
                  { title: '模型与键' },
                  { title: '分区与分桶' },
                  { title: '高级属性' },
                ]}
              />
              <div style={{ display: createStep === 0 ? 'block' : 'none', marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                  先写列：第一个字段默认作为主键（KEY）；key 列强制非空、不可设默认值；AGGREGATE 模型会自动多出「聚合类型」列。
                </Typography.Text>
                <Form.List name="columns">
                  {(fields, { add, remove }) => (
                    <div>
                      <Space style={{ marginBottom: 8 }}>
                        <Button size="small" icon={<FileAddOutlined />}
                                onClick={() => add({ type: 'VARCHAR(64)', nullable: true })}>
                          新增字段
                        </Button>
                        {fields.length === 0 && (
                          <Typography.Text type="danger">至少要有 1 个字段。</Typography.Text>
                        )}
                      </Space>
                      <Table
                        size="small" pagination={false} rowKey="key"
                        dataSource={fields.map((f) => ({ ...f, key: f.key }))}
                        locale={{ emptyText: tableEmpty('还没有字段', '点上方「新增字段」开始定义表结构') }}
                        columns={[
                          {
                            title: '字段名', width: 150,
                            render: (_: any, field: any) => (
                              <Form.Item name={[field.name, 'name']} rules={[
                                { required: true, message: '必填' },
                                { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '命名不合法' },
                              ]} style={{ margin: 0 }}>
                                <Input placeholder="order_id" />
                              </Form.Item>
                            ),
                          },
                          {
                            title: '类型', width: 160,
                            render: (_: any, field: any) => (
                              <Form.Item name={[field.name, 'type']} rules={[{ required: true }]}
                                         style={{ margin: 0 }}>
                                <AutoComplete options={TYPES.map((t) => ({ value: t }))}
                                              filterOption={(input, option) =>
                                                String(option?.value || '').toUpperCase().includes(input.toUpperCase())} />
                              </Form.Item>
                            ),
                          },
                          {
                            // key 列 Doris 强制非空，开关只对 value 列生效
                            title: '可空', width: 76,
                            render: (_: any, field: any) => {
                              const keyCol = isKeyColumn(columnNameAt(field.name))
                              return (
                                <Form.Item name={[field.name, 'nullable']} valuePropName="checked"
                                           style={{ margin: 0 }}>
                                  <Switch size="small" checkedChildren="可空" unCheckedChildren="非空"
                                          disabled={keyCol}
                                          title={keyCol ? 'key 列必须非空' : undefined} />
                                </Form.Item>
                              )
                            },
                          },
                          {
                            // key 列不允许默认值（Doris sort key 限制），前端直接禁用
                            title: '默认值', width: 120,
                            render: (_: any, field: any) => {
                              const keyCol = isKeyColumn(columnNameAt(field.name))
                              return (
                                <Form.Item name={[field.name, 'defaultValue']} style={{ margin: 0 }}>
                                  <Input placeholder={keyCol ? 'key 列不可设' : '可选'}
                                         disabled={keyCol} />
                                </Form.Item>
                              )
                            },
                          },
                          {
                            title: '注释',
                            render: (_: any, field: any) => (
                              <Form.Item name={[field.name, 'comment']} style={{ margin: 0 }}>
                                <Input />
                              </Form.Item>
                            ),
                          },
                          ...(isAggregate ? [{
                            // AGGREGATE 模型：只有 value 列显示聚合类型，key 列不显示
                            title: '聚合类型', width: 130,
                            render: (_: any, field: any) => {
                              if (isKeyColumn(columnNameAt(field.name))) {
                                return (
                                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                                    key 列
                                  </Typography.Text>
                                )
                              }
                              return (
                                <Form.Item name={[field.name, 'aggType']} style={{ margin: 0 }}>
                                  <Select size="small" allowClear placeholder="默认 REPLACE"
                                          options={AGG_TYPE_OPTIONS} />
                                </Form.Item>
                              )
                            },
                          }] : []),
                          {
                            title: '操作', width: 48,
                            render: (_: any, field: any) => (
                              <Button size="small" danger type="link"
                                      onClick={() => remove(field.name)}>删除</Button>
                            ),
                          },
                        ]}
                      />
                    </div>
                  )}
                </Form.List>
              </div>

              <div style={{ display: createStep === 1 ? 'block' : 'none', marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                  模型决定写入行为，主键（KEY 列）决定排序与去重边界；拿不准就选 DUPLICATE，不会丢行。
                </Typography.Text>
                <Form.Item name="model" label="数据模型"
                           tooltip="切换模型只影响显示与 DDL：AGGREGATE 会在字段列表多出「聚合类型」列">
                  <Select options={MODEL_OPTIONS} style={{ width: 240 }} />
                </Form.Item>
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: -6 }}>
                  {MODEL_DESC[modelWatch]}
                </Typography.Paragraph>
                <Form.Item name="keys" label="主键（KEY 列，排在最前）"
                           tooltip="UNIQUE=按 key 列去重，适合 upsert 结果表；不选时默认第一个字段">
                  <Select mode="multiple" options={keyOptions} placeholder="默认第一个字段" />
                </Form.Item>
                {isAggregate && (
                  <Alert type="info" showIcon style={{ marginBottom: 8 }}
                         message="AGGREGATE 模型：请到「字段列表」为每个 value 列选择聚合类型（key 列不可选），缺省按 REPLACE。" />
                )}
                {columnsWatch.some((c: any) => c?.name) && nonNullableCount === 0 && (
                  <Typography.Text type="danger" style={{ fontSize: 12 }}>
                    提示：还没有 value 列设为非空；事实表通常至少把时间、金额列设为 NOT NULL。
                  </Typography.Text>
                )}
              </div>

              <div style={{ display: createStep === 2 ? 'block' : 'none', marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                  大表按时间分区 + 按高基数列分桶；小表、维表可以不分区，直接跳下一步。
                </Typography.Text>
                <Form.Item name={['partition', 'mode']} label="分区方式">
                  <Segmented options={PARTITION_OPTIONS} />
                </Form.Item>
                {partitionMode !== 'NONE' && (
                  <>
                    <Row gutter={12}>
                      <Col span={partitionMode === 'DYNAMIC' ? 6 : 12}>
                        <Form.Item name={['partition', 'columns']} label="分区列（必须是主键列）"
                                   rules={[{ required: true, message: '请选择分区列' }]}>
                          <Select options={partitionColumnOptions} showSearch allowClear
                                  placeholder="先在「模型与键」把该列设为主键" />
                        </Form.Item>
                      </Col>
                      {partitionMode === 'DYNAMIC' && (
                        <>
                          <Col span={4}>
                            <Form.Item name={['partition', 'dynamic', 'timeUnit']} label="时间单位">
                              <Select options={TIME_UNIT_OPTIONS} />
                            </Form.Item>
                          </Col>
                          <Col span={3}>
                            <Form.Item name={['partition', 'dynamic', 'start']} label="start"
                                       tooltip="历史分区范围，需 <= 0">
                              <InputNumber style={{ width: '100%' }} />
                            </Form.Item>
                          </Col>
                          <Col span={3}>
                            <Form.Item name={['partition', 'dynamic', 'end']} label="end"
                                       tooltip="未来分区范围，需 >= 0">
                              <InputNumber style={{ width: '100%' }} />
                            </Form.Item>
                          </Col>
                          <Col span={3}>
                            <Form.Item name={['partition', 'dynamic', 'prefix']} label="前缀">
                              <Input placeholder="p" />
                            </Form.Item>
                          </Col>
                          <Col span={3}>
                            <Form.Item name={['partition', 'dynamic', 'buckets']} label="分桶"
                                       tooltip="缺省用表的分桶数">
                              <InputNumber min={1} max={64} style={{ width: '100%' }} />
                            </Form.Item>
                          </Col>
                          <Col span={2}>
                            <Form.Item name={['partition', 'dynamic', 'createHistory']}
                                       label="建历史" valuePropName="checked">
                              <Switch size="small" />
                            </Form.Item>
                          </Col>
                        </>
                      )}
                    </Row>
                    {partitionMode === 'DYNAMIC' && (
                      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                        动态分区=按时间自动建/删分区，RANGE + dynamic_partition.* 属性由后端拼进 PROPERTIES，不用手写属性串。
                      </Typography.Paragraph>
                    )}
                  </>
                )}
                {partitionMode === 'RANGE' && (
                  <Form.List name={['partition', 'ranges']}>
                    {(fields, { add, remove }) => (
                      <div>
                        <Space style={{ marginBottom: 8 }}>
                          <Typography.Text strong>RANGE 分区</Typography.Text>
                          <Button size="small" icon={<FileAddOutlined />}
                                  onClick={() => add({ name: `p${fields.length + 1}`, lessThan: '' })}>
                            新增分区
                          </Button>
                        </Space>
                        {fields.map((field) => (
                          <Row gutter={8} key={field.key} align="middle" style={{ marginBottom: 8 }}>
                            <Col span={8}>
                              <Form.Item name={[field.name, 'name']} style={{ margin: 0 }}
                                         rules={[{ required: true, message: '分区名必填' }]}>
                                <Input placeholder="分区名，例如 p20260101" />
                              </Form.Item>
                            </Col>
                            <Col span={12}>
                              <Form.Item name={[field.name, 'lessThan']} style={{ margin: 0 }}
                                         rules={[{ required: true, message: '上界必填' }]}>
                                <Input placeholder="上界，例如 2026-01-02（VALUES LESS THAN）" />
                              </Form.Item>
                            </Col>
                            <Col span={4}>
                              <Button size="small" danger type="link"
                                      onClick={() => remove(field.name)}>删除</Button>
                            </Col>
                          </Row>
                        ))}
                        {namedPartitions === 0 && (
                          <Typography.Text type="danger">RANGE 分区至少需要 1 个分区。</Typography.Text>
                        )}
                      </div>
                    )}
                  </Form.List>
                )}
                {partitionMode === 'LIST' && (
                  <Form.List name={['partition', 'lists']}>
                    {(fields, { add, remove }) => (
                      <div>
                        <Space style={{ marginBottom: 8 }}>
                          <Typography.Text strong>LIST 分区（单列）</Typography.Text>
                          <Button size="small" icon={<FileAddOutlined />}
                                  onClick={() => add({ name: `p${fields.length + 1}`, values: [] })}>
                            新增分区
                          </Button>
                        </Space>
                        {fields.map((field) => (
                          <Row gutter={8} key={field.key} align="middle" style={{ marginBottom: 8 }}>
                            <Col span={8}>
                              <Form.Item name={[field.name, 'name']} style={{ margin: 0 }}
                                         rules={[{ required: true, message: '分区名必填' }]}>
                                <Input placeholder="分区名，例如 p_east" />
                              </Form.Item>
                            </Col>
                            <Col span={12}>
                              <Form.Item name={[field.name, 'values']} style={{ margin: 0 }}
                                         rules={[{ required: true, message: '至少一个枚举值' }]}>
                                <Select mode="tags" tokenSeparators={[',']} open={false}
                                        placeholder="枚举值，回车或逗号分隔（VALUES IN）" />
                              </Form.Item>
                            </Col>
                            <Col span={4}>
                              <Button size="small" danger type="link"
                                      onClick={() => remove(field.name)}>删除</Button>
                            </Col>
                          </Row>
                        ))}
                        {namedPartitions === 0 && (
                          <Typography.Text type="danger">LIST 分区至少需要 1 个分区。</Typography.Text>
                        )}
                      </div>
                    )}
                  </Form.List>
                )}
                <Form.Item name="distributionColumns" label="分桶列（缺省第一个主键列）"
                           tooltip="分桶列必须是 key 列，可多选；不选时后端使用第一个主键列"
                           style={{ marginTop: 12 }}>
                  <Select mode="multiple" allowClear options={partitionColumnOptions}
                          placeholder="默认第一个主键列" style={{ maxWidth: 480 }} />
                </Form.Item>
                <Form.Item name="buckets" label="分桶数"
                           tooltip="常用 1~16；单桶数据建议不超过 10GB"
                           rules={[{ required: true, message: '请输入分桶数' }]}>
                  <InputNumber min={1} max={64} style={{ width: 180 }} />
                </Form.Item>
              </div>

              <div style={{ display: createStep === 3 ? 'block' : 'none', marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
                  索引、副本与冷热分层都是可选项，全部保持默认也能直接创建。
                </Typography.Text>
                {/* REQ-DW-6：二级索引与字段同页；目标列取字段列表已命名的列，类型缺省 BITMAP */}
                <Form.List name="indexes">
                  {(fields, { add, remove }) => (
                    <div style={{ marginBottom: 12 }}>
                      <Space wrap style={{ marginBottom: 8 }}>
                        <Typography.Text strong>二级索引（可选）</Typography.Text>
                        <Button size="small" icon={<FileAddOutlined />}
                                onClick={() => add({ name: `idx_${fields.length + 1}`, type: 'BITMAP' })}>
                          添加索引
                        </Button>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          类型留空按 BITMAP；BloomFilter 是表属性（bloom_filter_columns），不在这里配
                        </Typography.Text>
                      </Space>
                      {fields.map((field) => (
                        <Row gutter={8} key={field.key} align="top" style={{ marginBottom: 4 }}>
                          <Col span={5}>
                            <Form.Item name={[field.name, 'name']} label="索引名" style={{ margin: 0 }}
                                       rules={[
                                         { required: true, message: '必填' },
                                         { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '字母或下划线开头' },
                                       ]}>
                              <Input placeholder="idx_name" />
                            </Form.Item>
                          </Col>
                          <Col span={6}>
                            <Form.Item name={[field.name, 'column']} label="目标列" style={{ margin: 0 }}
                                       rules={[{ required: true, message: '必选' }]}>
                              <Select showSearch allowClear options={keyOptions}
                                      placeholder="字段列表里的列" />
                            </Form.Item>
                          </Col>
                          <Col span={6}>
                            <Form.Item name={[field.name, 'type']} label="类型" style={{ margin: 0 }}>
                              <Select options={INDEX_TYPE_OPTIONS} />
                            </Form.Item>
                          </Col>
                          <Col span={5}>
                            <Form.Item name={[field.name, 'comment']} label="注释" style={{ margin: 0 }}>
                              <Input placeholder="可选" />
                            </Form.Item>
                          </Col>
                          <Col span={2}>
                            <Button size="small" danger type="link"
                                    onClick={() => remove(field.name)}>删除</Button>
                          </Col>
                        </Row>
                      ))}
                      {fields.length === 0 && (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          不需要索引可以整段跳过；索引只能建在已有列上，改列名后记得同步调整。
                        </Typography.Text>
                      )}
                    </div>
                  )}
                </Form.List>
                <Form.Item name="replication" label="副本数"
                           rules={[{ required: true, message: '请输入副本数' }]}>
                  <InputNumber min={1} max={10} style={{ width: 180 }} />
                </Form.Item>
                <Space size={12} align="start">
                  <Form.Item
                    name="storageMedium"
                    label="存储介质"
                    tooltip="SSD=热数据，HDD=冷数据；表级设置对所有分区生效"
                    style={{ width: 200 }}
                  >
                    <Select options={[
                      { value: '', label: '默认（跟随集群）' },
                      { value: 'SSD', label: 'SSD（热）' },
                      {
                        value: 'HDD',
                        label: coldAvailable === false ? 'HDD（冷，数据源未配置冷存储）' : 'HDD（冷）',
                        disabled: coldAvailable === false,
                      },
                    ]} />
                  </Form.Item>
                  <Form.Item
                    name="storageCooldownTime"
                    label="冷却时间（可选）"
                    tooltip="格式 yyyy-MM-dd HH:mm:ss；不填则数据一直留在当前介质"
                    style={{ minWidth: 240 }}
                  >
                    <Input placeholder="2026-12-31 00:00:00"
                           disabled={coldAvailable === false} />
                  </Form.Item>
                </Space>
                {coldAvailable === false && (
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                    当前项目的数据源未配置冷存储（S3/HDFS），只能选 SSD；如需冷数据请先在数据源中配置默认存储。
                  </Typography.Paragraph>
                )}
              </div>

              <Space style={{ marginTop: 16 }}>
                <Button disabled={createStep === 0} onClick={() => setCreateStep(createStep - 1)}>
                  上一步
                </Button>
                {createStep < 3 && (
                  <Button type="primary" ghost onClick={() => setCreateStep(createStep + 1)}>
                    下一步
                  </Button>
                )}
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  四个面板始终一起校验：没填完的必填项在对应步骤标红，点「创建表」会自动跳到出错步骤。
                </Typography.Text>
              </Space>
            </Col>
            <Col span={9}>
              {/* REQ-DW-18：DDL 预览常驻右侧——后端同一生成器（POST /api/tables/preview），
                  与「创建表」执行语句一致；黄色行=相对上一次预览的变化行 */}
              <Card size="small" style={{ position: 'sticky', top: 0 }}
                    title={<Space size={8}>DDL 预览（与提交语句一致）{previewLoading && <Spin size="small" />}</Space>}
                    extra={<Button size="small" onClick={() => setPreviewTick((v) => v + 1)}>刷新预览</Button>}>
                {previewError ? (
                  <Alert type="warning" showIcon message={previewError} />
                ) : previewDdl ? (
                  <pre style={{
                    margin: 0, maxHeight: 460, overflow: 'auto', fontSize: 12, lineHeight: 1.6,
                    background: 'rgba(0,0,0,0.03)', borderRadius: 4, padding: 8,
                    whiteSpace: 'pre-wrap', wordBreak: 'break-all',
                  }}>
                    {previewDdl.split('\n').map((line, i) => (
                      <div key={i}
                           style={prevPreviewDdlRef.current
                             && !previewPrevLines.has(line)
                             && line.trim() !== ''
                             ? { background: 'rgba(250,173,20,0.28)', borderRadius: 2 }
                             : undefined}>
                        {line || ' '}
                      </div>
                    ))}
                  </pre>
                ) : (
                  <Typography.Text type="secondary">
                    填好表名与字段后自动生成 DDL（只预览，不执行）。
                  </Typography.Text>
                )}
                {previewWarnings.length > 0 && (
                  <Alert style={{ marginTop: 8 }} type="info" showIcon
                         message={(
                           <Space direction="vertical" size={2}>
                             {previewWarnings.map((w) => <span key={w}>{w}</span>)}
                           </Space>
                         )} />
                )}
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8, marginBottom: 0 }}>
                  黄色高亮=相对上一次预览新增或变化的行。
                </Typography.Paragraph>
              </Card>
            </Col>
          </Row>
        </Form>
        </div>

        {/* SQL 模式：同一份 SQL 既能预览也能直接执行，执行前必过解析 */}
        <div style={{ display: createMode === 'SQL' ? 'block' : 'none' }}>
          <Space direction="vertical" size={8} style={{ width: '100%' }}>
            <Alert
              type="info" showIcon
              message="只允许单条 CREATE TABLE，表名必须写成 `库名.表名`"
              description={`当前库：${database || '未选择'}。执行与表单建表同一套权限（table:ddl）、租户库归属与审计；多语句 / SET / INSERT / DROP / ALTER / CTAS 会被拒绝。`}
            />
            <Input.TextArea
              value={sqlText}
              onChange={(e) => setSqlText(e.target.value)}
              rows={14}
              spellCheck={false}
              placeholder={'CREATE TABLE `demo_retail`.`ods_order` (\n  `order_id` BIGINT NOT NULL\n) ENGINE=OLAP\nUNIQUE KEY(`order_id`)\nDISTRIBUTED BY HASH(`order_id`) BUCKETS 8\nPROPERTIES ("replication_allocation"="tag.location.default: 1");'}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
            <Card
              size="small"
              title={<Space size={8}>SQL 解析{sqlLoading && <Spin size="small" />}</Space>}
              extra={(
                <Button size="small" onClick={() => { regenerateSqlFromForm().catch(() => undefined) }}>
                  从表单生成
                </Button>
              )}
            >
              {sqlPreviewError ? (
                <Alert type="error" showIcon message={sqlPreviewError} />
              ) : sqlPreview ? (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  {(sqlPreview.unsupported || []).length > 0 && (
                    <Alert
                      type="warning" showIcon
                      message="以下内容表单不支持，切回表单会丢失"
                      description={(
                        <ul style={{ paddingLeft: 18, margin: 0 }}>
                          {(sqlPreview.unsupported || []).map((item, index) => (
                            <li key={index}>{item}</li>
                          ))}
                        </ul>
                      )}
                    />
                  )}
                  {(sqlPreview.warnings || []).length > 0 && (
                    <Alert
                      type="info" showIcon
                      message={(
                        <Space direction="vertical" size={2}>
                          {(sqlPreview.warnings || []).map((w) => <span key={w}>{w}</span>)}
                        </Space>
                      )}
                    />
                  )}
                  <pre style={diffPreStyle}>{sqlPreview.ddl}</pre>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    解析出的表：{sqlPreview.table || '-'}；切到表单模式会先用同一份结构生成 DDL 并比对差异。
                  </Typography.Text>
                </Space>
              ) : (
                <Typography.Text type="secondary">
                  输入 SQL 后自动解析（只预览，不执行）。
                </Typography.Text>
              )}
            </Card>
          </Space>
        </div>
      </Modal>

      {/* SQL → 表单：表单不支持的项必须先明说，不能静默丢 */}
      <Modal
        open={!!discardPrompt}
        title="以下内容表单不支持，切回表单会丢失"
        width={720}
        onCancel={() => setDiscardPrompt(null)}
        footer={[
          <Button key="keep" onClick={() => setDiscardPrompt(null)}>保留 SQL 模式</Button>,
          <Button key="drop" type="primary" danger onClick={confirmDiscardUnsupported}>
            丢弃不支持项并切回表单
          </Button>,
        ]}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <ul style={{ paddingLeft: 18, margin: 0, maxHeight: 220, overflow: 'auto' }}>
            {(discardPrompt?.list || []).map((item, index) => <li key={index}>{item}</li>)}
          </ul>
          {discardPrompt?.diff && (
            <Alert
              type="warning" showIcon
              message="切回表单后 DDL 也会变"
              description={(
                <pre style={diffPreStyle}>
                  {discardPrompt.formDdl || '（表单生成失败，说明还有表单不支持的结构）'}
                </pre>
              )}
            />
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            选「保留 SQL 模式」时以 SQL 为准，表单不会同步这些内容。
          </Typography.Text>
        </Space>
      </Modal>

      {/* 建表帮助：模型 / 分区 / 分桶副本 / 索引 / 7 条预检 / 模板 */}
      <Modal
        open={helpOpen}
        title="建表帮助：模型选择 / 分区 / 分桶 / 索引 / 常见失败 / 模板"
        width={1000}
        onCancel={() => setHelpOpen(false)}
        footer={<Button onClick={() => setHelpOpen(false)}>关闭</Button>}
      >
        <Tabs
          items={[
            {
              key: 'model',
              label: '① 选模型',
              children: (
                <>
                  <Table size="small" pagination={false} rowKey="k"
                         dataSource={MODEL_HELP} columns={helpColumns}
                         locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8 }}>
                    不确定就选 DUPLICATE：明细最安全，不丢行；需要按主键覆盖才用 UNIQUE。
                  </Typography.Paragraph>
                </>
              ),
            },
            {
              key: 'partition',
              label: '② 选分区',
              children: (
                <>
                  <Table size="small" pagination={false} rowKey="k"
                         dataSource={PARTITION_HELP} columns={helpColumns}
                         locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
                  <Typography.Title level={5} style={{ marginTop: 16 }}>
                    动态分区参数（PARTITION BY RANGE(列) () + properties）
                  </Typography.Title>
                  <Table size="small" pagination={false} rowKey="k"
                         dataSource={DYNAMIC_HELP} columns={helpColumns}
                         locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
                </>
              ),
            },
            {
              key: 'bucket',
              label: '③ 分桶与副本',
              children: (
                <Table size="small" pagination={false} rowKey="k"
                       dataSource={BUCKET_HELP} columns={helpColumns}
                       locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
              ),
            },
            {
              key: 'index',
              label: '④ 索引',
              children: (
                <>
                  <Table size="small" pagination={false} rowKey="k"
                         dataSource={INDEX_HELP} columns={helpColumns}
                         locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 8 }}>
                    索引只影响查询速度，不影响正确性；表很小或过滤列基数不明时先别加。
                  </Typography.Paragraph>
                </>
              ),
            },
            {
              key: 'precheck',
              label: '⑤ 常见失败（7 条预检）',
              children: (
                <>
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                    建表前平台会跑下面 7 条预检，不通过直接 400 并在页面上给出具体字段；SQL 模式同样适用。
                  </Typography.Paragraph>
                  <Table size="small" pagination={false} rowKey="k"
                         dataSource={PRECHECK_HELP} columns={helpColumns}
                         locale={{ emptyText: tableEmpty('没有内置说明', '内容内置，正常不会为空') }} />
                </>
              ),
            },
            {
              key: 'template',
              label: '⑥ 模板',
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    点「填入」把 SQL 放到 SQL 模式（库名已按当前库替换），可再切回表单。
                  </Typography.Text>
                  {tableTemplates(database || 'your_db').map((tpl) => (
                    <Card
                      key={tpl.key}
                      size="small"
                      title={tpl.label}
                      extra={(
                        <Button size="small" type="primary"
                                onClick={() => {
                                  if (!canDdl) {
                                    message.warning('没有建表权限（table:ddl）')
                                    return
                                  }
                                  if (!database) {
                                    message.warning('请先在页面左上角选择库')
                                    return
                                  }
                                  setHelpOpen(false)
                                  openCreate(tpl.sql)
                                }}>
                          填入
                        </Button>
                      )}
                    >
                      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                        {tpl.desc}
                      </Typography.Paragraph>
                      <pre style={diffPreStyle}>{tpl.sql}</pre>
                    </Card>
                  ))}
                </Space>
              ),
            },
          ]}
        />
      </Modal>

      {/* 表编辑：结构化表单 + 服务端生成 DDL 预览 + 二次确认 */}
      <Modal
        open={editOpen}
        title={`编辑表结构 ${database || ''}.${editTarget}`}
        width={1040}
        onCancel={() => setEditOpen(false)}
        footer={[
          <Button key="cancel" onClick={() => setEditOpen(false)}>取消</Button>,
          <Popconfirm
            key="ok"
            title="执行该 DDL？"
            description="请先核对左侧预览；生产库会进入审批，不会立即执行。"
            onConfirm={submitAlter}
            disabled={!canExecute}
          >
            <Button type="primary" loading={editSaving} disabled={!canExecute}>
              执行变更
            </Button>
          </Popconfirm>,
        ]}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          {editProd && (
            <Alert type="warning" showIcon
                   message="生产库变更需要审批：提交后由项目负责人或平台管理员审批通过才会执行。" />
          )}
          <Segmented
            value={operation}
            onChange={(value) => { setOperation(value as EditOperation); setAckWarnings(false) }}
            options={OPERATION_OPTIONS}
          />
          <Form form={editForm} layout="vertical"
                onValuesChange={() => setEditVersion((v) => v + 1)}>
            {operation === 'ADD_COLUMN' && (
              <>
                <Row gutter={12}>
                  <Col span={6}>
                    <Form.Item name={['add', 'name']} label="字段名" rules={[
                      { required: true, message: '必填' },
                      { pattern: /^[a-zA-Z_][a-zA-Z0-9_]{0,63}$/, message: '字母或下划线开头' },
                    ]}>
                      <Input placeholder="channel" />
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={['add', 'type']} label="类型" rules={[{ required: true }]}>
                      <AutoComplete options={TYPES.map((t) => ({ value: t }))}
                                    filterOption={(input, option) =>
                                      String(option?.value || '').toUpperCase().includes(input.toUpperCase())} />
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={['add', 'nullable']} label="可空" valuePropName="checked">
                      <Switch checkedChildren="可空" unCheckedChildren="非空" />
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={['add', 'position']} label="位置">
                      <Select options={[
                        { value: 'APPEND', label: '末尾' },
                        { value: 'FIRST', label: '最前' },
                        { value: 'AFTER', label: '指定列之后' },
                      ]} />
                    </Form.Item>
                  </Col>
                </Row>
                <Row gutter={12}>
                  <Col span={6}>
                    <Form.Item noStyle shouldUpdate>
                      {() => editForm.getFieldValue(['add', 'position']) === 'AFTER' && (
                        <Form.Item name={['add', 'afterColumn']} label="在哪个字段之后"
                                   rules={[{ required: true, message: '请选择字段' }]}>
                          <Select options={columnOptions} showSearch />
                        </Form.Item>
                      )}
                    </Form.Item>
                  </Col>
                  <Col span={6}>
                    <Form.Item name={['add', 'defaultValue']} label="默认值（非空字段有数据时必填）">
                      <Input placeholder="常量，例如 0 / 2026-01-01" />
                    </Form.Item>
                  </Col>
                  <Col span={12}>
                    <Form.Item name={['add', 'comment']} label="注释">
                      <Input />
                    </Form.Item>
                  </Col>
                </Row>
              </>
            )}

            {operation === 'MODIFY_COLUMN' && (
              <Row gutter={12}>
                <Col span={6}>
                  <Form.Item name={['modify', 'name']} label="字段" rules={[{ required: true }]}>
                    <Select
                      showSearch
                      options={columnOptions}
                      onChange={(name) => {
                        const col = editColumns.find((c) => c.name === name)
                        editForm.setFieldsValue({
                          modify: {
                            name,
                            type: col?.type,
                            nullable: !!col?.nullable,
                            comment: col?.comment,
                          },
                        })
                        setEditVersion((v) => v + 1)
                      }}
                    />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name={['modify', 'type']} label="新类型（只放行安全扩宽）">
                    <AutoComplete options={TYPES.map((t) => ({ value: t }))}
                                  filterOption={(input, option) =>
                                    String(option?.value || '').toUpperCase().includes(input.toUpperCase())} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name={['modify', 'nullable']} label="可空（只允许 非空→可空）"
                             valuePropName="checked" tooltip="Doris 不支持 NULL → NOT NULL">
                    <Switch checkedChildren="可空" unCheckedChildren="非空"
                            disabled={modifyNullableDisabled} />
                  </Form.Item>
                </Col>
                <Col span={6}>
                  <Form.Item name={['modify', 'comment']} label="注释">
                    <Input />
                  </Form.Item>
                </Col>
              </Row>
            )}

            {operation === 'DROP_COLUMN' && (
              <Row gutter={12}>
                <Col span={8}>
                  <Form.Item name={['drop', 'name']} label="要删除的字段" rules={[{ required: true }]}>
                    <Select
                      showSearch
                      options={columnOptions}
                      onChange={() => setEditVersion((v) => v + 1)}
                    />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Form.Item name={['drop', 'confirm']} label={`输入 ${database || ''}.${editTarget} 确认`}
                             rules={[{ required: true, message: '请输入完整表名确认' }]}>
                    <Input placeholder={`${database || ''}.${editTarget}`} />
                  </Form.Item>
                </Col>
                <Col span={8}>
                  <Alert type="error" showIcon style={{ marginTop: 30 }}
                         message="删除字段后该列数据不可恢复" />
                </Col>
              </Row>
            )}

            {operation === 'SET_PROPERTIES' && (
              <Row gutter={12}>
                <Col span={6}>
                  <Form.Item name={['properties', 'replication']} label="副本数（1-3，且不超过存活 BE 数）">
                    <InputNumber min={1} max={3} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={18}>
                  <Alert type="info" showIcon style={{ marginTop: 30 }}
                         message="P1 只允许修改副本数；存储策略等属性后续版本再开放。" />
                </Col>
              </Row>
            )}
          </Form>

          <Row gutter={12}>
            <Col span={14}>
              <Card size="small" title="DDL 预览（服务端生成，不可编辑）">
                {prechecking ? (
                  <Spin size="small" />
                ) : precheck?.ddl ? (
                  <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>
                    {precheck.ddl}
                  </pre>
                ) : (
                  <Typography.Text type="secondary">
                    填写完整后自动生成预览
                  </Typography.Text>
                )}
              </Card>
            </Col>
            <Col span={10}>
              {(precheck?.blocking || []).map((item: any, index: number) => (
                <Alert key={`block-${index}`} type="error" showIcon style={{ marginBottom: 8 }}
                       message={item.code}
                       description={item.message} />
              ))}
              {warnings.map((item: any, index: number) => (
                <Alert key={`warn-${index}`} type="warning" showIcon style={{ marginBottom: 8 }}
                       message={`${item.code}：${item.message || ''}`} />
              ))}
              {warnings.length > 0 && (
                <Checkbox checked={ackWarnings} onChange={(e) => setAckWarnings(e.target.checked)}>
                  我已确认以上影响
                </Checkbox>
              )}
              {precheck?.allowed && warnings.length === 0 && (
                <Alert type="success" showIcon message="预检通过，可以执行" />
              )}
              {editProd && (
                <Alert type="warning" showIcon style={{ marginTop: 8 }}
                       message="提交后进入审批，不会立即执行。" />
              )}
            </Col>
          </Row>
        </Space>
      </Modal>

      {/* 重命名 */}
      <Modal
        open={!!renameTarget}
        title={`重命名 ${database || ''}.${renameTarget?.table || ''}`}
        onCancel={() => setRenameTarget(null)}
        onOk={submitRename}
        okText={editProd ? '提交审批' : '重命名'}
        okButtonProps={{
          danger: true,
          loading: dangerSaving,
          disabled: !renameName.trim()
            || renameConfirm.trim() !== renameName.trim()
            || (editProd && renameReason.trim().length < 5),
        }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Typography.Paragraph>
            新表名只能同库改名，且下游 SQL / 授权不会自动改写。
          </Typography.Paragraph>
          <Input placeholder="新表名，例如 ods_order_v2" value={renameName}
                 onChange={(e) => setRenameName(e.target.value)} />
          <Input placeholder="再次输入新表名确认" value={renameConfirm}
                 onChange={(e) => setRenameConfirm(e.target.value)} />
          <Input.TextArea placeholder={editProd ? '变更原因（生产库必填，5-500 字）' : '变更原因（可选）'}
                          value={renameReason} rows={2}
                          onChange={(e) => setRenameReason(e.target.value)} />
        </Space>
      </Modal>

      {/* 清空数据 */}
      <Modal
        open={!!truncateTarget}
        title={<span style={{ color: '#cf1322' }}>清空表数据 {database || ''}.{truncateTarget?.table || ''}</span>}
        onCancel={() => setTruncateTarget(null)}
        onOk={submitTruncate}
        okText={editProd ? '提交审批' : '清空数据'}
        okButtonProps={{
          danger: true,
          loading: dangerSaving,
          disabled: truncateConfirm.trim() !== (truncateTarget?.table || '')
            || (editProd && truncateReason.trim().length < 5),
        }}
      >
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Alert type="error" showIcon
                 message="清空后数据不可恢复，表结构保留。请输入表名确认。" />
          <Typography.Text>
            请输入 <Typography.Text code>{truncateTarget?.table}</Typography.Text> 确认。
          </Typography.Text>
          <Input value={truncateConfirm} onChange={(e) => setTruncateConfirm(e.target.value)}
                 placeholder={truncateTarget?.table} />
          <Input.TextArea placeholder={editProd ? '变更原因（生产库必填，5-500 字）' : '变更原因（可选）'}
                          value={truncateReason} rows={2}
                          onChange={(e) => setTruncateReason(e.target.value)} />
        </Space>
      </Modal>

      <Drawer
        open={detailOpen} width={860} onClose={() => setDetailOpen(false)}
        title={detail ? `${detail.database}.${detail.table}` : '表结构'}
        extra={detail && (
          <Space>
            {/* 预览 20 行 = POST /api/tables/{db}/{t}/preview，后端 @RequireAtom("query:run")；
                裁剪条件必须与后端同一个原子，否则「看得见、点了 403」。 */}
            <Tooltip title={canQuery ? '' : '缺少查询权限（query:run），请到「权限申请」提交库/表读取授权'}>
              <Button onClick={loadPreview} disabled={!canQuery}>预览 20 行</Button>
            </Tooltip>
            {canDdl && (
              <Button icon={<EditOutlined />} onClick={() => { setDetailOpen(false); openEdit({ table: detail.table }) }}>
                编辑结构
              </Button>
            )}
            {canDdl && (
              <Button icon={<SwapOutlined />} onClick={() => {
                setRenameTarget({ table: detail.table })
                setRenameName('')
                setRenameConfirm('')
                setRenameReason('')
              }}>重命名</Button>
            )}
            {canDdl && (
              <Button danger onClick={() => {
                setTruncateTarget({ table: detail.table })
                setTruncateConfirm('')
                setTruncateReason('')
              }}>清空数据</Button>
            )}
            {canDdl && (
              <Button danger onClick={() => {
                setDropTarget({ table: detail.table } as TableRow)
                setDropConfirm('')
                setDetailOpen(false)
              }}>删除表</Button>
            )}
          </Space>
        )}
      >
        {detail ? (
          <>
            {meta.degraded && (
              <Alert
                type="warning"
                showIcon
                style={{ marginBottom: 12 }}
                message="元数据降级"
                description="Gravitino 暂不可用，表结构已回退到直连 Doris。内容仍可用，但可能与统一元数据存在短暂差异。"
              />
            )}
            <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="字段数">{detail.columns?.length}</Descriptions.Item>
              <Descriptions.Item label="环境">
                {String(detail.database || '').toLowerCase().endsWith('_prd')
                  ? <Tag color="red">PROD</Tag> : <Tag color="blue">DEV</Tag>}
              </Descriptions.Item>
            </Descriptions>
            {/* 如何查询：给当前表的可复制 SELECT，并可直接带到查询页 */}
            {(() => {
              const dbName = String(detail.database || '')
              const tableName = String(detail.table || '')
              const firstColumn = (detail.columns || [])[0]?.name as string | undefined
              const baseSql = tableSelectSql(dbName, tableName)
              const filteredSql = tableSelectSql(dbName, tableName, firstColumn)
              const isPrd = dbName.toLowerCase().endsWith('_prd')
              const examples = firstColumn
                ? [
                  { label: '基础查询：前 100 行', sql: baseSql },
                  { label: `带过滤条件（示例列：${firstColumn}）`, sql: filteredSql },
                ]
                : [{ label: '基础查询：前 100 行', sql: baseSql }]
              return (
                <Card size="small" title="如何查询" style={{ marginBottom: 12 }}>
                  <Descriptions size="small" column={1} style={{ marginBottom: 8 }}>
                    <Descriptions.Item label="可查引擎">
                      <Space size={4} wrap>
                        <Tag color="blue">Doris</Tag>
                        <Tag color="cyan">Iceberg</Tag>
                        <Tag color="orange">Spark</Tag>
                        <Typography.Text style={{ fontSize: 12 }}>
                          Doris 内表、Iceberg 湖表（catalog.db.table）在「数据查询」直接查；Spark 在「数据开发」的 SPARK 脚本里做批处理。
                        </Typography.Text>
                      </Space>
                      <div style={{ marginTop: 4 }}>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          Kafka / 文件类数据源只能导入，没有 SQL 查询链路。下一步：先到「数据集成」建同步任务把数据写入 Iceberg 湖或 Doris，实时数据到「流开发」用 FlinkSQL 落 Doris，完成后再到「数据查询」查。
                        </Typography.Text>
                        <div style={{ marginTop: 2 }}>
                          <Space size={4} wrap>
                            <Button size="small" type="link" style={{ padding: 0 }}
                                    onClick={() => nav('/integration')}>去数据集成入湖/入仓</Button>
                            <Button size="small" type="link" style={{ padding: 0 }}
                                    onClick={() => nav('/stream')}>去流开发落 Doris</Button>
                          </Space>
                        </div>
                      </div>
                    </Descriptions.Item>
                    <Descriptions.Item label="你的权限">
                      {canQuery ? (
                        <Typography.Text type="success" style={{ fontSize: 12 }}>
                          已具备查询入口权限（query:run）；具体库表仍需 READ 授权，遇到 403 时到「权限申请」提交库/表读取授权。
                        </Typography.Text>
                      ) : (
                        <Space size={6} wrap>
                          <Typography.Text type="warning" style={{ fontSize: 12 }}>
                            缺少查询入口权限（query:run）。复制 SQL 不受影响；请提交库/表读取授权，审批通过后刷新再执行。
                          </Typography.Text>
                          <Button size="small" type="primary" style={{ height: 22, fontSize: 12 }}
                                  onClick={() => nav('/access')}>去申请权限</Button>
                        </Space>
                      )}
                    </Descriptions.Item>
                    <Descriptions.Item label="注意事项">
                      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, lineHeight: 1.8 }}>
                        <li>
                          {isPrd ? <Tag color="red">PROD</Tag> : null}
                          库名以 _prd 结尾按生产库处理，查询页只读，不会修改生产数据；改结构请走「表管理」的生产变更流程并填写原因。
                        </li>
                        <li>结果上限：前端每次最多展示 200 行、后端硬上限 1000 行，达到上限会提示「结果已截断」；需要更多数据请收紧 where 条件或分批导出。</li>
                        <li>超时：服务端单次查询 60 秒上限，缩小时间范围、补 where 条件或降低 limit 后重试。</li>
                        <li>脱敏：结果按数据分级（L1–L4）与字段脱敏规则自动打码，L3/L4 字段可能只显示掩码；需要明文请到「权限申请」申请明文查看（column:unmask）或联系数据 owner。</li>
                      </ul>
                    </Descriptions.Item>
                  </Descriptions>
                  <Space size={6} wrap>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      下面 SQL 可直接复制，或点「到查询页」带过去再补充条件。
                    </Typography.Text>
                    {isPrd ? <Tag color="red">生产库 _prd：只读</Tag> : null}
                  </Space>
                  {examples.map((example) => (
                    <div key={example.label} style={{ marginTop: 8 }}>
                      <Space size={6} wrap>
                        <Typography.Text strong style={{ fontSize: 12 }}>
                          {example.label}
                        </Typography.Text>
                        <Button
                          size="small"
                          type="link"
                          icon={<CopyOutlined />}
                          onClick={() => copyDetailSql(example.sql, example.label)}
                        >
                          复制
                        </Button>
                      </Space>
                      <pre style={sqlBlockStyle}>{example.sql}</pre>
                    </div>
                  ))}
                  {!firstColumn && (
                    <Typography.Paragraph type="secondary" style={{ fontSize: 12, margin: '8px 0 0' }}>
                      还没有拿到字段信息，取到字段后这里会给出带 where 的示例。
                    </Typography.Paragraph>
                  )}
                  <Space size={8} wrap style={{ marginTop: 8 }}>
                    <Button
                      size="small"
                      type="primary"
                      icon={<SearchOutlined />}
                      disabled={!canQuery}
                      onClick={() => goQueryWith(baseSql)}
                    >
                      到查询页
                    </Button>
                    <Button
                      size="small"
                      disabled={!canQuery || !firstColumn}
                      onClick={() => goQueryWith(filteredSql)}
                    >
                      带条件去查询
                    </Button>
                    {!canQuery && (
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        当前账号没有 query:run 权限，复制 SQL 不受影响。
                      </Typography.Text>
                    )}
                  </Space>
                </Card>
              )
            })()}
            <Table
              size="small" pagination={false} rowKey="name" dataSource={detail.columns || []}
              locale={{ emptyText: tableEmpty('这个表没有字段信息', '点「刷新」或稍后重新读取元数据') }}
              columns={[
                { title: '字段', dataIndex: 'name', key: 'name' },
                { title: '类型', dataIndex: 'type', key: 'type' },
                {
                  title: '可空', dataIndex: 'nullable', key: 'nullable', width: 80,
                  render: (v: boolean) => (v ? '是' : '否'),
                },
                {
                  title: '主键', dataIndex: 'key', key: 'key', width: 80,
                  render: (v: string) => (String(v).toLowerCase() === 'true'
                    ? <Tag color="gold">KEY</Tag> : '-'),
                },
                { title: '注释', dataIndex: 'comment', key: 'comment' },
              ]}
            />
            {previewLoadError && (
              <ErrorState
                compact
                title="预览失败"
                message={<ErrorText error={previewLoadError} />}
                onRetry={() => loadPreview()}
              />
            )}
            {preview && (
              <Card size="small" title="预览" style={{ marginTop: 12 }}>
                <Table
                  size="small" pagination={false} scroll={{ x: 'max-content' }}
                  dataSource={(preview.rows || []).map((row: string[], index: number) => {
                    const item: any = { key: index }
                    ;(preview.columns || []).forEach((col: string, i: number) => { item[col] = row[i] })
                    return item
                  })}
                  locale={{ emptyText: tableEmpty('预览没有返回行', '表里当前没有数据，或过滤条件太窄；这不是加载失败') }}
                  columns={(preview.columns || []).map((col: string) => ({
                    title: col, dataIndex: col, key: col,
                  }))}
                />
                {!preview.rows?.length && (
                  <Typography.Text type="secondary">
                    预览成功，但表里还没有数据（不是加载失败）。
                  </Typography.Text>
                )}
              </Card>
            )}
            {/* Gravitino 的元数据模型不含 DDL：走统一元数据时 ddl 为空，直接隐藏区块 */}
            {detail.ddl && (
              <Card size="small" title="建表 DDL" style={{ marginTop: 12 }}>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', fontSize: 12 }}>{detail.ddl}</pre>
              </Card>
            )}
          </>
        ) : detailError ? (
          <ErrorState
            title="表结构加载失败"
            message={<ErrorText error={detailError} />}
            onRetry={() => detailTarget && openDetail({ table: detailTarget.table }, detailTarget.database)}
          />
        ) : (
          <Typography.Text type="secondary">加载中…</Typography.Text>
        )}
      </Drawer>

      <Modal
        open={!!dropTarget} title={`删除表 ${dropTarget?.table || ''}`}
        onCancel={() => { setDropTarget(null); setDropConfirm('') }}
        onOk={dropTable} okText="删除" okButtonProps={{ danger: true, disabled: dropConfirm !== dropTarget?.table }}
      >
        {editProd && (
          <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                 message="生产库删表须走「SQL 变更」"
                 description="后端会直接拒绝删除 _prd 库的表（400）；请到「SQL 变更」提交 DROP TABLE 变更单——自动备份、审批后执行、支持回滚。"
                 // REQ-UX-FLOWLINK-1：参照 Query.tsx「申请明文」的去申请模式给跳转按钮；
                 // 旧单据措辞同步改「变更单」（SQL 变更页的单据实名）
                 action={<Button size="small" type="primary" onClick={() => nav('/changes')}>去 SQL 变更</Button>} />
        )}
        <Typography.Paragraph>
          删除不可恢复。请输入表名 <Typography.Text code>{dropTarget?.table}</Typography.Text> 确认。
        </Typography.Paragraph>
        <Input value={dropConfirm} onChange={(e) => setDropConfirm(e.target.value)}
               placeholder={dropTarget?.table} />
      </Modal>

      {/* REQ-UX-FLOWLINK-1（审计§三9）：表结构变更单详情——DDL 提交/审批/执行各
          toast 的「查看变更单」链接落点；只读，读既有 ddl-jobs 详情+事件接口 */}
      <Modal
        open={ddlJobId != null}
        title={`变更单 #${ddlJobId ?? ''}`}
        width={760}
        onCancel={() => setDdlJobId(null)}
        footer={[
          <Button key="refresh" onClick={() => void loadDdlJob(Number(ddlJobId))}>刷新</Button>,
          <Button key="close" onClick={() => setDdlJobId(null)}>关闭</Button>,
        ]}
      >
        {ddlJobError && !ddlJob ? (
          <ErrorState message={ddlJobError} onRetry={() => void loadDdlJob(Number(ddlJobId))} />
        ) : !ddlJob ? (
          <LoadingState rows={4} />
        ) : (
          <>
            <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
              <Descriptions.Item label="状态">
                <Tag color={DDL_JOB_STATUS_META[String(ddlJob.status)]?.color || 'default'}>
                  {DDL_JOB_STATUS_META[String(ddlJob.status)]?.text || ddlJob.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="环境">{ddlJob.env || '-'}</Descriptions.Item>
              <Descriptions.Item label="操作">
                {DDL_JOB_OPERATION_LABEL[String(ddlJob.operation)] || ddlJob.operation}
              </Descriptions.Item>
              <Descriptions.Item label="表">
                {ddlJob.database}.{ddlJob.table}
              </Descriptions.Item>
              <Descriptions.Item label="提交人">{ddlJob.requestedBy || '-'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">{fmtDdlTime(ddlJob.requestedAt)}</Descriptions.Item>
              <Descriptions.Item label="审批人">{ddlJob.approver || '-'}</Descriptions.Item>
              <Descriptions.Item label="审批时间">{fmtDdlTime(ddlJob.approvedAt)}</Descriptions.Item>
              {ddlJob.approvalComment && (
                <Descriptions.Item label="审批意见" span={2}>{ddlJob.approvalComment}</Descriptions.Item>
              )}
              {ddlJob.dorisJobId && (
                <Descriptions.Item label="Doris 作业" span={2}>
                  {ddlJob.dorisJobId}
                  {ddlJob.dorisProgress ? ` · 进度 ${ddlJob.dorisProgress}` : ''}
                  {ddlJob.dorisState ? ` · ${ddlJob.dorisState}` : ''}
                </Descriptions.Item>
              )}
              {ddlJob.reason && (
                <Descriptions.Item label="变更原因" span={2}>{ddlJob.reason}</Descriptions.Item>
              )}
              {ddlJob.errorMessage && (
                <Descriptions.Item label="失败原因" span={2}>
                  <Typography.Text type="danger">
                    {ddlJob.errorCode ? `[${ddlJob.errorCode}] ` : ''}{ddlJob.errorMessage}
                  </Typography.Text>
                </Descriptions.Item>
              )}
            </Descriptions>
            {ddlJob.ddl && (
              <Card size="small" title="变更 SQL" style={{ marginBottom: 12 }}>
                <pre style={{ margin: 0, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                  {ddlJob.ddl}
                </pre>
              </Card>
            )}
            <Card size="small" title="流转记录">
              <Table
                size="small"
                rowKey={(r: any) => String(r.id)}
                dataSource={ddlJobEvents}
                pagination={false}
                locale={{ emptyText: tableEmpty('暂无流转记录') }}
                columns={[
                  { title: '动作', dataIndex: 'action', width: 100 },
                  { title: '操作人', dataIndex: 'actor', width: 110 },
                  { title: '详情', dataIndex: 'detail', ellipsis: true },
                  { title: '时间', dataIndex: 'created_at', width: 150, render: fmtDdlTime },
                ]}
              />
            </Card>
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 8 }}>
              {ddlJob.status === 'PENDING_APPROVAL'
                ? '等待审批；审批通过后自动执行，本页会自动跟进状态。'
                : DDL_JOB_ACTIVE_STATUS.includes(String(ddlJob.status))
                  ? '执行中每 5 秒自动刷新状态。'
                  : ''}
            </Typography.Text>
          </>
        )}
      </Modal>

      <Modal
        open={bulkDropOpen} title={`批量删除 ${selectedTables.length} 张表`}
        onCancel={() => { setBulkDropOpen(false); setBulkDropConfirm('') }}
        onOk={bulkDropTable} okText="删除" confirmLoading={bulkBusy}
        okButtonProps={{ danger: true, disabled: bulkDropConfirm.trim() !== bulkDropExpected }}
      >
        <Typography.Paragraph type="danger">
          删除不可恢复。请输入下面全部表名做一次性总确认（英文逗号分隔、顺序一致）：
        </Typography.Paragraph>
        <Typography.Paragraph
          code style={{ wordBreak: 'break-all', fontSize: 12, maxHeight: 160, overflow: 'auto' }}
        >
          {bulkDropExpected}
        </Typography.Paragraph>
        <Input.TextArea rows={2} value={bulkDropConfirm}
               onChange={(e) => setBulkDropConfirm(e.target.value)}
               placeholder="逐字符输入全部 库.表 清单" />
      </Modal>

      <Modal
        open={!!dropDbTarget} title={`删除库 ${dropDbTarget || ''}`}
        onCancel={() => { setDropDbTarget(null); setDropDbConfirm('') }}
        onOk={dropDatabase} okText="删除"
        okButtonProps={{ danger: true, disabled: dropDbConfirm.trim() !== dropDbTarget }}
      >
        {(databases.find((d) => d.database === dropDbTarget)?.tables ?? 0) > 0 && (
          <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                 message={`该库当前有 ${databases.find((d) => d.database === dropDbTarget)?.tables} 张表`}
                 description="后端只允许删空库：请先删完表，否则会被 400 拒绝并回显表数。" />
        )}
        <Typography.Paragraph>
          删除不可恢复，且只允许删除空库。请输入库名{' '}
          <Typography.Text code>{dropDbTarget}</Typography.Text> 确认。
        </Typography.Paragraph>
        <Input value={dropDbConfirm} onChange={(e) => setDropDbConfirm(e.target.value)}
               placeholder={dropDbTarget ?? undefined} />
      </Modal>
    </div>
  )
}
