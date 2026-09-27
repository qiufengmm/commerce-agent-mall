package com.macro.mall.agent.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * {@link MallPortalClient#isServiceReady()} 固定匿名探针的 TDD 契约测试。
 *
 * <p>只覆盖「就绪探针专用」的固定请求与 envelope 判定，全程用 {@link MockRestServiceServer}
 * 离线模拟，不访问真实 mall-portal、不携带任何凭据。
 *
 * <p>探针的不变量：
 * <ul>
 *   <li>固定 {@code GET /product/search?pageNum=1&pageSize=1}，仅两个 query 参数，无 {@code sort/keyword}；</li>
 *   <li>不携带 {@code Authorization}（匿名探针）；</li>
 *   <li>仅当 HTTP 200 且业务 envelope {@code code=200} 且 {@code data.total} 为<strong>非负整数</strong>时返回 true；</li>
 *   <li>任何协议不符、HTTP 非 200、连接/超时异常都返回 false，不抛出；</li>
 *   <li>与业务 {@link MallPortalClient#searchProducts} 分离——后者的 {@code pageSize} 仍固定为 5。</li>
 * </ul>
 */
class MallPortalClientReadinessTest {

    private static final String PORTAL_BASE_URL = "http://portal.internal:8085";
    private static final String READINESS_RAW_QUERY = "pageNum=1&pageSize=1";

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private final List<Recorded> recorded = new ArrayList<>();

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        recorded.clear();
    }

    // ------------------------------------------------------------------ //
    // 固定请求：method / host / 精确白名单 path / 无鉴权
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("就绪探针发送固定匿名 GET /product/search?pageNum=1&pageSize=1，无 Authorization")
    void readinessSendsExactFixedAnonymousRequest() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");

        assertThat(client().isServiceReady()).isTrue();

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.uri().getScheme()).isEqualTo("http");
        assertThat(request.uri().getHost()).isEqualTo("portal.internal");
        assertThat(request.uri().getPort()).isEqualTo(8085);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(request.uri().getRawQuery()).isEqualTo(READINESS_RAW_QUERY);
        assertThat(rawQueryPairs(request.uri())).containsExactly("pageNum=1", "pageSize=1");
        assertThat(request.headers().containsKey(HttpHeaders.AUTHORIZATION))
                .as("就绪探针必须匿名，不得携带 Authorization")
                .isFalse();
    }

    // ------------------------------------------------------------------ //
    // data.total 判定
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("data.total 为非负整数时探针健康（0 与正整数都接受）")
    void readinessAcceptsNonNegativeIntegerTotal() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");
        assertThat(client().isServiceReady()).isTrue();

        setUp();
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":7}}");
        assertThat(client().isServiceReady()).isTrue();
    }

    @Test
    @DisplayName("data.total 为负数时探针不健康")
    void readinessRejectsNegativeTotal() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":-1}}");
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("data.total 缺失或为 null 时探针不健康")
    void readinessRejectsMissingOrNullTotal() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"pageNum\":1}}");
        assertThat(client().isServiceReady()).isFalse();

        setUp();
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":null}}");
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("data.total 不是 JSON 整数时探针不健康")
    void readinessRejectsNonIntegerTotal() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":\"7\"}}");
        assertThat(client().isServiceReady()).as("数字字符串不是整数").isFalse();

        setUp();
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":7.5}}");
        assertThat(client().isServiceReady()).as("非整值小数不是整数").isFalse();

        setUp();
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":true}}");
        assertThat(client().isServiceReady()).as("布尔不是整数").isFalse();
    }

    // ------------------------------------------------------------------ //
    // HTTP / envelope / 协议失败
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("HTTP 200 但业务 envelope code 非 200 时探针不健康")
    void readinessRejectsNonOkEnvelopeCode() {
        expect(200, "{\"code\":500,\"message\":\"落库失败\",\"data\":{\"total\":7}}");
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("HTTP 非 200 时探针不健康")
    void readinessRejectsNonOkHttpStatus() {
        expect(503, "{\"code\":503,\"message\":\"门户不可用\"}");
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("HTTP 200 但响应不是合法 envelope 时探针不健康")
    void readinessRejectsUnparseableBody() {
        expect(200, "not-a-json-body");
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("连接失败时探针返回不健康而不是抛异常")
    void readinessTreatsConnectionFailureAsNotReady() {
        server.expect(capture()).andRespond(withException(new ConnectException("connection refused")));
        assertThat(client().isServiceReady()).isFalse();
    }

    @Test
    @DisplayName("读取超时探针返回不健康而不是抛异常")
    void readinessTreatsTimeoutAsNotReady() {
        server.expect(capture()).andRespond(withException(new SocketTimeoutException("read timed out")));
        assertThat(client().isServiceReady()).isFalse();
    }

    // ------------------------------------------------------------------ //
    // 不影响业务搜索语义
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("就绪探针不改变业务 searchProducts 的固定 pageSize=5 语义")
    void readinessDoesNotChangeBusinessSearchPageSize() {
        expectJsonFixture("product-search.json");

        client().searchProducts(new MallPortalClient.SearchParams(null, null, null, 0, 1));

        Recorded request = onlyRequest();
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(rawQueryPairs(request.uri()))
                .as("业务搜索必须继续使用固定 pageSize=5，而不是探针的 pageSize=1")
                .contains("pageNum=1", "pageSize=5")
                .doesNotContain("pageSize=1");
    }

    // ------------------------------------------------------------------ //
    // 辅助
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

    private void expect(int status, String body) {
        server.expect(capture())
                .andRespond(withStatus(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON));
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

    private static List<String> rawQueryPairs(URI uri) {
        String rawQuery = uri.getRawQuery();
        return rawQuery == null ? List.of() : List.of(rawQuery.split("&", -1));
    }

    private record Recorded(HttpMethod method, URI uri, HttpHeaders headers) {
    }
}
