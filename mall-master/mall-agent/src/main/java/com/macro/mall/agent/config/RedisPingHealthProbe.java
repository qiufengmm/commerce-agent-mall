package com.macro.mall.agent.config;

import java.util.Objects;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.macro.mall.agent.api.HealthProbe;

/**
 * Redis 就绪探针：通过只读 {@code PING} 判断 Redis 是否可用。
 *
 * <p>不变量：
 * <ul>
 *   <li><strong>惰性</strong>：构造时不获取连接，只有 {@link #isHealthy()} 被调用（即
 *       {@code /health/ready} 请求时）才向 {@link RedisConnectionFactory} 获取连接；</li>
 *   <li><strong>只读</strong>：只执行 {@code PING}，不写任何键、不执行任何数据变更命令；</li>
 *   <li>仅当 {@code PING} 返回 {@code PONG} 时返回 {@code true}；</li>
 *   <li>连接获取或 {@code PING} 抛出的任何运行时异常都<strong>失败关闭</strong>为 {@code false}；</li>
 *   <li><strong>安全关闭</strong>：无论成功失败都关闭连接，关闭失败不改变探针结果；</li>
 *   <li>不记录异常正文、Redis 地址或任何凭据——本类不写日志。</li>
 * </ul>
 */
public final class RedisPingHealthProbe implements HealthProbe {

    private static final String EXPECTED_PING = "PONG";

    private final RedisConnectionFactory connectionFactory;

    public RedisPingHealthProbe(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory 不能为空");
    }

    @Override
    public boolean isHealthy() {
        RedisConnection connection = null;
        try {
            connection = connectionFactory.getConnection();
            if (connection == null) {
                return false;
            }
            return EXPECTED_PING.equals(connection.ping());
        } catch (RuntimeException ex) {
            // 失败关闭：连接/命令故障一律视为未就绪；不记录异常正文、地址或凭据
            return false;
        } finally {
            closeQuietly(connection);
        }
    }

    private static void closeQuietly(RedisConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // 关闭失败不应把探针结果异常化，也不改变已得到的健康判定
        }
    }
}
