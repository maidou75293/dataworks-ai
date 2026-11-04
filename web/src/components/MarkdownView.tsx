import React from 'react'

/**
 * 帮助手册轻量 Markdown 渲染（REQ-HELP-NAV-1）。
 * 只渲染我们自己的 docs/帮助/*.md 受控子集：#~####、段落、- 列表、1. 列表、
 * |表格|、```围栏代码、`行内代码`、**加粗**；不解析 HTML（React 文本节点天然转义）。
 * h2 的锚点 id = `${idPrefix}-s${序号}`，序号只数围栏外的 h2——与后端 HelpService 的
 * sections 编号是同一规则，目录树据此定位。
 */

type Block =
  | { t: 'h'; level: number; id?: string; text: string }
  | { t: 'p'; text: string }
  | { t: 'ul' | 'ol'; items: string[] }
  | { t: 'code'; lang: string; text: string }
  | { t: 'table'; header: string[]; rows: string[][] }

function parse(md: string, idPrefix: string): Block[] {
  const out: Block[] = []
  const lines = md.split('\n')
  let h2 = 0
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.startsWith('```')) {
      const lang = line.slice(3).trim()
      const buf: string[] = []
      i++
      while (i < lines.length && !lines[i].startsWith('```')) buf.push(lines[i++])
      i++ // 跳过闭合围栏
      out.push({ t: 'code', lang, text: buf.join('\n') })
      continue
    }
    if (/^\s*$/.test(line)) { i++; continue }
    if (/^\s*---+\s*$/.test(line)) { i++; continue }   // 分隔线：跳过
    const hm = /^(#{1,4})\s+(.*)$/.exec(line)
    if (hm) {
      const level = hm[1].length
      const text = hm[2].trim()
      if (level === 2) out.push({ t: 'h', level, id: `${idPrefix}-s${++h2}`, text })
      else out.push({ t: 'h', level, text })
      i++
      continue
    }
    if (/^\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$/.test(lines[i + 1])) {
      const cells = (l: string) => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
      const header = cells(line)
      const rows: string[][] = []
      i += 2
      while (i < lines.length && /^\|.*\|\s*$/.test(lines[i])) rows.push(cells(lines[i++]))
      out.push({ t: 'table', header, rows })
      continue
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*]\s+/, ''))
      out.push({ t: 'ul', items })
      continue
    }
    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items: string[] = []
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) items.push(lines[i++].replace(/^\s*\d+[.)]\s+/, ''))
      out.push({ t: 'ol', items })
      continue
    }
    const para: string[] = []
    while (i < lines.length && !/^\s*$/.test(lines[i])
      && !lines[i].startsWith('#') && !lines[i].startsWith('```')
      && !/^\s*[-*]\s+/.test(lines[i]) && !/^\s*\d+[.)]\s+/.test(lines[i])
      && !/^\|.*\|\s*$/.test(lines[i])) {
      para.push(lines[i].trim())
      i++
    }
    if (para.length) out.push({ t: 'p', text: para.join(' ') })
    else i++
  }
  return out
}

/** 行内：`code` 与 **bold**，其余按纯文本（React 转义，不出现 HTML 注入面）。 */
function inline(text: string): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  const re = /(`[^`]+`|\*\*[^*]+\*\*)/g
  let last = 0
  let m: RegExpExecArray | null
  let k = 0
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    const tok = m[0]
    if (tok.startsWith('`')) parts.push(<code key={k} className="md-ic">{tok.slice(1, -1)}</code>)
    else parts.push(<strong key={k}>{tok.slice(2, -2)}</strong>)
    last = m.index + tok.length
    k++
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts
}

export default function MarkdownView({ text, idPrefix = 'hc' }: { text: string; idPrefix?: string }) {
  const blocks = React.useMemo(() => parse(text, idPrefix), [text, idPrefix])
  return (
    <div className="markdown-view">
      {blocks.map((b, idx) => {
        switch (b.t) {
          case 'h': {
            const size = [0, 18, 15, 13.5, 13][b.level]
            return (
              <div
                key={idx}
                id={b.id}
                style={{ fontSize: size, fontWeight: 600, margin: b.level === 1 ? '4px 0 10px' : '16px 0 8px', scrollMarginTop: 8 }}
              >
                {inline(b.text)}
              </div>
            )
          }
          case 'p':
            return <p key={idx} style={{ margin: '6px 0', fontSize: 13, lineHeight: 1.85 }}>{inline(b.text)}</p>
          case 'ul':
            return (
              <ul key={idx} style={{ margin: '6px 0', paddingLeft: 20, fontSize: 13, lineHeight: 1.85 }}>
                {b.items.map((it, n) => <li key={n}>{inline(it)}</li>)}
              </ul>
            )
          case 'ol':
            return (
              <ol key={idx} style={{ margin: '6px 0', paddingLeft: 20, fontSize: 13, lineHeight: 1.85 }}>
                {b.items.map((it, n) => <li key={n}>{inline(it)}</li>)}
              </ol>
            )
          case 'code':
            // 内联样式不依赖 styles.css（REQ-HELP-NAV-1 避开共享样式文件）
            return (
              <pre key={idx} style={{
                padding: '10px 12px', background: 'rgba(127,127,127,0.14)', borderRadius: 6,
                overflow: 'auto', fontSize: 12, lineHeight: 1.7, margin: '8px 0', whiteSpace: 'pre',
              }}>
                <code>{b.text}</code>
              </pre>
            )
          case 'table':
            return (
              <table key={idx} style={{ borderCollapse: 'collapse', fontSize: 12, width: '100%', margin: '8px 0' }}>
                <thead>
                  <tr>{b.header.map((c, n) => (
                    <th key={n} style={{ border: '1px solid rgba(127,127,127,0.35)', padding: '4px 8px', textAlign: 'left', background: 'rgba(127,127,127,0.10)' }}>{inline(c)}</th>
                  ))}</tr>
                </thead>
                <tbody>
                  {b.rows.map((r, n) => (
                    <tr key={n}>{r.map((c, m) => (
                      <td key={m} style={{ border: '1px solid rgba(127,127,127,0.35)', padding: '4px 8px' }}>{inline(c)}</td>
                    ))}</tr>
                  ))}
                </tbody>
              </table>
            )
          default:
            return null
        }
      })}
    </div>
  )
}
