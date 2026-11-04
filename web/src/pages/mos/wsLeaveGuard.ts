/**
 * wsLeaveGuard.ts —— 标注工作台「浏览器前进/后退」离开通道（BUG-PARITY-4）。
 *
 * 为什么是**页面级模块**而不是 MosWorkbench 里的 useEffect（本卡第一版就挂在组件上，实测不成立）：
 * react-router 6 的 BrowserRouter 走 useSyncExternalStore 同步 store 变更，真·`history.back()`
 * 派发 popstate 时，Router 的 `handlePop` 一跑完，工作台组件**在这次派发里就被卸载**，它的清理
 * 函数把自己的监听摘掉。调试件 dbg10（parity4 临时件）钉死这件事：往 window 上另挂两个局外人
 * canary——一个比 Router 还早、一个比组件的 onPop 还晚——真后退时两个 canary 都收到了这次
 * popstate（C0@408 / C1@411），唯独组件里注册的那个一次没跑（留痕为空、0 个弹窗）。
 * 换句话说：这条通道上「组件自己问」物理上问不出来，capture 也救不了（popstate 的 target 就是
 * window，同 target 按注册顺序派发，而本工作台是 lazy 路由必然晚于 Router）。
 *
 * 于是：① 监听装在模块上，一次装好、**永不注销**（组件卸载不影响它）；② 确认框用宿主注入的
 * 站内静态 Modal.confirm——它挂在 document.body，组件没了它还在（dbg6 实测卸载后照样弹出、
 * 照样能点）；③ 这条通道拦不住导航（Router 已经把人带到目标页），所以「取消」的语义改成
 * **撤销刚发生的这一步历史**：`window.history.go(undo)`，undo 由 @remix-run/router 记在
 * `history.state.idx` 的条目号算出（不新增条目、也不吞用户的历史条目，也不需要按时间猜的
 * 「放行窗口」——撤销这一步的落点还在工作台路由里，被下面的同路由判断按语义吃掉：dbg11 实测
 * 那一刻守卫读到的甚至是刚挂载、草稿还没回放的新实例（不脏），也就是**两道闸都兜得住**，
 * 但「落点还在工作台=不是离开」才是道理，不该指望时序；红检 R10 用 T11（/8→/7 同路由后退，
 * 守卫若不让路就和 setPid 各问一遍=2 个弹窗）把这道闸钉死）；④ 撤销后重挂载
 * 的内容由草稿自动回放（offerDraft），基线仍是服务器版本，所以**照样算未保存**：「未保存」标
 * 还在、下次离开还会再问（这条口径写在 wsLifecycle.offerDraft，红检 R11 专门盯着它）。
 *
 * 派发时序（dbg11/dbg12 留痕实测，写清免得后人靠猜）：一次 popstate 里 Router 的监听先跑并**同步**
 * 完成重渲染，React 的 passive effect 在「每个监听跑完后的 microtask 检查点」里就刷完了 —— 所以本模块
 * 监听跑到时，落点若在工作台路由，看到的可能是刚挂载、草稿还没回放的新实例（`dirty=false`，dbg11），
 * 也可能 `confirmOpen` 已被 pid effect 的 setPid 置真（dbg12：/8→/7 后退时它的确认框已经挂上）。
 * 于是「同一次导航只问一遍」这件事由三道闸共同兜着：同路由让路（WS_PATH）/ 已有确认框不叠层
 * （confirmOpen）/ 没改东西不问（dirty）。**别把时序当保证**：语义上「落点还在工作台里挪动＝不是
 * 离开」这条判断本身必须写在 WS_PATH 上，红检按实测留痕分配证人（让路闸的单独证人是 T12 同地址后退）。
 *
 * 与 Vue 原件的差距如实记：Vue 是「中止导航、组件不卸载、屏上内容原地不动」；这里是「导航完成
 * 后撤销这一步」，取消会有一次重挂载闪烁，内容靠草稿回捞而不是靠内存——防丢语义等价（不丢字、
 * 不丢任务、走之前必问），体验略逊。要收回成真·中止得上 useBlocker/RouterProvider，那要改
 * 共享文件 App.tsx/main.tsx（notes/lane-m7.md：main.tsx/App.tsx 属主控）→ 本卡不动。
 */
import type { WorkbenchModel } from './wsLifecycle'

/** 工作台路由前缀（App.tsx:200 `mos/label-studio/:pid`） */
const WS_PATH = /^\/mos\/label-studio\/\d+/

/** @remix-run/router 的 getIndex()：条目号写在 history.state.idx，缺失就按「后退一步」算 */
function readIdx(): number | null {
  const st = window.history.state as { idx?: number } | null
  return st && typeof st.idx === 'number' ? st.idx : null
}

let cur: WorkbenchModel | null = null
let armed = false                 // 有未保存内容、下次离开要问
let installed = false
let lastIdx: number | null = null

/** 组件挂载时调用：把页面级监听装上（幂等、永不注销），并指向这个实例 */
export function installMosLeaveGuard(model: WorkbenchModel): void {
  if (typeof window === 'undefined') return
  cur = model
  lastIdx = readIdx()
  if (installed) return
  installed = true
  window.addEventListener('popstate', onPop)
}

/** 改了一笔 → 这条通道重新武装（wsLifecycle 的 markEdited/touch 调） */
export function armMosLeaveGuard(model: WorkbenchModel): void { cur = model; armed = true }

/** 用户已同意离开（返回钮/菜单/后退任一通道答了「仍然离开」）→ 别再问第二遍。
 *  有了这一步，紧随的那次 history 后退（navigate(-1)）自然不会再问，不需要「放行窗口」那种
 *  按时间猜的机制——猜的时间窗既会漏也会误放，能被场景证伪的只有「答过就别再问」这个状态位。 */
export function disarmMosLeaveGuard(): void { armed = false }

function onPop(): void {
  const fromIdx = lastIdx
  const idx = readIdx()
  lastIdx = idx
  const m = cur
  if (!m || !armed) return
  // 目标还在工作台路由里：同路由换 pid 交给 setPid（一次导航只问一遍——不让路就会各问一遍，
  // 验收场景 T11 就是钉这一条的），而「回到工作台」本来就该安静回来（内容靠草稿回放兜）；
  // 我们自己撤销后退的那一步也正落在这里，所以不需要额外的放行窗口。
  if (WS_PATH.test(window.location.pathname)) return
  if (m.confirmOpen) return                 // 站内已有确认框：不叠第二层
  if (!m.dirty) { armed = false; return }   // 没改东西：正常前进/后退，不打扰
  const undo = fromIdx != null && idx != null ? fromIdx - idx : 1
  void m.confirmLeave().then((go) => {
    if (go) return                 // 确认要走：Router 已把人带到目标页，什么都不用补
    window.history.go(undo)        // 取消=撤销这一步历史，回到工作台条目
  })
}
