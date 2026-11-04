import { useEffect, useMemo, useState } from 'react'
import {
  Alert, Button, Card, Checkbox, Form, Input, Modal, Popconfirm, Select, Space, Table, Tabs, Tag,
  Typography, message,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { api, del, post, put } from '../api'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'

interface TenantRow {
  code: string
  name: string
  isDefault: boolean
  roles: string[]
}

interface RoleRow {
  code: string
  name: string
  builtin: boolean
  permissions: string[]
}

interface CatalogRow {
  code: string
  name: string
  group_name: string
  danger: string
}

interface TenantAdminRow {
  code: string
  name: string
  status: string
  members: number
  dataCount: number
  createdAt?: string
}

interface UserOption {
  id: number
  username: string
  displayName: string
}

interface MemberRow {
  userId: number
  username: string
  displayName: string
  status: string
  legacyRole: string
  roles: string[]
}

const DANGER_COLOR: Record<string, string> = { 高: 'red', 中: 'orange' }

export default function Roles({ embedded = false }: { embedded?: boolean }) {
  const [tenants, setTenants] = useState<TenantRow[]>([])
  const [tenant, setTenant] = useState('default')
  const [roles, setRoles] = useState<RoleRow[]>([])
  const [catalog, setCatalog] = useState<CatalogRow[]>([])
  const [members, setMembers] = useState<MemberRow[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  // 租户管理（第四期）：平台管理员可建租户、邀请成员
  const [allTenants, setAllTenants] = useState<TenantAdminRow[] | null>(null)
  const [tenantModal, setTenantModal] = useState(false)
  const [tenantSaving, setTenantSaving] = useState(false)
  // D54 删租户：手输租户编码才能确认，弹窗里写清「会连带没掉什么」
  const [tenantDelete, setTenantDelete] = useState<TenantAdminRow>()
  const [tenantDeleteInput, setTenantDeleteInput] = useState('')
  const [tenantDeleteBusy, setTenantDeleteBusy] = useState(false)
  const [inviteOpen, setInviteOpen] = useState(false)
  const [userOptions, setUserOptions] = useState<UserOption[]>([])
  const [tenantForm] = Form.useForm()
  const [inviteForm] = Form.useForm()
  const [editing, setEditing] = useState<RoleRow | null>(null)
  const [creating, setCreating] = useState(false)
  const [assigning, setAssigning] = useState<MemberRow | null>(null)
  const [form] = Form.useForm()
  const [assignForm] = Form.useForm()

  const catalogByGroup = useMemo(() => {
    const groups = new Map<string, CatalogRow[]>()
    for (const item of catalog) {
      const list = groups.get(item.group_name) || []
      list.push(item)
      groups.set(item.group_name, list)
    }
    return Array.from(groups.entries())
  }, [catalog])

  const load = async (code = tenant) => {
    setLoading(true)
    try {
      const [roleRows, memberRows] = await Promise.all([
        api<RoleRow[]>(`/api/rbac/roles?tenant=${encodeURIComponent(code)}`),
        api<MemberRow[]>(`/api/rbac/members?tenant=${encodeURIComponent(code)}`),
      ])
      setRoles(roleRows)
      setMembers(memberRows)
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载角色失败')
    } finally {
      setLoading(false)
    }
  }

  const reloadTenants = async (preferred?: string) => {
    const [mine, all] = await Promise.all([
      api<TenantRow[]>('/api/rbac/tenants'),
      // 没有 tenant:manage 的角色会 403：租户页签对它隐藏，不影响角色/成员页
      api<TenantAdminRow[]>('/api/rbac/all-tenants').catch(() => null),
    ])
    setTenants(mine)
    setAllTenants(all)
    if (preferred) {
      setTenant(preferred)
    } else {
      const preferredCode = mine.find((r) => r.isDefault)?.code || mine[0]?.code
      if (preferredCode) setTenant(preferredCode)
    }
  }

  useEffect(() => {
    reloadTenants().catch((e) => setError(e instanceof Error ? e.message : '加载租户失败'))
    api<CatalogRow[]>('/api/rbac/catalog').then(setCatalog).catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    load(tenant)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tenant])

  const openCreate = () => {
    form.resetFields()
    form.setFieldsValue({ permissions: [] })
    setCreating(true)
  }

  const openEdit = (row: RoleRow) => {
    form.setFieldsValue({ code: row.code, name: row.name, permissions: row.permissions })
    setEditing(row)
  }

  const submit = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      if (editing) {
        await put(`/api/rbac/roles/${editing.code}`, {
          tenant, name: values.name, permissions: values.permissions,
        })
        message.success('角色已更新')
      } else {
        await post('/api/rbac/roles', {
          tenant, code: values.code, name: values.name, permissions: values.permissions,
        })
        message.success('角色已创建')
      }
      setCreating(false)
      setEditing(null)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const remove = async (row: RoleRow) => {
    try {
      // del 调用点（D54）：由列表行 Popconfirm 触发（确认框文案写明「删除后该角色的授权关系一并移除」），
      // 角色是租户内配置对象、可重建，属中危，手输编码级别不够必要
      await del(`/api/rbac/roles/${row.code}?tenant=${encodeURIComponent(tenant)}`)
      message.success('角色已删除')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除失败')
    }
  }

  const submitTenant = async () => {
    const values = await tenantForm.validateFields()
    setTenantSaving(true)
    try {
      await post('/api/rbac/tenants', values)
      message.success('租户已创建，你已是该租户的 ADMIN')
      setTenantModal(false)
      await reloadTenants(values.code)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建租户失败')
    } finally {
      setTenantSaving(false)
    }
  }

  const removeTenant = async () => {
    if (!tenantDelete) return
    // 后端也要核对 confirm=<code>（D54）：界面的手输只是让人慢下来，真正的护栏在服务端
    if (tenantDeleteInput.trim().toLowerCase() !== tenantDelete.code.toLowerCase()) {
      message.warning('请输入与租户编码完全一致的内容')
      return
    }
    setTenantDeleteBusy(true)
    try {
      // del 调用点（D54）：手输租户编码才允许（弹窗在下方 tenantDelete Modal），
      // 后端 RbacController.deleteTenant 同样校验 confirm=<code>，绕过界面直接发请求也会被 400
      await del(`/api/rbac/tenants/${encodeURIComponent(tenantDelete.code)}`
        + `?confirm=${encodeURIComponent(tenantDeleteInput.trim())}`)
      message.success(`租户 ${tenantDelete.code} 已删除`)
      setTenantDelete(undefined)
      setTenantDeleteInput('')
      await reloadTenants()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除租户失败')
    } finally {
      setTenantDeleteBusy(false)
    }
  }

  const openInvite = async () => {
    inviteForm.resetFields()
    setInviteOpen(true)
    if (!userOptions.length) {
      try {
        const rows = await api<UserOption[]>('/api/users')
        setUserOptions(rows.map((r) => ({
          id: r.id, username: r.username, displayName: r.displayName,
        })))
      } catch (e) {
        message.error(e instanceof Error ? e.message : '加载用户失败')
      }
    }
  }

  const submitInvite = async () => {
    const values = await inviteForm.validateFields()
    setSaving(true)
    try {
      await post(`/api/rbac/tenants/${encodeURIComponent(tenant)}/members`, {
        userId: values.userId, roles: values.roles,
      })
      message.success('成员已加入该租户')
      setInviteOpen(false)
      await load()
      await reloadTenants(tenant)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '邀请失败')
    } finally {
      setSaving(false)
    }
  }

  const doAssignRoles = async (selected: string[]) => {
    if (!assigning) return
    setSaving(true)
    try {
      await post(`/api/rbac/members/${assigning.userId}/roles`, {
        tenant, roles: selected, clearAll: selected.length === 0,
      })
      message.success('成员角色已更新')
      setAssigning(null)
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '设置失败')
    } finally {
      setSaving(false)
    }
  }

  const submitAssign = async () => {
    const values = await assignForm.validateFields()
    if (!assigning) return
    const selected: string[] = values.roles ?? []
    if (selected.length === 0) {
      // BUG-TC7-G3-1：空提交是全量覆盖语义下的「清空全部角色（含内置）」，
      // 成员会变成能登录、所有接口 403 的空壳状态——必须二次显式确认。
      Modal.confirm({
        title: `清空成员「${assigning.username}」的全部角色？`,
        content: `清空后 ${assigning.username} 在 ${tenant} 租户下只剩登录能力，所有接口都会返回 403`
          + '（内置角色也会被摘除）。要把人移出租户请改用「删除成员」。',
        okText: '清空',
        okButtonProps: { danger: true },
        onOk: () => doAssignRoles([]),
      })
      return
    }
    await doAssignRoles(selected)
  }

  const tenantSelect = (
    <Select
      style={{ width: 200 }}
      value={tenant}
      onChange={setTenant}
      options={tenants.map((t) => ({ value: t.code, label: `${t.name}（${t.code}）` }))}
    />
  )

  const roleTable = (
    <Card
      title={<Space>角色定义 {tenantSelect}</Space>}
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建角色</Button>}
    >
      {error ? <ErrorState message={error} onRetry={() => load()} /> : (
        <Table<RoleRow>
          rowKey="code"
          size="small"
          loading={loading}
          dataSource={roles}
          locale={{ emptyText: tableEmpty('还没有角色') }}
          columns={[
            { title: '编码', dataIndex: 'code', width: 180 },
            { title: '名称', dataIndex: 'name' },
            {
              title: '类型', dataIndex: 'builtin', width: 100,
              render: (builtin: boolean) => builtin
                ? <Tag color="blue">内置</Tag> : <Tag color="green">自定义</Tag>,
            },
            {
              title: '权限点', dataIndex: 'permissions', width: 100,
              render: (perms: string[]) => `${perms.length} 个`,
            },
            {
              title: '操作', width: 160,
              render: (_, row) => row.builtin ? <Tag>不可修改</Tag> : (
                <Space>
                  <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
                  <Popconfirm title={`删除角色「${row.code}」？`} okText="删除" okButtonProps={{ danger: true }} onConfirm={() => remove(row)}>
                    <Button size="small" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      )}
    </Card>
  )

  const memberTable = (
    <Card
      title="成员角色（覆盖式设置，含内置角色）"
      extra={allTenants ? (
        <Button icon={<PlusOutlined />} onClick={openInvite}>邀请成员</Button>
      ) : null}
    >
      <Table<MemberRow>
        rowKey="userId"
        size="small"
        loading={loading}
        dataSource={members}
        locale={{ emptyText: tableEmpty('该租户还没有成员') }}
        columns={[
          { title: '账号', dataIndex: 'username', width: 200 },
          { title: '姓名', dataIndex: 'displayName', width: 160 },
          {
            title: '状态', dataIndex: 'status', width: 100,
            render: (s: string) => s === 'ACTIVE' ? <Tag color="green">启用</Tag> : <Tag>禁用</Tag>,
          },
          {
            title: '角色', dataIndex: 'roles',
            render: (rows: string[]) => rows.length
              ? rows.map((r) => <Tag key={r} color="blue">{r}</Tag>)
              : <Tag>无</Tag>,
          },
          {
            title: '操作', width: 140,
            render: (_, row) => (
              <Button size="small" onClick={() => {
                assignForm.setFieldsValue({ roles: row.roles })
                setAssigning(row)
              }}>设置角色</Button>
            ),
          },
        ]}
      />
    </Card>
  )

  const tenantTable = (
    <Card
      title="租户"
      extra={<Button type="primary" icon={<PlusOutlined />}
        onClick={() => { tenantForm.resetFields(); setTenantModal(true) }}>新建租户</Button>}
    >
      <Table<TenantAdminRow>
        rowKey="code"
        size="small"
        dataSource={allTenants || []}
        locale={{ emptyText: tableEmpty('还没有租户') }}
        columns={[
          { title: '编码', dataIndex: 'code', width: 180 },
          { title: '名称', dataIndex: 'name' },
          {
            title: '状态', dataIndex: 'status', width: 100,
            render: (v: string) => v === 'ACTIVE'
              ? <Tag color="green">启用</Tag> : <Tag>{v}</Tag>,
          },
          { title: '成员', dataIndex: 'members', width: 90 },
          {
            title: '业务数据', dataIndex: 'dataCount', width: 110,
            render: (n: number) => n ? `${n} 条` : <span style={{ color: '#999' }}>无</span>,
          },
          {
            title: '操作', width: 150,
            render: (_, row) => row.code === 'default' ? <Tag>默认租户</Tag> : (
              // D54：删租户是「整片下线」级别的动作，改成手输编码确认的弹窗
              // （旧实现是 Popconfirm 点一下就走，且描述只说「只删除租户与成员关系」，
              //  把项目归属、租户内角色会被连带没掉这件事说小了）
              <Button
                size="small"
                danger
                icon={<DeleteOutlined />}
                disabled={row.dataCount > 0}
                onClick={() => { setTenantDelete(row); setTenantDeleteInput('') }}
              >
                删除
              </Button>
            ),
          },
        ]}
      />
    </Card>
  )

  const body = (
    <Tabs
      items={[
        { key: 'roles', label: '角色定义', children: roleTable },
        { key: 'members', label: '成员角色', children: memberTable },
        ...(allTenants ? [{ key: 'tenants', label: '租户', children: tenantTable }] : []),
      ]}
    />
  )

  const permissionPicker = (
    <Form.Item name="permissions" label="权限点" extra="按分组勾选；内置角色的权限集不可改。">
      <Checkbox.Group style={{ width: '100%' }}>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          {catalogByGroup.map(([group, items]) => (
            <div key={group}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>{group}</div>
              <Space wrap>
                {items.map((item) => (
                  <Checkbox key={item.code} value={item.code}>
                    {item.name}
                    <span style={{ color: '#999', marginLeft: 4 }}>{item.code}</span>
                    {item.danger && <Tag color={DANGER_COLOR[item.danger] || 'default'}
                      style={{ marginLeft: 4 }}>{item.danger}危</Tag>}
                  </Checkbox>
                ))}
              </Space>
            </div>
          ))}
        </Space>
      </Checkbox.Group>
    </Form.Item>
  )

  return (
    <div>
      {!embedded && (
        <PageHeader title="角色与权限"
          description="角色属于租户；自定义角色可勾选权限点，内置角色只读。成员角色为覆盖式设置。" />
      )}
      {body}

      <Modal
        open={creating || !!editing}
        title={editing ? `编辑角色 ${editing.code}` : '新建角色'}
        onCancel={() => { setCreating(false); setEditing(null) }}
        onOk={submit}
        confirmLoading={saving}
        width={760}
        destroyOnClose
      >
        <Form form={form} layout="vertical">
          <Form.Item name="code" label="角色编码"
            rules={[{ required: true, message: '编码必填' },
              { pattern: /^[A-Z][A-Z0-9_]{1,63}$/, message: '大写字母开头，只能包含大写字母、数字、下划线' }]}>
            <Input placeholder="例如 DATA_READER" disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '名称必填' }]}>
            <Input placeholder="例如 只读数据分析" />
          </Form.Item>
          {permissionPicker}
        </Form>
      </Modal>

      <Modal
        open={tenantModal}
        title="新建租户"
        onCancel={() => setTenantModal(false)}
        onOk={submitTenant}
        confirmLoading={tenantSaving}
        destroyOnClose
      >
        <Form form={tenantForm} layout="vertical">
          <Form.Item name="code" label="租户编码"
            rules={[{ required: true, message: '编码必填' },
              { pattern: /^[a-z][a-z0-9_]{1,31}$/,
                message: '小写字母开头，2-32 位小写字母、数字或下划线' }]}>
            <Input placeholder="例如 tenant_c" />
          </Form.Item>
          <Form.Item name="name" label="租户名称" rules={[{ required: true, message: '名称必填' }]}>
            <Input placeholder="例如 华东数据中心" />
          </Form.Item>
          <div style={{ color: '#8c8c8c', fontSize: 12 }}>
            创建后自动复制 5 个内置角色，你本人会成为该租户的 ADMIN。
          </div>
        </Form>
      </Modal>

      <Modal
        open={inviteOpen}
        title={`邀请成员加入 ${tenant}`}
        onCancel={() => setInviteOpen(false)}
        onOk={submitInvite}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={inviteForm} layout="vertical">
          <Form.Item name="userId" label="选择账号"
            rules={[{ required: true, message: '请选择账号' }]}>
            <Select showSearch optionFilterProp="label"
              options={userOptions.map((u) => ({
                value: u.id, label: `${u.displayName}（${u.username}）`,
              }))} />
          </Form.Item>
          <Form.Item name="roles" label="角色（该租户下）"
            extra="不选则默认 VIEWER；可之后再在成员页调整。">
            <Select mode="multiple" allowClear
              options={roles.map((r) => ({ value: r.code, label: `${r.name}（${r.code}）` }))} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={!!tenantDelete}
        title={tenantDelete ? `删除租户：${tenantDelete.name}（${tenantDelete.code}）` : ''}
        onCancel={() => { setTenantDelete(undefined); setTenantDeleteInput('') }}
        onOk={removeTenant}
        okText="确认删除租户"
        okButtonProps={{
          danger: true,
          // 手输完全一致的租户编码才允许点（与 Tables.tsx 删表同一口径）
          disabled: !tenantDelete
            || tenantDeleteInput.trim().toLowerCase() !== tenantDelete.code.toLowerCase(),
          loading: tenantDeleteBusy,
        }}
        destroyOnClose
      >
        {tenantDelete ? (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            <Alert
              type="error"
              showIcon
              message="删除后该租户下的成员关系、租户内角色、项目归属与授权会一并失效"
              description={(
                <>
                  当前成员 <b>{tenantDelete.members}</b> 人会被移出该租户（用户账号本身保留）；
                  该租户内的自定义角色与内置角色副本随之删除；
                  挂在这个租户下的项目、库表授权在重新归属之前将无法使用。
                  {tenantDelete.dataCount > 0
                    ? `该租户还有 ${tenantDelete.dataCount} 条业务数据，必须先清掉才允许删除。`
                    : '请先确认没有人在用这个租户。'}
                </>
              )}
            />
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                请输入租户编码 <Typography.Text code>{tenantDelete.code}</Typography.Text> 以确认
              </Typography.Text>
              <Input
                style={{ marginTop: 6 }}
                value={tenantDeleteInput}
                onChange={(e) => setTenantDeleteInput(e.target.value)}
                placeholder={tenantDelete.code}
              />
            </div>
          </Space>
        ) : null}
      </Modal>

      <Modal
        open={!!assigning}
        title={assigning ? `设置角色：${assigning.username}` : ''}
        onCancel={() => setAssigning(null)}
        onOk={submitAssign}
        confirmLoading={saving}
        destroyOnClose
      >
        <Form form={assignForm} layout="vertical">
          <Form.Item name="roles"
            label="角色（可多选，覆盖该租户下的全部角色，含内置；留空需二次确认清空）">
            <Select mode="multiple" allowClear
              options={roles.map((r) => ({ value: r.code, label: `${r.name}（${r.code}）` }))} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
