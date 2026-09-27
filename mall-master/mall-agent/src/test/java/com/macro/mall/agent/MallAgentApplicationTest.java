package com.macro.mall.agent;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;

import com.macro.mall.agent.api.AgentChatService;
import com.macro.mall.agent.model.ModelClient;
import com.macro.mall.agent.session.RateLimiter;
import com.macro.mall.agent.session.SessionRepository;
import com.macro.mall.agent.storefront.MallPortalClient;

/**
 * 服务模块上下文隔离测试（计划 Task 11 Step 1）：真实启动 Spring 上下文，但<strong>不</strong>接入
 * 任何外部依赖。
 *
 * <p>目的不是覆盖业务逻辑，而是证明独立服务的装配边界：
 *
 * <ul>
 *   <li>真实 {@link MallAgentApplication} 能被加载并启动完整 Web 应用上下文；</li>
 *   <li>{@code server.port} 由真实 {@code application.yml} 的 {@code ${MALL_AGENT_PORT:8086}}
 *       占位符解析得到 {@code 8086}，但 MOCK Web 环境<strong>不绑定 socket</strong>
 *       （因此不会占用端口、也不会对外提供服务）；</li>
 *   <li>Bean 图中不存在 {@code DataSource}、MyBatis、MongoDB、RabbitMQ、Elasticsearch 客户端，
 *       且这些客户端根本不在模块 classpath 上；</li>
 *   <li>不存在 {@link RedisConnectionFactory}：测试 profile 显式排除 Redis 自动配置，
 *       因此上下文启动不会创建（更不会连接）任何 Redis 连接工厂；</li>
 *   <li>模型、mall-portal 与 Redis {@link StringRedisTemplate} 全部由 Mockito 替身提供。</li>
 * </ul>
 *
 * <p>刻意<strong>不</strong>请求 {@code /health/ready}：真实就绪探针会访问 mall-portal 与 Redis，
 * 而本隔离上下文不建立任何外部连接，请求它既不能证明装配边界，反而会引入对不存在依赖的假设。
 * 就绪语义（依赖健康 → 200、任一失败 → 503、响应脱敏）由 {@code HealthReadinessIntegrationTest}
 * 在受控替身下验证，「装配期不触碰 Redis」由 {@code RedisStartupIsolationIntegrationTest} 用独立上下文验证。
 *
 * <p>Redis 的真实读写语义由 {@code RedisSessionIntegrationTest} 与
 * {@code MallAgentRedisIntegrationTest} 在显式配置 {@code MALL_AGENT_TEST_REDIS_URL} 时验证；
 * 本类在任何情况下都不建立 Redis、门户或模型连接，也不会读取或设置
 * {@code MALL_AGENT_TEST_REDIS_URL}。
 */
@SpringBootTest(
        classes = MallAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
            // 宿主隔离属性列表：与 AgentProperties.ENVIRONMENT_VARIABLES 一一对应，逐一钉死为
            // 安全的确定值。AgentProperties 是逐个精确读取这些 OS 环境变量的，若这里少钉一项，
            // 宿主进程里同名的 MALL_AGENT_* 变量就会渗入上下文，使隔离结论失真。
            //
            // 维护约束：AgentProperties 每新增一个生产环境变量，必须同步在本列表补一行。
            //
            // 这些地址只用于让上下文在「配置层面」可复现：ModelClient / MallPortalClient /
            // StringRedisTemplate 全部是 Mockito 替身，本测试不会向以下任何地址发起连接。
            //
            // 端口：只钉 MALL_AGENT_PORT，不再直接注入 server.port；这样 server.port 只能由
            // 真实 application.yml 的 ${MALL_AGENT_PORT:8086} 占位符解析得到，避免自证式断言。
            "MALL_AGENT_HOST=127.0.0.1",
            "MALL_AGENT_PORT=8086",
            "MALL_AGENT_LOG_LEVEL=INFO",
            // 通配来源已被生产配置拒绝（fail closed）；这里钉死为「不授权任何来源」。
            "MALL_AGENT_CORS_ALLOW_ORIGINS=",
            // 钉死为「不信任任何反向代理」（fail closed）；空值语义与生产默认一致，且杜绝宿主取值渗入。
            "MALL_AGENT_TRUSTED_PROXY_IP=",
            "MALL_AGENT_REQUEST_TIMEOUT_SECONDS=35",
            // 钉死模型模式：即使运行环境存在真实凭据，本测试也只用替身，不读取、不使用凭据。
            "MALL_AGENT_MODEL_MODE=stub",
            // API Key 明确置空：杜绝任何来自宿主的凭据渗入。
            "MALL_AGENT_OPENAI_API_KEY=",
            "MALL_AGENT_OPENAI_BASE_URL=http://127.0.0.1:8080/v1",
            "MALL_AGENT_OPENAI_MODEL=stub-model",
            "MALL_AGENT_OPENAI_TIMEOUT_SECONDS=30",
            "MALL_AGENT_MAX_TOOL_ROUNDS=4",
            "MALL_AGENT_PORTAL_BASE_URL=http://127.0.0.1:8085",
            "MALL_AGENT_PORTAL_TIMEOUT_SECONDS=10",
            // loopback、无凭据；Redis 自动配置被 test profile 排除，本测试不建立连接。
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
@ActiveProfiles("test")
@Import(MallAgentApplicationTest.RedisSubstitute.class)
class MallAgentApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    /** 模型客户端替身：替换 {@code AgentRuntimeConfiguration} 的真实 Bean，不读凭据、不联网。 */
    @MockitoBean
    private ModelClient modelClient;

    /** mall-portal 只读客户端替身：不发起任何 HTTP 请求。 */
    @MockitoBean
    private MallPortalClient mallPortalClient;

    @Test
    @DisplayName("真实应用类启动完整上下文，聊天服务等运行 Bean 均已装配")
    void applicationContextStartsWithRealApplicationClass() {
        assertThat(context).isNotNull();
        assertThat(context).isInstanceOf(WebApplicationContext.class);
        assertThat(context.getBean(MallAgentApplication.class)).isNotNull();
        assertThat(context.getBean(AgentChatService.class)).isNotNull();
    }

    @Test
    @DisplayName("test profile 生效，server.port 由真实 application.yml 占位符解析且不绑定 socket")
    void testProfileAppliesPinnedPortWithoutBindingSocket() {
        assertThat(environment.acceptsProfiles(Profiles.of("test"))).isTrue();
        assertThat(environment.getProperty("MALL_AGENT_MODEL_MODE")).isEqualTo("stub");

        // 端口断言不是自证式：测试只钉 MALL_AGENT_PORT，不再直接注入 server.port，
        // 因此 server.port 只能由 application.yml 的 ${MALL_AGENT_PORT:8086} 解析得到。
        Integer pinnedAgentPort = environment.getProperty("MALL_AGENT_PORT", Integer.class);
        assertThat(pinnedAgentPort).isEqualTo(8086);
        Integer serverPort = environment.getProperty("server.port", Integer.class);
        assertThat(serverPort)
                .as("server.port 必须来自 application.yml 的 ${MALL_AGENT_PORT:8086} 占位符")
                .isEqualTo(8086);
        assertThat(serverPort)
                .as("server.port 必须与注入的 MALL_AGENT_PORT 一致，证明取值链路真实连通")
                .isEqualTo(pinnedAgentPort);

        // MOCK Web 环境不会启动内嵌容器，因此不存在真实监听端口
        assertThat(environment.getProperty("local.server.port")).isNull();
    }

    @Test
    @DisplayName("Bean 图与 classpath 均不含数据库、消息队列与搜索引擎客户端")
    void noDatabaseMessageQueueOrSearchClientBeans() {
        assertThat(context.getBeanNamesForType(DataSource.class))
                .as("本服务不得接入商城数据库，不能存在 DataSource Bean")
                .isEmpty();
        assertThat(context.containsBean("dataSource")).isFalse();
        assertThat(context.containsBean("jdbcTemplate")).isFalse();

        assertThat(context.containsBean("sqlSessionFactory")).isFalse();
        assertThat(context.containsBean("mongoTemplate")).isFalse();
        assertThat(context.containsBean("rabbitTemplate")).isFalse();

        ClassLoader classLoader = context.getClassLoader();
        assertThat(ClassUtils.isPresent("org.springframework.jdbc.core.JdbcTemplate", classLoader))
                .as("模块 classpath 不应包含 Spring JDBC")
                .isFalse();
        assertThat(ClassUtils.isPresent("org.apache.ibatis.session.SqlSessionFactory", classLoader))
                .as("模块 classpath 不应包含 MyBatis")
                .isFalse();
        assertThat(ClassUtils.isPresent("com.mongodb.client.MongoClient", classLoader))
                .as("模块 classpath 不应包含 MongoDB 客户端")
                .isFalse();
        assertThat(ClassUtils.isPresent("org.springframework.amqp.rabbit.connection.ConnectionFactory", classLoader))
                .as("模块 classpath 不应包含 RabbitMQ 客户端")
                .isFalse();
        assertThat(ClassUtils.isPresent("co.elastic.clients.elasticsearch.ElasticsearchClient", classLoader))
                .as("模块 classpath 不应包含 Elasticsearch 客户端")
                .isFalse();
        assertThat(ClassUtils.isPresent("org.elasticsearch.client.RestHighLevelClient", classLoader))
                .as("模块 classpath 不应包含 Elasticsearch 高层客户端")
                .isFalse();
    }

    @Test
    @DisplayName("隔离上下文不创建 RedisConnectionFactory，因此不会连接任何 Redis")
    void redisConnectionFactoryIsAbsent() {
        assertThat(context.getBeanNamesForType(RedisConnectionFactory.class))
                .as("测试 profile 必须排除 Redis 自动配置，不能出现 RedisConnectionFactory")
                .isEmpty();
        assertThat(context.containsBean("redisConnectionFactory")).isFalse();
        assertThat(context.containsBean("lettuceConnectionFactory")).isFalse();
        assertThat(context.containsBean("redisTemplate")).isFalse();
    }

    @Test
    @DisplayName("模型、门户与 Redis 依赖均为 Mockito 替身，会话与限流 Bean 用替身完成装配")
    void externalCollaboratorsAreMockitoSubstitutes() {
        assertThat(Mockito.mockingDetails(context.getBean(ModelClient.class)).isMock()).isTrue();
        assertThat(Mockito.mockingDetails(context.getBean(MallPortalClient.class)).isMock()).isTrue();
        assertThat(Mockito.mockingDetails(context.getBean(StringRedisTemplate.class)).isMock()).isTrue();

        // 会话仓储与限流器是真实实现，但只持有替身模板：构造阶段不建立连接
        assertThat(context.getBean(SessionRepository.class)).isNotNull();
        assertThat(context.getBean(RateLimiter.class)).isNotNull();
    }

    /**
     * 隔离 profile 的 Redis 替身。
     *
     * <p>测试 profile 排除了 {@code RedisAutoConfiguration}，因此容器不会自行提供
     * {@link StringRedisTemplate}；这里用 Mockito 替身补齐装配，使
     * {@code AgentSessionConfiguration} 的会话仓储与限流器无需真实 Redis 即可构建。
     *
     * <p>Bean 名与自动配置一致（{@code stringRedisTemplate}），因此即使在尚未排除 Redis 自动配置的
     * 环境下，自动配置的同类 Bean 也会因 {@code @ConditionalOnMissingBean} 让位，不会出现重复定义。
     */
    @TestConfiguration
    static class RedisSubstitute {

        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }
    }
}
