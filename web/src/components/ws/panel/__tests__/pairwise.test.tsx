/**
 * pairwise（卡面称 ranker，模板 :620-628）组件测试：三键互斥选择 →
 * s.extra=side → buildResult:313-318 `value:{selected:[side]}`（none 也算选了）。
 */
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { buildResult } from '../../../../pages/mos/wsSchema'
import { PAIRWISE_SIDES } from '../panelOps'
import { Host, makeModel } from './host'

const schema = {
  mode: 'text',
  controls: [{ type: 'pairwise', from_name: 'cmp', to_names: ['模型A', '模型B'] }],
}

describe('pairwise / ranker', () => {
  it('标题用 to_names 拼「A vs B」；三个按钮=左右平', async () => {
    const model = makeModel(schema)
    render(<Host model={model} />)
    expect(screen.getByText('模型A vs 模型B')).toBeInTheDocument()
    const grp = screen.getByTestId('pairwise-cmp')
    expect(grp).toHaveTextContent('左侧更好')
    expect(grp).toHaveTextContent('右侧更好')
    expect(grp).toHaveTextContent(/持\s?平/)
    expect(PAIRWISE_SIDES).toEqual(['left', 'right', 'none'])
  })

  it('点选写 side，buildResult 输出 selected:[side]；改选互斥', async () => {
    const model = makeModel(schema)
    render(<Host model={model} />)
    await userEvent.click(screen.getByText('左侧更好'))
    expect(model.s.extra.cmp).toBe('left')
    expect(buildResult(model.s, model.ctl)).toEqual([
      { from_name: 'cmp', to_name: undefined, type: 'pairwise', value: { selected: ['left'] } },
    ])
    // antd 双字按钮会插空格（「持 平」），用正则匹配
    await userEvent.click(screen.getByText(/^持\s?平$/))
    expect(model.s.extra.cmp).toBe('none')
    expect(buildResult(model.s, model.ctl)[0].value).toEqual({ selected: ['none'] })
    // 未选时不出项
    const fresh = makeModel(schema)
    expect(buildResult(fresh.s, fresh.ctl)).toEqual([])
  })

  it('无 to_names 时标题回退 from_name（Vue :621 同式）', () => {
    const model = makeModel({
      mode: 'text', controls: [{ type: 'pairwise', from_name: 'cmp2' }],
    })
    render(<Host model={model} />)
    expect(screen.getByTestId('pairwise-cmp2')).toHaveTextContent('cmp2')
  })
})
