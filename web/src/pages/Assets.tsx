import { useCallback, useEffect, useMemo, useState } from 'react'
import { useLocation } from 'react-router-dom'
import {
  Alert, Button, Card, Col, Dropdown, Form, Image, Input, Modal, Pagination,
  Popconfirm, Row, Segmented, Select, Space, Spin, Switch, Tag, Tooltip, Tree, TreeSelect,
  Typography, Upload, message,
} from 'antd'
import type { TreeDataNode } from 'antd'
import {
  DeleteOutlined, DownloadOutlined, EyeOutlined, ExportOutlined, FilePdfOutlined, FileTextOutlined,
  FolderAddOutlined, FolderOpenOutlined, FolderOutlined, InboxOutlined, LockOutlined,
  MoreOutlined, PictureOutlined, ReloadOutlined, UserAddOutlined, VideoCameraOutlined,
} from '@ant-design/icons'
import { api, del, getToken, post, put } from '../api'
import { currentUser } from '../auth'
import AssetsMedia from './AssetsMedia'
import PageHeader from '../components/PageHeader'
import { can } from '../permissions'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import ErrorText, { errorTip } from '../components/ErrorText'

type Asset = {
  id: number
  /** 内容层字段：S11 两层口径下，无内容授权的行返回 null（列表/详情均裁剪）。 */
  uri: string | null
  media_type: string
  mime?: string
  size_bytes: number
  sha256?: string | null
  meta?: string
  status: string
  created_by?: string
  created_at?: string
  folder_id?: number | null
  url?: string
  reused?: boolean
  /** 目录层条目对全员下发；contentAuthorized=false 时下载/预览会 403。 */
  contentAuthorized?: boolean
  contentHint?: string
}

type ListResp = { items: Asset[]; page: number; size: number; total: number }

type FolderInfo = {
  id: number
  parentId: number | null
  name: string
  path: string
  createdBy?: string
  createdAt?: string
  directCount: number
  hasGrants: boolean
  canRead: boolean
  canWrite: boolean
  canManage: boolean
}

type TreeResp = { folders: FolderInfo[]; rootCount: number; rootWritable: boolean }

type GrantRow = { subjectType: 'USER' | 'ROLE'; subjectId: string; canRead: boolean; canWrite: boolean }

const TYPES = [
  { label: '全部', value: '' },
  { label: '图片', value: 'IMAGE' },
  { label: 'PDF', value: 'PDF' },
  { label: '文本', value: 'TEXT' },
  { label: '音频', value: 'AUDIO' },
  { label: '视频', value: 'VIDEO' },
  { label: '其他', value: 'OTHER' },
]

/** 目录树选择：'all'=全部可见，'root'=我的上传（folder_id=NULL），'f:<id>'=具体目录 */
function keyToFolderId(key: string): number | undefined {
  if (key === 'all') return undefined
  if (key === 'root') return 0
  return Number(key.slice(2))
}

function typeIcon(type: string) {
  if (type === 'IMAGE') return <PictureOutlined />
  if (type === 'PDF') return <FilePdfOutlined />
  if (type === 'TEXT') return <FileTextOutlined />
  if (type === 'VIDEO') return <VideoCameraOutlined />
  return <InboxOutlined />
}

function sizeText(bytes?: number) {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function nameOf(asset: Asset) {
  try {
    const meta = asset.meta ? JSON.parse(asset.meta) : {}
    if (meta?.originalFilename) return String(meta.originalFilename)
  } catch {
    // meta 不是合法 JSON 时退回 URI 尾部
  }
  // S11：无内容授权的行 uri 被裁剪为 null，回退用 id 命名（名称本身属目录层，照常展示）
  const tail = asset.uri?.split('/').pop() || `asset-${asset.id}`
  return tail
}

/**
 * 多模态资产（V110 目录化 + S11 两层口径）：左侧目录树（建/改名/移动/删除/授权），
 * 右侧按目录过滤的资产网格。文件本体在 MinIO，平台只存元数据。
 *
 * <p>口径（评审确认 2026-10-26，见 docs/资产目录与授权-20261026.md）：
 * <b>目录层</b>——目录树/列表/详情元数据对同租户全员可见，被关目录显示锁标志但不再消失；
 * <b>内容层</b>——下载/预览（预签名）需要授权：owner、目录内容授权或管理侧，
 * 无授权后端返回 403 并提示提交权限申请（行上 {@code contentAuthorized=false}）。
 * 无写权不能上传/移动/删除；虚拟根「我的上传」里的内容仅上传人与管理员可取。</p>
 */
export default function Assets() {
  const location = useLocation()
  const [items, setItems] = useState<Asset[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(24)
  const [type, setType] = useState('')
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [listError, setListError] = useState<any>(null)
  const [uploading, setUploading] = useState(false)
  const [preview, setPreview] = useState<Asset>()
  const [previewText, setPreviewText] = useState('')
  const [previewError, setPreviewError] = useState('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [folderForm] = Form.useForm<{ name: string }>()
  const writable = can('asset:write')
  // REQ-UNIFY-7：媒体库（MOS media API）并入资产面；标签本身按 mos:media 权限点裁剪
  const mediaAllowed = can('mos:media')
  // BUG-PARITY-13-C：URL 可直达媒体库（/assets?view=media&pick=1 = 向导「去媒体库挑」的落点；
  // 同路由换 query 时组件不重挂，useState 初始值不重算，所以再用 effect 跟一次）
  // BUG-PARITY-14：tab 记忆。老版媒体是顶级路由 1 击直达；并入后「默认资产视图需再点、
  // 选择不记忆」=断头。上次选的 tab 落 localStorage（dw_assets_tab），进页直接落回去；
  // ?view= 深链仍优先（13-C 落点口径不变）。存储不可用（隐私模式）静默退化=不记忆，不弹错。
  const viewFromUrl = new URLSearchParams(location.search).get('view')
  const [view, setView] = useState<'asset' | 'media'>(() => {
    if (viewFromUrl === 'media' || viewFromUrl === 'asset') return viewFromUrl
    let saved = ''
    try { saved = localStorage.getItem('dw_assets_tab') || '' } catch { /* 同上 */ }
    return saved === 'media' ? 'media' : 'asset'
  })
  const switchView = (v: 'asset' | 'media') => {
    setView(v)
    try { localStorage.setItem('dw_assets_tab', v) } catch { /* 同上 */ }
  }
  useEffect(() => {
    if (mediaAllowed && viewFromUrl === 'media') setView('media')
  }, [mediaAllowed, viewFromUrl])

  // ---- 目录 ----
  const [tree, setTree] = useState<TreeResp>({ folders: [], rootCount: 0, rootWritable: true })
  const [selectedKey, setSelectedKey] = useState('all')
  const [expandedKeys, setExpandedKeys] = useState<(string | number)[]>([])
  const [folderModal, setFolderModal] = useState<{ open: boolean; title: string; parentId: number | null; editId?: number }>()
  const [moveAsset, setMoveAsset] = useState<Asset>()
  const [moveTarget, setMoveTarget] = useState<number>(0)
  const [grantFolder, setGrantFolder] = useState<FolderInfo>()
  const [grantRows, setGrantRows] = useState<GrantRow[]>([])
  const [grantOpenDefault, setGrantOpenDefault] = useState(true)
  const [grantSaving, setGrantSaving] = useState(false)
  const [grantOptions, setGrantOptions] = useState<{ users: { subjectId: string; displayName: string }[]; roles: { subjectId: string; displayName: string }[]; myRoles: string[] }>({ users: [], roles: [], myRoles: [] })

  const folders = tree.folders
  const folderById = useMemo(() => new Map(folders.map((f) => [f.id, f])), [folders])
  const selectedFolder = selectedKey.startsWith('f:') ? folderById.get(Number(selectedKey.slice(2))) : undefined
  // 当前上传/新建目标目录的写权限：虚拟根由 asset:write 权限点控制，恒可写
  const targetWritable = !selectedFolder || selectedFolder.canWrite

  const loadTree = useCallback(async () => {
    try {
      const data = await api<TreeResp>('/api/asset-folders')
      const list = Array.isArray(data?.folders) ? data.folders : []
      setTree({ folders: list, rootCount: Number(data?.rootCount || 0), rootWritable: data?.rootWritable !== false })
      setExpandedKeys((prev) => (prev.length ? prev : list.filter((f) => f.parentId == null).map((f) => f.id)))
    } catch {
      // 目录树失败不挡资产列表（比如老后端还没部署 V110）
      setTree({ folders: [], rootCount: 0, rootWritable: true })
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setListError(null)
    try {
      const qs = new URLSearchParams({ page: String(page), size: String(size) })
      if (type) qs.set('mediaType', type)
      const fid = keyToFolderId(selectedKey)
      if (fid !== undefined) qs.set('folderId', String(fid))
      const data = await api<ListResp>(`/api/assets?${qs.toString()}`)
      setItems(Array.isArray(data?.items) ? data.items : [])
      setTotal(Number(data?.total || 0))
    } catch (e: any) {
      setListError(e)
      setItems([])
      setTotal(0)
    } finally {
      setLoading(false)
    }
  }, [page, size, type, selectedKey])

  useEffect(() => { load() }, [load])
  useEffect(() => { loadTree() }, [loadTree])

  const reloadAll = () => Promise.all([loadTree(), load()])

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    if (!kw) return items
    return items.filter((a) => nameOf(a).toLowerCase().includes(kw))
  }, [items, keyword])

  /** 资产所在目录的可写性（删除/移动的判定，矩阵见 docs）。 */
  const canTouchAsset = (asset: Asset) => {
    if (!writable) return false
    if (asset.folder_id == null || asset.folder_id === 0) return true
    return folderById.get(asset.folder_id)?.canWrite ?? false
  }

  const upload = async (file: File) => {
    const body = new FormData()
    body.append('file', file)
    if (selectedFolder) body.append('folderId', String(selectedFolder.id))
    const res = await fetch('/api/assets', {
      method: 'POST',
      headers: getToken() ? { Authorization: `Bearer ${getToken()}` } : undefined,
      body,
    })
    const data = await res.json().catch(() => null)
    if (!res.ok) throw new Error(data?.message || `上传失败（${res.status}）`)
    return data as Asset
  }

  const onUpload = async (files: File[]) => {
    if (!writable) {
      message.warning('当前角色没有上传多模态资产的权限（asset:write）')
      return
    }
    if (!targetWritable) {
      message.warning(`目录「${selectedFolder?.name}」为只读：上传需要该目录的写权限`)
      return
    }
    setUploading(true)
    let ok = 0
    let reused = 0
    try {
      for (const file of files) {
        const asset = await upload(file)
        ok += 1
        if (asset?.reused) reused += 1
      }
      message.success(`上传完成：${ok} 个${reused ? `，其中 ${reused} 个复用已有内容` : ''}`)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const remove = async (asset: Asset) => {
    try {
      await del(`/api/assets/${asset.id}`)
      message.success('已删除（对象保留，可由运维统一清理）')
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '删除失败')
    }
  }

  /**
   * 点击资产 → 详情与预览（REQ-DW-2 ③④）。列表按 S11 口径不下发预签名地址，
   * 此前 IMAGE/PDF 点开是空弹窗。现在：先取一次详情（有内容授权才带 url），
   * 再按类型内嵌预览；无授权或取址失败给出明确的「没有权限」提示与申请指引。
   */
  const openPreview = async (asset: Asset) => {
    setPreviewText('')
    setPreviewError('')
    setPreview(asset)
    // S11 内容层：无授权的行不发起预签名请求（后端会 403），弹窗直接提示提交权限申请
    if (asset.contentAuthorized === false) return
    setPreviewLoading(true)
    try {
      let url = asset.url
      if (!url) {
        const d = await api<Asset>(`/api/assets/${asset.id}`)
        setPreview(d)
        if (d.contentAuthorized === false) return
        url = d.url
      }
      if (url && asset.media_type === 'TEXT') {
        try {
          const resp = await fetch(url)
          setPreviewText((await resp.text()).slice(0, 20000))
        } catch {
          setPreviewText('文本内容读取失败：若提示无内容访问权限，请对该资产所在目录提交权限申请')
        }
      }
    } catch (e: any) {
      setPreviewError(e?.status === 403
        ? (e?.message || '没有该资产的内容访问权限，请提交权限申请')
        : errorTip(e, e?.status))
    } finally {
      setPreviewLoading(false)
    }
  }

  // ---------------------------------------------------------------- 目录树

  const buildTree = (): TreeDataNode[] => {
    const children = (parentId: number | null): TreeDataNode[] => folders
      .filter((f) => (f.parentId ?? null) === parentId)
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((f) => ({
        key: `f:${f.id}`,
        title: f.name,
        icon: f.hasGrants ? <LockOutlined /> : <FolderOutlined />,
        folder: f,
        children: children(f.id),
      })) as TreeDataNode[]
    return [
      { key: 'all', title: '全部资产', icon: <FolderOpenOutlined /> },
      { key: 'root', title: `我的上传（${tree.rootCount}）`, icon: <FolderOutlined /> },
      ...children(null),
    ]
  }

  /**
   * 目录移动/资产移动的候选树：只列可见目录；onlyWritable 时只留可写目录；
   * exclude 时递归排除该目录及其整棵子树（防「移到自己的子孙下」白跑一趟）。
   */
  const buildSelectTree = (onlyWritable: boolean, includeRoot: boolean, exclude?: FolderInfo) => {
    const isExcluded = (f: FolderInfo) => !!exclude
      && (f.id === exclude.id || f.path === exclude.path || f.path.startsWith(`${exclude.path}/`))
    const children = (parentId: number | null): any[] => folders
      .filter((f) => (f.parentId ?? null) === parentId)
      .filter((f) => !onlyWritable || f.canWrite)
      .filter((f) => !isExcluded(f))
      .sort((a, b) => a.name.localeCompare(b.name))
      .map((f) => ({
        value: f.id,
        title: `${f.path}${f.hasGrants ? '（受限）' : ''}`,
        children: children(f.id),
      }))
    return [
      ...(includeRoot ? [{ value: 0, title: '我的上传（根目录）' }] : []),
      ...children(null),
    ]
  }

  const onDrop = async (info: any) => {
    const dragKey = String(info.dragNode?.key || '')
    const dropKey = String(info.node?.key || '')
    if (!dragKey.startsWith('f:') || dragKey === dropKey) return
    const id = Number(dragKey.slice(2))
    const dropFolder = dropKey.startsWith('f:') ? folderById.get(Number(dropKey.slice(2))) : undefined
    // BUG-DW-15：目标必须等于鼠标指示的位置——拖进行中间=放进该目录；
    // 拖到行间缝隙=移到该行的同层（其父目录下），而不是统统塞进鼠标掠过的那一行。
    const parentId = dropFolder
      ? (info.dropToGap ? (dropFolder.parentId ?? 0) : dropFolder.id)
      : 0
    const dragged = folderById.get(id)
    if (dropFolder && dragged?.path && dropFolder.path.startsWith(`${dragged.path}/`)) {
      message.warning('不能把目录移动到它自己的子目录里')
      await reloadAll()
      return
    }
    if (parentId === (dragged?.parentId ?? 0)) {
      // 同层缝隙拖回原位等无变化场景：不浪费一次写请求，只把视图复位
      await reloadAll()
      return
    }
    try {
      await put(`/api/asset-folders/${id}`, { parentId })
      message.success(parentId === 0 ? '目录已移动到根级' : '目录已移动')
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '移动目录失败')
    }
  }

  // ---------------------------------------------------------------- 目录操作

  const createFolder = async (values: { name: string }) => {
    try {
      if (folderModal?.editId) {
        await put(`/api/asset-folders/${folderModal.editId}`, { name: values.name })
        message.success('目录已重命名')
      } else {
        await post('/api/asset-folders', { name: values.name, parentId: folderModal?.parentId ?? 0 })
        message.success('目录已创建')
      }
      setFolderModal(undefined)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '保存目录失败')
    }
  }

  const deleteFolder = async (folder: FolderInfo) => {
    try {
      await del(`/api/asset-folders/${folder.id}`)
      message.success('目录已删除')
      if (selectedKey === `f:${folder.id}`) setSelectedKey('root')
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '删除目录失败')
    }
  }

  const moveFolder = (folder: FolderInfo) => {
    let target = folder.parentId ?? 0
    Modal.confirm({
      title: `把「${folder.name}」移动到…`,
      icon: null,
      content: (
        <TreeSelect
          style={{ width: '100%', marginTop: 8 }}
          treeDefaultExpandAll
          value={target}
          treeData={buildSelectTree(false, true, folder)}
          onChange={(v) => { target = Number(v) }}
        />
      ),
      okText: '移动',
      onOk: async () => {
        try {
          await put(`/api/asset-folders/${folder.id}`, { parentId: target })
          message.success('目录已移动')
          await reloadAll()
        } catch (e: any) {
          message.error(e?.message || '移动目录失败')
          throw e
        }
      },
    })
  }

  const moveAssetTo = async () => {
    if (!moveAsset) return
    try {
      await put(`/api/assets/${moveAsset.id}/folder`, { folderId: moveTarget })
      message.success(moveTarget === 0 ? '已移动到「我的上传」' : '资产已移动')
      setMoveAsset(undefined)
      await reloadAll()
    } catch (e: any) {
      message.error(e?.message || '移动资产失败')
    }
  }

  // ---------------------------------------------------------------- 授权

  const openGrants = async (folder: FolderInfo) => {
    try {
      const [g, opts] = await Promise.all([
        api<{ grants: GrantRow[]; openDefault: boolean }>(`/api/asset-folders/${folder.id}/grants`),
        api<typeof grantOptions>('/api/asset-folders/grant-options'),
      ])
      setGrantRows(Array.isArray(g?.grants) ? g.grants : [])
      setGrantOpenDefault(Boolean(g?.openDefault))
      setGrantOptions(opts)
      setGrantFolder(folder)
    } catch (e: any) {
      message.error(e?.message || '读取目录授权失败')
    }
  }

  const saveGrants = async () => {
    if (!grantFolder) return
    const rows = grantRows.filter((r) => r.subjectId)
    const dup = new Set<string>()
    for (const r of rows) {
      const k = `${r.subjectType}:${r.subjectId.toLowerCase()}`
      if (dup.has(k)) {
        message.error(`主体重复：${r.subjectId}`)
        return
      }
      dup.add(k)
    }
    const me = currentUser()
    const selfCovered = !rows.length || rows.some((r) => r.canWrite
      && ((r.subjectType === 'USER' && r.subjectId.toLowerCase() === me.toLowerCase())
        || (r.subjectType === 'ROLE' && grantOptions.myRoles
            .some((code) => code.toLowerCase() === r.subjectId.toLowerCase()))))
    if (!selfCovered) {
      Modal.confirm({
        title: '你会失去本目录的管理权',
        content: '新授权列表里没有你（作为用户或角色）的写权限，保存后将无法再打开本弹窗改回来，需要目录创建者或管理员处理。确认保存？',
        okText: '仍然保存',
        okButtonProps: { danger: true },
        onOk: () => doSaveGrants(rows),
      })
      return
    }
    await doSaveGrants(rows)
  }

  const doSaveGrants = async (rows: GrantRow[]) => {
    setGrantSaving(true)
    try {
      const g = await put<any>(`/api/asset-folders/${grantFolder!.id}/grants`, { grants: rows })
      setGrantRows(Array.isArray(g?.grants) ? g.grants : [])
      setGrantOpenDefault(Boolean(g?.openDefault))
      message.success(rows.length ? `已保存 ${rows.length} 条授权` : '已撤销全部授权，目录恢复默认开放')
      await loadTree()
    } catch (e: any) {
      message.error(e?.message || '保存授权失败')
    } finally {
      setGrantSaving(false)
    }
  }

  /** 打开新建/重命名目录弹窗：先清上次输入，重命名回填当前名。 */
  const openFolderModal = (title: string, parentId: number | null, editId?: number) => {
    folderForm.resetFields()
    if (editId != null) folderForm.setFieldsValue({ name: folderById.get(editId)?.name })
    setFolderModal({ open: true, title, parentId, editId })
  }

  const folderMenu = (folder: FolderInfo) => ({
    items: [
      // 建子目录与后端同口径：父目录「可写」即可（create 走 requireWritable）；
      // 改名/移动/授权/删除仍要求管理权（REQ-DW-2 ①：此前只挂 canManage，可写成员看不到入口）
      ...(folder.canWrite ? [
        { key: 'create', label: '新建子目录', icon: <FolderAddOutlined /> },
      ] : []),
      ...(folder.canManage ? [
        { key: 'rename', label: '重命名' },
        { key: 'move', label: '移动到…', icon: <ExportOutlined /> },
        { key: 'grant', label: folder.hasGrants ? '授权设置（已受限）' : '授权设置', icon: <UserAddOutlined /> },
        { type: 'divider' as const },
        { key: 'delete', label: '删除（仅空目录）', danger: true },
      ] : []),
    ],
    onClick: ({ key, domEvent }: any) => {
      domEvent?.stopPropagation?.()
      if (key === 'create') openFolderModal(`在「${folder.name}」下新建子目录`, folder.id)
      if (key === 'rename') openFolderModal(`重命名「${folder.name}」`, folder.parentId, folder.id)
      if (key === 'move') moveFolder(folder)
      if (key === 'grant') openGrants(folder)
      if (key === 'delete') deleteFolder(folder)
    },
  })

  const treeTitle = (node: any) => {
    const folder = node.folder as FolderInfo | undefined
    if (!folder) return node.title
    const row = (
      <Space size={4} style={{ width: '100%', justifyContent: 'space-between' }}>
        <Space size={4}>
          {folder.hasGrants ? <LockOutlined style={{ color: '#faad14' }} /> : <FolderOutlined />}
          <span>{folder.name}</span>
          {folder.directCount > 0 ? <Tag style={{ marginInlineStart: 2 }}>{folder.directCount}</Tag> : null}
        </Space>
        <Space size={4}>
          {!folder.canWrite && <Tag color="default" style={{ marginInlineEnd: 0 }}>只读</Tag>}
          {folder.canManage && writable ? (
            <Dropdown menu={folderMenu(folder)} trigger={['click']}>
              <MoreOutlined onClick={(e) => e.stopPropagation()} />
            </Dropdown>
          ) : null}
        </Space>
      </Space>
    )
    // BUG-DW-15：与其他树（数据开发/流开发项目树）同口径——右键也出菜单，
    // 菜单动作来自被右键的节点本身（闭包 folder），不依赖先左键选中。
    if (!writable || (!folder.canWrite && !folder.canManage)) return row
    return (
      <Dropdown menu={folderMenu(folder)} trigger={['contextMenu']}>
        {row}
      </Dropdown>
    )
  }

  // 上传落点：选中具体目录=该目录；「全部资产」「我的上传」=虚拟根
  const uploadTargetName = selectedFolder ? selectedFolder.path : '我的上传'
  const locationName = selectedKey === 'all' ? '全部资产'
    : selectedKey === 'root' ? '我的上传' : (selectedFolder?.path || '…')

  // ---------------------------------------------------------------- 渲染

  return (
    <div>
      <PageHeader
        title="多模态资产"
        description="图片、PDF、文本等非结构化文件统一存放：文件本体在对象存储，平台只存元数据（类型、大小、sha256、上传人）。目录授权见左侧目录树。"
        extra={(
          <Space>
            <Input.Search
              allowClear
              placeholder="按文件名筛选（当前页）"
              style={{ width: 220 }}
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
            />
            <Button icon={<ReloadOutlined />} onClick={reloadAll} loading={loading}>刷新</Button>
          </Space>
        )}
      />

      {mediaAllowed && (
        <Segmented
          style={{ marginBottom: 12 }}
          value={view}
          data-testid="assets-tab"
          onChange={(v) => switchView(v as 'asset' | 'media')}
          options={[
            { label: '平台资产', value: 'asset' },
            { label: '媒体库（MOS）', value: 'media' },
          ]}
        />
      )}
      {mediaAllowed && view === 'media' ? (
        <AssetsMedia />
      ) : (
      <>
      {!writable && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="当前角色只能查看资产"
          description="上传与删除需要 asset:write；目录被单独授权时，能否看到内容还取决于目录的读权限。"
        />
      )}

      <Row gutter={12}>
        <Col xs={24} md={6} lg={5}>
          <Card
            size="small"
            title="资产目录"
            extra={(
              <Tooltip title={selectedFolder
                ? `在「${selectedFolder.name}」下新建子目录（跟随当前选中；未选中目录时建到根）`
                : '在「我的上传」下新建根目录'}>
                <Button size="small" type="text" icon={<FolderAddOutlined />}
                        disabled={!writable || !targetWritable}
                        onClick={() => (selectedFolder
                          ? openFolderModal(`在「${selectedFolder.name}」下新建目录`, selectedFolder.id)
                          : openFolderModal('新建目录（根）', 0))} />
              </Tooltip>
            )}
            style={{ marginBottom: 12 }}
          >
            <Tree
              blockNode
              showIcon
              draggable={writable}
              treeData={buildTree()}
              selectedKeys={[selectedKey]}
              expandedKeys={expandedKeys}
              onExpand={(keys) => setExpandedKeys(keys as (string | number)[])}
              onSelect={(keys) => {
                if (!keys.length) return // 再点一次已选节点不反选，避免视图跳回「全部」
                setPage(1)
                setSelectedKey(String(keys[0]))
              }}
              onRightClick={({ event, node }) => {
                // BUG-DW-15：右键哪一行，选中态就落到哪一行——菜单/工具栏动作与鼠标目标保持一致，
                // 不再出现「选中 A、操作 B」的错位
                event.preventDefault?.()
                setPage(1)
                setSelectedKey(String(node.key))
              }}
              onDrop={onDrop}
              titleRender={treeTitle}
              style={{ fontSize: 13 }}
            />
          </Card>
        </Col>

        <Col xs={24} md={18} lg={19}>
          {writable && targetWritable && (
            <Card size="small" style={{ marginBottom: 12 }}>
              <Upload.Dragger
                multiple
                showUploadList={false}
                disabled={uploading}
                beforeUpload={(file) => {
                  if (file.size > 50 * 1024 * 1024) {
                    message.error(`${file.name} 超过 50 MB`)
                    return Upload.LIST_IGNORE
                  }
                  void onUpload([file])
                  return Upload.LIST_IGNORE
                }}
              >
                <p className="ant-upload-drag-icon"><InboxOutlined /></p>
                <p className="ant-upload-text">
                  {uploading ? '上传中…' : `点击或拖拽文件到这里上传（到「${uploadTargetName}」）`}
                </p>
                <p className="ant-upload-hint">
                  单文件 ≤ 50 MB；同目录同内容（sha256）自动复用；支持图片 / PDF / 文本预览
                </p>
              </Upload.Dragger>
            </Card>
          )}
          {writable && !targetWritable && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              message={`目录「${selectedFolder?.name}」为只读`}
              description="你只有该目录的读权限；上传、移动、删除需要写权限，请联系目录创建者或管理员在「授权设置」中开通。"
            />
          )}

          <Space style={{ marginBottom: 12 }} wrap>
            <Segmented
              options={TYPES}
              value={type}
              onChange={(value) => { setPage(1); setType(String(value)) }}
            />
            <Typography.Text type="secondary">共 {total} 个 · 当前位置：{locationName}</Typography.Text>
            {selectedFolder && (
              <Tag color={selectedFolder.canWrite ? 'green' : 'default'}>
                {selectedFolder.canWrite ? '读写' : '只读'}
              </Tag>
            )}
          </Space>

          {listError ? (
            <ErrorState
              title={listError?.status === 403 ? '没有权限' : '资产列表加载失败'}
              message={listError?.status === 403
                ? '当前账号没有查看多模态资产的权限（asset:read）。如需查看或上传，请联系管理员分配对应角色，或在「权限申请」提交申请。'
                : <ErrorText error={listError} />}
              onRetry={load}
            />
          ) : (
          <Spin spinning={loading}>
            {loading && items.length === 0 ? (
              <LoadingState rows={6} />
            ) : filtered.length === 0 ? (
              <EmptyState
                title="这里还没有资产"
                hint={selectedFolder
                  ? `选中目录为「${selectedFolder.name}」，把文件拖到上方上传区即可上传`
                  : '把文件拖到上方上传区即可上传（图片 / PDF / 文本 / 音视频）'}
              />
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 12 }}>
                {filtered.map((asset) => (
                  <Card
                    key={asset.id}
                    size="small"
                    hoverable
                    cover={(
                      <div
                        style={{ height: 140, display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#fafafa', overflow: 'hidden', cursor: 'pointer' }}
                        onClick={() => openPreview(asset)}
                      >
                        {asset.media_type === 'IMAGE' && asset.url ? (
                          <Image src={asset.url} preview={false} height={140}
                            style={{ objectFit: 'cover', width: '100%' }} />
                        ) : (
                          <span style={{ fontSize: 42, color: asset.contentAuthorized === false ? '#bfbfbf' : '#8c8c8c' }}>
                            {asset.contentAuthorized === false
                              ? <Tooltip title="无内容访问权限：点开可查看授权申请提示"><LockOutlined /></Tooltip>
                              : typeIcon(asset.media_type)}
                          </span>
                        )}
                      </div>
                    )}
                    actions={[
                      <EyeOutlined key="preview" onClick={() => openPreview(asset)} />,
                      canTouchAsset(asset) ? (
                        <Tooltip key="move" title="移动到…">
                          <ExportOutlined onClick={() => {
                            setMoveAsset(asset)
                            setMoveTarget(asset.folder_id ?? 0)
                          }} />
                        </Tooltip>
                      ) : <span key="move" />,
                      canTouchAsset(asset) ? (
                        <Popconfirm
                          key="delete"
                          title={`删除资产「${nameOf(asset)}」？`}
                          description="列表里会移除；对象文件保留，可由运维统一清理。"
                          okText="删除"
                          okButtonProps={{ danger: true }}
                          cancelText="取消"
                          onConfirm={() => remove(asset)}
                        >
                          <DeleteOutlined />
                        </Popconfirm>
                      ) : <span key="delete" />,
                    ]}
                  >
                    <Card.Meta
                      title={<Typography.Text ellipsis={{ tooltip: nameOf(asset) }}>{nameOf(asset)}</Typography.Text>}
                      description={(
                        <Space direction="vertical" size={2} style={{ width: '100%' }}>
                          <Space size={4} wrap>
                            <Tag>{asset.media_type}</Tag>
                            <Typography.Text type="secondary">{sizeText(asset.size_bytes)}</Typography.Text>
                            {selectedKey === 'all' && (
                              <Tag color="blue">
                                {asset.folder_id == null ? '我的上传'
                                  : (folderById.get(asset.folder_id)?.name || '…')}
                              </Tag>
                            )}
                          </Space>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            {asset.created_by || '-'} · {asset.created_at ? String(asset.created_at).slice(0, 19).replace('T', ' ') : '-'}
                          </Typography.Text>
                        </Space>
                      )}
                    />
                  </Card>
                ))}
              </div>
            )}
          </Spin>
          )}

          {total > size && (
            <div style={{ marginTop: 16, textAlign: 'right' }}>
              <Pagination
                current={page}
                pageSize={size}
                total={total}
                showSizeChanger
                pageSizeOptions={[12, 24, 48, 96]}
                onChange={(p, s) => { setPage(p); setSize(s) }}
              />
            </div>
          )}
        </Col>
      </Row>
      </>
      )}

      {/* 新建 / 重命名目录（REQ-DW-2 ①：此前只有 okText 没有 onOk，点「保存」不会提交） */}
      <Modal
        open={!!folderModal?.open}
        title={folderModal?.title}
        okText="保存"
        onOk={() => folderForm.submit()}
        onCancel={() => setFolderModal(undefined)}
        destroyOnClose
      >
        <Form form={folderForm} onFinish={createFolder} layout="vertical">
          <Form.Item
            name="name"
            label="目录名称"
            rules={[
              { required: true, message: '请输入目录名' },
              { max: 64, message: '目录名最长 64 个字符' },
            ]}
          >
            <Input
              placeholder="例如：产品素材（字母/数字/中文/空格/点/横线/下划线）"
              autoFocus
              onPressEnter={() => folderForm.submit()}
            />
          </Form.Item>
        </Form>
      </Modal>

      {/* 资产移动 */}
      <Modal
        open={!!moveAsset}
        title={moveAsset ? `把「${nameOf(moveAsset)}」移动到…` : ''}
        okText="移动"
        onCancel={() => setMoveAsset(undefined)}
        onOk={moveAssetTo}
        destroyOnClose
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          只列出你有写权限的目录；移动需要源目录与目标目录双方的写权限。
        </Typography.Paragraph>
        <TreeSelect
          style={{ width: '100%' }}
          treeDefaultExpandAll
          value={moveTarget}
          treeData={buildSelectTree(true, true)}
          onChange={(v) => setMoveTarget(Number(v))}
        />
      </Modal>

      {/* 目录授权（权限查看 + 配置） */}
      <Modal
        open={!!grantFolder}
        title={grantFolder ? `目录授权：${grantFolder.path}` : ''}
        width={640}
        okText="保存授权"
        onCancel={() => setGrantFolder(undefined)}
        onOk={saveGrants}
        confirmLoading={grantSaving}
        destroyOnClose
      >
        <Alert
          type={grantOpenDefault ? 'info' : 'warning'}
          showIcon
          style={{ marginBottom: 12 }}
          message={grantOpenDefault
            ? '当前未配置授权：本租户成员默认可以下载/预览/写该目录内资产（目录与条目本就对全员可见）'
            : `已配置 ${grantRows.length} 条授权：仅下列主体可取内容/写（目录创建者与管理员保留兜底权限）`}
          description="授权只约束内容层（下载/预览/写），不拦目录与列表可见性；只作用于本目录、不向下继承，但取内容要求整条祖先路径可读，限制本目录会连带断开子树的内容路径（子目录仍可见，下载 403）。写权限自动包含读。"
        />
        {grantRows.map((row, idx) => (
          <Space key={idx} style={{ display: 'flex', marginBottom: 8 }} align="baseline">
            <Select
              value={row.subjectType}
              style={{ width: 92 }}
              options={[{ value: 'USER', label: '用户' }, { value: 'ROLE', label: '角色' }]}
              onChange={(subjectType) => setGrantRows(grantRows.map((r, i) => (i === idx
                ? { ...r, subjectType, subjectId: '' } : r)))}
            />
            <Select
              showSearch
              value={row.subjectId || undefined}
              placeholder="选择用户/角色"
              style={{ width: 230 }}
              optionFilterProp="label"
              options={row.subjectType === 'USER'
                ? grantOptions.users.map((u) => ({ value: u.subjectId, label: `${u.displayName}（${u.subjectId}）` }))
                : grantOptions.roles.map((r) => ({ value: r.subjectId, label: `${r.displayName}（${r.subjectId}）` }))}
              onChange={(subjectId) => setGrantRows(grantRows.map((r, i) => (i === idx ? { ...r, subjectId } : r)))}
            />
            <Tooltip title="读=可下载/取预览地址（目录与条目对全员可见，不受此项限制）">
              <Space size={4}>
                <span>读</span>
                <Switch checked={row.canRead || row.canWrite} disabled={row.canWrite}
                  onChange={(canRead) => setGrantRows(grantRows.map((r, i) => (i === idx ? { ...r, canRead } : r)))} />
              </Space>
            </Tooltip>
            <Tooltip title="写=可上传/移动/删除该目录内资产，并可管理该目录">
              <Space size={4}>
                <span>写</span>
                <Switch checked={row.canWrite}
                  onChange={(canWrite) => setGrantRows(grantRows.map((r, i) => (i === idx
                    ? { ...r, canWrite, canRead: canWrite ? true : r.canRead } : r)))} />
              </Space>
            </Tooltip>
            <Button size="small" type="text" danger icon={<DeleteOutlined />}
              onClick={() => setGrantRows(grantRows.filter((_, i) => i !== idx))} />
          </Space>
        ))}
        <Space style={{ marginTop: 4 }}>
          <Button size="small" icon={<UserAddOutlined />}
                  disabled={grantRows.length >= 100}
                  onClick={() => setGrantRows([...grantRows, { subjectType: 'USER', subjectId: '', canRead: true, canWrite: false }])}>
            新增授权
          </Button>
          {grantRows.length > 0 && (
            <Button size="small" type="text" danger onClick={() => setGrantRows([])}>
              清空（恢复默认开放）
            </Button>
          )}
        </Space>
      </Modal>

      {/* 资产详情 / 预览（REQ-DW-2 ③：点开先取详情拿预签名地址，再按类型内嵌浏览） */}
      <Modal
        open={!!preview}
        title={preview ? nameOf(preview) : ''}
        footer={null}
        width={preview?.media_type === 'PDF' || preview?.media_type === 'VIDEO' ? 900 : 720}
        onCancel={() => setPreview(undefined)}
      >
        {preview && (
          <Space size={6} wrap style={{ marginBottom: 10 }}>
            <Tag>{preview.media_type}</Tag>
            <Typography.Text type="secondary">{sizeText(preview.size_bytes)}</Typography.Text>
            <Typography.Text type="secondary">上传人：{preview.created_by || '-'}</Typography.Text>
            <Typography.Text type="secondary">
              {preview.created_at ? String(preview.created_at).slice(0, 19).replace('T', ' ') : '-'}
            </Typography.Text>
            {preview.folder_id != null && folderById.get(preview.folder_id) ? (
              <Tag color="blue">{folderById.get(preview.folder_id)!.path}</Tag>
            ) : null}
          </Space>
        )}
        {preview?.contentAuthorized === false && (
          <Alert
            type="warning"
            showIcon
            message="没有权限：无法打开该资产内容"
            description={preview.contentHint
              || '元数据（名称/类型/大小/归属）对租户内全员可见，但下载与预览文件本体需要内容授权：请提交权限申请，或联系上传人 / 目录管理员授权。'}
          />
        )}
        {previewError && (
          <Alert type="warning" showIcon message="无法打开该资产内容" description={previewError} />
        )}
        {preview && previewLoading && !preview.url && preview.contentAuthorized !== false && !previewError && (
          <div style={{ textAlign: 'center', padding: '40px 0' }}><Spin /></div>
        )}
        {preview && preview.contentAuthorized !== false && !previewError && preview.url && (
          <>
            {preview.media_type === 'IMAGE' && (
              <div style={{ textAlign: 'center' }}>
                <img src={preview.url} alt={nameOf(preview)} style={{ maxWidth: '100%' }} />
              </div>
            )}
            {preview.media_type === 'PDF' && (
              <iframe title="pdf-preview" src={preview.url} style={{ width: '100%', height: 600, border: 0 }} />
            )}
            {preview.media_type === 'AUDIO' && (
              <audio controls src={preview.url} style={{ width: '100%' }} />
            )}
            {preview.media_type === 'VIDEO' && (
              <video controls src={preview.url} style={{ width: '100%', maxHeight: 480, background: '#000' }} />
            )}
            {preview.media_type === 'TEXT' && (
              <pre style={{ maxHeight: 560, overflow: 'auto', whiteSpace: 'pre-wrap', margin: 0 }}>
                {previewText || '加载中…'}
              </pre>
            )}
            {!['IMAGE', 'PDF', 'TEXT', 'AUDIO', 'VIDEO'].includes(preview.media_type) && (
              <Alert
                type="info"
                showIcon
                style={{ marginBottom: 12 }}
                message="该类型暂不支持内嵌预览"
                description="点下方「下载原文件」后用本地应用打开（预签名地址 10 分钟内有效）。"
              />
            )}
            <Space style={{ marginTop: 12 }}>
              <Button icon={<DownloadOutlined />} href={preview.url} target="_blank" rel="noreferrer">
                下载原文件
              </Button>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>预签名地址 10 分钟内有效</Typography.Text>
            </Space>
          </>
        )}
        {preview && preview.contentAuthorized !== false && !previewError && !previewLoading && !preview.url && (
          <EmptyState compact title="没有可用的预览地址" hint="对象存储未配置或授权未生效；可稍后重试，或在「权限申请」提交内容授权申请。" />
        )}
      </Modal>
    </div>
  )
}
