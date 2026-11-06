import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import {
  Alert, AutoComplete, Button, Card, Divider, Dropdown, Form, Input, Modal,
  Pagination, Space, Tag, Tooltip, Typography, message,
} from 'antd'
import type { MenuProps } from 'antd'
import { CopyOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import {
  deleteModel, listModels, modelManifest, mosChannelUrl, publishModel, reconcileModel,
  registerModel, rescanModel, suggestions, unpublishModel,
} from '../api/mos'
import type { MosModel, MosModelManifest, MosReconcile, MosSuggestions } from '../api/mos'

/**
 * 模型资产（MOS）——REQ-UNIFY-2 重写模板页。
 * 功能对照 mos/platform/frontend/src/views/Models.vue（272 行）：
 * 列表/统计 chips/登记/能力对账/重扫/下载链接/发布/撤销/删除/深链定位（?model=）。
 * v437 UX 口径：三态铺满（LoadingState/ErrorState/EmptyState）、面包屑由 MainLayout
 * 按 NAV 自动渲染、卡片右键上下文菜单（Dropdown trigger=contextMenu）。
 */

const STATUS_LABEL: Record<string, string> = { draft: '草稿', candidate: '候选', active: '现役', archived: '归档' }
const STATUS_COLOR: Record<string, string> = { draft: 'default', candidate: 'gold', active: 'green', archived: 'default' }
// 与 Models.vue:139 同一套能力色带，跨端认得出是同一信息
const CAP_COLORS = ['#1677ff', '#722ed1', '#00a870', '#fa8c16', '#f53f3f', '#13c2c2']

const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }

function capStr(c?: Record<string, number>): string {
  const ks = Object.keys(c || {})
  return ks.length ? ks.map((k) => `${k}:${c?.[k]}`).join(' ') : '—'
}

/** 一行的「键：值」，值超宽省略号 + title 悬浮全文（对照 Vue 的 .kv/.ell）。 */
function Kv({ k, title, children }: { k: string; title?: string; children: ReactNode }) {
  return (
    <div style={{ display: 'flex', gap: 8, fontSize: 12, lineHeight: '22px' }}>
      <span style={{ color: '#888', flex: '0 0 60px' }}>{k}</span>
      <span title={title} style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
        {children}
      </span>
    </div>
  )
}

export default function MosModels() {
  const [searchParams] = useSearchParams()
  const [models, setModels] = useState<MosModel[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<(Error & { status?: number }) | null>(null)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)
  // 能力对账 / 下载链接的按卡片结果（Vue 里的 rec/dl 映射）
  const [rec, setRec] = useState<Record<string, MosReconcile>>({})
  const [dl, setDl] = useState<Record<string, MosModelManifest>>({})
  const [sug, setSug] = useState<MosSuggestions>({})
  const [showForm, setShowForm] = useState(false)
  const [registering, setRegistering] = useState(false)
  const [highlightId, setHighlightId] = useState('')
  const focusedRef = useRef('')
  const [form] = Form.useForm<{ name: string; path: string; base?: string; method?: string; notes?: string }>()

  const load = useCallback(async () => {
    setLoading(true)
    try {
      setModels(await listModels())
      setLoadError(null)
    } catch (e: any) {
      setLoadError(e)
      message.error('加载失败: ' + (e?.message || '未知错误'))
    } finally {
      setLoading(false)
    }
    // 候选值失败不影响主流程（与 Models.vue load() 的 try/catch 口径一致）
    try { setSug(await suggestions()) } catch { /* ignore */ }
  }, [])

  useEffect(() => { void load() }, [load])

  const activeCount = useMemo(() => models.filter((m) => m.status === 'active').length, [models])
  const pageCount = Math.max(1, Math.ceil(models.length / pageSize))
  // 列表变少/每页条数变大时当前页可能已不存在，退回最后一页（对照 usePager 的 watch）
  useEffect(() => { if (page > pageCount) setPage(pageCount) }, [page, pageCount])
  const paged = models.slice((page - 1) * pageSize, page * pageSize)

  // 深链定位：/mos/models?model=mdl_xxx 翻到目标所在页并高亮 4.5s（对照 Models.vue focusFromQuery）
  useEffect(() => {
    const id = searchParams.get('model') || ''
    if (!id || loading || models.length === 0 || focusedRef.current === id) return
    focusedRef.current = id
    setHighlightId(id)
    const idx = models.findIndex((m) => m.id === id)
    if (idx >= 0) setPage(Math.floor(idx / pageSize) + 1)
    window.setTimeout(() => {
      document.getElementById('model-' + id)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    }, 60)
    window.setTimeout(() => setHighlightId((cur) => (cur === id ? '' : cur)), 4500)
  }, [searchParams, models, loading, pageSize])

  const dirCands = useMemo(() => {
    const seen = new Set<string>()
    const out: { value: string; label: string }[] = []
    for (const c of [...(sug.adapters || []), ...(sug.base_models || [])]) {
      if (!seen.has(c.v)) { seen.add(c.v); out.push({ value: c.v, label: c.l }) }
    }
    return out
  }, [sug])

  async function submitRegister() {
    if (registering) return
    let values
    try { values = await form.validateFields() } catch { return }
    setRegistering(true)
    try {
      const r = await registerModel(values)
      setShowForm(false)
      form.resetFields()
      message.success(`已登记 ${r.id}（${r.scan?.size_gb ?? '—'} GB · ${r.scan?.keys_total ?? '—'} 张量）`)
      await load()
    } catch (e: any) {
      message.error('登记失败: ' + (e?.message || '未知错误'))
    } finally {
      setRegistering(false)
    }
  }

  async function doRecon(m: MosModel) {
    try {
      const r = await reconcileModel(m.id)
      setRec((p) => ({ ...p, [m.id]: r }))
      if (r.ok) message.success('能力完整')
      else message.error(r.verdict)
    } catch (e: any) { message.error(e?.message || '能力对账失败') }
  }

  async function doRescan(m: MosModel) {
    try {
      const info = await rescanModel(m.id)
      await load()
      message.success(`重扫完成：${info.size_gb} GB / ${info.keys_total} 张量`)
    } catch (e: any) { message.error(e?.message || '重扫失败') }
  }

  async function doDl(m: MosModel) {
    try {
      const r = await modelManifest(m.id)
      setDl((p) => ({ ...p, [m.id]: r }))
    } catch (e: any) { message.error(e?.message || '下载链接获取失败') }
  }

  async function doPublish(m: MosModel) {
    try {
      const r = await publishModel(m.id, { status: 'candidate' })
      message.success(`已发布 ${r.dest_name}（${r.mode}）→ 可到 StreamVision 配置页选它`)
      await load()
    } catch (e: any) { message.error(e?.message || '发布失败') }
  }

  function doUnpublish(m: MosModel) {
    Modal.confirm({
      title: '撤销发布',
      content: `从推理端版本仓库撤销「${m.name}」？（软链会删，源目录不动）`,
      okText: '撤销',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await unpublishModel(m.id, m.version || '')
          message.success('已撤销')
          await load()
        } catch (e: any) { message.error(e?.message || '撤销发布失败') }
      },
    })
  }

  function doRemove(m: MosModel) {
    Modal.confirm({
      title: '从台账移除',
      content: `从台账移除「${m.name}」？（不动磁盘文件）`
        + (m.version ? `\n它已发布（${m.version}）：会同时从推理端版本仓库撤销，软链删除。` : ''),
      okText: '移除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          const r = await deleteModel(m.id)
          const n = r?.released?.removed?.length || 0
          message.success('已移除' + (n ? `，并撤销 ${n} 个已发布版本` : ''))
          await load()
        } catch (e: any) { message.error(e?.message || '移除失败') }
      },
    })
  }

  function goDeploy(m: MosModel) {
    // 部署页 /mos/deploy 属后续批次重写卡（REQ-UNIFY-3+）；本批不挂死链
    message.info(`MOS 部署页正在迁移，稍后可在此选择模型「${m.name}」（${m.id}）部署`)
  }

  function copyText(t: string) {
    navigator.clipboard.writeText(t).then(
      () => message.success('已复制'),
      () => message.error('复制失败，请手动选择'),
    )
  }

  /** 卡片右键上下文菜单：与底部按钮同源的动作集合（v437 右键基准）。 */
  const cardMenu = (m: MosModel): MenuProps => ({
    items: [
      { key: 'recon', label: '能力对账' },
      { key: 'rescan', label: '重扫' },
      { key: 'dl', label: '下载链接' },
      { key: 'deploy', label: '去部署' },
      { type: 'divider' },
      { key: 'publish', label: m.version ? '撤销发布' : '发布到推理端' },
      { key: 'remove', label: '从台账移除', danger: true },
    ],
    onClick: ({ key }) => {
      if (key === 'recon') void doRecon(m)
      else if (key === 'rescan') void doRescan(m)
      else if (key === 'dl') void doDl(m)
      else if (key === 'deploy') goDeploy(m)
      else if (key === 'publish') { if (m.version) doUnpublish(m); else void doPublish(m) }
      else if (key === 'remove') doRemove(m)
    },
  })

  function renderCard(m: MosModel) {
    const caps = m.capabilities || {}
    const capKeys = Object.keys(caps)
    const capTotal = capKeys.reduce((s, k) => s + (Number(caps[k]) || 0), 0) || 1
    const manifest = dl[m.id]
    const reconcile = rec[m.id]
    return (
      <Dropdown key={m.id} trigger={['contextMenu']} menu={cardMenu(m)}>
        <Card
          size="small"
          hoverable
          id={`model-${m.id}`}
          style={highlightId === m.id
            ? { borderColor: '#1677ff', boxShadow: '0 0 0 3px rgba(22,119,255,.14)' }
            : undefined}
          title={<span style={{ fontWeight: 600, fontSize: 14 }}>{m.name}</span>}
          extra={(
            <Space size={4} wrap>
              <Tag color={STATUS_COLOR[m.status] || 'default'}>{STATUS_LABEL[m.status] || m.status}</Tag>
              {m.version ? (
                <Tag title={`已定版并收进 models/releases/${m.name}-${m.version}`}>{m.version}</Tag>
              ) : (
                <Tag color="orange" title="未定版：还在训练产物目录里，会被下次训练覆盖；想长期留住就点「发布到推理端」">未定版</Tag>
              )}
            </Space>
          )}
        >
          <div style={{ ...MONO, fontSize: 11, color: '#999' }}>{m.id}</div>
          <Divider style={{ margin: '8px 0' }} />
          <Kv k="体积" title={m.size_gb == null ? undefined : `${m.size_gb} GB`}>
            <span style={MONO}>{m.size_gb == null ? '—' : `${m.size_gb} GB`}</span>
          </Kv>
          <Kv k="张量"><span style={MONO}>{m.keys_total ?? '—'}</span></Kv>
          <Kv k="架构" title={m.architectures || undefined}>{m.architectures || '—'}</Kv>
          {m.method && <Kv k="方法">{m.method}</Kv>}
          {m.job_id && (
            <Kv k="来源任务" title="训练任务页迁移中，暂以文本展示任务号">
              <span style={MONO}>{m.job_id}</span>
            </Kv>
          )}
          <Kv k="目录" title={m.path || undefined}><span style={{ color: '#888' }}>{m.path || '—'}</span></Kv>

          <div style={{ margin: '10px 0 6px', fontSize: 12, color: '#888' }}>能力</div>
          {capKeys.length > 0 ? (
            <>
              <div style={{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', background: '#f0f0f0' }}>
                {capKeys.map((k, i) => (
                  <span
                    key={k}
                    title={`${k}: ${caps[k]}`}
                    style={{ width: `${Math.max(3, Math.round((caps[k] / capTotal) * 100))}%`, background: CAP_COLORS[i % CAP_COLORS.length] }}
                  />
                ))}
              </div>
              <Space size={4} wrap style={{ marginTop: 6 }}>
                {capKeys.map((k, i) => (
                  <Tag key={k} style={{ fontSize: 11 }}>
                    <span style={{ display: 'inline-block', width: 6, height: 6, borderRadius: 3, background: CAP_COLORS[i % CAP_COLORS.length], marginRight: 4 }} />
                    {k} {caps[k]}
                  </Tag>
                ))}
              </Space>
            </>
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>（无多模态能力张量）</Typography.Text>
          )}

          {reconcile && (
            <Alert
              style={{ marginTop: 8 }}
              type={reconcile.ok ? 'success' : 'warning'}
              showIcon
              message={reconcile.verdict}
              description={!reconcile.ok && (
                <div style={{ fontSize: 12 }}>
                  <div>基座：{capStr(reconcile.base?.caps)}</div>
                  <div>产物：{capStr(reconcile.product?.caps)}</div>
                </div>
              )}
            />
          )}

          {manifest && (
            <div style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 8, marginTop: 8, fontSize: 12 }}>
              <b>下载链接</b>
              <div style={{ color: '#888', margin: '2px 0 6px' }}>
                整包 {manifest.total_gb ?? '—'} GB / {manifest.file_count ?? '—'} 文件
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 4 }}>
                <span style={{ ...MONO, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                  {location.origin}{manifest.archive_url ? mosChannelUrl(manifest.archive_url) : '—'}
                </span>
                <Tooltip title="复制整包下载链接">
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    disabled={!manifest.archive_url}
                    onClick={() => copyText(`${location.origin}${manifest.archive_url ? mosChannelUrl(manifest.archive_url) : ''}`)}
                  />
                </Tooltip>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ ...MONO, color: '#888', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
                  {location.origin}{mosChannelUrl(`/api/models/${m.id}/manifest`)}
                </span>
                <Tooltip title="复制清单地址">
                  <Button
                    size="small"
                    icon={<CopyOutlined />}
                    onClick={() => copyText(`${location.origin}${mosChannelUrl(`/api/models/${m.id}/manifest`)}`)}
                  />
                </Tooltip>
              </div>
            </div>
          )}

          <Space size={4} wrap style={{ marginTop: 10 }}>
            <Button size="small" onClick={() => void doRecon(m)}>能力对账</Button>
            <Button size="small" onClick={() => void doRescan(m)}>重扫</Button>
            <Button size="small" onClick={() => void doDl(m)}>下载链接</Button>
            <Tooltip title="部署页在后续批次迁移，当前点击给出提示">
              <Button size="small" onClick={() => goDeploy(m)}>去部署</Button>
            </Tooltip>
            {m.version ? (
              <Button size="small" onClick={() => doUnpublish(m)}>撤销发布</Button>
            ) : (
              <Button size="small" type="primary" onClick={() => void doPublish(m)}>发布到推理端</Button>
            )}
            <Button size="small" danger onClick={() => doRemove(m)}>删除</Button>
          </Space>
        </Card>
      </Dropdown>
    )
  }

  return (
    <div>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }} wrap>
        <Space wrap>
          <Tag>共 {models.length} 个模型</Tag>
          <Tag color="green">现役 {activeCount}</Tag>
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setShowForm(true)}>登记模型</Button>
        </Space>
      </Space>

      {/* 三态铺满（v437 / DW-22 模板）：错误 > 首屏加载 > 空态 > 内容 */}
      {loadError ? (
        <ErrorState
          title={loadError.status === 403 ? '没有权限' : '模型列表加载失败'}
          message={loadError.status === 403
            ? '当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。'
            : loadError.message || '未知错误'}
          onRetry={() => void load()}
        />
      ) : loading && models.length === 0 ? (
        <LoadingState rows={6} />
      ) : models.length === 0 ? (
        <EmptyState
          title="还没有登记模型"
          hint="训练产出后点「登记模型」；登记即自动扫描体积/分片/张量/能力"
          action={(
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setShowForm(true)}>登记模型</Button>
          )}
        />
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
            {paged.map(renderCard)}
          </div>
          <Pagination
            style={{ marginTop: 12, textAlign: 'right' }}
            current={page}
            pageSize={pageSize}
            total={models.length}
            showSizeChanger
            pageSizeOptions={[12, 24, 48]}
            onChange={(p, s) => { setPage(s !== pageSize ? 1 : p); setPageSize(s) }}
          />
        </>
      )}

      <Modal
        open={showForm}
        title="登记模型"
        width={640}
        okText={registering ? '登记中…' : '登记并扫描'}
        confirmLoading={registering}
        onCancel={() => setShowForm(false)}
        onOk={() => void submitRegister()}
      >
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '名称必填' }]}>
            <Input placeholder="如：MiniCPM-o LoRA-merged" />
          </Form.Item>
          <Form.Item
            name="path"
            label="模型目录"
            rules={[{ required: true, message: '模型目录必填' }]}
            extra="登记即自动扫描体积/分片/张量/能力；下拉是训练产物与本机目录候选"
          >
            <AutoComplete options={dirCands} placeholder="models/…" />
          </Form.Item>
          <Form.Item name="base" label="基座（能力对账用）">
            <AutoComplete
              options={(sug.base_models || []).map((c) => ({ value: c.v, label: c.l }))}
              placeholder="models/MiniCPM-o-4_5"
            />
          </Form.Item>
          <Form.Item name="method" label="方法">
            <Input placeholder="如 LoRA(SFT)→merge(bf16)" />
          </Form.Item>
          <Form.Item name="notes" label="备注">
            <Input />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
