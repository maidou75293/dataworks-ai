/**
 * 测试宿主：复刻 MosWorkbench 的订阅方式（useSyncExternalStore(model.subscribe)），
 * 让 model.setChoice/setExtra/markEdited 触发的 notify 能驱动 WsPanel 重渲染。
 */
import { useSyncExternalStore } from 'react'
import { WorkbenchModel, type Transport } from '../../../../pages/mos/wsLifecycle'
import { deriveControls, emptyState, type WsSchema } from '../../../../pages/mos/wsSchema'
import WsPanel from '../WsPanel'

function stubTransport(): Transport {
  const no = (async () => { throw new Error('transport 不接单测') }) as never
  return { lsConfig: no, lsProject: no, lsStats: no, lsTasks: no, lsTask: no,
           lsAnnotate: no, datasets: no, lsSyncMap: no, lsToDataset: no }
}

export function makeModel(schema: Partial<WsSchema> = {}): WorkbenchModel {
  const model = new WorkbenchModel({ transport: stubTransport() })
  model.pid = 1
  model.schema = { mode: 'text', controls: [], text_fields: [], hypertext_fields: [],
                   media: null, frame_rate: 25, ...schema }
  model.ctl = deriveControls(model.schema)
  model.s = emptyState()
  model.selected = { id: 7 }
  model.baseline = ''
  return model
}

export function Host({ model }: { model: WorkbenchModel }) {
  // subscribe 是类方法：必须包箭头函数保 this（同 MosWorkbench 挂载写法）
  useSyncExternalStore((cb) => model.subscribe(cb), () => model.version)
  return <WsPanel model={model} />
}
