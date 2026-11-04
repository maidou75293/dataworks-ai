/**
 * 媒体库 → 新建标注项目 的样本交接（跨路由，不依赖后端临时状态）。
 * 移植自 mos/platform/frontend/src/annotPick.js:1-31（键名保持一致：
 * 'ft.annot.pick' / 'ft.annot.draft'）——BUG-PARITY-13-C。
 *
 * 用法（对齐老版动线）：
 * - 媒体库勾完样本点「加入新建项目」→ setPendingPick(items) + navigate('/mos/labels?create=1')
 *   （老 Media.vue:1500-1504 sendToWizard）；
 * - 向导打开时 getPendingPick() 预填样本、takeCreateDraft() 恢复离开前的项目名/模板
 *   （老 LabelStudio.vue:1148-1165 openCreateWithPick）；
 * - 创建成功后 clearPendingPick()。
 */
import type { MosMediaItem } from './api/mos'

const PICK_KEY = 'ft.annot.pick'
const DRAFT_KEY = 'ft.annot.draft'

export type CreateDraft = {
  title?: string
  tpl?: string
  label_config?: string
  cfgAdvanced?: boolean
}

export function setPendingPick(items: MosMediaItem[]): void {
  try { sessionStorage.setItem(PICK_KEY, JSON.stringify(items || [])) } catch { /* 隐私模式 */ }
}

export function getPendingPick(): MosMediaItem[] {
  try {
    const raw = sessionStorage.getItem(PICK_KEY)
    const arr = raw ? JSON.parse(raw) : []
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

export function clearPendingPick(): void {
  try { sessionStorage.removeItem(PICK_KEY) } catch { /* ignore */ }
}

export function setCreateDraft(draft: CreateDraft): void {
  try { sessionStorage.setItem(DRAFT_KEY, JSON.stringify(draft || {})) } catch { /* 隐私模式 */ }
}

/** 读出即清（老 takeCreateDraft 语义：一次性恢复，不会下次再冒出来） */
export function takeCreateDraft(): CreateDraft | null {
  try {
    const raw = sessionStorage.getItem(DRAFT_KEY)
    sessionStorage.removeItem(DRAFT_KEY)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}
