import type { Model, ScriptFile, Task } from './types'

export type LineChange = { type: 'same' | 'add' | 'del'; text: string }
export type FieldChange = { field: string; label: string; before: string; after: string }

export type NodeChange = {
  code: string
  name: string
  kind: 'added' | 'removed' | 'changed'
  fields: FieldChange[]
  /** 节点 SQL / Shell / Python 的行级差异 */
  scriptDiff?: LineChange[]
}

export type FileChange = {
  path: string
  /** 重命名前的路径，仅 kind = renamed 时有值 */
  from?: string
  kind: 'added' | 'removed' | 'changed' | 'renamed'
  fields: FieldChange[]
  lines?: LineChange[]
}

export type EdgeChange = { from: string; to: string; kind: 'added' | 'removed' }
export type FolderChange = {
  path: string
  /** 重命名前的路径，仅 kind = renamed 时有值 */
  from?: string
  kind: 'added' | 'removed' | 'renamed'
}

export type DiffSummary = {
  nodesAdded: number
  nodesRemoved: number
  nodesChanged: number
  edgesAdded: number
  edgesRemoved: number
  filesAdded: number
  filesRemoved: number
  filesChanged: number
  filesRenamed: number
  foldersAdded: number
  foldersRemoved: number
  foldersRenamed: number
  total: number
}

export type ModelDiff = {
  nodes: NodeChange[]
  edges: EdgeChange[]
  files: FileChange[]
  folders: FolderChange[]
  summary: DiffSummary
}

const NODE_FIELDS: { key: keyof Task; label: string; fallback?: string }[] = [
  { key: 'name', label: '名称' },
  { key: 'type', label: '类型' },
  { key: 'retries', label: '重试次数' },
  { key: 'ruleCode', label: '质量规则' },
  { key: 'datasource', label: '数据源', fallback: 'DORIS' },
  { key: 'datasourceId', label: '数据源 ID' },
]

function text(value: unknown): string {
  return value === null || value === undefined ? '' : String(value)
}

function taskField(task: Task, key: keyof Task, fallback?: string): string {
  const value = task[key]
  if (fallback && (value === null || value === undefined || value === '')) return fallback
  return text(value)
}

/**
 * 行级差异（LCS）。超大文本直接按整段替换，避免 O(n*m) 拖死浏览器。
 */
export function diffLines(before = '', after = ''): LineChange[] {
  const a = before.split('\n')
  const b = after.split('\n')
  if (a.length * b.length > 250_000) {
    return [
      ...a.map((line) => ({ type: 'del' as const, text: line })),
      ...b.map((line) => ({ type: 'add' as const, text: line })),
    ]
  }
  const dp: number[][] = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j]
        ? dp[i + 1][j + 1] + 1
        : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: LineChange[] = []
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ type: 'del', text: a[i] })
      i++
    } else {
      out.push({ type: 'add', text: b[j] })
      j++
    }
  }
  while (i < a.length) out.push({ type: 'del', text: a[i++] })
  while (j < b.length) out.push({ type: 'add', text: b[j++] })
  return out
}

function normalizeModel(raw: string | Model | undefined): Model {
  if (!raw) return { tasks: [], edges: [] }
  if (typeof raw !== 'string') {
    return {
      tasks: raw.tasks || [],
      edges: raw.edges || [],
      files: raw.files || [],
      folders: raw.folders || [],
    }
  }
  try {
    const parsed = JSON.parse(raw) as Model
    return {
      tasks: parsed.tasks || [],
      edges: parsed.edges || [],
      files: parsed.files || [],
      folders: parsed.folders || [],
    }
  } catch {
    return { tasks: [], edges: [] }
  }
}

/** 以「版本 → 当前」为方向计算差异。 */
export function diffModels(fromRaw: string | Model | undefined, toRaw: string | Model | undefined): ModelDiff {
  const from = normalizeModel(fromRaw)
  const to = normalizeModel(toRaw)
  const fromTasks = new Map(from.tasks.map((task) => [task.code, task]))
  const toTasks = new Map(to.tasks.map((task) => [task.code, task]))
  const nodes: NodeChange[] = []

  const codes = new Set([...fromTasks.keys(), ...toTasks.keys()])
  for (const code of Array.from(codes).sort()) {
    const before = fromTasks.get(code)
    const after = toTasks.get(code)
    if (!before && after) {
      nodes.push({ code, name: after.name || code, kind: 'added', fields: [] })
      continue
    }
    if (before && !after) {
      nodes.push({ code, name: before.name || code, kind: 'removed', fields: [] })
      continue
    }
    if (!before || !after) continue
    const fields: FieldChange[] = []
    for (const field of NODE_FIELDS) {
      const left = taskField(before, field.key, field.fallback)
      const right = taskField(after, field.key, field.fallback)
      if (left !== right) {
        fields.push({ field: String(field.key), label: field.label, before: left || '-', after: right || '-' })
      }
    }
    const beforeScript = before.script || ''
    const afterScript = after.script || ''
    const scriptDiff = beforeScript !== afterScript
      ? diffLines(beforeScript, afterScript) : undefined
    if (fields.length > 0 || scriptDiff) {
      nodes.push({
        code,
        name: after.name || before.name || code,
        kind: 'changed',
        fields,
        scriptDiff,
      })
    }
  }

  const edgeKey = (edge: { from: string; to: string }) => `${edge.from}→${edge.to}`
  const fromEdges = new Map(from.edges.map((edge) => [edgeKey(edge), edge]))
  const toEdges = new Map(to.edges.map((edge) => [edgeKey(edge), edge]))
  const edges: EdgeChange[] = []
  for (const [key, edge] of toEdges) {
    if (!fromEdges.has(key)) edges.push({ from: edge.from, to: edge.to, kind: 'added' })
  }
  for (const [key, edge] of fromEdges) {
    if (!toEdges.has(key)) edges.push({ from: edge.from, to: edge.to, kind: 'removed' })
  }

  const fromFiles = new Map<string, ScriptFile>((from.files || []).map((file) => [file.path, file]))
  const toFiles = new Map<string, ScriptFile>((to.files || []).map((file) => [file.path, file]))
  const files: FileChange[] = []

  // 重命名配对：只有路径变了、内容和语言都没变，才认定为重命名。
  // 空文件不参与配对，否则任意两个新建空文件都会被误判成改名。
  const addedPaths = Array.from(toFiles.keys())
    .filter((path) => !fromFiles.has(path)).sort()
  const removedPaths = Array.from(fromFiles.keys())
    .filter((path) => !toFiles.has(path)).sort()
  const usedAdded = new Set<string>()
  const renamedFiles = new Map<string, string>() // 旧路径 → 新路径
  for (const oldPath of removedPaths) {
    const before = fromFiles.get(oldPath)!
    const content = before.content || ''
    if (!content.trim()) continue
    const match = addedPaths.find((path) => {
      if (usedAdded.has(path)) return false
      const after = toFiles.get(path)!
      return (after.content || '') === content
        && (after.language || '') === (before.language || '')
    })
    if (!match) continue
    usedAdded.add(match)
    renamedFiles.set(oldPath, match)
  }

  for (const [oldPath, newPath] of renamedFiles) {
    files.push({ path: newPath, from: oldPath, kind: 'renamed', fields: [] })
  }
  for (const path of addedPaths) {
    if (!usedAdded.has(path)) files.push({ path, kind: 'added', fields: [] })
  }
  for (const path of removedPaths) {
    if (!renamedFiles.has(path)) files.push({ path, kind: 'removed', fields: [] })
  }
  const paths = Array.from(fromFiles.keys()).filter((path) => toFiles.has(path)).sort()
  for (const path of paths) {
    const before = fromFiles.get(path)!
    const after = toFiles.get(path)!
    const fields: FieldChange[] = []
    if ((before.language || '') !== (after.language || '')) {
      fields.push({
        field: 'language',
        label: '语言',
        before: before.language || 'SQL',
        after: after.language || 'SQL',
      })
    }
    const beforeContent = before.content || ''
    const afterContent = after.content || ''
    if (beforeContent !== afterContent || fields.length > 0) {
      files.push({
        path,
        kind: 'changed',
        fields,
        lines: beforeContent !== afterContent
          ? diffLines(beforeContent, afterContent) : undefined,
      })
    }
  }
  files.sort((a, b) => a.path.localeCompare(b.path))

  const fromFolders = new Set(from.folders || [])
  const toFolders = new Set(to.folders || [])
  const addedFolders = Array.from(toFolders)
    .filter((path) => !fromFolders.has(path)).sort()
  const removedFolders = Array.from(fromFolders)
    .filter((path) => !toFolders.has(path)).sort()
  // 目录改名：目录下所有文件都以相同后缀搬到同一个新目录
  const renamedFolders = new Map<string, string>()
  const usedAddedFolder = new Set<string>()
  for (const oldFolder of removedFolders) {
    const under = Array.from(fromFiles.keys())
      .filter((path) => path.startsWith(oldFolder + '/'))
    if (under.length === 0) continue
    const firstNew = renamedFiles.get(under[0])
    if (!firstNew) continue
    const suffix = under[0].slice(oldFolder.length + 1)
    if (!firstNew.endsWith('/' + suffix)) continue
    const newFolder = firstNew.slice(0, firstNew.length - suffix.length - 1)
    if (!addedFolders.includes(newFolder) || usedAddedFolder.has(newFolder)) continue
    const moved = under.every((path) =>
      renamedFiles.get(path) === `${newFolder}/${path.slice(oldFolder.length + 1)}`)
    if (!moved) continue
    renamedFolders.set(oldFolder, newFolder)
    usedAddedFolder.add(newFolder)
  }
  const folders: FolderChange[] = [
    ...Array.from(renamedFolders).map(([oldPath, newPath]) => ({
      path: newPath, from: oldPath, kind: 'renamed' as const,
    })),
    ...addedFolders.filter((path) => !usedAddedFolder.has(path))
      .map((path) => ({ path, kind: 'added' as const })),
    ...removedFolders.filter((path) => !renamedFolders.has(path))
      .map((path) => ({ path, kind: 'removed' as const })),
  ]

  const summary: DiffSummary = {
    nodesAdded: nodes.filter((node) => node.kind === 'added').length,
    nodesRemoved: nodes.filter((node) => node.kind === 'removed').length,
    nodesChanged: nodes.filter((node) => node.kind === 'changed').length,
    edgesAdded: edges.filter((edge) => edge.kind === 'added').length,
    edgesRemoved: edges.filter((edge) => edge.kind === 'removed').length,
    filesAdded: files.filter((file) => file.kind === 'added').length,
    filesRemoved: files.filter((file) => file.kind === 'removed').length,
    filesChanged: files.filter((file) => file.kind === 'changed').length,
    filesRenamed: files.filter((file) => file.kind === 'renamed').length,
    foldersAdded: folders.filter((folder) => folder.kind === 'added').length,
    foldersRemoved: folders.filter((folder) => folder.kind === 'removed').length,
    foldersRenamed: folders.filter((folder) => folder.kind === 'renamed').length,
    total: nodes.length + edges.length + files.length + folders.length,
  }

  return { nodes, edges, files, folders, summary }
}
