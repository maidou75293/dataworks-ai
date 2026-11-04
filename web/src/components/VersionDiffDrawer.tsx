import { Alert, Collapse, Drawer, Empty, Space, Tag, Typography } from 'antd'
import type { CollapseProps } from 'antd'
import type { FileChange, LineChange, ModelDiff, NodeChange } from '../modelDiff'

type VersionDiffDrawerProps = {
  open: boolean
  versionNo?: number
  diff?: ModelDiff
  dirty: boolean
  onClose: () => void
}

const KIND_LABEL: Record<string, string> = {
  added: '新增',
  removed: '删除',
  changed: '修改',
  renamed: '重命名',
}

const KIND_COLOR: Record<string, string> = {
  added: 'green',
  removed: 'red',
  changed: 'orange',
  renamed: 'blue',
}

function kindTag(kind: string) {
  return <Tag color={KIND_COLOR[kind]}>{KIND_LABEL[kind]}</Tag>
}

/** 行级差异：左侧旧行号、右侧新行号，+ / - 背景色区分。 */
function LineDiff({ lines }: { lines: LineChange[] }) {
  let oldNo = 0
  let newNo = 0
  return (
    <div className="line-diff">
      {lines.map((line, index) => {
        if (line.type !== 'add') oldNo += 1
        if (line.type !== 'del') newNo += 1
        return (
          <div key={index} className={`diff-line ${line.type}`}>
            <span className="diff-no">{line.type === 'add' ? '' : oldNo}</span>
            <span className="diff-no">{line.type === 'del' ? '' : newNo}</span>
            <span className="diff-sign">
              {line.type === 'add' ? '+' : line.type === 'del' ? '-' : ' '}
            </span>
            <span className="diff-text">{line.text || ' '}</span>
          </div>
        )
      })}
    </div>
  )
}

function FieldDiff({ fields }: { fields: NodeChange['fields'] }) {
  if (fields.length === 0) return null
  return (
    <div className="diff-fields">
      {fields.map((field) => (
        <div className="diff-field" key={field.field}>
          <span className="diff-field-label">{field.label}</span>
          <span className="diff-before">{field.before}</span>
          <span className="diff-arrow">→</span>
          <span className="diff-after">{field.after}</span>
        </div>
      ))}
    </div>
  )
}

function summaryText(diff: ModelDiff): string {
  const { summary } = diff
  const parts: string[] = []
  if (summary.nodesAdded) parts.push(`新增节点 ${summary.nodesAdded}`)
  if (summary.nodesRemoved) parts.push(`删除节点 ${summary.nodesRemoved}`)
  if (summary.nodesChanged) parts.push(`修改节点 ${summary.nodesChanged}`)
  if (summary.edgesAdded) parts.push(`新增依赖 ${summary.edgesAdded}`)
  if (summary.edgesRemoved) parts.push(`删除依赖 ${summary.edgesRemoved}`)
  if (summary.filesAdded) parts.push(`新增脚本 ${summary.filesAdded}`)
  if (summary.filesRemoved) parts.push(`删除脚本 ${summary.filesRemoved}`)
  if (summary.filesChanged) parts.push(`修改脚本 ${summary.filesChanged}`)
  if (summary.filesRenamed) parts.push(`重命名脚本 ${summary.filesRenamed}`)
  if (summary.foldersAdded) parts.push(`新增目录 ${summary.foldersAdded}`)
  if (summary.foldersRemoved) parts.push(`删除目录 ${summary.foldersRemoved}`)
  if (summary.foldersRenamed) parts.push(`重命名目录 ${summary.foldersRenamed}`)
  return parts.length > 0 ? parts.join('，') : '没有变化'
}

function fileItems(files: FileChange[]): CollapseProps['items'] {
  return files.map((file) => ({
    key: `file:${file.path}`,
    label: (
      <Space wrap size={6}>
        {kindTag(file.kind)}
        {file.kind === 'renamed' && file.from ? (
          <Typography.Text code>{file.from} → {file.path}</Typography.Text>
        ) : (
          <Typography.Text code>{file.path}</Typography.Text>
        )}
      </Space>
    ),
    children: (
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        <FieldDiff fields={file.fields} />
        {file.lines ? <LineDiff lines={file.lines} /> : null}
        {file.kind === 'renamed' && file.fields.length === 0 && !file.lines ? (
          <Typography.Text type="secondary">仅路径变化，脚本内容未修改。</Typography.Text>
        ) : null}
      </Space>
    ),
  }))
}

export default function VersionDiffDrawer({
  open, versionNo, diff, dirty, onClose,
}: VersionDiffDrawerProps) {
  return (
    <Drawer
      title={versionNo ? `版本对比 · v${versionNo} → 当前` : '版本对比'}
      open={open}
      width={900}
      onClose={onClose}
    >
      {dirty ? (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="当前对比包含未保存改动"
          description="发布前会先保存这些改动，因此这里看到的就是将进入新版本的内容。"
        />
      ) : null}
      {!diff || diff.summary.total === 0 ? (
        <Empty description="该版本与当前编辑内容一致" />
      ) : (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert type="success" showIcon message={summaryText(diff)} />
          {diff.nodes.length > 0 ? (
            <div>
              <Typography.Title level={5} style={{ marginBottom: 8 }}>
                节点（{diff.summary.nodesAdded} 新增 / {diff.summary.nodesChanged} 修改 / {diff.summary.nodesRemoved} 删除）
              </Typography.Title>
              <Collapse
                size="small"
                // 默认展开第一个变化项，用户不用先点开才知道改了什么
                defaultActiveKey={diff.nodes.length > 0 ? [`node:${diff.nodes[0].code}`] : []}
                items={diff.nodes.map((node) => ({
                  key: `node:${node.code}`,
                  label: (
                    <Space wrap size={6}>
                      {kindTag(node.kind)}
                      <Typography.Text strong>{node.name}</Typography.Text>
                      <Typography.Text code>{node.code}</Typography.Text>
                    </Space>
                  ),
                  children: (
                    <Space direction="vertical" size={8} style={{ width: '100%' }}>
                      <FieldDiff fields={node.fields} />
                      {node.scriptDiff ? (
                        <div>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            脚本差异
                          </Typography.Text>
                          <LineDiff lines={node.scriptDiff} />
                        </div>
                      ) : null}
                      {node.kind === 'added' ? (
                        <Typography.Text type="secondary">该版本中没有这个节点。</Typography.Text>
                      ) : null}
                      {node.kind === 'removed' ? (
                        <Typography.Text type="secondary">当前编辑内容中已经没有这个节点。</Typography.Text>
                      ) : null}
                    </Space>
                  ),
                }))}
              />
            </div>
          ) : null}
          {diff.files.length > 0 ? (
            <div>
              <Typography.Title level={5} style={{ marginBottom: 8 }}>
                脚本（{diff.summary.filesAdded} 新增 / {diff.summary.filesChanged} 修改 / {diff.summary.filesRenamed} 重命名 / {diff.summary.filesRemoved} 删除）
              </Typography.Title>
              <Collapse
                size="small"
                defaultActiveKey={diff.files.length > 0 ? [`file:${diff.files[0].path}`] : []}
                items={fileItems(diff.files)}
              />
            </div>
          ) : null}
          {diff.edges.length > 0 ? (
            <div>
              <Typography.Title level={5} style={{ marginBottom: 8 }}>
                依赖（{diff.summary.edgesAdded} 新增 / {diff.summary.edgesRemoved} 删除）
              </Typography.Title>
              <Space wrap size={6}>
                {diff.edges.map((edge) => (
                  <Tag
                    key={`${edge.kind}:${edge.from}:${edge.to}`}
                    color={edge.kind === 'added' ? 'green' : 'red'}
                  >
                    {edge.kind === 'added' ? '+' : '-'} {edge.from} → {edge.to}
                  </Tag>
                ))}
              </Space>
            </div>
          ) : null}
          {diff.folders.length > 0 ? (
            <div>
              <Typography.Title level={5} style={{ marginBottom: 8 }}>
                目录（{diff.summary.foldersAdded} 新增 / {diff.summary.foldersRenamed} 重命名 / {diff.summary.foldersRemoved} 删除）
              </Typography.Title>
              <Space wrap size={6}>
                {diff.folders.map((folder) => (
                  <Tag
                    key={`${folder.kind}:${folder.from || ''}:${folder.path}`}
                    color={folder.kind === 'added' ? 'green'
                      : folder.kind === 'renamed' ? 'blue' : 'red'}
                  >
                    {folder.kind === 'added' ? '+' : folder.kind === 'renamed' ? '⇄' : '-'}{' '}
                    {folder.kind === 'renamed' && folder.from
                      ? `${folder.from} → ${folder.path}` : folder.path}
                  </Tag>
                ))}
              </Space>
            </div>
          ) : null}
        </Space>
      )}
    </Drawer>
  )
}
