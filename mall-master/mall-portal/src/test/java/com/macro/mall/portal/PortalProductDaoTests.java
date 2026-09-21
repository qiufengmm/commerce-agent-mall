package com.macro.mall.portal;

import com.macro.mall.mapper.PmsProductAttributeMapper;
import com.macro.mall.mapper.PmsProductFullReductionMapper;
import com.macro.mall.mapper.PmsProductLadderMapper;
import com.macro.mall.mapper.PmsProductMapper;
import com.macro.mall.mapper.PmsSkuStockMapper;
import com.macro.mall.mapper.SmsCouponMapper;
import com.macro.mall.model.PmsProductAttribute;
import com.macro.mall.model.PmsSkuStock;
import com.macro.mall.portal.domain.CartProduct;
import com.macro.mall.portal.domain.PromotionProduct;
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
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前台商品查询逻辑单元测试。
 * <p>
 * 测试使用 H2 内存库（MySQL 兼容模式）直接加载生产映射文件 dao/PortalProductDao.xml，
 * 不启动 Spring 容器，不连接真实 MySQL，也不需要 Redis、MongoDB、RabbitMQ 或 Docker。
 */
public class PortalProductDaoTests {

    private static final String NS = "com.macro.mall.portal.dao.PortalProductDao.";

    /**
     * 有 SKU、阶梯价、满减的促销商品
     */
    private static final Long PRODUCT_WITH_PROMOTION_ID = 26L;
    /**
     * 只有 SKU 的促销商品
     */
    private static final Long PRODUCT_WITH_SKU_ONLY_ID = 27L;
    /**
     * 无任何关联数据的促销商品
     */
    private static final Long PRODUCT_WITHOUT_RELATION_ID = 28L;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initSqlSessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:portal_product_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE,COUNT",
                "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        // mall-mbg 生成的映射提供 BaseResultMap，供被测映射继承
        configuration.addMapper(PmsProductMapper.class);
        configuration.addMapper(PmsSkuStockMapper.class);
        configuration.addMapper(PmsProductLadderMapper.class);
        configuration.addMapper(PmsProductFullReductionMapper.class);
        configuration.addMapper(PmsProductAttributeMapper.class);
        // 同一映射文件内的可用优惠券查询引用了 SmsCouponMapper.BaseResultMap
        configuration.addMapper(SmsCouponMapper.class);
        try (InputStream inputStream = Resources.getResourceAsStream("dao/PortalProductDao.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "dao/PortalProductDao.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void initSchema() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "DROP TABLE IF EXISTS pms_product");
            execute(session, "CREATE TABLE pms_product ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(255), sub_title VARCHAR(255), price DECIMAL(10,2),"
                    + "pic VARCHAR(255), stock INT, promotion_type INT, gift_growth INT, gift_point INT,"
                    + "product_attribute_category_id BIGINT)");
            execute(session, "DROP TABLE IF EXISTS pms_sku_stock");
            execute(session, "CREATE TABLE pms_sku_stock ("
                    + "id BIGINT PRIMARY KEY, product_id BIGINT, sku_code VARCHAR(64), price DECIMAL(10,2),"
                    + "stock INT, lock_stock INT, promotion_price DECIMAL(10,2), pic VARCHAR(255))");
            execute(session, "DROP TABLE IF EXISTS pms_product_ladder");
            execute(session, "CREATE TABLE pms_product_ladder ("
                    + "id BIGINT PRIMARY KEY, product_id BIGINT, count INT, discount DECIMAL(10,2), price DECIMAL(10,2))");
            execute(session, "DROP TABLE IF EXISTS pms_product_full_reduction");
            execute(session, "CREATE TABLE pms_product_full_reduction ("
                    + "id BIGINT PRIMARY KEY, product_id BIGINT, full_price DECIMAL(10,2), reduce_price DECIMAL(10,2))");
            execute(session, "DROP TABLE IF EXISTS pms_product_attribute");
            execute(session, "CREATE TABLE pms_product_attribute ("
                    + "id BIGINT PRIMARY KEY, product_attribute_category_id BIGINT, name VARCHAR(64),"
                    + "type INT, sort INT)");
        }
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            // 商品 26/27/28/29 均为促销商品，26 关联 2 个 SKU、2 个阶梯价、2 个满减
            execute(session, "INSERT INTO pms_product (id, name, promotion_type, gift_growth, gift_point,"
                    + "price, pic, stock, sub_title, product_attribute_category_id) VALUES"
                    + "(26, '促销商品26', 2, 10, 100, 1000.00, '26.png', 100, '副标题26', 7)");
            execute(session, "INSERT INTO pms_product (id, name, promotion_type, gift_growth, gift_point,"
                    + "price, pic, stock, sub_title, product_attribute_category_id) VALUES"
                    + "(27, '促销商品27', 1, 5, 50, 500.00, '27.png', 50, '副标题27', 7)");
            execute(session, "INSERT INTO pms_product (id, name, promotion_type, gift_growth, gift_point,"
                    + "price, pic, stock, sub_title, product_attribute_category_id) VALUES"
                    + "(28, '促销商品28', 0, 0, 0, 200.00, '28.png', 20, '副标题28', 7)");
            execute(session, "INSERT INTO pms_product (id, name, promotion_type, gift_growth, gift_point,"
                    + "price, pic, stock, sub_title, product_attribute_category_id) VALUES"
                    + "(29, '促销商品29', 0, 0, 0, 300.00, '29.png', 30, '副标题29', 7)");

            execute(session, "INSERT INTO pms_sku_stock (id, product_id, sku_code, price, stock, lock_stock, promotion_price, pic) VALUES"
                    + "(101, 26, 'SKU26-1', 1000.00, 60, 5, 900.00, '26-1.png')");
            execute(session, "INSERT INTO pms_sku_stock (id, product_id, sku_code, price, stock, lock_stock, promotion_price, pic) VALUES"
                    + "(102, 26, 'SKU26-2', 1100.00, 40, 0, 950.00, '26-2.png')");
            execute(session, "INSERT INTO pms_sku_stock (id, product_id, sku_code, price, stock, lock_stock, promotion_price, pic) VALUES"
                    + "(103, 27, 'SKU27-1', 500.00, 50, 0, 450.00, '27-1.png')");
            execute(session, "INSERT INTO pms_sku_stock (id, product_id, sku_code, price, stock, lock_stock, promotion_price, pic) VALUES"
                    + "(104, 28, 'SKU28-1', 200.00, 20, 0, 180.00, '28-1.png')");

            execute(session, "INSERT INTO pms_product_ladder (id, product_id, count, discount, price) VALUES (201, 26, 2, 0.90, 900.00)");
            execute(session, "INSERT INTO pms_product_ladder (id, product_id, count, discount, price) VALUES (202, 26, 5, 0.80, 800.00)");
            execute(session, "INSERT INTO pms_product_ladder (id, product_id, count, discount, price) VALUES (203, 27, 3, 0.95, 475.00)");

            execute(session, "INSERT INTO pms_product_full_reduction (id, product_id, full_price, reduce_price) VALUES (301, 26, 1000.00, 100.00)");
            execute(session, "INSERT INTO pms_product_full_reduction (id, product_id, full_price, reduce_price) VALUES (302, 26, 2000.00, 300.00)");
            execute(session, "INSERT INTO pms_product_full_reduction (id, product_id, full_price, reduce_price) VALUES (303, 27, 500.00, 50.00)");

            // 商品属性：type=0 为规格（购物车查询只取规格），type=1 为参数
            execute(session, "INSERT INTO pms_product_attribute (id, product_attribute_category_id, name, type, sort) VALUES (401, 7, '颜色', 0, 1)");
            execute(session, "INSERT INTO pms_product_attribute (id, product_attribute_category_id, name, type, sort) VALUES (402, 7, '容量', 0, 2)");
            execute(session, "INSERT INTO pms_product_attribute (id, product_attribute_category_id, name, type, sort) VALUES (403, 7, '产地', 1, 3)");
        }
    }

    @Test
    @DisplayName("传入商品 id 集合时返回对应促销商品及其 SKU、阶梯价、满减")
    public void testGetPromotionProductList() {
        List<Long> ids = Arrays.asList(26L, 27L, 28L, 29L);
        List<PromotionProduct> promotionProductList = getPromotionProductList(ids);

        assertEquals(4, promotionProductList.size());
        // 生产 SQL 未定义返回顺序，统一按 id 排序后比较
        assertEquals(Arrays.asList(26L, 27L, 28L, 29L), idsOf(promotionProductList));

        // 字段断言按 id 精确定位商品，不依赖结果集顺序
        PromotionProduct product = productById(promotionProductList, PRODUCT_WITH_PROMOTION_ID);
        assertEquals("促销商品26", product.getName());
        assertEquals(Integer.valueOf(2), product.getPromotionType());
        assertEquals(Integer.valueOf(10), product.getGiftGrowth());
        assertEquals(Integer.valueOf(100), product.getGiftPoint());
        assertEquals(2, product.getSkuStockList().size());
        assertEquals(0, new BigDecimal("900.00").compareTo(
                skuById(product.getSkuStockList(), 101L).getPromotionPrice()));
    }

    @Test
    @DisplayName("id 集合没有匹配数据时返回空列表")
    public void testGetPromotionProductListWhenNoMatch() {
        assertTrue(getPromotionProductList(Arrays.asList(999L, 1000L)).isEmpty());
    }

    @Test
    @DisplayName("一对多关联不会重复生成商品记录：SKU、阶梯价、满减全部合并到同一商品")
    public void testGetPromotionProductListMergesOneToManyRelations() {
        List<PromotionProduct> promotionProductList = getPromotionProductList(Arrays.asList(PRODUCT_WITH_PROMOTION_ID));

        // 4 个 SKU/阶梯/满减组合会产生 2*2*2=8 行结果，应合并为 1 条商品记录
        assertEquals(1, promotionProductList.size());
        // 关联集合由 MyBatis 按行顺序聚合，生产 SQL 未排序，比较前统一按 id 排序
        PromotionProduct product = promotionProductList.get(0);
        assertEquals(PRODUCT_WITH_PROMOTION_ID, product.getId());
        assertEquals(2, product.getSkuStockList().size());
        assertEquals(Arrays.asList(101L, 102L), skuIdsOf(product.getSkuStockList()));
        assertEquals(2, product.getProductLadderList().size());
        assertEquals(Arrays.asList(201L, 202L), ladderIdsOf(product.getProductLadderList()));
        assertEquals(2, product.getProductFullReductionList().size());
        assertEquals(Arrays.asList(301L, 302L), fullReductionIdsOf(product.getProductFullReductionList()));
    }

    @Test
    @DisplayName("关联表无数据时商品主记录仍然返回，集合为空")
    public void testGetPromotionProductListKeepsProductWithoutRelation() {
        List<PromotionProduct> promotionProductList = getPromotionProductList(Arrays.asList(PRODUCT_WITHOUT_RELATION_ID));
        // 商品 28 只有 SKU，没有阶梯价与满减
        assertEquals(1, promotionProductList.size());
        assertEquals(1, promotionProductList.get(0).getSkuStockList().size());
        assertTrue(promotionProductList.get(0).getProductLadderList().isEmpty());
        assertTrue(promotionProductList.get(0).getProductFullReductionList().isEmpty());

        // 商品 29 完全没有任何关联数据
        List<PromotionProduct> withoutAnyRelation = getPromotionProductList(Arrays.asList(29L));
        assertEquals(1, withoutAnyRelation.size());
        assertTrue(withoutAnyRelation.get(0).getSkuStockList().isEmpty());
    }

    @Test
    @DisplayName("购物车商品查询只返回规格属性，并按 sort 倒序聚合 SKU 与属性")
    public void testGetCartProduct() {
        CartProduct cartProduct;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            cartProduct = session.selectOne(NS + "getCartProduct", PRODUCT_WITH_PROMOTION_ID);
        }

        assertNotNull(cartProduct);
        assertEquals("促销商品26", cartProduct.getName());
        assertEquals("副标题26", cartProduct.getSubTitle());
        assertEquals(2, cartProduct.getSkuStockList().size());
        // 生产 SQL 未对 SKU 排序，比较前统一按 id 排序
        assertEquals(Arrays.asList(101L, 102L), skuIdsOf(cartProduct.getSkuStockList()));
        // type=1 的参数属性不参与购物车展示，先做集合校验避免依赖顺序
        assertEquals(new HashSet<>(Arrays.asList("容量", "颜色")), namesOf(cartProduct.getProductAttributeList()));
        // 生产 SQL getCartProduct 明确要求 ORDER BY pa.sort DESC，因此顺序本身属于业务约定
        assertEquals(Arrays.asList("容量", "颜色"), cartProduct.getProductAttributeList().stream()
                .map(PmsProductAttribute::getName).collect(java.util.stream.Collectors.toList()));
    }

    private List<PromotionProduct> getPromotionProductList(List<Long> ids) {
        Map<String, Object> params = new HashMap<>();
        params.put("ids", ids);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectList(NS + "getPromotionProductList", params);
        }
    }

    /**
     * 生产 SQL 未定义返回顺序，查询结果与 MyBatis 集合聚合顺序都取决于数据库返回的行顺序，
     * 因此测试统一先按 id 排序再比较，或按 id 精确定位对象，不依赖任何隐式顺序。
     */
    private List<Long> idsOf(List<PromotionProduct> products) {
        return sorted(products.stream().map(PromotionProduct::getId).collect(java.util.stream.Collectors.toList()));
    }

    private List<Long> skuIdsOf(List<PmsSkuStock> skuStocks) {
        return sorted(skuStocks.stream().map(PmsSkuStock::getId).collect(java.util.stream.Collectors.toList()));
    }

    private List<Long> ladderIdsOf(List<com.macro.mall.model.PmsProductLadder> ladders) {
        return sorted(ladders.stream().map(com.macro.mall.model.PmsProductLadder::getId).collect(java.util.stream.Collectors.toList()));
    }

    private List<Long> fullReductionIdsOf(List<com.macro.mall.model.PmsProductFullReduction> fullReductions) {
        return sorted(fullReductions.stream().map(com.macro.mall.model.PmsProductFullReduction::getId).collect(java.util.stream.Collectors.toList()));
    }

    private Set<String> namesOf(List<PmsProductAttribute> attributes) {
        return attributes.stream().map(PmsProductAttribute::getName)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private PromotionProduct productById(List<PromotionProduct> products, Long id) {
        return products.stream()
                .filter(product -> id.equals(product.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("查询结果中不存在商品:" + id));
    }

    private PmsSkuStock skuById(List<PmsSkuStock> skuStocks, Long id) {
        return skuStocks.stream()
                .filter(skuStock -> id.equals(skuStock.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("查询结果中不存在SKU:" + id));
    }

    private List<Long> sorted(List<Long> ids) {
        List<Long> copy = new ArrayList<>(ids);
        Collections.sort(copy);
        return copy;
    }

    private void execute(SqlSession session, String sql) {
        try {
            session.getConnection().createStatement().execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行SQL失败:" + sql, e);
        }
    }
}
