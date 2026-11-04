import { useEffect, useState } from 'react'
import {
  Alert, AutoComplete, Button, Card, Checkbox, Form, Input, Modal, Popconfirm, Radio, Select, Space,
  Switch, Table, Tabs, Tag, Tooltip, Typography, message,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { api, del, post, put } from '../api'
import PageHeader from '../components/PageHeader'
import ProjectCreateModal from '../components/ProjectCreateModal'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import Departments from './Departments'
import Users from './Users'
import Roles from './Roles'
import type { Department, Project } from '../types'

/** 平台管理三个相似页面（项目 / 部门 / 用户）合成一个「管理中心」，用分页切换。 */
const PATH_BY_TAB: Record<string, string> = {
  projects: '/admin',
  departments: '/departments',
  users: '/users',
  roles: '/roles',
}
const TAB_BY_PATH: Record<string, string> = {
  '/departments': 'departments',
  '/users': 'users',
  '/roles': 'roles',
}

/** v483：项目级数据安全策略（GET/PUT /api/projects/{id}/security）。 */
interface ProjectSecurity {
  projectId: number
  ownerUser?: string | null
  securityMode: 'INHERIT' | 'OFF' | 'TABLE' | 'COLUMN'
  defaultColumnLevel?: string | null
  columnControlRequired?: boolean | null
  effectiveMode: string
  effectiveSource: 'PROJECT' | 'TENANT' | 'PLATFORM'
  effectiveDefaultColumnLevel?: string
  editable: boolean
  platform: { mode: string; enforce: string; defaultColumnLevel: string }
  tenant: { mode?: string | null }
}

/** D57：空壳库巡检（GET /api/projects/database-inventory）的返回结构。 */
interface EnvDatabase {
  database: string
  tables: number
  referencedBy: string[]
  system: boolean
  emptyShell: boolean
}

interface EnvInventory {
  target: string
  total: number
  emptyShells: number
  databases: EnvDatabase[]
}

const SECURITY_MODE_LABEL: Record<string, string> = {
  OFF: '关闭',
  TABLE: '表级',
  COLUMN: '字段级',
}

const SECURITY_SOURCE_LABEL: Record<string, string> = {
  PROJECT: '项目自定义',
  TENANT: '继承自租户',
  PLATFORM: '继承自平台',
}

/** D42：项目成员（V113 platform.project_member）。role OWNER=负责人，ADMIN=项目管理员。 */
interface ProjectMember {
  username: string
  role: 'OWNER' | 'ADMIN'
}

interface ProjectMembers {
  ownerUser?: string | null
  members: ProjectMember[]
  /** false = 当前用户既不是负责人也不是平台管理员 → 区块只读。 */
  manageable: boolean
}

interface UserOption {
  username: string
  displayName?: string | null
}

interface DorisDatasourceOption {
  id: number
  name: string
  ds_type: string
  jdbc_url?: string | null
  builtin?: boolean
}

function ProjectPanel() {
  const [projects, setProjects] = useState<Project[]>([])
  const [datasources, setDatasources] = useState<DorisDatasourceOption[]>([])
  // D40：编辑弹窗要能改「所属部门」，下拉源与部门页同一个接口
  const [departments, setDepartments] = useState<Department[]>([])
  // D42：负责人 / 项目管理员（GET/PUT /api/projects/{id}/members）
  const [membersForm] = Form.useForm()
  const [members, setMembers] = useState<ProjectMembers>()
  const [membersLoading, setMembersLoading] = useState(false)
  const [membersError, setMembersError] = useState('')
  const [userOptions, setUserOptions] = useState<UserOption[]>([])
  // D43：SQL 守卫（GET/PUT /api/projects/{id}/sql-guard）。canManage=false 时只读并说明原因。
  const [sqlGuard, setSqlGuard] = useState<{ projectId: number; sqlGuardEnabled: boolean; canManage: boolean }>()
  const [sqlGuardNext, setSqlGuardNext] = useState<boolean | null>(null)
  const [sqlGuardError, setSqlGuardError] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  // D57：DEV/PROD 环境与空壳库（两个接口都只给平台管理员；403 时页面如实显示后端原因）
  const [inventory, setInventory] = useState<EnvInventory>()
  const [envLoading, setEnvLoading] = useState(false)
  const [envError, setEnvError] = useState('')
  const [backfillNote, setBackfillNote] = useState('')

  const [creating, setCreating] = useState(false)
  // 编辑项目：名称/描述可改，编码是各接口的引用，保持不动
  const [editing, setEditing] = useState<Project>()
  const [editForm] = Form.useForm()
  // 数据安全卡片（随项目一起加载；非 owner 且无 system:manage 时只读）
  const [securityForm] = Form.useForm()
  const [security, setSecurity] = useState<ProjectSecurity>()
  const [securityLoading, setSecurityLoading] = useState(false)
  const [securityError, setSecurityError] = useState('')
  const [ccrInherit, setCcrInherit] = useState(true)
  const securityMode = Form.useWatch('securityMode', securityForm)
  const securityReadOnly = Boolean(security && !security.editable)

  const load = async () => {
    setLoading(true)
    try {
      const [list, sources, depts] = await Promise.all([
        api<Project[]>('/api/projects'),
        api<DorisDatasourceOption[]>('/api/datasources?type=DORIS').catch(() => []),
        api<Department[]>('/api/departments').catch(() => []),
      ])
      setProjects(list)
      setDatasources((sources || []).filter((item) => item.ds_type === 'DORIS'))
      setDepartments(depts || [])
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '未知原因')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
    loadUserOptions()
  }, [])

  /** 成员选择器的候选：/api/users 需要 user:read，拿不到就退回手填用户名（后端仍会校验存在性）。 */
  // D57：数仓里的库只增不减（历史测试项目留下的空壳），先给一张能看的清单
  const loadInventory = async () => {
    setEnvLoading(true)
    setEnvError('')
    try {
      setInventory(await api<EnvInventory>('/api/projects/database-inventory'))
    } catch (e) {
      setEnvError(e instanceof Error ? e.message : '空壳库巡检失败')
    } finally {
      setEnvLoading(false)
    }
  }

  // create=false 只补 platform.project 的两列；true 才在 Doris 上真建库（幂等，非空目标库会报冲突）
  const backfillDatabases = async (create: boolean) => {
    setEnvLoading(true)
    setEnvError('')
    try {
      const out = await post<{ scanned: number; filled: number; conflicts: unknown[] }>(
        `/api/projects/backfill-databases?create=${create}`,
      )
      setBackfillNote(`扫描 ${out.scanned} 个缺库名的项目，补齐 ${out.filled} 个，`
        + `冲突 ${out.conflicts?.length ?? 0} 个`
        + `（${create ? '已在 Doris 建库' : '只补元数据，未动 Doris'}）`)
      await load()
      await loadInventory()
    } catch (e) {
      setEnvError(e instanceof Error ? e.message : '回填库名失败')
    } finally {
      setEnvLoading(false)
    }
  }

  const loadUserOptions = async () => {
    try {
      setUserOptions(await api<UserOption[]>('/api/users'))
    } catch {
      setUserOptions([])
    }
  }

  /** D42：负责人 / 项目管理员。manageable=false 时界面只读并说明原因。 */
  const loadMembers = (project: Project) => {
    setMembers(undefined)
    setMembersError('')
    setMembersLoading(true)
    api<ProjectMembers>(`/api/projects/${project.id}/members`)
      .then((data) => {
        setMembers(data)
        membersForm.setFieldsValue({
          ownerUser: data.ownerUser || undefined,
          rows: (data.members || []).map((m) => ({ username: m.username, role: m.role })),
        })
      })
      .catch((e) => setMembersError(e instanceof Error ? e.message : '加载项目成员失败'))
      .finally(() => setMembersLoading(false))
  }

  /** D43：读当前守卫值 + 能不能改（后端判定，前端不复制规则）。 */
  const loadSqlGuard = (project: Project) => {
    setSqlGuard(undefined)
    setSqlGuardNext(null)
    setSqlGuardError('')
    api<{ projectId: number; sqlGuardEnabled: boolean; canManage: boolean }>(
      `/api/projects/${project.id}/sql-guard`)
      .then(setSqlGuard)
      .catch((e) => setSqlGuardError(e instanceof Error ? e.message : '加载 SQL 守卫状态失败'))
  }

  /**
   * D44：部门下拉的候选。项目当前挂的部门若不在候选里（被删/跨租户不可见），
   * 仍然列出来并标注不可选，避免界面显示成裸 id 或让人以为「没挂部门」。
   */
  const departmentOptions = (current?: number | null) => {
    const known = departments.map((d) => ({ value: d.id, label: `${d.name}（${d.dept_id}）` }))
    if (current && !known.some((o) => o.value === current)) {
      return [...known, { value: current, label: `不可选的部门（id=${current}）`, disabled: true }]
    }
    return known
  }
  const unselectableDepartment = editing?.department_id
    && !departments.some((d) => d.id === editing?.department_id)
    ? editing.department_id : null

  const openEdit = (project: Project) => {
    setEditing(project)
    loadMembers(project)
    loadSqlGuard(project)
    editForm.setFieldsValue({
      name: project.name,
      description: project.description || '',
      warehouseDatasourceId: project.warehouse_datasource_id
        ?? datasources.find((item) => item.builtin)?.id,
      // D40：后端支持的字段在界面上要能改，不再只暴露 name/description/数仓
      departmentId: project.department_id ?? undefined,
      devDatabase: project.dev_database || '',
      prodDatabase: project.prod_database || '',
    })
    // 每次打开都重新拉取：安全策略可能被负责人/平台管理员改过
    setSecurity(undefined)
    setSecurityError('')
    setCcrInherit(true)
    setSecurityLoading(true)
    api<ProjectSecurity>(`/api/projects/${project.id}/security`)
      .then((data) => {
        setSecurity(data)
        setCcrInherit(data.columnControlRequired == null)
        securityForm.setFieldsValue({
          securityMode: data.securityMode || 'INHERIT',
          defaultColumnLevel: data.defaultColumnLevel || undefined,
          columnControlRequired: data.columnControlRequired === true,
          confirm: '',
        })
      })
      .catch((e) => {
        setSecurityError(e instanceof Error ? e.message : '加载项目数据安全配置失败')
      })
      .finally(() => setSecurityLoading(false))
  }

  const saveEdit = async () => {
    if (!editing) return
    try {
      const values = await editForm.validateFields()
      const securityValues = security?.editable ? await securityForm.validateFields() : null
      // D40：全站唯一的项目保存点。Development.tsx 的「重命名项目」只发 name，
      // 其余字段一律回到这里改，避免两处各自拼 payload。
      // D41：PATCH 语义 —— 只发「真的改过」的字段；显式 null = 清空；没发的字段后端不动。
      const original = editing
      const body: Record<string, unknown> = {}
      if (values.name !== original.name) body.name = values.name
      const desc = values.description || ''
      if (desc !== (original.description || '')) body.description = desc === '' ? null : desc
      const dept = values.departmentId ?? null
      if (dept !== (original.department_id ?? null)) body.departmentId = dept
      // REQ-DW-1 ②：业务线从表单移除，保存时不再发 business（后端列保留，接口仍兼容显式传值）
      const dev = values.devDatabase || ''
      const prod = values.prodDatabase || ''
      // 库名不可清空：两个都填且与现值不同才发（成对），留空＝不改
      if (dev && prod && (dev !== (original.dev_database || '')
        || prod !== (original.prod_database || ''))) {
        body.devDatabase = dev
        body.prodDatabase = prod
      }
      const ds = values.warehouseDatasourceId ?? null
      if (ds !== (original.warehouse_datasource_id ?? null)) body.warehouseDatasourceId = ds
      if (Object.keys(body).length === 0) {
        message.info('没有改动，未提交')
        setEditing(undefined)
        return
      }
      await put(`/api/projects/${editing.id}`, body)
      // 只在真的有改动时提交安全策略，避免每次改名都写一条审计
      if (security && securityValues) {
        const nextMode = securityValues.securityMode || 'INHERIT'
        const nextLevel = securityValues.defaultColumnLevel || null
        const nextRequired = ccrInherit ? null : Boolean(securityValues.columnControlRequired)
        const changed = nextMode !== (security.securityMode || 'INHERIT')
          || nextLevel !== (security.defaultColumnLevel || null)
          || nextRequired !== (security.columnControlRequired ?? null)
        if (changed) {
          const body: Record<string, unknown> = {
            securityMode: nextMode,
            defaultColumnLevel: nextLevel,
            columnControlRequired: nextRequired,
          }
          if (nextMode === 'OFF') body.confirm = securityValues.confirm
          await put(`/api/projects/${editing.id}/security`, body)
        }
      }
      // D42：成员区块只在「真的改过」时提交（全量替换语义）
      if (members && members.manageable) {
        const values2 = await membersForm.validateFields()
        const rows: ProjectMember[] = (values2.rows || []).filter((r: ProjectMember) => r?.username)
        const ownerUser: string | null = values2.ownerUser || null
        const changed = JSON.stringify({
          ownerUser, rows: rows.map((r) => [r.username, r.role]).sort(),
        }) !== JSON.stringify({
          ownerUser: members.ownerUser || null,
          rows: (members.members || []).map((r) => [r.username, r.role]).sort(),
        })
        if (changed) {
          if (!rows.some((r) => r.role === 'OWNER')) {
            message.error('必须保留至少一个负责人（OWNER）')
            return
          }
          await put(`/api/projects/${editing.id}/members`, { members: rows, ownerUser })
        }
      }
      // D43：守卫开关只在真的有改动时提交（PUT 是平台级动作，别每次改名都写审计）
      if (sqlGuard && sqlGuardNext !== null && sqlGuardNext !== sqlGuard.sqlGuardEnabled) {
        await put(`/api/projects/${editing.id}/sql-guard`, {
          enabled: sqlGuardNext, reason: '项目设置页修改',
        })
      }
      setEditing(undefined)
      message.success('项目已更新')
      await load()
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '更新项目失败')
    }
  }

  const remove = (project: Project) => {
    Modal.confirm({
      title: `删除项目「${project.name}」？`,
      okText: '删除项目',
      content: (
        <div>
          <p style={{ marginBottom: 6 }}>会级联删除该项目下的全部内容，且不可恢复：</p>
          <ul style={{ margin: 0, paddingLeft: 18 }}>
            <li>文件（含版本、调度、实例索引）</li>
            <li>数据源、数据服务（含已发布 API 与 Token）</li>
            <li>质量规则与执行结果、告警规则</li>
            <li>数据集成任务、通知渠道</li>
          </ul>
          <p style={{ margin: '8px 0 0', color: '#8c8c8c' }}>
            为保护数据，Doris 库（{project.dev_database || '-'} / {project.prod_database || '-'}）
            本身不会被删除，需要清理请由 DBA 处理。
          </p>
        </div>
      ),
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/projects/${project.id}`)
          message.success('已删除')
          await load()
        } catch (e: any) {
          message.error(e?.message || '删除项目失败')
          throw e
        }
      },
    })
  }

  return (
    <div>
      <Card
        extra={(
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
            新建项目
          </Button>
        )}
      >
        {error ? <ErrorState title="「项目列表」加载失败" message={error} onRetry={load} /> : (
        <Table<Project>
          rowKey="id"
          loading={loading}
          dataSource={projects}
          pagination={false}
          scroll={{ x: 1120 }}
          locale={{ emptyText: tableEmpty('还没有项目', '点击右上角「新建项目」创建第一个项目') }}
          columns={[
            { title: '项目名称', dataIndex: 'name', width: 140 },
            {
              title: '编码',
              dataIndex: 'code',
              width: 170,
              render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
            },
            {
              title: '部门',
              width: 130,
              render: (_, row) => (row.department_name
                ? (
                  <Tooltip title={`${row.department_code} / ${row.department_dept_id}`}>
                    <span>{row.department_name}</span>
                  </Tooltip>
                )
                : <Typography.Text type="secondary">历史项目</Typography.Text>),
            },
            {
              title: '负责人',
              dataIndex: 'owner_user',
              width: 100,
              render: (value?: string) => (value
                ? <Tag color="gold">{value}</Tag>
                : <Typography.Text type="secondary">-</Typography.Text>),
            },
            {
              title: '数仓数据源',
              width: 220,
              ellipsis: true,
              render: (_, row) => (row.warehouse_datasource_name
                ? <Tag color="geekblue">{row.warehouse_datasource_name}</Tag>
                : <Typography.Text type="secondary">未配置（内置）</Typography.Text>),
            },
            {
              title: 'Doris 库（DEV / PROD）',
              width: 300,
              ellipsis: true,
              render: (_, row) => (row.dev_database ? (
                <Space size={4} wrap>
                  <Tag color="blue">{row.dev_database}</Tag>
                  <Tag color="green">{row.prod_database}</Tag>
                </Space>
              ) : <Typography.Text type="secondary">未建库（可在下方「回填库名」补齐）</Typography.Text>),
            },
            {
              title: '表名前缀',
              dataIndex: 'table_prefix',
              width: 90,
              render: (value?: string) => (value ? <Tag>{value}</Tag>
                : <Typography.Text type="secondary">-</Typography.Text>),
            },
            { title: '文件数', dataIndex: 'workflow_count', width: 80 },
            {
              title: '操作',
              width: 180,
              fixed: 'right',
              render: (_, row) => (
                <Space size={4}>
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    title="修改项目名称/描述"
                    onClick={() => openEdit(row)}
                  >
                    编辑
                  </Button>
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove(row)}>
                    删除
                  </Button>
                </Space>
              ),
            },
          ]}
        />
        )}
      </Card>

      <Card
        size="small"
        title="DEV / PROD 环境与空壳库"
        style={{ marginTop: 16 }}
        extra={(
          <Space wrap>
            <Button size="small" loading={envLoading} onClick={loadInventory}>空壳库巡检</Button>
            <Popconfirm
              title="按项目编码补齐缺失的 DEV/PROD 库名？"
              description="只写 platform.project 两列，不在 Doris 建库"
              onConfirm={() => backfillDatabases(false)}
            >
              <Button size="small" loading={envLoading}>回填库名</Button>
            </Popconfirm>
            <Popconfirm
              title="补齐库名并在 Doris 上真的建库？"
              description="同名库已存在且非空会报冲突，不会覆盖别人的数据"
              onConfirm={() => backfillDatabases(true)}
            >
              <Button size="small" danger loading={envLoading}>回填并建库</Button>
            </Popconfirm>
          </Space>
        )}
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          项目的 DEV/PROD 库是环境隔离的依据：试跑走开发库、发布走生产库，表管理也按它判要不要审批。
          新建项目不填库名时按编码自动生成（编码_dev / 编码_prd）；删项目只清掉
          「0 张表且没有别的项目引用」的空库，有数据一律保留。这两个按钮只给平台管理员。
        </Typography.Paragraph>
        {envError && <Alert type="error" showIcon message={envError} style={{ marginBottom: 8 }} />}
        {backfillNote && (
          <Alert type="success" showIcon message={backfillNote} style={{ marginBottom: 8 }} />
        )}
        {inventory && (
          <Table<EnvDatabase>
            rowKey="database"
            size="small"
            pagination={{ pageSize: 8 }}
            dataSource={inventory.databases.filter((row) => row.emptyShell)}
            locale={{
              emptyText: tableEmpty('没有空壳库',
                `${inventory.target} 共 ${inventory.total} 个库，没有「无表且无项目引用」的库`),
            }}
            columns={[
              { title: '库名', dataIndex: 'database' },
              { title: '表数', dataIndex: 'tables', width: 90 },
              {
                title: '引用项目',
                dataIndex: 'referencedBy',
                width: 200,
                render: (value: string[]) => (value || []).join('、') || '-',
              },
            ]}
          />
        )}
        {inventory && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {inventory.target}：共 {inventory.total} 个库，其中空壳库 {inventory.emptyShells} 个
            （上表只列空壳库；系统库不计入）
          </Typography.Text>
        )}
      </Card>

      <ProjectCreateModal
        open={creating}
        onCancel={() => setCreating(false)}
        onCreated={async () => {
          setCreating(false)
          await load()
        }}
      />
      <Modal
        title="编辑项目"
        open={Boolean(editing)}
        onOk={() => saveEdit()}
        onCancel={() => setEditing(undefined)}
        okText="保存"
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" preserve={false}>
          <Form.Item label="编码">
            <Input value={editing?.code} disabled />
          </Form.Item>
          <Form.Item
            label="项目名称"
            name="name"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="例如 销售域" maxLength={128} />
          </Form.Item>
          <Form.Item
            label="数仓数据源"
            name="warehouseDatasourceId"
            extra="只列 type=DORIS 的数据源；存储归属数据源，项目不再单独配置。"
          >
            <Select
              placeholder="选择 Doris 数据源"
              options={datasources.map((item) => ({
                value: item.id,
                label: `${item.name}${item.builtin ? '（内置）' : `（${item.jdbc_url || '外部'}）`}`,
              }))}
            />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item label="所属部门" name="departmentId"
                     extra="部门决定表名前缀等默认口径；改部门不会自动改已有库名。">
            <Select allowClear placeholder="选择部门"
                    options={departmentOptions(editing?.department_id)} />
          </Form.Item>
          {unselectableDepartment ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`该项目挂在不可选的部门（id=${unselectableDepartment}）下`}
              description={'部门可能已被删除或在当前租户不可见。保持不动则不会改动这一项；'
                + '要改成别的部门请从下拉里选，要取消归属请点「清空」后保存 —— 不会被静默清空。'}
            />
          ) : null}
          {/* REQ-DW-1 ②：业务线不再出现在表单（存量数据保留列，库名直接成对改）。 */}
          <Form.Item label="开发库名" name="devDatabase" dependencies={['prodDatabase']}
                     rules={[{
                       validator: (_, value) => {
                         const prod = editForm.getFieldValue('prodDatabase')
                         if (!value && !prod) return Promise.resolve()
                         if (value && prod) return Promise.resolve()
                         return Promise.reject(new Error('dev / prod 库名必须成对修改（都不填＝不改）'))
                       },
                     }]}
                     extra="改名会新建对应 Doris 库并让项目指向新库，旧库不会删除；必须与生产库名一起提交。">
            <Input placeholder="不改就留空" maxLength={64} />
          </Form.Item>
          <Form.Item label="生产库名" name="prodDatabase" dependencies={['devDatabase']}
                     rules={[{
                       validator: (_, value) => {
                         const dev = editForm.getFieldValue('devDatabase')
                         if (!value && !dev) return Promise.resolve()
                         if (value && dev) return Promise.resolve()
                         return Promise.reject(new Error('dev / prod 库名必须成对修改（都不填＝不改）'))
                       },
                     }]}
                     extra="与开发库名一起提交；两者都留空表示不改。">
            <Input placeholder="不改就留空" maxLength={64} />
          </Form.Item>
          <Form.Item label="存储类型"
                     extra="创建后不可改：存储归属数仓数据源，项目只引用一个 type=DORIS 的数据源。">
            <Input value={editing?.storage_type || 'doris'} disabled />
          </Form.Item>
          <Form.Item label="连接类字段（Doris / HDFS / S3）"
                     extra="创建后不可改，需要换连接请改「数仓数据源」或新建项目。">
            <Input value={`${editing?.warehouse_datasource_name || '内置 Doris'}（数仓数据源）`} disabled />
          </Form.Item>
        </Form>
        <Card
          size="small"
          title="数据安全"
          style={{ marginTop: 16 }}
          loading={securityLoading}
        >
          {securityError ? (
            <Alert type="error" showIcon message={securityError} />
          ) : security ? (
            <>
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message={`当前生效：${SECURITY_MODE_LABEL[security.effectiveMode] || security.effectiveMode}`}
                description={security.effectiveSource === 'PROJECT'
                  ? '项目已覆盖平台设置'
                  : `${SECURITY_SOURCE_LABEL[security.effectiveSource]}（当前 ${
                    SECURITY_MODE_LABEL[security.platform.mode] || security.platform.mode}）`}
              />
              {securityReadOnly && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="只有项目负责人或平台管理员可以修改，当前为只读"
                />
              )}
              <Form form={securityForm} component={false} layout="vertical">
                <Form.Item
                  name="securityMode"
                  label="数据安全粒度"
                  rules={[{ required: true, message: '请选择数据安全粒度' }]}
                >
                  <Radio.Group disabled={securityReadOnly}>
                    <Radio value="INHERIT">继承平台</Radio>
                    <Radio value="OFF">关闭</Radio>
                    <Radio value="TABLE">表级</Radio>
                    <Radio value="COLUMN">字段级</Radio>
                  </Radio.Group>
                </Form.Item>
                {securityMode === 'INHERIT' && (
                  <Typography.Paragraph type="secondary" style={{ marginTop: -8 }}>
                    {SECURITY_SOURCE_LABEL[security.effectiveSource]}（当前
                    {SECURITY_MODE_LABEL[security.effectiveMode] || security.effectiveMode}）
                  </Typography.Paragraph>
                )}
                <Form.Item
                  name="defaultColumnLevel"
                  label="默认列等级（新增列）"
                  extra={`不选表示继承平台（${security.platform.defaultColumnLevel}）`}
                >
                  <Select
                    allowClear
                    disabled={securityReadOnly}
                    placeholder={`继承平台（${security.platform.defaultColumnLevel}）`}
                    options={[
                      { value: 'L1', label: 'L1 公开' },
                      { value: 'L2', label: 'L2 内部' },
                      { value: 'L3', label: 'L3 敏感' },
                      { value: 'L4', label: 'L4 机密' },
                    ]}
                  />
                </Form.Item>
                <Form.Item label="强制字段级" tooltip="开启后该项目所有查询都按字段级脱敏，覆盖上面的粒度选择">
                  <Space>
                    <Form.Item name="columnControlRequired" valuePropName="checked" noStyle>
                      <Switch
                        disabled={securityReadOnly || ccrInherit}
                        checkedChildren="强制"
                        unCheckedChildren="不强制"
                      />
                    </Form.Item>
                    <Checkbox
                      checked={ccrInherit}
                      disabled={securityReadOnly}
                      onChange={(e) => {
                        setCcrInherit(e.target.checked)
                        if (e.target.checked) {
                          securityForm.setFieldValue('columnControlRequired', false)
                        }
                      }}
                    >
                      继承平台
                    </Checkbox>
                  </Space>
                </Form.Item>
                {securityMode === 'OFF' && (
                  <Form.Item
                    name="confirm"
                    label="关闭确认"
                    rules={[{
                      validator: (_, value) => (
                        securityForm.getFieldValue('securityMode') === 'OFF'
                          && String(value || '').trim().toUpperCase() !== 'CONFIRM'
                          ? Promise.reject(new Error('请输入 CONFIRM 关闭该项目的数据安全'))
                          : Promise.resolve()
                      ),
                    }]}
                  >
                    <Input
                      disabled={securityReadOnly}
                      placeholder="输入 CONFIRM 二次确认"
                      autoComplete="off"
                    />
                  </Form.Item>
                )}
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  生效方式（{security.platform.enforce === 'LOG' ? '只记录' : '强制拦截'}）
                  与阈值仍由平台统一配置，项目只覆盖粒度。
                </Typography.Paragraph>
              </Form>
            </>
          ) : null}
        </Card>
        <Card size="small" title="负责人与项目管理员" style={{ marginTop: 16 }}
              loading={membersLoading}>
          {membersError ? <Alert type="error" showIcon message={membersError} /> : null}
          {members && !members.manageable ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message="当前为只读：只有项目负责人本人或平台管理员可以配置成员"
              description={`现在的负责人是 ${members.ownerUser || '（未设置）'}。需要改动请让负责人或平台管理员操作。`}
            />
          ) : null}
          <Form form={membersForm} layout="vertical" preserve={false}
                disabled={!(members && members.manageable)}>
            <Form.Item label="负责人" name="ownerUser"
                       extra="负责人是项目库的归属人，也是权限申请的审批人；必须同时是下面列表里角色为「负责人」的那个人。">
              <Select allowClear showSearch placeholder="选择负责人"
                      options={Array.from(new Set([
                        ...(members?.members || []).map((m) => m.username),
                        ...(members?.ownerUser ? [members.ownerUser] : []),
                      ])).map((u) => ({ value: u, label: u }))} />
            </Form.Item>
            <Form.List name="rows">
              {(fields, { add, remove }) => (
                <>
                  {fields.map((field) => (
                    <Space key={field.key} align="baseline">
                      <Form.Item name={[field.name, 'username']}
                                 rules={[{ required: true, message: '成员用户名必填' }]}
                                 style={{ marginBottom: 4 }}>
                        <AutoComplete style={{ width: 220 }} placeholder="成员用户名"
                                      options={userOptions.map((u) => ({
                                        value: u.username,
                                        label: `${u.displayName || u.username}（${u.username}）`,
                                      }))} />
                      </Form.Item>
                      <Form.Item name={[field.name, 'role']}
                                 rules={[{ required: true, message: '角色必填' }]}
                                 style={{ marginBottom: 4 }}>
                        <Select style={{ width: 120 }}
                                options={[{ value: 'OWNER', label: '负责人' },
                                          { value: 'ADMIN', label: '管理员' }]} />
                      </Form.Item>
                      {members?.manageable ? (
                        <Button size="small" type="link" danger
                                onClick={() => remove(field.name)}>删除</Button>
                      ) : null}
                    </Space>
                  ))}
                  {members?.manageable ? (
                    <Button size="small" icon={<PlusOutlined />}
                            onClick={() => add({ role: 'ADMIN' })}>新增成员</Button>
                  ) : null}
                  {members?.manageable ? (
                    <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                      管理员与负责人一样可以改项目设置、审批项目内的权限申请；
                      但只有负责人本人或平台管理员能再改这份名单。
                    </Typography.Paragraph>
                  ) : null}
                </>
              )}
            </Form.List>
          </Form>
        </Card>
        <Card size="small" title="SQL 守卫（破坏性语句需审批）" style={{ marginTop: 16 }}>
          {sqlGuardError ? <Alert type="error" showIcon message={sqlGuardError} /> : null}
          {sqlGuard ? (
            <>
              <Space align="center" style={{ marginBottom: 8 }}>
                <Switch
                  checked={sqlGuardNext ?? sqlGuard.sqlGuardEnabled}
                  disabled={!sqlGuard.canManage}
                  onChange={(next) => {
                    if (!next) {
                      // 这是降低安全等级的动作：关掉前必须二次确认
                      Modal.confirm({
                        title: '关闭该项目的 SQL 守卫？',
                        okText: '关闭',
                        okButtonProps: { danger: true },
                        onOk: () => setSqlGuardNext(false),
                        onCancel: () => setSqlGuardNext(null),
                        content: (
                          <div>
                            <p style={{ marginBottom: 6 }}>
                              关闭后，该项目里的 DROP / TRUNCATE 等破坏性语句
                              <b>不再要求提交变更单审批</b>：
                              后端会直接拒绝建单（409「项目已关闭 SQL 守卫，可直接执行，无需建单」），
                              语句改由查询页直接执行。
                            </p>
                            <p style={{ margin: 0, color: '#8c8c8c' }}>
                              没有变更单也就没有「执行前单表备份」，误删无法靠平台回滚；
                              开关本身是平台级动作，会写审计。
                            </p>
                          </div>
                        ),
                      })
                      return
                    }
                    setSqlGuardNext(true)
                  }}
                />
                <Typography.Text>
                  {(sqlGuardNext ?? sqlGuard.sqlGuardEnabled) ? '已开启' : '已关闭'}
                </Typography.Text>
              </Space>
              {!sqlGuard.canManage ? (
                <Alert
                  type="warning"
                  showIcon
                  message="当前为只读：SQL 守卫是平台级开关"
                  description="需要 system:manage 且在默认租户下才能改 —— 租户管理员关掉自己租户的守卫，等于让自己的破坏性语句不经审批，不能由租户自己决定。"
                />
              ) : (
                <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
                  开启时：命中审批规则的语句必须走「SQL 变更」建单 → 审批 → 执行。
                  关闭时：该项目全部语句直接执行，不再建单。改动随「保存」一起提交，并写审计。
                </Typography.Paragraph>
              )}
            </>
          ) : (
            <Typography.Text type="secondary">读取当前守卫状态…</Typography.Text>
          )}
        </Card>
      </Modal>
    </div>
  )
}

const TAB_META: Record<string, { label: string; description: string }> = {
  projects: {
    label: '项目',
    description: '项目用于隔离开发与生产；新建项目会自动准备对应的开发库和生产库，表名默认带部门前缀',
  },
  departments: {
    label: '部门',
    description: '部门编码 + 部门ID 生成项目编码（gx_01_gongxiang）；表名前缀用于默认建表命名',
  },
  users: {
    label: '用户与权限',
    description: '角色矩阵：只读访客 / 运维 / 数据开发 / 平台管理员；改动立即生效',
  },
  roles: {
    label: '角色与权限点',
    description: '角色属于租户：内置角色只读，自定义角色按 57 个权限点勾选；成员角色覆盖式设置；平台管理员可在此建租户、邀请成员',
  },
}

export default function Projects() {
  const location = useLocation()
  const nav = useNavigate()
  const tab = TAB_BY_PATH[location.pathname] || 'projects'

  return (
    <div>
      <PageHeader title="管理中心" description={TAB_META[tab].description} />
      <Tabs
        activeKey={tab}
        onChange={(key) => nav(PATH_BY_TAB[key])}
        items={[
          { key: 'projects', label: TAB_META.projects.label, children: <ProjectPanel /> },
          {
            key: 'departments',
            label: TAB_META.departments.label,
            children: <Departments embedded />,
          },
          { key: 'users', label: TAB_META.users.label, children: <Users embedded /> },
          { key: 'roles', label: TAB_META.roles.label, children: <Roles embedded /> },
        ]}
      />
    </div>
  )
}
