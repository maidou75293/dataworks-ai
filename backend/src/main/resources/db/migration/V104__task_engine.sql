-- V104：脚本引擎 type+engine 收口（2026-10-26）
--
-- 背景：脚本引擎是节点模型里的可选字段（tasks[].engine: DORIS / SPARK / FLINK）。
-- 老模型只有 datasource:'DORIS' 标记，没有 engine；运行期按 DORIS 兜底。
-- 为了让「引擎创建后只读」「按引擎校验/试跑」有确定的数据基础，这里把存量
-- SQL 任务的 engine 显式补成 'DORIS'。
--
-- 口径：
--   * 只补 SQL 任务（type 缺省按 SQL 处理，与 WorkflowModelRules / ScriptEngine 一致）；
--   * 只补 engine 缺失 / JSON null / 空串的任务；SPARK / FLINK 等已有值一律不覆盖；
--   * 保留 tasks 数组顺序与其他字段（t || '{"engine":"DORIS"}' 只新增键）；
--   * model_json 在 workflow 与 workflow_version 两张表都是 jsonb；
--   * 幂等：补完之后不再满足 exists 条件，重复执行不会改任何行。

update platform.workflow w
   set model_json = jsonb_set(
           w.model_json,
           '{tasks}',
           (
               select jsonb_agg(
                          case
                              when jsonb_typeof(t) = 'object'
                               and coalesce(upper(t ->> 'type'), 'SQL') = 'SQL'
                               and coalesce(t ->> 'engine', '') = ''
                              then t || '{"engine":"DORIS"}'::jsonb
                              else t
                          end
                          order by ord
                      )
                 from jsonb_array_elements(w.model_json -> 'tasks')
                      with ordinality as x(t, ord)
           ),
           false)
 where jsonb_typeof(w.model_json -> 'tasks') = 'array'
   and exists (
       select 1
         from jsonb_array_elements(w.model_json -> 'tasks') t
        where jsonb_typeof(t) = 'object'
          and coalesce(upper(t ->> 'type'), 'SQL') = 'SQL'
          and coalesce(t ->> 'engine', '') = ''
   );

update platform.workflow_version v
   set model_json = jsonb_set(
           v.model_json,
           '{tasks}',
           (
               select jsonb_agg(
                          case
                              when jsonb_typeof(t) = 'object'
                               and coalesce(upper(t ->> 'type'), 'SQL') = 'SQL'
                               and coalesce(t ->> 'engine', '') = ''
                              then t || '{"engine":"DORIS"}'::jsonb
                              else t
                          end
                          order by ord
                      )
                 from jsonb_array_elements(v.model_json -> 'tasks')
                      with ordinality as x(t, ord)
           ),
           false)
 where jsonb_typeof(v.model_json -> 'tasks') = 'array'
   and exists (
       select 1
         from jsonb_array_elements(v.model_json -> 'tasks') t
        where jsonb_typeof(t) = 'object'
          and coalesce(upper(t ->> 'type'), 'SQL') = 'SQL'
          and coalesce(t ->> 'engine', '') = ''
   );
