import { useEffect, useMemo, useRef, useState } from 'react'
import {
  Button, Card, Col, DatePicker, Form, Input, InputNumber, Modal, Popconfirm, Row, Select,
  Space, Switch, Table, Tag, Tooltip, Typography, message,
} from 'antd'
import {
  DeleteOutlined, EditOutlined, PlayCircleOutlined, PlusOutlined, ReloadOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import { api, errMsg, del, post, put } from '../api'
import { can } from '../permissions'
import PageHeader from '../components/PageHeader'
import SearchableSelect from '../components/SearchableSelect'
import { ErrorState, tableEmpty } from '../components/StateBlocks'
import { useGlobalProject } from '../project'
import type { Project, QualityResult, QualityRule } from '../types'

/**
 * CONFLICT-WM3-2（REQ-DW-7 × TC7-G1 合并裁决）：内置统计模板清单扩到 10 类，
 * 与后端 QualityController.RULE_TYPES 同一份（含波动率/正则/枚举/离散值，
 * 「类型下拉 10 类」判据保持）。REQ-DW-7 简化骨架不变：选检查方式 → 填行内参数，
 * 不手写 JSON、不选数据源；「内置模板」下拉消费 GET /api/quality/templates（15 项），
 * 选模板自动带出行内参数；模板接口取不到时退化为手填参数，十类仍可创建。
 */
const RULE_TEMPLATES = [
  { value: 'ROW_COUNT', label: '表行数 —— 行数达到下限（最常用：当天有数据）' },
  { value: 'VOLATILITY', label: '行数波动率 —— 相对 N 天前的行数变化不超过上限' },
  { value: 'NULL_RATE', label: '字段空值率 —— 空值占比不超过上限' },
  { value: 'UNIQUE', label: '字段唯一性 —— 重复行数不超过容忍值' },
  { value: 'RANGE', label: '字段数值范围 —— 数值不越界' },
  { value: 'REGEX', label: '格式校验 —— 字段值匹配正则，不匹配行数不超过容忍值' },
  { value: 'ENUM', label: '枚举白名单 —— 字段值必须落在白名单内' },
  { value: 'DISCRETE', label: '离散值个数 —— 去重值个数落在设定区间内' },
  { value: 'FRESHNESS', label: '数据新鲜度 —— 执行日期的数据已到达' },
  { value: 'CUSTOM_SQL', label: '自定义 SQL —— 一条只读查询返回单个数字（高级）' },
]

const TYPE_LABELS: Record<string, string> = {
  ROW_COUNT: '表行数', VOLATILITY: '行数波动率', NULL_RATE: '空值率', UNIQUE: '唯一性',
  RANGE: '数值范围', REGEX: '格式校验', ENUM: '枚举白名单', DISCRETE: '离散值个数',
  FRESHNESS: '新鲜度', CUSTOM_SQL: '自定义 SQL',
}

const CODE_SUFFIX: Record<string, string> = {
  ROW_COUNT: 'rowcount', VOLATILITY: 'vol', NULL_RATE: 'nullrate', UNIQUE: 'unique',
  RANGE: 'range', REGEX: 'regex', ENUM: 'enum', DISCRETE: 'discrete',
  FRESHNESS: 'fresh', CUSTOM_SQL: 'sql',
}

/** 必须指定字段的检查方式（含 FRESHNESS 的日期字段；与后端创建期字段白名单一致） */
const COL_TEMPLATES = ['NULL_RATE', 'UNIQUE', 'RANGE', 'REGEX', 'ENUM', 'DISCRETE', 'FRESHNESS']

/** GET /api/quality/templates 的一项（TC7-G1 内置模板清单）。 */
type RuleTemplate = {
  name: string
  ruleType: string
  columnRequired?: boolean
  columnName?: string | null
  description?: string
  config: Record<string, unknown>
}

/**
 * 十类检查方式的 config ↔ 行内参数字段 双向映射。
 * 模板导入（创建）与编辑存量规则共用一份，保证「模板 config 能过自身创建校验」
 * （QualityRuleTemplatesTest）与表单回填口径一致（CONFLICT-WM3-2 合并裁决）。
 */
const templateToFormFields = (type: string, config: Record<string, unknown>) => {
  const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : undefined)
  const pct = (v: unknown) => (num(v) === undefined ? undefined : Math.round(num(v)! * 10000) / 100)
  const fill: Record<string, unknown> = {}
  switch (type) {
    case 'ROW_COUNT':
      fill.rowCountMin = num(config.min)
      fill.rowCountMax = num(config.max)
      break
    case 'VOLATILITY':
      if (Array.isArray(config.windows)) {
        fill.volWindows = config.windows.filter((w) => typeof w === 'number')
      }
      fill.volMaxPct = pct(config.maxChangeRate)
      break
    case 'NULL_RATE': {
      const maxRate = num(config.max)
      fill.nullMaxPct = maxRate === undefined ? 0 : Math.round(maxRate * 10000) / 100
      break
    }
    case 'UNIQUE':
      fill.dupMax = num(config.max) ?? 0
      break
    case 'RANGE':
      fill.rangeMin = num(config.min)
      fill.rangeMax = num(config.max)
      break
    case 'REGEX':
      fill.regexPattern = typeof config.pattern === 'string' ? config.pattern : ''
      fill.regexMaxBad = num(config.maxBad) ?? 0
      break
    case 'ENUM':
      fill.enumAllowed = Array.isArray(config.allowed) ? config.allowed.join(',') : ''
      fill.enumMaxBad = num(config.maxBad) ?? 0
      break
    case 'DISCRETE':
      fill.discMin = num(config.min)
      fill.discMax = num(config.max)
      break
    case 'FRESHNESS':
      fill.freshMin = num(config.min) ?? 1
      break
    case 'CUSTOM_SQL':
      fill.sql = typeof config.sql === 'string' ? config.sql : ''
      fill.expect = num(config.expect) ?? 1
      break
  }
  return fill
}

/** GET /api/projects/{id}/quality/gate-usage 的一行（D46）：一条规则被哪个工作流当闸门用。 */
type GateUsage = {
  ruleCode: string
  workflowId: number
  workflowCode: string
  workflowName: string
  gateTaskCode: string
  /** 闸门不过会被一起拦住的下游任务（传递闭包，不是只有直接下游） */
  blockedTasks: string[]
}

export default function Quality() {
  const [projects, setProjects] = useState<Project[]>([])
  // 当前项目全局共享：和数据开发、数据源保持同一个项目
  const [projectId, setProjectId] = useGlobalProject()
  const [rules, setRules] = useState<QualityRule[]>([])
  const [results, setResults] = useState<QualityResult[]>([])
  const [gateUsage, setGateUsage] = useState<GateUsage[]>([])
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<QualityRule>()
  const [bizDate, setBizDate] = useState(dayjs())
  const [onlyFailed, setOnlyFailed] = useState(false)
  const [templates, setTemplates] = useState<RuleTemplate[]>([])
  const [form] = Form.useForm()
  const ruleType = Form.useWatch('ruleType', form)
  // 编辑存量规则时保留表单没覆盖到的旧配置键，保存时合回去，不做静默删键
  const configBaseRef = useRef<Record<string, unknown>>({})

  const loadProjects = async () => {
    const list = await api<Project[]>('/api/projects')
    setProjects(list)
    if (!list.some((item) => item.id === projectId) && list.length > 0) setProjectId(list[0].id)
  }

  // 切项目后旧请求可能晚到；只接受最后一次
  const loadSeq = useRef(0)
  // 异步操作完成后一律刷新「当前」项目，而不是操作发起时闭包里的旧项目
  const projectIdRef = useRef(projectId)
  projectIdRef.current = projectId
  const loadRules = async () => {
    const pid = projectIdRef.current
    const seq = ++loadSeq.current
    if (!pid) {
      setRules([])
      setResults([])
      return
    }
    setError('')
    try {
      const [r, res, gu] = await Promise.all([
        api<QualityRule[]>(`/api/projects/${pid}/quality/rules`),
        // 多取一些结果用于近 7 天趋势聚合
        api<QualityResult[]>(`/api/projects/${pid}/quality/results?limit=300`),
        // 闸门用法是旁路信息：拿不到不影响规则/结果，退化成「没在用」
        api<GateUsage[]>(`/api/projects/${pid}/quality/gate-usage`).catch(() => []),
      ])
      if (seq !== loadSeq.current) return
      setRules(r)
      setResults(res)
      setGateUsage(gu)
    } catch (e) {
      if (seq !== loadSeq.current) return
      setError(e instanceof Error ? e.message : '加载质量数据失败')
    }
  }

  useEffect(() => {
    loadProjects().catch((e) => message.error(errMsg(e)))
    // 模板清单是旁路信息：拿不到不影响手工建规则，下拉退化成空
    api<RuleTemplate[]>('/api/quality/templates').then(setTemplates).catch(() => setTemplates([]))
  }, [])

  useEffect(() => {
    // 立即清空上一个项目的规则/结果，避免切换瞬间显示并操作错项目的数据
    setRules([])
    setResults([])
    setGateUsage([])
    loadRules()
  }, [projectId])

  // 近 7 天质量趋势（按浏览器本地时区归组）
  const trend = useMemo(() => {
    const days = Array.from({ length: 7 }, (_, i) => {
      const d = dayjs().subtract(6 - i, 'day')
      return { date: d.format('YYYY-MM-DD'), label: d.format('MM-DD'), passed: 0, failed: 0 }
    })
    const index = new Map(days.map((d) => [d.date, d]))
    results.forEach((row) => {
      // 规则执行失败（数据源连不上/SQL 报错）不是数据结论，不能算进通过率
      if (row.exec_status === 'ERROR') return
      const day = index.get(row.created_at ? dayjs(row.created_at).format('YYYY-MM-DD') : '')
      if (!day) return
      if (row.passed) day.passed += 1
      else day.failed += 1
    })
    const max = Math.max(1, ...days.map((d) => d.passed + d.failed))
    const passed = days.reduce((sum, d) => sum + d.passed, 0)
    const failed = days.reduce((sum, d) => sum + d.failed, 0)
    const total = passed + failed
    const errors = results.filter((row) => row.exec_status === 'ERROR').length
    return { days, max, passed, failed, total, errors, rate: total ? Math.round((passed * 100) / total) : 0 }
  }, [results])

  const openCreate = () => {
    setEditing(undefined)
    configBaseRef.current = {}
    form.resetFields()
    form.setFieldsValue({
      ruleType: 'ROW_COUNT',
      rowCountMin: 1,
      // TC7-G2：默认弱规则（只告警），避免新建规则就把别人的下游链路停掉
      severity: 'WEAK',
      enabled: true,
    })
    setCreating(true)
  }

  // 新用户最容易卡在「编码填什么」：按表名 + 检查方式自动生成建议值，已填过不覆盖
  const suggestCode = () => {
    if (editing) return
    if (String(form.getFieldValue('code') || '').trim()) return
    const table = String(form.getFieldValue('tableName') || '').trim()
    const base = (table.split('.').pop() || 'rule').toLowerCase()
    const suffix = CODE_SUFFIX[String(form.getFieldValue('ruleType') || 'ROW_COUNT')] || 'check'
    const code = `qc_${base}_${suffix}`.replace(/[^a-z0-9_]/g, '_').slice(0, 64)
    if (/^[a-zA-Z][a-zA-Z0-9_]{1,63}$/.test(code)) form.setFieldsValue({ code })
  }

  // CONFLICT-WM3-2 合并裁决：GET /api/quality/templates（TC7-G1）在这里消费——
  // 选模板把 config 带进「行内参数」字段（仍可改），不再落手写 JSON；
  // 接口取不到时该下拉不出现，十类检查方式依然可手填创建（REQ-DW-7 判据不依赖它）。
  const applyTemplate = (name: string) => {
    const t = templates.find((x) => x.name === name)
    if (!t) return
    form.setFieldsValue({
      ...(t.columnName ? { columnName: t.columnName } : {}),
      ...templateToFormFields(t.ruleType, t.config),
    })
    suggestCode()
  }

  const openEdit = (rule: QualityRule) => {
    // 模板参数从 config_json 反填；severity 存在 config 里，拆成独立字段（TC7-G2）
    let config: Record<string, unknown>
    try {
      const parsed = JSON.parse(rule.config || '{}')
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        throw new Error('不是 JSON 对象')
      }
      config = parsed
    } catch {
      // 存量脏配置填不进模板表单；显式报错，不静默改写（D56 口径）
      message.error(`规则 ${rule.code} 的存量配置不是合法 JSON 对象，无法用表单编辑；请删除后重建`)
      return
    }
    const sevRaw = config.severity
    delete config.severity
    configBaseRef.current = { ...config }
    const fill: Record<string, unknown> = {
      code: rule.code,
      name: rule.name,
      ruleType: rule.rule_type,
      tableName: rule.table_name,
      columnName: rule.column_name,
      severity: sevRaw ? String(sevRaw).toUpperCase() : rule.severity || 'WEAK',
      enabled: rule.enabled,
      // CONFLICT-WM3-2：十类检查方式的参数回填与模板导入共用一份映射（templateToFormFields）
      ...templateToFormFields(rule.rule_type, config),
    }
    if (rule.rule_type === 'FRESHNESS') {
      // 日期字段历史上可能写在 config.dateColumn，统一回填到「日期字段」输入
      const dc = typeof config.dateColumn === 'string' && config.dateColumn
        ? config.dateColumn : rule.column_name
      fill.columnName = dc
    }
    setEditing(rule)
    // 先清掉上一条规则的校验错误与未提交字段，避免串数据
    form.resetFields()
    form.setFieldsValue(fill)
    setCreating(true)
  }

  const submit = async () => {
    const values = await form.validateFields()
    const type = String(values.ruleType)
    // REQ-DW-7：表单收敛为「检查哪张表 + 内置统计模板/自定义 SQL」。
    // 先剥掉所有模板已知键再按当前类型重拼，防止换了检查方式后旧参数残留
    // （如 NULL_RATE 的 max 漏进行数上限）；表单没覆盖的未知旧键保留（不静默删键）。
    // CONFLICT-WM3-2：四类新规则的行内参数键一并纳入剥离清单，换检查方式不串参
    const templateKeys = [
      'min', 'max', 'dateColumn', 'sql', 'sql_b64', 'expect', 'severity',
      'windows', 'maxChangeRate', 'pattern', 'maxBad', 'allowed',
    ]
    const config: Record<string, unknown> = {}
    Object.entries(configBaseRef.current).forEach(([k, v]) => {
      if (!templateKeys.includes(k)) config[k] = v
    })
    const col = typeof values.columnName === 'string' ? values.columnName.trim() : ''
    let columnName: string | undefined
    switch (type) {
      case 'ROW_COUNT':
        if (values.rowCountMin != null) config.min = values.rowCountMin
        if (values.rowCountMax != null) config.max = values.rowCountMax
        break
      case 'NULL_RATE':
        columnName = col
        config.max = (values.nullMaxPct ?? 0) / 100
        break
      case 'UNIQUE':
        columnName = col
        config.max = values.dupMax ?? 0
        break
      case 'RANGE':
        columnName = col
        if (values.rangeMin != null) config.min = values.rangeMin
        if (values.rangeMax != null) config.max = values.rangeMax
        break
      case 'VOLATILITY':
        // TC7-G1：windows=比对窗口（1..3660 天），maxChangeRate=±比例（表单按百分数填）
        config.windows = values.volWindows
        config.maxChangeRate = (values.volMaxPct ?? 0) / 100
        break
      case 'REGEX':
        columnName = col
        config.pattern = String(values.regexPattern).trim()
        config.maxBad = values.regexMaxBad ?? 0
        break
      case 'ENUM':
        columnName = col
        config.allowed = String(values.enumAllowed)
          .split(/[,，、;；\s]+/).filter(Boolean)
        config.maxBad = values.enumMaxBad ?? 0
        break
      case 'DISCRETE':
        columnName = col
        if (values.discMin != null) config.min = values.discMin
        if (values.discMax != null) config.max = values.discMax
        break
      case 'FRESHNESS':
        // 日期字段统一写 columnName（后端 evaluate 的取值口径），不再落 config.dateColumn
        columnName = col
        config.min = values.freshMin ?? 1
        break
      case 'CUSTOM_SQL':
        config.sql = String(values.sql).trim()
        config.expect = values.expect ?? 1
        break
    }
    // TC7-G2：强度存进 config_json.severity；WEAK 不落键，保持配置干净
    if (values.severity === 'STRONG') {
      config.severity = 'STRONG'
    }
    // REQ-DW-7 ①：payload 不再携带数据源字段——归属由后端 bindTarget
    // 按表名解析（判不出来 400 并给动作），执行时按规则所属项目路由（D56 口径）。
    const payload = {
      code: String(values.code).trim(),
      name: String(values.name).trim(),
      ruleType: type,
      tableName: String(values.tableName).trim(),
      columnName,
      config,
      enabled: values.enabled,
    }
    if (editing) {
      await put(`/api/quality/rules/${editing.id}`, payload)
    } else {
      await post(`/api/projects/${projectId}/quality/rules`, payload)
    }
    setCreating(false)
    setEditing(undefined)
    form.resetFields()
    message.success(editing ? '规则已保存' : '规则已创建，点规则行的「执行」可立即试跑')
    await loadRules()
  }

  // 执行需要访问数据库，几秒内可能被连点；同一规则只允许一次在跑
  const [runningRuleId, setRunningRuleId] = useState<number>()
  const run = async (rule: QualityRule) => {
    if (runningRuleId) return
    setRunningRuleId(rule.id)
    try {
      const result = await post<{ passed: boolean; detail: string; status?: string }>(
        `/api/quality/rules/${rule.id}/run?bizDate=${bizDate.format('YYYY-MM-DD')}`,
      )
      // 执行失败（数据源不可达/规则 SQL 报错）要说清楚是「规则没跑起来」，
      // 不能和「数据不达标」共用一句「未通过」。
      if (result.status === 'ERROR') {
        message.error(`${rule.code}: 规则执行失败 ${result.detail}`)
      } else {
        message[result.passed ? 'success' : 'error'](
          `${rule.code}: ${result.passed ? '通过' : '未通过'} ${result.detail}`,
        )
      }
      await loadRules()
    } catch (e) {
      message.error(e instanceof Error ? e.message : '执行失败')
    } finally {
      setRunningRuleId(undefined)
    }
  }

  return (
    <div>
      <PageHeader
        title="数据质量"
        description="规则、执行结果与 DAG 闸门"
        extra={
          <Space wrap>
            <SearchableSelect
              style={{ width: 220 }}
              value={projectId}
              onChange={setProjectId}
              options={projects.map((p) => ({ value: p.id, label: `${p.name} (${p.code})` }))}
            />
            <Space size={4}>
              {/* 这个日期只作用于「执行」，不筛选列表；不标清楚会被当成过滤条件 */}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>执行日期</Typography.Text>
              <DatePicker
                allowClear={false}
                value={bizDate}
                disabledDate={(d) => d.isAfter(dayjs(), 'day')}
                onChange={(v) => setBizDate(v || dayjs())}
                title="点「执行」时使用这个业务日期；不能选择未来日期"
              />
            </Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadRules()}>刷新</Button>
            {can('quality:write') && (
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreate} disabled={!projectId}>
                新建规则
              </Button>
            )}
          </Space>
        }
      />

      <Card
        size="small"
        title="近 7 天质量趋势"
        extra={trend.total === 0 ? (
          <span className="trend-summary">
            近 7 天暂无有效执行结果{trend.errors > 0 ? `（${trend.errors} 条规则执行失败）` : ''}
          </span>
        ) : (
          <span className="trend-summary">
            通过 <b className="trend-pass">{trend.passed}</b> · 失败{' '}
            <b className="trend-fail">{trend.failed}</b> · 通过率 <b>{trend.rate}%</b>
            {trend.errors > 0 ? <> · 执行失败 <b className="trend-fail">{trend.errors}</b></> : null}
          </span>
        )}
        style={{ marginBottom: 16 }}
      >
        {trend.total === 0 ? (
          <Typography.Text type="secondary">
            {trend.errors > 0
              ? `近 7 天有 ${trend.errors} 条规则执行失败（数据源不可达或规则 SQL 报错），这不算数据不达标，明细见下方「最近结果」。`
              : '近 7 天还没有质量执行记录。点规则行的「执行」，或等文件质量闸门运行后，这里会显示通过/失败趋势。'}
          </Typography.Text>
        ) : (
        <div className="trend">
          {trend.days.map((d) => {
            const total = d.passed + d.failed
            const scale = (n: number) => `${(n / trend.max) * 100}%`
            return (
              <div className="trend-row" key={d.date}>
                <span className="trend-label">{d.label}</span>
                <div className="trend-track">
                  <div className="trend-bar success" style={{ width: scale(d.passed) }} title={`通过 ${d.passed}`} />
                  <div className="trend-bar failed" style={{ width: scale(d.failed) }} title={`失败 ${d.failed}`} />
                </div>
                <span className="trend-count">{total} 次</span>
              </div>
            )
          })}
        </div>
        )}
      </Card>

      <Row gutter={16}>
        <Col span={24}>
          <Card title="规则" size="small">
            {error ? (
              <ErrorState message={error} onRetry={() => loadRules()} />
            ) : (
            <Table<QualityRule>
              rowKey="id"
              dataSource={rules}
              pagination={false}
              size="small"
              tableLayout="fixed"
              scroll={{ x: 1000 }}
              locale={{ emptyText: tableEmpty('还没有质量规则', '点右上角「新建规则」：填表名、选检查方式即可，Doris 数仓与平台库都支持') }}
              columns={[
                { title: '编码', dataIndex: 'code', width: 130, ellipsis: true },
                { title: '名称', dataIndex: 'name', ellipsis: true },
                {
                  title: '类型', dataIndex: 'rule_type', width: 110,
                  render: (v: string) => TYPE_LABELS[v] || v,
                },
                {
                  title: '强度', width: 80,
                  render: (_, row) => row.severity === 'STRONG'
                    ? <Tag color="volcano" title="失败时阻断下游工作流调度">强</Tag>
                    : <Tag title="失败只告警，不阻断调度">弱</Tag>,
                },
                {
                  title: '数据源', width: 180, ellipsis: true,
                  render: (_, row) => row.datasource_name
                    ? <Tag color={row.datasource_type === 'DORIS' ? 'geekblue' : 'blue'}>{row.datasource_name}</Tag>
                    : (row.datasource === 'DORIS'
                      // D56：解析出来的规则没绑连接，执行时按项目数仓路由，标签要说清这点
                      ? <Tag color="geekblue">Doris（按项目数仓）</Tag>
                      : <Tag>PostgreSQL（平台库）</Tag>),
                },
                {
                  title: '对象', width: 180, ellipsis: true,
                  render: (_, row) => `${row.table_name}${row.column_name ? '.' + row.column_name : ''}`,
                },
                {
                  // D46：规则被哪些工作流当「质量闸门」用，不达标会连哪些下游一起拦住
                  title: '用作闸门', width: 200, ellipsis: true,
                  render: (_, row) => {
                    const used = gateUsage.filter((g) => g.ruleCode === row.code)
                    if (!used.length) {
                      return (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          未用于 DAG 闸门
                        </Typography.Text>
                      )
                    }
                    return (
                      <Space direction="vertical" size={0}>
                        {used.slice(0, 2).map((g) => (
                          <Tooltip
                            key={`${g.workflowId}-${g.gateTaskCode}`}
                            title={`工作流「${g.workflowName}」的闸门任务 ${g.gateTaskCode}：`
                              + `这条规则不达标就拦掉下游 ${g.blockedTasks.length
                                ? g.blockedTasks.join('、') : '（它在末端，只拦住这次运行）'}`}
                          >
                            <Tag
                              color="volcano"
                              style={{ maxWidth: 170, overflow: 'hidden', textOverflow: 'ellipsis' }}
                            >
                              {g.workflowName}
                            </Tag>
                          </Tooltip>
                        ))}
                        {used.length > 2 && (
                          <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                            等 {used.length} 处闸门在用
                          </Typography.Text>
                        )}
                      </Space>
                    )
                  },
                },
                {
                  title: '操作',
                  width: 190,
                  render: (_, row) => (
                    <Space>
                      {can('quality:run') && (
                        <Button
                            size="small"
                            icon={<PlayCircleOutlined />}
                            loading={runningRuleId === row.id}
                            disabled={Boolean(runningRuleId) && runningRuleId !== row.id}
                            onClick={() => run(row)}
                          >
                            执行
                          </Button>
                      )}
                      {can('quality:write') && (
                        <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
                      )}
                      {can('quality:write') && (
                        <Popconfirm
                          title={`删除规则「${row.code}」？`}
                          description="会同时删除该规则的历史执行结果"
                          okText="删除"
                          okButtonProps={{ danger: true }}
                          onConfirm={async () => {
                            try {
                              await del(`/api/quality/rules/${row.id}`)
                              message.success('规则已删除')
                              await loadRules()
                            } catch (e: any) {
                              message.error(e.message || '删除失败')
                            }
                          }}
                        >
                          <Button type="text" size="small" danger icon={<DeleteOutlined />} />
                        </Popconfirm>
                      )}
                    </Space>
                  ),
                },
              ]}
            />
            )}
          </Card>
        </Col>
        <Col span={24} style={{ marginTop: 16 }}>
          <Card
            title="最近结果"
            size="small"
            extra={(
              <Space size={6}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>只看失败</Typography.Text>
                <Switch size="small" checked={onlyFailed} onChange={setOnlyFailed} />
              </Space>
            )}
          >
            <Table<QualityResult>
              rowKey="id"
              dataSource={onlyFailed ? results.filter((row) => !row.passed) : results}
              pagination={{ pageSize: 10 }}
              size="small"
              scroll={{ x: 720 }}
              expandable={{
                // 明细列会省略号截断，展开看完整原因
                expandedRowRender: (row) => (
                  <Typography.Text style={{ fontSize: 12 }}>{row.detail || '无明细'}</Typography.Text>
                ),
              }}
              locale={{
                emptyText: onlyFailed
                  ? tableEmpty('没有失败的执行结果')
                  : tableEmpty('还没有执行结果', '选择规则点「执行」，或由文件质量闸门自动写入'),
              }}
              columns={[
                { title: '规则', dataIndex: 'rule_code', width: 160 },
                { title: '业务日期', dataIndex: 'biz_date', width: 110 },
                {
                  title: '结果', dataIndex: 'passed', width: 96,
                  render: (v, row) => row.exec_status === 'ERROR'
                    ? <Tag color="orange">执行失败</Tag>
                    : v ? <Tag color="green">通过</Tag> : <Tag color="red">失败</Tag>,
                },
                { title: '明细', dataIndex: 'detail', ellipsis: true },
              ]}
            />
          </Card>
        </Col>
      </Row>

      <Modal
        title={editing ? `编辑质量规则：${editing.code}` : '新建质量规则'}
        open={creating}
        width={680}
        onOk={() => submit().catch((e) => message.error(errMsg(e)))}
        onCancel={() => {
          setCreating(false)
          setEditing(undefined)
          configBaseRef.current = {}
        }}
        okText={editing ? '保存' : '创建并启用'}
      >
        <Form form={form} layout="vertical">
          <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
            只需填「检查哪张表 + 怎么检查」：平台按表名自动定位数据库、执行时按规则所属项目路由，
            不用选数据源。
          </Typography.Paragraph>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item
                label="规则名称" name="name"
                rules={[{ required: true, message: '给规则起个看得懂的名字' }]}
              >
                <Input placeholder="例：订单表当日行数不为零" maxLength={128} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="规则编码" name="code"
                extra="字母开头；创建后不可改，工作流「质量闸门」按编码引用"
                rules={[
                  { required: true, message: '必填（填表名后会自动生成）' },
                  { pattern: /^[a-zA-Z][a-zA-Z0-9_]{1,63}$/, message: '字母开头，只能含字母/数字/下划线' },
                ]}
              >
                <Input disabled={!!editing} placeholder="例：qc_ods_orders_rowcount" onBlur={suggestCode} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            label="检查的表" name="tableName"
            rules={[
              { required: true, message: '必填' },
              { pattern: /^[A-Za-z_][A-Za-z0-9_.]*$/, message: '只能含字母/数字/下划线/点' },
            ]}
            extra="写全「库.表」；不带库名默认当前项目的数仓；平台库写 platform.表名。数据源由平台自动定位，不需要选择"
          >
            <Input placeholder="例：demo_retail.ods_orders" onBlur={suggestCode} />
          </Form.Item>
          <Form.Item
            label="怎么检查（内置统计模板，选一项填参数即可）" name="ruleType"
            rules={[{ required: true, message: '必填' }]}
          >
            <Select options={RULE_TEMPLATES} />
          </Form.Item>
          {!editing && ruleType && templates.some((t) => t.ruleType === ruleType) && (
            <Form.Item
              label="内置模板（选一个自动填参数）"
              extra="平台内置清单；不选也行，直接手填下面的参数"
            >
              <Select
                allowClear
                placeholder="可选：从内置模板导入参数与字段"
                onChange={(v) => applyTemplate(String(v ?? ''))}
                options={templates.filter((t) => t.ruleType === ruleType).map((t) => ({
                  value: t.name,
                  label: t.description ? `${t.name} — ${t.description}` : t.name,
                }))}
              />
            </Form.Item>
          )}
          {ruleType && COL_TEMPLATES.includes(ruleType) && (
            <Form.Item
              label={ruleType === 'FRESHNESS' ? '日期字段' : '检查的字段'}
              name="columnName"
              rules={[
                { required: true, message: '这种检查方式必须指定字段' },
                { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, message: '只能含字母/数字/下划线' },
              ]}
              extra={ruleType === 'FRESHNESS' ? '存放数据日期的列' : '例：order_id'}
            >
              <Input placeholder={ruleType === 'FRESHNESS' ? '例：dt、update_time' : '例：order_id'} />
            </Form.Item>
          )}
          {ruleType === 'ROW_COUNT' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item
                  label="行数下限" name="rowCountMin" dependencies={['rowCountMax']}
                  extra="填 1 就是「表不能为空」"
                  rules={[({ getFieldValue }) => ({
                    validator(_, value) {
                      if (value == null && getFieldValue('rowCountMax') == null) {
                        return Promise.reject(new Error('行数下限与上限至少填一个'))
                      }
                      return Promise.resolve()
                    },
                  })]}
                >
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} placeholder="例：1" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item label="行数上限" name="rowCountMax" extra="留空 = 不限">
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'VOLATILITY' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item
                  label="比对窗口（天）" name="volWindows"
                  rules={[{ required: true, message: '至少选一个窗口' }]}
                  extra="基线 = 本规则 N 天前最近一次执行的行数；还没有基线时不判定"
                >
                  <Select
                    mode="multiple"
                    placeholder="例：1、7、30"
                    options={[
                      { value: 1, label: '1 天前' },
                      { value: 7, label: '7 天前' },
                      { value: 30, label: '30 天前' },
                    ]}
                  />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item
                  label="变化幅度上限（±%）" name="volMaxPct" initialValue={30}
                  rules={[{ required: true, message: '必填' }]}
                  extra="填 30 = 变化超过 ±30% 不通过；任一窗口超限即不通过"
                >
                  <InputNumber min={0} step={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'NULL_RATE' && (
            <Form.Item
              label="空值率上限（%）" name="nullMaxPct" initialValue={0}
              extra="0 = 该字段不允许空值；填 5 表示空值的行最多占 5%"
            >
              <InputNumber min={0} max={100} step={0.1} style={{ width: 160 }} />
            </Form.Item>
          )}
          {ruleType === 'UNIQUE' && (
            <Form.Item
              label="允许重复的行数" name="dupMax" initialValue={0}
              extra="0 = 必须完全唯一；空值不算重复"
            >
              <InputNumber min={0} precision={0} style={{ width: 160 }} />
            </Form.Item>
          )}
          {ruleType === 'RANGE' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item
                  label="最小值（含）" name="rangeMin" dependencies={['rangeMax']}
                  extra="越界的行只要存在 1 条就不通过"
                  rules={[({ getFieldValue }) => ({
                    validator(_, value) {
                      if (value == null && getFieldValue('rangeMax') == null) {
                        return Promise.reject(new Error('最小值与最大值至少填一个'))
                      }
                      return Promise.resolve()
                    },
                  })]}
                >
                  <InputNumber style={{ width: '100%' }} placeholder="例：0" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item label="最大值（含）" name="rangeMax" extra="留空 = 不限">
                  <InputNumber style={{ width: '100%' }} placeholder="例：9999" />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'REGEX' && (
            <Row gutter={12}>
              <Col span={14}>
                <Form.Item
                  label="正则表达式" name="regexPattern"
                  rules={[{ required: true, message: '正则不能为空' }]}
                  extra="空值不计入；「内置模板」里有手机号/邮箱/身份证示例"
                >
                  <Input placeholder="例：^1[3-9]\d{9}$（中国大陆手机号）" />
                </Form.Item>
              </Col>
              <Col span={10}>
                <Form.Item label="允许不匹配的行数" name="regexMaxBad" initialValue={0} extra="0 = 零容忍">
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'ENUM' && (
            <Row gutter={12}>
              <Col span={14}>
                <Form.Item
                  label="枚举白名单" name="enumAllowed"
                  rules={[{ required: true, message: '至少填一个合法值' }]}
                  extra="多个值用逗号分隔；值不在名单内（空值除外）的行算不匹配"
                >
                  <Input placeholder="例：PAID,SHIPPED,CANCELED" />
                </Form.Item>
              </Col>
              <Col span={10}>
                <Form.Item label="允许不匹配的行数" name="enumMaxBad" initialValue={0} extra="0 = 零容忍">
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'DISCRETE' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item
                  label="去重值个数下限（含）" name="discMin" dependencies={['discMax']}
                  extra="字段种类突然变多/变少都能兜住"
                  rules={[({ getFieldValue }) => ({
                    validator(_, value) {
                      if (value == null && getFieldValue('discMax') == null) {
                        return Promise.reject(new Error('下限与上限至少填一个'))
                      }
                      return Promise.resolve()
                    },
                  })]}
                >
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} placeholder="例：1" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item label="去重值个数上限（含）" name="discMax" extra="留空 = 不限">
                  <InputNumber min={0} precision={0} style={{ width: '100%' }} placeholder="例：100" />
                </Form.Item>
              </Col>
            </Row>
          )}
          {ruleType === 'FRESHNESS' && (
            <Form.Item
              label="当日数据至少多少行" name="freshMin" initialValue={1}
              extra="「日期字段 = 执行日期」的行数达到这个数，才算数据按时到达"
            >
              <InputNumber min={1} precision={0} style={{ width: 160 }} />
            </Form.Item>
          )}
          {ruleType === 'CUSTOM_SQL' && (
            <>
              <Form.Item
                label="统计 SQL（返回一个数字）" name="sql"
                rules={[{ required: true, message: 'SQL 不能为空' }]}
                extra="只允许一条只读查询；写 ${biz_date} 会替换成执行日期"
              >
                <Input.TextArea
                  rows={4}
                  placeholder={"select count(*) from demo_retail.ods_orders where dt = '${biz_date}'"}
                />
              </Form.Item>
              <Form.Item label="期望结果" name="expect" initialValue={1} extra="查询结果等于期望值才算通过">
                <InputNumber style={{ width: 160 }} />
              </Form.Item>
            </>
          )}
          <Form.Item
            label="不达标时怎么办" name="severity" initialValue="WEAK"
            extra="强规则失败会拦读取这张表的下游工作流；拿不准就选弱规则"
          >
            <Select
              options={[
                { value: 'WEAK', label: '弱规则：只告警，不影响调度（默认）' },
                { value: 'STRONG', label: '强规则：阻断下游调度' },
              ]}
            />
          </Form.Item>
          <Form.Item label="创建后立即启用" name="enabled" valuePropName="checked" initialValue={true}>
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
