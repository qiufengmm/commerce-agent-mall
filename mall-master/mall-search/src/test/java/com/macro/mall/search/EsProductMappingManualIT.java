package com.macro.mall.search;

import com.macro.mall.search.domain.EsProduct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.document.Document;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elasticsearch 在线映射校验（外部集成测试，默认 mvn test 不会执行）。
 * <p>
 * 该测试会真实访问 MALL_ES_URIS 指向的 Elasticsearch（默认 localhost:9200）并写入商品索引映射，
 * 因此命名为 *IT，Surefire 默认包含的 *Test / *Tests / Test* / *TestCase 不会匹配它。
 * 数据源使用 H2 内存库，避免依赖本机开发 MySQL。
 * <p>
 * 断言要求：putMapping 必须被 ES 确认成功，并且读回的映射中包含商品关键字段，避免只打印结果就通过。
 * <p>
 * 显式运行方式（需先启动 Elasticsearch）：
 *
 * <pre>
 * mvn -pl mall-search -am -DskipTests=false "-Dtest=EsProductMappingManualIT" test
 * </pre>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mall_search_mapping_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class EsProductMappingManualIT {

    @Autowired
    private ElasticsearchTemplate elasticsearchTemplate;

    @Test
    @DisplayName("在线环境：商品索引映射写入成功且关键字段可读回")
    void testEsProductMapping() {
        IndexOperations indexOperations = elasticsearchTemplate.indexOps(EsProduct.class);
        if (!indexOperations.exists()) {
            assertTrue(indexOperations.create(), "商品索引 pms 不存在且自动创建未被确认");
        }

        Document mappingDocument = indexOperations.createMapping(EsProduct.class);
        assertNotNull(mappingDocument, "未能根据 EsProduct 生成索引映射");
        assertTrue(indexOperations.putMapping(mappingDocument), "Elasticsearch 未确认商品索引映射写入");

        Map<?, ?> mapping = indexOperations.getMapping();
        assertNotNull(mapping, "读取商品索引映射失败");
        Map<String, Object> properties = resolveProperties(mapping);
        assertFalse(properties.isEmpty(), "商品索引映射中没有任何字段定义:" + mapping);

        // 至少校验商品流通链路上的关键字段：货号、名称、价格、库存、属性值
        assertTrue(properties.containsKey("productSn"), "缺少商品货号字段 productSn:" + properties.keySet());
        assertTrue(properties.containsKey("name"), "缺少商品名称字段 name:" + properties.keySet());
        assertTrue(properties.containsKey("price"), "缺少商品价格字段 price:" + properties.keySet());
        assertTrue(properties.containsKey("stock"), "缺少商品库存字段 stock:" + properties.keySet());
        assertTrue(properties.containsKey("attrValueList"), "缺少商品属性值字段 attrValueList:" + properties.keySet());
        assertEquals("keyword", fieldType(properties.get("productSn")),
                "商品货号 productSn 应定义为 keyword 精确查询字段");
    }

    /**
     * ES 返回的映射在不同版本下可能是 {properties={...}} 或 {_doc={properties={...}}}，这里递归取最外层 properties。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveProperties(Map<?, ?> mapping) {
        Object directProperties = mapping.get("properties");
        if (directProperties instanceof Map<?, ?> properties) {
            return (Map<String, Object>) properties;
        }
        for (Map.Entry<?, ?> entry : mapping.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> nested) {
                Map<String, Object> resolved = resolveProperties(nested);
                if (!resolved.isEmpty()) {
                    return resolved;
                }
            }
        }
        return Map.of();
    }

    private String fieldType(Object fieldMapping) {
        if (fieldMapping instanceof Map<?, ?> fieldDefinition) {
            Object type = fieldDefinition.get("type");
            return type == null ? null : String.valueOf(type);
        }
        return null;
    }
}
