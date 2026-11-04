/**
 * choice 多选/单选组件测试（卡面验收 ②）：面板勾选 → s.choiceVals → buildResult
 * 的 choices 项逐字段对拍（模板 :570-588；buildResult:276-282）。
 */
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { buildResult } from '../../../../pages/mos/wsSchema'
import { Host, makeModel } from './host'

const schema = {
  mode: 'image',
  controls: [
    { type: 'choices', from_name: 'cat', to_name: 'img', multiple: true,
      options: [{ value: '猫' }, { value: '狗' }, { value: '鸟' }] },
  ],
}

describe('choices 多选', () => {
  it('勾选两个 → choiceVals 数组 → buildResult 一条 choices 项', async () => {
    const model = makeModel(schema)
    render(<Host model={model} />)
    const cbs = await screen.findAllByRole('checkbox')
    expect(cbs).toHaveLength(3)
    await userEvent.click(cbs[0])
    expect(model.s.choiceVals.cat).toEqual(['猫'])
    await userEvent.click(cbs[1])
    expect(model.s.choiceVals.cat).toEqual(['猫', '狗'])
    // 再点一次取消
    await userEvent.click(cbs[0])
    expect(model.s.choiceVals.cat).toEqual(['狗'])
    expect(buildResult(model.s, model.ctl)).toEqual([
      { from_name: 'cat', to_name: 'img', type: 'choices', value: { choices: ['狗'] } },
    ])
  })

  it('单选组渲染 radio，点选写字符串', async () => {
    const model = makeModel({
      mode: 'image',
      controls: [{ type: 'choices', from_name: 'q', to_name: 'img',
                   options: [{ value: '好' }, { value: '差' }] }],
    })
    render(<Host model={model} />)
    const radios = await screen.findAllByRole('radio')
    await userEvent.click(radios[1])
    expect(model.s.choiceVals.q).toBe('差')
    expect(buildResult(model.s, model.ctl)).toEqual([
      { from_name: 'q', to_name: 'img', type: 'choices', value: { choices: ['差'] } },
    ])
  })

  it('多组分类时标题用 from_name，单组显示「整图类别」', () => {
    const single = makeModel(schema)
    const { unmount } = render(<Host model={single} />)
    expect(screen.getByText('整图类别')).toBeInTheDocument()
    unmount()
    const two = makeModel({
      mode: 'image',
      controls: [
        { type: 'choices', from_name: '甲组', to_name: 'img', options: [{ value: 'a' }] },
        { type: 'choices', from_name: '乙组', to_name: 'img', multiple: true, options: [{ value: 'b' }] },
      ],
    })
    render(<Host model={two} />)
    expect(screen.getByText('甲组')).toBeInTheDocument()
    expect(screen.getByText('乙组')).toBeInTheDocument()
  })
})
