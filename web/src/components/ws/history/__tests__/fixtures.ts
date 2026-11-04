/**
 * fixtures.ts —— REQ-UNIFY-6-5 单测夹具：注入 transport/storage/now/askConfirm/toast，
 * 走真实 setPid→loadShell→loadTasks→pick 链路（不 mock 状态机内部）。
 */
import { WorkbenchModel } from '../../../../pages/mos/wsLifecycle'
import type { Transport, WsStorage } from '../../../../pages/mos/wsLifecycle'
import type { WsSchema } from '../../../../pages/mos/wsSchema'

export const T0 = Date.parse('2026-10-28T00:00:00Z')

/** 全控件 schema：覆盖 13 组区域类型 + texts/choices/extra 一条道跑齐 */
export const FULL_SCHEMA: WsSchema = {
  mode: 'image',
  controls: [
    { type: 'labels', from_name: 'lbl', to_name: 'img', options: [{ value: '猫' }, { value: '狗' }] },
    { type: 'rectanglelabels', from_name: 'box1', to_name: 'img', options: [{ value: '猫' }] },
    { type: 'polygonlabels', from_name: 'poly1', to_name: 'img', options: [{ value: '猫' }] },
    { type: 'keypointlabels', from_name: 'kp1', to_name: 'img', options: [{ value: '点' }] },
    { type: 'ellipselabels', from_name: 'el1', to_name: 'img', options: [{ value: '眼' }] },
    { type: 'brushlabels', from_name: 'brush1', to_name: 'img', options: [{ value: '发' }] },
    { type: 'hypertextlabels', from_name: 'hyper1', to_name: 'txt', options: [{ value: '实体' }] },
    { type: 'timelinelabels', from_name: 'seg1', to_name: 'aud', options: [{ value: '段' }] },
    { type: 'relations', from_name: 'rel1', to_name: 'lbl' },
    { type: 'textarea', from_name: 'answer', to_name: 'img' },
    { type: 'choices', from_name: 'ch1', to_name: 'answer', options: [{ value: 'A' }, { value: 'B' }] },
    { type: 'rating', from_name: 'rate', to_name: 'answer' },
  ],
  text_fields: [{ field: 'input', name: 'txt' }],
  hypertext_fields: [{ field: 'text' }],
  media: { field: 'image' },
  frame_rate: 25,
}

/** 多选版（草稿 map 结构断言用：choices 值=数组才不被单选归一化吃掉） */
export const MULTI_SCHEMA: WsSchema = {
  ...FULL_SCHEMA,
  controls: FULL_SCHEMA.controls!.map((c) => (c.type === 'choices' ? { ...c, multiple: true } : c)),
}

export function memStorage(): WsStorage & { map: Map<string, string> } {
  const map = new Map<string, string>()
  return {
    map,
    getItem: (k: string) => (map.has(k) ? map.get(k)! : null),
    setItem: (k: string, v: string) => { map.set(k, v) },
    removeItem: (k: string) => { map.delete(k) },
    keys: () => [...map.keys()],
  }
}

export type TransportOver = {
  schema?: WsSchema
  detail?: Record<string, Record<string, any>>
}

export function makeTransport(over: TransportOver = {}): Transport {
  const detail = over.detail || {}
  return {
    lsConfig: async () => ({ url: 'http://127.0.0.1:8080' }),
    lsProject: async (pid: number) => ({
      id: pid, title: 'P' + pid, workbench: true, schema: over.schema || FULL_SCHEMA,
      show_skip_button: true,
    }),
    lsStats: async () => ({ total: 2, annotated: 0, unannotated: 2 }),
    lsTasks: async () => ({
      items: [
        { id: 1, annotated: false, data: { image: '/i1.jpg' } },
        { id: 2, annotated: false, data: { image: '/i2.jpg' } },
      ],
      matched: 2,
    }),
    lsTask: async (_p: number, tid: number) => detail[String(tid)] || { annotations: [] },
    lsAnnotate: async () => ({ id: 900 }),
    datasets: async () => [],
    lsSyncMap: async () => ({}),
    lsToDataset: async () => ({}),
  }
}

export type ModelOver = TransportOver & {
  storage?: WsStorage | null
  now?: () => number
  ask?: boolean
  toasts?: Array<['ok' | 'bad' | 'warn', string]>
}

export function makeModel(over: ModelOver = {}): WorkbenchModel {
  const toasts = over.toasts || []
  return new WorkbenchModel({
    transport: makeTransport(over),
    storage: over.storage ?? null,
    now: over.now ?? (() => T0),
    askConfirm: async () => over.ask !== false,   // 默认同意「仍然离开」
    toast: (lv, t) => { toasts.push([lv, t]) },
  })
}

/** 打开项目 7 并落在任务 1（loadTasks:4153 首屏自动选第一条的真实路径） */
export async function openTask(over: ModelOver = {}): Promise<WorkbenchModel> {
  const m = makeModel(over)
  await m.setPid(7)
  if (!m.selected || m.selected.id !== 1) throw new Error('夹具：任务 1 未就绪')
  return m
}
