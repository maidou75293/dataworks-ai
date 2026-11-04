import { memo } from 'react'

/**
 * 表格行 memo 包装（v452）。
 *
 * <p>rc-table 默认每行都是内联箭头函数组件，父组件任何 state 变化都会把整表所有行
 * 重新渲染一遍；把行组件提到模块级并用 React.memo 包住，行 props 不变时直接复用。</p>
 */
const MemoTableRow = memo(function MemoTableRow(props: Record<string, unknown>) {
  return <tr {...props} />
})

export default MemoTableRow
