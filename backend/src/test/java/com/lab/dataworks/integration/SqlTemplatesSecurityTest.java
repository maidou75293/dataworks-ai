package com.lab.dataworks.integration;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTemplatesSecurityTest {

    private Map<String, Object> task(String query, List<Map<String, Object>> params) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("query", query);
        source.put("params", params);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("sourceConfig", source);
        return task;
    }

    @Test
    void unquotedStringParameterIsQuotedAndEscaped() {
        Map<String, Object> task = task(
                "select * from users where name = ${name}",
                List.of(Map.of("name", "name", "type", "string", "required", true)));

        String sql = SqlTemplates.apply(task, Map.of("name", "x' or '1'='1"))
                .sqls().get(0);

        assertTrue(sql.contains("name = 'x'' or ''1''=''1'"), sql);
        assertFalse(sql.contains("name = x'"), sql);
    }

    @Test
    void stringParameterInsideLiteralIsNotDoubleQuoted() {
        Map<String, Object> task = task(
                "select * from users where name = '${name}'",
                List.of(Map.of("name", "name", "type", "string", "required", true)));

        String sql = SqlTemplates.apply(task, Map.of("name", "O'Brien"))
                .sqls().get(0);

        assertTrue(sql.contains("name = 'O''Brien'"), sql);
        assertFalse(sql.contains("''O"), sql);
    }

    @Test
    void numericParameterStaysUnquoted() {
        Map<String, Object> task = task(
                "select * from users limit ${n}",
                List.of(Map.of("name", "n", "type", "int", "required", true)));

        String sql = SqlTemplates.apply(task, Map.of("n", 10)).sqls().get(0);
        assertTrue(sql.endsWith("limit 10"), sql);
    }

    @Test
    void readOnlyGuardRejectsWritesAndFilesystemFunctions() {
        assertThrows(ApiException.class,
                () -> SqlTemplates.guardReadOnly("select * into stolen from users"));
        assertThrows(ApiException.class,
                () -> SqlTemplates.guardReadOnly("select pg_sleep(10)"));
        assertThrows(ApiException.class,
                () -> SqlTemplates.guardReadOnly("select pg_read_file('/etc/passwd')"));
        assertThrows(ApiException.class,
                () -> SqlTemplates.guardReadOnly("with x as (delete from t returning *) select * from x"));
        assertThrows(ApiException.class,
                () -> SqlTemplates.guardReadOnly("select 1; select 2"));
    }

    @Test
    void readOnlyGuardRejectsDialectEscapeBypasses() {
        // 双引号包住的函数名：strip 后仍要能看到
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select \"pg_read_file\"('/etc/passwd')"));
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select \"setval\"('platform.workflow_id_seq', 1, false)"));
        // MySQL 要求 -- 后带空白才是注释；不带空白时后面的分号不能藏
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select 1--1; delete from t"));
        // PG 标准字符串里反斜杠不转义引号；按 MySQL 解释会吞掉第二条语句
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select * from t where c = 'a\\' ; delete from t --'"));
        // 执行型注释
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select 1 /*!50000 INTO OUTFILE '/tmp/x' */"));
        assertThrows(ApiException.class, () -> SqlTemplates.guardReadOnly(
                "select 1 into outfile '/tmp/x'"));
    }

    @Test
    void readOnlyGuardStillAllowsNormalCommentsAndLiterals() {
        SqlTemplates.guardReadOnly("select * from t -- note; ok");
        SqlTemplates.guardReadOnly("select 'sleep(1)' as note from t");
        SqlTemplates.guardReadOnly("select \"update\" from t");
    }

    @Test
    void readOnlyGuardAllowsSelectAndWith() {
        SqlTemplates.guardReadOnly("select * from users where id = 1");
        SqlTemplates.guardReadOnly("with x as (select 1) select * from x");
    }
}
