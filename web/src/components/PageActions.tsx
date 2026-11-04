import { useState } from 'react'
import type { Dispatch, ReactNode, SetStateAction } from 'react'
import { Button, Popconfirm, Space, Tooltip } from 'antd'
import type { ButtonProps } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SaveOutlined,
} from '@ant-design/icons'

/**
 * 标准动作配置。权限统一由调用方用 can(...)/canAny(...) 算好后传 `show`，
 * 组件不做权限判定；`show === false` 与「没传该动作」一样不渲染，避免出现空按钮。
 */
export type PageActionConfig = {
  /** 动作文案；不传用预置统一文案（刷新/新建/编辑/删除/保存）。 */
  label?: ReactNode
  /** 返回 Promise 的动作（refresh/save）按钮自动 busy；也可显式传 loading。 */
  onClick?: () => void | Promise<unknown>
  disabled?: boolean
  loading?: boolean
  /** 权限开关：调用方传 can('datasource:write') 之类的结果。 */
  show?: boolean
  /** 原生 title 提示（沿用部分页面现有写法）。 */
  title?: string
  /** 覆盖实例默认按钮形态（头部默认 primary(default)，行内常传 link/text）。 */
  type?: ButtonProps['type']
  icon?: ReactNode
  danger?: boolean
}

export type PageDeleteAction = PageActionConfig & {
  /** Popconfirm 标题；不传默认「删除「文案」？」。 */
  confirmTitle?: ReactNode
  confirmDescription?: ReactNode
  /** 传 false 表示调用方有自己的确认流程（如输入名字确认弹窗），只渲染危险按钮本体。 */
  popconfirm?: boolean
  /** 禁用时的 Tooltip 文案（如「内置 Doris 由平台维护，不能删除」）；有它时不再包 Popconfirm。 */
  disabledTip?: ReactNode
}

type PresetName = 'refresh' | 'create' | 'edit' | 'delete' | 'save'

const PRESET_LABELS: Record<PresetName, string> = {
  refresh: '刷新', create: '新建', edit: '编辑', delete: '删除', save: '保存',
}

const PRESET_ICONS: Record<PresetName, ReactNode> = {
  refresh: <ReloadOutlined />, create: <PlusOutlined />, edit: <EditOutlined />,
  delete: <DeleteOutlined />, save: <SaveOutlined />,
}

/** 把 promise 型 onClick 包成「执行期间 busy」的 onClick；显式 loading 时不接管。 */
function withBusy(onClick: (() => void | Promise<unknown>) | undefined, setBusy: (v: boolean) => void) {
  if (!onClick) return undefined
  return () => {
    const r = onClick()
    if (r && typeof (r as Promise<unknown>).then === 'function') {
      setBusy(true)
      ;(r as Promise<unknown>).finally(() => setBusy(false))
    }
  }
}

function renderAction(
  preset: PresetName,
  config: PageActionConfig,
  defaults: { size?: ButtonProps['size']; buttonType?: ButtonProps['type'] },
  busy?: { loading?: boolean; setBusy?: Dispatch<SetStateAction<boolean>> },
): ReactNode {
  const managedBusy = busy?.loading === undefined && busy?.setBusy
    ? withBusy(config.onClick, (v) => busy.setBusy!(v))
    : undefined
  return (
    <Button
      size={defaults.size}
      type={config.type ?? defaults.buttonType ?? (preset === 'create' ? 'primary' : 'default')}
      danger={config.danger ?? (preset === 'delete' ? true : undefined)}
      icon={config.icon ?? PRESET_ICONS[preset]}
      disabled={config.disabled}
      title={config.title}
      loading={busy?.loading ?? config.loading}
      onClick={managedBusy ?? config.onClick}
    >
      {config.label ?? PRESET_LABELS[preset]}
    </Button>
  )
}

export type PageActionsProps = {
  refresh?: PageActionConfig
  /** 支持一个或多个「新建」入口（如表管理页的 新建库 + 新建表）。 */
  create?: PageActionConfig | PageActionConfig[]
  edit?: PageActionConfig
  delete?: PageDeleteAction
  save?: PageActionConfig
  /** 实例级默认形态：头部按钮组不传；表格行内传 size="small" buttonType="link"/"text"。 */
  size?: ButtonProps['size']
  buttonType?: ButtonProps['type']
}

/**
 * 页面标准操作区：同一语义只保留一个入口，文案/图标/loading/危险确认在这里统一收敛。
 *
 * 用法一（页面头部）：
 *   <PageActions
 *     refresh={{ onClick: () => load() }}
 *     create={{ label: '新建数据源', onClick: openCreate, show: can('datasource:write') }}
 *   />
 * 用法二（表格行内）：
 *   <PageActions size="small" buttonType="link"
 *     edit={{ onClick: () => openEdit(row) }}
 *     delete={{ confirmTitle: `删除 ${row.name}？`, onClick: () => remove(row) }}
 *   />
 *
 * 约束：只负责按钮的组织与样式收敛，不承载业务逻辑；不传（或 show=false）的动作不渲染。
 */
export default function PageActions({
  refresh, create, edit, delete: del, save, size, buttonType,
}: PageActionsProps) {
  // refresh/save 的 promise 自动 busy（显式 loading 优先）
  const [refreshBusy, setRefreshBusy] = useState(false)
  const [saveBusy, setSaveBusy] = useState(false)
  const defaults = { size, buttonType }
  const items: ReactNode[] = []

  if (refresh && refresh.show !== false) {
    items.push(
      <span key="refresh" style={{ display: 'inline-flex' }}>
        {renderAction('refresh', refresh, defaults,
          refresh.loading === undefined ? { loading: refreshBusy, setBusy: setRefreshBusy } : undefined)}
      </span>,
    )
  }
  const creates = create === undefined ? [] : (Array.isArray(create) ? create : [create])
  creates.forEach((c, i) => {
    if (c.show === false) return
    items.push(
      <span key={`create-${i}`} style={{ display: 'inline-flex' }}>
        {renderAction('create', c, defaults)}
      </span>,
    )
  })
  if (edit && edit.show !== false) {
    items.push(
      <span key="edit" style={{ display: 'inline-flex' }}>
        {renderAction('edit', edit, defaults)}
      </span>,
    )
  }
  if (del && del.show !== false) {
    const button = renderAction('delete', del, defaults)
    if (del.disabled && del.disabledTip) {
      // 禁用 + 说明原因：不能出 Popconfirm（点了也没用），只给 Tooltip
      items.push(
        <Tooltip key="delete" title={del.disabledTip}>{button}</Tooltip>,
      )
    } else if (del.popconfirm !== false && !del.disabled) {
      items.push(
        <Popconfirm key="delete"
                    title={del.confirmTitle ?? `${PRESET_LABELS.delete}「${del.label ?? ''}」？`}
                    description={del.confirmDescription}
                    onConfirm={() => del.onClick?.()}
                    okButtonProps={{ danger: true }}>
          {button}
        </Popconfirm>,
      )
    } else {
      items.push(<span key="delete" style={{ display: 'inline-flex' }}>{button}</span>)
    }
  }
  if (save && save.show !== false) {
    items.push(
      <span key="save" style={{ display: 'inline-flex' }}>
        {renderAction('save', save, defaults,
          save.loading === undefined ? { loading: saveBusy, setBusy: setSaveBusy } : undefined)}
      </span>,
    )
  }

  if (items.length === 0) return null
  return <Space size={4} wrap>{items}</Space>
}
