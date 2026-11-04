/**
 * undo-redo.test.ts —— REQ-UNIFY-6-5 验收①：每类区域一条 undo/redo 断言（13 组全覆盖）。
 * 对照 Vue 原件 historyState:1657 / applyHistory:1680 / flushHistory:1736 /
 * resetHistory:1747 / undo:1757 / redo:1767 / burst 合并 watch(historyState):1795。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { openTask } from './fixtures'

const flush = () => vi.advanceTimersByTime(320)   // HISTORY_BURST_MS

type ArrayField = 'segments' | 'boxes' | 'polygons' | 'keypoints' | 'ellipses'
  | 'textSpans' | 'hyperSpans' | 'relations' | 'tsSegments' | 'brushRegions'

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

const GROUPS: Array<{ name: string; field: ArrayField; sample: Record<string, unknown> }> = [
  { name: '区间 segments', field: 'segments',
    sample: { id: 'g1', label: '猫', start: 0, end: 5, text: '你好' } },
  { name: '矩形框 boxes', field: 'boxes',
    sample: { id: 'b1', label: '猫', x: 10, y: 20, w: 30, h: 40 } },
  { name: '多边形 polygons', field: 'polygons',
    sample: { id: 'p1', label: '猫', points: [[1, 2], [3, 4], [5, 6]] } },
  { name: '关键点 keypoints', field: 'keypoints',
    sample: { id: 'k1', label: '点', x: 12, y: 34 } },
  { name: '椭圆 ellipses', field: 'ellipses',
    sample: { id: 'e1', label: '眼', x: 10, y: 20, w: 8, h: 6 } },
  { name: '文本区间 textSpans', field: 'textSpans',
    sample: { id: 's1', label: '实体', start: 0, end: 2, text: '某词' } },
  { name: '超文本 hyperSpans', field: 'hyperSpans',
    sample: { id: 'h1', label: '实体', text: '某词', startOffset: 0, endOffset: 4 } },
  { name: '关系 relations', field: 'relations',
    sample: { id: 'r1', from_id: 'g1', to_id: 'g2', direction: '→' } },
  { name: '时间序列 tsSegments', field: 'tsSegments',
    sample: { id: 't1', label: '段', start: 1, end: 2 } },
  { name: '笔刷 brushRegions', field: 'brushRegions',
    sample: { id: 'br1', label: '发', w: 2, h: 2, rle: [0, 1, 1, 0] } },
]

describe('13 组区域类型 × undo/redo（验收①）', () => {
  // 10 个区域数组组
  for (const g of GROUPS) {
    it(`${g.name}：入栈→撤销清空→重做还原`, async () => {
      const m = await openTask()
      ;(m.s[g.field] as Array<Record<string, unknown>>).push(g.sample)
      m.touch()
      flush()
      expect(m.undoCount).toBe(1)
      expect(m.redoCount).toBe(0)
      expect(m.undo()).toBe(true)
      expect(m.s[g.field]).toHaveLength(0)
      expect(m.redoCount).toBe(1)
      expect(m.redo()).toBe(true)
      expect(m.s[g.field][0]).toEqual(g.sample)
      expect(m.undo()).toBe(true)   // 二次撤销回基线
      expect(m.s[g.field]).toHaveLength(0)
    })
  }

  // 11) texts —— 含 historyState 的 text 镜像键（Vue:1661，草稿值结构照搬的前提）
  it('文本答案 texts：镜像键同步入栈', async () => {
    const m = await openTask()
    m.setText('answer', '甲')
    flush()
    const h = () => JSON.parse(m.historyState())
    expect(h().text).toBe('甲')
    expect(h().texts.answer).toBe('甲')
    expect(m.undo()).toBe(true)
    expect(m.s.texts.answer).toBe('')
    expect(h().text).toBe('')
    expect(m.redo()).toBe(true)
    expect(m.s.texts.answer).toBe('甲')
  })

  // 12) choices —— 单选回空串（applyHistory 按控件重建，非裸还原）
  it('分类 choices：撤销回控件缺省值', async () => {
    const m = await openTask()
    m.setChoice('ch1', 'A')
    flush()
    expect(m.undo()).toBe(true)
    expect(m.s.choiceVals.ch1).toBe('')
    expect(m.redo()).toBe(true)
    expect(m.s.choiceVals.ch1).toBe('A')
  })

  // 13) extra（rating/number/datetime/taxonomy/pairwise）
  it('附加控件 extra：撤销后键被清除', async () => {
    const m = await openTask()
    m.setExtra('rate', 4)
    flush()
    expect(JSON.parse(m.historyState()).extra).toEqual({ rate: 4 })
    expect(m.undo()).toBe(true)
    expect(m.s.extra).toEqual({})
    expect(m.redo()).toBe(true)
    expect(m.s.extra.rate).toBe(4)
  })

  // 13 组之外的画布瞬时态：polyDraft 也在序列化全集里（卡面「序列化对象=全集」）
  it('polyDraft 绘图草稿同样一步可撤', async () => {
    const m = await openTask()
    m.s.polyDraft = [[1, 2], [3, 4]]
    m.touch()
    flush()
    expect(m.undo()).toBe(true)
    expect(m.s.polyDraft).toEqual([])
    expect(m.redo()).toBe(true)
    expect(m.s.polyDraft).toEqual([[1, 2], [3, 4]])
  })
})

describe('连续输入合并（burst=320ms，Vue:1795）', () => {
  it('窗口内三次输入 = 一步，撤销直回起点', async () => {
    const m = await openTask()
    m.setText('answer', 'a')
    vi.advanceTimersByTime(100)
    m.setText('answer', 'ab')
    vi.advanceTimersByTime(100)
    m.setText('answer', 'abc')
    flush()
    expect(m.undoCount).toBe(1)
    expect(m.undo()).toBe(true)
    expect(m.s.texts.answer).toBe('')   // burst 前锚点=空串，不是 'ab'
  })

  it('隔开的两笔 = 两步，逐级回退', async () => {
    const m = await openTask()
    m.setText('answer', 'a')
    flush()
    m.setText('answer', 'ab')
    flush()
    expect(m.undoCount).toBe(2)
    m.undo()
    expect(m.s.texts.answer).toBe('a')
    m.undo()
    expect(m.s.texts.answer).toBe('')
    m.redo()
    expect(m.s.texts.answer).toBe('a')
    m.redo()
    expect(m.s.texts.answer).toBe('ab')
  })

  it('burst 内改了又改回 = 净零不入栈（flushHistory:1740 before===cur）', async () => {
    const m = await openTask()
    m.setText('answer', 'a')
    vi.advanceTimersByTime(100)
    m.setText('answer', '')
    flush()
    expect(m.undoCount).toBe(0)
    // 分两笔（各自落帧）时，改回原样按 Vue 语义仍是一步「a→」
    m.setText('answer', 'a')
    flush()
    m.setText('answer', '')
    flush()
    expect(m.undoCount).toBe(2)
    m.undo()
    expect(m.s.texts.answer).toBe('a')
  })

  it('未选中任务不记步（noteDataChange !selected 闸）', async () => {
    const m = await openTask()
    m.selected = null
    m.setText('answer', 'a')
    flush()
    expect(m.undoCount).toBe(0)
    expect(m.undo()).toBe(false)
    expect(m.redo()).toBe(false)
  })
})

describe('栈深上限 HISTORY_MAX=30（Vue:1656/1736）', () => {
  it('第 31 笔挤掉最早一帧', async () => {
    const m = await openTask()
    for (let i = 1; i <= 32; i++) {
      m.setText('answer', String(i))
      flush()
    }
    expect(m.undoCount).toBe(30)
    let n = 0
    while (m.undo()) n++
    expect(n).toBe(30)
    // 入栈帧序列 = ['','1',…,'31']，超限挤出最早两帧后剩 '2'..'31'
    expect(m.s.texts.answer).toBe('2')
  })
})

describe('换任务清栈（resetHistory:1747，Vue resetAnnotation nextTick:2357）', () => {
  it('切任务后栈=0，且在途 burst 丢弃', async () => {
    const m = await openTask()
    m.setText('answer', '未保存')
    expect(m.undoCount).toBe(0)          // 320ms 未到，还没入栈
    await m.pick(m.tasks[1])
    expect(m.undoCount).toBe(0)
    expect(m.redoCount).toBe(0)
    expect(m.s.texts.answer).toBe('')    // 新任务基线
    flush()                              // 旧定时器已被 resetHistory 清掉
    expect(m.undoCount).toBe(0)
  })
})

describe('Ctrl+Z/Y 键分发（onKey:4649-4652）', () => {
  it('有栈=undo/redo，空栈也消费按键；文本框与弹窗不抢', async () => {
    const m = await openTask()
    m.setText('answer', '甲')
    flush()
    expect(await m.onKey({ key: 'z', ctrlKey: true, tagName: 'body' })).toBe('undo')
    expect(m.s.texts.answer).toBe('')
    expect(await m.onKey({ key: 'Z', ctrlKey: true, shiftKey: true, tagName: 'body' })).toBe('redo')
    expect(m.s.texts.answer).toBe('甲')
    expect(await m.onKey({ key: 'y', ctrlKey: true, tagName: 'body' })).toBe('redo-empty')
    m.undo()
    expect(await m.onKey({ key: 'z', ctrlKey: true, tagName: 'body' })).toBe('undo-empty')
    // 文本框内保留浏览器原生撤销（Vue 同闸）
    m.setText('answer', '乙')
    flush()
    expect(await m.onKey({ key: 'z', ctrlKey: true, tagName: 'textarea' })).toBeNull()
    expect(m.s.texts.answer).toBe('乙')
    // 弹窗打开时不抢键
    m.modalOpen = true
    expect(await m.onKey({ key: 'z', ctrlKey: true, tagName: 'body' })).toBeNull()
    m.modalOpen = false
  })
})
