package com.lab.dataworks.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TC7-G1：内置监控规则模板清单（对标 DataWorks「内置监控规则模板」）。
 *
 * <p>只读静态清单，不落库、不随租户变化：前端「新建规则」按模板一键带出
 * 类型/字段/配置，用户改完表名再走现有创建接口——「按模板批量下发」的
 * 模板与表的绑定仍在调用方（验收契约 docs/测试-TC7-G1.md §五）。
 * 每项 config 必须能通过 {@link QualityService#validateConfig}（有单测钉住，
 * 防止模板改了口径、创建却被自己拦掉）。</p>
 */
public final class QualityRuleTemplates {

    private QualityRuleTemplates() {
    }

    public static List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(template("表行数大于 0", "ROW_COUNT", false, null,
                "表当天至少有一行数据；适合所有日增表的「有没有到数」检查",
                Map.of("min", 1)));
        out.add(template("表行数 1 天波动率", "VOLATILITY", false, null,
                "行数相对 1 天前的变化幅度超过 ±30% 不通过；基线取本规则前一天的执行记录",
                Map.of("windows", List.of(1), "maxChangeRate", 0.3)));
        out.add(template("表行数 7 天波动率", "VOLATILITY", false, null,
                "行数相对 7 天前的变化幅度超过 ±30% 不通过",
                Map.of("windows", List.of(7), "maxChangeRate", 0.3)));
        out.add(template("表行数 30 天波动率", "VOLATILITY", false, null,
                "行数相对 30 天前的变化幅度超过 ±30% 不通过",
                Map.of("windows", List.of(30), "maxChangeRate", 0.3)));
        out.add(template("表行数 1/7/30 天波动率（组合）", "VOLATILITY", false, null,
                "对标 DataWorks「表行数，1、7、30 天波动率」：任一窗口超限即不通过",
                Map.of("windows", List.of(1, 7, 30), "maxChangeRate", 0.3)));
        out.add(template("字段空值率不高于 5%", "NULL_RATE", true, null,
                "指定字段的 NULL 占比超过 5% 不通过",
                Map.of("max", 0.05)));
        out.add(template("字段值唯一", "UNIQUE", true, null,
                "指定字段出现重复值即不通过（NULL 不参与判重）",
                Map.of("max", 0)));
        out.add(template("字段值域下限", "RANGE", true, null,
                "字段值小于下限即不通过；上限可在配置里补 max",
                Map.of("min", 0)));
        out.add(template("格式校验：手机号", "REGEX", true, null,
                "不匹配中国大陆手机号格式的行数 > 0 不通过（NULL 不计入）",
                Map.of("pattern", "^1[3-9]\\d{9}$", "maxBad", 0)));
        out.add(template("格式校验：邮箱", "REGEX", true, null,
                "常见邮箱格式校验；可按业务放宽",
                Map.of("pattern", "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", "maxBad", 0)));
        out.add(template("格式校验：身份证号", "REGEX", true, null,
                "18 位身份证号（末位支持 X）；出生日期合法性请另配自定义 SQL",
                Map.of("pattern", "^\\d{17}[0-9Xx]$", "maxBad", 0)));
        out.add(template("枚举值不匹配行数", "ENUM", true, null,
                "值不在白名单内的行数 > 0 不通过；allowed 请按业务改（如订单状态）",
                Map.of("allowed", List.of("DEV", "PROD"), "maxBad", 0)));
        out.add(template("离散值（分组个数）", "DISCRETE", true, null,
                "字段 distinct 值个数应落在 [min,max]；状态种类突然变多/变少都能兜住",
                Map.of("min", 1, "max", 100)));
        out.add(template("数据新鲜度（当天有更新）", "FRESHNESS", true, null,
                "业务日期当天至少有 1 行新数据；dateColumn 可在配置里覆盖字段名",
                Map.of("min", 1)));
        out.add(template("自定义 SQL 哨兵值", "CUSTOM_SQL", false, null,
                "跑一条只读 SQL 与期望值严格相等（老语义不变）；示例 select 1 = 1",
                Map.of("sql", "select 1", "expect", 1)));
        return List.copyOf(out);
    }

    private static Map<String, Object> template(String name, String ruleType, boolean columnRequired,
                                                 String columnName, String description,
                                                 Map<String, Object> config) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("ruleType", ruleType);
        item.put("columnRequired", columnRequired);
        item.put("columnName", columnName);
        item.put("description", description);
        item.put("config", config);
        return item;
    }
}
