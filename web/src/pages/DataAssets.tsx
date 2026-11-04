import { Tabs } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import PageHeader from '../components/PageHeader'
import DataMap from './DataMap'
import Lineage from './Lineage'

/**
 * 数据资产统一入口：资产目录（元数据浏览）与数据血缘合并为一个模块。
 * 两个视图共用同一套 catalog/schema/table 资产标识，不再作为两个并列菜单。
 */
export default function DataAssets() {
  const location = useLocation()
  const navigate = useNavigate()
  const params = new URLSearchParams(location.search)
  const tab = location.pathname.startsWith('/lineage') || params.get('tab') === 'lineage'
    ? 'lineage'
    : 'catalog'

  return (
    <div>
      <PageHeader
        title="数据资产"
        description="统一资产目录：catalog / schema / 表 / 字段 / 文件集，以及每张表的上下游血缘。"
      />
      <Tabs
        activeKey={tab}
        onChange={(key) => {
          const search = location.search
          navigate(key === 'lineage' ? `/lineage${search}` : '/map')
        }}
        items={[
          { key: 'catalog', label: '资产目录', children: <DataMap embedded /> },
          { key: 'lineage', label: '数据血缘', children: <Lineage embedded /> },
        ]}
      />
    </div>
  )
}
