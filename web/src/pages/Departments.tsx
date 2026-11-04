import { useEffect, useState } from 'react'
import {
  Alert, Button, Card, Form, Input, Modal, Space, Table, Tag, Typography, message,
} from 'antd'
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons'
import { api, del, post, put } from '../api'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import type { Department } from '../types'

const CODE_RULE = {
  pattern: /^[a-z][a-z0-9]{1,7}$/,
  message: '2-8 位小写字母/数字，以字母开头，例如 gx',
}
const DEPT_ID_RULE = {
  pattern: /^[0-9]{1,8}$/,
  message: '1-8 位数字，例如 01',
}
const PREFIX_RULE = {
  pattern: /^[a-z][a-z0-9_]{0,31}$/,
  message: '小写字母开头，只含小写字母、数字、下划线，例如 gx_',
}

/**
 * 部门管理：项目编码（gx_部门ID_业务）、Doris 库名（业务_dev / 业务_prd）
 * 与默认表名前缀都以这里为准。
 */
export default function Departments({ embedded = false }: { embedded?: boolean }) {
  const [rows, setRows] = useState<Department[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<Department | null>()
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm()
  const code = Form.useWatch('code', form)
  const deptId = Form.useWatch('deptId', form)

  const load = async () => {
    setLoading(true)
    try {
      setRows(await api<Department[]>('/api/departments'))
      setError('')
    } catch (e) {
      setError(e instanceof Error ? e.message : '未知原因')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  const open = (row?: Department) => {
    setEditing(row || null)
    form.resetFields()
    if (row) {
      form.setFieldsValue({
        code: row.code,
        deptId: row.dept_id,
        name: row.name,
        tablePrefix: row.table_prefix,
        ownerUser: row.owner_user || '',
        description: row.description || '',
      })
    }
  }

  const save = async () => {
    try {
      const values = await form.validateFields()
      setSubmitting(true)
      const body = {
        code: values.code,
        deptId: values.deptId,
        name: values.name,
        tablePrefix: values.tablePrefix || '',
        ownerUser: values.ownerUser || '',
        description: values.description || '',
      }
      if (editing) {
        await put(`/api/departments/${editing.id}`, body)
        message.success('部门已更新')
      } else {
        await post('/api/departments', body)
        message.success('部门已创建')
      }
      setEditing(undefined)
      await load()
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '保存部门失败')
    } finally {
      setSubmitting(false)
    }
  }

  const remove = (row: Department) => {
    Modal.confirm({
      title: `删除部门「${row.name}」？`,
      content: (row.project_count || 0) > 0
        ? `该部门下还有 ${row.project_count} 个项目，需要先把项目改挂到其他部门。`
        : '删除后不影响已存在的项目与 Doris 库。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/departments/${row.id}`)
          message.success('已删除')
          await load()
        } catch (e: any) {
          message.error(e?.message || '删除部门失败')
          throw e
        }
      },
    })
  }

  const previewCode = code && deptId ? `${code}_${deptId}_gongxiang` : ''

  const createButton = (
    <Button type="primary" icon={<PlusOutlined />} onClick={() => open()}>
      新建部门
    </Button>
  )

  return (
    <div>
      {!embedded && (
        <PageHeader
          title="部门管理"
          description="部门编码 + 部门ID 生成项目编码（gx_01_gongxiang）；表名前缀用于默认建表命名。"
          extra={createButton}
        />
      )}
      <Card extra={embedded ? createButton : undefined}>
        {error ? <ErrorState title="「部门列表」加载失败" message={error} onRetry={load} /> : (
          <Table<Department>
            rowKey="id"
            loading={loading}
            dataSource={rows}
            pagination={false}
            scroll={{ x: 980 }}
            locale={{
              emptyText: tableEmpty('还没有部门', '点击右上角「新建部门」，项目编码和 Doris 库名按部门规则生成'),
            }}
            columns={[
              {
                title: '部门编码',
                dataIndex: 'code',
                width: 100,
                render: (value: string) => <Typography.Text code>{value}</Typography.Text>,
              },
              { title: '部门ID', dataIndex: 'dept_id', width: 90 },
              { title: '部门名称', dataIndex: 'name', width: 160 },
              {
                title: '表名前缀',
                dataIndex: 'table_prefix',
                width: 110,
                render: (value: string) => <Tag color="blue">{value}</Tag>,
              },
              {
                title: '项目编码示例',
                width: 190,
                render: (_, row) => (
                  <Typography.Text code>{`${row.code}_${row.dept_id}_业务`}</Typography.Text>
                ),
              },
              { title: '负责人', dataIndex: 'owner_user', width: 110, render: (v) => v || '-' },
              { title: '项目数', dataIndex: 'project_count', width: 80 },
              { title: '描述', dataIndex: 'description', ellipsis: true },
              {
                title: '操作',
                width: 160,
                fixed: 'right',
                render: (_, row) => (
                  <Space size={4}>
                    <Button size="small" icon={<EditOutlined />} onClick={() => open(row)}>
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

      <Modal
        title={editing ? `编辑部门 · ${editing.name}` : '新建部门'}
        open={editing !== undefined}
        onOk={save}
        confirmLoading={submitting}
        onCancel={() => setEditing(undefined)}
        okText={editing ? '保存' : '创建'}
        width={520}
        destroyOnClose
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Space size={12} style={{ width: '100%' }} align="start">
            <Form.Item
              label="部门编码"
              name="code"
              rules={[{ required: true, message: '请输入部门编码' }, CODE_RULE]}
              style={{ width: 200 }}
              tooltip={(editing?.project_count || 0) > 0
                ? '部门下已有项目，编码不可修改' : '项目编码的第一段，例如 gx'}
            >
              <Input placeholder="gx" maxLength={8} disabled={(editing?.project_count || 0) > 0} />
            </Form.Item>
            <Form.Item
              label="部门ID"
              name="deptId"
              rules={[{ required: true, message: '请输入部门ID' }, DEPT_ID_RULE]}
              style={{ width: 160 }}
              tooltip={(editing?.project_count || 0) > 0
                ? '部门下已有项目，部门ID不可修改' : '项目编码的第二段，例如 01'}
            >
              <Input placeholder="01" maxLength={8} disabled={(editing?.project_count || 0) > 0} />
            </Form.Item>
          </Space>
          <Form.Item label="部门名称" name="name" rules={[{ required: true, message: '请输入部门名称' }]}>
            <Input placeholder="共享服务部" maxLength={64} />
          </Form.Item>
          <Form.Item
            label="表名前缀"
            name="tablePrefix"
            rules={[PREFIX_RULE]}
            tooltip="新建表时默认带上的前缀；留空按「部门编码_」生成"
          >
            <Input placeholder={code ? `${code}_` : 'gx_'} maxLength={32} />
          </Form.Item>
          <Form.Item label="负责人" name="ownerUser" tooltip="项目/库的默认负责人，可留空">
            <Input placeholder="admin" maxLength={64} />
          </Form.Item>
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
        <Alert
          type="info"
          showIcon
          message="命名规则"
          description={(
            <Space direction="vertical" size={4}>
              <span>
                项目编码：<Typography.Text code>{previewCode || '部门编码_部门ID_业务英文名'}</Typography.Text>
              </span>
              <span>
                Doris 库：<Typography.Text code>业务英文名_dev</Typography.Text>
                {' / '}
                <Typography.Text code>业务英文名_prd</Typography.Text>
                （新建项目时自动创建）
              </span>
            </Space>
          )}
        />
      </Modal>
    </div>
  )
}
