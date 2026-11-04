# e2e/acceptance — dataworks 数据中台可重复验收

一套**可重复跑、只读优先**的验收脚本：健康检查、能力枚举、以及 16 条业务验收用例的
可执行步骤（`cases.md`）。与 `scripts/e2e/` 下的一次性回归脚本互补——这里不建数据、
不依赖上一次运行的残留状态。

## 目录

| 文件 | 作用 | 是否只读 |
|---|---|---|
| `common.py` | HTTP 封装（curl.exe / urllib 回退）、JSON 解析、断言、结果表格、`--readonly` 开关 | — |
| `check_health.py` | `/api/health`、`schemaVersion`、`db` 依赖 | ✅ 纯 GET |
| `check_capabilities.py` | 登录 + 数据源/项目/库表/权限三模式/能力矩阵枚举 | ✅ 仅 GET（登录 POST 除外） |
| `cases.md` | 16 条验收用例（前置/步骤/预期/证据/清理） | 文档 |

## 怎么跑

在本机 WSL（推荐，脚本自动走 `curl.exe`）：

```bash
cd dataworks-ai

# 1. 健康检查（无需账号）
python3 e2e/acceptance/check_health.py --readonly

# 2. 能力枚举（需要账号）
export DW_USER=admin
export DW_PASS='******'
python3 e2e/acceptance/check_capabilities.py --readonly

# 3. 只给定 token（不登录，最严格的只读）
export DW_TOKEN='<已签发的 Bearer token>'
python3 e2e/acceptance/check_capabilities.py --readonly --json
```

Windows CMD / PowerShell 下也可以直接跑（无 curl.exe 时自动回退 urllib）：

```powershell
cd dataworks-ai
python e2e\acceptance\check_health.py --readonly
```

参数：

| 参数 | 说明 |
|---|---|
| `--base` | 平台地址，默认 `http://192.0.2.11:30090`，也可用 `DW_BASE` |
| `--readonly` | 拒绝 GET 以外的一切请求（唯一例外：`POST /api/auth/login` 换 token） |
| `--timeout` | 单请求超时秒数，默认 30（`DW_TIMEOUT`） |
| `--json` | 结束时输出机器可读 JSON 摘要（CI 用） |

退出码：`0` 无 FAIL（允许 SKIP）；`1` 存在 FAIL。CI 可直接据此卡门禁。

## 需要的账号

| 用途 | 建议账号 | 必须权限 |
|---|---|---|
| 健康检查 | 无 | 接口公开 |
| 能力枚举 | `admin` 或平台管理员 | `system:manage`、`query:run`、`table:ddl`、`role:read` |
| 权限用例 A05–A10 | 两个角色：管理员 + 普通分析员 | 分析员仅有 `query:run`，无目标表/列授权 |
| DDL 用例 A02–A04 | 开发或管理员 | `table:ddl` |
| 集成/血缘 A14–A15 | 管理员 | `sync:read` / `sync:write` |

凭据只从环境变量读取，**不要写进脚本或提交到仓库**。脚本不做重试、不猜密码：
登录失败一次即停（平台连续失败会锁定 5 分钟）。

## 数据自建自清规则

1. **命名**：所有新建对象 `acc_` 前缀 + 8 位时间戳，例：`acc_t_10260030`、`acc_mask_10260030`。
2. **位置**：只落在验收专用项目 / 验收库；不碰 `_prd` 结尾的生产库（平台对 `_prd` 只读）。
3. **自清**：用例结束（含失败）必须执行清理——删表、下线并删除同步任务、撤销临时授权、恢复权限模式。
   清理失败要在报告里单列，不能静默。
4. **权限模式是全局开关**：A07–A09 会切换 `OFF/TABLE/COLUMN`，**必须**在 `finally` 里恢复原值并
   二次 `GET /api/security/permission-mode` 确认；线上环境禁止做这三条（只在测试环境跑）。
5. **不改既有资产**：需要改表结构时只改本次 `acc_` 表；不重命名、不 drop 既有表。
6. **血缘与审计不清理**：属追溯数据；验收任务下线即可。

## 只读模式的边界

`--readonly` 允许：

- 所有 `GET`（含带查询参数的枚举）；
- `POST /api/auth/login`——仅用于换 token，服务端只写一条登录审计，不改业务数据。

`--readonly` 拒绝：建表/改表、查询执行（`POST /api/query/doris`）、授权、模式切换、任务运行等。
被拒绝时脚本直接抛 `RuntimeError`，不会发出请求。

## 输出长什么样

```
PASS 健康接口可达  [HTTP 200]  HTTP 200  12ms
PASS status=UP  [HTTP 200]  status='UP'
...
--------------------------------------------------------------------------------
状态    HTTP    用例                                          详情
--------------------------------------------------------------------------------
PASS    200     健康接口可达                                  HTTP 200  12ms
...
合计 9   PASS 9   FAIL 0   SKIP 0   用时 1.4s
```

每条带 HTTP 状态码与响应片段（截断 160 字符）；FAIL 会在末尾汇总，便于直接贴进验收报告。

## 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `CURL_ERROR(6)` / 连接超时 | WSL 到 10.10 网段不通，确认走的是 `curl.exe`（输出里 `via curl.exe`）；或平台地址不对 |
| 全部 401 | 未设 `DW_USER/DW_PASS` 或 token 过期；先 `export` 再跑 |
| 登录 401 | 账号密码不对；**不要重复试**（5 分钟锁定），找管理员重置 |
| `登录 429` | 已触发锁定，等 5 分钟或换 IP/账号 |
| `SKIP 无凭据` | 只跑了匿名部分；补 `DW_USER/DW_PASS` 后重跑 |
| `metadataSource` 为空 | 元数据服务未配置；看 `/api/tables/databases?withMeta=true` 的 `degraded` |

## 与其他脚本的关系

- `scripts/e2e/accept-*.py`：历史回归脚本，可能建数据、依赖固定账号；
- `e2e/acceptance/`：新的门禁层，**只读可跑**、可重复、无残留，适合每次发布后执行；
- `cases.md` 的写操作用例：在测试环境按步骤人工/半自动执行，作为发布前的业务验收清单。
