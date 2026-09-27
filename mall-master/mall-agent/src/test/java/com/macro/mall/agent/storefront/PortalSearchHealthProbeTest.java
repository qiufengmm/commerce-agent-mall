package com.macro.mall.agent.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import com.macro.mall.agent.api.HealthProbe;

/**
 * {@link PortalSearchHealthProbe} 的 TDD 契约测试。
 *
 * <p>证明就绪探针被「包装」而非「新增门户客户端公开方法」：{@link PortalSearchHealthProbe} 实现
 * {@link HealthProbe}，并在 {@link #isHealthy()} 时委托 {@link MallPortalClient} 的包级固定匿名
 * 只读检查。全程用 {@link MockRestServiceServer} 离线模拟，不访问真实 mall-portal、不携带凭据。
 *
 * <p>安全边界：
 * <ul>
 *   <li>包装器公开契约只有 {@link HealthProbe#isHealthy()}，不暴露任何接受 URL / HTTP method /
 *       header 的公共方法；</li>
 *   <li>不新增第六个 {@link MallPortalClient} 公开业务方法（由
 *       {@code MallPortalClientTest#portalClientExposesOnlyTheFiveFixedReadOperations} 守卫）。</li>
 * </ul>
 */
class PortalSearchHealthProbeTest {

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

    @Test
    @DisplayName("包装器是 HealthProbe，healthy 委托门户固定匿名只读检查（HTTP 200 + code=200 + 非负 total）")
    void probeDelegatesToPortalFixedAnonymousProbe() {
        expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":7}}");

        HealthProbe probe = new PortalSearchHealthProbe(client());

        assertThat(probe).isInstanceOf(HealthProbe.class);
        assertThat(probe.isHealthy()).isTrue();

        Recorded request = onlyRequest();
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.uri().getHost()).isEqualTo("portal.internal");
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(request.uri().getRawQuery()).isEqualTo(READINESS_RAW_QUERY);
        assertThat(request.headers().containsKey(HttpHeaders.AUTHORIZATION))
                .as("就绪探针必须匿名，不得携带 Authorization")
                .isFalse();
    }

    @Test
    @DisplayName("门户 envelope code 非 200 时包装器返回不健康")
    void probeReportsUnhealthyForNonOkEnvelope() {
        expect(200, "{\"code\":500,\"message\":\"落库失败\",\"data\":{\"total\":7}}");

        assertThat(new PortalSearchHealthProbe(client()).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("连接失败时包装器失败关闭，不抛异常")
    void probeFailsClosedOnConnectionFailure() {
        server.expect(capture()).andRespond(withException(new ConnectException("connection refused")));

        assertThat(new PortalSearchHealthProbe(client()).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("包装器公开契约不暴露任何 URL/HTTP method/header 入口")
    void probeExposesNoArbitraryRequestEntry() {
        List<Method> publicMethods = Arrays.stream(PortalSearchHealthProbe.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .collect(Collectors.toList());

        assertThat(publicMethods).extracting(Method::getName).containsExactly("isHealthy");
        for (Method method : publicMethods) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertThat((Object) parameter)
                        .as("包装器公开方法不得接受任意 URI / HTTP method / header 参数")
                        .isNotIn(URI.class, HttpMethod.class, HttpHeaders.class);
            }
        }
    }

    @Test
    @DisplayName("门户客户端为 null 时拒绝构造")
    void rejectsNullPortalClient() {
        assertThatThrownBy(() -> new PortalSearchHealthProbe(null))
                .isInstanceOf(NullPointerException.class);
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

    private Recorded onlyRequest() {
        assertThat(recorded).as("必须发出且只发出一次门户请求").hasSize(1);
        return recorded.get(0);
    }

    private record Recorded(HttpMethod method, URI uri, HttpHeaders headers) {
    }
}
