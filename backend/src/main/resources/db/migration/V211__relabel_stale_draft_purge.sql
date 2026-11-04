-- ============================================================================
-- BUG-CHAIN-2-B 回流队列过期草稿清理（PG 侧真迁移；mos 侧孪生件同槽同号，见文末⑤）
-- 项目负责人 2026-11-02 批脏数据清理=甲：过期回流存量走迁移批量清。
-- 号段：V211=V210 之后下一空闲号（V210 注「V201+ 空闲」，CONFLICT-WM5-1 主控定版）；
--       占号在案不越段——本行即占号登记。
--
-- 【A 段口径 = 过期依据（commit 8bcfdde，与 docs/运维-回流队列脏数据清单-20261102.md 逐字一致）】
--   relabel 推送的条目只是「数据集条目」，数据集→标注队列无自动通路（现网 :8090 openapi
--   实测 dataset∧project 路由=NONE）；队列可见唯一口=mos 手动
--   POST /api/label-studio/projects/{pid}/import，且平台侧无调用方。
--   ⇒ 项目负责人批准基准日之前产生的回流队列草稿，永远不会被任何标注员看到、也无对账目标，
--     属过期死存量；批次 1/2 另引用幽灵数据集 ds_5@0（mos 侧 ds_5 已不存在，假 mos_task_ref）。
--
-- 【过期定义（写死，勿改口径——改了必须同步清单文档）】
--   platform.flywheel_relabel_batch：
--       created_at < '2026-11-02 00:00:00+08'
--       且不被非终态 flywheel_run（status not in ('CLOSED','FAILED')）引用——
--       flywheel_run 只读判定、不改写（本卡「不许碰别的表」）；
--   platform.badcase_pool：
--       batch_id ∈ 上述批次集合（池行随批次同死同活，不留孤儿）。
--   基准日取项目负责人批甲当日零点：现网九行存量（3 批次 + 6 池行）全部早于此，逐行见清单文档。
--
-- 【删除方式（卡面选择）：备份表，不是软删标记。理由三条——】
--   ① ledger_key 有 UNIQUE 约束，软删留身会占死键，下一圈重跑 E2E/FW-6 封批直接撞键；
--   ② 两表 status 均有 CHECK 枚举，新增「已清/作废」值=改表结构，且所有读谓词（READY 扫描、
--      NEW 计数等）都要跟着代码改动排除软删行——违反本卡「不许碰别的表、零代码变更」边界；
--   ③ 备份表让队列面保持干净，回滚=一条 INSERT ... SELECT（命令见清单文档第四节）；
--      Flyway PG 单迁移单事务，中途失败自动整体回滚、等同未发生。
--   备份表 *_v211bak 为本次清理新增表，只此两张，不碰其他表。
-- ============================================================================

-- 1) 批次台账先备份后删（备份=权威删除集，重跑幂等：删除只认备份表里的 id）。
create table if not exists platform.flywheel_relabel_batch_v211bak as
    select b.*, now() as v211_purged_at
      from platform.flywheel_relabel_batch b
     where b.created_at < '2026-11-02 00:00:00+08'
       and not exists (select 1
                         from platform.flywheel_run r
                        where r.relabel_batch_id = b.id
                          and r.status not in ('CLOSED', 'FAILED'));

delete from platform.flywheel_relabel_batch b
 where b.id in (select id from platform.flywheel_relabel_batch_v211bak);

-- 2) 池行随批次走：备份集=批次备份集的 batch_id 像集，再按备份集删除。
create table if not exists platform.badcase_pool_v211bak as
    select p.*, now() as v211_purged_at
      from platform.badcase_pool p
     where p.batch_id in (select id from platform.flywheel_relabel_batch_v211bak);

delete from platform.badcase_pool p
 where p.id in (select id from platform.badcase_pool_v211bak);

-- ⑤ 同槽孪生件说明：本链脏数据在 mos 侧还有两行空答案待标注草稿（清单文档第三节），
--   mos 后端=Python+SQLite、无 .sql 自动迁移机制，对偶件按卡面口径落
--   mos/platform/backend/src/main/resources/db/migration/V211__relabel_stale_draft_purge.sql，
--   为运维手动执行脚本（执行/复核命令都在清单文档里），与本迁移同口径、同基准日。
