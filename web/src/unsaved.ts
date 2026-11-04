/**
 * 全局「离开前确认」钩子。
 *
 * 浏览器刷新/关闭由 beforeunload 拦截；SPA 内部跳转（左侧菜单、退出登录）
 * 不触发 beforeunload，必须由页面注册一个同步守卫。返回值 true 表示可以离开。
 */
let guard: (() => boolean) | null = null

export function setLeaveGuard(fn?: () => boolean): void {
  guard = fn ?? null
}

export function canLeaveCurrentPage(): boolean {
  return guard ? guard() : true
}
