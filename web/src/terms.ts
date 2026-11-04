/**
 * terms.ts — 界面术语唯一口径表（REQ-UX-TERMS-1，2026-11-04）。
 *
 * 病根账（审计-20261104-C §三 17 条之术语面）：同一动作四名（校验配置/配置校验/
 * 试运行/立即执行同步）；「上线」与「发布」混用；调度组件真名（Prefect/Deployment）
 * 渗进动作反馈 toast；运维页裸用「水位/背压/Checkpoint/JobManager」不加白话注；
 * MOS 页残留内部黑话（幽灵行/止血单）。旧文档改一处、页面改一处=永远对不齐，
 * 所以词表只允许有这一个出处，红绿锁在 scripts/e2e/ux-terms-snapshot-test.py
 * （卡面判据，逐条比对本表；改口径=先改本表+改锁里的快照，不许只改页面）。
 *
 * 口径三段（对齐 CLAUDE.md「文档口径」纪律）：
 *   1) 平台动作实名：发布 / 下线 / 审批并发布 / 校验配置 / 试运行 / 触发 —— 用户看到的动作词只有这些；
 *   2) 禁用词 = FORBIDDEN_TERMS，scope='all' 连代码注释都不许出现（注释也是术语污染源）；
 *      scope='prose' 只禁用户可见文本（字符串字面量与 JSX 文案），代码标识符与注释不算正文面；
 *   3) 架构/技术选型页（Home 架构卡片、Login 特性条、System 的 K8s 资源类型名）允许写组件真名——
 *      那是产品事实披露，不是动作反馈；逐文件计数快照钉死，往其它页面渗一个新词=锁红。
 */

/** 禁用词条目。word=原样子串；lock 脚本按 scope 扫描整个 web/src（本文件除外）。 */
export type ForbiddenTerm = {
  /** 禁用词/旧叫法（按字面子串匹配，prose 面按整词匹配） */
  word: string
  /** all=任何表面（含注释）零命中；prose=仅用户可见文本零命中（注释与标识符豁免） */
  scope: 'all' | 'prose'
  /** 统一叫法（写清替换去向） */
  replace: string
  /** 为什么禁 */
  reason: string
}

export const FORBIDDEN_TERMS: ForbiddenTerm[] = [
  { word: '上线', scope: 'all',
    replace: '发布；版本状态=已发布/未发布（status.tsx）；调度启停状态 ONLINE=在线',
    reason: '平台动作实名是「发布」，「上线」是口语别名，同一流程两套词=口径漂移源头' },
  { word: '工单', scope: 'all',
    replace: '变更单（SQL 变更页单据实名）',
    reason: '用户侧没有「工单」页面，toast 报「工单 #N」=死链；REQ-UX-FLOWLINK-1 已统一，本卡清残留' },
  { word: '幽灵', scope: 'all',
    replace: '残留（幽灵 Pod→残留 Pod；幽灵行→0 条目的台账行）',
    reason: '自造比喻词，新用户无法从字面理解；运维页术语按 k8s 常规叫法「残留」' },
  { word: '止血', scope: 'all',
    replace: '清理（数据面清理清单由运维侧人工处理）',
    reason: '「PM 止血单」是团队内部流程黑话，不该出现在用户界面与注释' },
  { word: '僵尸', scope: 'all',
    replace: '残留（卡在 Terminating/Failed 的 Pod）',
    reason: '同「幽灵」族：比喻词不当界面术语' },
  { word: 'script_placeholder', scope: 'all',
    replace: '界面写「占位标签」；wire 值只引用本文件 MOS_DATA_ORIGIN_PLACEHOLDER 常量',
    reason: '后端枚举值渗进界面文案=黑话；全 web/src 只允许 terms.ts 这一处字面量' },
  { word: '配置校验', scope: 'all',
    replace: '动作名「校验配置」（按钮）；结果面「校验结果/校验通过/校验未通过」',
    reason: '同一动作四名之一：正序倒序混用（校验配置 vs 配置校验）' },
  { word: '立即执行同步', scope: 'all',
    replace: '试运行',
    reason: '同一动作四名之一：与发布门槛文案「发布前需试运行成功」脱节；「试运行」是验收文档口径' },
  { word: 'Prefect', scope: 'prose',
    replace: '动作反馈与操作文案写「调度/调度服务」；组件真名只许出现在 Home/Login/System（锁内白名单+计数快照）',
    reason: '执行引擎是实现细节，动作反馈里提它不帮用户做决定，只制造认知噪音' },
  { word: 'Deployment', scope: 'prose',
    replace: '同上：写「调度/调度记录」；System 页 K8s 资源类型名除外（白名单）',
    reason: '同 Prefect：「Prefect 上的 Deployment 也会下线」这类文案用户读不懂也不需读懂' },
  { word: 'Flow Run', scope: 'prose',
    replace: '运行实例 / 运行记录',
    reason: '调度引擎的表名直译渗进界面；平台自己的实体名是「运行实例」（Instances 页实名）' },
]

/**
 * 动作词表单点（同义动作收敛后的唯一叫法）。
 * 已接线消费方：Integration.tsx（校验配置/试运行按钮）、status.tsx（版本环境状态标签）。
 * 其余页面用同字样即可，锁按 FORBIDDEN_TERMS 反面钉死旧叫法。
 */
export const ACTION_TERMS = {
  /** 表单静态检查（不发起运行） */
  validate: '校验配置',
  /** 真跑一次但不计入发布门槛以外的副作用；采集任务发布门槛=「试运行成功」 */
  trialRun: '试运行',
  /** 把 DEV 跑通的版本推到目标环境 */
  publish: '发布',
  /** 停掉线上版本，可再发布 */
  offline: '下线',
  /** 审批人一步完成：批准 + 发布 */
  approvePublish: '审批并发布',
  /** 手动起一次运行 */
  trigger: '触发',
} as const

/** 版本在某一环境的状态标签（唯一出处=status.tsx 从此表取词）。 */
export const VERSION_STATUS_TERMS = {
  DRAFT: '未发布',
  PENDING_APPROVAL: '待审批',
  PUBLISHING: '发布中',
  PUBLISHED: '已发布',
  SUPERSEDED: '已替换',
  OFFLINE: '已下线',
} as const

/**
 * MOS data_origin 的 wire 枚举值「占位标签数据集」。后端返回值就是这个名字，
 * 比较与展示映射只准引用本常量（script_placeholder 字面量全仓唯一出处）。
 */
export const MOS_DATA_ORIGIN_PLACEHOLDER = 'script_placeholder'
