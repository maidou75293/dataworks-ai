import { useCallback, useEffect, useMemo, useState } from 'react'
import type { CSSProperties } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, Button, Card, Checkbox, Input, Modal, Popconfirm, Space, Table, Tabs, Tag, Typography, message,
} from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckOutlined, CloseOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { api, del, post } from '../api'
import PageHeader from '../components/PageHeader'
import { ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'
import { canAny } from '../permissions'
import { currentUser } from '../auth'

/** 与后端 ChangeService.STATUSES 一致；APPROVED 只是执行中的中间态。
 *  BUG-TC6-6：补 CANCELLED——后端撤回接口早已存在（POST /api/changes/{id}/cancel），
 *  此前前端零调用且枚举不含该状态，撤回后的单据在所有页签里都数不到（像凭空消失）。*/
export type ChangeStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'DONE' | 'FAILED'

/** GET /api/changes 返回的字段（后端 VIEW 已转成 camelCase）。 */
export interface ChangeRequest {
  id: number
  tenantId: string
  projectId: number
  datasourceId: number
  targetTable?: string | null
  sqlText: string
  status: ChangeStatus
  requester: string
  reviewer?: string | null
  reviewComment?: string | null
  backupTable?: string | null
  execResult?: string | null
  createdAt: string
  reviewedAt?: string | null
  executedAt?: string | null
  /** D53：回滚 SQL（DROP/TRUNCATE 执行成功后回填真实备份表名）。 */
  inverseSql?: string | null
  /** D53：不可逆标记（数据无法自动还原，审批前必须勾选确认）。 */
  irreversible?: boolean
  /** D53：回滚单指向的原变更单 id。 */
  rollbackOf?: number | null
}

/** 与后端 ChangeRules.BACKUP_PLACEHOLDER 一致：执行前的回滚 SQL 占位符。 */
export const BACKUP_PLACEHOLDER = '<备份表·执行后回填>'

export const CHANGE_STATUS_META: Record<ChangeStatus, { text: string; color: string }> = {
  PENDING: { text: '待审批', color: 'gold' },
  APPROVED: { text: '执行中', color: 'blue' },
  DONE: { text: '已完成', color: 'green' },
  FAILED: { text: '执行失败', color: 'red' },
  REJECTED: { text: '已驳回', color: 'default' },
  CANCELLED: { text: '已撤回', color: 'default' },
}

const STATUS_ORDER: ChangeStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'DONE', 'FAILED']

/**
 * 后端将来新增状态时按原文展示，不能因为查表失败把整页打崩
 * （列表接口会把后端枚举原样透传）。
 */
export function changeStatusMeta(status?: string | null): { text: string; color: string } {
  return (status && CHANGE_STATUS_META[status as ChangeStatus])
    || { text: status?.trim() ? status : '未知状态', color: 'default' }
}

/** 后端时间统一按 ISO 字符串返回；无法解析时原样显示，避免显示 Invalid Date。 */
export function formatChangeTime(value?: string | null): string {
  if (!value) return '-'
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm:ss') : value
}

type TabKey = 'ALL' | ChangeStatus

interface DecisionState {
  row: ChangeRequest
  action: 'approve' | 'reject'
}

const INVERSE_PRE_STYLE: CSSProperties = {
  margin: 0,
  padding: 8,
  background: '#fafafa',
  border: '1px solid #f0f0f0',
  borderRadius: 6,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  fontSize: 12,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  maxHeight: 160,
  overflow: 'auto',
}

/**
 * D53：「回滚方案」展示块（审批弹窗与详情页共用，保证两处口径一致）。
 * 不可逆 → 红色警示；有回滚 SQL → 原文展示；还没有 → 说明执行后生成。
 */
export function RollbackPlanBlock({ row }: { row: ChangeRequest }) {
  if (row.irreversible) {
    return (
      <Alert
        type="error"
        showIcon
        message="该变更不可逆"
        description="数据无法自动还原（如 DROP/MODIFY COLUMN、DELETE/UPDATE 等），审批执行后无法平台内回滚。"
      />
    )
  }
  const inverse = row.inverseSql?.trim()
  if (!inverse) {
    return (
      <Typography.Text type="secondary">
        回滚 SQL 将在执行后按自动备份表生成（DROP / TRUNCATE 单表）。
      </Typography.Text>
    )
  }
  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      <pre style={INVERSE_PRE_STYLE}>{inverse}</pre>
      {inverse.includes(BACKUP_PLACEHOLDER) ? (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          备份表名在审批执行成功后自动回填。
        </Typography.Text>
      ) : null}
    </Space>
  )
}

export default function ChangeRequests() {
  const nav = useNavigate()
  const canApprove = canAny('change:approve')
  const [rows, setRows] = useState<ChangeRequest[]>([])
  const [loaded, setLoaded] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [tab, setTab] = useState<TabKey>('ALL')
  const [decision, setDecision] = useState<DecisionState | null>(null)
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  // D53：irreversible 单的「已知不可逆」勾选（不勾选后端也会 400，前端先禁用按钮）
  const [ackIrreversible, setAckIrreversible] = useState(false)

  /**
   * @param silent 轮询刷新：不动 loading、不覆盖错误态，
   *   否则每 30 秒表格闪一次骨架，或把一次瞬时失败的红条糊在正常显示的页面上。
   */
  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      // 不传 status：一次拿全量，各状态条数直接在前端统计；切换 Tab 不再重复请求。
      // 接口最多返回 200 条，计数口径也以这 200 条为准。
      const data = await api<ChangeRequest[] | null>('/api/changes')
      setRows(Array.isArray(data) ? data : [])
      setLoaded(true)
      setError('')
    } catch (e) {
      if (!silent) {
        setError(e instanceof Error ? e.message : '加载变更单失败')
      } else {
        console.warn('[changes] 静默刷新失败', e)
      }
    } finally {
      if (!silent) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // BUG-TC6-9：审批是「等别人动」的场景 ⇒ 30 秒静默轮询（页面不可见时跳过），
  // 「执行中」的单也能自己走到 DONE/FAILED，不用手点刷新。
  useEffect(() => {
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
  }, [load])

  const counts = useMemo(() => {
    const out = {} as Record<ChangeStatus, number>
    for (const status of STATUS_ORDER) out[status] = 0
    for (const row of rows) out[row.status] += 1
    return out
  }, [rows])

  const filtered = useMemo(
    () => (tab === 'ALL' ? rows : rows.filter((row) => row.status === tab)),
    [rows, tab],
  )

  const openDecision = (row: ChangeRequest, action: DecisionState['action']) => {
    setDecision({ row, action })
    setComment('')
    setAckIrreversible(false)
  }

  const decide = async () => {
    if (!decision || submitting) return
    const { row, action } = decision
    setSubmitting(true)
    try {
      await post(`/api/changes/${row.id}/${action}`, {
        comment: comment.trim(),
        // 只在「通过」时携带；后端对 irreversible 单缺这个标记一律 400
        acknowledgeIrreversible: action === 'approve' && row.irreversible
          ? ackIrreversible
          : undefined,
      })
      message.success(action === 'approve' ? '已通过，开始执行' : '已驳回')
      setDecision(null)
      setComment('')
      await load()
    } catch (e) {
      // 403（不能审批自己提交的单）等后端提示原样展示，方便用户知道原因。
      message.error(e instanceof Error ? e.message : '处理失败')
    } finally {
      setSubmitting(false)
    }
  }

  // BUG-TC6-6：提交人撤回自己 PENDING 的单（后端限制 requester + PENDING，
  // 越权/状态不符后端 400「只能撤回自己提交且仍待审批的单据」，前端只做入口不放宽）
  const cancelRequest = async (row: ChangeRequest) => {
    try {
      await post(`/api/changes/${row.id}/cancel`, {})
      message.success('已撤回')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '撤回失败')
    }
  }

  // REQ-D31：审批账号作废他人 PENDING 单（后端 change:approve + PENDING 双条件，
  // 状态被并发抢改 409；状态迁移不物理删，单据留在「已撤回/作废」页签可审计）
  const voidRequest = async (row: ChangeRequest) => {
    try {
      await del(`/api/changes/${row.id}`)
      message.success('已作废')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '作废失败')
    }
  }

  const columns: TableColumnsType<ChangeRequest> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 120,
      render: (_value, row) => (
        <Space size={4}>
          <span>{row.id}</span>
          {/* D53：回滚单打标，并提示它指向的原单 */}
          {row.rollbackOf ? (
            <Tag color="purple" style={{ marginInlineEnd: 0 }}>
              回滚#{row.rollbackOf}
            </Tag>
          ) : null}
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (_value, row) => {
        const meta = changeStatusMeta(row.status)
        return <Tag color={meta.color}>{meta.text}</Tag>
      },
    },
    {
      title: '目标表',
      dataIndex: 'targetTable',
      ellipsis: true,
      render: (_value, row) => (
        row.targetTable?.trim()
          ? <Typography.Text code>{row.targetTable}</Typography.Text>
          : '-'
      ),
    },
    { title: '提交人', dataIndex: 'requester', width: 120 },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (_value, row) => formatChangeTime(row.createdAt),
    },
    {
      title: '审批人',
      dataIndex: 'reviewer',
      width: 120,
      render: (_value, row) => row.reviewer || '-',
    },
    {
      title: '操作',
      width: canApprove ? 260 : 80,
      fixed: 'right',
      render: (_value, row) => (
        // 行本身可点进详情，操作按钮要阻止冒泡，否则点「通过」会先跳页。
        <Space size={4} onClick={(event) => event.stopPropagation()}>
          {/* UX-DW10REST-9（REQ-DW-10 R1）：删独立「详情」按钮，点行即进单据（onRow+守卫） */}
          {/* BUG-TC6-6：本人 + 待审批才可撤回（与后端 requester/PENDING 判定一致） */}
          {row.status === 'PENDING' && row.requester === currentUser() ? (
            <Popconfirm
              title={`撤回变更单 #${row.id}？`}
              description="撤回后单据关闭，需要改动请重新提交。"
              okText="撤回"
              okButtonProps={{ danger: true }}
              onConfirm={() => void cancelRequest(row)}
            >
              <Button size="small" type="link" danger>撤回</Button>
            </Popconfirm>
          ) : null}
          {canApprove && row.status === 'PENDING' ? (
            <>
              <Button
                size="small"
                type="primary"
                icon={<CheckOutlined />}
                onClick={() => openDecision(row, 'approve')}
              >
                通过
              </Button>
              <Button
                size="small"
                danger
                icon={<CloseOutlined />}
                onClick={() => openDecision(row, 'reject')}
              >
                驳回
              </Button>
              {/* REQ-D31：他人 PENDING 单的关闭出口——驳回是「审不过」，作废是「不再处理」；
                  本人单不重复放入口（走「撤回」，同一终态 CANCELLED） */}
              {row.requester !== currentUser() ? (
                <Popconfirm
                  title={`作废变更单 #${row.id}？`}
                  description="只关闭单据、不执行 SQL、不算审批；提交人如仍需改动请重新提交。"
                  okText="作废"
                  okButtonProps={{ danger: true }}
                  onConfirm={() => void voidRequest(row)}
                >
                  <Button size="small" type="link" danger>作废</Button>
                </Popconfirm>
              ) : null}
            </>
          ) : null}
        </Space>
      ),
    },
  ]

  const renderTable = (data: ChangeRequest[]) => (
    <Table<ChangeRequest>
      rowKey="id"
      size="small"
      loading={loading}
      dataSource={data}
      columns={columns}
      scroll={{ x: 960 }}
      pagination={{ pageSize: 20, hideOnSinglePage: true, showSizeChanger: false }}
      locale={{
        emptyText: tableEmpty(
          tab === 'ALL' ? '还没有变更单' : `没有「${CHANGE_STATUS_META[tab].text}」的变更单`,
          '在 SQL 编辑器里提交破坏性语句后，会在这里生成待审批单据',
        ),
      }}
      onRow={(row) => ({
        style: { cursor: 'pointer' },
        onClick: (event) => {
          // UX-DW10REST-9（REQ-DW-10 R1）：点击记录=打开详情；行内控件保持自身行为
          const target = event.target as HTMLElement
          if (target.closest('a, button, input, textarea, .ant-select')) return
          nav(`/changes/${row.id}`)
        },
      })}
    />
  )

  return (
    <div>
      <PageHeader
        title="SQL 变更"
        description="破坏性 SQL 需要审批，通过后立即执行并自动备份 DROP/TRUNCATE 单表。"
        extra={(
          <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
            刷新
          </Button>
        )}
      />

      {error ? (
        <Card>
          <ErrorState message={error} onRetry={() => void load()} />
        </Card>
      ) : !loaded && loading ? (
        <Card>
          <LoadingState rows={6} />
        </Card>
      ) : (
        <Card>
          <Tabs
            activeKey={tab}
            onChange={(key) => setTab(key as TabKey)}
            items={[
              {
                key: 'ALL',
                label: `全部（${rows.length}）`,
                children: renderTable(filtered),
              },
              ...STATUS_ORDER.map((status) => ({
                key: status,
                label: `${CHANGE_STATUS_META[status].text}（${counts[status]}）`,
                children: renderTable(filtered),
              })),
            ]}
          />
        </Card>
      )}

      <Modal
        title={decision
          ? `${decision.action === 'approve' ? '通过' : '驳回'}变更单 #${decision.row.id}`
          : ''}
        open={Boolean(decision)}
        onOk={() => void decide()}
        confirmLoading={submitting}
        onCancel={() => {
          if (!submitting) setDecision(null)
        }}
        okText={decision?.action === 'approve' ? '通过并执行' : '确认驳回'}
        okButtonProps={{
          danger: decision?.action === 'reject',
          // D53：不可逆单没勾选「已知不可逆」不允许提交（后端同样 400 兜底）
          disabled: decision?.action === 'approve'
            && Boolean(decision.row.irreversible) && !ackIrreversible,
        }}
        cancelText="取消"
        destroyOnClose
      >
        {decision ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">
                {decision.row.requester} 提交
              </Typography.Text>
              <div>
                {decision.row.targetTable?.trim()
                  ? <Typography.Text code>{decision.row.targetTable}</Typography.Text>
                  : <Typography.Text type="secondary">未识别到目标表</Typography.Text>}
              </div>
            </div>
            {decision.action === 'approve' ? (
              <>
                <Alert
                  type="warning"
                  showIcon
                  message="通过后立即执行，不能撤销"
                  description="DROP / TRUNCATE 单表会先自动备份；备份失败则不执行原语句。"
                />
                {/* D53：审批前必须能看到回滚方案 */}
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Typography.Text strong>回滚方案</Typography.Text>
                  <RollbackPlanBlock row={decision.row} />
                </Space>
                {decision.row.irreversible ? (
                  <Checkbox
                    checked={ackIrreversible}
                    onChange={(event) => setAckIrreversible(event.target.checked)}
                  >
                    已知不可逆——执行后数据无法自动还原
                  </Checkbox>
                ) : null}
              </>
            ) : null}
            <Input.TextArea
              rows={3}
              maxLength={500}
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              placeholder={decision.action === 'approve' ? '审批意见（可选）' : '驳回原因（可选）'}
            />
          </Space>
        ) : null}
      </Modal>
    </div>
  )
}
