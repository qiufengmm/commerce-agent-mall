package com.macro.mall.portal.service.impl;

import com.macro.mall.mapper.OmsOrderItemMapper;
import com.macro.mall.mapper.OmsOrderMapper;
import com.macro.mall.mapper.SmsCouponHistoryMapper;
import com.macro.mall.mapper.SmsCouponMapper;
import com.macro.mall.mapper.SmsCouponProductCategoryRelationMapper;
import com.macro.mall.mapper.SmsCouponProductRelationMapper;
import com.macro.mall.model.OmsOrderSetting;
import com.macro.mall.model.UmsMember;
import com.macro.mall.portal.dao.PortalMemberDao;
import com.macro.mall.portal.dao.PortalOrderDao;
import com.macro.mall.portal.dao.SmsCouponHistoryDao;
import com.macro.mall.portal.domain.OmsOrderDetail;
import com.macro.mall.portal.domain.OrderOperationResult;
import com.macro.mall.portal.service.UmsMemberService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 基于 H2 内存库的真实事务测试：验证订单关闭补偿失败时事务确实回滚。
 * <p>
 * 与 Mockito 单测不同，本测试使用真实的 DataSource、真实的事务管理器和真实的生产 SQL：
 * <ul>
 *     <li>状态转换与补偿在同一个真实事务中执行；</li>
 *     <li>补偿失败后数据库里订单状态必须仍是待付款，而不是已关闭；</li>
 *     <li>批量取消时成功订单可以提交，失败订单只回滚自己。</li>
 * </ul>
 * 全程使用 H2 内存库，不连接真实数据库，不修改真实表结构。
 */
class OmsPortalOrderTransactionH2Test {

    private static final Long MEMBER_ID = 1L;
    private static final Long SKU_ID = 11L;
    private static final Long COUPON_ID = 55L;

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static TransactionTemplate transactionTemplate;
    private static PortalOrderDao portalOrderDao;
    private static SmsCouponHistoryDao couponHistoryDao;
    private static OmsOrderMapper orderMapper;
    private static OmsOrderItemMapper orderItemMapper;
    private static com.macro.mall.mapper.OmsOrderSettingMapper orderSettingMapper;

    private OmsPortalOrderServiceImpl orderService;

    @BeforeAll
    static void initInfrastructure() throws Exception {
        JdbcDataSource h2DataSource = new JdbcDataSource();
        h2DataSource.setURL("jdbc:h2:mem:portal_order_tx;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2DataSource.setUser("sa");
        h2DataSource.setPassword("");
        dataSource = h2DataSource;

        Environment environment = new Environment("h2", new SpringManagedTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        //先注册 MBG Mapper，自定义 DAO XML 的 resultMap 会继承它们的 BaseResultMap
        configuration.addMapper(OmsOrderMapper.class);
        configuration.addMapper(OmsOrderItemMapper.class);
        configuration.addMapper(SmsCouponHistoryMapper.class);
        configuration.addMapper(SmsCouponMapper.class);
        configuration.addMapper(SmsCouponProductRelationMapper.class);
        configuration.addMapper(SmsCouponProductCategoryRelationMapper.class);
        loadDaoXml(configuration, "dao/PortalOrderDao.xml");
        loadDaoXml(configuration, "dao/SmsCouponHistoryDao.xml");
        loadDaoXml(configuration, "dao/PortalMemberDao.xml");

        sqlSessionFactory = new org.apache.ibatis.session.defaults.DefaultSqlSessionFactory(configuration);
        SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        portalOrderDao = sqlSessionTemplate.getMapper(PortalOrderDao.class);
        couponHistoryDao = sqlSessionTemplate.getMapper(SmsCouponHistoryDao.class);
        orderMapper = sqlSessionTemplate.getMapper(OmsOrderMapper.class);
        orderItemMapper = sqlSessionTemplate.getMapper(OmsOrderItemMapper.class);
        orderSettingMapper = mock(com.macro.mall.mapper.OmsOrderSettingMapper.class);
    }

    private static void loadDaoXml(Configuration configuration, String resource) throws Exception {
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @BeforeEach
    void initSchemaAndService() {
        execute("DROP TABLE IF EXISTS oms_order");
        execute("CREATE TABLE oms_order (id BIGINT PRIMARY KEY, member_id BIGINT, coupon_id BIGINT, order_sn VARCHAR(64),"
                + " create_time TIMESTAMP, member_username VARCHAR(64), total_amount DECIMAL(10,2), pay_amount DECIMAL(10,2),"
                + " freight_amount DECIMAL(10,2), promotion_amount DECIMAL(10,2), integration_amount DECIMAL(10,2),"
                + " coupon_amount DECIMAL(10,2), discount_amount DECIMAL(10,2), pay_type INT, source_type INT, status INT,"
                + " order_type INT, delivery_company VARCHAR(64), delivery_sn VARCHAR(64), auto_confirm_day INT,"
                + " integration INT, growth INT, promotion_info VARCHAR(512), bill_type INT, bill_header VARCHAR(200),"
                + " bill_content VARCHAR(200), bill_receiver_phone VARCHAR(32), bill_receiver_email VARCHAR(64),"
                + " receiver_name VARCHAR(100), receiver_phone VARCHAR(32), receiver_post_code VARCHAR(32),"
                + " receiver_province VARCHAR(32), receiver_city VARCHAR(32), receiver_region VARCHAR(32),"
                + " receiver_detail_address VARCHAR(200), note VARCHAR(500), confirm_status INT, delete_status INT,"
                + " use_integration INT, payment_time TIMESTAMP, delivery_time TIMESTAMP, receive_time TIMESTAMP,"
                + " comment_time TIMESTAMP, modify_time TIMESTAMP)");
        execute("DROP TABLE IF EXISTS oms_order_item");
        execute("CREATE TABLE oms_order_item (id BIGINT PRIMARY KEY, order_id BIGINT, order_sn VARCHAR(64),"
                + " product_id BIGINT, product_pic VARCHAR(500), product_name VARCHAR(200), product_brand VARCHAR(200),"
                + " product_sn VARCHAR(64), product_price DECIMAL(10,2), product_quantity INT, product_sku_id BIGINT,"
                + " product_sku_code VARCHAR(64), product_category_id BIGINT, promotion_name VARCHAR(200),"
                + " promotion_amount DECIMAL(10,2), coupon_amount DECIMAL(10,2), integration_amount DECIMAL(10,2),"
                + " real_amount DECIMAL(10,2), gift_integration INT, gift_growth INT, product_attr VARCHAR(500))");
        execute("DROP TABLE IF EXISTS pms_sku_stock");
        execute("CREATE TABLE pms_sku_stock (id BIGINT PRIMARY KEY, stock INT, lock_stock INT)");
        execute("DROP TABLE IF EXISTS sms_coupon_history");
        execute("CREATE TABLE sms_coupon_history (id BIGINT PRIMARY KEY, member_id BIGINT, coupon_id BIGINT,"
                + " use_status INT, use_time TIMESTAMP, order_id BIGINT, order_sn VARCHAR(64))");
        execute("DROP TABLE IF EXISTS ums_member");
        execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, integration INT)");

        orderService = new OmsPortalOrderServiceImpl();
        injectByType(orderService,
                transactionTemplate,
                portalOrderDao,
                couponHistoryDao,
                orderMapper,
                orderItemMapper,
                new H2MemberService());
    }

    @Test
    @DisplayName("真实事务：释放锁定库存失败时，订单状态回滚为待付款，不留下已关闭但未补偿的订单")
    void cancelOrderRollsBackWhenStockReleaseFails() {
        insertOrder(100L, "SN100", 0, null, 0);
        insertOrderItem(1L, 100L, SKU_ID, 1);
        //锁定库存为 0，释放条件不满足，补偿必然失败
        insertSkuStock(SKU_ID, 10, 0);

        assertThrows(RuntimeException.class,
                () -> transactionTemplate.execute(status -> orderService.cancelOrder(100L)));

        //事务回滚后订单仍是待付款，重试仍可以继续关闭并补偿
        assertEquals(0, queryInt("SELECT status FROM oms_order WHERE id = 100"));
    }

    @Test
    @DisplayName("真实事务：返还优惠券失败时，订单状态回滚为待付款")
    void cancelOrderRollsBackWhenCouponReturnFails() {
        insertOrder(200L, "SN200", 0, COUPON_ID, 0);
        insertSkuStock(SKU_ID, 10, 0);
        //历史数据场景：订单使用了优惠券，但没有绑定 sms_coupon_history.order_id，按订单返还命中 0 行

        assertThrows(RuntimeException.class,
                () -> transactionTemplate.execute(status -> orderService.cancelOrder(200L)));

        assertEquals(0, queryInt("SELECT status FROM oms_order WHERE id = 200"));
    }

    @Test
    @DisplayName("真实事务：补偿全部成功时订单提交为已关闭，库存、优惠券与积分都只变动一次")
    void cancelOrderCommitsWhenCompensationSucceeds() {
        insertOrder(300L, "SN300", 0, COUPON_ID, 20);
        insertOrderItem(1L, 300L, SKU_ID, 2);
        insertSkuStock(SKU_ID, 10, 2);
        insertCouponHistory(1L, MEMBER_ID, COUPON_ID, 1, 300L, "SN300");
        execute("INSERT INTO ums_member (id, integration) VALUES (" + MEMBER_ID + ", 100)");

        OrderOperationResult result = transactionTemplate.execute(status -> orderService.cancelOrder(300L));

        assertEquals(OrderOperationResult.SUCCESS, result);
        assertEquals(4, queryInt("SELECT status FROM oms_order WHERE id = 300"));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = " + SKU_ID));
        assertEquals(0, queryInt("SELECT use_status FROM sms_coupon_history WHERE id = 1"));
        assertEquals(1, queryInt("SELECT COUNT(*) FROM sms_coupon_history WHERE id = 1 AND order_id IS NULL"));
        assertEquals(120, queryInt("SELECT integration FROM ums_member WHERE id = " + MEMBER_ID));

        //重复取消：幂等且不再补偿
        assertEquals(OrderOperationResult.IDEMPOTENT,
                transactionTemplate.execute(status -> orderService.cancelOrder(300L)));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = " + SKU_ID));
        assertEquals(120, queryInt("SELECT integration FROM ums_member WHERE id = " + MEMBER_ID));
    }

    @Test
    @DisplayName("真实事务：批量超时取消时成功订单提交，补偿失败的订单独立回滚并被抛出")
    void cancelTimeOutOrderCommitsSuccessAndRollsBackFailureIndependently() {
        //400 使用了优惠券但没有绑定记录，补偿失败；500 库存与优惠券都正常
        insertOrder(400L, "SN400", 0, COUPON_ID, 0);
        insertOrder(500L, "SN500", 0, null, 0);
        insertOrderItem(1L, 500L, SKU_ID, 1);
        insertSkuStock(SKU_ID, 10, 1);

        OmsOrderSetting setting = new OmsOrderSetting();
        setting.setNormalOrderOvertime(1);
        when(orderSettingMapper.selectByPrimaryKey(1L)).thenReturn(setting);

        PortalOrderDao batchOrderDao = spy(portalOrderDao);
        doReturn(Arrays.asList(timeOutOrder(400L, "SN400"), timeOutOrder(500L, "SN500")))
                .when(batchOrderDao).getTimeOutOrders(anyInt());
        injectByType(orderService, batchOrderDao, orderSettingMapper);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> orderService.cancelTimeOutOrder());
        assertTrue(exception.getMessage().contains("400"),
                "失败订单必须出现在异常信息中，实际：" + exception.getMessage());

        //失败订单回滚后仍是待付款，成功订单已提交为已关闭
        assertEquals(0, queryInt("SELECT status FROM oms_order WHERE id = 400"));
        assertEquals(4, queryInt("SELECT status FROM oms_order WHERE id = 500"));
        assertEquals(0, queryInt("SELECT lock_stock FROM pms_sku_stock WHERE id = " + SKU_ID));
    }

    private OmsOrderDetail timeOutOrder(Long id, String orderSn) {
        OmsOrderDetail detail = new OmsOrderDetail();
        detail.setId(id);
        detail.setOrderSn(orderSn);
        detail.setMemberId(MEMBER_ID);
        return detail;
    }

    private void insertOrder(Long id, String orderSn, Integer status, Long couponId, Integer useIntegration) {
        execute("INSERT INTO oms_order (id, member_id, coupon_id, order_sn, status, delete_status, use_integration,"
                + " create_time) VALUES (" + id + ", " + MEMBER_ID + ", " + couponId + ", '" + orderSn + "', "
                + status + ", 0, " + useIntegration + ", NOW())");
    }

    private void insertOrderItem(Long id, Long orderId, Long skuId, Integer quantity) {
        execute("INSERT INTO oms_order_item (id, order_id, product_sku_id, product_quantity) VALUES ("
                + id + ", " + orderId + ", " + skuId + ", " + quantity + ")");
    }

    private void insertSkuStock(Long skuId, Integer stock, Integer lockStock) {
        execute("INSERT INTO pms_sku_stock (id, stock, lock_stock) VALUES ("
                + skuId + ", " + stock + ", " + lockStock + ")");
    }

    private void insertCouponHistory(Long id, Long memberId, Long couponId, Integer useStatus,
                                     Long orderId, String orderSn) {
        execute("INSERT INTO sms_coupon_history (id, member_id, coupon_id, use_status, use_time, order_id, order_sn)"
                + " VALUES (" + id + ", " + memberId + ", " + couponId + ", " + useStatus + ", NOW(), "
                + orderId + ", '" + orderSn + "')");
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行SQL失败:" + sql, e);
        }
    }

    private Integer queryInt(String sql) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(sql)) {
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

    /**
     * 按类型把依赖注入到服务实现中，避免依赖 Spring 容器即可获得真实对象
     */
    private static void injectByType(Object target, Object... dependencies) {
        for (Object dependency : dependencies) {
            boolean injected = false;
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().isInstance(dependency)) {
                        field.setAccessible(true);
                        try {
                            field.set(target, dependency);
                            injected = true;
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException("注入依赖失败:" + field.getName(), e);
                        }
                    }
                }
            }
            if (!injected) {
                throw new IllegalStateException("未找到类型为 " + dependency.getClass().getName() + " 的依赖字段");
            }
        }
    }

    /**
     * 只实现取消补偿需要的积分返还，其余方法不在本测试范围内
     */
    private class H2MemberService implements UmsMemberService {
        private final PortalMemberDao portalMemberDao;

        private H2MemberService() {
            SqlSessionTemplate sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
            this.portalMemberDao = sqlSessionTemplate.getMapper(PortalMemberDao.class);
        }

        @Override
        public boolean refundIntegration(Long id, Integer integration) {
            return portalMemberDao.refundIntegration(id, integration) == 1;
        }

        @Override
        public boolean deductIntegration(Long id, Integer integration) {
            return portalMemberDao.deductIntegration(id, integration) == 1;
        }

        @Override
        public UmsMember getByUsername(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UmsMember getById(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void register(String username, String password, String telephone, String authCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String generateAuthCode(String telephone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updatePassword(String telephone, String password, String authCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UmsMember getCurrentMember() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateIntegration(Long id, Integer integration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserDetails loadUserByUsername(String username) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String login(String username, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String refreshToken(String token) {
            throw new UnsupportedOperationException();
        }
    }
}
