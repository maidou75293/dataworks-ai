import { useEffect, useMemo, useState } from 'react'
import {
  Alert, Form, Input, Modal, Select, Space, Tag, Typography, message,
} from 'antd'
import { DatabaseOutlined, FolderOpenOutlined, LinkOutlined } from '@ant-design/icons'
import { Link } from 'react-router-dom'
import { api, post } from '../api'
import { currentUser } from '../auth'
import type { Department, Project } from '../types'

interface UserOption {
  username: string
  displayName: string
  role: string
  status: 'ACTIVE' | 'DISABLED'
}

/** V98：所有数仓都是数据源；项目只选择一个 type=DORIS 的数据源。 */
interface DataSourceOption {
  id: number
  project_id: number
  name: string
  ds_type: string
  jdbc_url?: string | null
  builtin?: boolean
}

/**
 * REQ-DW-1：前缀自动生成 + 提交前冲突提示的预览接口返回。
 * business/库名由后端按默认规则生成（部门编码+部门ID，撞库自动顺延），
 * skipped>0 表示默认前缀已被平台内其他项目占用、避让了几次。
 */
interface BusinessPreview {
  departmentId: number
  business: string
  code: string
  devDatabase: string
  prodDatabase: string
  tablePrefix: string | null
  skipped: number
}

interface Props {
  open: boolean
  onCancel: () => void
  onCreated: (project: Project) => void
}

/**
 * 新建项目：部门 + 项目名称（+ 负责人/数据源都有默认值）。
 * REQ-DW-1：表单精简——业务线和 DEV/PROD 库名不再手填，
 * 前缀按默认规则自动生成，冲突在提交前就提示，不让人手填撞库。
 * 存储不再挂在项目上（归数据源），所以弹窗里没有 HDFS/S3 选项。
 */
export default function ProjectCreateModal({ open, onCancel, onCreated }: Props) {
  const [form] = Form.useForm()
  const [departments, setDepartments] = useState<Department[]>([])
  const [users, setUsers] = useState<UserOption[]>([])
  const [datasources, setDatasources] = useState<DataSourceOption[]>([])
  const [auto, setAuto] = useState<BusinessPreview | null>(null)
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const departmentId = Form.useWatch('departmentId', form)
  const warehouseDatasourceId = Form.useWatch('warehouseDatasourceId', form)

  useEffect(() => {
    if (!open) return
    setError('')
    form.resetFields()
    setLoading(true)
    Promise.all([
      api<Department[]>('/api/departments'),
      api<UserOption[]>('/api/users').catch(() => [] as UserOption[]),
      api<DataSourceOption[]>('/api/datasources?type=DORIS').catch(() => [] as DataSourceOption[]),
    ])
      .then(([rows, people, sources]) => {
        setDepartments(rows || [])
        setUsers((people || []).filter((u) => u.status === 'ACTIVE'))
        const doris = (sources || []).filter((s) => s.ds_type === 'DORIS')
        setDatasources(doris)
        const builtin = doris.find((s) => s.builtin) || doris[0]
        if (builtin) form.setFieldValue('warehouseDatasourceId', builtin.id)
        if (rows?.length === 1) form.setFieldValue('departmentId', rows[0].id)
        const me = currentUser()
        const preferred = people?.find((u) => u.username === me && u.status === 'ACTIVE')
          || people?.find((u) => u.role === 'ADMIN' && u.status === 'ACTIVE')
          || people?.find((u) => u.status === 'ACTIVE')
        if (preferred) form.setFieldValue('ownerUser', preferred.username)
      })
      .catch((e) => setError(e instanceof Error ? e.message : '加载部门/数据源失败'))
      .finally(() => setLoading(false))
  }, [open])

  useEffect(() => {
    // REQ-DW-1：部门选定后拉「自动生成」预览；不选部门就没有可生成项
    if (!open || !departmentId) {
      setAuto(null)
      return
    }
    api<BusinessPreview>(`/api/projects/business-preview?departmentId=${departmentId}`)
      .then(setAuto)
      .catch(() => setAuto(null))
  }, [open, departmentId])

  const selectedDatasource = useMemo(
    () => datasources.find((item) => item.id === warehouseDatasourceId),
    [datasources, warehouseDatasourceId])

  const submit = async () => {
    try {
      const values = await form.validateFields()
      setSubmitting(true)
      const created = await post<Project>('/api/projects', {
        departmentId: values.departmentId,
        name: values.name,
        description: values.description || '',
        ownerUser: values.ownerUser,
        warehouseDatasourceId: values.warehouseDatasourceId,
      })
      message.success(`项目已创建，Doris 库：${created.dev_database} / ${created.prod_database}`)
      form.resetFields()
      onCreated(created)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '新建项目失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="新建项目"
      open={open}
      onOk={submit}
      confirmLoading={submitting}
      onCancel={onCancel}
      okText="创建项目"
      width={620}
      destroyOnClose
    >
      {error ? <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} /> : null}
      {!loading && departments.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="还没有部门，无法生成项目编码和 Doris 库名"
          description={(
            <span>
              先到 <Link to="/departments">平台管理 → 部门管理</Link> 创建部门（编码、部门ID、表名前缀）。
            </span>
          )}
        />
      ) : null}
      {!loading && datasources.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message="还没有可用的 Doris 数据源"
          description={(
            <span>
              先到 <Link to="/datasources">数据源</Link> 创建一个 Doris 数据源（V98 迁移会内置一个「内置 Doris」）。
            </span>
          )}
        />
      ) : null}
      <Form form={form} layout="vertical" preserve={false}>
        <Form.Item
          label="部门"
          name="departmentId"
          rules={[{ required: true, message: '请选择部门' }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择部门"
            options={departments.map((item) => ({
              value: item.id,
              label: `${item.name}（${item.code} / ${item.dept_id} / 表前缀 ${item.table_prefix}）`,
            }))}
          />
        </Form.Item>
        <Form.Item label="项目名称" name="name" rules={[{ required: true, message: '请输入项目名称' }]}>
          <Input placeholder="例如 共享出行" maxLength={128} />
        </Form.Item>
        <Form.Item label="负责人（owner）" name="ownerUser">
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择负责人"
            options={users.map((user) => ({
              value: user.username,
              label: `${user.displayName || user.username}（${user.username} / ${user.role}）`,
            }))}
          />
        </Form.Item>

        <Typography.Title level={5} style={{ marginTop: 4 }}>
          <DatabaseOutlined /> 数仓（Doris 数据源）
        </Typography.Title>
        <Form.Item
          name="warehouseDatasourceId"
          label="Doris 数据源"
          rules={[{ required: true, message: '请选择 Doris 数据源' }]}
          extra={(
            <span>
              只列 type=DORIS 的数据源；需要外部 Doris 请先到
              {' '}<Link to="/datasources">数据源</Link> 创建。
            </span>
          )}
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
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>

      {auto && auto.skipped > 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 12 }}
          message={`默认库名前缀已被其他项目占用，已自动顺延 ${auto.skipped} 次改用「${auto.business}」，无需手工修改`}
        />
      ) : null}
      <div className="project-preview">
        <div className="project-preview-title">创建后自动生成</div>
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space size={8} wrap>
            <LinkOutlined style={{ color: '#1677ff' }} />
            <Typography.Text type="secondary">项目编码</Typography.Text>
            <Typography.Text code>
              {auto ? auto.code : (departmentId ? '读取生成结果失败（仍可提交，后端按同规则生成）' : '选择部门后自动生成')}
            </Typography.Text>
          </Space>
          <Space size={8} wrap>
            <DatabaseOutlined style={{ color: '#1677ff' }} />
            <Typography.Text type="secondary">数仓数据源</Typography.Text>
            {selectedDatasource
              ? <Tag color={selectedDatasource.builtin ? 'geekblue' : 'blue'}>
                  {selectedDatasource.name}
                </Tag>
              : <Typography.Text type="secondary">请选择 DORIS 数据源</Typography.Text>}
          </Space>
          <Space size={8} wrap>
            <DatabaseOutlined style={{ color: '#1677ff' }} />
            <Typography.Text type="secondary">Doris 库（DEV / PROD）</Typography.Text>
            {auto ? (
              <><Tag color="blue">{auto.devDatabase}</Tag><Tag color="green">{auto.prodDatabase}</Tag></>
            ) : (
              <Typography.Text type="secondary">
                {departmentId ? '—' : '选部门后按「前缀_dev / 前缀_prd」自动生成'}
              </Typography.Text>
            )}
          </Space>
          <Space size={8} wrap>
            <FolderOpenOutlined style={{ color: '#1677ff' }} />
            <Typography.Text type="secondary">表名前缀</Typography.Text>
            {auto?.tablePrefix ? <Tag>{auto.tablePrefix}</Tag> : <Typography.Text type="secondary">继承部门</Typography.Text>}
          </Space>
        </Space>
      </div>
    </Modal>
  )
}
