/**
 * mediaSrc.ts —— 画布媒体取址（REQ-UNIFY-6-3）。
 *
 * 事实（explore-m6-REQ-UNIFY-6-3.md）：
 *   - mos 侧 media_url 已被后端统一成平台 /media/ 同源地址
 *     （mos/platform/backend/adapters/label_studio.py:877 platform_media_url）；
 *   - 中台通道 /api/mos/** 只认 Authorization: Bearer 头
 *     （AuthInterceptor.bearer:57，无 Cookie 回落），<img/<video/<audio src> 带不了头
 *     → 必须前端 fetch（带 token）转 blob objectURL 喂给元素；
 *   - MosProxyController:46-71 对 /api/mos/<tail> 原样转发，/media/ 与 /api/ 都通
 *     （mosChannelUrl 只剥 /api 前缀，/media/ 直拼）。
 * 外部 http(s)/data:/blob: 地址直通（CDN 公开可拉；timeseries 的绝对 URL 同口径，
 * Vue loadTimeseries:2766 也是直接 fetch）。
 */
import { getToken } from '../../../api'
import { mosChannelUrl } from '../../../api/mos'

/** 纯函数：mos 相对/绝对地址 → 浏览器可请求的通道地址 */
export function toChannelUrl(raw: unknown): string {
  const s = String(raw ?? '')
  if (!s) return ''
  if (/^(https?:|data:|blob:)/i.test(s)) return s
  if (s.startsWith('/')) return mosChannelUrl(s)
  return s
}

export interface MediaResolver {
  /** media_url → 元素可用 src（通道地址走 Bearer fetch + objectURL 缓存） */
  resolve(raw: unknown): Promise<string>
  fetchText(raw: unknown): Promise<string>
  releaseAll(): void
}

/**
 * BUG-PARITY-6：objectURL 缓存从「一个解析器一份」改成「整页一份 + LRU 上限」。
 * 事实：右栏包在 `{model.selected && model.detail && …}` 里，pick 一开始就把 detail 置 null
 * （wsLifecycle:517），切一条任务=画布整棵子树卸载重挂，`useMemo(…, [])` 的私有 Map 连
 * resolver 一起被丢掉。预取回来的东西在下一次切换时归零，「±1 预取」等于白取
 * （parity6 T12 首轮实测：按 J 时 one/two/three 又各发一次 GET）。
 * 缓存归页面共享后 J/K 才真命中预取；上限 CACHE_MAX 张，超了丢最旧并 revoke，不无界增长。
 */
const CACHE_MAX = 48
const urlCache = new Map<string, string>()
const pending = new Map<string, Promise<string>>()

function cachePut(url: string, obj: string): void {
  urlCache.set(url, obj)
  while (urlCache.size > CACHE_MAX) {
    const oldest = urlCache.keys().next().value as string | undefined
    if (oldest === undefined) break
    const stale = urlCache.get(oldest)
    urlCache.delete(oldest)
    if (stale) { try { URL.revokeObjectURL(stale) } catch { /* ignore */ } }
  }
}

export function makeDomResolver(): MediaResolver {
  const canBlob = typeof fetch !== 'undefined'
    && typeof URL !== 'undefined' && typeof URL.createObjectURL === 'function'
  function authHeaders(u: string): Headers {
    const headers = new Headers()
    const t = getToken()
    if (t && !/^(https?:|data:)/i.test(u)) headers.set('Authorization', 'Bearer ' + t)
    return headers
  }
  async function resolve(raw: unknown): Promise<string> {
    const url = toChannelUrl(raw)
    // 非通道地址或无 fetch 环境（SSR/单测）：原样返回，元素加载失败自有 onMediaError 口径
    if (!url.startsWith('/') || !canBlob) return url
    const hit = urlCache.get(url)
    if (hit) return hit
    // 同一张图的并发请求合流（预取与主路径可能同帧发起）：一次下载，两个调用方共享同一个 promise
    const inflight = pending.get(url)
    if (inflight) return inflight
    const p = (async () => {
      const resp = await fetch(url, { headers: authHeaders(url) })
      if (!resp.ok) throw new Error('HTTP ' + resp.status)
      const blob = await resp.blob()
      const obj = URL.createObjectURL(blob)
      cachePut(url, obj)
      return obj
    })().finally(() => { pending.delete(url) })
    pending.set(url, p)
    return p
  }
  async function fetchText(raw: unknown): Promise<string> {
    const url = toChannelUrl(raw)
    const resp = await fetch(url, { headers: authHeaders(url) })
    if (!resp.ok) throw new Error('HTTP ' + resp.status)
    return await resp.text()
  }
  /** 整页媒体缓存清空（缓存现在是页面级共享，别在组件卸载时调——见 WsMediaStage 尾注） */
  function releaseAll(): void {
    for (const v of urlCache.values()) { try { URL.revokeObjectURL(v) } catch { /* ignore */ } }
    urlCache.clear()
    pending.clear()
  }
  return { resolve, fetchText, releaseAll }
}
