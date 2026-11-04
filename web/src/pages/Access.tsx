import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, Button, Card, Input, Modal, Popconfirm, Radio, Select, Space, Table, Tabs, Tag,
  Tooltip, Typography, message,
} from 'antd'
import {
  CheckOutlined, CloseOutlined, FileTextOutlined, FolderOpenOutlined, KeyOutlined,
} from '@ant-design/icons'
import { api, post } from '../api'
import PageHeader from '../components/PageHeader'
import { ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'
import { canAny } from '../permissions'

type Level = 'READ' | 'WRITE' | 'NONE'

interface DatabaseRow {
  tenant: string
  catalog: string
  schemaName: string
  ownerUser: string
  tableCount: number
  level: Level
  pending: boolean
  mine: boolean
}

interface TableRow {
  id: number
  tenant: string
  catalog: string
  schemaName: string
  tableName: string
  ownerUser: string
  description?: string
  level: Level
  pending: boolean
  owner: boolean
  mine: boolean
}

interface Resources {
  tenant: string
  admin: boolean
  databases: DatabaseRow[]
  tables: TableRow[]
  folders: FolderRow[]
}

interface FolderRow {
  id: number
  name: string
  path: string
  createdBy: string
  grantCount: number
  level: Level
  pending: boolean
  owner: boolean
  mine: boolean
  openDefault: boolean
}

type ResourceType = 'DATABASE' | 'TABLE' | 'COLUMN' | 'ASSET_FOLDER'

interface AccessRequest {
  id: number
  requester: string
  requesterTenant: string
  resourceType: ResourceType
  catalog: string
  schemaName: string
  tableName?: string
  columnName?: string
  /** V112：ASSET_FOLDER 申请的真正资源键 */
  folderId?: number
  permission: 'READ' | 'WRITE'
  reason?: string
  /** v460：接口还会返回 REVOKED（回收）与 EXPIRED（到期任务），类型必须覆盖 */
  /** PENDING_SECOND：L4 机密资源已过一审，等待另一位审批人二审 */
  status: 'PENDING' | 'PENDING_SECOND' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
    | 'REVOKED' | 'EXPIRED'
  approver?: string
  secondApprover?: string
  comment?: string
  createdAt: string
  decidedAt?: string
  /** D52：有效期与回收留痕（后端 baseRequestSql 早就返回了，前端类型补齐才敢用） */
  expiresAt?: string
  revokedBy?: string
  revokedAt?: string
  ownerUser?: string
  ownerTenant?: string
}

/** 到期口径与后端判定一致：expires_at 为空按决定时间 + 90 天（V80/F-P2-3）。 */
function effectiveExpiry(row: AccessRequest): string | undefined {
  if (row.expiresAt) return row.expiresAt
  if (row.decidedAt) {
    const d = new Date(row.decidedAt)
    if (!Number.isNaN(d.getTime())) {
      d.setDate(d.getDate() + 90)
      return d.toISOString()
    }
  }
  return undefined
}

/** 剩余天数：< 0 表示已过期；拿不到到期时间返回 null（显示「-」而不是猜）。 */
function daysLeft(row: AccessRequest): number | null {
  const raw = effectiveExpiry(row)
  if (!raw) return null
  const ts = new Date(raw).getTime()
  if (Number.isNaN(ts)) return null
  return Math.floor((ts - Date.now()) / 86400000)
}

interface ColumnOption {
  columnName: string
  dataLevel?: string
}

const LEVEL_LABEL: Record<Level, { text: string; color: string }> = {
  WRITE: { text: '可写', color: 'green' },
  READ: { text: '只读', color: 'blue' },
  NONE: { text: '无权限', color: 'default' },
}

const STATUS_LABEL: Record<AccessRequest['status'], { text: string; color: string }> = {
  PENDING: { text: '审批中', color: 'orange' },
  PENDING_SECOND: { text: '待二审', color: 'geekblue' },
  APPROVED: { text: '已通过', color: 'green' },
  REJECTED: { text: '已驳回', color: 'red' },
  CANCELLED: { text: '已撤销', color: 'default' },
  REVOKED: { text: '已回收', color: 'volcano' },
  EXPIRED: { text: '已过期', color: 'default' },
}

const PERMISSION_LABEL = { READ: '读取', WRITE: '写入' } as const

/**
 * v460 F-P1-5 兜底：接口以后新增状态（或历史数据里 status 为空）时按原文显示，
 * 不能出现空标签，更不能因为查表失败把「我的申请」整页打崩。
 */
function statusMeta(status?: string): { text: string; color: string } {
  return STATUS_LABEL[status as AccessRequest['status']]
    ?? { text: status?.trim() ? status : '未知状态', color: 'default' }
}

function resourceText(row: {
  catalog: string; schemaName: string; tableName?: string; columnName?: string
  resourceType?: string
  /** V112：ASSET_FOLDER 申请用 path 展示（schema_name 存的是 path 快照） */
  path?: string
}) {
  if (row.resourceType === 'ASSET_FOLDER') {
    return row.path || row.schemaName || '(目录)'
  }
  const base = `${row.catalog}.${row.schemaName}`
  if (row.resourceType === 'DATABASE' || !row.tableName) return base
  const table = `${base}.${row.tableName}`
  return row.columnName ? `${table}.${row.columnName}` : table
}

function LevelTag({ level, pending, owner }: { level: Level; pending: boolean; owner?: boolean }) {
  if (owner) {
    return <Tag color="gold" title="负责人拥有全部读写权限">负责人 · 全部权限</Tag>
  }
  if (level !== 'NONE') {
    // 未知 level（接口加新枚举/字段为空）不能直接 LEVEL_LABEL[level].color，否则整页白屏
    const meta = LEVEL_LABEL[level] ?? { text: level || '未知权限', color: 'default' }
    return <Tag color={meta.color}>{meta.text}</Tag>
  }
  if (pending) return <Tag color="orange">审批中</Tag>
  return <Tag>无权限</Tag>
}

export default function Access() {
  const [resources, setResources] = useState<Resources>()
  const [mine, setMine] = useState<AccessRequest[]>([])
  const [approvals, setApprovals] = useState<AccessRequest[]>([])
  // BUG-TC6-2：已授权（可回收）的单子。approvals 只返回 PENDING/PENDING_SECOND，
  // 回收按钮挂在那边是死分支；这里单独取 scope=grants（后端按 access:revoke 收口）。
  const [grants, setGrants] = useState<AccessRequest[]>([])
  const canRevokeGrant = canAny('access:revoke', 'user:manage')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [tab, setTab] = useState('resources')
  const [apply, setApply] = useState<{
    resourceType: ResourceType
    catalog: string
    schemaName: string
    tableName?: string
    columnName?: string
    /** V112：ASSET_FOLDER 申请需要 folderId（真正资源键）+ path（展示用） */
    folderId?: number
    path?: string
    permission: 'READ' | 'WRITE'
  }>()
  const [columnOptions, setColumnOptions] = useState<ColumnOption[]>([])
  const [columnLoading, setColumnLoading] = useState(false)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [decision, setDecision] = useState<{ row: AccessRequest; action: 'approve' | 'reject' }>()
  const [comment, setComment] = useState('')
  // BUG-TC6-14：批量审批（只给「待我审批」列表）。后端逐项复用单项 decide 闸门，
  // 前端这里只负责选择与确认；单次上限 50 与后端一致。
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const [bulk, setBulk] = useState<'approve' | 'reject'>()
  const [bulkComment, setBulkComment] = useState('')
  const [bulkBusy, setBulkBusy] = useState(false)
  // D52 续期弹窗：说明会写进新申请的 reason，审批人得能看到为什么续
  const [renew, setRenew] = useState<AccessRequest>()
  const [renewComment, setRenewComment] = useState('')
  const [renewBusy, setRenewBusy] = useState(false)
  const [searchParams, setSearchParams] = useSearchParams()

  /**
   * @param silent 轮询刷新：不动 loading、不覆盖错误态。
   *   否则每 30 秒表格闪一次骨架、或把一次瞬时失败的红条糊在已经正常显示的页面上。
   */
  const load = async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const [res, my, todo, granted] = await Promise.all([
        api<Resources>('/api/access/resources'),
        api<AccessRequest[]>('/api/access/requests?scope=mine'),
        api<AccessRequest[]>('/api/access/requests?scope=approvals'),
        // 没有回收权限就不请求：后端会返回空数组，但没必要多打一次
        canRevokeGrant
          ? api<AccessRequest[]>('/api/access/requests?scope=grants')
          : Promise.resolve([] as AccessRequest[]),
      ])
      setResources(res)
      setMine(my)
      setApprovals(todo)
      setGrants(granted)
      setError('')
    } catch (e) {
      if (!silent) {
        setError(e instanceof Error ? e.message : '加载权限数据失败')
      } else {
        // 静默失败要留痕（排障时能看到），但不打扰用户
        console.warn('[access] 静默刷新失败', e)
      }
    } finally {
      if (!silent) setLoading(false)
    }
  }

  // BUG-TC6-9：审批是「等别人动」的场景，数据会在我停留期间变化 ⇒ 30 秒静默轮询，
  // 与告警中心同款口径（页面不可见时跳过；切回标签页立即刷新一次）。
  useEffect(() => {
    void load()
    const timer = window.setInterval(() => {
      if (document.hidden) return
      void load(true)
    }, 30_000)
    const onVisible = () => {
      if (!document.hidden) void load(true)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [])

  const loadColumns = async (catalog: string, schemaName: string, tableName?: string) => {
    if (!tableName) {
      setColumnOptions([])
      return
    }
    setColumnLoading(true)
    try {
      const qs = new URLSearchParams({ catalog, schemaName, tableName })
      const list = await api<ColumnOption[]>(`/api/access/columns?${qs.toString()}`)
      setColumnOptions(Array.isArray(list) ? list : [])
    } catch {
      // 字段元数据加载失败不阻塞整表申请
      setColumnOptions([])
    } finally {
      setColumnLoading(false)
    }
  }

  const openApply = (
    resourceType: ResourceType, catalog: string, schemaName: string,
    permission: 'READ' | 'WRITE', tableName?: string, columnName?: string,
  ) => {
    const type: ResourceType = columnName ? 'COLUMN' : resourceType
    setApply({ resourceType: type, catalog, schemaName, tableName, columnName, permission })
    setReason('')
    setColumnOptions([])
    void loadColumns(catalog, schemaName, tableName)
  }

  /** V112：打开目录内容权申请弹窗 */
  const openApplyFolder = (folderId: number, path: string, permission: 'READ' | 'WRITE') => {
    setApply({
      resourceType: 'ASSET_FOLDER',
      catalog: 'ASSET',
      schemaName: path,
      folderId,
      path,
      permission,
    })
    setReason('')
    setColumnOptions([])
  }

  const submit = async () => {
    if (!apply) return
    setSubmitting(true)
    try {
      const payload = {
        ...apply,
        columnName: apply.resourceType === 'COLUMN' ? apply.columnName : undefined,
        // V112：ASSET_FOLDER 申请只发 folderId + permission + reason，不发 catalog/schemaName/tableName
        catalog: apply.resourceType === 'ASSET_FOLDER' ? undefined : apply.catalog,
        schemaName: apply.resourceType === 'ASSET_FOLDER' ? undefined : apply.schemaName,
        tableName: apply.resourceType === 'ASSET_FOLDER' ? undefined : apply.tableName,
        path: undefined, // path 只用于展示，不发给后端
      }
      await post('/api/access/requests', { ...payload, reason })
      setApply(undefined)
      message.success(apply.resourceType === 'COLUMN'
        ? '列权限申请已提交，等待负责人审批'
        : apply.resourceType === 'ASSET_FOLDER'
          ? '目录内容权申请已提交，等待目录负责人审批'
          : '申请已提交，等待负责人审批')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '提交申请失败')
    } finally {
      setSubmitting(false)
    }
  }

  // 支持从查询结果「申请明文」深链过来（Query 页只需带 ?column=&table=&schema=）。
  useEffect(() => {
    const columnName = searchParams.get('column') || undefined
    const tableName = searchParams.get('table') || undefined
    const schemaName = searchParams.get('schema') || undefined
    if (!columnName || !tableName || !schemaName) return
    const catalog = searchParams.get('catalog') || 'Doris'
    const permission = searchParams.get('permission') === 'WRITE' ? 'WRITE' : 'READ'
    openApply('COLUMN', catalog, schemaName, permission, tableName, columnName)
    setSearchParams({}, { replace: true })
  }, [])

  const decide = async () => {
    if (!decision) return
    try {
      await post(`/api/access/requests/${decision.row.id}/${decision.action}`, { comment })
      setDecision(undefined)
      setComment('')
      message.success(decision.action === 'approve' ? '已通过' : '已驳回')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '处理失败')
    }
  }

  /** 单项可批 = 待审批状态；批量选择与单项闸门同口径（BUG-TC6-14）。 */
  const canDecide = (row: AccessRequest) => row.status === 'PENDING' || row.status === 'PENDING_SECOND'

  const bulkDecide = async () => {
    if (!bulk || !selectedIds.length) return
    setBulkBusy(true)
    try {
      // 接线检查器按「无空格完整串」提取调用路径，模板拼接会被判未接界面
      // （同 Tables.tsx 删库段注释先例）——两个端点写成完整字面量
      const path = bulk === 'approve' ? '/api/access/requests/bulk-approve'
        : '/api/access/requests/bulk-reject'
      const res = await post<{ total: number; succeeded: number; failed: number;
        results: { id: number; ok: boolean; message?: string }[] }>(
        path,
        { ids: selectedIds, comment: bulkComment },
      )
      setBulk(undefined)
      setBulkComment('')
      setSelectedIds([])
      await load()
      const failedRows = (res?.results || []).filter((r) => !r.ok)
      if (!res || res.failed === 0) {
        message.success(`已批量${bulk === 'approve' ? '通过' : '驳回'} ${res?.succeeded ?? 0} 条`)
      } else {
        // 失败不许静默：逐条原因带回界面（最多列 3 条，其余提示进列表看状态）
        const why = failedRows.slice(0, 3)
          .map((r) => `#${r.id}：${r.message || '处理失败'}`).join('；')
        message.warning(
          `成功 ${res.succeeded} 条、失败 ${res.failed} 条。${why}`
          + (failedRows.length > 3 ? `…（其余 ${failedRows.length - 3} 条见列表）` : ''),
          8,
        )
      }
    } catch (e) {
      // 整体预检失败（空/超 50/重复）走这里：后端一条都没处理
      message.error(e instanceof Error ? e.message : '批量处理失败')
    } finally {
      setBulkBusy(false)
    }
  }

  const revoke = async (row: AccessRequest) => {
    try {
      await post(`/api/access/requests/${row.id}/revoke`)
      message.success('已回收该授权')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '回收失败')
    }
  }

  const cancel = async (row: AccessRequest) => {
    try {
      await post(`/api/access/requests/${row.id}/cancel`)
      message.success('已撤销')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '撤销失败')
    }
  }

  const openRenew = (row: AccessRequest) => {
    setRenew(row)
    setRenewComment('')
  }

  /**
   * 续期＝对同一资源再接一张新单走原审批链（不是直接把到期时间往后推）：
   * 直接延长等于开一条绕过审批人的授权通道。原授权按原时间自然失效。
   */
  const submitRenew = async () => {
    if (!renew) return
    setRenewBusy(true)
    try {
      const out = await post<{ id: number; reused?: boolean }>(
        `/api/access/requests/${renew.id}/renew`, { comment: renewComment },
      )
      message.success(out?.reused
        ? `已有审批中的续期单（#${out.id}），这次复用那一单，没有新建`
        : `已提交续期申请 #${out.id}，等待审批人处理`)
      setRenew(undefined)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '续期提交失败')
    } finally {
      setRenewBusy(false)
    }
  }

  const permissionHint = (row: { level: Level; pending: boolean }) => {
    if (row.pending) return '审批中'
    if (row.level === 'WRITE') return '已有写入权限'
    if (row.level === 'READ') return '已有读取权限'
    return ''
  }

  const actionButtons = (
    row: { level: Level; pending: boolean; owner?: boolean },
    resourceType: 'DATABASE' | 'TABLE', catalog: string, schemaName: string, tableName?: string,
  ) => (
    <Space size={4}>
      <Button
        size="small"
        disabled={row.owner || row.pending || row.level === 'READ' || row.level === 'WRITE'}
        onClick={() => openApply(resourceType, catalog, schemaName, 'READ', tableName)}
      >
        申请读取
      </Button>
      <Button
        size="small"
        type="primary"
        ghost
        disabled={row.owner || row.pending || row.level === 'WRITE'}
        onClick={() => openApply(resourceType, catalog, schemaName, 'WRITE', tableName)}
      >
        申请写入
      </Button>
    </Space>
  )

  const requestColumns = (mode: 'mine' | 'approvals' | 'grants') => [
    ...(mode !== 'mine'
      ? [{
        title: '申请人',
        render: (_: unknown, row: AccessRequest) => (
          <Space size={4}>
            <span>{row.requester}</span>
            <Tag>{row.requesterTenant}</Tag>
          </Space>
        ),
      }]
      : []),
    {
      title: '资源',
      render: (_: unknown, row: AccessRequest) => (
        <Space size={6}>
          {row.resourceType === 'DATABASE'
            ? <FolderOpenOutlined />
            : row.resourceType === 'COLUMN'
              ? <KeyOutlined />
              : row.resourceType === 'ASSET_FOLDER'
                ? <FolderOpenOutlined style={{ color: '#1890ff' }} />
                : <FileTextOutlined />}
          <Tooltip title={row.resourceType === 'COLUMN' ? '列级授权：只放行这一列' : undefined}>
            <Typography.Text code>
              {row.resourceType === 'COLUMN' && row.tableName && row.columnName
                ? `${row.catalog}.${row.schemaName}.${row.tableName}.${row.columnName}`
                : resourceText(row)}
            </Typography.Text>
          </Tooltip>
          <Tag color={row.resourceType === 'COLUMN' ? 'purple' : row.resourceType === 'ASSET_FOLDER' ? 'blue' : undefined}>
            {row.resourceType === 'DATABASE' ? '库' : row.resourceType === 'COLUMN' ? '列' : row.resourceType === 'ASSET_FOLDER' ? '目录' : '表'}
          </Tag>
        </Space>
      ),
    },
    {
      title: '权限',
      width: 80,
      render: (_: unknown, row: AccessRequest) => PERMISSION_LABEL[row.permission] ?? row.permission,
    },
    {
      title: '状态',
      width: 100,
      render: (_: unknown, row: AccessRequest) => {
        const meta = statusMeta(row.status)
        return <Tag color={meta.color}>{meta.text}</Tag>
      },
    },
    { title: '理由', dataIndex: 'reason', ellipsis: true },
    {
      // D52：到期时间必须显式可见——「权限什么时候没了」不该等到查询被拒才知道
      title: '有效期', width: 150,
      render: (_: unknown, row: AccessRequest) => {
        if (row.status === 'PENDING' || row.status === 'PENDING_SECOND'
          || row.status === 'REJECTED' || row.status === 'CANCELLED') {
          return <Typography.Text type="secondary" style={{ fontSize: 12 }}>未生效</Typography.Text>
        }
        const left = daysLeft(row)
        const when = (effectiveExpiry(row) || '').slice(0, 10)
        if (row.status === 'EXPIRED') {
          return <Tag>已于 {when || '-'} 到期</Tag>
        }
        if (row.status === 'REVOKED') {
          return (
            <Tooltip title={`由 ${row.revokedBy || '系统'} 回收；回收时间 ${(row.revokedAt || '').slice(0, 10) || '-'}`}>
              <Tag color="volcano">已回收</Tag>
            </Tooltip>
          )
        }
        const hint = `到期时间 ${when || '未记录（按决定时间 +90 天）'}；`
          + '到期前 7 天与 1 天各提醒一次申请人与审批人'
        if (left === null) return <Tag color="default">-</Tag>
        if (left <= 1) return <Tooltip title={hint}><Tag color="red">剩 {Math.max(left, 0)} 天</Tag></Tooltip>
        if (left <= 7) return <Tooltip title={hint}><Tag color="orange">剩 {left} 天</Tag></Tooltip>
        return <Tooltip title={hint}><Tag color="green">剩 {left} 天</Tag></Tooltip>
      },
    },
    { title: '申请时间', dataIndex: 'createdAt', width: 170 },
    ...(mode === 'mine'
      ? [
        {
          title: '审批人 / 意见',
          width: 190,
          render: (_: unknown, row: AccessRequest) => {
            const approvers = [row.approver, row.secondApprover].filter(Boolean).join(' / ')
            return approvers
              ? `${approvers}${row.comment ? '：' + row.comment : ''}`
              : '-'
          },
        },
        {
          title: '操作',
          width: 130,
          render: (_: unknown, row: AccessRequest) => (
            row.status === 'PENDING' || row.status === 'PENDING_SECOND'
              ? <Button size="small" type="link" onClick={() => cancel(row)}>撤销</Button>
              // 续期只对申请人可见可点（「我的申请」这一列本来就是本人单子）：
              // 代他人续期会把审计里的申请人写歪，所以审批人那侧不给入口
              : row.status === 'APPROVED' || row.status === 'EXPIRED'
                ? (
                  <Button size="small" type="link" onClick={() => openRenew(row)}>续期</Button>
                )
                : '-'
          ),
        },
      ]
      : [
        {
          title: '操作',
          width: 170,
          // BUG-TC6-2：「回收」只在「已授权」页签出现（该页签的数据源就是 APPROVED 可回收单）。
          // 待我审批页签不再挂 APPROVED 分支——那条 SQL 永远不返回 APPROVED，是死分支。
          render: (_: unknown, row: AccessRequest) => {
            if (mode === 'grants') {
              return row.status === 'APPROVED'
                ? (
                  <Popconfirm
                    title="回收该授权？"
                    description="回收后对方立即失去该库/表权限，原审批记录保留"
                    okText="回收" okButtonProps={{ danger: true }} cancelText="取消"
                    onConfirm={() => revoke(row)}
                  >
                    <Button size="small" danger type="link" icon={<CloseOutlined />}>回收</Button>
                  </Popconfirm>
                )
                : '-'
            }
            return row.status === 'PENDING' || row.status === 'PENDING_SECOND'
              ? (
                <Space size={4}>
                  <Button
                    size="small"
                    type="primary"
                    icon={<CheckOutlined />}
                    onClick={() => { setDecision({ row, action: 'approve' }); setComment('') }}
                  >
                    {row.status === 'PENDING_SECOND' ? '二审通过' : '通过'}
                  </Button>
                  <Button
                    size="small"
                    danger
                    icon={<CloseOutlined />}
                    onClick={() => { setDecision({ row, action: 'reject' }); setComment('') }}
                  >
                    驳回
                  </Button>
                </Space>
              )
              : '-'
          },
        },
      ]),
  ]

  return (
    <div>
      <PageHeader
        title="权限申请"
        description="按库 / 表 / 资产目录申请读取、写入权限；项目/库/目录的负责人（owner）拥有全部权限，并负责审批其他人的申请。平台管理员可兜底审批。授权有有效期：到期前 7 天与 1 天各提醒一次申请人与审批人，到期自动失效，可在「我的申请」点「续期」重新走一轮审批；有回收权限的人可在「已授权」页签随时回收已生效的授权。"
        extra={(
          <Tag icon={<KeyOutlined />} color="blue">
            我的租户：{resources?.tenant || '-'}
          </Tag>
        )}
      />

      {error ? (
        <Card>
          <ErrorState title="「权限申请」加载失败" message={error} onRetry={() => void load()} />
        </Card>
      ) : loading && !resources ? (
        <Card><LoadingState rows={6} /></Card>
      ) : (
        <Card>
          <Tabs
            activeKey={tab}
            onChange={setTab}
            items={[
              {
                key: 'resources',
                label: '可申请资源',
                children: (
                  <Space direction="vertical" size={16} style={{ width: '100%' }}>
                    <div>
                      <Typography.Title level={5} style={{ marginTop: 0 }}>库（schema）</Typography.Title>
                      <Table<DatabaseRow>
                        rowKey={(row) => `${row.tenant}:${row.catalog}:${row.schemaName}`}
                        size="small"
                        loading={loading}
                        dataSource={resources?.databases || []}
                        pagination={false}
                        locale={{ emptyText: tableEmpty('还没有纳管的库', '由资源负责人在元数据里登记') }}
                        columns={[
                          { title: '租户', dataIndex: 'tenant', width: 120 },
                          {
                            title: '库',
                            render: (_, row) => <Typography.Text code>{resourceText(row)}</Typography.Text>,
                          },
                          { title: '表数量', dataIndex: 'tableCount', width: 90 },
                          { title: '负责人', dataIndex: 'ownerUser', width: 120 },
                          {
                            title: '我的权限',
                            width: 110,
                            render: (_, row) => <LevelTag level={row.level} pending={row.pending} />,
                          },
                          {
                            title: '操作',
                            width: 190,
                            render: (_, row) => actionButtons(
                              row, 'DATABASE', row.catalog, row.schemaName),
                          },
                        ]}
                      />
                    </div>
                    <div>
                      <Typography.Title level={5}>表</Typography.Title>
                      <Table<TableRow>
                        rowKey="id"
                        size="small"
                        loading={loading}
                        dataSource={resources?.tables || []}
                        pagination={{ pageSize: 10, hideOnSinglePage: true }}
                        scroll={{ x: 900 }}
                        locale={{ emptyText: tableEmpty('还没有纳管的表', '由资源负责人在元数据里登记') }}
                        columns={[
                          { title: '租户', dataIndex: 'tenant', width: 100 },
                          {
                            title: '库 / 表',
                            render: (_, row) => (
                              <Space size={6}>
                                <Typography.Text code>
                                  {row.catalog}.{row.schemaName}.{row.tableName}
                                </Typography.Text>
                                {row.mine ? <Tag>本租户</Tag> : null}
                              </Space>
                            ),
                          },
                          { title: '负责人', dataIndex: 'ownerUser', width: 110 },
                          { title: '说明', dataIndex: 'description', ellipsis: true },
                          {
                            title: '我的权限',
                            width: 110,
                            render: (_, row) => (
                              <LevelTag level={row.level} pending={row.pending} owner={row.owner} />
                            ),
                          },
                          {
                            title: '操作',
                            width: 190,
                            render: (_, row) => (
                              <Space direction="vertical" size={2}>
                                {actionButtons(row, 'TABLE', row.catalog, row.schemaName, row.tableName)}
                                {permissionHint(row) ? (
                                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                                    {permissionHint(row)}
                                  </Typography.Text>
                                ) : null}
                              </Space>
                            ),
                          },
                        ]}
                      />
                    </div>
                    <div>
                      <Typography.Title level={5}>资产目录（V112）</Typography.Title>
                      <Table<FolderRow>
                        rowKey="id"
                        size="small"
                        loading={loading}
                        dataSource={resources?.folders || []}
                        pagination={{ pageSize: 10, hideOnSinglePage: true }}
                        scroll={{ x: 800 }}
                        locale={{ emptyText: tableEmpty('还没有资产目录', '在「资产」模块里新建目录') }}
                        columns={[
                          {
                            title: '目录路径',
                            render: (_, row) => (
                              <Space size={6}>
                                <FolderOpenOutlined />
                                <Typography.Text code>{row.path}</Typography.Text>
                                {row.openDefault && (
                                  <Tag color="default" title="未配置授权：本租户全员可访问">
                                    默认开放
                                  </Tag>
                                )}
                              </Space>
                            ),
                          },
                          { title: '负责人', dataIndex: 'createdBy', width: 110 },
                          {
                            title: '我的权限',
                            width: 110,
                            render: (_, row) => (
                              <LevelTag level={row.level} pending={row.pending} owner={row.owner} />
                            ),
                          },
                          {
                            title: '操作',
                            width: 190,
                            render: (_, row) => (
                              <Space direction="vertical" size={2}>
                                <Space size={4}>
                                  <Button
                                    size="small"
                                    disabled={row.owner || row.pending || row.level === 'READ' || row.level === 'WRITE'}
                                    onClick={() => openApplyFolder(row.id, row.path, 'READ')}
                                  >
                                    申请读取
                                  </Button>
                                  <Button
                                    size="small"
                                    type="primary"
                                    ghost
                                    disabled={row.owner || row.pending || row.level === 'WRITE'}
                                    onClick={() => openApplyFolder(row.id, row.path, 'WRITE')}
                                  >
                                    申请写入
                                  </Button>
                                </Space>
                                {permissionHint(row) ? (
                                  <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                                    {permissionHint(row)}
                                  </Typography.Text>
                                ) : null}
                              </Space>
                            ),
                          },
                        ]}
                      />
                    </div>
                    {/* BUG-TC6-9：以前这里带 `!resources.admin`，把最该兜底审批的平台/租户管理员
                        排除在外。approvals 是「scope=approvals」的结果，后端已按 canApprove
                        逐条过滤（含管理员分支），所以条数对所有审批人都可信。 */}
                    {resources && approvals.length > 0 ? (
                      <Alert
                        type="info"
                        showIcon
                        message={`有 ${approvals.length} 条申请在等你审批`}
                        action={<Button size="small" onClick={() => setTab('approvals')}>去审批</Button>}
                      />
                    ) : null}
                  </Space>
                ),
              },
              {
                key: 'mine',
                label: `我的申请${mine.length ? `（${mine.length}）` : ''}`,
                children: (
                  <Table<AccessRequest>
                    rowKey="id"
                    size="small"
                    loading={loading}
                    dataSource={mine}
                    pagination={{ pageSize: 10, hideOnSinglePage: true }}
                    scroll={{ x: 1000 }}
                    locale={{
                      emptyText: tableEmpty('还没有申请', '到「可申请资源」里对库或表发起申请'),
                    }}
                    columns={requestColumns('mine') as never}
                  />
                ),
              },
              {
                key: 'approvals',
                label: `待我审批${approvals.length ? `（${approvals.length}）` : ''}`,
                children: (
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    {selectedIds.length > 0 ? (
                      <Space wrap>
                        <Typography.Text type="secondary">
                          已选 {selectedIds.length} 条待审批申请
                        </Typography.Text>
                        <Button
                          size="small"
                          type="primary"
                          icon={<CheckOutlined />}
                          disabled={selectedIds.length > 50}
                          onClick={() => { setBulk('approve'); setBulkComment('') }}
                        >
                          批量通过
                        </Button>
                        <Button
                          size="small"
                          danger
                          icon={<CloseOutlined />}
                          disabled={selectedIds.length > 50}
                          onClick={() => { setBulk('reject'); setBulkComment('') }}
                        >
                          批量驳回
                        </Button>
                        <Button size="small" onClick={() => setSelectedIds([])}>清除选择</Button>
                        {selectedIds.length > 50 ? (
                          <Typography.Text type="danger">单次最多 50 条，请减少选择</Typography.Text>
                        ) : null}
                      </Space>
                    ) : null}
                    <Table<AccessRequest>
                      rowKey="id"
                      size="small"
                      loading={loading}
                      dataSource={approvals}
                      pagination={{ pageSize: 10, hideOnSinglePage: true }}
                      scroll={{ x: 1000 }}
                      rowSelection={{
                        selectedRowKeys: selectedIds,
                        onChange: (keys) => setSelectedIds(keys as number[]),
                        // 与单项闸门同口径：只有待审批的行可勾选（BUG-TC6-14）
                        getCheckboxProps: (row: AccessRequest) => ({ disabled: !canDecide(row) }),
                      }}
                      locale={{
                        emptyText: tableEmpty('没有待审批的申请', '负责人和管理员会在这里看到待处理申请'),
                      }}
                      columns={requestColumns('approvals') as never}
                    />
                  </Space>
                ),
              },
              // 「已授权」只对能回收的人出现（access:revoke / 平台管理员），
              // 与后端 scope=grants 的收口一致：看得到就一定点得动。
              ...(canRevokeGrant
                ? [{
                  key: 'grants',
                  label: `已授权${grants.length ? `（${grants.length}）` : ''}`,
                  children: (
                    <Table<AccessRequest>
                      rowKey="id"
                      size="small"
                      loading={loading}
                      dataSource={grants}
                      pagination={{ pageSize: 10, hideOnSinglePage: true }}
                      scroll={{ x: 1000 }}
                      locale={{
                        emptyText: tableEmpty('没有可回收的授权',
                          '审批通过的授权会出现在这里，可随时回收'),
                      }}
                      columns={requestColumns('grants') as never}
                    />
                  ),
                }]
                : []),
            ]}
          />
        </Card>
      )}

      <Modal
        title={apply
          ? `申请${apply.resourceType === 'DATABASE'
            ? '库' : apply.resourceType === 'COLUMN' ? '列' : apply.resourceType === 'ASSET_FOLDER' ? '目录' : '表'}${PERMISSION_LABEL[apply.permission]}权限`
          : ''}
        open={Boolean(apply)}
        onOk={submit}
        confirmLoading={submitting}
        onCancel={() => setApply(undefined)}
        okText="提交申请"
        destroyOnClose
      >
        {apply ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>资源</Typography.Text>
              <div>
                <Typography.Text code>
                  {resourceText({
                    ...apply,
                    tableName: apply.resourceType === 'DATABASE' ? undefined : apply.tableName,
                    path: apply.resourceType === 'ASSET_FOLDER' ? apply.path : undefined,
                  })}
                </Typography.Text>
              </div>
            </div>
            {apply.resourceType !== 'DATABASE' ? (
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  申请范围（可选）
                </Typography.Text>
                <Select
                  allowClear
                  style={{ width: '100%' }}
                  loading={columnLoading}
                  value={apply.columnName}
                  placeholder={columnOptions.length
                    ? '整表（不指定列）' : '该表还没有登记字段，只能申请整表'}
                  options={columnOptions.map((c) => ({
                    value: c.columnName,
                    label: `${c.columnName}${c.dataLevel ? `（${c.dataLevel}）` : ''}`,
                  }))}
                  onChange={(value) => setApply({
                    ...apply,
                    columnName: value,
                    resourceType: value ? 'COLUMN' : 'TABLE',
                  })}
                />
                <div style={{ marginTop: 4, fontSize: 12, color: '#8c8c8c' }}>
                  不选 = 整表授权；选择字段 = 按列申请，只放行该列明文。
                </div>
                {apply.columnName
                  && columnOptions.find((c) => c.columnName === apply.columnName)?.dataLevel === 'L4' ? (
                  <Alert
                    style={{ marginTop: 8 }}
                    type="warning"
                    showIcon
                    message="L4 机密列需要两位审批人依次通过"
                    description="通过后有效期 30 天；同一位审批人不能连续审批两次。"
                  />
                ) : null}
              </div>
            ) : null}
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>申请权限</Typography.Text>
              <Radio.Group
                value={apply.permission}
                onChange={(e) => setApply({ ...apply, permission: e.target.value })}
              >
                <Radio.Button value="READ">读取</Radio.Button>
                <Radio.Button value="WRITE">写入</Radio.Button>
              </Radio.Group>
            </div>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>申请理由</Typography.Text>
              <Input.TextArea
                rows={3}
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="例如：需要把 tenant_b.orders 接入质量校验"
              />
              <div style={{ marginTop: 4, fontSize: 12, color: '#8c8c8c' }}>
                通过后有效期：普通资源 90 天、L4 机密资源 30 天；到期前 7 天与 1 天各提醒一次，
                到期自动失效，可在「我的申请」点「续期」重新走一轮审批。
              </div>
            </div>
          </Space>
        ) : null}
      </Modal>

      <Modal
        title={renew ? `续期申请：${resourceText(renew)}（${PERMISSION_LABEL[renew.permission] ?? renew.permission}）` : ''}
        open={Boolean(renew)}
        onOk={submitRenew}
        onCancel={() => setRenew(undefined)}
        okText="提交续期申请"
        confirmLoading={renewBusy}
        destroyOnClose
      >
        {renew ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert
              type="info"
              showIcon
              message="续期会再生成一张新申请，走原来的审批链"
              description={(
                <>
                  原授权 #{renew.id} 的到期时间不会被直接改长——
                  延长有效期等于绕过审批人，所以续期＝重新申请、批准后接上新的有效期。
                  原授权到期前仍然可用。
                </>
              )}
            />
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>当前到期时间</Typography.Text>
              <div>
                <Tag color="blue">{effectiveExpiry(renew)?.slice(0, 10) || '未记录（按决定时间 +90 天）'}</Tag>
                {daysLeft(renew) !== null && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    还剩 {Math.max(daysLeft(renew) ?? 0, 0)} 天
                  </Typography.Text>
                )}
              </div>
            </div>
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>续期说明（会写进新申请的理由）</Typography.Text>
              <Input.TextArea
                rows={3}
                maxLength={500}
                value={renewComment}
                onChange={(e) => setRenewComment(e.target.value)}
                placeholder="例如：月度对账还要用这张表，下个季度仍在跑"
              />
            </div>
          </Space>
        ) : null}
      </Modal>

      <Modal
        title={decision ? (decision.action === 'approve' ? '通过申请' : '驳回申请') : ''}
        open={Boolean(decision)}
        onOk={decide}
        onCancel={() => setDecision(undefined)}
        okText={decision?.action === 'approve' ? '通过' : '驳回'}
        okButtonProps={{ danger: decision?.action === 'reject' }}
        destroyOnClose
      >
        {decision ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <div>
              {decision.row.requester} 申请
              {' '}
              <Typography.Text code>{resourceText(decision.row)}</Typography.Text>
              {' '}
              的 {PERMISSION_LABEL[decision.row.permission]} 权限
            </div>
            {decision.row.status === 'PENDING_SECOND' ? (
              <Alert
                type="warning"
                showIcon
                message="L4 机密资源需要两位审批人"
                description={`该申请已由 ${decision.row.approver || '另一位审批人'} 一审通过；`
                  + '你是二审人，通过后授权立即生效（有效期 30 天），同一人不能批两次。'}
              />
            ) : null}
            <Input.TextArea
              rows={3}
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              placeholder={decision.action === 'approve' ? '审批意见（可选）' : '驳回原因'}
            />
          </Space>
        ) : null}
      </Modal>

      <Modal
        title={bulk ? `批量${bulk === 'approve' ? '通过' : '驳回'} ${selectedIds.length} 条申请` : ''}
        open={Boolean(bulk)}
        onOk={bulkDecide}
        confirmLoading={bulkBusy}
        onCancel={() => { setBulk(undefined); setBulkComment('') }}
        okText={bulk === 'approve' ? '通过' : '驳回'}
        okButtonProps={{ danger: bulk === 'reject' }}
        destroyOnClose
      >
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="每条申请都按与单条审批相同的闸门处理"
            description={'不能自批、跨租户拦截、L4/策略二审仍需另一位审批人补批；'
              + '已处理过或不满足条件的条目会单独报失败，不影响其余。'}
          />
          <Input.TextArea
            rows={2}
            maxLength={200}
            value={bulkComment}
            onChange={(e) => setBulkComment(e.target.value)}
            placeholder={bulk === 'approve' ? '审批意见（可选，写入每条申请）' : '驳回原因（可选，写入每条申请）'}
          />
        </Space>
      </Modal>
    </div>
  )
}
