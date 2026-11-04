package com.lab.dataworks.flywheel;

import com.lab.dataworks.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-FW-4 编排设置单测：白名单键 + 值校验「非法写入显式 400」、读取「脏值回默认并告警」。
 * 存储用内存 Map 假 JdbcTemplate（同 RelabelTriggerTest 口径，不打库）。
 */
class OrchestratorSettingsTest {

    private static final class StoreJdbc extends JdbcTemplate {
        final Map<String, String> data = new java.util.HashMap<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("from platform.setting")) {
                String v = data.get(String.valueOf(args[0]));
                return v == null ? List.of() : List.of(Map.of("setting_value", v));
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("insert into platform.setting")) {
                data.put(String.valueOf(args[0]), String.valueOf(args[1]));
                return 1;
            }
            return 0;
        }
    }

    @Test
    void unknownKeysRejectedNoWildcardSettingWrite() {
        OrchestratorSettings s = new OrchestratorSettings(new StoreJdbc());
        ApiException e = assertThrows(ApiException.class,
                () -> s.write("flywheel.orch.something_else", "1"));
        assertTrue(e.getMessage().contains("不允许"));
        // 关键：不能拿编排口改任意 setting（比如管理员开关）
        assertThrows(ApiException.class, () -> s.write("guard.mode", "OFF"));
        assertThrows(ApiException.class, () -> s.write(null, "x"));
    }

    @Test
    void gateKeysNormalizeAndRejectJunk() {
        StoreJdbc jdbc = new StoreJdbc();
        OrchestratorSettings s = new OrchestratorSettings(jdbc);
        s.write(OrchestratorSettings.KEY_ANNOTATION_GATE, " auto ");
        assertEquals("AUTO", jdbc.data.get(OrchestratorSettings.KEY_ANNOTATION_GATE));
        s.write(OrchestratorSettings.KEY_EVAL_GATE, "manual");
        assertEquals("MANUAL", jdbc.data.get(OrchestratorSettings.KEY_EVAL_GATE));
        assertThrows(ApiException.class,
                () -> s.write(OrchestratorSettings.KEY_ANNOTATION_GATE, "看心情"));
    }

    @Test
    void evalScoreRenewAndFw3KeysValidate() {
        StoreJdbc jdbc = new StoreJdbc();
        OrchestratorSettings s = new OrchestratorSettings(jdbc);
        s.write(OrchestratorSettings.KEY_EVAL_MIN_SCORE, "6.5");
        assertEquals("6.5", jdbc.data.get(OrchestratorSettings.KEY_EVAL_MIN_SCORE));
        assertThrows(ApiException.class, () -> s.write(OrchestratorSettings.KEY_EVAL_MIN_SCORE, "11"));
        assertThrows(ApiException.class, () -> s.write(OrchestratorSettings.KEY_EVAL_MIN_SCORE, "abc"));
        s.write(OrchestratorSettings.KEY_AUTO_RENEW, "FALSE");
        assertEquals("false", jdbc.data.get(OrchestratorSettings.KEY_AUTO_RENEW));
        assertThrows(ApiException.class, () -> s.write(OrchestratorSettings.KEY_AUTO_RENEW, "yes"));
        // FW-3 三键经编排口同白名单（设置页一个弹窗改全套）
        s.write(FlywheelSettings.KEY_BATCH_SIZE, "50");
        assertThrows(ApiException.class, () -> s.write(FlywheelSettings.KEY_BATCH_SIZE, "0"));
        assertThrows(ApiException.class, () -> s.write(FlywheelSettings.KEY_CONFIDENCE, "1.5"));
        s.write(FlywheelSettings.KEY_TARGET_MODEL, "qwen-prod");
        assertEquals("qwen-prod", jdbc.data.get(FlywheelSettings.KEY_TARGET_MODEL));
    }

    @Test
    void dirtyStoredValuesFallBackToDefaults() {
        StoreJdbc jdbc = new StoreJdbc();
        jdbc.data.put(OrchestratorSettings.KEY_ANNOTATION_GATE, "MAYBE");
        jdbc.data.put(OrchestratorSettings.KEY_EVAL_MIN_SCORE, "十");
        OrchestratorSettings s = new OrchestratorSettings(jdbc);
        assertEquals("MANUAL", s.annotationGate(), "脏值按最保守（人工闸）执行");
        assertEquals(7.0, s.evalMinScore());
        assertTrue(s.autoRenew(), "缺键默认续圈");
        jdbc.data.put(OrchestratorSettings.KEY_AUTO_RENEW, "false");
        assertEquals(false, s.autoRenew());
    }

    @Test
    void snapshotOnlyExposesWhitelist() {
        StoreJdbc jdbc = new StoreJdbc();
        jdbc.data.put("some.other.key", "secret");
        jdbc.data.put(OrchestratorSettings.KEY_EVAL_GATE, "AUTO");
        Map<String, Object> snap = new OrchestratorSettings(jdbc).snapshot();
        assertEquals(OrchestratorSettings.WRITABLE_KEYS.size(), snap.size());
        assertTrue(!snap.containsKey("some.other.key"), "白名单外键不许从编排口泄漏");
        assertEquals("AUTO", snap.get(OrchestratorSettings.KEY_EVAL_GATE));
    }
}
