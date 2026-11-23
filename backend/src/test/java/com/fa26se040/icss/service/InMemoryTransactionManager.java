package com.fa26se040.icss.service;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transaction manager cho unit test không cần DB: không giữ tài nguyên gì, nhưng đi qua đúng vòng đời của
 * AbstractPlatformTransactionManager (REQUIRED tham gia transaction đang có, REQUIRES_NEW tạm dừng rồi khôi phục
 * transaction ngoài, rollback-only cục bộ và toàn cục, afterCommit / afterCompletion(STATUS_COMMITTED | STATUS_ROLLED_BACK)).
 * Dùng với {@link #transactional(Object)} để @Transactional trên service thật có hiệu lực như trong Spring.
 */
final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

    private static final class Holder {
        boolean rollbackOnly;
    }

    private static final class Tx implements SmartTransactionObject {
        Holder holder;

        Tx(Holder holder) {
            this.holder = holder;
        }

        @Override
        public boolean isRollbackOnly() {
            return holder != null && holder.rollbackOnly;
        }

        @Override
        public void flush() {
        }
    }

    private final ThreadLocal<Holder> current = new ThreadLocal<>();
    final AtomicInteger commits = new AtomicInteger();
    final AtomicInteger rollbacks = new AtomicInteger();

    @Override
    protected Object doGetTransaction() {
        return new Tx(current.get());
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((Tx) transaction).holder != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        Holder holder = new Holder();
        ((Tx) transaction).holder = holder;
        current.set(holder);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Holder suspended = current.get();
        ((Tx) transaction).holder = null;
        current.remove();
        return suspended;
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        current.set((Holder) suspendedResources);
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rollbacks.incrementAndGet();
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        ((Tx) status.getTransaction()).holder.rollbackOnly = true;
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        current.remove();
    }

    /** Proxy CGLIB áp @Transactional của {@code target} bằng manager này (như bean Spring). */
    @SuppressWarnings("unchecked")
    <T> T transactional(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(this, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
