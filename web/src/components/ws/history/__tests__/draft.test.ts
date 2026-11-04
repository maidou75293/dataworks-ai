/**
 * draft.test.ts —— REQ-UNIFY-6-5 验收③：草稿 draftKey 格式与值结构照搬 Vue，
 * 同 origin 新旧（React/Vue）共存期不互相污染。
 * 对照 persistDraftNow:2101 / offerDraft:2138 / scanDrafts:2079 / snapshot watch:2196。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { memStorage, makeModel, openTask, T0, MULTI_SCHEMA } from './fixtures'
import type { WsStorage } from '../../../../pages/mos/wsLifecycle'
import { snapshotOf } from '../../../../pages/mos/wsSchema'

const DAY = 24 * 3600 * 1000
const KEY1 = 'ls.draft.v1.7.1'
const KEY2 = 'ls.draft.v1.7.2'

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

const emptyArrays = {
  segments: [], boxes: [], polygons: [], keypoints: [], ellipses: [],
  spans: [], hyper: [], relations: [], ts: [], brush: [],
  polyDraft: [], brushDraft: [], extra: {},
}
/** React 新格式：带 texts/choices 映射 */
const reactState = (texts: Record<string, string>, choices: Record<string, unknown> = {}) =>
  JSON.stringify({ text: Object.values(texts)[0] || '', texts, choices, ...emptyArrays })
/** Vue 旧格式：只有 text 镜像 + choices 数组（texts 缺省） */
const legacyState = (text: string, choices?: string[]) =>
  JSON.stringify({ text, ...(choices === undefined ? {} : { choices }), ...emptyArrays })

function seed(st: WsStorage, key: string, v: Record<string, any>) {
  st.setItem(key, JSON.stringify(v))
}

describe('draftKey 格式与值结构（验收③-1，Vue:2068/2101）', () => {
  it('watch(snapshot) 600ms 落盘：键 ls.draft.v1.<pid>.<taskId>，值六字段', async () => {
    const st = memStorage()
    const m = await openTask({ storage: st })
    m.setText('answer', '草稿内容')
    vi.advanceTimersByTime(600)
    expect([...st.map.keys()]).toEqual([KEY1])         // ls.draft.v1.7.1
    const v = JSON.parse(st.getItem(KEY1)!)
    expect(Object.keys(v).sort())
      .toEqual(['annotation_id', 'at', 'restored', 'server_at', 'snap', 'state'])
    expect(v.at).toBe(T0)
    expect(v.state).toBe(m.historyState())             // state=historyState() 全集序列化
    expect(JSON.parse(v.state).texts.answer).toBe('草稿内容')
    expect(v.snap).toBe(snapshotOf(m.s, m.ctl))
    expect(v.restored).toBe(false)
    expect(v.annotation_id).toBeNull()                 // 还没存过标注
    expect(v.server_at).toBe('')
  })

  it('视图类 emit（快照没变）不虚写草稿（snapAnchor 闸=Vue watch 语义）', async () => {
    const st = memStorage()
    const m = await openTask({ storage: st })
    m.touch()                                           // zoom/timeupdate 这类等价 emit
    vi.advanceTimersByTime(1000)
    expect(st.map.size).toBe(0)
  })

  it('未保存离开（beforeUnload:4627）先落草稿', async () => {
    const st = memStorage()
    const m = await openTask({ storage: st })
    expect(m.beforeUnload()).toBe(false)                // 干净的走人
    m.setText('answer', '走前存一把')
    expect(m.beforeUnload()).toBe(true)
    expect(st.map.has(KEY1)).toBe(true)
  })

  it('300KB 超限：不落盘、只提醒一次（Vue:2105）', async () => {
    const st = memStorage()
    const toasts: Array<['ok' | 'bad' | 'warn', string]> = []
    const m = await openTask({ storage: st, toasts })
    m.setText('answer', 'x'.repeat(320000))
    vi.advanceTimersByTime(600)
    expect(st.map.size).toBe(0)
    expect(toasts.filter((t) => t[0] === 'bad')).toHaveLength(1)
    m.setText('answer', 'y'.repeat(320000))
    vi.advanceTimersByTime(1200)
    expect(toasts.filter((t) => t[0] === 'bad')).toHaveLength(1)   // draftWarned 不刷屏
  })
})

describe('scanDrafts 过期与坏数据（Vue:2079）', () => {
  it('30 天外的、解析失败的清掉；非数字任务号与其他项目键原样保留', async () => {
    const st = memStorage()
    seed(st, 'ls.draft.v1.7.5', { at: T0 - DAY, state: '{"text":""}' })            // 新鲜
    seed(st, 'ls.draft.v1.7.6', { at: T0 - 31 * DAY, state: '{"text":""}' })       // 过期
    st.setItem('ls.draft.v1.7.7', '{坏数据')                                        // 解析失败
    seed(st, 'ls.draft.v1.7.abc', { at: T0, state: '{"text":""}' })                 // 非数字 tid
    seed(st, 'ls.draft.v1.8.9', { at: T0, state: '{"text":""}' })                   // 别的项目
    const m = makeModel({ storage: st })
    await m.setPid(7)                                                               // setPid→scanDrafts
    expect(m.draftIds).toEqual({ '5': true })
    expect(st.map.has('ls.draft.v1.7.5')).toBe(true)
    expect(st.map.has('ls.draft.v1.7.6')).toBe(false)
    expect(st.map.has('ls.draft.v1.7.7')).toBe(false)
    expect(st.map.has('ls.draft.v1.7.abc')).toBe(true)
    expect(st.map.has('ls.draft.v1.8.9')).toBe(true)
  })
})

describe('新旧格式共存期不互相污染（验收③-2，不做跨 origin 迁移）', () => {
  it('React 写新草稿不碰 Vue 旧草稿；旧草稿可读且回填规则照搬', async () => {
    const st = memStorage()
    const legacyRaw = JSON.stringify({
      at: T0, state: legacyState('旧值答案', ['A']), snap: '"legacy-snap"',
      restored: false, annotation_id: null, server_at: '',
    })
    seed(st, KEY2, JSON.parse(legacyRaw))
    const m = await openTask({ storage: st, schema: MULTI_SCHEMA })

    // 任务1 走 React 新格式落盘——不得触碰任务2 的旧格式条目
    m.setText('answer', '新格式内容')
    vi.advanceTimersByTime(600)
    const key1Raw = st.getItem(KEY1)!
    expect(JSON.parse(key1Raw).state).toContain('"texts"')
    expect(st.getItem(KEY2)).toBe(legacyRaw)            // 旧条目逐字节原样

    // 切到任务2：旧格式被正确读取（text 回填首框 / choices 数组回填首组）
    await m.pick(m.tasks[1])
    expect(m.s.texts.answer).toBe('旧值答案')
    expect(m.s.choiceVals.ch1).toEqual(['A'])           // 多选组保留数组
    expect(m.draftConflict).toBe(false)
    expect(m.undoCount).toBe(0)                          // 恢复草稿不算编辑步
    vi.advanceTimersByTime(5000)                         // 恢复后没有新的用户编辑
    expect(st.getItem(KEY2)).toBe(legacyRaw)             // 不自动重排落盘污染旧值
    // 任务1 的新格式条目仍在、内容未变（切任务时 dirty 重写同键同值）
    expect(st.getItem(KEY1)).toBe(key1Raw)

    m.dropDraft()                                        // 「用服务器版本」只清自己这条
    expect(st.map.has(KEY2)).toBe(false)
    expect(st.getItem(KEY1)).toBe(key1Raw)
  })

  it('旧格式单选组：数组归一取首值（Vue:1706-1710）', async () => {
    const st = memStorage()
    seed(st, KEY1, { at: T0, state: legacyState('单选值', ['B', 'A']), snap: '"x"',
      restored: false, annotation_id: null, server_at: '' })
    const m = await openTask({ storage: st })           // FULL_SCHEMA：ch1 单选
    expect(m.s.texts.answer).toBe('单选值')
    expect(m.s.choiceVals.ch1).toBe('B')
  })
})

describe('冲突与处置（offerDraft:2147-2171 两分支）', () => {
  it('同标注 server_at 变了：不自动覆盖，applyDraft/dropDraft 由用户评审确认', async () => {
    const st = memStorage()
    seed(st, KEY1, { at: T0, state: reactState({ answer: '冲突草稿' }), snap: '"x"',
      restored: false, annotation_id: 55, server_at: 'srv-old' })
    const m = makeModel({ storage: st,
      detail: { '1': { annotations: [{ id: 55, mine: true, updated_at: 'srv-new', result: [] }] } } })
    await m.setPid(7)
    expect(m.draftConflict).toBe(true)
    expect(m.draftOffer).toBeTruthy()
    expect(m.s.texts.answer).toBe('')                   // 草稿没被静默应用
    expect(m.dirty).toBe(false)                         // 基线已改按服务器版本
    expect(m.undoCount).toBe(0)
    m.applyDraft()
    expect(m.s.texts.answer).toBe('冲突草稿')
    expect(m.s.restored).toBe(true)
    expect(m.draftConflict).toBe(false)
    expect(m.undoCount).toBe(0)                         // 应用草稿同样不入栈
    expect(m.undo()).toBe(false)
    expect(st.map.has(KEY1)).toBe(true)                 // 应用后条目仍在（保存才清）
  })

  it('打草稿时没有我的标注、之后别的标签页存出一份：也算冲突（Vue:2160）', async () => {
    const st = memStorage()
    seed(st, KEY1, { at: T0, state: reactState({ answer: '无主草稿' }), snap: '"x"',
      restored: false, annotation_id: null, server_at: '' })
    const m = makeModel({ storage: st,
      detail: { '1': { annotations: [{ id: 77, mine: true, updated_at: 'other-tab', result: [] }] } } })
    await m.setPid(7)
    expect(m.draftConflict).toBe(true)
    expect(m.s.texts.answer).toBe('')
  })

  it('无冲突自动应用；dropDraft 回服务器版本并删条目', async () => {
    const st = memStorage()
    seed(st, KEY1, { at: T0, state: reactState({ answer: '自动草稿' }), snap: '"x"',
      restored: true, annotation_id: null, server_at: '' })
    const m = await openTask({ storage: st })
    expect(m.draftConflict).toBe(false)
    expect(m.s.texts.answer).toBe('自动草稿')
    expect(m.s.restored).toBe(true)
    m.dropDraft()
    expect(st.map.has(KEY1)).toBe(false)
    expect(m.s.texts.answer).toBe('')                   // resetForTask 回服务器版本
    expect(m.draftOffer).toBeNull()
    expect(m.undoCount).toBe(0)
  })

  it('snap 与当前快照一致=草稿没增量：直接清掉不提示（Vue:2144）', async () => {
    const st = memStorage()
    const m = await openTask({ storage: st })
    m.setText('answer', '同步一份')
    m.persistDraftNow()                                 // 草稿含未保存内容
    // 模拟「快照与当前一致」：把 snap 改写成当前快照再重新过 offer 闸
    const v = JSON.parse(st.getItem(KEY1)!)
    v.snap = snapshotOf(m.s, m.ctl)
    seed(st, KEY1, v)
    m.offerDraft(m.selected!.id)                        // pick 路径同此入口（:386）
    expect(m.draftOffer).toBeNull()
    expect(m.draftConflict).toBe(false)
    expect(st.map.has(KEY1)).toBe(false)                // 一致=没改过，条目顺手清
  })

  it('dismissDraft 收条不清库', async () => {
    const st = memStorage()
    seed(st, KEY1, { at: T0, state: reactState({ answer: '冲突草稿' }), snap: '"x"',
      restored: false, annotation_id: 55, server_at: 'srv-old' })
    const m = makeModel({ storage: st,
      detail: { '1': { annotations: [{ id: 55, mine: true, updated_at: 'srv-new', result: [] }] } } })
    await m.setPid(7)
    expect(m.draftConflict).toBe(true)
    m.dismissDraft()
    expect(m.draftOffer).toBeNull()
    expect(m.draftConflict).toBe(false)
    expect(st.map.has(KEY1)).toBe(true)
    expect(m.s.texts.answer).toBe('')                   // 只是收起，草稿没应用
  })
})
