package com.lab.dataworks.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.PermissionResolver;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.ApiException;
import com.lab.dataworks.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D61（TC-2 用例 AC-08）：申请理由必须有上限，上限 <b>500 字</b>。
 *
 * <p>缺陷现场（v539 live 实测）：{@code reason} 传 5000 个「长」字，接口 200、id=253 原样入库
 * （列是 {@code text}，无界）。审批列表/详情页被超长理由撑爆，而前端 TextArea 早就写着
 * {@code maxLength={500}} —— 也就是<b>只有绕开页面直连 API 才能写进来</b>。
 * 这条用例把「后端与前端同一个数字」钉住，同时锁住三件容易写歪的事：</p>
 * <ol>
 *   <li>长度按**字（code point）**算，不按 UTF-16 长度：500 个 emoji 是 1000 个 char，
 *       若按 char 算就会把合规输入判成超限（误伤比不设限更糟）；</li>
 *   <li>边界含 500（恰好 500 字必须通过），400 文案带<b>当前字数</b>；</li>
 *   <li>限长排在资源存在性查询<b>之前</b>：超长理由不该先跑三轮库查询再被拒。</li>
 * </ol>
 *
 * <p>续期（拼接理由、历史数据不迁）的同源用例在 {@code AccessRenewTest}。</p>
 */
class AccessReasonLimitTest {

    private JdbcTemplate jdbc;
    private AccessController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        controller = new AccessController(jdbc, mock(SessionService.class),
                mock(PermissionResolver.class), mock(TransactionTemplate.class),
                mock(AccessExpiryJob.class), mock(AccessEscalationJob.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequest asAlice() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/access/requests");
        request.setAttribute("username", "alice");
        return request;
    }

    private static AccessController.CreateRequest reqWith(String reason) {
        return new AccessController.CreateRequest("TABLE", "PG", "tenant_b", "customers",
                null, null, "READ", reason);
    }

    // ---------------------------------------------------------------- HTTP 层

    @Test
    void fiveThousandCharReasonIs400NotSilentInsert() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of(
                "resourceType", "TABLE", "catalog", "PG", "schemaName", "tenant_b",
                "tableName", "customers", "permission", "READ", "reason", "长".repeat(5000)));

        // 登录属性由 AuthInterceptor 写入；standalone MockMvc 没有拦截器，得自己塞，
        // 否则 controller 的 user(http) 先抛 401「未登录」（鉴权本就排在入参校验之前）
        MvcResult result = mvc.perform(post("/api/access/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(json)
                        .requestAttr("username", "alice"))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("申请理由不能超过 500 字"), body);
        assertTrue(body.contains("5000"), "400 要带当前字数，用户不用自己数: " + body);
        // 卡面 repro 的库/表在纳管清单里才走得下去；限长排在前面，一条查询都不该发
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingReasonIsStillOptional() {
        // 理由不是必填：空/缺省不能被这条限长顺手变成必填（那是改需求，不是修 BUG）
        assertEquals(null, AccessController.requireReasonLength(null));
        assertEquals("", AccessController.requireReasonLength(""));
        assertEquals("   ", AccessController.requireReasonLength("   "));
    }

    // ---------------------------------------------------------------- 边界与口径

    @Test
    void exactlyFiveHundredCharsPassesAndFiveHundredOneFails() {
        String ok = "长".repeat(500);
        assertEquals(ok, AccessController.requireReasonLength(ok));

        ApiException e = assertThrows(ApiException.class,
                () -> AccessController.requireReasonLength("长".repeat(501)));
        assertEquals(400, e.getStatus());
        assertTrue(e.getMessage().contains("（当前 501 字）"), e.getMessage());
    }

    /** 代理对（emoji / 生僻字）算一个字：500 个 = 1000 个 UTF-16 char，但仍是 500 字，必须放过。 */
    @Test
    void lengthCountsCodePointsNotUtf16Units() {
        String emoji = new String(Character.toChars(0x1F600));   // 😀
        String emoji500 = emoji.repeat(500);
        assertEquals(1000, emoji500.length(), "这条用例的前提：代理对在 UTF-16 里是两个 char");
        assertEquals(emoji500, AccessController.requireReasonLength(emoji500));
        assertEquals(500, AccessController.reasonLength(emoji500));

        assertThrows(ApiException.class, () -> AccessController.requireReasonLength(emoji.repeat(501)));
    }

    /** 计数发生在 trim 之后（create 里先 trim 再限长），首尾空白不占额度。 */
    @Test
    void limitIsCountedAfterTrim() {
        String withPadding = "长".repeat(500) + " \n\t ";

        assertEquals(504, AccessController.reasonLength(withPadding), "未 trim 是 504 字");
        assertEquals(500, AccessController.reasonLength(withPadding.trim()));
        assertEquals("长".repeat(500), AccessController.requireReasonLength(withPadding.trim()));
    }

    // ---------------------------------------------------------------- 续期拼接

    /** 续期把「说明」拼进理由：短说明原样带上，不截不断。 */
    @Test
    void shortNoteIsKeptVerbatim() {
        String prefix = "续期自 #9（原到期 2026-11-02）";
        String note = "；说明：月度对账还要用这张表";

        assertEquals(note, AccessController.fitNote(note, prefix));
    }

    /**
     * 历史审批意见可能本身就没上限：拼进来的说明必须**截断到总长 ≤ 500 并留可见标记**，
     * 既不许静默写超限，也不许因为旧数据把用户的续期整个挡死（那是回归）。
     */
    @Test
    void overlongNoteIsTruncatedWithVisibleMarker() {
        String prefix = "续期自 #9（原到期 2026-11-02）";
        String note = "；说明：" + "长".repeat(5000);

        String fitted = AccessController.fitNote(note, prefix);

        assertTrue(AccessController.reasonLength(prefix + fitted) <= 500,
                "拼接后总长必须在上限内，当前 " + AccessController.reasonLength(prefix + fitted));
        assertTrue(fitted.contains("说明超长已截断"), "截断要留可见标记: " + fitted);
        assertTrue(fitted.startsWith("；说明：长"), "保留前半段可读内容: " + fitted);
    }

    /** 前缀本身已经吃掉全部额度时，宁可不带说明，也不能写出一条超限记录。 */
    @Test
    void noRoomForNoteDropsItInsteadOfOverflowing() {
        String prefix = "续期自 #9（原到期 " + "长".repeat(600) + "）";

        assertEquals("", AccessController.fitNote("；说明：还要用", prefix));
    }
}
