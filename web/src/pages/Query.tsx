import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Alert, Button, Card, Modal, Select, Space, Table, Tooltip, Typography, message } from 'antd'
import { CopyOutlined, DownloadOutlined, PlayCircleOutlined } from '@ant-design/icons'
import { api, classifySql, createChange, post } from '../api'
import PageHeader from '../components/PageHeader'
import CodeEditor from '../components/CodeEditor'
import ErrorText from '../components/ErrorText'
import { EmptyState, ErrorState, tableEmpty } from '../components/StateBlocks'

// 新装集群可能还没有任何业务表，默认 SQL 用不依赖具体库的语句
const DEFAULT_SQL = 'SELECT 1 AS ok'

/** CSV 单元格：含逗号、引号、换行时按 RFC 4180 加引号。 */
function csvCell(value: unknown) {
  const text = value == null ? '' : String(value)
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

/** 加 BOM 让 Excel 直接认 UTF-8，避免中文变乱码。 */
function toCsv(headers: string[], data: unknown[][]) {
  return '﻿' + [headers, ...data]
    .map((row) => row.map(csvCell).join(','))
    .join('\r\n')
}

function downloadText(filename: string, text: string) {
  const blob = new Blob([text], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

/** http 页面没有 navigator.clipboard，退回 execCommand 才能真的复制成功。 */
async function copyText(text: string) {
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    const area = document.createElement('textarea')
    area.value = text
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    let ok = false
    try {
      ok = document.execCommand('copy')
    } catch {
      ok = false
    }
    document.body.removeChild(area)
    return ok
  }
}

type DorisResult = {
  columns?: string[]
  rows?: string[][]
  rowCount: number
  elapsedMs?: number
  maxRows?: number
  target?: string
  /** v478：本响应实际被掩码/令牌化的列名 */
  maskedColumns?: string[]
  tokenizedColumns?: string[]
  /** 守卫解析出的表（用于「申请明文」深链） */
  guard?: { tables?: { schema?: string; table?: string }[] }
  /** V99：实际执行引擎（固定 doris）与「涉及 N 个数据源」提示 */
  engine?: string
  engineHint?: string | null
  engineDatasources?: string[]
}

type ProjectOption = { id: number; code: string; name: string }

/** V99：GET /api/query/capabilities 的数据源能力（可查 / 仅导入）。 */
type CapabilityDatasource = {
  id: number
  name: string
  type: string
  engine: string
  queryable: boolean
  projectId?: number
  /** 可用于匹配 SQL 库前缀的别名：数据源名、库名、JDBC URL 里的库名 */
  databases?: string[]
  /** 仅导入的原因（Kafka / 文件类） */
  reason?: string
}

type Capabilities = {
  engine: string
  queryable: CapabilityDatasource[]
  notQueryable: CapabilityDatasource[]
  tablePrefixes: string[]
  notQueryablePrefixes?: string[]
  note?: string
}

/**
 * 从 SQL 里粗取表引用（只用于执行前提示，不参与安全判定；真正的拦截在后端）。
 * 支持 `table` / `db.table` / `catalog.db.table`、反引号/双引号、from/join 两种关键字。
 */
function sqlTableRefs(text: string): { catalog?: string; schema?: string; table: string }[] {
  const refs: { catalog?: string; schema?: string; table: string }[] = []
  const pattern = /\b(?:from|join)\s+[`"]?([A-Za-z0-9_]+)[`"]?(?:\s*\.\s*[`"]?([A-Za-z0-9_]+)[`"]?)?(?:\s*\.\s*[`"]?([A-Za-z0-9_]+)[`"]?)?/gi
  let match: RegExpExecArray | null
  while ((match = pattern.exec(text)) !== null) {
    if (match[3]) refs.push({ catalog: match[1], schema: match[2], table: match[3] })
    else if (match[2]) refs.push({ schema: match[1], table: match[2] })
    else refs.push({ table: match[1] })
  }
  return refs
}

export default function Query() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [sql, setSql] = useState(DEFAULT_SQL)
  const [columns, setColumns] = useState<any[]>([])
  const [rows, setRows] = useState<any[]>([])
  const [loading, setLoading] = useState(false)
  const [executed, setExecuted] = useState(false)
  const [error, setError] = useState('')
  const [errorStatus, setErrorStatus] = useState<number | undefined>(undefined)
  const [meta, setMeta] = useState('')
  const [target, setTarget] = useState('')
  const [maskedColumns, setMaskedColumns] = useState<string[]>([])
  const [tokenizedColumns, setTokenizedColumns] = useState<string[]>([])
  const [guardTables, setGuardTables] = useState<{ schema?: string; table?: string }[]>([])
  // 项目决定连哪个 Doris：空 = 平台内置；有外部连接的项目走自己的实例
  const [projects, setProjects] = useState<ProjectOption[]>([])
  const [projectId, setProjectId] = useState<number | undefined>(undefined)
  // V99：可查 / 仅导入数据源。拿不到时页面照常可用（后端仍会兜底校验）
  const [capabilities, setCapabilities] = useState<Capabilities | null>(null)

  useEffect(() => {
    api<ProjectOption[]>('/api/projects')
      .then((list) => setProjects(Array.isArray(list) ? list : []))
      .catch(() => setProjects([]))
  }, [])

  // 表详情「到查询页」会带 ?sql=...&projectId=...；读取一次后从地址栏移除，
  // 否则刷新页面会用旧 SQL 覆盖用户刚改过的内容。
  useEffect(() => {
    const incoming = searchParams.get('sql')
    const incomingProject = searchParams.get('projectId')
    if (!incoming && !incomingProject) return
    if (incoming) {
      setSql(incoming)
      setExecuted(false)
      setError('')
      setErrorStatus(undefined)
    }
    if (incomingProject) {
      const id = Number(incomingProject)
      if (Number.isFinite(id) && id > 0) setProjectId(id)
    }
    const next = new URLSearchParams(searchParams)
    next.delete('sql')
    next.delete('projectId')
    setSearchParams(next, { replace: true })
  }, [searchParams, setSearchParams])

  useEffect(() => {
    let alive = true
    const path = projectId == null
      ? '/api/query/capabilities'
      : `/api/query/capabilities?projectId=${encodeURIComponent(projectId)}`
    api<Capabilities>(path)
      .then((res) => { if (alive) setCapabilities(res) })
      .catch(() => { if (alive) setCapabilities(null) })
    return () => { alive = false }
  }, [projectId])

  /**
   * 执行前提示：按 SQL 里的库前缀估算「涉及 N 个数据源」。
   * 匹配不到已登记数据源时按 1 个（平台内置 Doris）计；解析不出表（如 SELECT 1）则不提示。
   */
  const capabilityView = useMemo(() => {
    const refs = sqlTableRefs(sql)
    const all = [...(capabilities?.queryable || []), ...(capabilities?.notQueryable || [])]
    const aliasesOf = (ds: CapabilityDatasource) =>
      (ds.databases && ds.databases.length ? ds.databases : [ds.name])
    const matched = new Map<number, CapabilityDatasource>()
    for (const ref of refs) {
      // V100：三段式 catalog.db.table 先按 Catalog 名匹配湖数据源
      const needle = (ref.catalog || ref.schema || ref.table).toLowerCase()
      for (const ds of all) {
        const hit = (ref.catalog || ref.schema)
          ? aliasesOf(ds).some((alias) => alias.toLowerCase() === needle)
          : ds.name.toLowerCase() === needle
        if (hit) matched.set(ds.id, ds)
      }
    }
    const list = [...matched.values()]
    const blocked = list.filter((ds) => ds.queryable === false)
    return {
      count: list.length > 0 ? list.length : (refs.length > 0 ? 1 : 0),
      names: list.map((ds) => ds.name),
      // 提交前判定要带 datasourceId：优先用 SQL 实际命中的数据源，避免拿错项目开关
      ids: list.map((ds) => ds.id),
      blocked,
    }
  }, [sql, capabilities])

  /**
   * 提交前判定用的数据源：SQL 命中一个已知数据源就用它；否则按当前项目挑一个可查的
   * 数据源（优先 Doris）。拿不到就跳过判定，真正的只读/权限校验仍在后端。
   */
  const changeDatasourceId = useMemo(() => {
    const queryable = capabilities?.queryable || []
    const matched = capabilityView.ids
      .map((id) => queryable.find((ds) => ds.id === id))
      .filter((ds): ds is CapabilityDatasource => Boolean(ds))
    const candidates = matched.length > 0
      ? matched
      : queryable.filter((ds) => projectId == null || ds.projectId === projectId)
    const doris = candidates.find((ds) =>
      (ds.type || '').toUpperCase() === 'DORIS' || (ds.engine || '').toUpperCase() === 'DORIS')
    return (doris || candidates[0])?.id
  }, [capabilities, capabilityView.ids, projectId])

  // 生产库（_prd）只读提示：查询页本身不会写数据，但要避免用户以为这里能做生产变更
  const mentionsPrd = useMemo(() => /_prd\b/i.test(sql), [sql])

  const run = async () => {
    if (!sql.trim()) {
      message.warning('先输入 SQL')
      return
    }
    // Ctrl+Enter 不经过按钮的 loading 态，慢查询期间会并发发出第二个请求，
    // 后返回的旧结果会覆盖新结果
    if (loading) return
    // SQL 编辑器提交拦截（007 第八节）：查询页虽然只读，用户仍会在这里试破坏性语句。
    // 命中审批规则就引导去建变更单，而不是让用户先撞上「只允许 SELECT/WITH」再自己找入口。
    if (changeDatasourceId != null) {
      try {
        const verdict = await classifySql(changeDatasourceId, sql, projectId)
        if (verdict.needReview) {
          Modal.confirm({
            title: '该 SQL 需要审批',
            content: `命中规则：${verdict.reason}。查询页不执行破坏性语句；可以直接提交变更单，`
              + '审批通过后由平台执行（DROP / TRUNCATE 单表会先自动备份）。',
            okText: '提交变更单',
            cancelText: '不提交',
            onOk: async () => {
              try {
                const change = await createChange(changeDatasourceId, sql, projectId)
                message.success(`已生成变更单 #${change.id}（${change.status}），等待审批`)
              } catch (e: any) {
                message.error(e?.message || '提交变更单失败')
                throw e
              }
            },
          })
          return
        }
      } catch {
        // 判定接口不可用/当前角色无 change:submit 时不阻塞查询：
        // 查询页的只读校验与权限判定仍在后端执行路径上兜底。
      }
    }
    setLoading(true)
    setError('')
    setErrorStatus(undefined)
    try {
      const res = await post<DorisResult>('/api/query/doris', { sql, maxRows: 200, projectId })
      // 空结果集时 Doris 可能不返回列名，此时不要伪造 col1
      const names: string[] = res.columns && res.columns.length
        ? res.columns
        : (res.rows && res.rows.length ? ['col1'] : [])
      // 先算掩码列再渲染表头：之前 maskedSet 在 setColumns 之后才声明，
      // map 回调同步执行会撞上 TDZ（ReferenceError），整页结果直接不显示
      const masked = res.maskedColumns || []
      const maskedSet = new Set(masked)
      setColumns(names.map((c, i) => ({
        // v480：敏感列加锁图标（TOKEN 列仍可分组/关联，但读不到明文）
        title: c ? (maskedSet.has(c) ? `${c} 🔒` : c) : `col${i + 1}`,
        dataIndex: String(i),
        key: String(i),
        ellipsis: true,
      })))
      setRows((res.rows || []).map((r: string[], i: number) => {
        const row: any = { key: i }
        r.forEach((v, j) => { row[String(j)] = v })
        return row
      }))
      const elapsed = typeof res.elapsedMs === 'number' ? ` · 耗时 ${res.elapsedMs} ms` : ''
      const cap = res.maxRows ?? 200
      const truncated = res.rowCount >= cap
      // V99：后端会回报实际执行说明（JDBC 多源 join 时是「涉及 N 个数据源…」）
      const engineNote = res.engineHint ? ` · ${res.engineHint}` : ''
      setMeta(`返回 ${res.rowCount} 行${elapsed}${truncated ? ` · 已达上限 ${cap} 行，结果已截断` : ''}${engineNote}`)
      setTarget(res.target || '')
      setMaskedColumns(masked)
      setTokenizedColumns(res.tokenizedColumns || [])
      setGuardTables(res.guard?.tables || [])
      setExecuted(true)
    } catch (e: any) {
      setColumns([])
      setRows([])
      setMeta('')
      setMaskedColumns([])
      setTokenizedColumns([])
      setGuardTables([])
      setExecuted(true)
      const raw = e?.message || '查询失败'
      setErrorStatus(typeof e?.status === 'number' ? e.status : undefined)
      // 底层 socket 超时对用户没有意义，翻成人话；其余交给 ErrorText 做「原因 + 下一步」
      setError(/timed?\s*out|timeout/i.test(raw)
        ? `查询超时（服务端 60 秒上限），请缩小时间范围或加过滤条件后重试。原始错误：${raw}`
        : raw)
    } finally {
      setLoading(false)
    }
  }

  const resultHeaders = columns.map((column: any) => String(column.title))
  const resultData = rows.map((row: any) => (
    columns.map((_: any, index: number) => row[String(index)])
  ))

  const exportCsv = () => {
    if (!rows.length) return
    const stamp = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, '')
    downloadText(`doris-query-${stamp}.csv`, toCsv(resultHeaders, resultData))
    message.success(`已导出 ${rows.length} 行（不是当前分页）`)
  }

  const copyResult = async () => {
    if (!rows.length) return
    const text = [resultHeaders, ...resultData]
      .map((row) => row.map((value) => (value == null ? '' : String(value))).join('\t'))
      .join('\n')
    const ok = await copyText(text)
    if (ok) message.success(`已复制 ${rows.length} 行，可直接粘贴到 Excel`)
    else message.error('浏览器不允许自动复制，请手动选中表格复制')
  }

  return (
    <div>
      <PageHeader
        title="数据查询（Doris）"
        description="只读 SQL 控制台，最多返回 200 行；适合临时排查与结果预览。内表用 db.table，Iceberg 湖表用 catalog.db.table（Catalog 由「数据源 → Iceberg 湖」维护）。"
      />
      <Card
        title={<Space size={8}>
          <span>SQL</span>
          <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
            只读 · 只允许 SELECT / WITH · 补全只列可查询表（插入时带库名前缀）
          </Typography.Text>
        </Space>}
        extra={<Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Ctrl+Enter 运行
          </Typography.Text>
          <Button
            type="primary"
            icon={<PlayCircleOutlined />}
            loading={loading}
            onClick={() => run()}
          >
            运行
          </Button>
        </Space>}
      >
        <Space size={8} style={{ marginBottom: 12 }} wrap>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>执行位置</Typography.Text>
          <Select
            allowClear
            style={{ width: 320 }}
            placeholder="平台内置 Doris"
            value={projectId}
            onChange={(value) => {
              setProjectId(value)
              setTarget('')
              setExecuted(false)
            }}
            options={projects.map((p) => ({
              value: p.id,
              label: `${p.name}（${p.code}）`,
            }))}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {target ? `上次执行：${target}` : '不选 = 平台内置 Doris'}
          </Typography.Text>
        </Space>
        {/* V99：执行前告诉用户这条 SQL 涉及哪些数据源、实际在 Doris 执行；
            命中 Kafka / 文件类数据源时提前说明会被拒绝，不用等 400 */}
        {capabilityView.blocked.length > 0 ? (
          <Alert
            type="error"
            showIcon
            style={{ marginBottom: 12 }}
            message={`涉及 ${capabilityView.blocked.length} 个仅导入数据源，无法用 SQL 查询`}
            description={`${capabilityView.blocked.map((ds) => ds.name).join('、')}：${
              capabilityView.blocked[0].reason || '请先入湖或导入后再查询'
            }`}
          />
        ) : capabilityView.count > 0 ? (
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message={`涉及 ${capabilityView.count} 个数据源，将使用 Doris 执行`}
            description={capabilityView.names.length > 0
              ? `匹配到：${capabilityView.names.join('、')}（执行引擎固定为 Doris）`
              : '未匹配到已登记数据源，将使用平台内置 Doris 执行'}
          />
        ) : null}
        {mentionsPrd && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 12 }}
            message="SQL 涉及生产库（_prd）：本页只读"
            description="查询页只允许 SELECT / WITH，不会修改生产数据。需要改表结构请到「表管理」并按生产变更流程填写原因；需要写数据请走数据集成或数据开发。"
          />
        )}
        <CodeEditor
          label="SQL 查询"
          height={240}
          language="SQL"
          value={sql}
          onChange={setSql}
          onRun={() => run()}
          projectId={projectId}
          placeholder="只允许 SELECT / WITH"
        />
      </Card>
      <Card
        style={{ marginTop: 16 }}
        title={meta || '结果'}
        extra={columns.length > 0 ? (
          <Space size={8}>
            <Tooltip title="复制全部结果（制表符分隔），可直接粘贴到 Excel">
              <Button
                size="small"
                icon={<CopyOutlined />}
                disabled={!rows.length}
                onClick={copyResult}
              >
                复制
              </Button>
            </Tooltip>
            <Tooltip title="导出全部结果（最多 200 行），不是当前分页">
              <Button
                size="small"
                icon={<DownloadOutlined />}
                disabled={!rows.length}
                onClick={exportCsv}
              >
                导出 CSV
              </Button>
            </Tooltip>
          </Space>
        ) : null}
      >
        {error ? (
          <ErrorState
            title="查询失败"
            message={<ErrorText error={error} status={errorStatus} />}
            compact
          />
        ) : !executed ? (
          <EmptyState title="尚未执行查询" hint="输入 SQL 后按 Ctrl+Enter 或点「运行」，结果会显示在这里" />
        ) : columns.length === 0 ? (
          <EmptyState title="查询成功，没有返回数据" hint="换一个 SQL 或检查过滤条件" />
        ) : (
          <>
          {maskedColumns.length > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 12 }}
              action={(() => {
                const table = guardTables.find((t) => t?.table)
                if (!table || !maskedColumns.length) return null
                const params = new URLSearchParams({
                  catalog: 'Doris',
                  schema: table.schema || '',
                  table: table.table || '',
                  column: maskedColumns[0],
                  permission: 'READ',
                })
                return (
                  <Button size="small" type="primary"
                          onClick={() => { window.location.href = `/access?${params.toString()}` }}>
                    申请明文
                  </Button>
                )
              })()}
              message={`已脱敏 ${maskedColumns.length} 列：${maskedColumns.join('、')}`}
              description={`敏感列按配置输出 ***（MASK）或稳定令牌（TOKEN）。${
                tokenizedColumns.length > 0
                  ? `令牌列（${tokenizedColumns.join('、')}）可直接用于 group by / join / count distinct：同值同令牌、跨查询稳定，但无法还原明文。`
                  : ''
              }需要明文请到「权限申请」提交表/列授权。`}
            />
          )}
          <Table
            columns={columns}
            dataSource={rows}
            size="small"
            scroll={{ x: Math.max(560, columns.length * 160) }}
            pagination={{ pageSize: 20, hideOnSinglePage: true }}
            locale={{ emptyText: tableEmpty('查询成功，没有返回数据') }}
          />
          </>
        )}
      </Card>
    </div>
  )
}
