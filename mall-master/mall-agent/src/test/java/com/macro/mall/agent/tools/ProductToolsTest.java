package com.macro.mall.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import com.macro.mall.agent.safety.UntrustedTextFence;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.ProductAttribute;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.ProductSearchResponse;
import com.macro.mall.agent.storefront.dto.ProductSummary;
import com.macro.mall.agent.storefront.dto.SkuStock;

/**
 * 商品只读工具（搜索 / 详情 / 比较）的 TDD 契约测试（计划 Task 8 Step 2）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.product_tools}：分页大小固定 5、
 * 详情使用门户派生的可售库存、比较按 ID 顺序逐个标记成功/不存在/异常。
 * 所有事实来自 {@code src/test/resources/portal} 脱敏 fixture；门户用 Mockito 替身，
 * 不访问真实 mall-portal、Redis 或任何外部服务。
 */
public class ProductToolsTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();
    private static final String AUTHORIZATION = "Bearer placeholder-member-token";

    private MallPortalClient portal;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        portal = mock(MallPortalClient.class);
        registry = new ToolRegistry(List.of(
                new SearchProductsTool(), new GetProductDetailTool(), new CompareProductsTool()));
    }

    // ------------------------------------------------------------------ //
    // fixture 装载（脱敏门户 JSON）
    // ------------------------------------------------------------------ //

    public static JsonNode portalData(String fileName) {
        try (InputStream stream = ProductToolsTest.class.getClassLoader()
                .getResourceAsStream("portal/" + fileName)) {
            if (stream == null) {
                throw new IllegalStateException("缺少测试 fixture：portal/" + fileName);
            }
            return JSON.readTree(stream).get("data");
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取测试 fixture：portal/" + fileName, exception);
        }
    }

    /** 搜索 fixture：3 件商品，共 7 件。 */
    public static ProductSearchResponse searchPageFixture() {
        try {
            return JSON.treeToValue(portalData("product-search.json"), ProductSearchResponse.class);
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建搜索 fixture", exception);
        }
    }

    /** 详情 fixture：商品 27，可售 112，3 个 SKU，2 个属性，1 张公开券。 */
    public static ProductDetailResponse detailFixture() {
        return detailFixture(27L, 1, 0);
    }

    /** 详情 fixture，可覆盖商品 ID 与上下架状态。 */
    public static ProductDetailResponse detailFixture(long id, Integer publishStatus, Integer deleteStatus) {
        JsonNode data = portalData("product-detail.json");
        JsonNode product = data.get("product");
        List<SkuStock> skus = JSON.convertValue(data.get("skuStockList"), new TypeReference<List<SkuStock>>() { });
        List<ProductCouponResponse> coupons = JSON.convertValue(
                data.get("couponList"), new TypeReference<List<ProductCouponResponse>>() { });
        return new ProductDetailResponse(
                id,
                product.get("name").asText(),
                text(product.get("pic")),
                decimal(product.get("price")),
                text(product.get("subTitle")),
                text(product.get("description")),
                longOrNull(product.get("brandId")),
                text(product.get("brandName")),
                longOrNull(product.get("productCategoryId")),
                text(product.get("productCategoryName")),
                text(product.get("productSn")),
                intOrNull(product.get("sale")),
                intOrNull(product.get("stock")),
                publishStatus,
                deleteStatus,
                skus,
                mergedAttributes(data),
                coupons);
    }

    /** 12 个 SKU（单价 100.00）与 7 张公开券、8 个属性，用于验证输出上限。 */
    public static ProductDetailResponse oversizedDetailFixture() {
        return oversizedDetailFixture(27L);
    }

    public static ProductDetailResponse oversizedDetailFixture(long id) {
        ProductDetailResponse base = detailFixture(id, 1, 0);
        List<SkuStock> skus = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            skus.add(new SkuStock(200 + index, "SKU-" + index, new BigDecimal("100.00"), 5, 0, 0, null));
        }
        List<ProductCouponResponse> coupons = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            coupons.add(new ProductCouponResponse(300 + index, "券 " + index, 0, 0, new BigDecimal("10.00"),
                    new BigDecimal("100.00"), 1, null, null, 0, null, null));
        }
        List<ProductAttribute> attributes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            attributes.add(new ProductAttribute("属性" + index, "值" + index));
        }
        return new ProductDetailResponse(
                id, base.name(), base.pic(), base.price(), base.subTitle(), base.description(),
                base.brandId(), base.brandName(), base.productCategoryId(), base.productCategoryName(),
                base.productSn(), base.sale(), base.stock(), base.publishStatus(), base.deleteStatus(),
                skus, attributes, coupons);
    }

    public static List<ProductCouponResponse> productCouponsFixture() {
        try {
            return JSON.convertValue(
                    portalData("coupon-by-product.json"), new TypeReference<List<ProductCouponResponse>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建商品券 fixture", exception);
        }
    }

    public static List<CouponHistoryResponse> couponHistoryFixture() {
        try {
            return JSON.convertValue(
                    portalData("coupon-history.json"), new TypeReference<List<CouponHistoryResponse>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("无法构建会员券历史 fixture", exception);
        }
    }

    private static List<ProductAttribute> mergedAttributes(JsonNode data) {
        Map<Long, String> values = new LinkedHashMap<>();
        for (JsonNode item : data.get("productAttributeValueList")) {
            values.put(item.get("productAttributeId").asLong(), item.get("value").asText());
        }
        List<ProductAttribute> attributes = new ArrayList<>();
        for (JsonNode item : data.get("productAttributeList")) {
            String value = values.get(item.get("id").asLong());
            if (value != null && item.hasNonNull("name")) {
                attributes.add(new ProductAttribute(item.get("name").asText(), value));
            }
        }
        return attributes;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static BigDecimal decimal(JsonNode node) {
        return node == null || node.isNull() ? null : node.decimalValue();
    }

    private static Long longOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asLong();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    private ToolResult search(String arguments) {
        return registry.invoke(SearchProductsTool.NAME, arguments, ToolContext.guest(portal));
    }

    private ToolResult detail(String arguments) {
        return registry.invoke(GetProductDetailTool.NAME, arguments, ToolContext.guest(portal));
    }

    private ToolResult compare(String arguments) {
        return registry.invoke(CompareProductsTool.NAME, arguments, ToolContext.guest(portal));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listAt(Map<String, Object> payload, String key) {
        return (List<Map<String, Object>>) payload.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> payload, String key) {
        return (Map<String, Object>) payload.get(key);
    }

    // ------------------------------------------------------------------ //
    // searchProducts
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("搜索使用受控参数且分页大小由服务端固定")
    void searchUsesControlledParamsWithFixedPageSize() {
        given(portal.searchProducts(any())).willReturn(searchPageFixture());

        ToolResult result = search(
                "{\"keyword\":\"手机\",\"brandId\":6,\"productCategoryId\":19,\"sort\":2,\"pageNum\":2}");

        ArgumentCaptor<MallPortalClient.SearchParams> captor =
                ArgumentCaptor.forClass(MallPortalClient.SearchParams.class);
        verify(portal).searchProducts(captor.capture());
        MallPortalClient.SearchParams params = captor.getValue();
        assertThat(params.keyword()).isEqualTo("手机");
        assertThat(params.brandId()).isEqualTo(6);
        assertThat(params.productCategoryId()).isEqualTo(19);
        assertThat(params.sort()).isEqualTo(2);
        assertThat(params.pageNum()).isEqualTo(2);

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(result.payload().get("type")).isEqualTo("search");
        assertThat(result.payload().get("maxProductsPerSearch")).isEqualTo(5);
        assertThat(result.payload().get("total")).isEqualTo(7);
        assertThat(result.payload().get("returnedCount")).isEqualTo(3);
        assertThat(result.payload().get("pageNum")).isEqualTo(1);
        assertThat(result.facts()).containsExactly("搜索返回 3 件候选商品（共 7 件）");

        assertThat(mapAt(result.payload(), "conditions")).containsEntry("keyword", "手机")
                .containsEntry("brandId", 6).containsEntry("productCategoryId", 19)
                .containsEntry("sort", 2);

        List<Map<String, Object>> products = listAt(result.payload(), "products");
        assertThat(products).hasSize(3);
        assertThat(products.get(0)).containsEntry("id", 26L).containsEntry("price", "1899.00")
                .containsEntry("name", "示例手机 A").containsEntry("categoryName", "手机通讯");
        assertThat(products.get(2)).containsEntry("id", 28L).containsEntry("name", "示例手机 C");
        assertThat(products.get(2)).doesNotContainKeys("price", "pic", "subtitle", "brandName");
    }

    @Test
    @DisplayName("空参数使用默认排序与首页，条件里保留 sort=0")
    void searchWithEmptyArgumentsUsesDefaults() {
        given(portal.searchProducts(any())).willReturn(searchPageFixture());

        ToolResult result = search("{}");

        assertThat(mapAt(result.payload(), "conditions")).containsExactly(Map.entry("sort", 0));
    }

    @Test
    @DisplayName("搜索结果最多返回 5 件候选商品")
    void searchCapsReturnedProductsAtFive() {
        given(portal.searchProducts(any())).willReturn(searchPageWith(8));

        ToolResult result = search("{}");

        assertThat(listAt(result.payload(), "products")).hasSize(5);
        assertThat(result.payload().get("returnedCount")).isEqualTo(5);
    }

    @Test
    @DisplayName("搜索门户故障映射为固定结构化错误")
    void searchPortalFailureReturnsStructuredError() {
        given(portal.searchProducts(any())).willThrow(PortalException.unavailable());

        ToolResult result = search("{}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("STOREFRONT_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("门户服务暂时不可用");
        assertThat(result.payload()).containsEntry("errorCode", "STOREFRONT_UNAVAILABLE")
                .containsEntry("message", "门户服务暂时不可用");
        assertThat(result.facts()).containsExactly("searchProducts 执行失败：STOREFRONT_UNAVAILABLE");
    }

    @ParameterizedTest(name = "搜索拒绝额外字段：{0}")
    @ValueSource(strings = {
            "{\"pageSize\":5}", "{\"url\":\"http://evil\"}", "{\"method\":\"GET\"}",
            "{\"headers\":{}}", "{\"token\":\"x\"}", "{\"unknown\":1}", "{\"sql\":\"select 1\"}"})
    @DisplayName("搜索拒绝额外控制字段且不触达门户")
    void searchRejectsExtraFieldsBeforeCallingPortal(String arguments) {
        assertThatThrownBy(() -> search(arguments))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class)
                .hasMessage("工具参数不符合受控约束");
        verifyNoInteractions(portal);
    }

    @ParameterizedTest(name = "搜索拒绝非法取值：{0}")
    @ValueSource(strings = {
            "{\"pageNum\":0}", "{\"pageNum\":21}", "{\"pageNum\":\"3\"}", "{\"pageNum\":3.0}",
            "{\"pageNum\":true}", "{\"sort\":-1}", "{\"sort\":5}", "{\"sort\":\"2\"}",
            "{\"brandId\":0}", "{\"brandId\":-1}", "{\"productCategoryId\":0}"})
    @DisplayName("搜索拒绝越界与非整型参数且不触达门户")
    void searchRejectsOutOfRangeAndCoercedValues(String arguments) {
        assertThatThrownBy(() -> search(arguments))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("搜索接受 snake_case 字段名，但拒绝同时给出两种写法")
    void searchAcceptsSnakeCaseButRejectsDuplicateSpellings() {
        given(portal.searchProducts(any())).willReturn(searchPageFixture());

        assertThat(search("{\"page_num\":3,\"product_category_id\":19}").status())
                .isEqualTo(ToolResult.Status.OK);
        verify(portal, times(1)).searchProducts(any());

        assertThatThrownBy(() -> search("{\"pageNum\":3,\"page_num\":4}"))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verify(portal, times(1)).searchProducts(any());
    }

    @Test
    @DisplayName("搜索按 Unicode 码点限制关键词长度 100")
    void searchRejectsKeywordLongerThanHundred() {
        given(portal.searchProducts(any())).willReturn(searchPageFixture());

        assertThat(search("{\"keyword\":\"" + "x".repeat(100) + "\"}").status())
                .isEqualTo(ToolResult.Status.OK);
        verify(portal, times(1)).searchProducts(any());

        assertThatThrownBy(() -> search("{\"keyword\":\"" + "x".repeat(101) + "\"}"))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verify(portal, times(1)).searchProducts(any());
    }

    // ------------------------------------------------------------------ //
    // getProductDetail
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("详情使用门户派生的可售库存与字段")
    void detailUsesPortalAvailableStockAndFields() {
        given(portal.getProductDetail(27L)).willReturn(detailFixture());

        ToolResult result = detail("{\"productId\":27}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(result.payload().get("type")).isEqualTo("detail");
        assertThat(result.payload().get("availableStock")).isEqualTo(112);
        assertThat(result.payload().get("stockStatus")).isEqualTo("IN_STOCK");
        assertThat(result.payload().get("skuCount")).isEqualTo(3);
        assertThat(result.payload().get("productSn")).isEqualTo("201808270027");
        assertThat(result.facts()).containsExactly(
                "商品 27 可售库存 112，库存状态 IN_STOCK", "商品 27 共 3 个 SKU");

        assertThat(mapAt(result.payload(), "product")).containsEntry("id", 27L)
                .containsEntry("price", "2999.00").containsEntry("availableStock", 112)
                .containsEntry("stockStatus", "IN_STOCK").containsEntry("categoryName", "手机通讯");

        List<Map<String, Object>> skus = listAt(result.payload(), "skuStocks");
        assertThat(skus).hasSize(3);
        assertThat(skus.get(0)).containsEntry("id", 106L).containsEntry("availableStock", 100)
                .containsEntry("price", "2999.00");
        assertThat(skus.get(1)).containsEntry("id", 107L).containsEntry("availableStock", 0);

        List<Map<String, Object>> attributes = listAt(result.payload(), "attributes");
        assertThat(attributes).extracting(item -> item.get("name")).containsExactly("颜色", "内存");

        List<Map<String, Object>> coupons = listAt(result.payload(), "publicCoupons");
        assertThat(coupons).hasSize(1);
        assertThat(coupons.get(0)).containsEntry("id", 55L).containsEntry("amount", "50.00")
                .containsEntry("minPoint", "500.00")
                .containsEntry("endTime", "2030-01-01T00:00:00+00:00");
    }

    @Test
    @DisplayName("详情最多返回 10 个 SKU 与 5 张公开券")
    void detailCapsSkusAndPublicCoupons() {
        given(portal.getProductDetail(anyLong())).willReturn(oversizedDetailFixture());

        ToolResult result = detail("{\"productId\":27}");

        assertThat(result.payload().get("skuCount")).isEqualTo(12);
        assertThat(listAt(result.payload(), "skuStocks")).hasSize(10);
        assertThat(listAt(result.payload(), "publicCoupons")).hasSize(5);
    }

    @Test
    @DisplayName("详情未找到映射为 PRODUCT_NOT_FOUND")
    void detailNotFoundMapsToProductNotFound() {
        given(portal.getProductDetail(999L)).willThrow(PortalException.notFound());

        ToolResult result = detail("{\"productId\":999}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.PRODUCT_NOT_FOUND);
        assertThat(result.payload()).containsEntry("productId", 999L)
                .containsEntry("reason", "未找到该商品或商品已下架");
        assertThat(result.facts()).containsExactly("商品 999 不可用：未找到该商品或商品已下架");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    @DisplayName("已下架或已删除商品按未找到处理")
    void unavailableProductIsTreatedAsNotFound() {
        given(portal.getProductDetail(31L)).willReturn(detailFixture(31L, 0, 0));
        given(portal.getProductDetail(32L)).willReturn(detailFixture(32L, 1, 1));

        assertThat(detail("{\"productId\":31}").payload())
                .containsEntry("reason", "该商品已下架，无法提供详情");
        assertThat(detail("{\"productId\":32}").status()).isEqualTo(ToolResult.Status.PRODUCT_NOT_FOUND);
        assertThat(detail("{\"productId\":32}").payload())
                .containsEntry("reason", "该商品已下架，无法提供详情");
    }

    @Test
    @DisplayName("详情门户故障映射为固定结构化错误")
    void detailPortalFailureReturnsStructuredError() {
        given(portal.getProductDetail(27L)).willThrow(PortalException.timeout());

        ToolResult result = detail("{\"productId\":27}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("STOREFRONT_TIMEOUT");
        assertThat(result.errorMessage()).isEqualTo("门户服务响应超时");
        assertThat(result.facts()).containsExactly("getProductDetail 执行失败：STOREFRONT_TIMEOUT");
    }

    @ParameterizedTest(name = "详情拒绝非法参数：{0}")
    @ValueSource(strings = {
            "{\"productId\":0}", "{\"productId\":-1}", "{\"productId\":\"27\"}",
            "{\"productId\":27.0}", "{\"productId\":true}", "{}", "{\"productId\":null}",
            "{\"productId\":27,\"url\":\"http://evil\"}", "{\"productId\":27,\"product_id\":28}"})
    @DisplayName("详情拒绝非法、缺失或多余参数且不触达门户")
    void detailRejectsInvalidArguments(String arguments) {
        assertThatThrownBy(() -> detail(arguments))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("详情接受 snake_case 字段名")
    void detailAcceptsSnakeCase() {
        given(portal.getProductDetail(27L)).willReturn(detailFixture());

        assertThat(detail("{\"product_id\":27}").status()).isEqualTo(ToolResult.Status.OK);
    }

    // ------------------------------------------------------------------ //
    // compareProducts
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("比较按 ID 顺序逐个标记成功与不存在")
    void compareMarksEachIdInOrder() {
        given(portal.getProductDetail(27L)).willReturn(detailFixture());
        given(portal.getProductDetail(999L)).willThrow(PortalException.notFound());

        ToolResult result = compare("{\"productIds\":[27,999]}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(result.payload().get("type")).isEqualTo("comparison");
        assertThat(result.payload().get("missingProductIds")).isEqualTo(List.of(999L));
        assertThat(result.errorCode()).isNull();
        assertThat(result.facts()).containsExactly("比较 2 件商品，其中 1 件不可用");

        List<Map<String, Object>> items = listAt(result.payload(), "items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("productId", 27L).containsEntry("status", "OK")
                .containsEntry("price", "2999.00").containsEntry("availableStock", 112)
                .containsEntry("stockStatus", "IN_STOCK").containsEntry("skuCount", 3)
                .containsEntry("cheapestSkuPrice", "2999.00");
        assertThat(items.get(1)).containsExactly(Map.entry("productId", 999L),
                Map.entry("status", "PRODUCT_NOT_FOUND"));

        ArgumentCaptor<Long> ids = ArgumentCaptor.forClass(Long.class);
        verify(portal, times(2)).getProductDetail(ids.capture());
        assertThat(ids.getAllValues()).containsExactly(27L, 999L);
    }

    @Test
    @DisplayName("已下架商品在比较结果里标记原因")
    void compareUnavailableProductIsMarkedWithReason() {
        given(portal.getProductDetail(27L)).willReturn(detailFixture());
        given(portal.getProductDetail(31L)).willReturn(detailFixture(31L, 0, 0));

        ToolResult result = compare("{\"productIds\":[27,31]}");

        List<Map<String, Object>> items = listAt(result.payload(), "items");
        assertThat(items.get(1)).containsEntry("productId", 31L)
                .containsEntry("status", "PRODUCT_NOT_FOUND").containsEntry("reason", "该商品已下架");
    }

    @Test
    @DisplayName("全部不存在时状态为 PRODUCT_NOT_FOUND 且带固定不可用错误")
    void compareAllMissingReturnsProductNotFoundWithUnavailableError() {
        given(portal.getProductDetail(anyLong())).willThrow(PortalException.notFound());

        ToolResult result = compare("{\"productIds\":[999,888]}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.PRODUCT_NOT_FOUND);
        assertThat(result.errorCode()).isEqualTo("STOREFRONT_UNAVAILABLE");
        assertThat(result.errorMessage()).isEqualTo("商品数据暂时无法获取");
        assertThat(result.payload().get("missingProductIds")).isEqualTo(List.of(999L, 888L));
        assertThat(result.facts()).containsExactly("比较 2 件商品，其中 2 件不可用");
    }

    @Test
    @DisplayName("门户异常的商品标记为 ERROR，不伪装成成功事实")
    void comparePortalFailureMarksErrorItems() {
        given(portal.getProductDetail(27L)).willThrow(PortalException.unavailable());
        given(portal.getProductDetail(28L)).willThrow(PortalException.unavailable());

        ToolResult result = compare("{\"productIds\":[27,28]}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.errorCode()).isEqualTo("STOREFRONT_UNAVAILABLE");
        List<Map<String, Object>> items = listAt(result.payload(), "items");
        assertThat(items).extracting(item -> item.get("status")).containsExactly("ERROR", "ERROR");
        assertThat(items.get(0)).doesNotContainKeys("price", "availableStock", "name");
    }

    @Test
    @DisplayName("比较属性最多 6 个且价格取最低 SKU")
    void compareCapsAttributesAndUsesCheapestSkuPrice() {
        given(portal.getProductDetail(33L)).willReturn(oversizedDetailFixture(33L));
        given(portal.getProductDetail(34L)).willReturn(detailFixture(34L, 0, 0));

        ToolResult result = compare("{\"productIds\":[33,34]}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        List<Map<String, Object>> items = listAt(result.payload(), "items");
        assertThat(items.get(0)).containsEntry("productId", 33L).containsEntry("cheapestSkuPrice", "100.00");
        assertThat(listAt(items.get(0), "attributes")).hasSize(6);
    }

    @ParameterizedTest(name = "比较拒绝非法 ID 列表：{0}")
    @ValueSource(strings = {
            "{\"productIds\":[]}", "{\"productIds\":[1]}", "{\"productIds\":[1,2,3,4]}",
            "{\"productIds\":[1,1]}", "{\"productIds\":[1,0]}", "{\"productIds\":[\"1\",2]}",
            "{\"productIds\":[1,2.0]}", "{\"productIds\":\"x\"}", "{\"productIds\":[1,true]}",
            "{}", "{\"productIds\":[1,2],\"url\":\"http://evil\"}"})
    @DisplayName("比较拒绝非法 ID 列表且不触达门户")
    void compareRejectsInvalidIdLists(String arguments) {
        assertThatThrownBy(() -> compare(arguments))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("比较接受 snake_case 字段名")
    void compareAcceptsSnakeCase() {
        given(portal.getProductDetail(27L)).willReturn(detailFixture());
        given(portal.getProductDetail(28L)).willReturn(detailFixture(28L, 0, 0));

        assertThat(compare("{\"product_ids\":[27,28]}").status()).isEqualTo(ToolResult.Status.OK);
    }

    // ------------------------------------------------------------------ //
    // long 商品 ID 域（D1）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("详情接受超出 int 范围的正整数 ID 并原样传给门户")
    void detailAcceptsIdsBeyondIntRangeAndForwardsThem() {
        long bigId = 2_147_483_648L;
        given(portal.getProductDetail(bigId)).willReturn(detailFixture(bigId, 1, 0));

        ToolResult result = detail("{\"productId\":2147483648}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(portal).getProductDetail(captor.capture());
        assertThat(captor.getValue()).isEqualTo(bigId);
    }

    @Test
    @DisplayName("比较接受超出 int 范围的 ID 列表并按顺序传给门户")
    void compareAcceptsIdsBeyondIntRangeInOrder() {
        given(portal.getProductDetail(2_147_483_648L)).willReturn(detailFixture(2_147_483_648L, 1, 0));
        given(portal.getProductDetail(2_147_483_649L)).willReturn(detailFixture(2_147_483_649L, 0, 0));

        ToolResult result = compare("{\"productIds\":[2147483648,2147483649]}");

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(portal, times(2)).getProductDetail(captor.capture());
        assertThat(captor.getAllValues()).containsExactly(2_147_483_648L, 2_147_483_649L);
        assertThat(listAt(result.payload(), "items")).extracting(item -> item.get("productId"))
                .containsExactly(2_147_483_648L, 2_147_483_649L);
    }

    @ParameterizedTest(name = "超出 signed long 的商品 ID 被拒绝：{0}")
    @ValueSource(strings = {"9223372036854775808", "-9223372036854775809"})
    @DisplayName("超出 signed long 范围的 ID 被拒绝且不触达门户")
    void idsBeyondSignedLongRangeAreRejected(String value) {
        assertThatThrownBy(() -> detail("{\"productId\":" + value + "}"))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class)
                .hasMessage("工具参数不符合受控约束");
        assertThatThrownBy(() -> compare("{\"productIds\":[" + value + ",1]}"))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verifyNoInteractions(portal);
    }

    // ------------------------------------------------------------------ //
    // 交给模型的围栏内容
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("模型内容是围栏 JSON，包含工具名、状态与数据")
    void modelContentIsFencedJson() {
        given(portal.getProductDetail(999L)).willThrow(PortalException.notFound());

        String content = detail("{\"productId\":999}").toModelContent();

        assertThat(content).isEqualTo("""
                <<<MALL_UNTRUSTED_DATA>>>
                [tool_result:getProductDetail]
                {"tool":"getProductDetail","status":"PRODUCT_NOT_FOUND","data":{"productId":999,"reason":"未找到该商品或商品已下架"}}
                <<<END_MALL_UNTRUSTED_DATA>>>""");
    }

    @Test
    @DisplayName("失败结果的模型内容附带稳定错误码与文案，且不泄漏上游信息")
    void modelContentCarriesErrorCodeAndMessage() {
        given(portal.getProductDetail(27L)).willThrow(PortalException.unavailable());

        String content = detail("{\"productId\":27}").toModelContent();

        assertThat(content).contains("\"errorCode\":\"STOREFRONT_UNAVAILABLE\"")
                .contains("\"errorMessage\":\"门户服务暂时不可用\"")
                .doesNotContain("Bearer")
                .doesNotContain("http://");
    }

    @Test
    @DisplayName("模型内容受字符上限约束且不泄漏凭据")
    void modelContentIsBoundedAndNeverLeaksCredentials() {
        given(portal.getProductDetail(27L)).willReturn(oversizedDetailFixture());

        ToolResult result = registry.invoke(
                GetProductDetailTool.NAME, "{\"productId\":27}", ToolContext.member(portal, AUTHORIZATION));

        String bounded = result.toModelContent(200);
        int bound = 200 + UntrustedTextFence.DATA_OPEN_TAG.length()
                + UntrustedTextFence.DATA_CLOSE_TAG.length() + 40;
        assertThat(bounded.codePointCount(0, bounded.length())).isLessThanOrEqualTo(bound);
        assertThat(bounded).contains(UntrustedTextFence.TRUNCATION_SUFFIX);
        assertThat(result.toModelContent()).doesNotContain(AUTHORIZATION);
        assertThat(ToolResult.MAX_RESULT_CHARS).isEqualTo(4000);
    }

    /** 搜索 fixture，但把返回列表替换为指定数量的商品（用于验证上限）。 */
    private static ProductSearchResponse searchPageWith(int itemCount) {
        ProductSearchResponse base = searchPageFixture();
        List<ProductSummary> items = new ArrayList<>();
        for (int index = 0; index < itemCount; index++) {
            items.add(new ProductSummary(
                    900 + index, "示例手机 " + (900 + index), null, new BigDecimal("1000.00"),
                    null, null, null, null, null, null, null, null, null, null));
        }
        return new ProductSearchResponse(base.pageNum(), base.pageSize(), base.totalPage(), base.total(), items);
    }
}
