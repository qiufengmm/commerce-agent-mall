package com.macro.mall.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * {@link RedisPingHealthProbe} 的 TDD 契约测试。
 *
 * <p>只模拟 Redis 边界（{@link RedisConnectionFactory} 与 {@link RedisConnection} 为 Mockito 替身），
 * 不连接真实 Redis、不执行任何写命令。
 *
 * <p>探针不变量：
 * <ul>
 *   <li>仅在 {@code isHealthy()} 被调用时才惰性获取连接；</li>
 *   <li>只执行只读 {@code PING}，且返回 {@code PONG} 才算健康；</li>
 *   <li>任何连接/命令异常都失败关闭为 false；</li>
 *   <li>无论成功失败都安全关闭连接，关闭异常不改变探针结果；</li>
 *   <li>不记录异常正文、地址或凭据。</li>
 * </ul>
 */
class RedisPingHealthProbeTest {

    private RedisConnectionFactory connectionFactory;
    private RedisConnection connection;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(RedisConnectionFactory.class);
        connection = mock(RedisConnection.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
    }

    @Test
    @DisplayName("PING 返回 PONG 时探针健康，且只执行只读 PING 再关闭连接")
    void healthyWhenPingReturnsPong() {
        when(connection.ping()).thenReturn("PONG");

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isTrue();

        verify(connectionFactory).getConnection();
        verify(connection).ping();
        verify(connection).close();
        // 除只读 PING 与关闭外不得有任何其它 Redis 命令（尤其不得出现 set/del 等写命令）
        verifyNoMoreInteractions(connection);
        verifyNoMoreInteractions(connectionFactory);
    }

    @Test
    @DisplayName("PING 返回非 PONG 时探针不健康")
    void notReadyWhenPingReturnsUnexpected() {
        when(connection.ping()).thenReturn("LOADING");

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("PING 返回 null 时探针不健康")
    void notReadyWhenPingReturnsNull() {
        when(connection.ping()).thenReturn(null);

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isFalse();
    }

    @Test
    @DisplayName("获取连接抛异常时探针失败关闭")
    void notReadyWhenConnectionFactoryThrows() {
        when(connectionFactory.getConnection())
                .thenThrow(new IllegalStateException("Unable to connect to redis://user:secret@localhost:6379/0"));

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isFalse();
        verify(connection, times(0)).close();
    }

    @Test
    @DisplayName("PING 抛异常时探针失败关闭且连接仍被关闭")
    void notReadyWhenPingThrows() {
        when(connection.ping()).thenThrow(new RuntimeException("redis command failed"));

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isFalse();
        verify(connection).close();
    }

    @Test
    @DisplayName("探针成功与失败都安全关闭连接")
    void closesConnectionOnBothOutcomes() {
        when(connection.ping()).thenReturn("PONG");
        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isTrue();
        verify(connection).close();

        RedisConnection failing = mock(RedisConnection.class);
        Mockito.reset(connectionFactory);
        when(connectionFactory.getConnection()).thenReturn(failing);
        when(failing.ping()).thenReturn("PANG");
        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isFalse();
        verify(failing).close();
    }

    @Test
    @DisplayName("连接直到首次探针执行时才被惰性获取")
    void doesNotAcquireConnectionBeforeFirstProbe() {
        RedisPingHealthProbe probe = new RedisPingHealthProbe(connectionFactory);
        verifyNoInteractions(connectionFactory);

        when(connection.ping()).thenReturn("PONG");
        assertThat(probe.isHealthy()).isTrue();

        verify(connectionFactory, times(1)).getConnection();
    }

    @Test
    @DisplayName("关闭异常不改变已成功的探针结果")
    void closeFailureDoesNotFlipResult() {
        when(connection.ping()).thenReturn("PONG");
        Mockito.doThrow(new RuntimeException("close failed")).when(connection).close();

        assertThat(new RedisPingHealthProbe(connectionFactory).isHealthy()).isTrue();
    }

    @Test
    @DisplayName("连接工厂为 null 时拒绝构造")
    void rejectsNullFactory() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new RedisPingHealthProbe(null)))
                .isNotNull();
    }
}
