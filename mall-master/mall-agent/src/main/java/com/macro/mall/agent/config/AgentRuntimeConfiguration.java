package com.macro.mall.agent.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.macro.mall.agent.agent.AgentLimits;
import com.macro.mall.agent.agent.AgentOrchestrator;
import com.macro.mall.agent.api.AgentChatService;
import com.macro.mall.agent.api.DefaultAgentChatService;
import com.macro.mall.agent.deadline.DeadlineClientHttpRequestFactory;
import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.model.OpenAiCompatibleClient;
import com.macro.mall.agent.model.StubModelClient;
import com.macro.mall.agent.session.IdentityResolver;
import com.macro.mall.agent.session.InFlightGuard;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.tools.ToolRegistry;

/**
 * 运行时业务 Bean 装配：把模型客户端、mall-portal 只读客户端、工具注册表、编排器、身份解析与
 * 聊天服务接入 Spring 容器。
 *
 * <p>与 {@link AgentConfiguration}（纯配置）和 {@link AgentSessionConfiguration}（会话/限流/防重）
 * 分开：本类只依赖它们产出的 Bean，不重复构造。
 *
 * <p><strong>启动阶段不建立任何外部连接</strong>：{@link RestClient} 与
 * {@code StringRedisTemplate}（会话/限流 Bean）都是惰性的，只有真正发起请求或执行 Redis 命令时
 * 才建立连接；模型模式为 {@code stub} 时使用确定性的 {@link StubModelClient}，不读取任何凭据。
 */
@Configuration
public class AgentRuntimeConfiguration {

    /** mall-portal 只读客户端；连接/读取超时取自 {@link AgentProperties#getPortalTimeoutSeconds()}。 */
    @Bean
    public MallPortalClient mallPortalClient(AgentProperties properties) {
        return new MallPortalClient(
                deadlineRestClientBuilder(properties.getPortalTimeoutSeconds()),
                properties.getPortalBaseUrl());
    }

    /**
     * 模型客户端：{@code stub} 模式返回确定性 {@link StubModelClient}（离线评测与本地演示），
     * 其余返回 OpenAI 兼容客户端。凭据缺失时不会建立连接，调用时由编排层/异常层映射为 503。
     */
    @Bean
    public ModelClient modelClient(AgentProperties properties) {
        if (AgentProperties.MODEL_MODE_STUB.equals(properties.getModelMode())) {
            return new StubModelClient();
        }
        return new OpenAiCompatibleClient(
                deadlineRestClientBuilder(properties.getOpenaiTimeoutSeconds()),
                properties.getOpenaiBaseUrl(),
                properties.getOpenaiApiKey(),
                properties.getOpenaiModel());
    }

    /** 只读工具注册表：固定注册四个工具，无任意 URL/方法入口。 */
    @Bean
    public ToolRegistry toolRegistry() {
        return ToolRegistry.defaultRegistry();
    }

    /** 受限工具循环编排器，轮数/上下文/卡片边界来自 {@link AgentProperties}。 */
    @Bean
    public AgentOrchestrator agentOrchestrator(
            ModelClient modelClient,
            ToolRegistry toolRegistry,
            MallPortalClient mallPortalClient,
            AgentProperties properties) {
        return new AgentOrchestrator(
                modelClient, toolRegistry, mallPortalClient, AgentLimits.from(properties));
    }

    /** 身份解析：会员身份只由 mall-portal {@code /sso/info} 服务端解析。 */
    @Bean
    public IdentityResolver identityResolver(
            MallPortalClient mallPortalClient, SessionRepository sessionRepository) {
        return new IdentityResolver(mallPortalClient, sessionRepository);
    }

    /** 聊天业务服务：控制器唯一业务入口。 */
    @Bean
    public AgentChatService agentChatService(
            AgentProperties properties,
            IdentityResolver identityResolver,
            RateLimiter rateLimiter,
            InFlightGuard inFlightGuard,
            SessionRepository sessionRepository,
            AgentOrchestrator agentOrchestrator) {
        return new DefaultAgentChatService(
                properties, identityResolver, rateLimiter, inFlightGuard, sessionRepository, agentOrchestrator);
    }

    /**
     * 构造带连接/读取超时的 {@link RestClient.Builder}；只创建对象，不发起请求。
     *
     * <p>使用共享的不可变 JDK {@link HttpClient} + {@link DeadlineClientHttpRequestFactory}：
     * 没有请求级 deadline 时按 {@code timeoutSeconds} 施加单次请求的完整超时；处于 deadline 作用域内时，
     * 每次请求的完整超时取 {@code min(timeoutSeconds, 剩余预算)}，剩余预算耗尽则在网络调用前失败。
     *
     * <p>协议固定为 HTTP/1.1，并<strong>禁止自动跟随重定向</strong>（{@link HttpClient.Redirect#NEVER}），
     * 与被替换的 Python {@code httpx} 一致：{@code httpx} 默认 {@code follow_redirects=False}。
     * 跟随 3xx 会把携带 {@code Authorization}/{@code Bearer} 的请求转发到非配置主机，因此模型与门户
     * 客户端都不跟随；3xx 响应由各自的响应解析逻辑按既有固定安全错误处理。
     */
    private static RestClient.Builder deadlineRestClientBuilder(double timeoutSeconds) {
        Duration configuredTimeout = Duration.ofMillis(Math.max(1L, Math.round(timeoutSeconds * 1000.0)));
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(configuredTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return RestClient.builder()
                .requestFactory(new DeadlineClientHttpRequestFactory(httpClient, configuredTimeout));
    }
}
