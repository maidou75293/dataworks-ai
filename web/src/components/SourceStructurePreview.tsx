import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Modal, Spin, Table, Tag, Typography } from 'antd'
import { api } from '../api'

/**
 * REQ-DW-15：源数据结构预览弹框（只读）。
 *
 * 左侧 = 数据源下的库表清单（Kafka 为 topic 清单），点任意一行看字段定义；
 * Kafka 的字段结构来自抽样消息推断，界面明确标注「抽样推断」。
 * 数据来自 /api/integration/datasource/{id}/tables 与 .../tables/{t}/columns。
 *
 * REQ-DW-16：同一组件支持「未保存表单」预览——传 adhocRequest（表单连接参数）
 * 即走 POST /api/integration/datasource/preview/{tables,columns}，临时试连不落库。
 * 两条路径共用这一个实现（数据源新建/编辑页 与 同步任务页），禁止复制两份。
 */

type TableItem = {
  name: string
  catalog?: string | null
  schema?: string | null
  type?: string
  comment?: string | null
}

type ColumnItem = {
  name: string
  typeName: string
  nullable?: boolean
  comment?: string | null
  primaryKey?: boolean
  seenCount?: number
}

type TablesResp = { kind: 'JDBC' | 'KAFKA'; product?: string; items: TableItem[]; truncated?: boolean }
type ColumnsResp = {
  kind: string
  inferred?: boolean
  note?: string
  columns: ColumnItem[]
  sampleCount?: number
  parsedCount?: number
}

export default function SourceStructurePreview(props: {
  open: boolean
  datasourceId?: number
  /** 未保存表单的连接参数（REQ-DW-16）；有值时走临时预览接口，忽略 datasourceId */
  adhocRequest?: Record<string, unknown> | null
  datasourceName?: string
  onClose: () => void
}) {
  const { open, datasourceId, datasourceName, onClose } = props
  const adhoc = props.adhocRequest || null
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [resp, setResp] = useState<TablesResp | null>(null)
  const [selected, setSelected] = useState<TableItem | null>(null)
  const [colsLoading, setColsLoading] = useState(false)
  const [colsError, setColsError] = useState('')
  const [cols, setCols] = useState<ColumnsResp | null>(null)
  // 打开后快速切换数据源时，只认最后一次请求的结果（与 Integration 列表页同口径）
  const seq = useRef(0)

  useEffect(() => {
    if (!open || (!datasourceId && !adhoc)) return
    const my = ++seq.current
    setLoading(true)
    setError('')
    setResp(null)
    setSelected(null)
    setCols(null)
    setColsError('')
    const req = adhoc
      // REQ-DW-16：用表单参数临时试连（不落库）
      ? api<TablesResp>('/api/integration/datasource/preview/tables',
          { method: 'POST', body: JSON.stringify(adhoc) })
      : api<TablesResp>(`/api/integration/datasource/${datasourceId}/tables`)
    req
      .then((d) => { if (my === seq.current) setResp(d) })
      .catch((e: Error) => { if (my === seq.current) setError(e.message || '读取表清单失败') })
      .finally(() => { if (my === seq.current) setLoading(false) })
  }, [open, datasourceId, adhoc])

  const pickTable = async (item: TableItem) => {
    if (!datasourceId && !adhoc) return
    const my = ++seq.current
    setSelected(item)
    setCols(null)
    setColsError('')
    setColsLoading(true)
    try {
      let d: ColumnsResp
      if (adhoc) {
        d = await api<ColumnsResp>('/api/integration/datasource/preview/columns', {
          method: 'POST',
          body: JSON.stringify({
            ...adhoc,
            table: item.name,
            ...(resp?.kind === 'JDBC'
              ? { catalog: item.catalog || undefined, schema: item.schema || undefined }
              : {}),
          }),
        })
      } else {
        const qs = new URLSearchParams()
        if (resp?.kind === 'JDBC') {
          if (item.catalog) qs.set('catalog', item.catalog)
          if (item.schema) qs.set('schema', item.schema)
        }
        const suffix = qs.toString() ? `?${qs.toString()}` : ''
        d = await api<ColumnsResp>(
          `/api/integration/datasource/${datasourceId}/tables/${encodeURIComponent(item.name)}/columns${suffix}`,
        )
      }
      if (my === seq.current) setCols(d)
    } catch (e) {
      if (my === seq.current) setColsError((e as Error).message || '读取字段失败')
    } finally {
      if (my === seq.current) setColsLoading(false)
    }
  }

  const isKafka = resp?.kind === 'KAFKA'

  return (
    <Modal
      title={`源数据结构预览 · ${datasourceName || ''}`}
      open={open}
      onCancel={onClose}
      footer={null}
      width={980}
      destroyOnClose
    >
      {error && <Alert type="error" showIcon message={error} style={{ marginBottom: 12 }} />}
      <div style={{ display: 'flex', gap: 16 }}>
        <div style={{ width: 380, flexShrink: 0 }}>
          <Typography.Text strong>{isKafka ? 'Topic 清单' : '表清单'}</Typography.Text>
          <Spin spinning={loading}>
            <Table<TableItem>
              size="small"
              rowKey={(r) => `${r.catalog || ''}.${r.schema || ''}.${r.name}`}
              style={{ marginTop: 8, maxHeight: 420, overflow: 'auto' }}
              dataSource={resp?.items || []}
              pagination={false}
              columns={[
                { title: '名称', dataIndex: 'name' },
                { title: '类型', dataIndex: 'type', width: 80,
                  render: (v?: string) => (v && v !== 'TOPIC' ? <Tag>{v}</Tag> : null) },
              ]}
              onRow={(r) => ({ onClick: () => pickTable(r), style: { cursor: 'pointer' } })}
              rowClassName={(r) => (selected?.name === r.name ? 'ant-table-row-selected' : '')}
              locale={{ emptyText: loading ? ' ' : (resp?.items?.length ? '无' : '没有可读对象') }}
            />
          </Spin>
          {resp?.truncated && (
            <Alert type="warning" showIcon message="表数量超过 500，仅显示前 500 个"
              style={{ marginTop: 8 }} />
          )}
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          {!selected && (
            <Typography.Text type="secondary">点击左侧任意{isKafka ? ' topic' : '表'}查看字段定义</Typography.Text>
          )}
          {selected && (
            <>
              <Typography.Text strong>
                {isKafka ? `Topic：${selected.name}` : `字段定义 · ${selected.name}`}
              </Typography.Text>
              {cols?.inferred && (
                <Tag color="orange" style={{ marginLeft: 8 }}>抽样推断</Tag>
              )}
              {colsError && <Alert type="error" showIcon message={colsError} style={{ margin: '8px 0' }} />}
              {cols?.note && (
                <Alert type="info" showIcon message={cols.note} style={{ margin: '8px 0' }} />
              )}
              <Spin spinning={colsLoading}>
                <Table<ColumnItem>
                  size="small"
                  rowKey="name"
                  style={{ marginTop: 8 }}
                  dataSource={cols?.columns || []}
                  pagination={false}
                  scroll={{ y: 340 }}
                  columns={[
                    { title: '字段', dataIndex: 'name' },
                    { title: '类型', dataIndex: 'typeName', width: 140,
                      render: (v: string) => (v ? <Tag color="blue">{v}</Tag> : '-') },
                    { title: '可空', dataIndex: 'nullable', width: 70,
                      render: (v?: boolean) => (v === undefined ? '-' : (v ? '是' : '否')) },
                    ...(cols?.inferred
                      ? [{ title: '抽样出现', dataIndex: 'seenCount', width: 90,
                          render: (v?: number) => `${v ?? '-'}/${cols.parsedCount ?? '-'}` } as any]
                      : [{ title: '主键', dataIndex: 'primaryKey', width: 70,
                          render: (v?: boolean) => (v ? <Tag color="gold">PK</Tag> : '') }]),
                    { title: '注释', dataIndex: 'comment', ellipsis: true,
                      render: (v?: string) => v || '-' },
                  ]}
                />
              </Spin>
              {cols && !cols.inferred && (cols.columns?.length ?? 0) === 0 && !colsLoading && (
                <Alert type="warning" showIcon message="该表没有读到字段（可能是视图或空表）"
                  style={{ marginTop: 8 }} />
              )}
              <Button size="small" style={{ marginTop: 8 }} onClick={() => { setSelected(null); setCols(null) }}>
                返回清单
              </Button>
            </>
          )}
        </div>
      </div>
    </Modal>
  )
}
