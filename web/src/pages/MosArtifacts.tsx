import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button, Card, Input, Modal, Pagination, Space, Tag, Typography, message } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { EmptyState, ErrorState, LoadingState } from '../components/StateBlocks'
import {
  createMosJob, deleteArtifact, listArtifacts, listDeployments, registerModel,
} from '../api/mos'
import type { MosArtifact, MosArtifactList, MosDeploymentLite } from '../api/mos'

/**
 * 全部产物台账（MOS）——BUG-PARITY-12 落地页，重写 mos/platform/frontend/src/views/Artifacts.vue（279 行）。
 *
 * 去向方案（docs/交互对拍-任务页三页去向-20261102.md）：产物台账本批建独立页，
 * **入口只从训练任务页右上角「全部产物」进**（沿用老版 navItems.js:26 用户决策
 * 「产物挂任务详情；台账路由从任务页右上角进入」，不进侧栏菜单、不造第二套发现路径）。
 *
 * 对照锚点（Artifacts.vue 行号）：页头/产物根 3-19；三态 21-31（失败绝不画成「还没有产物」，
 * :24-26 注释原话）；最终产物卡 33-59（已部署回显 40-41/193-197）；平铺台账 61-105
 * （最终产物排最前 KIND_RANK :150-153、来源任务回链 :80-82）；「出可部署版本」链式量化
 * 89-95 + mergeIt 201-220（一键=融合导出→成功后自动接 AWQ 量化，走 POST /api/jobs
 * chain_quant/chain_calib_n=12 快版；正式定版 128 条校准在训练任务页——快/正式差异文案 :205-206）；
 * 登记弹窗 107-123；?register= 深链收端 169-184（任务/概览带产物路径进来自动开登记）；
 * 删除 409→force 二次确认 238-266。
 *
 * 部署去向：MOS 部署页属后续批次（MosModels.tsx:196 既有口径），本页只**回显**「已部署 :port」
 * （deployments 只读 listDeployments），不给假导航；「去部署」与 MosJobs 详情同一诚实文案。
 */

const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' }
// 最终产物排最前的口径照搬老版 KIND_RANK（Artifacts.vue:151）：awq > merged > output
const KIND_RANK: Record<string, number> = { awq: 0, merged: 1, output: 2 }
const baseName = (p?: string) => String(p || '').split('/').filter(Boolean).pop() || ''

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

export default function MosArtifacts() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [data, setData] = useState<MosArtifactList>({ root: '', items: [], counts: {}, active: {} })
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<(Error & { status?: number }) | null>(null)
  const [deps, setDeps] = useState<MosDeploymentLite[]>([])
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(12)
  const [merging, setMerging] = useState('')
  const [regOpen, setRegOpen] = useState(false)
  const [reg, setReg] = useState({ path: '', name: '', base: '', method: '', notes: '', kind: '' })
  // 深链只触发一次：load() 每 5 秒/每次操作都会重跑，参数还挂在 URL 上，不能每次都弹窗
  const regDeepLinkDone = useRef(false)

  /** 这份产物是不是正被某个部署实例用着（= 最终产物已发布；老版 deployedOf :193-197） */
  const deployedOf = useCallback((a: MosArtifact) => {
    const d = (deps || []).find((x) => x.model_path === a.path)
    return d || null
  }, [deps])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const r = await listArtifacts()
      setData(r)
      setLoadError(null)
      // 从训练任务/外链点「去登记模型 →」带 ?register=<产物路径>：直接打开登记弹窗
      // （老版 Artifacts.vue:179-183；没登记才弹，已登记给回显不重复弹）
      const want = String(searchParams.get('register') || '')
      if (!regDeepLinkDone.current && want) {
        regDeepLinkDone.current = true
        const hit = (r.items || []).find((a) => a.path === want)
        if (hit && !hit.registered_by) {
          setReg({ path: hit.path, name: hit.label || hit.name || '', base: '', method: hit.kind || '', notes: '', kind: hit.rel || '' })
          setRegOpen(true)
          message.info('已按深链打开该产物的登记弹窗')
        } else if (hit) {
          message.info(`该产物已登记为「${hit.registered_by}」，无需重复登记`)
        } else {
          // 诚实引导：路径对不上（产物被删/换环境）直说，不许静默
          message.warning('台账里没找到深链指定的产物目录，可能已被删除；可在下面列表里手动登记')
        }
      }
    } catch (e: any) {
      // 失败不能画成「还没有产物」：那会让用户以为产物被删了（Artifacts.vue:24-26）
      setLoadError(e)
    } finally {
      setLoading(false)
    }
    try { setDeps(await listDeployments()) } catch { setDeps([]) }
  }, [searchParams])

  useEffect(() => { void load() }, [load])

  const list = useMemo(() => [...(data.items || [])].sort((a, b) => (
    ((KIND_RANK[a.kind || ''] ?? 9) - (KIND_RANK[b.kind || ''] ?? 9))
    || String(b.mtime || '').localeCompare(String(a.mtime || ''))),
  ), [data])
  const finalItem = useMemo(() => (data.items || []).find((i) => i.kind === 'awq') || null, [data])
  const pageCount = Math.max(1, Math.ceil(list.length / pageSize))
  useEffect(() => { if (page > pageCount) setPage(pageCount) }, [page, pageCount])
  const paged = list.slice((page - 1) * pageSize, page * pageSize)

  // ── 一键出可部署版本：融合导出 →（成功后）自动接 AWQ 量化（Artifacts.vue:201-220）──
  function mergeIt(a: MosArtifact) {
    Modal.confirm({
      title: '出可部署版本',
      content: '这条适配器会：① 融合成完整模型；② 自动 AWQ 量化（校准 12 条，先快速出个能跑起来的版本）。\n'
        + `基座：${a.base_model || '训练环境默认'}\n产物会出现在下面「可部署产物」里。\n\n`
        + '要正式定版的量化（128 条校准）请到「训练任务」页用默认参数跑。',
      okText: '开始融合导出',
      onOk: async () => {
        setMerging(a.path)
        try {
          // chain_quant 走 POST /api/jobs（jobs.py:1146：产物页「出可部署版本」走的就是这个入口，
          // 并带同输入查重 409）；快版校准 12 条——正式定版 128 条在任务页量化阶段跑
          const r = await createMosJob({
            method: 'export', force: true,
            params: { base: a.base_model || '', adapters: a.path, chain_quant: true, chain_calib_n: 12 },
          })
          message.success('已发起：融合导出 ' + r.id + '（成功后自动接量化）')
          await load()
        } catch (e: any) { message.error('发起失败: ' + (e?.message || '')) }
        finally { setMerging('') }
      },
    })
  }

  function openReg(a: MosArtifact) {
    setReg({ path: a.path, name: a.label || a.name || '', base: '', method: a.kind || '', notes: '', kind: a.rel || '' })
    setRegOpen(true)
  }

  async function doRegister() {
    try {
      await registerModel({ name: reg.name, path: reg.path, base: reg.base, method: reg.method, notes: reg.notes })
      message.success(`已登记「${reg.name}」`)
      setRegOpen(false)
      await load()
    } catch (e: any) { message.error('登记失败: ' + (e?.message || '')) }
  }

  // 删除：409=还登记在模型库里，确认后才带 force（Artifacts.vue:249-265；
  // 只删磁盘会让模型行 path 悬空，部署/下载全失败且无法自愈）
  function drop(a: MosArtifact) {
    Modal.confirm({
      title: '删除产物',
      content: `删除产物「${a.rel || a.path}」？目录会从磁盘删除，不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteArtifact(a.path)
          message.success('已删除产物')
          await load()
        } catch (e: any) {
          if (e.status !== 409) { message.error('删除失败: ' + (e?.message || '')); return }
          new Promise<void>((resolve) => {
            Modal.confirm({
              title: '产物已登记为模型',
              content: `${e.message}\n\n继续删除会同时移除模型库里的登记；已发布的还会从推理端版本仓库撤销。`,
              okText: '仍然删除',
              okButtonProps: { danger: true },
              onOk: async () => {
                try {
                  const r = await deleteArtifact(a.path, true)
                  const removed = (r?.models_removed || []).length
                  message.success('已删除产物' + (removed ? `，并移除模型登记 ${removed} 条` : ''))
                  await load()
                } catch (e2: any) { message.error('删除失败: ' + (e2?.message || '')) }
                finally { resolve() }
              },
              onCancel: () => resolve(),
            })
          })
        }
      },
    })
  }

  function renderActions(a: MosArtifact, inFinalCard = false) {
    const act = data.active?.[a.path]
    return (
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: inFinalCard ? 10 : 6 }}>
        {a.adapter && (
          <Button
            size="small" type="primary"
            disabled={merging === a.path || !!act}
            title={act
              ? `已有任务在跑（${act.method === 'quant' ? '量化' : '融合'}），完成后再操作`
              : `一键：先融合成完整模型，再自动 AWQ 量化；基座 ${a.base_model || '按训练环境默认'}`}
            onClick={() => mergeIt(a)}
          >
            {merging === a.path ? '提交中…' : (act ? (act.method === 'quant' ? '量化中…' : '融合中…') : '出可部署版本')}
          </Button>
        )}
        {!a.adapter && a.registered_by && (
          <Button
            size="small"
            onClick={() => navigate(a.model_id
              ? `/mos/models?model=${encodeURIComponent(String(a.model_id))}` : '/mos/models')}
          >
            查看
          </Button>
        )}
        {!a.adapter && !a.registered_by && (
          <Button size="small" type="primary" onClick={() => openReg(a)}>登记入模型库</Button>
        )}
        <Button size="small" danger onClick={() => drop(a)}>删除</Button>
        {!inFinalCard && a.task_id && (
          <Button
            size="small" type="link"
            title="打开产出它的训练任务（含融合 / 量化阶段）"
            onClick={() => navigate(`/mos/jobs?job=${encodeURIComponent(String(a.task_id))}`)}
          >
            来源任务 →
          </Button>
        )}
      </div>
    )
  }

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
          <Space wrap>
            <Typography.Text strong>全部产物</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              产物根：<span style={MONO}>{data.root || '—'}</span>
            </Typography.Text>
          </Space>
          <Space wrap>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              本次训练的产物在任务详情的「产物链」里看；没有归属到任务的历史目录也在这里
            </Typography.Text>
            <Button size="small" onClick={() => navigate('/mos/jobs')}>← 训练任务</Button>
            <Button size="small" icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          </Space>
        </Space>
      </Card>

      {loadError ? (
        <ErrorState
          title={loadError.status === 403 ? '没有权限' : '产物没加载出来'}
          message={loadError.status === 403
            ? '当前账号没有模型训练（mos）通道权限（mos:access）。请联系管理员分配角色，或在「权限申请」提交申请。'
            : (loadError.message || '未知错误') + '（注意：这只是没读到，不代表产物被删了）'}
          onRetry={() => void load()}
        />
      ) : loading && !list.length ? (
        <LoadingState rows={6} />
      ) : !loading && !list.length ? (
        <EmptyState
          title="训练环境里还没有任何产物"
          hint="先发起一次训练/融合/量化任务；任务结束后这里会自动出现输出目录"
          action={<Button onClick={() => navigate('/mos/jobs')}>← 训练任务</Button>}
        />
      ) : null}

      {finalItem && !loadError && (
        <Card size="small" style={{ marginBottom: 12 }} data-testid="final-artifact">
          <Space style={{ width: '100%', justifyContent: 'space-between', alignItems: 'flex-start' }} wrap>
            <div style={{ minWidth: 0 }}>
              <Typography.Text strong>最终产物 · {finalItem.label || finalItem.name}</Typography.Text>
              <div style={{ fontSize: 12, color: '#888', marginTop: 4 }}>
                基座 {baseName(finalItem.base_model) || '—'} · {finalItem.size_gb} GB · {finalItem.mtime}
                {deployedOf(finalItem) ? <Tag color="green" style={{ marginLeft: 8 }}>已部署 :{deployedOf(finalItem)?.port}</Tag> : null}
              </div>
              <Typography.Text style={{ ...MONO, fontSize: 11, color: '#999' }} ellipsis={{ tooltip: finalItem.path }}>
                {finalItem.rel}
              </Typography.Text>
            </div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>部署 / 交付用这份</Typography.Text>
          </Space>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10 }}>
            {finalItem.model_id && (
              <Button size="small" onClick={() => navigate(`/mos/models?model=${encodeURIComponent(String(finalItem.model_id))}`)}>
                在模型库查看
              </Button>
            )}
            {deployedOf(finalItem) ? null : (finalItem.model_id ? (
              // 部署页属后续批次（MosModels.tsx:196 同口径）：诚实引导，不造死链导航
              <Button size="small" onClick={() => message.info('MOS 部署页正在迁移，后续批次开放（模型：' + finalItem.model_id + '）')}>
                去部署
              </Button>
            ) : renderActions(finalItem, true))}
          </div>
        </Card>
      )}

      {!loading && !loadError && list.length > 0 && (
        <Card
          size="small"
          title={(
            <Space wrap>
              <span>全部产物</span>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {list.length} 份 · 最终产物排最前
              </Typography.Text>
            </Space>
          )}
        >
          {paged.map((a) => (
            <div key={a.path} style={{ borderBottom: '1px dashed #f0f0f0', padding: '9px 0' }}>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
                <Typography.Text strong ellipsis={{ tooltip: a.path }} style={{ maxWidth: 320 }}>
                  {a.label || a.name}
                </Typography.Text>
                {a.adapter && <Tag>LoRA 适配器</Tag>}
                {a.registered_by
                  ? <Tag color="green">已登记 · {a.registered_by}</Tag>
                  : (!a.adapter && <Tag>未登记</Tag>)}
                {a.kind_label && <Tag color="default">{a.kind_label}</Tag>}
                {deployedOf(a) && <Tag color="blue">已部署 :{deployedOf(a)?.port}</Tag>}
              </div>
              <Kv k="基座" title={a.base_model}>{baseName(a.base_model) || '—'}</Kv>
              <div style={{ fontSize: 11, color: '#999', ...MONO }}>{a.rel} · {a.size_gb} GB · {a.mtime}</div>
              {renderActions(a)}
            </div>
          ))}
          <Pagination
            style={{ marginTop: 12, textAlign: 'right' }}
            current={page}
            pageSize={pageSize}
            total={list.length}
            showSizeChanger
            pageSizeOptions={[12, 24, 48]}
            onChange={(p, s) => { setPage(s !== pageSize ? 1 : p); setPageSize(s) }}
          />
        </Card>
      )}

      {/* 登记产物到模型库（对照 Artifacts.vue:107-123 + 任务详情同一弹窗字段） */}
      <Modal
        open={regOpen}
        title="登记产物到模型库"
        width={560}
        onCancel={() => setRegOpen(false)}
        onOk={() => void doRegister()}
        okText="登记"
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>产物目录</div>
            <Input value={reg.path} style={MONO} disabled />
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>模型名</div>
            <Input value={reg.name} onChange={(e) => setReg((p) => ({ ...p, name: e.target.value }))} />
          </div>
          <div style={{ display: 'flex', gap: 10 }}>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>基座</div>
              <Input value={reg.base} placeholder="留空可后补" onChange={(e) => setReg((p) => ({ ...p, base: e.target.value }))} />
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>方法</div>
              <Input value={reg.method} placeholder={reg.kind} onChange={(e) => setReg((p) => ({ ...p, method: e.target.value }))} />
            </div>
          </div>
          <div>
            <div style={{ fontSize: 12, color: '#888', marginBottom: 4 }}>备注</div>
            <Input value={reg.notes} onChange={(e) => setReg((p) => ({ ...p, notes: e.target.value }))} />
          </div>
        </div>
      </Modal>
    </div>
  )
}
