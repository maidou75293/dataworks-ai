/** panelStyles.ts —— 面板视觉常量（REQ-UNIFY-6-2；与 WsPanels.tsx/121 同口径）。 */
export const BORDER = '1px solid #d9d9d9'

export const KBD: React.CSSProperties = {
  border: BORDER, borderRadius: 3, padding: '0 4px', fontSize: 10,
  color: '#888', background: '#fafafa', fontFamily: 'ui-monospace, Consolas, monospace',
}

/** .field-label（Vue 面板各行左侧小灰标签） */
export const FIELD_LABEL: React.CSSProperties = { fontSize: 12, color: '#666', marginRight: 4 }

/** .mono */
export const MONO: React.CSSProperties = { fontFamily: 'ui-monospace, Consolas, monospace' }

/** .ell：单行截断 */
export const ELL: React.CSSProperties = {
  maxWidth: 240, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
}

export const MUT: React.CSSProperties = { color: '#888', fontSize: 12 }
