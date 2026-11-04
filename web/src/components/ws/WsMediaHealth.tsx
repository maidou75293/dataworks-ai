/**
 * WsMediaHealth.tsx —— 工作台项目级「图片失效」聚合横幅（BUG-LABEL-MEDIA-404-1 修复层③）。
 *
 * 替代「逐条点 9 次才知道全挂」：队列加载完后，对图片任务的 media_url 去重发 HEAD 探测，
 * dead>0 时出横幅「本项目 N/M 条图片失效」+ 直达「导入任务」。
 * 探测口径（canvas/mediaSrc.ts 同源）：
 *   - 相对 `/media/**` 必须走 /api/mos 通道且只认 `Authorization: Bearer` 头
 *     （AuthInterceptor 无 Cookie 回落）；mos 侧 /media 注册了 GET+HEAD（main.py:169），
 *     Java MosClient **不透传 Range**，所以探测只能用 HEAD，不能 GET+Range 截断；
 *   - 绝对 http（旧坏形态 127.0.0.1）浏览器跨域探不动，判 unknown 不误报成失效；
 *   - 401/403/5xx/网络抖动 = unknown——「无法判定」不进失效数字（军规：守卫要红得起来，
 *     但不能把环境噪声算成用户的坏数据）。
 * 并发 6，逐条增量出结果；队列签名（queueSig）一变即重探。
 */
import { useEffect, useRef, useState } from 'react'
import { Alert, Button } from 'antd'
import { getToken } from '../../api'
import { toChannelUrl } from './canvas/mediaSrc'
import { alertText, imageRefs, pendingUrls, queueSig, tallyHealth } from './mediaHealth'
import type { HealthVerdict } from './mediaHealth'

interface Props {
  pid: number
  filter: string
  tasks: Array<Record<string, any>>
  /** 直达「导入任务」弹窗（MosWorkbench 传 dialogs.setImportOpen(true)） */
  onImport?: () => void
}

const CONCURRENCY = 6
const PROBE_TIMEOUT_MS = 15000

async function probeOne(url: string): Promise<HealthVerdict> {
  const ch = toChannelUrl(url)
  if (!ch.startsWith('/')) return 'unknown' // 绝对/奇异形态：跨域探不动，不误判
  const token = getToken()
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), PROBE_TIMEOUT_MS)
  try {
    const resp = await fetch(ch, {
      method: 'HEAD',
      headers: token ? { Authorization: 'Bearer ' + token } : {},
      signal: ctrl.signal,
    })
    if (resp.ok || resp.status === 206) return 'alive'
    if (resp.status === 404 || resp.status === 410) return 'dead'
    return 'unknown'
  } catch {
    return 'unknown'
  } finally {
    clearTimeout(timer)
  }
}

export function WsMediaHealth({ pid, filter, tasks, onImport }: Props) {
  const [results, setResults] = useState<Record<string, HealthVerdict>>({})
  const tasksRef = useRef(tasks)
  tasksRef.current = tasks
  const genRef = useRef(0)
  const sig = queueSig(pid, filter, tasks)

  useEffect(() => {
    const gen = ++genRef.current
    setResults({})
    const pend = pendingUrls(imageRefs(tasksRef.current), {})
    if (!pend.length) return
    let cursor = 0
    const worker = async () => {
      while (cursor < pend.length) {
        const u = pend[cursor++]
        const v = await probeOne(u)
        if (genRef.current !== gen) return // 队列已切换，本批作废
        setResults((old) => (genRef.current !== gen ? old : { ...old, [u]: v }))
      }
    }
    for (let i = 0; i < Math.min(CONCURRENCY, pend.length); i++) void worker()
  }, [sig])

  const tally = tallyHealth(imageRefs(tasks), results)
  if (!tally.dead) return null
  const ids = tally.deadIds.slice(0, 5).map(String).join('、')
  return (
    <Alert
      type="warning"
      showIcon
      message={alertText(tally)}
      description={
        <>
          这些任务的图片在媒体库里已找不到（多为资产重传后旧地址悬空）。
          {ids ? `失效任务 #${ids}${tally.deadIds.length > 5 ? '…' : ''}；` : '；'}
          新导入的任务不受影响，旧任务可由管理员跑存量修复脚本重挂。
        </>
      }
      action={onImport && (
        <Button size="small" type="primary" onClick={onImport}>导入任务</Button>
      )}
    />
  )
}

export default WsMediaHealth
