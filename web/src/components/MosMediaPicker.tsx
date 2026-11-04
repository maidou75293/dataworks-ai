import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, Button, Checkbox, Typography, message } from 'antd'
import { mediaSubtree, mediaTree } from '../api/mos'
import type { MosMediaCrumb, MosMediaFolder, MosMediaItem, MosMediaTree } from '../api/mos'
import { TEXT_MAX } from '../mediaTasks'

/**
 * 从媒体库选样本（BUG-PARITY-13-C）——移植 mos/platform/frontend/src/components/MediaPicker.vue。
 *
 * 消费方：
 * - 新建标注项目向导（MosLabels，老 LabelStudio.vue:252 同位）；
 * - 工作台「导入任务」弹窗（WsDialogs，老 LabelStudioWorkbench.vue:1037
 *   `<MediaPicker :accept-kinds="[mode]">` 的等价——替代 13-C 前平铺 200 封顶无全选的降级壳）。
 *
 * 行为口径（行号=老 MediaPicker.vue）：
 * - 目录行勾选框 = 整个文件夹**递归**选中（mediaSubtree 按前缀一次取回，:224-280）；
 *   半选态：有已选但未全选（folderState :232-242，all 判据用 subtree 缓存、缺缓存时退化用
 *   tree.files 的递归文件数）。
 * - 文件分页走服务端（:159-189）：mediaTree(path, pageSize, offset, kind)，
 *   acceptKinds 只有一种时交服务端过滤，保证「第 2 页」仍是该类型的第 2 页。
 * - PDF/Word 与其他不可建任务类型置灰（usable :138-142），提示「需先在媒体库转可标注」（:144）。
 * - 「全选已加载（N）」只对**当前层已载可用文件**做并集/摘除（:210-222），跨目录已选不吞。
 * - 选中文本 >200KB 提示「创建任务时只取前 200KB」（:81-83，阈值与 mediaTasks.TEXT_MAX 同源）。
 */

const KIND_LABEL: Record<string, string> = { audio: '音频', video: '视频', image: '图片', text: '文本', other: '其他' }
const PICKABLE = new Set(['audio', 'video', 'image', 'text'])
const DOC_EXT = new Set(['pdf', 'doc', 'docx'])

const PAGE_SIZE = 60

const extOf = (name: string) => {
  const i = String(name || '').lastIndexOf('.')
  return i >= 0 ? String(name).slice(i + 1).toLowerCase() : ''
}
const isDoc = (m?: MosMediaItem | null) => !!m && DOC_EXT.has(extOf(m.name))
const fmtSize = (n: number) => {
  const v = Number(n) || 0
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  if (v < 1024 * 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + ' MB'
  return (v / 1024 / 1024 / 1024).toFixed(1) + ' GB'
}

export default function MosMediaPicker({
  value, onChange, acceptKinds = [],
}: {
  value: MosMediaItem[]
  onChange: (list: MosMediaItem[]) => void
  /** 只允许选这些类型（空=全选，但不可建任务的文件仍置灰） */
  acceptKinds?: string[]
}) {
  const [cur, setCur] = useState('')
  const [folders, setFolders] = useState<MosMediaFolder[]>([])
  const [files, setFiles] = useState<MosMediaItem[]>([])
  const [filesTotal, setFilesTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [crumbs, setCrumbs] = useState<MosMediaCrumb[]>([{ name: '全部文件', path: '' }])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [folderBusy, setFolderBusy] = useState('')
  // path -> 该目录递归文件（勾选后缓存，二次点击取消不用重拉；老 :226）
  const folderFiles = useRef<Record<string, MosMediaItem[]>>({})

  const picked = value || []
  const selMap = useMemo(() => Object.fromEntries(picked.map((m) => [m.path, m])), [picked])
  // 只允许一种类型时交服务端过滤（老 :117）
  const kindParam = acceptKinds.length === 1 ? acceptKinds[0] : ''
  const visibleFiles = acceptKinds.length ? files.filter((m) => acceptKinds.includes(m.kind)) : files
  const usable = useCallback((m?: MosMediaItem | null) => {
    if (!m || isDoc(m)) return false
    if (!PICKABLE.has(m.kind)) return false
    return !acceptKinds.length || acceptKinds.includes(m.kind)
  }, [acceptKinds])
  const usableFiles = visibleFiles.filter(usable)
  const allPicked = usableFiles.length > 0 && usableFiles.every((m) => !!selMap[m.path])
  const bigText = picked.some((m) => m.kind === 'text' && Number(m.size) > TEXT_MAX)
  const pageCount = Math.max(1, Math.ceil(filesTotal / PAGE_SIZE))

  let loadSeq = 0
  const load = useCallback(async (path = '', pageNo = 1) => {
    // 连点目录/面包屑时只认最后一次请求（老 :158-189）
    const seq = ++loadSeq
    setLoading(true)
    setError('')
    try {
      const d: MosMediaTree = await mediaTree(path, PAGE_SIZE, (pageNo - 1) * PAGE_SIZE, kindParam)
      if (seq !== loadSeq) return
      setCur(d.path || path || '')
      setFolders(d.folders || [])
      setFiles(d.files || [])
      setFilesTotal(Number(d.files_total != null ? d.files_total : (d.files || []).length))
      setCrumbs(d.crumbs || [{ name: '全部文件', path: '' }])
      setPage(pageNo)
      const pages = Math.max(1, Math.ceil(Number(d.files_total || 0) / PAGE_SIZE))
      if (pageNo > pages) { void load(path, pages); return }  // 删空当前页时回退（老 :176）
    } catch (e: any) {
      if (seq !== loadSeq) return
      setError(e?.message || '加载失败')
      // 记住用户想去哪个目录：重试要重试它，而不是退回上一次成功的位置（老 :180-181）
      setCur(path || '')
      setFolders([])
      setFiles([])
      setFilesTotal(0)
      setPage(1)
    } finally {
      if (seq === loadSeq) setLoading(false)
    }
  }, [kindParam])

  useEffect(() => { void load('') }, [load])

  const toggle = (m: MosMediaItem) => {
    if (!usable(m)) return
    if (selMap[m.path]) onChange(picked.filter((x) => x.path !== m.path))
    else onChange([...picked, m])
  }

  const selectAll = () => {
    if (allPicked) {
      // 取消全选只摘「本层已载可用」，跨目录/别的页已选不动（老 :210-222）
      const vis = new Set(usableFiles.map((m) => m.path))
      onChange(picked.filter((m) => !vis.has(m.path)))
      return
    }
    const have = new Set(picked.map((m) => m.path))
    const next = picked.slice()
    for (const m of usableFiles) if (!have.has(m.path)) next.push(m)
    onChange(next)
  }

  const selectedUnder = (f: MosMediaFolder) => {
    const pre = f.path + '/'
    return picked.filter((m) => m.path.startsWith(pre))
  }
  const folderState = (f: MosMediaFolder): 'none' | 'some' | 'all' => {
    const sel = selectedUnder(f)
    if (!sel.length) return 'none'
    const all = folderFiles.current[f.path]
    if (all) {
      const usableAll = all.filter(usable)
      const have = new Set(picked.map((m) => m.path))
      return usableAll.length && usableAll.every((m) => have.has(m.path)) ? 'all' : 'some'
    }
    return sel.length >= f.files ? 'all' : 'some'
  }

  // 文件夹勾选框：勾上=递归选中整个目录；取消=移除该目录下已选（老 :243-280）
  const toggleFolder = async (f: MosMediaFolder) => {
    if (folderBusy) return
    if (folderState(f) === 'all') {
      const pre = f.path + '/'
      onChange(picked.filter((m) => !m.path.startsWith(pre)))
      return
    }
    setFolderBusy(f.path)
    try {
      let items = folderFiles.current[f.path]
      if (!items) {
        const r = await mediaSubtree(f.path, kindParam)
        items = r.items || []
        folderFiles.current = { ...folderFiles.current, [f.path]: items }
      }
      const have = new Set(picked.map((m) => m.path))
      const add = items.filter((m) => usable(m) && !have.has(m.path))
      if (!add.length) {
        // 没有可再添加的但目录下已有选中项（如夹带永远选不中的 PDF）：再点一次=取消整个文件夹（老 :261-272）
        const sel = selectedUnder(f)
        if (sel.length) {
          const pre = f.path + '/'
          onChange(picked.filter((m) => !m.path.startsWith(pre)))
          message.success('已从「' + f.name + '」移除 ' + sel.length + ' 个文件')
          return
        }
        message.error(items.length
          ? '这个文件夹里的文件都已选中，或没有当前可用的类型'
          : '这个文件夹（含子目录）里没有可用文件')
        return
      }
      onChange([...picked, ...add])
      message.success('已从「' + f.name + '」加入 ' + add.length + ' 个文件')
    } catch (e: any) {
      message.error('读取文件夹失败: ' + (e?.message || '未知错误'))
    } finally {
      setFolderBusy('')
    }
  }

  const rowTitle = (m: MosMediaItem) => {
    if (isDoc(m)) return 'PDF / Word 请先在「媒体库」点「转可标注」，再按生成的图片或文档建项目'
    if (!usable(m)) return '这个类型不能直接生成标注任务'
    return m.path
  }

  return (
    <div
      data-testid="media-picker"
      style={{ border: '1px solid #d9d9d9', borderRadius: 8, overflow: 'hidden', background: '#fff' }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', padding: '6px 8px', borderBottom: '1px solid #f0f0f0', background: '#fafafa' }}>
        <div data-testid="mp-crumbs" style={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
          {crumbs.map((c, i) => (
            <span key={c.path || 'root'} style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}>
              {i > 0 && <span style={{ color: '#999', fontSize: 12 }}>/</span>}
              <Button
                type={i === crumbs.length - 1 ? 'text' : 'link'}
                size="small"
                style={{ maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
                onClick={() => void load(c.path, 1)}
              >
                {c.name}
              </Button>
            </span>
          ))}
        </div>
        {usableFiles.length > 0 && (
          <Button size="small" data-testid="mp-select-all" onClick={selectAll}>
            {allPicked ? '取消全选' : `全选已加载（${usableFiles.length}）`}
          </Button>
        )}
      </div>

      <div style={{ maxHeight: 300, overflowY: 'auto' }}>
        {loading ? (
          <div data-testid="mp-loading" style={{ padding: 22, textAlign: 'center', color: '#999', fontSize: 13 }}>加载中…</div>
        ) : error ? (
          <Alert
            type="error"
            showIcon
            style={{ margin: 8 }}
            message="媒体库没加载出来"
            description={error}
            action={<Button size="small" onClick={() => void load(cur)}>重试</Button>}
          />
        ) : (
          <>
            {folders.map((f) => {
              const st = folderState(f)
              return (
                <div
                  key={f.path}
                  data-testid={`mp-dir-${f.path}`}
                  style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderBottom: '1px solid #f2f4f7' }}
                >
                  <Checkbox
                    checked={st === 'all'}
                    indeterminate={st === 'some'}
                    disabled={!f.files || folderBusy === f.path}
                    title={f.files ? '选中整个文件夹（含子目录）' : '空文件夹'}
                    onChange={() => void toggleFolder(f)}
                  />
                  <Button type="text" size="small" data-testid={`mp-dir-go-${f.path}`} onClick={() => void load(f.path, 1)}>
                    <span style={{ background: '#fff7e6', color: '#ad6800', borderRadius: 4, padding: '1px 5px', fontSize: 11, marginRight: 8 }}>目录</span>
                    <b style={{ marginRight: 8 }}>{f.name}</b>
                    <span style={{ color: '#999', fontSize: 12 }}>
                      {f.files} 个文件{f.has_children ? '（含子目录）' : ''}
                    </span>
                    <span style={{ color: '#1677ff', fontSize: 12, marginLeft: 8 }}>
                      {folderBusy === f.path ? '读取中…' : '打开 ›'}
                    </span>
                  </Button>
                </div>
              )
            })}
            {visibleFiles.map((m) => (
              <label
                key={m.path}
                data-testid={`mp-file-${m.path}`}
                title={rowTitle(m)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8, padding: '6px 10px',
                  borderBottom: '1px solid #f2f4f7', cursor: usable(m) ? 'pointer' : 'not-allowed',
                  opacity: usable(m) ? 1 : 0.55, background: selMap[m.path] ? '#eef6ff' : '#fff',
                }}
              >
                <Checkbox checked={!!selMap[m.path]} disabled={!usable(m)} onChange={() => toggle(m)} />
                <span style={{ fontSize: 13, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</span>
                <span style={{ fontSize: 12, color: '#666' }}>{KIND_LABEL[m.kind] || '其他'}</span>
                <span style={{ fontSize: 12, color: '#999', fontFamily: 'ui-monospace, Consolas, monospace' }}>{fmtSize(m.size)}</span>
                {!usable(m) && (
                  <span style={{ fontSize: 12, color: '#d4380d' }}>{isDoc(m) ? '需先在媒体库转可标注' : '不支持'}</span>
                )}
              </label>
            ))}
            {!folders.length && !visibleFiles.length && (
              <div style={{ padding: 22, textAlign: 'center', color: '#999', fontSize: 13 }}>
                {acceptKinds.length
                  ? `这个目录里没有${acceptKinds.map((k) => KIND_LABEL[k] || '其他').join(' / ')}文件；可以切到其他目录，或先到「媒体库」上传。`
                  : '这个目录是空的；先到「媒体库」上传文件。'}
              </div>
            )}
            {pageCount > 1 && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8, padding: 8, borderTop: '1px solid #f2f4f7', fontSize: 12 }}>
                <Button size="small" disabled={page <= 1 || loading} onClick={() => void load(cur, page - 1)}>上一页</Button>
                <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>第 {page} / {pageCount} 页</span>
                <Button size="small" disabled={page >= pageCount || loading} onClick={() => void load(cur, page + 1)}>下一页</Button>
                <span style={{ color: '#999' }}>每页 {PAGE_SIZE} 个 · 共 {filesTotal} 个</span>
              </div>
            )}
          </>
        )}
      </div>

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', padding: '6px 10px', borderTop: '1px solid #f0f0f0', background: '#fafafa', fontSize: 12 }}>
        <span>已选 <b data-testid="mp-picked-count" style={{ color: '#0f7b5f' }}>{picked.length}</b> 个</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexWrap: 'wrap', flex: 1, minWidth: 0 }}>
          {picked.slice(0, 4).map((m) => (
            <span key={m.path} style={{ display: 'inline-flex', alignItems: 'center', gap: 3, maxWidth: 180, background: '#fff', border: '1px solid #d9d9d9', borderRadius: 10, padding: '0 2px 0 7px' }}>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</span>
              <Button type="text" size="small" title={'移除 ' + m.name} aria-label="移除" style={{ padding: '0 4px' }} onClick={() => onChange(picked.filter((x) => x.path !== m.path))}>×</Button>
            </span>
          ))}
          {picked.length > 4 && <span style={{ color: '#999' }}>… 等 {picked.length} 个</span>}
          {!picked.length && <Typography.Text type="secondary">勾选上面的文件，可以跨目录多选</Typography.Text>}
        </div>
        {picked.length > 0 && <Button size="small" data-testid="mp-clear" onClick={() => onChange([])}>清空</Button>}
      </div>
      {bigText && (
        <div data-testid="mp-bignote" style={{ padding: '4px 10px 6px', fontSize: 12, color: '#999', borderTop: '1px dashed #f0f0f0' }}>
          选中的文本文件超过 200 KB，创建任务时只取前 200 KB。
        </div>
      )}
    </div>
  )
}
