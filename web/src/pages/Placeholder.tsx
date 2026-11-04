import { Card } from 'antd'
import PageHeader from '../components/PageHeader'
import { EmptyState } from '../components/StateBlocks'

export default function Placeholder({ title, note }: { title: string; note: string }) {
  return (
    <div>
      <PageHeader title={title} description="模块规划中，当前为占位页。" />
      <Card>
        <EmptyState title="功能尚未开放" hint={note} />
      </Card>
    </div>
  )
}
