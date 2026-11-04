import type { ReactNode } from 'react'
import { Alert, Button, Empty, Skeleton } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'

type EmptyStateProps = {
  title?: string
  hint?: ReactNode
  action?: ReactNode
  compact?: boolean
}

/** 统一空状态：标题 + 一句可操作提示 + 可选按钮。 */
export function EmptyState({ title = '暂无数据', hint, action, compact }: EmptyStateProps) {
  return (
    <div className={compact ? 'state-block compact' : 'state-block'}>
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={
          <div className="state-text">
            <div className="state-title">{title}</div>
            {hint ? <div className="state-hint">{hint}</div> : null}
          </div>
        }
      >
        {action}
      </Empty>
    </div>
  )
}

/** 统一错误状态：保留错误原因，并提供重试。 */
export function ErrorState({
  title = '加载失败',
  message,
  onRetry,
  compact,
}: {
  title?: string
  message: ReactNode
  onRetry?: () => void
  compact?: boolean
}) {
  return (
    <div className={compact ? 'state-block compact' : 'state-block'}>
      <Alert
        type="error"
        showIcon
        message={title}
        description={message}
        action={
          onRetry ? (
            <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
              重试
            </Button>
          ) : undefined
        }
      />
    </div>
  )
}

/** 统一加载骨架，用于卡片内首次加载。 */
export function LoadingState({ rows = 4 }: { rows?: number }) {
  return (
    <div className="state-block">
      <Skeleton active paragraph={{ rows }} />
    </div>
  )
}

/** 表格 locale.emptyText 的统一写法。 */
export function tableEmpty(title: string, hint?: ReactNode) {
  return <EmptyState compact title={title} hint={hint} />
}
