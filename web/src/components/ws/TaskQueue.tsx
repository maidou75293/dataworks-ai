/**
 * TaskQueue.tsx —— 队列侧栏（REQ-UNIFY-6-1）。
 * 模板对照 LabelStudioWorkbench.vue:134-190（只读）：搜索、三页签计数、只看我的、
 * 列表项（#id / 已标注·待标注 / 我的·他人 / 草稿角标 / 问题 / 媒体名 / 答案）、底部计数与加载更多。
 *
 * BUG-PARITY-6 补两件事（都在原件里，移植时掉了）：
 * ① 收起/抽屉形态（Vue CSS 4975-4977 桌面 queue-closed、5532 专注让位、
 *    5584-5604 窄屏绝对定位抽屉 + transform）——原来本组件固定 300px 常驻，
 *    model.queueOpen 没人读、也没有 queueClosed，顶栏那颗钮无处可收；
 * ② 空队列直达「导入任务」（Vue :179-180）——原来 total=0 只有一句文案，
 *    导入入口埋在「更多 ▾」二级，新用户建完项目进来看到的是死路。
 */
import { useRef, useState } from 'react'
import { Button, Checkbox, Input, Tag } from 'antd'
import type { WorkbenchModel } from '../../pages/mos/wsLifecycle'

/** baseName（Vue:2246）：媒体文件名展示 */
export function baseName(u: unknown): string {
  const s = String(u || '').split('?')[0]
  try { return decodeURIComponent(s.split('/').pop() || '') } catch { return s.split('/').pop() || '' }
}

const BORDER = '1px solid #d9d9d9'

export function TaskQueue({ model, onImport }: {
  model: WorkbenchModel
  /** BUG-PARITY-6：空队列直达导入（Vue :179-180 @click="importOpen = true"）。
   *  壳层传 dialogs.setImportOpen(true)——导入弹窗本体在 dialogs/WsDialogs.tsx，
   *  本组件只管把它请出来，不自己发请求。 */
  onImport?: () => void
}) {
  // Vue 用 v-model 实时写 q、280ms 防抖后拉列表（onSearch:4245）；这里同口径
  const [kw, setKw] = useState(model.q)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onInput = (v: string) => {
    setKw(v)
    model.q = v
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => { void model.searchNow() }, 280)
  }
  const t = model.tabCounts
  const filters: [string, string, number][] = [
    ['unannotated', '待标注', t.unannotated],
    ['annotated', '已标注', t.annotated],
    ['all', '全部', t.total],
  ]
  const emptyText = model.loading ? '加载中…'
    : model.q ? '没有匹配任务'
      : model.project && model.stats.total === 0 ? '项目还没有任务'
        : model.project && model.stats.unannotated === 0 ? '全部标注完成'
          : '这个筛选下没有任务'

  // BUG-PARITY-6：形态判定（Vue CSS 桌面 :4975-4977、专注/最大化让位 :5532、窄屏抽屉 :5584-5604）。
  // 桌面「收起」或专注让位 → display:none 但**保持挂载**：原件用 display:none 而不是 v-if，
  // 为的是不丢搜索关键词与列表滚动位置——本组件的 kw 同样是本地态，卸载一次就没了。
  const drawer = model.isNarrow
  const hidden = !drawer && (model.queueClosed || model.focusMode || model.maximized)
  return (
    <aside data-ws="queue" style={drawer ? {
      position: 'absolute', top: 0, bottom: 0, left: 0, zIndex: 46,
      width: 'min(320px, 86vw)', flexShrink: 0, borderRight: BORDER,
      boxShadow: '8px 0 24px rgba(15, 23, 42, .16)',
      transform: model.queueOpen ? 'none' : 'translateX(-105%)',
      transition: 'transform .18s ease',
      display: 'flex', flexDirection: 'column', background: '#fff', minHeight: 0,
    } : {
      width: 300, flexShrink: 0, borderRight: BORDER,
      display: hidden ? 'none' : 'flex',
      flexDirection: 'column', background: '#fff', minHeight: 0,
    }}>
      <div style={{ padding: 10, borderBottom: BORDER }}>
        <Input.Search
          placeholder="搜索问题 / 答案…"
          allowClear
          value={kw}
          onChange={(e) => onInput(e.target.value)}
        />
        <div style={{ display: 'flex', gap: 4, marginTop: 8 }}>
          {filters.map(([key, label, n]) => (
            <Button key={key} size="small" type={model.filter === key ? 'primary' : 'default'}
                    onClick={() => { void model.setFilter(key) }}>
              {label} <span style={{ opacity: 0.85 }}>{n}</span>
            </Button>
          ))}
        </div>
        <Checkbox
          checked={model.onlyMine}
          onChange={(e) => { void model.toggleOnlyMine(e.target.checked) }}
          title="待标注 = 我还没标过的（别人标过也算我的活）；已标注 = 我标过的"
          style={{ marginTop: 8, fontSize: 12 }}
        >
          只看我的
        </Checkbox>
      </div>
      <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
        {model.tasks.map((t2) => {
          const active = model.selected && model.selected.id === t2.id
          return (
            <button key={t2.id} onClick={() => { void model.pick(t2) }}
                    style={{
                      display: 'block', width: '100%', textAlign: 'left', padding: '8px 10px',
                      borderBottom: BORDER, cursor: 'pointer', background: active ? '#e6f4ff' : '#fff',
                      borderLeft: active ? '3px solid #1677ff' : '3px solid transparent',
                      borderTop: 'none', borderRight: 'none',
                    }}>
              <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                <span style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 12 }}>#{t2.id}</span>
                <Tag color={t2.annotated ? 'green' : 'default'} style={{ marginInlineEnd: 0 }}>
                  {t2.annotated ? '已标注' : '待标注'}
                </Tag>
                {t2.mine ? (
                  <Tag color="blue" style={{ marginInlineEnd: 0 }} title="我标注过这条">我的</Tag>
                ) : t2.annotated ? (
                  <Tag style={{ marginInlineEnd: 0 }}
                       title={`已有 ${(t2.annotators || 1)} 份标注，都不是我的`}>他人</Tag>
                ) : null}
                {model.draftIds[String(t2.id)] ? (
                  <Tag color="orange" style={{ marginInlineEnd: 0 }} title="有未保存的草稿">草稿</Tag>
                ) : null}
              </div>
              <div style={{ fontSize: 12.5, marginTop: 4, lineHeight: '18px' }}>
                {t2.question || (t2.media_kind ? '（媒体任务）' : '（无文本字段）')}
              </div>
              {t2.media_kind ? (
                <div style={{ fontSize: 11, color: '#888', fontFamily: 'ui-monospace, Consolas, monospace' }}>
                  {baseName(t2.media_url)}
                </div>
              ) : null}
              {t2.answer ? (
                <div style={{ fontSize: 12, color: '#666', marginTop: 2 }}>{t2.answer}</div>
              ) : null}
            </button>
          )
        })}
        {!model.tasks.length && (
          <div data-ws="queue-empty" style={{ padding: 24, textAlign: 'center', color: '#888', fontSize: 12 }}>
            {emptyText}
            {/* BUG-PARITY-6：Vue :179-180 逐字条件 !loading && project && stats.total===0——
                项目一条任务都没有时才给直达钮（搜索没匹配/全部标完不给，那是另一回事）。
                原症=这里只有一句「项目还没有任务」，导入埋在「更多 ▾」二级，
                新建项目进来的人看到的是死路：文案说没有任务，页面上没有任何按钮能变出任务。 */}
            {!model.loading && model.project && model.stats.total === 0 && onImport && (
              <div style={{ marginTop: 10 }}>
                <Button size="small" onClick={onImport}>导入任务</Button>
              </div>
            )}
          </div>
        )}
      </div>
      <div style={{
        padding: '6px 10px', borderTop: BORDER, display: 'flex', alignItems: 'center',
        fontSize: 12, color: '#666',
      }}>
        <span>显示 {model.tasks.length} / {model.matched} 条</span>
        <span style={{ flex: 1 }} />
        {model.tasks.length < model.matched && (
          <Button size="small" disabled={model.loading} onClick={() => model.loadMoreTasks()}>
            加载更多
          </Button>
        )}
      </div>
    </aside>
  )
}
