-- V114：告警静默期与升级策略（D50，泳道 wm2）。
--
-- 背景：告警中心原先只有「确认 / 关闭」两个动作，既没有维护窗口也没有升级链路，
-- 真实使用会退化成告警疲劳——没人确认也就没人负责。本迁移只加策略字段，
-- 不改事件表既有状态机（OPEN/ACKED/CLOSED 与 (rule_id, event_key) 去重键保持原样）。
--
-- alert_rule：
--   · silence_until：维护窗口。窗口内规则命中**不生成事件**，但 AlertService 会写一条
--     ALERT_SILENCED 审计（否则事后查不到「为什么没告警」）；NULL＝不静默。
--   · escalate_after_minutes：事件产生后多久仍无人确认就升级；默认 30，0＝该规则不升级。
--   · escalate_to：升级接收人（用户名或角色码，仅用于留痕与通知文案，
--     投递仍走现有 platform.notify_channel 通道，不新造通道）。
--
-- alert_event：
--   · escalated_at / escalated_to：源事件「已升级过」的标记与当时的接收人。
--     升级会另插一条 event_key 形如 `<原 key>:escalated` 的新事件（因此天然被唯一键去重），
--     并把源事件 escalated_at 置为当前时间——同一源事件只会升级一次。
--     新生成的升级事件自身 escalated_at 也一并置位，不会被下一轮扫描再升级。

alter table platform.alert_rule
    add column if not exists silence_until timestamptz,
    add column if not exists escalate_after_minutes int not null default 30,
    add column if not exists escalate_to varchar(64);

alter table platform.alert_event
    add column if not exists escalated_at timestamptz,
    add column if not exists escalated_to varchar(64);

create index if not exists idx_alert_rule_silence
    on platform.alert_rule (enabled, silence_until);
-- 升级扫描：只看「未确认、未升级、还在窗口内」的事件
create index if not exists idx_alert_event_escalate
    on platform.alert_event (status, acked_at, escalated_at, created_at);
