import { forwardRef, useEffect, useRef, useState } from 'react'
import type { ChangeEvent, ComponentProps, CompositionEvent } from 'react'
import { Input } from 'antd'
import type { InputProps, InputRef } from 'antd'

/**
 * 中文输入法友好的输入框。
 *
 * <p>问题：搜索框都是受控写法 `value={keyword}`，中文输入法每个拼音按键都会触发
 * 父组件 setState → 整页重渲染，候选框跟着卡。英文没有组合期，所以只有中文卡。</p>
 *
 * <p>做法：组件内部维护 local value（拼音阶段只改 local），`compositionstart` 到
 * `compositionend` 之间不调用外部 onChange；组合结束（或非中文直接输入/粘贴/清空）
 * 才把最终值抛给父组件。外部 value 变化（清空、切换筛选、深链回填）用 useEffect 对齐。</p>
 */

export interface ImeValueProps {
  value?: string
  onChange?: (value: string) => void
}

type CompositionHandlers = {
  onCompositionStart: () => void
  onCompositionEnd: (e: CompositionEvent<HTMLInputElement>) => void
}

/**
 * 把「外部受控值」变成「输入法友好的受控值」。
 * 自定义输入框（textarea / 自绘组件）可以直接复用这组 props。
 */
export function useImeValue(
  external: string | undefined,
  onChange?: (value: string) => void,
): { value: string; onChange: (e: ChangeEvent<HTMLInputElement>) => void }
  & CompositionHandlers & { flush: () => void } {
  const [local, setLocal] = useState(external ?? '')
  const composing = useRef(false)
  const lastEmitted = useRef(external ?? '')
  const timer = useRef<number | undefined>(undefined)

  // 外部值变化：点清空、切换筛选、深链回填都要同步回输入框
  useEffect(() => {
    const next = external ?? ''
    lastEmitted.current = next
    setLocal(next)
  }, [external])

  useEffect(() => () => {
    if (timer.current) window.clearTimeout(timer.current)
  }, [])

  const emit = (next: string) => {
    if (next === lastEmitted.current) return
    lastEmitted.current = next
    onChange?.(next)
  }

  /**
   * 中文连续上屏（每个字一次 compositionend）时把外部更新合并到 200ms 一次：
   * 逐字上屏 10 个字否则会让父组件重渲染 10 次——验收要求 ≤2。非中文输入、
   * 粘贴、清空仍然立即抛给外部（行为与原来完全一致）。
   */
  const emitCoalesced = (next: string) => {
    if (next === '') {
      if (timer.current) window.clearTimeout(timer.current)
      emit(next)
      return
    }
    if (timer.current) window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => emit(next), 200)
  }

  const flush = () => {
    if (timer.current) {
      window.clearTimeout(timer.current)
      timer.current = undefined
    }
  }

  return {
    value: local,
    flush,
    onChange: (e) => {
      const next = e.target.value
      setLocal(next)
      // 组合期（拼音还没上屏）只改本地值，父组件不重渲染
      if (!composing.current) emit(next)
    },
    onCompositionStart: () => {
      composing.current = true
    },
    onCompositionEnd: (e) => {
      composing.current = false
      const next = e.currentTarget.value
      setLocal(next)
      // 组合结束时 React 可能还会补发一次 onChange，emit 内做去重
      emitCoalesced(next)
    },
  }
}

export type ImeInputProps = Omit<InputProps, 'value' | 'onChange'> & ImeValueProps

/** 默认导出：搜索/筛选输入框的统一入口。 */
const ImeInput = forwardRef<InputRef, ImeInputProps>(function ImeInput(
  { value, onChange, ...rest }, ref,
) {
  const { flush, ...bind } = useImeValue(value, onChange)
  return (
    <Input
      ref={ref}
      {...rest}
      {...bind}
      onBlur={(e) => {
        flush()
        rest.onBlur?.(e)
      }}
    />
  )
})

export type ImeSearchProps = Omit<ComponentProps<typeof Input.Search>,
  'value' | 'onChange'> & ImeValueProps

/** Input.Search 版本：保留回车/放大镜触发 onSearch 的行为。 */
export const ImeSearch = forwardRef<any, ImeSearchProps>(function ImeSearch(
  { value, onChange, ...rest }, ref,
) {
  const { flush, ...bind } = useImeValue(value, onChange)
  return (
    <Input.Search
      ref={ref}
      {...rest}
      {...bind}
      onBlur={(e) => {
        flush()
        ;(rest.onBlur as ((event: React.FocusEvent<HTMLInputElement>) => void) | undefined)?.(e)
      }}
    />
  )
})

/** 300ms 防抖值：keyword 触发接口请求时用，避免每个按键都打后端。 */
export function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])
  return debounced
}

export default ImeInput
