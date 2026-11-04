import { useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Alert, Button, Card, Col, Descriptions, Input, Row, Space, Table, Tag, Typography, message } from 'antd'
import { CopyOutlined, SearchOutlined } from '@ant-design/icons'
import { api } from '../api'
import PageHeader from '../components/PageHeader'
import ImeInput from '../components/ImeInput'
import SearchableSelect from '../components/SearchableSelect'
import { EmptyState, ErrorState, LoadingState, tableEmpty } from '../components/StateBlocks'
import ErrorText from '../components/ErrorText'
import { canAny } from '../permissions'

/** 标识符加反引号；catalog/schema/table 来自元数据，正常不含反引号，这里仍做一次转义。 */
const quoteIdent = (value: string) => '`' + value.replace(/`/g, '``') + '`'

/** 数据地图表详情的示例 SQL：湖表/外部 Doris 表都用 catalog.schema.table 三段式。 */
function assetSelectSql(catalog: string, schema: string, table: string, column?: string) {
  const from = `${quoteIdent(catalog)}.${quoteIdent(schema)}.${quoteIdent(table)}`
  return column
    ? `select *\nfrom ${from}\nwhere ${quoteIdent(column)} is not null\nlimit 100`
    : `select *\nfrom ${from}\nlimit 100`
}

/** http 页面没有 navigator.clipboard，退回 execCommand 才能真的复制成功。 */
async function copyToClipboard(text: string): Promise<boolean> {
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

const sqlBlockStyle = {
  margin: '6px 0 0',
  padding: 8,
  fontSize: 12,
  lineHeight: 1.6,
  background: 'rgba(0,0,0,0.03)',
  borderRadius: 4,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
} as const

type Column = {
  name: string
  type: string
  nullable?: boolean
  comment?: string
}

type Asset = {
  name: string
  kind: 'table' | 'fileset'
}

type TableDetail = {
  catalog: string
  schema: string
  table: string
  comment?: string
  columns: Column[]
  properties?: Record<string, string>
}

type FilesetDetail = {
  catalog: string
  schema: string
  fileset: string
  type?: string
  storageLocation?: string
  comment?: string
  properties?: Record<string, string>
}

export default function DataMap({ embedded = false }: { embedded?: boolean }) {
  const nav = useNavigate()
  const canQuery = canAny('query:run')
  const [catalogs, setCatalogs] = useState<string[]>([])
  const [catalog, setCatalog] = useState<string>()
  const [schemas, setSchemas] = useState<string[]>([])
  const [schema, setSchema] = useState<string>()
  const [assets, setAssets] = useState<Asset[]>([])
  const [asset, setAsset] = useState<Asset>()
  const [tableDetail, setTableDetail] = useState<TableDetail>()
  const [filesetDetail, setFilesetDetail] = useState<FilesetDetail>()
  const [loading, setLoading] = useState(false)
  const [assetsLoading, setAssetsLoading] = useState(false)
  const [error, setError] = useState('')
  // 资产列表与字段列表的本地搜索：数据量就是当前 schema，不需要后端分页
  const [assetKeyword, setAssetKeyword] = useState('')
  const [fieldKeyword, setFieldKeyword] = useState('')
  // 快速切换 catalog / schema 时，旧请求晚返回不能覆盖新选择
  const loadSeq = useRef(0)

  const loadCatalogs = async () => {
    setError('')
    try {
      const res = await api<{ catalogs: string[] }>('/api/catalog/catalogs')
      const list = res.catalogs || []
      setCatalogs(list)
      // 首次进入自动选第一个 catalog，避免整页空状态
      if (list.length > 0) onCatalog(list[0])
    } catch (e: any) {
      setError(e.message || '加载 catalog 失败')
    }
  }

  const loadSchemas = async (name: string) => {
    const seq = ++loadSeq.current
    setError('')
    try {
      const res = await api<{ schemas: string[] }>(`/api/catalog/catalogs/${encodeURIComponent(name)}/schemas`)
      if (seq !== loadSeq.current) return
      const list = res.schemas || []
      setSchemas(list)
      if (list.length > 0) {
        setSchema(list[0])
        loadAssets(name, list[0], seq)
      }
    } catch (e: any) {
      if (seq !== loadSeq.current) return
      setSchemas([])
      setError(e.message || '加载 schema 失败')
    }
  }

  const loadAssets = async (catalogName: string, schemaName: string, seq: number) => {
    const base = `/api/catalog/catalogs/${encodeURIComponent(catalogName)}/schemas/${encodeURIComponent(schemaName)}`
    const merged: Asset[] = []
    setAssetsLoading(true)
    try {
      try {
        const res = await api<{ tables: string[] }>(`${base}/tables`)
        ;(res.tables || []).forEach((name) => merged.push({ name, kind: 'table' }))
      } catch {
        // FILESET catalogs have no /tables endpoint; fall through to filesets.
      }
      try {
        const res = await api<{ filesets: string[] }>(`${base}/filesets`)
        ;(res.filesets || []).forEach((name) => merged.push({ name, kind: 'fileset' }))
      } catch {
        // RELATIONAL catalogs have no /filesets endpoint.
      }
      if (seq !== loadSeq.current) return
      setError('')
      setAssets(merged)
      if (merged.length > 0) {
        setAsset(merged[0])
        loadDetail(catalogName, schemaName, merged[0], seq)
      }
    } finally {
      if (seq === loadSeq.current) setAssetsLoading(false)
    }
  }

  const loadDetail = async (catalogName: string, schemaName: string, target: Asset, seq: number) => {
    const base = `/api/catalog/catalogs/${encodeURIComponent(catalogName)}/schemas/${encodeURIComponent(schemaName)}`
    setLoading(true)
    try {
      if (target.kind === 'table') {
        const res = await api<TableDetail>(`${base}/tables/${encodeURIComponent(target.name)}`)
        if (seq !== loadSeq.current) return
        setTableDetail(res)
        setFilesetDetail(undefined)
      } else {
        const res = await api<FilesetDetail>(`${base}/filesets/${encodeURIComponent(target.name)}`)
        if (seq !== loadSeq.current) return
        setFilesetDetail(res)
        setTableDetail(undefined)
      }
      setError('')
    } catch (e: any) {
      if (seq !== loadSeq.current) return
      setTableDetail(undefined)
      setFilesetDetail(undefined)
      setError(e.message || '加载详情失败')
    } finally {
      if (seq === loadSeq.current) setLoading(false)
    }
  }

  useEffect(() => {
    loadCatalogs()
  }, [])

  const onCatalog = (name: string) => {
    setCatalog(name)
    setSchema(undefined)
    setAsset(undefined)
    setTableDetail(undefined)
    setFilesetDetail(undefined)
    setSchemas([])
    setAssets([])
    loadSchemas(name)
  }

  const onSchema = (name: string) => {
    setSchema(name)
    setAsset(undefined)
    setTableDetail(undefined)
    setFilesetDetail(undefined)
    setAssets([])
    if (catalog) {
      const seq = ++loadSeq.current
      loadAssets(catalog, name, seq)
    }
  }

  const onAsset = (target: Asset) => {
    setAsset(target)
    if (catalog && schema) {
      const seq = ++loadSeq.current
      loadDetail(catalog, schema, target, seq)
    }
  }

  const keyword = assetKeyword.trim().toLowerCase()
  const visibleAssets = useMemo(() => (keyword
    ? assets.filter((a) => a.name.toLowerCase().includes(keyword))
    : assets), [assets, keyword])
  const fieldKw = fieldKeyword.trim().toLowerCase()
  const visibleColumns = useMemo(() => (tableDetail?.columns || []).filter((c) => !fieldKw
    || c.name.toLowerCase().includes(fieldKw)
    || String(c.comment || '').toLowerCase().includes(fieldKw)),
  [tableDetail?.columns, fieldKw])

  const copySql = async (sql: string) => {
    const ok = await copyToClipboard(sql)
    if (ok) message.success('SQL 已复制，可到「数据查询」粘贴执行')
    else message.error('浏览器不允许自动复制，请手动选中 SQL 复制')
  }

  const goQueryWith = (sql: string) => {
    if (!canQuery) {
      message.warning('当前账号没有查询权限（query:run），请先到「权限申请」提交库/表读取授权')
      return
    }
    nav(`/query?sql=${encodeURIComponent(sql)}`)
  }

  const renderDetail = () => {
    if (filesetDetail) {
      return (
        <>
          <Descriptions size="small" column={2}>
            <Descriptions.Item label="catalog">{filesetDetail.catalog}</Descriptions.Item>
            <Descriptions.Item label="schema">{filesetDetail.schema}</Descriptions.Item>
            <Descriptions.Item label="文件集">{filesetDetail.fileset}</Descriptions.Item>
            <Descriptions.Item label="类型">{filesetDetail.type || '-'}</Descriptions.Item>
            <Descriptions.Item label="存储位置" span={2}>{filesetDetail.storageLocation || '-'}</Descriptions.Item>
            {filesetDetail.comment ? (
              <Descriptions.Item label="注释" span={2}>{filesetDetail.comment}</Descriptions.Item>
            ) : null}
          </Descriptions>
          <Typography.Text type="secondary">文件集是对象存储目录，没有表结构。</Typography.Text>
          <Alert
            type="info"
            showIcon
            style={{ marginTop: 12 }}
            message="仅导入：文件集不支持 SQL 查询"
            description="文件集没有 SQL 执行链路，不能直接 select。请先同步入湖/入仓再查：到「数据集成」建同步任务把数据写入 Iceberg 湖或 Doris，实时数据到「流开发」用 FlinkSQL 落 Doris，然后到「数据查询」查询。"
          />
          <Space size={8} wrap style={{ marginTop: 8 }}>
            <Button size="small" type="primary" onClick={() => nav('/integration')}>去数据集成入湖/入仓</Button>
            <Button size="small" onClick={() => nav('/stream')}>去流开发落 Doris</Button>
          </Space>
        </>
      )
    }
    if (tableDetail) {
      return (
        <>
          <Descriptions size="small" column={2} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="catalog">{tableDetail.catalog}</Descriptions.Item>
            <Descriptions.Item label="schema">{tableDetail.schema}</Descriptions.Item>
            <Descriptions.Item label="表">{tableDetail.table}</Descriptions.Item>
            <Descriptions.Item label="字段数">{tableDetail.columns?.length || 0}</Descriptions.Item>
            {tableDetail.comment ? (
              <Descriptions.Item label="注释" span={2}>{tableDetail.comment}</Descriptions.Item>
            ) : null}
          </Descriptions>
          {(() => {
            const fullName = `${tableDetail.catalog}.${tableDetail.schema}.${tableDetail.table}`
            const firstColumn = tableDetail.columns?.[0]?.name
            const baseSql = assetSelectSql(tableDetail.catalog, tableDetail.schema, tableDetail.table)
            const filteredSql = firstColumn
              ? assetSelectSql(tableDetail.catalog, tableDetail.schema, tableDetail.table, firstColumn)
              : baseSql
            const isPrd = tableDetail.schema.toLowerCase().endsWith('_prd')
            return (
              <Card size="small" title="如何查询" style={{ marginBottom: 12 }}>
                <Descriptions size="small" column={1} style={{ marginBottom: 8 }}>
                  <Descriptions.Item label="可查引擎">
                    <Space size={4} wrap>
                      <Tag color="blue">Doris</Tag>
                      <Tag color="cyan">Iceberg</Tag>
                      <Tag color="orange">Spark</Tag>
                      <Typography.Text style={{ fontSize: 12 }}>
                        关系型 Catalog 的表可用 <Typography.Text code>{fullName}</Typography.Text> 在「数据查询」查；Spark 在「数据开发」的 SPARK 脚本里做批处理。
                      </Typography.Text>
                    </Space>
                    <div style={{ marginTop: 4 }}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        Kafka / 文件类数据源、文件集只能导入，没有 SQL 查询链路。下一步：到「数据集成」建同步任务写入 Iceberg 湖或 Doris，实时数据到「流开发」用 FlinkSQL 落 Doris，完成后再到「数据查询」查。
                      </Typography.Text>
                      <div style={{ marginTop: 2 }}>
                        <Space size={4} wrap>
                          <Button size="small" type="link" style={{ padding: 0 }}
                                  onClick={() => nav('/integration')}>去数据集成入湖/入仓</Button>
                          <Button size="small" type="link" style={{ padding: 0 }}
                                  onClick={() => nav('/stream')}>去流开发落 Doris</Button>
                        </Space>
                      </div>
                    </div>
                  </Descriptions.Item>
                  <Descriptions.Item label="你的权限">
                    {canQuery ? (
                      <Typography.Text type="success" style={{ fontSize: 12 }}>
                        已具备查询入口权限（query:run）；具体库表仍需 READ 授权，遇到 403 时到「权限申请」提交库/表读取授权。
                      </Typography.Text>
                    ) : (
                      <Space size={6} wrap>
                        <Typography.Text type="warning" style={{ fontSize: 12 }}>
                          缺少查询入口权限（query:run）。复制 SQL 不受影响；请提交库/表读取授权，审批通过后刷新再执行。
                        </Typography.Text>
                        <Button size="small" type="primary" style={{ height: 22, fontSize: 12 }}
                                onClick={() => nav('/access')}>去申请权限</Button>
                      </Space>
                    )}
                  </Descriptions.Item>
                  <Descriptions.Item label="注意事项">
                    <ul style={{ margin: 0, paddingLeft: 18, fontSize: 12, lineHeight: 1.8 }}>
                      <li>
                        {isPrd ? <Tag color="red">PROD</Tag> : null}
                        库名以 _prd 结尾按生产库处理，查询页只读，不会修改生产数据；改结构请走「表管理」的生产变更流程并填写原因。
                      </li>
                      <li>结果上限：前端每次最多展示 200 行、后端硬上限 1000 行，达到上限会提示「结果已截断」；需要更多数据请收紧 where 条件或分批导出。</li>
                      <li>超时：服务端单次查询 60 秒上限，缩小时间范围、补 where 条件或降低 limit 后重试。</li>
                      <li>脱敏：结果按数据分级（L1–L4）与字段脱敏规则自动打码，L3/L4 字段可能只显示掩码；需要明文请到「权限申请」申请明文查看（column:unmask）或联系数据 owner。</li>
                      <li>外部项目的 Doris Catalog：到查询页后要选择对应项目，否则默认走平台内置 Doris，可能找不到这个 Catalog。</li>
                    </ul>
                  </Descriptions.Item>
                </Descriptions>
                <div style={{ marginTop: 4 }}>
                  <Space size={6} wrap>
                    <Typography.Text strong style={{ fontSize: 12 }}>示例 SQL</Typography.Text>
                    {isPrd ? <Tag color="red">生产库 _prd：只读</Tag> : null}
                    <Button size="small" type="link" icon={<CopyOutlined />}
                            onClick={() => copySql(baseSql)}>复制</Button>
                  </Space>
                  <pre style={sqlBlockStyle}>{baseSql}</pre>
                </div>
                {firstColumn ? (
                  <div style={{ marginTop: 8 }}>
                    <Space size={6} wrap>
                      <Typography.Text strong style={{ fontSize: 12 }}>
                        带过滤条件（示例列：{firstColumn}）
                      </Typography.Text>
                      <Button size="small" type="link" icon={<CopyOutlined />}
                              onClick={() => copySql(filteredSql)}>复制</Button>
                    </Space>
                    <pre style={sqlBlockStyle}>{filteredSql}</pre>
                  </div>
                ) : null}
                <Space size={8} wrap style={{ marginTop: 8 }}>
                  <Button size="small" type="primary" icon={<SearchOutlined />}
                          disabled={!canQuery} onClick={() => goQueryWith(baseSql)}>
                    到查询页
                  </Button>
                  <Button size="small" disabled={!canQuery || !firstColumn}
                          onClick={() => goQueryWith(filteredSql)}>
                    带条件去查询
                  </Button>
                  {!canQuery && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      当前账号没有 query:run 权限，复制 SQL 不受影响；到「权限申请」提交库/表读取授权。
                    </Typography.Text>
                  )}
                </Space>
              </Card>
            )
          })()}
          <ImeInput
            placeholder="搜索字段名或注释"
            allowClear
            value={fieldKeyword}
            onChange={setFieldKeyword}
            style={{ marginBottom: 8, maxWidth: 320 }}
          />
          <Table
            size="small"
            rowKey="name"
            pagination={false}
            dataSource={visibleColumns}
            scroll={{ x: 520 }}
            locale={{ emptyText: tableEmpty(fieldKeyword ? '没有匹配的字段' : '该表没有字段') }}
            columns={[
              { title: '字段', dataIndex: 'name', key: 'name' },
              { title: '类型', dataIndex: 'type', key: 'type', render: (v: string) => <Tag color="blue">{v}</Tag> },
              { title: '可空', dataIndex: 'nullable', key: 'nullable', render: (v?: boolean) => (v === false ? '否' : '是') },
              { title: '注释', dataIndex: 'comment', key: 'comment', ellipsis: true },
            ]}
          />
        </>
      )
    }
    return <EmptyState title="选择一张表或文件集" hint="左侧列表会展示当前 schema 下的资产" />
  }

  const title = asset
    ? `${catalog}.${schema}.${asset.name}`
    : '表 / 文件集详情'

  return (
    <div>
      {embedded ? null : (
        <PageHeader
          title="数据资产"
          description="元数据来自 Gravitino：catalog / schema / 表 / 字段 / 文件集，与 Doris、MinIO 对齐。"
        />
      )}
      {error ? <ErrorState message={<ErrorText error={error} />} onRetry={loadCatalogs} /> : null}
      <Card size="small">
        <Space wrap>
          <SearchableSelect
            style={{ width: 180 }}
            placeholder="catalog"
            value={catalog}
            onChange={onCatalog}
            options={catalogs.map((c) => ({ value: c, label: c }))}
          />
          <SearchableSelect
            style={{ width: 180 }}
            placeholder="schema"
            value={schema}
            onChange={onSchema}
            disabled={!catalog}
            options={schemas.map((s) => ({ value: s, label: s }))}
          />
        </Space>
      </Card>

      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col xs={24} lg={7}>
          <Card size="small" title={`表 / 文件集（${assets.length}）`} styles={{ body: { maxHeight: 520, overflow: 'auto' } }}>
            {/* 接口失败与「确实没有资产」分开：失败时上面已有 ErrorState，不再显示空状态 */}
            {error ? null : assetsLoading ? (
              <LoadingState rows={5} />
            ) : assets.length === 0 ? (
              <EmptyState
                title="没有可展示的资产"
                hint={catalog && schema ? '当前 schema 下没有表或文件集' : '先选 catalog 和 schema'}
              />
            ) : (
              <>
              <ImeInput
                placeholder="搜索表 / 文件集"
                allowClear
                value={assetKeyword}
                onChange={setAssetKeyword}
                style={{ marginBottom: 8 }}
              />
              {visibleAssets.length === 0 ? (
                <EmptyState title="没有匹配的资产" hint={`没有名称包含「${assetKeyword}」的表或文件集`} />
              ) : (
              <div className="asset-list">
                {visibleAssets.map((a) => {
                  const active = asset?.kind === a.kind && asset?.name === a.name
                  return (
                    <button
                      type="button"
                      key={`${a.kind}:${a.name}`}
                      className={`asset-item${active ? ' active' : ''}`}
                      onClick={() => onAsset(a)}
                    >
                      <span className="asset-item-icon">{a.kind === 'fileset' ? '📁' : '▦'}</span>
                      <span className="asset-item-name">{a.name}</span>
                      {a.kind === 'fileset' ? (
                        <Tag color="default" style={{ marginLeft: 'auto', marginInlineEnd: 0, flex: 'none', fontSize: 10 }}>
                          仅导入
                        </Tag>
                      ) : null}
                    </button>
                  )
                })}
              </div>
              )}
              </>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={17} className="map-detail-col">
          <Card size="small" title={title} loading={loading}>
            {renderDetail()}
          </Card>
        </Col>
      </Row>
    </div>
  )
}
