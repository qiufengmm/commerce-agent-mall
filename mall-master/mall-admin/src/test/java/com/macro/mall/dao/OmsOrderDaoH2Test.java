package com.macro.mall.dao;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.OmsOrderOperateHistoryMapper;
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
 * 使用 H2 内存库（MySQL 兼容模式）验证后台订单条件 SQL 的真实行为。
 * <p>
 * 直接加载生产用的 dao/OmsOrderDao.xml，不连接真实数据库，也不修改真实表结构。
 */
class OmsOrderDaoH2Test {

    private static final String NS = "com.macro.mall.dao.OmsOrderDao.";
    private static final Long ORDER_ID = 100L;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initSqlSessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:admin_order_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(OmsOrderMapper.class);
        configuration.addMapper(OmsOrderItemMapper.class);
        configuration.addMapper(OmsOrderOperateHistoryMapper.class);
        try (InputStream inputStream = Resources.getResourceAsStream("dao/OmsOrderDao.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "dao/OmsOrderDao.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void initSchema() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "DROP TABLE IF EXISTS oms_order");
            execute(session, "CREATE TABLE oms_order (id BIGINT PRIMARY KEY, order_sn VARCHAR(64), member_id BIGINT,"
                    + " coupon_id BIGINT, use_integration INT, status INT, delete_status INT,"
                    + " delivery_company VARCHAR(64), delivery_sn VARCHAR(64), delivery_time TIMESTAMP, modify_time TIMESTAMP)");
            execute(session, "DROP TABLE IF EXISTS pms_sku_stock");
            execute(session, "CREATE TABLE pms_sku_stock (id BIGINT PRIMARY KEY, stock INT, lock_stock INT)");
            execute(session, "DROP TABLE IF EXISTS sms_coupon_history");
            execute(session, "CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY, member_id BIGINT, coupon_id BIGINT,"
                    + " use_status INT, use_time TIMESTAMP, order_id BIGINT, order_sn VARCHAR(64))");
            execute(session, "DROP TABLE IF EXISTS ums_member");
            execute(session, "CREATE TABLE ums_member (id BIGINT PRIMARY KEY, integration INT)");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "INSERT INTO oms_order (id, order_sn, member_id, status, delete_status) VALUES (100, 'SN100', 1, 0, 0)");
            execute(session, "INSERT INTO pms_sku_stock (id, stock, lock_stock) VALUES (11, 10, 5)");
            execute(session, "INSERT INTO sms_coupon_history (id, member_id, coupon_id, use_status, order_id, order_sn)"
                    + " VALUES (1, 1, 55, 1, 100, 'SN100')");
            execute(session, "INSERT INTO ums_member (id, integration) VALUES (1, 100)");
        }
    }

    @Test
    @DisplayName("发货只能把待发货订单更新为已发货")
    void deliverOneOnlyAllowsWaitDeliverOrder() {
        assertEquals(0, update("deliverOne", params("orderId", ORDER_ID, "deliveryCompany", "顺丰", "deliverySn", "SF1")));

        insertOrder(200L, 1);
        assertEquals(1, update("deliverOne", params("orderId", 200L, "deliveryCompany", "顺丰", "deliverySn", "SF1")));
        assertEquals(2, queryInt("SELECT status FROM oms_order WHERE id = 200"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM oms_order WHERE id = 200 AND delivery_time IS NOT NULL"));

        // 重复发货命中 0 行
        assertEquals(0, update("deliverOne", params("orderId", 200L, "deliveryCompany", "顺丰", "deliverySn", "SF2")));
        assertEquals("SF1", queryString("SELECT delivery_sn FROM oms_order WHERE id = 200"));
    }

    @Test
    @DisplayName("关闭订单只能把待付款订单更新为已关闭，已支付订单不能关闭")
    void closeOrderIfUnpaidOnlyAllowsUnpaidOrder() {
        assertEquals(1, update("closeOrderIfUnpaid", params("orderId", ORDER_ID)));
        assertEquals(4, queryInt("SELECT status FROM oms_order WHERE id = 100"));
        assertEquals(0, update("closeOrderIfUnpaid", params("orderId", ORDER_ID)));

        insertOrder(200L, 1);
        assertEquals(0, update("closeOrderIfUnpaid", params("orderId", 200L)));
        assertEquals(1, queryInt("SELECT status FROM oms_order WHERE id = 200"));
    }

    @Test
    @DisplayName("释放锁定库存不会让 lock_stock 变成负数")
    void releaseSkuStockLockNeverGoesNegative() {
        assertEquals(1, update("releaseSkuStockLock", params("skuId", 11L, "quantity", 5)));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));

        // 重复释放命中 0 行
        assertEquals(0, update("releaseSkuStockLock", params("skuId", 11L, "quantity", 5)));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = 11"));
    }

    @Test
    @DisplayName("返还优惠券只命中绑定在当前订单上的已使用记录，重复返还不会重复命中")
    void returnCouponOnlyHitsCurrentOrderRecordOnce() {
        assertEquals(1, update("returnCoupon", params("orderId", ORDER_ID, "memberId", 1L)));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 1 AND order_id IS NULL AND order_sn IS NULL"));

        // 重复返还命中 0 行
        assertEquals(0, update("returnCoupon", params("orderId", ORDER_ID, "memberId", 1L)));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 1 AND use_status = 0"));
    }

    @Test
    @DisplayName("同一会员多个待付款订单：乱序取消只返还当前订单绑定的优惠券，不会返还其它订单的优惠券")
    void returnCouponDoesNotTouchOtherOrderCoupon() {
        //会员 1 使用同种优惠券 55 创建两个待付款订单，分别绑定不同的优惠券记录
        execute("INSERT INTO oms_order (id, order_sn, member_id, status, delete_status) VALUES (200, 'SN200', 1, 0, 0)");
        execute("INSERT INTO sms_coupon_history (id, member_id, coupon_id, use_status, order_id, order_sn)"
                + " VALUES (2, 1, 55, 1, 200, 'SN200')");

        //先取消第二个订单：只返还绑定在 200 上的记录 2
        assertEquals(1, update("returnCoupon", params("orderId", 200L, "memberId", 1L)));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 2"));
        assertEquals(1, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));

        //再取消第一个订单：只返还绑定在 100 上的记录 1
        assertEquals(1, update("returnCoupon", params("orderId", ORDER_ID, "memberId", 1L)));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE use_status = 1 AND order_id IS NOT NULL"));

        //两个订单重复取消都不会再返还
        assertEquals(0, update("returnCoupon", params("orderId", 200L, "memberId", 1L)));
        assertEquals(0, update("returnCoupon", params("orderId", ORDER_ID, "memberId", 1L)));
    }

    @Test
    @DisplayName("关闭订单返还积分")
    void refundIntegrationAddsIntegration() {
        assertEquals(1, update("refundIntegration", params("memberId", 1L, "integration", 20)));
        assertEquals(120, queryInt("SELECT integration FROM ums_member WHERE id = 1"));
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

    private void execute(String sql) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, sql);
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

    private String queryString(String sql) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try (var rs = session.getConnection().createStatement().executeQuery(sql)) {
                if (!rs.next()) {
                    throw new IllegalStateException("查询没有返回任何行:" + sql);
                }
                return rs.getString(1);
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
