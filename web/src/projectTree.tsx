import type { ReactNode } from 'react'
import { AppstoreOutlined, FolderOpenOutlined } from '@ant-design/icons'
import type { Workflow } from './types'

/**
 * 「项目结构」树：只展示当前项目，像资源管理器一样
 * 项目 → 多级文件夹 → 文件（文件就是工作流，点开进编辑器）。
 *
 * 项目列表是全局的：页头右上角的下拉框切换，不放进树里。
 */
export type ProjectTreeNode = {
  key: string
  title: ReactNode
  searchText?: string
  isLeaf?: boolean
  selectable?: boolean
  icon?: ReactNode
  children?: ProjectTreeNode[]
}

export type ProjectTreeData = { workflows: Workflow[]; folders: string[] }

/** 每个叶子都带 data-tree-key：拖拽落点按鼠标下的行解析，避免 antd 报错相邻节点。 */
export function treeLabel(key: string, title: ReactNode): ReactNode {
  return <span className="tree-node-label" data-tree-key={key}>{title}</span>
}

type DirNode = { children: Map<string, DirNode>; workflows: Workflow[] }

function dirTreeOf(data: ProjectTreeData): DirNode {
  const root: DirNode = { children: new Map(), workflows: [] }
  const ensure = (path: string): DirNode => {
    let current = root
    let acc = ''
    for (const part of path.split('/').filter(Boolean)) {
      acc = acc ? `${acc}/${part}` : part
      if (!current.children.has(part)) {
        current.children.set(part, { children: new Map(), workflows: [] })
      }
      current = current.children.get(part) as DirNode
    }
    return current
  }
  data.folders.forEach(ensure)
  data.workflows.forEach((item) => {
    const folder = (item.folder || '').trim()
    if (!folder) root.workflows.push(item)
    else ensure(folder).workflows.push(item)
  })
  return root
}

/** 项目下全部文件夹路径（含从文件 folder 推导出来的中间层级）。 */
export function projectDirPaths(data: ProjectTreeData): string[] {
  const keys = new Set<string>()
  const add = (path: string) => {
    const parts = path.split('/').filter(Boolean)
    parts.forEach((_, index) => keys.add(parts.slice(0, index + 1).join('/')))
  }
  data.folders.forEach(add)
  data.workflows.forEach((item) => { if (item.folder) add(item.folder) })
  return Array.from(keys)
}

function emptyHint(key: string, text: string): ProjectTreeNode {
  return {
    key,
    title: <span style={{ fontSize: 12, color: '#999' }}>{text}</span>,
    isLeaf: true,
    selectable: false,
  }
}

/**
 * 生成当前项目的树节点：项目用应用图标（和文件夹区分开），
 * 下面多级文件夹 + 文件，空项目给一句「右键新建」的提示。
 */
export function buildProjectNode(opts: {
  key: string
  title: ReactNode
  searchText?: string
  data: ProjectTreeData
  /** 文件夹 key：同一项目里同名路径唯一 */
  dirKey: (path: string) => string
  /** 文件节点由页面自己画（数据开发页是叶子，编辑器里当前文件要展开脚本） */
  workflowNode: (workflow: Workflow) => ProjectTreeNode
}): ProjectTreeNode {
  const { key, title, data, dirKey, workflowNode } = opts
  const convert = (dir: DirNode, prefix: string): ProjectTreeNode[] => {
    const dirs = Array.from(dir.children.entries())
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([name, child]) => {
        const path = prefix ? `${prefix}/${name}` : name
        const dirK = dirKey(path)
        return {
          key: dirK,
          title: treeLabel(dirK, name),
          searchText: name,
          icon: <FolderOpenOutlined style={{ color: '#d48806' }} />,
          children: convert(child, path),
        }
      })
    const files = [...dir.workflows]
      .sort((a, b) => a.name.localeCompare(b.name))
      .map(workflowNode)
    return [...dirs, ...files]
  }
  const children = convert(dirTreeOf(data), '')
  return {
    key,
    title: treeLabel(key, title),
    searchText: opts.searchText,
    // 项目图标和文件夹图标要能一眼区分：项目是蓝色应用图标，文件夹是黄色文件夹
    icon: <AppstoreOutlined style={{ color: 'var(--dw-primary, #1677ff)' }} />,
    selectable: true,
    children: children.length > 0
      ? children
      : [emptyHint(`${key}:empty`, '右键这里新建文件夹或文件')],
  }
}
