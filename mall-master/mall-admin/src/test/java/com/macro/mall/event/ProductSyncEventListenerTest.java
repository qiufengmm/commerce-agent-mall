package com.macro.mall.event;

import com.macro.mall.service.EsProductSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品索引同步事件监听器的触发时机测试
 * 用DataSourceTransactionManager加mock的Connection驱动真实的事务提交/回滚流程，
 * 只用TransactionSynchronizationManager验证提交后同步的时机，未连接真实MySQL
 */
public class ProductSyncEventListenerTest {

    @Configuration
    static class TestConfig {
        final EsProductSyncService syncService = mock(EsProductSyncService.class);

        @Bean
        public EsProductSyncService esProductSyncService() {
            return syncService;
        }

        @Bean
        public ProductSyncEventListener productSyncEventListener() {
            return new ProductSyncEventListener();
        }

        /**
         * 生产环境下由Spring Boot事务自动配置注册，这里显式注册以还原真实行为
         */
        @Bean
        public TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }
    }

    private AnnotationConfigApplicationContext context;
    private TestConfig config;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    public void setUp() throws SQLException {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        config = context.getBean(TestConfig.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(mock(Connection.class));
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @AfterEach
    public void tearDown() {
        context.close();
        TransactionSynchronizationManager.clear();
    }

    @Test
    public void testSyncNotTriggeredBeforeCommitAndTriggeredAfterCommit() {
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());

        context.publishEvent(new ProductSyncEvent(Arrays.asList(1L, 2L)));

        //事务提交前不允许触发同步
        verifyNoInteractions(config.syncService);

        transactionManager.commit(status);

        verify(config.syncService).syncBatch(Arrays.asList(1L, 2L));
    }

    @Test
    public void testSyncNotTriggeredWhenTransactionRolledBack() {
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());

        context.publishEvent(new ProductSyncEvent(Collections.singletonList(3L)));

        transactionManager.rollback(status);

        verifyNoInteractions(config.syncService);
    }

    @Test
    public void testSyncTriggeredImmediatelyWhenNoTransaction() {
        context.publishEvent(new ProductSyncEvent(Arrays.asList(1L, 2L)));

        verify(config.syncService).syncBatch(Arrays.asList(1L, 2L));
    }

    @Test
    public void testSingleIdEventTriggeredWithSingleSync() {
        context.publishEvent(new ProductSyncEvent(Collections.singletonList(7L)));

        verify(config.syncService).sync(7L);
    }

    @Test
    public void testEmptyEventNotTriggerSync() {
        context.publishEvent(new ProductSyncEvent(Collections.emptyList()));

        verifyNoInteractions(config.syncService);
    }

    @Test
    public void testDuplicateIdsTriggerOnceWithDistinctIds() {
        context.publishEvent(new ProductSyncEvent(Arrays.asList(1L, 1L, 2L, null)));

        verify(config.syncService).syncBatch(Arrays.asList(1L, 2L));
    }

    @Test
    public void testMultipleEventsInSameTransactionTriggerAfterCommit() {
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());

        context.publishEvent(new ProductSyncEvent(Collections.singletonList(1L)));
        context.publishEvent(new ProductSyncEvent(Arrays.asList(2L, 3L)));

        verifyNoInteractions(config.syncService);

        transactionManager.commit(status);

        verify(config.syncService).sync(1L);
        verify(config.syncService).syncBatch(Arrays.asList(2L, 3L));
    }
}
