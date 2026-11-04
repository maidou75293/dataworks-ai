import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Button, Card, Form, Input, Modal, Select, Tag, Tree, Typography, message,
} from 'antd'
import {
  FileAddOutlined, FileTextOutlined, FolderAddOutlined, FolderOpenOutlined, ReloadOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { api, post } from '../api'
import { canAny } from '../permissions'

type Project = { id: number; code: string; name: string }
type Job = {
  id: number
  project_id: number
  code: string
  name: string
  folder?: string
  env?: 'DEV' | 'PROD'
  published_version?: number
  status: 'DRAFT' | 'SUBMITTING' | 'RUNNING' | 'STOPPED' | 'FAILED'
}
type FolderRow = { id: number; path: string }

const STATUS_COLOR: Record<string, string> = {
  RUNNING: 'processing', SUBMITTING: 'processing', FAILED: 'red',
  STOPPED: 'default', DRAFT: 'default',
}

type Selection = { kind: 'project' | 'folder' | 'job'; projectId: number; path: string; jobId?: number }

type TreeNode = {
  key: string
  icon: JSX.Element
  title: any
  isLeaf?: boolean
  selectable?: boolean
  children?: TreeNode[]
}

export default function StreamTree({ currentId, beforeNavigate, refreshKey }: {
  currentId?: number
  /** 返回 false 表示取消跳转（当前脚本有未保存修改） */
  beforeNavigate?: () => boolean
  /** 编辑器保存/跑完状态变化时传进来，树会跟着刷新名称与状态 */
  refreshKey?: string
}) {
  const nav = useNavigate()
  const canWrite = canAny('flink:write')
  const [projects, setProjects] = useState<Project[]>([])
  const [jobs, setJobs] = useState<Job[]>([])
  const [foldersByProject, setFoldersByProject] = useState<Record<number, string[]>>({})
  const [expanded, setExpanded] = useState<string[]>([])
  const [selection, setSelection] = useState<Selection | null>(null)
  const [scriptOpen, setScriptOpen] = useState(false)
  const [folderOpen, setFolderOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [scriptForm] = Form.useForm()
  const [folderForm] = Form.useForm()

  const load = useCallback(async () => {
    try {
      const [list, jobRows] = await Promise.all([
        api<Project[]>('/api/projects'),
        api<Job[]>('/api/flink/jobs'),
      ])
      const projectList = Array.isArray(list) ? list : []
      setProjects(projectList)
      setJobs(Array.isArray(jobRows) ? jobRows : [])
      const folderSets = await Promise.all(projectList.map((project) =>
        api<FolderRow[]>(`/api/flink/jobs/projects/${project.id}/flink-folders`)
          .catch(() => [])))
      const byProject: Record<number, string[]> = {}
      projectList.forEach((project, index) => {
        const rows = folderSets[index]
        byProject[project.id] = Array.isArray(rows) ? rows.map((row) => row.path) : []
      })
      setFoldersByProject(byProject)
      setExpanded((prev) => (prev.length ? prev : projectList.map((p) => `proj:${p.id}`)))
    } catch (e: any) {
      message.error(e?.message || '加载流任务树失败')
    }
  }, [])

  useEffect(() => { load() }, [load, currentId, refreshKey])

  const treeData = useMemo(() => projects.map((project) => {
    const allPaths = new Set<string>(foldersByProject[project.id] || [])
    jobs.filter((j) => j.project_id === project.id).forEach((j) => {
      if (j.folder) {
        const parts = j.folder.split('/')
        for (let i = 1; i <= parts.length; i++) {
          allPaths.add(parts.slice(0, i).join('/'))
        }
      }
    })

    const makeNodes = (parent: string): TreeNode[] => {
      const prefix = parent ? `${parent}/` : ''
      const children = Array.from(allPaths)
        .filter((path) => path.startsWith(prefix) && !path.slice(prefix.length).includes('/'))
        .sort()
      return children.map((path) => {
        const name = path.slice(prefix.length)
        const childNodes: TreeNode[] = makeNodes(path)
        const jobNodes = jobs
          .filter((j) => j.project_id === project.id && (j.folder || '') === path)
          .map((job) => ({
            key: `job:${job.id}`,
            icon: <FileTextOutlined />,
            isLeaf: true,
            title: (
              <span className="wf-tree-title" data-tree-key={`job:${job.id}`}>
                <span className="wf-tree-name" title={job.name}>{job.name}</span>
                {job.published_version ? <span className="wf-tree-env">v{job.published_version}</span> : null}
                <Tag style={{ marginInlineStart: 6, fontSize: 10, lineHeight: '16px' }}
                     color={STATUS_COLOR[job.status] || 'default'}>
                  {job.status === 'RUNNING' ? '运行' : job.status === 'FAILED' ? '失败'
                    : job.status === 'STOPPED' ? '停止' : '草稿'}
                </Tag>
              </span>
            ),
          }))
        return {
          key: `dir:${project.id}:${path}`,
          icon: <FolderOpenOutlined />,
          title: name,
          selectable: true,
          children: [...childNodes, ...jobNodes],
        }
      })
    }

    const rootJobs = jobs
      .filter((j) => j.project_id === project.id && !j.folder)
      .map((job) => ({
        key: `job:${job.id}`,
        icon: <FileTextOutlined />,
        isLeaf: true,
        title: (
          <span className="wf-tree-title" data-tree-key={`job:${job.id}`}>
            <span className="wf-tree-name" title={job.name}>{job.name}</span>
            {job.published_version ? <span className="wf-tree-env">v{job.published_version}</span> : null}
            <Tag style={{ marginInlineStart: 6, fontSize: 10, lineHeight: '16px' }}
                 color={STATUS_COLOR[job.status] || 'default'}>
              {job.status === 'RUNNING' ? '运行' : job.status === 'FAILED' ? '失败'
                : job.status === 'STOPPED' ? '停止' : '草稿'}
            </Tag>
          </span>
        ),
      }))

    return {
      key: `proj:${project.id}`,
      icon: <FolderOpenOutlined />,
      title: (
        <span>
          {project.name}
          {' '}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{project.code}</Typography.Text>
        </span>
      ),
      children: [...makeNodes(''), ...rootJobs],
    }
  }), [projects, jobs, foldersByProject])

  const parseSelection = (key: string): Selection | null => {
    if (key.startsWith('proj:')) {
      return { kind: 'project', projectId: Number(key.slice(5)), path: '' }
    }
    if (key.startsWith('dir:')) {
      const [, projectId, ...rest] = key.split(':')
      return { kind: 'folder', projectId: Number(projectId), path: rest.join(':') }
    }
    if (key.startsWith('job:')) {
      const job = jobs.find((j) => j.id === Number(key.slice(4)))
      if (!job) return null
      return { kind: 'job', projectId: job.project_id, path: job.folder || '', jobId: job.id }
    }
    return null
  }

  const openScript = (sel?: Selection | null) => {
    const target = sel || selection
    if (!target) {
      message.warning('先在左侧选中项目或目录')
      return
    }
    scriptForm.setFieldsValue({
      projectId: target.projectId,
      folder: target.path,
      name: '',
      code: '',
    })
    setScriptOpen(true)
  }

  const openFolder = (sel?: Selection | null) => {
    const target = sel || selection
    if (!target) {
      message.warning('先在左侧选中项目或目录')
      return
    }
    folderForm.setFieldsValue({ projectId: target.projectId, parent: target.path, name: '' })
    setFolderOpen(true)
  }

  const createScript = async () => {
    if (beforeNavigate && !beforeNavigate()) return
    try {
      const values = await scriptForm.validateFields()
      setSaving(true)
      const created = await post<Job>('/api/flink/jobs', {
        projectId: values.projectId,
        code: values.code,
        name: values.name,
        description: '',
        sqlText: '',
        sessionMode: 'ISOLATED',
        folder: values.folder || '',
      })
      message.success('已创建')
      setScriptOpen(false)
      await load()
      nav(`/stream/${created.id}`)
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '创建失败')
    } finally {
      setSaving(false)
    }
  }

  const createFolder = async () => {
    try {
      const values = await folderForm.validateFields()
      const path = [values.parent, values.name?.trim()].filter(Boolean).join('/')
      setSaving(true)
      await post(`/api/flink/jobs/projects/${values.projectId}/flink-folders`, { path })
      message.success('目录已创建')
      setFolderOpen(false)
      setExpanded((current) => Array.from(new Set([
        ...current,
        `proj:${values.projectId}`,
        values.parent ? `dir:${values.projectId}:${values.parent}` : '',
      ].filter(Boolean))))
      await load()
    } catch (e: any) {
      if (e?.errorFields) return
      message.error(e?.message || '建目录失败')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card
      size="small"
      title="项目结构"
      style={{ height: '100%' }}
      styles={{ body: { padding: 8, height: 'calc(100% - 39px)', overflow: 'auto' } }}
      extra={(
        <span>
          <Button size="small" type="text" icon={<ReloadOutlined />}
                  aria-label="刷新项目结构" title="刷新项目结构" onClick={load} />
          {canWrite && (
            <>
              <Button size="small" type="text" icon={<FolderAddOutlined />}
                      title="在选中目录下新建目录" onClick={() => openFolder()} />
              <Button size="small" type="text" icon={<FileAddOutlined />}
                      title="在选中目录下新建脚本" onClick={() => openScript()} />
            </>
          )}
        </span>
      )}
    >
      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
        项目 → 目录 → 流任务（脚本）；右键目录可新建
      </Typography.Text>
      <Tree
        showIcon
        blockNode
        className="project-tree"
        treeData={treeData}
        expandedKeys={expanded}
        selectedKeys={currentId ? [`job:${currentId}`] : []}
        onExpand={(keys) => setExpanded(keys.map(String))}
        onRightClick={({ event, node }) => {
          event.preventDefault()
          const sel = parseSelection(String(node.key))
          if (!sel) return
          setSelection(sel)
          Modal.confirm({
            title: sel.kind === 'folder' ? `目录：${sel.path}` : '项目根目录',
            content: '在这个位置新建什么？',
            okText: '新建脚本',
            cancelText: '新建目录',
            onOk: () => openScript(sel),
            onCancel: () => openFolder(sel),
          })
        }}
        onSelect={(keys, info) => {
          const key = keys[0] === undefined
            ? (info.node?.key === undefined ? undefined : String(info.node.key))
            : String(keys[0])
          if (!key) return
          const sel = parseSelection(key)
          if (sel) setSelection(sel)
          if (key.startsWith('job:')) {
            const next = key.slice(4)
            if (String(next) !== String(currentId ?? '') && beforeNavigate && !beforeNavigate()) return
            nav(`/stream/${next}`)
          }
        }}
      />

      <Modal
        open={scriptOpen} title="新建流任务（脚本）" onCancel={() => setScriptOpen(false)}
        onOk={createScript} confirmLoading={saving} okText="创建并编辑"
      >
        <Form form={scriptForm} layout="vertical">
          <Form.Item name="projectId" label="项目" rules={[{ required: true }]}>
            <Select disabled options={projects.map((p) => ({ value: p.id, label: p.name }))} />
          </Form.Item>
          <Form.Item name="folder" label="目录（相对项目根，可留空）">
            <Input placeholder="例如 实时/订单" />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true }]}>
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item name="code" label="编码" rules={[
            { required: true },
            { pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/, message: '字母开头，只含字母/数字/下划线' },
          ]}>
            <Input placeholder="例如 stream_orders" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={folderOpen} title="新建目录" onCancel={() => setFolderOpen(false)}
        onOk={createFolder} confirmLoading={saving} okText="创建"
      >
        <Form form={folderForm} layout="vertical">
          <Form.Item name="projectId" label="项目" rules={[{ required: true }]}>
            <Select disabled options={projects.map((p) => ({ value: p.id, label: p.name }))} />
          </Form.Item>
          <Form.Item name="parent" label="上级目录">
            <Input disabled />
          </Form.Item>
          <Form.Item name="name" label="目录名" rules={[
            { required: true },
            { pattern: /^[A-Za-z0-9_一-龥-]{1,64}$/, message: '只允许字母/数字/下划线/中文/短横线' },
          ]}>
            <Input placeholder="例如 实时" />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
