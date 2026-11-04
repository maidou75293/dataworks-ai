/**
 * taxonomy 展平测试（卡面验收 ②）：flattenTaxonomy:2631 纯函数 + Select 组件链路
 * （选路径 → s.extra=数组 → buildResult:306-312 taxonomy [[path]]）。
 */
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { buildResult, type SchemaCtl } from '../../../../pages/mos/wsSchema'
import { flattenTaxonomy, parseTaxPath, taxOptionsOf, taxValueKey } from '../panelOps'
import { Host, makeModel } from './host'

const TREE = [
  { value: '动物', children: [{ value: '猫' }, { value: '狗' }] },
  { value: '植物', children: [{ value: '树', children: [{ value: '松树' }] }] },
]

describe('flattenTaxonomy（纯）', () => {
  it('展平到叶子，label 为「a / b / c」，深路径含全部祖先', () => {
    expect(flattenTaxonomy(TREE)).toEqual([
      { path: ['动物', '猫'], label: '动物 / 猫' },
      { path: ['动物', '狗'], label: '动物 / 狗' },
      { path: ['植物', '树', '松树'], label: '植物 / 树 / 松树' },
    ])
  })
  it('无 children 的节点自身即叶子；空树=[]', () => {
    expect(flattenTaxonomy([{ value: '独层' }])).toEqual([{ path: ['独层'], label: '独层' }])
    expect(flattenTaxonomy([])).toEqual([])
  })
  it('taxValueKey/parseTaxPath 往返（onTaxChange:2827 / taxValueKey:2832）', () => {
    const c = { from_name: 't' } as SchemaCtl
    expect(taxValueKey(c, {})).toBe('[]')
    const key = taxValueKey(c, { t: ['动物', '猫'] })
    expect(parseTaxPath(key)).toEqual(['动物', '猫'])
    expect(taxValueKey(c, { t: '脏数据' })).toBe('[]')
  })
})

describe('taxonomy 控件组件', () => {
  const ctl: SchemaCtl = {
    type: 'taxonomy', from_name: 'tax1', to_name: 'src', tree: TREE as never,
  }

  it('下拉选项=展平叶子 +（未选择）；选中后 buildResult 出 taxonomy 项', async () => {
    const model = makeModel({ mode: 'text', controls: [ctl] })
    render(<Host model={model} />)
    const box = screen.getByTestId('taxonomy-tax1')
    expect(box.querySelector('.ant-select-selection-item')).toHaveTextContent('（未选择）')
    await userEvent.click(box.querySelector('.ant-select-selector') as Element)
    await userEvent.click(await screen.findByText('植物 / 树 / 松树'))
    expect(model.s.extra.tax1).toEqual(['植物', '树', '松树'])
    expect(box.querySelector('.ant-select-selection-item')).toHaveTextContent('植物 / 树 / 松树')
    expect(buildResult(model.s, model.ctl)).toEqual([
      { from_name: 'tax1', to_name: 'src', type: 'taxonomy',
        value: { taxonomy: [['植物', '树', '松树']] } },
    ])
    // 未选择 → result 里不出现（taxValueKey 的 '[]' 视为空）
    await userEvent.click(box.querySelector('.ant-select-selector') as Element)
    await userEvent.click(await screen.findByText('（未选择）'))
    expect(buildResult(model.s, model.ctl)).toEqual([])
  })

  it('taxOptionsOf 读控件 tree 槽（:1947）', () => {
    expect(taxOptionsOf(ctl).map((t) => t.label)).toEqual(['动物 / 猫', '动物 / 狗', '植物 / 树 / 松树'])
    expect(taxOptionsOf(undefined)).toEqual([])
  })
})
