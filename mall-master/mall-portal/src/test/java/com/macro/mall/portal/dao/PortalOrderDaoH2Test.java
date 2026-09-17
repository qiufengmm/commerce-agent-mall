package com.macro.mall.portal.dao;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 使用 H2 内存库（MySQL 兼容模式）验证门户订单条件 SQL 的真实行为。
 * <p>
 * 测试直接加载生产用的 dao/PortalOrderDao.xml，不连接真实数据库，也不修改真实表结构。
 */
class PortalOrderDaoH2Test {

    private static final String NS = "com.macro.mall.portal.dao.PortalOrderDao.";
    private static final Long SKU_ID = 11L;
    private static final Long ORDER_ID = 100L;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initSqlSessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:portal_order_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        // mall-mbg 生成的映射提供 BaseResultMap，供被测映射继承
        configuration.addMapper(OmsOrderMapper.class);
        configuration.addMapper(OmsOrderItemMapper.class);
        try (InputStream inputStream = Resources.getResourceAsStream("dao/PortalOrderDao.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "dao/PortalOrderDao.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void initSchema() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "DROP TABLE IF EXISTS pms_sku_stock");
            execute(session, "CREATE TABLE pms_sku_stock (id BIGINT PRIMARY KEY, stock INT, lock_stock INT)");
            execute(session, "DROP TABLE IF EXISTS oms_order");
            execute(session, "CREATE TABLE oms_order ("
                    + "id BIGINT PRIMARY KEY, order_sn VARCHAR(64), member_id BIGINT, coupon_id BIGINT,"
                    + "use_integration INT, status INT, pay_type INT, payment_time TIMESTAMP, receive_time TIMESTAMP,"
                    + "confirm_status INT, delete_status INT, modify_time TIMESTAMP, create_time TIMESTAMP)");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "INSERT INTO pms_sku_stock (id, stock, lock_stock) VALUES (11, 10, 0)");
            execute(session, "INSERT INTO oms_order (id, order_sn, member_id, status, delete_status) VALUES (100, 'SN100', 1, 0, 0)");
        }
    }

    @Test
    @DisplayName("可用库存充足时锁定成功，可用库存不变")
    void lockSkuStockWhenAvailableStockEnough() {
        assertEquals(1, update("lockSkuStock", params("skuId", SKU_ID, "quantity", 3)));
        assertEquals(10, queryInt("SELECT stock FROM pms_sku_stock WHERE id = 11"));
        assertEquals(3, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("可用库存不足时锁定失败且不产生任何写入")
    void lockSkuStockWhenAvailableStockNotEnough() {
        // 已锁定 8，可用库存只剩 2
        update("lockSkuStock", params("skuId", SKU_ID, "quantity", 8));
        assertEquals(0, update("lockSkuStock", params("skuId", SKU_ID, "quantity", 3)));
        assertEquals(8, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));
        assertEquals(10, queryInt("SELECT stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("锁定库存不会把可用库存算成负数")
    void lockSkuStockNeverExceedsStock() {
        assertEquals(1, update("lockSkuStock", params("skuId", SKU_ID, "quantity", 10)));
        assertEquals(0, update("lockSkuStock", params("skuId", SKU_ID, "quantity", 1)));
        assertEquals(0, queryInt("SELECT stock - lock_stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("支付成功扣减库存：真实库存与锁定库存同时减少")
    void deductSkuStockDecreasesBothStockAndLockStock() {
        update("lockSkuStock", params("skuId", SKU_ID, "quantity", 3));
        assertEquals(1, update("deductSkuStock", params("skuId", SKU_ID, "quantity", 3)));
        assertEquals(7, queryInt("SELECT stock FROM pms_sku_stock WHERE id = 11"));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("锁定库存不足时不能扣减真实库存，避免超卖")
    void deductSkuStockFailsWhenLockStockNotEnough() {
        assertEquals(0, update("deductSkuStock", params("skuId", SKU_ID, "quantity", 3)));
        assertEquals(10, queryInt("SELECT stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("重复释放锁定库存不会把 lock_stock 变成负数")
    void releaseSkuStockLockIsIdempotentForDataSafety() {
        update("lockSkuStock", params("skuId", SKU_ID, "quantity", 2));
        assertEquals(1, update("releaseSkuStockLock", params("skuId", SKU_ID, "quantity", 2)));
        assertEquals(0, update("releaseSkuStockLock", params("skuId", SKU_ID, "quantity", 2)));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("支付状态只能从 0 转为 1，重复或越级调用命中 0 行")
    void payOrderIfUnpaidOnlyAllowsUnpaidOrder() {
        assertEquals(1, update("payOrderIfUnpaid", params("orderId", ORDER_ID, "payType", 1)));
        assertEquals(1, queryInt("SELECT status FROM oms_order WHERE id = 100"));
        assertEquals(1, queryInt("SELECT pay_type FROM oms_order WHERE id = 100"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM oms_order WHERE id = 100 AND payment_time IS NOT NULL"));

        // 重复回调：状态已不是 0
        assertEquals(0, update("payOrderIfUnpaid", params("orderId", ORDER_ID, "payType", 1)));
        // 支付时间与支付方式保持首次写入的值
        assertEquals(1, queryInt("SELECT pay_type FROM oms_order WHERE id = 100"));
    }

    @Test
    @DisplayName("已关闭订单不能再次标记为已支付")
    void payOrderIfUnpaidRejectsClosedOrder() {
        assertEquals(1, update("closeOrderIfUnpaid", params("orderId", ORDER_ID)));
        assertEquals(4, queryInt("SELECT status FROM oms_order WHERE id = 100"));
        assertEquals(0, update("payOrderIfUnpaid", params("orderId", ORDER_ID, "payType", 1)));
        assertEquals(4, queryInt("SELECT status FROM oms_order WHERE id = 100"));
    }

    @Test
    @DisplayName("关闭订单只能从 0 转为 4，已支付订单不能被关闭")
    void closeOrderIfUnpaidOnlyAllowsUnpaidOrder() {
        assertEquals(1, update("payOrderIfUnpaid", params("orderId", ORDER_ID, "payType", 1)));
        assertEquals(0, update("closeOrderIfUnpaid", params("orderId", ORDER_ID)));
        assertEquals(1, queryInt("SELECT status FROM oms_order WHERE id = 100"));
    }

    @Test
    @DisplayName("确认收货只能从 2 转为 3")
    void confirmReceiveIfDeliveredOnlyAllowsDeliveredOrder() {
        assertEquals(0, update("confirmReceiveIfDelivered", params("orderId", ORDER_ID)));

        insertOrder(200L, 2);
        assertEquals(1, update("confirmReceiveIfDelivered", params("orderId", 200L)));
        assertEquals(3, queryInt("SELECT status FROM oms_order WHERE id = 200"));
        assertEquals(1, queryInt("SELECT confirm_status FROM oms_order WHERE id = 200"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM oms_order WHERE id = 200 AND receive_time IS NOT NULL"));

        // 重复确认收货命中 0 行
        assertEquals(0, update("confirmReceiveIfDelivered", params("orderId", 200L)));
    }

    private void insertOrder(Long id, Integer status) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "INSERT INTO oms_order (id, order_sn, member_id, status, delete_status) VALUES ("
                    + id + ", 'SN" + id + "', 1, " + status + ", 0)");
        }
    }

    private int update(String statementName, Map<String, Object> params) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.update(NS + statementName, params);
        }
    }

    private void execute(SqlSession session, String sql) {
        try {
            session.getConnection().createStatement().execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行SQL失败:" + sql, e);
        }
    }

    private Integer queryInt(String sql) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try (var rs = session.getConnection().createStatement().executeQuery(sql)) {
                if (!rs.next()) {
                    throw new IllegalStateException("查询没有返回任何行:" + sql);
                }
                return rs.getInt(1);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("查询失败:" + sql, e);
        }
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
