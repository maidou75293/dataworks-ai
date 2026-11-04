/**
 * mediaHealth.ts —— 队列图片失效聚合的纯逻辑（BUG-LABEL-MEDIA-404-1 修复层③）。
 *
 * 语义（卡③「本项目 N/M 条图片失效」）：
 *   - 口径按「任务条数」计（9 条任务共用一个坏文件也算 9 条失效，与用户逐条点开一致）；
 *   - 探测结果只按 URL 去重缓存（pendingUrls 只回未判定的）；
 *   - **unknown ≠ dead**：网络抖动/401/5xx 一律判「无法判定」，不混进失效数字；
 *   - 本文件不碰 fetch/react，闸3 node 对拍直接 import 跑（scripts/e2e/label-media-404-test.py）。
 */

export type HealthVerdict = 'alive' | 'dead' | 'unknown'

export interface ImageRef {
  id: unknown
  url: string
}

export interface HealthTally {
  dead: number
  alive: number
  unknown: number
  total: number
  deadIds: unknown[]
}

/** 任务队列 -> 图片条目（media_kind==='image' 且 media_url 非空；一行一任务，不去重） */
export function imageRefs(tasks: Array<Record<string, any>> | null | undefined): ImageRef[] {
  const out: ImageRef[] = []
  for (const t of tasks || []) {
    if (!t) continue
    if (String(t.media_kind ?? '') !== 'image') continue
    const url = String(t.media_url ?? '')
    if (!url) continue
    out.push({ id: t.id, url })
  }
  return out
}

function urlOf(row: Record<string, any>): string {
  // 兼容两种入参：任务行（media_url）或 imageRefs 产物（url）
  return String(row?.url ?? row?.media_url ?? '')
}

function idOf(row: Record<string, any>): unknown {
  return row?.id ?? null
}

/** 待探测 URL 清单：去重、剔除已判定的（results 里出现过就不再探） */
export function pendingUrls(
  refs: Array<Record<string, any>>,
  results: Record<string, HealthVerdict>,
): string[] {
  const seen = new Set<string>()
  const out: string[] = []
  for (const r of refs || []) {
    const u = urlOf(r)
    if (!u || results[u] || seen.has(u)) continue
    seen.add(u)
    out.push(u)
  }
  return out
}

/** 聚合：total=图片任务行数；dead/unknown 按各自 URL 的判定计 */
export function tallyHealth(
  rows: Array<Record<string, any>>,
  results: Record<string, HealthVerdict>,
): HealthTally {
  let dead = 0
  let alive = 0
  let unknown = 0
  let total = 0
  const deadIds: unknown[] = []
  for (const row of rows || []) {
    const u = urlOf(row)
    if (!u) continue
    total++
    const v = results[u]
    if (v === 'dead') { dead++; deadIds.push(idOf(row)) } else if (v === 'alive') alive++
    else unknown++ // 含未判完（探测是增量的，横幅只等 dead 出现）
  }
  return { dead, alive, unknown, total, deadIds }
}

/** 横幅文案（闸3 精确断言）：`本项目 N/M 条图片失效`，unknown>0 追加「无法判定」 */
export function alertText(t: HealthTally): string {
  const base = '本项目 ' + t.dead + '/' + t.total + ' 条图片失效'
  return t.unknown > 0 ? base + '，另有 ' + t.unknown + ' 条无法判定' : base
}

/** 队列签名：项目/筛选/成员（id+地址）任一变化即变——换签名才重新探测 */
export function queueSig(pid: unknown, filter: string, tasks: Array<Record<string, any>>): string {
  const rows = tasks || []
  return [String(pid), String(filter), String(rows.length),
    rows.map((t) => String(t?.id ?? '') + '~' + String(t?.media_url ?? '')).join(',')].join('\u001f')
}
