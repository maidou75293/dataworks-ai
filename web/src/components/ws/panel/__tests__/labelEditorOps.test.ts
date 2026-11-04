/**
 * 标签编辑器纯操作测试（卡面「标签编辑器」验收的行为面）：
 * labelEditorRows:1835 / buildLabelSave:1907-1921 / applyLabelRename:1854-1903 /
 * toggleRegionLabel:2418；区域关系 addRelation:2820、区间 addSegment:3211。
 * （弹窗请求链路 lsSetLabels 属金样回放/联调面，不在单测里发真请求。）
 */
import { describe, expect, it } from 'vitest'
import { deriveControls, emptyState, isoToLocalInput, type Region, type SchemaCtl } from '../../../../pages/mos/wsSchema'
import {
  addRelation, addSegment, applyLabelRename, buildLabelSave, labelEditorRows,
  numberInputToExtra, regionsOf, resetFormForTask, toggleRegionLabel, emptyForm,
} from '../panelOps'

const options = (vals: string[]) => vals.map((v) => ({ value: v }))

describe('labelEditorRows / buildLabelSave（saveLabels:1907）', () => {
  const ctl = { type: 'choices', from_name: 'cat', options: options(['猫', '狗']) } as SchemaCtl

  it('行来自控件 options；orig=value', () => {
    const rows = labelEditorRows(ctl)
    expect(rows.map((r) => [r.orig, r.value])).toEqual([['猫', '猫'], ['狗', '狗']])
    expect(labelEditorRows(null)).toEqual([])
  })

  it('改名 → rename[旧]=新；同名合并（第二行去重不进 rename，与 Vue :1916 continue 同式）', () => {
    const rows = labelEditorRows(ctl).map((r) =>
      r.orig === '猫' ? { ...r, value: '猫咪' } : r.orig === '狗' ? { ...r, value: '猫咪' } : r)
    const built = buildLabelSave(rows)
    expect(built.ok && built).toEqual({ ok: true, labels: ['猫咪'], rename: { 猫: '猫咪' } })
  })

  it('清空一行 = 删除该类别（rename[旧]=\'\'，:1913）', () => {
    const rows = labelEditorRows(ctl).map((r) => (r.orig === '猫' ? { ...r, value: '' } : r))
    const built = buildLabelSave(rows)
    expect(built.ok && built).toEqual({ ok: true, labels: ['狗'], rename: { 猫: '' } })
  })

  it('全部清空 → 拒绝「至少保留一个类别」（:1921 同文案）', () => {
    const rows = labelEditorRows(ctl).map((r) => ({ ...r, value: '  ' }))
    expect(buildLabelSave(rows)).toEqual({ ok: false, msg: '至少保留一个类别' })
  })
})

describe('applyLabelRename（:1854-1903）', () => {
  const mk = (controls: SchemaCtl[]) => {
    const schema = { mode: 'image', controls } as never
    const ctl = deriveControls(schema)
    return { ctl, s: emptyState() }
  }

  it('choices：选中项跟着改名；被删类别退位（rename→\'\'→labels[0]）', () => {
    const c = { type: 'choices', from_name: 'cat', multiple: true, options: options(['猫', '狗']) } as SchemaCtl
    const { ctl, s } = mk([c])
    s.choiceVals.cat = ['猫']
    applyLabelRename({ 猫: '猫咪' }, ['猫咪', '狗'], 'choices', 'cat', s, ctl, emptyForm())
    expect(s.choiceVals.cat).toEqual(['猫咪'])
    applyLabelRename({ 猫咪: '' }, ['狗'], 'choices', 'cat', s, ctl, emptyForm())
    expect(s.choiceVals.cat).toEqual(['狗'])
  })

  it('单选 choices：字符串值同样改写', () => {
    const c = { type: 'choices', from_name: 'q', options: options(['好', '差']) } as SchemaCtl
    const { ctl, s } = mk([c])
    s.choiceVals.q = '好'
    applyLabelRename({ 好: '不错' }, ['不错', '差'], 'choices', 'q', s, ctl, emptyForm())
    expect(s.choiceVals.q).toBe('不错')
  })

  it('rectanglelabels：主 label + labels 数组 + form.boxLabel 一起改；不误伤区间', () => {
    const rc = { type: 'rectanglelabels', from_name: 'box1', options: options(['猫', '狗']) } as SchemaCtl
    const lc = { type: 'labels', from_name: 'seg1', options: options(['人']) } as SchemaCtl
    const { ctl, s } = mk([rc, lc])
    s.boxes.push({ id: 'b1', label: '猫', labels: ['猫', '狗'] })
    s.segments.push({ id: 'e1', label: '猫' })   // 与框同名的区间不许被框改名带歪
    const form = emptyForm(); form.boxLabel = '猫'
    applyLabelRename({ 猫: '猫咪' }, ['猫咪', '狗'], 'rectanglelabels', 'box1', s, ctl, form)
    expect(s.boxes[0].label).toBe('猫咪')
    expect(s.boxes[0].labels).toEqual(['猫咪', '狗'])
    expect(s.segments[0].label).toBe('猫')
    expect(form.boxLabel).toBe('猫咪')
  })

  it('labels（音频区间）：segments/textSpans/segLabel 改写；vrect 共用时改走框分支（:1895）', () => {
    const lc = { type: 'labels', from_name: 'seg1', options: options(['猫', '狗']) } as SchemaCtl
    const { ctl, s } = mk([lc])
    s.segments.push({ id: 'e1', label: '猫' })
    s.textSpans.push({ id: 't1', label: '猫', labels: ['猫'] })
    const form = emptyForm(); form.segLabel = '猫'
    applyLabelRename({ 猫: '猫咪' }, ['猫咪', '狗'], 'labels', 'seg1', s, ctl, form)
    expect(s.segments[0].label).toBe('猫咪')
    expect(s.textSpans[0].labels).toEqual(['猫咪'])
    expect(form.segLabel).toBe('猫咪')
  })

  it('未出现在 rename 的名字原样保留', () => {
    const rc = { type: 'rectanglelabels', from_name: 'box1', options: options(['猫', '狗']) } as SchemaCtl
    const { ctl, s } = mk([rc])
    s.boxes.push({ id: 'b1', label: '狗' })
    applyLabelRename({ 猫: '猫咪' }, ['猫咪', '狗'], 'rectanglelabels', 'box1', s, ctl, emptyForm())
    expect(s.boxes[0].label).toBe('狗')
  })
})

describe('toggleRegionLabel（:2418）/ addRelation（:2820）/ addSegment（:3211）', () => {
  it('勾选追加、取消过滤、勾掉主类别下一个顶上、最后一个不许取消', () => {
    const b: Region = { id: 'b1', label: '猫' }
    expect(toggleRegionLabel(b, '狗', true)).toBe(true)
    expect(b.labels).toEqual(['猫', '狗'])
    expect(b.label).toBe('猫')
    expect(toggleRegionLabel(b, '猫', false)).toBe(true)   // 取消主类别
    expect(b.labels).toEqual(['狗'])
    expect(b.label).toBe('狗')                              // 自动顶上
    expect(toggleRegionLabel(b, '狗', false)).toBe(false)   // 最后一个拒绝
    expect(b.labels).toEqual(['狗'])
  })

  it('关系：空端点/自连报错；成功后 direction=right，regionsOf 顺序=框/区间/多边形/关键点/椭圆/文本', () => {
    const s = emptyState()
    expect(addRelation(s, '', 'b2')).toEqual({ ok: false, msg: '选择两个区域' })
    s.boxes.push({ id: 'b1', label: '猫' })
    s.segments.push({ id: 'e1', label: '人' })
    s.textSpans.push({ id: 't1', label: '句' })
    expect(addRelation(s, 'b1', 'b1')).toEqual({ ok: false, msg: '不能连接同一个区域' })
    expect(addRelation(s, 'b1', 'e1').ok).toBe(true)
    expect(s.relations).toEqual([{ id: s.relations[0].id, from_id: 'b1', to_id: 'e1', direction: 'right' }])
    expect(regionsOf(s).map((r) => r.kind)).toEqual(['框', '区间', '文本'])
  })

  it('区间：先选标签 / 终点>起点；resetFormForTask 预填首选项与首两区域', () => {
    const lc = { type: 'labels', from_name: 'seg1', options: options(['猫', '狗']) } as SchemaCtl
    const ctl = deriveControls({ mode: 'audio', controls: [lc] } as never)
    const s = emptyState()
    const form = emptyForm()
    expect(addSegment(s, form)).toEqual({ ok: false, msg: '先选一个标签' })
    form.segLabel = '猫'; form.segStart = 5; form.segEnd = 5
    expect(addSegment(s, form)).toEqual({ ok: false, msg: '终点必须大于起点' })
    form.segEnd = 6.2
    expect(addSegment(s, form).ok).toBe(true)
    expect(s.segments[0]).toMatchObject({ label: '猫', start: 5, end: 6.2 })
    resetFormForTask(s, ctl, 'audio', 'box', form)
    expect(form.segLabel).toBe('猫')     // 控件首选项
    expect(form.relFrom).toBe(s.segments[0].id)
    expect(form.relTo).toBe('')          // 只有一个区域
  })
})

describe('numberInputToExtra / isoToLocalInput 口径', () => {
  it('\'\'→\'\'（清空），数字串→number，非法串原样（v-model.number:599）', () => {
    expect(numberInputToExtra('')).toBe('')
    expect(numberInputToExtra('3.5')).toBe(3.5)
    expect(numberInputToExtra('abc')).toBe('abc')
  })
  it('datetime 本地串解析（金样 TZ=Asia/Shanghai 对拍面在回放脚本，这里锁格式）', () => {
    expect(isoToLocalInput('2026-10-28T03:04:05.000Z')).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  })
})
