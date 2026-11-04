/**
 * BUG-PARITY-15 列表手感与偏好「平台通用包」——列表通用约定的唯一实现点（改一处全站受益）。
 *
 * 对拍=docs/交互对拍-标注五页新旧行为-20261029.md §二/§三/§四/§五 标 PARITY-15 的通用维度：
 *  ① 每页条数全局持久（§二:52/§三:75/§四:91/§五:113）≡ 老 prefs.js:6-35——localStorage 键 'ft.prefs'、
 *     PAGE_SIZES [20,50,80,100,200]、默认 50、跨标签页 storage 同步；键格式与老版互通，不另立第二套键；
 *  ② 总数+吸底（§三:75/§四:91）≡ Pager.vue:3-6,82-85——「共 N 条 · 第 x–y 条」逐字口径；
 *     分页条 position:sticky 吸在视口底部；不足一页也保留总数（hideOnSinglePage 属禁用词）；
 *  ③ 后退=pushState（§三:73）≡ Datasets.vue:1340-1341——从列表打开详情 push 一条历史，
 *     浏览器后退只关详情不离开页面；深链直达（历史里没有该条）关闭退回 replace；
 *  ④ 确认框换行（§四:92）≡ ConfirmDialog.vue:39——content 里的 \n 真空行（pre-wrap），
 *     不再被 antd Modal.confirm 裸串折叠挤成一行；
 *  ⑤ 行/卡片键盘可达（§二:53/§三:77/§四:93）≡ LabelStudio.vue:104-105 / Datasets.vue:48-50 /
 *     Jobs.vue:30-31——Tab 可达 + Enter/Space 触发 + focus 可见外框；
 *  ⑥ 防抖即查（§三:72）≡ Datasets.vue:289-290,1437-1444——280ms 停止输入即查，不等回车。
 *
 * 验收=python -I scripts/e2e/parity15-list-prefs-test.py（闸1 静态 / 闸2 真浏览器 / 闸3 红检 / 闸4 live SKIP）。
 * 消费方：MosLabels / MosDatasets / MosJobs / AssetsMedia（媒体页接线同包；13 系列夹具按新默认值翻断言）。
 */
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Modal } from 'antd'

// ── ① 每页条数全局偏好（≡ prefs.js:6-35，键/值集/默认逐字保留，老数据可直接续用） ──
export const PREFS_KEY = 'ft.prefs'
export const PAGE_SIZES = [20, 50, 80, 100, 200]
export const DEFAULT_PAGE_SIZE = 50

function readPrefs(): { pageSize?: unknown } {
  try { return JSON.parse(localStorage.getItem(PREFS_KEY) || '{}') || {} } catch { return {} }
}
export function normalizePageSize(n: unknown): number {
  const v = Number(n)
  return PAGE_SIZES.includes(v) ? v : DEFAULT_PAGE_SIZE
}
function writePrefs(n: number) {
  // 隐私模式写不进就算了（prefs.js:19 同口径），不抛错、不打断浏览
  try { localStorage.setItem(PREFS_KEY, JSON.stringify({ ...readPrefs(), pageSize: n })) } catch { /* noop */ }
}

// 同页多列表共享同一值：本页改过大小后，另一处 useGlobalPageSize 的列表立刻跟着（≡ prefs.js watch 单一 ref）
const sizeListeners = new Set<(n: number) => void>()

export function useGlobalPageSize(): [number, (n: number) => void] {
  const [size, setSizeState] = useState(() => normalizePageSize(readPrefs().pageSize))
  useEffect(() => {
    // 吸底/焦点环样式随「本页用了列表通用包」注入一次——不依赖详情参数路径（Jobs/Labels/媒体都吃到）
    ensurePagerCss()
    const apply = (n: number) => setSizeState(normalizePageSize(n))
    sizeListeners.add(apply)
    // 同机多标签页保持一致（≡ prefs.js:28-35：e.key=null 的 localStorage.clear() 也按存储被改处理）
    const onStorage = (e: StorageEvent) => {
      if (e.key !== PREFS_KEY && e.key !== null) return
      apply(normalizePageSize(readPrefs().pageSize))
    }
    window.addEventListener('storage', onStorage)
    return () => { sizeListeners.delete(apply); window.removeEventListener('storage', onStorage) }
  }, [])
  const setSize = (n: number) => {
    const v = normalizePageSize(n)
    writePrefs(v)
    sizeListeners.forEach((cb) => cb(v))
  }
  return [size, setSize]
}

// ── ② 总数文案 + 吸底（≡ Pager.vue:3-6 口径、:82-85 粘性） ────────────────
/** 「共 N 条 · 第 x–y 条」；不足一页只留「共 N 条」（≡ Pager.vue v-if pageCount>1） */
export function listShowTotal(label = '条') {
  return (total: number, range: [number, number]) =>
    `共 ${total} ${label}` + (range[0] > 1 || range[1] < total ? ` · 第 ${range[0]}–${range[1]} ${label}` : '')
}

export interface ListPagerOpts {
  current: number
  total: number
  size: number
  label?: string
  onChange: (page: number, size: number) => void
}
/** 统一分页 props（antd Table.pagination 与独立 Pagination 通用）。
 *  每页条数变了页码从头来（≡ Pager.vue:64-69——否则跳到中间某页），回调只发一次。 */
export function listPagination(o: ListPagerOpts) {
  return {
    current: o.current,
    pageSize: o.size,
    total: o.total,
    showSizeChanger: true,
    pageSizeOptions: PAGE_SIZES,
    showTotal: listShowTotal(o.label),
    onChange: (p: number, s: number) => {
      if (s !== o.size) o.onChange(1, s)
      else o.onChange(p, s)
    },
  }
}

// 吸底样式与键盘可达焦点环：随第一次使用注入一次（长列表滚到一半也能翻页，≡ Pager.vue:81 注释原意）
const PAGER_CSS = [
  '.dw-sticky-pager .ant-pagination,',
  '.dw-sticky-pager.ant-pagination{position:sticky;bottom:0;z-index:4;background:#fff;',
  'padding:8px 10px;border-top:1px solid #ececec;box-shadow:0 -1px 0 #ececec;}',
  '.dw-kb{cursor:pointer;}',
  '.dw-kb:focus-visible{outline:2px solid #1677ff;outline-offset:-2px;border-radius:2px;}',
].join('')
let cssInjected = false
function ensurePagerCss() {
  if (cssInjected || typeof document === 'undefined') return
  if (document.getElementById('dw-list-prefs-css')) { cssInjected = true; return }
  const s = document.createElement('style')
  s.id = 'dw-list-prefs-css'
  s.textContent = PAGER_CSS
  document.head.appendChild(s)
  cssInjected = true
}

// ── ③ 后退=pushState（详情载体是 URL 参数时通用） ──────────────────────────
/** 详情/抽屉与 ?param= 的历史语义：
 *  pushDetail(id) —— 用户从列表打开：push 一条历史（浏览器后退=关详情留在页内，不再直接离页）；
 *  popDetail()    —— 关闭：本次打开 push 过就 navigate(-1) 退回去（MemoryRouter 也安全，不碰真历史）；
 *                    深链直达（历史里没有该条）就 replace 清参；
 *  URL 上参数被外部去掉（真按了后退）时自动把 pushed 标记归位，防止下一次关闭误退别人的历史。 */
export function useDetailHistory(paramName: string) {
  const [searchParams, setSearchParams] = useSearchParams()
  const navigate = useNavigate()
  const pushedRef = useRef(false)

  useEffect(() => {
    if (!searchParams.get(paramName)) pushedRef.current = false
  }, [searchParams, paramName])

  const pushDetail = (value: string) => {
    ensurePagerCss()
    if (searchParams.get(paramName) === value) return
    pushedRef.current = true
    setSearchParams({ [paramName]: value })
  }
  const popDetail = () => {
    if (pushedRef.current) {
      pushedRef.current = false
      navigate(-1)
      return
    }
    if (searchParams.get(paramName)) {
      setSearchParams((prev) => {
        const next = new URLSearchParams(prev)
        next.delete(paramName)
        return next
      }, { replace: true })
    }
  }
  return { searchParams, pushDetail, popDetail }
}

// ── ④ 确认框：站内 Modal.confirm + content 换行兑现（≡ ConfirmDialog.vue:39 pre-wrap） ──
export function dwConfirm(o: {
  title: string
  content: string
  okText?: string
  cancelText?: string
  danger?: boolean
  onOk?: () => void | Promise<unknown>
  onCancel?: () => void
}) {
  Modal.confirm({
    title: o.title,
    // 裸字符串会被折叠成一行；这里必须用节点承载 \n（§四:92「删除任务」「融合」「量化」三框全吃过这个亏）
    content: <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{o.content}</div>,
    okText: o.okText || '确定',
    cancelText: o.cancelText || '取消',
    okButtonProps: { danger: !!o.danger },
    onOk: () => o.onOk?.(),
    onCancel: () => o.onCancel?.(),
  })
}

// ── ⑤ 行/卡片键盘可达 ─────────────────────────────────────────────────────
/** 展开到 <tr>/<Card>/<a> 上：Tab 可达（tabIndex=0），Enter/Space 触发，输入控件焦点不吃。 */
export function kbActivate(onEnter: () => void) {
  return {
    tabIndex: 0,
    className: 'dw-kb',
    onKeyDown: (e: React.KeyboardEvent) => {
      if (e.key !== 'Enter' && e.key !== ' ') return
      // 只认落点在可达区域本身的键盘事件：行内按钮/输入焦点的 Enter 冒泡上来时不吃，
      // 否则「聚焦删除钮按回车」会连带触发一次打开详情
      if (e.target !== e.currentTarget) return
      e.preventDefault()
      e.stopPropagation()
      onEnter()
    },
  }
}

// ── ⑥ 防抖即查（≡ Datasets.vue:289-290,1437-1444 的 280ms） ────────────────
export const DEBOUNCE_MS = 280

export function useDebouncedValue<T>(value: T, ms = DEBOUNCE_MS): T {
  const [v, setV] = useState(value)
  useEffect(() => {
    const t = window.setTimeout(() => setV(value), ms)
    return () => window.clearTimeout(t)
  }, [value, ms])
  return v
}
