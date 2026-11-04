package com.lab.dataworks.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D13：{@code POST /api/agent/chat} 的空请求体必须稳定 400，不能随 Accept 变成 500。
 *
 * <p>原状：映射上带 {@code produces = text/event-stream}，于是
 * {@code Accept: application/json}（或 {@code text/plain}）在<b>进方法之前</b>就被内容协商
 * 判成 {@code HttpMediaTypeNotAcceptableException}，全局处理器没有这个分支 → 兜底 500
 * 「服务器内部错误，请查看后端日志」，监控把它记成服务端故障。
 * 同一条非法输入按 Accept 分成 400 / 500 两种结果。</p>
 *
 * <p>本测试用 standalone MockMvc（不连 agent-service，也不起 Spring 上下文）：
 * 非法输入都在发起上游请求之前被挡住，所以只需要 mock 掉 {@link AgentAccess}。</p>
 */
class AgentChatErrorContractTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AgentChatController controller = new AgentChatController(
                new ObjectMapper(), "http://127.0.0.1:1", "test-token", mock(AgentAccess.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void emptyBodyIs400ForEveryAccept() throws Exception {
        for (String accept : List.of("application/json", "text/plain", "text/event-stream", "*/*")) {
            // 修复前：application/json 与 text/plain 是 500，另外两个才是 400
            mvc.perform(post("/api/agent/chat").header("Accept", accept)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest())
                    // 修复前这一条在 application/json / text/plain 下是 500
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.emptyString())));
        }
    }

    @Test
    void emptyBodyWithNoContentTypeIsAlso400() throws Exception {
        // 运维脚本 / curl 常见写法：连 Content-Type 都不带
        mvc.perform(post("/api/agent/chat"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jsonClientGetsStructuredErrorBody() throws Exception {
        mvc.perform(post("/api/agent/chat").header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("message")));
    }

    @Test
    void streamClientGetsPlainTextError() throws Exception {
        mvc.perform(post("/api/agent/chat")
                        .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void blankMessageIs400Not500() throws Exception {
        for (String accept : List.of("application/json", "text/event-stream")) {
            mvc.perform(post("/api/agent/chat").header("Accept", accept)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(post("/api/agent/chat").header("Accept", MediaType.APPLICATION_JSON_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.emptyString())));
    }

    @Test
    void errorBodyIsNeverEmpty() throws Exception {
        // 空 body 的 4xx 会让前端只能显示「请求失败」，排障时什么信息都没有
        for (String accept : List.of("application/json", "text/plain", "text/event-stream", "*/*")) {
            String body = mvc.perform(post("/api/agent/chat").header("Accept", accept))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.isBlank(), "错误响应必须带可诊断信息，Accept=" + accept);
        }
    }
}
