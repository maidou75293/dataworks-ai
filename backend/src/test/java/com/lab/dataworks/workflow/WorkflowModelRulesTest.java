package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 保存路径的引擎约束：必填、FLINK 不绑源、创建后只读。 */
class WorkflowModelRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static Map<String, Object> task(String code, String type, Object engine) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("code", code);
        task.put("name", code);
        task.put("type", type);
        if (engine != null) {
            task.put("engine", engine);
        }
        return task;
    }

    @Test
    void oldTaskWithoutEngineIsStillAllowed() {
        assertDoesNotThrow(() -> WorkflowModelRules.validateEngines(
                List.of(task("t1", "SQL", null))));
    }

    @Test
    void explicitNullOrBlankEngineIsRejected() {
        Map<String, Object> explicitNull = task("t1", "SQL", null);
        explicitNull.put("engine", null);
        ApiException nullError = assertThrows(ApiException.class,
                () -> WorkflowModelRules.validateEngines(List.of(explicitNull)));
        assertEquals(400, nullError.getStatus());
        assertTrue(nullError.getMessage().contains("不能为空"));

        ApiException blankError = assertThrows(ApiException.class,
                () -> WorkflowModelRules.validateEngines(
                        List.of(task("t2", "SQL", "   "))));
        assertEquals(400, blankError.getStatus());
        assertTrue(blankError.getMessage().contains("DORIS"));
    }

    @Test
    void illegalEngineIsRejectedAtSaveTime() {
        ApiException error = assertThrows(ApiException.class,
                () -> WorkflowModelRules.validateEngines(List.of(task("t1", "SQL", "PRESTO"))));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不支持的执行引擎"));
    }

    @Test
    void flinkCannotBindDatasource() {
        Map<String, Object> flink = task("t1", "SQL", "FLINK");
        flink.put("datasourceId", 9);
        ApiException error = assertThrows(ApiException.class,
                () -> WorkflowModelRules.validateEngines(List.of(flink)));
        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("不支持绑定数据源"));

        // SHELL 节点没有引擎语义，不拦
        Map<String, Object> shell = task("t2", "SHELL", null);
        shell.put("datasourceId", 9);
        assertDoesNotThrow(() -> WorkflowModelRules.validateEngines(List.of(shell)));
    }

    @Test
    void changingEngineAfterCreationIsRejected() {
        String stored = "{\"tasks\":[{\"code\":\"t1\",\"type\":\"SQL\",\"engine\":\"DORIS\"}]}";
        Object incoming = Map.of("tasks", List.of(task("t1", "SQL", "FLINK")));

        ApiException error = assertThrows(ApiException.class,
                () -> WorkflowModelRules.ensureEnginesUnchanged(mapper, stored, incoming));

        assertEquals(400, error.getStatus());
        assertTrue(error.getMessage().contains("执行引擎创建后不可修改"));
        assertTrue(error.getMessage().contains("DORIS"));
        assertTrue(error.getMessage().contains("FLINK"));
    }

    @Test
    void legacyMissingEngineMatchesExplicitDoris() {
        String stored = "{\"tasks\":[{\"code\":\"t1\",\"type\":\"SQL\"}]}";
        Object incoming = Map.of("tasks", List.of(task("t1", "SQL", "DORIS")));
        assertDoesNotThrow(() -> WorkflowModelRules.ensureEnginesUnchanged(
                mapper, stored, incoming));
    }

    @Test
    void typeChangeAndNewTasksAreAllowed() {
        String stored = "{\"tasks\":[{\"code\":\"t1\",\"type\":\"SQL\",\"engine\":\"FLINK\"}]}";
        // 改成 SHELL 后引擎字段不再适用
        Object changedType = Map.of("tasks", List.of(task("t1", "SHELL", null)));
        assertDoesNotThrow(() -> WorkflowModelRules.ensureEnginesUnchanged(
                mapper, stored, changedType));
        // 新任务带自己的引擎
        Object newTask = Map.of("tasks", List.of(
                task("t1", "SQL", "FLINK"), task("t2", "SQL", "SPARK")));
        assertDoesNotThrow(() -> WorkflowModelRules.ensureEnginesUnchanged(
                mapper, stored, newTask));
    }

    @Test
    void unreadableStoredModelDoesNotBlockSave() {
        assertDoesNotThrow(() -> WorkflowModelRules.ensureEnginesUnchanged(
                mapper, "not-json", Map.of("tasks", List.of())));
    }
}
