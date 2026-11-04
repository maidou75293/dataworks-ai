/**
 * 帮助入口中央登记表（REQ-DW-19，单一事实源）。
 *
 * 作用：
 * 1. 全局页头的 `?` 图标据此推导「当前页面 → 帮助页锚点」，做到一处实现、各页直达，
 *    不在每个页面复制按钮，也不逐页传 prop；
 * 2. 帮助页据此渲染锚点目录；REQ-UX-HELP-1 起全部页面都有对应章节，
 *    `pendingHelpPages()` 仅作回归守卫（非空 = 又出现了没去处的 ? 按钮）。
 *
 * 章节 key 必须与 pages/Help.tsx 里 Collapse 的 items[].key 一致。
 */

export type HelpSection = { key: string; title: string }

/**
 * 帮助页章节（key = Collapse item key，title = 锚点目录标题）。
 * REQ-UX-HELP-1：概念小词典 + 13 个治理页专属章节补齐，每页 ? 都有直达去处。
 */
export const HELP_SECTIONS: HelpSection[] = [
  { key: 'glossary', title: '概念小词典（租户 / 权限点 / 项目 / 工作流 / 环境与发布）' },
  { key: 'start', title: '首次使用：从空环境到第一条查询' },
  { key: 'query', title: '查询页怎么用' },
  { key: 'query-data', title: '怎么查数据（去哪查 / 能查什么 / 权限）' },
  { key: 'tables', title: '表管理怎么用' },
  { key: 'errors', title: '常见错误对照表' },
  { key: 'engines', title: '引擎怎么选（Doris / Flink / Spark）' },
  // 以下 13 章为治理页专属章节（REQ-UX-HELP-1，审计 §14：原先这些页的 ? 无处可去）
  { key: 'quality', title: '数据质量：规则、强度与闸门' },
  { key: 'service', title: '数据服务：把 SQL 发布成 API' },
  { key: 'bi', title: '数据可视化：看板与发布快照' },
  { key: 'functions', title: '函数管理：自定义函数注册与使用' },
  { key: 'security', title: '安全中心：审计、会话与登录' },
  { key: 'changes', title: 'SQL 变更：审批与执行' },
  { key: 'ops', title: '运维总览：一天看完运行情况' },
  { key: 'instances', title: '运行实例：状态、日志与重跑' },
  { key: 'alerts', title: '告警中心：确认、关闭与投递' },
  { key: 'baselines', title: '任务基线：SLA 与预警' },
  { key: 'monitor', title: '系统监控：服务与引擎健康' },
  { key: 'gravitino', title: '元数据巡检：一致性与修复' },
  { key: 'admin', title: '管理中心：项目、部门、用户与角色' },
]

/** 章节 key → 标题，供帮助页校验/展示。 */
export const HELP_SECTION_TITLE: Record<string, string> = Object.fromEntries(
  HELP_SECTIONS.map((s) => [s.key, s.title]),
)

export type PageHelp = {
  /** 路由前缀（最长前缀匹配，子路由跟随父页，如 /dev/:id → /dev）。 */
  path: string
  /** 页面中文名，仅用于「缺章节清单」展示。 */
  label: string
  /**
   * 该页右上角 `?` 直达的帮助章节锚点；留空表示暂无对应章节，
   * `?` 回落到帮助首页，且该页会被列入待补齐清单。
   */
  anchor?: string
}

/**
 * 各页面右上角 `?` 图标直达的锚点。
 * 每页必须有 anchor（验收脚本逐页断言）；同一章节允许多页共用时须注释写明理由。
 * 现状：查询/表/资产/申请/开发/流/集成/数据源 8 页落在既有主题章节
 * （「怎么查数据」「引擎怎么选」按主题而非按页组织，REQ-DW-19 设计，审计未点名）；
 * 其余页面（含 13 个治理页）均为一页一章。
 */
export const PAGE_HELP: PageHelp[] = [
  { path: '/home', label: '工作台', anchor: 'start' },
  { path: '/query', label: '数据查询', anchor: 'query' },
  { path: '/tables', label: '表管理', anchor: 'tables' },
  // 元数据目录/血缘/多模态资产/权限申请：帮助里「怎么查数据」章节确实讲了从这些入口查数与申请读权限
  { path: '/map', label: '元数据目录', anchor: 'query-data' },
  { path: '/lineage', label: '数据血缘', anchor: 'query-data' },
  { path: '/assets', label: '多模态资产', anchor: 'query-data' },
  { path: '/access', label: '权限申请', anchor: 'query-data' },
  // 开发/集成/流开发/数据源：落到「引擎怎么选」章节（Doris / Flink / Spark 的入口与取舍）
  { path: '/dev', label: '数据开发', anchor: 'engines' },
  { path: '/stream', label: '流开发', anchor: 'engines' },
  { path: '/integration', label: '数据集成', anchor: 'engines' },
  { path: '/datasources', label: '数据源', anchor: 'engines' },
  // 治理页专属章节（REQ-UX-HELP-1）：一页一章，`?` 不再回落帮助首页
  { path: '/quality', label: '数据质量', anchor: 'quality' },
  { path: '/service', label: '数据服务', anchor: 'service' },
  { path: '/bi', label: '数据可视化', anchor: 'bi' },
  { path: '/functions', label: '函数管理', anchor: 'functions' },
  { path: '/security', label: '安全中心', anchor: 'security' },
  { path: '/changes', label: 'SQL 变更', anchor: 'changes' },
  { path: '/ops', label: '运维总览', anchor: 'ops' },
  { path: '/ops/instances', label: '运行实例', anchor: 'instances' },
  { path: '/ops/alerts', label: '告警中心', anchor: 'alerts' },
  { path: '/ops/baselines', label: '任务基线', anchor: 'baselines' },
  { path: '/ops/system', label: '系统监控', anchor: 'monitor' },
  { path: '/ops/gravitino', label: '元数据巡检', anchor: 'gravitino' },
  { path: '/admin', label: '管理中心', anchor: 'admin' },
  // 管理中心同组件的其他入口，跟随 /admin 章节
  { path: '/departments', label: '部门管理', anchor: 'admin' },
  { path: '/users', label: '用户与权限', anchor: 'admin' },
  { path: '/roles', label: '角色与权限点', anchor: 'admin' },
]

/**
 * 按当前路径找帮助锚点（最长前缀匹配）。
 * 未登记或已登记但无 anchor 的页面返回 undefined，调用方据此回落到 /help 首页。
 */
export function helpAnchorForPath(pathname: string): string | undefined {
  let best: PageHelp | undefined
  for (const p of PAGE_HELP) {
    const hit = pathname === p.path || pathname.startsWith(`${p.path}/`)
    if (!hit) continue
    if (!best || p.path.length > best.path.length) best = p
  }
  return best?.anchor
}

/** 缺章节页面清单（已登记但尚无对应帮助正文的页面）。 */
export function pendingHelpPages(): PageHelp[] {
  return PAGE_HELP.filter((p) => !p.anchor)
}
