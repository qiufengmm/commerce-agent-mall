package com.macro.mall.agent.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.tools.AgentTool;
import com.macro.mall.agent.tools.CompareProductsTool;
import com.macro.mall.agent.tools.GetProductDetailTool;
import com.macro.mall.agent.tools.ProductToolsTest;
import com.macro.mall.agent.tools.SearchProductsTool;
import com.macro.mall.agent.tools.ToolContext;
import com.macro.mall.agent.tools.ToolRegistry;
import com.macro.mall.agent.tools.ToolResult;

/**
 * 商品事实收集与卡片构造的 TDD 契约测试（计划 Task 8 Step 4）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.presentation.products}：
 * 卡片事实只从成功的 {@link ToolResult} 生成，来源优先级 {@code detail &gt; compare &gt; search}，
 * 按选定 ID 去重保序、最多 5 张，无有效选择时回退事实顺序；
 * 价格、库存、图片与详情路径全部来自服务端事实，模型只能挑选本轮出现过的 ID。
 *
 * <p>事实由 Mockito 替身门户 + 既有脱敏 fixture 驱动真实工具产生，不访问任何外部服务。
 */
class ProductCardBuilderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MallPortalClient portal;
    private ToolRegistry registry;
    private ProductFactCollector collector;

    @BeforeEach
    void setUp() {
        portal = mock(MallPortalClient.class);
        registry = new ToolRegistry(List.of(
                new SearchProductsTool(), new GetProductDetailTool(), new CompareProductsTool()));
        collector = new ProductFactCollector();
    }

    private ToolResult searchResult() {
        given(portal.searchProducts(any())).willReturn(ProductToolsTest.searchPageFixture());
        return registry.invoke(SearchProductsTool.NAME, "{}", ToolContext.guest(portal));
    }

    private ToolResult detailResult(long id) {
        given(portal.getProductDetail(id)).willReturn(ProductToolsTest.detailFixture(id, 1, 0));
        return registry.invoke(GetProductDetailTool.NAME, "{\"productId\":" + id + "}", ToolContext.guest(portal));
    }

    private ToolResult detailResultWithoutPicture(long id) {
        ProductDetailResponse base = ProductToolsTest.detailFixture(id, 1, 0);
        ProductDetailResponse withoutPicture = new ProductDetailResponse(
                base.id(), base.name(), null, base.price(), null, base.description(), base.brandId(),
                base.brandName(), base.productCategoryId(), base.productCategoryName(), base.productSn(),
                base.sale(), base.stock(), base.publishStatus(), base.deleteStatus(), base.skuStocks(),
                base.attributes(), base.publicCoupons());
        given(portal.getProductDetail(id)).willReturn(withoutPicture);
        return registry.invoke(GetProductDetailTool.NAME, "{\"productId\":" + id + "}", ToolContext.guest(portal));
    }

    private ToolResult compareResult(String arguments) {
        given(portal.getProductDetail(27L)).willReturn(ProductToolsTest.detailFixture(27L, 1, 0));
        given(portal.getProductDetail(999L)).willThrow(PortalException.notFound());
        return registry.invoke(CompareProductsTool.NAME, arguments, ToolContext.guest(portal));
    }

    private static ProductFact fact(long id, String name, String pic, String price, String subtitle,
                                    int availableStock, int sourceRank) {
        return new ProductFact(id, name, pic, price, subtitle, availableStock,
                AgentTool.stockStatusFor(availableStock), sourceRank);
    }

    // ------------------------------------------------------------------ //
    // 事实格式化
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "可售 {0} → {1}")
    @CsvSource({"0,OUT_OF_STOCK", "-5,OUT_OF_STOCK", "1,LOW_STOCK", "10,LOW_STOCK", "11,IN_STOCK", "112,IN_STOCK"})
    @DisplayName("库存状态按可售数量分段")
    void stockStatusIsDerivedFromAvailableStock(int availableStock, String expected) {
        assertThat(AgentTool.stockStatusFor(availableStock)).isEqualTo(expected);
    }

    @Test
    @DisplayName("金额统一格式化为两位小数")
    void moneyIsFormattedWithTwoDecimals() {
        assertThat(AgentTool.formatMoney(null)).isNull();
        assertThat(AgentTool.formatMoney(new BigDecimal("1899"))).isEqualTo("1899.00");
        assertThat(AgentTool.formatMoney(new BigDecimal("0"))).isEqualTo("0.00");
        assertThat(AgentTool.formatMoney(new BigDecimal("50.005"))).isEqualTo("50.01");
    }

    @Test
    @DisplayName("详情路径固定为 /pages/product/product?id={id}")
    void detailPathIsFixed() {
        assertThat(ProductCardBuilder.detailPath(27L)).isEqualTo("/pages/product/product?id=27");
        assertThatThrownBy(() -> ProductCardBuilder.detailPath(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProductCardBuilder.detailPath(-3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ //
    // 事实收集
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("只有成功结果才产生事实")
    void onlySuccessfulResultsProduceFacts() {
        collector.add(ToolResult.failure("searchProducts", ToolResult.Status.ERROR,
                Map.of("errorCode", "STOREFRONT_UNAVAILABLE"), List.of(),
                "STOREFRONT_UNAVAILABLE", "门户服务暂时不可用"));
        collector.add(ToolResult.failure("getProductDetail", ToolResult.Status.PRODUCT_NOT_FOUND,
                Map.of("productId", 999L), List.of(), null, null));

        assertThat(collector.facts()).isEmpty();
    }

    @Test
    @DisplayName("搜索结果生成事实，库存用 stock 且图片/价格来自服务端")
    void searchFactsUseServerStockAndFields() {
        collector.add(searchResult());

        assertThat(collector.facts().keySet()).containsExactly(26L, 27L, 28L);
        ProductFact fact = collector.facts().get(26L);
        assertThat(fact.name()).isEqualTo("示例手机 A");
        assertThat(fact.pic()).isEqualTo("http://localhost:9000/mall/example-26.jpg");
        assertThat(fact.price()).isEqualTo("1899.00");
        assertThat(fact.subtitle()).isEqualTo("示例副标题 A");
        assertThat(fact.availableStock()).isEqualTo(500);
        assertThat(fact.stockStatus()).isEqualTo("IN_STOCK");
        assertThat(fact.sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_SEARCH);
        assertThat(collector.facts().get(28L).availableStock()).isZero();
        assertThat(collector.facts().get(28L).stockStatus()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    @DisplayName("详情事实使用门户派生的可售库存")
    void detailFactsUsePortalAvailableStock() {
        collector.add(detailResult(27L));

        ProductFact fact = collector.facts().get(27L);
        assertThat(fact.availableStock()).isEqualTo(112);
        assertThat(fact.stockStatus()).isEqualTo("IN_STOCK");
        assertThat(fact.sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_DETAIL);
    }

    @Test
    @DisplayName("比较只采纳成功项")
    void compareFactsOnlyIncludeSuccessfulItems() {
        collector.add(compareResult("{\"productIds\":[27,999]}"));

        assertThat(collector.facts().keySet()).containsExactly(27L);
        assertThat(collector.facts().get(27L).sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_COMPARE);
    }

    @Test
    @DisplayName("未知工具名不产生事实")
    void unknownToolNamesProduceNoFacts() {
        collector.add(ToolResult.ok("someOtherTool", Map.of("products", List.of()), List.of()));

        assertThat(collector.facts()).isEmpty();
    }

    @Test
    @DisplayName("来源优先级 detail > compare > search 覆盖低优先事实")
    void detailOutranksCompareOutranksSearch() {
        collector.add(searchResult());
        collector.add(compareResult("{\"productIds\":[27,999]}"));
        assertThat(collector.facts().get(27L).sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_COMPARE);

        collector.add(detailResult(27L));

        ProductFact afterDetail = collector.facts().get(27L);
        assertThat(afterDetail.sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_DETAIL);
        assertThat(afterDetail.availableStock()).isEqualTo(112);
        assertThat(collector.facts().keySet()).containsExactly(26L, 27L, 28L);
    }

    @Test
    @DisplayName("低优先事实不会覆盖高优先事实")
    void lowerRankDoesNotOverrideHigherRank() {
        collector.add(detailResult(27L));
        collector.add(searchResult());

        assertThat(collector.facts().get(27L).sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_DETAIL);
        assertThat(collector.facts().get(27L).availableStock()).isEqualTo(112);
    }

    @Test
    @DisplayName("高优先来源缺失的图片/副标题回退到已收集事实")
    void missingPictureAndSubtitleFallBackToCollectedFacts() {
        collector.add(searchResult());
        collector.add(detailResultWithoutPicture(27L));

        ProductFact fact = collector.facts().get(27L);
        assertThat(fact.sourceRank()).isEqualTo(ProductFact.SOURCE_RANK_DETAIL);
        assertThat(fact.pic()).isEqualTo("http://localhost:9000/mall/example-27.jpg");
        assertThat(fact.subtitle()).isEqualTo("示例副标题 B");
    }

    // ------------------------------------------------------------------ //
    // 卡片构造
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("卡片按选定 ID 去重保序，且只输出本轮事实中的 ID")
    void cardsKeepSelectionOrderAndRejectUnknownIds() {
        collector.add(searchResult());
        collector.add(detailResult(27L));

        List<ProductCard> cards = new ProductCardBuilder()
                .build(List.of(999L, 27L, 26L, 27L, 26L, 26L), collector.facts());

        assertThat(cards).extracting(ProductCard::id).containsExactly(27L, 26L);
        assertThat(cards.get(0).detailPath()).isEqualTo("/pages/product/product?id=27");
        assertThat(cards.get(0).availableStock()).isEqualTo(112);
        assertThat(cards.get(0).price()).isEqualTo("2999.00");
    }

    @Test
    @DisplayName("无有效模型选择时回退事实顺序，最多 5 张")
    void cardsFallBackToFactOrderAndCapAtFive() {
        Map<Long, ProductFact> facts = new LinkedHashMap<>();
        for (long id = 1; id <= 7; id++) {
            facts.put(id, fact(id, "商品 " + id, "http://img/" + id + ".jpg", "10.00", "副标题", 5,
                    ProductFact.SOURCE_RANK_SEARCH));
        }

        List<ProductCard> cards = new ProductCardBuilder().build(List.of(999L), facts);

        assertThat(cards).extracting(ProductCard::id).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("选中 ID 超过 5 个时只保留前 5 个")
    void cardsCapSelectedIdsAtFive() {
        Map<Long, ProductFact> facts = new LinkedHashMap<>();
        for (long id = 1; id <= 7; id++) {
            facts.put(id, fact(id, "商品 " + id, null, null, null, 0, ProductFact.SOURCE_RANK_SEARCH));
        }

        List<ProductCard> cards = new ProductCardBuilder()
                .build(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), facts);

        assertThat(cards).extracting(ProductCard::id).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    @DisplayName("卡片字段全部来自服务端事实，不携带模型改写")
    void cardsUseServerFactsOnly() {
        collector.add(detailResult(27L));
        ProductFact fact = collector.facts().get(27L);

        ProductCard card = new ProductCardBuilder().build(List.of(27L), collector.facts()).get(0);

        assertThat(card.id()).isEqualTo(fact.productId());
        assertThat(card.name()).isEqualTo(fact.name());
        assertThat(card.pic()).isEqualTo(fact.pic());
        assertThat(card.price()).isEqualTo(fact.price());
        assertThat(card.subtitle()).isEqualTo(fact.subtitle());
        assertThat(card.stockStatus()).isEqualTo(fact.stockStatus());
        assertThat(card.availableStock()).isEqualTo(fact.availableStock());
        assertThat(card.detailPath()).isEqualTo("/pages/product/product?id=" + fact.productId());

        // 构造入口只有「选定 ID + 事实表」，模型没有任何提供价格/库存/图片/路径的参数
        Method build = java.util.Arrays.stream(ProductCardBuilder.class.getMethods())
                .filter(method -> "build".equals(method.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(build.getParameterTypes()).containsExactly(List.class, Map.class);
    }

    @Test
    @DisplayName("卡片 JSON 字段为 camelCase")
    void cardJsonUsesCamelCase() throws Exception {
        collector.add(detailResult(27L));

        ProductCard card = new ProductCardBuilder().build(List.of(27L), collector.facts()).get(0);
        JsonNode node = JSON.readTree(JSON.writeValueAsString(card));

        assertThat(node.fieldNames()).toIterable().containsExactly(
                "id", "name", "pic", "price", "subtitle", "stockStatus", "availableStock", "detailPath");
        assertThat(node.get("stockStatus").asText()).isEqualTo("IN_STOCK");
        assertThat(node.get("availableStock").asInt()).isEqualTo(112);
    }

    @Test
    @DisplayName("缺失事实的卡片仍使用稳定默认值")
    void cardsWithMissingFieldsUseStableDefaults() {
        Map<Long, ProductFact> facts = new LinkedHashMap<>();
        facts.put(9L, fact(9L, "", null, null, null, 0, ProductFact.SOURCE_RANK_SEARCH));

        ProductCard card = new ProductCardBuilder().build(List.of(9L), facts).get(0);

        assertThat(card.name()).isEmpty();
        assertThat(card.pic()).isNull();
        assertThat(card.price()).isNull();
        assertThat(card.stockStatus()).isEqualTo("OUT_OF_STOCK");
        assertThat(card.detailPath()).isEqualTo("/pages/product/product?id=9");
    }

    @Test
    @DisplayName("显式 maxCards 可收紧上限且至少为 1")
    void maxCardsIsConfigurable() {
        Map<Long, ProductFact> facts = new LinkedHashMap<>();
        for (long id = 1; id <= 3; id++) {
            facts.put(id, fact(id, "商品 " + id, null, null, null, 1, ProductFact.SOURCE_RANK_SEARCH));
        }

        assertThat(new ProductCardBuilder(2).build(List.of(), facts)).hasSize(2);
        assertThat(new ProductCardBuilder(0).build(List.of(), facts)).hasSize(1);
    }
}
