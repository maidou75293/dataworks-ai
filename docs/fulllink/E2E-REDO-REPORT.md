# E2E-REDO 全链路端到端重跑报告（清理后新基线）

- 执行时间：2026-11-03 06:24–06:40（epoch31 收口；e29/e30 两班被 recycle 重派作废，见附注）
- 被测版本：**v586**；`/api/health` schemaVersion **211**；环境 `http://192.0.2.11:30090`（deploy 文档入口）
- 基线三块：①`scripts/e2e/accept-a01-a18.py --version v586`（UI 未跳过，A17/A18 截图实测）②契约两脚本 ③三链（00_login→链1→链2→链3）
- 结果：**PASS 19/22，FAIL 3（A13 产品BUG、契约与链3 用例期望），SKIP 0**；红项卡：BUG-META-GRAV-CRED-1、TEST-SVC-CONTRACT-CRED-1、TEST-DL1-CHAIN3-WATCH-1
- 逐条原始证据：`logs/e2e/e2e-redo-v586-20261103.jsonl`（既有字段+期望输出/判定/失败归类/bug卡号四补栏）；A 块另存 `docs/验收证据-A01-A18-v586-20261103-062759.jsonl` 与 `…-062937.jsonl`（A13 复跑件）；脚本级原始输出在 `logs/e2e/redo-*.out`

| 用例号 | 功能名 | 输入(method/path/request) | 期望输出 | 实际输出(status/response) | 判定 | 失败归类 | bug卡号 |
|---|---|---|---|---|---|---|---|
| A01 | 启动与健康 | GET /api/health（双实例） | 200；status=UP、db=UP、schemaVersion=当前迁移最大号(211) | 200 UP/UP schemaVersion=211 两次一致 | PASS | - | - |
| A02 | 建表与动态分区 | POST /api/tables 建 acc_dyn_1026（DAY 动态分区）→ SHOW PARTITIONS | 200；7 个分区=执行日前后 3 天 | 200；分区 p20261031..p20261106 共 7 个含当天 p20261103 | PASS | - | - |
| A03 | 建表 7 条预检 | POST /api/tables/preview 依次 7 个非法参数 | 7/7 返 400 且文案分别定位副本数/分桶列/key 默认值/类型/分区列/保留字/动态参数 | 7/7 命中 400 文案各就各位 | PASS | - | - |
| A04 | SQL 模式与多语句拦截 | POST /api/tables/sql/preview 等价 DDL；再两条 CREATE | 首条 200 且 model=DUPLICATE；两条 400 只允许单条语句 | 200 DUPLICATE；400 只允许单条语句 | PASS | - | - |
| A05 | 字段级 L3/L4 默认脱敏 | admin 设 category=L3+TOKEN、name=L4+MASK；viewer POST /api/query/doris | category 返 16 位 token；name 返 ***；无明文 | category=16 位 token；name=***；tokenizedColumns 在案 | PASS | - | - |
| A06 | 权限模式 OFF | PUT /api/security/permission-mode OFF+confirm；viewer 查未登记 L3 表 | 200；原样明文；guard.mode=OFF；审计含高风险标记 | 明文返回；审计 [高风险] 在案 | PASS | - | - |
| A07 | 权限模式 TABLE | 恢复 TABLE 后 viewer 查未登记 L3 表 | 403 且提示提交权限申请；不返回行 | 403 含申请提示文案 | PASS | - | - |
| A08 | 权限模式 COLUMN | COLUMN 下 viewer 先查→viewer 提交列级申请→admin 审批→再查→回收→再查 | 未授权 token；通过后明文；回收后回 token | 申请 #315：token→明文→token 三阶段符合 | PASS | - | - |
| A09 | 项目级安全覆盖 | 平台 TABLE；PUT /api/projects/1/security COLUMN；再 GET | effectiveMode=COLUMN、effectiveSource=PROJECT；平台仍 TABLE | COLUMN/PROJECT 生效；平台仍 TABLE；已恢复 INHERIT | PASS | - | - |
| A10 | TOKEN 关联分析 | COLUMN 下 viewer 两次 group by category 查询对比 token | 同值同 token；count 与明文分组一致；无明文 | 8 组全部同值同 token；计数一致 | PASS | - | - |
| A11 | Iceberg 数据源与建表 | POST /api/lake/.../tables 建 acc_lake_1026（day(ts)） | 200；managedTableId 非空、dataLevel=L2；DDL 无 BUCKETS | catalog=iceberg_lake managedTableId=264；DDL 无 BUCKETS | PASS | - | - |
| A12 | Iceberg 查询与内表 join | POST /api/query/doris 查湖表；再 join 内表 | 两条均 200；join 返回匹配行；无 Catalog 未登记 | 两条 200；join 2 行 | PASS | - | - |
| A13 | Gravitino 降级 | GET /api/tables/databases?withMeta=true（前置）→ kubectl 缩容 gravitino → 轮询 → 恢复 | 前置 degraded=false（Gravitino 在位时）；缩容后 degraded=true metadataSource=doris；恢复后 degraded=false | 前置即红：未缩容已 degraded=true/metadataSource=doris；Gravitino Pod Running 1/1，catalog doris_dev LIST schemas HTTP 500 Access denied for user dw_meta_ro（凭据轮换未同步）；两次复现（06:27/06:29） | FAIL | 产品BUG | BUG-META-GRAV-CRED-1 |
| A14 | Spark 查询 | POST /api/workflows/360/validate → /smoke（临时自建 SPARK 流） | 校验 ok；冒烟返回行；无引擎报错 | validate ok；smoke rowCount=1（workflow=360 临时自建，已删） | PASS | - | - |
| A15 | 多模态上传 | POST /api/assets 上传测试图 | 200 返回 id/uri/sha256；重复上传复用同一条 | 200 id=106 sha256=97a3a410…；重传 reused=True | PASS | - | - |
| A16 | 多模态检索与预签名 | GET /api/assets?mediaType=IMAGE；GET /api/assets/106/url；curl 预签名 | 列表含该资产；预签名 200 且字节一致 | total=24 含 106；预签名 200 下载 73B=上传 73B；删后 DELETED | PASS | - | - |
| A17 | 血缘列级 | GET /api/lineage?edgeType=COLUMN&table=fm_orders_911144.fm_agg_911144；UI /lineage 切列级截图 | 带 source_column/target_column 的边；界面与接口一致 | 2 条列级边；headless Edge 截图一致（docs/screenshots/accept-a17-column-20261103-062759.png） | PASS | - | - |
| A18 | 可发现性示例 SQL | GET /api/query/capabilities?projectId=1；UI /tables 结构→如何查询 | queryable/notQueryable 非空；四要素齐全 | 矩阵非空；截图四要素在（docs/screenshots/accept-a18-tables-20261103-062759.png） | PASS | - | - |
| 契约 | 数据服务契约+助手 Harness 契约 | service-contract-test.py（建带参服务→注入/三传参/类型/200 行截断/字符串冒号/只读守卫）+ agent-harness-contract-test.py（10 端口装配/鉴权/排序/审计链） | 两脚本全绿 rc=0 | harness 40 passed 0 failed rc=0；service 首步管理员登录 401 其余未执行（脚本写死 admin/admin，DEV 口令已轮换），且失败早退仍退 0=假绿闸 | FAIL | 用例期望 | TEST-SVC-CONTRACT-CRED-1 |
| 链1 | 数据→入仓（PG 源→Doris 汇） | dl1/00_login.py → 20_chain1_datasource.py → 21_chain1_sync.py（建 PG/Doris 源→同步任务→run→核行数） | 登录复用 token；建源 200；同步 SUCCESS；行数核对一致 | 首跑 21 红：DL1_DORIS_PW 未注入→建 Doris 源 400 必须填写密码；补环境变量重跑：源/汇 200、run SUCCESS rowsRead=20 rowsWritten=20、汇总与 Doris 计数核对过 | PASS | - | - |
| 链2 | AI Agent 链（配置/记忆/会话/对话） | dl1/30_agent_chain.py（identityMode 读写+非法值、记忆 CRUD/forget/404、SSE 对话、坏 project 404、health 诚实） | 全部断言 PASS 且 rc=0 | 30 步全 PASS FAIL 数=0 rc=0（chat-sse 首包 sessionId 正常） | PASS | - | - |
| 链3 | MOS 桥（annotation-push/media/flywheel） | dl1/40_mos_chain.py（推标注→回查→去重、资产上传→media 搬运→台账、flywheel track/poll/events） | 全部断言 PASS | 重跑后 19/20 PASS；flywheel-watch-readback FAIL：track 200 watchId=14 但探针 GET /api/mosbridge/flywheel/watch 默认 openOnly=true 而 job 已终态→恒 []（产品语义自洽，探针缺参数）；另脚本 FAIL 时 rc 仍 0=假绿 | FAIL | 用例期望 | TEST-DL1-CHAIN3-WATCH-1 |

## 附：红项与重跑口径

- **A13（产品BUG→BUG-META-GRAV-CRED-1）**：主跑+单条复跑两次前置即红（未缩容已 degraded=true）。取证：`GET /api/metadata/source` source=gravitino 但 degraded=true、degradedCount=5、lastDegradedReason=GRAVITINO_UNAVAILABLE；NodePort 30093 `LIST doris_dev/schemas` HTTP 500 `Access denied for user 'dw_meta_ro@192.0.2.12' (using password: YES)`；Gravitino Pod 本身 Running 1/0重启。定性=Gravitino catalog 凭据与 Doris 侧 dw_meta_ro 轮换不同步（与 ENV-1 同族不同账号），平台降级回退逻辑本身按设计工作。
- **契约（用例期望→TEST-SVC-CONTRACT-CRED-1）**：agent-harness-contract 40/40 全绿；service-contract 因写死 admin/admin 登录 401（DEV 口令轮换，accept 侧 F-C 已修此口径），实质断言 0 执行，且失败早退 rc=0 属假绿闸，卡面判据已改为输出面判绿。
- **链1（记 PASS）**：首跑 21 于建 Doris 源 400（缺 DL1_DORIS_PW 环境变量，平台『必须填写密码』校验正确）；注入凭据后重跑全绿 rowsRead=20/rowsWritten=20。首红归类=环境（执行侧缺 env 注入），已按重跑结论转正，如实留痕。
- **链3（用例期望→TEST-DL1-CHAIN3-WATCH-1）**：链1 入仓后 annotation-push 系列转绿，剩 flywheel-watch-readback 两次复现 FAIL：产品 watch 列表默认 openOnly=true 不含已终态行（语义自洽），探针未带参数；脚本 FAIL 时 rc 恒 0 同入卡修。
- **SKIP 说明**：本轮 UI 未跳过（A17/A18 headless Edge 实测过），故无环境类 SKIP；22 号齐全性由 `python -I scripts/ci/check-e2e-redo-report.py` 把关。
- **机制观察（非业务红，转 PM）**：本道心跳换班异常——progress 三件套（--lane/--epoch/--ring）BEAT_OK 入账后 recycle 仍按约 3 分钟节奏 release（06:24:22→06:27:21→06:28:22→06:31:22→06:32:22，epoch 29→30→31），lane-m7.beat 新鲜在账未被识别为活；疑回收器判活源不含 progress 事件（或未跑 start 挂 pid）。旧班一切上报按规矩作废，本报告由 e31 在任班出。
