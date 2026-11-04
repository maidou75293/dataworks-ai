import {
  useEffect, useRef, useState, type KeyboardEvent, type ReactNode, type UIEvent,
} from 'react'
import {
  caretPosition, loadSqlTables, sqlFunctionsFor, suggestFor, type SqlFunction, type SqlTable,
  type SuggestResult, type Suggestion,
} from '../sqlSuggest'

type CodeEditorProps = {
  value: string
  onChange?: (value: string) => void
  readOnly?: boolean
  placeholder?: string
  /** Ctrl/Cmd + S 触发；不传时只拦截浏览器默认保存。 */
  onSave?: () => void
  /** Ctrl/Cmd + Enter 触发，用于 SQL 控制台直接执行。 */
  onRun?: () => void
  label?: string
  /** 固定高度；用于查询页这类不适合撑满父容器的场景。 */
  height?: number
  /** SQL / PYTHON / SHELL / TEXT，决定语法高亮规则；不传则不高亮。 */
  language?: string
  /** SQL 编辑器是否启用表名/字段补全，默认启用。 */
  autocomplete?: boolean
  /** 补全元数据按项目隔离；不传 = 平台内置 Doris。 */
  projectId?: number
  /** 直接给补全用的表/字段目录（FlinkSQL 从脚本里的 CREATE TABLE 解析）。 */
  tables?: SqlTable[]
}

const KIND_LABEL: Record<Suggestion['kind'], string> = {
  table: '表',
  column: '字段',
  keyword: '关键字',
  function: '函数',
}

function suggestionKey(item: Suggestion) {
  return `${item.kind}:${item.insert.toLowerCase()}`
}

/**
 * 把关键字/函数兜底并进 suggestFor 的结果：
 * - 有表/字段结果时按同一个前缀过滤后追加；
 * - Ctrl+Space 手动唤出且当前没有可补内容时，给完整的关键字/函数清单；
 * - 按 kind + insert 去重，最后统一截断到 MAX_SUGGESTIONS。
 */
function withBuiltins(
  result: SuggestResult | null,
  text: string,
  caret: number,
  manual: boolean,
): SuggestResult | null {
  if (!result && !manual) return null
  const fallbackWord = (text.slice(0, caret).match(/[A-Za-z0-9_]*$/) || [''])[0]
  const start = result ? result.start : caret - fallbackWord.length
  const end = result ? result.end : caret
  const prefix = (result ? text.slice(result.start, caret) : fallbackWord).toLowerCase()
  const base = result ? [...result.items] : []
  const seen = new Set(base.map(suggestionKey))
  const builtins: Suggestion[] = []
  const pushBuiltin = (item: Suggestion) => {
    const key = suggestionKey(item)
    if (seen.has(key)) return
    seen.add(key)
    builtins.push(item)
  }

  if (prefix) {
    SQL_BUILTIN_KEYWORDS
      .filter((keyword) => keyword.startsWith(prefix))
      .forEach((keyword) => pushBuiltin({ label: keyword, insert: keyword, kind: 'keyword' }))
    SQL_BUILTIN_FUNCTIONS
      .filter((name) => name.startsWith(prefix))
      .forEach((name) => pushBuiltin({
        label: name, insert: `${name}(`, kind: 'function', detail: '函数',
      }))
  } else if (manual) {
    SQL_BUILTIN_KEYWORDS
      .forEach((keyword) => pushBuiltin({ label: keyword, insert: keyword, kind: 'keyword' }))
    SQL_BUILTIN_FUNCTIONS
      .forEach((name) => pushBuiltin({
        label: name, insert: `${name}(`, kind: 'function', detail: '函数',
      }))
  }

  if (base.length + builtins.length === 0) return null
  if (base.length + builtins.length <= MAX_SUGGESTIONS) {
    return { items: [...base, ...builtins], start, end }
  }
  // 宽表可能用字段把 50 个名额占满：给关键字/函数留出位置，否则它们会被截断掉
  const keepBuiltins = builtins.slice(0, MAX_SUGGESTIONS)
  const room = MAX_SUGGESTIONS - keepBuiltins.length
  return { items: [...base.slice(0, Math.max(0, room)), ...keepBuiltins], start, end }
}

type Token = { text: string; cls?: string }

/**
 * 编辑器自带的 SQL 关键字/函数兜底。
 *
 * sqlSuggest 已经会产出这两类补全；这里再保留一份“最小集合”并在合并时去重，
 * 目的是：表元数据接口慢/失败时，关键字和常用函数仍然可用；Ctrl+Space
 * 在空编辑器里也能唤出清单。两份列表按 kind + insert 去重，不会出现重复项。
 */
const SQL_BUILTIN_KEYWORDS = [
  'select', 'from', 'where', 'group by', 'order by', 'having', 'limit', 'offset',
  'join', 'left join', 'right join', 'inner join', 'full join', 'on',
  'as', 'and', 'or', 'not', 'null', 'is null', 'is not null', 'in', 'exists',
  'between', 'like', 'asc', 'desc', 'distinct', 'union', 'union all', 'with',
  'case', 'when', 'then', 'else', 'end', 'cast',
]

const SQL_BUILTIN_FUNCTIONS = [
  'count', 'sum', 'avg', 'min', 'max', 'round', 'abs', 'ceil', 'floor',
  'coalesce', 'ifnull', 'nullif', 'now', 'date_trunc', 'date_format',
  'row_number', 'rank', 'dense_rank', 'lag', 'lead',
]

/** 补全面板最多展示多少项，避免大库/宽表把列表撑得过长。 */
const MAX_SUGGESTIONS = 50

const SQL_KEYWORDS = new Set(('select from where group by order having limit offset join left right ' +
  'inner outer full cross on as and or not null is in exists between like asc desc distinct union all ' +
  'insert into values update set delete create table view drop alter with case when then else end cast ' +
  'count sum avg min max over partition rows range using primary key if add column database schema ' +
  'explain analyze true false interval current_date current_timestamp').split(' '))

const PY_KEYWORDS = new Set(('def class import from return if elif else for while in not and or is ' +
  'None True False try except finally raise with as lambda yield pass break continue global nonlocal ' +
  'assert del async await self').split(' '))

const SH_KEYWORDS = new Set(('if then else fi for in do done while case esac function export local ' +
  'return echo set unset source').split(' '))

function normalizeLang(language?: string): 'sql' | 'python' | 'shell' | 'json' | '' {
  const value = (language || '').toLowerCase()
  if (value === 'sql') return 'sql'
  if (value === 'python' || value === 'py') return 'python'
  if (value === 'shell' || value === 'sh' || value === 'bash') return 'shell'
  if (value === 'json') return 'json'
  return ''
}

/**
 * 轻量语法高亮：只用一条正则扫一遍，不引入 Monaco / CodeMirror。
 * 关键是「不改动一个字符」——未命中的文本原样保留，保证和 textarea 逐字对齐。
 */
function tokenize(code: string, lang: ReturnType<typeof normalizeLang>): Token[] {
  if (!lang) return [{ text: code }]
  let pattern: RegExp
  if (lang === 'sql') {
    pattern = /(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:[^']|'')*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)/g
  } else if (lang === 'python') {
    pattern = /(#[^\n]*)|('''[\s\S]*?'''|"""[\s\S]*?"""|'(?:[^'\\\n]|\\.)*'|"(?:[^"\\\n]|\\.)*")|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)/g
  } else if (lang === 'shell') {
    pattern = /(#[^\n]*)|('(?:[^']*)'|"(?:[^"\\]|\\.)*")|(\$\{?[A-Za-z_][A-Za-z0-9_]*\}?)|(\b\d+\b)|([A-Za-z_][A-Za-z0-9_]*)/g
  } else {
    pattern = /("(?:[^"\\]|\\.)*")|(\b-?\d+(?:\.\d+)?\b)|(\btrue\b|\bfalse\b|\bnull\b)/g
  }

  const tokens: Token[] = []
  let last = 0
  let match: RegExpExecArray | null
  while ((match = pattern.exec(code)) !== null) {
    if (match.index > last) tokens.push({ text: code.slice(last, match.index) })
    const text = match[0]
    let cls: string | undefined
    if (lang === 'json') {
      cls = match[1] ? 'tok-string' : match[2] ? 'tok-number' : 'tok-literal'
    } else if (match[1]) {
      cls = 'tok-comment'
    } else if (lang === 'shell' && match[3]) {
      cls = 'tok-variable'
    } else if (match[2]) {
      cls = 'tok-string'
    } else if (/\d/.test(text[0])) {
      cls = 'tok-number'
    } else if (lang === 'sql' ? SQL_KEYWORDS.has(text.toLowerCase())
      : lang === 'python' ? PY_KEYWORDS.has(text)
        : SH_KEYWORDS.has(text)) {
      cls = 'tok-keyword'
    }
    tokens.push({ text, cls })
    last = match.index + text.length
  }
  if (last < code.length) tokens.push({ text: code.slice(last) })
  return tokens
}

function renderTokens(tokens: Token[]): ReactNode[] {
  return tokens.map((token, index) => token.cls
    ? <span key={index} className={token.cls}>{token.text}</span>
    : <span key={index}>{token.text}</span>)
}

/**
 * 轻量代码编辑器：行号、横向滚动、Tab 缩进、Ctrl/Cmd+S 保存、SQL/Python/Shell 高亮。
 * 高亮层是 textarea 下面的一层只读 <pre>，两层字体与间距完全一致，滚动同步。
 */
export default function CodeEditor({
  value, onChange, readOnly = false, placeholder, onSave, onRun, label = '代码编辑器',
  height, language, autocomplete = true, projectId, tables: providedTables,
}: CodeEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const gutterRef = useRef<HTMLDivElement>(null)
  const highlightRef = useRef<HTMLPreElement>(null)
  const lines = Math.max(1, value.split('\n').length)
  const lang = normalizeLang(language)
  const [tables, setTables] = useState<SqlTable[]>([])
  // 项目注册的 UDF（已发布）：与表目录同一次请求返回，用于函数名补全
  const [functions, setFunctions] = useState<SqlFunction[]>([])
  const [sug, setSug] = useState<(SuggestResult & { index: number; top: number; left: number }) | null>(null)

  const suggestEnabled = lang === 'sql' && autocomplete && !readOnly && !!onChange

  useEffect(() => {
    if (!suggestEnabled) return
    let alive = true
    if (providedTables) {
      setTables(providedTables)
      return () => { alive = false }
    }
    setTables([])
    setFunctions([])
    loadSqlTables(projectId).then((list) => {
      if (!alive) return
      setTables(list)
      setFunctions(sqlFunctionsFor(projectId))
    })
    return () => { alive = false }
  }, [suggestEnabled, projectId, providedTables])

  /** 根据光标位置重新计算补全项，并把它贴在光标下方（空间不够时翻到上方）。 */
  const refreshSuggest = (el: HTMLTextAreaElement, manual = false) => {
    if (!suggestEnabled || value.length > 100_000) {
      setSug(null)
      return
    }
    if (!providedTables && tables.length === 0) {
      // 元数据第一次没拉到（Doris 元数据接口偶发慢）：下一次按键顺手重试，成功就补上表名提示
      loadSqlTables(projectId).then((list) => { if (list.length > 0) setTables(list) })
    }
    if (el.selectionStart !== el.selectionEnd) {
      setSug(null)
      return
    }
    // 表/字段优先；关键字/函数兜底，去重与截断在 withBuiltins 里统一做
    const result = withBuiltins(
      suggestFor(el.value, el.selectionStart, tables, functions),
      el.value,
      el.selectionStart,
      manual,
    )
    if (!result || result.items.length === 0) {
      setSug(null)
      return
    }
    const caret = caretPosition(el, el.selectionStart)
    const lineHeight = parseFloat(window.getComputedStyle(el).lineHeight) || 20
    const panelHeight = Math.min(220, result.items.length * 26 + 30)
    const below = caret.top + lineHeight + panelHeight <= el.clientHeight
    const width = 300
    setSug({
      ...result,
      index: 0,
      top: below ? caret.top + lineHeight : Math.max(0, caret.top - panelHeight),
      left: Math.max(0, Math.min(caret.left, el.clientWidth - width - 12)),
    })
  }

  const acceptSuggestion = (item: Suggestion) => {
    if (!sug) return
    const el = textareaRef.current
    const nextValue = value.slice(0, sug.start) + item.insert + value.slice(sug.end)
    onChange?.(nextValue)
    setSug(null)
    const caret = sug.start + item.insert.length
    requestAnimationFrame(() => {
      if (!el) return
      el.focus()
      el.selectionStart = caret
      el.selectionEnd = caret
    })
  }

  const syncScroll = (event: UIEvent<HTMLTextAreaElement>) => {
    if (gutterRef.current) {
      gutterRef.current.scrollTop = event.currentTarget.scrollTop
    }
    if (highlightRef.current) {
      highlightRef.current.scrollTop = event.currentTarget.scrollTop
      highlightRef.current.scrollLeft = event.currentTarget.scrollLeft
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
      event.preventDefault()
      // 只读角色/保存中不应发起写请求
      if (!readOnly) onSave?.()
      return
    }
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault()
      onRun?.()
      return
    }
    // Ctrl+Space 手动唤出补全：平时只在输入时弹，移动光标不再自动弹
    if ((event.ctrlKey || event.metaKey) && event.key === ' ') {
      event.preventDefault()
      // 手动唤出：没有表/字段可补时也给出关键字与函数清单
      refreshSuggest(event.currentTarget, true)
      return
    }
    if (sug && sug.items.length > 0) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        setSug({ ...sug, index: (sug.index + 1) % sug.items.length })
        return
      }
      if (event.key === 'ArrowUp') {
        event.preventDefault()
        setSug({ ...sug, index: (sug.index - 1 + sug.items.length) % sug.items.length })
        return
      }
      if (event.key === 'Enter' || event.key === 'Tab') {
        event.preventDefault()
        acceptSuggestion(sug.items[sug.index])
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        setSug(null)
        return
      }
    }
    if (event.key !== 'Tab' || readOnly || !onChange) return
    const el = event.currentTarget
    const start = el.selectionStart
    const end = el.selectionEnd
    const lineStart = value.lastIndexOf('\n', start - 1) + 1
    const lineBreak = value.indexOf('\n', end)
    const lineEnd = lineBreak === -1 ? value.length : lineBreak
    const multiline = value.slice(start, end).includes('\n')
    const indent = '  '

    if (event.shiftKey) {
      // 反缩进；没有可减的缩进就放行，让键盘用户能用 Shift+Tab 移出编辑器
      const lines = value.slice(lineStart, lineEnd).split('\n')
      let removedFirst = 0
      let removedTotal = 0
      const outdented = lines.map((line, index) => {
        const match = line.match(/^( {1,2}|\t)/)
        const cut = match ? match[1].length : 0
        if (index === 0) removedFirst = cut
        removedTotal += cut
        return line.slice(cut)
      }).join('\n')
      if (removedTotal === 0) return
      event.preventDefault()
      onChange(value.slice(0, lineStart) + outdented + value.slice(lineEnd))
      requestAnimationFrame(() => {
        const nextStart = Math.max(lineStart, start - removedFirst)
        const nextEnd = Math.max(nextStart, end - removedTotal)
        el.selectionStart = nextStart
        el.selectionEnd = nextEnd
      })
      return
    }

    event.preventDefault()
    if (multiline) {
      // 选中多行时整块缩进，而不是把选区替换成两个空格
      const lines = value.slice(lineStart, lineEnd).split('\n')
      onChange(value.slice(0, lineStart) + lines.map((line) => indent + line).join('\n')
        + value.slice(lineEnd))
      requestAnimationFrame(() => {
        el.selectionStart = start + indent.length
        el.selectionEnd = end + indent.length * lines.length
      })
      return
    }
    onChange(value.slice(0, start) + indent + value.slice(end))
    requestAnimationFrame(() => {
      const next = start + indent.length
      el.selectionStart = next
      el.selectionEnd = next
    })
  }

  return (
    <div className="code-editor" style={height ? { height, minHeight: height } : undefined}>
      <div className="code-editor-gutter" ref={gutterRef} aria-hidden="true">
        {Array.from({ length: lines }, (_, i) => <div key={i}>{i + 1}</div>)}
      </div>
      <div className="code-editor-main">
        <pre className="code-editor-highlight" ref={highlightRef} aria-hidden="true">
          {/* 超长文本不做逐 token 渲染，避免每次按键卡死；内容本身不受影响 */}
          {value.length > 100_000 ? value : renderTokens(tokenize(value, lang))}
          {'​'}
        </pre>
        <textarea
          ref={textareaRef}
          aria-label={label}
          value={value}
          readOnly={readOnly}
          spellCheck={false}
          wrap="off"
          placeholder={placeholder}
          onChange={(e) => {
            onChange?.(e.target.value)
            // 输入后立刻按新内容算补全；字符串/注释里 suggestFor 会自己返回 null
            refreshSuggest(e.currentTarget)
          }}
          onClick={() => setSug(null)}
          onKeyUp={(e) => {
            if (['ArrowDown', 'ArrowUp', 'Enter', 'Tab', 'Escape'].includes(e.key)) return
            // 移动光标不是输入：直接关掉补全面板，避免它挡住下面几行
            if (['ArrowLeft', 'ArrowRight', 'Home', 'End', 'PageUp', 'PageDown'].includes(e.key)) {
              setSug(null)
              return
            }
            refreshSuggest(e.currentTarget)
          }}
          onScroll={(e) => {
            syncScroll(e)
            if (sug) setSug(null)
          }}
          onBlur={() => window.setTimeout(() => setSug(null), 120)}
          onKeyDown={onKeyDown}
        />
        {sug && sug.items.length > 0 && (
          <div className="sql-suggest" style={{ top: sug.top, left: sug.left }} role="listbox">
            {sug.items.map((item, index) => (
              <div
                key={`${item.kind}-${item.insert}-${index}`}
                role="option"
                aria-selected={index === sug.index}
                className={`sql-suggest-item${index === sug.index ? ' is-active' : ''}`}
                onMouseDown={(e) => {
                  // 阻止 textarea 失焦，点击才能落到补全项上
                  e.preventDefault()
                  acceptSuggestion(item)
                }}
                onMouseEnter={() => setSug((current) => (current ? { ...current, index } : current))}
              >
                <span className={`sql-suggest-kind kind-${item.kind}`}>{KIND_LABEL[item.kind]}</span>
                <span className="sql-suggest-label">{item.label}</span>
                {item.detail ? <span className="sql-suggest-detail">{item.detail}</span> : null}
              </div>
            ))}
            <div className="sql-suggest-foot">↑↓ 选择 · Enter / Tab 插入 · Esc 关闭</div>
          </div>
        )}
      </div>
    </div>
  )
}
