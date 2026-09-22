package com.macro.mall.service.impl;

import com.macro.mall.dao.*;
import com.macro.mall.dto.PmsProductParam;
import com.macro.mall.event.ProductSyncEvent;
import com.macro.mall.event.ProductSyncEventListener;
import com.macro.mall.mapper.*;
import com.macro.mall.model.PmsProduct;
import com.macro.mall.model.PmsProductExample;
import com.macro.mall.service.EsProductSyncService;
import com.macro.mall.service.PmsProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 商品状态变更事务边界测试
 * 从Spring容器获取经过代理的生产Bean执行：验证四点：
 * 1. 四个状态方法经Spring代理调用且实现方法上有@Transactional；
 * 2. 事务提交前不会调用EsProductSyncService；
 * 3. 提交成功后恰好同步一次；
 * 4. 回滚后零同步，同步服务异常不影响已提交的商品事务。
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = PmsProductStatusTransactionTest.ProductTransactionTestConfig.class)
public class PmsProductStatusTransactionTest {
    private static final List<String> STATUS_METHODS = List.of(
            "updatePublishStatus", "updateRecommendStatus", "updateNewStatus", "updateDeleteStatus");

    @MockBean
    private DataSource dataSource;
    @MockBean
    private PmsProductMapper productMapper;
    @MockBean
    private PmsMemberPriceDao memberPriceDao;
    @MockBean
    private PmsMemberPriceMapper memberPriceMapper;
    @MockBean
    private PmsProductLadderDao productLadderDao;
    @MockBean
    private PmsProductLadderMapper productLadderMapper;
    @MockBean
    private PmsProductFullReductionDao productFullReductionDao;
    @MockBean
    private PmsProductFullReductionMapper productFullReductionMapper;
    @MockBean
    private PmsSkuStockDao skuStockDao;
    @MockBean
    private PmsSkuStockMapper skuStockMapper;
    @MockBean
    private PmsProductAttributeValueDao productAttributeValueDao;
    @MockBean
    private PmsProductAttributeValueMapper productAttributeValueMapper;
    @MockBean
    private CmsSubjectProductRelationDao subjectProductRelationDao;
    @MockBean
    private CmsSubjectProductRelationMapper subjectProductRelationMapper;
    @MockBean
    private CmsPrefrenceAreaProductRelationDao prefrenceAreaProductRelationDao;
    @MockBean
    private CmsPrefrenceAreaProductRelationMapper prefrenceAreaProductRelationMapper;
    @MockBean
    private PmsProductDao productDao;
    @MockBean
    private PmsProductVertifyRecordDao productVertifyRecordDao;
    @MockBean
    private EsProductSyncService esProductSyncService;

    @Autowired
    private PmsProductService pmsProductService;
    @Autowired
    private ApplicationContext applicationContext;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Connection connection;
    private TransactionOperations transactionOperations;

    @BeforeEach
    void setUp() throws Exception {
        connection = Mockito.mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        transactionOperations = new TransactionTemplate(transactionManager);
        when(productMapper.updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class))).thenReturn(2);
    }

    @Test
    public void testStatusMethodsAreCalledThroughSpringProxy() throws Exception {
        assertNotNull(pmsProductService, "应从Spring容器取得商品服务");
        assertTrue(AopUtils.isAopProxy(pmsProductService), "商品状态方法必须经Spring代理调用");
        assertEquals(PmsProductServiceImpl.class, AopProxyUtils.ultimateTargetClass(pmsProductService),
                "代理目标类必须是生产实现类");
        for (String methodName : STATUS_METHODS) {
            Method targetMethod = PmsProductServiceImpl.class.getMethod(methodName, List.class, Integer.class);
            assertNotNull(AnnotationUtils.findAnnotation(targetMethod, Transactional.class),
                    methodName + " 实现类方法必须声明@Transactional");
        }
        Method createMethod = PmsProductServiceImpl.class.getMethod("create", PmsProductParam.class);
        assertNotNull(AnnotationUtils.findAnnotation(createMethod, Transactional.class),
                "发布同步事件的新增商品方法必须运行在事务中");
        Method updateMethod = PmsProductServiceImpl.class.getMethod("update", Long.class, PmsProductParam.class);
        assertNotNull(AnnotationUtils.findAnnotation(updateMethod, Transactional.class),
                "发布同步事件的编辑商品方法必须运行在事务中");
    }

    @Test
    public void testUpdatePublishStatusSyncAfterCommitOnly() {
        List<Long> ids = Arrays.asList(1L, 2L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.updatePublishStatus(ids, 1);
            verify(productMapper, times(1)).updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class));
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).syncBatch(ids);
    }

    @Test
    public void testUpdateRecommendStatusSyncAfterCommitOnly() {
        List<Long> ids = Arrays.asList(3L, 4L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.updateRecommendStatus(ids, 1);
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).syncBatch(ids);
    }

    @Test
    public void testUpdateNewStatusSyncAfterCommitOnly() {
        List<Long> ids = Arrays.asList(5L, 6L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.updateNewStatus(ids, 1);
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).syncBatch(ids);
    }

    @Test
    public void testUpdateDeleteStatusSyncAfterCommitOnly() {
        List<Long> ids = Arrays.asList(7L, 8L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.updateDeleteStatus(ids, 1);
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).syncBatch(ids);
    }

    @Test
    public void testCreateSyncAfterCommitOnly() {
        stubInsertSelectiveAssignNewId(101L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.create(new PmsProductParam());
            verify(productMapper, times(1)).insertSelective(any(PmsProduct.class));
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).sync(101L);
    }

    @Test
    public void testUpdateSyncAfterCommitOnly() {
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.update(202L, new PmsProductParam());
            verifyNoInteractions(esProductSyncService);
        });
        verify(esProductSyncService, times(1)).sync(202L);
    }

    @Test
    public void testCreateRollbackKeepsEsUntouched() {
        stubInsertSelectiveAssignNewId(303L);
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.create(new PmsProductParam());
            status.setRollbackOnly();
        });
        verify(esProductSyncService, never()).sync(any(Long.class));
        verify(esProductSyncService, never()).syncBatch(anyList());
    }

    @Test
    public void testUpdateRollbackKeepsEsUntouched() {
        transactionOperations.executeWithoutResult(status -> {
            pmsProductService.update(404L, new PmsProductParam());
            status.setRollbackOnly();
        });
        verify(esProductSyncService, never()).sync(any(Long.class));
        verify(esProductSyncService, never()).syncBatch(anyList());
    }

    @Test
    public void testEventPublishedWithoutTransactionDoesNotSync() {
        //没有事务时不再回退执行，事件不会触发同步，避免读到未提交数据
        applicationContext.publishEvent(new ProductSyncEvent(Arrays.asList(41L, 42L)));

        verifyNoInteractions(esProductSyncService);
    }

    @Test
    public void testSyncFailureDoesNotRollbackCreateTransaction() throws Exception {
        stubInsertSelectiveAssignNewId(505L);
        doThrow(new RuntimeException("ES不可用")).when(esProductSyncService).sync(505L);

        transactionOperations.executeWithoutResult(status -> pmsProductService.create(new PmsProductParam()));

        verify(esProductSyncService, times(1)).sync(505L);
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void testRollbackOnlyKeepsEsUntouched() throws Exception {
        for (String methodName : STATUS_METHODS) {
            reset(esProductSyncService, productMapper);
            List<Long> ids = new ArrayList<>(Arrays.asList(11L, 12L));
            transactionOperations.executeWithoutResult(status -> {
                invokeStatusMethod(methodName, ids);
                status.setRollbackOnly();
            });
            verifyNoInteractions(esProductSyncService);
        }
    }

    @Test
    public void testBusinessExceptionRollbackKeepsEsUntouched() throws Exception {
        for (String methodName : STATUS_METHODS) {
            reset(esProductSyncService, productMapper);
            List<Long> ids = new ArrayList<>(Arrays.asList(21L, 22L));
            assertThrows(IllegalStateException.class, () -> transactionOperations.executeWithoutResult(status -> {
                invokeStatusMethod(methodName, ids);
                throw new IllegalStateException("业务异常触发回滚");
            }));
            verifyNoInteractions(esProductSyncService);
        }
    }

    @Test
    public void testSyncFailureDoesNotRollbackProductTransaction() throws Exception {
        try {
            List<Long> ids = Arrays.asList(31L, 32L);
            doThrow(new RuntimeException("ES不可用")).when(esProductSyncService).syncBatch(ids);
            transactionOperations.executeWithoutResult(status -> pmsProductService.updatePublishStatus(ids, 0));
            verify(productMapper, times(1)).updateByExampleSelective(any(PmsProduct.class), any(PmsProductExample.class));
            verify(esProductSyncService, times(1)).syncBatch(ids);
            verify(connection, times(1)).commit();
            verify(connection, never()).rollback();
        } finally {
            //测试结束前确认监听器与事件都能从同一个容器取得，避免容器内存在多套发布机制
            assertNotNull(applicationContext.getBean(ProductSyncEventListener.class));
        }
    }

    /**
     * 模拟MyBatis回填自增主键，新增商品后才能拿到商品id发布同步事件
     */
    private void stubInsertSelectiveAssignNewId(Long productId) {
        when(productMapper.insertSelective(any(PmsProduct.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, PmsProduct.class).setId(productId);
            return 1;
        });
    }

    private void invokeStatusMethod(String methodName, List<Long> ids) {
        try {
            Method proxyMethod = PmsProductService.class.getMethod(methodName, List.class, Integer.class);
            proxyMethod.invoke(pmsProductService, ids, 1);
        } catch (Exception e) {
            throw new IllegalStateException("调用商品状态方法失败：" + methodName, e);
        }
    }

    /**
     * 仅包含事务相关的最小生产配置，数据源使用测试替身，不连接真实数据库
     */
    @Configuration
    @EnableTransactionManagement
    public static class ProductTransactionTestConfig {
        @Bean
        public PmsProductServiceImpl pmsProductService() {
            return new PmsProductServiceImpl();
        }

        @Bean
        public ProductSyncEventListener productSyncEventListener() {
            return new ProductSyncEventListener();
        }

        @Bean
        public TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }

        @Bean
        public DataSourceTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }
}
