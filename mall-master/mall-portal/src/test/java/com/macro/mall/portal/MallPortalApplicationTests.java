package com.macro.mall.portal;

import com.macro.mall.portal.repository.MemberBrandAttentionRepository;
import com.macro.mall.portal.repository.MemberProductCollectionRepository;
import com.macro.mall.portal.repository.MemberReadHistoryRepository;
import com.macro.mall.portal.service.UmsMemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门户服务完整 Spring 上下文测试（已隔离外部中间件）。
 * <p>
 * 隔离手段（全部位于测试侧，生产配置与业务逻辑未修改）：
 * 1. 使用 test 专用配置 src/test/resources/application-test.yml：数据源显式指向 H2 内存库；
 * 2. 排除 MongoDB 自动配置，MongoDB 仓储用 @MockBean 替换，避免 MongoClient 监控线程连接 localhost:27017；
 * 3. 关闭 RabbitAdmin 声明（spring.rabbitmq.dynamic=false）与监听器自动启动，避免连接 localhost:5672；
 * 4. 排除 Redis 自动配置，RedisConnectionFactory 用 @MockBean 提供替身，避免 Lettuce 连接 localhost:6379；
 * 5. 门户不直接持有 Elasticsearch 客户端，MallSearchClient 只在被调用时才发起 HTTP 请求。
 * <p>
 * 本类同时用断言固化上述边界，避免以后被改回连接外部服务。
 */
@ActiveProfiles("test")
@SpringBootTest
public class MallPortalApplicationTests {

    @MockBean
    private MemberReadHistoryRepository memberReadHistoryRepository;

    @MockBean
    private MemberProductCollectionRepository memberProductCollectionRepository;

    @MockBean
    private MemberBrandAttentionRepository memberBrandAttentionRepository;

    /**
     * Redis 连接工厂测试替身：上下文仍需 RedisTemplate/RedisCacheManager 完成装配（mall-security 的 RedisConfig
     * 继承 BaseRedisConfig），但不使用真实 Lettuce/Jedis 客户端，因此不会连接 localhost:6379。
     */
    @MockBean
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("完整上下文可加载，门户核心服务 bean 存在")
    public void contextLoads() {
        assertNotNull(applicationContext.getBean(UmsMemberService.class));
    }

    @Test
    @DisplayName("上下文使用 H2 内存库，不连接开发 MySQL")
    public void dataSourceIsInMemoryH2() throws Exception {
        DataSource dataSource = applicationContext.getBean(DataSource.class);
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            assertNotNull(url);
            assertTrue(url.startsWith("jdbc:h2:mem:"), "测试数据源必须是 H2 内存库，实际为:" + url);
        }
    }

    @Test
    @DisplayName("不创建 MongoDB 客户端 bean，不会连接 localhost:27017")
    public void mongoClientBeansAreAbsent() {
        assertEquals(0, applicationContext.getBeanNamesForType(com.mongodb.client.MongoClient.class).length,
                "测试中不应创建 MongoClient，否则启动时会连接 MongoDB");
        assertEquals(0, applicationContext.getBeanNamesForType(MongoTemplate.class).length,
                "测试中不应创建 MongoTemplate");
        assertEquals(0, applicationContext.getBeanNamesForType(MongoDatabaseFactory.class).length,
                "测试中不应创建 MongoDatabaseFactory");
    }

    @Test
    @DisplayName("不创建真实 Redis 客户端，不会连接 localhost:6379")
    public void redisConnectionFactoryIsTestDouble() {
        Map<String, RedisConnectionFactory> factories =
                applicationContext.getBeansOfType(RedisConnectionFactory.class);
        // 上下文仍需要 Redis 相关数据访问 bean 装配成功，因此这里必须是 Mockito 替身而非 Lettuce/Jedis 客户端
        assertFalse(factories.isEmpty(), "应提供 RedisConnectionFactory 测试替身，否则 RedisTemplate 无法装配");
        factories.forEach((name, factory) -> {
            assertTrue(Mockito.mockingDetails(factory).isMock(),
                    "RedisConnectionFactory 必须是测试替身:" + name + " -> " + factory.getClass().getName());
            String className = factory.getClass().getName().toLowerCase(Locale.ROOT);
            assertFalse(className.contains("lettuce") || className.contains("jedis"),
                    "测试上下文不应存在真实 Redis 客户端:" + name + " -> " + factory.getClass().getName());
        });
        RedisTemplate<?, ?> redisTemplate = applicationContext.getBean(RedisTemplate.class);
        assertTrue(Mockito.mockingDetails(redisTemplate.getConnectionFactory()).isMock(),
                "RedisTemplate 必须绑定测试替身连接工厂，避免读写 Redis 时连接 localhost:6379");
    }

    @Test
    @DisplayName("RabbitMQ 监听器容器未启动且不声明队列，不会连接 localhost:5672")
    public void rabbitMqIsNotStarted() {
        assertEquals(0, applicationContext.getBeanNamesForType(AmqpAdmin.class).length,
                "测试中不应创建 RabbitAdmin，否则启动时会声明队列并连接 RabbitMQ");
        RabbitListenerEndpointRegistry registry = applicationContext.getBean(RabbitListenerEndpointRegistry.class);
        assertNotNull(registry);
        assertFalse(registry.getListenerContainers().isEmpty(),
                "应注册 @RabbitListener 监听器容器，否则本断言失去意义");
        registry.getListenerContainers().forEach(container ->
                assertFalse(container.isRunning(), "监听器容器不应在测试中启动:" + container));
    }

}
