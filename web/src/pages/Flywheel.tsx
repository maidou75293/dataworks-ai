import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Alert, AutoComplete, Button, Card, Collapse, Drawer, Empty, Form, Input, Modal, Popconfirm,
  Radio, Select, Space, Steps, Switch, Table as PreviewTable, Tag, Timeline, Tooltip,
  Typography, message,
} from 'antd'
import {
  DatabaseOutlined, PlusOutlined, ReloadOutlined, SearchOutlined, SettingOutlined,
  SyncOutlined, TableOutlined, ThunderboltOutlined,
} from '@ant-design/icons'
import { ErrorState, LoadingState } from '../components/StateBlocks'
import {
  FLYWHEEL_STEPS, GATE_LABEL, decideGate, fwPickerDatabases, fwPickerProjects, fwPickerSample,
  fwPickerTableInfo, fwPickerTables, listRuns, loadSettings, pumpTick, runDetail,
  saveSettings, startRun,
} from '../api/flywheel'
import type { FlywheelEvent, FlywheelRun, FwProject, FwSample, FwTableInfo, FwTableRow } from '../api/flywheel'
import { listDatasets } from '../api/mos'

const { Text } = Typography

/**
 * 数据飞轮总览（REQ-FW-4；信息架构整改 REQ-FLYWHEEL-PAGE-1， * 开圈入口改版 REQ-FLYWHEEL-ENTRY-1，）。
 *
 * 页级信息架构 = 三步向导：**开一圈 → 过闸 → 看结果**（GuideBand 常驻列表上方，空态同样顶置）；
 * 开一圈（向导第①步）内部也是三步：**可视化选表 → 预览入环数据 → 确认**——业务用户全程不写 SQL，
 * 手写「选表 SQL」与「治理对象 ID」降级为折叠的「高级选项」（入口保留，专家仍可用）；
 * 圈列表按阶段排序，等人过闸的排最前；7 个调参项默认折叠进「高级设置」（编排设置 + 开圈杂项同收纳）；
 * 黑话一律配悬停人话批注（GLOSSARY + Term/withTerms，口径与 REQ-UX-TERMS-1 并批同源）。
 *
 * 一条泳道仍 = 一圈 FlywheelRun，七步「选表入环→标注→训练→评测→部署上岗→badcase 池→回环重标」；
 * 两个人工质量闸（标注完成、评测准入）在本页放行或拒绝；链内步骤归 mos 原生链（口径见卡面 03:03）。
 */

/**
 * 黑话批注表（REQ-FLYWHEEL-PAGE-1）：页面上出现的每个黑话都必须能在这里查到一句人话。
 * 术语口径与 REQ-UX-TERMS-1 并批（该卡做全仓清扫，本表为其在飞轮页的落点；新增黑话先加解释再上桌）。
 */
const GLOSSARY: Record<string, string> = {
  badcase: '模型答得不好的样本（错题）；错题攒够了打包重标，下一圈就专练这些薄弱点',
  闸门: '流程里等你点「放行」的检查点；编排设置改成 AUTO 后就不需要人守',
  封批: '把攒下的错题打包成一批，派回标注环节重做',
  续圈: '这一圈闭环后自动开下一圈（错题攒够才开，不用人手动发起）',
  入池: '错题被登记下来，排队等打包',
  入环: '把选中的数据装进飞轮，正式开始一圈',
  上岗: '评测达标的新模型顶替旧模型，开始对外回答',
  冻结版本: '开圈时锁定的训练数据快照；之后数据变了也不影响本圈',
  回环重标: '把本圈错题重新标注一遍，变成下一圈的原料',
  闭环: '一圈七步全部走完',
}

/** 悬停批注：黑话原文（或自定义显示词）+ GLOSSARY 人话解释。 */
function Term({ word, children }: { word: string; children?: ReactNode }) {
  return (
    <Tooltip title={`【${word}】${GLOSSARY[word] || ''}`}>
      <span style={{ borderBottom: '1px dotted #8c8c8c', cursor: 'help' }}>{children || word}</span>
    </Tooltip>
  )
}

/** 把一段文案里出现的在册黑话逐个包成 Term 批注（长词优先，避免短词截断长词）。 */
function withTerms(text: string): ReactNode[] {
  const words = Object.keys(GLOSSARY).sort((a, b) => b.length - a.length)
  const out: ReactNode[] = []
  let rest = text
  while (rest.length > 0) {
    let hit = -1
    let hitWord = ''
    for (const w of words) {
      const i = rest.indexOf(w)
      if (i >= 0 && (hit < 0 || i < hit)) { hit = i; hitWord = w }
    }
    if (hit < 0) { out.push(rest); break }
    if (hit > 0) out.push(rest.slice(0, hit))
    out.push(<Term key={`w${out.length}`} word={hitWord} />)
    rest = rest.slice(hit + hitWord.length)
  }
  return out
}

/** 七步泳道里需要批注的步（label 原文不变，只挂悬停解释；其余步名已够白话）。 */
const STEP_TIP: Record<string, string> = {
  TABLE: '入环', DEPLOY: '上岗', BADCASE: 'badcase', RELABEL: '回环重标',
}

/** 三步向导（项目负责人 PRD B 段定版顺序：开一圈→过闸→看结果；A1 判据按此序对表）。 */
const GUIDE_STEPS = [
  { key: 'start', title: '① 开一圈', desc: '从数据目录可视化选表、预览、确认入环，全程不用写 SQL（高级选项仍可手写）' },
  { key: 'gate', title: '② 过闸', desc: '圈会停在两道质量闸等你：标注完成后、评测上岗前，在本页看现场再放行/拒绝' },
  { key: 'result', title: '③ 看结果', desc: '闭环后看评测分数、新模型与分步事件明细；错题攒够自动开下一圈' },
]

/** 向导带：三步常显在列表上方，每步带直达动作（开圈钮 / 滚到第一个等闸圈 / 打开最近闭环圈的事件）。 */
function GuideBand({ gateCount, latestDoneId, onStart, onGate, onResult }: {
  gateCount: number
  latestDoneId: number | null
  onStart: () => void
  onGate: () => void
  onResult: () => void
}) {
  const actions = [
    <Button key="go-start" size="small" type="primary" icon={<PlusOutlined />} onClick={onStart}>开一圈</Button>,
    <Button key="go-gate" size="small" disabled={gateCount === 0} onClick={onGate}>
      {gateCount > 0 ? `去放行（${gateCount} 圈等你）` : '暂无等闸的圈'}
    </Button>,
    <Button key="go-result" size="small" disabled={latestDoneId === null} onClick={onResult}>
      {latestDoneId !== null ? `看第 ${latestDoneId} 圈结果` : '暂无已闭环的圈'}
    </Button>,
  ]
  return (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space align="start" style={{ width: '100%' }}>
        {GUIDE_STEPS.map((g) => (
          <div key={g.key} style={{ flex: 1, minWidth: 0 }}>
            <Space direction="vertical" size={2}>
              <Text strong>{g.title}</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>{g.desc}</Text>
              {actions[GUIDE_STEPS.indexOf(g)]}
            </Space>
          </div>
        ))}
      </Space>
    </Card>
  )
}

/** 圈列表按生命周期阶段排序：等你过闸的最先，其次在跑，再看闭环/终止（各阶段内新圈在前）。 */
const STAGE_ORDER: Record<string, number> = { WAIT_GATE: 0, RUNNING: 1, CLOSED: 2, FAILED: 3 }

/** 空态示例（demo 库问答表；项目负责人 PRD B 段：空态引导从一句话升级为带示例的分步指引）。 */
const EXAMPLE_SQL = 'select question, answer from demo.qa'

const STATUS_TAG: Record<string, { color: string; label: string }> = {
  RUNNING: { color: 'processing', label: '进行中' },
  WAIT_GATE: { color: 'gold', label: '等人工闸门' },
  CLOSED: { color: 'green', label: '已闭环' },
  FAILED: { color: 'red', label: '已终止' },
}

const EVENT_COLOR: Record<string, string> = { INFO: 'blue', GATE: 'gold', WARN: 'orange', ERROR: 'red' }

/** 编排设置的人话名（键与默认值对照后端 OrchestratorSettings.java:29-41 + FlywheelSettings.java:25-28）。 */
const SETTING_LABELS: Record<string, { label: string; hint: string }> = {
  'flywheel.orch.annotation_gate': { label: '标注完成闸门', hint: '人工=每圈停这里等你放行；自动=标注齐了直接过' },
  'flywheel.orch.eval_gate': { label: '评测准入闸门', hint: '人工=评测跑完等你看分放行；自动=达标自动过' },
  'flywheel.orch.eval_min_score': { label: '评测准入分下限', hint: '均分低于此值不放行（默认 7）' },
  'flywheel.orch.auto_renew': { label: '闭环后自动续圈', hint: '自动=badcase 攒够开下一圈；关=只闭不续' },
  'flywheel.badcase.confidence_threshold': { label: 'badcase 置信阈值', hint: '上岗模型置信度低于此值算 badcase 入池' },
  'flywheel.badcase.batch_size': { label: '回环封批条数', hint: 'badcase 攒够这个数才封重标注批' },
  'flywheel.relabel.target_model': { label: '回环对照模型', hint: 'badcase 判定对照的模型名（可空）' },
}

function stepIndex(step: string): number {
  const i = FLYWHEEL_STEPS.findIndex((s) => s.key === step)
  return i < 0 ? 0 : i
}

function fmt(ms?: number | null): string {
  return ms ? new Date(ms).toLocaleString() : '—'
}

export default function Flywheel() {
  const nav = useNavigate()
  const [dsOpts, setDsOpts] = useState<{ value: string; label: string }[]>([])
  const [runs, setRuns] = useState<FlywheelRun[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [openOnly, setOpenOnly] = useState(false)
  const [tickBusy, setTickBusy] = useState(false)

  // 事件抽屉
  const [drawerRun, setDrawerRun] = useState<number | null>(null)
  const [drawerEvents, setDrawerEvents] = useState<FlywheelEvent[] | null>(null)
  const [drawerError, setDrawerError] = useState<string | null>(null)

  // 开圈 & 设置
  const [startOpen, setStartOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [settings, setSettings] = useState<Record<string, string | null> | null>(null)
  const [startForm] = Form.useForm()
  const [rejectFor, setRejectFor] = useState<{ id: number; gate: string } | null>(null)
  const [rejectReason, setRejectReason] = useState('')
  // 空态「用示例开一圈」：弹窗 destroyOnClose，示例经 presetExample 走 initialValues（不碰未挂载表单）
  const [presetExample, setPresetExample] = useState(false)
  // 「高级选项（手写 SQL / 治理对象 ID）」折叠面板开合；受控是因为空态示例按钮要程序化展开
  const [advKeys, setAdvKeys] = useState<string[]>([])

  // 开圈向导 · 可视化选表主路径（REQ-FLYWHEEL-ENTRY-1）：选表 → 预览 → 确认，全程不写 SQL
  const [wStep, setWStep] = useState(0)
  const [wProjects, setWProjects] = useState<{ value: number; label: string }[]>([])
  const [wProject, setWProject] = useState<number | undefined>(undefined)
  const [wDbs, setWDbs] = useState<{ value: string; label: string }[]>([])
  const [wDb, setWDb] = useState('')
  const [wSearch, setWSearch] = useState('')
  const [wTables, setWTables] = useState<FwTableRow[]>([])
  const [wTablesBusy, setWTablesBusy] = useState(false)
  const [wTablesError, setWTablesError] = useState('')
  const [wSel, setWSel] = useState('')
  const [wInfo, setWInfo] = useState<FwTableInfo | null>(null)
  const [wInfoBusy, setWInfoBusy] = useState(false)
  const [wQ, setWQ] = useState('')
  const [wA, setWA] = useState('')
  const [wSample, setWSample] = useState<FwSample | null>(null)
  const [wSampleBusy, setWSampleBusy] = useState(false)
  const [wSampleError, setWSampleError] = useState('')

  const refresh = useCallback(async () => {
    try {
      const rows = await listRuns(openOnly)
      setRuns(rows)
      setLoadError(null)
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : '加载失败')
    }
  }, [openOnly])

  useEffect(() => {
    refresh()
    // 编排定时圈 30s 一跳，页面按 20s 跟进即可（只读列表，成本一页一请求）
    const t = setInterval(refresh, 20000)
    return () => clearInterval(t)
  }, [refresh])

  const openDrawer = async (id: number) => {
    setDrawerRun(id)
    setDrawerEvents(null)
    setDrawerError(null)
    try {
      const d = await runDetail(id)
      setDrawerEvents(d.events || [])
    } catch (e) {
      setDrawerError(e instanceof Error ? e.message : '事件加载失败')
    }
  }

  const approve = async (r: FlywheelRun) => {
    if (!r.gateWait) return
    try {
      await decideGate(r.id, r.gateWait, 'approve')
      message.success(`已放行「${GATE_LABEL[r.gateWait]}」`)
      refresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '放行失败')
    }
  }

  const reject = async () => {
    if (!rejectFor) return
    try {
      await decideGate(rejectFor.id, rejectFor.gate, 'reject', rejectReason || undefined)
      message.success('已拒绝，本圈终止')
      setRejectFor(null)
      setRejectReason('')
      refresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '拒绝失败')
    }
  }

  const tick = async () => {
    setTickBusy(true)
    try {
      const out = await pumpTick()
      message.success(`催了一圈：轮询 ${out.polled ?? 0}，入池 ${out.badcaseInserted ?? 0}，`
        + `封批 ${out.relabelBatches ?? 0}，推进 ${out.runsAdvanced ?? 0} 圈`)
      refresh()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '催圈失败（桥接或后端不可用会显式报错）')
    } finally {
      setTickBusy(false)
    }
  }

  const wErr = (e: unknown) => (e instanceof Error ? e.message : '加载失败')

  // 向导派生：搜索框只做前端过滤（表清单一次拉全）；入环 SQL 由向导代拼、用户零手写
  const wFiltered = useMemo(() => {
    const kw = wSearch.trim().toLowerCase()
    if (!kw) return wTables
    return wTables.filter((t) => t.table.toLowerCase().includes(kw)
      || (t.comment || '').toLowerCase().includes(kw))
  }, [wTables, wSearch])
  const wPartitioned = !!(wInfo?.ddl && /PARTITION\s+BY/i.test(wInfo.ddl))
  const wReady = !!(wSel && wQ && wA)
  const genSql = wSel && wQ && wA ? `select \`${wQ}\`, \`${wA}\` from \`${wDb}\`.\`${wSel}\`` : ''

  const wLoadTables = async (projectId: number | undefined, db: string) => {
    if (!db) { setWTables([]); return }
    setWTablesBusy(true)
    setWTablesError('')
    try {
      setWTables(await fwPickerTables(projectId, db))
    } catch (e) {
      setWTables([])
      setWTablesError(wErr(e))
    } finally {
      setWTablesBusy(false)
    }
  }

  const wResetPick = () => {
    setWSel('')
    setWInfo(null)
    setWQ('')
    setWA('')
    setWSample(null)
    setWSampleError('')
  }

  const wLoadDatabases = async (projectId?: number) => {
    try {
      const rows = await fwPickerDatabases(projectId)
      setWDbs(rows.map((r) => ({ value: r.database, label: r.database })))
      // 默认落在第一个有表的库（Doris 元数据里库列表按名字排序，空库在前会选无可选）
      const first = rows.find((r) => (r.tables ?? 0) > 0)?.database || rows[0]?.database || ''
      setWDb(first)
      wResetPick()
      void wLoadTables(projectId, first)
    } catch (e) {
      setWDbs([])
      setWTablesError(wErr(e))
    }
  }

  /** 勾选一张表：拉列与 DDL（分区判定），并猜测问答列（问题/答案字面优先，其次文本列）。 */
  const wPickTable = async (table: string) => {
    setWSel(table)
    setWQ('')
    setWA('')
    setWInfo(null)
    setWSample(null)
    setWSampleError('')
    setWInfoBusy(true)
    try {
      const info = await fwPickerTableInfo(wProject, wDb, table)
      setWInfo(info)
      const names = info.columns.map((c) => c.name)
      const isText = (n: string) => {
        const c = info.columns.find((x) => x.name === n)
        return !!c && /varchar|char|string|text/i.test(String(c.type || ''))
      }
      const byHint = (hints: string[], taken: string) =>
        names.find((n) => n !== taken && hints.some((h) => n.toLowerCase().includes(h)))
      const q0 = byHint(['question', '问题', 'query', 'ask'], '')
        ?? names.find((n) => isText(n) && !/_id$/i.test(n))
        ?? names.find(isText) ?? names[0] ?? ''
      const a0 = byHint(['answer', '答案', 'reply', 'response'], q0)
        ?? names.find((n) => n !== q0 && isText(n))
        ?? names.find((n) => n !== q0) ?? ''
      setWQ(q0)
      setWA(a0)
    } catch (e) {
      setWInfo(null)
      setWSampleError(wErr(e))
    } finally {
      setWInfoBusy(false)
    }
  }

  const wLoadSample = async () => {
    if (!wSel) return
    setWSampleBusy(true)
    setWSampleError('')
    setWSample(null)
    try {
      setWSample(await fwPickerSample(wProject, wDb, wSel, 20))
    } catch (e) {
      setWSampleError(wErr(e))
    } finally {
      setWSampleBusy(false)
    }
  }

  const submitStart = async () => {
    const v = await startForm.validateFields()
    const body: Record<string, unknown> = {}
    for (const [k, val] of Object.entries(v)) {
      if (val !== undefined && val !== '' && val !== null) body[k] = val
    }
    if (!body.sql && !body.dsId) {
      // 主路径（REQ-FLYWHEEL-ENTRY-1）：可视化选表的 SQL 由向导代拼，用户全程没写过一句 SQL
      if (wReady) {
        body.sql = genSql
        body.qColumn = wQ
        body.aColumn = wA
        if (wProject != null) body.projectId = wProject
      } else {
        message.warning('请先勾选一张数据表；也可展开「高级选项」——SQL 选表 或 治理对象 ID 二选一')
        return
      }
    }
    try {
      const run = await startRun(body)
      message.success(`第 ${run.id} 圈已发起（当前在「${GATE_LABEL[run.currentStep] || run.currentStep}」）`)
      setStartOpen(false)
      setPresetExample(false)
      startForm.resetFields()
      refresh()
    } catch (e) {
      Modal.error({ title: '开圈失败', content: e instanceof Error ? e.message : String(e) })
    }
  }

  /** 向导「下一步」：高级选项里已填 SQL/治理对象 ID 时直通旧开圈（专家不被向导绑住）。 */
  const wNext = async () => {
    const v = startForm.getFieldsValue()
    if (v.sql || v.dsId) {
      await submitStart()
      return
    }
    if (wStep === 0) {
      if (!wReady) {
        message.warning('先勾选一张表，并指定问题列/答案列')
        return
      }
      setWStep(1)
      void wLoadSample()
    } else if (wStep === 1) {
      setWStep(2)
    }
  }

  /**
   * 开圈弹窗（REQ-FLYWHEEL-ENTRY-1）：主路径=可视化选表；
   * dsId 从数据集下拉里选（列表失败就退回手填，不挡主表单）；withExample=空态「用示例开一圈」。
   */
  const openStart = (withExample = false) => {
    setStartOpen(true)
    setWStep(0)
    setWSearch('')
    wResetPick()
    setWTablesError('')
    setAdvKeys(withExample ? ['adv-legacy'] : [])
    fwPickerProjects()
      .then((rows: FwProject[]) => setWProjects(
        rows.map((p) => ({ value: p.id, label: `${p.name}（${p.code || p.id}）` }))))
      .catch(() => setWProjects([]))
    void wLoadDatabases(wProject)
    listDatasets()
      .then((rows) => setDsOpts(rows
        // REQ-UX-FLOW-1#6：relabel 台账里 0 条目的不入选池（选中只会空转一圈）；
        // 训练集列表侧的展示与 stale 标记归 MosDatasets，数据面清理另有台账动作。
        .filter((d) => !(String(d.name || '').startsWith('relabel-') && (d.n_items ?? 0) === 0))
        .map((d) => ({
          value: d.id, label: `${d.id} · ${d.name}（${d.n_items ?? 0} 条，${d.status}）`,
        }))))
      .catch(() => setDsOpts([]))
  }

  const openSettings = async () => {
    setSettingsOpen(true)
    setSettings(null)
    try {
      setSettings(await loadSettings())
    } catch (e) {
      setSettings({})
      message.error(e instanceof Error ? e.message : '设置加载失败')
    }
  }

  const saveSettingRow = async (key: string, value: string) => {
    try {
      const snap = await saveSettings({ [key]: value })
      setSettings(snap)
      message.success('已保存，下一圈生效')
    } catch (e) {
      message.error(e instanceof Error ? e.message : '保存失败（非法值会被显式拒绝）')
    }
  }

  const statusOf = (r: FlywheelRun): 'process' | 'finish' | 'error' | 'wait' => {
    if (r.status === 'FAILED') return 'error'
    if (r.status === 'CLOSED') return 'finish'
    if (r.status === 'WAIT_GATE') return 'wait'
    return 'process'
  }

  // 阶段优先排序（等闸最前）；向导带与空态都用同一份派生数
  const list = [...(runs || [])].sort(
    (a, b) => (STAGE_ORDER[a.status] ?? 9) - (STAGE_ORDER[b.status] ?? 9) || b.id - a.id,
  )
  const gateRuns = list.filter((r) => r.status === 'WAIT_GATE')
  const latestDone = list.find((r) => r.status === 'CLOSED') || null

  const prefillExample = () => {
    setPresetExample(true)
    openStart(true)
  }
  const gotoGate = () => {
    document.getElementById('fw-first-gate')?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
  const seeResult = () => {
    if (latestDone) openDrawer(latestDone.id)
    else message.info('还没有已闭环的圈——先按第①步开一圈')
  }

  return (
    <div style={{ padding: 16 }}>
      <Space style={{ marginBottom: 12, width: '100%', justifyContent: 'space-between' }}>
        <Space>
          <Typography.Title level={4} style={{ margin: 0 }}>数据飞轮</Typography.Title>
          <Text type="secondary">
            一圈 = 选表入环→标注→训练→评测→<Term word="上岗">部署</Term>→收集 <Term word="badcase" />→
            <Term word="回环重标">回环</Term>；你只需要把两道 <Term word="闸门">质量闸</Term>
          </Text>
        </Space>
        <Space>
          <span>只看进行中</span>
          <Switch checked={openOnly} onChange={setOpenOnly} />
          <Tooltip title="立即跑一轮编排：轮询训练/评测状态、badcase 入池封批、推进各圈一步（后台每 30 秒也会自动一跳）">
            <Button icon={<ThunderboltOutlined />} loading={tickBusy} onClick={tick}>立即推进</Button>
          </Tooltip>
          <Button icon={<SettingOutlined />} onClick={() => void openSettings()}>编排设置</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openStart()}>开一圈</Button>
        </Space>
      </Space>

      {/* 三步向导常驻（REQ-FLYWHEEL-PAGE-1）：空态/在跑都顶置，新用户的地图在内容之前 */}
      <GuideBand gateCount={gateRuns.length} latestDoneId={latestDone ? latestDone.id : null}
        onStart={() => openStart()} onGate={gotoGate} onResult={seeResult} />

      {loadError && !runs
        ? <ErrorState message={loadError} onRetry={refresh} />
        : runs === null
          ? <LoadingState />
          : list.length === 0
            ? (
              <Card size="small" style={{ textAlign: 'center', padding: '24px 0' }}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={(
                    <Space direction="vertical" size={4}>
                      <Text>还没有飞轮圈 —— 按上面三步开始：开一圈 → 过闸 → 看结果</Text>
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        没想好数据就先照抄示例（demo 库的问答表），跑通第一圈再换自己的表
                      </Text>
                    </Space>
                  )} />
                <Space>
                  <Text code>{EXAMPLE_SQL}</Text>
                  <Button type="primary" icon={<PlusOutlined />} onClick={prefillExample}>用示例开一圈</Button>
                </Space>
              </Card>
            )
            : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {list.map((r) => (
                  <Card
                    key={r.id}
                    size="small"
                    id={gateRuns.length > 0 && gateRuns[0].id === r.id ? 'fw-first-gate' : undefined}
                    title={(
                      <Space>
                        <Text strong>第 {r.id} 圈</Text>
                        <Tag color={STATUS_TAG[r.status]?.color || 'default'}>{withTerms(STATUS_TAG[r.status]?.label || r.status)}</Tag>
                        {r.gateWait && <Tag color="gold">停在：{GATE_LABEL[r.gateWait]}</Tag>}
                        {r.parentRunId ? <Tag>父圈 #{r.parentRunId}</Tag> : null}
                        {r.childRunId ? <Tag color="cyan">下一圈 #{r.childRunId}</Tag> : null}
                      </Space>
                    )}
                    extra={(
                      <Space>
                        {r.status === 'WAIT_GATE' && r.gateWait && (
                          <>
                            {/* 放行前先能去看现场：标注→标注项目页，评测→评测页（REQ-FW-UX-1） */}
                            {r.gateWait === 'ANNOTATE' ? <Button size="small" onClick={() => nav('/mos/labels')}>去标注</Button> : null}
                            {r.gateWait === 'EVAL' ? <Button size="small" onClick={() => nav('/mos/evaluations')}>看评测</Button> : null}
                            <Popconfirm title={`放行「${GATE_LABEL[r.gateWait]}」闸？`} onConfirm={() => approve(r)}>
                              <Button size="small" type="primary">放行</Button>
                            </Popconfirm>
                            <Button size="small" danger onClick={() => setRejectFor({ id: r.id, gate: r.gateWait! })}>拒绝</Button>
                          </>
                        )}
                        <Button size="small" onClick={() => openDrawer(r.id)}>事件</Button>
                      </Space>
                    )}
                  >
                    <Steps
                      size="small"
                      current={stepIndex(r.currentStep)}
                      status={statusOf(r)}
                      items={FLYWHEEL_STEPS.map((s) => ({
                        title: STEP_TIP[s.key] ? <Term word={STEP_TIP[s.key]}>{s.label}</Term> : s.label,
                      }))}
                      style={{ marginBottom: 8 }}
                    />
                    <Space size={4} wrap style={{ fontSize: 12 }}>
                      {/* 外部 id 全部可点（REQ-FW-UX-1）：深链由目标页既有 ?ds=/?job=/?model= 参数承接 */}
                      {r.dsId ? (
                        <Tooltip title={`【冻结版本】${GLOSSARY['冻结版本']}。本圈快照=${r.datasetVersion || '未冻结'}；点开去训练集明细`}>
                          <Tag style={{ cursor: 'pointer' }} onClick={() => nav(`/mos/datasets?ds=${encodeURIComponent(r.dsId!)}`)}>ds {r.dsId}</Tag>
                        </Tooltip>
                      ) : null}
                      {r.jobExtId ? <Tag color="blue" style={{ cursor: 'pointer' }} onClick={() => nav(`/mos/jobs?job=${encodeURIComponent(r.jobExtId!)}`)}>job {r.jobExtId}</Tag> : null}
                      {r.evalExtId ? <Tag color="purple" style={{ cursor: 'pointer' }} onClick={() => nav('/mos/evaluations')}>eval {r.evalExtId}</Tag> : null}
                      {r.modelExtId ? <Tag color="green" style={{ cursor: 'pointer' }} onClick={() => nav(`/mos/models?model=${encodeURIComponent(r.modelExtId!)}`)}>模型 {r.modelName || r.modelExtId}</Tag> : null}
                      {r.deployRef ? <Tag color="cyan">dep {r.deployRef}（<Term word="上岗" /> {fmt(r.deployTsMs)}）</Tag> : null}
                      {r.relabelBatchId ? <Tag style={{ cursor: 'pointer' }} onClick={() => nav('/mos/labels')}>批 {r.relabelBatchId}</Tag> : null}
                      {r.relabelTaskRef ? <Tag style={{ cursor: 'pointer' }} onClick={() => nav('/mos/labels')}>回环 {r.relabelTaskRef}</Tag> : null}
                      <Text type="secondary">发起人 {r.createdBy || '—'} · {fmt(r.createdMs)}</Text>
                    </Space>
                    {r.lastError
                      ? <Alert style={{ marginTop: 8 }} type={r.status === 'FAILED' ? 'error' : 'warning'} showIcon
                          message={r.status === 'FAILED' ? '本圈终止原因' : '当前卡点'} description={r.lastError} />
                      : null}
                    {r.status === 'WAIT_GATE' && !r.lastError
                      ? <Alert style={{ marginTop: 8 }} type="info" showIcon
                          message={`等人工确认「${GATE_LABEL[r.gateWait || '']}」；配置为 AUTO 时会自动放行`} />
                      : null}
                  </Card>
                ))}
              </Space>
            )}

      {/* 事件抽屉：模板=数据集成运行事件（Timeline 按步上色） */}
      <Drawer title={drawerRun ? `第 ${drawerRun} 圈 · 编排事件` : '编排事件'} width={720}
        open={drawerRun !== null} onClose={() => setDrawerRun(null)}>
        {drawerEvents === null && !drawerError ? <LoadingState /> : null}
        {drawerError ? <ErrorState message={drawerError} onRetry={() => openDrawer(drawerRun!)} /> : null}
        {drawerEvents && drawerEvents.length === 0 ? <Empty description="暂无事件" /> : null}
        {drawerEvents && drawerEvents.length > 0 ? (
          <Timeline
            items={drawerEvents.map((ev) => ({
              color: EVENT_COLOR[ev.type] || 'blue',
              children: (
                <>
                  <Space size={6}>
                    <Tag>{FLYWHEEL_STEPS.find((s) => s.key === ev.step)?.label || ev.step}</Tag>
                    <Text type="secondary" style={{ fontSize: 12 }}>{fmt(ev.tsMs)}</Text>
                  </Space>
                  <div>{ev.message}</div>
                  {ev.metrics ? <Text code style={{ fontSize: 12 }}>{ev.metrics}</Text> : null}
                </>
              ),
            }))}
          />
        ) : null}
      </Drawer>

      {/* 开圈（向导第①步，REQ-FLYWHEEL-ENTRY-1）：主路径=可视化选表→预览→确认；
          手写 SQL / 治理对象 ID 保留但降级为折叠「高级选项」；杂项默认折叠进高级设置 */}
      <Modal title="开一圈数据飞轮" open={startOpen} width={880}
        onOk={() => { (wStep < 2 ? wNext() : submitStart()).catch(() => undefined) }}
        okText={wStep === 2 ? '确认开圈' : '下一步'}
        onCancel={() => { setStartOpen(false); setPresetExample(false) }} destroyOnClose>
        <Steps size="small" current={wStep} style={{ marginBottom: 16 }}
          items={[{ title: '选数据表' }, { title: '预览入环数据' }, { title: '确认开圈' }]} />
        <Form form={startForm} layout="vertical"
          initialValues={presetExample
            ? { method: 'sft', evalLimit: 10, sql: EXAMPLE_SQL, qColumn: 'question', aColumn: 'answer' }
            : { method: 'sft', evalLimit: 10 }}>

          {wStep === 0 && (
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <Text type="secondary">不用写 SQL：选一张表、指定问答两列，下一步先预览再确认</Text>
              <Space wrap align="start">
                <div>
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>项目（库表按项目仓库路由）</div>
                  <Select style={{ width: 220 }} allowClear placeholder="默认仓库" value={wProject}
                    onChange={(v?: number) => { setWProject(v); void wLoadDatabases(v) }}
                    options={wProjects} />
                </div>
                <div>
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>数据库</div>
                  <Select style={{ width: 240 }} showSearch optionFilterProp="label" placeholder="数据库"
                    value={wDb || undefined}
                    onChange={(v: string) => { setWDb(v); wResetPick(); void wLoadTables(wProject, v) }}
                    options={wDbs} />
                </div>
                <div>
                  <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>搜索表名/备注</div>
                  <Input allowClear prefix={<SearchOutlined />} placeholder="如 user"
                    value={wSearch} onChange={(e) => setWSearch(e.target.value)} style={{ width: 220 }} />
                </div>
              </Space>
              <div style={{ border: '1px solid #d9d9d9', borderRadius: 8, maxHeight: 240, overflowY: 'auto' }}>
                {wTablesBusy ? <LoadingState rows={2} /> : wTablesError ? (
                  <ErrorState message={wTablesError} onRetry={() => void wLoadTables(wProject, wDb)} />
                ) : wFiltered.length === 0 ? (
                  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} style={{ margin: '12px 0' }}
                    description={wSearch ? `没有匹配「${wSearch}」的表` : '该库暂无表'} />
                ) : wFiltered.map((t) => (
                  <div key={t.table} role="button" data-pick="table-row" onClick={() => void wPickTable(t.table)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: 8, padding: '6px 12px',
                      cursor: 'pointer', borderBottom: '1px solid #f0f0f0',
                      background: wSel === t.table ? '#e6f4ff' : undefined,
                    }}>
                    <Radio checked={wSel === t.table} />
                    <DatabaseOutlined style={{ color: '#8c8c8c' }} />
                    <Text strong>{t.table}</Text>
                    {t.comment ? <Text type="secondary">{t.comment}</Text> : null}
                    <Text type="secondary" style={{ marginLeft: 'auto', fontSize: 12 }}>
                      {t.rows == null ? '行数未知' : `约 ${t.rows} 行`}
                    </Text>
                  </div>
                ))}
              </div>
              {wSel ? (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Space wrap>
                    <Tag icon={<TableOutlined />} color="blue">{wDb}.{wSel}</Tag>
                    {wInfoBusy || !wInfo ? <Text type="secondary">列与分区读取中…</Text> : (
                      <>
                        <Tag>{wInfo.columns.length} 列</Tag>
                        <Tag color={wPartitioned ? 'geekblue' : 'default'}>{wPartitioned ? '分区表' : '未分区'}</Tag>
                      </>
                    )}
                    <Text type="secondary" style={{ fontSize: 12 }}>问答列已按列名自动猜好，不对就手动改</Text>
                  </Space>
                  {wInfo ? (
                    <Space wrap>
                      <div>
                        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>问题列</div>
                        <Select style={{ width: 240 }} value={wQ || undefined} onChange={setWQ}
                          options={wInfo.columns.map((c) => ({ value: c.name, label: `${c.name} · ${c.type || '?'}` }))} />
                      </div>
                      <div>
                        <div style={{ fontSize: 12, color: '#8c8c8c', marginBottom: 2 }}>答案列</div>
                        <Select style={{ width: 240 }} value={wA || undefined} onChange={setWA}
                          options={wInfo.columns.map((c) => ({ value: c.name, label: `${c.name} · ${c.type || '?'}` }))} />
                      </div>
                    </Space>
                  ) : null}
                </Space>
              ) : null}
              {wSampleError && wStep === 0 ? <Alert type="warning" showIcon message={wSampleError} /> : null}
            </Space>
          )}

          {wStep === 1 && (
            <Space direction="vertical" size={10} style={{ width: '100%' }}>
              <Space align="center">
                <Text strong>入环范围</Text>
                <Text type="secondary" style={{ fontSize: 12 }}>下面这段 SQL 由向导代拼、开圈时执行，不用编辑</Text>
              </Space>
              <Text code>{genSql}</Text>
              <Space wrap>
                <Tag color="blue">{wDb}.{wSel}</Tag>
                <Tag>问题列 {wQ}</Tag>
                <Tag>答案列 {wA}</Tag>
                <Tag color={wPartitioned ? 'geekblue' : 'default'}>{wPartitioned ? '分区表' : '未分区'}</Tag>
              </Space>
              {wSampleError ? (
                <ErrorState message={`样例数据加载失败：${wSampleError}`} onRetry={() => void wLoadSample()} />
              ) : wSampleBusy || wSample === null ? <LoadingState rows={3} /> : (
                <>
                  <PreviewTable size="small" pagination={false} scroll={{ x: true }}
                    rowKey={(_r, i) => String(i)}
                    dataSource={wSample.rows.slice(0, 10).map(
                      (r) => Object.fromEntries(wSample.columns.map((c, i) => [c, r[i] ?? ''])))}
                    columns={wSample.columns.map((c) => ({
                      title: c === wQ ? `${c}（问题）` : c === wA ? `${c}（答案）` : c,
                      dataIndex: c, key: c, ellipsis: true,
                    }))} />
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    样例预览 {wSample.rowCount} 行（表内展示前 {Math.min(10, wSample.rows.length)} 行；
                    真正<Term word="入环">进环</Term>的是上面 SQL 的全部查询结果）
                  </Text>
                </>
              )}
            </Space>
          )}

          {wStep === 2 && (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Alert type="info" showIcon message="即将开一圈" description={(
                <Space direction="vertical" size={2} style={{ fontSize: 13 }}>
                  <span>数据表 <Text code>{wDb}.{wSel}</Text>
                    （{wPartitioned ? '分区表' : '未分区'}{wInfo ? `，${wInfo.columns.length} 列` : ''}）</span>
                  <span>映射：问题列 <Text code>{wQ}</Text> → 答案列 <Text code>{wA}</Text></span>
                  <span>入环 SQL（向导代拼） <Text code>{genSql}</Text></span>
                  <span>样例预览 {wSample ? `${wSample.rowCount} 行` : '未加载（可直接开圈）'}</span>
                </Space>
              )} />
              <Text type="secondary" style={{ fontSize: 12 }}>
                开圈后本圈出现在下方列表；停在<Term word="闸门">质量闸</Term>时在本页放行即可
              </Text>
            </Space>
          )}

          {wStep > 0 ? (
            <Button size="small" style={{ marginTop: 12 }} onClick={() => setWStep(wStep - 1)}>上一步</Button>
          ) : null}

          <Collapse size="small" ghost activeKey={advKeys}
            onChange={(k) => setAdvKeys(Array.isArray(k) ? k : [k])}
            items={[{
              key: 'adv-legacy',
              label: <Space><SettingOutlined /><Text type="secondary" style={{ fontSize: 12 }}>高级选项（不用选表：手写 SQL 或指定治理对象 ID）</Text></Space>,
              children: (
                <>
                  <Form.Item name="sql" label="选表 SQL（与治理对象 ID 二选一；只读查询）">
                    <Input.TextArea rows={2} placeholder="select question, answer from demo.qa" />
                  </Form.Item>
                  <Space>
                    <Form.Item name="qColumn" label="问题列"><Input placeholder="question" /></Form.Item>
                    <Form.Item name="aColumn" label="答案列"><Input placeholder="answer" /></Form.Item>
                  </Space>
                  <Form.Item name="dsId" label="或：治理对象（badcase 回环出来的重标注集；下拉可选，也可手填 ds_xxx）">
                    <AutoComplete
                      options={dsOpts} style={{ width: 420 }} placeholder="选择或输入 ds_xxx"
                      filterOption={(i, o) => String(o?.value ?? '').toLowerCase().includes(i.toLowerCase())
                        || String(o?.label ?? '').toLowerCase().includes(i.toLowerCase())}
                    />
                  </Form.Item>
                </>
              ),
            }]} />
          <Collapse size="small" ghost items={[{
            key: 'adv-start',
            // forceRender：折叠态也要挂载表单字段，validateFields 才拿得到 method/evalLimit 初值
            forceRender: true,
            label: <Space><SettingOutlined /><Text type="secondary" style={{ fontSize: 12 }}>高级设置（基座模型 / 训练法 / 评测条数 / 候选服务地址，一般不用动）</Text></Space>,
            children: (
              <>
                <Space wrap>
                  <Form.Item name="base" label="基座模型"><Input placeholder="qwen-base" style={{ width: 180 }} /></Form.Item>
                  <Form.Item name="method" label="训练法"><Input style={{ width: 100 }} /></Form.Item>
                  <Form.Item name="evalLimit" label="评测条数"><Input style={{ width: 90 }} /></Form.Item>
                </Space>
                <Form.Item name="candidateBaseUrl" label="候选服务 base_url（评测可空）"><Input /></Form.Item>
              </>
            ),
          }]} />
        </Form>
      </Modal>

      {/* 编排设置：白名单键，非法值后端显式 400；7 项全部收纳进「高级设置」，默认收起（REQ-FLYWHEEL-PAGE-1） */}
      <Modal title="编排设置（下一圈生效）" open={settingsOpen} footer={null} width={760}
        onCancel={() => setSettingsOpen(false)}>
        {settings === null ? <LoadingState rows={3} /> : (
          <Space direction="vertical" style={{ width: '100%' }}>
            <Text type="secondary">平时不用动——默认配置就能跑圈。以下 7 项调参改完下一圈生效，非法值会被明确拒绝</Text>
            <Collapse size="small" items={[{
              key: 'adv-settings',
              label: (
                <Space>
                  <SettingOutlined />
                  <Text strong>高级设置（7 项调参，默认收起）</Text>
                </Space>
              ),
              children: (
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    闸门=MANUAL 时人工在本页放行；AUTO 也会遵守「评测准入分」下限
                  </Text>
                  {Object.entries(settings).map(([key, val]) => {
                    const meta = SETTING_LABELS[key]
                    return (
                      <Space key={key} align="start" style={{ width: '100%' }}>
                        <div style={{ width: 300 }}>
                          <Text strong>{withTerms(meta?.label || key)}</Text>
                          <div><Text type="secondary" style={{ fontSize: 12 }}>{meta ? withTerms(meta.hint) : '高级项：非法值会被显式拒绝'}</Text></div>
                          <Text type="secondary" code style={{ fontSize: 11 }}>{key}</Text>
                        </div>
                        {key.endsWith('_gate') || key === 'flywheel.orch.auto_renew' ? (
                          <Select
                            size="small" style={{ width: 160 }} value={val || undefined}
                            options={key.endsWith('_gate')
                              ? [{ value: 'MANUAL', label: 'MANUAL（人工闸）' }, { value: 'AUTO', label: 'AUTO（自动放行）' }]
                              : [{ value: 'true', label: 'true（自动续圈）' }, { value: 'false', label: 'false（只闭不续）' }]}
                            onChange={(nv) => saveSettingRow(key, nv)}
                          />
                        ) : (
                          <Input.Search
                            size="small" style={{ width: 220 }} defaultValue={val || ''} enterButton="保存"
                            onSearch={(nv) => saveSettingRow(key, nv)}
                          />
                        )}
                      </Space>
                    )
                  })}
                </Space>
              ),
            }]} />
          </Space>
        )}
      </Modal>

      {/* 拒绝闸门（要原因，落 last_error） */}
      <Modal title="拒绝并终止本圈" open={rejectFor !== null} onOk={reject}
        onCancel={() => { setRejectFor(null); setRejectReason('') }} okText="拒绝" okButtonProps={{ danger: true }}>
        <Input.TextArea rows={3} value={rejectReason} onChange={(e) => setRejectReason(e.target.value)}
          placeholder="为什么不放行？（写进本圈终止原因，例：标注一致率太低）" />
      </Modal>
    </div>
  )
}
