package com.macro.mall.search.dao;

import com.macro.mall.search.domain.EsProduct;
import com.macro.mall.search.domain.EsProductAttributeValue;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 H2 内存库（MySQL 兼容模式）验证 mall-search 商品搜索 DAO 的真实 SQL 行为。
 * <p>
 * 测试直接加载生产映射文件 dao/EsProductDao.xml，不启动 Spring 容器，不连接真实 MySQL，
 * 也不需要 Elasticsearch、Redis 或 Docker。
 */
class EsProductDaoH2Test {

    private static final String NS = "com.macro.mall.search.dao.EsProductDao.";

    /**
     * 已上架且未删除的商品
     */
    private static final Long ONLINE_PRODUCT_ID = 1L;
    private static final Long ANOTHER_ONLINE_PRODUCT_ID = 2L;
    /**
     * 已逻辑删除的商品
     */
    private static final Long DELETED_PRODUCT_ID = 3L;
    /**
     * 已下架的商品
     */
    private static final Long UNPUBLISHED_PRODUCT_ID = 4L;

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initSqlSessionFactory() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:es_product_dao;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
                "sa", "");
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream inputStream = Resources.getResourceAsStream("dao/EsProductDao.xml")) {
            new XMLMapperBuilder(inputStream, configuration, "dao/EsProductDao.xml", configuration.getSqlFragments()).parse();
        }
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void initSchema() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            execute(session, "DROP TABLE IF EXISTS pms_product");
            execute(session, "CREATE TABLE pms_product ("
                    + "id BIGINT PRIMARY KEY, product_sn VARCHAR(64), brand_id BIGINT, brand_name VARCHAR(64),"
                    + "product_category_id BIGINT, product_category_name VARCHAR(64), pic VARCHAR(255),"
                    + "name VARCHAR(255), sub_title VARCHAR(255), price DECIMAL(10,2), sale INT,"
                    + "new_status INT, recommand_status INT, stock INT, promotion_type INT,"
                    + "keywords VARCHAR(255), sort INT, delete_status INT, publish_status INT)");
            execute(session, "DROP TABLE IF EXISTS pms_product_attribute_value");
            execute(session, "CREATE TABLE pms_product_attribute_value ("
                    + "id BIGINT PRIMARY KEY, product_id BIGINT, product_attribute_id BIGINT,"
                    + "value VARCHAR(255))");
            execute(session, "DROP TABLE IF EXISTS pms_product_attribute");
            execute(session, "CREATE TABLE pms_product_attribute ("
                    + "id BIGINT PRIMARY KEY, type INT, name VARCHAR(64))");

            // 已上架且未删除的商品 1、2
            execute(session, "INSERT INTO pms_product (id, product_sn, brand_id, brand_name, product_category_id,"
                    + "product_category_name, pic, name, sub_title, price, sale, new_status, recommand_status,"
                    + "stock, promotion_type, keywords, sort, delete_status, publish_status) VALUES"
                    + "(1, 'SN001', 1, '小米', 10, '手机', 'pic1.png', '小米8', '全面屏手机', 2699.00, 100,"
                    + "1, 1, 500, 2, '手机 小米', 1, 0, 1)");
            execute(session, "INSERT INTO pms_product (id, product_sn, brand_id, brand_name, product_category_id,"
                    + "product_category_name, pic, name, sub_title, price, sale, new_status, recommand_status,"
                    + "stock, promotion_type, keywords, sort, delete_status, publish_status) VALUES"
                    + "(2, 'SN002', 2, '华为', 10, '手机', 'pic2.png', '华为P30', '拍照手机', 3999.00, 50,"
                    + "1, 0, 300, 0, '手机 华为', 2, 0, 1)");
            // 已逻辑删除的商品 3
            execute(session, "INSERT INTO pms_product (id, name, delete_status, publish_status, price) VALUES"
                    + "(3, '已删除商品', 1, 1, 100.00)");
            // 已下架的商品 4
            execute(session, "INSERT INTO pms_product (id, name, delete_status, publish_status, price) VALUES"
                    + "(4, '已下架商品', 0, 0, 200.00)");

            execute(session, "INSERT INTO pms_product_attribute (id, type, name) VALUES (10, 0, '颜色')");
            execute(session, "INSERT INTO pms_product_attribute (id, type, name) VALUES (11, 1, '材质')");
            // 商品 1 有两个属性值，用于验证一对多合并
            execute(session, "INSERT INTO pms_product_attribute_value (id, product_id, product_attribute_id, value) VALUES (100, 1, 10, '红色')");
            execute(session, "INSERT INTO pms_product_attribute_value (id, product_id, product_attribute_id, value) VALUES (101, 1, 11, '玻璃')");
            execute(session, "INSERT INTO pms_product_attribute_value (id, product_id, product_attribute_id, value) VALUES (102, 2, 10, '黑色')");
        }
    }

    @Test
    @DisplayName("不传 id 时只返回已上架且未删除的商品")
    void getAllEsProductListWithoutIdOnlyReturnsPublishedProducts() {
        List<EsProduct> products = selectList("getAllEsProductList", null);

        assertEquals(2, products.size());
        assertEquals(Arrays.asList(ONLINE_PRODUCT_ID, ANOTHER_ONLINE_PRODUCT_ID), idsOf(products));
        EsProduct first = products.get(0);
        assertEquals("SN001", first.getProductSn());
        assertEquals("小米", first.getBrandName());
        assertEquals("手机", first.getProductCategoryName());
        assertEquals("小米8", first.getName());
        assertEquals("全面屏手机", first.getSubTitle());
        assertEquals(0, new BigDecimal("2699.00").compareTo(first.getPrice()));
        assertEquals(Integer.valueOf(500), first.getStock());
        assertEquals(Integer.valueOf(2), first.getPromotionType());
    }

    @Test
    @DisplayName("传入 id 时只返回该商品，已删除或已下架的商品被过滤")
    void getAllEsProductListWithIdFiltersDeletedAndUnpublishedProducts() {
        assertEquals(1, selectList("getAllEsProductList", params("id", ONLINE_PRODUCT_ID)).size());

        assertTrue(selectList("getAllEsProductList", params("id", DELETED_PRODUCT_ID)).isEmpty(),
                "逻辑删除的商品不应出现在搜索商品列表中");
        assertTrue(selectList("getAllEsProductList", params("id", UNPUBLISHED_PRODUCT_ID)).isEmpty(),
                "已下架的商品不应出现在搜索商品列表中");
    }

    @Test
    @DisplayName("多个属性值的商品只返回一条商品记录，属性值聚合到 attrValueList")
    void getAllEsProductListMergesOneToManyAttributeValues() {
        List<EsProduct> products = selectList("getAllEsProductList", params("id", ONLINE_PRODUCT_ID));

        assertEquals(1, products.size());
        List<EsProductAttributeValue> attrValueList = products.get(0).getAttrValueList();
        assertNotNull(attrValueList);
        assertEquals(2, attrValueList.size());
        assertEquals(Arrays.asList(100L, 101L), attrIdsOf(attrValueList));
        EsProductAttributeValue color = attrValueList.get(0);
        assertEquals(Long.valueOf(10), color.getProductAttributeId());
        assertEquals("红色", color.getValue());
        assertEquals(Integer.valueOf(0), color.getType());
        assertEquals("颜色", color.getName());
    }

    @Test
    @DisplayName("按 id 集合查询只返回集合内且可上架的商品")
    void getEsProductListByIdsOnlyReturnsMatchedOnlineProducts() {
        Map<String, Object> params = new HashMap<>();
        params.put("ids", Arrays.asList(ONLINE_PRODUCT_ID, ANOTHER_ONLINE_PRODUCT_ID, DELETED_PRODUCT_ID));
        List<EsProduct> products = selectList("getEsProductListByIds", params);

        assertEquals(2, products.size());
        assertEquals(Arrays.asList(ONLINE_PRODUCT_ID, ANOTHER_ONLINE_PRODUCT_ID), idsOf(products));
    }

    @Test
    @DisplayName("id 集合没有匹配数据时返回空列表")
    void getEsProductListByIdsReturnsEmptyListWhenNoMatch() {
        Map<String, Object> params = new HashMap<>();
        params.put("ids", Arrays.asList(9999L));
        assertTrue(selectList("getEsProductListByIds", params).isEmpty());

        Map<String, Object> deletedOnly = new HashMap<>();
        deletedOnly.put("ids", Arrays.asList(DELETED_PRODUCT_ID, UNPUBLISHED_PRODUCT_ID));
        assertTrue(selectList("getEsProductListByIds", deletedOnly).isEmpty());
    }

    private List<EsProduct> selectList(String statementName, Map<String, Object> params) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            return session.selectList(NS + statementName, params);
        }
    }

    private List<Long> idsOf(List<EsProduct> products) {
        return products.stream().map(EsProduct::getId).collect(java.util.stream.Collectors.toList());
    }

    private List<Long> attrIdsOf(List<EsProductAttributeValue> attrValues) {
        return attrValues.stream().map(EsProductAttributeValue::getId).collect(java.util.stream.Collectors.toList());
    }

    private Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private void execute(SqlSession session, String sql) {
        try {
            session.getConnection().createStatement().execute(sql);
        } catch (Exception e) {
            throw new IllegalStateException("执行SQL失败:" + sql, e);
        }
    }
}
