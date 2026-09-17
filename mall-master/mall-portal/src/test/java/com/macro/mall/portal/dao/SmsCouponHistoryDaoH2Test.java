package com.macro.mall.portal.dao;

import com.macro.mall.mapper.SmsCouponHistoryMapper;
import com.macro.mall.mapper.SmsCouponMapper;
import com.macro.mall.mapper.SmsCouponProductCategoryRelationMapper;
import com.macro.mall.mapper.SmsCouponProductRelationMapper;
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
 * 使用 H2 内存库（MySQL 兼容模式）验证门户优惠券占用与返还的条件 SQL 行为。
 * <p>
 * 直接加载生产用的 dao/SmsCouponHistoryDao.xml，不连接真实数据库，也不修改真实表结构。
 * 重点验证优惠券与订单的绑定关系：占用时绑定 order_id/order_sn，返还时只返还当前订单绑定的记录。
 */
class SmsCouponHistoryDaoH2Test {

    private static final String NS = "com.macro.mall.portal.dao.SmsCouponHistoryDao.";
    private static final Long MEMBER_ID = 1L;
    private static final Long COUPON_ID = 55L;
    private static final Long FIRST_ORDER_ID = 100L;
    private static final Long SECOND_ORDER_ID = 200L;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initSqlSessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:portal_coupon_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        //生产 XML 中的 resultMap 依赖 MBG 生成的映射，需要先注册
        configuration.addMapper(SmsCouponHistoryMapper.class);
        configuration.addMapper(SmsCouponMapper.class);
        configuration.addMapper(SmsCouponProductRelationMapper.class);
        configuration.addMapper(SmsCouponProductCategoryRelationMapper.class);
        try (InputStream inputStream = Resources.getResourceAsStream("dao/SmsCouponHistoryDao.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "dao/SmsCouponHistoryDao.xml",
                    configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void initSchema() {
        execute("DROP TABLE IF EXISTS sms_coupon_history");
        execute("CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY, member_id BIGINT, coupon_id BIGINT,"
                + " use_status INT, use_time TIMESTAMP, order_id BIGINT, order_sn VARCHAR(64))");
        //同一会员持有两张同种优惠券
        execute("INSERT INTO sms_coupon_history (id, member_id, coupon_id, use_status) VALUES (1, 1, 55, 0)");
        execute("INSERT INTO sms_coupon_history (id, member_id, coupon_id, use_status) VALUES (2, 1, 55, 0)");
    }

    @Test
    @DisplayName("占用优惠券会同时绑定当前订单的 order_id 与 order_sn")
    void useCouponBindsCouponToCurrentOrder() {
        assertEquals(1, useCoupon(FIRST_ORDER_ID, "SN100"));

        assertEquals(1, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history"
                + " WHERE id = 1 AND order_id = 100 AND order_sn = 'SN100' AND use_time IS NOT NULL"));
        //另一张同种优惠券仍未被占用
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 2"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 2 AND order_id IS NULL"));
    }

    @Test
    @DisplayName("两个待付款订单分别绑定不同的优惠券，乱序取消只返还当前订单绑定的那张")
    void returnCouponOnlyReturnsCurrentOrderCoupon() {
        assertEquals(1, useCoupon(FIRST_ORDER_ID, "SN100"));
        assertEquals(1, useCoupon(SECOND_ORDER_ID, "SN200"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 1 AND order_id = 100"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 2 AND order_id = 200"));

        //先取消第二个订单：只返还绑定在 200 上的记录 2，绑定在 100 上的记录 1 不受影响
        assertEquals(1, returnCoupon(SECOND_ORDER_ID));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 2"));
        assertEquals(1, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 1 AND order_id = 100"));

        //再取消第一个订单：只返还绑定在 100 上的记录 1
        assertEquals(1, returnCoupon(FIRST_ORDER_ID));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE use_status = 1 AND order_id IS NOT NULL"));

        //重复取消都不会再返还
        assertEquals(0, returnCoupon(FIRST_ORDER_ID));
        assertEquals(0, returnCoupon(SECOND_ORDER_ID));
        assertEquals(2, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE use_status = 0 AND order_id IS NULL"));
    }

    @Test
    @DisplayName("占用与返还都是条件更新：没有可用记录时占用返回 0，非本人订单不能返还")
    void useAndReturnCouponAreConditional() {
        assertEquals(1, useCoupon(FIRST_ORDER_ID, "SN100"));
        assertEquals(1, useCoupon(SECOND_ORDER_ID, "SN200"));
        //两张同种优惠券都已占用，第三次占用命中 0 行
        assertEquals(0, useCoupon(300L, "SN300"));
        assertEquals(0, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE order_id = 300"));

        //其它会员不能返还该优惠券
        assertEquals(0, update("returnCoupon", params("orderId", FIRST_ORDER_ID, "memberId", 2L)));
        assertEquals(1, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
    }

    private int useCoupon(Long orderId, String orderSn) {
        return update("useCoupon", params("memberId", MEMBER_ID, "couponId", COUPON_ID,
                "orderId", orderId, "orderSn", orderSn));
    }

    private int returnCoupon(Long orderId) {
        return update("returnCoupon", params("orderId", orderId, "memberId", MEMBER_ID));
    }

    private int update(String statementName, Map<String, Object> params) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            return session.update(NS + statementName, params);
        }
    }

    private void execute(String sql) {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            try {
                session.getConnection().createStatement().execute(sql);
            } catch (Exception e) {
                throw new IllegalStateException("执行SQL失败:" + sql, e);
            }
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
