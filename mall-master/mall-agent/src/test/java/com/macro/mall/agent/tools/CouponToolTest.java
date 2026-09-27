package com.macro.mall.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalException;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;

/**
 * 会员优惠券只读工具的 TDD 契约测试（计划 Task 8 Step 3）。
 *
 * <p>行为对照 Python {@code mall_shopping_agent.tools.coupon_tools}：
 * 游客或无 Authorization 立即返回 {@code LOGIN_REQUIRED} 且零会员接口调用；
 * 会员只把请求携带的 Authorization 透传给两条会员只读接口；
 * 结果只解释“本人未使用历史券”与“该商品适用券”的 {@code couponId} 交集，
 * 按可用优先、金额降序排列，最多 5 张，绝不调用任何领券或写接口。
 *
 * <p>门户用 Mockito 替身，时间用固定 {@link Clock}，不访问真实 mall-portal、Redis 或网络。
 */
class CouponToolTest {

    private static final String AUTHORIZATION = "Bearer placeholder-member-token";
    private static final long PRODUCT_ID = 27L;

    /** 固定时钟：落在 fixture 券的有效期内（2024-01-01 ~ 2030-01-01）。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2025-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Clock BEFORE_START = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Clock AFTER_END = Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC);

    private MallPortalClient portal;
    private GetMemberCouponsForProductTool tool;
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        portal = mock(MallPortalClient.class);
        tool = new GetMemberCouponsForProductTool(CLOCK);
        registry = new ToolRegistry(List.of(tool));
    }

    private ToolResult invoke(ToolContext context) {
        return registry.invoke(GetMemberCouponsForProductTool.NAME,
                "{\"productId\":" + PRODUCT_ID + "}", context);
    }

    private ToolResult invokeAsMember() {
        return invoke(ToolContext.member(portal, AUTHORIZATION));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> couponsOf(ToolResult result) {
        return (List<Map<String, Object>>) result.payload().get("coupons");
    }

    // ------------------------------------------------------------------ //
    // 注册表：严格四个工具
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("默认注册表精确注册四个工具，且只有会员券工具要求登录")
    void defaultRegistryRegistersExactlyFourToolsInOrder() {
        ToolRegistry defaultRegistry = ToolRegistry.defaultRegistry(CLOCK);

        assertThat(defaultRegistry.names()).containsExactly(
                "searchProducts", "getProductDetail", "compareProducts", "getMemberCouponsForProduct");
        assertThat(defaultRegistry.names()).hasSize(4);

        assertThat(defaultRegistry.find("searchProducts").orElseThrow().requiresMember()).isFalse();
        assertThat(defaultRegistry.find("getProductDetail").orElseThrow().requiresMember()).isFalse();
        assertThat(defaultRegistry.find("compareProducts").orElseThrow().requiresMember()).isFalse();
        assertThat(defaultRegistry.find("getMemberCouponsForProduct").orElseThrow().requiresMember()).isTrue();

        assertThat(defaultRegistry.openAiTools()).hasSize(4);
        for (Map<String, Object> openAiTool : defaultRegistry.openAiTools()) {
            assertThat(openAiTool.get("type")).isEqualTo("function");
            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) openAiTool.get("function");
            assertThat(defaultRegistry.names()).contains((String) function.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
            assertThat(parameters.get("type")).isEqualTo("object");
            assertThat(parameters.get("additionalProperties")).isEqualTo(Boolean.FALSE);
        }
    }

    // ------------------------------------------------------------------ //
    // 游客门槛：零会员接口调用
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("游客调用立即要求登录，且不触碰任何会员接口")
    void guestCallRequiresLoginWithoutTouchingMemberEndpoints() {
        ToolResult result = invoke(ToolContext.guest(portal));

        assertThat(result.status()).isEqualTo(ToolResult.Status.LOGIN_REQUIRED);
        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).containsEntry("type", "coupons")
                .containsEntry("productId", PRODUCT_ID)
                .containsEntry("requiresLogin", true)
                .containsEntry("reason", "登录后可以查询您本人已领取、且适用于该商品的优惠券。");
        assertThat(result.facts()).containsExactly("查询个人优惠券需要先登录");
        assertThat(result.errorCode()).isNull();
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("空 Authorization 视为游客，同样零调用")
    void blankAuthorizationIsTreatedAsGuest() {
        ToolResult result = registry.invoke(GetMemberCouponsForProductTool.NAME,
                "{\"productId\":" + PRODUCT_ID + "}", new ToolContext(portal, ""));

        assertThat(result.status()).isEqualTo(ToolResult.Status.LOGIN_REQUIRED);
        verifyNoInteractions(portal);
    }

    // ------------------------------------------------------------------ //
    // 会员调用：Token 透传与交集
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("会员调用把同一 Authorization 透传给两条会员只读接口")
    void memberCallPassesAuthorizationToBothMemberEndpoints() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION))
                .willReturn(ProductToolsTest.productCouponsFixture());

        ToolResult result = invokeAsMember();

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        ArgumentCaptor<String> historyAuth = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> couponAuth = ArgumentCaptor.forClass(String.class);
        verify(portal).listUnusedCouponHistory(historyAuth.capture());
        verify(portal).listProductCoupons(anyLong(), couponAuth.capture());
        assertThat(historyAuth.getValue()).isEqualTo(AUTHORIZATION);
        assertThat(couponAuth.getValue()).isEqualTo(AUTHORIZATION);
    }

    @Test
    @DisplayName("只解释本人未使用历史券与商品适用券的 couponId 交集")
    void couponsIntersectReceivedUnusedWithProductApplicable() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION))
                .willReturn(ProductToolsTest.productCouponsFixture());

        ToolResult result = invokeAsMember();

        // 历史券 couponId = {55, 77, 999}；商品适用券 id = {55, 77, 99} → 交集 {55, 77}
        assertThat(result.payload()).containsEntry("receivedCouponCount", 3)
                .containsEntry("applicableCouponCount", 3)
                .containsEntry("matchedCouponCount", 2)
                .containsEntry("usableCouponCount", 2);
        assertThat(couponsOf(result)).extracting(item -> item.get("couponId")).containsExactly(77L, 55L);
        assertThat(result.facts()).containsExactly(
                "商品 27 共有 2 张已领取且适用的优惠券", "其中 2 张当前可用");
    }

    @Test
    @DisplayName("可用券优先、金额降序，并给出完整解释字段")
    void couponExplanationsPreferUsableThenDescendingAmount() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION))
                .willReturn(ProductToolsTest.productCouponsFixture());

        ToolResult result = invokeAsMember();
        List<Map<String, Object>> coupons = couponsOf(result);

        assertThat(coupons.get(0)).containsEntry("couponId", 77L).containsEntry("name", "满2000减200")
                .containsEntry("amount", "200.00").containsEntry("minPoint", "2000.00")
                .containsEntry("useType", 2).containsEntry("scope", "指定商品")
                .containsEntry("status", "AVAILABLE").containsEntry("usableForProduct", true)
                .containsEntry("startTime", "2024-01-01T00:00:00+00:00")
                .containsEntry("endTime", "2030-01-01T00:00:00+00:00")
                .containsEntry("explanation",
                        "满2000减200：指定商品，满 2000.00 元可用，面额 200.00 元，当前在有效期内，可用于该商品。");
        assertThat(coupons.get(1)).containsEntry("couponId", 55L).containsEntry("scope", "全场通用")
                .containsEntry("explanation",
                        "满500减50：全场通用，满 500.00 元可用，面额 50.00 元，当前在有效期内，可用于该商品。");
    }

    @Test
    @DisplayName("已过期与未生效券排在可用券之后并标注状态")
    void expiredAndNotStartedCouponsAreRankedAfterUsable() {
        List<ProductCouponResponse> applicable = List.of(
                coupon(1L, "过期券", new BigDecimal("500.00"), new BigDecimal("0.00"), 0,
                        Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-06-01T00:00:00Z")),
                coupon(2L, "未生效券", new BigDecimal("600.00"), new BigDecimal("0.00"), 1,
                        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z")),
                coupon(3L, "可用券", new BigDecimal("100.00"), new BigDecimal("0.00"), 2,
                        Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));
        given(portal.listUnusedCouponHistory(AUTHORIZATION)).willReturn(historyFor(1L, 2L, 3L));
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION)).willReturn(applicable);

        ToolResult result = invokeAsMember();
        List<Map<String, Object>> coupons = couponsOf(result);

        // 可用券优先；不可用的券按面额降序（600.00 在 500.00 之前），可用券本身也在首位
        assertThat(coupons).extracting(item -> item.get("couponId")).containsExactly(3L, 2L, 1L);
        assertThat(coupons.get(0)).containsEntry("status", "AVAILABLE").containsEntry("usableForProduct", true);
        assertThat(coupons.get(1)).containsEntry("status", "NOT_STARTED").containsEntry("usableForProduct", false);
        assertThat(coupons.get(2)).containsEntry("status", "EXPIRED").containsEntry("usableForProduct", false)
                .containsEntry("explanation", "过期券：全场通用，无使用门槛，面额 500.00 元，已过有效期，不能使用。");
    }

    @Test
    @DisplayName("结果最多 5 张券")
    void couponsAreCappedAtFive() {
        List<ProductCouponResponse> applicable = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            long id = 400L + index;
            ids.add(id);
            applicable.add(coupon(id, "券" + index, new BigDecimal("10.00"), new BigDecimal("0.00"), 0,
                    Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z")));
        }
        given(portal.listUnusedCouponHistory(AUTHORIZATION)).willReturn(historyFor(ids.toArray(Long[]::new)));
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION)).willReturn(applicable);

        ToolResult result = invokeAsMember();

        assertThat(couponsOf(result)).hasSize(5);
        assertThat(result.payload()).containsEntry("matchedCouponCount", 7);
    }

    @Test
    @DisplayName("适用范围按 useType 解释，未知范围有固定兜底")
    void couponScopeLabelsAreExplained() {
        assertThat(explainOf(useType(0))).contains("全场通用");
        assertThat(explainOf(useType(1))).contains("指定分类");
        assertThat(explainOf(useType(2))).contains("指定商品");
        assertThat(explainOf(useType(9))).contains("未知适用范围");
    }

    @Test
    @DisplayName("无门槛券与缺失金额有稳定兜底文案")
    void couponThresholdAndAmountFallbacks() {
        ProductCouponResponse zeroMinPoint = coupon(10L, "零门槛券", null, new BigDecimal("0.00"), 0,
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        List<Map<String, Object>> explained = GetMemberCouponsForProductTool.buildCouponExplanations(
                List.of(zeroMinPoint), CLOCK);

        assertThat(explained.get(0)).containsEntry("amount", null).containsEntry("minPoint", "0.00")
                .containsEntry("explanation", "零门槛券：全场通用，无使用门槛，面额 0.00 元，当前在有效期内，可用于该商品。");
    }

    // ------------------------------------------------------------------ //
    // 失效 Token 与门户错误
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("失效 Token 返回登录要求，且不再调用商品券接口")
    void invalidTokenRequiresLogin() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willThrow(PortalException.memberUnauthorized());

        ToolResult result = invokeAsMember();

        assertThat(result.status()).isEqualTo(ToolResult.Status.LOGIN_REQUIRED);
        assertThat(result.payload()).containsEntry("requiresLogin", true);
        verify(portal, never()).listProductCoupons(anyLong(), any());
    }

    @Test
    @DisplayName("商品券接口返回 404 时标记商品不可用")
    void productCouponLookupNotFoundMarksProductNotFound() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION)).willThrow(PortalException.notFound());

        ToolResult result = invokeAsMember();

        assertThat(result.status()).isEqualTo(ToolResult.Status.PRODUCT_NOT_FOUND);
        assertThat(result.payload()).containsEntry("productId", PRODUCT_ID)
                .containsEntry("reason", "未找到该商品");
        assertThat(result.facts()).containsExactly("商品 27 不可用");
    }

    @Test
    @DisplayName("会员券门户故障映射为固定结构化错误")
    void couponPortalFailuresMapToStructuredError() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION)).willThrow(PortalException.unavailable());
        ToolResult historyFailure = invokeAsMember();

        assertThat(historyFailure.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(historyFailure.errorCode()).isEqualTo("STOREFRONT_UNAVAILABLE");
        assertThat(historyFailure.errorMessage()).isEqualTo("门户服务暂时不可用");
        assertThat(historyFailure.facts()).containsExactly("会员优惠券查询失败：STOREFRONT_UNAVAILABLE");
        verify(portal, never()).listProductCoupons(anyLong(), any());

        MallPortalClient secondPortal = mock(MallPortalClient.class);
        given(secondPortal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(secondPortal.listProductCoupons(PRODUCT_ID, AUTHORIZATION))
                .willThrow(PortalException.timeout());

        ToolResult couponFailure = invoke(ToolContext.member(secondPortal, AUTHORIZATION));

        assertThat(couponFailure.status()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(couponFailure.errorCode()).isEqualTo("STOREFRONT_TIMEOUT");
    }

    // ------------------------------------------------------------------ //
    // 只读边界与参数校验
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("门户客户端没有任何领券或写入口")
    void portalClientExposesNoClaimOrWriteEndpoint() {
        List<String> methodNames = Arrays.stream(MallPortalClient.class.getMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames)
                .doesNotContain("claimCoupon", "claim", "addToCart", "placeOrder", "pay", "cancelOrder",
                        "confirmReceipt", "updateStock", "importAll", "write", "post", "put", "delete");
        assertThat(methodNames).contains(
                "searchProducts", "getProductDetail", "resolveMember", "listUnusedCouponHistory",
                "listProductCoupons");
    }

    @Test
    @DisplayName("券结果与模型内容绝不携带 Authorization")
    void couponResultNeverCarriesAuthorization() {
        given(portal.listUnusedCouponHistory(AUTHORIZATION))
                .willReturn(ProductToolsTest.couponHistoryFixture());
        given(portal.listProductCoupons(PRODUCT_ID, AUTHORIZATION))
                .willReturn(ProductToolsTest.productCouponsFixture());

        ToolResult result = invokeAsMember();

        assertThat(result.toModelContent()).doesNotContain(AUTHORIZATION).doesNotContain("Bearer");
        assertThat(result.toString()).doesNotContain(AUTHORIZATION);
    }

    @ParameterizedTest(name = "券工具拒绝非法参数：{0}")
    @ValueSource(strings = {
            "{\"productId\":0}", "{\"productId\":-1}", "{\"productId\":\"27\"}", "{\"productId\":27.0}",
            "{}",             "{\"productId\":27,\"url\":\"http://evil\"}", "{\"productId\":27,\"token\":\"x\"}",
            "{\"productId\":27,\"product_id\":28}", "{\"productId\":9223372036854775808}"})
    @DisplayName("券工具拒绝非法、缺失或多余参数且不触达门户")
    void couponToolRejectsInvalidArguments(String arguments) {
        assertThatThrownBy(() -> registry.invoke(
                GetMemberCouponsForProductTool.NAME, arguments, ToolContext.member(portal, AUTHORIZATION)))
                .isInstanceOf(ToolRegistry.InvalidToolArgumentsException.class);
        verifyNoInteractions(portal);
    }

    @Test
    @DisplayName("券工具接受超出 int 范围的正整数商品 ID（D1）")
    void couponToolAcceptsIdsBeyondIntRange() {
        long bigId = 2_147_483_648L;
        given(portal.listUnusedCouponHistory(AUTHORIZATION)).willReturn(List.of());
        given(portal.listProductCoupons(bigId, AUTHORIZATION)).willReturn(List.of());

        ToolResult result = registry.invoke(GetMemberCouponsForProductTool.NAME,
                "{\"productId\":2147483648}", ToolContext.member(portal, AUTHORIZATION));

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        assertThat(result.payload()).containsEntry("productId", bigId);
        verify(portal).listProductCoupons(bigId, AUTHORIZATION);
    }

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private static ProductCouponResponse coupon(long id, String name, BigDecimal amount, BigDecimal minPoint,
                                                int useType, Instant startTime, Instant endTime) {
        return new ProductCouponResponse(id, name, 0, 0, amount, minPoint, 1, startTime, endTime, useType,
                null, null);
    }

    private static List<CouponHistoryResponse> historyFor(Long... couponIds) {
        List<CouponHistoryResponse> history = new ArrayList<>();
        long id = 1000L;
        for (Long couponId : couponIds) {
            history.add(new CouponHistoryResponse(id++, couponId, 0, 1, null));
        }
        return history;
    }

    private static ProductCouponResponse useType(int useType) {
        return coupon(500L, "范围券", new BigDecimal("10.00"), new BigDecimal("0.00"), useType,
                Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static String explainOf(ProductCouponResponse couponResponse) {
        return (String) GetMemberCouponsForProductTool
                .buildCouponExplanations(List.of(couponResponse), CLOCK)
                .get(0).get("explanation");
    }
}
