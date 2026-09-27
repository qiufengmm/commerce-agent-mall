package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

import com.macro.mall.agent.MallAgentApplication;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.storefront.MallPortalClient;

/**
 * 就绪探针装配的<strong>行为</strong>测试：真实启动 Spring 上下文，让 {@code /health/ready}
 * 走真实的 {@link com.macro.mall.agent.api.HealthProbe} 装配，再通过
 * {@link MockRestServiceServer}（真实 {@link MallPortalClient}）与 Mockito
 * {@link RedisConnectionFactory} 只模拟外部 HTTP / Redis 边界。
 *
 * <p>目的：证明「旧占位实现（两个探针恒 {@code () -> false}）→ ready 恒 503」这一行为被替换为
 * 依赖真实的就绪语义：门户固定匿名只读探针与 Redis PING 都健康时 200，任一失败时 503，
 * 且失败响应不包含依赖地址、异常正文或凭据。
 *
 * <p>本类刻意只引用<strong>已存在</strong>的生产类型，因此可以在探针改造<strong>之前</strong>编译并
 * 运行：红灯来自「占位探针恒 false → ready 503 ≠ 期望 200」，而不是编译失败。
 *
 * <p>隔离边界：test profile 排除 Redis 自动配置，Redis 连接工厂与 {@link StringRedisTemplate}
 * 由 Mockito 替身提供；模型模式钉死为 stub；门户 HTTP 由 {@link MockRestServiceServer} 截获，
 * 不建立任何真实网络连接、不写入任何 Redis。
 */
@SpringBootTest(
        classes = MallAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            "MALL_AGENT_HOST=127.0.0.1",
            "MALL_AGENT_PORT=8086",
            "MALL_AGENT_LOG_LEVEL=INFO",
            // 通配来源已被生产配置拒绝（fail closed）；这里钉死为「不授权任何来源」。
            "MALL_AGENT_CORS_ALLOW_ORIGINS=",
            // 钉死为「不信任任何反向代理」（fail closed）；空值语义与生产默认一致，且杜绝宿主取值渗入。
            "MALL_AGENT_TRUSTED_PROXY_IP=",
            "MALL_AGENT_REQUEST_TIMEOUT_SECONDS=35",
            "MALL_AGENT_MODEL_MODE=stub",
            "MALL_AGENT_OPENAI_API_KEY=",
            "MALL_AGENT_OPENAI_BASE_URL=http://127.0.0.1:8080/v1",
            "MALL_AGENT_OPENAI_MODEL=stub-model",
            "MALL_AGENT_OPENAI_TIMEOUT_SECONDS=30",
            "MALL_AGENT_MAX_TOOL_ROUNDS=4",
            // 只用于让被替换的门户客户端校验通过；MockRestServiceServer 会在建连前截获请求，不做 DNS 解析。
            "MALL_AGENT_PORTAL_BASE_URL=http://portal.internal:8085",
            "MALL_AGENT_PORTAL_TIMEOUT_SECONDS=10",
            "MALL_AGENT_REDIS_URL=redis://127.0.0.1:6379/0",
            "MALL_AGENT_SESSION_TTL_SECONDS=86400",
            "MALL_AGENT_SESSION_MAX_MESSAGES=20",
            "MALL_AGENT_RATE_LIMIT_SESSION_LIMIT=20",
            "MALL_AGENT_RATE_LIMIT_SESSION_WINDOW_SECONDS=300",
            "MALL_AGENT_RATE_LIMIT_IP_LIMIT=60",
            "MALL_AGENT_RATE_LIMIT_IP_WINDOW_SECONDS=300",
            "MALL_AGENT_TOOL_RESULT_MAX_CHARS=4000",
            "MALL_AGENT_CONTEXT_MAX_CHARS=16000"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({HealthReadinessIntegrationTest.PortalStub.class, HealthReadinessIntegrationTest.RedisSubstitute.class})
class HealthReadinessIntegrationTest {

    private static final String PORTAL_BASE_URL = "http://portal.internal:8085";
    private static final String READINESS_QUERY = "pageNum=1&pageSize=1";

    /** 合成敏感标记：只用于断言失败响应不回显依赖地址、上游正文或凭据。 */
    private static final String LEAK_HOST_MARKER = "portal.internal";
    private static final String LEAK_BODY_MARKER = "upstream-secret-body";
    private static final String LEAK_CREDENTIAL_MARKER = "synthetic-credential";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PortalStub portalStub;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @BeforeEach
    void resetStubs() {
        portalStub.server().reset();
        portalStub.recorded().clear();
        // 每个用例都从「未配置任何 Redis 行为」开始，避免替身桩在用例间泄漏。
        Mockito.reset(redisConnectionFactory);
    }

    // ------------------------------------------------------------------ //
    // 依赖健康 → 就绪 200
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("门户固定匿名探针与 Redis PING 都健康时，ready 返回 200 UP 且只发一次固定请求")
    void readyIsUpWhenPortalAndRedisAreHealthy() throws Exception {
        portalStub.expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");
        stubRedisPing("PONG");

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("操作成功"))
                .andExpect(jsonPath("$.data.status").value("UP"));

        portalStub.server().verify();

        assertThat(portalStub.recorded()).as("门户探针必须且只发一次请求").hasSize(1);
        Recorded request = portalStub.recorded().get(0);
        assertThat(request.method()).isEqualTo(HttpMethod.GET);
        assertThat(request.uri().getPath()).isEqualTo("/product/search");
        assertThat(request.uri().getRawQuery()).isEqualTo(READINESS_QUERY);
        assertThat(request.headers().containsKey(HttpHeaders.AUTHORIZATION))
                .as("固定匿名探针不得携带 Authorization")
                .isFalse();
    }

    @Test
    @DisplayName("存活探针只反映进程存活，不受依赖健康影响且保持 200")
    void liveStaysUpRegardlessOfDependencies() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    // ------------------------------------------------------------------ //
    // 依赖失败 → 就绪 503
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("门户业务 envelope code 非 200 时 ready 返回 503 DOWN")
    void readyIsDownWhenPortalEnvelopeCodeIsNotOk() throws Exception {
        portalStub.expect(200, "{\"code\":500,\"message\":\"落库失败\",\"data\":{\"total\":7}}");
        stubRedisPing("PONG");

        expectNotReady();
    }

    @Test
    @DisplayName("门户 total 为负数时 ready 返回 503 DOWN")
    void readyIsDownWhenPortalTotalIsNegative() throws Exception {
        portalStub.expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":-1}}");
        stubRedisPing("PONG");

        expectNotReady();
    }

    @Test
    @DisplayName("门户 HTTP 非 200 时 ready 返回 503 DOWN")
    void readyIsDownWhenPortalHttpStatusIsNotOk() throws Exception {
        portalStub.expect(503, "{\"code\":503,\"message\":\"门户不可用\"}");
        stubRedisPing("PONG");

        expectNotReady();
    }

    @Test
    @DisplayName("Redis PING 非 PONG 时 ready 返回 503 DOWN")
    void readyIsDownWhenRedisPingIsUnexpected() throws Exception {
        portalStub.expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");
        stubRedisPing("LOADING");

        expectNotReady();
    }

    @Test
    @DisplayName("Redis 连接获取失败时 ready 返回 503 DOWN")
    void readyIsDownWhenRedisConnectionFails() throws Exception {
        portalStub.expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");
        Mockito.when(redisConnectionFactory.getConnection())
                .thenThrow(new IllegalStateException("Unable to connect to redis://fail:" + LEAK_CREDENTIAL_MARKER));

        expectNotReady();
    }

    // ------------------------------------------------------------------ //
    // 脱敏
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("依赖失败响应不包含依赖地址、上游正文或凭据")
    void readyFailureResponseNeverLeaksDependencyDetails() throws Exception {
        portalStub.expect(500, "{\"code\":500,\"message\":\"boom " + LEAK_HOST_MARKER
                + " " + LEAK_BODY_MARKER + " " + LEAK_CREDENTIAL_MARKER + "\"}");
        Mockito.when(redisConnectionFactory.getConnection())
                .thenThrow(new IllegalStateException("redis " + LEAK_CREDENTIAL_MARKER));

        MvcResult result = mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain(LEAK_HOST_MARKER)
                .doesNotContain(LEAK_BODY_MARKER)
                .doesNotContain(LEAK_CREDENTIAL_MARKER)
                .doesNotContain("http://")
                .doesNotContain("Exception");
    }

    // 「上下文装配期不触碰 Redis 连接工厂」由独立上下文用例
    // RedisStartupIsolationIntegrationTest 断言：本类每个用例都会 Mockito.reset 连接工厂，
    // 会把启动期的调用记录一并抹掉，在这里断言属于恒真空断言，故不再保留。

    // ------------------------------------------------------------------ //
    // 辅助
    // ------------------------------------------------------------------ //

    private void expectNotReady() throws Exception {
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.status").value("DOWN"));
    }

    private void stubRedisPing(String pong) {
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        Mockito.when(redisConnectionFactory.getConnection()).thenReturn(connection);
        Mockito.when(connection.ping()).thenReturn(pong);
    }

    /** 捕获到的门户请求快照；只保留 method/URI/headers，便于断言且不持有请求流。 */
    record Recorded(HttpMethod method, URI uri, HttpHeaders headers) {
    }

    /**
     * 用真实 {@link MallPortalClient}（绑定 {@link MockRestServiceServer}）替换生产门户 Bean：
     * 仍然走真实客户端的固定 URI 构造与 envelope 解析，只在 HTTP 传输层被截获。
     */
    @TestConfiguration
    static class PortalStub {

        private final RestClient.Builder builder = RestClient.builder();
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        private final List<Recorded> recorded = new ArrayList<>();

        @Bean
        @Primary
        MallPortalClient readinessStubMallPortalClient(AgentProperties properties) {
            return new MallPortalClient(builder, properties.getPortalBaseUrl());
        }

        MockRestServiceServer server() {
            return server;
        }

        List<Recorded> recorded() {
            return recorded;
        }

        void expect(int status, String body) {
            server.expect(request -> {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(request.getHeaders());
                recorded.add(new Recorded(request.getMethod(), request.getURI(), headers));
            }).andRespond(withStatus(HttpStatus.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON));
        }
    }

    /**
     * test profile 排除了 Redis 自动配置，因此这里用 Mockito 替身补齐
     * {@link RedisConnectionFactory} 与 {@link StringRedisTemplate} 的装配。
     *
     * <p>注意：{@code MallAgentApplicationTest} 同样<strong>不</strong>提供连接工厂，但它刻意
     * 不请求 {@code /health/ready}，因此并未验证「无工厂 → ready 失败关闭」。该失败关闭语义由
     * {@link HealthReadinessRedisAbsentIntegrationTest} 在真实上下文下断言。
     */
    @TestConfiguration
    static class RedisSubstitute {

        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return Mockito.mock(RedisConnectionFactory.class);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }
}
