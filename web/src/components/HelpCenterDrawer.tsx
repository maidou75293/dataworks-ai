import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Drawer, Input, Spin, Tag, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { api } from '../api'
import MarkdownView from './MarkdownView'

/**
 * 帮助中心抽屉（REQ-HELP-NAV-1，项目负责人 2026-11-03「使用帮助加到右上角，里面包括 k8s 结构介绍，还有运维手册」）。
 * 内容来自 GET /api/help/tree + /api/help/doc（素材单一事实源 = 仓库 docs/帮助/）。
 * 目录树按「篇 → ## 章节」导航；搜索覆盖标题与正文；右上角版本角标显示手册生成日期。
 * 「平台使用帮助（产品操作）」按钮沿用 REQ-DW-19 的当前页锚点直达 /help#anchor。
 */

type Section = { id: string; title: string }
type TreeDoc = { path: string; title: string; manualDate: string; size: number; sections: Section[] }
type FullDoc = TreeDoc & { markdown: string; sources: string }

/** 把正文按「## 」切段，供逐节全文匹配（切法与后端章节编号一致）。 */
function splitBodies(md: string, sections: Section[]): Record<string, string> {
  const out: Record<string, string> = {}
  const parts = md.split('\n## ')
  out['__pre'] = parts[0]
  sections.forEach((s, i) => {
    out[s.id] = (i + 1 < parts.length ? parts[i + 1] : '')
  })
  return out
}

export default function HelpCenterDrawer(props: {
  open: boolean
  onClose: () => void
  onGotoBiz: () => void
}) {
  const [docs, setDocs] = useState<TreeDoc[]>([])
  const [cache, setCache] = useState<Record<string, FullDoc>>({})
  const [active, setActive] = useState('')
  const [kw, setKw] = useState('')
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!props.open || docs.length > 0 || loading || err) return
    ;(async () => {
      setLoading(true)
      try {
        const t = await api<{ docs: TreeDoc[] }>('/api/help/tree')
        setDocs(t.docs)
        if (t.docs[0]) setActive(t.docs[0].path)
        const map: Record<string, FullDoc> = {}
        await Promise.all(t.docs.map(async (d) => {
          map[d.path] = await api<FullDoc>('/api/help/doc?path=' + encodeURIComponent(d.path)) as FullDoc
        }))
        setCache(map)
      } catch (e: any) {
        setErr(String(e?.message || e))
      } finally {
        setLoading(false)
      }
    })()
  }, [props.open, docs.length, loading, err])

  // 搜索过滤：命中 篇标题 / 章节标题 / 该节正文 任一即入树；空搜索=全量目录
  const treeData = useMemo<DataNode[]>(() => {
    const k = kw.trim().toLowerCase()
    const nodes: DataNode[] = []
    for (const d of docs) {
      const full = cache[d.path]
      const bodies = full ? splitBodies(full.markdown, d.sections) : {}
      const titleHit = !k || d.title.toLowerCase().includes(k)
      const docBodyHit = !k || (full?.markdown || '').toLowerCase().includes(k)
      const secs = d.sections.filter((s) => {
        if (!k) return true
        if (s.title.toLowerCase().includes(k)) return true
        return (bodies[s.id] || '').toLowerCase().includes(k)
      })
      if (!k && titleHit) {
        nodes.push({
          key: d.path,
          title: `${d.title}`,
          children: d.sections.map((s) => ({ key: `${d.path}#${s.id}`, title: s.title, isLeaf: true })),
        })
        continue
      }
      if (titleHit || secs.length > 0 || docBodyHit) {
        const label = titleHit || secs.length > 0 ? d.title : `${d.title}（正文命中）`
        nodes.push({
          key: d.path,
          title: label,
          children: (titleHit ? d.sections : secs).map((s) => ({ key: `${d.path}#${s.id}`, title: s.title, isLeaf: true })),
        })
      }
    }
    return nodes
  }, [docs, cache, kw])

  // 搜索过滤后目录保持展开（defaultExpandAll 只在首次挂载生效，树数据一变就得显式给 keys）
  const expandedKeys = useMemo(() => treeData.map((n) => String(n.key)), [treeData])

  const select = (key: string) => {
    if (key.includes('#')) {
      const [path, id] = key.split('#')
      setActive(path)
      setTimeout(() => {
        document.getElementById(`hc-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }, 120)
    } else {
      setActive(key)
    }
  }

  const doc = cache[active]
  const badge = docs.find((d) => d.path === active)?.manualDate || docs[0]?.manualDate || ''

  return (
    <Drawer
      open={props.open}
      onClose={props.onClose}
      width={1040}
      className="help-center-drawer"
      title="使用帮助 · 帮助中心"
      extra={
        <Button size="small" className="help-goto-biz" onClick={props.onGotoBiz}>
          平台使用帮助（产品操作）
        </Button>
      }
    >
      <div style={{ display: 'flex', gap: 12, height: '100%' }}>
        <div style={{ width: 250, flexShrink: 0, borderRight: '1px solid var(--ant-color-split, #eee)', paddingRight: 8 }}>
          <Input.Search
            allowClear
            placeholder="搜索目录与正文"
            className="help-search"
            value={kw}
            onChange={(e) => setKw(e.target.value)}
          />
          <div style={{ marginTop: 8, overflow: 'auto' }}>
            {treeData.length === 0 && !loading && <Typography.Text type="secondary">没有匹配条目</Typography.Text>}
            <Tree
              treeData={treeData}
              expandedKeys={expandedKeys}
              selectedKeys={active ? [active] : []}
              onSelect={(keys) => keys[0] && select(String(keys[0]))}
            />
          </div>
        </div>
        <div style={{ flex: 1, overflow: 'auto' }} className="help-doc-body">
          {err && (
            <Alert
              type="warning"
              showIcon
              message="帮助内容接口未就绪"
              description={`读取 /api/help 失败：${err}。帮助中心随本版本后端部署生效；产品操作类帮助可点右上角「平台使用帮助」。`}
            />
          )}
          {loading && !err && <Spin style={{ display: 'block', margin: '40px auto' }} />}
          {doc && !loading && (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                <Typography.Title level={5} style={{ margin: 0 }}>{doc.title}</Typography.Title>
                {/* 版本角标：手册生成日期（判据「版本角标显示手册生成日期」） */}
                <Tag color="blue" className="help-manual-badge">手册 {badge}</Tag>
              </div>
              {doc.sources && (
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 10 }}>
                  内容提炼自在册文档：{doc.sources}
                </Typography.Paragraph>
              )}
              <MarkdownView text={doc.markdown} idPrefix="hc" />
            </>
          )}
        </div>
      </div>
    </Drawer>
  )
}
