export interface Project {
  id: number
  code: string
  name: string
  description?: string
  workflow_count?: number
  /** 部门（新建项目时必填，历史项目可能为空）。 */
  department_id?: number | null
  department_code?: string | null
  department_dept_id?: string | null
  department_name?: string | null
  /** 业务英文名，Doris 库名 = 业务_dev / 业务_prd。 */
  business?: string | null
  dev_database?: string | null
  prod_database?: string | null
  /** 新建表时的默认表名前缀，例如 gx_。 */
  table_prefix?: string | null
  /** 项目负责人：拥有项目库全部权限，并审批其他人的权限申请。 */
  owner_user?: string | null
  /** V64：存储类型（doris | lake）。创建后不可改，界面只读展示。 */
  storage_type?: string | null
  /** V98：项目使用的数仓数据源（type=DORIS）；空 = 平台内置 Doris。 */
  warehouse_datasource_id?: number | null
  warehouse_datasource_name?: string | null
  warehouse_datasource_type?: string | null
  warehouse_datasource_jdbc_url?: string | null
}

/** 部门：编码 + 部门ID 生成项目编码，表名前缀用于建表。 */
export interface Department {
  id: number
  code: string
  dept_id: string
  name: string
  table_prefix: string
  owner_user?: string | null
  description?: string | null
  created_at?: string
  project_count?: number
}

export interface DataSource {
  id: number
  project_id: number
  name: string
  ds_type: string
  jdbc_url: string
  username?: string
  created_at?: string
  last_test_at?: string
  last_test_ok?: boolean
  last_test_message?: string
}

export interface Task {
  code: string
  name: string
  type: 'SQL' | 'SHELL' | 'PYTHON' | 'QUALITY_GATE' | string
  script?: string
  datasource?: 'PG' | 'DORIS' | string
  datasourceId?: number
  /** 节点绑定的默认库（SQL 里可以只写表名）；目前 Doris/PG 运行期生效 */
  database?: string
  ruleCode?: string
  retries?: number
  x?: number
  y?: number
}

export interface Edge {
  from: string
  to: string
}

/** 工作流内的在线脚本文件；path 为相对路径，支持多级目录（如 etl/ods/load_orders.sql）。 */
export interface ScriptFile {
  path: string
  content: string
  language?: 'SQL' | 'SHELL' | 'PYTHON' | 'TEXT' | string
}

export interface Model {
  tasks: Task[]
  edges: Edge[]
  files?: ScriptFile[]
  /** 用户显式创建的空目录（有文件的目录会从 path 自动推导）。 */
  folders?: string[]
  /** 文件级上游依赖：本项目里其他文件（工作流）的 id（历史字段）。 */
  upstreams?: number[]
  /**
   * 文件级上游依赖表：编辑者已获得读取/写入权限的库表。
   * 依赖按「表」记录，血缘图按 表 → 文件 展示。
   */
  upstreamTables?: UpstreamTable[]
}

export interface UpstreamTable {
  catalog: string
  schemaName: string
  tableName: string
}

export interface Workflow {
  id: number
  project_id: number
  project_code: string
  code: string
  name: string
  env: string
  description?: string
  /** 项目内目录路径，空表示根目录。 */
  folder?: string | null
  /** 乐观锁版本号，每次保存 +1；提交时必须回传加载时的值。 */
  revision?: number
  /** 服务器最后保存时间（ISO，带时区偏移），冲突提示里格式成本地时间。 */
  updated_at?: string
  model: string
  version_count?: number
  instance_count?: number
  prod_version?: number
  prod_published_at?: string
  prod_pending_version?: number
  /** 最近一次发布到 DEV 的版本号（列表页展示「DEV 已跑通」用）。 */
  dev_version?: number
  /** 该 DEV 版本上的成功运行次数；0 表示还没跑通，发布会被后端拒绝。 */
  dev_success_count?: number
  versions?: Version[]
  deployments?: WorkflowDeployment[]
  schedule?: Schedule[]
}

/** 每个环境最近一次发布（workflow_deployment）。 */
export interface WorkflowDeployment {
  env: string
  version_no: number
  deployed_at: string
  deployed_by?: string
}

export interface Version {
  id: number
  version_no: number
  /** 最近一次部署状态（兼容字段）；环境状态看 dev_status / prod_status */
  status: string
  dev_status?: string | null
  prod_status?: string | null
  prod_pending_at?: string | null
  created_by: string
  created_at: string
  published_at?: string
  published_by?: string
  prefect_env?: string
  model?: string
}

export interface Schedule {
  id: number
  env: string
  cron: string
  timezone: string
  status: string
  start_date?: string
  end_date?: string
  schedule_type?: string
  run_time?: string
  weekdays?: string
  month_days?: string
  start_at?: string
}

export interface Instance {
  id: number
  workflow_id: number
  dag_id: string
  run_id: string
  biz_date?: string
  state: string
  started_at?: string
  ended_at?: string
  created_at: string
  /** 失败原因（Prefect state.message），只有失败实例才有 */
  error_message?: string | null
  /** 同一工作流 + 同一业务日期正在跑/排队的实例 id；用它禁用「重跑」 */
  active_instance_id?: number | null
  workflow_name?: string
  workflow_code?: string
  project_code?: string
  project_name?: string
}

export interface TaskInstance {
  id: number
  task_code: string
  task_name?: string
  state?: string
  try_number: number
  started_at?: string
  ended_at?: string
  error_message?: string | null
}

export interface QualityRule {
  id: number
  project_id: number
  code: string
  name: string
  rule_type: string
  table_name: string
  column_name?: string
  config: string
  /** TC7-G2：STRONG = 失败时阻断跨工作流下游调度；WEAK/缺省 = 仅告警 */
  severity?: 'STRONG' | 'WEAK' | string
  enabled: boolean
  datasource?: 'PG' | 'DORIS' | string
  datasource_id?: number
  datasource_name?: string
  datasource_type?: string
}

export interface ServiceParam {
  name: string
  type: 'STRING' | 'INT' | 'LONG' | 'DECIMAL' | 'BOOL' | string
  required?: boolean
  defaultValue?: string
}

export interface DataService {
  id: number
  project_id: number
  project_name?: string
  code: string
  name: string
  method: 'GET' | 'POST'
  datasource_id?: number
  datasource_name?: string
  datasource_type?: string
  description?: string
  sql_text: string
  params: ServiceParam[]
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  version: number
  token_masked?: string
  api_token?: string
  created_by?: string
  created_at: string
  updated_at: string
  published_at?: string
  calls_24h?: number
  errors_24h?: number
  /** 生产快照版本（发布后才有） */
  release_version?: number
  release_published_at?: string
  /** 开发态改过、尚未发布 */
  draft_dirty?: boolean
  /** 冒烟测试是否覆盖当前开发态内容 */
  smoke_ok?: boolean
  smoke_ok_at?: string
}

export interface AlertRule {
  id: number
  projectId: number
  projectName?: string
  name: string
  ruleType: 'WORKFLOW_FAILED' | 'QUALITY_FAILED' | 'SLA_TIMEOUT' | string
  workflowId?: number
  workflowName?: string
  qualityRuleId?: number
  qualityRuleName?: string
  thresholdMinutes?: number
  severity: 'WARNING' | 'CRITICAL' | string
  enabled: boolean
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface AlertEvent {
  id: number
  ruleId?: number
  ruleName: string
  projectId?: number
  projectName?: string
  ruleType: string
  severity: string
  title: string
  detail?: string
  status: 'OPEN' | 'ACKED' | 'CLOSED' | string
  workflowId?: number
  workflowName?: string
  instanceId?: number
  qualityResultId?: number
  createdAt: string
  ackedBy?: string
  ackedAt?: string
  closedBy?: string
  closedAt?: string
}

export interface AlertSummary {
  open: number
  criticalOpen: number
  todayNew: number
  todayClosed: number
  total: number
  openByType: Record<string, number>
}

export interface QualityResult {
  id: number
  rule_id: number
  rule_code: string
  rule_name: string
  biz_date: string
  passed: boolean
  /** OK = 规则执行成功（passed 是数据结论）；ERROR = 规则没执行成功，passed 不可信 */
  exec_status?: 'OK' | 'ERROR'
  detail: string
  created_at: string
}
