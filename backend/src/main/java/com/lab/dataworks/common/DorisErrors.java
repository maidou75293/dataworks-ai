package com.lab.dataworks.common;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris / JDBC 报错的统一映射（D58）。
 *
 * <p>改之前每个入口各写一份：{@code DorisAdminService} 一律抛 502 + 原文
 * （{@code "Doris 执行失败: errCode = 2, detailMessage = Unknown table 'x' in 'db'"}），
 * 表管理里 {@code tablesDirect / detailDirect} 又各自 {@code message.contains("Unknown table")}
 * 判一遍。「表不存在」是调用方能自己改对的问题，报 502 会让前端只会显示「服务异常」，
 * 而原文还把内部库名、SQL 片段甚至账号信息一并回显出去。</p>
 *
 * <p>这里只保留一份判定：{@link #classify(Throwable)} 按 SQLState 优先、关键字兜底；
 * {@link #toApiException(Throwable, String)} 给出归一化状态码与人话；
 * 原文只交给 {@link #raw(Throwable)} 写日志与审计。</p>
 */
public final class DorisErrors {

    /** 失败类别；每个类别对应一个 HTTP 状态码（{@link #httpStatus(Cause)}）。 */
    public enum Cause {
        /** 连不上 / 超时前就断了：调用方重试有可能变好，保持 502。 */
        UNREACHABLE,
        /** 数据源执行超时。 */
        TIMEOUT,
        /** 账号权限不足 → 403。 */
        DENIED,
        /** 库 / 表 / 列不存在 → 404。 */
        MISSING_OBJECT,
        /** 对象已存在（重复建）→ 409。 */
        ALREADY_EXISTS,
        /** SQL 语法或该引擎不支持的写法 → 400。 */
        BAD_SQL,
        /** 兜不住的内部错误 → 502，但不带原文。 */
        INTERNAL,
    }

    /** {@code Unknown table 't' in 'db'} / {@code Unknown table 't' in db}，库名单独捕获。 */
    private static final Pattern UNKNOWN_TABLE = Pattern.compile(
            "(?i)unknown table '([A-Za-z0-9_]+)'(?: in '?([A-Za-z0-9_.]+)'?)?");
    private static final Pattern UNKNOWN_DATABASE = Pattern.compile(
            "(?i)unknown database '?([A-Za-z0-9_]+)'?");
    private static final Pattern PG_MISSING = Pattern.compile(
            "(?i)(?:relation|table|missing relation) \"?([A-Za-z0-9_.\"]+)\"? (?:does not exist|doesn't exist)");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.]{0,127}");

    private DorisErrors() {
    }

    /**
     * 把 Doris / 驱动异常翻译成状态码正确的 {@link ApiException}。
     *
     * <p>已经是 {@code ApiException} 且消息不是引擎原文的（例如我们自己的 403「无权操作该库」、
     * 409「已被其他项目占用」），原样放回，不能被这里改写。</p>
     *
     * @param action 人话描述这次在干什么，例如「删除表 demo_retail.ods_order」
     */
    public static ApiException toApiException(Throwable error, String action) {
        return toApiException(error, action, null);
    }

    /**
     * @param missingLabel 对象不存在时的完整说法（如「表不存在: demo_retail.ods_order」）。
     *                     调用方知道自己操作的是表还是库，比从报错文本里猜准；
     *                     传 null 时退化成从报错里抽出的对象名。
     */
    public static ApiException toApiException(Throwable error, String action, String missingLabel) {
        if (error instanceof ApiException e && !isEngineText(e.getMessage())) {
            return e;
        }
        Cause cause = classify(error);
        String object = missingLabel != null ? missingLabel : derivedLabel(error);
        return new ApiException(httpStatus(cause), message(cause, object, action));
    }

    /** 从报错里抽对象名，拼成「表不存在: x」/「库不存在: x」/「对象不存在: x」。 */
    private static String derivedLabel(Throwable error) {
        String hint = objectHint(error);
        if (hint == null) {
            return null;
        }
        if (UNKNOWN_DATABASE.matcher(haystack(error)).find()
                && !UNKNOWN_TABLE.matcher(haystack(error)).find()) {
            return "库不存在: " + hint;
        }
        return hint.contains(".") ? "表不存在: " + hint : "对象不存在: " + hint;
    }

    /** 状态码口径：客户端能自己改对的一律 4xx，只有平台侧不可用才是 5xx。 */
    public static int httpStatus(Cause cause) {
        return switch (cause) {
            case MISSING_OBJECT -> 404;
            case DENIED -> 403;
            case BAD_SQL -> 400;
            case ALREADY_EXISTS -> 409;
            case TIMEOUT -> 504;
            case UNREACHABLE, INTERNAL -> 502;
        };
    }

    /** SQLState 优先，其次整条异常链里的关键字。 */
    public static Cause classify(Throwable error) {
        String state = sqlState(error);
        String text = haystack(error);
        if (contains(text, "connection refused", "communications link failure", "connect timed out",
                "connection timed out", "no route to host", "unknown host", "could not open connection",
                "failed to obtain connection", "unable to open jdbc connection", "connection is not available",
                "no beacon", "received fatal error") || state.startsWith("08")) {
            return Cause.UNREACHABLE;
        }
        if (contains(text, "access denied", "permission denied", "not authorized",
                "authorization failed", "does not have privilege", "erraccess", "no privileges",
                "password authentication failed") || state.startsWith("28") || state.equals("42501")) {
            return Cause.DENIED;
        }
        if (contains(text, "timeout exceeded", "exceeded timeout", "query timeout", "sockettimeout",
                "canceling statement due to", "err_timeout") || state.equals("57014")) {
            return Cause.TIMEOUT;
        }
        if (contains(text, "unknown table", "unknown database", "does not exist", "doesn't exist",
                "table not found", "no such table", "undefined_table", "errbadtable")
                || state.equals("42p01") || state.startsWith("3f0") || state.equals("42s02")) {
            return Cause.MISSING_OBJECT;
        }
        if (contains(text, "already exists", "err_duplicate", "duplicate table", "has been created")) {
            return Cause.ALREADY_EXISTS;
        }
        if (contains(text, "syntax error", "err_parse", "parse syntax", "bad sql grammar",
                "getting analyzing error", "unknown column", "invalid column", "mismatched input")
                || state.equals("42601") || state.equals("1054") || state.equals("42s22")) {
            return Cause.BAD_SQL;
        }
        return Cause.INTERNAL;
    }

    /** 归一化对外文案：说清「哪里不行 + 该怎么做」，绝不带原文。 */
    private static String message(Cause cause, String object, String action) {
        String what = action == null || action.isBlank() ? "这次操作" : action;
        return switch (cause) {
            case UNREACHABLE -> "Doris 连不上，" + what + " 没能执行；请稍后重试，或检查数仓连接是否可用";
            case TIMEOUT -> "Doris 执行超时，" + what + " 未完成；请缩小范围（加分区条件 / 少列）后重试";
            case DENIED -> "账号权限不足，" + what + " 被拒绝"
                    + (object == null ? "" : "（对象 " + object + "）")
                    + "；需要该库表的相应权限后重试";
            case MISSING_OBJECT -> (object == null ? "要操作的对象不存在" : object)
                    + "，" + what + " 没有执行；请确认库名与表名（表还没建请先在表管理里建表）";
            case ALREADY_EXISTS -> "对象" + (object == null ? "" : " " + object)
                    + " 已存在，" + what + " 没有执行；请换个名字，或先确认这个库/表是不是别人的";
            case BAD_SQL -> "这条 SQL 被 Doris 拒绝（语法或该引擎不支持的写法），" + what + " 没有执行；请检查语句";
            case INTERNAL -> "Doris 执行失败，" + what + " 没有完成（内部错误，已记录日志，请联系平台管理员）";
        };
    }

    /**
     * 从报错里抽对象名（只取白名单字符）：404 的响应要能写「表不存在: db.t」，
     * 但不能把整条 detailMessage 原样搬出去。
     */
    public static String objectHint(Throwable error) {
        String text = haystack(error);
        Matcher table = UNKNOWN_TABLE.matcher(text);
        if (table.find()) {
            String database = table.group(2) != null ? safe(table.group(2)) : null;
            if (database == null) {
                Matcher unknownDb = UNKNOWN_DATABASE.matcher(text);
                database = unknownDb.find() ? safe(unknownDb.group(1)) : null;
            }
            return database == null ? safe(table.group(1)) : database + "." + safe(table.group(1));
        }
        Matcher database = UNKNOWN_DATABASE.matcher(text);
        if (database.find()) {
            return safe(database.group(1));
        }
        Matcher pg = PG_MISSING.matcher(text);
        if (pg.find()) {
            return safe(pg.group(1).replace("\"", ""));
        }
        return null;
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return SAFE_NAME.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 是不是引擎原文（用来决定 ApiException 要不要被改写）。 */
    private static boolean isEngineText(String message) {
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.ROOT);
        return text.contains("doris 执行失败") || text.contains("doris 查询失败")
                || text.contains("doris 执行失败（外部连接") || text.contains("errcode")
                || text.contains("detailmessage") || text.contains("bad sql grammar")
                || text.contains("statementcallback") || text.contains("连接 doris 失败");
    }

    /** 写日志/审计用的原文；**不允许进响应**。 */
    public static String raw(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            text.append(current.getClass().getSimpleName()).append(": ")
                    .append(current.getMessage() == null ? "" : current.getMessage()).append("; ");
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }

    private static String haystack(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                text.append(current.getMessage().toLowerCase(Locale.ROOT)).append(' ');
            }
            text.append(current.getClass().getName().toLowerCase(Locale.ROOT)).append(' ');
            if (current.getCause() == current) {
                break;
            }
        }
        return text.toString();
    }

    private static String sqlState(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && !state.isBlank()) {
                    return state.toLowerCase(Locale.ROOT);
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return "";
    }

    private static boolean contains(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
