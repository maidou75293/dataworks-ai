import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Alert, AutoComplete, Breadcrumb, Button, Card, Col, Dropdown, Form, Input, Modal,
  Popconfirm, Row, Select, Space, Table, Tag, Timeline, Tooltip, Tree, Typography, Upload, message,
} from 'antd'
import type { TreeDataNode } from 'antd'
import {
  CloudUploadOutlined, DeleteOutlined, DownloadOutlined, FolderAddOutlined,
  FolderOpenOutlined, FolderOutlined, HistoryOutlined, LinkOutlined, MoreOutlined,
  PlayCircleOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, StopOutlined,
  SwapOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { api, errMsg, del, getToken, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import SearchableSelect from '../components/SearchableSelect'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { useGlobalProject } from '../project'
import type { Project } from '../types'

type ResourceType = 'jar' | 'py' | 'zip' | 'whl' | 'txt'
type Language = 'java' | 'python'

type UdfResource = {
  id: number
  name: string
  type: ResourceType
  language: Language
  folderId?: number | null
  folderPath?: string | null
  version: number
  size: number
  checksum: string
  active: boolean
  filename?: string
  uploader: string
  createdAt: string
  references?: number
  metadata?: {
    functions?: string[]
    requirements?: string[]
    packageName?: string
    packageVersion?: string
    requirementsPath?: string
    pythonFiles?: number
    truncated?: boolean
  }
  downloadUrl?: string
}

type UdfFolder = {
  id: number
  parentId?: number | null
  name: string
  path: string
  resourceCount: number
  totalResourceCount: number
}

type UdfTree = {
  projectId: number
  rootResources: number
  totalResources: number
  folders: UdfFolder[]
}

type Paged<T> = { items: T[]; total: number; page: number; size: number }

type UdfFunction = {
  id: number
  resourceName: string
  engine: 'DORIS' | 'FLINK'
  language?: Language
  name: string
  fullName: string
  className: string
  returnType: string
  argTypes: string
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
  activeVersion?: number
  createdBy?: string
  createdAt?: string
  publishedBy?: string
  publishedAt?: string
}

type UdfVersion = {
  id: number
  version: number
  type: ResourceType
  language: Language
  folderId?: number | null
  size: number
  checksum: string
  active: boolean
  filename?: string
  uploader: string
  createdAt: string
}

type UdfResourceEvent = {
  id: number
  resourceId?: number | null
  name: string
  version?: number | null
  action: 'UPLOAD' | 'ACTIVATE' | 'DELETE' | 'MOVE'
  actor?: string
  detail?: string
  createdAt: string
}

type UdfReference = {
  id: number
  name: string
  fullName: string
  engine: string
  language: string
  status: string
  createdBy?: string
  createdAt?: string
  publishedBy?: string
  publishedAt?: string
}

const ENGINES = [
  { value: 'DORIS', label: 'Doris（查询页 / 工作流 SQL）' },
  { value: 'FLINK', label: 'Flink（流开发作业）' },
]

const TYPE_LABEL: Record<ResourceType, string> = {
  jar: 'JAR',
  py: 'Python',
  zip: 'ZIP 包',
  whl: 'Wheel',
  txt: '依赖清单',
}

const TYPE_COLOR: Record<ResourceType, string> = {
  jar: 'geekblue',
  py: 'green',
  zip: 'cyan',
  whl: 'purple',
  txt: 'orange',
}

const ALLOWED_EXT = ['jar', 'py', 'zip', 'whl', 'txt']
const MAX_BYTES = 50 * 1024 * 1024

function sizeLabel(bytes?: number) {
  if (!bytes) return '-'
  return bytes >= 1024 * 1024
    ? `${(bytes / 1024 / 1024).toFixed(1)} MB`
    : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

function statusTag(status: UdfFunction['status']) {
  if (status === 'PUBLISHED') return <Tag color="green">已发布</Tag>
  if (status === 'OFFLINE') return <Tag color="orange">已停用</Tag>
  return <Tag>草稿</Tag>
}

function languageTag(language?: string) {
  return language === 'python'
    ? <Tag color="green">Python</Tag>
    : <Tag color="blue">Java</Tag>
}

function typeTag(type?: string) {
  const value = (type || 'jar') as ResourceType
  return <Tag color={TYPE_COLOR[value] || 'default'}>{TYPE_LABEL[value] || value}</Tag>
}

function fileExt(name: string) {
  const value = name.toLowerCase()
  const dot = value.lastIndexOf('.')
  return dot >= 0 ? value.slice(dot + 1) : ''
}

/**
 * 函数管理：资源（jar / py / zip / whl / txt）→ 注册函数 → 发布。
 *
 * <p>Doris 只支持 Java UDF；Python 资源只能注册为 FLINK 函数，发布时由
 * UdfService.flinkPreamble 把 py/zip + requirements 注入作业依赖。</p>
 */
export default function Functions() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useGlobalProject()
  const projectRef = useRef(projectId)
  projectRef.current = projectId

  const [tree, setTree] = useState<UdfTree>({ projectId: 0, rootResources: 0, totalResources: 0, folders: [] })
  const [selectedKey, setSelectedKey] = useState('all')
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const [resources, setResources] = useState<UdfResource[]>([])
  const [allResources, setAllResources] = useState<UdfResource[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [functions, setFunctions] = useState<UdfFunction[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const loadSeq = useRef(0)

  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [fileList, setFileList] = useState<any[]>([])
  const [uploadForm] = Form.useForm()

  const [folderOpen, setFolderOpen] = useState(false)
  const [folderMode, setFolderMode] = useState<'create' | 'rename'>('create')
  const [folderTarget, setFolderTarget] = useState<UdfFolder | undefined>()
  const [folderParent, setFolderParent] = useState<number | null>(null)
  const [folderForm] = Form.useForm()

  const [moveOpen, setMoveOpen] = useState(false)
  const [moveTarget, setMoveTarget] = useState<UdfResource | undefined>()
  const [moveFolderId, setMoveFolderId] = useState<number | null>(null)

  const [folderMoveOpen, setFolderMoveOpen] = useState(false)
  const [folderMoveTarget, setFolderMoveTarget] = useState<UdfFolder | undefined>()
  const [folderMoveParentId, setFolderMoveParentId] = useState<number | null>(null)

  const [refOpen, setRefOpen] = useState(false)
  const [refLoading, setRefLoading] = useState(false)
  const [refTarget, setRefTarget] = useState<UdfResource | undefined>()
  const [references, setReferences] = useState<UdfReference[]>([])

  const [versionOpen, setVersionOpen] = useState(false)
  const [versionLoading, setVersionLoading] = useState(false)
  const [versionTarget, setVersionTarget] = useState<UdfResource | undefined>()
  const [versions, setVersions] = useState<UdfVersion[]>([])
  const [versionEvents, setVersionEvents] = useState<UdfResourceEvent[]>([])

  const [fnOpen, setFnOpen] = useState(false)
  const [editing, setEditing] = useState<UdfFunction>()
  const [fnForm] = Form.useForm()

  const fnResourceName = Form.useWatch('resourceName', fnForm)
  const fnEngine = Form.useWatch('engine', fnForm)

  const folders = tree.folders || []
  const folderById = useMemo(() => new Map(folders.map((f) => [f.id, f])), [folders])
  const resourceByName = useMemo(() => {
    const map = new Map<string, UdfResource>()
    allResources.forEach((r) => {
      const old = map.get(r.name)
      if (!old || (r.active && !old.active) || (r.active === old.active && r.version > old.version)) {
        map.set(r.name, r)
      }
    })
    return map
  }, [allResources])
  const selectedFolder = selectedKey.startsWith('f:')
    ? folderById.get(Number(selectedKey.slice(2))) : undefined
  const uploadFolderId = selectedFolder ? selectedFolder.id : null
  const activeNames = new Set(allResources.filter((r) => r.active).map((r) => r.name))

  const loadProjects = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    if (!list.some((item) => item.id === projectId) && list.length > 0) setProjectId(list[0].id)
  }

  const loadTree = async (pid: number) => {
    const data = await api<UdfTree>(`/api/udf/projects/${pid}/tree`)
    if (pid !== projectRef.current) return
    setTree(data)
    const keys = (data.folders || []).map((f) => `f:${f.id}`)
    setExpandedKeys((old) => {
      const keep = old.filter((key) => keys.includes(String(key)))
      return keep.length ? keep : keys
    })
  }

  const loadAllResources = async (pid: number) => {
    const data = await api<UdfResource[]>(`/api/udf/projects/${pid}/resources`)
    if (pid !== projectRef.current) return
    setAllResources(Array.isArray(data) ? data : [])
  }

  const loadFunctions = async (pid: number) => {
    const data = await api<UdfFunction[]>(`/api/udf/projects/${pid}/functions`)
    if (pid !== projectRef.current) return
    setFunctions(data)
  }

  const loadResourcePage = async (pid = projectRef.current) => {
    if (!pid) {
      setResources([])
      setTotal(0)
      return
    }
    const seq = ++loadSeq.current
    setLoading(true)
    setError('')
    try {
      const qs = new URLSearchParams()
      qs.set('page', String(page))
      qs.set('size', String(pageSize))
      if (selectedKey === 'root') qs.set('folderId', 'root')
      else if (selectedKey.startsWith('f:')) qs.set('folderId', selectedKey.slice(2))
      if (keyword.trim()) qs.set('keyword', keyword.trim())
      const data = await api<Paged<UdfResource>>(
        `/api/udf/projects/${pid}/resources?${qs.toString()}`)
      if (seq !== loadSeq.current || pid !== projectRef.current) return
      setResources(Array.isArray(data?.items) ? data.items : [])
      setTotal(Number(data?.total || 0))
    } catch (e: any) {
      if (seq !== loadSeq.current) return
      setError(e?.message || '加载资源失败')
      setResources([])
      setTotal(0)
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }

  const reloadAll = async () => {
    const pid = projectRef.current
    if (!pid) return
    await Promise.all([loadTree(pid), loadAllResources(pid), loadFunctions(pid), loadResourcePage(pid)])
  }

  useEffect(() => {
    loadProjects().catch((e) => message.error(errMsg(e)))
  }, [])

  useEffect(() => {
    setSelectedKey('all')
    setPage(1)
    setKeywordInput('')
    setKeyword('')
    setResources([])
    setFunctions([])
    setAllResources([])
    const pid = projectId
    if (!pid) return
    Promise.all([loadTree(pid), loadAllResources(pid), loadFunctions(pid)])
      .catch((e) => setError(e?.message || '加载函数资源失败'))
  }, [projectId])

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setKeyword(keywordInput.trim())
      setPage(1)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [keywordInput])

  useEffect(() => {
    loadResourcePage().catch(() => undefined)
  }, [selectedKey, page, pageSize, keyword, projectId])

  const buildTree = (): TreeDataNode[] => {
    const children = (parentId: number | null): TreeDataNode[] => folders
      .filter((f) => (f.parentId ?? null) === parentId)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((f) => ({
        key: `f:${f.id}`,
        title: f.name,
        icon: <FolderOutlined />,
        folder: f,
        children: children(f.id),
      })) as TreeDataNode[]
    return [
      {
        key: 'all',
        title: `全部资源（${tree.totalResources || 0}）`,
        icon: <FolderOpenOutlined />,
      },
      {
        key: 'root',
        title: `根目录（${tree.rootResources || 0}）`,
        icon: <FolderOutlined />,
      },
      ...children(null),
    ]
  }

  const breadcrumbItems = useMemo(() => {
    const items: any[] = [{
      title: <a onClick={() => setSelectedKey('all')}>全部资源</a>,
    }]
    if (selectedKey === 'root') {
      items.push({ title: '根目录' })
      return items
    }
    if (!selectedKey.startsWith('f:')) return items
    items.push({ title: <a onClick={() => setSelectedKey('root')}>根目录</a> })
    const folder = selectedFolder
    if (!folder) return items
    const segments = folder.path.split('/').filter(Boolean)
    let path = ''
    segments.forEach((segment, index) => {
      path += `/${segment}`
      const target = folders.find((f) => f.path === path)
      const last = index === segments.length - 1
      items.push({
        title: last || !target
          ? segment
          : <a onClick={() => setSelectedKey(`f:${target.id}`)}>{segment}</a>,
      })
    })
    return items
  }, [selectedKey, selectedFolder, folders])

  const openCreateFolder = (parentId: number | null) => {
    setFolderMode('create')
    setFolderTarget(undefined)
    setFolderParent(parentId)
    folderForm.resetFields()
    setFolderOpen(true)
  }

  const openRenameFolder = (folder: UdfFolder) => {
    setFolderMode('rename')
    setFolderTarget(folder)
    folderForm.setFieldsValue({ name: folder.name })
    setFolderOpen(true)
  }

  /** 文件夹移动弹窗：默认选中当前父目录；下拉里排除自己与自己的子孙（后端也会再判一次环）。 */
  const openMoveFolder = (folder: UdfFolder) => {
    setFolderMoveTarget(folder)
    setFolderMoveParentId(folder.parentId ?? null)
    setFolderMoveOpen(true)
  }

  const folderMoveOptions = useMemo(() => {
    const target = folderMoveTarget
    return [
      { value: 0, label: '根目录' },
      ...folders
        .filter((f) => !target
          || (f.id !== target.id && !f.path.startsWith(`${target.path}/`)))
        .map((f) => ({ value: f.id, label: f.path })),
    ]
  }, [folders, folderMoveTarget])

  const submitFolderMove = async () => {
    if (!folderMoveTarget) return
    try {
      await post(`/api/udf/folders/${folderMoveTarget.id}/move`, {
        parentId: folderMoveParentId,
      })
      message.success('文件夹已移动')
      setFolderMoveOpen(false)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '移动文件夹失败')
    }
  }

  const submitFolder = async () => {
    const values = await folderForm.validateFields()
    const pid = projectRef.current
    if (!pid) return
    if (folderMode === 'create') {
      const out = await post<UdfFolder>(`/api/udf/projects/${pid}/folders`, {
        parentId: folderParent, name: values.name,
      })
      message.success('文件夹已创建')
      setSelectedKey(`f:${out.id}`)
    } else if (folderTarget) {
      await put(`/api/udf/folders/${folderTarget.id}`, { name: values.name })
      message.success('文件夹已重命名')
    }
    setFolderOpen(false)
    await reloadAll()
  }

  const removeFolder = async (folder: UdfFolder, force = false) => {
    try {
      const out = await del<any>(`/api/udf/folders/${folder.id}${force ? '?force=true' : ''}`)
      message.success(force
        ? `已删除 ${out.deletedFolders} 个文件夹，${out.movedResources} 个资源移到根目录`
        : '文件夹已删除')
      const inSubtree = selectedFolder
        && (selectedFolder.id === folder.id || selectedFolder.path.startsWith(`${folder.path}/`))
      if (inSubtree) setSelectedKey('root')
      await reloadAll()
    } catch (e: any) {
      if (e?.status === 409 && !force) {
        Modal.confirm({
          title: '文件夹非空',
          content: `${e.message}。确认后会删除子文件夹，资源会移到根目录。`,
          okText: '级联删除',
          okButtonProps: { danger: true },
          onOk: () => removeFolder(folder, true),
        })
        return
      }
      message.error(e?.message || '删除文件夹失败')
    }
  }

  const onDrop = async (info: any) => {
    const dragKey = String(info.dragNode?.key || '')
    const dropKey = String(info.node?.key || '')
    if (!dragKey.startsWith('f:') || dragKey === dropKey) return
    const id = Number(dragKey.slice(2))
    const parentId = dropKey.startsWith('f:') ? Number(dropKey.slice(2)) : null
    try {
      await post(`/api/udf/folders/${id}/move`, { parentId })
      message.success('文件夹已移动')
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '移动文件夹失败')
    }
  }

  const treeTitle = (node: any) => {
    const folder = node.folder as UdfFolder | undefined
    if (!folder) return node.title
    const menu = {
      items: [
        { key: 'create', label: '新建子文件夹' },
        { key: 'rename', label: '重命名' },
        { key: 'move', label: '移动到…' },
        { key: 'delete', label: '删除', danger: true },
      ],
      onClick: ({ key, domEvent }: any) => {
        domEvent?.stopPropagation?.()
        if (key === 'create') openCreateFolder(folder.id)
        if (key === 'rename') openRenameFolder(folder)
        if (key === 'move') openMoveFolder(folder)
        if (key === 'delete') removeFolder(folder).catch(() => undefined)
      },
    }
    return (
      <Space size={4} style={{ width: '100%', justifyContent: 'space-between' }}>
        <Space size={4}>
          <FolderOutlined />
          <span>{folder.name}</span>
          {folder.totalResourceCount > 0
            ? <Tag style={{ marginInlineStart: 2 }}>{folder.totalResourceCount}</Tag>
            : null}
        </Space>
        {can('udf:write') ? (
          // UX-DW10REST-9（REQ-DW-10 R2）：⋮ 与右键并存——trigger 补 contextMenu，目录行右击出同一菜单
          <Dropdown menu={menu} trigger={['click', 'contextMenu']}>
            <MoreOutlined onClick={(e) => e.stopPropagation()} />
          </Dropdown>
        ) : null}
      </Space>
    )
  }

  const download = async (row: UdfResource) => {
    try {
      const res = await fetch(`/api/udf/resources/${row.id}/download`, {
        headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : undefined,
      })
      if (!res.ok) {
        let detail = `下载失败（${res.status}）`
        try {
          const body = await res.json()
          detail = body?.message || detail
        } catch {
          // ignore
        }
        throw new Error(detail)
      }
      const blob = await res.blob()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = row.filename || `${row.name}.${row.type}`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e: any) {
      message.error(e?.message || '下载失败')
    }
  }

  const openReferences = async (row: UdfResource) => {
    setRefTarget(row)
    setRefOpen(true)
    setRefLoading(true)
    setReferences([])
    try {
      const data = await api<{ functions: UdfReference[] }>(`/api/udf/resources/${row.id}/references`)
      setReferences(data.functions || [])
    } catch (e: any) {
      message.error(e?.message || '读取引用失败')
    } finally {
      setRefLoading(false)
    }
  }

  const openVersions = async (row: UdfResource) => {
    setVersionTarget(row)
    setVersionOpen(true)
    setVersionLoading(true)
    setVersions([])
    setVersionEvents([])
    try {
      const [list, events] = await Promise.all([
        api<UdfVersion[]>(`/api/udf/resources/${row.id}/versions`),
        api<UdfResourceEvent[]>(`/api/udf/resources/${row.id}/events`),
      ])
      setVersions(list)
      setVersionEvents(events || [])
    } catch (e: any) {
      message.error(e?.message || '读取版本失败')
    } finally {
      setVersionLoading(false)
    }
  }

  const openMove = (row: UdfResource) => {
    setMoveTarget(row)
    setMoveFolderId(row.folderId ?? null)
    setMoveOpen(true)
  }

  const submitMove = async () => {
    if (!moveTarget) return
    try {
      await post(`/api/udf/resources/${moveTarget.id}/move`, { folderId: moveFolderId })
      message.success('资源已移动')
      setMoveOpen(false)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '移动资源失败')
    }
  }

  const upload = async () => {
    const values = await uploadForm.validateFields()
    const pid = projectRef.current
    if (!pid || fileList.length === 0) {
      message.error('请选择要上传的文件')
      return
    }
    setUploading(true)
    try {
      for (const item of fileList) {
        const file = item.originFileObj || item
        const body = new FormData()
        body.append('file', file)
        if (fileList.length === 1 && values.name) body.append('name', values.name)
        if (uploadFolderId != null) body.append('folderId', String(uploadFolderId))
        const res = await fetch(`/api/udf/projects/${pid}/resources`, {
          method: 'POST',
          headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : undefined,
          body,
        })
        const data = await res.json().catch(() => null)
        if (!res.ok) throw new Error(data?.message || `上传失败（${res.status}）`)
      }
      message.success(`已上传 ${fileList.length} 个资源`)
      setUploadOpen(false)
      setFileList([])
      uploadForm.resetFields()
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const busy = async (fn: () => Promise<unknown>, ok: string) => {
    try {
      await fn()
      message.success(ok)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '操作失败')
    }
  }

  const openCreate = () => {
    const first = allResources.find((r) => r.active)
      || [...allResources].sort((a, b) => b.version - a.version)[0]
    if (!first) {
      message.warning('请先上传资源')
      return
    }
    setEditing(undefined)
    fnForm.resetFields()
    fnForm.setFieldsValue({
      engine: first.language === 'python' ? 'FLINK' : 'DORIS',
      language: first.language,
      returnType: 'INT',
      resourceName: first.name,
    })
    setFnOpen(true)
  }

  const openEdit = (row: UdfFunction) => {
    setEditing(row)
    fnForm.resetFields()
    fnForm.setFieldsValue({
      resourceName: row.resourceName,
      engine: row.engine,
      language: row.language || 'java',
      name: row.name,
      className: row.className,
      returnType: row.returnType,
      argTypes: row.argTypes,
    })
    setFnOpen(true)
  }

  const submitFn = async () => {
    const values = await fnForm.validateFields()
    const pid = projectRef.current
    if (!pid) return
    const args = String(values.argTypes || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
    const resource = resourceByName.get(values.resourceName)
    const language: Language = resource?.language || values.language || 'java'
    const payload = {
      resourceName: values.resourceName,
      engine: values.engine,
      language,
      name: values.name,
      className: values.className,
      returnType: values.returnType,
      argTypes: args,
    }
    if (editing) {
      await put(`/api/udf/functions/${editing.id}`, payload)
      message.success('函数已更新')
    } else {
      await post(`/api/udf/projects/${pid}/functions`, payload)
      message.success('函数已保存为草稿')
    }
    setFnOpen(false)
    setEditing(undefined)
    await reloadAll()
  }

  const selectedFnResource = fnResourceName ? resourceByName.get(fnResourceName) : undefined
  const fnLanguage: Language = selectedFnResource?.language
    || (fnForm.getFieldValue('language') as Language) || 'java'
  const pythonCandidates = selectedFnResource?.metadata?.functions || []
  const folderOptions = [
    { value: 0, label: '根目录' },
    ...folders.map((f) => ({ value: f.id, label: f.path })),
  ]

  return (
    <div className="page">
      <PageHeader
        title="函数管理"
        description="上传 jar / Python 资源，注册 Doris / Flink 函数；Doris 只支持 Java，Python 只能用于 Flink。支持多级文件夹、批量上传、下载和版本回看。"
        extra={(
          <Space wrap>
            <SearchableSelect
              value={projectId}
              onChange={(v) => setProjectId(v as number)}
              placeholder="选择项目"
              style={{ width: 200 }}
              options={projects.map((p) => ({ value: p.id, label: p.name }))}
            />
            <Button icon={<ReloadOutlined />} onClick={() => reloadAll().catch(() => undefined)}>刷新</Button>
            {can('udf:write') && (
              <Button icon={<FolderAddOutlined />} onClick={() => openCreateFolder(uploadFolderId)}>
                新建文件夹
              </Button>
            )}
            {can('udf:write') && (
              <Button icon={<CloudUploadOutlined />} onClick={() => setUploadOpen(true)}>上传资源</Button>
            )}
            {can('udf:write') && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>注册函数</Button>
            )}
          </Space>
        )}
      />

      {error ? <ErrorState message={error} onRetry={() => reloadAll()} /> : null}

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="使用说明"
        description={(
          <span>
            1）按目录上传资源（jar ≤50MB；py/zip/whl/txt 也 ≤50MB，Python 仅用于 Flink）；
            2）注册函数并发布；3）查询页写 <Typography.Text code>p{projectId || 'Id'}_函数名(...)</Typography.Text>。
            Doris 不支持 Python UDF，选 Python 资源时引擎会自动切到 Flink。
          </span>
        )}
      />

      <Row gutter={16}>
        <Col xs={24} md={7} lg={6}>
          <Card
            title="目录"
            size="small"
            style={{ marginBottom: 16 }}
            extra={(
              <Space size={4}>
                {can('udf:write') && (
                  <Tooltip title="在当前目录新建文件夹">
                    <Button size="small" type="text" icon={<FolderAddOutlined />}
                            onClick={() => openCreateFolder(uploadFolderId)} />
                  </Tooltip>
                )}
              </Space>
            )}
          >
            <Tree
              blockNode
              showIcon
              draggable
              treeData={buildTree()}
              selectedKeys={[selectedKey]}
              expandedKeys={expandedKeys}
              onExpand={(keys) => setExpandedKeys(keys as string[])}
              onSelect={(keys) => setSelectedKey(String(keys[0] || 'all'))}
              onDrop={onDrop}
              titleRender={treeTitle}
              style={{ fontSize: 13 }}
            />
          </Card>
        </Col>

        <Col xs={24} md={17} lg={18}>
          <Card
            title="资源"
            size="small"
            style={{ marginBottom: 16 }}
            extra={(
              <Space wrap>
                <Input
                  allowClear
                  prefix={<SearchOutlined />}
                  placeholder="搜索资源名 / 文件名"
                  value={keywordInput}
                  onChange={(e) => setKeywordInput(e.target.value)}
                  style={{ width: 240 }}
                />
              </Space>
            )}
          >
            <Breadcrumb items={breadcrumbItems} style={{ marginBottom: 12 }} />
            <Table<UdfResource>
              rowKey="id"
              dataSource={resources}
              size="small"
              loading={loading}
              scroll={{ x: 1100 }}
              locale={{ emptyText: tableEmpty('这个目录还没有资源', '点击右上角「上传资源」开始') }}
              pagination={{
                current: page,
                pageSize,
                total,
                showSizeChanger: true,
                pageSizeOptions: [10, 20, 50, 100],
                showTotal: (n) => `共 ${n} 个版本`,
                onChange: (p, s) => { setPage(p); setPageSize(s) },
              }}
              columns={[
                {
                  title: '资源名', dataIndex: 'name', width: 210,
                  render: (v: string, row) => (
                    <Space direction="vertical" size={0}>
                      <Space size={4}>
                        <Typography.Text strong>{v}</Typography.Text>
                        {row.active ? <Tag color="green">启用中</Tag> : null}
                      </Space>
                      <Space size={4}>
                        {typeTag(row.type)}
                        {languageTag(row.language)}
                      </Space>
                    </Space>
                  ),
                },
                { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
                { title: '大小', dataIndex: 'size', width: 90, render: sizeLabel },
                {
                  title: '引用', dataIndex: 'references', width: 70,
                  render: (v: number, row) => (
                    <Button type="link" size="small" style={{ padding: 0 }}
                            onClick={() => openReferences(row)}>
                      {v || 0}
                    </Button>
                  ),
                },
                { title: '文件', dataIndex: 'filename', width: 180, ellipsis: true },
                { title: '上传人', dataIndex: 'uploader', width: 100 },
                {
                  title: '操作', width: 320, fixed: 'right',
                  render: (_, row) => (
                    <Space size={2}>
                      <Tooltip title="下载">
                        <Button size="small" type="text" icon={<DownloadOutlined />}
                                onClick={() => download(row)} />
                      </Tooltip>
                      <Tooltip title="版本记录">
                        <Button size="small" type="text" icon={<HistoryOutlined />}
                                onClick={() => openVersions(row)} />
                      </Tooltip>
                      {can('udf:write') && (
                        <Tooltip title="移动到目录">
                          <Button size="small" type="text" icon={<SwapOutlined />}
                                  onClick={() => openMove(row)} />
                        </Tooltip>
                      )}
                      {can('udf:publish') && !row.active && (
                        <Button size="small" icon={<ThunderboltOutlined />}
                                onClick={() => busy(
                                  () => post(`/api/udf/resources/${row.id}/activate`),
                                  '已切换启用版本')}>
                          启用
                        </Button>
                      )}
                      {can('udf:publish') && row.active && (
                        <Popconfirm
                          title={`停用资源版本「${row.name}」v${row.version}？`}
                          description="停用后同名资源没有启用版本，注册/发布新函数会失败，直到重新启用某个版本"
                          okText="停用"
                          okButtonProps={{ danger: true }}
                          onConfirm={() => busy(
                            () => post(`/api/udf/resources/${row.id}/deactivate`),
                            '已停用该版本')}
                        >
                          <Button size="small" icon={<StopOutlined />}>停用</Button>
                        </Popconfirm>
                      )}
                      {can('udf:write') && !row.active && (
                        <Popconfirm
                          title={`删除资源版本「${row.name}」v${row.version}？`}
                          description={row.references
                            ? `有 ${row.references} 个函数引用该资源名；启用版本不可删`
                            : '删除后从列表和版本记录中隐藏（行与文件保留用于审计）'}
                          okText="删除"
                          okButtonProps={{ danger: true }}
                          onConfirm={() => busy(
                            () => del(`/api/udf/resources/${row.id}`), '资源已删除')}
                        >
                          <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                      )}
                    </Space>
                  ),
                },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Card title="函数" size="small">
        <Table<UdfFunction>
          rowKey="id"
          dataSource={functions}
          size="small"
          pagination={{ pageSize: 20, hideOnSinglePage: true }}
          scroll={{ x: 1100 }}
          locale={{ emptyText: tableEmpty('还没有注册函数', '先上传资源，再点「注册函数」') }}
          columns={[
            {
              title: '调用名', dataIndex: 'fullName', width: 200,
              render: (v: string, row) => (
                <Space direction="vertical" size={0}>
                  <Typography.Text code>{v}</Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    短名 {row.name}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: '引擎', dataIndex: 'engine', width: 90,
              render: (v: string) => <Tag color={v === 'DORIS' ? 'geekblue' : 'purple'}>{v}</Tag>,
            },
            {
              title: '语言', dataIndex: 'language', width: 90,
              render: (v: string) => languageTag(v),
            },
            { title: '资源', dataIndex: 'resourceName', width: 130 },
            {
              title: '签名', width: 200,
              render: (_, row) => (
                <Typography.Text style={{ fontSize: 12 }}>
                  ({row.argTypes || ''}) → {row.returnType}
                </Typography.Text>
              ),
            },
            {
              title: '类名 / Python 入口', dataIndex: 'className', ellipsis: true,
              render: (v: string) => <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>,
            },
            {
              title: '状态', dataIndex: 'status', width: 90,
              render: (v: UdfFunction['status']) => statusTag(v),
            },
            {
              title: '发布留痕', width: 150,
              render: (_, row) => row.publishedAt
                ? (
                  <Tooltip title={row.publishedAt}>
                    <Typography.Text style={{ fontSize: 12 }}>
                      {row.publishedBy || '-'}
                    </Typography.Text>
                  </Tooltip>
                ) : <Typography.Text type="secondary" style={{ fontSize: 12 }}>未发布</Typography.Text>,
            },
            {
              title: '操作', width: 220,
              render: (_, row) => (
                <Space>
                  {row.status !== 'PUBLISHED' && can('udf:publish') && (
                    <Button
                      size="small"
                      type="primary"
                      icon={<PlayCircleOutlined />}
                      disabled={!activeNames.has(row.resourceName)}
                      onClick={() => busy(
                        () => post(`/api/udf/functions/${row.id}/publish`), '函数已发布')}
                    >
                      发布
                    </Button>
                  )}
                  {row.status === 'PUBLISHED' && can('udf:publish') && (
                    <Popconfirm
                      title={`停用函数「${row.fullName}」？`}
                      description="Doris 侧会同步 DROP FUNCTION；Flink 下次提交不再注入"
                      okText="停用"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => busy(
                        () => post(`/api/udf/functions/${row.id}/offline`), '函数已停用')}
                    >
                      <Button size="small" icon={<StopOutlined />}>停用</Button>
                    </Popconfirm>
                  )}
                  {row.status === 'DRAFT' && can('udf:write') && (
                    <Button size="small" onClick={() => openEdit(row)}>编辑</Button>
                  )}
                  {can('udf:write') && (
                    <Popconfirm
                      title={`删除函数「${row.fullName}」？`}
                      description="已发布的函数会先在 Doris 侧删除"
                      okText="删除"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => busy(
                        () => del(`/api/udf/functions/${row.id}`), '函数已删除')}
                    >
                      <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>

      <Modal
        title="上传资源"
        open={uploadOpen}
        confirmLoading={uploading}
        okText={fileList.length > 1 ? `上传 ${fileList.length} 个文件` : '上传'}
        onOk={() => upload().catch((e) => message.error(errMsg(e)))}
        onCancel={() => {
          setUploadOpen(false)
          setFileList([])
          uploadForm.resetFields()
        }}
      >
        <Form form={uploadForm} layout="vertical">
          <Form.Item label="目标目录">
            <Typography.Text>{selectedFolder?.path || '根目录'}</Typography.Text>
          </Form.Item>
          <Form.Item label="资源文件（可多选）" required>
            <Upload
              accept=".jar,.py,.zip,.whl,.txt"
              multiple
              maxCount={20}
              fileList={fileList}
              beforeUpload={(file) => {
                const ext = fileExt(file.name)
                if (!ALLOWED_EXT.includes(ext)) {
                  message.error('只支持 .jar / .py / .zip / .whl / .txt')
                  return Upload.LIST_IGNORE
                }
                if (file.size > MAX_BYTES) {
                  message.error('单个文件不能超过 50MB')
                  return Upload.LIST_IGNORE
                }
                setFileList((old) => [...old, file as any])
                return false
              }}
              onRemove={(file) => {
                setFileList((old) => old.filter((item) => item.uid !== file.uid))
              }}
            >
              <Button icon={<CloudUploadOutlined />}>选择文件</Button>
            </Upload>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              jar 用于 Java；py/zip/whl 用于 Python（Flink）；txt 表示 requirements 依赖清单。
            </Typography.Text>
          </Form.Item>
          <Form.Item
            label="资源名"
            name="name"
            tooltip="单个文件时可留空取文件名；多文件时按文件名各自命名"
            rules={[{ pattern: /^[a-z][a-z0-9_]{1,47}$/, message: '小写字母开头，只含小写字母、数字、下划线' }]}
          >
            <Input disabled={fileList.length > 1} placeholder="例如 myudf（留空用文件名）" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={folderMode === 'create' ? '新建文件夹' : `重命名 ${folderTarget?.name || ''}`}
        open={folderOpen}
        onOk={() => submitFolder().catch((e) => message.error(errMsg(e)))}
        onCancel={() => setFolderOpen(false)}
      >
        <Form form={folderForm} layout="vertical">
          {folderMode === 'create' && (
            <Form.Item label="上级目录">
              <Typography.Text>
                {folderParent == null ? '根目录' : folderById.get(folderParent)?.path || '根目录'}
              </Typography.Text>
            </Form.Item>
          )}
          <Form.Item
            label="文件夹名"
            name="name"
            rules={[
              { required: true, message: '请输入文件夹名' },
              { max: 64, message: '最多 64 个字符' },
            ]}
          >
            <Input placeholder="例如 实时 / 零售 / 清洗" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`移动文件夹：${folderMoveTarget?.name || ''}`}
        open={folderMoveOpen}
        okText="移动"
        onOk={() => submitFolderMove().catch((e) => message.error(errMsg(e)))}
        onCancel={() => setFolderMoveOpen(false)}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            当前路径：{folderMoveTarget?.path || '-'}；不能移动到自身或自己的子目录。
          </Typography.Text>
          <Select
            showSearch
            optionFilterProp="label"
            style={{ width: '100%' }}
            value={folderMoveParentId ?? 0}
            onChange={(v) => setFolderMoveParentId(Number(v) === 0 ? null : Number(v))}
            options={folderMoveOptions}
          />
        </Space>
      </Modal>

      <Modal
        title={`移动资源：${moveTarget?.name || ''}`}
        open={moveOpen}
        onOk={() => submitMove()}
        onCancel={() => setMoveOpen(false)}
      >
        <Select
          style={{ width: '100%' }}
          value={moveFolderId ?? 0}
          onChange={(v) => setMoveFolderId(Number(v) === 0 ? null : Number(v))}
          options={folderOptions}
        />
      </Modal>

      <Modal
        title={`引用：${refTarget?.name || ''}`}
        open={refOpen}
        footer={null}
        width={760}
        onCancel={() => setRefOpen(false)}
      >
        <Table<UdfReference>
          rowKey="id"
          size="small"
          loading={refLoading}
          dataSource={references}
          pagination={false}
          locale={{ emptyText: tableEmpty('没有函数引用该资源', '注册函数后会出现在这里') }}
          columns={[
            { title: '函数', dataIndex: 'fullName', width: 200 },
            { title: '引擎', dataIndex: 'engine', width: 80 },
            { title: '语言', dataIndex: 'language', width: 80 },
            { title: '状态', dataIndex: 'status', width: 90 },
            { title: '创建人', dataIndex: 'createdBy', width: 100 },
          ]}
        />
      </Modal>

      <Modal
        title={`版本记录：${versionTarget?.name || ''}`}
        open={versionOpen}
        footer={null}
        width={820}
        onCancel={() => setVersionOpen(false)}
      >
        <Table<UdfVersion>
          rowKey="id"
          size="small"
          loading={versionLoading}
          dataSource={versions}
          pagination={false}
          locale={{ emptyText: tableEmpty('没有版本记录') }}
          columns={[
            { title: '版本', dataIndex: 'version', width: 70, render: (v: number) => `v${v}` },
            {
              title: '类型', dataIndex: 'type', width: 100,
              render: (v: ResourceType) => typeTag(v),
            },
            {
              title: '状态', dataIndex: 'active', width: 90,
              render: (v: boolean) => v ? <Tag color="green">启用中</Tag> : <Tag>历史</Tag>,
            },
            { title: '大小', dataIndex: 'size', width: 90, render: sizeLabel },
            { title: '文件', dataIndex: 'filename', ellipsis: true },
            { title: '上传人', dataIndex: 'uploader', width: 100 },
            { title: '时间', dataIndex: 'createdAt', width: 180 },
          ]}
        />
        <Card size="small" type="inner" title="变更记录" style={{ marginTop: 12 }}>
          <Timeline
            items={(versionEvents || []).map((event) => ({
              color: event.action === 'DELETE' ? 'red'
                : event.action === 'ACTIVATE' ? 'green'
                  : event.action === 'MOVE' ? 'blue' : 'gray',
              children: (
                <Space direction="vertical" size={0}>
                  <Typography.Text strong>
                    {event.action === 'UPLOAD' ? '上传'
                      : event.action === 'ACTIVATE' ? '启用'
                        : event.action === 'DELETE' ? '删除' : '移动'}
                    {event.version ? ` v${event.version}` : ''}
                  </Typography.Text>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {event.actor || '-'} · {event.createdAt}
                    {event.detail ? ` · ${event.detail}` : ''}
                  </Typography.Text>
                </Space>
              ),
            }))}
          />
          {!versionEvents.length && <Typography.Text type="secondary">还没有变更记录</Typography.Text>}
        </Card>
      </Modal>

      <Modal
        title={editing ? `编辑函数：${editing.fullName}` : '注册函数'}
        open={fnOpen}
        okText={editing ? '保存' : '保存草稿'}
        onOk={() => submitFn().catch((e) => message.error(errMsg(e)))}
        onCancel={() => {
          setFnOpen(false)
          setEditing(undefined)
        }}
      >
        <Form form={fnForm} layout="vertical">
          <Form.Item label="所属资源" name="resourceName" rules={[{ required: true }]}>
            <Select
              showSearch
              optionFilterProp="label"
              onChange={(value) => {
                const resource = resourceByName.get(value)
                if (!resource) return
                fnForm.setFieldsValue({
                  language: resource.language,
                  engine: resource.language === 'python' ? 'FLINK' : fnForm.getFieldValue('engine') || 'DORIS',
                })
              }}
              options={Array.from(resourceByName.values()).map((r) => ({
                value: r.name,
                label: `${r.name}（${r.type}/${r.language === 'python' ? 'Python' : 'Java'}）${activeNames.has(r.name) ? '' : '（无启用版本）'}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            label="引擎"
            name="engine"
            rules={[{ required: true }]}
            extra={fnLanguage === 'python'
              ? 'Python 函数只能跑在 Flink：平台不做 Doris 远程 Python UDF。要在 Doris 的 SQL 里调用，请上传 Java（jar）资源、引擎选 Doris。'
              : 'Doris：查询页与工作流 SQL 里可直接调用；Flink：流开发作业提交时自动注入依赖。'}
          >
            <Select options={ENGINES.map((engine) => ({
              ...engine,
              disabled: engine.value === 'DORIS' && fnLanguage === 'python',
              // D59：禁用的选项要写清「为什么不能选」，不能只让它变灰
              label: engine.value === 'DORIS' && fnLanguage === 'python'
                ? `${engine.label}（Python 不支持，请改用 Java jar）`
                : engine.label,
            }))} />
          </Form.Item>
          <Form.Item label="语言" name="language">
            <Select options={[
              { value: 'java', label: 'Java（Doris / Flink）' },
              { value: 'python', label: 'Python（仅 Flink）' },
            ]} disabled />
          </Form.Item>
          <Form.Item
            label="函数短名"
            name="name"
            tooltip="最终调用名 = p{项目ID}_短名"
            rules={[{
              required: true,
              pattern: /^[a-z][a-z0-9_]{1,47}$/,
              message: '小写字母开头，只含小写字母、数字、下划线',
            }]}
          >
            <Input placeholder="例如 add_one" addonBefore={`p${projectId || 'Id'}_`} />
          </Form.Item>
          {fnLanguage === 'python' ? (
            <Form.Item
              label="Python 入口"
              name="className"
              tooltip="module.func；zip 里多级包用 pkg.module.func"
              rules={[
                { required: true, message: '填写 module.func' },
                { pattern: /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$/, message: '格式：module.func' },
              ]}
            >
              {pythonCandidates.length > 0 ? (
                <AutoComplete
                  options={pythonCandidates.map((entry) => ({ value: entry }))}
                  filterOption={(input, option) =>
                    String(option?.value || '').toLowerCase().includes(input.toLowerCase())}
                  placeholder="例如 my_udf.add_one"
                />
              ) : (
                <Input placeholder="例如 my_udf.add_one" />
              )}
            </Form.Item>
          ) : (
            <Form.Item
              label="Java 类名"
              name="className"
              rules={[{ required: true, message: '填写包含包名的完整类名' }]}
            >
              <Input placeholder="com.example.AddUdf" />
            </Form.Item>
          )}
          {fnLanguage === 'python' && pythonCandidates.length > 0 && (
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 12 }}
              message={`已从资源包解析到 ${pythonCandidates.length} 个入口候选`}
              description={pythonCandidates.slice(0, 5).join('、')}
            />
          )}
          <Form.Item label="参数类型（逗号分隔，可留空）" name="argTypes">
            <Input placeholder="INT, INT" />
          </Form.Item>
          <Form.Item label="返回类型" name="returnType" rules={[{ required: true }]}>
            <Input placeholder="INT" />
          </Form.Item>
          {fnLanguage === 'python' && fnEngine === 'DORIS' && (
            <Alert type="error" showIcon message="Doris 不支持 Python UDF，请把引擎改为 Flink" />
          )}
        </Form>
      </Modal>
    </div>
  )
}
