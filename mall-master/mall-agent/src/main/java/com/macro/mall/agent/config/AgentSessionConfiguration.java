package com.macro.mall.agent.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.RedisRateCounter;
import com.macro.mall.agent.session.RedisRateLimiter;
import com.macro.mall.agent.session.RedisSessionRepository;
import com.macro.mall.agent.session.SessionRepository;

/**
 * 会话、限流与并发防重的 Bean 装配：产出 {@link SessionRepository}、{@link RateLimiter} 与
 * {@link InFlightGuard} 三个 Bean，供运行时业务装配直接注入。
 *
 * <p>刻意与 {@link AgentConfiguration} 分开：{@code AgentConfiguration} 会被
 * {@code @WebMvcTest} 切片显式 {@code @Import}，而这里需要 {@code StringRedisTemplate}。
 * 本类只构造包装对象，<strong>不建立任何 Redis 连接</strong>：Lettuce 连接在第一次执行命令时
 * 才建立，因此应用启动不会因为 Redis 不可达而失败或主动联网。
 *
 * <p>阈值全部来自 {@link AgentProperties}（默认 TTL 86400 秒、最近 20 条消息、
 * 会话 300 秒 20 次、IP 300 秒 60 次）。身份解析（{@code IdentityResolver}）、mall-portal 客户端
 * 与聊天服务（{@code AgentChatService}）不在本类装配，由 {@link AgentRuntimeConfiguration} 负责。
 */
@Configuration
public class AgentSessionConfiguration {

    @Bean
    public InFlightGuard inFlightGuard() {
        return new InFlightGuard();
    }

    @Bean
    public SessionRepository sessionRepository(StringRedisTemplate template, AgentProperties properties) {
        return new RedisSessionRepository(
                template, properties.getSessionTtlSeconds(), properties.getSessionMaxMessages());
    }

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate template, AgentProperties properties) {
        return new RedisRateLimiter(
                new RedisRateCounter(template),
                new RateLimiter.Rule(
                        properties.getRateLimitSessionLimit(), properties.getRateLimitSessionWindowSeconds()),
                new RateLimiter.Rule(
                        properties.getRateLimitIpLimit(), properties.getRateLimitIpWindowSeconds()),
                Clock.systemUTC());
    }
}
