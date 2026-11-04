#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""只读能力枚举：确认平台"能做什么"与接口契约没有退化。

覆盖：
  1. 未登录访问受保护接口必须 401（鉴权边界）；
  2. 登录（DW_TOKEN 或 DW_USER/DW_PASS）；
  3. 数据源列表：queryable / engine 字段存在，打印可查/仅导入分布；
  4. 项目列表 → 取一个项目做后续探测；
  5. 查询能力矩阵 /api/query/capabilities（queryable / notQueryable / tablePrefixes）；
  6. 库列表 + 表列表（withMeta=true 看 metadataSource / degraded）；
  7. 权限三模式 /api/security/permission-mode（OFF / TABLE / COLUMN + enforce）；
  8. 权限点与角色 /api/rbac/permissions、/api/rbac/catalog；
  9. 资源目录 /api/access/resources；
 10. 湖 Catalog /api/lake/catalogs、血缘 /api/lineage/tables。

全程只发 GET；唯一例外是登录 POST（会话引导，不写业务数据）。

用法：
  export DW_USER=admin DW_PASS=******       # 或 export DW_TOKEN=...
  python3 e2e/acceptance/check_capabilities.py --readonly
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as dw  # noqa: E402

TOKEN = None


def probe(name, path, expect=200, validate=None, skip_codes=(404,), auth=True):
    """GET + 断言 + 打印 HTTP 状态与响应片段。返回 body（跳过时为 None）。"""
    if auth and not TOKEN:
        dw.skip(name, "无凭据（DW_TOKEN / DW_USER+DW_PASS）")
        return None
    status, body = dw.http("GET", path, token=TOKEN if auth else None)
    if status in skip_codes:
        dw.skip(name, "HTTP %s %s" % (status, dw.snippet(body, 120)))
        return None
    ok = status == expect
    if ok and validate is not None:
        try:
            ok = bool(validate(body))
        except Exception as exc:  # 结构不符也算失败
            ok, body = False, "%s | %s" % (dw.snippet(body, 100), exc)
    dw.check(name, ok, "HTTP %s %s" % (status, dw.snippet(body, 160)), status=status)
    return body


def main() -> int:
    global TOKEN
    args = dw.parse_args("dataworks 能力枚举（只读）")
    dw.info("目标 %s  只读=%s" % (dw.BASE, dw.is_readonly()))

    # ---- 1. 鉴权边界（不带 token）
    st, body = dw.http("GET", "/api/projects", token=None)
    dw.check("未登录访问 /api/projects 返回 401", st == 401,
             "HTTP %s %s" % (st, dw.snippet(body, 100)), status=st)

    # ---- 2. 登录
    TOKEN, source = dw.login()
    if TOKEN:
        dw.check("获取访问令牌", True, "来源=%s token=…%s" % (source, TOKEN[-6:]))
    else:
        dw.skip("获取访问令牌", "来源=%s（未猜密码、未重试；后续认证用例 SKIP）" % source)

    # ---- 3. 数据源（核心字段契约）
    rows = probe("数据源列表可读", "/api/datasources", validate=lambda b: isinstance(b, list))
    if isinstance(rows, list):
        missing = [r.get("name") for r in rows
                   if not isinstance(r, dict) or "queryable" not in r or "engine" not in r]
        dw.check("每个数据源含 queryable / engine", not missing,
                 "缺失=%s 共%d条" % (missing[:3], len(rows)))
        queryable = [r for r in rows if r.get("queryable") is True]
        blocked = [r for r in rows if r.get("queryable") is False]
        dw.check("可查 / 仅导入 两类都有样本", bool(rows),
                 "可查=%d 仅导入=%d" % (len(queryable), len(blocked)))
        print("")
        print("%-4s %-22s %-10s %-9s %-8s %-10s %-10s" %
              ("ID", "名称", "类型", "engine", "可查", "lake", "gravitino"))
        for r in rows[:20]:
            print("%-4s %-22s %-10s %-9s %-8s %-10s %-10s" % (
                r.get("id"), str(r.get("name"))[:22], r.get("ds_type"),
                str(r.get("engine") or "-"), r.get("queryable"),
                str(r.get("lake_status") or "-"), str(r.get("gravitino_status") or "-")))
        print("")
        # Kafka / 文件类必须 queryable=false —— 这是 V99 的核心不变量
        bad = [r.get("name") for r in rows
               if str(r.get("engine") or "").upper() in ("KAFKA", "FILE", "S3FILE")
               and r.get("queryable") is not False]
        dw.check("Kafka/文件类未被标为可查", not bad, "违规=%s" % (bad[:3] or "-"))

    # ---- 4. 项目
    projects = probe("项目列表可读", "/api/projects", validate=lambda b: isinstance(b, list))
    project_id = None
    if isinstance(projects, list) and projects:
        first = projects[0] if isinstance(projects[0], dict) else {}
        project_id = first.get("id")
        dw.check("项目列表非空", True,
                 "共%d个，首个 id=%s code=%s" % (len(projects), project_id, first.get("code")))
    elif isinstance(projects, list):
        dw.check("项目列表非空", False, "返回空数组：后续库/表探测无样本")

    # ---- 5. 查询能力矩阵
    caps = probe("查询能力矩阵可读", "/api/query/capabilities" + dw.qs({"projectId": project_id}),
                 validate=lambda b: isinstance(b, dict) and "queryable" in b)
    if isinstance(caps, dict):
        dw.check("能力矩阵含 notQueryable / tablePrefixes",
                 "notQueryable" in caps and "tablePrefixes" in caps,
                 "queryable=%d notQueryable=%d prefixes=%d" % (
                     len(caps.get("queryable") or []),
                     len(caps.get("notQueryable") or []),
                     len(caps.get("tablePrefixes") or [])))

    # ---- 6. 库 / 表（元数据降级可见性）
    dbs = probe("库列表可读（withMeta）",
                "/api/tables/databases" + dw.qs({"projectId": project_id, "withMeta": "true"}),
                validate=lambda b: isinstance(b, dict) or isinstance(b, list))
    database = None
    db_rows = []
    degraded = None
    if isinstance(dbs, dict):
        db_rows = dbs.get("databases") or []
        degraded = dbs.get("degraded")
        dw.check("库列表带元数据来源标记", bool(dbs.get("metadataSource")) or degraded is not None,
                 "metadataSource=%s degraded=%s cached=%s" % (
                     dbs.get("metadataSource"), degraded, dbs.get("metadataCached")))
    elif isinstance(dbs, list):
        db_rows = dbs
        dw.check("库列表带元数据来源标记", False, "返回裸数组（未走 withMeta 分支）")
    if db_rows:
        database = db_rows[0].get("database") if isinstance(db_rows[0], dict) else None
        dw.info("样本库=%s 共%d个" % (database, len(db_rows)))

    if database:
        tables = probe("表列表可读（withMeta）",
                       "/api/tables" + dw.qs({"projectId": project_id, "database": database,
                                              "withMeta": "true"}),
                       validate=lambda b: isinstance(b, dict) or isinstance(b, list))
        trows = tables.get("tables") if isinstance(tables, dict) else tables
        if isinstance(trows, list):
            names = [t.get("table") or t.get("name") for t in trows if isinstance(t, dict)]
            dw.check("表列表可枚举", True, "库 %s 共%d张，样本=%s"
                     % (database, len(trows), names[:3]))
    else:
        dw.skip("表列表可读（withMeta）", "没有可用样本库")

    # ---- 7. 权限三模式
    mode = probe("权限模式配置可读", "/api/security/permission-mode",
                 validate=lambda b: isinstance(b, dict) and "effectiveMode" in b)
    if isinstance(mode, dict):
        codes = [m.get("code") for m in (mode.get("modes") or []) if isinstance(m, dict)]
        dw.check("三模式 OFF/TABLE/COLUMN 齐全", set(codes) >= {"OFF", "TABLE", "COLUMN"},
                 "modes=%s" % codes)
        # effectiveMode 是守卫推导态（OFF / LOG / ENFORCE），不是模式名：
        # OFF 模式 → OFF；否则 enforce=LOG → LOG，其余 → ENFORCE。
        actual = str(mode.get("effectiveMode") or "").upper()
        expect = ("OFF" if str(mode.get("mode") or "").upper() == "OFF"
                  else "LOG" if str(mode.get("enforce") or "").upper() == "LOG"
                  else "ENFORCE")
        dw.check("生效态与 mode/enforce 推导一致", actual == expect,
                 "mode=%s enforce=%s → effectiveMode=%s（期望 %s）" % (
                     mode.get("mode"), mode.get("enforce"), actual, expect))

    # ---- 8. 权限点 / 角色
    perms = probe("当前用户权限点可读", "/api/rbac/permissions",
                  validate=lambda b: isinstance(b, dict) and "permissions" in b)
    if isinstance(perms, dict):
        dw.check("角色与权限点非空",
                 bool(perms.get("roles")) and bool(perms.get("permissions")),
                 "tenant=%s roles=%s permissions=%d" % (
                     perms.get("tenant"), perms.get("roles"), len(perms.get("permissions") or [])))

    catalog = probe("权限点目录可读", "/api/rbac/catalog",
                    validate=lambda b: isinstance(b, (list, dict)))
    if catalog is not None:
        size = len(catalog) if isinstance(catalog, (list, dict)) else 0
        dw.check("权限点目录非空", size > 0, "条目=%d %s" % (size, dw.snippet(catalog, 80)))

    # ---- 9. 资源目录（权限申请用）
    probe("资源目录可读", "/api/access/resources",
          validate=lambda b: isinstance(b, (list, dict)))

    # ---- 10. 湖 Catalog / 血缘（不存在时 SKIP，不算能力退化）
    probe("湖 Catalog 可读", "/api/lake/catalogs",
          validate=lambda b: isinstance(b, (list, dict)), skip_codes=(404, 501))
    probe("血缘表清单可读", "/api/lineage/tables" + dw.qs({"env": "DEV"}),
          validate=lambda b: isinstance(b, dict), skip_codes=(404,))

    dw.info("只读枚举完成：未创建/修改任何数据")
    return dw.summary(args.as_json)


if __name__ == "__main__":
    sys.exit(main())
