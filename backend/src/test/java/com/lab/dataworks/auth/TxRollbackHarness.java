package com.lab.dataworks.auth;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * D32 测试基建：让被测对象走**真实的 Spring 事务代理链**
 * （ProxyFactory + TransactionInterceptor + AnnotationTransactionAttributeSource），
 * 方法上没标 {@code @Transactional} 就不会开启事务——所以「撤掉注解测试必须红」，
 * 这不是只查注解存在的摆设。
 *
 * <p>事务管理器换成「快照-恢复」桩：doBegin 时抓取内存表的快照，
 * doRollback 恢复（＝数据库回滚的效果），doCommit 丢弃。仓库单测没有 PG 容器
 * （SQL 是 PG 方言，H2 跑不了），桩只模拟事务语义，SQL 路由由各测试自己完成。</p>
 */
final class TxRollbackHarness {

    private TxRollbackHarness() {
    }

    /** 内存表的快照/回放，由测试夹具提供。 */
    record Hooks(Supplier<Object> capture, Consumer<Object> replay) {
    }

    /** 快照事务桩：记录 commit / rollback 各发生了几次。 */
    static final class SnapshotTxManager extends AbstractPlatformTransactionManager {
        private final Hooks hooks;
        int commits;
        int rollbacks;

        SnapshotTxManager(Hooks hooks) {
            this.hooks = hooks;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object[1];
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return false;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            ((Object[]) transaction)[0] = hooks.capture().get();
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void doRollback(DefaultTransactionStatus status) {
            hooks.replay().accept(((Object[]) status.getTransaction())[0]);
            rollbacks++;
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            // 本测试不主动 markRollbackOnly；给出实现避免走父类的异常分支
        }
    }

    /** 按 @Transactional 织入事务代理；target 必须经返回的代理调用才受事务保护。 */
    @SuppressWarnings("unchecked")
    static <T> T txProxy(T target, SnapshotTxManager txm) {
        TransactionInterceptor interceptor =
                new TransactionInterceptor(txm, new AnnotationTransactionAttributeSource());
        ProxyFactory pf = new ProxyFactory(target);
        pf.addAdvice(interceptor);
        pf.setProxyTargetClass(true);
        return (T) pf.getProxy();
    }
}
