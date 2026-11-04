import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Alert, Button, Collapse, Space, Table, Tag, Typography } from 'antd'
import PageHeader from '../components/PageHeader'
import { HELP_SECTIONS, HELP_SECTION_TITLE, pendingHelpPages } from '../helpAnchors'
import { tableEmpty } from '../components/StateBlocks'

type ErrorRow = { symptom: string; cause: string; next: string }
type EngineRow = { engine: string; scene: string; avoid: string; entry: string }
type GlossaryRow = { term: string; definition: string; where: string }

/**
 * 概念小词典（REQ-UX-HELP-1，审计 §三7/8/10）：全站反复出现但从未定义的概念。
 * 恰好六词条；验收脚本按 GLOSSARY 断言条目数与术语名。文案是「解释」不是「新功能」，
 * 术语清扫（统一叫法）由术语卡负责，本词典按现状口径说明。
 */
const GLOSSARY: GlossaryRow[] = [
  {
    term: '租户',
    definition: '平台最外层的隔离边界：项目、文件、数据源、告警、审计都归属某个租户。你能看到和操作的，永远只限当前租户内的东西，别的租户互相不可见。',
    where: '属于多个租户时，页面右上角会出现租户标签，点一下即可切换；建租户、邀请成员、删租户只有平台管理员能做。',
  },
  {
    term: '权限点',
    definition: '平台最小的权限单位，一个权限点对应一种动作，例如 query:run（能否执行查询）、table:ddl（能否建改删表）、ops:run（能否运维/重跑）、alert:ack（能否确认告警）、change:approve（能否审批 SQL 变更）。权限点按「角色」打包发人：内置角色为平台管理员 / 数据开发 / 运维 / 只读访客，自定义角色在管理中心「角色与权限点」勾选。',
    where: '菜单、按钮、页面按你的权限点自动裁剪。按钮看不到、页面打不开，多半是缺对应权限点——先问管理员在角色页核对，不要以为是功能坏了。',
  },
  {
    term: '工作流',
    definition: '在「数据开发」创建的、可调度的任务：一个或多个 SQL / 脚本节点加依赖关系，构成一条工作流。界面上常用「文件」「任务」指同一件东西：列表页叫「文件」，运维页面列名也多写「文件」。目前叫法尚未完全统一，记住：文件 = 工作流 = 任务，指的是同一个可调度对象。',
    where: '发布后按调度执行，每次执行生成一个「运行实例」，到「运维 → 运行实例」查看；血缘、基线、告警都按这条链路挂起来。',
  },
  {
    term: 'DEV 与 PROD（开发 / 生产环境）',
    definition: 'DEV 是日常开发验证的环境，PROD 是正式对外的环境。每个项目有一对数仓库：开发库与生产库（库名以 _dev / _prd 结尾）；数据源本身也按 DEV / PROD 两个环境登记。日常写数、试跑都在 DEV；_prd 库按生产对待：查询页只读，结构变更必须走审批。',
    where: '选数据源、补数弹窗选环境、版本发布状态都会碰到这两个标签。注意：补数默认 DEV，要补生产数据必须显式切到 PROD，补完记得核对目标库名。',
  },
  {
    term: '项目',
    definition: '组织工作的基本单位：文件、数据源、质量规则、数据服务、看板都挂在某个项目下。在管理中心新建项目时，平台会同步为它创建一对数仓库（开发库 + 生产库），库名按部门前缀自动生成。',
    where: '多数页面左上角的「项目选择」就是切上下文：选哪个项目，看到的列表、查的库、建的表就是哪个项目的。跨项目数据默认互相隔离。',
  },
  {
    term: '发布与审批',
    definition: '改完文件保存只是草稿，要让调度真正执行必须「发布」。版本状态流转：未发布 → 待审批 → 发布中 → 已发布；被新版替换标「已替换」，也可「已下线」后再发布。数据服务与 BI 看板另有一套：必须先在 DEV 验证跑通，线上跑的是发布快照，发布后的编辑不影响线上，直到重新发布。采集（同步）任务要试运行成功才能发布，发布后冻结。',
    where: '发布、下线、回滚、审批全部留痕，可在「安全中心 → 审计日志」查动作与结果；审批按钮只在有审批权限点的人手里出现。',
  },
]

const ERROR_ROWS: ErrorRow[] = [
  {
    symptom: '无权访问库 / 无权访问查询涉及的数据表（403）',
    cause: '当前账号没有这个库或表的读权限。',
    next: '到「权限申请」提交库/表 READ 授权；需要明文时按列申请，不要直接申请整库写权限。',
  },
  {
    symptom: '表 / 库不存在（Unknown table / does not exist）',
    cause: '名称拼错、对象已删除，或湖表没有写全 catalog.db.table。',
    next: '到「表管理」刷新后确认名称；湖表按 catalog.db.table 三段式书写。',
  },
  {
    symptom: '涉及 N 个仅导入数据源，无法用 SQL 查询',
    cause: '命中了 Kafka topic / 文件类数据源 / 文件集，它们没有 SQL 执行链路，只能导入。',
    next: '先同步入湖/入仓再查：到「数据集成」写入 Iceberg 湖或 Doris；Kafka 实时数据到「流开发」用 FlinkSQL 落 Doris，再回「数据查询」。',
  },
  {
    symptom: '查询超时（服务端 60 秒上限）',
    cause: '扫描范围太大、没有走分区/索引，或引擎当前负载高。',
    next: '缩小时间范围、补 where 条件、降低 limit；持续超时到「运维 → 系统监控」查看引擎状态。',
  },
  {
    symptom: '无法连接服务器 / 网络错误',
    cause: '请求没到后端，或后端到引擎的连接中断。',
    next: '先重试；持续失败看「运维 → 系统监控」的服务状态，或联系管理员。',
  },
  {
    symptom: '元数据降级（Gravitino 不可用）',
    cause: '统一元数据服务暂不可用，平台自动回退到直连 Doris。',
    next: '降级期间库表/字段仍可浏览，结果可能与统一元数据有短暂差异；稍后刷新，持续存在到「数据源」检查连接。',
  },
  {
    symptom: 'SQL 无法安全解析',
    cause: 'SQL 里用了子查询/动态拼接，分级网关无法确定真实表名。',
    next: '改写成显式表名（from/join 后直接写库.表），或把中间结果先落表再查。',
  },
  {
    symptom: '函数不存在 / 未发布',
    cause: 'UDF 没有发布，或函数属于别的项目。',
    next: '到「函数管理」确认已启用并发布；跨项目函数不可直接调用。',
  },
]

const ENGINE_ROWS: EngineRow[] = [
  {
    engine: 'Doris',
    scene: '交互式只读查询、明细/聚合、Iceberg 湖表查询（catalog.db.table）；数据开发里的 SQL 节点也走它。',
    avoid: '不适合做 Kafka 实时消费、CDC 同步和复杂的多步骤批处理编排。',
    entry: '数据查询 / 表管理 / 数据开发',
  },
  {
    engine: 'Flink',
    scene: 'Kafka 实时流、CDC、窗口聚合、实时结果写 Doris；流开发页的 FlinkSQL 会话集群。',
    avoid: '一次性大批量补数用 BATCH 模式可以跑，但资源开销大，优先用 Spark/离线任务。',
    entry: '流开发',
  },
  {
    engine: 'Spark',
    scene: '离线大批量批处理/ETL（Spark Thrift Server，脚本引擎选 SPARK）；适合重计算和一次性补数。',
    avoid: '不适合低延迟交互查询；小结果集临时排查优先用 Doris。',
    entry: '数据开发（脚本节点，执行引擎 SPARK）',
  },
]

function SectionList({ items }: { items: string[] }) {
  return (
    <ul style={{ margin: '4px 0 0', paddingLeft: 20, fontSize: 13, lineHeight: 1.9 }}>
      {items.map((item) => <li key={item}>{item}</li>)}
    </ul>
  )
}

/** 使用帮助：首次使用路径、查询页、表管理、常见错误、引擎选择。 */
export default function Help() {
  const { hash } = useLocation()
  const nav = useNavigate()
  // 深链锚点：URL 形如 /help#query 时定位到对应章节（右上角 ? 图标据此直达）
  const target = hash.startsWith('#') ? hash.slice(1) : ''
  const [activeKey, setActiveKey] = useState<string[]>(
    () => (HELP_SECTION_TITLE[target] ? [target] : ['glossary', 'start']),
  )
  // 锚点变化：展开对应章节，等面板渲染出来后再滚动过去
  useEffect(() => {
    if (!HELP_SECTION_TITLE[target]) return
    setActiveKey((prev) => (prev.includes(target) ? prev : [...prev, target]))
    const timer = window.setTimeout(() => {
      document.getElementById(`help-${target}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 80)
    return () => window.clearTimeout(timer)
  }, [target])
  const jump = (key: string) => nav(`/help#${key}`)
  const pending = pendingHelpPages()

  return (
    <div>
      <PageHeader
        title="使用帮助"
        description="从建项目到查数的最短路径，以及怎么查数据、查询页、表管理和常见错误的处理方式。页面内容按当前平台版本整理，不代替权限申请和审批流程。"
      />

      {/* 锚点目录：点任一章节直达并展开；URL 带 #key，便于各页右上角 ? 图标上下文直达 */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, margin: '12px 0' }}>
        {HELP_SECTIONS.map((s) => (
          <Button key={s.key} size="small" onClick={() => jump(s.key)}>
            {s.title}
          </Button>
        ))}
      </div>

      <Collapse
        activeKey={activeKey}
        onChange={(k) => setActiveKey(Array.isArray(k) ? (k as string[]) : [k as string])}
        items={[
          {
            key: 'glossary',
            label: '概念小词典（租户 / 权限点 / 项目 / 工作流 / 环境与发布）',
            children: (
              <>
                <span id="help-glossary" />
                <Table<GlossaryRow>
                  size="small"
                  rowKey="term"
                  pagination={false}
                  dataSource={GLOSSARY}
                  locale={{ emptyText: tableEmpty('没有词条', '内容内置，正常不会为空') }}
                  columns={[
                    { title: '概念', dataIndex: 'term', key: 'term', width: 170 },
                    {
                      title: '是什么',
                      dataIndex: 'definition',
                      key: 'definition',
                      render: (value: string) => <Typography.Text style={{ fontSize: 12 }}>{value}</Typography.Text>,
                    },
                    {
                      title: '在哪会碰到',
                      dataIndex: 'where',
                      key: 'where',
                      width: 330,
                      render: (value: string) => (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{value}</Typography.Text>
                      ),
                    },
                  ]}
                />
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
                  这六个概念是全站最常碰到、又最少被解释的。其余名词查不到解释时，点该页面右上角的 ? 看本页专属章节。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: 'start',
            label: '首次使用：从空环境到第一条查询',
            children: (
              <>
                <span id="help-start" />
                <Typography.Paragraph style={{ marginBottom: 4 }}>
                  按下面顺序走完一次，环境就具备可查询的数据了：
                </Typography.Paragraph>
                <ol style={{ paddingLeft: 20, fontSize: 13, lineHeight: 1.9 }}>
                  <li>
                    <Typography.Text strong>建项目</Typography.Text>：右上角项目/管理中心新建项目。
                    项目会对应一个 Doris 库；跨项目的数据默认互相隔离。
                  </li>
                  <li>
                    <Typography.Text strong>配数据源</Typography.Text>：到「数据源」登记 Doris、MySQL、
                    Kafka 或 Iceberg 湖。外部 Doris 与内置 Doris 的查询入口相同，但执行位置不同。
                  </li>
                  <li>
                    <Typography.Text strong>准备表</Typography.Text>：到「表管理」新建库/建表；
                    需要加工逻辑就到「数据开发」建工作流和 SQL 节点，实时链路到「流开发」。
                  </li>
                  <li>
                    <Typography.Text strong>查询</Typography.Text>：到「数据查询」选择执行位置，
                    写 <Typography.Text code>select * from 库名.表名 limit 100</Typography.Text>，
                    按 Ctrl+Enter 运行。没有业务表时可用 <Typography.Text code>SELECT 1 AS ok</Typography.Text> 验证连通。
                  </li>
                </ol>
                <Alert
                  type="info"
                  showIcon
                  style={{ marginTop: 12 }}
                  message="执行位置怎么选"
                  description="不选 = 平台内置 Doris；选某个项目 = 该项目绑定的 Doris。查 Iceberg 湖表用 catalog.db.table，catalog 在「数据源 → Iceberg 湖」维护。"
                />
              </>
            ),
          },
          {
            key: 'query',
            label: '查询页怎么用',
            children: (
              <>
                <span id="help-query" />
                <SectionList items={[
                  '只读：只接受 SELECT / WITH，DDL、DML 会被后端拒绝。',
                  '结果上限：前端每次最多取 200 行；后端硬上限 1000 行。达到上限会提示「结果已截断」。',
                  '执行位置：页面顶部下拉选择项目；不选时用平台内置 Doris。外部项目库跟随项目路由。',
                  '补全：输入库名./表名. 补字段；Ctrl+Space 手动唤出表、字段、关键字和函数清单。',
                  '结果操作：「复制」是制表符分隔全部结果，可直接粘贴 Excel；「导出 CSV」导出全部结果，不是当前分页。',
                  '脱敏：敏感列输出 ***（MASK）或稳定令牌（TOKEN）。令牌可做 group by / join / count distinct，但无法还原明文；需要明文到「权限申请」提交列授权。',
                  '生产库：库名以 _prd 结尾时按生产环境处理。查询页只读，不会修改数据；改结构请走「表管理」并填写生产变更原因。',
                  '超时：服务端单次查询 60 秒上限，缩小范围或加过滤条件后重试。',
                ]}
                />
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
                  从表详情跳转：表管理 → 某张表 →「结构」→「如何查询」，可复制 SQL 或直接带到本页。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: 'query-data',
            label: '怎么查数据（去哪查 / 能查什么 / 权限）',
            children: (
              <>
                <span id="help-query-data" />
                <Typography.Title level={5} style={{ fontSize: 13, margin: '0 0 4px' }}>去哪查</Typography.Title>
                <SectionList items={[
                  '主入口：「数据查询」（左侧菜单，页面 /query）。先选执行位置，再写 SQL，按 Ctrl+Enter 运行。',
                  '从表进入：「表管理 → 某张表 → 结构 → 如何查询」，可复制示例 SQL，或点「到查询页」直接带过去。',
                  '从数据地图进入：「数据资产 → 资产目录」，选中表后详情里同样有「如何查询」，可复制或跳转。',
                  '定时加工：到「数据开发」建 SQL 节点（Doris）或 SPARK 脚本节点；实时链路到「流开发」写 FlinkSQL。',
                ]} />
                <Typography.Title level={5} style={{ fontSize: 13, margin: '12px 0 4px' }}>能查什么</Typography.Title>
                <SectionList items={[
                  'Doris 内表：平台内置 Doris 或项目绑定的外部 Doris；SQL 里直接写 库名.表名。',
                  'Iceberg 湖表：按 catalog.db.table 三段式查询；catalog 在「数据源 → Iceberg 湖」维护。',
                  'Spark：用于离线大批量批处理 / ETL，入口是「数据开发」的 SPARK 脚本节点，不是交互式查询页。',
                  '外部 JDBC 源（MySQL / PostgreSQL / ClickHouse 等）：先同步进 Doris 再查，查询实际由 Doris 执行。',
                ]} />
                <Typography.Title level={5} style={{ fontSize: 13, margin: '12px 0 4px' }}>不能查什么</Typography.Title>
                <SectionList items={[
                  'Kafka topic：没有 SQL 执行链路，不能直接 select；用「流开发」FlinkSQL 消费并落 Doris 后再查。',
                  '文件类数据源 / 文件集（Fileset、S3、本地文件等）：只能导入，不能直接 SQL 查询；先同步入湖/入仓再查。',
                  '查询页只读：只接受 SELECT / WITH；DDL、DML（建表、写入、删除）会被后端拒绝，请走「表管理」「数据集成」「数据开发」。',
                  '生产库：库名以 _prd 结尾按生产环境只读处理，改结构必须走生产变更流程并填写原因。',
                  '未纳管、无 READ 授权的库 / 表：会返回 403，先在「权限申请」提交授权。',
                ]} />
                <Typography.Title level={5} style={{ fontSize: 13, margin: '12px 0 4px' }}>权限怎么申请</Typography.Title>
                <SectionList items={[
                  'query:run 是「能否打开查询页并执行」的入口权限；没有它时执行按钮不可用，复制 SQL 不受影响。',
                  '具体库 / 表还需要 READ 授权：到「数据治理 → 权限申请」（/access）→ 选择库或表 →「申请读取」→ 填写理由 → 提交。',
                  '审批：资源负责人或租户管理员通过后生效；同一资源、同一权限在审批中时不能重复提交。',
                  '敏感列默认脱敏（MASK / TOKEN）；需要明文时按列申请，不要申请整库写权限。',
                  '查询是只读操作，不需要 WRITE 权限；写权限只影响数据集成 / 数据开发等写入链路。',
                ]} />
                <Typography.Title level={5} style={{ fontSize: 13, margin: '12px 0 4px' }}>常见错误</Typography.Title>
                <SectionList items={[
                  '「涉及 N 个仅导入数据源，无法用 SQL 查询」→ 先同步入湖/入仓再查，不要在 Kafka / 文件类数据源上直接 select。',
                  '「无权访问库 / 无权访问查询涉及的数据表（403）」→ 到「权限申请」提交库 / 表 READ 授权，通过后刷新再执行。',
                  '「查询超时」→ 服务端单次 60 秒上限；缩小时间范围、补 where 条件、降低 limit 后重试。',
                  '「结果已截断」→ 前端每次最多 200 行、后端硬上限 1000 行；需要全量请导出 CSV 或先聚合。',
                  '「表 / 库不存在」→ 到「表管理」刷新确认名称；湖表记得写全 catalog.db.table。',
                ]} />
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
                  完整对照见上方「常见错误对照表」；引擎差异见下方「引擎怎么选」。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: 'tables',
            label: '表管理怎么用',
            children: (
              <>
                <span id="help-tables" />
                <SectionList items={[
                  '两种口径：右上角切换「Doris 内表」和「Iceberg 湖表」。内表按项目连接的 Doris 浏览；湖表按 catalog.db.table 浏览。',
                  '表详情：点「结构」看字段、主键、可空、注释和建表 DDL；「预览 20 行」只读采样，不执行写操作。',
                  '建表：表单模式适合常规 OLAP 表（模型、分区、分桶有默认值）；SQL 模式适合索引、特殊属性等表单覆盖不到的 DDL。',
                  '权限：建库/建表/改表/删表需要 table:ddl；没有权限时按钮不显示，服务端还会再校验一次。',
                  '生产库：_prd 库的变更必须填写原因。清空/删除/重命名都会二次确认，删除不可恢复。',
                  '元数据降级：Gravitino 不可用时自动回退直连 Doris，页面顶部会提示；浏览和查询仍可用。',
                  '如何查询：表详情里的「如何查询」给出可直接复制的 SELECT（含 where 示例），并能跳到查询页。',
                ]}
                />
              </>
            ),
          },
          {
            key: 'errors',
            label: `常见错误对照表（${ERROR_ROWS.length}）`,
            children: (
              <>
                <span id="help-errors" />
                <Table<ErrorRow>
                size="small"
                rowKey="symptom"
                pagination={false}
                dataSource={ERROR_ROWS}
                locale={{ emptyText: tableEmpty('没有内置错误条目', '内容内置，正常不会为空') }}
                columns={[
                  { title: '你看到的', dataIndex: 'symptom', key: 'symptom', width: 280 },
                  { title: '原因', dataIndex: 'cause', key: 'cause' },
                  {
                    title: '下一步',
                    dataIndex: 'next',
                    key: 'next',
                    width: 360,
                    render: (value: string) => (
                      <Typography.Text style={{ fontSize: 12 }}>{value}</Typography.Text>
                    ),
                  },
                ]}
              />
              </>
            ),
          },
          {
            key: 'engines',
            label: '引擎怎么选（Doris / Flink / Spark）',
            children: (
              <>
                <span id="help-engines" />
                <Table<EngineRow>
                  size="small"
                  rowKey="engine"
                  pagination={false}
                  dataSource={ENGINE_ROWS}
                locale={{ emptyText: tableEmpty('没有内置引擎对比', '内容内置，正常不会为空') }}
                  columns={[
                    {
                      title: '引擎',
                      dataIndex: 'engine',
                      key: 'engine',
                      width: 110,
                      render: (value: string) => (
                        <Space size={6}>
                          <Tag color={value === 'Doris' ? 'blue' : value === 'Flink' ? 'green' : 'orange'}>
                            {value}
                          </Tag>
                        </Space>
                      ),
                    },
                    { title: '适合', dataIndex: 'scene', key: 'scene' },
                    {
                      title: '不适合',
                      dataIndex: 'avoid',
                      key: 'avoid',
                      width: 300,
                      render: (value: string) => (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{value}</Typography.Text>
                      ),
                    },
                    {
                      title: '入口',
                      dataIndex: 'entry',
                      key: 'entry',
                      width: 210,
                      render: (value: string) => (
                        <Typography.Text style={{ fontSize: 12 }}>{value}</Typography.Text>
                      ),
                    },
                  ]}
                />
                <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 12, marginBottom: 0 }}>
                  一句话：临时看数选 Doris；实时链路选 Flink；一次性大批量批处理选 Spark。
                </Typography.Paragraph>
              </>
            ),
          },
          {
            key: 'quality',
            label: '数据质量：规则、强度与闸门',
            children: (
              <>
                <span id="help-quality" />
                <SectionList items={[
                  '这页干什么：给表定检查规则、手动或按计划执行、看近 7 天趋势与最近结果；先选项目，再建规则。',
                  '十种检查方式：表行数、行数波动率、空值率、唯一性、数值范围、格式（正则）、枚举白名单、离散值个数、新鲜度、自定义 SQL；除行数和波动率外，多数类型必须指定字段。',
                  '规则强度是关键：弱规则失败只告警、不拦调度；强规则被工作流当质量闸门用时，失败会阻断该工作流及其下游。「用作闸门」列显示这条规则拦着谁。',
                  '结果三种：通过 / 失败 / 执行失败。「执行失败」指规则本身跑不起来（数据源不可达、SQL 报错），不算数据不达标，也不计入通过率。',
                  '不用选数据源：表按 库.表 定位；不带库名默认当前项目的数仓；波动率的基线是本规则 N 天前最近一次执行，没有基线不判定。',
                  '自定义 SQL 只允许一条只读查询，${biz_date} 会替换成执行日期；执行日期只影响这一次执行，不能选未来，也不会筛选列表。',
                  '坑：删除规则会连同历史执行结果一起删；编码创建后不可改（工作流闸门按编码引用）；同一规则同时只允许跑一次。',
                  '关系：闸门在工作流上配置（数据开发页）；质量不达标的告警在「运维 → 告警中心」查看处理。',
                ]} />
              </>
            ),
          },
          {
            key: 'service',
            label: '数据服务：把 SQL 发布成 API',
            children: (
              <>
                <span id="help-service" />
                <SectionList items={[
                  '这页干什么：把一条只读 SQL 发布成带 Token 鉴权的 HTTP 接口，供其他系统调用；SQL 里用 :参数名 引用参数，参数有类型（文本/整数/小数/布尔/日期/时间）。',
                  '发布必须先「DEV 跑通」：用已保存的开发态 SQL 真实执行一次，通过后才能发布；改了任何内容都要重新测再发布。',
                  '改已发布的服务不影响线上：线上跑的是发布快照（vN），编辑保存只是开发态；发布成功后线上才换新版本。',
                  '怎么调用：地址是 平台域名/api/ds/服务编码，鉴权头 X-API-Token 或 Bearer；最多返回 200 行；未发布或已下线的调用返回 403。',
                  'Token 默认打码，点「显示」才可见；「重置 Token」后旧 Token 立即失效，新 Token 当场展开供复制，记得同步给调用方。',
                  '版本与回滚：每次发布留一个版本，当前线上标「线上」；回滚是用旧快照生成新版本，历史不被覆盖。',
                  '坑：日期列比较要选日期/时间类型参数，否则不生效；下线后再发布要重新走 DEV 验证。',
                  '关系：每次调用都记入「安全中心」审计；数据源跟随项目，发布/下线权限点分别是 service:publish / service:write。',
                ]} />
              </>
            ),
          },
          {
            key: 'bi',
            label: '数据可视化：看板与发布快照',
            children: (
              <>
                <span id="help-bi" />
                <SectionList items={[
                  '这页干什么：用只读 SQL 搭看板（表格 / 折线 / 柱状 / 饼图 / 指标卡），流程和数据服务同款：开发态调试 → DEV 跑通 → 发布到线上；线上是发布快照。',
                  '看板参数：图表 SQL 里写 :参数名；「DEV 跑通」按默认值执行全部图表，查看时可覆盖参数重新查询，空值参数不传。',
                  '发布前置：必须验证跑通（或内容与线上完全一致）；发布时内容没变会提示「线上仍为 vN」；点表格行直接打开看板——已发布看线上版，未发布看开发态。',
                  '每个图表各自选数据源（来自看板所属项目的数据源）；编辑时项目不可改。',
                  '坑：至少要有 1 个图表才能保存；X/Y/分组列必须真实存在于查询结果列，否则会退回第一列；空值显示 NULL，折线缺值断线不补 0；行数超上限会标「已截断」。',
                  '下线与回滚：下线后线上不再展示，修改后重新发布可恢复；回滚生成新版本并把看板恢复成那一版。',
                  '关系：查看抽屉里的「线上版本/开发态」切换仅 bi:write 权限可见；看板数据不出平台，不走数据服务的对外 Token。',
                ]} />
              </>
            ),
          },
          {
            key: 'functions',
            label: '函数管理：自定义函数注册与使用',
            children: (
              <>
                <span id="help-functions" />
                <SectionList items={[
                  '这页管两类东西：资源文件（jar / py / zip / whl / txt，单文件上限 50MB）和函数（状态：草稿 → 已发布 → 已停用）。',
                  '最短路径：上传资源 → 启用某个版本 → 注册函数（选资源与版本、填类名）→ 发布。每个资源同一时刻只有一个「启用中」版本；发布要求所用资源有启用版本，否则按钮禁用。',
                  '引擎分工：Doris 只支持 Java 函数，选了 Python 资源引擎会自动锁到 Flink。Doris 函数发布后可在查询页和工作流 SQL 里直接调用；Flink 函数在流开发作业提交时自动注入，不用手动 ADD。',
                  '调用名由系统生成（项目前缀_短名、小写下划线），不能随意起名。',
                  '权限：上传/注册/编辑/删除要 udf:write；启用/停用版本、发布/停用函数要 udf:publish。',
                  '坑：停用启用中的版本后，用它的函数再注册/发布会失败；停用或删除已发布函数会同步清理 Doris 侧；删除非空文件夹是级联操作；版本记录删除只删注册信息、文件保留用于审计。',
                  '关系：资源按项目隔离，列表跟随左上角项目选择；哪些函数引用某资源，可在资源的引用查看里看。',
                ]} />
              </>
            ),
          },
          {
            key: 'security',
            label: '安全中心：审计、会话与数据分级',
            children: (
              <>
                <span id="help-security" />
                <SectionList items={[
                  '标签页按权限裁剪：审计日志、在线会话、数据分级、安全状况。只读排查用审计和会话（security:read / audit:read），分级脱敏要 data:classify / mask:manage。',
                  '审计日志：登录、发布、回滚、服务调用、会话吊销、脱敏命中等都留痕，动作和结果（成功/失败）都有中文标签；顶部统计卡点一下即联动筛选；单次最多返回 200 条，更久的按时间范围翻页查。',
                  '在线会话：显示每个人的有效/已过期会话；「吊销」后该会话立即失效（需二次确认）；页面只展示会话元数据，从不展示 Token 本身。',
                  '数据分级：字段分 L1 公开 / L2 内部 / L3 敏感 / L4 机密；表级等级由列里最高级自动推导，不用手工设。脱敏方式有遮蔽（***）、令牌（TOKEN，可分组可关联但不可还原）等；L3/L4 改级会二次确认并说明谁能看明文。',
                  '查询网关三种模式：OFF（不拦截）、LOG（只记录）、ENFORCE（强制脱敏）。关闭开关这类高危切换仅限平台管理员，且要手输 CONFIRM 确认，操作本身写入审计。',
                  '关系：查询页看到列值是 *** 或 TOKEN 时，规则在本页的数据分级里定义；要明文就按列到「权限申请」提授权，不要整库申请。',
                ]} />
              </>
            ),
          },
          {
            key: 'changes',
            label: 'SQL 变更：审批与执行',
            children: (
              <>
                <span id="help-changes" />
                <SectionList items={[
                  '这页是破坏性 SQL（DROP、TRUNCATE 等）的审批与执行台账。入口不在本页：在查询页/编辑器执行命中审批规则的 SQL 时，弹窗里提交变更单，之后回到本页跟踪。',
                  '状态流转：待审批 → 执行中（审批通过后立即执行）→ 已完成 / 执行失败；待审批阶段可被驳回，提交人也可撤回。列表按状态分 Tab，页签上的红点表示有待审批单。',
                  '审批人要看清再点：通过 = 立即执行、不能撤销；不可逆的语句必须勾选「已知不可逆」才能通过（后端还会再校验）；不能审批自己提交的单。',
                  '保护措施：单表的 DROP / TRUNCATE 执行前自动备份，备份失败则不执行；详情页的回滚 SQL 里备份表名是执行后才回填的。',
                  '回滚不是自动的：发起回滚会生成一张新变更单，重新走审批和执行。',
                  '和表管理的区别：表管理页的改结构/重命名/清空走另一套变更审批（提示「变更单 #N」）；但删除生产库（_prd）的表会被直接拒绝，页面会引导你到这里建 DROP TABLE 单——有备份、有审批、可回滚。',
                  '权限：提交要 change:submit，审批要 change:approve；单据离开可见范围后详情页会提示不存在或无权查看。',
                ]} />
              </>
            ),
          },
          {
            key: 'ops',
            label: '运维总览：一天看完运行情况',
            children: (
              <>
                <span id="help-ops" />
                <SectionList items={[
                  '这页把运行实例、告警、系统状态放在一屏：四张入口卡 + 六张统计卡（数字点击即带筛选跳转），核心统计每 10 秒自动刷新。',
                  '批量补数：选项目 → 文件 → 环境（DEV/PROD，默认 DEV，补生产要显式切换）→ 日期区间 → 并行度（1–4）；提交后到数据开发该文件的「补数据」看进度。需要 ops:run 权限。',
                  '近 7 天趋势与「最近失败」表基于最近 500 条实例聚合；点失败行直接跳到该实例详情。',
                  '生命周期区（平台/项目负责人可见）：到期分区不会自动删除——要在「待确认清单」勾选并确认删除，再执行一轮才移入回收；回收保留 30 天，可一键恢复。没有确认就永远不会动你的数据。',
                  '坑：执行记录按表名/时间的筛选只作用于已取的最近 500 条；扫描任务每天固定时间跑一轮，刚发布的记录为空是正常的。',
                  '关系：重试、停止、日志在「运行实例」页做；告警处理在「告警中心」做；本页只是总览与入口。',
                ]} />
              </>
            ),
          },
          {
            key: 'instances',
            label: '运行实例：状态、日志与重跑',
            children: (
              <>
                <span id="help-instances" />
                <SectionList items={[
                  '这页是所有工作流运行实例的列表（每 10 秒自动刷新）：状态有排队中 / 运行中 / 成功 / 失败 / 已取消 / 上游失败 / 已跳过。',
                  '筛选全部下发后端并可回写 URL：状态、项目、文件、关键字（文件 / DAG / 运行 ID 都能搜）；复制链接给别人，打开就是同一份筛选结果。',
                  '本页只有两个动作：看详情、重跑。重跑需要 ops:run，且只对结束的失败/取消/上游失败实例开放；运行中、排队中不给重跑（会多起一个实例）。',
                  '重跑不覆盖历史：它是新建一次运行，原实例记录保留；同一业务日期已有在跑的实例时按钮置灰，防止两个运行同时写同一批表。',
                  '日志：点行打开详情抽屉 → 任务表每行「日志」按钮；空日志会明确标出；失败原因可展开看全文。未跑完的实例每 3 秒自动更新状态。',
                  '关系：详情里点文件名可跳到数据开发的该文件定义；告警事件里的「实例 #ID」也是跳这里；补数在运维总览发起。',
                ]} />
              </>
            ),
          },
          {
            key: 'alerts',
            label: '告警中心：确认、关闭与投递',
            children: (
              <>
                <span id="help-alerts" />
                <SectionList items={[
                  '告警怎么来：运行失败、质量未通过、运行超时（SLA）、采集任务失败、系统监控异常都会自动生成事件；规则每 30 秒评估一次，同一规则对同一对象只告警一次，不会刷屏。',
                  '处理状态机：待处理 →（确认）→ 已确认 →（关闭）→ 已关闭。确认是关闭的前置；关闭必须填至少 5 个字的处置说明；关闭后可重开（回到已确认，说明保留）。',
                  '删除只对「已关闭」开放，界面删了就找不回（审计日志仍保留动作）；批量确认/关闭会跳过状态不符的行并说明原因。',
                  '级别分严重 / 警告：铃铛角标和「严重待处理」卡就是给值班的第一优先级；事件详情里的实例编号可跳到运行实例。',
                  '投递记录：通知渠道支持 Webhook / 钉钉 / 飞书 / 邮件，可测试、启停。投递失败自动退避重试（1/2/4/8/16 分钟，最多 5 次）；耗尽后标「未送达」——此时值班可能根本没收到，先修渠道，再点「重投」补发。',
                  '静默窗口命中的告警不生成事件但会记审计；超时未确认的严重告警可按规则升级。',
                  '关系：告警规则本身的启停/删除需要管理权限；「立即检查」可手动跑一轮全平台或单规则评估。',
                ]} />
              </>
            ),
          },
          {
            key: 'baselines',
            label: '任务基线：承诺时间与预警',
            children: (
              <>
                <span id="help-baselines" />
                <SectionList items={[
                  '基线是什么：给一组关键工作流承诺「几点前必须跑完」。平台按每个任务的历史耗时（P90）加当前运行情况，每分钟评估一次，预计赶不上就先进入预警，真赶不上就是违约。',
                  '本页是只读看板：看今日状态（未开始 / 运行中 / 预警中 / 已完成 / 已违约 / 运行失败 / 已停用）、展开行看每个成员任务的估算依据、在「事件时间线」查预计迟到 / 承诺违约 / 已恢复三类事件。',
                  '新建、编辑、删除不在本页：到数据开发打开该任务，右侧「配置」面板里的「任务基线」设置承诺完成时间与提前预警分钟数。',
                  '「评估」按钮手动触发一轮评估（需要 ops:run）；行上的「去任务配置」直达对应文件。',
                  '坑：基线违约显示在本页时间线，不进告警中心——盯违约要收藏本页或看事件时间线。',
                  '关系：基线成员就是工作流；它们排队、运行、失败的情况到「运行实例」查。',
                ]} />
              </>
            ),
          },
          {
            key: 'monitor',
            label: '系统监控：服务与引擎健康',
            children: (
              <>
                <span id="help-monitor" />
                <SectionList items={[
                  '这页看集群健康：节点就绪数、Pod 运行数、异常 Pod、副本不足、磁盘最高使用率、未关闭告警，每 15 秒自动刷新；历史曲线另有专门的曲线入口（页面底部给了地址）。',
                  '任何登录用户都能看（只读）；动操作要权限：扩缩副本、启停/删除服务、节点封锁驱逐、查询限额（system:manage）等。',
                  '高危动作有守卫：删除节点会先「预演」列出受影响 Pod，有 Pod 未清不能直接删，要先驱逐；最后一台可调度节点会被后端拒绝删除；删除服务是删本体不是停用，勿与「停止」混用。',
                  '改副本数或内存上限会滚动重启该服务，短暂闪断属预期；平台自身的核心组件不提供启停按钮。',
                  '存储引擎的节点管理：缩容要先把数据迁空（进度可见），迁不完下不掉；扩容前的副本检查会提示风险。',
                  '关系：这页异常几乎会波及所有业务页面——查询变慢、任务不排队、告警堆积时，先来这里定位是哪块组件的问题。',
                ]} />
              </>
            ),
          },
          {
            key: 'gravitino',
            label: '元数据巡检：一致性与清理',
            children: (
              <>
                <span id="help-gravitino" />
                <SectionList items={[
                  '这页核对统一元数据目录与平台数据源的一致性：每一项分为「在用 / 受保护 / 孤儿 / 重复」四类——孤儿是平台已没有对应数据源的残留条目，重复是同名多份的注册信息。',
                  '进页面自动跑一次「巡检（预演）」：只报告、不改动；明细表逐项写明「会删 / 不动」和判定依据。',
                  '「确认清理」只会删除孤儿与重复项，且在用、受保护（平台内置）条目永远不动；清理是元数据层面的软删除，不碰数仓库表里的真实数据。',
                  '入口权限是 datasource:write：能管理数据源的人才进得来；可清理数为 0 时按钮禁用。',
                  '关系：刚删过数据源后这里出现孤儿是正常时序；清理后「表管理/数据地图」的浏览不受影响，因为物理表从未被动过。',
                ]} />
              </>
            ),
          },
          {
            key: 'admin',
            label: '管理中心：项目、部门、用户与角色',
            children: (
              <>
                <span id="help-admin" />
                <SectionList items={[
                  '四个标签页：项目、部门、用户与权限、角色与权限点；入口要求任一管理权限点（user:manage / role:manage / tenant:manage / department:manage），缺哪个对应页签就看不到。',
                  '新建项目 = 真建库：填部门 + 名称（+ 负责人 / 数仓数据源）提交后，平台为该库创建一对仓库（开发库 + 生产库），库名按部门前缀自动生成、前缀被占用会顺延并提示；项目编码由部门信息拼成，创建后不可改。',
                  '项目编辑守卫：改名库必须成对改，改库名只建新库、旧库不删（数据迁移要自己做）；成员里必须保留至少一个负责人；安全策略仅项目负责人或平台管理员可编辑。',
                  '删项目要当心：会级联删除项目下的文件、数据源、服务、质量规则、告警规则、采集任务和通知渠道且不可恢复；真实数据库会保留，需管理员另行处理。',
                  '租户与成员（角色页）：内置角色只读，自定义角色按权限点勾选；邀请成员选择账号并给租户内角色（默认只读访客）；default 租户不可删，有业务数据的租户也不可删，强删校验要手输租户编码。',
                  '坑：项目列表保存是全平台唯一的项目编辑入口（数据开发里的重命名只改名）；找不到入口先来这里各页签看一遍。',
                ]} />
              </>
            ),
          },
        ]}
      />

      {pending.length > 0 && (
        <Alert
          type="warning"
          showIcon
          style={{ marginTop: 12 }}
          message="以下页面暂无对应帮助章节（正文待后续卡补齐）"
          description={pending.map((p) => p.label).join('、')}
        />
      )}
    </div>
  )
}
