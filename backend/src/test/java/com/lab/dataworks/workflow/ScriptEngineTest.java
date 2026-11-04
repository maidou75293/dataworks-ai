package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 引擎字段归一化：老模型缺省 DORIS，非法值必须 400。 */
class ScriptEngineTest {

    @Test
    void missingEngineFallsBackToDoris() {
        assertEquals(ScriptEngine.DORIS, ScriptEngine.normalize(null));
        assertEquals(ScriptEngine.DORIS, ScriptEngine.normalize(""));
        assertEquals(ScriptEngine.DORIS, ScriptEngine.normalize("   "));
        assertEquals(ScriptEngine.DORIS, ScriptEngine.fromTask(null));
    }

    @Test
    void engineIsCaseInsensitive() {
        assertEquals(ScriptEngine.DORIS, ScriptEngine.normalize("doris"));
        assertEquals(ScriptEngine.SPARK, ScriptEngine.normalize(" spark "));
        assertEquals(ScriptEngine.FLINK, ScriptEngine.normalize("Flink"));
    }

    @Test
    void illegalEngineIsBadRequest() {
        ApiException error = assertThrows(ApiException.class,
                () -> ScriptEngine.normalize("PRESTO"));
        assertEquals(400, error.getStatus());
        // 报错要说清可选值，别只丢一个 valueOf 异常
        assertEquals(true, error.getMessage().contains("DORIS"));
        assertEquals(true, error.getMessage().contains("SPARK"));
        assertEquals(true, error.getMessage().contains("FLINK"));
    }

    /**
     * V104 迁移前后的模型解析：迁移前 SQL 任务缺 engine → DORIS；
     * 迁移后同一任务显式 engine=DORIS，解析结果不变；已有 SPARK / FLINK 不被覆盖。
     */
    @Test
    void legacyAndMigratedModelsResolveToTheSameEngine() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode legacy = mapper.readTree("""
                {"tasks":[
                  {"code":"t1","type":"SQL","script":"select 1"},
                  {"code":"t2","type":"SQL","engine":"SPARK","script":"select 2"},
                  {"code":"t3","type":"SQL","engine":"FLINK","script":"select 3"},
                  {"code":"t4","type":"SHELL","script":"echo hi"}
                ],"edges":[]}
                """);
        JsonNode migrated = mapper.readTree("""
                {"tasks":[
                  {"code":"t1","type":"SQL","engine":"DORIS","script":"select 1"},
                  {"code":"t2","type":"SQL","engine":"SPARK","script":"select 2"},
                  {"code":"t3","type":"SQL","engine":"FLINK","script":"select 3"},
                  {"code":"t4","type":"SHELL","script":"echo hi"}
                ],"edges":[]}
                """);

        for (int i = 0; i < 3; i++) {
            assertEquals(ScriptEngine.fromTask(legacy.path("tasks").get(i)),
                    ScriptEngine.fromTask(migrated.path("tasks").get(i)));
        }
        assertEquals(ScriptEngine.DORIS, ScriptEngine.fromTask(legacy.path("tasks").get(0)));
        assertEquals(ScriptEngine.SPARK, ScriptEngine.fromTask(legacy.path("tasks").get(1)));
        assertEquals(ScriptEngine.FLINK, ScriptEngine.fromTask(legacy.path("tasks").get(2)));
    }
}
