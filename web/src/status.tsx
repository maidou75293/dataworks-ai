import { Tag } from 'antd'
import type { ReactNode } from 'react'
import { VERSION_STATUS_TERMS } from './terms'

/**
 * 运行实例状态统一口径：后端返回英文枚举，界面上必须显示中文。
 * 之前运行实例 / 运维中心 / 数据开发三处各自维护一份，直接渲染 success/failed，
 * 和全站中文标签不一致，用户要先“翻译”一遍才能判断。
 */
export const RUN_STATE_LABEL: Record<string, string> = {
  queued: '排队中',
  running: '运行中',
  success: '成功',
  failed: '失败',
  cancelled: '已取消',
  upstream_failed: '上游失败',
  skipped: '已跳过',
}

export const RUN_STATE_COLOR: Record<string, string> = {
  success: 'green',
  running: 'blue',
  queued: 'gold',
  failed: 'red',
  cancelled: 'default',
  upstream_failed: 'volcano',
  skipped: 'default',
}

export function runStateLabel(state?: string): string {
  if (!state) return '-'
  return RUN_STATE_LABEL[state] || state
}

/** 带颜色的状态标签；没有状态时显示占位横杠，不伪造“未知”。 */
export function RunStateTag({ state }: { state?: string }): ReactNode {
  if (!state) return <Tag>-</Tag>
  return <Tag color={RUN_STATE_COLOR[state] || 'default'}>{runStateLabel(state)}</Tag>
}

/**
 * 版本在某一个环境里的状态（后端 dev_status / prod_status）。
 * 以前版本只有一列 status，跨环境共用：同一版本先发 DEV 再发 PROD 会互相覆盖，
 * 已发布的版本会显示成 DEV 或「待审批」。现在每个环境各有一列，界面分开显示。
 * 取词单点=terms.ts VERSION_STATUS_TERMS（REQ-UX-TERMS-1；快照锁 scripts/e2e/ux-terms-snapshot-test.py）。
 */
export const VERSION_ENV_STATUS_LABEL: Record<string, string> = { ...VERSION_STATUS_TERMS }

const VERSION_ENV_STATUS_COLOR: Record<string, string> = {
  PENDING_APPROVAL: 'gold',
  PUBLISHING: 'blue',
  PUBLISHED: 'green',
  SUPERSEDED: 'default',
  OFFLINE: 'default',
}

/** 版本环境状态标签；null/空 视为「未发布」，不显示后端枚举。 */
export function VersionEnvStatusTag({ status }: { status?: string | null }): ReactNode {
  const key = status && status !== 'DRAFT' ? status : 'DRAFT'
  return (
    <Tag color={VERSION_ENV_STATUS_COLOR[key] || 'default'}>
      {VERSION_ENV_STATUS_LABEL[key] || key}
    </Tag>
  )
}

/** 只有跑完且没成功的实例才给「重跑」入口；运行中/排队中重跑只会多起一个实例。 */
export function canRerun(state?: string): boolean {
  return state === 'failed' || state === 'cancelled' || state === 'upstream_failed'
}

/** 筛选下拉用：值仍是后端枚举，展示中文。 */
export function runStateOptions(states: string[]) {
  return states.map((state) => ({ value: state, label: runStateLabel(state) }))
}

function humanizeDuration(ms: number): string | null {
  if (!Number.isFinite(ms) || ms < 0) return null
  if (ms < 1000) return `${ms} ms`
  const seconds = Math.round(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    const rest = seconds % 60
    return rest ? `${minutes}m ${rest}s` : `${minutes}m`
  }
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`
}

/**
 * 运行耗时：列表里最该看的是「跑了多久」，而不是两个时间戳自己去减。
 * 运行中的实例还没有 ended_at，按当前时间算并标「已运行」——否则值班只看到一个横杠，
 * 分不清是刚跑 10 秒还是已经卡了 3 小时。
 */
export function formatDuration(start?: string, end?: string, state?: string): string {
  if (!start) return '-'
  if (!end) {
    if (state !== 'running') return '-'
    const running = humanizeDuration(Date.now() - new Date(start).getTime())
    return running ? `已运行 ${running}` : '-'
  }
  return humanizeDuration(new Date(end).getTime() - new Date(start).getTime()) || '-'
}
