package com.lab.dataworks.mosbridge;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.TenantGuard;
import com.lab.dataworks.query.DorisQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REQ-FW-1 控制层单测：表查询出口挂点只读闸门（单条 SELECT/WITH）、入参校验、
 * 审计成功/失败都落（同 REQ-UNIFY-1 口径）、返回体透传推送计数。
 * 不起 Spring：standalone 直接 new 控制器（现网判定由 AuthInterceptor 按
 * {@code @RequireAtom({"query:run","mos:access"})} all-of 先行）。
 */
class AnnotationPushControllerTest {

    private DorisQueryService doris;
    private AnnotationPushService pusher;
    private SessionService sessions;
    private TenantGuard guard;
    private AnnotationPushController controller;

    @BeforeEach
    void setUp() {
        doris = mock(DorisQueryService.class);
        pusher = mock(AnnotationPushService.class);
        sessions = mock(SessionService.class);
        guard = mock(TenantGuard.class);
        controller = new AnnotationPushController(doris, pusher, sessions, guard);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute("username", "alice");
        req.setAttribute("tenant", "acme");
        req.setRemoteAddr("10.0.0.9");
        return req;
    }

    private static DorisQueryService.Result oneRow() {
        return new DorisQueryService.Result(
                List.of("question", "answer"), List.of(List.of("q", "a")), 1, 1000, 1L, "doris");
    }

    @Test
    void readOnlyGateAllowsSelectWithAndTrailingSemicolon() {
        assertThat(AnnotationPushController.requireReadOnly("  SELECT 1  ;  ")).isEqualTo("SELECT 1");
        assertThat(AnnotationPushController.requireReadOnly("with t as (select 1) select * from t"))
                .startsWith("with");
    }

    @Test
    void readOnlyGateRejectsMultiStatementAndWrites() {
        assertThatThrownBy(() -> AnnotationPushController.requireReadOnly("select 1; drop table x"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("单条");
                });
        assertThatThrownBy(() -> AnnotationPushController.requireReadOnly("delete from dataset"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void happyPathQueriesThroughDorisEgressAndAuditsSuccess() {
        when(doris.query(any(), anyString(), anyInt())).thenReturn(oneRow());
        when(pusher.push(eq("acme"), eq("alice"), anyString(), eq("ds_abc"),
                any(), eq("question"), eq("answer"), any()))
                .thenReturn(new AnnotationPushService.PushReport("ds_abc", "0", 1, 0, 0, 1));
        AnnotationPushController.PushRequest body = new AnnotationPushController.PushRequest(
                "select question, answer from qa", 7L, "ds_abc", "question", "answer", null, null);
        Map<String, Object> out = controller.push(body, request());
        assertThat(out).containsEntry("dsId", "ds_abc").containsEntry("inserted", 1)
                .containsEntry("version", "0");
        verify(guard).requireProject(7L);
        verify(doris).query(7L, "select question, answer from qa", 1000);
        verify(sessions).audit(eq("alice"), eq("ANNOTATION_PUSH"), contains("ds=ds_abc"),
                contains("inserted=1"), eq("SUCCESS"), eq("10.0.0.9"));
    }

    @Test
    void pushFailureAuditsFailedAndRethrows() {
        when(doris.query(any(), anyString(), anyInt())).thenReturn(oneRow());
        when(pusher.push(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ApiException(502, "无法连接 ModelOneStation"));
        AnnotationPushController.PushRequest body = new AnnotationPushController.PushRequest(
                "select question, answer from qa", null, "ds_abc", "question", "answer", null, 50);
        assertThatThrownBy(() -> controller.push(body, request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(502));
        verify(sessions).audit(eq("alice"), eq("ANNOTATION_PUSH"), anyString(),
                contains("502"), eq("FAILED"), any());
    }

    @Test
    void blankColumnsAndBadMaxRowsFailBeforeQuery() {
        AnnotationPushController.PushRequest noCols = new AnnotationPushController.PushRequest(
                "select 1", null, "ds_abc", null, "answer", null, null);
        assertThatThrownBy(() -> controller.push(noCols, request()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(400));
        AnnotationPushController.PushRequest badRows = new AnnotationPushController.PushRequest(
                "select 1", null, "ds_abc", "q", "a", null, 99999);
        assertThatThrownBy(() -> controller.push(badRows, request()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("maxRows");
                });
        verify(doris, org.mockito.Mockito.never()).query(any(), anyString(), anyInt());
    }

    private static String contains(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }
}
