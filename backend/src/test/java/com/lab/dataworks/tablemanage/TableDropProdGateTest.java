package com.lab.dataworks.tablemanage;

import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.common.GlobalExceptionHandler;
import com.lab.dataworks.common.TenantDataScope;
import com.lab.dataworks.metadata.MetadataReader;
import com.lab.dataworks.project.DorisAdminService;
import com.lab.dataworks.project.WarehouseResolver;
import com.lab.dataworks.query.DorisQueryService;
import com.lab.dataworks.security.DataGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-TC6-5①：生产库（库名 `_prd` 结尾，与 TableDdlService env 判定同口径）删表
 * 不得直接执行——同一张生产表，加列/改名/清空都要审批（PENDING_APPROVAL），
 * 唯独 {@code DELETE /api/tables/{db}/{t}} 全流程无 env 分支，直接 drop。
 *
 * <p>卡面给了两条路（改造工单审批 / 最低 5 字理由+审计）；本实现取更强口径：
 * 复用变更单通道（D53：建单即备份、审批后执行、可回滚），本端点对 _prd 库一律
 * 400 指向变更单，并且**拒绝也落审计**（REJECTED），否则「谁试过删生产表」对不上账。</p>
 */
class TableDropProdGateTest {

    private DorisAdminService doris;
    private SessionService sessions;
    private MetadataReader metadata;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        doris = mock(DorisAdminService.class);
        sessions = mock(SessionService.class);
        metadata = mock(MetadataReader.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("show create table"), any(Object[].class)))
                .thenReturn(List.of());
        // CONFLICT-WM1：合流后删表端点的生产判定改走 TableDdlService.gateEnv
        // （列值权威 + _prd 命名兜底一份化）。mock 的 gateEnv 返回 null 时控制器按命名约定
        // 兜底，与真闸门口径一致——本卡用例（_prd 拒绝 / dev 放行 / 大小写）判定不变。
        TableController controller = new TableController(
                jdbc, doris, mock(DorisQueryService.class), sessions,
                mock(TenantDataScope.class), mock(WarehouseResolver.class), metadata,
                mock(DataGuardService.class), mock(TableDdlService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void prodDatabaseDropIsRejectedAndAudited() throws Exception {
        mvc.perform(delete("/api/tables/demo_retail_prd/orders?confirm=orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("须走变更单")));

        // 关键反向断言：不能有任何 drop table / 快照读取发生
        verify(doris, never()).exec(any(), contains("drop table"));
        // 拒绝也要可追溯
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(sessions).audit(eq("unknown"), eq("TABLE_DROP"), eq("demo_retail_prd.orders"),
                detail.capture(), eq("REJECTED"), any());
        assertTrue(detail.getValue().contains("PROD_CHANGE_REQUEST_REQUIRED"), detail.getValue());
        verify(metadata, never()).invalidateProject(any());
    }

    @Test
    void uppercaseSuffixAlsoGated() throws Exception {
        // 判定与 TableDdlService 同口径：大小写不敏感
        mvc.perform(delete("/api/tables/Demo_Retail_PRD/orders?confirm=orders"))
                .andExpect(status().isBadRequest());
        verify(doris, never()).exec(any(), contains("drop table"));
    }

    @Test
    void devDatabaseDropStillExecutes() throws Exception {
        // 对照：非生产库删表行为不变（防止一刀切把 DEV 也堵死）
        mvc.perform(delete("/api/tables/demo_retail/orders?confirm=orders"))
                .andExpect(status().isOk());
        verify(doris).exec(any(), eq("drop table `demo_retail`.`orders`"));
        verify(sessions).audit(eq("unknown"), eq("TABLE_DROP"), eq("demo_retail.orders"),
                contains("table=demo_retail.orders"), eq("SUCCESS"), any());
        verify(metadata).invalidateProject(null);
    }

    @Test
    void gateAppliesOnlyAfterConfirmValidation() throws Exception {
        // 顺序口径：confirm 不符仍先按「删除确认需要与表名一致」拒绝，
        // 不能把参数错误升级成生产闸门提示（既有 tc1-crud 用例依赖该顺序）
        mvc.perform(delete("/api/tables/demo_retail_prd/orders?confirm=wrong"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("删除确认需要与表名一致")));
        verify(sessions, never()).audit(any(), eq("TABLE_DROP"), any(), any(),
                eq("REJECTED"), any());
    }

    @Test
    void prodLikeNamesWithoutSuffixStayDev() {
        // 判定只认结尾 _prd：prd_demo / _prd1 都算 DEV（与 TableDdlService 规则逐字一致）
        assertTrue(!TableController.isProdDatabase("prd_demo"));
        assertTrue(!TableController.isProdDatabase("demo_prd1"));
        assertTrue(TableController.isProdDatabase("demo_prd"));
    }
}
