package com.macro.mall.agent.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.macro.mall.agent.http.GeneratedBodyStream;
import com.macro.mall.agent.storefront.dto.CouponHistoryResponse;
import com.macro.mall.agent.storefront.dto.MemberInfoResponse;
import com.macro.mall.agent.storefront.dto.ProductCouponResponse;
import com.macro.mall.agent.storefront.dto.ProductDetailResponse;
import com.macro.mall.agent.storefront.dto.ProductSearchResponse;
import com.macro.mall.agent.storefront.dto.ProductSummary;
import com.macro.mall.agent.tools.GetProductDetailTool;
import com.macro.mall.agent.tools.ToolContext;
import com.macro.mall.agent.tools.ToolRegistry;
import com.macro.mall.agent.tools.ToolResult;

/**
 * {@link MallPortalClient} 固定只读接缝的 TDD 契约测试（Task 5）。
 *
 * <p>本测试只依赖五个定型只读操作，全程使用 {@link MockRestServiceServer} 离线模拟，
 * 不访问真实 mall-portal、不使用真实 Token。行为对照 Python
 * {@code mall_shopping_agent.storefront.mall_portal} / {@code schemas} 与
 * {@code tests/unit/storefront/test_mall_portal.py}，响应由
 * {@code src/test/resources/portal/} 下五个脱敏 fixture 驱动。
 *
 * <p>测试中的凭据与上游正文均为合成占位常量，只用于断言它们不会出现在异常或日志里；
 * Token 等值一律用布尔断言比较，避免失败信息回显取值。
 */
class MallPortalClientTest {

    /** 构造配置的 portal 地址；host 必须等于这里的 host。 */
    private static final String PORTAL_SCHEME = "http";
    private static final String PORTAL_HOST = "portal.internal";
    private static final int PORTAL_PORT = 8085;
    private static final String PORTAL_BASE_URL = PORTAL_SCHEME + "://" + PORTAL_HOST + ":" + PORTAL_PORT;

    /** 合成占位会员 Token；不是真实凭据，只用于透传与脱敏断言。 */
    private static final String MEMBER_TOKEN = "Bearer synthetic-placeholder-member-token";
    /** 合成上游响应正文标记；用于断言脱敏，不是真实内容。 */
    private static final String UPSTREAM_SECRET = "portal-upstream-secret-body-must-not-leak";
    /** 搜索关键词；用于断言异常/日志不回显完整 query。 */
    private static final String SEARCH_KEYWORD = "手机";

    /** 响应正文硬字节上限，与生产有界读取保持一致（1 MiB）。 */
    private static final long RESPONSE_BODY_CAP_BYTES = 1024L * 1024L;

    /** 与 Spring 运行时的 ObjectMapper 一致：注册 JavaTimeModule 并输出 ISO-8601 文本。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;

    /** 按请求发生顺序记录的所有门户请求，便于断言 host/path/query/header。 */
    private final List<Recorded> recorded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        recorded.clear();
    }

    // ------------------------------------------------------------------ //
    // 五个固定 GET 方法：method / host / 精确白名单 path
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("搜索只调用 /product/search：GET、1-based pageNum、固定 pageSize=5、sort/keyword/brand/category")
    void searchSendsFixedGetWithOneBasedPagingAndControlledFilters() {
        expectJsonFixture("product-search.json");
        MallPortalClient client = client();

        MallPortalClient.SearchParams params = new MallPortalClient.SearchParams(SEARCH_KEYWORD, 6, 19, 3, 2);
        ProductSearchResponse page = client.searchProducts(params);

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(queryParams(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pageNum", "2",
                "pageSize", "5",
                "sort", "3",
                "keyword", SEARCH_KEYWORD,
                "brandId", "6",
                "productCategoryId", "19"));
        assertNoAuthorization(request);

        // 1-based 页码原样发出，响应页码来自门户返回
        assertThat(page.pageNum()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(5);
        assertThat(page.total()).isEqualTo(7);
        assertThat(page.totalPage()).isEqualTo(2);
        assertThat(page.list()).hasSize(3);
        assertThat(page.list().get(0).id()).isEqualTo(26);
        assertThat(page.list().get(0).name()).isEqualTo("示例手机 A");
        assertThat(page.list().get(0).price()).isEqualByComparingTo("1899.00");
        assertThat(page.list().get(0).price().toPlainString()).isEqualTo("1899.00");
        assertThat(page.list().get(0).brandName()).isEqualTo("示例品牌");
        // 缺失可选字段的条目必须容忍
        assertThat(page.list().get(2).id()).isEqualTo(28);
        assertThat(page.list().get(2).price()).isNull();
        assertThat(page.list().get(2).pic()).isNull();
        assertThat(page.list().get(2).brandName()).isNull();
        server.verify();
    }

    @Test
    @DisplayName("搜索在 optional filters 为 null 时省略 keyword/brandId/productCategoryId")
    void searchOmitsNullOptionalFilters() {
        expectJsonFixture("product-search.json");

        client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(queryParams(request)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pageNum", "1",
                "pageSize", "5",
                "sort", "0"));
        assertThat(queryParams(request)).doesNotContainKeys("keyword", "brandId", "productCategoryId");
    }

    @Test
    @DisplayName("搜索响应 data=null 返回空页并保留请求页码与固定页大小")
    void searchReturnsEmptyPagePreservingRequestedPagingWhenDataNull() {
        server.expect(capture()).andRespond(withSuccess(
                "{\"code\":200,\"message\":\"操作成功\",\"data\":null}", MediaType.APPLICATION_JSON));

        ProductSearchResponse page =
                client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 3));

        assertThat(page.pageNum()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(5);
        assertThat(page.list()).isEmpty();
        assertThat(page.total()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("详情只调用 /product/detail/{id}，解析 SKU 库存、属性与公开券")
    void detailSendsExactPathAndParsesSkuAttributesAndCoupons() {
        expectJsonFixture("product-detail.json");

        ProductDetailResponse detail = client().getProductDetail(27);

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/product/detail/27");
        assertNoAuthorization(request);

        assertThat(detail.id()).isEqualTo(27);
        assertThat(detail.name()).isEqualTo("示例手机 B");
        assertThat(detail.price()).isEqualByComparingTo("2999.00");
        // 每个 SKU 的可售库存 = max(stock-lockStock, 0)
        assertThat(detail.skuStocks()).hasSize(3);
        assertThat(detail.skuStocks().get(0).availableStock()).isEqualTo(100);
        assertThat(detail.skuStocks().get(1).availableStock()).isZero();
        assertThat(detail.skuStocks().get(2).availableStock()).isEqualTo(12);
        assertThat(detail.availableStock()).isEqualTo(112);
        // 只保留有取值的属性
        assertThat(detail.attributes()).hasSize(2);
        assertThat(detail.attributes().get(0).name()).isEqualTo("颜色");
        assertThat(detail.attributes().get(0).value()).isEqualTo("金色");
        assertThat(detail.attributes().get(1).name()).isEqualTo("内存");
        assertThat(detail.attributes().get(1).value()).isEqualTo("8GB");
        // 公开券金额保留两位精度
        assertThat(detail.publicCoupons()).hasSize(1);
        assertThat(detail.publicCoupons().get(0).id()).isEqualTo(55);
        assertThat(detail.publicCoupons().get(0).amount().toPlainString()).isEqualTo("50.00");
        assertThat(detail.publicCoupons().get(0).endTime()).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("SKU 的 spData 从门户 JSON 经 DTO 一直传到 getProductDetail 工具输出，缺失字段被省略")
    void skuSpDataFlowsFromPortalJsonThroughDtoToDetailTool() {
        expectJsonFixture("product-detail.json");

        // 真实链路：门户 JSON -> MallPortalResponseParser -> SkuStock DTO -> GetProductDetailTool 输出
        ToolResult result = new ToolRegistry(List.of(new GetProductDetailTool()))
                .invoke(GetProductDetailTool.NAME, "{\"productId\":27}", ToolContext.guest(client()));

        assertThat(result.status()).isEqualTo(ToolResult.Status.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skus = (List<Map<String, Object>>) result.payload().get("skuStocks");
        assertThat(skus).hasSize(3);
        // SKU 106/107 门户 spData 是 JSON 文本，必须原样透传到工具输出
        assertThat(skus.get(0)).containsEntry("spData", "[{\"key\":\"颜色\",\"value\":\"金色\"}]");
        assertThat(skus.get(1)).containsEntry("spData", "[{\"key\":\"颜色\",\"value\":\"银色\"}]");
        // SKU 108 门户缺失 spData：按 putIfNotNull 语义省略该键，而不是输出 null
        assertThat(skus.get(2)).doesNotContainKey("spData");
        server.verify();
    }

    @Test
    @DisplayName("SkuStock DTO 建模 spData：有值原样保留，缺失字段为 null")
    void skuSpDataIsModeledOnDtoWithNullForMissing() {
        expectJsonFixture("product-detail.json");

        ProductDetailResponse detail = client().getProductDetail(27);

        assertThat(detail.skuStocks()).hasSize(3);
        assertThat(detail.skuStocks().get(0).spData()).isEqualTo("[{\"key\":\"颜色\",\"value\":\"金色\"}]");
        assertThat(detail.skuStocks().get(1).spData()).isEqualTo("[{\"key\":\"颜色\",\"value\":\"银色\"}]");
        assertThat(detail.skuStocks().get(2).spData()).isNull();
        server.verify();
    }

    @ParameterizedTest(name = "详情拒绝非正 ID {0}，且不发请求")
    @ValueSource(longs = {0L, -1L})
    void detailRejectsNonPositiveIdBeforeAnyRequest(long productId) {
        MallPortalClient client = client();

        IllegalArgumentException failure = catchThrowableOfType(
                () -> client.getProductDetail(productId), IllegalArgumentException.class);

        assertThat(failure).isNotNull();
        assertThat(recorded).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("/sso/info 原样透传 Authorization，解析本人身份且不携带密码/Token")
    void resolveMemberSendsSsoInfoWithAuthorization() throws Exception {
        expectJsonFixture("sso-info.json");

        MemberInfoResponse member = client().resolveMember(MEMBER_TOKEN);

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/sso/info");
        assertAuthorizationPassedThrough(request);

        assertThat(member.memberId()).isEqualTo(7);
        assertThat(member.nickname()).isEqualTo("示例会员");
        String serialized = JSON.writeValueAsString(member);
        assertThat(serialized).doesNotContain("password");
        assertThat(serialized).doesNotContain("token");
        assertThat(serialized.contains(MEMBER_TOKEN)).isFalse();
    }

    @Test
    @DisplayName("未使用券历史只调用 listHistory?useStatus=0 并透传 Authorization")
    void listUnusedCouponHistorySendsUseStatusZeroWithAuthorization() throws Exception {
        expectJsonFixture("coupon-history.json");

        List<CouponHistoryResponse> history = client().listUnusedCouponHistory(MEMBER_TOKEN);

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/member/coupon/listHistory");
        assertThat(queryParams(request)).containsExactlyInAnyOrderEntriesOf(Map.of("useStatus", "0"));
        assertAuthorizationPassedThrough(request);

        assertThat(history).hasSize(3);
        assertThat(history.stream().map(CouponHistoryResponse::couponId).collect(Collectors.toList()))
                .containsExactly(55L, 77L, 999L);
        assertThat(history).allSatisfy(item -> assertThat(item.useStatus()).isZero());

        String serialized = JSON.writeValueAsString(history);
        assertThat(serialized).doesNotContain("couponCode", "memberId", "memberNickname", "password", "token");
    }

    @Test
    @DisplayName("商品券只调用 listByProduct/{id} 并透传 Authorization，金额保留两位精度")
    void listProductCouponsSendsPositiveProductIdWithAuthorization() {
        expectJsonFixture("coupon-by-product.json");

        List<ProductCouponResponse> coupons = client().listProductCoupons(27, MEMBER_TOKEN);

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/member/coupon/listByProduct/27");
        assertAuthorizationPassedThrough(request);

        assertThat(coupons.stream().map(ProductCouponResponse::id).collect(Collectors.toList()))
                .containsExactly(55L, 77L, 99L);
        assertThat(coupons.get(1).amount().toPlainString()).isEqualTo("200.00");
        assertThat(coupons.get(1).useType()).isEqualTo(2);
    }

    @ParameterizedTest(name = "商品券拒绝非正 productId {0}，且不发请求")
    @ValueSource(longs = {0L, -1L})
    void listProductCouponsRejectsNonPositiveIdBeforeAnyRequest(long productId) {
        MallPortalClient client = client();

        IllegalArgumentException failure = catchThrowableOfType(
                () -> client.listProductCoupons(productId, MEMBER_TOKEN), IllegalArgumentException.class);

        assertThat(failure).isNotNull();
        assertThat(recorded).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("五条请求全部为 GET、host 固定、path 精确白名单，Authorization 仅出现在三条会员路径")
    void allRequestsStayOnFixedGetWhitelist() {
        expectJsonFixture("product-search.json");
        expectJsonFixture("product-detail.json");
        expectJsonFixture("sso-info.json");
        expectJsonFixture("coupon-history.json");
        expectJsonFixture("coupon-by-product.json");

        MallPortalClient client = client();
        client.searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));
        client.getProductDetail(27);
        client.resolveMember(MEMBER_TOKEN);
        client.listUnusedCouponHistory(MEMBER_TOKEN);
        client.listProductCoupons(27, MEMBER_TOKEN);

        assertThat(recorded).hasSize(5);
        recorded.forEach(request -> {
            assertThat(request.method()).isEqualTo(HttpMethod.GET);
            assertGetOnConfiguredHost(request);
            assertThat(isAllowedPath(request.uri().getPath()))
                    .as("路径必须属于五条固定白名单")
                    .isTrue();
        });
        assertNoAuthorization(recorded.get(0));
        assertNoAuthorization(recorded.get(1));
        assertAuthorizationPassedThrough(recorded.get(2));
        assertAuthorizationPassedThrough(recorded.get(3));
        assertAuthorizationPassedThrough(recorded.get(4));
        server.verify();
    }

    @Test
    @DisplayName("Authorization 只对 /sso/info 与两条会员券路径原样传递，search/detail 不含该头")
    void authorizationBoundaryIsExact() {
        expectJsonFixture("product-search.json");
        expectJsonFixture("product-detail.json");

        MallPortalClient client = client();
        client.searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));
        client.getProductDetail(27);

        assertThat(recorded).hasSize(2);
        recorded.forEach(request -> assertThat(request.headers().containsKey(HttpHeaders.AUTHORIZATION))
                .as("商品搜索与详情不得携带 Authorization")
                .isFalse());
    }

    // ------------------------------------------------------------------ //
    // 不接受任意 URL / method / header 的通用入口
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("API 表面只提供五个定型只读操作，且不暴露 URI/HTTP method/header 参数")
    void portalClientExposesOnlyTheFiveFixedReadOperations() {
        Set<String> publicNames = Arrays.stream(MallPortalClient.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(publicNames).containsExactlyInAnyOrder(
                "searchProducts",
                "getProductDetail",
                "resolveMember",
                "listUnusedCouponHistory",
                "listProductCoupons");

        for (Method method : MallPortalClient.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat((Object) parameter)
                        .as("公共操作不得接受任意 URI / HTTP method / header 参数")
                        .isNotIn(URI.class, HttpMethod.class, HttpHeaders.class);
            }
        }
    }

    @Test
    @DisplayName("SearchParams 是受控 record，且不含 url/method/header/path/pageSize 等外部控制字段")
    void searchParamsRecordHasNoExternalControlFields() {
        Class<MallPortalClient.SearchParams> type = MallPortalClient.SearchParams.class;

        assertThat(type.isRecord()).as("SearchParams 必须是受控的 record").isTrue();
        Set<String> components = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
        assertThat(components).containsExactlyInAnyOrder(
                "keyword", "brandId", "productCategoryId", "sort", "pageNum");
        assertThat(components).doesNotContain(
                "pageSize", "url", "uri", "path", "method", "header", "headers", "body");
    }

    @Test
    @DisplayName("SearchParams 强制 pageNum 1..20、sort 0..4、brand/category 正数、keyword 长度≤100")
    void searchParamsEnforcesServerSideLimits() {
        // 合法边界
        assertThat(new MallPortalClient.SearchParams(null, 1, 1, 4, 20)).isNotNull();
        assertThat(new MallPortalClient.SearchParams("字".repeat(100), null, null, 0, 1)).isNotNull();

        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, null, 0, 0));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, null, 0, 21));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, null, -1, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, null, 5, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, 0, null, 0, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, -1, null, 0, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, 0, 0, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams(null, null, -1, 0, 1));
        assertInvalidSearchParams(() -> new MallPortalClient.SearchParams("字".repeat(101), null, null, 0, 1));
    }

    // ------------------------------------------------------------------ //
    // 错误安全映射
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("HTTP 401 → MEMBER_UNAUTHORIZED，且不泄漏 Token/上游正文")
    void httpUnauthorizedMapsToMemberUnauthorized() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .body("{\"code\":401,\"message\":\"" + UPSTREAM_SECRET + "\"}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().resolveMember(MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.MEMBER_UNAUTHORIZED, 401);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("HTTP 403 → MEMBER_UNAUTHORIZED")
    void httpForbiddenMapsToMemberUnauthorized() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"code\":403,\"message\":\"" + UPSTREAM_SECRET + "\"}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().listUnusedCouponHistory(MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.MEMBER_UNAUTHORIZED, 401);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("门户 envelope code=401 → MEMBER_UNAUTHORIZED")
    void envelopeCodeUnauthorizedMapsToMemberUnauthorized() {
        expectJson("{\"code\":401,\"message\":\"" + UPSTREAM_SECRET + "\",\"data\":null}");

        PortalException failure = catchThrowableOfType(
                () -> client().resolveMember(MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.MEMBER_UNAUTHORIZED, 401);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("身份响应缺少 id / data=null 按 Python 行为映射 MEMBER_UNAUTHORIZED")
    void memberIdentityWithoutIdMapsToMemberUnauthorized() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":null}");

        PortalException failure = catchThrowableOfType(
                () -> client().resolveMember(MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.MEMBER_UNAUTHORIZED, 401);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("HTTP 404 → NOT_FOUND")
    void httpNotFoundMapsToNotFound() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .body("{\"code\":404,\"message\":\"" + UPSTREAM_SECRET + "\"}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("门户 envelope code=404 → NOT_FOUND")
    void envelopeCodeNotFoundMapsToNotFound() {
        expectJson("{\"code\":404,\"message\":\"" + UPSTREAM_SECRET + "\",\"data\":null}");

        PortalException failure = catchThrowableOfType(
                () -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("详情路径 HTTP 500（Spring 默认错误结构）按 Python 行为映射 NOT_FOUND")
    void detailServerErrorMapsToNotFound() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"timestamp\":\"2026-09-22T00:00:00Z\",\"status\":500,\"error\":\"x\"}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("商品券路径 HTTP 500 按 Python 行为映射 NOT_FOUND")
    void productCouponServerErrorMapsToNotFound() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("{\"timestamp\":\"2026-09-22T00:00:00Z\",\"status\":500,\"error\":\"x\"}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().listProductCoupons(27, MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("envelope code=500 业务错误 → UNAVAILABLE，且不回显上游正文")
    void businessErrorCodeMapsToUnavailable() {
        expectJson("{\"code\":500,\"message\":\"" + UPSTREAM_SECRET + "\"}");

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(SEARCH_KEYWORD, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.UNAVAILABLE, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("搜索路径 HTTP 5xx（非详情路径）→ UNAVAILABLE")
    void searchServerErrorMapsToUnavailable() {
        server.expect(capture()).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("{\"timestamp\":\"2026-09-22T00:00:00Z\",\"status\":503}")
                .contentType(MediaType.APPLICATION_JSON));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(SEARCH_KEYWORD, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.UNAVAILABLE, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("读取超时 → TIMEOUT/502，且不泄漏原始异常文本")
    void readTimeoutMapsToTimeout() {
        server.expect(capture()).andRespond(withException(new SocketTimeoutException("read timeout " + UPSTREAM_SECRET)));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(SEARCH_KEYWORD, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.TIMEOUT, 502);
        assertSafeFailure(failure);
        assertThat(describe(failure).toLowerCase(Locale.ROOT)).doesNotContain("socket");
    }

    @Test
    @DisplayName("连接超时 → TIMEOUT/502")
    void connectTimeoutMapsToTimeout() {
        server.expect(capture())
                .andRespond(withException(new HttpConnectTimeoutException("connect timeout")));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.TIMEOUT, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("普通连接失败 → UNAVAILABLE/502")
    void connectionFailureMapsToUnavailable() {
        server.expect(capture()).andRespond(withException(new ConnectException("connection refused")));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.UNAVAILABLE, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("非法 JSON → PROTOCOL/502，且不保留原始 body")
    void invalidJsonMapsToProtocolError() {
        server.expect(capture()).andRespond(withSuccess(UPSTREAM_SECRET, MediaType.TEXT_PLAIN));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(SEARCH_KEYWORD, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("缺少 code 的非法成功 envelope → PROTOCOL/502")
    void missingCodeEnvelopeMapsToProtocolError() {
        expectJson("{\"message\":\"操作成功\",\"data\":{}}");

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("响应 shape 非法（搜索 data 非对象）→ PROTOCOL/502")
    void searchShapeMismatchMapsToProtocolError() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":\"not-an-object\"}");

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("异常与日志不保留 Authorization、原始 body、完整 host/URL/query")
    void failureDoesNotRetainAuthorizationUpstreamBodyOrFullUrl() {
        Logger logger = (Logger) LoggerFactory.getLogger(MallPortalClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        PortalException failure;
        try {
            expectJson("{\"code\":500,\"message\":\"" + UPSTREAM_SECRET + "\"}");
            failure = catchThrowableOfType(
                    () -> client().searchProducts(
                            new MallPortalClient.SearchParams(SEARCH_KEYWORD, null, null, 0, 1)),
                    PortalException.class);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(failure).isNotNull();
        assertSafeFailure(failure);
        // 日志不得打印 Token 或上游正文；断言用布尔比较，避免失败信息回显取值
        boolean leaked = appender.list.stream().anyMatch(event -> {
            String message = event.getFormattedMessage();
            return message != null
                    && (message.contains(MEMBER_TOKEN) || message.contains(UPSTREAM_SECRET));
        });
        assertThat(leaked).as("日志不得包含 Token 或上游正文").isFalse();
    }

    // ------------------------------------------------------------------ //
    // data=null 与不存在商品
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("优惠券历史 data=null 返回空列表")
    void couponHistoryDataNullReturnsEmptyList() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":null}");

        List<CouponHistoryResponse> history = client().listUnusedCouponHistory(MEMBER_TOKEN);

        assertThat(history).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("商品券 data=null 返回空列表")
    void productCouponsDataNullReturnsEmptyList() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":null}");

        List<ProductCouponResponse> coupons = client().listProductCoupons(27, MEMBER_TOKEN);

        assertThat(coupons).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("详情 data 缺少 product 字段 → NOT_FOUND")
    void detailWithoutProductFieldReportsNotFound() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":{}}");

        PortalException failure = catchThrowableOfType(
                () -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("详情 data 非对象 → NOT_FOUND")
    void detailWithNonObjectDataReportsNotFound() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":\"not-an-object\"}");

        PortalException failure = catchThrowableOfType(
                () -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.NOT_FOUND, 404);
        assertSafeFailure(failure);
    }

    // ------------------------------------------------------------------ //
    // 未知门户字段容忍（与严格全局 Jackson 策略兼容）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("DTO 容忍门户新增未知字段，且不把敏感值存入结果")
    void dtosTolerateUnknownPortalFields() {
        expectJson("{\"code\":200,\"message\":\"操作成功\",\"data\":{"
                + "\"pageNum\":1,\"pageSize\":5,\"totalPage\":1,\"total\":1,"
                + "\"futureField\":\"ignored\","
                + "\"list\":[{\"id\":26,\"name\":\"示例手机 A\",\"futureItemField\":123}]}}");

        ProductSearchResponse page =
                client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        assertThat(page.list()).hasSize(1);
        assertThat(page.list().get(0).id()).isEqualTo(26);
        assertThat(page.list().get(0).name()).isEqualTo("示例手机 A");
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R1：字段校验与 Python pydantic 宽松语义
    // ------------------------------------------------------------------ //

    /** Python 门户 envelope：固定 code=200，data 由调用方拼装。 */
    private static String envelope(String data) {
        return "{\"code\":200,\"message\":\"操作成功\",\"data\":" + data + "}";
    }

    @ParameterizedTest(name = "搜索条目字段非法则 PROTOCOL/502：{0}")
    @ValueSource(strings = {
        "{\"id\":26,\"name\":null}",
        "{\"id\":26,\"name\":123}",
        "{\"id\":26,\"name\":true}",
        "{\"id\":26,\"name\":\"A\",\"stock\":\"abc\"}",
        "{\"id\":26,\"name\":\"A\",\"stock\":100.5}",
        "{\"id\":26,\"name\":\"A\",\"stock\":\"0x10\"}",
        "{\"id\":26,\"name\":\"A\",\"brandName\":5}",
        "{\"id\":26,\"name\":\"A\",\"price\":\"abc\"}",
        "{\"id\":26,\"name\":\"A\",\"price\":true}",
        "{\"id\":26.5,\"name\":\"A\"}",
        "{\"name\":\"A\"}"
    })
    void searchRejectsItemFieldsPythonRejects(String item) {
        expectJson(envelope("{\"list\":[" + item + "]}"));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @ParameterizedTest(name = "搜索分页字段非法则 PROTOCOL/502：{0}")
    @ValueSource(strings = {"{\"pageNum\":2.5,\"list\":[]}", "{\"total\":\"abc\",\"list\":[]}"})
    void searchRejectsInvalidPageFields(String data) {
        expectJson(envelope(data));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("搜索接受 Python 宽松数值：整值 float、数字字符串、布尔按 int")
    void searchAcceptsPythonLaxNumericForms() {
        expectJson(envelope("{\"pageNum\":\"2\",\"pageSize\":5.0,\"totalPage\":1,\"total\":true,"
                + "\"list\":[{\"id\":26.0,\"name\":\"A\",\"stock\":\"100\",\"sale\":100.0,"
                + "\"publishStatus\":true}]}"));

        ProductSearchResponse page =
                client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        assertThat(page.pageNum()).isEqualTo(2);
        assertThat(page.pageSize()).isEqualTo(5);
        assertThat(page.totalPage()).isEqualTo(1);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.list().get(0).id()).isEqualTo(26);
        assertThat(page.list().get(0).stock()).isEqualTo(100);
        assertThat(page.list().get(0).sale()).isEqualTo(100);
        assertThat(page.list().get(0).publishStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("文本字段按 Python str_strip_whitespace 去空白，可选字段 null 保持 null")
    void textFieldsAreStrippedAndOptionalValuesStayNull() {
        expectJson(envelope("{\"list\":[{\"id\":26,\"name\":\"  示例手机 A  \","
                + "\"brandName\":\"  示例品牌 \",\"subTitle\":null,\"pic\":null,"
                + "\"stock\":null,\"price\":null}]}"));

        ProductSummary item = client()
                .searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1))
                .list()
                .get(0);

        assertThat(item.name()).isEqualTo("示例手机 A");
        assertThat(item.brandName()).isEqualTo("示例品牌");
        assertThat(item.subTitle()).isNull();
        assertThat(item.pic()).isNull();
        assertThat(item.stock()).isNull();
        assertThat(item.price()).isNull();
    }

    @Test
    @DisplayName("SKU 与优惠券无效行按 Python helper 整条跳过")
    void invalidSkuAndCouponRowsAreSkippedLikePython() {
        expectJson(envelope("{\"product\":{\"id\":27,\"name\":\"示例手机 B\",\"stock\":120},"
                + "\"skuStockList\":[{\"id\":9.0,\"stock\":5},{\"id\":10,\"stock\":\"abc\"},"
                + "{\"id\":11,\"stock\":7,\"lockStock\":2},{\"id\":12,\"lockStock\":\"x\"}],"
                + "\"couponList\":[{\"id\":1.0,\"amount\":1},{\"id\":55,\"amount\":\"abc\"},"
                + "{\"id\":77,\"amount\":1.5,\"useType\":0.0}]}"));

        ProductDetailResponse detail = client().getProductDetail(27);

        assertThat(detail.skuStocks()).hasSize(1);
        assertThat(detail.skuStocks().get(0).id()).isEqualTo(11);
        assertThat(detail.skuStocks().get(0).availableStock()).isEqualTo(5);
        assertThat(detail.availableStock()).isEqualTo(5);
        assertThat(detail.publicCoupons()).hasSize(1);
        assertThat(detail.publicCoupons().get(0).id()).isEqualTo(77);
        assertThat(detail.publicCoupons().get(0).amount().toPlainString()).isEqualTo("1.5");
        assertThat(detail.publicCoupons().get(0).useType()).isZero();
    }

    @ParameterizedTest(name = "详情 product 字段非法则 PROTOCOL/502：{0}")
    @ValueSource(strings = {
        "\"stock\":\"abc\"",
        "\"price\":\"abc\"",
        "\"name\":null",
        "\"brandName\":5",
        "\"stock\":100.5",
        "\"price\":true"
    })
    void detailRejectsInvalidProductFields(String field) {
        expectJson(envelope("{\"product\":{\"id\":27,\"name\":\"示例手机 B\"," + field + "}}"));

        PortalException failure =
                catchThrowableOfType(() -> client().getProductDetail(27), PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("详情没有可用 SKU 时库存回退到 product.stock")
    void detailFallsBackToProductStockWhenNoUsableSkuRemains() {
        expectJson(envelope("{\"product\":{\"id\":27,\"name\":\"示例手机 B\",\"stock\":120},"
                + "\"skuStockList\":[{\"id\":1,\"stock\":\"abc\"},{\"id\":2,\"lockStock\":\"x\"}]}"));

        ProductDetailResponse detail = client().getProductDetail(27);

        assertThat(detail.skuStocks()).isEmpty();
        assertThat(detail.availableStock()).isEqualTo(120);
    }

    @Test
    @DisplayName("商品属性最多保留 10 项、跳过无取值属性并按 Python 语义去空白")
    void detailKeepsAtMostTenValuedAttributes() {
        StringBuilder attributes = new StringBuilder("{\"id\":1,\"name\":\"  颜色  \"}");
        StringBuilder values = new StringBuilder("{\"productAttributeId\":1,\"value\":\"  金色  \"}");
        for (int index = 2; index <= 12; index++) {
            attributes.append(",{\"id\":").append(index).append(",\"name\":\"属性").append(index).append("\"}");
            values.append(",{\"productAttributeId\":").append(index)
                    .append(",\"value\":\"值").append(index).append("\"}");
        }
        attributes.append(",{\"id\":99,\"name\":\"无取值\"}");

        expectJson(envelope("{\"product\":{\"id\":27,\"name\":\"示例手机 B\"},"
                + "\"productAttributeList\":[" + attributes + "],"
                + "\"productAttributeValueList\":[" + values + "]}"));

        ProductDetailResponse detail = client().getProductDetail(27);

        assertThat(detail.attributes()).hasSize(10);
        assertThat(detail.attributes().get(0).name()).isEqualTo("颜色");
        assertThat(detail.attributes().get(0).value()).isEqualTo("金色");
        assertThat(detail.attributes()).extracting(attribute -> attribute.name()).doesNotContain("无取值");
    }

    @ParameterizedTest(name = "会员身份非法则 MEMBER_UNAUTHORIZED：{0}")
    @ValueSource(strings = {
        "{\"id\":null}",
        "{\"id\":7.0}",
        "{\"id\":\"abc\"}",
        "{}",
        "{\"id\":7,\"nickname\":123}"
    })
    void memberIdentityWithInvalidShapeMapsToUnauthorized(String data) {
        expectJson(envelope(data));

        PortalException failure = catchThrowableOfType(
                () -> client().resolveMember(MEMBER_TOKEN), PortalException.class);

        assertKind(failure, PortalException.Kind.MEMBER_UNAUTHORIZED, 401);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("会员身份文本字段按 Python 语义去空白")
    void memberIdentityTextFieldsAreStripped() {
        expectJson(envelope("{\"id\":7,\"username\":\"  member  \",\"nickname\":\"  示例会员  \"}"));

        MemberInfoResponse member = client().resolveMember(MEMBER_TOKEN);

        assertThat(member.memberId()).isEqualTo(7);
        assertThat(member.username()).isEqualTo("member");
        assertThat(member.nickname()).isEqualTo("示例会员");
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R2：时间与会员等级字段（对齐 Python 模型完整字段）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("未使用券历史保留 createTime，并兼容 Python 接受的多种时间形式")
    void couponHistoryRetainsCreateTimeAcrossPythonAcceptedForms() throws Exception {
        expectJson(envelope("["
                + "{\"id\":1,\"couponId\":55,\"useStatus\":0,\"createTime\":\"2024-05-01T02:00:00.000Z\"},"
                + "{\"id\":2,\"couponId\":77,\"useStatus\":0,\"createTime\":\"2024-05-01 02:00:00\"},"
                + "{\"id\":3,\"couponId\":88,\"useStatus\":0,\"createTime\":1714528800000},"
                + "{\"id\":4,\"couponId\":98,\"useStatus\":0,\"createTime\":1714528800.123},"
                + "{\"id\":5,\"couponId\":99,\"useStatus\":0,\"createTime\":\"2024-05-01T10:00:00+08:00\"},"
                + "{\"id\":6,\"couponId\":100,\"useStatus\":0,\"createTime\":null},"
                + "{\"id\":7,\"couponId\":101,\"useStatus\":0,\"createTime\":\"not-a-date\"}]"));

        List<CouponHistoryResponse> history = client().listUnusedCouponHistory(MEMBER_TOKEN);

        // 无法解析的时间在 Python 里是校验失败，因此整条跳过
        assertThat(history).hasSize(6);
        assertThat(history).extracting(CouponHistoryResponse::couponId)
                .containsExactly(55L, 77L, 88L, 98L, 99L, 100L);
        assertThat(history.get(0).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        // 空格分隔且无偏移：按 UTC 规范化
        assertThat(history.get(1).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        // epoch 毫秒
        assertThat(history.get(2).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        // 小数秒必须精确保留，不得经 double 截断
        assertThat(history.get(3).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00.123Z"));
        // 带偏移
        assertThat(history.get(4).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        assertThat(history.get(5).createTime()).isNull();

        String serialized = JSON.writeValueAsString(history);
        assertThat(serialized).contains("createTime");
        assertThat(serialized).doesNotContain("couponCode", "memberId", "memberNickname", "password", "token");
    }

    @Test
    @DisplayName("商品券保留 memberLevel，无法解析时间的行整条跳过")
    void productCouponsRetainMemberLevelAndSkipUnparsableTimes() {
        expectJson(envelope("["
                + "{\"id\":55,\"amount\":50.0,\"minPoint\":500.0,\"memberLevel\":3,"
                + "\"startTime\":\"2024-01-01T00:00:00.000Z\",\"endTime\":\"2030-01-01T00:00:00.000Z\","
                + "\"useType\":0},"
                + "{\"id\":56,\"memberLevel\":null,\"endTime\":null},"
                + "{\"id\":57,\"endTime\":\"not-a-date\"}]"));

        List<ProductCouponResponse> coupons = client().listProductCoupons(27, MEMBER_TOKEN);

        assertThat(coupons).hasSize(2);
        assertThat(coupons.get(0).id()).isEqualTo(55);
        assertThat(coupons.get(0).memberLevel()).isEqualTo(3);
        assertThat(coupons.get(0).endTime()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        assertThat(coupons.get(1).id()).isEqualTo(56);
        assertThat(coupons.get(1).memberLevel()).isNull();
        assertThat(coupons.get(1).endTime()).isNull();
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R3：搜索 query 编码（对齐 Python httpx params 语义）
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("搜索关键词按 httpx quote_plus 语义编码：单一参数、无注入、无 fragment")
    void searchEncodesKeywordAsSingleHttpxEquivalentQueryParameter() {
        expectJsonFixture("product-search.json");
        String keyword = "a b&c=d+e%f#g?h/手机~_-.";

        client().searchProducts(new MallPortalClient.SearchParams(keyword, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(request.uri().getRawFragment()).isNull();
        assertThat(rawQueryCount(request.uri(), "keyword")).isEqualTo(1);
        assertThat(rawQueryNames(request.uri()))
                .containsExactlyInAnyOrder("pageNum", "pageSize", "sort", "keyword");
        // 原始编码与 Python httpx（urllib.parse.quote_plus）一致，见报告中的 Python 探针输出
        assertThat(rawQueryValue(request.uri(), "keyword"))
                .isEqualTo("a+b%26c%3Dd%2Be%25f%23g%3Fh%2F%E6%89%8B%E6%9C%BA~_-.");
        // 按表单语义解码后等于规范化后的原关键词
        assertThat(decodedQueryValue(request.uri(), "keyword")).isEqualTo(keyword);
    }

    @ParameterizedTest(name = "关键词 {0} 不破坏 query 结构")
    @ValueSource(strings = {
        "100%", "%zz", "a#b", "a?b", "a&b", "a=b", "a+b", "a/b", "a;b", "a:b",
        "手机", "café", "~-._", "a b"
    })
    void searchNeverBreaksQueryStructureOrLeaksIntoFragment(String keyword) {
        expectJsonFixture("product-search.json");

        client().searchProducts(new MallPortalClient.SearchParams(keyword, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(request.uri().getRawFragment()).isNull();
        assertThat(rawQueryCount(request.uri(), "keyword")).isEqualTo(1);
        assertThat(queryParams(request)).containsOnlyKeys("pageNum", "pageSize", "sort", "keyword");
        assertThat(decodedQueryValue(request.uri(), "keyword")).isEqualTo(keyword);
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R4：构造边界、异常工厂收敛与非法 Authorization
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "非法 portalBaseUrl 被拒绝且不回显输入：{0}")
    @ValueSource(strings = {
        "",
        "   ",
        "portal.internal:8085",
        "/relative/path",
        "//portal.internal:8085",
        "ftp://portal.internal:8085",
        "http://user:secret-marker@portal.internal:8085",
        "http://portal.internal:8085?x=1",
        "http://portal.internal:8085#frag",
        "http://portal.internal:8085/a b"
    })
    void constructorRejectsUnsafeBaseUrl(String baseUrl) {
        IllegalArgumentException failure = catchThrowableOfType(
                () -> new MallPortalClient(restClientBuilder, baseUrl), IllegalArgumentException.class);

        assertThat(failure).as("必须拒绝非法基础地址").isNotNull();
        if (!baseUrl.isBlank()) {
            // 不回显输入：既避免泄漏内嵌凭据，也避免把原始地址写进日志/报告
            assertThat(failure.getMessage()).doesNotContain(baseUrl);
        }
        assertThat(recorded).isEmpty();
    }

    @Test
    @DisplayName("接受带路径前缀的 http 基础地址，并去掉末尾斜杠")
    void constructorAcceptsHttpBaseWithPathPrefix() {
        expectJsonFixture("product-search.json");

        MallPortalClient prefixed = new MallPortalClient(restClientBuilder, PORTAL_BASE_URL + "/portal/");
        prefixed.searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertGetOnConfiguredHost(request);
        assertThat(request.uri().getPath()).isEqualTo("/portal/product/search");
    }

    @Test
    @DisplayName("接受不带端口号的 https 基础地址")
    void constructorAcceptsHttpsBaseWithoutExplicitPort() {
        expectJsonFixture("product-search.json");

        new MallPortalClient(restClientBuilder, "https://" + PORTAL_HOST)
                .searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertThat(request.uri().getScheme()).isEqualTo("https");
        assertThat(request.uri().getHost()).isEqualTo(PORTAL_HOST);
        assertThat(request.uri().getPort()).isEqualTo(-1);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
    }

    @Test
    @DisplayName("PortalException 公开工厂不接受调用方消息，避免注入上游正文/URL/Token")
    void portalExceptionDoesNotExposeMessageInjectingFactory() {
        for (Method method : PortalException.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat((Object) parameter)
                        .as("公开异常工厂 %s 不得接受任意消息参数", method.getName())
                        .isNotEqualTo(String.class);
            }
        }
    }

    @ParameterizedTest(name = "非法 Authorization 映射为 UNAVAILABLE/502")
    @ValueSource(strings = {
        "Bearer inject-marker\r\nX-Injected: 1",
        "Bearer inject-marker\nX-Injected: 1",
        "Bearer inject-marker\u0000"
    })
    void invalidAuthorizationHeaderMapsToUnavailable(String authorization) {
        MallPortalClient client = client();

        PortalException failure = catchThrowableOfType(
                () -> client.resolveMember(authorization), PortalException.class);

        assertKind(failure, PortalException.Kind.UNAVAILABLE, 502);
        assertThat(describe(failure)).doesNotContain("inject-marker");
        assertThat(recorded).as("非法头值不得发出请求").isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("空 Authorization 按 Python falsey 语义完全不发送")
    void emptyAuthorizationIsNotSentLikePythonFalsey() {
        expectJsonFixture("sso-info.json");

        MemberInfoResponse member = client().resolveMember("");

        assertThat(member.memberId()).isEqualTo(7);
        assertNoAuthorization(onlyRequest());
        server.verify();
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R5：搜索分页字段「缺失 vs 显式 null」与时间字符串边界
    // ------------------------------------------------------------------ //

    @ParameterizedTest(name = "搜索分页字段显式 null 则 PROTOCOL/502：{0}")
    @ValueSource(strings = {
        "{\"pageNum\":null,\"list\":[]}",
        "{\"pageSize\":null,\"list\":[]}",
        "{\"totalPage\":null,\"list\":[]}",
        "{\"total\":null,\"list\":[]}",
        "{\"list\":null}"
    })
    void searchRejectsExplicitNullPageFields(String data) {
        expectJson(envelope(data));

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        assertKind(failure, PortalException.Kind.PROTOCOL, 502);
        assertSafeFailure(failure);
    }

    @Test
    @DisplayName("搜索分页字段缺失时使用 Python 模型默认值，而不是请求页码")
    void searchUsesPythonDefaultsWhenPageFieldsAreMissing() {
        expectJson(envelope("{}"));
        expectJson(envelope("{\"total\":7}"));

        MallPortalClient client = client();
        ProductSearchResponse empty =
                client.searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 4));

        assertThat(empty.pageNum()).isEqualTo(1);
        assertThat(empty.pageSize()).isEqualTo(5);
        assertThat(empty.totalPage()).isZero();
        assertThat(empty.total()).isZero();
        assertThat(empty.list()).isEmpty();

        ProductSearchResponse partial =
                client.searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 4));

        assertThat(partial.pageNum()).isEqualTo(1);
        assertThat(partial.pageSize()).isEqualTo(5);
        assertThat(partial.total()).isEqualTo(7);
        assertThat(partial.totalPage()).isZero();
        assertThat(partial.list()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("时间字符串：科学记数与首尾空白文本整行跳过，合法行精确保留")
    void couponHistoryAppliesPythonTimeStringRules() {
        expectJson(envelope("["
                + "{\"id\":1,\"couponId\":55,\"useStatus\":0,\"createTime\":\"1714528800\"},"
                + "{\"id\":2,\"couponId\":56,\"useStatus\":0,\"createTime\":\"1714528800.123\"},"
                + "{\"id\":3,\"couponId\":57,\"useStatus\":0,\"createTime\":1714528800.25},"
                + "{\"id\":4,\"couponId\":58,\"useStatus\":0,\"createTime\":\" 1714528800 \"},"
                + "{\"id\":5,\"couponId\":59,\"useStatus\":0,\"createTime\":\" 2024-05-01T02:00:00Z \"},"
                + "{\"id\":6,\"couponId\":60,\"useStatus\":0,\"createTime\":\"1e9\"},"
                + "{\"id\":7,\"couponId\":61,\"useStatus\":0,\"createTime\":\"1E9\"},"
                + "{\"id\":8,\"couponId\":62,\"useStatus\":0,\"createTime\":\"1e+9\"}]"));

        List<CouponHistoryResponse> history = client().listUnusedCouponHistory(MEMBER_TOKEN);

        assertThat(history).hasSize(3);
        assertThat(history).extracting(CouponHistoryResponse::couponId).containsExactly(55L, 56L, 57L);
        assertThat(history.get(0).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        assertThat(history.get(1).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00.123Z"));
        // JSON 数值小数秒必须精确保留
        assertThat(history.get(2).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00.250Z"));
    }

    @Test
    @DisplayName("时间字符串保留 pydantic 实测接受的普通十进制形态（含符号、trailing dot、前导点）")
    void couponHistoryAcceptsPlainDecimalTimeStringsPythonAccepts() {
        expectJson(envelope("["
                + "{\"id\":1,\"couponId\":51,\"useStatus\":0,\"createTime\":\"+1000\"},"
                + "{\"id\":2,\"couponId\":52,\"useStatus\":0,\"createTime\":\"-1000\"},"
                + "{\"id\":3,\"couponId\":53,\"useStatus\":0,\"createTime\":\"2024\"},"
                + "{\"id\":4,\"couponId\":54,\"useStatus\":0,\"createTime\":\"1714528800.\"},"
                + "{\"id\":5,\"couponId\":55,\"useStatus\":0,\"createTime\":\".5\"}]"));

        List<CouponHistoryResponse> history = client().listUnusedCouponHistory(MEMBER_TOKEN);

        assertThat(history).hasSize(5);
        assertThat(history.get(0).createTime()).isEqualTo(Instant.parse("1970-01-01T00:16:40Z"));
        assertThat(history.get(1).createTime()).isEqualTo(Instant.parse("1969-12-31T23:43:20Z"));
        assertThat(history.get(2).createTime()).isEqualTo(Instant.parse("1970-01-01T00:33:44Z"));
        assertThat(history.get(3).createTime()).isEqualTo(Instant.parse("2024-05-01T02:00:00Z"));
        assertThat(history.get(4).createTime()).isEqualTo(Instant.parse("1970-01-01T00:00:00.500Z"));
    }

    // ------------------------------------------------------------------ //
    // 审查返工 R6：响应体硬上限
    // ------------------------------------------------------------------ //

    /**
     * 恶意/故障门户可返回超大正文；客户端必须以固定字节上限有界读取，超过上限立即按固定安全错误失败，
     * 且<strong>不在读到上限后继续 drain 完整正文</strong>。异常不得携带合成正文标记或 Token。
     */
    @Test
    @DisplayName("响应体超过硬上限时按固定协议错误失败，且不读取封顶之后的正文")
    void oversizedResponseBodyFailsWithFixedProtocolErrorWithoutDraining() {
        long cap = RESPONSE_BODY_CAP_BYTES;
        GeneratedBodyStream stream = new GeneratedBodyStream(
                "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"pageNum\":1,\"pageSize\":5,\"totalPage\":1,\"total\":1,"
                        + "\"list\":[{\"id\":1,\"name\":\"" + UPSTREAM_SECRET,
                "\"}]}}",
                cap + 4096);
        server.expect(capture()).andRespond(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(stream, HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            return response;
        });

        PortalException failure = catchThrowableOfType(
                () -> client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1)),
                PortalException.class);

        server.verify();
        assertThat(failure).as("超过硬上限必须按固定错误失败，而不是当成正常响应").isNotNull();
        assertThat(failure.kind()).isEqualTo(PortalException.Kind.PROTOCOL);
        assertThat(failure.getCause()).as("不得链式持有上游正文或原始异常").isNull();
        assertThat(failure.getMessage())
                .doesNotContain(UPSTREAM_SECRET)
                .doesNotContain(MEMBER_TOKEN);
        assertThat(stream.consumed())
                .as("读到上限后必须停止，不得继续 drain 完整正文")
                .isLessThanOrEqualTo(cap + 1)
                .isLessThan(stream.totalBytes());
    }

    // ------------------------------------------------------------------ //
    // 辅助方法
    // ------------------------------------------------------------------ //

    private MallPortalClient client() {
        return new MallPortalClient(restClientBuilder, PORTAL_BASE_URL);
    }

    private RequestMatcher capture() {
        return request -> recorded.add(toRecorded(request));
    }

    private static Recorded toRecorded(ClientHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        return new Recorded(request.getMethod(), request.getURI(), headers);
    }

    private void expectJson(String body) {
        server.expect(capture()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectJsonFixture(String fixtureName) {
        server.expect(capture()).andRespond(withSuccess(fixture(fixtureName), MediaType.APPLICATION_JSON));
    }

    private static String fixture(String name) {
        try {
            return new ClassPathResource("portal/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("无法读取 fixture " + name, ex);
        }
    }

    private Recorded onlyRequest() {
        assertThat(recorded).as("必须发出且只发出一次门户请求").hasSize(1);
        return recorded.get(0);
    }

    private static void assertGetOnConfiguredHost(Recorded request) {
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.uri().getScheme()).isEqualTo(PORTAL_SCHEME);
        assertThat(request.uri().getHost()).isEqualTo(PORTAL_HOST);
        assertThat(request.uri().getPort()).isEqualTo(PORTAL_PORT);
    }

    private static void assertNoAuthorization(Recorded request) {
        assertThat(request.headers().containsKey(HttpHeaders.AUTHORIZATION))
                .as("商品搜索与详情不得携带 Authorization")
                .isFalse();
    }

    private static void assertAuthorizationPassedThrough(Recorded request) {
        // 用布尔比较，避免失败信息回显 Token 取值
        assertThat(MEMBER_TOKEN.equals(request.headers().getFirst(HttpHeaders.AUTHORIZATION)))
                .as("会员接口必须原样透传 Authorization")
                .isTrue();
    }

    private static void assertKind(PortalException failure, PortalException.Kind kind, int httpStatus) {
        assertThat(failure).as("必须抛出 PortalException").isNotNull();
        assertThat(failure.kind()).isEqualTo(kind);
        assertThat(failure.httpStatus()).isEqualTo(httpStatus);
    }

    /** 异常链（含自身）不得携带 Token、上游正文、host 或完整 query。 */
    private static void assertSafeFailure(PortalException failure) {
        assertThat(failure).isNotNull();
        String described = describe(failure);
        boolean leaked = described.contains(MEMBER_TOKEN)
                || described.contains(UPSTREAM_SECRET)
                || described.contains(PORTAL_HOST)
                || described.contains(SEARCH_KEYWORD);
        assertThat(leaked).as("异常不得携带凭据、上游正文或完整 URL/query").isFalse();
    }

    private static String describe(Throwable failure) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            builder.append(current.getClass().getName())
                    .append(' ')
                    .append(current.getMessage())
                    .append('\n');
            if (current.getCause() == current) {
                break;
            }
        }
        return builder.toString();
    }

    /**
     * 语义查询参数：按 Python/httpx 的表单语义（{@code unquote_plus}）解码，断言比较的是参数取值
     * 而不是编码形态。
     *
     * <p>注意 {@code UriComponentsBuilder.fromUri(URI)} 解析 {@code build()} 后返回的是原始
     * （仍带百分号编码的）文本，不会自动解码；所以这里必须显式 {@link URLDecoder#decode}，
     * 否则断言只会比较编码后的字符串。
     */
    private static Map<String, String> queryParams(Recorded request) {
        Map<String, String> result = new LinkedHashMap<>();
        UriComponentsBuilder.fromUri(request.uri())
                .build()
                .getQueryParams()
                .forEach((key, values) -> result.put(
                        key,
                        values.isEmpty()
                                ? null
                                : URLDecoder.decode(values.get(0), StandardCharsets.UTF_8)));
        return result;
    }

    /** 原始 query 的 name=value 对；这里刻意<strong>不</strong>做任何解码。 */
    private static List<String[]> rawQueryPairs(URI uri) {
        List<String[]> pairs = new ArrayList<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return pairs;
        }
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                pairs.add(new String[] {pair, ""});
            } else {
                pairs.add(new String[] {pair.substring(0, separator), pair.substring(separator + 1)});
            }
        }
        return pairs;
    }

    private static String rawQueryValue(URI uri, String name) {
        for (String[] pair : rawQueryPairs(uri)) {
            if (pair[0].equals(name)) {
                return pair[1];
            }
        }
        return null;
    }

    private static int rawQueryCount(URI uri, String name) {
        int count = 0;
        for (String[] pair : rawQueryPairs(uri)) {
            if (pair[0].equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static List<String> rawQueryNames(URI uri) {
        List<String> names = new ArrayList<>();
        for (String[] pair : rawQueryPairs(uri)) {
            names.add(pair[0]);
        }
        return names;
    }

    /** 按 Python 表单语义（{@code unquote_plus}）解码单个 query 取值。 */
    private static String decodedQueryValue(URI uri, String name) {
        String raw = rawQueryValue(uri, name);
        return raw == null ? null : URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static boolean isAllowedPath(String path) {
        return path.equals("/product/search")
                || path.startsWith("/product/detail/")
                || path.equals("/sso/info")
                || path.equals("/member/coupon/listHistory")
                || path.startsWith("/member/coupon/listByProduct/");
    }

    private static void assertInvalidSearchParams(Supplier<MallPortalClient.SearchParams> factory) {
        IllegalArgumentException failure = catchThrowableOfType(factory::get, IllegalArgumentException.class);
        assertThat(failure).as("非法 SearchParams 必须在构造阶段被拒绝").isNotNull();
    }

    /** 记录的门户请求。 */
    private record Recorded(HttpMethod method, URI uri, HttpHeaders headers) {
    }
}
