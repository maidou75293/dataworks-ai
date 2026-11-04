import {
  useEffect, useMemo, useRef, useState,
  type CSSProperties, type DragEvent, type ReactNode,
} from 'react'
import {
  Alert, AutoComplete, Button, Card, Checkbox, Col, DatePicker, Divider, Drawer, Dropdown, Empty,
  Form, Input, InputNumber, Modal, Popconfirm, Progress, Radio, Row, Select, Space, Table, Tag, Tree,
  Typography, message,
} from 'antd'
import {
  ApartmentOutlined, CaretRightOutlined, CloudUploadOutlined, CodeOutlined, DeleteOutlined,
  FileAddOutlined, FileOutlined, FileTextOutlined, FolderAddOutlined, FolderOpenOutlined, HistoryOutlined,
  LeftOutlined, LinkOutlined, PlayCircleOutlined, PlusOutlined, RedoOutlined,
  SaveOutlined, ScheduleOutlined, SearchOutlined, SettingOutlined, UndoOutlined,
} from '@ant-design/icons'
import { useNavigate, useParams } from 'react-router-dom'
import ReactFlow, {
  Background, Controls, Handle, MarkerType, Position, applyEdgeChanges, applyNodeChanges,
  type Connection, type Edge as FlowEdge, type EdgeChange, type Node, type NodeChange,
  type NodeProps, type ReactFlowInstance,
} from 'reactflow'
import 'reactflow/dist/style.css'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// 调度开始时间统一按北京时间保存与展示，浏览器不在东八区时也显示用户当初输入的值
dayjs.extend(utc)
dayjs.extend(timezone)
const SCHEDULE_TZ = 'Asia/Shanghai'
import { api, errMsg, del, post, put } from '../api'
import {
  buildProjectNode, projectDirPaths, treeLabel,
  type ProjectTreeData, type ProjectTreeNode,
} from '../projectTree'
import { can } from '../permissions'
import { ENV_OPTIONS, ENV_BACKFILL_HINT, ENV_DEV_DEFAULT_HINT, ENV_FOLLOW_FILE_HINT } from '../envOptions'
import PageHeader from '../components/PageHeader'
import ImeInput from '../components/ImeInput'
import CodeEditor from '../components/CodeEditor'
import VersionDiffDrawer from '../components/VersionDiffDrawer'
import BaselineConfig from '../components/BaselineConfig'
import { ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'
import { diffModels, type ModelDiff } from '../modelDiff'
import {
  mergeModels, resolveMerged,
  type MergeChoices, type MergeConflict, type MergeResult, type MergeStats,
} from '../modelMerge'
import { canLeaveCurrentPage, setLeaveGuard } from '../unsaved'
import { setGlobalProject } from '../project'
import { RunStateTag, VersionEnvStatusTag } from '../status'
import type { MenuProps } from 'antd'
import type {
  DataSource, Edge, Instance, Model, Project, QualityRule, ScriptFile, Task, UpstreamTable,
  Version, Workflow,
} from '../types'

const TASK_TYPES = ['SQL', 'SHELL', 'PYTHON', 'QUALITY_GATE']

/** 补数据：GET /api/backfills/{jobId} 的逐日切片。 */
type BackfillDay = {
  id: number
  bizDate: string
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'
  runId?: string | null
  message?: string | null
  startedAt?: string | null
  finishedAt?: string | null
}

type BackfillJob = {
  jobId: number
  env: string
  dateStart: string
  dateEnd: string
  parallelism: number
  status: 'PENDING' | 'RUNNING' | 'PAUSED' | 'DONE' | 'FAILED' | 'CANCELLED'
  totalDays: number
  doneDays: number
  failedDays: number
  createdAt?: string
  finishedAt?: string | null
  days: BackfillDay[]
  workflowCode?: string
  workflowName?: string
}

type BackfillSummary = {
  jobId: number
  env: string
  dateStart: string
  dateEnd: string
  parallelism: number
  status: BackfillJob['status']
  totalDays: number
  doneDays: number
  failedDays: number
  createdAt?: string
  finishedAt?: string | null
}

const BACKFILL_JOB_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '排队中' },
  RUNNING: { color: 'processing', label: '运行中' },
  PAUSED: { color: 'warning', label: '已暂停' },
  DONE: { color: 'success', label: '已完成' },
  FAILED: { color: 'error', label: '有失败' },
  CANCELLED: { color: 'default', label: '已取消' },
}

const BACKFILL_DAY_STATUS: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待运行' },
  RUNNING: { color: 'processing', label: '运行中' },
  DONE: { color: 'success', label: '成功' },
  FAILED: { color: 'error', label: '失败' },
  SKIPPED: { color: 'default', label: '已跳过' },
}

/**
 * SQL 节点的执行引擎。写入任务 JSON 的 `engine`；老模型没有该字段时后端按 DORIS。
 * SHELL/PYTHON 节点不选引擎。
 */
const SCRIPT_ENGINES = [
  { value: 'DORIS', label: 'Doris（项目数仓）' },
  { value: 'SPARK', label: 'Spark（Spark SQL）' },
  { value: 'FLINK', label: 'Flink（流式 SQL，异步提交）' },
]

/** Task 类型定义在共享 types.ts（本实例不改），引擎字段在此本地扩展。 */
type TaskWithEngine = Task & { engine?: string }

/** 数据源接口还带 queryable / engine 两个派生字段（V99），绑定时按引擎过滤用。 */
type BindableDatasource = DataSource & { queryable?: boolean; engine?: string }

/** 老模型没有 engine → DORIS；未知值也按 DORIS 展示（后端会拒绝并说明原因）。 */
function engineOf(task: Task): string {
  const value = String((task as TaskWithEngine).engine || '').toUpperCase()
  return SCRIPT_ENGINES.some((item) => item.value === value) ? value : 'DORIS'
}

/** 模型里原始 engine 值（大写）；缺省返回空串。 */
function rawEngineOf(task: Task): string {
  return String((task as TaskWithEngine).engine || '').trim().toUpperCase()
}

/** 模型里是否显式写了 engine；用来区分「老任务缺省 DORIS」和「用户真的选了 DORIS」。 */
function hasExplicitEngine(task: Task): boolean {
  return rawEngineOf(task) !== ''
}

/** 显式写了一个不认识的引擎：展示原值并标红，后端保存时会 400。 */
function isUnknownEngine(task: Task): boolean {
  const raw = rawEngineOf(task)
  return raw !== '' && !SCRIPT_ENGINES.some((item) => item.value === raw)
}

function engineLabel(engine: string): string {
  return SCRIPT_ENGINES.find((item) => item.value === engine)?.label || engine
}

/**
 * 绑定的数据源下拉按引擎过滤：
 * DORIS 只列「可执行 SQL」的数据源（Kafka / 文件类不能直接查）；
 * SPARK 只列 SPARK 类型；FLINK 走 SQL Gateway，不允许绑定（返回空并由界面给原因）。
 */
function bindableDatasources(engine: string, list: BindableDatasource[]): BindableDatasource[] {
  if (engine === 'FLINK') return []
  if (engine === 'SPARK') {
    return list.filter((item) => String(item.ds_type || '').toUpperCase() === 'SPARK')
  }
  return list.filter((item) => item.queryable !== false)
}

/** 只有 SQL 节点带 engine；其它类型不写这个字段。 */
function enginePatch(type: string, engine?: string): { engine?: string } {
  return type === 'SQL' ? { engine: engine || 'DORIS' } : {}
}

const SCHEDULE_TYPES = [
  { value: 'ONCE', label: '一次' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周几' },
  { value: 'MONTHLY', label: '每月几号' },
]

const WEEKDAY_OPTIONS = [
  { value: 1, label: '周一' },
  { value: 2, label: '周二' },
  { value: 3, label: '周三' },
  { value: 4, label: '周四' },
  { value: 5, label: '周五' },
  { value: 6, label: '周六' },
  { value: 7, label: '周日' },
]

const MONTH_DAY_OPTIONS = Array.from({ length: 31 }, (_, i) => ({
  value: i + 1,
  label: `${i + 1} 号`,
}))

type TaskNodeData = { name: string; type: string; connectFrom?: boolean }

/** 三栏共用高度：随窗口伸缩，避免大屏只用了半屏、小屏又被固定 560 顶出去。 */
const PANEL_HEIGHT = 'clamp(520px, calc(100vh - 250px), 880px)'

/** 撤销/重做的一步：工作流名称 + 完整模型（节点、连线、脚本、目录）。 */
type EditorSnapshot = { name: string; model: Model }

/** 撤销栈上限：够回退几十步，又不会随长时间编辑无限涨内存。 */
const HISTORY_LIMIT = 50

type RunResult = {
  taskCode: string
  taskName: string
  engine?: string
  /** D38：这次试跑实际执行的环境（DEV/PROD）与实际库名；绑定数据源时 database 为 null */
  env?: string
  database?: string | null
  bizDate: string
  executedSql: string
  columns: string[]
  rows: string[][]
  rowCount: number
  maxRows: number
  elapsedMs: number
  /** FLINK：异步提交的说明（纯 SELECT 只做 EXPLAIN 校验） */
  message?: string
  /** 实际连的连接（项目 Doris / 数据源 xx / Spark Thrift url / Flink SQL Gateway） */
  target?: string
  jobId?: string
  flinkState?: string
}

function TaskNode({ data, selected }: NodeProps<TaskNodeData>) {
  return (
    <div className={`task-node${selected ? ' selected' : ''}${data.connectFrom ? ' connect-from' : ''}`}>
      <Handle type="target" position={Position.Left} className="task-handle" />
      <div className="task-node-name" title={data.name}>{data.name}</div>
      <div className="task-node-type">{data.type}</div>
      <Handle type="source" position={Position.Right} className="task-handle" />
    </div>
  )
}

const nodeTypes = { task: TaskNode }

function toNodes(tasks: Task[]): Node[] {
  return tasks.map((task, index) => ({
    id: task.code,
    type: 'task',
    position: { x: task.x ?? 120 + index * 230, y: task.y ?? 120 },
    data: { name: task.name, type: task.type },
  }))
}

/**
 * 按依赖分层重排节点位置。
 * 由 API / 导入生成的流程常常所有节点都是同一个坐标，画布上只看得到一个节点，
 * 目录树却有几十个，用户会以为节点丢了。只在「大面积重叠」时触发，不动正常编排。
 */
function autoLayoutTasks(tasks: Task[], edges: Model['edges']): Task[] {
  if (tasks.length < 3) return tasks
  const keys = new Set(tasks.map((task) => `${task.x ?? 'none'}:${task.y ?? 'none'}`))
  if (keys.size * 2 >= tasks.length) return tasks
  const known = new Set(tasks.map((task) => task.code))
  const upstream = new Map<string, string[]>()
  edges.forEach((edge) => {
    if (!known.has(edge.from) || !known.has(edge.to)) return
    upstream.set(edge.to, [...(upstream.get(edge.to) || []), edge.from])
  })
  const cache = new Map<string, number>()
  const depthOf = (code: string, seen: Set<string>): number => {
    const cached = cache.get(code)
    if (cached !== undefined) return cached
    if (seen.has(code)) return 0
    seen.add(code)
    const ups = upstream.get(code) || []
    const depth = ups.length ? 1 + Math.max(...ups.map((up) => depthOf(up, seen))) : 0
    seen.delete(code)
    cache.set(code, depth)
    return depth
  }
  const rows = new Map<number, number>()
  return tasks.map((task) => {
    const depth = depthOf(task.code, new Set())
    const row = rows.get(depth) || 0
    rows.set(depth, row + 1)
    return { ...task, x: 80 + depth * 240, y: 80 + row * 100 }
  })
}

// 连线统一加粗：默认 1px 灰线在投影/高分屏上几乎看不见
const EDGE_STYLE = { strokeWidth: 3, stroke: '#5c6b7a' }
const EDGE_MARKER = { type: MarkerType.ArrowClosed, width: 20, height: 20, color: '#5c6b7a' }

function toFlowEdges(edges: Model['edges']): FlowEdge[] {
  return edges.map((edge) => ({
    id: `${edge.from}-${edge.to}`,
    source: edge.from,
    target: edge.to,
    animated: true,
    markerEnd: EDGE_MARKER,
    style: EDGE_STYLE,
  }))
}

const SCRIPT_LANGUAGES = [
  { value: 'SQL', label: 'SQL' },
  { value: 'SHELL', label: 'Shell' },
  { value: 'PYTHON', label: 'Python' },
  { value: 'TEXT', label: '文本' },
]

/** 校验并规整脚本路径：允许 a/b/c.sql 这样的多级相对路径。 */
function normalizeScriptPath(raw: string, folder = false): string {
  const path = (raw || '').trim().replace(/\\/g, '/').replace(/\/+/g, '/').replace(/^\/|\/$/g, '')
  if (!path) throw new Error(folder ? '目录不能为空' : '路径不能为空')
  if (path.split('/').some((part) => part === '.' || part === '..')) {
    throw new Error('路径不能包含 . 或 ..')
  }
  if (path.split('/').some((part) => !part.trim())) {
    throw new Error('路径不能包含空目录名')
  }
  if (!folder && path.endsWith('/')) throw new Error('路径不能以 / 结尾')
  return path
}

function dirOf(path: string): string {
  const index = path.lastIndexOf('/')
  return index >= 0 ? path.slice(0, index) : ''
}

function baseOf(path: string): string {
  const index = path.lastIndexOf('/')
  return index >= 0 ? path.slice(index + 1) : path
}

function initialScript(language: string, path: string): string {
  if (language === 'SHELL') return `#!/usr/bin/env bash\nset -euo pipefail\n\necho "run ${baseOf(path)}"\n`
  if (language === 'PYTHON') return `# ${baseOf(path)}\n\ndef main():\n    print("hello")\n\n\nif __name__ == "__main__":\n    main()\n`
  if (language === 'SQL') return `-- ${baseOf(path)}\nselect 1;\n`
  return ''
}

/** 权限申请接口返回的可访问资源（只用于依赖表选择弹框）。 */
type AccessTableRow = {
  tenant: string
  catalog: string
  schemaName: string
  tableName: string
  level: 'READ' | 'WRITE' | 'NONE'
  owner?: boolean
}
type AccessDbOption = {
  key: string
  tenant: string
  catalog: string
  schemaName: string
  count: number
}

/** 依赖表在界面上的唯一键：catalog.schema.table。 */
function tableRefKey(table: { catalog: string; schemaName: string; tableName: string }) {
  return `${table.catalog}.${table.schemaName}.${table.tableName}`
}


type ScriptTreeNode = {
  key: string
  title: string
  isLeaf?: boolean
  icon?: ReactNode
  children?: ScriptTreeNode[]
}

/** 把扁平的文件路径 + 空目录拼成多级目录树。 */
function buildScriptTree(files: ScriptFile[], folders: string[]): ScriptTreeNode[] {
  type Dir = { children: Map<string, Dir>; leaf?: boolean }
  const root: Dir = { children: new Map() }
  const ensureDir = (path: string): Dir => {
    let current = root
    let acc = ''
    for (const part of path.split('/').filter(Boolean)) {
      acc = acc ? `${acc}/${part}` : part
      if (!current.children.has(part)) current.children.set(part, { children: new Map() })
      current = current.children.get(part) as Dir
    }
    return current
  }
  folders.forEach((folder) => ensureDir(folder))
  files.forEach((file) => {
    const parent = ensureDir(dirOf(file.path))
    parent.children.set(baseOf(file.path), { children: new Map(), leaf: true })
  })
  const convert = (dir: Dir, prefix: string): ScriptTreeNode[] => Array
    .from(dir.children.entries())
    .map(([name, child]) => {
      const key = prefix ? `${prefix}/${name}` : name
      return child.leaf
        ? { key, title: name, isLeaf: true, icon: <FileTextOutlined /> }
        : {
            key,
            title: name,
            icon: <FolderOpenOutlined />,
            children: convert(child, key),
          }
    })
    .sort((a, b) => Number(a.isLeaf) - Number(b.isLeaf) || a.title.localeCompare(b.title))
  return convert(root, '')
}

function scriptFolderKeys(files: ScriptFile[], folders: string[]): string[] {
  const keys = new Set(folders)
  files.forEach((file) => {
    const parts = dirOf(file.path).split('/').filter(Boolean)
    parts.forEach((_, index) => keys.add(parts.slice(0, index + 1).join('/')))
  })
  return Array.from(keys)
}

/** 脚本树的 key 加前缀，避免和任务编码、别的流程的脚本重名。 */
function prefixScriptTree(nodes: ScriptTreeNode[], prefix = 'script'): ProjectTreeNode[] {
  return nodes.map((node) => {
    const key = `${prefix}:${node.key}`
    return {
      ...node,
      key,
      title: treeLabel(key, node.title),
      searchText: node.title,
      children: node.children ? prefixScriptTree(node.children, prefix) : undefined,
    }
  })
}

/**
 * 流程下面就是脚本：脚本（流程图里的节点，各自配上下游）+ 脚本文件（带目录的 .sql 等）。
 * 当前流程的 prefix 是空的，和以前一样用 task:/script: 开头；
 * 旁边展开的其他流程加 o<id>: 前缀，避免同名脚本撞 key。
 */
function workflowChildren(
  prefix: string,
  tasks: Task[],
  scriptTree: ScriptTreeNode[],
  fileCount: number,
  showTasks = true,
): ProjectTreeNode[] {
  const p = (part: string) => (prefix ? `${prefix}:${part}` : part)
  const filesKey = p('group:files')
  // 文件（流程）下面直接列脚本；单脚本文件不列（文件本身就是脚本）
  const children: ProjectTreeNode[] = showTasks
    ? tasks.map((task) => ({
      key: p(`task:${task.code}`),
      title: treeLabel(p(`task:${task.code}`), `${task.name} · ${task.type}`),
      searchText: `${task.name} ${task.code} ${task.type}`,
      isLeaf: true,
      icon: <FileTextOutlined />,
    }))
    : []
  // 脚本文件是另一个概念（带目录的源码文件），没有时不占位置
  if (scriptTree.length > 0) {
    children.push({
      key: filesKey,
      title: treeLabel(filesKey, `脚本文件（${fileCount}）`),
      searchText: '脚本文件',
      icon: <FolderOpenOutlined />,
      selectable: false,
      children: prefixScriptTree(scriptTree, p('script')),
    })
  }
  return children
}

/** 把合并统计说成人话：节点 2、脚本 1。 */
function describeMergeStats(stats: MergeStats) {
  const parts: string[] = []
  if (stats.tasks) parts.push(`节点 ${stats.tasks}`)
  if (stats.files) parts.push(`脚本 ${stats.files}`)
  if (stats.edges) parts.push(`连线 ${stats.edges}`)
  if (stats.folders) parts.push(`目录 ${stats.folders}`)
  return parts.join('、')
}

/** 服务端返回的 model 是 JSON 字符串；解析失败就退回原来的三选一。 */
function parseModel(raw: unknown): Model | null {
  try {
    const value = typeof raw === 'string' ? JSON.parse(raw || '{}') : raw
    if (!value || typeof value !== 'object') return null
    const model = value as Partial<Model>
    return {
      tasks: model.tasks || [],
      edges: model.edges || [],
      files: model.files || [],
      folders: model.folders || [],
    }
  } catch {
    return null
  }
}

/** 脚本类冲突给出两边原文，避免用户只看行数盲选。 */
function conflictContents(conflict: MergeConflict): { local: string; remote: string } | null {
  if (conflict.kind === 'file') {
    const local = (conflict.localValue as { file?: ScriptFile } | undefined)?.file
    const remote = (conflict.remoteValue as { file?: ScriptFile } | undefined)?.file
    if (local || remote) {
      return { local: local?.content ?? '', remote: remote?.content ?? '' }
    }
  }
  if (conflict.kind === 'task') {
    const local = conflict.localValue as Task | undefined
    const remote = conflict.remoteValue as Task | undefined
    if (local && remote && local.script !== remote.script
      && (local.script || remote.script)) {
      return { local: local.script || '', remote: remote.script || '' }
    }
  }
  return null
}

const PRE_STYLE: CSSProperties = {
  margin: '6px 0 0',
  padding: 8,
  background: 'var(--dw-bg)',
  borderRadius: 4,
  fontSize: 12,
  lineHeight: 1.5,
  maxHeight: 160,
  overflow: 'auto',
  whiteSpace: 'pre-wrap',
}

/** 冲突逐项选择：默认「保留我的」，点一下就能换成对方的。 */
function MergeConflictList({
  conflicts, choices,
}: { conflicts: MergeConflict[]; choices: MergeChoices }) {
  const [, rerender] = useState(0)
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  return (
    <div style={{ maxHeight: 320, overflow: 'auto' }}>
      {conflicts.map((conflict) => {
        const contents = conflictContents(conflict)
        return (
          <div
            key={conflict.key}
            style={{ padding: '8px 0', borderBottom: '1px solid var(--dw-border)' }}
          >
            <div style={{ fontWeight: 500 }}>{conflict.label}</div>
            <div style={{ color: 'var(--dw-text-3)', fontSize: 12, margin: '2px 0 6px' }}>
              {conflict.detail}
            </div>
            <Space size={8} align="center">
              <Radio.Group
                size="small"
                value={choices[conflict.key] || 'local'}
                onChange={(event) => {
                  choices[conflict.key] = event.target.value
                  rerender((tick) => tick + 1)
                }}
              >
                <Radio.Button value="local">保留我的</Radio.Button>
                <Radio.Button value="remote">用对方的</Radio.Button>
              </Radio.Group>
              {contents ? (
                <Button
                  type="link"
                  size="small"
                  style={{ padding: 0 }}
                  onClick={() => setExpanded((current) => ({
                    ...current, [conflict.key]: !current[conflict.key],
                  }))}
                >
                  {expanded[conflict.key] ? '收起内容' : '查看两边内容'}
                </Button>
              ) : null}
            </Space>
            {contents && expanded[conflict.key] ? (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <div>
                  <div style={{ fontSize: 12, color: 'var(--dw-text-3)' }}>我的</div>
                  <pre style={PRE_STYLE}>{contents.local}</pre>
                </div>
                <div>
                  <div style={{ fontSize: 12, color: 'var(--dw-text-3)' }}>对方的</div>
                  <pre style={PRE_STYLE}>{contents.remote}</pre>
                </div>
              </div>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

export default function WorkflowEditor() {
  const { workflowId } = useParams()
  const nav = useNavigate()
  const id = Number(workflowId)
  const [wf, setWf] = useState<Workflow>()
  const [model, setModel] = useState<Model>({ tasks: [], edges: [] })
  const [nodes, setNodes] = useState<Node[]>([])
  const [edges, setEdges] = useState<FlowEdge[]>([])
  const [flow, setFlow] = useState<ReactFlowInstance>()
  const [selectedCode, setSelectedCode] = useState<string>()
  const [viewMode, setViewMode] = useState<'graph' | 'code'>('graph')
  // 血缘图分两种：编排图（可编辑）与表血缘（按 SQL 实时解析，只读）
  const [graphMode, setGraphMode] = useState<'flow' | 'table'>('flow')
  const [sqlLineage, setSqlLineage] = useState<
    { taskCode: string; source: string; target: string }[]
  >([])
  const [lineageLoading, setLineageLoading] = useState(false)
  // 右侧「配置」默认展开：新建的文件/节点打开就能看到类型、数据源、重试、上游依赖
  const [configOpen, setConfigOpen] = useState(true)
  const [dirty, setDirty] = useState(false)
  const dragStart = useRef<Record<string, { x: number; y: number }>>({})
  const [connectMode, setConnectMode] = useState(false)
  const [connectFrom, setConnectFrom] = useState<string>()
  const [loadError, setLoadError] = useState('')
  const [saving, setSaving] = useState(false)
  const [versions, setVersions] = useState<Version[]>([])
  const [versionOpen, setVersionOpen] = useState(false)
  const [scheduleOpen, setScheduleOpen] = useState(false)
  const [triggerOpen, setTriggerOpen] = useState(false)
  // 刚发布时后端会等执行器同步（最多 60 秒），按钮需要显示进度而不是像卡死
  const [triggering, setTriggering] = useState(false)
  // 补数据：提交后在弹窗里轮询进度，逐日表格可直接重跑失败的天
  const [backfillOpen, setBackfillOpen] = useState(false)
  const [backfillBusy, setBackfillBusy] = useState(false)
  const [backfillJob, setBackfillJob] = useState<BackfillJob>()
  const [backfillHistory, setBackfillHistory] = useState<BackfillSummary[]>([])
  const [backfillForm] = Form.useForm()
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewCode, setPreviewCode] = useState('')
  const [scheduleForm] = Form.useForm()
  const [triggerForm] = Form.useForm()
  const [newFileForm] = Form.useForm()
  const [newFolderForm] = Form.useForm()
  const scheduleType = Form.useWatch('scheduleType', scheduleForm)
  const [scriptSelected, setScriptSelected] = useState<string>()
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [newScriptOpen, setNewScriptOpen] = useState(false)
  const [newScriptForm] = Form.useForm()
  // 新建脚本/新建文件弹窗：只有 SQL 需要选执行引擎
  const newScriptType = Form.useWatch('type', newScriptForm)
  const [newFileOpen, setNewFileOpen] = useState(false)
  const [newFolderOpen, setNewFolderOpen] = useState(false)
  const [projectFolderOpen, setProjectFolderOpen] = useState(false)
  const [projectFolderValue, setProjectFolderValue] = useState('')
  const [runOpen, setRunOpen] = useState(false)
  const [runLoading, setRunLoading] = useState(false)
  const [runResult, setRunResult] = useState<RunResult>()
  const [runError, setRunError] = useState('')
  const [pathDraft, setPathDraft] = useState('')
  const [renaming, setRenaming] = useState(false)
  const [selectedEdge, setSelectedEdge] = useState<{ from: string; to: string }>()
  const [diffOpen, setDiffOpen] = useState(false)
  const [diffVersionNo, setDiffVersionNo] = useState<number>()
  const [diffData, setDiffData] = useState<ModelDiff>()
  const [diffDirty, setDiffDirty] = useState(false)
  const [diffLoadingFor, setDiffLoadingFor] = useState<number>()
  // 版本弹窗里的发布/审批/回滚同一时间只允许一个，防止连点重复发布
  const [versionBusy, setVersionBusy] = useState<string>()
  // 撤销栈：存整份快照而不是操作日志，画布/树/编码任一入口改的都自动覆盖
  const historyRef = useRef<{
    stack: EditorSnapshot[]
    index: number
    saved: EditorSnapshot
  }>({
    stack: [],
    index: -1,
    saved: { name: '', model: { tasks: [], edges: [] } },
  })
  const [historyTick, setHistoryTick] = useState(0)
  // 三方合并的 base：服务器上一次被本地确认过的内容（加载时或最近一次保存成功）
  const mergeBaseRef = useRef<{
    model: Model; revision?: number; name?: string; description?: string
  } | null>(null)
  // 合并提示：拉取对方改动后一直显示到下次保存成功，避免用户不知道画布被改过。
  // 同时记住合并前的本地模型，用户不满意可以一键撤回这次合并。
  // 窗口重新获得焦点时检查服务器 revision：提前提示「别人改过」，
  // 而不是等用户点保存才弹冲突。
  const [staleNotice, setStaleNotice] = useState('')
  const [mergeNotice, setMergeNotice] = useState<{
    text: string
    revertModel: Model
    revertRevision?: number
    revertBase: { model: Model; revision?: number; name?: string; description?: string } | null
  } | null>(null)
  // 同一项目的目录/工作流：左侧「项目结构」树和页头切换菜单共用，
  // 打开一个流程时父目录和其他流程仍留在树里，切流程不用先退回列表。
  const [projectWorkflows, setProjectWorkflows] = useState<Workflow[]>([])
  const [projectFolders, setProjectFolders] = useState<string[]>([])
  const [projectName, setProjectName] = useState('')
  // 树里当前选中的节点（项目/目录/流程），右键菜单和下方操作都按它给
  const [treeSel, setTreeSel] = useState(`pwf:${id}`)
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number; key: string } | null>(null)
  // 移动到其他文件夹：文件/文件夹共用一套弹窗，只选目标目录
  const [moveItem, setMoveItem] = useState<
    { kind: 'workflow'; id: number; name: string } | { kind: 'folder'; path: string }
  >()
  const [moveDest, setMoveDest] = useState('')
  // 页头项目切换用：项目是全局的，切完直接回到该项目的开发台
  const [projects, setProjects] = useState<Project[]>([])
  // 当前项目对应的 Doris 库与表名前缀：配置面板直接显示，脚本里不用再猜库名
  const currentProject = projects.find((item) => item.id === wf?.project_id)
  const [newWorkflowOpen, setNewWorkflowOpen] = useState(false)
  const [newWorkflowForm] = Form.useForm()
  const newWorkflowType = Form.useWatch('type', newWorkflowForm)
  // 最近一次运行：编辑时就能看到上次跑成没成，一键跳到实例详情
  const [latestRun, setLatestRun] = useState<Instance | null>(null)
  // 项目里已有的质量规则：节点配置直接选，不用再去质量页抄编码
  const [qualityRules, setQualityRules] = useState<QualityRule[]>([])
  // 节点可绑定的数据源：按引擎过滤（Doris 只列可查 SQL 的、Spark 只列 SPARK 类型）
  const [datasources, setDatasources] = useState<BindableDatasource[]>([])
  // 依赖表选择：只列当前用户有读取 / 写入权限（或是负责人）的库表
  const [depOpen, setDepOpen] = useState(false)
  const [depLoading, setDepLoading] = useState(false)
  const [depTables, setDepTables] = useState<AccessTableRow[]>([])
  // 三级左右选择：库 → 表 → 已选择；库和表都能搜索
  const [depSchema, setDepSchema] = useState('')
  const [depSchemaSearch, setDepSchemaSearch] = useState('')
  const [depSearch, setDepSearch] = useState('')
  const [depSelectedSearch, setDepSelectedSearch] = useState('')
  const [depSelected, setDepSelected] = useState<UpstreamTable[]>([])

  // 每次渲染都同步一份「当前状态」，避免 500ms 防抖/键盘回调拿到旧闭包
  const liveSnapshot = useRef<EditorSnapshot>({ name: '', model: { tasks: [], edges: [] } })
  liveSnapshot.current = { name: wf?.name || '', model }
  // sameSnapshot 只比较名称+模型；描述也要防「保存期间被旧响应覆盖」
  const liveDescription = useRef('')
  liveDescription.current = wf?.description || ''
  // 切换工作流时递增；旧的保存/元数据响应不能写到新工作流上
  const editorEpoch = useRef(0)

  const scriptFiles = model.files || []
  const scriptFolders = model.folders || []
  const scriptTree = useMemo(
    () => buildScriptTree(scriptFiles, scriptFolders),
    [model.files, model.folders],
  )
  const selectedIsFile = scriptFiles.some((file) => file.path === scriptSelected)
  // 脚本文件有目录时默认展开，点开流程就能看到文件；路径变了才重新展开，
  // 用户手动收起的目录不会每帧被顶开。
  const scriptPathsKey = `${scriptFiles.map((file) => file.path).join('|')}#${scriptFolders.join('|')}`
  useEffect(() => {
    setExpandedKeys((current) => Array.from(new Set([
      ...current,
      'group:files',
      ...scriptFolderKeys(scriptFiles, scriptFolders).map((path) => `script:${path}`),
    ])))
  }, [scriptPathsKey])
  // 文件就是脚本：只有一个脚本时，这个脚本就是文件本身，树里不再挂子节点；
  // 脚本名与文件名双向同步（改哪边都等于改文件）。
  const implicitSingleScript = model.tasks.length === 1
  // 当前流程内部：流程节点 + 脚本，脚本按真实目录多级展开。
  const currentWorkflowTree = useMemo<ProjectTreeNode[]>(
    () => workflowChildren('', model.tasks, scriptTree, scriptFiles.length, !implicitSingleScript),
    [model.tasks, scriptTree, scriptFiles.length, implicitSingleScript],
  )
  // 左侧「项目结构」只展示当前项目：项目 → 多级文件夹 → 文件（工作流）。
  // 当前文件就地展开脚本；其他文件点一下切过去。项目切换用页头下拉。
  const projectTree = useMemo<ProjectTreeNode[]>(() => {
    const node = {
      key: 'proj:root',
      title: (
        <span>
          {projectName || wf?.project_code || '项目'}
          {' '}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {wf?.project_code || ''}
          </Typography.Text>
        </span>
      ),
      searchText: `${projectName} ${wf?.project_code || ''}`,
      data: { workflows: projectWorkflows, folders: projectFolders },
      dirKey: (path: string) => `pdir:${path}`,
      workflowNode: (item: Workflow) => ({
        key: `pwf:${item.id}`,
        // 名称单独占一列、可省略；「当前」标记固定在右侧，不会被截断。
        // 当前文件直接用编辑中的名称，改文件名时树上同步显示。
        title: (
          <span className="wf-tree-title" data-tree-key={`pwf:${item.id}`}>
            <span className="wf-tree-name" title={item.id === id ? (wf?.name || item.name) : item.name}>
              {item.id === id ? (wf?.name || item.name) : item.name}
            </span>
            <span className="wf-tree-env">{item.env}</span>
            {item.id === id ? <span className="wf-tree-current">当前</span> : null}
          </span>
        ),
        searchText: `${item.id === id ? (wf?.name || item.name) : item.name} ${item.env}${item.id === id ? ' 当前' : ''}`,
        isLeaf: item.id !== id ? true : currentWorkflowTree.length === 0,
        icon: item.id === id ? <FileTextOutlined /> : <FileOutlined />,
        // 单脚本文件不挂脚本子节点；脚本文件（.sql 等）仍然挂在文件下面
        children: item.id === id && currentWorkflowTree.length > 0
          ? currentWorkflowTree : undefined,
      }),
    }
    return [buildProjectNode(node)]
  }, [projectName, wf?.project_code, wf?.name, projectWorkflows, projectFolders,
    currentWorkflowTree, id, implicitSingleScript])

  const treeExpandedKeys = expandedKeys

  // 依赖表弹框：库 → 表 → 已选择 三列；库和表各自搜索
  const depSchemaOptions = (() => {
    const map = new Map<string, AccessDbOption>()
    depTables.forEach((table) => {
      const key = `${table.tenant}:${table.catalog}:${table.schemaName}`
      const current = map.get(key)
      if (current) {
        current.count += 1
      } else {
        map.set(key, {
          key,
          tenant: table.tenant,
          catalog: table.catalog,
          schemaName: table.schemaName,
          count: 1,
        })
      }
    })
    const term = depSchemaSearch.trim().toLowerCase()
    return Array.from(map.values())
      .filter((db) => !term
        || `${db.catalog}.${db.schemaName} ${db.tenant}`.toLowerCase().includes(term))
      .sort((a, b) => a.key.localeCompare(b.key))
  })()
  const depVisibleTables = depTables
    .filter((table) => `${table.tenant}:${table.catalog}:${table.schemaName}` === depSchema)
    .filter((table) => {
      const term = depSearch.trim().toLowerCase()
      return !term || table.tableName.toLowerCase().includes(term)
    })
  const depSelectedVisibleTables = depSelected.filter((table) => {
    const term = depSelectedSearch.trim().toLowerCase()
    return !term || tableRefKey(table).toLowerCase().includes(term)
  })
  const depVisibleSelectedCount = depVisibleTables.filter(
    (table) => depSelected.some((item) => tableRefKey(item) === tableRefKey(table))).length
  const depAllVisibleSelected = depVisibleTables.length > 0
    && depVisibleSelectedCount === depVisibleTables.length
  const depSomeVisibleSelected = depVisibleSelectedCount > 0 && !depAllVisibleSelected

  // 移动目标只列已有文件夹；移动文件夹时排除自己和子文件夹
  const folderOptions = useMemo(() => {
    const blocked = moveItem?.kind === 'folder' ? moveItem.path : null
    const all = projectDirPaths({ workflows: projectWorkflows, folders: projectFolders })
      .sort((a, b) => a.localeCompare(b))
    return [
      { value: '', label: '项目根' },
      ...all
        .filter((path) => !blocked || (path !== blocked && !path.startsWith(`${blocked}/`)))
        .map((path) => ({ value: path, label: path })),
    ]
  }, [projectWorkflows, projectFolders, moveItem])

  const selected = useMemo(
    () => model.tasks.find((task) => task.code === selectedCode),
    [model.tasks, selectedCode],
  )
  const selectedScript = useMemo(
    () => scriptFiles.find((file) => file.path === scriptSelected),
    [model.files, scriptSelected],
  )
  const selectedScriptFolder = scriptSelected && !selectedScript ? scriptSelected : undefined
  const upstreamTables = model.upstreamTables || []
  // 当前节点绑定的数据源（用于展示名称，而不是只给一个 #id）
  const boundDatasource = selected?.datasourceId
    ? datasources.find((item) => item.id === selected.datasourceId)
    : undefined
  // 文件级血缘：上游库表 → 当前文件（依赖按表记录，只展示有权限的表）
  const fileLineageGraph = useMemo(() => {
    if (!implicitSingleScript || upstreamTables.length === 0) return null
    const baseStyle = {
      width: 210,
      padding: 10,
      borderRadius: 8,
      fontSize: 12,
      background: '#fff',
      border: '1px solid #d9e2ec',
      color: '#1f2329',
    }
    const nodes: Node[] = [
      ...upstreamTables.map((table, index) => ({
        id: `lineage-table-${index}`,
        position: { x: 30, y: 30 + index * 110 },
        data: { label: `${table.catalog}.${table.schemaName}.${table.tableName}` },
        draggable: false,
        connectable: false,
        style: baseStyle,
      })),
      {
        id: `lineage-file-${id}`,
        position: { x: 380, y: 30 + Math.max(0, upstreamTables.length - 1) * 55 },
        data: { label: `${wf?.name || '当前文件'}（当前）` },
        draggable: false,
        connectable: false,
        style: {
          ...baseStyle,
          background: '#e6f4ff',
          border: '1px solid #1677ff',
        },
      },
    ]
    const edges: FlowEdge[] = upstreamTables.map((_, index) => ({
      id: `lineage-edge-${index}`,
      source: `lineage-table-${index}`,
      target: `lineage-file-${id}`,
      markerEnd: { type: MarkerType.ArrowClosed, width: 22, height: 22, color: '#1677ff' },
      style: { stroke: '#1677ff', strokeWidth: 3 },
    }))
    return { nodes, edges }
  }, [implicitSingleScript, JSON.stringify(upstreamTables), id, wf?.name])
  // 表血缘：切到「表血缘」时按当前 SQL 实时解析（不入库，未发布也看得到）
  useEffect(() => {
    if (viewMode !== 'graph' || graphMode !== 'table') return
    let alive = true
    setLineageLoading(true)
    post<{ edges: { taskCode: string; source: string; target: string }[] }>(
      '/api/lineage/preview', { model: { tasks: model.tasks, edges: model.edges } },
    )
      .then((res) => { if (alive) setSqlLineage(res?.edges || []) })
      .catch(() => { if (alive) setSqlLineage([]) })
      .finally(() => { if (alive) setLineageLoading(false) })
    return () => { alive = false }
  }, [viewMode, graphMode, JSON.stringify(model.tasks)])

  const tableLineageGraph = useMemo(() => {
    const pairs = sqlLineage.filter((edge) => edge.source && edge.target)
    if (pairs.length === 0) return null
    const sources = Array.from(new Set(pairs.map((e) => e.source)))
    const targets = Array.from(new Set(pairs.map((e) => e.target)))
    const box = (text: string, extra?: CSSProperties) => ({
      width: 240,
      padding: '8px 10px',
      borderRadius: 8,
      fontSize: 12,
      background: '#fff',
      border: '1px solid #d9e2ec',
      color: '#1f2329',
      wordBreak: 'break-all' as const,
      ...extra,
    })
    const nodes: Node[] = [
      ...sources.map((table, index) => ({
        id: `ls-${index}`,
        position: { x: 20, y: 30 + index * 90 },
        data: { label: table },
        draggable: false,
        connectable: false,
        style: box(table),
      })),
      ...targets.map((table, index) => ({
        id: `lt-${index}`,
        position: { x: 420, y: 30 + index * 90 },
        data: { label: table },
        draggable: false,
        connectable: false,
        style: box(table, { background: '#e6f4ff', border: '1px solid #1677ff' }),
      })),
    ]
    const edgeList: FlowEdge[] = pairs.map((edge, index) => ({
      id: `le-${index}`,
      source: `ls-${sources.indexOf(edge.source)}`,
      target: `lt-${targets.indexOf(edge.target)}`,
      label: edge.taskCode,
      labelStyle: { fill: '#1677ff', fontSize: 12, fontWeight: 600 },
      labelBgStyle: { fill: '#e6f4ff', fillOpacity: 1 },
      labelBgPadding: [4, 2] as [number, number],
      labelBgBorderRadius: 4,
      markerEnd: { type: MarkerType.ArrowClosed, width: 22, height: 22, color: '#1677ff' },
      style: { stroke: '#1677ff', strokeWidth: 3 },
      animated: true,
    }))
    return { nodes, edges: edgeList }
  }, [sqlLineage])

  /** 右侧选中项已有自己的保存按钮时，页头不再重复展示保存 */
  const hasContextSave = configOpen && Boolean(selected || selectedScript || selectedScriptFolder)

  // ---- TC-8 界面卫生：同动作按钮只保留一个定义点 ----
  // 各使用处仍按原渲染条件（dirty / hasContextSave / 选中对象）包裹，
  // handler、loading、错误口径不变。
  // CONFLICT-WM3 裁决：一个动作 = 一个定义点，但**文案按上下文区分**（D38/D39 口径）。
  // 「保存」这个动作在工作流页有 4 个作用域（整个流程 / 节点 / 脚本信息 / 目录），
  // 统一成两个字会让用户不知道按下存的是哪一层，所以把 label/title 做成参数，
  // 去重的是 handler，不是信息量。
  // 判据：合并后各使用处渲染出的文案与提示，与 main@08ae580 的内联写法逐字相同
  //（差异只剩「同一 handler 写在 1 处而不是 6 处」）。
  const saveBtn = (opts: { size?: 'small'; icon?: boolean; label?: string; title?: string } = {}) => {
    const label = opts.label ?? '保存'
    return (
      <Button
        size={opts.size}
        type="primary"
        icon={opts.icon === false ? undefined : <SaveOutlined />}
        title={opts.title}
        loading={saving}
        onClick={() => save().catch((e) => message.error(errMsg(e)))}
      >
        {label}
      </Button>
    )
  }
  // 试跑只有一个作用域（当前 SQL，DEV），文案与提示都在定义点里固定。
  const runBtn = (opts: { size?: 'small' } = {}) => (
    <Button
      size={opts.size}
      icon={<PlayCircleOutlined />}
      loading={runLoading}
      title="只在 DEV 跑：跟随项目开发库执行当前 SQL，不生成运行实例（生产库需 ops:run:prod）"
      onClick={() => runNode()}
    >
      试跑（DEV）
    </Button>
  )
  const publishBtn = (opts: { title?: string; catchErr?: boolean } = {}) => (
    <Button
      size="small"
      icon={<CloudUploadOutlined />}
      title={opts.title ?? '生成版本并发布到 DEV / PROD'}
      onClick={() => {
        const p = openPublish()
        if (opts.catchErr !== false) p.catch((e) => message.error(errMsg(e)))
      }}
    >
      发布
    </Button>
  )
  const undoRedoGroup = () => (
    <Space.Compact>
      <Button
        size="small"
        icon={<UndoOutlined />}
        title="撤销（Ctrl+Z）"
        disabled={!canUndo || saving || !can('workflow:update')}
        onClick={undo}
      />
      <Button
        size="small"
        icon={<RedoOutlined />}
        title="重做（Ctrl+Shift+Z）"
        disabled={!canRedo || saving || !can('workflow:update')}
        onClick={redo}
      />
    </Space.Compact>
  )
  const newScriptBtn = () => (
    <Button size="small" icon={<PlusOutlined />} onClick={openNewScript}>
      新建脚本
    </Button>
  )

  const deployments = wf?.deployments || []
  const devDeployment = deployments.find((item) => item.env === 'DEV')
  const prodDeployment = deployments.find((item) => item.env === 'PROD')
  const prodPendingVersion = versions
    .filter((item) => item.prod_status === 'PENDING_APPROVAL'
      || (item.prod_status == null && item.status === 'PENDING_APPROVAL'))
    .sort((a, b) => b.version_no - a.version_no)[0]

  const displayNodes = useMemo(
    () => nodes.map((node) => ({
      ...node,
      data: { ...node.data, connectFrom: node.id === connectFrom },
    })),
    [nodes, connectFrom],
  )

  // 配置面板开合会改变画布宽度：重新 fit 一次，避免节点被挤出可视区
  useEffect(() => {
    const timer = window.setTimeout(() => {
      flow?.fitView({ padding: 0.25, maxZoom: 1.1, duration: 300 })
    }, 350)
    return () => window.clearTimeout(timer)
  }, [configOpen, viewMode, flow])

  // 首次加载工作流后等节点渲染完再 fit：ReactFlow 初始 fitView 可能早于节点测量，
  // 大图会有一列节点被裁到画布外
  const fittedWorkflow = useRef<number>()
  useEffect(() => {
    if (!flow || nodes.length === 0 || fittedWorkflow.current === id) return
    fittedWorkflow.current = id
    const timer = window.setTimeout(() => {
      flow.fitView({ padding: 0.2, maxZoom: 1, duration: 200 })
    }, 250)
    return () => window.clearTimeout(timer)
  }, [flow, nodes.length, id])

  // 切流程时可能有多个 GET 在飞：只接受最后一次请求的结果，
  // 否则 A 的响应回来会覆盖 B，甚至把 A 的内容保存到 B
  const loadSeq = useRef(0)
  // 第一次打开某个文件：只有一个脚本时直接选中并切到编码，打开就能改
  const autoOpenedRef = useRef<number | null>(null)
  const load = async () => {
    const seq = ++loadSeq.current
    setLoadError('')
    try {
      const data = await api<Workflow>(`/api/workflows/${id}`)
      if (seq !== loadSeq.current) return
      setWf(data)
      // 打开哪个项目的流程，全局当前项目就跟到哪
      if (data.project_id) setGlobalProject(data.project_id)
      let parsed: Model = { tasks: [], edges: [] }
      try {
        parsed = JSON.parse(data.model || '{}')
        parsed.tasks = parsed.tasks || []
        parsed.edges = parsed.edges || []
        parsed.tasks = autoLayoutTasks(parsed.tasks, parsed.edges)
      } catch {
        message.warning('文件内容解析失败，已按空内容打开')
      }
      // 文件就是脚本：老的空文件（或接口建的空文件）打开时自动补一个 SQL 脚本，
      // 直接给编辑器，不再提示「没有脚本，去创建脚本」。
      const needsDefaultScript = parsed.tasks.length === 0 && !(parsed.files || []).length
      if (needsDefaultScript) {
        parsed = {
          ...parsed,
          tasks: [{
            code: 'task_1',
            name: data.name,
            type: 'SQL',
            script: initialScript('SQL', data.name),
            datasource: 'DORIS',
            retries: 0,
            x: 140,
            y: 120,
          }],
        }
      }
      setModel(parsed)
      setNodes(toNodes(parsed.tasks))
      setEdges(toFlowEdges(parsed.edges))
      if (autoOpenedRef.current !== id) {
        autoOpenedRef.current = id
        if (parsed.tasks.length === 1 && !(parsed.files || []).length) {
          // 单脚本文件：直接选中它，右侧就显示类型/数据源/重试，不必先找节点
          setSelectedCode(parsed.tasks[0].code)
          setScriptSelected(undefined)
          // 文件就是脚本：单脚本文件打开直接落到编码页（新建文件、老空文件都是这种）
          if (needsDefaultScript || parsed.tasks[0].name === data.name) {
            setViewMode('code')
          }
        }
      }
      // 合并而不是覆盖：项目目录和其他流程的展开状态要保留，打开新流程时不能全收起
      setExpandedKeys((current) => Array.from(new Set([
        ...current,
        'proj:root',
        `pwf:${id}`,
        'group:nodes',
        ...scriptFolderKeys(parsed.files || [], parsed.folders || []).map((key) => `script:${key}`),
      ])))
      setVersions(data.versions || [])
      const snapshot: EditorSnapshot = { name: data.name, model: parsed }
      const history = historyRef.current
      history.stack = [snapshot]
      history.index = 0
      history.saved = snapshot
      mergeBaseRef.current = {
        model: parsed, revision: data.revision,
        name: data.name, description: data.description,
      }
      setMergeNotice(null)
      setHistoryTick((tick) => tick + 1)
      setDirty(false)
    } catch (e) {
      if (seq !== loadSeq.current) return
      setLoadError(e instanceof Error ? e.message : '加载文件失败')
    }
  }

  useEffect(() => {
    // 切流程后，上一流程在途的保存/元数据请求一律丢弃
    editorEpoch.current += 1
    // 切换流程先清空上一条流程：加载完成前页面只显示 loading，
    // 避免旧模型留在编辑器里被「保存」写进新的工作流
    setWf(undefined)
    setModel({ tasks: [], edges: [] })
    setNodes([])
    setEdges([])
    setDirty(false)
    setSelectedCode(undefined)
    setScriptSelected(undefined)
    setTreeSel(`pwf:${id}`)
    load()
  }, [id])

  // 窗口重新获得焦点：比较服务器 revision 与当前基线，提前提示冲突
  useEffect(() => {
    if (!wf || !id) return
    let alive = true
    const timer: { value?: number } = {}
    const onFocus = () => {
      // 连点切换窗口时防抖，避免一次切换发多个请求
      if (timer.value) window.clearTimeout(timer.value)
      timer.value = window.setTimeout(async () => {
        try {
          const latest = await api<Workflow>(`/api/workflows/${id}`)
          if (!alive) return
          if (latest.revision != null && wf.revision != null
              && Number(latest.revision) !== Number(wf.revision)) {
            setStaleNotice(
              '服务器上的版本已被其他人更新。直接保存会提示逐项合并；重新加载会丢弃本地未保存的改动。')
          } else {
            setStaleNotice('')
          }
        } catch {
          // 网络失败不打扰编辑，保存时仍会走冲突检测
        }
      }, 300)
    }
    window.addEventListener('focus', onFocus)
    return () => {
      alive = false
      if (timer.value) window.clearTimeout(timer.value)
      window.removeEventListener('focus', onFocus)
    }
  }, [id, wf?.revision])

  // 项目结构（目录 → 工作流）用于左侧树和页头切换菜单；切目录/不切都不影响当前编辑
  useEffect(() => {
    const pid = wf?.project_id
    if (!pid) return
    let alive = true
    Promise.all([
      api<Workflow[]>(`/api/projects/${pid}/workflows`),
      api<{ path: string }[]>(`/api/projects/${pid}/workflow-folders`).catch(() => []),
      api<Project[]>('/api/projects').catch(() => [] as Project[]),
    ]).then(([list, folderRows, projectList]) => {
      if (!alive) return
      setProjectWorkflows(list || [])
      setProjectFolders((folderRows || []).map((row) => row.path))
      setProjects(projectList || [])
      setProjectName((projectList || []).find((item) => item.id === pid)?.name || '')
      // 项目层级默认展开：打开流程后父目录仍在原位，兄弟流程一眼可见
      setExpandedKeys((current) => Array.from(new Set([
        ...current,
        'proj:root',
        `pwf:${id}`,
        ...projectDirPaths({
          workflows: list || [],
          folders: (folderRows || []).map((row) => row.path),
        }).map((path) => `pdir:${path}`),
      ])))
    }).catch(() => {
      // 切菜单/树拉不到不影响编辑，只是暂时只有当前流程
    })
    return () => { alive = false }
  }, [wf?.project_id, id])

  /** 删除项目目录：目录下还有东西时后端会拒绝，这里只删空目录。 */
  const removeProjectFolder = (path: string) => {
    const pid = wf?.project_id
    if (!pid) return
    Modal.confirm({
      title: `删除文件夹「${path}」？`,
      okText: '删除',
      content: '文件夹下还有子文件夹或文件时需要先移走；空文件夹会直接删除。',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/projects/${pid}/workflow-folders?path=${encodeURIComponent(path)}`)
          setProjectFolders((current) => current.filter(
            (item) => item !== path && !item.startsWith(`${path}/`)))
          setExpandedKeys((current) => current.filter(
            (key) => key !== `pdir:${path}` && !key.startsWith(`pdir:${path}/`)))
          if (treeSel === `pdir:${path}` || treeSel.startsWith(`pdir:${path}/`)) {
            setTreeSel(`pwf:${id}`)
          }
          message.success('文件夹已删除')
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除文件夹失败')
        }
      },
    })
  }

  // 右键菜单点外面就关
  useEffect(() => {
    if (!ctxMenu) return
    const close = () => setCtxMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [ctxMenu])

  /** a/b/c → [a, a/b, a/b/c]：新建/移动后只把受影响的层级合进树。 */
  const folderAncestors = (path: string): string[] => {
    const parts = path.split('/').filter(Boolean)
    return parts.map((_, index) => parts.slice(0, index + 1).join('/'))
  }

  /** 删除文件（工作流）：只摘掉这一行；删的是当前正在编辑的文件时回列表。 */
  const deleteWorkflow = (item: Workflow) => {
    Modal.confirm({
      title: `删除文件「${item.name}」？`,
      content: '会同时删除版本、调度与实例索引，已挂载的调度会一并下线。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/workflows/${item.id}`)
          setProjectWorkflows((current) => current.filter((row) => row.id !== item.id))
          message.success('文件已删除')
          if (item.id === id) {
            // 删的是当前文件：优先就地打开同项目里的另一个文件，不退回列表重载整页
            const next = projectWorkflows.find((row) => row.id !== item.id)
            if (next) nav(`/dev/${next.id}`)
            else nav('/dev')
          }
        } catch (e) {
          message.error(e instanceof Error ? e.message : '删除文件失败')
          throw e
        }
      },
    })
  }

  /** 移动文件夹后，把本地树里同一棵子树的前缀换掉，不重新拉取。 */
  const applyFolderMoveLocally = (from: string, to: string) => {
    const remap = (value: string) => (
      value === from || value.startsWith(`${from}/`)
        ? `${to}${value.slice(from.length)}`
        : value
    )
    setProjectFolders((current) => Array.from(new Set(current.map(remap))))
    setProjectWorkflows((current) => current.map((item) => (
      item.folder ? { ...item, folder: remap(item.folder) } : item
    )))
    if (wf?.folder) {
      const next = remap(wf.folder)
      if (next !== wf.folder) setWf({ ...wf, folder: next })
    }
    setExpandedKeys((current) => Array.from(new Set([
      ...current.filter((key) => key !== `pdir:${from}` && !key.startsWith(`pdir:${from}/`)),
      'proj:root',
      ...folderAncestors(to).map((path) => `pdir:${path}`),
    ])))
  }

  const moveToFolder = async () => {
    const pid = wf?.project_id
    if (!moveItem) return
    const dest = moveDest.trim()
    try {
      if (moveItem.kind === 'workflow') {
        await put(`/api/workflows/${moveItem.id}/folder`, { path: dest })
        setProjectWorkflows((current) => current.map((item) => (
          item.id === moveItem.id ? { ...item, folder: dest } : item
        )))
        if (wf?.id === moveItem.id) setWf({ ...wf, folder: dest })
        message.success(dest ? `已移动到 ${dest}` : '已移动到项目根')
      } else if (pid) {
        const moved = await put<{ from: string; to: string }>(
          `/api/projects/${pid}/workflow-folders`,
          { from: moveItem.path, to: dest },
        )
        applyFolderMoveLocally(moved.from, moved.to)
        message.success(`已移动到 ${moved.to}`)
      }
      setMoveItem(undefined)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '移动失败')
    }
  }

  /**
   * 右键只给资源管理器里的常识操作：
   * 项目＝新建文件夹/新建文件；文件夹＝新建文件/删除文件夹/移动到其他文件夹；
   * 文件＝删除/移动到其他文件夹。打开文件＝单击，脚本用右上「新建脚本」。
   */
  const ctxMenuItems = (key: string): {
    label: string
    danger?: boolean
    onClick: () => void
  }[] => {
    if (key === 'proj:root') {
      if (!can('workflow:update')) return []
      return [
        {
          label: '新建文件夹',
          onClick: () => {
            setProjectFolderValue('')
            setProjectFolderOpen(true)
          },
        },
        { label: '新建文件', onClick: () => openNewWorkflow('') },
      ]
    }
    if (key.startsWith('pdir:')) {
      const path = key.slice(5)
      if (!can('workflow:update')) return []
      return [
        {
          label: '新建文件夹',
          onClick: () => {
            setProjectFolderValue(`${path}/`)
            setProjectFolderOpen(true)
          },
        },
        { label: '新建文件', onClick: () => openNewWorkflow(path) },
        { label: '删除文件夹', danger: true, onClick: () => removeProjectFolder(path) },
        {
          label: '移动到其他文件夹',
          onClick: () => {
            setMoveItem({ kind: 'folder', path })
            setMoveDest('')
          },
        },
      ]
    }
    if (key.startsWith('pwf:')) {
      const wid = Number(key.slice(4))
      const item = projectWorkflows.find((row) => row.id === wid)
      if (!item || !can('workflow:update')) return []
      return [
        { label: '删除文件', danger: true, onClick: () => deleteWorkflow(item) },
        {
          label: '移动到其他文件夹',
          onClick: () => {
            setMoveItem({ kind: 'workflow', id: wid, name: item.name })
            setMoveDest(item.folder || '')
          },
        },
      ]
    }
    if (key.startsWith('script:')) {
      const path = key.slice(7)
      const isFile = scriptFiles.some((file) => file.path === path)
      if (!can('workflow:update')) return []
      if (isFile) {
        return [
          { label: '重命名 / 移动', onClick: () => selectScriptNode(path, true) },
          { label: '删除脚本', danger: true, onClick: () => removeScriptNode(path, true) },
        ]
      }
      return [
        { label: '新建脚本文件', onClick: () => openNewScriptFile(path) },
        { label: '新建子目录', onClick: () => openNewScriptFolder(path) },
        { label: '删除目录', danger: true, onClick: () => removeScriptNode(path, false) },
      ]
    }
    if (key.startsWith('task:')) {
      const code = key.slice(5)
      if (!can('workflow:update')) return []
      return [
        { label: '选中节点', onClick: () => selectTask(code) },
        { label: '删除节点', danger: true, onClick: () => removeTaskByCode(code) },
      ]
    }
    return []
  }

  // 质量规则列表：节点配置里的「质量规则编码」直接选，避免手抄
  useEffect(() => {
    const pid = wf?.project_id
    if (!pid) return
    let alive = true
    api<QualityRule[]>(`/api/projects/${pid}/quality/rules`)
      .then((list) => { if (alive) setQualityRules(list || []) })
      .catch(() => { if (alive) setQualityRules([]) })
    return () => { alive = false }
  }, [wf?.project_id])

  // 本项目的可绑定数据源；拉不到只影响下拉，不影响编辑
  useEffect(() => {
    const pid = wf?.project_id
    if (!pid) return
    let alive = true
    api<BindableDatasource[]>(`/api/projects/${pid}/datasources`)
      .then((list) => { if (alive) setDatasources(list || []) })
      .catch(() => { if (alive) setDatasources([]) })
    return () => { alive = false }
  }, [wf?.project_id])

  // 最近一次运行：每 20 秒刷新，编辑时也能看到线上结果
  useEffect(() => {
    let alive = true
    const fetchLatest = () => {
      api<Instance[]>(`/api/ops/instances?workflowId=${id}&limit=1`)
        .then((list) => { if (alive) setLatestRun(list.length > 0 ? list[0] : null) })
        .catch(() => { if (alive) setLatestRun(null) })
    }
    fetchLatest()
    const timer = window.setInterval(fetchLatest, 20000)
    return () => {
      alive = false
      window.clearInterval(timer)
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

  // 浏览器前进/后退不触发 beforeunload，也不会走 canLeaveCurrentPage。
  // 在 capture 阶段先拦住事件：取消时阻止 Router 处理并恢复当前 URL。
  useEffect(() => {
    if (!dirty) return
    const onPop = (event: PopStateEvent) => {
      if (!dirty) return
      if (window.confirm('当前文件有未保存的修改，离开会丢失。确定离开吗？')) {
        window.removeEventListener('popstate', onPop, true)
        return
      }
      event.stopImmediatePropagation()
      window.history.pushState(null, '', window.location.href)
    }
    window.addEventListener('popstate', onPop, true)
    return () => window.removeEventListener('popstate', onPop, true)
  }, [dirty])

  // 左侧菜单 / 退出登录是 SPA 内部跳转，不会触发 beforeunload，需要页面守卫
  useEffect(() => {
    if (!dirty) {
      setLeaveGuard(undefined)
      return
    }
    setLeaveGuard(() => window.confirm('当前文件有未保存的修改，离开会丢失。确定离开吗？'))
    return () => setLeaveGuard(undefined)
  }, [dirty])

  // 脚本/目录路径输入框跟随选中项
  useEffect(() => {
    setPathDraft(selectedScript?.path || selectedScriptFolder || '')
  }, [selectedScript?.path, selectedScriptFolder])

  // 浏览器标签也显示未保存标记，切到别的标签页时不会忘记保存
  useEffect(() => {
    if (!wf) return
    document.title = `${dirty ? '● ' : ''}${wf.name} · 数据开发`
    return () => {
      document.title = 'data-ai-agent 数据平台'
    }
  }, [wf?.name, dirty])

  /** 发布类操作只刷新版本/调度元数据，不能用服务端模型覆盖编辑器里未保存的改动 */
  const refreshMeta = async () => {
    const epoch = editorEpoch.current
    const data = await api<Workflow>(`/api/workflows/${id}`)
    if (epoch !== editorEpoch.current) return
    // 绝不能在这里采纳服务端的 revision/name/description：
    // 画布可能有未保存改动，提前推进 revision 会让下一次保存绕过 409。
    setWf((current) => (current ? {
      ...current,
      versions: data.versions,
      deployments: data.deployments,
      schedule: data.schedule,
      env: data.env,
    } : data))
    setVersions(data.versions || [])
  }

  const syncModel = (next: Model) => {
    setModel(next)
    setNodes(toNodes(next.tasks))
    setEdges(toFlowEdges(next.edges))
  }

  // ---------- 撤销 / 重做 ----------

  const sameSnapshot = (a: EditorSnapshot, b: EditorSnapshot) => a.name === b.name
    && JSON.stringify(a.model) === JSON.stringify(b.model)

  /** 把当前状态记一步；内容和栈顶一致时不产生空记录。 */
  const recordSnapshot = () => {
    const history = historyRef.current
    if (history.index < 0) return
    const current = liveSnapshot.current
    if (sameSnapshot(current, history.stack[history.index])) return
    const stack = history.stack.slice(0, history.index + 1).concat(current)
    history.stack = stack.length > HISTORY_LIMIT
      ? stack.slice(stack.length - HISTORY_LIMIT)
      : stack
    history.index = history.stack.length - 1
    setHistoryTick((tick) => tick + 1)
  }

  // 连续输入（打字、连续拖拽）合并成一步：停手 500ms 后才入栈
  useEffect(() => {
    if (historyRef.current.index < 0) return
    const timer = window.setTimeout(recordSnapshot, 500)
    return () => window.clearTimeout(timer)
  }, [model, wf?.name])

  const applySnapshot = (snap: EditorSnapshot) => {
    setWf((current) => (current ? { ...current, name: snap.name } : current))
    setModel(snap.model)
    setNodes(toNodes(snap.model.tasks))
    setEdges(toFlowEdges(snap.model.edges))
    setSelectedEdge(undefined)
    setSelectedCode((code) => (
      code && snap.model.tasks.some((task) => task.code === code) ? code : undefined))
    setScriptSelected((path) => (
      path && ((snap.model.files || []).some((file) => file.path === path)
        || (snap.model.folders || []).includes(path)) ? path : undefined))
    // 撤销回已保存的状态时，「未保存」标记要一起消失
    setDirty(!sameSnapshot(snap, historyRef.current.saved))
  }

  const undo = () => {
    // 先把尚未入栈的输入落成一步，否则第一次撤销会跳过刚输入的内容
    recordSnapshot()
    const history = historyRef.current
    if (history.index <= 0) return
    history.index -= 1
    applySnapshot(history.stack[history.index])
    setHistoryTick((tick) => tick + 1)
  }

  const redo = () => {
    const history = historyRef.current
    const top = history.index >= 0 ? history.stack[history.index] : undefined
    // Undo 之后又改了内容：这是新的分支，redo 应该失效而不是吞掉新改动
    if (top && !sameSnapshot(liveSnapshot.current, top)) return
    if (history.index >= history.stack.length - 1) return
    history.index += 1
    applySnapshot(history.stack[history.index])
    setHistoryTick((tick) => tick + 1)
  }

  const { canUndo, canRedo } = useMemo(() => {
    const history = historyRef.current
    const top = history.index >= 0 ? history.stack[history.index] : undefined
    const pending = Boolean(top) && !sameSnapshot(liveSnapshot.current, top as EditorSnapshot)
    return {
      canUndo: history.index > 0 || pending,
      // 有新编辑（pending）时不能 redo，否则会丢掉尚未入栈的改动
      canRedo: !pending && history.index >= 0
        && history.index < history.stack.length - 1,
    }
  }, [historyTick, model, wf?.name])

  // Ctrl/Cmd+Z 撤销，Ctrl+Shift+Z / Ctrl+Y 重做；弹窗打开时让位给输入框原生撤销
  const overlayOpen = newFileOpen || versionOpen || scheduleOpen
    || triggerOpen || previewOpen || runOpen || diffOpen

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.altKey) return
      if (event.defaultPrevented) return
      const target = event.target as HTMLElement | null
      const typing = !!target && (target.tagName === 'INPUT'
        || target.tagName === 'TEXTAREA' || target.isContentEditable)
      const key = event.key.toLowerCase()
      const confirmOpen = Array.from(document.querySelectorAll('.ant-modal-confirm'))
        .some((node) => (node as HTMLElement).offsetParent !== null)
      if (key === 's') {
        // 编辑器 textarea 里由 CodeEditor 处理；这里兜住画布/配置面板里的 Ctrl+S
        if (overlayOpen || confirmOpen || saving) return
        if (!can('workflow:update')) return
        event.preventDefault()
        if (dirty) save().catch((e) => message.error(errMsg(e)))
        else message.info('没有未保存的改动')
        return
      }
      const isUndo = key === 'z' && !event.shiftKey
      const isRedo = (key === 'z' && event.shiftKey) || key === 'y'
      if (!isUndo && !isRedo) return
      // 输入框内让浏览器原生撤销生效，别把整份画布快照回滚掉
      if (typing) return
      if (overlayOpen || confirmOpen || saving) return
      if (!can('workflow:update')) return
      event.preventDefault()
      if (isUndo) undo()
      else redo()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [overlayOpen, saving, historyTick, model, wf?.name, dirty])

  /** 选中节点：右侧显示配置，中间编码页显示节点脚本。 */
  const selectTask = (code: string) => {
    setSelectedCode(code)
    setScriptSelected(undefined)
    // 手动收起过配置面板时，选中节点也把面板带出来，否则类型/数据源/重试无处可改
    setConfigOpen(true)
  }

  /** 选中脚本或目录；文件自动切到编码视图。 */
  const selectScriptNode = (path: string, isLeaf: boolean) => {
    setSelectedCode(undefined)
    setScriptSelected(path)
    setConfigOpen(true)
    if (isLeaf) {
      setViewMode('code')
      expandForPath(path)
    } else {
      // 点目录就展开这一层：逐层往下看，父目录保持展开、不被收起
      setExpandedKeys((current) => (
        current.includes(`script:${path}`) ? current : [...current, `script:${path}`]
      ))
    }
  }

  /** 右侧配置直接改 model，画布节点与目录树同帧更新。 */
  const patchTask = (code: string, patch: Partial<Task> & { engine?: string }) => {
    setDirty(true)
    setModel((current) => ({
      ...current,
      tasks: current.tasks.map((task) => task.code === code ? { ...task, ...patch } : task),
    }))
    setNodes((current) => current.map((node) => node.id === code
      ? { ...node, data: { ...node.data, name: patch.name ?? node.data.name, type: patch.type ?? node.data.type } }
      : node))
  }

  const patchScript = (path: string, patch: Partial<ScriptFile>) => {
    setDirty(true)
    setModel((current) => ({
      ...current,
      files: (current.files || []).map((file) => file.path === path
        ? { ...file, ...patch } : file),
    }))
  }

  /** 当前节点的上游依赖（可以有多个）。 */
  const upstreamOf = (code: string) => model.edges
    .filter((edge) => edge.to === code)
    .map((edge) => edge.from)

  /** 改依赖即改连线；多条依赖一起生效，并阻止成环。 */
  const setDependencies = (code: string, deps: string[]) => {
    const unique = Array.from(new Set(deps.filter((dep) => dep && dep !== code)))
    const seen = new Set<string>([code])
    const stack = [code]
    while (stack.length > 0) {
      const current = stack.pop() as string
      for (const edge of model.edges) {
        if (edge.from === current && !seen.has(edge.to)) {
          seen.add(edge.to)
          stack.push(edge.to)
        }
      }
    }
    const bad = unique.find((dep) => seen.has(dep))
    if (bad) {
      message.error(`不能依赖 ${bad}：会形成循环`)
      return
    }
    const nextEdges = [
      ...model.edges.filter((edge) => edge.to !== code),
      ...unique.map((from) => ({ from, to: code })),
    ]
    const next = { ...model, edges: nextEdges }
    setModel(next)
    setEdges(toFlowEdges(nextEdges))
    setDirty(true)
  }

  /** 发布当前工作流：先保存当前编辑，再生成版本并打开发布弹窗。 */
  const openPublish = async () => {
    if (!can('workflow:update')) {
      message.error('没有发布权限')
      return
    }
    if (model.tasks.length === 0) {
      message.warning('文件中还没有脚本，无法发布')
      return
    }
    Modal.confirm({
      title: '发布当前文件？',
      content: '如有未保存改动会先保存，然后生成或复用版本，再选择 DEV / PROD 发布。',
      okText: '继续',
      onOk: async () => {
        try {
          if (dirty) {
            const result = await save(true)
            // 冲突时用户选择保留/放弃：不要再基于不确定的内容生成版本
            if (result !== 'saved') return
          }
          const ver = await post<{ version_no: number; created?: boolean }>(
            `/api/workflows/${id}/versions`, {})
          await refreshMeta()
          message.success(ver.created === false
            ? `内容与 v${ver.version_no} 相同，直接使用该版本发布`
            : `已生成 v${ver.version_no}，请选择环境发布`)
          setVersionOpen(true)
        } catch (e: any) {
          message.error(e.message || '发布失败')
          throw e
        }
      },
    })
  }

  type ConflictChoice = 'overwrite' | 'discard' | 'keep'
  /**
   * 并发保存冲突：三选一。
   * 用户不关心 revision/刷新这些系统词，只关心「我的修改会不会丢」，
   * 所以标题说人话、按钮统一以「我的修改」为主语，默认焦点放在安全的「保留」上。
   * Esc/关闭都按「保留」处理：手一抖不会丢改动。
   */
  const askOverwrite = (savedAt: string) => new Promise<ConflictChoice>((resolve) => {
    let settled = false
    let modal: ReturnType<typeof Modal.confirm>
    const finish = (choice: ConflictChoice) => {
      if (settled) return
      settled = true
      modal?.destroy()
      resolve(choice)
    }
    modal = Modal.confirm({
      title: '这个文件刚被别人保存过',
      icon: null,
      width: 520,
      content: (
        <div>
          <p style={{ marginBottom: 8 }}>你的修改还在，没有丢。</p>
          <p style={{ marginBottom: 0, color: 'var(--dw-text-3)', fontSize: 12 }}>
            {savedAt ? `服务器上的版本是 ${savedAt} 保存的。` : '服务器上的版本比你打开时新。'}
            「覆盖」会丢掉对方刚保存的内容，「丢弃」会丢掉你这次的编辑。
          </p>
        </div>
      ),
      footer: (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          <Button danger onClick={() => finish('discard')}>丢弃我的修改</Button>
          <Button onClick={() => finish('overwrite')}>用我的修改覆盖</Button>
          <Button type="primary" onClick={() => finish('keep')}>保留我的修改</Button>
        </div>
      ),
      onCancel: () => finish('keep'),
    })
  })

  type MergeDecision = 'apply' | 'force' | 'cancel'

  /**
   * 有冲突时逐项让用户选；默认全部「保留我的」。
   * 「先不合」不写服务器也不改画布，改动原样留着。
   */
  const askMerge = (merge: MergeResult, savedAt: string) => new Promise<{
    decision: MergeDecision
    choices: MergeChoices
  }>((resolve) => {
    const choices: MergeChoices = {}
    merge.conflicts.forEach((conflict) => { choices[conflict.key] = 'local' })
    let settled = false
    let modal: ReturnType<typeof Modal.confirm>
    const finish = (decision: MergeDecision) => {
      if (settled) return
      settled = true
      modal?.destroy()
      resolve({ decision, choices: { ...choices } })
    }
    const auto = describeMergeStats(merge.autoMerged)
    modal = Modal.confirm({
      title: '服务器上的版本也有改动',
      icon: null,
      width: 620,
      content: (
        <div>
          <p style={{ marginBottom: 8 }}>
            {savedAt ? `对方在 ${savedAt} 保存过这份文件。` : '对方在你打开之后保存过这份文件。'}
            不冲突的改动会直接合并{auto ? `（${auto}）` : ''}；
            下面 {merge.conflicts.length} 处改在同一个地方，需要你选。
          </p>
          <MergeConflictList conflicts={merge.conflicts} choices={choices} />
          <p style={{ marginTop: 8, marginBottom: 0, color: 'var(--dw-text-3)', fontSize: 12 }}>
            应用后画布变成合并结果，此时还没有保存；检查无误再点「保存」。
            位置冲突一律以你画布上的为准。
          </p>
        </div>
      ),
      footer: (
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          <Button onClick={() => finish('cancel')}>先不合并</Button>
          <Button danger onClick={() => finish('force')}>全部用我的覆盖</Button>
          <Button type="primary" onClick={() => finish('apply')}>应用合并结果</Button>
        </div>
      ),
      onCancel: () => finish('cancel'),
    })
  })

  /** 把合并结果应用到画布：更新 revision 让下次保存能成功，但保持「未保存」等用户确认。 */
  const applyMerged = (
    merged: Model,
    localModel: Model,
    remote: Workflow,
    remoteModel: Model,
    stats: MergeStats,
    conflictCount: number,
    savedAt: string,
  ) => {
    const previousRevision = wf?.revision
    const previousBase = mergeBaseRef.current
    recordSnapshot()
    syncModel(merged)
    setSelectedCode((code) => (
      code && merged.tasks.some((task) => task.code === code) ? code : undefined))
    setScriptSelected((path) => (
      path && ((merged.files || []).some((file) => file.path === path)
        || (merged.folders || []).includes(path)) ? path : undefined))
    setSelectedEdge(undefined)
    setWf((current) => (current
      ? { ...current, revision: remote.revision, updated_at: remote.updated_at }
      : current))
    mergeBaseRef.current = {
      model: remoteModel, revision: remote.revision,
      name: remote.name, description: remote.description,
    }
    setDirty(true)
    const carried = describeMergeStats(stats)
    setMergeNotice({
      text: `已拉取对方${savedAt ? ` ${savedAt} ` : ''}保存的改动`
        + (carried ? `（${carried}）` : '')
        + (conflictCount ? `，其中 ${conflictCount} 处冲突按你的选择处理` : '，没有冲突')
        + '。画布还没保存，检查后点「保存」。',
      revertModel: localModel,
      revertRevision: previousRevision,
      revertBase: previousBase,
    })
  }

  /** 撤回合并：恢复合并前的本地内容与 revision，改动仍然留在画布上。 */
  const revertMerge = () => {
    if (!mergeNotice) return
    syncModel(mergeNotice.revertModel)
    setWf((current) => (current
      ? { ...current, revision: mergeNotice.revertRevision }
      : current))
    mergeBaseRef.current = mergeNotice.revertBase
    setMergeNotice(null)
    setDirty(true)
    message.info('已撤回这次合并，你的修改都还在')
  }

  /** 保存结果：冲突时用户选择「保留」或「放弃并刷新」，调用方需要据此停止后续动作。 */
  type SaveResult = 'saved' | 'kept' | 'discarded' | 'merged'

  const persist = async (
    next: Model, silent = false, force = false,
  ): Promise<SaveResult> => {
    if (!wf) return 'saved'
    setSaving(true)
    const requestEpoch = editorEpoch.current
    // 保存期间用户可能继续编辑；响应回来时只应用「没有被新编辑覆盖」的部分
    const requestSnapshot = liveSnapshot.current
    const requestDescription = liveDescription.current
    try {
      const updated = await put<Workflow>(`/api/workflows/${id}`, {
        name: wf.name,
        description: wf.description,
        model: next,
        revision: wf.revision,
        force,
      })
      // 已经切到别的工作流：这次保存属于上一个 id，不能改当前状态
      if (requestEpoch !== editorEpoch.current) {
        return 'kept' as const
      }
      const changedDuringSave = !sameSnapshot(liveSnapshot.current, requestSnapshot)
        || liveDescription.current !== requestDescription
      setWf((current) => {
        if (!current) return updated
        if (changedDuringSave) {
          return {
            ...current,
            ...updated,
            // 保留保存期间产生的新名称/描述/本地模型
            name: liveSnapshot.current.name,
            description: liveDescription.current,
            model: current.model,
          }
        }
        // 合并而不是整体替换：PUT 详情不含 deployments/schedule，
        // 直接替换会把「DEV/PROD 已发布」标记和调度配置清空。
        return { ...current, ...updated }
      })
      // 保存点写进栈：之后撤销回这里，「未保存」标记会自动消失
      const snapshot: EditorSnapshot = { name: updated.name || wf.name, model: next }
      const history = historyRef.current
      history.saved = snapshot
      if (!changedDuringSave && history.index >= 0) {
        const top = history.stack[history.index]
        if (sameSnapshot(top, snapshot)) {
          history.stack[history.index] = snapshot
        } else {
          // 还没到 500ms 入栈的改动（或重命名这类立即落库的操作）补成一步
          history.stack = history.stack.slice(0, history.index + 1).concat(snapshot)
          history.index = history.stack.length - 1
          setHistoryTick((tick) => tick + 1)
        }
      }
      if (!changedDuringSave) {
        setModel(next)
      }
      setDirty(changedDuringSave)
      mergeBaseRef.current = {
        model: next, revision: updated.revision,
        name: updated.name, description: updated.description,
      }
      setMergeNotice(null)
      if (changedDuringSave) {
        message.info('已保存，但保存期间的新改动还未提交，请再保存一次')
      } else if (!silent) {
        message.success('已保存')
      }
      return 'saved' as const
    } catch (e: any) {
      if (requestEpoch !== editorEpoch.current) {
        return 'kept' as const
      }
      if (e?.status === 409 && !force) {
        // 先拉取服务器最新内容，再和本地做三方合并（Git 的 pull + merge）
        let remote: Workflow | null = null
        try {
          remote = await api<Workflow>(`/api/workflows/${id}`)
        } catch {
          remote = null
        }
        const remoteModel = remote ? parseModel(remote.model) : null
        const baseEntry = mergeBaseRef.current
        const base = baseEntry?.model || historyRef.current.saved.model
        const savedAt = remote?.updated_at ? dayjs(remote.updated_at).format('HH:mm') : ''

        // 名称/描述不参与 modelMerge：双方都改过时必须让用户选择，
        // 否则第二次保存会用旧名称静默覆盖对方的重命名。
        const remoteMetaChanged = !!(remote && baseEntry && (
          (remote.name || '') !== (baseEntry.name || '')
          || (remote.description || '') !== (baseEntry.description || '')
        ))
        const localMetaChanged = !!baseEntry && (
          (wf.name || '') !== (baseEntry.name || '')
          || (wf.description || '') !== (baseEntry.description || '')
        )
        if (remoteMetaChanged && localMetaChanged) {
          setSaving(false)
          const choice = await askOverwrite(savedAt)
          if (choice === 'overwrite') return persist(next, silent, true)
          if (choice === 'discard') {
            await load()
            message.info('已加载服务器上的最新版本，你这次的修改没有保留')
            return 'discarded'
          }
          message.warning('对方同时修改了名称/描述，修改还在，没有提交')
          return 'kept'
        }
        if (remoteMetaChanged && remote) {
          // 只有对方改了：采用对方的名称/描述，避免下次保存覆盖回去
          const remoteName = remote.name
          const remoteDescription = remote.description
          setWf((current) => (current
            ? { ...current, name: remoteName, description: remoteDescription }
            : current))
        }

        if (remote && remoteModel && base) {
          const merge = mergeModels(base, next, remoteModel)
          if (merge.conflicts.length === 0) {
            applyMerged(merge.model, next, remote, remoteModel, merge.autoMerged, 0, savedAt)
            message.info('对方改动已自动合并，检查后点「保存」')
            return 'merged'
          }
          const { decision, choices } = await askMerge(merge, savedAt)
          if (decision === 'force') {
            setSaving(false)
            return persist(next, silent, true)
          }
          if (decision === 'cancel') {
            message.warning('修改还在，没有合并。稍后点保存可以再试')
            return 'kept'
          }
          applyMerged(
            resolveMerged(merge, choices), next, remote, remoteModel,
            merge.autoMerged, merge.conflicts.length, savedAt,
          )
          message.info('已合并，检查后点「保存」')
          return 'merged'
        }

        // 拉不到对方内容（网络/权限）时，退回三选一
        const choice = await askOverwrite(savedAt)
        if (choice === 'overwrite') {
          setSaving(false)
          return persist(next, silent, true)
        }
        if (choice === 'discard') {
          await load()
          message.info('已加载服务器上的最新版本，你这次的修改没有保留')
          return 'discarded'
        }
        // 保留：不写服务器，dirty 保持，用户随时可以再点保存
        message.warning('修改还在，没有提交。稍后点保存可以再决定是否覆盖')
        return 'kept'
      }
      message.error(e instanceof Error ? e.message : '保存失败')
      throw e
    } finally {
      setSaving(false)
    }
  }

  const save = async (silent = false): Promise<SaveResult> => {
    if (!wf) return 'saved'
    const blankName = model.tasks.find((task) => !task.name.trim())
    if (blankName) throw new Error(`任务 ${blankName.code} 名称不能为空`)
    const positions = new Map(nodes.map((node) => [node.id, node.position]))
    const next: Model = {
      ...model,
      tasks: model.tasks.map((task) => {
        const p = positions.get(task.code)
        return p ? { ...task, x: p.x, y: p.y } : task
      }),
      edges: model.edges,
    }
    return persist(next, silent)
  }

  const addTask = () => {
    const n = model.tasks.length + 1
    let code = `task_${n}`
    while (model.tasks.some((task) => task.code === code)) {
      code = `${code}_x`
    }
    // 放在现有节点最右侧，避免固定坐标重叠；加完自动 fitView 保证可见
    const maxX = nodes.length ? Math.max(...nodes.map((node) => node.position.x)) : 0
    const task: TaskWithEngine = {
      code,
      name: `任务 ${n}`,
      type: 'SQL',
      engine: 'DORIS',
      script: 'select 1',
      datasource: 'DORIS',
      retries: 0,
      x: nodes.length ? maxX + 230 : 120,
      y: nodes.length ? nodes[0].position.y : 120,
    }
    const next = { ...model, tasks: [...model.tasks, task] }
    syncModel(next)
    setDirty(true)
    selectTask(code)
    if (viewMode === 'graph') {
      window.setTimeout(() => flow?.fitView({ padding: 0.25, maxZoom: 1.1, duration: 300 }), 80)
    }
  }

  /**
   * 新建脚本：脚本就是流程图里的一个节点，建的时候只要名称 + 类型 + 上下游，
   * 连好线直接出在画布上；SQL、数据源、重试这些细节仍然在右侧「配置」里改。
   */
  const openNewScript = () => {
    if (!can('workflow:update')) return
    newScriptForm.resetFields()
    newScriptForm.setFieldsValue({
      type: 'SQL', engine: 'DORIS', datasource: 'DORIS', upstream: [], downstream: [],
    })
    setNewScriptOpen(true)
  }

  const createScriptTask = async () => {
    try {
      const values = await newScriptForm.validateFields()
      const name = String(values.name || '').trim()
      if (!name) return
      let n = model.tasks.length + 1
      let code = `task_${n}`
      while (model.tasks.some((task) => task.code === code)) {
        n += 1
        code = `task_${n}`
      }
      const type = String(values.type || 'SQL')
      // SQL 脚本必须明确选引擎（表单已 required；这里再兜一层，避免空值默认成 DORIS）
      if (type === 'SQL' && !values.engine) {
        message.error('请选择执行引擎（DORIS / SPARK / FLINK）')
        return
      }
      const maxX = nodes.length ? Math.max(...nodes.map((node) => node.position.x)) : 0
      const task: TaskWithEngine = {
        code,
        name,
        type,
        ...enginePatch(type, values.engine),
        script: initialScript(type, name),
        datasource: values.datasource || 'DORIS',
        retries: 0,
        x: nodes.length ? maxX + 230 : 120,
        y: nodes.length ? nodes[0].position.y : 120,
      }
      const upstream: string[] = (values.upstream || []).filter((item: string) => item !== code)
      const downstream: string[] = (values.downstream || []).filter((item: string) => item !== code)
      const edges: Edge[] = [
        ...model.edges,
        ...upstream.map((from) => ({ from, to: code })),
        ...downstream.map((to) => ({ from: code, to })),
      ].filter((edge, index, all) => all.findIndex(
        (other) => other.from === edge.from && other.to === edge.to,
      ) === index)
      const next: Model = { ...model, tasks: [...model.tasks, task], edges }
      syncModel(next)
      setDirty(true)
      setNewScriptOpen(false)
      newScriptForm.resetFields()
      selectTask(code)
      message.success(`已新建脚本：${name}`)
      if (viewMode === 'graph') {
        window.setTimeout(() => flow?.fitView({ padding: 0.25, maxZoom: 1.1, duration: 300 }), 80)
      }
    } catch (e: any) {
      if (!e?.errorFields) message.error(e.message || '新建脚本失败')
    }
  }

  const removeTaskByCode = (code: string) => {
    const next: Model = {
      ...model,
      tasks: model.tasks.filter((task) => task.code !== code),
      edges: model.edges.filter((edge) => edge.from !== code && edge.to !== code),
    }
    syncModel(next)
    if (selectedCode === code) setSelectedCode(undefined)
    setDirty(true)
    message.info('已从画布移除，点「保存」后生效')
  }

  const removeTask = () => {
    if (!selected) return
    removeTaskByCode(selected.code)
  }

  /** 展开某路径的所有上级目录，保证新建/打开后节点可见。 */
  const expandForPath = (path: string) => {
    const parts = path.split('/').filter(Boolean)
    parts.pop()
    const keys = [
      // 脚本文件挂在「脚本文件」分组下面，建完要连分组一起展开才看得到
      'group:files',
      ...parts.map((_, index) => `script:${parts.slice(0, index + 1).join('/')}`),
    ]
    setExpandedKeys((current) => Array.from(new Set([...current, ...keys])))
  }

  const createScriptFile = async () => {
    try {
      const values = await newFileForm.validateFields()
      const path = normalizeScriptPath(values.path)
      if (scriptFiles.some((file) => file.path === path) || scriptFolders.includes(path)) {
        throw new Error(`路径已存在：${path}`)
      }
      const language = values.language || 'SQL'
      const content = initialScript(language, path)
      const next: Model = { ...model, files: [...scriptFiles, { path, content, language }] }
      setModel(next)
      setDirty(true)
      expandForPath(path)
      setNewFileOpen(false)
      newFileForm.resetFields()
      setSelectedCode(undefined)
      setScriptSelected(path)
      setViewMode('code')
      message.success('已创建脚本，点「保存」后生效')
    } catch (e: any) {
      if (!e?.errorFields) message.error(e.message || '创建脚本失败')
    }
  }

  /** 先建空目录再往里放脚本/子目录；目录只是占位，建完立即出现在树里。 */
  const createScriptFolder = async () => {
    try {
      const values = await newFolderForm.validateFields()
      const path = normalizeScriptPath(values.path, true)
      if (scriptFolders.includes(path)) throw new Error(`目录已存在：${path}`)
      // 同名文件/文件子树会让 buildScriptTree 用叶子覆盖目录节点，
      // 目录里的脚本会从树上消失（数据还在，但无法再操作）。
      if (scriptFiles.some((file) => file.path === path || file.path.startsWith(`${path}/`))) {
        throw new Error(`已存在同名文件或文件目录：${path}`)
      }
      if (scriptFiles.some((file) => path.startsWith(`${file.path}/`))) {
        throw new Error(`上级路径是文件，不能创建目录：${path}`)
      }
      const next: Model = { ...model, folders: [...scriptFolders, path] }
      setModel(next)
      setDirty(true)
      expandForPath(path)
      setNewFolderOpen(false)
      newFolderForm.resetFields()
      setSelectedCode(undefined)
      setScriptSelected(path)
      message.success('已创建目录，点「保存」后生效')
    } catch (e: any) {
      if (!e?.errorFields) message.error(e.message || '创建目录失败')
    }
  }

  const removeScriptNode = (path: string, isFile: boolean) => {
    const prefix = `${path}/`
    const next: Model = isFile
      ? { ...model, files: scriptFiles.filter((file) => file.path !== path) }
      : {
          ...model,
          files: scriptFiles.filter((file) => file.path !== path && !file.path.startsWith(prefix)),
          folders: scriptFolders.filter((folder) => folder !== path && !folder.startsWith(prefix)),
        }
    setModel(next)
    setDirty(true)
    if (scriptSelected && (scriptSelected === path || scriptSelected.startsWith(prefix))) {
      setScriptSelected(undefined)
      if (viewMode === 'code') setViewMode('graph')
    }
    message.info('已删除，点「保存」后生效')
  }

  /** 改路径 = 重命名或移动到其他目录；文件与目录都走同一套校验（树右键/拖拽也复用）。 */
  const applyScriptPath = async (current: string, rawNext: string, isFile: boolean) => {
    let next = ''
    try {
      next = normalizeScriptPath(rawNext, !isFile)
    } catch (e) {
      // 以前这里直接抛错，调用处 .catch(() => undefined) 吞掉，点重命名毫无反应
      message.error(e instanceof Error ? e.message : '路径不合法')
      return
    }
    if (!next) {
      message.error('路径不能为空')
      return
    }
    if (next === current) {
      message.info('路径没有变化')
      return
    }
    if (isFile) {
      if (scriptFiles.some((file) => file.path === next) || scriptFolders.includes(next)) {
        message.error(`目标路径已存在：${next}`)
        return
      }
    } else {
      const targetPrefix = `${next}/`
      const targetExists = scriptFolders.includes(next)
        || scriptFiles.some((file) => file.path === next || file.path.startsWith(targetPrefix))
        || scriptFiles.some((file) => next.startsWith(`${file.path}/`))
      if (targetExists) {
        message.error(`目标目录已存在：${next}`)
        return
      }
      if (next.startsWith(`${current}/`)) {
        message.error('不能把目录移动到自己的子目录里')
        return
      }
    }
    const nextModel: Model = isFile ? {
      ...model,
      files: scriptFiles.map((file) => file.path === current ? { ...file, path: next } : file),
    } : {
      ...model,
      files: scriptFiles.map((file) => file.path === current || file.path.startsWith(`${current}/`)
        ? { ...file, path: next + file.path.slice(current.length) } : file),
      folders: [
        ...scriptFolders.filter((path) => path !== current && !path.startsWith(`${current}/`)),
        ...scriptFolders
          .filter((path) => path === current || path.startsWith(`${current}/`))
          .map((path) => next + path.slice(current.length)),
      ],
    }
    const wasDirty = dirty
    setRenaming(true)
    try {
      // 重命名会立即落库：先把改名前状态记一步，撤销才能回到旧路径
      recordSnapshot()
      setModel(nextModel)
      setScriptSelected(next)
      setPathDraft(next)
      expandForPath(next)
      const result = await persist(nextModel, true)
      if (result === 'saved') {
        message.success(isFile ? '已重命名 / 移动脚本' : '已重命名 / 移动目录')
      } else if (result === 'kept') {
        // 本地路径没落库，必须标记未保存，否则页头显示「已保存」误导用户
        setDirty(true)
      }
    } catch {
      // 落库失败：回滚本地路径，避免界面显示已改名但服务器没有
      setModel(model)
      setScriptSelected(current)
      setPathDraft(current)
      setDirty(wasDirty)
    } finally {
      setRenaming(false)
    }
  }

  const renameScriptNode = async (isFile: boolean) => {
    const current = isFile ? selectedScript?.path : selectedScriptFolder
    if (!current) return
    await applyScriptPath(current, pathDraft, isFile)
  }

  /** 单节点试跑：执行编辑器里当前的 SQL（含未保存改动），不生成 Prefect 运行。 */
  const runNode = async () => {
    if (!selected) return
    setRunOpen(true)
    setRunLoading(true)
    setRunError('')
    setRunResult(undefined)
    try {
      const result = await post<RunResult>(`/api/workflows/${id}/tasks/${selected.code}/run`, {
        sql: selected.script || '',
        maxRows: 100,
        bizDate: dayjs().format('YYYY-MM-DD'),
      })
      setRunResult(result)
    } catch (e) {
      setRunError(e instanceof Error ? e.message : '试跑失败')
    } finally {
      setRunLoading(false)
    }
  }

  /** 判断从 start 出发能否到达 target，用于阻止连线成环。 */
  const hasPath = (start: string, target: string): boolean => {
    const stack = [start]
    const seen = new Set<string>()
    while (stack.length > 0) {
      const cur = stack.pop() as string
      if (cur === target) return true
      if (seen.has(cur)) continue
      seen.add(cur)
      model.edges.filter((edge) => edge.from === cur).forEach((edge) => stack.push(edge.to))
    }
    return false
  }

  const addEdge = (from: string, to: string): boolean => {
    if (from === to) {
      message.warning('任务不能连接自己')
      return false
    }
    if (hasPath(to, from)) {
      message.error(`不能连接 ${from} → ${to}：会形成循环依赖`)
      return false
    }
    if (model.edges.some((e) => e.from === from && e.to === to)) return false
    const next = { ...model, edges: [...model.edges, { from, to }] }
    syncModel(next)
    setDirty(true)
    return true
  }

  const removeSelectedEdge = () => {
    if (!selectedEdge) return
    const nextEdges = model.edges.filter(
      (edge) => !(edge.from === selectedEdge.from && edge.to === selectedEdge.to),
    )
    setModel({ ...model, edges: nextEdges })
    setEdges(toFlowEdges(nextEdges))
    setSelectedEdge(undefined)
    setDirty(true)
    message.info('已删除连线，点「保存」后生效')
  }

  const onConnect = (connection: Connection) => {
    if (!connection.source || !connection.target) return
    if (addEdge(connection.source, connection.target)) {
      message.success(`已连接 ${connection.source} → ${connection.target}（记得保存）`)
    }
  }

  const onNodeClick = (node: Node) => {
    setSelectedEdge(undefined)
    if (!connectMode) {
      selectTask(node.id)
      return
    }
    if (!connectFrom) {
      setConnectFrom(node.id)
      message.info(`已选择起点 ${node.id}，再点击目标任务`)
      return
    }
    if (connectFrom === node.id) {
      setConnectFrom(undefined)
      return
    }
    if (addEdge(connectFrom, node.id)) {
      message.success(`已连接 ${connectFrom} → ${node.id}（记得保存）`)
    } else if (model.edges.some((e) => e.from === connectFrom && e.to === node.id)) {
      message.info('这两个任务已经连过了')
    }
    setConnectFrom(undefined)
  }

  const onNodesChange = (changes: NodeChange[]) => {
    setNodes((current) => applyNodeChanges(changes, current))
  }

  const onEdgesChange = (changes: EdgeChange[]) => {
    if (changes.some((change) => change.type === 'remove' || change.type === 'add')) {
      setDirty(true)
    }
    const next = applyEdgeChanges(changes, edges)
    setEdges(next)
    setModel((current) => ({
      ...current,
      edges: next.map((edge) => ({ from: edge.source, to: edge.target })),
    }))
  }

  const createVersion = async () => {
    if (dirty) {
      const result = await save(true)
      if (result !== 'saved') return
    }
    const ver = await post<{ version_no: number; created?: boolean }>(
      `/api/workflows/${id}/versions`, {})
    if (ver.created === false) {
      message.info(`内容与 v${ver.version_no} 相同，未生成新版本`)
    } else {
      message.success(`已生成新版本 v${ver.version_no}`)
    }
    await refreshMeta()
    setVersionOpen(true)
  }

  /** 版本 → 当前编辑内容的差异；版本列表按需拉取（带 model 快照）。 */
  const openDiff = async (row: Version) => {
    setDiffLoadingFor(row.version_no)
    try {
      const all = await api<Version[]>(`/api/workflows/${id}/versions`)
      const target = all.find((item) => item.version_no === row.version_no)
      if (!target?.model) throw new Error('该版本没有模型快照')
      setDiffData(diffModels(target.model, model))
      setDiffVersionNo(row.version_no)
      setDiffDirty(dirty)
      setDiffOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '读取版本内容失败')
    } finally {
      setDiffLoadingFor(undefined)
    }
  }

  const publish = async (versionNo: number, env: string) => {
    const result = await post<{ status: string; message?: string; dagId?: string }>(
      `/api/workflows/${id}/versions/${versionNo}/publish`, { env },
    )
    if (result.status === 'PENDING_APPROVAL') {
      message.info(result.message || '已提交生产审批')
    } else {
      // 发布完的下一步通常是看运行结果：消息里直接给入口，不用自己找菜单
      message.success({
        duration: 8,
        content: (
          <span>
            已发布到 {env}：{result.dagId}
            {' '}
            <a onClick={() => { if (canLeaveCurrentPage()) nav(`/ops/instances?workflowId=${id}`) }}>
              查看运行实例
            </a>
          </span>
        ),
      })
    }
    await refreshMeta()
  }

  const approve = async (versionNo: number) => {
    const result = await post<{ dagId?: string }>(
      `/api/workflows/${id}/versions/${versionNo}/approve`, { env: 'PROD' },
    )
    message.success(`已审批发布 ${result.dagId || ''}`)
    await refreshMeta()
  }

  const rollback = async (versionNo: number, env: string) => {
    const result = await post<{ dagId?: string }>(`/api/workflows/${id}/rollback/${versionNo}`, { env })
    message.success(`已回滚并发布 ${result.dagId || ''}`)
    await refreshMeta()
  }

  /** 版本弹窗操作统一加锁：请求期间相关按钮转圈并禁用，避免重复发布。 */
  const runVersionAction = async (key: string, fn: () => Promise<void>) => {
    if (versionBusy) return
    setVersionBusy(key)
    try {
      await fn()
    } catch (e: any) {
      message.error(e?.message || '操作失败')
    } finally {
      setVersionBusy(undefined)
    }
  }

  const saveSchedule = async () => {
    const values = await scheduleForm.validateFields()
    await put(`/api/workflows/${id}/schedule`, {
      env: wf?.env || 'DEV',
      status: values.status,
      scheduleType: values.scheduleType,
      startAt: values.startAt.format('YYYY-MM-DD HH:mm'),
      weekdays: values.weekdays?.join(','),
      monthDays: values.monthDays?.join(','),
      endDate: values.scheduleType === 'ONCE'
        ? undefined : values.endDate?.format('YYYY-MM-DD'),
    })
    message.success('调度已保存；请重新发布后生效')
    setScheduleOpen(false)
    // 只刷新工作流元数据：load() 会用服务端模型覆盖编辑器，
    // 把画布上未保存的节点/脚本/连线一起抹掉
    await refreshMeta()
  }

  const doTrigger = async () => {
    const values = await triggerForm.validateFields()
    setTriggering(true)
    try {
      const result = await post<{ runId: string; dagId: string }>(`/api/workflows/${id}/trigger`, {
        env: values.env,
        bizDate: values.bizDate.format('YYYY-MM-DD'),
      })
      message.success(`已触发 ${result.dagId} / ${result.runId}`)
      setTriggerOpen(false)
      // 触发后直接落到这个工作流的实例列表，不用再去运维中心里找；
      // 同样要过未保存守卫，否则画布改动静默丢失
      if (canLeaveCurrentPage()) nav(`/ops/instances?workflowId=${id}`)
    } catch (e) {
      const msg = e instanceof Error ? e.message : '触发失败'
      // 发布后 Flow 文件由 ConfigMap 异步挂载，立刻触发会暂时 503；
      // 弹窗保持打开，告诉用户「等一下再点一次」，而不是只抛原始错误。
      message.error(msg.includes('尚未同步')
        ? '执行器还在同步 Flow 文件（通常 1 分钟内完成），稍等后点「触发」重试即可'
        : msg)
    } finally {
      setTriggering(false)
    }
  }

  // ---------------------------------------------------------------- 补数据

  const refreshBackfillHistory = async () => {
    try {
      setBackfillHistory(await api<BackfillSummary[]>(`/api/workflows/${id}/backfills`))
    } catch {
      // 历史拉不到不影响提交/查看当前作业
    }
  }

  const openBackfill = () => {
    backfillForm.resetFields()
    backfillForm.setFieldsValue({
      env: wf?.env || 'DEV',
      parallelism: 2,
      // 默认补 T-7 ~ T-1：当天数据通常还在跑，不抢日常调度
      range: [dayjs().subtract(7, 'day'), dayjs().subtract(1, 'day')],
    })
    setBackfillJob(undefined)
    setBackfillOpen(true)
    refreshBackfillHistory()
  }

  const submitBackfill = async () => {
    if (backfillBusy) return
    const values = await backfillForm.validateFields()
    setBackfillBusy(true)
    try {
      const job = await post<BackfillJob>(`/api/workflows/${id}/backfill`, {
        env: values.env,
        dateStart: values.range[0].format('YYYY-MM-DD'),
        dateEnd: values.range[1].format('YYYY-MM-DD'),
        parallelism: values.parallelism,
      })
      setBackfillJob(job)
      message.success(`已提交补数作业 #${job.jobId}，共 ${job.totalDays} 天`)
      refreshBackfillHistory()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交补数失败')
    } finally {
      setBackfillBusy(false)
    }
  }

  const loadBackfillJob = async (jobId: number) => {
    try {
      const job = await api<BackfillJob>(`/api/backfills/${jobId}`)
      setBackfillJob(job)
      if (job.status !== 'RUNNING') refreshBackfillHistory()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '读取补数进度失败')
    }
  }

  const cancelBackfill = async () => {
    if (!backfillJob) return
    try {
      setBackfillJob(await post<BackfillJob>(`/api/backfills/${backfillJob.jobId}/cancel`))
      message.success('已取消：未开始的日期已跳过')
      refreshBackfillHistory()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '取消失败')
    }
  }

  const retryBackfill = async (date?: string) => {
    if (!backfillJob) return
    try {
      setBackfillJob(await post<BackfillJob>(`/api/backfills/${backfillJob.jobId}/retry-failed`,
        date ? { date } : {}))
      message.success(date ? `已重新排队 ${date}` : '失败的天已重新排队')
      refreshBackfillHistory()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重跑失败的天失败')
    }
  }

  // 运行中的作业每 3 秒拉一次进度；结束后停止轮询（不拖住页面）
  useEffect(() => {
    if (!backfillOpen || backfillJob?.status !== 'RUNNING') return
    const jobId = backfillJob.jobId
    const timer = window.setInterval(() => { loadBackfillJob(jobId).catch(() => undefined) }, 3000)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [backfillOpen, backfillJob?.status, backfillJob?.jobId])

  const preview = async () => {
    if (dirty) {
      const saved = await save(true)
      if (saved !== 'saved') return
    }
    const result = await api<{ python: string; dagId: string }>(`/api/workflows/${id}/dag`)
    setPreviewCode(`# ${result.dagId}\n${result.python}`)
    setPreviewOpen(true)
  }

  /**
   * 在编辑器里直接新建同项目的流程：默认放到当前流程所在目录，
   * 建完直接打开，不用先退回数据开发页。
   */
  const createWorkflowHere = async () => {
    const values = await newWorkflowForm.validateFields()
    if (!wf?.project_id) return
    if (!canLeaveCurrentPage()) return
    try {
      const { type = 'SQL', engine, ...rest } = values
      if (type === 'SQL' && !engine) {
        message.error('请选择执行引擎（DORIS / SPARK / FLINK）')
        return
      }
      const created = await post<Workflow>(`/api/projects/${wf.project_id}/workflows`, {
        ...rest,
        folder: (values.folder || '').trim(),
        // 新文件带一个同类型脚本，打开就能写，不用再找「新建脚本」
        model: {
          tasks: [{
            code: 'task_1',
            name: values.name,
            type,
            ...enginePatch(type, engine),
            script: initialScript(type, values.name),
            datasource: 'DORIS',
            retries: 0,
            x: 140,
            y: 120,
          }],
          edges: [],
          files: [],
          folders: [],
        },
      })
      setNewWorkflowOpen(false)
      newWorkflowForm.resetFields()
      message.success(`已创建：${created.name}`)
      nav(`/dev/${created.id}`)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '新建文件失败')
    }
  }

  const openNewScriptFile = (base: string) => {
    if (!can('workflow:update')) return
    newFileForm.setFieldsValue({ path: base ? `${base}/` : '', language: 'SQL' })
    setNewFileOpen(true)
  }

  const openNewScriptFolder = (base: string) => {
    if (!can('workflow:update')) return
    newFolderForm.setFieldsValue({ path: base ? `${base}/` : '' })
    setNewFolderOpen(true)
  }

  /** 依赖表弹框：库在左、表在右，表支持搜索和多选，只列有权限的表。 */
  const openDependencyPicker = async () => {
    if (!can('workflow:update')) return
    setDepSelected(model.upstreamTables || [])
    setDepSearch('')
    setDepSelectedSearch('')
    setDepSchemaSearch('')
    setDepOpen(true)
    setDepLoading(true)
    try {
      const res = await api<{ tables: AccessTableRow[] }>('/api/access/resources')
      const accessible = (res.tables || []).filter((table) => table.level !== 'NONE' || table.owner)
      setDepTables(accessible)
      const first = accessible[0]
      setDepSchema(first ? `${first.tenant}:${first.catalog}:${first.schemaName}` : '')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载可依赖的表失败')
    } finally {
      setDepLoading(false)
    }
  }

  const toggleDepTable = (table: AccessTableRow, checked: boolean) => {
    const key = `${table.catalog}.${table.schemaName}.${table.tableName}`
    setDepSelected((current) => {
      if (!checked) return current.filter((item) => tableRefKey(item) !== key)
      if (current.some((item) => tableRefKey(item) === key)) return current
      return [...current, {
        catalog: table.catalog,
        schemaName: table.schemaName,
        tableName: table.tableName,
      }]
    })
  }

  const removeDepTable = (key: string) => {
    setDepSelected((current) => current.filter((item) => tableRefKey(item) !== key))
  }

  const selectAllVisibleTables = (checked: boolean) => {
    const keys = depVisibleTables.map(
      (table) => `${table.catalog}.${table.schemaName}.${table.tableName}`)
    setDepSelected((current) => {
      if (!checked) return current.filter((item) => !keys.includes(tableRefKey(item)))
      const next = [...current]
      depVisibleTables.forEach((table) => {
        const key = `${table.catalog}.${table.schemaName}.${table.tableName}`
        if (!next.some((item) => tableRefKey(item) === key)) {
          next.push({
            catalog: table.catalog,
            schemaName: table.schemaName,
            tableName: table.tableName,
          })
        }
      })
      return next
    })
  }

  const confirmDependencies = () => {
    syncModel({ ...model, upstreamTables: depSelected })
    setDirty(true)
    setDepOpen(false)
    message.success(depSelected.length
      ? `已选择 ${depSelected.length} 张依赖表，点保存后生效`
      : '已清空依赖表，点保存后生效')
  }

  /** 新建流程：编码/名称每次清空，目录默认是当前流程所在目录（可改成新路径，目录自动生成）。 */
  const openNewWorkflow = (folder: string) => {
    newWorkflowForm.resetFields()
    newWorkflowForm.setFieldsValue({ env: 'DEV', folder })
    setNewWorkflowOpen(true)
  }

  /** 先在项目树下建空目录，再把流程拖进去；输入的是相对项目根的整条路径。 */
  const createProjectFolder = async () => {
    const pid = wf?.project_id
    const path = projectFolderValue.trim().replace(/^\/+|\/+$/g, '')
    if (!pid) return
    if (!path) {
      message.warning('请输入目录路径')
      return
    }
    try {
      await post(`/api/projects/${pid}/workflow-folders`, { path })
      // 只把新目录和它的父级合进树，不重拉整棵树
      setProjectFolders((current) => Array.from(new Set([...current, ...folderAncestors(path)])))
      setExpandedKeys((current) => Array.from(new Set([
        ...current,
        'proj:root',
        ...folderAncestors(path).map((item) => `pdir:${item}`),
      ])))
      setProjectFolderOpen(false)
      setProjectFolderValue('')
      message.success(`文件夹已创建：${path}`)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建目录失败')
    }
  }

  /** 工作流移动到项目目录（树内拖拽用）。 */
  const moveWorkflowTo = async (workflowId: number, folder: string) => {
    if (!can('workflow:update')) return
    try {
      await put(`/api/workflows/${workflowId}/folder`, { path: folder })
      setProjectWorkflows((current) => current.map((item) => (
        item.id === workflowId ? { ...item, folder } : item
      )))
      if (wf?.id === workflowId) setWf({ ...wf, folder })
      message.success(folder ? `已移动到 ${folder}` : '已移动到项目根目录')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '移动失败')
    }
  }

  const currentWfKey = `pwf:${id}`

  /** 新建脚本/目录时默认落在当前选中的目录里。 */
  const selectedScriptBase = scriptSelected
    ? (scriptFiles.some((file) => file.path === scriptSelected)
      ? dirOf(scriptSelected)
      : scriptSelected)
    : ''

  /** 鼠标下那一行的 data-tree-key：antd 在多级树里可能把落点报成相邻行。 */
  const rowKeyAt = (x: number, y: number): string | null => {
    const el = document.elementFromPoint(x, y) as HTMLElement | null
    const keyEl = el?.closest?.('.ant-tree-treenode')
      ?.querySelector?.('[data-tree-key]') as HTMLElement | null
    return keyEl?.dataset?.treeKey || null
  }

  /** 正在拖的行；目录行不能拖，落到目录上的事件由外层兜住，需要从这里取。 */
  const dragKeyRef = useRef<string | null>(null)

  /** 树内拖拽：脚本拖进脚本目录，流程文件拖进项目目录。 */
  const applyTreeDrop = (dragKey: string, dropKey: string) => {
    setExpandedKeys((current) => {
      if (dropKey.startsWith('pdir:') || dropKey.startsWith('script:')) {
        return current.includes(dropKey) ? current : [...current, dropKey]
      }
      return current
    })
    if (dragKey.startsWith('script:')) {
      const path = dragKey.slice(7)
      const isFile = scriptFiles.some((file) => file.path === path)
      let dest: string | null = null
      if (dropKey.startsWith('script:')) {
        dest = dropKey.slice(7)
        // 拖到脚本文件上不是「移入目录」；以前会拼出 b.sql/a.sql 这种非法路径
        if (scriptFiles.some((file) => file.path === dest)) {
          message.warning('不能移动到另一个脚本文件上，请拖到目录节点')
          return
        }
      } else if (dropKey === currentWfKey || dropKey === 'group:nodes'
        || dropKey === 'group:files') dest = ''
      if (dest === null) return
      if (dest && (dest === path || dest.startsWith(`${path}/`))) {
        message.warning('不能移动到自己的子目录里')
        return
      }
      const base = path.split('/').filter(Boolean).pop() || path
      applyScriptPath(path, dest ? `${dest}/${base}` : base, isFile)
      return
    }
    if (dragKey.startsWith('pwf:')) {
      const movedId = Number(dragKey.slice(4))
      const moved = projectWorkflows.find((item) => item.id === movedId)
      if (!moved) return
      let dest: string | null = null
      if (dropKey.startsWith('pdir:')) dest = dropKey.slice(5)
      else if (dropKey === 'proj:root') dest = ''
      else if (dropKey.startsWith('pwf:') && Number(dropKey.slice(4)) !== movedId) {
        // 拖到别的流程文件上：跟它放在同一个目录
        const target = projectWorkflows.find((item) => item.id === Number(dropKey.slice(4)))
        dest = target ? (target.folder || '') : null
      }
      if (dest === null) return
      if (movedId === id) {
        // 拖的是当前流程：拖拽开始时它被收起了，落点确定后重新展开
        setExpandedKeys((current) => (current.includes(currentWfKey)
          ? current
          : [...current, currentWfKey]))
      }
      if ((moved.folder || '') === dest) {
        message.info(dest ? `已经在 ${dest} 里` : '已经在项目根目录')
        return
      }
      moveWorkflowTo(movedId, dest)
    }
  }

  /**
   * 拖到目录上时高亮那一行：目录行不是 rc-tree 的落点，没有它自带的提示线。
   * 用一个独立浮层画，不往行上加 class——树在拖拽期间会因保存/展开而重渲染，
   * 行上的 class 会被 React 覆盖掉。
   */
  const dropzoneRef = useRef<HTMLDivElement>(null)
  const [dropHover, setDropHover] = useState<
    { key: string; top: number; left: number; width: number; height: number } | null
  >(null)

  const highlightDropRow = (key: string | null) => {
    // 只有「流程拖到目录/项目根」需要自己提示；脚本拖拽和拖到流程文件上
    // 由 rc-tree 自己画落点线，别叠两个提示
    if (!key || !dragKeyRef.current?.startsWith('pwf:')
      || (!key.startsWith('pdir:') && key !== 'proj:root')) {
      setDropHover((current) => (current ? null : current))
      return
    }
    const row = document.querySelector(
      `.ant-tree-treenode:has([data-tree-key="${CSS.escape(key)}"])`)
    const box = dropzoneRef.current?.getBoundingClientRect()
    const rect = row?.getBoundingClientRect()
    if (!box || !rect) {
      setDropHover((current) => (current ? null : current))
      return
    }
    setDropHover((current) => (current?.key === key ? current : {
      key,
      top: rect.top - box.top,
      left: rect.left - box.left,
      width: rect.width,
      height: rect.height,
    }))
  }

  /** rc-tree 的 drop：先按鼠标下的行纠正落点，再交给统一处理。 */
  const onTreeDrop = (info: {
    dragNode: { key: string | number }
    node: { key: string | number }
    event?: { clientX: number; clientY: number }
  }) => {
    const dragKey = String(info.dragNode.key)
    const dropKey = (info.event && typeof info.event.clientX === 'number'
      ? rowKeyAt(info.event.clientX, info.event.clientY)
      : null) || String(info.node.key)
    highlightDropRow(null)
    applyTreeDrop(dragKey, dropKey)
  }

  /**
   * 目录行不可拖，rc-tree 就不给它挂 dragover/drop，
   * 浏览器因此不认为这里是落点。这里在树的外层兜住：
   * 会冒泡上来的，只可能是落在不可拖行（目录、项目根）上的拖拽。
   */
  const onTreeDragOverFallback = (event: DragEvent) => {
    if (!dragKeyRef.current) return
    event.preventDefault()
    highlightDropRow(rowKeyAt(event.clientX, event.clientY))
  }
  const onTreeDropFallback = (event: DragEvent) => {
    const dragKey = dragKeyRef.current
    if (!dragKey) return
    event.preventDefault()
    const dropKey = rowKeyAt(event.clientX, event.clientY)
    highlightDropRow(null)
    if (dropKey) applyTreeDrop(dragKey, dropKey)
  }

  /** 返回列表：有未保存改动时先确认，避免 SPA 跳转绕过 beforeunload。 */
  const leaveEditor = () => {
    if (!dirty) {
      nav('/dev')
      return
    }
    Modal.confirm({
      title: '有未保存的修改',
      content: '离开后，未保存的节点、脚本和连线会丢失。',
      okText: '离开',
      cancelText: '留下',
      okButtonProps: { danger: true },
      onOk: () => nav('/dev'),
    })
  }

  if (!wf) {
    if (loadError) {
      return (
        <Card>
          <ErrorState message={loadError} onRetry={() => load()} />
          <Button style={{ marginTop: 12 }} onClick={() => nav('/dev')}>返回文件列表</Button>
        </Card>
      )
    }
    return <Card loading />
  }

  return (
    <div>
      <PageHeader
        backTo="/dev"
        onBack={leaveEditor}
        title={wf.name}
        tags={(
          <>
            <Tag color={wf.env === 'PROD' ? 'red' : 'blue'}>{wf.env}</Tag>
            <Tag color={dirty ? 'orange' : 'green'}>{dirty ? '未保存' : '已保存'}</Tag>
          </>
        )}
        description={`${wf.project_code} / ${wf.code}`}
        extra={(
          <Space wrap>
            {/* 项目切换放在编辑器页头：不用退回数据开发页再选项目 */}
            <span className="project-switch">
              <Select
                size="small"
                showSearch
                optionFilterProp="label"
                style={{ width: 200 }}
                value={wf.project_id}
                placeholder="选择项目"
                title="切换到其他项目（未保存的改动会先确认）"
                options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
                onChange={(pid) => {
                  if (pid === wf.project_id) return
                  if (!canLeaveCurrentPage()) return
                  setGlobalProject(pid)
                  nav(`/dev?project=${pid}`)
                }}
              />
            </span>
            <Space size={4}>
              {latestRun ? (
                <RunStateTag state={latestRun.state} />
              ) : (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>还没有运行</Typography.Text>
              )}
              <Button
                size="small"
                icon={<HistoryOutlined />}
                title="只看这个文件的运行实例"
                onClick={() => {
                  if (!canLeaveCurrentPage()) return
                  nav(`/ops/instances?workflowId=${id}`)
                }}
              >
                运行实例
              </Button>
              {can('workflow:update') && (publishBtn())}
              {can('ops:run') && (
                <Button
                  size="small"
                  icon={<CaretRightOutlined />}
                  title="用默认环境和今天触发一次运行"
                  onClick={() => {
                    triggerForm.setFieldsValue({ env: wf.env, bizDate: dayjs() })
                    setTriggerOpen(true)
                  }}
                >
                  触发
                </Button>
              )}
              {can('ops:backfill') && (
                <Button
                  size="small"
                  icon={<HistoryOutlined />}
                  title="按日期区间批量重跑历史数据"
                  onClick={openBackfill}
                >
                  补数据
                </Button>
              )}
            </Space>
          </Space>
        )}
      />

      {staleNotice ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="服务器上的版本已更新"
          description={staleNotice}
          action={(
            <Button
              size="small"
              onClick={() => {
                if (dirty && !window.confirm('重新加载会丢弃本地未保存的改动，确定？')) return
                setStaleNotice('')
                load()
              }}
            >
              重新加载
            </Button>
          )}
          closable
          onClose={() => setStaleNotice('')}
        />
      ) : null}

      {mergeNotice ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="服务器上的改动已合并，还没有保存"
          description={mergeNotice.text}
          action={(
            <Space>
              <Button size="small" onClick={revertMerge}>撤销合并</Button>
              {saveBtn({ size: 'small', icon: false, label: '保存合并结果' })}
            </Space>
          )}
        />
      ) : null}

      {connectMode ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={connectFrom
            ? `起点：${connectFrom}，请点击目标任务完成连线`
            : '连线模式：点击第一个任务作为起点，再点击第二个任务'}
        />
      ) : null}

      <Row gutter={12}>
        <Col xs={24} lg={7}>
          <Card
            size="small"
            title={<Space><CodeOutlined />项目结构</Space>}
            styles={{
              body: {
                padding: 8,
                height: PANEL_HEIGHT,
                overflow: 'hidden',
                display: 'flex',
                flexDirection: 'column',
              },
            }}
          >
            <Typography.Text
              type="secondary"
              style={{ fontSize: 12, display: 'block', marginBottom: 6 }}
            >
              项目 → 文件夹 → 文件 → 脚本；右键新建，选中后在下方操作条；文件也能直接拖进文件夹
            </Typography.Text>
            <div
              ref={dropzoneRef}
              className="project-tree-dropzone"
              style={{ flex: 1, minHeight: 0, overflow: 'auto' }}
              // dragenter 先进来一次、dragover 每 ~350ms 才来一次：两个都接，
              // 落点高亮才会在鼠标进入目录时立刻出现
              onDragEnter={onTreeDragOverFallback}
              onDragOver={onTreeDragOverFallback}
              onDrop={onTreeDropFallback}
            >
              {dropHover && (
                <div
                  className="tree-drop-hover"
                  style={{
                    top: dropHover.top,
                    left: dropHover.left,
                    width: dropHover.width,
                    height: dropHover.height,
                  }}
                />
              )}
              <Tree
                showIcon
                blockNode
                className="project-tree"
                treeData={projectTree}
                draggable={{
                  icon: false,
                  nodeDraggable: (node) => {
                    // 脚本拖进脚本目录；流程文件拖进项目目录（或拖到别的流程上跟它同目录）
                    const key = String(node.key)
                    return key.startsWith('script:') || key.startsWith('pwf:')
                  },
                }}
                onDragStart={({ node }) => { dragKeyRef.current = String(node.key) }}
                onDragEnd={() => { dragKeyRef.current = null; highlightDropRow(null) }}
                onDrop={(info) => onTreeDrop(info as never)}
                selectedKeys={selectedCode
                  // 隐式脚本在树里就是文件本身，选中它时高亮文件节点
                  ? [implicitSingleScript ? `pwf:${id}` : `task:${selectedCode}`]
                  : scriptSelected
                    ? [`script:${scriptSelected}`]
                    : [treeSel]}
                expandedKeys={treeExpandedKeys}
                onExpand={(keys) => setExpandedKeys(keys.map(String))}
                onRightClick={({ event, node }) => {
                  event.preventDefault()
                  const key = String(node.key)
                  setTreeSel(key)
                  setCtxMenu({ x: event.clientX, y: event.clientY, key })
                }}
                onSelect={(keys, info) => {
                  // 点已选中的节点时 antd 会以空 keys 触发；用节点自身的 key 兜底，
                  // 保证「再点一次文件」仍然选中它唯一的脚本，而不是什么都不发生
                  const key = keys[0] === undefined
                    ? (info.node?.key === undefined ? undefined : String(info.node.key))
                    : String(keys[0])
                  if (!key) return
                  if (key === 'proj:root') {
                    // 当前项目：选中后下方出现「新建目录 / 新建流程」
                    setTreeSel(key)
                    setSelectedCode(undefined)
                    setScriptSelected(undefined)
                    return
                  }
                  if (key.startsWith('pdir:')) {
                    setTreeSel(key)
                    setExpandedKeys((current) => (current.includes(key) ? current : [...current, key]))
                    return
                  }
                  if (key.startsWith('task:')) {
                    selectTask(key.slice(5))
                    return
                  }
                  if (key.startsWith('script:')) {
                    selectScriptNode(key.slice(7), Boolean(info.node.isLeaf))
                    return
                  }
                  if (key.startsWith('pwf:')) {
                    const nextId = Number(key.slice(4))
                    if (!nextId || nextId === id) {
                      // 点当前文件：文件就是脚本，单选文件直接落到它唯一的脚本上
                      setTreeSel(key)
                      setScriptSelected(undefined)
                      if (model.tasks.length === 1) {
                        selectTask(model.tasks[0].code)
                      } else {
                        setSelectedCode(undefined)
                      }
                      return
                    }
                    // 切到兄弟流程：有未保存改动先确认，取消就留在原地
                    if (!canLeaveCurrentPage()) return
                    nav(`/dev/${nextId}`)
                  }
                }}
              />
              {ctxMenu && (
                <div
                  className="tree-ctx"
                  style={{ left: ctxMenu.x, top: ctxMenu.y }}
                  onClick={(event) => event.stopPropagation()}
                >
                  {ctxMenuItems(ctxMenu.key).map((item) => (
                    <button
                      key={item.label}
                      type="button"
                      className={item.danger ? 'danger' : ''}
                      onClick={() => {
                        setCtxMenu(null)
                        item.onClick()
                      }}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </Card>
        </Col>
        {/* 左 7 + 中 10/16 + 右 7/1 = 24：收起时中间必须补到 16，
            否则 7+17+1=25 会让窄条换行到底部，看起来像「收起来就展不开」 */}
        <Col xs={24} lg={configOpen ? 10 : 16}>
          <Card
            size="small"
            tabList={[
              { key: 'graph', tab: '血缘图' },
              { key: 'code', tab: '编码' },
            ]}
            activeTabKey={viewMode}
            onTabChange={(key) => setViewMode(key as 'graph' | 'code')}
            extra={viewMode === 'graph' ? (
              <Space>
                {/* 视图切换只用图标，不放文字标签；hover 有说明 */}
                <Button
                  size="small"
                  icon={<ApartmentOutlined />}
                  type={graphMode === 'table' ? 'primary' : 'default'}
                  title={graphMode === 'table' ? '回到编排图' : '按 SQL 查看表血缘'}
                  onClick={() => setGraphMode(graphMode === 'table' ? 'flow' : 'table')}
                />
                {graphMode === 'flow' && (canUndo || canRedo) ? (undoRedoGroup()) : null}
                {graphMode === 'flow' && selectedEdge ? (
                  <Button
                    size="small"
                    danger
                    icon={<DeleteOutlined />}
                    title={`删除 ${selectedEdge.from} → ${selectedEdge.to}`}
                    onClick={removeSelectedEdge}
                  >
                    删除连线
                  </Button>
                ) : null}
                {graphMode === 'flow' && can('workflow:update') && (
                  <Button
                    size="small"
                    type={connectMode ? 'primary' : 'default'}
                    icon={<LinkOutlined />}
                    onClick={() => {
                      setConnectMode((v) => !v)
                      setConnectFrom(undefined)
                    }}
                  >
                    {connectMode ? '退出连线' : '连线模式'}
                  </Button>
                )}
                {can('workflow:update') && (newScriptBtn())}
                <Button
                  size="small"
                  icon={<CodeOutlined />}
                  title="查看生成的 DAG（Python）"
                  onClick={() => preview().catch((e) => message.error(errMsg(e)))}
                />
                {dirty && can('workflow:update') && !hasContextSave && (
                  saveBtn({ size: 'small', title: '保存整个工作流（Ctrl+S）' })
                )}
              </Space>
            ) : (
              <Space>
                <Typography.Text
                  type="secondary"
                  title={selected
                    ? `节点脚本 · ${selected.code}`
                    : selectedScript ? selectedScript.path : '未选择节点或脚本'}
                  style={{
                    fontSize: 12, display: 'inline-block', maxWidth: 220, verticalAlign: 'bottom',
                    overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                  }}
                >
                  {selected
                    ? `节点脚本 · ${selected.code}`
                    : selectedScript ? selectedScript.path : '未选择节点或脚本'}
                </Typography.Text>
                {/* 编码页也能直接加脚本，不用先切回血缘图 */}
                {can('workflow:update') && (newScriptBtn())}
                {canUndo || canRedo ? (undoRedoGroup()) : null}
                {can('workflow:update') && (
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    Ctrl+S 保存
                  </Typography.Text>
                )}
                {can('workflow:update') && selected?.type === 'SQL' && (runBtn({ size: 'small' }))}
                {can('workflow:update') && dirty && !hasContextSave && (
                  saveBtn({ size: 'small', title: '保存整个工作流（Ctrl+S）' })
                )}
              </Space>
            )}
            styles={{
              body: {
                padding: viewMode === 'graph' ? 0 : 12,
                height: PANEL_HEIGHT,
                overflow: viewMode === 'graph' ? 'hidden' : 'auto',
              },
            }}
          >
            {viewMode === 'graph' ? (
              graphMode === 'table' ? (
                tableLineageGraph ? (
                  <div style={{ height: '100%', minHeight: 520 }}>
                    <ReactFlow
                      nodes={tableLineageGraph.nodes}
                      edges={tableLineageGraph.edges}
                      nodesDraggable={false}
                      nodesConnectable={false}
                      edgesFocusable={false}
                      fitView
                      fitViewOptions={{ padding: 0.25, maxZoom: 1.1 }}
                      minZoom={0.2}
                    >
                      <Background />
                      <Controls showInteractive={false} />
                    </ReactFlow>
                  </div>
                ) : (
                  <div style={{ padding: 24 }}>
                    <Empty
                      image={Empty.PRESENTED_IMAGE_SIMPLE}
                      description={(
                        <span style={{ fontSize: 12 }}>
                          {lineageLoading
                            ? '正在解析 SQL…'
                            : '当前文件的 SQL 里没有解析到表：写成 INSERT INTO 目标表 ... SELECT ... FROM 源表 就能看到血缘'}
                        </span>
                      )}
                    />
                  </div>
                )
              ) : fileLineageGraph ? (
                <div style={{ height: '100%', minHeight: 520 }}>
                  <ReactFlow
                    nodes={fileLineageGraph.nodes}
                    edges={fileLineageGraph.edges}
                    nodesDraggable={false}
                    nodesConnectable={false}
                    edgesFocusable={false}
                    fitView
                    fitViewOptions={{ padding: 0.2, maxZoom: 1 }}
                    onNodeClick={(_, node) => {
                      const target = Number(node.data?.fileId)
                      if (!target || target === id) return
                      if (!canLeaveCurrentPage()) return
                      nav(`/dev/${target}`)
                    }}
                  >
                    <Background />
                    <Controls showInteractive={false} />
                  </ReactFlow>
                </div>
              ) : (
              <div style={{ height: '100%', minHeight: 520, position: 'relative' }}>
                {/* 血缘只在有依赖时才有意义：没有连线时给一句提示，不假装画了一张图 */}
                {model.edges.length === 0 && model.tasks.length > 0 && (
                  <div style={{
                    position: 'absolute', top: 8, left: 8, right: 8, zIndex: 5,
                    pointerEvents: 'none', padding: '6px 10px', borderRadius: 6,
                    border: '1px dashed var(--dw-border, #d9d9d9)',
                    background: 'rgba(255, 255, 255, 0.92)',
                    color: 'var(--dw-text-3, #888)', fontSize: 12,
                  }}>
                    {implicitSingleScript
                      ? '还没有依赖关系：右侧「上游依赖」里选其他文件，这里就会显示血缘图'
                      : '还没有依赖关系：新建脚本时选上游 / 下游，这里就会显示血缘图'}
                  </div>
                )}
                <ReactFlow
                  nodes={displayNodes}
                  edges={edges}
                  nodeTypes={nodeTypes}
                  nodesDraggable={can('workflow:update')}
                  nodesConnectable={can('workflow:update')}
                  edgesFocusable={can('workflow:update')}
                  onInit={setFlow}
                  onNodesChange={onNodesChange}
                  onEdgesChange={onEdgesChange}
                  onConnect={onConnect}
                  onNodeClick={(_, node) => onNodeClick(node)}
                  onEdgeClick={(_, edge) => {
                    if (!can('workflow:update')) return
                    setSelectedEdge({ from: edge.source, to: edge.target })
                  }}
                  onPaneClick={() => setSelectedEdge(undefined)}
                  onNodeDragStart={(_, node) => {
                    dragStart.current[node.id] = { ...node.position }
                  }}
                  onNodeDragStop={(_, node) => {
                    const start = dragStart.current[node.id]
                    // 单击节点也会触发 dragStop；位置没变就不算修改
                    if (start && start.x === node.position.x && start.y === node.position.y) return
                    setDirty(true)
                    setModel((current) => ({
                      ...current,
                      tasks: current.tasks.map((task) => task.code === node.id
                        ? { ...task, x: node.position.x, y: node.position.y } : task),
                    }))
                  }}
                  // ReactFlow 默认 minZoom=0.5，20+ 节点的流程会放不下，两侧被裁
                  minZoom={0.15}
                  fitView
                  fitViewOptions={{ padding: 0.25, maxZoom: 1.1, minZoom: 0.15 }}
                  deleteKeyCode={null}
                >
                  <Background />
                  <Controls />
                </ReactFlow>
              </div>
              )
            ) : selected ? (
              <CodeEditor
                label="节点脚本"
                language={selected.type}
                value={selected.script || ''}
                onChange={(value) => patchTask(selected.code, { script: value })}
                readOnly={!can('workflow:update')}
                placeholder="SQL / Shell / Python，可用 ${biz_date}"
                onSave={() => {
                  if (!can('workflow:update') || saving) return
                  save().catch((e) => message.error(errMsg(e)))
                }}
              />
            ) : selectedScript ? (
              <CodeEditor
                label="脚本内容"
                language={selectedScript.language}
                value={selectedScript.content || ''}
                onChange={(value) => patchScript(selectedScript.path, { content: value })}
                readOnly={!can('workflow:update')}
                onSave={() => {
                  if (!can('workflow:update') || saving) return
                  save().catch((e) => message.error(errMsg(e)))
                }}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={(
                  <div>
                    在左侧选择脚本，或切到「血缘图」
                    <div style={{ marginTop: 4, fontSize: 12, color: 'var(--dw-text-3)' }}>
                      脚本的重命名、删除与所在目录在右侧「配置」面板里；面板收起时点竖排按钮可展开
                    </div>
                  </div>
                )}
              />
            )}
          </Card>
        </Col>
        {configOpen ? (
        <Col xs={24} lg={7}>
          <Card
            size="small"
            title="配置"
            extra={(
              <Button
                size="small"
                type="text"
                icon={<LeftOutlined />}
                title="收起配置"
                onClick={() => setConfigOpen(false)}
              />
            )}
            styles={{ body: { padding: 12, height: PANEL_HEIGHT, overflow: 'auto' } }}
          >
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>文件名称</Typography.Text>
              <Input
                value={wf.name}
                disabled={!can('workflow:update')}
                onChange={(e) => {
                  const value = e.target.value
                  // 隐式脚本的脚本名跟着文件名走，改名后不会突然多出一个「SQL」子节点
                  const syncScriptName = implicitSingleScript
                  setWf({ ...wf, name: value })
                  setDirty(true)
                  if (syncScriptName) {
                    patchTask(model.tasks[0].code, { name: value })
                  }
                }}
                placeholder="文件名称"
              />
              <Space wrap size={4}>
                {can('workflow:update') && (
                  <Button
                    size="small"
                    icon={<HistoryOutlined />}
                    title="生成版本快照（不发布）"
                    loading={versionBusy === 'create-version'}
                    onClick={() => runVersionAction('create-version', async () => {
                      await createVersion()
                    })}
                  >
                    存版本
                  </Button>
                )}
                {can('workflow:update') && (publishBtn({ title: '先保存当前编辑，再生成版本并选择环境', catchErr: false }))}
                {can('workflow:update') && (
                  <Button
                    size="small"
                    icon={<ScheduleOutlined />}
                    onClick={() => {
                      const existing = wf.schedule?.find((s) => s.env === wf.env)
                      const type = existing?.schedule_type && existing.schedule_type !== 'CRON'
                        ? existing.schedule_type : 'DAILY'
                      const startAt = existing?.start_at
                        // start_at 带 +08:00：固定按北京时间显示，不跟随浏览器时区
                        ? dayjs(existing.start_at).tz(SCHEDULE_TZ)
                        : existing?.start_date
                          ? dayjs(`${existing.start_date} ${existing.run_time || '00:00'}`)
                          : dayjs().minute(0).second(0)
                      scheduleForm.setFieldsValue({
                        scheduleType: type,
                        startAt,
                        weekdays: existing?.weekdays
                          ? existing.weekdays.split(',').map((v) => Number(v))
                          : [1, 2, 3, 4, 5],
                        monthDays: existing?.month_days
                          ? existing.month_days.split(',').map((v) => Number(v))
                          : [1],
                        endDate: existing?.end_date ? dayjs(existing.end_date) : undefined,
                        status: existing?.status || 'ONLINE',
                      })
                      setScheduleOpen(true)
                    }}
                  >
                    定时
                  </Button>
                )}
                {can('ops:run') && (
                  <Button
                    size="small"
                    icon={<CaretRightOutlined />}
                    onClick={() => {
                      triggerForm.setFieldsValue({ env: wf.env, bizDate: dayjs() })
                      setTriggerOpen(true)
                    }}
                  >
                    触发
                  </Button>
                )}
              </Space>
              <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                <Tag
                  color={devDeployment ? 'blue' : 'default'}
                  title={devDeployment
                    ? `最近发布：${dayjs(devDeployment.deployed_at).format('YYYY-MM-DD HH:mm')}`
                    : '尚未发布到 DEV'}
                >
                  {devDeployment ? `DEV v${devDeployment.version_no}` : 'DEV 未发布'}
                </Tag>
                {prodPendingVersion ? (
                  <Tag color="orange" title="生产发布等待管理员审批">
                    PROD 审批中 v{prodPendingVersion.version_no}
                  </Tag>
                ) : (
                  <Tag
                    color={prodDeployment ? 'green' : 'default'}
                    title={prodDeployment
                      ? `最近发布：${dayjs(prodDeployment.deployed_at).format('YYYY-MM-DD HH:mm')}`
                      : '尚未发布到 PROD'}
                  >
                    {prodDeployment ? `PROD v${prodDeployment.version_no}` : 'PROD 未发布'}
                  </Tag>
                )}
              </div>
            </Space>
            {/* REQ-DW-11：任务基线在任务配置里设置；切换任务时按 id 重挂载重载基线 */}
            <BaselineConfig key={`baseline-${wf.id}`} workflow={wf} />
            {currentProject?.dev_database ? (
              <div className="project-db-bar">
                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                  <Space size={6} wrap>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>Doris 库</Typography.Text>
                    <Tag color="blue" title={`DEV 库：${currentProject.dev_database}`}>
                      {currentProject.dev_database}
                    </Tag>
                    <Tag color="green" title={`PROD 库：${currentProject.prod_database}`}>
                      {currentProject.prod_database}
                    </Tag>
                  </Space>
                  <Space size={6} wrap>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>表名前缀</Typography.Text>
                    <Typography.Text code>{currentProject.table_prefix || '-'}</Typography.Text>
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      新建表默认带此前缀
                    </Typography.Text>
                  </Space>
                </Space>
              </div>
            ) : null}
            <Divider style={{ margin: '12px 0' }} />
            {selected ? (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text strong>节点配置</Typography.Text>
                  <Typography.Text code>{selected.code}</Typography.Text>
                </Space>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>名称</Typography.Text>
                  <Input
                    value={selected.name}
                    placeholder="节点名称"
                    disabled={!can('workflow:update')}
                    onChange={(e) => {
                      const value = e.target.value
                      patchTask(selected.code, { name: value })
                      // 隐式脚本：脚本名就是文件名，改一处另一处跟着变，避免多出子节点
                      if (implicitSingleScript) {
                        setWf((current) => (current ? { ...current, name: value } : current))
                      }
                    }}
                  />
                </div>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>类型</Typography.Text>
                  <Select
                    style={{ width: '100%' }}
                    value={selected.type}
                    disabled={!can('workflow:update')}
                    onChange={(value) => patchTask(selected.code, {
                      type: value,
                      // 改成非 SQL 后引擎不再适用；SQL 节点保留原引擎（创建后只读）
                      engine: value === 'SQL' ? engineOf(selected) : undefined,
                    })}
                    options={TASK_TYPES.map((type) => ({ value: type }))}
                  />
                </div>
                {selected.type === 'SQL' && (
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      执行引擎（创建后只读）
                    </Typography.Text>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <Tag color={isUnknownEngine(selected) ? 'red' : 'blue'}>
                        {isUnknownEngine(selected)
                          ? `${rawEngineOf(selected)}（不支持的引擎）`
                          : engineLabel(engineOf(selected))}
                      </Tag>
                      {!hasExplicitEngine(selected) && (
                        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                          老任务未设置引擎，按 DORIS 执行
                        </Typography.Text>
                      )}
                    </div>
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      引擎在脚本创建时确定，编辑时不可切换（避免运行中换引擎）；需要别的引擎请新建脚本。
                    </Typography.Text>
                  </div>
                )}
                {selected.type === 'SQL' && engineOf(selected) === 'FLINK' && (
                  <Alert
                    type={selected.datasourceId ? 'warning' : 'info'}
                    showIcon
                    message="FLINK 引擎不支持绑定数据源"
                    description={selected.datasourceId
                      ? `该节点仍绑定着数据源（#${selected.datasourceId}），试跑会被拒绝；请先解除绑定。`
                      : "Flink SQL 走 SQL Gateway，连接器在 CREATE TABLE ... WITH ('connector'=...) 里声明。"}
                    action={selected.datasourceId ? (
                      <Button
                        size="small"
                        onClick={() => patchTask(selected.code, { datasourceId: undefined })}
                      >
                        解除绑定
                      </Button>
                    ) : undefined}
                  />
                )}
                {selected.type === 'SQL' && engineOf(selected) !== 'FLINK' && (
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      绑定数据源（可选）
                    </Typography.Text>
                    <Select
                      allowClear
                      style={{ width: '100%' }}
                      placeholder={engineOf(selected) === 'SPARK'
                        ? '只列出 SPARK 类型数据源'
                        : '只列出可执行 SQL 的数据源'}
                      value={selected.datasourceId}
                      disabled={!can('workflow:update')}
                      onChange={(value) => patchTask(selected.code, {
                        datasourceId: value,
                      })}
                      options={bindableDatasources(engineOf(selected), datasources)
                        .map((item) => ({
                          value: item.id,
                          label: `${item.name} · ${item.ds_type}`,
                        }))}
                      notFoundContent={engineOf(selected) === 'SPARK'
                        ? '项目里还没有 SPARK 类型数据源；不绑定会走平台常驻 Spark Thrift'
                        : '项目里还没有可执行 SQL 的数据源'}
                    />
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {engineOf(selected) === 'SPARK'
                        ? '不绑定则走平台常驻 Spark Thrift Server（结果同步返回）。'
                        : '不绑定则跟随项目数仓；绑定后试跑直连该数据源执行只读 SQL。'}
                    </Typography.Text>
                    {selected.datasourceId && (
                      <Alert
                        style={{ marginTop: 6 }}
                        type="info"
                        showIcon
                        message={`已绑定数据源 #${selected.datasourceId}${boundDatasource
                          ? ` · ${boundDatasource.name}` : ''}`}
                        description="试跑会直接连接这个数据源执行只读 SQL。"
                        action={(
                          <Button
                            size="small"
                            onClick={() => patchTask(selected.code, { datasourceId: undefined })}
                          >
                            解除绑定
                          </Button>
                        )}
                      />
                    )}
                  </div>
                )}
                {selected.type === 'QUALITY_GATE' && (
                  <div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>质量规则编码</Typography.Text>
                      <Typography.Link
                        style={{ fontSize: 12 }}
                        onClick={() => { if (canLeaveCurrentPage()) nav('/quality') }}
                      >
                        管理质量规则
                      </Typography.Link>
                    </div>
                    <AutoComplete
                      value={selected.ruleCode || ''}
                      disabled={!can('workflow:update')}
                      onChange={(value) => patchTask(selected.code, { ruleCode: value })}
                      options={qualityRules.map((rule) => ({
                        value: rule.code,
                        label: `${rule.code} · ${rule.name}`,
                      }))}
                      filterOption={(input, option) => String(option?.label ?? '')
                        .toLowerCase().includes(input.toLowerCase())}
                      placeholder="选已有规则，或直接输入编码（必填）"
                      style={{ width: '100%' }}
                    />
                  </div>
                )}
                {selected.type === 'SQL' && (
                  <div>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      绑定库（可选，填了 SQL 里可以只写表名）
                    </Typography.Text>
                    <Input
                      placeholder="例如 demo_retail"
                      value={selected.database ?? ''}
                      disabled={!can('workflow:update')}
                      onChange={(event) => patchTask(selected.code, {
                        database: event.target.value.trim() || undefined,
                      })}
                    />
                  </div>
                )}
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>重试次数</Typography.Text>
                  <InputNumber
                    min={0}
                    max={5}
                    style={{ width: '100%' }}
                    value={selected.retries ?? 0}
                    disabled={!can('workflow:update')}
                    onChange={(value) => patchTask(selected.code, { retries: Number(value) || 0 })}
                  />
                </div>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {implicitSingleScript ? '上游依赖（有权限的库表）' : '上游依赖（可多选）'}
                  </Typography.Text>
                  {implicitSingleScript ? (
                    <Space direction="vertical" size={6} style={{ width: '100%' }}>
                      <Space wrap size={4}>
                        {upstreamTables.length === 0 ? (
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            未选择依赖表
                          </Typography.Text>
                        ) : upstreamTables.map((table) => {
                          const key = `${table.catalog}.${table.schemaName}.${table.tableName}`
                          return (
                            <Tag
                              key={key}
                              closable={can('workflow:update')}
                              onClose={(event) => {
                                event.preventDefault()
                                syncModel({
                                  ...model,
                                  upstreamTables: upstreamTables.filter(
                                    (item) => `${item.catalog}.${item.schemaName}.${item.tableName}` !== key),
                                })
                                setDirty(true)
                              }}
                            >
                              {key}
                            </Tag>
                          )
                        })}
                      </Space>
                      <Button
                        size="small"
                        icon={<PlusOutlined />}
                        disabled={!can('workflow:update')}
                        onClick={() => { openDependencyPicker().catch(() => undefined) }}
                      >
                        选择依赖表
                      </Button>
                    </Space>
                  ) : (
                    <Select
                      mode="multiple"
                      style={{ width: '100%' }}
                      value={upstreamOf(selected.code)}
                      disabled={!can('workflow:update')}
                      onChange={(values) => setDependencies(selected.code, values)}
                      placeholder="无依赖"
                      options={model.tasks
                        .filter((task) => task.code !== selected.code)
                        .map((task) => ({ value: task.code, label: `${task.name} · ${task.code}` }))}
                    />
                  )}
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {implicitSingleScript
                      ? '只列你有读取 / 写入权限的库表；没有权限先去「权限申请」，保存后在「血缘图」预览。'
                      : '上游全部成功后才触发本节点；可以多选，清空即删除连线。'}
                  </Typography.Text>
                </div>
                <div style={{
                  position: 'sticky', bottom: 0, background: '#fff',
                  margin: '0 -12px -12px', padding: '8px 12px 12px',
                  borderTop: '1px solid #f0f0f0',
                }}>
                  <Space wrap>
                    {can('workflow:update') && selected.type === 'SQL' && !selected.datasourceId && (runBtn())}
                    {can('workflow:update') && (
                      saveBtn({ label: '保存节点改动', title: '保存当前节点的改动' })
                    )}
                    {can('workflow:update') && (
                      <Popconfirm
                        title={`删除节点「${selected.code}」？`}
                        description="与它相关的连线也会一起删除；点「保存」后才写回服务端。"
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        onConfirm={removeTask}
                      >
                        <Button danger>删除节点</Button>
                      </Popconfirm>
                    )}
                  </Space>
                </div>
              </Space>
            ) : selectedScript ? (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Typography.Text strong>脚本配置</Typography.Text>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>路径</Typography.Text>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      aria-label="脚本路径"
                      value={pathDraft}
                      disabled={!can('workflow:update')}
                      onChange={(e) => setPathDraft(e.target.value)}
                      onPressEnter={() => renameScriptNode(true).catch(() => undefined)}
                    />
                    <Button
                      loading={renaming}
                      disabled={!can('workflow:update')}
                      onClick={() => renameScriptNode(true).catch(() => undefined)}
                    >
                      重命名
                    </Button>
                  </Space.Compact>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    改路径即重命名或移动到其他目录。
                  </Typography.Text>
                </div>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>语言</Typography.Text>
                  <Select
                    style={{ width: '100%' }}
                    value={selectedScript.language || 'SQL'}
                    options={SCRIPT_LANGUAGES}
                    disabled={!can('workflow:update')}
                    onChange={(value) => patchScript(selectedScript.path, { language: value })}
                  />
                </div>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  脚本文件随文件保存；目前不参与调度运行。
                </Typography.Text>
                <div style={{
                  position: 'sticky', bottom: 0, background: '#fff',
                  margin: '0 -12px -12px', padding: '8px 12px 12px',
                  borderTop: '1px solid #f0f0f0',
                }}>
                  <Space>
                    {can('workflow:update') && (
                      saveBtn({ label: '保存脚本信息', title: '保存当前脚本的改名/语言等改动' })
                    )}
                    {can('workflow:update') && (
                      <Popconfirm
                        title={`删除脚本「${selectedScript.path}」？`}
                        description="删除后不可恢复。"
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => removeScriptNode(selectedScript.path, true)}
                      >
                        <Button danger title="删除脚本">删除</Button>
                      </Popconfirm>
                    )}
                  </Space>
                </div>
              </Space>
            ) : selectedScriptFolder ? (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                <Typography.Text strong>目录</Typography.Text>
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>路径</Typography.Text>
                  <Space.Compact style={{ width: '100%' }}>
                    <Input
                      aria-label="目录路径"
                      value={pathDraft}
                      disabled={!can('workflow:update')}
                      onChange={(e) => setPathDraft(e.target.value)}
                      onPressEnter={() => renameScriptNode(false).catch(() => undefined)}
                    />
                    <Button
                      loading={renaming}
                      disabled={!can('workflow:update')}
                      onClick={() => renameScriptNode(false).catch(() => undefined)}
                    >
                      重命名
                    </Button>
                  </Space.Compact>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    改路径即重命名或移动到其他目录；子目录和脚本一起移动。
                  </Typography.Text>
                </div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  删除目录会连同子目录和脚本一起删除。
                </Typography.Text>
                {can('workflow:update') && (
                  <div style={{
                    position: 'sticky', bottom: 0, background: '#fff',
                    margin: '0 -12px -12px', padding: '8px 12px 12px',
                    borderTop: '1px solid #f0f0f0',
                  }}>
                    <Space>
                      {saveBtn({ label: '保存目录改动', title: '保存目录改动' })}
                      <Popconfirm
                        title={`删除脚本目录「${selectedScriptFolder}」？`}
                        description="目录下的脚本和子目录会一起删除；点「保存」后生效。"
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => removeScriptNode(selectedScriptFolder, false)}
                      >
                        <Button danger>删除目录</Button>
                      </Popconfirm>
                    </Space>
                  </div>
                )}
              </Space>
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={<span style={{ fontSize: 12 }}>从左侧选择节点、脚本或目录</span>}
              />
            )}
          </Card>
        </Col>
        ) : (
        <Col xs={24} lg={1} style={{ minWidth: 48 }}>
          <Card size="small" styles={{ body: { padding: 4, height: PANEL_HEIGHT } }}>
            <Button
              type="text"
              icon={<SettingOutlined />}
              title="展开配置"
              onClick={() => setConfigOpen(true)}
              style={{ height: '100%', width: '100%' }}
            >
              <span style={{ writingMode: 'vertical-rl', letterSpacing: 2 }}>展开配置</span>
            </Button>
          </Card>
        </Col>
        )}
      </Row>

      <Modal
        title="选择依赖表"
        open={depOpen}
        width={940}
        onOk={confirmDependencies}
        onCancel={() => setDepOpen(false)}
        okText={`确定（${depSelected.length}）`}
        destroyOnClose
      >
        <div className="dep-hint">
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            只显示你有读取 / 写入权限（或是负责人）的库表；没有权限请先
          </Typography.Text>
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            onClick={() => { if (canLeaveCurrentPage()) nav('/access') }}
          >
            去申请权限
          </Button>
        </div>
        <div className="dep-picker">
          {/* 第一列：库 */}
          <section className="dep-col">
            <header className="dep-col-head">
              <strong>库</strong>
              <span className="dep-count">{depSchemaOptions.length}</span>
            </header>
            <div className="dep-col-search">
              <ImeInput
                allowClear
                size="small"
                prefix={<SearchOutlined />}
                placeholder="搜索库"
                value={depSchemaSearch}
                onChange={setDepSchemaSearch}
              />
            </div>
            <div className="dep-col-body">
              {depSchemaOptions.length === 0 && !depLoading ? (
                <div className="dep-empty">还没有有权限的库<br />先去「权限申请」申请</div>
              ) : depSchemaOptions.map((db) => (
                <div
                  key={db.key}
                  role="button"
                  className={`dep-row ${db.key === depSchema ? 'active' : ''}`}
                  onClick={() => setDepSchema(db.key)}
                >
                  <div className="dep-row-main">
                    <div className="dep-row-title">{db.catalog}.{db.schemaName}</div>
                    <div className="dep-row-meta">
                      <Tag>{db.tenant}</Tag>
                      {db.count} 张表
                    </div>
                  </div>
                  <span className="dep-row-arrow">›</span>
                </div>
              ))}
            </div>
          </section>

          {/* 第二列：表 */}
          <section className="dep-col">
            <header className="dep-col-head">
              <strong>表</strong>
              <span className="dep-count">{depVisibleTables.length}</span>
            </header>
            <div className="dep-col-search">
              <ImeInput
                allowClear
                size="small"
                prefix={<SearchOutlined />}
                placeholder="搜索表名"
                value={depSearch}
                onChange={setDepSearch}
              />
            </div>
            {depVisibleTables.length > 0 ? (
              <div className="dep-select-all">
                <Checkbox
                  checked={depAllVisibleSelected}
                  indeterminate={depSomeVisibleSelected}
                  onChange={(e) => selectAllVisibleTables(e.target.checked)}
                >
                  全选当前结果
                </Checkbox>
              </div>
            ) : null}
            <div className="dep-col-body">
              {depVisibleTables.length === 0 ? (
                <div className="dep-empty">
                  {depSchema ? '这个库里没有匹配的表' : '先在左边选一个库'}
                </div>
              ) : depVisibleTables.map((table) => {
                const key = tableRefKey(table)
                const checked = depSelected.some((item) => tableRefKey(item) === key)
                return (
                  <label key={key} className={`dep-check-row ${checked ? 'checked' : ''}`}>
                    <Checkbox
                      checked={checked}
                      onChange={(e) => toggleDepTable(table, e.target.checked)}
                    />
                    <span className="dep-check-name" title={table.tableName}>{table.tableName}</span>
                    <Tag color={table.level === 'WRITE' || table.owner ? 'green' : 'blue'}>
                      {table.level === 'WRITE' || table.owner ? '可写' : '只读'}
                    </Tag>
                  </label>
                )
              })}
            </div>
          </section>

          {/* 第三列：已选择 */}
          <section className="dep-col">
            <header className="dep-col-head">
              <strong>已选择</strong>
              <Space size={2}>
                <span className="dep-count">{depSelected.length}</span>
                {depSelected.length > 0 ? (
                  <Button type="link" size="small" onClick={() => setDepSelected([])}>
                    清空
                  </Button>
                ) : null}
              </Space>
            </header>
            <div className="dep-col-search">
              <ImeInput
                allowClear
                size="small"
                prefix={<SearchOutlined />}
                placeholder="搜索已选表"
                value={depSelectedSearch}
                onChange={setDepSelectedSearch}
              />
            </div>
            <div className="dep-col-body">
              {depSelected.length === 0 ? (
                <div className="dep-empty">还没有选择表<br />在中间勾选后出现在这里</div>
              ) : depSelectedVisibleTables.length === 0 ? (
                <div className="dep-empty">没有匹配的已选表</div>
              ) : depSelectedVisibleTables.map((table) => {
                const key = tableRefKey(table)
                return (
                  <div key={key} className="dep-selected-row">
                    <span className="dep-selected-name" title={key}>{key}</span>
                    <Button
                      type="text"
                      size="small"
                      danger
                      icon={<DeleteOutlined />}
                      title="移除"
                      onClick={() => removeDepTable(key)}
                    />
                  </div>
                )
              })}
            </div>
          </section>
        </div>
      </Modal>

      <Modal
        title="新建脚本"
        open={newScriptOpen}
        onOk={() => createScriptTask()}
        onCancel={() => setNewScriptOpen(false)}
        okText="创建"
        forceRender
      >
        <Form form={newScriptForm} layout="vertical" preserve={false}>
          <Form.Item
            name="name"
            label="脚本名称"
            rules={[{ required: true, message: '请输入脚本名称' }]}
          >
            <Input placeholder="例如：清洗订单明细" maxLength={64} />
          </Form.Item>
          <Form.Item name="type" label="类型" initialValue="SQL">
            <Select options={TASK_TYPES.map((type) => ({ value: type }))} />
          </Form.Item>
          {(newScriptType || 'SQL') === 'SQL' && (
            <Form.Item
              name="engine"
              label="执行引擎"
              initialValue="DORIS"
              rules={[{ required: true, message: '请选择执行引擎（DORIS / SPARK / FLINK）' }]}
              extra="创建后引擎不可修改，需更换请新建脚本。Doris 跟随项目数仓；Spark 走平台 Spark Thrift；Flink 为流式作业、异步提交"
            >
              <Select options={SCRIPT_ENGINES} />
            </Form.Item>
          )}
          <Form.Item
            name="upstream"
            label="上游脚本"
            extra="上游全部成功后才触发本脚本；可以不选，稍后在画布上连线也行"
          >
            <Select
              mode="multiple"
              allowClear
              placeholder="不选就是入口脚本"
              options={model.tasks.map((task) => ({
                value: task.code,
                label: `${task.name}（${task.type}）`,
              }))}
            />
          </Form.Item>
          <Form.Item name="downstream" label="下游脚本" extra="本脚本成功后触发这些脚本">
            <Select
              mode="multiple"
              allowClear
              placeholder="需要时再选"
              options={model.tasks.map((task) => ({
                value: task.code,
                label: `${task.name}（${task.type}）`,
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建脚本文件"
        open={newFileOpen}
        onOk={() => createScriptFile()}
        onCancel={() => setNewFileOpen(false)}
        okText="创建"
        forceRender
      >
        <Form form={newFileForm} layout="vertical" preserve={false}>
          <Form.Item
            name="path"
            label="脚本路径"
            rules={[{ required: true, message: '请输入脚本路径' }]}
            extra="支持多级目录，例如 etl/ods/load_orders.sql"
          >
            <Input placeholder="scripts/demo.sql" />
          </Form.Item>
          <Form.Item name="language" label="语言" initialValue="SQL">
            <Select options={SCRIPT_LANGUAGES} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建脚本目录"
        open={newFolderOpen}
        onOk={() => createScriptFolder()}
        onCancel={() => setNewFolderOpen(false)}
        okText="创建"
        forceRender
      >
        <Form form={newFolderForm} layout="vertical" preserve={false}>
          <Form.Item
            name="path"
            label="目录路径"
            rules={[{ required: true, message: '请输入目录路径' }]}
            extra="支持多级目录，例如 etl/ods；建完可以直接把脚本或子目录拖进去"
          >
            <Input placeholder="scripts/etl" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建文件夹"
        open={projectFolderOpen}
        onOk={() => createProjectFolder()}
        onCancel={() => setProjectFolderOpen(false)}
        okText="创建"
        forceRender
      >
        <Input
          value={projectFolderValue}
          onChange={(e) => setProjectFolderValue(e.target.value)}
          placeholder="营销/日报"
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          输入相对项目根的路径，支持多级；建完可以把文件拖进去。
        </Typography.Text>
      </Modal>

      <Modal
        title={`新建文件 · ${projectName || wf.project_code}`}
        open={newWorkflowOpen}
        onOk={() => createWorkflowHere().catch(() => undefined)}
        onCancel={() => setNewWorkflowOpen(false)}
        okText="创建并打开"
        forceRender
      >
        <Form form={newWorkflowForm} layout="vertical" preserve={false}>
          <Form.Item
            label="编码"
            name="code"
            rules={[{ required: true, pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/, message: '字母开头，只含字母数字下划线' }]}
          >
            <Input placeholder="例如 order_etl" />
          </Form.Item>
          <Form.Item label="名称" name="name" rules={[{ required: true }]}>
            <Input placeholder="例如 订单日汇总" />
          </Form.Item>
          <Form.Item
            label="目录"
            name="folder"
            extra="默认放在当前文件夹；填新路径即自动生成目录，如 营销/日报"
          >
            <Input placeholder="营销/日报" />
          </Form.Item>
          <Form.Item
            label="类型"
            name="type"
            initialValue="SQL"
            extra="建好后文件里就有一个这个类型的脚本，打开即可编辑"
          >
            <Select options={TASK_TYPES.filter((type) => type !== 'QUALITY_GATE')
              .map((type) => ({ value: type }))} />
          </Form.Item>
          {(newWorkflowType || 'SQL') === 'SQL' && (
            <Form.Item
              label="执行引擎"
              name="engine"
              initialValue="DORIS"
              rules={[{ required: true, message: '请选择执行引擎（DORIS / SPARK / FLINK）' }]}
              extra="创建后引擎不可修改，需更换请新建文件。Doris 跟随项目数仓；Spark 走平台 Spark Thrift；Flink 为流式作业、异步提交"
            >
              <Select options={SCRIPT_ENGINES} />
            </Form.Item>
          )}
          <Form.Item label="环境" name="env" initialValue="DEV" extra={ENV_DEV_DEFAULT_HINT}>
            <Select options={ENV_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={moveItem?.kind === 'folder'
          ? `移动文件夹 · ${moveItem.path}`
          : `移动文件 · ${moveItem?.kind === 'workflow' ? moveItem.name : ''}`}
        open={Boolean(moveItem)}
        onOk={() => moveToFolder()}
        onCancel={() => setMoveItem(undefined)}
        okText="移动"
        forceRender
      >
        <Select
          showSearch
          optionFilterProp="label"
          style={{ width: '100%' }}
          value={moveDest}
          onChange={(value) => setMoveDest(value)}
          options={folderOptions}
          placeholder="选择目标文件夹"
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          只列出项目里已有的文件夹；文件夹不能移动到它自己或子文件夹里。
        </Typography.Text>
      </Modal>

      <Modal
        title="版本与发布"
        open={versionOpen}
        width={860}
        footer={null}
        onCancel={() => setVersionOpen(false)}
      >
        <Table<Version>
          rowKey="id"
          dataSource={versions}
          locale={{ emptyText: tableEmpty('还没有版本', '点「发布」后生成第一个版本') }}
          pagination={{
            pageSize: 8,
            hideOnSinglePage: true,
            showTotal: (total) => `共 ${total} 个版本`,
          }}
          columns={[
            {
              title: '版本',
              dataIndex: 'version_no',
              render: (v, _, index) => (
                <Space size={4}>
                  {`v${v}`}
                  {index === 0 ? <Tag color="blue">最新</Tag> : null}
                </Space>
              ),
            },
            {
              title: 'DEV',
              dataIndex: 'dev_status',
              width: 96,
              render: (s) => <VersionEnvStatusTag status={s} />,
            },
            {
              title: 'PROD',
              dataIndex: 'prod_status',
              width: 96,
              render: (s) => <VersionEnvStatusTag status={s} />,
            },
            { title: '创建人', dataIndex: 'created_by' },
            {
              title: '创建时间',
              dataIndex: 'created_at',
              render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
            },
            {
              title: '发布时间',
              dataIndex: 'published_at',
              render: (v) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
            },
            {
              title: '操作',
              render: (_, row) => (
                <Space wrap>
                  <Button
                    size="small"
                    title="查看该版本与当前编辑内容的差异"
                    loading={diffLoadingFor === row.version_no}
                    onClick={() => openDiff(row).catch(() => undefined)}
                  >
                    对比
                  </Button>
                  {can('workflow:update') && (
                    <Button
                      size="small"
                      loading={versionBusy === `dev-${row.version_no}`}
                      disabled={!!versionBusy && versionBusy !== `dev-${row.version_no}`}
                      onClick={() => runVersionAction(`dev-${row.version_no}`,
                        () => publish(row.version_no, 'DEV'))}
                    >
                      发布 DEV
                    </Button>
                  )}
                  {can('workflow:update') && (
                    <Button
                      size="small"
                      loading={versionBusy === `prod-${row.version_no}`}
                      disabled={!!versionBusy && versionBusy !== `prod-${row.version_no}`}
                      onClick={() => runVersionAction(`prod-${row.version_no}`,
                        () => publish(row.version_no, 'PROD'))}
                    >
                      提交 PROD
                    </Button>
                  )}
                  {can('workflow:approve')
                    && (row.prod_status === 'PENDING_APPROVAL'
                      || (row.prod_status == null && row.status === 'PENDING_APPROVAL')) && (
                    <Button
                      size="small"
                      type="primary"
                      loading={versionBusy === `approve-${row.version_no}`}
                      disabled={!!versionBusy && versionBusy !== `approve-${row.version_no}`}
                      onClick={() => runVersionAction(`approve-${row.version_no}`,
                        () => approve(row.version_no))}
                    >
                      审批发布
                    </Button>
                  )}
                  {can('workflow:update') && (
                    <Button
                      size="small"
                      title="以该版本内容创建新版本并发布到当前环境"
                      loading={versionBusy === `rollback-${row.version_no}`}
                      disabled={!!versionBusy && versionBusy !== `rollback-${row.version_no}`}
                      onClick={() => {
                        // 回滚会直接替换当前环境线上版本，必须二次确认
                        Modal.confirm({
                          title: `回滚到 v${row.version_no}？`,
                          content: `会以 v${row.version_no} 的内容创建新版本，并立即发布到 ${wf.env}；${wf.env} 当前版本会被替换。`,
                          okText: '回滚并发布',
                          okButtonProps: { danger: true },
                          onOk: () => runVersionAction(`rollback-${row.version_no}`,
                            () => rollback(row.version_no, wf.env)),
                        })
                      }}
                    >
                      回滚
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Modal>

      <VersionDiffDrawer
        open={diffOpen}
        versionNo={diffVersionNo}
        diff={diffData}
        dirty={diffDirty}
        onClose={() => setDiffOpen(false)}
      />

      <Modal
        title="定时调度"
        open={scheduleOpen}
        onOk={() => saveSchedule().catch((e) => message.error(errMsg(e)))}
        onCancel={() => setScheduleOpen(false)}
        okText="保存"
      >
        <Form form={scheduleForm} layout="vertical">
          <Form.Item label="调度类型" name="scheduleType" rules={[{ required: true }]}>
            <Select options={SCHEDULE_TYPES} />
          </Form.Item>
          <Form.Item
            label={(scheduleType === 'ONCE' ? '运行时间' : '启动时间') + '（北京时间）'}
            name="startAt"
            rules={[{ required: true, message: '请选择启动时间' }]}
            extra={scheduleType === 'ONCE'
              ? '只运行一次，到点自动触发（按北京时间）。'
              : '启动日期 + 时刻；时刻即之后每次运行的时间，统一按北京时间。'}
          >
            <DatePicker
              showTime={{ format: 'HH:mm' }}
              format="YYYY-MM-DD HH:mm"
              style={{ width: '100%' }}
            />
          </Form.Item>
          {scheduleType === 'WEEKLY' ? (
            <Form.Item label="每周几" name="weekdays" rules={[{ required: true, message: '请选择星期几' }]}>
              <Select mode="multiple" options={WEEKDAY_OPTIONS} placeholder="可多选" />
            </Form.Item>
          ) : null}
          {scheduleType === 'MONTHLY' ? (
            <Form.Item label="每月几号" name="monthDays" rules={[{ required: true, message: '请选择日期' }]}>
              <Select mode="multiple" options={MONTH_DAY_OPTIONS} placeholder="可多选" />
            </Form.Item>
          ) : null}
          {scheduleType !== 'ONCE' ? (
            <Form.Item label="结束日期（可选）" name="endDate">
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          ) : null}
          <Form.Item label="状态" name="status">
            <Select options={[
              { value: 'ONLINE', label: '在线' },
              { value: 'OFFLINE', label: '下线' },
            ]} />
          </Form.Item>
          <Alert type="warning" showIcon message="保存后需重新发布这个文件，新的调度才会生效。" />
        </Form>
      </Modal>

      <Modal
        title="补数据"
        open={backfillOpen}
        width={980}
        onOk={() => submitBackfill().catch(() => undefined)}
        onCancel={() => setBackfillOpen(false)}
        okText="提交补数"
        confirmLoading={backfillBusy}
        destroyOnClose
      >
        {!devDeployment && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="还没有发布到 DEV"
            description="补数复用已发布的运行路径，请先把当前文件发布到 DEV。"
          />
        )}
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="补数会按业务日期重跑；同一天重跑必须覆盖而不是追加"
          description="提交前会检查已发布版本：INSERT INTO 追加写需要配套 DELETE FROM 目标表 WHERE 日期 = '${biz_date}' 的前置清理任务，否则拒绝提交。"
        />
        <Form form={backfillForm} layout="vertical">
          <Row gutter={12}>
            <Col span={6}>
              <Form.Item label="环境" name="env" rules={[{ required: true }]} extra={ENV_BACKFILL_HINT}>
                <Select options={ENV_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="日期区间" name="range" rules={[{ required: true }]}>
                <DatePicker.RangePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item
                label="并行度"
                name="parallelism"
                rules={[{ required: true }]}
                extra="同时最多跑几天，1–4"
              >
                <InputNumber min={1} max={4} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>

        {backfillJob && (
          <>
            <Divider orientation="left" style={{ marginTop: 4 }}>
              作业 #{backfillJob.jobId}
              <Tag
                style={{ marginLeft: 8 }}
                color={BACKFILL_JOB_STATUS[backfillJob.status]?.color}
              >
                {BACKFILL_JOB_STATUS[backfillJob.status]?.label || backfillJob.status}
              </Tag>
            </Divider>
            <Progress
              percent={backfillJob.totalDays
                ? Math.round(((backfillJob.doneDays + backfillJob.failedDays) / backfillJob.totalDays) * 100)
                : 0}
              status={backfillJob.status === 'FAILED'
                ? 'exception'
                : backfillJob.status === 'DONE' ? 'success' : 'active'}
              format={() => `${backfillJob.doneDays + backfillJob.failedDays}/${backfillJob.totalDays}`}
            />
            <Space style={{ margin: '8px 0 12px' }}>
              <Typography.Text type="secondary">
                成功 {backfillJob.doneDays} 天 · 失败 {backfillJob.failedDays} 天 · 并行度 {backfillJob.parallelism}
              </Typography.Text>
              {backfillJob.status === 'RUNNING' && (
                <Button size="small" onClick={() => cancelBackfill().catch(() => undefined)}>
                  取消（未开始的日期跳过）
                </Button>
              )}
              {backfillJob.status !== 'RUNNING' && backfillJob.failedDays > 0 && (
                <Button size="small" danger onClick={() => retryBackfill().catch(() => undefined)}>
                  重跑失败的天
                </Button>
              )}
            </Space>
            <Table<BackfillDay>
              size="small"
              rowKey="id"
              pagination={false}
              dataSource={backfillJob.days}
              locale={{ emptyText: tableEmpty('没有补数天', '选择日期范围发起补数后按日期自动列出') }}
              scroll={{ y: 240 }}
              columns={[
                { title: '业务日期', dataIndex: 'bizDate', width: 110 },
                {
                  title: '状态',
                  dataIndex: 'status',
                  width: 90,
                  render: (value: string) => (
                    <Tag color={BACKFILL_DAY_STATUS[value]?.color}>
                      {BACKFILL_DAY_STATUS[value]?.label || value}
                    </Tag>
                  ),
                },
                {
                  title: '运行实例',
                  dataIndex: 'runId',
                  width: 180,
                  render: (value?: string | null) => value
                    ? <Typography.Text copyable={{ text: value }} style={{ fontSize: 12 }}>{value.slice(0, 8)}…</Typography.Text>
                    : <Typography.Text type="secondary">-</Typography.Text>,
                },
                {
                  title: '说明',
                  dataIndex: 'message',
                  ellipsis: true,
                  render: (value?: string | null) => value || <Typography.Text type="secondary">-</Typography.Text>,
                },
                {
                  title: '操作',
                  width: 96,
                  render: (_: unknown, row) => row.status === 'FAILED' && backfillJob.status !== 'RUNNING'
                    ? (
                      <Button type="link" size="small"
                        onClick={() => retryBackfill(row.bizDate).catch(() => undefined)}>
                        重跑这天
                      </Button>
                    )
                    : null,
                },
              ]}
            />
          </>
        )}

        <Divider orientation="left" style={{ marginTop: 16 }}>补数历史</Divider>
        <Table<BackfillSummary>
          size="small"
          rowKey="jobId"
          dataSource={backfillHistory}
          pagination={false}
          scroll={{ y: 180 }}
          locale={{ emptyText: tableEmpty('还没有补数记录', '在补数面板发起一次补数后生成') }}
          onRow={(row) => ({
            onClick: () => loadBackfillJob(row.jobId).catch(() => undefined),
            style: { cursor: 'pointer' },
          })}
          columns={[
            { title: '作业', dataIndex: 'jobId', width: 80, render: (v: number) => `#${v}` },
            { title: '环境', dataIndex: 'env', width: 70 },
            { title: '区间', width: 200, render: (_: unknown, row) => `${row.dateStart} ~ ${row.dateEnd}` },
            { title: '并行度', dataIndex: 'parallelism', width: 70 },
            {
              title: '状态',
              dataIndex: 'status',
              width: 90,
              render: (value: string) => (
                <Tag color={BACKFILL_JOB_STATUS[value]?.color}>
                  {BACKFILL_JOB_STATUS[value]?.label || value}
                </Tag>
              ),
            },
            {
              title: '进度',
              width: 120,
              render: (_: unknown, row) => `${row.doneDays + row.failedDays}/${row.totalDays}（失败 ${row.failedDays}）`,
            },
            {
              title: '提交时间',
              dataIndex: 'createdAt',
              render: (value?: string) => value ? dayjs(value).format('MM-DD HH:mm') : '-',
            },
          ]}
        />
      </Modal>

      <Modal
        title="手动触发"
        open={triggerOpen}
        onOk={() => doTrigger().catch(() => undefined)}
        onCancel={() => setTriggerOpen(false)}
        okText="触发"
        confirmLoading={triggering}
      >
        {!devDeployment && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="还没有发布到 DEV"
            description="触发运行需要先把当前文件发布到 DEV；发布后回到这里即可运行。"
            action={(
              <Button
                size="small"
                onClick={() => {
                  setTriggerOpen(false)
                  openPublish().catch((e) => message.error(errMsg(e)))
                }}
              >
                去发布
              </Button>
            )}
          />
        )}
        {devDeployment
          && dayjs().diff(dayjs(devDeployment.deployed_at), 'minute') < 10 && (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="刚发布不久"
            description="执行器同步 Flow 文件约需 1 分钟；第一次触发如果提示「同步中」，稍等后点「触发」重试即可。"
          />
        )}
        <Form form={triggerForm} layout="vertical">
          <Form.Item label="环境" name="env" rules={[{ required: true }]} extra={ENV_FOLLOW_FILE_HINT}>
            <Select options={ENV_OPTIONS} />
          </Form.Item>
          <Form.Item label="业务日期" name="bizDate" rules={[{ required: true }]}>
            <DatePicker style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={`试跑（${runResult?.env || 'DEV'}） · ${runResult?.taskName || selected?.name || ''}`}
        open={runOpen}
        width={880}
        onClose={() => setRunOpen(false)}
      >
        {runLoading ? <LoadingState rows={5} /> : runError ? (
          <Alert type="error" showIcon message="试跑失败" description={runError} />
        ) : runResult ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {runResult.message ? (
              <Alert
                type="info"
                showIcon
                message={`执行完成（引擎 ${runResult.engine || 'FLINK'}）`}
                description={runResult.message}
              />
            ) : (
              <Alert
                type="success"
                showIcon
                message={`执行成功：返回 ${runResult.rowCount} 行，耗时 ${runResult.elapsedMs} ms`}
                description={`引擎 ${runResult.engine || 'DORIS'}；业务日期 ${runResult.bizDate}，最多展示 ${runResult.maxRows} 行；试跑不生成运行实例。`}
              />
            )}
            {/* D38：把「跑在哪个环境、哪个库、哪个连接」摊开显示，便于验收核对 */}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              环境 <b>{runResult.env || 'DEV'}</b>
              {' · '}实际库 <b>{runResult.database || '（未使用项目库：跟随所选数据源/连接）'}</b>
              {runResult.target ? <> · 连接 <b>{runResult.target}</b></> : null}
            </Typography.Text>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>实际执行的 SQL</Typography.Text>
              <pre className="code-block">{runResult.executedSql}</pre>
            </div>
            <Table<{ key: number; cells: (string | null)[] }>
              size="small"
              rowKey="key"
              dataSource={runResult.rows.map((cells, index) => ({ key: index, cells }))}
              pagination={false}
              scroll={{ x: 'max-content' }}
              locale={{ emptyText: tableEmpty('执行成功，没有返回数据行', '写入 / DDL 语句本来就不返回结果集') }}
              columns={runResult.columns.map((column, index) => ({
                title: column || `col_${index + 1}`,
                render: (_: unknown, row: { cells: (string | null)[] }) => row.cells[index] ?? '',
              }))}
            />
          </Space>
        ) : null}
      </Drawer>

      <Drawer
        title="生成的 DAG（Python）"
        open={previewOpen}
        width={800}
        onClose={() => setPreviewOpen(false)}
      >
        <pre className="code-block">{previewCode}</pre>
      </Drawer>
    </div>
  )
}
