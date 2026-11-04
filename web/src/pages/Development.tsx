import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, Button, Card, Col, Descriptions, Drawer, Form, Input, Modal, Row, Select, Space, Table,
  Tag, Tooltip, Tree, Typography, message,
} from 'antd'
import {
  AppstoreAddOutlined, CloudUploadOutlined, DatabaseOutlined, DeleteOutlined, EditOutlined, ExportOutlined,
  EyeOutlined, FileOutlined, FileTextOutlined, FolderOpenOutlined, PlusOutlined,
  ReloadOutlined, SearchOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import { api, errMsg, del, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import { ENV_OPTIONS, ENV_DEV_DEFAULT_HINT } from '../envOptions'
import MemoTableRow from '../components/MemoTableRow'
import ImeInput from '../components/ImeInput'
import ProjectCreateModal from '../components/ProjectCreateModal'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { useGlobalProject } from '../project'
import { buildProjectNode, projectDirPaths } from '../projectTree'
import { RunStateTag } from '../status'
import type { Instance, Project, TaskInstance, Workflow } from '../types'

const DONE_STATES = ['success', 'failed', 'cancelled', 'upstream_failed']

const folderMemoryKey = (projectId?: number) => `dw_dev_folder_${projectId ?? 0}`

/** 记住当前目录：从编辑器返回时还停在这个目录，不会被甩回「全部工作流」。 */
function rememberFolder(projectId: number | undefined, path: string | null) {
  if (!projectId) return
  try {
    sessionStorage.setItem(folderMemoryKey(projectId), path || '')
  } catch {
    // 隐私模式下 sessionStorage 可能不可用，记不住不影响功能
  }
}

/** 记住最后打开的工作流：返回列表时它在树里保持选中，方便接着看。 */
function rememberWorkflow(projectId: number | undefined, workflowId: number | null) {
  if (!projectId) return
  try {
    sessionStorage.setItem(`dw_dev_wf_${projectId}`, workflowId ? String(workflowId) : '')
  } catch {
    // 忽略隐私模式下的存储失败
  }
}

function recallWorkflow(projectId: number | undefined): number | undefined {
  if (!projectId) return undefined
  try {
    return Number(sessionStorage.getItem(`dw_dev_wf_${projectId}`)) || undefined
  } catch {
    return undefined
  }
}

function recallFolder(projectId: number | undefined): string | null {
  if (!projectId) return null
  try {
    return sessionStorage.getItem(folderMemoryKey(projectId)) || null
  } catch {
    return null
  }
}

/** a/b/c → [a, a/b, a/b/c]：新建/移动后只把受影响的层级合进树。 */
function folderAncestors(path: string): string[] {
  const parts = path.split('/').filter(Boolean)
  return parts.map((_, index) => parts.slice(0, index + 1).join('/'))
}

function addFolderPaths(current: string[], path: string): string[] {
  return Array.from(new Set([...current, ...folderAncestors(path)]))
}

const FILE_TYPES = ['SQL', 'SHELL', 'PYTHON']

/**
 * SQL 节点的执行引擎：Doris（默认，跟随项目数仓）、Spark（Spark Thrift，同步返回）、
 * Flink（流式异步提交）。写入任务 JSON 的 `engine` 字段；老模型没有该字段时后端按 DORIS。
 */
const FILE_ENGINES = [
  { value: 'DORIS', label: 'Doris（项目数仓）' },
  { value: 'SPARK', label: 'Spark（Spark SQL）' },
  { value: 'FLINK', label: 'Flink（流式 SQL，异步提交）' },
]

/** 新文件自带一个同类型的脚本，打开就能写：不用先摸「新建脚本」在哪。 */
function firstScript(type: string, name: string): string {
  if (type === 'SHELL') return `#!/usr/bin/env bash\nset -euo pipefail\n\necho "${name}"\n`
  if (type === 'PYTHON') return `# ${name}\nprint("hello")\n`
  return `-- ${name}\nselect 1 as v;\n`
}

function allFolderKeys(workflows: Workflow[], folders: string[]): string[] {
  const keys = new Set<string>()
  const add = (path: string) => {
    const parts = path.split('/').filter(Boolean)
    parts.forEach((_, index) => keys.add(`dir:${parts.slice(0, index + 1).join('/')}`))
  }
  folders.forEach(add)
  workflows.forEach((wf) => { if (wf.folder) add(wf.folder) })
  return Array.from(keys)
}

export default function Development() {
  const nav = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const [projects, setProjects] = useState<Project[]>([])
  // 当前项目是全局的：在编辑器、数据源、数据质量切过项目，这里打开就是那个项目
  const [projectId, setProjectId] = useGlobalProject()
  const [workflows, setWorkflows] = useState<Workflow[]>([])
  const [folders, setFolders] = useState<string[]>([])
  // selectedFolder：树里当前选中的目录。跟资源管理器一样，点目录就在右侧看它的文件，
  // 也决定「新建文件」落在哪；点项目根回到全部文件。
  const [selectedFolder, setSelectedFolder] = useState<string | null>(null)
  // 选中项目根：目录操作（新建一级目录）收在树下方的上下文操作里，
  // 卡片右上只保留「新建项目」
  const [rootSelected, setRootSelected] = useState(false)
  const [selectedWorkflow, setSelectedWorkflow] = useState<number | undefined>()
  const [keyword, setKeyword] = useState('')
  const [folderOpen, setFolderOpen] = useState(false)
  const [newFolderPath, setNewFolderPath] = useState('')
  const [projectOpen, setProjectOpen] = useState(false)
  // 移动到其他文件夹：文件/文件夹共用一套弹窗，只选目标目录
  const [moveItem, setMoveItem] = useState<
    { kind: 'workflow'; wf: Workflow } | { kind: 'folder'; path: string }
  >()
  const [moveDest, setMoveDest] = useState('')
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  // 右键菜单：位置 + 被点的节点 key
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number; key: string } | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [publishingId, setPublishingId] = useState<number>()
  const [instOpen, setInstOpen] = useState(false)
  const [instWorkflow, setInstWorkflow] = useState<Workflow>()
  const [instances, setInstances] = useState<Instance[]>([])
  const [instLoading, setInstLoading] = useState(false)
  const [detail, setDetail] = useState<(Instance & { tasks: TaskInstance[] })>()
  const [detailOpen, setDetailOpen] = useState(false)
  const [log, setLog] = useState('')
  const [logOpen, setLogOpen] = useState(false)
  const [form] = Form.useForm()
  // 新建文件弹窗：只有 SQL 需要选执行引擎，SHELL/PYTHON 不显示
  const newFileType = Form.useWatch('type', form)
  // 项目名称由创建者（当前即管理员）自己改，不用去管理中心
  const [renameOpen, setRenameOpen] = useState(false)
  const [renameForm] = Form.useForm()

  /** 选目录＝在右侧看这个目录的文件，并记住下次回来还停在里。 */
  const selectFolder = (path: string | null) => {
    setSelectedFolder(path)
    rememberFolder(projectId, path)
  }

  const loadProjects = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    // 优先用 ?project=（编辑器里切项目后跳到这里的地址），其次是全局记住的项目，
    // 都无效时落到第一个项目。
    const wanted = Number(searchParams.get('project')) || projectId
    const next = list.find((item) => item.id === wanted) ?? list[0]
    if (next && next.id !== projectId) setProjectId(next.id)
  }

  // 快速切项目时旧项目的响应不能覆盖新项目
  const loadSeq = useRef(0)
  // 首次/切项目才「全展开」；之后的刷新保留用户当前的展开状态
  const loadedProjectRef = useRef<number | undefined>()
  const loadWorkflows = async (pid = projectId) => {
    if (!pid) {
      setWorkflows([])
      setFolders([])
      return
    }
    const seq = ++loadSeq.current
    setLoading(true)
    setError('')
    try {
      const [list, folderRows] = await Promise.all([
        api<Workflow[]>(`/api/projects/${pid}/workflows`),
        api<{ path: string }[]>(`/api/projects/${pid}/workflow-folders`).catch(() => []),
      ])
      if (seq !== loadSeq.current) return
      const folderPaths = (folderRows || []).map((row) => row.path)
      const firstLoad = loadedProjectRef.current !== pid
      loadedProjectRef.current = pid
      setWorkflows(list)
      setFolders(folderPaths)
      setExpandedKeys((current) => (firstLoad || current.length === 0
        ? ['root', ...allFolderKeys(list, folderPaths)]
        : current))
      // 记住的目录被删掉时回到全部，避免停在一个不存在的目录上
      setSelectedFolder((current) => (
        current && !folderPaths.some((path) => path === current || path.startsWith(`${current}/`))
          ? null
          : current
      ))
    } catch (e) {
      if (seq !== loadSeq.current) return
      setError(e instanceof Error ? e.message : '加载文件失败')
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }

  useEffect(() => {
    loadProjects().catch((e) => message.error(errMsg(e)))
  }, [])

  useEffect(() => {
    // 切项目才重置到该项目上次停留的目录
    const remembered = recallFolder(projectId)
    setSelectedFolder(remembered)
    setRootSelected(!remembered)
    setSelectedWorkflow(recallWorkflow(projectId))
    loadWorkflows().catch((e) => message.error(errMsg(e)))
  }, [projectId])

  /** 页头切项目：树只展示当前项目，切完回到该项目上次停留的文件夹。 */
  const switchProject = (pid: number) => {
    if (!pid) return
    setProjectId(pid)
    setSearchParams({ project: String(pid) }, { replace: true })
    setSelectedWorkflow(undefined)
    setRootSelected(false)
    setExpandedKeys(['root'])
  }

  /**
   * 右键只给资源管理器里的常识操作，别的一律不放：
   * 项目＝新建文件夹/新建文件；文件夹＝新建文件/删除文件夹/移动到其他文件夹；
   * 文件＝删除/移动到其他文件夹。打开文件＝单击，不占菜单。
   */
  const ctxMenuItems = (key: string): {
    label: string
    danger?: boolean
    onClick: () => void
  }[] => {
    if (!can('workflow:create')) return []
    if (key === 'root') {
      return [
        {
          label: '新建文件夹',
          onClick: () => {
            setNewFolderPath('')
            setFolderOpen(true)
          },
        },
        {
          label: '新建文件',
          onClick: () => {
            form.setFieldsValue({ env: 'DEV', folder: '' })
            setCreating(true)
          },
        },
      ]
    }
    if (key.startsWith('dir:')) {
      const path = key.slice(4)
      return [
        {
          label: '新建文件夹',
          onClick: () => {
            selectFolder(path)
            setNewFolderPath(`${path}/`)
            setFolderOpen(true)
          },
        },
        {
          label: '新建文件',
          onClick: () => {
            selectFolder(path)
            form.setFieldsValue({ env: 'DEV', folder: path })
            setCreating(true)
          },
        },
        { label: '删除文件夹', danger: true, onClick: () => removeFolder(path) },
        {
          label: '移动到其他文件夹',
          onClick: () => {
            setMoveItem({ kind: 'folder', path })
            setMoveDest('')
          },
        },
      ]
    }
    if (key.startsWith('wf:')) {
      const wf = workflows.find((item) => item.id === Number(key.slice(3)))
      if (!wf) return []
      return [
        { label: '删除文件', danger: true, onClick: () => remove(wf) },
        {
          label: '移动到其他文件夹',
          onClick: () => {
            setMoveItem({ kind: 'workflow', wf })
            setMoveDest(wf.folder || '')
          },
        },
      ]
    }
    return []
  }

  // 右键菜单点外面就关
  useEffect(() => {
    if (!ctxMenu) return
    const close = () => setCtxMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [ctxMenu])

  const createWorkflow = async () => {
    const values = await form.validateFields()
    if (!projectId) {
      message.warning('请先选择项目')
      return
    }
    const { type = 'SQL', engine, ...rest } = values
    // SQL 文件必须明确选引擎（表单已 required；这里再兜一层，避免空值落库后按 DORIS 静默执行）
    if (type === 'SQL' && !engine) {
      message.error('请选择执行引擎（DORIS / SPARK / FLINK）')
      return
    }
    // 新文件直接带一个同类型脚本：打开就有可编辑的 SQL/Shell/Python
    const wf = await post<Workflow>(`/api/projects/${projectId}/workflows`, {
      ...rest,
      model: {
        tasks: [{
          code: 'task_1',
          name: rest.name,
          type,
          ...(type === 'SQL' ? { engine } : {}),
          script: firstScript(type, rest.name),
          datasource: 'DORIS',
          retries: 0,
          x: 140,
          y: 120,
        }],
        edges: [],
        files: [],
        folders: [],
      },
    })
    setCreating(false)
    form.resetFields()
    message.success('文件已创建')
    nav(`/dev/${wf.id}`)
  }

  /** 就地新建项目：建完自动切到新项目，不用先去管理中心。 */
  const onProjectCreated = async (created: Project) => {
    setProjectOpen(false)
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    setProjectId(created.id)
  }

  /**
   * 重命名当前项目：D40 起这里**只改名称**。
   *
   * <p>以前这里也发 description，与项目设置页各拼一份 payload：同一件事两个入口、
   * 字段集还不一样（项目设置发 3 个字段、这里发 2 个），用户不知道以哪边为准。
   * 现在唯一的「完整项目编辑」入口是 管理中心 / 项目设置（Projects.tsx）。</p>
   */
  const openRenameProject = () => {
    const current = projects.find((item) => item.id === projectId)
    if (!current) return
    renameForm.setFieldsValue({ name: current.name })
    setRenameOpen(true)
  }

  const renameProject = async () => {
    if (!projectId) return
    try {
      const values = await renameForm.validateFields()
      // 只发 name：其它字段不在这里改（见上方注释），避免两处各自拼 payload
      await put<Project>(`/api/projects/${projectId}`, { name: values.name })
      // 保存后重新拉取，不靠本地合并（后端可能改写了值）
      setProjects(await api<Project[]>('/api/projects'))
      setRenameOpen(false)
      message.success('项目名称已更新')
    } catch (e) {
      if ((e as { errorFields?: unknown }).errorFields) return
      message.error(e instanceof Error ? e.message : '更新项目名称失败')
    }
  }

  /**
   * 先建空文件夹，再把文件拖/移进去；建完自动选中。
   * 只把新目录合进树（并展开父级），不重新拉取整棵树——别的节点不闪、展开状态不变。
   */
  const createFolder = async () => {
    if (!projectId) return
    const path = newFolderPath.trim().replace(/^\/+|\/+$/g, '')
    if (!path) {
      message.warning('请输入目录路径')
      return
    }
    try {
      await post(`/api/projects/${projectId}/workflow-folders`, { path })
      setFolders((current) => addFolderPaths(current, path))
      setExpandedKeys((current) => Array.from(new Set([
        ...current,
        'root',
        ...folderAncestors(path).map((item) => `dir:${item}`),
      ])))
      setFolderOpen(false)
      setNewFolderPath('')
      setRootSelected(false)
      selectFolder(path)
      message.success('文件夹已创建')
    } catch (e: any) {
      message.error(e.message || '创建文件夹失败')
    }
  }

  const removeFolder = (path: string) => {
    if (!projectId) return
    Modal.confirm({
      title: `删除文件夹「${path}」？`,
      content: '文件夹下还有文件或子文件夹时需要先移走或删除；空文件夹会直接删除。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/projects/${projectId}/workflow-folders?path=${encodeURIComponent(path)}`)
          // 局部删除：只摘掉这个目录和它的子目录，其余节点保持原样
          setFolders((current) => current.filter(
            (item) => item !== path && !item.startsWith(`${path}/`)))
          setExpandedKeys((current) => current.filter(
            (key) => key !== `dir:${path}` && !key.startsWith(`dir:${path}/`)))
          if (selectedFolder === path || (selectedFolder || '').startsWith(`${path}/`)) {
            selectFolder(null)
            setRootSelected(true)
          }
          message.success('文件夹已删除')
        } catch (e: any) {
          // 后端拒绝（文件夹里还有文件/子文件夹）时给明确原因；
          // 这里不再把错误抛回去，否则 antd 会留下一个没人处理的 Promise 拒绝
          message.error(e.message || '删除文件夹失败')
        }
      },
    })
  }

  /** 移动文件夹后，把本地树里同一棵子树的前缀换掉，不重新拉取。 */
  const applyFolderMoveLocally = (from: string, to: string) => {
    const remap = (value: string) => (
      value === from || value.startsWith(`${from}/`)
        ? `${to}${value.slice(from.length)}`
        : value
    )
    setFolders((current) => Array.from(new Set(current.map(remap))))
    setWorkflows((current) => current.map((item) => (
      item.folder ? { ...item, folder: remap(item.folder) } : item
    )))
    setExpandedKeys((current) => Array.from(new Set([
      ...current.filter((key) => key !== `dir:${from}` && !key.startsWith(`dir:${from}/`)),
      'root',
      ...folderAncestors(to).map((item) => `dir:${item}`),
    ])))
    if (selectedFolder && (selectedFolder === from || selectedFolder.startsWith(`${from}/`))) {
      const next = remap(selectedFolder)
      setSelectedFolder(next)
      rememberFolder(projectId, next)
    }
  }

  const moveToFolder = async () => {
    if (!moveItem) return
    const dest = moveDest.trim()
    try {
      if (moveItem.kind === 'workflow') {
        await put(`/api/workflows/${moveItem.wf.id}/folder`, { path: dest })
        setWorkflows((current) => current.map((item) => (
          item.id === moveItem.wf.id ? { ...item, folder: dest } : item
        )))
        setExpandedKeys((current) => Array.from(new Set([
          ...current,
          'root',
          ...folderAncestors(dest).map((item) => `dir:${item}`),
        ])))
        message.success(dest ? `已移动到 ${dest}` : '已移动到项目根')
      } else {
        const moved = await put<{ from: string; to: string }>(
          `/api/projects/${projectId}/workflow-folders`,
          { from: moveItem.path, to: dest },
        )
        applyFolderMoveLocally(moved.from, moved.to)
        message.success(`已移动到 ${moved.to}`)
      }
      setMoveItem(undefined)
    } catch (e: any) {
      message.error(e.message || '移动失败')
    }
  }

  const remove = (wf: Workflow) => {
    Modal.confirm({
      title: `删除文件「${wf.name}」？`,
      content: '会同时删除版本、调度与实例索引，已挂载的调度会一并下线。',
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await del(`/api/workflows/${wf.id}`)
          // 局部删除：只摘掉这一行
          setWorkflows((current) => current.filter((item) => item.id !== wf.id))
          if (selectedWorkflow === wf.id) {
            setSelectedWorkflow(undefined)
            rememberWorkflow(projectId, null)
          }
          message.success('已删除')
        } catch (e: any) {
          // 以前删除失败时确认框既不关闭也不报错，用户只看到「没反应」
          message.error(e?.message || '删除文件失败')
          throw e
        }
      },
    })
  }

  /** 发布只改这一行的版本字段：不整树刷新，展开状态和选中都不动。 */
  const patchWorkflow = (wfId: number, patch: Partial<Workflow>) => {
    setWorkflows((current) => current.map((item) => (
      item.id === wfId ? { ...item, ...patch } : item
    )))
  }

  /** 发布到线上：先提示当前是否已发布，再创建版本并提交/审批 PROD。 */
  const release = (wf: Workflow) => {
    const online = wf.prod_version
      ? `当前已发布：v${wf.prod_version}${wf.prod_published_at ? `（${dayjs(wf.prod_published_at).format('YYYY-MM-DD HH:mm')}）` : ''}`
      : '当前尚未发布'
    // 与流开发同一条规则：DEV 跑通才能发布。后端 requireDevSuccess 会拦，
    // 这里提前拦一次，避免用户点完「发布」才看到 400，也避免白建一个版本。
    if (!wf.prod_pending_version && Number(wf.dev_success_count || 0) === 0) {
      Modal.warning({
        title: 'DEV 还没跑通，不能发布',
        content: '发布前必须先在开发环境跑通：发布 DEV → 触发一次 → 等这次运行成功，再回来发布。'
          + '（流开发里对应的动作是「DEV 跑通」）',
        okText: '知道了',
      })
      return
    }
    if (wf.prod_pending_version) {
      if (!can('workflow:approve')) {
        message.info(`v${wf.prod_pending_version} 已提交，等待平台管理员审批`)
        return
      }
      Modal.confirm({
        title: '发布审批',
        content: `${online}。v${wf.prod_pending_version} 已提交线上审批，是否立即审批并发布？`,
        okText: '审批并发布',
        onOk: async () => {
          try {
            await post(`/api/workflows/${wf.id}/versions/${wf.prod_pending_version}/approve`, { env: 'PROD' })
            message.success(`已发布到线上环境：v${wf.prod_pending_version}`)
            patchWorkflow(wf.id, {
              prod_version: wf.prod_pending_version,
              prod_pending_version: undefined,
              prod_published_at: new Date().toISOString(),
            })
          } catch (e) {
            message.error(e instanceof Error ? e.message : '审批发布失败')
            throw e
          }
        },
      })
      return
    }
    Modal.confirm({
      title: '发布到线上环境',
      content: `${online}。将基于当前已保存的编排提交线上发布；内容没有变化时复用最近版本。`,
      okText: '发布',
      onOk: async () => {
        setPublishingId(wf.id)
        try {
          const ver = await post<{ version_no: number; created?: boolean }>(
            `/api/workflows/${wf.id}/versions`, {})
          const pub = await post<{ status: string }>(
            `/api/workflows/${wf.id}/versions/${ver.version_no}/publish`, { env: 'PROD' },
          )
          const versionCount = (wf.version_count ?? 0) + (ver.created ? 1 : 0)
          if (pub.status === 'PENDING_APPROVAL') {
            patchWorkflow(wf.id, {
              version_count: versionCount,
              prod_pending_version: ver.version_no,
            })
            if (can('workflow:approve')) {
              Modal.confirm({
                title: `已提交线上审批（v${ver.version_no}）`,
                content: '是否现在审批并发布到线上环境？',
                okText: '审批并发布',
                onOk: async () => {
                  try {
                    await post(`/api/workflows/${wf.id}/versions/${ver.version_no}/approve`, { env: 'PROD' })
                    message.success(`已发布到线上环境：v${ver.version_no}`)
                    patchWorkflow(wf.id, {
                      prod_version: ver.version_no,
                      prod_pending_version: undefined,
                      prod_published_at: new Date().toISOString(),
                    })
                  } catch (e) {
                    message.error(e instanceof Error ? e.message : '审批发布失败')
                    throw e
                  }
                },
              })
            } else {
              message.success(`已提交线上审批（v${ver.version_no}），等待平台管理员审批`)
            }
          } else {
            message.success(`已发布到线上环境：v${ver.version_no}`)
            patchWorkflow(wf.id, {
              version_count: versionCount,
              prod_version: ver.version_no,
              prod_pending_version: undefined,
              prod_published_at: new Date().toISOString(),
            })
          }
        } catch (e) {
          message.error(e instanceof Error ? e.message : '发布失败')
          throw e
        } finally {
          setPublishingId(undefined)
        }
      },
    })
  }

  const openInstances = async (wf: Workflow) => {
    setInstWorkflow(wf)
    setInstances([])
    setInstOpen(true)
    setInstLoading(true)
    try {
      setInstances(await api<Instance[]>(`/api/ops/instances?workflowId=${wf.id}&limit=100`))
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载运行实例失败')
    } finally {
      setInstLoading(false)
    }
  }

  const openInstanceDetail = async (row: Instance) => {
    try {
      setDetail(await api<Instance & { tasks: TaskInstance[] }>(`/api/ops/instances/${row.id}`))
      setDetailOpen(true)
    } catch (e) {
      message.error(e instanceof Error ? e.message : '加载实例详情失败')
    }
  }

  // 详情没跑完时 3 秒刷新：任务明细和失败原因自己跟上，不用关掉再点开
  const detailId = detail?.id
  const detailState = detail?.state
  useEffect(() => {
    if (!detailOpen || !detailId || DONE_STATES.includes(detailState || '')) return
    let alive = true
    const timer = window.setInterval(async () => {
      try {
        const fresh = await api<Instance & { tasks: TaskInstance[] }>(`/api/ops/instances/${detailId}`)
        if (alive) setDetail(fresh)
      } catch {
        // 单次失败忽略
      }
    }, 3000)
    return () => { alive = false; window.clearInterval(timer) }
  }, [detailOpen, detailId, detailState])

  const openLog = async (instanceId: number, taskCode: string) => {
    try {
      const data = await api<{ log: string }>(`/api/ops/instances/${instanceId}/tasks/${taskCode}/log`)
      setLog(data.log || '（空日志）')
      setLogOpen(true)
    } catch (e) {
      message.error(`「任务日志」加载失败：${e instanceof Error ? e.message : '未知原因'}`)
    }
  }

  const term = keyword.trim().toLowerCase()
  // 像资源管理器：选中文件夹，右边就只看这个文件夹（含子文件夹）的文件；
  // 有搜索词时跨全部目录查找，避免「明明有这个文件，却因为停在别的目录搜不到」
  // v452：过滤结果缓存，输入关键字时不必每次重算整棵树
  const shownWorkflows = useMemo(() => {
    const scoped = term || !selectedFolder
      ? workflows
      : workflows.filter((wf) => {
          const folder = wf.folder || ''
          return folder === selectedFolder || folder.startsWith(`${selectedFolder}/`)
        })
    return term
      ? scoped.filter((wf) => `${wf.name} ${wf.code}`.toLowerCase().includes(term))
      : scoped
  }, [workflows, selectedFolder, term])
  // 树只展示当前项目：项目 → 多级文件夹 → 文件（工作流）。
  // 项目列表是全局的，用页头右上角的下拉框切换。
  const currentProject = projects.find((item) => item.id === projectId)
  const projectTree = useMemo(() => (currentProject ? [buildProjectNode({
    key: 'root',
    title: <strong>{`${currentProject.name}（${currentProject.code}）`}</strong>,
    searchText: `${currentProject.name} ${currentProject.code}`,
    data: { workflows, folders },
    dirKey: (path) => `dir:${path}`,
    workflowNode: (wf) => ({
      key: `wf:${wf.id}`,
      title: wf.name,
      searchText: `${wf.name} ${wf.code}`,
      isLeaf: true,
      icon: <FileTextOutlined />,
    }),
  })] : []), [currentProject, workflows, folders])

  // 树下方操作条跟右键同一套语义；没选中时按项目根处理
  const treeSel = selectedFolder
    ? `dir:${selectedFolder}`
    : selectedWorkflow ? `wf:${selectedWorkflow}` : 'root'

  // 移动目标只列已有文件夹；移动文件夹时排除自己和子文件夹
  const folderOptions = useMemo(() => {
    const blocked = moveItem?.kind === 'folder' ? moveItem.path : null
    const all = projectDirPaths({ workflows, folders }).sort((a, b) => a.localeCompare(b))
    return [
      { value: '', label: '项目根' },
      ...all
        .filter((path) => !blocked || (path !== blocked && !path.startsWith(`${blocked}/`)))
        .map((path) => ({ value: path, label: path })),
    ]
  }, [workflows, folders, moveItem])

  return (
    <div>
      <PageHeader
        title="数据开发"
        description="项目 → 文件夹 → 文件 → 血缘 → 版本 → 发布"
        extra={
          <Space wrap>
            {/* 项目列表是全局的：右上角下拉切换，旁边直接新建项目 */}
            <Select
              showSearch
              optionFilterProp="label"
              style={{ width: 220 }}
              value={projectId}
              placeholder="选择项目"
              onChange={(id) => switchProject(id)}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
            {currentProject?.dev_database ? (
              <Tooltip title={`表名前缀：${currentProject.table_prefix || '-'}`}>
                <Tag color="blue" icon={<DatabaseOutlined />}>{currentProject.dev_database}</Tag>
              </Tooltip>
            ) : null}
            {can('project:manage') && (
              <Button icon={<AppstoreAddOutlined />} onClick={() => setProjectOpen(true)}>
                新建项目
              </Button>
            )}
            {can('project:manage') && projectId && (
              <Button icon={<EditOutlined />} onClick={openRenameProject}>
                重命名项目
              </Button>
            )}
            <Button icon={<ReloadOutlined />} onClick={() => loadWorkflows()}>
              刷新
            </Button>
          </Space>
        }
      />

      {error ? (
        <Card>
          <ErrorState message={error} onRetry={() => loadWorkflows()} />
        </Card>
      ) : (
        <Row gutter={16}>
          <Col xs={24} lg={8} xl={7}>
            <Card
              size="small"
              title="项目结构"
              styles={{
                body: {
                  padding: 8,
                  height: 560,
                  overflow: 'hidden',
                  display: 'flex',
                  flexDirection: 'column',
                },
              }}
            >
              {/* 树单独滚动，下方操作条固定在卡片底部，不用滚到最下面才找得到 */}
              <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
              <Tree
                showIcon
                blockNode
                className="project-tree"
                treeData={projectTree}
                expandedKeys={expandedKeys}
                onExpand={(keys) => setExpandedKeys(keys.map(String))}
                onRightClick={({ event, node }) => {
                  event.preventDefault()
                  setCtxMenu({
                    x: event.clientX,
                    y: event.clientY,
                    key: String(node.key),
                  })
                }}
                selectedKeys={[
                  ...(rootSelected ? ['root'] : []),
                  ...(selectedFolder ? [`dir:${selectedFolder}`] : []),
                  ...(selectedWorkflow ? [`wf:${selectedWorkflow}`] : []),
                ]}
                onSelect={(keys, info) => {
                  // 点已选中的节点会以「取消选择」触发（keys 为空），但节点 key 仍在
                  // info 里：目录标题的展开/收起要用它，否则第二次点击没有反应。
                  const key = keys[0] !== undefined
                    ? String(keys[0])
                    : String(info.node?.key ?? '')
                  if (!key) return
                  if (key === 'root') {
                    // 选中项目根：右侧回到全部文件，下方出现「新建文件夹 / 新建文件」
                    setRootSelected(true)
                    selectFolder(null)
                    setSelectedWorkflow(undefined)
                    return
                  }
                  if (key.startsWith('wf:')) {
                    const id = Number(key.slice(3))
                    setSelectedWorkflow(id)
                    setSelectedFolder(null)
                    setRootSelected(false)
                    rememberWorkflow(projectId, id)
                    nav(`/dev/${id}`)
                    return
                  }
                  if (key.startsWith('dir:')) {
                    // 点文件夹＝展开并在右侧看它的文件（资源管理器习惯）；
                    // 已选中时 antd 会以 keys=[] 触发，这里用 info.node.key 兜底继续展开。
                    selectFolder(key.slice(4))
                    setSelectedWorkflow(undefined)
                    setRootSelected(false)
                    rememberWorkflow(projectId, null)
                    setExpandedKeys((current) => (
                      current.includes(key) ? current : [...current, key]
                    ))
                  }
                }}
              />
              {ctxMenu && (
                <div
                  className="tree-ctx"
                  style={{ left: ctxMenu.x, top: ctxMenu.y }}
                  onClick={(event) => event.stopPropagation()}
                >
                  {ctxMenuItems(ctxMenu.key).map((item) => (
                    <button
                      key={item.label}
                      type="button"
                      className={item.danger ? 'danger' : ''}
                      onClick={() => {
                        setCtxMenu(null)
                        item.onClick()
                      }}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              )}
              {workflows.length === 0 && folders.length === 0 && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {projectId
                    ? '还没有文件：右键项目或文件夹选「新建文件」，也可以先建文件夹。'
                    : '还没有项目，点右上角「新建项目」创建第一个项目。'}
                </Typography.Text>
              )}
              </div>
              {/* 选中节点后，同一套右键操作放在树下方：不用记右键 */}
              <div style={{
                marginTop: 6, paddingTop: 6, borderTop: '1px solid #f0f0f0',
                display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
                flexWrap: 'wrap', minHeight: 30,
              }}>
                  <Space size={4} wrap>
                    {ctxMenuItems(treeSel).map((item) => (
                      <Button
                        key={item.label}
                        size="small"
                        type="link"
                        danger={item.danger}
                        onClick={item.onClick}
                      >
                        {item.label}
                      </Button>
                    ))}
                  </Space>
                </div>
            </Card>
          </Col>
          <Col xs={24} lg={16} xl={17}>
            <Card
              title={term
                ? '搜索结果（全部目录）'
                : selectedFolder ? `文件 · ${selectedFolder}（含子文件夹）` : '全部文件'}
              extra={(
                <Space>
                  <ImeInput
                    allowClear
                    prefix={<SearchOutlined />}
                    placeholder="搜索名称或编码"
                    style={{ width: 200 }}
                    value={keyword}
                    onChange={setKeyword}
                  />
                  <Typography.Text type="secondary">
                    共 {shownWorkflows.length} 个
                  </Typography.Text>
                </Space>
              )}
            >
              <Table
                components={{ body: { row: MemoTableRow } }}
                rowKey="id"
                loading={loading}
                dataSource={shownWorkflows}
                pagination={{
                  pageSize: 10,
                  showSizeChanger: true,
                  hideOnSinglePage: true,
                  showTotal: (total) => `共 ${total} 个`,
                }}
                scroll={{ x: 700 }}
                // 单击整行即可打开文件编辑：不用非要点到名称或「编辑」按钮
                onRow={(row) => ({
                  style: { cursor: 'pointer' },
                  onClick: (event) => {
                    const target = event.target as HTMLElement
                    if (target.closest('a, button, input, textarea, .ant-select')) return
                    nav(`/dev/${row.id}`)
                  },
                })}
                locale={{
                  emptyText: term
                    ? tableEmpty('没有匹配的文件', '换个关键字，或清空搜索')
                    : tableEmpty(
                        selectedFolder ? '这个文件夹（含子文件夹）还没有文件' : '还没有文件',
                        '右键项目或文件夹即可新建文件',
                      ),
                }}
                columns={[
                  {
                    title: '名称',
                    dataIndex: 'name',
                    render: (value, row) => (
                      <Space direction="vertical" size={0}>
                        <Space size={6}>
                          <a onClick={() => nav(`/dev/${row.id}`)}>{value}</a>
                          <Tag
                            color={row.env === 'PROD' ? 'red' : 'blue'}
                            style={{ marginInlineEnd: 0 }}
                          >
                            {row.env}
                          </Tag>
                        </Space>
                        <Typography.Text
                          type="secondary"
                          style={{ fontSize: 12, whiteSpace: 'nowrap' }}
                        >
                          {row.folder ? `${row.folder} · ` : ''}
                          {row.code} · 版本 {row.version_count ?? 0} ·{' '}
                          <a title="查看运行实例列表" onClick={() => openInstances(row)}>
                            实例 {row.instance_count ?? 0}
                          </a>
                        </Typography.Text>
                      </Space>
                    ),
                  },
                  {
                    title: '线上状态',
                    width: 160,
                    render: (_, row) => {
                      const devReady = Number(row.dev_success_count || 0) > 0
                      return (
                        <Space direction="vertical" size={2}>
                          {row.prod_version ? (
                            <Tag color="green" title={row.prod_published_at || ''}>
                              已发布 v{row.prod_version}
                            </Tag>
                          ) : row.prod_pending_version ? (
                            <Tag color="orange">审批中 v{row.prod_pending_version}</Tag>
                          ) : (
                            <Tag>未发布</Tag>
                          )}
                          {/* 与流开发的「冒烟通过才能发布」同一条规则：DEV 跑通才能发布 */}
                          <Tag
                            color={devReady ? 'blue' : 'default'}
                            title={devReady
                              ? `v${row.dev_version} 在 DEV 有成功运行`
                              : '发布前必须先在 DEV 触发一次并等待成功'}
                          >
                            {devReady ? 'DEV 已跑通' : 'DEV 未跑通'}
                          </Tag>
                        </Space>
                      )
                    },
                  },
                  {
                    title: '操作',
                    width: 230,
                    render: (_, row) => (
                      <Space size={4}>
                        <Button size="small" icon={<EditOutlined />} onClick={() => nav(`/dev/${row.id}`)}>
                          编辑
                        </Button>
                        {can(row.prod_pending_version ? 'workflow:approve' : 'workflow:publish') && (
                          <Button
                            size="small"
                            icon={<CloudUploadOutlined />}
                            loading={publishingId === row.id}
                            title={row.prod_pending_version
                              ? '审批并发布到线上环境'
                              : Number(row.dev_success_count || 0) > 0
                                ? '发布到线上环境'
                                : 'DEV 还没跑通：先发布 DEV 并触发一次、等待成功，才能发布'}
                            onClick={() => release(row)}
                          >
                            {row.prod_pending_version
                              ? (can('workflow:approve') ? '审批' : '待审批')
                              : '发布'}
                          </Button>
                        )}
                        {can('workflow:update') && (
                          <Button
                            size="small"
                            icon={<FolderOpenOutlined />}
                            title="移动到其他文件夹"
                            onClick={() => {
                              setMoveItem({ kind: 'workflow', wf: row })
                              setMoveDest(row.folder || '')
                            }}
                          />
                        )}
                        {can('workflow:delete') && (
                          <Button
                            type="text"
                            size="small"
                            danger
                            icon={<DeleteOutlined />}
                            title="删除文件"
                            onClick={() => remove(row)}
                          />
                        )}
                      </Space>
                    ),
                  },
                ]}
              />
            </Card>
          </Col>
        </Row>
      )}

      <Modal
        title={`运行实例 · ${instWorkflow?.name || ''}`}
        open={instOpen}
        width={980}
        footer={(
          <Button
            icon={<ExportOutlined />}
            disabled={!instWorkflow}
            onClick={() => nav(`/ops/instances?workflowId=${instWorkflow?.id}`)}
          >
            在运行实例页打开
          </Button>
        )}
        onCancel={() => setInstOpen(false)}
      >
        <Table<Instance>
          rowKey="id"
          loading={instLoading}
          dataSource={instances}
          pagination={{ pageSize: 10 }}
          size="small"
          scroll={{ x: 820 }}
          locale={{ emptyText: tableEmpty('这个文件还没有运行实例', '在编辑器里点「触发」，或等待调度运行') }}
          onRow={(row) => ({
            onClick: () => openInstanceDetail(row),
            style: { cursor: 'pointer' },
          })}
          columns={[
            { title: '业务日期', dataIndex: 'biz_date', width: 110 },
            { title: '状态', dataIndex: 'state', width: 120, render: (state) => <RunStateTag state={state} /> },
            {
              title: '失败原因', dataIndex: 'error_message', width: 220,
              ellipsis: { showTitle: false },
              render: (text: string | null) => text ? (
                <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                  <span className="failure-reason-text">{text}</span>
                </Tooltip>
              ) : '-',
            },
            {
              title: '开始时间', dataIndex: 'started_at', width: 160,
              render: (v) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
            },
            {
              title: '结束时间', dataIndex: 'ended_at', width: 160,
              render: (v) => v ? dayjs(v).format('MM-DD HH:mm:ss') : '-',
            },
            { title: 'DAG', dataIndex: 'dag_id', ellipsis: true },
          ]}
        />
        <Typography.Text type="secondary" style={{ fontSize: 13 }}>
          点击任意一行查看实例详情和任务日志。
        </Typography.Text>
      </Modal>

      <Drawer
        title={detail
          ? `实例 #${detail.id} · ${detail.workflow_name} / ${detail.biz_date || ''}`
          : '实例详情'}
        open={detailOpen}
        width={820}
        onClose={() => setDetailOpen(false)}
      >
        {detail && (
          <>
            <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
              <Descriptions.Item label="状态"><RunStateTag state={detail.state} /></Descriptions.Item>
              <Descriptions.Item label="业务日期">{detail.biz_date}</Descriptions.Item>
              <Descriptions.Item label="DAG" span={2}>{detail.dag_id}</Descriptions.Item>
              <Descriptions.Item label="运行 ID" span={2}>{detail.run_id}</Descriptions.Item>
              <Descriptions.Item label="开始时间">
                {detail.started_at ? dayjs(detail.started_at).format('YYYY-MM-DD HH:mm:ss') : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="结束时间">
                {detail.ended_at ? dayjs(detail.ended_at).format('YYYY-MM-DD HH:mm:ss') : '-'}
              </Descriptions.Item>
            </Descriptions>
            {detail.error_message ? (
              <Alert
                type="error"
                showIcon
                style={{ marginBottom: 16 }}
                message="失败原因"
                description={<div className="failure-reason">{detail.error_message}</div>}
              />
            ) : null}
            <Table<TaskInstance>
              rowKey="id"
              dataSource={detail.tasks}
              pagination={false}
              size="small"
              locale={{ emptyText: tableEmpty('暂无任务明细', '任务状态由调度服务轮询同步') }}
              expandable={{
                rowExpandable: (row) => Boolean(row.error_message),
                expandedRowRender: (row) => <div className="failure-reason">{row.error_message}</div>,
              }}
              columns={[
                { title: '任务', dataIndex: 'task_code' },
                { title: '状态', dataIndex: 'state', render: (state) => <RunStateTag state={state} /> },
                { title: '尝试', dataIndex: 'try_number', width: 80 },
                {
                  title: '失败原因', dataIndex: 'error_message', width: 220,
                  ellipsis: { showTitle: false },
                  render: (text: string | null) => text ? (
                    <Tooltip title={<span className="failure-reason-tip">{text}</span>}>
                      <span className="failure-reason-text">{text}</span>
                    </Tooltip>
                  ) : '-',
                },
                {
                  title: '操作',
                  width: 100,
                  render: (_, row) => (
                    <Button size="small" icon={<EyeOutlined />} onClick={() => openLog(detail.id, row.task_code)}>
                      日志
                    </Button>
                  ),
                },
              ]}
            />
          </>
        )}
      </Drawer>

      <Modal
        title="任务日志"
        open={logOpen}
        width={900}
        footer={null}
        onCancel={() => setLogOpen(false)}
      >
        <pre className="code-block log-block">{log}</pre>
      </Modal>

      <Modal
        title="新建文件"
        open={creating}
        onOk={() => createWorkflow().catch((e) => message.error(errMsg(e)))}
        onCancel={() => setCreating(false)}
        okText="创建"
      >
        <Form form={form} layout="vertical" initialValues={{ env: 'DEV' }}>
          <Form.Item label="编码" name="code" rules={[{ required: true, pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/, message: '字母开头，只含字母数字下划线' }]}>
            <Input placeholder="例如 order_etl" />
          </Form.Item>
          <Form.Item label="文件名" name="name" rules={[{ required: true }]}>
            <Input placeholder="例如 订单日汇总" />
          </Form.Item>
          <Form.Item label="环境" name="env" extra={ENV_DEV_DEFAULT_HINT}>
            <Select options={ENV_OPTIONS} />
          </Form.Item>
          <Form.Item label="文件夹" name="folder" extra="留空表示项目根；文件夹按路径自动生成，如填 营销/日报 即建两级">
            <Input placeholder="营销/日报" />
          </Form.Item>
          <Form.Item
            label="类型"
            name="type"
            initialValue="SQL"
            extra="建好后文件里就有一个这个类型的脚本，打开即可编辑"
          >
            <Select options={FILE_TYPES.map((type) => ({ value: type }))} />
          </Form.Item>
          {(newFileType || 'SQL') === 'SQL' && (
            <Form.Item
              label="执行引擎"
              name="engine"
              initialValue="DORIS"
              rules={[{ required: true, message: '请选择执行引擎（DORIS / SPARK / FLINK）' }]}
              extra="创建后引擎不可修改，需更换请新建文件。Doris 跟随项目数仓；Spark 走平台 Spark Thrift（结果同步返回）；Flink 是流式作业，试跑只提交异步作业"
            >
              <Select options={FILE_ENGINES} />
            </Form.Item>
          )}
          <Form.Item label="描述" name="description">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建文件夹"
        open={folderOpen}
        onOk={() => createFolder()}
        onCancel={() => setFolderOpen(false)}
        okText="创建"
        destroyOnClose
      >
        <Input
          value={newFolderPath}
          onChange={(e) => setNewFolderPath(e.target.value)}
          placeholder="营销/日报"
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          支持多级文件夹；当前选中的文件夹会作为前缀。建完可以把文件拖进去。
        </Typography.Text>
      </Modal>

      <ProjectCreateModal
        open={projectOpen}
        onCancel={() => setProjectOpen(false)}
        onCreated={onProjectCreated}
      />

      <Modal
        title="重命名项目"
        open={renameOpen}
        onOk={() => renameProject()}
        onCancel={() => setRenameOpen(false)}
        okText="保存"
        destroyOnClose
      >
        <Form form={renameForm} layout="vertical" preserve={false}>
          <Form.Item
            label="项目名称"
            name="name"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="例如 销售域" maxLength={128} />
          </Form.Item>
          <Typography.Text type="secondary">
            这里只改名称。描述、部门、业务线、库名、数仓数据源请到「管理中心 · 项目设置」改
            （全站唯一的项目编辑入口）。
          </Typography.Text>
        </Form>
      </Modal>

      <Modal
        title={moveItem?.kind === 'folder'
          ? `移动文件夹 · ${moveItem.path}`
          : `移动文件 · ${moveItem?.kind === 'workflow' ? moveItem.wf.name : ''}`}
        open={Boolean(moveItem)}
        onOk={() => moveToFolder()}
        onCancel={() => setMoveItem(undefined)}
        okText="移动"
        destroyOnClose
      >
        <Select
          showSearch
          optionFilterProp="label"
          style={{ width: '100%' }}
          value={moveDest}
          onChange={(value) => setMoveDest(value)}
          options={folderOptions}
          placeholder="选择目标文件夹"
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          只列出项目里已有的文件夹；文件夹不能移动到它自己或子文件夹里。
        </Typography.Text>
      </Modal>
    </div>
  )
}
