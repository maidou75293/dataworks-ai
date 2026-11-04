import { useCallback, useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert, Button, Card, Checkbox, Descriptions, Input, Modal, Popconfirm, Space, Tag,
  Typography, message,
} from 'antd'
import {
  CheckOutlined, CloseOutlined, ReloadOutlined, UndoOutlined,
} from '@ant-design/icons'
import { api, post } from '../api'
import PageHeader from '../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import { canAny } from '../permissions'
import { currentUser } from '../auth'
import {
  BACKUP_PLACEHOLDER, RollbackPlanBlock, changeStatusMeta, formatChangeTime,
} from './ChangeRequests'
import type { ChangeRequest } from './ChangeRequests'

type DecisionAction = 'approve' | 'reject'

// 保留换行/缩进（whiteSpace: pre-wrap），长行不撑破页面；内容不截断，超出滚动查看。
const PRE_STYLE: CSSProperties = {
  margin: 0,
  padding: 12,
  background: '#fafafa',
  border: '1px solid #f0f0f0',
  borderRadius: 6,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  fontSize: 12,
  lineHeight: 1.7,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  overflow: 'auto',
  maxHeight: 420,
}

function execPlaceholder(status: ChangeRequest['status']): string {
  if (status === 'PENDING') return '尚未执行，等待审批通过'
  if (status === 'APPROVED') return '正在执行，刷新查看结果'
  if (status === 'REJECTED') return '未执行（已驳回）'
  return '暂无执行结果'
}

export default function ChangeRequestDetail() {
  const { id } = useParams<{ id: string }>()
  const nav = useNavigate()
  const canApprove = canAny('change:approve')
  const numericId = Number(id)
  /** undefined = 还没拿到结果（加载中）；null = 查不到（不存在或无权查看）。 */
  const [row, setRow] = useState<ChangeRequest | null | undefined>(undefined)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [decision, setDecision] = useState<DecisionAction | null>(null)
  const [comment, setComment] = useState('')
  const [submitting, setSubmitting] = useState(false)
  // D53：irreversible 单的「已知不可逆」勾选（不勾选后端也会 400，前端先禁用按钮）
  const [ackIrreversible, setAckIrreversible] = useState(false)
  const [rolling, setRolling] = useState(false)
  const canSubmit = canAny('change:submit')

  /**
   * @param silent 轮询刷新：不动 loading、不覆盖错误态；「执行中」的单靠它自己走到结果。
   */
  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      // 后端没有 GET /api/changes/{id}，只能拉列表按 id 查找；列表已按租户和可见范围收口。
      const data = await api<ChangeRequest[] | null>('/api/changes')
      const found = Array.isArray(data)
        ? data.find((item) => Number(item.id) === numericId) ?? null
        : null
      // 审批人只能从列表看到本租户的 PENDING 单据：刚审批完的单据会立刻离开列表。
      // 这里保留 POST 返回的最新快照，避免「刚审批完就显示不存在」。
      setRow((prev) => found ?? prev ?? null)
      setError('')
    } catch (e) {
      if (!silent) {
        setError(e instanceof Error ? e.message : '加载变更单失败')
      } else {
        console.warn('[changes] 详情静默刷新失败', e)
      }
    } finally {
      if (!silent) setLoading(false)
    }
  }, [numericId])

  // 同一组件切换 :id 时先清掉上一张单，避免加载期间短暂显示错误内容。
  useEffect(() => {
    setRow(undefined)
    setError('')
    void load()
  }, [load])

  // BUG-TC6-9：详情页等审批结果的人不该反复手点「刷新」⇒ 30 秒静默轮询。
  // 用 setInterval 而不是把 load 放进上面的挂载 effect：轮询不该清掉 row 造成闪烁。
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

  /** D53：发起回滚——生成一张新变更单（走同一套审批），成功后跳到回滚单详情。 */
  const doRollback = async () => {
    if (!row || rolling) return
    setRolling(true)
    try {
      const created = await post<ChangeRequest>(`/api/changes/${row.id}/rollback`, {})
      message.success(`回滚单 #${created.id} 已生成，仍需审批后执行（不自动回滚）`)
      nav(`/changes/${created.id}`)
    } catch (e) {
      // 400（不可逆/无回滚 SQL/在途回滚单）等后端提示原样展示
      message.error(e instanceof Error ? e.message : '发起回滚失败')
    } finally {
      setRolling(false)
    }
  }

  const decide = async () => {
    if (!row || !decision || submitting) return
    const action = decision
    setSubmitting(true)
    try {
      // POST 返回审批后的完整快照（含执行结果）；先落到页面，再拉列表校准。
      const updated = await post<ChangeRequest>(`/api/changes/${row.id}/${action}`, {
        comment: comment.trim(),
        // 只在「通过」时携带；后端对 irreversible 单缺这个标记一律 400
        acknowledgeIrreversible: action === 'approve' && row.irreversible
          ? ackIrreversible
          : undefined,
      })
      setRow(updated)
      setDecision(null)
      setComment('')
      message.success(action === 'approve' ? '已通过，已提交执行' : '已驳回')
      await load()
    } catch (e) {
      // 403（不能审批自己提交的单）等后端提示原样展示，弹窗保持打开便于重试。
      message.error(e instanceof Error ? e.message : '处理失败')
    } finally {
      setSubmitting(false)
    }
  }

  // BUG-TC6-6：提交人撤回自己 PENDING 的单（后端 requester+PENDING 双条件，400 兜底）
  const cancelRequest = async () => {
    if (!row) return
    try {
      await post(`/api/changes/${row.id}/cancel`, {})
      message.success('已撤回')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '撤回失败')
    }
  }

  const statusMeta = row ? changeStatusMeta(row.status) : undefined

  return (
    <div>
      <PageHeader
        title={row ? `变更单 #${row.id}` : `变更单 #${id ?? '-'}`}
        description="破坏性 SQL 审批通过后立即执行；DROP / TRUNCATE 单表执行前自动备份。"
        backTo="/changes"
        tags={statusMeta ? <Tag color={statusMeta.color}>{statusMeta.text}</Tag> : undefined}
        extra={(
          <Space wrap>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void load()}>
              刷新
            </Button>
            {row && row.status === 'PENDING' && row.requester === currentUser() ? (
              <Popconfirm
                title={`撤回变更单 #${row.id}？`}
                description="撤回后单据关闭，需要改动请重新提交。"
                okText="撤回"
                okButtonProps={{ danger: true }}
                onConfirm={() => void cancelRequest()}
              >
                <Button danger>撤回</Button>
              </Popconfirm>
            ) : null}
            {row && canApprove && row.status === 'PENDING' ? (
              <>
                <Button
                  type="primary"
                  icon={<CheckOutlined />}
                  onClick={() => {
                    setDecision('approve')
                    setComment('')
                    setAckIrreversible(false)
                  }}
                >
                  通过
                </Button>
                <Button
                  danger
                  icon={<CloseOutlined />}
                  onClick={() => {
                    setDecision('reject')
                    setComment('')
                    setAckIrreversible(false)
                  }}
                >
                  驳回
                </Button>
              </>
            ) : null}
          </Space>
        )}
      />

      {!row ? (
        error ? (
          <Card>
            <ErrorState title="「变更单详情」加载失败" message={error} onRetry={() => void load()} />
          </Card>
        ) : loading ? (
          <Card><LoadingState rows={6} /></Card>
        ) : (
          <Card>
            <EmptyState
              title="变更单不存在或无权查看"
              hint="单据可能已离开你的可见范围（审批完成后只有提交人能看到），或链接里的编号有误。"
              action={<Button onClick={() => nav('/changes')}>返回列表</Button>}
            />
          </Card>
        )
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {error ? (
            <Alert
              type="warning"
              showIcon
              message="刷新失败"
              description={`${error}（用右上角「刷新」重试）`}
            />
          ) : null}
          {row.status === 'APPROVED' ? (
            <Alert
              type="info"
              showIcon
              message="正在执行，刷新查看结果"
              description="通过即执行；耗时取决于 SQL 复杂度与数据量。"
            />
          ) : null}

          <Card size="small" title="基本信息">
            <Descriptions size="small" column={2}>
              <Descriptions.Item label="状态">
                <Tag color={statusMeta?.color}>{statusMeta?.text}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="提交人">{row.requester || '-'}</Descriptions.Item>
              <Descriptions.Item label="提交时间">
                {formatChangeTime(row.createdAt)}
              </Descriptions.Item>
              <Descriptions.Item label="审批人">{row.reviewer || '-'}</Descriptions.Item>
              <Descriptions.Item label="审批时间">
                {formatChangeTime(row.reviewedAt)}
              </Descriptions.Item>
              <Descriptions.Item label="审批意见" span={2}>
                {row.reviewComment?.trim() || '-'}
              </Descriptions.Item>
              <Descriptions.Item label="目标表">
                {row.targetTable?.trim()
                  ? <Typography.Text code>{row.targetTable}</Typography.Text>
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="数据源 ID">{row.datasourceId}</Descriptions.Item>
              <Descriptions.Item label="项目 ID">{row.projectId}</Descriptions.Item>
              <Descriptions.Item label="备份表">
                {row.backupTable?.trim()
                  ? <Typography.Text code>{row.backupTable}</Typography.Text>
                  : '-'}
              </Descriptions.Item>
              {/* D53：回滚闭环的三个新口径 */}
              <Descriptions.Item label="可逆性">
                {row.irreversible
                  ? <Tag color="red">不可逆（数据无法自动还原）</Tag>
                  : <Tag color="green">可生成回滚</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="回滚自">
                {row.rollbackOf ? (
                  <Button
                    type="link"
                    size="small"
                    style={{ padding: 0 }}
                    onClick={() => nav(`/changes/${row.rollbackOf}`)}
                  >
                    原变更单 #{row.rollbackOf}
                  </Button>
                ) : '-'}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card size="small" title="SQL 原文">
            <pre style={{ ...PRE_STYLE, maxHeight: 560 }}>{row.sqlText}</pre>
          </Card>

          {/* D53：回滚方案 + 回滚入口。回滚 = 生成一张新变更单，走同一套审批，
              不自动执行；只有 DONE 且可逆且回滚 SQL 已回填（无占位符）才给入口。 */}
          <Card
            size="small"
            title="回滚方案"
            extra={row.status === 'DONE' && !row.irreversible
              && row.inverseSql?.trim()
              && !row.inverseSql.includes(BACKUP_PLACEHOLDER)
              && canSubmit ? (
                <Popconfirm
                  title="发起回滚？"
                  description="将按下面的回滚 SQL 生成一张新变更单，仍需审批后执行（不自动回滚）。"
                  onConfirm={() => void doRollback()}
                  okText="发起"
                  cancelText="取消"
                >
                  <Button type="primary" danger size="small" icon={<UndoOutlined />} loading={rolling}>
                    发起回滚
                  </Button>
                </Popconfirm>
              ) : null}
          >
            <RollbackPlanBlock row={row} />
          </Card>

          <Card
            size="small"
            title="执行结果"
            extra={row.executedAt ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                执行时间：{formatChangeTime(row.executedAt)}
              </Typography.Text>
            ) : null}
          >
            {row.execResult?.trim()
              ? <pre style={PRE_STYLE}>{row.execResult}</pre>
              : <Typography.Text type="secondary">{execPlaceholder(row.status)}</Typography.Text>}
          </Card>
        </Space>
      )}

      <Modal
        title={decision
          ? `${decision === 'approve' ? '通过' : '驳回'}变更单 #${row?.id ?? ''}`
          : ''}
        open={Boolean(decision) && Boolean(row)}
        onOk={() => void decide()}
        confirmLoading={submitting}
        onCancel={() => {
          if (!submitting) setDecision(null)
        }}
        okText={decision === 'approve' ? '通过并执行' : '确认驳回'}
        okButtonProps={{
          danger: decision === 'reject',
          // D53：不可逆单没勾选「已知不可逆」不允许提交（后端同样 400 兜底）
          disabled: decision === 'approve' && Boolean(row?.irreversible) && !ackIrreversible,
        }}
        cancelText="取消"
        destroyOnClose
      >
        {row && decision ? (
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <div>
              <Typography.Text type="secondary">{row.requester} 提交</Typography.Text>
              <div>
                {row.targetTable?.trim()
                  ? <Typography.Text code>{row.targetTable}</Typography.Text>
                  : <Typography.Text type="secondary">未识别到目标表</Typography.Text>}
              </div>
            </div>
            {decision === 'approve' ? (
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
                  <RollbackPlanBlock row={row} />
                </Space>
                {row.irreversible ? (
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
              placeholder={decision === 'approve' ? '审批意见（可选）' : '驳回原因（可选）'}
            />
          </Space>
        ) : null}
      </Modal>
    </div>
  )
}
