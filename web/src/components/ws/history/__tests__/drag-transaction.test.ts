/**
 * drag-transaction.test.ts —— REQ-UNIFY-6-5 验收②：拖拽=一次事务。
 * beginDragHistory:1777 / endDragHistory:1783；走真实 CanvasEngine 拖动链
 * （beginShapeDrag → pointermove×N → pointerup），证明引擎静音闸+落帧只在收尾发生一次。
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { CanvasEngine } from '../../canvas/engine'
import type { CanvasHost, EngineEnv, PointerLike } from '../../canvas/engine'
import { openTask } from './fixtures'

const flush = () => vi.advanceTimersByTime(320)

beforeEach(() => { vi.useFakeTimers() })
afterEach(() => { vi.useRealTimers() })

function fakeWin() {
  const reg = new Map<string, Array<(e: any) => void>>()
  return {
    innerWidth: 800,
    addEventListener(t: string, fn: (e: any) => void) {
      const a = reg.get(t) || []
      a.push(fn)
      reg.set(t, a)
    },
    removeEventListener(t: string, fn: (e: any) => void) {
      reg.set(t, (reg.get(t) || []).filter((x) => x !== fn))
    },
    fire(t: string, e: any) {
      (reg.get(t) || []).slice().forEach((fn) => fn(e))
    },
  }
}

function makeHarness() {
  return openTask().then((m) => {
    const host: CanvasHost = {
      get s() { return m.s },
      get ctl() { return m.ctl },
      get mode() { return m.mode },
      get selected() { return m.selected },
      touch: () => m.touch(),
      beginDragHistory: () => m.beginDragHistory(),
      endDragHistory: () => m.endDragHistory(),
    }
    const win = fakeWin()
    const env = {
      stageRect: () => ({ left: 0, top: 0, width: 400, height: 300 }),
      scroll: () => ({ clientWidth: 400, clientHeight: 300, scrollWidth: 400, scrollHeight: 300, scrollLeft: 0, scrollTop: 0 }),
      media: () => null,
      win,
      fullscreen: { request() {}, exit() {}, active: () => false },
      fetchText: async () => '',
    } as unknown as EngineEnv
    return { m, eng: new CanvasEngine(host, env), win }
  })
}

const pt = (x: number, y: number): PointerLike => ({
  clientX: x, clientY: y, pointerType: 'mouse', button: 0, isPrimary: true,
  preventDefault() {}, stopPropagation() {}, currentTarget: null,
})

async function withBox() {
  const h = await makeHarness()
  h.m.s.boxes.push({ id: 'b1', label: '猫', x: 10, y: 10, w: 20, h: 20 })
  h.m.touch()
  flush()
  return h
}

describe('拖拽=一次事务（验收②）', () => {
  it('beginDrag 后连续 pointermove 仅压 1 帧（真引擎链）', async () => {
    const { m, eng, win } = await withBox()
    expect(m.undoCount).toBe(1)                       // 建框帧
    eng.beginShapeDrag(pt(60, 30), 'box', 0)
    expect(eng.shapeDrag.active).toBe(true)
    for (const x of [80, 100, 120, 140, 160]) {
      win.fire('pointermove', { clientX: x, clientY: 30, preventDefault() {} })
    }
    expect(m.undoCount).toBe(1)                       // 拖动全程 0 新帧
    expect(m.redoCount).toBe(0)
    win.fire('pointerup', {})
    expect(eng.shapeDrag.active).toBe(false)
    expect(m.undoCount).toBe(2)                       // 收尾合计恰好 1 帧
    expect((m.s.boxes[0] as any).x).toBeGreaterThan(10)
    // 微任务后静音解除：下一次编辑正常起步新 burst
    await Promise.resolve()
    m.setText('answer', '乙')
    flush()
    expect(m.undoCount).toBe(3)
    // 逐帧回退：文本 → 拖拽（回拖前坐标）→ 建框（框消失）
    expect(m.undo()).toBe(true)
    expect(m.s.texts.answer).toBe('')
    expect(m.undo()).toBe(true)
    expect(m.s.boxes[0].x).toBe(10)
    expect(m.s.boxes[0].y).toBe(10)
    expect(m.undo()).toBe(true)
    expect(m.s.boxes).toHaveLength(0)
    expect(m.redo()).toBe(true)                       // 重做建框
    expect(m.s.boxes[0].x).toBe(10)
    expect(m.redo()).toBe(true)                       // 重做拖拽结果
    expect((m.s.boxes[0] as any).x).toBeGreaterThan(10)
  })

  it('原地按下再松手 = 不入栈（before===cur）', async () => {
    const { m, eng, win } = await withBox()
    eng.beginShapeDrag(pt(60, 30), 'box', 0)
    win.fire('pointerup', {})
    expect(m.undoCount).toBe(1)
  })

  it('beginDragHistory 幂等：连按两次收尾仍 1 帧', async () => {
    const m = await openTask()
    m.s.boxes.push({ id: 'b1', label: '猫', x: 10, y: 10, w: 20, h: 20 })
    m.touch()
    flush()
    expect(m.undoCount).toBe(1)
    m.beginDragHistory()
    m.beginDragHistory()                               // dragCheckpoint 已在：直接 return
    ;(m.s.boxes[0] as any).x = 40
    m.touch()
    m.endDragHistory()
    await Promise.resolve()
    expect(m.undoCount).toBe(2)                       // 只压一帧，不是两帧
  })

  it('end 无 begin = 空操作', async () => {
    const m = await openTask()
    m.endDragHistory()
    expect(m.undoCount).toBe(0)
    m.s.boxes.push({ id: 'b1', label: '猫', x: 1, y: 1, w: 2, h: 2 })
    m.touch()
    flush()
    expect(m.undoCount).toBe(1)
  })

  it('在途 burst 先落定再打拖拽检查点（begin 前未 flush 的编辑不丢）', async () => {
    const m = await openTask()
    m.setText('answer', '甲')                          // burst 在途，未 flush
    m.beginDragHistory()                               // begin 内 flushHistory 先落这一步
    expect(m.undoCount).toBe(1)
    m.s.polyDraft = [[1, 1]]
    m.s.keypoints.push({ id: 'k1', label: '点', x: 5, y: 5 })
    m.touch()
    m.endDragHistory()
    await Promise.resolve()
    expect(m.undoCount).toBe(2)
    expect(m.redoCount).toBe(0)
    m.undo()
    expect(m.s.keypoints).toHaveLength(0)
    expect(m.s.texts.answer).toBe('甲')                // 文本帧独立保留
  })

  it('polyDraft 单源：applyHistory 换数组后引擎读到的就是新数组（悬空镜像回归）', async () => {
    const { m, eng } = await makeHarness()
    m.s.polyDraft = [[1, 2], [3, 4]]
    m.touch()
    flush()
    expect(eng.polyDraft).toEqual([[1, 2], [3, 4]])
    expect(eng.polyDraft).toBe(m.s.polyDraft)
    expect(m.undo()).toBe(true)                        // applyHistory 用 clone 整体替换
    expect(eng.polyDraft).toEqual([])
    expect(eng.polyDraft).toBe(m.s.polyDraft)
    expect(m.redo()).toBe(true)
    expect(eng.polyDraft).toEqual([[1, 2], [3, 4]])
    expect(eng.polyDraft).toBe(m.s.polyDraft)
  })
})
