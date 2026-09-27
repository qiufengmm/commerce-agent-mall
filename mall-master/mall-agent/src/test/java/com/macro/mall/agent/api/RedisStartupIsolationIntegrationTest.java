package com.macro.mall.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.macro.mall.agent.MallAgentApplication;
import com.macro.mall.agent.config.AgentProperties;

/**
 * 「装配期不触碰 Redis」的<strong>独立上下文</strong>断言。
 *
 * <p>{@link HealthReadinessIntegrationTest} 里的同名用例不可靠：那里每个用例开始都会
 * {@code Mockito.reset(redisConnectionFactory)} 清空调用记录（否则探针替身桩会在用例间泄漏），
 * 于是上下文启动期间可能发生的 {@code getConnection} 早已被抹掉，{@code verifyNoInteractions}
 * 变成恒真的空断言。
 *
 * <p>因此本类刻意使用<strong>另一个</strong> {@code @Import} 配置，让 Spring 建立并缓存一个与
 * {@link HealthReadinessIntegrationTest} 不同的上下文；本类<strong>不含任何 {@code Mockito.reset}</strong>，
 * 连接工厂替身的调用记录从上下文启动起持续累计。测试体在上下文完全启动后执行，因此
 * {@code verifyNoInteractions} 覆盖的正是装配阶段（以及随后的 {@code /health/live} 请求）。
 *
 * <p>隔离边界：test profile 排除 Redis 自动配置，连接工厂与 {@link StringRedisTemplate} 都是
 * Mockito 替身；本类只访问 {@code /health/live}（不触碰就绪探针），不建立任何真实网络或 Redis 连接。
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
            "MALL_AGENT_PORTAL_BASE_URL=http://127.0.0.1:8085",
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
@Import(RedisStartupIsolationIntegrationTest.StartupRedisSubstitute.class)
class RedisStartupIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Test
    @DisplayName("上下文装配期不获取 Redis 连接，存活探针也不触碰连接工厂")
    void startupAndLiveProbeNeverAcquireRedisConnection() throws Exception {
        // 此时上下文已完全启动；本类不 reset，故此处断言覆盖整个装配阶段。
        mockMvc.perform(get("/health/live")).andExpect(status().isOk());

        verify(redisConnectionFactory, never()).getConnection();
        verifyNoInteractions(redisConnectionFactory);
    }

    @Test
    @DisplayName("本类 @SpringBootTest 钉住的环境键覆盖 AgentProperties.ENVIRONMENT_VARIABLES")
    void pinnedEnvironmentKeysCoverEveryProductionVariable() {
        String[] pinned = RedisStartupIsolationIntegrationTest.class
                .getAnnotation(SpringBootTest.class)
                .properties();
        Set<String> pinnedKeys = Arrays.stream(pinned)
                .map(property -> property.substring(0, property.indexOf('=')))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        assertThat(pinnedKeys)
                .as("宿主隔离必须钉住全部生产变量，否则同名的宿主 MALL_AGENT_* 会渗入上下文使隔离结论失真")
                .containsAll(AgentProperties.ENVIRONMENT_VARIABLES);
    }

    /**
     * 启动期隔离用的 Redis 替身：与 {@code MallAgentApplicationTest.RedisSubstitute} 一样补齐
     * {@link StringRedisTemplate}，并额外提供一个受监控的 {@link RedisConnectionFactory} 替身，
     * 供用例证明装配期从未索取连接。
     *
     * <p>本类<strong>不</strong>在 {@code @BeforeEach} 里 reset 该替身，调用记录因此保留启动事实。
     */
    @TestConfiguration
    static class StartupRedisSubstitute {

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
