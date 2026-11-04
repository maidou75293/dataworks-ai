import type { Edge, Model, ScriptFile, Task } from './types'

/**
 * 工作流模型的三方合并（base = 上次保存的版本，local = 当前画布，remote = 服务器最新）。
 *
 * 规则和 Git 一致：只有一方改过的地方自动采用那一方；两边改到同一处才算冲突，
 * 冲突默认保留本地，由用户逐个选择。位置（x/y）冲突不打扰用户，一律取本地。
 */

export type MergeConflictKind = 'task' | 'file' | 'edge' | 'folder'

export type MergeConflict = {
  key: string
  kind: MergeConflictKind
  /** 给用户看的条目名，如「任务 数据同步」「脚本 docs/a.sql」 */
  label: string
  /** 冲突说明，如「名称不同（你「A」/ 对方「B」）」 */
  detail: string
  /** 「保留我的」时的取值；undefined 表示删除 */
  localValue?: unknown
  /** 「用对方的」时的取值；undefined 表示删除 */
  remoteValue?: unknown
  /** 文件冲突时，本地默认结果占用的路径（应用「用对方的」时要先摘掉） */
  localPath?: string
}

export type MergeStats = {
  tasks: number
  files: number
  edges: number
  folders: number
}

export type MergeResult = {
  /** 冲突全部按「保留我的」处理后的模型 */
  model: Model
  conflicts: MergeConflict[]
  /** 从服务器带进本地、本地原本没有的改动数量 */
  autoMerged: MergeStats
}

export type MergeChoices = Record<string, 'local' | 'remote'>

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
const same = (a: unknown, b: unknown) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
const lineCount = (text: unknown) => String(text ?? '').split('\n').length

type FieldResult = { value: unknown; conflict: boolean }

/** 三方字段合并：没动过的取另一边，两边一样直接采用，两边改动不同才算冲突。 */
function mergeField(hasBase: boolean, base: unknown, local: unknown, remote: unknown): FieldResult {
  if (same(local, remote)) return { value: local, conflict: false }
  if (!hasBase) {
    // base 里没有这个字段：只有一方新增时直接采用，不能算冲突
    if (local === undefined) return { value: remote, conflict: false }
    if (remote === undefined) return { value: local, conflict: false }
    return { value: local, conflict: true }
  }
  if (same(local, base)) return { value: remote, conflict: false }
  if (same(remote, base)) return { value: local, conflict: false }
  return { value: local, conflict: true }
}

const TASK_FIELD_LABELS: Record<string, string> = {
  name: '名称',
  type: '类型',
  script: '脚本',
  datasource: '数据源',
  datasourceId: '数据源',
  ruleCode: '质量规则',
  retries: '重试次数',
}

function taskFieldDetail(key: string, local: unknown, remote: unknown): string {
  const label = TASK_FIELD_LABELS[key] || key
  if (key === 'script') return `脚本内容不同（你 ${lineCount(local)} 行 / 对方 ${lineCount(remote)} 行）`
  if (key === 'ruleCode') return `质量规则不同（你「${local || '未设置'}」/ 对方「${remote || '未设置'}」）`
  return `${label}不同（你「${local ?? ''}」/ 对方「${remote ?? ''}」）`
}

function mergeTasks(
  base: Task[], local: Task[], remote: Task[], conflicts: MergeConflict[],
): Task[] {
  const b = new Map(base.map((t) => [t.code, t]))
  const l = new Map(local.map((t) => [t.code, t]))
  const r = new Map(remote.map((t) => [t.code, t]))
  const out: Task[] = []

  for (const code of new Set([...b.keys(), ...l.keys(), ...r.keys()])) {
    const bt = b.get(code)
    const lt = l.get(code)
    const rt = r.get(code)
    const label = `任务「${(lt || rt || bt)?.name || code}」`

    if (!lt && !rt) continue
    if (lt && !rt) {
      // 对方删除
      if (!bt) { out.push(clone(lt)); continue }
      if (same(lt, bt)) continue
      conflicts.push({
        key: `task:${code}`, kind: 'task', label,
        detail: '对方删除了这个节点，你改过它',
        localValue: clone(lt), remoteValue: undefined,
      })
      out.push(clone(lt))
      continue
    }
    if (!lt && rt) {
      // 本地删除
      if (!bt) { out.push(clone(rt)); continue }
      if (same(rt, bt)) continue
      conflicts.push({
        key: `task:${code}`, kind: 'task', label,
        detail: '你删除或重命名了这个节点，对方改过它',
        localValue: undefined, remoteValue: clone(rt),
      })
      continue
    }

    // 两边都有：逐字段合并
    const merged = clone(lt as Task)
    const conflicted: string[] = []
    const keys = new Set([
      ...Object.keys(bt || {}), ...Object.keys(lt as Task), ...Object.keys(rt as Task),
    ])
    for (const key of keys) {
      if (key === 'code') continue
      const hasBase = Boolean(bt) && Object.prototype.hasOwnProperty.call(bt, key)
      const result = mergeField(
        hasBase,
        bt ? (bt as unknown as Record<string, unknown>)[key] : undefined,
        (lt as unknown as Record<string, unknown>)[key],
        (rt as unknown as Record<string, unknown>)[key],
      )
      if (!result.conflict) {
        ;(merged as unknown as Record<string, unknown>)[key] = result.value
        continue
      }
      // 坐标冲突不值得打断用户：人在画布前，以本地位置为准
      if (key === 'x' || key === 'y') continue
      conflicted.push(key)
    }

    if (conflicted.length === 0) {
      out.push(merged)
      continue
    }
    const localVariant = clone(merged)
    const remoteVariant = clone(merged)
    for (const key of conflicted) {
      ;(remoteVariant as unknown as Record<string, unknown>)[key] = (rt as unknown as Record<string, unknown>)[key]
    }
    conflicts.push({
      key: `task:${code}`,
      kind: 'task',
      label,
      detail: conflicted
        .map((key) => taskFieldDetail(
          key,
          (lt as unknown as Record<string, unknown>)[key],
          (rt as unknown as Record<string, unknown>)[key],
        ))
        .join('；'),
      localValue: localVariant,
      remoteValue: remoteVariant,
    })
    out.push(localVariant)
  }
  return out
}

type FileEntry = { path: string; file: ScriptFile }

/** 内容完全一致的「删 + 增」视为重命名，避免并发改名后出现两份副本。 */
function fileView(base: ScriptFile[], side: ScriptFile[]) {
  const baseMap = new Map(base.map((f) => [f.path, f]))
  const sideMap = new Map(side.map((f) => [f.path, f]))
  const added = Array.from(sideMap.keys()).filter((path) => !baseMap.has(path)).sort()
  const used = new Set<string>()
  const entries = new Map<string, FileEntry | null>()

  for (const basePath of Array.from(baseMap.keys()).sort()) {
    if (sideMap.has(basePath)) {
      entries.set(basePath, { path: basePath, file: sideMap.get(basePath) as ScriptFile })
      continue
    }
    const before = baseMap.get(basePath) as ScriptFile
    let renamed: string | undefined
    if ((before.content || '').trim()) {
      renamed = added.find((path) => !used.has(path)
        && (sideMap.get(path)?.content || '') === (before.content || '')
        && (sideMap.get(path)?.language || '') === (before.language || ''))
    }
    if (renamed) {
      used.add(renamed)
      entries.set(basePath, { path: renamed, file: sideMap.get(renamed) as ScriptFile })
    } else {
      entries.set(basePath, null)
    }
  }

  const extras = Array.from(sideMap.keys())
    .filter((path) => !baseMap.has(path) && !used.has(path))
    .map((path) => ({ path, file: sideMap.get(path) as ScriptFile }))
  return { entries, extras }
}

function mergeFiles(
  base: ScriptFile[], local: ScriptFile[], remote: ScriptFile[], conflicts: MergeConflict[],
): ScriptFile[] {
  const baseMap = new Map(base.map((f) => [f.path, f]))
  const localView = fileView(base, local)
  const remoteView = fileView(base, remote)
  const out = new Map<string, ScriptFile>()

  for (const basePath of baseMap.keys()) {
    const before = baseMap.get(basePath) as ScriptFile
    const le = localView.entries.get(basePath) || null
    const re = remoteView.entries.get(basePath) || null
    const label = `脚本 ${basePath}`

    if (!le && !re) continue
    if (!le && re) {
      // 本地删除或改名，对方保留原路径且没改内容 → 删除生效
      if (re.path === basePath && same(re.file, before)) continue
      conflicts.push({
        key: `file:${basePath}`, kind: 'file', label,
        detail: re.path === basePath
          ? '你删除或重命名了它，对方修改了内容'
          : '你删除或重命名了它，对方也给它改了名',
        localValue: undefined, remoteValue: clone(re),
      })
      continue
    }
    if (le && !re) {
      // 对方删除或改名，本地保留原路径且没改内容 → 删除生效
      if (le.path === basePath && same(le.file, before)) continue
      conflicts.push({
        key: `file:${basePath}`, kind: 'file', label,
        detail: le.path === basePath
          ? '对方删除或重命名了它，你修改了内容'
          : '对方删除或重命名了它，你也给它改了名',
        localValue: clone(le), remoteValue: undefined, localPath: le.path,
      })
      out.set(le.path, clone(le.file))
      continue
    }

    const content = mergeField(true, before.content, le?.file.content, re?.file.content)
    const language = mergeField(true, before.language, le?.file.language, re?.file.language)
    let path = (le as FileEntry).path
    let pathConflict = false
    if (le!.path !== re!.path) {
      if (le!.path === basePath) path = re!.path
      else if (re!.path === basePath) path = le!.path
      else pathConflict = true
    }
    const conflictFields: string[] = []
    if (content.conflict) conflictFields.push('内容')
    if (language.conflict) conflictFields.push('语言')
    if (pathConflict) conflictFields.push('新路径')

    if (conflictFields.length === 0) {
      const mergedFile: ScriptFile = {
        path,
        content: content.value as string,
        language: language.value as ScriptFile['language'],
      }
      // 两个不同基础文件可能被双方改名到同一路径（a→x、b→x）。
      // 直接 out.set 会静默覆盖，且 conflicts 为空，用户会以为自动合并成功。
      const existing = out.get(path)
      if (existing && !same(existing, mergedFile)) {
        conflicts.push({
          key: `file-collision:${basePath}:${path}`,
          kind: 'file',
          label: `脚本 ${path}`,
          detail: `两个不同来源的脚本被改名到同一路径「${path}」，只能保留一个；请选择或先改名`,
          localValue: { path, file: clone(existing) },
          remoteValue: { path, file: clone(mergedFile) },
          localPath: path,
        })
        continue
      }
      out.set(path, mergedFile)
      continue
    }

    const localVariant: FileEntry = {
      path: le!.path,
      file: {
        ...clone(le!.file),
        ...(language.conflict ? {} : { language: language.value as ScriptFile['language'] }),
      },
    }
    const remoteVariant: FileEntry = {
      path: re!.path,
      file: {
        ...clone(re!.file),
        ...(language.conflict ? {} : { language: language.value as ScriptFile['language'] }),
      },
    }
    const details: string[] = []
    if (content.conflict) {
      details.push(`内容不同（你 ${lineCount(le!.file.content)} 行 / 对方 ${lineCount(re!.file.content)} 行）`)
    }
    if (language.conflict) details.push('语言不同')
    if (pathConflict) details.push(`新路径不同（你「${le!.path}」/ 对方「${re!.path}」）`)
    conflicts.push({
      key: `file:${basePath}`, kind: 'file', label,
      detail: details.join('；'),
      localValue: localVariant, remoteValue: remoteVariant, localPath: localVariant.path,
    })
    out.set(localVariant.path, localVariant.file)
  }

  // 两边新增的脚本
  const localExtras = new Map(localView.extras.map((e) => [e.path, e]))
  const remoteExtras = new Map(remoteView.extras.map((e) => [e.path, e]))
  for (const path of new Set([...localExtras.keys(), ...remoteExtras.keys()])) {
    const le = localExtras.get(path)
    const re = remoteExtras.get(path)
    if (le && re) {
      if (same(le.file, re.file)) {
        out.set(path, clone(le.file))
        continue
      }
      conflicts.push({
        key: `file+${path}`, kind: 'file', label: `脚本 ${path}`,
        detail: '你们各自新增了同名脚本，内容不同',
        localValue: clone(le), remoteValue: clone(re), localPath: path,
      })
      out.set(path, clone(le.file))
      continue
    }
    const only = (le || re) as FileEntry
    const existing = out.get(path)
    if (existing && !same(existing, only.file)) {
      // 本地把 a 改名成 b，对方同时新增了 b：保留本地改名结果，但必须让用户看见冲突
      conflicts.push({
        key: `file-collision:${path}`,
        kind: 'file',
        label: `脚本 ${path}`,
        detail: `本地改名/新增与对方新增落到同一路径「${path}」，只能保留一个`,
        localValue: { path, file: clone(existing) },
        remoteValue: clone(only),
        localPath: path,
      })
      continue
    }
    out.set(path, clone(only.file))
  }

  return Array.from(out.values())
}

/** 连线和目录没有内容，只有「在/不在」：一方删除即删除，两边新增则保留一份。 */
function mergePresence(base: string[], local: string[], remote: string[]): string[] {
  const b = new Set(base)
  const l = new Set(local)
  const r = new Set(remote)
  const out: string[] = []
  for (const key of new Set([...b, ...l, ...r])) {
    const include = b.has(key) ? (l.has(key) && r.has(key)) : (l.has(key) || r.has(key))
    if (include) out.push(key)
  }
  return out
}

const edgeKey = (edge: Edge) => `${edge.from}->${edge.to}`

function mergeEdges(base: Edge[], local: Edge[], remote: Edge[]): Edge[] {
  const byKey = new Map<string, Edge>()
  ;[...local, ...remote].forEach((edge) => byKey.set(edgeKey(edge), edge))
  const keys = mergePresence(base.map(edgeKey), local.map(edgeKey), remote.map(edgeKey))
  return keys.map((key) => clone(byKey.get(key) as Edge)).filter(Boolean)
}

function countStats(local: Model, merged: Model): MergeStats {
  const taskMap = (model: Model) => new Map((model.tasks || []).map((t) => [t.code, JSON.stringify(t)]))
  const fileMap = (model: Model) => new Map((model.files || []).map((f) => [f.path, JSON.stringify(f)]))
  let tasks = 0
  const lm = taskMap(local)
  const mm = taskMap(merged)
  for (const [code, value] of mm) if (lm.get(code) !== value) tasks += 1
  for (const code of lm.keys()) if (!mm.has(code)) tasks += 1

  let files = 0
  const lf = fileMap(local)
  const mf = fileMap(merged)
  for (const [path, value] of mf) if (lf.get(path) !== value) files += 1
  for (const path of lf.keys()) if (!mf.has(path)) files += 1

  const edgeSet = (model: Model) => new Set((model.edges || []).map(edgeKey))
  const le = edgeSet(local)
  const me = edgeSet(merged)
  let edges = 0
  for (const key of me) if (!le.has(key)) edges += 1
  for (const key of le) if (!me.has(key)) edges += 1

  const folderSet = (model: Model) => new Set(model.folders || [])
  const lfd = folderSet(local)
  const mfd = folderSet(merged)
  let folders = 0
  for (const path of mfd) if (!lfd.has(path)) folders += 1
  for (const path of lfd) if (!mfd.has(path)) folders += 1

  return { tasks, files, edges, folders }
}

/** 文件级上游依赖：一方没改就取另一方，两边都改了取并集。 */
function mergeUpstreams(base: number[], local: number[], remote: number[]): number[] {
  const same = (a: number[], b: number[]) => a.length === b.length && a.every((v, i) => v === b[i])
  if (same(local, base)) return remote
  if (same(remote, base)) return local
  return Array.from(new Set([...local, ...remote]))
}

export function mergeModels(base: Model, local: Model, remote: Model): MergeResult {
  const conflicts: MergeConflict[] = []
  const model: Model = {
    tasks: mergeTasks(base.tasks || [], local.tasks || [], remote.tasks || [], conflicts),
    edges: mergeEdges(base.edges || [], local.edges || [], remote.edges || []),
    files: mergeFiles(base.files || [], local.files || [], remote.files || [], conflicts),
    folders: mergePresence(base.folders || [], local.folders || [], remote.folders || []),
    upstreams: mergeUpstreams(base.upstreams || [], local.upstreams || [], remote.upstreams || []),
  }
  return { model, conflicts, autoMerged: countStats(local, model) }
}

/** 按用户逐项选择，把「保留我的」结果里对应的条目换成对方的。 */
export function resolveMerged(result: MergeResult, choices: MergeChoices): Model {
  const model = clone(result.model)
  for (const conflict of result.conflicts) {
    if (choices[conflict.key] !== 'remote') continue
    if (conflict.kind === 'task') {
      const code = conflict.key.slice('task:'.length)
      model.tasks = (model.tasks || []).filter((task) => task.code !== code)
      if (conflict.remoteValue) model.tasks.push(clone(conflict.remoteValue as Task))
    } else if (conflict.kind === 'file') {
      const path = conflict.localPath
      if (path) model.files = (model.files || []).filter((file) => file.path !== path)
      if (conflict.remoteValue) {
        const entry = conflict.remoteValue as FileEntry
        model.files = (model.files || []).filter((file) => file.path !== entry.path)
        model.files.push({ ...clone(entry.file), path: entry.path })
      }
    }
  }
  return model
}
