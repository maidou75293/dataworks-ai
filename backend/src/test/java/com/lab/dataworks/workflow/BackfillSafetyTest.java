package com.lab.dataworks.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackfillSafetyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String model(String... scripts) {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < scripts.length; i++) {
            if (i > 0) {
                tasks.append(',');
            }
            tasks.append("{\"code\":\"t").append(i + 1).append("\",\"type\":\"SQL\",\"script\":")
                    .append(quote(scripts[i])).append('}');
        }
        return "{\"tasks\":[" + tasks + "],\"edges\":[]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    @Test
    void appendInsertIsUnsafe() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(
                model("insert into dw.t select 1 as v"), mapper);
        assertFalse(result.safe());
        assertTrue(result.unsafe().get(0).contains("t1"));
    }

    @Test
    void overwriteInsertIsSafe() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(
                model("insert overwrite table dw.t select 1 as v"), mapper);
        assertTrue(result.safe(), String.valueOf(result.unsafe()));
    }

    @Test
    void deleteForSameDateThenAppendIsSafe() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(model(
                "delete from dw.t where dt = '${biz_date}'",
                "insert into dw.t select '${biz_date}' as dt, 1 as v"), mapper);
        assertTrue(result.safe(), String.valueOf(result.unsafe()));
    }

    @Test
    void deleteWithoutBizDateDoesNotCoverAppend() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(model(
                "delete from dw.t where dt > '2020-01-01'",
                "insert into dw.t select '${biz_date}' as dt, 1 as v"), mapper);
        assertFalse(result.safe());
    }

    @Test
    void deleteOfAnotherTableDoesNotCoverAppend() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(model(
                "delete from dw.other where dt = '${biz_date}'",
                "insert into dw.t select '${biz_date}' as dt, 1 as v"), mapper);
        assertFalse(result.safe());
    }

    @Test
    void qualifiedAndUnqualifiedNamesMatch() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(model(
                "delete from `dw`.`t` where dt = '${biz_date}'",
                "insert into t select '${biz_date}' as dt, 1 as v"), mapper);
        assertTrue(result.safe(), String.valueOf(result.unsafe()));
    }

    @Test
    void keywordsInsideLiteralsAndCommentsAreIgnored() {
        BackfillSafety.Analysis result = BackfillSafety.analyze(model(
                "-- insert into fake select 1\nselect 'insert into fake' as note"), mapper);
        assertTrue(result.safe(), String.valueOf(result.unsafe()));
    }

    @Test
    void shellTaskIsReportedButNotBlocking() {
        String json = "{\"tasks\":[{\"code\":\"sh\",\"type\":\"SHELL\",\"script\":\"echo hi\"}],"
                + "\"edges\":[]}";
        BackfillSafety.Analysis result = BackfillSafety.analyze(json, mapper);
        assertTrue(result.safe());
        assertTrue(result.unchecked().contains("sh"));
    }
}
