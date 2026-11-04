import { useEffect, useState } from 'react'
import {
  Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Tooltip, message,
} from 'antd'
import { DeleteOutlined, KeyOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import { api, del, post, put } from '../api'
import PageHeader from '../components/PageHeader'
import ImeInput from '../components/ImeInput'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { ROLE_COLOR, ROLE_OPTIONS, BUILTIN_ROLE_OPTIONS, roleLabelOf, type Role } from '../permissions'

interface UserRow {
  id: number
  username: string
  displayName: string
  role: Role
  roleLabel: string
  status: 'ACTIVE' | 'DISABLED'
  source: 'LOCAL' | 'SSO'
  builtin: boolean
  createdAt: string
  lastLoginAt?: string
}

const PASSWORD_RULE = {
  required: true,
  min: 8,
  max: 128,
  pattern: /^(?=.*[A-Za-z])(?=.*\d).{8,128}$/,
  message: '至少 8 位，且同时包含字母和数字',
}

export default function Users({ embedded = false }: { embedded?: boolean }) {
  const [rows, setRows] = useState<UserRow[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [kwInput, setKwInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [roleFilter, setRoleFilter] = useState<Role>()
  const [statusFilter, setStatusFilter] = useState<'ACTIVE' | 'DISABLED'>()

  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<UserRow | null>(null)
  const [resetting, setResetting] = useState<UserRow | null>(null)
  const [saving, setSaving] = useState(false)
  const [createForm] = Form.useForm()
  const [editForm] = Form.useForm()
  const [passwordForm] = Form.useForm()

  const load = async (kw = keyword) => {
    setLoading(true)
    try {
      const query = kw ? `?keyword=${encodeURIComponent(kw)}` : ''
      setRows(await api<UserRow[]>(`/api/users${query}`))
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载用户失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const search = () => {
    setKeyword(kwInput.trim())
    void load(kwInput.trim())
  }

  const create = async () => {
    const values = await createForm.validateFields()
    setSaving(true)
    try {
      await post('/api/users', values)
      setCreating(false)
      createForm.resetFields()
      message.success('用户已创建')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '创建失败')
    } finally {
      setSaving(false)
    }
  }

  const saveEdit = async () => {
    if (!editing) return
    const values = await editForm.validateFields()
    const changed = values.role !== editing.role || values.status !== editing.status
    if (changed) {
      // 改角色/禁用会立即影响对方权限与在线会话，先确认再保存
      const ok = await new Promise<boolean>((resolve) => {
        Modal.confirm({
          title: `修改用户「${editing.username}」的权限？`,
          content: [
            values.role !== editing.role
              ? `角色：${roleLabelOf(editing.role)} → ${roleLabelOf(values.role as string)}`
              : '',
            values.status !== editing.status
              ? `状态：${editing.status === 'ACTIVE' ? '启用' : '禁用'} → ${values.status === 'ACTIVE' ? '启用' : '禁用'}`
              : '',
            values.status === 'DISABLED' ? '禁用后该用户的登录会话会立即失效。' : '',
          ].filter(Boolean).join('；'),
          okButtonProps: { danger: values.status === 'DISABLED' },
          okText: '保存修改',
          cancelText: '再想想',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        })
      })
      if (!ok) return
    }
    setSaving(true)
    try {
      // REQ-DW-12：后端重绑是覆盖式的，只有角色真的改了才提交 role，
      // 避免只改显示名/状态时按历史列粗值把 OWNER/安全审核员 降成只读访客。
      const payload: Record<string, unknown> = { ...values }
      if (values.role === editing.role) delete payload.role
      await put(`/api/users/${editing.id}`, payload)
      setEditing(null)
      message.success('已保存')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const savePassword = async () => {
    if (!resetting) return
    const values = await passwordForm.validateFields()
    setSaving(true)
    try {
      await post(`/api/users/${resetting.id}/password`, { password: values.password })
      setResetting(null)
      passwordForm.resetFields()
      message.success('密码已重置，该用户已有会话已失效')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '重置失败')
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (row: UserRow) => {
    const disable = row.status === 'ACTIVE'
    try {
      // 只提交状态：带上旧 role/displayName 会把别人刚改的角色覆盖回去
      await put(`/api/users/${row.id}`, {
        status: disable ? 'DISABLED' : 'ACTIVE',
      })
      message.success(disable ? '已禁用' : '已启用')
      await load()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '操作失败')
    }
  }

  const visibleRows = rows.filter((row) => (!roleFilter || row.role === roleFilter)
    && (!statusFilter || row.status === statusFilter))

  const createButton = (
    <Button type="primary" icon={<PlusOutlined />} onClick={() => {
      // 上次取消时表单没有销毁，重开必须先清空，避免残留上一个密码
      createForm.resetFields()
      setCreating(true)
    }}>
      新建用户
    </Button>
  )

  return (
    <div>
      {!embedded && (
        <PageHeader
          title="用户与权限"
          description="内置角色：平台管理员 / 项目Owner / 安全审核员 / 数据开发 / 只读访客；改动立即生效"
          extra={createButton}
        />
      )}
      <Card extra={embedded ? createButton : undefined}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 16, flexWrap: 'wrap' }}>
          <ImeInput
            allowClear
            placeholder="用户名或显示名"
            prefix={<SearchOutlined />}
            value={kwInput}
            style={{ width: 240 }}
            onChange={(v) => {
              setKwInput(v)
              // 点 x 清空后自动回到全量列表，否则列表还停在上次关键字的结果
              if (!v && keyword) {
                setKeyword('')
                void load('')
              }
            }}
            onPressEnter={search}
          />
          <Button onClick={search}>查询</Button>
          <Select
            allowClear
            placeholder="角色"
            style={{ width: 140 }}
            value={roleFilter}
            onChange={setRoleFilter}
            options={ROLE_OPTIONS}
          />
          <Select
            allowClear
            placeholder="状态"
            style={{ width: 120 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={[
              { value: 'ACTIVE', label: '启用' },
              { value: 'DISABLED', label: '已禁用' },
            ]}
          />
          <span style={{ color: '#888', fontSize: 12 }}>
            SSO 用户首次登录会自动开户为「只读访客」，可在这里提前建号并指定角色。
          </span>
        </div>
        {error ? <ErrorState message={error} onRetry={() => load()} /> : (
          <Table<UserRow>
            rowKey="id"
            loading={loading}
            dataSource={visibleRows}
            pagination={false}
            scroll={{ x: 900 }}
            locale={{
              emptyText: rows.length > 0
                ? tableEmpty('没有符合条件的用户', '清空角色/状态筛选或换个关键字')
                : tableEmpty('还没有用户', '点击右上角「新建用户」创建账号'),
            }}
            columns={[
              {
                title: '用户名',
                dataIndex: 'username',
                width: 180,
                render: (v: string, row) => (
                  <Space size={6}>
                    <span>{v}</span>
                    {row.builtin ? <Tag color="gold">内置</Tag> : null}
                  </Space>
                ),
              },
              { title: '显示名', dataIndex: 'displayName', width: 160 },
              {
                title: '角色',
                dataIndex: 'role',
                width: 130,
                render: (v: Role) => <Tag color={ROLE_COLOR[v]}>{roleLabelOf(v)}</Tag>,
              },
              {
                title: '状态',
                dataIndex: 'status',
                width: 100,
                render: (v: string) => (
                  <Tag color={v === 'ACTIVE' ? 'green' : 'default'}>
                    {v === 'ACTIVE' ? '启用' : '禁用'}
                  </Tag>
                ),
              },
              {
                title: '来源',
                dataIndex: 'source',
                width: 90,
                render: (v: string) => (v === 'SSO' ? 'SSO' : '本地'),
              },
              {
                title: '最后登录',
                dataIndex: 'lastLoginAt',
                width: 170,
                render: (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-'),
              },
              {
                title: '操作',
                width: 280,
                render: (_, row) => (
                  <Space size={4}>
                    <Tooltip title={row.builtin ? '内置管理员由部署配置固定' : ''}>
                      <Button size="small" disabled={row.builtin} onClick={() => {
                        setEditing(row)
                        editForm.resetFields()
                        editForm.setFieldsValue({
                          displayName: row.displayName,
                          role: row.role,
                          status: row.status,
                        })
                      }}>
                        编辑
                      </Button>
                    </Tooltip>
                    <Tooltip title={row.builtin ? '内置管理员密码由部署配置管理' : ''}>
                      <Button
                        size="small"
                        icon={<KeyOutlined />}
                        disabled={row.builtin}
                        onClick={() => {
                          setResetting(row)
                          passwordForm.resetFields()
                        }}
                      >
                        重置密码
                      </Button>
                    </Tooltip>
                    <Popconfirm
                      title={row.status === 'ACTIVE'
                        ? `禁用用户「${row.username}」？`
                        : `启用用户「${row.username}」？`}
                      description={row.status === 'ACTIVE'
                        ? '禁用后该用户已登录的会话会立即失效，且无法再登录。'
                        : '启用后该用户可以用原密码登录。'}
                      okText={row.status === 'ACTIVE' ? '禁用' : '启用'}
                      okButtonProps={{ danger: row.status === 'ACTIVE' }}
                      onConfirm={() => toggleStatus(row)}
                    >
                      <Button
                        size="small"
                        danger={row.status === 'ACTIVE'}
                        disabled={row.builtin}
                      >
                        {row.status === 'ACTIVE' ? '禁用' : '启用'}
                      </Button>
                    </Popconfirm>
                    {!row.builtin && (
                      <Popconfirm
                        title={`删除用户「${row.username}」？`}
                        description="用户记录与会话会删除，历史审计保留。"
                        okText="删除"
                        okButtonProps={{ danger: true }}
                        onConfirm={async () => {
                          try {
                            await del(`/api/users/${row.id}`)
                            message.success('用户已删除')
                            await load()
                          } catch (e) {
                            message.error(e instanceof Error ? e.message : '删除失败')
                          }
                        }}
                      >
                        <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Modal
        title="新建用户"
        open={creating}
        confirmLoading={saving}
        onOk={create}
        onCancel={() => setCreating(false)}
        okText="创建"
      >
        <Form form={createForm} layout="vertical" initialValues={{ role: 'VIEWER' }}>
          <Form.Item
            label="用户名"
            name="username"
            rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$/, message: '3–64 位字母、数字、点、下划线或短横线' },
            ]}
          >
            <Input placeholder="例如 zhangsan" autoComplete="off" />
          </Form.Item>
          <Form.Item label="显示名" name="displayName">
            <Input placeholder="例如 张三" />
          </Form.Item>
          <Form.Item label="角色" name="role" rules={[{ required: true }]}>
            <Select options={BUILTIN_ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item label="初始密码" name="password" rules={[PASSWORD_RULE]}>
            <Input.Password placeholder="至少 8 位，含字母和数字" autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`编辑用户：${editing?.username || ''}`}
        open={Boolean(editing)}
        confirmLoading={saving}
        onOk={saveEdit}
        onCancel={() => setEditing(null)}
      >
        <Form form={editForm} layout="vertical">
          <Form.Item label="显示名" name="displayName">
            <Input />
          </Form.Item>
          <Form.Item label="角色" name="role" rules={[{ required: true }]}>
            <Select options={BUILTIN_ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item label="状态" name="status" rules={[{ required: true }]}>
            <Select
              options={[
                { value: 'ACTIVE', label: '启用' },
                { value: 'DISABLED', label: '禁用' },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`重置密码：${resetting?.username || ''}`}
        open={Boolean(resetting)}
        confirmLoading={saving}
        onOk={savePassword}
        onCancel={() => setResetting(null)}
      >
        <Form form={passwordForm} layout="vertical">
          <Form.Item label="新密码" name="password" rules={[PASSWORD_RULE]}>
            <Input.Password placeholder="至少 8 位，含字母和数字" autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
