package com.macro.mall.agent.config;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.macro.mall.agent.api.HealthProbe;
import com.macro.mall.agent.api.ReadinessService;
import com.macro.mall.agent.storefront.MallPortalClient;
import com.macro.mall.agent.storefront.PortalSearchHealthProbe;

/**
 * 就绪探针装配。
 *
 * <p>两个探针均为真实依赖检查，且<strong>只在 {@code /health/ready} 请求时</strong>才访问依赖：
 * <ul>
 *   <li>{@code portalHealthProbe}：复用 {@link MallPortalClient} 已配置超时的 {@code RestClient}，
 *       发送固定匿名只读请求 {@code GET /product/search?pageNum=1&pageSize=1}，成功后校验
 *       HTTP 200、业务 {@code code=200} 与 {@code data.total} 为非负整数；</li>
 *   <li>{@code redisHealthProbe}：通过 {@link RedisPingHealthProbe} 惰性获取连接并执行只读
 *       {@code PING}，期望 {@code PONG} 后安全关闭。</li>
 * </ul>
 *
 * <p>两者都<strong>不在启动阶段建立任何连接</strong>：本类只构造包装对象。任一探针失败或抛异常时
 * {@link ReadinessService} 返回未就绪，{@code /health/ready} 响应 503，且不含依赖地址、异常正文或凭据。
 *
 * <p>当容器中没有 {@link RedisConnectionFactory}（例如隔离测试 profile 排除了 Redis 自动配置）时，
 * Redis 探针<strong>失败关闭</strong>为固定返回 false，上下文仍可启动；这保证「依赖缺失即未就绪」，
 * 不会假报健康。
 */
@Configuration
public class AgentHealthConfiguration {

    /** mall-portal 只读探针：固定匿名请求，复用门户客户端已配置的超时。 */
    @Bean
    public HealthProbe portalHealthProbe(MallPortalClient mallPortalClient) {
        // 用包装器对外暴露探针，避免给 MallPortalClient 增加第六个公开业务方法
        return new PortalSearchHealthProbe(mallPortalClient);
    }

    /** Redis 探针：惰性连接 + 只读 PING；缺少连接工厂时失败关闭。 */
    @Bean
    public HealthProbe redisHealthProbe(ObjectProvider<RedisConnectionFactory> connectionFactories) {
        RedisConnectionFactory connectionFactory = connectionFactories.getIfAvailable();
        if (connectionFactory == null) {
            // 依赖缺失即未就绪：不建立任何连接，也不假报健康
            return () -> false;
        }
        return new RedisPingHealthProbe(connectionFactory);
    }

    @Bean
    public ReadinessService readinessService(Map<String, HealthProbe> probes) {
        return new ReadinessService(probes);
    }
}
