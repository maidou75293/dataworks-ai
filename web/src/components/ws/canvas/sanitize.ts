/**
 * sanitize.ts —— HTML 白名单清洗（REQ-UNIFY-6-3 文档预览）。
 *
 * 逐行移植 mos/platform/frontend/src/sanitize.js（96 行，只读原件）：
 * 文档正文（docx——HTML）与 HyperText 任务正文来自上传文件，直接塞进
 * dangerouslySetInnerHTML 等于让一个精心构造的 docx 在平台 origin 里执行脚本。
 * 控制字符绕过（java\tscript:、前导空格）与协议相对地址（//host、\\host）
 * 的判定口径原样保留（2026-10-24 审查教训）。
 *
 * 移植注：DOMParser 只在浏览器存在；node/SSR 返回 ''（组件按「无安全内容」渲染
 * 兜底文案，绝不回落原文）。isSafeUrl 是纯字符串逻辑，单测直跑。
 */

const ALLOWED = new Set([
  'p', 'div', 'span', 'br', 'hr', 'strong', 'b', 'em', 'i', 'u', 's', 'sub', 'sup',
  'ul', 'ol', 'li', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'caption', 'colgroup', 'col',
  'blockquote', 'pre', 'code', 'a', 'img', 'figure', 'figcaption',
  'section', 'article', 'header', 'footer', 'dl', 'dt', 'dd',
])

// 这些标签连同内容一起丢弃
const BLOCKED = new Set([
  'script', 'style', 'iframe', 'object', 'embed', 'link', 'meta', 'base',
  'form', 'input', 'button', 'textarea', 'select', 'option', 'svg', 'math',
  'video', 'audio', 'source', 'track', 'template', 'noscript',
])

const ATTRS: Record<string, string[]> = {
  a: ['href', 'title'],
  img: ['src', 'alt', 'title', 'width', 'height'],
  td: ['colspan', 'rowspan'],
  th: ['colspan', 'rowspan'],
  col: ['span'],
  colgroup: ['span'],
}

// 浏览器解析 URL 前会丢掉 NUL——空格、DEL 这类控制字符：`java\tscript:` 等价于
// `javascript:`。协议判定必须先把它们去掉，否则会被当成「没有 scheme 的相对路径」放行。
const URL_CONTROLS = /[\u0000-  \u007f]/g

export function isSafeUrl(value: unknown, tag: string): boolean {
  const v = String(value ?? '').trim()
  if (!v) return false
  // 统一去掉控制字符后再判协议；原值仍按原样保留（相对路径里的空格是合法字符）。
  const flat = v.replace(URL_CONTROLS, '')
  if (!flat) return false
  // `//host/x` 和 `\\host\x` 是协议相对地址，会被浏览器解析成外站绝对地址。
  if (flat.startsWith('//') || flat.startsWith('\\\\')) return false
  // 相对路径 / 锚点：没有 scheme 就是安全的
  if (!/^[a-z][a-z0-9+.-]*:/i.test(flat)) return true
  const scheme = flat.slice(0, flat.indexOf(':')).toLowerCase()
  if (scheme === 'http' || scheme === 'https' || scheme === 'mailto') return true
  if (tag === 'img' && scheme === 'data' && /^data:image\/(png|jpe?g|gif|webp|bmp);/i.test(flat)) return true
  return false
}

type El = { nodeType: number; nodeValue: string | null; tagName: string;
  childNodes: ArrayLike<El>; attributes: ArrayLike<{ name: string; value: string }> }

function cleanInto(src: El, dst: { appendChild(n: unknown): void }, doc: Document): void {
  for (const node of Array.from(src.childNodes as unknown as ArrayLike<El>)) {
    if (node.nodeType === 3) {                     // 文本
      dst.appendChild(doc.createTextNode(node.nodeValue || ''))
      continue
    }
    if (node.nodeType !== 1) continue               // 注释等丢弃
    const tag = node.tagName.toLowerCase()
    if (BLOCKED.has(tag)) continue
    if (!ALLOWED.has(tag)) {                        // 未知标签：保留文字，丢标签
      cleanInto(node, dst, doc)
      continue
    }
    let el
    try {
      el = doc.createElement(tag)
    } catch {
      continue
    }
    const allowed = ATTRS[tag] || []
    for (const attr of Array.from(node.attributes)) {
      const name = attr.name.toLowerCase()
      if (!allowed.includes(name)) continue
      if ((name === 'href' || name === 'src') && !isSafeUrl(attr.value, tag)) continue
      el.setAttribute(name, attr.value)
    }
    if (tag === 'a') {
      el.setAttribute('rel', 'noopener noreferrer')
      el.setAttribute('target', '_blank')
    }
    if (tag === 'img') el.setAttribute('loading', 'lazy')
    cleanInto(node as unknown as El, el, doc)
    dst.appendChild(el)
  }
}

export function sanitizeHtml(html: unknown): string {
  const s = String(html ?? '')
  if (!s || typeof DOMParser === 'undefined') return ''
  const doc = new DOMParser().parseFromString(s, 'text/html')
  const out = doc.createElement('div')
  cleanInto(doc.body as unknown as El, out, doc)
  return out.innerHTML
}
