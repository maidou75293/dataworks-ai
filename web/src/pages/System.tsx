import { useEffect, useState } from 'react'
import {
  Alert, Button, Card, Col, Descriptions, Input, InputNumber, Modal, Popconfirm, Progress, Row, Select, Space,
  Statistic, Table, Tabs, Tag, Tooltip, Typography, message,
} from 'antd'
import { ReloadOutlined, CloudServerOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import type { ColumnsType } from 'antd/es/table'
import { api, del, post, put } from '../api'
import { can } from '../permissions'

// 对话智能体同源快照（REQ-AGENT-CFGPANEL-1）：读自 agent-service 进程当前生效值。
// 类型里刻意没有 apiKey 字段——服务端根本不下发密钥明文（只有已配置 + 尾 4 位指纹），
// 「密钥永不回显」在结构层成立，不靠渲染时记得脱敏。
type AgentModelConfig = {
  ok: boolean
  service?: string
  baseUrl: string
  modelName: string
  keyConfigured: boolean
  keyFingerprint: string
  healthy: boolean
  healthReason?: string | null
}

type NodeRow = {
  name: string
  ready: boolean
  schedulable: boolean
  internalIp: string
  cpuCapacity: string
  memoryCapacity: string
  pods: number
  cpuPercent: number | null
  memoryPercent: number | null
  diskPercent: number | null
  diskTotalGb?: string
  diskFreeGb?: string
  memoryTotalGb?: string
  memoryUsedGb?: string
}

type PodRow = {
  namespace: string
  name: string
  phase: string
  node: string
  ready: boolean
  restarts: number
  reason: string
  startTime: string
}

type DeploymentRow = {
  name: string
  desired: number
  available: number
  unavailable: number
}

type ComponentRow = {
  name: string
  ready: number
  total: number
  detail: string
}

type ClusterNode = {
  name: string
  ip: string
  status: string
  healthy: boolean
  cpu: string
  memory: string
  disk: string
  extra: string
}

type ClusterInfo = {
  key: string
  name: string
  role: string
  status: string
  healthy: boolean
  summary: string
  note: string
  nodes: ClusterNode[]
  scaleKind?: string | null
  scaleName?: string | null
  scaleReplicas?: number | null
  scaleReason?: string | null
}

type SeatunnelOverview = {
  clusterNodeCount: number
  desiredNodes: number
  readyNodes: number
  runningJobs: number
  jobs: Record<string, number>
}

type ServiceRow = {
  namespace: string
  kind: 'deployment' | 'statefulset' | 'daemonset'
  name: string
  replicas: number
  ready: number
  cpuUsage: string
  memoryUsage: string
  memoryRequest: string
  memoryLimit: string
  memoryLimitTotal: string
  stopBlockedReason: string
  stopped: boolean
  stoppable: boolean
  usageAvailable: boolean
}

type DorisNodeRow = {
  name: string
  ip: string
  role: string
  schedulable: boolean
  bePodRunning: boolean
  registered: boolean
  alive: boolean
  decommissioned: boolean
  tablets: number
  dataUsed: string
  usedPct: string
  capacity: string
  note: string
}

type Overview = {
  generatedAt: string
  summary: {
    nodesReady: number
    nodesTotal: number
    podsRunning: number
    podsAbnormal: number
    deploymentsUnavailable: number
    maxCpuPercent: number | null
    maxMemoryPercent: number | null
    maxDiskPercent: number | null
    maxCpuNode: string
    maxMemoryNode: string
    maxDiskNode: string
    alertsOpen: number
    systemAlertsOpen: number
  }
  nodes: NodeRow[]
  abnormalPods: PodRow[]
  deployments: DeploymentRow[]
  components: ComponentRow[]
  seatunnel?: SeatunnelOverview
  clusters?: ClusterInfo[]
}

/** GET /api/query-limits 的字段契约（D45）：范围与含义都由后端给，前端不再抄一份常量。 */
type LimitField = {
  key: string
  label: string
  unit: string
  min: number
  max: number
  hint: string
  /** false＝这个值能存但后端没有消费点，界面标「未生效」并禁编辑 */
  enforced: boolean
}

type QueryLimitsView = {
  tenantId: string
  limits: Record<string, number>
  defaults: Record<string, number>
  inflight: number
  fields: LimitField[]
}

/** TC-8 界面卫生：弹窗底部「关闭」按钮只保留一个定义点；关闭行为由调用方传入。 */
function modalCloseBtn(onClose: () => void) {
  return <Button type="primary" onClick={onClose}>关闭</Button>
}

function pct(value: number | null) {
  if (value === null || value === undefined) return <Typography.Text type="secondary">-</Typography.Text>
  // 80% 以上必须一眼看出是红的；antd 的 status=normal 仍是主题蓝，起不到警示作用
  const color = value >= 80 ? '#f53f3f' : value >= 60 ? '#faad14' : '#00b42a'
  return (
    <Progress
      percent={Math.min(100, Math.round(value))}
      size="small"
      strokeColor={color}
      format={() => `${value.toFixed(1)}%`}
    />
  )
}

// ---------- 内存右调建议器（REQ-SYS-RIGHTSIZE-1）----------
type RightSizeContainer = {
  container: string
  currentRequest: string; currentRequestBytes: number
  currentLimit: string; currentLimitBytes: number
  p95: string | null; p95Bytes: number; podSamples: number; dataAvailable: boolean
  declared: string | null; declaredBytes: number; declaredKind: string; declaredSource: string | null
  suggestedRequest: string | null; suggestedRequestBytes: number
  suggestedLimit: string | null; suggestedLimitBytes: number
  reason: string; inversion: boolean
}

type RightSizeRow = {
  namespace: string; kind: string; name: string; replicas: number
  applyable: boolean; containers: RightSizeContainer[]
}

type RightSizeData = {
  rows: RightSizeRow[]
  quota: Record<string, Record<string, string>>
  allocatable: {
    nodes: number; totalBytes: number; usedRequestBytes: number; total: string; usedRequest: string
  }
  generatedAt: string; windowDays: number; stepMi: number; namespaces: string[]
}

type ApplyPlan = {
  namespace: string; kind: string; name: string; dryRun: boolean; mode: string; applied: boolean
  containers: {
    container: string; oldRequest: string; oldLimit: string
    newRequest: string; newLimit: string; declared: string | null
  }[]
  rewritePlan: string[]
  gates: string[]
}

export default function System() {
  const nav = useNavigate()
  const [data, setData] = useState<Overview>()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [services, setServices] = useState<ServiceRow[]>([])
  const [flinkCluster, setFlinkCluster] = useState<any>(null)
  const [usageAvailable, setUsageAvailable] = useState(true)
  const [servicesAt, setServicesAt] = useState('')
  const [servicesError, setServicesError] = useState('')
  const [flinkError, setFlinkError] = useState('')
  const [nsFilter, setNsFilter] = useState<string>('')
  const [busy, setBusy] = useState('')
  const [replicaDraft, setReplicaDraft] = useState<Record<string, number>>({})
  const [memoryDraft, setMemoryDraft] = useState<Record<string, string>>({})
  // Doris BE 是 DaemonSet（一节点一实例），扩缩容的对象是 K8s 节点
  const [dorisOpen, setDorisOpen] = useState(false)
  const [dorisNodes, setDorisNodes] = useState<DorisNodeRow[]>([])
  const [dorisMeta, setDorisMeta] = useState({ clusterNodeCount: 0, joinedNodeCount: 0 })
  const [dorisBusy, setDorisBusy] = useState('')
  // 集群级扩缩容：目前只有 Zeta 能按副本数改（Doris 走节点管理，MinIO 分布式不支持）
  const [clusterDraft, setClusterDraft] = useState<Record<string, number>>({})
  // k3s 节点运维：封锁/驱逐/移除 + 加节点命令
  const [nodeBusy, setNodeBusy] = useState('')
  // D54 移除节点：弹窗里显示 dryRun 预览到的受影响 Pod 清单
  const [nodeRemove, setNodeRemove] = useState<{ name: string; pods: string[] }>()
  const [nodeRemoveBusy, setNodeRemoveBusy] = useState(false)
  const [joinOpen, setJoinOpen] = useState(false)
  const [joinInfo, setJoinInfo] = useState<{
    serverUrl: string; tokenCommand: string; joinCommand: string; steps: string[]; note: string
  }>()
  const [clusterBusy, setClusterBusy] = useState('')
  const [replBusy, setReplBusy] = useState(false)
  // 单副本表在退役迁移窗口内没有冗余副本，弹窗里提前提示
  const [singleReplica, setSingleReplica] = useState<{ count: number; detail: string[] }>(
    { count: 0, detail: [] })
  // 副本迁移进度：Doris 补副本是异步的，点了按钮不会立刻变，必须给进度条
  const [replProgress, setReplProgress] = useState<{
    target?: number
    percent: number
    replicas: number
    expectedReplicas: number
    tableCount: number
    singleReplicaCount: number
    complete: boolean
    tables: { name: string; replication: number; replicas: number; expected: number; percent: number }[]
  }>()
  // 查询限额（D45）：view 是服务端生效值，draft 是还没保存的编辑值，两者分开才不会互相冲掉
  const [limitsView, setLimitsView] = useState<QueryLimitsView | null>(null)
  const [limitsDraft, setLimitsDraft] = useState<Record<string, number>>({})
  const [limitsBusy, setLimitsBusy] = useState(false)
  const [limitsError, setLimitsError] = useState('')
  // 对话智能体区块（REQ-AGENT-CFGPANEL-1）：draft.apiKey 永远是空串起步——
  // 服务端不下发密钥，编辑框没有可回填的旧值，用户要么留空（不改）要么填新的
  const [agentModel, setAgentModel] = useState<AgentModelConfig | null>(null)
  const [agentModelError, setAgentModelError] = useState('')
  const [agentDraft, setAgentDraft] = useState({ baseUrl: '', modelName: '', apiKey: '' })
  const [agentEditOpen, setAgentEditOpen] = useState(false)
  const [agentBusy, setAgentBusy] = useState(false)

  // ---------- K8s 运维治理（REQ-K8S-OPS-BOARD-1）----------
  // 双区块：业务总览 / K8s 运维一站式
  const [board, setBoard] = useState('overview')
  // 残留 Pod 清理：先 dryRun 拿清单，二次确认后真删
  const [ghostModal, setGhostModal] = useState(false)
  const [ghostList, setGhostList] = useState<any[]>([])
  const [ghostBusy, setGhostBusy] = useState(false)
  // SeaTunnel 制品恢复：后端不跑 shell，返回操作单+疑似清单
  const [seatunnelTicket, setSeatunnelTicket] = useState<any>(null)
  const [seatunnelBusy, setSeatunnelBusy] = useState(false)
  // Registry 旧 tag 清理：预览 -> 执行
  const [gcPreview, setGcPreview] = useState<any>(null)
  const [gcBusy, setGcBusy] = useState(false)
  const [gcRunBusy, setGcRunBusy] = useState(false)

  // 内存右调建议器（REQ-SYS-RIGHTSIZE-1）：数据随 15 秒主轮询走；应用后额外补拉两次看收敛
  const [rs, setRs] = useState<RightSizeData | null>(null)
  const [rsError, setRsError] = useState('')
  const [rsBusy, setRsBusy] = useState('')
  const [rsPlan, setRsPlan] = useState<ApplyPlan | null>(null)
  const [rsPlanBusy, setRsPlanBusy] = useState(false)
  const [svcTab, setSvcTab] = useState('workloads')

  const load = async () => {
    setLoading(true)
    try {
      setData(await api<Overview>('/api/system/overview'))
      setError('')
      await loadServices()
      // 限额里的「当前在跑几条」是实时值，跟着 15 秒轮询一起刷；
      // 用户的草稿是另一份 state，不会被这里的回显冲掉
      void loadQueryLimits()
      void loadRightsizing()
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载系统监控失败')
    } finally {
      setLoading(false)
    }
  }

  /** 服务资源单独轮询：滚动/扩缩容期间「未就绪 x/y」自动更新，不用刷新页面。 */
  const loadServices = async () => {
    try {
      api<any>('/api/flink/cluster')
        .then((v) => { setFlinkCluster(v); setFlinkError('') })
        .catch((e) => {
          setFlinkCluster(null)
          setFlinkError(e instanceof Error ? e.message : String(e))
        })
      const res = await api<{ services: ServiceRow[]; usageAvailable: boolean }>(
        '/api/system/services')
      setServices(res.services || [])
      setUsageAvailable(Boolean(res.usageAvailable))
      setServicesAt(new Date().toLocaleTimeString())
      setServicesError('')
    } catch (e) {
      setServices([])
      setServicesError(e instanceof Error ? e.message : String(e))
    }
  }

  // ---------- 内存右调建议器 ----------
  const rsKey = (r: { namespace: string; kind: string; name: string }) =>
    `${r.namespace}/${r.kind}/${r.name}`

  const loadRightsizing = async () => {
    if (!can('system:manage')) return
    try {
      setRs(await api<RightSizeData>('/api/system/rightsizing'))
      setRsError('')
    } catch (e) {
      setRsError(e instanceof Error ? e.message : String(e))
    }
  }

  /** 建议是否等于现值：等值就不必滚动重启，按钮标「无需调整」。 */
  const rsUnchanged = (c: RightSizeContainer) =>
    c.dataAvailable && c.currentRequestBytes === c.suggestedRequestBytes
      && c.currentLimitBytes === c.suggestedLimitBytes

  const rsPreview = async (row: RightSizeRow) => {
    setRsBusy(rsKey(row))
    try {
      // 先看 dryRun 账（硬闸数字 + 联动改写计划），确认才真应用——与节点驱逐同一套安全动线
      setRsPlan(await post<ApplyPlan>('/api/system/rightsizing/apply',
        { namespace: row.namespace, kind: row.kind, name: row.name, dryRun: true }))
    } catch (e: any) {
      message.error(e?.message || '右调预演失败')
    } finally {
      setRsBusy('')
    }
  }

  const rsApply = async () => {
    if (!rsPlan) return
    setRsPlanBusy(true)
    try {
      await post<ApplyPlan>('/api/system/rightsizing/apply',
        { namespace: rsPlan.namespace, kind: rsPlan.kind, name: rsPlan.name, dryRun: false })
      message.success(`${rsPlan.name} 已应用建议（${rsPlan.containers.map((c) =>
        `${c.container}: ${c.oldRequest}/${c.oldLimit} → ${c.newRequest}/${c.newLimit}`).join('；')}），滚动更新进行中`)
      setRsPlan(null)
      // 15 秒内数字必须收敛：立刻 + 5s + 15s 各拉一次（主轮询 15s 也兜底）
      void loadRightsizing()
      window.setTimeout(() => void loadRightsizing(), 5000)
      window.setTimeout(() => void loadRightsizing(), 15000)
    } catch (e: any) {
      message.error(e?.message || '应用建议失败')
    } finally {
      setRsPlanBusy(false)
    }
  }

  const toggle = async (row: ServiceRow, action: 'stop' | 'start') => {
    setBusy(`${row.kind}/${row.name}`)
    try {
      await post(`/api/system/services/${row.namespace}/${row.kind}/${row.name}/${action}`, {})
      message.success(action === 'stop'
        ? `已停止 ${row.name}，内存将在一个副本周期内释放`
        : `已启动 ${row.name}`)
      await load()
    } catch (e: any) {
      message.error(e?.message || (action === 'stop' ? '停止失败' : '启动失败'))
    } finally {
      setBusy('')
    }
  };

  const setReplicas = async (row: ServiceRow) => {
    const key = `${row.kind}/${row.name}`
    const value = replicaDraft[key]
    if (value === undefined || value === null) {
      message.warning('请先填副本数')
      return
    }
    setBusy(key)
    try {
      await post(`/api/system/services/${row.namespace}/${key}/replicas`, { replicas: value })
      message.success(`${row.name} 副本数已设为 ${value}`)
      await load()
    } catch (e: any) {
      message.error(e?.message || '设置副本数失败')
    } finally {
      setBusy('')
    }
  };

  const setMemory = async (row: ServiceRow) => {
    const key = `${row.kind}/${row.name}`
    const raw = (memoryDraft[key] || '').trim()
    if (!raw) {
      message.warning('请填内存大小，例如 2 或 2.5（单位默认 G）')
      return
    }
    // 单位默认 G：只填数字时按 G 处理；也允许显式写 3072Mi
    const value = /^\d+(\.\d+)?$/.test(raw) ? `${raw}G` : raw
    setBusy(key)
    try {
      await post(`/api/system/services/${row.namespace}/${key}/memory`, { limit: value })
      message.success(`${row.name} 内存上限已设为 ${value}，容器会滚动重启`)
      await load()
    } catch (e: any) {
      message.error(e?.message || '扩容失败')
    } finally {
      setBusy('')
    }
  };

  const removeService = async (row: ServiceRow) => {
    setBusy(`${row.kind}/${row.name}`)
    try {
      // del 调用点（D54）：Popconfirm 说明「删除工作负载本体（不是停用），需要时按清单重建」，
      // 且核心组件（stoppable=false）根本不出现这个按钮
      await del(`/api/system/services/${row.namespace}/${row.kind}/${row.name}`)
      message.success(`已删除 ${row.namespace}/${row.name}`)
      await loadServices()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    } finally {
      setBusy('')
    }
  };

  const loadDorisNodes = async () => {
    try {
      const res = await api<{
        nodes: DorisNodeRow[]; clusterNodeCount: number; joinedNodeCount: number
      }>('/api/system/doris/nodes')
      setDorisNodes(res.nodes || [])
      setDorisMeta({
        clusterNodeCount: res.clusterNodeCount || 0,
        joinedNodeCount: res.joinedNodeCount || 0,
      })
    } catch (e: any) {
      message.error(e?.message || '读取 Doris 节点失败')
    }
  }

  const dorisAction = async (node: DorisNodeRow,
    action: 'join' | 'decommission' | 'cancel-decommission' | 'drop') => {
    setDorisBusy(`${node.name}/${action}`)
    try {
      await post(`/api/system/doris/nodes/${node.name}/${action}`, {})
      const text = {
        join: `已把 ${node.name} 加入 Doris`,
        decommission: `${node.name} 开始退役，tablet 会自动迁到其它 BE`,
        'cancel-decommission': `已取消 ${node.name} 的退役`,
        drop: `${node.name} 已退出 Doris`,
      }[action]
      message.success(text)
      await loadDorisNodes()
      await loadServices()
    } catch (e: any) {
      message.error(e?.message || '操作失败')
    } finally {
      setDorisBusy('')
    }
  }

  const loadReplProgress = async () => {
    try {
      setReplProgress(await api('/api/system/doris/replication-progress'))
    } catch {
      // 进度拿不到不影响主流程
    }
  }

  const openJoin = async () => {
    setJoinOpen(true)
    try {
      setJoinInfo(await api('/api/system/nodes/join-command'))
    } catch (e: any) {
      message.error(e?.message || '读取加入命令失败')
    }
  }

  const nodeAction = async (row: NodeRow,
    action: 'cordon' | 'uncordon' | 'drain' | 'remove') => {
    setNodeBusy(`${row.name}/${action}`)
    try {
      if (action === 'cordon' || action === 'uncordon') {
        await post(`/api/system/nodes/${row.name}/cordon`, { schedulable: action === 'uncordon' })
        message.success(action === 'cordon' ? `已封锁 ${row.name}` : `已解封 ${row.name}`)
      } else if (action === 'drain') {
        // 先 dryRun 看清单，再真驱逐：直接驱逐用户不知道会掉哪些 Pod
        const preview = await post<{ pods: string[]; failed: string[] }>(
          `/api/system/nodes/${row.name}/drain`, { dryRun: true })
        if (!preview.pods?.length) {
          message.info('该节点上没有需要驱逐的业务 Pod（DaemonSet 不驱逐）')
          return
        }
        await post(`/api/system/nodes/${row.name}/drain`, { dryRun: false })
        message.success(`已驱逐 ${preview.pods.length} 个 Pod，它们会在其它节点重建`)
      } else {
        // D54：移除节点＝这台机器退出集群（比驱逐更重），旧实现是 del() 点一下就执行。
        // 先跑一次 drain dryRun 拿「这台节点上还有哪些业务 Pod」，把数字摆进确认框；
        // 还有 Pod 时不允许直接移除，必须先驱逐，否则 Pod 会跟着节点一起没。
        const preview = await post<{ pods?: string[] }>(
          `/api/system/nodes/${row.name}/drain`, { dryRun: true })
        setNodeRemove({ name: row.name, pods: preview?.pods || [] })
        return
      }
      await load()
    } catch (e: any) {
      message.error(e?.message || '操作失败')
    } finally {
      setNodeBusy('')
    }
  }

  /**
   * 真正执行移除节点。
   *
   * <p>{@code del()} 调用点说明（D54 全站口径：要么有二次确认，要么注明低风险）：
   * 这是高危动作，确认在 {@code nodeRemove} 弹窗里——弹窗内容来自
   * {@code POST /api/system/nodes/{n}/drain {dryRun:true}} 的实时预览，
   * 上面还有 Pod 时「直接移除」按钮是禁用状态。</p>
   */
  // del 调用点（D54）：见上方 nodeRemove 弹窗——按钮文案与禁用态都由 dryRun 实时预览决定
  const doRemoveNode = (name: string) => del(`/api/system/nodes/${name}`)

  const confirmRemoveNode = async () => {
    if (!nodeRemove) return
    if (nodeRemove.pods.length > 0) {
      message.warning(`该节点还有 ${nodeRemove.pods.length} 个业务 Pod，请先驱逐`)
      return
    }
    setNodeRemoveBusy(true)
    try {
      await doRemoveNode(nodeRemove.name)
      message.success(`已从集群移除 ${nodeRemove.name}`)
      setNodeRemove(undefined)
      await load()
    } catch (e: any) {
      message.error(e?.message || '移除节点失败')
    } finally {
      setNodeRemoveBusy(false)
    }
  }

  const drainThenRemoveNode = async () => {
    if (!nodeRemove) return
    setNodeRemoveBusy(true)
    try {
      await post(`/api/system/nodes/${nodeRemove.name}/drain`, { dryRun: false })
      await doRemoveNode(nodeRemove.name)
      message.success(`已驱逐 ${nodeRemove.pods.length} 个 Pod，并把 ${nodeRemove.name} 移出集群`)
      setNodeRemove(undefined)
      await load()
    } catch (e: any) {
      message.error(e?.message || '驱逐或移除失败')
    } finally {
      setNodeRemoveBusy(false)
    }
  }

  const scaleCluster = async (c: ClusterInfo) => {
    const value = clusterDraft[c.key] ?? c.scaleReplicas ?? 0
    if (!value) {
      message.warning('请先填副本数')
      return
    }
    setClusterBusy(c.key)
    try {
      await post(`/api/system/clusters/${c.key}/replicas`, { replicas: value })
      message.success(`${c.name} 副本数已设为 ${value}，新节点会在 1-2 分钟内加入集群`)
      await load()
      await loadServices()
    } catch (e: any) {
      message.error(e?.message || '扩缩容失败')
    } finally {
      setClusterBusy('')
    }
  }

  /** 缩容前置：单副本表的 tablet 没有第二份副本，先加到 3 副本才迁得走。 */
  const raiseReplication = async () => {
    setReplBusy(true)
    try {
      const res = await post<{ target: number; changed: string[]; skipped: string[] }>(
        '/api/system/doris/replication', { replicas: 3 })
      const changed = res.changed || []
      message.success(changed.length
        ? `已把 ${changed.length} 张表调到 3 副本，Doris 正在补副本（进度见下方）`
        : '所有表都已经是 3 副本')
      if ((res.skipped || []).length) {
        message.warning(`${res.skipped!.length} 张表没改成功：${res.skipped![0]}`)
      }
      await loadDorisNodes()
      await loadReplProgress()
    } catch (e: any) {
      message.error(e?.message || '调整副本数失败')
    } finally {
      setReplBusy(false)
    }
  }

  /** "0.93G" → "0.93"：输入框默认显示当前每副本上限，刷新后不会空掉。 */
  const gText = (value?: string) => (value && value.endsWith('G') ? value.slice(0, -1) : '')

  /** "1.23G" → 1.23，用于算使用率；解析不了返回 null。 */
  const gb = (value?: string) => {
    const match = /^([\d.]+)G$/.exec(value || '')
    return match ? Number(match[1]) : null
  }

  useEffect(() => {
    load()
    const timer = window.setInterval(load, 15000)
    // 服务资源刷新更勤：状态变化（未就绪/启动中）要能自己动起来
    const serviceTimer = window.setInterval(() => { loadServices() }, 5000)
    return () => {
      window.clearInterval(timer)
      window.clearInterval(serviceTimer)
    }
  }, [])

  // 退役/迁移是滚动过程，弹窗打开期间每 5 秒拉一次节点与副本进度；体检详情只拉一次
  useEffect(() => {
    if (!dorisOpen) return
    loadDorisNodes()
    loadReplProgress()
    api<{ singleReplicaCount: number; detail: string[] }>('/api/system/doris/replication-check')
      .then((r) => setSingleReplica({
        count: r.singleReplicaCount || 0, detail: r.detail || [],
      }))
      .catch(() => setSingleReplica({ count: 0, detail: [] }))
    const timer = window.setInterval(() => {
      loadDorisNodes()
      loadReplProgress()
    }, 5000)
    return () => window.clearInterval(timer)
  }, [dorisOpen])

  // ---------- 查询限额（D45）----------
  // 无 system:manage 时连 GET 都是 403，所以不发请求，界面按只读说明处理
  const canManageLimits = can('system:manage')

  const loadQueryLimits = async () => {
    if (!canManageLimits) return
    try {
      const res = await api<QueryLimitsView>('/api/query-limits')
      setLimitsView(res)
      setLimitsError('')
      // 草稿里已经和服务端一致的项丢掉，避免留着「看着改了其实没改」的错觉
      setLimitsDraft((prev) => {
        const next: Record<string, number> = {}
        for (const key of Object.keys(prev)) {
          if (prev[key] !== res.limits[key]) next[key] = prev[key]
        }
        return next
      })
    } catch (e: any) {
      setLimitsError(e?.message || '读取查询限额失败')
    }
  }

  /** 草稿值合法性：非法先在前端挡一下，同时给原文提示（后端同样会 400，不静默夹边界）。 */
  const limitsFieldError = (f: LimitField): string => {
    const value = limitsDraft[f.key]
    if (value === undefined || value === null) return ''
    if (!Number.isInteger(value)) return `${f.label}必须是整数`
    if (value < f.min || value > f.max) {
      return `${f.label}必须在 ${f.min}-${f.max} ${f.unit}之间，当前填了 ${value}`
    }
    return ''
  }

  const limitsChangedFields = (): LimitField[] => (limitsView?.fields || []).filter((f) => {
    const value = limitsDraft[f.key]
    return value !== undefined && value !== null && value !== limitsView?.limits[f.key]
  })

  const saveQueryLimits = async () => {
    const changed = limitsChangedFields()
    if (!changed.length) {
      message.info('没有需要保存的改动')
      return
    }
    const errors = changed.map(limitsFieldError).filter(Boolean)
    if (errors.length) {
      setLimitsError(errors.join('；'))
      return
    }
    setLimitsBusy(true)
    try {
      // 只提交改动字段（后端语义：null＝这一项不改），没动的项不会被连带覆盖
      const body: Record<string, number> = {}
      for (const f of changed) body[f.key] = limitsDraft[f.key]
      await put('/api/query-limits', body)
      await loadQueryLimits()
      message.success(`已保存：${changed.map((f) => f.label).join('、')}`)
    } catch (e: any) {
      // 越界值后端直接 400，这里要把原文显示出来，不能假装成功
      setLimitsError(e?.message || '保存查询限额失败')
      message.error(e?.message || '保存查询限额失败')
    } finally {
      setLimitsBusy(false)
    }
  }

  const resetQueryLimitsDraft = () => {
    setLimitsDraft({})
    setLimitsError('')
  }

  // ---------- K8s 运维治理动作 ----------

  /** 残留 Pod 清理：先 dryRun 拉清单弹窗，用户二次确认后再真删（带审计）。 */
  const previewGhostPods = async () => {
    setGhostBusy(true)
    try {
      const res = await post<{ ghosts: any[] }>('/api/system/k8s/ghost-pods/cleanup', { dryRun: true })
      setGhostList(res.ghosts || [])
      setGhostModal(true)
    } catch (e: any) {
      message.error(e?.message || '扫描残留 Pod 失败')
    } finally {
      setGhostBusy(false)
    }
  }

  const doCleanGhostPods = async () => {
    setGhostBusy(true)
    try {
      const res = await post<{ deletedCount: number; failed: string[] }>(
        '/api/system/k8s/ghost-pods/cleanup', { dryRun: false })
      const failed = res.failed || []
      message.success(`已清理 ${res.deletedCount ?? 0} 个残留 Pod${failed.length ? `，失败 ${failed.length}` : ''}`)
      setGhostModal(false)
      await load()
    } catch (e: any) {
      message.error(e?.message || '清理残留 Pod 失败')
    } finally {
      setGhostBusy(false)
    }
  }

  /** SeaTunnel 制品恢复：调 ops 等价链，返回操作单（后端不跑 shell）。 */
  const loadSeatunnelTicket = async () => {
    setSeatunnelBusy(true)
    try {
      const res = await post<any>('/api/system/k8s/seatunnel-artifacts/restore', {})
      setSeatunnelTicket(res)
    } catch (e: any) {
      message.error(e?.message || '读取 SeaTunnel 恢复操作单失败')
    } finally {
      setSeatunnelBusy(false)
    }
  }

  /** Registry 旧 tag 清理：预览 -> 执行（保留最近 10 版 + 在用 digest）。 */
  const previewRegistryGc = async () => {
    setGcBusy(true)
    try {
      setGcPreview(await post<any>('/api/system/k8s/registry/gc-preview', {}))
    } catch (e: any) {
      message.error(e?.message || 'Registry 清理预览失败')
    } finally {
      setGcBusy(false)
    }
  }

  const runRegistryGc = async () => {
    setGcRunBusy(true)
    try {
      const res = await post<{ deletedCount: number; failed: string[]; gcNote?: string }>(
        '/api/system/k8s/registry/gc-run', { confirm: true })
      message.success(`已删除 ${res.deletedCount ?? 0} 个旧 tag`)
      if (res.gcNote) message.info(res.gcNote)
      setGcPreview(await post<any>('/api/system/k8s/registry/gc-preview', {}))
    } catch (e: any) {
      message.error(e?.message || 'Registry 清理执行失败')
    } finally {
      setGcRunBusy(false)
    }
  }

  // ---------- 对话智能体（REQ-AGENT-CFGPANEL-1）----------
  // 读数只走 GET /api/agent/model-config（agent-service 同源快照）；页面不存第二份配置。
  // 健康灯与对话冒烟同因：灯红=端点/密钥探测不过，同一组值正是对话要用的。
  const canAgentModel = can('agent:config')

  const loadAgentModel = async () => {
    if (!canAgentModel) return
    try {
      setAgentModel(await api<AgentModelConfig>('/api/agent/model-config'))
      setAgentModelError('')
    } catch (e: any) {
      setAgentModelError(e?.message || '读取对话智能体配置失败')
    }
  }

  useEffect(() => {
    void loadAgentModel()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canAgentModel])

  const openAgentEdit = () => {
    // 回填端点/模型名（非敏感），密钥框永远留空：没有「旧值」可显示
    setAgentDraft({
      baseUrl: agentModel?.baseUrl || '',
      modelName: agentModel?.modelName || '',
      apiKey: '',
    })
    setAgentEditOpen(true)
  }

  const saveAgentModel = async () => {
    setAgentBusy(true)
    try {
      // 只提交真改了的字段（后端语义：null＝这一项不改）；apiKey 空串=不改、
      // 非空=替换整钥，清除密钥不在页面做（防手滑清 key 把全平台对话打死）
      const body: Record<string, string> = {}
      if (agentDraft.baseUrl !== (agentModel?.baseUrl || '')) body.baseUrl = agentDraft.baseUrl
      if (agentDraft.modelName !== (agentModel?.modelName || '')) body.modelName = agentDraft.modelName
      if (agentDraft.apiKey) body.apiKey = agentDraft.apiKey
      if (!Object.keys(body).length) {
        message.info('没有要保存的改动')
        return
      }
      await put('/api/agent/model-config', body)
      setAgentEditOpen(false)
      await loadAgentModel()  // 保存后强制重探：灯红/绿立刻反映新配置真实可用性
      message.success('已生效（进程内即时，无需重启服务）')
    } catch (e: any) {
      // 上游 400（非法端点/非法模型名/含空白密钥）原文显示，不吞
      message.error(e?.message || '保存对话智能体配置失败')
    } finally {
      setAgentBusy(false)
    }
  }

  const s = data?.summary
  const grafana = `${location.protocol}//${location.hostname}:30092`
  const prometheus = `${location.protocol}//${location.hostname}:30091`

  const SERVICE_COLUMNS: ColumnsType<ServiceRow> = [
          {
            title: '服务', dataIndex: 'name', width: 200,
            render: (v: string, r) => (
              <Space size={4} wrap>
                <span>
                  {v}
                  <Typography.Text type="secondary" style={{ fontSize: 11, marginLeft: 4 }}>
                    {r.namespace}
                  </Typography.Text>
                </span>
                {r.stopped ? <Tag color="default">已停止</Tag> : null}
                {!r.stopped && r.ready < r.replicas
                  ? <Tag color="orange">未就绪 {r.ready}/{r.replicas}</Tag> : null}
              </Space>
            ),
          },
          {
            title: '副本', width: 80,
            render: (_: unknown, r) => (
              <Tooltip title={r.kind === 'statefulset' ? 'StatefulSet' : 'Deployment'}>
                <span>{r.ready}/{r.replicas}</span>
              </Tooltip>
            ),
          },
          {
            title: '内存（用量 / 总配额）', dataIndex: 'memoryUsage', width: 200,
            render: (v: string, r) => {
              const used = gb(v)
              // 用量是全部副本之和，配额也用「每副本 × 副本数」比较
              const limit = gb(r.memoryLimitTotal)
              if (used === null || !limit) {
                return <span>{v}</span>
              }
              const percent = Math.min(100, Math.round((used / limit) * 100))
              const color = percent >= 80 ? '#f53f3f' : percent >= 60 ? '#faad14' : '#00b42a'
              return (
                <Space direction="vertical" size={0} style={{ width: '100%' }}>
                  <span title={`每副本 request ${r.memoryRequest} / limit ${r.memoryLimit}`}>
                    {`${v} / ${r.memoryLimitTotal}`}
                  </span>
                  <Progress percent={percent} size="small" strokeColor={color}
                    showInfo={false} />
                </Space>
              )
            },
          },

          {
            title: '副本设置', width: 120,
            render: (_: unknown, r) => {
              if (r.kind === 'daemonset') {
                return (
                  <Space size={4} wrap>
                    <Tooltip title="DaemonSet 每个节点运行 1 个实例，副本数跟随节点数。
扩容/缩容的对象是 K8s 节点（加机器或调整 nodeSelector），不是 Pod 副本。">
                      <Typography.Text type="secondary">每节点 1 个</Typography.Text>
                    </Tooltip>
                    {r.name === 'doris-be' ? (
                      <Button size="small" type="link" style={{ padding: 0 }}
                        onClick={() => setDorisOpen(true)}>节点管理</Button>
                    ) : null}
                  </Space>
                )
              }
              const key = `${r.kind}/${r.name}`
              return (
                <Space size={4}>
                  <InputNumber
                    size="small"
                    min={0}
                    max={20}
                    style={{ width: 62 }}
                    value={replicaDraft[key] ?? r.replicas}
                    onChange={(v) => setReplicaDraft({ ...replicaDraft, [key]: Number(v ?? 0) })}
                  />
                  <Button size="small" loading={busy === key}
                    onClick={() => setReplicas(r)}>应用</Button>
                </Space>
              )
            },
          },
          {
            title: '内存设置（每副本）', width: 160,
            render: (_: unknown, r) => {
              const key = `${r.kind}/${r.name}`
              return (
                <Space size={4}>
                  <Input
                    size="small"
                    style={{ width: 70 }}
                    placeholder="每副本，如 2.5"
                    value={memoryDraft[key] ?? gText(r.memoryLimit)}
                    onChange={(e) => setMemoryDraft({ ...memoryDraft, [key]: e.target.value })}
                  />
                  <Button size="small" loading={busy === key}
                    onClick={() => setMemory(r)}>应用</Button>
                </Space>
              )
            },
          },
          {
            title: '启停 / 删除', width: 170,
            render: (_: unknown, r) => {
              if (!r.stoppable) {
                return (
                  <Tooltip title={r.stopBlockedReason || '核心组件，停用会影响平台'}>
                    <Tag color="default">核心组件</Tag>
                  </Tooltip>
                )
              }
              const key = `${r.kind}/${r.name}`
              return (
                <Space size={4}>
                  {r.stopped ? (
                    <Button size="small" type="primary" ghost loading={busy === key}
                      onClick={() => toggle(r, 'start')}>启动</Button>
                  ) : (
                    <Popconfirm
                      title={`停止服务「${r.name}」？`}
                      description="会释放它占用的内存；启动后恢复原副本数"
                      okText="停止"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => toggle(r, 'stop')}
                    >
                      <Button size="small" danger loading={busy === key}>停止</Button>
                    </Popconfirm>
                  )}
                  <Popconfirm
                    title={`删除工作负载「${r.namespace}/${r.name}」？`}
                    description="删除工作负载本体（不是停用），需要时按清单重建"
                    okText="删除"
                    okButtonProps={{ danger: true }}
                    onConfirm={() => removeService(r)}
                  >
                    <Button size="small" type="text" danger loading={busy === key}>删除</Button>
                  </Popconfirm>
                </Space>
              )
            },
          },
  ]

  const namespaceOptions = Array.from(new Set(services.map((s) => s.namespace))).sort()
  // ---------- 内存右调建议页签（REQ-SYS-RIGHTSIZE-1）----------
  // 行=容器：现值/7日P95/进程声明/建议一屏对账；rsDiffCount=「有实测且建议≠现值」的容器数
  const rsItems = (rs?.rows || []).flatMap((r) => r.containers.map((c) => ({ row: r, c })))
  const rsDiffCount = rsItems.filter(({ c }) => c.dataAvailable && !rsUnchanged(c)).length
  const rsInvertCount = rsItems.filter(({ c }) => c.inversion).length

  const rsTabChildren = (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {rsError && (
        <ErrorState compact title="「右调建议」加载失败" message={rsError} onRetry={loadRightsizing} />
      )}
      {rs && (
        <Alert
          type="info"
          showIcon
          message={`建议口径：近 ${rs.windowDays} 日 working_set P95，request×1.3 / limit×2，`
            + `${rs.stepMi}Mi 步向上取整；limit 不低于进程声明换算下限。${rsInvertCount > 0
              ? `当前检出 ${rsInvertCount} 处「limit < 进程申请」倒挂。` : '未检出倒挂。'}`}
          description={`生成于 ${new Date(rs.generatedAt).toLocaleTimeString()}；`
            + `节点 allocatable ${rs.allocatable.total || '-'}，已申请 ${rs.allocatable.usedRequest || '-'}；`
            + `配额 ${Object.values(rs.quota || {}).map((q) =>
              `requests.memory ${q.requestsMemoryUsed}/${q.requestsMemoryHard}`).join('，') || '无'}。`
            + '只出建议不自动改：应用 = 人点一下，硬闸（配额/节点余量/倒挂）由服务端统一拦。'}
        />
      )}
      <Table
        rowKey={({ row, c }) => `${row.namespace}/${row.kind}/${row.name}|${c.container}`}
        size="small"
        loading={!rs && !rsError}
        dataSource={rsItems}
        locale={{ emptyText: tableEmpty('暂无建议数据', '后端聚合 Prometheus 7 日实测与集群配置；点「刷新」重试') }}
        pagination={{ pageSize: 15, showSizeChanger: false,
          showTotal: (t) => `${t} 个容器，${rsDiffCount} 个可调` }}
        scroll={{ x: 1080 }}
        columns={[
          {
            title: '服务', dataIndex: ['row', 'name'], width: 200,
            render: (_: string, { row }) => (
              <Space direction="vertical" size={0}>
                <Space size={4}>
                  <Tag style={{ fontSize: 11 }}>{row.kind}</Tag>
                  <span>{row.name}</span>
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  {row.namespace} · {row.replicas} 副本
                </Typography.Text>
              </Space>
            ),
          },
          { title: '容器', width: 110, render: (_: unknown, { c }) => c.container },
          {
            title: '现值 申请/上限', width: 150,
            render: (_: unknown, { c }) => (
              <Typography.Text type={c.inversion ? 'danger' : undefined} style={{ fontSize: 12 }}>
                {c.currentRequest} / {c.currentLimit}
              </Typography.Text>
            ),
          },
          {
            title: `近${rs?.windowDays || 7}日 P95`, width: 120,
            render: (_: unknown, { c }) => (c.dataAvailable
              ? (
                  <Tooltip title={`按 Pod 计 ${c.podSamples} 个样本序列（7 日窗口内出现过数据）`}>
                    <span>{c.p95}</span>
                  </Tooltip>
                )
              : <Tag>样本不足</Tag>),
          },
          {
            title: '进程声明', width: 130,
            render: (_: unknown, { c }) => (c.declared
              ? (
                  <Tooltip title={c.declaredSource || ''}>
                    <Typography.Text style={{ fontSize: 12 }}>
                      {c.declared} {c.declaredKind === 'XMX' ? '(-Xmx)' : '(process.size)'}
                    </Typography.Text>
                  </Tooltip>
                )
              : <Typography.Text type="secondary">无</Typography.Text>),
          },
          {
            title: '建议 申请/上限', width: 160,
            render: (_: unknown, { c }) => (c.dataAvailable
              ? (
                  <Tooltip title={c.reason}>
                    <Typography.Text strong style={{ fontSize: 12 }}>
                      {c.suggestedRequest} / {c.suggestedLimit}
                    </Typography.Text>
                  </Tooltip>
                )
              : <Typography.Text type="secondary">—</Typography.Text>),
          },
          {
            title: '状态', width: 90,
            render: (_: unknown, { c }) => {
              if (c.inversion) {
                return (
                  <Tooltip title={`现 limit ${c.currentLimit} 低于进程声明换算下限，属倒挂，建议值已抬底`}>
                    <Tag color="red">倒挂</Tag>
                  </Tooltip>
                )
              }
              if (rsUnchanged(c)) return <Tag color="green">无需调整</Tag>
              if (!c.dataAvailable) return <Tag>样本不足</Tag>
              return null
            },
          },
          {
            title: '操作', width: 110,
            render: (_: unknown, { row, c }) => (
              <Button
                size="small"
                type="primary"
                ghost
                disabled={!c.dataAvailable || rsUnchanged(c) || !row.applyable || busy !== ''}
                loading={rsBusy === `${row.namespace}/${row.kind}/${row.name}`}
                onClick={() => void rsPreview(row)}
              >
                预览应用
              </Button>
            ),
          },
        ] as ColumnsType<{ row: RightSizeRow; c: RightSizeContainer }>}
      />
    </Space>
  )

  const rsModal = (
    <Modal
      open={!!rsPlan}
      title={`应用右调建议：${rsPlan?.namespace || ''}/${rsPlan?.name || ''}`}
      onCancel={() => setRsPlan(null)}
      okText="确认应用（会滚动更新）"
      cancelText="取消"
      onOk={() => void rsApply()}
      confirmLoading={rsPlanBusy}
      width={640}
    >
      {rsPlan && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Table
            rowKey="container"
            size="small"
            pagination={false}
            dataSource={rsPlan.containers}
            columns={[
              { title: '容器', dataIndex: 'container' },
              { title: '现 申请/上限', render: (_: unknown, r) => `${r.oldRequest} / ${r.oldLimit}` },
              {
                title: '应用后',
                render: (_: unknown, r) => (
                  <Typography.Text strong>{r.newRequest} / {r.newLimit}</Typography.Text>
                ),
              },
              { title: '进程声明', dataIndex: 'declared', render: (v: string | null) => v || '无' },
            ]}
          />
          {rsPlan.rewritePlan.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message="同一动作联动改写进程内存（防 limit < 进程申请 倒挂）"
              description={<ul style={{ margin: 0, paddingLeft: 18 }}>
                {rsPlan.rewritePlan.map((t) => <li key={t}>{t}</li>)}
              </ul>}
            />
          )}
          <div>
            <Typography.Text strong>应用前硬闸：</Typography.Text>
            <ul style={{ margin: '4px 0', paddingLeft: 18 }}>
              {rsPlan.gates.map((t) => <li key={t}><Typography.Text style={{ fontSize: 12 }}>{t}</Typography.Text></li>)}
            </ul>
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            确认后 patch requests/limits 并触发滚动更新；数字在 15 秒内随轮询收敛。
          </Typography.Text>
        </Space>
      )}
    </Modal>
  )
  const shownServices = nsFilter ? services.filter((s) => s.namespace === nsFilter) : services
  const coreServices = shownServices.filter((s) => !s.stoppable)
  const otherServices = shownServices.filter((s) => s.stoppable)

  return (
    <div>
      <PageHeader
        title="系统监控"
        description="总览＝业务告警、Flink 会话集群与查询限额；节点、工作负载与 K8s 治理动作见「K8s 运维」板块。每 15 秒自动刷新，详细历史曲线见 Grafana。"
        extra={(
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
            <Button href={grafana} target="_blank">Grafana</Button>
            <Button href={prometheus} target="_blank">Prometheus</Button>
          </Space>
        )}
      />

      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} /> : null}

      <Tabs
        activeKey={board}
        onChange={setBoard}
        items={[
          {
            key: 'overview',
            label: '总览',
            children: (
              <div data-board="overview">
          <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
            <Col xs={12} md={8} xl={4}>
              <Card>
                <Statistic title="节点就绪" value={s ? `${s.nodesReady}/${s.nodesTotal}` : '-'} />
              </Card>
            </Col>
            <Col xs={12} md={8} xl={4}>
              <Card>
                <Statistic title="Pod 运行中" value={s?.podsRunning ?? '-'} />
              </Card>
            </Col>
            <Col xs={12} md={8} xl={4}>
              <Card>
                <Statistic
                  title="异常 Pod"
                  value={s?.podsAbnormal ?? '-'}
                  valueStyle={s && s.podsAbnormal > 0 ? { color: '#cf1322' } : undefined}
                />
              </Card>
            </Col>
            <Col xs={12} md={8} xl={4}>
              <Card>
                <Statistic
                  title="副本不足的工作负载"
                  value={s?.deploymentsUnavailable ?? '-'}
                  valueStyle={s && s.deploymentsUnavailable > 0 ? { color: '#d46b08' } : undefined}
                />
              </Card>
            </Col>
            <Col xs={12} md={8} xl={4}>
              <Card>
                <Statistic
                  title="最高磁盘使用率"
                  value={s?.maxDiskPercent ?? '-'}
                  suffix={s?.maxDiskPercent !== null && s?.maxDiskPercent !== undefined ? '%' : ''}
                  valueStyle={s && (s.maxDiskPercent ?? 0) >= 85 ? { color: '#cf1322' } : undefined}
                />
                <Typography.Text type="secondary">{s?.maxDiskNode || '-'}</Typography.Text>
              </Card>
            </Col>
            <Col xs={12} md={8} xl={4}>
              <Card
                hoverable
                onClick={() => nav('/ops/alerts')}
                title={<span style={{ fontSize: 12, color: 'var(--dw-text-3)', fontWeight: 400 }}>未关闭告警</span>}
              >
                <Statistic
                  value={s?.alertsOpen ?? '-'}
                  valueStyle={s && s.alertsOpen > 0 ? { color: '#cf1322' } : undefined}
                />
                <Typography.Text type="secondary">系统告警 {s?.systemAlertsOpen ?? 0} · 点击查看</Typography.Text>
              </Card>
            </Col>
          </Row>

          <Card
            title="Flink 集群"
            size="small"
            style={{ marginBottom: 16 }}
            extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
              流开发会话集群（JobManager + TaskManager）
            </Typography.Text>}
          >
            <Row gutter={16}>
              <Col xs={12} md={4}><Statistic title="TaskManager" value={flinkCluster?.taskmanagers ?? 0} /></Col>
              <Col xs={12} md={4}><Statistic title="Slot 总量" value={flinkCluster?.slotsTotal ?? 0} /></Col>
              <Col xs={12} md={4}><Statistic title="空闲 Slot" value={flinkCluster?.slotsAvailable ?? 0} /></Col>
              <Col xs={12} md={4}><Statistic title="运行中作业" value={flinkCluster?.jobsRunning ?? 0} /></Col>
              <Col xs={12} md={4}><Statistic title="已完成" value={flinkCluster?.jobsFinished ?? 0} /></Col>
              <Col xs={12} md={4}><Statistic title="失败/取消" value={(flinkCluster?.jobsFailed ?? 0) + (flinkCluster?.jobsCancelled ?? 0)} /></Col>
            </Row>
            {flinkError && (
              <ErrorState compact title="「Flink 集群」加载失败" message={flinkError} onRetry={loadServices} />
            )}
            {flinkCluster?.workers?.length ? (
              <Table
                style={{ marginTop: 12 }}
                size="small" pagination={false} rowKey="id"
                dataSource={flinkCluster.workers}
                locale={{ emptyText: tableEmpty('没有 TaskManager', 'Flink 会话集群拉起后由 JobManager 自动上报') }}
                columns={[
                  { title: 'TaskManager', dataIndex: 'id', key: 'id',
                    render: (v: string) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text> },
                  { title: 'Slot 使用', key: 'slots', width: 120,
                    render: (_: any, w: any) => `${w.slots - w.freeSlots} / ${w.slots}` },
                  { title: 'CPU 核', dataIndex: 'cpuCores', key: 'cpuCores', width: 90 },
                  { title: '物理内存', key: 'memory', width: 120,
                    render: (_: any, w: any) => `${(w.memory / 1024 / 1024 / 1024).toFixed(1)} GB` },
                ]}
              />
            ) : null}
            {flinkCluster?.jobs?.length ? (
              <Table
                style={{ marginTop: 12 }}
                size="small" pagination={false} rowKey="id"
                dataSource={flinkCluster.jobs}
                locale={{ emptyText: tableEmpty('没有作业记录', '在「流开发」提交作业后显示') }}
                columns={[
                  { title: '作业', dataIndex: 'name', key: 'name', ellipsis: true,
                    render: (v: string) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text> },
                  { title: '状态', dataIndex: 'state', key: 'state', width: 120,
                    render: (v: string) => (
                      <Tag color={v === 'RUNNING' ? 'processing'
                        : v === 'FAILED' ? 'red'
                          : v === 'FINISHED' ? 'green' : 'default'}>{v}</Tag>
                    ) },
                  { title: 'Job ID', dataIndex: 'id', key: 'id', width: 300,
                    render: (v: string) => <Typography.Text copyable style={{ fontSize: 12 }}>{v}</Typography.Text> },
                  { title: '开始时间', dataIndex: 'startTime', key: 'startTime', width: 170,
                    render: (v: number) => (v ? new Date(v).toLocaleString() : '-') },
                  { title: '运行时长', dataIndex: 'duration', key: 'duration', width: 110,
                    render: (v: number) => {
                      if (!v) return '-'
                      const m = Math.floor(v / 60000)
                      return m >= 60 ? `${Math.floor(m / 60)}h${m % 60}m` : `${m}m`
                    } },
                ]}
              />
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                当前集群没有作业（流开发的作业跑在这个会话集群上）
              </Typography.Text>
            )}
          </Card>

          <Card
            title="查询限额"
            size="small"
            style={{ marginBottom: 16 }}
            extra={(
              <Space size={8} wrap>
                {limitsView?.fields?.length ? (
                  <Tag color={(limitsView.inflight ?? 0) >= (limitsView.limits?.maxConcurrency ?? 0)
                    ? 'red' : (limitsView.inflight ?? 0) > 0 ? 'processing' : 'default'}>
                    正在跑 {limitsView.inflight ?? 0} 条 / 并发上限 {limitsView.limits?.maxConcurrency ?? '-'}
                  </Tag>
                ) : null}
                {canManageLimits ? (
                  <Button size="small" icon={<ReloadOutlined />} onClick={loadQueryLimits}>重新读取</Button>
                ) : null}
              </Space>
            )}
          >
            {!canManageLimits ? (
              <Alert
                type="info"
                showIcon
                message="只读"
                description="查询限额决定别人能跑多大的查询，只有「系统管理」（system:manage）权限可以查看和修改；需要调整请联系运维或平台管理员。"
              />
            ) : limitsError && !limitsView ? (
              <Alert type="error" showIcon message={limitsError} />
            ) : !limitsView ? (
              <Typography.Text type="secondary">读取中…</Typography.Text>
            ) : !limitsView.fields?.length ? (
              // 前端可能先于后端发版：没有字段契约就不渲染输入框，免得让人对着空白表格猜范围
              <Alert
                type="warning"
                showIcon
                message="后端没有返回限额字段契约"
                description="这一版后端的 GET /api/query-limits 还没有 fields（字段含义与合法范围）。界面不猜范围，请升级后端后再配置。"
              />
            ) : (
              <>
                <Table<LimitField>
                  rowKey="key"
                  size="small"
                  pagination={false}
                  dataSource={limitsView.fields}
                  locale={{ emptyText: tableEmpty('没有可配置参数', '后端 GET /api/query-limits 返回 fields 后出现') }}
                  columns={[
                    {
                      title: '项目', dataIndex: 'label', width: 180,
                      render: (v: string, f) => (
                        <Space direction="vertical" size={0}>
                          <span>{v}</span>
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {f.hint}
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    {
                      title: '当前生效值', width: 170,
                      render: (_: unknown, f) => {
                        const invalid = limitsFieldError(f)
                        return (
                          <Space size={6}>
                            <InputNumber
                              size="small"
                              style={{ width: 110 }}
                              // 刻意不设 min/max：antd 会在失焦时把越界值夹到边界，
                              // 那就是「以为设了 99999 其实存了 3600」的老问题；这里保留原值并显式报错
                              value={limitsDraft[f.key] ?? limitsView.limits[f.key]}
                              disabled={!f.enforced}
                              status={invalid ? 'error' : undefined}
                              onChange={(v) => setLimitsDraft({
                                ...limitsDraft,
                                [f.key]: v === null || v === undefined ? 0 : Number(v),
                              })}
                            />
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{f.unit}</Typography.Text>
                          </Space>
                        )
                      },
                    },
                    {
                      title: '合法范围', width: 150,
                      render: (_: unknown, f) => (
                        <Typography.Text style={{ fontSize: 12 }}>
                          {`${f.min} - ${f.max} ${f.unit}`}
                          <br />
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {`默认 ${limitsView.defaults[f.key]}`}
                          </Typography.Text>
                        </Typography.Text>
                      ),
                    },
                    {
                      title: '状态', width: 120,
                      render: (_: unknown, f) => {
                        if (!f.enforced) {
                          return (
                            <Tooltip title="后端没有消费点，改了这个值不会生效；先只读展示">
                              <Tag color="orange">未生效</Tag>
                            </Tooltip>
                          )
                        }
                        if (limitsFieldError(f)) {
                          return <Tag color="red">超出范围</Tag>
                        }
                        const changed = limitsDraft[f.key] !== undefined
                          && limitsDraft[f.key] !== limitsView.limits[f.key]
                        return changed ? <Tag color="blue">待保存</Tag> : <Tag color="default">已生效</Tag>
                      },
                    },
                  ]}
                />
                {limitsError ? (
                  <Alert
                    type="error"
                    showIcon
                    style={{ marginTop: 12 }}
                    message={limitsError}
                    description="保存失败时原值不变；越界值后端会直接拒绝，不会被悄悄夹到边界。"
                  />
                ) : null}
                <Space style={{ marginTop: 12 }}>
                  <Button
                    type="primary"
                    size="small"
                    loading={limitsBusy}
                    disabled={!limitsChangedFields().length}
                    onClick={saveQueryLimits}
                  >
                    保存改动{limitsChangedFields().length ? `（${limitsChangedFields().length}）` : ''}
                  </Button>
                  <Button size="small" disabled={!limitsChangedFields().length}
                    onClick={resetQueryLimitsDraft}>撤销改动</Button>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    租户 {limitsView.tenantId}：改完立即对之后的查询生效，正在跑的查询不受影响。
                  </Typography.Text>
                </Space>
              </>
            )}
          </Card>

              </div>
            ),
          },
          {
            key: 'k8s',
            label: <Space size={4}><CloudServerOutlined />K8s 运维</Space>,
            children: (
              <div data-board="k8s-ops">
                  <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
                    <Col xs={12} md={8} xl={4}>
                      <Card><Statistic title="节点就绪" value={s ? `${s.nodesReady}/${s.nodesTotal}` : '-'} /></Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                      <Card><Statistic title="Pod 运行中" value={s?.podsRunning ?? '-'} /></Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                      <Card>
                        <Statistic title="异常 Pod" value={s?.podsAbnormal ?? '-'}
                          valueStyle={s && s.podsAbnormal > 0 ? { color: '#cf1322' } : undefined} />
                      </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                      <Card>
                        <Statistic title="副本不足的工作负载" value={s?.deploymentsUnavailable ?? '-'}
                          valueStyle={s && s.deploymentsUnavailable > 0 ? { color: '#d46b08' } : undefined} />
                      </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                      <Card>
                        <Statistic title="最高内存水位" value={s?.maxMemoryPercent ?? '-'}
                          suffix={s?.maxMemoryPercent !== null && s?.maxMemoryPercent !== undefined ? '%' : ''}
                          valueStyle={s && (s.maxMemoryPercent ?? 0) >= 85 ? { color: '#cf1322' } : undefined} />
                        <Typography.Text type="secondary">{s?.maxMemoryNode || '-'}</Typography.Text>
                      </Card>
                    </Col>
                    <Col xs={12} md={8} xl={4}>
                      <Card>
                        <Statistic title="最高磁盘使用率" value={s?.maxDiskPercent ?? '-'}
                          suffix={s?.maxDiskPercent !== null && s?.maxDiskPercent !== undefined ? '%' : ''}
                          valueStyle={s && (s.maxDiskPercent ?? 0) >= 85 ? { color: '#cf1322' } : undefined} />
                        <Typography.Text type="secondary">{s?.maxDiskNode || '-'}</Typography.Text>
                      </Card>
                    </Col>
                  </Row>
                  <Card
                    title="治理动作"
                    size="small"
                    style={{ marginBottom: 16 }}
                    extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      写操作一律二次确认并记审计（system:manage）
                    </Typography.Text>}
                  >
                    <Row gutter={[16, 16]}>
                      <Col xs={24} md={8}>
                        <Card size="small" type="inner" title="残留 Pod 清理">
                          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                            清理卡在 Terminating / Failed 的残留 Pod：先扫描出清单，确认后强制删除；运行中的 Pod 不受影响。
                          </Typography.Paragraph>
                          <Button size="small" danger loading={ghostBusy} onClick={previewGhostPods}>扫描残留 Pod</Button>
                        </Card>
                      </Col>
                      <Col xs={24} md={8}>
                        <Card size="small" type="inner" title="SeaTunnel 制品恢复">
                          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                            /tmp 被系统清理后同步 Job 卡 ContainerCreating；生成操作单，执行走 ops 通道（后端不跑 shell）。
                          </Typography.Paragraph>
                          <Button size="small" loading={seatunnelBusy} onClick={loadSeatunnelTicket}>生成操作单</Button>
                        </Card>
                      </Col>
                      <Col xs={24} md={8}>
                        <Card size="small" type="inner" title="Registry 旧 tag 清理">
                          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                            每个仓库保留最近 10 版 + 全部在用 tag/digest；预览候选清单后再执行删除。
                          </Typography.Paragraph>
                          <Space size={4}>
                            <Button size="small" loading={gcBusy} onClick={previewRegistryGc}>预览</Button>
                            <Popconfirm
                              title={"删除 " + (gcPreview?.candidateCount ?? 0) + " 个旧 tag？"}
                              description="只删被新版本挤出的旧 tag 的 manifest 引用；在用 tag/digest 永不动"
                              okText="删除"
                              okButtonProps={{ danger: true }}
                              disabled={!gcPreview?.candidateCount}
                              onConfirm={runRegistryGc}
                            >
                              <Button size="small" danger loading={gcRunBusy} disabled={!gcPreview?.candidateCount}>执行</Button>
                            </Popconfirm>
                          </Space>
                        </Card>
                      </Col>
                    </Row>
                    {gcPreview?.repos?.length ? (
                      <Table
                        style={{ marginTop: 12 }}
                        size="small"
                        rowKey="name"
                        pagination={false}
                        dataSource={(gcPreview.repos || []).filter((r: any) => (r.candidates || []).length)}
                        columns={[
                          { title: '仓库', dataIndex: 'name' },
                          { title: 'tag 总数', dataIndex: 'tagTotal', width: 90 },
                          { title: '将删除', key: 'cand', width: 300,
                            render: (_: unknown, r: any) => (r.candidates || []).map((c: any) => (
                              <Tag key={c.tag}>{c.tag}</Tag>
                            )) },
                          { title: '保留数', dataIndex: 'keptCount', width: 80 },
                        ]}
                      />
                    ) : null}
                  </Card>
                  <Typography.Title level={5} style={{ marginTop: 8, marginBottom: 8 }}>集群健康总览（节点 / 配额 / 异常 Pod）</Typography.Title>
          <Card
            title="节点"
            size="small"
            style={{ marginBottom: 16 }}
            extra={(
              <Button size="small" onClick={openJoin}>新增节点</Button>
            )}
          >
            <Table<NodeRow>
              rowKey="name"
              size="small"
              loading={loading && !data}
              dataSource={data?.nodes || []}
              locale={{ emptyText: tableEmpty('还没有节点', '点右上角「添加节点」把机器加入集群') }}
              pagination={false}
              scroll={{ x: 900 }}
              columns={[
                {
                  title: '节点', dataIndex: 'name', width: 200,
                  render: (v: string, r) => (
                    <Space direction="vertical" size={0}>
                      <Space size={4}>
                        <Tag color={r.ready ? 'green' : 'red'}>{r.ready ? 'Ready' : 'NotReady'}</Tag>
                        <span>{v}</span>
                      </Space>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        {r.internalIp}
                      </Typography.Text>
                    </Space>
                  ),
                },
                {
                  title: 'CPU', width: 140,
                  render: (_: unknown, r) => `${r.cpuPercent?.toFixed(1) ?? '-'}% · ${r.cpuCapacity || '-'} 核`,
                },
                {
                  title: '内存水位（已用 / 总量）', width: 230,
                  render: (_: unknown, r) => (
                    <Space direction="vertical" size={0} style={{ width: '100%' }}>
                      <Space size={6}>
                        <span>{`${r.memoryUsedGb || '-'} / ${r.memoryTotalGb || '-'}`}</span>
                        <Typography.Text
                          type={(r.memoryPercent ?? 0) >= 80 ? 'danger' : 'secondary'}
                          style={{ fontSize: 12 }}
                        >
                          {r.memoryPercent === null || r.memoryPercent === undefined
                            ? '' : `${r.memoryPercent.toFixed(1)}%`}
                        </Typography.Text>
                      </Space>
                      <Progress
                        percent={Math.min(100, Math.round(r.memoryPercent ?? 0))}
                        size="small" showInfo={false}
                        strokeColor={(r.memoryPercent ?? 0) >= 80 ? '#f53f3f'
                          : (r.memoryPercent ?? 0) >= 60 ? '#faad14' : '#00b42a'}
                      />
                    </Space>
                  ),
                },
                {
                  title: '磁盘 可用 / 总量', width: 240,
                  render: (_: unknown, r) => (
                    <Space size={6}>
                      <span>{`${r.diskFreeGb || '-'} / ${r.diskTotalGb || '-'}`}</span>
                      <Typography.Text
                        type={(r.diskPercent ?? 0) >= 80 ? 'danger' : 'secondary'}
                        style={{ fontSize: 12 }}
                      >
                        {r.diskPercent === null || r.diskPercent === undefined
                          ? '' : `已用 ${r.diskPercent.toFixed(1)}%`}
                      </Typography.Text>
                    </Space>
                  ),
                },
                { title: 'Pod 数', dataIndex: 'pods', width: 80 },
                {
                  title: '扩缩容', width: 250,
                  render: (_: unknown, r) => (
                    <Space size={4} wrap>
                      {r.schedulable ? (
                        <Button size="small" loading={nodeBusy === `${r.name}/cordon`}
                          onClick={() => nodeAction(r, 'cordon')}>封锁</Button>
                      ) : (
                        <Button size="small" type="primary" ghost
                          loading={nodeBusy === `${r.name}/uncordon`}
                          onClick={() => nodeAction(r, 'uncordon')}>解封</Button>
                      )}
                      <Tooltip title="驱逐业务 Pod（DaemonSet 不动），让它们到别的节点重建">
                        <Button size="small" danger loading={nodeBusy === `${r.name}/drain`}
                          onClick={() => nodeAction(r, 'drain')}>驱逐</Button>
                      </Tooltip>
                      <Popconfirm
                        title={`从集群移除 ${r.name}？`}
                        description="要求先封锁并驱逐；移除只删 K8s 节点对象，机器还在的话要自己卸 k3s"
                        onConfirm={() => nodeAction(r, 'remove')}
                      >
                        <Button size="small" type="text" danger
                          loading={nodeBusy === `${r.name}/remove`}>移除</Button>
                      </Popconfirm>
                    </Space>
                  ),
                },
              ]}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
              扩容：宿主机层加一台机器 → 点「新增节点」拿到加入命令 → 新机器执行后刷新本页；
              缩容：封锁 → 驱逐 → 移除。全程不会自动删数据。
            </Typography.Text>
          </Card>

          {services.length > 0 && (
            <Card
              title="服务资源"
              size="small"
              style={{ marginBottom: 16 }}
              extra={(
                <Space wrap>
                  <Select
                    size="small"
                    style={{ width: 200 }}
                    value={nsFilter}
                    onChange={setNsFilter}
                    options={[{ value: '', label: '全部命名空间' },
                      ...namespaceOptions.map((ns) => ({ value: ns, label: ns }))]}
                  />
                  {!usageAvailable && (
                    <Typography.Text type="secondary">Prometheus 不可用，仅显示配额</Typography.Text>
                  )}
                  {servicesAt && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      更新于 {servicesAt}
                    </Typography.Text>
                  )}
                </Space>
              )}
            >
              <Tabs
                activeKey={svcTab}
                onChange={setSvcTab}
                items={[
                  {
                    key: 'workloads',
                    label: '工作负载',
                    children: (
                      <>
              {servicesError && (
                <ErrorState compact title="「服务列表」加载失败" message={servicesError} onRetry={loadServices} />
              )}
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 8 }}
                message="「核心组件」= 搭建平台自身需要的组件（dw-dev / dw-spark）"
                description="dw-dev 里的工作负载都属于平台自身（后端、PG、Prefect、SeaTunnel、MinIO、NFS、Doris、Prometheus/Grafana/Alertmanager、Keycloak、Gravitino、Embedding、MCP）；dw-spark 里是常驻的 Spark Thrift Server（driver + 2 executor，SPARK 引擎的运行时）。两者都不可一键停/删；dataworks-test* 里是数据采集用的测试数据源夹具，可以停止或删除来释放内存。"
              />
              <Typography.Text strong>核心组件（{coreServices.length}）</Typography.Text>
              <Table<ServiceRow>
                rowKey={(r) => `${r.namespace}/${r.kind}/${r.name}`}
                size="small"
                style={{ marginTop: 6 }}
                dataSource={coreServices}
                locale={{ emptyText: tableEmpty('没有核心组件', '平台组件随部署产生；点「刷新」重试') }}
                pagination={false}
                scroll={{ x: 870 }}
                columns={SERVICE_COLUMNS}
              />

              <Typography.Text strong style={{ display: 'inline-block', marginTop: 16 }}>
                其他服务（{otherServices.length}）
              </Typography.Text>
              <Table<ServiceRow>
                rowKey={(r) => `${r.namespace}/${r.kind}/${r.name}`}
                size="small"
                style={{ marginTop: 6 }}
                dataSource={otherServices}
                locale={{ emptyText: tableEmpty('没有其他服务', 'dw-dev / dataworks-test 等工作负载会自动出现') }}
                pagination={{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }}
                scroll={{ x: 870 }}
                columns={SERVICE_COLUMNS}
              />
                      </>
                    ),
                  },
                  {
                    key: 'rightsizing',
                    label: `内存右调建议${rsDiffCount > 0 ? `（${rsDiffCount}）` : ''}`,
                    children: rsTabChildren,
                  },
                ]}
              />
            </Card>
          )}
          {rsModal}

          <Card title="异常 Pod" size="small" style={{ marginBottom: 16 }}>
            <Table<PodRow>
              rowKey={(r) => `${r.namespace}/${r.name}`}
              size="small"
              dataSource={data?.abnormalPods || []}
              pagination={false}
              locale={{ emptyText: tableEmpty('没有异常 Pod', '所有 Pod 都处于 Running/Ready 时这里是空的') }}
              columns={[
                { title: 'Pod', dataIndex: 'name' },
                { title: '命名空间', dataIndex: 'namespace', width: 130 },
                { title: '阶段', dataIndex: 'phase', width: 100, render: (v: string) => <Tag color="red">{v}</Tag> },
                { title: '节点', dataIndex: 'node', width: 120 },
                { title: '就绪', dataIndex: 'ready', width: 70, render: (v: boolean) => (v ? '是' : '否') },
                { title: '重启次数', dataIndex: 'restarts', width: 90 },
                { title: '原因', dataIndex: 'reason', width: 220, render: (v: string) => v || '-' },
              ]}
            />
          </Card>

          <Card
            title="对话智能体"
            size="small"
            style={{ marginBottom: 16 }}
            extra={(
              <Space size={8}>
                {agentModel ? (
                  <Tooltip title={agentModel.healthy
                    ? '端点连通、密钥可用；对话冒烟应为绿'
                    : `灯红联动对话冒烟红：${agentModel.healthReason || '原因未知'}`}>
                    <Tag color={agentModel.healthy ? 'green' : 'red'}>
                      {agentModel.healthy ? '可用' : '不可用'}
                    </Tag>
                  </Tooltip>
                ) : null}
                {canAgentModel ? (
                  <Button size="small" icon={<ReloadOutlined />} onClick={loadAgentModel}>重新读取</Button>
                ) : null}
              </Space>
            )}
          >
            {!canAgentModel ? (
              <Alert
                type="info"
                showIcon
                message="只读"
                description="对话智能体的端点/密钥/模型名影响全平台对话，仅平台「配置助手」（agent:config）权限可查看和修改；需要调整请联系运维或平台管理员。"
              />
            ) : agentModelError && !agentModel ? (
              // 读不到也要把故障说出口：这个区块存在的意义就是「坏了页面看得见」
              <Alert
                type="error"
                showIcon
                message={agentModelError}
                description="agent-service 不可达或未部署本功能；对话冒烟（scripts/e2e/agent-chat-smoke-test.py）此时应同红。"
              />
            ) : !agentModel ? (
              <Typography.Text type="secondary">读取中…</Typography.Text>
            ) : (
              <>
                <Descriptions size="small" column={1} colon={false} labelStyle={{ width: 100 }}>
                  <Descriptions.Item label="健康灯">
                    <Space size={6} wrap>
                      <Tag color={agentModel.healthy ? 'green' : 'red'}>
                        {agentModel.healthy ? '绿 · 模型可用' : '红 · 模型不可用'}
                      </Tag>
                      {!agentModel.healthy && agentModel.healthReason ? (
                        <Typography.Text type="danger" style={{ fontSize: 12 }}>
                          {agentModel.healthReason}
                        </Typography.Text>
                      ) : null}
                    </Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="模型端点">
                    <Typography.Text code copyable={{ text: agentModel.baseUrl }}>
                      {agentModel.baseUrl}
                    </Typography.Text>
                  </Descriptions.Item>
                  <Descriptions.Item label="模型名">{agentModel.modelName}</Descriptions.Item>
                  <Descriptions.Item label="API Key">
                    <Space size={6} wrap>
                      {agentModel.keyConfigured ? (
                        <Tag color="green">已配置{agentModel.keyFingerprint ? ` · 尾4位 ${agentModel.keyFingerprint}` : ''}</Tag>
                      ) : (
                        <Tag color="red">未配置 · 对话必挂</Tag>
                      )}
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        平台永不回显密钥明文，只显示指纹
                      </Typography.Text>
                    </Space>
                  </Descriptions.Item>
                  <Descriptions.Item label="服务">
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {agentModel.service || 'agent-service'}（读的是进程当前生效值，改完即时生效不用重启）
                    </Typography.Text>
                  </Descriptions.Item>
                </Descriptions>
                <Space style={{ marginTop: 8 }}>
                  <Button size="small" type="primary" onClick={openAgentEdit}>编辑配置</Button>
                </Space>
              </>
            )}
          </Card>

          <Modal
            title="编辑对话智能体配置"
            open={agentEditOpen}
            onCancel={() => setAgentEditOpen(false)}
            onOk={saveAgentModel}
            okText="保存并生效"
            confirmLoading={agentBusy}
            width={560}
          >
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="影响全平台对话"
              description="保存后 agent-service 进程内立即生效并写回 .env（重启后仍是这份）。端点或密钥填错时健康灯会变红，对话冒烟同步红。"
            />
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <div>
                <Typography.Text strong>模型端点（OpenAI 兼容）</Typography.Text>
                <Input
                  value={agentDraft.baseUrl}
                  placeholder="https://dashscope.aliyuncs.com/compatible-mode/v1"
                  onChange={(e) => setAgentDraft({ ...agentDraft, baseUrl: e.target.value })}
                />
              </div>
              <div>
                <Typography.Text strong>模型名</Typography.Text>
                <Input
                  value={agentDraft.modelName}
                  placeholder="qwen3.8-flash"
                  onChange={(e) => setAgentDraft({ ...agentDraft, modelName: e.target.value })}
                />
              </div>
              <div>
                <Typography.Text strong>API Key（留空=保持不变）</Typography.Text>
                <Input.Password
                  value={agentDraft.apiKey}
                  autoComplete="new-password"
                  placeholder="填入即整体替换；保存后永不再显示原文"
                  onChange={(e) => setAgentDraft({ ...agentDraft, apiKey: e.target.value })}
                />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  当前：{agentModel?.keyConfigured
                    ? `已配置${agentModel.keyFingerprint ? `（尾4位 ${agentModel.keyFingerprint}）` : ''}`
                    : '未配置'}
                </Typography.Text>
              </div>
            </Space>
          </Modal>

          <Typography.Title level={5} style={{ marginTop: 8, marginBottom: 8 }}>
            集群级组件
          </Typography.Title>
          {(data?.clusters || []).map((c) => (
            <Card
              key={c.key}
              size="small"
              style={{ marginBottom: 16 }}
              title={(
                <Space size={8}>
                  <span>{c.name}</span>
                  <Tag color={c.healthy ? 'green' : c.status === '未部署' ? 'default' : 'orange'}>
                    {c.status}
                  </Tag>
                  <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                    {c.role}
                  </Typography.Text>
                </Space>
              )}
              extra={(
                <Space size={10} wrap>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>{c.summary}</Typography.Text>
                  {c.scaleKind ? (
                    <Space size={4}>
                      <InputNumber
                        size="small"
                        min={1}
                        max={20}
                        style={{ width: 62 }}
                        value={clusterDraft[c.key] ?? c.scaleReplicas ?? undefined}
                        onChange={(v) => setClusterDraft({ ...clusterDraft, [c.key]: Number(v ?? 1) })}
                      />
                      <Button size="small" loading={clusterBusy === c.key}
                        onClick={() => scaleCluster(c)}>扩缩容</Button>
                    </Space>
                  ) : (
                    <Tooltip title={c.scaleReason || ''}>
                      <Typography.Text type="secondary" style={{ fontSize: 12, cursor: 'help' }}>
                        不可按副本扩缩
                      </Typography.Text>
                    </Tooltip>
                  )}
                </Space>
              )}
            >
              {c.nodes.length ? (
                <Table<ClusterNode>
                  rowKey="name"
                  size="small"
                  pagination={false}
                  dataSource={c.nodes}
                  locale={{ emptyText: tableEmpty('没有 BE 节点', 'Doris BE 注册后自动出现') }}
                  columns={[
                    {
                      title: '节点', dataIndex: 'name', width: 210,
                      render: (v: string, r) => (
                        <Space direction="vertical" size={0}>
                          <Space size={4}>
                            <Tag color={r.healthy ? 'green' : 'red'}>{r.healthy ? '在线' : r.status}</Tag>
                            <span>{v}</span>
                          </Space>
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            {r.ip}
                          </Typography.Text>
                        </Space>
                      ),
                    },
                    { title: 'CPU', dataIndex: 'cpu', width: 110 },
                    { title: '内存（用量 / 配额）', dataIndex: 'memory', width: 230 },
                    { title: '磁盘', dataIndex: 'disk', width: 240 },
                    { title: '说明', dataIndex: 'extra' },
                  ]}
                />
              ) : (
                <Alert type="info" showIcon message={c.summary} description={c.note} />
              )}
              {c.nodes.length ? (
                <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 6 }}>
                  {c.note}
                </Typography.Text>
              ) : null}
            </Card>
          ))}

          {data ? (
            <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
              数据时间：{new Date(data.generatedAt).toLocaleString()}
            </Typography.Text>
          ) : null}

              </div>
            ),
          },
        ]}
      />
      <Modal
        title="新增 k3s 节点"
        open={joinOpen}
        width={760}
        onCancel={() => setJoinOpen(false)}
        footer={modalCloseBtn(() => setJoinOpen(false))}
      >
        {joinInfo ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert
              type="info"
              showIcon
              message={`集群入口 ${joinInfo.serverUrl}`}
              description="平台不保存节点 token：按下面步骤在 server 上生成一次性 token，再在新机器上执行加入命令。"
            />
            <ol style={{ paddingLeft: 20, margin: 0 }}>
              {joinInfo.steps.map((step) => (
                <li key={step} style={{ marginBottom: 6 }}>{step}</li>
              ))}
            </ol>
            <div>
              <Typography.Text strong>1. 在任一 control-plane 节点生成 token</Typography.Text>
              <Typography.Paragraph copyable code style={{ marginBottom: 0 }}>
                {joinInfo.tokenCommand}
              </Typography.Paragraph>
            </div>
            <div>
              <Typography.Text strong>2. 在新机器上执行</Typography.Text>
              <Typography.Paragraph copyable code style={{ marginBottom: 0 }}>
                {joinInfo.joinCommand}
              </Typography.Paragraph>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{joinInfo.note}</Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary">读取中…</Typography.Text>
        )}
      </Modal>

      <Modal
        title={nodeRemove ? `从集群移除节点：${nodeRemove.name}` : ''}
        open={!!nodeRemove}
        width={620}
        onCancel={() => setNodeRemove(undefined)}
        footer={(
          <Space>
            <Button onClick={() => setNodeRemove(undefined)}>取消</Button>
            {nodeRemove && nodeRemove.pods.length > 0 ? (
              <Button
                type="primary"
                danger
                loading={nodeRemoveBusy}
                onClick={drainThenRemoveNode}
              >
                先驱逐 {nodeRemove.pods.length} 个 Pod，再移除节点
              </Button>
            ) : (
              <Button
                type="primary"
                danger
                loading={nodeRemoveBusy}
                onClick={confirmRemoveNode}
              >
                确认移除节点
              </Button>
            )}
          </Space>
        )}
      >
        {nodeRemove ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert
              type="error"
              showIcon
              message="移除后这台机器会退出集群，上面的工作负载不会自动迁走"
              description={nodeRemove.pods.length
                ? `节点上仍有 ${nodeRemove.pods.length} 个业务 Pod（DaemonSet 不计）。`
                  + '必须先驱逐——把它们赶到其它节点后，这台才可安全移除；否则这些 Pod 会随节点一起消失。'
                : '节点上已没有需要驱逐的业务 Pod，可以直接移除。'
                  + '注意：集群至少要保留一台可调度节点，最后一台移除会被后端拒绝。'}
            />
            {nodeRemove.pods.length ? (
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  将受影响的 Pod（{nodeRemove.pods.length} 个）
                </Typography.Text>
                <div style={{ marginTop: 6, maxHeight: 180, overflow: 'auto' }}>
                  {nodeRemove.pods.map((p) => (
                    <Tag key={p} style={{ marginBottom: 4 }}>{p}</Tag>
                  ))}
                </div>
              </div>
            ) : null}
          </Space>
        ) : null}
      </Modal>

      <Modal
        title="Doris BE 节点（扩 / 缩）"
        open={dorisOpen}
        width={920}
        onCancel={() => setDorisOpen(false)}
        footer={(
          <Space>
            <Popconfirm
              title="把单副本表调到 3 副本？"
              description="只加副本、不删数据；改完 Doris 会自动均衡。表的数据会被各存 3 份。"
              onConfirm={raiseReplication}
            >
              <Button loading={replBusy}>单副本表调到 3 副本</Button>
            </Popconfirm>
            {modalCloseBtn(() => setDorisOpen(false))}
          </Space>
        )}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message={`BE 以 DaemonSet 部署，一节点一实例：集群共 ${dorisMeta.clusterNodeCount} 个 K8s 节点，已加入 Doris ${dorisMeta.joinedNodeCount} 个`}
          description="扩容＝在宿主机层加机器并 join 到 k3s，再点「加入 Doris」；缩容＝先「开始退役」，Doris 会自动把 tablet（包括单副本的）克隆到其它 BE，迁空后点「下掉 BE」。"
        />
        {replProgress ? (
          <div style={{ marginBottom: 12 }}>
            <Space size={8} style={{ marginBottom: 2 }}>
              <Typography.Text strong>副本进度</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {`${replProgress.replicas} / ${replProgress.expectedReplicas} 个副本组 · ${replProgress.tableCount} 张表 · 目标 ${replProgress.target ?? 3} 副本`}
              </Typography.Text>
              {replProgress.complete
                ? <Tag color="green">已完成</Tag>
                : <Tag color="processing">补副本中</Tag>}
            </Space>
            <Progress
              percent={replProgress.percent}
              status={replProgress.complete ? 'success' : 'active'}
              strokeColor={replProgress.complete ? '#00b42a' : undefined}
            />
            {replProgress.tables.filter((t) => t.percent < 100).slice(0, 5).map((t) => (
              <div key={t.name} style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <Typography.Text type="secondary" style={{ fontSize: 12, width: 220 }}>
                  {t.name}
                </Typography.Text>
                <Progress percent={t.percent} size="small" style={{ flex: 1, marginBottom: 0 }} />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {`${t.replicas}/${t.expected}`}
                </Typography.Text>
              </div>
            ))}
          </div>
        ) : null}
        {(replProgress ? replProgress.singleReplicaCount : singleReplica.count) > 0 ? (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message={`${(replProgress ? replProgress.singleReplicaCount : singleReplica.count)} 张表还是单副本：退役时会被克隆到其它 BE，迁移窗口内没有冗余副本`}
            description={`${singleReplica.detail.slice(0, 6).join('、')}${singleReplica.detail.length > 6 ? ' 等' : ''}。迁移期间如果源节点宕机才会丢数据；想让缩容更稳，先把副本调到 3 再退役。`}
          />
        ) : null}
        <Table<DorisNodeRow>
          rowKey="name"
          size="small"
          loading={dorisNodes.length === 0}
          dataSource={dorisNodes}
          locale={{ emptyText: tableEmpty('没有 Doris 节点', 'BE 注册后自动出现；点「刷新」重试') }}
          pagination={false}
          columns={[
            {
              title: '节点', dataIndex: 'name', width: 220,
              render: (v: string, r) => (
                <Space direction="vertical" size={0}>
                  <Space size={4}>
                    <span>{v}</span>
                    {r.role === 'control-plane' ? <Tag>control-plane</Tag> : null}
                  </Space>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {`${r.ip} · ${r.schedulable ? '可调度' : '已封锁'}`}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: 'BE 实例', width: 100,
              render: (_: unknown, r) => (r.bePodRunning
                ? <Tag color="green">运行中</Tag> : <Tag color="default">无</Tag>),
            },
            {
              title: 'Doris 状态', width: 170,
              render: (_: unknown, r) => (
                <Space size={4} wrap>
                  {r.registered ? <Tag color="blue">已注册</Tag> : <Tag color="default">未注册</Tag>}
                  {r.registered ? (r.alive
                    ? <Tag color="green">在线</Tag> : <Tag color="red">心跳异常</Tag>) : null}
                  {r.decommissioned ? <Tag color="orange">退役中</Tag> : null}
                </Space>
              ),
            },
            {
              title: 'Tablet / 数据量', width: 190,
              render: (_: unknown, r) => (
                <Space direction="vertical" size={0}>
                  <span>{`${r.tablets} 个 · ${r.dataUsed}`}</span>
                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                    {r.registered ? `磁盘已用 ${r.usedPct}（共 ${r.capacity}）` : r.note}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: '操作', width: 210,
              render: (_: unknown, r) => {
                const busyKey = (a: string) => dorisBusy === `${r.name}/${a}`
                if (!r.registered) {
                  return (
                    <Button size="small" type="primary" ghost loading={busyKey('join')}
                      onClick={() => dorisAction(r, 'join')}>加入 Doris</Button>
                  )
                }
                if (r.decommissioned) {
                  return (
                    <Space size={4}>
                      <Button size="small" loading={busyKey('cancel-decommission')}
                        onClick={() => dorisAction(r, 'cancel-decommission')}>取消退役</Button>
                      <Tooltip title={r.tablets > 0
                        ? `还有 ${r.tablets} 个 tablet 没迁完，现在下掉会丢数据` : ''}>
                        <Button size="small" danger disabled={r.tablets > 0}
                          loading={busyKey('drop')}
                          onClick={() => dorisAction(r, 'drop')}>下掉 BE</Button>
                      </Tooltip>
                    </Space>
                  )
                }
                return (
                  <Popconfirm
                    title={`${r.name} 开始退役？`}
                    description="Doris 会把该节点上的 tablet 克隆到其它 BE，迁完（tablet 归零）后再点「下掉 BE」"
                    onConfirm={() => dorisAction(r, 'decommission')}
                  >
                    <Button size="small" danger loading={busyKey('decommission')}>开始退役</Button>
                  </Popconfirm>
                )
              },
            },
          ]}
        />
      </Modal>
      <Modal
        title="清理残留 Pod"
        open={ghostModal}
        width={680}
        onCancel={() => setGhostModal(false)}
        footer={(
          <Space>
            <Button onClick={() => setGhostModal(false)}>取消</Button>
            <Popconfirm
              title={"强制删除 " + ghostList.length + " 个残留 Pod？"}
              description="grace=0 立即删除；只删 Terminating/Failed 残留，运行中 Pod 不受影响，动作记审计"
              okText="删除"
              okButtonProps={{ danger: true }}
              disabled={!ghostList.length}
              onConfirm={doCleanGhostPods}
            >
              <Button type="primary" danger loading={ghostBusy} disabled={!ghostList.length}>
                确认清理{ghostList.length ? `（${ghostList.length}）` : ''}
              </Button>
            </Popconfirm>
          </Space>
        )}
      >
        {ghostList.length ? (
          <Table
            size="small" rowKey={(r: any) => `${r.namespace}/${r.name}`} pagination={false}
            dataSource={ghostList}
            columns={[
              { title: '命名空间', dataIndex: 'namespace', width: 130 },
              { title: 'Pod', dataIndex: 'name' },
              { title: '阶段', dataIndex: 'phase', width: 100, render: (v: string) => <Tag color="red">{v}</Tag> },
              { title: 'Terminating', dataIndex: 'terminating', width: 100, render: (v: boolean) => (v ? '是' : '否') },
              { title: '节点', dataIndex: 'node', width: 130 },
            ]}
          />
        ) : (
          <Alert type="success" showIcon message="没有发现残留 Pod，无需清理" />
        )}
      </Modal>
      <Modal
        title={seatunnelTicket?.ticket?.title || 'SeaTunnel 制品恢复'}
        open={!!seatunnelTicket}
        width={760}
        onCancel={() => setSeatunnelTicket(null)}
        footer={<Button onClick={() => setSeatunnelTicket(null)}>关闭</Button>}
      >
        {seatunnelTicket ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert type="info" showIcon message={seatunnelTicket.ticket?.note || ''}
              description="后端不直接执行 shell：请按操作单在运维通道执行；动作已记审计。" />
            {(seatunnelTicket.suspected || []).length ? (
              <Alert type="warning" showIcon
                message={`${seatunnelTicket.suspectedCount} 个 Pod 疑似卡在制品缺失`}
                description={(seatunnelTicket.suspected || []).map((pp: any) =>
                  `${pp.namespace}/${pp.name}（${pp.phase}${pp.reason ? ' · ' + pp.reason : ''}）`).join('；')} />
            ) : (
              <Alert type="success" showIcon message="当前没有疑似卡在制品缺失的 Pod" />
            )}
            <ol style={{ paddingLeft: 20, margin: 0 }}>
              {(seatunnelTicket.ticket?.steps || []).map((st: string) => (
                <li key={st} style={{ marginBottom: 6 }}>{st}</li>
              ))}
            </ol>
            {(seatunnelTicket.ticket?.commands || []).map((cmd: string) => (
              <Typography.Paragraph key={cmd} copyable code style={{ marginBottom: 0 }}>{cmd}</Typography.Paragraph>
            ))}
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>应急手工等价链：</Typography.Text>
            {(seatunnelTicket.ticket?.manual || []).map((m: string) => (
              <Typography.Paragraph key={m} copyable code style={{ marginBottom: 0, fontSize: 12 }}>{m}</Typography.Paragraph>
            ))}
          </Space>
        ) : null}
      </Modal>
    </div>
  )
}
