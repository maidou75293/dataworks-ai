import { Typography } from 'antd'

/**
 * 后端错误 → 「原因 + 下一步」的统一映射。
 *
 * 只做展示层翻译，不改变任何请求/权限逻辑；原始 message 始终保留在
 * 「原始错误（排查用）」里，避免把真实报错吞掉后无法排查。
 */
export type ErrorLike =
  | string
  | { message?: string; status?: number }
  | null
  | undefined

export type ErrorAdvice = {
  kind: 'auth' | 'permission' | 'notfound' | 'metadata' | 'readonly' | 'engine' | 'timeout' | 'unknown'
  /** 一句话原因 */
  cause: string
  /** 用户接下来能做什么 */
  next: string
}

type Rule = ErrorAdvice & { test: RegExp }

// 顺序有意义：401/403 先判，避免「无权访问库: xxx」被「库不存在」误伤。
const RULES: Rule[] = [
  {
    kind: 'auth',
    test: /未登录|登录(已|状态)?(过期|失效)|token\s*(已)?(过期|失效)|unauthorized/i,
    cause: '登录状态已失效，后端没有拿到有效身份。',
    next: '重新登录；如果刚改过密码或角色，登录后再刷新当前页。',
  },
  {
    kind: 'permission',
    test: /无权|权限不足|没有.{0,6}权限|forbidden|access denied|not authorized|拒绝访问/i,
    cause: '当前账号缺少这条 SQL / 这个库表的操作权限。',
    next: '到「权限申请」提交库/表读写授权，或联系租户管理员加权限；只读查询选 READ 即可。',
  },
  {
    kind: 'notfound',
    test: /不存在|找不到|unknown\s+table|unknown\s+database|no\s+such\s+(table|database)|does\s+not\s+exist|not\s+found/i,
    cause: '库名或表名可能拼错，或对象已被删除 / 重命名。',
    next: '到「表管理」刷新库表列表，确认名称后再执行；湖表要写全 catalog.db.table。',
  },
  {
    kind: 'metadata',
    test: /元数据降级|降级|gravitino|统一元数据.{0,12}(不可用|失败)/i,
    cause: '统一元数据服务（Gravitino）暂不可用，平台已回退到直连 Doris。',
    next: '已展示的内容仍可用，可稍后刷新重试；如果字段缺失且持续存在，到「数据源」检查 Gravitino 连接。',
  },
  {
    kind: 'readonly',
    test: /只允许\s*(select|with)|只读|read[- ]only|不允许.{0,12}(insert|update|delete|create|drop)/i,
    cause: '查询页是只读入口，只接受 SELECT / WITH，不接受 DDL / DML。',
    next: '去掉建表、写入、删除语句后重试；需要改结构请到「表管理」，需要写数据请用「数据集成/数据开发」。',
  },
  {
    kind: 'engine',
    test: /仅导入|无法用\s*sql\s*查询|没有\s*sql\s*执行链路|引擎.{0,8}不支持|不支持.{0,8}(查询|引擎)|unsupported|not\s+queryable|kafka\s*数据源|文件类数据源/i,
    cause: '这条 SQL 命中的数据源没有 SQL 查询链路：Kafka topic、文件类数据源 / 文件集只能导入，不能直接查询。',
    next: '先同步入湖/入仓再查：到「数据集成」把数据写入 Iceberg 湖或 Doris；Kafka 实时数据到「流开发」用 FlinkSQL 落 Doris；完成后到「数据查询」查询。',
  },
  {
    kind: 'timeout',
    test: /超时|timeout|timed\s*out|connection\s*(refused|reset)|unknown\s+host|socket|网络|无法连接服务器|eof|502|503|504/i,
    cause: '请求没有及时到达后端，或后端到引擎（Doris/Flink）的连接超时。',
    next: '缩小时间范围、加 where 过滤或降低 limit 后重试；持续失败到「运维 → 系统监控」看服务状态，或联系管理员。',
  },
]

function rawMessage(error: ErrorLike): string {
  if (typeof error === 'string') return error
  if (error && typeof error === 'object' && typeof error.message === 'string') return error.message
  return error == null ? '' : String(error)
}

/** 把后端 message / HTTP 状态码翻成原因与下一步。 */
export function describeError(error: ErrorLike, status?: number): ErrorAdvice {
  const raw = rawMessage(error)
  const actualStatus = status ?? (error && typeof error === 'object' ? error.status : undefined)
  if (actualStatus === 401) return RULES[0]
  if (actualStatus === 403) return RULES[1]
  if (actualStatus === 404) return RULES[2]
  for (const rule of RULES) {
    if (rule.test.test(raw)) {
      return { kind: rule.kind, cause: rule.cause, next: rule.next }
    }
  }
  return {
    kind: 'unknown',
    cause: raw ? `后端返回了未归类的错误：${raw}` : '请求失败，但没有拿到具体错误信息。',
    next: '先重试一次；持续失败请把「原始错误」和操作步骤发给平台管理员。',
  }
}

/** 适合塞进 message.error 的一行摘要（原因 + 下一步）。 */
export function errorTip(error: ErrorLike, status?: number): string {
  const advice = describeError(error, status)
  return `${advice.cause} ${advice.next}`
}

/**
 * 错误块：原因 + 下一步 + 可折叠的原始错误。
 *
 * 直接作为 <ErrorState message={...}> 的内容使用，不渲染额外的 Alert。
 */
export default function ErrorText({
  error,
  status,
  showRaw = true,
}: {
  error: ErrorLike
  status?: number
  showRaw?: boolean
}) {
  const raw = rawMessage(error)
  const advice = describeError(error, status)
  return (
    <div className="error-text">
      <div>
        <Typography.Text strong>原因：</Typography.Text>
        {advice.cause}
      </div>
      <div style={{ marginTop: 4 }}>
        <Typography.Text strong>下一步：</Typography.Text>
        {advice.next}
      </div>
      {showRaw && raw ? (
        <details style={{ marginTop: 6 }}>
          <summary style={{ cursor: 'pointer', fontSize: 12, opacity: 0.75 }}>
            原始错误（排查用）
          </summary>
          <pre style={{
            margin: '6px 0 0',
            padding: 8,
            maxHeight: 160,
            overflow: 'auto',
            fontSize: 12,
            lineHeight: 1.5,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            background: 'rgba(0,0,0,0.03)',
            borderRadius: 4,
          }}
          >
            {raw}
          </pre>
        </details>
      ) : null}
    </div>
  )
}
