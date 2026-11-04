import { api } from './api'

/** Doris 表/字段目录，用于 SQL 自动补全。 */
export type SqlColumn = { name: string; type?: string }
export type SqlTable = { database: string; table: string; columns: SqlColumn[] }

/** 项目已发布的 UDF（函数管理页注册，名称带 p{projectId}_ 前缀）。 */
export type SqlFunction = {
  fullName: string
  engine?: string
  returnType?: string
  argTypes?: string
}

export type Suggestion = {
  label: string
  insert: string
  kind: 'table' | 'column' | 'keyword' | 'function'
  detail?: string
}

// 关键字要覆盖 DDL/DML：流开发的脚本是 CREATE TABLE ... INSERT INTO ...，
// 之前只放了查询词，打了 CREATE 一个提示都不出，用户只能靠点「.」补字段。
const KEYWORDS = [
  'select', 'from', 'where', 'group by', 'order by', 'having', 'limit', 'offset',
  'join', 'left join', 'right join', 'inner join', 'full join', 'on', 'as', 'and', 'or',
  'not', 'null', 'is null', 'is not null', 'in', 'exists', 'between', 'like',
  'asc', 'desc', 'distinct', 'union all', 'union', 'with', 'case', 'when', 'then', 'else', 'end',
  'cast', 'interval', 'over', 'partition by', 'rows between', 'current_date', 'current_timestamp',
  // DDL / DML
  'create table', 'create temporary table', 'create view', 'create database', 'drop table',
  'alter table', 'insert into', 'insert overwrite', 'delete from', 'update', 'set',
  'primary key', 'not enforced', 'watermark for', 'as', 'comment',
  // Flink 流语义
  'tumble', 'hop', 'cumulate', 'session window', 'rowtime', 'proctime', 'interval',
  'emit changes', 'match_recognize', 'explain', 'use catalog', 'use database',
]

const FUNCTIONS = [
  'count', 'sum', 'avg', 'min', 'max', 'round', 'abs', 'ceil', 'floor',
  'concat', 'substring', 'substr', 'length', 'trim', 'lower', 'upper', 'replace',
  'now', 'date_trunc', 'date_format', 'datediff', 'to_date', 'year', 'month', 'day',
  'coalesce', 'ifnull', 'nullif', 'row_number', 'rank', 'dense_rank', 'lag', 'lead',
  // Flink 时间函数：流开发脚本里几乎都要用
  'to_timestamp', 'to_date', 'current_timestamp', 'unix_timestamp', 'from_unixtime',
  'tumble_start', 'tumble_end', 'hop_start', 'hop_end', 'date_add', 'date_sub',
]

// 缓存按项目隔离：不同项目可能连不同的 Doris 实例，共用缓存会给出错的表名。
const caches = new Map<string, { at: number; tables: SqlTable[] }>()
const inflights = new Map<string, Promise<SqlTable[]>>()
// UDF 与表目录同一次请求返回，缓存在一起（函数名按项目隔离）
const functionCaches = new Map<string, SqlFunction[]>()

function cacheKey(projectId?: number) {
  return projectId == null ? 'builtin' : String(projectId)
}

/**
 * 取一次元数据并缓存 5 分钟；失败就退化成只提示关键字，不打扰用户。
 * 失败/空结果只缓存 30 秒：Doris 元数据偶尔慢半拍，不能让一次失败把补全封 5 分钟。
 * projectId 为空 = 平台内置 Doris。
 */
export function loadSqlTables(projectId?: number): Promise<SqlTable[]> {
  const key = cacheKey(projectId)
  const cache = caches.get(key) || null
  const ttl = cache && cache.tables.length === 0 ? 30_000 : 5 * 60_000
  if (cache && Date.now() - cache.at < ttl) return Promise.resolve(cache.tables)
  const inflight = inflights.get(key)
  if (inflight) return inflight
  const path = projectId == null
    ? '/api/query/tables'
    : `/api/query/tables?projectId=${encodeURIComponent(projectId)}`
  const pending = api<{ tables?: SqlTable[]; functions?: SqlFunction[] }>(path)
    .then((res) => {
      const fresh = { at: Date.now(), tables: (res?.tables || []).map((t) => ({
        database: t.database,
        table: t.table,
        columns: t.columns || [],
      })) }
      caches.set(key, fresh)
      functionCaches.set(key, res?.functions || [])
      return fresh.tables
    })
    .catch(() => {
      // 元数据不可用时 30 秒后再试，避免每次按键都打接口
      caches.set(key, { at: Date.now(), tables: [] })
      return [] as SqlTable[]
    })
    .finally(() => { inflights.delete(key) })
  inflights.set(key, pending)
  return pending
}

/** 补全用的已发布 UDF；须在 loadSqlTables 之后调用（同一次请求带回）。 */
export function sqlFunctionsFor(projectId?: number): SqlFunction[] {
  return functionCaches.get(cacheKey(projectId)) || []
}

const RESERVED_AFTER_ALIAS = new Set([
  'where', 'on', 'left', 'right', 'inner', 'full', 'join', 'group', 'order', 'having',
  'limit', 'union', 'as', 'using', 'cross',
])

/** 从 SQL 里粗略解析「表 / 别名」，够补全用即可。 */
function referencedTables(sql: string) {
  const map = new Map<string, string>() // 别名或表名 → 完整表名
  const re = /\b(?:from|join)\s+([A-Za-z0-9_$.]+)(?:\s+(?:as\s+)?([A-Za-z0-9_$]+))?/gi
  let match: RegExpExecArray | null
  while ((match = re.exec(sql)) !== null) {
    const full = match[1].replace(/[`"]/g, '')
    const alias = match[2] && !RESERVED_AFTER_ALIAS.has(match[2].toLowerCase()) ? match[2] : ''
    const bare = full.split('.').pop() || full
    map.set(full.toLowerCase(), full)
    map.set(bare.toLowerCase(), full)
    if (alias) map.set(alias.toLowerCase(), full)
  }
  return map
}

function findTable(full: string, tables: SqlTable[]): SqlTable | undefined {
  const target = full.toLowerCase()
  return tables.find((t) => `${t.database}.${t.table}`.toLowerCase() === target)
    || tables.find((t) => t.table.toLowerCase() === target)
}

/** 光标是否在字符串或注释里——是的话不弹补全。 */
function inStringOrComment(text: string, caret: number): boolean {
  const lineStart = text.lastIndexOf('\n', caret - 1) + 1
  const line = text.slice(lineStart, caret)
  if (line.includes('--')) return true
  const blockOpen = text.lastIndexOf('/*')
  const blockClose = text.lastIndexOf('*/')
  if (blockOpen > blockClose) return true
  let quotes = 0
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i]
    if (ch === "'") {
      if (line[i + 1] === "'") { i += 1; continue }
      quotes += 1
    }
  }
  return quotes % 2 === 1
}

export type SuggestResult = {
  items: Suggestion[]
  /** 用 insert 替换 [start, end) */
  start: number
  end: number
}

/**
 * 根据光标前的文本给出补全项：
 * - `别名.` / `表名.` → 该表字段
 * - `库名.` → 该库的表
 * - 其它 → FROM/JOIN 里出现的表字段 + 所有表 + 关键字/函数
 */
export function suggestFor(rawText: string, caret: number, tables: SqlTable[],
                           functions: SqlFunction[] = []): SuggestResult | null {
  if (caret < 0 || caret > rawText.length) return null
  const before = rawText.slice(0, caret)
  const wordMatch = before.match(/[A-Za-z0-9_$.]*$/)
  const word = wordMatch ? wordMatch[0] : ''
  // 刚打完 FROM / JOIN 空格时，直接列表名
  const wantsTable = /\b(from|join)\s*$/i.test(before)
  // 刚打完 SELECT / WHERE / AND / ON / 逗号空格时，直接列字段；没有引用表就不打扰
  const wantsColumn = /\b(select|where|and|or|on|by|having)\s+$/i.test(before)
    || /,\s*$/.test(before)
  if (!word && !wantsTable && !wantsColumn) return null
  if (inStringOrComment(rawText, caret)) return null
  if (!word && wantsTable) {
    const items = tables.slice(0, 40).map<Suggestion>((t) => ({
      label: `${t.database}.${t.table}`,
      insert: `${t.database}.${t.table}`,
      kind: 'table',
      detail: `${t.columns.length} 个字段`,
    }))
    return items.length ? { items, start: caret, end: caret } : null
  }

  const dot = word.lastIndexOf('.')
  const qualifier = dot >= 0 ? word.slice(0, dot) : ''
  const prefix = dot >= 0 ? word.slice(dot + 1) : word
  const lowerPrefix = prefix.toLowerCase()
  const start = caret - prefix.length
  const items: Suggestion[] = []
  // 已经完整打出来的词不再提示它自己：否则字段名写完、面板还挂在下一行上挡着继续写
  const exact = (item: Suggestion) => lowerPrefix.length > 0
    && (item.insert.toLowerCase() === lowerPrefix || item.label.toLowerCase() === lowerPrefix)

  if (!word && wantsColumn) {
    // 光标刚落在 SELECT / WHERE 后面的空位：把已引用表的字段直接列出来
    const refs = referencedTables(rawText)
    const seen = new Set<string>()
    for (const full of new Set(refs.values())) {
      const table = findTable(full, tables)
      if (!table) continue
      for (const c of table.columns) {
        const key = c.name.toLowerCase()
        if (seen.has(key)) continue
        seen.add(key)
        items.push({
          label: c.name, insert: c.name, kind: 'column', detail: `${table.table}.${c.name}`,
        })
      }
    }
    return items.length ? { items: items.slice(0, 40), start: caret, end: caret } : null
  }

  if (qualifier && !qualifier.includes('.')) {
    // 别名 / 表名 → 字段
    const refs = referencedTables(rawText)
    const resolved = refs.get(qualifier.toLowerCase()) || qualifier
    const table = findTable(resolved, tables)
    if (table) {
      table.columns
        .filter((c) => c.name.toLowerCase().startsWith(lowerPrefix))
        .slice(0, 40)
        .forEach((c) => items.push({
          label: c.name, insert: c.name, kind: 'column', detail: c.type,
        }))
      const kept = items.filter((item) => !exact(item))
      return kept.length ? { items: kept, start, end: caret } : null
    }
    // 库名 → 该库的表
    const dbTables = tables.filter((t) => t.database.toLowerCase() === qualifier.toLowerCase())
    if (dbTables.length > 0) {
      dbTables
        .filter((t) => t.table.toLowerCase().startsWith(lowerPrefix))
        .slice(0, 40)
        .forEach((t) => items.push({
          label: t.table, insert: t.table, kind: 'table', detail: `${t.columns.length} 个字段`,
        }))
      const kept = items.filter((item) => !exact(item))
      return kept.length ? { items: kept, start, end: caret } : null
    }
    return null
  }

  const refs = referencedTables(rawText)
  const seen = new Set<string>()
  const push = (item: Suggestion) => {
    const key = `${item.kind}:${item.insert}`
    if (seen.has(key)) return
    seen.add(key)
    items.push(item)
  }

  // 1. 当前查询里已经出现的表的字段优先
  for (const full of new Set(refs.values())) {
    const table = findTable(full, tables)
    if (!table) continue
    table.columns
      .filter((c) => c.name.toLowerCase().startsWith(lowerPrefix))
      .forEach((c) => push({
        label: c.name, insert: c.name, kind: 'column', detail: `${table.table}.${c.name}`,
      }))
  }
  // 2. 所有表
  tables
    .filter((t) => t.table.toLowerCase().startsWith(lowerPrefix)
      || `${t.database}.${t.table}`.toLowerCase().startsWith(lowerPrefix))
    .slice(0, 30)
    .forEach((t) => push({
      label: `${t.database}.${t.table}`,
      insert: `${t.database}.${t.table}`,
      kind: 'table',
      detail: `${t.columns.length} 个字段`,
    }))
  // 3. 项目注册的 UDF 排在通用函数前面：它们才是这个项目特有的
  functions
    .filter((f) => f.fullName.toLowerCase().startsWith(lowerPrefix))
    .slice(0, 20)
    .forEach((f) => push({
      label: f.fullName,
      insert: `${f.fullName}(`,
      kind: 'function',
      detail: `自定义函数${f.returnType ? ` · 返回 ${f.returnType}` : ''}${f.engine ? ` · ${f.engine}` : ''}`,
    }))
  // 4. 关键字与内置函数
  KEYWORDS.filter((k) => k.startsWith(lowerPrefix)).forEach((k) => push({
    label: k, insert: k, kind: 'keyword',
  }))
  FUNCTIONS.filter((f) => f.startsWith(lowerPrefix)).forEach((f) => push({
    label: f, insert: `${f}(`, kind: 'function', detail: '函数',
  }))

  if (items.length === 0 && lowerPrefix.length >= 2) {
    // 前缀没命中时退一步做包含匹配，找得到也能用
    tables
      .filter((t) => `${t.database}.${t.table}`.toLowerCase().includes(lowerPrefix))
      .slice(0, 20)
      .forEach((t) => push({
        label: `${t.database}.${t.table}`,
        insert: `${t.database}.${t.table}`,
        kind: 'table',
        detail: `${t.columns.length} 个字段`,
      }))
  }

  const kept = items.filter((item) => !exact(item))
  return kept.length ? { items: kept.slice(0, 50), start, end: caret } : null
}

/** 光标在 textarea 中的像素坐标，用于把补全面板贴到光标下方。 */
export function caretPosition(el: HTMLTextAreaElement, position: number): { top: number; left: number } {
  const style = window.getComputedStyle(el)
  const mirror = document.createElement('div')
  const props = [
    'fontFamily', 'fontSize', 'fontWeight', 'lineHeight', 'letterSpacing', 'paddingTop',
    'paddingRight', 'paddingBottom', 'paddingLeft', 'borderTopWidth', 'borderLeftWidth',
    'boxSizing', 'whiteSpace', 'tabSize', 'textTransform', 'wordSpacing',
  ] as const
  props.forEach((prop) => { mirror.style[prop] = style[prop] })
  mirror.style.position = 'absolute'
  mirror.style.visibility = 'hidden'
  mirror.style.top = '-9999px'
  mirror.style.left = '0'
  mirror.style.width = `${el.clientWidth}px`
  mirror.style.overflow = 'hidden'
  mirror.textContent = el.value.slice(0, position)
  const marker = document.createElement('span')
  marker.textContent = '​'
  mirror.appendChild(marker)
  document.body.appendChild(mirror)
  const top = marker.offsetTop - el.scrollTop
  const left = marker.offsetLeft - el.scrollLeft
  document.body.removeChild(mirror)
  return { top, left }
}
