/**
 * MosWorkbench.tsx —— 标注工作台 React 壳（REQ-UNIFY-6-1）。
 *
 * 分工：状态机/契约在 wsLifecycle.ts + wsSchema.ts（Vue 原件口径的纯移植，
 * 对照行号见两文件头注）；本文件只做渲染与浏览器事件接线。
 * 路由 `/mos/label-studio/:pid` 的登记涉及 App.tsx（共享文件）→ 主控合入。
 * 「?」帮助面板是 React 壳新增便利项：Vue 原件 onKey:4634 没有 ? 绑定
 * （快捷键条 :929 只列真实可用键）。BUG-PARITY-5 前帮助表已写「? 打开/关闭本帮助」
 * 却无任何绑定=假承诺；本卡把绑定补成事实（见 onKey 分发 effect 的 '?' 分支），
 * 并给帮助表补上画布引擎实支持键（keys.ts:45-58）。
 *
 * BUG-PARITY-6 可发现性：本文件是「顶栏常驻入口」的落点。老版把队列钮（Vue:12-19）、
 * 最大化/专注钮（:60-71）、空队列直达导入（:173-181）都摆在鼠标用户看得见的位置，
 * React 壳移植时只留了键位与状态位——引擎有 toggleMax/toggleFocus 但没有钮，
 * model.queueOpen 只被写 false 从不写 true（Esc 队列分支成死码），功能等于对不用键盘的
 * 用户不存在。这一层还顺手改了两处**布局归属**：
 * ① 最大化固定层从画布根（WsMediaStage 的 S.rootMax，z-index 2000 盖住顶栏=
 *    「退出最大化」鼠标点不到，正是本卡要修的可发现性缺陷本身）挪到本文件根
 *    data-ws="wb-root"（Vue .wb.wb-max:5515-5524 加在整工作台根上，顶栏跟着一起铺满）；
 * ② 原生全屏请求的目标元素随之从画布根改成本壳根（Vue toggleMax:3130-3136 对 wbRoot 请求）。
 */
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { Alert, Button, Modal, Progress, Select, Tag, Typography, message } from 'antd'
import { TaskQueue } from '../../components/ws/TaskQueue'
import { DraftBar, HelpModal, QuestionCard, ViewingTag } from '../../components/ws/WsPanels'
// REQ-UNIFY-6-3 画布挂载（doc §七 交主控 2 行 diff）+ REQ-UNIFY-6-2 面板挂载（卡面主控合入清单 1）
import { WsMediaStage } from '../../components/ws/canvas/WsMediaStage'
import { WsMediaHealth } from '../../components/ws/WsMediaHealth'
import WsPanel from '../../components/ws/panel/WsPanel'
import { WsDialogBar, WsDialogModals, WsDialogStrips, useWsDialogs } from '../../components/ws/dialogs/WsDialogs'
import { probeWriteAccess, wsStorage, wsTransport } from '../../components/ws/wsApi'
// BUG-PARITY-4：平台内菜单/Link 跳转走 unsaved.ts 同步守卫通道（不触发 popstate/beforeunload）
import { setLeaveGuard } from '../../unsaved'
// BUG-LABEL-WRITE-403-1②：写权限三态——常驻横幅替代一闪 toast、写按钮 disabled+title 解释
import { READONLY_TITLE, getWhoamiInfo, getWriteAccess, readonlyBannerText, subscribeWriteAccess } from '../../components/ws/wsAccess'
import { WorkbenchModel } from './wsLifecycle'
import type { ConfirmReq } from './wsLifecycle'
// BUG-PARITY-4：浏览器前进/后退的离开通道是页面级监听（组件里注册轮不到被调用，见 wsLeaveGuard 头注）
import { disarmMosLeaveGuard, installMosLeaveGuard } from './wsLeaveGuard'

const BORDER = '1px solid #d9d9d9'
/** Vue .q-toggle.on:4869 = 描边与文字变主色，**不是**填充钮：队列开着只是形态，不是「当前动作」 */
const Q_ON: React.CSSProperties = { borderColor: '#1677ff', color: '#1677ff' }

export default function MosWorkbench() {
  const params = useParams()
  const navigate = useNavigate()
  const answerRef = useRef<any>(null)  // antd TextArea 实例（focus()）
  // BUG-PARITY-6：整壳根元素——最大化时的固定层 + 原生全屏请求目标（Vue wbRoot:2 同职责）
  const rootRef = useRef<HTMLDivElement>(null)
  const [help, setHelp] = useState(false)

  const model = useMemo(() => new WorkbenchModel({
    transport: wsTransport,
    storage: wsStorage(),
    // 站内确认框：与 Vue askConfirm 等价语义；model 侧用 confirmOpen 挡住背后画布（:4639）
    askConfirm: (o: ConfirmReq) => new Promise<boolean>((resolve) => {
      Modal.confirm({
        title: o.title,
        content: o.message,
        okText: o.confirmText || '确定',
        okButtonProps: o.danger ? { danger: true } : undefined,
        onOk: () => resolve(true),
        onCancel: () => resolve(false),
      })
    }),
    toast: (lv, t) => {
      if (lv === 'ok') message.success(t)
      else if (lv === 'bad') message.error(t)
      else message.warning(t)
    },
    focusAnswer: () => answerRef.current?.focus(),
  }), [])

  // REQ-UNIFY-6-6 弹窗群＋AI 辅助：动作在 wsDialogActions（headless），这里只接线。
  // 帮助弹窗与九弹窗共写 model.modalOpen，闸门取并集（Vue:4637 同闸语义）。
  const helpRef = useRef(false)
  helpRef.current = help
  const dialogs = useWsDialogs(model, () => helpRef.current)

  // useSyncExternalStore：model 是可变对象，版本号即快照
  useSyncExternalStore((cb) => model.subscribe(cb), () => model.version)

  // BUG-PARITY-6：窄屏判定（Vue isNarrow:1626 / onWindowResize:3162 = innerWidth<=700）。
  // 阈值读取留在本文件（状态机不碰 window，见 wsLifecycle 头注口径），结论写回 model，
  // 队列形态（内联栏 / 抽屉）与「专注钮只在宽屏出现」都读它。首帧也判一次：
  // 只挂 resize 的话，手机宽度进来第一次不拉动窗口就永远按桌面形态渲染。
  useEffect(() => {
    const sync = () => model.setNarrow(window.innerWidth <= 700)
    sync()
    window.addEventListener('resize', sync)
    return () => window.removeEventListener('resize', sync)
  }, [model])

  // BUG-LABEL-WRITE-403-1②：进入工作台探测一次写权限（whoami perms.label）；
  // unknown=乐观态（不灰不禁），真实写 403 由 wsApi/dialogApi chokepoint 翻 readonly
  useEffect(() => { void probeWriteAccess() }, [])
  const access = useSyncExternalStore(subscribeWriteAccess, getWriteAccess)
  const readOnly = access === 'readonly'

  const pid = Number(params.pid)
  useEffect(() => {
    if (!Number.isFinite(pid) || pid <= 0) return
    void model.setPid(pid).then((sw) => {   // 首次与切项目同路径（watch(pid):4748 语义）
      // BUG-PARITY-4：onBeforeRouteUpdate 取消=Router 中止、URL 原地不动；React 里 URL 已经变了，
      // 等价中止=把地址栏 replace 回旧项目（replace 不留历史，否则「后退」又落回一次确认）。
      if (!sw && model.pid > 0) navigate('/mos/label-studio/' + model.pid, { replace: true })
    })
  }, [pid, model, navigate])

  // 列表页「设置画布」深链 ?settings=1（Vue loadShell:3866-3868）：项目就绪后打开设置并清参
  const [searchParams, setSearchParams] = useSearchParams()
  useEffect(() => {
    if (searchParams.get('settings') === '1' && model.project) {
      void dialogs.openSettings()
      setSearchParams({})
    }
  }, [searchParams, setSearchParams, model.project, dialogs])

  // 帮助弹窗打开时 model.modalOpen=true：背后画布不接受 S/J/Ctrl+Enter（Vue:4637 同闸）
  // 6-6 后与弹窗群取并集（store 侧写 on || helpRef，此处写 help || anyOpen，方向一致）
  // BUG-PARITY-1：面板两弹窗不吃本行——WsPanel 写 model.panelModalOpen，画布/壳层闸取并集
  useEffect(() => { model.modalOpen = help || dialogs.anyOpen() }, [model, help, dialogs])

  // 全局键分发（Vue onMounted:4798 window.addEventListener('keydown', onKey)）
  useEffect(() => {
    const handler = async (e: KeyboardEvent) => {
      const tgt = e.target as HTMLElement | null
      // BUG-PARITY-5：帮助表「? 打开/关闭本帮助」补成真绑定（Vue 原件不绑、React 壳新增项）。
      // typing 不劫持（与 Vue:4642 同判据——标注答案里要打问号不能被收掉）；
      // 弹窗群开着不叠帮助（Vue:4637 modalOpen 同闸语义）。
      if (e.key === '?') {
        const tag = (tgt?.tagName || '').toLowerCase()
        if (tag !== 'input' && tag !== 'textarea' && tag !== 'select') {
          if (helpRef.current) setHelp(false)
          else if (!dialogs.anyOpen()) setHelp(true)
          return
        }
      }
      const action = await model.onKey({
        key: e.key, ctrlKey: e.ctrlKey, metaKey: e.metaKey,
        shiftKey: e.shiftKey, altKey: e.altKey,
        tagName: tgt?.tagName,
        inModal: !!tgt?.closest?.('.ant-modal'),
      })
      if (action === 'escape') {
        // BUG-PARITY-6：原症=model.queueOpen 只被写 false、从不写 true（pick:440 与旧 :417），
        // 这条分支永远空转=死码。抽屉真落地后它才有意义：窄屏 Esc 收抽屉，
        // 与「点遮罩收抽屉」走同一个出口（model.closeQueueDrawer）。
        // 两处让位：① typing——Vue:4642 豁免集（在搜索框里按 Esc 不该把抽屉收掉）；
        // ② defaultPrevented——画布键在 capture 阶段先消费并 preventDefault
        //   （退出最大化/专注/取消选中，WsMediaStage:254 handleKey + keys.ts:42/60/61），
        //   别一次 Esc 既退全屏又收抽屉。
        const tag = (tgt?.tagName || '').toLowerCase()
        const typing = tag === 'input' || tag === 'textarea' || tag === 'select'
        if (!typing && !e.defaultPrevented) model.closeQueueDrawer()
        return
      }
      if (action === 'next' || action === 'prev' || action === 'skip'
        || action === 'focus-answer' || action === 'save' || action?.startsWith('choice:')
        // 6-5：Ctrl+Z/Y 消费按键（Vue:4651 preventDefault 无条件执行，空栈也要拦下浏览器原生撤销）
        || action === 'undo' || action === 'redo' || action === 'undo-empty' || action === 'redo-empty') {
        e.preventDefault()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [model, dialogs])

  // beforeUnload:4627 —— 未保存先落草稿再拦浏览器
  useEffect(() => {
    const h = (e: BeforeUnloadEvent) => {
      if (model.beforeUnload()) { e.preventDefault(); e.returnValue = '' }
    }
    window.addEventListener('beforeunload', h)
    return () => window.removeEventListener('beforeunload', h)
  }, [model])

  // BUG-PARITY-4 —— 浏览器前进/后退的离开询问**不在这里挂监听**，交给页面级 wsLeaveGuard。
  // useBlocker 用不了（BrowserRouter 非 data router，直接 invariant 抛错；换 RouterProvider 要动
  // 共享文件 App.tsx/main.tsx）。而挂在组件的 useEffect 上实测问不出来：BrowserRouter 用
  // useSyncExternalStore，真·后退的 popstate 派发里 handlePop 一跑完，本组件当场卸载、清理函数
  // 把自己的监听摘掉 —— 调试件 dbg10 往 window 上另挂两个局外人 canary（一个比 Router 早、一个比
  // 组件的 onPop 晚），两个 canary 都收到了这次 popstate，唯独组件里注册的那个一次没跑。
  // 所以这里只负责「挂载时把页面级监听装上」（幂等、永不注销），拦截/撤销这一步的语义、
  // 与 Vue「中止导航」的差距都写在 wsLeaveGuard.ts 头注里。
  useEffect(() => { installMosLeaveGuard(model) }, [model])

  // BUG-PARITY-4 —— 平台内跳转通道（MainLayout 菜单/退出登录调 canLeaveCurrentPage，
  // 既不触发 popstate 也不触发 beforeunload）。该通道签名是同步 boolean，只能 window.confirm
  // （与 WorkflowEditor 注册的同型）；站内 Modal 覆盖返回钮/换 pid/浏览器后退三条路径。
  useEffect(() => {
    setLeaveGuard(() => {
      if (!model.dirty) return true
      model.persistOnLeave()
      const go = window.confirm('还有未保存的标注\n当前标注还没保存。切走后可以在这条任务的草稿里恢复，仍要离开吗？')
      if (go) disarmMosLeaveGuard()   // BUG-PARITY-4：同意离开就别再追着想问第二次
      return go
    })
    return () => setLeaveGuard(undefined)
  }, [model])

  // BUG-PARITY-4 —— onUnmounted:4819-4821 兜底落盘：确认路径之外的离开（整页跳转等）
  // 也保证 600ms 防抖窗口里的输入当场进草稿。
  useEffect(() => () => { model.persistOnLeave() }, [model])

  // 「← 返回」：老版 back():4622-4625 故意不自问、交给路由守卫；新壳没有 Router 守卫，
  // 就在钮上问这一次（confirmLeave 先 persistDraftNow 再问；答「仍然离开」会 disarm 页面级
  // 守卫，所以紧随的 navigate(-1) 不会再问第二遍）。
  const goBack = async () => {
    if (!(await model.confirmLeave())) return
    navigate(-1)
  }

  const { project } = model
  const nativeUrl = (() => {
    // nativeUrl:3796：LS 登录 Cookie 绑 localhost
    let base = (model.lsUrl || 'http://127.0.0.1:8080').replace(/\/$/, '')
    base = base.replace('://127.0.0.1', '://localhost')
    return base + '/projects/' + model.pid + '/data'
  })()

  const firstTa = model.ctl.textareaCtl
  const answerLen = firstTa ? (model.s.texts[firstTa.from_name] || '').length : 0

  // Vue :13 的 on 态判据（窄屏看抽屉开没开、桌面看有没有收起），逐字同一个表达式喂给
  // class 与 aria-expanded
  const queueOn = model.isNarrow ? model.queueOpen : !model.queueClosed

  // BUG-PARITY-6：最大化层（Vue .wb.wb-max:5519-5524 逐字 position:fixed/inset:0/
  // z-index:900/100vw×100dvh）。关键是加在**整壳根**而不是画布上——原件的顶栏一起铺满，
  // 「退出最大化」钮鼠标点得到；React 旧版把它画在 WsMediaStage 里（z-index 2000 盖住顶栏），
  // 结果只有 Shift+F/Esc 能退出，正是本卡要修的可发现性缺陷。z-index 900 < antd Modal 1000，
  // 「快捷键」帮助窗在最大化时仍可叠在上面（Vue 同序）。
  const rootStyle: React.CSSProperties = {
    display: 'flex', flexDirection: 'column', height: '100%', gap: 8,
    ...(model.maximized
      ? { position: 'fixed', inset: 0, zIndex: 900, margin: 0, width: '100vw',
          height: '100dvh', background: '#fff' }
      : {}),
  }

  return (
    <div ref={rootRef} data-ws="wb-root" style={rootStyle}>
      {/* 顶栏 */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <Button onClick={() => { void goBack() }}>← 返回</Button>
        <Typography.Title level={5} style={{ margin: 0 }}>
          标注工作台{project && project.title ? ` · ${project.title}` : ''}
        </Typography.Title>
        {/* BUG-PARITY-6：队列钮回搬 Vue :12-19（:10-11 的注释点名「一个按钮管两种形态：
            窄屏开抽屉，桌面端收起/展开左栏；以前两个按钮各管一套状态，手机上点错那个
            会让抽屉再也打不开」）。原症=本壳根本没有这颗钮——model.queueOpen 只被写 false、
            queueClosed 字段都不存在，「把宽度让给图」与窄屏抽屉两条路全靠键盘，
            而 Esc 分支又是死码。on 态描边高亮逐字照 .q-toggle.on:4869（不是填充钮）。 */}
        <Button size="small" data-ws="q-toggle"
                aria-label="任务队列"
                aria-expanded={queueOn}
                style={queueOn ? Q_ON : undefined}
                title={model.isNarrow ? (model.queueOpen ? '收起任务抽屉' : '展开任务抽屉')
                                      : (model.queueClosed ? '展开任务队列' : '收起任务队列，把宽度让给图')}
                onClick={() => model.toggleQueue()}>
          ☰ 队列 {model.tasks.length}
        </Button>
        <Tag>总 {model.stats.total}</Tag>
        <Tag color="green">已标注 {model.stats.annotated}</Tag>
        <Tag color="orange">待标注 {model.stats.unannotated}</Tag>
        <span style={{ flex: 1 }} />
        {/* 6-6：AI 就绪标 + predict-box + 更多菜单（Vue 顶栏 26-81 同序） */}
        <WsDialogBar model={model} store={dialogs} />
        {/* BUG-PARITY-6：最大化/专注两钮回搬 Vue :60-71。键位一直在（canvas/keys.ts:42/45/61
            → engine.toggleMax:713/toggleFocus:704），**唯独没有钮**：会用键盘的人不受影响，
            鼠标用户根本不知道画布能铺满屏幕、也不知道为什么队列不见了。
            状态与动作都经 model.canvasView 镜像（单一来源=引擎），钮与键按哪个入口都同步。
            画布没挂载（原生画布项目/还没选任务）时不渲染——没有画布还挂「最大化」=假钮（同
            BUG-PARITY-5 对「按钮没反应」的口径）。专注钮两处让位：最大化时隐藏（Vue :66
            v-if="!maximized"），窄屏也隐藏（Vue CSS :5610 display:none，且引擎 toggleFocus:705
            窄屏直接 return，挂着按不动同样是假钮）。 */}
        {model.canvasView && (
          <Button size="small" data-ws="max-btn" type={model.maximized ? 'primary' : 'default'}
                  aria-pressed={model.maximized}
                  title={model.maximized ? '退出最大化（Shift+F / Esc）' : '最大化：画布铺满整个屏幕（Shift+F）'}
                  onClick={() => model.toggleMax()}>
            {model.maximized ? '退出最大化' : '最大化'}
            <span aria-hidden="true" style={{ marginLeft: 6 }}>{model.maximized ? '⤡' : '⛶'}</span>
          </Button>
        )}
        {model.canvasView && !model.maximized && !model.isNarrow && (
          <Button size="small" data-ws="focus-btn" type={model.focusMode ? 'primary' : 'default'}
                  aria-label={model.focusMode ? '退出专注' : '专注模式'}
                  title={model.focusMode ? '退出专注（F）' : '收起任务队列，画布占满整个右侧（F）'}
                  onClick={() => model.toggleFocus()}>
            {model.focusMode ? '退出专注' : '专注'}
            <span aria-hidden="true" style={{ marginLeft: 6 }}>{model.focusMode ? '⤡' : '⤢'}</span>
          </Button>
        )}
        <Select
          size="small"
          style={{ minWidth: 180 }}
          placeholder="同步目标标注集（可选）"
          value={model.datasetId || undefined}
          onChange={(v) => { void model.setDataset(v) }}
          options={model.lsDatasets.map((d: Record<string, any>) => ({ value: d.id, label: d.name }))}
          notFoundContent="留空将自动新建标注集"
          allowClear
        />
        <Button size="small" loading={model.syncing}
                disabled={!project?.workbench || readOnly}
                title={readOnly ? READONLY_TITLE : '把已标注数据固化成标注集（写到 mos，冻结后可发布给训练）'}
                onClick={() => { void model.sync() }}>
          同步到标注集
        </Button>
        <Button size="small" onClick={() => setHelp(true)}>快捷键</Button>
      </div>

      {/* BUG-LABEL-WRITE-403-1②：只读态常驻横幅（顶部、不自动消失、带申请路径） */}
      {readOnly && (
        <Alert type="warning" showIcon message={READONLY_TITLE}
          description={readonlyBannerText(getWhoamiInfo())} />
      )}

      {/* 6-6：AI 运行条 + 同步完成条（「查看数据集/去训练」深链，Vue:89-112） */}
      <WsDialogStrips model={model} store={dialogs} />

      {/* BUG-LABEL-MEDIA-404-1③：队列图片失效聚合横幅（替代逐条点试错），直达导入任务 */}
      {project && project.workbench && (
        <WsMediaHealth pid={model.pid} filter={model.filter} tasks={model.tasks}
          onImport={() => dialogs.setImportOpen(true)} />
      )}

      {model.loading && !project && <Progress percent={100} status="active" showInfo={false} />}

      {model.loadError && (
        <Alert type="error" message="打不开项目" description={model.loadError}
          action={<Button size="small" onClick={() => navigate(-1)}>返回列表</Button>} />
      )}

      {project && !project.workbench && (
        <Alert type="warning"
          message="这个项目使用原生画布"
          description={<>
            这个 label_config 里有平台画布暂不支持的控件
            {project.schema && project.schema.reason ? `：${project.schema.reason}` : '（如 ParagraphLabels、Ranker 或 per-region 表单）'}；
            在原生画布完成后，数据仍会回到平台标注集。
            <Button type="primary" size="small" style={{ marginLeft: 8 }}
                    href={nativeUrl} target="_blank" rel="noopener">打开原生画布 ↗</Button>
          </>}
        />
      )}

      {/* 主体：队列 + 画布（原生画布/加载失败绝不渲染平台画布，Vue:130 注释——
          不支持控件会被 buildResult 静默丢掉，保存一次就把原生标注覆盖没了） */}
      {!model.loadError && project && project.workbench && (
        <div data-ws="wb-body"
             style={{ display: 'flex', flex: 1, minHeight: 0, position: 'relative',
                      border: BORDER, borderRadius: 6, overflow: 'hidden', background: '#fff' }}>
          {/* BUG-PARITY-6：窄屏抽屉遮罩（Vue :135 v-if="queueOpen" + CSS :4942 宽屏不显示 /
              :5592 窄屏 absolute inset 0 z-45 rgba(15,23,42,.3)）。点遮罩收抽屉；
              桌面不渲染——toggleQueue 在宽屏走 queueClosed，queueOpen 恒 false。
              层位与原件一致：遮罩 45 < 抽屉 46（TaskQueue 自己的抽屉样式）。 */}
          {model.isNarrow && model.queueOpen && (
            <div data-ws="queue-backdrop" onClick={() => model.closeQueueDrawer()}
                 style={{ position: 'absolute', inset: 0, zIndex: 45, background: 'rgba(15, 23, 42, .3)' }} />
          )}
          <TaskQueue model={model} onImport={() => dialogs.setImportOpen(true)} />
          <main style={{ flex: 1, overflowY: 'auto', padding: 12, minWidth: 0 }}>
            {!model.selected && (
              <div style={{ textAlign: 'center', color: '#888', marginTop: 80 }}>
                <b>选一条任务开始</b>
                <div style={{ fontSize: 12, marginTop: 4 }}>左侧队列点一条，或按 J / K 切换</div>
              </div>
            )}
            {model.selected && model.detail && (
              <>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginBottom: 8 }}>
                  <span style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}>任务 #{model.selected.id}</span>
                  <Tag color={model.selected.annotated ? 'green' : 'orange'}>
                    {model.selected.annotated ? '已标注' : '未标注'}
                  </Tag>
                  {model.dirty && <Tag color="gold">未保存</Tag>}
                  <ViewingTag model={model} />
                  <span style={{ flex: 1 }} />
                  {/* 6-6：训练样本预览（Vue:561-563 同位） */}
                  <Button size="small" type="text" title="看看这条会变成训练文件里的哪一行"
                          onClick={() => { void dialogs.openPreview() }}>预览训练样本</Button>
                  <span style={{ fontSize: 12, color: '#888' }}>{model.indexLabel}</span>
                  <Button size="small" disabled={!model.hasPrev} title="上一条（K）"
                          onClick={() => model.navigate(-1)}>←</Button>
                  <Button size="small" disabled={!model.hasNext} title="下一条（J）"
                          onClick={() => model.navigate(1)}>→</Button>
                </div>

                <DraftBar model={model} />

                {project.expert_instruction ? (
                  <details open={project.show_instruction !== false} style={{ marginBottom: 8 }}>
                    <summary style={{ fontSize: 12, color: '#666', cursor: 'pointer' }}>标注说明</summary>
                    <div style={{ fontSize: 12.5, color: '#555', padding: '6px 8px', border: BORDER, borderRadius: 4, marginTop: 4 }}>
                      {project.expert_instruction}
                    </div>
                  </details>
                ) : null}

                <QuestionCard model={model} />

                {/* BUG-PARITY-5：缺控件横幅三动作接线（动作在 dialogs store，
                    Vue :3878-3928 移植；改项目配置不归画布卡） */}
                <WsMediaStage model={model}
                  onAddBox={() => { void dialogs.addImageBoxControl() }}
                  onAddDescription={() => { void dialogs.addImageDescription() }}
                  onOpenSettings={() => { void dialogs.openSettings() }}
                  fixingBox={dialogs.fixingBox}
                  fixingDesc={dialogs.fixingDesc}
                  // BUG-PARITY-6：全屏对象=整壳根（Vue toggleMax 对 wbRoot 请求，:3134），
                  // 传画布根会把顶栏切在元素外面
                  getFsRoot={() => rootRef.current} />

                <div style={{ border: BORDER, borderRadius: 6, padding: 12 }}>
                  <WsPanel model={model} answerRef={answerRef} />

                  <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginTop: 6, flexWrap: 'wrap' }}>
                    <Button type="primary" loading={model.saving}
                            disabled={!model.canSave || readOnly}
                            title={readOnly
                              ? READONLY_TITLE + '——写按钮已禁用，申请路径见页面顶部横幅'
                              : model.canSave
                                ? (model.emptyAllowed
                                    ? '允许空标注：也可以不填内容直接保存（Ctrl+Enter 或 Ctrl+S）'
                                    : '保存（Ctrl+Enter 或 Ctrl+S）')
                                : '先填写标注内容（项目未开启空标注）'}
                            onClick={() => { void model.save() }}>
                      {model.saveLabel}
                    </Button>
                    {project.show_skip_button !== false && (
                      <Button onClick={() => { void model.skip() }}>跳过 <kbd style={KBD}>S</kbd></Button>
                    )}
                    <Button size="small" disabled={!model.hasPrev} title="上一条（K）"
                            onClick={() => model.navigate(-1)}>← 上一条</Button>
                    <Button size="small" disabled={!model.hasNext} title="下一条（J）"
                            onClick={() => model.navigate(1)}>下一条 →</Button>
                    {/* 6-5 撤销/重做（Vue:924-927 同位同禁用口径：栈空即灰） */}
                    <Button size="small" disabled={!model.undoCount}
                            title="撤销 (Ctrl+Z)" aria-label="撤销"
                            onClick={() => { model.undo() }}>↶</Button>
                    <Button size="small" disabled={!model.redoCount}
                            title="重做 (Ctrl+Shift+Z)" aria-label="重做"
                            onClick={() => { model.redo() }}>↷</Button>
                    <span style={{ flex: 1 }} />
                    {firstTa && !model.ctl.perRegionText && model.mode !== 'image' ? (
                      <span style={{ fontSize: 11.5, color: '#888', fontFamily: 'ui-monospace, Consolas, monospace' }}>
                        {answerLen} 字
                      </span>
                    ) : null}
                    <span style={{ fontSize: 12, color: '#888' }}>
                      <kbd style={KBD}>Ctrl S</kbd> 保存 <kbd style={KBD}>Ctrl Z</kbd> 撤销
                      <kbd style={KBD}>J</kbd> 下一条
                      <kbd style={KBD}>K</kbd> 上一条 <kbd style={KBD}>1-9</kbd> 选项
                      <kbd style={KBD}>/</kbd> 聚焦
                    </span>
                  </div>
                </div>
              </>
            )}
            {model.selected && !model.detail && !model.detailLoading && (
              <Alert type="error" style={{ marginTop: 12 }}
                message={model.detailError || '任务详情加载失败'}
                action={<Button size="small" onClick={() => model.retryDetail()}>重试</Button>} />
            )}
          </main>
        </div>
      )}

      {/* 6-6：九弹窗面（导入/QC/设置/AI映射/预览/任务数据抽屉——含 BUG-PARITY-3 标注历史） */}
      <WsDialogModals model={model} store={dialogs} />
      <HelpModal open={help} onClose={() => setHelp(false)} />
    </div>
  )
}

const KBD: React.CSSProperties = {
  border: '1px solid #d9d9d9', borderRadius: 3, padding: '0 4px', fontSize: 10,
  color: '#888', background: '#fafafa', fontFamily: 'ui-monospace, Consolas, monospace',
}
