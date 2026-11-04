import { useCallback, useEffect, useState } from 'react'
import {
  Alert, Button, Card, Col, Popconfirm, Row, Space, Statistic, Table, Tag, Typography, message,
} from 'antd'
import { ExperimentOutlined, DeleteOutlined } from '@ant-design/icons'
import { post } from '../api'
import PageHeader from '../components/PageHeader'
import { ErrorState, tableEmpty } from '../components/StateBlocks'

/**
 * 元数据巡检（Gravitino Catalog 一致性）。
 *
 * 后端 `POST /api/datasources/gravitino/cleanup` 默认 dry-run，只分类不删除；
 * `confirm=true` 才真删。分类里 `protected`（平台内置 Catalog）和 `in-use`
 * （还有数据源引用）永远不删，所以这个页面先把「会删哪些」摆出来再给一次确认。
 */

type CareEntry = {
  name?: string
  category?: string
  deletable?: boolean
  occurrences?: number
  reason?: string
}

type CareSummary = Record<string, number>

type CareResult = {
  dryRun?: boolean
  tenant?: string
  namespacePrefix?: string
  catalogs?: CareEntry[]
  summary?: CareSummary
  deleted?: string[]
}

const CATEGORY_LABEL: Record<string, { text: string; color: string }> = {
  'in-use': { text: '在用', color: 'blue' },
  protected: { text: '受保护', color: 'default' },
  orphan: { text: '孤儿', color: 'orange' },
  duplicate: { text: '重复', color: 'red' },
}

function categoryTag(category?: string) {
  const meta = category ? CATEGORY_LABEL[category] : undefined
  return <Tag color={meta?.color || 'default'}>{meta?.text || category || '-'}</Tag>
}

export default function GravitinoCare() {
  const [result, setResult] = useState<CareResult | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const run = useCallback(async (confirm: boolean) => {
    setLoading(true)
    setError('')
    try {
      const res = await post<CareResult>(
        `/api/datasources/gravitino/cleanup?confirm=${confirm ? 'true' : 'false'}`, {})
      setResult(res)
      if (confirm) {
        const deleted = res?.deleted?.length ?? 0
        message.success(`已清理 ${deleted} 个 Catalog`)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '巡检失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void run(false) }, [run])

  const rows = result?.catalogs ?? []
  const summary = result?.summary ?? {}
  const deletable = Number(summary.deletable ?? 0)

  return (
    <div className="page">
      <PageHeader
        title="元数据巡检"
        description="检查 Gravitino 里的 Catalog 与平台数据源是否一致：孤儿（平台已无对应数据源）和重复（catalog_meta 同名残留）可以清理，在用和受保护的一律不动。"
        extra={(
          <Space>
            <Button icon={<ExperimentOutlined />} loading={loading} onClick={() => void run(false)}>
              巡检（dry-run）
            </Button>
            <Popconfirm
              title={`清理 ${deletable} 个 Catalog？`}
              description="只删孤儿与重复项；Gravitino 是软删除，不会动 Doris 里的库表。"
              okButtonProps={{ danger: true }}
              okText="清理"
              disabled={deletable === 0}
              onConfirm={() => void run(true)}
            >
              <Button danger icon={<DeleteOutlined />} disabled={deletable === 0}>
                确认清理{deletable > 0 ? `（${deletable}）` : ''}
              </Button>
            </Popconfirm>
          </Space>
        )}
      />

      {error && (
        <ErrorState compact title="「巡检结果」加载失败" message={error} onRetry={() => run(false)} />
      )}

      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message="默认只巡检不删除"
        description={result
          ? `租户 ${result.tenant ?? '-'}，命名空间前缀 ${result.namespacePrefix ?? '-'}；本次是${result.dryRun ? ' dry-run（未删除任何东西）' : '真实清理'}。`
          : '点「巡检」先生成清单，确认无误再点「确认清理」。'}
      />

      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="Catalog 总数" value={Number(summary.total ?? 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="在用" value={Number(summary['in-use'] ?? 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="受保护" value={Number(summary.protected ?? 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="孤儿" value={Number(summary.orphan ?? 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="重复" value={Number(summary.duplicate ?? 0)} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="可删除" value={deletable} /></Card></Col>
        <Col xs={12} sm={8} md={6}><Card size="small"><Statistic title="同名出现 >1 次" value={Number(summary.repeated ?? 0)} /></Card></Col>
      </Row>

      <Table<CareEntry>
        rowKey={(r, i) => `${r.name ?? 'x'}-${i}`}
        size="small"
        loading={loading}
        dataSource={rows}
        pagination={false}
        locale={{ emptyText: tableEmpty('没有需要处理的 Catalog', '点「巡检」生成清单；在用与受保护的不会被删除') }}
        columns={[
          {
            title: 'Catalog', dataIndex: 'name',
            render: (v?: string) => <Typography.Text code>{v || '-'}</Typography.Text>,
          },
          {
            title: '分类', dataIndex: 'category', width: 100,
            render: (v?: string) => categoryTag(v),
          },
          {
            title: '可删除', dataIndex: 'deletable', width: 90,
            render: (v?: boolean) => (v ? <Tag color="red">会删</Tag> : <Tag>不动</Tag>),
          },
          {
            title: '同名次数', dataIndex: 'occurrences', width: 100,
            render: (v?: number) => (v && v > 1 ? <Tag color="volcano">{v}</Tag> : (v ?? 1)),
          },
          {
            title: '判定依据', dataIndex: 'reason',
            render: (v?: string) => <Typography.Text type="secondary">{v || '-'}</Typography.Text>,
          },
        ]}
      />
    </div>
  )
}
