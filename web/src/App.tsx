import { lazy, ReactNode, Suspense, useEffect, useState } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { message, Spin } from 'antd'
import Login from './pages/Login'
import MainLayout from './layouts/MainLayout'
import { isLoggedIn, logout, refreshMe } from './auth'
import { canAny } from './permissions'

// 页面级拆包：编辑器/数据集成这些重页面不再拖慢登录和首页首屏，
// 只有真正进入对应路由时才下载。登录页保持同步加载，避免首页再多一次往返。
const Development = lazy(() => import('./pages/Development'))
const WorkflowEditor = lazy(() => import('./pages/WorkflowEditor'))
const Instances = lazy(() => import('./pages/Instances'))
const Ops = lazy(() => import('./pages/Ops'))
const Quality = lazy(() => import('./pages/Quality'))
const Projects = lazy(() => import('./pages/Projects'))
const Query = lazy(() => import('./pages/Query'))
const DataAssets = lazy(() => import('./pages/DataAssets'))
const Integration = lazy(() => import('./pages/Integration'))
const Stream = lazy(() => import('./pages/Stream'))
const StreamJob = lazy(() => import('./pages/StreamJob'))
const Tables = lazy(() => import('./pages/Tables'))
const Functions = lazy(() => import('./pages/Functions'))
const DataSources = lazy(() => import('./pages/DataSources'))
const Service = lazy(() => import('./pages/Service'))
const Bi = lazy(() => import('./pages/Bi'))
const Security = lazy(() => import('./pages/Security'))
const Access = lazy(() => import('./pages/Access'))
const ChangeRequests = lazy(() => import('./pages/ChangeRequests'))
const ChangeRequestDetail = lazy(() => import('./pages/ChangeRequestDetail'))
const Alerts = lazy(() => import('./pages/Alerts'))
const Baselines = lazy(() => import('./pages/Baselines'))
const System = lazy(() => import('./pages/System'))
const GravitinoCare = lazy(() => import('./pages/GravitinoCare'))
const NotFound = lazy(() => import('./pages/NotFound'))
const Home = lazy(() => import('./pages/Home'))
const Help = lazy(() => import('./pages/Help'))
const Assets = lazy(() => import('./pages/Assets'))
const MosModels = lazy(() => import('./pages/MosModels'))
const MosJobs = lazy(() => import('./pages/MosJobs'))
const MosArtifacts = lazy(() => import('./pages/MosArtifacts'))
const MosDatasets = lazy(() => import('./pages/MosDatasets'))
const MosLabels = lazy(() => import('./pages/MosLabels'))
const MosEvaluations = lazy(() => import('./pages/MosEvaluations'))
const MosWorkbench = lazy(() => import('./pages/mos/MosWorkbench'))
const Flywheel = lazy(() => import('./pages/Flywheel'))

/** 页面加载占位：只占内容区，侧边栏不跟着闪。 */
function page(node: ReactNode) {
  return (
    <Suspense fallback={(
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 120 }}>
        <Spin size="large" />
      </div>
    )}>
      {node}
    </Suspense>
  )
}

/** 旧入口重定向：保留查询串，编辑器/告警的深链（?workflowId=、?instance=）不受影响。 */
function Redirect({ to }: { to: string }) {
  const { search } = useLocation()
  return <Navigate to={`${to}${search}`} replace />
}

function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation()
  const [ready, setReady] = useState(false)
  useEffect(() => {
    if (!isLoggedIn()) {
      setReady(true)
      return
    }
    let alive = true
    // 登录后刷新一次角色：升级前的老会话、或管理员刚改过角色，界面按最新角色渲染
    refreshMe()
      .catch((e: any) => {
        if (e?.status === 403) {
          // 账号被禁用：留在界面里只会处处 403，直接回登录页并说明原因
          message.error(e?.message || '账号已被禁用，请联系平台管理员')
          logout()
          window.location.replace('/login')
          return
        }
        // 网络抖动时继续用上次缓存的角色，但要告诉用户看到的可能不是最新权限
        message.warning('权限信息刷新失败，当前显示的是上次缓存的角色；刷新页面可重试')
      })
      .finally(() => {
        if (alive) setReady(true)
      })
    return () => {
      alive = false
    }
  }, [])
  if (!isLoggedIn()) {
    // 带上去向：登录后回到原来的深链（含查询串），而不是一律回首页
    const redirect = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?redirect=${redirect}`} replace />
  }
  if (!ready) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', paddingTop: 160 }}>
        <Spin size="large" />
      </div>
    )
  }
  return <>{children}</>
}

/** 任一权限点即可进入；判定在服务端还会再做一次。
 * 被拦时不再静默弹回首页（ux 审计 A-4）：先用全局 message 给一次性提示，再 Navigate 回 /home。
 * 同 key 去重：StrictMode 双调用或用户连点受限入口，都只显示一条、不叠加。 */
function RequireAny({ atoms, label, children }: { atoms: string[]; label: string; children: ReactNode }) {
  const allowed = canAny(...atoms)
  useEffect(() => {
    if (!allowed) {
      message.warning({
        content: `无权限访问「${label}」，请联系管理员分配角色`,
        key: 'no-access-' + label,
      })
    }
  }, [allowed, label])
  return allowed ? <>{children}</> : <Navigate to="/home" replace />
}

const MANAGE_ATOMS = ['user:manage', 'role:manage', 'tenant:manage', 'department:manage']
const SECURITY_ATOMS = ['security:read', 'audit:read', 'data:classify', 'mask:manage']

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <MainLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/home" replace />} />
        <Route path="home" element={page(<Home />)} />
        <Route path="help" element={page(<Help />)} />
        <Route path="dev" element={page(<Development />)} />
        <Route path="dev/:workflowId" element={page(<WorkflowEditor />)} />
        <Route path="query" element={page(<Query />)} />
        <Route path="ops" element={page(<Ops />)} />
        <Route path="ops/instances" element={page(<Instances />)} />
        <Route path="ops/alerts" element={page(<Alerts />)} />
        <Route path="ops/baselines" element={page(<Baselines />)} />
        <Route path="ops/system" element={page(<System />)} />
        <Route path="ops/gravitino"
          element={page(<RequireAny atoms={['datasource:write']} label="元数据巡检"><GravitinoCare /></RequireAny>)} />
        {/* 旧地址保留重定向：书签、编辑器跳转和外部告警链接继续可用 */}
        <Route path="instances" element={<Redirect to="/ops/instances" />} />
        <Route path="alerts" element={<Redirect to="/ops/alerts" />} />
        <Route path="system" element={<Redirect to="/ops/system" />} />
        <Route path="quality" element={page(<Quality />)} />
        <Route path="admin" element={page(<RequireAny atoms={MANAGE_ATOMS} label="管理中心"><Projects /></RequireAny>)} />
        {/* 部门/用户是「管理中心」的分页，旧地址保留深链 */}
        <Route path="departments" element={page(<RequireAny atoms={MANAGE_ATOMS} label="部门"><Projects /></RequireAny>)} />
        <Route path="users" element={page(<RequireAny atoms={MANAGE_ATOMS} label="用户与权限"><Projects /></RequireAny>)} />
        <Route path="roles" element={page(<RequireAny atoms={MANAGE_ATOMS} label="角色与权限点"><Projects /></RequireAny>)} />
        <Route path="map" element={page(<DataAssets />)} />
        <Route path="lineage" element={page(<DataAssets />)} />
        <Route path="integration" element={page(<Integration />)} />
        <Route path="stream" element={page(<Stream />)} />
        <Route path="stream/:id" element={page(<StreamJob />)} />
        <Route path="tables" element={page(<Tables />)} />
        <Route path="assets" element={page(<Assets />)} />
        <Route path="functions" element={page(<Functions />)} />
        <Route path="datasources" element={page(<DataSources />)} />
        {/* REQ-UNIFY-2：MOS 模型资产；入口按 mos:access 权限点裁剪（REQ-UNIFY-3+ 在此组内续页） */}
        <Route
          path="mos/models"
          element={page(<RequireAny atoms={['mos:access']} label="模型训练（MOS）"><MosModels /></RequireAny>)}
        />
        {/* REQ-UNIFY-3：训练监控页（Jobs.vue 重写，列表/详情抽屉/日志流/停止重试） */}
        <Route
          path="mos/jobs"
          element={page(<RequireAny atoms={['mos:access']} label="模型训练（MOS）"><MosJobs /></RequireAny>)}
        />
        {/* BUG-PARITY-12：全部产物台账（Artifacts.vue 重写）；入口＝训练任务页右上角「全部产物」
            （老版 navItems.js:26 决策：产物挂任务详情、台账从任务页进入，不进侧栏菜单） */}
        <Route
          path="mos/artifacts"
          element={page(<RequireAny atoms={['mos:access']} label="全部产物（MOS）"><MosArtifacts /></RequireAny>)}
        />
        {/* REQ-UNIFY-5：数据集管理页（Datasets.vue 重写，列表/详情/上传入口，数据+媒体两口径） */}
        <Route
          path="mos/datasets"
          element={page(<RequireAny atoms={['mos:access']} label="模型训练（MOS）"><MosDatasets /></RequireAny>)}
        />
        {/* REQ-UNIFY-6-2：标注项目列表页（vendor LabelStudio.vue 的列表/新建最小等价），同 mos:access 收口 */}
        <Route
          path="mos/labels"
          element={page(<RequireAny atoms={['mos:access']} label="模型训练（MOS）"><MosLabels /></RequireAny>)}
        />
        {/* REQ-UNIFY-4：模型评测页（Evaluations.vue 等价重写），同 mos:access 收口 */}
        <Route
          path="mos/evaluations"
          element={page(<RequireAny atoms={['mos:access']} label="模型训练（MOS）"><MosEvaluations /></RequireAny>)}
        />
        {/* REQ-UNIFY-6-1：标注工作台 React 壳（LabelStudioWorkbench.vue 等价），深链带项目 id */}
        <Route
          path="mos/label-studio/:pid"
          element={page(<RequireAny atoms={['mos:access']} label="标注工作台"><MosWorkbench /></RequireAny>)}
        />
        {/* REQ-FW-4：数据飞轮编排总览（权限点同后端 @RequireAtom mos:flywheel） */}
        <Route
          path="flywheel"
          element={page(<RequireAny atoms={['mos:flywheel']} label="数据飞轮"><Flywheel /></RequireAny>)}
        />
        <Route path="service" element={page(<Service />)} />
        <Route path="bi" element={page(<Bi />)} />
        <Route path="security" element={page(<RequireAny atoms={SECURITY_ATOMS} label="安全中心"><Security /></RequireAny>)} />
        <Route path="access" element={page(<Access />)} />
        {/* SQL 变更审核：有提交或审批权限点即可进入；按 id 直达也在服务端做租户校验 */}
        <Route path="changes" element={page(<RequireAny atoms={['change:submit', 'change:approve']} label="SQL 变更"><ChangeRequests /></RequireAny>)} />
        <Route path="changes/:id" element={page(<RequireAny atoms={['change:submit', 'change:approve']} label="SQL 变更"><ChangeRequestDetail /></RequireAny>)} />
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}
