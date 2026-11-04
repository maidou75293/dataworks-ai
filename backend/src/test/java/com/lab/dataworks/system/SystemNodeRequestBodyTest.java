package com.lab.dataworks.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D21：节点 cordon / drain 的空请求体要能进到方法体。
 *
 * <p>两个方法本来就按「空 body＝默认值」写（{@code schedulable=true} / {@code dryRun=false}），
 * 但 {@code @RequestBody} 没加 {@code required = false}，运维脚本发空 POST 直接被
 * 「请求体不是合法的 JSON」拦在方法体之前——注释与实现口径相反。</p>
 */
class SystemNodeRequestBodyTest {

    private K8sNodeService nodes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        nodes = mock(K8sNodeService.class);
        when(nodes.setSchedulable(anyString(), anyBoolean())).thenReturn(Map.of("ok", true));
        when(nodes.drain(anyString(), anyBoolean())).thenReturn(Map.of("pods", List.of()));
        // S12/S13 之后 SystemController 多了一个 SessionService 依赖：这里用真
        // SessionService + 假 JdbcTemplate（与 SystemControllerAuditTest 同口径），
        // 让 cordon/drain 的「先记后做」直通到方法体，审计落库只打到 mock。
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                mock(org.springframework.jdbc.core.JdbcTemplate.class);
        SystemController controller = new SystemController(
                mock(SystemMonitorService.class), mock(K8sSnapshotService.class),
                mock(ServiceResourceService.class), mock(DorisNodeService.class),
                nodes, mock(K8sOpsService.class), mock(RightsizingService.class), jdbc,
                new ObjectMapper(), new com.lab.dataworks.auth.SessionService(jdbc, 12),
                mock(com.lab.dataworks.auth.InternalTokenService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void cordonWithoutBodyUsesDefaultSchedulableTrue() throws Exception {
        mvc.perform(post("/api/system/nodes/kNode1/cordon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // 默认值＝恢复调度（与方法体里的 req == null 分支一致）
        verify(nodes).setSchedulable(eq("kNode1"), eq(true));
    }

    @Test
    void drainWithoutBodyUsesDefaultDryRunFalse() throws Exception {
        mvc.perform(post("/api/system/nodes/kNode1/drain"))
                .andExpect(status().isOk());
        verify(nodes).drain(eq("kNode1"), eq(false));
    }

    @Test
    void cordonWithEmptyJsonObjectAlsoWorks() throws Exception {
        mvc.perform(post("/api/system/nodes/kNode1/cordon")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(nodes).setSchedulable(eq("kNode1"), eq(true));
    }

    @Test
    void explicitValuesStillHonoured() throws Exception {
        mvc.perform(post("/api/system/nodes/kNode1/cordon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schedulable\":false}"))
                .andExpect(status().isOk());
        verify(nodes).setSchedulable(eq("kNode1"), eq(false));

        mvc.perform(post("/api/system/nodes/kNode1/drain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isOk());
        verify(nodes).drain(eq("kNode1"), eq(true));
    }

    @Test
    void malformedBodyIsStillBadRequest() throws Exception {
        // 放开必填不等于放过坏 JSON：能解析（含空 body）才进方法体
        mvc.perform(post("/api/system/nodes/kNode1/cordon")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"schedulable\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void notAcceptableIs406Not500() throws Exception {
        // D13 同源：声明 produces 的接口遇到不匹配的 Accept，以前落兜底 500「服务器内部错误」
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/metrics/k8s")
                        .header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(status().isNotAcceptable());
    }
}
