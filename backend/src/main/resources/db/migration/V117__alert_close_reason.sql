-- BUG-TC6-10：关闭告警必须留下处置说明。
--
-- 背景：关闭接口原先不接收任何 body，事后只能从 closed_by/closed_at 看出「谁何时」关了，
-- 看不出「为什么」；而 CLOSED 又是唯一的终态，误关一次线索就断了。V117 只加一列：
--   · close_reason：关闭时填写的处置说明（接口层强制 ≥5 字，与变更单/改名的理由口径一致）。
--     为空表示历史数据（V117 之前关闭的事件），前端按「未填写」展示。
--   · 重开（POST /api/alerts/{id}/reopen）会把 close_reason 清空，历史理由留在
--     platform.audit_log（ALERT_CLOSE / ALERT_REOPEN 两条），当前行只反映当前状态。
--
-- 号段说明：V116 已被 D51 返工占用，本迁移按顺序占 V117。合并/部署时 V116 必须与 V117
-- 同批上线（Flyway 默认不允许低版本迁移晚于高版本落库）。

alter table platform.alert_event
    add column if not exists close_reason text;
