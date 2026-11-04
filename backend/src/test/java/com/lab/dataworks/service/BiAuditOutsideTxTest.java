package com.lab.dataworks.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lab.dataworks.auth.SessionService;
import com.lab.dataworks.security.DataGuardService;
import com.lab.dataworks.secret.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S13：BI 的「审计挪到事务提交后」——业务回滚不能把审计一起带走。
 *
 * <p>原状：{@code BiController} 的写方法带 {@code @Transactional}，审计在事务<b>内</b>
 * 插入。业务一回滚（唯一键冲突、图表校验失败、Doris 报错），审计行跟着消失——
 * 用户拿到失败响应，安全中心里却「什么都没发生过」。修复是注册
 * {@link TransactionSynchronization}：提交后写 {@code SUCCESS}，回滚后在事务外补
 * {@code FAILED}。这里用假 JdbcTemplate + 真 SessionService 钉住三条语义：</p>
 *
 * <ol>
 *   <li>有活动同步时，审计<b>不得</b>在业务事务内落库（先要看不到写入）；</li>
 *   <li>提交路径恰好一条 SUCCESS，不重复记；</li>
 *   <li>回滚路径恰好一条 FAILED，detail 说明「事务回滚，操作未生效」——
 *       撤掉 {@code afterCompletion} 的回滚分支，这条必红。</li>
 * </ol>
 */
class BiAuditOutsideTxTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BiController controller = new BiController(jdbc, new ObjectMapper(),
            mock(SecretCipher.class), mock(DataGuardService.class), new SessionService(jdbc, 12));

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("username", "bi_owner");
        http.setRemoteAddr("10.99.99.99");
        return http;
    }

    /** 触发已注册的同步：committed=true 走 afterCommit+COMPLETED，false 只走 ROLLED_BACK。 */
    private void fire(boolean committed) {
        for (TransactionSynchronization sync
                : TransactionSynchronizationManager.getSynchronizations()) {
            if (committed) {
                sync.afterCommit();
            }
            sync.afterCompletion(committed
                    ? TransactionSynchronization.STATUS_COMMITTED
                    : TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    /** 捕获审计 insert 的参数（auditAs：username, action, target, detail, result, ip, tenant）。 */
    private List<List<Object>> auditInserts(int times) {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(times)).update(contains("insert into platform.audit_log"), args.capture());
        return args.getAllValues().stream().map(List::of).toList();
    }

    @Test
    void auditDefersWhileTransactionActive() {
        TransactionSynchronizationManager.initSynchronization();

        controller.auditOutsideTx(request(), "BI_DASHBOARD_PUBLISH", "dash_1", "发布快照");

        // 关键旧 bug 语义：业务事务里一条都不写——回滚才有机会「不带走审计」
        verify(jdbc, never()).update(contains("insert into platform.audit_log"), any(Object[].class));
        fire(true);

        List<List<Object>> rows = auditInserts(1);
        assertEquals("SUCCESS", rows.get(0).get(4));
        assertEquals("bi_owner", rows.get(0).get(0));
    }

    /** 回滚＝旧缺陷现场：审计必须还在，且是带原因的 FAILED，而不是消失或空记录。 */
    @Test
    void rollbackWritesOneFailedAuditOutsideTx() {
        TransactionSynchronizationManager.initSynchronization();

        controller.auditOutsideTx(request(), "BI_DASHBOARD_OFFLINE", "dash_2", "下线看板");

        fire(false);

        List<List<Object>> rows = auditInserts(1);   // afterCompletion(COMMITTED) 早退：不重复记
        assertEquals("FAILED", rows.get(0).get(4));
        String detail = String.valueOf(rows.get(0).get(3));
        assertTrue(detail.contains("下线看板"), detail);
        assertTrue(detail.contains("事务回滚，操作未生效"), detail);
    }

    /** 提交路径只记一次：afterCommit 写完 SUCCESS 后，afterCompletion 不得再补一条。 */
    @Test
    void commitDoesNotDoubleWrite() {
        TransactionSynchronizationManager.initSynchronization();

        controller.auditOutsideTx(request(), "BI_DASHBOARD_CREATE", "dash_3", "新建看板");

        fire(true);

        List<List<Object>> rows = auditInserts(1);   // times(1) 本身就是「不重复」断言
        assertEquals("SUCCESS", rows.get(0).get(4));
    }

    /** 没有活动事务（同步未激活）时按原样立即落库，不能把审计憋没。 */
    @Test
    void immediateWriteWhenNoTransactionActive() {
        controller.auditOutsideTx(request(), "BI_DASHBOARD_SMOKE", "dash_4", "DEV 跑通");

        List<List<Object>> rows = auditInserts(1);
        assertEquals("SUCCESS", rows.get(0).get(4));
    }
}
