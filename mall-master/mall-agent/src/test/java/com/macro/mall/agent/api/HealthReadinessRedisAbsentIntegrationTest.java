package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import com.macro.mall.agent.MallAgentApplication;
import com.macro.mall.agent.config.AgentProperties;
import com.macro.mall.agent.storefront.MallPortalClient;

/**
 * 「缺少 {@link RedisConnectionFactory} 时就绪失败关闭」的<strong>真实上下文</strong>测试。
 *
 * <p>被测分支是 {@code AgentHealthConfiguration#redisHealthProbe(ObjectProvider)}：当容器里没有
 * Redis 连接工厂时，它返回固定的 {@code () -> false}，从而让 {@code /health/ready} 失败关闭为
 * 503 DOWN；与之相对，{@code /health/live} 只反映进程存活，必须保持 200 UP。
 *
 * <p>关键点：本测试<strong>不</strong>提供 {@link RedisConnectionFactory}（test profile 排除了
 * Redis 自动配置，见 {@code application-test.yml}），门户探针则由真实 {@link MallPortalClient}
 * 绑定 {@link MockRestServiceServer} 提供一次健康响应。于是唯一不健康的依赖就是「缺失的 Redis」，
 * 断言 503 DOWN 恰好验证失败关闭语义，而不是被门户探针失败掩盖。
 *
 * <p>与本类不同，{@code HealthReadinessIntegrationTest} 会替身补齐连接工厂，{@code
 * MallAgentApplicationTest} 与 {@code RedisStartupIsolationIntegrationTest} 虽同样缺工厂，却
 * 从不请求 {@code /health/ready}。因此「缺工厂 → ready 失败关闭」此前没有专属断言，本类用来补齐。
 *
 * <p>隔离边界：模型模式钉死为 stub，门户 HTTP 由 {@link MockRestServiceServer} 在建连前截获，
 * 不建立任何真实网络连接、不读写任何 Redis；响应断言只涉及固定的状态码与文案，不含依赖地址或凭据。
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
@Import({
    HealthReadinessRedisAbsentIntegrationTest.PortalStub.class,
    HealthReadinessRedisAbsentIntegrationTest.RedisTemplateSubstitute.class
})
class HealthReadinessRedisAbsentIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PortalStub portalStub;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void resetStubs() {
        portalStub.server().reset();
        portalStub.recorded().clear();
    }

    @Test
    @DisplayName("缺少 RedisConnectionFactory 时 /health/ready 失败关闭为 503 DOWN，门户探针健康")
    void readyFailsClosedWhenRedisConnectionFactoryIsAbsent() throws Exception {
        assertThat(context.getBeanNamesForType(RedisConnectionFactory.class))
                .as("本上下文必须确实没有 RedisConnectionFactory，才走无工厂回退分支")
                .isEmpty();

        // 门户探针健康：明确唯一失败来源是缺失的 Redis，而非门户。
        portalStub.expect(200, "{\"code\":200,\"message\":\"操作成功\",\"data\":{\"total\":0}}");

        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.data.status").value("DOWN"));

        portalStub.server().verify();
        assertThat(portalStub.recorded())
                .as("门户探针必须被调用一次且健康，证明 503 来自缺失的 Redis 工厂")
                .hasSize(1);
    }

    @Test
    @DisplayName("缺少 RedisConnectionFactory 时 /health/live 仍保持 200 UP")
    void liveStaysUpWhenRedisConnectionFactoryIsAbsent() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"));
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

    /** 捕获到的门户请求快照；只保留 method/URI/headers，便于断言且不持有请求流。 */
    record Recorded(HttpMethod method, URI uri, HttpHeaders headers) {
    }

    /**
     * test profile 排除了 Redis 自动配置，因此这里只补齐装配所需的
     * {@link StringRedisTemplate} 替身；<strong>刻意不提供</strong> {@link RedisConnectionFactory}，
     * 以便让 {@code AgentHealthConfiguration} 走「依赖缺失 → 失败关闭」分支。
     */
    @TestConfiguration
    static class RedisTemplateSubstitute {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }
}
