import { useCallback, useEffect, useState } from 'react'
import {
  Avatar, Badge, Breadcrumb, Button, Dropdown, Form, Input, Layout, Menu, Modal, Tag, Tooltip,
  message,
} from 'antd'
import type { MenuProps } from 'antd'
import {
  AlertOutlined, ApiOutlined, ApartmentOutlined, AuditOutlined, BarChartOutlined, BgColorsOutlined, BellOutlined, CloudServerOutlined, ClusterOutlined,
  CodeOutlined, DashboardOutlined, DatabaseOutlined, DownOutlined, ExperimentOutlined, FieldTimeOutlined,
  FunctionOutlined, HomeOutlined,
  KeyOutlined, LockOutlined, MenuFoldOutlined, MenuUnfoldOutlined, QuestionCircleOutlined,
  PlayCircleOutlined, PictureOutlined, RocketOutlined, SafetyCertificateOutlined, SearchOutlined, SettingOutlined,
  SwapOutlined, SyncOutlined, TableOutlined, TagsOutlined,
  TeamOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, post } from '../api'
import { currentDisplayName, currentUser, logout, switchTenant } from '../auth'
import { canAny, currentRoleLabel, currentTenant, roleColor } from '../permissions'
import { THEMES, useAppTheme, type ThemeKey } from '../theme'
import AgentChat from '../components/AgentChat'
import HelpCenterDrawer from '../components/HelpCenterDrawer'
import { canLeaveCurrentPage } from '../unsaved'
import { helpAnchorForPath } from '../helpAnchors'
import { menuSelected } from './navResolve'

const { Header, Sider, Content } = Layout

type NavItem = {
  key: string
  label: string
  icon: React.ReactNode
  atoms?: string[]
  /** BUG-TC6-9：挂「待我审批」角标的数据源（计数来自后端已按审批权收口的列表接口） */
  approvalBadge?: 'access' | 'change'
}

// 一级导航统一是平铺分组：组标题即信息架构的一层，组内每一项都是可直接打开的页面。
// 分组口径：概览 / 作业（数据开发）/ 资源（数据资源）/ 治理（数据治理）/
// 安全（安全与合规）/ 值班链路（运维监控）/ 平台（平台管理）。
type NavSection = {
  kind: 'group'
  label: string
  items: NavItem[]
  // 组标题左侧图标，目前只有运维监控组用
  icon?: React.ReactNode
  // 组标题右侧显示待处理告警数，目前只有运维监控组用
  showAlertBadge?: boolean
}

const NAV: NavSection[] = [
  {
    kind: 'group',
    label: '概览',
    items: [
      { key: '/home', label: '系统介绍', icon: <HomeOutlined /> },
      { key: '/help', label: '使用帮助', icon: <QuestionCircleOutlined /> },
    ],
  },
  {
    kind: 'group',
    label: '数据开发',
    items: [
      { key: '/dev', label: '数据开发', icon: <CodeOutlined /> },
      { key: '/integration', label: '数据集成', icon: <SwapOutlined /> },
      { key: '/stream', label: '流开发', icon: <RocketOutlined /> },
      { key: '/query', label: '数据查询', icon: <SearchOutlined /> },
    ],
  },
  {
    kind: 'group',
    label: '数据资源',
    items: [
      { key: '/datasources', label: '数据源', icon: <DatabaseOutlined /> },
      { key: '/tables', label: '表管理', icon: <TableOutlined /> },
      { key: '/functions', label: '函数管理', icon: <FunctionOutlined /> },
      // 与「多模态资产」区分：这一项是表/血缘所在的元数据目录
      { key: '/map', label: '元数据目录', icon: <ClusterOutlined /> },
      { key: '/assets', label: '多模态资产', icon: <PictureOutlined /> },
    ],
  },
  {
    kind: 'group',
    label: '数据治理',
    items: [
      { key: '/quality', label: '数据质量', icon: <SafetyCertificateOutlined /> },
      { key: '/service', label: '数据服务', icon: <ApiOutlined /> },
      { key: '/bi', label: '数据可视化', icon: <BarChartOutlined /> },
    ],
  },
  {
    // REQ-UNIFY-2：mos（标注+微调平台）统一入口。菜单组与 /api/mos/** 通道同用
    // mos:access 权限点收口（通道鉴权在 REQ-UNIFY-1 后端侧）。
    kind: 'group',
    label: '模型训练（MOS）',
    // REQ-MOS-MENU-ORDER-1：组内按业务流动线排序——标注项目→训练集→训练任务→模型评测→模型资产→数据飞轮
    items: [
      { key: '/mos/labels', label: '标注项目', icon: <TagsOutlined />, atoms: ['mos:access'] },
      // REQ-UX-FLOW-1#1：同名异义收敛——ds_ 实体在训练组菜单叫「训练集」（旧称「数据集」直达本页不报错）
      { key: '/mos/datasets', label: '训练集', icon: <DatabaseOutlined />, atoms: ['mos:access'] },
      { key: '/mos/jobs', label: '训练任务', icon: <ThunderboltOutlined />, atoms: ['mos:access'] },
      { key: '/mos/evaluations', label: '模型评测', icon: <BarChartOutlined />, atoms: ['mos:access'] },
      { key: '/mos/models', label: '模型资产', icon: <ExperimentOutlined />, atoms: ['mos:access'] },
      { key: '/flywheel', label: '数据飞轮', icon: <SyncOutlined />, atoms: ['mos:flywheel'] },
    ],
  },
  {
    kind: 'group',
    label: '安全与合规',
    items: [
      { key: '/access', label: '权限申请', icon: <KeyOutlined />, approvalBadge: 'access' },
      {
        key: '/changes', label: 'SQL 变更', icon: <AuditOutlined />,
        atoms: ['change:submit', 'change:approve'],
        approvalBadge: 'change',
      },
      {
        key: '/security', label: '安全中心', icon: <LockOutlined />,
        atoms: ['security:read', 'audit:read', 'data:classify', 'mask:manage'],
      },
    ],
  },
  {
    kind: 'group',
    label: '运维监控',
    icon: <ThunderboltOutlined />,
    showAlertBadge: true,
    items: [
      { key: '/ops', label: '运维总览', icon: <DashboardOutlined /> },
      { key: '/ops/instances', label: '运行实例', icon: <PlayCircleOutlined /> },
      { key: '/ops/alerts', label: '告警中心', icon: <AlertOutlined /> },
      { key: '/ops/baselines', label: '任务基线', icon: <FieldTimeOutlined /> },
      { key: '/ops/system', label: '系统监控', icon: <CloudServerOutlined /> },
      {
        key: '/ops/gravitino', label: '元数据巡检', icon: <DatabaseOutlined />,
        atoms: ['datasource:write'],
      },
    ],
  },
  {
    kind: 'group',
    label: '平台管理',
    items: [
      {
        key: '/admin', label: '管理中心', icon: <SettingOutlined />,
        atoms: ['user:manage', 'role:manage', 'tenant:manage', 'department:manage'],
      },
    ],
  },
]

export default function MainLayout() {
  const nav = useNavigate()
  const location = useLocation()
  // 手动折过的用户下次进来保持折叠；断点自动收起不写入偏好
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem('dw_sider_collapsed') === '1'
    } catch {
      return false
    }
  })
  const toggleSider = () => {
    setCollapsed((v) => {
      try {
        localStorage.setItem('dw_sider_collapsed', v ? '0' : '1')
      } catch {
        // 存储禁用时只影响下次进入的默认状态
      }
      return !v
    })
  }
  // 告警徽标：值班同学不用先点进告警中心就知道有没有事
  // MT-P1-1：多租户成员显示顶部切换器（只有一个租户时不显示）
  const [tenants, setTenants] = useState<{ code: string; name: string }[]>([])
  useEffect(() => {
    api<{ code: string; name: string }[]>('/api/rbac/tenants')
      .then((rows) => setTenants(Array.isArray(rows) ? rows : []))
      .catch(() => setTenants([]))
  }, [])

  const tenantMenu: MenuProps = {
    items: tenants.map((t) => ({
      key: t.code,
      label: t.code === currentTenant() ? `${t.name}（当前）` : t.name,
      disabled: t.code === currentTenant(),
    })),
    onClick: ({ key }) => {
      switchTenant(key)
        .then(() => {
          message.success('已切换租户')
          nav('/home')
          // 强制刷新：列表/缓存都按新租户重取，避免短暂显示旧租户数据
          window.setTimeout(() => window.location.reload(), 200)
        })
        .catch((e) => message.error(e?.message || '切换租户失败'))
    },
  }

  const [alertCounts, setAlertCounts] = useState({ open: 0, critical: 0 })
  const loadAlertCounts = useCallback(() => {
    if (document.hidden) return
    api<{ open?: number; criticalOpen?: number }>('/api/alerts/summary')
      .then((d) => setAlertCounts({
        open: Number(d?.open) || 0,
        critical: Number(d?.criticalOpen) || 0,
      }))
      // 徽标是辅助信息，失败不打扰；告警中心页面会显示具体错误
      .catch(() => undefined)
  }, [])
  // BUG-TC6-9：审批待办角标。审批人不用先点进页面才知道有没有新申请等他。
  // 计数源直接用两个列表接口，**不再前端判权**：
  //  - access 的 scope=approvals 在后端已逐行过 canApprove（平台管理员 / 资源租户的
  //    角色型审批人 / 资源负责人 / L4 换人二审），返回条数就是「待我审批」；
  //  - changes 列表 = 自己的单 ∪（有 change:approve 时）本租户 PENDING，故要排掉自己提交的。
  const [approvalCounts, setApprovalCounts] = useState({ access: 0, change: 0 })
  const loadApprovalCounts = useCallback(() => {
    if (document.hidden) return
    const canSeeAccess = canAny('access:request', 'access:approve', 'access:revoke', 'user:manage')
    const canApproveChange = canAny('change:approve')
    if (canSeeAccess) {
      api<unknown[]>('/api/access/requests?scope=approvals')
        .then((rows) => setApprovalCounts((prev) => ({
          ...prev, access: Array.isArray(rows) ? rows.length : 0,
        })))
        // 角标是辅助信息，失败不打扰；对应页面会显示具体错误
        .catch(() => undefined)
    }
    if (canApproveChange) {
      api<{ status?: string; requester?: string }[]>('/api/changes')
        .then((rows) => setApprovalCounts((prev) => ({
          ...prev,
          change: Array.isArray(rows)
            ? rows.filter((row) => row?.status === 'PENDING' && row?.requester !== currentUser()).length
            : 0,
        })))
        .catch(() => undefined)
    }
  }, [])
  useEffect(() => {
    loadAlertCounts()
    loadApprovalCounts()
    const refresh = () => {
      loadAlertCounts()
      loadApprovalCounts()
    }
    const timer = window.setInterval(refresh, 60_000)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [loadAlertCounts, loadApprovalCounts, location.pathname])
  // 每次渲染都按当前角色过滤：登录后、管理员改完角色刷新页面都能拿到最新菜单
  const visibleSections = NAV
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => !item.atoms || canAny(...item.atoms)),
    }))
    .filter((section) => section.items.length > 0)
  const flat = visibleSections.flatMap((section) =>
    section.items.map((item) => ({ ...item, section: section.label })))
  const menuItems: MenuProps['items'] = visibleSections.map((section) => {
    const children = section.items.map((item) => {
      const pending = item.approvalBadge === 'access'
        ? approvalCounts.access
        : item.approvalBadge === 'change' ? approvalCounts.change : 0
      return {
        key: item.key,
        icon: item.icon,
        // 有代办时把数字挂在菜单项右侧；0 时不渲染，免得菜单常年挂个 0
        label: pending > 0 ? (
          <span
            style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}
            title={`${pending} 条待你审批`}
          >
            <span>{item.label}</span>
            <Badge count={pending} size="small" style={{ marginInlineEnd: 4 }} />
          </span>
        ) : item.label,
      }
    })
    const showAlertBadge = Boolean(section.showAlertBadge) && alertCounts.open > 0
    // 组标题默认纯文本；需要配图标或挂告警徽标时才包一层 span（group 的 label 支持 ReactNode）
    const label = section.icon || showAlertBadge ? (
      <span style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          {section.icon}
          <span>{section.label}</span>
        </span>
        {showAlertBadge ? <Badge count={alertCounts.open} size="small" /> : null}
      </span>
    ) : section.label
    return { type: 'group' as const, label, children }
  })
  // 血缘页签/标注工作台/部门用户等深链先映射回所属菜单，再做最长前缀高亮。
  // 纯逻辑在 navResolve.ts（BUG-LABEL-NAV-1 修复本体；UX-NAV-6 常驻锁
  // scripts/e2e/ux-nav-breadcrumb-test.py：撤掉 /mos/label-studio→/mos/labels
  // 映射侧栏会掉回「系统介绍」、面包屑「data-ai-agent / 概览」，锁必须红）
  const selected = menuSelected(location.pathname, flat.map((item) => item.key))
  const current = flat.find((item) => item.key === selected)

  const { theme, setTheme } = useAppTheme()
  const [pwdOpen, setPwdOpen] = useState(false)
  const [pwdSaving, setPwdSaving] = useState(false)
  const [pwdForm] = Form.useForm()
  const userMenu: MenuProps = {
    items: [
      { key: 'password', label: '修改密码' },
      { type: 'divider' },
      { key: 'logout', label: '退出登录' },
    ],
    onClick: ({ key }) => {
      if (key === 'password') {
        pwdForm.resetFields()
        setPwdOpen(true)
        return
      }
      if (!canLeaveCurrentPage()) return
      // 退出是高频误触项（尤其在下拉菜单里），先确认
      Modal.confirm({
        title: '退出登录？',
        content: '退出后需要重新输入账号密码才能进入平台。',
        okText: '退出登录',
        cancelText: '继续使用',
        okButtonProps: { danger: true },
        onOk: () => {
          logout()
          nav('/login', { replace: true })
        },
      })
    },
  }
  // REQ-HELP-NAV-1：右上角「使用帮助」改为打开帮助中心抽屉（K8s 结构介绍 + 运维手册）；
  // 原 REQ-DW-19 的按页锚点直达保留在抽屉内「平台使用帮助（产品操作）」按钮上。
  const [helpOpen, setHelpOpen] = useState(false)
  const themeMenu: MenuProps = {
    items: (Object.keys(THEMES) as ThemeKey[]).map((key) => ({
      key,
      label: (
        <span className="theme-option">
          <span className="theme-dot" style={{ background: THEMES[key].primary }} />
          <span>{THEMES[key].label}</span>
          {theme === key ? <span className="theme-check">✓</span> : null}
        </span>
      ),
    })),
    onClick: ({ key }) => setTheme(key as ThemeKey),
  }

  return (
    <Layout className="app-layout">
      <Header className="app-header">
        <div className="brand">
          <Button
            type="text"
            className="sider-toggle"
            title={collapsed ? '展开侧栏' : '收起侧栏'}
            aria-label={collapsed ? '展开侧栏' : '收起侧栏'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={toggleSider}
          />
          <div className="brand-logo">AI</div>
          <div className="brand-text">
            <span className="brand-title">data-ai-agent 数据平台</span>
            <span className="brand-sub">Internal Data Platform</span>
          </div>
        </div>
        <div className="header-actions">
          <Dropdown menu={themeMenu} placement="bottomRight">
            <Button
              type="text"
              className="theme-toggle"
              icon={<BgColorsOutlined />}
              title="切换主题色（经典蓝 / 经典绿）"
            />
          </Dropdown>
          <Tooltip title={alertCounts.open > 0
            ? `${alertCounts.open} 条待处理告警${alertCounts.critical > 0
              ? `，其中 ${alertCounts.critical} 条严重` : ''}，点击查看`
            : '暂无待处理告警'}>
            <Badge
              count={alertCounts.open}
              size="small"
              offset={[-4, 4]}
              color={alertCounts.critical > 0 ? 'red' : 'orange'}
            >
              <Button
                type="text"
                className="alert-bell"
                aria-label={alertCounts.open > 0 ? `待处理告警 ${alertCounts.open} 条` : '暂无待处理告警'}
                icon={<BellOutlined />}
                onClick={() => { if (canLeaveCurrentPage()) nav('/ops/alerts') }}
              />
            </Badge>
          </Tooltip>
          {/* REQ-HELP-NAV-1：右上角 ? 打开帮助中心抽屉（内置 K8s 结构介绍 + 运维手册，全站可见）。
              REQ-DW-19 的按页锚点直达不丢：抽屉内「平台使用帮助（产品操作）」仍跳 /help#<当前页锚点>。 */}
          <Tooltip title="使用帮助">
            <Button
              type="text"
              className="help-entry"
              aria-label="使用帮助"
              icon={<QuestionCircleOutlined />}
              onClick={() => setHelpOpen(true)}
            />
          </Tooltip>
          {tenants.length > 1 && (
            <Dropdown menu={tenantMenu} placement="bottomRight">
              <Tag color="geekblue" style={{ cursor: 'pointer' }} title="切换租户">
                {currentTenant()}
              </Tag>
            </Dropdown>
          )}
          <Tag color={theme === 'green' ? 'green' : 'blue'} className="env-tag">DEV</Tag>
          <Tag color={roleColor()} className="role-tag">
            {currentRoleLabel()}
          </Tag>
          <Dropdown menu={userMenu} placement="bottomRight">
            <span className="header-user">
              <Avatar size={26} style={{ background: '#1677ff' }}>
                {currentDisplayName().slice(0, 1).toUpperCase()}
              </Avatar>
              <span className="header-username">{currentDisplayName()}</span>
              <DownOutlined className="header-caret" />
            </span>
          </Dropdown>
        </div>
      </Header>
      <Layout>
        <Sider
          width={208}
          className="app-sider"
          theme="dark"
          collapsible
          collapsed={collapsed}
          trigger={null}
          collapsedWidth={64}
          breakpoint="xl"
          onBreakpoint={(broken) => {
            if (!broken) return
            // 用户显式选过「展开」就尊重偏好，别每次刷新都被断点收回去
            if (localStorage.getItem('dw_sider_collapsed') === '0') return
            setCollapsed(true)
          }}
        >
          <Menu
            mode="inline"
            theme="dark"
            selectedKeys={[selected]}
            items={menuItems}
            onClick={({ key }) => {
              // 编辑页有未保存修改时，先在页面注册的守卫里确认
              if (canLeaveCurrentPage()) nav(key)
            }}
          />
          <div className="sider-foot">v0.1 · DEV</div>
        </Sider>
        <Layout className="app-main">
          <div className="content-topbar">
            {/* 面包屑只到分组：末级和页面标题重复（外部 UX 审计 P2-8） */}
            <Breadcrumb
              items={[
                { title: 'data-ai-agent' },
                ...(current ? [{ title: current.section }] : [{ title: '数据开发' }]),
              ]}
            />
          </div>
          <Content className="app-content">
            <Outlet />
          </Content>
        </Layout>
      </Layout>
      <AgentChat />
      <HelpCenterDrawer
        open={helpOpen}
        onClose={() => setHelpOpen(false)}
        onGotoBiz={() => {
          if (!canLeaveCurrentPage()) return
          const anchor = helpAnchorForPath(location.pathname)
          nav(anchor ? `/help#${anchor}` : '/help')
          setHelpOpen(false)
        }}
      />
      <Modal
        open={pwdOpen}
        title="修改密码"
        okText="保存"
        cancelText="取消"
        confirmLoading={pwdSaving}
        onCancel={() => setPwdOpen(false)}
        onOk={() => pwdForm.submit()}
        destroyOnClose
      >
        <Form
          form={pwdForm}
          layout="vertical"
          onFinish={async (values) => {
            setPwdSaving(true)
            try {
              await post('/api/auth/password', {
                oldPassword: values.oldPassword,
                newPassword: values.newPassword,
              })
              message.success('密码已修改，请用新密码重新登录')
              setPwdOpen(false)
              logout()
              nav('/login', { replace: true })
            } catch (e: any) {
              message.error(e.message || '修改密码失败')
            } finally {
              setPwdSaving(false)
            }
          }}
        >
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 8, message: '至少 8 位' }]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再输入一次' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) return Promise.resolve()
                  return Promise.reject(new Error('两次输入不一致'))
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </Layout>
  )
}
