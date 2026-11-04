import { Select, type SelectProps } from 'antd'

/**
 * 统一的可搜索下拉。
 *
 * 之前每个页面自己写 Select：有的加 showSearch，有的没加，用户看到的就是
 * 「同样的下拉，有的能输入过滤、有的不能」。所有选项超过 5 个的下拉都用这个组件，
 * 默认按 label 过滤；调用方仍可覆盖任意 Select 属性。
 */
export default function SearchableSelect(props: SelectProps) {
  return <Select showSearch optionFilterProp="label" {...props} />
}
