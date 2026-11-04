package com.lab.dataworks.tablemanage;

import com.lab.dataworks.common.ApiException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris 字段类型解析 + 安全变更矩阵（P1）。
 *
 * <p>目标类型只允许平台建表白名单；源类型额外兼容 Doris 已有的
 * {@code DATEV2 / DATETIMEV2 / DECIMALV3} 写法。类型变更只放行「安全扩宽」：
 * 数值只增不减、DECIMAL 整数位不变、VARCHAR 只扩不缩、DATE → DATETIME。
 * 其余（缩窄、字符串 ↔ 数值、JSON ↔ 其他、NULL → NOT NULL）一律拒绝，
 * 并把原因写清楚，避免把 Doris 的英文报错直接甩给用户。</p>
 */
final class DorisType {

    enum Family { BOOLEAN, INT, FLOAT, DECIMAL, CHAR, DATE, DATETIME, STRING, JSON, UNKNOWN }

    private static final Pattern PATTERN =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\(\\s*([0-9]+)\\s*(?:,\\s*([0-9]+)\\s*)?\\))?$");

    private static final Set<String> WHITELIST = Set.of(
            "BOOLEAN", "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT",
            "FLOAT", "DOUBLE", "DECIMAL", "DATE", "DATETIME", "VARCHAR",
            "STRING", "TEXT", "JSON");

    final String base;
    /** 无参数时为 -1。 */
    final int precision;
    final int scale;
    final Family family;

    private DorisType(String base, int precision, int scale, Family family) {
        this.base = base;
        this.precision = precision;
        this.scale = scale;
        this.family = family;
    }

    /** 解析任意 Doris 类型写法；无法解析时返回 null。 */
    static DorisType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = PATTERN.matcher(raw.trim());
        if (!m.matches()) {
            return null;
        }
        String base = m.group(1).toUpperCase(Locale.ROOT);
        base = switch (base) {
            case "INTEGER" -> "INT";
            case "NUMERIC", "DECIMALV3" -> "DECIMAL";
            case "DATEV2" -> "DATE";
            case "DATETIMEV2" -> "DATETIME";
            case "CHARACTER", "CHAR" -> "CHAR";
            default -> base;
        };
        int precision = m.group(2) == null ? -1 : Integer.parseInt(m.group(2));
        int scale = m.group(3) == null ? -1 : Integer.parseInt(m.group(3));
        return new DorisType(base, precision, scale, familyOf(base));
    }

    private static Family familyOf(String base) {
        return switch (base) {
            case "BOOLEAN" -> Family.BOOLEAN;
            case "TINYINT", "SMALLINT", "INT", "BIGINT", "LARGEINT" -> Family.INT;
            case "FLOAT", "DOUBLE" -> Family.FLOAT;
            case "DECIMAL" -> Family.DECIMAL;
            case "CHAR", "VARCHAR" -> Family.CHAR;
            case "DATE" -> Family.DATE;
            case "DATETIME" -> Family.DATETIME;
            case "STRING", "TEXT" -> Family.STRING;
            case "JSON" -> Family.JSON;
            default -> Family.UNKNOWN;
        };
    }

    /** 解析目标类型并执行白名单/参数范围校验；不合法直接 400。 */
    static DorisType target(String raw) {
        DorisType type = parse(raw);
        if (type == null) {
            throw ApiException.badRequest("无法解析字段类型: " + raw);
        }
        if (!WHITELIST.contains(type.base)) {
            throw ApiException.badRequest("不支持的字段类型: " + raw
                    + "（平台只允许 BOOLEAN / 整数 / FLOAT / DOUBLE / DECIMAL(p,s) / DATE /"
                    + " DATETIME(p) / VARCHAR(n) / STRING / TEXT / JSON）");
        }
        switch (type.base) {
            case "DECIMAL" -> {
                if (type.precision < 1 || type.precision > 38 || type.scale < 0
                        || type.scale > type.precision) {
                    throw ApiException.badRequest("DECIMAL 参数不合法: " + raw
                            + "（p 取 1-38，s 取 0-p）");
                }
            }
            case "VARCHAR" -> {
                if (type.precision < 1 || type.precision > 65533) {
                    throw ApiException.badRequest("VARCHAR 长度需在 1-65533: " + raw);
                }
            }
            case "DATETIME" -> {
                if (type.precision != -1 && (type.precision < 0 || type.precision > 6)) {
                    throw ApiException.badRequest("DATETIME 精度需在 0-6: " + raw);
                }
            }
            default -> {
                if (type.precision != -1) {
                    throw ApiException.badRequest("类型 " + type.base + " 不接受参数: " + raw);
                }
            }
        }
        return type;
    }

    /** 规范化类型字符串（大写 + 紧凑括号），用于生成 DDL。 */
    String canonical() {
        return switch (base) {
            case "DECIMAL" -> "DECIMAL(" + precision + "," + scale + ")";
            case "VARCHAR" -> "VARCHAR(" + precision + ")";
            case "DATETIME" -> precision == -1 ? "DATETIME" : "DATETIME(" + precision + ")";
            default -> base;
        };
    }

    /**
     * 安全扩宽判定。返回 null 表示允许；否则返回给用户看的中文原因。
     */
    static String checkWiden(DorisType from, DorisType to) {
        if (from == null) {
            return "无法解析当前字段类型，P1 不允许修改";
        }
        if (from.family == Family.UNKNOWN) {
            return "当前字段类型 " + from.base + " 不在平台支持范围（ARRAY/MAP/STRUCT 等），"
                    + "P1 不允许修改";
        }
        // 注释 / NULL 变更时类型不变
        if (from.canonical().equals(to.canonical())) {
            return null;
        }
        if (from.family == to.family) {
            return switch (from.family) {
                case INT -> intRank(to.base) >= intRank(from.base)
                        ? null : "整数类型只能扩宽（例如 INT → BIGINT），不能缩窄";
                case FLOAT -> "FLOAT" .equals(from.base) && "DOUBLE".equals(to.base)
                        ? null : "浮点类型只能 FLOAT → DOUBLE";
                case DECIMAL -> decimalWiden(from, to);
                case CHAR -> from.base.equals("CHAR")
                        ? ("VARCHAR".equals(to.base) && to.precision >= from.precision
                        ? null : "CHAR 只能安全扩宽为更长的 VARCHAR")
                        : (to.precision >= from.precision
                        ? null : "VARCHAR 只能扩宽，不能缩小长度");
                case DATE, DATETIME, STRING, JSON, BOOLEAN ->
                        "该类型不支持改变定义（仅注释/NULL 可改）";
                default -> "不支持的类型变更";
            };
        }
        if (from.family == Family.DATE && to.family == Family.DATETIME) {
            return null;
        }
        return "不允许的类型转换: " + from.base + " → " + to.base
                + "（P1 只放行安全扩宽：整数升位、FLOAT→DOUBLE、DECIMAL/VARCHAR 扩宽、"
                + "DATE→DATETIME）";
    }

    private static String decimalWiden(DorisType from, DorisType to) {
        if (to.precision < from.precision) {
            return "DECIMAL 总精度不能缩小";
        }
        int fromInt = from.precision - Math.max(from.scale, 0);
        int toInt = to.precision - Math.max(to.scale, 0);
        if (toInt < fromInt) {
            return "DECIMAL 整数位不能减少（原 " + fromInt + " 位，目标 " + toInt + " 位）";
        }
        return null;
    }

    private static int intRank(String base) {
        return switch (base) {
            case "TINYINT" -> 1;
            case "SMALLINT" -> 2;
            case "INT" -> 3;
            case "BIGINT" -> 4;
            case "LARGEINT" -> 5;
            default -> 0;
        };
    }

    /** 判断一个默认值字面量能否按该类型解析。 */
    boolean acceptsDefault(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String v = value.trim();
        try {
            return switch (family) {
                case BOOLEAN -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")
                        || v.equals("0") || v.equals("1");
                case INT -> {
                    long n = Long.parseLong(v);
                    yield switch (base) {
                        case "TINYINT" -> n >= -128 && n <= 127;
                        case "SMALLINT" -> n >= -32768 && n <= 32767;
                        case "INT" -> n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE;
                        default -> true;
                    };
                }
                case FLOAT -> {
                    Double.parseDouble(v);
                    yield true;
                }
                case DECIMAL -> {
                    new java.math.BigDecimal(v);
                    yield true;
                }
                case DATE -> v.matches("\\d{4}-\\d{2}-\\d{2}") || v.matches("\\d{8}");
                case DATETIME -> v.matches("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}:\\d{2}(\\.[0-9]{1,6})?)?");
                case CHAR -> v.length() <= (precision < 0 ? 65533 : precision);
                default -> true;
            };
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 默认值是否是非确定函数（Doris 对存量行要求常量）。 */
    static boolean isNonDeterministicDefault(String value) {
        if (value == null) {
            return false;
        }
        String v = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return v.startsWith("now(") || v.startsWith("current_timestamp")
                || v.startsWith("uuid(") || v.startsWith("rand(")
                || v.startsWith("random(") || v.startsWith("unix_timestamp(");
    }
}
