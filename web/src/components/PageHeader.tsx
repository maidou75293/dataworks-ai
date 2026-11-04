import type { ReactNode } from 'react'
import { Button, Typography } from 'antd'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'

type PageHeaderProps = {
  title: ReactNode
  description?: ReactNode
  tags?: ReactNode
  extra?: ReactNode
  /**
   * 标准操作区（推荐传 <PageActions .../>）：刷新/新建/编辑/删除/保存等通用动作放这里，
   * 页面自定义控件（筛选下拉、业务专属按钮）继续走 extra。两者共存时 actions 渲染在 extra 之后。
   */
  actions?: ReactNode
  backTo?: string
  /** 自定义返回行为（例如有未保存修改时先确认）。 */
  onBack?: () => void
}

/**
 * 所有页面统一标题区：左侧标题/说明（可带返回），右侧操作区。
 * 操作区在窄屏自动换行到标题下方。
 */
export default function PageHeader({ title, description, tags, extra, actions, backTo, onBack }: PageHeaderProps) {
  const nav = useNavigate()
  return (
    <div className="page-head">
      <div className="page-head-main">
        {backTo ? (
          <Button
            className="page-head-back"
            icon={<ArrowLeftOutlined />}
            onClick={() => (onBack ? onBack() : nav(backTo))}
          />
        ) : null}
        <div className="page-head-text">
          <div className="page-head-title">
            <Typography.Title level={4}>{title}</Typography.Title>
            {tags}
          </div>
          {description ? (
            <Typography.Text type="secondary" className="page-head-desc">
              {description}
            </Typography.Text>
          ) : null}
        </div>
      </div>
      {(extra || actions) ? <div className="page-head-extra">{extra}{actions}</div> : null}
    </div>
  )
}
