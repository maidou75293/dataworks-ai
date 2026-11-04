package com.lab.dataworks.metadata;

import com.lab.dataworks.auth.RequireAtom;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 收尾：metadata.source 开关接口的权限点、取值校验与切换后缓存失效。 */
class MetadataSourceControllerTest {

    private MetadataSettings settings;
    private MetadataReader reader;
    private SessionService sessions;
    private MetadataSourceController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        settings = mock(MetadataSettings.class);
        GravitinoClient client = mock(GravitinoClient.class);
        reader = mock(MetadataReader.class);
        sessions = mock(SessionService.class);
        controller = new MetadataSourceController(settings, client, reader, sessions);
        request = mock(HttpServletRequest.class);
        when(request.getAttribute("username")).thenReturn("admin");
        when(settings.source()).thenReturn(MetadataSettings.GRAVITINO);
        when(reader.stats()).thenReturn(Map.of("degraded", false, "degradedCount", 0L));
        when(client.baseUrl()).thenReturn("http://gravitino:8090");
        when(client.metalake()).thenReturn("dataworks");
    }

    @Test
    void getAndPutRequireSystemManage() throws Exception {
        RequireAtom get = MetadataSourceController.class.getMethod("get")
                .getAnnotation(RequireAtom.class);
        assertNotNull(get, "GET /api/metadata/source 必须有权限点");
        assertEquals("system:manage", String.join(",", get.value()));

        RequireAtom put = MetadataSourceController.class
                .getMethod("put", MetadataSourceController.Input.class, HttpServletRequest.class)
                .getAnnotation(RequireAtom.class);
        assertNotNull(put, "PUT /api/metadata/source 必须有权限点");
        assertEquals("system:manage", String.join(",", put.value()));
    }

    @Test
    void invalidSourceIsRejectedWith400() {
        ApiException e = assertThrows(ApiException.class,
                () -> controller.put(new MetadataSourceController.Input("mysql"), request));

        assertEquals(400, e.getStatus());
        verify(settings, never()).setSource(anyString(), anyString());
    }

    @Test
    void nullBodyIsRejectedWith400() {
        ApiException e = assertThrows(ApiException.class, () -> controller.put(null, request));
        assertEquals(400, e.getStatus());
    }

    @Test
    void switchingSourceIsAuditedAndInvalidatesCache() {
        when(settings.source()).thenReturn(MetadataSettings.GRAVITINO,
                MetadataSettings.DIRECT);

        Map<String, Object> out = controller.put(
                new MetadataSourceController.Input("DIRECT"), request);

        assertEquals(MetadataSettings.DIRECT, out.get("source"));
        verify(settings).setSource(MetadataSettings.DIRECT, "admin");
        // 切换后立刻清掉上一来源的缓存与降级诊断
        verify(reader).invalidateAll();
        verify(sessions).audit(eq("admin"), eq("METADATA_SOURCE_UPDATE"), eq("direct"),
                anyString(), eq("SUCCESS"), any());
    }

    @Test
    void rewritingTheSameSourceDoesNotFlushCache() {
        when(settings.source()).thenReturn(MetadataSettings.DIRECT);

        controller.put(new MetadataSourceController.Input("direct"), request);

        verify(settings).setSource(MetadataSettings.DIRECT, "admin");
        verify(reader, never()).invalidateAll();
    }

    @Test
    void describeExposesDegradedDiagnostics() {
        when(reader.stats()).thenReturn(Map.of(
                "degraded", true, "degradedCount", 3L,
                "lastDegradedReason", "connection refused"));

        Map<String, Object> out = controller.get();

        assertEquals(MetadataSettings.GRAVITINO, out.get("source"));
        assertEquals(true, out.get("degraded"));
        assertEquals(3L, out.get("degradedCount"));
        assertEquals("connection refused", out.get("lastDegradedReason"));
        assertNotNull(out.get("options"));
        assertNotNull(out.get("gravitino"));
    }
}
