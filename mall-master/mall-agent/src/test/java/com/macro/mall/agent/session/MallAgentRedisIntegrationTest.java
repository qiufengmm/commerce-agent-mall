package com.macro.mall.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.macro.mall.agent.api.ProductCard;
import com.macro.mall.agent.api.SessionMessage;

/**
 * 真实 Redis 上的会话仓储集成测试（计划 Task 11 Step 2）。
 *
 * <p>与 {@code RedisSessionIntegrationTest} 的分工：目标解析守卫（拒绝 0 号库、拒绝回退生产变量、
 * 解析凭证与 TLS）的纯函数断言已由该类覆盖，本类<strong>不复制 gate 逻辑</strong>，
 * 而是直接复用其 package-private {@link RedisSessionIntegrationTest.Target}，
 * 专注于真实 Redis 上的仓储行为。
 *
 * <p>安全边界：
 * <ul>
 *   <li>连接来源<strong>只</strong>是 {@code MALL_AGENT_TEST_REDIS_URL}，绝不回退到生产
 *       {@code MALL_AGENT_REDIS_URL}；</li>
 *   <li>必须显式指定非 0 号库（{@code /1} 起），拒绝 {@code /0} 与缺省库；</li>
 *   <li>只有未配置 {@code MALL_AGENT_TEST_REDIS_URL} 时才通过 {@code assumeTrue} 明确跳过；
 *       显式配置但非法或非本机 loopback 时，复用自 {@code RedisSessionIntegrationTest} 的
 *       gate 解析会抛出 {@link IllegalArgumentException} 明确失败，而不是被静默跳过；</li>
 *   <li>跳过判断发生在任何连接建立<strong>之前</strong>——连接工厂与
 *       {@link StringRedisTemplate} 都是惰性创建，只在 gate 通过后按需建立；</li>
 *   <li>所有键都带唯一 {@code mall:agent:test:{runId}:} 前缀并逐条登记，清理时只
 *       {@code DELETE} 本测试本次创建过的精确键，绝不使用 {@code FLUSHDB}/{@code FLUSHALL}；</li>
 *   <li>不打印任何地址、库号、用户名或密码；断言失败信息也只包含 key 后缀与业务内容。</li>
 * </ul>
 *
 * <p>覆盖内容：真实 {@link RedisSessionRepository} 的保存/读取、TTL 设置与读取续期，
 * 以及生产 {@link AgentIdentity} 派生出的游客/会员会话键在真实 Redis 上的互不干扰。
 */
class MallAgentRedisIntegrationTest {

    private static final String ENV_TEST_REDIS_URL = "MALL_AGENT_TEST_REDIS_URL";
    private static final String TEST_KEY_PREFIX = "mall:agent:test:";
    private static final String SESSION_ID = "2dc7b03e-7368-4d6a-a8ef-b0ea16f6c92c";
    private static final int TTL_SECONDS = 30;
    /** 压小后的既有 TTL：留足 10 秒余量，避免在慢机器上键于读取续期前过期而偶发变红。 */
    private static final int REDUCED_TTL_SECONDS = 10;
    private static final String SKIP_REASON =
            "未配置 " + ENV_TEST_REDIS_URL + "，跳过真实 Redis 集成测试";

    private final List<String> createdKeys = new ArrayList<>();

    private RedisSessionIntegrationTest.Target target;
    private String prefix;
    private StringRedisTemplate template;
    private LettuceConnectionFactory connectionFactory;

    /** 只做纯函数 gate 解析与键前缀生成，不建立任何连接。 */
    @BeforeEach
    void resolveTestTargetWithoutConnecting() {
        target = RedisSessionIntegrationTest.Target.resolve(System::getenv).orElse(null);
        prefix = TEST_KEY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ":";
        createdKeys.clear();
    }

    /** 只删除本测试本次创建过的精确键，随后关闭连接工厂；任何情况下都不 flush。 */
    @AfterEach
    void cleanUpOnlyKeysCreatedByThisTest() {
        try {
            if (template != null) {
                for (String key : createdKeys) {
                    template.delete(key);
                }
                template = null;
            }
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
                connectionFactory = null;
            }
        }
    }

    @Test
    @DisplayName("真实 Redis 上保存并读取游客会话，写入时带配置的 TTL")
    void savesAndLoadsGuestSessionWithConfiguredTtl() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key(AgentIdentity.guest(SESSION_ID).sessionKey());
        RedisSessionRepository repository = repository();

        repository.save(guestKey, new SessionSnapshot(
                List.of(new SessionMessage("user", "有什么手机"),
                        new SessionMessage("assistant", "为您找到三款")),
                List.of(card())));

        SessionSnapshot loaded = repository.load(guestKey);
        assertThat(loaded.messages()).extracting(SessionMessage::content)
                .containsExactly("有什么手机", "为您找到三款");
        assertThat(loaded.products()).extracting(ProductCard::id).containsExactly(27L);
        assertThat(redis().getExpire(guestKey)).isBetween(1L, (long) TTL_SECONDS);
    }

    @Test
    @DisplayName("真实 Redis 上读取会续期 TTL，而不是把键读成剩余极短寿命")
    void loadRenewsTtlInsteadOfOnlyReading() {
        assumeTrue(target != null, SKIP_REASON);
        String guestKey = key(AgentIdentity.guest(SESSION_ID).sessionKey());
        RedisSessionRepository repository = repository();
        repository.save(guestKey, snapshot("有什么手机"));

        redis().expire(guestKey, Duration.ofSeconds(REDUCED_TTL_SECONDS));
        assertThat(redis().getExpire(guestKey)).isLessThanOrEqualTo((long) REDUCED_TTL_SECONDS);

        repository.load(guestKey);

        assertThat(redis().getExpire(guestKey)).isGreaterThan((long) REDUCED_TTL_SECONDS);
    }

    @Test
    @DisplayName("真实 Redis 上同一 sessionId 的游客与会员键互不影响")
    void guestAndMemberSessionKeysAreIsolatedInRealRedis() {
        assumeTrue(target != null, SKIP_REASON);
        AgentIdentity guestIdentity = AgentIdentity.guest(SESSION_ID);
        AgentIdentity memberIdentity = AgentIdentity.member(7, SESSION_ID);
        assertThat(guestIdentity.sessionKey()).isNotEqualTo(memberIdentity.sessionKey());

        String guestKey = key(guestIdentity.sessionKey());
        String memberKey = key(memberIdentity.sessionKey());
        RedisSessionRepository repository = repository();
        repository.save(guestKey, snapshot("游客问题"));
        repository.save(memberKey, snapshot("会员问题"));

        assertThat(repository.load(guestKey).messages()).extracting(SessionMessage::content)
                .containsExactly("游客问题");
        assertThat(repository.load(memberKey).messages()).extracting(SessionMessage::content)
                .containsExactly("会员问题");

        // 删除会员键不影响游客键：两者是彼此独立的键空间
        repository.delete(memberKey);
        assertThat(redis().hasKey(memberKey)).isFalse();
        assertThat(repository.load(guestKey).messages()).extracting(SessionMessage::content)
                .containsExactly("游客问题");
    }

    // ------------------------------------------------------------------ //
    // 连接与辅助：只在 gate 通过后被调用
    // ------------------------------------------------------------------ //

    private RedisSessionRepository repository() {
        return new RedisSessionRepository(redis(), TTL_SECONDS, 20);
    }

    private StringRedisTemplate redis() {
        if (template == null) {
            connectionFactory = connect(target);
            template = new StringRedisTemplate(connectionFactory);
            template.afterPropertiesSet();
        }
        return template;
    }

    private static LettuceConnectionFactory connect(RedisSessionIntegrationTest.Target target) {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(target.host(), target.port());
        standalone.setDatabase(target.database());
        if (target.username() != null) {
            standalone.setUsername(target.username());
        }
        if (target.password() != null) {
            standalone.setPassword(RedisPassword.of(target.password()));
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5));
        LettuceClientConfiguration clientConfiguration =
                target.ssl() ? builder.useSsl().build() : builder.build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, clientConfiguration);
        factory.afterPropertiesSet();
        return factory;
    }

    private String key(String suffix) {
        String key = prefix + suffix;
        createdKeys.add(key);
        return key;
    }

    private static SessionSnapshot snapshot(String content) {
        return new SessionSnapshot(List.of(new SessionMessage("user", content)), List.of());
    }

    private static ProductCard card() {
        return new ProductCard(
                27L,
                "示例手机 B",
                "http://localhost:9000/mall/example-27.jpg",
                "2999.00",
                "示例副标题 B",
                "IN_STOCK",
                112,
                "/pages/product/product?id=27");
    }
}
