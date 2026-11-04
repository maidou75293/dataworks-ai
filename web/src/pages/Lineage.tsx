import { useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Card, Drawer, Segmented, Space, Table, Tag, Typography } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import ReactFlow, {
  Background, Controls, Handle, MarkerType, MiniMap, Position,
  useEdgesState, useNodesState,
  type Edge as FlowEdge, type Node, type NodeProps,
} from 'reactflow'
import 'reactflow/dist/style.css'
import { api } from '../api'
import PageHeader from '../components/PageHeader'
import SearchableSelect from '../components/SearchableSelect'
import { EmptyState, ErrorState, tableEmpty } from '../components/StateBlocks'
import { ENV_OPTIONS } from '../envOptions'

type Edge = {
  id: number
  workflow_id: number
  workflow_code: string
  workflow_name: string
  version_no: number
  task_code: string
  source_table: string
  target_table: string
  source_column: string
  target_column: string
  edge_type: 'TABLE' | 'COLUMN'
  origin: 'PUBLISH' | 'RUNTIME'
  /** measured = 引擎 EXPLAIN 计划确认；inferred = 静态解析推断 */
  confidence: 'measured' | 'inferred'
  run_id: string | null
  occurred_at: string
  created_at: string
}

type WorkflowRef = { id: number; code: string; name: string }

type LineageResponse = {
  count: number
  edges: Edge[]
  workflows: WorkflowRef[]
  versions: number[]
  focusFound?: boolean
  componentTables?: number
  summary: { origin: string; edge_type: string; edges: number }[]
}

type TableRef = { table_name: string; edges: number }

type Mode = 'table' | 'column'
type Origin = 'ALL' | 'PUBLISH' | 'RUNTIME'

type LineageNodeData = {
  label: string
  full: string
  kind: 'source' | 'middle' | 'target' | 'isolated'
  origin: string
  dim?: boolean
  onSelect?: () => void
}

function LineageNode({ data }: NodeProps<LineageNodeData>) {
  return (
    <div className={`lineage-node ${data.kind}${data.dim ? ' dim' : ''}`}
         onClick={() => data.onSelect?.()}>
      <Handle type="target" position={Position.Left} />
      <div className="lineage-node-name" title={data.full}>{data.label}</div>
      <div className="lineage-node-meta">
        {data.origin}
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

const nodeTypes = { lineage: LineageNode }

const ORIGIN_LABEL: Record<string, string> = { PUBLISH: '发布', RUNTIME: '运行期' }

function originText(origins: Set<string>) {
  if (origins.has('PUBLISH') && origins.has('RUNTIME')) return '发布+运行期'
  return origins.has('RUNTIME') ? '运行期' : '发布'
}

/** 去掉库名 + 所有节点共有的长前缀，避免复杂 DAG 的节点名全被截断成一样。 */
function shortNames(tables: Set<string>): Map<string, string> {
  const stripped = new Map<string, string>()
  tables.forEach((t) => stripped.set(t, t.includes('.') ? t.slice(t.lastIndexOf('.') + 1) : t))
  const names = [...stripped.values()]
  if (names.length < 2) return stripped
  let prefix = names[0]
  names.forEach((n) => {
    while (prefix && !n.startsWith(prefix)) prefix = prefix.slice(0, -1)
  })
  const cut = prefix.lastIndexOf('_') + 1
  if (cut >= 8) {
    stripped.forEach((name, table) => stripped.set(table, '…' + name.slice(cut)))
  }
  return stripped
}

function buildGraph(edges: Edge[], mode: Mode): { nodes: Node<LineageNodeData>[]; flows: FlowEdge[] } {
  const relevant = mode === 'column'
    ? edges.filter((e) => e.edge_type === 'COLUMN' && e.source_table && e.source_column && e.target_table)
    : edges.filter((e) => e.edge_type === 'TABLE')

  const keyOf = (table: string, column?: string) => (mode === 'column' ? `${table}.${column}` : table)
  const names = new Set<string>()
  const tableNames = new Set<string>()
  const pairs = new Map<string, {
    source: string; target: string; count: number; origins: Set<string>; records: Edge[]
    measured: number
  }>()

  relevant.forEach((e) => {
    const source = keyOf(e.source_table, e.source_column)
    const target = keyOf(e.target_table, e.target_column)
    if (!source || !target || source === target) return
    names.add(source)
    names.add(target)
    tableNames.add(e.source_table)
    tableNames.add(e.target_table)
    const key = `${source}\u0000${target}`
    const item = pairs.get(key) || {
      source, target, count: 0, origins: new Set<string>(), records: [] as Edge[], measured: 0,
    }
    item.count += 1
    item.origins.add(e.origin)
    if (e.confidence === 'measured') item.measured += 1
    item.records.push(e)
    pairs.set(key, item)
  })

  const short = shortNames(tableNames)
  const displayOf = (id: string) => {
    let best = ''
    tableNames.forEach((t) => {
      if ((id === t || id.startsWith(t + '.')) && t.length > best.length) best = t
    })
    if (!best) return id
    return (short.get(best) || best) + id.slice(best.length)
  }

  const outgoing = new Map<string, Set<string>>()
  const indegree = new Map<string, number>()
  names.forEach((name) => {
    outgoing.set(name, new Set())
    indegree.set(name, 0)
  })
  pairs.forEach(({ source, target }) => {
    if (!outgoing.get(source)!.has(target)) {
      outgoing.get(source)!.add(target)
      indegree.set(target, (indegree.get(target) || 0) + 1)
    }
  })

  // Kahn 分层：没有入边的放第 0 层，后面的按最长路径下移
  const level = new Map<string, number>()
  const queue: string[] = []
  names.forEach((name) => {
    if ((indegree.get(name) || 0) === 0) {
      level.set(name, 0)
      queue.push(name)
    }
  })
  let guard = 0
  while (queue.length > 0 && guard < 100000) {
    guard += 1
    const name = queue.shift()!
    const lv = level.get(name) || 0
    outgoing.get(name)!.forEach((child) => {
      level.set(child, Math.max(level.get(child) || 0, lv + 1))
      const left = (indegree.get(child) || 1) - 1
      indegree.set(child, left)
      if (left === 0) queue.push(child)
    })
  }
  names.forEach((name) => {
    if (!level.has(name)) level.set(name, 0)
  })

  const byLevel = new Map<number, string[]>()
  names.forEach((name) => {
    const lv = level.get(name) || 0
    if (!byLevel.has(lv)) byLevel.set(lv, [])
    byLevel.get(lv)!.push(name)
  })
  const maxRows = Math.max(1, ...[...byLevel.values()].map((list) => list.length))
  const rowGap = mode === 'column' ? 74 : 92

  const nodes: Node<LineageNodeData>[] = []
  byLevel.forEach((list, lv) => {
    list.sort()
    list.forEach((name, index) => {
      const y = 40 + (index + (maxRows - list.length) / 2) * rowGap
      const hasUpstream = relevant.some(
        (e) => keyOf(e.target_table, e.target_column) === name)
      const hasDownstream = relevant.some(
        (e) => keyOf(e.source_table, e.source_column) === name)
      const origins = new Set(relevant
        .filter((e) => keyOf(e.source_table, e.source_column) === name
          || keyOf(e.target_table, e.target_column) === name)
        .map((e) => e.origin))
      nodes.push({
        id: name,
        type: 'lineage',
        position: { x: 40 + lv * (mode === 'column' ? 320 : 280), y },
        data: {
          label: displayOf(name),
          full: name,
          kind: hasUpstream && hasDownstream ? 'middle'
            : hasUpstream ? 'target' : hasDownstream ? 'source' : 'isolated',
          origin: originText(origins),
        },
      })
    })
  })

  const flows: FlowEdge[] = []
  pairs.forEach(({ source, target, origins, records, measured }) => {
    const runtimeOnly = origins.size === 1 && origins.has('RUNTIME')
    flows.push({
      id: `${source}->${target}`,
      source,
      target,
      animated: !runtimeOnly,
      label: measured > 0 ? '实测' : undefined,
      labelStyle: { fill: '#08979c', fontSize: 11, fontWeight: 600 },
      labelShowBg: measured > 0,
      labelBgStyle: { fill: '#e6fffb', fillOpacity: 0.95 },
      labelBgPadding: [4, 2],
      labelBgBorderRadius: 3,
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 22,
        height: 22,
        color: runtimeOnly ? '#52c41a' : '#1677ff',
      },
      // 连线上不再显示聚合条数：多条记录双击连线看明细，避免细线上叠文字
      data: { records },
      // 3px 起：1-2px 的线在高分屏/投屏上几乎看不见
      style: {
        stroke: runtimeOnly ? '#52c41a' : '#1677ff',
        strokeWidth: 3,
        strokeDasharray: runtimeOnly ? '7 5' : undefined,
      },
    })
  })

  return { nodes, flows }
}

export default function Lineage({ embedded = false }: { embedded?: boolean }) {
  const params = new URLSearchParams(window.location.search)
  const [focusTable, setFocusTable] = useState(params.get('table') || '')
  const [mode, setMode] = useState<Mode>(
    params.get('edgeType') === 'COLUMN' ? 'column' : 'table')
  const [origin, setOrigin] = useState<Origin>(
    params.get('origin') === 'PUBLISH' || params.get('origin') === 'RUNTIME'
      ? params.get('origin') as Origin : 'ALL')
  const [env, setEnv] = useState<'DEV' | 'PROD'>(params.get('env') === 'PROD' ? 'PROD' : 'DEV')
  const [versionNo, setVersionNo] = useState<number | undefined>(
    params.get('versionNo') ? Number(params.get('versionNo')) : undefined)
  const [versions, setVersions] = useState<number[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [tables, setTables] = useState<TableRef[]>([])
  const [focusFound, setFocusFound] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<string>('')
  const [edgeRecords, setEdgeRecords] = useState<Edge[] | null>(null)
  // 快速切换表/版本时，旧请求晚返回不能覆盖新结果
  const seq = useRef(0)

  useEffect(() => {
    api<{ tables: TableRef[] }>(`/api/lineage/tables?env=${env}`)
      .then((res) => setTables(res.tables || []))
      .catch(() => { /* 下拉清单失败不影响 URL 直达 */ })
  }, [env])

  const load = async () => {
    if (!focusTable) {
      setEdges([])
      setVersions([])
      setFocusFound(true)
      setLoading(false)
      return
    }
    const my = ++seq.current
    // 切表/环境时先清空：请求期间和失败后不能把上一张表的图挂在新标题下
    setEdges([])
    setSelected('')
    setVersions([])
    setError('')
    setLoading(true)
    try {
      const q = new URLSearchParams()
      q.set('focus', focusTable)
      q.set('depth', '3')
      q.set('limit', '2000')
      q.set('env', env)
      if (versionNo) q.set('versionNo', String(versionNo))
      const res = await api<LineageResponse>(`/api/lineage?${q}`)
      if (my !== seq.current) return
      setEdges(res.edges || [])
      setFocusFound(res.focusFound !== false)
      setVersions(res.versions || [])
      setError('')
    } catch (e: any) {
      if (my !== seq.current) return
      setError(e.message || '加载血缘失败')
    } finally {
      if (my === seq.current) setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [focusTable, versionNo, env])

  // 表级/列级与来源都只是视图筛选：接口一次返回全部边，切视图不重新请求
  const visibleEdges = useMemo(() => edges.filter((e) => (
    (mode === 'column' ? e.edge_type === 'COLUMN' : e.edge_type === 'TABLE')
    && (origin === 'ALL' || e.origin === origin)
  )), [edges, mode, origin])
  const graph = useMemo(() => buildGraph(visibleEdges, mode), [visibleEdges, mode])

  const related = useMemo(() => {
    if (!selected) return null
    const adjacency = new Map<string, Set<string>>()
    graph.flows.forEach((f) => {
      if (!adjacency.has(f.source)) adjacency.set(f.source, new Set())
      if (!adjacency.has(f.target)) adjacency.set(f.target, new Set())
      adjacency.get(f.source)!.add(f.target)
      adjacency.get(f.target)!.add(f.source)
    })
    const seen = new Set<string>([selected])
    const stack = [selected]
    while (stack.length) {
      const cur = stack.pop()!
      adjacency.get(cur)?.forEach((next) => {
        if (!seen.has(next)) {
          seen.add(next)
          stack.push(next)
        }
      })
    }
    return seen
  }, [selected, graph.flows])

  const renderNodes = useMemo(() => graph.nodes.map((node) => ({
    ...node,
    data: {
      ...node.data,
      dim: !!related && !related.has(node.id),
      onSelect: () => setSelected((cur) => cur === node.id ? '' : node.id),
    },
  })), [graph.nodes, related])

  const renderEdges = useMemo(() => graph.flows.map((edge) => ({
    ...edge,
    style: {
      ...edge.style,
      opacity: related && !(related.has(edge.source) && related.has(edge.target)) ? 0.12 : 1,
    },
  })), [graph.flows, related])

  const [flowNodes, setFlowNodes, onNodesChange] = useNodesState<LineageNodeData>([])
  const [flowEdges, setFlowEdges, onEdgesChange] = useEdgesState([])
  useEffect(() => {
    setFlowNodes(renderNodes)
    setFlowEdges(renderEdges)
  }, [renderNodes, renderEdges, setFlowNodes, setFlowEdges])

  const controls = (
    <Space wrap>
      <Segmented
        value={mode}
        onChange={(v) => { setMode(v as Mode); setSelected('') }}
        options={[
          { value: 'table', label: '表级' },
          { value: 'column', label: '列级' },
        ]}
      />
      <Segmented
        value={origin}
        onChange={(v) => { setOrigin(v as Origin); setSelected('') }}
        options={[
          { value: 'ALL', label: '全部来源' },
          { value: 'PUBLISH', label: '发布' },
          { value: 'RUNTIME', label: '运行期' },
        ]}
      />
      <SearchableSelect
        style={{ width: 170 }}
        value={env}
        onChange={(v) => {
          setEnv((v as 'DEV' | 'PROD') || 'DEV')
          setVersionNo(undefined)
          setSelected('')
        }}
        options={ENV_OPTIONS}
      />
      <SearchableSelect
        allowClear
        style={{ width: 360 }}
        placeholder="选择表"
        value={focusTable || undefined}
        onChange={(v) => {
          setFocusTable(v || '')
          setVersionNo(undefined)
          setSelected('')
        }}
        options={tables.map((t) => ({
          value: t.table_name,
          label: t.table_name,
        }))}
      />
      {versions.length > 1 ? (
        <SearchableSelect
          allowClear
          style={{ width: 170 }}
          placeholder="最新版本（默认）"
          value={versionNo}
          onChange={(v) => {
            setVersionNo(v)
            setSelected('')
          }}
          options={versions.map((v) => ({ value: v, label: `v${v}` }))}
        />
      ) : null}
      <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>刷新</Button>
    </Space>
  )

  return (
    <div>
      {embedded ? (
        <Card size="small" style={{ marginBottom: 16 }}>{controls}</Card>
      ) : (
        <PageHeader
          title="数据血缘"
          description="选择一张表，只看它的最新上下游；有多版本时可切换版本查看历史。"
          extra={controls}
        />
      )}

      {error ? <ErrorState message={error} onRetry={load} /> : null}

      <Card
        size="small"
        title={focusTable ? `血缘 DAG · ${focusTable}（${env}）` : '血缘 DAG'}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="默认按最新版本展示：节点只依赖该环境最新发布版本的上下游，历史版本不参与；图上没有继续向下的连线，就表示这张表没有下游。"
        />
        {!focusTable ? (
          <EmptyState
            title="选择一张表查看血缘"
            hint="在上方下拉框选择表名，默认展示最新发布版本与最近一次运行的关系"
          />
        ) : graph.nodes.length === 0 ? (
          <EmptyState
            title={focusFound ? '这张表还没有血缘' : `没有找到「${focusTable}」的血缘`}
            hint={focusFound ? '发布含 INSERT / CREATE TABLE AS 的 SQL 文件后自动生成' : '换一张表或清空版本筛选再试'}
          />
        ) : (
          <div style={{ height: 560 }}>
            <ReactFlow
              nodes={flowNodes}
              edges={flowEdges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              nodeTypes={nodeTypes}
              nodesConnectable={false}
              fitView
              fitViewOptions={{ padding: 0.15, maxZoom: 1.2 }}
              minZoom={0.05}
              onPaneClick={() => setSelected('')}
              onEdgeDoubleClick={(_event, edge) => {
                const records = (edge.data as { records?: Edge[] } | undefined)?.records
                if (records?.length) {
                  setEdgeRecords(records)
                }
              }}
            >
              <Background />
              <MiniMap pannable zoomable />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
        )}
        <Space size="large" style={{ marginTop: 8, fontSize: 12, color: '#646a73' }}>
          <span>蓝色实线：发布血缘</span>
          <span>绿色虚线：运行期血缘</span>
          <span>标「实测」的连线：引擎 EXPLAIN 计划确认到的表级边</span>
          <span>未标注 = 静态解析推断（inferred）</span>
          <span>双击连线：查看这条上下游的明细记录</span>
        </Space>
      </Card>

      <Card size="small" title={`血缘边（${visibleEdges.length}）`} style={{ marginTop: 16 }}>
        {error ? <ErrorState message={error} onRetry={load} /> : (
          <Table
            size="small"
            rowKey="id"
            loading={loading}
            dataSource={visibleEdges}
            scroll={{ x: 1264 }}
            pagination={{ pageSize: 10 }}
            locale={{ emptyText: tableEmpty('没有匹配的血缘边', '发布含 INSERT / CREATE AS 的 SQL 文件后自动生成') }}
            columns={[
              {
                title: '文件',
                key: 'workflow',
                width: 180,
                render: (_: unknown, r: Edge) => (
                  <Space direction="vertical" size={0}>
                    <span>{r.workflow_name}</span>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.workflow_code}</Typography.Text>
                  </Space>
                ),
              },
              { title: '版本', dataIndex: 'version_no', key: 'version_no', width: 64,
                render: (v: number) => `v${v}` },
              { title: '任务', dataIndex: 'task_code', key: 'task_code', width: 120 },
              {
                title: '上游',
                key: 'source',
                render: (_: unknown, r: Edge) => (
                  r.source_table
                    ? <Tag color="blue">{r.source_table}{r.source_column ? `.${r.source_column}` : ''}</Tag>
                    : <Typography.Text type="secondary">-</Typography.Text>
                ),
              },
              {
                title: '下游',
                key: 'target',
                render: (_: unknown, r: Edge) => (
                  r.target_table
                    ? <Tag color="green">{r.target_table}{r.target_column ? `.${r.target_column}` : ''}</Tag>
                    : <Typography.Text type="secondary">只读</Typography.Text>
                ),
              },
              {
                title: '类型',
                dataIndex: 'edge_type',
                key: 'edge_type',
                width: 74,
                render: (v: string) => <Tag color={v === 'COLUMN' ? 'purple' : 'default'}>{v === 'COLUMN' ? '列级' : '表级'}</Tag>,
              },
              {
                title: '来源',
                dataIndex: 'origin',
                key: 'origin',
                width: 84,
                render: (v: string) => <Tag color={v === 'RUNTIME' ? 'green' : 'blue'}>{ORIGIN_LABEL[v] || v}</Tag>,
              },
              {
                title: '置信度',
                dataIndex: 'confidence',
                key: 'confidence',
                width: 84,
                render: (v: string) => (v === 'measured'
                  ? <Tag color="cyan" title="引擎 EXPLAIN 计划确认">实测</Tag>
                  : <Tag title="静态解析推断">推断</Tag>),
              },
              {
                title: '记录时间',
                dataIndex: 'occurred_at',
                key: 'occurred_at',
                width: 160,
                render: (v: string) => (v ? new Date(v).toLocaleString() : ''),
              },
            ]}
          />
        )}
      </Card>

      <Drawer
        open={!!edgeRecords}
        onClose={() => setEdgeRecords(null)}
        width={820}
        title={edgeRecords?.length ? `血缘明细 · ${edgeRecords.length} 条记录` : '血缘明细'}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          一对上下游可能同时有发布期和运行期记录，这里全部列出，
          可看到每条记录来自哪个文件、版本与任务。
        </Typography.Paragraph>
        <Table
          size="small"
          rowKey="id"
          dataSource={edgeRecords || []}
          pagination={false}
          scroll={{ x: 836 }}
          columns={[
            {
              title: '文件', key: 'workflow', width: 160,
              render: (_: unknown, r: Edge) => (
                <Space direction="vertical" size={0}>
                  <span>{r.workflow_name}</span>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.workflow_code}</Typography.Text>
                </Space>
              ),
            },
            { title: '版本', dataIndex: 'version_no', key: 'version_no', width: 64,
              render: (v: number) => `v${v}` },
            { title: '任务', dataIndex: 'task_code', key: 'task_code', width: 110 },
            {
              title: '上游', key: 'source',
              render: (_: unknown, r: Edge) => (
                r.source_table
                  ? <Tag color="blue">{r.source_table}{r.source_column ? `.${r.source_column}` : ''}</Tag>
                  : <Typography.Text type="secondary">-</Typography.Text>
              ),
            },
            {
              title: '下游', key: 'target',
              render: (_: unknown, r: Edge) => (
                r.target_table
                  ? <Tag color="green">{r.target_table}{r.target_column ? `.${r.target_column}` : ''}</Tag>
                  : <Typography.Text type="secondary">只读</Typography.Text>
              ),
            },
            {
              title: '类型', dataIndex: 'edge_type', key: 'edge_type', width: 70,
              render: (v: string) => (
                <Tag color={v === 'COLUMN' ? 'purple' : 'default'}>{v === 'COLUMN' ? '列级' : '表级'}</Tag>
              ),
            },
            {
              title: '血缘来源', dataIndex: 'origin', key: 'origin', width: 84,
              render: (v: string) => <Tag color={v === 'RUNTIME' ? 'green' : 'blue'}>{ORIGIN_LABEL[v] || v}</Tag>,
            },
            {
              title: '置信度', dataIndex: 'confidence', key: 'confidence', width: 76,
              render: (v: string) => (v === 'measured'
                ? <Tag color="cyan">实测</Tag>
                : <Tag>推断</Tag>),
            },
            {
              title: 'run_id', dataIndex: 'run_id', key: 'run_id', width: 120,
              render: (v: string | null) => (v
                ? <Typography.Text copyable={{ text: v }} style={{ fontSize: 12 }}>{v.slice(0, 8)}…</Typography.Text>
                : '-'),
            },
            {
              title: '记录时间', dataIndex: 'occurred_at', key: 'occurred_at', width: 150,
              render: (v: string) => (v ? new Date(v).toLocaleString() : ''),
            },
          ]}
        />
      </Drawer>
    </div>
  )
}
