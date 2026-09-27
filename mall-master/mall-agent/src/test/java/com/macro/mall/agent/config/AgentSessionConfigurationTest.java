package com.macro.mall.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import com.macro.mall.agent.api.SessionMessage;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimitExceededException;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.RedisRateLimiter;
import com.macro.mall.agent.session.RedisSessionRepository;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.session.SessionSnapshot;

/**
 * {@link AgentSessionConfiguration} 的装配契约测试（计划 Task 6 装配步骤）。
 *
 * <p>验证三件事：
 * <ol>
 *   <li>装配只构造 Bean，<strong>不访问 Redis</strong>（不建立连接）；</li>
 *   <li>会话仓储使用 {@code AgentProperties} 的 TTL 与消息上限；</li>
 *   <li>限流器使用配置的会话/窗口阈值与 Redis 原子计数器。</li>
 * </ol>
 *
 * <p>不启动 Spring 上下文（直接调用工厂方法），因此不需要真实 Redis，也不会连接任何默认 Redis。
 */
class AgentSessionConfigurationTest {

    private static final Map<String, String> OVERRIDES = Map.of(
            AgentProperties.SESSION_TTL_SECONDS, "120",
            AgentProperties.SESSION_MAX_MESSAGES, "4",
            AgentProperties.RATE_LIMIT_SESSION_LIMIT, "2",
            AgentProperties.RATE_LIMIT_SESSION_WINDOW_SECONDS, "300",
            AgentProperties.RATE_LIMIT_IP_LIMIT, "5",
            AgentProperties.RATE_LIMIT_IP_WINDOW_SECONDS, "300");

    private static final String SESSION_KEY = "mall:agent:session:guest:2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";

    private final AgentSessionConfiguration configuration = new AgentSessionConfiguration();

    @Test
    @DisplayName("装配只构造 Bean，不访问 Redis")
    void wiringDoesNotTouchRedis() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);

        configuration.inFlightGuard();
        configuration.sessionRepository(template, properties());
        configuration.rateLimiter(template, properties());

        verifyNoInteractions(template);
    }

    @Test
    @DisplayName("会话仓储 Bean 使用配置的 TTL")
    void sessionRepositoryUsesConfiguredTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(template.opsForValue()).willReturn(values);

        SessionRepository repository = configuration.sessionRepository(template, properties());

        assertThat(repository).isInstanceOf(RedisSessionRepository.class);
        repository.save(SESSION_KEY, new SessionSnapshot(List.of(new SessionMessage("user", "你好")), List.of()));
        then(values).should().set(eq(SESSION_KEY), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    @DisplayName("会话仓储 Bean 使用配置的消息上限（裁剪到 4 条）")
    void sessionRepositoryUsesConfiguredMessageLimit() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        given(template.opsForValue()).willReturn(values);
        Map<String, String> backing = new HashMap<>();
        given(values.getAndExpire(anyString(), any(Duration.class)))
                .willAnswer(invocation -> backing.get(invocation.<String>getArgument(0)));
        org.mockito.BDDMockito.willAnswer(invocation -> {
            backing.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).given(values).set(anyString(), anyString(), any(Duration.class));

        SessionRepository repository = configuration.sessionRepository(template, properties());
        repository.save(SESSION_KEY, new SessionSnapshot(
                List.of(new SessionMessage("user", "1"), new SessionMessage("user", "2"),
                        new SessionMessage("user", "3"), new SessionMessage("user", "4"),
                        new SessionMessage("user", "5")),
                List.of()));

        assertThat(repository.load(SESSION_KEY).messages()).extracting(SessionMessage::content)
                .containsExactly("2", "3", "4", "5");
    }

    @Test
    @DisplayName("限流 Bean 使用配置阈值与 Redis 原子计数器")
    void rateLimiterUsesConfiguredRulesAndRedisCounter() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        Map<String, Long> counters = new HashMap<>();
        given(template.execute(any(RedisScript.class), anyList(), any())).willAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> keys = (List<String>) invocation.getArgument(1);
            return counters.merge(keys.get(0), 1L, Long::sum);
        });

        RateLimiter limiter = configuration.rateLimiter(template, properties());

        assertThat(limiter).isInstanceOf(RedisRateLimiter.class);
        limiter.check(SESSION_KEY, "203.0.113.10");
        limiter.check(SESSION_KEY, "203.0.113.10");
        assertThatThrownBy(() -> limiter.check(SESSION_KEY, "203.0.113.10"))
                .isInstanceOf(RateLimitExceededException.class);

        then(template).should(atLeastOnce()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("防重 Bean 是进程内实现：每次装配得到独立实例")
    void inFlightGuardIsProcessLocalPerInstance() {
        InFlightGuard guard = configuration.inFlightGuard();

        assertThat(guard.acquire("mall:agent:session:guest:x:hash")).isTrue();
        assertThat(guard.acquire("mall:agent:session:guest:x:hash")).isFalse();
        assertThat(configuration.inFlightGuard().acquire("mall:agent:session:guest:x:hash")).isTrue();
    }

    private static AgentProperties properties() {
        return AgentProperties.from(OVERRIDES::get);
    }
}
