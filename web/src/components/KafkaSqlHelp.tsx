import { useState } from 'react'
import { Button, Drawer, Space, Table, Tabs, Tag, Typography, message } from 'antd'
import { CopyOutlined, QuestionCircleOutlined } from '@ant-design/icons'

type Option = {
  key: string
  meaning: string
  suggest: string
  required?: boolean
}

const SET_OPTIONS: Option[] = [
  { key: "SET 'execution.runtime-mode'", meaning: '运行模式：STREAMING 常驻；BATCH 跑完即退（一次性汇总用它）',
    suggest: "'STREAMING'；批处理补数用 'BATCH'" },
  { key: "SET 'parallelism.default'", meaning: '作业并行度——就在这里配（表单已不提供「并行度」字段）',
    suggest: "不写按 1 跑；1-4 起步，按 slot 数调" },
  { key: "SET 'pipeline.name'", meaning: '作业名（Flink Web UI / 集群作业列表里显示的名字）',
    suggest: "写成业务名，如 'city_order_summary'" },
  { key: "SET 'execution.checkpointing.interval'", meaning: 'Checkpoint 间隔；Kafka 位点靠它提交',
    suggest: "'30s'；没开 checkpoint 就反复重放" },
  { key: "SET 'execution.checkpointing.mode'", meaning: 'Checkpoint 语义',
    suggest: "'EXACTLY_ONCE'（默认）；容忍重复时可用 'AT_LEAST_ONCE'" },
  { key: "SET 'execution.checkpointing.timeout'", meaning: '单次 checkpoint 超时时间', suggest: "'10min'" },
  { key: "SET 'state.backend.type'", meaning: '状态后端；1.20 用 state.backend.type（state.backend 已弃用）',
    suggest: "'hashmap'；大状态再换 rocksdb" },
  { key: "SET 'state.checkpoints.dir'", meaning: 'Checkpoint 落盘目录（JobManager 挂了要靠它恢复）',
    suggest: "'file:///opt/flink/checkpoints'" },
  { key: "SET 'restart-strategy.type'", meaning: '重启策略：fixed-delay / failure-rate / none',
    suggest: "fixed-delay + 3 次 + 10s 间隔" },
  { key: "SET 'table.local-time-zone'", meaning: 'SQL 里的时区（时间函数、DATE_FORMAT 都受它影响）',
    suggest: "'Asia/Shanghai'" },
  { key: "SET 'table.exec.state.ttl'", meaning: '流聚合状态保留时长（不设会一直涨）',
    suggest: "'1h'，按业务容忍的回撤时间调" },
]

const KAFKA_OPTIONS: Option[] = [
  { key: "'connector' = 'kafka'", meaning: '连接器名，Flink 1.20 自带', suggest: 'kafka', required: true },
  { key: "'topic' = 'demo_orders'", meaning: '订阅的 topic；多个 topic 用分号分隔',
    suggest: '和「数据预览」里的 topic 一致', required: true },
  { key: "'topic-pattern'", meaning: '按正则订阅一批 topic（与 topic 二选一）',
    suggest: "'demo_.*'" },
  { key: "'properties.bootstrap.servers'", meaning: 'broker 地址，逗号分隔多个',
    suggest: '抄 Kafka 数据源的 Bootstrap Servers', required: true },
  { key: "'properties.group.id'", meaning: '消费组——就在 WITH 里写死（表单已不提供「消费组」字段）；位点提交、积压（lag）统计都按它算',
    suggest: "按环境写死：DEV 用 'dw_dev_orders_reader'，发布到 PROD 改 'dw_prd_orders_reader'；老任务的 ${KAFKA_GROUP_ID} 占位符仍可用", required: true },
  { key: "'scan.startup.mode'", meaning: '第一次启动从哪读：group-offsets（默认）/ earliest-offset / latest-offset / specific-offsets / timestamp',
    suggest: '调试用 earliest-offset；线上用 group-offsets' },
  { key: "'scan.startup.specific-offsets'", meaning: "指定分区位点，如 'partition:0,offset:100;partition:1,offset:200'",
    suggest: '只在 specific-offsets 模式下用' },
  { key: "'scan.startup.timestamp-millis'", meaning: '从某个时间戳（毫秒）开始读', suggest: '回补历史数据时用' },
  { key: "'scan.bounded.mode'", meaning: '把流读成有界（批）模式：latest-offset / group-offsets / specific-offsets / timestamp',
    suggest: '一次性补数时用，配合 runtime-mode=BATCH' },
  { key: "'properties.enable.auto.commit'", meaning: 'Kafka 客户端自动提交位点',
    suggest: "'false'，位点交给 checkpoint 管理" },
  { key: "'properties.auto.offset.reset'", meaning: '没有已提交位点时的兜底：earliest / latest / none',
    suggest: "'earliest'" },
  { key: "'properties.security.protocol'", meaning: 'SASL / SSL 认证协议',
    suggest: '当前 demo 集群是 PLAINTEXT，不用填' },
  { key: "'properties.sasl.mechanism' / 'properties.sasl.jaas.config'", meaning: 'SASL 账号信息',
    suggest: '需要认证的集群再填，密码不要写明文，走平台凭据' },
  { key: "'format' = 'json'", meaning: '消息体格式：json / csv / raw / avro / debezium-json / canal-json',
    suggest: 'demo_orders 是 JSON，用 json' },
  { key: "'json.ignore-parse-errors'", meaning: '解析失败的行直接跳过，不中断作业',
    suggest: "'true'，先跑通再看脏数据" },
  { key: "'json.fail-on-missing-field'", meaning: '缺少字段时是否失败', suggest: "'false'" },
]

const DORIS_OPTIONS: Option[] = [
  { key: "'connector' = 'doris'", meaning: 'Doris 连接器（Stream Load 写入）', suggest: 'doris', required: true },
  { key: "'fenodes'", meaning: 'FE 的 HTTP 端口（8030），不是 SQL 端口 9030',
    suggest: "'${DORIS_HOST}:8030'，提交时由后端替换", required: true },
  { key: "'username' / 'password'", meaning: 'Doris 账号',
    suggest: "'${DORIS_USER}' / '${DORIS_PASSWORD}'，库里不落明文", required: true },
  { key: "'table.identifier'", meaning: '目标表，库名.表名',
    suggest: 'demo_retail.dws_city_summary（用「表管理」建）', required: true },
  { key: "'sink.label-prefix'", meaning: 'Stream Load 的 label 前缀，同一作业靠它去重/重试',
    suggest: '按作业固定，如 city_summary' },
  { key: "'sink.enable-delete'", meaning: '允许按主键删除/更新（UNIQUE KEY 表）',
    suggest: '需要 upsert 时 true' },
  { key: "'sink.enable-2pc'", meaning: '两阶段提交，checkpoint 失败时不落半批数据',
    suggest: '追求精确一次时 true' },
  { key: "'sink.batch.size' / 'sink.batch.interval'", meaning: '攒批条数 / 间隔',
    suggest: '默认 10000 / 10s；量大再调' },
  { key: "'sink.max-retries'", meaning: 'Stream Load 失败重试次数', suggest: '3' },
  { key: "'sink.properties.format'", meaning: '写入格式：json / csv', suggest: "'json'" },
  { key: "'sink.properties.strict_mode'", meaning: '严格模式：脏数据直接报错而不是静默过滤',
    suggest: "'true'（不想丢数据就开）" },
]

const ERRORS: Option[] = [
  { key: 'Missing required options: properties.group.id',
    meaning: 'Kafka 源没写消费组', suggest: "WITH 里补 'properties.group.id'（按环境写死组名）" },
  { key: 'Could not find any factories that implement DynamicTableFactory',
    meaning: 'connector 名字写错，或镜像里没有这个连接器', suggest: 'Kafka 写 kafka，Doris 写 doris（小写、无版本号）' },
  { key: 'No operators defined in streaming topology',
    meaning: '只有 CREATE TABLE，没有 INSERT INTO ... SELECT', suggest: '脚本最后补一条 INSERT' },
  { key: 'Job ... has already reached terminal state',
    meaning: '作业已经结束（批跑完 / 已取消 / 失败）', suggest: '平台会自动归位成「已停止」，直接重新运行即可' },
  { key: 'Checkpoint expired before completing',
    meaning: 'checkpoint 超时，通常是状态太大或并行度不够', suggest: '调大 checkpointing.timeout、加并行度或缩 state.ttl' },
  { key: 'Stream Load failed / errCode',
    meaning: 'Doris 写入失败：账号、表模型、字段类型或 label 冲突', suggest: '核对 table.identifier、UNIQUE 主键与字段类型；换 sink.label-prefix' },
  { key: 'Batch 作业提交后立即 FINISHED',
    meaning: 'runtime-mode=BATCH 时跑完就退，属正常', suggest: '要常驻就把 runtime-mode 改回 STREAMING' },
]

const EXAMPLE = `-- ===== ① 运行参数（SET 必须写在最前面）=====
SET 'pipeline.name' = 'city_order_summary';
SET 'execution.runtime-mode' = 'STREAMING';
SET 'parallelism.default' = '2';
SET 'execution.checkpointing.interval' = '30s';
SET 'execution.checkpointing.mode' = 'EXACTLY_ONCE';
SET 'state.backend.type' = 'hashmap';
SET 'restart-strategy.type' = 'fixed-delay';
SET 'restart-strategy.fixed-delay.attempts' = '3';
SET 'restart-strategy.fixed-delay.delay' = '10s';
SET 'table.local-time-zone' = 'Asia/Shanghai';
SET 'table.exec.state.ttl' = '1h';

-- ===== ② Kafka 源：订单 JSON =====
CREATE TABLE kafka_orders (
  order_id   STRING,
  city       STRING,
  category   STRING,
  amount     DOUBLE,
  order_ts   BIGINT,
  ts AS TO_TIMESTAMP_LTZ(order_ts, 3),
  WATERMARK FOR ts AS ts - INTERVAL '5' SECOND
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo_orders',
  'properties.bootstrap.servers' = 'kafka-demo.dw-dev.svc.cluster.local:9092',
  'properties.group.id' = 'dw_dev_orders_reader',
  'scan.startup.mode' = 'earliest-offset',
  'properties.enable.auto.commit' = 'false',
  'format' = 'json',
  'json.ignore-parse-errors' = 'true'
);

-- ===== ③ Doris 结果表（UNIQUE KEY：同城市覆盖写）=====
CREATE TABLE doris_city_summary (
  city    STRING,
  orders  BIGINT,
  amount  DOUBLE,
  PRIMARY KEY (city) NOT ENFORCED
) WITH (
  'connector' = 'doris',
  'fenodes' = '\${DORIS_HOST}:8030',
  'username' = '\${DORIS_USER}',
  'password' = '\${DORIS_PASSWORD}',
  'table.identifier' = 'demo_retail.dws_city_summary',
  'sink.label-prefix' = 'city_summary',
  'sink.enable-delete' = 'true'
);

-- ===== ④ 计算并写入 =====
INSERT INTO doris_city_summary
SELECT city, COUNT(*) AS orders, SUM(amount) AS amount
FROM kafka_orders
GROUP BY city;`

const PLACEHOLDERS: Option[] = [
  { key: '${KAFKA_GROUP_ID}', meaning: 'Kafka 消费组（仅兼容老任务）', suggest: "提交时替换成任务上存量组名；新任务不用它，直接在 WITH 里写死" },
  { key: '${DORIS_HOST}', meaning: 'Doris FE 地址', suggest: '平台已知，提交时自动替换' },
  { key: '${DORIS_USER} / ${DORIS_PASSWORD}', meaning: 'Doris 账号', suggest: '库里的 SQL 不落明文' },
  { key: 'SET 语句里的值', meaning: '运行参数', suggest: '并行度、检查点等运行参数全部在 SQL 里 SET，不在右侧表单配' },
]

function OptionsTable({ rows }: { rows: Option[] }) {
  return (
    <Table<Option>
      size="small"
      rowKey="key"
      pagination={false}
      dataSource={rows}
      columns={[
        {
          title: '项',
          dataIndex: 'key',
          key: 'key',
          width: 280,
          render: (value: string, row) => (
            <Space size={4} wrap>
              <Typography.Text code style={{ fontSize: 12 }}>{value}</Typography.Text>
              {row.required && <Tag color="red" style={{ marginInlineEnd: 0 }}>必填</Tag>}
            </Space>
          ),
        },
        { title: '什么意思', dataIndex: 'meaning', key: 'meaning' },
        {
          title: '建议值 / 怎么处理',
          dataIndex: 'suggest',
          key: 'suggest',
          width: 280,
          render: (value: string) => (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{value}</Typography.Text>
          ),
        },
      ]}
    />
  )
}

/** 流开发页的脚本帮助：完整例子（含 SET）+ Kafka/Doris 参数 + 常见错误。 */
export default function KafkaSqlHelp() {
  const [open, setOpen] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(EXAMPLE)
      message.success('完整例子已复制')
    } catch {
      message.warning('浏览器不允许写剪贴板，请手动选中复制')
    }
  }
  return (
    <>
      <Button size="small" icon={<QuestionCircleOutlined />} onClick={() => setOpen(true)}>
        脚本帮助
      </Button>
      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        width={1000}
        title="FlinkSQL 脚本帮助：SET 参数 / Kafka 源 / Doris 汇 / 常见错误"
      >
        <Tabs
          items={[
            {
              key: 'example',
              label: '完整例子',
              children: (
                <>
                  <Space style={{ marginBottom: 8 }}>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      SET 必须写在脚本最前面；下面这份改 topic/表名就能跑。
                    </Typography.Text>
                    <Button size="small" icon={<CopyOutlined />} onClick={copy}>复制</Button>
                  </Space>
                  <pre className="form-json-example" style={{ maxHeight: 460, overflow: 'auto' }}>
                    {EXAMPLE}
                  </pre>
                </>
              ),
            },
            {
              key: 'pg',
              label: '并行度 / 消费组',
              children: (
                <>
                  <Typography.Paragraph style={{ fontSize: 13 }}>
                    流任务表单已不提供「并行度」「消费组」两个字段——都写进 FlinkSQL 脚本本身。
                    一个脚本里有多个 Kafka 源时，每个源可以配自己的消费组，互不干扰。
                  </Typography.Paragraph>
                  <Typography.Title level={5}>① 并行度：SET 语句（作业级）</Typography.Title>
                  <pre className="form-json-example">{`-- 写在所有 CREATE/INSERT 之前；不写则按并行度 1 运行
SET 'parallelism.default' = '2';`}</pre>
                  <Typography.Title level={5}>② 消费组：Kafka 源 WITH 子句（按环境写死）</Typography.Title>
                  <pre className="form-json-example">{`CREATE TABLE kafka_orders (
  order_id STRING,
  amount   DOUBLE
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo_orders',
  'properties.bootstrap.servers' = 'kafka-demo.dw-dev.svc.cluster.local:9092',
  'properties.group.id' = 'dw_dev_orders_reader',   -- 发布到 PROD 改 dw_prd_orders_reader
  'format' = 'json'
);`}</pre>
                  <Typography.Title level={5}>③ 可选：单源/单汇并行度（WITH 子句）</Typography.Title>
                  <pre className="form-json-example">{`-- Kafka 源（在上面的 WITH 里追加）
'scan.parallelism' = '4',
-- Doris 汇
'sink.parallelism' = '2'`}</pre>
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                    位点提交依赖 checkpoint：记得配
                    <Typography.Text code>{"SET 'execution.checkpointing.interval' = '30s';"}</Typography.Text>，
                    否则重启会按 scan.startup.mode 重放。老脚本里的
                    <Typography.Text code>{'${KAFKA_GROUP_ID}'}</Typography.Text>
                    占位符仍按任务上存的消费组替换（兼容保留）。
                  </Typography.Paragraph>
                </>
              ),
            },
            {
              key: 'set',
              label: `SET 参数（${SET_OPTIONS.length}）`,
              children: (
                <>
                  <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                    运行参数写 <Typography.Text code>SET '名' = '值';</Typography.Text>，
                    必须在所有 CREATE/INSERT 之前。并行度就用
                    <Typography.Text code>{"SET 'parallelism.default' = '2';"}</Typography.Text>
                    在脚本里设置，表单右侧不再有该字段。
                  </Typography.Paragraph>
                  <OptionsTable rows={SET_OPTIONS} />
                </>
              ),
            },
            {
              key: 'kafka',
              label: `Kafka 源（${KAFKA_OPTIONS.length}）`,
              children: <OptionsTable rows={KAFKA_OPTIONS} />,
            },
            {
              key: 'doris',
              label: `Doris 汇（${DORIS_OPTIONS.length}）`,
              children: <OptionsTable rows={DORIS_OPTIONS} />,
            },
            {
              key: 'errors',
              label: '常见错误',
              children: <OptionsTable rows={ERRORS} />,
            },
            {
              key: 'more',
              label: '占位符 / 多源',
              children: (
                <>
                  <Typography.Title level={5}>占位符（提交时由后端替换）</Typography.Title>
                  <OptionsTable rows={PLACEHOLDERS} />
                  <Typography.Title level={5} style={{ marginTop: 16 }}>一个脚本里多个 Kafka 源</Typography.Title>
                  <ul style={{ paddingLeft: 18, fontSize: 13 }}>
                    <li>一个脚本可以建多张 Kafka 表，每张表写自己的 topic。</li>
                    <li>
                      都用 <Typography.Text code>{'${KAFKA_GROUP_ID}'}</Typography.Text> 就是<strong>同一个消费组</strong>：
                      各表位点共享、容易互相顶；每个源要独立位点就写死具体组名（如
                      <Typography.Text code>dw_dev_orders_a</Typography.Text>）。
                    </li>
                    <li>
                      消费组直接在 WITH 里按环境写死；老任务上存的消费组只影响
                      <Typography.Text code>{'${KAFKA_GROUP_ID}'}</Typography.Text>
                      替换和 Kafka 预览默认值，不会改你写死的组名。
                    </li>
                    <li>
                      位点靠 checkpoint 提交：没开 <Typography.Text code>execution.checkpointing.interval</Typography.Text>
                      时，<Typography.Text code>earliest-offset</Typography.Text> 每次重启都会从头重放。
                    </li>
                  </ul>
                </>
              ),
            },
          ]}
        />
      </Drawer>
    </>
  )
}
