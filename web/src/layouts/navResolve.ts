/**
 * 侧栏高亮/面包屑归属的纯逻辑（UX-NAV-6 常驻锁对拍件：
 * scripts/e2e/ux-nav-breadcrumb-test.py）。
 *
 * 背景（体验审计 P3 / BUG-LABEL-NAV-1）：菜单高亮是「最长前缀」匹配，深链路径
 * 天然对不上菜单 key——"/mos/label-studio/4".startsWith("/mos/labels") 为 false，
 * 不显式映射就掉进 /home 兜底：侧栏高亮「系统介绍」、面包屑「data-ai-agent / 概览」，
 * 用户在最深最常用的页面失去坐标。
 *
 * 规矩照 /lineage→/map 先例：新增深链页必须在这里登记所属菜单 key，
 * 常驻锁会按 NAV 真实 key 表对拍（映射目标不是真菜单 key 同样锁红）。
 */
export function navPathKey(pathname: string): string {
  return pathname.startsWith('/lineage') ? '/map'
    : pathname.startsWith('/mos/label-studio') ? '/mos/labels'
      : ['/departments', '/users'].includes(pathname) ? '/admin'
        : pathname
}

/** 在可见菜单 key 里做最长前缀高亮；无匹配回落 /home（系统介绍）。 */
export function menuSelected(pathname: string, menuKeys: string[]): string {
  const pathKey = navPathKey(pathname)
  return menuKeys
    .filter((key) => pathKey.startsWith(key))
    .sort((a, b) => b.length - a.length)[0] || '/home'
}
